#!/usr/bin/env python3
"""Bounded, single-attempt physical USB trial for the private ARM64 probe.

This file intentionally has no project dependencies.  It uses the fixed ADB
binary for the already-authorized device and libusb's public control-transfer
API for the Android Open Accessory HID keyboard.  It does not install, reboot,
clear logs/data, inject shell input, signal a PID, or touch another package.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import ctypes
import hashlib
import json
import os
import re
import shlex
import subprocess
import sys
import threading
import time
import uuid
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Sequence


ADB = "/home/adrian/android-sdk/platform-tools/adb"
SERIAL_ENV_VAR = "WOWFOREVER_ADB_SERIAL"
SERIAL = os.environ.get(SERIAL_ENV_VAR, "")
PACKAGE = "com.wowforever.arm64probe"
UID = 10330
ALIAS = "com.wowforever.arm64probe/app.gamenative.MainActivityAliasDefault"
VERSION_CODE = 39
VERSION_NAME = "0.2.15-automated"
CURRENT_MARKER = "files/forever/current.json"
RUN_RECORD_ROOT = "files/forever/runs"
SENSITIVE_SESSION_MARKER = "files/forever/assisted-credentials-sensitive"
UI_DUMP_PREFIX = "/data/local/tmp/wowforever-usb-lab-"
UI_DUMP_SUFFIX = ".xml"
UI_COMMAND_TIMEOUT = 45
UI_DUMP_CLEANUP_TIMEOUT = 5.0
TRIAL_SECONDS = 75
PRIVATE_JSON_CAP = 500_000
MAX_MAP_BYTES = 2 * 1024 * 1024
MAX_CMDLINE_BYTES = 8192
MAX_PROCESS_SNAPSHOTS = 48
MAX_PROCESSES_PER_SAMPLE = 24
MAX_PROCESS_CANDIDATES_PER_SAMPLE = 6
FINAL_CAPTURE_RESERVE_SECONDS = 25
UI_READINESS_SECONDS = 8.0
UI_READINESS_MAX_DUMPS = 3
UI_READINESS_MIN_COMMAND_SECONDS = 3.0
UI_READINESS_DUMP_BUDGET_SECONDS = 3.0
UI_READINESS_CLEANUP_SECONDS = 0.5
UI_READINESS_DUMP_SPACING_SECONDS = 2.0
EARLY_ROOT_SAMPLING_SECONDS = 10
EARLY_SAMPLE_INTERVAL_SECONDS = 0.25
REGULAR_SAMPLE_INTERVAL_SECONDS = 3.0
MAX_PS_SAMPLES = 64
MAX_PS_SAMPLE_BYTES = 64 * 1024
MAX_PS_TOTAL_BYTES = 4 * 1024 * 1024
MAX_PS_HEADER_BYTES = 256
MAX_STREAM_JSON = 800 * 1024  # Base64 adds 4/3 overhead to each 512-KiB raw stream.
MAX_STREAM_TOTAL_RAW = 2 * 512 * 1024
MAX_GRAPHICS_LOG_BYTES = 256 * 1024
STREAM_HEAD_BYTES = 128 * 1024
STREAM_TAIL_BYTES = 384 * 1024
RUN_RE = re.compile(r"^RUN-[A-Za-z0-9_.-]+$")
SENSITIVE_RE = re.compile(r"(?:token|password|passwd|secret|credential|cookie|account|email|auth)", re.I)
ALLOWED_PROCESS_BASES = {
    "box64",
    "fex",
    "explorer.exe",
    "start.exe",
    "wine",
    "wine64",
    "wineserver",
    "wowb.exe",
    "wowb-arm64.exe",
}
GRAPHICS_PROCESS_PREFIX = "graphicsprobe"
RUNTIME_PROCESS_NAMES = frozenset(ALLOWED_PROCESS_BASES)


@dataclass(frozen=True)
class ProbeIdentity:
    file: str
    size: int
    sha256: str


@dataclass(frozen=True)
class TrialPolicy:
    collect_graphics: bool
    collect_panvk_identity: bool
    collect_probe_identity: bool
    max_seconds: int | None = None


LEGACY_PROBE = ProbeIdentity(
    "GraphicsProbe-ARM64.exe", 89600,
    "a19e6721647fae0f31052133c585c91194823883d6be9bb81c6d0d342abfee9f",
)
COMPILER_LOG_PROBE = ProbeIdentity(
    "GraphicsProbeLog-ARM64.exe", 90112,
    "795acd6d878d6def7139566da6040b16bf2ee6a2a9edfb90d16d4af32a67ab9a",
)
READBACK_PROBE = ProbeIdentity(
    "GraphicsProbeReadback-ARM64.exe", 96768,
    "80c1c260d7781bda73e3d5552c7ca730e72382288a9256b4a78c7899d3f0c3de",
)
APPROVED_TRIAL_PROBES = {
    (39, "0.2.15-automated"): LEGACY_PROBE,
    (40, "0.2.16-automated"): LEGACY_PROBE,
    (41, "0.2.17-automated"): LEGACY_PROBE,
    (42, "0.2.18-automated"): LEGACY_PROBE,
    (43, "0.2.19-automated"): LEGACY_PROBE,
    (44, "0.2.20-automated"): LEGACY_PROBE,
    (45, "0.2.21-automated"): LEGACY_PROBE,
    (46, "0.2.22-automated"): LEGACY_PROBE,
    (47, "0.2.23-automated"): LEGACY_PROBE,
    (48, "0.2.24-automated"): LEGACY_PROBE,
    (49, "0.2.25-automated"): LEGACY_PROBE,
    (50, "0.2.26-automated"): LEGACY_PROBE,
    (51, "0.2.27-automated"): COMPILER_LOG_PROBE,
    (52, "0.2.28-automated"): COMPILER_LOG_PROBE,
    (53, "0.2.29-automated"): COMPILER_LOG_PROBE,
    (54, "0.2.30-automated"): COMPILER_LOG_PROBE,
    (55, "0.2.31-automated"): READBACK_PROBE,
}
APPROVED_TRIAL_POLICIES = {
    (39, "0.2.15-automated"): TrialPolicy(False, False, False),
    (40, "0.2.16-automated"): TrialPolicy(False, False, False),
    (41, "0.2.17-automated"): TrialPolicy(True, False, False),
    (42, "0.2.18-automated"): TrialPolicy(False, False, False),
    (43, "0.2.19-automated"): TrialPolicy(False, False, False),
    (44, "0.2.20-automated"): TrialPolicy(False, False, False),
    (45, "0.2.21-automated"): TrialPolicy(False, False, False),
    (46, "0.2.22-automated"): TrialPolicy(False, False, False),
    (47, "0.2.23-automated"): TrialPolicy(False, False, False),
    (48, "0.2.24-automated"): TrialPolicy(True, True, True, 120),
    (49, "0.2.25-automated"): TrialPolicy(True, True, True, 120),
    (50, "0.2.26-automated"): TrialPolicy(True, True, True, 120),
    (51, "0.2.27-automated"): TrialPolicy(True, True, True, 120),
    (52, "0.2.28-automated"): TrialPolicy(True, True, True, 120),
    (53, "0.2.29-automated"): TrialPolicy(True, True, True, 120),
    (54, "0.2.30-automated"): TrialPolicy(True, True, True, 120),
    (55, "0.2.31-automated"): TrialPolicy(True, True, True, 120),
}
assert APPROVED_TRIAL_POLICIES.keys() == APPROVED_TRIAL_PROBES.keys()


def trial_policy(version_code: int, version_name: str) -> TrialPolicy:
    try:
        return APPROVED_TRIAL_POLICIES[(version_code, version_name)]
    except KeyError as exc:
        raise TrialError("version arguments must identify an approved fixed trial") from exc


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.chmod(path, 0o600)


def write_bytes(path: Path, value: bytes) -> None:
    with path.open("wb") as stream:
        stream.write(value)
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(path, 0o600)


class TrialError(RuntimeError):
    pass


def require_serial() -> str:
    if not SERIAL:
        raise TrialError("WOWFOREVER_ADB_SERIAL is not set (explicit device selection required)")
    return SERIAL


@dataclass(frozen=True)
class AdbResult:
    argv: tuple[str, ...]
    returncode: int
    stdout: bytes
    stderr: bytes


class Adb:
    def __init__(self, path: str = ADB, serial: str | None = None) -> None:
        resolved = serial if serial is not None else require_serial()
        if not resolved:
            raise TrialError("WOWFOREVER_ADB_SERIAL is not set (explicit device selection required)")
        if path != ADB or resolved != SERIAL:
            raise TrialError("ADB path and serial are fixed for this trial")
        self.path = path
        self.serial = resolved
        self.deadline: float | None = None
        self.sensitive_session_detected = False

    def require_no_sensitive_session(self) -> None:
        files = shlex.quote("files")
        marker = shlex.quote(SENSITIVE_SESSION_MARKER)
        parent = shlex.quote("files/forever")
        command = (f"if [ -L {files} ] || [ ! -d {files} ] || [ ! -r {files} ] || [ ! -x {files} ] || "
                   f"[ -L {parent} ] || {{ [ -e {parent} ] && {{ [ ! -d {parent} ] || "
                   f"[ ! -r {parent} ] || [ ! -x {parent} ]; }}; }} "
                   f"|| [ -e {marker} ] || [ -L {marker} ]; then printf ACTIVE; else printf CLEAR; fi")
        try:
            result = self.shell("run-as", PACKAGE, "sh", "-c", command, timeout=5)
        except (OSError, TrialError, ValueError):
            self.sensitive_session_detected = True
            raise TrialError("assisted credential session active or marker unavailable; host capture disabled") from None
        if result.returncode != 0 or result.stdout.strip() != b"CLEAR" or getattr(result, "stderr", b""):
            self.sensitive_session_detected = True
            raise TrialError("assisted credential session active or marker unavailable; host capture disabled")

    def require_no_sensitive_run(self, run_id: str) -> None:
        if not RUN_RE.fullmatch(run_id):
            self.sensitive_session_detected = True
            raise TrialError("invalid RUN identity; host capture disabled")
        files = shlex.quote("files")
        forever = shlex.quote("files/forever")
        runs = shlex.quote(f"files/forever/runs/{run_id}-assisted-sensitive")
        parent = shlex.quote("files/forever/runs")
        command = (f"if [ -L {files} ] || [ ! -d {files} ] || [ ! -r {files} ] || [ ! -x {files} ] || "
                   f"[ -L {forever} ] || [ ! -d {forever} ] || [ ! -r {forever} ] || [ ! -x {forever} ] || "
                   f"[ -L {parent} ] || [ ! -d {parent} ] || [ ! -r {parent} ] || [ ! -x {parent} ] || "
                   f"[ -e {runs} ] || [ -L {runs} ]; then printf ACTIVE; else printf CLEAR; fi")
        try:
            result = self.shell("run-as", PACKAGE, "sh", "-c", command, timeout=5)
        except (OSError, TrialError, ValueError):
            self.sensitive_session_detected = True
            raise TrialError("sensitive RUN marker unavailable; host capture disabled") from None
        if result.returncode != 0 or result.stdout.strip() != b"CLEAR" or getattr(result, "stderr", b""):
            self.sensitive_session_detected = True
            raise TrialError("sensitive RUN or marker unavailable; host capture disabled")

    def run(self, args: Sequence[str], *, timeout: float = 30.0) -> AdbResult:
        if not self.serial:
            raise TrialError("WOWFOREVER_ADB_SERIAL is not set (explicit device selection required)")
        argv = (self.path, "-s", self.serial, *map(str, args))
        if self.deadline is not None:
            remaining = self.deadline - time.monotonic()
            if remaining <= 0:
                raise TrialError("absolute host trial deadline reached")
            timeout = min(timeout, remaining)
        try:
            result = subprocess.run(
                list(argv), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                timeout=timeout, check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            return AdbResult(argv, 125, b"", str(exc).encode())
        return AdbResult(argv, result.returncode, result.stdout or b"", result.stderr or b"")

    def shell(self, *args: str, timeout: float = 30.0) -> AdbResult:
        return self.run(("shell", *args), timeout=timeout)

    def exec_out(self, *args: str, timeout: float = 30.0) -> AdbResult:
        return self.run(("exec-out", *args), timeout=timeout)


def require_no_sensitive_session(adb: Adb) -> None:
    """Production Adb enforces the marker; offline fixtures may omit the hook."""
    checker = getattr(adb, "require_no_sensitive_session", None)
    if checker is not None:
        checker()


def require_no_sensitive_run(adb: Adb, run_id: str) -> None:
    checker = getattr(adb, "require_no_sensitive_run", None)
    if checker is not None:
        checker(run_id)


def write_capture_bytes(adb: Adb, path: Path, value: bytes, *, exclusive: bool = False,
                        run_id: str | None = None) -> None:
    try:
        require_no_sensitive_session(adb)
        if run_id is not None:
            require_no_sensitive_run(adb, run_id)
        mode = "xb" if exclusive else "wb"
        with path.open(mode) as stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
        require_no_sensitive_session(adb)
        if run_id is not None:
            require_no_sensitive_run(adb, run_id)
    except TrialError:
        path.unlink(missing_ok=True)
        raise


def write_capture_json(adb: Adb, path: Path, value: object, *, run_id: str | None = None) -> None:
    write_capture_bytes(adb, path, (json.dumps(value, indent=2, sort_keys=True) + "\n").encode(), run_id=run_id)


def append_capture_jsonl(adb: Adb, path: Path, value: object, *, run_id: str | None = None) -> None:
    try:
        require_no_sensitive_session(adb)
        if run_id is not None:
            require_no_sensitive_run(adb, run_id)
        with path.open("a", encoding="utf-8") as stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(json.dumps(value, sort_keys=True) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        require_no_sensitive_session(adb)
        if run_id is not None:
            require_no_sensitive_run(adb, run_id)
    except TrialError:
        path.unlink(missing_ok=True)
        raise


def text(result: AdbResult) -> str:
    return result.stdout.decode("utf-8", errors="replace").strip()


class Journal:
    def __init__(self, path: Path, *, serial: str, package: str, version: dict[str, object]) -> None:
        self.path = path
        self.serial = serial
        self.package = package
        self.version = version
        self.path.touch(mode=0o600, exist_ok=False)
        os.chmod(self.path, 0o600)
        self._last_timestamp: datetime | None = None

    def state(self, name: str, **metadata: object) -> None:
        timestamp = datetime.now(timezone.utc)
        timestamp = timestamp.replace(microsecond=(timestamp.microsecond // 1000) * 1000)
        if self._last_timestamp is not None and timestamp <= self._last_timestamp:
            timestamp = self._last_timestamp + timedelta(milliseconds=1)
        self._last_timestamp = timestamp
        record = {
            "timestamp": timestamp.isoformat(timespec="milliseconds"),
            "state": name,
            "serial": self.serial,
            "package": self.package,
            "version": self.version,
            "metadata": metadata,
        }
        with self.path.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(record, sort_keys=True) + "\n")
            stream.flush()
            os.fsync(stream.fileno())


@dataclass(frozen=True)
class CurrentRun:
    run_id: str
    payload: dict[str, Any]


def parse_strict_run(raw: bytes) -> CurrentRun | None:
    """Accept only a complete run JSON object with the versioned ``runId`` key."""
    stripped = raw.lstrip()
    if not stripped.startswith(b"{"):
        return None
    try:
        decoder = json.JSONDecoder()
        value, end = decoder.raw_decode(stripped.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    if stripped.decode("utf-8")[end:].strip() or not isinstance(value, dict):
        return None
    run_id = value.get("runId")
    if not isinstance(run_id, str) or not RUN_RE.fullmatch(run_id):
        return None
    return CurrentRun(run_id, value)


def read_private_json(adb: Adb, path: str) -> tuple[dict[str, Any] | None, str]:
    """Read one fixed private JSON file without trusting metadata paths."""
    require_no_sensitive_session(adb)
    result = adb.exec_out(
        "run-as", PACKAGE, "head", "-c", str(PRIVATE_JSON_CAP + 1), path, timeout=45
    )
    require_no_sensitive_session(adb)
    if result.returncode != 0 or len(result.stdout) > PRIVATE_JSON_CAP:
        return None, "missing" if not result.stdout else "oversized-or-unreadable"
    parsed = parse_strict_run(result.stdout)
    if parsed is None:
        return None, "missing-or-malformed"
    return parsed.payload, "ok"


def run_record_path(run_id: str) -> str:
    if not RUN_RE.fullmatch(run_id):
        raise TrialError("invalid runId")
    return f"{RUN_RECORD_ROOT}/{run_id}.json"


def read_current(adb: Adb) -> tuple[CurrentRun | None, str]:
    marker, marker_status = read_private_json(adb, CURRENT_MARKER)
    if marker is None:
        return None, marker_status
    marker_run_id = marker.get("runId")
    if not isinstance(marker_run_id, str):
        return None, "missing-or-malformed"
    record, record_status = read_private_json(adb, run_record_path(marker_run_id))
    if record is None:
        return None, record_status
    run_id = record.get("runId")
    if run_id != marker_run_id:
        return None, "runId-mismatch"
    return CurrentRun(marker_run_id, record), "ok"


def safe_output_dir(path: Path) -> Path:
    resolved = path.expanduser().resolve()
    repo = Path(__file__).resolve().parents[2]
    if resolved == repo or repo in resolved.parents:
        raise TrialError("--output must be outside the repository")
    if not resolved.is_dir():
        raise TrialError("--output must be an existing directory")
    return resolved


def new_run_dir(output: Path) -> tuple[str, Path]:
    for _ in range(20):
        run_id = "trial-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:10]
        candidate = output / run_id
        try:
            candidate.mkdir(mode=0o700)
            os.chmod(candidate, 0o700)
            return run_id, candidate
        except FileExistsError:
            continue
    raise TrialError("could not allocate a unique private run directory")


def discard_sensitive_capture_outputs(run_dir: Path) -> None:
    """Discard only this fresh trial's content-bearing capture artifacts."""
    patterns = (
        "final-screenshot.png", "logcat.txt", "logcat-stderr.txt",
        "run-diagnostics.jsonl", "stdout-snapshot.json", "stderr-snapshot.json",
        "stream-status.json", "ps-raw-*.txt", "process-observations.jsonl",
        "ps-observations.jsonl", "process-snapshots.jsonl", "maps-*.txt",
        "graphics-probe.jsonl", "private-run*.json",
    )
    for pattern in patterns:
        for path in run_dir.glob(pattern):
            if path.is_file() or path.is_symlink():
                path.unlink(missing_ok=True)


