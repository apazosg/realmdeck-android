//! Bounded SIGINT provenance capture.
//!
//! The signal path is intentionally kept separate from the patcher.  The
//! handler only touches its immutable context, a fixed stack buffer, the
//! async-signal-safe libc primitives, and one already-open descriptor.  JSON,
//! validation, file creation, and sigaction inspection all happen outside the
//! handler.

use serde_json::{Map, Value, json};
use std::ffi::CString;
use std::fs;
use std::io;
use std::mem::MaybeUninit;
use std::os::unix::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicPtr, AtomicU32, Ordering};
use std::sync::{Mutex, OnceLock};

const HEADER_BYTES: usize = 256;
const EVENT_BYTES: usize = 64;
const MAX_EVENTS: u32 = 4;
const MAX_RUN_ID_BYTES: usize = 100;
const STATUS_VERSION: u32 = 1;
const SA_RESTORER_LINUX: libc::c_ulong = 0x0400_0000;

struct TraceContext {
    fd: libc::c_int,
    armed_pid: libc::pid_t,
    event_count: AtomicU32,
    install_failed: AtomicU32,
}

struct Binding {
    output_path: String,
    run_id: String,
    boot_id: String,
    expected_pid: libc::pid_t,
    expected_start_ticks: u64,
    context: *mut TraceContext,
}

// The pointer is published only after every context field has been initialized
// and before sigaction installs the handler.  It is never reclaimed or closed:
// an in-flight callback must never observe fd reuse.
unsafe impl Send for Binding {}
unsafe impl Sync for Binding {}
static CONTEXT: AtomicPtr<TraceContext> = AtomicPtr::new(std::ptr::null_mut());
static BINDING: OnceLock<Binding> = OnceLock::new();
static ARM_LOCK: Mutex<()> = Mutex::new(());

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Disposition {
    Default,
    Ignore,
    Custom,
    IncompatibleDefault,
}

fn response(status: &str, reason: &str, binding: Option<&Binding>, verified: bool, previous: Option<&str>) -> String {
    let mut out = Map::new();
    out.insert("version".to_string(), json!(STATUS_VERSION));
    out.insert("status".to_string(), json!(status));
    out.insert("reason".to_string(), json!(reason));
    if verified {
        out.insert("verifiedBinding".to_string(), Value::Bool(true));
    }
    if let Some(binding) = binding {
        out.insert("pid".to_string(), json!(binding.expected_pid));
        out.insert("startTicks".to_string(), json!(binding.expected_start_ticks));
        out.insert("runId".to_string(), json!(binding.run_id));
        out.insert("bootId".to_string(), json!(binding.boot_id));
        out.insert("path".to_string(), json!(binding.output_path));
    }
    if let Some(previous) = previous {
        out.insert("previousDisposition".to_string(), json!(previous));
    }
    Value::Object(out).to_string()
}

fn error_response(reason: impl Into<String>, binding: Option<&Binding>) -> String {
    response("ERROR", &reason.into(), binding, false, None)
}

fn unsupported_response(reason: &str, binding: Option<&Binding>, previous: Option<&str>) -> String {
    response("UNSUPPORTED", reason, binding, binding.is_some(), previous)
}

