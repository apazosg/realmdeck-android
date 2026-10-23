#!/usr/bin/env python3
"""Fixed private vtest triangle/readback gate; no presentation or Wine launch."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import stat
from pathlib import Path
from typing import Any

_path = Path(__file__).with_name("run_resource_usb.py")
_spec = importlib.util.spec_from_file_location("vtest_resource_for_triangle", _path)
if _spec is None or _spec.loader is None:
    raise ImportError(str(_path))
resource = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(resource)
shared = resource.shared

CLIENT_NAME = "VtestTriangle"
CLIENT = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/vtest-triangle/VtestTriangle")
CLIENT_BYTES = 30_944
CLIENT_SHA256 = "52c6edbfa00213b86390b1178edddea125916945baf7a8b94562e76a7ee94325"
CLIENT_MODE = 0o755
SOURCE = Path(__file__).with_name("VtestTriangle.c")
KNOWN_SOURCE_SHA256 = "85e147f73700bf0b95c601ad46872cb37b90c883287d3767d4691cc227fb5d07"
NATIVE_SUCCESS_STATUS = "TRIANGLE_OFFSCREEN"
SUCCESS_STATUS = "VTEST_TRIANGLE_OFFSCREEN"
BOUNDARY = shared.TRIANGLE_BOUNDARY
STAGES = [
    "uid_verified", "cwd_verified", "server_identity_loaded", "socket_verified",
    "connected", "peer_verified", "server_identity_verified", "request_sent",
    "capabilities_received", "render_target_create_sent", "vbo_create_sent",
    "setup_submitted", "clear_verified", "draw_submitted", "busy_wait_verified",
    "triangle_readback_verified", "objects_cleanup_sent", "resource_unref_sent",
    "server_identity_revalidated", "response_eofverified", "cleanup",
]
KEYS = {
    *shared.EVENT_KEYS, "protocolCaps", "width", "height", "readbackBytes",
    "clearVerified", "clearPixels", "trianglePixels", "greenPixels",
    "nonuniformRGB", "readbackFNVhash", "unrefSent", "resourceUnrefSent",
    "triangleVerified", "offscreenDrawVerified", "presentationVerified",
    "d3dVerified", "wowVerified", "hardwareRenderingVerified",
}


def load_client() -> dict[str, Any]:
    """Only the fixed libc-only AA64 client and its exact built sources."""
    info = shared._regular_nosymlink(CLIENT)
    if info.st_size != CLIENT_BYTES or stat.S_IMODE(info.st_mode) != CLIENT_MODE:
        raise ValueError("triangle artifact metadata mismatch")
    with CLIENT.open("rb") as stream:
        raw = stream.read(CLIENT_BYTES + 1)
    if len(raw) != CLIENT_BYTES or hashlib.sha256(raw).hexdigest() != CLIENT_SHA256:
        raise ValueError("triangle artifact hash mismatch")
    shared.loader._elf64_aarch64_dyn(raw, CLIENT_NAME, require_linker_interp=True)
    return {
        "path": CLIENT, "bytes": CLIENT_BYTES, "sha256": CLIENT_SHA256,
        "mode": "0700",
        "source": resource._source_record(SOURCE, KNOWN_SOURCE_SHA256),
        "includedSource": resource._source_record(resource.INCLUDED_SOURCE, resource.KNOWN_INCLUDED_SOURCE_SHA256),
        "expectedNeeded": ["libc.so"],
    }


def copy_client(client: dict[str, Any], private_relative: str) -> dict[str, Any]:
    return shared.copy_client(client, private_relative, client_name=CLIENT_NAME)


def parse_triangle_event(raw: bytes, run: str) -> dict[str, Any]:
    if not raw or len(raw) > 4096 or not raw.endswith(b"\n"):
        raise ValueError("missing, oversized or incomplete triangle event")
    try:
        text = raw.decode("utf-8", "strict")
        decoder = json.JSONDecoder(object_pairs_hook=shared.loader.unique_json_pairs)
        value, end = decoder.raw_decode(text[:-1])
        if end != len(text) - 1:
            raise ValueError("trailing triangle output")
    except (UnicodeDecodeError, ValueError) as exc:
        raise ValueError("invalid triangle JSON") from exc
    if not isinstance(value, dict) or set(value) != KEYS:
        raise ValueError("triangle schema mismatch")
    if value["runId"] != run or value["status"] != NATIVE_SUCCESS_STATUS:
        raise ValueError("triangle RUN/status mismatch")
    ints = (
        "nativePid", "uid", "peerPid", "peerUid", "expectedServerPid",
        "expectedServerStartTicks", "capBytes", "capNonzeroBytes", "durationMs",
        "width", "height", "readbackBytes", "clearPixels", "trianglePixels", "greenPixels",
    )
    if any(type(value[key]) is not int for key in ints):
        raise ValueError("triangle integer type mismatch")
    if (value["nativePid"] <= 0 or value["uid"] != shared.UID or value["peerUid"] != shared.UID
            or value["peerPid"] <= 0 or value["peerPid"] != value["expectedServerPid"]
            or value["expectedServerStartTicks"] <= 0 or value["serverIdentityVerified"] is not True):
        raise ValueError("triangle server identity mismatch")
    if (value["stages"] != STAGES or not 0 <= value["durationMs"] <= 15_000
            or not 0 < value["capBytes"] <= shared.MAX_CAP_BYTES
            or not 0 <= value["capNonzeroBytes"] <= value["capBytes"]):
        raise ValueError("triangle stages/caps/deadline mismatch")
    if (not isinstance(value["capsFNVhash"], str) or not shared.HEX16.fullmatch(value["capsFNVhash"])
            or not isinstance(value["readbackFNVhash"], str)
            or not shared.HEX16.fullmatch(value["readbackFNVhash"])
            or int(value["readbackFNVhash"], 16) == 0):
        raise ValueError("triangle hash mismatch")
    caps = value["protocolCaps"]
    if (not isinstance(caps, dict) or set(caps) != {"capBytes", "capNonzeroBytes", "capsFNVhash"}
            or type(caps["capBytes"]) is not int or type(caps["capNonzeroBytes"]) is not int
            or any(caps[key] != value[key] for key in caps)):
        raise ValueError("triangle duplicated cap evidence mismatch")
    if (value["width"] != 64 or value["height"] != 64 or value["readbackBytes"] != 16_384
            or value["clearPixels"] != 4096 or value["trianglePixels"] < 128
            or value["greenPixels"] < 128 or value["trianglePixels"] + value["greenPixels"] != 4096):
        raise ValueError("triangle pixel/readback gate mismatch")
    for key in ("clearVerified", "nonuniformRGB", "unrefSent", "resourceUnrefSent", "triangleVerified", "offscreenDrawVerified"):
        if value[key] is not True:
            raise ValueError("triangle or cleanup not verified")
    for key in (*shared.BROADER_FLAGS, "presentationVerified", "d3dVerified", "wowVerified", "hardwareRenderingVerified"):
        if value[key] is not False:
            raise ValueError("triangle event overstated its boundary")
    return value


def classify_triangle(
    *, run: str, server_stdout: bytes, server_stderr: bytes,
    client_stdout: bytes, client_stderr: bytes,
    server_exit: int | None, client_exit: int | None, timed_out: bool, complete: bool,
    server_stdout_truncated: bool = False, server_stderr_truncated: bool = False,
    client_stdout_truncated: bool = False, client_stderr_truncated: bool = False,
    expected_server: dict[str, Any] | None = None,
    forced_cleanup: bool = False, ownership_error: str | None = None,
) -> tuple[str, dict[str, Any] | None, str | None]:
    if (timed_out or forced_cleanup or ownership_error or not complete
            or any((server_stdout_truncated, server_stderr_truncated, client_stdout_truncated, client_stderr_truncated))):
        return "UNKNOWN", None, "capture or ownership incomplete"
    if server_exit is None or client_exit is None:
        return "UNKNOWN", None, "native exit status unavailable"
    if any(marker in server_stdout or marker in server_stderr for marker in shared.FORBIDDEN_SERVER_MARKERS):
        return "FAILED", None, "renderer/context failure"
    if server_stdout != shared.SERVER_STDOUT_CAPS_SEQUENCE or client_stderr:
        return "FAILED", None, "unexpected native output"
    try:
        event = parse_triangle_event(client_stdout, run)
    except ValueError as exc:
        return "FAILED", None, str(exc)
    if server_exit != 0 or client_exit != 0:
        return "FAILED", event, "native process exit nonzero"
    if (expected_server is None or event["expectedServerPid"] != expected_server.get("pid")
            or event["expectedServerStartTicks"] != expected_server.get("startTicks")):
        return "FAILED", event, "peer differs from HOST-owned server"
    return SUCCESS_STATUS, event, None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seconds", type=int, default=20)
    args = parser.parse_args(argv)
    if not 5 <= args.seconds <= 20:
        parser.error("--seconds must be between 5 and 20")
    return shared.main(
        ["--output", str(args.output), "--seconds", str(args.seconds)],
        candidate_loader=resource.load_candidate, payload_copier=resource.copy_payload,
        client_loader=load_client, client_copier=copy_client, client_name=CLIENT_NAME,
        classifier=classify_triangle, success_status=SUCCESS_STATUS, boundary=BOUNDARY,
    )


if __name__ == "__main__":
    raise SystemExit(main())
