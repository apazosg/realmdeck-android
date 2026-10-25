from __future__ import annotations

import importlib.util
import hashlib
import json
from pathlib import Path
import struct

import pytest


ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "scripts/arm64-prototype/app/apply_functional_poc.py"
SPEC = importlib.util.spec_from_file_location("functional_poc_recipe", SCRIPT)
assert SPEC and SPEC.loader
recipe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recipe)


def _fixture(tmp_path: Path) -> Path:
    source = tmp_path / "GameNative"
    (source / "app/src/main/java/app/gamenative/ui/screen/xserver").mkdir(parents=True)
    (source / "app/src/main").mkdir(parents=True, exist_ok=True)
    (source / "app").mkdir(exist_ok=True)
    (source / "app/build.gradle.kts").write_text(
        'android {\n    versionCode = 63\n    versionName = "0.2.39-functional-poc"\n}\n',
        encoding="utf-8",
    )
    (source / "app/src/main/AndroidManifest.xml").write_text(
        """<manifest>
        <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
        <application>
        <activity android:name="app.gamenative.ForeverWineSupervisorActivity"
            android:exported="true" android:enabled="true" android:permission="android.permission.DUMP" />
        <activity-alias android:name="app.gamenative.ForeverWineSupervisorLauncher"
            android:enabled="true" android:permission="android.permission.DUMP"></activity-alias>
        <!--
         android:windowSoftInputMode must always be in the activity block.
        -->
        </application>
        </manifest>
        """,
        encoding="utf-8",
    )
    (source / "app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt").write_text(
        """private fun setup() {
    ProcessHelper.hardKillStaleWineProcesses()

    val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
    ProcessHelper.addDebugCallback { line ->
        if (captureLogs) logFile?.appendText(line + "\\n")
    }
    val gameTerminationCallback = Callback<Int> { status ->
        onGameLaunchError?.invoke("status: $status")
    }
                val wmListener = object : WindowManager.OnWindowModificationListener {
                        override fun onUpdateWindowContent(window: Window) {
                        }
                        override fun onModifyWindowProperty(window: Window, property: Property) {
                        }
                        override fun onMapWindow(window: Window) {
                        }
                        override fun onUnmapWindow(window: Window) {
                        }
                        override fun onChangeWindowZOrder(window: Window) {
                        }
                        override fun onUpdateWindowGeometry(window: Window, resized: Boolean) {
                        }
                }
}
        // FOREVER_AA64_DIRECT: only the verified managed custom game, never a filename heuristic.
""",
        encoding="utf-8",
    )
    (source / recipe.PULSE_AUDIO).parent.mkdir(parents=True, exist_ok=True)
    (source / recipe.PULSE_AUDIO).write_text(recipe.AUDIO_STOP_ORIGINAL)
    return source


def test_diagnostic_output_observes_existing_readers_and_is_idempotent(tmp_path: Path) -> None:
    original = (_fixture(tmp_path) / recipe.XSERVER).read_text()
    once = recipe.transform_xserver(original)
    assert recipe.transform_xserver(once) == once
    assert once.count("ProcessHelper.addDebugCallback") == 1
    assert 'container.getExtra("foreverFunctionalPoc") == "true"' in once
    assert "foreverRemoteOutput?.append(line)" in once
    assert "foreverRemoteOutput?.finished(status)" in once
    assert "if (captureLogs) logFile?.appendText" in once
    assert "setExecObserver" not in once
    assert once.count("ForeverFunctionalPoc.currentWowStartup()") == 1
    assert "observeForeverWowStartup(window, contentUpdated = true)" in once
    assert "foreverWowStartup?.forget(window.id)" in once
    with pytest.raises(recipe.OverlayError, match="startup observation differs"):
        recipe.transform_xserver(once.replace("contentUpdated = true", "contentUpdated = false"))
    with pytest.raises(recipe.OverlayError, match="output hook differs"):
        recipe.transform_xserver(once.replace("finished(status)", "finished(0)"))


