"""Host-only contract and parser tests for the inert kbase metadata probe."""

from __future__ import annotations

import json
import shutil
import struct
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
DIRECTORY = ROOT / "scripts/diagnostics/android-panvk"
SOURCE = DIRECTORY / "KbaseMetadata.c"
BUILD = DIRECTORY / "build_kernel_probe.sh"
README = DIRECTORY / "README-kernel.md"
RUN_ID = "PVK-0123456789abcdef0123456789abcdef"
KBASE_METADATA_CWD_A = "/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-kernel/" + RUN_ID


def _harness(tmp_path: Path) -> Path:
    compiler = shutil.which("cc") or shutil.which("clang")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    harness = tmp_path / "kbase_metadata_harness.c"
    harness.write_text(
        f"""
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#define KBASE_METADATA_TESTING 1
#include "{SOURCE}"

static int calls;
static int mode;
static int mock_open(const char *path, int flags) {{
    if (strcmp(path, "/dev/mali0") || flags != (O_RDWR | O_CLOEXEC | O_NOFOLLOW)) return -1;
    if (mode == -7) {{ errno = EACCES; return -1; }}
    return 77;
}}
static int mock_lstat(const char *path, struct stat *value) {{
    if (strcmp(path, "/dev/mali0")) return -1;
    if (mode == -8) {{ errno = EACCES; return -1; }}
    memset(value, 0, sizeof(*value));
    value->st_mode = S_IFCHR;
    value->st_rdev = makedev(10, 113);
    if (mode == -9) value->st_rdev = makedev(10, 114);
    value->st_dev = 9; value->st_ino = 10;
    return 0;
}}
static int mock_fstat(int fd, struct stat *value) {{
    if (fd != 77) return -1;
    memset(value, 0, sizeof(*value));
    value->st_mode = S_IFCHR;
    value->st_rdev = makedev(10, 113);
    value->st_dev = 9; value->st_ino = 10;
    return 0;
}}
static int mock_close(int fd) {{ return fd == 77 ? 0 : -1; }}
static uid_t mock_uid(void) {{ return 10330; }}
static char *mock_getcwd(char *path, size_t capacity) {{
    if (capacity <= strlen("{KBASE_METADATA_CWD_A}")) return NULL;
    strcpy(path, "{KBASE_METADATA_CWD_A}");
    return path;
}}
static char *mock_bad_getcwd(char *path, size_t capacity) {{
    const char *bad = "/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-kernel/RUN";
    if (capacity <= strlen(bad)) return NULL;
    strcpy(path, bad);
    return path;
}}
static int mock_ioctl(int fd, unsigned long request, void *argument) {{
    struct kbase_ioctl_version_check *version = argument;
    struct kbase_ioctl_get_gpuprops *props = argument;
    unsigned char *bytes;
    if (fd != 77) return -1;
    ++calls;
    if (calls == 1 && request == KBASE_IOCTL_VERSION_CHECK_CSF) {{
        if (version->major != 0 || version->minor != 0) {{ errno = EINVAL; return -1; }}
        if (mode == -99) {{ errno = EPERM; return -1; }}
        version->major = 1; version->minor = 24; return 0;
    }}
    if (calls == 2 && request == KBASE_IOCTL_GET_GPUPROPS && props->buffer == 0 && props->size == 0 && props->flags == 0) {{
        if (mode == -13) {{ errno = ENOTTY; return -1; }}
        if (mode == -14 || mode == -15) return 16;
        return mode;
    }}
    if (calls == 3 && request == KBASE_IOCTL_GET_GPUPROPS &&
        (props->size == (unsigned)mode || ((mode == -14 || mode == -15) && props->size == 16U))) {{
        bytes = (unsigned char *)(uintptr_t)props->buffer;
        if (mode == 8) memcpy(bytes, "\\xDE\\x00\\x00\\x00\\x78\\x56\\x34\\x12", 8);
        else memcpy(bytes, "\\xDE\\x00\\x00\\x00\\x78\\x56\\x34\\x12\\xD2\\x00\\x00\\x00\\x04\\x03\\x02\\x01", 16);
        if (mode == -14 || mode == -15) bytes[props->size] = 0;
        if (mode == -14) {{ errno = EIO; return -1; }}
        if (mode == -15) return (int)props->size + 1;
        return 16;
    }}
    errno = ENOTTY; return -1;
}}

static int parse_case(const char *name) {{
    unsigned char good[] = {{ 55U << 2 | 2U, 0, 0, 0, 0x78, 0x56, 0x34, 0x12 }};
    unsigned char duplicate[] = {{ 55U << 2 | 2U, 0, 0, 0, 1, 0, 0, 0, 55U << 2 | 2U, 0, 0, 0, 2, 0, 0, 0 }};
    unsigned char trunc[] = {{ 55U << 2 | 2U, 0, 0, 0, 1, 2 }};
    unsigned char wrong_width[] = {{ 55U << 2 | 1U, 0, 0, 0, 1, 0 }};
    unsigned char unknown_width[] = {{ 0x8f, 0x01, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 0xde, 0, 0, 0, 0x78, 0x56, 0x34, 0x12 }};
    struct kbase_properties properties;
    if (!strcmp(name, "good")) return kbase_parse_properties(good, sizeof(good), &properties) && properties.present[3] && properties.value[3] == 0x12345678U ? 0 : 1;
    if (!strcmp(name, "duplicate")) return kbase_parse_properties(duplicate, sizeof(duplicate), &properties) ? 1 : 0;
    if (!strcmp(name, "trunc")) return kbase_parse_properties(trunc, sizeof(trunc), &properties) ? 1 : 0;
    if (!strcmp(name, "wrong-width")) return kbase_parse_properties(wrong_width, sizeof(wrong_width), &properties) ? 1 : 0;
    if (!strcmp(name, "unknown-width")) return kbase_parse_properties(unknown_width, sizeof(unknown_width), &properties) && properties.present[3] ? 0 : 1;
    return 2;
}}

    int main(int argc, char **argv) {{
        struct kbase_probe probe;
        char cwd[256] = "{KBASE_METADATA_CWD_A}";
        char output[KBASE_METADATA_MAX_LINE + 1U];
        if (argc != 2) return 90;
        if (!strncmp(argv[1], "parse-", 6)) return parse_case(argv[1] + 6);
        if (!strcmp(argv[1], "json")) {{
            kbase_init_probe(&probe);
            probe.status = "PROPERTIES_OBSERVED";
            probe.valid_observation = 1;
            probe.identity_before = probe.identity_after = 1;
            probe.started_ns = 10; probe.finished_ns = 20;
            probe.properties_valid = 1; probe.properties.present[3] = 1;
            probe.properties.value[3] = 0xa8670000U;
            probe.properties.present[0] = 1; probe.properties.value[0] = 0x01020304U;
            probe.close_called = 1; probe.close_ret = 0;
            probe.native_success = 1;
            if (!kbase_build_json(&probe, "PVK-0123456789abcdef0123456789abcdef", 7, 10330, 7, 7, cwd, output, sizeof(output))) return 1;
            return fputs(output, stdout) == EOF ? 1 : 0;
        }}
        if (!strcmp(argv[1], "json-blocked")) {{
            kbase_init_probe(&probe);
            probe.status = "PROPERTIES_BLOCKED";
            probe.valid_observation = 1;
            probe.identity_before = probe.identity_after = 1;
            probe.started_ns = 10; probe.finished_ns = 20;
            probe.close_called = 1; probe.close_ret = 0;
            if (!kbase_build_json(&probe, "PVK-0123456789abcdef0123456789abcdef", 7, 10330, 7, 7, cwd, output, sizeof(output))) return 1;
            return fputs(output, stdout) == EOF ? 1 : 0;
        }}
            if (!strcmp(argv[1], "ioctl-shape")) {{
        kbase_metadata_test_hooks.open_fn = mock_open;
        kbase_metadata_test_hooks.lstat_fn = mock_lstat;
        kbase_metadata_test_hooks.fstat_fn = mock_fstat;
        kbase_metadata_test_hooks.close_fn = mock_close;
        kbase_metadata_test_hooks.ioctl_fn = mock_ioctl;
        kbase_metadata_test_hooks.getuid_fn = mock_uid;
        kbase_metadata_test_hooks.getcwd_fn = mock_getcwd;
        mode = 16;
        kbase_init_probe(&probe);
            return kbase_run_probe("PVK-0123456789abcdef0123456789abcdef", &probe, cwd, sizeof(cwd)) && calls == 3 && probe.properties_valid ? 0 : 1;
        }}
        if (!strcmp(argv[1], "scope-error") || !strcmp(argv[1], "lstat-error") ||
            !strcmp(argv[1], "wrong-device") || !strcmp(argv[1], "open-error") || !strcmp(argv[1], "version-error") || !strcmp(argv[1], "properties-blocked") ||
            !strcmp(argv[1], "size-zero") || !strcmp(argv[1], "size-too-large") ||
            !strcmp(argv[1], "fill-too-large") || !strcmp(argv[1], "fill-guard-eio") ||
            !strcmp(argv[1], "fill-guard-oversized")) {{
            kbase_metadata_test_hooks.open_fn = mock_open;
            kbase_metadata_test_hooks.lstat_fn = mock_lstat;
            kbase_metadata_test_hooks.fstat_fn = mock_fstat;
            kbase_metadata_test_hooks.close_fn = mock_close;
            kbase_metadata_test_hooks.ioctl_fn = mock_ioctl;
            kbase_metadata_test_hooks.getuid_fn = mock_uid;
            kbase_metadata_test_hooks.getcwd_fn = mock_getcwd;
            if (!strcmp(argv[1], "scope-error")) kbase_metadata_test_hooks.getcwd_fn = mock_bad_getcwd;
            mode = !strcmp(argv[1], "lstat-error") ? -8 : !strcmp(argv[1], "wrong-device") ? -9 : !strcmp(argv[1], "open-error") ? -7 : !strcmp(argv[1], "version-error") ? -99 : !strcmp(argv[1], "properties-blocked") ? -13 : !strcmp(argv[1], "size-zero") ? 0 : !strcmp(argv[1], "size-too-large") ? 16385 : !strcmp(argv[1], "fill-guard-eio") ? -14 : !strcmp(argv[1], "fill-guard-oversized") ? -15 : 8;
            kbase_init_probe(&probe);
            (void)kbase_run_probe("PVK-0123456789abcdef0123456789abcdef", &probe, cwd, sizeof(cwd));
            if (!strcmp(argv[1], "scope-error")) return !strcmp(probe.status, "UNKNOWN") && !probe.version_called && probe.finished_ns > probe.started_ns ? 0 : 1;
            if (!strcmp(argv[1], "lstat-error") || !strcmp(argv[1], "open-error")) return !strcmp(probe.status, "OPEN_FAILED") && probe.finished_ns > probe.started_ns && probe.deadline_valid && !probe.close_called && probe.error_errno == -EACCES ? 0 : 1;
            if (!strcmp(argv[1], "wrong-device")) return !strcmp(probe.status, "UNKNOWN") && !strcmp(probe.error_stage, "lstat_identity") && !probe.version_called && !probe.valid_observation ? 0 : 1;
            if (!strcmp(argv[1], "version-error")) return probe.version_called && probe.version_errno == -EPERM && !probe.properties_probe_called && !strcmp(probe.status, "VERSION_FAILED") && probe.finished_ns > probe.started_ns && probe.deadline_valid ? 0 : 1;
            if (!strcmp(argv[1], "properties-blocked")) return probe.valid_observation && !strcmp(probe.status, "PROPERTIES_BLOCKED") && probe.properties_probe_called && !probe.properties_fill_called ? 0 : 1;
            if (!strcmp(argv[1], "fill-guard-eio") || !strcmp(argv[1], "fill-guard-oversized")) return !probe.valid_observation && !probe.properties_valid && !strcmp(probe.status, "UNKNOWN") && probe.properties_fill_called ? 0 : 1;
            return probe.properties_probe_called && !strcmp(probe.status, "UNKNOWN") ? 0 : 1;
        }}
    return 91;
}}
""",
        encoding="utf-8",
    )
    binary = tmp_path / "kbase_metadata_harness"
    built = subprocess.run(
        [compiler, "-D_GNU_SOURCE", "-std=c11", "-Wall", "-Wextra", "-Werror", str(harness), "-o", str(binary)],
        capture_output=True,
        text=True,
    )
    assert built.returncode == 0, built.stdout + built.stderr
    return binary


