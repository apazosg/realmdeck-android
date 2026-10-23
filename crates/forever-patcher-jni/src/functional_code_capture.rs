//! Opt-in, read-only capture of two fixed ARM64 `.text` windows.
//!
//! This module is intentionally not part of the normal image comparator.  It
//! has no caller-selected PID, RVA, length, or output path.  A lease owns one
//! held source fd and its source hash; a capture is committed only after the
//! process identity, source metadata, and private RX map coverage have been
//! checked again.

use super::{ReadOnlyMemory, Target};
use forever_patcher::MemoryAccess;
use serde_json::{json, Value};
use std::fs::{File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt};
use std::os::unix::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard, TryLockError};
use std::time::{Duration, Instant};
use std::ffi::CString;

pub(super) const SOURCE_SIZE: u64 = 94_286_544;
pub(super) const SOURCE_SHA256: &str = "d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59";
const MAX_HEADER: usize = 64 * 1024;
const MAX_MAPS: usize = 2 * 1024 * 1024;
const MAX_MAP_ENTRIES: usize = 4096;
const MAX_CANDIDATES: usize = 1024;
const MAX_COVERAGE_ENTRIES: usize = 32;
const MAX_METADATA: usize = 64 * 1024;
const MAX_ATTEMPTS: u32 = 64;
const SNAPSHOT_BYTES: usize = 0x4000 * 2;
const MAX_SNAPSHOTS: u8 = 2;
const FIRST_SNAPSHOT_AFTER: Duration = Duration::from_secs(20);
const SECOND_SNAPSHOT_AFTER: Duration = Duration::from_secs(60);
const EARLY_NOT_READY: &str = "early candidate guard found no validated candidate; private-RX body versus header-not-found is not distinguishable within the bounded lookup";
// In the app's Android namespace /data/user/0 is a root-owned symlink.
// Use the user-0 app-data anchor directly; retain the no-symlink walk and
// fixed package/relative output authority. run-as collects the same files.
const OUTPUT_ROOT: &str = "/data/data/com.wowforever.arm64probe/files/functional-poc/captures";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) struct CodeWindow {
    pub(super) name: &'static str,
    pub(super) rva: u64,
    pub(super) length: usize,
}

pub(super) const WINDOWS: [CodeWindow; 2] = [
    CodeWindow { name: "text-223c000", rva: 0x223c000, length: 0x4000 },
    CodeWindow { name: "text-37b0000", rva: 0x37b0000, length: 0x4000 },
];

#[derive(Clone, Debug, PartialEq, Eq)]
struct FileIdentity {
    size: u64,
    mode: u32,
    dev: u64,
    ino: u64,
    mtime: i64,
    mtime_nsec: i64,
    ctime: i64,
    ctime_nsec: i64,
    nlink: u64,
    uid: u32,
    gid: u32,
}

impl FileIdentity {
    fn from_metadata(metadata: &std::fs::Metadata) -> Self {
        Self {
            size: metadata.len(),
            mode: metadata.mode(),
            dev: metadata.dev(),
            ino: metadata.ino(),
            mtime: metadata.mtime(),
            mtime_nsec: metadata.mtime_nsec(),
            ctime: metadata.ctime(),
            ctime_nsec: metadata.ctime_nsec(),
            nlink: metadata.nlink(),
            uid: metadata.uid(),
            gid: metadata.gid(),
        }
    }

    fn regular(&self) -> bool {
        self.mode & libc::S_IFMT as u32 == libc::S_IFREG as u32 && self.nlink > 0
    }
}

struct HeldSource {
    file: Mutex<File>,
    identity: FileIdentity,
    sha256: String,
    layout: PeLayout,
}

impl HeldSource {
    fn open(path: &Path) -> Result<Self, String> {
        let file = OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
            .open(path)
            .map_err(|_| "source file could not be opened read-only".to_string())?;
        let identity = FileIdentity::from_metadata(
            &file.metadata().map_err(|_| "source metadata could not be read".to_string())?,
        );
        if !identity.regular() || identity.size != SOURCE_SIZE {
            return Err("source is not the fixed authenticated regular file".to_string());
        }
        let mut held = Self {
            file: Mutex::new(file),
            identity,
            sha256: String::new(),
            layout: PeLayout::empty(),
        };
        held.sha256 = sha256_held(&held)?;
        if held.sha256 != SOURCE_SHA256 {
            return Err("source SHA-256 does not match the authenticated AA64 build".to_string());
        }
        held.layout = read_source_layout(&held)?;
        validate_source_windows(&held.layout, held.identity.size)?;
        if !held.stable() {
            return Err("source metadata changed while its lease hash was computed".to_string());
        }
        Ok(held)
    }

    fn stable(&self) -> bool {
        self.file
            .lock()
            .ok()
            .and_then(|file| file.metadata().ok())
            .map(|metadata| FileIdentity::from_metadata(&metadata) == self.identity)
            .unwrap_or(false)
    }

    fn read_at(&self, offset: u64, output: &mut [u8]) -> io::Result<()> {
        let length = u64::try_from(output.len()).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "source read overflow"))?;
        if offset.checked_add(length).filter(|end| *end <= self.identity.size).is_none() {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "source read is outside the held file"));
        }
        let mut file = self.file.lock().map_err(|_| io::Error::other("source fd mutex poisoned"))?;
        file.seek(SeekFrom::Start(offset))?;
        file.read_exact(output)
    }
}

fn sha256_held(source: &HeldSource) -> Result<String, String> {
    let mut file = source.file.lock().map_err(|_| "source fd mutex poisoned".to_string())?;
    file.seek(SeekFrom::Start(0)).map_err(|_| "source hash seek failed".to_string())?;
    let mut digest = Sha256::new();
    let mut bytes_read = 0u64;
    let mut buffer = [0u8; 64 * 1024];
    loop {
        if bytes_read > SOURCE_SIZE { return Err("source grew beyond the bounded hash input".to_string()); }
        let remaining = SOURCE_SIZE + 1 - bytes_read;
        let read_len = buffer.len().min(usize::try_from(remaining).unwrap_or(buffer.len()));
        if read_len == 0 { break; }
        let count = file.read(&mut buffer[..read_len]).map_err(|_| "source hash read failed".to_string())?;
        if count == 0 { break; }
        bytes_read = bytes_read.saturating_add(count as u64);
        if bytes_read > SOURCE_SIZE { return Err("source grew beyond the bounded hash input".to_string()); }
        digest.update(&buffer[..count]);
    }
    file.seek(SeekFrom::Start(0)).map_err(|_| "source rewind failed".to_string())?;
    if bytes_read != SOURCE_SIZE {
        return Err("source size changed during its lease hash".to_string());
    }
    Ok(digest.finish())
}

fn sha256_bytes(bytes: &[u8]) -> String {
    let mut digest = Sha256::new();
    digest.update(bytes);
    digest.finish()
}

#[derive(Clone, Debug)]
struct PeSection {
    name: [u8; 8],
    virtual_size: u32,
    virtual_address: u32,
    raw_size: u32,
    raw_pointer: u32,
    characteristics: u32,
}

#[derive(Clone, Debug)]
struct PeLayout {
    machine: u16,
    timestamp: u32,
    section_count: u16,
    optional_size: u16,
    source_image_base: u64,
    entry_rva: u32,
    image_size: u32,
    header_size: u32,
    sections: Vec<PeSection>,
}

impl PeLayout {
    fn empty() -> Self {
        Self { machine: 0, timestamp: 0, section_count: 0, optional_size: 0, source_image_base: 0, entry_rva: 0, image_size: 0, header_size: 0, sections: Vec::new() }
    }

    fn parse(bytes: &[u8], file_size: Option<u64>) -> Result<Self, String> {
        if bytes.len() < 0x40 || &bytes[..2] != b"MZ" { return Err("candidate is not MZ".to_string()); }
        let pe = usize::try_from(read_u32(bytes, 0x3c)?).map_err(|_| "PE offset overflow".to_string())?;
        let coff = pe.checked_add(4).ok_or_else(|| "COFF offset overflow".to_string())?;
        if coff.checked_add(20).is_none_or(|end| end > bytes.len()) || bytes.get(pe..coff) != Some(b"PE\0\0") {
            return Err("PE header is outside the bounded read".to_string());
        }
        let machine = read_u16(bytes, coff)?;
        let section_count = read_u16(bytes, coff + 2)?;
        let timestamp = read_u32(bytes, coff + 4)?;
        let optional_size = read_u16(bytes, coff + 16)?;
        if machine != 0xaa64 || section_count == 0 || section_count > 96 || optional_size < 112 {
            return Err("candidate is not bounded PE32+ AA64".to_string());
        }
        let optional = coff + 20;
        let optional_end = optional.checked_add(optional_size as usize).ok_or_else(|| "optional header overflow".to_string())?;
        if optional_end > bytes.len() || read_u16(bytes, optional)? != 0x20b { return Err("PE32+ optional header is incomplete".to_string()); }
        let source_image_base = read_u64(bytes, optional + 24)?;
        let image_size = read_u32(bytes, optional + 56)?;
        let header_size = read_u32(bytes, optional + 60)?;
        if image_size == 0 || header_size == 0 || header_size as usize > MAX_HEADER { return Err("PE image/header bound exceeded".to_string()); }
        let table = optional_end;
        let table_len = (section_count as usize).checked_mul(40).ok_or_else(|| "section table overflow".to_string())?;
        if table.checked_add(table_len).is_none_or(|end| end > bytes.len() || end > MAX_HEADER) { return Err("section table is outside bounded header".to_string()); }
        let mut sections = Vec::with_capacity(section_count as usize);
        for index in 0..section_count as usize {
            let offset = table + index * 40;
            let mut name = [0u8; 8]; name.copy_from_slice(&bytes[offset..offset + 8]);
            let section = PeSection {
                name,
                virtual_size: read_u32(bytes, offset + 8)?,
                virtual_address: read_u32(bytes, offset + 12)?,
                raw_size: read_u32(bytes, offset + 16)?,
                raw_pointer: read_u32(bytes, offset + 20)?,
                characteristics: read_u32(bytes, offset + 36)?,
            };
            let image_end = u64::from(section.virtual_address).checked_add(u64::from(section.virtual_size.max(section.raw_size))).ok_or_else(|| "section image range overflow".to_string())?;
            if image_end > u64::from(image_size) { return Err("section exceeds SizeOfImage".to_string()); }
            if let Some(size) = file_size {
                let raw_end = u64::from(section.raw_pointer).checked_add(u64::from(section.raw_size)).ok_or_else(|| "section raw range overflow".to_string())?;
                if raw_end > size { return Err("section raw range exceeds source".to_string()); }
            }
            sections.push(section);
        }
        Ok(Self {
            machine,
            timestamp,
            section_count,
            optional_size,
            source_image_base,
            entry_rva: read_u32(bytes, optional + 16)?,
            image_size,
            header_size,
            sections,
        })
    }

    fn matches(&self, other: &Self) -> bool {
        self.machine == other.machine && self.timestamp == other.timestamp && self.section_count == other.section_count
            && self.optional_size == other.optional_size && self.entry_rva == other.entry_rva
            && self.image_size == other.image_size && self.header_size == other.header_size
            && self.sections.iter().zip(&other.sections).all(|(a, b)| a.name == b.name && a.virtual_size == b.virtual_size
                && a.virtual_address == b.virtual_address && a.raw_size == b.raw_size && a.raw_pointer == b.raw_pointer
                && a.characteristics == b.characteristics)
    }
}

fn read_source_layout(source: &HeldSource) -> Result<PeLayout, String> {
    let mut initial = [0u8; 0x40];
    source.read_at(0, &mut initial).map_err(|_| "source DOS header read failed".to_string())?;
    let pe = usize::try_from(read_u32(&initial, 0x3c)?).map_err(|_| "source PE offset overflow".to_string())?;
    if pe > MAX_HEADER { return Err("source PE offset exceeds bound".to_string()); }
    let prefix = pe.checked_add(24).ok_or_else(|| "source COFF prefix overflow".to_string())?;
    if prefix > MAX_HEADER { return Err("source COFF prefix exceeds bound".to_string()); }
    let mut prefix_bytes = vec![0u8; prefix.max(initial.len())];
    source.read_at(0, &mut prefix_bytes).map_err(|_| "source COFF header read failed".to_string())?;
    let optional_size = read_u16(&prefix_bytes, pe + 20)? as usize;
    let sections = read_u16(&prefix_bytes, pe + 6)? as usize;
    let length = pe.checked_add(24).and_then(|value| value.checked_add(optional_size)).and_then(|value| value.checked_add(sections.checked_mul(40)?)).ok_or_else(|| "source PE header overflow".to_string())?;
    if length > MAX_HEADER { return Err("source PE header exceeds bound".to_string()); }
    let mut bytes = vec![0u8; length.max(initial.len())];
    source.read_at(0, &mut bytes).map_err(|_| "source section table read failed".to_string())?;
    PeLayout::parse(&bytes, Some(source.identity.size))
}

