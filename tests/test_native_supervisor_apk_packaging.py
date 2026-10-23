"""Host-only packaging gates for the code 59 native supervisor candidate."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import re
import struct
import zipfile
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
APPLY_PATH = ROOT / "scripts/arm64-prototype/app/apply_overlay.py"
SPEC = importlib.util.spec_from_file_location("native_supervisor_overlay", APPLY_PATH)
assert SPEC and SPEC.loader
overlay = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(overlay)


def _elf(*, dependency: bytes = b"libc.so") -> bytes:
    """Build a tiny ET_DYN fixture with linker64 and 16KiB LOADs."""
    data = bytearray(0x8000)
    data[:4] = b"\x7fELF"
    data[4:6] = b"\x02\x01"
    struct.pack_into("<HHIQQQIHHHHHH", data, 16, 3, 183, 1, 0, 64, 0, 0, 64, 56, 4, 0, 0, 0)
    ph = 64
    # First LOAD backs the headers, interpreter, dynamic table and strings.
    struct.pack_into("<IIQQQQQQ", data, ph, 1, 5, 0, 0, 0, 0x5000, 0x5000, 0x4000)
    ph += 56
    struct.pack_into("<IIQQQQQQ", data, ph, 1, 6, 0x4000, 0x4000, 0, 0x4000, 0x4000, 0x4000)
    ph += 56
    interpreter = b"/system/bin/linker64\0"
    data[0x300:0x300 + len(interpreter)] = interpreter
    struct.pack_into("<IIQQQQQQ", data, ph, 3, 0, 0x300, 0, 0, len(interpreter), len(interpreter), 1)
    ph += 56
    struct.pack_into("<IIQQQQQQ", data, ph, 2, 0, 0x1000, 0, 0, 48, 48, 8)
    strings = b"\0" + dependency + b"\0"
    data[0x2000:0x2000 + len(strings)] = strings
    struct.pack_into("<QQ", data, 0x1000, 5, 0x2000)
    struct.pack_into("<QQ", data, 0x1010, 10, len(strings))
    struct.pack_into("<QQ", data, 0x1020, 1, 1)
    return bytes(data)


def _native_fixture(root: Path) -> tuple[Path, dict[str, object]]:
    root.mkdir()
    files: dict[str, dict[str, object]] = {}
    for name in overlay.NATIVE_SUPERVISOR_FILES:
        path = root / name
        path.write_bytes(_elf())
        path.chmod(0o755)
        files[name] = {"size": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    manifest = {
        "schema": 1,
        "variant": overlay.NATIVE_SUPERVISOR_VARIANT,
        "source": {"repository": "fixture", "commit": "a" * 40},
        "recipe": {"path": "fixture.sh", "sha256": "b" * 64},
        "provenance": {"compiler": "fixture", "ndk": "24"},
        "artifacts": {name: {"bytes": item["size"], "sha256": item["sha256"], "elfMachine": 183, "interpreter": "/system/bin/linker64", "loadSegmentAlignment": 16384} for name, item in files.items()},
    }
    (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    return root, manifest


def _fixture_pins(monkeypatch: pytest.MonkeyPatch, root: Path, manifest: dict[str, object]) -> None:
    monkeypatch.setattr(overlay, "NATIVE_SUPERVISOR_MANIFEST_SHA256",
                        hashlib.sha256((root / "manifest.json").read_bytes()).hexdigest())
    monkeypatch.setattr(overlay, "NATIVE_SUPERVISOR_PINS", {
        name: (path.stat().st_size, hashlib.sha256(path.read_bytes()).hexdigest())
        for name in overlay.NATIVE_SUPERVISOR_FILES
        for path in [root / name]
    })


def test_native_fixture_archive_and_manifest_are_pinned(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    root, manifest = _native_fixture(tmp_path / "native-run-supervisor-v2")
    _fixture_pins(monkeypatch, root, manifest)
    checked, entries = overlay.validate_native_supervisor_inputs(root)
    assert checked == manifest
    assert set(entries) == set(overlay.NATIVE_SUPERVISOR_FILES)
    archive_path = tmp_path / "fixture.zip"
    with zipfile.ZipFile(archive_path, "w") as archive:
        for name in (*overlay.NATIVE_SUPERVISOR_FILES, "manifest.json"):
            archive.write(root / name, "assets/forever/native-supervisor/" + name)
    with zipfile.ZipFile(archive_path) as archive:
        assert archive.read("assets/forever/native-supervisor/manifest.json") == (root / "manifest.json").read_bytes()


@pytest.mark.parametrize("dependency", [b"libm.so", b"libdl.so"])
def test_non_libc_or_bad_elf_is_rejected(tmp_path: Path, dependency: bytes, monkeypatch: pytest.MonkeyPatch) -> None:
    root, _manifest = _native_fixture(tmp_path / "native-run-supervisor-v2")
    _fixture_pins(monkeypatch, root, _manifest)
    (root / overlay.NATIVE_SUPERVISOR_FILES[0]).write_bytes(_elf(dependency=dependency))
    with pytest.raises(ValueError, match="non-libc dependency|pin mismatch"):
        overlay.validate_native_supervisor_inputs(root)

    (root / overlay.NATIVE_SUPERVISOR_FILES[0]).write_bytes(b"not an elf")
    with pytest.raises(ValueError, match="not a little-endian ELF"):
        overlay.inspect_native_elf(root / overlay.NATIVE_SUPERVISOR_FILES[0])


def test_manifest_pin_collision_and_unknown_version_fail_closed(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    root, manifest = _native_fixture(tmp_path / "native-run-supervisor-v2")
    _fixture_pins(monkeypatch, root, manifest)
    manifest["artifacts"][overlay.NATIVE_SUPERVISOR_FILES[0]]["sha256"] = "0" * 64
    (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="pin mismatch"):
        overlay.validate_native_supervisor_inputs(root)

    assert overlay.resolve_release_version('versionCode = 55\nversionName = "0.2.31-automated"') == (55, "0.2.31-automated", False)
    assert overlay.resolve_release_version('versionCode = 56\nversionName = "0.2.32-automated"') == (56, "0.2.32-automated", False)
    assert overlay.resolve_release_version('versionCode = 57\nversionName = "0.2.33-automated"') == (57, "0.2.33-automated", False)
    assert overlay.resolve_release_version('versionCode = 58\nversionName = "0.2.34-automated"') == (58, "0.2.34-automated", False)
    assert overlay.resolve_release_version('versionCode = 59\nversionName = "0.2.35-automated"') == (59, "0.2.35-automated", True)
    assert overlay.resolve_release_version('versionCode = 23\nversionName = "1.2.1"') == (23, "1.2.1", False)
    with pytest.raises(ValueError, match="collision"):
        overlay.resolve_release_version('versionCode = 56\nversionName = "0.2.31-automated"')
    with pytest.raises(ValueError, match="unknown"):
        overlay.resolve_release_version('versionCode = 999\nversionName = "future"')


def test_existing_payloads_are_byte_preserved_and_recipe_is_native_only(tmp_path: Path) -> None:
    app = tmp_path / "app"
    retained = app / "src/main/assets/legacy/runtime.bin"
    retained.parent.mkdir(parents=True)
    retained.write_bytes(b"retained-runtime")
    retained_lib = app / "src/main/jniLibs/arm64-v8a/libold.so"
    retained_lib.parent.mkdir(parents=True)
    retained_lib.write_bytes(b"retained-panvk-or-no-opengl")
    snapshot = overlay.snapshot_preserved_payloads(app)
    overlay.assert_preserved_payloads(snapshot)
    retained.write_bytes(b"changed")
    with pytest.raises(ValueError, match="existing payload changed"):
        overlay.assert_preserved_payloads(snapshot)

    recipe = APPLY_PATH.read_text(encoding="utf-8")
    contract = (ROOT / "scripts/arm64-prototype/app/launch-contract.json").read_text(encoding="utf-8")
    assert "TARGET_VERSION_CODE = 59" in recipe and "TARGET_VERSION_NAME = \"0.2.35-automated\"" in recipe
    assert "NATIVE_SUPERVISOR_VARIANT = \"native-run-supervisor-v2\"" in recipe
    assert '"launchKind": "native_run_supervisor"' in recipe
    assert '"kind": "native_run_supervisor"' in contract
    assert "useLegacyPackaging = true" in recipe and "extractNativeLibs=\"true\"" in recipe


def test_only_intended_native_v2_payload_paths_may_replace_v1(tmp_path: Path) -> None:
    app = tmp_path / "app"
    old_manifest = app / "src/main/assets/forever/native-supervisor/manifest.json"
    old_manifest.parent.mkdir(parents=True)
    old_manifest.write_bytes(b"v1 manifest")
    native_dir = app / "src/main/jniLibs/arm64-v8a"
    native_dir.mkdir(parents=True)
    for name in overlay.NATIVE_SUPERVISOR_FILES:
        (native_dir / name).write_bytes(b"old-v1")
    unrelated = app / "src/main/assets/legacy/libforever_run_supervisor.so"
    unrelated.parent.mkdir(parents=True)
    unrelated.write_bytes(b"must remain")
    same_name_other_path = app / "src/main/assets/legacy/manifest.json"
    same_name_other_path.write_bytes(b"also remains")
    snapshot = overlay.snapshot_preserved_payloads(app)
    assert old_manifest in snapshot
    assert all(native_dir / name in snapshot for name in overlay.NATIVE_SUPERVISOR_FILES)
    assert unrelated in snapshot and same_name_other_path in snapshot
    old_manifest.write_bytes(b"v2 manifest")
    for name in overlay.NATIVE_SUPERVISOR_FILES:
        (native_dir / name).write_bytes(b"new-v2")
    with pytest.raises(ValueError, match="existing payload changed"):
        overlay.assert_preserved_payloads(snapshot)


def test_fresh_upstream_and_code56_to57_recipe_transitions_are_idempotent() -> None:
    for source in (
        'versionCode = 23\nversionName = "1.2.1"',
        'versionCode = 56\nversionName = "0.2.32-automated"',
    ):
        text = source
        code = re.search(r"\bversionCode\s*=\s*\d+\b", text)
        name = re.search(r'\bversionName\s*=\s*"[^"]+"', text)
        assert code and name
        text = text.replace(code.group(0), "versionCode = 59").replace(name.group(0), 'versionName = "0.2.35-automated"')
        once = text
        text = text.replace("versionCode = 59", "versionCode = 59").replace('versionName = "0.2.35-automated"', 'versionName = "0.2.35-automated"')
        assert text == once
        assert overlay.resolve_release_version(text) == (59, "0.2.35-automated", True)


def test_prior_runtime_rewrite_anchors_remain_unchanged() -> None:
    recipe = APPLY_PATH.read_text(encoding="utf-8")
    for old, new in (
        ('"MEDIACONV_AUDIO_DUMP_FILE=/data/data/app.gamenative/files/imagefs/home/xuser/audio.dmp"', '"MEDIACONV_AUDIO_DUMP_FILE=/data/data/" + BuildConfig.APPLICATION_ID'),
        ('"MEDIACONV_VIDEO_DUMP_FILE=/data/data/app.gamenative/files/imagefs/home/xuser/video.dmp"', '"MEDIACONV_VIDEO_DUMP_FILE=/data/data/" + BuildConfig.APPLICATION_ID'),
        ('missingDrives += "E:/data/data/app.gamenative/storage";', 'missingDrives += "E:" + appDataDir + "/storage";'),
        ('path.endsWith("/app.gamenative/storage")', 'path.endsWith(appDataDir + "/storage")'),
    ):
        assert old in recipe and new in recipe


def test_available_native_controls_match_pinned_ndk_metadata() -> None:
    source = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/native-run-supervisor-v2")
    assert source.is_dir()
    _manifest, entries = overlay.validate_native_supervisor_inputs(source)
    assert hashlib.sha256((source / "manifest.json").read_bytes()).hexdigest() == "a246a11bc142fecbe156c40ac8913baba02a172288aff8b1f2787c21eb1134b6"
    for name, pin in entries.items():
        inspected = overlay.inspect_native_elf(source / name)
        assert pin["bytes"] == inspected["size"]
        assert pin["sha256"] == inspected["sha256"]
        assert (pin["bytes"], pin["sha256"]) == overlay.NATIVE_SUPERVISOR_PINS[name]
        assert pin.get("elfMachine", 183) == 183
        assert pin.get("interpreter", "/system/bin/linker64") == "/system/bin/linker64"
        assert pin.get("loadSegmentAlignment", 16384) == 16384


def test_old_variant_is_not_accepted_by_new_recipe(tmp_path: Path) -> None:
    root, manifest = _native_fixture(tmp_path / "native-run-supervisor-v2")
    manifest["variant"] = "native-run-supervisor-v1"
    (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="unexpected native supervisor manifest identity"):
        overlay.validate_native_supervisor_inputs(root)


def test_frozen_prelaunch_disposition_is_pinned_and_prelaunch_only() -> None:
    raw = overlay.validate_prelaunch_disposition_source()
    assert hashlib.sha256(raw).hexdigest() == overlay.PRELAUNCH_DISPOSITION_SHA256
    value = json.loads(raw)
    assert value["kind"] == "EXACT_HISTORICAL_PRELAUNCH_AUTHORIZATION"
    assert value["variant"] == overlay.NATIVE_SUPERVISOR_VARIANT
    assert value["priorStatus"] == "UNKNOWN" and value["nativeExit"] is None and value["echild"] is False
    assert all(value["runFiles"][name]["devicePresence"] == "missing"
               for name in ("stdout", "stderr", "witness-stdout", "witness-stderr"))


def test_native_manifest_disables_inherited_entries_idempotently(tmp_path):
    import xml.etree.ElementTree as ET
    manifest = tmp_path / "AndroidManifest.xml"
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
        xmlns:tools="http://schemas.android.com/tools"><application>
        <!--
         android:windowSoftInputMode baseline anchor -->
        <activity android:name=".MainActivity" />
        <activity-alias android:name=".MainActivityAliasDefault" android:targetActivity=".MainActivity" android:enabled="true" />
        <activity android:name=".ui.screen.auth.NexusOAuthCallbackActivity" android:exported="true" />
        </application></manifest>''')
    overlay.configure_native_only_manifest(manifest)
    once = manifest.read_bytes()
    overlay.configure_native_only_manifest(manifest)
    assert once == manifest.read_bytes()
    ns = "{http://schemas.android.com/apk/res/android}"
    nodes = list(ET.fromstring(once).find("application"))
    for name in (".MainActivity", ".MainActivityAliasDefault", ".ui.screen.auth.NexusOAuthCallbackActivity",
                 "androidx.compose.ui.tooling.PreviewActivity", "androidx.activity.ComponentActivity"):
        matches = [node for node in nodes if node.get(ns + "name") == name]
        assert len(matches) == 1 and matches[0].get(ns + "enabled") == "false"
    native = next(node for node in nodes if node.get(ns + "name") == "app.gamenative.ForeverNativeSupervisorActivity")
    assert native.get(ns + "permission") == "android.permission.DUMP"