def test_audio_stop_uses_owned_control_channel_and_rejects_drift() -> None:
    original = 'start: killAllPulseAudioProcesses();\n' + recipe.AUDIO_STOP_ORIGINAL
    once = recipe.transform_pulse_audio(original)
    assert recipe.transform_pulse_audio(once) == once
    assert once.count('execPactlCommand("exit");') == 1
    assert once.count("killAllPulseAudioProcesses();") == 1  # Startup is unchanged.
    for changed in (once.replace('execPactlCommand("exit");', "killAllPulseAudioProcesses();"), original + recipe.AUDIO_STOP_ORIGINAL):
        with pytest.raises(recipe.OverlayError):
            recipe.transform_pulse_audio(changed)


def test_recipe_applies_and_is_idempotent(tmp_path: Path) -> None:
    source = _fixture(tmp_path)
    recipe.apply(source, overlay=ROOT / "scripts/arm64-prototype/app/overlay")
    snapshot = {
        path.relative_to(source): path.read_bytes()
        for path in source.rglob("*")
        if path.is_file()
    }

    recipe.apply(source, overlay=ROOT / "scripts/arm64-prototype/app/overlay")
    assert snapshot == {
        path.relative_to(source): path.read_bytes()
        for path in source.rglob("*")
        if path.is_file()
    }
    assert 'versionCode = 64' in (source / recipe.BUILD).read_text()
    assert 'buildConfigField("String", "FOREVER_SERVER_PROFILE"' in (
        source / recipe.BUILD
    ).read_text()
    assert recipe.DIRECT_ROUTE in (source / recipe.XSERVER).read_text()
    manifest = (source / recipe.MANIFEST).read_text()
    functional = manifest.split('android:name="app.gamenative.ForeverFunctionalPocActivity"', 1)[1].split('</activity>', 1)[0]
    assert 'android:permission=' not in functional
    assert 'android:permission="android.permission.DUMP"' in manifest.split(
        'android:name="app.gamenative.ForeverWineSupervisorActivity"', 1
    )[1].split('/>', 1)[0]
    assert 'android:permission="android.permission.DUMP"' in manifest.split(
        'android:name="app.gamenative.ForeverWineSupervisorLauncher"', 1
    )[1].split('</activity-alias>', 1)[0]
    assert (source / recipe.MARKER).is_file()
    import json
    marker = json.loads((source / recipe.MARKER).read_text())
    assert marker["argumentsTemplate"] == ["-config", "BetaFunctionalPoc-<profileId>.wtf", "-d3d11"]
    assert marker["profiles"]["original"] == {"config": "BetaFunctionalPoc-original.wtf", "memoryPatchDefault": False}
    assert marker["profiles"]["classic_forever"]["portal"] == "contract.server.portal"
    assert marker["diagnostic"]["bcnEmulationCache"] == "0"
    assert marker["diagnostic"]["wine"] == "Proton11"
    assert marker["diagnostic"]["androidMediaVolume"] == "1/15"
    assert marker["memoryWriteStage"] == "READ_ONLY_FIRST"
    assert marker["memoryPatchEnabledByDefault"] is False
    assert marker["codeCaptureEnabledByDefault"] is False
    assert marker["codeCapture"]["totalBytes"] == 65536
    assert marker["codeCapture"]["sourceSha256"] == "d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59"
    assert marker["jniProvenance"] is None
    assert marker["nativeBuildProvenance"] is None
    assert "ForeverFunctionalServerProfiles.kt" in marker["sourceSha256"]
    assert set(marker["overlaySha256"]) == set(recipe.OVERLAY_NAMES)


def test_recipe_rejects_unknown_version(tmp_path: Path) -> None:
    source = _fixture(tmp_path)
    build = source / recipe.BUILD
    build.write_text(build.read_text().replace("versionCode = 63", "versionCode = 61"), encoding="utf-8")
    with pytest.raises(recipe.OverlayError, match="unsupported source version"):
        recipe.apply(source, overlay=ROOT / "scripts/arm64-prototype/app/overlay")


def test_server_profile_field_is_independent_of_version_and_idempotent() -> None:
    # Standalone build-text transform: no fixture manifest needed (the fixture
    # manifest path has a pre-existing, unrelated permission-anchor drift).
    build = 'android {\n    versionCode = 63\n    versionName = "0.2.39-functional-poc"\n}\n'
    once = recipe.transform_build(build)
    assert "versionCode = 64" in once
    assert once.count("FOREVER_SERVER_PROFILE") == 1
    assert "classic_forever" in once
    # A second transform over the already-target text keeps the version and
    # the single field instead of returning early or duplicating it.
    assert recipe.transform_build(once) == once
    with pytest.raises(recipe.OverlayError, match="differs from classic_forever"):
        recipe.transform_build(once.replace("classic_forever", "original"))