fn validate_source_windows(layout: &PeLayout, file_size: u64) -> Result<(), String> {
    let text = layout.sections.iter().find(|section| &section.name[..5] == b".text" && section.name[5..].iter().all(|byte| *byte == 0))
        .ok_or_else(|| "authenticated source has no exact executable .text section".to_string())?;
    if text.characteristics & 0x2000_0000 == 0 { return Err("authenticated .text section is not executable".to_string()); }
    for window in WINDOWS {
        let end = window.rva.checked_add(window.length as u64).ok_or_else(|| "fixed window overflow".to_string())?;
        let section_end = u64::from(text.virtual_address).checked_add(u64::from(text.virtual_size.max(text.raw_size))).ok_or_else(|| "text image range overflow".to_string())?;
        let raw_end = u64::from(text.virtual_address).checked_add(u64::from(text.raw_size)).ok_or_else(|| "text raw range overflow".to_string())?;
        let raw_offset = u64::from(text.raw_pointer).checked_add(window.rva.saturating_sub(u64::from(text.virtual_address))).ok_or_else(|| "text raw offset overflow".to_string())?;
        if window.rva < u64::from(text.virtual_address) || end > section_end || end > raw_end || raw_offset.checked_add(window.length as u64).is_none_or(|value| value > file_size) {
            return Err(format!("fixed capture window {} is not fully raw-backed executable .text", window.name));
        }
    }
    Ok(())
}

fn read_u16(bytes: &[u8], offset: usize) -> Result<u16, String> {
    let value = bytes.get(offset..offset.checked_add(2).ok_or_else(|| "u16 offset overflow".to_string())?).ok_or_else(|| "u16 outside bounded header".to_string())?;
    Ok(u16::from_le_bytes([value[0], value[1]]))
}
fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, String> {
    let value = bytes.get(offset..offset.checked_add(4).ok_or_else(|| "u32 offset overflow".to_string())?).ok_or_else(|| "u32 outside bounded header".to_string())?;
    Ok(u32::from_le_bytes(value.try_into().map_err(|_| "invalid u32".to_string())?))
}
fn read_u64(bytes: &[u8], offset: usize) -> Result<u64, String> {
    let value = bytes.get(offset..offset.checked_add(8).ok_or_else(|| "u64 offset overflow".to_string())?).ok_or_else(|| "u64 outside bounded header".to_string())?;
    Ok(u64::from_le_bytes(value.try_into().map_err(|_| "invalid u64".to_string())?))
}
#[derive(Clone, Debug, PartialEq, Eq)]
struct MapEntry {
    start: u64,
    end: u64,
    perms: String,
    offset: u64,
    device: String,
    inode: u64,
    path_kind: &'static str,
}

fn map_path_kind(path: Option<&str>, device: &str, inode: u64) -> &'static str {
    match path {
        None => "anonymous",
        Some(path) if path == "/dev" || path.starts_with("/dev/") => "device",
        // Linux PR_SET_VMA_ANON_NAME labels are still private anonymous
        // mappings, not pseudo-file backing. Never retain the label itself.
        Some(path) if path.starts_with("[anon:") && inode == 0 && device == "00:00" => "anonymous",
        Some(path) if path.starts_with('[') => "pseudo",
        Some(_) => "file",
    }
}

fn maps_for_process(memory: &ReadOnlyMemory) -> Result<Vec<MapEntry>, String> {
    let bytes = super::ProcFs::read_file(memory.target.pid, "maps", MAX_MAPS, &memory.budget).map_err(|_| "runtime maps could not be read completely".to_string())?;
    if bytes.is_empty() || !bytes.ends_with(b"\n") { return Err("runtime maps are empty or truncated".to_string()); }
    let text = std::str::from_utf8(&bytes).map_err(|_| "runtime maps are not UTF-8".to_string())?;
    let mut result = Vec::new();
    for line in text.lines() {
        let mut fields = line.split_whitespace();
        let range = fields.next().ok_or_else(|| "runtime map range missing".to_string())?;
        let perms = fields.next().ok_or_else(|| "runtime map permissions missing".to_string())?;
        let offset = fields.next().ok_or_else(|| "runtime map offset missing".to_string())?;
        let device = fields.next().ok_or_else(|| "runtime map device missing".to_string())?;
        let inode = fields.next().ok_or_else(|| "runtime map inode missing".to_string())?;
        let (start, end) = range.split_once('-').ok_or_else(|| "runtime map range malformed".to_string()).and_then(|(a, b)| Ok((u64::from_str_radix(a, 16).map_err(|_| "runtime map start malformed".to_string())?, u64::from_str_radix(b, 16).map_err(|_| "runtime map end malformed".to_string())?)))?;
        if end <= start || perms.len() != 4 || u64::from_str_radix(offset, 16).is_err() || inode.parse::<u64>().is_err() {
            return Err("runtime map metadata malformed".to_string());
        }
        let permission_bytes = perms.as_bytes();
        if !permission_bytes.iter().take(3).all(|byte| matches!(byte, b'r' | b'w' | b'x' | b'-')) || !matches!(permission_bytes[3], b'p' | b's') || device.split_once(':').is_none_or(|(a, b)| a.is_empty() || b.is_empty() || u64::from_str_radix(a, 16).is_err() || u64::from_str_radix(b, 16).is_err()) {
            return Err("runtime map permissions or device malformed".to_string());
        }
        let path_kind = map_path_kind(fields.next(), device, inode.parse().unwrap());
        result.push(MapEntry { start, end, perms: perms.to_string(), offset: u64::from_str_radix(offset, 16).unwrap(), device: device.to_string(), inode: inode.parse().unwrap(), path_kind });
        if result.len() > MAX_MAP_ENTRIES { return Err("runtime map entry bound exceeded".to_string()); }
    }
    Ok(result)
}

fn private_readable(map: &MapEntry) -> bool { map.perms.as_bytes()[0] == b'r' && map.perms.as_bytes()[3] == b'p' && map.path_kind != "device" }
fn private_rx(map: &MapEntry) -> bool { private_readable(map) && map.perms == "r-xp" }

fn coverage(maps: &[MapEntry], address: u64, length: usize) -> Result<Vec<MapEntry>, String> {
    let end = address.checked_add(length as u64).ok_or_else(|| "runtime window address overflow".to_string())?;
    let mut cursor = address;
    let mut result = Vec::new();
    while cursor < end {
        let map = maps.iter().find(|map| map.start <= cursor && cursor < map.end).ok_or_else(|| "runtime window has a map gap".to_string())?;
        if !private_rx(map) { return Err("runtime window is not private r-xp".to_string()); }
        if result.last() != Some(map) { result.push(map.clone()); }
        cursor = map.end.min(end);
        if result.len() > MAX_COVERAGE_ENTRIES { return Err("runtime coverage metadata bound exceeded".to_string()); }
    }
    Ok(result)
}

fn anonymous_backing(map: &MapEntry) -> bool { map.path_kind == "anonymous" && map.inode == 0 && map.device == "00:00" }

fn clipped_offset(map: &MapEntry, clipped_start: u64) -> Result<u64, String> {
    let delta = clipped_start.checked_sub(map.start).ok_or_else(|| "runtime window backing offset underflow".to_string())?;
    if anonymous_backing(map) {
        Ok(0)
    } else if map.path_kind == "file" || (map.inode != 0 && map.device != "00:00") {
        map.offset.checked_add(delta).ok_or_else(|| "runtime window backing offset overflow".to_string())
    } else {
        Err("runtime window backing metadata is unsupported".to_string())
    }
}

fn can_coalesce(left: &MapEntry, right: &MapEntry) -> bool {
    if left.end != right.start || left.perms != right.perms || left.device != right.device || left.inode != right.inode || left.path_kind != right.path_kind {
        return false;
    }
    if anonymous_backing(left) && anonymous_backing(right) {
        return left.offset == 0 && right.offset == 0;
    }
    left.end.checked_sub(left.start).and_then(|length| left.offset.checked_add(length)) == Some(right.offset)
}

fn metadata_private_readable(map: &MapEntry) -> bool {
    matches!(map.perms.as_str(), "r--p" | "rw-p" | "r-xp") && map.path_kind != "device"
}

fn normalized_window_coverage_with(maps: &[MapEntry], address: u64, length: usize, metadata_only: bool) -> Result<Vec<MapEntry>, String> {
    let end = address.checked_add(length as u64).ok_or_else(|| "runtime window address overflow".to_string())?;
    let mut cursor = address;
    let mut result = Vec::new();
    while cursor < end {
        let map = maps.iter().find(|map| map.start <= cursor && cursor < map.end).ok_or_else(|| "runtime window has a map gap".to_string())?;
        if !(if metadata_only { metadata_private_readable(map) } else { private_rx(map) }) {
            return Err(if metadata_only { "runtime metadata window is not private readable" } else { "runtime window is not private r-xp" }.to_string());
        }
        let clipped_end = map.end.min(end);
        let clipped = MapEntry {
            start: cursor,
            end: clipped_end,
            perms: map.perms.clone(),
            offset: clipped_offset(map, cursor)?,
            device: map.device.clone(),
            inode: map.inode,
            path_kind: map.path_kind,
        };
        if let Some(previous) = result.last_mut() {
            if can_coalesce(previous, &clipped) {
                previous.end = clipped.end;
            } else {
                result.push(clipped);
            }
        } else {
            result.push(clipped);
        }
        if result.len() > MAX_COVERAGE_ENTRIES { return Err("runtime coverage metadata bound exceeded".to_string()); }
        cursor = clipped_end;
    }
    Ok(result)
}

fn normalized_window_coverage(maps: &[MapEntry], address: u64, length: usize) -> Result<Vec<MapEntry>, String> {
    normalized_window_coverage_with(maps, address, length, false)
}

fn normalized_metadata_window_coverage(maps: &[MapEntry], address: u64, length: usize) -> Result<Vec<MapEntry>, String> {
    normalized_window_coverage_with(maps, address, length, true)
}

fn window_coverage(maps: &[MapEntry], base: u64) -> Result<Vec<MapEntry>, String> {
    let mut all = Vec::new();
    for window in WINDOWS {
        let address = base.checked_add(window.rva).ok_or_else(|| "runtime window address overflow".to_string())?;
        all.extend(normalized_window_coverage(maps, address, window.length)?);
        if all.len() > MAX_COVERAGE_ENTRIES { return Err("runtime coverage metadata bound exceeded".to_string()); }
    }
    Ok(all)
}

fn normalized_metadata_coverage(maps: &[MapEntry], base: u64) -> Result<Vec<MapEntry>, String> {
    let mut all = Vec::new();
    for window in WINDOWS {
        let address = base.checked_add(window.rva).ok_or_else(|| "runtime window address overflow".to_string())?;
        all.extend(normalized_metadata_window_coverage(maps, address, window.length)?);
        if all.len() > MAX_COVERAGE_ENTRIES { return Err("runtime coverage metadata bound exceeded".to_string()); }
    }
    Ok(all)
}

fn read_memory<M: MemoryAccess + ?Sized>(memory: &M, maps: &[MapEntry], address: u64, output: &mut [u8]) -> io::Result<()> {
    let end = address.checked_add(output.len() as u64).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "runtime read overflow"))?;
    let mut cursor = address;
    let mut offset = 0usize;
    while cursor < end {
        let map = maps.iter().find(|map| map.start <= cursor && cursor < map.end && private_readable(map)).ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "runtime read has private-map gap"))?;
        let count = usize::try_from(map.end.min(end) - cursor).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "runtime segment overflow"))?;
        memory.read(cursor, &mut output[offset..offset + count])?;
        cursor += count as u64;
        offset += count;
    }
    Ok(())
}

