"""Offline contract, forwarding, and bounded-output tests for the DRI observer."""

from __future__ import annotations

import hashlib
import json
import shutil
import struct
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
PROBE = ROOT / "scripts/diagnostics/android-vtest"
CONTEXT = PROBE / "GuestEglContext.c"
LOADER = PROBE / "GuestEglLoader.c"
OBSERVER = PROBE / "DriReturnObserver.c"
LOG_PATCH = PROBE / "guest-egl-local-logs.patch"
OBSERVER_PATCH = PROBE / "guest-egl-dri-observer.patch"
BUILD = PROBE / "build_dri_observer.sh"
README = PROBE / "README-dri-observer.md"
ARTIFACT = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/dri-return-observer")

CONTEXT_SHA256 = "32764d6981a8989c1f3479c59c247fae43f38e326b352aa0f7d3bc36be58e29f"
LOADER_SHA256 = "4338dd9adcee54e4d91a371cd11d6f0bddc1bb77aa21fb0e1d9d4189e929dec6"
LOG_PATCH_SHA256 = "46d47f0bd02f7444d8819ecd791efe97bed5a594772216b015ca822b52e67c9d"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def apply_patches(tmp_path: Path) -> tuple[Path, Path]:
    context = tmp_path / "GuestEglContext.c"
    loader = tmp_path / "GuestEglLoader.c"
    shutil.copyfile(CONTEXT, context)
    shutil.copyfile(LOADER, loader)
    for patch in (LOG_PATCH, OBSERVER_PATCH):
        result = subprocess.run(
            ["/usr/bin/patch", "--batch", "--fuzz=0", "--forward", "--no-backup-if-mismatch", f"--directory={tmp_path}"],
            input=patch.read_text(encoding="utf-8"),
            text=True,
            capture_output=True,
        )
        assert result.returncode == 0, result.stdout + result.stderr
    return context, loader


def test_fixed_inputs_and_observer_patch_are_separate(tmp_path: Path) -> None:
    assert sha256(CONTEXT) == CONTEXT_SHA256
    assert sha256(LOADER) == LOADER_SHA256
    assert sha256(LOG_PATCH) == LOG_PATCH_SHA256
    context, loader = apply_patches(tmp_path)
    assert context.read_text(encoding="utf-8") == CONTEXT.read_text(encoding="utf-8")
    result = loader.read_text(encoding="utf-8")
    assert result.count('"LD_PRELOAD"') == 2
    assert '"LD_PRELOAD"' in result
    assert '"%s/guest/observer/libdri_return_observer.so"' in result
    assert 'strcmp(values[11], observerPath) == 0' in result
    assert len(list(tmp_path.glob("*.orig"))) == 0
    assert len(list(tmp_path.glob("*.rej"))) == 0


def test_observer_source_fixes_nine_argument_abi_and_rejects_unsafe_resolution() -> None:
    source = OBSERVER.read_text(encoding="utf-8")
    assert "unsigned *error, void *data, bool thread_safe" in source
    assert "RTLD_NOLOAD | RTLD_NOW | RTLD_LOCAL" in source
    assert "RTLD_NEXT" not in source
    assert '"guest/lib/libgallium-26.2.4.so"' in source
    assert '"driCreateContextAttribs"' in source
    assert "dladdr" in source and "realpath" in source and "O_NOFOLLOW" in source
    assert "_exit(125)" in source
    assert "WFDRI_RETURN" in source
    assert "MAX_ATTRIBUTE_PAIRS 16U" in source
    assert "MAX_RECORDS 4U" in source and "MAX_OUTPUT_BYTES 4096U" in source
    assert "__thread int recursion_guard" in source
    for forbidden in ("eglCreateContext", "glX", "ioctl", "ptrace", "sigaction", "constructor"):
        assert forbidden not in source


