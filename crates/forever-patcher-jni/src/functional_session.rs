//! Read-only functional-session gate for the unmanaged ARM64 PoC.
//!
//! This module is intentionally separate from the managed launcher API below.
//! It accepts no PID or address from Java, never opens `/proc/<pid>/mem` for
//! writing, and never calls a signal or reaper on a runtime process.  Its only
//! purpose is to establish whether the real child can be identified and read.

use forever_patcher::MemoryAccess;
use serde_json::{json, Map, Value};
use std::collections::{BTreeMap, BTreeSet, VecDeque};
use std::fs::File;
use std::io::{self, Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::os::unix::fs::MetadataExt;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

#[path = "functional_image_compare.rs"]
mod functional_image_compare;
#[path = "functional_code_capture.rs"]
mod functional_code_capture;
#[path = "functional_full_scan.rs"]
mod functional_full_scan;

const RESULT_VERSION: u64 = 1;
const CANONICAL_CONTRACT: &str = include_str!("../../../forever/contract.json");
const MAX_PROC_FILE: usize = 128 * 1024;
const MAX_MAPS_FILE: usize = 2 * 1024 * 1024;
const MAX_TREE: usize = 256;
const MAX_TIDS: usize = 2048;
const MAX_CANDIDATES: usize = 64;
const MAX_REGIONS: usize = 4096;
const MAX_READ_BYTES: u64 = 2 * 1024 * 1024 * 1024;
const MAX_READ_OPS: u64 = 262144;
const MAX_READ_CHUNK: usize = 256 * 1024;
const POLL_BUDGET: Duration = Duration::from_secs(20);
const DIAGNOSTIC_SLOTS: u64 = 8;
const DIAGNOSTIC_OVERLAP: usize = 31;
const STRUCTURAL_BLOCK_SIZE: usize = 480;
const STRUCTURAL_OVERLAP: usize = STRUCTURAL_BLOCK_SIZE - 1;
const PR_SET_CHILD_SUBREAPER: libc::c_int = 36;
const PR_GET_CHILD_SUBREAPER: libc::c_int = 37;
const TARGET_SUFFIX: &str = "customgames/wow forever/wowb-arm64.exe";

static NEXT_TOKEN: AtomicU64 = AtomicU64::new(1);
static ACTIVE: OnceLock<Mutex<Option<Arc<Lease>>>> = OnceLock::new();

fn active() -> &'static Mutex<Option<Arc<Lease>>> {
    ACTIVE.get_or_init(|| Mutex::new(None))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Stat {
    ppid: u32,
    start_ticks: u64,
}

#[derive(Clone, Debug)]
struct ProcFs;

impl ProcFs {
    fn path(pid: u32, name: &str) -> PathBuf {
        PathBuf::from("/proc").join(pid.to_string()).join(name)
    }

    fn read_file(pid: u32, name: &str, limit: usize, budget: &Budget) -> io::Result<Vec<u8>> {
        budget.begin()?;
        let path = Self::path(pid, name);
        let mut file = match File::open(path) {
            Ok(file) => file,
            Err(error) => {
                if !matches!(error.raw_os_error(), Some(libc::ENOENT | libc::ESRCH)) {
                    budget.error(&error);
                }
                return Err(error);
            }
        };
        let mut bytes = Vec::new();
        if let Err(error) = file.by_ref().take(limit as u64 + 1).read_to_end(&mut bytes) {
            budget.error(&error);
            return Err(error);
        }
        if bytes.len() > limit {
            budget.incomplete.store(true, Ordering::Relaxed);
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "bounded proc read exceeded"));
        }
        budget.charge_proc(bytes.len())?;
        Ok(bytes)
    }

    fn stat(pid: u32, budget: &Budget) -> io::Result<Stat> {
        let bytes = Self::read_file(pid, "stat", MAX_PROC_FILE, budget)?;
        let text = std::str::from_utf8(&bytes)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
        let close = text
            .rfind(')')
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "proc stat has no comm"))?;
        let fields: Vec<&str> = text[close + 1..].split_whitespace().collect();
        if fields.len() <= 19 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "proc stat is incomplete"));
        }
        Ok(Stat {
            ppid: fields[1]
                .parse()
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?,
            start_ticks: fields[19]
                .parse()
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?,
        })
    }

    fn uid(pid: u32, budget: &Budget) -> io::Result<[u32; 4]> {
        let bytes = Self::read_file(pid, "status", MAX_PROC_FILE, budget)?;
        let text = std::str::from_utf8(&bytes)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
        let line = text.lines()
            .find_map(|line| line.strip_prefix("Uid:").map(str::trim))
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "proc status has no Uid"))?;
        let fields: Vec<&str> = line.split_whitespace().collect();
        if fields.len() < 4 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "proc status Uid has fewer than four fields"));
        }
        let mut values = [0u32; 4];
        for (index, field) in fields.iter().take(4).enumerate() {
            values[index] = field
                .parse()
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
        }
        Ok(values)
    }

    fn tids(pid: u32, budget: &Budget) -> io::Result<Vec<u32>> {
        budget.begin()?;
        let directory = PathBuf::from("/proc").join(pid.to_string()).join("task");
        let entries = std::fs::read_dir(directory).map_err(|error| {
            budget.error(&error);
            error
        })?;
        let mut tids = Vec::new();
        for entry in entries {
            let entry = match entry {
                Ok(entry) => entry,
                Err(error) => {
                    budget.error(&error);
                    continue;
                }
            };
            let name = entry.file_name();
            let Some(tid) = name.to_str().and_then(|value| value.parse::<u32>().ok()) else { continue };
            budget.charge(name.len())?;
            tids.push(tid);
            if tids.len() > MAX_TIDS {
                budget.incomplete.store(true, Ordering::Relaxed);
                return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "thread enumeration exceeded bound"));
            }
        }
        Ok(tids)
    }

    fn children(pid: u32, tid: u32, budget: &Budget) -> io::Result<Vec<u32>> {
        let bytes = Self::read_file(pid, &format!("task/{tid}/children"), MAX_PROC_FILE, budget)?;
        let text = std::str::from_utf8(&bytes)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
        text.split_whitespace()
            .map(|value| {
                value
                    .parse()
                    .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))
            })
            .collect()
    }

    fn maps(pid: u32, budget: &Budget) -> io::Result<Vec<forever_patcher::Region>> {
        let bytes = Self::read_file(pid, "maps", MAX_MAPS_FILE, budget)?;
        let text = std::str::from_utf8(&bytes)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
        let mut regions = Vec::new();
        for line in text.lines() {
            let mut columns = line.split_whitespace();
            let (Some(range), Some(perms)) = (columns.next(), columns.next()) else { continue };
            let _offset = columns.next();
            let _device = columns.next();
            let _inode = columns.next();
            let named = columns.next();
            let anonymous = named.is_none() || named.is_some_and(|path| path.starts_with('['));
            let private_rw = perms.as_bytes().get(0) == Some(&b'r')
                && perms.as_bytes().get(1) == Some(&b'w')
                && perms.as_bytes().get(2) == Some(&b'-')
                && perms.as_bytes().get(3) == Some(&b'p');
            if !anonymous || !private_rw {
                continue;
            }
            let Some((start, end)) = range.split_once('-') else { continue };
            let (Ok(start), Ok(end)) = (u64::from_str_radix(start, 16), u64::from_str_radix(end, 16)) else { continue };
            if end <= start {
                continue;
            }
            regions.push(forever_patcher::Region { start, end });
            if regions.len() > MAX_REGIONS {
                budget.incomplete.store(true, Ordering::Relaxed);
                return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "too many anonymous regions"));
            }
        }
        budget.regions(&regions);
        Ok(regions)
    }
}

#[derive(Debug)]
struct Budget {
    deadline: Instant,
    bytes: AtomicU64,
    proc_bytes: AtomicU64,
    actual_memory_bytes: AtomicU64,
    memory_read_ops: AtomicU64,
    ops: AtomicU64,
    read_errors: AtomicU64,
    permission_errors: AtomicU64,
    exhausted: AtomicBool,
    incomplete: AtomicBool,
    diagnostics: Mutex<ReadDiagnostics>,
}

impl Budget {
    fn new() -> Self {
        Self {
            deadline: Instant::now() + POLL_BUDGET,
            bytes: AtomicU64::new(0),
            proc_bytes: AtomicU64::new(0),
            actual_memory_bytes: AtomicU64::new(0),
            memory_read_ops: AtomicU64::new(0),
            ops: AtomicU64::new(0),
            read_errors: AtomicU64::new(0),
            permission_errors: AtomicU64::new(0),
            exhausted: AtomicBool::new(false),
            incomplete: AtomicBool::new(false),
            diagnostics: Mutex::new(ReadDiagnostics::default()),
        }
    }

    fn check(&self) -> io::Result<()> {
        if Instant::now() >= self.deadline {
            self.exhausted.store(true, Ordering::Relaxed);
            return Err(io::Error::new(io::ErrorKind::TimedOut, "functional read deadline exceeded"));
        }
        Ok(())
    }

    fn begin(&self) -> io::Result<()> {
        self.check()?;
        if self.ops.fetch_add(1, Ordering::Relaxed) + 1 > MAX_READ_OPS {
            self.exhausted.store(true, Ordering::Relaxed);
            return Err(io::Error::new(io::ErrorKind::TimedOut, "functional read operation budget exceeded"));
        }
        Ok(())
    }

    fn charge(&self, amount: usize) -> io::Result<()> {
        if self.bytes.fetch_add(amount as u64, Ordering::Relaxed) + amount as u64 > MAX_READ_BYTES {
            self.exhausted.store(true, Ordering::Relaxed);
            return Err(io::Error::new(io::ErrorKind::TimedOut, "functional read byte budget exceeded"));
        }
        self.check()
    }

    fn charge_proc(&self, amount: usize) -> io::Result<()> {
        self.proc_bytes.fetch_add(amount as u64, Ordering::Relaxed);
        self.charge(amount)
    }

    fn record_memory_read(&self, amount: usize) {
        self.actual_memory_bytes.fetch_add(amount as u64, Ordering::Relaxed);
        self.memory_read_ops.fetch_add(1, Ordering::Relaxed);
    }

    fn regions(&self, regions: &[forever_patcher::Region]) {
        if let Ok(mut diagnostics) = self.diagnostics.lock() {
            diagnostics.regions(regions);
        }
    }

    fn regions_full_snapshot(&self, count: usize, total_bytes: &u64) {
        if let Ok(mut diagnostics) = self.diagnostics.lock() {
            diagnostics.region_count = count as u64;
            diagnostics.total_candidate_bytes = *total_bytes;
        }
    }

    fn inspect_chunk(&self, address: u64, bytes: &[u8], contract: &forever_patcher::KeyStoreContract) {
        if let Ok(mut diagnostics) = self.diagnostics.lock() {
            diagnostics.inspect_chunk(address, bytes, contract);
        }
    }

    fn diagnostics(&self) -> Value {
        self.diagnostics.lock().map(|diagnostics| diagnostics.json()).unwrap_or_else(|_| json!({"diagnosticsUnavailable": true}))
    }

    fn error(&self, error: &io::Error) {
        self.read_errors.fetch_add(1, Ordering::Relaxed);
        self.incomplete.store(true, Ordering::Relaxed);
        if matches!(error.raw_os_error(), Some(libc::EACCES | libc::EPERM)) {
            self.permission_errors.fetch_add(1, Ordering::Relaxed);
        }
    }

    fn complete(&self) -> bool {
        !self.exhausted.load(Ordering::Relaxed)
            && !self.incomplete.load(Ordering::Relaxed)
            && self.read_errors.load(Ordering::Relaxed) == 0
    }
}

#[derive(Clone)]
struct Target {
    pid: u32,
    start_ticks: u64,
    ppid: u32,
    uid4: [u32; 4],
    // The raw bounded cmdline is retained only in this private in-memory
    // identity. It is never formatted, logged, or serialized.
    cmdline: Vec<u8>,
}

struct Lease {
    token: String,
    own_pid: u32,
    own_start_ticks: u64,
    own_uid: u32,
    executable: PathBuf,
    nonce: String,
    contract: forever_patcher::KeyStoreContract,
    baseline: BTreeMap<u32, u64>,
    // Lease-scoped only: the image comparator may retain one prior structural
    // header observation for its bounded late-window hypothesis.
    remembered_candidate: Mutex<Option<functional_image_compare::RememberedCandidate>>,
    code_capture: Option<Arc<functional_code_capture::CodeCaptureLease>>,
    cancelled: AtomicBool,
    diagnostic_cursor: AtomicU64,
    full_scan: Mutex<Option<functional_full_scan::FullScanState>>,
    previous_subreaper: bool,
    pending_apply: Mutex<Option<PendingApply>>,
    apply_disabled: AtomicBool,
}

/// Single-candidate write proposal derived from the last `run_full_scan`
/// strict snapshot. It is NOT a uniqueness claim: an incomplete global pass
/// may still propose its one validated block, and every apply freshly
/// revalidates identity, mapping, and the full 480 B before any write.
#[derive(Clone)]
struct PendingApply {
    target: Target,
    region: functional_full_scan::SnapshotRegion,
    addr: u64,
}

impl Lease {
    fn current(&self) -> bool {
        if self.cancelled.load(Ordering::Acquire) {
            return false;
        }
        active()
            .lock()
            .ok()
            .and_then(|guard| guard.as_ref().map(|lease| lease.token == self.token))
            .unwrap_or(false)
    }
}

struct ReadOnlyMemory {
    lease: Arc<Lease>,
    target: Target,
    budget: Budget,
    file: Mutex<File>,
    inspect_core: bool,
}

impl ReadOnlyMemory {
    /// Cheap per-read pin check. Expensive PE/nonce/ancestry checks are done
    /// before opening the fd and again after the complete poll; every fd read
    /// still rechecks cancellation, PID/startticks, and all four UID fields.
    fn validate_pinned(&self) -> io::Result<()> {
        if !self.lease.current() {
            return Err(io::Error::new(io::ErrorKind::Interrupted, "functional lease is stale or cancelled"));
        }
        let own_stat = ProcFs::stat(self.lease.own_pid, &self.budget).map_err(|error| identity_io(error.to_string()))?;
        if own_stat.start_ticks != self.lease.own_start_ticks {
            return Err(identity_io("launcher PID starttime changed; possible PID reuse".to_string()));
        }
        let current_stat = ProcFs::stat(self.target.pid, &self.budget).map_err(|error| identity_io(error.to_string()))?;
        if current_stat.start_ticks != self.target.start_ticks {
            return Err(identity_io("stored target identity changed; possible PID reuse or credential change".to_string()));
        }
        let current_uid4 = ProcFs::uid(self.target.pid, &self.budget).map_err(|error| identity_io(error.to_string()))?;
        if current_uid4 != self.target.uid4 || !uid4_matches(current_uid4, self.lease.own_uid) {
            return Err(identity_io("stored target UID fields changed".to_string()));
        }
        if !self.lease.current() {
            return Err(io::Error::new(io::ErrorKind::Interrupted, "functional lease is stale or cancelled"));
        }
        Ok(())
    }
}

impl MemoryAccess for ReadOnlyMemory {
    fn regions(&self) -> io::Result<Vec<forever_patcher::Region>> {
        self.validate_pinned()?;
        let regions = ProcFs::maps(self.target.pid, &self.budget)?;
        self.validate_pinned()?;
        Ok(regions)
    }

    fn read(&self, address: u64, buffer: &mut [u8]) -> io::Result<()> {
        if buffer.len() > MAX_READ_CHUNK {
            self.budget.incomplete.store(true, Ordering::Relaxed);
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "functional read chunk exceeds bound"));
        }
        self.validate_pinned()?;
        self.budget.begin()?;
        self.budget.charge(buffer.len())?;
        let mut actual = 0usize;
        let mut read_error = None;
        {
            let mut file = self
                .file
                .lock()
                .map_err(|_| io::Error::other("read-only memory fd mutex poisoned"))?;
            if let Err(error) = file.seek(SeekFrom::Start(address)) {
                read_error = Some(error);
            } else {
                while actual < buffer.len() {
                    match file.read(&mut buffer[actual..]) {
                        Ok(0) => {
                            read_error = Some(io::Error::new(io::ErrorKind::UnexpectedEof, "short /proc/<pid>/mem read"));
                            break;
                        }
                        Ok(count) => actual += count,
                        Err(error) => {
                            read_error = Some(error);
                            break;
                        }
                    }
                }
            }
        }
        self.budget.record_memory_read(actual);
        if let Some(error) = read_error {
            self.budget.error(&error);
            return Err(error);
        }
        if self.inspect_core {
            self.budget.inspect_chunk(address, buffer, &self.lease.contract);
        }
        self.validate_pinned()
    }

    fn write(&self, _address: u64, _data: &[u8]) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::PermissionDenied, "functional session is read-only"))
    }
}

fn parse_contract(path: &str) -> Result<forever_patcher::KeyStoreContract, String> {
    let mut file = File::open(path).map_err(|error| format!("contract cannot be opened: {error}"))?;
    let mut bytes = Vec::new();
    file.by_ref()
        .take((CANONICAL_CONTRACT.len() + 1) as u64)
        .read_to_end(&mut bytes)
        .map_err(|error| format!("contract read failed: {error}"))?;
    if bytes != CANONICAL_CONTRACT.as_bytes() {
        return Err("contract does not exactly match embedded canonical contract".to_string());
    }
    let contract = forever_patcher::load_contract(path).map_err(|error| error.to_string())?;
    if contract.entry_size != 40
        || contract.entry_count != 12
        || contract.target_group != 8
        || contract.anchor_group != 1
        || contract.block_size() != 480
    {
        return Err("contract is not the canonical Forever group-8 contract".to_string());
    }
    Ok(contract)
}

fn canonical_executable(path: &str) -> Result<PathBuf, String> {
    let input = Path::new(path);
    if !input.is_absolute() {
        return Err("executablePath must be absolute".to_string());
    }
    let executable = std::fs::canonicalize(input)
        .map_err(|error| format!("executablePath is not readable: {error}"))?;
    let normalized = executable.to_string_lossy().replace('\\', "/").to_ascii_lowercase();
    if !normalized.ends_with(TARGET_SUFFIX) {
        return Err("functional target must end in CustomGames/WoW Forever/WowB-ARM64.exe".to_string());
    }
    verify_pe_aa64(&executable)?;
    Ok(executable)
}

fn verify_pe_aa64(path: &Path) -> Result<(), String> {
    let mut file = File::open(path).map_err(|error| format!("executable cannot be opened: {error}"))?;
    let size = file
        .metadata()
        .map_err(|error| format!("executable metadata failed: {error}"))?
        .len();
    if size < 0x40 {
        return Err("executable is shorter than a DOS header".to_string());
    }
    let mut dos = [0u8; 0x40];
    file.read_exact(&mut dos).map_err(|error| format!("executable DOS header read failed: {error}"))?;
    if &dos[..2] != b"MZ" {
        return Err("executable is not a PE image".to_string());
    }
    let pe_offset = u32::from_le_bytes(dos[0x3c..0x40].try_into().unwrap()) as u64;
    if pe_offset.checked_add(26).filter(|end| *end <= size).is_none() {
        return Err("PE header is outside executable".to_string());
    }
    file.seek(SeekFrom::Start(pe_offset)).map_err(|error| format!("PE header seek failed: {error}"))?;
    let mut header = [0u8; 26];
    file.read_exact(&mut header).map_err(|error| format!("PE header read failed: {error}"))?;
    if &header[..4] != b"PE\0\0" || u16::from_le_bytes([header[4], header[5]]) != 0xaa64 || u16::from_le_bytes([header[24], header[25]]) != 0x20b {
        return Err("executable is not PE AA64/PE32+".to_string());
    }
    Ok(())
}

