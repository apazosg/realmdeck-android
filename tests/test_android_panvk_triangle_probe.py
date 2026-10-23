"""Offline fake-PFN tests for PanVK Gate C (offscreen triangle readback)."""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
SOURCE = ROOT / "scripts/diagnostics/android-panvk/PanvkTriangle.c"
README = ROOT / "scripts/diagnostics/android-panvk/README-triangle.md"
BUILD_SCRIPT = ROOT / "scripts/diagnostics/android-panvk/build_triangle_probe.sh"
VERT_SHADER = ROOT / "scripts/diagnostics/android-panvk/shaders/triangle.vert"
FRAG_SHADER = ROOT / "scripts/diagnostics/android-panvk/shaders/triangle.frag"
HEADER_INCLUDE = Path("/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include")

TOP_LEVEL_KEYS = [
    "schema", "version", "runId", "pid", "uid", "pgid", "sid",
    "monotonicStartNs", "monotonicEndNs", "cwd", "scopeValid", "icdPath",
    "icdBytes", "icdSha256", "icdIdentityBefore", "icdIdentityAfter",
    "icdIdentityStable", "load", "exports", "interface", "functions", "api",
    "instance", "physical", "queues", "selection", "device", "queue",
    "triangle", "timing", "cleanup", "icdCodeLoaded",
    "constructorsMayHaveExecuted", "vulkanFunctionsInvoked", "apiComplete",
    "gpuWorkload", "triangleRendered", "presentation", "d3d", "wow", "status",
    "failureStage", "failureDetail", "nativeExit",
]

TRIANGLE_KEYS = [
    "image", "imageMemory", "bind", "view", "renderPass", "framebuffer",
    "shaders", "pipelineLayout", "pipeline", "staging", "commandPool",
    "commandBuffer", "record", "fence", "submit", "invalidate", "readback",
]

FUNCTION_NAMES = [
    "vkEnumerateInstanceVersion", "vkCreateInstance", "vkDestroyInstance",
    "vkEnumeratePhysicalDevices", "vkGetPhysicalDeviceProperties",
    "vkGetPhysicalDeviceQueueFamilyProperties",
    "vkGetPhysicalDeviceMemoryProperties", "vkCreateDevice", "vkDestroyDevice",
    "vkGetDeviceProcAddr", "vkGetDeviceQueue",
    "vkCreateImage", "vkGetImageMemoryRequirements",
    "vkAllocateMemory", "vkFreeMemory", "vkBindImageMemory",
    "vkCreateImageView", "vkDestroyImageView", "vkDestroyImage",
    "vkCreateRenderPass", "vkDestroyRenderPass",
    "vkCreateFramebuffer", "vkDestroyFramebuffer",
    "vkCreateShaderModule", "vkDestroyShaderModule",
    "vkCreatePipelineLayout", "vkDestroyPipelineLayout",
    "vkCreateGraphicsPipelines", "vkDestroyPipeline",
    "vkCreateBuffer", "vkDestroyBuffer", "vkGetBufferMemoryRequirements",
    "vkBindBufferMemory", "vkMapMemory", "vkUnmapMemory",
    "vkInvalidateMappedMemoryRanges",
    "vkCreateCommandPool", "vkDestroyCommandPool", "vkAllocateCommandBuffers",
    "vkFreeCommandBuffers", "vkBeginCommandBuffer", "vkEndCommandBuffer",
    "vkCmdBeginRenderPass", "vkCmdBindPipeline", "vkCmdDraw",
    "vkCmdEndRenderPass", "vkCmdCopyImageToBuffer",
    "vkCreateFence", "vkDestroyFence", "vkWaitForFences", "vkQueueSubmit",
]

CASES = [
    "pass", "pass-nocoherent", "create-error", "image-error",
    "image-requirements-cap", "no-memory-type", "image-alloc-error",
    "staging-alloc-error", "image-bind-error", "view-error",
    "renderpass-error", "framebuffer-error", "vert-shader-error",
    "frag-shader-error", "pipeline-layout-error", "pipeline-error",
    "staging-buffer-error", "staging-requirements-cap", "staging-bind-error",
    "map-null", "pool-error", "cmd-alloc-error", "begin-error", "end-error",
    "submit-error", "fence-timeout", "wait-error", "clear-only", "full-cover",
    "owner", "clock-after-create", "transfer-only",
]


