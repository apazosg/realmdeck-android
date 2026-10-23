from __future__ import annotations

import importlib.util
import json
import subprocess
from pathlib import Path
from typing import Any

import pytest


SCRIPT = Path(__file__).parents[1] / "scripts/diagnostics/android-panvk/run_device_usb.py"
SPEC = importlib.util.spec_from_file_location("panvk_device_runner_test_target", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


RUN = "PVK-" + "a" * 32
ROOT = "/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-loader/" + RUN
ICD = f"{ROOT}/{runner.ICD_NAME}"
OWNER = ICD
VK13 = (1 << 22) | (3 << 12)


def _identity() -> dict[str, Any]:
    return {"regular": True, "ownerUid": runner.UID, "device": 8, "inode": 99, "mode": 0o600, "size": runner.ICD_BYTES}


def _function(name: str) -> dict[str, Any]:
    return {"name": name, "resolved": True, "owner": OWNER, "ownerExact": True, "error": ""}


def valid_native() -> dict[str, Any]:
    families = [{"index": 0, "flags": 1, "queueCount": 1}]
    return {
        "schema": runner.NATIVE_SCHEMA, "version": 1, "runId": RUN,
        "pid": 123, "uid": runner.UID, "pgid": 123, "sid": 123,
        "monotonicStartNs": 100, "monotonicEndNs": 200, "cwd": ROOT, "scopeValid": True,
        "icdPath": ICD, "icdBytes": runner.ICD_BYTES, "icdSha256": runner.ICD_SHA256,
        "icdIdentityBefore": _identity(), "icdIdentityAfter": _identity(), "icdIdentityStable": True,
        "load": {"attempted": True, "loaded": True, "dlerror": ""},
        "exports": [{"name": name, "found": True, "owner": OWNER, "ownerExact": True, "error": ""} for name in runner.EXPORT_NAMES],
        "interface": {"input": 7, "result": 0, "output": 7, "supported": True},
        "functions": [_function(name) for name in runner.FUNCTION_NAMES],
        "api": {"enumerateInstanceVersionFound": True, "ownerExact": True, "legacy10": False, "result": 0, "reported": VK13, "requested": VK13},
        "instance": {"createAttempted": True, "created": True, "handleReturnedOnError": False, "result": 0, "layerCount": 0, "extensionCount": 0, "allocatorNull": True},
        "physical": {"reportedCount": 1, "returnedCount": 1, "retainedCount": 1, "capacity": 8, "countResult": 0, "fillResult": 0, "complete": True, "vendorId": 0x13B5, "deviceId": 0xA8670000, "name": "Mali-G610 MC6", "propertiesValid": True, "targetExact": True},
        "queues": {"reportedCount": 1, "returnedCount": 1, "retainedCount": 1, "capacity": 16, "complete": True, "families": families},
        "selection": {"selected": True, "familyIndex": 0, "flags": 1, "queueCount": 1, "kind": "graphics"},
        "device": {"createAttempted": True, "created": True, "handleReturnedOnError": False, "result": 0, "queueCreateCount": 1, "queueFamilyIndex": 0, "queueCount": 1, "priority": 1.0, "featuresNull": True, "layerCount": 0, "extensionCount": 0, "allocatorNull": True},
        "queue": {"getAttempted": True, "index": 0, "nonNull": True, "observed": True},
        "timing": {"callCount": 10, "monotonicChecks": 20, "valid": True},
        "cleanup": {"deviceDestroyAttempted": True, "deviceDestroyOk": True, "instanceDestroyAttempted": True, "instanceDestroyOk": True, "dlcloseAttempted": True, "dlcloseOk": True, "complete": True},
        "icdCodeLoaded": True, "constructorsMayHaveExecuted": True, "vulkanFunctionsInvoked": True,
        "apiComplete": False, "gpuWorkload": False, "triangle": False, "presentation": False, "d3d": False, "wow": False,
        "status": "DEVICE_QUEUE_OBSERVED", "failureStage": "none", "failureDetail": "", "nativeExit": 0,
    }


def encoded(document: dict[str, Any]) -> bytes:
    return (json.dumps(document, separators=(",", ":")) + "\n").encode()


def test_valid_full_schema_is_a_control_a_success() -> None:
    parsed = runner.parse_native(encoded(valid_native()), RUN, ROOT, adb_exit=0)
    assert parsed["status"] == "DEVICE_QUEUE_OBSERVED"
    assert parsed["gpuWorkload"] is False


@pytest.mark.parametrize(
    "mutate",
    [
        lambda d: d["physical"].update(reportedCount=9, returnedCount=0, retainedCount=0, complete=False),
        lambda d: d["queues"].update(reportedCount=17, returnedCount=0, complete=False),
        lambda d: d["device"].update(result=-4, created=False, handleReturnedOnError=True),
        lambda d: d["queue"].update(nonNull=False, observed=False),
    ],
)
def test_partial_or_failed_observations_never_pass(mutate: Any) -> None:
    document = valid_native()
    mutate(document)
    document["status"] = "UNKNOWN" if document["device"]["result"] == 0 else "VK_ERROR"
    document["nativeExit"] = 1
    document["failureStage"] = "vulkan" if document["status"] == "VK_ERROR" else "queues"
    document["failureDetail"] = "bounded failure"
    if document["status"] == "UNKNOWN":
        document["queues"] = {"reportedCount": 0, "returnedCount": 0, "retainedCount": 0, "capacity": 16, "complete": False, "families": []}
        document["selection"] = {"selected": False, "familyIndex": 0, "flags": 0, "queueCount": 0, "kind": ""}
        document["queue"]["getAttempted"] = False
        document["queue"]["nonNull"] = False
        document["queue"]["observed"] = False
    parsed = runner.parse_native(encoded(document), RUN, ROOT, adb_exit=1)
    assert parsed["status"] != "DEVICE_QUEUE_OBSERVED"


def test_bool_is_not_accepted_as_native_integer() -> None:
    document = valid_native()
    document["physical"]["reportedCount"] = True
    with pytest.raises(ValueError, match="physical.reportedCount"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_null_and_success_handle_contradictions_are_rejected() -> None:
    document = valid_native()
    document["device"]["handleReturnedOnError"] = True
    with pytest.raises(ValueError, match="error-handle"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_api_witness_must_be_capped_and_consistent() -> None:
    document = valid_native()
    document["api"]["requested"] = (1 << 22) | (2 << 12)
    with pytest.raises(ValueError, match="capped"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_no_queue_requires_complete_queue_counts() -> None:
    document = valid_native()
    document["queues"] = {"reportedCount": 1, "returnedCount": 0, "retainedCount": 0, "capacity": 16, "complete": False, "families": []}
    document["selection"] = {"selected": False, "familyIndex": 0, "flags": 0, "queueCount": 0, "kind": ""}
    document["status"] = "NO_QUEUE"
    document["failureStage"] = "no_queue"
    document["failureDetail"] = "incomplete"
    document["nativeExit"] = 1
    with pytest.raises(ValueError, match="NO_QUEUE"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=1)


def test_unknown_cleanup_is_preserved_but_not_success() -> None:
    document = valid_native()
    document["cleanup"]["deviceDestroyOk"] = False
    document["cleanup"]["complete"] = False
    document["status"] = "UNKNOWN"
    document["failureStage"] = "cleanup"
    document["failureDetail"] = "destroy did not complete"
    document["nativeExit"] = 1
    parsed = runner.parse_native(encoded(document), RUN, ROOT, adb_exit=1)
    assert parsed["cleanup"]["complete"] is False


def test_shell_command_is_fixed_run_as_command() -> None:
    command = runner.shell_command(ROOT, RUN)
    assert command[:8] == [runner.ADB, "-s", runner.SERIAL, "shell", "-T", "run-as", runner.PACKAGE, "/system/bin/sh"]
    assert "--run-id" in command[-1]
    assert "PANVK" not in command[-1]


def test_capture_write_failure_happens_before_popen(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    calls = {"popen": 0}

    class NeverPopen:
        def __init__(self, *args: Any, **kwargs: Any) -> None:
            calls["popen"] += 1

    monkeypatch.setattr(runner, "load_native", lambda: {"path": Path("/external/PanvkDeviceQueue"), "sha256": runner.NATIVE_SHA256})
    monkeypatch.setattr(runner, "remote_status_preflight", lambda: {"shellV2": True})
    monkeypatch.setattr(runner, "package_preflight", lambda: (runner.UID, "/data/user/0/com.wowforever.arm64probe", "u:r:platform_app:s0"))
    monkeypatch.setattr(runner, "_api_preflight", lambda: runner.DEVICE_API_LEVEL)
    monkeypatch.setattr(runner, "copy_native", lambda native, run: (f"{runner.PRIVATE_PREFIX}/{run}", {"stagingClean": True}))
    monkeypatch.setattr(runner.subprocess, "Popen", NeverPopen)

    def fail_capture(path: Path, data: bytes, mode: int = 0o600) -> None:
        raise OSError("capture fsync failed")

    monkeypatch.setattr(runner, "_write_exclusive", fail_capture)
    with pytest.raises(OSError, match="capture fsync"):
        runner.main(["--output", str(tmp_path)])
    assert calls["popen"] == 0


def test_upload_policy_has_exclusive_stage_and_private_modes() -> None:
    script = runner._copy_script("/data/local/tmp/wowforever-panvk-device-abc", f"{runner.PRIVATE_PREFIX}/{RUN}")
    assert "mkdir -m 755" not in script
    assert "mkdir -m 700" in script
    assert "chmod 0700" in script and "chmod 0600" in script
    assert "set -C" in script and "PANVK_DEVICE_UPLOAD_OK" in script


@pytest.mark.parametrize("status", ["VK_ERROR", "NO_QUEUE", "LOADER_FAILED"])
def test_known_native_failure_does_not_force_stop_an_unrelated_owned_process(status: str) -> None:
    assert runner._requires_force_stop(status, start_error=None, timed_out=False,
                                       cleanup_error=None, diagnostic=None,
                                       remaining_error=None, remaining=[], output_errors=[],
                                       native_cleanup_complete=True) is False


def test_unknown_or_incomplete_capture_still_requires_exact_package_cleanup() -> None:
    common = dict(start_error=None, timed_out=False, cleanup_error=None,
                  diagnostic=None, remaining_error=None, remaining=[], output_errors=[],
                  native_cleanup_complete=True)
    assert runner._requires_force_stop("UNKNOWN", **common) is True
    assert runner._requires_force_stop("VK_ERROR", **{**common, "diagnostic": "incomplete pipe"}) is True
    assert runner._requires_force_stop("VK_ERROR", **{**common, "native_cleanup_complete": False}) is True