fn put_u32(buf: &mut [u8], offset: usize, value: u32) {
    buf[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}

fn put_i32(buf: &mut [u8], offset: usize, value: i32) {
    put_u32(buf, offset, value as u32);
}

fn put_u64(buf: &mut [u8], offset: usize, value: u64) {
    buf[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
}

#[cfg(test)]
fn read_u64(buf: &[u8], offset: usize) -> u64 {
    u64::from_le_bytes(buf[offset..offset + 8].try_into().unwrap_or([0; 8]))
}

fn read_self_start_ticks() -> Result<u64, String> {
    let text = fs::read_to_string("/proc/self/stat").map_err(|e| format!("self stat unreadable: {e}"))?;
    let close = text.rfind(')').ok_or_else(|| "self stat has no comm terminator".to_string())?;
    let fields: Vec<&str> = text[close + 1..].split_whitespace().collect();
    fields.get(19)
        .ok_or_else(|| "self stat has no starttime".to_string())?
        .parse::<u64>()
        .map_err(|e| format!("self starttime invalid: {e}"))
}

fn read_boot_id() -> Result<String, String> {
    let boot = fs::read_to_string("/proc/sys/kernel/random/boot_id")
        .map_err(|e| format!("boot id unreadable: {e}"))?;
    Ok(boot.trim().to_string())
}

fn valid_uuid(value: &str) -> bool {
    if value.len() != 36 {
        return false;
    }
    value.bytes().enumerate().all(|(index, byte)| {
        if matches!(index, 8 | 13 | 18 | 23) {
            byte == b'-'
        } else {
            byte.is_ascii_hexdigit()
        }
    })
}

fn valid_run_id(value: &str) -> bool {
    let bytes = value.as_bytes();
    !bytes.is_empty()
        && bytes.len() <= MAX_RUN_ID_BYTES
        && bytes[0].is_ascii_alphanumeric()
        && bytes.iter().all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

fn current_action() -> io::Result<libc::sigaction> {
    let mut action = MaybeUninit::<libc::sigaction>::zeroed();
    // SAFETY: sigaction fills the supplied object for SIGINT.
    let result = unsafe { libc::sigaction(libc::SIGINT, std::ptr::null(), action.as_mut_ptr()) };
    if result != 0 {
        Err(io::Error::last_os_error())
    } else {
        // SAFETY: sigaction succeeded and initialized the object.
        Ok(unsafe { action.assume_init() })
    }
}

fn action_disposition(action: &libc::sigaction) -> Disposition {
    let handler = action.sa_sigaction;
    if handler == libc::SIG_DFL {
        let compatible = libc::SA_RESTART as libc::c_ulong | SA_RESTORER_LINUX;
        if (action.sa_flags as libc::c_ulong) & !compatible == 0 {
            Disposition::Default
        } else {
            Disposition::IncompatibleDefault
        }
    } else if handler == libc::SIG_IGN {
        Disposition::Ignore
    } else {
        Disposition::Custom
    }
}

fn disposition_name(disposition: Disposition) -> &'static str {
    match disposition {
        Disposition::Default => "SIG_DFL",
        Disposition::Ignore => "SIG_IGN",
        Disposition::Custom => "CUSTOM",
        Disposition::IncompatibleDefault => "SIG_DFL_INCOMPATIBLE_FLAGS",
    }
}

fn clock_pair() -> Result<(u64, u64), String> {
    let mut realtime = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    let mut monotonic = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    // SAFETY: both timespecs are valid writable stack objects.
    let realtime_ok = unsafe { libc::clock_gettime(libc::CLOCK_REALTIME, &mut realtime) } == 0;
    // SAFETY: both timespecs are valid writable stack objects.
    let monotonic_ok = unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut monotonic) } == 0;
    if !realtime_ok || !monotonic_ok || realtime.tv_sec < 0 || monotonic.tv_sec < 0 {
        return Err(format!("clock_gettime failed: {}", io::Error::last_os_error()));
    }
    let realtime_ms = (realtime.tv_sec as u64)
        .checked_mul(1_000)
        .and_then(|v| v.checked_add(realtime.tv_nsec as u64 / 1_000_000))
        .ok_or_else(|| "realtime milliseconds overflow".to_string())?;
    let monotonic_ns = (monotonic.tv_sec as u64)
        .checked_mul(1_000_000_000)
        .and_then(|v| v.checked_add(monotonic.tv_nsec as u64))
        .ok_or_else(|| "monotonic nanoseconds overflow".to_string())?;
    Ok((realtime_ms, monotonic_ns))
}

fn split_absolute_path(path: &Path) -> Result<(PathBuf, CString), String> {
    if !path.is_absolute() {
        return Err("outputPath must be absolute".to_string());
    }
    let name = path.file_name().ok_or_else(|| "outputPath has no basename".to_string())?;
    let name_bytes = name.as_bytes();
    if name_bytes.is_empty() || name_bytes.contains(&0) || name_bytes.contains(&b'/') {
        return Err("outputPath basename is invalid".to_string());
    }
    let name = CString::new(name_bytes).map_err(|_| "outputPath basename is invalid".to_string())?;
    let parent = path.parent().ok_or_else(|| "outputPath has no parent".to_string())?;
    Ok((parent.to_path_buf(), name))
}

fn path_components(path: &Path) -> Result<Vec<Vec<u8>>, String> {
    let mut components = Vec::new();
    for component in path.components() {
        match component {
            std::path::Component::RootDir => {}
            std::path::Component::Normal(value) => components.push(value.as_bytes().to_vec()),
            _ => return Err("outputPath contains a non-canonical component".to_string()),
        }
    }
    Ok(components)
}

#[cfg(target_os = "android")]
fn process_package() -> Result<String, String> {
    let bytes = fs::read("/proc/self/cmdline").map_err(|e| format!("process package unreadable: {e}"))?;
    let first = bytes.split(|byte| *byte == 0).next().unwrap_or(&[]);
    let package = std::str::from_utf8(first).map_err(|_| "process package is not ASCII".to_string())?;
    let package = package.split(':').next().unwrap_or("");
    if package.is_empty() || !package.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_')) {
        return Err("process package name is invalid".to_string());
    }
    Ok(package.to_string())
}

