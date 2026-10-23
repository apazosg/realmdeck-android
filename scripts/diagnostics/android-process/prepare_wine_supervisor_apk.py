#!/usr/bin/env python3
"""Bounded, fail-closed preflight for the candidate Wine62 supervisor APK.

This command is the only mutating step in the candidate Wine62 preparation boundary.  It
does not launch an Activity, send input, stop a process, or accept a command or
path from the device.  The normal test path injects a raw ADB object; the real
path uses the fixed SDK binary and serial below.
"""
from __future__ import annotations

import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import stat
import subprocess
import sys
import time
import uuid
from typing import Any, Callable, Sequence


ROOT = Path(__file__).resolve().parents[3]
COLLECTOR_SOURCE = Path(__file__).with_name("run_wine_supervisor_apk.py")


def _load_collector() -> Any:
    name = "wowforever_wine_supervisor_collector"
    loaded = sys.modules.get(name)
    if loaded is not None:
        return loaded
    spec = importlib.util.spec_from_file_location(name, COLLECTOR_SOURCE)
    if spec is None or spec.loader is None:
        raise RuntimeError("Wine collector unavailable")
    module = importlib.util.module_from_spec(spec)
    # dataclasses and a few annotations require the module to be registered
    # before exec_module, especially when this file is loaded by a test.
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


collector = _load_collector()

ADB = collector.ADB
SERIAL = collector.SERIAL
DEVICE = collector.DEVICE
MODEL = collector.MODEL
MANUFACTURER = collector.MANUFACTURER
API_LEVEL = collector.API_LEVEL
PACKAGE = collector.PACKAGE
UID = collector.UID
VERSION_CODE = collector.VERSION_CODE
VERSION_NAME = collector.VERSION_NAME
CERTIFICATE_SHA256 = collector.CERTIFICATE_SHA256
OLD_APK60_SHA256 = collector.OLD_APK60_SHA256
REBOOT_SENTINEL_BOOT = collector.REBOOT_SENTINEL_BOOT
OLD_V1_CURRENT_SHA256 = collector.OLD_V1_CURRENT_SHA256
OLD_V2_CURRENT_SHA256 = collector.OLD_V2_CURRENT_SHA256
OLD_V2_PERSISTENT_ROOT = collector.OLD_V2_PERSISTENT_ROOT
OLD_V2_HISTORY_SHA256 = collector.OLD_V2_HISTORY_SHA256

PREPARE_SECONDS = 240.0
BOOT_SECONDS = 120.0
ADB_OUTPUT_CAP = 64 * 1024
RECORD_CAP = 64 * 1024
MAX_APK_BYTES = 2 * 1024 * 1024 * 1024
MEMORY_RESERVE_BYTES = 256 * 1024 * 1024
OLD_APK60_BYTES = 418_266_965
KNOWN_APK61_SHA256 = collector.KNOWN_FAILURE_APK61_SHA256
KNOWN_APK61_BYTES = 418_308_948
INTERRUPTED_DISPOSITION_SHA256 = collector.CONTINUATION_CONTRACT_SHA256
SHA_RE = re.compile(r"^[0-9a-f]{64}$")
BOOT_RE = re.compile(r"^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")
APK_PATH_RE = re.compile(r"^/data/app/[A-Za-z0-9_~+=/.-]+/base\.apk$")

PROTECTED = {
    "compiler": {
        "path": "files/imagefs_shared/home/xuser-CUSTOM_GAME_1368588668/.wine/drive_c/windows/system32/d3dcompiler_47.dll",
        "bytes": 1_114_112,
        "sha256": "c374b107fd14890e4b602921c82198bd7c3773f7f1d27f0712be92cb52ed3add",
        "uid": 10330,
        "mode": 771,
        "nlink": 1,
    },
    "backup": {
        "path": "files/forever/controls/compiler-prefix/RUN-1791497262380-683075998117/backup-d3dcompiler_47.dll-7239e3f8-b5fc-42d3-9650-15481cd22164.bak",
        "bytes": 4_453_888,
        "sha256": "06dc23aaa86607e083ed1725f2e99cc40501caf93408569eb9f287b272b58e52",
        "uid": 10330,
        "mode": 771,
        "nlink": 1,
    },
}


class PrepareError(RuntimeError):
    """A bounded preflight failure; callers must treat the attempt as STOP."""


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _json(value: object) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


def _text(result: Any) -> str:
    try:
        return bytes(getattr(result, "stdout", b"")).decode("utf-8", "strict").strip()
    except (UnicodeDecodeError, TypeError) as exc:
        raise PrepareError("ADB response was not strict UTF-8") from exc


def _result_bytes(result: Any, field: str) -> bytes:
    value = getattr(result, field, b"")
    if not isinstance(value, (bytes, bytearray)):
        raise PrepareError(f"ADB {field} result is not bytes")
    if len(value) > ADB_OUTPUT_CAP:
        raise PrepareError(f"ADB {field} output exceeded bound")
    return bytes(value)


def _label(args: Sequence[str]) -> str:
    labels = {
        ("get-state",): "get-state",
        ("reboot",): "reboot",
        ("install", "-r", "--no-streaming"): "install",
        ("exec-out", "run-as", PACKAGE, "id", "-u"): "run-as-uid",
        ("exec-out", "run-as", PACKAGE, "ps", "-A", "-o", "UID,PID,PPID,NAME"): "own-ps",
    }
    for key, value in labels.items():
        if tuple(args[:len(key)]) == key:
            return value
    if args[:2] == ("shell", "-T"):
        return "shell-" + (args[2] if len(args) > 2 else "unknown")
    if args[:2] == ("exec-out", "run-as"):
        return "exec-out-run-as-" + (args[3] if len(args) > 3 else "unknown")
    return str(args[0]) if args else "adb"


