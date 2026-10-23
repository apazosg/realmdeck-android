#!/usr/bin/env python3
"""Run the bounded, native vtest capability handshake on the approved device.

This runner deliberately stops at ``CAPABILITIES_RECEIVED``.  It does not
start Wine, the WoW client, a renderer workload, or a presentation path.  The
server and the client are both children of the package's ``run-as`` shell and
all evidence is written before either child is started.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import os
import re
import shlex
import stat
import subprocess
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Callable


_LOADER_PATH = Path(__file__).with_name("run_loader_usb.py")
_LOADER_SPEC = importlib.util.spec_from_file_location("vtest_loader", _LOADER_PATH)
if _LOADER_SPEC is None or _LOADER_SPEC.loader is None:  # pragma: no cover - packaging error
    raise ImportError(f"cannot load {_LOADER_PATH}")
loader = importlib.util.module_from_spec(_LOADER_SPEC)
_LOADER_SPEC.loader.exec_module(loader)


ADB = loader.ADB
SERIAL = loader.SERIAL
PACKAGE = loader.PACKAGE
UID = loader.UID
VERSION_CODE = loader.VERSION_CODE
CLIENT = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/controls/"
    "vtest-client/VtestHandshake"
)
CLIENT_BYTES = 16_360
CLIENT_SHA256 = "c8f7223f368e5441f00e580cd725d98e55391c9810886a0531dcb90900d46b22"
CLIENT_MODE = 0o755
RUN_PREFIX = "VTEST-"
CLIENT_NAME = "VtestHandshake"
RESOURCE_CLIENT_NAME = "VtestResource"
TRIANGLE_CLIENT_NAME = "VtestTriangle"
STAGE_PREFIX = "/data/local/tmp/wowforever-vtest-client-"
SERVER_STDOUT_LIMIT = 64 * 1024
SERVER_STDERR_LIMIT = 128 * 1024
CLIENT_STDOUT_LIMIT = 64 * 1024
CLIENT_STDERR_LIMIT = 128 * 1024
MAX_CAP_BYTES = 256 * 1024
DEFAULT_SECONDS = 15
MIN_SECONDS = 5
MAX_SECONDS = 30
SUCCESS_MARKER = b"vtest_client_dispatch_commands: client context created."
# The pinned upstream vtest_server.c uses printf for context creation and stdout
# for an orderly client disconnect. Some prior candidates emitted the marker on
# stderr. Accept this one exact stdout sequence, not arbitrary server chatter.
SERVER_STDOUT_CAPS_SEQUENCE = SUCCESS_MARKER + b"\nclient: VTEST_CLIENT_DISCONNECTED\n"
FORBIDDEN_SERVER_MARKERS = (b"failed to initialise renderer.", b"CONTEXT_MISSING")
EXPECTED_STAGES = [
    "uid_verified",
    "cwd_verified",
    "server_identity_loaded",
    "socket_verified",
    "connected",
    "peer_verified",
    "server_identity_verified",
    "request_sent",
    "server_identity_revalidated",
    "response_eofverified",
    "capabilities_received",
    "cleanup",
]
BROADER_FLAGS = ("egl", "gpu", "triangle", "device", "hardware", "present", "wowPass", "render")
EVENT_KEYS = {
    "runId",
    "status",
    "nativePid",
    "uid",
    "peerPid",
    "peerUid",
    "expectedServerPid",
    "expectedServerStartTicks",
    "serverIdentityVerified",
    "stages",
    "capBytes",
    "capNonzeroBytes",
    "capsFNVhash",
    "durationMs",
    *BROADER_FLAGS,
}
HEX16 = re.compile(r"^[0-9a-f]{16}$")
CAPTURE_BOUNDARY = "native vtest capability handshake only; not hardware, presentation, D3D, WoW, or Wine qualification"
RESOURCE_BOUNDARY = "native vtest resource allocation/map/write/read only; not hardware, rendering, presentation, D3D, WoW, or Wine qualification"
TRIANGLE_BOUNDARY = "native vtest fixed 64x64 offscreen triangle/readback only; not presentation, D3D, WoW, Wine, or general hardware support"
_REGISTERED_INTEGRATIONS = {
    (CLIENT_NAME, "VTEST_CAPS_RECEIVED", CAPTURE_BOUNDARY),
    (RESOURCE_CLIENT_NAME, "VTEST_RESOURCE_ROUNDTRIP", RESOURCE_BOUNDARY),
    (TRIANGLE_CLIENT_NAME, "VTEST_TRIANGLE_OFFSCREEN", TRIANGLE_BOUNDARY),
}


# These small wrappers keep the loader's already-reviewed gates in one place,
# while making the handshake orchestration independently testable offline.
def load_candidate() -> tuple[dict[str, Any], list[dict[str, Any]], bytes]:
    return loader.load_candidate()


def remote_status_preflight() -> dict[str, Any]:
    return loader.remote_status_preflight()


def package_preflight() -> tuple[int, str, str]:
    return loader._package_preflight()


def system_alias_evidence() -> list[dict[str, Any]]:
    return loader.system_alias_evidence()


def own_processes(uid: int = UID) -> list[dict[str, str]]:
    return loader.own_processes(uid)


def copy_payload(files: list[dict[str, Any]], run: str) -> tuple[str, dict[str, Any]]:
    return loader.copy_payload(files, run)


def adb(*args: str, timeout: int = 15) -> subprocess.CompletedProcess[bytes]:
    return loader.adb(*args, timeout=timeout)


def require_adb(result: subprocess.CompletedProcess[bytes], stage: str, limit: int = 128 * 1024) -> bytes:
    return loader.require_adb(result, stage, limit)


def _write_exclusive(path: Path, data: bytes, mode: int = 0o600) -> None:
    loader._write_exclusive(path, data, mode)


def _regular_nosymlink(path: Path) -> os.stat_result:
    if path.is_symlink():
        raise ValueError(f"symlink is not an approved regular file: {path}")
    info = path.stat()
    if not stat.S_ISREG(info.st_mode):
        raise ValueError(f"not a regular file: {path}")
    return info


def load_client() -> dict[str, Any]:
    """Pin the separately built native client before any ADB transfer."""
    info = _regular_nosymlink(CLIENT)
    if info.st_size != CLIENT_BYTES or stat.S_IMODE(info.st_mode) != CLIENT_MODE:
        raise ValueError("VtestHandshake artifact metadata mismatch")
    data = CLIENT.read_bytes()
    if len(data) != CLIENT_BYTES or hashlib.sha256(data).hexdigest() != CLIENT_SHA256:
        raise ValueError("VtestHandshake artifact hash mismatch")
    return {"path": CLIENT, "bytes": CLIENT_BYTES, "sha256": CLIENT_SHA256, "mode": "0700"}


def _q(value: str) -> str:
    return shlex.quote(value)


def server_arguments(egl_mode: str) -> list[str]:
    if egl_mode not in {"default", "surfaceless"}:
        raise ValueError("unapproved EGL qualification mode")
    arguments = ["--no-loop-or-fork", "--use-gles"]
    if egl_mode == "surfaceless":
        arguments.append("--use-egl-surfaceless")
    return arguments + ["--socket-path", "vtest.sock"]


def copy_client(client: dict[str, Any], private_relative: str, *, client_name: str = CLIENT_NAME) -> dict[str, Any]:
    """Push exactly one client file through an exclusive public stage."""
    if client_name not in {CLIENT_NAME, RESOURCE_CLIENT_NAME, TRIANGLE_CLIENT_NAME}:
        raise ValueError("unapproved native client")
    if not re.fullmatch(r"files/forever/controls/vtest-loader/[A-Za-z0-9_-]{1,100}", private_relative):
        raise ValueError("invalid private client destination")
    stage = STAGE_PREFIX + uuid.uuid4().hex
    stage_created = False
    stage_file = f"{stage}/{client_name}"
    destination = f"{private_relative}/bin/{client_name}"
    cleanup_failures: list[str] = []
    try:
        require_adb(adb("shell", "test", "-d", "/data/local/tmp"), "client staging parent")
        require_adb(adb("shell", "mkdir", "-m", "755", stage), "exclusive client staging directory")
        stage_created = True
        require_adb(adb("push", str(client["path"]), stage_file, timeout=30), "client sync upload")
        require_adb(adb("shell", "chmod", "0644", stage_file), "client staging mode")
        script = "; ".join(
            [
                "umask 077",
                "set -e",
                "set -C",
                f"test ! -e {_q(destination)} && test ! -L {_q(destination)}",
                f"cat {_q(stage_file)} > {_q(destination)}",
                f"chmod 0700 {_q(destination)}",
                "printf VTEST_CLIENT_UPLOAD_OK",
            ]
        )
        uploaded = adb("exec-out", "run-as", PACKAGE, "sh", "-c", script, timeout=30)
        if uploaded.returncode or uploaded.stdout != b"VTEST_CLIENT_UPLOAD_OK" or uploaded.stderr:
            raise RuntimeError("private client upload sentinel was not exact")
        hashed = require_adb(
            adb("exec-out", "run-as", PACKAGE, "sha256sum", destination),
            "device client hash",
            limit=4096,
        ).decode(errors="replace").split()
        if not hashed or hashed[0] != client["sha256"]:
            raise RuntimeError("device client pin mismatch")
        mode = require_adb(
            adb("exec-out", "run-as", PACKAGE, "stat", "-c", "%a", destination),
            "device client mode",
            limit=4096,
        ).strip()
        if mode != b"700":
            raise RuntimeError("device client mode mismatch")
    finally:
        if stage_created:
            result = adb("shell", "rm", "-f", stage_file)
            if result.returncode or result.stdout or result.stderr:
                cleanup_failures.append(client_name)
            result = adb("shell", "rmdir", stage)
            if result.returncode or result.stdout or result.stderr:
                cleanup_failures.append("staging-directory")
    cleanup = {"path": stage, "stagingClean": not cleanup_failures, "failures": cleanup_failures}
    if cleanup_failures:
        raise RuntimeError("owned client staging cleanup failed; no payload executed: " + json.dumps(cleanup))
    return {"destination": destination, "bytes": client["bytes"], "sha256": client["sha256"], "mode": "0700", "cleanup": cleanup}


def _shell_command(private_root: str, executable: str, arguments: list[str]) -> list[str]:
    """Build a fixed ``adb shell -T run-as`` command with a private cwd."""
    root_match = re.fullmatch(
        r"/data/(?:user/0|data)/com\.wowforever\.arm64probe/files/forever/controls/"
        r"vtest-loader/(VTEST-[A-Za-z0-9_-]{1,94})", private_root,
    )
    if root_match is None:
        raise ValueError("unapproved private native working directory")
    if executable not in {"virgl_test_server", CLIENT_NAME, RESOURCE_CLIENT_NAME, TRIANGLE_CLIENT_NAME}:
        raise ValueError("unapproved native executable")
    allowed = ([server_arguments("default"), server_arguments("surfaceless")]
               if executable == "virgl_test_server" else [["--run-id", root_match[1]]])
    if arguments not in allowed:
        raise ValueError("native arguments are not the fixed qualification contract")
    root = _q(private_root)
    env = " ".join(
        _q(item)
        for item in (
            "PATH=/system/bin",
            "ANDROID_ROOT=/system",
            "ANDROID_DATA=/data",
            f"LD_LIBRARY_PATH={private_root}/lib:/system/lib64",
            f"TMPDIR={private_root}",
        )
    )
    binary = f"{private_root}/bin/{executable}"
    child = "/system/bin/env -i " + env + " /system/bin/linker64 " + _q(binary)
    child += " " + " ".join(_q(argument) for argument in arguments)
    script = f"cd {root} && exec {child}"
    if executable == "virgl_test_server":
        # The shell's PID/start-time survive exec. The client does not trust just
        # a same-UID socket: HOST verifies this exact instance before binding it.
        script = (f"cd {root} && umask 077 && set -e && set -C && "
                  "test ! -e server.pid && test ! -L server.pid && "
                  "printf '%s\\n' \"$$\" > server.pid && exec " + child)
    # adb shell reconstructs a remote shell command. Keep the entire inner script
    # as one argument to run-as sh -c; otherwise cd/exec may run in the shell UID.
    return [ADB, "-s", SERIAL, "shell", "-T", "run-as", PACKAGE, "/system/bin/sh", "-c", _q(script)]


def parse_client_event(raw: bytes, run: str) -> dict[str, Any]:
    """Parse one exact native event; no textual success marker is accepted."""
    if not raw or len(raw) > CLIENT_STDOUT_LIMIT:
        raise ValueError("client event is missing or oversized")
    try:
        text = raw.decode("utf-8", "strict")
        event = json.loads(text, object_pairs_hook=loader.unique_json_pairs)
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise ValueError("client event is not strict JSON") from exc
    if not isinstance(event, dict) or set(event) != EVENT_KEYS:
        raise ValueError("client event schema mismatch")
    if event["runId"] != run or event["status"] != "CAPABILITIES_RECEIVED":
        raise ValueError("client event status or run id mismatch")
    for field in ("nativePid", "peerPid", "uid", "peerUid", "expectedServerPid", "expectedServerStartTicks", "capBytes", "capNonzeroBytes", "durationMs"):
        if type(event[field]) is not int:
            raise ValueError(f"client event field is not an integer: {field}")
    if event["nativePid"] <= 0 or event["peerPid"] <= 0 or event["uid"] != UID or event["peerUid"] != UID:
        raise ValueError("client event identity mismatch")
    if (event["peerPid"] != event["expectedServerPid"] or event["expectedServerStartTicks"] <= 0
            or event["serverIdentityVerified"] is not True):
        raise ValueError("client event server instance mismatch")
    if not 0 < event["capBytes"] <= MAX_CAP_BYTES or not 0 <= event["capNonzeroBytes"] <= event["capBytes"]:
        raise ValueError("client capability size is outside its bound")
    if not isinstance(event["capsFNVhash"], str) or not HEX16.fullmatch(event["capsFNVhash"]):
        raise ValueError("client capability hash is not fixed-width hexadecimal")
    if event["durationMs"] < 0 or event["stages"] != EXPECTED_STAGES:
        raise ValueError("client stages or duration mismatch")
    if any(event[field] is not False for field in BROADER_FLAGS):
        raise ValueError("client reported a broader qualification")
    return event


def classify_handshake(
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
) -> tuple[str, dict[str, Any] | None, str | None]:
    """Return status, validated event, and a diagnostic without claiming hardware."""
    if timed_out or not complete or any(
        (server_stdout_truncated, server_stderr_truncated, client_stdout_truncated, client_stderr_truncated)
    ):
        return "UNKNOWN", None, "capture incomplete or truncated"
    if server_exit is None or client_exit is None:
        return "UNKNOWN", None, "native exit status missing"
    if any(marker in server_stderr or marker in server_stdout for marker in FORBIDDEN_SERVER_MARKERS):
        return "FAILED", None, "server renderer initialization or context failed"
    if not client_stdout or not (server_stderr or server_stdout):
        return "UNKNOWN", None, "required native output is missing"
    try:
        event = parse_client_event(client_stdout, run)
    except ValueError as exc:
        return "FAILED", None, str(exc)
    if client_exit != 0 or server_exit != 0:
        return "FAILED", event, "native process exit was non-zero"
    if (expected_server is None or event["expectedServerPid"] != expected_server.get("pid")
            or event["expectedServerStartTicks"] != expected_server.get("startTicks")):
        return "FAILED", event, "peer is not the HOST-verified launched server"
    if client_stderr:
        return "FAILED", event, "client emitted unexpected stderr"
    if server_stdout and server_stdout != SERVER_STDOUT_CAPS_SEQUENCE:
        return "FAILED", event, "server emitted unexpected stdout"
    if SUCCESS_MARKER not in server_stderr and server_stdout != SERVER_STDOUT_CAPS_SEQUENCE:
        return "FAILED", event, "server success evidence is absent"
    if any(marker in server_stderr for marker in FORBIDDEN_SERVER_MARKERS):
        return "FAILED", event, "server renderer failure evidence is present"
    # The report namespace is deliberately distinct from the native event's
    # status: this is a vtest capability result, not a generic hardware pass.
    return "VTEST_CAPS_RECEIVED", event, None


def _drain(name: str, pipe: Any, limit: int, result: dict[str, Any]) -> None:
    loader._drain(name, pipe, limit, result)


def _external_parent(value: Path) -> Path:
    return loader._external_parent(value)


def _write_output(out: Path, name: str, data: bytes) -> None:
    _write_exclusive(out / name, data)


def _terminate_held(processes: list[subprocess.Popen[bytes]]) -> bool:
    """Terminate only Popen handles owned by this runner; never guess a PID."""
    quiesced = True
    for process in processes:
        if process.poll() is None:
            process.terminate()
    for process in processes:
        if process.poll() is None:
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                # The Android package force-stop below is the only escalation.
                quiesced = False
    return quiesced


def _force_stop_package() -> dict[str, Any]:
    try:
        result = adb("shell", "am", "force-stop", PACKAGE, timeout=15)
    except Exception as exc:
        return {"action": "force-stop exact qualification package", "returnCode": None,
                "error": str(exc)}
    return {"action": "force-stop exact qualification package", "returnCode": result.returncode,
            "stdout": result.stdout.decode(errors="replace"), "stderr": result.stderr.decode(errors="replace")}


def _start_capture(out: Path, capture: dict[str, Any]) -> None:
    # Full command and all pins must be durable before Popen can run.
    _write_exclusive(out / "capture.json", (json.dumps(capture, indent=2) + "\n").encode())


def _stat_identity(raw: bytes, pid: int) -> tuple[int, int]:
    text = raw.decode("ascii", "strict").strip()
    if len(raw) > 16 * 1024 or ") " not in text:
        raise ValueError("invalid bounded server stat")
    prefix, rest = text.rsplit(") ", 1)
    if int(prefix.split("(", 1)[0].strip()) != pid:
        raise ValueError("server stat PID mismatch")
    fields = rest.split()
    ticks = int(fields[19])
    if ticks <= 0:
        raise ValueError("server start time missing")
    return pid, ticks


def bind_server_instance(private_root: str, private_relative: str,
                         arguments: list[str] | None = None) -> dict[str, Any]:
    deadline = time.monotonic() + 4
    def bounded_adb(*arguments: str) -> subprocess.CompletedProcess[bytes]:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError("server identity binding deadline expired")
        return adb(*arguments, timeout=min(remaining, 3))
    expected_binary = f"{private_root}/bin/virgl_test_server"
    suffix = arguments if arguments is not None else server_arguments("default")
    if suffix not in (server_arguments("default"), server_arguments("surfaceless")):
        raise ValueError("unapproved bound server arguments")
    last_error = "server PID was not published"
    while time.monotonic() < deadline:
        try:
            pid_raw = require_adb(bounded_adb("exec-out", "run-as", PACKAGE, "cat", private_relative + "/server.pid"),
                                  "owned server PID", limit=128).strip()
            if not re.fullmatch(rb"[1-9][0-9]{0,9}", pid_raw):
                raise ValueError("server PID is not numeric")
            pid = int(pid_raw)
            first = require_adb(bounded_adb("exec-out", "run-as", PACKAGE, "cat", f"/proc/{pid}/stat"), "server stat", limit=16 * 1024)
            status = require_adb(bounded_adb("exec-out", "run-as", PACKAGE, "cat", f"/proc/{pid}/status"), "server UID", limit=16 * 1024)
            uid = next(line.split()[1:] for line in status.decode("ascii", "strict").splitlines() if line.startswith("Uid:"))
            if uid != [str(UID)] * 4:
                raise ValueError("server UID mismatch")
            command = require_adb(bounded_adb("exec-out", "run-as", PACKAGE, "cat", f"/proc/{pid}/cmdline"), "server argv", limit=16 * 1024)
            tokens = [part.decode("utf-8", "strict") for part in command.split(b"\0") if part]
            if tokens not in ([expected_binary, *suffix], ["/system/bin/linker64", expected_binary, *suffix]):
                raise ValueError("server has not exec'd the fixed private binary")
            second = require_adb(bounded_adb("exec-out", "run-as", PACKAGE, "cat", f"/proc/{pid}/stat"), "server stat recheck", limit=16 * 1024)
            identity = _stat_identity(first, pid)
            if identity != _stat_identity(second, pid):
                raise ValueError("server changed identity during binding")
            destination = private_relative + "/server.identity"
            content = f"{pid} {identity[1]}\n"
            script = ("umask 077; set -e; set -C; "
                      f"test ! -L {_q(destination)} && test ! -e {_q(destination)}; "
                      f"printf %s {_q(content)} > {_q(destination)}; printf VTEST_SERVER_BOUND")
            written = bounded_adb("exec-out", "run-as", PACKAGE, "sh", "-c", script)
            if written.returncode or written.stdout != b"VTEST_SERVER_BOUND" or written.stderr:
                raise RuntimeError("server identity publication failed")
            return {"pid": pid, "startTicks": identity[1], "uid": UID, "binary": expected_binary}
        except (ValueError, RuntimeError, IndexError, StopIteration) as exc:
            last_error = str(exc)
            time.sleep(0.05)
    raise RuntimeError("unable to bind owned server instance: " + last_error)


def _candidate_capture_evidence(manifest: dict[str, Any]) -> dict[str, Any]:
    """Keep candidate provenance small and durable without trusting it as a gate."""
    evidence: dict[str, Any] = {}
    if "candidateKind" not in manifest:
        return evidence
    evidence["candidateKind"] = manifest["candidateKind"]
    if "oldProviderManifestSha256" in manifest:
        evidence["oldProviderManifestSha256"] = manifest["oldProviderManifestSha256"]
    source = manifest.get("source")
    if isinstance(source, dict):
        evidence["candidateSource"] = {
            key: source[key]
            for key in ("archive", "bytes", "sha256", "root")
            if key in source
        }
    profile = manifest.get("buildProfile")
    if isinstance(profile, dict):
        evidence["buildProfile"] = {
            key: profile[key]
            for key in ("name", "officialSourceBuild", "functionalEglFix")
            if key in profile
        }
    return evidence


def main(
    argv: list[str] | None = None,
    *,
    candidate_loader: Callable[[], tuple[dict[str, Any], list[dict[str, Any]], bytes]] | None = None,
    payload_copier: Callable[[list[dict[str, Any]], str], tuple[str, dict[str, Any]]] | None = None,
    client_loader: Callable[[], dict[str, Any]] | None = None,
    client_copier: Callable[[dict[str, Any], str], dict[str, Any]] | None = None,
    client_name: str | None = None,
    classifier: Callable[..., tuple[str, dict[str, Any] | None, str | None]] | None = None,
    success_status: str | None = None,
    boundary: str | None = None,
) -> int:
    # Resolve defaults at call time.  Besides keeping the old CLI unchanged,
    # this preserves the existing offline tests' module-level monkeypatches.
    candidate_loader = load_candidate if candidate_loader is None else candidate_loader
    payload_copier = copy_payload if payload_copier is None else payload_copier
    integration_was_supplied = any(value is not None for value in
                                   (client_loader, client_copier, client_name, classifier, success_status, boundary))
    client_loader = load_client if client_loader is None else client_loader
    client_copier = copy_client if client_copier is None else client_copier
    client_name = CLIENT_NAME if client_name is None else client_name
    classifier = classify_handshake if classifier is None else classifier
    success_status = "VTEST_CAPS_RECEIVED" if success_status is None else success_status
    boundary = CAPTURE_BOUNDARY if boundary is None else boundary
    if not all(callable(value) for value in (client_loader, client_copier, classifier)):
        raise ValueError("native client integration seams must be callable")
    if integration_was_supplied and (client_name, success_status, boundary) not in _REGISTERED_INTEGRATIONS:
        raise ValueError("unregistered native client integration")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="existing external evidence parent")
    parser.add_argument("--seconds", type=int, default=DEFAULT_SECONDS,
                         help="bounded native run deadline (5..30 seconds)")
    parser.add_argument("--egl-mode", choices=("default", "surfaceless"), default="default",
                        help="one fixed EGL initialization flag; not a new library or driver")
    args = parser.parse_args(argv)
    if not MIN_SECONDS <= args.seconds <= MAX_SECONDS:
        parser.error("--seconds must be between 5 and 30")
    parent = _external_parent(args.output)

    # Host pins and both fixed artifacts are checked before any ADB transfer.
    manifest, files, manifest_raw = candidate_loader()
    client = client_loader()
    shell_status = remote_status_preflight()
    uid, app_root, selinux_context = package_preflight()
    system_libs = system_alias_evidence()
    if uid != UID or not own_processes(UID) == []:
        raise RuntimeError("qualification package is not quiescent before upload")

    run = RUN_PREFIX + uuid.uuid4().hex
    private_relative, server_staging = payload_copier(files, run)
    client_upload = client_copier(client, private_relative)
    private_root = f"{app_root}/{private_relative}"
    native_server_arguments = server_arguments(args.egl_mode)
    server_command = _shell_command(
        private_root, "virgl_test_server",
        native_server_arguments,
    )
    client_command = _shell_command(private_root, client_name, ["--run-id", run])
    out = parent / ("native-" + run)
    out.mkdir(mode=0o700)
    client_capture = {"name": client_name, "destination": client_upload["destination"], "bytes": client["bytes"],
                      "sha256": client["sha256"], "mode": client_upload["mode"]}
    for provenance_key in ("source", "includedSource"):
        if provenance_key in client:
            client_capture[provenance_key] = client[provenance_key]
    capture = {
        "schema": 1,
        "runId": run,
        "serial": SERIAL,
        "package": PACKAGE,
        "uid": uid,
        "versionCode": VERSION_CODE,
        "selinuxContext": selinux_context,
        "manifestSha256": hashlib.sha256(manifest_raw).hexdigest(),
        **_candidate_capture_evidence(manifest),
        "payloadFiles": [{"destination": item["destination"], "bytes": item["bytes"], "sha256": item["sha256"]}
                         for item in files],
        "client": client_capture,
        "systemLibraries": system_libs,
        "workingDirectory": private_root,
        "serverCommand": server_command,
        "clientCommand": client_command,
        "shellStatusPreflight": shell_status,
        "stagingCleanup": {"server": server_staging, "client": client_upload["cleanup"]},
        "protocolVerified": False,
        "presentationVerified": False,
        "d3dVerified": False,
        "wowVerified": False,
        "hardware": False,
        "present": False,
        "executionStarted": False,
        "requestedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "eglModeRequested": args.egl_mode,
    }
    _start_capture(out, capture)

    processes: list[subprocess.Popen[bytes]] = []
    results: dict[str, Any] = {}
    threads: list[threading.Thread] = []
    timed_out = False
    ownership_error: str | None = None
    forced_cleanup: dict[str, Any] | None = None
    server_process: subprocess.Popen[bytes] | None = None
    client_process: subprocess.Popen[bytes] | None = None
    server_identity: dict[str, Any] | None = None
    started = time.monotonic()
    try:
        server_process = subprocess.Popen(server_command, stdin=subprocess.DEVNULL,
                                          stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        processes.append(server_process)
        for name, pipe, limit in (("serverStdout", server_process.stdout, SERVER_STDOUT_LIMIT),
                                   ("serverStderr", server_process.stderr, SERVER_STDERR_LIMIT)):
            assert pipe is not None
            thread = threading.Thread(target=_drain, args=(name, pipe, limit, results), daemon=True)
            threads.append(thread)
            thread.start()
        server_identity = bind_server_instance(private_root, private_relative, native_server_arguments)
        _write_exclusive(out / "server-instance.json", (json.dumps(server_identity, indent=2) + "\n").encode())
        client_process = subprocess.Popen(client_command, stdin=subprocess.DEVNULL,
                                          stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        processes.append(client_process)
        for name, pipe, limit in (("clientStdout", client_process.stdout, CLIENT_STDOUT_LIMIT),
                                   ("clientStderr", client_process.stderr, CLIENT_STDERR_LIMIT)):
            assert pipe is not None
            thread = threading.Thread(target=_drain, args=(name, pipe, limit, results), daemon=True)
            threads.append(thread)
            thread.start()
        deadline = started + args.seconds
        for process in (client_process, server_process):
            remaining = max(0.0, deadline - time.monotonic())
            try:
                process.wait(timeout=remaining)
            except subprocess.TimeoutExpired:
                timed_out = True
                break
    except Exception as exc:
        ownership_error = f"native child start failed: {exc}"
    finally:
        if timed_out or ownership_error:
            if not _terminate_held(processes):
                ownership_error = ownership_error or "held native child did not quiesce"
        for thread in threads:
            thread.join(timeout=3)
        if any(thread.is_alive() for thread in threads):
            ownership_error = ownership_error or "output drain did not quiesce"
        try:
            remaining = own_processes(UID)
        except Exception as exc:
            ownership_error = ownership_error or f"post-run ownership check failed: {exc}"
            remaining = []
        if timed_out or remaining or ownership_error:
            forced_cleanup = _force_stop_package()
            for process in processes:
                if process.poll() is None:
                    try:
                        process.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        # This is the held HOST adb client, never an Android PID.
                        process.kill()
                        process.wait(timeout=3)
            for thread in threads:
                thread.join(timeout=3)
            try:
                remaining = own_processes(UID)
            except Exception as exc:
                remaining = [{"state": "UNKNOWN", "error": str(exc)}]

    values = {
        name: results.get(name, {"data": b"", "total": 0, "truncated": False})
        for name in ("serverStdout", "serverStderr", "clientStdout", "clientStderr")
    }
    for name, item in values.items():
        _write_output(out, name + ".txt", item["data"])
    status, event, diagnostic = classifier(
        run=run,
        server_stdout=values["serverStdout"]["data"], server_stderr=values["serverStderr"]["data"],
        client_stdout=values["clientStdout"]["data"], client_stderr=values["clientStderr"]["data"],
        server_exit=server_process.returncode if server_process is not None else None,
        client_exit=client_process.returncode if client_process is not None else None,
        timed_out=timed_out or ownership_error is not None or forced_cleanup is not None,
        complete=not any(thread.is_alive() for thread in threads) and len(results) == 4,
        server_stdout_truncated=values["serverStdout"]["truncated"],
        server_stderr_truncated=values["serverStderr"]["truncated"],
        client_stdout_truncated=values["clientStdout"]["truncated"],
        client_stderr_truncated=values["clientStderr"]["truncated"],
        expected_server=server_identity,
    )
    summary = {
        "schema": 1,
        "runId": run,
        "serial": SERIAL,
        "package": PACKAGE,
        "status": status,
        "eglModeRequested": args.egl_mode,
        "diagnostic": diagnostic,
        "serverExitCode": server_process.returncode if server_process is not None else None,
        "clientExitCode": client_process.returncode if client_process is not None else None,
        "hostAdbServerPid": server_process.pid if server_process is not None else None,
        "hostAdbClientPid": client_process.pid if client_process is not None else None,
        "timedOut": timed_out,
        "forcedCleanup": forced_cleanup is not None,
        "forcedCleanupNativeExit": (
            {"serverExitCode": server_process.returncode if server_process is not None else None,
             "clientExitCode": client_process.returncode if client_process is not None else None}
            if forced_cleanup is not None else None
        ),
        "ownershipError": ownership_error,
        "remainingOwnProcesses": remaining,
        "serverStdoutBytes": values["serverStdout"]["total"],
        "serverStderrBytes": values["serverStderr"]["total"],
        "clientStdoutBytes": values["clientStdout"]["total"],
        "clientStderrBytes": values["clientStderr"]["total"],
        "serverStdoutTruncated": values["serverStdout"]["truncated"],
        "serverStderrTruncated": values["serverStderr"]["truncated"],
        "clientStdoutTruncated": values["clientStdout"]["truncated"],
        "clientStderrTruncated": values["clientStderr"]["truncated"],
        "completePipes": not any(thread.is_alive() for thread in threads) and len(results) == 4,
        "event": event,
        "verifiedServerInstance": server_identity,
        "protocolCapsReceived": status in {"VTEST_CAPS_RECEIVED", "VTEST_RESOURCE_ROUNDTRIP", "VTEST_TRIANGLE_OFFSCREEN"},
        "resourceTransferVerified": status in {"VTEST_RESOURCE_ROUNDTRIP", "VTEST_TRIANGLE_OFFSCREEN"},
        "offscreenTriangleVerified": status == "VTEST_TRIANGLE_OFFSCREEN",
        "presentationVerified": False,
        "d3dVerified": False,
        "wowVerified": False,
        "egl": False,
        "gpu": False,
        "triangle": False,
        "device": False,
        "hardware": False,
        "present": False,
        "d3d": False,
        "wowPass": False,
        "render": False,
        "nativeEglDlopenAliases": "UNVERIFIED",
        "output": str(out),
        "boundary": boundary,
    }
    _write_exclusive(out / "summary.json", (json.dumps(summary, indent=2) + "\n").encode())
    print(json.dumps(summary, indent=2))
    return 0 if status == success_status and forced_cleanup is None else 1


if __name__ == "__main__":
    raise SystemExit(main())
