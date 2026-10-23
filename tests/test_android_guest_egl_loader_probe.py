"""Offline gates for the fixed private guest GLVND loader control."""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import struct
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
PROBE = ROOT / "scripts/diagnostics/android-vtest"
SOURCE = PROBE / "GuestEglLoader.c"
BUILD = PROBE / "build_guest_loader.sh"
README = PROBE / "README-guest-loader.md"
ARTIFACT = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/guest-egl-loader")
ELF = ARTIFACT / "GuestEglLoader"


def test_source_contract_is_private_loader_only_and_has_fixed_schema() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    assert '#define SCHEMA "wowforever.android-guest-egl-loader/version1"' in source
    assert '#define PRIVATE_LOADER_RELATIVE "guest/lib/libEGL.so.1.1.0"' in source
    assert "MAX_EXTENSION_SCAN 16384U" in source
    assert "MAX_EXTENSION_PREVIEW 512U" in source
    assert "MAX_MAP_BYTES (2U * 1024U * 1024U)" in source
    assert "MAX_MAP_LINE 4096U" in source
    assert 'strcmp(argv[1], "--run-id")' in source
    assert 'memcmp(value, "VTEST-", 6U)' in source
    for event in ("begin", "scope", "library", "exports", "client_extensions", "mappings", "cleanup", "end"):
        assert f'"{event}"' in source
    assert 'dlopen(PRIVATE_LOADER_RELATIVE, RTLD_NOW | RTLD_LOCAL)' in source
    assert "dlsym(handle, name)" in source
    for symbol in (
        "eglBindAPI", "eglChooseConfig", "eglCreateContext", "eglGetError",
        "eglGetPlatformDisplay", "eglGetProcAddress", "eglInitialize",
        "eglMakeCurrent", "eglQueryString", "eglTerminate",
    ):
        assert f'"{symbol}"' in source
    assert "owner_is_loader" in source and "realpath" in source
    assert "#define EGL_NO_DISPLAY ((EGLDisplay)0)" in source
    assert 'queryString(EGL_NO_DISPLAY, EGL_EXTENSIONS)' in source
    assert 'state->api.getError()' in source
    assert 'fopen("/proc/self/maps", "rb")' in source
    assert '"/system/"' in source and '"/apex/"' in source and '"/vendor/"' in source
    assert "map_line_has_android_graphics" in source
    for flag in (
        "eglContextVerified", "virpipeVerified", "hardwareRenderingVerified",
        "triangleVerified", "presentationVerified", "d3dVerified", "wowVerified",
    ):
        assert f'\\"{flag}\\":false' in source
    assert "eglInitialize(" not in source
    assert "eglGetDisplay(" not in source
    assert "eglMakeCurrent(" not in source
    assert "eglCreateContext(" not in source
    assert "eglTerminate(" not in source
    assert "eglGetProcAddress(" not in source
    assert "eglGetString(" not in source
    assert "eglSwapBuffers" not in source
    assert "glDraw" not in source
    assert "vtest.sock" in source
    assert "signal(" not in source
    assert "kill(" not in source
    assert "ptrace" not in source
    assert "ioctl" not in source


def test_build_script_is_strict_and_pins_existing_candidate_before_output() -> None:
    completed = subprocess.run(["bash", "-n", str(BUILD)], cwd=ROOT, capture_output=True, text=True)
    assert completed.returncode == 0, completed.stderr
    recipe = BUILD.read_text(encoding="utf-8")
    assert "24.0.8215888" in recipe
    assert "aarch64-linux-android29-clang" in recipe
    assert "--build-id=none" in recipe
    assert "-Wall -Wextra -Werror -fPIE" in recipe
    assert "-pie -o" in recipe
    assert '"$ROOT/GuestEglLoader.c" -ldl' in recipe
    assert "--needed-libs --dyn-syms" in recipe
    assert "64 * 1024" in recipe
    assert '"libdl.so", "libc.so"' in recipe
    assert "30c43814697a22dc72ee4e3b1eeb8ec9d6b6af228ee9c4ffc21e15b3fe8d9ce9" in recipe
    assert "1cc803fbcbef9a7d1743966702c733fc63b8e311a3aa77f8020ee7594e11d216" in recipe