class Adb:
    """Fixed-serial ADB facade with bounded output and operation telemetry."""

    def __init__(self, raw: Any | None = None) -> None:
        self.raw = raw or _RealAdb()
        self.records: list[dict[str, object]] = []
        self.intent: Callable[[str], None] | None = None
        self.record_sink: Callable[[dict[str, object]], None] | None = None
        self.deadline: float | None = None

    def run(self, args: tuple[str, ...] | Sequence[str], *, timeout: float = 30.0) -> Any:
        argv = tuple(args)
        operation = _label(argv)
        if self.deadline is not None:
            timeout = min(timeout, self.deadline - time.monotonic())
            if timeout <= 0:
                raise PrepareError(f"{operation}: preparation deadline reached")
        try:
            result = self.raw.run(argv, timeout=timeout)
        except (OSError, subprocess.TimeoutExpired, TimeoutError, PrepareError) as exc:
            record = {"operation": operation, "returnCode": 125, "stdoutBytes": 0, "stderrBytes": 0}
            self.records.append(record)
            if self.record_sink:
                self.record_sink(record)
            raise PrepareError(f"{operation}: bounded ADB read failed") from exc
        stdout = _result_bytes(result, "stdout")
        stderr = _result_bytes(result, "stderr")
        record = {"operation": operation, "returnCode": int(getattr(result, "returncode", 125)),
                  "stdoutBytes": len(stdout), "stderrBytes": len(stderr)}
        self.records.append(record)
        if self.record_sink:
            self.record_sink(record)
        if self.deadline is not None and time.monotonic() > self.deadline:
            raise PrepareError(f"{operation}: result returned after preparation deadline")
        return result

    def shell(self, *args: str, timeout: float = 30.0) -> Any:
        return self.run(("shell", "-T", *args), timeout=timeout)

    def exec_out(self, *args: str, timeout: float = 30.0) -> Any:
        return self.run(("exec-out", *args), timeout=timeout)


class _RealAdb:
    def run(self, args: Sequence[str], *, timeout: float) -> Any:
        argv = (ADB, "-s", SERIAL, *map(str, args))
        try:
            result = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    timeout=timeout, check=False)
            return type("AdbResult", (), {"returncode": result.returncode,
                                           "stdout": result.stdout or b"", "stderr": result.stderr or b""})()
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise PrepareError("bounded ADB subprocess failed") from exc


def _ok(result: Any, operation: str) -> bytes:
    stdout = _result_bytes(result, "stdout")
    stderr = _result_bytes(result, "stderr")
    # ADB may start its host daemon during this read-only readiness operation.
    # No other command or diagnostic is permitted to ignore stderr.
    bootstrap = b"* daemon not running; starting now at tcp:5037\n* daemon started successfully\n"
    if operation == "get-state" and getattr(result, "returncode", 125) == 0 and stdout == b"device\n" and stderr == bootstrap:
        stderr = b""
    if int(getattr(result, "returncode", 125)) != 0 or stderr:
        raise PrepareError(f"{operation}: bounded ADB operation failed")
    return stdout


def _checked_text(result: Any, operation: str) -> str:
    try:
        return _ok(result, operation).decode("utf-8", "strict").strip()
    except UnicodeDecodeError as exc:
        raise PrepareError(f"{operation}: response was not strict UTF-8") from exc


def _fixed_boot(adb: Adb) -> str:
    value = _checked_text(adb.exec_out("run-as", PACKAGE, "cat", "/proc/sys/kernel/random/boot_id", timeout=10), "boot-id")
    if not BOOT_RE.fullmatch(value):
        raise PrepareError("boot id is absent or malformed")
    return value


def _device_properties(adb: Adb) -> dict[str, str]:
    if _checked_text(adb.run(("get-state",), timeout=10), "get-state") != "device":
        raise PrepareError("fixed serial is not ready")
    props = {key: _checked_text(adb.shell("getprop", prop, timeout=8), f"getprop-{key}") for key, prop in (
        ("device", "ro.product.device"), ("model", "ro.product.model"),
        ("manufacturer", "ro.product.manufacturer"), ("api", "ro.build.version.sdk"))}
    expected = {"device": DEVICE, "model": MODEL, "manufacturer": MANUFACTURER, "api": str(API_LEVEL)}
    if props != expected:
        raise PrepareError("fixed device identity mismatch")
    uid = _checked_text(adb.exec_out("run-as", PACKAGE, "id", "-u", timeout=10), "run-as-uid")
    if uid != str(UID):
        raise PrepareError("run-as UID mismatch")
    return props


def _installed_package(adb: Adb) -> tuple[str, str, str]:
    path_raw = _checked_text(adb.shell("pm", "path", PACKAGE, timeout=10), "package-path")
    match = re.fullmatch(r"package:(" + APK_PATH_RE.pattern[1:-1] + r")", path_raw)
    if match is None:
        raise PrepareError("installed APK path is not an exact base APK")
    path = match.group(1)
    dump = _checked_text(adb.shell("dumpsys", "package", PACKAGE, timeout=15), "package-dump")
    if not re.search(r"\bversionCode=(?:60|61|62)\b", dump):
        raise PrepareError("installed package version is not old 60/known 61/candidate 62")
    if not re.search(r"\btargetSdk(?:Version)?[=:]36\b", dump) or not ("debuggable=true" in dump or "DEBUGGABLE" in dump):
        raise PrepareError("installed package is not the debuggable target-36 fixture")
    digest_fields = _checked_text(adb.exec_out("run-as", PACKAGE, "sha256sum", path, timeout=30), "installed-apk-hash").split()
    if digest_fields != [digest_fields[0] if digest_fields else "", path] or not SHA_RE.fullmatch(digest_fields[0]):
        raise PrepareError("installed APK hash response is not canonical")
    return digest_fields[0], path, dump


