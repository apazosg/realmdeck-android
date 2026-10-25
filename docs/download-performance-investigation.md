# Download performance investigation

## C145 durable groups of up to sixteen

Source commit `040254f` applies the approved maximum-group change from four to
sixteen objects. Pressure, rollover and end-of-stream still flush smaller groups.
The 34-key/1-GiB raw budget, sixteen fetch workers, object authentication,
checkpoint format/identity and data-force → checkpoint-force → acknowledgement/
raw-retirement ordering remain unchanged. More unacknowledged staging can require
replay, and the light group check runs less frequently; acknowledged progress is
not deliberately discarded. This is an optimization, not another diagnostic-only
candidate, but fewer force calls do not guarantee a particular wall-time saving.

The two affected Kotlin families initially verified 108 cases, with one new-test
assertion missing the existing finalization frame and one opt-in native skip.
After correcting that assertion and strengthening the legacy-prefix fixture to
flush every four objects explicitly, the two final focused cases passed. Across
the runs, 109 distinct cases are verified and one optional diagnostic remains
skipped; this is not a claim of one final all-green full-suite run. Five focused
host admission/version cases pass. Independent inline-source review found no
actionable blocker and did not independently execute tests.

The canonical build completed in 4m53s. Its source/build/payload audit passed;
version 145 is `0.2.121-commit-group-16`, signed with the prior certificate.
APK SHA-256:
`486445e0623b86e8779fd190c2afd1a0742daccaf346c19826520736fc6aff7b`.
The reviewed CASC sources match the canonical staged sources. A fresh paused,
no-guest, Wi-Fi and writable-USB preflight preceded replacement installation.
Before launch, all three progress hashes and size/inode/mtime fields matched
exactly, as did the installed APK hash. The fresh-consent ten-minute observation
is in progress; preparation is separate, and the helper owns the final Pause.
No C145 throughput result is claimed yet. Merge remains deferred.

## C144 append-clock diagnostic

C144 (`0.2.120-append-breakdown`) adds five local aggregate clocks inside the
existing append timing: segment transition/opening, local header construction
and private-to-segment copy, ordinary index insertion, buffered group flush, and
data-file force. Segment transition includes its full storage check and generated
header index entries; those are not part of the ordinary index clock. The light
group check remains outside the append timer. Checkpoint and raw-retirement
ordering, buffers, quotas, verification and storage policy are unchanged.

The two focused interrupted-prefix and failed-boundary/resume regressions passed
on the final source, with no failures or skips. Six focused host source-admission
and version tests passed. Independent inline-diff review found no actionable
blocker; it did not independently execute tests. Child nanoseconds and the old
per-operation rounded milliseconds are not an exact accounting partition, and
failure-final `newObjects` is not a durable count. Periodic committed samples
will be used for attribution, not concurrent HTTP totals or a pure-USB claim.

The canonical build completed in 4m57s and its source/build/payload audit passed.
APK SHA-256:
`f33c57b13e00796179d02fd935950dc3cc2aab47c74311923f19a37b72524962`.
The certificate matches C143 and the packaged version is 144. A fresh paused,
no-guest, Wi-Fi and writable-USB preflight passed before replacement. Installation
succeeded; before launch, all three owner/receipt/checkpoint hashes and stat
fields matched exactly, and the installed APK hash matched the audited candidate.
The completed measurement below counts preparation separately. Merge and any
further optimization remain deferred until the diagnosis and next bounded change.

### Completed C144 observation

The operation completed 608.682 seconds of numeric payload observations and
paused normally, with Resume available and zero active fetch callbacks or HTTP
requests. Rounded useful-network bytes increased by 486.590 MB: **0.7994 MB/s**
(display bounds 0.79933–0.79950). Five approximately two-minute windows measured
1.038 / 1.413 / 0.556 / 0.151 / 0.811 MB/s. This is not a matched-object A/B:
the aligned writer cohort averaged 19,955 CASC bytes per object, versus 105,921
in the prior C143 interval. It processed 40.06 objects/s versus 35.54. CASC bytes
are not useful-network bytes; neither comparison establishes that the diagnostic
change caused a speed regression or that the 3.2-MB/s goal is sustained.

