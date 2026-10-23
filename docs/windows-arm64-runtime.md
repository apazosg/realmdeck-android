# Windows ARM64 runtime audit and app handoff

Updated 2026-09-29. This is a static integration audit plus the first pinned-source
build gate. It does not claim an Android launch, PowerVR rendering, audio, login,
or Classic Forever success.

## Decision

The minimal viable experiment is a **new isolated ARM64 app variant** using the
GameNative Bionic/Wine pairing as its reference. It must not reuse the existing
FrontMage/FEX prefix or change the current x86_64 launcher. The runtime package
alone is insufficient: GameNative's Bionic ImageFS and Android-side native glue
are required around the Proton WCP.

The implementation handoff is deliberately limited to:

- `scripts/arm64-prototype/runtime/`: verified materialization and static audit;
- `scripts/arm64-prototype/app/`: launch, packaging contract, and pinned-source
  build record; and
- this document.

The exact GameNative source was acquired, inspected, and built as a stock
`arm64-v8a` modern debug APK. No APK was installed. The first offline build
stopped because AGP 8.8.0 was not cached; the exact wrapper then resolved the
official dependencies online and completed with the existing external JDK 17.

## Source audit

The source was inspected at the exact commit named by the community bundle:

`https://github.com/utkarshdalal/GameNative/tree/205cbf8ddc06f855a1b85ba57c78c4be9a6930aa`

Relevant behavior:

| Source | Finding |
|---|---|
| `BionicProgramLauncherComponent` | Uses Bionic ImageFS paths, sets Android/Wine environment, adds SysV SHM/evshim/Bionic redirect preload, and selects the final Wine command. |
| `GuestProgramLauncherComponent` | The generic path is a proot-rooted `/usr/bin/env` launch; it binds mapped drives and forces `WINEESYNC=0` in that path. |
| `WineInfo` | `arm64ec` is a recognized Wine selector. For that selector the Bionic launcher does not prepend Box64; it invokes the Wine path directly and selects `HODLL` for the configured emulator. |
| `ContentsManager` | WCP imports require profile metadata, `bin`, `lib`, and `prefixPack`; Wine/Proton installs are kept under shared content and linked into the ImageFS. DXVK installation is a trusted-file copy into `system32`. |
| `ImageFsInstaller` | The Bionic ImageFS supplies the Linux userland, Wine link, redirect libraries, and guest libraries. It is separate from the WCP payload. |
| `XServerScreen` | Builds `wine explorer /desktop=shell,<size> ...`, binds drives, and starts XServer, SysV SHM, and PulseAudio/ALSA components before the guest process. |
| `PulseAudioComponent` | Starts the app-provided AArch64 `libpulseaudio.so` and exposes a Unix socket; the Wine `winepulse` files alone do not provide an Android audio server. |
| `extras/vulkan.c` | The system path opens `/system/lib64/libvulkan.so`; custom driver paths use Adrenotools. The PowerVR variant must use only the system path. |
| `TarCompressorUtils` | The upstream extractor does not enforce traversal safety; the prototype materializer therefore uses its own checked extractor. |

### Consequences for this project

The community WCP is a component payload, not a standalone Android runtime.
The future app must supply or build the following outside the WCP:

- Bionic/ImageFS `/usr/bin/env`, `/usr/lib`, fonts/configuration, and Wine
  runtime links;
- AArch64 Android process/root glue (`libproot.so` or an equivalent),
  `libandroid-sysvshm.so`, `libevshim.so`, and the selected Bionic redirect shim;
- X/display, SysV SHM, and audio services;
- `winhandler.exe` and the input/control path if controls are enabled.

The Proton WCP contains no `wineandroid.so`, no GameNative JNI libraries, no
`winhandler.exe`, and no Android Vulkan loader. It does contain Wine guest-side
`winevulkan`/`vulkan-1` files and Pulse/direct-audio Wine modules.

## Materialized runtime evidence

Input bundle:

`/home/adrian/wowforever-artifacts/references/gamenative-rp6/WoW-Forever-GameNative-RP6-all-in-one-2026-09-25.zip`

