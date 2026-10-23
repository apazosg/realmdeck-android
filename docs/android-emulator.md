# Headless Android x86_64 emulator (Linux host)

> Installed and verified on 2026-09-28 on `hermes` (Ubuntu 26.04, x86_64).
> Purpose: develop and test Android tooling and the separate diagnostic app.
> It is **not** suitable for the game runtime (see Limitations).

## Installed components

| Component | Exact version | Path |
|---|---|---|
| Command line tools | 15859902 (`commandlinetools-linux-15859902_latest.zip`, sdkmanager 22.0) | `~/.local/opt/android-sdk/cmdline-tools/latest/` |
| Emulator | 37.1.11 (build_id 15917651) | `~/.local/opt/android-sdk/emulator/` |
| platform-tools (adb, fastboot) | 37.0.1 | `~/.local/opt/android-sdk/platform-tools/` |
| platforms;android-35 | rev 2 (Android 15, API 35, ext level 13) | `~/.local/opt/android-sdk/platforms/android-35/` |
| build-tools;35.0.0 | 35.0.0 | `~/.local/opt/android-sdk/build-tools/35.0.0/` |
| System image | `system-images;android-35;google_apis;x86_64` **rev 9** (`x86_64-35_r09`) | `~/.local/opt/android-sdk/system-images/android-35/google_apis/x86_64/` |
| AVD `wowtest` | `pixel_5` profile, API 35, ABI x86_64 | `~/.android/avd/wowtest.avd/config.ini` |
| Artifacts (log, PID, screenshots, trivial APK) | — | `/home/adrian/wowforever-artifacts/android-emulator/` (outside the repository) |

Relevant AVD configuration (`config.ini`): `hw.ramSize=4096M`,
`hw.gpu.mode=swiftshader_indirect` + `hw.gpu.enabled=yes` (software rendering,
no physical GPU), `hw.audioInput=no`, `hw.audioOutput=no`,
`hw.camera.back=none`, `hw.camera.front=none`. Start with
`-no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 4096`.

Installation notes:

- `/dev/kvm` exists; the user belongs to the `kvm` group, but the session may not
  have it active (check with `id -nG`). If unavailable, launch emulator commands
  with `sg kvm -c '<command>'` (the script already does this automatically).
- When creating the AVD, `avdmanager` left template placeholders in `config.ini`
  (`avd.id=<build>`, `avd.name=<build>`, `disk.dataPartition.path=<temp>`): these
  were manually corrected (`avd.id/name=wowtest`, `<temp>` line removed). Without
  this, the AVD still appears in `-list-avds`, but it is best to keep it healthy.

## Usage (via script)

```bash
scripts/android-emulator.sh start            # arranca y espera a sys.boot_completed=1 (timeout 600 s)
scripts/android-emulator.sh stop             # apagado limpio (adb emu kill / PID guardado)
scripts/android-emulator.sh status           # proceso + estado adb + boot
scripts/android-emulator.sh screencap [ruta] # PNG de la pantalla (defecto: $EMU_DIR/screencap-<fecha>.png)
```

Variables: `ANDROID_SDK_ROOT` (default `~/.local/opt/android-sdk`),
`ANDROID_EMU_DIR` (default
`/home/adrian/wowforever-artifacts/android-emulator`),
`ANDROID_EMU_AVD` (default `wowtest`),
`ANDROID_EMU_SERIAL` (default `emulator-5554`),
`ANDROID_EMU_BOOT_TIMEOUT` (default 600 s).

Manual equivalents:

```bash
export ANDROID_SDK_ROOT=$HOME/.local/opt/android-sdk
sg kvm -c "nohup $ANDROID_SDK_ROOT/emulator/emulator -avd wowtest -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 4096 > /home/adrian/wowforever-artifacts/android-emulator/emulator.log 2>&1 & echo \$! > /home/adrian/wowforever-artifacts/android-emulator/emulator.pid"
$ANDROID_SDK_ROOT/platform-tools/adb wait-for-device
$ANDROID_SDK_ROOT/platform-tools/adb shell getprop sys.boot_completed  # 1 = listo
$ANDROID_SDK_ROOT/platform-tools/adb -s emulator-5554 emu kill         # parar
```

**Warning:** to stop the emulator, always use the saved PID (`emulator.pid`) or
`adb emu kill`. **Do not** use `pkill -f`/`grep -f` with a pattern that appears
literally in the command line itself: it would kill the shell itself.

## View the screen without a window

```bash
$ANDROID_SDK_ROOT/platform-tools/adb -s emulator-5554 exec-out screencap -p > /home/adrian/wowforever-artifacts/android-emulator/boot.png
```