#[cfg(target_os = "android")]
fn validate_android_private_parent(parent: &Path) -> Result<(), String> {
    let package = process_package()?;
    let candidates = [
        PathBuf::from(format!("/data/user/0/{package}/files/forever/runs")),
        PathBuf::from(format!("/data/user_de/0/{package}/files/forever/runs")),
        PathBuf::from(format!("/data/data/{package}/files/forever/runs")),
    ];
    if candidates.iter().any(|candidate| candidate == parent) {
        Ok(())
    } else {
        Err("outputPath is outside the process private files/forever/runs directory".to_string())
    }
}

fn open_private_output(path: &Path, expected_basename: &str) -> Result<libc::c_int, String> {
    let (parent, basename) = split_absolute_path(path)?;
    if basename.to_bytes() != expected_basename.as_bytes() {
        return Err("outputPath basename must be <runId>-sigint.bin".to_string());
    }
    #[cfg(target_os = "android")]
    validate_android_private_parent(&parent)?;
    #[cfg(all(not(target_os = "android"), not(test)))]
    return Err("signal trace output is only enabled in Android private storage".to_string());

    let components = path_components(&parent)?;
    let root = CString::new("/").expect("literal has no NUL");
    // SAFETY: opening a fixed directory with no caller-controlled bytes.
    let mut dir_fd = unsafe { libc::open(root.as_ptr(), libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC, 0) };
    if dir_fd < 0 {
        return Err(format!("output parent root cannot be opened: {}", io::Error::last_os_error()));
    }
    for component in components {
        let component = CString::new(component).map_err(|_| "output parent contains NUL".to_string())?;
        // SAFETY: dir_fd is our directory fd and O_NOFOLLOW prevents symlink traversal.
        let next = unsafe {
            libc::openat(
                dir_fd,
                component.as_ptr(),
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
                0,
            )
        };
        if next < 0 {
            let error = io::Error::last_os_error();
            // SAFETY: dir_fd is owned by this function and is not published.
            unsafe { libc::close(dir_fd) };
            return Err(format!("output parent is not a private no-follow directory: {error}"));
        }
        // SAFETY: dir_fd is the previous directory and no longer needed.
        unsafe { libc::close(dir_fd) };
        dir_fd = next;
    }
    let fd = unsafe {
        libc::openat(
            dir_fd,
            basename.as_ptr(),
            libc::O_WRONLY | libc::O_CREAT | libc::O_EXCL | libc::O_CLOEXEC | libc::O_NOFOLLOW | libc::O_APPEND,
            0o600,
        )
    };
    let open_error = if fd < 0 { Some(io::Error::last_os_error()) } else { None };
    // SAFETY: dir_fd is the traversal fd and is never used by the handler.
    unsafe { libc::close(dir_fd) };
    let fd = match (fd, open_error) {
        (fd, None) if fd >= 0 => fd,
        (_, Some(error)) => return Err(format!("output file cannot be created exclusively: {error}")),
        _ => return Err("output file cannot be created".to_string()),
    };

    let mut stat = MaybeUninit::<libc::stat>::zeroed();
    // SAFETY: fd is open and stat points to writable storage.
    if unsafe { libc::fstat(fd, stat.as_mut_ptr()) } != 0 {
        let error = io::Error::last_os_error();
        // SAFETY: fd is owned until the context is published.
        unsafe { libc::close(fd) };
        return Err(format!("output file metadata failed: {error}"));
    }
    // SAFETY: fstat succeeded.
    let stat = unsafe { stat.assume_init() };
    let mode = stat.st_mode as libc::mode_t;
    if (mode & libc::S_IFMT as libc::mode_t) != libc::S_IFREG as libc::mode_t
        || stat.st_uid != unsafe { libc::getuid() }
        || mode & 0o777 != 0o600
    {
        // SAFETY: fd is not published on this path.
        unsafe { libc::close(fd) };
        return Err("output file is not a private regular 0600 file owned by the process".to_string());
    }
    Ok(fd)
}

