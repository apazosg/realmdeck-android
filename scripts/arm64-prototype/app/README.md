# Windows ARM64 launcher prototypes

## Current functional launcher: C116; resumption blocked

**`0.2.92-app-branding` / code 116** is installed. The accepted C115 Kotlin UI is
byte-identical; the user approved its corrected Settings screenshot.
The product UI is `overlay/ForeverFunctionalPocActivity.kt`,
not the diagnostic app under the repository's `android/` directory.

- Home follows real client/storage/update state: download an absent client,
  complete an incomplete installation, or show the active transfer with phase
  progress, Pause and View download. Phase percentages are not whole-client
  installation percentages. There is no usable Prepare/Launch action during an
  active transfer or when the required client is absent.
- Ready clients retain Prepare, explicit consent and Launch through one route;
  the client-manager shortcut no longer bypasses consent. A complete client and
  physical launch/login are still unproven; see the status document below.
- Classic Forever is fixed at build time (`FOREVER_SERVER_PROFILE`), not a runtime
  server selector. Memory patch/capture remain opt-in and off by default.
- GPU information is a separate Settings destination; technical paths/details
  stay secondary. Six original local vectors live in `ForeverFunctionalIcons.kt`,
  with existing labels retained and decorative icons excluded from announcements.
- The five Settings actions share `ForeverSettingsAction`: left-aligned 20dp
  icons, 12dp icon/text gap, common 16dp horizontal/12dp vertical padding,
  minimum 64dp height and 12dp between actions. Labels can grow for accessibility.
  The tested device showed five 272×64dp buttons, equal gaps and single-line labels.

C115 passed six focused tests, canonical reconstruction/build and
`PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT` (2187 sources/178 payloads). Original source
baseline/postimage checks and payloads were not relaxed. Installing with `-r`
preserved checkpoint, receipt and storage preferences byte for byte; final
resumption evidence is tracked separately, not inferred from installation.
That resumption is now proven: the same 70450/USB transfer committed 168 new
objects, with the foreground service and 16 fetch workers active. Returning from
the manager leaves it running; this is not proof of a complete client or login.

See [product status](../../../docs/launcher-app-status.md) for UI acceptance,
tests and limitations, and [downloader handoff](../../../docs/downloader-handoff.md)
for the authoritative installed APK/hash, transfer state, evidence and safe
re-entry. Do not rerun one-shot install/confirmation drivers or treat historical
measurements below as current device state.

### Android icon and startup: C116 continuation

The original shield/diamond is also provided as native Android resources in
`branding-res/`: nine explicitly admitted XML files, with an adaptive icon,
API33+ monochrome layer, API31+ native splash and an API29–30 starting window.
The application, round icon and both existing aliases use the same icon; no
component identities or enabled states change. The entry theme inherits
`Theme.Pluvia`, including its API35 bar/edge-to-edge policy. There is no extra
Activity, dependency, animation timer or change to the accepted Kotlin UI.

These resources are staged by `reconstruct_functional_source.py` and recorded in
its receipt. The older overlay-only applicator is not the C116 delivery recipe.
Tests exercise strict resource admission and branding bindings; the final APK's
compiled bindings were also inspected with `aapt`. C116 is installed and the real
native splash is correct; Android app-info still displays the upstream icon and
the desktop icon has not been located. Do not assume an icon-cache cause.

All three durable control files survived installation byte-for-byte, but C116
resumption is blocked **before costs/consent**: planning detects an unfinished
transaction. No recovery was actually called, despite the generic UI message.
Read-only journal/staging diagnosis is in progress; do not retry/reset/clear state
or invoke recovery blindly. Some recovery paths reset CASC. The C115 resumption
evidence above is historical, not a claim that C116 is currently downloading.

## Historical automated launcher: code 33

Historical deliverable: **Forever 0.2.9-automated / code 33**, an integrated private
launcher candidate. The home action is **Preparar y lanzar**, not three manual
custom games. See `docs/automated-arm64-launcher.md` for the artifact hash,
verification evidence, download bounds and phone instructions.

Code 24 was tried on the phone and failed before launch: the verifier incorrectly
required a nonempty `files` list for Proton. The pinned Wine profile legitimately
has `files: []`: this is a DLL-copy list, not its installed tree manifest.
Code 25 checks Wine binaries/libraries/prefixPack and preserves strict DXVK
copy-list checks. It reuses the installed profile without deleting user data.
Six regression tests use the real `ContentsManager` and pinned WCP profile JSONs;
two Wine-selector tests also pass. The user then reached client provisioning in
code 25, demonstrating that the runtime-profile gate no longer blocked that run.