def _history_snapshot(adb: Adb) -> tuple[dict[str, bytes | None], bytes | None]:
    history = collector._history(adb)
    current_raw, _ = collector._read_current(adb)
    return history, current_raw


def _history_pins(history: dict[str, Any]) -> dict[str, Any]:
    if set(history) != {"old-v1-current", "old-v2-current", *[f"old-v2/{name}" for name in OLD_V2_HISTORY_SHA256]}:
        raise PrepareError("historical fixture reads are incomplete")
    try:
        old_v1 = _sha256(history["old-v1-current"] or b"")
        old_v2 = _sha256(history["old-v2-current"] or b"")
        archive = {f"{OLD_V2_PERSISTENT_ROOT}/{name}": _sha256(history[f"old-v2/{name}"] or b"")
                   for name in OLD_V2_HISTORY_SHA256}
    except (KeyError, TypeError):
        raise PrepareError("historical fixture reads are malformed") from None
    pins = {"oldV2CurrentSha256": old_v2, "oldV1CurrentSha256": old_v1,
            "oldV2ArchiveAndReceiptSha256": archive}
    expected = {"oldV2CurrentSha256": OLD_V2_CURRENT_SHA256, "oldV1CurrentSha256": OLD_V1_CURRENT_SHA256,
                "oldV2ArchiveAndReceiptSha256": {f"{OLD_V2_PERSISTENT_ROOT}/{name}": digest
                                                  for name, digest in OLD_V2_HISTORY_SHA256.items()}}
    if pins != expected:
        raise PrepareError("historical fixture hashes are not exact")
    return pins


def _history_stable(before: dict[str, Any], after: dict[str, Any]) -> dict[str, Any]:
    if before.keys() != after.keys():
        raise PrepareError("historical fixture path set changed")
    try:
        collector._history_stable(before, after)
    except Exception as exc:
        raise PrepareError(str(exc)) from exc
    return _history_pins(before)


def _known_failed_wine(adb: Adb, current: dict[str, Any], current_raw: bytes) -> bool:
    try:
        contract, _ = collector.load_continuation_contract()
        collector.verify_continuation_external_evidence(contract)
        failure = contract["knownFailure"]
        if (current.get("runId"), current.get("stage"), current.get("status"),
                current.get("terminationUnknown"), current.get("quiescenceConfirmed"),
                current.get("message")) != (
                    failure["runId"], "EXITED", "FAILED", True, False,
                    "filesDir is not the canonical private app root"):
            return False
        if collector.sha256_bytes(current_raw) != failure["current"]["sha256"]:
            return False
        binding, binding_status = collector._device_private(
            adb, f"{collector.RUN_ROOT}/{failure['runId']}/wine-binding.json", collector.JSON_CAP,
            run_id=failure["runId"],
        )
        result, result_status = collector._device_private(
            adb, f"{collector.RUN_ROOT}/{failure['runId']}/wine-result.json", collector.JSON_CAP,
            run_id=failure["runId"],
        )
        return (binding_status == "ok" and result_status == "ok" and binding is not None and result is not None and
                collector.sha256_bytes(binding) == failure["binding"]["sha256"] and
                collector.sha256_bytes(result) == failure["wineResult"]["sha256"])
    except Exception:
        return False