Across periodic committed samples 256–25,600 (632.612 writer seconds), the new
nested clocks measured:

| Append suboperation | Seconds | Share of measured child-clock sum |
|---|---:|---:|
| Group force helper | 191.320 | 92.16% |
| Local header and private-to-segment copy | 10.718 | 5.16% |
| Ordinary index insertion/bookkeeping | 4.738 | 2.28% |
| Buffered group flush | 0.824 | 0.40% |
| Segment transition | 0 | 0% |

The **force helper**, not isolated `force(true)`, is the dominant measured child.
It checks the pathname for symlink/regular-file shape, opens a fresh write channel,
forces it and closes it. Its 191.320 seconds are about **30.24% of this writer
interval**, not 92% of the entire download. Removing the whole helper cost would
therefore not double throughput, and is not an approved or safe optimization.
The old append clock reports 193.409 seconds; summed nanosecond children exceed
it by 14.192 seconds because the old clock truncates per operation. They must not
be forced into an exact partition or added to the outer clock.

The same writer interval reports 164.284 seconds of source resolution, 77.903
of private copy/authentication and 26.596 of checkpoint work. Final run counters
record 25,620 committed objects, 167.032 seconds of future wait and 64.741 seconds
of raw retirement; these final counters have different boundaries and are not
additional disjoint costs. Producer budget wait is concurrent. Preparation still
consumed roughly fifteen minutes before payload and is a separate unresolved cost.

Next assess whether avoiding repeated channel reopening can preserve required
path and ownership checks, or whether internal helper phase timings are needed
before choosing that change. No force-frequency, commit-group, validation-policy
or descriptor-lifetime change is implemented by this diagnostic. Evidence is the
C144 scoped log, payload samples, append/throughput/pipeline summaries and final
pause capture, retained outside source control.

## C143 directed storage-boundary candidate

Commit `1632460` implements the approved heavy/light policy for directed,
sourceless CASC installs only. Full validation remains at admission/resume,
segment opening and final activation. Before each durable group, including a
partial group, a light check verifies fresh selection/revision and no-follow
root/base directory identities. A failed boundary check prevents the group
checkpoint and raw retirement. Sourced serial and non-directed paths retain
their existing full per-object checks.

This deliberately changes detection coverage: marker, ancestor and fresh mount
metadata checks may be deferred across multiple groups until the next full
boundary. Four objects do not bound elapsed time or bytes. Unacknowledged staging
writes may occur before a failing group check. The existing narrow pathname-force
versus open-writer race is not eliminated. These are accepted trade-offs, not
claims of equivalent coverage or zero risk. Quotas, object authentication,
checkpoint format, plan identity and force/ack ordering are unchanged.

Four final focused Kotlin regressions passed with no failures or skips, covering
light identity checks/marker deferral, capability-gated parallel cadence,
unchanged serial reuse, and failed partial-group/resume boundary checks retaining
raws. Focused host admission/provenance tests and independent review also passed;
the historical baseline failures documented below were not silently fixed.

The canonical C143 build (`0.2.119-light-storage`) completed and its
source/build/payload audit passed. APK SHA-256:
`af2b21765959dfeab8e93747b108b948bf48e8bb3d185306015ed793f5f7a67b`.
The signing certificate matches the prior installed candidate. C143 avoids a
version downgrade from the installed C142; no uninstall, data reset or downgrade
flag is used. The interrupted observation and the subsequent completed measurement
are distinguished below; compilation alone was not throughput evidence.

### Completed C143 repeat

The same audited APK completed an uninterrupted observation of **608.073 seconds**
with 50 numeric payload samples. Rounded useful-network counters increased by
approximately **2,358.038 MB**, giving **3.878 decimal MB/s**. Endpoint display
rounding bounds the result to **3.790–3.966 MB/s**. This execution therefore
exceeds the 3.2-MB/s engineering target even at the lower rounding bound. It is
one successful window, not proof of whole-client throughput or a matched-object
causal speedup over the reported 1.6-MB/s reference.

Five approximately two-minute subwindows measured **3.589 / 7.623 / 1.923 /
3.463 / 2.599 MB/s**. Their actual durations differ; GiB display quantization
makes the later subwindows particularly imprecise. These are not five independent
trials. The operation ended paused with Resume available, all 8,451 callbacks
completed, and zero active callbacks and HTTP requests. It durably committed
22,992 new objects; retained prepared raws are not active network requests.

