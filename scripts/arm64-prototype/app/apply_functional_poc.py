#!/usr/bin/env python3
"""Apply the small, GUI-only WoW ARM64 Mali proof-of-concept overlay.

This recipe intentionally does not replay the historical launcher recipe.  It
    adds only the PoC Kotlin sources/tests, the opt-in code-capture bridge, a separate provenance marker, the PoC
launcher manifest entry, and exact source edits to route the existing GameNative
GUI to the fixed custom game and stop its own audio normally. Existing assets, JNI, stores,
controllers, and supervisor entries are otherwise left byte-for-byte alone.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys


PACKAGE = "com.wowforever.arm64probe"
CURRENT_VERSION = (63, "0.2.39-functional-poc")
TARGET_VERSION = (64, "0.2.40-functional-forever-poc")
PINNED_UPSTREAM_COMMIT = "205cbf8ddc06f855a1b85ba57c78c4be9a6930aa"
APP_ROOT = Path(__file__).resolve().parent
REPO_ROOT = APP_ROOT.parents[2]
OVERLAY_NAMES = (
    "ForeverFunctionalPoc.kt",
    "ForeverFunctionalPocActivity.kt",
    "ForeverFunctionalLauncherUx.kt",
    "ForeverFunctionalUiText.kt",
    "ForeverFunctionalIcons.kt",
    "ForeverFunctionalClientStorage.kt",
    "ForeverFunctionalDisplayFit.kt",
    "ForeverFunctionalGpuCompatibility.kt",
    "ForeverFunctionalAutologinCredentials.kt",
    "ForeverFunctionalAutologinAttempt.kt",
    "ForeverFunctionalAutologinSettings.kt",
    "ForeverFunctionalServerProfiles.kt",
    "ForeverFunctionalPocPatchBridge.kt",
    "ForeverFunctionalPocPatchSession.kt",
    "ForeverFunctionalPatchSummary.kt",
    "ForeverFunctionalWowStartup.kt",
    "ForeverRemoteDiagnosticsContract.kt",
    "ForeverRemoteDiagnosticsSanitizer.kt",
    "ForeverRemoteDiagnosticsStore.kt",
    "ForeverRemoteDiagnosticsReport.kt",
    "ForeverRemoteDiagnosticsFrozenConfig.kt",
    "ForeverRemoteDiagnosticsLocalCapture.kt",
    "ForeverRemoteDiagnosticsUpload.kt",
    "ForeverRemoteDiagnosticsAndroid.kt",
    "ForeverRemoteDiagnosticsDialog.kt",
    "ForeverRemoteDiagnosticsWineLog.kt",
)
TEST_OVERLAY_NAMES = (
    "ForeverFunctionalServerProfilesTest.kt", "ForeverFunctionalGpuCompatibilityTest.kt",
    "ForeverFunctionalAutologinCredentialsTest.kt", "ForeverFunctionalAutologinAttemptTest.kt",
    "ForeverFunctionalAutologinSettingsTest.kt",
    "ForeverFunctionalDisplayFitTest.kt",
    "ForeverFunctionalLauncherUxTest.kt",
    "ForeverFunctionalWowStartupTest.kt",
    "ForeverFunctionalPatchSummaryTest.kt",
    "ForeverFunctionalClientStorageTest.kt",
    "ForeverRemoteDiagnosticsSanitizerTest.kt",
    "ForeverRemoteDiagnosticsStoreTest.kt",
    "ForeverRemoteDiagnosticsReportTest.kt",
    "ForeverRemoteDiagnosticsFrozenConfigTest.kt",
    "ForeverRemoteDiagnosticsLocalCaptureTest.kt",
    "ForeverRemoteDiagnosticsUploadTest.kt",
    "ForeverRemoteDiagnosticsWineLogTest.kt",
    "ForeverRemoteDiagnosticsCredentialPrivacyTest.kt",
)

BUILD = Path("app/build.gradle.kts")
MANIFEST = Path("app/src/main/AndroidManifest.xml")
XSERVER = Path("app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt")
PULSE_AUDIO = Path("app/src/main/java/com/winlator/xenvironment/components/PulseAudioComponent.java")
MARKER = Path("app/src/main/assets/forever/functional-poc/provenance.json")
MAIN_ROOT = Path("app/src/main/java/app/gamenative")
TEST_ROOT = Path("app/src/test/java/app/gamenative")
MAX_NATIVE_PROVENANCE_BYTES = 4 * 1024 * 1024
EXPECTED_NDK = "/home/adrian/android-sdk/ndk/24.0.8215888"
EXPECTED_RUST_BIN = "/home/adrian/.rustup/toolchains/stable-x86_64-unknown-linux-gnu/bin"
EXPECTED_NDK_BIN = f"{EXPECTED_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin"

DIRECT_ROUTE = '''        // FOREVER_FUNCTIONAL_POC: fixed unmanaged WoW GUI route, before all
        // historical managed/probe admission paths.  The Activity supplies the
        // container; this branch supplies only the fixed executable and cwd.
        if (app.gamenative.ForeverFunctionalPoc.isRoute(
            container.getExtra("foreverFunctionalPoc") == "true",
            gameSource == GameSource.CUSTOM_GAME,
            bootToContainer,
            testGraphics,
        )) {
            val direct = app.gamenative.ForeverFunctionalPoc.command(gameFolderPath, executablePath)
            guestProgramLauncherComponent.workingDir = File(requireNotNull(gameFolderPath)).canonicalFile
            envVars.put("WINEPATH", "A:\\\\")
            return direct
         }

'''

POC_ACTIVITY_MANIFEST = '''        <!-- FOREVER_FUNCTIONAL_POC: explicit GUI launch of the real WoW client. -->
        <activity
            android:name="app.gamenative.ForeverFunctionalPocActivity"
            android:exported="true"
            android:permission="android.permission.DUMP"
            android:screenOrientation="portrait"
            android:configChanges="orientation|screenSize|keyboard|keyboardHidden|navigation"
            android:windowSoftInputMode="adjustNothing"
            android:theme="@style/Theme.Pluvia">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
         </activity>

'''


class OverlayError(ValueError):
    """The destination is not one of the explicitly supported source states."""


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _read(root: Path, relative: Path) -> str:
    path = root / relative
    if not path.is_file() or path.is_symlink():
        raise OverlayError(f"required regular source file is missing: {path}")
    return path.read_text(encoding="utf-8")


def _write_if_changed(root: Path, relative: Path, data: str) -> bool:
    path = root / relative
    if path.exists() or path.is_symlink():
        if path.is_symlink() or not path.is_file():
            raise OverlayError(f"recipe destination is not a regular file: {path}")
        if path.read_text(encoding="utf-8") == data:
            return False
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(data, encoding="utf-8")
    return True


def _replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise OverlayError(f"{label} anchor count is {count}, expected one")
    return text.replace(old, new, 1)


FUNCTIONAL_SERVER_PROFILE_FIELD = 'buildConfigField("String", "FOREVER_SERVER_PROFILE", "\\"classic_forever\\"")'
# Escaped form as it lands in build.gradle.kts: \"classic_forever\".
FUNCTIONAL_SERVER_PROFILE_VALUE = '\\"classic_forever\\"'


def ensure_server_profile_field(text: str) -> str:
    """Inject the build-time server profile field; idempotent and version-independent.

    A stored ORIGINAL preference from an older install can never override it:
    Kotlin coerces to EFFECTIVE_PROFILE and validates it here at recipe time.
    """
    if "FOREVER_SERVER_PROFILE" in text:
        if FUNCTIONAL_SERVER_PROFILE_VALUE not in text:
            raise OverlayError("functional server profile build field differs from classic_forever")
        if text.count("FOREVER_SERVER_PROFILE") != 1:
            raise OverlayError("functional server profile build field anchor is not unique")
        return text
    name = re.search(r'\bversionName\s*=\s*"[^"]+"', text)
    if not name:
        raise OverlayError("build version name anchor is missing for the server profile field")
    line_end = text.index("\n", name.end())
    indent = "        "
    return text[:line_end] + "\n" + indent + FUNCTIONAL_SERVER_PROFILE_FIELD + text[line_end:]


def transform_build(text: str) -> str:
    code_token = 'versionCode = '
    name_token = 'versionName = "'
    if text.count(code_token) != 1 or text.count(name_token) != 1:
        raise OverlayError("build version anchors are not unique")
    import re

    code = re.search(r"\bversionCode\s*=\s*(\d+)\b", text)
    name = re.search(r'\bversionName\s*=\s*"([^"]+)"', text)
    if not code or not name:
        raise OverlayError("build version anchors are missing")
    pair = (int(code.group(1)), name.group(1))
    if pair not in (CURRENT_VERSION, TARGET_VERSION):
        raise OverlayError(f"unsupported source version: {pair!r}")
    if pair == TARGET_VERSION:
        # Version is already the target: the profile field injection below is
        # still required and stays idempotent.
        return ensure_server_profile_field(text)
    text = _replace_once(text, f"versionCode = {pair[0]}", f"versionCode = {TARGET_VERSION[0]}", "versionCode")
    text = _replace_once(text, f'versionName = "{pair[1]}"', f'versionName = "{TARGET_VERSION[1]}"', "versionName")
    return ensure_server_profile_field(text)


POC_UPDATE_SERVICE_MANIFEST = '''        <!-- FOREVER_FUNCTIONAL_POC: notification-backed client download that survives app close. -->
        <service
            android:name="app.gamenative.ForeverClientUpdateForegroundService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
'''

def _append_wake_lock_permission(text: str) -> str:
    if 'android.permission.WAKE_LOCK' in text:
        return text
    permission = 'android.permission.FOREGROUND_SERVICE_DATA_SYNC'
    position = text.find(permission)
    if position < 0:
        raise OverlayError("foreground service data sync permission anchor is missing")
    start = text.rindex('<uses-permission', 0, position)
    end = text.index('/>', position) + len('/>')
    return text[:end] + '\n        <uses-permission android:name="android.permission.WAKE_LOCK" />' + text[end:]


def transform_manifest(text: str) -> str:
    supervisor_activity = 'android:name="app.gamenative.ForeverWineSupervisorActivity"'
    supervisor_alias = 'android:name="app.gamenative.ForeverWineSupervisorLauncher"'
    for name in (supervisor_activity, supervisor_alias):
        if text.count(name) != 1:
            raise OverlayError(f"existing supervisor manifest anchor count is not one: {name}")

    # Keep the historical fixture entry available for inspection, but not as a
    # launcher.  The PoC is the only exported launcher in this candidate.
    activity_block = supervisor_activity
    activity_start = text.index(activity_block)
    activity_end = text.index('/>', activity_start) + 2
    activity = text[activity_start:activity_end]
    if 'android:enabled="true"' in activity:
        activity = activity.replace('android:enabled="true"', 'android:enabled="false"', 1)
        text = text[:activity_start] + activity + text[activity_end:]
    elif 'android:enabled="false"' not in activity:
        raise OverlayError("supervisor activity enabled anchor is missing")

    alias_start = text.index(supervisor_alias)
    alias_end = text.index('</activity-alias>', alias_start) + len('</activity-alias>')
    alias = text[alias_start:alias_end]
    if 'android:enabled="true"' in alias:
        alias = alias.replace('android:enabled="true"', 'android:enabled="false"', 1)
        text = text[:alias_start] + alias + text[alias_end:]
    elif 'android:enabled="false"' not in alias:
        raise OverlayError("supervisor alias enabled anchor is missing")

    marker = '        <!--\n         android:windowSoftInputMode must always be in the activity block.'
    if text.count(marker) != 1:
        raise OverlayError("MainActivity manifest insertion anchor changed")
    if "ForeverFunctionalPocActivity" not in text:
        text = text.replace(marker, POC_ACTIVITY_MANIFEST + marker, 1)
    elif POC_ACTIVITY_MANIFEST not in text:
        # The previous functional candidate predates the explicit IME policy.
        # It is still an app-owned entry, so update only that activity block.
        activity_name = 'android:name="app.gamenative.ForeverFunctionalPocActivity"'
        activity_start = text.index(activity_name)
        activity_end = text.index('</activity>', activity_start) + len('</activity>')
        activity = text[activity_start:activity_end]
        if 'android:windowSoftInputMode=' not in activity:
            config_anchor = '            android:configChanges="orientation|screenSize|keyboard|keyboardHidden|navigation"'
            if activity.count(config_anchor) != 1:
                raise OverlayError("functional PoC activity config anchor changed")
            activity = activity.replace(
                config_anchor,
                config_anchor + '\n            android:windowSoftInputMode="adjustNothing"',
                1,
            )
            text = text[:activity_start] + activity + text[activity_end:]
            activity_end = text.index('</activity>', activity_start) + len('</activity>')
        if POC_ACTIVITY_MANIFEST not in text:
            # Confirm the old block is still the fixed explicit activity before
            # allowing an owned migration; unrelated manifest drift is rejected.
            required = (
                'android:exported="true"',
                'android:permission="android.permission.DUMP"',
                'android:windowSoftInputMode="adjustNothing"',
                'android:theme="@style/Theme.Pluvia"',
            )
            if any(value not in activity for value in required):
                raise OverlayError("existing functional PoC manifest entry differs")
            if 'android:screenOrientation="landscape"' in activity:
                activity = activity.replace(
                    'android:screenOrientation="landscape"',
                    'android:screenOrientation="portrait"',
                    1,
                )
                text = text[:activity_start] + activity + text[activity_end:]
            elif 'android:screenOrientation="portrait"' not in activity:
                raise OverlayError("functional PoC screen orientation anchor is missing")

    if "app.gamenative.ForeverClientUpdateForegroundService" not in text:
        close = '    </application>'
        if text.count(close) != 1:
            raise OverlayError("application close anchor is not unique")
        text = text.replace(close, POC_UPDATE_SERVICE_MANIFEST + close, 1)
    elif POC_UPDATE_SERVICE_MANIFEST not in text:
        raise OverlayError("existing foreground update service manifest entry differs")
    return _append_wake_lock_permission(text)


def transform_xserver(text: str) -> str:
    # Capture this attempt's observer before Wine starts. Do not add another
    # parameter to the register-constrained XServerScreen composable.
    startup_hooks = (
        ("                val wmListener = object : WindowManager.OnWindowModificationListener {\n",
         """                // FOREVER_WOW_STARTUP: observation only, never native write authority.
                val foreverWowStartup = if (container.getExtra("foreverFunctionalPoc") == "true") {
                    app.gamenative.ForeverFunctionalPoc.currentWowStartup()
                } else null
                val wmListener = object : WindowManager.OnWindowModificationListener {
                        private fun observeForeverWowStartup(window: Window, contentUpdated: Boolean = false) {
                            foreverWowStartup?.observe(window.id, window.className,
                                window.width.toInt(), window.height.toInt(), window.attributes.isMapped(), contentUpdated)
                        }
"""),
        ("                        override fun onUpdateWindowContent(window: Window) {\n",
         "                        override fun onUpdateWindowContent(window: Window) {\n                            observeForeverWowStartup(window, contentUpdated = true)\n"),
        ("                        override fun onModifyWindowProperty(window: Window, property: Property) {\n",
         "                        override fun onModifyWindowProperty(window: Window, property: Property) {\n                            observeForeverWowStartup(window)\n"),
        ("                        override fun onMapWindow(window: Window) {\n",
         "                        override fun onMapWindow(window: Window) {\n                            observeForeverWowStartup(window)\n"),
        ("                        override fun onUnmapWindow(window: Window) {\n",
         "                        override fun onUnmapWindow(window: Window) {\n                            foreverWowStartup?.forget(window.id)\n"),
        ("                        override fun onChangeWindowZOrder(window: Window) {\n",
         "                        override fun onDestroyWindow(window: Window) {\n                            foreverWowStartup?.forget(window.id)\n                        }\n\n                        override fun onChangeWindowZOrder(window: Window) {\n"),
        ("                        override fun onUpdateWindowGeometry(window: Window, resized: Boolean) {\n",
         "                        override fun onUpdateWindowGeometry(window: Window, resized: Boolean) {\n                            observeForeverWowStartup(window)\n"),
    )
    if "FOREVER_WOW_STARTUP" not in text:
        for anchor, hook in startup_hooks:
            text = _replace_once(text, anchor, hook, "observed WoW startup")
    elif any(text.count(hook) != 1 for _, hook in startup_hooks):
        raise OverlayError("existing WoW startup observation differs")

    output_anchor = "    ProcessHelper.addDebugCallback { line ->\n"
    output_hook = """    // FOREVER_REMOTE_OUTPUT: observe existing readers, never replace the exec owner.
    val foreverRemoteOutput = if (container.getExtra("foreverFunctionalPoc") == "true") {
        runCatching { app.gamenative.ForeverRemoteDiagnosticsAndroid.outputSink(context) }.getOrNull()
    } else null
    ProcessHelper.addDebugCallback { line ->
        runCatching { foreverRemoteOutput?.append(line) }
"""
    exit_anchor = "    val gameTerminationCallback = Callback<Int> { status ->\n"
    exit_hook = exit_anchor + "        runCatching { foreverRemoteOutput?.finished(status) }\n"
    if "FOREVER_REMOTE_OUTPUT" not in text:
        text = _replace_once(text, output_anchor, output_hook, "functional diagnostic output")
        text = _replace_once(text, exit_anchor, exit_hook, "functional diagnostic exit")
    elif text.count(output_hook) != 1 or text.count(exit_hook) != 1:
        raise OverlayError("existing functional diagnostic output hook differs")

    stale_kill = "    ProcessHelper.hardKillStaleWineProcesses()\n\n    val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)"
    guarded_kill = """    if (container?.getExtra("foreverFunctionalPoc") != "true") {
        ProcessHelper.hardKillStaleWineProcesses()
    } else {
        Timber.i("Functional PoC: retaining the existing Wine prefix/process context")
    }

    val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)"""
    if "Functional PoC: retaining the existing Wine prefix/process context" not in text:
        text = _replace_once(text, stale_kill, guarded_kill, "stale Wine cleanup")
    elif stale_kill in text:
        raise OverlayError("XServer cleanup has both PoC and unpatched anchors")

    anchor = "        // FOREVER_AA64_DIRECT: only the verified managed custom game, never a filename heuristic."
    if DIRECT_ROUTE not in text:
        if "FOREVER_FUNCTIONAL_POC" in text:
            raise OverlayError("existing functional PoC route differs")
        text = _replace_once(text, anchor, DIRECT_ROUTE + anchor, "custom GUI route")
    return text


def _native_helpers():
    native_root = REPO_ROOT / "scripts/arm64-prototype/native"
    elf_name = "wowforever_elf16k_audit"
    elf_module = sys.modules.get(elf_name)
    if elf_module is None:
        elf_spec = importlib.util.spec_from_file_location(elf_name, native_root / "elf16k_audit.py")
        if elf_spec is None or elf_spec.loader is None:
            raise OverlayError("ELF audit helper cannot be loaded")
        elf_module = importlib.util.module_from_spec(elf_spec)
        elf_spec.loader.exec_module(elf_module)
        sys.modules[elf_name] = elf_module
        sys.modules.setdefault("elf16k_audit", elf_module)
    sys.modules.setdefault("elf16k_audit", elf_module)
    provenance_name = "wowforever_capture_provenance"
    provenance_module = sys.modules.get(provenance_name)
    if provenance_module is None:
        provenance_spec = importlib.util.spec_from_file_location(provenance_name, native_root / "capture_provenance.py")
        if provenance_spec is None or provenance_spec.loader is None:
            raise OverlayError("capture provenance helper cannot be loaded")
        provenance_module = importlib.util.module_from_spec(provenance_spec)
        sys.modules[provenance_name] = provenance_module
        provenance_spec.loader.exec_module(provenance_module)
    return provenance_module, elf_module


def _source_manifest(root: Path) -> dict[str, str]:
    try:
        return _native_helpers()[0].source_manifest(root)
    except (OSError, ValueError) as error:
        raise OverlayError(str(error)) from error


def _toolchain_manifest(value: dict[str, object]) -> dict[str, object]:
    try:
        toolchain = value["toolchain"]
        ndk_entry = toolchain["ndk"]
        if not isinstance(ndk_entry, dict) or ndk_entry.get("path") != EXPECTED_NDK:
            raise ValueError("NDK path is not the approved NDK24 root")
        ndk = Path(EXPECTED_NDK)
        source_properties = ndk / "source.properties"
        if ndk.is_symlink() or not ndk.is_dir() or source_properties.is_symlink() or not source_properties.is_file():
            raise ValueError("approved NDK root or source.properties is unavailable")
        properties = source_properties.read_text(encoding="utf-8")
        version_match = re.search(r"^Pkg\.Revision\s*=\s*(.+)$", properties, re.MULTILINE)
        if version_match is None:
            raise ValueError("NDK source.properties has no version identity")

        def tool(name: str, entry: object, expected_path: str) -> dict[str, str]:
            if not isinstance(entry, dict) or entry.get("path") != expected_path:
                raise ValueError(f"{name} path is not the approved pinned tool")
            path = Path(expected_path)
            if not path.is_file() or not path.is_symlink() and not path.stat().st_mode & 0o111:
                raise ValueError(f"{name} is not executable")
            resolved = path.resolve(strict=True)
            if resolved.is_symlink() or not resolved.is_file():
                raise ValueError(f"{name} resolved target is not regular")
            result = subprocess.run([str(path), "--version"], check=False, capture_output=True, text=True, timeout=10)
            if result.returncode != 0:
                raise ValueError(f"{name} identity command failed")
            actual = {
                "path": str(path),
                "resolvedPath": str(resolved),
                "sha256": hashlib.sha256(resolved.read_bytes()).hexdigest(),
                "version": (result.stdout + result.stderr).strip()[:4096],
            }
            expected = actual if "resolvedPath" in entry else {key: value for key, value in actual.items() if key != "resolvedPath"}
            if any(entry.get(key) != expected[key] for key in expected):
                raise ValueError(f"{name} hash/version identity changed")
            return expected

        expected = {
            "api": 29,
            "elfPageSize": 16384,
            "ndk": {
                "path": EXPECTED_NDK,
                "version": version_match.group(1).strip(),
                "sourcePropertiesSha256": hashlib.sha256(source_properties.read_bytes()).hexdigest(),
            },
            "rustc": tool("rustc", toolchain["rustc"], f"{EXPECTED_RUST_BIN}/rustc"),
            "cargo": tool("cargo", toolchain["cargo"], f"{EXPECTED_RUST_BIN}/cargo"),
            "llvmStrip": tool("llvm-strip", toolchain["llvmStrip"], f"{EXPECTED_NDK_BIN}/llvm-strip"),
            "linker": tool("linker", toolchain["linker"], f"{EXPECTED_NDK_BIN}/aarch64-linux-android29-clang"),
            "readelf": tool("llvm-readelf", toolchain["readelf"], f"{EXPECTED_NDK_BIN}/llvm-readelf"),
        }
        if toolchain != expected:
            raise ValueError("toolchain map is not self-consistent")
        return expected
    except (KeyError, TypeError, OSError, ValueError, subprocess.SubprocessError) as error:
        raise OverlayError(f"native toolchain provenance is invalid: {error}") from error


def _regular_input(path: Path, label: str) -> Path:
    if path.is_symlink() or not path.is_file():
        raise OverlayError(f"{label} must be a regular non-symlink file: {path}")
    return path


def _validate_native_provenance(native_provenance: Path, native_read_only: Path) -> tuple[dict[str, object], dict[str, object], str, bytes]:
    sidecar_path = _regular_input(native_provenance, "native provenance sidecar")
    native_path = _regular_input(native_read_only, "native ELF")
    try:
        sidecar_bytes = sidecar_path.read_bytes()
        if len(sidecar_bytes) > MAX_NATIVE_PROVENANCE_BYTES:
            raise OverlayError("native provenance sidecar exceeds its bound")
        sidecar = json.loads(sidecar_bytes.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise OverlayError(f"native provenance sidecar is unreadable: {error}") from error
    if not isinstance(sidecar, dict) or sidecar.get("schema") != 1:
        raise OverlayError("native provenance sidecar schema must be 1")
    before = sidecar.get("before")
    after = sidecar.get("after")
    if not isinstance(before, dict) or not isinstance(after, dict):
        raise OverlayError("native provenance must contain independent before and after objects")
    if before.get("schema") != 1 or before.get("phase") != "before":
        raise OverlayError("native provenance before phase is invalid")
    if after.get("schema") != 1 or after.get("phase") != "after":
        raise OverlayError("native provenance after phase is invalid")
    if "nativeElf" in before or "nativeElf" not in after:
        raise OverlayError("native ELF provenance must exist only in the after phase")
    before_core = {key: value for key, value in before.items() if key not in {"phase", "nativeElf"}}
    after_core = {key: value for key, value in after.items() if key not in {"phase", "nativeElf"}}
    if before_core != after_core:
        raise OverlayError("before and after provenance differ outside phase/nativeElf")
    current_sources = _source_manifest(REPO_ROOT)
    if before.get("sourceSha256") != current_sources or after.get("sourceSha256") != current_sources:
        raise OverlayError("native provenance source map does not match the current compiled source")
    current_toolchain = _toolchain_manifest(after)
    if before.get("toolchain") != current_toolchain or after.get("toolchain") != current_toolchain:
        raise OverlayError("native provenance toolchain changed since the native build")
    if current_toolchain.get("api") != 29 or current_toolchain.get("elfPageSize") != 16384:
        raise OverlayError("native provenance is not API29/16KiB")
    native = after["nativeElf"]
    if not isinstance(native, dict) or native.get("elfClass") != 64 or native.get("machine") != "AArch64":
        raise OverlayError("native provenance ELF identity is not AArch64 ELF64")
    elf_bytes = native_path.read_bytes()
    actual_sha = hashlib.sha256(elf_bytes).hexdigest()
    if native.get("bytes") != len(elf_bytes) or native.get("sha256") != actual_sha:
        raise OverlayError("native ELF size or SHA-256 does not match provenance")
    try:
        actual_loads = _native_helpers()[1].audit_elf(elf_bytes)
    except (ValueError, OSError) as error:
        raise OverlayError(f"native ELF 16KiB audit failed: {error}") from error
    if native.get("ptLoad") != actual_loads:
        raise OverlayError("native ELF PT_LOAD audit does not match provenance")
    return sidecar, native, hashlib.sha256(sidecar_bytes).hexdigest(), elf_bytes


def marker_bytes(
    recipe_sha256: str,
    overlay: Path = APP_ROOT / "overlay",
    native_audit: dict[str, object] | None = None,
    native_provenance: dict[str, object] | None = None,
    native_provenance_sha256: str | None = None,
) -> bytes:
    value = {
        "schema": 1,
        "variant": "wowforever.functional-poc/v1",
        "route": "functional-wow-aa64",
        "package": PACKAGE,
        "versionCode": TARGET_VERSION[0],
        "versionName": TARGET_VERSION[1],
        "sourceCommit": PINNED_UPSTREAM_COMMIT,
        "recipeSha256": recipe_sha256,
        "client": "external CustomGames/WoW Forever/WowB-ARM64.exe",
        "argumentsTemplate": ["-config", "BetaFunctionalPoc-<profileId>.wtf", "-d3d11"],
        "profiles": {
            "original": {"config": "BetaFunctionalPoc-original.wtf", "memoryPatchDefault": False},
            "classic_forever": {"config": "BetaFunctionalPoc-classic_forever.wtf", "memoryPatchDefault": False, "portal": "contract.server.portal"},
        },
        "diagnostic": {
            "bcnEmulationType": "software", "bcnEmulation": "none", "bcnEmulationCache": "0",
            "resourceType": "ahb", "wine": "Proton11", "renderer": "DXVK/PanVK/noGL",
            "androidMediaVolume": "1/15", "enableBGDL": "0", "accessibilityScreenNarrationEnabled": "0",
            "Sound_MasterVolume": "0.03", "showPhotosensitivityWarning": "0",
        },
        "overlaySha256": {
            name: hashlib.sha256((overlay / name).read_bytes()).hexdigest()
            for name in OVERLAY_NAMES
        },
        "renderer": "Vulkan/PanVK/DXVK/noGL",
        "memoryWriteStage": "READ_ONLY_FIRST",
        "memoryPatchEnabledByDefault": False,
        "codeCaptureEnabledByDefault": False,
        "codeCapture": {
            "enabledOnlyByExplicitControl": True,
            "windows": [
                {"rva": "0x223c000", "length": 16384},
                {"rva": "0x37b0000", "length": 16384},
            ],
            "maxSnapshots": 2,
            "snapshotBytes": 32768,
            "totalBytes": 65536,
            "sourceSize": 94286544,
            "sourceSha256": "d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59",
            "timingDecision": "late-monotonic-20s-and-60s",
        },
        "managedForever": False,
        "sourceSha256": {
            name: hashlib.sha256((overlay / name).read_bytes()).hexdigest()
            for name in (*OVERLAY_NAMES, *TEST_OVERLAY_NAMES)
        },
        "jniProvenance": None,
        "nativeBuildProvenance": native_provenance,
        "nativeProvenanceSidecarSha256": native_provenance_sha256,
        "nativeElfAudit": native_audit,
        "packagedSoAudit": None if native_audit is None else {
            "path": "app/src/main/jniLibs/arm64-v8a/libforever_patcher_jni.so",
            "sha256": native_audit["sha256"],
            "bytes": native_audit["bytes"],
            "matchesNativeInput": True,
        },
    }
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")


def _stage_sources(source: Path, overlay: Path, update_owned: bool = False) -> list[Path]:
    changed: list[Path] = []
    for name in (*OVERLAY_NAMES, *TEST_OVERLAY_NAMES):
        origin = overlay / name
        if not origin.is_file() or origin.is_symlink():
            raise OverlayError(f"PoC overlay source is missing: {origin}")
        target = source / (TEST_ROOT if name in TEST_OVERLAY_NAMES else MAIN_ROOT) / name
        data = origin.read_bytes()
        if target.exists() or target.is_symlink():
            if target.is_symlink() or not target.is_file():
                raise OverlayError(f"PoC source destination is not regular: {target}")
            if target.read_bytes() != data and not update_owned:
                raise OverlayError(f"PoC source destination collision: {target}")
            if target.read_bytes() != data:
                target.write_bytes(data)
                changed.append(target)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            changed.append(target)
    return changed


def _validate_source_destinations(source: Path, overlay: Path, update_owned: bool = False) -> None:
    for name in (*OVERLAY_NAMES, *TEST_OVERLAY_NAMES):
        origin = overlay / name
        if not origin.is_file() or origin.is_symlink():
            raise OverlayError(f"PoC overlay source is missing: {origin}")
        target = source / (TEST_ROOT if name in TEST_OVERLAY_NAMES else MAIN_ROOT) / name
        if target.exists() or target.is_symlink():
            if target.is_symlink() or not target.is_file():
                raise OverlayError(f"PoC source destination is not regular: {target}")
            if target.read_bytes() != origin.read_bytes() and not update_owned:
                raise OverlayError(f"PoC source destination collision: {target}")


AUDIO_STOP_ORIGINAL = '''            Timber.tag("PulseAudioComponent").d("Stopping...");
            killAllPulseAudioProcesses();'''
AUDIO_STOP_OWNED = '''            Timber.tag("PulseAudioComponent").d("Stopping...");
            // FOREVER_OWNED_AUDIO_SHUTDOWN: request exit via this session's socket.
            // ps NAME may be linker64; never infer daemon ownership from that name.
            execPactlCommand("exit");'''


def transform_pulse_audio(text: str) -> str:
    if AUDIO_STOP_OWNED in text:
        if text.count(AUDIO_STOP_OWNED) != 1 or AUDIO_STOP_ORIGINAL in text:
            raise OverlayError("owned PulseAudio stop anchor changed")
        return text
    return _replace_once(text, AUDIO_STOP_ORIGINAL, AUDIO_STOP_OWNED, "owned PulseAudio stop")


def apply(
    source: Path,
    *,
    overlay: Path = APP_ROOT / "overlay",
    dry_run: bool = False,
    update_owned: bool = False,
    native_read_only: Path | None = None,
    native_provenance: Path | None = None,
) -> list[Path]:
    if not source.is_dir() or source.is_symlink():
        raise OverlayError(f"source checkout is not a real directory: {source}")
    build = _read(source, BUILD)
    manifest = _read(source, MANIFEST)
    xserver = _read(source, XSERVER)
    pulse_audio = _read(source, PULSE_AUDIO)
    transformed = {
        BUILD: transform_build(build),
        MANIFEST: transform_manifest(manifest),
        XSERVER: transform_xserver(xserver),
        PULSE_AUDIO: transform_pulse_audio(pulse_audio),
    }
    recipe_sha = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    native_bytes = None
    native_target = source / "app/src/main/jniLibs/arm64-v8a/libforever_patcher_jni.so"
    native_audit = None
    native_provenance_value = None
    native_provenance_sha256 = None
    if native_read_only is not None:
        if native_provenance is None:
            raise OverlayError("--native-provenance is required with --native-read-only")
        native_provenance_value, native_audit, native_provenance_sha256, native_bytes = _validate_native_provenance(native_provenance, native_read_only)
        if native_target.is_symlink():
            raise OverlayError("read-only JNI destination is a symlink")
    elif native_provenance is not None:
        raise OverlayError("--native-provenance requires --native-read-only")
    marker_value = json.loads(marker_bytes(
        recipe_sha,
        overlay,
        native_audit=native_audit,
        native_provenance=native_provenance_value,
        native_provenance_sha256=native_provenance_sha256,
    ))
    if native_bytes is not None:
        marker_value["readOnlyPatchBridge"] = marker_value["nativeElfAudit"]
    marker_text = json.dumps(marker_value, indent=2, sort_keys=True) + "\n"
    marker_path = source / MARKER
    if update_owned:
        if not marker_path.is_file() or marker_path.is_symlink():
            raise OverlayError("own PoC provenance is required before source update")
        previous = json.loads(marker_path.read_text(encoding="utf-8"))
        if previous.get("variant") != "wowforever.functional-poc/v1" or previous.get("versionCode") not in (CURRENT_VERSION[0], TARGET_VERSION[0]):
            raise OverlayError("not an existing functional PoC source")
    if marker_path.exists() or marker_path.is_symlink():
        if marker_path.is_symlink() or not marker_path.is_file() or (marker_path.read_text(encoding="utf-8") != marker_text and not update_owned):
            raise OverlayError(f"functional PoC provenance collision: {marker_path}")
    _validate_source_destinations(source, overlay, update_owned)

    changed: list[Path] = []
    if not dry_run:
        for relative, data in transformed.items():
            if _write_if_changed(source, relative, data):
                changed.append(source / relative)
        changed.extend(_stage_sources(source, overlay, update_owned))
        if native_bytes is not None:
            native_target.parent.mkdir(parents=True, exist_ok=True)
            if not native_target.exists() or native_target.read_bytes() != native_bytes:
                native_target.write_bytes(native_bytes)
                changed.append(native_target)
        if not marker_path.exists() or update_owned:
            marker_path.parent.mkdir(parents=True, exist_ok=True)
            marker_path.write_text(marker_text, encoding="utf-8")
            changed.append(marker_path)
    return changed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="generated GameNative checkout")
    parser.add_argument("--overlay", type=Path, default=APP_ROOT / "overlay")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--update-owned", action="store_true", help="update only the functional PoC sources/tests and its own marker")
    parser.add_argument("--native-read-only", type=Path, help="cross-built read-only functional JNI bridge")
    parser.add_argument("--native-provenance", type=Path, help="independent before/after sidecar for --native-read-only")
    args = parser.parse_args()
    changed = apply(
        args.source.resolve(), overlay=args.overlay.resolve(), dry_run=args.dry_run,
        update_owned=args.update_owned, native_read_only=args.native_read_only,
        native_provenance=args.native_provenance,
    )
    print(f"FUNCTIONAL-POC-{'CHECK' if args.dry_run else 'APPLY'} PASS")
    for path in changed:
        print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
