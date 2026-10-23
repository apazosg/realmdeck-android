"""Offline contract and synthetic-child tests for the bounded DRI version observer."""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
PROBE = ROOT / "scripts/diagnostics/android-vtest"
OBSERVER = PROBE / "DriVersionObserver.c"
CONTEXT = PROBE / "GuestEglContext.c"
LOADER = PROBE / "GuestEglLoader.c"
LOG_PATCH = PROBE / "guest-egl-local-logs.patch"
VERSION_PATCH = PROBE / "guest-egl-dri-version.patch"
BUILD = PROBE / "build_dri_version_observer.sh"
ARTIFACT = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/dri-version-limit")
PROVIDER = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/virgl-audit/private_egl_candidate-v1/lib/libgallium-26.2.4.so")
PROVIDER_SHA256 = "910cc41d1a77427dafd16c8049425504576879a30767aa7473ab70629ed348b5"


def _compiler() -> str:
    compiler = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
    if compiler is None:
        pytest.skip("no existing host C compiler available")
    return compiler


def _fixture(tmp_path: Path) -> Path:
    harness = tmp_path / "dri_version_fixture.c"
    harness.write_text(
        f"""
#define _GNU_SOURCE
#define DRI_VERSION_OBSERVER_HOST_TEST 1
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
static int failed;
static int changing;
static int touch_screen = 1;
static int expected_thread_safe = 1;
static unsigned next_maximum;
static void *sentinel = (void *)(uintptr_t)0x12345678U;
static unsigned values[] = {{0U, 3U, 1U, 3U}};

static void *provider(void *screen, int api, const void *config, void *shared, unsigned count,
                     const uint32_t *attributes, unsigned *error, void *data, bool thread_safe) {{
    (void)config; (void)shared; (void)data;
    ++calls;
    if (api != 3 || count != 2U || attributes != values || (int)thread_safe != expected_thread_safe) failed = 1;
    if (touch_screen && screen != NULL) *(unsigned *)((unsigned char *)screen + 64U) = changing ? 99U : next_maximum;
    if (error != NULL) *error = next_maximum == 77U ? 0U : 3U;
    errno = 91;
    return changing ? NULL : (next_maximum == 77U ? sentinel : NULL);
}}

static int run_measure(unsigned maximum, int want_result, int expect_inference) {{
    unsigned char screen[68] = {{0}}; unsigned error = 9U; void *result;
    next_maximum = maximum; changing = 0; calls = 0; failed = 0;
    expected_thread_safe = 1; touch_screen = 1;
    *(unsigned *)(screen + 64U) = maximum;
    dri_observer_test_reset(); dri_observer_test_set_provider(provider);
    errno = 17; result = driCreateContextAttribs(screen, 3, (void *)1, (void *)2, 2U, values, &error, (void *)3, true);
    if (calls != 1 || failed || errno != 91 || (result == sentinel) != want_result) return 1;
    (void)expect_inference;
    return 0;
}}

static int run_inaccessible(void) {{
    unsigned error = 3U; calls = 0; failed = 0; next_maximum = 0; touch_screen = 0;
    dri_observer_test_reset(); dri_observer_test_set_provider(provider); errno = 17;
    if (driCreateContextAttribs((void *)(uintptr_t)1U, 3, NULL, NULL, 2U, values, &error, NULL, true) != NULL || calls != 1 || errno != 91) return 1;
    return 0;
}}

static int run_overflow(void) {{
    unsigned error = 3U; uintptr_t p = UINTPTR_MAX - 63U; calls = 0; next_maximum = 0; touch_screen = 0;
    dri_observer_test_reset(); dri_observer_test_set_provider(provider); errno = 17;
    (void)driCreateContextAttribs((void *)p, 3, NULL, NULL, 2U, values, &error, NULL, true);
    return calls == 1 && errno == 91 ? 0 : 1;
}}

static int run_null(void) {{
    unsigned error = 3U; calls = 0; next_maximum = 0; touch_screen = 0;
    dri_observer_test_reset(); dri_observer_test_set_provider(provider); errno = 17;
    (void)driCreateContextAttribs(NULL, 3, NULL, NULL, 2U, values, &error, NULL, true);
    return calls == 1 && errno == 91 ? 0 : 1;
}}

static int run_change(void) {{
    unsigned char screen[68] = {{0}}; unsigned error = 3U; next_maximum = 33U; changing = 1; calls = 0;
    *(unsigned *)(screen + 64U) = 33U; dri_observer_test_reset(); dri_observer_test_set_provider(provider);
    (void)driCreateContextAttribs(screen, 3, NULL, NULL, 2U, values, &error, NULL, true);
    return calls == 1 ? 0 : 1;
}}

static int run_scope(void) {{
    unsigned char screen[68] = {{0}}; unsigned bad[] = {{0U, 3U, 1U, 2U}}; unsigned error = 3U;
    next_maximum = 0; calls = 0; dri_observer_test_reset(); dri_observer_test_set_provider(provider);
    (void)driCreateContextAttribs(screen, 3, NULL, NULL, 2U, bad, &error, NULL, true);
    return calls == 1 ? 0 : 1;
}}

static int run_boolean(void) {{
    unsigned char screen[68] = {{0}}; unsigned error = 3U; next_maximum = 0; calls = 0; failed = 0;
    expected_thread_safe = 0; touch_screen = 1; dri_observer_test_reset(); dri_observer_test_set_provider(provider);
    (void)driCreateContextAttribs(screen, 3, NULL, NULL, 2U, values, &error, NULL, false);
    return calls == 1 && !failed ? 0 : 1;
}}

static void *thread_entry(void *arg) {{
    unsigned char *screen = (unsigned char *)arg; unsigned error = 3U;
    for (int i = 0; i < 8; ++i) (void)driCreateContextAttribs(screen, 3, NULL, NULL, 2U, values, &error, NULL, true);
    return NULL;
}}

static int run_concurrent(void) {{
    unsigned char screens[4][68] = {{0}}; pthread_t threads[4];
    expected_thread_safe = 1; touch_screen = 1; next_maximum = 0; failed = 0; calls = 0;
    dri_observer_test_reset(); dri_observer_test_set_provider(provider);
    for (int i = 0; i < 4; ++i) if (pthread_create(&threads[i], NULL, thread_entry, screens[i]) != 0) return 1;
    for (int i = 0; i < 4; ++i) if (pthread_join(threads[i], NULL) != 0) return 2;
    return calls == 32 && !failed ? 0 : 3;
}}

static int run_limit(void) {{
    unsigned char screen[68] = {{0}}; unsigned error = 3U; expected_thread_safe = 1; touch_screen = 1; next_maximum = 0;
    calls = 0; dri_observer_test_reset(); dri_observer_test_set_provider(provider);
    for (int i = 0; i < 8; ++i) (void)driCreateContextAttribs(screen, 3, NULL, NULL, 2U, values, &error, NULL, true);
    return calls == 8 ? 0 : 1;
}}

static int run_injected(int mode) {{
    unsigned char screen[68] = {{0}}; unsigned error = 3U; next_maximum = 0; calls = 0;
    *(unsigned *)(screen + 64U) = 0U; dri_observer_test_reset(); dri_observer_test_set_provider(provider); dri_observer_test_set_read_mode(mode);
    (void)driCreateContextAttribs(screen, 3, NULL, NULL, 2U, values, &error, NULL, true);
    return calls == 1 ? 0 : 1;
}}

static int run_digest(void) {{
    char output[65];
    return dri_observer_test_sha256_hex((const unsigned char *)"abc", 3U, output, sizeof(output)) &&
           strcmp(output, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") == 0 ? 0 : 1;
}}

int main(int argc, char **argv) {{
    if (argc < 2 || argc > 3) return 2;
    if (!strcmp(argv[1], "layout") && argc == 3)
        return dri_observer_test_provider_layout_path(argv[2]) &&
               dri_observer_test_provider_file_verified(argv[2]) ? 0 : 1;
    if (!strcmp(argv[1], "low")) return run_measure(0U, 0, 1);
    if (!strcmp(argv[1], "equal")) return run_measure(33U, 0, 0);
    if (!strcmp(argv[1], "high")) return run_measure(99U, 0, 0);
    if (!strcmp(argv[1], "result")) return run_measure(77U, 1, 1);
    if (!strcmp(argv[1], "inaccessible")) return run_inaccessible();
    if (!strcmp(argv[1], "overflow")) return run_overflow();
    if (!strcmp(argv[1], "null")) return run_null();
    if (!strcmp(argv[1], "change")) return run_change();
    if (!strcmp(argv[1], "scope")) return run_scope();
    if (!strcmp(argv[1], "boolean")) return run_boolean();
    if (!strcmp(argv[1], "concurrent")) return run_concurrent();
    if (!strcmp(argv[1], "limit")) return run_limit();
    if (!strcmp(argv[1], "deny")) return run_injected(1);
    if (!strcmp(argv[1], "unsupported")) return run_injected(2);
    if (!strcmp(argv[1], "partial")) return run_injected(3);
    if (!strcmp(argv[1], "digest")) return run_digest();
    return 3;
}}
""",
        encoding="utf-8",
    )
    executable = tmp_path / "dri_version_fixture"
    result = subprocess.run(
        [_compiler(), "-std=c11", "-Wall", "-Wextra", "-Werror", "-DDRI_VERSION_OBSERVER_HOST_TEST",
         "-pthread", str(harness), "-ldl", "-o", str(executable)],
        capture_output=True, text=True,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    return executable


def _rows(result: subprocess.CompletedProcess[bytes]) -> list[dict]:
    return [json.loads(line.removeprefix(b"WFDRI_VERSION ").decode("ascii")) for line in result.stderr.splitlines()]


def test_contract_and_patch_are_sibling_only(tmp_path: Path) -> None:
    source = OBSERVER.read_text(encoding="utf-8")
    assert '"driCreateContextAttribs"' in source
    assert "process_vm_readv" in source and "getpid()" in source
    assert "RTLD_NOLOAD | RTLD_NOW | RTLD_LOCAL" in source
    assert "screen + 64" in source or "DRI_SCREEN_FIELD_OFFSET" in source
    assert "MAX_RECORDS 4U" in source and "MAX_OUTPUT_BYTES 4096U" in source
    assert "ptrace" not in source and "sigaction" not in source and "/proc/" + "mem" not in source
    assert "/controls/vtest-loader/" in source
    assert "0x9f,0x11,0x00,0x71" in source
    assert hashlib.sha256(PROVIDER.read_bytes()).hexdigest() == PROVIDER_SHA256
    assert hashlib.sha256(CONTEXT.read_bytes()).hexdigest() == "32764d6981a8989c1f3479c59c247fae43f38e326b352aa0f7d3bc36be58e29f"
    assert hashlib.sha256(LOADER.read_bytes()).hexdigest() == "4338dd9adcee54e4d91a371cd11d6f0bddc1bb77aa21fb0e1d9d4189e929dec6"
    assert 'libdri_version_observer.so' in VERSION_PATCH.read_text(encoding="utf-8")
    assert 'LD_PRELOAD' in VERSION_PATCH.read_text(encoding="utf-8")
    shutil.copyfile(CONTEXT, tmp_path / "GuestEglContext.c")
    shutil.copyfile(LOADER, tmp_path / "GuestEglLoader.c")
    for patch in (LOG_PATCH, VERSION_PATCH):
        result = subprocess.run(
            ["/usr/bin/patch", "--batch", "--fuzz=0", "--forward", "--no-backup-if-mismatch", f"--directory={tmp_path}"],
            input=patch.read_text(encoding="utf-8"), text=True, capture_output=True,
        )
        assert result.returncode == 0, result.stdout + result.stderr
    patched_loader = (tmp_path / "GuestEglLoader.c").read_text(encoding="utf-8")
    assert patched_loader.count('"LD_PRELOAD"') == 2
    assert 'libdri_version_observer.so' in patched_loader
    assert not list(tmp_path.glob("*.orig")) and not list(tmp_path.glob("*.rej"))


def test_host_synthetic_reads_forwarding_errno_and_inference(tmp_path: Path) -> None:
    fixture = _fixture(tmp_path)
    low = subprocess.run([str(fixture), "low"], capture_output=True)
    assert low.returncode == 0
    row = _rows(low)[0]
    assert row["layoutVerified"] and row["scopeMatches"] and row["forwarded"]
    assert row["maximumBefore"] == 0 and row["maximumAfter"] == 0
    assert row["measurementValid"] and row["checkInference"] == "EARLY_CHECK_WOULD_REJECT"
    assert row["incomingErrno"] == 17 and row["returnedErrno"] == 91
    assert row["threadSafe"] is True and row["requestedVersion"] == 33 and row["attributePairs"] == [[0, 3], [1, 3]]

    equal = subprocess.run([str(fixture), "equal"], capture_output=True)
    assert equal.returncode == 0 and _rows(equal)[0]["checkInference"] == "NOT_EXPLAINED_BY_EARLY_CHECK"
    high = subprocess.run([str(fixture), "high"], capture_output=True)
    assert high.returncode == 0 and _rows(high)[0]["checkInference"] == "NOT_EXPLAINED_BY_EARLY_CHECK"
    result = subprocess.run([str(fixture), "result"], capture_output=True)
    assert result.returncode == 0 and _rows(result)[0]["checkInference"] == "NO_BAD_VERSION"
    boolean = subprocess.run([str(fixture), "boolean"], capture_output=True)
    assert boolean.returncode == 0 and _rows(boolean)[0]["threadSafe"] is False


def test_threading_and_four_record_limit(tmp_path: Path) -> None:
    fixture = _fixture(tmp_path)
    concurrent = subprocess.run([str(fixture), "concurrent"], capture_output=True)
    assert concurrent.returncode == 0
    assert len(_rows(concurrent)) == 4 and len(concurrent.stderr) <= 4096
    limited = subprocess.run([str(fixture), "limit"], capture_output=True)
    assert limited.returncode == 0
    assert len(_rows(limited)) == 4 and len(limited.stderr) <= 4096


@pytest.mark.parametrize("mode", ["inaccessible", "overflow", "null", "change", "scope", "deny", "unsupported", "partial"])
def test_invalid_or_unstable_reads_never_change_original(tmp_path: Path, mode: str) -> None:
    result = subprocess.run([str(_fixture(tmp_path)), mode], capture_output=True)
    assert result.returncode == 0, result.stderr.decode(errors="replace")
    row = _rows(result)[0]
    assert row["forwarded"] and row["returnedErrno"] == 91 and row["checkInference"] == "UNKNOWN"
    assert not row["measurementValid"]
    if mode in {"null", "overflow"}:
        assert row["maximumBefore"] is None and row["maximumAfter"] is None
        assert row["beforeReadBytes"] is None and row["afterReadBytes"] is None


def test_sha_vector_and_build_recipe_contract(tmp_path: Path) -> None:
    fixture = _fixture(tmp_path)
    digest = subprocess.run([str(fixture), "digest"], capture_output=True)
    assert digest.returncode == 0
    syntax = subprocess.run(["bash", "-n", str(BUILD)], capture_output=True, text=True)
    assert syntax.returncode == 0, syntax.stderr
    recipe = BUILD.read_text(encoding="utf-8")
    assert "24.0.8215888" in recipe and "aarch64-linux-android29-clang" in recipe
    assert PROVIDER_SHA256 in recipe and "17656040" in recipe
    assert "GuestEglDriVersion" in recipe and "libdri_version_observer.so" in recipe
    assert "--fuzz=0" in recipe and "--build-id=none" in recipe


def _elf_layout(provider: Path) -> tuple[int, int]:
    data = provider.read_bytes()
    phoff = int.from_bytes(data[32:40], "little")
    phentsize = int.from_bytes(data[54:56], "little")
    phnum = int.from_bytes(data[56:58], "little")
    loads = []
    dynamic = None
    for index in range(phnum):
        at = phoff + index * phentsize
        values = __import__("struct").unpack_from("<IIQQQQQQ", data, at)
        if values[0] == 1:
            loads.append((values[2], values[3], values[5], values[6]))
        elif values[0] == 2:
            dynamic = (values[3], values[5])

    def va_to_file(va: int) -> int:
        for offset, vaddr, filesz, _ in loads:
            if vaddr <= va < vaddr + filesz:
                return offset + va - vaddr
        raise AssertionError(f"VA {va:#x} not mapped")

    assert dynamic is not None
    dynamic_offset = va_to_file(dynamic[0])
    tags = {}
    for at in range(dynamic_offset, dynamic_offset + dynamic[1], 16):
        tag, value = __import__("struct").unpack_from("<qQ", data, at)
        if tag == 0:
            break
        tags[tag] = value
    symtab = va_to_file(tags[6])
    strtab = va_to_file(tags[5])
    syment = tags[11]
    instruction_offset = va_to_file(0xaa8870)
    expected = bytes.fromhex(
        "9f1100716009467ae8f7ff549f0d0071ee179f1a7f0d0071"
        "eb938e1a9f0d00714e00805223f7ff540bf707370b088052"
        "050000146b010c2a7f09007142f6ff540b0980520b686bb8"
        "6e008052ebf5ff34bf010b6ba8f5ff54"
    )
    assert data[instruction_offset:instruction_offset + len(expected)] == expected
    for index in range(2000):
        at = symtab + index * syment
        st_name, _, _, _, value, size = __import__("struct").unpack_from("<IBBHQQ", data, at)
        name_at = strtab + st_name
        end = data.find(b"\0", name_at)
        if data[name_at:end] == b"driCreateContextAttribs":
            return at, instruction_offset
    raise AssertionError("provider symbol not found")


def test_host_actual_provider_layout_rejects_wrong_bytes_hash_and_symbol(tmp_path: Path) -> None:
    fixture = _fixture(tmp_path)
    actual = subprocess.run([str(fixture), "layout", str(PROVIDER)], capture_output=True)
    assert actual.returncode == 0
    symbol_offset, instruction_offset = _elf_layout(PROVIDER)

    wrong_byte = tmp_path / "wrong-byte.so"
    shutil.copyfile(PROVIDER, wrong_byte)
    with wrong_byte.open("r+b") as stream:
        stream.seek(instruction_offset)
        stream.write(bytes([stream.read(1)[0] ^ 1]))
    assert subprocess.run([str(fixture), "layout", str(wrong_byte)], capture_output=True).returncode != 0
    wrong_byte.unlink()

    wrong_hash = tmp_path / "wrong-hash.so"
    shutil.copyfile(PROVIDER, wrong_hash)
    with wrong_hash.open("r+b") as stream:
        stream.seek(0)
        stream.write(b"X")
    assert subprocess.run([str(fixture), "layout", str(wrong_hash)], capture_output=True).returncode != 0
    wrong_hash.unlink()

    wrong_symbol = tmp_path / "wrong-symbol.so"
    shutil.copyfile(PROVIDER, wrong_symbol)
    with wrong_symbol.open("r+b") as stream:
        stream.seek(symbol_offset + 8)
        stream.write((0xaa856d).to_bytes(8, "little"))
    assert subprocess.run([str(fixture), "layout", str(wrong_symbol)], capture_output=True).returncode != 0


def test_real_manifest_is_bounded_when_present() -> None:
    manifest = ARTIFACT / "manifest.json"
    if not manifest.is_file():
        pytest.skip("local NDK artifact has not been built")
    payload = json.loads(manifest.read_text(encoding="ascii"))
    assert payload["schema"] == "wowforever.android-dri-version-observer"
    assert payload["provider"]["sha256"] == PROVIDER_SHA256
    for name in ("child", "observer"):
        record = payload["artifacts"][name]
        data = (ARTIFACT / record["file"]).read_bytes()
        assert record["size"] == len(data) <= 64 * 1024
        assert record["sha256"] == hashlib.sha256(data).hexdigest()
    assert payload["artifacts"]["child"]["interpreter"] == "/system/bin/linker64"
    assert payload["artifacts"]["observer"]["interpreter"] is None
