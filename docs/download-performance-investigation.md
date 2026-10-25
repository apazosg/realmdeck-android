# Download performance investigation

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
