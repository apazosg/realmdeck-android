---
id: download-bottlenecks-public-2026-10-25
state: in-progress
date: 2026-10-25
---

# Download performance work

## Approved scope

The measurement candidate will use version code 143 and version name
`0.2.119-light-storage`, following the installed code 142. The user confirmed
continuing with that correction; do not force a downgrade or uninstall to install
the earlier code-141 build. Reconstruct and audit the new identity before use.

Continue the approved Home installation, bounded download and recovery work from
US-008. The user approved transferring the relevant reviewed implementation onto
a branch based on the public repository. This is a selective source port, not a
merge of unrelated histories or a new release. Preserve public guides, licensing
and sanitization boundaries. Do not push without authorization.

The engineering target is at least **3,200,000 useful network bytes/second**,
twice the user's reported earlier 1.6 MB/s. It is not an end-user SLA. One completed
C143 window now exceeds it; repeatability and whole-client behavior remain open.
Improve demonstrated bottlenecks incrementally and remeasure; a complete
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

### Completed C143 measurement and next bottleneck

The confirmed repeat completed 608.073 seconds of actual payload observation at
3.878 decimal MB/s (rounded-counter bounds 3.790–3.966), then paused with no active
fetch callbacks or HTTP requests. This exceeds 3.2 MB/s for this window, not as a
general guarantee or matched-object before/after proof. Five subwindows fluctuate
substantially; a second completed comparable observation is still needed.

The aligned writer interval now spends 40.85% in the combined segment append/copy,
index insertion and force clock, 19.10% in private copy/authentication, and 12.21%
in source resolution. Investigate these combined clocks before choosing another
optimization: do not label all append time USB force or all private time crypto.
The raw retirement timer accounts for part of the unclassified writer time;
producer/HTTP concurrent sums cannot be added to the wall-clock partition.
Preparation remains a separate unresolved latency problem. See the measurement
notes for denominators and accepted coverage changes.

### Requested review of validation frequency

The user asks to evaluate whether checks are excessive and whether their number
or frequency can be reduced as a performance/risk trade-off. This authorizes
analysis, not an arbitrary time cache or an unreviewed reduction in protection.
For each expensive check, document its measured cost, failure it detects, what
can change between checks, and whether another retained guard detects that change
before a consequential write. Separate genuinely redundant work from checks at
different observation points. Compare same-point consolidation, per-object and
bounded-batch policies; do not presume a descriptor-based alternative is supported
by the existing Android APIs or requires no new complexity.

Present estimated savings with uncertainty and the concrete residual risks
(disconnect/remount, path substitution, links, ownership and stale selection).
Then obtain approval for any changed safety/UC-AC contract and derive affected
requirements/tests before implementation. The current same-point attribute
experiment may continue, but all other validation cadence remains unchanged
while that decision is pending. No new option, configuration switch, quota or
checkpoint-format change is implied by this evaluation.

#### Concrete lighter policy — approved, implementation in progress

The user approved this exact trade-off on 2026-10-25. US-007 AC-05/FR-005 and
US-008 AC-04/FR-004 now record it, with TC-008 and TC-019 respectively tracing
the changed boundaries and retained-source failure behavior. Implementation,
independent review, exact source admission and physical measurement remain gates.

The proposed experiment for directed sourceless installs is:

- Full validation at admission/resume, before accessing staging and opening a new
  CASC segment, and before final activation. Do not repeat it for every source.
- Before each durable group (including partial flushes), check the current
  selection/revision and root/base identities using fresh no-follow attributes.
  Missing, linked, wrong-type or changed identities stop the operation before
  the group's force/checkpoint acknowledgement and raw retirement.
- Keep individual payload authentication, cancellation, safe owned-file opens,
  commit-group size, quotas and durable ordering unchanged. Other install/update
  paths keep their existing full validation until separately evaluated.

This deliberately defers full ancestor walks, marker reads and fresh volume/
namespace metadata to the full-validation boundaries. Changes detectable only by
those checks may remain undetected through multiple groups within one segment.
Staging writes may have occurred before a failed group check; there is no fixed
byte/time bound for four objects. This is reduced checking, not equivalent safety.

The existing writer forces a segment by reopening its pathname rather than by
forcing its original output descriptor. Neither the current full root validation
nor the proposed lightweight check proves that both descriptors refer to the same
file under every concurrent path substitution. The proposed experiment leaves
that existing narrow race unchanged, rather than claiming to solve it or adding
an unproven descriptor abstraction. Acceptance explicitly includes this limit;
if same-file descriptor binding is required, settle that as a separate prerequisite.

