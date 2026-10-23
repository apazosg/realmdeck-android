#!/usr/bin/env python3
"""Run the fixed native four-byte vtest resource round trip.

This host runner selects the reviewed Android-default source candidate and
reuses the handshake runner's ownership, capture, staging, and cleanup path.
The native client only allocates, maps, writes, reads, and unreferences one
four-byte resource; it is not a rendering or device qualification.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import stat
import sys
from pathlib import Path
from typing import Any


_SOURCE_RUNNER_NAME = "vtest_source_runner"
_SOURCE_RUNNER_PATH = Path(__file__).with_name("run_source_handshake_usb.py")


def _load_source_runner() -> Any:
    existing = sys.modules.get(_SOURCE_RUNNER_NAME)
    existing_path = getattr(existing, "__file__", None) if existing is not None else None
    if existing is not None and existing_path is not None and Path(existing_path).resolve() == _SOURCE_RUNNER_PATH.resolve():
        return existing
    spec = importlib.util.spec_from_file_location(_SOURCE_RUNNER_NAME, _SOURCE_RUNNER_PATH)
    if spec is None or spec.loader is None:  # pragma: no cover - packaging error
        raise ImportError(f"cannot load {_SOURCE_RUNNER_PATH}")
    module = importlib.util.module_from_spec(spec)
    # SourceCandidate is a dataclass.  Register the module before executing it
    # so dataclasses can resolve its defining module in every import mode.
    sys.modules[_SOURCE_RUNNER_NAME] = module
    try:
        spec.loader.exec_module(module)
    except Exception:
        sys.modules.pop(_SOURCE_RUNNER_NAME, None)
        raise
    return module


source_runner = _load_source_runner()
shared = source_runner.shared
SourceCandidate = source_runner.SourceCandidate
ANDROID_DEFAULT = source_runner.ANDROID_DEFAULT

CLIENT = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/controls/"
    "vtest-resource/VtestResource"
)
CLIENT_BYTES = 21_848
CLIENT_SHA256 = "a31356a847bd342a2b5469d0837f88e2389c234f3b03d1beddfe3be88198efac"
CLIENT_MODE = 0o755
CLIENT_NAME = "VtestResource"
SUCCESS_STATUS = "VTEST_RESOURCE_ROUNDTRIP"
NATIVE_SUCCESS_STATUS = "RESOURCE_ROUNDTRIP"
DEFAULT_HOST_SECONDS = 20
MAX_HOST_SECONDS = 20
BOUNDARY = shared.RESOURCE_BOUNDARY
SOURCE = Path(__file__).with_name("VtestResource.c")
INCLUDED_SOURCE = Path(__file__).with_name("VtestHandshake.c")
KNOWN_SOURCE_SHA256 = "f85a9467f827d67f4cf0d350542834c17ea9f4a893cc0f875a0d3063dc6710f1"
KNOWN_INCLUDED_SOURCE_SHA256 = "e968d23777d858df1f9ac8bec1c621127a3f108d160a6f30af7d0fe5da6a6721"
SOURCE_LIMIT = 1024 * 1024
EXPECTED_NEEDED = ("libc.so",)

RESOURCE_STAGES = [
    "uid_verified",
    "cwd_verified",
    "server_identity_loaded",
    "socket_verified",
    "connected",
    "peer_verified",
    "server_identity_verified",
    "request_sent",
    "capabilities_received",
    "resource_create_sent",
    "transfer_put_sent",
    "transfer_get_sent",
    "readback_verified",
    "resource_unref_sent",
    "server_identity_revalidated",
    "response_eofverified",
    "cleanup",
]
RESOURCE_KEYS = {
    *shared.EVENT_KEYS,
    "resourceId",
    "resourceBytes",
    "writtenHex",
    "readbackHex",
    "resourceTransferVerified",
    "resourceUnrefSent",
}
RESOURCE_HEX = "a1b2c3d4"


def load_candidate() -> tuple[dict[str, Any], list[dict[str, Any]], bytes]:
    """Load only the fixed Android-default candidate."""
    return source_runner.load_candidate(candidate=ANDROID_DEFAULT)


def copy_payload(files: list[dict[str, Any]], run: str) -> tuple[str, dict[str, Any]]:
    """Copy the fixed ten-file candidate through the reviewed copier."""
    return source_runner.copy_payload(files, run, candidate=ANDROID_DEFAULT)


def _source_record(path: Path, expected_sha: str) -> dict[str, str]:
    info = shared._regular_nosymlink(path)
    if info.st_size > SOURCE_LIMIT:
        raise ValueError(f"source exceeds its bound: {path}")
    with path.open("rb") as stream:
        data = stream.read(SOURCE_LIMIT + 1)
    if len(data) != info.st_size:
        raise ValueError(f"source changed while being captured: {path}")
    digest = hashlib.sha256(data).hexdigest()
    if digest != expected_sha:
        raise ValueError(f"source differs from the built artifact: {path}")
    return {"path": str(path), "sha256": digest}


def load_client() -> dict[str, Any]:
    """Pin the separate resource artifact before any ADB transfer."""
    info = shared._regular_nosymlink(CLIENT)
    if info.st_size != CLIENT_BYTES or stat.S_IMODE(info.st_mode) != CLIENT_MODE:
        raise ValueError("VtestResource artifact metadata mismatch")
    data = CLIENT.read_bytes()
    if len(data) != CLIENT_BYTES or hashlib.sha256(data).hexdigest() != CLIENT_SHA256:
        raise ValueError("VtestResource artifact hash mismatch")
    # The fixed digest is the artifact authority.  These ELF gates document
    # the separately reviewed NDK 29 AArch64 PIE/linker boundary without
    # executing or inspecting a device copy.
    shared.loader._elf64_aarch64_dyn(data, CLIENT_NAME, require_linker_interp=True)
    return {
        "path": CLIENT,
        "bytes": CLIENT_BYTES,
        "sha256": CLIENT_SHA256,
        "mode": "0700",
        "source": _source_record(SOURCE, KNOWN_SOURCE_SHA256),
        "includedSource": _source_record(INCLUDED_SOURCE, KNOWN_INCLUDED_SOURCE_SHA256),
        "expectedNeeded": list(EXPECTED_NEEDED),
        "knownSourceSha256": KNOWN_SOURCE_SHA256,
        "knownIncludedSourceSha256": KNOWN_INCLUDED_SOURCE_SHA256,
    }


def copy_client(client: dict[str, Any], private_relative: str) -> dict[str, Any]:
    """Use the shared exclusive copier with only the registered client name."""
    return shared.copy_client(client, private_relative, client_name=CLIENT_NAME)


def parse_resource_event(raw: bytes, run: str) -> dict[str, Any]:
    """Parse and validate the native resource event as one exact JSON line."""
    if not raw or len(raw) > shared.CLIENT_STDOUT_LIMIT:
        raise ValueError("resource event is missing or oversized")
    try:
        text = raw.decode("utf-8", "strict")
        if not text.endswith("\n"):
            raise ValueError("resource event is not one JSON line")
        decoder = json.JSONDecoder(object_pairs_hook=shared.loader.unique_json_pairs)
        event, end = decoder.raw_decode(text[:-1])
        if end != len(text) - 1:
            raise ValueError("resource event has trailing output")
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise ValueError("resource event is not strict JSON") from exc
    if not isinstance(event, dict) or set(event) != RESOURCE_KEYS:
        raise ValueError("resource event schema mismatch")
    if event["runId"] != run or event["status"] != NATIVE_SUCCESS_STATUS:
        raise ValueError("resource event status or run id mismatch")
    integer_fields = (
        "nativePid", "uid", "peerPid", "peerUid", "expectedServerPid",
        "expectedServerStartTicks", "capBytes", "capNonzeroBytes", "durationMs",
        "resourceId", "resourceBytes",
    )
    if any(type(event[field]) is not int for field in integer_fields):
        raise ValueError("resource event integer field is malformed")
    if event["nativePid"] <= 0 or event["peerPid"] <= 0 or event["uid"] != shared.UID or event["peerUid"] != shared.UID:
        raise ValueError("resource event identity mismatch")
    if (event["peerPid"] != event["expectedServerPid"] or event["expectedServerStartTicks"] <= 0
            or event["serverIdentityVerified"] is not True):
        raise ValueError("resource event server instance mismatch")
    if not 0 < event["capBytes"] <= shared.MAX_CAP_BYTES or not 0 <= event["capNonzeroBytes"] <= event["capBytes"]:
        raise ValueError("resource capability size is outside its bound")
    if not isinstance(event["capsFNVhash"], str) or not shared.HEX16.fullmatch(event["capsFNVhash"]):
        raise ValueError("resource capability hash is not fixed-width hexadecimal")
    if not 0 <= event["durationMs"] <= 15_000 or event["stages"] != RESOURCE_STAGES:
        raise ValueError("resource event stages or duration mismatch")
    if event["resourceId"] != 1 or event["resourceBytes"] != 4:
        raise ValueError("resource identity or size mismatch")
    if event["writtenHex"] != RESOURCE_HEX or event["readbackHex"] != RESOURCE_HEX:
        raise ValueError("resource readback mismatch")
    if event["resourceTransferVerified"] is not True or event["resourceUnrefSent"] is not True:
        raise ValueError("resource transfer or unref is not verified")
    for field in shared.BROADER_FLAGS:
        if event[field] is not False:
            raise ValueError("resource event reported a broader qualification")
    return event


def classify_resource(
    *,
    run: str,
    server_stdout: bytes,
    server_stderr: bytes,
    client_stdout: bytes,
    client_stderr: bytes,
    server_exit: int | None,
    client_exit: int | None,
    timed_out: bool,
    complete: bool,
    server_stdout_truncated: bool = False,
    server_stderr_truncated: bool = False,
    client_stdout_truncated: bool = False,
    client_stderr_truncated: bool = False,
    expected_server: dict[str, Any] | None = None,
    forced_cleanup: bool = False,
    ownership_error: str | None = None,
) -> tuple[str, dict[str, Any] | None, str | None]:
    """Claim only the fixed resource event, never a generic exit-zero pass."""
    if timed_out or forced_cleanup or ownership_error is not None or not complete or any(
        (server_stdout_truncated, server_stderr_truncated, client_stdout_truncated, client_stderr_truncated)
    ):
        return "UNKNOWN", None, "capture incomplete or truncated"
    if server_exit is None or client_exit is None:
        return "UNKNOWN", None, "native exit status missing"
    if any(marker in server_stdout or marker in server_stderr for marker in shared.FORBIDDEN_SERVER_MARKERS):
        return "FAILED", None, "server renderer initialization or context failed"
    if server_stdout != shared.SERVER_STDOUT_CAPS_SEQUENCE:
        return "FAILED", None, "server emitted unexpected stdout"
    if client_stderr:
        return "FAILED", None, "client emitted unexpected stderr"
    try:
        event = parse_resource_event(client_stdout, run)
    except ValueError as exc:
        return "FAILED", None, str(exc)
    if server_exit != 0 or client_exit != 0:
        return "FAILED", event, "native process exit was non-zero"
    if (expected_server is None or event["expectedServerPid"] != expected_server.get("pid")
            or event["expectedServerStartTicks"] != expected_server.get("startTicks")):
        return "FAILED", event, "peer is not the HOST-verified launched server"
    return SUCCESS_STATUS, event, None


def main(argv: list[str] | None = None) -> int:
    """Expose only the bounded output/deadline CLI for this fixed control."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="existing external evidence parent")
    parser.add_argument("--seconds", type=int, default=DEFAULT_HOST_SECONDS,
                        help="bounded host run deadline (5..20 seconds; native client <=15 seconds)")
    args = parser.parse_args(argv)
    if not shared.MIN_SECONDS <= args.seconds <= MAX_HOST_SECONDS:
        parser.error("--seconds must be between 5 and 20")
    normalized = ["--output", str(args.output), "--seconds", str(args.seconds)]
    return shared.main(
        normalized,
        candidate_loader=load_candidate,
        payload_copier=copy_payload,
        client_loader=load_client,
        client_copier=copy_client,
        client_name=CLIENT_NAME,
        classifier=classify_resource,
        success_status=SUCCESS_STATUS,
        boundary=BOUNDARY,
    )


if __name__ == "__main__":
    raise SystemExit(main())
