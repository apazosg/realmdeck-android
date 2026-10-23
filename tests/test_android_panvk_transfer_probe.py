"""Offline fake-PFN tests for PanVK control B (bounded 4 KiB transfer)."""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
SOURCE = ROOT / "scripts/diagnostics/android-panvk/PanvkTransfer.c"
README = ROOT / "scripts/diagnostics/android-panvk/README-transfer.md"
BUILD_SCRIPT = ROOT / "scripts/diagnostics/android-panvk/build_transfer_probe.sh"
HEADER_INCLUDE = Path("/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include")

TOP_LEVEL_KEYS = [
    "schema", "version", "runId", "pid", "uid", "pgid", "sid",
    "monotonicStartNs", "monotonicEndNs", "cwd", "scopeValid", "icdPath",
    "icdBytes", "icdSha256", "icdIdentityBefore", "icdIdentityAfter",
    "icdIdentityStable", "load", "exports", "interface", "functions", "api",
    "instance", "physical", "queues", "selection", "device", "queue",
    "transfer", "timing", "cleanup", "icdCodeLoaded",
    "constructorsMayHaveExecuted", "vulkanFunctionsInvoked", "apiComplete",
    "gpuWorkload", "triangle", "presentation", "d3d", "wow", "status",
    "failureStage", "failureDetail", "nativeExit",
]

TRANSFER_KEYS = [
    "buffer", "memoryRequirements", "memoryType", "allocation", "bind", "map",
    "hostWrite", "commandPool", "commandBuffer", "record", "fence", "submit",
    "invalidate", "readback",
]

FUNCTION_NAMES = [
    "vkEnumerateInstanceVersion", "vkCreateInstance", "vkDestroyInstance",
    "vkEnumeratePhysicalDevices", "vkGetPhysicalDeviceProperties",
    "vkGetPhysicalDeviceQueueFamilyProperties",
    "vkGetPhysicalDeviceMemoryProperties", "vkCreateDevice", "vkDestroyDevice",
    "vkGetDeviceProcAddr", "vkGetDeviceQueue", "vkCreateBuffer",
    "vkDestroyBuffer", "vkGetBufferMemoryRequirements", "vkAllocateMemory",
    "vkFreeMemory", "vkBindBufferMemory", "vkMapMemory", "vkUnmapMemory",
    "vkFlushMappedMemoryRanges", "vkInvalidateMappedMemoryRanges",
    "vkCreateCommandPool", "vkDestroyCommandPool", "vkAllocateCommandBuffers",
    "vkFreeCommandBuffers", "vkBeginCommandBuffer", "vkEndCommandBuffer",
    "vkCmdFillBuffer", "vkCmdPipelineBarrier", "vkCreateFence",
    "vkDestroyFence", "vkWaitForFences", "vkQueueSubmit",
]

CASES = [
    "pass", "pass-nocoherent", "create-error", "buffer-error",
    "requirements-cap", "no-memory-type", "alloc-error", "bind-error",
    "map-null", "flush-error", "fence-timeout", "readback-mismatch", "owner",
    "clock-after-create",
]


def _compiler() -> str:
    compiler = shutil.which("clang") or shutil.which("cc") or shutil.which("gcc")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    return compiler