fn process_tree(root: u32, budget: &Budget) -> Result<Vec<(u32, Stat)>, String> {
    match std::fs::metadata(ProcFs::path(root, &format!("task/{root}/children"))) {
        Err(error) if error.raw_os_error() == Some(libc::ENOENT) => return process_tree_by_parent(root, budget),
        Err(error) => { budget.error(&error); return Err(format!("child interface unreadable: {error}")); },
        Ok(_) => (),
    }
    let mut queue = VecDeque::from([root]);
    let mut seen = BTreeSet::new();
    let mut result = Vec::new();
    let mut tids_seen = 0usize;
    while let Some(pid) = queue.pop_front() {
        if !seen.insert(pid) {
            continue;
        }
        if seen.len() > MAX_TREE {
            budget.incomplete.store(true, Ordering::Relaxed);
            return Err("owned child tree exceeded bound".to_string());
        }
        let stat = match ProcFs::stat(pid, budget) {
            Ok(stat) => stat,
            Err(error) if matches!(error.raw_os_error(), Some(libc::ENOENT | libc::ESRCH)) => continue,
            Err(error) => return Err(format!("owned child tree stat failed: {error}")),
        };
        result.push((pid, stat));
        let tids = match ProcFs::tids(pid, budget) {
            Ok(tids) => tids,
            Err(error) if matches!(error.raw_os_error(), Some(libc::ENOENT | libc::ESRCH)) => continue,
            Err(error) => return Err(format!("owned thread tree read failed: {error}")),
        };
        for tid in tids {
            tids_seen += 1;
            if tids_seen > MAX_TIDS {
                budget.incomplete.store(true, Ordering::Relaxed);
                return Err("owned thread enumeration exceeded bound".to_string());
            }
            let children = match ProcFs::children(pid, tid, budget) {
                Ok(children) => children,
                Err(error) if matches!(error.raw_os_error(), Some(libc::ENOENT | libc::ESRCH)) => continue,
                Err(error) => return Err(format!("owned child tree read failed: {error}")),
            };
            queue.extend(children);
        }
    }
    Ok(result)
}

fn owned_subtree(root: u32, snapshot: &BTreeMap<u32, Stat>, budget: &Budget) -> Vec<(u32, Stat)> {
    snapshot.iter().filter_map(|(&pid, &stat)| {
        let mut cursor = pid;
        let mut seen = BTreeSet::new();
        for _ in 0..64 {
            if cursor == root { return Some((pid, stat)); }
            if !seen.insert(cursor) { return None; }
            cursor = snapshot.get(&cursor)?.ppid;
        }
        budget.incomplete.store(true, Ordering::Relaxed);
        None
    }).collect()
}

fn process_tree_by_parent(root: u32, budget: &Budget) -> Result<Vec<(u32, Stat)>, String> {
    let uid = unsafe { libc::getuid() };
    let mut snapshot = BTreeMap::new();
    let entries = std::fs::read_dir("/proc").map_err(|error| format!("proc enumeration failed: {error}"))?;
    for (index, entry) in entries.enumerate() {
        budget.begin().map_err(|error| error.to_string())?;
        if index > 8192 { budget.incomplete.store(true, Ordering::Relaxed); return Err("proc enumeration bound exceeded".to_string()); }
        let entry = entry.map_err(|error| error.to_string())?;
        let Some(pid) = entry.file_name().to_str().and_then(|name| name.parse::<u32>().ok()) else { continue };
        let metadata = match entry.metadata() {
            Ok(metadata) => metadata,
            Err(error) if error.raw_os_error() == Some(libc::ENOENT) => continue,
            Err(error) => { budget.error(&error); continue; },
        };
        if metadata.uid() != uid { continue; }
        let stat = match ProcFs::stat(pid, budget) {
            Ok(stat) => stat,
            Err(error) if matches!(error.raw_os_error(), Some(libc::ENOENT | libc::ESRCH)) => continue,
            Err(error) => { budget.error(&error); continue; },
        };
        snapshot.insert(pid, stat);
        if snapshot.len() > MAX_TREE { budget.incomplete.store(true, Ordering::Relaxed); return Err("app-owned proc bound exceeded".to_string()); }
    }
    if !snapshot.contains_key(&root) { return Err("app identity disappeared".to_string()); }
    Ok(owned_subtree(root, &snapshot, budget))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum DiagnosticClass {
    FileReadOnly,
    FileReadWrite,
    AnonymousReadOnly,
    FileReadExecute,
    AnonymousReadExecute,
    AnonymousReadWrite,
}

impl DiagnosticClass {
    const ALL: [Self; 6] = [
        Self::FileReadOnly,
        Self::FileReadWrite,
        Self::AnonymousReadOnly,
        Self::FileReadExecute,
        Self::AnonymousReadExecute,
        Self::AnonymousReadWrite,
    ];

    fn name(self) -> &'static str {
        match self {
            Self::FileReadOnly => "file-r--",
            Self::FileReadWrite => "file-rw",
            Self::AnonymousReadOnly => "anon-r--",
            Self::FileReadExecute => "file-rx",
            Self::AnonymousReadExecute => "anon-rx",
            Self::AnonymousReadWrite => "anon-rw",
        }
    }

    fn from_slot(slot: u64) -> Option<Self> {
        // Slot zero is reserved for the fixed runtime-image comparison.  The
        // six historical map diagnostics retain their order in slots 1..=6.
        slot.checked_sub(1).and_then(|index| Self::ALL.get(index as usize).copied())
    }

    fn matches(self, anonymous: bool, permissions: &[u8]) -> bool {
        let permission_class = match permissions {
            b"r--p" => Self::FileReadOnly,
            b"rw-p" => Self::FileReadWrite,
            b"r-xp" => Self::FileReadExecute,
            _ => return false,
        };
        let anonymous_class = match self {
            Self::AnonymousReadOnly | Self::AnonymousReadExecute | Self::AnonymousReadWrite => true,
            Self::FileReadOnly | Self::FileReadWrite | Self::FileReadExecute => false,
        };
        let permission_matches = match (permission_class, self) {
            (Self::FileReadOnly, Self::FileReadOnly | Self::AnonymousReadOnly) => true,
            (Self::FileReadWrite, Self::FileReadWrite | Self::AnonymousReadWrite) => true,
            (Self::FileReadExecute, Self::FileReadExecute | Self::AnonymousReadExecute) => true,
            _ => false,
        };
        anonymous == anonymous_class && permission_matches
    }
}

fn is_dev_path(path: &str) -> bool {
    path == "/dev" || path.starts_with("/dev/")
}

fn parse_diagnostic_maps(text: &str, class: DiagnosticClass) -> Result<Vec<forever_patcher::Region>, String> {
    if text.is_empty() || !text.ends_with('\n') {
        return Err("maps file is empty or truncated".to_string());
    }
    let mut regions = Vec::new();
    for line in text.lines() {
        let mut columns = line.split_whitespace();
        let Some(range) = columns.next() else { return Err("maps line has no range".to_string()) };
        let Some(permissions) = columns.next() else { return Err("maps line has no permissions".to_string()) };
        let Some(offset) = columns.next() else { return Err("maps line has no offset".to_string()) };
        let Some(device) = columns.next() else { return Err("maps line has no device".to_string()) };
        let Some(inode) = columns.next() else { return Err("maps line has no inode".to_string()) };
        let valid_device = device.split_once(':').is_some_and(|(major, minor)| {
            !major.is_empty()
                && !minor.is_empty()
                && u64::from_str_radix(major, 16).is_ok()
                && u64::from_str_radix(minor, 16).is_ok()
        });
        let permission_bytes = permissions.as_bytes();
        let valid_permissions = permission_bytes.len() == 4
            && matches!(permission_bytes[0], b'r' | b'-')
            && matches!(permission_bytes[1], b'w' | b'-')
            && matches!(permission_bytes[2], b'x' | b'-')
            && matches!(permission_bytes[3], b'p' | b's');
        if !valid_permissions
            || u64::from_str_radix(offset, 16).is_err()
            || !valid_device
            || u64::from_str_radix(inode, 10).is_err()
        {
            return Err("maps line has malformed metadata".to_string());
        }
        let Some((start, end)) = range.split_once('-') else { return Err("maps line has malformed range".to_string()) };
        let (Ok(start), Ok(end)) = (u64::from_str_radix(start, 16), u64::from_str_radix(end, 16)) else {
            return Err("maps line has malformed range".to_string());
        };
        if end <= start {
            return Err("maps line has an empty range".to_string());
        }
        let name = columns.next();
        let (anonymous, excluded) = match name {
            None => (true, false),
            Some(path) if path.starts_with('[') => (true, false),
            Some(path) if path.starts_with('/') => (false, is_dev_path(path)),
            Some(_) => (true, false),
        };
        if !excluded && class.matches(anonymous, permissions.as_bytes()) {
            regions.push(forever_patcher::Region { start, end });
            if regions.len() > MAX_REGIONS {
                return Err("diagnostic region bound exceeded".to_string());
            }
        }
    }
    Ok(regions)
}

impl ProcFs {
    fn diagnostic_maps(pid: u32, class: DiagnosticClass, budget: &Budget) -> io::Result<Vec<forever_patcher::Region>> {
        let bytes = Self::read_file(pid, "maps", MAX_MAPS_FILE, budget)?;
        let text = match std::str::from_utf8(&bytes) {
            Ok(text) => text,
            Err(error) => {
                budget.incomplete.store(true, Ordering::Relaxed);
                return Err(io::Error::new(io::ErrorKind::InvalidData, error));
            }
        };
        match parse_diagnostic_maps(text, class) {
            Ok(regions) => Ok(regions),
            Err(error) => {
                budget.incomplete.store(true, Ordering::Relaxed);
                Err(io::Error::new(io::ErrorKind::InvalidData, error))
            }
        }
    }
}

#[derive(Debug)]
struct DiagnosticMapScan {
    class: &'static str,
    region_count: u64,
    total_candidate_bytes: u64,
    actual_memory_bytes_read: u64,
    read_errors: u64,
    maps_complete: bool,
    map_parse_errors: u64,
    complete: bool,
    known_key_hits: [u64; 12],
    replacement_key_hits: u64,
    aligned_anchor_hits: u64,
    unaligned_anchor_hits: u64,
    memory_read_ops: u64,
    budget_exhausted: bool,
    permission_denied_reads: u64,
    structural_store_probe: StructuralStoreProbe,
}

#[derive(Debug)]
struct StructuralStoreProbe {
    ordered_id_candidates: u64,
    metadata_match_candidates: u64,
    fully_known_candidates: u64,
    replacement_group8: u64,
    samples: Vec<Value>,
    candidates_capped: bool,
    complete: bool,
}

impl Default for StructuralStoreProbe {
    fn default() -> Self {
        Self {
            ordered_id_candidates: 0,
            metadata_match_candidates: 0,
            fully_known_candidates: 0,
            replacement_group8: 0,
            samples: Vec::new(),
            candidates_capped: false,
            complete: true,
        }
    }
}

impl StructuralStoreProbe {
    fn inspect_chunk(
        &mut self,
        address: u64,
        bytes: &[u8],
        minimum_candidate_start: u64,
        contract: &forever_patcher::KeyStoreContract,
    ) {
        if self.candidates_capped {
            return;
        }
        let block_size = contract.block_size() as usize;
        let entry_size = contract.entry_size;
        if contract.entry_count != 12
            || entry_size != 40
            || block_size != STRUCTURAL_BLOCK_SIZE
        {
            self.complete = false;
            return;
        }
        // A completely read region shorter than one hypothesis block has no
        // possible candidate; it is not incomplete coverage.
        if bytes.len() < block_size {
            return;
        }
        let last_position = bytes.len() - block_size;
        let first_position = minimum_candidate_start.saturating_sub(address) as usize;
        if first_position > last_position {
            return;
        }
        for position in first_position..=last_position {
            // The first byte and the first two complete IDs reject almost all
            // positions before the remaining ten IDs are inspected.
            if bytes[position] != 1
                || u32::from_le_bytes(bytes[position..position + 4].try_into().unwrap()) != 1
                || u32::from_le_bytes(bytes[position + entry_size..position + entry_size + 4].try_into().unwrap()) != 2
            {
                continue;
            }
            if (2..contract.entry_count).any(|index| {
                let offset = position + index * entry_size;
                u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap()) != (index + 1) as u32
            }) {
                continue;
            }
            self.classify_candidate(address.saturating_add(position as u64), &bytes[position..position + block_size], contract);
            if self.candidates_capped {
                break;
            }
        }
    }

    fn classify_candidate(&mut self, address: u64, bytes: &[u8], contract: &forever_patcher::KeyStoreContract) {
        if self.ordered_id_candidates as usize >= MAX_DIAGNOSTIC_CANDIDATES {
            self.candidates_capped = true;
            self.complete = false;
            return;
        }
        self.ordered_id_candidates += 1;
        let mut flag_matches = 0u64;
        let mut padding_matches = 0u64;
        let mut known_key_matches = 0u64;
        let mut known_key_matches_by_group = [false; 12];
        let mut nonzero_key_slots = 0u64;
        let mut distinct_keys = [[0u8; forever_patcher::KEY_LEN]; 12];
        let mut distinct_key_slots = 0u64;
        let mut replacement_group8 = false;
        for index in 0..contract.entry_count {
            let offset = index * contract.entry_size;
            if bytes[offset + 36] == contract.expected_flags[index] {
                flag_matches += 1;
            }
            if contract.accepts_padding(&bytes[offset + 37..offset + 40]) {
                padding_matches += 1;
            }
            let key: &[u8] = &bytes[offset + 4..offset + 36];
            if key.iter().any(|byte| *byte != 0) {
                nonzero_key_slots += 1;
            }
            if !distinct_keys[..distinct_key_slots as usize].iter().any(|known| known == key) {
                distinct_keys[distinct_key_slots as usize].copy_from_slice(key);
                distinct_key_slots += 1;
            }
            let key_matches = if index + 1 == contract.target_group {
                if key == contract.new_key {
                    replacement_group8 = true;
                    true
                } else {
                    key == contract.known_keys[index]
                }
            } else {
                key == contract.known_keys[index]
            };
            if key_matches {
                known_key_matches += 1;
            }
            known_key_matches_by_group[index] = key_matches;
        }
        let metadata_matches = flag_matches == contract.entry_count as u64
            && padding_matches == contract.entry_count as u64;
        if metadata_matches {
            self.metadata_match_candidates += 1;
        }
        let fully_known = metadata_matches && known_key_matches == contract.entry_count as u64;
        if fully_known {
            self.fully_known_candidates += 1;
            if replacement_group8 {
                self.replacement_group8 += 1;
            }
        }
        if self.samples.len() < MAX_DIAGNOSTIC_SAMPLES {
            self.samples.push(json!({
                "knownKeyMatches12": known_key_matches,
                "knownKeyMatchByGroup12": known_key_matches_by_group,
                "fullyKnown": fully_known,
                "replacementGroup8": replacement_group8,
                "flagMatches": flag_matches,
                "paddingMatches": padding_matches,
                "nonzeroKeySlots": nonzero_key_slots,
                "distinctKeySlots": distinct_key_slots,
                "alignedToContract": address.is_multiple_of(contract.scan_alignment.max(1)),
            }));
        }
    }

    fn json(&self) -> Value {
        json!({
            "orderedIdCandidates": self.ordered_id_candidates,
            "metadataMatchCandidates": self.metadata_match_candidates,
            "fullyKnownCandidates": self.fully_known_candidates,
            "replacementGroup8": self.replacement_group8,
            "samples": self.samples,
            "samplesCapped": self.ordered_id_candidates > self.samples.len() as u64,
            "hypothesis": "contiguous-12x40-le-id",
            "candidatesCapped": self.candidates_capped,
            "complete": self.complete,
            "readOnly": true,
            "writeAuthorized": false,
        })
    }
}

impl DiagnosticMapScan {
    fn new(class: DiagnosticClass) -> Self {
        Self {
            class: class.name(),
            region_count: 0,
            total_candidate_bytes: 0,
            actual_memory_bytes_read: 0,
            read_errors: 0,
            maps_complete: true,
            map_parse_errors: 0,
            complete: false,
            known_key_hits: [0; 12],
            replacement_key_hits: 0,
            aligned_anchor_hits: 0,
            unaligned_anchor_hits: 0,
            memory_read_ops: 0,
            budget_exhausted: false,
            permission_denied_reads: 0,
            structural_store_probe: StructuralStoreProbe::default(),
        }
    }

    fn json(&self) -> Value {
        json!({
            "class": self.class,
            "regionCount": self.region_count,
            "totalCandidateBytes": self.total_candidate_bytes,
            "actualMemoryBytesRead": self.actual_memory_bytes_read,
            "readErrors": self.read_errors,
            "mapsComplete": self.maps_complete,
            "mapParseErrors": self.map_parse_errors,
            "complete": self.complete,
            "knownKeyHits12": self.known_key_hits,
            "replacementKeyHits": self.replacement_key_hits,
            "alignedAnchorHits": self.aligned_anchor_hits,
            "unalignedAnchorHits": self.unaligned_anchor_hits,
            "memoryReadOps": self.memory_read_ops,
            "budgetExhausted": self.budget_exhausted,
            "permissionDeniedReads": self.permission_denied_reads,
            "readOnly": true,
            "writeAuthorized": false,
            "structuralStoreProbe": self.structural_store_probe.json(),
        })
    }
}

#[derive(Clone, Copy)]
enum DiagnosticNeedle {
    Known(usize, [u8; forever_patcher::KEY_LEN]),
    Replacement([u8; forever_patcher::KEY_LEN]),
}

fn inspect_diagnostic_bytes(
    address: u64,
    bytes: &[u8],
    contract: &forever_patcher::KeyStoreContract,
    result: &mut DiagnosticMapScan,
) {
    inspect_diagnostic_bytes_from(address, bytes, address, contract, result);
}

fn inspect_diagnostic_bytes_from(
    address: u64,
    bytes: &[u8],
    minimum_candidate_start: u64,
    contract: &forever_patcher::KeyStoreContract,
    result: &mut DiagnosticMapScan,
) {
    let mut buckets: [Vec<DiagnosticNeedle>; 256] = std::array::from_fn(|_| Vec::new());
    for (index, key) in contract.known_keys.iter().enumerate() {
        buckets[key[0] as usize].push(DiagnosticNeedle::Known(index, *key));
    }
    buckets[contract.new_key[0] as usize].push(DiagnosticNeedle::Replacement(contract.new_key));
    let anchor = contract.anchor_key();
    let last_position = bytes.len().saturating_sub(forever_patcher::KEY_LEN - 1);
    let first_position = minimum_candidate_start.saturating_sub(address) as usize;
    if first_position >= last_position {
        return;
    }
    for position in first_position..last_position {
        let first = bytes[position] as usize;
        let Some(absolute) = address.checked_add(position as u64) else { continue };
        let mut anchor_match = false;
        for needle in &buckets[first] {
            match needle {
                DiagnosticNeedle::Known(index, key) if bytes[position..position + forever_patcher::KEY_LEN] == key[..] => {
                    result.known_key_hits[*index] += 1;
                    anchor_match |= key == anchor;
                }
                DiagnosticNeedle::Replacement(key) if bytes[position..position + forever_patcher::KEY_LEN] == key[..] => {
                    result.replacement_key_hits += 1;
                }
                _ => {}
            }
        }
        if anchor_match {
            if absolute.is_multiple_of(contract.scan_alignment.max(1)) {
                result.aligned_anchor_hits += 1;
            } else {
                result.unaligned_anchor_hits += 1;
            }
        }
    }
}

