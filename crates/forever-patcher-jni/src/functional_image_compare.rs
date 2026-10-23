//! Bounded, read-only comparison of fixed PE image windows.
//!
//! This is deliberately a diagnostic, not a loader or a patch authorizer.  It
//! only accepts the image selected by the functional session, keeps the source
//! file open with `O_NOFOLLOW`, compares one fixed RVA, and emits metrics
//! without addresses or bytes.

use super::ReadOnlyMemory;
use forever_patcher::MemoryAccess;
use serde_json::{json, Value};
use std::fs::{File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom};
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::path::Path;

const SOURCE_SIZE: u64 = 94_286_544;
const MAX_PE_HEADERS: usize = 64 * 1024;
const MAX_SECTIONS: usize = 96;
const MAX_HEADER_CANDIDATES: usize = 1024;
const MAX_MAPS_BYTES: usize = 2 * 1024 * 1024;
const MAX_RELOCATION_BYTES: usize = 16 * 1024 * 1024;

// This is a fixed source/runtime slot observed in the upstream ARM64 image.
// It is a diagnostic comparison only; its relationship to authentication is
// deliberately not inferred here.
const WINDOWS: [ImageWindow; 1] = [
    ImageWindow { name: "upstream-ed25519-prefix-slot", rva: 0x4a5ff00, length: 32 },
];

#[derive(Clone, Copy)]
struct ImageWindow {
    name: &'static str,
    rva: u64,
    length: usize,
}

#[derive(Clone, Debug)]
struct RuntimeMap {
    start: u64,
    end: u64,
    readable: bool,
    writable: bool,
    executable: bool,
    private: bool,
    device: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(super) struct FileIdentity {
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
    file: File,
    identity: FileIdentity,
}

impl HeldSource {
    fn open(path: &Path, expected_size: u64) -> Result<Self, String> {
        let file = OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
            .open(path)
            .map_err(|_| "source file could not be opened read-only".to_string())?;
        let identity = FileIdentity::from_metadata(
            &file.metadata().map_err(|_| "source metadata could not be read".to_string())?,
        );
        if !identity.regular() {
            return Err("source is not a regular file".to_string());
        }
        if identity.size != expected_size {
            return Err("source file size is not the fixed authenticated build size".to_string());
        }
        Ok(Self { file, identity })
    }

    fn stable(&self) -> bool {
        self.file
            .metadata()
            .ok()
            .map(|metadata| FileIdentity::from_metadata(&metadata) == self.identity)
            .unwrap_or(false)
    }

    fn read_exact_at(&mut self, offset: u64, bytes: &mut [u8]) -> io::Result<()> {
        let length = u64::try_from(bytes.len()).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "source read length overflow"))?;
        if offset.checked_add(length).filter(|end| *end <= self.identity.size).is_none() {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "source read is outside the held file"));
        }
        self.file.seek(SeekFrom::Start(offset))?;
        self.file.read_exact(bytes)
    }
}

#[derive(Clone, Debug)]
struct Section {
    name: [u8; 8],
    virtual_size: u32,
    virtual_address: u32,
    raw_size: u32,
    raw_pointer: u32,
    characteristics: u32,
}

impl Section {
    fn name_text(&self) -> String {
        let end = self.name.iter().position(|byte| *byte == 0).unwrap_or(self.name.len());
        String::from_utf8_lossy(&self.name[..end]).to_string()
    }

    fn image_end(&self) -> Option<u64> {
        u64::from(self.virtual_address).checked_add(u64::from(self.virtual_size.max(self.raw_size)))
    }

    fn contains_image_range(&self, rva: u64, length: usize) -> bool {
        let Some(end) = rva.checked_add(length as u64) else { return false };
        let Some(section_end) = self.image_end() else { return false };
        rva >= u64::from(self.virtual_address) && end <= section_end
    }

    fn contains_raw_range(&self, rva: u64, length: usize) -> bool {
        let Some(end) = rva.checked_add(length as u64) else { return false };
        let section_start = u64::from(self.virtual_address);
        let Some(section_end) = section_start.checked_add(u64::from(self.raw_size)) else { return false };
        rva >= section_start && end <= section_end
    }
}

#[derive(Clone, Debug)]
pub(super) struct PeHeader {
    machine: u16,
    time_date_stamp: u32,
    section_count: u16,
    optional_size: u16,
    entry_rva: u32,
    image_base: u64,
    size_image: u32,
    size_headers: u32,
    sections: Vec<Section>,
    reloc_directory: Option<(u32, u32)>,
}

impl PeHeader {
    fn parse(bytes: &[u8], file_size: Option<u64>) -> Result<Self, String> {
        if bytes.len() < 0x40 || &bytes[..2] != b"MZ" {
            return Err("candidate is not an MZ image".to_string());
        }
        let pe_offset = u64::from(read_u32(bytes, 0x3c)?);
        let pe_offset_usize = usize::try_from(pe_offset).map_err(|_| "PE header offset is not representable".to_string())?;
        let coff_start = pe_offset_usize.checked_add(4).ok_or_else(|| "PE header offset overflow".to_string())?;
        let coff_end = coff_start.checked_add(20).ok_or_else(|| "COFF header overflow".to_string())?;
        if coff_end > bytes.len() || &bytes[pe_offset_usize..coff_start] != b"PE\0\0" {
            return Err("PE signature or COFF header is outside the bounded header".to_string());
        }
        let machine = read_u16(bytes, coff_start)?;
        let section_count = read_u16(bytes, coff_start + 2)?;
        let time_date_stamp = read_u32(bytes, coff_start + 4)?;
        let optional_size = read_u16(bytes, coff_start + 16)?;
        if machine != 0xaa64 || section_count == 0 || usize::from(section_count) > MAX_SECTIONS {
            return Err("candidate is not a bounded PE32+ AA64 image".to_string());
        }
        let optional_start = coff_end;
        let optional_end = optional_start.checked_add(usize::from(optional_size)).ok_or_else(|| "optional header overflow".to_string())?;
        if optional_end > bytes.len() || optional_size < 112 || read_u16(bytes, optional_start)? != 0x20b {
            return Err("PE32+ optional header is incomplete".to_string());
        }
        let entry_rva = read_u32(bytes, optional_start + 16)?;
        let image_base = read_u64(bytes, optional_start + 24)?;
        let size_image = read_u32(bytes, optional_start + 56)?;
        let size_headers = read_u32(bytes, optional_start + 60)?;
        let number_of_directories = read_u32(bytes, optional_start + 108)?;
        if size_image == 0 || size_headers == 0 || usize::try_from(size_headers).ok().is_none_or(|size| size > MAX_PE_HEADERS) {
            return Err("PE image/header size is outside the fixed bound".to_string());
        }
        let section_start = optional_end;
        let section_bytes = usize::from(section_count)
            .checked_mul(40)
            .ok_or_else(|| "section table size overflow".to_string())?;
        let section_end = section_start.checked_add(section_bytes).ok_or_else(|| "section table overflow".to_string())?;
        if section_end > bytes.len() || section_end > MAX_PE_HEADERS || section_end > usize::try_from(size_headers).unwrap_or(0) {
            return Err("section table is outside bounded PE headers".to_string());
        }
        let mut sections = Vec::with_capacity(usize::from(section_count));
        for index in 0..usize::from(section_count) {
            let offset = section_start + index * 40;
            let mut name = [0u8; 8];
            name.copy_from_slice(&bytes[offset..offset + 8]);
            let section = Section {
                name,
                virtual_size: read_u32(bytes, offset + 8)?,
                virtual_address: read_u32(bytes, offset + 12)?,
                raw_size: read_u32(bytes, offset + 16)?,
                raw_pointer: read_u32(bytes, offset + 20)?,
                characteristics: read_u32(bytes, offset + 36)?,
            };
            let image_end = section.image_end().ok_or_else(|| "section image range overflow".to_string())?;
            if image_end > u64::from(size_image) {
                return Err("section exceeds PE SizeOfImage".to_string());
            }
            if let Some(file_size) = file_size {
                let raw_end = u64::from(section.raw_pointer)
                    .checked_add(u64::from(section.raw_size))
                    .ok_or_else(|| "section raw range overflow".to_string())?;
                if raw_end > file_size {
                    return Err("section raw range exceeds source file".to_string());
                }
            }
            sections.push(section);
        }
        let reloc_directory = if number_of_directories >= 6 && optional_size >= 112 + 6 * 8 {
            let offset = optional_start + 112 + 5 * 8;
            let rva = read_u32(bytes, offset)?;
            let size = read_u32(bytes, offset + 4)?;
            (rva != 0 && size != 0).then_some((rva, size))
        } else {
            None
        };
        Ok(Self {
            machine,
            time_date_stamp,
            section_count,
            optional_size,
            entry_rva,
            image_base,
            size_image,
            size_headers,
            sections,
            reloc_directory,
        })
    }

    fn header_matches(&self, other: &Self) -> bool {
        self.machine == other.machine
            && self.time_date_stamp == other.time_date_stamp
            && self.section_count == other.section_count
            && self.optional_size == other.optional_size
            && self.entry_rva == other.entry_rva
            && self.size_image == other.size_image
            && self.size_headers == other.size_headers
            && self.sections.len() == other.sections.len()
            && self.sections.iter().zip(&other.sections).all(|(left, right)| {
                left.name == right.name
                    && left.virtual_size == right.virtual_size
                    && left.virtual_address == right.virtual_address
                    && left.raw_size == right.raw_size
                    && left.raw_pointer == right.raw_pointer
                    && left.characteristics == right.characteristics
            })
    }

    fn section_for_image_range(&self, rva: u64, length: usize) -> Option<&Section> {
        self.sections.iter().find(|section| section.contains_image_range(rva, length))
    }

    fn section_for_raw_rva(&self, rva: u64, length: usize) -> Option<&Section> {
        self.sections.iter().find(|section| section.contains_raw_range(rva, length))
    }

    fn raw_offset(&self, rva: u64, length: usize) -> Option<u64> {
        let section = self.section_for_raw_rva(rva, length)?;
        u64::from(section.raw_pointer).checked_add(rva.checked_sub(u64::from(section.virtual_address))?)
    }
}

/// A deliberately private observation retained only by the active functional
/// lease. It is a bounded read hypothesis, not a module identity or proof.
#[derive(Clone, Debug)]
pub(super) struct RememberedCandidate {
    pub(super) base: u64,
    pub(super) source_header: PeHeader,
    pub(super) source_identity: FileIdentity,
    pub(super) target_pid: u32,
    pub(super) target_start_ticks: u64,
    pub(super) target_ppid: u32,
    pub(super) target_uid4: [u32; 4],
    pub(super) target_cmdline: Vec<u8>,
}

#[derive(Clone, Debug)]
struct RelocationInfo {
    dir64_rvas: Vec<u64>,
    available: bool,
    limitation: Option<&'static str>,
}

impl RelocationInfo {
    fn unavailable(reason: &'static str) -> Self {
        Self { dir64_rvas: Vec::new(), available: false, limitation: Some(reason) }
    }
}

