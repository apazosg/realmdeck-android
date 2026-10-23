---
id: frontmage-runtime-prototype-2026-09-29
state: in-progress
date: 2026-09-29
---

## Summary

Approved by the user: “venga adelante entonces vamos con ello. a ver si podemos
tener más avances de cada apk”. This gate covers only a reproducible FrontMage/FEX
source-and-build baseline and its plan. It does not add UI features, launcher
wiring, probes, or runtime swapping.

The baseline is pinned to `FrontMage/winlator-llm` commit
`4293b75b449d11fdf66da567791c9165fa6df6a0`, not a moving branch. The corresponding
known APK is `frontmage-ab/app-debug.apk`, sha256
`01d364cafc77bb919a89668ac20618a108763c4e46ef3a81a104a4dc4bf98b2e`, package
`com.winlator.llm`, version `7.1`/code `16`. The device report records the known
combination `imagefs=21 + proton-10-arm64ec + FEXCore=2508 + VirGL 23.1.9 +
WineD3D`: `ApiProbe`/Hello passed, while `WowB.exe` failed with
`0xC0000005` at `WowB_loader.dll + 0x203E6AB` in an anonymous executable view.

The recipe will preserve upstream package/build identity for this baseline and
will not produce an APK intended to overwrite installed FrontMage. Any later
namespace change must be preceded by a complete package/entrypoint/path audit.

The source-only APK omitted `imagefs.txz`, `imagefs_patches.tzst`, and
`preinstall/proton-10-arm64ec.wcp.xz`. The exact known release APK was inventoried
locally and those three entries were recovered into generated external assets.
The recovered-asset build passed packaging and manifest hash checks; no runtime
was launched and no device/ADB operation was performed.

## Content

### Fixed provenance and pairing

| Item | Fixed evidence / decision |
|---|---|
| Upstream source | `FrontMage/winlator-llm@4293b75b449d11fdf66da567791c9165fa6df6a0`; predecessor `b1c7b23163bc15cf985b974af906b2d4c28203d6` retained as comparison provenance |
| Build wrapper | Upstream Gradle wrapper `7.5.1`; AGP `7.4.2`; JDK 17; compile SDK 30; min/target 26/28; NDK `22.1.7171670`; CMake `3.22.1`; arm64-v8a only |
| Known APK | `/home/adrian/wowforever-artifacts/frontmage-ab/app-debug.apk`, 791,878,864 bytes, sha256 `01d364cafc77bb919a89668ac20618a108763c4e46ef3a81a104a4dc4bf98b2e` |
| FEX bridge used by known APK | `fexcore-2508.tzst` sha256 `0afc4ded5830c2038237498ffe34c830533c6152ca7197349c06de4f44aee4a3`; `libwow64fex.dll` sha256 `aad28c9cdf85788e97ca355c2505433f12ba1a90931f2e27a52690690ee4d51f`, md5 `b985512ebfdb45d73384703d8584788f`; `libarm64ecfex.dll` sha256 `f1eb67578e0834ce1399b4356ea8832252849f6701a05e8439e1791466913556`, md5 `d7422128e90456a982293482028068b6` |
| Comparison FEX bridge | Ludashi 2508 variant documented in `FEX_STATUS-b1c7b231.md`: tzst sha256 `84b3fd9536b9ce8173663ac383e3072d791685d5a156c724a105a6f6974b3b9a`, DLL md5 `9c31c104412458919236a9a129d55243` / `8ee699e0208c96907919fb6711a7582c`; never mix it into this baseline |
| ImageFS/Wine | Known APK assets: `imagefs.txz` sha256 `f96d362b7e148e86ab0d2c290978bf39b38e5c7ffc8ae4adf1d2a65c62bbb780`, `imagefs_patches.tzst` sha256 `398f863a69957da8be6ee9b73134cecead64b833f82afcb84b60d1a6725eef5a`, `preinstall/proton-10-arm64ec.wcp.xz` sha256 `2501af75ffe4cffad0671e4e6613f0c8e6113fcf16f5a474a4dd3569135010d5` |
| Graphics assets | VirGL `23.1.9` sha256 `c42f22641079f678d501ca920a5b2e549add2b34d8d18e1e10f19ddb838eeed0`; Vortek `2.0` sha256 `909e3f7c555022faee9d126fa55f76b8cb9f08f925626b21373a14be0411c3c7`; wrapper sha256 `caffb48c1dd45c3e072452a00cd91ff2cb4881e0d65dae465d9106cf99917795` |

### Scope boundary