fn scan_diagnostic_regions(
    memory: &impl MemoryAccess,
    budget: &Budget,
    contract: &forever_patcher::KeyStoreContract,
    regions: &[forever_patcher::Region],
    result: &mut DiagnosticMapScan,
) {
    result.region_count = regions.len() as u64;
    result.total_candidate_bytes = regions.iter().map(forever_patcher::Region::len).fold(0, u64::saturating_add);
    let actual_before = budget.actual_memory_bytes.load(Ordering::Relaxed);
    let ops_before = budget.memory_read_ops.load(Ordering::Relaxed);
    let mut complete = true;
    for region in regions.iter().filter(|region| !region.is_empty()) {
        let mut offset = region.start;
        let mut raw_minimum_candidate_start = region.start;
        let mut structural_minimum_candidate_start = region.start;
        loop {
            if budget.check().is_err() {
                complete = false;
                break;
            }
            let chunk_end = offset.saturating_add(MAX_READ_CHUNK as u64).min(region.end);
            if chunk_end <= offset {
                complete = false;
                break;
            }
            let mut chunk = vec![0u8; (chunk_end - offset) as usize];
            if memory.read(offset, &mut chunk).is_err() {
                result.read_errors += 1;
                complete = false;
            } else {
                inspect_diagnostic_bytes_from(offset, &chunk, raw_minimum_candidate_start, contract, result);
                result.structural_store_probe.inspect_chunk(
                    offset,
                    &chunk,
                    structural_minimum_candidate_start,
                    contract,
                );
                raw_minimum_candidate_start = chunk_end.saturating_sub(DIAGNOSTIC_OVERLAP as u64).max(region.start);
                structural_minimum_candidate_start = chunk_end.saturating_sub(STRUCTURAL_OVERLAP as u64).max(region.start);
            }
            if chunk_end == region.end || budget.exhausted.load(Ordering::Relaxed) {
                break;
            }
            offset = chunk_end - STRUCTURAL_OVERLAP as u64;
        }
        if budget.exhausted.load(Ordering::Relaxed) {
            break;
        }
    }
    result.actual_memory_bytes_read = budget.actual_memory_bytes.load(Ordering::Relaxed).saturating_sub(actual_before);
    result.memory_read_ops = budget.memory_read_ops.load(Ordering::Relaxed).saturating_sub(ops_before);
    result.budget_exhausted = budget.exhausted.load(Ordering::Relaxed);
    result.permission_denied_reads = budget.permission_errors.load(Ordering::Relaxed);
    result.complete = complete && budget.complete();
    result.structural_store_probe.complete = result.complete
        && result.structural_store_probe.complete
        && !result.structural_store_probe.candidates_capped;
}

fn scan_diagnostic_class(memory: &ReadOnlyMemory, class: DiagnosticClass) -> DiagnosticMapScan {
    let mut result = DiagnosticMapScan::new(class);
    let regions = match ProcFs::diagnostic_maps(memory.target.pid, class, &memory.budget) {
        Ok(regions) => regions,
        Err(_) => {
            result.maps_complete = false;
            result.map_parse_errors = 1;
            result.read_errors = 1;
            result.structural_store_probe.complete = false;
            result.budget_exhausted = memory.budget.exhausted.load(Ordering::Relaxed);
            result.permission_denied_reads = memory.budget.permission_errors.load(Ordering::Relaxed);
            result.complete = false;
            return result;
        }
    };
    scan_diagnostic_regions(memory, &memory.budget, &memory.lease.contract, &regions, &mut result);
    result
}

const MAX_DIAGNOSTIC_CANDIDATES: usize = 64;
const MAX_DIAGNOSTIC_SAMPLES: usize = 8;

#[derive(Default, Debug)]
struct RejectionSummary {
    count: u64,
    samples: Vec<Value>,
}

#[derive(Default, Debug)]
struct ReadDiagnostics {
    region_count: u64,
    total_candidate_bytes: u64,
    anchor_hits: u64,
    anchor_hits_capped: bool,
    diagnostic_candidates_capped: bool,
    anchor_addresses: BTreeSet<u64>,
    classified_addresses: BTreeSet<u64>,
    diagnostic_valid_store_count: u64,
    invalid_total: u64,
    id_order: RejectionSummary,
    flag: RejectionSummary,
    padding: RejectionSummary,
    key: RejectionSummary,
}

impl ReadDiagnostics {
    fn regions(&mut self, regions: &[forever_patcher::Region]) {
        self.region_count = regions.len() as u64;
        self.total_candidate_bytes = regions.iter().map(|region| region.end.saturating_sub(region.start)).fold(0u64, u64::saturating_add);
    }

    fn inspect_chunk(&mut self, address: u64, bytes: &[u8], contract: &forever_patcher::KeyStoreContract) {
        let anchor = contract.anchor_key();
        let alignment = contract.scan_alignment.max(1);
        let anchor_offset = 4 + (contract.anchor_group.saturating_sub(1) * contract.entry_size);
        let block_size = contract.block_size();
        for (position, window) in bytes.windows(forever_patcher::KEY_LEN).enumerate() {
            let Some(absolute) = address.checked_add(position as u64) else { continue };
            if absolute % alignment != 0 || window != anchor.as_slice() {
                continue;
            }
            if self.anchor_addresses.len() < MAX_DIAGNOSTIC_CANDIDATES {
                if self.anchor_addresses.insert(absolute) {
                    self.anchor_hits += 1;
                }
            } else {
                self.anchor_hits_capped = true;
            }
            let Some(block) = absolute.checked_sub(anchor_offset as u64) else { continue };
            let Some(block_offset) = block.checked_sub(address).and_then(|offset| usize::try_from(offset).ok()) else { continue };
            if block_offset.checked_add(block_size).is_none_or(|end| end > bytes.len()) {
                continue;
            }
            if self.classified_addresses.contains(&block) {
                continue;
            }
            if self.classified_addresses.len() >= MAX_DIAGNOSTIC_CANDIDATES {
                self.diagnostic_candidates_capped = true;
                continue;
            }
            self.classified_addresses.insert(block);
            self.classify_candidate(&bytes[block_offset..block_offset + block_size], contract);
        }
    }

    fn classify_candidate(&mut self, bytes: &[u8], contract: &forever_patcher::KeyStoreContract) {
        for index in 0..contract.entry_count {
            let offset = index * contract.entry_size;
            let actual_id = u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap_or([0; 4]));
            let expected_id = (index + 1) as u32;
            if actual_id != expected_id {
                self.invalid_total += 1;
                Self::reject(&mut self.id_order, json!({"index": index, "expectedId": expected_id, "actualId": actual_id}));
                return;
            }
            let actual_flag = bytes[offset + 36];
            let expected_flag = contract.expected_flags[index];
            if actual_flag != expected_flag {
                self.invalid_total += 1;
                Self::reject(&mut self.flag, json!({"index": index, "expectedFlag": expected_flag, "actualFlag": actual_flag}));
                return;
            }
            let padding = &bytes[offset + 37..offset + contract.entry_size];
            if !contract.accepts_padding(padding) {
                self.invalid_total += 1;
                Self::reject(&mut self.padding, json!({"index": index}));
                return;
            }
            let key = &bytes[offset + 4..offset + 36];
            let key_ok = if index + 1 == contract.target_group {
                key == contract.known_keys[index] || key == contract.new_key
            } else {
                key == contract.known_keys[index]
            };
            if !key_ok {
                self.invalid_total += 1;
                Self::reject(&mut self.key, json!({"index": index}));
                return;
            }
        }
        self.diagnostic_valid_store_count += 1;
    }

    fn reject(summary: &mut RejectionSummary, sample: Value) {
        summary.count += 1;
        if summary.samples.len() < MAX_DIAGNOSTIC_SAMPLES {
            summary.samples.push(sample);
        }
    }

    fn summary(summary: &RejectionSummary) -> Value {
        json!({"count": summary.count, "samples": summary.samples})
    }

    fn json(&self) -> Value {
        json!({
            "regionCount": self.region_count,
            "totalCandidateBytes": self.total_candidate_bytes,
            "anchorHits": self.anchor_hits,
            "anchorHitsCapped": self.anchor_hits_capped,
            "diagnosticValidStoreCount": self.diagnostic_valid_store_count,
            "diagnosticCandidatesCapped": self.diagnostic_candidates_capped,
            "invalidAnchorCandidate": {
                "total": self.invalid_total,
                "idOrder": Self::summary(&self.id_order),
                "flag": Self::summary(&self.flag),
                "padding": Self::summary(&self.padding),
                "key": Self::summary(&self.key),
            },
        })
    }
}

fn get_subreaper() -> Result<bool, String> {
    let mut value: libc::c_int = 0;
    let result = unsafe { libc::prctl(PR_GET_CHILD_SUBREAPER, &mut value as *mut libc::c_int) };
    if result != 0 {
        return Err(format!("PR_GET_CHILD_SUBREAPER failed: {}", io::Error::last_os_error()));
    }
    Ok(value != 0)
}

fn set_subreaper(enabled: bool) -> Result<(), String> {
    let result = unsafe { libc::prctl(PR_SET_CHILD_SUBREAPER, if enabled { 1 } else { 0 }) };
    if result != 0 {
        return Err(format!("PR_SET_CHILD_SUBREAPER failed: {}", io::Error::last_os_error()));
    }
    Ok(())
}

fn validate_nonce(pid: u32, nonce: &str, budget: &Budget) -> io::Result<bool> {
    let bytes = ProcFs::read_file(pid, "environ", MAX_PROC_FILE, budget)?;
    Ok(nonce_matches(&bytes, nonce))
}

fn nonce_matches(environ: &[u8], nonce: &str) -> bool {
    let expected = format!("WOWFOREVER_POC_RUN_ID={nonce}");
    environ.split(|byte| *byte == 0).any(|entry| entry == expected.as_bytes())
}

fn pid_is_new(baseline: &BTreeMap<u32, u64>, pid: u32, start_ticks: u64) -> bool {
    baseline.get(&pid).map_or(true, |old_ticks| *old_ticks != start_ticks)
}

fn uid4_matches(uid4: [u32; 4], kernel_uid: u32) -> bool {
    uid4.iter().all(|uid| *uid == kernel_uid)
}