fn write_header(fd: libc::c_int, run_id: &str, boot_id: &str, pid: i32, start_ticks: u64) -> Result<(), String> {
    let (realtime_ms, monotonic_ns) = clock_pair()?;
    let pgid = unsafe { libc::getpgid(0) };
    let sid = unsafe { libc::getsid(0) };
    let mut header = [0u8; HEADER_BYTES];
    header[0..8].copy_from_slice(b"WFSIG001");
    put_u32(&mut header, 8, 1);
    put_u32(&mut header, 12, HEADER_BYTES as u32);
    put_i32(&mut header, 16, pid);
    put_u32(&mut header, 20, unsafe { libc::getuid() });
    put_u64(&mut header, 24, start_ticks);
    put_u64(&mut header, 32, realtime_ms);
    put_u64(&mut header, 40, monotonic_ns);
    put_i32(&mut header, 48, pgid);
    put_i32(&mut header, 52, sid);
    put_u32(&mut header, 56, run_id.len() as u32);
    put_u32(&mut header, 60, boot_id.len() as u32);
    header[64..64 + run_id.len()].copy_from_slice(run_id.as_bytes());
    header[192..192 + boot_id.len()].copy_from_slice(boot_id.as_bytes());
    let mut written = 0usize;
    while written < header.len() {
        // SAFETY: fd is private and header slice remains valid for this call.
        let count = unsafe {
            libc::write(
                fd,
                header[written..].as_ptr().cast::<libc::c_void>(),
                header.len() - written,
            )
        };
        if count < 0 {
            let error = io::Error::last_os_error();
            if error.raw_os_error() == Some(libc::EINTR) {
                continue;
            }
            unsafe { libc::close(fd) };
            return Err(format!("signal trace header write failed: {error}"));
        }
        if count == 0 {
            unsafe { libc::close(fd) };
            return Err("signal trace header write made no progress".to_string());
        }
        written += count as usize;
    }
    // The handler is not installed until this durable header is complete.
    if unsafe { libc::fsync(fd) } != 0 {
        let error = io::Error::last_os_error();
        unsafe { libc::close(fd) };
        return Err(format!("signal trace header fsync failed: {error}"));
    }
    Ok(())
}

fn write_event(fd: libc::c_int, signal: i32, info: *const libc::siginfo_t, receiving_pid: i32, receiving_tid: i32) {
    let mut event = [0u8; EVENT_BYTES];
    event[0..8].copy_from_slice(b"WFSIGEV1");
    put_u32(&mut event, 8, 1);
    put_u32(&mut event, 12, EVENT_BYTES as u32);
    put_i32(&mut event, 16, signal);
    let mut flags = if info.is_null() { 0 } else { 1 };
    let mut sender_pid = 0i32;
    let mut sender_uid = 0u32;
    if !info.is_null() {
        // SAFETY: the kernel supplies this pointer for SA_SIGINFO handlers.
        let code = unsafe { (*info).si_code };
        put_i32(&mut event, 20, code);
        if matches!(code, libc::SI_USER | libc::SI_QUEUE | libc::SI_TKILL) {
            // SAFETY: these accessors read the siginfo union supplied by libc.
            let candidate_pid = unsafe { (*info).si_pid() };
            let candidate_uid = unsafe { (*info).si_uid() };
            if candidate_pid > 0 {
                sender_pid = candidate_pid;
                sender_uid = candidate_uid as u32;
                flags |= 2;
            }
        }
    }
    put_i32(&mut event, 24, receiving_pid);
    put_i32(&mut event, 28, receiving_tid);
    put_i32(&mut event, 32, sender_pid);
    put_u32(&mut event, 36, sender_uid);
    let mut realtime = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    let mut monotonic = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    let realtime_ok = unsafe { libc::clock_gettime(libc::CLOCK_REALTIME, &mut realtime) } == 0;
    let monotonic_ok = unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut monotonic) } == 0;
    if realtime_ok && realtime.tv_sec >= 0 {
        put_u64(&mut event, 40, (realtime.tv_sec as u64).saturating_mul(1_000).saturating_add(realtime.tv_nsec as u64 / 1_000_000));
    } else {
        flags |= 4;
    }
    if monotonic_ok && monotonic.tv_sec >= 0 {
        put_u64(&mut event, 48, (monotonic.tv_sec as u64).saturating_mul(1_000_000_000).saturating_add(monotonic.tv_nsec as u64));
    } else {
        flags |= 4;
    }
    put_u32(&mut event, 56, flags);
    // One attempt only: a short/error record is invalid evidence, never a
    // reason to postpone default termination through an EINTR retry loop.
    unsafe { libc::write(fd, event.as_ptr().cast::<libc::c_void>(), event.len()); }
}

#[inline(always)]
unsafe fn libc_errno() -> *mut libc::c_int {
    #[cfg(target_os = "android")]
    {
        return libc::__errno();
    }
    #[cfg(not(target_os = "android"))]
    libc::__errno_location()
}

