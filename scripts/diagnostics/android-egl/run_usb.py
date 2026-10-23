#!/usr/bin/env python3
"""Run the fixed native EGL diagnostic under the authorized lab app UID.

No APK launch, driver/library replacement, Wine, UI input, or package data reset.
The child gets only Android system library paths. A timeout is unknown evidence,
not GPU rejection; framework force-stop is limited to this lab package.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shlex
import subprocess
import threading
import uuid
from pathlib import Path

ADB = "/home/adrian/android-sdk/platform-tools/adb"
SERIAL_ENV_VAR = "WOWFOREVER_ADB_SERIAL"
SERIAL = os.environ.get(SERIAL_ENV_VAR, "")


def require_serial() -> str:
    if not SERIAL:
        raise RuntimeError("WOWFOREVER_ADB_SERIAL is not set (explicit device selection required)")
    return SERIAL
PACKAGE = "com.wowforever.arm64probe"
ARTIFACT = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/egl-baseline")
EXPECTED_SHA = "163ebc3f5705e322778bfd35f1fa2b82627dacce510885a3b172b9dd9c39e34b"
EXPECTED_SIZE = 22592
SCHEMA = "wowforever.android-egl-baseline/version1"
EVENTS = ["begin", "egl", "context", "caps", "shaders", "draw", "readback", "end"]
PROBES = {
    "baseline": {"artifact": ARTIFACT, "file": "EglBaseline", "sha": EXPECTED_SHA,
                 "size": EXPECTED_SIZE, "schema": SCHEMA, "accepted": "OFFSCREEN_PASS"},
    "surfaceless": {
        "artifact": Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/egl-surfaceless"),
        "file": "EglSurfaceless", "sha": "c67c8671e87b1f035c8d844be71c1cc112913f96704b60d5191e5fa11b6c6fc6",
        "size": 23520, "schema": "wowforever.android-egl-surfaceless/version1",
        "accepted": "SURFACELESS_OFFSCREEN_PASS",
    },
}
STDOUT_LIMIT = 65536
STDERR_LIMIT = 131072


def adb(*args: str, input_bytes: bytes | None = None, timeout: int = 15) -> subprocess.CompletedProcess:
    return subprocess.run([ADB, "-s", require_serial(), *args], input=input_bytes, capture_output=True, timeout=timeout)


def require_success(result: subprocess.CompletedProcess, stage: str) -> bytes:
    if result.returncode:
        raise RuntimeError(f"{stage} failed: {result.stderr[:2048].decode(errors='replace')}")
    return result.stdout


def unique_pairs(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON field")
        result[key] = value
    return result


def validate_output(raw: bytes, run: str, uid: int, return_code: int | None,
                    kind: str = "baseline") -> dict:
    if kind not in PROBES:
        raise ValueError("unknown fixed EGL probe")
    profile = PROBES[kind]
    if not raw or len(raw) > STDOUT_LIMIT or not raw.endswith(b"\n"):
        raise ValueError("missing, oversized or incomplete EGL output")
    rows = [json.loads(line, object_pairs_hook=unique_pairs,
                       parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))
            for line in raw.decode("utf-8", "strict").splitlines()]
    if len(rows) != len(EVENTS) or [row.get("event") for row in rows] != EVENTS:
        raise ValueError("incomplete or duplicate EGL stages")
    if any(row.get("schema") != profile["schema"] or row.get("runId") != run for row in rows):
        raise ValueError("EGL evidence identity mismatch")
    begin, end = rows[0], rows[-1]
    if (type(begin.get("pid")) is not int or begin["pid"] <= 0
            or begin.get("uid") != uid or end.get("pid") != begin["pid"] or end.get("uid") != uid):
        raise ValueError("EGL process identity mismatch")
    timestamps = [row.get("timeNs") for row in rows]
    if any(type(value) is not int or value <= 0 for value in timestamps) or timestamps != sorted(timestamps):
        raise ValueError("invalid monotonic EGL timestamps")
    if any(end.get(key) is not False for key in ("presentationVerified", "d3dVerified", "wowVerified")):
        raise ValueError("invalid expanded acceptance claim")
    readback = rows[6]
    offscreen_pass = (
        return_code == 0 and begin.get("status") == "STARTED"
        and rows[1].get("status") == "MEASURED" and rows[2].get("status") == "CREATED"
        and rows[2].get("current") is True and rows[3].get("status") == "MEASURED"
        and rows[4].get("status") == "PASS" and rows[5].get("status") == "PASS"
        and readback.get("status") == "PASS" and readback.get("glError") == 0
        and type(readback.get("coloredPixels")) is int and 128 <= readback["coloredPixels"] <= 4096
        and end.get("status") == profile["accepted"] and end.get("offscreenPass") is True
        and end.get("cleanupFailures") == 0 and end.get("cleanupStatus") == "CLEAN"
    )
    if kind == "surfaceless":
        offscreen_pass = offscreen_pass and (
            rows[1].get("extensionScanValid") is True
            and rows[1].get("hasEGLKHRCreateContext") is True
            and rows[1].get("hasEGLKHRSurfacelessContext") is True
            and rows[1].get("surfaceCreated") is False
            and rows[1].get("noSurfacePath") is True and rows[1].get("eglError") == 0x3000
            and rows[2].get("noSurfaceCurrent") is True
            and rows[2].get("surface") == "EGL_NO_SURFACE" and rows[2].get("eglError") == 0x3000
            and rows[5].get("fboCreated") is True and rows[5].get("fboComplete") is True
            and type(readback.get("rgbaFnv1a")) is int and readback["rgbaFnv1a"] != 0
            and end.get("nativeExit") == return_code and end.get("vtestVerified") is False
        )
    return {"schema": 1, "runId": run, "pid": begin["pid"], "uid": uid,
            "status": profile["accepted"] if offscreen_pass else "NOT_ACCEPTED",
            "probeKind": kind,
            "nativeReportedStatus": end.get("status"), "exitCode": return_code,
            "glVendor": rows[3].get("glVendor"), "glRenderer": rows[3].get("glRenderer"),
            "glVersion": rows[3].get("glVersion"), "coloredPixels": readback.get("coloredPixels"),
            "presentationVerified": False, "d3dVerified": False, "wowVerified": False,
            "vtestVerified": False,
            "boundary": "owned run-as UID child; not an APK-domain, vtest, Wine or presentation qualification"}


def own_processes(uid: int) -> list[dict]:
    raw = require_success(adb("shell", "ps", "-A", "-o", "UID,PID,NAME"), "own-UID preflight")
    result = []
    for line in raw.decode(errors="replace").splitlines()[1:]:
        fields = line.split(None, 2)
        if len(fields) == 3 and fields[0] in {str(uid), f"u0_a{uid - 10000}"}:
            result.append({"pid": fields[1], "name": fields[2]})
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="existing external evidence parent")
    parser.add_argument("--probe", choices=tuple(PROBES), default="baseline",
                        help="fixed reviewed artifact; no arbitrary executable path")
    args = parser.parse_args()
    try:
        require_serial()
    except RuntimeError as exc:
        parser.error(str(exc))
    profile = PROBES[args.probe]
    filename = profile["file"]
    parent = args.output.absolute()
    repo = Path(__file__).resolve().parents[3]
    if not parent.is_dir() or parent.is_symlink() or parent.resolve().is_relative_to(repo):
        raise SystemExit("evidence parent must exist outside the repo without a symlink")
    for component in [parent, *parent.parents]:
        if component.is_symlink():
            raise SystemExit("symlink in evidence parent")
    run = ("EGLSF-" if args.probe == "surfaceless" else "EGL-") + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:10]
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", run):
        raise SystemExit("invalid generated RUN")
    binary_path = profile["artifact"] / filename
    if binary_path.is_symlink() or not binary_path.is_file() or binary_path.stat().st_size != profile["size"]:
        raise SystemExit("EGL artifact is not the reviewed fixed file")
    payload = binary_path.read_bytes()
    if hashlib.sha256(payload).hexdigest() != profile["sha"]:
        raise SystemExit("EGL artifact pin mismatch")
    if require_success(adb("get-state"), "device authorization").strip() != b"device":
        raise SystemExit("device is not authorized")
    uid_raw = require_success(adb("exec-out", "run-as", PACKAGE, "id", "-u"), "app UID")
    if not uid_raw.strip().isdigit():
        raise SystemExit("unknown app UID")
    uid = int(uid_raw)
    if uid != 10330 or own_processes(uid):
        raise SystemExit("unexpected UID or live application processes; no probe started")
    app_root = require_success(adb("exec-out", "run-as", PACKAGE, "pwd"), "app data root").decode().strip()
    if app_root not in {f"/data/user/0/{PACKAGE}", f"/data/data/{PACKAGE}"}:
        raise SystemExit("unexpected private app root")
    out = parent / ("native-" + run)
    out.mkdir(mode=0o700)
    control_name = "egl-surfaceless" if args.probe == "surfaceless" else "egl-baseline"
    relative = f"files/forever/controls/{control_name}/{run}"
    directories = ["files", "files/forever", "files/forever/controls", f"files/forever/controls/{control_name}", relative]
    guards = []
    for directory in directories[:-1]:
        quoted = shlex.quote(directory)
        guards.append(f"test ! -L {quoted} && (test -d {quoted} || mkdir {quoted})")
    # Use ADB's sync file transfer, not exec-out (which did not deliver stdin).
    # The staged ELF is public diagnostic code, never credentials or app data.
    stage = f"/data/local/tmp/wowforever-egl-{uuid.uuid4().hex}"
    require_success(adb("shell", "test", "-d", "/data/local/tmp"), "staging parent")
    require_success(adb("shell", "mkdir", "-m", "755", stage), "exclusive staging directory")
    try:
        require_success(adb("push", str(binary_path), stage + "/" + filename, timeout=30), "sync upload")
        guards += [f"test ! -e {shlex.quote(relative)} && test ! -L {shlex.quote(relative)}",
                   f"mkdir -m 700 {shlex.quote(relative)}",
                   f"cat {shlex.quote(stage + '/' + filename)} > {shlex.quote(relative + '/' + filename)}"]
        # exec-out preserves the complete -c argument; quote path operands only.
        upload = "umask 077; set -e; set -C; " + "; ".join(guards) + "; printf EGL_UPLOAD_OK"
        upload_result = require_success(adb("exec-out", "run-as", PACKAGE, "sh", "-c", upload), "private exclusive copy")
    finally:
        # Only the two staging objects created above; no glob or app data removal.
        adb("shell", "rm", "-f", stage + "/" + filename)
        adb("shell", "rmdir", stage)
    if upload_result != b"EGL_UPLOAD_OK":
        (out / "upload-failure.txt").write_bytes(upload_result[:4096])
        raise SystemExit("private upload did not confirm success; not executed")
    uploaded = require_success(adb("exec-out", "run-as", PACKAGE, "sha256sum", relative + "/" + filename), "device artifact hash")
    if uploaded.decode().split()[0] != profile["sha"]:
        raise SystemExit("device artifact pin mismatch; not executed")
    context = require_success(adb("exec-out", "run-as", PACKAGE, "id", "-Z"), "SELinux evidence").decode().strip()
    # shell -T supplies the remote process exit status and disables PTY conversion.
    command = [ADB, "-s", SERIAL, "shell", "-T", "run-as", PACKAGE, "/system/bin/env", "-i",
               "PATH=/system/bin:/system/xbin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
               "LD_LIBRARY_PATH=/system/lib64", f"TMPDIR={app_root}/{relative}",
               "/system/bin/linker64", f"{app_root}/{relative}/{filename}", "--run-id", run]
    (out / "capture.json").write_text(json.dumps({"schema": 1, "runId": run, "serial": SERIAL,
        "uid": uid, "selinuxContext": context, "probeKind": args.probe,
        "artifactSha256": profile["sha"], "command": command,
        "runtimeChanged": False, "wineStarted": False}, indent=2) + "\n")
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
    results = {}

    def drain(name: str, pipe, limit: int) -> None:
        data = bytearray()
        total = 0
        with pipe:
            while True:
                chunk = pipe.read(8192)
                if not chunk:
                    break
                total += len(chunk)
                data.extend(chunk[:max(0, limit + 1 - len(data))])
        results[name] = {"data": bytes(data), "total": total, "truncated": total > limit}

    threads = [threading.Thread(target=drain, args=("stdout", process.stdout, STDOUT_LIMIT), daemon=True),
               threading.Thread(target=drain, args=("stderr", process.stderr, STDERR_LIMIT), daemon=True)]
    for thread in threads:
        thread.start()
    timed_out = False
    stop = None
    try:
        process.wait(timeout=45)
    except subprocess.TimeoutExpired:
        timed_out = True
        # Exact authorized lab package only; this is not a claim of kernel containment.
        stopped = adb("shell", "am", "force-stop", PACKAGE)
        stop = {"action": "owned lab package force-stop after EGL timeout", "returnCode": stopped.returncode}
        process.terminate()  # Only the HOST ADB client held by this Popen instance.
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
    for thread in threads:
        thread.join(timeout=5)
    for name in ("stdout", "stderr"):
        if name in results:
            (out / (name + ".txt")).write_bytes(results[name]["data"])
    summary = {"schema": 1, "runId": run, "status": "UNKNOWN", "timedOut": timed_out,
               "hostAdbExitCode": process.returncode, "timeoutStop": stop}
    try:
        if timed_out or any(thread.is_alive() for thread in threads) or "stdout" not in results or results["stdout"]["truncated"]:
            raise ValueError("timeout, incomplete drain or truncated output")
        summary.update(validate_output(results["stdout"]["data"], run, uid, process.returncode, args.probe))
    except (ValueError, TypeError, KeyError) as exc:
        summary["reason"] = str(exc)
    summary["remainingOwnProcesses"] = own_processes(uid)
    summary["output"] = str(out)
    (out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))
    return 0 if summary["status"] == profile["accepted"] and not summary["remainingOwnProcesses"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