def test_existing_code64_manifest_migrates_landscape_poc_to_portrait() -> None:
    manifest = """<manifest>
        <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
        <application>
        <activity android:name="app.gamenative.ForeverWineSupervisorActivity"
            android:exported="true" android:enabled="false" android:permission="android.permission.DUMP" />
        <activity-alias android:name="app.gamenative.ForeverWineSupervisorLauncher"
            android:enabled="false" android:permission="android.permission.DUMP"></activity-alias>
        <activity android:name="app.gamenative.ForeverFunctionalPocActivity"
            android:exported="true"
            android:permission="android.permission.DUMP"
            android:screenOrientation="landscape"
            android:configChanges="orientation|screenSize|keyboard|keyboardHidden|navigation"
            android:theme="@style/Theme.Pluvia">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <!--
         android:windowSoftInputMode must always be in the activity block.
        -->
        </application>
    </manifest>
    """
    transformed = recipe.transform_manifest(manifest)
    assert 'android:screenOrientation="portrait"' in transformed
    assert 'android:screenOrientation="landscape"' not in transformed
    assert 'android:windowSoftInputMode="adjustNothing"' in transformed
    functional = transformed.split('android:name="app.gamenative.ForeverFunctionalPocActivity"', 1)[1].split('</activity>', 1)[0]
    assert 'android:permission=' not in functional
    assert 'android:permission="android.permission.DUMP"' in transformed.split(
        'android:name="app.gamenative.ForeverWineSupervisorActivity"', 1
    )[1].split('/>', 1)[0]
    assert 'android:permission="android.permission.DUMP"' in transformed.split(
        'android:name="app.gamenative.ForeverWineSupervisorLauncher"', 1
    )[1].split('</activity-alias>', 1)[0]
    assert recipe.transform_manifest(transformed) == transformed

    branded = transformed.replace(
        'android:theme="@style/Theme.Pluvia"',
        'android:theme="@style/Theme.ForeverFunctionalSplash"',
    )
    assert recipe.transform_manifest(branded) == branded


@pytest.mark.parametrize("permission", [
    'android:permission="com.example.UNREVIEWED"',
    'android:permission="android.permission.DUMP" android:permission="android.permission.DUMP"',
])
def test_functional_entry_migration_rejects_unknown_or_duplicated_permission(permission: str) -> None:
    manifest = """<manifest><application>
        <activity android:name="app.gamenative.ForeverWineSupervisorActivity" android:enabled="false" />
        <activity-alias android:name="app.gamenative.ForeverWineSupervisorLauncher" android:enabled="false"></activity-alias>
        <activity android:name="app.gamenative.ForeverFunctionalPocActivity" android:exported="true"
            PERMISSION
            android:screenOrientation="portrait" android:configChanges="orientation|screenSize|keyboard|keyboardHidden|navigation"
            android:windowSoftInputMode="adjustNothing" android:theme="@style/Theme.Pluvia">
            <intent-filter><action android:name="android.intent.action.MAIN" /></intent-filter>
        </activity>
        <!--
         android:windowSoftInputMode must always be in the activity block.
        -->
        </application></manifest>""".replace("PERMISSION", permission)
    with pytest.raises(recipe.OverlayError, match="permission"):
        recipe.transform_manifest(manifest)


def test_functional_entry_migration_rejects_ambiguous_activity() -> None:
    manifest = """<manifest><application>
        <activity android:name="app.gamenative.ForeverWineSupervisorActivity" android:enabled="false" />
        <activity-alias android:name="app.gamenative.ForeverWineSupervisorLauncher" android:enabled="false"></activity-alias>
        <activity android:name="app.gamenative.ForeverFunctionalPocActivity" android:exported="true"
            android:screenOrientation="portrait" android:configChanges="orientation|screenSize|keyboard|keyboardHidden|navigation"
            android:windowSoftInputMode="adjustNothing" android:theme="@style/Theme.Pluvia"></activity>
        <activity android:name="app.gamenative.ForeverFunctionalPocActivity"></activity>
        <!--
         android:windowSoftInputMode must always be in the activity block.
        -->
        </application></manifest>"""
    with pytest.raises(recipe.OverlayError, match="not unique"):
        recipe.transform_manifest(manifest)


