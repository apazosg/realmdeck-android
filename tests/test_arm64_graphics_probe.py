"""Offline contract checks for the bounded Windows AA64 graphics probe."""

import json
import re
from pathlib import Path


ROOT = Path(__file__).parents[1]
PROBE = ROOT / "scripts/diagnostics/arm64-graphics"
SOURCE = PROBE / "GraphicsProbe.c"
BUILD = PROBE / "build.sh"
READBACK_BUILD = PROBE / "build-readback.sh"


def test_probe_source_is_bounded_and_uses_the_fixed_contract() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    assert "GraphicsProbe-ARM64" in source
    assert '"--log"' in source
    assert '"--run-id"' in source
    assert "A:\\\\Logs\\\\%s-graphics-probe.jsonl" in source
    assert "MAX_FRAMES 30" in source
    assert "30000" in source
    assert "CreateWindowExW" in source
    assert "D3D11CreateDevice" in source
    assert "D3D_FEATURE_LEVEL_11_1" in source
    assert "D3D_DRIVER_TYPE_UNKNOWN" in source
    assert "D3D_DRIVER_TYPE_HARDWARE" in source
    assert "vkGetInstanceProcAddr" in source
    assert "winevulkan.dll" in source
    assert "vulkan-1.dll" in source
    assert "shaderDrawParameters" in source
    assert "samplerMirrorClampToEdge" in source
    assert "shaderDemoteToHelperInvocation" in source
    assert "synchronization2" in source
    assert "dynamicRendering" in source
    assert "robustBufferAccess2" in source
    assert "exact-header-definitions" in source


def test_probe_does_not_add_game_or_network_surface() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    assert "WinHttp" not in source
    assert "URLDownload" not in source
    assert "CreateProcess" not in source
    assert "WowB" not in source
    assert "VirtualAlloc" not in source


def test_compiler_load_captures_errors_before_later_win32_calls() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    load = source.index("compiler.handle = LoadLibraryW(compiler.requested);")
    capture = source.index("compiler.load_error = GetLastError();", load)
    proc = source.index('compiler.captured_proc = GetProcAddress(compiler.handle, "D3DCompile");', load)
    proc_capture = source.index("compiler.proc_error = GetLastError();", proc)
    record = source.index("record_module(module0", load)
    assert capture < record
    assert proc < proc_capture < record
    assert "if (compiler.handle == NULL)" in source
    assert "procError is null" in source
    assert "compile_shader = (ProbeD3DCompile)compiler_module->captured_proc;" in source
    assert "load_proc(compiler_module->handle" not in source


def test_compiler_load_event_has_fixed_nullable_error_schema() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    assert 'log_event(&log, "compiler_load", compiler_fields)' in source
    assert '"moduleName\\":\\"d3dcompiler_47.dll\\"' in source
    assert '"loaded\\":%s,\\"getProcFound\\":%s,\\"loadError\\":%s,\\"procError\\":%s' in source
    assert 'if (!valid) strcpy(out, "null");' in source


def test_build_recipe_is_strict_and_manifest_is_external() -> None:
    build = BUILD.read_text(encoding="utf-8")
    assert "aarch64-w64-mingw32-clang" in build
    assert "-Wall -Wextra -Werror" in build
    assert "llvm-readobj" in build
    assert "GraphicsProbe-ARM64.exe" in build
    assert "graphics-probe-compiler-log-v2" in build
    assert 'controls/graphics-probe"' not in build
    assert "/home/adrian/wowforever-artifacts/arm64-prototype" in build
    assert "PROBE_SCHEMA 1" in SOURCE.read_text(encoding="utf-8")
    assert not (PROBE / "manifest.json").exists()


def test_jsonl_event_names_and_manifest_shape_are_stable() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    for event in ("probe_begin", "compiler_load", "modules", "vulkan_caps", "d3d_attempt", "device_created", "shader", "swapchain", "present", "probe_end"):
        assert f'"{event}"' in source
    assert re.search(r"machine.?[=:\\]183", '{"machine":183}')
    assert json.loads('{"schema":1,"event":"probe_end","runId":"RUN_1"}')['schema'] == 1


def test_instance_feature_queries_match_api_and_extensions_are_bounded() -> None:
    source = SOURCE.read_text()
    assert "application_info.apiVersion = VK_API_VERSION_1_3" in source
    assert "VkPhysicalDeviceShaderDrawParametersFeatures" in source
    assert "reported_extension_count <= MAX_EXTENSIONS" in source
    assert "bool_text(state->extensions_complete" in source
    assert "index < MAX_ADAPTERS && within_budget()" in source


def test_presentation_requires_pixels_and_all_real_presents_not_occlusion() -> None:
    source = SOURCE.read_text()
    assert "D3D11_USAGE_STAGING" in source and "D3D11_MAP_READ" in source
    assert "colored >= 128" in source and '"triangle_readback"' in source
    assert "if (hr != S_OK) { present_failed = 1; break; }" in source
    assert "presents == MAX_FRAMES && removed_reason == S_OK" in source
    assert "CloseHandle(log.file)" in source and "log.output_ok ? 0 : 1" in source
    assert "GetModuleFileNameW(module->handle" in source


def test_readback_variant_compares_offscreen_and_backbuffer_without_changing_legacy_default() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    build = READBACK_BUILD.read_text(encoding="utf-8")
    assert "#define READBACK_VARIANT 0" in source
    assert "readback_resource" in source
    for phase in ("offscreen_clear", "offscreen_draw", "backbuffer_clear", "backbuffer_draw"):
        assert f'"{phase}"' in source
    assert "modules-d3d-shaders-ready" in source
    assert "D3D11_USAGE_STAGING" in source and "D3D11_MAP_READ" in source
    assert "int copy_submitted = 0;" in source
    assert "copy_submitted = 1;" in source
    assert 'copy_submitted ? "VOID_SUBMITTED" : "NOT_ATTEMPTED"' in source
    assert '"status\\\":\\\"TIMEOUT' in source
    assert "-DREADBACK_VARIANT=1" in build
    assert "GraphicsProbeReadback-ARM64.exe" in build
    assert '"variant": "readback-v1"' in build


def test_readback_marker_and_pause_are_bounded() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    assert '\\"maxPauseMs\\":5000' in source
    assert "pause_budget = pause_start - probe_started < MAX_PROBE_MS" in source
    assert "pause_ms = (DWORD)(pause_budget < 5000 ? pause_budget : 5000)" in source
    assert "pauseRequestedMs" in source and "pauseElapsedMs" in source and "pauseOverrun" in source
    assert "if (!within_budget()) goto cleanup;" in source


def test_readback_cannot_pass_after_a_blocking_call_overruns_the_budget() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    assert 'log_event(log, "probe_budget"' in source
    assert "removed_reason == S_OK && within_budget();" in source
    assert "Not queried: never claim device removal from a timeout" in source