Outer SHA-256:

`70f1f9924d3649fed811634024eb5b40544485aa11b43d0f28959e95211fa3e2`

Selected payloads:

- `proton-11.0-90624-arm64ec.wcp`
- `dxvk-2.4.1-wow-aarch64-test.wcp`

Static audit results:

- Proton expanded bytes: `969073178`; 2274 entries.
- DXVK expanded bytes: `8007595`; 13 entries.
- Prefix pack: 575 entries, 22067952 compressed bytes,
  SHA-256 `ade99dc80b17086a269c038c9bc50167902103a9a61df763cba4d613543ff12a`.
- Proton tree: 60 ELF files, all ELF64 AArch64, interpreter
  `/system/bin/linker64` where present.
- Proton PE inventory: 772 ARM64, 771 x86, 1 x86-64, 0 ARM64EC.
- DXVK `dxgi.dll` and `d3d11.dll`: ARM64 PE.

With `LC_ALL=C`, the observed `DT_NEEDED` names are grouped below. This is the
dependency closure visible in the WCP, not a blanket request to package every
Android/system framework:

- **Wine core**: `libc.so`, `libdl.so`, `libm.so`, `ntdll.so`, `win32u.so`.
- **Display/SHM**: `libX11.so`, `libXext.so`, `libandroid-sysvshm.so`.
- **Audio**: `libpulse.so` for Wine PulseAudio, `libasound.so` for ALSA, and
  `libaaudio.so` plus `liblog.so` for the direct-audio path.
- **Fonts**: `libfontconfig.so`, `libfreetype.so`, `libexpat.so.1`,
  `libpng16.so`, `libz.so.1`, `libbrotlidec.so`, `libbrotlicommon.so`, and
  `libbz2.so.1.0`.
- **Optional media**: `libgstreamer-1.0.so`, `libgstbase-1.0.so`,
  `libgstaudio-1.0.so`, `libgstvideo-1.0.so`, `libgstapp-1.0.so`,
  `libgsttag-1.0.so`, `libgstgl-1.0.so`, `libgio-2.0.so.0`,
  `libglib-2.0.so.0`, `libgobject-2.0.so.0`, `libavcodec.so.62`,
  `libavformat.so.62`, and `libavutil.so.60`.
- **Other optional path**: `libdrm.so`.

The runtime has no `DT_NEEDED` entry for `libvulkan.so`: Wine's
`winevulkan.so` resolves Vulkan through the Android/app integration. The
PowerVR variant must therefore use `/system/lib64/libvulkan.so`, not package a
driver or set a Turnip ICD. The materializer pins the locale so localized
`readelf` output cannot erase this evidence.

## Launch contract

The contract is versioned in:

`scripts/arm64-prototype/app/launch-contract.json`

The source-compatible command model is:

```text
<runtime>/bin/wine explorer /desktop=shell,<screen-size> <client-pe> -d3d11
```

Required policy:

- client PE must independently report `AA64`/`0xaa64` before launch;
- native ARM64 system32 only; no x86-64 DLL override;
- `WINEESYNC=0` for the reference setup;
- `LD_LIBRARY_PATH` includes the app ImageFS, `/system/lib64`, and runtime lib;
- do not set a packaged ICD or load Turnip/Adrenotools;
- keep runtime/prefix/client/logs outside the APK asset set where possible;
- keep the runtime hash and per-stage logs exportable without ADB.

The app contract does not define an application ID, manifest, JNI implementation,
or Gradle variant. Those are next-builder decisions after the static pairing
gate and must remain isolated from the current launcher.

## What was not proven

- The community report covers one Retroid Pocket 6 with Adreno 740, not PowerVR.
- No PowerVR system Vulkan launch was attempted.
- No Android process was started from the materialized runtime.
- No authentication or Classic Forever patch path is present in the bundle.
- The community bundle does not contain the client; the separate official CDN
  client is verified as ARM64 in the evidence below.
- Component licensing/redistribution must be reviewed before packaging.

## Official client evidence

