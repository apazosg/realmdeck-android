"""Offline protocol, readback, and build-contract tests for VtestTriangle."""

from __future__ import annotations

import json
import shutil
import struct
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
SOURCE = ROOT / "scripts/diagnostics/android-vtest/VtestTriangle.c"
BUILD = ROOT / "scripts/diagnostics/android-vtest/build_triangle_client.sh"
HANDSHAKE = ROOT / "scripts/diagnostics/android-vtest/VtestHandshake.c"


def _harness(tmp_path: Path) -> Path:
    compiler = shutil.which("cc") or shutil.which("clang")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    source = tmp_path / "harness.c"
    source.write_text(
        f'''
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#define VTEST_TESTING 1
#include "{SOURCE}"

int main(int argc, char **argv) {{
    uint8_t data[20000];
    size_t n;
    if (argc != 2) return 90;
    if (strcmp(argv[1], "wire") == 0) {{
        n = vtest_triangle_encode_target_create(data, sizeof(data));
        fwrite(data, 1, n, stdout);
        n = vtest_triangle_encode_vbo_create(data, sizeof(data));
        fwrite(data, 1, n, stdout);
        n = vtest_triangle_encode_busy_wait(data, sizeof(data));
        fwrite(data, 1, n, stdout);
        return 0;
    }}
    if (strcmp(argv[1], "setup") == 0) {{
        n = vtest_triangle_encode_setup_cmdbuf(data, sizeof(data));
        fwrite(data, 1, n, stdout);
        return n == 0 ? 1 : 0;
    }}
    if (strcmp(argv[1], "draw") == 0) {{
        n = vtest_triangle_encode_draw_cmdbuf(data, sizeof(data));
        fwrite(data, 1, n, stdout);
        return n == 0 ? 1 : 0;
    }}
    if (strcmp(argv[1], "bounds") == 0) {{
        return vtest_triangle_encode_setup_cmdbuf(data, 0) == 0 &&
               vtest_triangle_encode_setup_cmdbuf(data, 16) == 0 &&
               vtest_triangle_encode_submit_packet(data, 8, data, 4) == 0 ? 0 : 1;
    }}
    if (strcmp(argv[1], "clear") == 0 || strcmp(argv[1], "accept") == 0) {{
        struct triangle_readback result;
        int accepted;
        n = fread(data, 1, sizeof(data), stdin);
        if (strcmp(argv[1], "clear") == 0)
            return vtest_triangle_validate_clear_readback(data, n) ? 0 : 1;
        accepted = vtest_triangle_accept_readback(data, n, &result);
        printf("%d %u %u %u %u %d %016llx\\n",
               accepted, result.clear_verified,
               result.triangle_pixels, result.green_pixels, result.readback_bytes,
               result.nonuniform_rgb, (unsigned long long)result.fnv1a);
        return 0;
    }}
    if (strcmp(argv[1], "event") == 0)
        return vtest_triangle_test_event();
    if (strcmp(argv[1], "event-clock-error") == 0)
        return vtest_triangle_test_event_clock_error();
    return 91;
}}
''',
        encoding="utf-8",
    )
    binary = tmp_path / "harness"
    subprocess.run(
        [compiler, "-D_GNU_SOURCE", "-std=c11", "-Wall", "-Wextra", "-Werror", str(source), "-o", str(binary)],
        check=True,
    )
    return binary


def _readback(*, changed: bool = True, x_only: bool = False) -> bytes:
    pixels = bytearray(bytes((0, 255, 0, 0)) * (64 * 64))
    if changed:
        for y in range(20, 44):
            for x in range(20, 44):
                offset = (y * 64 + x) * 4
                pixels[offset:offset + 4] = bytes((x & 0xFF, 64 + (y & 0x3F), 128, 17))
    if x_only:
        for y in range(20, 44):
            for x in range(20, 44):
                pixels[(y * 64 + x) * 4 + 3] = 99
    return bytes(pixels)


def test_protocol0_resource_and_busy_wait_goldens_are_bounded(tmp_path: Path) -> None:
    harness = _harness(tmp_path)
    result = subprocess.run([str(harness), "wire"], capture_output=True, check=True)
    expected = (
        struct.pack("<12I", 10, 2, 1, 2, 2, 2, 64, 64, 1, 1, 0, 0)
        + struct.pack("<12I", 10, 2, 2, 0, 64, 16, 96, 1, 1, 1, 0, 0)
        + struct.pack("<4I", 2, 7, 0, 1)
    )
    assert result.stdout == expected
    assert len(result.stdout) == 112
    assert subprocess.run([str(harness), "bounds"], check=False).returncode == 0