def test_route_contract_is_fixed_and_unmanaged() -> None:
    poc = (ROOT / "scripts/arm64-prototype/app/overlay/ForeverFunctionalPoc.kt").read_text()
    assert 'APP_ID = "CUSTOM_GAME_1368588668"' in poc
    assert 'EXEC_ARGS_TEMPLATE = "-config BetaFunctionalPoc-<profileId>.wtf -d3d11"' in poc
    assert 'originalConfig.readText' in poc
    assert 'container.putExtra("foreverManaged", "false")' in poc
    assert "ForeverRunStore" not in poc
    assert "ptrace" not in poc.lower()
    assert "return WINDOWS_EXECUTABLE" in poc
    assert 'File(imageFs.rootDir, "usr/lib")' in poc
    assert 'runtimeLibraries.distinct().joinToString(":")' in poc
    assert 'graphics.put("bcnEmulationType", "software")' in poc
    assert 'graphics.put("bcnEmulation", "none")' in poc
    assert 'graphics.put("resourceType", "ahb")' in poc
    assert 'graphics.put("bcnEmulationCache", "0")' in poc
    assert 'codeCaptureEnabled: Boolean = false' in (ROOT / "scripts/arm64-prototype/app/overlay/ForeverFunctionalServerProfiles.kt").read_text()
    assert 'nativeBegin(executablePath: String, nonce: String, contractPath: String, codeCaptureEnabled: Boolean)' in (ROOT / "scripts/arm64-prototype/app/overlay/ForeverFunctionalPocPatchBridge.kt").read_text()
    session = (ROOT / "scripts/arm64-prototype/app/overlay/ForeverFunctionalPocPatchSession.kt").read_text()
    activity = (ROOT / "scripts/arm64-prototype/app/overlay/ForeverFunctionalPocActivity.kt").read_text()
    assert "fun preflightCapture(" in session
    assert "nativePoll" not in session.split("fun preflightCapture(", 1)[1].split("fun start(", 1)[0]
    assert "throw IllegalStateException(detail)" in session
    assert 'status = "WoW no lanzado"' in activity
    assert '"launch_blocked"' in activity
    profiles = (ROOT / "scripts/arm64-prototype/app/overlay/ForeverFunctionalServerProfiles.kt").read_text()
    assert '"enableBGDL" to "0"' in profiles
    assert '"Sound_MasterVolume" to "0.03"' in profiles
    assert '"showPhotosensitivityWarning" to "0"' in profiles
    assert '"graphicsQuality" to "0"' in profiles
    assert '"maxFPS" to "30"' in profiles
    assert '"GamePadEnable" to "1"' in profiles
    assert '"BetaFunctionalPoc-original.wtf"' in profiles
    assert '"BetaFunctionalPoc-classic_forever.wtf"' in profiles
