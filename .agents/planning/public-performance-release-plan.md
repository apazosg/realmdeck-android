---
id: public-performance-release-2026-10-25
state: draft
date: 2026-10-25
---

## Summary

The user requested public integration and a new public version while the C145
download trial runs. Prepare a reviewed experimental prerelease from the public
performance branch, not a merge of unrelated internal history. Publication and
remote branch changes wait for approval of this plan and the release gates below.

## Content

- Target repository: `apazosg/realmdeck-android`. Integrate the curated
  `perf/download-bottlenecks` branch into its public `main`, preserving remote
  changes and branch protection. Use a reviewable pull request when available;
  no force push, unrelated-history merge, reset or replacement of internal `main`.
- Proposed public build: **C146 / 0.2.122-performance-preview**, tag
  `c146-0.2.122-test`, explicitly a prerelease. C145 remains the diagnostic build
  being measured; do not replace its artifact with different bytes.
- Latest public prerelease observed: C142. Its launcher-entry permission fix was
  not included in the selective performance port. The actual audited C145 APK
  still requires `android.permission.DUMP` on its exported functional launcher
  Activity. An ADB start is not evidence of ordinary launcher-icon access.
  Restore that narrow functional-entry fix, preserve diagnostic-component
  protections, review the boundary and test normal icon launch before release.
- Keep manual APK updates explicit. This branch does not include C142's APK
  notification feature; do not claim an automatic update path. Adding or repairing
  an update checker is not part of this release plan.
- Preserve approved integrity, quotas, checkpoint compatibility and heavy/light
  storage-policy tradeoffs. No further download optimization is implied.
- Finish the active C145 trial without concurrent phone UI operations. Record
  its actual result and limitations before deciding whether to include group16
  in the public candidate; a failure blocks publication pending resolution.
- Review public diffs for credentials, device identifiers, private captures and
  internal operational records. Publish only redistributable, build-bound assets;
  no client files or signing secrets. Check licensing/source obligations and
  notices; a partial source archive must not be described as complete
  Corresponding Source or a self-contained build environment. Unresolved
  redistribution obligations block publication.
- Build and audit the exact final candidate, signature, version, source set and
  payloads. Provide English notes, SHA256SUMS, source/provenance scope and notices.
  Keep existing C142 assets immutable and verify uploaded asset digests.
- Publish source and an experimental prerelease only after the final source,
  device-entry, recovery and artifact gates pass. No stable-release or general
  2x performance claim based on a single device or distinct object cohorts.

## Implementation / Action Items

- [ ] Approve scope, prerelease channel and proposed C146 identity.
- [ ] Complete and document the active C145 trial and normal final Pause.
- [ ] Verify current remote refs, public diff and authenticated publishing access.
  The GitHub CLI was not found on the current PATH; do not expose credentials or
  change security settings to bypass missing access.
- [ ] Restore only the functional-entry permission fix; add focused regression
  protection and independent security review without opening diagnostic entries.
- [ ] Prepare English release notes and current-status documentation, with known
  baseline test failures and measured-device limitations stated honestly.
- [ ] Build/audit C146 and verify normal launcher-icon access, upgrade without
  reset and preserved progress; do not interfere with the active C145 trial.
- [ ] Audit asset privacy, source provenance, redistribution scope and checksums.
- [ ] Push the reviewed public branch, integrate via the permitted public-main
  workflow and tag the exact approved source commit; never import private history.
- [ ] Publish the prerelease and verify its public metadata and asset hashes.

## Open Issues

- This plan awaits user approval before implementation, push, merge or release.
- C145's completed measurement is still pending. It is not yet public-release
  acceptance, and final C146 binary checks cannot be inherited from C145.
- Publishing credentials/permissions and remote branch protections remain to be
  checked read-only. No authenticated publication has been attempted.