#[derive(Clone, Debug)]
struct WindowMetrics {
    name: &'static str,
    rva: u64,
    length: usize,
    section: String,
    source_zero_fill_bytes: usize,
    source_zero_bytes: usize,
    runtime_zero_bytes: usize,
    runtime_nonzero_bytes: usize,
    runtime_distinct_byte_values: usize,
    runtime_most_common_byte_count: usize,
    runtime_executable_bytes: usize,
    runtime_writable_bytes: usize,
    equal_bytes: usize,
    changed_bytes: usize,
    normalized_equal_bytes: Option<usize>,
    relocation_bytes_masked: usize,
    relocation_mismatches: usize,
    known_key_matches: [bool; 12],
    replacement_key_match: bool,
    source_prefix_still_matches: bool,
    file_slot_equal: usize,
    runtime_prefix_equal: Option<bool>,
}

impl WindowMetrics {
    fn json(&self) -> Value {
        json!({
            "name": self.name,
            "rva": self.rva,
            "length": self.length,
            "section": self.section,
            "sourceZeroFillBytes": self.source_zero_fill_bytes,
            "sourceZeroBytes": self.source_zero_bytes,
            "runtimeZeroBytes": self.runtime_zero_bytes,
            "runtimeNonzeroBytes": self.runtime_nonzero_bytes,
            "runtimeDistinctByteValues": self.runtime_distinct_byte_values,
            "runtimeMostCommonByteCount": self.runtime_most_common_byte_count,
            "runtimeExecutableBytes": self.runtime_executable_bytes,
            "runtimeWritableBytes": self.runtime_writable_bytes,
            "equalBytes": self.equal_bytes,
            "changedBytes": self.changed_bytes,
            "normalizedEqualBytes": self.normalized_equal_bytes,
            "relocationBytesMasked": self.relocation_bytes_masked,
            "relocationMismatches": self.relocation_mismatches,
            "knownKeyMatches": self.known_key_matches,
            "replacementKeyMatch": self.replacement_key_match,
            "sourcePrefixStillMatches": self.source_prefix_still_matches,
            "fileSlotEqual": self.file_slot_equal,
            "runtimePrefixEqual": self.runtime_prefix_equal,
        })
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct SlotMetrics {
    known_key_matches: [bool; 12],
    replacement_key_match: bool,
    source_prefix_still_matches: bool,
    file_slot_equal: usize,
    runtime_prefix_equal: Option<bool>,
}

fn compare_slot(
    source: &[u8],
    runtime: &[u8],
    contract: &forever_patcher::KeyStoreContract,
) -> Option<SlotMetrics> {
    if source.len() != 32
        || runtime.len() != 32
        || contract.entry_size != 40
        || contract.entry_count != 12
        || contract.anchor_group == 0
        || contract.anchor_group > contract.entry_count
        || contract.known_keys.len() != 12
        || contract.target_group == 0
        || contract.target_group > contract.entry_count
    {
        return None;
    }
    let mut known_key_matches = [false; 12];
    for (index, key) in contract.known_keys.iter().enumerate() {
        known_key_matches[index] = runtime == key;
    }
    let file_slot_equal = source.iter().zip(runtime).filter(|(left, right)| left == right).count();
    // An eight-byte locator is not the complete 32-byte public key.
    let runtime_prefix_equal = Some(runtime[..8] == source[..8]);
    Some(SlotMetrics {
        known_key_matches,
        replacement_key_match: runtime == contract.new_key,
        source_prefix_still_matches: source[..8] == contract.known_keys[8][..8],
        file_slot_equal,
        runtime_prefix_equal,
    })
}

#[derive(Clone, Debug)]
pub(super) struct ImageComparison {
    pub(super) complete: bool,
    pub(super) status: &'static str,
    pub(super) error_kind: Option<&'static str>,
    pub(super) error: Option<String>,
    candidate_count: usize,
    header_candidates_examined: usize,
    source_fd_stable: bool,
    header_structure_matches: bool,
    aslr_detected: bool,
    preferred_image_base_matches: bool,
    windows: Vec<WindowMetrics>,
    total_equal_bytes: usize,
    total_changed_bytes: usize,
    normalized_equal_bytes: Option<usize>,
    relocation_bytes_masked: usize,
    relocation_mismatches: usize,
    relocation_limitation: Option<&'static str>,
    comparison_basis: &'static str,
    cached_header_used: bool,
    current_header_candidate_count: usize,
    identity_validated_after_read: bool,
}

impl ImageComparison {
    fn incomplete(kind: &'static str, detail: &'static str) -> Self {
        Self {
            complete: false,
            status: "INCOMPLETE",
            error_kind: Some(kind),
            error: Some(detail.to_string()),
            candidate_count: 0,
            header_candidates_examined: 0,
            source_fd_stable: false,
            header_structure_matches: false,
            aslr_detected: false,
            preferred_image_base_matches: false,
            windows: Vec::new(),
            total_equal_bytes: 0,
            total_changed_bytes: 0,
            normalized_equal_bytes: None,
            relocation_bytes_masked: 0,
            relocation_mismatches: 0,
            relocation_limitation: None,
            comparison_basis: "none",
            cached_header_used: false,
            current_header_candidate_count: 0,
            identity_validated_after_read: false,
        }
    }

    fn after_source(mut self, header_structure_matches: bool, source: &HeldSource) -> Self {
        self.source_fd_stable = source.stable();
        self.header_structure_matches = header_structure_matches;
        self
    }

    pub(super) fn drop_observations(&mut self, error_kind: &'static str, detail: &'static str) {
        self.complete = false;
        self.status = "INCOMPLETE";
        self.error_kind = Some(error_kind);
        self.error = Some(detail.to_string());
        self.candidate_count = 0;
        self.header_candidates_examined = 0;
        self.source_fd_stable = false;
        self.header_structure_matches = false;
        self.aslr_detected = false;
        self.preferred_image_base_matches = false;
        self.windows.clear();
        self.total_equal_bytes = 0;
        self.total_changed_bytes = 0;
        self.normalized_equal_bytes = None;
        self.relocation_bytes_masked = 0;
        self.relocation_mismatches = 0;
        self.relocation_limitation = None;
        self.comparison_basis = "none";
        self.cached_header_used = false;
        self.current_header_candidate_count = 0;
        self.identity_validated_after_read = false;
    }

    pub(super) fn mark_identity_validated_after_read(&mut self) {
        self.identity_validated_after_read = true;
    }

    pub(super) fn json(&self) -> Value {
        json!({
            "status": self.status,
            "readOnly": true,
            "writeAuthorized": false,
            "cryptographicProof": false,
            "sourceBoundBy": if self.source_fd_stable {
                if self.header_structure_matches {
                    json!(["held-fd", "stable-metadata", "matching-pe-structure"])
                } else {
                    json!(["held-fd", "stable-metadata"])
                }
            } else {
                json!([])
            },
            "referenceSha256Checked": false,
            "sourceFileSize": SOURCE_SIZE,
            "candidateCount": self.candidate_count,
            "headerCandidatesExamined": self.header_candidates_examined,
            "comparisonBasis": self.comparison_basis,
            "cachedHeaderUsed": self.cached_header_used,
            "currentHeaderCandidateCount": self.current_header_candidate_count,
            "observationsFromEarlierHeader": self.cached_header_used,
            "identityValidatedAfterRead": self.identity_validated_after_read,
            "headerStructureMatches": self.header_structure_matches,
            "aslrDetected": self.aslr_detected,
            "preferredImageBaseMatches": self.preferred_image_base_matches,
            "preferredImageBaseLimitation": if self.preferred_image_base_matches { Value::Null } else { json!("runtime preferred ImageBase differs; ASLR uses the mapped candidate base") },
            "windows": self.windows.iter().map(WindowMetrics::json).collect::<Vec<_>>(),
            "totalEqualBytes": self.total_equal_bytes,
            "totalChangedBytes": self.total_changed_bytes,
            "normalizedEqualBytes": self.normalized_equal_bytes,
            "relocationBytesMasked": self.relocation_bytes_masked,
            "relocationMismatches": self.relocation_mismatches,
            "relocationLimitation": self.relocation_limitation,
            "relocationDifferencesAreNotUnpackingProof": true,
            "complete": self.complete,
        })
    }
}

fn read_u16(bytes: &[u8], offset: usize) -> Result<u16, String> {
    let end = offset.checked_add(2).ok_or_else(|| "header field offset overflow".to_string())?;
    let value = bytes.get(offset..end).ok_or_else(|| "header field is outside the bounded read".to_string())?;
    Ok(u16::from_le_bytes([value[0], value[1]]))
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, String> {
    let end = offset.checked_add(4).ok_or_else(|| "header field offset overflow".to_string())?;
    let value = bytes.get(offset..end).ok_or_else(|| "header field is outside the bounded read".to_string())?;
    Ok(u32::from_le_bytes([value[0], value[1], value[2], value[3]]))
}

fn read_u64(bytes: &[u8], offset: usize) -> Result<u64, String> {
    let end = offset.checked_add(8).ok_or_else(|| "header field offset overflow".to_string())?;
    let value = bytes.get(offset..end).ok_or_else(|| "header field is outside the bounded read".to_string())?;
    Ok(u64::from_le_bytes(value.try_into().map_err(|_| "header field has invalid length".to_string())?))
}

fn parse_maps(text: &[u8]) -> Result<Vec<RuntimeMap>, String> {
    if text.is_empty() || text.len() > MAX_MAPS_BYTES || !text.ends_with(b"\n") {
        return Err("maps text is empty or truncated".to_string());
    }
    let text = std::str::from_utf8(text).map_err(|_| "maps text is not UTF-8".to_string())?;
    let mut maps = Vec::new();
    let mut previous_end = 0u64;
    for line in text.lines() {
        let mut columns = line.split_whitespace();
        let range = columns.next().ok_or_else(|| "maps line has no range".to_string())?;
        let permissions = columns.next().ok_or_else(|| "maps line has no permissions".to_string())?;
        let offset = columns.next().ok_or_else(|| "maps line has no offset".to_string())?;
        let device = columns.next().ok_or_else(|| "maps line has no device".to_string())?;
        let inode = columns.next().ok_or_else(|| "maps line has no inode".to_string())?;
        let (start_text, end_text) = range.split_once('-').ok_or_else(|| "maps line has malformed range".to_string())?;
        let start = u64::from_str_radix(start_text, 16).map_err(|_| "maps start is malformed".to_string())?;
        let end = u64::from_str_radix(end_text, 16).map_err(|_| "maps end is malformed".to_string())?;
        if end <= start || start < previous_end || permissions.len() != 4 {
            return Err("maps range or ordering is malformed".to_string());
        }
        let permission_bytes = permissions.as_bytes();
        if u64::from_str_radix(offset, 16).is_err()
            || inode.parse::<u64>().is_err()
            || !permission_bytes.iter().take(3).all(|byte| matches!(byte, b'r' | b'w' | b'x' | b'-'))
            || !matches!(permission_bytes[3], b'p' | b's')
            || device.split_once(':').is_none_or(|(major, minor)| {
                major.is_empty() || minor.is_empty() || u64::from_str_radix(major, 16).is_err() || u64::from_str_radix(minor, 16).is_err()
            })
        {
            return Err("maps metadata is malformed".to_string());
        }
        let pathname = columns.next().unwrap_or("");
        maps.push(RuntimeMap {
            start,
            end,
            readable: permission_bytes[0] == b'r',
            writable: permission_bytes[1] == b'w',
            executable: permission_bytes[2] == b'x',
            private: permission_bytes[3] == b'p',
            device: pathname == "/dev" || pathname.starts_with("/dev/"),
        });
        previous_end = end;
        if maps.len() > super::MAX_REGIONS {
            return Err("maps region bound exceeded".to_string());
        }
    }
    Ok(maps)
}

fn map_matches(map: &RuntimeMap, executable: bool) -> bool {
    map.readable && map.private && !map.device && (!executable || map.executable)
}

fn read_covered<M: MemoryAccess + ?Sized>(
    memory: &M,
    maps: &[RuntimeMap],
    address: u64,
    bytes: &mut [u8],
    executable: bool,
) -> io::Result<()> {
    let length = u64::try_from(bytes.len()).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "memory read length overflow"))?;
    let end = address.checked_add(length).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "memory read address overflow"))?;
    let mut cursor = address;
    let mut offset = 0usize;
    while cursor < end {
        let map = maps
            .iter()
            .find(|map| map.start <= cursor && cursor < map.end && map_matches(map, executable))
            .ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "private readable map coverage has a gap"))?;
        let segment_end = map.end.min(end);
        let segment_len = usize::try_from(segment_end - cursor).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "memory segment length overflow"))?;
        let next_offset = offset.checked_add(segment_len).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "memory buffer offset overflow"))?;
        memory.read(cursor, &mut bytes[offset..next_offset])?;
        cursor = segment_end;
        offset = next_offset;
    }
    Ok(())
}