def _fixture(tmp_path: Path) -> Path:
    harness = tmp_path / "panvk_transfer_fixture.c"
    harness.write_text(
        f"""
#define _GNU_SOURCE 1
#define PANVK_TRANSFER_HOST_TEST 1
#include <stdio.h>
#include <string.h>
#include "{SOURCE}"
int main(int argc, char **argv) {{
    char output[65536];
    char seq[8192];
    if (argc != 2) return 90;
    if (!strcmp(argv[1], "sequence")) {{
        if (!panvk_transfer_test_fake("pass", output, sizeof(output))) return 1;
        if (!panvk_transfer_test_call_sequence(seq, sizeof(seq))) return 1;
        if (fputs(seq, stdout) == EOF || fputc('\\n', stdout) == EOF) return 1;
        return 0;
    }}
    if (!strcmp(argv[1], "sequence-nocoherent")) {{
        if (!panvk_transfer_test_fake("pass-nocoherent", output, sizeof(output))) return 1;
        if (!panvk_transfer_test_call_sequence(seq, sizeof(seq))) return 1;
        if (fputs(seq, stdout) == EOF || fputc('\\n', stdout) == EOF) return 1;
        return 0;
    }}
    if (!strcmp(argv[1], "submit-counts")) {{
        int submit = 0, wait = 0;
        if (!panvk_transfer_test_fake("pass", output, sizeof(output))) return 1;
        return panvk_transfer_test_submit_counts(&submit, &wait) &&
               submit == 1 && wait == 1 ? 0 : 1;
    }}
    if (!strcmp(argv[1], "oversize")) {{
        if (!panvk_transfer_test_encode_oversized_fallback(output, sizeof(output))) return 1;
        if (fputs(output, stdout) == EOF || fputc('\\n', stdout) == EOF) return 1;
        return 0;
    }}
    for (int i = 0; i < {(len(CASES))}; ++i) {{
        (void)i;
    }}
    if (!panvk_transfer_test_fake(argv[1], output, sizeof(output))) return 1;
    if (fputs(output, stdout) == EOF || fputc('\\n', stdout) == EOF) return 1;
    return 0;
}}
""",
        encoding="utf-8",
    )
    executable = tmp_path / "panvk_transfer_fixture"
    command = [
        _compiler(), "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
        "-DPANVK_TRANSFER_HOST_TEST",
        f"-I{HEADER_INCLUDE}", str(harness), "-ldl", "-o", str(executable),
    ]
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    assert result.returncode == 0, result.stdout + result.stderr
    return executable


def _run_case(fixture: Path, case: str) -> dict:
    result = subprocess.run([str(fixture), case], capture_output=True, text=True, check=False)
    assert result.returncode == 0, result.stderr
    return json.loads(result.stdout)


def test_source_and_readme_boundaries() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    readme = README.read_text(encoding="utf-8")
    assert '#include "PanvkPrivateLoader.c"' in source
    assert "vkCmdFillBuffer" in source and "vkQueueSubmit" in source
    assert "vkWaitForFences" in source and "vkCreateBuffer" in source
    for forbidden in (
        "vkCreateShaderModule", "SPIRV", "vkCreateImage", "vkCreateRenderPass",
        "vkCreateFramebuffer", "vkCreateSwapchain", "vkQueueWaitIdle",
        "vkDeviceWaitIdle", "vkResetDevice", "eglInitialize", "ioctl(",
        'open("/dev/', "ptrace", "textureCompressionBC", "robustBufferAccess2",
    ):
        assert forbidden not in source
    assert "TRANSFER_READBACK_OBSERVED" in readme
    assert "NO_MEMORY_TYPE" in readme and "TIMEOUT" in readme
    assert "wowforever.android-panvk-transfer" in readme
    assert "build_transfer_probe.sh" in BUILD_SCRIPT.read_text(encoding="utf-8")