def _guard_prior_wine(adb: Adb, current_raw: bytes | None = None) -> None:
    if current_raw is None:
        current_raw, current = collector._read_current(adb)
    else:
        current = collector.strict_json(current_raw) if current_raw else None
    if current is None:
        return
    run_id = current.get("runId")
    status = current.get("status")
    if not isinstance(run_id, str) or not collector.RUN_RE.fullmatch(run_id):
        raise PrepareError("generic current has no valid RUN identity")
    if status == "ACTIVE":
        try:
            contract, _ = collector.load_contract()
            collector.verify_external_evidence(contract)
            prior = contract["prior"]
            if (run_id, current_raw and collector.sha256_bytes(current_raw), current.get("stage")) != (
                    prior["runId"], prior["current"]["sha256"], "PREFLIGHT"):
                raise PrepareError("active Wine RUN is not the fixed interrupted authority")
            if current.get("launchAttempted") is not False or current.get("terminationUnknown") is not False or current.get("quiescenceConfirmed") is not False:
                raise PrepareError("active Wine RUN has changed markers")
            binding_raw, binding_status = collector._device_private(
                adb, f"{collector.RUN_ROOT}/{run_id}/wine-binding.json", collector.JSON_CAP, run_id=run_id)
            if binding_status != "ok" or binding_raw is None or collector.sha256_bytes(binding_raw) != prior["binding"]["sha256"]:
                raise PrepareError("fixed interrupted Wine binding is not exact")
            collector._validate_wine_binding(binding_raw, run_id, prior["wsRunId"], prior["nonce"])
            for name in collector.WINE_FILES:
                if name == "wine-binding.json":
                    continue
                raw, marker_status = collector._device_private(
                    adb, f"{collector.RUN_ROOT}/{run_id}/{name}", collector.TOTAL_CAP if name.endswith("jsonl") else collector.JSON_CAP, run_id=run_id)
                if raw is not None or marker_status != "missing":
                    raise PrepareError("active Wine RUN has a marker or result")
            admission, admission_status = collector._device_private(
                adb, f"{collector.RUN_ROOT}/{run_id}-wine-result-admission.json", collector.JSON_CAP, run_id=run_id)
            if admission is not None or admission_status != "missing":
                raise PrepareError("active Wine RUN has a result admission")
            return
        except (PrepareError, Exception) as exc:
            if isinstance(exc, PrepareError):
                raise
            raise PrepareError("active Wine RUN is not the fixed interrupted authority") from exc
    if status == "FAILED" and _known_failed_wine(adb, current, current_raw or b""):
        return
    if status not in {"EXITED", "CANCELLED", "FAILED"}:
        raise PrepareError("prior generic RUN is active or unknown")
    try:
        collector._require_prior_wine_closed(adb, current)
    except Exception as exc:
        raise PrepareError(str(exc)) from exc
    if collector.RUN_RE.fullmatch(run_id):
        binding_raw, binding_status = collector._device_private(
            adb, f"{collector.RUN_ROOT}/{run_id}/wine-binding.json", collector.JSON_CAP, run_id=run_id)
        if binding_status == "missing":
            return
        if binding_raw is None or binding_status != "ok":
            raise PrepareError("prior Wine binding is unreadable")
        binding = collector.strict_json(binding_raw, cap=collector.JSON_CAP)
        ws, nonce = binding.get("wsRunId"), binding.get("nonce")
        quarantine, status = collector._device_private(
            adb, f"{collector.RUN_ROOT}/{run_id}/wine-result-quarantine.json", collector.JSON_CAP, run_id=run_id)
        if quarantine is None or status != "ok":
            raise PrepareError("prior Wine quarantine marker is missing")
        marker = collector.strict_json(quarantine, cap=collector.JSON_CAP)
        if (marker.get("state"), marker.get("runId"), marker.get("wsRunId"), marker.get("nonce")) != (
                "PREPARE", run_id, ws, nonce):
            raise PrepareError("prior Wine quarantine binding is incomplete")


def _nofollow(adb: Adb, path: str) -> None:
    parts = path.split("/")
    for index in range(1, len(parts) + 1):
        result = adb.shell("run-as", PACKAGE, "readlink", "/".join(parts[:index]), timeout=8)
        stdout = _result_bytes(result, "stdout")
        stderr = _result_bytes(result, "stderr")
        if stdout or (int(getattr(result, "returncode", 125)) != 0 and stderr and
                      not any(marker in stderr.lower() for marker in (b"invalid argument", b"no such file or directory"))):
            raise PrepareError("protected path authority contains a symlink or is unreadable")


def _protected_one(adb: Adb, item: dict[str, Any]) -> dict[str, Any]:
    path = item["path"]
    _nofollow(adb, path)
    # The format is single-quoted inside the fixed remote sh -c argument.  No
    # device-provided path or format is ever interpolated here.
    fmt = "%u:%a:%h:%d:%i:%Y:%s:%F"
    command = f"test ! -L {shlex.quote(path)} && stat -c {shlex.quote(fmt)} {shlex.quote(path)}"
    stat_raw = _checked_text(adb.shell("run-as", PACKAGE, "sh", "-c", shlex.quote(command), timeout=20), "protected-file-stat")
    fields = stat_raw.split(":")
    if len(fields) != 8:
        raise PrepareError("protected file stat format is invalid")
    try:
        uid, mode, nlink, dev, inode, mtime, size = map(int, fields[:7])
    except ValueError as exc:
        raise PrepareError("protected file stat values are invalid") from exc
    if (uid, mode, nlink, size) != (item["uid"], item["mode"], item["nlink"], item["bytes"]) or fields[7] != "regular file":
        raise PrepareError("protected file metadata pin mismatch")
    digest = _checked_text(adb.shell("run-as", PACKAGE, "sha256sum", path, timeout=30), "protected-file-hash").split()
    again = _checked_text(adb.shell("run-as", PACKAGE, "sh", "-c", shlex.quote(command), timeout=20), "protected-file-stat-after")
    if digest != [item["sha256"], path] or again != stat_raw:
        raise PrepareError("protected file bytes pin mismatch")
    return {"path": path, "bytes": size, "sha256": item["sha256"], "uid": uid, "mode": mode,
            "nlink": nlink, "dev": dev, "inode": inode, "mtime": mtime}


def _protected_pins(adb: Adb) -> dict[str, dict[str, Any]]:
    return {name: _protected_one(adb, item) for name, item in PROTECTED.items()}


def _wait_for_boot(adb: Adb, before: str) -> tuple[str, str]:
    if not BOOT_RE.fullmatch(before):
        raise PrepareError("pre-reboot boot id is invalid")
    deadline = time.monotonic() + BOOT_SECONDS
    adb_deadline = getattr(adb, "deadline", None)
    if adb_deadline is not None:
        deadline = min(deadline, adb_deadline)
    first_after: str | None = None
    while time.monotonic() < deadline:
        try:
            completed = _checked_text(adb.shell("getprop", "sys.boot_completed", timeout=8), "boot-complete")
        except PrepareError:
            time.sleep(min(0.5, max(0.0, deadline - time.monotonic())))
            continue
        if completed == "1":
            candidate = _fixed_boot(adb)
            if candidate != before and candidate != REBOOT_SENTINEL_BOOT:
                first_after = candidate
                break
        time.sleep(min(0.25, max(0.0, deadline - time.monotonic())))
    if first_after is None:
        raise PrepareError("bounded boot_complete observation failed")
    second = _fixed_boot(adb)
    if second != first_after or second in {before, REBOOT_SENTINEL_BOOT}:
        raise PrepareError("boot UUID did not stabilize to a new value")
    return first_after, second


