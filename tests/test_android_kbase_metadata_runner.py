"""Offline contract tests for the fixed kbase metadata HOST runner."""

from __future__ import annotations

import importlib.util
import hashlib
import io
import json
import os
import struct
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
PATH = ROOT / "scripts/diagnostics/android-panvk/run_kernel_usb.py"
SPEC = importlib.util.spec_from_file_location("kbase_metadata_runner", PATH)
assert SPEC and SPEC.loader
runner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)


RUN = "PVK-0123456789abcdef0123456789abcdef"
ROOT_PRIVATE = "/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-kernel/" + RUN


def _base(status: str = "PROPERTIES_OBSERVED") -> dict:
    return {
        "schema": runner.NATIVE_SCHEMA, "runId": RUN, "pid": 101, "uid": 10330, "pgid": 101, "sid": 101,
        "cwd": ROOT_PRIVATE, "time": {"startedNs": 10, "finishedNs": 20, "durationNs": 10}, "status": status,
        "error": {"stage": "none", "errno": None, "ret": None},
        "device": {"path": "/dev/mali0", "major": 10, "minor": 113, "identityBefore": True, "identityAfter": True},
        "version": {"called": True, "ioctl": "0xc0048034", "ret": 0, "errno": 0, "major": 1, "minor": 24},
        "properties": {"probeCalled": True, "probeRet": 16, "probeErrno": 0, "fillCalled": True, "fillRet": 16,
                       "fillErrno": 0, "returnedBytes": 16, "gpuIDKnown": True, "gpuIDhex": "0xa8670000",
                       "textureflags": {"52": "0x01020304"}, "valid": True},
        **{name: False for name in runner.FALSE_FLAGS},
        "cleanup": {"closed": True, "closeRet": 0, "closeErrno": 0}, "nativeExit": 0,
    }


def _raw(document: dict) -> bytes:
    return (json.dumps(document, separators=(",", ":")) + "\n").encode()


@pytest.fixture
def complete_document() -> dict:
    return _base()


def test_parser_accepts_complete_measurement_and_rejects_duplicate_json(complete_document: dict) -> None:
    assert runner.parse_native(_raw(complete_document), RUN, ROOT_PRIVATE)["status"] == "PROPERTIES_OBSERVED"
    duplicate = _raw(complete_document).replace(b'"status":"PROPERTIES_OBSERVED"', b'"status":"PROPERTIES_OBSERVED","status":"PROPERTIES_OBSERVED"')
    with pytest.raises(ValueError, match="duplicate"):
        runner.parse_native(duplicate, RUN, ROOT_PRIVATE)


@pytest.mark.parametrize("status", ["OPEN_FAILED", "VERSION_FAILED"])
def test_controlled_failures_require_nonzero_exit_and_real_negative_version_call(status: str) -> None:
    document = _base(status)
    document["nativeExit"] = 1
    document["device"]["identityBefore"] = False
    document["device"]["identityAfter"] = False
    document["cleanup"] = {"closed": False, "closeRet": 0, "closeErrno": 0}
    if status == "VERSION_FAILED":
        document["version"].update(ret=-1, errno=-1)
        document["error"] = {"stage": "version", "errno": -1, "ret": -1}
        document["properties"] = {"probeCalled": False, "probeRet": None, "probeErrno": None, "fillCalled": False, "fillRet": None,
                                   "fillErrno": None, "returnedBytes": 0, "gpuIDKnown": False, "textureflags": {}, "valid": False}
    else:
        document["version"] = {"called": False, "ioctl": "0xc0048034", "ret": None, "errno": None, "major": 0, "minor": 0}
        document["error"] = {"stage": "open", "errno": -13, "ret": -1}
        document["properties"] = {"probeCalled": False, "probeRet": None, "probeErrno": None, "fillCalled": False, "fillRet": None,
                                   "fillErrno": None, "returnedBytes": 0, "gpuIDKnown": False, "textureflags": {}, "valid": False}
    assert runner.parse_native(_raw(document), RUN, ROOT_PRIVATE)["status"] == status
    document["nativeExit"] = 0
    with pytest.raises(ValueError):
        runner.parse_native(_raw(document), RUN, ROOT_PRIVATE)