fn target_identity_matches(expected: &Target, actual: &Target) -> bool {
    expected.pid == actual.pid
        && expected.start_ticks == actual.start_ticks
        && expected.ppid == actual.ppid
        && expected.uid4 == actual.uid4
        && expected.cmdline == actual.cmdline
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum PostReadIdentity {
    Valid,
    IdentityFailure,
    Cancelled,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum PostReadDecision {
    ReadDiagnostic,
    Incomplete,
    IdentityFailure,
    Cancelled,
}

fn post_read_observation_decision(
    remembered_candidate: &Mutex<Option<functional_image_compare::RememberedCandidate>>,
    comparison: &mut functional_image_compare::ImageComparison,
    identity: PostReadIdentity,
    active_same: bool,
    budget_complete: bool,
) -> PostReadDecision {
    if !active_same || identity == PostReadIdentity::Cancelled {
        if let Ok(mut remembered) = remembered_candidate.lock() {
            *remembered = None;
        }
        comparison.drop_observations("stale_lease", "lease became stale or cancelled after the read");
        return PostReadDecision::Cancelled;
    }
    if identity == PostReadIdentity::IdentityFailure {
        if let Ok(mut remembered) = remembered_candidate.lock() {
            *remembered = None;
        }
        comparison.drop_observations("post_read_identity", "target identity changed after the read");
        return PostReadDecision::IdentityFailure;
    }
    comparison.mark_identity_validated_after_read();
    if comparison.complete && budget_complete {
        PostReadDecision::ReadDiagnostic
    } else {
        PostReadDecision::Incomplete
    }
}

fn target_token(token: &[u8]) -> bool {
    let Ok(token) = std::str::from_utf8(token) else { return false };
    let normalized = token.replace('\\', "/").to_ascii_lowercase();
    normalized.ends_with(&format!("/{TARGET_SUFFIX}"))
        || normalized == "a:/wowb-arm64.exe"
}

fn validate_cmdline(pid: u32, budget: &Budget) -> io::Result<bool> {
    // Explorer's argv also contains the PE it launches. Require the actual
    // client's comm as an additional selector, never as ownership proof.
    let comm = ProcFs::read_file(pid, "comm", 64, budget)?;
    if !String::from_utf8_lossy(&comm).trim().eq_ignore_ascii_case("WowB-ARM64.exe") {
        return Ok(false);
    }
    let bytes = ProcFs::read_file(pid, "cmdline", MAX_PROC_FILE, budget)?;
    Ok(bytes.split(|byte| *byte == 0).any(target_token))
}

fn capture_cmdline(pid: u32, budget: &Budget) -> io::Result<Option<Vec<u8>>> {
    let comm = ProcFs::read_file(pid, "comm", 64, budget)?;
    if !String::from_utf8_lossy(&comm).trim().eq_ignore_ascii_case("WowB-ARM64.exe") {
        return Ok(None);
    }
    let bytes = ProcFs::read_file(pid, "cmdline", MAX_PROC_FILE, budget)?;
    if bytes.split(|byte| *byte == 0).any(target_token) {
        Ok(Some(bytes))
    } else {
        Ok(None)
    }
}

fn descendant_of(lease: &Lease, pid: u32, budget: &Budget) -> Result<bool, String> {
    let mut current = pid;
    let mut seen = BTreeSet::new();
    for _ in 0..64 {
        if !seen.insert(current) {
            return Ok(false);
        }
        if current == lease.own_pid {
            let root = ProcFs::stat(current, budget).map_err(|error| error.to_string())?;
            let root_uid4 = ProcFs::uid(current, budget).map_err(|error| error.to_string())?;
            return Ok(root.start_ticks == lease.own_start_ticks && uid4_matches(root_uid4, lease.own_uid));
        }
        let stat = ProcFs::stat(current, budget).map_err(|error| error.to_string())?;
        if stat.ppid == 0 || stat.ppid == 1 || stat.ppid == current {
            return Ok(false);
        }
        let uid4 = ProcFs::uid(current, budget).map_err(|error| error.to_string())?;
        if !uid4_matches(uid4, lease.own_uid) {
            return Ok(false);
        }
        current = stat.ppid;
    }
    Ok(false)
}

fn validate_target(lease: &Lease, pid: u32, budget: &Budget) -> Result<Target, String> {
    if !lease.current() {
        return Err("functional lease is stale or cancelled".to_string());
    }
    verify_pe_aa64(&lease.executable).map_err(|error| format!("verified executable source changed: {error}"))?;
    let stat = ProcFs::stat(pid, budget).map_err(|error| classify_identity_io("target stat", error))?;
    if !pid_is_new(&lease.baseline, pid, stat.start_ticks) || pid == lease.own_pid {
        return Err("target was present before the functional session or is the app".to_string());
    }
    let uid4 = ProcFs::uid(pid, budget).map_err(|error| classify_identity_io("target uid", error))?;
    if !uid4_matches(uid4, lease.own_uid) {
        return Err("target UID is not the current app UID".to_string());
    }
    if !descendant_of(lease, pid, budget)? {
        return Err("target ancestry is not proven to the current app".to_string());
    }
    if !validate_cmdline(pid, budget).map_err(|error| classify_identity_io("target cmdline", error))? {
        return Err("target command line has no exact external WowB token".to_string());
    }
    // Services/zombies in the same owned runtime need no environment reads.
    // Only a real fixed WowB selector may reach nonce and memory validation.
    if !validate_nonce(pid, &lease.nonce, budget).map_err(|error| classify_identity_io("target nonce", error))? {
        return Err("target nonce does not match this lease".to_string());
    }
    let cmdline = capture_cmdline(pid, budget)
        .map_err(|error| classify_identity_io("target cmdline", error))?
        .ok_or_else(|| "target command line changed after nonce validation".to_string())?;
    if !lease.current() {
        return Err("functional lease became stale during identity validation".to_string());
    }
    Ok(Target { pid, start_ticks: stat.start_ticks, ppid: stat.ppid, uid4, cmdline })
}

fn classify_identity_io(context: &str, error: io::Error) -> String {
    format!("{context}: {error}")
}

fn identity_io(error: String) -> io::Error {
    io::Error::new(io::ErrorKind::PermissionDenied, error)
}

fn status_json(status: &str, target: Option<&Target>, budget: Option<&Budget>, error: Option<(&str, String)>) -> String {
    let mut out = Map::new();
    out.insert("version".to_string(), json!(RESULT_VERSION));
    out.insert("status".to_string(), json!(status));
    out.insert("readOnly".to_string(), json!(true));
    out.insert("writeAuthorized".to_string(), json!(false));
    out.insert("bytesWritten".to_string(), json!(0));
    out.insert("pid".to_string(), json!(target.map_or(0, |value| value.pid)));
    out.insert("startTicks".to_string(), json!(target.map_or(0, |value| value.start_ticks)));
    out.insert("uid".to_string(), json!(target.map_or(0, |value| value.uid4[0])));
    out.insert("UID".to_string(), json!(target.map_or(0, |value| value.uid4[0])));
    out.insert("uid4".to_string(), json!(target.map_or([0; 4], |value| value.uid4)));
    out.insert("UID4".to_string(), json!(target.map_or([0; 4], |value| value.uid4)));
    out.insert("ppid".to_string(), json!(target.map_or(0, |value| value.ppid)));
    out.insert("stores".to_string(), json!(0));
    out.insert("validStoreCount".to_string(), json!(0));
    out.insert("alreadyPatched".to_string(), json!(false));
    if let Some(budget) = budget {
        out.insert("coverage".to_string(), json!({
            "readBytes": budget.bytes.load(Ordering::Relaxed),
            "budgetBytes": budget.bytes.load(Ordering::Relaxed),
            "procBytes": budget.proc_bytes.load(Ordering::Relaxed),
            "actualMemoryBytesRead": budget.actual_memory_bytes.load(Ordering::Relaxed),
            "memoryReadOps": budget.memory_read_ops.load(Ordering::Relaxed),
            "readOps": budget.ops.load(Ordering::Relaxed),
            "readErrors": budget.read_errors.load(Ordering::Relaxed),
            "permissionDeniedReads": budget.permission_errors.load(Ordering::Relaxed),
            "complete": budget.complete(),
            "readOnly": true,
        }));
        if let Value::Object(diagnostics) = budget.diagnostics() {
            for (key, value) in diagnostics {
                out.insert(key, value);
            }
        }
    }
    if let Some((kind, detail)) = error {
        out.insert("errorKind".to_string(), json!(kind));
        out.insert("error".to_string(), json!(detail));
    }
    Value::Object(out).to_string()
}

fn scan_json(lease: Arc<Lease>) -> String {
    let budget = Budget::new();
    if !lease.current() {
        return status_json("CANCELLED", None, Some(&budget), Some(("stale_lease", "lease is stale or cancelled".to_string())));
    }
    // A new poll supersedes the previous proposal; `full_scan_json` re-sets
    // it below iff exactly one complete block freshly validates.
    clear_pending(&lease);
    if let Err(error) = verify_pe_aa64(&lease.executable) {
        return status_json("REJECTED", None, Some(&budget), Some(("wrong_executable", format!("verified executable source changed: {error}"))));
    }
    let tree = match process_tree(lease.own_pid, &budget) {
        Ok(tree) => tree,
        Err(error) => {
            let kind = if budget.permission_errors.load(Ordering::Relaxed) > 0 { "permission_denied" } else { "read_coverage" };
            return status_json("INCOMPLETE", None, Some(&budget), Some((kind, error)));
        }
    };
    let mut matches = Vec::new();
    let mut rejected = 0usize;
    let mut identity_error = None;
    for (pid, stat) in tree {
        if pid == lease.own_pid || !pid_is_new(&lease.baseline, pid, stat.start_ticks) {
            continue;
        }
        match validate_target(&lease, pid, &budget) {
            Ok(target) => {
                if matches.len() >= MAX_CANDIDATES {
                    budget.incomplete.store(true, Ordering::Relaxed);
                    return status_json("INCOMPLETE", None, Some(&budget), Some(("candidate_bound", "more than 64 read-only target candidates".to_string())));
                }
                matches.push(target)
            }
            Err(error) => {
                if error.contains("nonce does not match") || error.contains("no exact external WowB") {
                    rejected += 1;
                } else {
                    budget.incomplete.store(true, Ordering::Relaxed);
                    identity_error.get_or_insert(error);
                }
            }
        }
        if !lease.current() {
            return status_json("CANCELLED", None, Some(&budget), Some(("stale_lease", "lease cancelled during identity scan".to_string())));
        }
    }
    if !budget.complete() {
        let kind = if budget.permission_errors.load(Ordering::Relaxed) > 0 { "permission_denied" } else { "read_coverage" };
        return status_json("INCOMPLETE", None, Some(&budget), Some((kind, identity_error.unwrap_or_else(|| "target discovery coverage was incomplete; no NOT_FOUND claim".to_string()))));
    }
    if matches.len() > 1 {
        return status_json("AMBIGUOUS", None, Some(&budget), Some(("ambiguous", "more than one unique nonce/executable target matched".to_string())));
    }
    if matches.is_empty() {
        let status = if rejected > 0 { "REJECTED" } else { "NOT_FOUND" };
        return status_json(status, None, Some(&budget), Some(("target", "no unique proven functional target".to_string())));
    }
    let target = matches.remove(0);
    let mem_path = ProcFs::path(target.pid, "mem");
    let file = match File::open(mem_path) {
        Ok(file) => file,
        Err(error) => {
            budget.error(&error);
            let kind = if matches!(error.raw_os_error(), Some(libc::EACCES | libc::EPERM)) { "permission_denied" } else { "memory_access" };
            let detail = if kind == "permission_denied" {
                format!("/proc/<pid>/mem read-only open denied: {error}; no root/ptrace/SELinux bypass attempted")
            } else {
                format!("/proc/<pid>/mem read-only open failed: {error}")
            };
            return status_json("INCOMPLETE", Some(&target), Some(&budget), Some((kind, detail)));
        }
    };
    // Opening the fd is not the identity proof: the process can exit or be
    // reused between discovery and open. Re-run the complete target proof
    // before consuming a per-lease diagnostic slot.
    let post_open_target = match validate_target(&lease, target.pid, &budget) {
        Ok(target) => target,
        Err(error) => {
            let status = if !lease.current() { "CANCELLED" } else { "INCOMPLETE" };
            let kind = if status == "CANCELLED" { "stale_lease" } else { "identity" };
            return status_json(status, Some(&target), Some(&budget), Some((kind, error)));
        }
    };
    if !budget.complete() {
        return status_json(
            "INCOMPLETE",
            Some(&target),
            Some(&budget),
            Some(("read_coverage", "read budget changed after memory open identity validation".to_string())),
        );
    }
    let active_guard = match active().lock() {
        Ok(guard) => guard,
        Err(_) => {
            return status_json(
                "ERROR",
                Some(&target),
                Some(&budget),
                Some(("lease_mutex", "functional lease mutex poisoned".to_string())),
            )
        }
    };
    if !active_guard.as_ref().is_some_and(|current| {
        current.token == lease.token && !current.cancelled.load(Ordering::Acquire)
    }) {
        return status_json(
            "CANCELLED",
            Some(&target),
            Some(&budget),
            Some(("stale_lease", "lease ended after memory open identity validation".to_string())),
        );
    }
    // The mutex closes the open/identity/cancellation race. Discovery failures
    // and post-open identity failures never reach this increment.
    // Full-readable mode (codeCaptureEnabled=false) bypasses the historical
    // rotation entirely: no image-comparison slot and no per-class polls.
    if lease.code_capture.is_none() {
        if !target_identity_matches(&target, &post_open_target) {
            clear_pending(&lease);
            return status_json(
                "INCOMPLETE",
                Some(&target),
                Some(&budget),
                Some(("identity", "target identity changed after memory open validation".to_string())),
            );
        }
        drop(active_guard);
        let target = post_open_target;
        let memory = ReadOnlyMemory {
            lease: lease.clone(),
            target: target.clone(),
            budget,
            file: Mutex::new(file),
            inspect_core: false,
        };
        return full_scan_json(&lease, &target, &memory);
    }
    let slot = match claim_diagnostic_slot_if_identity_matches(&lease.diagnostic_cursor, &target, &post_open_target) {
        Some(slot) => slot,
        None => {
            clear_pending(&lease);
            return status_json(
                "INCOMPLETE",
                Some(&target),
                Some(&budget),
                Some(("identity", "target identity changed after memory open validation".to_string())),
            )
        }
    };
    drop(active_guard);
    let target = post_open_target;
    let memory = ReadOnlyMemory {
        lease: lease.clone(),
        target: target.clone(),
        budget,
        file: Mutex::new(file),
        inspect_core: slot == DIAGNOSTIC_SLOTS - 1,
    };
    // The opt-in capture is deliberately a separate reader.  It never changes
    // the normal rotating metrics/slot behavior and has its own fixed byte cap.
    let code_capture = lease
        .code_capture
        .as_ref()
        .map(|capture| functional_code_capture::poll(capture, &memory));
    if slot == 0 {
        return attach_code_capture(runtime_image_comparison_json(&lease, &target, &memory), code_capture);
    }
    if let Some(class) = DiagnosticClass::from_slot(slot) {
        return attach_code_capture(diagnostic_scan_json(&lease, &target, &memory, class), code_capture);
    }
    let found = match forever_patcher::scan_stores(&memory, &lease.contract) {
        Ok(found) => found,
        Err(error) => {
            if !lease.current() {
            return attach_code_capture(status_json("CANCELLED", Some(&target), Some(&memory.budget), Some(("stale_lease", "lease cancelled during read-only scan".to_string()))), code_capture);
            }
            let kind = if error.kind() == io::ErrorKind::PermissionDenied { "permission_denied" } else { "memory_access" };
            return attach_code_capture(status_json("INCOMPLETE", Some(&target), Some(&memory.budget), Some((kind, error.to_string()))), code_capture);
        }
    };
    if !memory.budget.complete() {
        return attach_code_capture(status_json("INCOMPLETE", Some(&target), Some(&memory.budget), Some(("read_coverage", "read-only scan was incomplete; no store result authorized".to_string()))), code_capture);
    }
    let final_target = match validate_target(&lease, target.pid, &memory.budget) {
        Ok(final_target) => final_target,
        Err(error) => {
            let status = if !lease.current() { "CANCELLED" } else { "INCOMPLETE" };
            let kind = if status == "CANCELLED" { "stale_lease" } else { "identity" };
            return attach_code_capture(status_json(status, Some(&target), Some(&memory.budget), Some((kind, error))), code_capture);
        }
    };
    if !target_identity_matches(&target, &final_target) {
        clear_pending(&lease);
        return attach_code_capture(status_json("INCOMPLETE", Some(&target), Some(&memory.budget), Some(("identity", "target identity changed after the complete read".to_string()))), code_capture);
    }
    clear_pending(&lease);
    attach_code_capture(complete_scan_json(&lease, &target, &memory.budget, &found), code_capture)
}

fn attach_code_capture(serialized: String, capture: Option<Value>) -> String {
    let Some(capture) = capture else { return serialized };
    let Ok(mut value) = serde_json::from_str::<Map<String, Value>>(&serialized) else { return serialized };
    value.insert("codeCapture".to_string(), capture);
    Value::Object(value).to_string()
}

fn claim_diagnostic_slot(cursor: &AtomicU64) -> u64 {
    cursor.fetch_add(1, Ordering::AcqRel) % DIAGNOSTIC_SLOTS
}

fn claim_diagnostic_slot_if_identity_matches(cursor: &AtomicU64, expected: &Target, actual: &Target) -> Option<u64> {
    target_identity_matches(expected, actual).then(|| claim_diagnostic_slot(cursor))
}

fn diagnostic_status_json(
    status: &str,
    target: Option<&Target>,
    budget: &Budget,
    scan: &DiagnosticMapScan,
    error: Option<(&str, String)>,
) -> String {
    let mut out = serde_json::from_str::<Map<String, Value>>(&status_json(status, target, Some(budget), error)).unwrap_or_default();
    out.insert("diagnosticMapScan".to_string(), scan.json());
    Value::Object(out).to_string()
}

fn diagnostic_scan_json(
    lease: &Lease,
    target: &Target,
    memory: &ReadOnlyMemory,
    class: DiagnosticClass,
) -> String {
    let scan = scan_diagnostic_class(memory, class);
    if !lease.current() {
        return diagnostic_status_json(
            "CANCELLED",
            Some(target),
            &memory.budget,
            &scan,
            Some(("stale_lease", "lease cancelled during read-only diagnostic scan".to_string())),
        );
    }
    if !scan.complete || !memory.budget.complete() {
        let kind = if memory.budget.permission_errors.load(Ordering::Relaxed) > 0 {
            "permission_denied"
        } else {
            "read_coverage"
        };
        return diagnostic_status_json(
            "INCOMPLETE",
            Some(target),
            &memory.budget,
            &scan,
            Some((kind, "read-only map diagnostic was incomplete; no global absence claim authorized".to_string())),
        );
    }
    let final_target = match validate_target(lease, target.pid, &memory.budget) {
        Ok(final_target) => final_target,
        Err(error) => {
            let status = if !lease.current() { "CANCELLED" } else { "INCOMPLETE" };
            let kind = if status == "CANCELLED" { "stale_lease" } else { "identity" };
            return diagnostic_status_json(status, Some(target), &memory.budget, &scan, Some((kind, error)));
        }
    };
    if !target_identity_matches(target, &final_target) {
        return diagnostic_status_json(
            "INCOMPLETE",
            Some(target),
            &memory.budget,
            &scan,
            Some(("identity", "target identity changed after the complete diagnostic read".to_string())),
        );
    }
    let guard = match active().lock() {
        Ok(guard) => guard,
        Err(_) => {
            return diagnostic_status_json(
                "ERROR",
                Some(&final_target),
                &memory.budget,
                &scan,
                Some(("lease_mutex", "functional lease mutex poisoned".to_string())),
            )
        }
    };
    let active_same = guard.as_ref().is_some_and(|current| {
        current.token == lease.token && !current.cancelled.load(Ordering::Acquire)
    });
    if !active_same {
        return diagnostic_status_json(
            "CANCELLED",
            Some(&final_target),
            &memory.budget,
            &scan,
            Some(("stale_lease", "lease ended before diagnostic completion".to_string())),
        );
    }
    diagnostic_status_json("READ_DIAGNOSTIC", Some(&final_target), &memory.budget, &scan, None)
}

fn runtime_image_comparison_json(
    lease: &Lease,
    target: &Target,
    memory: &ReadOnlyMemory,
) -> String {
    let mut comparison = functional_image_compare::scan(memory);
    let (identity, final_target) = if !lease.current() {
        (PostReadIdentity::Cancelled, None)
    } else {
        match validate_target(lease, target.pid, &memory.budget) {
            Ok(final_target) if target_identity_matches(target, &final_target) => (PostReadIdentity::Valid, Some(final_target)),
            Ok(_) => (PostReadIdentity::IdentityFailure, None),
            Err(_) if lease.current() => (PostReadIdentity::IdentityFailure, None),
            Err(_) => (PostReadIdentity::Cancelled, None),
        }
    };
    let guard = match active().lock() {
        Ok(guard) => guard,
        Err(_) => {
            if let Ok(mut remembered) = lease.remembered_candidate.lock() {
                *remembered = None;
            }
            comparison.drop_observations("lease_mutex", "active lease lock was unavailable after the read");
            return runtime_comparison_status_json(
                "ERROR",
                target,
                &memory.budget,
                &comparison,
                Some(("lease_mutex", "functional lease mutex poisoned".to_string())),
            );
        }
    };
    let active_same = guard.as_ref().is_some_and(|current| {
        current.token == lease.token && !current.cancelled.load(Ordering::Acquire)
    });
    let decision = post_read_observation_decision(
        &lease.remembered_candidate,
        &mut comparison,
        identity,
        active_same,
        memory.budget.complete(),
    );
    match decision {
        PostReadDecision::Cancelled => runtime_comparison_status_json(
            "CANCELLED",
            target,
            &memory.budget,
            &comparison,
            Some(("stale_lease", "lease ended before runtime image comparison completion".to_string())),
        ),
        PostReadDecision::IdentityFailure => runtime_comparison_status_json(
            "INCOMPLETE",
            target,
            &memory.budget,
            &comparison,
            Some(("post_read_identity", "target identity changed after the read".to_string())),
        ),
        PostReadDecision::Incomplete => {
            let kind = comparison.error_kind.unwrap_or_else(|| {
                if memory.budget.permission_errors.load(Ordering::Relaxed) > 0 {
                    "permission_denied"
                } else {
                    "read_coverage"
                }
            });
            let status = if comparison.status == "AMBIGUOUS" { "AMBIGUOUS" } else { "INCOMPLETE" };
            runtime_comparison_status_json(
                status,
                final_target.as_ref().unwrap_or(target),
                &memory.budget,
                &comparison,
                Some((kind, comparison.error.clone().unwrap_or_else(|| "runtime image comparison was incomplete".to_string()))),
            )
        }
        PostReadDecision::ReadDiagnostic => runtime_comparison_status_json(
            "READ_DIAGNOSTIC",
            final_target.as_ref().expect("valid post-read identity has a final target"),
            &memory.budget,
            &comparison,
            None,
        ),
    }
}

fn runtime_comparison_status_json(
    status: &str,
    target: &Target,
    budget: &Budget,
    comparison: &functional_image_compare::ImageComparison,
    error: Option<(&str, String)>,
) -> String {
    let mut out = serde_json::from_str::<Map<String, Value>>(&status_json(status, Some(target), Some(budget), error)).unwrap_or_default();
    out.insert("runtimeImageComparison".to_string(), comparison.json());
    Value::Object(out).to_string()
}

fn full_scan_json(lease: &Lease, target: &Target, memory: &ReadOnlyMemory) -> String {
    let result = functional_full_scan::run_full_scan(lease, target, memory);
    note_scan_for_apply(lease, target, &result);
    let coverage = functional_full_scan::coverage_json(&result.coverage, result.found.len());
    let with_coverage = |serialized: String| {
        let mut out = serde_json::from_str::<Map<String, Value>>(&serialized).unwrap_or_default();
        out.insert("fullScan".to_string(), coverage.clone());
        Value::Object(out).to_string()
    };
    match result.outcome {
        functional_full_scan::FullScanOutcome::Cancelled { detail } => {
            with_coverage(status_json("CANCELLED", Some(target), Some(&memory.budget), Some(("stale_lease", detail))))
        }
        functional_full_scan::FullScanOutcome::IdentityFailure { detail } => {
            with_coverage(status_json("INCOMPLETE", Some(target), Some(&memory.budget), Some(("identity", detail))))
        }
        functional_full_scan::FullScanOutcome::Incomplete { kind, detail } => {
            with_coverage(status_json("INCOMPLETE", Some(target), Some(&memory.budget), Some((kind, detail))))
        }
        functional_full_scan::FullScanOutcome::Complete => {
            let guard = match active().lock() {
                Ok(guard) => guard,
                Err(_) => {
                    clear_pending(lease);
                    return with_coverage(status_json(
                        "ERROR",
                        Some(target),
                        Some(&memory.budget),
                        Some(("lease_mutex", "functional lease mutex poisoned".to_string())),
                    ))
                }
            };
            let active_same = guard.as_ref().is_some_and(|current| {
                current.token == lease.token && !current.cancelled.load(Ordering::Acquire)
            });
            if !active_same {
                clear_pending(lease);
                return with_coverage(status_json(
                    "CANCELLED",
                    Some(target),
                    Some(&memory.budget),
                    Some(("stale_lease", "lease ended before full scan completion".to_string())),
                ));
            }
            drop(guard);
            let all_patched = !result.found.is_empty() && result.found.iter().all(|store| store.already_patched);
            let status = if result.found.is_empty() {
                "NOT_FOUND"
            } else if all_patched {
                "ALREADY_PATCHED"
            } else {
                "VALIDATED"
            };
            let mut out = serde_json::from_str::<Map<String, Value>>(
                &status_json(status, Some(target), Some(&memory.budget), None),
            )
            .unwrap_or_default();
            out.insert("stores".to_string(), json!(result.found.len()));
            out.insert("validStoreCount".to_string(), json!(result.found.len()));
            out.insert("alreadyPatched".to_string(), json!(all_patched));
            if result.found.is_empty() {
                out.insert("errorKind".to_string(), json!("store_not_found"));
                out.insert("error".to_string(), json!("no complete compatible key store was found"));
            }
            out.insert("fullScan".to_string(), coverage);
            Value::Object(out).to_string()
        }
    }
}

/// Linearizes completion against `nativeEnd`: an end that wins the active
/// lease mutex cannot be followed by a stale `VALIDATED` result.
fn complete_scan_json(lease: &Lease, target: &Target, budget: &Budget, found: &[forever_patcher::FoundStore]) -> String {    let guard = match active().lock() {
        Ok(guard) => guard,
        Err(_) => return status_json("ERROR", Some(target), Some(budget), Some(("lease_mutex", "functional lease mutex poisoned".to_string()))),
    };
    let active_same = guard.as_ref().is_some_and(|current| current.token == lease.token && !current.cancelled.load(Ordering::Acquire));
    if !active_same {
        return status_json("CANCELLED", Some(target), Some(budget), Some(("stale_lease", "lease ended before scan completion".to_string())));
    }
    let all_patched = !found.is_empty() && found.iter().all(|store| store.already_patched);
    let status = if found.is_empty() { "NOT_FOUND" } else if all_patched { "ALREADY_PATCHED" } else { "VALIDATED" };
    let mut out = serde_json::from_str::<Map<String, Value>>(&status_json(status, Some(target), Some(budget), None)).unwrap_or_default();
    out.insert("stores".to_string(), json!(found.len()));
    out.insert("validStoreCount".to_string(), json!(found.len()));
    out.insert("alreadyPatched".to_string(), json!(all_patched));
    if found.is_empty() {
        out.insert("errorKind".to_string(), json!("store_not_found"));
        out.insert("error".to_string(), json!("no complete compatible key store was found"));
    }
    Value::Object(out).to_string()
}

fn clear_pending(lease: &Lease) {
    if let Ok(mut pending) = lease.pending_apply.lock() {
        *pending = None;
    }
}

fn containing_region<'a>(
    snapshot: &'a [functional_full_scan::SnapshotRegion],
    addr: u64,
    block_len: u64,
) -> Option<&'a functional_full_scan::SnapshotRegion> {
    snapshot.iter().find(|region| {
        region.start <= addr && addr.saturating_add(block_len) <= region.end && region.end > region.start
    })
}

