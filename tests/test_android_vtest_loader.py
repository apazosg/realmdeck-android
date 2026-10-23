"""Offline tests for the bounded host-runner loader qualification."""

from __future__ import annotations

import hashlib
import importlib.util
import os
import stat
import subprocess
import io
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
PATH = ROOT / "scripts/diagnostics/android-vtest/run_loader_usb.py"
SPEC = importlib.util.spec_from_file_location("vtest_loader", PATH)
assert SPEC and SPEC.loader
loader = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(loader)


def _elf(size: int = 64) -> bytes:
    size = max(size, 256)
    data = bytearray(size)
    data[:4] = b"\x7fELF"
    data[4:6] = b"\x02\x01"
    data[16:18] = (3).to_bytes(2, "little")
    data[18:20] = (183).to_bytes(2, "little")
    data[32:40] = (64).to_bytes(8, "little")
    data[54:56] = (56).to_bytes(2, "little")
    data[56:58] = (1).to_bytes(2, "little")
    data[64:68] = (3).to_bytes(4, "little")
    data[72:80] = (120).to_bytes(8, "little")
    data[96:104] = (21).to_bytes(8, "little")
    data[120:141] = b"/system/bin/linker64\0"
    return bytes(data)


def _document(tmp_path: Path) -> dict:
    materialized = []
    destinations = sorted(loader.EXPECTED_DESTINATIONS)
    for destination in destinations:
        payload = _elf()
        path = tmp_path / destination
        path.parent.mkdir(parents=True, exist_ok=True)
        mode = 0o700 if destination == "bin/virgl_test_server" else 0o600
        path.write_bytes(payload)
        path.chmod(mode)
        materialized.append({
            "bytes": len(payload),
            "destination": destination,
            "mode": format(mode, "04o"),
            "sha256": hashlib.sha256(payload).hexdigest(),
        })
    # The validator's fixed total is tested separately; this fixture is for
    # schema/path checks only and is deliberately not a runnable candidate.
    return {
        "schema": loader.SCHEMA,
        "schemaVersion": 1,
        "executedPayloads": False,
        "ready": False,
        "deviceAliases": loader.EXPECTED_ALIASES,
        "protocol": {"status": "UNVERIFIED"},
        "materializedFiles": materialized,
    }


def test_manifest_rejects_path_traversal_and_glvnd_regular_payload(tmp_path: Path) -> None:
    document = _document(tmp_path)
    document["materializedFiles"][0]["destination"] = "lib/../escape.so"
    with pytest.raises(ValueError, match="destination"):
        loader.validate_manifest_document(document, tmp_path)

    document = _document(tmp_path)
    document["materializedFiles"][0]["destination"] = "lib/libEGL.so.1"
    with pytest.raises(ValueError, match="payload destination|GLVND"):
        loader.validate_manifest_document(document, tmp_path)


def test_manifest_rejects_symlink_and_wrong_mode_without_following(tmp_path: Path) -> None:
    document = _document(tmp_path)
    target = tmp_path / "lib/libX11.so"
    replacement = tmp_path / "outside"
    replacement.write_bytes(_elf())
    target.unlink()
    target.symlink_to(replacement)
    with pytest.raises(ValueError, match="symlink"):
        loader.validate_manifest_document(document, tmp_path)
    target.unlink()

    document = _document(tmp_path)
    target = tmp_path / "lib/libX11.so"
    target.chmod(0o644)
    with pytest.raises(ValueError, match="metadata"):
        loader.validate_manifest_document(document, tmp_path)