fn read_runtime_layout<M: MemoryAccess + ?Sized>(memory: &M, maps: &[MapEntry], base: u64) -> Result<PeLayout, String> {
    let mut initial = [0u8; 0x40];
    read_memory(memory, maps, base, &mut initial).map_err(|_| "runtime PE header read failed".to_string())?;
    let pe = usize::try_from(read_u32(&initial, 0x3c)?).map_err(|_| "runtime PE offset overflow".to_string())?;
    if pe > MAX_HEADER { return Err("runtime PE offset exceeds bound".to_string()); }
    let prefix = pe.checked_add(24).ok_or_else(|| "runtime COFF prefix overflow".to_string())?;
    if prefix > MAX_HEADER { return Err("runtime COFF prefix exceeds bound".to_string()); }
    let mut prefix_bytes = vec![0u8; prefix.max(initial.len())];
    read_memory(memory, maps, base, &mut prefix_bytes).map_err(|_| "runtime COFF read failed".to_string())?;
    let optional_size = read_u16(&prefix_bytes, pe + 20)? as usize;
    let sections = read_u16(&prefix_bytes, pe + 6)? as usize;
    let length = pe.checked_add(24).and_then(|value| value.checked_add(optional_size)).and_then(|value| value.checked_add(sections.checked_mul(40)?)).ok_or_else(|| "runtime PE header overflow".to_string())?;
    if length > MAX_HEADER { return Err("runtime PE header exceeds bound".to_string()); }
    let mut bytes = vec![0u8; length.max(initial.len())];
    read_memory(memory, maps, base, &mut bytes).map_err(|_| "runtime section table read failed".to_string())?;
    PeLayout::parse(&bytes, None)
}

#[derive(Clone, Debug)]
struct Candidate {
    base: u64,
    layout: PeLayout,
    coverage: Vec<MapEntry>,
}