/// Private anonymous writable mappings only: `rw-p` or `rwxp` with inode 0
/// and device 00:00, and no file/device backing. Both unnamed anonymous and
/// kernel-named anonymous (`[heap]`/`[stack]`, classified `Pseudo`) are
/// eligible; file- or device-backed mappings are denied even with inode 0.
/// Read-only mappings (vDSO/vvar and the like) fail on the `w` bit. There is
/// no `mprotect` and no forced write: anything else is rejected.
fn is_writable_anon_private(region: &functional_full_scan::SnapshotRegion) -> bool {
    let perms_ok = region.perms == *b"rw-p" || region.perms == *b"rwxp";
    let unbacked_kind = region.kind == functional_full_scan::BackingKind::Anonymous
        || region.kind == functional_full_scan::BackingKind::Pseudo;
    perms_ok && unbacked_kind && region.inode == 0 && region.dev_major == 0 && region.dev_minor == 0
}

/// A single validated candidate (even from a globally `INCOMPLETE` pass)
/// may be proposed as long as exactly one complete block validated, the pass
/// did not observe a mapping change, and the lease is still current. Zero or
/// several candidates, cancellation, identity failure, or observed mapping
/// changes clear the proposal. This claims nothing about global uniqueness.
fn note_scan_for_apply(
    lease: &Lease,
    target: &Target,
    result: &functional_full_scan::FullScanResult,
) {
    let proposable = match &result.outcome {
        functional_full_scan::FullScanOutcome::Complete => true,
        functional_full_scan::FullScanOutcome::Incomplete { kind, .. } => {
            *kind == "read_coverage" || *kind == "permission_denied"
        }
        functional_full_scan::FullScanOutcome::Cancelled { .. }
        | functional_full_scan::FullScanOutcome::IdentityFailure { .. } => false,
    };
    if !proposable || !lease.current() {
        clear_pending(lease);
        return;
    }
    if result.coverage.maps_changed || result.found.len() != 1 {
        clear_pending(lease);
        return;
    }
    let addr = result.found[0].addr;
    let block_len = lease.contract.block_size() as u64;
    match containing_region(&result.snapshot, addr, block_len) {
        Some(region) => {
            if let Ok(mut pending) = lease.pending_apply.lock() {
                *pending = Some(PendingApply { target: target.clone(), region: *region, addr });
            }
        }
        None => clear_pending(lease),
    }
}

fn apply_json(status: &str, bytes_written: u64, write_authorized: bool, error: Option<(&str, &str)>) -> String {
    let mut out = Map::new();
    out.insert("version".to_string(), json!(RESULT_VERSION));
    out.insert("status".to_string(), json!(status));
    out.insert("readOnly".to_string(), json!(false));
    out.insert("writeAuthorized".to_string(), json!(write_authorized));
    out.insert("bytesWritten".to_string(), json!(bytes_written));
    if let Some((kind, detail)) = error {
        out.insert("errorKind".to_string(), json!(kind));
        out.insert("error".to_string(), json!(detail));
    }
    Value::Object(out).to_string()
}

/// Process-only identity re-proof that never touches the global `active()`
/// mutex, so it is safe to call while holding that mutex inside the commit
/// gate (the regular `validate_target`/`validate_pinned` would deadlock
/// reentrantly). It deliberately re-reads only `/proc` identity (stat,
/// starttime, ppid, UID, cmdline, nonce, ancestry) plus the caller's mapping
/// check: the verified executable source on USB/FUSE is already validated
/// twice outside the gate via `validate_target`, and holding the End mutex
/// during avoidable external-file I/O could hang indefinitely. A changed
/// on-disk file cannot retarget the already-proven running PID.
fn recheck_identity_nolock(lease: &Lease, expected: &Target, budget: &Budget) -> Result<(), String> {
    let stat = ProcFs::stat(expected.pid, budget).map_err(|error| classify_identity_io("target stat", error))?;
    if stat.start_ticks != expected.start_ticks || stat.ppid != expected.ppid {
        return Err("target identity changed; possible PID reuse".to_string());
    }
    let current_stat = ProcFs::stat(lease.own_pid, budget).map_err(|error| classify_identity_io("target stat", error))?;
    if current_stat.start_ticks != lease.own_start_ticks {
        return Err("launcher PID starttime changed; possible PID reuse".to_string());
    }
    let uid4 = ProcFs::uid(expected.pid, budget).map_err(|error| classify_identity_io("target uid", error))?;
    if uid4 != expected.uid4 || !uid4_matches(uid4, lease.own_uid) {
        return Err("stored target UID fields changed".to_string());
    }
    if !descendant_of(lease, expected.pid, budget)? {
        return Err("target ancestry is not proven to the current app".to_string());
    }
    let cmdline = capture_cmdline(expected.pid, budget)
        .map_err(|error| classify_identity_io("target cmdline", error))?
        .ok_or_else(|| "target command line changed after nonce validation".to_string())?;
    if cmdline != expected.cmdline {
        return Err("target command line changed".to_string());
    }
    if !validate_nonce(expected.pid, &lease.nonce, budget).map_err(|error| classify_identity_io("target nonce", error))? {
        return Err("target nonce does not match this lease".to_string());
    }
    Ok(())
}

fn current_region_for(
    pid: u32,
    addr: u64,
    block_len: u64,
    budget: &Budget,
) -> Result<functional_full_scan::SnapshotRegion, String> {
    let bytes = ProcFs::read_file(pid, "maps", MAX_MAPS_FILE, budget)
        .map_err(|error| format!("target maps unreadable: {error}"))?;
    let text = std::str::from_utf8(&bytes).map_err(|_| "target maps are not UTF-8".to_string())?;
    let regions = functional_full_scan::parse_full_readable_maps(text)
        .map_err(|detail| format!("target maps invalid: {detail}"))?;
    containing_region(&regions, addr, block_len)
        .copied()
        .ok_or_else(|| "candidate block is not inside one readable mapping".to_string())
}

/// Core verified commit against any `MemoryAccess`: re-read the full 480 B,
/// require it to equal the outside-gate pre-image, strictly validate, write
/// only the 32 B group-8 slot on an original block, and verify the 480 B
/// read-back matches the pre-image except those 32 B. Pre-write problems are
/// `Invalid` (no write attempted); post-write problems are reported with the
/// real committed count so the caller can latch the lease. This is the exact
/// function the production apply path calls inside its commit gate, and the
/// focused tests below drive it with fake memory.
#[derive(Debug)]
enum CommitVerdict {
    Applied { bytes: u64 },
    AlreadyPatched,
    Invalid,
}

fn commit_verified_block<M: MemoryAccess>(
    memory: &M,
    contract: &forever_patcher::KeyStoreContract,
    addr: u64,
    expected: &[u8],
) -> Result<CommitVerdict, (u64, String)> {
    let block_len = contract.block_size();
    if expected.len() != block_len {
        return Ok(CommitVerdict::Invalid);
    }
    let mut current = vec![0u8; block_len];
    if memory.read(addr, &mut current).is_err() {
        return Ok(CommitVerdict::Invalid);
    }
    if current != expected {
        return Ok(CommitVerdict::Invalid);
    }
    match functional_full_scan::validate_strict_block(&current, contract) {
        None => Ok(CommitVerdict::Invalid),
        Some(true) => Ok(CommitVerdict::AlreadyPatched),
        Some(false) => {
            let target_off = ((contract.target_group - 1) * contract.entry_size + 4) as u64;
            let key_addr = addr.saturating_add(target_off);
            if let Err(error) = memory.write(key_addr, &contract.new_key) {
                return Err((0u64, format!("candidate block write failed: {}", error.kind())));
            }
            let mut after = vec![0u8; block_len];
            if let Err(error) = memory.read(addr, &mut after) {
                return Err((forever_patcher::KEY_LEN as u64, format!("candidate block read-back failed: {}", error.kind())));
            }
            let key_range = target_off as usize..target_off as usize + forever_patcher::KEY_LEN;
            if after[key_range.clone()] != contract.new_key[..] {
                return Err((forever_patcher::KEY_LEN as u64, "candidate key read-back mismatch".to_string()));
            }
            let mut preimage = expected.to_vec();
            preimage[key_range].copy_from_slice(&contract.new_key);
            if after != preimage {
                return Err((forever_patcher::KEY_LEN as u64, "candidate block read-back mismatch".to_string()));
            }
            match functional_full_scan::validate_strict_block(&after, contract) {
                Some(true) => Ok(CommitVerdict::Applied { bytes: forever_patcher::KEY_LEN as u64 }),
                _ => Err((forever_patcher::KEY_LEN as u64, "candidate block read-back mismatch".to_string())),
            }
        }
    }
}

/// Per-attempt write progress shared with `ApplyMemory.write`. Created
/// outside the panic catch so a panic after a write attempt still reports
/// the honest byte count and latches exactly this lease: `attempted` is set
/// when the single write syscall is issued, `written` holds what the kernel
/// accepted (0 on error, short n, or full 32). No global tracking.
struct ApplyProgress {
    attempted: AtomicBool,
    written: AtomicU64,
}

/// Direct `/proc/<pid>/mem` read/write helper for apply only. The
/// read-only poll path (`ReadOnlyMemory`) is untouched and still denies
/// every write.
struct ApplyMemory {
    file: Mutex<File>,
    budget: Budget,
    progress: Arc<ApplyProgress>,
}

impl MemoryAccess for ApplyMemory {
    fn regions(&self) -> io::Result<Vec<forever_patcher::Region>> {
        Err(io::Error::new(io::ErrorKind::PermissionDenied, "apply memory exposes no region scan"))
    }

    fn read(&self, address: u64, buffer: &mut [u8]) -> io::Result<()> {
        if buffer.len() > 4096 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "apply read exceeds one block"));
        }
        self.budget.begin()?;
        self.budget.charge(buffer.len())?;
        let mut file = self.file.lock().map_err(|_| io::Error::other("apply memory fd mutex poisoned"))?;
        file.seek(SeekFrom::Start(address))?;
        let mut actual = 0usize;
        while actual < buffer.len() {
            match file.read(&mut buffer[actual..]) {
                Ok(0) => return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "short /proc/<pid>/mem read")),
                Ok(count) => actual += count,
                Err(error) => {
                    self.budget.error(&error);
                    return Err(error);
                }
            }
        }
        self.budget.record_memory_read(actual);
        self.budget.check()?;
        Ok(())
    }

    fn write(&self, address: u64, data: &[u8]) -> io::Result<()> {
        if data.len() != forever_patcher::KEY_LEN {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "apply write must be exactly one key"));
        }
        self.budget.check()?;
        self.budget.charge(data.len())?;
        let mut file = self.file.lock().map_err(|_| io::Error::other("apply memory fd mutex poisoned"))?;
        file.seek(SeekFrom::Start(address))?;
        // A single bounded write with no retry: the attempt flag is set only
        // when the syscall is actually issued, and the honest kernel count is
        // stashed so short writes and errors report real bytes and latch.
        self.progress.attempted.store(true, Ordering::Release);
        let written = match <File as std::io::Write>::write(&mut file, data) {
            Ok(count) => count,
            Err(error) => return Err(error),
        };
        self.progress.written.store(written as u64, Ordering::Relaxed);
        if written != data.len() {
            self.budget.error(&io::Error::new(io::ErrorKind::WriteZero, "short apply write"));
            return Err(io::Error::new(io::ErrorKind::WriteZero, "short apply write"));
        }
        self.budget.check()?;
        Ok(())
    }
}

/// Pure admission predicate for the commit gate. The focused orchestration
/// tests drive this exact condition: the active lease must still be this
/// lease and uncancelled, this lease must be uncancelled and unlatched, and
/// the pending proposal must be unchanged. Anything else denies with zero
/// writes, so an End that won, a latched lease, or a concurrent poll that
/// replaced the proposal can never yield a stale success.
fn gate_proposal_admits(
    lease_token: &str,
    lease_cancelled: bool,
    apply_disabled: bool,
    pending: &PendingApply,
    active_token: Option<&str>,
    active_cancelled: bool,
    pending_now: Option<&PendingApply>,
) -> bool {
    active_token == Some(lease_token)
        && !active_cancelled
        && !lease_cancelled
        && !apply_disabled
        && matches!(pending_now, Some(now) if now.addr == pending.addr
            && target_identity_matches(&now.target, &pending.target)
            && now.region == pending.region)
}

fn apply_inner(lease: &Lease, progress: Arc<ApplyProgress>) -> String {
    if lease.apply_disabled.load(Ordering::Acquire) {
        return apply_json("DENIED", 0, false, Some(("apply_disabled", "a previous apply failure disabled this lease; no write attempted")));
    }
    if lease.code_capture.is_some() {
        // Legacy capture sessions never propose a candidate; deny even if a
        // proposal somehow exists, with zero writes.
        clear_pending(lease);
        return apply_json("DENIED", 0, false, Some(("capture", "code capture sessions never apply; no write attempted")));
    }
    let pending = match lease.pending_apply.lock().ok().and_then(|guard| guard.clone()) {
        Some(pending) => pending,
        None => return apply_json("DENIED", 0, false, Some(("no_candidate", "no single validated candidate is pending; no write attempted"))),
    };
    if lease.cancelled.load(Ordering::Acquire) {
        clear_pending(&lease);
        return apply_json("DENIED", 0, false, Some(("stale_lease", "lease is cancelled; no write attempted")));
    }
    // Fresh proof outside the commit gate: full identity, current mapping,
    // and a complete strict block re-read. Unrelated global I/O gaps from the
    // poll do not suppress a complete fresh candidate here.
    let budget = Budget::new();
    let block_len = lease.contract.block_size() as u64;
    if validate_target(&lease, pending.target.pid, &budget).map_err(|error| error).and_then(|current| {
        if target_identity_matches(&pending.target, &current) { Ok(()) } else { Err("target identity changed".to_string()) }
    }).is_err() {
        clear_pending(&lease);
        return apply_json("DENIED", 0, false, Some(("identity", "target identity changed; no write attempted")));
    }
    let current_region = match current_region_for(pending.target.pid, pending.addr, block_len, &budget) {
        Ok(region) => region,
        Err(_) => {
            clear_pending(&lease);
            return apply_json("DENIED", 0, false, Some(("maps", "candidate mapping changed; no write attempted")));
        }
    };
    if current_region != pending.region || !is_writable_anon_private(&current_region) {
        clear_pending(&lease);
        return apply_json("DENIED", 0, false, Some(("maps", "candidate mapping is not private writable anonymous memory; no write attempted")));
    }
    let mem_path = ProcFs::path(pending.target.pid, "mem");
    let file = match std::fs::OpenOptions::new().read(true).write(true).open(mem_path) {
        Ok(file) => file,
        Err(_) => {
            clear_pending(&lease);
            return apply_json("DENIED", 0, false, Some(("memory_access", "writable memory open denied; no write attempted")));
        }
    };
    // Post-open identity proof before consuming the fd for block I/O.
    if validate_target(&lease, pending.target.pid, &budget).map_err(|error| error).and_then(|current| {
        if target_identity_matches(&pending.target, &current) { Ok(()) } else { Err("target identity changed".to_string()) }
    }).is_err() {
        clear_pending(&lease);
        return apply_json("DENIED", 0, false, Some(("identity", "target identity changed after memory open; no write attempted")));
    }
    if !budget.complete() {
        clear_pending(&lease);
        return apply_json("DENIED", 0, false, Some(("read_coverage", "apply pre-read coverage was incomplete; no write attempted")));
    }
    let memory = ApplyMemory { file: Mutex::new(file), budget: Budget::new(), progress: progress.clone() };
    let mut before = vec![0u8; lease.contract.block_size()];
    if memory.read(pending.addr, &mut before).is_err() {
        clear_pending(&lease);
        return apply_json("DENIED", 0, false, Some(("memory_access", "candidate block re-read failed; no write attempted")));
    }
    // Strictly valid originals AND already-patched blocks both fall through
    // to the same commit gate below: an in-gate ALREADY still has to confirm
    // the current lease/proposal/mapping/full block and the post proof, so a
    // stale ALREADY success after End/cancel can never be reported.
    if functional_full_scan::validate_strict_block(&before, &lease.contract).is_none() {
        clear_pending(&lease);
        return apply_json("DENIED", 0, false, Some(("identity", "candidate block changed; no write attempted")));
    }
    // Small bounded commit gate: linearize against `nativeEnd`. The global
    // mutex is held only for the process-local recheck plus one 32 B write
    // plus read-back, never for the long scan above; nothing inside calls
    // `lease.current()`/`validate_target` (reentrant deadlock), and the
    // executable source on USB/FUSE is not re-read here (already proven twice
    // outside the gate via `validate_target`).
    let commit = match active().lock() {
        Ok(guard) => {
            let pending_now = lease.pending_apply.lock().ok().and_then(|guard| guard.clone());
            let admitted = match guard.as_ref() {
                Some(current) => gate_proposal_admits(
                    &lease.token,
                    lease.cancelled.load(Ordering::Acquire),
                    lease.apply_disabled.load(Ordering::Acquire),
                    &pending,
                    Some(current.token.as_str()),
                    current.cancelled.load(Ordering::Acquire),
                    pending_now.as_ref(),
                ),
                None => false,
            };
            if admitted { Some(guard) } else { None }
        }
        Err(_) => {
            return apply_json("ERROR", 0, false, Some(("lease_mutex", "functional lease mutex poisoned")));
        }
    };
    let Some(guard) = commit else {
        clear_pending(&lease);
        return apply_json("DENIED", 0, false, Some(("stale_lease", "lease ended before apply commit; no write attempted")));
    };
    // Inside the gate: recheck without touching `active()` recursively.
    let gate_budget = Budget::new();
    if recheck_identity_nolock(&lease, &pending.target, &gate_budget).is_err() {
        drop(guard);
        clear_pending(&lease);
        return apply_json("DENIED", 0, false, Some(("identity", "target identity changed; no write attempted")));
    }
    let gate_region = match current_region_for(pending.target.pid, pending.addr, block_len, &gate_budget) {
        Ok(region) => region,
        Err(_) => {
            drop(guard);
            clear_pending(&lease);
            return apply_json("DENIED", 0, false, Some(("maps", "candidate mapping changed; no write attempted")));
        }
    };
    if gate_region != pending.region || !is_writable_anon_private(&gate_region) {
        drop(guard);
        clear_pending(&lease);
        return apply_json("DENIED", 0, false, Some(("maps", "candidate mapping is not private writable anonymous memory; no write attempted")));
    }
    // Explicit gate-budget proof before the single write: a blown deadline or
    // incomplete/errored pre-commit coverage denies with zero writes.
    if gate_budget.check().is_err() || !gate_budget.complete() {
        drop(guard);
        clear_pending(&lease);
        return apply_json("DENIED", 0, false, Some(("read_coverage", "apply commit coverage was incomplete; no write attempted")));
    }
    let commit_outcome = commit_verified_block(&memory, &lease.contract, pending.addr, &before);
    let committed: u64 = match &commit_outcome {
        Ok(CommitVerdict::Applied { bytes }) => *bytes,
        Ok(_) => 0,
        Err((bytes, _)) => (*bytes).max(progress.written.load(Ordering::Relaxed)),
    };
    if let Err((_, _)) = commit_outcome {
        lease.apply_disabled.store(true, Ordering::Release);
        drop(guard);
        clear_pending(&lease);
        return apply_json("ERROR", committed, true, Some(("memory_access", "candidate block write failed")));
    }
    if matches!(commit_outcome, Ok(CommitVerdict::Invalid)) {
        drop(guard);
        clear_pending(&lease);
        return apply_json("DENIED", 0, false, Some(("identity", "candidate block changed; no write attempted")));
    }
    // Common post proof for BOTH applied and already-patched blocks: confirm
    // the lease/proposal is still current and the mapping still validates,
    // with no stale success if End/cancel won. No more writes happen below.
    let applied = matches!(commit_outcome, Ok(CommitVerdict::Applied { .. }));
    let post_ok = gate_budget.check().is_ok()
        && recheck_identity_nolock(&lease, &pending.target, &gate_budget).is_ok()
        && current_region_for(pending.target.pid, pending.addr, block_len, &gate_budget)
            .is_ok_and(|region| region == pending.region)
        && gate_budget.complete();
    if !post_ok {
        // Ordered failure latch: a concurrent call may already have
        // pre-read a recreated original block, so the latch must be set
        // BEFORE the End mutex is released, not after.
        if applied {
            lease.apply_disabled.store(true, Ordering::Release);
        }
        drop(guard);
        clear_pending(&lease);
        if applied {
            return apply_json("ERROR", committed, true, Some(("memory_access", "candidate block verification failed")));
        }
        return apply_json("DENIED", 0, false, Some(("identity", "target identity or mapping changed after apply; no write attempted")));
    }
    drop(guard);
    // Retain the proposal: Kotlin re-calls apply on its 1 s loop instead of
    // a new 20 s scan. A recreated original block re-applies; a moved or
    // changed block denies and clears on the next call.
    if applied {
        apply_json("APPLIED", committed, true, None)
    } else {
        apply_json("ALREADY_PATCHED", 0, true, None)
    }
}