@pytest.mark.parametrize("change", [
    ("runId", "PVK-wrong"), ("cwd", "/data/user/0/com.wowforever.arm64probe/files/forever/pvk"),
    ("uid", True), ("version", {"called": True, "ioctl": "0x40108003", "ret": 0, "errno": 0, "major": 1, "minor": 24}),
])
def test_identity_ioctl_and_exact_types_fail_closed(change: tuple[str, object]) -> None:
    document = _base()
    document[change[0]] = change[1]
    with pytest.raises(ValueError):
        runner.parse_native(_raw(document), RUN, ROOT_PRIVATE)


def test_properties_blocked_must_be_a_negative_ioctl_without_set_flags() -> None:
    document = _base("PROPERTIES_BLOCKED")
    document["nativeExit"] = 1
    document["properties"] = {"probeCalled": True, "probeRet": -1, "probeErrno": -1, "fillCalled": False, "fillRet": None,
                               "fillErrno": None, "returnedBytes": 0, "gpuIDKnown": False, "textureflags": {}, "valid": False}
    document["error"] = {"stage": "properties_probe", "errno": -1, "ret": -1}
    assert runner.parse_native(_raw(document), RUN, ROOT_PRIVATE)["status"] == "PROPERTIES_BLOCKED"
    document["nativeExit"] = 0
    with pytest.raises(ValueError):
        runner.parse_native(_raw(document), RUN, ROOT_PRIVATE)
    document["nativeExit"] = 1
    document["properties"]["probeErrno"] = 1
    with pytest.raises(ValueError):
        runner.parse_native(_raw(document), RUN, ROOT_PRIVATE)


def test_version_observed_has_no_properties_and_all_broader_flags_false() -> None:
    document = _base("VERSION_OBSERVED")
    document["version"]["major"] = 2
    document["properties"] = {"probeCalled": False, "probeRet": None, "probeErrno": None, "fillCalled": False, "fillRet": None,
                               "fillErrno": None, "returnedBytes": 0, "gpuIDKnown": False, "textureflags": {}, "valid": False}
    assert runner.parse_native(_raw(document), RUN, ROOT_PRIVATE)["status"] == "VERSION_OBSERVED"
    document["hardwareVerified"] = True
    with pytest.raises(ValueError):
        runner.parse_native(_raw(document), RUN, ROOT_PRIVATE)


def test_shell_command_is_single_quoted_inner_fixed_environment() -> None:
    command = runner.shell_command(ROOT_PRIVATE, RUN)
    assert command[4] == "-T" and command[6] == runner.PACKAGE
    script = command[-1]
    assert "cd " in script and "/system/bin/env -i" in script and "/system/bin/linker64" in script
    assert "PATH=/system/bin" in script and "ANDROID_ROOT=/system" in script and "ANDROID_DATA=/data" in script
    assert "LD_LIBRARY_PATH=" + ROOT_PRIVATE + ":/system/lib64" in script
    assert "KbaseMetadata --run-id" in script and "LD_PRELOAD" not in script and "SET_FLAGS" not in script
    import shlex
    assert len(shlex.split(script)) == 1
    with pytest.raises(ValueError):
        runner.shell_command("/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-kernel/" + RUN.upper(), RUN)