fn read_runtime_window<M: MemoryAccess + ?Sized>(memory: &M, maps: &[RuntimeMap], address: u64, length: usize) -> io::Result<Vec<u8>> {
    let mut bytes = vec![0u8; length];
    read_covered(memory, maps, address, &mut bytes, false)?;
    Ok(bytes)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct WindowDiagnosticCounts {
    source_zero_bytes: usize,
    runtime_zero_bytes: usize,
    runtime_nonzero_bytes: usize,
    runtime_distinct_byte_values: usize,
    runtime_most_common_byte_count: usize,
    runtime_executable_bytes: usize,
    runtime_writable_bytes: usize,
}

fn runtime_window_coverage(maps: &[RuntimeMap], address: u64, length: usize) -> Result<(usize, usize), &'static str> {
    let length_u64 = u64::try_from(length).map_err(|_| "runtime window length overflow")?;
    let end = address.checked_add(length_u64).ok_or("runtime window address overflow")?;
    let mut cursor = address;
    let mut executable_bytes = 0usize;
    let mut writable_bytes = 0usize;
    while cursor < end {
        let map = maps
            .iter()
            .find(|map| map.start <= cursor && cursor < map.end)
            .ok_or("runtime window map coverage has a gap")?;
        if !map_matches(map, false) {
            return Err("runtime window is not covered by private readable maps");
        }
        let segment_end = map.end.min(end);
        let segment_len = usize::try_from(segment_end - cursor).map_err(|_| "runtime window segment length overflow")?;
        if map.executable {
            executable_bytes = executable_bytes.saturating_add(segment_len);
        }
        if map.writable {
            writable_bytes = writable_bytes.saturating_add(segment_len);
        }
        cursor = segment_end;
    }
    Ok((executable_bytes, writable_bytes))
}

fn window_diagnostic_counts(source: &[u8], runtime: &[u8], maps: &[RuntimeMap], runtime_address: u64) -> Result<WindowDiagnosticCounts, &'static str> {
    if source.len() != runtime.len() {
        return Err("source and runtime window lengths differ");
    }
    let (runtime_executable_bytes, runtime_writable_bytes) = runtime_window_coverage(maps, runtime_address, runtime.len())?;
    let mut frequencies = [0usize; 256];
    for byte in runtime {
        frequencies[usize::from(*byte)] = frequencies[usize::from(*byte)].saturating_add(1);
    }
    let runtime_zero_bytes = frequencies[0];
    Ok(WindowDiagnosticCounts {
        source_zero_bytes: source.iter().filter(|byte| **byte == 0).count(),
        runtime_zero_bytes,
        runtime_nonzero_bytes: runtime.len().saturating_sub(runtime_zero_bytes),
        runtime_distinct_byte_values: frequencies.iter().filter(|count| **count != 0).count(),
        runtime_most_common_byte_count: frequencies.into_iter().max().unwrap_or(0),
        runtime_executable_bytes,
        runtime_writable_bytes,
    })
}

fn map_covers_range(maps: &[RuntimeMap], address: u64, length: usize) -> bool {
    let Some(end) = address.checked_add(length as u64) else { return false };
    let mut cursor = address;
    while cursor < end {
        let Some(map) = maps.iter().find(|map| map.start <= cursor && cursor < map.end && map_matches(map, false)) else {
            return false;
        };
        cursor = map.end.min(end);
    }
    true
}

fn fixed_windows_covered(maps: &[RuntimeMap], base: u64) -> bool {
    WINDOWS.iter().all(|window| {
        base.checked_add(window.rva)
            .is_some_and(|address| map_covers_range(maps, address, window.length))
    })
}

fn cached_fallback_allowed(current_header_count: usize) -> bool {
    current_header_count == 0
}

fn cache_candidate(slot: &std::sync::Mutex<Option<RememberedCandidate>>, candidate: RememberedCandidate) {
    if let Ok(mut remembered) = slot.lock() {
        *remembered = Some(candidate);
    }
}

fn candidate_matches(candidate: &RememberedCandidate, source: &HeldSource, source_header: &PeHeader, target: &super::Target) -> bool {
    candidate.source_identity == source.identity
        && candidate.source_header.header_matches(source_header)
        && candidate.target_pid == target.pid
        && candidate.target_start_ticks == target.start_ticks
        && candidate.target_ppid == target.ppid
        && candidate.target_uid4 == target.uid4
        && candidate.target_cmdline == target.cmdline
}

fn cached_candidate(
    slot: &std::sync::Mutex<Option<RememberedCandidate>>,
    source: &HeldSource,
    source_header: &PeHeader,
    target: &super::Target,
) -> Option<RememberedCandidate> {
    let mut remembered = slot.lock().ok()?;
    let matches = remembered.as_ref().is_some_and(|candidate| candidate_matches(candidate, source, source_header, target));
    if matches {
        remembered.clone()
    } else {
        // A source replacement or target identity change consumes the private
        // observation. It must never become a cross-PID or cross-source cache.
        *remembered = None;
        None
    }
}

fn read_source_header(source: &mut HeldSource) -> Result<PeHeader, String> {
    let mut initial = [0u8; 0x40];
    source.read_exact_at(0, &mut initial).map_err(|_| "source DOS header could not be read".to_string())?;
    let pe_offset = usize::try_from(u64::from(read_u32(&initial, 0x3c)?)).map_err(|_| "source PE offset is not representable".to_string())?;
    let prefix_len = pe_offset
        .checked_add(4 + 20)
        .ok_or_else(|| "source COFF prefix overflow".to_string())?;
    if prefix_len > MAX_PE_HEADERS {
        return Err("source PE header exceeds fixed header bound".to_string());
    }
    let mut prefix = vec![0u8; prefix_len.max(initial.len())];
    source.read_exact_at(0, &mut prefix).map_err(|_| "source COFF header could not be read".to_string())?;
    let optional_size = usize::from(read_u16(&prefix, pe_offset + 4 + 16)?);
    let section_count = usize::from(read_u16(&prefix, pe_offset + 4 + 2)?);
    let header_len = pe_offset
        .checked_add(4 + 20)
        .and_then(|value| value.checked_add(optional_size))
        .and_then(|value| value.checked_add(section_count.checked_mul(40)?))
        .ok_or_else(|| "source PE header length overflow".to_string())?;
    if header_len > MAX_PE_HEADERS {
        return Err("source PE header exceeds fixed header bound".to_string());
    }
    let mut header = vec![0u8; header_len.max(initial.len())];
    source.read_exact_at(0, &mut header).map_err(|_| "source section layout could not be read".to_string())?;
    PeHeader::parse(&header, Some(source.identity.size))
}

fn read_source_image_window(source: &mut HeldSource, header: &PeHeader, rva: u64, length: usize) -> Result<(Vec<u8>, usize), String> {
    let section = header.section_for_image_range(rva, length)
        .ok_or_else(|| "fixed source window exceeds its PE section".to_string())?;
    let relative = rva.checked_sub(u64::from(section.virtual_address))
        .ok_or_else(|| "source virtual offset overflow".to_string())?;
    let available = u64::from(section.raw_size).saturating_sub(relative);
    let backed = available.min(length as u64) as usize;
    let mut bytes = vec![0u8; length];
    if backed > 0 {
        let offset = u64::from(section.raw_pointer).checked_add(relative)
            .ok_or_else(|| "source raw offset overflow".to_string())?;
        source.read_exact_at(offset, &mut bytes[..backed])
            .map_err(|_| "source image window file bytes are unreadable".to_string())?;
    }
    // PE VirtualSize beyond SizeOfRawData is zero-initialized storage, not bytes
    // from the following file section. Bootstrap writes to precisely this BSS.
    Ok((bytes, length - backed))
}

fn read_runtime_header<M: MemoryAccess + ?Sized>(memory: &M, maps: &[RuntimeMap], base: u64) -> Result<PeHeader, String> {
    let mut initial = vec![0u8; 0x40];
    read_covered(memory, maps, base, &mut initial, false).map_err(|_| "runtime header candidate could not be read".to_string())?;
    let pe_offset = usize::try_from(u64::from(read_u32(&initial, 0x3c)?)).map_err(|_| "runtime PE offset is not representable".to_string())?;
    let prefix_len = pe_offset.checked_add(4 + 20).ok_or_else(|| "runtime COFF prefix overflow".to_string())?;
    if prefix_len > MAX_PE_HEADERS {
        return Err("runtime PE header exceeds fixed header bound".to_string());
    }
    let mut prefix = vec![0u8; prefix_len.max(initial.len())];
    read_covered(memory, maps, base, &mut prefix, false).map_err(|_| "runtime COFF header could not be read".to_string())?;
    let optional_size = usize::from(read_u16(&prefix, pe_offset + 4 + 16)?);
    let section_count = usize::from(read_u16(&prefix, pe_offset + 4 + 2)?);
    let header_len = pe_offset
        .checked_add(4 + 20)
        .and_then(|value| value.checked_add(optional_size))
        .and_then(|value| value.checked_add(section_count.checked_mul(40)?))
        .ok_or_else(|| "runtime PE header length overflow".to_string())?;
    if header_len > MAX_PE_HEADERS {
        return Err("runtime PE header exceeds fixed header bound".to_string());
    }
    let mut header = vec![0u8; header_len.max(initial.len())];
    read_covered(memory, maps, base, &mut header, false).map_err(|_| "runtime section layout could not be read".to_string())?;
    PeHeader::parse(&header, None)
}

