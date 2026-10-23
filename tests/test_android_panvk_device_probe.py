"""Offline fake-PFN tests for PanVK control A."""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
SOURCE = ROOT / "scripts/diagnostics/android-panvk/PanvkDeviceQueue.c"
README = ROOT / "scripts/diagnostics/android-panvk/README-device.md"
CONTRACT = ROOT / "scripts/diagnostics/android-panvk/DEVICE-QUEUE-CONTRACT.md"
HEADER_INCLUDE = Path("/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include")


def _compiler() -> str:
    compiler = shutil.which("clang") or shutil.which("cc") or shutil.which("gcc")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    return compiler


def _fixture(tmp_path: Path) -> Path:
    harness = tmp_path / "panvk_device_fixture.c"
    harness.write_text(
        f"""
#define _GNU_SOURCE 1
#define PANVK_DEVICE_QUEUE_HOST_TEST 1
#include <stdio.h>
#include <string.h>
#include "{SOURCE}"
int main(int argc, char **argv) {{
    char output[65536];
    if (argc != 2) return 90;
    if (!strcmp(argv[1], "select")) {{
        char kind[16]; unsigned index = 99;
        return panvk_test_select_queue(1U << 2, 1U, &index, kind, sizeof(kind)) && index == 0U && !strcmp(kind, "transfer") ? 0 : 1;
    }}
    if (!strcmp(argv[1], "order")) {{
        if (!panvk_test_fake_device("pass", output, sizeof(output))) return 1;
        return panvk_test_fake_call_order() ? 0 : 1;
    }}
    if (!strcmp(argv[1], "cleanup-counter")) {{
        if (!panvk_test_fake_device("owner", output, sizeof(output))) return 1;
        return panvk_test_fake_destroy_calls() == 1 ? 0 : 1;
    }}
    if (!strcmp(argv[1], "pass") || !strcmp(argv[1], "create-error") ||
        !strcmp(argv[1], "create-error-nonnull") || !strcmp(argv[1], "count-overflow") ||
        !strcmp(argv[1], "count-partial") || !strcmp(argv[1], "queue-null") ||
        !strcmp(argv[1], "owner") || !strcmp(argv[1], "clock-after-create") ||
        !strcmp(argv[1], "fill-overflow") || !strcmp(argv[1], "zero-queues")) {{
        if (!panvk_test_fake_device(argv[1], output, sizeof(output))) return 1;
        return fputs(output, stdout) == EOF ? 1 : 0;
    }}
    return 91;
}}
""",
        encoding="utf-8",
    )
    executable = tmp_path / "panvk_device_fixture"
    command = [
        _compiler(), "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
        f"-I{HEADER_INCLUDE}", str(harness), "-ldl", "-o", str(executable),
    ]
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    assert result.returncode == 0, result.stdout + result.stderr
    return executable


def test_contract_and_source_boundaries_are_frozen() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    readme = README.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    assert '#include "PanvkPrivateLoader.c"' in source
    assert "vkCreateDevice" in source and "vkGetDeviceQueue" in source
    for forbidden in (
        "vkAllocateMemory", "vkCreateBuffer", "vkCreateCommandPool",
        "vkAllocateCommandBuffers", "vkQueueSubmit", "vkCreateFence",
        "vkDeviceWaitIdle", "vkQueueWaitIdle", "vkCreateShaderModule",
        "vkCreateSwapchain", "eglInitialize", "ioctl(", 'open("/dev/',
    ):
        assert forbidden not in source
    assert "DEVICE_QUEUE_OBSERVED" in readme
    assert "NO_QUEUE" in readme
    assert "<=64" in contract or "64KiB" in contract


def test_fake_pfn_success_has_exact_one_queue_and_bounded_typed_json(tmp_path: Path) -> None:
    fixture = _fixture(tmp_path)
    result = subprocess.run([str(fixture), "select"], check=False)
    assert result.returncode == 0
    assert subprocess.run([str(fixture), "order"], check=False).returncode == 0
    result = subprocess.run([str(fixture), "pass"], capture_output=True, text=True, check=False)
    assert result.returncode == 0
    document = json.loads(result.stdout)
    assert document["status"] == "DEVICE_QUEUE_OBSERVED"
    assert document["nativeExit"] == 0
    assert document["physical"]["vendorId"] == 0x13B5
    assert document["physical"]["deviceId"] == 0xA8670000
    assert document["queues"]["reportedCount"] == document["queues"]["returnedCount"] == 2
    assert document["queues"]["retainedCount"] == len(document["queues"]["families"]) == 2
    assert document["selection"] == {
        "selected": True, "familyIndex": 0, "flags": 1,
        "queueCount": 1, "kind": "graphics",
    }
    assert document["device"]["queueCreateCount"] == 1
    assert document["device"]["queueCount"] == 1
    assert document["device"]["priority"] == 1.0
    assert document["device"]["featuresNull"] is True
    assert document["device"]["layerCount"] == document["device"]["extensionCount"] == 0
    assert document["queue"] == {"getAttempted": True, "index": 0, "nonNull": True, "observed": True}
    assert document["cleanup"]["deviceDestroyOk"] is True
    assert subprocess.run([str(fixture), "cleanup-counter"], check=False).returncode == 0
    assert all(document[name] is False for name in ("apiComplete", "gpuWorkload", "triangle", "presentation", "d3d", "wow"))
    assert len(result.stdout.encode()) < 64 * 1024


@pytest.mark.parametrize("case, status", [
    ("create-error", "VK_ERROR"),
    ("create-error-nonnull", "VK_ERROR"),
    ("count-overflow", "UNKNOWN"),
    ("count-partial", "UNKNOWN"),
    ("queue-null", "UNKNOWN"),
    ("owner", "UNKNOWN"),
    ("clock-after-create", "UNKNOWN"),
    ("fill-overflow", "UNKNOWN"),
    ("zero-queues", "NO_QUEUE"),
])
def test_fake_pfn_adversarial_results_never_claim_success(tmp_path: Path, case: str, status: str) -> None:
    fixture = _fixture(tmp_path)
    result = subprocess.run([str(fixture), case], capture_output=True, text=True, check=False)
    assert result.returncode == 0, result.stderr
    document = json.loads(result.stdout)
    assert document["status"] == status
    assert document["nativeExit"] == 1
    if case == "create-error-nonnull":
        assert document["device"]["handleReturnedOnError"] is True
        assert document["cleanup"]["deviceDestroyAttempted"] is False
    if case in {"count-overflow", "count-partial"}:
        assert document["queues"]["complete"] is False
    if case == "fill-overflow":
        assert document["queues"]["returnedCount"] == 17
        assert document["queues"]["retainedCount"] == 16
        assert len(document["queues"]["families"]) == 16
    if case == "clock-after-create":
        assert document["cleanup"]["deviceDestroyOk"] is True
        assert document["timing"]["valid"] is False
    if case == "zero-queues":
        assert document["queues"]["reportedCount"] == document["queues"]["returnedCount"] == 0
        assert document["queues"]["retainedCount"] == 0
        assert document["queues"]["families"] == []
        assert document["selection"]["selected"] is False
        assert document["device"]["createAttempted"] is False
        assert document["cleanup"]["deviceDestroyAttempted"] is False
    assert document["gpuWorkload"] is False