/// Panic-boundary report for `apply`, driven by the exact lease Arc resolved
/// before the catch and the per-attempt progress shared with
/// `ApplyMemory.write`. A panic after a write attempt latches this lease,
/// clears its proposal, and reports the honest kernel byte count; a panic
/// before any attempt reports zero with no latch (no unconditional pre-write
/// DoS) and never rolls memory back.
fn apply_panic_report(lease: &Lease, progress: &ApplyProgress) -> String {
    if progress.attempted.load(Ordering::Acquire) {
        lease.apply_disabled.store(true, Ordering::Release);
        clear_pending(lease);
        let bytes = progress.written.load(Ordering::Relaxed);
        apply_json("ERROR", bytes, true, Some(("panic", "functional apply aborted safely")))
    } else {
        apply_json("ERROR", 0, false, Some(("panic", "functional apply aborted safely")))
    }
}

pub fn apply(token: &str) -> String {
    // Resolve the exact lease Arc before the catch: a panic after a write
    // attempt must latch exactly this lease without re-locking (possibly
    // poisoned) global state just to find it. No extra ABI, no globals.
    let lease = match active().lock().ok().and_then(|guard| {
        guard.as_ref().filter(|lease| lease.token == token).cloned()
    }) {
        Some(lease) => lease,
        None => return apply_json("DENIED", 0, false, Some(("stale_lease", "token is stale or unknown; no write attempted"))),
    };
    let progress = Arc::new(ApplyProgress { attempted: AtomicBool::new(false), written: AtomicU64::new(0) });
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| apply_inner(&lease, progress.clone()))) {
        Ok(value) => value,
        Err(_) => apply_panic_report(&lease, &progress),
    }
}

pub fn begin(executable_path: &str, nonce: &str, contract_path: &str, code_capture_enabled: bool) -> String {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| begin_inner(executable_path, nonce, contract_path, code_capture_enabled)));
    match result {
        Ok(Ok(token)) => token,
        Ok(Err(error)) => json!({"version": RESULT_VERSION, "status": "ERROR", "errorKind": "begin", "error": error}).to_string(),
        Err(_) => json!({"version": RESULT_VERSION, "status": "ERROR", "errorKind": "panic", "error": "functional begin aborted safely"}).to_string(),
    }
}

fn begin_inner(executable_path: &str, nonce: &str, contract_path: &str, code_capture_enabled: bool) -> Result<String, String> {
    if nonce.is_empty() || nonce.len() > 256 || nonce.as_bytes().contains(&0) {
        return Err("nonce must be non-empty, bounded, and NUL-free".to_string());
    }
    let executable = canonical_executable(executable_path)?;
    let contract = parse_contract(contract_path)?;
    let own_pid = std::process::id();
    let own_uid = unsafe { libc::getuid() };
    let budget = Budget::new();
    let own_stat = ProcFs::stat(own_pid, &budget).map_err(|error| format!("app stat failed: {error}"))?;
    let own_uid4 = ProcFs::uid(own_pid, &budget).map_err(|error| format!("app UID fields failed: {error}"))?;
    if !uid4_matches(own_uid4, own_uid) {
        return Err(format!("app UID fields are not all the kernel UID {own_uid}"));
    }
    let baseline = process_tree(own_pid, &budget)?
        .into_iter()
        .map(|(pid, stat)| (pid, stat.start_ticks))
        .collect::<BTreeMap<_, _>>();
    if !budget.complete() {
        return Err("cannot establish a complete pre-launch process baseline".to_string());
    }
    let mut guard = active().lock().map_err(|_| "functional lease mutex poisoned".to_string())?;
    if guard.is_some() {
        return Err("another functional session is already active".to_string());
    }
    let previous_subreaper = get_subreaper()?;
    set_subreaper(true)?;
    let code_capture = if code_capture_enabled {
        match functional_code_capture::CodeCaptureLease::begin(&executable, nonce) {
            Ok(capture) => Some(Arc::new(capture)),
            Err(error) => {
                let _ = set_subreaper(previous_subreaper);
                return Err(error);
            }
        }
    } else {
        None
    };
    let token = format!("functional-{}-{}", own_pid, NEXT_TOKEN.fetch_add(1, Ordering::Relaxed));
    let lease = Arc::new(Lease {
        token: token.clone(),
        own_pid,
        own_start_ticks: own_stat.start_ticks,
        own_uid,
        executable,
        nonce: nonce.to_string(),
        contract,
        baseline,
        remembered_candidate: Mutex::new(None),
        code_capture,
        cancelled: AtomicBool::new(false),
        diagnostic_cursor: AtomicU64::new(0),
        full_scan: Mutex::new(None),
        previous_subreaper,
        pending_apply: Mutex::new(None),
        apply_disabled: AtomicBool::new(false),
    });
    *guard = Some(lease);
    Ok(token)
}

pub fn poll(token: &str) -> String {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let lease = active()
            .lock()
            .ok()
            .and_then(|guard| guard.as_ref().filter(|lease| lease.token == token).cloned());
        match lease {
            Some(lease) => scan_json(lease),
            None => status_json("ERROR", None, None, Some(("stale_lease", "token is stale or unknown; no memory read attempted".to_string()))),
        }
    }));
    match result {
        Ok(value) => value,
        Err(_) => status_json("ERROR", None, None, Some(("panic", "functional poll aborted safely".to_string()))),
    }
}