fn parse_relocations(source: &mut HeldSource, header: &PeHeader) -> RelocationInfo {
    let Some((directory_rva, directory_size)) = header.reloc_directory else {
        return RelocationInfo { dir64_rvas: Vec::new(), available: true, limitation: Some("source has no usable relocation directory") };
    };
    let size = usize::try_from(directory_size).unwrap_or(usize::MAX);
    if size == 0 || size > MAX_RELOCATION_BYTES {
        return RelocationInfo::unavailable("relocation directory exceeds the bounded normalization read");
    }
    let Some(offset) = header.raw_offset(u64::from(directory_rva), size) else {
        return RelocationInfo::unavailable("relocation directory is not wholly inside a source section");
    };
    let mut bytes = vec![0u8; size];
    if source.read_exact_at(offset, &mut bytes).is_err() {
        return RelocationInfo::unavailable("relocation directory could not be read");
    }
    let mut cursor = 0usize;
    let mut dir64_rvas = Vec::new();
    let mut limitation = None;
    while cursor < bytes.len() {
        let Some(block_end_header) = cursor.checked_add(8) else {
            return RelocationInfo::unavailable("relocation block header overflow");
        };
        if block_end_header > bytes.len() {
            return RelocationInfo::unavailable("relocation block header is truncated");
        }
        let page = u64::from(u32::from_le_bytes(bytes[cursor..cursor + 4].try_into().unwrap_or([0; 4])));
        let block_size = usize::try_from(u32::from_le_bytes(bytes[cursor + 4..cursor + 8].try_into().unwrap_or([0; 4]))).unwrap_or(usize::MAX);
        if block_size < 8 || block_size % 2 != 0 {
            return RelocationInfo::unavailable("relocation block size is malformed");
        }
        let Some(block_end) = cursor.checked_add(block_size) else {
            return RelocationInfo::unavailable("relocation block range overflow");
        };
        if block_end > bytes.len() {
            return RelocationInfo::unavailable("relocation block exceeds its directory");
        }
        let mut entry = cursor + 8;
        while entry < block_end {
            let raw = u16::from_le_bytes(bytes[entry..entry + 2].try_into().unwrap_or([0; 2]));
            let kind = raw >> 12;
            let offset_in_page = u64::from(raw & 0x0fff);
            match kind {
                0 => (),
                10 => {
                    let Some(target) = page.checked_add(offset_in_page) else {
                        return RelocationInfo::unavailable("DIR64 relocation target overflow");
                    };
                    if target.checked_add(8).filter(|end| *end <= u64::from(header.size_image)).is_none()
                        || header.section_for_raw_rva(target, 8).is_none()
                    {
                        return RelocationInfo::unavailable("DIR64 relocation target is outside source image data");
                    }
                    dir64_rvas.push(target);
                }
                _ => limitation = Some("source relocation types other than DIR64 are unsupported"),
            }
            entry += 2;
        }
        cursor = block_end;
    }
    RelocationInfo { dir64_rvas, available: limitation.is_none(), limitation }
}

fn relocation_value_matches(source: u64, runtime: u64, preferred_base: u64, actual_base: u64) -> bool {
    if actual_base >= preferred_base {
        source.checked_add(actual_base - preferred_base) == Some(runtime)
    } else {
        source.checked_sub(preferred_base - actual_base) == Some(runtime)
    }
}

fn compare_window_bytes(
    source: &[u8],
    runtime: &[u8],
    rva: u64,
    relocations: &[u64],
    normalize: bool,
    preferred_base: u64,
    actual_base: u64,
) -> (usize, usize, Option<usize>, usize, usize) {
    let equal_bytes = source.iter().zip(runtime).filter(|(left, right)| left == right).count();
    let changed_bytes = source.len().saturating_sub(equal_bytes);
    if !normalize {
        return (equal_bytes, changed_bytes, None, 0, 0);
    }
    let mut source_normalized = source.to_vec();
    let mut runtime_normalized = runtime.to_vec();
    let mut masked = 0usize;
    let mut relocation_mismatches = 0usize;
    for target in relocations {
        let Some(relative) = target.checked_sub(rva).and_then(|offset| usize::try_from(offset).ok()) else { continue };
        let Some(end) = relative.checked_add(8) else { continue };
        if end > source.len() || end > runtime.len() { continue; }
        let source_value = u64::from_le_bytes(source[relative..end].try_into().unwrap_or([0; 8]));
        let runtime_value = u64::from_le_bytes(runtime[relative..end].try_into().unwrap_or([0; 8]));
        if !relocation_value_matches(source_value, runtime_value, preferred_base, actual_base) {
            relocation_mismatches = relocation_mismatches.saturating_add(1);
        }
        source_normalized[relative..end].fill(0);
        runtime_normalized[relative..end].fill(0);
        masked = masked.saturating_add(8);
    }
    let normalized_equal = source_normalized.iter().zip(&runtime_normalized).filter(|(left, right)| left == right).count();
    (equal_bytes, changed_bytes, Some(normalized_equal), masked, relocation_mismatches)
}

fn aslr_detected(actual_load_base: u64, source_preferred_base: u64) -> bool {
    actual_load_base != source_preferred_base
}

fn maps_for_process(memory: &ReadOnlyMemory) -> Result<Vec<RuntimeMap>, String> {
    let bytes = super::ProcFs::read_file(memory.target.pid, "maps", MAX_MAPS_BYTES, &memory.budget)
        .map_err(|_| "runtime maps could not be read completely".to_string())?;
    parse_maps(&bytes)
}

fn cached_window_result(
    source: &HeldSource,
    candidate: &RememberedCandidate,
    examined: usize,
    kind: &'static str,
    detail: &'static str,
) -> ImageComparison {
    let mut result = ImageComparison::incomplete(kind, detail);
    result.header_candidates_examined = examined;
    result.source_fd_stable = source.stable();
    result.header_structure_matches = false;
    result.comparison_basis = "earlier-structural-header";
    result.cached_header_used = true;
    result.current_header_candidate_count = 0;
    result.aslr_detected = aslr_detected(candidate.base, candidate.source_header.image_base);
    result.preferred_image_base_matches = candidate.base == candidate.source_header.image_base;
    result
}

fn scan_cached_windows(
    memory: &ReadOnlyMemory,
    source: &mut HeldSource,
    candidate: &RememberedCandidate,
    maps: &[RuntimeMap],
    examined: usize,
    search_error: Option<(&'static str, &'static str)>,
) -> ImageComparison {
    let mut windows = Vec::with_capacity(WINDOWS.len());
    let relocation_info = parse_relocations(source, &candidate.source_header);
    let mut total_equal = 0usize;
    let mut total_changed = 0usize;
    let mut total_normalized = Some(0usize);
    let mut total_masked = 0usize;
    let mut total_relocation_mismatches = 0usize;
    let mut relocation_limitation = relocation_info.limitation;

    for window in WINDOWS {
        let Some(section) = candidate.source_header.section_for_image_range(window.rva, window.length) else {
            return cached_window_result(source, candidate, examined, "source_mapping", "fixed source window does not fit the remembered PE layout");
        };
        let (source_bytes, source_zero_fill_bytes) = match read_source_image_window(source, &candidate.source_header, window.rva, window.length) {
            Ok(value) => value,
            Err(_) => return cached_window_result(source, candidate, examined, "source", "fixed source image window could not be reconstructed from raw bytes and zero fill"),
        };
        let Some(runtime_address) = candidate.base.checked_add(window.rva) else {
            return cached_window_result(source, candidate, examined, "runtime_mapping", "remembered fixed runtime window address overflow");
        };
        let runtime_bytes = match read_runtime_window(memory, maps, runtime_address, window.length) {
            Ok(bytes) => bytes,
            Err(_) => {
                return cached_window_result(source, candidate, examined, "read_coverage", "remembered fixed runtime windows could not be read completely");
            }
        };
        let diagnostic_counts = match window_diagnostic_counts(&source_bytes, &runtime_bytes, maps, runtime_address) {
            Ok(counts) => counts,
            Err(_) => {
                return cached_window_result(source, candidate, examined, "read_coverage", "remembered fixed runtime window permissions could not be validated");
            }
        };
        let Some(slot_metrics) = compare_slot(&source_bytes, &runtime_bytes, &memory.lease.contract) else {
            return cached_window_result(source, candidate, examined, "contract", "fixed upstream key slot could not be compared under the validated contract");
        };
        let relevant_relocations = relocation_info
            .dir64_rvas
            .iter()
            .copied()
            .filter(|target| {
                let Some(window_end) = window.rva.checked_add(window.length as u64) else { return false };
                *target >= window.rva && target.checked_add(8).is_some_and(|end| end <= window_end)
            })
            .collect::<Vec<_>>();
        let Some(window_end) = window.rva.checked_add(window.length as u64) else {
            return cached_window_result(source, candidate, examined, "runtime_mapping", "fixed comparison window range overflow");
        };
        if relocation_info.dir64_rvas.iter().any(|target| {
            *target >= window.rva
                && *target < window_end
                && target.checked_add(8).is_none_or(|end| end > window_end)
        }) {
            relocation_limitation = Some("DIR64 relocation crosses a fixed comparison-window boundary");
        }
        let normalize_window = relocation_info.available
            && !relocation_info.dir64_rvas.iter().any(|target| {
                *target >= window.rva
                    && *target < window_end
                    && target.checked_add(8).is_none_or(|end| end > window_end)
            });
        let (equal, changed, normalized, masked, relocation_mismatches) = compare_window_bytes(
            &source_bytes,
            &runtime_bytes,
            window.rva,
            &relevant_relocations,
            normalize_window,
            candidate.source_header.image_base,
            candidate.base,
        );
        if let Some(value) = normalized {
            total_normalized = total_normalized.map(|total| total.saturating_add(value));
        } else {
            total_normalized = None;
        }
        total_equal = total_equal.saturating_add(equal);
        total_changed = total_changed.saturating_add(changed);
        total_masked = total_masked.saturating_add(masked);
        total_relocation_mismatches = total_relocation_mismatches.saturating_add(relocation_mismatches);
        windows.push(WindowMetrics {
            name: window.name,
            rva: window.rva,
            length: window.length,
            section: section.name_text(),
            source_zero_fill_bytes,
            source_zero_bytes: diagnostic_counts.source_zero_bytes,
            runtime_zero_bytes: diagnostic_counts.runtime_zero_bytes,
            runtime_nonzero_bytes: diagnostic_counts.runtime_nonzero_bytes,
            runtime_distinct_byte_values: diagnostic_counts.runtime_distinct_byte_values,
            runtime_most_common_byte_count: diagnostic_counts.runtime_most_common_byte_count,
            runtime_executable_bytes: diagnostic_counts.runtime_executable_bytes,
            runtime_writable_bytes: diagnostic_counts.runtime_writable_bytes,
            equal_bytes: equal,
            changed_bytes: changed,
            normalized_equal_bytes: normalized,
            relocation_bytes_masked: masked,
            relocation_mismatches,
            known_key_matches: slot_metrics.known_key_matches,
            replacement_key_match: slot_metrics.replacement_key_match,
            source_prefix_still_matches: slot_metrics.source_prefix_still_matches,
            file_slot_equal: slot_metrics.file_slot_equal,
            runtime_prefix_equal: slot_metrics.runtime_prefix_equal,
        });
    }
    if !source.stable() {
        return cached_window_result(source, candidate, examined, "source", "source metadata changed during cached fixed-window comparison");
    }
    let (kind, detail) = search_error.unwrap_or((
        "cached_header_hypothesis",
        "current PE header candidate count was zero; fixed windows were read under an earlier structural header hypothesis",
    ));
    let mut result = cached_window_result(source, candidate, examined, kind, detail);
    result.windows = windows;
    result.total_equal_bytes = total_equal;
    result.total_changed_bytes = total_changed;
    result.normalized_equal_bytes = total_normalized;
    result.relocation_bytes_masked = total_masked;
    result.relocation_mismatches = total_relocation_mismatches;
    result.relocation_limitation = relocation_limitation;
    result
}

