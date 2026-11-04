---
id: android-package-identity-2026-11-04
state: in-progress
date: 2026-11-04
---

## Summary

The user wants the Android application ID `com.pelele.wowforever` instead of the
prototype ID `com.wowforever.arm64probe`, while fixing the current fresh-install
flow. Android treats these as separate applications. The intended internal
client path is `/storage/emulated/0/Android/data/com.pelele.wowforever/CustomGames/WoW Forever`.

The old application's private state and files are not available to the new
package automatically. Preserve the installed prototype and all of its data;
do not uninstall, delete, copy, or migrate them. The user has not approved a
data migration. No full client download has been approved by this plan: retain
the existing plan/cost confirmation before downloading client payloads.

## Content

- The ID is embedded in the build overlay, reconstruction and audit tools,
  launch contract, runtime/supervisor identity checks, path validation, and
  tests. Some mentions in historical documents and evidence describe old builds
  and must not be rewritten as if they described the new APK.
- US-007 requires app-specific storage identity validation and no automatic
  migration; US-008 requires guarded recovery and explicit cost consent. The
  fresh-install fix must retain those guarantees under the new identity.
- This is a local experimental build and device validation, not a release,
  publication, general compatibility claim, or evidence of complete gameplay.

## Implementation / Action Items

- [ ] 1. Finish diagnosing and verifying the fresh-install recovery and
      nonexistent-root path, without weakening journal, storage, or runtime gates.
- [ ] 2. Inventory current production package-ID consumers and make the minimum
      coordinated changes required for `com.pelele.wowforever`; preserve provenance
      of previous builds and avoid broad replacement of historical records.
- [ ] 3. Update affected tests and any proportionate specification artifacts;
      run focused storage, launcher, runtime identity and updater checks. Review
      package isolation and security-sensitive identity checks independently.
- [ ] 4. Build and audit a new APK outside the repository, verify its package,
      signing, artifacts and source inputs, then install it alongside the old app
      on the explicitly selected ADB device without removing or altering old data.
- [ ] 5. Exercise the new app's fresh install flow and confirm the displayed
      destination and recovery behavior through to the download-cost consent.
      Do not begin the full client download without the existing consent.
- [ ] 6. Document actual evidence and limitations, commit only task-owned changes,
      and retain this completed plan in the appropriate archive.

## 2026-11-04 — VPN download policy and C220 candidate

The user asked to allow downloads with an active VPN and to publish a new preview.
`ForeverWifiDownloads` now accepts a VPN whose underlying transport is Wi-Fi;
cellular and VPN-over-cellular remain ineligible. The candidate for that preview
is C220 / `0.2.196-preview`. This plan's package-identity change ships in that
build; the release APK installs alongside the prototype and does not migrate its
data. Build/audit and the focused VPN test run on the host; installing beside the
prototype and walking the device flow remain unverified here.

## Open Issues

- The user has not requested migration of the old app's settings, credentials,
  runtime, incomplete download, or client. If any turns out to be needed, stop
  and establish an explicit safe migration scope before touching those data.