def _compiler() -> str:
    compiler = shutil.which("clang") or shutil.which("cc") or shutil.which("gcc")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    return compiler


def _fixture(tmp_path: Path) -> Path:
    stub = tmp_path / "triangle_spirv.h"
    stub.write_text(
        "#pragma once\n"
        "#include <stdint.h>\n"
        "static const uint32_t triangle_vert_spv[] = { "
        "0x07230203U, 0x00010000U, 0x00080001U, 0x0000000BU };\n"
        "static const uint32_t triangle_frag_spv[] = { "
        "0x07230203U, 0x00010000U, 0x00080001U, 0x0000000CU };\n",
        encoding="utf-8",
    )
    harness = tmp_path / "panvk_triangle_fixture.c"
    harness.write_text(
        f"""
#define _GNU_SOURCE 1
#define PANVK_TRIANGLE_HOST_TEST 1
#include <stdio.h>
#include <string.h>
#include "{SOURCE}"
int main(int argc, char **argv) {{
    char output[65536];
    char seq[16384];
    if (argc != 2) return 90;
    if (!strcmp(argv[1], "sequence")) {{
        if (!panvk_triangle_test_fake("pass", output, sizeof(output))) return 1;
        if (!panvk_triangle_test_call_sequence(seq, sizeof(seq))) return 1;
        if (fputs(seq, stdout) == EOF || fputc('\\n', stdout) == EOF) return 1;
        return 0;
    }}
    if (!strcmp(argv[1], "sequence-nocoherent")) {{
        if (!panvk_triangle_test_fake("pass-nocoherent", output, sizeof(output))) return 1;
        if (!panvk_triangle_test_call_sequence(seq, sizeof(seq))) return 1;
        if (fputs(seq, stdout) == EOF || fputc('\\n', stdout) == EOF) return 1;
        return 0;
    }}
    if (!strcmp(argv[1], "submit-counts")) {{
        int submit = 0, wait = 0;
        if (!panvk_triangle_test_fake("pass", output, sizeof(output))) return 1;
        return panvk_triangle_test_submit_counts(&submit, &wait) &&
               submit == 1 && wait == 1 ? 0 : 1;
    }}
    if (!strcmp(argv[1], "oversize")) {{
        if (!panvk_triangle_test_encode_oversized_fallback(output, sizeof(output))) return 1;
        if (fputs(output, stdout) == EOF || fputc('\\n', stdout) == EOF) return 1;
        return 0;
    }}
    for (int i = 0; i < {(len(CASES))}; ++i) {{
        (void)i;
    }}
    if (!panvk_triangle_test_fake(argv[1], output, sizeof(output))) return 1;
    if (fputs(output, stdout) == EOF || fputc('\\n', stdout) == EOF) return 1;
    return 0;
}}
""",
        encoding="utf-8",
    )
    executable = tmp_path / "panvk_triangle_fixture"
    command = [
        _compiler(), "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
        "-DPANVK_TRIANGLE_HOST_TEST",
        f"-I{HEADER_INCLUDE}", f"-I{tmp_path}", str(harness), "-ldl", "-o", str(executable),
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
    assert '#include "triangle_spirv.h"' in source
    assert "vkCreateImage" in source and "vkCreateGraphicsPipelines" in source
    assert "vkCmdDraw" in source and "vkCmdCopyImageToBuffer" in source
    assert "vkCreateRenderPass" in source and "vkCreateFramebuffer" in source
    assert "vkCreateShaderModule" in source and "vkQueueSubmit" in source
    assert "dependencyCount = 2U" in source
    assert "VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT" in source
    assert "VK_ACCESS_TRANSFER_READ_BIT" in source
    assert "VK_QUEUE_GRAPHICS_BIT" in source
    for forbidden in (
        "vkCmdFillBuffer", "vkCmdPipelineBarrier", "vkFlushMappedMemoryRanges",
        "vkCreateSwapchain", "vkQueuePresent", "vkQueueWaitIdle",
        "vkDeviceWaitIdle", "vkResetDevice", "eglInitialize", "ioctl(",
        'open("/dev/', "ptrace", "textureCompressionBC", "robustBufferAccess2",
    ):
        assert forbidden not in source
    assert "TRIANGLE_OFFSCREEN_OBSERVED" in readme
    assert "NO_MEMORY_TYPE" in readme and "TIMEOUT" in readme
    assert "clearPixels" in readme and "trianglePixels" in readme
    assert "transfer-only family is rejected before device creation" in readme
    assert "universal pixel-count proof" in readme
    assert "wowforever.android-panvk-triangle" in readme
    build_text = BUILD_SCRIPT.read_text(encoding="utf-8")
    assert "glslc" in build_text and "spirv-val" in build_text
    assert "panvk-triangle-v1" in build_text and "triangle_spirv.h" in build_text
    assert VERT_SHADER.read_text(encoding="utf-8").count("gl_VertexIndex") >= 1
    assert "layout(location = 0) out vec4" in FRAG_SHADER.read_text(encoding="utf-8")


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
        "IMG_CREATE", "IMG_MEMREQ", "MEMPROPS", "IMG_ALLOC", "IMG_BIND",
        "VIEW_CREATE", "RP_CREATE", "FB_CREATE", "SHADER_VERT", "SHADER_FRAG",
        "LAYOUT_CREATE", "PIPELINE_CREATE", "BUF_CREATE", "BUF_MEMREQ",
        "STG_ALLOC", "STG_BIND", "MAP", "POOL_CREATE", "CMD_ALLOC", "BEGIN",
        "BEGIN_RP", "BIND_PIPE", "DRAW", "END_RP", "COPY", "END",
        "FENCE_CREATE", "SUBMIT", "WAIT",
        "PIPE_DESTROY", "LAYOUT_DESTROY", "SHADER_DESTROY", "SHADER_DESTROY",
        "FB_DESTROY", "RP_DESTROY", "VIEW_DESTROY", "IMG_DESTROY", "UNMAP",
        "CMD_FREE", "BUF_DESTROY", "MEM_FREE", "MEM_FREE", "POOL_DESTROY",
        "FENCE_DESTROY", "DESTROY",
    ]
    assert calls[len(prefix) + len(device_queries):] == tail

    document = _run_case(fixture, "pass")
    assert document["status"] == "TRIANGLE_OFFSCREEN_OBSERVED"
    assert document["nativeExit"] == 0
    assert document["schema"] == "wowforever.android-panvk-triangle"
    assert document["version"] == 1
    assert document["physical"]["vendorId"] == 0x13B5
    assert document["physical"]["deviceId"] == 0xA8670000
    assert document["physical"]["name"] == "Mali-G610 MC6"
    assert document["selection"]["selected"] is True
    assert document["selection"]["kind"] == "graphics"
    assert document["device"]["queueCreateCount"] == 1
    assert document["device"]["queueCount"] == 1
    assert document["device"]["priority"] == 1.0
    assert document["device"]["featuresNull"] is True
    assert document["device"]["layerCount"] == document["device"]["extensionCount"] == 0
    assert document["queue"] == {"getAttempted": True, "index": 0, "nonNull": True, "observed": True}
    triangle = document["triangle"]
    assert triangle["image"] == {
        "createAttempted": True, "created": True, "handleReturnedOnError": False,
        "result": 0, "format": 37, "extentWidth": 64, "extentHeight": 64,
        "usage": 0x21, "tiling": 0, "samples": 1, "mipLevels": 1,
        "arrayLayers": 1, "sharingMode": 0,
    }
    image_memory = triangle["imageMemory"]
    assert image_memory["requirementsQueried"] is True
    assert image_memory["requirementsSize"] == 65536
    assert image_memory["memoryTypeBits"] == 2
    assert image_memory["typeSelected"] is True
    assert image_memory["typeIndex"] == 1
    assert image_memory["deviceLocal"] is True
    assert image_memory["allocateAttempted"] is True
    assert image_memory["allocated"] is True
    assert image_memory["handleReturnedOnError"] is False
    assert image_memory["allocateResult"] == 0
    assert image_memory["capacityBytes"] == 16 * 1024 * 1024
    assert triangle["bind"] == {"attempted": True, "ok": True}
    for key in ("view", "renderPass", "framebuffer"):
        assert triangle[key] == {
            "createAttempted": True, "created": True,
            "handleReturnedOnError": False, "result": 0,
        }
    assert triangle["shaders"] == {
        "vertBytes": 16, "fragBytes": 16, "vertCreateAttempted": True,
        "vertCreated": True, "vertResult": 0, "fragCreateAttempted": True,
        "fragCreated": True, "fragResult": 0,
    }
    assert triangle["pipelineLayout"] == {"createAttempted": True, "created": True, "result": 0}
    assert triangle["pipeline"] == {
        "createAttempted": True, "created": True,
        "handleReturnedOnError": False, "result": 0,
    }
    staging = triangle["staging"]
    assert staging["bufferCreateAttempted"] is True
    assert staging["bufferCreated"] is True
    assert staging["handleReturnedOnError"] is False
    assert staging["bufferResult"] == 0
    assert staging["bufferSize"] == 16384
    assert staging["bufferUsage"] == 2
    assert staging["memoryQueried"] is True
    assert staging["memorySize"] == 16384
    assert staging["memoryTypeBits"] == 1
    assert staging["memoryTypeSelected"] is True
    assert staging["typeIndex"] == 0
    assert staging["hostVisible"] is True
    assert staging["hostCoherent"] is True
    assert staging["allocateAttempted"] is True
    assert staging["allocated"] is True
    assert staging["allocateResult"] == 0
    assert staging["capacityBytes"] == 16 * 1024 * 1024
    assert staging["bindAttempted"] is True and staging["bindOk"] is True
    assert staging["mapAttempted"] is True and staging["mapped"] is True
    assert staging["nonNull"] is True
    assert triangle["commandPool"]["created"] is True
    assert triangle["commandPool"]["result"] == 0
    assert triangle["commandPool"]["flags"] == 2
    assert triangle["commandPool"]["queueFamilyIndex"] == document["selection"]["familyIndex"]
    assert triangle["commandBuffer"] == {"allocateAttempted": True, "allocated": True, "result": 0, "level": 0}
    record = triangle["record"]
    assert record["beginAttempted"] is True and record["begun"] is True
    assert record["beginResult"] == 0
    assert record["drawAttempted"] is True and record["drawOk"] is True
    assert record["drawVertexCount"] == 3
    assert record["copyAttempted"] is True and record["copyOk"] is True
    assert record["endAttempted"] is True and record["ended"] is True
    assert record["endResult"] == 0
    assert triangle["fence"] == {"createAttempted": True, "created": True, "result": 0, "flags": 0}
    submit = triangle["submit"]
    assert submit["attempted"] is True
    assert submit["result"] == 0
    assert submit["submitCount"] == 1
    assert submit["waitAttempted"] is True
    assert submit["waitResult"] == 0
    assert submit["waited"] is True
    assert submit["timeoutNs"] == 1000000000
    assert submit["signaled"] is True
    assert triangle["invalidate"] == {
        "required": False, "attempted": False, "ok": False,
        "offset": 0, "size": 0,
    }
    readback = triangle["readback"]
    assert readback["attempted"] is True
    assert readback["verified"] is True
    assert readback["byteSize"] == 16384
    assert readback["pixelCount"] == 4096
    assert readback["clearPixels"] == 2048
    assert readback["trianglePixels"] == 2048
    assert len(readback["hashHex"]) == 16
    int(readback["hashHex"], 16)
    assert readback["hashHex"] == readback["hashHex"].lower()
    assert document["cleanup"]["complete"] is True
    assert document["cleanup"]["deviceDestroyOk"] is True
    assert document["cleanup"]["instanceDestroyOk"] is True
    assert document["cleanup"]["dlcloseOk"] is True
    assert document["gpuWorkload"] is True
    assert document["triangleRendered"] is True
    assert all(document[name] is False for name in ("apiComplete", "presentation", "d3d", "wow"))
    assert document["vulkanFunctionsInvoked"] is True