def _host_fixture(tmp_path: Path) -> Path:
    compiler = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
    if compiler is None:
        pytest.skip("no existing host C compiler available")
    harness = tmp_path / "dri_observer_fixture.c"
    harness.write_text(
        f"""
#define _GNU_SOURCE
#define DRI_RETURN_OBSERVER_HOST_TEST 1
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>
#include "{OBSERVER}"

static _Atomic int calls;
static _Atomic int failed;
static void *wanted_screen;
static const void *wanted_config;
static void *wanted_shared;
static void *wanted_data;
static const uint32_t *wanted_attributes;
static unsigned wanted_count;
static unsigned *wanted_error;
static int wanted_api;
static bool wanted_thread_safe;
static int recurse;
static uint32_t values[] = {{11U, 22U, 33U, 44U}};
static void *sentinel = (void *)(uintptr_t)0x1234U;

static void *provider(void *screen, int api, const void *config, void *shared, unsigned count,
                     const uint32_t *attributes, unsigned *error, void *data, bool thread_safe) {{
    ++calls;
    if (recurse) return driCreateContextAttribs(screen, api, config, shared, count, attributes, error, data, thread_safe);
    if (screen != wanted_screen || api != wanted_api || config != wanted_config || shared != wanted_shared ||
        count != wanted_count || attributes != wanted_attributes || error != wanted_error || data != wanted_data ||
        thread_safe != wanted_thread_safe) failed = 1;
    if (error != NULL) *error = 77U;
    errno = 91;
    return sentinel;
}}

static void *thread_entry(void *unused) {{
    (void)unused;
    for (int i = 0; i < 8; ++i) {{
        if (driCreateContextAttribs(wanted_screen, wanted_api, wanted_config, wanted_shared, 0U, NULL,
                                    NULL, wanted_data, false) != sentinel) failed = 1;
    }}
    return NULL;
}}

static int run_forward(void) {{
    unsigned error = 9U;
    int before_calls;
    wanted_screen = (void *)(uintptr_t)0x1001U;
    wanted_api = 4;
    wanted_config = (void *)(uintptr_t)0x1002U;
    wanted_shared = (void *)(uintptr_t)0x1003U;
    wanted_count = 2U;
    wanted_attributes = values;
    wanted_error = &error;
    wanted_data = (void *)(uintptr_t)0x1004U;
    wanted_thread_safe = true;
    dri_observer_test_reset();
    dri_observer_test_set_provider(provider);
    errno = 17;
    if (driCreateContextAttribs(wanted_screen, wanted_api, wanted_config, wanted_shared, wanted_count,
                                wanted_attributes, wanted_error, wanted_data, true) != sentinel ||
        error != 77U || errno != 91 || calls == 0 || failed) return 1;
    before_calls = calls;
    wanted_thread_safe = false;
    error = 3U;
    if (driCreateContextAttribs(wanted_screen, wanted_api, wanted_config, wanted_shared, wanted_count,
                                wanted_attributes, wanted_error, wanted_data, false) != sentinel ||
        error != 77U || errno != 91 || calls != before_calls + 1) return 2;
    return 0;
}}

static int run_bounds(void) {{
    unsigned error = 0U;
    dri_observer_test_reset();
    dri_observer_test_set_provider(provider);
    wanted_screen = NULL; wanted_api = 1; wanted_config = NULL; wanted_shared = NULL;
    wanted_count = 17U; wanted_attributes = (const uint32_t *)(uintptr_t)1U;
    wanted_error = &error; wanted_data = NULL; wanted_thread_safe = false;
    if (driCreateContextAttribs(NULL, 1, NULL, NULL, 17U, wanted_attributes, &error, NULL, false) != sentinel) return 1;
    wanted_count = 1U; wanted_attributes = values; wanted_error = NULL;
    if (driCreateContextAttribs(NULL, 1, NULL, NULL, 1U, values, NULL, NULL, false) != sentinel) return 2;
    {{
        uint32_t many[32];
        for (unsigned i = 0; i < 32; ++i) many[i] = i;
        wanted_attributes = many;
        for (unsigned count = 3; count <= 16; count += 13) {{
            wanted_count = count;
            if (driCreateContextAttribs(NULL, 1, NULL, NULL, count, many, NULL, NULL, false) != sentinel) return 3;
        }}
    }}
    return failed;
}}

static int run_concurrent(void) {{
    pthread_t threads[4];
    dri_observer_test_reset();
    dri_observer_test_set_provider(provider);
    wanted_screen = NULL; wanted_api = 2; wanted_config = NULL; wanted_shared = NULL;
    wanted_count = 0U; wanted_attributes = NULL; wanted_error = NULL; wanted_data = NULL; wanted_thread_safe = false;
    calls = 0; failed = 0;
    for (size_t i = 0; i < 4; ++i) if (pthread_create(&threads[i], NULL, thread_entry, NULL) != 0) return 1;
    for (size_t i = 0; i < 4; ++i) if (pthread_join(threads[i], NULL) != 0) return 2;
    return failed || calls != 32;
}}

static int run_invalid_scope(void) {{
    dri_observer_test_reset();
    dri_observer_test_set_provider(provider);
    dri_observer_test_set_scope(0);
    (void)driCreateContextAttribs(NULL, 0, NULL, NULL, 0U, NULL, NULL, NULL, false);
    return 99;
}}

static int run_invalid_provider(void) {{
    dri_observer_test_reset();
    dri_observer_test_set_provider(NULL);
    (void)driCreateContextAttribs(NULL, 0, NULL, NULL, 0U, NULL, NULL, NULL, false);
    return 99;
}}

static int run_recursion(void) {{
    dri_observer_test_reset();
    dri_observer_test_set_provider(provider);
    recurse = 1;
    (void)driCreateContextAttribs(NULL, 0, NULL, NULL, 0U, NULL, NULL, NULL, false);
    return 99;
}}

static int run_record_limit(void) {{
    dri_observer_test_reset();
    dri_observer_test_set_provider(provider);
    wanted_screen = NULL; wanted_api = 3; wanted_config = NULL; wanted_shared = NULL;
    wanted_count = 0U; wanted_attributes = NULL; wanted_error = NULL; wanted_data = NULL; wanted_thread_safe = false;
    for (int i = 0; i < 8; ++i) if (driCreateContextAttribs(NULL, 3, NULL, NULL, 0U, NULL, NULL, NULL, false) != sentinel) return 1;
    return 0;
}}

int main(int argc, char **argv) {{
    if (argc != 2) return 2;
    if (!strcmp(argv[1], "forward")) return run_forward();
    if (!strcmp(argv[1], "bounds")) return run_bounds();
    if (!strcmp(argv[1], "concurrent")) return run_concurrent();
    if (!strcmp(argv[1], "scope")) return run_invalid_scope();
    if (!strcmp(argv[1], "provider")) return run_invalid_provider();
    if (!strcmp(argv[1], "recursion")) return run_recursion();
    if (!strcmp(argv[1], "limit")) return run_record_limit();
    return 3;
}}
""",
        encoding="utf-8",
    )
    executable = tmp_path / "dri_observer_fixture"
    result = subprocess.run(
        [compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", "-DDRI_RETURN_OBSERVER_HOST_TEST",
         "-pthread", str(harness), "-ldl", "-o", str(executable)],
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    return executable


def test_host_forwarding_bounds_concurrency_and_record_limit(tmp_path: Path) -> None:
    fixture = _host_fixture(tmp_path)
    forwarded = subprocess.run([str(fixture), "forward"], capture_output=True, check=False)
    assert forwarded.returncode == 0, forwarded.stderr.decode(errors="replace")
    rows = [json.loads(line.removeprefix(b"WFDRI_RETURN ").decode("ascii")) for line in forwarded.stderr.splitlines()]
    assert [row["threadSafe"] for row in rows] == [True, False]
    assert all(row["providerVerified"] and row["forwarded"] and row["resultPresent"] for row in rows)
    assert rows[0]["error"] == 77 and rows[0]["incomingErrno"] == 17 and rows[0]["returnedErrno"] == 91
    assert rows[0]["attributePairs"] == [[11, 22], [33, 44]]

    bounds = subprocess.run([str(fixture), "bounds"], capture_output=True, check=False)
    assert bounds.returncode == 0
    bound_rows = [json.loads(line.removeprefix(b"WFDRI_RETURN ").decode("ascii")) for line in bounds.stderr.splitlines()]
    assert bound_rows[0]["attributesObserved"] is False and bound_rows[0]["attributesOversize"] is True
    assert bound_rows[1]["errorPresent"] is False and bound_rows[1]["error"] is None
    assert bound_rows[1]["attributePairs"] == [[11, 22]]
    assert len(bound_rows[2]["attributePairs"]) == 3
    assert len(bound_rows[3]["attributePairs"]) == 16
    assert bound_rows[3]["attributePairs"][-1] == [30, 31]

    concurrent = subprocess.run([str(fixture), "concurrent"], capture_output=True, check=False)
    assert concurrent.returncode == 0
    concurrent_rows = concurrent.stderr.splitlines()
    assert len(concurrent_rows) == 4
    assert all(line.startswith(b"WFDRI_RETURN ") for line in concurrent_rows)
    assert len(concurrent.stderr) <= 4096

    limited = subprocess.run([str(fixture), "limit"], capture_output=True, check=False)
    assert limited.returncode == 0
    assert len(limited.stderr.splitlines()) == 4
    assert len(limited.stderr) <= 4096


@pytest.mark.parametrize("mode", ["scope", "provider", "recursion"])
def test_invalid_scope_provider_and_recursion_exit_125(tmp_path: Path, mode: str) -> None:
    fixture = _host_fixture(tmp_path)
    result = subprocess.run([str(fixture), mode], capture_output=True, check=False)
    assert result.returncode == 125


def test_build_recipe_and_existing_artifact_contract() -> None:
    syntax = subprocess.run(["bash", "-n", str(BUILD)], capture_output=True, text=True)
    assert syntax.returncode == 0, syntax.stderr
    recipe = BUILD.read_text(encoding="utf-8")
    assert CONTEXT_SHA256 in recipe and LOADER_SHA256 in recipe and LOG_PATCH_SHA256 in recipe
    assert "24.0.8215888" in recipe and "aarch64-linux-android29-clang" in recipe
    assert "--fuzz=0" in recipe and "--forward" in recipe and "--build-id=none" in recipe
    assert "CFLAGS CPPFLAGS CXXFLAGS LDFLAGS" in recipe
    assert '"libc.so", "libdl.so"' in recipe
    assert '"driCreateContextAttribs"' in recipe


def test_real_artifact_manifest_is_bounded_when_present() -> None:
    manifest_path = ARTIFACT / "manifest.json"
    if not manifest_path.is_file():
        pytest.skip("local NDK artifact has not been built")
    payload = json.loads(manifest_path.read_text(encoding="ascii"))
    assert payload["schema"] == "wowforever.android-dri-return-observer"
    assert payload["originalSourceSha256"] == {"GuestEglContext.c": CONTEXT_SHA256, "GuestEglLoader.c": LOADER_SHA256}
    for name, expected in (("child", "GuestEglDriObservation"), ("observer", "libdri_return_observer.so")):
        record = payload["artifacts"][name]
        data = (ARTIFACT / record["file"]).read_bytes()
        assert record["file"] == expected
        assert record["size"] == len(data) <= 64 * 1024
        assert record["sha256"] == hashlib.sha256(data).hexdigest()
        assert record["elfType"] == "ET_DYN" and record["machine"] == 183
        assert set(record["neededLibraries"]) == {"libc.so", "libdl.so"}
    assert payload["artifacts"]["child"]["interpreter"] == "/system/bin/linker64"
    assert payload["artifacts"]["observer"]["interpreter"] is None