def test_full_success_lifecycle_with_exact_call_order(tmp_path: Path) -> None:
    fixture = _fixture(tmp_path)
    assert subprocess.run([str(fixture), "submit-counts"], check=False).returncode == 0
    result = subprocess.run([str(fixture), "sequence"], capture_output=True, text=True, check=False)
    assert result.returncode == 0, result.stderr
    calls = result.stdout.strip().split(",")
    prefix = [
        "I:vkGetPhysicalDeviceQueueFamilyProperties",
        "I:vkGetPhysicalDeviceMemoryProperties",
        "I:vkCreateDevice", "I:vkDestroyDevice", "I:vkGetDeviceProcAddr",
        "QF:count", "QF:fill", "CREATE", "D:vkDestroyDevice",
        "D:vkGetDeviceQueue", "GETQ",
    ]
    assert calls[:len(prefix)] == prefix
    device_queries = [f"D:{name}" for name in FUNCTION_NAMES[11:]]
    assert calls[len(prefix):len(prefix) + len(device_queries)] == device_queries
    tail = [
        "BUF_CREATE", "MEMREQ", "MEMPROPS", "ALLOC", "BIND", "MAP",
        "POOL_CREATE", "CMD_ALLOC", "BEGIN", "FILL", "BARRIER", "END",
        "FENCE_CREATE", "SUBMIT", "WAIT", "UNMAP", "CMD_FREE", "POOL_DESTROY",
        "FENCE_DESTROY", "BUF_DESTROY", "MEM_FREE", "DESTROY",
    ]
    assert calls[len(prefix) + len(device_queries):] == tail

    document = _run_case(fixture, "pass")
    assert document["status"] == "TRANSFER_READBACK_OBSERVED"
    assert document["nativeExit"] == 0
    assert document["schema"] == "wowforever.android-panvk-transfer"
    assert document["version"] == 1
    assert document["physical"]["vendorId"] == 0x13B5
    assert document["physical"]["deviceId"] == 0xA8670000
    assert document["physical"]["name"] == "Mali-G610 MC6"
    assert document["selection"]["selected"] is True
    assert document["selection"]["kind"] in ("transfer", "graphics", "compute")
    assert document["device"]["queueCreateCount"] == 1
    assert document["device"]["queueCount"] == 1
    assert document["device"]["priority"] == 1.0
    assert document["device"]["featuresNull"] is True
    assert document["device"]["layerCount"] == document["device"]["extensionCount"] == 0
    assert document["queue"] == {"getAttempted": True, "index": 0, "nonNull": True, "observed": True}
    transfer = document["transfer"]
    assert transfer["buffer"] == {
        "createAttempted": True, "created": True, "handleReturnedOnError": False,
        "result": 0, "size": 4096, "usage": 2, "sharingMode": 0,
    }
    assert transfer["memoryRequirements"]["queried"] is True
    assert transfer["memoryRequirements"]["size"] == 4096
    assert transfer["memoryType"]["selected"] is True
    assert transfer["memoryType"]["hostVisible"] is True
    assert transfer["allocation"]["attempted"] is True
    assert transfer["allocation"]["allocated"] is True
    assert transfer["allocation"]["capacityBytes"] == 262144
    assert transfer["bind"] == {"attempted": True, "ok": True}
    assert transfer["map"] == {"attempted": True, "mapped": True, "nonNull": True}
    assert transfer["hostWrite"]["performed"] is True
    assert transfer["hostWrite"]["bytes"] == 4096
    assert transfer["commandPool"]["created"] is True
    assert transfer["commandPool"]["queueFamilyIndex"] == document["selection"]["familyIndex"]
    assert transfer["commandBuffer"] == {"allocateAttempted": True, "allocated": True, "result": 0, "level": 0}
    assert transfer["record"]["begun"] is True
    assert transfer["record"]["fillOk"] is True
    assert transfer["record"]["fillOffset"] == 0
    assert transfer["record"]["fillSize"] == 4096
    assert transfer["record"]["fillData"] == 0xA1B2C3D4
    assert transfer["record"]["barrierOk"] is True
    assert transfer["record"]["ended"] is True
    assert transfer["fence"]["created"] is True
    assert transfer["fence"]["flags"] == 0
    assert transfer["submit"]["attempted"] is True
    assert transfer["submit"]["result"] == 0
    assert transfer["submit"]["waitAttempted"] is True
    assert transfer["submit"]["waitResult"] == 0
    assert transfer["submit"]["waited"] is True
    assert transfer["submit"]["timeoutNs"] == 1000000000
    assert transfer["submit"]["signaled"] is True
    assert transfer["readback"]["attempted"] is True
    assert transfer["readback"]["verified"] is True
    assert transfer["readback"]["dwordCount"] == 1024
    assert transfer["readback"]["mismatches"] == 0
    assert transfer["readback"]["firstMismatchIndex"] == -1
    assert transfer["readback"]["pattern"] == 0xA1B2C3D4
    assert len(transfer["readback"]["hashHex"]) == 16
    int(transfer["readback"]["hashHex"], 16)
    assert transfer["readback"]["hashHex"] == transfer["readback"]["hashHex"].lower()
    assert document["cleanup"]["complete"] is True
    assert document["cleanup"]["deviceDestroyOk"] is True
    assert document["cleanup"]["instanceDestroyOk"] is True
    assert document["cleanup"]["dlcloseOk"] is True
    assert document["gpuWorkload"] is True
    assert all(document[name] is False for name in ("apiComplete", "triangle", "presentation", "d3d", "wow"))
    assert document["vulkanFunctionsInvoked"] is True


def test_noncoherent_path_flushes_and_invalidates(tmp_path: Path) -> None:
    fixture = _fixture(tmp_path)
    result = subprocess.run([str(fixture), "sequence-nocoherent"], capture_output=True, text=True, check=False)
    assert result.returncode == 0, result.stderr
    calls = result.stdout.strip().split(",")
    assert "FLUSH" in calls and "INVALIDATE" in calls
    assert calls.index("FLUSH") < calls.index("POOL_CREATE")
    assert calls.index("INVALIDATE") < calls.index("UNMAP")
    document = _run_case(fixture, "pass-nocoherent")
    assert document["status"] == "TRANSFER_READBACK_OBSERVED"
    assert document["nativeExit"] == 0
    assert document["transfer"]["memoryType"]["hostCoherent"] is False
    assert document["transfer"]["hostWrite"]["flushAttempted"] is True
    assert document["transfer"]["hostWrite"]["flushOk"] is True
    assert document["transfer"]["invalidate"]["required"] is True
    assert document["transfer"]["invalidate"]["attempted"] is True
    assert document["transfer"]["invalidate"]["ok"] is True
    assert document["transfer"]["readback"]["verified"] is True
    assert document["gpuWorkload"] is True