extern "C" fn signal_handler(signal: libc::c_int, info: *mut libc::siginfo_t, _ucontext: *mut libc::c_void) {
    // SAFETY: errno is thread-local storage supplied by libc.
    let errno_ptr = unsafe { libc_errno() };
    let saved_errno = unsafe { *errno_ptr };
    let receiving_pid = unsafe { libc::getpid() };
    let receiving_tid = unsafe { libc::syscall(libc::SYS_gettid) as libc::pid_t };
    let context = CONTEXT.load(Ordering::Acquire);
    if !context.is_null() {
        // SAFETY: the context is leaked after publication and remains valid for
        // the entire process lifetime.
        let context = unsafe { &*context };
        if receiving_pid == context.armed_pid {
            let slot = context.event_count.fetch_add(1, Ordering::Relaxed);
            if slot < MAX_EVENTS {
                write_event(context.fd, signal, info.cast_const(), receiving_pid, receiving_tid);
            }
        }
    }
    // In both the armed process and a fork child, restore the prior default
    // disposition and deliver SIGINT to this exact thread.  There is no _exit,
    // group signal, allocation, lock, JNI call, or JSON path here.
    let mut default_action: libc::sigaction = unsafe { std::mem::zeroed() };
    default_action.sa_sigaction = libc::SIG_DFL;
    default_action.sa_flags = 0;
    unsafe {
        libc::sigaction(signal, &default_action, std::ptr::null_mut());
        libc::syscall(libc::SYS_tgkill, receiving_pid, receiving_tid, signal);
        *errno_ptr = saved_errno;
    }
}

fn install_handler(previous: &libc::sigaction) -> Result<(), String> {
    let mut action = *previous;
    action.sa_sigaction = signal_handler as *const () as usize;
    action.sa_flags = (action.sa_flags as libc::c_ulong | libc::SA_SIGINFO as libc::c_ulong) as _;
    // SAFETY: action is initialized from the queried SIGINT disposition.
    if unsafe { libc::sigaction(libc::SIGINT, &action, std::ptr::null_mut()) } != 0 {
        return Err(format!("SIGINT handler installation failed: {}", io::Error::last_os_error()));
    }
    Ok(())
}

fn binding_matches(binding: &Binding, output_path: &str, run_id: &str, boot_id: &str, expected_pid: i32, expected_start_ticks: u64) -> bool {
    binding.output_path == output_path
        && binding.run_id == run_id
        && binding.boot_id == boot_id
        && binding.expected_pid == expected_pid
        && binding.expected_start_ticks == expected_start_ticks
}

fn current_handler_is_ours() -> io::Result<bool> {
    let action = current_action()?;
    Ok(action.sa_sigaction == signal_handler as *const () as usize
        && (action.sa_flags as libc::c_ulong) & libc::SA_SIGINFO as libc::c_ulong != 0)
}

/// Arms the fixed binary SIGINT trace, or returns a schema-1 diagnostic result.
pub fn arm_signal_trace(output_path: &str, run_id: &str, boot_id: &str, expected_pid: i32, expected_start_ticks: i64) -> String {
    // Android/libsigchain and other bundled components may own SIGINT. Linux
    // child tests do not prove exclusive ownership in the Android host. Until
    // a supported mechanism is validated, DO NOT alter an Android disposition.
    if cfg!(target_os = "android") {
        return unsupported_response("ANDROID_SIGNAL_OWNERSHIP_UNVERIFIED", None, None);
    }
    let _lock = ARM_LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    let expected_start_ticks = match u64::try_from(expected_start_ticks) {
        Ok(value) => value,
        Err(_) => return error_response("expectedStartTicks must be non-negative", BINDING.get()),
    };
    let current_pid = unsafe { libc::getpid() };
    let current_pid_i32 = current_pid as i32;
    if expected_pid != current_pid_i32 {
        return error_response("expectedPid does not match the current process", BINDING.get());
    }
    if let Some(binding) = BINDING.get() {
        if binding_matches(binding, output_path, run_id, boot_id, expected_pid, expected_start_ticks) {
            if unsafe { (*binding.context).install_failed.load(Ordering::Acquire) != 0 } {
                return error_response("SIGINT handler was not armed", Some(binding));
            }
            return match current_handler_is_ours() {
                Ok(true) => response("ARMED", "ALREADY_BOUND", Some(binding), true, Some("SIG_DFL")),
                Ok(false) => unsupported_response("HANDLER_NO_LONGER_INSTALLED", Some(binding), Some("SIG_DFL")),
                Err(error) => error_response(format!("SIGINT disposition query failed: {error}"), Some(binding)),
            };
        }
        return unsupported_response("ALREADY_BOUND", Some(binding), Some("SIG_DFL"));
    }

    let action = match current_action() {
        Ok(action) => action,
        Err(error) => return error_response(format!("SIGINT disposition query failed: {error}"), None),
    };
    let disposition = action_disposition(&action);
    if disposition != Disposition::Default {
        return unsupported_response("PREVIOUS_DISPOSITION_UNSUPPORTED", None, Some(disposition_name(disposition)));
    }
    if !valid_run_id(run_id) {
        return error_response("runId must match the ASCII run identifier grammar", None);
    }
    if !valid_uuid(boot_id) {
        return error_response("bootId must be a canonical 36-byte UUID", None);
    }
    let actual_start_ticks = match read_self_start_ticks() {
        Ok(value) => value,
        Err(error) => return error_response(error, None),
    };
    if actual_start_ticks != expected_start_ticks {
        return error_response("expectedStartTicks does not match the current process", None);
    }
    let actual_boot_id = match read_boot_id() {
        Ok(value) => value,
        Err(error) => return error_response(error, None),
    };
    if actual_boot_id != boot_id {
        return error_response("bootId does not match the current boot", None);
    }
    let expected_basename = format!("{run_id}-sigint.bin");
    let fd = match open_private_output(Path::new(output_path), &expected_basename) {
        Ok(fd) => fd,
        Err(error) => return error_response(error, None),
    };
    if let Err(error) = write_header(fd, run_id, boot_id, current_pid_i32, expected_start_ticks) {
        return error_response(error, None);
    }
    let context = Box::into_raw(Box::new(TraceContext {
        fd,
        armed_pid: current_pid,
        event_count: AtomicU32::new(0),
        install_failed: AtomicU32::new(0),
    }));
    let binding = Binding {
        output_path: output_path.to_string(),
        run_id: run_id.to_string(),
        boot_id: boot_id.to_string(),
        expected_pid,
        expected_start_ticks,
        context,
    };
    if BINDING.set(binding).is_err() {
        // This is unreachable under ARM_LOCK, but do not publish a descriptor
        // twice if an implementation changes the synchronization later.
        return error_response("signal trace binding initialization raced", None);
    }
    CONTEXT.store(context, Ordering::Release);
    if let Err(error) = install_handler(&action) {
        unsafe { (*context).install_failed.store(1, Ordering::Release); }
        if let Some(binding) = BINDING.get() {
            // The immutable record keeps the attempted identity; no handler is
            // installed and the previously queried default remains in place.
            return error_response(error, Some(binding));
        }
        return error_response(error, None);
    }
    response("ARMED", "INSTALLED", BINDING.get(), true, Some("SIG_DFL"))
}

