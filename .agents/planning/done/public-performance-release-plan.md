---
id: public-performance-release-2026-10-25
state: done
date: 2026-10-25
---

## Summary

The user requested public integration and a new public version while the C145
download trial runs. Prepare a reviewed experimental prerelease from the public
performance branch, not a merge of unrelated internal history. Publication and
remote branch changes remain subject to the release gates below. The user approved
this plan, including public integration, push and prerelease publication, on
2026-10-25. Internal history and private operational material remain excluded.

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

- [x] Approve scope, prerelease channel and proposed C146 identity.
- [x] Complete and document the C145 trial and normal final Pause. The observed
  0.401 MB/s does not establish a performance improvement: force-helper cost per
  object fell, while source-readiness waits grew. The user subsequently approved
  publishing C146 experimentally with these limitations, not as a demonstrated win.
- [x] Verify current remote refs and authenticated access: public `main` remains
  at `457056e`; authenticated read-only checks confirm the repository account has
   push access. Credentials were not logged; subsequent authorized mutations are
   recorded in the verification section below.
- [x] Restore only the functional-entry permission fix; add focused regression
  protection and independent security review without opening diagnostic entries.
  Commit `26a61d2` restores the known launcher entry; focused manifest/auditor tests
  pass, including inherited application-permission rejection. Actual APK auditing
   and ordinary launcher-icon access were subsequently verified separately.
- [x] Prepare English release notes and current-status documentation, with known
  baseline test failures and measured-device limitations stated honestly.
- [x] Build/audit C146 and verify normal launcher-icon access, upgrade without
  reset and preserved progress; do not interfere with the active C145 trial.
- [x] Audit asset privacy, source provenance, redistribution scope and checksums.
  The receipt-bound source whitelist and public text received independent review;
  actual archive entries and all five asset checksums were checked locally. The
  archive explicitly excludes signing material and private captures, and states
  its partial runtime-source/build-input coverage.
- [x] Push the reviewed public branch, integrate via the permitted public-main
  workflow and tag the exact approved source commit; never import private history.
- [x] Publish the prerelease and verify its public metadata and asset hashes.

## Open Issues

- C146 publication is complete. This closes the release operation, not the
  continuing performance investigation or general device acceptance.
- C145 completed normally but measured 0.401 MB/s on a different object cohort.
  Do not publish the group16 policy as a demonstrated end-to-end improvement.
  The user's subsequent instruction to proceed resolves this as an experimental
  publication with explicit non-improvement caveats. C146's own audit and upgrade
  fingerprint checks pass; a MAIN/LAUNCHER start from the ordinary launcher UID
   was recorded after installation. Asset review and publication verification passed.
- Authenticated read-only checks succeeded; public `main` is not protected.
   Final diff/asset review preceded the authorized push and integration.
- On 2026-10-25 the user explicitly reconfirmed permission to redistribute the
  bundled proprietary components identified in the inherited notices. Record this
  as the owner's confirmation, not an independent legal audit. No further rights
  clarification is pending from the user. Preserve component notices and prepare
  the applicable application source/build materials; do not mislabel a partial
   runtime-source collection as complete Corresponding Source.

## Publication verification — 2026-10-25

- [PR #1](https://github.com/apazosg/realmdeck-android/pull/1) merged the reviewed
  public branch without importing internal history. Merge commit:
  `66092f5bc354ada23bc269bd3cc5da7010379411`.
- The merge parents match the expected public base and reviewed branch head;
  its tree matches the reviewed head exactly. Tag `c146-0.2.122-test` points to
  this merge. The APK's build-source commit remains `26a61d2`, with subsequent
  release-documentation changes only; no rebuild claim is implied.
- [C146 release](https://github.com/apazosg/realmdeck-android/releases/tag/c146-0.2.122-test)
  is publicly accessible with `prerelease=true` and `draft=false`.
- All six assets are uploaded: APK, application-source archive, README, component
  notices, build summary and SHA256SUMS. Each server SHA-256 digest matches the
  checked local asset; unauthenticated API verification independently confirmed
  metadata, tag target, merged PR and all six digests.
- C142 attachments were not replaced. No force push or internal-history merge
  was performed. Performance limitations and partial runtime-source coverage
  remain explicit in the published notes and assets.
