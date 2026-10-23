"""Offline fake-PFN and policy tests for the native Android WSI control."""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
SOURCE = ROOT / "scripts/diagnostics/android-panvk/PanvkWsi.c"
README = ROOT / "scripts/diagnostics/android-panvk/README-wsi.md"
BUILD = ROOT / "scripts/diagnostics/android-panvk/build_wsi.sh"
INCLUDE = Path("/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include")
SPIRV = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/panvk-triangle-v1")

RUN = "PWSI-0123456789abcdef0123456789abcdef"
DIR = f"/data/data/com.wowforever.arm64probe/files/forever/controls/panvk-wsi/{RUN}"
ICD = f"{DIR}/libvulkan_panfrost.so"
JOURNAL = f"{DIR}/native.jsonl"


def _compiler() -> str:
    compiler = shutil.which("cc") or shutil.which("clang") or shutil.which("gcc")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    return compiler


def _fixture(tmp_path: Path) -> Path:
    harness = tmp_path / "panvk_wsi_fixture.c"
    harness.write_text(
        f"""
#define _GNU_SOURCE 1
#define PANVK_WSI_HOST_TEST 1
#include <stdio.h>
#include <string.h>
#include "{SOURCE}"
int main(int argc, char **argv) {{
    char json[65536];
    if (argc != 2) return 90;
    if (!strcmp(argv[1], "sha")) {{
        Sha256 hash; unsigned char data[65536], digest[32]; size_t count; sha_init(&hash);
        while ((count = fread(data, 1, sizeof(data), stdin)) != 0) sha_update(&hash, data, count);
        if (ferror(stdin)) return 1;
        sha_final(&hash, digest);
        for (size_t i=0; i<32; ++i) printf("%02x", digest[i]);
        return 0;
    }}
    if (!strcmp(argv[1], "generation")) return panvk_wsi_test_generation(19) && panvk_wsi_test_generation(7) ? 0 : 1;
    if (!strcmp(argv[1], "oneuse")) return panvk_wsi_test_one_use() && !panvk_wsi_test_one_use() ? 0 : 1;
    if (!strcmp(argv[1], "paths")) return panvk_wsi_test_path("{JOURNAL}", "{ICD}", "{RUN}") &&
        !panvk_wsi_test_path("{DIR}/link", "{ICD}", "{RUN}") &&
        !panvk_wsi_test_path("{JOURNAL}", "{DIR}/other.so", "{RUN}") ? 0 : 1;
    if (!strcmp(argv[1], "canonical-alias")) {{
        const char *canonical = "/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-wsi/{RUN}";
        const char *canonical_icd = "/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-wsi/{RUN}/libvulkan_panfrost.so";
        return canonical_scope_valid("{DIR}", canonical, "{ICD}", canonical_icd, "{RUN}") &&
            !canonical_scope_valid("{DIR}", "/data/user/0/other", "{ICD}", canonical_icd, "{RUN}") &&
            !canonical_scope_valid("{DIR}", canonical, "{ICD}", "/outside/libvulkan_panfrost.so", "{RUN}") ? 0 : 1;
    }}
    if (!strcmp(argv[1], "window")) return panvk_wsi_test_window(320, 240) && !panvk_wsi_test_window(321, 240) ? 0 : 1;
    if (!strcmp(argv[1], "bounds")) return panvk_wsi_test_bounds(256, 32, 16, 4) &&
        !panvk_wsi_test_bounds(257, 32, 16, 4) && !panvk_wsi_test_bounds(256, 33, 16, 4) ? 0 : 1;
    if (!strcmp(argv[1], "queue")) return panvk_wsi_test_queue(VK_QUEUE_GRAPHICS_BIT, 1) &&
        !panvk_wsi_test_queue(VK_QUEUE_TRANSFER_BIT, 1) && !panvk_wsi_test_queue(VK_QUEUE_GRAPHICS_BIT, 0) ? 0 : 1;
    if (!strcmp(argv[1], "pfns")) return panvk_wsi_test_fake_pfns() && panvk_wsi_test_present_dependency() &&
        panvk_wsi_test_error_handle() && panvk_wsi_test_cleanup_guard() && panvk_wsi_test_cancel_submitted() &&
        panvk_wsi_test_null_error_safe() && panvk_wsi_test_partial_destroy_safe() && panvk_wsi_test_pfn_dedup() ? 0 : 1;
    if (!strcmp(argv[1], "lifecycle")) return panvk_wsi_test_three_frames() ? 0 : 1;
    if (!strcmp(argv[1], "journal-stop")) return panvk_wsi_test_journal_stop() ? 0 : 1;
    if (!strcmp(argv[1], "journal-capacity")) return panvk_wsi_test_journal_capacity() ? 0 : 1;
    if (!strcmp(argv[1], "resize")) return panvk_wsi_test_resize() ? 0 : 1;
    if (!strcmp(argv[1], "cancel-after-submit")) return panvk_wsi_test_cancel_after_submit() ? 0 : 1;
    if (!strcmp(argv[1], "idle-failure")) return panvk_wsi_test_idle_failure_retains_window() ? 0 : 1;
    if (!strcmp(argv[1], "setup-journal-failure")) return panvk_wsi_test_setup_journal_failure_cleanup() ? 0 : 1;
    if (!strcmp(argv[1], "swapchain-count")) return panvk_wsi_test_swapchain_count() ? 0 : 1;
    if (!strcmp(argv[1], "json")) {{
        if (!panvk_wsi_test_encode(json, sizeof(json))) return 1;
        fputs(json, stdout); return 0;
    }}
    if (!strcmp(argv[1], "negative-journal")) {{
        if (!panvk_wsi_test_negative_json(json, sizeof(json), 1)) return 1;
        fputs(json, stdout); return 0;
    }}
    if (!strcmp(argv[1], "negative-capacity")) {{
        if (!panvk_wsi_test_negative_json(json, sizeof(json), 2)) return 1;
        fputs(json, stdout); return 0;
    }}
    if (!strcmp(argv[1], "negative-frame")) {{
        if (!panvk_wsi_test_negative_json(json, sizeof(json), 3)) return 1;
        fputs(json, stdout); return 0;
    }}
    if (!strcmp(argv[1], "negative-load")) {{
        if (!panvk_wsi_test_negative_json(json, sizeof(json), 4)) return 1;
        fputs(json, stdout); return 0;
    }}
    return 91;
}}
""",
        encoding="utf-8",
    )
    executable = tmp_path / "panvk_wsi_fixture"
    result = subprocess.run(
        [_compiler(), "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
         "-Wno-unused-function", "-D_GNU_SOURCE", "-DPANVK_WSI_HOST_TEST",
         f"-I{INCLUDE}", f"-I{SPIRV}", str(harness), "-ldl", "-o", str(executable)],
        capture_output=True, text=True, check=False,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    return executable


def test_native_source_contract_is_narrow() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    readme = README.read_text(encoding="utf-8")
    build = BUILD.read_text(encoding="utf-8")
    assert "Java_app_gamenative_PanvkWsiBridge_nativeRun" in source
    assert "Java_app_gamenative_PanvkWsiBridge_nativeCancel" in source
    assert "ANativeWindow_fromSurface" in source
    assert "g_started_generation" in source
    assert '\\"result\\":null' in source
    assert "write_result" not in source
    assert "native.jsonl" in source and "result.json" not in source
    assert '"VK_KHR_surface"' not in source  # extension comes from the pinned header constant
    assert "VK_KHR_SURFACE_EXTENSION_NAME" in source
    assert "VK_KHR_ANDROID_SURFACE_EXTENSION_NAME" in source
    assert 'resolve_instance(&c->fn,VK_NULL_HANDLE,"vkDestroyInstance"' not in source
    assert 'resolve_instance(&c->fn,c->instance,"vkDestroyInstance"' in source
    assert "VK_PRESENT_MODE_FIFO_KHR" in source
    assert "UINT64_C(500000000)" in source and "UINT64_C(1000000000)" in source
    assert "VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT" in source
    assert "VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT" in source
    for forbidden in ("vkDeviceWaitIdle", "vkResetDevice", "eglInitialize", "ANativeWindow_lock", "Canvas"):
        assert forbidden not in source
    assert "libpanvk_wsi_probe.so" in readme and "androidPresentationObserved" in readme
    assert "-Werror" in build and "-fPIC" in build and "libandroid.so" in build


def test_actual_sha_encoder_matches_standard_and_pinned_icd(tmp_path: Path) -> None:
    import hashlib
    fixture = _fixture(tmp_path)
    for data in (b"", b"abc", b"x"*55, b"x"*56, b"x"*63, b"x"*64, b"x"*100000):
        result = subprocess.run([str(fixture), "sha"], input=data, capture_output=True, check=True)
        assert result.stdout.decode() == hashlib.sha256(data).hexdigest()
    icd = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/panvk-audit-beta17/icd/libvulkan_panfrost.so")
    with icd.open("rb") as stream:
        result = subprocess.run([str(fixture), "sha"], stdin=stream, capture_output=True, check=True)
    assert result.stdout.decode() == "5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9"


def test_fake_pfns_window_ownership_and_generation(tmp_path: Path) -> None:
    fixture = _fixture(tmp_path)
    for case in ("generation", "oneuse", "paths", "canonical-alias", "window", "bounds", "queue", "pfns",
                 "lifecycle", "journal-stop", "journal-capacity", "resize",
                  "cancel-after-submit", "idle-failure", "setup-journal-failure", "swapchain-count"):
        result = subprocess.run([str(fixture), case], check=False)
        assert result.returncode == 0, case


def test_json_encoder_reports_real_api_results_and_false_visual_claim(tmp_path: Path) -> None:
    fixture = _fixture(tmp_path)
    result = subprocess.run([str(fixture), "json"], capture_output=True, text=True, check=False)
    assert result.returncode == 0
    document = json.loads(result.stdout)
    assert document["schema"] == "wowforever.android-panvk-wsi"
    assert document["version"] == 1
    assert document["generation"] == 12
    assert document["scopeValid"] is True
    assert document["loadAttempted"] is True
    assert document["loadLoaded"] is True
    assert document["surface"] == {
        "fromSurface": False,
        "windowOwned": False,
        "width": 320,
        "height": 240,
        "createResult": 0,
        "created": False,
    }
    assert document["swapchain"]["imageCount"] == 2
    assert document["frames"]["count"] == 3
    assert document["apiPresentSuccess"] is True
    assert document["counts"]["presentSuccess"] == 3
    assert document["androidPresentationObserved"] is False
    assert all(document[name] is False for name in ("wine", "d3d", "wow"))
    assert document["status"] == "API_PRESENT_COMPLETED"
    assert len(result.stdout.encode("utf-8")) < 64 * 1024

    for case, stage in (("negative-journal", "journal"), ("negative-capacity", "journal")):
        negative = subprocess.run([str(fixture), case], capture_output=True, text=True, check=False)
        assert negative.returncode == 0
        failed = json.loads(negative.stdout)
        assert failed["status"] == "UNKNOWN"
        assert failed["failureStage"] == stage
        assert failed["nativeExit"] == 1
    negative_frame = subprocess.run(
        [str(fixture), "negative-frame"], capture_output=True, text=True, check=False
    )
    assert negative_frame.returncode == 0
    failed_frame = json.loads(negative_frame.stdout)
    first = failed_frame["frames"]["items"][0]
    assert failed_frame["status"] == "VK_ERROR"
    assert failed_frame["apiPresentSuccess"] is False
    assert first["acquireCalled"] is True
    assert first["acquireResult"] == -4  # VK_ERROR_DEVICE_LOST
    assert first["submitCalled"] is False
    assert first["submitResult"] is None
    assert first["presentCalled"] is False
    assert first["presentResult"] is None
    negative_load = subprocess.run(
        [str(fixture), "negative-load"], capture_output=True, text=True, check=False
    )
    assert negative_load.returncode == 0
    failed_load = json.loads(negative_load.stdout)
    assert failed_load["scopeValid"] is True
    assert failed_load["loadAttempted"] is True
    assert failed_load["loadLoaded"] is False
    assert failed_load["status"] == "VK_ERROR"
