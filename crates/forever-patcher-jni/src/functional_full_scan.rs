//! Apply-eligible progressive scan for `codeCaptureEnabled=false`.
//!
//! Observed-maps-consistent multi-time pass semantics (not a point-in-time
//! atomic snapshot: identical maps before and after never prove the contents
//! stayed identical, only that no mapping change was observed):
//! - Each pass snapshots only mappings accepted by the existing writer's
//!   anonymous private RW/RWX predicate, in address order. Other readable
//!   mappings cannot authorize application and are not a fallback traversal.
//!   This is a scoped read, never permission to apply or global uniqueness.
//! - A pass is reported complete only when the maps are identical at pass
//!   start and at pass end, no chunk read failed, the exact target identity
//!   matches before and after, the lease stays current, the candidate list
//!   never exceeded its bound, and the per-poll budget stays complete.
//!   Anything else is `INCOMPLETE`, never `NOT_FOUND`; no result claims
//!   global absence or uniqueness, only what this pass observed.
//! - Budget exhaustion or cancellation never advances the cursor and never
//!   records a gap: the unread chunk is preserved so the next poll re-reads
//!   the same slice. The lease-scoped cursor otherwise resumes at
//!   `chunk_end - overlap` so an anchor or a 480-byte block straddling the
//!   poll boundary is still observed in the next poll (deduplicated via
//!   `seen`). Read failures from the kernel advance by one bounded 4 KiB page
//!   with a recorded whole-pass gap, never retrying the same address and
//!   never silently skipping to complete.
//! - Candidates require the full 480-byte block to be re-read and strictly
//!   validated (all 12 LE ids, flags, paddings, 11 known keys plus group-8
//!   known-or-replacement). A block re-read that fails for budget/cancel
//!   reasons preserves the current chunk and stops for a rescan; one that
//!   fails for a real I/O reason persists a gap for the whole pass. Multiple
//!   validated blocks are all retained up to the 64-candidate bound; a
//!   partial pass never implies uniqueness and never authorizes a write.

use super::{Budget, Lease, ReadOnlyMemory, Target};
use forever_patcher::MemoryAccess;
use std::collections::BTreeSet;
use std::sync::atomic::Ordering;

const PAGE_ADVANCE: u64 = 4096;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum BackingKind {
    Anonymous,
    File,
    Device,
    Pseudo,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) struct SnapshotRegion {
    pub start: u64,
    pub end: u64,
    pub perms: [u8; 4],
    pub offset: u64,
    pub dev_major: u64,
    pub dev_minor: u64,
    pub inode: u64,
    pub kind: BackingKind,
}

pub(super) struct FullScanState {
    pub target: Target,
    pub regions: Vec<SnapshotRegion>,
    pub next_region: usize,
    pub next_offset: u64,
    pub cumulative_bytes: u64,
    pub cumulative_gaps: u64,
    pub cumulative_regions_done: u64,
    pub maps_changed: bool,
    pub candidate_capped: bool,
    pub found: Vec<forever_patcher::FoundStore>,
    pub seen: BTreeSet<u64>,
    pub resumed_poll: bool,
}

#[derive(Debug)]
pub(super) struct FullScanCoverage {
    pub regions_total: u64,
    pub regions_visited: u64,
    pub bytes_total: u64,
    pub bytes_read_cumulative: u64,
    pub read_gaps: u64,
    pub maps_changed: bool,
    pub maps_complete: bool,
    pub pass_complete: bool,
    pub budget_exhausted: bool,
    pub candidate_capped: bool,
    pub resumed: bool,
}

