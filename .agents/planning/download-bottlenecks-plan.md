---
id: download-bottlenecks-public-2026-10-25
state: in-progress
date: 2026-10-25
---

# Download performance work

## Approved scope

Continue the approved Home installation, bounded download and recovery work from
US-008. The user approved transferring the relevant reviewed implementation onto
a branch based on the public repository. This is a selective source port, not a
merge of unrelated histories or a new release. Preserve public guides, licensing
and sanitization boundaries. Do not push without authorization.

The engineering target is at least **3,200,000 useful network bytes/second**,
twice the user's reported earlier 1.6 MB/s. It is not an end-user SLA or a measured
result. Improve demonstrated bottlenecks incrementally and remeasure; a complete
prediction of the final speedup is not a prerequisite for a small proven fix.

## Existing behavior to preserve

- US-008 FR-002/003/004/011: accurate consented costs, individual authentication,
  durable progress, identity-bound recovery and stale-consent rejection.
- US-008 NFR-001 through NFR-004: bounded cursors/queues, strict HTTP/integrity,
  owned no-follow paths and independent integrity review before activation.
- US-007 FR-005/NFR-002: unchanged physical storage identity validation.
- Wi-Fi-only app-managed downloads; no cellular fallback or VPN bypass.
- Sixteen fetch workers, 34 reserved raw keys and a 1-GiB raw budget. Each archive
  batch contains at most 16 exactly contiguous objects and 1 MiB, with no overfetch.
- No installation reset, client duplication, cross-volume migration, checkpoint
  format change or implicit weakening of durable acknowledgement.

## Implementation / Action Items

- [x] Create `perf/download-bottlenecks` from the public `origin/main`, retaining
  the internal worktree and history separately.
- [x] Port the reviewed download implementation and necessary dependencies only.
  Exclude unrelated APK publication notices and internal operational documents.
- [x] Reconcile specifications and public-safe measurement notes; remove private
  approval quotations without changing the approved behavior.
- [x] Check focused host tests, source admissions and relevant Kotlin regressions;
  independently review recovery/concurrency and release-authority changes.
  Canonical reconstruction and mixed-source compilation completed: 301 Kotlin
  tests passed, three optional cases skipped, and one failure reproduced unchanged
  on the public baseline. Host checks also retain two pre-existing failures;
  see the validation notes rather than treating the suite as fully green.
- [x] Commit only task-owned changes; verify the public base is an ancestor and
  no internal history or generated/private artifacts have been introduced.
  Source port: `78525d2`; no push, APK assembly or device operation.
- [ ] Profile the dominant preparation consumer and storage checks before changing
  their implementation. Repeated path walks alone do not prove redundant guards.
- [ ] Validate each subsequent bounded improvement and repeat comparable physical
  measurements. Record preparation separately and use ten-minute payload windows
  with two-minute subwindows, exact byte counters where available and explicit
  reuse/retry/cohort limitations.

## Open Issues

The latest measured candidate averages about 1.516 MB/s, below both the reported
1.6-MB/s reference and the 3.2-MB/s target. Different sequential object cohorts
are not a controlled A/B comparison. See the
[measurement summary](../../docs/download-performance-investigation.md).

Ready-first writing remains a design question, not an implemented change: packing,
per-volume cost, retained checkpoints and both replay modes must be settled before
changing write order. Increasing quotas or reducing storage-validation frequency
requires a separate decision. This port does not authorize either.
