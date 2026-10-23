"""Offline HOST-only tests for the private guest EGL loader runner."""

from __future__ import annotations

import copy
import hashlib
import importlib.util
import io
import json
import os
import subprocess
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
DIRECTORY = ROOT / "scripts/diagnostics/android-vtest"
sys.path.insert(0, str(DIRECTORY))
SPEC = importlib.util.spec_from_file_location("guest_loader_runner", DIRECTORY / "run_guest_loader_usb.py")
assert SPEC and SPEC.loader
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)

RUN = "VTEST-" + "a" * 32
PRIVATE = f"/data/user/0/{runner.PACKAGE}/files/forever/controls/vtest-loader/{RUN}"


def test_fixed_manifest_digest_is_authorized_before_parse(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    manifest = tmp_path / "candidate-manifest.json"
    manifest.write_text("{}", encoding="ascii")
    monkeypatch.setattr(runner, "MANIFEST", manifest)
    with pytest.raises(ValueError, match="manifest hash mismatch"):
        runner.load_candidate()


def test_fixed_manifest_and_tree_pins_are_not_self_authority() -> None:
    assert runner.MANIFEST_SHA256 == "30c43814697a22dc72ee4e3b1eeb8ec9d6b6af228ee9c4ffc21e15b3fe8d9ce9"
    assert runner.TREE_SHA256 == "1cc803fbcbef9a7d1743966702c733fc63b8e311a3aa77f8020ee7594e11d216"


def test_stream_hash_is_bounded_and_does_not_materialize_large_file(tmp_path: Path) -> None:
    path = tmp_path / "large.so"
    path.write_bytes(b"x" * (2 * 1024 * 1024))
    path.chmod(0o600)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    original = runner.PER_FILE_LIMIT
    runner.PER_FILE_LIMIT = 2 * 1024 * 1024
    try:
        result = runner._stream_hash(path, path.stat().st_size, digest)
    finally:
        runner.PER_FILE_LIMIT = original
    assert result["bytes"] == 2 * 1024 * 1024


def test_alias_validation_rejects_absolute_escape_loops_and_long_chains() -> None:
    base = [{"path": "lib/libEGL.so", "target": "libEGL.so.1", "resolvedPath": "lib/libEGL.so.1.1.0"}]
    base.extend({"path": f"lib/x{i}", "target": "libEGL.so.1.1.0", "resolvedPath": "lib/libEGL.so.1.1.0"} for i in range(12))
    with pytest.raises(ValueError, match="pure relative"):
        runner._validate_aliases({"approvedSymlinkAliases": [{**base[0], "target": "/system/libEGL.so"}, *base[1:]]}, {"libEGL.so.1.1.0"})
    aliases = [{"path": "lib/a", "target": "b", "resolvedPath": "lib/a"}, {"path": "lib/b", "target": "a", "resolvedPath": "lib/a"}]
    aliases.extend({"path": f"lib/x{i}", "target": "a", "resolvedPath": "lib/a"} for i in range(11))
    with pytest.raises(ValueError, match="loops"):
        runner._validate_aliases({"approvedSymlinkAliases": aliases}, {"lib/final"})


def test_vendor_json_is_exact_and_not_an_outside_path() -> None:
    assert len(runner.EXPECTED_VENDOR_JSON) == 105
    assert b"libEGL_mesa.so.0" in runner.EXPECTED_VENDOR_JSON
    with pytest.raises(ValueError, match="unsafe"):
        runner._safe_relative("share/../outside.json")


def test_native_artifact_placeholder_fails_closed(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(runner, "NATIVE_ARTIFACT_BYTES", 0)
    monkeypatch.setattr(runner, "NATIVE_ARTIFACT_SHA256", "PARENT_PLACEHOLDER")
    with pytest.raises(ValueError, match="placeholder"):
        runner.probe_file()


def test_native_artifact_fixture_uses_host_755_and_device_700(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    artifact = tmp_path / "GuestEglLoader"
    artifact.write_bytes(b"fixed-native-fixture")
    artifact.chmod(0o755)
    monkeypatch.setattr(runner, "NATIVE_ARTIFACT", artifact)
    monkeypatch.setattr(runner, "NATIVE_ARTIFACT_BYTES", artifact.stat().st_size)
    monkeypatch.setattr(runner, "NATIVE_ARTIFACT_SHA256", hashlib.sha256(artifact.read_bytes()).hexdigest())
    monkeypatch.setattr(runner.loader, "_elf64_aarch64_dyn", lambda *args, **kwargs: None)
    monkeypatch.setattr(runner, "_validate_native_dynamic", lambda data: None)
    result = runner.probe_file()
    assert result["path"] == artifact and result["mode"] == "0700"


def test_native_command_has_fixed_guest_environment_and_no_server_or_display() -> None:
    command = runner.native_command(PRIVATE, RUN)
    text = command[-1]
    assert "GuestEglLoader --run-id " + RUN in text
    assert "__EGL_VENDOR_LIBRARY_FILENAMES=" in text
    assert "GALLIUM_DRIVER=virpipe" in text and "VTEST_SOCKET_NAME=vtest.sock" in text
    assert "LD_PRELOAD" not in text and "DISPLAY" not in text
    assert "virgl_test_server" not in text and "guest/lib:/system/lib64" in text
    with pytest.raises(ValueError):
        runner.native_command(PRIVATE + "/../old", RUN)
    with pytest.raises(ValueError):
        runner.native_command(PRIVATE, "arbitrary")


def test_alias_canonical_check_uses_absolute_app_root_and_rejects_system_root() -> None:
    alias = {"path": "lib/libEGL.so", "target": "libEGL.so.1", "resolvedPath": "lib/libEGL.so.1.1.0"}
    app_root = f"/data/user/0/{runner.PACKAGE}"
    relative = "files/forever/controls/vtest-loader/" + RUN
    assert runner._alias_canonical_target(app_root, relative, alias).endswith(
        "/guest/lib/libEGL.so.1.1.0"
    )
    with pytest.raises(ValueError, match="application root"):
        runner._alias_canonical_target("/system", "files/forever/controls/vtest-loader/" + RUN, alias)


def _row(event: str, **changes: object) -> dict[str, object]:
    row: dict[str, object] = {
        "schema": runner.NATIVE_SCHEMA, "runId": RUN, "event": event, "timeNs": 1,
        "pid": 42, "uid": runner.UID, "pgid": 40, "sid": 40,
    }
    for key in runner.EVENT_FIELDS[event]:
        if key in runner.OPTIONAL_BOOL:
            row[key] = None
        elif key in runner.BOOL_FIELDS:
            row[key] = False
        elif key in runner.INT_RANGES:
            row[key] = None if key in runner.NULLABLE_INT else (0 if key == "nativeExit" else runner.INT_RANGES[key][0])
        else:
            row[key] = None
    row.update(changes)
    return row


def _pass_rows() -> list[dict[str, object]]:
    rows = [_row(event, timeNs=index + 1) for index, event in enumerate(runner.EVENTS)]
    rows[0]["status"] = "STARTED"
    rows[1].update(status="VALID", scopeValid=True, runIdValid=True, uidValid=True, cwdValid=True,
                   environmentValid=True, selectionValid=True, cwd=PRIVATE, libraryPath=PRIVATE + "/guest/lib/libEGL.so.1.1.0")
    rows[2].update(status="LOADED", loaded=True, privatePath=PRIVATE + "/guest/lib/libEGL.so.1.1.0",
                   canonicalPath=PRIVATE + "/guest/lib/libEGL.so.1.1.0")
    rows[3].update(status="EXPORTED", exportCount=10, expectedExportCount=10, allExports=True, ownerExact=True,
                   ownerPath=PRIVATE + "/guest/lib/libEGL.so.1.1.0")
    rows[4].update(status="MEASURED", queryValid=True, extensionScanValid=True, extensionScanBytes=12,
                   extensionsTruncated=True, extensions="EGL_EXT_client_extensions", eglError=0x3000,
                   hasEGL_EXT_platform_base=True, hasEGL_KHR_create_context=True, hasEGL_KHR_surfaceless_context=True)
    rows[5].update(status="MAPPED", mapsRead=True, mapsComplete=True, mapsTruncated=False, mapsBytes=1024, mapLines=8,
                   privateLoaderMapped=True, privateVendorMapped=True, privateGalliumMapped=True, privateLlvmMapped=True,
                   androidEglMapped=False, androidGlesMapped=False)
    rows[6].update(status="CLEAN", cleanupFailures=0, handleClosed=True, cleanupStatus="CLEAN")
    rows[7].update(status="GUEST_LOADER_PASS", privateLoaderVerified=True, exportsVerified=True,
                   clientExtensionsVerified=True, mappingsVerified=True, cleanupVerified=True, nativeExit=0)
    return rows


def _encoded(rows: list[dict[str, object]]) -> bytes:
    return b"".join((json.dumps(row) + "\n").encode() for row in rows)


def test_classifier_accepts_only_complete_private_loader_evidence_and_rejects_broader_claims() -> None:
    raw = _encoded(_pass_rows())
    assert runner.validate_output(raw, RUN, 0, True)[0] == "GUEST_LOADER_PASS"
    for index, key, value in [(1, "libraryPath", PRIVATE + "/guest/lib:/system/lib64"),
                              (2, "canonicalPath", "/system/lib64/libEGL.so"), (5, "mesaVendorMapped", False),
                              (7, "virpipeVerified", True), (7, "nativeExit", False), (0, "pid", True)]:
        broken = copy.deepcopy(_pass_rows())
        broken[index][key] = value
        assert runner.validate_output(_encoded(broken), RUN, 0, True)[0] == "UNKNOWN"
    failed = _pass_rows()
    failed[2].update(status="LOADER_FAILED", loadError="dlopen failed")
    failed[7].update(status="LOADER_FAILED", nativeExit=1)
    assert runner.validate_output(_encoded(failed), RUN, 1, True)[0] == "LOADER_FAILED"


def test_classifier_rejects_truncation_missing_rows_duplicate_fields_and_bad_identity() -> None:
    raw = _encoded(_pass_rows())
    assert runner.validate_output(b"", RUN, 0, True)[0] == "UNKNOWN"
    assert runner.validate_output(raw[:-1], RUN, 0, True)[0] == "UNKNOWN"
    assert runner.validate_output(raw, RUN, 0, False)[0] == "UNKNOWN"
    assert runner.validate_output(raw + raw, RUN, 0, True)[0] == "UNKNOWN"
    altered = _pass_rows()
    altered[3]["uid"] = True
    assert runner.validate_output(_encoded(altered), RUN, 0, True)[0] == "UNKNOWN"


def test_extension_observation_must_be_present_even_for_empty_extensions() -> None:
    for key in ("extensions", "hasEGL_EXT_platform_base", "hasEGL_KHR_create_context", "hasEGL_KHR_surfaceless_context"):
        broken = _pass_rows()
        broken[4][key] = None
        assert runner.validate_output(_encoded(broken), RUN, 0, True)[0] == "UNKNOWN"
    empty = _pass_rows()
    empty[4].update(extensions="", extensionScanBytes=0, extensionsTruncated=False,
                    hasEGL_EXT_platform_base=False, hasEGL_KHR_create_context=False, hasEGL_KHR_surfaceless_context=False)
    assert runner.validate_output(_encoded(empty), RUN, 0, True)[0] == "GUEST_LOADER_PASS"


def test_canonical_app_root_accepts_android_alias_only(monkeypatch: pytest.MonkeyPatch) -> None:
    canonical = f"/data/user/0/{runner.PACKAGE}"
    monkeypatch.setattr(runner.loader, "adb", lambda *args, **kwargs:
                        subprocess.CompletedProcess(args, 0, (canonical + "\n").encode(), b""))
    assert runner._canonical_app_root(f"/data/data/{runner.PACKAGE}") == canonical
    monkeypatch.setattr(runner.loader, "adb", lambda *args, **kwargs:
                        subprocess.CompletedProcess(args, 0, b"/system\n", b""))
    with pytest.raises(ValueError, match="escaped"):
        runner._canonical_app_root(canonical)
    altered = _pass_rows()
    altered[0]["schema"] = "wrong/schema"
    assert runner.validate_output(_encoded(altered), RUN, 0, True)[0] == "UNKNOWN"


def test_capture_is_durable_before_exec_and_stdin_is_closed() -> None:
    source = (DIRECTORY / "run_guest_loader_usb.py").read_text()
    assert source.index('loader._write_exclusive(out / "capture.json"') < source.index("subprocess.Popen(command, stdin=subprocess.DEVNULL")
    assert "stderr_result[\"data\"]" in source


def test_capture_write_failure_starts_no_process(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.setattr(runner.loader, "_external_parent", lambda value: tmp_path)
    monkeypatch.setattr(runner, "load_candidate", lambda: ({}, [], b"fixture"))
    monkeypatch.setattr(runner, "probe_file", lambda: {"destination": "guest/bin/GuestEglLoader", "path": tmp_path / "GuestEglLoader", "bytes": 1, "sha256": "a" * 64, "mode": "0700"})
    monkeypatch.setattr(runner.loader, "remote_status_preflight", lambda: {"shellV2": True})
    monkeypatch.setattr(runner.loader, "_package_preflight", lambda: (runner.UID, f"/data/user/0/{runner.PACKAGE}", "runas_app"))
    monkeypatch.setattr(runner, "copy_payload", lambda files, probe, run, aliases, *, app_root: (runner.PRIVATE_PREFIX + "/" + run, {"stagingClean": True}))
    monkeypatch.setattr(runner, "owned_processes", lambda timeout: [])
    calls: list[object] = []
    monkeypatch.setattr(runner.subprocess, "Popen", lambda *args, **kwargs: calls.append(args))
    monkeypatch.setattr(runner.loader, "_write_exclusive", lambda *args, **kwargs: (_ for _ in ()).throw(OSError("disk full")))
    with pytest.raises(OSError, match="disk full"):
        runner.main(["--output", str(tmp_path)])
    assert calls == []


def test_capture_serializes_native_path_before_exec(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    probe_path = tmp_path / "GuestEglLoader"
    probe_path.write_bytes(b"fixture")
    monkeypatch.setattr(runner.loader, "_external_parent", lambda value: tmp_path)
    monkeypatch.setattr(runner, "load_candidate", lambda: ({}, [], b"fixture"))
    monkeypatch.setattr(runner, "probe_file", lambda: {"destination": "guest/bin/GuestEglLoader", "path": probe_path, "bytes": 7, "sha256": "a" * 64, "mode": "0700"})
    monkeypatch.setattr(runner.loader, "remote_status_preflight", lambda: {"shellV2": True})
    monkeypatch.setattr(runner.loader, "_package_preflight", lambda: (runner.UID, f"/data/user/0/{runner.PACKAGE}", "runas_app"))
    monkeypatch.setattr(runner, "owned_processes", lambda timeout: [])
    monkeypatch.setattr(runner, "copy_payload", lambda files, probe, run, aliases, *, app_root: (runner.PRIVATE_PREFIX + "/" + run, {"stagingClean": True}))
    monkeypatch.setattr(runner.subprocess, "Popen", lambda *args, **kwargs: (_ for _ in ()).throw(OSError("fixture exec")))
    assert runner.main(["--output", str(tmp_path)]) == 1
    capture = next(tmp_path.glob("native-GUEST-*/capture.json"))
    native = json.loads(capture.read_text())["nativeArtifact"]
    assert native["path"] == str(probe_path)


def test_cleanup_adb_errors_still_reap_held_host_handle(monkeypatch: pytest.MonkeyPatch) -> None:
    class Process:
        returncode = None
        terminated = False
        def poll(self): return self.returncode
        def terminate(self): self.terminated = True; self.returncode = -15
        def wait(self, timeout): return self.returncode
    process = Process()
    def failure(*args, **kwargs): raise subprocess.TimeoutExpired("adb fixture", 1)
    monkeypatch.setattr(runner, "owned_processes", failure)
    monkeypatch.setattr(runner, "force_stop", failure)
    cleaned = runner.cleanup_child(process, [], True)
    assert process.terminated
    assert cleaned["remainingOwnProcesses"] == [{"state": "UNKNOWN"}]
    assert cleaned["forcedCleanup"]["status"] == "UNKNOWN"


def test_evidence_write_failure_is_unknown_and_attempted_after_cleanup_error(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[str] = []
    def fail(path: Path, data: bytes) -> None:
        calls.append(path.name)
        raise OSError("evidence disk full")
    monkeypatch.setattr(runner.loader, "_write_exclusive", fail)
    assert runner._write_evidence(tmp_path, "stdout.txt", b"raw") == "stdout.txt: evidence disk full"
    assert calls == ["stdout.txt"]