Approved UC/AC decision: allow deferred full destination checks and intermediate
staging writes under this policy; require stopping without group acknowledgement
or raw retirement on a detected lightweight/full-check failure. US-007
AC-05/FR-005 and US-008 AC-04/FR-004 have been reconciled. Test root/base substitution,
selection change, partial flush, rollover and resume, and explicitly verify that
marker-only changes are detected at the next full boundary, not falsely at each
group. Cadence implementation is in progress; no changed APK has been activated.

The earlier directional request for lighter checks has now been superseded by
approval of the explicit split policy and its deferred-check and descriptor-race
limits above. Implementation and measured acceptance, not contract approval,
remain pending. Do not introduce an arbitrary timer or claim equivalent coverage
for deferred marker/ancestor/mount-metadata checks.

The HTTP batch is not a storage transaction. Current source callbacks revalidate
before waiting for a fetched object; CASC then verifies the private source, appends
to staging, and forces segment/checkpoint data in groups of at most four objects.
A new segment and a forced partial group can introduce additional boundaries.
Therefore a fetch-batch counter is not an adequate replacement for a writer hook.

| Policy | Full destination validation | Trade-off |
|---|---|---|
| Current | Per requested object | Smallest object-count detection window, with repeated expensive root walks; the existing fetch wait can still separate the check from the write. |
| Conservative batching | At the first append of each durable group and again before its durable acknowledgement, plus segment opens | Roughly two full checks per four objects when groups fill; detects a mid-group change before acknowledging, but not necessarily before every staging append. |
| Balanced candidate | Before forcing/checkpointing each durable group, plus initial staging access, segment opens, resume and final activation | Roughly one full check per four objects when groups fill; accepts delayed detection during up to one unacknowledged group. No claim that intermediate staging writes are prevented. |

The balanced candidate above is the earlier comparison; the approved lighter
split policy supersedes it. Neither has yet been activated.
Keep per-object payload authentication, cancellation, safe owned-file opens,
existing force/checkpoint order and raw retention until durable acknowledgement.
Do not use disconnect notifications as the sole guard: they may arrive late.
Do not change quotas, commit-group size, checkpoint format or activation consent.
Unknown storage identity or a failed group check must stop without acknowledging
or discarding the retained raws; no automatic repair of the changed destination.

Under the measured per-call cost, reducing four full validations to one could
avoid at most approximately 75% of that validation component (about 139 s of the
recorded 185.5 s). Segment opens, partial groups and unchanged file-level checks
reduce that saving. This is not a throughput prediction or proof of the 3.2-MB/s
goal; timing scopes and object cohorts differ.

Permission for staging writes within an unchecked group and the reconciled
US-007/US-008 contract are approved. Review writer hooks in both sourced and
sourceless paths before acceptance. Focused tests
must change/remount/replace the destination after an intermediate append and
before commit, verify no group acknowledgement or premature raw retirement,
cover segment rollover/partial flush and resume, and preserve existing integrity
tests. If this requires a new recovery guarantee or format, stop for that decision.

### Next bounded storage experiment

The first source inspection identifies a same-point `exists(NOFOLLOW_LINKS)` /
directory-type pair in candidate inspection. Replace only that pair with one
no-follow attribute read: a missing root remains admissible for an empty target,
an existing non-directory/link is not trusted, and other attribute errors fail
closed. Keep the later directory check after the safety walk, all path walks,
marker/identity/mount checks and per-object validation frequency unchanged.
This is a measured-cost experiment within the approved storage work, not proof
that those other checks are redundant or that a speedup has been achieved.
Validate absent/file/link roots and existing update guards, independently review
the safety change and exact source admission, then measure the physical profile
before retaining it as a performance improvement.

Before C143, the long measured candidate averaged about 1.516 MB/s, below both the reported
1.6-MB/s reference and the 3.2-MB/s target. Different sequential object cohorts
are not a controlled A/B comparison. See the
[measurement summary](../../docs/download-performance-investigation.md).

Ready-first writing remains a design question, not an implemented change: packing,
per-volume cost, retained checkpoints and both replay modes must be settled before
changing write order. Increasing quotas or reducing storage-validation frequency
requires a separate decision. This port does not authorize either.