The CDN client used for the source/build gate is already independently verified;
the earlier wording that left this as an open check is obsolete:

- Path: `/home/adrian/wowforever-artifacts/references/official-arm64-beta/WowB-ARM64.exe`
- Build: `1.60.1.70009` (`WOW-70009patch1.60.1_ForeverBeta`)
- Size: `94,286,544` bytes
- SHA-256: `d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59`
- PE machine: `0xaa64` (`ARM64`); optional header `0x020b`

This proves the client PE architecture, not Wine startup, rendering, login, or
Forever patch compatibility.

## Pinned-source build gate

The source is the exact revision named by the verified bundle's `SOURCES.txt`,
not a moving branch or current `HEAD`:

- GameNative commit: `205cbf8ddc06f855a1b85ba57c78c4be9a6930aa`
- Local source archive SHA-256:
  `9151ca5a20168a8901797d7fcc2946706f57532088632527876efa2931bdc361`
- Source-declared AGP: `8.8.0`; wrapper-declared Gradle: `8.12.1`
- Attempted task: `:app:assembleModernDebug`
- Result: `BUILD SUCCESSFUL` for the stock baseline APK; no runtime integration
  or native CMake rebuild was reached.
- APK: `app-modern-debug.apk`, `232,966,506` bytes,
  SHA-256 `f00382e9edad6a652f2886c0dd86d477beefcf1299be8d5dbd9c8babaf076a22`
- APK package/ABI: `app.gamenative` / `arm64-v8a`; audit found no WoW client or
  CASC payload.

The exact NDK `27.3.13750724` was not installed because this stock source build
uses its shipped native libraries and does not invoke external CMake. The APK is
therefore a compile/package baseline, not proof of the native ARM64 glue build.

The detailed handoff and next integration hook are in
`scripts/arm64-prototype/app/BASELINE-BUILD.md`.

## Historical diagnostic setup overlay checkpoint (code 23)

The first bounded implementation is now available as a reproducible overlay:

```text
scripts/arm64-prototype/app/apply_overlay.py
scripts/arm64-prototype/app/overlay/Arm64Diagnostics.kt
scripts/arm64-prototype/app/overlay/Arm64SetupScreen.kt
```

Applied to the external pinned source, the overlay:

- uses application ID `com.wowforever.arm64probe` while retaining the source
  namespace `app.gamenative` and `R` imports;
- dynamically resolves the audited package-dependent media/cache/gamepad paths;
- adds an explicit setup screen that imports the pinned Proton and DXVK WCPs via
  `ContentsManager.extraContentFile()` and `finishInstallContent()`;
- copies the three externally built AA64 probes into an app custom-game folder;
- registers that folder with the existing `CustomGameScanner`/container path;
- selects `proton-11.0-90624-arm64ec`,
  `dxvk-2.4.1-wow-aarch64-test`, Bionic, wrapper/System, and Android system
  Vulkan; and
- configures `HelloArm64.exe` as the first executable. The AA64 probes are native
  PE/AA64; the community paired Wine/FEX path remains available for Wine's x86
  auxiliary binaries. No standalone Box64 launcher, Turnip, or Blizzard client is
  added.

The profile-resolution guard now passes the canonical imported entry ID through
`WineInfo.fromIdentifier()`. For example,
`Proton-proton-11.0-90624-arm64ec-1` resolves through the imported profile's
`verName` to `arm64ec` and the profile install directory, rather than stripping
the entry suffix. Malformed profile versions remain non-ARM64EC and are rejected
by the setup guard. DXVK remains selected as the prefixed consumer ID
`dxvk-2.4.1-wow-aarch64-test`; its WCP's plain `d3d11.dll`/`dxgi.dll` payload is
not treated as ARM64EC.

The final offline build candidate was produced with the pinned JDK 17 / Gradle
8.12.1 command:

- Path: `/home/adrian/wowforever-artifacts/arm64-prototype/toolchain/GameNative/app/build/outputs/apk/modern/debug/app-modern-debug.apk`
- Size: `365,944,497` bytes
- SHA-256: `35e9911b0713bc6f6ca750aef16216e453cffdb50cfeef50f49c4382faaa256f`
- Package/version: `com.wowforever.arm64probe` / `1.2.1` (`versionCode 23`)
- ABI: `arm64-v8a`
- Debug certificate SHA-256: `2b082e8200e066e665f73e71f547f827d6674634298024cb0b823be22adb242b`
- APK v2 signature: verified; no release signing was used.
- Strict APK audit: no `WowB.exe`, `WowB-ARM64.exe`, CASC, or Forever client
  markers; only the three external AA64 probes are included.

Targeted parser and WineInfo resolution tests passed. APK installation, Android
execution, PowerVR rendering, audio, login, and Classic Forever authentication
remain intentionally untested. The candidate is staged outside the repository
for parent review; no public upload or delivery was performed.
This checkpoint does not yet implement automatic multi-stage sequencing or a
custom lifecycle; after setup, the existing GameNative custom-game UI is used.

## Historical automated launcher checkpoint (code 24, 2026-09-30)

The code-23 APK was subsequently tested by the user: all three entries were
launched in order, the loading stages changed, and each returned to the Android
desktop. Export `RUN-1790746460061-673305530202438` had three `MISSING` probe logs.
That does not identify a PowerVR/Wine cause; it exposed missing startup evidence.

The code-24 candidate replaced the manual-library route with **Preparar y
lanzar**: finite official ARM64 CDN bootstrap, dedicated runtime/container,
canonical `BetaSuspendedTest.wtf`, supervised real WoW invocation, guarded JNI
patcher and persistent SAF diagnostics. Sources/recipe, artifact hash and complete
verification boundaries are in [automated-arm64-launcher.md](automated-arm64-launcher.md).

APK version `0.2.0-automated` / code `24`, same package/debug certificate; label
**Forever** in every locale. SHA-256:
`dec5df5c73f646cd064052c6d42fb53993f33ab9aba72521423854bb912ef805`.
Size `489,521,421` bytes, arm64-v8a, minSdk 29 / targetSdk 36.
Build, native/source/asset/signature audit and targeted tests passed in the host.
Independent CASC golden verification also exposed and fixed a checksum-B
rotation error; all 17 generated files now match cascette.

**Private single-attempt trial only:** retry after Wine spawn requires an Android
reboot, because sampled descendants are not proof of complete termination.
Current APK execution, adequate CASC/streaming, actual CertStore ARM64, render and
Forever login still require the phone. No Turnip/GLX workaround is applied blindly.

## Runtime-profile correction (code 25, 2026-10-01)

The user tried code 24 and immediately received `runtime profile has no installed
file manifest: Proton-proton-11.0-90624-arm64ec-1` at `runtime: resolving pinned
Wine profile`, before any Wine spawn. Reproduced with the actual pinned WCP JSONs:
Wine/Proton legitimately declare an empty `files` DLL-copy list. The verifier had
incorrectly required DXVK-style copy entries for Wine.

The corrected candidate was `0.2.1-automated` / code **25**, update-compatible without data
deletion. Wine binary/library/prefix checks remain mandatory; DXVK requires its
declared DLLs. Six runtime regressions and two Wine-selector tests pass, as do the
offline build, fresh recipe and APK audit. Runtime payloads, JNI and certificate
are unchanged. Artifact/hash and exact procedure are in
[automated-arm64-launcher.md](automated-arm64-launcher.md).

This is a launcher validation fix, not a Wine/PowerVR execution or login result.
The observed pre-spawn error does not itself require reboot before retry; the
post-spawn single-attempt/reboot policy remains unchanged.

## BLTE catalog correction (code 26, 2026-10-01)

The code-25 phone run passed runtime-profile validation and reached `client:
downloading`, then rejected `bc0fe26fca9629c46c33c478077ecd4d` with `BLTE decoded
size mismatch: 154 != 181`. The producer had confused the encoded VFS buildconfig
size with the decoded size. The Android reader correctly rejected that catalog;
the failure is not evidence of a runtime/GPU problem or truncated download.