def test_absent_pin_fails_closed(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(runner, "NATIVE_ARTIFACT_SHA256", "")
    with pytest.raises(ValueError, match="pin"):
        runner.load_native()


def test_final_source_authority_and_actual_helper_api() -> None:
    for name, digest in runner.SOURCE_PINS.items():
        assert hashlib.sha256((runner.SOURCE_ROOT / name).read_bytes()).hexdigest() == digest
    assert callable(runner.loader._package_preflight)
    assert callable(runner.loader.remote_status_preflight)
    assert callable(runner.loader.own_processes)


def test_exact_android_canonical_aliases_only() -> None:
    document = _base()
    document["cwd"] = ROOT_PRIVATE.replace("/data/user/0/", "/data/data/", 1)
    assert runner.parse_native(_raw(document), RUN, ROOT_PRIVATE)["status"] == "PROPERTIES_OBSERVED"
    document["cwd"] = document["cwd"].replace(RUN, RUN + "-other")
    with pytest.raises(ValueError):
        runner.parse_native(_raw(document), RUN, ROOT_PRIVATE)


def _mock_main_preflight(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.setattr(runner, "_external_parent", lambda value: tmp_path)
    monkeypatch.setattr(runner, "load_native", lambda: {
        "path": Path("inert-native"), "source": {}, "sha256": runner.NATIVE_ARTIFACT_SHA256,
        "bytes": runner.NATIVE_ARTIFACT_BYTES,
    })
    monkeypatch.setattr(runner.loader, "remote_status_preflight", lambda: {"shellV2": True})
    monkeypatch.setattr(runner.loader, "_package_preflight", lambda: (10330, f"/data/user/0/{runner.PACKAGE}", "inert-domain"))
    monkeypatch.setattr(runner.loader, "own_processes", lambda uid: [])
    monkeypatch.setattr(runner.uuid, "uuid4", lambda: type("InertUUID", (), {"hex": RUN[4:]})())
    monkeypatch.setattr(runner, "copy_native", lambda native, run: (runner._private_relative(run), {"stagingClean": True}))


def test_capture_is_durable_before_exec_and_stdin_is_closed(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    _mock_main_preflight(monkeypatch, tmp_path)
    observed = []

    class InertProcess:
        pid = 101
        returncode = 0
        stdout = io.BytesIO(_raw(_base()))
        stderr = io.BytesIO()

        def wait(self, timeout=None):
            return 0

        def poll(self):
            return 0

    def start(command, **kwargs):
        capture = tmp_path / ("native-" + RUN) / "capture.json"
        assert json.loads(capture.read_text())["runId"] == RUN
        assert kwargs["stdin"] == runner.subprocess.DEVNULL
        observed.append(command)
        return InertProcess()

    monkeypatch.setattr(runner.subprocess, "Popen", start)
    assert runner.main(["--output", str(tmp_path)]) == 0
    assert len(observed) == 1
    summary = json.loads((tmp_path / ("native-" + RUN) / "summary.json").read_text())
    assert summary["status"] == "PROPERTIES_OBSERVED"
    assert summary["forceStop"] is None and summary["wowVerified"] is False


def test_capture_io_failure_starts_no_child(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    _mock_main_preflight(monkeypatch, tmp_path)
    def fail_write(path, data):
        raise OSError("inert full filesystem")
    monkeypatch.setattr(runner, "_write_exclusive", fail_write)
    monkeypatch.setattr(runner.subprocess, "Popen", lambda *args, **kwargs: pytest.fail("child must not start"))
    with pytest.raises(OSError, match="filesystem"):
        runner.main(["--output", str(tmp_path)])


def test_elf_gate_requires_exact_shape() -> None:
    with pytest.raises(ValueError):
        runner._validate_elf(b"\x7fELF" + b"\x02\x01" + b"\0" * 128)


def test_output_caps_and_non_boolean_integer_rejection() -> None:
    document = _base()
    document["pid"] = True
    with pytest.raises(ValueError):
        runner.parse_native(_raw(document), RUN, ROOT_PRIVATE)
    with pytest.raises(ValueError):
        runner.parse_native(b"{}\n" * 2, RUN, ROOT_PRIVATE)
    assert runner.NATIVE_STDOUT_LIMIT == 16 * 1024 and runner.NATIVE_STDERR_LIMIT == 64 * 1024
