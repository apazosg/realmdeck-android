"""Host-only offline tests for the Gate B transfer runner (no ADB/device)."""
from __future__ import annotations

import importlib.util
import json
import subprocess
from pathlib import Path
from typing import Any

import pytest


SCRIPT = Path(__file__).parents[1] / "scripts/diagnostics/android-panvk/run_transfer_usb.py"
SPEC = importlib.util.spec_from_file_location("panvk_transfer_runner_test_target", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


RUN = "PVK-" + "b" * 32
ROOT = "/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-loader/" + RUN
ICD = f"{ROOT}/{runner.ICD_NAME}"
OWNER = ICD
VK13 = (1 << 22) | (3 << 12)


def _identity() -> dict[str, Any]:
    return {"regular": True, "ownerUid": runner.UID, "device": 8, "inode": 99, "mode": 0o600, "size": runner.ICD_BYTES}


def _function(name: str) -> dict[str, Any]:
    return {"name": name, "resolved": True, "owner": OWNER, "ownerExact": True, "error": ""}


def _transfer() -> dict[str, Any]:
    return {
        "buffer": {"createAttempted": True, "created": True, "handleReturnedOnError": False, "result": 0, "size": 4096, "usage": 2, "sharingMode": 0},
        "memoryRequirements": {"queried": True, "size": 4096, "alignment": 64, "memoryTypeBits": 0b101},
        "memoryType": {"selected": True, "typeIndex": 0, "propertyFlags": 0x3, "hostVisible": True, "hostCoherent": True, "deviceLocal": False},
        "allocation": {"attempted": True, "allocated": True, "handleReturnedOnError": False, "result": 0, "requestedBytes": 4096, "capacityBytes": 256 * 1024},
        "bind": {"attempted": True, "ok": True},
        "map": {"attempted": True, "mapped": True, "nonNull": True},
        "hostWrite": {"performed": True, "bytes": 4096, "coherent": True, "flushAttempted": False, "flushOk": False, "flushOffset": 0, "flushSize": 0},
        "commandPool": {"createAttempted": True, "created": True, "result": 0, "flags": 2, "queueFamilyIndex": 0},
        "commandBuffer": {"allocateAttempted": True, "allocated": True, "result": 0, "level": 0},
        "record": {"beginAttempted": True, "begun": True, "beginResult": 0, "fillAttempted": True, "fillOk": True, "fillOffset": 0, "fillSize": 4096, "fillData": 0xA1B2C3D4, "barrierAttempted": True, "barrierOk": True, "endAttempted": True, "ended": True, "endResult": 0},
        "fence": {"createAttempted": True, "created": True, "result": 0, "flags": 0},
        "submit": {"attempted": True, "result": 0, "waitAttempted": True, "waitResult": 0, "waited": True, "timeoutNs": 1_000_000_000, "signaled": True},
        "invalidate": {"required": False, "attempted": False, "ok": True, "offset": 0, "size": 0},
        "readback": {"attempted": True, "verified": True, "dwordCount": 1024, "mismatches": 0, "firstMismatchIndex": -1, "pattern": 0xA1B2C3D4, "hashHex": "a" * 16},
    }


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
        "transfer": _transfer(),
        "timing": {"callCount": 12, "monotonicChecks": 24, "valid": True},
        "cleanup": {"deviceDestroyAttempted": True, "deviceDestroyOk": True, "instanceDestroyAttempted": True, "instanceDestroyOk": True, "dlcloseAttempted": True, "dlcloseOk": True, "complete": True},
        "icdCodeLoaded": True, "constructorsMayHaveExecuted": True, "vulkanFunctionsInvoked": True,
        "apiComplete": False, "gpuWorkload": True, "triangle": False, "presentation": False, "d3d": False, "wow": False,
        "status": "TRANSFER_READBACK_OBSERVED", "failureStage": "none", "failureDetail": "", "nativeExit": 0,
    }


def encoded(document: dict[str, Any]) -> bytes:
    return (json.dumps(document, separators=(",", ":")) + "\n").encode()