The corrected candidate was `0.2.2-automated` / code **26**, same package/certificate and
runtime. Corrected all affected metadata sizes, audited all 832 required objects
with independent cascette decoding, then verified those exact bytes with the
APK's streaming reader in host JVM tests. No size/hash check was relaxed, no
client/network download was added, and existing valid runtime/cache is retained.
Thirteen Python regressions, 9 BLTE/provisioner tests and the 2 real-catalog tests
pass; fresh overlay equivalence, offline build and actual APK audit also pass.

Neither this gate nor the phone's provisioning progress proves Wine spawn,
PowerVR rendering, CASC sufficiency, CertStore layout or login. The specific error
occurred before Wine: update/retry requires no reboot for it, while the post-spawn
Android-reboot rule remains. See [automated-arm64-launcher.md](automated-arm64-launcher.md)
for the final artifact/hash and procedure.

## CDN source correction (code 27, 2026-10-01)

Code 26 advanced to loose-file provisioning and received HTTP 404 for
`e092b002f52c0827ce4b65d534d7316d`, still before Wine spawn. A zero-offset CDN
file-index was incorrectly used as a data archive. Code 27 (`0.2.3-automated`)
distinguishes file indices from archive indices, resolves the verified standalone
payload and rejects the wrong source inside Android before any download.

The old URL reproduced 404. All 35 corrected loose routes returned valid 64-byte
206 ranges; the affected object returned 200 in full and passed decoded MD5/size.
Twenty-two Python tests and 9 focused JVM tests pass; fresh recipe equivalence,
offline build and actual APK audit pass. The investigation received only about
3.9 MB, without new runtime/toolchains/full client downloads. Existing installed
runtime/cache, native patcher and 748,157,712-byte provisioner bound are preserved.

This is a CDN/provisioning correction, not proof of Wine or WoW execution. The
observed pre-spawn 404 permits update/retry without Android reboot; any subsequent
Wine spawn/exit/cancel still requires reboot before another private attempt.

## Wine bootstrap observed; package/lifecycle correction (code 28, 2026-10-01)

The subsequent code-27 phone report `RUN-1790883604709-786341652235022` passed the
preparation gates and spawned native Wine (PID 18319), with SDL/evshim initialization.
The user saw runtime controls and a black screen; after minimize/restore they were
back at **Forever home**, not the Android desktop. No validated WoW PID or rendered
game frame appears in the report. Its stale `ACTIVE/RUNNING` state is not proof of
continued execution. The old historical SIGTERM cannot be attributed without host
identity fields.

Actual stderr redirected Termux/Winlator library paths to `app.gamenative`, rather
than this APK's package. Offline inspection of the shipped AArch64 WX preload
proved its supported host-package variables: `WINEMU_HOST_PKG`, then `HOST_PKG`,
then the original package default. Code 28 passes both aliases at the final launch
environment merge; bounded Wine process/module/SEH channels are enforced after
upstream's logging defaults. The full SysV SHM/evshim/WX preload and HODLL are
preserved. SHA-256 of the unchanged WX asset in code 27 and 28:
`8f50d8af131dbf1eb008b8a9e48aca110f7cbc02e787959771317dd5c5c56e26`.
This confirms a configuration defect, not the sole cause of the black screen.

New helper/preload evidence and lifecycle records distinguish wrapper startup,
WoW identity and host exit. Lost supervision becomes `INTERRUPTED`; no automatic
respawn or stale-PID reattachment is attempted. One-shot spawn/veto and terminal
callback guards were reviewed and tested. **40 focused JVM tests pass without
skips**, fresh recipe equivalence covers 50 inputs, and build/actual APK audit pass.
No PE/ELF was executed by the tests, and no new runtime/driver/NDK was downloaded.

Current candidate: `0.2.4-automated` / code **28**, same package/certificate, details
in [automated-arm64-launcher.md](automated-arm64-launcher.md). The user must reboot
Android before this attempt because code 27 did spawn Wine; no data clearing.
Actual WoW startup, Surface restoration, PowerVR frames, adequate CASC, CertStore
layout and Forever login remain unverified.
