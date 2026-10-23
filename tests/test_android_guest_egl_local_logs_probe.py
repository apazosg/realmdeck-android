"""Offline build and environment-diff gates for the guest EGL local-logs ELF."""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
DIRECTORY = ROOT / "scripts/diagnostics/android-vtest"
CONTEXT = DIRECTORY / "GuestEglContext.c"
LOADER = DIRECTORY / "GuestEglLoader.c"
PATCH = DIRECTORY / "guest-egl-local-logs.patch"
BUILD = DIRECTORY / "build_guest_egl_log.sh"
README = DIRECTORY / "README-guest-egl-local-logs.md"
ARTIFACT = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/guest-egl-local-logs")
ELF = ARTIFACT / "GuestEglLocalLogs"

CONTEXT_SHA256 = "32764d6981a8989c1f3479c59c247fae43f38e326b352aa0f7d3bc36be58e29f"
LOADER_SHA256 = "4338dd9adcee54e4d91a371cd11d6f0bddc1bb77aa21fb0e1d9d4189e929dec6"
OLD_NAMES = (
    "PATH",
    "ANDROID_ROOT",
    "ANDROID_DATA",
    "LD_LIBRARY_PATH",
    "TMPDIR",
    "__EGL_VENDOR_LIBRARY_FILENAMES",
    "GALLIUM_DRIVER",
    "VTEST_SOCKET_NAME",
)
NEW_NAMES = OLD_NAMES + ("EGL_LOG_LEVEL", "MESA_LOG", "MESA_LOG_LEVEL")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _apply_patch(tmp_path: Path, patch_text: str | None = None) -> tuple[Path, Path]:
    context = tmp_path / "GuestEglContext.c"
    loader = tmp_path / "GuestEglLoader.c"
    shutil.copyfile(CONTEXT, context)
    shutil.copyfile(LOADER, loader)
    patch = tmp_path / "guest-egl-local-logs.patch"
    patch.write_text(PATCH.read_text(encoding="utf-8") if patch_text is None else patch_text, encoding="utf-8")
    result = subprocess.run(
        ["/usr/bin/patch", "--batch", "--fuzz=0", f"--directory={tmp_path}"],
        input=patch.read_text(encoding="utf-8"),
        text=True,
        capture_output=True,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    return context, loader


def test_inputs_are_fixed_and_canonical_patch_changes_only_loader_environment(tmp_path: Path) -> None:
    assert _sha256(CONTEXT) == CONTEXT_SHA256
    assert _sha256(LOADER) == LOADER_SHA256
    original_loader = LOADER.read_text(encoding="utf-8")
    original_context = CONTEXT.read_text(encoding="utf-8")
    context, loader = _apply_patch(tmp_path)
    patched_loader = loader.read_text(encoding="utf-8")
    assert context.read_text(encoding="utf-8") == original_context
    assert patched_loader != original_loader
    assert patched_loader.count('"EGL_LOG_LEVEL", "MESA_LOG", "MESA_LOG_LEVEL"') == 2
    assert 'strcmp(values[8], "debug") == 0' in patched_loader
    assert 'strcmp(values[9], "file") == 0' in patched_loader
    assert 'strcmp(values[10], "debug") == 0' in patched_loader
    expected_loader = original_loader
    old_allowlist = '"__EGL_VENDOR_LIBRARY_FILENAMES", "GALLIUM_DRIVER", "VTEST_SOCKET_NAME"'
    new_allowlist = old_allowlist + ', "EGL_LOG_LEVEL", "MESA_LOG", "MESA_LOG_LEVEL"'
    assert expected_loader.count(old_allowlist) == 2
    expected_loader = expected_loader.replace(old_allowlist, new_allowlist)
    expected_loader = expected_loader.replace(
        'strcmp(values[7], "vtest.sock") == 0;',
        'strcmp(values[7], "vtest.sock") == 0 && strcmp(values[8], "debug") == 0 &&\n'
        '           strcmp(values[9], "file") == 0 && strcmp(values[10], "debug") == 0;',
    )
    assert patched_loader == expected_loader


def test_malformed_source_and_noncanonical_patch_fail_fuzz_zero(tmp_path: Path) -> None:
    malformed_source = LOADER.read_text(encoding="utf-8").replace('"VTEST_SOCKET_NAME"', '"VTEST_SOCKET_OTHER"', 1)
    bad_source = tmp_path / "bad-source"
    bad_source.mkdir()
    (bad_source / "GuestEglContext.c").write_text(CONTEXT.read_text(encoding="utf-8"), encoding="utf-8")
    (bad_source / "GuestEglLoader.c").write_text(malformed_source, encoding="utf-8")
    patch_text = PATCH.read_text(encoding="utf-8")
    failed_source = subprocess.run(
        ["/usr/bin/patch", "--batch", "--fuzz=0", f"--directory={bad_source}"],
        input=patch_text,
        text=True,
        capture_output=True,
    )
    assert failed_source.returncode != 0
    valid_patch_root = tmp_path / "valid-patch"
    valid_patch_root.mkdir()
    (valid_patch_root / "GuestEglLoader.c").write_text(LOADER.read_text(encoding="utf-8"), encoding="utf-8")
    failed_patch = patch_text.replace('"VTEST_SOCKET_NAME"', '"VTEST_SOCKET_OTHER"', 1)
    failed_patch_result = subprocess.run(
        ["/usr/bin/patch", "--batch", "--fuzz=0", f"--directory={valid_patch_root}"],
        input=failed_patch,
        text=True,
        capture_output=True,
    )
    assert failed_patch_result.returncode != 0


def test_recipe_is_pinned_and_refuses_ambient_flags() -> None:
    syntax = subprocess.run(["bash", "-n", str(BUILD)], capture_output=True, text=True)
    assert syntax.returncode == 0, syntax.stderr
    recipe = BUILD.read_text(encoding="utf-8")
    assert CONTEXT_SHA256 in recipe and LOADER_SHA256 in recipe
    assert "24.0.8215888" in recipe and "aarch64-linux-android29-clang" in recipe
    assert "--fuzz=0" in recipe and "64 * 1024" in recipe
    assert 'assert set(needed) == {"libc.so", "libdl.so"}' in recipe
    assert "CFLAGS CPPFLAGS CXXFLAGS LDFLAGS" in recipe
    assert 'env" -i PATH=/usr/bin:/bin' in recipe


def _compile_environment_fixture(tmp_path: Path) -> Path:
    compiler = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
    if compiler is None:
        pytest.skip("no existing host C compiler available")
    _, patched_loader = _apply_patch(tmp_path)
    harness = tmp_path / "environment_fixture.c"
    harness.write_text(
        f"""
#define _GNU_SOURCE
#define main guest_loader_main_unused
#include "{patched_loader}"
#undef main
#include <stdlib.h>

extern char **environ;

static void set_paths(Paths *paths) {{
    memset(paths, 0, sizeof(*paths));
    (void)snprintf(paths->cwd, sizeof(paths->cwd), "/run");
    (void)snprintf(paths->ldLibraryPath, sizeof(paths->ldLibraryPath), "guest/lib:/system/lib64");
    (void)snprintf(paths->vendorJson, sizeof(paths->vendorJson), "/run/guest/share/glvnd/egl_vendor.d/50_mesa.json");
}}

static int check(char **environment, int expected) {{
    Paths paths;
    set_paths(&paths);
    environ = environment;
    return exact_environment(&paths) == expected ? 0 : 1;
}}

int main(void) {{
    char *valid[] = {{
        "PATH=/system/bin:/system/xbin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
        "LD_LIBRARY_PATH=guest/lib:/system/lib64", "TMPDIR=/run",
        "__EGL_VENDOR_LIBRARY_FILENAMES=/run/guest/share/glvnd/egl_vendor.d/50_mesa.json",
        "GALLIUM_DRIVER=virpipe", "VTEST_SOCKET_NAME=vtest.sock",
        "EGL_LOG_LEVEL=debug", "MESA_LOG=file", "MESA_LOG_LEVEL=debug", NULL
    }};
    char *unknown[] = {{"PATH=/system/bin:/system/xbin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
        "LD_LIBRARY_PATH=guest/lib:/system/lib64", "TMPDIR=/run",
        "__EGL_VENDOR_LIBRARY_FILENAMES=/run/guest/share/glvnd/egl_vendor.d/50_mesa.json",
        "GALLIUM_DRIVER=virpipe", "VTEST_SOCKET_NAME=vtest.sock", "EGL_LOG_LEVEL=debug",
        "MESA_LOG=file", "MESA_LOG_LEVEL=debug", "NOPE=1", NULL}};
    char *duplicate[] = {{"PATH=/system/bin:/system/xbin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
        "LD_LIBRARY_PATH=guest/lib:/system/lib64", "TMPDIR=/run",
        "__EGL_VENDOR_LIBRARY_FILENAMES=/run/guest/share/glvnd/egl_vendor.d/50_mesa.json",
        "GALLIUM_DRIVER=virpipe", "VTEST_SOCKET_NAME=vtest.sock", "EGL_LOG_LEVEL=debug",
        "MESA_LOG=file", "MESA_LOG_LEVEL=debug", "MESA_LOG_LEVEL=debug", NULL}};
    if (check(valid, 1) || check(unknown, 0) || check(duplicate, 0)) return 1;
    if (!environment_name_allowed("EGL_LOG_LEVEL") || !environment_name_allowed("MESA_LOG") ||
        !environment_name_allowed("MESA_LOG_LEVEL") || environment_name_allowed("NOPE")) return 2;
    return 0;
}}
""",
        encoding="utf-8",
    )
    executable = tmp_path / "environment_fixture"
    compiled = subprocess.run(
        [compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", str(harness), "-ldl", "-o", str(executable)],
        capture_output=True,
        text=True,
    )
    assert compiled.returncode == 0, compiled.stdout + compiled.stderr
    return executable


def test_actual_c_environment_gate_is_host_inert_and_not_a_device_pass(tmp_path: Path) -> None:
    executable = _compile_environment_fixture(tmp_path)
    ran = subprocess.run([str(executable)], capture_output=True, check=False)
    assert ran.returncode == 0, ran.stderr.decode(errors="replace")
    assert b"GUEST_CONTEXT_PASS" not in ran.stdout


def test_full_context_encoder_host_compiles_only(tmp_path: Path) -> None:
    compiler = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
    if compiler is None:
        pytest.skip("no existing host C compiler available")
    context, _ = _apply_patch(tmp_path)
    compiled = subprocess.run(
        [compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", "-fsyntax-only", str(context)],
        capture_output=True,
        text=True,
    )
    assert compiled.returncode == 0, compiled.stdout + compiled.stderr


def test_existing_artifact_manifest_is_bounded_when_present() -> None:
    manifest = ARTIFACT / "manifest.json"
    if not manifest.is_file():
        pytest.skip("local NDK artifact has not been built")
    payload = json.loads(manifest.read_text(encoding="ascii"))
    assert payload["file"] == "GuestEglLocalLogs"
    assert payload["size"] <= 64 * 1024
    assert payload["sha256"] == _sha256(ELF)
    assert payload["originalSourceSha256"] == {"GuestEglContext.c": CONTEXT_SHA256, "GuestEglLoader.c": LOADER_SHA256}
    assert payload["ndk"] == "24.0.8215888"