pub(super) enum FullScanOutcome {
    Complete,
    Incomplete { kind: &'static str, detail: String },
    Cancelled { detail: String },
    IdentityFailure { detail: String },
}

pub(super) struct FullScanResult {
    pub outcome: FullScanOutcome,
    pub coverage: FullScanCoverage,
    pub found: Vec<forever_patcher::FoundStore>,
    pub snapshot: Vec<SnapshotRegion>,
}

pub(super) enum IdentityVerdict {
    Match,
    Mismatch(String),
    CheckFailed(String),
}

pub(super) enum ScanLoopStop {
    Drained,
    StoppedBudget,
    StoppedCancel,
    StoppedIdentity(String),
}

fn classify_path(path: Option<&str>, device: &str, inode: u64) -> BackingKind {
    match path {
        None => BackingKind::Anonymous,
        Some(path) if path.starts_with("[anon:") && inode == 0 && device == "00:00" => BackingKind::Anonymous,
        Some(path) if path.starts_with('[') => BackingKind::Pseudo,
        Some(path) if path == "/dev" || path.starts_with("/dev/") => BackingKind::Device,
        Some(path) if path.starts_with('/') => BackingKind::File,
        Some(_) => BackingKind::Anonymous,
    }
}

/// All Linux maps lines whose permission field starts with `r` are included,
/// regardless of `w`/`x`, `p`/`s`, anonymous versus file-backed, or path
/// (including readable `/dev` mappings, whose kernel read failure is recorded
/// as a gap rather than excluded upfront). Only non-readable mappings
/// (`perms[0] != 'r'`) are excluded. The retained offset/device/inode/kind
/// quadruple is the bounded backing identity: the same virtual range backed
/// by different file data compares different, without retaining any raw path.
/// Malformed metadata is a maps error and never silent exclusion.
pub(super) fn parse_full_readable_maps(text: &str) -> Result<Vec<SnapshotRegion>, String> {
    if text.is_empty() || !text.ends_with('\n') {
        return Err("maps file is empty or truncated".to_string());
    }
    let mut regions = Vec::new();
    for line in text.lines() {
        let mut columns = line.split_whitespace();
        let Some(range) = columns.next() else { return Err("maps line has no range".to_string()) };
        let Some(permissions) = columns.next() else { return Err("maps line has no permissions".to_string()) };
        let Some(offset_text) = columns.next() else { return Err("maps line has no offset".to_string()) };
        let Some(device) = columns.next() else { return Err("maps line has no device".to_string()) };
        let Some(inode_text) = columns.next() else { return Err("maps line has no inode".to_string()) };
        let (major_text, minor_text) = device
            .split_once(':')
            .ok_or_else(|| "maps line has malformed metadata".to_string())?;
        let valid_device = !major_text.is_empty()
            && !minor_text.is_empty()
            && u64::from_str_radix(major_text, 16).is_ok()
            && u64::from_str_radix(minor_text, 16).is_ok();
        let permission_bytes = permissions.as_bytes();
        let valid_permissions = permission_bytes.len() == 4
            && matches!(permission_bytes[0], b'r' | b'-')
            && matches!(permission_bytes[1], b'w' | b'-')
            && matches!(permission_bytes[2], b'x' | b'-')
            && matches!(permission_bytes[3], b'p' | b's');
        let (Ok(offset), Ok(inode)) = (u64::from_str_radix(offset_text, 16), inode_text.parse::<u64>()) else {
            return Err("maps line has malformed metadata".to_string());
        };
        if !valid_permissions || !valid_device {
            return Err("maps line has malformed metadata".to_string());
        }
        let Some((start_text, end_text)) = range.split_once('-') else {
            return Err("maps line has malformed range".to_string());
        };
        let (Ok(start), Ok(end)) = (u64::from_str_radix(start_text, 16), u64::from_str_radix(end_text, 16)) else {
            return Err("maps line has malformed range".to_string());
        };
        if end <= start {
            return Err("maps line has an empty range".to_string());
        }
        if permission_bytes[0] != b'r' {
            continue;
        }
        let mut perms = [0u8; 4];
        perms.copy_from_slice(permission_bytes);
        regions.push(SnapshotRegion {
            start,
            end,
            perms,
            offset,
            dev_major: u64::from_str_radix(major_text, 16).unwrap_or(0),
            dev_minor: u64::from_str_radix(minor_text, 16).unwrap_or(0),
            inode,
            kind: classify_path(columns.next(), device, inode),
        });
        if regions.len() > super::MAX_REGIONS {
            return Err("full readable region bound exceeded".to_string());
        }
    }
    Ok(regions)
}

fn bytes_total(regions: &[SnapshotRegion]) -> u64 {
    regions.iter().map(|region| region.end.saturating_sub(region.start)).fold(0u64, u64::saturating_add)
}

pub(super) fn validate_strict_block(bytes: &[u8], contract: &forever_patcher::KeyStoreContract) -> Option<bool> {
    if bytes.len() != contract.block_size() {
        return None;
    }
    let mut target_is_new = false;
    for index in 0..contract.entry_count {
        let base = index * contract.entry_size;
        let id = u32::from_le_bytes(bytes[base..base + 4].try_into().ok()?);
        if id != (index as u32) + 1 {
            return None;
        }
        if bytes[base + 4 + forever_patcher::KEY_LEN] != contract.expected_flags[index] {
            return None;
        }
        let pad: [u8; 3] = bytes[base + 4 + forever_patcher::KEY_LEN + 1..base + contract.entry_size]
            .try_into()
            .ok()?;
        if !contract.accepts_padding(&pad) {
            return None;
        }
        let key = &bytes[base + 4..base + 4 + forever_patcher::KEY_LEN];
        if index + 1 == contract.target_group {
            if key == contract.new_key {
                target_is_new = true;
            } else if key != contract.known_keys[index] {
                return None;
            }
        } else if key != contract.known_keys[index] {
            return None;
        }
    }
    Some(target_is_new)
}

// Leave the shared all-readable parser unchanged for diagnostic consumers.
// Use the actual writer's predicate, not a second definition of eligibility.
fn retain_apply_eligible(regions: &mut Vec<SnapshotRegion>) {
    regions.retain(super::is_writable_anon_private);
    regions.sort_by_key(|region| region.start);
}

fn rebase_onto_fresh(saved_offset: u64, fresh: &[SnapshotRegion]) -> (usize, u64) {
    for (index, region) in fresh.iter().enumerate() {
        if saved_offset < region.end {
            return (index, saved_offset.max(region.start));
        }
    }
    (fresh.len(), saved_offset)
}

/// A changed mapping invalidates every previously validated candidate: the
/// same virtual address may now be backed by different data. Keep the cursor
/// progress by address and the changed flag so the pass reports `INCOMPLETE` rather
/// than a false absence, but never report stale candidates from old maps.
fn adopt_fresh_after_change(state: &mut FullScanState, fresh: Vec<SnapshotRegion>, target: &Target) {
    // Insertions behind this cursor wait until the next pass; repeatedly
    // changing maps must not restart scanning and starve forward progress.
    let (next_region, next_offset) = match state.regions.get(state.next_region) {
        Some(_) => rebase_onto_fresh(state.next_offset, &fresh),
        None => (fresh.len(), state.next_offset),
    };
    state.target = target.clone();
    state.regions = fresh;
    state.next_region = next_region;
    state.next_offset = next_offset;
    state.maps_changed = true;
    state.found.clear();
    state.seen.clear();
    state.candidate_capped = false;
    state.resumed_poll = true;
}

fn complete_eligible(state: &FullScanState, budget: &Budget) -> bool {
    state.cumulative_gaps == 0
        && !state.maps_changed
        && !state.candidate_capped
        && budget.complete()
}

pub(super) fn coverage_json(coverage: &FullScanCoverage, validated_store_count: usize) -> serde_json::Value {
    serde_json::json!({
        "mode": "apply-eligible",
        "scope": "APPLY_ELIGIBLE_REGIONS",
        "regionsTotal": coverage.regions_total,
        "regionsVisited": coverage.regions_visited,
        "bytesTotal": coverage.bytes_total,
        "bytesReadCumulative": coverage.bytes_read_cumulative,
        "readGaps": coverage.read_gaps,
        "mapsChanged": coverage.maps_changed,
        "mapsComplete": coverage.maps_complete,
        "passComplete": coverage.pass_complete,
        "budgetExhausted": coverage.budget_exhausted,
        "candidateCapped": coverage.candidate_capped,
        "resumed": coverage.resumed,
        "validatedStoreCount": validated_store_count,
        "readOnly": true,
        "writeAuthorized": false,
    })
}

fn advance_page(state: &mut FullScanState, regions: &[SnapshotRegion]) {
    let end = regions[state.next_region].end;
    let remaining = end.saturating_sub(state.next_offset);
    let step = PAGE_ADVANCE.min(remaining);
    if step == 0 {
        state.next_region += 1;
        if state.next_region < regions.len() {
            state.next_offset = regions[state.next_region].start;
        }
    } else {
        state.next_offset = state.next_offset.saturating_add(step);
    }
}

/// The production chunk loop, shared by the real poll path and the focused
/// tests below. Reads at most `MAX_READ_CHUNK` per step so no region or heap
/// is ever pulled into memory at once; the chunk-to-chunk overlap keeps an
/// anchor or 480-byte block straddling a boundary observable.
fn scan_loop<M: MemoryAccess>(
    memory: &M,
    budget: &Budget,
    contract: &forever_patcher::KeyStoreContract,
    regions: &[SnapshotRegion],
    state: &mut FullScanState,
    cancelled: &std::sync::atomic::AtomicBool,
    need_resumed_recheck: bool,
    check_identity: &dyn Fn() -> IdentityVerdict,
) -> ScanLoopStop {
    let block_size = contract.block_size() as u64;
    let entry_size = contract.entry_size as u64;
    let anchor_offset = 4 + (contract.anchor_group as u64).saturating_sub(1) * entry_size;
    let overlap = (block_size + forever_patcher::KEY_LEN as u64).min(super::MAX_READ_CHUNK as u64 - 1);
    let align = contract.scan_alignment.max(1);
    let anchor = contract.anchor_key();
    let mut recheck_pending = need_resumed_recheck;
    loop {
        if cancelled.load(Ordering::Acquire) {
            return ScanLoopStop::StoppedCancel;
        }
        if budget.check().is_err() {
            return ScanLoopStop::StoppedBudget;
        }
        if state.next_region >= regions.len() {
            return ScanLoopStop::Drained;
        }
        let region = &regions[state.next_region];
        if state.next_offset >= region.end {
            state.next_region += 1;
            if state.next_region < regions.len() {
                state.next_offset = regions[state.next_region].start;
            }
            state.cumulative_regions_done += 1;
            continue;
        }
        let chunk_end = state.next_offset.saturating_add(super::MAX_READ_CHUNK as u64).min(region.end);
        if chunk_end <= state.next_offset {
            state.cumulative_gaps += 1;
            state.next_region += 1;
            if state.next_region < regions.len() {
                state.next_offset = regions[state.next_region].start;
            }
            budget.incomplete.store(true, Ordering::Relaxed);
            continue;
        }
        let length = (chunk_end - state.next_offset) as usize;
        let mut chunk = vec![0u8; length];
        let chunk_start = state.next_offset;
        match memory.read(chunk_start, &mut chunk) {
            Ok(()) => {
                state.cumulative_bytes = state.cumulative_bytes.saturating_add(chunk.len() as u64);
                let mut chunk_block_gap = false;
                if chunk.len() >= forever_patcher::KEY_LEN {
                    let last = chunk.len() - forever_patcher::KEY_LEN;
                    // Keep absolute alignment even when an overlapping chunk
                    // starts unaligned. Skip non-candidates rather than doing
                    // a modulo and a full anchor comparison at every byte.
                    let remainder = chunk_start % align;
                    let first = if remainder == 0 { 0 } else { align - remainder };
                    let step = usize::try_from(align).unwrap_or(usize::MAX);
                    for offset in (first..=last as u64).step_by(step) {
                        let position = offset as usize;
                        let Some(absolute) = chunk_start.checked_add(offset) else { continue };
                        if chunk[position] != anchor[0] || chunk[position..position + 4] != anchor[..4] {
                            continue;
                        }
                        if chunk[position..position + forever_patcher::KEY_LEN] != anchor[..] {
                            continue;
                        }
                        let Some(block) = absolute.checked_sub(anchor_offset) else { continue };
                        if state.seen.contains(&block) {
                            continue;
                        }
                        if state.found.len() >= super::MAX_CANDIDATES {
                            state.candidate_capped = true;
                            budget.incomplete.store(true, Ordering::Relaxed);
                            continue;
                        }
                        let mut block_bytes = vec![0u8; contract.block_size()];
                        match memory.read(block, &mut block_bytes) {
                            Ok(()) => {
                                if let Some(already) = validate_strict_block(&block_bytes, contract) {
                                    if state.seen.insert(block) {
                                        state.found.push(forever_patcher::FoundStore { addr: block, already_patched: already });
                                    }
                                }
                            }
                            Err(error) => {
                                if cancelled.load(Ordering::Acquire) || error.kind() == std::io::ErrorKind::Interrupted {
                                    return ScanLoopStop::StoppedCancel;
                                }
                                if error.kind() == std::io::ErrorKind::TimedOut || budget.exhausted.load(Ordering::Relaxed) {
                                    return ScanLoopStop::StoppedBudget;
                                }
                                if error.kind() == std::io::ErrorKind::PermissionDenied {
                                    match check_identity() {
                                        IdentityVerdict::Match => chunk_block_gap = true,
                                        IdentityVerdict::Mismatch(detail) => return ScanLoopStop::StoppedIdentity(detail),
                                        IdentityVerdict::CheckFailed(detail) => {
                                            if cancelled.load(Ordering::Acquire) {
                                                return ScanLoopStop::StoppedCancel;
                                            }
                                            if budget.exhausted.load(Ordering::Relaxed) {
                                                return ScanLoopStop::StoppedBudget;
                                            }
                                            return ScanLoopStop::StoppedIdentity(detail);
                                        }
                                    }
                                } else {
                                    chunk_block_gap = true;
                                }
                            }
                        }
                    }
                }
                if chunk_block_gap {
                    state.cumulative_gaps += 1;
                }
                if recheck_pending {
                    recheck_pending = false;
                    match check_identity() {
                        IdentityVerdict::Match => {}
                        IdentityVerdict::Mismatch(detail) => return ScanLoopStop::StoppedIdentity(detail),
                        IdentityVerdict::CheckFailed(detail) => {
                            if cancelled.load(Ordering::Acquire) {
                                return ScanLoopStop::StoppedCancel;
                            }
                            if budget.exhausted.load(Ordering::Relaxed) {
                                return ScanLoopStop::StoppedBudget;
                            }
                            return ScanLoopStop::StoppedIdentity(detail);
                        }
                    }
                }
                if chunk_end == region.end {
                    state.next_region += 1;
                    if state.next_region < regions.len() {
                        state.next_offset = regions[state.next_region].start;
                    }
                    state.cumulative_regions_done += 1;
                } else {
                    state.next_offset = chunk_end.saturating_sub(overlap);
                }
                if budget.exhausted.load(Ordering::Relaxed) {
                    return ScanLoopStop::StoppedBudget;
                }
            }
            Err(error) => {
                if cancelled.load(Ordering::Acquire) || error.kind() == std::io::ErrorKind::Interrupted {
                    return ScanLoopStop::StoppedCancel;
                }
                if error.kind() == std::io::ErrorKind::TimedOut || budget.exhausted.load(Ordering::Relaxed) {
                    return ScanLoopStop::StoppedBudget;
                }
                if error.kind() == std::io::ErrorKind::PermissionDenied {
                    match check_identity() {
                        IdentityVerdict::Match => {
                            state.cumulative_gaps += 1;
                            advance_page(state, regions);
                        }
                        IdentityVerdict::Mismatch(detail) => return ScanLoopStop::StoppedIdentity(detail),
                        IdentityVerdict::CheckFailed(detail) => {
                            if cancelled.load(Ordering::Acquire) {
                                return ScanLoopStop::StoppedCancel;
                            }
                            if budget.exhausted.load(Ordering::Relaxed) {
                                return ScanLoopStop::StoppedBudget;
                            }
                            return ScanLoopStop::StoppedIdentity(detail);
                        }
                    }
                } else {
                    state.cumulative_gaps += 1;
                    advance_page(state, regions);
                }
            }
        }
    }
}

pub(super) fn run_full_scan(
    lease: &Lease,
    target: &Target,
    memory: &ReadOnlyMemory,
) -> FullScanResult {
    let finish_incomplete_empty = |kind: &'static str, detail: String, coverage: FullScanCoverage, found: Vec<forever_patcher::FoundStore>| FullScanResult {
        outcome: FullScanOutcome::Incomplete { kind, detail },
        coverage,
        found,
        snapshot: Vec::new(),
    };
    if !lease.current() {
        let coverage = FullScanCoverage {
            regions_total: 0,
            regions_visited: 0,
            bytes_total: 0,
            bytes_read_cumulative: 0,
            read_gaps: 0,
            maps_changed: false,
            maps_complete: true,
            pass_complete: false,
            budget_exhausted: false,
            candidate_capped: false,
            resumed: false,
        };
        return FullScanResult {
            outcome: FullScanOutcome::Cancelled { detail: "lease cancelled before full readable scan".to_string() },
            coverage,
            found: Vec::new(),
            snapshot: Vec::new(),
        };
    }
    let budget: &Budget = &memory.budget;
    let maps_bytes = match super::ProcFs::read_file(memory.target.pid, "maps", super::MAX_MAPS_FILE, budget) {
        Ok(bytes) => bytes,
        Err(error) => {
            let coverage = FullScanCoverage {
                regions_total: 0,
                regions_visited: 0,
                bytes_total: 0,
                bytes_read_cumulative: 0,
                read_gaps: 1,
                maps_changed: false,
                maps_complete: false,
                pass_complete: false,
                budget_exhausted: budget.exhausted.load(Ordering::Relaxed),
                candidate_capped: false,
                resumed: false,
            };
            return finish_incomplete_empty("read_coverage", format!("full readable maps unreadable: {error}"), coverage, Vec::new());
        }
    };
    let maps_text = match std::str::from_utf8(&maps_bytes) {
        Ok(text) => text,
        Err(error) => {
            budget.incomplete.store(true, Ordering::Relaxed);
            let coverage = FullScanCoverage {
                regions_total: 0,
                regions_visited: 0,
                bytes_total: 0,
                bytes_read_cumulative: 0,
                read_gaps: 1,
                maps_changed: false,
                maps_complete: false,
                pass_complete: false,
                budget_exhausted: budget.exhausted.load(Ordering::Relaxed),
                candidate_capped: false,
                resumed: false,
            };
            return finish_incomplete_empty("read_coverage", format!("full readable maps are not UTF-8: {error}"), coverage, Vec::new());
        }
    };
    let mut fresh = match parse_full_readable_maps(maps_text) {
        Ok(regions) => regions,
        Err(detail) => {
            budget.incomplete.store(true, Ordering::Relaxed);
            let coverage = FullScanCoverage {
                regions_total: 0,
                regions_visited: 0,
                bytes_total: 0,
                bytes_read_cumulative: 0,
                read_gaps: 1,
                maps_changed: false,
                maps_complete: false,
                pass_complete: false,
                budget_exhausted: budget.exhausted.load(Ordering::Relaxed),
                candidate_capped: false,
                resumed: false,
            };
            return finish_incomplete_empty("read_coverage", detail, coverage, Vec::new());
        }
    };
    retain_apply_eligible(&mut fresh);
    budget.regions_full_snapshot(fresh.len(), &bytes_total(&fresh));
    let finish_incomplete = |kind: &'static str, detail: String, coverage: FullScanCoverage, found: Vec<forever_patcher::FoundStore>| FullScanResult {
        outcome: FullScanOutcome::Incomplete { kind, detail },
        coverage,
        found,
        snapshot: fresh.clone(),
    };

    let mut guard = match lease.full_scan.lock() {
        Ok(guard) => guard,
        Err(_) => {
            let coverage = FullScanCoverage {
                regions_total: fresh.len() as u64,
                regions_visited: 0,
                bytes_total: bytes_total(&fresh),
                bytes_read_cumulative: 0,
                read_gaps: 1,
                maps_changed: false,
                maps_complete: true,
                pass_complete: false,
                budget_exhausted: budget.exhausted.load(Ordering::Relaxed),
                candidate_capped: false,
                resumed: false,
            };
            return finish_incomplete_empty("lease_mutex", "full scan state mutex poisoned".to_string(), coverage, Vec::new());
        }
    };

    let resumed;
    let mut maps_changed = false;
    if let Some(state) = guard.as_ref() {
        if super::target_identity_matches(&state.target, target) {
            resumed = true;
            if state.regions != fresh {
                maps_changed = true;
                if let Some(state) = guard.as_mut() {
                    adopt_fresh_after_change(state, fresh.clone(), target);
                }
            } else {
                maps_changed = state.maps_changed;
                if let Some(state) = guard.as_mut() {
                    state.resumed_poll = true;
                }
            }
        } else {
            resumed = false;
            let start = fresh.first().map_or(0, |region| region.start);
            *guard = Some(FullScanState {
                target: target.clone(),
                regions: fresh.clone(),
                next_region: 0,
                next_offset: start,
                cumulative_bytes: 0,
                cumulative_gaps: 0,
                cumulative_regions_done: 0,
                maps_changed: false,
                candidate_capped: false,
                found: Vec::new(),
                seen: BTreeSet::new(),
                resumed_poll: false,
            });
        }
    } else {
        resumed = false;
        let start = fresh.first().map_or(0, |region| region.start);
        *guard = Some(FullScanState {
            target: target.clone(),
            regions: fresh.clone(),
            next_region: 0,
            next_offset: start,
            cumulative_bytes: 0,
            cumulative_gaps: 0,
            cumulative_regions_done: 0,
            maps_changed: false,
            candidate_capped: false,
            found: Vec::new(),
            seen: BTreeSet::new(),
            resumed_poll: false,
        });
    }

    // Identity before the resumed chunk: the full proof, not only the cheap
    // per-read pin. The per-read pin still guards every chunk below.
    if resumed {
        match super::validate_target(lease, target.pid, budget) {
            Ok(current) if super::target_identity_matches(target, &current) => {}
            Ok(_) => {
                guard.take();
                let coverage = FullScanCoverage {
                    regions_total: fresh.len() as u64,
                    regions_visited: 0,
                    bytes_total: bytes_total(&fresh),
                    bytes_read_cumulative: 0,
                    read_gaps: 0,
                    maps_changed: true,
                    maps_complete: true,
                    pass_complete: false,
                    budget_exhausted: budget.exhausted.load(Ordering::Relaxed),
                    candidate_capped: false,
                    resumed,
                };
                return finish_incomplete("identity", "target identity changed before resumed full scan chunk".to_string(), coverage, Vec::new());
            }
            Err(error) => {
                let cancelled = !lease.current();
                drop(guard);
                let coverage = FullScanCoverage {
                    regions_total: fresh.len() as u64,
                    regions_visited: 0,
                    bytes_total: bytes_total(&fresh),
                    bytes_read_cumulative: 0,
                    read_gaps: 0,
                    maps_changed,
                    maps_complete: true,
                    pass_complete: false,
                    budget_exhausted: budget.exhausted.load(Ordering::Relaxed),
                    candidate_capped: false,
                    resumed,
                };
                if cancelled {
                    return FullScanResult {
                        outcome: FullScanOutcome::Cancelled { detail: error },
                        coverage,
                        found: Vec::new(),
                        snapshot: fresh.clone(),
                    };
                }
                return finish_incomplete("identity", error, coverage, Vec::new());
            }
        }
    }

    let check_identity = || -> IdentityVerdict {
        match super::validate_target(lease, target.pid, budget) {
            Ok(current) if super::target_identity_matches(target, &current) => IdentityVerdict::Match,
            Ok(_) => IdentityVerdict::Mismatch("target identity changed during full readable scan".to_string()),
            Err(error) => IdentityVerdict::CheckFailed(error),
        }
    };
    let stop = {
        let state = guard.as_mut().expect("full scan state initialized above");
        scan_loop(memory, budget, &lease.contract, &fresh, state, &lease.cancelled, resumed, &check_identity)
    };

    match stop {
        ScanLoopStop::StoppedCancel => {
            let coverage = snapshot_coverage(&guard, &fresh, maps_changed, true, resumed, budget);
            let found = guard.as_ref().map_or(Vec::new(), |state| state.found.clone());
            return FullScanResult {
                outcome: FullScanOutcome::Cancelled { detail: "lease cancelled during full readable scan".to_string() },
                coverage,
                found,
                snapshot: fresh.clone(),
            };
        }
        ScanLoopStop::StoppedBudget => {
            let coverage = snapshot_coverage(&guard, &fresh, maps_changed, true, resumed, budget);
            let found = guard.as_ref().map_or(Vec::new(), |state| state.found.clone());
            let kind: &'static str = if budget.permission_errors.load(Ordering::Relaxed) > 0 {
                "permission_denied"
            } else {
                "read_coverage"
            };
            return finish_incomplete(
                kind,
                "full readable scan hit its per-poll budget; resume on the next poll from the saved cursor".to_string(),
                coverage,
                found,
            );
        }
        ScanLoopStop::StoppedIdentity(detail) => {
            let coverage = snapshot_coverage(&guard, &fresh, maps_changed, true, resumed, budget);
            let found = guard.as_ref().map_or(Vec::new(), |state| state.found.clone());
            return FullScanResult {
                outcome: FullScanOutcome::IdentityFailure { detail },
                coverage,
                found,
                snapshot: fresh.clone(),
            };
        }
        ScanLoopStop::Drained => {}
    }

    // The cursor is exhausted: this pass ends here. Validate the tail before
    // deciding between a reportable result and an incomplete one.
    match super::validate_target(lease, target.pid, budget) {
        Ok(final_target) if super::target_identity_matches(target, &final_target) => {}
        Ok(_) => {
            let coverage = snapshot_coverage(&guard, &fresh, true, true, resumed, budget);
            let found = guard.as_ref().map_or(Vec::new(), |state| state.found.clone());
            return FullScanResult {
                outcome: FullScanOutcome::IdentityFailure { detail: "target identity changed after the complete full scan".to_string() },
                coverage,
                found,
                snapshot: fresh.clone(),
            };
        }
        Err(error) => {
            let now_cancelled = !lease.current();
            let coverage = snapshot_coverage(&guard, &fresh, true, true, resumed, budget);
            let found = guard.as_ref().map_or(Vec::new(), |state| state.found.clone());
            if now_cancelled {
                return FullScanResult {
                    outcome: FullScanOutcome::Cancelled { detail: error },
                    coverage,
                    found,
                    snapshot: fresh.clone(),
                };
            }
            return finish_incomplete("identity", error, coverage, found);
        }
    }
    let after_bytes = match super::ProcFs::read_file(target.pid, "maps", super::MAX_MAPS_FILE, budget) {
        Ok(bytes) => bytes,
        Err(error) => {
            let coverage = snapshot_coverage(&guard, &fresh, true, false, resumed, budget);
            let found = guard.as_ref().map_or(Vec::new(), |state| state.found.clone());
            *guard = None;
            return finish_incomplete("read_coverage", format!("full readable maps re-read failed after pass: {error}"), coverage, found);
        }
    };
    let after_text = match std::str::from_utf8(&after_bytes) {
        Ok(text) => text,
        Err(error) => {
            budget.incomplete.store(true, Ordering::Relaxed);
            let coverage = snapshot_coverage(&guard, &fresh, true, false, resumed, budget);
            let found = guard.as_ref().map_or(Vec::new(), |state| state.found.clone());
            *guard = None;
            return finish_incomplete("read_coverage", format!("full readable maps are not UTF-8 after pass: {error}"), coverage, found);
        }
    };
    let mut after = match parse_full_readable_maps(after_text) {
        Ok(regions) => regions,
        Err(detail) => {
            budget.incomplete.store(true, Ordering::Relaxed);
            let coverage = snapshot_coverage(&guard, &fresh, true, false, resumed, budget);
            let found = guard.as_ref().map_or(Vec::new(), |state| state.found.clone());
            *guard = None;
            return finish_incomplete("read_coverage", detail, coverage, found);
        }
    };
    retain_apply_eligible(&mut after);
    // Any observed mapping change (including at this end check) clears stale
    // candidates: same virtual addresses may now be backed by different data.
    // The pass resets deterministically for the next full pass.
    if after != fresh || maps_changed {
        if let Some(state) = guard.as_mut() {
            state.found.clear();
            state.seen.clear();
            state.candidate_capped = false;
        }
        let coverage = snapshot_coverage(&guard, &fresh, true, true, resumed, budget);
        *guard = None;
        return finish_incomplete(
            "read_coverage",
            "full readable maps changed during the pass; no store result authorized".to_string(),
            coverage,
            Vec::new(),
        );
    }
    let eligible = guard.as_ref().map_or(false, |state| complete_eligible(state, budget));
    let capped = guard.as_ref().map_or(false, |state| state.candidate_capped);
    let gaps = guard.as_ref().map_or(0, |state| state.cumulative_gaps);
    let budget_ok = budget.complete();
    if !eligible {
        let coverage = snapshot_coverage(&guard, &fresh, false, true, resumed, budget);
        let found = guard.as_ref().map_or(Vec::new(), |state| state.found.clone());
        *guard = None;
        let kind: &'static str = if capped {
            "candidate_bound"
        } else if !budget_ok && budget.permission_errors.load(Ordering::Relaxed) > 0 {
            "permission_denied"
        } else {
            "read_coverage"
        };
        let detail = if capped {
            "more than 64 validated key stores; pass capped, no uniqueness claim".to_string()
        } else if gaps > 0 {
            "full readable scan had unreadable gaps; no store result authorized".to_string()
        } else {
            "read-only full scan was incomplete; no store result authorized".to_string()
        };
        return finish_incomplete(kind, detail, coverage, found);
    }
    let found = guard.as_ref().map_or(Vec::new(), |state| state.found.clone());
    let coverage = snapshot_coverage(&guard, &fresh, false, true, resumed, budget);
    *guard = None;
    FullScanResult { outcome: FullScanOutcome::Complete, coverage, found, snapshot: fresh.clone() }
}

