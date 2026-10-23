"""Offline C-seam tests for the PanVK private loader-only control."""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
SOURCE = ROOT / "scripts/diagnostics/android-panvk/PanvkPrivateLoader.c"
README = ROOT / "scripts/diagnostics/android-panvk/README-private-loader.md"
RUN_ID = "PVK-0123456789abcdef0123456789abcdef"


def _compiler() -> str:
    compiler = shutil.which("cc") or shutil.which("clang") or shutil.which("gcc")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    return compiler


def _fixture(tmp_path: Path) -> Path:
    harness = tmp_path / "panvk_private_loader_fixture.c"
    harness.write_text(
        f"""
#define _GNU_SOURCE 1
#define PANVK_PRIVATE_LOADER_HOST_TEST 1
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include "{SOURCE}"

int main(int argc, char **argv) {{
    char digest[65];
    char output[16384];
    if (argc != 2) return 90;
    if (!strcmp(argv[1], "sha")) {{
        unsigned char boundary[64];
        unsigned char multiblock[768];
        unsigned index;
        for (index = 0U; index < sizeof(boundary); ++index) boundary[index] = 'a';
        for (index = 0U; index < sizeof(multiblock); ++index) multiblock[index] = (unsigned char)(index % 256U);
        return panvk_test_sha256_hex((const unsigned char *)"", 0U, digest, sizeof(digest)) &&
               !strcmp(digest, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") &&
               panvk_test_sha256_hex(boundary, sizeof(boundary), digest, sizeof(digest)) &&
               !strcmp(digest, "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb") &&
               panvk_test_sha256_hex(multiblock, sizeof(multiblock), digest, sizeof(digest)) &&
               !strcmp(digest, "f3a25aa93aa2fbba28d79260535bbd6a5eb0fc1c24a8b0f04e12b484c1dfe363") ? 0 : 1;
    }}
    if (!strcmp(argv[1], "scope")) {{
        return panvk_test_scope_path(
                   "/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-loader/{RUN_ID}",
                   "{RUN_ID}") &&
               panvk_test_scope_path(
                   "/data/data/com.wowforever.arm64probe/files/forever/controls/panvk-loader/{RUN_ID}",
                   "{RUN_ID}") &&
               !panvk_test_scope_path("/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-loader/RUN", "{RUN_ID}") &&
               !panvk_test_scope_path("/tmp/{RUN_ID}", "{RUN_ID}") ? 0 : 1;
    }}
    if (!strcmp(argv[1], "owner")) {{
        return panvk_test_owner_exact("/private/libvulkan_panfrost.so", "/private/libvulkan_panfrost.so") &&
               !panvk_test_owner_exact("/other/libvulkan_panfrost.so", "/private/libvulkan_panfrost.so") ? 0 : 1;
    }}
    if (!strcmp(argv[1], "pass")) {{
        return panvk_test_encode_fixture(1, 1, 1, 1, NULL, output, sizeof(output)) &&
               fputs(output, stdout) != EOF ? 0 : 1;
    }}
    if (!strcmp(argv[1], "loader-failure")) {{
        return panvk_test_encode_fixture(0, 0, 1, 1, "missing dependency \\\"libprivate.so\\\"", output, sizeof(output)) &&
               fputs(output, stdout) != EOF ? 0 : 1;
    }}
    if (!strcmp(argv[1], "unknown")) {{
        return panvk_test_encode_fixture(1, 0, 1, 0, NULL, output, sizeof(output)) &&
               fputs(output, stdout) != EOF ? 0 : 1;
    }}
    if (!strncmp(argv[1], "adversarial-", 12)) {{
        return panvk_test_encode_adversarial(argv[1] + 12, output, sizeof(output)) &&
               fputs(output, stdout) != EOF ? 0 : 1;
    }}
    if (!strcmp(argv[1], "fallback")) {{
        if (!panvk_test_encode_oversized_fallback(output, sizeof(output))) return 1;
        if (fputs(output, stdout) == EOF) return 1;
        return 1;
    }}
    return 91;
}}
""",
        encoding="utf-8",
    )
    executable = tmp_path / "panvk_private_loader_fixture"
    result = subprocess.run(
        [_compiler(), "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
         "-DPANVK_PRIVATE_LOADER_HOST_TEST", str(harness), "-ldl", "-o", str(executable)],
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    return executable


def test_contract_freezes_loader_only_scope_and_no_entrypoint_calls() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    readme = README.read_text(encoding="utf-8")
    assert '"vk_icdGetInstanceProcAddr"' in source
    assert '"vk_icdGetPhysicalDeviceProcAddr"' in source
    assert '"vk_icdNegotiateLoaderICDInterfaceVersion"' in source
    assert "RTLD_NOW | RTLD_LOCAL" in source
    assert "vkCreateInstance" not in source
    assert "ioctl(" not in source and "open(\"/dev/" not in source
    assert "sigaction" not in source and "ptrace" not in source
    assert "LD_PRELOAD" not in source
    assert "exactly these five" in readme
    assert hashlib.sha256(SOURCE.read_bytes()).hexdigest()


def test_host_seam_sha_scope_owner_and_json_classification(tmp_path: Path) -> None:
    fixture = _fixture(tmp_path)
    assert subprocess.run([str(fixture), "sha"], check=False).returncode == 0
    assert subprocess.run([str(fixture), "scope"], check=False).returncode == 0
    assert subprocess.run([str(fixture), "owner"], check=False).returncode == 0

    passed = subprocess.run([str(fixture), "pass"], check=False, capture_output=True, text=True)
    assert passed.returncode == 0
    document = json.loads(passed.stdout)
    assert set(document) == {
        "schema", "version", "runId", "pid", "uid", "pgid", "sid",
        "monotonicStartNs", "monotonicEndNs", "cwd", "scopeValid", "icdPath",
        "icdBytes", "icdSha256", "icdIdentityBefore", "icdIdentityAfter",
        "icdIdentityStable", "load", "exports", "cleanup", "icdCodeLoaded",
        "constructorsMayHaveExecuted", "vulkanFunctionsInvoked", "apiComplete",
        "vulkan", "gpu", "triangle", "presentation", "d3d", "wow", "status",
        "failureStage", "failureDetail", "nativeExit",
    }
    assert document["status"] == "PRIVATE_ICD_LOADER_OBSERVED"
    assert document["nativeExit"] == 0
    assert document["failureStage"] == "none"
    assert document["icdCodeLoaded"] is True
    assert document["constructorsMayHaveExecuted"] is True
    assert document["vulkanFunctionsInvoked"] is False
    assert all(document[name] is False for name in
               ("apiComplete", "vulkan", "gpu", "triangle", "presentation", "d3d", "wow"))
    assert [item["name"] for item in document["exports"]] == [
        "vk_icdGetInstanceProcAddr",
        "vk_icdGetPhysicalDeviceProcAddr",
        "vk_icdNegotiateLoaderICDInterfaceVersion",
    ]
    assert len(passed.stdout.encode("utf-8")) < 16 * 1024

    failed = subprocess.run([str(fixture), "loader-failure"], check=False, capture_output=True, text=True)
    assert failed.returncode == 0
    failure = json.loads(failed.stdout)
    assert failure["status"] == "LOADER_FAILED"
    assert failure["nativeExit"] == 1
    assert failure["load"] == {
        "attempted": True,
        "loaded": False,
        "dlerror": 'missing dependency "libprivate.so"',
    }
    assert failure["constructorsMayHaveExecuted"] is True
    assert failure["icdCodeLoaded"] is False
    assert failure["cleanup"] == {
        "dlcloseAttempted": False,
        "dlcloseOk": False,
        "complete": True,
        "errno": 0,
        "dlerror": "",
    }

    unknown = subprocess.run([str(fixture), "unknown"], check=False, capture_output=True, text=True)
    assert unknown.returncode == 0
    unknown_document = json.loads(unknown.stdout)
    assert unknown_document["status"] == "UNKNOWN"
    assert unknown_document["nativeExit"] == 1
    assert unknown_document["cleanup"]["complete"] is False

    for case in (
        "invalid-start", "invalid-end", "reversed-time", "contradictory-cleanup",
        "stable-false", "wrong-hash", "missing-identity", "loader-failure-unstable",
    ):
        result = subprocess.run([str(fixture), f"adversarial-{case}"], check=False,
                                capture_output=True, text=True)
        assert result.returncode == 0
        adversarial = json.loads(result.stdout)
        assert adversarial["status"] == "UNKNOWN", case
        assert adversarial["nativeExit"] == 1, case

    loader_complete = subprocess.run(
        [str(fixture), "adversarial-loader-failure-complete"],
        check=False, capture_output=True, text=True,
    )
    assert loader_complete.returncode == 0
    loader_document = json.loads(loader_complete.stdout)
    assert loader_document["status"] == "LOADER_FAILED"
    assert loader_document["nativeExit"] == 1
    assert loader_document["cleanup"]["complete"] is True
    assert loader_document["cleanup"]["dlcloseAttempted"] is False
    assert loader_document["cleanup"]["dlcloseOk"] is False

    fallback = subprocess.run([str(fixture), "fallback"], check=False, capture_output=True, text=True)
    assert fallback.returncode == 1
    fallback_document = json.loads(fallback.stdout)
    assert set(fallback_document) == set(document)
    assert fallback_document["status"] == "UNKNOWN"
    assert fallback_document["nativeExit"] == 1
    assert fallback_document["failureStage"] == "output"
    assert fallback_document["scopeValid"] is False
    assert all(not item["found"] and not item["ownerExact"] and item["owner"] == "" for item in fallback_document["exports"])
    assert fallback_document["runId"] == RUN_ID
    assert fallback_document["pid"] == 7
    assert fallback_document["monotonicStartNs"] == 1
    assert fallback_document["monotonicEndNs"] == 2
    assert fallback_document["load"] == {"attempted": True, "loaded": True, "dlerror": ""}
    assert fallback_document["icdCodeLoaded"] is True
    assert fallback_document["constructorsMayHaveExecuted"] is True
    assert fallback_document["cleanup"]["dlcloseAttempted"] is True
    assert fallback_document["cleanup"]["dlcloseOk"] is True
    assert fallback_document["cleanup"]["complete"] is True
    assert len(fallback.stdout.encode("utf-8")) <= 16 * 1024
