"""Offline contract tests for the fixed PanVK instance HOST runner."""

from __future__ import annotations

import importlib.util
import io
import json
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
PATH = ROOT / "scripts/diagnostics/android-panvk/run_instance_usb.py"
SPEC = importlib.util.spec_from_file_location("panvk_instance_runner", PATH)
assert SPEC and SPEC.loader
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)

RUN = "PVK-0123456789abcdef0123456789abcdef"
PRIVATE = f"/data/user/0/{runner.PACKAGE}/{runner._private_relative(RUN)}"
ICD = f"{PRIVATE}/libvulkan_panfrost.so"


def _identity(inode: int = 22) -> dict[str, object]:
    return {"regular": True, "ownerUid": runner.UID, "device": 11, "inode": inode, "mode": 0o600, "size": runner.ICD_BYTES}


def _features() -> dict[str, object]:
    return {name: False for name in ("fillModeNonSolid", "shaderClipDistance", "textureCompressionBC", "geometryShader", "tessellationShader", "sampleRateShading", "samplerAnisotropy", "robustBufferAccess")}


def _features2() -> dict[str, object]:
    return {"queried": True, "core": _features(), "vulkan11ShaderDrawParameters": False,
            "vulkan12": {"descriptorIndexing": False, "bufferDeviceAddress": False},
            "vulkan13": {"shaderDemoteToHelperInvocation": False, "synchronization2": False, "dynamicRendering": False},
            "transformFeedback": {"transformFeedback": None, "geometryStreams": None},
            "robustness2": {"robustBufferAccess2": None, "nullDescriptor": None}, "valid": True}


def _native(status: str = "PHYSICAL_METADATA_OBSERVED", **changes: object) -> dict[str, object]:
    device = {"metadataStatus": "OBSERVED", "vendorId": 0x13B5, "deviceId": 0xA867, "apiVersion": (1 << 22) | (3 << 12), "driverVersion": 1, "deviceName": "fake-panvk", "propertiesValid": True, "extensionsValid": True, "extensionCount": 0, "extensionReportedCount": 0, "extensionReturnedCount": 0, "extensionRetainedCount": 0, "extensionCapacity": 256, "extensionQueryResult": 0, "extensionFillResult": 0, "extensionsComplete": True, "extensions": [], "features": _features(), "features2": _features2(), "error": ""}
    value: dict[str, object] = {
        "schema": runner.NATIVE_SCHEMA, "version": 1, "runId": RUN, "pid": 101, "uid": runner.UID, "pgid": 101, "sid": 101,
        "monotonicStartNs": 10, "monotonicEndNs": 20, "cwd": PRIVATE, "scopeValid": True, "icdPath": ICD, "icdBytes": runner.ICD_BYTES, "icdSha256": runner.ICD_SHA256,
        "icdIdentityBefore": _identity(), "icdIdentityAfter": _identity(), "icdIdentityStable": True,
        "load": {"attempted": True, "loaded": True, "dlerror": ""},
        "exports": [{"name": name, "found": True, "owner": ICD, "ownerExact": True, "error": ""} for name in runner.EXPORT_NAMES],
        "interface": {"input": 7, "result": 0, "output": 7, "supported": True},
        "functions": [{"name": name, "resolved": True, "owner": ICD, "ownerExact": True, "error": ""} for name in runner.FUNCTION_NAMES],
        "api": {"enumerateInstanceVersionFound": True, "ownerExact": True, "legacy10": False, "result": 0, "reported": (1 << 22) | (3 << 12), "requested": (1 << 22) | (3 << 12)},
        "instance": {"createAttempted": True, "created": True, "result": 0, "layerCount": 0, "extensionCount": 0, "allocatorNull": True},
        "physicalDevices": {"countQueryResult": 0, "fillResult": 0, "reportedCount": 1, "returnedCount": 1, "retainedCount": 1, "capacity": 8, "complete": True, "devices": [device]},
        "cleanup": {"destroyAttempted": True, "destroyOk": True, "dlcloseAttempted": True, "dlcloseOk": True, "complete": True},
        "icdCodeLoaded": True, "constructorsMayHaveExecuted": True, "vulkanFunctionsInvoked": True, "apiComplete": False, "gpuWorkload": False,
        "triangle": False, "presentation": False, "d3d": False, "wow": False, "status": status, "failureStage": "none", "failureDetail": "", "nativeExit": 0,
    }
    value.update(changes)
    return value