An aligned writer interval, from 256 to 22,784 newly committed objects, excludes
initial replay and measures **633.894 seconds**. It is not the UI sample interval:

| Writer clock | Seconds | Share of this writer interval |
|---|---:|---:|
| Segment opening, append/copy, index insertion and data force | 258.935 | 40.85% |
| Private copy plus EKey/BLTE/content verification | 121.066 | 19.10% |
| Source resolution, including future readiness wait | 77.430 | 12.21% |
| Checkpoint append/force | 24.025 | 3.79% |
| Outside those clocks | 152.438 | 24.05% |

Nearby pipeline samples attribute 61.377 seconds to post-commit raw validation,
unlink and directory force, which is outside the four writer clocks. Future wait
is nested in source resolution, not an additional 77.602-second phase. Producer
budget sleep (213.990 seconds) and HTTP phase totals run concurrently and must
not be added to this partition. In a separate sparse process interval, 5,632
light group checks took 21.217 seconds, about 3.767 ms per check, with no failures.
Absent full-check logs do not imply zero full checks: they log only every 256 calls.

The dominant measured phase has shifted to append/copy/index/force; the current
clock does **not** isolate USB write bandwidth, force latency or index updates.
Private verification also includes a temporary-file copy, not cryptography alone.
Those distinctions should guide the next bounded investigation before changing
durability, object verification or the accepted storage policy. Preparation is
still separate: the fresh execution target inspection took 163.663 seconds.

Evidence retained outside Git: the confirmed repeat's original UI samples and
scoped log, throughput/pipeline/light-storage summaries and aligned writer-clock
summary. A preceding Resume recalculation stopped at fresh consent with no
payload; it is not part of this speed measurement. No reset, client duplication,
quota increase or write-order change was used.

### Interrupted C143 physical observation

C143 replacement installation succeeded. Before launch, the installed APK hash
matched the audited candidate and all three owner/receipt/checkpoint fingerprints
(including size, inode and modification time) matched their fresh pre-install
values. A freshly consented USB trial started, with a planned 600-second payload
window and preparation measured separately.

The host rebooted during observation, terminating the logger and UI observer
before their final Pause could run. Only 30 numeric payload samples over
359.628 seconds were retained: approximately 1,107.191 MB of useful bytes,
**3.079 decimal MB/s**, with display-rounding bounds 2.929–3.228 MB/s. The earlier
mid-run estimate of about 3.4 MB/s covered a shorter interval and is superseded
by this longer retained window. The GiB display rounding makes the last subwindow
particularly imprecise. This is not a completed ten-minute measurement, a paired
A/B speedup, or confirmation of the 3.2-MB/s target.

After reconnecting to the user-provided endpoint, the app was still downloading.
It was paused using the same official UHID control; the resulting UI showed
the paused state with Resume available, and the foreground operation subsequently
reported cancellation.
No reset, reinstall, installation deletion or client copy was used. Logs recovered
from the phone retain later observations, but they do not fill the missing UI
counter interval. The host interruption's underlying cause remains unresolved.

The retained log includes 2,816 light boundary checks with zero failures and
10.742 seconds accumulated check time before interruption. These are group
checks with the explicitly reduced coverage above, not equivalent per-object
full validation. A new uninterrupted observation is required for acceptance.

## Scope

This branch selectively transfers reviewed download work onto the public source
baseline. It is not a new APK release and does not import internal operational
logs, device captures, private working directories or unrelated Git history.
Existing English guides, release notes and licensing remain authoritative for
published artifacts. Branch validation does not retroactively validate a release.

## Transferred implementation

- Home-integrated preparation, explicit final consent and guarded pause/resume.
- Smoothed network speed/ETA, distinct from reuse, verification and activation.
- Wi-Fi-only client/runtime requests, without cellular fallback or VPN bypass.
- Exact contiguous archive batches with individual EKey/BLTE/CKey verification.
- A disposable bounded physical-order index, with authoritative catalog records
  resolved in blocks of at most 256 keys through the same admitted connection.
- Recovery membership independent of ingestion order, preserving legacy progress,
  durable commit groups and raw retention until acknowledgement.