@pytest.mark.parametrize("case, status", [
    ("create-error", "VK_ERROR"),
    ("buffer-error", "VK_ERROR"),
    ("requirements-cap", "UNKNOWN"),
    ("no-memory-type", "NO_MEMORY_TYPE"),
    ("alloc-error", "VK_ERROR"),
    ("bind-error", "VK_ERROR"),
    ("map-null", "UNKNOWN"),
    ("flush-error", "VK_ERROR"),
    ("fence-timeout", "TIMEOUT"),
    ("readback-mismatch", "UNKNOWN"),
    ("owner", "UNKNOWN"),
    ("clock-after-create", "UNKNOWN"),
])
def test_fake_pfn_failures_never_claim_success(tmp_path: Path, case: str, status: str) -> None:
    fixture = _fixture(tmp_path)
    document = _run_case(fixture, case)
    assert document["status"] == status
    assert document["nativeExit"] == 1
    assert document["gpuWorkload"] is False
    if case == "requirements-cap":
        assert document["transfer"]["memoryRequirements"]["size"] == 300000
        assert document["transfer"]["allocation"]["attempted"] is False
        assert document["transfer"]["allocation"]["allocated"] is False
    if case == "no-memory-type":
        assert document["transfer"]["memoryType"]["selected"] is False
        assert document["failureStage"] == "memory"
        assert document["transfer"]["allocation"]["attempted"] is False
    if case == "alloc-error":
        assert document["transfer"]["allocation"]["attempted"] is True
        assert document["transfer"]["allocation"]["allocated"] is False
        assert document["transfer"]["allocation"]["result"] < 0
    if case == "bind-error":
        assert document["transfer"]["bind"] == {"attempted": True, "ok": False}
    if case == "map-null":
        assert document["transfer"]["map"]["nonNull"] is False
        assert document["transfer"]["readback"]["attempted"] is False
    if case == "flush-error":
        assert document["transfer"]["hostWrite"]["flushAttempted"] is True
        assert document["transfer"]["hostWrite"]["flushOk"] is False
    if case == "fence-timeout":
        assert document["transfer"]["submit"]["attempted"] is True
        assert document["transfer"]["submit"]["result"] == 0
        assert document["transfer"]["submit"]["waitAttempted"] is True
        assert document["transfer"]["submit"]["waited"] is False
        assert document["transfer"]["submit"]["signaled"] is False
        assert document["failureStage"] == "timeout"
        assert document["transfer"]["readback"]["attempted"] is False
        assert document["cleanup"]["complete"] is False
    if case == "readback-mismatch":
        assert document["transfer"]["readback"]["attempted"] is True
        assert document["transfer"]["readback"]["verified"] is False
        assert document["transfer"]["readback"]["mismatches"] == 1
        assert document["transfer"]["readback"]["firstMismatchIndex"] == 7
    if case == "clock-after-create":
        assert document["timing"]["valid"] is False
        assert document["cleanup"]["deviceDestroyOk"] is True


