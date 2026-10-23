"""Offline contract and cross-build checks for the EGL_NO_SURFACE probe."""

from __future__ import annotations

import hashlib
import json
import shutil
import struct
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
PROBE = ROOT / "scripts/diagnostics/android-egl"
SOURCE = PROBE / "EglSurfaceless.c"
BUILD = PROBE / "build_surfaceless.sh"
README = PROBE / "README-surfaceless.md"
ARTIFACT = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/egl-surfaceless")
ELF = ARTIFACT / "EglSurfaceless"


def test_source_contract_is_bounded_and_uses_no_surface() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    for event in ("begin", "egl", "context", "caps", "shaders", "draw", "readback", "end"):
        assert f'"{event}"' in source
    assert '#define SCHEMA "wowforever.android-egl-surfaceless/version1"' in source
    assert 'argc != 3 || strcmp(argv[1], "--run-id")' in source
    assert "MAX_RUN_ID 100U" in source
    assert "MAX_EXTENSION_SCAN 16384U" in source
    assert "EGL_SURFACE_TYPE, EGL_WINDOW_BIT" in source
    assert "EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT" in source
    assert "EGL_CONTEXT_CLIENT_VERSION, 2" in source
    assert "eglMakeCurrent(probe->display, EGL_NO_SURFACE, EGL_NO_SURFACE, probe->context)" in source
    assert "#version 100" in source
    assert "glGenTextures" in source and "glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, WIDTH, HEIGHT" in source
    assert "glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE" in source
    assert "glGenBuffers" in source and "glDrawArrays(GL_TRIANGLES, 0, 3)" in source
    assert "glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE" in source
    assert "MIN_COLORED_PIXELS 128U" in source and "probe->pixel_hash != 0U" in source
    assert '"SURFACELESS_OFFSCREEN_PASS"' in source
    assert '"presentationVerified\\":false' in source
    assert '"d3dVerified\\":false' in source
    assert '"wowVerified\\":false' in source
    assert '"vtestVerified\\":false' in source
    assert "eglCreatePbufferSurface" not in source
    assert "EGL_PBUFFER_BIT" not in source
    assert "eglSwapBuffers" not in source
    assert "ANativeWindow" not in source
    assert "EGL_PLATFORM_MESA" not in source
    for forbidden in ("dlopen", "ioctl", "signal(", "kill(", "mmap", "ptrace", "GLES3", "gl3.h"):
        assert forbidden not in source


def test_build_script_has_strict_shell_syntax_and_pinned_recipe() -> None:
    completed = subprocess.run(["bash", "-n", str(BUILD)], cwd=ROOT, capture_output=True, text=True)
    assert completed.returncode == 0, completed.stderr
    recipe = BUILD.read_text(encoding="utf-8")
    assert "24.0.8215888" in recipe
    assert "-lEGL -lGLESv2" in recipe
    assert "EglSurfaceless.readelf.txt" in recipe
    assert "nativeArtifact" in recipe
    assert '"libEGL.so", "libGLESv2.so", "libc.so"' in recipe


