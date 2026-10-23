"""Offline contract and cross-build checks for the private libepoxy probe."""

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
SOURCE = PROBE / "EglEpoxyProbe.c"
BUILD = PROBE / "build_epoxy_probe.sh"
README = PROBE / "README-epoxy.md"
ARTIFACT = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/egl-epoxy")
ELF = ARTIFACT / "EglEpoxyProbe"


def test_source_contract_is_private_epoxy_only_and_bounded() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    assert '#define SCHEMA "wowforever.android-egl-epoxy/version1"' in source
    assert '#define LIBRARY_NAME "libepoxy.so"' in source
    assert '#define MAX_EXTENSION_SCAN 16384U' in source
    assert '#define MAX_OUTPUT_BYTES 65536U' in source
    assert 'strcmp(argv[1], "--run-id")' in source
    assert 'memcmp(value, "VTEST-", 6U)' in source
    for event in ("begin", "library", "client_extensions", "egl", "context", "caps", "cleanup", "end"):
        assert f'"{event}"' in source
    assert 'dlopen(LIBRARY_NAME, RTLD_NOW | RTLD_LOCAL)' in source
    assert 'dlsym(handle, name)' in source
    assert 'read_function_slot(slot, target, targetSize)' in source
    assert 'prove_library_slot(getDisplaySlot, expectedLibrary' in source
    assert 'EglGetDisplayFn resolvedGetDisplayFn = NULL' in source
    assert 'read_function_slot(getDisplaySlot, &resolvedGetDisplayFn' in source
    assert 'implementationPathKind' in source
    assert 'clientEglError' in source
    assert 'clientQueryKnown' not in source
    assert 'display_measurement_allowed(probe.library != NULL' in source
    assert 'extensionScanBytes' in source
    assert 'extensionsTruncated' in source
    assert 'char extensionJson[MAX_STRING]' in source
    assert 'char glVendor[MAX_STRING]' in source
    assert 'probe.glVendor[0] !=' in source
    assert 'probe.apiBound' in source
    assert '++probe->eglErrorFailures' in source
    assert 'EXPECTED_UID 10330U' in source
    assert '/data/user/0/com.wowforever.arm64probe/files/forever/controls/vtest-loader/%s' in source
    assert '/data/data/com.wowforever.arm64probe/files/forever/controls/vtest-loader/%s' in source
    assert '"%s/lib:/system/lib64", cwd' in source
    assert 'realpath(expectedPath, expectedCanonical)' in source
    assert 'EGL_SURFACE_TYPE, EGL_WINDOW_BIT' in source
    assert 'EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT' in source
    assert 'EGL_CONTEXT_CLIENT_VERSION, 2' in source
    assert 'EGL_NO_SURFACE, EGL_NO_SURFACE, probe.context' in source
    assert '"EPOXY_CONTEXT_PASS"' in source
    assert '\\"graphicsVerified\\":false' in source
    assert '\\"hardwareRenderingVerified\\":false' in source
    assert '\\"vtestVerified\\":false' in source
    assert '\\"presentationVerified\\":false' in source
    assert '\\"d3dVerified\\":false' in source
    assert '\\"wowVerified\\":false' in source
    assert "eglCreatePbufferSurface" not in source
    assert "EGL_PLATFORM_MESA" not in source
    assert "eglSwapBuffers" not in source
    assert "glDraw" not in source
    assert "ptrace" not in source
    assert "ioctl" not in source
    assert "setpgid" not in source
    assert "setsid" not in source
    assert not re.search(r"(?<![A-Za-z0-9_.])\begl(?:GetDisplay|Initialize|QueryString|MakeCurrent)\s*\(", source)
    assert not re.search(r"(?<![A-Za-z0-9_.])\bgl(?:GetString|GetError)\s*\(", source)


def test_build_script_is_strict_and_has_fixed_native_recipe() -> None:
    completed = subprocess.run(["bash", "-n", str(BUILD)], cwd=ROOT, capture_output=True, text=True)
    assert completed.returncode == 0, completed.stderr
    recipe = BUILD.read_text(encoding="utf-8")
    assert "24.0.8215888" in recipe
    assert "aarch64-linux-android29-clang" in recipe
    assert "--build-id=none" in recipe
    assert "-Wall -Wextra -Werror -fPIE" in recipe
    assert "-pie -o" in recipe
    assert '"$ROOT/EglEpoxyProbe.c" -ldl' in recipe
    assert "--needed-libs --dyn-syms" in recipe
    assert "64 * 1024" in recipe
    assert '"libdl.so", "libc.so"' in recipe