def package_version(dumpsys: str) -> tuple[str | None, str | None]:
    code = re.search(r"\bversionCode=(\d+)", dumpsys)
    name = re.search(r"\bversionName=([^\s]+)", dumpsys)
    return (code.group(1) if code else None, name.group(1) if name else None)


def validate_device(adb: Adb, requested_code: int, requested_name: str) -> dict[str, object]:
    require_no_sensitive_session(adb)
    state = adb.run(("get-state",), timeout=15)
    require_no_sensitive_session(adb)
    if state.returncode != 0 or text(state) != "device":
        raise TrialError(f"fixed serial is not ready: {text(state)!r}")
    require_no_sensitive_session(adb)
    path = adb.shell("pm", "path", PACKAGE)
    require_no_sensitive_session(adb)
    if path.returncode != 0 or not text(path).startswith("package:"):
        raise TrialError("private probe package is not installed")
    require_no_sensitive_session(adb)
    dump = adb.shell("dumpsys", "package", PACKAGE)
    require_no_sensitive_session(adb)
    code, name = package_version(text(dump))
    if code != str(requested_code) or name != requested_name:
        raise TrialError(f"unexpected installed version: code={code!r} name={name!r}")
    if not (re.search(r"\bdebuggable=true\b", text(dump)) or re.search(r"DEBUGGABLE", text(dump))):
        raise TrialError("installed package is not debuggable")
    require_no_sensitive_session(adb)
    identity = adb.exec_out("run-as", PACKAGE, "id", timeout=10)
    require_no_sensitive_session(adb)
    match = re.search(rb"\buid=(\d+)\b", identity.stdout)
    if identity.returncode != 0 or not match or int(match.group(1)) != UID:
        raise TrialError("run-as did not resolve to the expected UID")
    return {
        "version_code": requested_code,
        "version_name": requested_name,
        "uid": UID,
        "package_path": text(path),
        "run_as_uid": int(match.group(1)),
    }


def host_proc_stat(pid: int) -> dict[str, object]:
    raw = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8")
    close = raw.rfind(") ")
    fields = raw[close + 2 :].split() if close >= 0 else []
    return {"pid": pid, "start_ticks": int(fields[19]) if len(fields) > 19 else None}


class LogcatCapture:
    def __init__(self, run_dir: Path, uid: int, adb: Adb | None = None) -> None:
        require_serial()
        if adb is not None:
            require_no_sensitive_session(adb)
        self.run_dir = run_dir
        self.adb = adb
        self.run_id: str | None = None
        self.sensitive_session_detected = False
        self._guard_stop = threading.Event()
        self._guard_thread: threading.Thread | None = None
        self.out = (run_dir / "logcat.txt").open("wb")
        self.err = (run_dir / "logcat-stderr.txt").open("wb")
        os.fchmod(self.out.fileno(), 0o600)
        os.fchmod(self.err.fileno(), 0o600)
        self.argv = [
            ADB, "-s", SERIAL, "logcat", f"--uid={uid}",
            "-b", "main", "-b", "system", "-b", "crash", "-b", "events",
            "-v", "threadtime", "-T", "1",
            "*:V",
        ]
        self.process = subprocess.Popen(self.argv, stdout=self.out, stderr=self.err, close_fds=True)
        if adb is not None:
            try:
                require_no_sensitive_session(adb)
            except TrialError:
                self.sensitive_session_detected = True
                self.process.terminate()
                self.process.wait(timeout=4)
                self.out.close()
                self.err.close()
                raise
        try:
            start = host_proc_stat(self.process.pid)
        except OSError:
            start = {"pid": self.process.pid, "start_ticks": None}
        try:
            fd_target = os.readlink(f"/proc/{self.process.pid}/fd/1")
        except OSError:
            fd_target = None
        self.metadata: dict[str, object] = {
            "pid": self.process.pid,
            "start": start,
            "fd_stdout_child": 1,
            "fd_stdout_target": fd_target,
            "argv": self.argv,
        }
        write_json(run_dir / "logcat-process.json", self.metadata)
        if adb is not None:
            self._guard_thread = threading.Thread(target=self._watch_sensitive_session,
                                                  name="usb-trial-privacy-guard", daemon=True)
            self._guard_thread.start()

    def _watch_sensitive_session(self) -> None:
        while not self._guard_stop.wait(0.25):
            try:
                require_no_sensitive_session(self.adb)
                if self.run_id is not None:
                    require_no_sensitive_run(self.adb, self.run_id)
            except TrialError:
                self.sensitive_session_detected = True
                if self.process.poll() is None:
                    self.process.terminate()
                return

    def close(self) -> None:
        self._guard_stop.set()
        if self.process.poll() is None:
            self.process.terminate()
        if self._guard_thread is not None and self._guard_thread is not threading.current_thread():
            self._guard_thread.join(timeout=6)
        if self.adb is not None and not self.sensitive_session_detected:
            try:
                require_no_sensitive_session(self.adb)
                if self.run_id is not None:
                    require_no_sensitive_run(self.adb, self.run_id)
            except TrialError:
                self.sensitive_session_detected = True
        try:
            if self.process.poll() is None:
                self.process.terminate()
            try:
                returncode = self.process.wait(timeout=4)
            except subprocess.TimeoutExpired:
                self.process.kill()  # Retained HOST child handle only, never a phone PID.
                returncode = self.process.wait(timeout=2)
            self.metadata["returncode"] = returncode
            try:
                self.metadata["end"] = host_proc_stat(self.process.pid) if self.process.poll() is None else None
            except OSError:
                self.metadata["end"] = None
            write_json(self.run_dir / "logcat-process.json", self.metadata)
        finally:
            self.out.close()
            self.err.close()
            if self.sensitive_session_detected:
                for name in ("logcat.txt", "logcat-stderr.txt"):
                    (self.run_dir / name).unlink(missing_ok=True)


