"""Focused offline checks for the Android negotiation-trace build variant."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).parents[1]
BUILD_DIR = ROOT / "scripts/arm64-prototype/controls/virgl-build"
sys.path.insert(0, str(BUILD_DIR))
import build_diagnostic as recipe  # noqa: E402


def _patched_source(tmp_path: Path) -> tuple[Path, dict]:
    source = recipe._extract_source(tmp_path)
    record = recipe._apply_patch(source, tmp_path / "patch.log", "android-negotiation")
    return source, record


def test_negotiation_profile_inherits_android_default_without_mutating_it() -> None:
    default = recipe.PROFILES["android-default"]
    negotiation = recipe.PROFILES["android-negotiation"]

    assert default.workspace.name == "vtest-android-default-v2"
    assert default.candidate_name == "candidate_android_default_v2_verified"
    assert default.compatibility_defines == ("WFVTEST_ANDROID_DEFAULT=1",)
    assert len(recipe.ANDROID_DEFAULT_PATCHES) == 13
    assert negotiation.workspace == recipe.ANDROID_NEGOTIATION_WORKSPACE
    assert negotiation.workspace.name == "vtest-android-negotiation-v3"
    assert negotiation.candidate_name == "candidate_android_negotiation_verified"
    assert negotiation.functional_egl_fix is True
    assert negotiation.compatibility_defines == (
        "WFVTEST_ANDROID_DEFAULT=1",
        "WFVTEST_NEGOTIATION_TRACE=1",
    )
    assert len(recipe.ANDROID_NEGOTIATION_PATCHES) == 14
    assert recipe.ANDROID_NEGOTIATION_PATCHES[:-1] == recipe.ANDROID_DEFAULT_PATCHES
    assert recipe.ANDROID_NEGOTIATION_PATCH.name == "virglrenderer-1.3.0-wfvtest-negotiation-v1.patch"


def test_fresh_android_negotiation_patch_applies_all_fourteen_without_fuzz(tmp_path: Path) -> None:
    source, record = _patched_source(tmp_path)
    declared = [item[1] for item in recipe.ANDROID_NEGOTIATION_PATCHES]

    assert record["declaredPatchIds"] == declared
    assert len(record["patches"]) == 14
    assert record["fuzz"] == 0
    assert all(command[0] == "/usr/bin/patch" and "--fuzz=0" in command for command in record["commands"])
    assert record["patchTool"]["sha256"] == recipe.PATCH_TOOL_SHA256
    assert set(record["patchTool"]["environment"]) == {"PATH", "HOME", "TMPDIR", "LC_ALL", "LANG", "TZ"}
    assert not list(source.rglob("*.rej"))
    assert not list(source.rglob("*.orig"))
    assert "WFVTEST_NEGOTIATION_TRACE" in (source / "vtest/vtest_renderer.c").read_text()


def test_negotiation_hooks_preserve_protocol_values_and_log_only_metadata(tmp_path: Path) -> None:
    source, _record = _patched_source(tmp_path)
    renderer = (source / "vtest/vtest_renderer.c").read_text(encoding="utf-8")
    server = (source / "vtest/vtest_server.c").read_text(encoding="utf-8")
    patch = recipe.ANDROID_NEGOTIATION_PATCH.read_text(encoding="utf-8")

    assert "wfvtest_negotiation_command(header[VTEST_CMD_LEN], header[VTEST_CMD_ID]);" in server
    assert "wfvtest_negotiation_protocol(version_buf[VCMD_PROTOCOL_VERSION_VERSION], ctx->protocol_version);" in renderer
    assert "wfvtest_negotiation_capset_request(id, version, max_version, max_size," in renderer
    assert "wfvtest_negotiation_capset_reply(2, 1, max_size, true, caps_buf);" in renderer
    assert "wfvtest_negotiation_capset_reply(1, 1, max_size, true, caps_buf);" in renderer
    assert "resp_buf[VTEST_CMD_ID] = VCMD_GET_CAPSET;" in renderer
    assert "hdr_buf[1] = 2;" in renderer and "hdr_buf[1] = 1;" in renderer
    assert "WFVTEST_NEGOTIATION_TRACE" in patch
    assert "eglGetError" not in patch
    assert "virgl_fill_caps" not in patch
    assert "eglGetDisplay" not in patch
    assert not re.search(r"/home/|0x[0-9a-fA-F]+|argv|client.*string|payload", patch, re.IGNORECASE)
    assert "fopen(" not in patch
    assert "socket(" not in patch
    assert "signal(" not in patch


def test_trace_json_contract_is_bounded_and_has_actual_event_keysets() -> None:
    patch = recipe.ANDROID_NEGOTIATION_PATCH.read_text(encoding="utf-8")
    prefix = "WFVTEST_NEGOTIATION "
    patch_json = patch.replace('\\"', '"')
    lines = [line for line in patch_json.splitlines() if 'WFVTEST_NEGOTIATION {"schema":1' in line]
    assert lines
    assert all(len(prefix + line.split('WFVTEST_NEGOTIATION ', 1)[1]) <= 2048 for line in lines)

    expected = {
        "command": {"schema", "event", "sequence", "length", "id"},
        "protocol": {"schema", "event", "sequence", "requested", "selected"},
        "cap_request": {"schema", "event", "sequence", "id", "version", "maxVersion", "maxBytes", "valid"},
        "cap_reply": {
            "schema", "event", "sequence", "id", "version", "bytes", "valid", "glsl_level",
            "bset", "rawbitmask", "primitiveMask", "max_texture_array_layers", "max_streamout_buffers",
            "max_dual_source_render_targets", "max_render_targets", "max_samples", "max_tbo_size",
            "max_uniform_blocks", "max_viewports", "max_texture_gather_components", "capability_bits",
        },
        "trace_truncated": {"schema", "event"},
    }
    # The source format strings are deliberately fixed-key literals; parse each
    # literal after substituting bounded numeric fields with zero.
    for event, keys in expected.items():
        assert f'"event":"{event}"' in patch_json
        if event == "trace_truncated":
            continue
        match = re.search(rf'WFVTEST_NEGOTIATION (\{{"schema":1,"event":"{event}".*?\}})', patch_json)
        assert match
        text = re.sub(r"%[us]", "0", match.group(1))
        text = text.replace("\\n", "")
        parsed = json.loads(text)
        assert set(parsed) == keys


def test_capset_sizes_and_safe_reads_are_source_accurate(tmp_path: Path) -> None:
    source, _record = _patched_source(tmp_path)
    renderer = (source / "vtest/vtest_renderer.c").read_text(encoding="utf-8")
    caps = (source / "src/virgl_hw.h").read_text(encoding="utf-8")

    assert "offsetof(struct virgl_caps_v1, field)" in renderer
    assert "offsetof(struct virgl_caps_v2, capability_bits)" in renderer
    assert "memcpy(value, (const char *)data + offset, sizeof(*value));" in renderer
    assert "bytes - offset < sizeof(*value)" in renderer
    assert "struct virgl_caps_v1" in caps and "struct virgl_caps_v2" in caps
    assert recipe._profile("android-negotiation").compatibility_defines[-1] == "WFVTEST_NEGOTIATION_TRACE=1"
