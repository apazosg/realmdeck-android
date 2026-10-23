#!/usr/bin/env python3
"""Run the approved PanVK control-A once and capture a bounded logcat dump.

Diagnostic-only variant of ``run_device_usb.py``: identical pinned artifact,
scope, environment, cleanup and parser.  The only difference is that after the
child completes we dump the Android log buffers (bounded) and filter them for
kbase/panvk/Mesa diagnostics, so the ICD's own ``mesa_loge`` output can be kept.

No environment change, no binary change, no profile change, no capability
change, and never control B or GPU work.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path
from typing import Any


_DEVICE_PATH = Path(__file__).with_name("run_device_usb.py")
_SPEC = importlib.util.spec_from_file_location("panvk_device_logging_base", _DEVICE_PATH)
if _SPEC is None or _SPEC.loader is None:  # pragma: no cover
    raise ImportError(f"cannot load {_DEVICE_PATH}")
device = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = device
_SPEC.loader.exec_module(device)

LOGCAT_DUMP_LIMIT = 8 * 1024 * 1024
LOGCAT_FILTER_LIMIT = 1 * 1024 * 1024
LOGCAT_FILTER = ("kbase", "panvk", "mesa", "libegl", "vulkan", "mali", "g610", "wowforever")
TAG = "native-logging-"


def _device_time() -> str | None:
    try:
        result = subprocess.run(
            [device.ADB, "-s", device.SERIAL, "shell", "date", "+%m-%d_%H:%M:%S.000"],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15, shell=False,
        )
    except Exception:
        return None
    if result.returncode != 0:
        return None
    value = result.stdout.decode(errors="replace").strip().replace("_", " ")
    return value or None


def _dump_logcat(start_time: str | None) -> tuple[bytes, str | None]:
    command = [device.ADB, "-s", device.SERIAL, "logcat", "-d", "-b", "all", "-v", "threadtime"]
    if start_time:
        command += ["-T", start_time]
    try:
        result = subprocess.run(
            command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, shell=False,
        )
    except Exception as exc:
        return b"", str(exc)
    raw = result.stdout[-LOGCAT_DUMP_LIMIT:]
    if result.returncode != 0:
        return raw, f"logcat exit {result.returncode}: {result.stderr[:200]!r}"
    return raw, None


def _filter_logcat(raw: bytes) -> bytes:
    kept: list[bytes] = []
    total = 0
    for line in raw.splitlines(keepends=True):
        if any(token.encode() in line.lower() for token in LOGCAT_FILTER):
            if total + len(line) > LOGCAT_FILTER_LIMIT:
                break
            kept.append(line)
            total += len(line)
    return b"".join(kept)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    parent = device._external_parent(args.output)
    native = device.load_native()
    shell_status = device.remote_status_preflight()
    uid, app_root, selinux = device.package_preflight()
    if uid != device.UID or app_root not in device.APP_ROOTS:
        raise RuntimeError("unexpected app identity")
    api_level = device._api_preflight()
    run = "PVK-" + uuid.uuid4().hex
    relative, staging = device.copy_native(native, run)
    private_root = f"{app_root}/{relative}"
    command = device.shell_command(private_root, run)
    out = parent / (TAG + run)
    out.mkdir(mode=0o700)
    capture = {
        "schema": 1, "runId": run, "serial": device.SERIAL, "package": device.PACKAGE,
        "uid": uid, "versionCode": device.VERSION_CODE, "deviceApiLevel": api_level,
        "publishedMinimumApiLevel": device.PUBLISHED_MIN_API_LEVEL, "apiCompatibilityProven": False,
        "selinuxContext": selinux, "artifact": native, "stagingCleanup": staging,
        "privateRelative": relative, "workingDirectory": private_root, "command": command,
        "shellStatusPreflight": shell_status, "nativeSchema": device.NATIVE_SCHEMA,
        "diagnosticOnly": "bounded post-run logcat dump around the unchanged control A child",
        "executionStarted": False, "icdCodeLoaded": False, "constructorsMayHaveExecuted": False,
        "vulkanFunctionsInvoked": False, "apiComplete": False, "gpuWorkload": False,
        "triangle": False, "presentation": False, "d3d": False, "wow": False,
    }
    device._write_exclusive(out / "capture.json", (json.dumps(capture, sort_keys=True, indent=2, default=str) + "\n").encode())

    process: subprocess.Popen[bytes] | None = None
    captured: dict[str, Any] = {}
    threads: list[threading.Thread] = []
    timed_out = False
    start_error: str | None = None
    cleanup_error: str | None = None
    force_stop: dict[str, Any] | None = None
    logcat_start = _device_time()
    try:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=False)
        capture["executionStarted"] = True
        capture["hostAdbClientPid"] = process.pid
        if process.stdout is None or process.stderr is None:
            raise RuntimeError("missing native pipe")
        for name, pipe, limit in (("stdout", process.stdout, device.NATIVE_STDOUT_LIMIT), ("stderr", process.stderr, device.NATIVE_STDERR_LIMIT)):
            thread = threading.Thread(target=device._drain, args=(name, pipe, limit, captured), daemon=True)
            threads.append(thread)
            thread.start()
        try:
            process.wait(timeout=device.NATIVE_DEADLINE_SECONDS)
        except subprocess.TimeoutExpired:
            timed_out = True
            force_stop = device._force_stop()
    except Exception as exc:
        start_error = str(exc)
        if process is not None:
            force_stop = device._force_stop()
    finally:
        deadline = time.monotonic() + device.CLEANUP_DEADLINE_SECONDS
        if process is not None and not device._reap(process, deadline):
            cleanup_error = "held HOST adb handle did not quiesce"
        for thread in threads:
            thread.join(timeout=max(0.0, deadline - time.monotonic()))
        if any(thread.is_alive() for thread in threads):
            cleanup_error = cleanup_error or "output drain did not quiesce"

    # Best-effort bounded logcat dump after the child; never changes the result.
    logcat_raw, logcat_error = _dump_logcat(logcat_start)
    filtered = _filter_logcat(logcat_raw)
    for name, data in (("logcat.txt", logcat_raw), ("logcat-filtered.txt", filtered)):
        try:
            device._write_exclusive(out / name, data)
        except Exception:
            pass

    values = {name: captured.get(name, {"data": b"", "total": 0, "truncated": False}) for name in ("stdout", "stderr")}
    output_errors: list[str] = []
    for name in ("stdout", "stderr"):
        try:
            device._write_exclusive(out / f"{name}.txt", values[name]["data"])
        except Exception as exc:
            output_errors.append(f"{name}: {exc}")
    native_document: dict[str, Any] | None = None
    diagnostic: str | None = None
    status = "UNKNOWN"
    if start_error:
        diagnostic = "native child start failed: " + start_error
    elif timed_out:
        diagnostic = "native child exceeded ten-second deadline"
    elif cleanup_error:
        diagnostic = cleanup_error
    elif set(captured) != {"stdout", "stderr"}:
        diagnostic = "native output drain was incomplete"
    elif values["stdout"]["truncated"] or values["stderr"]["truncated"]:
        diagnostic = "native output exceeded capture bound"
    elif values["stderr"]["data"]:
        diagnostic = "native stderr was not empty"
    else:
        try:
            native_document = device.parse_native(values["stdout"]["data"], run, private_root, process.returncode if process else None)
            status = native_document["status"]
        except (UnicodeError, ValueError, json.JSONDecodeError) as exc:
            diagnostic = f"native JSON validation failed: {exc}"
    remaining_error: str | None = None
    try:
        remaining = device.own_processes(device.UID)
    except Exception as exc:
        remaining = [{"state": "UNKNOWN", "error": str(exc)}]
        remaining_error = str(exc)
    runner_error = device._requires_force_stop(
        status, start_error=start_error, timed_out=timed_out, cleanup_error=cleanup_error,
        diagnostic=diagnostic, remaining_error=remaining_error, remaining=remaining,
        output_errors=output_errors,
        native_cleanup_complete=bool(native_document and native_document["cleanup"]["complete"] is True),
    )
    if runner_error and force_stop is None:
        force_stop = device._force_stop()
    summary = {
        "schema": 1, "runId": run, "status": status, "diagnostic": diagnostic,
        "deviceApiLevel": api_level, "publishedMinimumApiLevel": device.PUBLISHED_MIN_API_LEVEL,
        "apiCompatibilityProven": False, "nativeExit": process.returncode if process else None,
        "hostAdbClientPid": process.pid if process else None, "timedOut": timed_out,
        "startError": start_error, "cleanupError": cleanup_error, "forceStop": force_stop,
        "remainingOwnProcesses": remaining, "stdoutBytes": values["stdout"]["total"],
        "stderrBytes": values["stderr"]["total"], "completePipes": not any(t.is_alive() for t in threads) and set(captured) == {"stdout", "stderr"},
        "native": native_document, "outputErrors": output_errors,
        "logcatBytes": len(logcat_raw), "logcatFilteredBytes": len(filtered), "logcatError": logcat_error, "logcatStart": logcat_start,
        "stopBeforeTransferRequired": status != "DEVICE_QUEUE_OBSERVED" or runner_error,
        "boundary": "diagnostic logcat dump around one PanVK device/queue control A; not transfer, workload, render, D3D, WoW",
        "output": str(out), "icdCodeLoaded": bool(native_document and native_document.get("icdCodeLoaded") is True),
        "constructorsMayHaveExecuted": bool(native_document and native_document.get("constructorsMayHaveExecuted") is True),
        "vulkanFunctionsInvoked": bool(native_document and native_document.get("vulkanFunctionsInvoked") is True),
        "apiComplete": False, "gpuWorkload": False, "triangle": False, "presentation": False, "d3d": False, "wow": False,
    }
    try:
        device._write_exclusive(out / "summary.json", (json.dumps(summary, sort_keys=True, indent=2, default=str) + "\n").encode())
    except Exception as exc:
        output_errors.append(f"summary: {exc}")
    print(json.dumps({key: summary[key] for key in ("schema", "runId", "status", "nativeExit", "timedOut", "diagnostic", "logcatBytes", "logcatFilteredBytes", "logcatError")}, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