def own_ps(adb: Adb) -> str:
    result = adb.exec_out("run-as", PACKAGE, "ps", "-A", "-o", "UID,PID,PPID,NAME", timeout=15)
    raw = _ok(result, "own-ps")
    try:
        lines = raw.decode("utf-8", "strict").splitlines()
    except UnicodeDecodeError as exc:
        raise PrepareError("own-UID process snapshot is not UTF-8") from exc
    if not lines or lines[0].split() != ["UID", "PID", "PPID", "NAME"]:
        raise PrepareError("own-UID process snapshot header is not canonical")
    rows = ["UID PID PPID NAME"]
    for line in lines[1:]:
        fields = line.split()
        if len(fields) != 4 or fields[0] != str(UID) or not fields[1].isdigit() or not fields[2].isdigit() or not fields[3]:
            # Foreign rows are discarded, but malformed rows belonging to the
            # own UID cannot be persisted as an ambiguous snapshot.
            if fields and fields[0] != str(UID):
                continue
            raise PrepareError("own-UID process snapshot row is malformed")
        rows.append(" ".join(fields))
    if len(rows) == 1:
        raise PrepareError("own-UID process snapshot is empty")
    return "\n".join(rows) + "\n"


def _installed_sha(installed: tuple[str, str, str]) -> str:
    if len(installed) != 3 or not SHA_RE.fullmatch(installed[0]):
        raise PrepareError("installed package tuple is malformed")
    return installed[0]


_IDENTITY_FIELDS = ("st_dev", "st_ino", "st_uid", "st_gid", "st_mode", "st_nlink", "st_size", "st_mtime_ns", "st_ctime_ns")


def _identity(value: os.stat_result) -> tuple[int, ...]:
    return tuple(int(getattr(value, field)) for field in _IDENTITY_FIELDS)


def _source_fd(apk: Path) -> tuple[int, tuple[int, ...]]:
    fd = -1
    try:
        fd = os.open(apk, os.O_RDONLY | os.O_NOFOLLOW)
        info = os.fstat(fd)
        path_info = os.stat(apk, follow_symlinks=False)
    except OSError as exc:
        if fd >= 0:
            os.close(fd)
        raise PrepareError("APK source cannot be opened without following a link") from exc
    if not stat.S_ISREG(info.st_mode) or _identity(info) != _identity(path_info) or info.st_size > MAX_APK_BYTES:
        os.close(fd)
        raise PrepareError("APK source identity or size is invalid")
    return fd, _identity(info)


def _hash_fd(fd: int, expected_size: int) -> tuple[str, int]:
    try:
        os.lseek(fd, 0, os.SEEK_SET)
    except OSError as exc:
        raise PrepareError("APK source is not seekable") from exc
    digest = hashlib.sha256()
    total = 0
    while total <= expected_size:
        block = os.read(fd, min(1024 * 1024, expected_size - total + 1))
        if not block:
            break
        total += len(block)
        if total > expected_size:
            raise PrepareError("APK source exceeded its stable size")
        digest.update(block)
    return digest.hexdigest(), total


def _memory_available() -> int:
    try:
        return int(os.sysconf("SC_AVPHYS_PAGES")) * int(os.sysconf("SC_PAGE_SIZE"))
    except (ValueError, OSError) as exc:
        raise PrepareError("available physical memory is unreadable") from exc