Code 25 stopped at `BLTE decoded size mismatch: 154 != 181`: the generator had
treated VFS encoded sizes as decoded sizes. Code 26 corrects 828 metadata sizes and
checks all **832 runtime objects** against independent cascette decoding and the
APK's `ForeverBlte` streaming reader on the host JVM. All 13 Python regressions,
9 existing BLTE/provisioner tests and 2 real-catalog reader tests pass. Raw object
hashes, encoded sizes and the 748,157,712-byte transfer bound are unchanged; valid
cached downloads and installed runtime are preserved. No Wine spawn/render/login
is inferred from passing the provisioning test.

Code 26 then hit HTTP 404 for `e092b002f52c0827ce4b65d534d7316d`: the producer
misread a zero-offset CDN file index as a payload archive. Code 27 uses the verified
direct object and rejects file-index hashes as payloads. All **35 loose routes**
returned valid 64-byte HTTP 206 probes; the affected 3,932,016-byte object returned
200 and passed BLTE/decoded MD5. The live investigation received only 3,934,256 bytes.
Twenty-two Python regressions and **9 focused JVM tests** pass, including actual
object download/cache reuse via the production downloader with fake HTTP (no test
network), invalid index routes, identity/range totals, and preservation after a
404. The 832 metadata payloads, runtime, JNI and transfer bounds are unchanged.

Code 27 reached Wine bootstrap on the phone, with runtime controls and a black
screen; minimizing/restoring returned to Forever home. The uploaded ZIP recorded
the Wine root but no validated WoW identity or frame. It also showed native library
rewrites to the old `app.gamenative` package. The actual unchanged WX ELF supports
`WINEMU_HOST_PKG`, then `HOST_PKG`, before that default: code 28 sets both to our
package at the final Bionic merge while preserving the complete preload/HODLL.
Bounded Wine process/module/SEH logging is enforced there too, after upstream's
`WINEDEBUG=-all`. No native rebuild, runtime swap or driver workaround was made.

Lost supervision now persists `INTERRUPTED`, not stale `ACTIVE/RUNNING`. Lifecycle,
bounded helper/preload evidence and own-process basename hints are exported. Host
exit attribution requires PID/package/unique run summary/time, not an old signal
alone. A spawn token is consumed once; vetoes cannot create a fake spawn failure,
and terminal UI callbacks cannot resurrect a cancelled run. This does **not**
claim physical Surface reattachment or successful minimize/restore on Android.

Code 28 was tested and the original Android host exited by SIGINT about 27 s after
Wine spawn, with exact PID/package/RUN/time attribution. Sender remains unknown.
Its stderr retained only the first 512 KiB, ending ~15 s before the host exit.

Code 29 adopts the concrete direct-AA64 route from
`jaredgei/wow-forever-android@2619b9f7be80d5549be96e8e04135228651b57f1`:
Wine/explorer receives the verified `A:\WowB-ARM64.exe` without the Windows
`winhandler.exe` wrapper. It requires PE AA64/PE32+, catalog MD5/size, exact managed
path/cwd/A-drive/RUN and canonical flags, not a filename heuristic. X11 input,
Java WinHandler, upstream nonmanaged routes, WCPs, graphics and private auth remain.
GLX/GStreamer flags were already present; fork startup NORMAL versus ESSENTIAL and
`.flavor.info` layout differences are recorded, not mixed into this experiment.

Head/tail output snapshots now keep 128/384 KiB per stream atomically, every ~750 ms
and at EOF. Exported final stream state is always UNKNOWN rather than overstating
freshness after host death/IO failure. Strict metadata, canonical boot IDs, late
spawn ownership, post-exec disk failure, terminal immutability and PGID/SID metadata
are tested. Native SIGINT arming is hard-UNSUPPORTED on Android before any mutation;
the Linux child tests/decoder do not establish Android signal-sender coverage.

Code 29 was tested and rejected the packaged catalog at the direct-launch gate:
2,501,689 real asset bytes exceed its independent 2 MiB limit. This was reproduced
using that production reader and actual Robolectric assets before fixing it.
Code 30 uses one `ForeverClientCatalog` reader for provisioning and launch, with
an 8 MiB cap, strict UTF-8/JSON and unchanged structural/pin checks. Exact-cap EOF,
cap+1 rejection, fragmented/zero reads and invalid metadata are covered. The APK
auditor checks packaged asset size against the actual source reader cap, preventing
another successful packaging gate for an immediately unlaunchable catalog.
**17 focused tests pass without skips** (catalog 5, launch 7, provisioner 5); no
runtime/client payload or transfer bound changes and no new network/PE execution.