Verified: a 1080×2340 PNG with the launcher and Google apps visible.

## Install test APKs

`adb install` works. Verified with a trivial APK (`org.wowforever.trivial`, at
`android-emulator/trivial-apk/trivial.apk`): `Success`, and it appears in
`pm list packages`.

**Finding:** on Android 15, an APK **without `classes.dex` does not install**
(`INSTALL_FAILED_INVALID_APK: … code is missing`), even if it declares no
activity. A minimal `classes.dex` (generated with `javac` + `d8 --min-api 21`) must
be included in the APK after `aapt2 link`, followed by `zipalign` + `apksigner sign`.
Correct order:

```bash
aapt2 link -o trivial-unsigned.apk --manifest AndroidManifest.xml -I android.jar …
# añadir classes.dex al zip (zip -j / python zipfile), NO como argumento de aapt2 link
zipalign -f 4 trivial-unsigned.apk trivial-aligned.apk
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out trivial.apk trivial-aligned.apk
adb -s emulator-5554 install -r trivial.apk
```

## Diagnostic Android project (historically Phase 5)

The separate diagnostic Android project lives in `android/` (Gradle Kotlin DSL,
Kotlin, Views, no Compose). It is not the functional launcher; current functional
launcher sources are under `scripts/arm64-prototype/app/`. Toolchain for this
diagnostic project:

| Component | Version | Note |
|---|---|---|
| NDK | **27.0.12077973** | First stable NDK with final `android-35` headers; compatible with `build-tools 35.0.0` |
| AGP / Gradle | 8.9.1 / 8.11.1 | Already in the local cache; the build works with `--offline` |
| Kotlin | 2.1.21 | — |
| `minSdk` / `targetSdk` / `compileSdk` | 26 / 28 / 35 | `abiFilters`: `x86_64` (emulator) + `arm64-v8a` (phone) |

The native library is the JNI bridge to the patcher (`crates/forever-patcher-jni`,
a separate crate; the `crates/forever-patcher` core still has no `unsafe`). **The
`.so` files are not versioned**: they are regenerated before assembling the APK.

```bash
scripts/build-android-native.sh          # cross-compila y copia a jniLibs/
cd android && ./gradlew :app:assembleDebug
$ANDROID_SDK_ROOT/platform-tools/adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
$ANDROID_SDK_ROOT/platform-tools/adb -s emulator-5554 shell am start -n com.wowforever.launcher/.MainActivity
```

## Reproducible smoke test without UI

The cross-process self-test is triggered with an `Intent` (`run_selftest=true`), so
it does not require touching the screen. `--serial` is mandatory: the runner never
selects an implicit device. Each run generates a unique `run_id`, passes it to the
Activity, and accepts only a `ForeverJNI` line with that identifier. It does not
clear the global logcat buffer; old logs remain in the evidence but cannot produce
a result for the current run.

```bash
scripts/android-launcher-smoke.py \
  --serial emulator-5554 \
  --apk android/app/build/outputs/apk/debug/app-debug.apk
```

Exit codes are `0=PASS`, `1=FAIL`, and `2=TIMEOUT`. Evidence is kept outside the
repository, at `/home/adrian/wowforever-artifacts/android-launcher/smoke/<run_id>/`:
`run.json`, `result.json`, `commands.txt`, installation/launch output, and
`logcat.txt`. The root can be changed with `--output-dir`, but the runner rejects
paths inside the repository and always generates the `run_id`; there is no public
override that allows reuse of an old one. If `adb` is not in `PATH`, the runner
also uses `/home/adrian/.local/opt/android-sdk/platform-tools/adb`.

The `contract.json` asset is no longer maintained by hand: the incremental task
`:app:generateContractAsset` copies `forever/contract.json` using a declared
input/output dependency and includes it in the APK during `preBuild`. To verify it
offline:

```bash
cd android
./gradlew --offline :app:generateContractAsset
```

The Gradle invalidation test is opt-in and builds a minimal project in a temporary
directory without modifying `android/app/build/`:

```bash
WOWFOREVER_RUN_GRADLE_INTEGRATION=1 \
  /home/adrian/.local/bin/uv run pytest tests/test_android_contract_asset.py -q
```

## Diagnostic test bench

The APK's Views screen retains the previous smoke test and adds a sequential suite
of three real tests: `JNI + contrato`, `ExecProbe`, and `crossProcessSelfTest`. Each
run is saved in the app's private storage as `files/diagnostics/runs/<run_id>.json`
plus a `<run_id>.log` log. The JSON includes app version, ABI, SDK/`targetSdk`, time
per test, and the `PASS`, `FAIL`, `TIMEOUT`, or `UNSUPPORTED` states; `runtime=none`
and `gpu=not_measured` are explicit. A timeout does not kill or declare the JNI call
canceled: the queue waits for it to finish before starting another test.

