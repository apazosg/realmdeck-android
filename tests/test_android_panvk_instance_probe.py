"""Offline C-seam tests for the PanVK instance metadata control."""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
SOURCE = ROOT / "scripts/diagnostics/android-panvk/PanvkInstanceMetadata.c"
README = ROOT / "scripts/diagnostics/android-panvk/README-instance.md"
HELPER = ROOT / "scripts/diagnostics/android-panvk/PanvkPrivateLoader.c"
HEADER_INCLUDE = Path("/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include")


def _compiler() -> str:
    compiler = shutil.which("clang") or shutil.which("cc") or shutil.which("gcc")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    return compiler


def _fixture(tmp_path: Path) -> Path:
    harness = tmp_path / "panvk_instance_fixture.c"
    harness.write_text(
        f"""
#define _GNU_SOURCE 1
#define PANVK_INSTANCE_METADATA_HOST_TEST 1
#include <stdio.h>
#include <string.h>
#include "{SOURCE}"
int main(int argc, char **argv) {{
    char output[65536];
    if (argc != 2) return 90;
    if (!strcmp(argv[1], "pass")) {{
        if (!panvk_test_encode_fixture(output, sizeof(output))) return 1;
        return fputs(output, stdout) == EOF ? 1 : 0;
    }}
    if (!strcmp(argv[1], "create-fails") || !strcmp(argv[1], "enumerate-fails"))
        return panvk_test_fake_lifecycle(argv[1]) ? 0 : 1;
    if (!strcmp(argv[1], "bool")) return panvk_test_valid_bool(0) && panvk_test_valid_bool(1) && !panvk_test_valid_bool(2) ? 0 : 1;
    if (!strcmp(argv[1], "count")) return panvk_test_reject_count(8) && !panvk_test_reject_count(9) ? 0 : 1;
    if (!strcmp(argv[1], "incomplete")) return panvk_test_reject_incomplete(0, 2, 2) && !panvk_test_reject_incomplete(5, 2, 2) && !panvk_test_reject_incomplete(0, 2, 1) ? 0 : 1;
    if (!strcmp(argv[1], "owner")) return panvk_test_instance_owner("/icd", "/icd") && !panvk_test_instance_owner("/other", "/icd") ? 0 : 1;
    if (!strncmp(argv[1], "case-", 5)) {{
        if (!panvk_test_encode_case(argv[1] + 5, output, sizeof(output))) return 1;
        return fputs(output, stdout) == EOF ? 1 : 0;
    }}
    if (!strcmp(argv[1], "feature-chain")) {{
        DeviceMetadata device;
        memset(&device, 0, sizeof(device));
        device.features2_queried = 1;
        init_feature_chain(&device, VK_API_VERSION_1_3);
        if (!validate_features2(&device)) return 81;
        device.vulkan13.synchronization2 = 2;
        if (validate_features2(&device)) return 82;
        memset(&device, 0, sizeof(device));
        device.features2_queried = 1;
        init_feature_chain(&device, VK_API_VERSION_1_1);
        if (device.features2.pNext != &device.shader_draw_parameters ||
            device.shader_draw_parameters.sType != VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_DRAW_PARAMETERS_FEATURES ||
            device.has_vulkan12) return 83;
        device.shader_draw_parameters.shaderDrawParameters = 1;
        if (!validate_features2(&device)) return 84;
        device.shader_draw_parameters.shaderDrawParameters = 2;
        if (validate_features2(&device)) return 85;
        return 0;
    }}
    if (!strcmp(argv[1], "partial-classification")) {{
        InstanceResult result;
        init_result(&result, "PVK-0123456789abcdef0123456789abcdef");
        result.load_loaded = result.scope_valid = result.binding_valid = 1;
        result.identity_stable = result.clock_valid = result.cleanup_complete = 1;
        result.physical_count = result.physical_retained = result.device_count = 1;
        result.physical_complete = 1;
        result.devices[0].properties_valid = result.devices[0].features_valid = 1;
        result.devices[0].extensions_valid = result.devices[0].features2_valid = 1;
        result.devices[0].extensions_complete = 0;
        classify_instance(&result);
        return !strcmp(result.status, "UNKNOWN") && result.native_exit == 1 ? 0 : 86;
    }}
    if (!strcmp(argv[1], "output-prefix")) {{
        InstanceResult result;
        init_result(&result, "PVK-0123456789abcdef0123456789abcdef");
        result.status = "PHYSICAL_METADATA_OBSERVED";
        result.physical_count = result.physical_returned = result.physical_retained = result.device_count = 8;
        result.physical_complete = 1;
        for (unsigned i = 0; i < 8; ++i) {{
            result.devices[i].extension_count = result.devices[i].extension_retained_count = 64;
            for (unsigned j = 0; j < 64; ++j) {{
                memset(result.devices[i].extension_names[j], 'A', 180);
                result.devices[i].extension_names[j][180] = 0;
            }}
        }}
        if (!encode_bounded_instance_result(&result, output, sizeof(output))) return 87;
        if (result.device_count == 0 || result.device_count >= 8) return 88;
        return fputs(output, stdout) == EOF ? 1 : 0;
    }}
    return 91;
}}
""",
        encoding="utf-8",
    )
    executable = tmp_path / "panvk_instance_fixture"
    command = [_compiler(), "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-Wno-format-truncation",
               f"-I{HEADER_INCLUDE}", str(harness), "-ldl", "-o", str(executable)]
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    assert result.returncode == 0, result.stdout + result.stderr
    return executable