def test_source_is_context_free_and_has_no_gpu_execution_surface() -> None:
    text = SOURCE.read_text(encoding="utf-8")
    assert '"/dev/mali0"' in text
    assert "O_RDWR | O_CLOEXEC | O_NOFOLLOW" in text
    assert "KBASE_IOCTL_VERSION_CHECK_CSF" in text and "KBASE_IOCTL_GET_GPUPROPS" in text
    assert "0xC0048034UL" in text and "0x40108003UL" in text
    assert "KBASE_KEY_GPU_ID = 55U" in text
    assert "KBASE_KEY_TEXTURE_3 = 81U" in text
    assert "strnlen(run_id" in text
    assert "KBASE_METADATA_CWD_PREFIX_A" in text and "KBASE_METADATA_CWD_PREFIX_B" in text
    assert "kbase_metadata_test_hooks.open_fn != NULL) scope_ok" not in text
    assert "SET_FLAGS" not in text and "mmap(" not in text
    assert "execve(" not in text and "posix_spawn" not in text
    assert "ptrace" not in text and "dlopen" not in text and "system(" not in text
    for field in ("flagsInited", "gpuWorkloadVerified", "driverExecution", "deviceVerified", "hardwareVerified"):
        assert field in text
    assert "PROPERTIES_BLOCKED" in text and "PROPERTIES_OBSERVED" in text