def test_setup_has_authoritative_objects_literals_and_shader_wire(tmp_path: Path) -> None:
    harness = _harness(tmp_path)
    setup = subprocess.run([str(harness), "setup"], capture_output=True, check=True).stdout
    assert len(setup) < 4096 and len(setup) % 4 == 0
    words = struct.unpack("<%dI" % (len(setup) // 4), setup)
    assert words[0] == 0x00050801  # CREATE_OBJECT/SURFACE, 5 payload dwords
    assert struct.pack("<I", 0x00080007) in setup  # CLEAR, 8 payload dwords
    expected_shaders = {
        b"VERT\nDCL IN[0]\nDCL IN[1]\nDCL OUT[0], POSITION\nDCL OUT[1], COLOR\n"
        b"  0: MOV OUT[1], IN[1]\n  1: MOV OUT[0], IN[0]\n  2: END\n\x00",
        b"FRAG\nDCL IN[0], COLOR, LINEAR\nDCL OUT[0], COLOR\n"
        b"  0: MOV OUT[0], IN[0]\n  1: END\n\x00",
    }
    observed_shaders = set()
    pos = 0
    shader_count = 0
    while pos < len(words):
        header = words[pos]
        length = header >> 16
        assert length > 0
        next_pos = pos + 1 + length
        assert next_pos <= len(words)
        if (header & 0xFF) == 1 and ((header >> 8) & 0xFF) == 4:
            body = words[pos + 1:next_pos]
            assert len(body) >= 5
            text_length = body[2]
            text_words = (text_length + 3) // 4
            assert len(body) == 5 + text_words
            assert body[3] == 300
            assert body[4] == 0  # virgl_emit_shader_streamout(NULL)
            text = struct.pack("<%dI" % text_words, *body[5:])
            assert text[text_length - 1:text_length] == b"\x00"
            assert text[text_length:] == b"\x00" * (text_words * 4 - text_length)
            observed_shaders.add(text[:text_length])
            shader_count += 1
        pos = next_pos
    assert pos == len(words)
    assert shader_count == 2 and observed_shaders == expected_shaders
    # The parser reached the post-shader bind/link/state commands, proving the
    # shader body length did not consume the next command boundary.
    assert any((word & 0xFF) == 31 for word in words)
    draw = subprocess.run([str(harness), "draw"], capture_output=True, check=True).stdout
    assert struct.unpack_from("<I", draw, 0)[0] == 0x000c0008
    assert struct.unpack_from("<12I", draw, 4)[1:3] == (3, 4)


@pytest.mark.parametrize(
    ("payload", "accepted"),
    [
        (_readback(), True),
        (_readback(changed=False), False),
        (bytes(64 * 64 * 4), False),
        (_readback()[:-1], False),
        (_readback(changed=False, x_only=True), False),
    ],
)
def test_readback_gate_requires_clear_history_triangle_pixels_and_rgb(tmp_path: Path, payload: bytes, accepted: bool) -> None:
    harness = _harness(tmp_path)
    result = subprocess.run([str(harness), "accept"], input=payload, capture_output=True, check=True)
    fields = result.stdout.decode().split()
    assert fields[0] == ("1" if accepted else "0")
    if accepted:
        assert fields[1:5] == ["0", "576", "3520", "16384"]
        assert fields[5] == "1"
        assert int(fields[6], 16) != 0


def test_x_padding_is_ignored_for_the_green_baseline(tmp_path: Path) -> None:
    harness = _harness(tmp_path)
    baseline = bytearray(_readback(changed=False))
    for offset in range(3, len(baseline), 4):
        baseline[offset] = 0xA5
    assert subprocess.run([str(harness), "clear"], input=baseline, check=False).returncode == 0


def test_uniform_rgb_change_is_not_a_triangle(tmp_path: Path) -> None:
    harness = _harness(tmp_path)
    solid = bytearray(_readback(changed=False))
    for y in range(20, 44):
        for x in range(20, 44):
            solid[(y * 64 + x) * 4:(y * 64 + x) * 4 + 4] = bytes((64, 64, 64, 0))
    result = subprocess.run([str(harness), "accept"], input=solid, capture_output=True, check=True)
    assert result.stdout.decode().split()[0] == "0"


def test_event_schema_status_and_flags_are_literal_and_bounded(tmp_path: Path) -> None:
    harness = _harness(tmp_path)
    result = subprocess.run([str(harness), "event"], capture_output=True, check=True)
    assert len(result.stdout) <= 4096
    event = json.loads(result.stdout)
    assert event["status"] == "TRIANGLE_OFFSCREEN"
    assert event["triangleVerified"] is True
    assert event["offscreenDrawVerified"] is True
    assert event["clearVerified"] is True
    assert event["clearPixels"] == 4096
    assert event["trianglePixels"] == 1024
    assert event["greenPixels"] == 3072
    assert event["nonuniformRGB"] is True
    assert event["readbackBytes"] == 16384
    assert event["width"] == 64 and event["height"] == 64
    assert event["presentationVerified"] is False
    assert event["d3dVerified"] is False
    assert event["wowVerified"] is False
    assert event["hardwareRenderingVerified"] is False
    assert event["unrefSent"] is True
    assert event["protocolCaps"] == {
        "capBytes": 308,
        "capNonzeroBytes": 300,
        "capsFNVhash": "0123456789abcdef",
    }
    assert "readbackHex" not in result.stdout.decode()
    assert event["stages"] == [
        "uid_verified", "cwd_verified", "server_identity_loaded", "socket_verified",
        "connected", "peer_verified", "server_identity_verified", "request_sent",
        "capabilities_received", "render_target_create_sent", "vbo_create_sent",
        "setup_submitted", "clear_verified", "draw_submitted", "busy_wait_verified",
        "triangle_readback_verified", "objects_cleanup_sent", "resource_unref_sent",
        "server_identity_revalidated", "response_eofverified", "cleanup",
    ]


def test_event_clock_error_cannot_emit_a_success_status(tmp_path: Path) -> None:
    harness = _harness(tmp_path)
    result = subprocess.run([str(harness), "event-clock-error"], capture_output=True, check=True)
    assert result.stdout == b""


def test_identity_deadlines_cleanup_and_failure_status_cannot_be_pass() -> None:
    text = SOURCE.read_text(encoding="utf-8")
    handshake = HANDSHAKE.read_text(encoding="utf-8")
    assert '#include "VtestHandshake.c"' in text
    assert "VTEST_UID" in text and "allowed_cwd" in text
    assert "SO_PEERCRED" in text and "read_proc_start_ticks" in text
    assert "credentials_length != sizeof(credentials)" in text
    assert "MSG_NOSIGNAL" in handshake and "shutdown(fd, SHUT_WR)" in text
    assert "receive_expected_eof" in text and "TRI_VTEST_BUSY_WAIT_FLAG_WAIT" in text
    assert "server_identity_revalidated" in text
    assert 'strcmp(status, "TRIANGLE_OFFSCREEN") == 0' in text
    assert 'triangle_emit_event(state, success ? "TRIANGLE_OFFSCREEN" : "UNKNOWN")' in text
    assert "signal(" not in text and "kill(" not in text
    assert "#include <EGL" not in text and "libvirglrenderer" not in text


def test_native_source_compiles_with_strict_host_flags(tmp_path: Path) -> None:
    compiler = shutil.which("cc") or shutil.which("clang")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    subprocess.run(
        [compiler, "-D_GNU_SOURCE", "-DVTEST_TESTING=1", "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2", "-c", str(SOURCE), "-o", str(tmp_path / "triangle.o")],
        check=True,
    )


def test_build_contract_is_external_pinned_android_elf_and_no_execution() -> None:
    text = BUILD.read_text(encoding="utf-8")
    assert 'ARTIFACT_DIR="$ARTIFACT_PARENT/vtest-triangle"' in text
    assert "ls -ld -- \"$ARTIFACT_PARENT\"" in text
    assert "aarch64-linux-android29-clang" in text
    for flag in ("-O2", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie", "--build-id=none"):
        assert flag in text
    assert "/system/bin/linker64" in text and "libc.so" in text
    assert 'test "$needed_count" -eq 1' in text
    assert 'test "$size" -le 65536' in text
    assert "candidate-manifest.json" in text
    assert "officialReferences" in text
    assert "actualReadelf" in text
    assert 'source_sha=$(sha256sum "$SOURCE"' in text