def test_memory_patch_apply_gating_tripwire():
    bridge = (ROOT / "scripts/arm64-prototype/app/overlay/ForeverFunctionalPocPatchBridge.kt").read_text()
    # Single-token writer: no address/PID/key travels from the caller.
    assert "external fun nativeApply(token: String): String" in bridge
    assert bridge.count("external fun nativeApply") == 1
    session = (ROOT / "scripts/arm64-prototype/app/overlay/ForeverFunctionalPocPatchSession.kt").read_text()
    # Strict-block trigger, never top-level VALIDATED alone; capped never applies.
    assert "fullScan" in session and "validatedStoreCount" in session
    assert "candidateCapped" in session
    assert "patch_apply" in session
    # Poll results stay read-only patch_scan; apply is logged separately.
    assert session.count('"patch_scan"') == 2
    # Success maintains the retained candidate instead of rescanning; DENIED
    # resumes polling, ERROR latches off. Capture worker never references apply.
    assert "maintainApply" in session
    assert "DENIED" in session
    assert "ForeverFunctionalPocPatchBridge.nativeApply(value)" in session
    # Explicit writer gate: default observer stays read-only, writer and
    # capture are mutually exclusive, and the hook authorizes group-8 writes
    # only for the memory-patch opt-in on the Classic Forever profile.
    assert "applyEnabled: Boolean = false" in session
    assert "require(!(applyEnabled && codeCaptureEnabled))" in session
    assert 'put("readOnly", !writesAuthorized)' in session
    poc = (ROOT / "scripts/arm64-prototype/app/overlay/ForeverFunctionalPoc.kt").read_text()
    assert "prepared.memoryPatchEnabled &&" in poc
    assert "prepared.profileId == FunctionalServerProfile.CLASSIC_FOREVER.id" in poc
    assert "!prepared.codeCaptureEnabled" in poc
    capture_worker = session.split("val worker = Thread {", 1)[1].split("return {", 1)[0]
    assert "nativeApply" not in capture_worker
    assert "onApply" not in capture_worker
    assert "maintainApply" not in capture_worker
    assert "applyEnabled" not in capture_worker
    assert "Thread.sleep(POLL_MILLIS)" in capture_worker
    # Approved timing untouched.
    assert "TOTAL_MILLIS = 900_000L" in session
    assert "ACTIVE_MILLIS = 180_000L" in session
    activity = (ROOT / "scripts/arm64-prototype/app/overlay/ForeverFunctionalPocActivity.kt").read_text()
    assert "Opciones de diagnóstico; no necesarias para jugar." not in activity
    assert "solo escribe la clave pública del grupo 8" in activity


def test_wow_startup_gated_observer_timing_tripwire():
    session = (ROOT / "scripts/arm64-prototype/app/overlay/ForeverFunctionalPocPatchSession.kt").read_text()
    activity = (ROOT / "scripts/arm64-prototype/app/overlay/ForeverFunctionalPocActivity.kt").read_text()
    # Same bounds; startup observation replaces Enter. Capture stays immediate.
    assert "TOTAL_MILLIS = 900_000L" in session
    assert "ACTIVE_MILLIS = 180_000L" in session
    assert "requiresStartupGating" in session
    assert "GatedPollSession" in session
    assert "tryArmFromWowStartup(attempt: String)" in session
    assert "if (pendingAttempt != attempt) return false" in session
    # Writer and observer alike wait for startup before reading.
    assert "if (apply == null)" not in session
    assert "while (armMs < 0L" in session
    # PENDING while awaiting startup; SEARCHING only once the worker actually
    # begins reading, published under the terminal guard.
    gate = session.split("val armedAt = synchronized(monitor) { armMs }", 1)[1].split(
        "var maintainApply", 1)[0]
    assert "PatchAttemptPhase.SEARCHING" in gate
    assert "statusLock" in gate
    assert "terminalSent" in gate
    # Single dynamic bound shared by worker and watchdog.
    assert "deadlineLocked" in session
    assert "currentDeadline" in session
    assert "runWriterWorker" not in session
    # Passive hook only: the original forwarding literal stays intact.
    assert "if (wineGuiRunning && PluviaApp.events.emit" in activity
    assert "return super.dispatchKeyEvent(event)" in activity
    assert "tryArmFromGameEnter" not in activity
    assert "isQualifyingGameEnter" not in activity
    # No key-content capture in the hook.
    assert "getUnicodeChar" not in activity


def test_poc_forwards_physical_keys_only_while_wine_gui_is_running():
    from pathlib import Path
    source = (Path(__file__).resolve().parents[1] / "scripts/arm64-prototype/app/overlay/ForeverFunctionalPocActivity.kt").read_text()
    assert "override fun dispatchKeyEvent(event: android.view.KeyEvent)" in source
    assert "if (wineGuiRunning && PluviaApp.events.emit" in source
    assert "return super.dispatchKeyEvent(event)" in source
    assert source.count("onRunningChanged(false)") == 2
    assert "onRunningChanged(true)" in source


def _native_elf(load_alignment: int = 0x4000, marker_byte: int = 0) -> bytes:
    data = bytearray(128)
    data[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<H", data, 18, 183)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<H", data, 54, 56)
    struct.pack_into("<H", data, 56, 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 0, 0, load_alignment)
    data[127] = marker_byte
    return bytes(data)


