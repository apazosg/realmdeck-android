#!/usr/bin/env python3
"""HOST-only orchestration for the fixed private guest EGL context gate."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import re
import shlex
import os
import stat
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Callable


DIRECTORY = Path(__file__).parent
if str(DIRECTORY) not in sys.path:
    sys.path.insert(0, str(DIRECTORY))


def _load(name: str, path: Path) -> Any:
    existing = sys.modules.get(name)
    if existing is not None and Path(getattr(existing, "__file__", "")).resolve() == path.resolve():
        return existing
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


source_runner = _load("vtest_context_source_runner", DIRECTORY / "run_source_handshake_usb.py")
shared = source_runner.shared
guest_loader = _load("vtest_context_guest_loader", DIRECTORY / "run_guest_loader_usb.py")

ADB = shared.ADB
SERIAL = shared.SERIAL
PACKAGE = shared.PACKAGE
UID = shared.UID
VERSION_CODE = shared.VERSION_CODE
RUN_PREFIX = "VTEST-"
PRIVATE_PREFIX = "files/forever/controls/vtest-loader"
NATIVE_NAME = "GuestEglContext"
NATIVE_ARTIFACT = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/guest-egl-context/GuestEglContext")
# Fixed parent-verified cross-build artifact; a changed output fails closed.
NATIVE_ARTIFACT_BYTES = 36528
NATIVE_ARTIFACT_SHA256 = "8db0e0565017743c29ad4814541b2c9607b42a3a48000bab538e3f16a5775975"
NATIVE_ARTIFACT_HOST_MODE = "0755"
NATIVE_ARTIFACT_MODE = "0700"
SCHEMA = "wowforever.android-guest-egl-context/version1"
EVENTS = ("begin", "scope", "library", "exports", "display", "context", "gl", "mappings", "cleanup", "end")
BASE_KEYS = {"schema", "runId", "event", "timeNs", "pid", "uid", "pgid", "sid"}
BROADER_FLAGS = ("hardwareRenderingVerified", "triangleVerified", "presentationVerified", "d3dVerified", "wowVerified")
# This pinned guest may disable its optional cache when the compiled Termux
# default is inaccessible. It is neither a graphics error nor a writable override.
OPTIONAL_CACHE_DISABLED_WARNING = b"Failed to create /data/data/com.termux/files for shader cache (Permission denied)---disabling.\n"
STDOUT_LIMIT = 64 * 1024
STDERR_LIMIT = 128 * 1024
DEADLINE_SECONDS = 30
MAX_STRING = 512
MAX_EXTENSION_SCAN = 16 * 1024
MAX_MAP_BYTES = 2 * 1024 * 1024
HEX64 = re.compile(r"^[0-9a-f]{64}$")

EVENT_FIELDS: dict[str, set[str]] = {
    "begin": {"status"},
    "scope": {"status", "scopeValid", "runIdValid", "uidValid", "cwdValid", "environmentValid", "selectionValid", "cwd", "libraryPath", "serverIdentityLoaded", "serverIdentityVerified", "expectedServerPid", "expectedServerStartTicks"},
    "library": {"status", "loaded", "privatePath", "canonicalPath", "loadError"},
    "exports": {"status", "exportCount", "expectedExportCount", "allExports", "ownerExact", "ownerPath", "missingSymbol", "wrongOwnerSymbol"},
    "display": {"status", "clientQueryValid", "clientExtensionScanValid", "clientExtensions", "clientExtensionScanBytes", "clientExtensionsTruncated", "hasEGL_EXT_platform_base", "displayCreated", "displayInitialized", "displayExtensions", "displayExtensionScanBytes", "displayExtensionsTruncated", "hasEGL_KHR_create_context", "hasEGL_KHR_surfaceless_context", "vendor", "version", "clientApis", "major", "minor", "eglError", "serverIdentityVerified"},
    "context": {"status", "boundApi", "configCount", "configChosen", "contextCreated", "contextMajor", "contextMinor", "profileCore", "makeCurrent", "eglError", "serverIdentityVerified"},
    "gl": {"status", "glExportsResolved", "vendor", "renderer", "version", "glslVersion", "rendererContainsVirgl", "softwareFallback", "glError", "serverIdentityVerified"},
    "mappings": {"status", "mapsRead", "mapsComplete", "mapsTruncated", "mapsBytes", "mapLines", "privateLoaderMapped", "privateVendorMapped", "privateGalliumMapped", "privateLlvmMapped", "androidEglMapped", "androidGlesMapped", "mappingError"},
    "cleanup": {"status", "unbindAttempted", "unbindOk", "destroyAttempted", "destroyOk", "terminateAttempted", "terminateOk", "handleClosed", "cleanupFailures", "serverIdentityRevalidated", "cleanupStatus"},
    "end": {"status", "privateLoaderVerified", "exportsVerified", "displayVerified", "eglContextVerified", "virpipeVerified", "glRendererVerified", "identityVerified", "mappingsVerified", "cleanupVerified", *BROADER_FLAGS, "nativeExit"},
}
BOOL_FIELDS = {"scopeValid", "runIdValid", "uidValid", "cwdValid", "environmentValid", "selectionValid", "serverIdentityLoaded", "serverIdentityVerified", "loaded", "allExports", "ownerExact", "clientQueryValid", "clientExtensionScanValid", "clientExtensionsTruncated", "hasEGL_EXT_platform_base", "displayCreated", "displayInitialized", "displayExtensionsTruncated", "hasEGL_KHR_create_context", "hasEGL_KHR_surfaceless_context", "boundApi", "configChosen", "contextCreated", "profileCore", "makeCurrent", "serverIdentityRevalidated", "glExportsResolved", "rendererContainsVirgl", "softwareFallback", "mapsRead", "mapsComplete", "mapsTruncated", "privateLoaderMapped", "privateVendorMapped", "privateGalliumMapped", "privateLlvmMapped", "androidEglMapped", "androidGlesMapped", "unbindAttempted", "unbindOk", "destroyAttempted", "destroyOk", "terminateAttempted", "terminateOk", "handleClosed", "privateLoaderVerified", "exportsVerified", "displayVerified", "eglContextVerified", "virpipeVerified", "glRendererVerified", "identityVerified", "mappingsVerified", "cleanupVerified", *BROADER_FLAGS}
OPTIONAL_BOOL = {"hasEGL_EXT_platform_base", "hasEGL_KHR_create_context", "hasEGL_KHR_surfaceless_context", "privateLoaderMapped", "privateVendorMapped", "privateGalliumMapped", "privateLlvmMapped", "androidEglMapped", "androidGlesMapped"}
INT_FIELDS = {"timeNs", "pid", "uid", "pgid", "sid", "expectedServerPid", "expectedServerStartTicks", "exportCount", "expectedExportCount", "clientExtensionScanBytes", "displayExtensionScanBytes", "major", "minor", "eglError", "configCount", "contextMajor", "contextMinor", "glError", "mapsBytes", "mapLines", "cleanupFailures", "nativeExit"}
STRING_FIELDS = {"status", "cwd", "libraryPath", "privatePath", "canonicalPath", "loadError", "ownerPath", "missingSymbol", "wrongOwnerSymbol", "clientExtensions", "displayExtensions", "vendor", "version", "clientApis", "glslVersion", "renderer", "mappingError", "cleanupStatus"}
NONNULL_STRING = {"status", "cleanupStatus"}


def _regular_nosymlink(path: Path) -> os.stat_result:
    if path.is_symlink():
        raise ValueError(f"symlink is not an approved regular file: {path}")
    info = path.stat()
    if not stat.S_ISREG(info.st_mode):
        raise ValueError(f"not a regular file: {path}")
    return info


def probe_file() -> dict[str, Any]:
    if NATIVE_ARTIFACT_BYTES <= 0 or not HEX64.fullmatch(NATIVE_ARTIFACT_SHA256) or NATIVE_ARTIFACT_SHA256 == "0" * 64:
        raise ValueError("native GuestEglContext artifact pin is still a placeholder")
    info = _regular_nosymlink(NATIVE_ARTIFACT)
    if info.st_size != NATIVE_ARTIFACT_BYTES or stat.S_IMODE(info.st_mode) != int(NATIVE_ARTIFACT_HOST_MODE, 8):
        raise ValueError("native GuestEglContext artifact metadata mismatch")
    data = NATIVE_ARTIFACT.read_bytes()
    if len(data) != NATIVE_ARTIFACT_BYTES or hashlib.sha256(data).hexdigest() != NATIVE_ARTIFACT_SHA256:
        raise ValueError("native GuestEglContext artifact hash mismatch")
    guest_loader.loader._elf64_aarch64_dyn(data, NATIVE_NAME, require_linker_interp=True)
    guest_loader._validate_native_dynamic(data)
    return {"destination": f"guest/bin/{NATIVE_NAME}", "path": NATIVE_ARTIFACT, "bytes": NATIVE_ARTIFACT_BYTES, "sha256": NATIVE_ARTIFACT_SHA256, "mode": NATIVE_ARTIFACT_MODE}


def _q(value: str) -> str:
    return shlex.quote(value)


def native_command(private_root: str, run: str) -> list[str]:
    guest = f"{private_root}/guest"
    roots = [f"/data/user/0/{PACKAGE}/{PRIVATE_PREFIX}/{run}", f"/data/data/{PACKAGE}/{PRIVATE_PREFIX}/{run}"]
    if private_root not in roots or not re.fullmatch(rf"/data/(?:user/0|data)/{re.escape(PACKAGE)}/{re.escape(PRIVATE_PREFIX)}/VTEST-[0-9a-f]{{32}}", private_root):
        raise ValueError("unapproved private guest context root")
    inner = (
        f"cd {_q(private_root)} && exec /system/bin/env -i "
        "PATH=/system/bin:/system/xbin ANDROID_ROOT=/system ANDROID_DATA=/data "
        f"{_q('LD_LIBRARY_PATH=' + guest + '/lib:/system/lib64')} "
        f"{_q('TMPDIR=' + private_root)} "
        f"{_q('__EGL_VENDOR_LIBRARY_FILENAMES=' + guest + '/share/glvnd/egl_vendor.d/50_mesa.json')} "
        "GALLIUM_DRIVER=virpipe VTEST_SOCKET_NAME=vtest.sock "
        f"/system/bin/linker64 {_q(guest + '/bin/' + NATIVE_NAME)} --run-id {_q(run)}"
    )
    return [ADB, "-s", SERIAL, "shell", "-T", "run-as", PACKAGE, "sh", "-c", _q(inner)]


def unique_json_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    return guest_loader.unique_json_pairs(pairs)


def _valid_event(row: dict[str, Any]) -> bool:
    event = row.get("event")
    if event not in EVENT_FIELDS or set(row) != BASE_KEYS | EVENT_FIELDS[event]:
        return False
    if not all(isinstance(row.get(key), str) for key in ("schema", "runId", "event")):
        return False
    for key, value in row.items():
        if key in BOOL_FIELDS and key not in OPTIONAL_BOOL and type(value) is not bool:
            return False
        if key in OPTIONAL_BOOL and value is not None and type(value) is not bool:
            return False
        if key in INT_FIELDS and (type(value) is not int or not (0 <= value <= (2**63 - 1))):
            return False
        if key in {"timeNs", "pid", "pgid", "sid"} and value <= 0:
            return False
        if key in STRING_FIELDS and value is not None:
            if not isinstance(value, str) or len(value) > MAX_STRING:
                return False
        if key in NONNULL_STRING and (not isinstance(value, str) or not value):
            return False
    return True


def validate_android_default_server_stderr(raw: bytes) -> dict[str, Any]:
    """Validate the historical three-line Android-default server identity."""
    lines = raw.splitlines()
    known_identity = len(lines) == 3 and all(
        line.startswith(prefix)
        and 0 < len(line) - len(prefix) <= 128
        and all(32 <= byte < 127 for byte in line)
        for line, prefix in zip(
            lines,
            (
                b"WFVTEST_ANDROID_DEFAULT gl.vendor=",
                b"WFVTEST_ANDROID_DEFAULT gl.renderer=",
                b"WFVTEST_ANDROID_DEFAULT gl.version=",
            ),
        )
    )
    return {
        "valid": known_identity,
        "identityVerified": known_identity,
        "parsedRows": [],
        "hasCapRequests": False,
        "capRequestCount": 0,
        "protocolMetadata": None,
        "traceTruncated": False,
    }


def validate_output(raw: bytes, run: str, exit_code: int | None, complete: bool, *, server_identity: dict[str, Any] | None = None) -> tuple[str, list[dict[str, Any]]]:
    if not complete or not raw or len(raw) > STDOUT_LIMIT or not raw.endswith(b"\n"):
        return "UNKNOWN", []
    try:
        lines = raw.splitlines()
        rows = [json.loads(line.decode("utf-8", "strict"), object_pairs_hook=unique_json_pairs) for line in lines]
        if len(rows) != len(EVENTS) or any(not isinstance(row, dict) or not _valid_event(row) for row in rows):
            return "UNKNOWN", rows
        if tuple(row["event"] for row in rows) != EVENTS:
            return "UNKNOWN", rows
        first = rows[0]
        if first["status"] != "STARTED":
            return "UNKNOWN", rows
        times = [row["timeNs"] for row in rows]
        if times != sorted(times):
            return "UNKNOWN", rows
        for row in rows:
            if row["schema"] != SCHEMA or row["runId"] != run or row["uid"] != UID or row["pid"] != first["pid"]:
                return "UNKNOWN", rows
            if row["pgid"] != first["pgid"] or row["sid"] != first["sid"]:
                return "UNKNOWN", rows
        end = rows[-1]
        if end["nativeExit"] != exit_code or any(end[key] is not False for key in BROADER_FLAGS):
            return "UNKNOWN", rows
        if end["status"] not in {"GUEST_CONTEXT_PASS", "CONTEXT_FAILED", "UNKNOWN"}:
            return "UNKNOWN", rows
        if end["status"] == "CONTEXT_FAILED":
            return ("CONTEXT_FAILED" if exit_code == 1 else "UNKNOWN"), rows
        if end["status"] != "GUEST_CONTEXT_PASS" or exit_code != 0:
            return "UNKNOWN", rows
        scope, library, exports, display, context, gl, mappings, cleanup = rows[1:9]
        private = scope["cwd"]
        allowed = (f"/data/user/0/{PACKAGE}/{PRIVATE_PREFIX}/{run}", f"/data/data/{PACKAGE}/{PRIVATE_PREFIX}/{run}")
        library_path = f"{private}/guest/lib/libEGL.so.1.1.0"
        good = (
            private in allowed and scope["status"] == "VALID" and all(scope[key] is True for key in ("scopeValid", "runIdValid", "uidValid", "cwdValid", "environmentValid", "selectionValid", "serverIdentityLoaded", "serverIdentityVerified"))
            and library["status"] == "LOADED" and library["loaded"] is True and library["privatePath"] == library_path and library["canonicalPath"] == library_path and library["loadError"] is None
            and exports["status"] == "EXPORTED" and exports["exportCount"] == 10 and exports["expectedExportCount"] == 10 and exports["allExports"] is True and exports["ownerExact"] is True and exports["ownerPath"] == library_path
            and exports["missingSymbol"] is None and exports["wrongOwnerSymbol"] is None
            and display["status"] == "MEASURED" and display["clientQueryValid"] is True and display["clientExtensionScanValid"] is True and display["clientExtensionScanBytes"] <= MAX_EXTENSION_SCAN and display["displayInitialized"] is True and display["displayExtensionScanBytes"] <= MAX_EXTENSION_SCAN
            and display["hasEGL_EXT_platform_base"] is True and display["hasEGL_KHR_create_context"] is True and display["hasEGL_KHR_surfaceless_context"] is True and display["eglError"] == 0x3000
            and context["status"] == "CURRENT" and context["boundApi"] is True and context["configChosen"] is True and context["configCount"] == 1 and context["contextCreated"] is True and context["profileCore"] is True and context["makeCurrent"] is True and (context["contextMajor"], context["contextMinor"]) >= (3, 3) and context["eglError"] == 0x3000
            and gl["status"] == "MEASURED" and gl["glExportsResolved"] is True and gl["rendererContainsVirgl"] is True and gl["softwareFallback"] is False and isinstance(gl["renderer"], str) and "virgl" in gl["renderer"].lower() and gl["glError"] == 0
            and mappings["status"] == "MAPPED" and mappings["mapsRead"] is True and mappings["mapsComplete"] is True and mappings["mapsTruncated"] is False and mappings["privateLoaderMapped"] is True and mappings["privateVendorMapped"] is True and mappings["privateGalliumMapped"] is True and mappings["privateLlvmMapped"] is True and mappings["androidEglMapped"] is False and mappings["androidGlesMapped"] is False and mappings["mappingError"] is None
            and cleanup["status"] == "CLEAN" and cleanup["cleanupFailures"] == 0 and cleanup["unbindOk"] is True and cleanup["destroyOk"] is True and cleanup["terminateOk"] is True and cleanup["handleClosed"] is True and cleanup["serverIdentityRevalidated"] is True
            and end["privateLoaderVerified"] is True and end["exportsVerified"] is True and end["displayVerified"] is True and end["eglContextVerified"] is True and end["virpipeVerified"] is True and end["glRendererVerified"] is True and end["identityVerified"] is True and end["mappingsVerified"] is True and end["cleanupVerified"] is True
        )
        good = good and all(row["serverIdentityVerified"] is True for row in (display, context, gl))
        good = good and all(isinstance(row[field], str) and row[field] for row, field in ((display, "vendor"), (display, "version"), (display, "clientApis"), (gl, "vendor"), (gl, "version"), (gl, "glslVersion")))
        # The native display row has no expected-server fields; the scope row is
        # the durable identity witness that must equal HOST's binding.
        good = good and server_identity is not None and scope["expectedServerPid"] == server_identity.get("pid") and scope["expectedServerStartTicks"] == server_identity.get("startTicks")
        return ("GUEST_CONTEXT_PASS" if good else "UNKNOWN"), rows
    except (UnicodeError, ValueError, json.JSONDecodeError, KeyError, TypeError):
        return "UNKNOWN", []


def _drain(name: str, pipe: Any, limit: int, result: dict[str, Any]) -> None:
    shared._drain(name, pipe, limit, result)


def _write(path: Path, name: str, data: bytes) -> None:
    shared._write_exclusive(path / name, data)


def main(
    argv: list[str] | None = None,
    *,
    probe_loader: Callable[[], dict[str, Any]] | None = None,
    payload_copier: Callable[..., tuple[str, dict[str, Any]]] | None = None,
    native_command_builder: Callable[[str, str], list[str]] | None = None,
    classifier: Callable[..., tuple[str, list[dict[str, Any]]]] | None = None,
    native_name: str | None = None,
    output_prefix: str | None = None,
    pass_status: str | None = None,
    boundary: str | None = None,
    source_candidate: source_runner.SourceCandidate | None = None,
    server_trace_validator: Callable[[bytes], dict[str, Any]] | None = None,
    guest_stderr_validator: Callable[[bytes], dict[str, Any]] | None = None,
) -> int:
    """Run the fixed two-child orchestration.

    The keyword-only arguments are an internal, offline-testable integration
    seam for probes which use the same Android-default server and guest
    closure.  With no seam supplied this is intentionally the historical
    GuestEglContext CLI and its capture/summary keys remain unchanged.
    """
    legacy = not any(value is not None for value in (
        probe_loader, payload_copier, native_command_builder, classifier,
        native_name, output_prefix, pass_status, boundary,
        source_candidate, server_trace_validator, guest_stderr_validator,
    ))
    if source_candidate is None:
        source_candidate = source_runner.ANDROID_DEFAULT
    if not any(source_candidate is candidate for candidate in source_runner.SOURCE_CANDIDATES.values()):
        raise ValueError("guest context requires a registered source candidate")
    server_trace_validator = validate_android_default_server_stderr if server_trace_validator is None else server_trace_validator
    if not callable(server_trace_validator):
        raise ValueError("server trace validator seam must be callable")
    if guest_stderr_validator is not None and not callable(guest_stderr_validator):
        raise ValueError("guest stderr validator seam must be callable")
    probe_loader = probe_file if probe_loader is None else probe_loader
    native_command_builder = native_command if native_command_builder is None else native_command_builder
    classifier = validate_output if classifier is None else classifier
    native_name = NATIVE_NAME if native_name is None else native_name
    output_prefix = "native-CONTEXT-" if output_prefix is None else output_prefix
    pass_status = "GUEST_CONTEXT_PASS" if pass_status is None else pass_status
    boundary = (
        "private guest EGL context and virgl renderer identity only; no draw, presentation, D3D, WoW, or Wine"
        if boundary is None else boundary
    )
    if not all(callable(value) for value in (probe_loader, native_command_builder, classifier)):
        raise ValueError("guest native integration seams must be callable")

    def default_payload_copier(
        files: list[dict[str, Any]], probe_data: dict[str, Any], run_id: str,
        aliases: list[dict[str, str]], *, app_root: str,
        server_files: list[dict[str, Any]], server_manifest_sha256: str,
    ) -> tuple[str, dict[str, Any]]:
        return guest_loader.copy_payload_to_existing_run(
            files, probe_data, run_id, aliases, app_root=app_root,
            server_files=server_files, server_manifest_sha256=server_manifest_sha256,
        )

    payload_copier = default_payload_copier if payload_copier is None else payload_copier
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="existing external evidence parent")
    args = parser.parse_args(argv)
    parent = shared._external_parent(args.output)

    source_manifest, server_files, source_raw = source_runner.load_candidate(candidate=source_candidate)
    guest_manifest, guest_files, _ = guest_loader.load_candidate()
    probe = probe_loader()
    shell_status = shared.remote_status_preflight()
    uid, app_root, selinux = shared.package_preflight()
    if uid != UID or shared.own_processes(UID):
        raise RuntimeError("qualification package is not quiescent before context upload")
    run = RUN_PREFIX + uuid.uuid4().hex
    private_relative, server_staging = source_runner.copy_payload(server_files, run, candidate=source_candidate)
    aliases = guest_manifest.get("approvedSymlinkAliases", [])
    private_relative, guest_staging = payload_copier(
        guest_files, probe, run, aliases, app_root=app_root, server_files=server_files,
        server_manifest_sha256=source_candidate.manifest_sha256,
    )
    private_root = f"{app_root}/{private_relative}"
    server_args = shared.server_arguments("default")
    server_command = shared._shell_command(private_root, "virgl_test_server", server_args)
    context_command = native_command_builder(private_root, run)
    out = parent / (output_prefix + run)
    out.mkdir(mode=0o700)
    capture = {
        "schema": 1, "runId": run, "serial": SERIAL, "package": PACKAGE, "uid": uid, "versionCode": VERSION_CODE,
        "selinuxContext": selinux, "serverManifestSha256": source_candidate.manifest_sha256,
        "serverManifestRawSha256": hashlib.sha256(source_raw).hexdigest(), "guestManifestSha256": guest_loader.MANIFEST_SHA256, "guestTreeSha256": guest_loader.TREE_SHA256,
        "serverFiles": [{key: item[key] for key in ("destination", "bytes", "sha256", "mode")} for item in server_files],
        "guestFiles": [{key: item[key] for key in ("destination", "bytes", "sha256", "mode")} for item in guest_files],
        "guestAliases": aliases, "nativeArtifact": {"destination": probe["destination"], "bytes": probe["bytes"], "sha256": probe["sha256"], "mode": probe["mode"]},
        "workingDirectory": private_root, "serverCommand": server_command, "contextCommand": context_command,
        "serverEnvironment": {"PATH": "/system/bin", "ANDROID_ROOT": "/system", "ANDROID_DATA": "/data", "LD_LIBRARY_PATH": private_root + "/lib:/system/lib64", "TMPDIR": private_root}, "shellStatusPreflight": shell_status,
        "stagingCleanup": {"server": server_staging, "guest": guest_staging}, "executionStarted": False,
        "requestedAt": dt.datetime.now(dt.timezone.utc).isoformat(), **{key: False for key in BROADER_FLAGS},
    }
    if not legacy:
        capture["nativeCommand"] = capture.pop("contextCommand")
        capture["passStatus"] = pass_status
        capture["nativeArtifact"]["name"] = native_name
    shared._write_exclusive(out / "capture.json", (json.dumps(capture, indent=2) + "\n").encode())

    server_process: subprocess.Popen[bytes] | None = None
    context_process: subprocess.Popen[bytes] | None = None
    processes: list[subprocess.Popen[bytes]] = []
    threads: list[threading.Thread] = []
    results: dict[str, Any] = {}
    identity: dict[str, Any] | None = None
    timed_out = False
    ownership_error: str | None = None
    forced_cleanup: dict[str, Any] | None = None
    started = time.monotonic()
    try:
        server_process = subprocess.Popen(server_command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        processes.append(server_process)
        for name, pipe, limit in (("serverStdout", server_process.stdout, shared.SERVER_STDOUT_LIMIT), ("serverStderr", server_process.stderr, shared.SERVER_STDERR_LIMIT)):
            assert pipe is not None
            thread = threading.Thread(target=_drain, args=(name, pipe, limit, results), daemon=True)
            threads.append(thread)
            thread.start()
        identity = shared.bind_server_instance(private_root, private_relative, server_args)
        shared._write_exclusive(out / "server-instance.json", (json.dumps(identity, indent=2) + "\n").encode())
        context_process = subprocess.Popen(context_command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        processes.append(context_process)
        for name, pipe, limit in (("contextStdout", context_process.stdout, STDOUT_LIMIT), ("contextStderr", context_process.stderr, STDERR_LIMIT)):
            assert pipe is not None
            thread = threading.Thread(target=_drain, args=(name, pipe, limit, results), daemon=True)
            threads.append(thread)
            thread.start()
        deadline = started + DEADLINE_SECONDS
        for process in (context_process, server_process):
            try:
                process.wait(timeout=max(0.0, deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                timed_out = True
                break
    except Exception as exc:
        ownership_error = f"native child start/bind failed: {exc}"
    finally:
        if timed_out or ownership_error:
            if not shared._terminate_held(processes):
                ownership_error = ownership_error or "held native child did not quiesce"
        for thread in threads:
            thread.join(timeout=3)
        if any(thread.is_alive() for thread in threads):
            ownership_error = ownership_error or "output drain did not quiesce"
        try:
            remaining = shared.own_processes(UID)
        except Exception as exc:
            ownership_error = ownership_error or f"post-run ownership check failed: {exc}"
            remaining = []
        if timed_out or remaining or ownership_error:
            try:
                stopped = shared.adb("shell", "am", "force-stop", PACKAGE, timeout=3)
                forced_cleanup = {"action": "exact owned package force-stop", "returnCode": stopped.returncode}
                if stopped.returncode:
                    ownership_error = ownership_error or "owned package cleanup failed"
            except Exception as exc:
                ownership_error = ownership_error or f"owned package cleanup unavailable: {exc}"
                forced_cleanup = {"action": "exact owned package force-stop", "state": "UNKNOWN"}
            finally:
                for process in processes:
                    try:
                        if process.poll() is None:
                            process.terminate()
                            try:
                                process.wait(timeout=1)
                            except subprocess.TimeoutExpired:
                                process.kill()
                                process.wait(timeout=1)
                    except Exception as exc:
                        ownership_error = ownership_error or f"held HOST handle cleanup: {exc}"
            try:
                remaining = guest_loader.owned_processes(3)
            except Exception as exc:
                remaining = [{"state": "UNKNOWN"}]
                ownership_error = ownership_error or f"final ownership unavailable: {exc}"
    values = {name: results.get(name, {"data": b"", "total": 0, "truncated": False}) for name in ("serverStdout", "serverStderr", "contextStdout", "contextStderr")}
    for name, value in values.items():
        _write(out, name + ".txt", value["data"])
    complete = not any(thread.is_alive() for thread in threads) and len(results) == 4
    classified = classifier(
        values["contextStdout"]["data"], run,
        context_process.returncode if context_process else None, complete,
        server_identity=identity,
    )
    if not isinstance(classified, tuple) or len(classified) not in {2, 3}:
        raise ValueError("native classifier returned an invalid result")
    status, rows = classified[:2]
    actual_context_status = classified[2] if len(classified) == 3 else status
    server_stderr = values["serverStderr"]["data"]
    trace_result = server_trace_validator(server_stderr)
    if not isinstance(trace_result, dict):
        raise ValueError("server trace validator returned an invalid result")
    known_identity = trace_result.get("valid") is True and trace_result.get("identityVerified") is True
    failure_marker = any(marker in value["data"] for marker in shared.FORBIDDEN_SERVER_MARKERS for value in (values["serverStdout"], values["serverStderr"]))
    optional_cache_warning = values["contextStderr"]["data"] == OPTIONAL_CACHE_DISABLED_WARNING
    guest_stderr_observation = None
    guest_stderr_ok = not values["contextStderr"]["data"] or optional_cache_warning
    if guest_stderr_validator is not None:
        guest_stderr_observation = guest_stderr_validator(values["contextStderr"]["data"])
        guest_stderr_ok = isinstance(guest_stderr_observation, dict) and guest_stderr_observation.get("valid") is True
    if values["serverStdout"]["data"] != shared.SERVER_STDOUT_CAPS_SEQUENCE or not known_identity or trace_result.get("traceTruncated") is True or failure_marker or not guest_stderr_ok or any(value.get("truncated", False) for value in values.values()) or (server_process is None or server_process.returncode != 0) or forced_cleanup is not None or ownership_error:
        status = "UNKNOWN"
    summary = {
        "schema": 1, "runId": run, "status": status, "output": str(out), "events": rows,
        "serverExitCode": server_process.returncode if server_process else None, "contextExitCode": context_process.returncode if context_process else None,
        "hostServerAdbPid": server_process.pid if server_process else None, "hostContextAdbPid": context_process.pid if context_process else None,
        "serverIdentity": identity, "timedOut": timed_out, "ownershipError": ownership_error, "forcedCleanup": forced_cleanup,
        "remainingOwnProcesses": remaining, "completePipes": complete,
        "serverStdoutBytes": values["serverStdout"]["total"], "serverStderrBytes": values["serverStderr"]["total"], "contextStdoutBytes": values["contextStdout"]["total"], "contextStderrBytes": values["contextStderr"]["total"],
        "serverContextCreatedWitness": values["serverStdout"]["data"] == shared.SERVER_STDOUT_CAPS_SEQUENCE,
        "optionalShaderCacheDisabled": optional_cache_warning,
    }
    if guest_stderr_observation is not None:
        summary["guestStderrObservation"] = guest_stderr_observation
    if legacy:
        summary.update({
            "eglContextVerified": status == pass_status, "virpipeVerified": status == pass_status,
            "hardwareRenderingVerified": False, "triangleVerified": False, "presentationVerified": False,
            "d3dVerified": False, "wowVerified": False,
        })
        summary["boundary"] = boundary
    else:
        summary.pop("serverContextCreatedWitness")
        summary["nativeExitCode"] = summary.pop("contextExitCode")
        summary["hostNativeAdbPid"] = summary.pop("hostContextAdbPid")
        summary["nativeStdoutBytes"] = summary.pop("contextStdoutBytes")
        summary["nativeStderrBytes"] = summary.pop("contextStderrBytes")
        summary.update({
            "passStatus": pass_status,
            "hardwareRenderingVerified": False, "triangleVerified": False,
            "presentationVerified": False, "d3dVerified": False, "wowVerified": False,
        })
        if pass_status == "PROFILE_MATRIX_MEASURED":
            summary.update({
                "profileMatrixMeasured": status == pass_status,
                "serverProfileMatrixServerWitness": values["serverStdout"]["data"] == shared.SERVER_STDOUT_CAPS_SEQUENCE,
            })
        summary.update({
            "actualContextStatus": actual_context_status,
            "contextQualified": False,
            "parsedNegotiationRows": trace_result.get("parsedRows", []),
            "hasCapRequests": trace_result.get("hasCapRequests", False),
            "capRequestCount": trace_result.get("capRequestCount", 0),
            "protocolMetadata": trace_result.get("protocolMetadata"),
            "captureLoggingControl": True,
        })
        summary["boundary"] = boundary
    shared._write_exclusive(out / "summary.json", (json.dumps(summary, indent=2) + "\n").encode())
    print(json.dumps(summary, indent=2))
    return 0 if status == pass_status else 1


if __name__ == "__main__":
    raise SystemExit(main())
