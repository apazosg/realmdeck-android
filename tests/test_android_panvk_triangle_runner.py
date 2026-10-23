"""Host-only offline tests for the Gate C offscreen-triangle runner (no ADB/device)."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import stat
import subprocess
from pathlib import Path
from typing import Any

import pytest


SCRIPT = Path(__file__).parents[1] / "scripts/diagnostics/android-panvk/run_triangle_usb.py"
SPEC = importlib.util.spec_from_file_location("panvk_triangle_runner_test_target", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


RUN = "PVK-" + "c" * 32
ROOT = "/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-loader/" + RUN
ICD = f"{ROOT}/{runner.ICD_NAME}"
OWNER = ICD
VK13 = (1 << 22) | (3 << 12)
V1_ROOT = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/panvk-triangle-v1")
V1_ARTIFACT_SHA256 = "2e757f2bcc6be23fe0b40cd6994cb00d3b8ca7388ccc8af91ca1efcf228013d0"
V1_MANIFEST_SHA256 = "009b09db7b3eb717b060dd16fa5cd86b7ef2b4c37d4614688b4790fcdb7a4ded"


def _identity() -> dict[str, Any]:
    return {"regular": True, "ownerUid": runner.UID, "device": 8, "inode": 99, "mode": 0o600, "size": runner.ICD_BYTES}


def _function(name: str) -> dict[str, Any]:
    return {"name": name, "resolved": True, "owner": OWNER, "ownerExact": True, "error": ""}


def _triangle() -> dict[str, Any]:
    return {
        "image": {"createAttempted": True, "created": True, "handleReturnedOnError": False, "result": 0, "format": 37, "extentWidth": 64, "extentHeight": 64, "usage": 0x21, "tiling": 0, "samples": 1, "mipLevels": 1, "arrayLayers": 1, "sharingMode": 0},
        "imageMemory": {"requirementsQueried": True, "requirementsSize": 65536, "alignment": 256, "memoryTypeBits": 0b101, "typeSelected": True, "typeIndex": 0, "propertyFlags": 0x1, "deviceLocal": True, "allocateAttempted": True, "allocated": True, "handleReturnedOnError": False, "allocateResult": 0, "requestedBytes": 65536, "capacityBytes": 16 * 1024 * 1024},
        "bind": {"attempted": True, "ok": True},
        "view": {"createAttempted": True, "created": True, "handleReturnedOnError": False, "result": 0},
        "renderPass": {"createAttempted": True, "created": True, "handleReturnedOnError": False, "result": 0},
        "framebuffer": {"createAttempted": True, "created": True, "handleReturnedOnError": False, "result": 0},
        "shaders": {"vertBytes": 1024, "fragBytes": 512, "vertCreateAttempted": True, "vertCreated": True, "vertResult": 0, "fragCreateAttempted": True, "fragCreated": True, "fragResult": 0},
        "pipelineLayout": {"createAttempted": True, "created": True, "result": 0},
        "pipeline": {"createAttempted": True, "created": True, "handleReturnedOnError": False, "result": 0},
        "staging": {"bufferCreateAttempted": True, "bufferCreated": True, "handleReturnedOnError": False, "bufferResult": 0, "bufferSize": 16384, "bufferUsage": 2, "memoryQueried": True, "memorySize": 16384, "alignment": 64, "memoryTypeBits": 0b101, "memoryTypeSelected": True, "typeIndex": 1, "hostVisible": True, "hostCoherent": True, "allocateAttempted": True, "allocated": True, "allocateResult": 0, "requestedBytes": 16384, "capacityBytes": 16 * 1024 * 1024, "bindAttempted": True, "bindOk": True, "mapAttempted": True, "mapped": True, "nonNull": True},
        "commandPool": {"createAttempted": True, "created": True, "result": 0, "flags": 2, "queueFamilyIndex": 0},
        "commandBuffer": {"allocateAttempted": True, "allocated": True, "result": 0, "level": 0},
        "record": {"beginAttempted": True, "begun": True, "beginResult": 0, "drawAttempted": True, "drawOk": True, "drawVertexCount": 3, "copyAttempted": True, "copyOk": True, "endAttempted": True, "ended": True, "endResult": 0},
        "fence": {"createAttempted": True, "created": True, "result": 0, "flags": 0},
        "submit": {"attempted": True, "result": 0, "submitCount": 1, "waitAttempted": True, "waitResult": 0, "waited": True, "timeoutNs": 1_000_000_000, "signaled": True},
        "invalidate": {"required": False, "attempted": False, "ok": True, "offset": 0, "size": 0},
        "readback": {"attempted": True, "verified": True, "byteSize": 16384, "pixelCount": 4096, "clearPixels": 3000, "trianglePixels": 1096, "hashHex": "b" * 16},
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
        "triangle": _triangle(),
        "timing": {"callCount": 12, "monotonicChecks": 24, "valid": True},
        "cleanup": {"deviceDestroyAttempted": True, "deviceDestroyOk": True, "instanceDestroyAttempted": True, "instanceDestroyOk": True, "dlcloseAttempted": True, "dlcloseOk": True, "complete": True},
        "icdCodeLoaded": True, "constructorsMayHaveExecuted": True, "vulkanFunctionsInvoked": True,
        "apiComplete": False, "gpuWorkload": True, "triangleRendered": True, "presentation": False, "d3d": False, "wow": False,
        "status": "TRIANGLE_OFFSCREEN_OBSERVED", "failureStage": "none", "failureDetail": "", "nativeExit": 0,
    }


def encoded(document: dict[str, Any]) -> bytes:
    return (json.dumps(document, separators=(",", ":")) + "\n").encode()


def test_full_success_document_accepted() -> None:
    parsed = runner.parse_native(encoded(valid_native()), RUN, ROOT, adb_exit=0)
    assert parsed["status"] == "TRIANGLE_OFFSCREEN_OBSERVED"
    assert parsed["triangle"]["readback"]["verified"] is True
    assert parsed["gpuWorkload"] is True
    assert parsed["triangleRendered"] is True
    assert len(parsed["functions"]) == 51


def test_functions_are_exactly_fifty_one_ordered() -> None:
    assert len(runner.FUNCTION_NAMES) == 51
    assert runner.FUNCTION_NAMES[0] == "vkEnumerateInstanceVersion"
    assert runner.FUNCTION_NAMES[11] == "vkCreateImage"
    assert runner.FUNCTION_NAMES[-1] == "vkQueueSubmit"
    assert "vkCreateGraphicsPipelines" in runner.FUNCTION_NAMES
    assert "vkCmdDraw" in runner.FUNCTION_NAMES


def test_graphics_is_required_but_unified_flags_keep_transfer_kind() -> None:
    assert runner._eligible(1 << 2, 1) is None
    assert runner._eligible(1 | (1 << 2) | (1 << 1), 1) == "transfer"
    document = valid_native()
    document["queues"]["families"][0]["flags"] = 7
    document["selection"].update(flags=7, kind="transfer")
    assert runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)["status"] == "TRIANGLE_OFFSCREEN_OBSERVED"


def test_allocation_request_covers_real_requirements() -> None:
    for resource, requirements, requested in (
        ("imageMemory", "requirementsSize", "requestedBytes"),
        ("staging", "memorySize", "requestedBytes"),
    ):
        document = valid_native()
        document["triangle"][resource][requested] = document["triangle"][resource][requirements] - 1
        with pytest.raises(ValueError, match="allocation size"):
            runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_missing_icd_identity_stability_rejected() -> None:
    document = valid_native()
    document["icdIdentityStable"] = False
    with pytest.raises(ValueError, match="identity"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_non_exact_function_owner_rejected() -> None:
    document = valid_native()
    document["functions"][11] = {"name": "vkCreateImage", "resolved": True, "owner": "/other/lib.so", "ownerExact": False, "error": ""}
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


def test_image_not_created_vk_error_accepted() -> None:
    document = valid_native()
    document["triangle"]["image"].update(created=False, result=-2, handleReturnedOnError=False)
    document["status"] = "VK_ERROR"
    document["nativeExit"] = 1
    document["failureStage"] = "image"
    document["failureDetail"] = "bounded failure"
    document["gpuWorkload"] = False
    document["triangleRendered"] = False
    parsed = runner.parse_native(encoded(document), RUN, ROOT, adb_exit=1)
    assert parsed["status"] == "VK_ERROR"


def test_image_not_created_never_success() -> None:
    document = valid_native()
    document["triangle"]["image"].update(created=False, result=-2)
    with pytest.raises(ValueError, match="success gates"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_pipeline_not_created_never_success() -> None:
    document = valid_native()
    document["triangle"]["pipeline"].update(created=False, result=-2)
    with pytest.raises(ValueError, match="success gates"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_fence_not_signaled_timeout_accepted() -> None:
    document = valid_native()
    document["triangle"]["submit"].update(waited=False, signaled=False, waitResult=2)
    document["status"] = "TIMEOUT"
    document["nativeExit"] = 1
    document["failureStage"] = "timeout"
    document["failureDetail"] = "fence wait timeout"
    document["gpuWorkload"] = False
    document["triangleRendered"] = False
    parsed = runner.parse_native(encoded(document), RUN, ROOT, adb_exit=1)
    assert parsed["status"] == "TIMEOUT"
    document2 = valid_native()
    document2["triangle"]["submit"].update(waited=False, signaled=False, waitResult=2)
    with pytest.raises(ValueError, match="success gates"):
        runner.parse_native(encoded(document2), RUN, ROOT, adb_exit=0)


def test_readback_clear_only_rejected() -> None:
    document = valid_native()
    document["triangle"]["readback"].update(verified=False, clearPixels=4096, trianglePixels=0, hashHex="")
    with pytest.raises(ValueError, match="success gates|verified"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_readback_covers_all_rejected() -> None:
    document = valid_native()
    document["triangle"]["readback"].update(verified=False, clearPixels=0, trianglePixels=4096, hashHex="")
    with pytest.raises(ValueError, match="success gates|verified"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_readback_accounting_mismatch_rejected() -> None:
    document = valid_native()
    document["triangle"]["readback"].update(clearPixels=2000, trianglePixels=2000)
    with pytest.raises(ValueError, match="accounting"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_bool_as_int_rejected() -> None:
    document = valid_native()
    document["physical"]["reportedCount"] = True
    with pytest.raises(ValueError, match="physical.reportedCount"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_int_as_bool_rejected() -> None:
    document = valid_native()
    document["triangle"]["bind"]["ok"] = 1
    with pytest.raises(ValueError, match="boolean"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_unknown_key_rejected() -> None:
    document = valid_native()
    document["extraKey"] = 1
    with pytest.raises(ValueError, match="schema mismatch"):
        runner.parse_native(encoded(document), RUN, ROOT, adb_exit=0)


def test_duplicate_key_rejected() -> None:
    raw = b'{"schema": "wowforever.android-panvk-triangle", "schema": 1}\n'
    with pytest.raises(ValueError, match="duplicate"):
        runner.parse_native(raw, RUN, ROOT, adb_exit=0)


def test_wrong_schema_rejected() -> None:
    document = valid_native()
    document["schema"] = "wowforever.android-panvk-transfer"
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
    assert artifact["bytes"] == runner.NATIVE_ARTIFACT_BYTES == 57264
    assert artifact["sha256"] == runner.NATIVE_ARTIFACT_SHA256
    assert artifact["manifestSha256"] == runner.NATIVE_MANIFEST_SHA256
    assert artifact["icd"]["sha256"] == runner.ICD_SHA256


def test_new_artifact_is_real_and_old_v1_record_is_untouched() -> None:
    old_artifact = V1_ROOT / "PanvkTriangle"
    old_manifest = V1_ROOT / "manifest.json"
    assert hashlib.sha256(old_artifact.read_bytes()).hexdigest() == V1_ARTIFACT_SHA256
    assert hashlib.sha256(old_manifest.read_bytes()).hexdigest() == V1_MANIFEST_SHA256
    current = runner.load_native()
    assert runner.ARTIFACT_ROOT.name == "panvk-triangle-v2-sync"
    assert current["bytes"] == runner.NATIVE_ARTIFACT_BYTES <= 64 * 1024
    assert current["sha256"] != V1_ARTIFACT_SHA256
    assert current["manifestSha256"] != V1_MANIFEST_SHA256
    assert current["sha256"] == json.loads(runner.NATIVE_MANIFEST.read_text())["sha256"]


def test_real_pin_variant_validates(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    src = tmp_path / "src"
    src.mkdir()
    sources = {
        "PanvkTriangle.c": b"// triangle probe\n",
        "README-triangle.md": b"# triangle\n",
        "build_triangle_probe.sh": b"#!/bin/sh\n",
    }
    pins: dict[str, dict[str, Any]] = {}
    for name, data in sources.items():
        path = src / name
        path.write_bytes(data)
        pins[name] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    header = tmp_path / "vulkan_core.h"
    header.write_bytes(b"h" * 1060766)
    helper = tmp_path / "PanvkPrivateLoader.c"
    helper.write_bytes(b"l" * 41294)
    header_sha = hashlib.sha256(header.read_bytes()).hexdigest()
    helper_sha = hashlib.sha256(helper.read_bytes()).hexdigest()
    artifact = tmp_path / "PanvkTriangle"
    artifact.write_bytes(b"\x7fELF-triangle-probe")
    os.chmod(artifact, 0o755)
    artifact_sha = hashlib.sha256(artifact.read_bytes()).hexdigest()
    icd = tmp_path / "libvulkan_panfrost.so"
    icd.write_bytes(b"icd-bytes")
    os.chmod(icd, 0o600)
    icd_sha = hashlib.sha256(icd.read_bytes()).hexdigest()
    manifest = tmp_path / "manifest.json"
    document = {
        "schema": runner.BUILD_SCHEMA,
        "file": runner.NATIVE_NAME,
        "size": len(b"\x7fELF-triangle-probe"),
        "sha256": artifact_sha,
        "mode": "0755",
        "interpreter": "/system/bin/linker64",
        "elfType": "ET_DYN",
        "machine": "AArch64",
        "pie": True,
        "ndk": "24.0.8215888",
        "target": "aarch64-linux-android29",
        "neededLibraries": ["libc.so", "libdl.so"],
        "linkLibraries": ["c", "dl"],
        "sourceBytes": {name: pin["bytes"] for name, pin in pins.items()},
        "sourceSha256": {name: pin["sha256"] for name, pin in pins.items()},
        "header": {"path": str(header), "bytes": 1060766, "sha256": header_sha},
        "helperSource": {"path": str(helper), "bytes": 41294, "sha256": helper_sha},
        "icdPin": {"sourcePath": str(icd), "bytes": len(b"icd-bytes"), "sha256": icd_sha, "mode": "0600"},
    }
    manifest.write_bytes(json.dumps(document).encode())
    manifest_sha = hashlib.sha256(manifest.read_bytes()).hexdigest()
    monkeypatch.setattr(runner, "SOURCE_ROOT", src)
    monkeypatch.setattr(runner, "SOURCE_PINS", pins)
    monkeypatch.setattr(runner, "HEADER_SOURCE", header)
    monkeypatch.setattr(runner, "HELPER_SOURCE", helper)
    monkeypatch.setattr(runner, "HEADER_SHA256", header_sha)
    monkeypatch.setattr(runner, "HELPER_SHA256", helper_sha)
    monkeypatch.setattr(runner, "NATIVE_ARTIFACT", artifact)
    monkeypatch.setattr(runner, "NATIVE_MANIFEST", manifest)
    monkeypatch.setattr(runner, "ICD_SOURCE", icd)
    monkeypatch.setattr(runner, "NATIVE_ARTIFACT_BYTES", len(b"\x7fELF-triangle-probe"))
    monkeypatch.setattr(runner, "NATIVE_BYTES", len(b"\x7fELF-triangle-probe"))
    monkeypatch.setattr(runner, "NATIVE_ARTIFACT_SHA256", artifact_sha)
    monkeypatch.setattr(runner, "NATIVE_SHA256", artifact_sha)
    monkeypatch.setattr(runner, "NATIVE_MANIFEST_SHA256", manifest_sha)
    monkeypatch.setattr(runner, "ICD_BYTES", len(b"icd-bytes"))
    monkeypatch.setattr(runner, "ICD_SHA256", icd_sha)
    loaded = runner.load_native()
    assert loaded["bytes"] == len(b"\x7fELF-triangle-probe")
    assert loaded["sha256"] == artifact_sha
    assert loaded["icd"]["sha256"] == icd_sha


@pytest.mark.parametrize("status", ["VK_ERROR", "NO_QUEUE", "NO_MEMORY_TYPE", "TIMEOUT", "LOADER_FAILED"])
def test_deterministic_statuses_do_not_force_stop(status: str) -> None:
    assert runner._requires_force_stop(status, start_error=None, timed_out=False,
                                       cleanup_error=None, diagnostic=None,
                                       remaining_error=None, remaining=[], output_errors=[],
                                       native_cleanup_complete=True) is False


def test_no_memory_type_witness_accepted() -> None:
    document = valid_native()
    document["triangle"]["imageMemory"] = {"requirementsQueried": True, "requirementsSize": 65536, "alignment": 256, "memoryTypeBits": 0b101, "typeSelected": False, "typeIndex": 0, "propertyFlags": 0, "deviceLocal": False, "allocateAttempted": False, "allocated": False, "handleReturnedOnError": False, "allocateResult": 0, "requestedBytes": 0, "capacityBytes": 0}
    document["triangle"]["bind"] = {"attempted": False, "ok": False}
    document["triangle"]["view"] = {"createAttempted": False, "created": False, "handleReturnedOnError": False, "result": 0}
    document["triangle"]["renderPass"] = {"createAttempted": False, "created": False, "handleReturnedOnError": False, "result": 0}
    document["triangle"]["framebuffer"] = {"createAttempted": False, "created": False, "handleReturnedOnError": False, "result": 0}
    document["triangle"]["shaders"] = {"vertBytes": 0, "fragBytes": 0, "vertCreateAttempted": False, "vertCreated": False, "vertResult": 0, "fragCreateAttempted": False, "fragCreated": False, "fragResult": 0}
    document["triangle"]["pipelineLayout"] = {"createAttempted": False, "created": False, "result": 0}
    document["triangle"]["pipeline"] = {"createAttempted": False, "created": False, "handleReturnedOnError": False, "result": 0}
    document["triangle"]["staging"] = {"bufferCreateAttempted": False, "bufferCreated": False, "handleReturnedOnError": False, "bufferResult": 0, "bufferSize": 0, "bufferUsage": 0, "memoryQueried": False, "memorySize": 0, "alignment": 0, "memoryTypeBits": 0, "memoryTypeSelected": False, "typeIndex": 0, "hostVisible": False, "hostCoherent": False, "allocateAttempted": False, "allocated": False, "allocateResult": 0, "requestedBytes": 0, "capacityBytes": 0, "bindAttempted": False, "bindOk": False, "mapAttempted": False, "mapped": False, "nonNull": False}
    document["triangle"]["commandPool"] = {"createAttempted": False, "created": False, "result": 0, "flags": 0, "queueFamilyIndex": 0}
    document["triangle"]["commandBuffer"] = {"allocateAttempted": False, "allocated": False, "result": 0, "level": 0}
    document["triangle"]["record"] = {"beginAttempted": False, "begun": False, "beginResult": 0, "drawAttempted": False, "drawOk": False, "drawVertexCount": 0, "copyAttempted": False, "copyOk": False, "endAttempted": False, "ended": False, "endResult": 0}
    document["triangle"]["fence"] = {"createAttempted": False, "created": False, "result": 0, "flags": 0}
    document["triangle"]["submit"] = {"attempted": False, "result": 0, "submitCount": 0, "waitAttempted": False, "waitResult": 0, "waited": False, "timeoutNs": 0, "signaled": False}
    document["triangle"]["invalidate"] = {"required": False, "attempted": False, "ok": True, "offset": 0, "size": 0}
    document["triangle"]["readback"] = {"attempted": False, "verified": False, "byteSize": 0, "pixelCount": 0, "clearPixels": 0, "trianglePixels": 0, "hashHex": ""}
    document["gpuWorkload"] = False
    document["triangleRendered"] = False
    document["status"] = "NO_MEMORY_TYPE"
    document["failureStage"] = "memory_type"
    document["failureDetail"] = "no device-local type"
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

    monkeypatch.setattr(runner, "load_native", lambda: {"path": Path("/external/PanvkTriangle"), "sha256": "x" * 64})
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
    script = runner._copy_script("/data/local/tmp/wowforever-panvk-triangle-abc", f"{runner.PRIVATE_PREFIX}/{RUN}")
    assert "mkdir -m 700" in script
    assert "chmod 0700" in script and "chmod 0600" in script
    assert "set -C" in script and "PANVK_TRIANGLE_UPLOAD_OK" in script