class _SealedApk:
    def __init__(self, root: Path, apk: Path, expected_sha: str) -> None:
        self.root = root
        self.apk = apk
        self.expected_sha = expected_sha
        self.source_fd: int | None = None
        self.fd: int | None = None
        self.directory: Path | None = None
        self.alias: Path | None = None
        self._directory_identity: tuple[int, ...] | None = None
        self._alias_identity: tuple[int, ...] | None = None
        self._alias_target: str | None = None
        self._mem_identity: tuple[int, ...] | None = None

    def __enter__(self) -> "_SealedApk":
        if not hasattr(os, "memfd_create") or not hasattr(fcntl, "F_ADD_SEALS"):
            raise PrepareError("Linux sealed memfd support is unavailable")
        source_fd, source_identity = _source_fd(self.apk)
        self.source_fd = source_fd
        try:
            source_size = os.fstat(source_fd).st_size
            available = _memory_available()
            if available <= source_size + MEMORY_RESERVE_BYTES:
                raise PrepareError("available physical memory is below APK handoff reserve")
            before_sha, before_size = _hash_fd(source_fd, source_size)
            if before_size != source_size or before_sha != self.expected_sha:
                raise PrepareError("APK source hash or size does not match approved pin")
            flags = int(getattr(os, "MFD_ALLOW_SEALING")) | int(getattr(os, "MFD_CLOEXEC", 0))
            memfd = os.memfd_create("wowforever-approved-apk", flags)
            self.fd = memfd
            copied = 0
            copied_hash = hashlib.sha256()
            os.lseek(memfd, 0, os.SEEK_SET)
            # Reopen the source only through the already-held descriptor; the
            # authenticated path is never used as an installer input again.
            if _identity(os.fstat(source_fd)) != source_identity:
                raise PrepareError("APK source identity changed before sealed copy")
            os.lseek(source_fd, 0, os.SEEK_SET)
            while copied < source_size:
                block = os.read(source_fd, min(1024 * 1024, source_size - copied))
                if not block:
                    raise PrepareError("APK source ended during sealed copy")
                offset = 0
                while offset < len(block):
                    written = os.write(memfd, block[offset:])
                    if written <= 0:
                        raise PrepareError("sealed APK copy made no progress")
                    offset += written
                copied += len(block)
                copied_hash.update(block)
            if copied != source_size or copied_hash.hexdigest() != before_sha:
                raise PrepareError("sealed APK copy hash or size mismatch")
            after_sha, after_size = _hash_fd(source_fd, source_size)
            if after_sha != before_sha or after_size != source_size:
                raise PrepareError("APK source changed during sealed copy")
            if _identity(os.stat(self.apk, follow_symlinks=False)) != source_identity:
                raise PrepareError("APK source path changed during sealed copy")
            seals = fcntl.F_SEAL_WRITE | fcntl.F_SEAL_GROW | fcntl.F_SEAL_SHRINK | fcntl.F_SEAL_SEAL
            fcntl.fcntl(memfd, fcntl.F_ADD_SEALS, seals)
            if fcntl.fcntl(memfd, fcntl.F_GET_SEALS) != seals:
                raise PrepareError("sealed APK memfd has incomplete seals")
            self._mem_identity = _identity(os.fstat(memfd))
            directory = self.root / f"apk-handoff-{uuid.uuid4().hex}"
            os.mkdir(directory, 0o700)
            self.directory = directory
            self._directory_identity = _identity(os.stat(directory, follow_symlinks=False))
            alias = directory / "approved.apk"
            target = f"/proc/{os.getpid()}/fd/{memfd}"
            os.symlink(target, alias)
            self.alias = alias
            self._alias_identity = _identity(os.lstat(alias))
            self._alias_target = target
            if os.readlink(alias) != target or _identity(os.stat(alias)) != self._mem_identity:
                raise PrepareError("sealed APK alias identity is not bound")
            if fcntl.fcntl(memfd, fcntl.F_GET_SEALS) != seals:
                raise PrepareError("sealed APK memfd changed before install")
            return self
        except PrepareError:
            self._cleanup()
            raise
        except OSError as exc:
            self._cleanup()
            raise PrepareError("sealed APK copy failed") from exc

    def _cleanup(self) -> None:
        alias = self.alias
        if alias is not None and self._alias_target is not None:
            try:
                if (alias.is_symlink() and self._alias_identity is not None and
                        _identity(os.lstat(alias)) == self._alias_identity and
                        os.readlink(alias) == self._alias_target and
                        self._mem_identity is not None and _identity(os.stat(alias)) == self._mem_identity):
                    alias.unlink()
            except OSError:
                pass
        directory = self.directory
        if directory is not None and self._directory_identity is not None:
            try:
                if (_identity(os.stat(directory, follow_symlinks=False))[:6] == self._directory_identity[:6] and
                        not any(directory.iterdir())):
                    directory.rmdir()
            except OSError:
                pass
        if self.fd is not None:
            try:
                os.close(self.fd)
            except OSError:
                pass
            self.fd = None
        if self.source_fd is not None:
            try:
                os.close(self.source_fd)
            except OSError:
                pass
            self.source_fd = None

    def __exit__(self, _type: Any, _value: Any, _traceback: Any) -> None:
        self._cleanup()


def _sealed_apk_handoff(root: Path, apk: Path, expected_sha: str) -> _SealedApk:
    return _SealedApk(root, apk, expected_sha)


def _stable_before_mutation(adb: Adb, history: dict[str, Any], current: bytes | None, ps: str) -> None:
    history_now, current_now = _history_snapshot(adb)
    ps_now = own_ps(adb)
    _history_stable(history, history_now)
    for snapshot in (ps, ps_now):
        collector._validate_snapshot(snapshot)
        if any(row.split()[3] != "ps" for row in snapshot.splitlines()[1:]):
            raise PrepareError("unexpected own-UID process before preparation mutation")
    if current_now != current:
        raise PrepareError("device ownership/current state changed before mutation")