def test_manifest_rejects_mismatched_uid_and_alias_evidence() -> None:
    # The device-facing preflight is mocked, so this test cannot call ADB.
    original = loader.adb
    calls: list[tuple[str, ...]] = []

    def fake_adb(*args: str, timeout: int = 15) -> subprocess.CompletedProcess[bytes]:
        calls.append(args)
        if args[:2] == ("get-state",):
            return subprocess.CompletedProcess(args, 0, b"device\n", b"")
        if args[:4] == ("exec-out", "dumpsys", "package", loader.PACKAGE):
            return subprocess.CompletedProcess(args, 0, b"versionCode=41\n", b"")
        if args[:3] == ("exec-out", "run-as", loader.PACKAGE) and args[3:] == ("id", "-u"):
            return subprocess.CompletedProcess(args, 0, b"10329\n", b"")
        raise AssertionError(f"unexpected mocked command: {args}")

    loader.adb = fake_adb
    try:
        with pytest.raises(RuntimeError, match="UID"):
            loader._package_preflight()
    finally:
        loader.adb = original
    assert calls


def test_manifest_pin_is_checked_before_candidate_json_is_used(tmp_path: Path) -> None:
    manifest = tmp_path / "candidate-manifest.json"
    manifest.write_text("{}", encoding="ascii")
    original_path, original_hash = loader.MANIFEST, loader.MANIFEST_SHA256
    loader.MANIFEST = manifest
    try:
        with pytest.raises(ValueError, match="hash mismatch"):
            loader.load_candidate()
    finally:
        loader.MANIFEST, loader.MANIFEST_SHA256 = original_path, original_hash


def test_help_status_requires_process_success_native_usage_and_complete_pipes() -> None:
    help_text = b"Usage: virgl_test_server --use-gles --socket-path PATH\n"
    assert loader.validate_help_status(help_text, b"", 0, True) == "LOADER_HELP_PASS"
    assert loader.validate_help_status(help_text, b"", 7, True) == "LOAD_FAILED"
    assert loader.validate_help_status(help_text, b"", 0, False) == "UNKNOWN"
    assert loader.validate_help_status(b"--use-gles --socket-path virgl_test_server", b"", 0, True) == "UNKNOWN"
    assert loader.validate_help_status(b"", b"", 0, True) == "UNKNOWN"
    assert loader.validate_help_status(help_text, b"", 0, True, stdout_truncated=True) == "UNKNOWN"
    assert loader.validate_help_status(help_text, b"", 0, True, stderr_truncated=True) == "UNKNOWN"
    rejected_help = b"virgl_test_server: unrecognized option `--help'\n"
    assert loader.validate_help_status(help_text, rejected_help, 1, True) == "LOADER_USAGE_OBSERVED"
    assert loader.validate_help_status(help_text, rejected_help, 7, True) == "LOAD_FAILED"
    assert loader.validate_help_status(help_text, rejected_help, 1, False) == "UNKNOWN"


def test_capture_drain_is_bounded_and_preserves_truncation() -> None:
    read_fd, write_fd = os.pipe()
    try:
        os.write(write_fd, b"x" * 32)
    finally:
        os.close(write_fd)
    result: dict = {}
    with os.fdopen(read_fd, "rb") as stream:
        loader._drain("stdout", stream, 8, result)
    assert result["stdout"] == {"data": b"x" * 8, "total": 32, "truncated": True}


def test_exclusive_host_output_never_overwrites(tmp_path: Path) -> None:
    path = tmp_path / "capture.json"
    loader._write_exclusive(path, b"first")
    with pytest.raises(FileExistsError):
        loader._write_exclusive(path, b"second")
    assert path.read_bytes() == b"first"
    assert stat.S_IMODE(path.stat().st_mode) == 0o600


def test_system_alias_evidence_requires_regular_elf_and_known_prefix() -> None:
    evidence = b"".join(
        f"VTEST_SYSTEM\t{alias['target']}\t/system/lib64/{alias['name']}\tregular file\t4096\t7f 45 4c 46\t{'a' * 64} {alias['target']}\n".encode()
        for alias in loader.EXPECTED_ALIASES
    )
    parsed = loader.parse_system_alias_evidence(evidence)
    assert [item["name"] for item in parsed] == ["libEGL.so.1", "libGLESv2.so.2"]
    bad = evidence.replace(
        b"\t/system/lib64/libEGL.so.1\t", b"\t/vendor/lib64/libEGL.so.1\t", 1
    )
    with pytest.raises(ValueError, match="known prefixes"):
        loader.parse_system_alias_evidence(bad)