def _native_sidecar(native: bytes, source_map: dict[str, str], toolchain: dict[str, object]) -> dict[str, object]:
    loads = recipe._native_helpers()[1].audit_elf(native)
    before = {
        "schema": 1,
        "phase": "before",
        "revision": "fixture-revision",
        "dirtyTree": False,
        "sourceSha256": source_map,
        "toolchain": toolchain,
    }
    after = dict(before)
    after["phase"] = "after"
    after["nativeElf"] = {
        "path": "fixture/native.so",
        "bytes": len(native),
        "sha256": hashlib.sha256(native).hexdigest(),
        "elfClass": 64,
        "machine": "AArch64",
        "ptLoad": loads,
    }
    return {"schema": 1, "before": before, "after": after}


def _native_fixture(monkeypatch: pytest.MonkeyPatch, tmp_path: Path):
    source_map = {"Cargo.toml": "a" * 64, "forever/contract.json": "b" * 64}
    toolchain = {
        "api": 29,
        "elfPageSize": 16384,
        "ndk": {"path": "fixture/ndk", "version": "24.0.8215888", "sourcePropertiesSha256": "c" * 64},
        "rustc": {"path": "fixture/rustc", "sha256": "d" * 64, "version": "rustc fixture"},
        "cargo": {"path": "fixture/cargo", "sha256": "e" * 64, "version": "cargo fixture"},
        "llvmStrip": {"path": "fixture/strip", "sha256": "f" * 64, "version": "strip fixture"},
        "linker": {"path": "fixture/linker", "sha256": "0" * 64, "version": "linker fixture"},
        "readelf": {"path": "fixture/readelf", "sha256": "1" * 64, "version": "readelf fixture"},
    }
    monkeypatch.setattr(recipe, "_source_manifest", lambda _root: source_map)
    monkeypatch.setattr(recipe, "_toolchain_manifest", lambda _after: toolchain)
    native = _native_elf()
    native_path = tmp_path / "native.so"
    native_path.write_bytes(native)
    sidecar_path = tmp_path / "provenance.json"
    sidecar = _native_sidecar(native, source_map, toolchain)
    sidecar_path.write_text(json.dumps(sidecar) + "\n", encoding="utf-8")
    return native_path, sidecar_path, sidecar