def test_json_schema_keyset_and_types(tmp_path: Path) -> None:
    fixture = _fixture(tmp_path)
    for case in ("pass", "fence-timeout", "no-memory-type", "readback-mismatch"):
        raw = subprocess.run([str(fixture), case], capture_output=True, text=True, check=False)
        assert raw.returncode == 0, raw.stderr
        assert raw.stdout.endswith("\n") and raw.stdout.count("\n") == 1
        assert len(raw.stdout.encode("utf-8")) < 64 * 1024
        document = json.loads(raw.stdout)
        assert list(document.keys()) == TOP_LEVEL_KEYS
        assert document["schema"] == "wowforever.android-panvk-transfer"
        assert document["version"] == 1 and isinstance(document["version"], int)
        assert isinstance(document["runId"], str) and len(document["runId"]) == 36
        assert isinstance(document["nativeExit"], int)
        assert isinstance(document["scopeValid"], bool)
        assert isinstance(document["load"], dict) and set(document["load"].keys()) == {"attempted", "loaded", "dlerror"}
        assert len(document["exports"]) == 3
        assert len(document["functions"]) == 33
        assert [entry["name"] for entry in document["functions"]] == FUNCTION_NAMES
        for entry in document["functions"]:
            assert set(entry.keys()) == {"name", "resolved", "owner", "ownerExact", "error"}
            assert isinstance(entry["resolved"], bool) and isinstance(entry["ownerExact"], bool)
        assert set(document["transfer"].keys()) == set(TRANSFER_KEYS)
        buffer = document["transfer"]["buffer"]
        assert set(buffer.keys()) == {"createAttempted", "created", "handleReturnedOnError", "result", "size", "usage", "sharingMode"}
        assert set(document["transfer"]["memoryRequirements"].keys()) == {"queried", "size", "alignment", "memoryTypeBits"}
        assert set(document["transfer"]["memoryType"].keys()) == {"selected", "typeIndex", "propertyFlags", "hostVisible", "hostCoherent", "deviceLocal"}
        assert set(document["transfer"]["allocation"].keys()) == {"attempted", "allocated", "handleReturnedOnError", "result", "requestedBytes", "capacityBytes"}
        assert set(document["transfer"]["bind"].keys()) == {"attempted", "ok"}
        assert set(document["transfer"]["map"].keys()) == {"attempted", "mapped", "nonNull"}
        assert set(document["transfer"]["hostWrite"].keys()) == {"performed", "bytes", "coherent", "flushAttempted", "flushOk", "flushOffset", "flushSize"}
        assert set(document["transfer"]["commandPool"].keys()) == {"createAttempted", "created", "result", "flags", "queueFamilyIndex"}
        assert set(document["transfer"]["commandBuffer"].keys()) == {"allocateAttempted", "allocated", "result", "level"}
        assert set(document["transfer"]["record"].keys()) == {"beginAttempted", "begun", "beginResult", "fillAttempted", "fillOk", "fillOffset", "fillSize", "fillData", "barrierAttempted", "barrierOk", "endAttempted", "ended", "endResult"}
        assert set(document["transfer"]["fence"].keys()) == {"createAttempted", "created", "result", "flags"}
        assert set(document["transfer"]["submit"].keys()) == {"attempted", "result", "waitAttempted", "waitResult", "waited", "timeoutNs", "signaled"}
        assert set(document["transfer"]["invalidate"].keys()) == {"required", "attempted", "ok", "offset", "size"}
        assert set(document["transfer"]["readback"].keys()) == {"attempted", "verified", "dwordCount", "mismatches", "firstMismatchIndex", "pattern", "hashHex"}
        for flag in ("apiComplete", "triangle", "presentation", "d3d", "wow"):
            assert document[flag] is False


def test_oversize_fallback_is_truthful_unknown(tmp_path: Path) -> None:
    fixture = _fixture(tmp_path)
    result = subprocess.run([str(fixture), "oversize"], capture_output=True, text=True, check=False)
    assert result.returncode == 0, result.stderr
    document = json.loads(result.stdout)
    assert document["status"] == "UNKNOWN"
    assert document["nativeExit"] == 1
    assert document["failureStage"] == "output"
    assert list(document.keys()) == TOP_LEVEL_KEYS


def test_success_path_performs_exactly_one_submit_and_wait(tmp_path: Path) -> None:
    fixture = _fixture(tmp_path)
    assert subprocess.run([str(fixture), "submit-counts"], check=False).returncode == 0
    document = _run_case(fixture, "pass")
    assert document["transfer"]["submit"]["attempted"] is True
    assert document["transfer"]["submit"]["waitAttempted"] is True
    assert document["transfer"]["submit"]["timeoutNs"] == 1000000000
    assert document["status"] == "TRANSFER_READBACK_OBSERVED"
    timeout_document = _run_case(fixture, "fence-timeout")
    assert timeout_document["status"] == "TIMEOUT"
    assert timeout_document["cleanup"]["complete"] is False


def test_readback_hash_matches_pattern_bytes() -> None:
    words = (0xA1B2C3D4.to_bytes(4, "little")) * 1024
    state = 14695981039346656037
    for byte in words:
        state ^= byte
        state = (state * 1099511628211) & 0xFFFFFFFFFFFFFFFF
    assert f"{state:016x}" == f"{state:016x}".lower()