def device_preflight(adb: Adb, approved_sha: str, apk: Path, *, install_path: Path | None = None) -> dict[str, Any]:
    if not SHA_RE.fullmatch(approved_sha):
        raise PrepareError("independent candidate 62 APK hash is mandatory")
    if approved_sha == OLD_APK60_SHA256:
        raise PrepareError("candidate 62 hash must be independent from old APK60")
    props = _device_properties(adb)
    installed_before = _installed_package(adb)
    installed_sha = _installed_sha(installed_before)
    installed_version = 62 if re.search(r"\bversionCode=62\b", installed_before[2]) else (61 if re.search(r"\bversionCode=61\b", installed_before[2]) else 60)
    if installed_version == 60 and installed_sha != OLD_APK60_SHA256:
        raise PrepareError("installed version 60 is not the exact historical APK60")
    if installed_version == 61 and installed_sha != KNOWN_APK61_SHA256:
        raise PrepareError("installed version 61 is not the exact known failed APK61")
    if installed_version == 62 and installed_sha != approved_sha:
        raise PrepareError("installed version 62 is not the independent candidate hash")
    boot_before = _fixed_boot(adb)
    history_before, generic_before = _history_snapshot(adb)
    _guard_prior_wine(adb, generic_before)
    try:
        current_value = collector.strict_json(generic_before) if generic_before else None
        active_interrupted = bool(current_value and current_value.get("status") == "FAILED" and
                                  _known_failed_wine(adb, current_value, generic_before))
    except Exception:
        active_interrupted = False
    # Installation may succeed while ADB emits a warning and preparation stops
    # before reboot. Accept only exact APK61 known-failure or independent APK62;
    # never reinstall or rearm the failed RUN here.
    # The full history/absence gates and mandatory fresh boot still apply.
    if active_interrupted and installed_sha not in {KNOWN_APK61_SHA256, approved_sha}:
        raise PrepareError("continuation exception requires exact APK61 or approved APK62")
    if active_interrupted:
        collector.load_continuation_contract()
    protected_before = _protected_pins(adb)
    ps_before = own_ps(adb)
    _stable_before_mutation(adb, history_before, generic_before, ps_before)

    if installed_sha in {OLD_APK60_SHA256, KNOWN_APK61_SHA256}:
        if adb.intent:
            adb.intent("install-intent")
        result = adb.run(("install", "-r", "--no-streaming", str(install_path or apk)), timeout=180)
        _ok(result, "install")
        installed_after_install = _installed_package(adb)
        if (_installed_sha(installed_after_install) != approved_sha or
                 not re.search(rf"\bversionCode={VERSION_CODE}\b", installed_after_install[2])):
            raise PrepareError("candidate 62 APK hash was not installed before reboot")
        _stable_before_mutation(adb, history_before, generic_before, ps_before)
    else:
        installed_after_install = installed_before
    if adb.intent:
        adb.intent("reboot-intent")
    result = adb.run(("reboot",), timeout=30)
    _ok(result, "reboot")

    stable_first, boot_after = _wait_for_boot(adb, boot_before)
    if active_interrupted:
        contract, _ = collector.load_continuation_contract()
        if boot_after == boot_before or boot_after == REBOOT_SENTINEL_BOOT or boot_after == contract["knownFailure"]["bootId"]:
            raise PrepareError("continuation exception boot boundary is not exact")
    props_after = _device_properties(adb)
    if props_after != props:
        raise PrepareError("device identity changed across reboot")
    installed_after = _installed_package(adb)
    if _installed_sha(installed_after) != approved_sha or not re.search(rf"\bversionCode={VERSION_CODE}\b", installed_after[2]):
        raise PrepareError("candidate 62 APK is not installed after reboot")
    history_after, generic_after = _history_snapshot(adb)
    history_pins = _history_stable(history_before, history_after)
    if generic_before != generic_after:
        raise PrepareError("generic current file changed during preflight")
    protected_after = _protected_pins(adb)
    if protected_before != protected_after:
        raise PrepareError("protected compiler files changed during preflight")
    ps_after = own_ps(adb)
    if any(row.split()[3] != "ps" for row in ps_after.splitlines()[1:]):
        raise PrepareError("unexpected own-UID process after reboot")
    return {
        "result": "READY_FOR_WINE62_SERVER_ISOLATION", "serial": SERIAL, "package": PACKAGE,
        "versionCode": VERSION_CODE, "versionName": VERSION_NAME, "approvedApkSha256": approved_sha,
        "installedApkSha256": _installed_sha(installed_after), "bootComplete": True,
        "bootBeforeReboot": boot_before, "bootAfterReboot": boot_after,
        "stableBootIds": [stable_first, boot_after], "ownUidProcessSnapshot": ps_after,
        "preRebootOwnUidProcessSnapshot": ps_before, "historyPins": history_pins,
        "interruptedDispositionContractSha256": INTERRUPTED_DISPOSITION_SHA256 if active_interrupted else None,
        "interruptedPreviousBootId": boot_before if active_interrupted else None,
        "candidateAuthority": collector.candidate_authority(),
    }


def _embedded_provenance(apk: Path) -> dict[str, Any]:
    value = collector.read_apk_provenance(apk)
    if not isinstance(value, dict):
        raise PrepareError("embedded APK provenance is not an object")
    return value


def host_authenticate(apk: Path, audit: Path, expected: str) -> dict[str, Any]:
    if not isinstance(expected, str) or not SHA_RE.fullmatch(expected):
        raise PrepareError("independent APK hash is mandatory")
    try:
        validated = collector.validate_apk(apk, audit, expected)
        if "provenance" not in validated:
            validated["provenance"] = _embedded_provenance(apk)
        return validated
    except Exception as exc:
        raise PrepareError(str(exc)) from exc


def _safe_output(path: Path) -> Path:
    if not path.exists() or path.is_symlink() or not path.is_dir():
        raise PrepareError("output parent must be an existing real directory")
    if any(parent.is_symlink() for parent in (path, *path.parents)):
        raise PrepareError("output path contains symlink")
    resolved = path.resolve()
    if resolved == ROOT or ROOT in resolved.parents:
        raise PrepareError("output must be outside repository")
    return resolved


def _new_evidence(parent: Path) -> Path:
    for _ in range(50):
        candidate = parent / f"wine62-preflight-{uuid.uuid4().hex}"
        try:
            candidate.mkdir(mode=0o700)
            return candidate
        except FileExistsError:
            continue
    raise PrepareError("cannot allocate unique preflight directory")


def _write_once(path: Path, raw: bytes) -> None:
    if path.exists() or path.is_symlink():
        raise PrepareError(f"preflight evidence rewrite refused: {path.name}")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(raw); stream.flush(); os.fsync(stream.fileno())
        fd = -1
    finally:
        if fd >= 0:
            os.close(fd)