def _encoder_harness(tmp_path: Path) -> tuple[Path, str]:
    compiler = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
    if compiler is None:
        pytest.skip("no host C compiler available")
    source = SOURCE.read_text(encoding="utf-8")
    body = source[source.index("/* HOST-TEST-BEGIN:"): source.index("static int path_append")]
    harness = tmp_path / "guest_loader_helpers.c"
    harness.write_text(
        """
#define _POSIX_C_SOURCE 200809L
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/types.h>
#define SCHEMA "wowforever.android-guest-egl-loader/version1"
#define MAX_OUTPUT_BYTES 65536U
#define MAX_LINE 24576U
#define MAX_STRING 512U
#define MAX_PATH_STRING 4096U
#define MAX_RUN_ID 38U
#define MAX_EXTENSION_SCAN 16384U
#define MAX_MAP_LINE 4096U
typedef struct Identity { pid_t pid; uid_t uid; pid_t pgid; pid_t sid; } Identity;
static size_t outputBytes;
"""
        + body
        + r"""
int main(void) {
    const char *events[] = {"begin", "scope", "library", "exports", "client_extensions", "mappings", "cleanup", "end"};
    const char *invalid[] = {NULL, "", "VTEST-", "VTEST-0123456789abcdef0123456789ABCDE", "VTEST-0123456789abcdef0123456789abcdef\n"};
    char run[39] = "VTEST-0123456789abcdef0123456789abcdef";
    char escaped[128];
    char tiny[3];
    char large[16385];
    char unterminated[8] = {'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'};
    char optional[32];
    int truncated = 0;
    int platform = 0, create = 0, surface = 0;
    size_t scanned = 0;
    size_t i;
    Identity identity = {1, 10330, 2, 3};
    for (i = 0; i < sizeof(invalid) / sizeof(invalid[0]); ++i) if (valid_run_id(invalid[i])) return 2;
    if (!valid_run_id(run)) return 3;
    json_string(escaped, sizeof(escaped), "quote \" slash \\ newline\n tab\t", 32, &truncated);
    if (truncated || !strstr(escaped, "\\\"") || !strstr(escaped, "\\\\") ||
        !strstr(escaped, "\\n") || !strstr(escaped, "\\t")) return 4;
    json_string(tiny, sizeof(tiny), "a", 1, &truncated);
    if (!truncated || strcmp(tiny, "\"\"")) return 5;
    json_string(NULL, 0, "x", 1, &truncated);
    if (!truncated) return 6;
    json_optional_string(optional, sizeof(optional), NULL, 20, &truncated);
    if (truncated || strcmp(optional, "null")) return 7;
    json_optional_bool(optional, sizeof(optional), 1, 1);
    if (strcmp(optional, "true")) return 7;
    memset(large, 'x', sizeof(large) - 1);
    large[sizeof(large) - 1] = 0;
    if (scan_extension_string(large, &platform, &create, &surface, &scanned) || scanned != 16384) return 8;
    if (scan_extension_string(NULL, &platform, &create, &surface, &scanned) || scanned != 0) return 9;
    if (!scan_extension_string("EGL_EXT_platform_base EGL_KHR_create_context EGL_KHR_surfaceless_context",
                              &platform, &create, &surface, &scanned) || scanned == 0) return 10;
    /* A token boundary matters: a fake path must not prove a private map. */
    if (!map_line_has_exact_path("7-8 rw-p 0 0 /private/lib.so\n", "/private/lib.so")) return 11;
    if (map_line_has_exact_path("7-8 rw-p 0 0 /private/lib.so.fake\n", "/private/lib.so")) return 12;
    if (map_line_has_exact_path("7-8 rw-p 0 0 /private/lib.so\n", NULL)) return 13;
    if (!map_line_has_android_graphics("7-8 r-xp 0 0 /apex/test/lib64/libEGL.so\n", 0)) return 20;
    if (!map_line_has_android_graphics("7-8 r-xp 0 0 /vendor/lib64/egl/libGLES_mali.so\n", 1)) return 21;
    if (map_line_has_android_graphics("7-8 r-xp 0 0 /private/guest/lib/libEGL.so.1.1.0\n", 0)) return 22;
    if (map_line_has_android_graphics("7-8 r-xp 0 0 /apexfake/lib64/libEGL.so\n", 0)) return 23;
    if (map_line_has_android_graphics("7-8 r-xp 0 0 /apex/test/lib64/libc.so\n", 0)) return 24;
    if (!copy_bounded(optional, sizeof(optional), "ok", 3) || strcmp(optional, "ok")) return 14;
    if (copy_bounded(optional, sizeof(optional), unterminated, sizeof(unterminated))) return 15;
    for (i = 0; i < sizeof(events) / sizeof(events[0]); ++i) {
        if (!emit_event(run, &identity, events[i], ",\"error\":\"quote \\\" and slash \\\\\"")) return 16;
    }
    return 0;
}
""",
        encoding="utf-8",
    )
    executable = tmp_path / "guest_loader_helpers"
    compiled = subprocess.run(
        [compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", str(harness), "-o", str(executable)],
        capture_output=True,
        text=True,
    )
    assert compiled.returncode == 0, compiled.stdout + compiled.stderr
    return executable, compiler


def test_host_encoder_and_gate_fixtures_never_load_egl(tmp_path: Path) -> None:
    executable, _ = _encoder_harness(tmp_path)
    ran = subprocess.run([str(executable)], capture_output=True, check=False)
    assert ran.returncode == 0, ran.stderr.decode(errors="replace")
    assert len(ran.stdout) <= 64 * 1024
    rows = [json.loads(line.decode("ascii")) for line in ran.stdout.splitlines()]
    assert [row["event"] for row in rows] == [
        "begin", "scope", "library", "exports", "client_extensions", "mappings", "cleanup", "end"
    ]
    assert all(row["schema"] == "wowforever.android-guest-egl-loader/version1" for row in rows)
    assert all(row["runId"] == "VTEST-0123456789abcdef0123456789abcdef" for row in rows)
    assert all(isinstance(row["pid"], int) and not isinstance(row["pid"], bool) for row in rows)
    assert all(row["error"] == 'quote " and slash \\' for row in rows)


def test_source_declares_dlopen_error_and_never_claims_private_payload_details() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    assert "dlerror()" in source
    assert "RTLD_NOW | RTLD_LOCAL" in source
    assert "concreteLoaderFailure" in source
    assert '"GUEST_LOADER_PASS"' in source
    assert '"LOADER_FAILED"' in source
    assert '"UNKNOWN"' in source
    # This gate intentionally has no private renderer payload or hardware claim.
    assert "renderer" not in source.lower()
    assert "triangle" in source.lower()  # fixed false schema field only
    assert "EGL_PLATFORM_MESA" not in source
    assert "vtest_test" not in source


@pytest.mark.skipif(
    not Path(
        "/home/adrian/android-sdk/ndk/24.0.8215888/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
    ).is_file(),
    reason="pinned local Android NDK is unavailable",
)
def test_real_arm64_crossbuild_and_readelf_contract() -> None:
    completed = subprocess.run(["bash", str(BUILD)], cwd=ROOT, capture_output=True, text=True, timeout=120)
    assert completed.returncode == 0, completed.stdout + completed.stderr
    assert ELF.is_file()
    assert ELF.stat().st_size <= 64 * 1024
    manifest = json.loads((ARTIFACT / "manifest.json").read_text(encoding="ascii"))
    readelf = (ARTIFACT / "GuestEglLoader.readelf.txt").read_text(encoding="ascii")
    data = ELF.read_bytes()
    assert data[:4] == b"\x7fELF"
    assert data[4] == 2
    assert struct.unpack_from("<H", data, 16)[0] == 3
    assert struct.unpack_from("<H", data, 18)[0] == 183
    assert manifest["elfType"] == "ET_DYN"
    assert manifest["machine"] == 183
    assert manifest["pie"] is True
    assert manifest["interpreter"] == "/system/bin/linker64"
    assert manifest["size"] == len(data) <= 64 * 1024
    assert manifest["sha256"] == hashlib.sha256(data).hexdigest()
    assert manifest["payloadSha256"] == manifest["sha256"]
    assert manifest["nativeArtifact"]["sha256"] == manifest["sha256"]
    compiler = Path(
        "/home/adrian/android-sdk/ndk/24.0.8215888/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
    )
    assert manifest["compilerSha256"] == hashlib.sha256(compiler.read_bytes()).hexdigest()
    assert manifest["compilerSize"] == compiler.stat().st_size
    assert "Type:                              DYN" in readelf
    assert "Machine:                           AArch64" in readelf
    assert "Requesting program interpreter: /system/bin/linker64" in readelf
    assert "Shared library: [libdl.so]" in readelf
    assert "Shared library: [libc.so]" in readelf
    assert set(manifest["neededLibraries"]) == {"libdl.so", "libc.so"}
    assert manifest["sourceSha256"][SOURCE.name] == hashlib.sha256(SOURCE.read_bytes()).hexdigest()
    assert manifest["sourceSha256"][BUILD.name] == hashlib.sha256(BUILD.read_bytes()).hexdigest()
    assert manifest["sourceSha256"][README.name] == hashlib.sha256(README.read_bytes()).hexdigest()
    assert manifest["candidate"]["manifestSha256"] == "30c43814697a22dc72ee4e3b1eeb8ec9d6b6af228ee9c4ffc21e15b3fe8d9ce9"