class UsbKeyboard:
    """AOAv2 keyboard, deliberately limited to HID control transfers."""

    class UsbDescriptor(ctypes.Structure):
        _fields_ = [
            ("bLength", ctypes.c_ubyte),
            ("bDescriptorType", ctypes.c_ubyte),
            ("bcdUSB", ctypes.c_ushort),
            ("bDeviceClass", ctypes.c_ubyte),
            ("bDeviceSubClass", ctypes.c_ubyte),
            ("bDeviceProtocol", ctypes.c_ubyte),
            ("bMaxPacketSize0", ctypes.c_ubyte),
            ("idVendor", ctypes.c_ushort),
            ("idProduct", ctypes.c_ushort),
            ("bcdDevice", ctypes.c_ushort),
            ("iManufacturer", ctypes.c_ubyte),
            ("iProduct", ctypes.c_ubyte),
            ("iSerialNumber", ctypes.c_ubyte),
            ("bNumConfigurations", ctypes.c_ubyte),
        ]

    HID_DESCRIPTOR = bytes.fromhex(
        "05 01 09 06 a1 01 05 07 19 e0 29 e7 15 00 25 01 75 01 "
        "95 08 81 02 95 01 75 08 81 01 95 06 75 08 15 00 25 65 "
        "05 07 19 00 29 65 81 00 c0"
    )

    def __init__(self, serial: str | None = None) -> None:
        self.serial = serial if serial is not None else require_serial()
        self.lib: Any = None
        self.context = ctypes.c_void_p()
        self.handle = ctypes.c_void_p()
        self.devices: Any = None
        self.registered = False

    def __enter__(self) -> "UsbKeyboard":
        if not self.serial:
            raise TrialError("WOWFOREVER_ADB_SERIAL is not set (explicit device selection required)")
        if ctypes.sizeof(self.UsbDescriptor) != 18 or len(self.HID_DESCRIPTOR) != 45:
            raise TrialError("USB descriptor layout invariant failed")
        try:
            self.lib = ctypes.CDLL("libusb-1.0.so.0")
        except OSError as exc:
            raise TrialError(f"libusb-1.0.so.0 unavailable: {exc}") from exc
        self._configure_api()
        rc = self.lib.libusb_init(ctypes.byref(self.context))
        if rc != 0:
            raise TrialError(f"libusb_init failed: {rc}")
        try:
            self._find_device()
            protocol = self._control_in(51, 0, 0, 2)
            if len(protocol) != 2 or int.from_bytes(protocol, "little") != 2:
                raise TrialError("USB device is not already in AOA protocol 2")
            descriptor = (ctypes.c_ubyte * len(self.HID_DESCRIPTOR)).from_buffer_copy(self.HID_DESCRIPTOR)
            self._control_out(54, 0xF071, len(self.HID_DESCRIPTOR), 0, b"")
            self.registered = True
            self._control_out(56, 0xF071, 0, len(self.HID_DESCRIPTOR), descriptor)
            time.sleep(0.4)
            return self
        except Exception:
            self.__exit__(None, None, None)
            raise

    def _configure_api(self) -> None:
        lib = self.lib
        lib.libusb_init.argtypes = [ctypes.POINTER(ctypes.c_void_p)]
        lib.libusb_init.restype = ctypes.c_int
        lib.libusb_exit.argtypes = [ctypes.c_void_p]
        lib.libusb_exit.restype = None
        dev_ptr = ctypes.POINTER(ctypes.c_void_p)
        lib.libusb_get_device_list.argtypes = [ctypes.c_void_p, ctypes.POINTER(dev_ptr)]
        lib.libusb_get_device_list.restype = ctypes.c_long
        lib.libusb_free_device_list.argtypes = [dev_ptr, ctypes.c_int]
        lib.libusb_free_device_list.restype = None
        lib.libusb_get_device_descriptor.argtypes = [ctypes.c_void_p, ctypes.POINTER(self.UsbDescriptor)]
        lib.libusb_get_device_descriptor.restype = ctypes.c_int
        lib.libusb_open.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p)]
        lib.libusb_open.restype = ctypes.c_int
        lib.libusb_close.argtypes = [ctypes.c_void_p]
        lib.libusb_close.restype = None
        lib.libusb_get_string_descriptor_ascii.argtypes = [ctypes.c_void_p, ctypes.c_ubyte, ctypes.POINTER(ctypes.c_ubyte), ctypes.c_int]
        lib.libusb_get_string_descriptor_ascii.restype = ctypes.c_int
        lib.libusb_control_transfer.argtypes = [
            ctypes.c_void_p, ctypes.c_ubyte, ctypes.c_ubyte, ctypes.c_ushort,
            ctypes.c_ushort, ctypes.POINTER(ctypes.c_ubyte), ctypes.c_ushort, ctypes.c_uint,
        ]
        lib.libusb_control_transfer.restype = ctypes.c_int

    def _find_device(self) -> None:
        device_ptr = ctypes.POINTER(ctypes.c_void_p)
        devices = device_ptr()
        count = self.lib.libusb_get_device_list(self.context, ctypes.byref(devices))
        if count < 0:
            raise TrialError(f"libusb_get_device_list failed: {count}")
        self.devices = devices
        for index in range(count):
            device = devices[index]
            descriptor = self.UsbDescriptor()
            if self.lib.libusb_get_device_descriptor(device, ctypes.byref(descriptor)) != 0:
                continue
            if descriptor.idVendor != 0x2717:
                continue
            handle = ctypes.c_void_p()
            if self.lib.libusb_open(device, ctypes.byref(handle)) != 0:
                continue
            serial_buf = (ctypes.c_ubyte * 256)()
            serial_len = self.lib.libusb_get_string_descriptor_ascii(
                handle, descriptor.iSerialNumber, serial_buf, len(serial_buf)
            )
            found = bytes(serial_buf[:serial_len]).decode("ascii", errors="strict") if serial_len >= 0 else ""
            if found == self.serial:
                self.handle = handle
                self.lib.libusb_free_device_list(self.devices, 1)
                self.devices = None
                return
            self.lib.libusb_close(handle)
        self.lib.libusb_free_device_list(self.devices, 1)
        self.devices = None
        raise TrialError("no matching AOA device (VID/serial)")

    def _control(self, direction: int, request: int, value: int, index: int, data: bytes | ctypes.Array[Any], length: int) -> bytes | None:
        if not self.handle:
            raise TrialError("USB keyboard is not open")
        if isinstance(data, bytes):
            buffer = (ctypes.c_ubyte * len(data)).from_buffer_copy(data)
        else:
            buffer = data
        result = self.lib.libusb_control_transfer(
            self.handle, direction, request, value, index,
            ctypes.cast(buffer, ctypes.POINTER(ctypes.c_ubyte)), length, 1000,
        )
        if result < 0 or result != length:
            raise TrialError(f"AOA control transfer request {request} failed: {result}")
        return bytes(buffer[:length]) if direction & 0x80 else None

    def _control_in(self, request: int, value: int, index: int, length: int) -> bytes:
        if not self.handle:
            raise TrialError("USB keyboard is not open")
        buffer = (ctypes.c_ubyte * length)()
        result = self.lib.libusb_control_transfer(
            self.handle, 0xC0, request, value, index,
            buffer, length, 1000,
        )
        if result != length:
            raise TrialError(f"AOA control IN request {request} failed: {result}")
        return bytes(buffer)

    def _control_out(self, request: int, value: int, index: int, length: int, data: bytes | ctypes.Array[Any]) -> None:
        self._control(0x40, request, value, index, data, length)

    def key(self, modifier: int, keycode: int) -> None:
        press = bytes((modifier, 0, keycode, 0, 0, 0, 0, 0))
        release = bytes(8)
        try:
            self._control_out(57, 0xF071, 0, 8, press)
        finally:
            try:
                self._control_out(57, 0xF071, 0, 8, release)
            finally:
                time.sleep(0.08)

    def __exit__(self, _type: object, _value: object, _traceback: object) -> None:
        try:
            if self.registered and self.handle:
                try:
                    self._control_out(55, 0xF071, 0, 0, b"")
                except TrialError:
                    pass
        finally:
            if self.handle:
                self.lib.libusb_close(self.handle)
                self.handle = ctypes.c_void_p()
            if self.devices is not None:
                self.lib.libusb_free_device_list(self.devices, 1)
                self.devices = None
            if self.lib is not None and self.context:
                self.lib.libusb_exit(self.context)
                self.context = ctypes.c_void_p()


def policy_is_nonsecure(adb: Adb, *, deadline: float) -> bool:
    while time.monotonic() < deadline:
        require_no_sensitive_session(adb)
        result = adb.shell("dumpsys", "window", "policy", timeout=8)
        require_no_sensitive_session(adb)
        for line in text(result).splitlines():
            if re.search(r"(?:^|[\s,])(?:mkeyguard)?secure=false(?:$|[\s,])", line.casefold()):
                return True
        time.sleep(0.25)
    return False


@dataclass
class UiState:
    root: ET.Element
    parents: dict[ET.Element, ET.Element]

    def top_is_own(self) -> bool:
        top = self.root if self.root.tag == "node" else next(iter(self.root.iter("node")), None)
        return top is not None and top.attrib.get("package") == PACKAGE

    def hierarchy_is_own(self) -> bool:
        return all(node.attrib.get("package") == PACKAGE for node in self.root.iter("node"))

    def consent(self) -> ET.Element | None:
        for node in self.root.iter("node"):
            if not node.attrib.get("class", "").endswith("CheckBox"):
                continue
            parent = self.parents.get(node)
            if parent is None:
                continue
            labels = {child.attrib.get("text", "") for child in parent.iter("node")}
            if labels.intersection({"Entiendo y autorizo esta descarga", "Entiendo y autorizo la descarga"}):
                return node
        return None

    def prepare(self) -> ET.Element | None:
        for node in self.root.iter("node"):
            if node.attrib.get("enabled") != "true" or node.attrib.get("focused") != "true":
                continue
            current: ET.Element | None = node
            for _ in range(4):  # self plus at most three ancestors
                if current is None:
                    break
                if current is not node:
                    parent_class = current.attrib.get("class", "")
                    if parent_class.endswith("ScrollView") or parent_class.endswith("ComposeView"):
                        break
                if any(child.attrib.get("text") == "Preparar y lanzar" for child in current.iter("node")):
                    if current.attrib.get("clickable") == "true" or node.attrib.get("clickable") == "true":
                        return node
                current = self.parents.get(current)
        return None


def _ui_command_timeout(deadline: float | None, maximum: float = UI_COMMAND_TIMEOUT) -> float:
    if deadline is None:
        return maximum
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TrialError("UI dump deadline reached")
    return min(maximum, remaining)


def _ui_dump_path() -> str:
    return f"{UI_DUMP_PREFIX}{uuid.uuid4().hex}{UI_DUMP_SUFFIX}"


def _remove_ui_dump(adb: Adb, path: str, deadline: float | None) -> bool:
    try:
        timeout = _ui_command_timeout(deadline, UI_DUMP_CLEANUP_TIMEOUT)
        result = adb.shell("rm", "-f", path, timeout=timeout)
    except (OSError, TrialError, ValueError):
        return False
    return result.returncode == 0


def _reject_late_ui_dump(deadline: float | None) -> None:
    if deadline is not None and time.monotonic() >= deadline:
        raise TrialError("UI dump returned after deadline")