def test_host_encoder_slot_resolution_and_pass_gates(tmp_path: Path) -> None:
    """Compile only the source's test-marked helpers; no EGL/GLES call occurs."""

    compiler = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
    if compiler is None:
        pytest.skip("no host C compiler available")
    source = SOURCE.read_text(encoding="utf-8")
    body = source[source.index("/* HOST-TEST-BEGIN:"): source.index("/* HOST-TEST-END */")]
    harness = tmp_path / "epoxy_helpers.c"
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
#define SCHEMA "wowforever.android-egl-epoxy/version1"
#define MAX_OUTPUT_BYTES 65536U
#define MAX_LINE 24576U
#define MAX_RUN_ID 38U
typedef struct Identity { pid_t pid; uid_t uid; pid_t pgid; pid_t sid; } Identity;
static int outputFailed;
static size_t outputBytes;
"""
        + body
        + r"""
static int called;
static int fixture_function(int value) { called += value; return value + 1; }

int main(void) {
    static const char *events[] = {"begin", "library", "client_extensions", "egl", "context", "caps", "cleanup", "end"};
    const char *invalid[] = {"", "VTEST-", "VTEST-0123456789abcdef0123456789ABCDE", "VTEST-0123456789abcdef0123456789abcdef\n"};
    const char escaped_input[] = {'q', '"', ' ', '\\', ' ', '\n', ' ', '\t', 0};
    char valid[39] = "VTEST-0123456789abcdef0123456789abcdef";
    char escaped[128];
    char optional[32];
    char tiny[3];
    int truncated = 0;
    int (*slot)(int) = fixture_function;
    int (*resolved)(int) = NULL;
    Identity identity = {1, 10330, 2, 3};
    size_t i;

    if (!valid_run_id(valid)) return 2;
    for (i = 0; i < sizeof(invalid) / sizeof(invalid[0]); ++i) if (valid_run_id(invalid[i])) return 3;
    json_string(escaped, sizeof(escaped), escaped_input, sizeof(escaped_input), &truncated);
    if (truncated || !strstr(escaped, "\\\"") || !strstr(escaped, "\\\\") ||
        !strstr(escaped, "\\n") || !strstr(escaped, "\\t")) return 4;
    json_string(tiny, sizeof(tiny), "a", 1, &truncated);
    if (!truncated || strcmp(tiny, "\"\"")) return 5;
    json_optional_string(optional, sizeof(optional), NULL, 10, &truncated);
    if (truncated || strcmp(optional, "null")) return 6;
    json_optional_bool(optional, sizeof(optional), 1, 1);
    if (strcmp(optional, "true")) return 6;
    if (!read_function_slot(&slot, &resolved, sizeof(resolved)) || resolved == NULL || resolved(3) != 4 || called != 3) return 7;
    if (!display_measurement_allowed(1) || display_measurement_allowed(0)) return 8;
    if (!context_pass_eligible(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)) return 8;
    for (i = 0; i < 11; ++i) {
        int values[] = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
        values[i] = 0;
        if (context_pass_eligible(values[0], values[1], values[2], values[3], values[4], values[5],
                                   values[6], values[7], values[8], values[9], values[10])) return 9;
    }
    for (i = 0; i < sizeof(events) / sizeof(events[0]); ++i) {
        if (!emit_event(valid, &identity, events[i], ",\"error\":\"quote \\\" and slash \\\\\"")) return 10;
    }
    return outputFailed || outputBytes > MAX_OUTPUT_BYTES ? 11 : 0;
}
""",
        encoding="utf-8",
    )
    executable = tmp_path / "epoxy_helpers"
    compiled = subprocess.run(
        [compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", str(harness), "-o", str(executable)],
        capture_output=True,
        text=True,
    )
    assert compiled.returncode == 0, compiled.stdout + compiled.stderr
    ran = subprocess.run([str(executable)], capture_output=True, check=False)
    assert ran.returncode == 0, ran.stderr.decode(errors="replace")
    assert len(ran.stdout) <= 64 * 1024
    rows = [json.loads(line.decode("ascii")) for line in ran.stdout.splitlines()]
    assert [row["event"] for row in rows] == [
        "begin", "library", "client_extensions", "egl", "context", "caps", "cleanup", "end"
    ]
    assert all(row["schema"] == "wowforever.android-egl-epoxy/version1" for row in rows)
    assert all(row["runId"] == "VTEST-0123456789abcdef0123456789abcdef" for row in rows)
    assert all(set(("pid", "uid", "pgid", "sid", "timeNs")) <= row.keys() for row in rows)
    assert all(row["error"] == 'quote " and slash \\' for row in rows)


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
    readelf = (ARTIFACT / "EglEpoxyProbe.readelf.txt").read_text(encoding="ascii")
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
    compiler = Path("/home/adrian/android-sdk/ndk/24.0.8215888/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang")
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