def _raw(value: dict[str, object]) -> bytes:
    return (json.dumps(value, separators=(",", ":")) + "\n").encode()


def test_final_artifact_manifest_and_pins_load_without_device() -> None:
    pytest.skip(
        "closed beta16 gate: the shared helper PanvkPrivateLoader.c advanced to beta.17 "
        "(cc8ea0c3...) and the frozen instance manifest binds the older helper, so "
        "load_native() now correctly fails closed. Superseded 2026-10-07."
    )
    artifact = runner.load_native()
    assert artifact["bytes"] == 37400
    assert artifact["sha256"] == "694c925e61d935b95f6d92fa8f314ad8a5d151d9cd2fb10702fa513012d4bfc1"
    assert artifact["manifestSha256"] == "f7bcb9a9eb8af7405de8d0f393fdf7144fa2923db8617b3340e0411f99175e3e"
    assert artifact["icd"]["sha256"] == runner.ICD_SHA256


def test_parser_validates_full_json_before_device_metadata_and_accepts_observation() -> None:
    parsed = runner.parse_native(_raw(_native()), RUN, PRIVATE, adb_exit=0)
    assert parsed["status"] == "PHYSICAL_METADATA_OBSERVED"
    assert parsed["vulkanFunctionsInvoked"] is True
    assert all(parsed[name] is False for name in ("apiComplete", "gpuWorkload", "triangle", "presentation", "d3d", "wow"))
    malformed = _raw(_native()).replace(b'"status":"PHYSICAL_METADATA_OBSERVED"', b'"status":"PHYSICAL_METADATA_OBSERVED",')
    with pytest.raises((ValueError, json.JSONDecodeError)):
        runner.parse_native(malformed, RUN, PRIVATE)
    duplicate = _raw(_native()).replace(b'"status":"PHYSICAL_METADATA_OBSERVED"', b'"status":"PHYSICAL_METADATA_OBSERVED","status":"PHYSICAL_METADATA_OBSERVED"')
    with pytest.raises(ValueError, match="duplicate"):
        runner.parse_native(duplicate, RUN, PRIVATE)


@pytest.mark.parametrize("change", [
    lambda d: d["exports"].__setitem__(0, {**d["exports"][0], "owner": "/other", "ownerExact": False, "error": "owner mismatch"}),
    lambda d: d["functions"].__setitem__(2, {**d["functions"][2], "resolved": False, "ownerExact": False}),
    lambda d: d["api"].update(reported=True),
    lambda d: d["physicalDevices"].update(reportedCount=2),
    lambda d: d["physicalDevices"].update(fillResult=5),
    lambda d: d["physicalDevices"]["devices"][0]["features"].update(fillModeNonSolid=2),
])
def test_parser_rejects_owner_function_api_bool_and_count_witness_failures(change) -> None:
    document = _native()
    change(document)
    with pytest.raises((ValueError, TypeError)):
        runner.parse_native(_raw(document), RUN, PRIVATE, adb_exit=0)