def test_noncoherent_path_invalidates(tmp_path: Path) -> None:
    fixture = _fixture(tmp_path)
    result = subprocess.run([str(fixture), "sequence-nocoherent"], capture_output=True, text=True, check=False)
    assert result.returncode == 0, result.stderr
    calls = result.stdout.strip().split(",")
    assert "INVALIDATE" in calls
    assert calls.index("INVALIDATE") == calls.index("WAIT") + 1
    assert calls.index("INVALIDATE") < calls.index("UNMAP")
    document = _run_case(fixture, "pass-nocoherent")
    assert document["status"] == "TRIANGLE_OFFSCREEN_OBSERVED"
    assert document["nativeExit"] == 0
    assert document["triangle"]["staging"]["hostCoherent"] is False
    assert document["triangle"]["invalidate"]["required"] is True
    assert document["triangle"]["invalidate"]["attempted"] is True
    assert document["triangle"]["invalidate"]["ok"] is True
    assert document["triangle"]["invalidate"]["offset"] == 0
    assert document["triangle"]["invalidate"]["size"] == 16384
    assert document["triangle"]["readback"]["verified"] is True
    assert document["triangleRendered"] is True
    assert document["gpuWorkload"] is True


def test_transfer_only_family_is_rejected_before_device_creation(tmp_path: Path) -> None:
    fixture = _fixture(tmp_path)
    result = subprocess.run([str(fixture), "transfer-only"], capture_output=True, text=True, check=False)
    assert result.returncode == 0, result.stderr
    document = json.loads(result.stdout)
    assert document["status"] == "UNKNOWN"
    assert document["failureStage"] == "no_queue"
    assert document["selection"]["selected"] is False
    assert document["device"]["createAttempted"] is False