/// Reports current handler ownership without reinstalling or signalling.
pub fn signal_trace_status() -> String {
    let _lock = ARM_LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    let Some(binding) = BINDING.get() else {
        return unsupported_response("NOT_ARMED", None, None);
    };
    if unsafe { (*binding.context).install_failed.load(Ordering::Acquire) != 0 } {
        return error_response("SIGINT handler was not armed", Some(binding));
    }
    match current_handler_is_ours() {
        Ok(true) => response("ARMED", "HANDLER_INSTALLED", Some(binding), true, Some("SIG_DFL")),
        Ok(false) => unsupported_response("HANDLER_NO_LONGER_INSTALLED", Some(binding), Some("SIG_DFL")),
        Err(error) => error_response(format!("SIGINT disposition query failed: {error}"), Some(binding)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::symlink;
    use std::os::unix::process::ExitStatusExt;
    use std::process::{Child, Command, Stdio};
    use std::thread;
    use std::time::Duration;

    extern "C" fn simple_custom_handler(_signal: libc::c_int) {}
    extern "C" fn siginfo_custom_handler(_signal: libc::c_int, _info: *mut libc::siginfo_t, _ctx: *mut libc::c_void) {}

    fn boot_id() -> String { read_boot_id().unwrap() }

    fn fixture_path(run_id: &str) -> (PathBuf, String) {
        let root = std::env::temp_dir().join(format!("forever-sigint-fixture-{}-{run_id}", std::process::id()));
        fs::create_dir_all(root.join("runs")).unwrap();
        let _ = fs::set_permissions(root.join("runs"), std::os::unix::fs::PermissionsExt::from_mode(0o700));
        let output = root.join("runs").join(format!("{run_id}-sigint.bin"));
        (output, root.to_string_lossy().into_owned())
    }

    fn child_fixture(mode: &str, output: &Path, run_id: &str) -> Child {
        let child = Command::new(std::env::current_exe().unwrap())
            .args(["--exact", "signal_trace::tests::signal_trace_fixture_entry", "--nocapture"])
            .env("FOREVER_SIGNAL_TRACE_FIXTURE", mode)
            .env("FOREVER_SIGNAL_TRACE_OUTPUT", output)
            .env("FOREVER_SIGNAL_TRACE_RUN", run_id)
            .stdout(Stdio::piped())
            .spawn()
            .unwrap();
        thread::sleep(Duration::from_millis(150));
        child
    }

    fn reap(child: Child) -> std::process::Output {
        child.wait_with_output().unwrap()
    }

    #[test]
    fn signal_trace_fixture_entry() {
        let Ok(mode) = std::env::var("FOREVER_SIGNAL_TRACE_FIXTURE") else { return };
        let output = PathBuf::from(std::env::var("FOREVER_SIGNAL_TRACE_OUTPUT").unwrap());
        let run_id = std::env::var("FOREVER_SIGNAL_TRACE_RUN").unwrap();
        let pid = unsafe { libc::getpid() } as i32;
        let ticks = read_self_start_ticks().unwrap();
        let boot = boot_id();
        unsafe {
            let mut action: libc::sigaction = std::mem::zeroed();
            libc::sigemptyset(&mut action.sa_mask);
            if mode == "ignore" {
                action.sa_sigaction = libc::SIG_IGN;
            } else if mode == "custom" {
                action.sa_sigaction = simple_custom_handler as *const () as usize;
            } else if mode == "siginfo-custom" {
                action.sa_sigaction = siginfo_custom_handler as *const () as usize;
                action.sa_flags = libc::SA_SIGINFO as _;
            } else {
                action.sa_sigaction = libc::SIG_DFL;
            }
            assert_eq!(libc::sigaction(libc::SIGINT, &action, std::ptr::null_mut()), 0);
        }
        let first = arm_signal_trace(output.to_str().unwrap(), &run_id, &boot, pid, ticks as i64);
        let mut line = first.clone();
        if mode == "repeat" {
            let second = arm_signal_trace(output.to_str().unwrap(), &run_id, &boot, pid, ticks as i64);
            line = format!("first={first} second={second} status={}", signal_trace_status());
        } else if mode == "status-change" {
            unsafe {
                let mut action: libc::sigaction = std::mem::zeroed();
                libc::sigemptyset(&mut action.sa_mask);
                action.sa_sigaction = libc::SIG_DFL;
                assert_eq!(libc::sigaction(libc::SIGINT, &action, std::ptr::null_mut()), 0);
            }
            line = format!("first={first} status={}", signal_trace_status());
        } else if mode == "fork-guard" {
            let child = unsafe { libc::fork() };
            assert!(child >= 0);
            if child == 0 {
                unsafe {
                    libc::syscall(libc::SYS_tgkill, libc::getpid(), libc::syscall(libc::SYS_gettid), libc::SIGINT);
                    libc::_exit(99);
                }
            }
            let mut status = 0;
            unsafe { libc::waitpid(child, &mut status, 0); }
            line = format!("first={first} fork_signaled={} fork_signal={}", libc::WIFSIGNALED(status), libc::WTERMSIG(status));
        }
        println!("{line}");
        use std::io::Write as _;
        std::io::stdout().flush().unwrap();
        if mode == "fork-guard" {
            return;
        }
        if mode == "ignore" || mode == "custom" || mode == "siginfo-custom" {
            thread::sleep(Duration::from_secs(10));
        } else {
            thread::sleep(Duration::from_secs(10));
        }
    }

    #[test]
    fn signal_trace_invalid_inputs_and_private_path_fail_closed() {
        let run_id = format!("invalid-{}", std::process::id());
        let (output, root) = fixture_path(&run_id);
        let pid = unsafe { libc::getpid() } as i32;
        let ticks = read_self_start_ticks().unwrap() as i64;
        let boot = boot_id();
        for (path, run, expected_pid, expected_ticks, expected_boot) in [
            (output.to_string_lossy().into_owned(), "bad/run", pid, ticks, boot.clone()),
            (output.to_string_lossy().into_owned(), run_id.as_str(), pid + 1, ticks, boot.clone()),
            (output.to_string_lossy().into_owned(), run_id.as_str(), pid, ticks + 1, boot.clone()),
            (output.to_string_lossy().into_owned(), run_id.as_str(), pid, ticks, "00000000-0000-0000-0000-000000000000".to_string()),
        ] {
            let result = arm_signal_trace(&path, run, &expected_boot, expected_pid, expected_ticks);
            assert!(result.contains("\"status\":\"ERROR\""), "{result}");
            assert_eq!(action_disposition(&current_action().unwrap()), Disposition::Default);
        }
        let existing = output.to_string_lossy().into_owned();
        fs::write(&existing, b"existing").unwrap();
        let result = arm_signal_trace(&existing, &run_id, &boot, pid, ticks);
        assert!(result.contains("\"status\":\"ERROR\""), "{result}");
        fs::remove_file(existing).unwrap();
        let missing_parent = Path::new(&root).join("missing").join(format!("{run_id}-sigint.bin"));
        let result = arm_signal_trace(missing_parent.to_str().unwrap(), &run_id, &boot, pid, ticks);
        assert!(result.contains("\"status\":\"ERROR\""), "{result}");
        fs::create_dir_all(Path::new(&root).join("real")).unwrap();
        symlink(Path::new(&root).join("real"), Path::new(&root).join("runs-link")).unwrap();
        let symlink_path = Path::new(&root).join("runs-link").join(format!("{run_id}-sigint.bin"));
        let result = arm_signal_trace(symlink_path.to_str().unwrap(), &run_id, &boot, pid, ticks);
        assert!(result.contains("\"status\":\"ERROR\""), "{result}");
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn signal_trace_default_sender_layout_and_repeat_status() {
        let run_id = format!("default-{}", std::process::id());
        let (output, root) = fixture_path(&run_id);
        let child = child_fixture("repeat", &output, &run_id);
        let child_pid = child.id() as i32;
        unsafe { libc::kill(child_pid, libc::SIGINT); }
        let result = reap(child);
        assert!(result.status.signal() == Some(libc::SIGINT), "child status: {:?}, stdout: {:?}", result.status, result.stdout);
        let stdout = String::from_utf8_lossy(&result.stdout);
        assert!(stdout.contains("ALREADY_BOUND"), "{stdout}");
        let bytes = fs::read(&output).unwrap();
        assert_eq!(bytes.len(), HEADER_BYTES + EVENT_BYTES);
        assert_eq!(&bytes[0..8], b"WFSIG001");
        assert_eq!(u32::from_le_bytes(bytes[8..12].try_into().unwrap()), 1);
        assert_eq!(u32::from_le_bytes(bytes[12..16].try_into().unwrap()), HEADER_BYTES as u32);
        assert_eq!(i32::from_le_bytes(bytes[16..20].try_into().unwrap()), child_pid);
        assert_eq!(u32::from_le_bytes(bytes[56..60].try_into().unwrap()), run_id.len() as u32);
        assert_eq!(&bytes[64..64 + run_id.len()], run_id.as_bytes());
        assert!(bytes[64 + run_id.len()..192].iter().all(|byte| *byte == 0));
        let boot = boot_id();
        assert_eq!(&bytes[192..228], boot.as_bytes());
        assert!(bytes[228..HEADER_BYTES].iter().all(|byte| *byte == 0));
        let event = &bytes[HEADER_BYTES..];
        assert_eq!(&event[0..8], b"WFSIGEV1");
        assert_eq!(i32::from_le_bytes(event[16..20].try_into().unwrap()), libc::SIGINT);
        assert_eq!(i32::from_le_bytes(event[24..28].try_into().unwrap()), child_pid);
        assert_eq!(i32::from_le_bytes(event[32..36].try_into().unwrap()), std::process::id() as i32);
        assert_eq!(u32::from_le_bytes(event[56..60].try_into().unwrap()), 3);
        assert!(event[60..EVENT_BYTES].iter().all(|byte| *byte == 0));
        assert!(read_u64(event, 40) >= read_u64(&bytes, 32));
        assert!(read_u64(event, 48) > 0);
        fs::remove_dir_all(root).unwrap();

        let run_id = format!("status-change-{}", std::process::id());
        let (output, root) = fixture_path(&run_id);
        let child = child_fixture("status-change", &output, &run_id);
        unsafe { libc::kill(child.id() as i32, libc::SIGINT); }
        let result = reap(child);
        assert_eq!(result.status.signal(), Some(libc::SIGINT));
        assert!(String::from_utf8_lossy(&result.stdout).contains("HANDLER_NO_LONGER_INSTALLED"));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn signal_trace_unsupported_dispositions_remain_alive_and_fork_guard_is_default() {
        for mode in ["ignore", "custom", "siginfo-custom"] {
            let run_id = format!("{mode}-{}", std::process::id());
            let (output, root) = fixture_path(&run_id);
            let mut child = child_fixture(mode, &output, &run_id);
            let child_pid = child.id() as i32;
            unsafe { libc::kill(child_pid, libc::SIGINT); }
            thread::sleep(Duration::from_millis(100));
            assert!(child.try_wait().unwrap().is_none(), "{mode} disposition did not remain alive");
            unsafe { libc::kill(child_pid, libc::SIGTERM); }
            let result = reap(child);
            assert_eq!(result.status.signal(), Some(libc::SIGTERM), "{mode}: {:?}", result.status);
            assert!(String::from_utf8_lossy(&result.stdout).contains("\"status\":\"UNSUPPORTED\""), "{mode}: {:?}", result.stdout);
            assert!(!output.exists(), "unsupported {mode} created evidence");
            fs::remove_dir_all(root).unwrap();
        }
        let run_id = format!("fork-{}", std::process::id());
        let (output, root) = fixture_path(&run_id);
        let child = child_fixture("fork-guard", &output, &run_id);
        let result = reap(child);
        assert!(result.status.success(), "fork guard parent: {:?}, {:?}", result.status, result.stdout);
        let stdout = String::from_utf8_lossy(&result.stdout);
        assert!(stdout.contains("fork_signaled=true") && stdout.contains("fork_signal=2"), "{stdout}");
        assert_eq!(fs::read(&output).unwrap().len(), HEADER_BYTES);
        fs::remove_dir_all(root).unwrap();
    }
}
