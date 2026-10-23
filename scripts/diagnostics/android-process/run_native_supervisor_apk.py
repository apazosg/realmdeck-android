#!/usr/bin/env python3
"""One consent-gated, native-only supervisor APK observation.

The collector deliberately owns no installation, reboot, shell input, process
signal, or runtime setup operation.  It validates the retained APK and the
parent's reboot preflight first, then sends one intent and one bounded AOA HID
keyboard sequence to the DUMP-protected fixture Activity.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import re
import stat
import sys
import time
import uuid
from pathlib import Path
from types import ModuleType
from typing import Any, Iterable, Sequence


ADB = "/home/adrian/android-sdk/platform-tools/adb"
SERIAL_ENV_VAR = "WOWFOREVER_ADB_SERIAL"
SERIAL = os.environ.get(SERIAL_ENV_VAR, "")
DEVICE = "xaga"
MODEL = "22041216G"
MANUFACTURER = "Xiaomi"
API_LEVEL = 34
PACKAGE = "com.wowforever.arm64probe"
UID = 10330
VERSION_CODE = 59
VERSION_NAME = "0.2.35-automated"
NATIVE_SUPERVISOR_VARIANT = "native-run-supervisor-v2"
CERTIFICATE_SHA256 = "2b082e8200e066e665f73e71f547f827d6674634298024cb0b823be22adb242b"
MANIFEST_SHA256 = "a246a11bc142fecbe156c40ac8913baba02a172288aff8b1f2787c21eb1134b6"
NATIVE_PINS_STATUS = "READY"
NATIVE_PINS = {
    "libforever_run_supervisor.so": (30_344, "fd8727d3d4b3b76e00cbf17c74bcee75684d7945aa8736f644ecbbaf55f7385c"),
    "libforever_run_fixture.so": (12_784, "7f1b6d256939c9a7310475c3d3bc7dbdfeb22ad46e3de4270413db05a663e67f"),
}
LEGACY_CURRENT_DEVICE = "files/forever/controls/native-supervisor/current.json"
LEGACY_CURRENT_SHA256 = "c9c899f579314d193a91a7bd935be5df16d6dcc1ece39d42863a9ab15064685c"
LEGACY_NATIVE56 = {
    "schema": 1,
    "runId": "NS-30823b6b5acd81b24830be89b809141d",
    "status": "UNKNOWN",
    "nativeExit": 2,
    "lastControlBootId": "3370c8dc-85b4-4975-91a6-352d14271fbd",
    "currentSha256": LEGACY_CURRENT_SHA256,
    "originalSealSha256": "6341c56a8b6383a9a32ebe012de59dfbbc22140bcefd92e6c075b717835ff5d0",
}
LEGACY_NATIVE57 = {
    "schema": 1,
    "runId": "NS-b0a3fe48f925d7d656f597f740b4b413",
    "nonce": "83ebf2726bda3c43258cd9523c465f59",
    "status": "UNKNOWN",
    "nativeExit": None,
    "currentSha256": "73428e2c77bdeafc5687a5860821b67b7f1489966f1cae62d04a12318c57244d",
    "attemptBootId": "66f3530d-a8e6-4a94-9b62-44393cdc75c0",
    "originalSealSha256": "f426bbd4ff2fd836efd9e0086e7ac742b134fc1fdffed27442426620126c23c2",
}
LEGACY_NATIVE57_APK_SHA256 = "8d0c0a86b1ca1571736e399d08f9ae7e36b9b7ec2f6b73075867c0a5fb747572"
PRELAUNCH_DISPOSITION_SOURCE = Path(__file__).with_name("NATIVE-PRELAUNCH-DISPOSITION.json")
PRELAUNCH_DISPOSITION_SHA256 = "423ed396ac54c9caa5edf43c06ec645dc5b68275928003c064729b85ecb44c62"
HISTORICAL_NATIVE57_RUN_FILES = {
    "run.json": (380, "73428e2c77bdeafc5687a5860821b67b7f1489966f1cae62d04a12318c57244d"),
    "events.jsonl": (51, "e81a47a4da7aa10ec844f14d821e6109b4584495c381459a49a96d5ceaaa4693"),
    "stdout": (0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
    "stderr": (0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
    "witness-stdout": (0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
    "witness-stderr": (0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
}
HISTORICAL_NATIVE57_ABSENT_STREAMS = frozenset({
    "stdout", "stderr", "witness-stdout", "witness-stderr",
})
ALIAS = "com.wowforever.arm64probe/app.gamenative.ForeverNativeSupervisorLauncher"
CURRENT_DEVICE = "files/forever/controls/native-supervisor-v2/current.json"
RUN_ROOT_DEVICE = "files/forever/controls/native-supervisor-v2"
LEGACY_NATIVE57_ROOT_DEVICE = f"{RUN_ROOT_DEVICE}/{LEGACY_NATIVE57['runId']}"
LEGACY_NATIVE57_RECEIPT_NAMES = (
    "current-before-disposition.json", "prelaunch-disposition.json", "prelaunch-consumption.json",
)
LEGACY_NATIVE57_RECEIPT_PATHS = frozenset(
    f"{LEGACY_NATIVE57_ROOT_DEVICE}/{name}" for name in LEGACY_NATIVE57_RECEIPT_NAMES
)
SCENARIOS = frozenset({
    "normal", "cancel_before_start", "cancel_fork", "eof", "invalid_protocol",
    "supervisor_failure",
})
PHYSICAL_SCENARIOS = frozenset(SCENARIOS - {"supervisor_failure"})
NON_TERMINAL_STATUSES = frozenset({"PREPARED", "STARTING", "RUNNING", "CANCELLING", "UNKNOWN"})
RUN_RE = re.compile(r"^NS-[0-9a-f]{32}$")
NONCE_RE = re.compile(r"^[0-9a-f]{32}$")
SHA_RE = re.compile(r"^[0-9a-f]{64}$")
BOOT_RE = re.compile(r"^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")
HOST_SECONDS = 60
CLEANUP_SECONDS = 10
UI_SECONDS = 8.0
UI_DUMPS = 3
WAKE_SETTLE_SECONDS = 0.15
WAKEFULNESS_POLL_SECONDS = 2.0
KEYGUARD_POLL_SECONDS = 2.0
CONTROL_POLL_INTERVAL_SECONDS = 0.1
LINE_CAP = 8 * 1024
STREAM_CAP = 64 * 1024
EVENTS_CAP = 256 * 1024
JSON_CAP = 64 * 1024
NOFOLLOW_CHECK_CAP = 4 * 1024

EVENT_KEYS = {
    "schema", "runId", "nonce", "seq", "phase", "event", "pid", "startTicks",
    "ppid", "uid0", "uid1", "uid2", "uid3", "role", "status", "exitCode",
    "childCount", "echild", "setResult", "getResult", "getValue",
}
STORED_EVENT_KEYS = EVENT_KEYS | {"kind"}
PROCESS_CREATE_INTENT_KEYS = {"kind", "role", "runId", "nonce"}
INTENT_KEYS = {"kind", "intent", "phase", "seq"}
FAULT_KEYS = {"kind", "fault", "detail"}
EVENT_NAMES = {"READY", "SPAWN", "ADOPT", "EXIT", "PHASE_DONE", "RUN_DONE", "ERROR"}
ROLES = {"supervisor", "fixture", "intermediate", "grandchild", "witness"}
EVENT_STATUSES = {"READY", "RUNNING", "REAPED", "PASS", "CANCELLED", "UNKNOWN", "ERROR"}
READINESS_FAILURE_REASONS = frozenset({
    "not-ready", "global-deadline", "final-reserve", "local-deadline",
    "ui-dump-cleanup-failed", "ui-dump-late", "ui-dump-failed", "foreign-top",
    "unknown",
})
SAFE_CONTROL_FAILURE_DETAILS = frozenset({
    "own UI readiness failed", "own UI focus guard failed",
    "own UI has no unique own focus", "native fixture controls are not in the initial state",
    "consent checkbox was not focused within eight tabs",
    "consent checkbox was not initially unchecked", "consent checkbox did not become checked",
    "expected own control is not focused", "Cancel became enabled before running",
    "native supervisor Activity did not start", "host deadline reached",
    "wakefulness did not become Awake", "wakefulness state was ambiguous",
    "wakefulness power state read failed", "wakefulness poll deadline reached",
    "keyguard policy was not explicitly nonsecure before dismiss",
    "keyguard policy was not explicitly nonsecure after dismiss",
    "keyguard policy was not explicitly nonsecure before HID",
    "keyguard policy poll deadline reached", "keyguard window policy read failed",
    "keyguard window policy was ambiguous", "keyguard remained showing after dismissal",
    "keyguard window policy was secure", "keyguard window poll deadline reached",
    "nonsecure public keyguard dismissal failed",
})


class CollectorError(RuntimeError):
    """A fail-closed collector or evidence error."""


def require_serial() -> str:
    if not SERIAL:
        raise CollectorError("WOWFOREVER_ADB_SERIAL is not set (explicit device selection required)")
    return SERIAL


def _helpers() -> ModuleType:
    """Load the existing USB/UI helper under an explicit sys.modules name."""
    name = "wowforever_native_supervisor_usb_helpers"
    loaded = sys.modules.get(name)
    if loaded is not None:
        return loaded
    path = (Path(__file__).parent.parent / "usb_forever_trial.py").resolve()
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise CollectorError("existing USB helper unavailable")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def _unique_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON field")
        result[key] = value
    return result


def strict_json(raw: bytes, *, cap: int = JSON_CAP) -> dict[str, Any]:
    if not raw or len(raw) > cap:
        raise CollectorError("bounded JSON missing or oversized")
    try:
        value = json.loads(raw.decode("utf-8", "strict"), object_pairs_hook=_unique_pairs)
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise CollectorError("bounded JSON malformed") from exc
    if not isinstance(value, dict):
        raise CollectorError("bounded JSON is not an object")
    return value


def sha256_file(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _regular(path: Path, *, maximum: int | None = None) -> os.stat_result:
    try:
        info = path.lstat()
    except OSError as exc:
        raise CollectorError("required artifact is unreadable") from exc
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        raise CollectorError("required artifact is not a regular non-symlink file")
    if maximum is not None and info.st_size > maximum:
        raise CollectorError("required artifact exceeds bound")
    for parent in (path.parent, *path.parents):
        try:
            if parent.is_symlink():
                raise CollectorError("artifact path contains symlink")
        except OSError as exc:
            raise CollectorError("artifact path is unreadable") from exc
    return info


def _safe_directory(path: Path, *, outside_repo: bool = False) -> Path:
    path = path.expanduser()
    if path.is_symlink() or not path.is_dir():
        raise CollectorError("directory must already exist and not be a symlink")
    for parent in (path, *path.parents):
        if parent.is_symlink():
            raise CollectorError("directory path contains symlink")
    resolved = path.resolve()
    if outside_repo:
        repo = Path(__file__).resolve().parents[3]
        if resolved == repo or repo in resolved.parents:
            raise CollectorError("output must be outside the repository")
    return resolved


def _fixed_sha(value: object, label: str) -> str:
    if not isinstance(value, str) or not SHA_RE.fullmatch(value):
        raise CollectorError(f"{label} hash is absent or invalid")
    return value


def frozen_prelaunch_disposition() -> tuple[bytes, dict[str, Any]]:
    """Load the one source-authorized prelaunch disposition, never a host input."""
    _regular(PRELAUNCH_DISPOSITION_SOURCE, maximum=JSON_CAP)
    raw = PRELAUNCH_DISPOSITION_SOURCE.read_bytes()
    if sha256_bytes(raw) != PRELAUNCH_DISPOSITION_SHA256:
        raise CollectorError("prelaunch disposition source hash mismatch")
    value = strict_json(raw, cap=JSON_CAP)
    if (value.get("schema"), value.get("kind"), value.get("variant"), value.get("priorStatus"),
            value.get("nativeExit"), value.get("echild"), value.get("runId"), value.get("nonce")) != (
                1, "EXACT_HISTORICAL_PRELAUNCH_AUTHORIZATION", NATIVE_SUPERVISOR_VARIANT, "UNKNOWN",
                None, False, LEGACY_NATIVE57["runId"], LEGACY_NATIVE57["nonce"]):
        raise CollectorError("prelaunch disposition source identity mismatch")
    run_files = value.get("runFiles")
    if not isinstance(run_files, dict):
        raise CollectorError("prelaunch disposition source runFiles metadata is missing")
    for name in HISTORICAL_NATIVE57_ABSENT_STREAMS:
        item = run_files.get(name)
        if (not isinstance(item, dict) or item.get("bytes") != 0 or
                item.get("sha256") != HISTORICAL_NATIVE57_RUN_FILES[name][1] or
                item.get("devicePresence") != "missing"):
            raise CollectorError("prelaunch disposition source stream-presence metadata is invalid")
    return raw, value


def _validate_historical57_current(raw: bytes) -> dict[str, Any]:
    if sha256_bytes(raw) != LEGACY_NATIVE57["currentSha256"]:
        raise CollectorError("historical native57 current hash changed")
    value = _current_payload(raw)
    if (value.get("runId"), value.get("nonce"), value.get("status"), value.get("nativeExit"),
            value.get("echild")) != (LEGACY_NATIVE57["runId"], LEGACY_NATIVE57["nonce"], "UNKNOWN", None, False):
        raise CollectorError("historical native57 current identity changed")
    return value


def _validate_historical57_files(files: dict[str, bytes | None]) -> dict[str, Any]:
    for name in ("run.json", "events.jsonl"):
        size, expected = HISTORICAL_NATIVE57_RUN_FILES[name]
        raw = files.get(name)
        if raw is None or len(raw) != size or sha256_bytes(raw) != expected:
            raise CollectorError(f"historical native57 {name} hash changed")
    for name in HISTORICAL_NATIVE57_ABSENT_STREAMS:
        if files.get(name) is not None:
            raise CollectorError(f"historical native57 {name} must be absent, not an empty placeholder")
    run = strict_json(files["run.json"] or b"", cap=JSON_CAP)
    if (run.get("schema"), run.get("variant"), run.get("runId"), run.get("nonce"), run.get("status"),
            run.get("nativeExit"), run.get("echild")) != (
                1, NATIVE_SUPERVISOR_VARIANT, LEGACY_NATIVE57["runId"], LEGACY_NATIVE57["nonce"],
                "UNKNOWN", None, False):
        raise CollectorError("historical native57 RUN identity changed")
    return run


def _sha_field(value: dict[str, Any], *names: str) -> str | None:
    for name in names:
        candidate = value.get(name)
        if isinstance(candidate, str):
            return candidate
    return None


def _validate_dynamic_prelaunch_abort(raw: bytes, *, fresh_boot_id: str | None) -> dict[str, Any]:
    value = strict_json(raw, cap=JSON_CAP)
    actual_fresh_boot = value.get("freshBootId")
    if fresh_boot_id is None:
        if not isinstance(actual_fresh_boot, str) or not BOOT_RE.fullmatch(actual_fresh_boot) or actual_fresh_boot == LEGACY_NATIVE57["attemptBootId"]:
            raise CollectorError("prelaunch disposition fresh boot binding is invalid")
        fresh_boot_id = actual_fresh_boot
    if (value.get("schema"), value.get("kind"), value.get("variant"), value.get("priorRunId"),
            value.get("priorNonce"), value.get("priorStatus"), value.get("attemptBootId"),
            value.get("freshBootId"), value.get("processCreate"), value.get("echild"),
            value.get("sourceSha256"), value.get("sealSha256")) != (
                1, "PRELAUNCH_ABORT", NATIVE_SUPERVISOR_VARIANT, LEGACY_NATIVE57["runId"],
                LEGACY_NATIVE57["nonce"], "UNKNOWN", LEGACY_NATIVE57["attemptBootId"], fresh_boot_id,
                "NOT_REACHED_BY_PINNED_SOURCE", False, PRELAUNCH_DISPOSITION_SHA256,
                LEGACY_NATIVE57["originalSealSha256"]):
        raise CollectorError("prelaunch disposition identity or source pin mismatch")
    if _sha_field(value, "priorCurrentSha256") != LEGACY_NATIVE57["currentSha256"]:
        raise CollectorError("prelaunch disposition current hash mismatch")
    if _sha_field(value, "priorRunSha256", "runSha256") != HISTORICAL_NATIVE57_RUN_FILES["run.json"][1]:
        raise CollectorError("prelaunch disposition RUN hash mismatch")
    if _sha_field(value, "priorEventsSha256", "eventsSha256") != HISTORICAL_NATIVE57_RUN_FILES["events.jsonl"][1]:
        raise CollectorError("prelaunch disposition events hash mismatch")
    expected_presence = {name: "MISSING" for name in sorted(HISTORICAL_NATIVE57_ABSENT_STREAMS)}
    if value.get("priorStreamPresence") != expected_presence:
        raise CollectorError("prelaunch disposition stream presence is not explicitly MISSING")
    apk = _sha_field(value, "apk57Sha256")
    if apk is None and isinstance(value.get("apk57"), dict):
        apk = value["apk57"].get("sha256")
    if apk != LEGACY_NATIVE57_APK_SHA256:
        raise CollectorError("prelaunch disposition APK57 hash mismatch")
    return value


def _validate_dynamic_consumption(
    raw: bytes, *, disposition_sha: str, fresh_boot_id: str,
    expected_run: dict[str, Any] | None = None,
) -> dict[str, Any]:
    value = strict_json(raw, cap=JSON_CAP)
    if (value.get("schema"), value.get("kind"), value.get("priorRunId"), value.get("freshBootId"),
            value.get("dispositionSha256"), value.get("sourceSha256")) != (
                1, "RESERVED_PRELAUNCH_CONSUMPTION", LEGACY_NATIVE57["runId"], fresh_boot_id,
                disposition_sha, PRELAUNCH_DISPOSITION_SHA256):
        raise CollectorError("prelaunch consumption identity or source pin mismatch")
    run_id = value.get("newRunId")
    nonce = value.get("newNonce")
    scenario = value.get("newScenario")
    if not isinstance(run_id, str) or not RUN_RE.fullmatch(run_id) or not isinstance(nonce, str) or not NONCE_RE.fullmatch(nonce):
        raise CollectorError("prelaunch consumption RUN identity is invalid")
    if not isinstance(scenario, str) or scenario not in SCENARIOS:
        raise CollectorError("prelaunch consumption scenario is invalid")
    if expected_run is not None and (run_id != expected_run["runId"] or nonce != expected_run["nonce"] or scenario != expected_run["scenario"]):
        raise CollectorError("prelaunch consumption is not bound to the admitted RUN")
    first = value.get("firstAdmittedRunId", run_id)
    if not isinstance(first, str) or not RUN_RE.fullmatch(first):
        raise CollectorError("prelaunch consumption first RUN binding is invalid")
    return value


def _write_private(path: Path, data: bytes) -> None:
    if path.exists() or path.is_symlink():
        raise CollectorError("evidence file already exists")
    path.parent.mkdir(mode=0o700, exist_ok=True)
    if path.parent.is_symlink():
        raise CollectorError("evidence parent is a symlink")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    fd = os.open(path, flags, 0o600)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        fd = -1
    finally:
        if fd >= 0:
            os.close(fd)
    os.chmod(path, 0o600)


def _write_json(path: Path, value: object) -> None:
    _write_private(path, (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode())


def _fsync_dir(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _text(result: Any) -> str:
    return bytes(getattr(result, "stdout", b"")).decode("utf-8", "strict").strip()


class AdbClient:
    """Fixed-device facade; the existing helper supplies subprocess behavior."""
    def __init__(self, raw: Any | None = None) -> None:
        if raw is None:
            serial = require_serial()
            raw = _helpers().Adb(ADB, serial)
        self.raw = raw
        self.deadline: float | None = None

    def run(self, args: Sequence[str], *, timeout: float = 30.0) -> Any:
        if self.deadline is not None:
            timeout = min(timeout, self.deadline - time.monotonic())
            if timeout <= 0:
                raise CollectorError("host deadline reached")
        result = self.raw.run(tuple(args), timeout=timeout)
        if self.deadline is not None and time.monotonic() > self.deadline:
            raise CollectorError("ADB result returned after host deadline")
        return result

    def shell(self, *args: str, timeout: float = 30.0) -> Any:
        return self.run(("shell", *args), timeout=timeout)

    def exec_out(self, *args: str, timeout: float = 30.0) -> Any:
        return self.run(("exec-out", *args), timeout=timeout)


def _check_private_components_no_follow(adb: AdbClient, path: str) -> None:
    """Check each fixed relative component before interpreting head failures.

    A dangling symlink produces the same ENOENT as a genuinely absent leaf when
    ``head`` follows links.  Reading each component with plain ``readlink``
    prevents that ambiguity without permitting arbitrary private paths.
    """
    components = path.split("/")
    if not components or components[0] != "files" or any(not item for item in components):
        raise CollectorError("private path is not a fixed relative path")
    for index in range(1, len(components) + 1):
        prefix = "/".join(components[:index])
        result = adb.shell("-T", "run-as", PACKAGE, "readlink", prefix, timeout=10)
        stdout = bytes(getattr(result, "stdout", b""))
        stderr = bytes(getattr(result, "stderr", b""))
        if len(stdout) > NOFOLLOW_CHECK_CAP or len(stderr) > NOFOLLOW_CHECK_CAP:
            raise CollectorError("private no-follow check exceeded bound")
        if stdout:
            raise CollectorError("private path contains a symbolic link")
        if result.returncode == 0 or stderr:
            lowered = stderr.lower()
            if result.returncode == 0 or not any(
                marker in lowered for marker in (b"no such file or directory", b"invalid argument")
            ):
                # Empty stderr is retained as a compatibility allowance for
                # toybox builds that report readlink's non-link result silently.
                if result.returncode != 0 and not stderr:
                    continue
                raise CollectorError("private no-follow check is unreadable")


def _device_private(adb: AdbClient, path: str, cap: int) -> tuple[bytes | None, str]:
    run_file = re.fullmatch(
        rf"{re.escape(RUN_ROOT_DEVICE)}/(NS-[0-9a-f]{{32}})/(run\.json|events\.jsonl|stdout|stderr|witness-stdout|witness-stderr)",
        path,
    )
    if path not in {LEGACY_CURRENT_DEVICE, CURRENT_DEVICE} and path not in LEGACY_NATIVE57_RECEIPT_PATHS and run_file is None:
        raise CollectorError("private path is outside the fixed native supervisor tree")
    _check_private_components_no_follow(adb, path)
    # exec-out uses the legacy exec transport: command failure can become rc=0
    # with stderr merged into stdout. Shell-v2 without a PTY preserves status
    # and binary stdout, so a missing first-run record is not malformed JSON.
    result = adb.shell("-T", "run-as", PACKAGE, "head", "-c", str(cap + 1), path, timeout=10)
    if result.returncode != 0:
        missing = f"head: {path}: No such file or directory\n".encode()
        return None, "missing" if not result.stdout and result.stderr == missing else "unreadable"
    if result.stderr or len(result.stdout) > cap:
        return None, "oversized-or-unreadable"
    return result.stdout, "ok"


def _preflight(directory: Path, expected_sha: str) -> dict[str, Any]:
    directory = _safe_directory(directory)
    candidates = sorted(directory.glob("state-*-READY_FOR_SINGLE_APPROVED_TRIAL.json"))
    if len(candidates) != 1:
        raise CollectorError("preflight must contain exactly one READY record")
    candidate = candidates[0]
    _regular(candidate, maximum=STREAM_CAP)
    record = strict_json(candidate.read_bytes(), cap=STREAM_CAP)
    if (record.get("result"), record.get("serial"), record.get("package"),
            record.get("versionCode"), record.get("versionName"),
            record.get("installedApkSha256"), record.get("retainedArtifactsVerified")) != (
                "READY_FOR_SINGLE_APPROVED_TRIAL", SERIAL, PACKAGE, VERSION_CODE,
                VERSION_NAME, expected_sha, True):
        raise CollectorError("preflight READY identity or APK pin mismatch")
    if record.get("legacyNative56") != LEGACY_NATIVE56 or record.get("legacyNative57") != LEGACY_NATIVE57:
        raise CollectorError("preflight legacy native56 receipt is not exact")
    before = record.get("bootBeforeReboot")
    after = record.get("bootAfterReboot")
    if not isinstance(before, str) or not BOOT_RE.fullmatch(before):
        raise CollectorError("preflight lacks pre-reboot boot identity")
    if (not isinstance(after, str) or not BOOT_RE.fullmatch(after) or after == before or
            after == LEGACY_NATIVE56["lastControlBootId"] or after == LEGACY_NATIVE57["attemptBootId"]):
        raise CollectorError("preflight lacks a new post-reboot boot identity")
    snapshot = record.get("ownUidProcessSnapshot")
    if not isinstance(snapshot, str) or not snapshot.startswith("UID PID PPID NAME\n"):
        raise CollectorError("preflight own-UID snapshot is not exact")
    rows = snapshot.splitlines()
    if len(rows) < 2 or any(
        len(row.split()) != 4 or row.split()[0] != str(UID) or
        any(not field.isdigit() for field in row.split()[1:3]) or row.split()[3] != "ps"
        for row in rows[1:]
    ):
        raise CollectorError("preflight own-UID snapshot contains invalid rows")
    return {"path": str(candidate), "sha256": sha256_file(candidate), "record": record}


def validate_apk(apk: Path, audit_path: Path, expected_sha: str) -> dict[str, Any]:
    if expected_sha is None:
        raise CollectorError("independent APK hash is required")
    if NATIVE_PINS_STATUS != "READY":
        raise CollectorError("new native supervisor pins are blocked")
    _regular(apk, maximum=2 * 1024 * 1024 * 1024)
    _regular(audit_path, maximum=STREAM_CAP)
    digest = sha256_file(apk)
    audit = strict_json(audit_path.read_bytes(), cap=STREAM_CAP)
    audit_sha = _fixed_sha(audit.get("sha256"), "audit APK")
    if digest != audit_sha or (expected_sha is not None and digest != _fixed_sha(expected_sha, "expected APK")):
        raise CollectorError("retained APK hash differs from validated audit")
    if (audit.get("schema"), audit.get("result"), audit.get("package"),
            audit.get("versionCode"), audit.get("versionName"), audit.get("certificateSha256")) != (
                "wowforever.private-apk-audit.v1", "PASS", PACKAGE, VERSION_CODE,
                VERSION_NAME, CERTIFICATE_SHA256):
        raise CollectorError("APK audit tuple or certificate is not approved")
    if audit.get("apk") and Path(str(audit["apk"])).resolve() != apk.resolve():
        raise CollectorError("audit does not identify the retained APK")
    provenance = audit.get("provenance")
    if not isinstance(provenance, dict) or provenance.get("nativeOnly") is not True or provenance.get("launchKind") != "native_run_supervisor":
        raise CollectorError("APK provenance is not native-only")
    native = provenance.get("nativeSupervisor")
    if not isinstance(native, dict) or native.get("variant") != NATIVE_SUPERVISOR_VARIANT or native.get("manifestSha256") != MANIFEST_SHA256:
        raise CollectorError("native manifest provenance is missing")
    files = native.get("files")
    if not isinstance(files, dict) or set(files) != set(NATIVE_PINS):
        raise CollectorError("native artifact set is not closed")
    for name, (size, pin) in NATIVE_PINS.items():
        item = files.get(name)
        if not isinstance(item, dict) or item.get("size", item.get("bytes")) != size or item.get("sha256") != pin:
            raise CollectorError("native artifact pin mismatch")
    disposition_raw, disposition = frozen_prelaunch_disposition()
    import zipfile
    canonical_contract = Path(__file__).resolve().parents[3] / "forever/contract.json"
    _regular(canonical_contract, maximum=JSON_CAP)
    contract_sha = sha256_file(canonical_contract)
    try:
        with zipfile.ZipFile(apk) as archive:
            if archive.read("assets/forever/contract.json") != canonical_contract.read_bytes():
                raise CollectorError("APK contract asset hash differs from canonical contract")
            asset_name = "assets/forever/native-supervisor/prelaunch-disposition.json"
            if archive.read(asset_name) != disposition_raw:
                raise CollectorError("prelaunch disposition asset differs from source")
    except (KeyError, zipfile.BadZipFile) as exc:
        raise CollectorError("prelaunch disposition asset is missing") from exc
    admission = provenance.get("prelaunchDisposition")
    if (not isinstance(admission, dict) or admission.get("assetSha256") != PRELAUNCH_DISPOSITION_SHA256 or
            admission.get("sourceSha256") != PRELAUNCH_DISPOSITION_SHA256 or
            admission.get("kind") != disposition.get("kind") or admission.get("authority") != "app-store-compile"):
        raise CollectorError("prelaunch disposition provenance is not independently pinned")
    inputs = provenance.get("inputs")
    if not isinstance(inputs, dict) or inputs.get("contract.json") != contract_sha:
        raise CollectorError("APK contract provenance hash is not canonical")
    return {"path": str(apk.resolve()), "bytes": apk.stat().st_size, "sha256": digest,
            "auditPath": str(audit_path.resolve()), "auditSha256": sha256_file(audit_path),
            "certificateSha256": CERTIFICATE_SHA256, "manifestSha256": native["manifestSha256"],
            "dispositionSha256": PRELAUNCH_DISPOSITION_SHA256, "contractSha256": contract_sha}


def validate_device(adb: AdbClient, expected_sha: str, preflight: dict[str, Any]) -> dict[str, Any]:
    if _text(adb.run(("get-state",), timeout=10)) != "device":
        raise CollectorError("fixed serial is not ready")
    props = {key: _text(adb.shell("getprop", prop, timeout=8)) for key, prop in (
        ("device", "ro.product.device"), ("model", "ro.product.model"),
        ("manufacturer", "ro.product.manufacturer"), ("api", "ro.build.version.sdk"))}
    if props != {"device": DEVICE, "model": MODEL, "manufacturer": MANUFACTURER, "api": str(API_LEVEL)}:
        raise CollectorError("fixed device identity mismatch")
    package_path = _text(adb.shell("pm", "path", PACKAGE, timeout=10))
    match = re.fullmatch(r"package:(/data/app/[A-Za-z0-9_~+=/.-]+/base\.apk)", package_path)
    if match is None:
        raise CollectorError("installed package path is not an exact base APK")
    dump = _text(adb.shell("dumpsys", "package", PACKAGE, timeout=15))
    if not re.search(rf"\bversionCode={VERSION_CODE}\b", dump) or not re.search(rf"\bversionName={re.escape(VERSION_NAME)}\b", dump):
        raise CollectorError("installed version tuple mismatch")
    if not (re.search(r"\bdebuggable=true\b", dump) or "DEBUGGABLE" in dump):
        raise CollectorError("installed package is not debuggable")
    # Android dumpsys prints a short Signature hash, not the certificate SHA256.
    # validate_apk pins the audited signing certificate; the exact installed
    # base.apk SHA256 below binds that same signed artifact without trusting it.
    uid = _text(adb.exec_out("run-as", PACKAGE, "id", "-u", timeout=10))
    if uid != str(UID):
        raise CollectorError("run-as UID mismatch")
    boot = _text(adb.exec_out("run-as", PACKAGE, "cat", "/proc/sys/kernel/random/boot_id", timeout=10))
    if boot != preflight["record"].get("bootAfterReboot") or not BOOT_RE.fullmatch(boot):
        raise CollectorError("current boot does not match preflight")
    installed = _text(adb.exec_out("run-as", PACKAGE, "sha256sum", match.group(1), timeout=30)).split()
    if installed != [expected_sha, match.group(1)]:
        raise CollectorError("installed base APK hash mismatch")
    return {**props, "serial": SERIAL, "uid": UID, "package": PACKAGE,
            "versionCode": VERSION_CODE, "versionName": VERSION_NAME,
            "apkPath": match.group(1), "apkSha256": installed[0], "bootId": boot,
            "certificateBinding": "exact-installed-audited-apk-sha256"}


def _current_payload(raw: bytes) -> dict[str, Any]:
    value = strict_json(raw, cap=JSON_CAP)
    run_id = value.get("runId")
    nonce = value.get("nonce")
    if not isinstance(run_id, str) or not RUN_RE.fullmatch(run_id) or not isinstance(nonce, str) or not NONCE_RE.fullmatch(nonce):
        raise CollectorError("native current record has invalid RUN identity")
    if value.get("schema") != 1 or value.get("variant") != NATIVE_SUPERVISOR_VARIANT:
        raise CollectorError("native current schema mismatch")
    return value


def _legacy_current_payload(raw: bytes) -> dict[str, Any]:
    if sha256_bytes(raw) != LEGACY_CURRENT_SHA256:
        raise CollectorError("legacy native56 current hash changed")
    value = strict_json(raw, cap=JSON_CAP)
    if (value.get("schema"), value.get("variant"), value.get("runId"), value.get("status"),
            value.get("nativeExit")) != (1, "native-run-supervisor-v1", LEGACY_NATIVE56["runId"],
                                           LEGACY_NATIVE56["status"], LEGACY_NATIVE56["nativeExit"]):
        raise CollectorError("legacy native56 current identity changed")
    return value


def read_legacy_current(adb: AdbClient) -> tuple[dict[str, Any], str]:
    raw, status = _device_private(adb, LEGACY_CURRENT_DEVICE, JSON_CAP)
    if raw is None:
        raise CollectorError(f"legacy native56 current is {status}")
    return _legacy_current_payload(raw), sha256_bytes(raw)


def read_current(adb: AdbClient) -> tuple[dict[str, Any] | None, str]:
    raw, status = _device_private(adb, CURRENT_DEVICE, JSON_CAP)
    if raw is None:
        return None, status
    try:
        return _current_payload(raw), "ok"
    except CollectorError:
        return None, "malformed"


def _new_run_id(old: dict[str, Any] | None, current: dict[str, Any]) -> bool:
    previous = old or {}
    return (current.get("runId") != previous.get("runId") and
            ("nonce" not in previous or current.get("nonce") != previous.get("nonce")))


def _node(state: Any, text: str, suffix: str) -> Any | None:
    matches = [
        node for node in state.root.iter("node")
        if node.attrib.get("text") == text and node.attrib.get("class", "").endswith(suffix)
    ]
    return matches[0] if len(matches) == 1 else None


def _focused(state: Any) -> list[Any]:
    return [node for node in state.root.iter("node") if node.attrib.get("focused") == "true"]


BUTTON_LABELS = {
    "start": ("Start control", "START CONTROL"),
    "cancel": ("Cancel control", "CANCEL CONTROL"),
}


def _button_node(state: Any, control: str) -> Any | None:
    """Find one of the two platform renderings of one known button label."""
    labels = BUTTON_LABELS[control]
    matches = [
        node for node in state.root.iter("node")
        if node.attrib.get("text") in labels and node.attrib.get("class", "").endswith("Button")
    ]
    return matches[0] if len(matches) == 1 else None


def _guard_ui(
    state: Any, expected: str | None = None, *, allow_no_focus: bool = False,
) -> Any | None:
    if not state.top_is_own() or not state.hierarchy_is_own():
        raise CollectorError("own UI focus guard failed")
    focused = _focused(state)
    if not focused and allow_no_focus:
        return None
    if len(focused) != 1 or focused[0].attrib.get("package") != PACKAGE:
        raise CollectorError("own UI has no unique own focus")
    if expected is None:
        return focused[0]
    if expected == "checkbox":
        node = _node(state, "I consent to start this native fixture control", "CheckBox")
    elif expected in BUTTON_LABELS:
        node = _button_node(state, expected)
    else:
        node = None
    if node is None or node is not focused[0] or node.attrib.get("enabled") != "true":
        raise CollectorError("expected own control is not focused")
    return node


def _guard_initial_controls(state: Any) -> Any | None:
    """Validate the idle fixture before permitting focus navigation input."""
    focused = _guard_ui(state, allow_no_focus=True)
    checkbox = _node(state, "I consent to start this native fixture control", "CheckBox")
    start = _button_node(state, "start")
    cancel = _button_node(state, "cancel")
    if (
        checkbox is None or start is None or cancel is None or
        any(node.attrib.get("package") != PACKAGE for node in (checkbox, start, cancel)) or
        checkbox.attrib.get("enabled") != "true" or
        checkbox.attrib.get("checked") != "false" or
        start.attrib.get("enabled") != "false" or
        cancel.attrib.get("enabled") != "false"
    ):
        raise CollectorError("native fixture controls are not in the initial state")
    return focused


def _fixed_readiness_reason(readiness: Any) -> str:
    reason = getattr(readiness, "reason", None)
    return reason if isinstance(reason, str) and reason in READINESS_FAILURE_REASONS else "unknown"


def _drive_consent_with_keyboard(
    adb: AdbClient, scenario: str, journal: list[dict[str, Any]], keyboard: Any,
    helper: ModuleType,
) -> None:
    """Drive consent using the already-open control HID context."""
    state = helper.dump_ui(adb.raw)
    focused = _guard_initial_controls(state)
    for index in range(9):
        if focused is not None:
            focused = _guard_ui(state)
        checkbox = _node(state, "I consent to start this native fixture control", "CheckBox")
        if checkbox is not None and focused is checkbox:
            _guard_ui(state, "checkbox")
            break
        if index == 8:
            raise CollectorError("consent checkbox was not focused within eight tabs")
        # A zero-focus idle hierarchy is valid only after the initial control
        # validation above.  Tab is focus navigation, not consent.
        helper.send_ui_key(keyboard, 43)
        journal.append({"reason": "tab-consent", "position": index + 1})
        state = helper.dump_ui(adb.raw)
        focused = _guard_ui(state)
    state = helper.dump_ui(adb.raw)
    checkbox = _guard_ui(state, "checkbox")
    if checkbox.attrib.get("checked") != "false":
        raise CollectorError("consent checkbox was not initially unchecked")
    helper.send_ui_key(keyboard, 44)
    journal.append({"reason": "consent-space", "position": "checkbox"})
    state = helper.dump_ui(adb.raw)
    checkbox = _guard_ui(state, "checkbox")
    if checkbox.attrib.get("checked") != "true":
        raise CollectorError("consent checkbox did not become checked")
    # Exactly one transition to Start control; there is no retry path.
    helper.send_ui_key(keyboard, 43)
    journal.append({"reason": "tab-start", "position": "start"})
    state = helper.dump_ui(adb.raw)
    _guard_ui(state, "start")
    cancel = _button_node(state, "cancel")
    if cancel is None or cancel.attrib.get("enabled") != "false":
        raise CollectorError("Cancel became enabled before running")
    journal.append({"reason": "start-enter-intent", "position": "start", "scenario": scenario})
    helper.send_ui_key(keyboard, 40)
    journal.append({"reason": "start-enter", "position": "start", "scenario": scenario})


def drive_consent(
    adb: AdbClient, scenario: str, journal: list[dict[str, Any]], keyboard: Any | None = None,
) -> None:
    """Wait for and drive the own Activity, without reopening control HID.

    A standalone caller may omit ``keyboard``; in that compatibility path the
    HID context is opened only after the initial own-focus/state guard.  The
    physical collector always supplies the context opened for wake, so its
    consent path cannot create a second HID session.
    """
    helper = _helpers()
    readiness = helper.wait_for_own_ui(adb.raw)
    if readiness.state is None:
        journal.append({"action": "readiness-failure", "reason": _fixed_readiness_reason(readiness)})
        raise CollectorError("own UI readiness failed")
    # The helper's readiness gate only admits the top package.  This stricter
    # dump is the last check before the first consent HID packet.
    state = helper.dump_ui(adb.raw)
    _guard_initial_controls(state)
    if keyboard is None:
        with helper.UsbKeyboard(SERIAL) as opened:
            _drive_consent_with_keyboard(adb, scenario, journal, opened, helper)
    else:
        _drive_consent_with_keyboard(adb, scenario, journal, keyboard, helper)


def _control_deadline(adb: AdbClient, seconds: float) -> float:
    """Return a local bound clipped before the global cleanup reserve."""
    now = time.monotonic()
    deadline = now + seconds
    if adb.deadline is not None:
        deadline = min(deadline, adb.deadline - CLEANUP_SECONDS)
    if deadline <= now:
        raise CollectorError("host deadline reached")
    return deadline


def _journal_failure(journal: list[dict[str, Any]] | None, reason: str) -> None:
    if journal is not None:
        journal.append({"action": "control-failure", "reason": reason})


def _parse_wakefulness(raw: bytes) -> bool:
    """Parse one exact mWakefulness field; never persist the power dump."""
    try:
        text = raw.decode("utf-8", "strict")
    except UnicodeDecodeError as exc:
        raise CollectorError("wakefulness power state read failed") from exc
    values = re.findall(r"(?m)^[ \t]*mWakefulness[ \t]*=[ \t]*([^\s]+)[ \t]*$", text)
    if len(values) != 1:
        raise CollectorError("wakefulness state was ambiguous")
    return values[0] == "Awake"


def _poll_wakefulness_awake(
    adb: AdbClient, journal: list[dict[str, Any]] | None = None,
) -> None:
    try:
        deadline = _control_deadline(adb, WAKEFULNESS_POLL_SECONDS)
    except CollectorError as exc:
        _journal_failure(journal, "wakefulness poll deadline reached")
        raise CollectorError("wakefulness poll deadline reached") from exc
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            _journal_failure(journal, "wakefulness poll deadline reached")
            raise CollectorError("wakefulness poll deadline reached")
        try:
            result = adb.shell("dumpsys", "power", timeout=min(remaining, 2.0))
            if result.returncode != 0 or result.stderr:
                raise CollectorError("wakefulness power state read failed")
            awake = _parse_wakefulness(result.stdout)
        except CollectorError as exc:
            _journal_failure(journal, str(exc))
            raise
        if time.monotonic() >= deadline:
            _journal_failure(journal, "wakefulness poll deadline reached")
            raise CollectorError("wakefulness poll deadline reached")
        if awake:
            if journal is not None:
                journal.append({"action": "wakefulness", "awake": True})
            return
        sleep_for = min(CONTROL_POLL_INTERVAL_SECONDS, deadline - time.monotonic())
        if sleep_for <= 0:
            _journal_failure(journal, "wakefulness did not become Awake")
            raise CollectorError("wakefulness did not become Awake")
        time.sleep(sleep_for)


def _policy_check(
    adb: AdbClient, helper: ModuleType, journal: list[dict[str, Any]] | None, *,
    stage: str,
) -> None:
    try:
        deadline = _control_deadline(adb, KEYGUARD_POLL_SECONDS)
        raw = adb.raw
        previous_raw_deadline = getattr(raw, "deadline", None)
        if hasattr(raw, "deadline"):
            raw.deadline = deadline if previous_raw_deadline is None else min(previous_raw_deadline, deadline)
        try:
            nonsecure = helper.policy_is_nonsecure(raw, deadline=deadline)
        finally:
            if hasattr(raw, "deadline"):
                raw.deadline = previous_raw_deadline
        if time.monotonic() >= deadline:
            raise CollectorError("keyguard policy poll deadline reached")
    except CollectorError:
        reason = f"keyguard policy was not explicitly nonsecure {stage}"
        _journal_failure(journal, reason)
        raise CollectorError(reason)
    except (OSError, ValueError, TypeError) as exc:
        reason = "keyguard policy poll deadline reached"
        _journal_failure(journal, reason)
        raise CollectorError(reason) from exc
    if nonsecure is not True:
        reason = f"keyguard policy was not explicitly nonsecure {stage}"
        _journal_failure(journal, reason)
        raise CollectorError(reason)


def _keyguard_delegate_state(raw: bytes) -> tuple[bool, bool]:
    """Read only exact showing/secure fields from one delegate block."""
    try:
        lines = raw.decode("utf-8", "strict").splitlines()
    except UnicodeDecodeError as exc:
        raise CollectorError("keyguard window policy read failed") from exc
    starts = [index for index, line in enumerate(lines)
              if line.strip() in {"KeyguardServiceDelegate", "KeyguardServiceDelegate:"}]
    if len(starts) != 1:
        raise CollectorError("keyguard window policy was ambiguous")
    start = starts[0]
    base_indent = len(lines[start]) - len(lines[start].lstrip())
    fields: dict[str, bool] = {}
    for line in lines[start + 1:]:
        if line.strip() and len(line) - len(line.lstrip()) <= base_indent:
            break
        match = re.fullmatch(r"[ \t]*(showing|secure)=(true|false)[ \t]*", line)
        if match is None:
            continue
        key = match.group(1)
        if key in fields:
            raise CollectorError("keyguard window policy was ambiguous")
        fields[key] = match.group(2) == "true"
    if set(fields) != {"showing", "secure"}:
        raise CollectorError("keyguard window policy was ambiguous")
    return fields["showing"], fields["secure"]


def _poll_keyguard_dismissed(
    adb: AdbClient, journal: list[dict[str, Any]] | None = None,
) -> None:
    try:
        deadline = _control_deadline(adb, KEYGUARD_POLL_SECONDS)
    except CollectorError as exc:
        _journal_failure(journal, "keyguard window poll deadline reached")
        raise CollectorError("keyguard window poll deadline reached") from exc
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            _journal_failure(journal, "keyguard window poll deadline reached")
            raise CollectorError("keyguard window poll deadline reached")
        try:
            result = adb.shell("dumpsys", "window", "policy", timeout=min(remaining, 2.0))
            if result.returncode != 0 or result.stderr:
                raise CollectorError("keyguard window policy read failed")
            showing, secure = _keyguard_delegate_state(result.stdout)
        except CollectorError as exc:
            _journal_failure(journal, str(exc))
            raise
        if time.monotonic() >= deadline:
            _journal_failure(journal, "keyguard window poll deadline reached")
            raise CollectorError("keyguard window poll deadline reached")
        if secure:
            _journal_failure(journal, "keyguard window policy was secure")
            raise CollectorError("keyguard window policy was secure")
        if not showing:
            if journal is not None:
                journal.append({"action": "keyguard-window", "showing": False, "secure": False})
            return
        sleep_for = min(CONTROL_POLL_INTERVAL_SECONDS, deadline - time.monotonic())
        if sleep_for <= 0:
            _journal_failure(journal, "keyguard remained showing after dismissal")
            raise CollectorError("keyguard remained showing after dismissal")
        time.sleep(sleep_for)


def _wake_nonsecure_with_keyboard(
    adb: AdbClient, evidence: Any, keyboard: Any, helper: ModuleType,
    journal: list[dict[str, Any]] | None = None,
) -> None:
    """Send one wake packet and one public dismissal, never unlock or retry."""
    keyboard.key(2, 0)
    evidence.action("wake-only-key", secure=False)
    _poll_wakefulness_awake(adb, journal)
    now = time.monotonic()
    remaining = float("inf")
    if adb.deadline is not None:
        remaining = adb.deadline - CLEANUP_SECONDS - now
    settle = min(WAKE_SETTLE_SECONDS, max(0.0, remaining))
    if settle <= 0:
        _journal_failure(journal, "wakefulness poll deadline reached")
        raise CollectorError("wakefulness poll deadline reached")
    time.sleep(settle)
    if adb.deadline is not None and time.monotonic() >= adb.deadline - CLEANUP_SECONDS:
        _journal_failure(journal, "wakefulness poll deadline reached")
        raise CollectorError("wakefulness poll deadline reached")
    evidence.action("wake-settle", settled=True)
    _policy_check(adb, helper, journal, stage="before dismiss")
    result = adb.shell("wm", "dismiss-keyguard", timeout=10)
    if result.returncode != 0 or result.stderr:
        _journal_failure(journal, "nonsecure public keyguard dismissal failed")
        raise CollectorError("nonsecure public keyguard dismissal failed")
    evidence.action("nonsecure-keyguard-dismissed")
    _policy_check(adb, helper, journal, stage="after dismiss")
    _poll_keyguard_dismissed(adb, journal)


def wake_nonsecure(adb: AdbClient, evidence: Any, keyboard: Any | None = None) -> None:
    """Check the exact nonsecure policy before opening or using wake HID."""
    helper = _helpers()
    _policy_check(adb, helper, None, stage="before HID")
    if keyboard is None:
        with helper.UsbKeyboard(SERIAL) as opened:
            _wake_nonsecure_with_keyboard(adb, evidence, opened, helper)
    else:
        _wake_nonsecure_with_keyboard(adb, evidence, keyboard, helper)


def _control_activity(
    adb: AdbClient, scenario: str, evidence: Any, journal: list[dict[str, Any]],
) -> None:
    """Run wake, Activity start, readiness, and consent in one HID context."""
    helper = _helpers()
    # This policy read deliberately precedes context entry: secure keyguard
    # means no wake packet and no HID context at all.
    _policy_check(adb, helper, journal, stage="before HID")
    with helper.UsbKeyboard(SERIAL) as keyboard:
        evidence.action("hid-context-enter")
        try:
            _wake_nonsecure_with_keyboard(adb, evidence, keyboard, helper, journal)
            started = adb.shell("am", "start", "-n", ALIAS, "--es", "scenario", scenario, timeout=15)
            if started.returncode != 0 or started.stderr or re.search(
                    r"(?m)^(?:Error|Exception|SecurityException)", _text(started)):
                raise CollectorError("native supervisor Activity did not start")
            evidence.action("activity-intent", position="before-USB-consent", scenario=scenario)
            drive_consent(adb, scenario, journal, keyboard)
        finally:
            evidence.action("hid-context-exit")


def _event_object(value: dict[str, Any], *, stored: bool) -> dict[str, Any]:
    expected = STORED_EVENT_KEYS if stored else EVENT_KEYS
    if set(value) != expected or (stored and value.get("kind") != "event"):
        raise CollectorError("native event fields are not exact")
    integer_keys = {"schema", "seq", "phase", "pid", "startTicks", "ppid", "uid0", "uid1", "uid2", "uid3", "exitCode", "childCount", "setResult", "getResult", "getValue"}
    if any(type(value[key]) is not int for key in integer_keys) or type(value["echild"]) is not bool:
        raise CollectorError("native event field types are invalid")
    if (value["schema"] != 1 or not isinstance(value["runId"], str) or
            not isinstance(value["nonce"], str) or not RUN_RE.fullmatch(value["runId"]) or
            not NONCE_RE.fullmatch(value["nonce"])):
        raise CollectorError("native event identity is invalid")
    if value["seq"] <= 0 or value["phase"] not in range(3) or value["event"] not in EVENT_NAMES or value["role"] not in ROLES or value["status"] not in EVENT_STATUSES:
        raise CollectorError("native event enum is invalid")
    if value["pid"] <= 0 or value["startTicks"] <= 0 or value["ppid"] <= 0 or value["childCount"] < 0:
        raise CollectorError("native event process identity is invalid")
    if any(value[key] != UID for key in ("uid0", "uid1", "uid2", "uid3")):
        raise CollectorError("native event UID4 is not the app UID")
    if any(not -(1 << 31) <= value[key] < (1 << 31) for key in integer_keys - {"seq", "startTicks"}):
        raise CollectorError("native event integer exceeds Kotlin bounds")
    if value["seq"] >= (1 << 63) or value["startTicks"] >= (1 << 63):
        raise CollectorError("native event long exceeds Kotlin bounds")
    event, role, status = value["event"], value["role"], value["status"]
    prctl = (value["setResult"], value["getResult"], value["getValue"])
    if event == "READY":
        if role not in {"supervisor", "witness"} or status != "READY" or value["phase"] != 0:
            raise CollectorError("invalid READY semantics")
        if prctl != ((0, 0, 1) if role == "supervisor" else (-1, -1, -1)):
            raise CollectorError("invalid READY prctl facts")
    elif prctl != (-1, -1, -1) or role == "witness":
        raise CollectorError("invalid non-READY prctl or witness event")
    if event != "EXIT" and value["exitCode"] != -1:
        raise CollectorError("non-EXIT event contains an exit code")
    if event in {"SPAWN", "ADOPT", "EXIT"}:
        if role not in {"intermediate", "grandchild"} or value["phase"] not in {1, 2}:
            raise CollectorError("invalid child event role or phase")
        if status != ("REAPED" if event == "EXIT" else "RUNNING"):
            raise CollectorError("invalid child event status")
    if event == "PHASE_DONE" and (role != "supervisor" or status != "PASS" or
            value["phase"] not in {1, 2} or value["childCount"] != 0 or not value["echild"]):
        raise CollectorError("invalid PHASE_DONE semantics")
    if event == "RUN_DONE" and (role != "supervisor" or value["phase"] != 0 or
            value["childCount"] != 0 or not value["echild"]):
        raise CollectorError("invalid RUN_DONE semantics")
    return value


def _intent_object(value: dict[str, Any]) -> dict[str, Any]:
    if set(value) != INTENT_KEYS or value.get("kind") != "intent" or value.get("intent") not in {"RUN", "START", "ACK", "STOP", "CLOSE"}:
        raise CollectorError("native intent fields are not exact")
    if type(value.get("phase")) is not int or type(value.get("seq")) is not int or value["phase"] not in range(3) or value["seq"] < 0:
        raise CollectorError("native intent types are invalid")
    return value


def parse_events(raw: bytes, run_id: str, nonce: str) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    if not raw or len(raw) > EVENTS_CAP or not raw.endswith(b"\n"):
        raise CollectorError("events transcript is missing, truncated, or oversized")
    lines = raw.splitlines(keepends=True)
    events: list[dict[str, Any]] = []
    intents: list[dict[str, Any]] = []
    next_event = 1
    next_command = 0
    process_markers: list[dict[str, Any]] = []
    supervisor_ready_seen = False
    run_intent_seen = False
    regular_start_seen = False
    for line in lines:
        if len(line) > LINE_CAP or not line.endswith(b"\n"):
            raise CollectorError("native IPC line bound exceeded")
        value = strict_json(line, cap=LINE_CAP)
        if value.get("kind") == "event":
            item = _event_object(value, stored=True)
            if item["runId"] != run_id or item["nonce"] != nonce or item["seq"] != next_event:
                raise CollectorError("native event sequence or identity mismatch")
            next_event += 1
            events.append(item)
        elif value.get("kind") == "intent":
            item = _intent_object(value)
            if item["seq"] != next_command:
                raise CollectorError("native command sequence mismatch")
            next_command += 1
            intents.append(item)
            if item["intent"] == "RUN":
                if run_intent_seen:
                    raise CollectorError("duplicate native RUN journal intent")
                run_intent_seen = True
            if item["intent"] in {"START", "CLOSE"}:
                regular_start_seen = True
        elif value.get("kind") == "process_create_intent":
            if set(value) != PROCESS_CREATE_INTENT_KEYS or value.get("role") not in {"supervisor", "witness"}:
                raise CollectorError("native process-create marker fields are not exact")
            if value.get("runId") != run_id or value.get("nonce") != nonce:
                raise CollectorError("native process-create marker identity mismatch")
            role = value["role"]
            if role == "supervisor":
                if process_markers or supervisor_ready_seen or not run_intent_seen:
                    raise CollectorError("duplicate or late supervisor process-create marker")
            elif role == "witness":
                if len(process_markers) != 1 or process_markers[0]["role"] != "supervisor" or not supervisor_ready_seen or regular_start_seen:
                    raise CollectorError("witness process-create marker is out of order")
            process_markers.append(value)
        elif value.get("kind") == "fault_intent":
            if set(value) != FAULT_KEYS or not isinstance(value.get("fault"), str) or not isinstance(value.get("detail"), str):
                raise CollectorError("native fault intent fields are invalid")
        else:
            raise CollectorError("unknown native transcript record")
        if value.get("kind") == "event" and value.get("event") == "READY" and value.get("role") == "supervisor":
            supervisor_ready_seen = True
    if not events:
        raise CollectorError("native event transcript is empty")
    if len(process_markers) != 2 or [item["role"] for item in process_markers] != ["supervisor", "witness"]:
        raise CollectorError("native process-create markers are missing or duplicated")
    return events, intents


def _identity_tuple(item: dict[str, Any]) -> tuple[int, int, int, tuple[int, int, int, int]]:
    return (item["pid"], item["startTicks"], item["ppid"], tuple(item[key] for key in ("uid0", "uid1", "uid2", "uid3")))


def _run_identities(run: dict[str, Any], events: Iterable[dict[str, Any]]) -> None:
    identities = run.get("identities")
    if not isinstance(identities, list) or not identities:
        raise CollectorError("native run lacks recorded identities")
    recorded = set()
    for item in identities:
        if not isinstance(item, dict) or set(item) != {"kind", "pid", "startTicks", "ppid", "uid0", "uid1", "uid2", "uid3"}:
            raise CollectorError("native recorded identity fields are invalid")
        if item["kind"] not in {"supervisor", "intermediate", "grandchild", "witness"} or any(type(item[key]) is not int for key in set(item) - {"kind"}):
            raise CollectorError("native recorded identity types are invalid")
        if item["pid"] <= 0 or item["startTicks"] <= 0 or item["ppid"] <= 0 or any(item[key] != UID for key in ("uid0", "uid1", "uid2", "uid3")):
            raise CollectorError("native recorded identity is not UID4-owned")
        recorded.add((item["kind"], item["pid"], item["startTicks"], item["ppid"]))
    for event in events:
        if event["role"] == "witness":
            continue
        key = (event["role"], event["pid"], event["startTicks"], event["ppid"])
        if key not in recorded:
            raise CollectorError("event has no app-recorded identity cross-link")


def _family_links(events: list[dict[str, Any]]) -> bool:
    """Check the kernel parent transitions retained in the flat transcript."""
    ready = events[0]
    supervisor = ready["pid"]
    for phase in (1, 2):
        phase_events = [item for item in events if item["phase"] == phase]
        if not phase_events:
            continue
        intermediate = next((item for item in phase_events if item["event"] == "SPAWN" and item["role"] == "intermediate"), None)
        grandchild = next((item for item in phase_events if item["event"] == "SPAWN" and item["role"] == "grandchild"), None)
        adopt = next((item for item in phase_events if item["event"] == "ADOPT"), None)
        if intermediate is None or grandchild is None or adopt is None:
            return False
        if intermediate["ppid"] != supervisor or grandchild["ppid"] != intermediate["pid"] or adopt["pid"] != grandchild["pid"] or adopt["startTicks"] != grandchild["startTicks"] or adopt["ppid"] != supervisor:
            return False
        for item in phase_events:
            if item["event"] == "EXIT" and item["role"] == "intermediate" and _identity_tuple(item) != _identity_tuple(intermediate):
                return False
            if item["event"] == "EXIT" and item["role"] == "grandchild" and (item["pid"] != grandchild["pid"] or item["startTicks"] != grandchild["startTicks"] or item["ppid"] != supervisor):
                return False
    return True


def _structural_native(run: dict[str, Any], events: list[dict[str, Any]], intents: list[dict[str, Any]], scenario: str) -> bool:
    if run.get("runId") != events[0]["runId"] or run.get("nonce") != events[0]["nonce"] or run.get("scenario") != scenario:
        return False
    if events[0]["event"] != "READY" or events[0]["phase"] != 0 or events[0]["role"] != "supervisor" or events[0]["status"] != "READY":
        return False
    ready = events[0]
    if any(item["role"] == "supervisor" and _identity_tuple(item) != _identity_tuple(ready) for item in events):
        return False
    if (ready["setResult"], ready["getResult"], ready["getValue"]) != (0, 0, 1):
        return False
    if not _family_links(events):
        return False
    names = [item["event"] for item in events]
    if scenario == "normal":
        expected = ["READY", "SPAWN", "SPAWN", "ADOPT", "EXIT", "EXIT", "PHASE_DONE",
                    "SPAWN", "SPAWN", "ADOPT", "EXIT", "EXIT", "PHASE_DONE", "RUN_DONE"]
        if (names != expected or len(intents) != 10 or
                [item["intent"] for item in intents] != ["RUN", "START", "ACK", "ACK", "ACK", "START", "ACK", "ACK", "ACK", "CLOSE"] or
                [item["phase"] for item in intents] != [0, 1, 1, 1, 1, 2, 2, 2, 2, 0]):
            return False
        if [item["phase"] for item in events] != [0, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 0]:
            return False
        if any(item["status"] != "REAPED" or item["exitCode"] != 0 for item in events if item["event"] == "EXIT"):
            return False
        if any(item["event"] == "PHASE_DONE" and (item["childCount"], item["echild"]) != (0, True) for item in events):
            return False
        return events[-1]["status"] == "PASS" and events[-1]["phase"] == 0 and events[-1]["childCount"] == 0 and events[-1]["echild"]
    if scenario == "cancel_before_start":
        return (names == ["READY", "RUN_DONE"] and [item["intent"] for item in intents] == ["RUN", "CLOSE"] and
                [item["phase"] for item in intents] == [0, 0] and events[-1]["status"] == "CANCELLED" and
                events[-1]["childCount"] == 0 and events[-1]["echild"])
    if scenario == "cancel_fork":
        return (names == ["READY", "SPAWN", "SPAWN", "EXIT", "ADOPT", "EXIT", "RUN_DONE"] and
                [item["intent"] for item in intents] == ["RUN", "START", "ACK", "STOP"] and
                [item["phase"] for item in intents] == [0, 1, 1, 1] and
                events[-1]["status"] == "CANCELLED" and events[-1]["childCount"] == 0 and events[-1]["echild"] and
                all(item["status"] == "REAPED" for item in events if item["event"] == "EXIT"))
    return False


def _witness_positive(run: dict[str, Any], witness_raw: bytes) -> bool:
    proof = run.get("witnessProof")
    if not isinstance(proof, dict) or proof.get("status") != "PASS" or proof.get("stableBeforeQ") is not True or proof.get("qSent") is not True or proof.get("reaped") is not True or type(proof.get("exit")) is not int or proof.get("exit") != 0:
        return False
    try:
        value = _event_object(strict_json(witness_raw, cap=LINE_CAP), stored=False)
    except CollectorError:
        return False
    recorded = run.get("identities", [])
    return (value["event"] == "READY" and value["role"] == "witness" and value["status"] == "READY" and
            value["setResult"] == -1 and value["getResult"] == -1 and value["getValue"] == -1 and
            value["runId"] == run["runId"] and value["nonce"] == run["nonce"] and
            proof.get("pid") == value["pid"] and proof.get("startTicks") == value["startTicks"] and
            any(isinstance(item, dict) and item.get("kind") == "witness" and
                item.get("pid") == value["pid"] and item.get("startTicks") == value["startTicks"] and
                item.get("ppid") == value["ppid"] for item in recorded))


def _typed_witness_proof(value: object) -> bool:
    if not isinstance(value, dict) or not isinstance(value.get("status"), str):
        return False
    if value["status"] in {"READY", "PASS"}:
        return (type(value.get("pid")) is int and value["pid"] > 0 and
                type(value.get("startTicks")) is int and value["startTicks"] > 0 and
                type(value.get("stableBeforeQ")) is bool and
                type(value.get("qSent")) is bool and type(value.get("reaped")) is bool and
                type(value.get("exit")) is int)
    return value["status"] == "UNPROVEN"


def terminal_semantic_equal(current: dict[str, Any], run: dict[str, Any]) -> bool:
    """Bind the terminal marker and run record before accepting either one."""
    if not isinstance(current, dict) or not isinstance(run, dict):
        return False
    if current.get("status") not in {"PASS", "CANCELLED", "UNKNOWN", "ERROR"}:
        return False
    for key in ("schema", "variant", "runId", "nonce", "scenario", "status",
                "nativeExit", "echild", "witnessProof"):
        if current.get(key) != run.get(key):
            return False
    if current.get("schema") != 1 or current.get("variant") != NATIVE_SUPERVISOR_VARIANT:
        return False
    if (not isinstance(current.get("runId"), str) or not isinstance(current.get("nonce"), str) or
            not RUN_RE.fullmatch(current["runId"]) or not NONCE_RE.fullmatch(current["nonce"])):
        return False
    if not isinstance(current.get("scenario"), str) or current["scenario"] not in SCENARIOS:
        return False
    if type(current.get("echild")) is not bool:
        return False
    if current.get("nativeExit") is not None and type(current.get("nativeExit")) is not int:
        return False
    return _typed_witness_proof(current.get("witnessProof"))


def terminal_evidence_stable(current: dict[str, Any], run: dict[str, Any],
                             after_current: dict[str, Any] | None,
                             after_run: dict[str, Any] | None) -> bool:
    return (after_current is not None and after_run is not None and
            terminal_semantic_equal(current, run) and
            terminal_semantic_equal(after_current, run) and
            terminal_semantic_equal(after_current, after_run))


def qualify_native(run: dict[str, Any], events_raw: bytes, witness_raw: bytes, scenario: str, *, app_status: str = "UNKNOWN") -> dict[str, Any]:
    result: dict[str, Any] = {
        "reportedNativeStatus": run.get("status", "UNKNOWN"), "reportedAppStatus": app_status,
        "apiQualification": False, "visualQualification": False, "wowQualification": False,
        "nativeFixtureQualification": "UNKNOWN", "outcome": "UNKNOWN",
    }
    if scenario not in SCENARIOS:
        result.update(nativeFixtureQualification="INVALID", outcome="INVALID")
        return result
    try:
        run_id, nonce = run["runId"], run["nonce"]
        if not RUN_RE.fullmatch(run_id) or not NONCE_RE.fullmatch(nonce) or run.get("schema") != 1 or run.get("variant") != NATIVE_SUPERVISOR_VARIANT:
            raise CollectorError("run identity/schema invalid")
        events, intents = parse_events(events_raw, run_id, nonce)
        _run_identities(run, events)
        coherent = _structural_native(run, events, intents, scenario)
        witness = _witness_positive(run, witness_raw)
    except (CollectorError, KeyError, TypeError):
        result["outcome"] = "INVALID" if scenario in {"eof", "invalid_protocol", "supervisor_failure"} else "UNKNOWN"
        return result
    if scenario in {"eof", "invalid_protocol", "supervisor_failure"}:
        # Fault controls never become global PASS, including a contradictory
        # application/native terminal record.
        result.update(nativeFixtureQualification="INVALID", outcome="INVALID")
    elif scenario == "normal" and coherent and run.get("status") == "PASS" and run.get("nativeExit") == 0 and run.get("echild") is True and witness:
        result.update(nativeFixtureQualification="GO", outcome="GO")
    elif scenario == "cancel_fork" and coherent and run.get("status") == "CANCELLED" and run.get("nativeExit") == 0 and run.get("echild") is True and witness:
        result.update(nativeFixtureQualification="CANCELLED", outcome="CANCELLED")
    elif scenario == "cancel_before_start" and coherent and run.get("status") == "CANCELLED" and run.get("nativeExit") == 0 and run.get("echild") is True and witness:
        result.update(nativeFixtureQualification="CANCELLED", outcome="CANCELLED")
    return result


class Evidence:
    def __init__(self, parent: Path, metadata: dict[str, Any]) -> None:
        self.parent = _safe_directory(parent, outside_repo=True)
        for _ in range(20):
            candidate = self.parent / ("native-supervisor-trial-" + uuid.uuid4().hex)
            try:
                candidate.mkdir(mode=0o700)
                break
            except FileExistsError:
                continue
        else:
            raise CollectorError("cannot allocate private evidence directory")
        self.root = candidate
        self.native = self.root / "native-supervisor"
        self.native.mkdir(mode=0o700)
        self.metadata = metadata
        _write_json(self.root / "source-runner.json", {
            "path": metadata.get("collector"), "sha256": metadata.get("collectorSha256")})
        _write_json(self.root / "protocol.json", {
            "path": metadata.get("protocol"), "sha256": metadata.get("protocolSha256")})
        _write_json(self.root / "manifest.json", {
            "variant": NATIVE_SUPERVISOR_VARIANT, "sha256": metadata.get("manifestSha256")})
        _write_json(self.root / "apk-identity.json", metadata.get("apk", {}))
        _write_json(self.root / "provenance.json", metadata)
        _fsync_dir(self.native)
        _fsync_dir(self.root)

    def action(self, event: str, **fields: Any) -> None:
        # Keep the action label separate from the optional reason detail.  A
        # number of failure paths intentionally record both, e.g.
        # action("transcript-missing", reason="...").
        value = {key: fields[key] for key in sorted(fields)}
        value["action"] = event
        path = self.root / "actions.jsonl"
        encoded = (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()
        if path.exists():
            with path.open("ab") as stream:
                if path.stat().st_size + len(encoded) > JSON_CAP:
                    raise CollectorError("action evidence exceeded bound")
                stream.write(encoded); stream.flush(); os.fsync(stream.fileno())
        else:
            _write_private(path, encoded)

    def capture(self, run: dict[str, Any], files: dict[str, bytes | None]) -> Path:
        run_id = run["runId"]
        if not RUN_RE.fullmatch(run_id):
            raise CollectorError("cannot create evidence for invalid RUN")
        directory = self.native / run_id
        expected = ("run.json", "events.jsonl", "stdout", "stderr", "witness-stdout", "witness-stderr")
        for name in expected:
            data = files.get(name)
            limit = EVENTS_CAP if name == "events.jsonl" else (JSON_CAP if name == "run.json" else STREAM_CAP)
            if data is not None and len(data) > limit:
                raise CollectorError("native evidence stream exceeded bound")
        directory.mkdir(mode=0o700)
        for name in expected:
            data = files.get(name)
            _write_private(directory / name, data if data is not None else b"")
        _fsync_dir(directory)
        _write_json(self.native / "current.json", run)
        _fsync_dir(self.native)
        return directory

    def capture_historical_native57(self, files: dict[str, bytes | None]) -> None:
        """Archive native57 bytes, recording missing device streams explicitly."""
        expected = {
            "current-before-disposition.json", "run.json", "events.jsonl", "stdout", "stderr",
            "witness-stdout", "witness-stderr", "prelaunch-disposition.json", "prelaunch-consumption.json",
        }
        if set(files) != expected:
            raise CollectorError("historical native57 evidence file set is not exact")
        directory = self.root / "historical-native57"
        directory.mkdir(mode=0o700)
        for name, data in files.items():
            if data is not None and len(data) > JSON_CAP:
                raise CollectorError("historical native57 evidence exceeded bound")
            # A host placeholder is allowed only as an explicitly labelled copy
            # of a missing device stream; it is never fed back to admission.
            _write_private(directory / name, data if data is not None else b"")
        _write_json(directory / "device-presence.json", {
            "schema": 1,
            "streams": {name: ("MISSING" if files.get(name) is None else "PRESENT")
                        for name in sorted(HISTORICAL_NATIVE57_ABSENT_STREAMS)},
        })
        _fsync_dir(directory)

    def finish(self, result: dict[str, Any]) -> None:
        _write_json(self.root / "result.json", result)
        seal: dict[str, Any] = {"schema": 1, "files": {}}
        for path in sorted(self.root.rglob("*")):
            if path.is_file() and path.name != "seal.json" and not path.is_symlink():
                info = _regular(path, maximum=EVENTS_CAP)
                seal["files"][str(path.relative_to(self.root))] = {"bytes": info.st_size, "sha256": sha256_file(path)}
        _write_json(self.root / "seal.json", seal)
        _fsync_dir(self.root)


def _read_run_files(adb: AdbClient, run_id: str) -> tuple[dict[str, Any] | None, dict[str, bytes | None], str]:
    run_raw, status = _device_private(adb, f"{RUN_ROOT_DEVICE}/{run_id}/run.json", JSON_CAP)
    if run_raw is None:
        return None, {}, status
    try:
        run = strict_json(run_raw, cap=JSON_CAP)
    except CollectorError:
        return None, {}, "malformed"
    files: dict[str, bytes | None] = {"run.json": run_raw}
    for name, cap in (("events.jsonl", EVENTS_CAP), ("stdout", STREAM_CAP), ("stderr", STREAM_CAP), ("witness-stdout", STREAM_CAP), ("witness-stderr", STREAM_CAP)):
        raw, _status = _device_private(adb, f"{RUN_ROOT_DEVICE}/{run_id}/{name}", cap)
        files[name] = raw
    return run, files, "ok"


def _read_historical57_run_files(
    adb: AdbClient, run_id: str,
) -> tuple[dict[str, Any] | None, dict[str, bytes | None], str]:
    """Read historical bytes, accepting absence only for its four old streams."""
    run_raw, status = _device_private(adb, f"{RUN_ROOT_DEVICE}/{run_id}/run.json", JSON_CAP)
    if run_raw is None:
        return None, {}, status
    if status != "ok":
        return None, {}, "unreadable"
    try:
        run = strict_json(run_raw, cap=JSON_CAP)
    except CollectorError:
        return None, {}, "malformed"
    files: dict[str, bytes | None] = {"run.json": run_raw}
    for name, cap in (("events.jsonl", EVENTS_CAP), ("stdout", STREAM_CAP), ("stderr", STREAM_CAP),
                      ("witness-stdout", STREAM_CAP), ("witness-stderr", STREAM_CAP)):
        raw, stream_status = _device_private(adb, f"{RUN_ROOT_DEVICE}/{run_id}/{name}", cap)
        if name in HISTORICAL_NATIVE57_ABSENT_STREAMS:
            if raw is not None or stream_status != "missing":
                raise CollectorError(f"historical native57 {name} is present or unreadable")
        elif raw is None or stream_status != "ok":
            raise CollectorError(f"historical native57 {name} is missing or unreadable")
        files[name] = raw
    return run, files, "ok"


def _history_snapshot(
    adb: AdbClient, current_archive: bytes | None = None,
) -> tuple[bytes, dict[str, bytes | None]]:
    """Capture the old archive and run files without converting missing to bytes."""
    if current_archive is None:
        current_archive, status = _device_private(
            adb, f"{LEGACY_NATIVE57_ROOT_DEVICE}/current-before-disposition.json", JSON_CAP)
        if current_archive is None or status != "ok":
            raise CollectorError("historical native57 current archive is missing")
    run, files, status = _read_historical57_run_files(adb, LEGACY_NATIVE57["runId"])
    if run is None or status != "ok":
        raise CollectorError("historical native57 RUN is missing")
    _validate_historical57_files(files)
    return current_archive, files


def _read_historical_native57(adb: AdbClient) -> tuple[dict[str, Any], dict[str, bytes | None], str]:
    """Read the live current plus the immutable historical native57 archive."""
    live_raw, live_status = _device_private(adb, CURRENT_DEVICE, JSON_CAP)
    if live_raw is None or live_status != "ok":
        raise CollectorError("historical native57 live current is missing")
    first_admission = sha256_bytes(live_raw) == LEGACY_NATIVE57["currentSha256"]
    if first_admission:
        # The original UNKNOWN current is the only valid first-admission
        # state.  The archived copy is not a substitute for a live current.
        current = _validate_historical57_current(live_raw)
        archive_bytes, run_files = _history_snapshot(adb, live_raw)
    else:
        live_current = _current_payload(live_raw)
        if live_current.get("runId") == LEGACY_NATIVE57["runId"]:
            raise CollectorError("historical native57 live current reused without exact original bytes")
        if live_current.get("status") not in {"PASS", "CANCELLED"}:
            raise CollectorError("historical native57 live current is not terminal")
        archive_raw, archive_status = _device_private(
            adb, f"{LEGACY_NATIVE57_ROOT_DEVICE}/current-before-disposition.json", JSON_CAP)
        if archive_raw is None or archive_status != "ok":
            raise CollectorError("historical native57 current archive is missing")
        _validate_historical57_current(archive_raw)
        archive_bytes, run_files = _history_snapshot(adb, archive_raw)
        current = live_current
    receipt_values: dict[str, bytes] = {}
    receipt_statuses: dict[str, str] = {}
    for name in LEGACY_NATIVE57_RECEIPT_NAMES:
        raw, receipt_status = _device_private(adb, f"{LEGACY_NATIVE57_ROOT_DEVICE}/{name}", JSON_CAP)
        receipt_statuses[name] = receipt_status
        if raw is not None:
            receipt_values[name] = raw
    if first_admission:
        if any(receipt_statuses[name] != "missing" for name in LEGACY_NATIVE57_RECEIPT_NAMES):
            raise CollectorError("partial or reused native57 admission receipts")
    else:
        if (any(receipt_statuses[name] != "ok" for name in LEGACY_NATIVE57_RECEIPT_NAMES) or
                any(receipt_values.get(name) is None for name in LEGACY_NATIVE57_RECEIPT_NAMES)):
            raise CollectorError("subsequent native59 control lacks complete native57 receipts")
        if receipt_values["current-before-disposition.json"] != archive_bytes:
            raise CollectorError("historical native57 current archive changed during admission")
        _validate_persistent_receipts_before(adb)
        for name in LEGACY_NATIVE57_RECEIPT_NAMES:
            after_raw, after_status = _device_private(
                adb, f"{LEGACY_NATIVE57_ROOT_DEVICE}/{name}", JSON_CAP)
            if after_status != "ok" or after_raw != receipt_values[name]:
                raise CollectorError("historical native57 admission receipts changed during admission")
    live_after_raw, live_after_status = _device_private(adb, CURRENT_DEVICE, JSON_CAP)
    if live_after_status != "ok" or live_after_raw != live_raw:
        raise CollectorError("historical native57 live current changed during admission")
    receipt_files = {
        "current-before-disposition.json": archive_bytes,
        **run_files,
        "prelaunch-disposition.json": receipt_values.get("prelaunch-disposition.json"),
        "prelaunch-consumption.json": receipt_values.get("prelaunch-consumption.json"),
    }
    return current, receipt_files, sha256_bytes(archive_bytes)


def _read_historical_receipts_after(
    adb: AdbClient, first_run: dict[str, Any], boot_id: str, *,
    first_admission: bool, persistent_receipt: dict[str, Any] | None,
) -> tuple[bytes, bytes]:
    disposition_raw, disposition_status = _device_private(adb, f"{LEGACY_NATIVE57_ROOT_DEVICE}/prelaunch-disposition.json", JSON_CAP)
    consumption_raw, consumption_status = _device_private(adb, f"{LEGACY_NATIVE57_ROOT_DEVICE}/prelaunch-consumption.json", JSON_CAP)
    if disposition_raw is None or disposition_status != "ok" or consumption_raw is None or consumption_status != "ok":
        raise CollectorError("historical prelaunch receipts are missing")
    disposition = _validate_dynamic_prelaunch_abort(
        disposition_raw, fresh_boot_id=boot_id if first_admission else None)
    consumption = _validate_dynamic_consumption(
        consumption_raw, disposition_sha=sha256_bytes(disposition_raw), fresh_boot_id=disposition["freshBootId"],
        expected_run=first_run if first_admission else None)
    if first_admission:
        if consumption.get("firstAdmittedRunId", consumption["newRunId"]) != first_run["runId"]:
            raise CollectorError("prelaunch consumption first RUN binding changed")
    elif persistent_receipt is None or consumption != persistent_receipt:
        raise CollectorError("historical prelaunch receipts changed after first admission")
    return disposition_raw, consumption_raw


def _validate_persistent_receipts_before(adb: AdbClient) -> dict[str, Any]:
    stored, stored_status = _device_private(adb, f"{LEGACY_NATIVE57_ROOT_DEVICE}/prelaunch-disposition.json", JSON_CAP)
    consumption_raw, consumption_status = _device_private(adb, f"{LEGACY_NATIVE57_ROOT_DEVICE}/prelaunch-consumption.json", JSON_CAP)
    if stored_status != "ok" or stored is None or consumption_status != "ok" or consumption_raw is None:
        raise CollectorError("historical prelaunch receipts are missing")
    disposition = _validate_dynamic_prelaunch_abort(stored, fresh_boot_id=None)
    consumption = _validate_dynamic_consumption(
        consumption_raw, disposition_sha=sha256_bytes(stored), fresh_boot_id=disposition["freshBootId"])
    if consumption.get("firstAdmittedRunId", consumption["newRunId"]) != consumption["newRunId"]:
        raise CollectorError("historical prelaunch first RUN binding changed")
    return consumption


def _bind_historical_receipt_bytes(
    before: dict[str, bytes | None], disposition: bytes, consumption: bytes, *,
    first_admission: bool,
) -> None:
    """Bind validated first receipts, or preserve the exact subsequent baseline."""
    for name, raw in (("prelaunch-disposition.json", disposition),
                      ("prelaunch-consumption.json", consumption)):
        if first_admission:
            if before.get(name) is not None:
                raise CollectorError("historical native57 first receipt baseline is not missing")
        elif before.get(name) != raw:
            raise CollectorError(f"historical native57 receipt changed during trial: {name}")
    if first_admission:
        before["prelaunch-disposition.json"] = disposition
        before["prelaunch-consumption.json"] = consumption


def _verify_historical_native57_stable(adb: AdbClient, before: dict[str, bytes | None], *, receipts_must_exist: bool) -> None:
    current_raw, files = _history_snapshot(adb)
    if sha256_bytes(current_raw) != sha256_bytes(before["current-before-disposition.json"]):
        raise CollectorError("historical native57 current changed during trial")
    for name in HISTORICAL_NATIVE57_RUN_FILES:
        if files.get(name) != before[name]:
            raise CollectorError(f"historical native57 {name} changed during trial")
    for name in LEGACY_NATIVE57_RECEIPT_NAMES[1:]:
        raw, status = _device_private(adb, f"{LEGACY_NATIVE57_ROOT_DEVICE}/{name}", JSON_CAP)
        if receipts_must_exist and (raw is None or status != "ok" or raw != before[name]):
            raise CollectorError(f"historical native57 receipt changed during trial: {name}")
        if not receipts_must_exist and (raw is not None or status != "missing"):
            raise CollectorError(f"historical native57 receipt appeared during trial: {name}")


def _result_base(*, native: str, app: str, outcome: str) -> dict[str, Any]:
    return {"schema": 1, "reportedNativeStatus": native, "reportedAppStatus": app,
            "apiQualification": False, "visualQualification": False, "wowQualification": False,
            "nativeFixtureQualification": "NOT_MEASURED" if outcome == "NOT_MEASURED" else "UNKNOWN",
             "outcome": outcome}


def _flush_control_journal(evidence: Evidence, journal: list[dict[str, Any]]) -> None:
    for item in journal:
        fields = dict(item)
        action = fields.pop("action", None)
        if action is None:
            action = fields.pop("reason", "control-step")
        evidence.action(str(action), **fields)
    journal.clear()


def _safe_control_detail(exc: BaseException) -> str | None:
    """Return only an allowlisted CollectorError message for evidence."""
    if isinstance(exc, CollectorError) and str(exc) in SAFE_CONTROL_FAILURE_DETAILS:
        return str(exc)
    return None


def _verify_legacy_unchanged(adb: AdbClient, before_sha: str, evidence: Evidence) -> None:
    _record, after_sha = read_legacy_current(adb)
    if after_sha != before_sha:
        raise CollectorError("legacy native56 current changed during trial")
    evidence.action("legacy-native56-unchanged", verified=True)


def collect(args: argparse.Namespace) -> int:
    scenario = args.scenario
    # supervisor_failure is deliberately retained for pure transcript
    # qualification, but never reaches ADB, USB, or Activity input.
    if scenario == "supervisor_failure":
        raise CollectorError("supervisor_failure is HOST/JVM-only")
    if scenario not in PHYSICAL_SCENARIOS:
        raise CollectorError("scenario is not allowlisted")
    apk = validate_apk(args.apk, args.audit, args.expected_apk_sha256)
    expected_sha = apk["sha256"]
    preflight = _preflight(args.preflight_directory, expected_sha)
    adb = AdbClient()
    device = validate_device(adb, expected_sha, preflight)
    legacy_before, legacy_before_sha = read_legacy_current(adb)
    if legacy_before["runId"] != LEGACY_NATIVE56["runId"]:
        raise CollectorError("legacy native56 current is not the approved RUN")
    old_current, old_status = read_current(adb)
    if old_status not in {"ok", "missing"}:
        raise CollectorError("historical native current record is malformed")
    if (old_current is not None and old_current.get("status") in NON_TERMINAL_STATUSES and
            old_current.get("runId") != LEGACY_NATIVE57["runId"]):
        raise CollectorError("historical native RUN is still non-terminal")
    historical_run, historical_files, historical_archive_sha = _read_historical_native57(adb)
    if old_current != historical_run:
        raise CollectorError("historical native current changed during admission")
    first_admission = historical_run.get("runId") == LEGACY_NATIVE57["runId"]
    if first_admission:
        if old_current != historical_run:
            raise CollectorError("historical native57 current does not match its archived RUN")
    elif old_current is None or old_current.get("status") not in {"PASS", "CANCELLED"}:
        raise CollectorError("native59 admission requires a terminal historical RUN")
    persistent_receipt = None if first_admission else _validate_persistent_receipts_before(adb)
    runner_path = Path(__file__)
    protocol_path = runner_path.with_name("NATIVE-SUPERVISOR-IPC.md")
    _regular(runner_path)
    _regular(protocol_path, maximum=STREAM_CAP)
    metadata = {
        "schema": 1, "collector": str(runner_path.resolve()),
        "collectorSha256": sha256_file(runner_path),
        "protocol": str(protocol_path.resolve()),
        "protocolSha256": sha256_file(protocol_path),
        "manifestSha256": apk["manifestSha256"], "apk": apk, "preflight": preflight,
        "device": device, "scenario": scenario, "oldCurrentStatus": old_status,
        "oldRunId": old_current.get("runId") if old_current else None,
        "historicalNative57": {
            "runId": LEGACY_NATIVE57["runId"],
            "currentArchiveSha256": historical_archive_sha,
            "dispositionSha256": PRELAUNCH_DISPOSITION_SHA256,
            "firstAdmittedRunId": persistent_receipt.get("firstAdmittedRunId") if persistent_receipt else None,
        },
        "adb": ADB, "serial": SERIAL,
    }
    evidence = Evidence(args.output, metadata)
    # The directory is created only after all pre-input admission checks.  Its
    # contents are immutable copies of the old run; consumption is filled only
    # after the new RUN has been observed and validated by the app/store.
    evidence.action("legacy-native56-verified", verified=True)
    evidence.action("validated-before-control", serial=SERIAL, scenario=scenario)
    deadline = time.monotonic() + HOST_SECONDS
    adb.deadline = deadline
    # wait_for_own_ui/dump_ui are imported from the existing helper and use
    # its own deadline attribute when they issue bounded ADB calls.
    adb.raw.deadline = deadline
    intent_sent = False
    current: dict[str, Any] | None = None
    run: dict[str, Any] | None = None
    files: dict[str, bytes | None] = {}
    app_status = "UNKNOWN"
    journal: list[dict[str, Any]] = []
    operation_deadline = deadline - CLEANUP_SECONDS
    try:
        _control_activity(adb, scenario, evidence, journal)
        intent_sent = True
        evidence.action("consent-gated-start-intent", position="start-button", scenario=scenario)
        _flush_control_journal(evidence, journal)
        while time.monotonic() < operation_deadline:
            candidate, status = read_current(adb)
            if candidate is not None and _new_run_id(old_current, candidate):
                if candidate.get("scenario") != scenario:
                    raise CollectorError("new native RUN scenario mismatch")
                current = candidate
                evidence.action("new-run-before-transcript", position="current.json", runId=current["runId"])
                break
            time.sleep(min(0.25, max(0.0, operation_deadline - time.monotonic())))
        if current is None:
            result = _result_base(native="MISSING", app="UNKNOWN", outcome="UNKNOWN")
            evidence.action("transcript-missing", reason="no-different-native-run")
            _verify_legacy_unchanged(adb, legacy_before_sha, evidence)
            _verify_historical_native57_stable(adb, historical_files, receipts_must_exist=False)
            adb.deadline = time.monotonic() + CLEANUP_SECONDS
            adb.raw.deadline = adb.deadline
            adb.shell("am", "force-stop", PACKAGE, timeout=10)
            evidence.action("owned-package-force-stop-after-evidence", reason="missing-run-cleanup")
            evidence.finish(result)
            return 1
        while time.monotonic() < operation_deadline:
            observed, status = read_current(adb)
            if observed is None or observed.get("runId") != current["runId"]:
                raise CollectorError("native current RUN changed or became unreadable")
            app_status = str(observed.get("status", "UNKNOWN"))
            if app_status in {"PASS", "CANCELLED", "UNKNOWN", "ERROR"}:
                current = observed
                break
            time.sleep(min(0.25, max(0.0, operation_deadline - time.monotonic())))
        # Evidence capture has the reserved final ten seconds and may still
        # read a terminal record produced at the edge of the operation window.
        adb.deadline = deadline
        run, files, file_status = _read_run_files(adb, current["runId"])
        if run is None:
            result = _result_base(native="MISSING", app=app_status, outcome="UNKNOWN")
            evidence.action("transcript-missing", reason=file_status)
        else:
            if run.get("runId") != current.get("runId") or run.get("nonce") != current.get("nonce"):
                raise CollectorError("native run record does not bind to current RUN")
            evidence.capture(run, files)
            stable, _stable_status = read_current(adb)
            post_run_raw, _post_run_status = _device_private(
                adb, f"{RUN_ROOT_DEVICE}/{run['runId']}/run.json", JSON_CAP)
            post_run: dict[str, Any] | None = None
            if post_run_raw is not None:
                try:
                    post_run = strict_json(post_run_raw, cap=JSON_CAP)
                except CollectorError:
                    post_run = None
            terminal_ok = terminal_evidence_stable(current, run, stable, post_run)
            if not terminal_ok:
                result = _result_base(native=str(run.get("status", "UNKNOWN")), app=app_status, outcome="UNKNOWN")
                evidence.action("terminal-current-run-mismatch", runId=run["runId"])
            else:
                witness = files.get("witness-stdout") or b""
                result = qualify_native(run, files.get("events.jsonl") or b"", witness, scenario, app_status=app_status)
                evidence.action("native-evidence-captured", runId=run["runId"], native=result["nativeFixtureQualification"])
        _verify_legacy_unchanged(adb, legacy_before_sha, evidence)
        if run is not None:
            # On the first admission these receipts are created by the valid
            # native RUN.  Validate and retain their exact device bytes before
            # the historical archive/stability check; the stability check
            # must compare against the newly admitted receipt set, not the
            # deliberately missing pre-trial set.
            disposition_raw, consumption_raw = _read_historical_receipts_after(
                adb, run, device["bootId"], first_admission=first_admission,
                persistent_receipt=persistent_receipt)
            _bind_historical_receipt_bytes(
                historical_files, disposition_raw, consumption_raw,
                first_admission=first_admission)
            if first_admission:
                result["historicalAdmission"] = {
                    "priorRunId": LEGACY_NATIVE57["runId"],
                    "currentArchiveSha256": historical_archive_sha,
                    "dispositionSha256": sha256_bytes(disposition_raw),
                    "consumptionSha256": sha256_bytes(consumption_raw),
                }
        _verify_historical_native57_stable(
            adb, historical_files, receipts_must_exist=(run is not None))
        if run is not None:
            evidence.capture_historical_native57(historical_files)
        if run is not None and app_status not in {"PASS", "CANCELLED"}:
            # This is an owned package cleanup, after evidence capture only; it
            # is never recorded as proof that unrelated processes are quiescent.
            adb.deadline = time.monotonic() + CLEANUP_SECONDS
            adb.raw.deadline = adb.deadline
            adb.shell("am", "force-stop", PACKAGE, timeout=10)
            evidence.action("owned-package-force-stop-after-evidence", reason="technical-cleanup")
        evidence.finish(result)
        return 0 if result["outcome"] in {"GO", "CANCELLED"} else 1
    except (CollectorError, OSError, ValueError) as exc:
        intent_sent = intent_sent or any(
            item.get("reason") == "start-enter-intent" or item.get("action") == "start-enter-intent"
            for item in journal
        )
        _flush_control_journal(evidence, journal)
        if current is not None:
            try:
                adb.deadline = max(deadline, time.monotonic()) + CLEANUP_SECONDS
                adb.raw.deadline = adb.deadline
                run, files, _ = _read_run_files(adb, current["runId"])
                if run is not None:
                    evidence.capture(run, files)
            except (CollectorError, OSError, ValueError):
                pass
        if "legacy_before_sha" in locals():
            try:
                _verify_legacy_unchanged(adb, legacy_before_sha, evidence)
            except (CollectorError, OSError, ValueError):
                evidence.action("legacy-native56-unchanged", verified=False)
        if intent_sent:
            try:
                adb.deadline = time.monotonic() + CLEANUP_SECONDS
                adb.raw.deadline = adb.deadline
                adb.shell("am", "force-stop", PACKAGE, timeout=10)
                evidence.action("owned-package-force-stop-after-evidence", reason="exception-cleanup")
            except (CollectorError, OSError, ValueError):
                evidence.action("owned-package-force-stop-failed", reason="exception-cleanup")
        result = _result_base(native="UNKNOWN" if intent_sent else "MISSING", app=app_status, outcome="UNKNOWN" if intent_sent else "NOT_MEASURED")
        failure_fields: dict[str, Any] = {"reason": "bounded-control-failure"}
        detail = _safe_control_detail(exc)
        if detail is not None:
            failure_fields["detail"] = detail
        evidence.action("prelaunch-abort" if not intent_sent else "collector-abort", **failure_fields)
        evidence.finish(result)
        return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True, help="retained candidate APK")
    parser.add_argument("--audit", type=Path, required=True, help="validated candidate APK audit")
    parser.add_argument("--preflight-directory", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="existing external evidence parent")
    parser.add_argument("--scenario", choices=sorted(PHYSICAL_SCENARIOS), required=True)
    parser.add_argument("--expected-apk-sha256", required=True, help="independent retained APK SHA-256 pin")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return collect(args)
    except CollectorError as exc:
        print(f"NATIVE-SUPERVISOR-ABORT {exc}", file=sys.stderr)
        return 2
    except (OSError, ValueError) as exc:
        print(f"NATIVE-SUPERVISOR-ABORT {type(exc).__name__}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
