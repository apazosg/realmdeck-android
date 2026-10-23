#!/usr/bin/env python3
"""Qualify only the fixed virgl test-server ``--help`` loader boundary.

This is deliberately a host runner.  It does not start an APK, Wine, the WoW
client, or the server's normal protocol mode.  The only device child command
is::

    /system/bin/linker64 <private-run>/bin/virgl_test_server --help

The payload is pinned by the reviewed candidate manifest.  Files are copied
one at a time through an exclusive public staging directory and then into a
new run directory owned by the debug lab package.  Private files are left in
place as evidence; only the worker's staging files are removed.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shlex
import stat
import subprocess
import threading
import uuid
from pathlib import Path
from typing import Any

ADB = "/home/adrian/android-sdk/platform-tools/adb"
SERIAL_ENV_VAR = "WOWFOREVER_ADB_SERIAL"
SERIAL = os.environ.get(SERIAL_ENV_VAR, "")


def require_serial() -> str:
    if not SERIAL:
        raise RuntimeError("WOWFOREVER_ADB_SERIAL is not set (explicit device selection required)")
    return SERIAL
PACKAGE = "com.wowforever.arm64probe"
UID = 10330
VERSION_CODE = 41
MANIFEST = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/controls/virgl-audit/"
    "server_candidate-v2/candidate-manifest.json"
)
MANIFEST_SHA256 = "5f40682f034050d5e72d98199b41a5da9444aa2770ee5732bb4a077de7e17174"
SCHEMA = "wowforever.virgl-server-candidate"
SCHEMA_VERSION = 1
RUN_PREFIX = "VTEST-"
PRIVATE_PREFIX = "files/forever/controls/vtest-loader"
STAGE_PREFIX = "/data/local/tmp/wowforever-vtest-"
STDOUT_LIMIT = 64 * 1024
STDERR_LIMIT = 128 * 1024
MANIFEST_LIMIT = 128 * 1024
PAYLOAD_LIMIT = 4 * 1024 * 1024
PAYLOAD_TOTAL_LIMIT = 8 * 1024 * 1024
PAYLOAD_COUNT = 10
PAYLOAD_TOTAL = 3_917_232
DEADLINE_SECONDS = 20
EXPECTED_ALIASES = [
    {"name": "libEGL.so.1", "target": "/system/lib64/libEGL.so"},
    {"name": "libGLESv2.so.2", "target": "/system/lib64/libGLESv2.so"},
]
EXPECTED_DESTINATIONS = {
    "bin/virgl_test_server",
    "lib/libX11.so",
    "lib/libXau.so",
    "lib/libXdmcp.so",
    "lib/libandroid-support.so",
    "lib/libdrm.so",
    "lib/libepoxy.so",
    "lib/libgbm.so",
    "lib/libvirglrenderer.so",
    "lib/libxcb.so",
}
FORBIDDEN_GL_ALIASES = {"libEGL.so.1", "libGLESv2.so.2"}
HEX64 = re.compile(r"^[0-9a-f]{64}$")


def unique_json_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON field: {key}")
        result[key] = value
    return result


def _regular_nosymlink(path: Path) -> os.stat_result:
    if path.is_symlink():
        raise ValueError(f"symlink is not an approved regular file: {path}")
    info = path.stat()
    if not stat.S_ISREG(info.st_mode):
        raise ValueError(f"not a regular file: {path}")
    return info


def _elf64_aarch64_dyn(data: bytes, label: str, require_linker_interp: bool = False) -> None:
    if len(data) < 20 or data[:4] != b"\x7fELF" or data[4:6] != b"\x02\x01":
        raise ValueError(f"{label} is not ELF64 little-endian")
    if int.from_bytes(data[16:18], "little") != 3:
        raise ValueError(f"{label} is not ET_DYN")
    if int.from_bytes(data[18:20], "little") != 183:
        raise ValueError(f"{label} is not AArch64")
    if require_linker_interp:
        phoff = int.from_bytes(data[32:40], "little")
        phentsize = int.from_bytes(data[54:56], "little")
        phnum = int.from_bytes(data[56:58], "little")
        interpreter = None
        for index in range(phnum):
            header = data[phoff + index * phentsize:phoff + (index + 1) * phentsize]
            if len(header) < 56:
                raise ValueError(f"{label} has a truncated program header")
            if int.from_bytes(header[:4], "little") == 3:
                offset = int.from_bytes(header[8:16], "little")
                size = int.from_bytes(header[32:40], "little")
                interpreter = data[offset:offset + size].split(b"\0", 1)[0]
                break
        if interpreter != b"/system/bin/linker64":
            raise ValueError(f"{label} does not use /system/bin/linker64")


def validate_manifest_document(document: dict[str, Any], candidate_root: Path) -> list[dict[str, Any]]:
    """Validate the bounded, closed payload and return host materialized files."""
    if document.get("schema") != SCHEMA or document.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError("unexpected candidate manifest schema")
    if document.get("executedPayloads") is not False or document.get("ready") is not False:
        raise ValueError("candidate must remain static and not ready for overclaim")
    if document.get("deviceAliases") != EXPECTED_ALIASES:
        raise ValueError("unexpected device aliases")
    if document.get("protocol", {}).get("status") != "UNVERIFIED":
        raise ValueError("protocol status is not unverified")

    materialized = document.get("materializedFiles")
    if not isinstance(materialized, list) or len(materialized) != PAYLOAD_COUNT:
        raise ValueError("unexpected materialized file count")
    destinations: list[str] = []
    files: list[dict[str, Any]] = []
    total = 0
    for entry in materialized:
        if not isinstance(entry, dict):
            raise ValueError("malformed materialized file")
        destination = entry.get("destination")
        if not isinstance(destination, str) or destination in destinations:
            raise ValueError("duplicate or invalid destination")
        if destination not in EXPECTED_DESTINATIONS:
            raise ValueError("unexpected payload destination")
        parts = destination.split("/")
        if len(parts) != 2 or parts[0] not in {"bin", "lib"} or not parts[1]:
            raise ValueError("unsafe payload destination")
        if parts[1] in FORBIDDEN_GL_ALIASES or "libEGL" in parts[1] or "libGLES" in parts[1]:
            raise ValueError("GLVND alias cannot be a regular payload")
        size = entry.get("bytes")
        digest = entry.get("sha256")
        mode = entry.get("mode")
        if type(size) is not int or size <= 0 or size > PAYLOAD_LIMIT:
            raise ValueError("invalid payload size")
        if not isinstance(digest, str) or not HEX64.fullmatch(digest):
            raise ValueError("invalid payload hash")
        if mode not in {"0600", "0700"}:
            raise ValueError("invalid payload mode")
        if destination == "bin/virgl_test_server" and mode != "0700":
            raise ValueError("server mode must be 0700")
        if destination != "bin/virgl_test_server" and mode != "0600":
            raise ValueError("library mode must be 0600")
        path = candidate_root / destination
        info = _regular_nosymlink(path)
        if info.st_size != size or stat.S_IMODE(info.st_mode) != int(mode, 8):
            raise ValueError(f"payload metadata mismatch: {destination}")
        data = path.read_bytes()
        if len(data) != size or hashlib.sha256(data).hexdigest() != digest:
            raise ValueError(f"payload hash mismatch: {destination}")
        _elf64_aarch64_dyn(data, destination, destination == "bin/virgl_test_server")
        destinations.append(destination)
        total += size
        files.append({"destination": destination, "path": path, "sha256": digest, "bytes": size,
                      "mode": mode})
    if total > PAYLOAD_TOTAL_LIMIT or set(destinations) != EXPECTED_DESTINATIONS or total != PAYLOAD_TOTAL:
        raise ValueError("candidate closure is not the approved ten-file payload")
    return files


def load_candidate() -> tuple[dict[str, Any], list[dict[str, Any]], bytes]:
    """Read and pin the manifest before any transfer can be attempted."""
    _regular_nosymlink(MANIFEST)
    if MANIFEST.stat().st_size > MANIFEST_LIMIT:
        raise ValueError("candidate manifest exceeds 128 KiB")
    raw = MANIFEST.read_bytes()
    if len(raw) > MANIFEST_LIMIT or hashlib.sha256(raw).hexdigest() != MANIFEST_SHA256:
        raise ValueError("candidate manifest hash mismatch")
    document = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_json_pairs)
    if not isinstance(document, dict):
        raise ValueError("candidate manifest is not an object")
    files = validate_manifest_document(document, MANIFEST.parent)
    return document, files, raw


def adb(*args: str, timeout: int = 15) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        [ADB, "-s", require_serial(), *args], capture_output=True, timeout=timeout, check=False
    )


def require_adb(result: subprocess.CompletedProcess[bytes], stage: str, limit: int = 128 * 1024) -> bytes:
    if len(result.stdout) > limit or len(result.stderr) > limit:
        raise RuntimeError(f"{stage} output exceeded bound")
    if result.returncode:
        raise RuntimeError(f"{stage} failed: {result.stderr[:2048].decode(errors='replace')}")
    return result.stdout


def own_processes(uid: int = UID) -> list[dict[str, str]]:
    raw = require_adb(adb("shell", "ps", "-A", "-o", "UID,PID,NAME"), "own-UID ps")
    result: list[dict[str, str]] = []
    aliases = {str(uid), f"u0_a{uid - 10000}"}
    for line in raw.decode(errors="replace").splitlines()[1:]:
        fields = line.split(None, 2)
        if len(fields) == 3 and fields[0] in aliases:
            result.append({"pid": fields[1], "name": fields[2]})
    return result


def validate_remote_status(features: bytes, result: subprocess.CompletedProcess[bytes]) -> dict[str, Any]:
    if "shell_v2" not in re.split(r"[,\s]+", features.decode("ascii", "strict").strip()):
        raise RuntimeError("ADB shell_v2 remote exit status is unavailable")
    if result.returncode != 37 or result.stdout != b"VTEST_STATUS_SENTINEL" or result.stderr:
        raise RuntimeError("remote shell exit/output sentinel did not match; no payload executed")
    return {"shellV2": True, "sentinelExitCode": 37, "sentinelOutputMatched": True}


def remote_status_preflight() -> dict[str, Any]:
    features = require_adb(adb("features"), "ADB feature negotiation", limit=4096)
    sentinel = "sh -c " + shlex.quote("printf VTEST_STATUS_SENTINEL; exit 37")
    result = adb("shell", "-T", sentinel)
    return validate_remote_status(features, result)


def _package_preflight() -> tuple[int, str, str]:
    if require_adb(adb("get-state"), "device authorization").strip() != b"device":
        raise RuntimeError("device is not authorized")
    package_dump = require_adb(adb("exec-out", "dumpsys", "package", PACKAGE), "package metadata")
    text = package_dump.decode(errors="replace")
    if not re.search(rf"\bversionCode={VERSION_CODE}(?:\D|$)", text):
        raise RuntimeError("unexpected package version")
    uid_raw = require_adb(adb("exec-out", "run-as", PACKAGE, "id", "-u"), "app UID")
    if not uid_raw.strip().isdigit() or int(uid_raw.strip()) != UID:
        raise RuntimeError("unexpected app UID")
    if own_processes(UID):
        raise RuntimeError("unexpected live application process")
    app_root = require_adb(adb("exec-out", "run-as", PACKAGE, "pwd"), "app data root").decode().strip()
    accepted_roots = {f"/data/user/0/{PACKAGE}", f"/data/data/{PACKAGE}"}
    if app_root not in accepted_roots:
        raise RuntimeError("unexpected private app root")
    selinux = require_adb(adb("exec-out", "run-as", PACKAGE, "id", "-Z"), "SELinux run-as context")
    context = selinux.decode(errors="replace").strip()
    if not context or "\n" in context:
        raise RuntimeError("missing SELinux run-as context")
    return UID, app_root, context


def parse_system_alias_evidence(raw: bytes) -> list[dict[str, Any]]:
    """Parse read-only target evidence emitted by the fixed device probe."""
    if not raw or len(raw) > 16 * 1024:
        raise ValueError("missing or oversized system alias evidence")
    result: list[dict[str, Any]] = []
    expected = {item["target"]: item["name"] for item in EXPECTED_ALIASES}
    for line in raw.decode("ascii", "strict").splitlines():
        fields = line.split("\t")
        if len(fields) != 7 or fields[0] != "VTEST_SYSTEM":
            raise ValueError("malformed system alias evidence")
        _, target, canonical, kind, size, magic, digest_and_path = fields
        if target not in expected or any(row["target"] == target for row in result):
            raise ValueError("unexpected or duplicate system alias target")
        if not (canonical.startswith("/system/lib64/") or canonical.startswith("/apex/")):
            raise ValueError("system alias canonical path is outside known prefixes")
        if kind != "regular file" or not size.isdigit() or int(size) <= 4:
            raise ValueError("system alias target is not a regular file")
        if magic.lower().replace(" ", "") != "7f454c46":
            raise ValueError("system alias target is not ELF")
        digest = digest_and_path.split(None, 1)[0]
        if not HEX64.fullmatch(digest):
            raise ValueError("invalid system alias hash")
        result.append({"name": expected[target], "target": target, "canonical": canonical,
                       "bytes": int(size), "sha256": digest})
    if {row["target"] for row in result} != set(expected):
        raise ValueError("incomplete system alias evidence")
    return result


def system_alias_evidence() -> list[dict[str, Any]]:
    script = (
        "set -e; "
        "for target in /system/lib64/libEGL.so /system/lib64/libGLESv2.so; do "
        "canonical=$(readlink -f \"$target\"); "
        "case \"$canonical\" in /system/lib64/*|/apex/*) ;; *) exit 31;; esac; "
        "test -f \"$canonical\"; "
        "kind=$(stat -c '%F' \"$canonical\"); size=$(stat -c '%s' \"$canonical\"); "
        "magic=$(od -An -tx1 -N4 \"$canonical\" | tr -d '\\n'); "
        "digest=$(sha256sum \"$canonical\"); digest=${digest%% *}; "
        "printf 'VTEST_SYSTEM\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s %s\\n' "
        "\"$target\" \"$canonical\" \"$kind\" \"$size\" \"$magic\" \"$digest\" \"$canonical\"; "
        "done"
    )
    raw = require_adb(adb("exec-out", "run-as", PACKAGE, "sh", "-c", script), "system alias evidence")
    return parse_system_alias_evidence(raw)


def _q(value: str) -> str:
    return shlex.quote(value)


def _private_copy_script(stage: str, private_root: str, files: list[dict[str, Any]]) -> str:
    directories = [
        "files",
        "files/forever",
        "files/forever/controls",
        PRIVATE_PREFIX,
        f"{PRIVATE_PREFIX}/{private_root.rsplit('/', 1)[-1]}",
        f"{PRIVATE_PREFIX}/{private_root.rsplit('/', 1)[-1]}/bin",
        f"{PRIVATE_PREFIX}/{private_root.rsplit('/', 1)[-1]}/lib",
    ]
    commands = ["umask 077", "set -e", "set -C"]
    for directory in directories[:4]:
        qdir = _q(directory)
        commands.append(f"test ! -L {qdir} && (test -d {qdir} || mkdir -m 700 {qdir})")
    run_dir = directories[4]
    commands.append(f"test ! -e {_q(run_dir)} && test ! -L {_q(run_dir)}")
    commands.append(f"mkdir -m 700 {_q(run_dir)}")
    for directory in directories[5:]:
        qdir = _q(directory)
        commands.append(f"test ! -L {qdir} && (test -d {qdir} || mkdir -m 700 {qdir})")
    for item in files:
        destination = f"{run_dir}/{item['destination']}"
        staged = f"{stage}/{Path(item['destination']).name}"
        commands.append(f"test ! -e {_q(destination)} && test ! -L {_q(destination)}")
        commands.append(f"cat {_q(staged)} > {_q(destination)}")
        commands.append(f"chmod {item['mode']} {_q(destination)}")
    for alias in EXPECTED_ALIASES:
        destination = f"{run_dir}/lib/{alias['name']}"
        commands.append(f"test ! -e {_q(destination)} && test ! -L {_q(destination)}")
        commands.append(f"ln -s {_q(alias['target'])} {_q(destination)}")
    commands.append("printf VTEST_UPLOAD_OK")
    return "; ".join(commands)


def copy_payload(files: list[dict[str, Any]], run: str) -> tuple[str, dict[str, Any]]:
    """Transfer each fixed file and return the private relative run path and stage."""
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", run):
        raise ValueError("invalid private run directory")
    stage = STAGE_PREFIX + uuid.uuid4().hex
    stage_created = False
    names = [Path(item["destination"]).name for item in files]
    cleanup_failures: list[str] = []
    try:
        require_adb(adb("shell", "test", "-d", "/data/local/tmp"), "staging parent")
        result = adb("shell", "mkdir", "-m", "755", stage)
        require_adb(result, "exclusive staging directory")
        stage_created = True
        for item, name in zip(files, names, strict=True):
            require_adb(adb("push", str(item["path"]), f"{stage}/{name}", timeout=30), "sync upload")
            require_adb(adb("shell", "chmod", "0644", f"{stage}/{name}"), "staging mode")
        relative = f"{PRIVATE_PREFIX}/{run}"
        script = _private_copy_script(stage, relative, files)
        uploaded = adb("exec-out", "run-as", PACKAGE, "sh", "-c", script, timeout=30)
        if uploaded.returncode or uploaded.stdout != b"VTEST_UPLOAD_OK" or uploaded.stderr:
            raise RuntimeError("private upload sentinel was not exact")
        for item in files:
            destination = f"{relative}/{item['destination']}"
            hashed = require_adb(
                adb("exec-out", "run-as", PACKAGE, "sha256sum", destination),
                "device artifact hash",
                limit=4096,
            ).decode(errors="replace").split()
            if not hashed or hashed[0] != item["sha256"]:
                raise RuntimeError(f"device artifact pin mismatch: {item['destination']}")
            actual_mode = require_adb(adb("exec-out", "run-as", PACKAGE, "stat", "-c", "%a", destination),
                                      "device artifact mode", limit=4096).strip()
            if actual_mode != item["mode"].lstrip("0").encode():
                raise RuntimeError(f"device artifact mode mismatch: {item['destination']}")
    except Exception:
        raise
    finally:
        if stage_created:
            for name in names:
                result = adb("shell", "rm", "-f", f"{stage}/{name}")
                if result.returncode or result.stdout or result.stderr:
                    cleanup_failures.append(name)
            result = adb("shell", "rmdir", stage)
            if result.returncode or result.stdout or result.stderr:
                cleanup_failures.append("staging-directory")
    cleanup = {"path": stage, "stagingClean": not cleanup_failures, "failures": cleanup_failures}
    if cleanup_failures:
        raise RuntimeError("owned staging cleanup failed; no payload executed: " + json.dumps(cleanup))
    return relative, cleanup


def _write_exclusive(path: Path, data: bytes, mode: int = 0o600) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    fd = os.open(path, flags, mode)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
    except Exception:
        try:
            os.close(fd)
        except OSError:
            pass
        raise


def validate_help_status(stdout: bytes, stderr: bytes, exit_code: int | None,
                        complete: bool, stdout_truncated: bool = False,
                        stderr_truncated: bool = False) -> str:
    """Classify only a complete successful native help response."""
    if not complete or stdout_truncated or stderr_truncated:
        return "UNKNOWN"
    content = (stdout + b"\n" + stderr).decode("utf-8", "replace")
    if not content.strip():
        return "UNKNOWN"
    lowered = content.lower()
    required = ("--use-gles", "--socket-path", "usage:", "virgl_test_server")
    # This pinned server rejects --help but prints its own usage and returns 1.
    # That proves execution reached its parser, NOT successful help, EGL or vtest.
    if exit_code == 1 and stderr == b"virgl_test_server: unrecognized option `--help'\n" and all(token in lowered for token in required):
        return "LOADER_USAGE_OBSERVED"
    if exit_code != 0:
        return "LOAD_FAILED"
    return "LOADER_HELP_PASS" if all(token in lowered for token in required) else "UNKNOWN"


def _drain(name: str, pipe: Any, limit: int, result: dict[str, Any]) -> None:
    data = bytearray()
    total = 0
    with pipe:
        while True:
            chunk = pipe.read(8192)
            if not chunk:
                break
            total += len(chunk)
            if len(data) < limit:
                data.extend(chunk[: limit - len(data)])
    result[name] = {"data": bytes(data), "total": total, "truncated": total > limit}


def _external_parent(value: Path) -> Path:
    parent = value.absolute()
    repo = Path(__file__).resolve().parents[3]
    if not parent.is_dir() or parent.is_symlink() or parent.resolve().is_relative_to(repo):
        raise ValueError("evidence parent must exist outside the repo without a symlink")
    for component in (parent, *parent.parents):
        if component.is_symlink():
            raise ValueError("symlink in evidence parent")
    return parent


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="existing external evidence parent")
    args = parser.parse_args(argv)
    parent = _external_parent(args.output)
    document, files, manifest_raw = load_candidate()
    shell_status = remote_status_preflight()
    uid, app_root, selinux_context = _package_preflight()
    system_libs = system_alias_evidence()
    run = RUN_PREFIX + uuid.uuid4().hex
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", run):
        raise RuntimeError("invalid generated RUN")
    out = parent / ("native-" + run)
    out.mkdir(mode=0o700)
    private_relative, staging = copy_payload(files, run)
    private_root = f"{app_root}/{private_relative}"
    server = f"{private_root}/bin/virgl_test_server"
    command = [
        ADB, "-s", SERIAL, "shell", "-T", "run-as", PACKAGE, "/system/bin/env", "-i",
        "PATH=/system/bin:/system/xbin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
        f"LD_LIBRARY_PATH={private_root}/lib:/system/lib64", f"TMPDIR={private_root}",
        "/system/bin/linker64", server, "--help",
    ]
    capture = {
        "schema": 1,
        "runId": run,
        "serial": SERIAL,
        "package": PACKAGE,
        "uid": uid,
        "versionCode": VERSION_CODE,
        "selinuxContext": selinux_context,
        "manifestSha256": hashlib.sha256(manifest_raw).hexdigest(),
        "payloadFiles": [{"destination": item["destination"], "bytes": item["bytes"],
                          "sha256": item["sha256"]} for item in files],
        "systemLibraries": system_libs,
        "command": command,
        "nativeEglDlopenAliases": "UNVERIFIED",
        "protocolVerified": False,
        "presentationVerified": False,
        "d3dVerified": False,
        "wowVerified": False,
        "quiescenceClaimed": False,
        "signalContainmentClaimed": False,
        "androidPid": "unknown",
        "requestedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "shellStatusPreflight": shell_status,
        "stagingCleanup": staging,
        "executionStarted": False,
    }
    # Persist the full command/pins before exec. A failed evidence write must not
    # leave a payload running without an owner or its deadline.
    _write_exclusive(out / "capture.json", (json.dumps(capture, indent=2) + "\n").encode())
    process = subprocess.Popen(command, stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    results: dict[str, Any] = {}
    threads = [
        threading.Thread(target=_drain, args=("stdout", process.stdout, STDOUT_LIMIT, results), daemon=True),
        threading.Thread(target=_drain, args=("stderr", process.stderr, STDERR_LIMIT, results), daemon=True),
    ]
    for thread in threads:
        thread.start()
    timed_out = False
    timeout_stop: dict[str, Any] | None = None
    try:
        process.wait(timeout=DEADLINE_SECONDS)
    except subprocess.TimeoutExpired:
        timed_out = True
        stopped = adb("shell", "am", "force-stop", PACKAGE)
        timeout_stop = {"action": "owned lab package force-stop after loader timeout",
                        "returnCode": stopped.returncode}
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)
    for thread in threads:
        thread.join(timeout=3)
    for name in ("stdout", "stderr"):
        _write_exclusive(out / f"{name}.txt", results.get(name, {"data": b""})["data"])
    complete = not timed_out and all(not thread.is_alive() for thread in threads) and set(results) == {"stdout", "stderr"}
    stdout_result = results.get("stdout", {"data": b"", "truncated": False})
    stderr_result = results.get("stderr", {"data": b"", "truncated": False})
    status = validate_help_status(stdout_result["data"], stderr_result["data"], process.returncode,
                                 complete, stdout_result.get("truncated", False),
                                 stderr_result.get("truncated", False))
    after = own_processes(UID)
    summary = {
        "schema": 1,
        "runId": run,
        "status": status,
        "timedOut": timed_out,
        "hostAdbExitCode": process.returncode,
        "hostAdbClientPid": process.pid,
        "androidPid": "unknown",
        "stdoutBytes": stdout_result.get("total", 0),
        "stderrBytes": stderr_result.get("total", 0),
        "stdoutTruncated": stdout_result.get("truncated", False),
        "stderrTruncated": stderr_result.get("truncated", False),
        "completePipes": complete,
        "timeoutStop": timeout_stop,
        "remainingOwnProcesses": after,
        "nativeEglDlopenAliases": "UNVERIFIED",
        "protocolVerified": False,
        "presentationVerified": False,
        "d3dVerified": False,
        "wowVerified": False,
        "quiescenceClaimed": False,
        "signalContainmentClaimed": False,
        "boundary": "owned run-as UID child; not hardware, protocol, Wine, WoW, or presentation qualification",
        "output": str(out),
    }
    _write_exclusive(out / "summary.json", (json.dumps(summary, indent=2) + "\n").encode())
    print(json.dumps(summary, indent=2))
    return 0 if status == "LOADER_HELP_PASS" and not after else 1


if __name__ == "__main__":
    raise SystemExit(main())