@pytest.mark.parametrize("case, status", [
    ("create-error", "VK_ERROR"),
    ("image-error", "VK_ERROR"),
    ("image-requirements-cap", "UNKNOWN"),
    ("no-memory-type", "NO_MEMORY_TYPE"),
    ("image-alloc-error", "VK_ERROR"),
    ("staging-alloc-error", "VK_ERROR"),
    ("image-bind-error", "VK_ERROR"),
    ("view-error", "VK_ERROR"),
    ("renderpass-error", "VK_ERROR"),
    ("framebuffer-error", "VK_ERROR"),
    ("vert-shader-error", "VK_ERROR"),
    ("frag-shader-error", "VK_ERROR"),
    ("pipeline-layout-error", "VK_ERROR"),
    ("pipeline-error", "VK_ERROR"),
    ("staging-buffer-error", "VK_ERROR"),
    ("staging-requirements-cap", "UNKNOWN"),
    ("staging-bind-error", "VK_ERROR"),
    ("map-null", "UNKNOWN"),
    ("pool-error", "VK_ERROR"),
    ("cmd-alloc-error", "VK_ERROR"),
    ("begin-error", "VK_ERROR"),
    ("end-error", "VK_ERROR"),
    ("submit-error", "VK_ERROR"),
    ("fence-timeout", "TIMEOUT"),
    ("wait-error", "VK_ERROR"),
    ("clear-only", "UNKNOWN"),
    ("full-cover", "UNKNOWN"),
    ("owner", "UNKNOWN"),
    ("clock-after-create", "UNKNOWN"),
])
def test_fake_pfn_failures_never_claim_success(tmp_path: Path, case: str, status: str) -> None:
    fixture = _fixture(tmp_path)
    document = _run_case(fixture, case)
    assert document["status"] == status
    assert document["nativeExit"] == 1
    assert document["gpuWorkload"] is False
    assert document["triangleRendered"] is False
    triangle = document["triangle"]
    if case == "image-requirements-cap":
        assert triangle["imageMemory"]["requirementsSize"] == 33554432
        assert triangle["imageMemory"]["allocateAttempted"] is False
        assert triangle["imageMemory"]["allocated"] is False
    if case == "no-memory-type":
        assert triangle["staging"]["memoryTypeSelected"] is False
        assert triangle["staging"]["allocated"] is False
        assert document["failureStage"] == "memory_type"
    if case == "image-alloc-error":
        assert triangle["imageMemory"]["allocateAttempted"] is True
        assert triangle["imageMemory"]["allocated"] is False
        assert triangle["imageMemory"]["allocateResult"] < 0
    if case == "staging-alloc-error":
        assert triangle["staging"]["allocateAttempted"] is True
        assert triangle["staging"]["allocated"] is False
        assert triangle["staging"]["allocateResult"] < 0
    if case == "image-bind-error":
        assert triangle["bind"] == {"attempted": True, "ok": False}
    if case == "map-null":
        assert triangle["staging"]["nonNull"] is False
        assert triangle["readback"]["attempted"] is False
    if case == "fence-timeout":
        assert triangle["submit"]["attempted"] is True
        assert triangle["submit"]["result"] == 0
        assert triangle["submit"]["waitAttempted"] is True
        assert triangle["submit"]["waited"] is False
        assert triangle["submit"]["signaled"] is False
        assert document["failureStage"] == "timeout"
        assert triangle["readback"]["attempted"] is False
        assert document["cleanup"]["complete"] is False
    if case == "clear-only":
        assert triangle["readback"]["attempted"] is True
        assert triangle["readback"]["verified"] is False
        assert triangle["readback"]["clearPixels"] == 4096
        assert triangle["readback"]["trianglePixels"] == 0
        assert triangle["readback"]["hashHex"] == ""
    if case == "full-cover":
        assert triangle["readback"]["attempted"] is True
        assert triangle["readback"]["verified"] is False
        assert triangle["readback"]["clearPixels"] == 0
        assert triangle["readback"]["trianglePixels"] == 4096
        assert triangle["readback"]["hashHex"] == ""
    if case == "clock-after-create":
        assert document["timing"]["valid"] is False
        assert document["cleanup"]["deviceDestroyOk"] is True


