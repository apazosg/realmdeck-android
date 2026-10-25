# Download performance investigation

## Scope

This branch selectively transfers reviewed download work onto the public source
baseline. It is not a new APK release and does not import internal operational
logs, device captures, private working directories or unrelated Git history.
Existing English guides, release notes and licensing remain authoritative for
published artifacts. Branch validation does not retroactively validate a release.

## Implementation being transferred

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

The target remains **at least 3.2 decimal MB/s**, not yet demonstrated. Profile and
remove proven inefficiencies incrementally, retaining integrity, storage identity,
Wi-Fi, consent and recovery guarantees. Measure preparation separately; a faster
preparation phase is not increased payload throughput. No ready-first writer,
quota increase or reduced validation cadence is included in this transfer.

See the [approved work plan](../.agents/planning/download-bottlenecks-plan.md).