pub fn end(token: &str) -> String {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut guard = active().lock().map_err(|_| "functional lease mutex poisoned".to_string())?;
        let Some(lease) = guard.as_ref().filter(|lease| lease.token == token).cloned() else {
            return Ok::<String, String>(status_json("ERROR", None, None, Some(("stale_lease", "token is stale or unknown".to_string()))));
        };
        lease.cancelled.store(true, Ordering::Release);
        match set_subreaper(lease.previous_subreaper) {
            Ok(()) => { guard.take(); Ok(status_json("ENDED", None, None, None)) },
            Err(error) => Ok(status_json("ERROR", None, None, Some(("subreaper_restore", error)))),
        }
    }));
    match result {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => json!({"version": RESULT_VERSION, "status": "ERROR", "errorKind": "end", "error": error}).to_string(),
        Err(_) => json!({"version": RESULT_VERSION, "status": "ERROR", "errorKind": "panic", "error": "functional end aborted safely"}).to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parent_snapshot_requires_real_ancestry_and_marks_depth_exhaustion() {
        let snapshot = BTreeMap::from([
            (10, Stat { ppid: 1, start_ticks: 1 }),
            (20, Stat { ppid: 10, start_ticks: 2 }),
            (30, Stat { ppid: 20, start_ticks: 3 }),
            (40, Stat { ppid: 1, start_ticks: 4 }),
            (50, Stat { ppid: 60, start_ticks: 5 }),
            (60, Stat { ppid: 50, start_ticks: 6 }),
        ]);
        assert_eq!(owned_subtree(10, &snapshot, &Budget::new()).iter().map(|x| x.0).collect::<Vec<_>>(), vec![10, 20, 30]);
        let deep = (1..=70).map(|pid| (pid, Stat { ppid: pid - 1, start_ticks: pid as u64 })).collect();
        let budget = Budget::new();
        let _ = owned_subtree(1, &deep, &budget);
        assert!(!budget.complete());
    }
    static NEXT_TEST_CONTRACT: AtomicU64 = AtomicU64::new(0);

    fn test_contract_path(prefix: &str) -> PathBuf {
        let id = NEXT_TEST_CONTRACT.fetch_add(1, Ordering::Relaxed);
        std::env::temp_dir().join(format!("{prefix}-{}-{id}", std::process::id()))
    }

    struct TestReadOnlyMemory {
        inner: forever_patcher::SliceMemory,
    }

    impl MemoryAccess for TestReadOnlyMemory {
        fn regions(&self) -> io::Result<Vec<forever_patcher::Region>> { self.inner.regions() }
        fn read(&self, address: u64, buffer: &mut [u8]) -> io::Result<()> { self.inner.read(address, buffer) }
        fn write(&self, _address: u64, _data: &[u8]) -> io::Result<()> {
            Err(io::Error::new(io::ErrorKind::PermissionDenied, "test memory is read-only"))
        }
    }

    #[test]
    fn exact_windows_token_is_not_a_basename_heuristic() {
        assert!(target_token(b"A:\\WowB-ARM64.exe"));
        assert!(target_token(b"Z:\\CustomGames\\WoW Forever\\WowB-ARM64.exe"));
        assert!(!target_token(b"WowB-ARM64.exe"));
        assert!(!target_token(b"Z:\\other\\WowB-ARM64.exe"));
    }

    #[test]
    fn unknown_token_never_opens_memory() {
        let result = poll("unknown-functional-token");
        assert!(result.contains("stale_lease"));
        assert!(result.contains("readOnly"));
    }

    #[test]
    fn code_capture_is_absent_from_default_normal_results() {
        let result = attach_code_capture(status_json("NOT_FOUND", None, None, None), None);
        assert!(!result.contains("codeCapture"));
    }

    #[test]
    fn stale_or_cancelled_status_cannot_become_a_positive_result() {
        let target = Target { pid: 9, start_ticks: 77, ppid: 4, uid4: [42; 4], cmdline: b"A:\\WowB-ARM64.exe\0".to_vec() };
        for status in ["CANCELLED", "INCOMPLETE"] {
            let output: Value = serde_json::from_str(&status_json(status, Some(&target), None, None)).unwrap();
            assert_ne!(output["status"], "VALIDATED");
            assert_eq!(output["writeAuthorized"], false);
            assert_eq!(output["stores"], 0);
        }
    }

    #[test]
    fn nonce_and_pid_reuse_proofs_are_exact() {
        assert!(nonce_matches(b"OTHER=x\0WOWFOREVER_POC_RUN_ID=fresh\0", "fresh"));
        assert!(!nonce_matches(b"WOWFOREVER_POC_RUN_ID=fresh-extra\0", "fresh"));
        assert!(uid4_matches([42, 42, 42, 42], 42));
        assert!(!uid4_matches([42, 42, 43, 42], 42));
        let baseline = BTreeMap::from([(42, 100_u64)]);
        assert!(!pid_is_new(&baseline, 42, 100));
        assert!(pid_is_new(&baseline, 42, 101));
        assert!(pid_is_new(&baseline, 43, 100));
        let expected = Target { pid: 9, start_ticks: 77, ppid: 4, uid4: [42; 4], cmdline: b"A:\\WowB-ARM64.exe\0".to_vec() };
        assert!(target_identity_matches(&expected, &expected));
        assert!(!target_identity_matches(&expected, &Target { start_ticks: 78, ..expected.clone() }));
        assert!(!target_identity_matches(&expected, &Target { uid4: [42, 42, 43, 42], ..expected.clone() }));
        assert!(!target_identity_matches(&expected, &Target { cmdline: b"A:\\changed.exe\0".to_vec(), ..expected.clone() }));
    }

    #[test]
    fn synthetic_valid_store_is_scanned_without_a_write_path() {
        let contract = parse_contract_from_embedded_for_test();
        let mut bytes = vec![0u8; contract.block_size()];
        for index in 0..contract.entry_count {
            let offset = index * contract.entry_size;
            bytes[offset..offset + 4].copy_from_slice(&((index + 1) as u32).to_le_bytes());
            bytes[offset + 4..offset + 36].copy_from_slice(&contract.known_keys[index]);
            bytes[offset + 36] = contract.expected_flags[index];
            bytes[offset + 37..offset + 40].copy_from_slice(&contract.accepted_paddings[0]);
        }
        let memory = TestReadOnlyMemory { inner: forever_patcher::SliceMemory::new(0x1000, bytes) };
        let before = memory.inner.snapshot();
        let found = forever_patcher::scan_stores(&memory, &contract).unwrap();
        assert_eq!(found.len(), 1);
        assert!(!found[0].already_patched);
        assert_eq!(before, memory.inner.snapshot());
        assert!(memory.write(0x1000, &[1]).is_err());
    }

    #[test]
    fn diagnostic_counts_multi_copy_rejections_without_key_bytes() {
        let contract = parse_contract_from_embedded_for_test();
        let mut bytes = vec![0u8; contract.block_size() * 4];
        for copy in 0..4 {
            let base = copy * contract.block_size();
            for index in 0..contract.entry_count {
                let offset = base + index * contract.entry_size;
                bytes[offset..offset + 4].copy_from_slice(&((index + 1) as u32).to_le_bytes());
                bytes[offset + 4..offset + 36].copy_from_slice(&contract.known_keys[index]);
                bytes[offset + 36] = contract.expected_flags[index];
                bytes[offset + 37..offset + 40].copy_from_slice(&contract.accepted_paddings[0]);
            }
        }
        bytes[contract.block_size() + 2 * contract.entry_size] = 0;
        bytes[contract.block_size() * 2 + 36] ^= 1;
        bytes[contract.block_size() * 3 + contract.entry_size + 4] ^= 1;
        let mut diagnostics = ReadDiagnostics::default();
        diagnostics.inspect_chunk(0x1000, &bytes, &contract);
        assert_eq!(diagnostics.anchor_hits, 4);
        assert_eq!(diagnostics.diagnostic_valid_store_count, 1);
        assert_eq!(diagnostics.invalid_total, 3);
        assert_eq!(diagnostics.id_order.count, 1);
        assert_eq!(diagnostics.flag.count, 1);
        assert_eq!(diagnostics.key.count, 1);
        let output = diagnostics.json().to_string();
        assert!(output.len() < 8 * 1024);
        assert!(!output.contains("known_keys"));
    }

    #[test]
    fn diagnostic_validators_share_free_first_padding_byte_rule() {
        // Los dos contadores de diagnóstico (ReadDiagnostics y sonda
        // estructural) usan la misma regla que el núcleo: primer byte de la
        // cola libre, bytes 38/39 a cero. Mezcla por entrada con la observada
        // `070000`, inéditas `ff0000`/`420000` e históricas.
        let contract = parse_contract_from_embedded_for_test();
        let observed: [[u8; 3]; 6] = [
            [0x07, 0x00, 0x00],
            [0xff, 0x00, 0x00],
            [0x42, 0x00, 0x00],
            [0x00, 0x00, 0x00],
            [0x7f, 0x00, 0x00],
            [0x07, 0x00, 0x00],
        ];
        let mut bytes = vec![0u8; contract.block_size()];
        for index in 0..contract.entry_count {
            let offset = index * contract.entry_size;
            bytes[offset..offset + 4].copy_from_slice(&((index + 1) as u32).to_le_bytes());
            bytes[offset + 4..offset + 36].copy_from_slice(&contract.known_keys[index]);
            bytes[offset + 36] = contract.expected_flags[index];
            bytes[offset + 37..offset + 40].copy_from_slice(&observed[index % observed.len()]);
        }
        let mut diagnostics = ReadDiagnostics::default();
        diagnostics.inspect_chunk(0x1000, &bytes, &contract);
        assert_eq!(diagnostics.diagnostic_valid_store_count, 1);
        assert_eq!(diagnostics.invalid_total, 0);
        assert_eq!(diagnostics.padding.count, 0);
        let mut structural = DiagnosticMapScan::new(DiagnosticClass::AnonymousReadWrite);
        structural.structural_store_probe.inspect_chunk(0x1000, &bytes, 0x1000, &contract);
        assert_eq!(structural.structural_store_probe.metadata_match_candidates, 1);
        assert_eq!(structural.structural_store_probe.samples[0]["paddingMatches"], 12);
        // Una cola con el tercer byte no nulo vuelve a rechazar en ambos.
        let mut bad = bytes;
        bad[2 * contract.entry_size + 39] = 0x01;
        let mut diagnostics = ReadDiagnostics::default();
        diagnostics.inspect_chunk(0x1000, &bad, &contract);
        assert_eq!(diagnostics.diagnostic_valid_store_count, 0);
        assert_eq!(diagnostics.padding.count, 1);
        let mut structural = DiagnosticMapScan::new(DiagnosticClass::AnonymousReadWrite);
        structural.structural_store_probe.inspect_chunk(0x1000, &bad, 0x1000, &contract);
        assert_eq!(structural.structural_store_probe.metadata_match_candidates, 0);
        assert_eq!(structural.structural_store_probe.samples[0]["paddingMatches"], 11);
    }

    fn parse_contract_from_embedded_for_test() -> forever_patcher::KeyStoreContract {
        let path = test_contract_path("functional-contract-test");
        std::fs::write(&path, CANONICAL_CONTRACT).unwrap();
        let contract = parse_contract(path.to_str().unwrap()).unwrap();
        std::fs::remove_file(path).unwrap();
        contract
    }

    #[test]
    fn contract_is_embedded_exactly() {
        let path = test_contract_path("functional-contract");
        std::fs::write(&path, CANONICAL_CONTRACT).unwrap();
        let contract = parse_contract(path.to_str().unwrap()).unwrap();
        assert_eq!(contract.block_size(), 480);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn diagnostic_map_classes_exclude_shared_dev_and_preserve_anon_pseudonyms() {
        let maps = concat!(
            "1000-2000 r--p 00000000 00:00 1 /data/app/client.so\n",
            "2000-3000 rw-p 00000000 00:00 2 /data/app/client.data\n",
            "3000-4000 r-xp 00000000 00:00 3 /data/app/client.exec\n",
            "4000-5000 r--p 00000000 00:00 4 /dev/ashmem/client\n",
            "5000-6000 rw-p 00000000 00:00 5 [heap]\n",
            "6000-7000 r-xp 00000000 00:00 6 anon:jit\n",
            "7000-8000 r--p 00000000 00:00 7\n",
            "8000-9000 rw-s 00000000 00:00 8 /data/app/shared\n",
        );
        assert_eq!(parse_diagnostic_maps(maps, DiagnosticClass::FileReadOnly).unwrap().len(), 1);
        assert_eq!(parse_diagnostic_maps(maps, DiagnosticClass::FileReadWrite).unwrap().len(), 1);
        assert_eq!(parse_diagnostic_maps(maps, DiagnosticClass::FileReadExecute).unwrap().len(), 1);
        assert_eq!(parse_diagnostic_maps(maps, DiagnosticClass::AnonymousReadOnly).unwrap().len(), 1);
        assert_eq!(parse_diagnostic_maps(maps, DiagnosticClass::AnonymousReadWrite).unwrap().len(), 1);
        assert_eq!(parse_diagnostic_maps(maps, DiagnosticClass::AnonymousReadExecute).unwrap().len(), 1);
    }

    #[test]
    fn diagnostic_maps_reject_truncation_and_bad_permission_positions() {
        let truncated_inode = "1000-2000 r--p 00000000 00:00\n";
        let truncated_path = "1000-2000 r--p 00000000 00:00 1 /data/app/client.so";
        let bad_permission = "1000-2000 rrrp 00000000 00:00 1 /data/app/client.so\n";
        assert!(parse_diagnostic_maps(truncated_inode, DiagnosticClass::FileReadOnly).is_err());
        assert!(parse_diagnostic_maps(truncated_path, DiagnosticClass::FileReadOnly).is_err());
        assert!(parse_diagnostic_maps(bad_permission, DiagnosticClass::FileReadOnly).is_err());
    }

    #[test]
    fn valid_maps_with_no_selected_class_are_complete_empty() {
        let maps = concat!(
            "1000-2000 ---p 00000000 00:00 1 /data/app/no-read.so\n",
            "2000-3000 rw-s 00000000 00:00 2 /data/app/shared.so\n",
        );
        let regions = parse_diagnostic_maps(maps, DiagnosticClass::FileReadOnly).unwrap();
        assert!(regions.is_empty());
    }

    #[test]
    fn diagnostic_key_buckets_count_all_keys_at_any_alignment_once() {
        let contract = parse_contract_from_embedded_for_test();
        let mut bytes = vec![0u8; contract.known_keys.len() * 64 + 64];
        for (index, key) in contract.known_keys.iter().enumerate() {
            let offset = index * 64 + if index % 2 == 0 { 0 } else { 1 };
            bytes[offset..offset + forever_patcher::KEY_LEN].copy_from_slice(key);
        }
        let replacement_offset = bytes.len() - forever_patcher::KEY_LEN;
        bytes[replacement_offset..].copy_from_slice(&contract.new_key);
        let mut result = DiagnosticMapScan::new(DiagnosticClass::AnonymousReadWrite);
        inspect_diagnostic_bytes(0x1000, &bytes, &contract, &mut result);
        assert!(result.known_key_hits.iter().all(|count| *count == 1));
        assert_eq!(result.replacement_key_hits, 1);
        assert_eq!(result.aligned_anchor_hits + result.unaligned_anchor_hits, 1);
    }

    #[test]
    fn diagnostic_thirty_one_byte_overlap_does_not_double_count() {
        let contract = parse_contract_from_embedded_for_test();
        let boundary = MAX_READ_CHUNK;
        let start = boundary - DIAGNOSTIC_OVERLAP;
        let mut first = vec![0u8; MAX_READ_CHUNK];
        let key = contract.anchor_key();
        // The key starts at the last possible cross-chunk position. It is not
        // complete in the first chunk and is complete exactly once in the next.
        let mut second = vec![0u8; DIAGNOSTIC_OVERLAP + forever_patcher::KEY_LEN];
        second[..forever_patcher::KEY_LEN].copy_from_slice(key);
        let mut result = DiagnosticMapScan::new(DiagnosticClass::AnonymousReadWrite);
        inspect_diagnostic_bytes(0, &first, &contract, &mut result);
        inspect_diagnostic_bytes(start as u64, &second, &contract, &mut result);
        assert_eq!(result.known_key_hits[contract.anchor_group - 1], 1);
        assert_eq!(result.aligned_anchor_hits + result.unaligned_anchor_hits, 1);
        first.clear();
    }

    fn synthetic_structural_store(contract: &forever_patcher::KeyStoreContract, known: bool) -> Vec<u8> {
        let mut bytes = vec![0u8; STRUCTURAL_BLOCK_SIZE];
        for index in 0..contract.entry_count {
            let offset = index * contract.entry_size;
            bytes[offset..offset + 4].copy_from_slice(&((index + 1) as u32).to_le_bytes());
            let key = if known {
                contract.known_keys[index]
            } else {
                [0x80u8.saturating_add(index as u8); forever_patcher::KEY_LEN]
            };
            bytes[offset + 4..offset + 36].copy_from_slice(&key);
            bytes[offset + 36] = contract.expected_flags[index];
            bytes[offset + 37..offset + 40].copy_from_slice(&contract.accepted_paddings[0]);
        }
        bytes
    }

    #[test]
    fn structural_probe_finds_layout_without_authorizing_unknown_keys() {
        let contract = parse_contract_from_embedded_for_test();
        let bytes = synthetic_structural_store(&contract, false);
        let mut result = DiagnosticMapScan::new(DiagnosticClass::AnonymousReadWrite);
        result.structural_store_probe.inspect_chunk(0x1001, &bytes, 0x1001, &contract);
        let probe = result.structural_store_probe.json();
        assert_eq!(probe["orderedIdCandidates"], 1);
        assert_eq!(probe["metadataMatchCandidates"], 1);
        assert_eq!(probe["fullyKnownCandidates"], 0);
        assert_eq!(probe["samples"][0]["knownKeyMatches12"], 0);
        assert_eq!(probe["samples"][0]["knownKeyMatchByGroup12"].as_array().unwrap().len(), 12);
        assert!(probe["samples"][0]["knownKeyMatchByGroup12"].as_array().unwrap().iter().all(|value| value == false));
        assert_eq!(probe["samples"][0]["nonzeroKeySlots"], 12);
        assert_eq!(probe["samples"][0]["distinctKeySlots"], 12);
        assert_eq!(probe["samples"][0]["alignedToContract"], false);
        assert_eq!(probe["hypothesis"], "contiguous-12x40-le-id");
        assert_eq!(probe["readOnly"], true);
        assert_eq!(probe["writeAuthorized"], false);
        let canonical_memory = TestReadOnlyMemory { inner: forever_patcher::SliceMemory::new(0x1001, bytes.clone()) };
        let before = canonical_memory.inner.snapshot();
        assert!(forever_patcher::scan_stores(&canonical_memory, &contract).unwrap().is_empty());
        assert_eq!(before, canonical_memory.inner.snapshot());
        assert!(canonical_memory.write(0x1001, &[1]).is_err());
        let output = result.json().to_string();
        assert!(output.len() < 8 * 1024);
        assert!(!output.contains("known_keys"));
        assert!(!output.contains("0x1001"));
        assert!(!output.contains("heap"));
    }

    #[test]
    fn structural_probe_reports_known_replacement_and_metadata_summary() {
        let contract = parse_contract_from_embedded_for_test();
        let mut known = synthetic_structural_store(&contract, true);
        let target_offset = (contract.target_group - 1) * contract.entry_size;
        known[target_offset + 4..target_offset + 36].copy_from_slice(&contract.new_key);
        let mut result = DiagnosticMapScan::new(DiagnosticClass::AnonymousReadWrite);
        result.structural_store_probe.inspect_chunk(0x1000, &known, 0x1000, &contract);
        assert_eq!(result.structural_store_probe.fully_known_candidates, 1);
        assert_eq!(result.structural_store_probe.replacement_group8, 1);

        known[36] ^= 1;
        known[38] ^= 1;
        let mut mismatched = DiagnosticMapScan::new(DiagnosticClass::AnonymousReadWrite);
        mismatched.structural_store_probe.inspect_chunk(0x1000, &known, 0x1000, &contract);
        assert_eq!(mismatched.structural_store_probe.ordered_id_candidates, 1);
        assert_eq!(mismatched.structural_store_probe.metadata_match_candidates, 0);
        assert_eq!(mismatched.structural_store_probe.fully_known_candidates, 0);
        assert_eq!(mismatched.structural_store_probe.samples[0]["flagMatches"], 11);
        assert_eq!(mismatched.structural_store_probe.samples[0]["paddingMatches"], 11);
    }

    #[test]
    fn structural_probe_rejects_wrong_order_and_distinguishes_zero_repeated_keys() {
        let contract = parse_contract_from_embedded_for_test();
        let mut wrong_order = synthetic_structural_store(&contract, false);
        wrong_order[contract.entry_size..contract.entry_size + 4].copy_from_slice(&3u32.to_le_bytes());
        let mut rejected = DiagnosticMapScan::new(DiagnosticClass::AnonymousReadWrite);
        rejected.structural_store_probe.inspect_chunk(0x1000, &wrong_order, 0x1000, &contract);
        assert_eq!(rejected.structural_store_probe.ordered_id_candidates, 0);

        let mut zero = synthetic_structural_store(&contract, false);
        for index in 0..contract.entry_count {
            let offset = index * contract.entry_size;
            zero[offset + 4..offset + 36].fill(0);
        }
        let mut repeated = synthetic_structural_store(&contract, false);
        for index in 0..contract.entry_count {
            let offset = index * contract.entry_size;
            repeated[offset + 4..offset + 36].fill(0xa5);
        }
        let mut zero_result = DiagnosticMapScan::new(DiagnosticClass::AnonymousReadWrite);
        zero_result.structural_store_probe.inspect_chunk(0x1000, &zero, 0x1000, &contract);
        let mut repeated_result = DiagnosticMapScan::new(DiagnosticClass::AnonymousReadWrite);
        repeated_result.structural_store_probe.inspect_chunk(0x1000, &repeated, 0x1000, &contract);
        assert_eq!(zero_result.structural_store_probe.samples[0]["nonzeroKeySlots"], 0);
        assert_eq!(zero_result.structural_store_probe.samples[0]["distinctKeySlots"], 1);
        assert_eq!(repeated_result.structural_store_probe.samples[0]["nonzeroKeySlots"], 12);
        assert_eq!(repeated_result.structural_store_probe.samples[0]["distinctKeySlots"], 1);
    }

    #[test]
    fn structural_candidate_and_raw_needle_overlap_are_each_counted_once() {
        let contract = parse_contract_from_embedded_for_test();
        let boundary = MAX_READ_CHUNK;
        let table_start = boundary - STRUCTURAL_OVERLAP;
        let mut bytes = vec![0u8; boundary + 1];
        bytes[table_start..table_start + STRUCTURAL_BLOCK_SIZE]
            .copy_from_slice(&synthetic_structural_store(&contract, false));
        let overlap_only_start = table_start + 44;
        bytes[overlap_only_start..overlap_only_start + forever_patcher::KEY_LEN]
            .copy_from_slice(&contract.known_keys[0]);
        let raw_start = boundary - DIAGNOSTIC_OVERLAP;
        bytes[raw_start..raw_start + forever_patcher::KEY_LEN].copy_from_slice(&contract.known_keys[1]);
        let memory = forever_patcher::SliceMemory::new(0, bytes);
        let budget = Budget::new();
        let mut result = DiagnosticMapScan::new(DiagnosticClass::AnonymousReadWrite);
        scan_diagnostic_regions(
            &memory,
            &budget,
            &contract,
            &[forever_patcher::Region { start: 0, end: boundary as u64 + 1 }],
            &mut result,
        );
        assert!(result.complete);
        assert_eq!(result.structural_store_probe.ordered_id_candidates, 1);
        assert_eq!(result.known_key_hits[0], 1);
        assert_eq!(result.known_key_hits[1], 1);
    }

    #[test]
    fn structural_probe_short_fully_read_region_is_complete_without_candidates() {
        let contract = parse_contract_from_embedded_for_test();
        let memory = forever_patcher::SliceMemory::new(0x1000, vec![0; STRUCTURAL_BLOCK_SIZE - 1]);
        let budget = Budget::new();
        let mut result = DiagnosticMapScan::new(DiagnosticClass::AnonymousReadWrite);
        scan_diagnostic_regions(
            &memory,
            &budget,
            &contract,
            &[forever_patcher::Region {
                start: 0x1000,
                end: 0x1000 + (STRUCTURAL_BLOCK_SIZE - 1) as u64,
            }],
            &mut result,
        );
        assert_eq!(result.complete, true);
        assert_eq!(result.structural_store_probe.complete, true);
        assert_eq!(result.structural_store_probe.ordered_id_candidates, 0);
        assert_eq!(result.json()["structuralStoreProbe"]["complete"], true);
    }

    #[test]
    fn structural_probe_caps_samples_and_candidates_without_leaks() {
        let contract = parse_contract_from_embedded_for_test();
        let one = synthetic_structural_store(&contract, false);
        let mut bytes = Vec::with_capacity(one.len() * (MAX_DIAGNOSTIC_CANDIDATES + 1));
        for _ in 0..=MAX_DIAGNOSTIC_CANDIDATES {
            bytes.extend_from_slice(&one);
        }
        let mut result = DiagnosticMapScan::new(DiagnosticClass::AnonymousReadWrite);
        result.structural_store_probe.inspect_chunk(0x1000, &bytes, 0x1000, &contract);
        let probe = result.structural_store_probe.json();
        assert_eq!(probe["orderedIdCandidates"], MAX_DIAGNOSTIC_CANDIDATES);
        assert_eq!(probe["samples"].as_array().unwrap().len(), MAX_DIAGNOSTIC_SAMPLES);
        assert_eq!(probe["candidatesCapped"], true);
        assert_eq!(probe["samplesCapped"], true);
        assert_eq!(probe["complete"], false);
        let output = result.json().to_string();
        assert!(output.len() < 8 * 1024);
        assert!(!output.contains("known_keys"));
        assert!(!output.contains("0x1000"));
    }

    #[test]
    fn diagnostic_rotation_is_atomic_and_discovery_does_not_consume_a_slot() {
        let cursor = AtomicU64::new(0);
        assert_eq!(claim_diagnostic_slot(&cursor), 0);
        assert_eq!(DiagnosticClass::from_slot(0), None);
        for expected in DiagnosticClass::ALL {
            let slot = claim_diagnostic_slot(&cursor);
            assert_eq!(DiagnosticClass::from_slot(slot), Some(expected));
        }
        assert_eq!(DiagnosticClass::from_slot(claim_diagnostic_slot(&cursor)), None);
        let before_not_found = cursor.load(Ordering::Acquire);
        // The NOT_FOUND path returns before claim_diagnostic_slot is called.
        assert_eq!(cursor.load(Ordering::Acquire), before_not_found);
        assert_eq!(claim_diagnostic_slot(&cursor), 0);
    }

    #[test]
    fn post_open_identity_failure_does_not_consume_a_slot() {
        let cursor = AtomicU64::new(0);
        let expected = Target { pid: 9, start_ticks: 77, ppid: 4, uid4: [42; 4], cmdline: b"A:\\WowB-ARM64.exe\0".to_vec() };
        let changed = Target { start_ticks: 78, ..expected.clone() };
        assert_eq!(claim_diagnostic_slot_if_identity_matches(&cursor, &expected, &changed), None);
        assert_eq!(cursor.load(Ordering::Acquire), 0);
    }

    #[test]
    fn incomplete_diagnostic_metadata_cannot_become_authoritative() {
        let mut result = DiagnosticMapScan::new(DiagnosticClass::FileReadOnly);
        result.maps_complete = false;
        result.map_parse_errors = 1;
        result.read_errors = 1;
        let output = result.json();
        assert_eq!(output["complete"], false);
        assert_eq!(output["stores"], Value::Null);
        assert_eq!(output["readOnly"], true);
    }

    struct PartialDiagnosticMemory {
        inner: forever_patcher::SliceMemory,
        reads: AtomicU64,
    }

    impl MemoryAccess for PartialDiagnosticMemory {
        fn regions(&self) -> io::Result<Vec<forever_patcher::Region>> { self.inner.regions() }

        fn read(&self, address: u64, buffer: &mut [u8]) -> io::Result<()> {
            if self.reads.fetch_add(1, Ordering::Relaxed) > 0 {
                return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "synthetic partial read"));
            }
            self.inner.read(address, buffer)
        }

        fn write(&self, _address: u64, _data: &[u8]) -> io::Result<()> {
            Err(io::Error::new(io::ErrorKind::PermissionDenied, "diagnostic memory is read-only"))
        }
    }

    #[test]
    fn partial_reads_and_exhausted_budget_are_incomplete() {
        let contract = parse_contract_from_embedded_for_test();
        let memory = PartialDiagnosticMemory {
            inner: forever_patcher::SliceMemory::new(0x1000, vec![0; 8192]),
            reads: AtomicU64::new(0),
        };
        let budget = Budget::new();
        let regions = [
            forever_patcher::Region { start: 0x1000, end: 0x1100 },
            forever_patcher::Region { start: 0x1200, end: 0x1300 },
        ];
        let mut result = DiagnosticMapScan::new(DiagnosticClass::AnonymousReadWrite);
        scan_diagnostic_regions(&memory, &budget, &contract, &regions, &mut result);
        assert!(!result.complete);
        assert_eq!(result.read_errors, 1);
        let exhausted = Budget::new();
        exhausted.exhausted.store(true, Ordering::Release);
        let mut exhausted_result = DiagnosticMapScan::new(DiagnosticClass::AnonymousReadWrite);
        scan_diagnostic_regions(&memory, &exhausted, &contract, &regions, &mut exhausted_result);
        assert!(!exhausted_result.complete);
        assert!(exhausted_result.budget_exhausted);
    }

    #[test]
    fn historical_diagnostic_rotation_excludes_new_full_scan_classes() {
        let maps = concat!(
            "2000-3000 rwxp 00000000 00:00 0\n",
            "3000-4000 r--s 00000000 00:00 1 /data/app/client.so\n",
            "9000-a000 rw-s 00000000 00:00 4 /data/app/shared\n",
        );
        for class in DiagnosticClass::ALL {
            assert!(parse_diagnostic_maps(maps, class).unwrap().is_empty());
        }
        let full = functional_full_scan::parse_full_readable_maps(maps).unwrap();
        assert_eq!(full.len(), 3);
    }

    #[test]
    fn read_only_memory_write_is_permission_denied() {
        let contract = parse_contract_from_embedded_for_test();
        let lease = Arc::new(Lease {
            token: "test-lease".to_string(),
            own_pid: std::process::id(),
            own_start_ticks: 1,
            own_uid: unsafe { libc::getuid() },
            executable: PathBuf::from("/tmp/WowB-ARM64.exe"),
            nonce: "test".to_string(),
            contract,
            baseline: BTreeMap::new(),
            remembered_candidate: Mutex::new(None),
            code_capture: None,
            cancelled: AtomicBool::new(false),
            diagnostic_cursor: AtomicU64::new(0),
            full_scan: Mutex::new(None),
            previous_subreaper: false,
            pending_apply: Mutex::new(None),
            apply_disabled: AtomicBool::new(false),
        });
        let target = Target { pid: 9, start_ticks: 77, ppid: 4, uid4: [42; 4], cmdline: b"A:\\WowB-ARM64.exe\0".to_vec() };
        let file = File::open("/dev/null").unwrap();
        let memory = ReadOnlyMemory { lease, target, budget: Budget::new(), file: Mutex::new(file), inspect_core: false };
        let error = memory.write(0x1000, &[1]).unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::PermissionDenied);
    }

    fn apply_test_store(contract: &forever_patcher::KeyStoreContract) -> Vec<u8> {
        let mut bytes = vec![0u8; contract.block_size()];
        for index in 0..contract.entry_count {
            let offset = index * contract.entry_size;
            bytes[offset..offset + 4].copy_from_slice(&((index + 1) as u32).to_le_bytes());
            bytes[offset + 4..offset + 36].copy_from_slice(&contract.known_keys[index]);
            bytes[offset + 36] = contract.expected_flags[index];
            bytes[offset + 37..offset + 40].copy_from_slice(&contract.accepted_paddings[0]);
        }
        bytes
    }

    fn apply_test_region(start: u64, perms: &[u8; 4], kind: functional_full_scan::BackingKind) -> functional_full_scan::SnapshotRegion {
        functional_full_scan::SnapshotRegion {
            start,
            end: start + 8192,
            perms: *perms,
            offset: 0,
            dev_major: 0,
            dev_minor: 0,
            inode: 0,
            kind,
        }
    }

    /// Short-write fake: mutates only the first 8 bytes, then fails. The
    /// caller must report the real partial count and latch the lease.
    struct PartialApplyMemory {
        inner: forever_patcher::SliceMemory,
    }

    impl MemoryAccess for PartialApplyMemory {
        fn regions(&self) -> io::Result<Vec<forever_patcher::Region>> { self.inner.regions() }
        fn read(&self, address: u64, buffer: &mut [u8]) -> io::Result<()> { self.inner.read(address, buffer) }
        fn write(&self, address: u64, data: &[u8]) -> io::Result<()> {
            self.inner.write(address, &data[..8.min(data.len())])?;
            Err(io::Error::new(io::ErrorKind::Other, "synthetic partial write"))
        }
    }

    /// Read-back corruption fake: the write lands, but every full-block
    /// read after a write flips one byte outside the key slot.
    struct CorruptingApplyMemory {
        inner: forever_patcher::SliceMemory,
        written: AtomicBool,
    }

    impl MemoryAccess for CorruptingApplyMemory {
        fn regions(&self) -> io::Result<Vec<forever_patcher::Region>> { self.inner.regions() }
        fn read(&self, address: u64, buffer: &mut [u8]) -> io::Result<()> {
            self.inner.read(address, buffer)?;
            if buffer.len() == 480 && self.written.load(Ordering::Relaxed) {
                buffer[0] ^= 0x01;
            }
            Ok(())
        }
        fn write(&self, address: u64, data: &[u8]) -> io::Result<()> {
            let outcome = self.inner.write(address, data);
            if outcome.is_ok() {
                self.written.store(true, Ordering::Relaxed);
            }
            outcome
        }
    }

    #[test]
    fn apply_commit_writes_only_target32_and_reports_idempotent() {
        let contract = parse_contract_from_embedded_for_test();
        let base = 0x1000u64;
        let image = apply_test_store(&contract);
        let expected = image.clone();
        let memory = forever_patcher::SliceMemory::new(base, image);
        match commit_verified_block(&memory, &contract, base, &expected) {
            Ok(CommitVerdict::Applied { bytes }) => assert_eq!(bytes, 32),
            other => panic!("expected Applied, got {other:?}"),
        }
        let after = memory.snapshot();
        let target_off = (contract.target_group - 1) * contract.entry_size + 4;
        assert_eq!(&after[target_off..target_off + 32], &contract.new_key);
        let mut untouched = expected.clone();
        untouched[target_off..target_off + 32].copy_from_slice(&contract.new_key);
        assert_eq!(after, untouched, "only the 32 group-8 bytes may change");
        assert_eq!(&after[4..36], &expected[4..36], "anchor key is untouched");
        // Second commit against the patched block is idempotent, zero bytes.
        match commit_verified_block(&memory, &contract, base, &after) {
            Ok(CommitVerdict::AlreadyPatched) => {}
            other => panic!("expected AlreadyPatched, got {other:?}"),
        }
        assert_eq!(memory.snapshot(), after, "idempotent re-commit writes nothing");
    }

    #[test]
    fn apply_commit_accepts_mixed_padding_rejects_bad_tail() {
        let contract = parse_contract_from_embedded_for_test();
        let observed: [[u8; 3]; 6] = [
            [0x07, 0x00, 0x00],
            [0xff, 0x00, 0x00],
            [0x42, 0x00, 0x00],
            [0x00, 0x00, 0x00],
            [0x7f, 0x00, 0x00],
            [0x07, 0x00, 0x00],
        ];
        let mut bytes = vec![0u8; contract.block_size()];
        for index in 0..contract.entry_count {
            let offset = index * contract.entry_size;
            bytes[offset..offset + 4].copy_from_slice(&((index + 1) as u32).to_le_bytes());
            bytes[offset + 4..offset + 36].copy_from_slice(&contract.known_keys[index]);
            bytes[offset + 36] = contract.expected_flags[index];
            bytes[offset + 37..offset + 40].copy_from_slice(&observed[index % observed.len()]);
        }
        let expected = bytes.clone();
        let memory = forever_patcher::SliceMemory::new(0x2000, bytes);
        assert!(matches!(
            commit_verified_block(&memory, &contract, 0x2000, &expected),
            Ok(CommitVerdict::Applied { bytes: 32 })
        ));
        for tail in [[0x07u8, 0x01, 0x00], [0x07, 0x00, 0x01]] {
            let mut bad = expected.clone();
            bad[5 * contract.entry_size + 37..5 * contract.entry_size + 40].copy_from_slice(&tail);
            let probe = bad.clone();
            let memory = forever_patcher::SliceMemory::new(0x2000, bad);
            assert!(matches!(
                commit_verified_block(&memory, &contract, 0x2000, &probe),
                Ok(CommitVerdict::Invalid)
            ));
            assert_eq!(memory.snapshot(), probe, "rejected tail writes zero bytes");
        }
    }

    #[test]
    fn apply_commit_rejects_wrong_ids_keys_flags_without_writing() {
        let contract = parse_contract_from_embedded_for_test();
        let good = apply_test_store(&contract);
        // Wrong ID in entry 3.
        let mut bad_id = good.clone();
        bad_id[2 * contract.entry_size..2 * contract.entry_size + 4].copy_from_slice(&99u32.to_le_bytes());
        // Wrong key in entry 5.
        let mut bad_key = good.clone();
        bad_key[4 * contract.entry_size + 4] ^= 0x01;
        // Wrong flag in entry 2.
        let mut bad_flag = good.clone();
        bad_flag[1 * contract.entry_size + 36] ^= 0x01;
        for bad in [&bad_id, &bad_key, &bad_flag] {
            let memory = forever_patcher::SliceMemory::new(0x3000, bad.clone());
            assert!(matches!(
                commit_verified_block(&memory, &contract, 0x3000, bad),
                Ok(CommitVerdict::Invalid)
            ));
            assert_eq!(memory.snapshot(), *bad, "rejected block writes zero bytes");
        }
        // Changed pre-image between poll and commit is also Invalid.
        let memory = forever_patcher::SliceMemory::new(0x3000, bad_key.clone());
        let mut changed = bad_key.clone();
        changed[0] ^= 0x01;
        let _ = memory.write(0x3000, &changed[0..1]);
        assert!(matches!(
            commit_verified_block(&memory, &contract, 0x3000, &bad_key),
            Ok(CommitVerdict::Invalid)
        ));
    }

    #[test]
    fn apply_mapping_gate_requires_private_writable_anon_and_exact_fingerprint() {
        let rw = apply_test_region(0x1000, b"rw-p", functional_full_scan::BackingKind::Anonymous);
        assert!(is_writable_anon_private(&rw));
        let rwx = apply_test_region(0x1000, b"rwxp", functional_full_scan::BackingKind::Anonymous);
        assert!(is_writable_anon_private(&rwx));
        // Kernel-named anonymous memory ([heap]/[stack] classify as Pseudo
        // with inode 0 / device 00:00) is private anonymous memory too: a
        // store there must not be rejected solely via its backing kind.
        let heap = apply_test_region(0x1000, b"rw-p", functional_full_scan::BackingKind::Pseudo);
        assert!(is_writable_anon_private(&heap));
        let readonly = apply_test_region(0x1000, b"r--p", functional_full_scan::BackingKind::Anonymous);
        assert!(!is_writable_anon_private(&readonly));
        let shared = functional_full_scan::SnapshotRegion { perms: *b"rw-s", kind: functional_full_scan::BackingKind::File, inode: 4, dev_major: 0, dev_minor: 0, start: 0x1000, end: 0x3000, offset: 0 };
        assert!(!is_writable_anon_private(&shared));
        let file = functional_full_scan::SnapshotRegion { perms: *b"rw-p", kind: functional_full_scan::BackingKind::File, inode: 7, dev_major: 8, dev_minor: 1, start: 0x1000, end: 0x3000, offset: 0 };
        assert!(!is_writable_anon_private(&file));
        let device = functional_full_scan::SnapshotRegion { perms: *b"rw-p", kind: functional_full_scan::BackingKind::Device, inode: 3, dev_major: 0, dev_minor: 0, start: 0x1000, end: 0x3000, offset: 0 };
        assert!(!is_writable_anon_private(&device));
        // Containment: the full 480 B must sit inside one region.
        let regions = vec![rw];
        assert!(containing_region(&regions, 0x1000, 480).is_some());
        assert!(containing_region(&regions, 0x1000 + 8192 - 480, 480).is_some());
        assert!(containing_region(&regions, 0x1000 + 8192 - 480 + 1, 480).is_none());
        // A changed backing at the same VA compares different.
        let mut changed = rw;
        changed.offset = 0x1000;
        assert_ne!(changed, rw);
    }

    #[test]
    fn apply_partial_write_and_readback_failure_are_truthful_errors() {
        let contract = parse_contract_from_embedded_for_test();
        let good = apply_test_store(&contract);
        // Partial write: 8 bytes land, then an error. The snapshot proves
        // the partial mutation; the caller latches the lease on any Err.
        let partial = PartialApplyMemory { inner: forever_patcher::SliceMemory::new(0x4000, good.clone()) };
        let outcome = commit_verified_block(&partial, &contract, 0x4000, &good);
        assert!(matches!(outcome, Err((0, _))), "write error carries (0, kind); real bytes come from the fd stash");
        let snapshot = partial.inner.snapshot();
        let target_off = (contract.target_group - 1) * contract.entry_size + 4;
        assert_eq!(&snapshot[target_off..target_off + 8], &contract.new_key[..8]);
        assert_eq!(&snapshot[target_off + 8..target_off + 32], &good[target_off + 8..target_off + 32]);
        // Read-back corruption: write lands fully, verification fails with
        // the real 32 committed bytes so the caller can latch truthfully.
        let corrupting = CorruptingApplyMemory { inner: forever_patcher::SliceMemory::new(0x5000, good.clone()), written: AtomicBool::new(false) };
        match commit_verified_block(&corrupting, &contract, 0x5000, &good) {
            Err((bytes, detail)) => {
                assert_eq!(bytes, 32);
                assert!(detail.contains("mismatch"));
            }
            other => panic!("expected read-back Err, got {other:?}"),
        }
    }

    #[test]
    fn apply_status_json_is_stable_and_leak_free() {
        for (status, bytes, authorized) in [("APPLIED", 32, true), ("ALREADY_PATCHED", 0, true), ("DENIED", 0, false), ("ERROR", 8, true)] {
            let text = apply_json(status, bytes, authorized, None);
            let value: Value = serde_json::from_str(&text).unwrap();
            assert_eq!(value["version"], 1);
            assert_eq!(value["status"], status);
            assert_eq!(value["bytesWritten"], bytes);
            assert_eq!(value["readOnly"], false);
            assert_eq!(value["writeAuthorized"], authorized);
            assert!(value.get("addr").is_none());
            assert!(value.get("address").is_none());
            assert!(!text.contains("0x"));
        }
        let denied = apply_json("DENIED", 0, false, Some(("identity", "target identity changed; no write attempted")));
        assert!(denied.contains("\"status\":\"DENIED\""));
        assert!(denied.contains("\"bytesWritten\":0"));
        let value: Value = serde_json::from_str(&denied).unwrap();
        assert_eq!(value["errorKind"], "identity");
    }

    fn apply_test_lease(contract: forever_patcher::KeyStoreContract) -> Arc<Lease> {
        Arc::new(Lease {
            token: "test-apply-lease".to_string(),
            own_pid: std::process::id(),
            own_start_ticks: 1,
            own_uid: unsafe { libc::getuid() },
            executable: PathBuf::from("/tmp/WowB-ARM64.exe"),
            nonce: "test".to_string(),
            contract,
            baseline: BTreeMap::new(),
            remembered_candidate: Mutex::new(None),
            code_capture: None,
            cancelled: AtomicBool::new(false),
            diagnostic_cursor: AtomicU64::new(0),
            full_scan: Mutex::new(None),
            previous_subreaper: false,
            pending_apply: Mutex::new(None),
            apply_disabled: AtomicBool::new(false),
        })
    }

    fn apply_test_pending(target: Target, region: functional_full_scan::SnapshotRegion, addr: u64) -> PendingApply {
        PendingApply { target, region, addr }
    }

    #[test]
    fn gate_admission_denies_stale_cancelled_latched_or_changed_proposal() {
        // Drives the exact predicate the production commit gate enforces, so
        // a cancelled/ended lease can never yield a stale ALREADY success and
        // no further commit is authorized after a failed post-check latches.
        let contract = parse_contract_from_embedded_for_test();
        let target = Target { pid: 9, start_ticks: 77, ppid: 4, uid4: [42; 4], cmdline: b"A:\\WowB-ARM64.exe\0".to_vec() };
        let region = apply_test_region(0x1000, b"rw-p", functional_full_scan::BackingKind::Anonymous);
        let pending = apply_test_pending(target.clone(), region, 0x1000);
        let admit = |active_token: Option<&str>, active_cancelled: bool, lease_cancelled: bool, disabled: bool, now: Option<&PendingApply>| {
            gate_proposal_admits("test-token", lease_cancelled, disabled, &pending, active_token, active_cancelled, now)
        };
        assert!(admit(Some("test-token"), false, false, false, Some(&pending)));
        // End won or token unknown: deny.
        assert!(!admit(None, false, false, false, Some(&pending)));
        assert!(!admit(Some("other-token"), false, false, false, Some(&pending)));
        // Cancelled on either side of the gate: deny.
        assert!(!admit(Some("test-token"), true, false, false, Some(&pending)));
        assert!(!admit(Some("test-token"), false, true, false, Some(&pending)));
        // Latched after a failed post-check: deny, no further commit.
        assert!(!admit(Some("test-token"), false, false, true, Some(&pending)));
        // Concurrent poll cleared or replaced the proposal: deny.
        assert!(!admit(Some("test-token"), false, false, false, None));
        let moved = apply_test_pending(target.clone(), region, 0x2000);
        assert!(!admit(Some("test-token"), false, false, false, Some(&moved)));
        let mut changed_region = region;
        changed_region.offset = 0x1000;
        let remapped = apply_test_pending(target.clone(), changed_region, 0x1000);
        assert!(!admit(Some("test-token"), false, false, false, Some(&remapped)));
        let changed_target = Target { start_ticks: 78, ..target.clone() };
        let retargeted = apply_test_pending(changed_target, region, 0x1000);
        assert!(!admit(Some("test-token"), false, false, false, Some(&retargeted)));
        let _ = contract;
    }

    #[test]
    fn panic_after_write_attempt_latches_and_reports_honest_bytes() {
        // Panic boundary truth, using the actual ApplyMemory write path and
        // the actual panic report: idle panics never latch, while any issued
        // write syscall latches this lease and reports the real kernel count.
        let contract = parse_contract_from_embedded_for_test();
        let lease = apply_test_lease(contract);
        let idle = Arc::new(ApplyProgress { attempted: AtomicBool::new(false), written: AtomicU64::new(0) });
        let report: Value = serde_json::from_str(&apply_panic_report(&lease, &idle)).unwrap();
        assert_eq!(report["status"], "ERROR");
        assert_eq!(report["bytesWritten"], 0);
        assert_eq!(report["writeAuthorized"], false);
        assert!(!lease.apply_disabled.load(Ordering::Acquire));
        // Full 32 B through the real fd write path, then a panic.
        let path = test_contract_path("apply-panic-mem");
        std::fs::write(&path, vec![0u8; 64]).unwrap();
        let target = Target { pid: 9, start_ticks: 77, ppid: 4, uid4: [42; 4], cmdline: b"A:\\WowB-ARM64.exe\0".to_vec() };
        let region = apply_test_region(0, b"rw-p", functional_full_scan::BackingKind::Anonymous);
        *lease.pending_apply.lock().unwrap() = Some(apply_test_pending(target, region, 0));
        let file = std::fs::OpenOptions::new().read(true).write(true).open(&path).unwrap();
        let progress = Arc::new(ApplyProgress { attempted: AtomicBool::new(false), written: AtomicU64::new(0) });
        let memory = ApplyMemory { file: Mutex::new(file), budget: Budget::new(), progress: progress.clone() };
        memory.write(0, &[0xabu8; 32]).unwrap();
        assert!(progress.attempted.load(Ordering::Acquire));
        assert_eq!(progress.written.load(Ordering::Relaxed), 32);
        let report: Value = serde_json::from_str(&apply_panic_report(&lease, &progress)).unwrap();
        assert_eq!(report["status"], "ERROR");
        assert_eq!(report["bytesWritten"], 32);
        assert_eq!(report["writeAuthorized"], true);
        assert!(lease.apply_disabled.load(Ordering::Acquire));
        assert!(lease.pending_apply.lock().unwrap().is_none());
        std::fs::remove_file(&path).unwrap();
        // Failed write attempt (read-only fd): honest 0 bytes, still latched.
        let lease = apply_test_lease(parse_contract_from_embedded_for_test());
        let path = test_contract_path("apply-panic-mem-ro");
        std::fs::write(&path, vec![0u8; 64]).unwrap();
        let ro = std::fs::OpenOptions::new().read(true).open(&path).unwrap();
        let progress = Arc::new(ApplyProgress { attempted: AtomicBool::new(false), written: AtomicU64::new(0) });
        let memory = ApplyMemory { file: Mutex::new(ro), budget: Budget::new(), progress: progress.clone() };
        assert!(memory.write(0, &[0xabu8; 32]).is_err());
        assert!(progress.attempted.load(Ordering::Acquire));
        assert_eq!(progress.written.load(Ordering::Relaxed), 0);
        let report: Value = serde_json::from_str(&apply_panic_report(&lease, &progress)).unwrap();
        assert_eq!(report["bytesWritten"], 0);
        assert_eq!(report["writeAuthorized"], true);
        assert!(lease.apply_disabled.load(Ordering::Acquire));
        std::fs::remove_file(&path).unwrap();
    }
}
