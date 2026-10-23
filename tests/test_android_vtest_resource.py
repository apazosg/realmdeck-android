"""Offline wire and policy tests for the bounded vtest resource probe."""

from __future__ import annotations

import json
import shutil
import struct
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
SOURCE = ROOT / "scripts/diagnostics/android-vtest/VtestResource.c"
BUILD = ROOT / "scripts/diagnostics/android-vtest/build_resource_client.sh"
OLD_SOURCE = ROOT / "scripts/diagnostics/android-vtest/VtestHandshake.c"


def _harness(tmp_path: Path) -> Path:
    compiler = shutil.which("cc") or shutil.which("clang")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    source = tmp_path / "harness.c"
    source.write_text(
        f"""
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#define VTEST_TESTING 1
#include \"{SOURCE}\"

int main(int argc, char **argv) {{
    uint8_t data[8 + 256 * 1024];
    size_t n;
    if (argc != 2) return 90;
    if (strcmp(argv[1], \"wire\") == 0) {{
        n = vtest_resource_encode_create_request(data, sizeof(data));
        fwrite(data, 1, n, stdout);
        n = vtest_resource_encode_put_request(data, sizeof(data));
        fwrite(data, 1, n, stdout);
        n = vtest_resource_encode_get_request(data, sizeof(data));
        fwrite(data, 1, n, stdout);
        n = vtest_resource_encode_unref_request(data, sizeof(data));
        fwrite(data, 1, n, stdout);
        return 0;
    }}
    if (strcmp(argv[1], \"readback\") == 0) {{
        n = fread(data, 1, sizeof(data), stdin);
        return vtest_resource_readback_is_exact(data, n) ? 0 : 1;
    }}
    if (strcmp(argv[1], \"peer-good\") == 0) {{
        return vtest_resource_peer_matches(202, 10330, 202, 123, 123) ? 0 : 1;
    }}
    if (strcmp(argv[1], \"peer-wrong-pid\") == 0) {{
        return vtest_resource_peer_matches(203, 10330, 202, 123, 123) ? 1 : 0;
    }}
    if (strcmp(argv[1], \"peer-wrong-uid\") == 0) {{
        return vtest_resource_peer_matches(202, 10331, 202, 123, 123) ? 1 : 0;
    }}
    if (strcmp(argv[1], \"peer-wrong-ticks\") == 0) {{
        return vtest_resource_peer_matches(202, 10330, 202, 123, 124) ? 1 : 0;
    }}
    if (strcmp(argv[1], \"caps\") == 0) {{
        n = fread(data, 1, sizeof(data), stdin);
        struct caps_summary summary;
        return vtest_parse_response(data, n, &summary) ? 0 : 1;
    }}
    if (strcmp(argv[1], \"identity\") == 0) {{
        n = fread(data, 1, sizeof(data), stdin);
        struct server_identity identity;
        return vtest_parse_server_identity(data, n, &identity) ? 0 : 1;
    }}
    if (strcmp(argv[1], \"cwd\") == 0) {{
        return allowed_cwd(\"VTEST-resource\") ? 1 : 0;
    }}
    if (strcmp(argv[1], \"eof-empty\") == 0 || strcmp(argv[1], \"eof-extra\") == 0) {{
        int sockets[2];
        if (socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) != 0) return 92;
        if (strcmp(argv[1], \"eof-extra\") == 0) send(sockets[1], \"x\", 1, 0);
        close(sockets[1]);
        n = receive_expected_eof(sockets[0], monotonic_ms() + 5000U) ? 0U : 1U;
        close(sockets[0]);
        return strcmp(argv[1], \"eof-empty\") == 0 ? (int)n : (int)(n == 1U ? 0U : 1U);
    }}
    if (strcmp(argv[1], \"event\") == 0) return vtest_resource_test_event();
    return 91;
}}
""",
        encoding="utf-8",
    )
    binary = tmp_path / "harness"
    subprocess.run(
        [compiler, "-D_GNU_SOURCE", "-std=c11", "-Wall", "-Wextra", "-Werror", str(source), "-o", str(binary)],
        check=True,
    )
    return binary


def test_exact_protocol0_resource_wire_and_fixed_payload(tmp_path: Path) -> None:
    harness = _harness(tmp_path)
    result = subprocess.run([str(harness), "wire"], capture_output=True, check=True)
    expected = (
        struct.pack("<II", 10, 2) + struct.pack("<10I", 1, 0, 64, 0, 4, 1, 1, 1, 0, 0)
        + struct.pack("<II", 12, 5) + struct.pack("<11I", 1, 0, 4, 4, 0, 0, 0, 4, 1, 1, 4) + bytes.fromhex("a1b2c3d4")
        + struct.pack("<II", 11, 4) + struct.pack("<11I", 1, 0, 4, 4, 0, 0, 0, 4, 1, 1, 4)
        + struct.pack("<II", 1, 3) + struct.pack("<I", 1)
    )
    assert result.stdout == expected


@pytest.mark.parametrize("payload", [b"", b"a1b2", b"a1b2c3d4x", bytes.fromhex("a1b2c3d5"), bytes.fromhex("a1b2c3d4")])
def test_readback_requires_exact_four_bytes_and_exact_contents(tmp_path: Path, payload: bytes) -> None:
    harness = _harness(tmp_path)
    result = subprocess.run([str(harness), "readback"], input=payload, check=False)
    assert result.returncode == (0 if payload == bytes.fromhex("a1b2c3d4") else 1)


