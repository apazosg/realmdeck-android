"""Focused host gates for the first bounded Wine APK package."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import struct
import zipfile
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
APPLY = ROOT / "scripts/arm64-prototype/app/apply_overlay.py"
SPEC = importlib.util.spec_from_file_location("wine_packaging", APPLY)
assert SPEC and SPEC.loader
wine = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(wine)


def _elf(dependency: bytes = b"libc.so") -> bytes:
    data = bytearray(0x8000)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<HHIQQQIHHHHHH", data, 16, 3, 183, 1, 0, 64, 0, 0, 64, 56, 4, 0, 0, 0)
    offset = 64
    struct.pack_into("<IIQQQQQQ", data, offset, 1, 5, 0, 0, 0, 0x5000, 0x5000, 0x4000)
    offset += 56
    struct.pack_into("<IIQQQQQQ", data, offset, 1, 6, 0x4000, 0x4000, 0, 0x4000, 0x4000, 0x4000)
    offset += 56
    interpreter = b"/system/bin/linker64\0"
    data[0x300:0x300 + len(interpreter)] = interpreter
    struct.pack_into("<IIQQQQQQ", data, offset, 3, 0, 0x300, 0, 0, len(interpreter), len(interpreter), 1)
    offset += 56
    struct.pack_into("<IIQQQQQQ", data, offset, 2, 0, 0x1000, 0, 0, 48, 48, 8)
    strings = b"\0" + dependency + b"\0"
    data[0x2000:0x2000 + len(strings)] = strings
    struct.pack_into("<QQ", data, 0x1000, 5, 0x2000)
    struct.pack_into("<QQ", data, 0x1010, 10, len(strings))
    struct.pack_into("<QQ", data, 0x1020, 1, 1)
    return bytes(data)


def test_version_guard_accepts_only_code59_fresh23_or_candidate61() -> None:
    assert wine.resolve_release_version('versionCode = 59\nversionName = "0.2.35-automated"') == (59, "0.2.35-automated", False)
    assert wine.resolve_release_version('versionCode = 23\nversionName = "1.2.1"') == (23, "1.2.1", False)
    assert wine.resolve_release_version('versionCode = 60\nversionName = "0.2.36-automated"') == (60, "0.2.36-automated", True)
    assert wine.resolve_release_version('versionCode = 61\nversionName = "0.2.37-automated"') == (61, "0.2.37-automated", True)
    with pytest.raises(ValueError, match="unknown"):
        wine.resolve_release_version('versionCode = 62\nversionName = "future"')
    with pytest.raises(ValueError, match="collision"):
        wine.resolve_release_version('versionCode = 60\nversionName = "wrong"')


def test_modern_assets_inherit_main_without_equal_priority_duplicates() -> None:
    baseline = wine.load_code59_source_baseline()
    original = next(entry["utf8Postimage"] for entry in baseline["entries"] if entry["path"] == "app/build.gradle.kts")
    changed = wine._transform_gradle(original)
    assert changed.count('srcDirs("src/modern/assets")') == 2
    assert 'srcDirs("src/modern/assets", "src/main/assets")' not in changed
    assert changed.count('srcDirs("src/legacy/assets", "src/main/assets")') == 2
    assert wine._transform_gradle(changed) == changed


def test_reviewed_overlay_source_deltas_are_exact_and_fail_closed(monkeypatch: pytest.MonkeyPatch) -> None:
    expected = {
        "ForeverBoundedLogRetention.kt": "7b92dffd6ed05ae9c6b7ab72f90476cff8c7fcdfe073ed4e49c0f74bca3c03f7",
        "ForeverHostSafetyTest.kt": "4698d034e0f0cacc20528641c4a702db2df51aefa8e08891e93017ccf98c6d45",
        "ForeverLaunchController.kt": "8e89d2b0a79af9c6d6470cbf7897fdcb3941437febac96cd819a48258fdd1dac",
        "ForeverProcessDiagnostics.kt": "8011dfbc1b88751fd655745a76ed888a2d2c8aa80a087d243def776196a9299e",
        "ForeverRunStore.kt": "b6b699cbe5cdca69d08409424bae2b66433a34890ea4af1f386b300a99e9d744",
    }
    baseline = wine.load_code59_source_baseline()
    assert wine.APPROVED_REVIEWED_OVERLAY_DELTAS == expected
    output = wine.validate_overlay_authority(baseline)
    bindings = {binding["name"]: binding for binding in baseline["overlayAuthority"]["bindings"]}
    assert {
        name: hashlib.sha256(output[Path(bindings[name]["path"])]).hexdigest()
        for name in expected
    } == expected

    monkeypatch.setitem(wine.APPROVED_REVIEWED_OVERLAY_DELTAS, "ForeverBoundedLogRetention.kt", "0" * 64)
    with pytest.raises(ValueError, match="reviewed overlay delta pin mismatch: ForeverBoundedLogRetention.kt"):
        wine.validate_overlay_authority(baseline)


def test_apk_audit_ignores_disabled_launchers_but_rejects_default_enabled(monkeypatch) -> None:
    monkeypatch.syspath_prepend(str(APPLY.parent))
    spec = importlib.util.spec_from_file_location("wine_apk_audit_test", APPLY.with_name("verify_apk.py"))
    assert spec and spec.loader
    audit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audit)
    filters = '    E: intent-filter (line=2)\n      E: action (line=3)\n        A: android:name="android.intent.action.MAIN"\n      E: category (line=4)\n        A: android:name="android.intent.category.LAUNCHER"\n'
    enabled = '  A: android:enabled(0x0101000e)=(type 0x12)0xffffffff\n'
    exported = '  A: android:exported(0x01010010)=(type 0x12)0xffffffff\n'
    permission = '  A: android:permission="android.permission.DUMP"\n'
    activity = ('E: activity (line=1)\n  A: android:name="app.gamenative.ForeverWineSupervisorActivity"\n'
                + enabled + exported + permission + '  A: android:process=":wine_supervisor"\n')
    alias = ('E: activity-alias (line=5)\n  A: android:name="app.gamenative.ForeverWineSupervisorLauncher"\n'
             + enabled + exported + permission
             + '  A: android:targetActivity="app.gamenative.ForeverWineSupervisorActivity"\n' + filters)
    old = ('E: activity-alias (line=6)\n  A: android:name="app.gamenative.MainActivityAliasDefault"\n'
           + '  A: android:enabled(0x0101000e)=(type 0x12)0x0\n' + filters)
    audit._audit_components(activity + alias + old)
    foreign = 'E: activity-alias (line=7)\n  A: android:name="other.Entry"\n' + filters
    with pytest.raises(ValueError, match="only the new Wine alias"):
        audit._audit_components(activity + alias + old + foreign)
    record = {"fullSHA256": "a" * 64, "size": 1, "sourceRecipe": "build.sh",
              "packagedPath": "assets/forever/wine-prefix/image-manifest.json"}
    assert audit._resource_record(record, "forever/wine-prefix/image-manifest.json") == record["packagedPath"]
    with pytest.raises(ValueError, match="packaged path mismatch"):
        audit._resource_record({**record, "packagedPath": "assets/foreign.json"}, "forever/wine-prefix/image-manifest.json")


def test_prior_native_upgrade_is_bound_to_two_owned_resources_and_independent_receipt(tmp_path: Path) -> None:
    old, new = b"old-unreleased-native", b"new-native"
    old_hash = hashlib.sha256(old).hexdigest()
    manifest = Path("app/src/main/assets/forever/wine-supervisor/manifest.json")
    native = Path("app/src/main/jniLibs/arm64-v8a/libforever_wine_supervisor.so")
    provenance = tmp_path / "app/src/main/assets/forever/provenance.json"
    provenance.parent.mkdir(parents=True)
    provenance.write_text(json.dumps({
        "versionCode": 60, "sourceBaseline": {"manifestSHA256": wine.SOURCE_BASELINE_SHA256},
        "resources": {
            "forever/wine-supervisor/manifest.json": {"fullSHA256": old_hash, "packagedPath": "assets/forever/wine-supervisor/manifest.json"},
            "lib/arm64-v8a/libforever_wine_supervisor.so": {"fullSHA256": old_hash, "packagedPath": "lib/arm64-v8a/libforever_wine_supervisor.so"},
            "foreign": {"fullSHA256": old_hash, "packagedPath": "foreign"},
        },
    }))
    pin = hashlib.sha256(provenance.read_bytes()).hexdigest()
    allowed = wine.authenticated_prior_wine_resources(tmp_path, pin)
    assert set(allowed) == {manifest, native}
    assert wine.authenticated_prior_wine_resources(tmp_path, None) == {}
    with pytest.raises(ValueError, match="provenance changed"):
        wine.authenticated_prior_wine_resources(tmp_path, "0" * 64)
    for relative in (manifest, native):
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(old)
    with pytest.raises(ValueError, match="collision"):
        wine.validate_exact_destinations(tmp_path, {manifest: new, native: new})
    wine.validate_exact_destinations(tmp_path, {manifest: new, native: new}, allowed)
    (tmp_path / native).write_bytes(b"manual-foreign-mutation")
    with pytest.raises(ValueError, match="collision"):
        wine.validate_exact_destinations(tmp_path, {manifest: new, native: new}, allowed)


def test_unknown_or_foreign_elf_is_rejected(tmp_path: Path) -> None:
    path = tmp_path / "foreign.so"
    path.write_bytes(_elf(b"libm.so"))
    with pytest.raises(ValueError, match="non-libc"):
        wine.inspect_native_elf(path)
    path.write_bytes(b"not an ELF")
    with pytest.raises(ValueError, match="not a little-endian ELF"):
        wine.inspect_native_elf(path)


def test_new_supervisor_pin_validation_rejects_collision_and_accepts_synthetic_pin(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    root = tmp_path / "wine-run-supervisor-v1"
    root.mkdir()
    binary = root / "libforever_wine_supervisor.so"
    binary.write_bytes(_elf())
    binary.chmod(0o755)
    digest = hashlib.sha256(binary.read_bytes()).hexdigest()
    manifest = {
        "schema": 1, "variant": wine.WINE_SUPERVISOR_VARIANT,
        "source": {"repository": "fixture"}, "recipe": {"file": "fixture"}, "provenance": {"compiler": "fixture"},
        "protocol": {"schema": 2},
        "artifacts": {binary.name: {"bytes": binary.stat().st_size, "sha256": digest,
            "elfMachine": 183, "interpreter": "/system/bin/linker64", "loadAlignment": 16384,
            "neededLibraries": ["libc.so"]}},
    }
    manifest_path = root / "manifest.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    monkeypatch.setattr(wine, "WINE_SUPERVISOR_MANIFEST_SHA256", hashlib.sha256(manifest_path.read_bytes()).hexdigest())
    monkeypatch.setattr(wine, "WINE_SUPERVISOR_PINS", {binary.name: (binary.stat().st_size, digest)})
    monkeypatch.setattr(wine, "WINE_SUPERVISOR_PIN_STATUS", "READY")
    checked, entries = wine.validate_wine_supervisor_inputs(root)
    assert checked["protocol"]["schema"] == 2 and entries[binary.name]["sha256"] == digest

    manifest["artifacts"][binary.name]["sha256"] = "0" * 64
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="manifest pin"):
        wine.validate_wine_supervisor_inputs(root)


def test_prefix_inputs_are_blocked_before_any_materialization(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(wine, "PREFIX_INPUTS_STATUS", "BLOCKED")
    with pytest.raises(wine.InputBlockedError, match="BLOCKED"):
        wine.validate_prefix_inputs(tmp_path / "wine-prefix-v1")


def test_current_prefix_resources_authenticate_independently() -> None:
    root = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/wine-prefix-v1")
    assert root.is_dir()
    publisher, files = wine.validate_prefix_inputs(root)
    assert publisher["variant"] == "wine-prefix-v1"
    assert set(files) == set(wine.PREFIX_FILES)


def test_tampered_prefix_pin_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    root = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/wine-prefix-v1")
    assert root.is_dir()
    monkeypatch.setitem(wine.PREFIX_INPUT_PINS, "publisher-manifest.json", (None, "0" * 64))
    with pytest.raises(ValueError, match="independent prefix input pin mismatch"):
        wine.validate_prefix_inputs(root)


def test_runtime_replay_is_streamed_and_idempotent(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    apk = tmp_path / "retained.apk"
    members = {"assets/runtime/proton.wcp": b"runtime-bytes", "lib/arm64-v8a/libold.so": b"native-bytes"}
    with zipfile.ZipFile(apk, "w") as archive:
        for name, data in members.items():
            archive.writestr(name, data)
    pins = {name: (len(data), hashlib.sha256(data).hexdigest()) for name, data in members.items()}
    retained = {"path": apk, "members": pins}
    wine.materialize_baseline_payloads(source, retained)
    wine.materialize_baseline_payloads(source, retained)
    assert (source / "app/src/main/assets/runtime/proton.wcp").read_bytes() == b"runtime-bytes"
    assert (source / "app/src/main/jniLibs/arm64-v8a/libold.so").read_bytes() == b"native-bytes"


def test_runtime_replay_rejects_unknown_collision(tmp_path: Path) -> None:
    source = tmp_path / "source"
    target = source / "app/src/main/assets/runtime/proton.wcp"
    target.parent.mkdir(parents=True)
    target.write_bytes(b"foreign")
    pins = {"assets/runtime/proton.wcp": (len(b"runtime"), hashlib.sha256(b"runtime").hexdigest())}
    with pytest.raises(ValueError, match="baseline replay destination collision"):
        wine.validate_baseline_replay_destinations(source, pins)


def test_kotlin_materialization_uses_exact_reviewed_overlay_and_no_fallback() -> None:
    sources = wine._kotlin_sources()
    tests = wine._kotlin_tests()
    overlay_dir = APPLY.parent / "overlay"
    assert "ForeverRunStore.kt" in sources
    assert "ForeverWinePrefix.kt" in sources
    assert "ForeverWineRunStoreTest.kt" in tests
    for name, text in sources.items():
        assert text == (overlay_dir / name).read_text(encoding="utf-8")
    for name, text in tests.items():
        assert text == (overlay_dir / name).read_text(encoding="utf-8")
    assert "fileKey()?.hashCode" not in sources["ForeverWinePrefix.kt"]
    assert "SERVER_ISOLATION" not in sources["ForeverWinePrefix.kt"]


def test_existing_payload_snapshot_detects_mutation_and_manifest_is_idempotent(tmp_path: Path) -> None:
    app = tmp_path / "app"
    old = app / "src/main/assets/forever/runtime/proton.wcp"
    old.parent.mkdir(parents=True)
    old.write_bytes(b"old-wcp")
    snapshot = wine.snapshot_preserved_payloads(app)
    wine.assert_preserved_payloads(snapshot)
    old.write_bytes(b"changed")
    with pytest.raises(ValueError, match="existing payload changed"):
        wine.assert_preserved_payloads(snapshot)

    manifest = tmp_path / "AndroidManifest.xml"
    manifest.write_text('''<manifest><application><activity android:name=".MainActivity" android:enabled="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>''', encoding="utf-8")
    wine.configure_wine_manifest(manifest)
    once = manifest.read_bytes()
    wine.configure_wine_manifest(manifest)
    assert once == manifest.read_bytes()
    text = once.decode()
    assert 'ForeverWineSupervisorActivity' in text and 'android:process=":wine_supervisor"' in text
    assert text.count('android:name=".MainActivity"') == 1 and 'android:enabled="false"' in text


def test_self_closing_legacy_components_remain_well_formed_xml(tmp_path: Path) -> None:
    import xml.etree.ElementTree as ET
    manifest = tmp_path / "AndroidManifest.xml"
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application><activity android:name="app.gamenative.PanvkWsiActivity" /><activity android:name="app.gamenative.ForeverNativeSupervisorActivity" /></application></manifest>''')
    wine.configure_wine_manifest(manifest)
    once = manifest.read_bytes()
    ET.fromstring(once)
    wine.configure_wine_manifest(manifest)
    assert manifest.read_bytes() == once


def test_resource_provenance_does_not_depend_on_fresh_checkout_path(tmp_path: Path) -> None:
    for relative, expected in (
        ("assets/forever/wine-prefix/manifest.json", "assets/forever/wine-prefix/manifest.json"),
        ("jniLibs/arm64-v8a/libforever_wine_supervisor.so", "lib/arm64-v8a/libforever_wine_supervisor.so"),
    ):
        sources = []
        for name in ("fresh", "actual"):
            path = tmp_path / name / "app/src/main" / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"{}")
            sources.append(wine._resource_provenance(path, "recipe-pin"))
        assert sources[0] == sources[1]
        assert sources[0]["packagedPath"] == expected


def test_changed_lab_source_requires_independently_pinned_prior_provenance(tmp_path: Path) -> None:
    relative = Path("app/src/main/java/app/gamenative/ForeverWinePrefix.kt")
    target = tmp_path / relative
    target.parent.mkdir(parents=True)
    target.write_bytes(b"previous reviewed output")
    payloads = {relative: b"corrected reviewed output"}
    baseline = {"overlayAuthority": {"bindings": []}}
    with pytest.raises(ValueError, match="collision"):
        wine.validate_materialization_destinations(tmp_path, payloads, baseline)
    prior = {
        "versionCode": 61, "versionName": "0.2.37-automated",
        "sourceBaseline": {
            "manifestSHA256": wine.SOURCE_BASELINE_SHA256,
            "overlayBindings": {"generatedSHA256": {str(relative): hashlib.sha256(target.read_bytes()).hexdigest()}},
        },
    }
    path = tmp_path / "app/src/main/assets/forever/provenance.json"
    path.parent.mkdir(parents=True)
    raw = json.dumps(prior).encode()
    path.write_bytes(raw)
    with pytest.raises(ValueError, match="independently pinned"):
        wine.authenticated_prior_source_outputs(tmp_path, "0" * 64, payloads)
    outputs = wine.authenticated_prior_source_outputs(tmp_path, hashlib.sha256(raw).hexdigest(), payloads)
    wine.validate_materialization_destinations(tmp_path, payloads, baseline, outputs)
    target.write_bytes(b"unrelated manual change")
    with pytest.raises(ValueError, match="collision"):
        wine.validate_materialization_destinations(tmp_path, payloads, baseline, outputs)