In scope: `scripts/frontmage-prototype/` source-pinned build recipe and overlays
without binary blobs; `docs/frontmage-runtime-prototype.md`; this plan; external
work under `/home/adrian/wowforever-artifacts/frontmage-prototype/`.

Out of scope: `scripts/runtime-prototype/`, Android launcher, probes, existing
FrontMage installation, package renaming, Box64/FEX mixing, and APK/UI features.
Artifacts remain external and are never copied into the repository.

### Gate criteria

1. Source checkout and submodule inputs are fixed and checksummed; no `latest`
   branch is used.
2. The recipe performs a clean upstream baseline build before any feature work.
   It reports the exact command, source commit, toolchain, APK path, size, and
   sha256, or stops with a concrete missing dependency (without a large hunt).
3. APK inventory records package, ABI, native libraries, assets, entry activities,
   ELF `PT_INTERP` values, hardcoded package paths, and provenance. All native
   starters are audited before any package rename; the known historical failure
   involving absolute `it.wowmobile` paths is explicitly checked.
4. License/security review identifies source licenses, submodules, proprietary
   or redistributable gaps, debug signing, permissions, and the rule that this is
   not a public/distributable release.
5. The next diagnostic stages are only planned: hello, API, dynamic-code, and
   one WowB launch, each with persistent bounded exports and explicit `PASS`,
   `FAIL`, `UNKNOWN`, or `SNAPSHOT`; no unknown is reported as a pass.
6. Release asset recovery is reproducible from the pinned local APK hash and
   never recombines FEX DLLs from another runtime pairing.

## Implementation / Action Items

- [x] 1. Add the pinned, binary-free FrontMage recipe under `scripts/frontmage-prototype/`.
  - [x] 1.1 Verify host/toolchain prerequisites before downloads; use existing JDK 17, SDK/NDK/CMake where compatible.
  - [x] 1.2 Clone/check out exactly `4293b75b...` in the external artifact root and record submodule commits.
  - [x] 1.3 Apply only textual overlays needed for recipe metadata/audit; do not rename the upstream package or add UI.
  - [x] 1.4 Build `assembleDebug` with the upstream configuration, installing only the authorized official SDK/NDK packages.
- [x] 2. Create `docs/frontmage-runtime-prototype.md` as the build gate record.
  - [x] 2.1 Record the real FrontMage reports and exact FEX/imagefs/Proton pairing.
  - [x] 2.2 Record APK/hash/path output and dependency sizes.
  - [x] 2.3 Record license/security gaps and the safe future namespace recommendation.
- [x] 3. Populate the external artifact workspace only with source checkouts, logs, reports, and build outputs.
  - [x] 3.1 Inventory APK ABIs, assets, native starters, `PT_INTERP`, and hardcoded absolute paths.
  - [x] 3.2 Preserve the known baseline APK; do not overwrite installed FrontMage.
- [x] 4. Record, but do not implement, staged diagnostic wiring for Hello → ApiProbe → DynCode → one WowB launch.
  - [x] 4.1 Defer Java-source reuse until compatibility review; no cross-edit of launcher/probe ownership.
   - [x] 4.2 Require persistent bounded exports and explicit unknown/failure markers.
- [x] 5. Recover the three missing release entries into external generated assets.
  - [x] 5.1 Inventory all release APK asset entries and record size/hash provenance.
  - [x] 5.2 Build with the recovered assets and verify the packaged manifest hashes.
  - [ ] 5.3 Validate the pairing on the target mobile runtime; no device work is part of this gate.

## Open Issues

- The host default remains JDK 21, but the recipe uses the explicit external JDK
  17. SDK 30 and NDK 22.1 were installed from the official SDK repository under
  the approved additional-dependency budget; no version substitution was made.
- FrontMage upstream includes an OpenXR gitlink; it is pinned to
  `288d3a7ebc1ad959f62d51da75baa3d27438c499` and recorded in the external
  provenance report. No binary substitute is allowed.
- The source commit's debug APK is signed with an Android debug certificate;
  therefore no public distribution or update-overwrite claim is valid.
- The public source build omits the release-only `imagefs.txz`,
  `imagefs_patches.tzst`, and `preinstall/proton-10-arm64ec.wcp.xz` assets. They
  are now recovered only as external reference/build inputs; redistribution and
  autonomous-runtime packaging still require a separate license/provenance
  decision.
- No mobile claim is made: the existing device has no usable ADB session in this
  task. Box64 and FEX must remain separate experimental baselines.
- The integrated mobile/runtime gate remains open; this plan must not be marked
  `done` until the known pairing is validated on mobile and the staged probes
  have explicit bounded results.