pub(super) fn scan(memory: &ReadOnlyMemory) -> ImageComparison {
    let mut source = match HeldSource::open(&memory.lease.executable, SOURCE_SIZE) {
        Ok(source) => source,
        Err(_) => return ImageComparison::incomplete("source", "source file could not be authenticated by held metadata and fixed build size"),
    };
    let source_header = match read_source_header(&mut source) {
        Ok(header) => header,
        Err(_) => return ImageComparison::incomplete("source", "source PE header/layout could not be authenticated"),
    };
    if !source.stable() {
        return ImageComparison::incomplete("source", "source metadata changed before comparison");
    }
    let maps = match maps_for_process(memory) {
        Ok(maps) => maps,
        Err(_) => return ImageComparison::incomplete("read_coverage", "runtime maps could not be read completely").after_source(false, &source),
    };
    let mut candidates = maps.iter().filter(|map| map_matches(map, false)).map(|map| map.start).collect::<Vec<_>>();
    // The preferred base is merely a prioritized hypothesis. It must pass the
    // same full structural header checks, and no result relies on the number.
    if maps.iter().any(|map| map.start <= source_header.image_base && source_header.image_base < map.end && map_matches(map, false)) {
        candidates.retain(|base| *base != source_header.image_base);
        candidates.insert(0, source_header.image_base);
    }
    let mut candidate_count = 0usize;
    let mut examined = 0usize;
    let mut candidate: Option<(u64, PeHeader)> = None;
    let mut candidate_read_failed = false;
    for base in candidates.iter().take(MAX_HEADER_CANDIDATES) {
        examined += 1;
        let mut initial = vec![0u8; 0x40];
        if read_covered(memory, &maps, *base, &mut initial, false).is_err() {
            candidate_read_failed = true;
            continue;
        }
        if initial.get(..2) != Some(b"MZ") {
            continue;
        }
        let runtime_header = match read_runtime_header(memory, &maps, *base) {
            Ok(header) => header,
            Err(_) => continue,
        };
        if !source_header.header_matches(&runtime_header) {
            continue;
        }
        candidate_count += 1;
        if candidate.is_none() {
            candidate = Some((*base, runtime_header));
        }
    }
    // Preserve useful fixed-window measurements when an unrelated header page
    // races or is unreadable. Such a search cannot establish uniqueness globally:
    // its result stays INCOMPLETE, never a complete diagnostic or patch authority.
    let search_error = candidate_search_error(candidates.len(), candidate_read_failed);
    if cached_fallback_allowed(candidate_count) {
        // Do not even select the remembered hypothesis after the pinned target
        // identity has changed. The subsequent reads repeat this check too.
        if memory.validate_pinned().is_err() {
            if let Ok(mut remembered) = memory.lease.remembered_candidate.lock() {
                *remembered = None;
            }
            return ImageComparison::incomplete("identity", "target identity changed before cached fixed-window hypothesis");
        }
        if let Some(remembered) = cached_candidate(&memory.lease.remembered_candidate, &source, &source_header, &memory.target) {
            if fixed_windows_covered(&maps, remembered.base) {
                return scan_cached_windows(memory, &mut source, &remembered, &maps, examined, search_error);
            }
        }
        let mut result = ImageComparison::incomplete("read_coverage", "no unique runtime PE image header matched the held source");
        result.header_candidates_examined = examined;
        result.source_fd_stable = source.stable();
        return result;
    }
    if candidate_count != 1 {
        let mut result = ImageComparison::incomplete("ambiguous", "more than one exact runtime PE image header matched the held source");
        result.status = "AMBIGUOUS";
        result.candidate_count = candidate_count;
        result.header_candidates_examined = examined;
        result.source_fd_stable = source.stable();
        result.header_structure_matches = true;
        result.comparison_basis = "current-pe-header";
        result.current_header_candidate_count = candidate_count;
        return result;
    }
    let (base, runtime_header) = candidate.expect("candidate count one establishes a candidate");
    if !source.stable() {
        return ImageComparison::incomplete("source", "source metadata changed after current PE header match");
    }
    cache_candidate(
        &memory.lease.remembered_candidate,
        RememberedCandidate {
            base,
            source_header: source_header.clone(),
            source_identity: source.identity.clone(),
            target_pid: memory.target.pid,
            target_start_ticks: memory.target.start_ticks,
            target_ppid: memory.target.ppid,
            target_uid4: memory.target.uid4,
            target_cmdline: memory.target.cmdline.clone(),
        },
    );
    let relocation_info = parse_relocations(&mut source, &source_header);
    let mut windows = Vec::with_capacity(WINDOWS.len());
    let mut total_equal = 0usize;
    let mut total_changed = 0usize;
    let mut total_normalized = Some(0usize);
    let mut total_masked = 0usize;
    let mut total_relocation_mismatches = 0usize;
    let mut relocation_limitation = relocation_info.limitation;
    for window in WINDOWS {
        let Some(section) = source_header.section_for_image_range(window.rva, window.length) else {
            return ImageComparison::incomplete("source_mapping", "fixed source window does not fit a named PE section").after_source(true, &source);
        };
        if runtime_header.section_for_image_range(window.rva, window.length).is_none() {
            return ImageComparison::incomplete("runtime_mapping", "fixed runtime window does not fit the authenticated PE image").after_source(true, &source);
        }
        let (source_bytes, source_zero_fill_bytes) = match read_source_image_window(&mut source, &source_header, window.rva, window.length) {
            Ok(value) => value,
            Err(_) => return ImageComparison::incomplete("source", "fixed source image window could not be reconstructed from raw bytes and zero fill").after_source(true, &source),
        };
        let Some(runtime_address) = base.checked_add(window.rva) else {
            return ImageComparison::incomplete("runtime_mapping", "runtime image address overflow").after_source(true, &source);
        };
        let runtime_bytes = match read_runtime_window(memory, &maps, runtime_address, window.length) {
            Ok(bytes) => bytes,
            Err(_) => {
                return ImageComparison::incomplete("read_coverage", "fixed runtime window has an unreadable, shared, device, or gapped map").after_source(true, &source);
            }
        };
        let diagnostic_counts = match window_diagnostic_counts(&source_bytes, &runtime_bytes, &maps, runtime_address) {
            Ok(counts) => counts,
            Err(_) => {
                return ImageComparison::incomplete("read_coverage", "fixed runtime window permissions could not be validated").after_source(true, &source);
            }
        };
        let Some(slot_metrics) = compare_slot(&source_bytes, &runtime_bytes, &memory.lease.contract) else {
            return ImageComparison::incomplete("contract", "fixed upstream key slot could not be compared under the validated contract").after_source(true, &source);
        };
        let relevant_relocations = relocation_info
            .dir64_rvas
            .iter()
            .copied()
            .filter(|target| {
                let Some(window_end) = window.rva.checked_add(window.length as u64) else { return false };
                *target >= window.rva && target.checked_add(8).is_some_and(|end| end <= window_end)
            })
            .collect::<Vec<_>>();
        let Some(window_end) = window.rva.checked_add(window.length as u64) else {
            return ImageComparison::incomplete("runtime_mapping", "fixed comparison window range overflow").after_source(true, &source);
        };
        if relocation_info.dir64_rvas.iter().any(|target| {
            *target >= window.rva
                && *target < window_end
                && target.checked_add(8).is_none_or(|end| end > window_end)
        }) {
            relocation_limitation = Some("DIR64 relocation crosses a fixed comparison-window boundary");
        }
        let normalize_window = relocation_info.available
            && !relocation_info.dir64_rvas.iter().any(|target| {
                *target >= window.rva
                    && *target < window_end
                    && target.checked_add(8).is_none_or(|end| end > window_end)
            });
        let (equal, changed, normalized, masked, relocation_mismatches) = compare_window_bytes(
            &source_bytes,
            &runtime_bytes,
            window.rva,
            &relevant_relocations,
            normalize_window,
            source_header.image_base,
            base,
        );
        if let Some(value) = normalized {
            total_normalized = total_normalized.map(|total| total.saturating_add(value));
        } else {
            total_normalized = None;
        }
        total_equal = total_equal.saturating_add(equal);
        total_changed = total_changed.saturating_add(changed);
        total_masked = total_masked.saturating_add(masked);
        total_relocation_mismatches = total_relocation_mismatches.saturating_add(relocation_mismatches);
        windows.push(WindowMetrics {
            name: window.name,
            rva: window.rva,
            length: window.length,
            section: section.name_text(),
            source_zero_fill_bytes,
            source_zero_bytes: diagnostic_counts.source_zero_bytes,
            runtime_zero_bytes: diagnostic_counts.runtime_zero_bytes,
            runtime_nonzero_bytes: diagnostic_counts.runtime_nonzero_bytes,
            runtime_distinct_byte_values: diagnostic_counts.runtime_distinct_byte_values,
            runtime_most_common_byte_count: diagnostic_counts.runtime_most_common_byte_count,
            runtime_executable_bytes: diagnostic_counts.runtime_executable_bytes,
            runtime_writable_bytes: diagnostic_counts.runtime_writable_bytes,
            equal_bytes: equal,
            changed_bytes: changed,
            normalized_equal_bytes: normalized,
            relocation_bytes_masked: masked,
            relocation_mismatches,
            known_key_matches: slot_metrics.known_key_matches,
            replacement_key_match: slot_metrics.replacement_key_match,
            source_prefix_still_matches: slot_metrics.source_prefix_still_matches,
            file_slot_equal: slot_metrics.file_slot_equal,
            runtime_prefix_equal: slot_metrics.runtime_prefix_equal,
        });
    }
    if !source.stable() {
        return ImageComparison::incomplete("source", "source metadata changed during comparison");
    }
    ImageComparison {
        complete: search_error.is_none(),
        status: if search_error.is_none() { "READ_DIAGNOSTIC" } else { "INCOMPLETE" },
        error_kind: search_error.map(|value| value.0),
        error: search_error.map(|value| value.1.to_string()),
        candidate_count,
        header_candidates_examined: examined,
        source_fd_stable: true,
        header_structure_matches: true,
        aslr_detected: aslr_detected(base, source_header.image_base),
        preferred_image_base_matches: runtime_header.image_base == source_header.image_base,
        windows,
        total_equal_bytes: total_equal,
        total_changed_bytes: total_changed,
        normalized_equal_bytes: total_normalized,
        relocation_bytes_masked: total_masked,
        relocation_mismatches: total_relocation_mismatches,
        relocation_limitation,
        comparison_basis: "current-pe-header",
        cached_header_used: false,
        current_header_candidate_count: candidate_count,
        identity_validated_after_read: false,
    }
}