fn snapshot_coverage(
    guard: &std::sync::MutexGuard<'_, Option<FullScanState>>,
    fresh: &[SnapshotRegion],
    maps_changed: bool,
    maps_complete: bool,
    resumed: bool,
    budget: &Budget,
) -> FullScanCoverage {
    let (visited, bytes_read_cumulative, read_gaps, candidate_capped) = guard.as_ref().map_or((0, 0, 0, false), |state| {
        (state.cumulative_regions_done, state.cumulative_bytes, state.cumulative_gaps, state.candidate_capped)
    });
    FullScanCoverage {
        regions_total: fresh.len() as u64,
        regions_visited: visited,
        bytes_total: bytes_total(fresh),
        bytes_read_cumulative,
        read_gaps,
        maps_changed,
        maps_complete,
        pass_complete: guard.as_ref().is_some_and(|state| state.next_region >= state.regions.len()),
        budget_exhausted: budget.exhausted.load(Ordering::Relaxed),
        candidate_capped,
        resumed,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    fn test_contract() -> forever_patcher::KeyStoreContract {
        static NEXT_ID: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let id = NEXT_ID.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let path = std::env::temp_dir().join(format!("full-scan-contract-{}-{id}", std::process::id()));
        std::fs::write(&path, super::super::CANONICAL_CONTRACT).unwrap();
        let contract = super::super::parse_contract(path.to_str().unwrap()).unwrap();
        std::fs::remove_file(&path).unwrap();
        contract
    }

    fn synthetic_store(contract: &forever_patcher::KeyStoreContract) -> Vec<u8> {
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

    fn dummy_target() -> super::super::Target {
        super::super::Target { pid: 7, start_ticks: 11, ppid: 3, uid4: [5; 4], cmdline: b"A:\\WowB-ARM64.exe\0".to_vec() }
    }

    fn dummy_state(regions: Vec<SnapshotRegion>) -> FullScanState {
        let start = regions.first().map_or(0, |region| region.start);
        FullScanState {
            target: dummy_target(),
            regions,
            next_region: 0,
            next_offset: start,
            cumulative_bytes: 0,
            cumulative_gaps: 0,
            cumulative_regions_done: 0,
            maps_changed: false,
            candidate_capped: false,
            found: Vec::new(),
            seen: BTreeSet::new(),
            resumed_poll: false,
        }
    }

    fn readable_region(start: u64, end: u64) -> SnapshotRegion {
        SnapshotRegion { start, end, perms: *b"rw-p", offset: 0, dev_major: 0, dev_minor: 0, inode: 0, kind: BackingKind::Anonymous }
    }

    fn always_match() -> impl Fn() -> IdentityVerdict {
        || IdentityVerdict::Match
    }

    /// Fails chunk-sized reads at the listed addresses exactly once with the
    /// given kind, then delegates to the wrapped image.
    struct OneShotFaultMemory {
        inner: forever_patcher::SliceMemory,
        once_addrs: Mutex<Vec<u64>>,
        kind: std::io::ErrorKind,
    }

    impl MemoryAccess for OneShotFaultMemory {
        fn regions(&self) -> std::io::Result<Vec<forever_patcher::Region>> { self.inner.regions() }
        fn read(&self, address: u64, buffer: &mut [u8]) -> std::io::Result<()> {
            if self.once_addrs.lock().map(|mut guard| guard.iter().position(|addr| *addr == address).map(|index| guard.remove(index))).unwrap_or(None).is_some() {
                let raw = match self.kind {
                    std::io::ErrorKind::TimedOut => Some(libc::ETIMEDOUT),
                    std::io::ErrorKind::Interrupted => Some(libc::EINTR),
                    _ => None,
                };
                return Err(match raw {
                    Some(errno) => std::io::Error::from_raw_os_error(errno),
                    None => std::io::Error::new(self.kind, "synthetic one-shot fault"),
                });
            }
            self.inner.read(address, buffer)
        }
        fn write(&self, _address: u64, _data: &[u8]) -> std::io::Result<()> {
            Err(std::io::Error::new(std::io::ErrorKind::PermissionDenied, "test memory is read-only"))
        }
    }

    /// Chunk reads succeed; 480-byte block re-reads fail with a real I/O
    /// error, exercising the persistent-gap path.
    struct BlockFailMemory {
        inner: forever_patcher::SliceMemory,
        block_len: usize,
    }

    impl MemoryAccess for BlockFailMemory {
        fn regions(&self) -> std::io::Result<Vec<forever_patcher::Region>> { self.inner.regions() }
        fn read(&self, address: u64, buffer: &mut [u8]) -> std::io::Result<()> {
            if buffer.len() == self.block_len {
                return Err(std::io::Error::new(std::io::ErrorKind::Other, "synthetic block EIO"));
            }
            self.inner.read(address, buffer)
        }
        fn write(&self, _address: u64, _data: &[u8]) -> std::io::Result<()> {
            Err(std::io::Error::new(std::io::ErrorKind::PermissionDenied, "test memory is read-only"))
        }
    }

    fn maps_fixture() -> &'static str {
        concat!(
            "1000-2000 r--p 00000000 00:00 0\n",
            "2000-3000 rwxp 00000000 00:00 0\n",
            "3000-4000 r--s 00000000 00:00 1 /data/app/client.so\n",
            "4000-5000 rw-p 00000000 00:00 0 [heap]\n",
            "5000-6000 r-xp 00000000 00:00 2 /data/app/client.exec\n",
            "6000-7000 r--p 00000000 00:00 3 /dev/ashmem/client\n",
            "7000-8000 ---p 00000000 00:00 0\n",
            "8000-9000 --xp 00000000 00:00 0\n",
            "9000-a000 rw-s 00000000 00:00 4 /data/app/shared\n",
        )
    }

    #[test]
    fn includes_all_readable_and_excludes_only_non_readable() {
        let regions = parse_full_readable_maps(maps_fixture()).unwrap();
        let starts: Vec<u64> = regions.iter().map(|region| region.start).collect();
        assert_eq!(starts, vec![0x1000, 0x2000, 0x3000, 0x4000, 0x5000, 0x6000, 0x9000]);
        assert!(regions.iter().any(|region| region.start == 0x2000 && region.perms == *b"rwxp"));
        assert!(regions.iter().any(|region| region.start == 0x3000 && region.perms == *b"r--s"));
        assert!(regions.iter().any(|region| region.start == 0x6000));
        assert!(regions.iter().any(|region| region.start == 0x9000 && region.perms == *b"rw-s"));
    }

    #[test]
    fn backing_identity_distinguishes_file_device_and_anonymous() {
        let regions = parse_full_readable_maps(maps_fixture()).unwrap();
        let by_start = |start: u64| regions.iter().find(|region| region.start == start).unwrap().clone();
        let anon = by_start(0x1000);
        assert_eq!((anon.offset, anon.dev_major, anon.dev_minor, anon.inode, anon.kind),
            (0, 0, 0, 0, BackingKind::Anonymous));
        let file = by_start(0x3000);
        assert_eq!((file.inode, file.kind), (1, BackingKind::File));
        assert_eq!(file.offset, 0);
        let heap = by_start(0x4000);
        assert_eq!(heap.kind, BackingKind::Pseudo);
        let device = by_start(0x6000);
        assert_eq!(device.kind, BackingKind::Device);
        assert_eq!(device.inode, 3);
        let shared = by_start(0x9000);
        assert_eq!((shared.inode, shared.kind), (4, BackingKind::File));
    }

    #[test]
    fn apply_eligible_mappings_exclude_readable_fallback() {
        let mut regions = parse_full_readable_maps(maps_fixture()).unwrap();
        retain_apply_eligible(&mut regions);
        let starts: Vec<u64> = regions.iter().map(|region| region.start).collect();
        assert_eq!(starts, vec![0x2000, 0x4000]);
        // Even a full valid-looking block in an ineligible mapping does not
        // cause a fallback traversal. Its address faults if actually read.
        let contract = test_contract();
        let mut image = vec![0u8; 0x9000];
        image[..contract.block_size()].copy_from_slice(&synthetic_store(&contract));
        let memory = OneShotFaultMemory {
            inner: forever_patcher::SliceMemory::new(0x1000, image),
            once_addrs: Mutex::new(vec![0x1000]),
            kind: std::io::ErrorKind::PermissionDenied,
        };
        let mut state = dummy_state(regions.clone());
        let stop = scan_loop(&memory, &super::super::Budget::new(), &contract,
            &regions, &mut state, &std::sync::atomic::AtomicBool::new(false), false, &always_match());
        assert!(matches!(stop, ScanLoopStop::Drained));
        assert_eq!(state.cumulative_regions_done, regions.len() as u64);
        assert!(state.found.is_empty());
        assert_eq!(*memory.once_addrs.lock().unwrap(), vec![0x1000]);
        assert_eq!(state.cumulative_gaps, 0);
    }

    #[test]
    fn budget_preserves_eligible_unread_chunk_without_fallback() {
        let contract = test_contract();
        let mut regions = parse_full_readable_maps(
            "1000-2000 r--p 00000000 00:00 0\n3000-4000 rw-p 00000000 00:00 0\n").unwrap();
        retain_apply_eligible(&mut regions);
        let memory = OneShotFaultMemory {
            inner: forever_patcher::SliceMemory::new(0x1000, vec![0u8; 0x3000]),
            once_addrs: Mutex::new(vec![0x3000]),
            kind: std::io::ErrorKind::TimedOut,
        };
        let mut state = dummy_state(regions.clone());
        let budget = super::super::Budget::new();
        let cancelled = std::sync::atomic::AtomicBool::new(false);
        let stop = scan_loop(&memory, &budget, &contract, &regions, &mut state, &cancelled, false, &always_match());
        assert!(matches!(stop, ScanLoopStop::StoppedBudget));
        assert_eq!((state.next_region, state.next_offset), (0, 0x3000));
        assert_eq!(state.cumulative_gaps, 0);
        let stop = scan_loop(&memory, &budget, &contract, &regions, &mut state, &cancelled, true, &always_match());
        assert!(matches!(stop, ScanLoopStop::Drained));
        assert_eq!(state.cumulative_regions_done, 1);
        assert!(complete_eligible(&state, &budget));
    }

    #[test]
    fn changed_eligible_maps_preserve_progress_and_never_restart_a_drained_pass() {
        let before = vec![readable_region(0x8000, 0x9000)];
        let mut state = dummy_state(before);
        state.next_offset = 0x8800;
        state.found.push(forever_patcher::FoundStore { addr: 0x8000, already_patched: false });
        state.seen.insert(0x8000);
        // A newly eligible lower-address mapping waits for the next pass.
        let mut after = vec![readable_region(0x1000, 0x3000),
            readable_region(0xa000, 0xb000)];
        retain_apply_eligible(&mut after);
        adopt_fresh_after_change(&mut state, after.clone(), &dummy_target());
        assert_eq!((state.next_region, state.next_offset), (1, 0xa000));
        assert!(state.maps_changed && state.found.is_empty() && state.seen.is_empty());
        assert_eq!(rebase_onto_fresh(0xc000, &after), (after.len(), 0xc000));
        state.next_region = after.len();
        after.push(readable_region(0xb000, 0xc000));
        adopt_fresh_after_change(&mut state, after.clone(), &dummy_target());
        assert_eq!(state.next_region, after.len());
    }

    #[test]
    fn next_eligible_pass_finds_store_created_behind_old_cursor() {
        let contract = test_contract();
        let regions = vec![readable_region(0x2000, 0x3000)];
        let memory = forever_patcher::SliceMemory::new(0x2000, vec![0u8; 0x1000]);
        let cancelled = std::sync::atomic::AtomicBool::new(false);
        let mut first = dummy_state(regions.clone());
        assert!(matches!(scan_loop(&memory, &super::super::Budget::new(), &contract,
            &regions, &mut first, &cancelled, false, &always_match()), ScanLoopStop::Drained));
        assert!(first.found.is_empty());
        memory.write(0x2100, &synthetic_store(&contract)).unwrap();
        let mut next = dummy_state(regions.clone());
        assert!(matches!(scan_loop(&memory, &super::super::Budget::new(), &contract,
            &regions, &mut next, &cancelled, false, &always_match()), ScanLoopStop::Drained));
        assert_eq!(next.found.len(), 1);
        assert_eq!(next.found[0].addr, 0x2100);
    }

    #[test]
    fn rejects_truncated_and_malformed_metadata() {
        assert!(parse_full_readable_maps("").is_err());
        assert!(parse_full_readable_maps("1000-2000 r--p 00000000 00:00 0").is_err());
        assert!(parse_full_readable_maps("1000-2000 rrrp 00000000 00:00 0\n").is_err());
        assert!(parse_full_readable_maps("1000-1000 r--p 00000000 00:00 0\n").is_err());
    }

    #[test]
    fn strict_block_validation_requires_all_twelve_groups() {
        let contract = test_contract();
        let mut bytes = synthetic_store(&contract);
        assert!(validate_strict_block(&bytes, &contract).is_some_and(|already| !already));
        bytes[(contract.target_group - 1) * contract.entry_size + 4..(contract.target_group - 1) * contract.entry_size + 36]
            .copy_from_slice(&contract.new_key);
        assert!(validate_strict_block(&bytes, &contract).is_some_and(|already| already));
        bytes[38] ^= 1;
        assert!(validate_strict_block(&bytes, &contract).is_none());
    }

    #[test]
    fn strict_block_accepts_free_first_padding_byte_and_rejects_nonzero_tail() {
        // Misma regla que el núcleo: el primer byte de la cola es libre
        // (`070000` observado con login OK, más `ff0000`/`420000` inéditas y
        // las históricas); cada byte de cola 38/39 no nulo invalida.
        let contract = test_contract();
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
        assert!(validate_strict_block(&bytes, &contract).is_some_and(|already| !already));
        for tail in [[0x07u8, 0x01, 0x00], [0x07, 0x00, 0x01], [0x00, 0x01, 0x00], [0x00, 0x00, 0x01]] {
            let mut bad = bytes.clone();
            let off = 5 * contract.entry_size;
            bad[off + 37..off + 40].copy_from_slice(&tail);
            assert!(validate_strict_block(&bad, &contract).is_none(), "cola {tail:02X?} debe rechazar");
        }
    }

    #[test]
    fn truncated_block_is_never_a_candidate() {
        let contract = test_contract();
        assert!(validate_strict_block(&vec![0u8; contract.block_size() - 1], &contract).is_none());
        assert!(validate_strict_block(&vec![0u8; contract.block_size() + 1], &contract).is_none());
        assert!(validate_strict_block(&[], &contract).is_none());
    }

    #[test]
    fn aligned_search_keeps_absolute_alignment_for_unaligned_chunk_starts() {
        let contract = test_contract();
        let store = synthetic_store(&contract);
        for misalignment in 0..4 {
            let base = 0x1000u64 + misalignment;
            let first = ((4 - base % 4) % 4) as usize;
            let unaligned = 512 + (first + 1) % 4;
            let mut image = vec![0u8; 2048];
            image[first..first + store.len()].copy_from_slice(&store);
            image[unaligned..unaligned + store.len()].copy_from_slice(&store);
            let memory = forever_patcher::SliceMemory::new(base, image);
            let regions = vec![readable_region(base, base + 2048)];
            let mut state = dummy_state(regions.clone());
            let budget = super::super::Budget::new();
            let cancelled = std::sync::atomic::AtomicBool::new(false);
            let stop = scan_loop(&memory, &budget, &contract, &regions, &mut state, &cancelled, false, &always_match());
            assert!(matches!(stop, ScanLoopStop::Drained));
            assert_eq!(state.found.len(), 1);
            assert_eq!(state.found[0].addr, base + first as u64);
            assert!(!state.found[0].already_patched);
            assert_eq!(memory.snapshot()[unaligned..unaligned + store.len()], store);
        }
    }

    #[test]
    fn budget_midread_preserves_cursor_and_resumes_to_find_straddling_store() {
        let contract = test_contract();
        let chunk = super::super::MAX_READ_CHUNK as u64;
        let base = 0x1000u64;
        // Block straddles the first chunk boundary; the anchor sits in the
        // first chunk while the block tail extends past it.
        let block_start = base + chunk - 200;
        assert_eq!(block_start % 4, 0);
        let total = (chunk + 1024) as usize;
        let mut image = vec![0u8; total];
        image[(block_start - base) as usize..(block_start - base) as usize + contract.block_size()]
            .copy_from_slice(&synthetic_store(&contract));
        let memory = OneShotFaultMemory {
            inner: forever_patcher::SliceMemory::new(base, image),
            once_addrs: Mutex::new(vec![base]),
            kind: std::io::ErrorKind::TimedOut,
        };
        let regions = vec![readable_region(base, base + total as u64)];
        let mut state = dummy_state(regions.clone());
        let budget = super::super::Budget::new();
        let cancelled = std::sync::atomic::AtomicBool::new(false);
        let stop = scan_loop(&memory, &budget, &contract, &regions, &mut state, &cancelled, false, &always_match());
        assert!(matches!(stop, ScanLoopStop::StoppedBudget));
        // The unread chunk is preserved: no cursor advance, no gap recorded.
        assert_eq!((state.next_region, state.next_offset), (0, base));
        assert_eq!(state.cumulative_gaps, 0);
        assert!(state.found.is_empty());
        // The next poll re-reads the same slice and finds the store that
        // straddles the old chunk boundary.
        let stop = scan_loop(&memory, &budget, &contract, &regions, &mut state, &cancelled, false, &always_match());
        assert!(matches!(stop, ScanLoopStop::Drained));
        assert_eq!(state.found.len(), 1);
        assert!(complete_eligible(&state, &budget));
    }

    #[test]
    fn cancel_midread_preserves_cursor_without_gap() {
        let contract = test_contract();
        let base = 0x2000u64;
        let total = 4096usize;
        let memory = OneShotFaultMemory {
            inner: forever_patcher::SliceMemory::new(base, vec![0u8; total]),
            once_addrs: Mutex::new(vec![base]),
            kind: std::io::ErrorKind::Interrupted,
        };
        let regions = vec![readable_region(base, base + total as u64)];
        let mut state = dummy_state(regions.clone());
        let budget = super::super::Budget::new();
        let cancelled = std::sync::atomic::AtomicBool::new(false);
        let stop = scan_loop(&memory, &budget, &contract, &regions, &mut state, &cancelled, false, &always_match());
        assert!(matches!(stop, ScanLoopStop::StoppedCancel));
        assert_eq!((state.next_region, state.next_offset), (0, base));
        assert_eq!(state.cumulative_gaps, 0);
    }

    #[test]
    fn candidate_block_io_failure_blocks_complete_forever() {
        let contract = test_contract();
        let base = 0x3000u64;
        let total = 8192usize;
        let mut image = vec![0u8; total];
        image[0..contract.block_size()].copy_from_slice(&synthetic_store(&contract));
        let memory = BlockFailMemory { inner: forever_patcher::SliceMemory::new(base, image), block_len: contract.block_size() };
        let regions = vec![readable_region(base, base + total as u64)];
        let mut state = dummy_state(regions.clone());
        let budget = super::super::Budget::new();
        let cancelled = std::sync::atomic::AtomicBool::new(false);
        let stop = scan_loop(&memory, &budget, &contract, &regions, &mut state, &cancelled, false, &always_match());
        assert!(matches!(stop, ScanLoopStop::Drained));
        // The chunk was consumed (no stall), but the persistent gap blocks
        // any future complete claim for this pass.
        assert!(state.cumulative_gaps > 0);
        assert!(state.found.is_empty());
        assert!(!complete_eligible(&state, &budget));
    }

    #[test]
    fn same_va_backing_change_clears_stale_candidates() {
        let before = parse_full_readable_maps("1000-2000 r--p 00000000 00:00 1 /data/app/before.so\n").unwrap();
        let after = parse_full_readable_maps("1000-2000 r--p 00001000 00:00 2 /data/app/after.so\n").unwrap();
        assert_ne!(before, after);
        let mut state = dummy_state(before);
        state.found.push(forever_patcher::FoundStore { addr: 0x1000, already_patched: false });
        state.seen.insert(0x1000);
        state.candidate_capped = true;
        state.cumulative_bytes = 128;
        let target = dummy_target();
        adopt_fresh_after_change(&mut state, after.clone(), &target);
        assert!(state.found.is_empty());
        assert!(state.seen.is_empty());
        assert!(!state.candidate_capped);
        assert!(state.maps_changed);
        assert_eq!(state.regions, after);
        assert_eq!(state.cumulative_bytes, 128);
        assert!(!complete_eligible(&state, &super::super::Budget::new()));
    }

    #[test]
    fn candidate_list_is_capped_at_64_without_unbounded_growth() {
        let contract = test_contract();
        let count = 70usize;
        let total = count * contract.block_size();
        let mut image = vec![0u8; total];
        for index in 0..count {
            image[index * contract.block_size()..(index + 1) * contract.block_size()]
                .copy_from_slice(&synthetic_store(&contract));
        }
        let base = 0x4000u64;
        let memory = forever_patcher::SliceMemory::new(base, image);
        let regions = vec![readable_region(base, base + total as u64)];
        let mut state = dummy_state(regions.clone());
        let budget = super::super::Budget::new();
        let cancelled = std::sync::atomic::AtomicBool::new(false);
        let stop = scan_loop(&memory, &budget, &contract, &regions, &mut state, &cancelled, false, &always_match());
        assert!(matches!(stop, ScanLoopStop::Drained));
        assert_eq!(state.found.len(), super::super::MAX_CANDIDATES);
        assert!(state.candidate_capped);
        assert!(!complete_eligible(&state, &budget));
        assert!(state.found.len() <= super::super::MAX_CANDIDATES);
    }

    #[test]
    fn coverage_json_reports_counts_without_addresses() {
        let coverage = FullScanCoverage {
            regions_total: 7,
            regions_visited: 7,
            bytes_total: 1024,
            bytes_read_cumulative: 1024,
            read_gaps: 0,
            maps_changed: false,
            maps_complete: true,
            pass_complete: true,
            budget_exhausted: false,
            candidate_capped: false,
            resumed: true,
        };
        let value = coverage_json(&coverage, 1);
        assert_eq!(value["mode"], "apply-eligible");
        assert_eq!(value["scope"], "APPLY_ELIGIBLE_REGIONS");
        assert_eq!(value["regionsTotal"], 7);
        assert_eq!(value["validatedStoreCount"], 1);
        assert_eq!(value["readOnly"], true);
        assert_eq!(value["writeAuthorized"], false);
        let text = value.to_string();
        assert!(!text.contains("0x"));
        assert!(!text.contains("addr"));
        assert!(!text.contains("heap"));
    }
}
