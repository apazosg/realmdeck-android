#!/usr/bin/env python3
"""Measure only private libepoxy -> Android DEFAULT EGL in an owned UID child.

The candidate/server is not executed. No Wine, socket, resource, presentation,
driver replacement or APK update occurs. Reuses the fixed ten-file loader pins.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import shlex
import subprocess
import threading
import time
import uuid
from pathlib import Path
from typing import Any

import run_loader_usb as loader

PROBE = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/egl-epoxy/EglEpoxyProbe")
PROBE_BYTES = 21_864
PROBE_SHA256 = "90e4c734cd8a862ee65a3fcb96753260ec631a8cafbf9a28062749333b2ed7fb"
PROBE_NAME = "EglEpoxyProbe"
SCHEMA = "wowforever.android-egl-epoxy/version1"
EVENTS = ("begin", "library", "client_extensions", "egl", "context", "caps", "cleanup", "end")
DEADLINE_SECONDS = 25
STDOUT_LIMIT = 64 * 1024
STDERR_LIMIT = 128 * 1024
BROADER = ("graphicsVerified", "hardwareRenderingVerified", "vtestVerified",
           "presentationVerified", "d3dVerified", "wowVerified")
BASE_KEYS = {"schema", "runId", "event", "timeNs", "pid", "uid", "pgid", "sid"}
EVENT_FIELDS = {
    "begin": {"status"},
    "library": {"status", "scopeValid", "slotAddressOwner", "slotPath", "canonicalPath", "missingSymbol", "lookupError"},
    "client_extensions": {"status", "extensions", "extensionScanValid", "hasEGL_EXT_platform_base",
                          "hasEGL_KHR_create_context", "hasEGL_KHR_surfaceless_context", "extensionScanBytes",
                          "extensionsTruncated", "eglError"},
    "egl": {"status", "displayValid", "initialized", "major", "minor", "vendor", "version", "clientApis",
            "extensions", "extensionScanValid", "extensionScanBytes", "extensionsTruncated", "mandatoryEglErrorCount",
            "hasEGL_EXT_platform_base", "hasEGL_KHR_create_context", "hasEGL_KHR_surfaceless_context", "eglError"},
    "context": {"status", "apiBound", "configChosen", "configCount", "contextCreated", "current", "surface", "eglError"},
    "caps": {"status", "glVendor", "glRenderer", "glVersion", "actualImplementationPath", "actualImplementationPathKnown",
             "implementationPathKind", "glError"},
    "cleanup": {"status", "cleanupFailures", "cleanupStatus"},
    "end": {"status", "eglContextVerified", *BROADER, "eglSuccess", "cleanup0", "mandatoryEglErrorCount", "nativeExit"},
}
BOOL_FIELDS = {"scopeValid", "slotAddressOwner", "extensionScanValid", "extensionsTruncated", "displayValid",
               "initialized", "apiBound", "configChosen", "contextCreated", "current", "eglContextVerified",
               "eglSuccess", "cleanup0", *BROADER}
OPTIONAL_BOOL = {"hasEGL_EXT_platform_base", "hasEGL_KHR_create_context", "hasEGL_KHR_surfaceless_context",
                 "actualImplementationPathKnown"}
INT_RANGES = {"major": (0, 16), "minor": (0, 32), "extensionScanBytes": (0, 16_384),
              "mandatoryEglErrorCount": (0, 64), "configCount": (0, 1), "cleanupFailures": (0, 16),
              "eglError": (0, 65_535), "glError": (0, 65_535), "nativeExit": (0, 1)}


def valid_event_types(row: dict[str, Any]) -> bool:
    if set(row) != BASE_KEYS | EVENT_FIELDS[row["event"]]:
        return False
    for key, value in row.items():
        if key in BOOL_FIELDS and type(value) is not bool:
            return False
        if key in OPTIONAL_BOOL and value is not None and type(value) is not bool:
            return False
        if key in INT_RANGES:
            if key == "eglError" and row["event"] == "client_extensions" and value is None:
                continue
            low, high = INT_RANGES[key]
            if type(value) is not int or not low <= value <= high:
                return False
        if key in {"pid", "uid", "pgid", "sid", "timeNs"} and (type(value) is not int or value <= 0):
            return False
        if key in {"slotPath", "canonicalPath", "missingSymbol", "lookupError", "extensions", "vendor", "version",
                   "clientApis", "glVendor", "glRenderer", "glVersion", "actualImplementationPath"}:
            limit = 16_384 if key == "extensions" and row["event"] == "egl" else (
                4096 if key in {"slotPath", "canonicalPath", "actualImplementationPath"} else 512)
            if value is not None and (not isinstance(value, str) or len(value) > limit):
                return False
        if key in {"status", "cleanupStatus", "surface", "implementationPathKind"} and (
                not isinstance(value, str) or not 0 < len(value) <= 64):
            return False
    return True


def probe_file() -> dict[str, Any]:
    info = loader._regular_nosymlink(PROBE)
    if info.st_size != PROBE_BYTES:
        raise ValueError("unexpected fixed epoxy probe size")
    raw = PROBE.read_bytes()
    if len(raw) != PROBE_BYTES or hashlib.sha256(raw).hexdigest() != PROBE_SHA256:
        raise ValueError("fixed epoxy probe hash mismatch")
    loader._elf64_aarch64_dyn(raw, PROBE_NAME, True)
    return {"destination": "bin/" + PROBE_NAME, "path": PROBE,
            "bytes": PROBE_BYTES, "sha256": PROBE_SHA256, "mode": "0700"}


def native_command(private_root: str, run: str) -> list[str]:
    if not re.fullmatch(r"VTEST-[0-9a-f]{32}", run):
        raise ValueError("invalid fixed RUN")
    roots = [f"{root}/files/forever/controls/vtest-loader/{run}"
             for root in (f"/data/user/0/{loader.PACKAGE}", f"/data/data/{loader.PACKAGE}")]
    if private_root not in roots:
        raise ValueError("unapproved private probe root")
    inner = (
        f"cd {shlex.quote(private_root)} && exec /system/bin/env -i "
        "PATH=/system/bin:/system/xbin ANDROID_ROOT=/system ANDROID_DATA=/data "
        f"{shlex.quote('LD_LIBRARY_PATH=' + private_root + '/lib:/system/lib64')} "
        f"{shlex.quote('TMPDIR=' + private_root)} /system/bin/linker64 "
        f"{shlex.quote(private_root + '/bin/' + PROBE_NAME)} --run-id {run}"
    )
    # One quoted inner command: cwd and exec both remain under run-as, not shell UID.
    return [loader.ADB, "-s", loader.SERIAL, "shell", "-T", "run-as", loader.PACKAGE,
            "sh", "-c", shlex.quote(inner)]


def validate_output(raw: bytes, run: str, exit_code: int | None, complete: bool,
                    truncated: bool = False) -> tuple[str, list[dict[str, Any]]]:
    if not complete or truncated or not raw or len(raw) > STDOUT_LIMIT or not raw.endswith(b"\n"):
        return "UNKNOWN", []
    lines = raw.splitlines()
    if len(lines) != len(EVENTS) or any(len(line) >= 24_576 for line in lines):
        return "UNKNOWN", []
    try:
        rows = [json.loads(line.decode("utf-8", "strict"), object_pairs_hook=loader.unique_json_pairs)
                for line in lines]
        if any(not isinstance(row, dict) for row in rows):
            return "UNKNOWN", []
        if tuple(row.get("event") for row in rows) != EVENTS:
            return "UNKNOWN", rows
        if any(not valid_event_types(row) for row in rows):
            return "UNKNOWN", rows
        first = rows[0]
        if type(first.get("pid")) is not int or first["pid"] <= 0 or first.get("status") != "STARTED":
            return "UNKNOWN", rows
        times = []
        for row in rows:
            if row.get("schema") != SCHEMA or row.get("runId") != run:
                return "UNKNOWN", rows
            if type(row.get("uid")) is not int or row["uid"] != loader.UID or row.get("pid") != first["pid"]:
                return "UNKNOWN", rows
            if type(row.get("timeNs")) is not int or row["timeNs"] <= 0:
                return "UNKNOWN", rows
            if any(row.get(key) != first.get(key) for key in ("pgid", "sid")):
                return "UNKNOWN", rows
            times.append(row["timeNs"])
        if times != sorted(times):
            return "UNKNOWN", rows
        end = rows[-1]
        if any(end.get(key) is not False for key in BROADER):
            return "UNKNOWN", rows
        if type(end.get("nativeExit")) is not int or end["nativeExit"] != exit_code:
            return "UNKNOWN", rows
        if end.get("status") != "EPOXY_CONTEXT_PASS":
            return ("CONTEXT_FAILED" if end.get("status") == "CONTEXT_FAILED" else "UNKNOWN"), rows
        lib, egl, ctx, caps, clean = rows[1], rows[3], rows[4], rows[5], rows[6]
        allowed_libs = {
            f"{root}/files/forever/controls/vtest-loader/{run}/lib/libepoxy.so"
            for root in (f"/data/user/0/{loader.PACKAGE}", f"/data/data/{loader.PACKAGE}")
        }
        good = (
            exit_code == 0 and lib.get("status") == "LOADED" and lib.get("scopeValid") is True
            and lib.get("slotAddressOwner") is True and lib.get("canonicalPath") in allowed_libs
            and lib.get("missingSymbol") is None and lib.get("lookupError") is None
            and egl.get("status") == "MEASURED" and egl.get("displayValid") is True
            and egl.get("initialized") is True and egl.get("extensionScanValid") is True
            and egl.get("eglError") == 0x3000 and egl.get("mandatoryEglErrorCount") == 0
            and ctx.get("status") == "CREATED" and ctx.get("apiBound") is True
            and ctx.get("configChosen") is True and ctx.get("configCount") == 1
            and ctx.get("contextCreated") is True and ctx.get("current") is True
            and ctx.get("surface") == "EGL_NO_SURFACE" and ctx.get("eglError") == 0x3000
            and caps.get("status") == "MEASURED" and caps.get("glError") == 0
            and all(isinstance(caps.get(key), str) and 0 < len(caps[key]) <= 512
                    for key in ("glVendor", "glRenderer", "glVersion"))
            and clean.get("status") == "CLEAN" and clean.get("cleanupStatus") == "CLEAN"
            and clean.get("cleanupFailures") == 0 and end.get("cleanup0") is True
            and end.get("eglSuccess") is True and end.get("mandatoryEglErrorCount") == 0
            and end.get("eglContextVerified") is True
        )
        # Optional client extension query is evidence, never a default-display prerequisite.
        return ("EPOXY_CONTEXT_PASS" if good else "UNKNOWN"), rows
    except (UnicodeError, ValueError, KeyError, TypeError):
        return "UNKNOWN", []


def force_stop(timeout: float) -> dict[str, Any]:
    result = loader.adb("shell", "am", "force-stop", loader.PACKAGE, timeout=timeout)
    return {"action": "authorized fixed lab package force-stop after native timeout/error",
            "returnCode": result.returncode,
            "stdout": result.stdout[:2048].decode(errors="replace"),
            "stderr": result.stderr[:2048].decode(errors="replace")}


def owned_processes(timeout: float) -> list[dict[str, Any]]:
    raw = loader.require_adb(loader.adb("shell", "ps", "-A", "-o", "UID,PID,NAME", timeout=timeout),
                             "bounded own-UID post-run check")
    rows = []
    aliases = {str(loader.UID), f"u0_a{loader.UID - 10000}"}
    for line in raw.decode("utf-8", "replace").splitlines()[1:]:
        parts = line.split(None, 2)
        if len(parts) == 3 and parts[0] in aliases:
            rows.append({"pid": parts[1], "name": parts[2]})
    return rows


def cleanup_child(process: Any, threads: list[threading.Thread], must_stop: bool) -> dict[str, Any]:
    """Best-effort bounded cleanup never skips the held HOST handle on ADB error."""
    deadline = time.monotonic() + 10
    errors = []
    stopped = None
    remaining: list[dict[str, Any]] = [{"state": "UNKNOWN"}]

    def budget() -> float:
        left = deadline - time.monotonic()
        if left <= 0:
            raise TimeoutError("cleanup deadline expired")
        return min(left, 3)

    try:
        remaining = owned_processes(budget())
    except Exception as exc:
        errors.append("ownership check: " + str(exc)[:1024])
        must_stop = True
    try:
        must_stop = must_stop or bool(remaining) or process.poll() is None or any(t.is_alive() for t in threads)
        if must_stop:
            try:
                stopped = force_stop(budget())
                if stopped["returnCode"] or stopped["stdout"] or stopped["stderr"]:
                    errors.append("fixed-package force-stop did not complete cleanly")
            except Exception as exc:
                stopped = {"action": "fixed-package force-stop attempted", "status": "UNKNOWN"}
                errors.append("force-stop: " + str(exc)[:1024])
    finally:
        # Local handle operations happen even if the remote cleanup or its budget failed.
        try:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=1)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=1)
        except Exception as exc:
            errors.append("held host ADB handle: " + str(exc)[:1024])
        for thread in threads:
            try:
                thread.join(timeout=max(0, min(1, deadline - time.monotonic())))
            except Exception as exc:
                errors.append("drain: " + str(exc)[:1024])
        if any(thread.is_alive() for thread in threads):
            errors.append("output drain did not finish")
        if must_stop:
            try:
                remaining = owned_processes(budget())
            except Exception as exc:
                remaining = [{"state": "UNKNOWN"}]
                errors.append("final ownership check: " + str(exc)[:1024])
    return {"forcedCleanup": stopped, "remainingOwnProcesses": remaining, "errors": errors}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="existing external evidence parent")
    args = parser.parse_args(argv)
    parent = loader._external_parent(args.output)
    _, files, manifest_raw = loader.load_candidate()
    probe = probe_file()
    shell_status = loader.remote_status_preflight()
    uid, app_root, selinux = loader._package_preflight()
    aliases = loader.system_alias_evidence()
    run = "VTEST-" + uuid.uuid4().hex
    out = parent / ("native-EPOXY-" + run)
    out.mkdir(mode=0o700)
    relative, staging = loader.copy_payload([*files, probe], run)
    private_root = app_root + "/" + relative
    command = native_command(private_root, run)
    capture = {
        "schema": 1, "runId": run, "serial": loader.SERIAL, "package": loader.PACKAGE,
        "uid": uid, "selinuxContext": selinux, "versionCode": loader.VERSION_CODE,
        "candidateManifestSha256": hashlib.sha256(manifest_raw).hexdigest(),
        "probe": {key: probe[key] for key in ("destination", "bytes", "sha256", "mode")},
        "payloadFiles": [{key: item[key] for key in ("destination", "bytes", "sha256", "mode")} for item in files],
        "systemLibraries": aliases, "stagingCleanup": staging, "workingDirectory": private_root,
        "command": command, "shellStatusPreflight": shell_status,
        "nativeDeadlineSeconds": DEADLINE_SECONDS, "executionStarted": False,
        "requestedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        **{key: False for key in BROADER},
    }
    # Every command, pin and scope decision is durable before the child exists.
    loader._write_exclusive(out / "capture.json", (json.dumps(capture, indent=2) + "\n").encode())
    process: subprocess.Popen[bytes] | None = None
    results: dict[str, Any] = {}
    threads: list[threading.Thread] = []
    timed_out = False
    error: str | None = None
    stopped: dict[str, Any] | None = None
    cleanup_errors: list[str] = []
    remaining: list[dict[str, Any]] = []
    try:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        for name, pipe, limit in (("stdout", process.stdout, STDOUT_LIMIT), ("stderr", process.stderr, STDERR_LIMIT)):
            thread = threading.Thread(target=loader._drain, args=(name, pipe, limit, results), daemon=True)
            threads.append(thread)
            thread.start()
        process.wait(timeout=DEADLINE_SECONDS)
    except subprocess.TimeoutExpired:
        timed_out = True
    except Exception as exc:
        error = str(exc)[:2048]
    finally:
        if process is not None:
            # Let naturally completed pipes finish before deciding on package cleanup.
            if not timed_out and error is None and process.poll() is not None:
                for thread in threads:
                    thread.join(timeout=1)
            cleaned = cleanup_child(process, threads, timed_out or error is not None)
            stopped, remaining, cleanup_errors = (cleaned["forcedCleanup"], cleaned["remainingOwnProcesses"], cleaned["errors"])
    complete = (process is not None and not timed_out and error is None and stopped is None
                and not cleanup_errors and not any(thread.is_alive() for thread in threads)
                and set(results) == {"stdout", "stderr"})
    output = results.get("stdout", {"data": b"", "total": 0, "truncated": False})
    stderr = results.get("stderr", {"data": b"", "total": 0, "truncated": False})
    status, rows = validate_output(output["data"], run, process.returncode if process is not None else None,
                                   complete, output["truncated"] or stderr["truncated"])
    if remaining or stderr["data"]:
        status = "UNKNOWN"
    evidence_errors = []
    for name, data in (("stdout.txt", output["data"]), ("stderr.txt", stderr["data"])):
        try:
            loader._write_exclusive(out / name, data)
        except Exception as exc:
            evidence_errors.append(name + ": " + str(exc)[:1024])
            status = "UNKNOWN"
    summary = {
        "schema": 1, "runId": run, "status": status, "output": str(out), "events": rows,
        "nativeExitCode": process.returncode if process is not None else None,
        "hostAdbClientPid": process.pid if process is not None else None,
        "timedOut": timed_out, "error": error, "forcedCleanup": stopped,
        "cleanupErrors": cleanup_errors, "evidenceErrors": evidence_errors,
        "remainingOwnProcesses": remaining, "completePipes": complete,
        "stdoutBytes": output["total"], "stderrBytes": stderr["total"],
        "stdoutTruncated": output["truncated"], "stderrTruncated": stderr["truncated"],
        "eglContextVerified": status == "EPOXY_CONTEXT_PASS", **{key: False for key in BROADER},
        "boundary": "fixed private epoxy EGL context only; no renderer, caps, resources, presentation, D3D or Wine",
    }
    try:
        loader._write_exclusive(out / "summary.json", (json.dumps(summary, indent=2) + "\n").encode())
    except Exception as exc:
        summary["status"] = "UNKNOWN"
        summary["eglContextVerified"] = False
        summary["evidenceErrors"].append("summary.json: " + str(exc)[:1024])
        status = "UNKNOWN"
    print(json.dumps(summary, indent=2))
    return 0 if status == "EPOXY_CONTEXT_PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