def test_full_success_document_accepted() -> None:
    parsed = runner.parse_native(encoded(valid_native()), RUN, ROOT, adb_exit=0)
    assert parsed["status"] == "TRANSFER_READBACK_OBSERVED"
    assert parsed["transfer"]["readback"]["verified"] is True
    assert parsed["gpuWorkload"] is True
    assert len(parsed["functions"]) == 33


def test_functions_are_exactly_thirty_three_ordered() -> None:
    assert len(runner.FUNCTION_NAMES) == 33
    assert runner.FUNCTION_NAMES[11] == "vkCreateBuffer"
    assert runner.FUNCTION_NAMES[-1] == "vkQueueSubmit"


def test_missing_icd_identity_stability_rejected() -> None:
    document = valid_native()
    document["icdIdentityStable"] = False
    with pytest.raises(ValueError, match="identity"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_non_exact_function_owner_rejected() -> None:
    document = valid_native()
    document["functions"][11] = {"name": "vkCreateBuffer", "resolved": True, "owner": "/other/lib.so", "ownerExact": False, "error": ""}
    with pytest.raises(ValueError, match="success gates"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_interface_unsupported_rejected() -> None:
    document = valid_native()
    document["interface"] = {"input": 7, "result": 0, "output": 7, "supported": False}
    with pytest.raises(ValueError, match="success gates|interface"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_instance_not_created_rejected() -> None:
    document = valid_native()
    document["instance"].update(created=False, result=-1)
    with pytest.raises(ValueError, match="success gates"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_physical_incomplete_rejected() -> None:
    document = valid_native()
    document["physical"].update(reportedCount=9, returnedCount=0, retainedCount=0, complete=False)
    with pytest.raises(ValueError, match="physical|success gates"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_physical_not_target_exact_rejected() -> None:
    document = valid_native()
    document["physical"].update(vendorId=0x1234, targetExact=False)
    with pytest.raises(ValueError, match="target|success gates"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_queue_null_rejected() -> None:
    document = valid_native()
    document["queue"].update(nonNull=False, observed=False)
    with pytest.raises(ValueError, match="success gates"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_allocation_not_allocated_rejected() -> None:
    document = valid_native()
    document["transfer"]["allocation"].update(allocated=False, result=-2)
    document["status"] = "VK_ERROR"
    document["nativeExit"] = 1
    document["failureStage"] = "memory"
    document["failureDetail"] = "bounded failure"
    parsed = runner.parse_native(encoded(document), RUN, ROOT, adb_exit=1)
    assert parsed["status"] == "VK_ERROR"


def test_allocation_not_allocated_never_success() -> None:
    document = valid_native()
    document["transfer"]["allocation"].update(allocated=False, result=-2)
    with pytest.raises(ValueError, match="success gates"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_fence_not_signaled_rejected() -> None:
    document = valid_native()
    document["transfer"]["submit"].update(waited=False, signaled=False, waitResult=2)
    document["status"] = "TIMEOUT"
    document["nativeExit"] = 1
    document["failureStage"] = "timeout"
    document["failureDetail"] = "fence wait timeout"
    parsed = runner.parse_native(encoded(document), RUN, ROOT, adb_exit=1)
    assert parsed["status"] == "TIMEOUT"
    document2 = valid_native()
    document2["transfer"]["submit"].update(waited=False, signaled=False, waitResult=2)
    with pytest.raises(ValueError, match="success gates"):
        runner.parse_native(encoded(document2), RUN, ROOT, adb_exit=0)


def test_readback_verified_false_rejected() -> None:
    document = valid_native()
    document["transfer"]["readback"].update(verified=False, hashHex="")
    with pytest.raises(ValueError, match="success gates"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_readback_mismatches_rejected() -> None:
    document = valid_native()
    document["transfer"]["readback"].update(verified=False, mismatches=3, firstMismatchIndex=7, hashHex="")
    with pytest.raises(ValueError, match="success gates"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_bool_as_int_rejected() -> None:
    document = valid_native()
    document["physical"]["reportedCount"] = True
    with pytest.raises(ValueError, match="physical.reportedCount"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_int_as_bool_rejected() -> None:
    document = valid_native()
    document["transfer"]["bind"]["ok"] = 1
    with pytest.raises(ValueError, match="boolean"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_unknown_key_rejected() -> None:
    document = valid_native()
    document["extraKey"] = 1
    with pytest.raises(ValueError, match="schema mismatch"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_duplicate_key_rejected() -> None:
    raw = b'{"schema": "wowforever.android-panvk-transfer", "schema": 1}\n'
    with pytest.raises(ValueError, match="duplicate"):
        runner.parse_native(raw, RUN, ROOT, adb_exit=0)


def test_wrong_schema_rejected() -> None:
    document = valid_native()
    document["schema"] = "wowforever.android-panvk-device-queue"
    with pytest.raises(ValueError, match="schema"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_oversize_output_rejected() -> None:
    raw = b"x" * runner.MAX_NATIVE_LINE + b"\n"
    with pytest.raises(ValueError, match="bounded"):
        runner.parse_native(raw, RUN, ROOT, adb_exit=0)


def test_malformed_json_rejected() -> None:
    with pytest.raises(Exception):
        runner.parse_native(b"not json\n", RUN, ROOT, adb_exit=0)


def test_shell_command_is_fixed_run_as_command() -> None:
    command = runner.shell_command(ROOT, RUN)
    assert command[:8] == [runner.ADB, "-s", runner.SERIAL, "shell", "-T", "run-as", runner.PACKAGE, "/system/bin/sh"]
    assert "--run-id" in command[-1]


def test_load_native_validates_real_pins() -> None:
    artifact = runner.load_native()
    assert artifact["bytes"] == runner.NATIVE_ARTIFACT_BYTES == 53280
    assert artifact["sha256"] == runner.NATIVE_ARTIFACT_SHA256
    assert artifact["manifestSha256"] == runner.NATIVE_MANIFEST_SHA256
    assert artifact["icd"]["sha256"] == runner.ICD_SHA256


@pytest.mark.parametrize("status", ["VK_ERROR", "NO_QUEUE", "NO_MEMORY_TYPE", "TIMEOUT", "LOADER_FAILED"])
def test_deterministic_statuses_do_not_force_stop(status: str) -> None:
    assert runner._requires_force_stop(status, start_error=None, timed_out=False,
                                       cleanup_error=None, diagnostic=None,
                                       remaining_error=None, remaining=[], output_errors=[],
                                       native_cleanup_complete=True) is False


def test_no_memory_type_witness_accepted() -> None:
    document = valid_native()
    document["transfer"]["memoryType"] = {"selected": False, "typeIndex": 0, "propertyFlags": 0, "hostVisible": False, "hostCoherent": False, "deviceLocal": False}
    document["transfer"]["allocation"] = {"attempted": True, "allocated": False, "handleReturnedOnError": False, "result": -2, "requestedBytes": 4096, "capacityBytes": 4096}
    document["transfer"]["bind"] = {"attempted": False, "ok": False}
    document["transfer"]["map"] = {"attempted": False, "mapped": False, "nonNull": False}
    document["transfer"]["hostWrite"] = {"performed": False, "bytes": 0, "coherent": False, "flushAttempted": False, "flushOk": False, "flushOffset": 0, "flushSize": 0}
    document["transfer"]["commandPool"] = {"createAttempted": False, "created": False, "result": 0, "flags": 0, "queueFamilyIndex": 0}
    document["transfer"]["commandBuffer"] = {"allocateAttempted": False, "allocated": False, "result": 0, "level": 0}
    document["transfer"]["record"] = {"beginAttempted": False, "begun": False, "beginResult": 0, "fillAttempted": False, "fillOk": False, "fillOffset": 0, "fillSize": 0, "fillData": 0, "barrierAttempted": False, "barrierOk": False, "endAttempted": False, "ended": False, "endResult": 0}
    document["transfer"]["fence"] = {"createAttempted": False, "created": False, "result": 0, "flags": 0}
    document["transfer"]["submit"] = {"attempted": False, "result": 0, "waitAttempted": False, "waitResult": 0, "waited": False, "timeoutNs": 0, "signaled": False}
    document["transfer"]["invalidate"] = {"required": False, "attempted": False, "ok": True, "offset": 0, "size": 0}
    document["transfer"]["readback"] = {"attempted": False, "verified": False, "dwordCount": 0, "mismatches": 0, "firstMismatchIndex": -1, "pattern": 0xA1B2C3D4, "hashHex": ""}
    document["status"] = "NO_MEMORY_TYPE"
    document["failureStage"] = "memory_type"
    document["failureDetail"] = "no host-visible type"
    document["nativeExit"] = 1
    parsed = runner.parse_native(encoded(document), RUN, ROOT, adb_exit=1)
    assert parsed["status"] == "NO_MEMORY_TYPE"


def test_timeout_incomplete_remaining_require_cleanup() -> None:
    common = dict(start_error=None, timed_out=False, cleanup_error=None,
                  diagnostic=None, remaining_error=None, remaining=[], output_errors=[],
                  native_cleanup_complete=True)
    assert runner._requires_force_stop("VK_ERROR", **{**common, "timed_out": True}) is True
    assert runner._requires_force_stop("VK_ERROR", **{**common, "diagnostic": "incomplete pipe"}) is True
    assert runner._requires_force_stop("VK_ERROR", **{**common, "remaining": [{"pid": "1"}]}) is True
    assert runner._requires_force_stop("VK_ERROR", **{**common, "output_errors": ["stdout: x"]}) is True
    assert runner._requires_force_stop("VK_ERROR", **{**common, "native_cleanup_complete": False}) is True
    assert runner._requires_force_stop("UNKNOWN", **common) is True


def test_capture_written_before_popen_and_stdin_closed(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    order: list[str] = []
    seen: dict[str, Any] = {}

    def fake_write(path: Path, data: bytes, mode: int = 0o600) -> None:
        order.append(Path(path).name)

    class FakePopen:
        def __init__(self, *args: Any, **kwargs: Any) -> None:
            order.append("popen")
            seen["stdin"] = kwargs.get("stdin")
            raise OSError("stop before device")

    monkeypatch.setattr(runner, "load_native", lambda: {"path": Path("/external/PanvkTransfer"), "sha256": "x" * 64})
    monkeypatch.setattr(runner, "remote_status_preflight", lambda: {"shellV2": True})
    monkeypatch.setattr(runner, "package_preflight", lambda: (runner.UID, "/data/user/0/com.wowforever.arm64probe", "u:r:platform_app:s0"))
    monkeypatch.setattr(runner, "_api_preflight", lambda: runner.DEVICE_API_LEVEL)
    monkeypatch.setattr(runner, "copy_native", lambda native, run: (f"{runner.PRIVATE_PREFIX}/{run}", {"stagingClean": True}))
    monkeypatch.setattr(runner, "_write_exclusive", fake_write)
    monkeypatch.setattr(runner.subprocess, "Popen", FakePopen)
    monkeypatch.setattr(runner, "_force_stop", lambda: {"action": "force-stop exact package", "returnCode": 0})
    monkeypatch.setattr(runner, "own_processes", lambda uid=runner.UID: [])
    rc = runner.main(["--output", str(tmp_path)])
    assert rc == 1
    assert order[0] == "capture.json"
    assert "popen" in order
    assert order.index("capture.json") < order.index("popen")
    assert seen["stdin"] is subprocess.DEVNULL


def test_upload_policy_has_exclusive_stage_and_private_modes() -> None:
    script = runner._copy_script("/data/local/tmp/wowforever-panvk-transfer-abc", f"{runner.PRIVATE_PREFIX}/{RUN}")
    assert "mkdir -m 700" in script
    assert "chmod 0700" in script and "chmod 0600" in script
    assert "set -C" in script and "PANVK_TRANSFER_UPLOAD_OK" in script
