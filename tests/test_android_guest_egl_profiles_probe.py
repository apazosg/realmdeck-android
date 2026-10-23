"""Offline gates for the fixed guest EGL profile-classification probe."""

from __future__ import annotations

import hashlib
import json
import shutil
import struct
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
DIRECTORY = ROOT / "scripts/diagnostics/android-vtest"
SOURCE = DIRECTORY / "GuestEglProfiles.c"
BUILD = DIRECTORY / "build_guest_profiles.sh"
README = DIRECTORY / "README-guest-profiles.md"
ARTIFACT = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/guest-egl-profiles")


def test_source_is_fixed_four_attempt_matrix_and_never_a_support_claim() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    assert '#define PROFILES_SCHEMA "wowforever.android-guest-egl-profiles/version1"' in source
    assert '#define main unused_context_main' in source and '"GuestEglContext.c"' in source
    assert 'dlopen("guest/lib/libEGL.so.1.1.0", RTLD_NOW | RTLD_LOCAL)' in source
    assert "eglGetConfigAttrib" in source and "owner_is_loader" in source
    assert "EGL_PLATFORM_SURFACELESS_MESA" in source
    assert "EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_SURFACE_TYPE, 0, EGL_NONE" in source
    assert "EGL_CONFORMANT" in source and "EGL_CONFIG_ID" in source
    for name in ("OpenGL3.3CORE", "OpenGL3.3COMPAT", "OpenGL3.2CORE", "OpenGL3.0DEFAULT"):
        assert name in source
    assert "EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR" in source
    assert "actualProfileKnown" in source and "GL_CONTEXT_PROFILE_MASK" in source
    assert "PROFILE_MATRIX_MEASURED" in source
    assert "profiles_context_qualified" not in source
    assert "context_server_live(&contextIdentity, cwd)" in source
    for flag in ("hardwareRenderingVerified", "triangleVerified", "presentationVerified", "d3dVerified", "wowVerified"):
        assert f'\\"{flag}\\":false' in source
    assert "eglSwapBuffers" not in source and "glDraw" not in source
    assert "signal(" not in source and "kill(" not in source and "connect(" not in source
    assert "LD_PRELOAD" not in source
    assert "VTEST_SOCKET_NAME" not in source  # native consumes the fixed environment, it does not choose it


def test_build_recipe_is_strict_pinned_and_external() -> None:
    checked = subprocess.run(["bash", "-n", str(BUILD)], capture_output=True, text=True)
    assert checked.returncode == 0, checked.stderr
    recipe = BUILD.read_text(encoding="utf-8")
    assert "24.0.8215888" in recipe and "aarch64-linux-android29-clang" in recipe
    assert "--build-id=none" in recipe and "-Wall -Wextra -Werror" in recipe
    assert '"$BUILD_TMP/GuestEglProfiles.c" -ldl -lc' in recipe
    assert "64 * 1024" in recipe and "llvm-readelf" in recipe
    assert "30c43814697a22dc72ee4e3b1eeb8ec9d6b6af228ee9c4ffc21e15b3fe8d9ce9" in recipe
    assert "1cc803fbcbef9a7d1743966702c733fc63b8e311a3aa77f8020ee7594e11d216" in recipe
    assert "b318221194b72c0bff5318457625a4595cf874a420be7c0bc3be08d43d1afa28" in recipe
    assert "run_guest_loader_usb.load_candidate()" in recipe
    assert "ls -ld -- \"$ARTIFACT_ROOT\" \"$CONTROLS\" \"$CANDIDATE\"" in recipe


def test_server_identity_regression_uses_reviewed_exact_argv_helper() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    context = (DIRECTORY / "GuestEglContext.c").read_text(encoding="utf-8")
    helper_start = context.index("static int context_server_live(")
    helper_end = context.index("static int context_scan_extensions(", helper_start)
    helper = context[helper_start:helper_end]
    assert "const char *args[] = {\"--no-loop-or-fork\", \"--use-gles\", \"--socket-path\", \"vtest.sock\"}" in helper
    assert "for (unsigned j = 0U; j < 4U; ++j)" in helper
    assert "if (size != want || memcmp(command, expectedCommand, want)) return 0;" in helper
    assert "ticks != identity->startTicks" in helper
    assert "uid != EXPECTED_UID" in helper
    assert "profiles_read_ticks" not in source
    assert "return context_server_live(&contextIdentity, cwd);" in source


