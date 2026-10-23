"""Offline wire and parser tests for the native vtest handshake probe."""

from __future__ import annotations

import shutil
import struct
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
SOURCE = ROOT / "scripts/diagnostics/android-vtest/VtestHandshake.c"
BUILD = ROOT / "scripts/diagnostics/android-vtest/build_client.sh"


def _host_harness(tmp_path: Path) -> Path:
    compiler = shutil.which("cc") or shutil.which("clang")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    harness = tmp_path / "harness.c"
    harness.write_text(
        f"""
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define VTEST_TESTING 1
#include "{SOURCE}"

int main(int argc, char **argv) {{
    if (argc == 3 && strcmp(argv[1], "run-id") == 0)
        return vtest_valid_run_id(argv[2]) ? 0 : 1;
    uint8_t input[8 + 256 * 1024];
    size_t input_length;
    input_length = fread(input, 1, sizeof(input), stdin);
    if (argc == 2 && strcmp(argv[1], "identity") == 0) {{
        struct server_identity identity;
        if (!vtest_parse_server_identity(input, input_length, &identity)) return 13;
        printf("%ld %llu\\n", (long)identity.pid,
               (unsigned long long)identity.start_ticks);
        return 0;
    }}
    if (argc == 2 && strcmp(argv[1], "procstat") == 0) {{
        uint64_t ticks;
        if (!vtest_parse_proc_stat(input, input_length, (pid_t)123, &ticks)) return 14;
        printf("%llu\\n", (unsigned long long)ticks);
        return 0;
    }}
    if (argc != 1) return 12;
    uint8_t request[22];
    struct caps_summary summary;
    if (vtest_encode_requests(request, sizeof(request)) != sizeof(request)) return 10;
    for (size_t i = 0; i < sizeof(request); ++i) printf("%02x", request[i]);
    putchar('\\n');

    uint8_t response[8 + 256 * 1024];
    size_t n = input_length;
    memcpy(response, input, input_length);
    if (!vtest_parse_response(response, n, &summary)) return 11;
    printf("%u %u %016llx\\n", summary.bytes, summary.nonzero_bytes,
           (unsigned long long)summary.fnv1a);
    return 0;
}}
""",
        encoding="utf-8",
    )
    binary = tmp_path / "harness"
    subprocess.run(
        [compiler, "-D_GNU_SOURCE", "-std=c11", "-Wall", "-Wextra", "-Werror", str(harness), "-o", str(binary)],
        check=True,
    )
    return binary


def test_wire_encoder_is_exact_and_little_endian(tmp_path: Path) -> None:
    harness = _host_harness(tmp_path)
    result = subprocess.run([str(harness)], input=b"", capture_output=True, check=False)
    assert result.returncode == 11
    assert result.stdout.splitlines()[0] == b"06000000080000007674657374000000000001000000"


def test_response_parser_accepts_nonzero_capabilities_and_reports_only_summary(tmp_path: Path) -> None:
    harness = _host_harness(tmp_path)
    caps = bytes(range(33))
    response = struct.pack("<II", len(caps) + 1, 1) + caps
    result = subprocess.run([str(harness)], input=response, capture_output=True, check=True)
    lines = result.stdout.splitlines()
    assert lines[0].startswith(b"0600000008000000767465737400")
    expected_hash = 14695981039346656037
    for byte in caps:
        expected_hash = ((expected_hash ^ byte) * 1099511628211) & ((1 << 64) - 1)
    assert lines[1] == f"33 32 {expected_hash:016x}".encode()
    assert caps.hex().encode() not in lines[1]


def test_server_identity_parser_is_exact_and_bounded(tmp_path: Path) -> None:
    harness = _host_harness(tmp_path)
    valid = subprocess.run([str(harness), "identity"], input=b"123 456\n", capture_output=True, check=True)
    assert valid.stdout == b"123 456\n"
    for malformed in (
        b"123 456",
        b"123 456\n\n",
        b"0 456\n",
        b"123 0\n",
        b"123 x\n",
        b"123 456\ntrailing",
        b"1" * 129,
    ):
        result = subprocess.run([str(harness), "identity"], input=malformed, capture_output=True, check=False)
        assert result.returncode == 13


def test_proc_stat_parser_checks_pid_comm_and_start_ticks_field(tmp_path: Path) -> None:
    harness = _host_harness(tmp_path)
    # Field 0 after comm is state; field 19 is Linux stat field 22 (starttime).
    stat = b"123 (server) S " + b" ".join(str(value).encode() for value in range(1, 20)) + b"\n"
    valid = subprocess.run([str(harness), "procstat"], input=stat, capture_output=True, check=True)
    assert valid.stdout == b"19\n"
    for malformed in (
        stat.replace(b"123 ", b"124 ", 1),
        stat.replace(b"(server)", b"(server", 1),
        stat[:-1] + b"x\n",
        b"123 (server) S 1 2 3\n",
    ):
        result = subprocess.run([str(harness), "procstat"], input=malformed, capture_output=True, check=False)
        assert result.returncode == 14


@pytest.mark.parametrize(
    "header",
    [
        struct.pack("<II", 0, 1),
        struct.pack("<II", 1, 1),
        struct.pack("<II", 2, 2),
        struct.pack("<II", 257 * 1024 + 1, 1),
        struct.pack("<II", 0xFFFFFFFF, 1),
    ],
)
def test_response_parser_rejects_header_boundaries(tmp_path: Path, header: bytes) -> None:
    harness = _host_harness(tmp_path)
    result = subprocess.run([str(harness)], input=header, capture_output=True, check=False)
    assert result.returncode == 11


def test_response_parser_rejects_trailing_capability_bytes_and_accepts_maximum(tmp_path: Path) -> None:
    harness = _host_harness(tmp_path)
    caps = b"x" * (256 * 1024)
    valid = subprocess.run(
        [str(harness)], input=struct.pack("<II", len(caps) + 1, 1) + caps,
        capture_output=True, check=True,
    )
    assert valid.stdout.splitlines()[1].startswith(b"262144 262144 ")
    trailing = subprocess.run(
        [str(harness)], input=struct.pack("<II", 2, 1) + b"x" + b"extra",
        capture_output=True, check=False,
    )
    assert trailing.returncode == 11


def test_invalid_run_ids_are_rejected_before_native_work(tmp_path: Path) -> None:
    # The host process is the inert test harness, never the Android client.
    harness = _host_harness(tmp_path)
    for value in ("", "-bad", "a/b", "a" * 101, "a b", "é"):
        result = subprocess.run([str(harness), "run-id", value], capture_output=True)
        assert result.returncode == 1
        assert result.stdout == b""


def test_build_script_declares_external_only_artifact_and_elf_checks() -> None:
    text = BUILD.read_text(encoding="utf-8")
    assert "/home/adrian/wowforever-artifacts/arm64-prototype/controls" in text
    assert "ls -ld -- \"$ARTIFACT_PARENT\"" in text
    assert "--build-id=none" in text
    assert "AArch64" in text and "ET_DYN" in text
    assert "/system/bin/linker64" in text and "libc.so" in text
    assert "sha256sum \"$SOURCE\"" in text


def test_native_contract_has_identity_revalidation_and_eof_only_close() -> None:
    text = SOURCE.read_text(encoding="utf-8")
    assert 'SERVER_IDENTITY_NAME "server.identity"' in text
    assert "O_NOFOLLOW" in text and "SO_PEERCRED" in text
    assert "shutdown(fd, SHUT_WR)" in text
    assert "MSG_PEEK" not in text
    assert "serverIdentityVerified" in text
    assert '"response_eofverified"' in text