def dump_ui(
    adb: Adb,
    *,
    deadline: float | None = None,
    cleanup_deadline: float | None = None,
) -> UiState:
    """Read one fresh UI dump and remove exactly its temporary path."""
    require_no_sensitive_session(adb)
    path = _ui_dump_path()
    if cleanup_deadline is None and deadline is not None:
        cleanup_deadline = deadline + UI_READINESS_CLEANUP_SECONDS
    try:
        if not _remove_ui_dump(adb, path, deadline):
            raise TrialError("UI dump path clear failed")
        dumped = adb.exec_out(
            "uiautomator", "dump", "--compressed", path,
            timeout=_ui_command_timeout(deadline),
        )
        _reject_late_ui_dump(deadline)
        if dumped.returncode != 0:
            raise TrialError("uiautomator dump failed")
        require_no_sensitive_session(adb)
        xml_result = adb.exec_out(
            "cat", path, timeout=_ui_command_timeout(deadline),
        )
        _reject_late_ui_dump(deadline)
        require_no_sensitive_session(adb)
        if xml_result.returncode != 0 or len(xml_result.stdout) > 512 * 1024:
            raise TrialError("UI XML missing, oversized, or unreadable")
        try:
            root = ET.fromstring(xml_result.stdout)
        except ET.ParseError as exc:
            raise TrialError("UI XML malformed") from exc
        parents = {child: parent for parent in root.iter() for child in parent}
        _reject_late_ui_dump(deadline)
        return UiState(root, parents)
    finally:
        if not _remove_ui_dump(adb, path, cleanup_deadline):
            raise TrialError("UI dump cleanup failed")


@dataclass(frozen=True)
class UiReadiness:
    state: UiState | None
    reason: str
    elapsed_ms: int
    dump_count: int


def wait_for_own_ui(
    adb: Adb,
    *,
    journal: Journal | None = None,
    started_at: float | None = None,
    clock: Any = None,
    sleeper: Any = None,
) -> UiReadiness:
    """Poll only UI ownership after activity start, before sending any key."""
    clock = time.monotonic if clock is None else clock
    sleeper = time.sleep if sleeper is None else sleeper
    started = clock() if started_at is None else started_at
    local_deadline = started + UI_READINESS_SECONDS
    global_deadline = getattr(adb, "deadline", None)
    if global_deadline is None:
        effective_deadline = local_deadline
    else:
        effective_deadline = min(
            local_deadline,
            global_deadline - FINAL_CAPTURE_RESERVE_SECONDS,
        )

    dump_count = 0
    last_reason = "not-ready"
    next_dump_at = started

    def finish(state: UiState | None, reason: str) -> UiReadiness:
        elapsed_ms = max(0, int(round((clock() - started) * 1000)))
        result = UiReadiness(state, reason, elapsed_ms, dump_count)
        if journal is not None:
            journal.state(
                "ui-readiness",
                status="ready" if state is not None else "not-ready",
                reason=reason,
                elapsed_ms=elapsed_ms,
                dump_count=dump_count,
            )
        return result

    def deadline_reason(now: float) -> str:
        if global_deadline is not None and now >= global_deadline:
            return "global-deadline"
        if global_deadline is not None and now >= global_deadline - FINAL_CAPTURE_RESERVE_SECONDS:
            return "final-reserve"
        return "local-deadline"

    while dump_count < UI_READINESS_MAX_DUMPS:
        now = clock()
        if now < next_dump_at:
            sleeper(min(next_dump_at - now, max(0.0, effective_deadline - now)))
            now = clock()
        minimum_remaining = UI_READINESS_MIN_COMMAND_SECONDS + UI_READINESS_CLEANUP_SECONDS
        if now + minimum_remaining > effective_deadline:
            if now >= effective_deadline:
                return finish(None, deadline_reason(now))
            return finish(None, deadline_reason(effective_deadline))

        if now >= effective_deadline:
            return finish(None, deadline_reason(now))

        dump_count += 1
        operation_deadline = min(
            now + UI_READINESS_DUMP_BUDGET_SECONDS,
            effective_deadline - UI_READINESS_CLEANUP_SECONDS,
        )
        cleanup_deadline = operation_deadline + UI_READINESS_CLEANUP_SECONDS
        try:
            state = dump_ui(
                adb,
                deadline=operation_deadline,
                cleanup_deadline=cleanup_deadline,
            )
        except TrialError as exc:
            if str(exc) == "UI dump cleanup failed":
                return finish(None, "ui-dump-cleanup-failed")
            elif str(exc) in {"UI dump deadline reached", "UI dump returned after deadline"}:
                last_reason = "ui-dump-late"
            else:
                last_reason = "ui-dump-failed"
        else:
            # The helper's clock is checked independently of dump_ui's command
            # clock.  Cooperative subprocess timeouts cannot make a late UI
            # result valid.
            if clock() >= effective_deadline:
                last_reason = "ui-dump-late"
            elif state.top_is_own():
                return finish(state, "own-top-ready")
            else:
                last_reason = "foreign-top"

        now = clock()
        if now >= effective_deadline:
            return finish(None, deadline_reason(now))
        if dump_count >= UI_READINESS_MAX_DUMPS:
            return finish(None, last_reason)
        next_dump_at += UI_READINESS_DUMP_SPACING_SECONDS

    return finish(None, last_reason)


def verify_top(state: UiState) -> None:
    if not state.top_is_own():
        raise TrialError("UI top node is not the own package")


def verify_capture_ui(state: UiState) -> None:
    """Stricter screenshot guard; do not change the existing consent/UI flow."""
    verify_top(state)
    if not state.hierarchy_is_own():
        raise TrialError("UI hierarchy contains a foreign or unknown package")


def send_ui_key(keyboard: UsbKeyboard, keycode: int) -> None:
    keyboard.key(0, keycode)
    time.sleep(0.15)


def sanitize_json(value: Any, *, key: str = "", depth: int = 0, budget: list[int] | None = None) -> Any:
    if budget is None:
        budget = [200]
    if budget[0] <= 0 or depth > 6:
        return "[omitted]"
    budget[0] -= 1
    if SENSITIVE_RE.search(key):
        return "[redacted]"
    if isinstance(value, dict):
        return {
            str(k): sanitize_json(v, key=str(k), depth=depth + 1, budget=budget)
            for k, v in list(value.items())[:80]
            if not SENSITIVE_RE.search(str(k))
        }
    if isinstance(value, list):
        return [sanitize_json(v, key=key, depth=depth + 1, budget=budget) for v in value[:80]]
    if isinstance(value, str):
        return value[:512]
    if isinstance(value, (bool, int, float)) or value is None:
        return value
    return str(value)[:128]


def read_private_bytes(adb: Adb, path: str, cap: int = PRIVATE_JSON_CAP) -> tuple[bytes | None, str]:
    require_no_sensitive_session(adb)
    result = adb.exec_out("run-as", PACKAGE, "head", "-c", str(cap + 1), path, timeout=45)
    require_no_sensitive_session(adb)
    if result.returncode != 0 or len(result.stdout) > cap:
        return None, "missing" if not result.stdout else "oversized-or-unreadable"
    return result.stdout, "ok" if result.stdout else "missing"


def decode_part(value: Any, limit: int) -> tuple[str, bytes] | None:
    if isinstance(value, dict):
        value = value.get("base64")
    if not isinstance(value, str):
        return None
    try:
        decoded = base64.b64decode(value, validate=True)
    except (ValueError, binascii.Error):
        return None
    if len(decoded) > limit or base64.b64encode(decoded).decode("ascii") != value:
        return None
    return value, decoded


def stream_part(payload: dict[str, Any], name: str) -> Any:
    for key in (f"{name}Base64", f"{name}_base64", name):
        if key in payload:
            return payload[key]
    return None


def save_run_diagnostics(adb: Adb, run_dir: Path, run_id: str, journal: Journal) -> None:
    require_no_sensitive_session(adb)
    require_no_sensitive_run(adb, run_id)
    before, _ = read_current(adb)
    if before is None or before.run_id != run_id:
        journal.state("run-diagnostics-missing", status="current-run-mismatch")
        return
    raw, status = read_private_bytes(adb, f"{RUN_RECORD_ROOT}/{run_id}-diagnostics.jsonl", 4 * 1024 * 1024)
    if raw is None:
        journal.state("run-diagnostics-missing", status=status)
        return
    lines = [line for line in raw.splitlines() if line.strip()]
    try:
        records = [json.loads(line) for line in lines]
    except json.JSONDecodeError:
        journal.state("run-diagnostics-missing", status="malformed")
        return
    after, _ = read_current(adb)
    if after is None or after.run_id != run_id or not all(
        isinstance(record, dict) and ("runId" not in record or record["runId"] == run_id)
        for record in records
    ):
        journal.state("run-diagnostics-missing", status="runId-mismatch")
        return
    write_capture_bytes(adb, run_dir / "run-diagnostics.jsonl", raw, run_id=run_id)
    journal.state("run-diagnostics", bytes=len(raw), records=len(records))


def bounded_streams(adb: Adb, run_dir: Path, current: CurrentRun, journal: Journal) -> None:
    require_no_sensitive_session(adb)
    require_no_sensitive_run(adb, current.run_id)
    statuses: dict[str, object] = {"runId": current.run_id, "streams": {}}
    total_bytes = 0
    for stream_name in ("stdout", "stderr"):
        path = f"{RUN_RECORD_ROOT}/{current.run_id}/{current.run_id}-{stream_name}-snapshot.json"
        raw, status = read_private_bytes(adb, path, MAX_STREAM_JSON)
        if raw is None:
            statuses["streams"][stream_name] = {"status": status}  # type: ignore[index]
            continue
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            statuses["streams"][stream_name] = {"status": "malformed"}  # type: ignore[index]
            continue
        if not isinstance(payload, dict) or payload.get("runId") != current.run_id or payload.get("stream") != stream_name:
            statuses["streams"][stream_name] = {"status": "runId-or-stream-mismatch"}  # type: ignore[index]
            continue
        head = decode_part(stream_part(payload, "head"), STREAM_HEAD_BYTES)
        tail = decode_part(stream_part(payload, "tail"), STREAM_TAIL_BYTES)
        if head is None or tail is None or total_bytes + len(head[1]) + len(tail[1]) > MAX_STREAM_TOTAL_RAW:
            statuses["streams"][stream_name] = {"status": "invalid-or-over-budget"}  # type: ignore[index]
            continue
        total_bytes += len(head[1]) + len(tail[1])
        output = payload  # Preserve total/truncation/capture/flush evidence as well as bytes.
        encoded = json.dumps(output, sort_keys=True).encode("utf-8") + b"\n"
        if len(encoded) > MAX_STREAM_JSON:
            statuses["streams"][stream_name] = {"status": "json-over-budget"}  # type: ignore[index]
            continue
        write_capture_bytes(adb, run_dir / f"{stream_name}-snapshot.json", encoded, run_id=current.run_id)
        statuses["streams"][stream_name] = {"status": "saved", "bytes": len(head[1]) + len(tail[1])}  # type: ignore[index]
    write_capture_json(adb, run_dir / "stream-status.json", statuses)
    journal.state("stream-snapshots", bytes=total_bytes)


def _is_uint(value: object) -> bool:
    return type(value) is int and 0 <= value <= 0xFFFFFFFF