def test_remote_status_requires_shell_v2_and_exact_nonzero_propagation() -> None:
    okay = subprocess.CompletedProcess([], 37, b"VTEST_STATUS_SENTINEL", b"")
    assert loader.validate_remote_status(b"cmd,shell_v2,stat_v2\n", okay)["sentinelExitCode"] == 37
    assert loader.validate_remote_status(b"shell_v2\ncmd\nstat_v2\n", okay)["shellV2"] is True
    for bad in [subprocess.CompletedProcess([], 0, okay.stdout, b""),
                subprocess.CompletedProcess([], 37, b"error text", b""),
                subprocess.CompletedProcess([], 37, okay.stdout, b"warning")]:
        with pytest.raises(RuntimeError, match="sentinel"):
            loader.validate_remote_status(b"shell_v2", bad)
    with pytest.raises(RuntimeError, match="shell_v2"):
        loader.validate_remote_status(b"cmd", okay)


def test_capture_is_durable_before_exec_and_stdin_is_closed() -> None:
    source = PATH.read_text()
    capture = source.index('_write_exclusive(out / "capture.json"')
    execute = source.index("process = subprocess.Popen(command, stdin=subprocess.DEVNULL")
    assert capture < execute
    assert "chmod {item['mode']}" in source
    assert "device artifact mode mismatch" in source
    assert "owned staging cleanup failed; no payload executed" in source


def test_capture_write_failure_never_starts_a_payload(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(loader, "_external_parent", lambda value: tmp_path)
    monkeypatch.setattr(loader, "load_candidate", lambda: ({}, [], b"{}"))
    monkeypatch.setattr(loader, "remote_status_preflight", lambda: {"shellV2": True})
    monkeypatch.setattr(loader, "_package_preflight", lambda: (10330, "/data/user/0/" + loader.PACKAGE, "runas_app"))
    monkeypatch.setattr(loader, "system_alias_evidence", lambda: [])
    monkeypatch.setattr(loader, "copy_payload", lambda files, run: (loader.PRIVATE_PREFIX + "/" + run, {"stagingClean": True}))
    def fail_write(*args: object) -> None:
        raise OSError("fixture disk full")
    monkeypatch.setattr(loader, "_write_exclusive", fail_write)
    monkeypatch.setattr(loader.subprocess, "Popen", lambda *args, **kwargs: pytest.fail("exec after failed capture"))
    with pytest.raises(OSError, match="disk full"):
        loader.main(["--output", str(tmp_path)])


def test_mocked_help_exec_closes_stdin_and_preserves_planned_capture(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(loader, "_external_parent", lambda value: tmp_path)
    monkeypatch.setattr(loader, "load_candidate", lambda: ({}, [], b"{}"))
    monkeypatch.setattr(loader, "remote_status_preflight", lambda: {"shellV2": True})
    monkeypatch.setattr(loader, "_package_preflight", lambda: (10330, "/data/user/0/" + loader.PACKAGE, "runas_app"))
    monkeypatch.setattr(loader, "system_alias_evidence", lambda: [])
    monkeypatch.setattr(loader, "copy_payload", lambda files, run: (loader.PRIVATE_PREFIX + "/" + run, {"stagingClean": True}))
    monkeypatch.setattr(loader, "own_processes", lambda uid: [])
    class FakeProcess:
        pid = 1234
        returncode = 0
        stdout = io.BytesIO(b"Usage: virgl_test_server --use-gles --socket-path PATH\n")
        stderr = io.BytesIO(b"")
        def wait(self, timeout: int) -> int:
            return 0
    def start(command: list[str], **kwargs: object) -> FakeProcess:
        assert kwargs["stdin"] == subprocess.DEVNULL
        assert command[-1] == "--help"
        captures = list(tmp_path.glob("native-*/capture.json"))
        assert len(captures) == 1
        return FakeProcess()
    monkeypatch.setattr(loader.subprocess, "Popen", start)
    assert loader.main(["--output", str(tmp_path)]) == 0