def test_build_recipe_is_strict_scoped_and_pinned() -> None:
    assert subprocess.run(["bash", "-n", str(BUILD)], capture_output=True, text=True).returncode == 0
    text = BUILD.read_text(encoding="utf-8")
    assert "24.0.8215888" in text and "aarch64-linux-android29-clang" in text
    assert "-Wall -Wextra -Werror" in text and "--build-id=none" in text
    assert "64 * 1024" in text and "neededLibraries" in text
    assert "de577411e8f27da9de50aec26a0d8cc43890745e" in text
    assert "/home/adrian/wowforever-artifacts/arm64-prototype/controls" in text
    assert "adb" not in text.lower() and "curl" not in text.lower()


@pytest.mark.parametrize(
    "case,expected",
    [("parse-good", 0), ("parse-duplicate", 0), ("parse-trunc", 0),
     ("parse-wrong-width", 0), ("parse-unknown-width", 0)],
)
def test_host_packed_parser_rejects_malformed_and_duplicate_selected_keys(tmp_path: Path, case: str, expected: int) -> None:
    harness = _harness(tmp_path)
    assert subprocess.run([str(harness), case], check=False).returncode == expected


def test_host_mock_proves_exact_version_then_two_property_calls(tmp_path: Path) -> None:
    harness = _harness(tmp_path)
    assert subprocess.run([str(harness), "ioctl-shape"], check=False).returncode == 0