def test_peer_gate_requires_exact_uid_pid_and_live_start_ticks(tmp_path: Path) -> None:
    harness = _harness(tmp_path)
    assert subprocess.run([str(harness), "peer-good"], check=False).returncode == 0
    for mode in ("peer-wrong-pid", "peer-wrong-uid", "peer-wrong-ticks"):
        assert subprocess.run([str(harness), mode], check=False).returncode == 0
    text = SOURCE.read_text(encoding="utf-8")
    assert "peer_uid == VTEST_UID" in text
    assert "read_proc_start_ticks" in text
    assert "credentials.pid" in text and "expected->pid" in text
    assert text.count("!resource_verify_peer(fd, state)") == 4


def test_malformed_caps_truncation_and_identity_inputs_are_rejected(tmp_path: Path) -> None:
    harness = _harness(tmp_path)
    valid_caps = struct.pack("<II", 2, 1) + b"x"
    assert subprocess.run([str(harness), "caps"], input=valid_caps, check=False).returncode == 0
    for malformed in (
        b"",
        struct.pack("<II", 0, 1),
        struct.pack("<II", 1, 1),
        struct.pack("<II", 2, 2) + b"x",
        valid_caps + b"extra",
        struct.pack("<II", 2, 1),
    ):
        assert subprocess.run([str(harness), "caps"], input=malformed, check=False).returncode == 1
    assert subprocess.run([str(harness), "identity"], input=b"123 456\n", check=False).returncode == 0
    for malformed in (b"123 456", b"123 0\n", b"123 x\n", b"123 456\nextra"):
        assert subprocess.run([str(harness), "identity"], input=malformed, check=False).returncode == 1
    assert subprocess.run([str(harness), "cwd"], check=False).returncode == 0
    assert subprocess.run([str(harness), "eof-empty"], check=False).returncode == 0
    assert subprocess.run([str(harness), "eof-extra"], check=False).returncode == 0


def test_identity_cwd_uid_and_wire_safety_policies_are_bounded() -> None:
    text = SOURCE.read_text(encoding="utf-8")
    old = OLD_SOURCE.read_text(encoding="utf-8")
    assert '#include "VtestHandshake.c"' in text
    assert "VTEST_TESTING" in text
    assert 'if (getuid() != VTEST_UID)' in text
    assert "allowed_cwd(state->run_id)" in text
    assert "load_server_identity" in old and "O_NOFOLLOW" in old and "0600" in old
    assert "SO_PEERCRED" in text and "MSG_NOSIGNAL" in old
    assert "signal(" not in text
    assert "stdin" not in text


def test_caps_response_bounds_and_eof_are_inherited_without_wire_extras() -> None:
    text = SOURCE.read_text(encoding="utf-8")
    old = OLD_SOURCE.read_text(encoding="utf-8")
    assert "receive_caps" in text and "MAX_CAP_BYTES" in old
    assert "receive_expected_eof" in text
    assert "shutdown(fd, SHUT_WR)" in text
    assert "VCMD_PROTOCOL_VERSION" not in text
    assert "SHM" not in text and "SCM_RIGHTS" not in text


def test_success_event_has_settled_schema_and_false_broader_flags(tmp_path: Path) -> None:
    harness = _harness(tmp_path)
    result = subprocess.run([str(harness), "event"], capture_output=True, check=True)
    lines = result.stdout.splitlines()
    assert len(lines) == 1
    event = json.loads(lines[0])
    assert event["status"] == "RESOURCE_ROUNDTRIP"
    assert event["resourceId"] == 1 and event["resourceBytes"] == 4
    assert event["writtenHex"] == "a1b2c3d4"
    assert event["readbackHex"] == "a1b2c3d4"
    assert event["resourceTransferVerified"] is True
    assert event["resourceUnrefSent"] is True
    assert set(event) == {
        "runId", "status", "nativePid", "uid", "peerPid", "peerUid",
        "expectedServerPid", "expectedServerStartTicks", "serverIdentityVerified",
        "stages", "capBytes", "capNonzeroBytes", "capsFNVhash", "durationMs",
        "resourceId", "resourceBytes", "writtenHex", "readbackHex",
        "resourceTransferVerified", "resourceUnrefSent", "egl", "gpu", "triangle",
        "device", "hardware", "present", "wowPass", "render",
    }
    assert event["stages"] == [
        "uid_verified", "cwd_verified", "server_identity_loaded", "socket_verified",
        "connected", "peer_verified", "server_identity_verified", "request_sent",
        "capabilities_received", "resource_create_sent", "transfer_put_sent",
        "transfer_get_sent", "readback_verified", "resource_unref_sent",
        "server_identity_revalidated", "response_eofverified", "cleanup",
    ]
    for key in ("egl", "gpu", "triangle", "device", "hardware", "present", "wowPass", "render"):
        assert event[key] is False


def test_stdout_failure_is_nonzero_and_cannot_become_a_protocol_pass(tmp_path: Path) -> None:
    harness = _harness(tmp_path)
    with open("/dev/full", "wb") as sink:
        result = subprocess.run([str(harness), "event"], stdout=sink, check=False)
    assert result.returncode != 0


def test_build_is_external_pinned_android_elf_and_does_not_execute_artifact() -> None:
    text = BUILD.read_text(encoding="utf-8")
    assert "ARTIFACT_PARENT=\"/home/adrian/wowforever-artifacts/arm64-prototype/controls\"" in text
    assert 'ARTIFACT_DIR="$ARTIFACT_PARENT/vtest-resource"' in text
    assert "ls -ld -- \"$ARTIFACT_PARENT\"" in text
    assert "aarch64-linux-android29-clang" in text
    assert "--build-id=none" in text and "-Werror" in text
    assert "/system/bin/linker64" in text and "libc.so" in text
    assert 'sha256sum "$SOURCE"' in text
    assert 'sha256sum "$INCLUDED_SOURCE"' in text
    assert 'sha256sum "$0"' in text
    assert 'test "$needed_count" -eq 1' in text
    assert "VtestResource" in text