def _is_hresult(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch(r"0x[0-9A-Fa-f]{8}", value) is not None


def _valid_samples(samples: object) -> bool:
    points = ((0, 0), (160, 120), (319, 239), (80, 60), (240, 180))
    return (isinstance(samples, list) and len(samples) == len(points) and all(
        isinstance(sample, dict) and type(sample.get("x")) is int and type(sample.get("y")) is int and
        (sample["x"], sample["y"]) == point and isinstance(sample.get("rgba"), list) and
        len(sample["rgba"]) == 4 and all(type(channel) is int and 0 <= channel <= 255 for channel in sample["rgba"])
        for sample, point in zip(samples, points)))


def _validate_readback_phases(records: list[dict[str, Any]], *, require_variant: bool) -> bool:
    begin = records[0]
    if require_variant and begin.get("variant") != "readback-v1":
        raise TrialError("readback variant marker is missing")
    expected = ["offscreen_clear", "offscreen_draw", "backbuffer_clear", "backbuffer_draw"]
    phases = [item for item in records if item.get("phase") in expected]
    windows = [item for item in records if item.get("event") == "readback_window"]
    if windows or phases or records[-1].get("status") == "PASS":
        if ([item.get("status") for item in windows] != ["READY", "RESUMED"] or
                any(item.get("marker") != "modules-d3d-shaders-ready" for item in windows) or
                (phases and records.index(windows[-1]) >= records.index(phases[0]))):
            raise TrialError("readback window markers are absent, duplicated or out of order")
    if not phases:
        return False
    names = [item["phase"] for item in phases]
    if names != expected[:len(names)] or len(set(names)) != len(names):
        raise TrialError("readback phases are duplicated or out of order")
    if len(phases) < len(expected):
        if any(item.get("status") != "TIMEOUT" for item in phases[-1:]):
            raise TrialError("readback phase prefix ended without an explicit timeout")
        return False
    if any(item.get("event") != ("triangle_readback" if name == "backbuffer_draw" else "readback")
           for item, name in zip(phases, expected)):
        raise TrialError("readback phase event type is contradictory")
    for item, phase in zip(phases, expected):
        for key in ("textureResult", "rtvResult", "stagingResult", "mapResult", "copyResult", "deviceRemovedReason"):
            if key not in item:
                raise TrialError(f"readback {phase} lacks {key}")
        if item.get("status") == "VERIFIED":
            if (item.get("mapResult") not in {"S_OK", "0x00000000"} or item.get("pDataValid") is not True or
                    type(item.get("rowPitch")) is not int or item["rowPitch"] < 1280 or
                    type(item.get("width")) is not int or item["width"] != 320 or
                    type(item.get("height")) is not int or item["height"] != 240 or item.get("copyResult") != "VOID_SUBMITTED" or
                    any(item.get(key) != "0x00000000" for key in ("textureResult", "rtvResult", "stagingResult")) or
                    not all(_is_hresult(item.get(key)) for key in ("textureResult", "rtvResult", "stagingResult", "mapResult", "deviceRemovedReason"))):
                raise TrialError(f"verified readback {phase} lacks coherent Map/copy/HRESULT evidence")
            if (not all(_is_uint(item.get(key)) for key in ("clearPixels", "coloredPixels", "rgbaFnv1a")) or
                    item.get("clearPixels", 0) + item.get("coloredPixels", 0) > 320 * 240):
                raise TrialError(f"verified readback {phase} pixel fields are malformed")
            if not _valid_samples(item.get("samples")):
                raise TrialError(f"verified readback {phase} samples are malformed")
            if phase.endswith("clear") and (item["clearPixels"] != 320 * 240 or item["coloredPixels"] != 0):
                raise TrialError(f"clear readback {phase} is not uniform")
            if phase.endswith("draw") and item["coloredPixels"] < 128:
                raise TrialError(f"draw readback {phase} lacks 128 colored pixels")
        elif item.get("status") not in {"FAILED", "TIMEOUT"}:
            raise TrialError(f"readback {phase} status is untyped")
    reasons = [item for item in records if item.get("event") == "device_removed_reason"]
    if not reasons and records[-1].get("status") != "PASS" and any(
            item.get("event") == "probe_budget" and item.get("status") == "TIMEOUT" for item in records):
        return False
    if (len(reasons) != 1 or records.index(reasons[0]) <= records.index(phases[-1]) or
            not _is_hresult(reasons[0].get("result"))):
        raise TrialError("post-readback DeviceRemovedReason witness is missing or malformed")
    presents = [item for item in records if item.get("event") == "present"]
    if presents and records.index(reasons[0]) >= records.index(presents[0]):
        raise TrialError("DeviceRemovedReason witness follows the first Present")
    return (all(item.get("status") == "VERIFIED" and item.get("deviceRemovedReason") == "0x00000000" for item in phases)
            and reasons[0].get("result") == "0x00000000")


def validate_graphics_log(raw: bytes, run_id: str, expected_probe: ProbeIdentity | None = None) -> list[dict[str, Any]]:
    """Validate a complete measurement, not a renderer/gameplay acceptance."""
    if not RUN_RE.fullmatch(run_id) or len(run_id) > 100:
        raise TrialError("invalid graphics RUN")
    if not raw or len(raw) > MAX_GRAPHICS_LOG_BYTES or not raw.endswith(b"\n"):
        raise TrialError("missing, oversized or truncated graphics log")

    def unique_object(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise TrialError("duplicate graphics JSON field")
            value[key] = item
        return value

    lines = raw.decode("utf-8", errors="strict").splitlines()
    if not 2 <= len(lines) <= 64 or any(not line or len(line.encode("utf-8")) > 8192 for line in lines):
        raise TrialError("graphics record bounds exceeded")
    records = [json.loads(line, object_pairs_hook=unique_object) for line in lines]
    if any(not isinstance(item, dict) or type(item.get("schema")) is not int or item["schema"] != 1
           or item.get("runId") != run_id or not isinstance(item.get("event"), str) for item in records):
        raise TrialError("graphics schema or RUN mismatch")
    if (records[0].get("event") != "probe_begin" or records[0].get("status") != "STARTED"
            or records[-1].get("event") != "probe_end"
            or sum(item["event"] == "probe_begin" for item in records) != 1
            or sum(item["event"] == "probe_end" for item in records) != 1):
        raise TrialError("missing or contradictory graphics terminal")
    terminal = records[-1]
    if terminal.get("status") == "PASS" and any(
            item.get("event") == "probe_budget" and item.get("status") == "TIMEOUT" for item in records):
        raise TrialError("graphics PASS contradicts a probe budget timeout")
    if terminal.get("status") not in {"PASS", "UNKNOWN", "FAILED", "DEVICE_REJECTED"}:
        raise TrialError("unknown graphics terminal status")
    readback_variant = expected_probe == READBACK_PROBE or records[0].get("variant") == "readback-v1"
    phases_complete = _validate_readback_phases(records, require_variant=expected_probe == READBACK_PROBE) if readback_variant else False
    if terminal.get("status") == "PASS" and not (
            terminal.get("d3dTriangleResult") is True and terminal.get("logDurable") is True
            and type(terminal.get("successfulPresents")) is int and terminal["successfulPresents"] == 30
            and [item.get("index") for item in records if item.get("event") == "present"] == list(range(1, 31))
            and all(item.get("successful") is True and item.get("result") in {"S_OK", "0x00000000"}
                    for item in records if item.get("event") == "present")
            and (not readback_variant or phases_complete)):
        raise TrialError("graphics PASS lacks triangle/presentation evidence")
    return records


def collect_graphics_probe(adb: Adb, run_dir: Path, current: CurrentRun, journal: Journal, expected_probe: ProbeIdentity | None = None) -> bool:
    """Read only this RUN's fixed external log; preserve failure as missing evidence."""
    require_no_sensitive_session(adb)
    require_no_sensitive_run(adb, current.run_id)
    before, before_status = read_current(adb)
    if before is None or before.run_id != current.run_id:
        journal.state("graphics-log-missing", status="current-run-mismatch", observed=before_status)
        return False
    run_id = current.run_id
    if not RUN_RE.fullmatch(run_id) or len(run_id) > 100:
        raise TrialError("invalid graphics RUN")
    logs = f"/storage/emulated/0/Android/data/{PACKAGE}/CustomGames/WoW Forever/Logs"
    path = f"{logs}/{run_id}-graphics-probe.jsonl"
    command = (f"test ! -L {shlex.quote(logs)} && test ! -L {shlex.quote(path)} && "
               + shlex.join(["head", "-c", str(MAX_GRAPHICS_LOG_BYTES + 1), path]))
    result = adb.exec_out("sh", "-c", command, timeout=20)
    require_no_sensitive_session(adb)
    try:
        if result.returncode:
            raise TrialError("graphics file unreadable")
        records = validate_graphics_log(result.stdout, run_id, expected_probe)
    except (TrialError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        journal.state("graphics-log-missing", status="invalid-or-missing", error=str(exc))
        return False
    after, after_status = read_current(adb)
    if after is None or after.run_id != run_id:
        journal.state("graphics-log-missing", status="run-changed-during-read", observed=after_status)
        return False
    try:
        write_capture_bytes(adb, run_dir / "graphics-probe.jsonl", result.stdout,
                            exclusive=True, run_id=run_id)
    except FileExistsError:
        journal.state("graphics-log-not-overwritten", run_id=run_id)
        return False
    journal.state("graphics-log-saved", run_id=run_id, bytes=len(result.stdout),
                  sha256=sha256(result.stdout), records=len(records),
                  reported_probe_status=records[-1]["status"],
                  successful_presents=records[-1].get("successfulPresents"),
                  gameplay_acceptance=False)
    return True


def collect_panvk_driver_identity(adb: Adb, run_dir: Path, current: CurrentRun, journal: Journal) -> bool:
    """File identity only, distinct from the live Wine mapping proof."""
    require_no_sensitive_session(adb)
    require_no_sensitive_run(adb, current.run_id)
    before, _ = read_current(adb)
    if before is None or before.run_id != current.run_id:
        return False
    path = f"files/forever/controls/panvk-wine/{current.run_id}/libvulkan_panfrost.so"
    command = (f"test -f {shlex.quote(path)} && test ! -L {shlex.quote(path)} && "
               f"stat -c '%s %u %a %d %i %Y' {shlex.quote(path)} && "
               f"sha256sum {shlex.quote(path)} && "
               f"stat -c '%s %u %a %d %i %Y' {shlex.quote(path)}")
    result = adb.exec_out("run-as", PACKAGE, "sh", "-c", command, timeout=30)
    require_no_sensitive_session(adb)
    try:
        lines = result.stdout.decode("ascii", "strict").splitlines()
        if result.returncode or result.stderr or len(lines) != 3 or lines[0] != lines[2]:
            raise ValueError("incomplete or unstable driver file")
        size, uid, mode, device, inode, modified = lines[0].split()
        digest = lines[1].split()[0]
        if (int(size) != 31_142_504 or int(uid) != UID or mode != "600" or
                digest != "5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9" or
                int(inode) <= 0 or int(device) <= 0):
            raise ValueError("driver does not match the approved private pin")
        after, _ = read_current(adb)
        if after is None or after.run_id != current.run_id:
            raise ValueError("current RUN changed")
        identity = {"schema": 1, "runId": current.run_id,
                    "path": f"/data/user/0/{PACKAGE}/{path}",
                    "size": int(size), "uid": int(uid), "mode": "0600", "sha256": digest,
                    "beforeAfterStable": True, "device": int(device), "inode": int(inode),
                    "mtime": int(modified), "mappingVerified": False}
        target = run_dir / "driver-identity.json"
        write_capture_json(adb, target, identity, run_id=current.run_id)
        journal.state("panvk-file-identity", run_id=current.run_id, mapping_verified=False)
        return True
    except (UnicodeError, ValueError, IndexError, OSError) as exc:
        journal.state("panvk-file-identity-missing", reason=str(exc)[:256])
        return False


def collect_probe_identity(
    adb: Adb,
    run_dir: Path,
    current: CurrentRun,
    journal: Journal,
    expected: ProbeIdentity = LEGACY_PROBE,
) -> bool:
    require_no_sensitive_session(adb)
    require_no_sensitive_run(adb, current.run_id)
    before, _ = read_current(adb)
    if before is None or before.run_id != current.run_id:
        return False
    path = f"/storage/emulated/0/Android/data/{PACKAGE}/CustomGames/WoW Forever/{expected.file}"
    command = (f"test -f {shlex.quote(path)} && test ! -L {shlex.quote(path)} && "
               f"stat -c '%s %i %Y' {shlex.quote(path)} && sha256sum {shlex.quote(path)} && "
               f"stat -c '%s %i %Y' {shlex.quote(path)}")
    result = adb.shell(command, timeout=10)
    require_no_sensitive_session(adb)
    lines = result.stdout.decode("ascii", "strict").splitlines()
    if result.returncode or result.stderr or len(lines) != 3 or lines[0] != lines[2]:
        return False
    size, inode, modified = lines[0].split()
    digest = lines[1].split()[0]
    after, _ = read_current(adb)
    if (int(size) != expected.size or int(inode) <= 0 or digest != expected.sha256 or
            after is None or after.run_id != current.run_id):
        return False
    write_capture_json(adb, run_dir / "probe-identity.json", {"schema": 1, "runId": current.run_id,
               "path": path, "file": expected.file, "size": int(size),
               "sha256": digest, "beforeAfterStable": True, "inode": int(inode), "mtime": int(modified)},
                       run_id=current.run_id)
    journal.state("probe-file-identity", run_id=current.run_id)
    return True


def parse_ps(raw: str) -> list[tuple[int, int, int, str]]:
    return prioritize_processes([row for row, _truncated in parse_ps_records(raw)], MAX_PROCESSES_PER_SAMPLE)


def parse_ps_records(raw: str) -> list[tuple[tuple[int, int, int, str], bool]]:
    """Parse only already-filtered ps text and retain NAME truncation evidence."""
    result: list[tuple[tuple[int, int, int, str], bool]] = []
    for line in raw.splitlines():
        parts = line.split(None, 3)
        if len(parts) != 4 or not parts[0].isdigit() or not parts[1].isdigit() or not parts[2].isdigit():
            continue
        if int(parts[0]) != UID:
            continue
        result.append(((int(parts[0]), int(parts[1]), int(parts[2]), parts[3][:128]), len(parts[3]) > 128))
    return [item for _index, item in sorted(
        enumerate(result), key=lambda item: (process_priority(item[1][0]), item[0])
    )[:MAX_PROCESSES_PER_SAMPLE]]


def ps_header_status(line: bytes) -> str:
    tokens = line.rstrip(b"\r\n").split()
    if len(line) > MAX_PS_HEADER_BYTES:
        return "header-oversized"
    if tokens == [b"UID", b"PID", b"PPID", b"NAME"]:
        return "header-valid"
    return "header-invalid"


def filter_own_ps_details(raw: bytes) -> tuple[bytes, str]:
    """Keep only an exact intended header and exact rows for this UID."""
    lines = raw.splitlines(keepends=True)
    if not lines:
        return b"", "header-missing"
    uid_prefix = str(UID).encode("ascii")
    first = lines[0]
    header_status = ps_header_status(first)
    header = first if header_status == "header-valid" else b""
    rows = [line for line in lines[1:] if line.lstrip().split(None, 1)[:1] == [uid_prefix]]
    if not header:
        rows = [line for line in lines if line.lstrip().split(None, 1)[:1] == [uid_prefix]]
    return header + b"".join(rows), header_status


def filter_own_ps(raw: bytes) -> bytes:
    """Compatibility wrapper for the ownership-filtered PS bytes."""
    return filter_own_ps_details(raw)[0]


def bounded_ps_bytes(value: bytes, limit: int = MAX_PS_SAMPLE_BYTES) -> tuple[bytes, bool]:
    """Bound an already ownership-filtered PS stream without adding foreign rows."""
    if len(value) <= limit:
        return value, False
    lines = value.splitlines(keepends=True)
    if not lines:
        return b"", True
    header = lines[0] if ps_header_status(lines[0]) == "header-valid" else b""
    kept = bytearray(header)
    for line in lines[1:] if header else lines:
        if len(kept) + len(line) > limit:
            break
        kept.extend(line)
    return bytes(kept), True


def append_jsonl(path: Path, value: object) -> None:
    with path.open("a", encoding="utf-8") as stream:
        os.fchmod(stream.fileno(), 0o600)
        stream.write(json.dumps(value, sort_keys=True) + "\n")
        stream.flush()
        os.fsync(stream.fileno())


def record_process_observation(run_dir: Path, value: dict[str, Any]) -> None:
    append_jsonl(run_dir / "process-observations.jsonl", value)


def record_ps_metadata(run_dir: Path, sample: int, *, start: str, end: str,
                       parameters: Sequence[str], status: str, own_bytes: int = 0,
                       saved_bytes: int = 0, truncated: bool = False, own_rows: int = 0) -> None:
    append_jsonl(run_dir / "ps-observations.jsonl", {
        "schema": 1, "sample": sample, "start": start, "end": end,
        "parameters": list(parameters), "own_uid": UID, "status": status,
        "own_bytes": own_bytes, "saved_bytes": saved_bytes,
        "truncated": truncated, "own_rows": own_rows,
    })


def save_ps_capture(run_dir: Path, sample: int, raw: bytes, *, start: str, end: str,
                    parameters: Sequence[str], adb: Adb | None = None,
                    run_id: str | None = None) -> tuple[str, bytes, bool] | None:
    """Persist bounded own-only PS bytes and its non-content capture metadata."""
    if adb is not None:
        require_no_sensitive_session(adb)
    if sample >= MAX_PS_SAMPLES:
        return None
    own, header_status = filter_own_ps_details(raw)
    bounded, truncated = bounded_ps_bytes(own)
    existing = sum(path.stat().st_size for path in run_dir.glob("ps-raw-*.txt"))
    if existing + len(bounded) > MAX_PS_TOTAL_BYTES:
        return None
    path = run_dir / f"ps-raw-{sample:02d}.txt"
    if adb is None:
        write_bytes(path, bounded)
    else:
        write_capture_bytes(adb, path, bounded, run_id=run_id)
    record_ps_metadata(run_dir, sample, start=start, end=end, parameters=parameters,
        status="saved" if header_status == "header-valid" else header_status,
        own_bytes=len(own), saved_bytes=len(bounded), truncated=truncated, own_rows=sum(
            1 for line in own.splitlines()
            if line.lstrip().split(None, 1)[:1] == [str(UID).encode("ascii")]
        ))
    return "saved", own, truncated


def sampling_timeout(adb: Adb, requested: float) -> float | None:
    """Return a timeout that cannot consume the final diagnostic reserve."""
    if adb.deadline is None:
        return requested
    remaining = adb.deadline - time.monotonic() - FINAL_CAPTURE_RESERVE_SECONDS
    return min(requested, remaining) if remaining > 0 else None


def sample_interval(root_spawned_at: float, *, now: float | None = None) -> float:
    elapsed = (time.monotonic() if now is None else now) - root_spawned_at
    return (EARLY_SAMPLE_INTERVAL_SECONDS if elapsed < EARLY_ROOT_SAMPLING_SECONDS
            else REGULAR_SAMPLE_INTERVAL_SECONDS)


def process_priority(row: tuple[int, int, int, str]) -> int:
    name = row[3].casefold()
    if name.startswith(GRAPHICS_PROCESS_PREFIX):
        return 0
    if name in RUNTIME_PROCESS_NAMES:
        return 1
    return 2


def prioritize_processes(
    rows: Sequence[tuple[int, int, int, str]],
    limit: int = MAX_PROCESSES_PER_SAMPLE,
) -> list[tuple[int, int, int, str]]:
    """Prioritize own probe/runtime comm names without treating names as proof."""
    return [row for _index, row in sorted(
        enumerate(rows), key=lambda item: (process_priority(item[1]), item[0])
    )[:limit]]


def proc_stat(adb: Adb, pid: int, *, reserve: float = 0) -> tuple[int, int, int] | None:
    timeout = sampling_timeout(adb, 5) if reserve else 5
    if timeout is None:
        return None
    require_no_sensitive_session(adb)
    result = adb.exec_out("run-as", PACKAGE, "cat", f"/proc/{pid}/stat", timeout=timeout)
    require_no_sensitive_session(adb)
    if result.returncode != 0:
        return None
    raw = text(result)
    close = raw.rfind(") ")
    if close < 0:
        return None
    fields = raw[close + 2 :].split()
    try:
        observed = int(raw.split(" (", 1)[0])
        parent, ticks = int(fields[1]), int(fields[19])
        return (pid, ticks, parent) if observed == pid and ticks > 0 and parent >= 0 else None
    except (IndexError, ValueError):
        return None


def proc_uid(adb: Adb, pid: int, *, reserve: float = 0) -> tuple[bool, list[int]]:
    timeout = sampling_timeout(adb, 5) if reserve else 5
    if timeout is None:
        return False, []
    require_no_sensitive_session(adb)
    result = adb.exec_out("run-as", PACKAGE, "cat", f"/proc/{pid}/status", timeout=timeout)
    require_no_sensitive_session(adb)
    if result.returncode != 0 or result.stderr:
        return False, []
    values: list[int] = []
    for line in text(result).splitlines():
        if line.startswith("Uid:"):
            values = [int(part) for part in line.split()[1:5] if part.isdigit()]
            break
    return len(values) == 4 and all(item == UID for item in values), values


def process_snapshot(adb: Adb, run_dir: Path, journal: Journal, sample: int, seen: dict[int, int],
                     run_id: str | None = None) -> int:
    require_no_sensitive_session(adb)
    if run_id is not None:
        require_no_sensitive_run(adb, run_id)

    def save_observation(value: dict[str, Any]) -> None:
        append_capture_jsonl(adb, run_dir / "process-observations.jsonl", value, run_id=run_id)

    def save_metadata(*, start: str, end: str, parameters: Sequence[str], status: str,
                      own_bytes: int = 0, saved_bytes: int = 0, truncated: bool = False,
                      own_rows: int = 0) -> None:
        require_no_sensitive_session(adb)
        record_ps_metadata(run_dir, sample, start=start, end=end, parameters=parameters, status=status,
                           own_bytes=own_bytes, saved_bytes=saved_bytes, truncated=truncated, own_rows=own_rows)

    ps_parameters = ("ps", "-A", "-o", "UID,PID,PPID,NAME")
    if sample >= MAX_PS_SAMPLES:
        reason = "raw-ps-sample-cap"
        now = utc_now()
        save_metadata(start=now, end=now, parameters=ps_parameters, status=reason)
        save_observation({
            "schema": 1, "sample": sample, "uid": UID, "selected": False, "reason": reason,
        })
        journal.state("process-raw-missing", sample=sample, reason=reason)
        return 0
    if sum(path.stat().st_size for path in run_dir.glob("ps-raw-*.txt")) >= MAX_PS_TOTAL_BYTES:
        reason = "raw-ps-total-cap"
        now = utc_now()
        save_metadata(start=now, end=now, parameters=ps_parameters, status=reason)
        save_observation({
            "schema": 1, "sample": sample, "uid": UID, "selected": False, "reason": reason,
        })
        journal.state("process-raw-missing", sample=sample, reason=reason)
        return 0
    ps_timeout = sampling_timeout(adb, 10)
    if ps_timeout is None:
        now = utc_now()
        save_metadata(start=now, end=now, parameters=ps_parameters, status="final-reserve")
        journal.state("process-snapshot-missing", sample=sample, reason="final-reserve")
        save_observation({
            "schema": 1, "sample": sample, "uid": UID, "selected": False,
            "reason": "final-reserve", "parameters": ["ps", "-A", "-o", "UID,PID,PPID,NAME"],
        })
        return 0
    ps_start = utc_now()
    require_no_sensitive_session(adb)
    ps = adb.shell("ps", "-A", "-o", "UID,PID,PPID,NAME", timeout=ps_timeout)
    ps_end = utc_now()
    require_no_sensitive_session(adb)
    if ps.returncode != 0:
        save_metadata(start=ps_start, end=ps_end, parameters=ps_parameters, status="ps-error")
        journal.state("process-snapshot-missing", sample=sample, reason="ps-error")
        save_observation({
            "schema": 1, "sample": sample, "uid": UID, "selected": False,
            "reason": "ps-error", "parameters": ["ps", "-A", "-o", "UID,PID,PPID,NAME"],
            "start": ps_start, "end": ps_end,
        })
        return 0
    raw = ps.stdout
    capture = save_ps_capture(
        run_dir, sample, raw, start=ps_start, end=ps_end,
        parameters=ps_parameters, adb=adb, run_id=run_id,
    )
    if capture is None:
        reason = "raw-ps-sample-cap" if sample >= MAX_PS_SAMPLES else "raw-ps-total-cap"
        save_metadata(start=ps_start, end=ps_end, parameters=ps_parameters, status=reason)
        save_observation({
            "schema": 1, "sample": sample, "uid": UID, "selected": False,
            "reason": reason, "start": ps_start, "end": ps_end,
        })
        journal.state("process-raw-missing", sample=sample, reason=reason)
        return 0
    _capture_status, owned_raw, _raw_truncated = capture
    saved = 0
    candidates = parse_ps_records(owned_raw.decode("utf-8", errors="replace"))
    # Android may keep NAME=wine after a PE changes its comm. Within each hint
    # priority, sample fresh candidates before repeating older observations.
    # This only schedules reads; names/PIDs/counts never establish ownership.
    candidates.sort(key=lambda item: (process_priority(item[0]), seen.get(item[0][1], 0)))
    eligible: list[tuple[tuple[int, int, int, str], bool]] = []
    for row, name_truncated in candidates:
        _uid, pid, ppid, name = row
        base_observation = {
            "schema": 1, "sample": sample, "uid": UID, "pid": pid, "ppid": ppid,
            "name": name, "name_truncated": name_truncated,
            "priority": process_priority(row), "seen": seen.get(pid, 0),
        }
        if sum(seen.values()) >= MAX_PROCESS_SNAPSHOTS:
            save_observation({**base_observation, "selected": False, "reason": "global-cap"})
        elif seen.get(pid, 0) >= 3:
            save_observation({**base_observation, "selected": False, "reason": "seen-quota"})
        elif len(eligible) >= MAX_PROCESS_CANDIDATES_PER_SAMPLE:
            save_observation({**base_observation, "selected": False, "reason": "selection-cap"})
        else:
            eligible.append((row, name_truncated))
            save_observation({**base_observation, "selected": True, "selection_index": len(eligible) - 1})

    for row, name_truncated in eligible:
        _uid, pid, ppid, name = row
        if sum(seen.values()) >= MAX_PROCESS_SNAPSHOTS:
            save_observation({
                "schema": 1, "sample": sample, "uid": UID, "pid": pid, "ppid": ppid,
                "name": name, "name_truncated": name_truncated,
                "priority": process_priority(row), "selected": False, "reason": "global-cap",
            })
            break
        if sampling_timeout(adb, 5) is None:
            save_observation({
                "schema": 1, "sample": sample, "uid": UID, "pid": pid, "ppid": ppid,
                "name": name, "name_truncated": name_truncated,
                "priority": process_priority(row), "selected": True, "reason": "final-reserve",
            })
            break
        before = proc_stat(adb, pid, reserve=FINAL_CAPTURE_RESERVE_SECONDS)
        uid_ok_before, _uid_values_before = proc_uid(adb, pid, reserve=FINAL_CAPTURE_RESERVE_SECONDS)
        base_observation = {
            "schema": 1, "sample": sample, "uid": UID, "pid": pid, "ppid": ppid,
            "name": name, "name_truncated": name_truncated,
            "priority": process_priority(row), "selected": True,
            "stat_before_available": before is not None, "stat_before_uid_verified": uid_ok_before,
            "uid_before": [UID] * 4 if uid_ok_before else None,
        }
        if before is not None and uid_ok_before:
            base_observation["stat_before"] = {
                "pid": before[0], "start_ticks": before[1], "ppid": before[2],
            }
        if before is None:
            save_observation({**base_observation, "reason": "stat-before-error"})
        if not uid_ok_before:
            save_observation({**base_observation, "reason": "uid-before-error"})
        if before is None or not uid_ok_before:
            continue
        maps_timeout = sampling_timeout(adb, 15)
        if maps_timeout is None:
            save_observation({**base_observation, "reason": "final-reserve",
                                                 "maps_returncode": None, "maps_bytes": 0,
                                                 "maps_truncated": False})
            break
        require_no_sensitive_session(adb)
        maps = adb.exec_out("run-as", PACKAGE, "head", "-c", str(MAX_MAP_BYTES + 1),
                            f"/proc/{pid}/maps", timeout=maps_timeout)
        require_no_sensitive_session(adb)
        maps_observation = {
            "maps_returncode": maps.returncode,
            "maps_bytes": len(maps.stdout),
            "maps_truncated": len(maps.stdout) > MAX_MAP_BYTES,
        }
        if maps.returncode != 0 or maps.stderr:
            save_observation({**base_observation, **maps_observation, "reason": "maps-error"})
            continue
        if len(maps.stdout) > MAX_MAP_BYTES:
            save_observation({**base_observation, **maps_observation, "reason": "maps-truncated"})
            continue
        cmdline_timeout = sampling_timeout(adb, 10)
        if cmdline_timeout is None:
            save_observation({**base_observation, **maps_observation,
                                                 "reason": "final-reserve", "cmdline_returncode": None,
                                                 "cmdline_bytes": 0, "cmdline_truncated": False})
            break
        require_no_sensitive_session(adb)
        cmdline = adb.exec_out("run-as", PACKAGE, "head", "-c", str(MAX_CMDLINE_BYTES + 1),
                               f"/proc/{pid}/cmdline", timeout=cmdline_timeout)
        require_no_sensitive_session(adb)
        cmdline_observation = {
            "cmdline_returncode": cmdline.returncode,
            "cmdline_bytes": len(cmdline.stdout),
            "cmdline_truncated": len(cmdline.stdout) > MAX_CMDLINE_BYTES,
        }
        if cmdline.returncode != 0 or cmdline.stderr:
            save_observation({**base_observation, **maps_observation,
                                                 **cmdline_observation, "reason": "cmdline-error"})
            continue
        if len(cmdline.stdout) > MAX_CMDLINE_BYTES:
            save_observation({**base_observation, **maps_observation,
                                                 **cmdline_observation, "reason": "cmdline-truncated"})
            continue
        cmd = cmdline.stdout.split(b"\0", 1)[0].decode("utf-8", errors="replace")
        base = os.path.basename(cmd)
        allowed_base = base if base.casefold() in ALLOWED_PROCESS_BASES else None
        after = proc_stat(adb, pid, reserve=FINAL_CAPTURE_RESERVE_SECONDS)
        uid_ok_after, _uid_values_after = proc_uid(adb, pid, reserve=FINAL_CAPTURE_RESERVE_SECONDS)
        base_observation["stat_after_available"] = after is not None
        base_observation["stat_after_uid_verified"] = uid_ok_after
        base_observation["uid_after"] = [UID] * 4 if uid_ok_after else None
        if after is not None and uid_ok_after:
            base_observation["stat_after"] = {
                "pid": after[0], "start_ticks": after[1], "ppid": after[2],
            }
        if after is None:
            save_observation({**base_observation, **maps_observation,
                                                 **cmdline_observation, "reason": "stat-after-error"})
        if not uid_ok_after:
            save_observation({**base_observation, **maps_observation,
                                                 **cmdline_observation, "reason": "uid-after-error"})
        if after is not None and before is not None and after != before:
            save_observation({**base_observation, **maps_observation,
                                                 **cmdline_observation, "reason": "identity-changed"})
        if after is not None and before is not None and (after[2] != before[2] or before[2] != ppid):
            save_observation({**base_observation, **maps_observation,
                                                 **cmdline_observation, "reason": "ppid-changed"})
        if after is None or not uid_ok_after or after != before or before[2] != ppid:
            continue
        map_path = run_dir / f"maps-{sample:02d}-{pid}-{seen.get(pid, 0) + 1}.txt"
        entry = {
            "sample": sample,
            "uid_ps": UID,
            "uid": UID,
            "pid": pid,
            "ppid": ppid,
            "name": name,
            "stat_before": {"pid": before[0], "start_ticks": before[1], "ppid": before[2]},
            "stat_after": {"pid": after[0], "start_ticks": after[1], "ppid": after[2]},
            "uid_before": [UID] * 4,
            "uid_after": [UID] * 4,
            "cmdline_base": allowed_base,
            "name_truncated": name_truncated,
            "map_file": map_path.name,
            "maps_bytes": len(maps.stdout),
            "maps_sha256": sha256(maps.stdout),
        }
        write_capture_bytes(adb, map_path, maps.stdout, run_id=run_id)
        append_capture_jsonl(adb, run_dir / "process-snapshots.jsonl", entry, run_id=run_id)
        seen[pid] = seen.get(pid, 0) + 1
        save_observation({**base_observation, **maps_observation, **cmdline_observation,
                                             "reason": "saved", "cmdline_base": allowed_base,
                                             "stat_after_ok": True, "uid_after_ok": True})
        saved += 1
    journal.state("process-snapshot", sample=sample, saved=saved, total=sum(seen.values()))
    return saved


def final_screenshot(adb: Adb, run_dir: Path, current: CurrentRun | None,
                     journal: Journal | None = None) -> bool:
    def skipped(reason: str) -> bool:
        if journal is not None:
            journal.state("screenshot-skipped", reason=reason, screenshot=False)
        return False

    if current is None:
        return skipped("no-current-run")
    require_no_sensitive_session(adb)
    require_no_sensitive_run(adb, current.run_id)
    try:
        verify_capture_ui(dump_ui(adb))
    except TrialError as exc:
        if getattr(adb, "sensitive_session_detected", False):
            raise
        if str(exc) in {"uiautomator dump failed", "UI XML missing, oversized, or unreadable", "UI XML malformed"}:
            reason = "ui-dump-failed"
        elif str(exc) == "UI hierarchy contains a foreign or unknown package":
            reason = "own-hierarchy-guard-failed"
        else:
            reason = "own-focus-guard-failed"
        return skipped(reason)
    require_no_sensitive_session(adb)
    result = adb.exec_out("screencap", "-p", timeout=20)
    require_no_sensitive_session(adb)
    if result.returncode != 0:
        return skipped("screencap-error")
    if not result.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
        return skipped("screencap-not-png")
    if len(result.stdout) > 16 * 1024 * 1024:
        return skipped("screencap-oversized")
    try:
        verify_capture_ui(dump_ui(adb))
    except TrialError as exc:
        if getattr(adb, "sensitive_session_detected", False):
            raise
        if str(exc) in {"uiautomator dump failed", "UI XML missing, oversized, or unreadable", "UI XML malformed"}:
            reason = "ui-dump-failed-after-capture"
        elif str(exc) == "UI hierarchy contains a foreign or unknown package":
            reason = "own-hierarchy-changed"
        else:
            reason = "own-focus-changed"
        return skipped(reason)
    write_capture_bytes(adb, run_dir / "final-screenshot.png", result.stdout, run_id=current.run_id)
    return True


def force_stop_if_current(adb: Adb, run_id: str, journal: Journal) -> bool:
    require_no_sensitive_session(adb)
    require_no_sensitive_run(adb, run_id)
    current, status = read_current(adb)
    if status != "ok" or current is None or current.run_id != run_id:
        journal.state("agent-stop-skipped", reason="latest-run-identity-mismatch", observed=status)
        return False
    require_no_sensitive_session(adb)
    require_no_sensitive_run(adb, run_id)
    result = adb.shell("am", "force-stop", PACKAGE, timeout=15)
    require_no_sensitive_session(adb)
    journal.state("agent-stop", returncode=result.returncode, natural_exit=False, run_id=run_id)
    return result.returncode == 0


def run_trial(args: argparse.Namespace) -> int:
    require_serial()
    adb = Adb()
    require_no_sensitive_session(adb)
    output = safe_output_dir(args.output)
    version = {"code": args.version_code, "name": args.version_name}
    policy = trial_policy(args.version_code, args.version_name)
    expected_probe = APPROVED_TRIAL_PROBES[(args.version_code, args.version_name)]
    # Validation intentionally precedes creation of the evidence directory.
    validated = validate_device(adb, args.version_code, args.version_name)
    before, before_status = read_current(adb)
    if before is not None:
        require_no_sensitive_run(adb, before.run_id)
    run_name, run_dir = new_run_dir(output)
    journal = Journal(run_dir / "journal.jsonl", serial=SERIAL, package=PACKAGE, version=version)
    journal.state("created", local_run_id=run_name, existing_run_id=before.run_id if before else None, existing_status=before_status, validation=validated)
    adb.deadline = time.monotonic() + args.seconds
    write_json(run_dir / "preflight.json", {
        "serial": SERIAL,
        "package": PACKAGE,
        "alias": ALIAS,
        "uid": UID,
        "version": version,
        "existing_run_id": before.run_id if before else None,
        "existing_run_status": before_status,
    })

    logcat: LogcatCapture | None = None
    prepare_pressed = False
    new_run: CurrentRun | None = None
    root_spawned_at: float | None = None
    stop_performed = False
    graphics_collected = False
    trial_error: str | None = None

    def record_unknown(reason: str) -> None:
        target = run_dir / "trial-result.json"
        if target.exists():
            return
        with target.open("xb") as stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write((json.dumps({"schema": 1, "runId": new_run.run_id if new_run else None,
                         "status": "UNKNOWN", "error": reason[:256], "presentationObserved": False}) + "\n").encode())
            stream.flush(); os.fsync(stream.fileno())
    try:
        # The host log starts before both am start and any AOA keyboard packet.
        logcat = LogcatCapture(run_dir, UID, adb)
        if before is not None:
            logcat.run_id = before.run_id
        journal.state("logcat-started", pid=logcat.process.pid, argv=logcat.argv)
        with UsbKeyboard() as keyboard:
            if not policy_is_nonsecure(adb, deadline=time.monotonic() + 8):
                raise TrialError("secure keyguard policy was not exactly secure=false")
            require_no_sensitive_session(adb)
            keyboard.key(2, 0)
            time.sleep(0.15)
            journal.state("wake-key-sent", modifier=2, keycode=0)
            require_no_sensitive_session(adb)
            dismissed = adb.shell("wm", "dismiss-keyguard", timeout=10)
            require_no_sensitive_session(adb)
            if dismissed.returncode != 0:
                raise TrialError("public wm dismiss-keyguard failed")
            require_no_sensitive_session(adb)
            started = adb.shell("am", "start", "-n", ALIAS, timeout=20)
            require_no_sensitive_session(adb)
            if started.returncode != 0:
                raise TrialError("own activity alias did not start")
            journal.state("activity-started", component=ALIAS)

            readiness = wait_for_own_ui(adb, journal=journal)
            if readiness.state is None:
                raise TrialError(f"own UI readiness failed: {readiness.reason}")

            consent_found = False
            for index in range(9):  # at most eight Tab packets
                state = dump_ui(adb)
                verify_top(state)
                checkbox = state.consent()
                if checkbox is not None and checkbox.attrib.get("focused") == "true":
                    consent_found = True
                    break
                if index == 8:
                    raise TrialError("consent checkbox was not focused within eight tabs")
                require_no_sensitive_session(adb)
                send_ui_key(keyboard, 43)  # HID Tab
                journal.state("tab-consent", index=index + 1)
            if not consent_found:
                raise TrialError("consent checkbox not found")
            state = dump_ui(adb)
            verify_top(state)
            checkbox = state.consent()
            if checkbox is None or checkbox.attrib.get("focused") != "true" or checkbox.attrib.get("checked") != "false":
                raise TrialError("consent checkbox was not focused and unchecked")
            require_no_sensitive_session(adb)
            send_ui_key(keyboard, 44)  # HID Space
            journal.state("consent-toggled", keycode=44)
            state = dump_ui(adb)
            verify_top(state)
            checkbox = state.consent()
            if checkbox is None or checkbox.attrib.get("focused") != "true" or checkbox.attrib.get("checked") != "true":
                raise TrialError("consent checkbox did not become checked")

            state = dump_ui(adb)
            verify_top(state)
            require_no_sensitive_session(adb)
            send_ui_key(keyboard, 43)  # only the one transition to Preparar y lanzar
            journal.state("tab-prepare", keycode=43)
            state = dump_ui(adb)
            verify_top(state)
            prepare = state.prepare()
            if prepare is None:
                raise TrialError("enabled own Preparar y lanzar control was not focused")
            journal.state("prepare-intent", text="Preparar y lanzar", enabled=True, focused=True)
            state = dump_ui(adb)
            verify_top(state)
            prepare = state.prepare()
            if prepare is None:
                raise TrialError("Preparar y lanzar focus changed before one-shot Enter")
            journal.state("prepare-press", text="Preparar y lanzar", one_shot=True)
            require_no_sensitive_session(adb)
            send_ui_key(keyboard, 40)  # HID Enter, exactly once
            prepare_pressed = True

        deadline = adb.deadline
        while time.monotonic() < deadline and new_run is None:
            candidate, status = read_current(adb)
            if candidate is not None and candidate.run_id != (before.run_id if before else None):
                new_run = candidate
                require_no_sensitive_run(adb, new_run.run_id)
                if logcat is not None:
                    logcat.run_id = new_run.run_id
                root_spawned_at = time.monotonic()
                journal.state("new-run", run_id=new_run.run_id)
                break
            time.sleep(0.5)
        if new_run is None:
            journal.state("run-missing", reason="no-different-strict-runId", before_status=before_status)
            return 1

        require_no_sensitive_run(adb, new_run.run_id)
        write_capture_json(adb, run_dir / "private-run.json", sanitize_json(new_run.payload), run_id=new_run.run_id)
        seen: dict[int, int] = {}
        sample = 0
        while time.monotonic() < deadline - FINAL_CAPTURE_RESERVE_SECONDS:
            current, status = read_current(adb)
            if current is None or current.run_id != new_run.run_id:
                journal.state("run-identity-mismatch", observed=status, expected_run_id=new_run.run_id)
                return 1
            require_no_sensitive_run(adb, new_run.run_id)
            if sum(seen.values()) < MAX_PROCESS_SNAPSHOTS:
                process_snapshot(adb, run_dir, journal, sample, seen, new_run.run_id)
                sample += 1
            time.sleep(min(sample_interval(root_spawned_at, now=time.monotonic()),
                          max(0.0, deadline - time.monotonic())))
        latest, latest_status = read_current(adb)
        if latest is None or latest.run_id != new_run.run_id:
            journal.state("run-identity-mismatch", observed=latest_status, expected_run_id=new_run.run_id)
            return 1
        require_no_sensitive_run(adb, latest.run_id)
        write_capture_json(adb, run_dir / "private-run-final.json", sanitize_json(latest.payload), run_id=latest.run_id)
        save_run_diagnostics(adb, run_dir, latest.run_id, journal)
        try:
            bounded_streams(adb, run_dir, latest, journal)
        except TrialError as exc:
            journal.state("stream-snapshots-missing", error=str(exc))
        screenshot = final_screenshot(adb, run_dir, latest, journal)
        if policy.collect_graphics:
            graphics_collected = collect_graphics_probe(adb, run_dir, latest, journal, expected_probe)
        if policy.collect_panvk_identity:
            collect_panvk_driver_identity(adb, run_dir, latest, journal)
        if policy.collect_probe_identity:
            collect_probe_identity(adb, run_dir, latest, journal, expected_probe)
        journal.state("final-capture", run_id=latest.run_id, screenshot=screenshot, snapshots=sum(seen.values()))
        stop_performed = force_stop_if_current(adb, latest.run_id, journal)
        if not stop_performed:
            trial_error = "owned-package stop not confirmed"
            record_unknown(trial_error)
            return 1
        journal.state("complete", run_id=latest.run_id, prepare_pressed=prepare_pressed)
        return 0
    except TrialError as exc:
        trial_error = str(exc)
        if getattr(adb, "sensitive_session_detected", False):
            if logcat is not None:
                logcat.close()
                logcat = None
            discard_sensitive_capture_outputs(run_dir)
        record_unknown(trial_error)
        journal.state("error", error=str(exc), prepare_pressed=prepare_pressed)
        return 1
    except (OSError, ValueError, ctypes.Error) as exc:
        trial_error = f"{type(exc).__name__}: {exc}"
        record_unknown(trial_error)
        journal.state("error", error=f"{type(exc).__name__}: {exc}", prepare_pressed=prepare_pressed)
        return 1
    finally:
        # A trial that reached a strict new runId is always closed through the
        # package-owned handle, including collector failures.  A missing or
        # changed RUN deliberately causes no phone cleanup.
        # Execution budget is absolute; cleanup has its own bounded 20 s window.
        cleanup_deadline = time.monotonic() + 20
        adb.deadline = cleanup_deadline - 5
        try:
            require_no_sensitive_session(adb)
        except TrialError:
            pass
        sensitive_session_detected = getattr(adb, "sensitive_session_detected", False)
        if logcat is not None and logcat.sensitive_session_detected:
            sensitive_session_detected = True
        if sensitive_session_detected:
            if logcat is not None:
                logcat.close()
                logcat = None
            discard_sensitive_capture_outputs(run_dir)
            journal.state("assisted-session-guard", captures_discarded=True, package_stopped=False)
        try:
            if prepare_pressed and new_run is None and not sensitive_session_detected:
                candidate, _ = read_current(adb)
                if candidate is not None and candidate.run_id != (before.run_id if before else None):
                    require_no_sensitive_run(adb, candidate.run_id)
                    new_run = candidate
                    if logcat is not None:
                        logcat.run_id = candidate.run_id
            if prepare_pressed and new_run is not None and not stop_performed and not sensitive_session_detected:
                latest, latest_status = read_current(adb)
                if latest is not None and latest.run_id == new_run.run_id:
                    try:
                        require_no_sensitive_run(adb, latest.run_id)
                        write_capture_json(adb, run_dir / "private-run-final.json", sanitize_json(latest.payload),
                                           run_id=latest.run_id)
                        save_run_diagnostics(adb, run_dir, latest.run_id, journal)
                        bounded_streams(adb, run_dir, latest, journal)
                        if policy.collect_graphics and not graphics_collected:
                            graphics_collected = collect_graphics_probe(adb, run_dir, latest, journal, expected_probe)
                        if policy.collect_panvk_identity:
                            collect_panvk_driver_identity(adb, run_dir, latest, journal)
                        if policy.collect_probe_identity:
                            collect_probe_identity(adb, run_dir, latest, journal, expected_probe)
                    except (TrialError, OSError, ValueError) as exc:
                        journal.state("stream-snapshots-missing", error=str(exc))
                    finally:
                        # Reserve a final short stop window rather than spend it on diagnostics.
                        adb.deadline = cleanup_deadline
                        stop_performed = force_stop_if_current(adb, latest.run_id, journal)
                        if not stop_performed:
                            record_unknown("cleanup: owned-package stop not confirmed")
                else:
                    journal.state("agent-stop-skipped", reason="latest-run-identity-mismatch", observed=latest_status)
        except TrialError as exc:
            if getattr(adb, "sensitive_session_detected", False):
                discard_sensitive_capture_outputs(run_dir)
            record_unknown(str(exc))
            journal.state("cleanup-unknown", reason=str(exc))
        finally:
            if logcat is not None:
                logcat.close()
                if getattr(adb, "sensitive_session_detected", False) or logcat.sensitive_session_detected:
                    discard_sensitive_capture_outputs(run_dir)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="existing external parent for private trial evidence")
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--seconds", type=int, default=TRIAL_SECONDS)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        policy = trial_policy(args.version_code, args.version_name)
    except TrialError as exc:
        parser.error(str(exc))
    if policy.max_seconds is not None and args.seconds > policy.max_seconds:
        parser.error(f"selected trial is limited to {policy.max_seconds} seconds")
    if not 20 <= args.seconds <= 180:
        parser.error("--seconds must be between 20 and 180")
    try:
        return run_trial(args)
    except TrialError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    except (OSError, ValueError, ctypes.Error) as exc:
        print(f"ERROR: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