def test_host_executes_actual_json_encoder_and_gate_harness(tmp_path: Path) -> None:
    """Exercise extracted C encoder/gating functions only; no EGL/GLES calls occur."""

    compiler = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
    if compiler is None:
        pytest.skip("no host C compiler available")
    source = SOURCE.read_text(encoding="utf-8")
    body = source[source.index("static int output_failed;") : source.index("static EGLint take_egl_error")]
    harness = tmp_path / "egl_surfaceless_json_harness.c"
    harness.write_text(
        """
#define _POSIX_C_SOURCE 200809L
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#define SCHEMA "wowforever.android-egl-surfaceless/version1"
#define MAX_LINE 7900U
#define MAX_OUTPUT_BYTES 65536U
#define MAX_RUN_ID 100U
#define MAX_DRIVER_STRING 512U
"""
        + body
        + r"""
int main(void) {
    static const char *events[] = {"begin", "egl", "context", "caps", "shaders", "draw", "readback", "end"};
    const char *invalid[] = {"", " bad", "R/", "R" "\x01"};
    char run_id[101];
    char escaped[128];
    char tiny[3];
    char optional[16];
    static const char escaped_input[] = {
        'q', 'u', 'o', 't', 'e', 34, ' ', 's', 'l', 'a', 's', 'h', 92, ' ',
        'n', 'e', 'w', 'l', 'i', 'n', 'e', 10, ' ', 't', 'a', 'b', 9, 0
    };
    int truncated = 0;
    int saw_quote_escape = 0;
    int saw_slash_escape = 0;
    int saw_newline_escape = 0;
    int saw_tab_escape = 0;
    size_t i;
    memset(run_id, 'a', sizeof(run_id) - 1U);
    run_id[0] = 'R';
    run_id[sizeof(run_id) - 1U] = 0;
    if (!valid_run_id(run_id)) return 2;
    for (i = 0U; i < sizeof(invalid) / sizeof(invalid[0]); ++i) {
        if (valid_run_id(invalid[i])) return 3;
    }
    json_string(escaped, sizeof(escaped), escaped_input, 100U, &truncated);
    for (i = 0U; escaped[i] != 0; ++i) {
        if (escaped[i] == 92 && escaped[i + 1U] == 34) saw_quote_escape = 1;
        if (escaped[i] == 92 && escaped[i + 1U] == 92) saw_slash_escape = 1;
        if (escaped[i] == 92 && escaped[i + 1U] == 'n') saw_newline_escape = 1;
        if (escaped[i] == 92 && escaped[i + 1U] == 't') saw_tab_escape = 1;
    }
    if (truncated || !saw_quote_escape || !saw_slash_escape || !saw_newline_escape || !saw_tab_escape) return 4;
    json_string(tiny, sizeof(tiny), "a", 1U, &truncated);
    if (!truncated || tiny[0] != 34 || tiny[1] != 34 || tiny[2] != 0) return 5;
    json_optional_string(optional, sizeof(optional), "ok", &truncated);
    if (truncated || optional[0] != 34 || optional[1] != 'o' || optional[2] != 'k' || optional[3] != 34) return 5;
    json_optional_bool(optional, sizeof(optional), 1, 1);
    if (strcmp(optional, "true") != 0) return 5;
    if (!surface_pass_eligible(1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0)) return 6;
    for (i = 0U; i < 11U; ++i) {
        int values[] = {1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0};
        if (i < 9U) values[i] = 0;
        else values[i] = 1;
        if (surface_pass_eligible(values[0], values[1], values[2], values[3], values[4], values[5],
                                   values[6], values[7], values[8], values[9], values[10])) return 7;
    }
    for (i = 0U; i < sizeof(events) / sizeof(events[0]); ++i) {
        if (!emit_event(run_id, events[i], ",\"note\":\"ok\"")) return 8;
    }
    return output_failed ? 9 : 0;
}
""",
        encoding="utf-8",
    )
    executable = tmp_path / "egl_surfaceless_json_harness"
    compiled = subprocess.run(
        [
            compiler,
            "-std=c11",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-ffunction-sections",
            "-fdata-sections",
            "-Wl,--gc-sections",
            str(harness),
            "-o",
            str(executable),
        ],
        capture_output=True,
        text=True,
    )
    assert compiled.returncode == 0, compiled.stdout + compiled.stderr
    ran = subprocess.run([str(executable)], capture_output=True, check=False)
    assert ran.returncode == 0, ran.stderr.decode(errors="replace")
    assert len(ran.stdout) <= 64 * 1024
    lines = ran.stdout.splitlines()
    assert len(lines) == 8
    records = [json.loads(line.decode("ascii")) for line in lines]
    assert [record["event"] for record in records] == [
        "begin", "egl", "context", "caps", "shaders", "draw", "readback", "end"
    ]
    assert all(record["schema"] == "wowforever.android-egl-surfaceless/version1" for record in records)
    assert all(record["runId"] == "R" + "a" * 99 for record in records)
    assert all(record["note"] == "ok" for record in records)


@pytest.mark.skipif(
    not Path(
        "/home/adrian/android-sdk/ndk/24.0.8215888/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
    ).is_file(),
    reason="pinned local Android NDK is unavailable",
)
def test_real_arm64_crossbuild_and_readelf_contract() -> None:
    completed = subprocess.run([str(BUILD)], cwd=ROOT, capture_output=True, text=True, timeout=120)
    assert completed.returncode == 0, completed.stdout + completed.stderr
    assert ELF.is_file()
    assert ELF.stat().st_size <= 256 * 1024
    manifest = json.loads((ARTIFACT / "manifest.json").read_text(encoding="ascii"))
    readelf = (ARTIFACT / "EglSurfaceless.readelf.txt").read_text(encoding="ascii")
    data = ELF.read_bytes()
    assert data[:4] == b"\x7fELF"
    assert data[4] == 2
    assert struct.unpack_from("<H", data, 16)[0] == 3
    assert struct.unpack_from("<H", data, 18)[0] == 183
    assert manifest["elfType"] == "ET_DYN"
    assert manifest["machine"] == 183
    assert manifest["pie"] is True
    assert manifest["size"] == len(data) <= 256 * 1024
    assert manifest["sha256"] == hashlib.sha256(data).hexdigest()
    assert manifest["nativeArtifact"]["sha256"] == manifest["sha256"]
    assert manifest["nativeArtifact"]["size"] == len(data)
    assert "Type:                              DYN" in readelf
    assert "Machine:                           AArch64" in readelf
    assert "Shared library: [libEGL.so]" in readelf
    assert "Shared library: [libGLESv2.so]" in readelf
    assert set(manifest["neededLibraries"]) <= {"libEGL.so", "libGLESv2.so", "libc.so"}
    assert manifest["sourceSha256"][SOURCE.name] == hashlib.sha256(SOURCE.read_bytes()).hexdigest()
    assert manifest["sourceSha256"][BUILD.name] == hashlib.sha256(BUILD.read_bytes()).hexdigest()
    assert manifest["sourceSha256"][README.name] == hashlib.sha256(README.read_bytes()).hexdigest()
