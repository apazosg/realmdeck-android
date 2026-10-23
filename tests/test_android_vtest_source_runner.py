"""Offline gates for the pinned official-source vtest runner."""

from __future__ import annotations

import copy
import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
PATH = ROOT / "scripts/diagnostics/android-vtest/run_source_handshake_usb.py"
SPEC = importlib.util.spec_from_file_location("vtest_source_runner", PATH)
assert SPEC and SPEC.loader
source = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = source
SPEC.loader.exec_module(source)


def _document() -> dict:
    return json.loads(source.SOURCE_MANIFEST.read_text(encoding="utf-8"))


def test_source_manifest_pin_is_exact_and_wrong_pin_is_rejected(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    manifest = tmp_path / "candidate-manifest.json"
    manifest.write_text("{}", encoding="utf-8")
    monkeypatch.setattr(source, "SOURCE_MANIFEST", manifest)
    with pytest.raises(ValueError, match="manifest hash mismatch"):
        source.load_candidate()
    assert source.SOURCE_MANIFEST_SHA256 == "3947b472b1aeeba6a9e16e882c2a503e9b05d831006da3ab60c47927bd21e031"


def test_source_manifest_has_its_own_bounded_metadata_limit(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    _, files, raw = source.load_candidate()
    assert len(files) == 10
    assert source.shared.loader.MANIFEST_LIMIT < len(raw) <= source.SOURCE_MANIFEST_LIMIT
    assert source.shared.loader.MANIFEST_LIMIT == 128 * 1024
    manifest = tmp_path / "candidate-manifest.json"
    manifest.write_bytes(b" " * (source.SOURCE_MANIFEST_LIMIT + 1))
    monkeypatch.setattr(source, "SOURCE_MANIFEST", manifest)
    with pytest.raises(ValueError, match="exceeds 1 MiB"):
        source.load_candidate()


def test_old_provider_manifest_stays_bounded(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    manifest = tmp_path / "old-manifest.json"
    manifest.write_bytes(b" " * (source.shared.loader.MANIFEST_LIMIT + 1))
    monkeypatch.setattr(source.shared.loader, "MANIFEST", manifest)
    with pytest.raises(ValueError, match="old provider manifest exceeds 128 KiB"):
        source._old_provider_manifest_pins()


def test_source_rejects_binary_and_old_provider_provenance_mismatch() -> None:
    document = _document()
    document["binaryAudit"][0]["elf"]["ptInterp"] = "/system/bin/linker"
    with pytest.raises(ValueError, match="ELF manifest field"):
        source.validate_source_manifest(document, source.SOURCE_MANIFEST.parent)

    document = _document()
    provider = next(item for item in document["materializedFiles"] if item["destination"] == "lib/libgbm.so")
    provider["sourceIdentity"]["manifest"] = "/tmp/unreviewed-manifest.json"
    with pytest.raises(ValueError, match="provider provenance"):
        source.validate_source_manifest(document, source.SOURCE_MANIFEST.parent)


def test_source_rejects_android_default_fix_or_changed_dynamic_pin() -> None:
    document = _document()
    document["buildProfile"]["functionalEglFix"] = True
    with pytest.raises(ValueError, match="build profile"):
        source.validate_source_manifest(document, source.SOURCE_MANIFEST.parent)

    document = _document()
    library = next(item for item in document["binaryAudit"] if item["destination"] == "lib/libvirglrenderer.so")
    library["elf"]["soname"] = "libvirglrenderer.so"
    with pytest.raises(ValueError, match="ELF manifest field"):
        source.validate_source_manifest(document, source.SOURCE_MANIFEST.parent)


def test_source_payload_nofollow_and_duplicate_outputs_fail_closed(tmp_path: Path) -> None:
    _, files, _ = source.load_candidate()
    target = next(item for item in files if item["destination"] == "lib/libvirglrenderer.so")
    original = target["path"]
    replacement = tmp_path / original.name
    replacement.symlink_to(original)
    target["path"] = replacement
    with pytest.raises(ValueError, match="symlink"):
        source._validate_copy_inputs(files)
    _, files, _ = source.load_candidate()
    with pytest.raises(ValueError, match="duplicate"):
        source._validate_copy_inputs(files[:-1] + [copy.deepcopy(files[0])])


def test_source_alias_is_one_exact_private_link_and_target_is_pinned(monkeypatch: pytest.MonkeyPatch) -> None:
    _, files, _ = source.load_candidate()
    calls: list[tuple[tuple[str, ...], int]] = []

    def fake_copy(payload: list[dict], run: str) -> tuple[str, dict]:
        assert len(payload) == 10
        return "files/forever/controls/vtest-loader/VTEST-offline", {"path": "stage", "stagingClean": True}

    def fake_adb(*args: str, timeout: int = 15) -> subprocess.CompletedProcess[bytes]:
        calls.append((args, timeout))
        return subprocess.CompletedProcess(args, 0, b"VTEST_SOURCE_ALIAS_OK", b"")

    monkeypatch.setattr(source.shared, "copy_payload", fake_copy)
    monkeypatch.setattr(source.shared, "adb", fake_adb)
    relative, staging = source.copy_payload(files, "VTEST-offline")
    assert relative.endswith("VTEST-offline")
    assert staging["sourceAlias"] == {
        "destination": "lib/libvirglrenderer.so.1",
        "target": "libvirglrenderer.so",
        "targetSha256": source.SOURCE_LIBRARY_SHA256,
        "targetRegular": True,
        "readlink": "libvirglrenderer.so",
        "exclusive": True,
    }
    script = calls[0][0][-1]
    assert "ln -s libvirglrenderer.so" in script
    assert "test ! -e" in script and "test ! -L" in script
    assert "readlink" in script and "libvirglrenderer.so.1" in script
    assert "ln -sf" not in script and script.count("ln -s ") == 1


def test_source_wrapper_keeps_shared_defaults_and_denies_broader_flags(monkeypatch: pytest.MonkeyPatch) -> None:
    observed: dict[str, object] = {}

    def fake_main(argv: list[str] | None = None, *, candidate_loader=None, payload_copier=None) -> int:
        observed.update({"argv": argv, "loader": candidate_loader, "copier": payload_copier})
        return 17

    monkeypatch.setattr(source.shared, "main", fake_main)
    assert source.main(["--output", "/external/evidence"]) == 17
    assert observed["loader"] is source.load_candidate
    assert observed["copier"] is source.copy_payload

    run = "VTEST-offline"
    event = {
        "runId": run,
        "status": "CAPABILITIES_RECEIVED",
        "nativePid": 1,
        "uid": source.shared.UID,
        "peerPid": 2,
        "peerUid": source.shared.UID,
        "expectedServerPid": 2,
        "expectedServerStartTicks": 3,
        "serverIdentityVerified": True,
        "stages": source.shared.EXPECTED_STAGES,
        "capBytes": 1,
        "capNonzeroBytes": 1,
        "capsFNVhash": "0123456789abcdef",
        "durationMs": 0,
        **{name: False for name in source.shared.BROADER_FLAGS},
    }
    event["gpu"] = True
    with pytest.raises(ValueError, match="broader"):
        source.shared.parse_client_event((json.dumps(event) + "\n").encode(), run)


def test_android_default_candidate_has_independent_pins_and_cannot_pass_diagnostic_gate() -> None:
    document, files, raw = source.load_candidate(candidate=source.ANDROID_DEFAULT)
    assert document["candidateKind"] == "official_source_android_default"
    assert document["buildProfile"]["functionalEglFix"] is True
    assert document["buildProfile"]["compatibilityDefines"] == ["WFVTEST_ANDROID_DEFAULT=1"]
    assert len(files) == 10 and len(raw) <= source.SOURCE_MANIFEST_LIMIT
    assert source.ANDROID_DEFAULT.manifest != source.DIAGNOSTIC.manifest
    with pytest.raises(ValueError, match="build profile"):
        source.validate_source_manifest(document, source.ANDROID_DEFAULT.manifest.parent)
    with pytest.raises(ValueError, match="payload pin"):
        source._validate_copy_inputs(files)
    altered = copy.deepcopy(document)
    altered["buildProfile"]["functionalEglFix"] = False
    with pytest.raises(ValueError, match="build profile"):
        source.validate_source_manifest(altered, source.ANDROID_DEFAULT.manifest.parent, candidate=source.ANDROID_DEFAULT)


def test_source_profile_cli_routes_fixed_candidate_without_changing_shared_cleanup(monkeypatch: pytest.MonkeyPatch) -> None:
    observed = {}

    def fake_main(argv, *, candidate_loader=None, payload_copier=None):
        observed["argv"] = argv
        document, files, _ = candidate_loader()
        assert document["candidateKind"] == "official_source_android_default"
        assert len(files) == 10
        observed["copier"] = payload_copier
        return 19

    monkeypatch.setattr(source.shared, "main", fake_main)
    assert source.main(["--source-profile", "android-default", "--output", "/external/evidence"]) == 19
    assert observed["argv"] == ["--output", "/external/evidence"]
    with pytest.raises(SystemExit):
        source.main(["--source-profile", "/unapproved/payload"])