- Eviction of legal inherited reservation debt before waiting, and regrouping of
  contiguous tails after capacity returns. Quotas are not increased.
- Aggregate-only diagnostics for head readiness, HTTP phases, preparation and
  physical storage revalidation. No identity-validation checks are removed.

## Public-branch source validation

Commit `78525d2` selectively ports the reviewed implementation onto public commit
`457056e`, without merging internal history. Public guides, licensing and the
baseline manifest permission/theme policy remain unchanged. The unrelated APK
publication-notice feature is excluded; this is not a new release.

Canonical reconstruction and compilation of the mixed public-branch sources
completed. The selected Kotlin regressions produced **301 passes, three optional
skips and one failure** across 305 cases. The skips are the opt-in native-index
diagnostic and two external real-catalog checks. The failing resolver test,
`oneResolverBuildsDifferentFingerprintsAndReusesOnlyVerifiedCompletedOutput`,
expects an exception that is not thrown. Replacing both resolver source and test
with their `origin/main` versions reproduces the same assertion at the same line;
it is a known baseline failure, not a new port regression.

Focused host validation initially reported 102 passes and two failures; the two
manifest/auditor families subsequently passed all 30 cases after removing an
unrelated manifest-policy change. The remaining baseline expectations concern
Account unavailable-state wording and an older release-version pair. They were
not changed to make the port appear green. Independent source/privacy and
recovery/concurrency review found no actionable issue in the selected port.

Validation was interrupted by a host restart. Six empty staged Git blobs were
restored to their exact object IDs without moving references or changing source;
only identified empty generated Gradle transform-cache entries were quarantined
before retrying compilation. Git integrity checks passed afterward. This does
not establish the underlying cause of the interrupted writes.

These checks validate source reconstruction and targeted regressions, **not** a
signed APK, physical acceptance or the 3.2-MB/s target. No APK was assembled or
installed and no device was operated for this public-branch port.

## Recorded evidence and limits

Earlier development candidates were measured on one ARM64 phone with a USB
destination. These are historical observations of those exact candidates, not
measurements of this newly ported branch or of the published preview.

| Observation | Result | Limit |
|---|---:|---|
| User's earlier download reference | 1.6 MB/s | Reported, not a paired instrumented baseline |
| Latest long payload trial | 1.516 MB/s over 612.379 s | Rounded useful-byte UI counters; distinct object cohort |
| Its five approximately two-minute windows | 0.962 / 1.951 / 1.570 / 2.151 / 0.942 MB/s | Actual window durations differ; not independent trials |
| Storage profiling | 185.534 s across 9,984 directed checks | Process counter interval, not the UI payload interval |
| Candidate inspection plus inclusive resolution | 95.52% of measured storage-check time | Does not prove any guard redundant |
| Full target inspections | 225.435 s and 169.203 s | Preparation and fresh execution planning, not payload transfer |

The long-trial APK SHA-256 was
`a85d8430f0f994550849da2b7b09f8a5781a6a14fd3ec30d7b9bf3c670caa8d6`.
Its source/build/payload audit passed; replacement preserved owner, stage receipt
and checkpoint fingerprints before execution. The operation ended paused with
no active fetch callbacks or HTTP requests. This is not full-client completion,
sustained gameplay or a general device-support claim.

An aligned writer interval separately measured 606.212 s for 9,984 new objects:
406.528 s source resolution, 22.066 s private verification, 81.715 s append/force,
28.063 s checkpoint work and 67.840 s residual. Source resolution contains storage
revalidation and future readiness waits. These nested measurements must not be
added again; concurrent HTTP/producer sums are not a wall-clock partition. CASC
written bytes are not interchangeable with useful network bytes.

## Next work

The target remains **at least 3.2 decimal MB/s**, now exceeded in one completed
C143 window but not yet established as repeatable or whole-client throughput. Profile and
remove proven inefficiencies incrementally, retaining integrity, storage identity,
Wi-Fi, consent and recovery guarantees. Measure preparation separately; a faster
preparation phase is not increased payload throughput. No ready-first writer,
quota increase or reduced validation cadence is included in this transfer.

See the [approved work plan](../.agents/planning/download-bottlenecks-plan.md).