def test_parser_requires_full_success_witnesses_and_accepts_structured_partial_errors() -> None:
    contradictions = [
        lambda d: d["interface"].update(supported=False),
        lambda d: d["instance"].update(created=False),
        lambda d: d["api"].update(result=-3),
        lambda d: d["physicalDevices"]["devices"][0]["features2"].update(valid=False),
    ]
    for change in contradictions:
        document = _native()
        change(document)
        with pytest.raises(ValueError):
            runner.parse_native(_raw(document), RUN, PRIVATE, adb_exit=0)

    partial = _native("UNKNOWN", nativeExit=1)
    partial["physicalDevices"].update(reportedCount=2, returnedCount=1, retainedCount=1, complete=False)
    assert runner.parse_native(_raw(partial), RUN, PRIVATE, adb_exit=1)["physicalDevices"]["retainedCount"] == 1

    extension_overflow = _native("UNKNOWN", nativeExit=1)
    device = extension_overflow["physicalDevices"]["devices"][0]
    device.update(metadataStatus="UNKNOWN", extensionReportedCount=257, extensionReturnedCount=0,
                  extensionRetainedCount=0, extensionCount=0, extensionFillResult=-3,
                  extensionsComplete=False, features2={**_features2(), "queried": False, "valid": False,
                  "vulkan11ShaderDrawParameters": None,
                  "vulkan12": {"descriptorIndexing": None, "bufferDeviceAddress": None},
                  "vulkan13": {"shaderDemoteToHelperInvocation": None, "synchronization2": None, "dynamicRendering": None}})
    assert runner.parse_native(_raw(extension_overflow), RUN, PRIVATE, adb_exit=1)["status"] == "UNKNOWN"

    for api_version in (0, (1 << 29) | (1 << 22)):
        invalid_api = _native("UNKNOWN", nativeExit=1)
        invalid_api["physicalDevices"]["devices"][0].update(metadataStatus="UNKNOWN", apiVersion=api_version,
            features2={**_features2(), "queried": False, "valid": False,
            "vulkan11ShaderDrawParameters": None,
            "vulkan12": {"descriptorIndexing": None, "bufferDeviceAddress": None},
            "vulkan13": {"shaderDemoteToHelperInvocation": None, "synchronization2": None, "dynamicRendering": None}})
        assert runner.parse_native(_raw(invalid_api), RUN, PRIVATE, adb_exit=1)["status"] == "UNKNOWN"

    create_error = _native("VK_ERROR", nativeExit=1)
    create_error["instance"].update(created=False, result=-3)
    create_error["physicalDevices"].update(countQueryResult=-3, fillResult=-3, reportedCount=0, returnedCount=0, retainedCount=0, complete=False, devices=[])
    create_error["cleanup"].update(destroyAttempted=False, destroyOk=False)
    assert runner.parse_native(_raw(create_error), RUN, PRIVATE, adb_exit=1)["status"] == "VK_ERROR"


def test_success_rejects_inconsistent_extension_results_and_counts() -> None:
    for key, value in (("extensionQueryResult", -3), ("extensionFillResult", 5),
                       ("extensionReportedCount", 1), ("extensionReturnedCount", 1)):
        document = _native()
        document["physicalDevices"]["devices"][0][key] = value
        with pytest.raises(ValueError):
            runner.parse_native(_raw(document), RUN, PRIVATE, adb_exit=0)


def test_legacy_instance_preserves_unqueryable_extension_features() -> None:
    for device_minor in (0, 3):
        document = _native()
        document["api"].update(enumerateInstanceVersionFound=False, legacy10=True,
                               reported=4194304, requested=4194304, result=0)
        device = document["physicalDevices"]["devices"][0]
        device["apiVersion"] = 4194304 | (device_minor << 12)
        device.update(extensions=[runner.VK_EXT_TRANSFORM_FEEDBACK, runner.VK_EXT_ROBUSTNESS_2],
                      extensionCount=2, extensionReportedCount=2, extensionReturnedCount=2,
                      extensionRetainedCount=2)
        f = device["features2"]
        f.update(queried=False, vulkan11ShaderDrawParameters=None)
        for key in ("vulkan12", "vulkan13", "transformFeedback", "robustness2"):
            f[key] = {name: None for name in f[key]}
        assert runner.parse_native(_raw(document), RUN, PRIVATE, adb_exit=0)["status"] == "PHYSICAL_METADATA_OBSERVED"
        f["transformFeedback"]["transformFeedback"] = True
        with pytest.raises(ValueError):
            runner.parse_native(_raw(document), RUN, PRIVATE, adb_exit=0)


def test_signed_instance_version_failure_is_retained_without_success() -> None:
    document = _native("UNKNOWN", nativeExit=1, failureStage="api", failureDetail="version query failed")
    document["api"].update(result=-3, reported=0, requested=4194304)
    document["instance"].update(createAttempted=False, created=False, result=-3)
    document["physicalDevices"].update(countQueryResult=-3, fillResult=-3, reportedCount=0,
                                      returnedCount=0, retainedCount=0, complete=False, devices=[])
    document["cleanup"].update(destroyAttempted=False, destroyOk=False)
    assert runner.parse_native(_raw(document), RUN, PRIVATE, adb_exit=1)["api"]["result"] == -3