fn candidate_search_error(count: usize, read_failed: bool) -> Option<(&'static str, &'static str)> {
    if count > MAX_HEADER_CANDIDATES {
        Some(("candidate_bound", "runtime header search was bounded; global uniqueness is not established"))
    } else if read_failed {
        Some(("read_coverage", "a runtime header was unreadable; global uniqueness is not established"))
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maps_require_complete_private_non_device_ranges() {
        let maps = parse_maps(b"1000-2000 r-xp 00000000 00:01 1 /data/image\n").unwrap();
        assert!(map_matches(&maps[0], true));
        assert!(!maps[0].writable);
        let shared = parse_maps(b"1000-2000 r-xs 00000000 00:01 1 /data/image\n").unwrap();
        assert!(!map_matches(&shared[0], false));
        assert!(!map_matches(&parse_maps(b"1000-2000 r-xp 00000000 00:01 1 /dev/image\n").unwrap()[0], false));
        assert!(!map_matches(&parse_maps(b"1000-2000 ---p 00000000 00:01 1 /data/image\n").unwrap()[0], false));
        assert!(parse_maps(b"1000-2000 r-xp 00000000 00:01 1 /data/image").is_err());
    }

    #[test]
    fn diagnostic_counts_cover_zero_randomish_and_sparse_distributions() {
        let maps = parse_maps(b"1000-1008 r-xp 00000000 00:01 1 /data/image\n").unwrap();
        let zeros = window_diagnostic_counts(&[0; 8], &[0; 8], &maps, 0x1000).unwrap();
        assert_eq!(zeros.source_zero_bytes, 8);
        assert_eq!(zeros.runtime_zero_bytes, 8);
        assert_eq!(zeros.runtime_nonzero_bytes, 0);
        assert_eq!(zeros.runtime_distinct_byte_values, 1);
        assert_eq!(zeros.runtime_most_common_byte_count, 8);

        let randomish = window_diagnostic_counts(&[0; 8], &[0, 1, 2, 3, 4, 5, 6, 7], &maps, 0x1000).unwrap();
        assert_eq!(randomish.source_zero_bytes, 8);
        assert_eq!(randomish.runtime_zero_bytes, 1);
        assert_eq!(randomish.runtime_nonzero_bytes, 7);
        assert_eq!(randomish.runtime_distinct_byte_values, 8);
        assert_eq!(randomish.runtime_most_common_byte_count, 1);

        let sparse = window_diagnostic_counts(&[1; 8], &[0, 0, 0, 7, 0, 0, 9, 0], &maps, 0x1000).unwrap();
        assert_eq!(sparse.source_zero_bytes, 0);
        assert_eq!(sparse.runtime_zero_bytes, 6);
        assert_eq!(sparse.runtime_nonzero_bytes, 2);
        assert_eq!(sparse.runtime_distinct_byte_values, 3);
        assert_eq!(sparse.runtime_most_common_byte_count, 6);
    }

    #[test]
    fn diagnostic_counts_classify_exact_private_rx_rw_coverage_and_reject_gaps() {
        let split = parse_maps(
            b"1000-1004 r-xp 00000000 00:01 1 /data/image\n1004-1008 rw-p 00000000 00:01 1 /data/image\n",
        )
        .unwrap();
        let counts = window_diagnostic_counts(&[0; 8], &[0; 8], &split, 0x1000).unwrap();
        assert_eq!(counts.runtime_executable_bytes, 4);
        assert_eq!(counts.runtime_writable_bytes, 4);

        let gap = parse_maps(
            b"1000-1004 r-xp 00000000 00:01 1 /data/image\n1005-1008 rw-p 00000000 00:01 1 /data/image\n",
        )
        .unwrap();
        assert!(window_diagnostic_counts(&[0; 8], &[0; 8], &gap, 0x1000).is_err());

        // BSS/virtual-tail zero fill is a source-layout count, not a claim
        // that every zero byte in the reconstructed source came from BSS.
        let mixed_source = window_diagnostic_counts(&[0, 1, 0, 2], &[1, 2, 3, 4], &split, 0x1000).unwrap();
        let source_zero_fill_bytes = 1;
        assert_eq!(mixed_source.source_zero_bytes, 2);
        assert_ne!(source_zero_fill_bytes, mixed_source.source_zero_bytes);
    }

    #[test]
    fn raw_and_dir64_normalized_metrics_are_distinct() {
        let source = [1u8, 2, 3, 4, 5, 6, 7, 8, 9];
        let mut runtime = source;
        runtime[2] ^= 0x80;
        runtime[4..8].copy_from_slice(&[0xaa, 0xbb, 0xcc, 0xdd]);
        let metrics = compare_window_bytes(&source, &runtime, 0x1000, &[0x1004], true, 0x1000, 0x1000);
        assert_eq!(metrics.0, 4);
        assert_eq!(metrics.1, 5);
        assert_eq!(metrics.2, Some(4));
        assert_eq!(metrics.3, 0);
        let metrics = compare_window_bytes(&source, &runtime, 0x1000, &[0x1000], true, 0x1000, 0x1000);
        assert_eq!(metrics.3, 8);
        assert_eq!(metrics.2, Some(9));
        assert_eq!(metrics.4, 1);

        let preferred = 0x1000u64;
        let actual = 0x3000u64;
        let source_pointer = preferred + 0x180;
        let runtime_pointer = actual + 0x180;
        let source = source_pointer.to_le_bytes();
        let runtime = runtime_pointer.to_le_bytes();
        let metrics = compare_window_bytes(&source, &runtime, 0x1000, &[0x1000], true, preferred, actual);
        assert_eq!(metrics.2, Some(8));
        assert_eq!(metrics.3, 8);
        assert_eq!(metrics.4, 0);
    }

    #[test]
    fn comparison_json_has_no_addresses_bytes_or_source_paths() {
        let value = ImageComparison {
            complete: true,
            status: "READ_DIAGNOSTIC",
            error_kind: None,
            error: None,
            candidate_count: 1,
            header_candidates_examined: 1,
            source_fd_stable: true,
            header_structure_matches: true,
            aslr_detected: true,
            preferred_image_base_matches: true,
            windows: vec![WindowMetrics { name: "test", rva: 0x1000, length: 8, section: ".text".to_string(), source_zero_fill_bytes: 0, source_zero_bytes: 0, runtime_zero_bytes: 0, runtime_nonzero_bytes: 8, runtime_distinct_byte_values: 1, runtime_most_common_byte_count: 8, runtime_executable_bytes: 8, runtime_writable_bytes: 0, equal_bytes: 8, changed_bytes: 0, normalized_equal_bytes: Some(8), relocation_bytes_masked: 0, relocation_mismatches: 0, known_key_matches: [false; 12], replacement_key_match: false, source_prefix_still_matches: true, file_slot_equal: 8, runtime_prefix_equal: Some(true) }],
            total_equal_bytes: 8,
            total_changed_bytes: 0,
            normalized_equal_bytes: Some(8),
            relocation_bytes_masked: 0,
            relocation_mismatches: 0,
            relocation_limitation: None,
            comparison_basis: "current-pe-header",
            cached_header_used: false,
            current_header_candidate_count: 1,
            identity_validated_after_read: false,
        };
        let output = value.json().to_string();
        assert!(!output.contains("0x"));
        assert!(!output.contains("/data"));
        assert!(!output.contains("rawBytes"));
        assert!(!output.contains("bootstrapPointerTargets"));
        assert!(!output.contains("targetRvas"));
        assert!(output.contains("\"rva\":4096"));
        assert!(output.contains("sourceBoundBy"));
        assert!(output.contains("referenceSha256Checked\":false"));
        assert!(output.contains("headerStructureMatches\":true"));
        assert!(output.contains("comparisonBasis\":\"current-pe-header\""));
        assert!(output.contains("cachedHeaderUsed\":false"));
        assert!(output.contains("currentHeaderCandidateCount\":1"));
        assert!(output.contains("runtimeMostCommonByteCount\":8"));
        assert!(!output.contains("runtimeMostCommonByteValue"));
        assert!(!output.contains("histogram"));
        assert!(!output.contains("virtualBase"));
    }

    #[test]
    fn aslr_uses_mapped_load_base_not_runtime_preferred_header_base() {
        let source_preferred = 0x1000;
        let mapped_load = 0x3000;
        let runtime_preferred_header = source_preferred;
        assert!(aslr_detected(mapped_load, source_preferred));
        assert_eq!(runtime_preferred_header, source_preferred);
    }

    #[test]
    fn upstream_slot_is_one_fixed_public_rva_and_allows_private_r() {
        let expected = [(0x4a5ff00, 32)];
        assert_eq!(WINDOWS.len(), expected.len());
        assert_eq!(WINDOWS.iter().map(|window| window.length).sum::<usize>(), 32);
        for (index, (window, &(rva, length))) in WINDOWS.iter().zip(&expected).enumerate() {
            assert_eq!((window.rva, window.length), (rva, length));
            assert_eq!(window.name, "upstream-ed25519-prefix-slot");
            for other in WINDOWS.iter().skip(index + 1) {
                assert!(window.rva + window.length as u64 <= other.rva
                    || other.rva + other.length as u64 <= window.rva);
            }
        }
        let maps = parse_maps(b"4a5ff00-4a5ff20 r--p 00000000 00:01 1 /data/image\n").unwrap();
        assert!(map_matches(&maps[0], false));
        assert!(fixed_windows_covered(&maps, 0));
    }

    #[test]
    fn late_window_hypothesis_is_exactly_one_fixed_read_and_is_not_complete() {
        assert_eq!(WINDOWS.iter().map(|window| window.length).sum::<usize>(), 32);
        let base = 0x1000_0000;
        let maps = vec![RuntimeMap {
            start: base,
            end: base + WINDOWS[0].rva + WINDOWS[0].length as u64,
            readable: true,
            writable: false,
            executable: false,
            private: true,
            device: false,
        }];
        assert!(fixed_windows_covered(&maps, base));
        assert_eq!(WINDOWS.len(), 1);

        let mut result = ImageComparison::incomplete("cached_header_hypothesis", "bounded hypothesis");
        result.comparison_basis = "earlier-structural-header";
        result.cached_header_used = true;
        result.current_header_candidate_count = 0;
        result.header_structure_matches = false;
        let output = result.json();
        assert_eq!(output["status"], "INCOMPLETE");
        assert_eq!(output["complete"], false);
        assert_eq!(output["cryptographicProof"], false);
        assert_eq!(output["writeAuthorized"], false);
        assert_eq!(output["comparisonBasis"], "earlier-structural-header");
        assert_eq!(output["cachedHeaderUsed"], true);
        assert_eq!(output["headerStructureMatches"], false);
        assert_eq!(output["currentHeaderCandidateCount"], 0);
        assert_eq!(output["windows"], serde_json::json!([]));
        let serialized = result.json().to_string();
        assert!(!serialized.contains("virtualBase"));
        assert!(!serialized.contains("rawBytes"));
        assert!(!serialized.contains("/data"));
    }

    #[test]
    fn ambiguous_current_header_never_allows_cached_fallback() {
        assert!(cached_fallback_allowed(0));
        assert!(!cached_fallback_allowed(1));
        assert!(!cached_fallback_allowed(2));
    }

    fn test_remembered_candidate() -> RememberedCandidate {
        RememberedCandidate {
            base: 0x1000,
            source_header: PeHeader::parse(&synthetic_header(), Some(2048)).unwrap(),
            source_identity: FileIdentity {
                size: 2048,
                mode: libc::S_IFREG as u32,
                dev: 1,
                ino: 2,
                mtime: 3,
                mtime_nsec: 4,
                ctime: 5,
                ctime_nsec: 6,
                nlink: 1,
                uid: 7,
                gid: 8,
            },
            target_pid: 9,
            target_start_ticks: 10,
            target_ppid: 11,
            target_uid4: [12; 4],
            target_cmdline: b"A:\\WowB-ARM64.exe\0".to_vec(),
        }
    }

    fn test_cached_comparison() -> ImageComparison {
        let mut comparison = ImageComparison::incomplete("cached_header_hypothesis", "bounded hypothesis");
        comparison.comparison_basis = "earlier-structural-header";
        comparison.cached_header_used = true;
        comparison.windows.push(WindowMetrics {
            name: "test",
            rva: 0x1000,
            length: 4,
            section: ".text".to_string(),
            source_zero_fill_bytes: 0,
            source_zero_bytes: 0,
            runtime_zero_bytes: 0,
            runtime_nonzero_bytes: 4,
            runtime_distinct_byte_values: 1,
            runtime_most_common_byte_count: 4,
            runtime_executable_bytes: 4,
            runtime_writable_bytes: 0,
            equal_bytes: 4,
            changed_bytes: 0,
            normalized_equal_bytes: Some(4),
            relocation_bytes_masked: 0,
            relocation_mismatches: 0,
            known_key_matches: [false; 12],
            replacement_key_match: false,
            source_prefix_still_matches: true,
            file_slot_equal: 4,
            runtime_prefix_equal: Some(true),
        });
        comparison.total_equal_bytes = 4;
        comparison
    }

    #[test]
    fn post_read_identity_failures_clear_cache_and_all_observations() {
        let expected = super::super::Target {
            pid: 9,
            start_ticks: 10,
            ppid: 11,
            uid4: [12; 4],
            cmdline: b"A:\\WowB-ARM64.exe\0".to_vec(),
        };
        assert!(!super::super::target_identity_matches(&expected, &super::super::Target { ppid: 99, ..expected.clone() }));
        assert!(!super::super::target_identity_matches(&expected, &super::super::Target { uid4: [12, 12, 13, 12], ..expected.clone() }));
        assert!(!super::super::target_identity_matches(&expected, &super::super::Target { cmdline: b"A:\\changed.exe\0".to_vec(), ..expected.clone() }));
        for failure in [
            // Nonce validation failures and the three target identity changes
            // all enter the same fixed post-read identity-failure path.
            super::super::PostReadIdentity::IdentityFailure,
            super::super::PostReadIdentity::IdentityFailure,
            super::super::PostReadIdentity::IdentityFailure,
        ] {
            let slot = std::sync::Mutex::new(Some(test_remembered_candidate()));
            let mut comparison = test_cached_comparison();
            let decision = super::super::post_read_observation_decision(&slot, &mut comparison, failure, true, true);
            assert_eq!(decision, super::super::PostReadDecision::IdentityFailure);
            assert!(slot.lock().unwrap().is_none());
            assert!(comparison.windows.is_empty());
            assert_eq!(comparison.total_equal_bytes, 0);
            assert_eq!(comparison.json()["identityValidatedAfterRead"], false);
            assert_eq!(comparison.error_kind, Some("post_read_identity"));
            let serialized = comparison.json().to_string();
            assert!(!serialized.contains("knownKeyMatches"));
            assert!(!serialized.contains("rawBytes"));
            assert!(!serialized.contains("keyHash"));
            assert!(!serialized.contains("heap"));
            assert!(!serialized.contains("targetRvas"));
        }
    }

    #[test]
    fn cancelled_active_lease_clears_cached_observations() {
        let slot = std::sync::Mutex::new(Some(test_remembered_candidate()));
        let mut comparison = test_cached_comparison();
        let decision = super::super::post_read_observation_decision(
            &slot,
            &mut comparison,
            super::super::PostReadIdentity::Valid,
            false,
            true,
        );
        assert_eq!(decision, super::super::PostReadDecision::Cancelled);
        assert!(slot.lock().unwrap().is_none());
        assert!(comparison.windows.is_empty());
        assert_eq!(comparison.error_kind, Some("stale_lease"));
    }

    #[test]
    fn valid_cached_incomplete_observations_are_retained_and_marked_validated() {
        let slot = std::sync::Mutex::new(Some(test_remembered_candidate()));
        let mut comparison = test_cached_comparison();
        let decision = super::super::post_read_observation_decision(
            &slot,
            &mut comparison,
            super::super::PostReadIdentity::Valid,
            true,
            true,
        );
        assert_eq!(decision, super::super::PostReadDecision::Incomplete);
        assert!(slot.lock().unwrap().is_some());
        assert_eq!(comparison.windows.len(), 1);
        assert_eq!(comparison.json()["identityValidatedAfterRead"], true);
        assert_eq!(comparison.json()["status"], "INCOMPLETE");
    }

    fn synthetic_key_contract() -> forever_patcher::KeyStoreContract {
        forever_patcher::KeyStoreContract {
            entry_size: 40,
            entry_count: 12,
            target_group: 8,
            anchor_group: 1,
            expected_flags: vec![1; 12],
            accepted_paddings: vec![[0; 3]],
            scan_alignment: 4,
            known_keys: (1u8..=12).map(|value| [value; 32]).collect(),
            new_key: [0xee; 32],
        }
    }

    #[test]
    fn slot_comparison_uses_exact_32_byte_known_key_vectors() {
        let contract = synthetic_key_contract();
        for (index, key) in contract.known_keys.iter().enumerate() {
            let metrics = compare_slot(&[0xaa; 32], key, &contract).unwrap();
            assert_eq!(metrics.known_key_matches[index], true);
            assert_eq!(metrics.known_key_matches.iter().filter(|matched| **matched).count(), 1);
            assert!(!metrics.replacement_key_match);
            assert_eq!(metrics.file_slot_equal, 0);
            assert_eq!(metrics.runtime_prefix_equal, Some(false));
            assert!(!metrics.source_prefix_still_matches);
        }
    }

    #[test]
    fn unknown_key_with_known_eight_byte_prefix_never_matches_a_whole_key() {
        let contract = synthetic_key_contract();
        let mut unknown = contract.known_keys[0];
        unknown[8] ^= 1;
        let metrics = compare_slot(&contract.known_keys[0], &unknown, &contract).unwrap();
        assert_eq!(metrics.known_key_matches, [false; 12]);
        assert!(!metrics.replacement_key_match);
        assert_eq!(metrics.file_slot_equal, 31);
        assert_eq!(metrics.runtime_prefix_equal, Some(true));
        assert!(!metrics.source_prefix_still_matches);
    }

    #[test]
    fn source_anchor_and_runtime_prefix_are_independent_of_full_key_identity() {
        let contract = synthetic_key_contract();
        let source = contract.known_keys[8];
        let mut runtime = source;
        runtime[31] ^= 1;
        let metrics = compare_slot(&source, &runtime, &contract).unwrap();
        assert!(metrics.source_prefix_still_matches);
        assert_eq!(metrics.runtime_prefix_equal, Some(true));
        assert_eq!(metrics.file_slot_equal, 31);
        assert_eq!(metrics.known_key_matches, [false; 12]);
        runtime[0] ^= 1;
        let changed_prefix = compare_slot(&source, &runtime, &contract).unwrap();
        assert!(changed_prefix.source_prefix_still_matches);
        assert_eq!(changed_prefix.runtime_prefix_equal, Some(false));
    }

    #[test]
    fn replacement_key_matches_only_the_contract_replacement_vector() {
        let contract = synthetic_key_contract();
        let metrics = compare_slot(&[0; 32], &contract.new_key, &contract).unwrap();
        assert_eq!(metrics.known_key_matches, [false; 12]);
        assert!(metrics.replacement_key_match);
        assert!(!metrics.source_prefix_still_matches);
    }

    #[test]
    fn slot_comparison_rejects_invalid_lengths_and_contract_bounds() {
        let contract = synthetic_key_contract();
        assert!(compare_slot(&[0; 31], &[0; 32], &contract).is_none());
        assert!(compare_slot(&[0; 32], &[0; 31], &contract).is_none());
        let mut invalid_count = contract.clone();
        invalid_count.entry_count = 11;
        assert!(compare_slot(&[0; 32], &[0; 32], &invalid_count).is_none());
        let mut invalid_target = contract.clone();
        invalid_target.target_group = 13;
        assert!(compare_slot(&[0; 32], &[0; 32], &invalid_target).is_none());
        let mut invalid_keys = contract;
        invalid_keys.known_keys.pop();
        assert!(compare_slot(&[0; 32], &[0; 32], &invalid_keys).is_none());
    }

    #[test]
    fn failed_or_partial_reads_do_not_manufacture_slot_matches() {
        let contract = synthetic_key_contract();
        assert!(compare_slot(&[0; 32], &[0; 31], &contract).is_none());
        let maps = parse_maps(b"1000-1008 r--p 00000000 00:01 1 /data/image\n").unwrap();
        assert!(read_runtime_window(&PartialFailureMemory, &maps, 0x1000, 32).is_err());
    }

    fn synthetic_header() -> Vec<u8> {
        let mut bytes = vec![0u8; 1024];
        bytes[..2].copy_from_slice(b"MZ");
        bytes[0x3c..0x40].copy_from_slice(&0x80u32.to_le_bytes());
        bytes[0x80..0x84].copy_from_slice(b"PE\0\0");
        bytes[0x84..0x86].copy_from_slice(&0xaa64u16.to_le_bytes());
        bytes[0x86..0x88].copy_from_slice(&2u16.to_le_bytes());
        bytes[0x88..0x8c].copy_from_slice(&123u32.to_le_bytes());
        bytes[0x94..0x96].copy_from_slice(&0xf0u16.to_le_bytes());
        let optional = 0x98;
        bytes[optional..optional + 2].copy_from_slice(&0x20bu16.to_le_bytes());
        bytes[optional + 16..optional + 20].copy_from_slice(&0x1000u32.to_le_bytes());
        bytes[optional + 24..optional + 32].copy_from_slice(&0x140000000u64.to_le_bytes());
        bytes[optional + 56..optional + 60].copy_from_slice(&0x4000u32.to_le_bytes());
        bytes[optional + 60..optional + 64].copy_from_slice(&0x400u32.to_le_bytes());
        bytes[optional + 108..optional + 112].copy_from_slice(&16u32.to_le_bytes());
        for (index, (name, rva, raw, flags)) in [
            (b".text\0\0\0", 0x1000u32, 0x400u32, 0x60000020u32),
            (b".data\0\0\0", 0x2000u32, 0x600u32, 0xc0000040u32),
        ].into_iter().enumerate() {
            let section = optional + 0xf0 + index * 40;
            bytes[section..section + 8].copy_from_slice(name);
            bytes[section + 8..section + 12].copy_from_slice(&0x200u32.to_le_bytes());
            bytes[section + 12..section + 16].copy_from_slice(&rva.to_le_bytes());
            bytes[section + 16..section + 20].copy_from_slice(&0x200u32.to_le_bytes());
            bytes[section + 20..section + 24].copy_from_slice(&raw.to_le_bytes());
            bytes[section + 36..section + 40].copy_from_slice(&flags.to_le_bytes());
        }
        bytes
    }

    #[test]
    fn bounded_pe_headers_match_structure_not_preferred_base_or_bad_layout() {
        let bytes = synthetic_header();
        let source = PeHeader::parse(&bytes, Some(2048)).unwrap();
        assert_eq!(source.raw_offset(0x1010, 16), Some(0x410));
        assert_eq!(source.raw_offset(0x11f8, 16), None);
        let mut relocated_header = source.clone();
        relocated_header.image_base += 0x10000;
        assert!(source.header_matches(&relocated_header));
        relocated_header.entry_rva += 4;
        assert!(!source.header_matches(&relocated_header));
        for (offset, value) in [(0x84, 0x8664u16), (0x86, 97u16), (0x94, u16::MAX)] {
            let mut invalid = bytes.clone();
            invalid[offset..offset + 2].copy_from_slice(&value.to_le_bytes());
            assert!(PeHeader::parse(&invalid, Some(2048)).is_err());
        }
        let mut invalid = bytes;
        invalid[0x98 + 56..0x98 + 60].copy_from_slice(&0x1100u32.to_le_bytes());
        assert!(PeHeader::parse(&invalid, Some(2048)).is_err());
    }

    struct FakeMemory;
    impl MemoryAccess for FakeMemory {
        fn regions(&self) -> io::Result<Vec<forever_patcher::Region>> { Ok(Vec::new()) }
        fn read(&self, _: u64, bytes: &mut [u8]) -> io::Result<()> { bytes.fill(0x42); Ok(()) }
        fn write(&self, _: u64, _: &[u8]) -> io::Result<()> { Err(io::ErrorKind::PermissionDenied.into()) }
    }

    struct PartialFailureMemory;
    impl MemoryAccess for PartialFailureMemory {
        fn regions(&self) -> io::Result<Vec<forever_patcher::Region>> { Ok(Vec::new()) }
        fn read(&self, _: u64, bytes: &mut [u8]) -> io::Result<()> {
            let prefix_len = bytes.len().min(2);
            bytes[..prefix_len].fill(0x42);
            Err(io::ErrorKind::UnexpectedEof.into())
        }
        fn write(&self, _: u64, _: &[u8]) -> io::Result<()> { Err(io::ErrorKind::PermissionDenied.into()) }
    }

    #[test]
    fn covered_reads_cross_adjacent_private_maps_but_not_gaps_or_shared_maps() {
        let adjacent = parse_maps(b"1000-1080 r--p 00000000 00:00 0\n1080-1100 r--p 00000000 00:00 0\n").unwrap();
        let mut bytes = [0u8; 64];
        read_covered(&FakeMemory, &adjacent, 0x1060, &mut bytes, false).unwrap();
        assert!(bytes.iter().all(|byte| *byte == 0x42));
        let mut gap = adjacent.clone();
        gap[1].start += 1;
        assert!(read_covered(&FakeMemory, &gap, 0x1060, &mut bytes, false).is_err());
        let mut shared = adjacent;
        shared[1].private = false;
        assert!(read_covered(&FakeMemory, &shared, 0x1060, &mut bytes, false).is_err());
    }

    #[test]
    fn partial_runtime_reader_failure_never_constructs_a_useful_window() {
        let maps = parse_maps(b"1000-1008 r-xp 00000000 00:01 1 /data/image\n").unwrap();
        let result = read_runtime_window(&PartialFailureMemory, &maps, 0x1000, 8);
        assert!(result.is_err());
    }

    #[test]
    fn incomplete_header_search_never_claims_global_uniqueness() {
        assert!(candidate_search_error(1, false).is_none());
        assert_eq!(candidate_search_error(1, true).unwrap().0, "read_coverage");
        assert_eq!(candidate_search_error(1025, false).unwrap().0, "candidate_bound");
    }

    #[test]
    fn held_source_rejects_symlink_and_detects_inplace_mutation() {
        use std::sync::atomic::{AtomicU64, Ordering};
        use std::os::unix::fs::symlink;
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let directory = std::env::temp_dir().join(format!("forever-image-source-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
        std::fs::create_dir(&directory).unwrap();
        let path = directory.join("source");
        std::fs::write(&path, b"source").unwrap();
        let held = HeldSource::open(&path, 6).unwrap();
        assert!(held.stable());
        let link = directory.join("link");
        symlink(&path, &link).unwrap();
        assert!(HeldSource::open(&link, 6).is_err());
        std::fs::write(&path, b"changed").unwrap();
        assert!(!held.stable());
        let incomplete = ImageComparison::incomplete("runtime_mapping", "synthetic error").after_source(true, &held);
        assert!(!incomplete.source_fd_stable);
        assert_eq!(incomplete.json()["sourceBoundBy"], json!([]));
        std::fs::remove_file(link).unwrap();
        std::fs::remove_file(path).unwrap();
        std::fs::remove_dir(directory).unwrap();
    }

    #[test]
    fn source_virtual_tail_is_zero_fill_not_next_section_file_bytes() {
        let path = std::env::temp_dir().join(format!("forever-bss-window-{}", std::process::id()));
        let mut raw = vec![0xccu8; 2048];
        raw[0x400..0x410].fill(0x42);
        std::fs::write(&path, &raw).unwrap();
        let mut source = HeldSource::open(&path, 2048).unwrap();
        let mut header = PeHeader::parse(&synthetic_header(), Some(2048)).unwrap();
        header.sections[0].raw_size = 16;
        let (mixed, zeroes) = read_source_image_window(&mut source, &header, 0x1008, 16).unwrap();
        assert_eq!(&mixed[..8], &[0x42; 8]);
        assert_eq!(&mixed[8..], &[0; 8]);
        assert_eq!(zeroes, 8);
        let (bss, zeroes) = read_source_image_window(&mut source, &header, 0x1080, 32).unwrap();
        assert_eq!(bss, vec![0; 32]);
        assert_eq!(zeroes, 32);
        assert!(read_source_image_window(&mut source, &header, 0x11f8, 16).is_err());
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn remembered_candidate_requires_full_source_and_target_identity() {
        let path = std::env::temp_dir().join(format!("forever-remembered-source-{}", std::process::id()));
        std::fs::write(&path, vec![0u8; 2048]).unwrap();
        let source = HeldSource::open(&path, 2048).unwrap();
        let source_header = PeHeader::parse(&synthetic_header(), Some(2048)).unwrap();
        let target = super::super::Target {
            pid: 14710,
            start_ticks: 8823,
            ppid: 8909,
            uid4: [10330; 4],
            cmdline: b"A:\\WowB-ARM64.exe\0".to_vec(),
        };
        let candidate = RememberedCandidate {
            base: 0x1000,
            source_header: source_header.clone(),
            source_identity: source.identity.clone(),
            target_pid: target.pid,
            target_start_ticks: target.start_ticks,
            target_ppid: target.ppid,
            target_uid4: target.uid4,
            target_cmdline: target.cmdline.clone(),
        };
        let slot = std::sync::Mutex::new(None);
        cache_candidate(&slot, candidate.clone());
        assert!(cached_candidate(&slot, &source, &source_header, &target).is_some());
        assert!(cached_candidate(&slot, &source, &source_header, &super::super::Target { pid: 14711, ..target.clone() }).is_none());
        cache_candidate(&slot, candidate.clone());
        assert!(cached_candidate(&slot, &source, &source_header, &super::super::Target { start_ticks: 8824, ..target.clone() }).is_none());
        cache_candidate(&slot, candidate.clone());
        assert!(cached_candidate(&slot, &source, &source_header, &super::super::Target { uid4: [10330, 10330, 10331, 10330], ..target.clone() }).is_none());
        cache_candidate(&slot, candidate);
        assert!(cached_candidate(&slot, &source, &source_header, &super::super::Target { cmdline: b"A:\\changed.exe\0".to_vec(), ..target }).is_none());
        let other_path = std::env::temp_dir().join(format!("forever-remembered-source-other-{}", std::process::id()));
        std::fs::write(&other_path, vec![0u8; 2048]).unwrap();
        let other_source = HeldSource::open(&other_path, 2048).unwrap();
        let other_target = super::super::Target {
            pid: 14710,
            start_ticks: 8823,
            ppid: 8909,
            uid4: [10330; 4],
            cmdline: b"A:\\WowB-ARM64.exe\0".to_vec(),
        };
        cache_candidate(&slot, RememberedCandidate {
            source_identity: source.identity.clone(),
            source_header,
            target_pid: other_target.pid,
            target_start_ticks: other_target.start_ticks,
            target_ppid: other_target.ppid,
            target_uid4: other_target.uid4,
            target_cmdline: other_target.cmdline.clone(),
            base: 0x1000,
        });
        assert!(cached_candidate(&slot, &other_source, &PeHeader::parse(&synthetic_header(), Some(2048)).unwrap(), &other_target).is_none());
        let new_lease_slot: std::sync::Mutex<Option<RememberedCandidate>> = std::sync::Mutex::new(None);
        assert!(new_lease_slot.lock().unwrap().is_none());
        assert!(source.stable());
        std::fs::remove_file(path).unwrap();
        std::fs::remove_file(other_path).unwrap();
    }
}
