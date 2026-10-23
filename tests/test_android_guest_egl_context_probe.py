"""Offline source/build gates for the private guest EGL context control."""

from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
DIRECTORY = ROOT / "scripts/diagnostics/android-vtest"
SOURCE = DIRECTORY / "GuestEglContext.c"
BUILD = DIRECTORY / "build_guest_context.sh"
README = DIRECTORY / "README-guest-context.md"


def test_context_source_is_fixed_private_surfaceless_gate() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    assert '#define CONTEXT_SCHEMA "wowforever.android-guest-egl-context/version1"' in source
    assert 'dlopen("guest/lib/libEGL.so.1.1.0", RTLD_NOW | RTLD_LOCAL)' in source
    assert "EGL_PLATFORM_SURFACELESS_MESA" in source
    assert "EGL_OPENGL_API" in source and "EGL_CONTEXT_MAJOR_VERSION_KHR" in source
    assert "EGL_OPENGL_BIT, EGL_SURFACE_TYPE, 0, EGL_NONE" in source
    assert 'state->api.getProcAddress("glGetString")' in source
    assert 'state->api.getProcAddress("glGetError")' in source
    assert 'fopen("/proc/self/maps", "rb")' in source
    assert 'CONTEXT_SERVER_IDENTITY "server.identity"' in source
    assert "O_NOFOLLOW" in source and "context_server_live" in source
    assert "GALLIUM_DRIVER" not in source or "virgl" in source
    assert "socket(" not in source and "connect(" not in source
    assert "EGL_PLATFORM_X11" not in source and "EGL_PBUFFER_BIT" not in source
    assert "eglSwapBuffers" not in source and "glDraw" not in source
    for event in ("begin", "scope", "library", "exports", "display", "context", "gl", "mappings", "cleanup", "end"):
        assert f'"{event}"' in source
    for flag in ("hardwareRenderingVerified", "triangleVerified", "presentationVerified", "d3dVerified", "wowVerified"):
        assert f'\\"{flag}\\":false' in source


def test_context_build_recipe_is_strict_and_bounded() -> None:
    assert subprocess.run(["bash", "-n", str(BUILD)], capture_output=True).returncode == 0
    recipe = BUILD.read_text(encoding="utf-8")
    assert "24.0.8215888" in recipe and "aarch64-linux-android29-clang" in recipe
    assert "--build-id=none" in recipe and "-Wall -Wextra -Werror" in recipe
    assert '"$ROOT/GuestEglContext.c" -ldl -lc' in recipe
    assert "64 * 1024" in recipe and "GuestEglLoader.c" in recipe
    assert '"libc.so", "libdl.so"' in recipe


def test_host_encoder_and_context_predicates_are_inert(tmp_path: Path) -> None:
    compiler = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
    if compiler is None:
        pytest.skip("no host C compiler available")
    source = SOURCE.read_text(encoding="utf-8")
    block = source[source.index("/* HOST-TEST-BEGIN:"): source.index("/* HOST-TEST-END */") + len("/* HOST-TEST-END */")]
    harness = tmp_path / "context_fixture.c"
    harness.write_text(
        """
#include <ctype.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#define MAX_RUN_ID 38U
#define CONTEXT_MAX_EXTENSION_SCAN (16U * 1024U)
#define CONTEXT_MAX_STRING 512U
static size_t bounded_length(const char *value, size_t limit, int *terminated) {
    size_t i; if (terminated != NULL) *terminated = 0; if (value == NULL) return 0U;
    for (i = 0U; i < limit; ++i) if (value[i] == '\\0') { if (terminated != NULL) *terminated = 1; return i; }
    return limit;
}
static int valid_run_id(const char *value) {
    size_t i; size_t length = bounded_length(value, MAX_RUN_ID + 1U, NULL);
    if (value == NULL || length != MAX_RUN_ID || memcmp(value, "VTEST-", 6U) != 0) return 0;
    for (i = 6U; i < length; ++i) if (!((value[i] >= '0' && value[i] <= '9') || (value[i] >= 'a' && value[i] <= 'f'))) return 0;
    return 1;
}
""" + block + """
int main(void) {
    const char *run = "VTEST-0123456789abcdef0123456789abcdef";
    char copied[16];
    if (!context_copy_string(copied, sizeof(copied), "virgl") || strcmp(copied, "virgl")) return 6;
    if (!context_valid_run_id(run) || context_valid_run_id("VTEST-short")) return 1;
    if (!context_contains_token("EGL_KHR_create_context EGL_KHR_surfaceless_context", "EGL_KHR_create_context")) return 2;
    if (context_contains_token("not-EGL_KHR_create_context", "EGL_KHR_create_context")) return 3;
    if (!context_contains_casefold("virgl renderer", "VIRGL")) return 4;
    if (context_contains_casefold("llvmpipe", "VIRGL")) return 5;
    return 0;
}
""",
        encoding="utf-8",
    )
    executable = tmp_path / "context_fixture"
    built = subprocess.run([compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", str(harness), "-o", str(executable)], capture_output=True, text=True)
    assert built.returncode == 0, built.stdout + built.stderr
    ran = subprocess.run([str(executable)], capture_output=True)
    assert ran.returncode == 0, ran.stderr.decode(errors="replace")