def test_json_schema_keyset_and_types(tmp_path: Path) -> None:
    fixture = _fixture(tmp_path)
    for case in ("pass", "fence-timeout", "no-memory-type", "clear-only"):
        raw = subprocess.run([str(fixture), case], capture_output=True, text=True, check=False)
        assert raw.returncode == 0, raw.stderr
        assert raw.stdout.endswith("\n") and raw.stdout.count("\n") == 1
        assert len(raw.stdout.encode("utf-8")) < 64 * 1024
        document = json.loads(raw.stdout)
        assert list(document.keys()) == TOP_LEVEL_KEYS
        assert document["schema"] == "wowforever.android-panvk-triangle"
        assert document["version"] == 1 and isinstance(document["version"], int)
        assert isinstance(document["runId"], str) and len(document["runId"]) == 36
        assert isinstance(document["nativeExit"], int)
        assert isinstance(document["scopeValid"], bool)
        assert isinstance(document["triangleRendered"], bool)
        assert isinstance(document["load"], dict) and set(document["load"].keys()) == {"attempted", "loaded", "dlerror"}
        assert len(document["exports"]) == 3
        assert len(document["functions"]) == 51
        assert [entry["name"] for entry in document["functions"]] == FUNCTION_NAMES
        for entry in document["functions"]:
            assert set(entry.keys()) == {"name", "resolved", "owner", "ownerExact", "error"}
            assert isinstance(entry["resolved"], bool) and isinstance(entry["ownerExact"], bool)
        assert set(document["triangle"].keys()) == set(TRIANGLE_KEYS)
        assert set(document["triangle"]["image"].keys()) == {
            "createAttempted", "created", "handleReturnedOnError", "result",
            "format", "extentWidth", "extentHeight", "usage", "tiling",
            "samples", "mipLevels", "arrayLayers", "sharingMode",
        }
        assert set(document["triangle"]["imageMemory"].keys()) == {
            "requirementsQueried", "requirementsSize", "alignment",
            "memoryTypeBits", "typeSelected", "typeIndex", "propertyFlags",
            "deviceLocal", "allocateAttempted", "allocated",
            "handleReturnedOnError", "allocateResult", "requestedBytes",
            "capacityBytes",
        }
        assert set(document["triangle"]["bind"].keys()) == {"attempted", "ok"}
        assert set(document["triangle"]["view"].keys()) == {
            "createAttempted", "created", "handleReturnedOnError", "result",
        }
        assert set(document["triangle"]["renderPass"].keys()) == {
            "createAttempted", "created", "handleReturnedOnError", "result",
        }
        assert set(document["triangle"]["framebuffer"].keys()) == {
            "createAttempted", "created", "handleReturnedOnError", "result",
        }
        assert set(document["triangle"]["shaders"].keys()) == {
            "vertBytes", "fragBytes", "vertCreateAttempted", "vertCreated",
            "vertResult", "fragCreateAttempted", "fragCreated", "fragResult",
        }
        assert set(document["triangle"]["pipelineLayout"].keys()) == {
            "createAttempted", "created", "result",
        }
        assert set(document["triangle"]["pipeline"].keys()) == {
            "createAttempted", "created", "handleReturnedOnError", "result",
        }
        assert set(document["triangle"]["staging"].keys()) == {
            "bufferCreateAttempted", "bufferCreated", "handleReturnedOnError",
            "bufferResult", "bufferSize", "bufferUsage", "memoryQueried",
            "memorySize", "alignment", "memoryTypeBits", "memoryTypeSelected",
            "typeIndex", "hostVisible", "hostCoherent", "allocateAttempted",
            "allocated", "allocateResult", "requestedBytes", "capacityBytes",
            "bindAttempted", "bindOk", "mapAttempted", "mapped", "nonNull",
        }
        assert set(document["triangle"]["commandPool"].keys()) == {
            "createAttempted", "created", "result", "flags", "queueFamilyIndex",
        }
        assert set(document["triangle"]["commandBuffer"].keys()) == {
            "allocateAttempted", "allocated", "result", "level",
        }
        assert set(document["triangle"]["record"].keys()) == {
            "beginAttempted", "begun", "beginResult", "drawAttempted", "drawOk",
            "drawVertexCount", "copyAttempted", "copyOk", "endAttempted",
            "ended", "endResult",
        }
        assert set(document["triangle"]["fence"].keys()) == {
            "createAttempted", "created", "result", "flags",
        }
        assert set(document["triangle"]["submit"].keys()) == {
            "attempted", "result", "submitCount", "waitAttempted", "waitResult",
            "waited", "timeoutNs", "signaled",
        }
        assert set(document["triangle"]["invalidate"].keys()) == {
            "required", "attempted", "ok", "offset", "size",
        }
        assert set(document["triangle"]["readback"].keys()) == {
            "attempted", "verified", "byteSize", "pixelCount", "clearPixels",
            "trianglePixels", "hashHex",
        }
        for flag in ("apiComplete", "presentation", "d3d", "wow"):
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
    assert document["triangle"]["submit"]["attempted"] is True
    assert document["triangle"]["submit"]["submitCount"] == 1
    assert document["triangle"]["submit"]["waitAttempted"] is True
    assert document["triangle"]["submit"]["timeoutNs"] == 1000000000
    assert document["status"] == "TRIANGLE_OFFSCREEN_OBSERVED"
    timeout_document = _run_case(fixture, "fence-timeout")
    assert timeout_document["status"] == "TIMEOUT"
    assert timeout_document["cleanup"]["complete"] is False


def test_readback_hash_matches_staging_bytes(tmp_path: Path) -> None:
    fixture = _fixture(tmp_path)
    document = _run_case(fixture, "pass")
    clear = bytes((0, 255, 0, 255)) * 2048
    red = bytes((255, 0, 0, 255)) * 2048
    state = 14695981039346656037
    for byte in clear + red:
        state ^= byte
        state = (state * 1099511628211) & 0xFFFFFFFFFFFFFFFF
    assert document["triangle"]["readback"]["hashHex"] == f"{state:016x}"