def test_source_freezes_instance_scope_and_prohibited_call_graph() -> None:
    source = SOURCE.read_text(encoding="utf-8") + HELPER.read_text(encoding="utf-8")
    readme = README.read_text(encoding="utf-8")
    for export in ("vk_icdGetInstanceProcAddr", "vk_icdGetPhysicalDeviceProcAddr", "vk_icdNegotiateLoaderICDInterfaceVersion"):
        assert export in source
    for allowed in ("vkCreateInstance", "vkDestroyInstance", "vkEnumeratePhysicalDevices", "vkGetPhysicalDeviceProperties", "vkGetPhysicalDeviceFeatures", "vkEnumerateDeviceExtensionProperties", "vkGetPhysicalDeviceFeatures2"):
        assert allowed in source
    for forbidden in ("vkCreateDevice", "vkGetDeviceQueue", "vkAllocateMemory", "vkQueueSubmit", "vkCreateShaderModule", "vkCreateSwapchain", "eglInitialize", "ioctl(", "open(\"/dev/"):
        assert forbidden not in source
    assert "ZERO_DEVICES" in readme
    assert "features2" in readme


def test_fake_pfn_lifecycle_validation_and_bounded_native_json(tmp_path: Path) -> None:
    fixture = _fixture(tmp_path)
    for case in ("create-fails", "enumerate-fails", "bool", "count", "incomplete", "owner", "feature-chain", "partial-classification"):
        assert subprocess.run([str(fixture), case], check=False).returncode == 0
    result = subprocess.run([str(fixture), "pass"], capture_output=True, text=True, check=False)
    assert result.returncode == 0
    document = json.loads(result.stdout)
    assert document["status"] == "PHYSICAL_METADATA_OBSERVED"
    assert document["nativeExit"] == 0
    assert document["cleanup"] == {"destroyAttempted": True, "destroyOk": True, "dlcloseAttempted": True, "dlcloseOk": True, "complete": True}
    assert all(document[name] is False for name in ("apiComplete", "gpuWorkload", "triangle", "presentation", "d3d", "wow"))
    assert len(result.stdout.encode()) < 64 * 1024
    bounded = subprocess.run([str(fixture), "output-prefix"], capture_output=True, text=True, check=False)
    assert bounded.returncode == 0
    prefix = json.loads(bounded.stdout)
    assert prefix["status"] == "UNKNOWN" and prefix["nativeExit"] == 1
    assert prefix["physicalDevices"]["complete"] is False
    assert prefix["physicalDevices"]["reportedCount"] == 8
    assert 0 < prefix["physicalDevices"]["retainedCount"] == len(prefix["physicalDevices"]["devices"]) < 8
    assert len(bounded.stdout.encode()) < 64 * 1024


def test_production_encoder_emits_typed_partial_and_error_witnesses(tmp_path: Path) -> None:
    fixture = _fixture(tmp_path)
    for case in ("partial-devices", "extension-overflow", "extension-incomplete", "vk-error", "api-invalid", "api-variant", "create-error-nonnull"):
        result = subprocess.run([str(fixture), f"case-{case}"], capture_output=True, text=True, check=False)
        assert result.returncode == 0, case
        document = json.loads(result.stdout)
        assert set(document) == {
            "schema", "version", "runId", "pid", "uid", "pgid", "sid", "monotonicStartNs", "monotonicEndNs", "cwd", "scopeValid", "icdPath", "icdBytes", "icdSha256", "icdIdentityBefore", "icdIdentityAfter", "icdIdentityStable", "load", "exports", "interface", "functions", "api", "instance", "physicalDevices", "cleanup", "icdCodeLoaded", "constructorsMayHaveExecuted", "vulkanFunctionsInvoked", "apiComplete", "gpuWorkload", "triangle", "presentation", "d3d", "wow", "status", "failureStage", "failureDetail", "nativeExit",
        }
        physical = document["physicalDevices"]
        assert physical["capacity"] == 8 and physical["retainedCount"] == len(physical["devices"])
        assert all(isinstance(value, (bool, type(None))) for value in (
            document["api"]["ownerExact"], document["cleanup"]["complete"],
        ))
        if case == "partial-devices":
            assert physical["reportedCount"] == 2 and physical["returnedCount"] == 1 and physical["complete"] is False
        if case == "extension-overflow":
            device = physical["devices"][0]
            assert device["extensionReportedCount"] == 257 and device["extensionRetainedCount"] == 0
        if case == "create-error-nonnull":
            assert document["instance"]["result"] < 0 and document["cleanup"]["destroyAttempted"] is False