def test_recipe_requires_and_validates_independent_native_provenance(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    source = _fixture(tmp_path)
    native_path, sidecar_path, sidecar = _native_fixture(monkeypatch, tmp_path)
    recipe.apply(source, overlay=ROOT / "scripts/arm64-prototype/app/overlay", native_read_only=native_path, native_provenance=sidecar_path)
    marker = json.loads((source / recipe.MARKER).read_text(encoding="utf-8"))
    assert marker["nativeProvenanceSidecarSha256"] == hashlib.sha256(sidecar_path.read_bytes()).hexdigest()
    assert marker["nativeBuildProvenance"] == sidecar
    assert marker["nativeElfAudit"]["elfClass"] == 64
    assert marker["nativeElfAudit"]["machine"] == "AArch64"
    assert marker["nativeElfAudit"]["ptLoad"][0]["align"] == 0x4000


def test_recipe_rejects_missing_tampered_and_wrong_phase_native_provenance(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    source = _fixture(tmp_path)
    native_path, sidecar_path, sidecar = _native_fixture(monkeypatch, tmp_path)
    with pytest.raises(recipe.OverlayError, match="required"):
        recipe.apply(source, overlay=ROOT / "scripts/arm64-prototype/app/overlay", native_read_only=native_path)
    tampered = json.loads(json.dumps(sidecar))
    tampered["after"]["nativeElf"]["sha256"] = "0" * 64
    sidecar_path.write_text(json.dumps(tampered), encoding="utf-8")
    with pytest.raises(recipe.OverlayError, match="SHA-256"):
        recipe.apply(source, overlay=ROOT / "scripts/arm64-prototype/app/overlay", native_read_only=native_path, native_provenance=sidecar_path)
    wrong_phase = json.loads(json.dumps(sidecar))
    wrong_phase["before"]["phase"] = "after"
    sidecar_path.write_text(json.dumps(wrong_phase), encoding="utf-8")
    with pytest.raises(recipe.OverlayError, match="before phase"):
        recipe.apply(source, overlay=ROOT / "scripts/arm64-prototype/app/overlay", native_read_only=native_path, native_provenance=sidecar_path)


def test_recipe_rejects_changed_source_toolchain_and_elf_layout(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    source = _fixture(tmp_path)
    native_path, sidecar_path, sidecar = _native_fixture(monkeypatch, tmp_path)
    monkeypatch.setattr(recipe, "_source_manifest", lambda _root: {"changed": "2" * 64})
    with pytest.raises(recipe.OverlayError, match="source map"):
        recipe.apply(source, overlay=ROOT / "scripts/arm64-prototype/app/overlay", native_read_only=native_path, native_provenance=sidecar_path)
    monkeypatch.setattr(recipe, "_source_manifest", lambda _root: sidecar["before"]["sourceSha256"])
    monkeypatch.setattr(recipe, "_toolchain_manifest", lambda _after: {**sidecar["before"]["toolchain"], "api": 30})
    with pytest.raises(recipe.OverlayError, match="toolchain"):
        recipe.apply(source, overlay=ROOT / "scripts/arm64-prototype/app/overlay", native_read_only=native_path, native_provenance=sidecar_path)
    bad_native = tmp_path / "bad-native.so"
    bad_bytes = _native_elf(load_alignment=0x1000)
    bad_native.write_bytes(bad_bytes)
    bad_sidecar = json.loads(json.dumps(sidecar))
    bad_sidecar["after"]["nativeElf"]["bytes"] = len(bad_bytes)
    bad_sidecar["after"]["nativeElf"]["sha256"] = hashlib.sha256(bad_bytes).hexdigest()
    bad_sidecar["after"]["nativeElf"]["ptLoad"][0]["align"] = 0x1000
    sidecar_path.write_text(json.dumps(bad_sidecar), encoding="utf-8")
    monkeypatch.setattr(recipe, "_toolchain_manifest", lambda _after: sidecar["before"]["toolchain"])
    with pytest.raises(recipe.OverlayError, match="16KiB audit"):
        recipe.apply(source, overlay=ROOT / "scripts/arm64-prototype/app/overlay", native_read_only=bad_native, native_provenance=sidecar_path)


def test_failed_wine_setup_cancels_owned_patch_session():
    # US-004 TC-025 (approved lot A1): Wine setup can fail without a normal
    # guest-exit callback. The owned patch session must be cancelled on that
    # path through the existing cancelPatch seam, exactly like onExit and
    # navigateBack; no new end/delete/force machinery, no game kill, no lease
    # deletion, no relaxed restore.
    activity = (ROOT / "scripts/arm64-prototype/app/overlay/ForeverFunctionalPocActivity.kt").read_text()
    block = activity.split("onGameLaunchError = { message ->", 1)[1].split("onWindowMapped", 1)[0]
    assert "cancelPatch()" in block
    # The terminal release still runs once through the existing shutdown path.
    assert "releaseGuestAfterShutdown" in block
    assert "prepared = null" in block
    # The error text stays human wording; the raw runtime message is only logged.
    assert "recordRemoteDiagnosticsAction" in block
    assert "launch_error" in block


def test_patch_status_is_advisory_attempt_isolated_and_never_green_from_scan():
    # US-004 TC-024/TC-026 (approved lot A2): minimal typed advisory state.
    # Only a successful native apply confirms application; VALIDATED scans,
    # DENIED, expiry, and capture never do. Terminals distinguish verified
    # from never verified; a restore ERROR stays an error. Late results from
    # an older attempt cannot overwrite the current attempt's state. Status
    # reporting never authorizes writes and never leaks native internals.
    session = (ROOT / "scripts/arm64-prototype/app/overlay/ForeverFunctionalPocPatchSession.kt").read_text()
    assert "enum class PatchAttemptPhase" in session
    for phase in ("DISABLED", "PENDING", "SEARCHING", "VERIFIED", "FAILED", "ENDED"):
        assert phase in session
    assert "data class PatchStatus" in session
    assert "everApplied" in session
    assert "captureOnly" in session
    # VERIFIED is emitted only from the native apply-result path, never from
    # a scan or the capture worker.
    assert session.count("PatchAttemptPhase.VERIFIED") == 1
    # Initial PENDING precedes any thread start; SEARCHING is published by
    # the worker only after the Enter gate, so no fast thread or terminal
    # can be followed by a revived initial status.
    launch = session.split("fun launch()", 1)[1].split("fun arm()", 1)[0]
    assert launch.index("emitStatus(PatchStatus(PatchAttemptPhase.PENDING))") < launch.index("worker!!.start()")
    note = session.split("private fun noteApplyResult", 1)[1].split("private fun runWorker", 1)[0]
    assert "PatchStatus(PatchAttemptPhase.VERIFIED" in note
    assert '"ERROR" ->' in note and "FAILED" in note
    # DENIED after a verified apply returns live monitoring to searching
    # with history; DENIED itself never confirms application.
    assert '"DENIED" ->' in note
    assert "PatchAttemptPhase.SEARCHING, everApplied = true" in note
    # Late same-attempt replies refine history/terminal only, never a live
    # state; the terminal composes under the same lock.
    assert "terminalSent" in note
    assert "Late proof after the terminal" in note
    assert "Late error after the terminal" in note
    assert "ended.get()" in note
    # Sticky closure failure: a failed nativeEnd latches independently of
    # history, so a late proof records everApplied but keeps FAILED.
    assert "endFailed" in note
    assert "PatchAttemptPhase.FAILED, everApplied = true" in note
    terminal = session.split("Terminal, exactly once", 1)[1].split("emitStatus(terminal)", 1)[0]
    assert 'statusOnly(result) != "ENDED"' in terminal
    assert "FAILED" in terminal and "everApplied = true" in terminal
    # Callbacks are best-effort and data-only: no raw native text travels.
    assert "private fun emitStatus" in session
    assert "runCatching { onStatus" in session
    assert "captureOnly = true" in session
    capture_worker = session.split("val worker = Thread {", 1)[1].split("return {", 1)[0]
    assert "VERIFIED" not in capture_worker
    # The ephemeral per-attempt observer is data-only and optional; the hook
    # forwards it without affecting write authority, budgets, or gating.
    poc = (ROOT / "scripts/arm64-prototype/app/overlay/ForeverFunctionalPoc.kt").read_text()
    assert "patchStatusListener" in poc
    assert "onStatus = { snapshot" in poc
    assert "applyEnabled: Boolean = false" in session
    assert "TOTAL_MILLIS = 900_000L" in session
    assert "ACTIVE_MILLIS = 180_000L" in session
    # The UI guard: generation-scoped, main-thread re-post, fixed Spanish
    # strings with no raw errors, addresses, keys, or removal claims. The
    # pre-begin state is PENDING (never stale SEARCHING); a failed begin
    # reports FAILED without resetting live/terminal history.
    activity = (ROOT / "scripts/arm64-prototype/app/overlay/ForeverFunctionalPocActivity.kt").read_text()
    assert "patchAttemptGeneration" in activity
    assert "Looper.getMainLooper" in activity
    assert "patchAttemptLabel" in activity
    assert "PatchAttemptPhase.PENDING" in activity
    assert "settleUnstartedPatchStatus" in activity
    assert "failed = launchCancellation == null" in activity
    # Settle preserves recorded history: PENDING/SEARCHING settle via copy,
    # never fabricating a clean "no verified application" over everApplied.
    settle = activity.split("fun settleUnstartedPatchStatus", 1)[1].split("Single launch path shared", 1)[0]
    assert "current.copy(" in settle
    for wording in (
        "Parche desactivado",
        "Parche: pendiente…",
        "Parche: buscando…",
        "Parche aplicado (verificado)",
        "Parche: fallo",
        "Vigilancia terminada (sin aplicación verificada)",
        "se verificó aplicación",
        "Captura diagnóstica (no aplica cambios)",
    ):
        assert wording in activity
    label = activity.split("private fun patchAttemptLabel", 1)[1].split(
        "/** Dedicated explicit GUI entry point", 1)[0]
    assert "error.message" not in label
    assert "eliminad" not in label
    assert "sin parche" not in label
    assert activity.count('Text("Lanzar WoW")') == 1