def test_shell_command_has_fixed_identity_environment_and_no_arbitrary_inputs() -> None:
    command = runner.shell_command(PRIVATE, RUN)
    script = command[-1]
    assert command[:7] == [runner.ADB, "-s", runner.SERIAL, "shell", "-T", "run-as", runner.PACKAGE]
    assert "exec /system/bin/env -i" in script and "/system/bin/linker64" in script
    assert all(item in script for item in ("PATH=/system/bin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data", "LD_LIBRARY_PATH=/system/lib64", f"TMPDIR={PRIVATE}"))
    with pytest.raises(ValueError): runner.shell_command(PRIVATE.replace(RUN, "../" + RUN), RUN)
    with pytest.raises(ValueError): runner.shell_command("/tmp/owned", RUN)


def test_copy_uploads_exclusively_verifies_hash_modes_and_cleans_owned_stage(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[tuple[str, ...]] = []
    monkeypatch.setattr(runner.uuid, "uuid4", lambda: type("U", (), {"hex": "a"})())

    def fake_adb(*args: str, timeout: int = 15) -> subprocess.CompletedProcess[bytes]:
        calls.append(args)
        if args[:2] == ("exec-out", "run-as") and args[3] == "sha256sum":
            path = args[-1]; digest = "p" * 64 if path.endswith(runner.NATIVE_NAME) else runner.ICD_SHA256
            return subprocess.CompletedProcess(args, 0, f"{digest} {path}\n".encode(), b"")
        if args[:2] == ("exec-out", "run-as") and args[3] == "stat":
            return subprocess.CompletedProcess(args, 0, (b"700\n" if args[-1].endswith(runner.NATIVE_NAME) else b"600\n"), b"")
        if args[:3] == ("exec-out", "run-as", runner.PACKAGE) and args[3] == "/system/bin/sh":
            return subprocess.CompletedProcess(args, 0, b"PANVK_INSTANCE_UPLOAD_OK", b"")
        return subprocess.CompletedProcess(args, 0, b"", b"")

    monkeypatch.setattr(runner, "adb", fake_adb)
    relative, cleanup = runner.copy_native({"path": Path("probe"), "sha256": "p" * 64}, RUN)
    assert relative.endswith(RUN) and cleanup["stagingClean"] is True
    assert next(args for args in calls if args[:2] == ("shell", "mkdir"))[2:4] == ("-m", "755")
    script = next(args[-1] for args in calls if args[:3] == ("exec-out", "run-as", runner.PACKAGE) and args[3] == "/system/bin/sh")
    assert "mkdir -m 700" in script and "chmod 0700" in script and "chmod 0600" in script
    assert [args[1] for args in calls if args and args[0] == "shell" and args[1] in {"rm", "rmdir"}] == ["rm", "rm", "rmdir"]


def test_mkdir_timeout_attempts_only_owned_empty_stage_cleanup(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[tuple[str, ...]] = []

    def fake_adb(*args: str, **kwargs: object) -> subprocess.CompletedProcess[bytes]:
        calls.append(args)
        if args[:2] == ("shell", "mkdir"):
            raise subprocess.TimeoutExpired(args, 15)
        return subprocess.CompletedProcess(args, 0, b"", b"")

    monkeypatch.setattr(runner, "adb", fake_adb)
    with pytest.raises(subprocess.TimeoutExpired):
        runner.copy_native({"path": Path("probe"), "sha256": "p" * 64}, RUN)
    assert [args[1] for args in calls] == ["test", "mkdir", "rmdir"]
    assert calls[-1][-1].startswith(runner.STAGE_PREFIX)


@pytest.mark.parametrize("status", ["LOADER_FAILED", "UNKNOWN"])
def test_capture_is_fsynced_before_popen_and_unknown_forces_only_owned_package(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, status: str) -> None:
    monkeypatch.setattr(runner, "_external_parent", lambda value: tmp_path)
    monkeypatch.setattr(runner, "load_native", lambda: {"path": Path("probe"), "sha256": "p" * 64, "bytes": 1, "source": {}})
    monkeypatch.setattr(runner, "remote_status_preflight", lambda: {"shellV2": True})
    monkeypatch.setattr(runner, "package_preflight", lambda: (runner.UID, f"/data/user/0/{runner.PACKAGE}", "ctx"))
    monkeypatch.setattr(runner, "copy_native", lambda native, run: (runner._private_relative(run), {"stagingClean": True}))
    monkeypatch.setattr(runner, "own_processes", lambda uid: [])
    monkeypatch.setattr(runner, "adb", lambda *args, **kwargs: subprocess.CompletedProcess(args, 0, b"34\n", b""))
    monkeypatch.setattr(runner.uuid, "uuid4", lambda: type("U", (), {"hex": RUN[4:]})())
    observed: list[dict[str, object]] = []
    stopped: list[str] = []
    monkeypatch.setattr(runner, "_force_stop", lambda: (stopped.append(runner.PACKAGE) or {"returnCode": 0}))
    document = _native(status, nativeExit=1)
    if status == "UNKNOWN":
        document["physicalDevices"].update(reportedCount=2, returnedCount=1, retainedCount=1, complete=False)

    class Process:
        pid = 700; returncode = 1; stdout = io.BytesIO(_raw(document)); stderr = io.BytesIO()
        def wait(self, timeout: float | None = None) -> int: return self.returncode
        def poll(self) -> int | None: return self.returncode

    def start(command: list[str], **kwargs: object) -> Process:
        observed.append(json.loads((tmp_path / ("native-" + RUN) / "capture.json").read_text()))
        assert kwargs["stdin"] is subprocess.DEVNULL and kwargs["shell"] is False
        return Process()

    monkeypatch.setattr(runner.subprocess, "Popen", start)
    assert runner.main(["--output", str(tmp_path)]) == 1
    assert observed[0]["executionStarted"] is False
    summary = json.loads((tmp_path / ("native-" + RUN) / "summary.json").read_text())
    assert summary["status"] == status
    assert stopped == ([runner.PACKAGE] if status == "UNKNOWN" else [])
    assert (summary["forceStop"] is not None) == (status == "UNKNOWN")


def test_timeout_reaps_held_host_process_and_force_stops_exact_package(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(runner, "_external_parent", lambda value: tmp_path)
    monkeypatch.setattr(runner, "load_native", lambda: {"path": Path("probe"), "sha256": "p" * 64, "bytes": 1, "source": {}})
    monkeypatch.setattr(runner, "remote_status_preflight", lambda: {"shellV2": True})
    monkeypatch.setattr(runner, "package_preflight", lambda: (runner.UID, f"/data/user/0/{runner.PACKAGE}", "ctx"))
    monkeypatch.setattr(runner, "copy_native", lambda native, run: (runner._private_relative(run), {"stagingClean": True}))
    monkeypatch.setattr(runner, "own_processes", lambda uid: [])
    monkeypatch.setattr(runner, "_force_stop", lambda: {"action": "force-stop exact qualification package", "returnCode": None})
    monkeypatch.setattr(runner.uuid, "uuid4", lambda: type("U", (), {"hex": RUN[4:]})())

    class Slow:
        pid = 701; returncode: int | None = None; stdout = io.BytesIO(); stderr = io.BytesIO()
        def __init__(self) -> None: self.waits = 0; self.terminated = False
        def wait(self, timeout: float | None = None) -> int:
            self.waits += 1
            if self.waits <= 2: raise subprocess.TimeoutExpired("adb", timeout)
            self.returncode = -15; return self.returncode
        def poll(self) -> int | None: return self.returncode
        def terminate(self) -> None: self.terminated = True
        def kill(self) -> None: self.returncode = -9

    child = Slow()
    monkeypatch.setattr(runner.subprocess, "Popen", lambda *args, **kwargs: child)
    monkeypatch.setattr(runner, "adb", lambda *args, **kwargs: subprocess.CompletedProcess(args, 0, b"34\n", b""))
    assert runner.main(["--output", str(tmp_path)]) == 1
    summary = json.loads((tmp_path / ("native-" + RUN) / "summary.json").read_text())
    assert summary["timedOut"] is True and summary["forceStop"]["action"].startswith("force-stop exact")
    assert child.waits >= 2 and child.terminated