def test_host_encoder_and_classification_branches_are_inert(tmp_path: Path) -> None:
    compiler = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
    if compiler is None:
        pytest.skip("no host C compiler available")
    source = SOURCE.read_text(encoding="utf-8")
    block = source[source.index("/* HOST-TEST-BEGIN:"): source.index("/* HOST-TEST-END */") + len("/* HOST-TEST-END */")]
    harness = tmp_path / "profiles_fixture.c"
    harness.write_text(
        """
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#define PROFILE_TESTING 1
#define PROFILES_ATTEMPT_COUNT 4U
#define EGL_SUCCESS 0x3000
typedef struct ProfilesAttempt {
    const char *name; int requestedMajor, requestedMinor, requestedProfileKnown, requestedCore;
    int preServerLive, createCalled, createKnown, createError, created;
    int makeCurrentCalled, makeCurrentKnown, makeCurrentOk, makeCurrentError;
    int actualValid, actualMajor, actualMinor, actualProfileKnown, actualProfile, actualCore;
    unsigned int glError; char vendor[512], renderer[512], version[512], glslVersion[512];
    int rendererVirgl, softwareFallback, unbindAttempted, unbindOk, unbindError;
    int destroyAttempted, destroyOk, destroyError, cleanupValid, postServerLive, deadlineValid;
} ProfilesAttempt;
""" + block + """
int main(void) {
    ProfilesAttempt attempts[4];
    char encoded[64];
    size_t i;
    memset(attempts, 0, sizeof(attempts));
    if (strcmp(profiles_attempt_name(0), "OpenGL3.3CORE") ||
        strcmp(profiles_attempt_name(1), "OpenGL3.3COMPAT") ||
        strcmp(profiles_attempt_name(2), "OpenGL3.2CORE") ||
        strcmp(profiles_attempt_name(3), "OpenGL3.0DEFAULT")) return 1;
    if (!profiles_request_uses_profile(0) || !profiles_request_uses_profile(2) ||
        profiles_request_uses_profile(3) || !profiles_request_is_core(0) || profiles_request_is_core(1)) return 2;
    if (!profiles_create_result_valid(0, 1, 0x3009) || !profiles_create_result_valid(1, 1, 0x3000) ||
        profiles_create_result_valid(0, 1, 0x3000) || profiles_create_result_valid(1, 1, 0x3009)) return 3;
    if (!profiles_config_attribute_result_valid(1, 1, 1, 0x3000) ||
        profiles_config_attribute_result_valid(0, 1, 1, 0x3000) ||
        profiles_config_attribute_result_valid(1, 0, 1, 0x3000)) return 4;
    if (!profiles_cleanup_result_valid(0, 0, 7) || !profiles_cleanup_result_valid(1, 1, 0x3000) ||
        profiles_cleanup_result_valid(1, 0, 0x3000)) return 5;
    for (i = 0; i < 4; ++i) {
        attempts[i].name = profiles_attempt_name(i);
        attempts[i].preServerLive = attempts[i].postServerLive = attempts[i].deadlineValid = 1;
        attempts[i].createKnown = 1; attempts[i].createError = 0x3009;
        attempts[i].cleanupValid = 1;
    }
    if (!profiles_matrix_measured(1, 1, 1, 1, 1, attempts)) return 6;
    attempts[0].created = attempts[0].makeCurrentKnown = 1;
    attempts[0].createError = EGL_SUCCESS;
    attempts[0].makeCurrentError = EGL_SUCCESS;
    if (profiles_attempt_result_valid(&attempts[0])) return 9;
    attempts[0].makeCurrentError = 0x3009;
    if (!profiles_attempt_result_valid(&attempts[0])) return 10;
    attempts[0].created = attempts[0].makeCurrentKnown = 0;
    attempts[0].createError = 0x3009;
    attempts[2].destroyAttempted = attempts[2].destroyOk = 1;
    attempts[2].destroyError = 0x3009;
    if (profiles_matrix_measured(1, 1, 1, 1, 1, attempts)) return 7;
    if (!profiles_encode_status(encoded, sizeof(encoded), "UNKNOWN") ||
        strcmp(encoded, "{\\\"status\\\":\\\"UNKNOWN\\\"}")) return 8;
    return 0;
}
""",
        encoding="utf-8",
    )
    executable = tmp_path / "profiles_fixture"
    built = subprocess.run([compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", str(harness), "-o", str(executable)], capture_output=True, text=True)
    assert built.returncode == 0, built.stdout + built.stderr
    ran = subprocess.run([str(executable)], capture_output=True)
    assert ran.returncode == 0, ran.stderr.decode(errors="replace")


@pytest.mark.skipif(
    not Path("/home/adrian/android-sdk/ndk/24.0.8215888/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang").is_file(),
    reason="pinned local Android NDK is unavailable",
)
def test_real_arm64_crossbuild_manifest_and_elf_contract() -> None:
    completed = subprocess.run(["bash", str(BUILD)], cwd=ROOT, capture_output=True, text=True, timeout=120)
    assert completed.returncode == 0, completed.stdout + completed.stderr
    elf = ARTIFACT / "GuestEglProfiles"
    manifest_path = ARTIFACT / "manifest.json"
    readelf_path = ARTIFACT / "GuestEglProfiles.readelf.txt"
    assert elf.is_file() and elf.stat().st_size <= 64 * 1024
    data = elf.read_bytes()
    assert data[:4] == b"\x7fELF" and data[4:6] == b"\x02\x01"
    assert struct.unpack_from("<H", data, 16)[0] == 3
    assert struct.unpack_from("<H", data, 18)[0] == 183
    manifest = json.loads(manifest_path.read_text(encoding="ascii"))
    assert manifest["sha256"] == hashlib.sha256(data).hexdigest()
    assert manifest["size"] == len(data)
    assert set(manifest["neededLibraries"]) == {"libc.so", "libdl.so"}
    assert manifest["sourceSha256"][SOURCE.name] == hashlib.sha256(SOURCE.read_bytes()).hexdigest()
    assert manifest["sourceSha256"][README.name] == hashlib.sha256(README.read_bytes()).hexdigest()
    assert "Type:                              DYN" in readelf_path.read_text(encoding="ascii")


def test_actual_complete_encoder_has_four_valid_json_attempts(tmp_path: Path) -> None:
    compiler = shutil.which("clang") or shutil.which("cc")
    if not compiler:
        pytest.skip("no host C compiler")
    # Match the existing native recipe's private include preparation, not a mock
    # of the encoder. Repository translation units remain unchanged.
    for name in ("GuestEglProfiles.c", "GuestEglContext.c", "GuestEglLoader.c"):
        source = (DIRECTORY / name).read_text(encoding="utf-8")
        if name == "GuestEglContext.c":
            needle = "int main(int argc, char **argv) {"
            assert source.count(needle) == 1
            source = source.replace(needle, "int unused_context_main(int argc, char **argv) {", 1)
        (tmp_path / name).write_text(source, encoding="utf-8")
    harness = tmp_path / "full_encoder.c"
    harness.write_text(
        '#define _GNU_SOURCE\n#define GUEST_PROFILES_TESTING 1\n'
        '#include "GuestEglProfiles.c"\n'
        'int main(void) { ProfilesState state; Identity identity; char out[PROFILES_OUTPUT_MAX + 1]; '
        'memset(&state, 0, sizeof(state)); memset(&identity, 0, sizeof(identity)); '
        'for(size_t j=0;j<4;j++) profiles_init_attempt(&state.attempts[j], j); '
        'if(!profiles_build_output(&state, "VTEST-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", &identity, out, sizeof(out))) return 1; '
        'return fputs(out, stdout) == EOF; }\n', encoding="utf-8")
    binary = tmp_path / "full_encoder"
    built = subprocess.run([compiler, "-std=c11", "-Wno-unknown-pragmas", "-Wno-unused-function", str(harness), "-ldl", "-o", str(binary)], capture_output=True, text=True)
    assert built.returncode == 0, built.stderr
    result = subprocess.run([str(binary)], capture_output=True, check=True)
    document = json.loads(result.stdout)
    assert document["contextQualified"] is False
    assert [row["name"] for row in document["attempts"]] == ["OpenGL3.3CORE", "OpenGL3.3COMPAT", "OpenGL3.2CORE", "OpenGL3.0DEFAULT"]
