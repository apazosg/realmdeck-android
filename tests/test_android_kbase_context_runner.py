"""Offline contract tests for the own-context kbase HOST runner."""

from __future__ import annotations

import importlib.util
import io
import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).parents[1]
PATH = ROOT / "scripts/diagnostics/android-panvk/run_context_usb.py"
SPEC = importlib.util.spec_from_file_location("kbase_context_runner", PATH)
assert SPEC and SPEC.loader
runner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)

RUN = "PVK-0123456789abcdef0123456789abcdef"
PRIVATE = "/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-kernel/" + RUN


def _base(status: str = "PROPERTIES_OBSERVED") -> dict:
    return {
        "schema": runner.NATIVE_SCHEMA, "runId": RUN, "pid": 101, "uid": 10330, "pgid": 101, "sid": 101,
        "cwd": PRIVATE, "time": {"startedNs": 10, "finishedNs": 20, "durationNs": 10}, "status": status,
        "error": {"stage": "none", "errno": None, "ret": None},
        "device": {"path": "/dev/mali0", "major": 10, "minor": 113, "identityBefore": True, "identityAfter": True},
        "version": {"called": True, "ioctl": "0xc0048034", "ret": 0, "errno": 0, "major": 1, "minor": 18},
        "context": {"called": True, "ioctl": "0x40048001", "createFlags": 0, "ret": 0, "errno": 0},
        "properties": {"probeCalled": True, "probeRet": 8, "probeErrno": 0, "fillCalled": True, "fillRet": 8,
                       "fillErrno": 0, "returnedBytes": 8, "gpuIDKnown": True, "gpuIDhex": "0xa8670000",
                       "textureflags": {}, "valid": True},
        "flagsInited": True, "gpuWorkloadVerified": False, "driverExecution": False,
        "deviceVerified": False, "hardwareVerified": False,
        "cleanup": {"closed": True, "closeRet": 0, "closeErrno": 0}, "nativeExit": 0,
    }


def _raw(document: dict) -> bytes:
    return (json.dumps(document, separators=(",", ":")) + "\n").encode()


def _empty_properties() -> dict:
    return {"probeCalled": False, "probeRet": None, "probeErrno": None,
            "fillCalled": False, "fillRet": None, "fillErrno": None,
            "returnedBytes": 0, "gpuIDKnown": False, "textureflags": {}, "valid": False}


def test_no_version_cannot_claim_properties_success() -> None:
    document = _base()
    document["version"].update(called=False, ret=None, errno=None, major=0, minor=0)
    document["context"].update(called=False, ret=None, errno=None)
    document["properties"] = _empty_properties()
    document["flagsInited"] = False
    with pytest.raises(ValueError):
        runner.parse_native(_raw(document), RUN, PRIVATE)
    document.update(status="UNKNOWN", nativeExit=1)
    document["error"] = {"stage": "scope", "errno": None, "ret": None}
    assert runner.parse_native(_raw(document), RUN, PRIVATE)["status"] == "UNKNOWN"
    document["error"]["stage"] = "properties_probe"
    with pytest.raises(ValueError):
        runner.parse_native(_raw(document), RUN, PRIVATE)


@pytest.mark.parametrize("value", [None, -1, 1])
def test_successful_version_requires_zero_errno(value: int | None) -> None:
    document = _base()
    document["version"]["errno"] = value
    with pytest.raises(ValueError):
        runner.parse_native(_raw(document), RUN, PRIVATE)


def test_failed_size_probe_stops_before_any_fill() -> None:
    document = _base("PROPERTIES_BLOCKED")
    document["nativeExit"] = 1
    document["error"] = {"stage": "properties_probe", "errno": -1, "ret": -1}
    document["properties"] = _empty_properties()
    document["properties"].update(probeCalled=True, probeRet=-1, probeErrno=-1)
    assert runner.parse_native(_raw(document), RUN, PRIVATE)["status"] == "PROPERTIES_BLOCKED"
    for fill_ret, fill_errno in [(8, 0), (-1, -1)]:
        document["properties"].update(fillCalled=True, fillRet=fill_ret, fillErrno=fill_errno)
        with pytest.raises(ValueError):
            runner.parse_native(_raw(document), RUN, PRIVATE)


def test_deadline_after_version_does_not_invent_context_call() -> None:
    document = _base("UNKNOWN")
    document["context"].update(called=False, ret=None, errno=None)
    document["properties"] = _empty_properties()
    document["flagsInited"] = False
    document["nativeExit"] = 1
    document["error"] = {"stage": "deadline_after_version", "errno": None, "ret": 0}
    assert not runner.parse_native(_raw(document), RUN, PRIVATE)["context"]["called"]


@pytest.mark.parametrize("replacement", ["other/../", "../", "files/forever/controls/../../"])
def test_shell_command_requires_exact_owned_root(replacement: str) -> None:
    invalid = PRIVATE.replace("/files/forever/controls/", "/" + replacement + "files/forever/controls/")
    with pytest.raises(ValueError):
        runner.shell_command(invalid, RUN)
    assert runner.shell_command(PRIVATE.replace("/data/user/0/", "/data/data/"), RUN)


def test_parser_accepts_context_measurement_and_rejects_non_boolean_integer() -> None:
    document = _base()
    assert runner.parse_native(_raw(document), RUN, PRIVATE)["status"] == "PROPERTIES_OBSERVED"
    document["uid"] = True
    with pytest.raises(ValueError):
        runner.parse_native(_raw(document), RUN, PRIVATE)