Code 30 was tested automatically on USB xaga: a fresh install omitted stock
Bionic ImageFS, causing a missing WX preload and Wine exit 1 in 39 ms. Codes 31–33
use the stock context-only fetch/installer, including owned migration/extraction,
pre-operation symlink/variant/conflict checks and AA64 postconditions. Missing
stock variant metadata commits only after success; empty recreated Proton defaults
are allowed, while nonempty collisions preserve sentinels and block mutation.

**Code 33 is physically tested on xaga/Mali, not PowerVR.** The linker blocker is
resolved and Wine stays alive, but Explorer enters an execute-fault loop during
`winex11.drv` initialization. No WoW identity/frame/login is proved. The trial was
stopped after capture to avoid a resource-consuming loop. No driver/WCP/JNI changes
or signal masking were made. See `docs/usb-lab-xaga.md` for exact tests and evidence.
Fresh recipe: 61 inputs; build and actual APK audit passed. Bootstrap 18 distinct
fixture cases checked across affected rounds; existing checks are not blindly rerun.
After a Wine spawn, retry requires an Android reboot until stronger process-tree
containment is implemented. The finite CDN bootstrap is not a complete game.
Private trial only; rendering and Forever authentication remain open gates.

## Current implementation and reproduction

- `apply_overlay.py`: pinned GameNative patches, all `Forever*.kt` sources/tests,
  production bridge/JNI, canonical contract, client catalog, runtime assets and
  provenance. Inputs are external: `--source`, `--bundle`, `--probes`, `--catalog`,
  `--native`; no Blizzard payload is added.
- `verify_overlay.py`: applies the recipe twice to the touched source files from
   the verified source archive and compares all 59 source/asset inputs to the build.
- `verify_apk.py`: audits package/version/labels/ABI, ELFs, update-compatible
  certificate, source/asset hashes (including the unchanged WX preload) and absence
  of Blizzard/CASC payloads.
- `stage_delivery.py`: stages the audited APK, instructions, hashes and source
  recipe outside the repo, refusing to overwrite different artifacts.
- `INSTRUCCIONES-automated.txt`: user-facing test procedure.

Use the pinned JDK 17 and existing Android SDK. After applying the overlay, build
with `--offline --no-daemon --no-configuration-cache --max-workers=1`:

```text
./gradlew :app:assembleModernDebug
```

Current bounded-reader regression gate (uses actual packaged test assets, not
only hand-written metadata):

```text
JAVA_TOOL_OPTIONS="-Dwowforever.catalog=<external-catalog.json>" \
  ./gradlew :app:testModernDebugUnitTest \
  --tests app.gamenative.ForeverCatalogAssetTest \
  --tests app.gamenative.ForeverArm64LaunchTest \
  --tests app.gamenative.ForeverClientProvisionerTest
```

The cache-only full-reader gate uses the external manifest generated by
`../client/runtime_audit.py`, never payloads copied into tests or the APK:

```text
JAVA_TOOL_OPTIONS="-Dwowforever.catalog=<external-catalog.json> -Dwowforever.runtimeAudit=<external-runtime-audit-manifest.json>" \
  ./gradlew :app:testModernDebugUnitTest --tests app.gamenative.ForeverBlteCatalogTest
```

The manifest records the exact catalog SHA plus cached object paths, sizes and
hashes. Without the opt-in property these host-only tests skip; delivery runs them
with both properties and checks that neither test skipped.

The CDN gate uses the bounded live evidence previously collected by
`../client/cdn_source_audit.py`, bound offline to the exact final catalog:

```text
JAVA_TOOL_OPTIONS="-Dwowforever.catalog=<external-catalog.json> -Dwowforever.cdnAudit=<external-cdn-sources.json>" \
  ./gradlew :app:testModernDebugUnitTest --tests app.gamenative.ForeverCdnSourceTest --tests app.gamenative.ForeverClientProvisionerTest
```

Both properties were provided for code 27; all nine tests executed without skips.
The recorded full object remains outside the repo/APK and is read only by the test.

The code-29 gate checked **58 distinct cases across targeted rounds**, without
skips: 45 unchanged cases passed initially, and all 13 affected cases passed in the
final run. Actual pinned WCP metadata and inert component fixtures are used:

```text
JAVA_TOOL_OPTIONS="-Dwowforever.runtimeProfiles=<external-runtime-directory>" \
  ./gradlew :app:testModernDebugUnitTest \
  --tests app.gamenative.ForeverArm64LaunchTest \
  --tests app.gamenative.ForeverSignalTraceTest \
  --tests app.gamenative.ForeverHostSafetyTest \
  --tests app.gamenative.ForeverRuntimeSetupTest \
  --tests app.gamenative.ForeverRuntimeEvidenceTest \
  --tests app.gamenative.ForeverRecoveryTest \
  --tests app.gamenative.ForeverProcessPolicyTest \
  --tests app.gamenative.ForeverLaunchStateTest \
  --tests app.gamenative.ForeverHostLifecycleTest \
  --tests app.gamenative.WineInfoArm64ResolutionTest
```

The real `ProcessHelper` veto test starts no binary. Final environment tests retain
SysV SHM/evshim/WX, HODLL, display and prefix while replacing only the five managed
keys. Existing CDN/full-reader/Rust checks were not repeated without a changed
component. Phone startup, client data sufficiency, frames and Forever login remain
open; the previous Wine spawn requires an Android reboot before the next trial.

The final audit caught stale provenance in an incremental asset merge. For the
delivery gate force **only** the asset task, then audit the actual APK:

```text
./gradlew :app:mergeModernDebugAssets --rerun :app:assembleModernDebug
python3 scripts/arm64-prototype/app/verify_apk.py \
  --apk <external-apk> --catalog <external-catalog> --native <external-so> \
  --build-tools <sdk>/build-tools/36.0.0 --output <external-audit.json>
python3 scripts/arm64-prototype/app/stage_delivery.py \
  --audit <external-audit.json> --delivery <existing-external-directory>
```

The initial scaffolding/audit below is retained as historical context; the
integrated `ForeverLaunchController` now owns preparation, provisioning,
configuration, launch supervision, recovery, patch gating and SAF export.

This directory is the isolated native-ARM64 app variant. It does not modify the
existing `com.wowforever.launcher` diagnostic app or the existing FrontMage/FEX
prototype. `BASELINE-BUILD.md` records the first build attempt against the exact
community-pinned GameNative source.

## Selected pairing

Use the GameNative Bionic pairing as the compatibility reference:

- GameNative source: `utkarshdalal/GameNative`
- Source commit: `205cbf8ddc06f855a1b85ba57c78c4be9a6930aa`
- Wine/Proton WCP: `proton-11.0-90624-arm64ec`
- DXVK WCP: `2.4.1-wow-aarch64-test`
- Android ABI: `arm64-v8a`
- Renderer: Android system Vulkan on PowerVR; no Turnip or Adrenotools

The WCP is named `arm64ec` because that is the GameNative/Wine selector model,
not because the supplied WoW PE was proven ARM64EC.  The static audit found 772
ARM64 PE files, 771 x86 PE files, and one x86-64 PE file; no ARM64EC PE was
found.  The client must still be checked independently before launch.

## Historical runtime dependency boundary

The GameNative integration supplies these runtime layers:

1. **App-private runtime**: the two WCP payloads and `prefixPack.txz`, extracted
   from the verified bundle by the runtime materializer.
2. **Bionic/ImageFS support**: `/usr/bin/env`, `/usr/lib`, fonts/configuration,
   Wine shared-library search paths, and the app-side process/proot glue.  These
   are not included in the community WCP and cannot be inferred from the WCP
   alone.
3. **Android native glue**: the GameNative-style `libproot.so`,
   `libpulseaudio.so`, `libandroid-sysvshm.so`, `libevshim.so`, and the selected
   Bionic redirect shim.  These must be supplied by the app/runtime build and
   audited as AArch64 ELF; they are not `wineandroid.so` files in the Proton
   WCP.
4. **Display/audio services**: an X/display endpoint, PulseAudio or ALSA
   endpoint, and the GameNative `winhandler.exe`/input path if controls are
   exposed.  The WCP contains `winepulse.so`/`winepulse.drv` and
   `winedirectaudio.so`/`.drv`, but no Android audio server or `winhandler.exe`.

Do not copy the native ARM64 runtime into the existing FEX prefix.  Do not add
Box64, x86-64 DLL overrides, Turnip libraries, or a packaged `libvulkan.so`.

## Historical diagnostic integration checkpoint

The first diagnostic APK reused the existing app path rather than implementing
a second runtime:

1. Add one diagnostic entry to the existing GameNative local/custom-game flow,
   not to the current Forever launcher. The entry supplies a client folder and
   selects `arm64ec` plus the Bionic container.
2. Import the two verified WCPs through `ContentsManager.extraContentFile()` and
   `finishInstallContent()`, then resolve the installed profile through
   `ContentsManager.getProfileByEntryName()`/`WineInfo.fromIdentifier()`.
3. Reuse `BionicProgramLauncherComponent` and `XServerScreen` for ImageFS,
   SysV SHM, evshim, redirect, display, and audio setup. Do not transplant the
   FrontMage framework or duplicate those launch paths.
4. Register the supplied client as a GameNative custom game. The pinned source's
   custom-game path bypasses Steam cloud/login requirements; Steam remains needed
   only for Steam-sourced games and store features. This is the correct
   no-account diagnostic route, but it must be verified by source-level tests
   before adding UI.

The stock source build gate has passed. The bounded setup overlay now packages
the two verified WCP inputs and the three externally built diagnostic probes,
then imports/registers them through the existing GameNative content/custom-game
APIs. It deliberately keeps the client, prefix, ImageFS, and runtime logs
outside the APK; the APK contains no WoW client. `HelloArm64.exe` is the first
configured custom-game executable. Multi-stage sequencing, automatic lifecycle,
SAF support-bundle integration, and Android execution remain later checkpoints.

## Launch model

The source audit shows that the Bionic launcher:

- installs or resolves the Proton profile through `ContentsManager`;
- uses `WineInfo` with the `arm64ec` selector;
- launches the native Wine shell path directly, without prepending Box64 when
  `WineInfo.isArm64EC()` is true;
- sets `HOME`, `TMPDIR`, `DISPLAY`, `PATH`, `LD_LIBRARY_PATH`, `PREFIX`,
  `XDG_DATA_DIRS`, `ANDROID_SYSVSHM_SERVER`, and `WINEESYNC`;
- uses app-provided `LD_PRELOAD` glue for SysV SHM, evshim, and Bionic
  redirection; and
- imports the client as a mapped Windows drive and launches it with `-d3d11`.

The current app-side contract is in `launch-contract.json`; it describes the
automated route, production JNI boundary and deliberate single-attempt policy.

## Static dependency findings

The materialized Proton tree contains 60 AArch64 ELF files.  Its native loader
is `/system/bin/linker64`.  After forcing the `C` locale for `readelf`, the
relevant `DT_NEEDED` groups are:

- Wine core: `libc.so`, `libdl.so`, `libm.so`, `ntdll.so`, `win32u.so`.
- Display/SHM: `libX11.so`, `libXext.so`, `libandroid-sysvshm.so`.
- Audio: `libpulse.so`, `libasound.so`, `libaaudio.so`, `liblog.so`.
- Fonts: `libfontconfig.so`, `libfreetype.so`, `libexpat.so.1`, `libpng16.so`,
  `libz.so.1`, `libbrotlidec.so`, `libbrotlicommon.so`, `libbz2.so.1.0`.
- Optional media: GStreamer `libgst*.so`, GLib/GObject/GIO, and FFmpeg
  `libavcodec.so.62`, `libavformat.so.62`, `libavutil.so.60`.
- Other optional path: `libdrm.so`.

There is no `DT_NEEDED` entry for `libvulkan.so`; the PowerVR variant must use
`/system/lib64/libvulkan.so` through app integration rather than package a
driver or set a Turnip ICD.

The tree has no `wineandroid.so`, no GameNative JNI libraries, no `winhandler`
binary, and no Android Vulkan loader.  `libvulkan-1`/`vulkan-1.dll` are Wine
guest-side files; Android Vulkan must be resolved from `/system/lib64` by the
app/native glue.  Audio remains a service dependency, not a WCP-only feature.

## Historical baseline verification (superseded by the current delivery above)

- Outer bundle hash and WCP extraction: verified.
- ELF/PE headers and prefix member paths: statically audited.
- Source audit: completed against the pinned GameNative commit above.
- Android execution, PowerVR rendering, audio, login, and Classic Forever
  authentication: **not tested**.
- Official CDN client: **verified** as PE `0xaa64`, build `1.60.1.70009`.
- Pinned GameNative source build: **successful** with the exact AGP `8.8.0` /
  Gradle `8.12.1` source configuration; see `BASELINE-BUILD.md`.
- Baseline APK: **built** outside the repository for `arm64-v8a`; no WoW client
  or CASC was packaged.
- APK install/device execution: **not performed**.