def test_host_actual_encoder_emits_bounded_schema_and_false_execution_flags(tmp_path: Path) -> None:
    harness = _harness(tmp_path)
    result = subprocess.run([str(harness), "json"], capture_output=True, check=True)
    assert len(result.stdout) <= 8192
    document = json.loads(result.stdout)
    assert document["schema"] == "wowforever.android-kbase-metadata/version1"
    assert document["status"] == "PROPERTIES_OBSERVED"
    assert document["properties"]["gpuIDhex"] == "0xa8670000"
    assert document["properties"]["textureflags"]["52"] == "0x01020304"
    for field in ("flagsInited", "gpuWorkloadVerified", "driverExecution", "deviceVerified", "hardwareVerified"):
        assert document[field] is False


def test_blocked_properties_is_valid_metadata_but_json_gate_exit_is_nonzero(tmp_path: Path) -> None:
    harness = _harness(tmp_path)
    result = subprocess.run([str(harness), "json-blocked"], capture_output=True, check=True)
    document = json.loads(result.stdout)
    assert document["status"] == "PROPERTIES_BLOCKED"
    assert document["nativeExit"] == 1


@pytest.mark.parametrize("case", ["scope-error", "lstat-error", "wrong-device", "open-error", "version-error", "properties-blocked", "size-zero", "size-too-large", "fill-too-large", "fill-guard-eio", "fill-guard-oversized"])
def test_host_mock_preserves_errors_and_bounds_without_retries(tmp_path: Path, case: str) -> None:
    harness = _harness(tmp_path)
    assert subprocess.run([str(harness), case], check=False).returncode == 0


def test_readme_records_authority_and_non_claim_contract() -> None:
    text = README.read_text(encoding="utf-8")
    assert "de577411e8f27da9de50aec26a0d8cc43890745e" in text
    assert "sourceAuthority" in text and "sourceHash" in text
    assert "kbase_uapi.h" in text or "mali_kbase_ioctl.h" in text
    assert "0xC0048034" in text and "0x40108003" in text
    assert "PROPERTIES_BLOCKED" in text and "fails the gate" in text and "exits nonzero" in text
    assert "not a GPU or driver pass" in text


@pytest.mark.skipif(
    not Path("/home/adrian/android-sdk/ndk/24.0.8215888/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang").is_file(),
    reason="pinned local Android NDK is unavailable",
)
def test_real_arm64_build_is_bounded_and_writes_only_external_artifact() -> None:
    artifact = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/kbase-metadata")
    completed = subprocess.run(["bash", str(BUILD)], cwd=ROOT, capture_output=True, text=True, timeout=120)
    assert completed.returncode == 0, completed.stdout + completed.stderr
    elf = artifact / "KbaseMetadata"
    report = (artifact / "KbaseMetadata.readelf.txt").read_text(encoding="ascii")
    manifest = json.loads((artifact / "manifest.json").read_text(encoding="ascii"))
    data = elf.read_bytes()
    assert len(data) <= 64 * 1024
    assert data[:4] == b"\x7fELF" and data[4:6] == b"\x02\x01"
    assert struct.unpack_from("<H", data, 16)[0] == 3
    assert struct.unpack_from("<H", data, 18)[0] == 183
    assert "Type:                              DYN" in report
    assert "Requesting program interpreter: /system/bin/linker64" in report
    assert manifest["neededLibraries"] == ["libc.so"]