Export buttons use `ACTION_CREATE_DOCUMENT` (SAF), with no storage permissions or
new dependencies, and offer JSON or text. The queue lives in a process singleton
and the Activity only resubscribes to its state, so recreation does not duplicate
probes or lose results already written. After the process dies completely, a run
left `RUNNING` is retained as an uncertain `TIMEOUT` when reopened; native
cancellation is not fabricated.

Existing automated compatibility remains: `run_selftest=true` and
`run_id=<id>` trigger only `crossProcessSelfTest`, and the runner accepts the
`ForeverJNI` marker from that run. Winlator/Wine are not packaged, and external
profiles are not run.

The strict ExecProbe parser does not turn a missing C2 control into PASS:
`SKIP (sin .so en nativeLibraryDir)` is reported as `UNSUPPORTED`. With a `.so`
present, it accepts the expected `ENOEXEC`/`EACCES` rejection **or execution
confirmed by `execve` followed by a process signal** (for example, the `.so`
attempted as an executable ends with `SIGSEGV`). The latter evidence shows that the
kernel allowed `execve`; it does not mean the probe or application crashed. The
self-test parser requires unique fields (`result`, `stores`, `patched`, child
confirmation, and `first_err`) and exact values, without rejecting the additional
diagnostic with spaces emitted by Rust.

The diagnostic delivery uses `useLegacyPackaging=true` so JNI libraries are
extracted to `nativeLibraryDir`, with no additional storage permissions, without
changing `targetSdk=28`, and without including Winlator/Wine/WoW.

Historical verification on `emulator-5554` before the packaging adjustment: suite
`run-4d2f6f603cc146a0a4cafedab746936f` honestly ended `UNSUPPORTED`
(`JNI + contrato PASS`, `ExecProbe UNSUPPORTED`, `crossProcessSelfTest PASS`).
Evidence is at
`/home/adrian/wowforever-artifacts/android-testbench/run-4d2f6f603cc146a0a4cafedab746936f/`.
The final compatible smoke test ended `PASS` with run ID
`run-3f22d5b280c24156aa35124436b46765` at
`/home/adrian/wowforever-artifacts/android-testbench/smoke/run-3f22d5b280c24156aa35124436b46765/`.

Cross-compilation details: without `cargo-ndk`; set
`CARGO_TARGET_{X86_64,AARCH64}_LINUX_ANDROID_LINKER` to the NDK clang and
`-C link-arg=-Wl,-z,max-page-size=16384` (Android 15+ requires 16 KB pages).

Verified on 2026-09-28: APK installed and started on `emulator-5554`; the `logcat`
tag `ForeverJNI` shows `nativeVersion=… (core KEY_LEN=32)` and
`contractSummary=entries=12 entry_size=40 target_group=8 anchor_group=1`.
Later automated validation is recorded in the evidence section of
`docs/fase-5-launcher.md`.

Verified local diagnostic delivery: `/home/adrian/wowforever-artifacts/delivery/`
contains `wowforever-diagnostic-0.1.1-code2-debug.apk` (1.760.556 bytes,
SHA-256 `b68eb89efb3156b3e266a618ef7752e5dd0c7b5e81c570abc0473848c90ce54e`).
The APK is debug, `minSdk=26`, `targetSdk=28`, declares zero permissions, and
contains `arm64-v8a` and `x86_64`; the v2 signature is valid. The PASS suite, JSON
exported by SAF, and persistent relaunch are documented in `docs/fase-5-launcher.md`.

## Limitations (important)

- The emulator is **x86_64**. It **cannot** run **Winlator or FrontMage**
  (arm64-only runtimes), nor reproduce the Vulkan driver of any real GPU
  (everything here uses software SwiftShader).
- It is suitable for developing and testing the diagnostic Android app and
  adb-based tooling, **not** for the game runtime or graphics performance testing.
- No camera, no audio, no Play Store (`PlayStore.enabled=no`, `google_apis` image,
  not `google_apis_playstore`).

## Reference commands

```bash
adb devices -l
adb -s emulator-5554 shell getprop ro.product.cpu.abi      # x86_64
adb -s emulator-5554 shell getprop ro.build.version.release # 15
adb -s emulator-5554 shell getprop ro.build.version.sdk     # 35
adb -s emulator-5554 shell getprop ro.product.model         # sdk_gphone64_x86_64
adb -s emulator-5554 shell cat /proc/meminfo | head -3
adb -s emulator-5554 shell pm list packages org.wowforever.trivial
```