def _sync(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _append_record(root: Path, record: dict[str, object]) -> None:
    path = root / "operations.jsonl"
    record = dict(record)
    record.setdefault("seq", sum(1 for _ in path.open("rb")) + 1 if path.exists() else 1)
    record.setdefault("timestampMs", int(time.time() * 1000))
    operation = str(record.get("operation", "diagnostic"))
    record.setdefault("phase", "cleanup" if operation in {"stop", "cleanup"} else
                      "mutation" if operation in {"install", "reboot", "install-intent", "reboot-intent"} else "preflight")
    raw = _json(record)
    prior = path.stat().st_size if path.exists() else 0
    if prior + len(raw) > RECORD_CAP:
        raise PrepareError("operation journal exceeded bound")
    if path.exists():
        with path.open("ab") as stream:
            stream.write(raw); stream.flush(); os.fsync(stream.fileno())
    else:
        _write_once(path, raw)


def _journal(root: Path, operation: str, adb: Adb, *, intent: bool = False) -> None:
    if intent:
        _append_record(root, {"operation": operation, "intent": True})
    else:
        _append_record(root, {"operation": operation, "returnCode": 0, "stdoutBytes": 0, "stderrBytes": 0})


def _seal(root: Path) -> None:
    files: dict[str, dict[str, object]] = {}
    for path in sorted(root.iterdir()):
        if path.name == "seal.json":
            continue
        if path.is_symlink() or not path.is_file():
            raise PrepareError("preflight evidence contains an unexpected entry")
        raw = path.read_bytes()
        files[path.name] = {"bytes": len(raw), "sha256": _sha256(raw)}
    _write_once(root / "seal.json", _json({"schema": 1, "files": files}))
    _sync(root)


def _host_file_pin(path: Path) -> dict[str, object]:
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        before = os.fstat(fd)
        digest = hashlib.sha256()
        total = 0
        while True:
            block = os.read(fd, 1024 * 1024)
            if not block:
                break
            total += len(block)
            if total > 2 * 1024 * 1024:
                raise PrepareError("source provenance file exceeds bound")
            digest.update(block)
        after = os.fstat(fd)
        if _identity(before) != _identity(after):
            raise PrepareError("source provenance file changed while hashing")
        return {"path": str(path.resolve()), "bytes": total, "sha256": digest.hexdigest()}
    except OSError as exc:
        raise PrepareError("source provenance file is unreadable") from exc
    finally:
        try:
            os.close(fd)
        except (UnboundLocalError, OSError):
            pass


def _source_provenance(apk: dict[str, Any]) -> dict[str, object]:
    protocol = {"name": "wowforever.wine62-preflight/v1", "serial": SERIAL, "package": PACKAGE,
                "install": ["install", "-r", "--no-streaming"], "reboot": ["reboot"]}
    return {
        "schema": 1,
        "helper": _host_file_pin(Path(__file__)),
        "collector": _host_file_pin(COLLECTOR_SOURCE),
        "protocol": {"name": protocol["name"], "sha256": _sha256(_json(protocol))},
        "apk": apk,
        "interruptedDispositionContractSha256": INTERRUPTED_DISPOSITION_SHA256,
    }


def _prepare_locked(args: argparse.Namespace, parent: Path) -> int:
    apk = Path(args.apk)
    audit = Path(args.audit)
    expected = args.expected_apk_sha256
    authenticated = host_authenticate(apk, audit, expected)
    try:
        contract, _ = collector.load_contract()
        collector.verify_external_evidence(contract)
    except Exception as exc:
        raise PrepareError("fixed interrupted disposition contract is not authenticated") from exc
    root = _new_evidence(parent)
    adb = Adb()
    adb.deadline = time.monotonic() + PREPARE_SECONDS
    adb.record_sink = lambda record: _append_record(root, record)
    adb.intent = lambda operation: _journal(root, operation, adb, intent=True)
    try:
        _write_once(root / "source-provenance.json", _json(_source_provenance(authenticated)))
        _sync(root)
        with _sealed_apk_handoff(root, apk, authenticated["sha256"]) as handoff:
            record = device_preflight(adb, authenticated["sha256"], apk, install_path=handoff.alias)
            # READY is intentionally the exact compact collector contract.
            ready = _json(record)
            if len(ready) > RECORD_CAP:
                raise PrepareError("READY record exceeded bound")
            _write_once(root / "state-00-READY_FOR_WINE62_SERVER_ISOLATION.json", ready)
            _journal(root, "ready", adb)
        _seal(root)
        print(root)
        return 0
    except Exception as exc:
        error = str(exc)[:512]
        try:
            _journal(root, "stop", adb)
            _write_once(root / "state-00-STOP.json", _json({"result": "STOP", "error": re.sub(r"/(?:[^\s/]+/)+[^\s/]*", "<approved-path>", error),
                                                              "seq": 1, "phase": "error", "timestampMs": int(time.time() * 1000)}))
            _seal(root)
            _sync(root)
        except Exception:
            pass
        print(f"wine62 preflight: {error}", file=sys.stderr)
        return 1


def prepare(args: argparse.Namespace) -> int:
    output = Path(args.output)
    parent = _safe_output(output)
    try:
        with collector.device_control_lock(output):
            return _prepare_locked(args, parent)
    except Exception as exc:
        if isinstance(exc, PrepareError):
            raise
        raise PrepareError(str(exc)) from exc


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--audit", required=True, type=Path)
    parser.add_argument("--expected-apk-sha256", required=True)
    parser.add_argument("--output", required=True, type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    try:
        return prepare(build_parser().parse_args(argv))
    except (PrepareError, OSError, ValueError) as exc:
        print(f"wine62 preflight: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
