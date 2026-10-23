"""Offline contract and cross-build checks for the native EGL baseline probe."""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
PROBE = ROOT / "scripts/diagnostics/android-egl"
SOURCE = PROBE / "EglBaseline.c"
BUILD = PROBE / "build.sh"
ARTIFACT = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/egl-baseline")
ELF = ARTIFACT / "EglBaseline"


def test_source_contract_is_bounded_and_truthful() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    for event in ("begin", "egl", "context", "caps", "shaders", "draw", "readback", "end"):
        assert f'"{event}"' in source
    assert 'SCHEMA "wowforever.android-egl-baseline/version1"' in source
    assert 'argc != 3 || strcmp(argv[1], "--run-id")' in source
    assert "MAX_RUN_ID 100U" in source
    assert "eglGetDisplay(EGL_DEFAULT_DISPLAY)" in source
    assert "EGL_OPENGL_ES3_BIT_KHR" in source
    assert "EGL_WIDTH, WIDTH" in source and "EGL_HEIGHT, HEIGHT" in source
    assert "#version 300 es" in source
    assert "glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE" in source
    assert "MIN_COLORED_PIXELS 128U" in source
    assert "strnlen(value, MAX_RUN_ID + 1U)" in source
    assert "strlen(" not in source
    assert '"OFFSCREEN_PASS"' in source
    assert '"presentationVerified\\":false' in source
    assert '"d3dVerified\\":false' in source
    assert '"wowVerified\\":false' in source
    assert "eglSwapBuffers" not in source
    assert "dlopen" not in source
    assert "mmap" not in source
    assert "ptrace" not in source
    assert "setenv" not in source
    assert "signal(" not in source
    assert "kill(" not in source


def test_build_script_has_strict_shell_syntax() -> None:
    completed = subprocess.run(["bash", "-n", str(BUILD)], cwd=ROOT, capture_output=True, text=True)
    assert completed.returncode == 0, completed.stderr


def test_host_executes_actual_json_encoder_and_parses_eight_records(tmp_path: Path) -> None:
    """Exercise the C encoder only; this harness never includes or calls EGL/GLES."""

    compiler = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
    if compiler is None:
        pytest.skip("no host C compiler available")
    source = SOURCE.read_text(encoding="utf-8")
    body = source[source.index("static int output_failed;") : source.index("static EGLint egl_last_error")]
    harness = tmp_path / "egl_json_encoder_harness.c"
    harness.write_text(
        """
#define _POSIX_C_SOURCE 200809L
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <stddef.h>
#define SCHEMA "wowforever.android-egl-baseline/version1"
#define MAX_LINE 7900U
#define MAX_RUN_ID 100U
#define MAX_DRIVER_STRING 128U
"""
        + body
        + """
int main(void) {
    static const char *events[] = {"begin", "egl", "context", "caps", "shaders", "draw", "readback", "end"};
    char run_id[101];
    char optional[8];
    char tiny[3];
    char exact[4];
    int truncated = 0;
    size_t i;
    memset(run_id, 'a', sizeof(run_id) - 1U);
    run_id[0] = 'R';
    run_id[sizeof(run_id) - 1U] = '\\0';
    if (!valid_run_id(run_id)) return 2;
    json_optional_string(optional, sizeof(optional), "ok", &truncated);
    if (truncated || strcmp(optional, "\\\"ok\\\"") != 0) return 3;
    json_string(tiny, sizeof(tiny), "a", 1U, &truncated);
    if (!truncated || strcmp(tiny, "\\\"\\\"") != 0) return 6;
    json_string(exact, sizeof(exact), "a", 1U, &truncated);
    if (truncated || strcmp(exact, "\\\"a\\\"") != 0) return 7;
    for (i = 0U; i < sizeof(events) / sizeof(events[0]); ++i) {
        if (!emit_event(run_id, events[i], "")) return 4;
    }
    return output_failed ? 5 : 0;
}
""",
        encoding="utf-8",
    )
    executable = tmp_path / "egl_json_encoder_harness"
    compiled = subprocess.run(
        [compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", str(harness), "-o", str(executable)],
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
    assert all(record["schema"] == "wowforever.android-egl-baseline/version1" for record in records)
    assert all(record["runId"] == "R" + "a" * 99 for record in records)


@pytest.mark.skipif(
    not Path("/home/adrian/android-sdk/ndk/24.0.8215888/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang").is_file(),
    reason="pinned local Android NDK is unavailable",
)
def test_real_arm64_crossbuild_and_readelf_contract() -> None:
    completed = subprocess.run([str(BUILD)], cwd=ROOT, capture_output=True, text=True, timeout=120)
    assert completed.returncode == 0, completed.stdout + completed.stderr
    assert ELF.is_file()
    assert ELF.stat().st_size <= 256 * 1024
    manifest = json.loads((ARTIFACT / "manifest.json").read_text(encoding="ascii"))
    readelf = (ARTIFACT / "EglBaseline.readelf.txt").read_text(encoding="ascii")
    data = ELF.read_bytes()
    assert data[:4] == b"\x7fELF"
    assert data[4] == 2
    assert manifest["elfType"] == "ET_DYN"
    assert manifest["machine"] == 183
    assert manifest["pie"] is True
    assert manifest["size"] == len(data) <= 256 * 1024
    assert manifest["sha256"] == hashlib.sha256(data).hexdigest()
    assert "Type:                              DYN" in readelf
    assert "Machine:                           AArch64" in readelf
    assert "Shared library: [libEGL.so]" in readelf
    assert "Shared library: [libGLESv3.so]" in readelf
    assert set(manifest["neededLibraries"]) <= {"libEGL.so", "libGLESv3.so", "libc.so", "libdl.so"}
    assert manifest["sourceSha256"][SOURCE.name] == hashlib.sha256(SOURCE.read_bytes()).hexdigest()
    assert manifest["sourceSha256"][BUILD.name] == hashlib.sha256(BUILD.read_bytes()).hexdigest()