#[derive(Clone, Debug)]
struct EarlyCandidate {
    base: u64,
    layout: PeLayout,
    coverage: Vec<MapEntry>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum EarlyReadinessClass {
    MetadataReady,
    PreferredBaseUnmapped,
    PreferredHeaderReadFailed,
    PreferredHeaderLayoutMismatch,
    HeaderMatchedPayloadNonRx,
    HeaderMatchedOtherCandidateRejection,
}

impl EarlyReadinessClass {
    fn as_str(self) -> &'static str {
        match self {
            Self::MetadataReady => "metadata-ready",
            Self::PreferredBaseUnmapped => "preferred-base-unmapped",
            Self::PreferredHeaderReadFailed => "preferred-header-read-failed",
            Self::PreferredHeaderLayoutMismatch => "preferred-header-layout-mismatch",
            Self::HeaderMatchedPayloadNonRx => "header-matched-payload-non-rx",
            Self::HeaderMatchedOtherCandidateRejection => "header-matched-other-candidate-rejection",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum EarlyWindowPermission {
    Unknown,
    Rx,
    Rw,
    ReadOnly,
    Shared,
    Device,
    Gap,
    Other,
}

impl EarlyWindowPermission {
    fn as_str(self) -> &'static str {
        match self {
            Self::Unknown => "unknown",
            Self::Rx => "r-xp",
            Self::Rw => "rw",
            Self::ReadOnly => "r--",
            Self::Shared => "shared",
            Self::Device => "device",
            Self::Gap => "gap",
            Self::Other => "other",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct EarlyWindowMetadata {
    name: &'static str,
    length: usize,
    permission: EarlyWindowPermission,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct EarlyReadinessMetadata {
    classification: EarlyReadinessClass,
    windows: [EarlyWindowMetadata; 2],
    current_header_observed: bool,
    base_pinned: bool,
}

impl EarlyReadinessMetadata {
    fn new(classification: EarlyReadinessClass, permissions: [EarlyWindowPermission; 2], current_header_observed: bool, base_pinned: bool) -> Self {
        Self {
            classification,
            windows: [
                EarlyWindowMetadata { name: WINDOWS[0].name, length: WINDOWS[0].length, permission: permissions[0] },
                EarlyWindowMetadata { name: WINDOWS[1].name, length: WINDOWS[1].length, permission: permissions[1] },
            ],
            current_header_observed,
            base_pinned,
        }
    }

    fn unknown(classification: EarlyReadinessClass) -> Self { Self::new(classification, [EarlyWindowPermission::Unknown; 2], false, false) }

    fn metadata_ready(permissions: [EarlyWindowPermission; 2], current_header_observed: bool, base_pinned: bool) -> Self {
        Self::new(EarlyReadinessClass::MetadataReady, permissions, current_header_observed, base_pinned)
    }

    fn json(self) -> Value {
        json!({
            "classification": self.classification.as_str(),
            "windows": self.windows.iter().map(|window| json!({
                "name": window.name,
                "length": window.length,
                "permission": window.permission.as_str(),
            })).collect::<Vec<_>>(),
            "currentHeaderObserved": self.current_header_observed,
            "basePinned": self.base_pinned,
        })
    }
}

fn early_window_permission(maps: &[MapEntry], base: u64, window: CodeWindow) -> EarlyWindowPermission {
    let Some(mut cursor) = base.checked_add(window.rva) else { return EarlyWindowPermission::Gap };
    let Some(end) = cursor.checked_add(window.length as u64) else { return EarlyWindowPermission::Gap };
    let mut classes = Vec::new();
    while cursor < end {
        let Some(map) = maps.iter().find(|map| map.start <= cursor && cursor < map.end) else { return EarlyWindowPermission::Gap };
        let class = if map.path_kind == "device" {
            EarlyWindowPermission::Device
        } else if map.perms.as_bytes().get(3) == Some(&b's') {
            EarlyWindowPermission::Shared
        } else if map.perms == "r-xp" {
            EarlyWindowPermission::Rx
        } else if map.perms.starts_with("rw") {
            EarlyWindowPermission::Rw
        } else if map.perms.starts_with("r--") {
            EarlyWindowPermission::ReadOnly
        } else {
            EarlyWindowPermission::Other
        };
        classes.push(class);
        cursor = map.end.min(end);
    }
    if classes.iter().all(|class| *class == EarlyWindowPermission::Rx) { EarlyWindowPermission::Rx }
    else if classes.iter().any(|class| *class == EarlyWindowPermission::Device) { EarlyWindowPermission::Device }
    else if classes.iter().any(|class| *class == EarlyWindowPermission::Shared) { EarlyWindowPermission::Shared }
    else if classes.iter().any(|class| *class == EarlyWindowPermission::Rw) { EarlyWindowPermission::Rw }
    else if classes.iter().all(|class| *class == EarlyWindowPermission::ReadOnly) { EarlyWindowPermission::ReadOnly }
    else { EarlyWindowPermission::Other }
}

fn diagnose_empty_early_lookup<M: MemoryAccess + ?Sized>(memory: &M, maps: &[MapEntry], source: &PeLayout) -> EarlyReadinessMetadata {
    let preferred = source.source_image_base;
    if !maps.iter().any(|map| map.start <= preferred && preferred < map.end && private_readable(map)) {
        return EarlyReadinessMetadata::unknown(EarlyReadinessClass::PreferredBaseUnmapped);
    }
    let runtime = match read_runtime_layout(memory, maps, preferred) {
        Ok(layout) => layout,
        Err(_) => return EarlyReadinessMetadata::unknown(EarlyReadinessClass::PreferredHeaderReadFailed),
    };
    if !source.matches(&runtime) {
        return EarlyReadinessMetadata::unknown(EarlyReadinessClass::PreferredHeaderLayoutMismatch);
    }
    let permissions = [
        early_window_permission(maps, preferred, WINDOWS[0]),
        early_window_permission(maps, preferred, WINDOWS[1]),
    ];
    let classification = if permissions.iter().all(|permission| *permission == EarlyWindowPermission::Rx) {
        EarlyReadinessClass::HeaderMatchedOtherCandidateRejection
    } else {
        EarlyReadinessClass::HeaderMatchedPayloadNonRx
    };
    EarlyReadinessMetadata::new(classification, permissions, true, false)
}

fn candidate_addresses(maps: &[MapEntry], source: &PeLayout) -> Result<Vec<u64>, String> {
    let mut map_starts = maps.iter().filter(|map| private_readable(map)).map(|map| map.start).collect::<Vec<_>>();
    map_starts.sort_unstable();
    map_starts.dedup();
    // PE headers need not be executable. Only the retained .text payload
    // windows require RX; header reads already require private readability.
    let preferred_mapped = maps.iter().any(|map| map.start <= source.source_image_base && source.source_image_base < map.end && private_readable(map));
    let mut addresses = Vec::with_capacity(map_starts.len() + usize::from(preferred_mapped));
    if preferred_mapped { addresses.push(source.source_image_base); }
    addresses.extend(map_starts.into_iter().filter(|address| !preferred_mapped || *address != source.source_image_base));
    if addresses.len() > MAX_CANDIDATES {
        return Err("runtime PE candidate inspection bound exceeded".to_string());
    }
    Ok(addresses)
}

fn find_candidates<M: MemoryAccess + ?Sized>(memory: &M, maps: &[MapEntry], source: &PeLayout) -> Result<Vec<Candidate>, String> {
    let mut result = Vec::new();
    for base in candidate_addresses(maps, source)? {
        let layout = match read_runtime_layout(memory, maps, base) {
            Ok(layout) if source.matches(&layout) => layout,
            _ => continue,
        };
        if let Ok(ranges) = window_coverage(maps, base) {
            result.push(Candidate { base, layout, coverage: ranges });
            if result.len() > MAX_CANDIDATES { return Err("runtime PE candidate bound exceeded".to_string()); }
        }
    }
    Ok(result)
}

fn find_early_header_candidates<M: MemoryAccess + ?Sized>(memory: &M, maps: &[MapEntry], source: &PeLayout) -> Result<Vec<EarlyCandidate>, String> {
    let mut result = Vec::new();
    for base in candidate_addresses(maps, source)? {
        let layout = match read_runtime_layout(memory, maps, base) {
            Ok(layout) if source.matches(&layout) => layout,
            _ => continue,
        };
        let mut ranges = Vec::new();
        let mut valid = true;
        for window in WINDOWS {
            match normalized_metadata_window_coverage(maps, base.checked_add(window.rva).ok_or_else(|| "runtime image address overflow".to_string())?, window.length) {
                Ok(value) => ranges.extend(value),
                Err(_) => { valid = false; break; }
            }
        }
        if valid {
            if ranges.len() > MAX_COVERAGE_ENTRIES { return Err("runtime coverage metadata bound exceeded".to_string()); }
            result.push(EarlyCandidate { base, layout, coverage: ranges });
            if result.len() > MAX_CANDIDATES { return Err("runtime PE candidate bound exceeded".to_string()); }
        }
    }
    Ok(result)
}

fn historical_backing_matches(current: &[MapEntry], historical: &[MapEntry]) -> bool {
    fn canonical(entries: &[MapEntry]) -> Option<Vec<MapEntry>> {
        let mut result: Vec<MapEntry> = Vec::new();
        for entry in entries {
            if !metadata_private_readable(entry) { return None; }
            if let Some(previous) = result.last_mut() {
                let same_backing = previous.end == entry.start
                    && previous.device == entry.device
                    && previous.inode == entry.inode
                    && previous.path_kind == entry.path_kind
                    && if anonymous_backing(previous) && anonymous_backing(entry) {
                        previous.offset == 0 && entry.offset == 0
                    } else {
                        previous.end.checked_sub(previous.start).and_then(|length| previous.offset.checked_add(length)) == Some(entry.offset)
                    };
                if same_backing {
                    previous.end = entry.end;
                    continue;
                }
            }
            result.push(entry.clone());
        }
        Some(result)
    }
    let (Some(current), Some(historical)) = (canonical(current), canonical(historical)) else { return false };
    current.iter().zip(&historical).all(|(now, old)|
        now.start == old.start && now.end == old.end && now.offset == old.offset
            && now.device == old.device && now.inode == old.inode && now.path_kind == old.path_kind)
        && current.len() == historical.len()
}

fn historical_header_observed<M: MemoryAccess + ?Sized>(memory: &M, maps: &[MapEntry], historical: &HistoricalCandidate) -> Result<bool, String> {
    match read_runtime_layout(memory, maps, historical.base) {
        Ok(layout) if layout.matches(&historical.layout) => Ok(true),
        Ok(_) => Err("historical candidate header layout changed".to_string()),
        Err(_) => Ok(false),
    }
}

fn refresh_historical_early<M: MemoryAccess + ?Sized>(memory: &M, before_maps: &[MapEntry], after_maps: &[MapEntry], historical: &HistoricalCandidate) -> Result<bool, String> {
    let before_metadata = normalized_metadata_coverage(before_maps, historical.base)?;
    if !historical_backing_matches(&before_metadata, &historical.coverage) {
        return Err("historical candidate metadata backing changed".to_string());
    }
    let after_metadata = normalized_metadata_coverage(after_maps, historical.base)?;
    if after_metadata != before_metadata {
        return Err("historical candidate metadata coverage changed during observation".to_string());
    }
    let before_header = historical_header_observed(memory, before_maps, historical)?;
    let after_header = historical_header_observed(memory, after_maps, historical)?;
    Ok(before_header && after_header)
}

// A bounded global lookup is not a successful zero-candidate lookup. It can
// only select the separately validated historical observation path, which
// never claims current module proof. All other errors remain fail-closed.
fn lookup_for_historical_observation(result: Result<Vec<Candidate>, String>, historical_available: bool) -> Result<(Vec<Candidate>, bool), String> {
    match result {
        Ok(candidates) => Ok((candidates, false)),
        Err(error) if error == "runtime PE candidate inspection bound exceeded" && historical_available => Ok((Vec::new(), true)),
        Err(error) => Err(error),
    }
}

fn map_json(map: &MapEntry) -> Value {
    json!({"start": map.start, "end": map.end, "permissions": map.perms, "device": map.device, "inode": map.inode, "offset": map.offset, "pathKind": map.path_kind})
}

#[derive(Clone, Debug)]
struct HistoricalCandidate {
    base: u64,
    layout: PeLayout,
    coverage: Vec<MapEntry>,
}

#[derive(Debug)]
struct CaptureState {
    attempts: u32,
    successes: u8,
    in_flight: bool,
    early_guard_failed: bool,
    early_readiness: Option<EarlyReadinessMetadata>,
    historical: Option<HistoricalCandidate>,
}

pub(super) struct CodeCaptureLease {
    source: HeldSource,
    nonce: String,
    output_dir: PathBuf,
    started: Instant,
    state: Arc<Mutex<CaptureState>>,
    operation: Mutex<()>,
}

impl CodeCaptureLease {
    pub(super) fn begin(path: &Path, nonce: &str) -> Result<Self, String> {
        Self::begin_at(path, nonce, Path::new(OUTPUT_ROOT))
    }

    fn begin_at(path: &Path, nonce: &str, root: &Path) -> Result<Self, String> {
        if nonce.is_empty() || nonce.len() > 64 || !nonce.bytes().all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_')) {
            return Err("capture nonce is not a bounded path-safe token".to_string());
        }
        let source = HeldSource::open(path)?;
        create_private_dir(root)?;
        let output_dir = root.join(nonce);
        if output_dir.exists() || output_dir.is_symlink() { return Err("capture nonce directory already exists".to_string()); }
        std::fs::create_dir(&output_dir).map_err(|_| "capture nonce directory could not be created".to_string())?;
        std::fs::set_permissions(&output_dir, std::fs::Permissions::from_mode(0o700)).map_err(|_| "capture nonce directory permissions could not be set".to_string())?;
        if let Err(error) = storage_preflight(&output_dir) {
            let _ = std::fs::remove_dir(&output_dir);
            return Err(error);
        }
        Ok(Self { source, nonce: nonce.to_string(), output_dir, started: Instant::now(), state: Arc::new(Mutex::new(CaptureState { attempts: 0, successes: 0, in_flight: false, early_guard_failed: false, early_readiness: None, historical: None })), operation: Mutex::new(()) })
    }
}

fn create_private_dir(path: &Path) -> Result<(), String> {
    let mut current = PathBuf::new();
    for (index, component) in path.components().enumerate() {
        current.push(component.as_os_str());
        if current.is_symlink() {
            let metadata = std::fs::symlink_metadata(&current).map_err(|_| "capture_root_symlink_component_metadata".to_string())?;
            let file_type = metadata.mode() & libc::S_IFMT as u32;
            return Err(format!("capture_root_symlink_component_{index}_mode_{file_type}_uid_{}", metadata.uid()));
        }
        if current.exists() {
            if !current.is_dir() { return Err("capture root is not a directory".to_string()); }
        } else {
            std::fs::create_dir(&current).map_err(|_| "capture root could not be created".to_string())?;
            std::fs::set_permissions(&current, std::fs::Permissions::from_mode(0o700)).map_err(|_| "capture root permissions could not be set".to_string())?;
        }
    }
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o700)).map_err(|_| "capture root permissions could not be set".to_string())
}

fn timing_index(elapsed: Duration, successes: u8) -> Option<u8> {
    if successes == 0 && elapsed >= FIRST_SNAPSHOT_AFTER {
        Some(0)
    } else if successes == 1 && elapsed >= SECOND_SNAPSHOT_AFTER {
        Some(1)
    } else {
        None
    }
}

fn target_metadata(target: &Target) -> Value {
    json!({"pid": target.pid, "startTicks": target.start_ticks, "uid4": target.uid4, "ppid": target.ppid, "cmdlineValidated": true, "ancestryValidated": true, "nonceValidated": true})
}

fn timing_json(state: &CaptureState, status: &str, detail: Option<&str>) -> Value {
    let mut output = json!({
        "enabled": true,
        "status": status,
        "timingDecision": "late-monotonic-20s-and-60s",
        "successCount": state.successes,
        "maxSuccessCount": MAX_SNAPSHOTS,
        "attemptCount": state.attempts,
        "historicalAvailable": state.historical.is_some(),
        "earlyReadiness": state.early_readiness.map(EarlyReadinessMetadata::json).unwrap_or(Value::Null),
        "snapshotBytes": SNAPSHOT_BYTES,
        "totalSnapshotByteCap": SNAPSHOT_BYTES * usize::from(MAX_SNAPSHOTS),
        "sourceSha256CheckedOncePerLease": true,
        "writeAuthorized": false,
    });
    if let Some(detail) = detail { output["detail"] = json!(detail); }
    output
}

fn note_early_failure(state: &mut CaptureState, detail: &str) {
    // Loader readiness is not a changed identity or an ambiguous image. Keep
    // metadata-only polling within the existing <20s window when no header
    // has been validated yet. Real guard failures invalidate cached authority.
    if detail != EARLY_NOT_READY {
        state.early_guard_failed = true;
        state.early_readiness = None;
        state.historical = None;
    }
}

fn enter_operation(operation: &Mutex<()>) -> Result<MutexGuard<'_, ()>, &'static str> {
    match operation.try_lock() {
        Ok(guard) => Ok(guard),
        Err(TryLockError::WouldBlock) => Err("BUSY"),
        Err(TryLockError::Poisoned(_)) => Err("ERROR"),
    }
}

pub(super) fn poll(capture: &CodeCaptureLease, memory: &ReadOnlyMemory) -> Value {
    // Serialize the entire observation/commit, including the early phase.
    // A stale concurrent observation cannot restore history invalidated by a
    // terminal failure. nativeEnd does not acquire this lock: cancellation
    // remains responsive and is rechecked before any persistence.
    let _operation = match enter_operation(&capture.operation) {
        Ok(guard) => guard,
        Err(status) => return json!({"enabled": true, "status": status, "detail": "capture observation already active or unavailable", "writeAuthorized": false}),
    };
    // Remember only validated metadata during the early polls. No code bytes
    // are retained before the approved late snapshot schedule.
    if capture.started.elapsed() < FIRST_SNAPSHOT_AFTER {
        if capture.state.lock().ok().is_some_and(|state| state.early_guard_failed) {
            if let Ok(state) = capture.state.lock() { return timing_json(&state, "INCOMPLETE", Some("early candidate guard previously failed; retry stopped")); }
        }
        if let Err(detail) = remember_early_candidate(capture, memory) {
            if let Ok(mut state) = capture.state.lock() {
                note_early_failure(&mut state, &detail);
                return timing_json(&state, "INCOMPLETE", Some(&detail));
            }
            return json!({"enabled": true, "status": "ERROR", "detail": "capture state mutex poisoned", "writeAuthorized": false});
        }
    }
    let index = {
        let mut state = match capture.state.lock() {
            Ok(state) => state,
            Err(_) => return json!({"enabled": true, "status": "ERROR", "detail": "capture state mutex poisoned", "writeAuthorized": false}),
        };
        if state.successes >= MAX_SNAPSHOTS { return timing_json(&state, "COMPLETE", None); }
        if state.in_flight { return timing_json(&state, "BUSY", Some("capture already in progress")); }
        let Some(index) = timing_index(capture.started.elapsed(), state.successes) else { return timing_json(&state, "PENDING", Some("waiting for the approved late monotonic window")); };
        if index < state.successes { return timing_json(&state, "PENDING", Some("approved snapshot timing already passed")); }
        if state.attempts >= MAX_ATTEMPTS { return timing_json(&state, "INCOMPLETE", Some("capture retry bound exhausted")); }
        state.attempts += 1;
        state.in_flight = true;
        index
    };
    let result = capture_one(capture, memory, index);
    let mut state = match capture.state.lock() {
        Ok(state) => state,
        Err(_) => return json!({"enabled": true, "status": "ERROR", "detail": "capture state mutex poisoned", "writeAuthorized": false}),
    };
    state.in_flight = false;
    match result {
        Ok(value) => {
            state.successes = state.successes.saturating_add(1);
            value
        }
        Err(detail) => timing_json(&state, "INCOMPLETE", Some(&detail)),
    }
}

fn remember_early_candidate(capture: &CodeCaptureLease, memory: &ReadOnlyMemory) -> Result<(), String> {
    if !capture.source.stable() { return Err("source changed before early candidate observation".to_string()); }
    let before_target = super::validate_target(&memory.lease, memory.target.pid, &memory.budget)
        .map_err(|_| "target identity failed before early candidate observation".to_string())?;
    if !super::target_identity_matches(&memory.target, &before_target) { return Err("target identity changed before early candidate observation".to_string()); }
    let before_maps = maps_for_process(memory)?;
    let prior_historical = capture.state.lock().map_err(|_| "capture state mutex poisoned".to_string())?.historical.clone();
    if let Some(historical) = prior_historical {
        // Once history exists, this lease is bound to its original base. Do
        // not perform a global lookup: an unrelated PE copy becoming
        // metadata-eligible must never rebind the source-module hint.
        if memory.lease.cancelled.load(std::sync::atomic::Ordering::Acquire) { return Err("early observation lease cancelled".to_string()); }
        let after_maps = maps_for_process(memory)?;
        let current_header_observed = refresh_historical_early(memory, &before_maps, &after_maps, &historical)?;
        if !capture.source.stable() { return Err("source changed after historical early candidate observation".to_string()); }
        let after_target = super::validate_target(&memory.lease, memory.target.pid, &memory.budget)
            .map_err(|_| "target identity failed after historical early candidate observation".to_string())?;
        if !super::target_identity_matches(&memory.target, &after_target) { return Err("target identity changed after historical early candidate observation".to_string()); }
        if memory.lease.cancelled.load(std::sync::atomic::Ordering::Acquire) { return Err("early observation lease cancelled".to_string()); }
        let mut state = capture.state.lock().map_err(|_| "capture state mutex poisoned".to_string())?;
        state.early_readiness = Some(EarlyReadinessMetadata::metadata_ready([
            early_window_permission(&before_maps, historical.base, WINDOWS[0]),
            early_window_permission(&before_maps, historical.base, WINDOWS[1]),
        ], current_header_observed, true));
        return Ok(());
    }

    let candidates = find_early_header_candidates(memory, &before_maps, &capture.source.layout)?;
    let candidate = match candidates.as_slice() {
        [candidate] => candidate,
        [] => {
            capture.state.lock().map_err(|_| "capture state mutex poisoned".to_string())?.early_readiness = Some(diagnose_empty_early_lookup(memory, &before_maps, &capture.source.layout));
            return Err(EARLY_NOT_READY.to_string());
        }
        _ => return Err("early PE candidate is ambiguous".to_string()),
    };
    let after_maps = maps_for_process(memory)?;
    let after = find_early_header_candidates(memory, &after_maps, &capture.source.layout)?;
    if !matches!(after.as_slice(), [current] if current.base == candidate.base && current.layout.matches(&candidate.layout)) {
        return Err("early PE candidate changed during observation".to_string());
    }
    if normalized_metadata_coverage(&after_maps, candidate.base)? != candidate.coverage || !capture.source.stable() {
        return Err("early candidate coverage or source changed".to_string());
    }
    let after_target = super::validate_target(&memory.lease, memory.target.pid, &memory.budget)
        .map_err(|_| "target identity failed after early candidate observation".to_string())?;
    if !super::target_identity_matches(&memory.target, &after_target) { return Err("target identity changed after early candidate observation".to_string()); }
    if memory.lease.cancelled.load(std::sync::atomic::Ordering::Acquire) { return Err("early observation lease cancelled".to_string()); }
    let mut state = capture.state.lock().map_err(|_| "capture state mutex poisoned".to_string())?;
    state.historical = Some(HistoricalCandidate { base: candidate.base, layout: candidate.layout.clone(), coverage: candidate.coverage.clone() });
    state.early_readiness = Some(EarlyReadinessMetadata::metadata_ready([
        early_window_permission(&before_maps, candidate.base, WINDOWS[0]),
        early_window_permission(&before_maps, candidate.base, WINDOWS[1]),
    ], true, true));
    Ok(())
}

fn capture_one(capture: &CodeCaptureLease, memory: &ReadOnlyMemory, index: u8) -> Result<Value, String> {
    if !capture.source.stable() { return Err("source metadata changed before capture".to_string()); }
    super::validate_target(&memory.lease, memory.target.pid, &memory.budget).map_err(|_| "target identity failed before capture".to_string())?;
    let before_maps = maps_for_process(memory)?;
    let historical_available = capture.state.lock().map_err(|_| "capture state mutex poisoned".to_string())?.historical.is_some();
    let (candidates, before_lookup_limited) = lookup_for_historical_observation(find_candidates(memory, &before_maps, &capture.source.layout), historical_available)?;
    let (base, layout, mut basis, coverage_before) = {
        let mut state = capture.state.lock().map_err(|_| "capture state mutex poisoned".to_string())?;
        match candidates.as_slice() {
            [candidate] => {
                if let Some(historical) = state.historical.as_ref() {
                    if !historical_backing_matches(&candidate.coverage, &historical.coverage) { return Err("runtime payload backing changed from early metadata".to_string()); }
                }
                let historical = HistoricalCandidate { base: candidate.base, layout: candidate.layout.clone(), coverage: candidate.coverage.clone() };
                state.historical = Some(historical);
                (candidate.base, candidate.layout.clone(), "current-structural-header", candidate.coverage.clone())
            }
            [] => {
                let historical = state.historical.clone().ok_or_else(|| "no current or retained structural PE candidate".to_string())?;
                let current_coverage = window_coverage(&before_maps, historical.base)?;
                if !historical_backing_matches(&current_coverage, &historical.coverage) { return Err("historical candidate map coverage changed".to_string()); }
                (historical.base, historical.layout, "earlier-structural-header", current_coverage)
            }
            _ => return Err("runtime PE candidate is ambiguous".to_string()),
        }
    };
    if layout.machine != capture.source.layout.machine { return Err("runtime candidate machine mismatch".to_string()); }
    let mut bytes = Vec::with_capacity(SNAPSHOT_BYTES);
    let mut window_offsets = Vec::new();
    for window in WINDOWS {
        let address = base.checked_add(window.rva).ok_or_else(|| "runtime capture address overflow".to_string())?;
        let mut fragment = vec![0u8; window.length];
        read_memory(memory, &before_maps, address, &mut fragment).map_err(|_| "runtime code window read was partial".to_string())?;
        window_offsets.push((window.name, window.rva, window.length, bytes.len()));
        bytes.extend_from_slice(&fragment);
    }
    if bytes.len() != SNAPSHOT_BYTES { return Err("runtime snapshot size is outside the fixed cap".to_string()); }
    if !capture.source.stable() { return Err("source metadata changed during capture".to_string()); }
    let after_maps = maps_for_process(memory)?;
    let historical_available = capture.state.lock().map_err(|_| "capture state mutex poisoned".to_string())?.historical.is_some();
    let (after_candidates, after_lookup_limited) = lookup_for_historical_observation(find_candidates(memory, &after_maps, &capture.source.layout), historical_available)?;
    let historical_coverage = capture.state.lock().map_err(|_| "capture state mutex poisoned".to_string())?.historical.clone()
        .ok_or_else(|| "historical candidate disappeared after capture".to_string())?.coverage;
    match after_candidates.as_slice() {
        [candidate] if candidate.base == base && candidate.layout.matches(&layout) => {},
        [] => basis = "earlier-structural-header",
        [_] => return Err("runtime PE candidate changed after capture".to_string()),
        _ => return Err("runtime PE candidate became ambiguous after capture".to_string()),
    }
    let coverage_after = window_coverage(&after_maps, base)?;
    if coverage_after != coverage_before { return Err("private RX map coverage changed during capture".to_string()); }
    if !historical_backing_matches(&coverage_after, &historical_coverage) { return Err("payload backing changed during capture".to_string()); }
    let after_target = super::validate_target(&memory.lease, memory.target.pid, &memory.budget).map_err(|_| "target identity failed after capture".to_string())?;
    if !super::target_identity_matches(&memory.target, &after_target) { return Err("target identity changed after capture".to_string()); }
    // Linearize the final cancellation check with nativeEnd. The active lease
    // mutex remains held until both CREATE_NEW artifacts are committed (or the
    // binary is removed on metadata failure).
    let active_guard = super::active().lock().map_err(|_| "active lease mutex poisoned".to_string())?;
    if !active_guard.as_ref().is_some_and(|lease| lease.token == memory.lease.token && !lease.cancelled.load(std::sync::atomic::Ordering::Acquire)) {
        return Err("capture lease became stale before persistence".to_string());
    }
    let snapshot_name = format!("snapshot-{index}.bin");
    let metadata_name = format!("snapshot-{index}.json");
    let snapshot_path = capture.output_dir.join(&snapshot_name);
    let metadata_path = capture.output_dir.join(&metadata_name);
    let snapshot_tmp = capture.output_dir.join(format!(".{snapshot_name}.tmp"));
    let metadata_tmp = capture.output_dir.join(format!(".{metadata_name}.tmp"));
    let metadata = json!({
        "schema": 1,
        "kind": "arm64-runtime-code-window",
        "status": if basis == "current-structural-header" { "CAPTURED" } else { "INCOMPLETE" },
        "basis": basis,
        "moduleProof": basis == "current-structural-header",
        "cryptographicModuleProof": false,
        "moduleLookupIncomplete": before_lookup_limited || after_lookup_limited,
        "writeAuthorized": false,
        "source": {"size": SOURCE_SIZE, "sha256": capture.source.sha256, "sha256CheckedOncePerLease": true, "metadataStable": true, "windowsValidatedAsRawBackedExecutableText": true},
        "target": target_metadata(&memory.target),
        "nonce": capture.nonce,
        "snapshot": {"index": index, "bytes": bytes.len(), "sha256": sha256_bytes(&bytes), "windows": window_offsets.iter().map(|(name, rva, length, offset)| json!({"name": name, "rva": rva, "length": length, "offset": offset})).collect::<Vec<_>>()},
        "mapsBefore": coverage_before.iter().map(map_json).collect::<Vec<_>>(),
        "mapsAfter": coverage_after.iter().map(map_json).collect::<Vec<_>>(),
        "mapsStable": true,
        "timingDecision": "late-monotonic-20s-and-60s",
    });
    let metadata_bytes = (serde_json::to_string(&metadata).map_err(|_| "capture metadata serialization failed".to_string())? + "\n").into_bytes();
    if metadata_bytes.len() > MAX_METADATA { return Err("capture metadata exceeded its bound".to_string()); }
    write_exclusive(&snapshot_tmp, &bytes)?;
    if let Err(error) = write_exclusive(&metadata_tmp, &metadata_bytes) {
        let _ = std::fs::remove_file(&snapshot_tmp);
        return Err(error);
    }
    if let Err(error) = commit_exclusive(&snapshot_tmp, &snapshot_path) {
        let _ = std::fs::remove_file(&snapshot_tmp);
        let _ = std::fs::remove_file(&metadata_tmp);
        return Err(error);
    }
    if let Err(error) = commit_exclusive(&metadata_tmp, &metadata_path) {
        let _ = std::fs::remove_file(&metadata_tmp);
        let _ = std::fs::remove_file(&snapshot_path);
        return Err(error);
    }
    if let Err(error) = sync_directory(&capture.output_dir) {
        let _ = std::fs::remove_file(&metadata_path);
        let _ = std::fs::remove_file(&snapshot_path);
        return Err(error);
    }
    Ok(json!({"enabled": true, "status": if basis == "current-structural-header" { "CAPTURED" } else { "INCOMPLETE" }, "basis": basis, "moduleProof": basis == "current-structural-header", "cryptographicModuleProof": false, "snapshotIndex": index, "bytes": bytes.len(), "metadataFile": format!("functional-poc/captures/{}/{}", capture.nonce, metadata_name), "binaryFile": format!("functional-poc/captures/{}/{}", capture.nonce, snapshot_name), "timingDecision": "late-monotonic-20s-and-60s", "writeAuthorized": false}))
}

fn write_exclusive(path: &Path, bytes: &[u8]) -> Result<(), String> {
    if path.exists() || path.is_symlink() { return Err("capture artifact already exists".to_string()); }
    let mut file = OpenOptions::new().write(true).create_new(true).custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC).mode(0o600).open(path).map_err(|_| "capture artifact could not be created exclusively".to_string())?;
    if let Err(_) = file.write_all(bytes).and_then(|_| file.sync_all()) { let _ = std::fs::remove_file(path); return Err("capture artifact write failed".to_string()); }
    Ok(())
}

fn sync_directory(path: &Path) -> Result<(), String> {
    File::open(path).and_then(|file| file.sync_all()).map_err(|_| "capture directory sync failed".to_string())
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn rename_noreplace(temporary: &Path, final_path: &Path, stage: &str) -> Result<(), String> {
    let temporary = CString::new(temporary.as_os_str().as_bytes()).map_err(|_| format!("storage_{stage}_path_invalid"))?;
    let final_path = CString::new(final_path.as_os_str().as_bytes()).map_err(|_| format!("storage_{stage}_path_invalid"))?;
    let result = unsafe {
        libc::syscall(
            libc::SYS_renameat2,
            libc::AT_FDCWD,
            temporary.as_ptr(),
            libc::AT_FDCWD,
            final_path.as_ptr(),
            libc::RENAME_NOREPLACE,
        )
    };
    if result == 0 {
        Ok(())
    } else {
        Err(format!("storage_{stage}_errno_{}", io::Error::last_os_error().raw_os_error().unwrap_or(-1)))
    }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn rename_noreplace(_temporary: &Path, _final_path: &Path, stage: &str) -> Result<(), String> {
    Err(format!("storage_{stage}_unsupported_platform"))
}

fn storage_preflight(root: &Path) -> Result<(), String> {
    let directory = std::fs::symlink_metadata(root).map_err(|_| "storage_preflight_directory_missing".to_string())?;
    if !directory.file_type().is_dir() || directory.uid() != unsafe { libc::geteuid() } || directory.mode() & 0o777 != 0o700 {
        return Err("storage_preflight_directory_not_private".to_string());
    }
    let temporary = root.join(".storage-preflight.tmp");
    let temporary_collision = root.join(".storage-preflight.tmp2");
    let final_path = root.join(".storage-preflight.marker");
    let marker = b"fp-v1\n";
    let collision_marker = b"fp-v2\n";
    write_exclusive(&temporary, marker)?;
    if let Err(error) = rename_noreplace(&temporary, &final_path, "preflight_first_publish") {
        let _ = std::fs::remove_file(&temporary);
        return Err(error);
    }
    let mut collision_created = false;
    let result = (|| {
        write_exclusive(&temporary_collision, collision_marker)?;
        collision_created = true;
        match rename_noreplace(&temporary_collision, &final_path, "preflight_collision") {
            Err(error) if error.ends_with("_errno_17") => Ok(()),
            Err(error) => Err(error),
            Ok(()) => Err("storage_preflight_collision_not_rejected".to_string()),
        }
    })();
    if collision_created { let _ = std::fs::remove_file(&temporary_collision); }
    let _ = std::fs::remove_file(&final_path);
    let sync_result = sync_directory(root);
    result.and(sync_result)
}

fn commit_exclusive(temporary: &Path, final_path: &Path) -> Result<(), String> {
    rename_noreplace(temporary, final_path, "capture_publish")
}

// Small dependency-free SHA-256 implementation used once at lease creation.
struct Sha256 { state: [u32; 8], length: u64, buffer: [u8; 64], buffered: usize }
impl Sha256 {
    fn new() -> Self { Self { state: [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19], length: 0, buffer: [0; 64], buffered: 0 } }
    fn update(&mut self, bytes: &[u8]) { self.length = self.length.saturating_add(bytes.len() as u64); let mut input = bytes; while !input.is_empty() { let count = (64 - self.buffered).min(input.len()); self.buffer[self.buffered..self.buffered + count].copy_from_slice(&input[..count]); self.buffered += count; input = &input[count..]; if self.buffered == 64 { let block = self.buffer; self.compress(&block); self.buffered = 0; } } }
    fn finish(mut self) -> String { let bit_length = self.length.saturating_mul(8); self.buffer[self.buffered] = 0x80; self.buffered += 1; if self.buffered > 56 { self.buffer[self.buffered..].fill(0); let block = self.buffer; self.compress(&block); self.buffered = 0; } self.buffer[self.buffered..56].fill(0); self.buffer[56..64].copy_from_slice(&bit_length.to_be_bytes()); let block = self.buffer; self.compress(&block); let mut output = String::new(); for word in self.state { output.push_str(&format!("{word:08x}")); } output }
    fn compress(&mut self, block: &[u8; 64]) { const K: [u32; 64] = [0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2]; let mut w=[0u32;64]; for i in 0..16 { w[i]=u32::from_be_bytes(block[i*4..i*4+4].try_into().unwrap()); } for i in 16..64 { let x=w[i-15].rotate_right(7)^w[i-15].rotate_right(18)^(w[i-15]>>3); let y=w[i-2].rotate_right(17)^w[i-2].rotate_right(19)^(w[i-2]>>10); w[i]=w[i-16].wrapping_add(x).wrapping_add(w[i-7]).wrapping_add(y); } let mut a=self.state[0]; let mut b=self.state[1]; let mut c=self.state[2]; let mut d=self.state[3]; let mut e=self.state[4]; let mut f=self.state[5]; let mut g=self.state[6]; let mut h=self.state[7]; for i in 0..64 { let s1=e.rotate_right(6)^e.rotate_right(11)^e.rotate_right(25); let ch=(e&f)^((!e)&g); let t1=h.wrapping_add(s1).wrapping_add(ch).wrapping_add(K[i]).wrapping_add(w[i]); let s0=a.rotate_right(2)^a.rotate_right(13)^a.rotate_right(22); let maj=(a&b)^(a&c)^(b&c); let t2=s0.wrapping_add(maj); h=g; g=f; f=e; e=d.wrapping_add(t1); d=c; c=b; b=a; a=t1.wrapping_add(t2); } self.state[0]=self.state[0].wrapping_add(a); self.state[1]=self.state[1].wrapping_add(b); self.state[2]=self.state[2].wrapping_add(c); self.state[3]=self.state[3].wrapping_add(d); self.state[4]=self.state[4].wrapping_add(e); self.state[5]=self.state[5].wrapping_add(f); self.state[6]=self.state[6].wrapping_add(g); self.state[7]=self.state[7].wrapping_add(h); }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io;
    use std::os::unix::fs::symlink;
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(0);

    #[test]
    fn pinned_early_refresh_rejects_permission_changes_during_one_observation() {
        let base = 0x10001000;
        let mut before = synthetic_map(base, 0x20000000);
        before.perms = "rw-p".to_string();
        let historical = HistoricalCandidate {
            base,
            layout: synthetic_source(base),
            coverage: normalized_metadata_coverage(&[before.clone()], base).unwrap(),
        };
        let mut after = before.clone();
        after.perms = "r-xp".to_string();
        let memory = SyntheticMemory { chunks: vec![] };
        assert!(refresh_historical_early(&memory, &[before], &[after.clone()], &historical).unwrap_err().contains("during observation"));
        // Across separate observations permission promotion is allowed if the
        // new observation itself remains stable and backing identity matches.
        assert_eq!(refresh_historical_early(&memory, &[after.clone()], &[after], &historical).unwrap(), false);
    }

    #[test]
    fn named_anonymous_maps_remain_anonymous_without_accepting_heap_stack_or_shared() {
        assert_eq!(map_path_kind(Some("[anon:loader-code]"), "00:00", 0), "anonymous");
        assert_eq!(map_path_kind(Some("[heap]"), "00:00", 0), "pseudo");
        assert_eq!(map_path_kind(Some("[stack]"), "00:00", 0), "pseudo");
        assert_eq!(map_path_kind(Some("[anon_shmem:shared]"), "00:00", 0), "pseudo");
        assert_eq!(map_path_kind(Some("/dev/ashmem/code"), "00:01", 1), "device");
        let mut map = synthetic_map(0x1000, 0x4000000);
        map.path_kind = map_path_kind(Some("[anon:loader-code]"), "00:00", 0);
        map.device = "00:00".to_string();
        map.inode = 0;
        assert!(window_coverage(&[map.clone()], 0).is_ok());
        map.perms = "rw-p".to_string();
        assert!(window_coverage(&[map], 0).is_err());
    }

    #[test]
    fn storage_preflight_preserves_a_preexisting_collision_temporary() {
        let root = std::env::temp_dir().join(format!("capture-preflight-collision-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
        std::fs::create_dir(&root).unwrap();
        std::fs::set_permissions(&root, std::fs::Permissions::from_mode(0o700)).unwrap();
        let foreign = root.join(".storage-preflight.tmp2");
        write_exclusive(&foreign, b"preexisting").unwrap();
        assert!(storage_preflight(&root).is_err());
        assert_eq!(std::fs::read(&foreign).unwrap(), b"preexisting");
        assert!(!root.join(".storage-preflight.marker").exists());
        assert!(!root.join(".storage-preflight.tmp").exists());
        std::fs::remove_file(foreign).unwrap();
        std::fs::remove_dir(root).unwrap();
    }

    struct SyntheticMemory {
        chunks: Vec<(u64, Vec<u8>)>,
    }

    impl MemoryAccess for SyntheticMemory {
        fn regions(&self) -> io::Result<Vec<forever_patcher::Region>> { Ok(Vec::new()) }

        fn read(&self, address: u64, output: &mut [u8]) -> io::Result<()> {
            let end = address.checked_add(output.len() as u64).ok_or_else(|| io::Error::other("synthetic address overflow"))?;
            let Some((base, bytes)) = self.chunks.iter().find(|(base, bytes)| *base <= address && end <= base.saturating_add(bytes.len() as u64)) else {
                return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "synthetic memory gap"));
            };
            let offset = usize::try_from(address - *base).map_err(|_| io::Error::other("synthetic offset overflow"))?;
            output.copy_from_slice(&bytes[offset..offset + output.len()]);
            Ok(())
        }

        fn write(&self, _address: u64, _data: &[u8]) -> io::Result<()> { Err(io::Error::new(io::ErrorKind::PermissionDenied, "synthetic memory is read-only")) }
    }

    struct HeaderOnlyMemory(SyntheticMemory);

    impl MemoryAccess for HeaderOnlyMemory {
        fn regions(&self) -> io::Result<Vec<forever_patcher::Region>> { self.0.regions() }

        fn read(&self, address: u64, output: &mut [u8]) -> io::Result<()> {
            assert!(output.len() <= 0x400, "classification must not read payload bytes");
            self.0.read(address, output)
        }

        fn write(&self, address: u64, data: &[u8]) -> io::Result<()> { self.0.write(address, data) }
    }

    fn synthetic_pe(image_base: u64) -> Vec<u8> {
        let mut bytes = vec![0u8; 0x200];
        bytes[..2].copy_from_slice(b"MZ");
        bytes[0x3c..0x40].copy_from_slice(&0x80u32.to_le_bytes());
        bytes[0x80..0x84].copy_from_slice(b"PE\0\0");
        let coff = 0x84;
        bytes[coff..coff + 2].copy_from_slice(&0xaa64u16.to_le_bytes());
        bytes[coff + 2..coff + 4].copy_from_slice(&1u16.to_le_bytes());
        bytes[coff + 4..coff + 8].copy_from_slice(&1u32.to_le_bytes());
        bytes[coff + 16..coff + 18].copy_from_slice(&240u16.to_le_bytes());
        let optional = coff + 20;
        bytes[optional..optional + 2].copy_from_slice(&0x20bu16.to_le_bytes());
        bytes[optional + 16..optional + 20].copy_from_slice(&0x1000u32.to_le_bytes());
        bytes[optional + 24..optional + 32].copy_from_slice(&image_base.to_le_bytes());
        bytes[optional + 56..optional + 60].copy_from_slice(&0x4000_0000u32.to_le_bytes());
        bytes[optional + 60..optional + 64].copy_from_slice(&0x400u32.to_le_bytes());
        let section = optional + 240;
        bytes[section..section + 8].copy_from_slice(b".text\0\0\0");
        bytes[section + 8..section + 12].copy_from_slice(&0x4000_0000u32.to_le_bytes());
        bytes[section + 12..section + 16].copy_from_slice(&0u32.to_le_bytes());
        bytes[section + 16..section + 20].copy_from_slice(&0x4000_0000u32.to_le_bytes());
        bytes[section + 20..section + 24].copy_from_slice(&0u32.to_le_bytes());
        bytes[section + 36..section + 40].copy_from_slice(&0x6000_0020u32.to_le_bytes());
        bytes
    }

    fn synthetic_source(image_base: u64) -> PeLayout { PeLayout::parse(&synthetic_pe(image_base), None).unwrap() }

    fn synthetic_map(start: u64, end: u64) -> MapEntry {
        MapEntry { start, end, perms: "r-xp".to_string(), offset: 0, device: "00:00".to_string(), inode: 1, path_kind: "file" }
    }

    #[test]
    fn fixed_windows_are_exact_non_overlapping_16k_text_ranges() {
        assert_eq!(WINDOWS, [CodeWindow { name: "text-223c000", rva: 0x223c000, length: 0x4000 }, CodeWindow { name: "text-37b0000", rva: 0x37b0000, length: 0x4000 }]);
        assert!(WINDOWS[0].rva + WINDOWS[0].length as u64 <= WINDOWS[1].rva);
        assert_eq!(SNAPSHOT_BYTES, 32768);
        assert_eq!(SNAPSHOT_BYTES * usize::from(MAX_SNAPSHOTS), 65536);
        assert_eq!(OUTPUT_ROOT, "/data/data/com.wowforever.arm64probe/files/functional-poc/captures");
    }

    #[test]
    fn sha256_known_vector_is_real_not_mock_metadata() {
        let mut digest = Sha256::new(); digest.update(b"abc");
        assert_eq!(digest.finish(), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    #[test]
    fn nonce_and_artifacts_are_private_exclusive_and_symlink_safe() {
        let root = std::env::temp_dir().join(format!("functional-capture-test-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
        std::fs::create_dir(&root).unwrap();
        let source_path = root.join("source");
        std::fs::write(&source_path, b"not-an-authenticated-client").unwrap();
        let link = root.join("link"); symlink(&source_path, &link).unwrap();
        assert!(HeldSource::open(&link).is_err());
        assert!(CodeCaptureLease::begin_at(&source_path, "../escape", &root).is_err());
        let _ = std::fs::remove_file(link); let _ = std::fs::remove_file(source_path); let _ = std::fs::remove_dir(root);
    }

    #[test]
    fn root_symlink_error_reports_only_component_index_filetype_and_uid() {
        let root = std::env::temp_dir().join(format!("functional-capture-root-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
        let real = root.join("real");
        let link = root.join("link");
        let requested = link.join("captures");
        std::fs::create_dir_all(&real).unwrap();
        symlink(&real, &link).unwrap();
        let metadata = std::fs::symlink_metadata(&link).unwrap();
        let expected_index = requested.components().enumerate().find(|(_, component)| component.as_os_str() == "link").map(|(index, _)| index).unwrap();
        let error = create_private_dir(&requested).unwrap_err();
        assert_eq!(error, format!("capture_root_symlink_component_{expected_index}_mode_{}_uid_{}", metadata.mode() & libc::S_IFMT as u32, metadata.uid()));
        assert!(!error.contains(root.to_str().unwrap()));
        let _ = std::fs::remove_file(&link);
        let _ = std::fs::remove_dir(&real);
        let _ = std::fs::remove_dir(&root);
    }

    #[test]
    fn private_maps_require_exact_r_x_private_and_reject_shared_device_or_gap() {
        let maps = vec![MapEntry { start: 0x1000, end: 0x2000, perms: "r-xp".to_string(), offset: 0, device: "00:01".to_string(), inode: 1, path_kind: "file" }];
        assert!(coverage(&maps, 0x1100, 0x100).is_ok());
        let mut shared = maps[0].clone(); shared.perms = "r-xs".to_string(); assert!(coverage(&[shared], 0x1100, 0x100).is_err());
        let mut device = maps[0].clone(); device.path_kind = "device"; assert!(coverage(&[device], 0x1100, 0x100).is_err());
        assert!(coverage(&maps, 0x1f00, 0x200).is_err());
    }

    #[test]
    fn two_windows_in_one_map_have_identical_normalized_coverage() {
        let map = MapEntry { start: 0x1000, end: 0x4000000, perms: "r-xp".to_string(), offset: 0, device: "00:00".to_string(), inode: 0, path_kind: "anonymous" };
        let coverage = window_coverage(&[map], 0).unwrap();
        assert_eq!(coverage.iter().map(|entry| (entry.start, entry.end)).collect::<Vec<_>>(), vec![(WINDOWS[0].rva, WINDOWS[0].rva + WINDOWS[0].length as u64), (WINDOWS[1].rva, WINDOWS[1].rva + WINDOWS[1].length as u64)]);
    }

    #[test]
    fn repro_whole_map_coverage_false_mismatch_for_two_fixed_windows() {
        let map = MapEntry { start: 0x1000, end: 0x4000000, perms: "r-xp".to_string(), offset: 0, device: "00:00".to_string(), inode: 0, path_kind: "anonymous" };
        let coverage = window_coverage(&[map], 0).unwrap();
        assert_eq!(coverage.iter().map(|entry| (entry.start, entry.end)).collect::<Vec<_>>(), vec![(WINDOWS[0].rva, WINDOWS[0].rva + WINDOWS[0].length as u64), (WINDOWS[1].rva, WINDOWS[1].rva + WINDOWS[1].length as u64)]);
    }

    #[test]
    fn normalized_coverage_clips_adjusts_offsets_and_coalesces_only_equivalent_segments() {
        let split = WINDOWS[0].rva + 0x1000;
        let first = MapEntry { start: 0, end: split, perms: "r-xp".to_string(), offset: 0x500, device: "08:01".to_string(), inode: 7, path_kind: "file" };
        let second = MapEntry { start: split, end: 0x4000000, offset: 0x500 + split, ..first.clone() };
        let coverage = window_coverage(&[first, second], 0).unwrap();
        assert_eq!(coverage.len(), 2);
        assert_eq!(coverage[0].start, WINDOWS[0].rva);
        assert_eq!(coverage[0].end, WINDOWS[0].rva + WINDOWS[0].length as u64);
        assert_eq!(coverage[0].offset, 0x500 + WINDOWS[0].rva);
        assert_eq!(coverage[1].start, WINDOWS[1].rva);
        assert_eq!(coverage[1].end, WINDOWS[1].rva + WINDOWS[1].length as u64);
        assert_eq!(coverage[1].offset, 0x500 + WINDOWS[1].rva);

        let anonymous_first = MapEntry { start: 0, end: split, perms: "r-xp".to_string(), offset: 91, device: "00:00".to_string(), inode: 0, path_kind: "anonymous" };
        let anonymous_second = MapEntry { start: split, end: 0x4000000, ..anonymous_first.clone() };
        let anonymous = window_coverage(&[anonymous_first, anonymous_second], 0).unwrap();
        assert_eq!(anonymous[0].offset, 0);
        assert_eq!(anonymous[0].end, WINDOWS[0].rva + WINDOWS[0].length as u64);

        let changed_offset = MapEntry { offset: 0x501, ..MapEntry { start: 0, end: 0x4000000, perms: "r-xp".to_string(), offset: 0x500, device: "08:01".to_string(), inode: 7, path_kind: "file" } };
        let original = MapEntry { start: 0, end: 0x4000000, perms: "r-xp".to_string(), offset: 0x500, device: "08:01".to_string(), inode: 7, path_kind: "file" };
        assert_ne!(window_coverage(&[original], 0).unwrap(), window_coverage(&[changed_offset], 0).unwrap());

        let different_inode = MapEntry { inode: 8, ..MapEntry { start: split, end: 0x4000000, perms: "r-xp".to_string(), offset: 0x500 + split, device: "08:01".to_string(), inode: 7, path_kind: "file" } };
        assert_ne!(window_coverage(&[MapEntry { start: 0, end: split, perms: "r-xp".to_string(), offset: 0x500, device: "08:01".to_string(), inode: 7, path_kind: "file" }, different_inode], 0).unwrap(), coverage);

        let overflow = MapEntry { start: 0, end: 0x4000000, perms: "r-xp".to_string(), offset: u64::MAX, device: "08:01".to_string(), inode: 7, path_kind: "file" };
        assert!(window_coverage(&[overflow], 0).is_err());

        for (perms, path_kind) in [("rw-p", "file"), ("r-xs", "file"), ("r-xp", "device")] {
            let invalid = MapEntry { start: 0, end: 0x4000000, perms: perms.to_string(), offset: 0, device: "08:01".to_string(), inode: 7, path_kind };
            assert!(window_coverage(&[invalid], 0).is_err());
        }
        let gap_left = MapEntry { start: 0, end: WINDOWS[0].rva + 0x1000, perms: "r-xp".to_string(), offset: 0, device: "00:00".to_string(), inode: 0, path_kind: "anonymous" };
        let gap_right = MapEntry { start: gap_left.end + 1, end: 0x4000000, ..gap_left.clone() };
        assert!(window_coverage(&[gap_left, gap_right], 0).is_err());
    }

    #[test]
    fn bounded_lookup_is_only_a_historical_observation_not_current_proof() {
        assert!(lookup_for_historical_observation(Err("runtime PE candidate inspection bound exceeded".to_string()), false).is_err());
        let (candidates, limited) = lookup_for_historical_observation(Err("runtime PE candidate inspection bound exceeded".to_string()), true).unwrap();
        assert!(candidates.is_empty());
        assert!(limited);
        assert!(lookup_for_historical_observation(Err("runtime maps malformed".to_string()), true).is_err());
        let (candidates, limited) = lookup_for_historical_observation(Ok(Vec::new()), false).unwrap();
        assert!(candidates.is_empty());
        assert!(!limited);
    }

    #[test]
    fn loader_not_ready_does_not_poison_early_observation_but_real_guard_failure_does() {
        let mut state = CaptureState { attempts: 0, successes: 0, in_flight: false, early_guard_failed: false, early_readiness: None, historical: None };
        let readiness = EarlyReadinessMetadata::unknown(EarlyReadinessClass::PreferredBaseUnmapped);
        state.early_readiness = Some(readiness);
        note_early_failure(&mut state, EARLY_NOT_READY);
        assert!(!state.early_guard_failed && state.historical.is_none());
        assert_eq!(state.early_readiness, Some(readiness));
        state.historical = Some(HistoricalCandidate { base: 0x10001000, layout: synthetic_source(0x10001000), coverage: vec![] });
        note_early_failure(&mut state, "early PE candidate is ambiguous");
        assert!(state.early_guard_failed && state.historical.is_none());
        assert_eq!(state.attempts, 0);
        assert_eq!(state.successes, 0);
    }

    #[test]
    fn early_observation_cannot_race_terminal_history_invalidation() {
        let operation = Arc::new(Mutex::new(()));
        let state = Arc::new(Mutex::new(CaptureState { attempts: 0, successes: 0, in_flight: false, early_guard_failed: false, early_readiness: None, historical: None }));
        let guard = enter_operation(&operation).unwrap();
        let other_operation = Arc::clone(&operation);
        let other_state = Arc::clone(&state);
        std::thread::spawn(move || {
            assert_eq!(enter_operation(&other_operation).unwrap_err(), "BUSY");
            // A second poll is rejected before observing or mutating state.
            assert!(!other_state.lock().unwrap().early_guard_failed);
        }).join().unwrap();
        note_early_failure(&mut state.lock().unwrap(), "early PE candidate is ambiguous");
        drop(guard);
        let _next = enter_operation(&operation).unwrap();
        let state = state.lock().unwrap();
        assert!(state.early_guard_failed && state.historical.is_none());
    }

    #[test]
    fn preferred_image_base_is_a_seed_only_and_runtime_map_start_remains_aslr_fallback() {
        let preferred = 0x1000_1000;
        let map_start = 0x1000_0000;
        let source = synthetic_source(preferred);
        let maps = vec![synthetic_map(map_start, 0x2000_0000)];
        let addresses = candidate_addresses(&maps, &source).unwrap();
        assert_eq!(addresses, vec![preferred, map_start]);
        let memory = SyntheticMemory { chunks: vec![(preferred, synthetic_pe(preferred))] };
        let candidates = find_candidates(&memory, &maps, &source).unwrap();
        assert_eq!(candidates.iter().map(|candidate| candidate.base).collect::<Vec<_>>(), vec![preferred]);

        let dedup_maps = vec![synthetic_map(preferred, 0x2000_0000)];
        assert_eq!(candidate_addresses(&dedup_maps, &source).unwrap(), vec![preferred]);

        let aslr_source = synthetic_source(0x4000_0000);
        let aslr_memory = SyntheticMemory { chunks: vec![(map_start, synthetic_pe(map_start))] };
        let aslr = find_candidates(&aslr_memory, &maps, &aslr_source).unwrap();
        assert_eq!(aslr.iter().map(|candidate| candidate.base).collect::<Vec<_>>(), vec![map_start]);
    }

    #[test]
    fn preferred_seed_requires_a_private_readable_mapped_header_and_matching_runtime_header() {
        let preferred = 0x1000_1000;
        let map_start = 0x1000_0000;
        let source = synthetic_source(preferred);
        let maps = vec![synthetic_map(map_start, 0x2000_0000)];
        let invalid_preferred = SyntheticMemory { chunks: vec![(preferred, vec![0u8; 0x200]), (map_start, synthetic_pe(map_start))] };
        let candidates = find_candidates(&invalid_preferred, &maps, &source).unwrap();
        assert_eq!(candidates.iter().map(|candidate| candidate.base).collect::<Vec<_>>(), vec![map_start]);

        let unmapped_source = synthetic_source(0x3000_0000);
        let unmapped = find_candidates(&SyntheticMemory { chunks: vec![] }, &maps, &unmapped_source).unwrap();
        assert!(unmapped.is_empty());

        let base_only = find_candidates(&SyntheticMemory { chunks: vec![(preferred, vec![0u8; 0x200])] }, &maps, &source).unwrap();
        assert!(base_only.is_empty(), "the preferred base alone is not module authority");
    }

    #[test]
    fn early_readiness_classifies_header_and_fixed_window_permissions_without_payload_reads() {
        let preferred = 0x1000_1000;
        let source = synthetic_source(preferred);
        let header = {
            let mut map = synthetic_map(0x1000_0000, preferred + 0x1000);
            map.perms = "r--p".to_string();
            map
        };
        let rx_text = synthetic_map(preferred + 0x1000, 0x2000_0000);
        let ready_maps = vec![header.clone(), rx_text.clone()];
        let ready_memory = HeaderOnlyMemory(SyntheticMemory { chunks: vec![(preferred, synthetic_pe(preferred))] });
        let ready_candidates = find_candidates(&ready_memory, &ready_maps, &source).unwrap();
        assert_eq!(ready_candidates.len(), 1);
        let ready = EarlyReadinessMetadata::metadata_ready([EarlyWindowPermission::Rx; 2], true, true);
        assert_eq!(ready.classification, EarlyReadinessClass::MetadataReady);
        assert_eq!(ready.windows[0].permission, EarlyWindowPermission::Rx);
        assert!(ready.current_header_observed && ready.base_pinned);
        assert_eq!(ready.json()["windows"][0]["name"], WINDOWS[0].name);
        assert_eq!(ready.json()["windows"][0]["length"], WINDOWS[0].length);

        let rw_text = MapEntry { perms: "rw-p".to_string(), ..rx_text };
        let rw_maps = vec![header.clone(), rw_text];
        let rw_metadata = diagnose_empty_early_lookup(&ready_memory, &rw_maps, &source);
        assert_eq!(rw_metadata.classification, EarlyReadinessClass::HeaderMatchedPayloadNonRx);
        assert_eq!(rw_metadata.windows[0].permission, EarlyWindowPermission::Rw);
        assert_eq!(rw_metadata.windows[1].permission, EarlyWindowPermission::Rw);

        let unmapped = diagnose_empty_early_lookup(&ready_memory, &[], &source);
        assert_eq!(unmapped.classification, EarlyReadinessClass::PreferredBaseUnmapped);
        let missing_mz = diagnose_empty_early_lookup(&HeaderOnlyMemory(SyntheticMemory { chunks: vec![(preferred, vec![0; 0x200])] }), &ready_maps, &source);
        assert_eq!(missing_mz.classification, EarlyReadinessClass::PreferredHeaderReadFailed);
        let probe_eof = diagnose_empty_early_lookup(&HeaderOnlyMemory(SyntheticMemory { chunks: vec![] }), &ready_maps, &source);
        assert_eq!(probe_eof.classification, EarlyReadinessClass::PreferredHeaderReadFailed);
        let mut mismatched_bytes = synthetic_pe(preferred);
        mismatched_bytes[0x88..0x8c].copy_from_slice(&2u32.to_le_bytes());
        let mismatch = diagnose_empty_early_lookup(&HeaderOnlyMemory(SyntheticMemory { chunks: vec![(preferred, mismatched_bytes)] }), &ready_maps, &source);
        assert_eq!(mismatch.classification, EarlyReadinessClass::PreferredHeaderLayoutMismatch);

        let rx_rejection = diagnose_empty_early_lookup(&ready_memory, ready_maps.as_slice(), &source);
        assert_eq!(rx_rejection.classification, EarlyReadinessClass::HeaderMatchedOtherCandidateRejection);
        let serialized = rw_metadata.json().to_string();
        assert!(!serialized.contains("0x") && !serialized.contains("/"));
    }

    #[test]
    fn early_metadata_accepts_rw_payload_and_late_rx_promotion_only_with_same_backing() {
        let preferred = 0x1000_1000;
        let source = synthetic_source(preferred);
        let mut header = synthetic_map(0x1000_0000, preferred + 0x1000);
        header.perms = "r--p".to_string();
        let mut early_payload = synthetic_map(preferred + 0x1000, 0x2000_0000);
        early_payload.perms = "rw-p".to_string();
        let early_maps = vec![header.clone(), early_payload.clone()];
        let memory = HeaderOnlyMemory(SyntheticMemory { chunks: vec![(preferred, synthetic_pe(preferred))] });
        let early = find_early_header_candidates(&memory, &early_maps, &source).unwrap();
        assert_eq!(early.len(), 1);

        let mut late_payload = early_payload.clone();
        late_payload.perms = "r-xp".to_string();
        let late_maps = vec![header, late_payload.clone()];
        let late = find_candidates(&memory, &late_maps, &source).unwrap();
        assert_eq!(late.len(), 1);
        assert!(historical_backing_matches(&late[0].coverage, &early[0].coverage));

        let changed_backing = MapEntry { inode: late_payload.inode + 1, ..late_payload };
        let changed = find_candidates(&memory, &[synthetic_map(0x1000_0000, preferred + 0x1000), changed_backing], &source).unwrap();
        assert_eq!(changed.len(), 1);
        assert!(!historical_backing_matches(&changed[0].coverage, &early[0].coverage));
    }

    #[test]
    fn historical_refresh_never_rebinds_to_another_metadata_eligible_pe_copy() {
        let old_base = 0x1000_1000;
        let other_base = 0x5000_1000;
        let source = synthetic_source(old_base);
        let old_map = MapEntry { start: old_base, end: old_base + 0x4000_0000, perms: "r--p".to_string(), offset: 0, device: "08:01".to_string(), inode: 7, path_kind: "file" };
        let other_map = MapEntry { start: other_base, end: other_base + 0x4000_0000, perms: "r--p".to_string(), offset: 0, device: "08:01".to_string(), inode: 8, path_kind: "file" };
        let maps = vec![old_map, other_map];
        let historical = HistoricalCandidate { base: old_base, layout: source.clone(), coverage: normalized_metadata_coverage(&maps, old_base).unwrap() };
        let memory = HeaderOnlyMemory(SyntheticMemory { chunks: vec![(other_base, synthetic_pe(other_base))] });

        let candidates = find_early_header_candidates(&memory, &maps, &source).unwrap();
        assert_eq!(candidates.iter().map(|candidate| candidate.base).collect::<Vec<_>>(), vec![other_base]);
        assert!(!refresh_historical_early(&memory, &maps, &maps, &historical).unwrap());
    }

    #[test]
    fn historical_refresh_rejects_a_valid_but_wrong_layout_at_the_pinned_base() {
        let base = 0x1000_1000;
        let source = synthetic_source(base);
        let map = MapEntry { start: base, end: base + 0x4000_0000, perms: "r--p".to_string(), offset: 0, device: "08:01".to_string(), inode: 7, path_kind: "file" };
        let maps = vec![map];
        let historical = HistoricalCandidate { base, layout: source.clone(), coverage: normalized_metadata_coverage(&maps, base).unwrap() };
        let mut wrong_layout = synthetic_pe(base);
        wrong_layout[0x88..0x8c].copy_from_slice(&2u32.to_le_bytes());
        let memory = HeaderOnlyMemory(SyntheticMemory { chunks: vec![(base, wrong_layout)] });

        assert_eq!(refresh_historical_early(&memory, &maps, &maps, &historical).unwrap_err(), "historical candidate header layout changed");
    }

    #[test]
    fn metadata_ready_reports_sampled_rw_windows_and_bounded_early_fallback() {
        let preferred = 0x1000_1000;
        let header = {
            let mut map = synthetic_map(0x1000_0000, preferred + 0x1000);
            map.perms = "r--p".to_string();
            map
        };
        let mut payload = synthetic_map(preferred + 0x1000, 0x2000_0000);
        payload.perms = "rw-p".to_string();
        let maps = vec![header, payload];
        let metadata = EarlyReadinessMetadata::metadata_ready([
            early_window_permission(&maps, preferred, WINDOWS[0]),
            early_window_permission(&maps, preferred, WINDOWS[1]),
        ], false, true);
        assert_eq!(metadata.classification, EarlyReadinessClass::MetadataReady);
        assert_eq!(metadata.windows[0].permission, EarlyWindowPermission::Rw);
        assert_eq!(metadata.windows[1].permission, EarlyWindowPermission::Rw);
        let json = metadata.json();
        assert_eq!(json["classification"], "metadata-ready");
        assert_eq!(json["windows"][0]["permission"], "rw");
        assert!(!json["currentHeaderObserved"].as_bool().unwrap());
        assert!(json["basePinned"].as_bool().unwrap());
    }

    #[test]
    fn readonly_interior_pe_header_with_rx_text_is_a_valid_candidate() {
        let preferred = 0x1000_1000;
        let source = synthetic_source(preferred);
        let mut header = synthetic_map(0x1000_0000, preferred + 0x1000);
        header.perms = "r--p".to_string();
        let text = synthetic_map(preferred + 0x1000, 0x2000_0000);
        let memory = SyntheticMemory { chunks: vec![(preferred, synthetic_pe(preferred))] };
        let candidates = find_candidates(&memory, &[header.clone(), text.clone()], &source).unwrap();
        assert_eq!(candidates.len(), 1);
        assert_eq!(candidates[0].base, preferred);
        let mut nonexecuting_text = text;
        nonexecuting_text.perms = "r--p".to_string();
        assert!(find_candidates(&memory, &[header, nonexecuting_text], &source).unwrap().is_empty());
    }

    #[test]
    fn candidate_address_capacity_is_strict_and_never_truncates() {
        let source = synthetic_source(0x9000_0000);
        let maps = (0..=MAX_CANDIDATES as u64).map(|index| synthetic_map(0x1000_0000 + index * 0x10000, 0x1000_0000 + index * 0x10000 + 0x2000)).collect::<Vec<_>>();
        assert_eq!(candidate_addresses(&maps, &source).unwrap_err(), "runtime PE candidate inspection bound exceeded");
    }

    #[test]
    fn exclusive_commit_never_replaces_existing_destination() {
        let root = std::env::temp_dir().join(format!("capture-commit-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
        std::fs::create_dir(&root).unwrap();
        let temporary = root.join("temporary");
        let destination = root.join("destination");
        write_exclusive(&temporary, b"new").unwrap();
        write_exclusive(&destination, b"old").unwrap();
        assert!(commit_exclusive(&temporary, &destination).is_err());
        assert_eq!(std::fs::read(&destination).unwrap(), b"old");
        std::fs::remove_file(&destination).unwrap();
        commit_exclusive(&temporary, &destination).unwrap();
        assert!(!temporary.exists());
        assert_eq!(std::fs::read(&destination).unwrap(), b"new");
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn storage_preflight_uses_atomic_no_replace_and_leaves_no_probe_files() {
        let root = std::env::temp_dir().join(format!("functional-storage-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
        std::fs::create_dir(&root).unwrap();
        std::fs::set_permissions(&root, std::fs::Permissions::from_mode(0o700)).unwrap();
        storage_preflight(&root).unwrap();
        assert_eq!(std::fs::read_dir(&root).unwrap().count(), 0);

        let temporary = root.join("temporary");
        let destination = root.join("destination");
        write_exclusive(&temporary, b"first").unwrap();
        commit_exclusive(&temporary, &destination).unwrap();
        assert!(!temporary.exists());
        assert_eq!(std::fs::read(&destination).unwrap(), b"first");
        let replacement = root.join("replacement");
        write_exclusive(&replacement, b"second").unwrap();
        assert!(commit_exclusive(&replacement, &destination).is_err());
        assert_eq!(std::fs::read(&destination).unwrap(), b"first");
        assert_eq!(std::fs::read(&replacement).unwrap(), b"second");
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn storage_preflight_readonly_parent_fails_closed_without_residuals() {
        let root = std::env::temp_dir().join(format!("functional-storage-readonly-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
        std::fs::create_dir(&root).unwrap();
        std::fs::set_permissions(&root, std::fs::Permissions::from_mode(0o500)).unwrap();
        let result = storage_preflight(&root);
        std::fs::set_permissions(&root, std::fs::Permissions::from_mode(0o700)).unwrap();
        assert!(result.is_err());
        assert_eq!(std::fs::read_dir(&root).unwrap().count(), 0);
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn capture_timing_is_late_and_never_claims_an_early_second_snapshot() {
        assert_eq!(timing_index(Duration::from_secs(19), 0), None);
        assert_eq!(timing_index(Duration::from_secs(20), 0), Some(0));
        assert_eq!(timing_index(Duration::from_secs(59), 1), None);
        assert_eq!(timing_index(Duration::from_secs(60), 1), Some(1));
        // A late first poll still fills slot zero; it cannot skip to a
        // duplicate second-slot path.
        assert_eq!(timing_index(Duration::from_secs(80), 0), Some(0));
    }

    #[test]
    fn source_windows_require_executable_text_and_full_raw_backing() {
        let text = PeSection { name: *b".text\0\0\0", virtual_size: 0x4000_0000, virtual_address: 0, raw_size: 0x4000_0000, raw_pointer: 0, characteristics: 0x6000_0020 };
        let layout = PeLayout { machine: 0xaa64, timestamp: 1, section_count: 1, optional_size: 240, source_image_base: 0x1000, entry_rva: 0, image_size: 0xffff_ffff, header_size: 0x400, sections: vec![text.clone()] };
        assert!(validate_source_windows(&layout, SOURCE_SIZE).is_ok());
        let mut no_exec = text.clone(); no_exec.characteristics &= !0x2000_0000;
        assert!(validate_source_windows(&PeLayout { sections: vec![no_exec], ..layout.clone() }, SOURCE_SIZE).is_err());
        let mut short_raw = text; short_raw.raw_size = 0x223c000 + 0x4000 - 1;
        assert!(validate_source_windows(&PeLayout { sections: vec![short_raw], ..layout }, SOURCE_SIZE).is_err());
    }

    #[test]
    fn snapshot_budget_and_state_guard_are_bounded_and_retryable() {
        let state = Arc::new(Mutex::new(CaptureState { attempts: 0, successes: 0, in_flight: true, early_guard_failed: false, early_readiness: None, historical: None }));
        assert!(state.lock().unwrap().in_flight);
        {
            let mut locked = state.lock().unwrap();
            locked.in_flight = false;
            locked.attempts = MAX_ATTEMPTS;
            locked.successes = MAX_SNAPSHOTS;
        }
        let locked = state.lock().unwrap();
        assert_eq!(locked.successes, 2);
        assert_eq!(SNAPSHOT_BYTES * usize::from(MAX_SNAPSHOTS), 65536);
        assert_eq!(timing_json(&locked, "COMPLETE", None)["writeAuthorized"], false);
    }

    #[test]
    fn map_change_and_historical_basis_remain_incomplete_not_authority() {
        let before = MapEntry { start: 1, end: 2, perms: "r-xp".to_string(), offset: 0, device: "00:01".to_string(), inode: 4, path_kind: "file" };
        let mut after = before.clone(); after.inode = 5;
        assert_ne!(vec![before], vec![after]);
        let state = CaptureState { attempts: 1, successes: 0, in_flight: false, early_guard_failed: false, early_readiness: None, historical: None };
        let output = timing_json(&state, "INCOMPLETE", Some("historical candidate has no current module proof"));
        assert_eq!(output["status"], "INCOMPLETE");
        assert_eq!(output["writeAuthorized"], false);
        assert_eq!(output["historicalAvailable"], false);
        let historical = HistoricalCandidate { base: 1, layout: PeLayout::empty(), coverage: Vec::new() };
        let state = CaptureState { historical: Some(historical), ..state };
        assert_eq!(timing_json(&state, "INCOMPLETE", None)["historicalAvailable"], true);
    }
}