@pytest.mark.parametrize("version", [(1, 21), (2, 0)])
def test_version_mismatch_requires_no_context_or_properties(version: tuple[int, int]) -> None:
    document = _base("VERSION_MISMATCH")
    document["version"].update(minor=version[1], major=version[0])
    document["context"] = {"called": False, "ioctl": "0x40048001", "createFlags": 0, "ret": None, "errno": None}
    document["flagsInited"] = False
    document["properties"] = {"probeCalled": False, "probeRet": None, "probeErrno": None, "fillCalled": False, "fillRet": None,
                                "fillErrno": None, "returnedBytes": 0, "gpuIDKnown": False, "textureflags": {}, "valid": False}
    document["nativeExit"] = 1
    assert runner.parse_native(_raw(document), RUN, PRIVATE)["status"] == "VERSION_MISMATCH"


def test_flags_blocked_is_controlled_failure_and_not_gpu_readiness() -> None:
    document = _base("FLAGS_BLOCKED")
    document["context"].update(ret=-1, errno=-1)
    document["error"] = {"stage": "set_flags", "errno": -1, "ret": -1}
    document["flagsInited"] = False
    document["properties"] = {"probeCalled": False, "probeRet": None, "probeErrno": None, "fillCalled": False, "fillRet": None,
                                "fillErrno": None, "returnedBytes": 0, "gpuIDKnown": False, "textureflags": {}, "valid": False}
    document["nativeExit"] = 1
    parsed = runner.parse_native(_raw(document), RUN, PRIVATE)
    assert parsed["status"] == "FLAGS_BLOCKED"
    assert all(parsed[name] is False for name in ("gpuWorkloadVerified", "driverExecution", "deviceVerified", "hardwareVerified"))


def test_shell_command_is_fixed_and_has_no_loader_or_arbitrary_path() -> None:
    command = runner.shell_command(PRIVATE, RUN)
    assert command[4:7] == ["-T", "run-as", runner.PACKAGE]
    script = command[-1]
    assert "KbaseContextMetadata --run-id" in script
    assert "/system/bin/linker64" in script and "LD_PRELOAD" not in script
    with pytest.raises(ValueError):
        runner.shell_command(PRIVATE.replace(RUN, RUN.upper()), RUN)


def test_output_shape_and_duplicate_json_fail_closed() -> None:
    document = _base()
    duplicate = _raw(document).replace(b'"status":"PROPERTIES_OBSERVED"', b'"status":"PROPERTIES_OBSERVED","status":"PROPERTIES_OBSERVED"')
    with pytest.raises(ValueError, match="duplicate"):
        runner.parse_native(duplicate, RUN, PRIVATE)
    document["pid"] = True
    with pytest.raises(ValueError):
        runner.parse_native(_raw(document), RUN, PRIVATE)


def test_capture_is_written_before_popen_and_stdin_is_devnull(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.setattr(runner, "_external_parent", lambda value: tmp_path)
    monkeypatch.setattr(runner, "load_native", lambda: {"path": Path("native"), "source": {}, "sha256": "a" * 64,
                                                          "bytes": 100, "manifestSha256": "b" * 64})
    monkeypatch.setattr(runner.loader, "remote_status_preflight", lambda: {"shellV2": True, "sentinelExitCode": 37})
    monkeypatch.setattr(runner.loader, "_package_preflight", lambda: (10330, f"/data/user/0/{runner.PACKAGE}", "ctx"))
    monkeypatch.setattr(runner.loader, "own_processes", lambda uid: [])
    monkeypatch.setattr(runner.uuid, "uuid4", lambda: type("Fixed", (), {"hex": RUN[4:]})())
    monkeypatch.setattr(runner, "copy_native", lambda native, run: (runner._private_relative(run), {"stagingClean": True}))
    observed: list[dict] = []

    class Process:
        pid = 101
        returncode = 0
        stdout = io.BytesIO(_raw(_base()))
        stderr = io.BytesIO()
        def wait(self, timeout=None): return 0
        def poll(self): return 0

    def start(command, **kwargs):
        capture = tmp_path / ("native-" + RUN) / "capture.json"
        observed.append(json.loads(capture.read_text()))
        assert kwargs["stdin"] is runner.subprocess.DEVNULL
        return Process()

    monkeypatch.setattr(runner.subprocess, "Popen", start)
    assert runner.main(["--output", str(tmp_path)]) == 0
    assert observed[0]["contextIoctl"] == "0x40048001"
    summary = json.loads((tmp_path / ("native-" + RUN) / "summary.json").read_text())
    assert summary["metadataObserved"] is True and summary["gpuWorkloadVerified"] is False


def test_mkdir_timeout_removes_only_empty_staging_and_never_uploads(monkeypatch: pytest.MonkeyPatch) -> None:
    calls = []

    def adb(*args, **kwargs):
        calls.append((args, kwargs))
        if args[:2] == ("shell", "mkdir"):
            raise runner.subprocess.TimeoutExpired(args, 15)
        assert args[0] == "shell" and args[1] in {"test", "rmdir"}
        return runner.subprocess.CompletedProcess(args, 0, b"", b"")

    monkeypatch.setattr(runner.loader, "adb", adb)
    with pytest.raises(runner.subprocess.TimeoutExpired):
        runner.copy_native({"path": Path("native")}, RUN)
    assert [args[1] for args, _ in calls] == ["test", "mkdir", "rmdir"]
    assert calls[-1][1] == {"timeout": 3}
