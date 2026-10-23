#!/usr/bin/env python3
"""Host-side, one-attempt collector for the Wine 0.2.38 server gate.

This collector is intentionally narrower than the older native fixture
collector.  It starts only the reviewed DUMP-protected Wine Activity and
admits only the closed SERVER/CLIENT_CONTROL profile.  It never installs an
APK, reboots a device, executes a device supplied command, reads arbitrary
paths from JSON, or claims graphics, API, WoW, authentication, or memory
evidence.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import importlib.util
import json
import os
import posixpath
import re
import shlex
import stat
import sys
import time
import uuid
import zipfile
from dataclasses import dataclass
from pathlib import Path
from types import ModuleType
from typing import Any, Iterable, Sequence

try:
    from wine_interrupted_run_contract import load_contract, verify_external_evidence, ContractError
except ModuleNotFoundError:
    _contract_spec = importlib.util.spec_from_file_location(
        "wine_interrupted_run_contract", Path(__file__).with_name("wine_interrupted_run_contract.py")
    )
    if _contract_spec is None or _contract_spec.loader is None:
        raise
    _contract_module = importlib.util.module_from_spec(_contract_spec)
    sys.modules["wine_interrupted_run_contract"] = _contract_module
    _contract_spec.loader.exec_module(_contract_module)
    load_contract = _contract_module.load_contract
    verify_external_evidence = _contract_module.verify_external_evidence
    ContractError = _contract_module.ContractError

_continuation_spec = importlib.util.spec_from_file_location(
    "wine_prefix_prelaunch_continuation_contract", Path(__file__).with_name("wine_prefix_prelaunch_continuation_contract.py")
)
if _continuation_spec is None or _continuation_spec.loader is None:
    raise ImportError("Wine continuation contract unavailable")
_continuation_module = importlib.util.module_from_spec(_continuation_spec)
sys.modules["wine_prefix_prelaunch_continuation_contract"] = _continuation_module
_continuation_spec.loader.exec_module(_continuation_module)
load_continuation_contract = _continuation_module.load_contract
verify_continuation_external_evidence = _continuation_module.verify_external_evidence


ADB = "/home/adrian/android-sdk/platform-tools/adb"
SERIAL_ENV_VAR = "WOWFOREVER_ADB_SERIAL"
SERIAL = os.environ.get(SERIAL_ENV_VAR, "")
DEVICE = "xaga"
MODEL = "22041216G"
MANUFACTURER = "Xiaomi"
API_LEVEL = 34
PACKAGE = "com.wowforever.arm64probe"
UID = 10330
VERSION_CODE = 62
VERSION_NAME = "0.2.38-automated"
CERTIFICATE_SHA256 = "2b082e8200e066e665f73e71f547f827d6674634298024cb0b823be22adb242b"
OLD_APK60_SHA256 = "76db60beacfb6d62a0f55b16c4d78b06e9b4351fd9a28de5695849b340800c24"
ALIAS = f"{PACKAGE}/app.gamenative.ForeverWineSupervisorLauncher"
ACTIVITY_PROCESS = ":wine_supervisor"

SUPERVISOR_SIZE = 45_056
SUPERVISOR_SHA256 = "6f641c3683b239eb58a3986e947afe2099555ef138d495f8490a000b5b52f0da"
PUBLISHER_SIZE = 123_560
PUBLISHER_SHA256 = "f479ea8def9dc3cffd42affa489ae1554f9214273b13af7d79053a2773ad6914"
MANIFEST_SHA256 = "8c9bcde26c1ae3a7ebcb620db47b5a5d4f9dc450f021ae4b4d97b28785b3b894"
NATIVE_VARIANT = "wine-run-supervisor-v1"
NATIVE_PINS = {"libforever_wine_supervisor.so": (SUPERVISOR_SIZE, SUPERVISOR_SHA256)}
PREFIX_RESOURCE_PINS = {
    "forever/wine-supervisor/manifest.json": (4745, MANIFEST_SHA256),
    "forever/wine-prefix/publisher-manifest.json": (2368, "63d5b1bf80e9039030839c7fd37139f9d9ea9c8f78cd2d3f3eee7de5ba99922d"),
    "forever/wine-prefix/prefix-image.zip": (47680132, "42f9e9119682f5e3cbd9071efe3f079d0cac76896fe3ce0fa9b2ef0d41048853"),
    "forever/wine-prefix/image-manifest.json": (124991, "2318de72bb020da9263300511a2139abbd06c228f46fac1708d0fb759e625ba9"),
    "lib/arm64-v8a/libforever_wine_supervisor.so": (SUPERVISOR_SIZE, SUPERVISOR_SHA256),
    "lib/arm64-v8a/libforever_wine_prefix.so": (PUBLISHER_SIZE, PUBLISHER_SHA256),
}
SOURCE_BASELINE_SHA256 = "0a3d039a1d018230a9a99ab7c4de45d7e5753b75626868c8f06cad3678fdd0d8"
IMAGE_MANIFEST_SHA256 = PREFIX_RESOURCE_PINS["forever/wine-prefix/image-manifest.json"][1]
IMAGE_ZIP_SHA256 = PREFIX_RESOURCE_PINS["forever/wine-prefix/prefix-image.zip"][1]

REBOOT_SENTINEL_BOOT = "9aaa217e-168e-468e-b5cd-970345b84370"
PREPARED_RECEIPT_REL = "files/forever/wine-controls/{ws}/prepared.receipt"
WINE_OUTPUT_REL = "files/forever/wine-controls/{ws}/prefix/.wine/.supervisor-output"

GENERIC_CURRENT = "files/forever/current.json"
RUN_ROOT = "files/forever/runs"
OLD_V1_CURRENT = "files/forever/controls/native-supervisor/current.json"
OLD_V2_CURRENT = "files/forever/controls/native-supervisor-v2/current.json"
OLD_V2_RUN = "NS-1951490a6a0bc710f0ea3e219f582e46"
OLD_V2_CURRENT_SHA256 = "f067e510b36e37e4c7e19c6c0163d36f125c5faa1f7335538271de474cd388d9"
OLD_V2_PERSISTENT_ROOT = "files/forever/controls/native-supervisor-v2/NS-b0a3fe48f925d7d656f597f740b4b413"
OLD_V2_PERSISTENT_FILES = (
    "current-before-disposition.json", "run.json", "events.jsonl",
    "prelaunch-disposition.json", "prelaunch-consumption.json",
)
OLD_V1_CURRENT_SHA256 = "c9c899f579314d193a91a7bd935be5df16d6dcc1ece39d42863a9ab15064685c"
OLD_V2_ARCHIVE_SHA256 = "73428e2c77bdeafc5687a5860821b67b7f1489966f1cae62d04a12318c57244d"
OLD_V2_RUN_SHA256 = OLD_V2_ARCHIVE_SHA256
OLD_V2_EVENTS_SHA256 = "e81a47a4da7aa10ec844f14d821e6109b4584495c381459a49a96d5ceaaa4693"
OLD_V2_DISPOSITION_SHA256 = "d289189baa2d1c55a365234434c8694265db6600cb40563a9bf686600b7146ba"
OLD_V2_CONSUMPTION_SHA256 = "8e8dd9267f6a558c219b53474577e801f8779a4ec988c0c7f37c8006107e0e3f"
OLD_V2_HISTORY_SHA256 = {
    "current-before-disposition.json": OLD_V2_ARCHIVE_SHA256, "run.json": OLD_V2_RUN_SHA256,
    "events.jsonl": OLD_V2_EVENTS_SHA256, "prelaunch-disposition.json": OLD_V2_DISPOSITION_SHA256,
    "prelaunch-consumption.json": OLD_V2_CONSUMPTION_SHA256,
}

HOST_SECONDS = 240
CLEANUP_SECONDS = 20
CONTROL_SECONDS = 60.0
UI_CLEANUP_SECONDS = 2.0
PREFIX_PREPARE_SECONDS = 120.0
LINE_CAP = 8 * 1024
TOTAL_CAP = 256 * 1024
STREAM_CAP = 64 * 1024
JSON_CAP = 64 * 1024
RUN_RE = re.compile(r"^RUN-[A-Za-z0-9_.-]+$")
WS_RE = re.compile(r"^WS-[0-9a-f]{32}$")
NONCE_RE = re.compile(r"^[0-9a-f]{32}$")
SHA_RE = re.compile(r"^[0-9a-f]{64}$")
BOOT_RE = re.compile(r"^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")

EVENT_KEYS = {
    "schema", "runId", "nonce", "seq", "event", "phase", "pid", "startTicks", "ppid",
    "uid0", "uid1", "uid2", "uid3", "role", "status", "exitCode", "childCount", "echild",
    "setResult", "getResult", "getValue", "prefixDev", "prefixIno", "socketDev", "socketIno",
    "lockDev", "lockIno",
}
RESULT_KEYS = {
    "schema", "runId", "wsRunId", "nonce", "terminalStatus", "nativeStatus", "closureObserved",
    "qualification", "clientPass", "supervisorExit", "echild", "witnessProof", "error",
}
WITNESS_KEYS = {
    "schema", "runId", "nonce", "seq", "phase", "event", "pid", "startTicks", "ppid",
    "uid0", "uid1", "uid2", "uid3", "role", "status", "exitCode", "childCount", "echild",
    "setResult", "getResult", "getValue", "prctl",
}
PROCESS_INTENT_KEYS = {"schema", "kind", "runId", "wsRunId", "nonce", "purpose"}
WINE_FILES = (
    "wine-binding.json", "wine-native-stdin.jsonl", "wine-stdin.jsonl", "wine-native-stdout.jsonl", "wine-native-stderr.jsonl",
    "wine-witness-stdout.jsonl", "wine-witness-stderr.jsonl", "wine-publisher-stdout.jsonl",
    "wine-publisher-stderr.jsonl", "wine-process-create-intents.jsonl", "wine-result.json",
    "wine-result-quarantine.json", "wine-result-commit.json",
)
OUTPUT_LOGS = ("server.stdout", "server.stderr", "client.stdout", "client.stderr")
DISPOSITION_RUN_ID = "RUN-1791718684192-65783495695"
KNOWN_FAILURE_RUN_ID = "RUN-1791738410167-533692127338"
KNOWN_FAILURE_APK61_SHA256 = "99a256bd8f5de5854c609490b83799cb0da6c38d184330283745d0bca5cf006d"
CONTINUATION_CONTRACT_SHA256 = "0008db0586756da7b8e20a88725788ce64dc4c290f2d04d360068ff65a64da27"


class CollectorError(RuntimeError):
    pass


def require_serial() -> str:
    if not SERIAL:
        raise CollectorError("WOWFOREVER_ADB_SERIAL is not set (explicit device selection required)")
    return SERIAL


def candidate_authority() -> dict[str, Any]:
    """Identity fields the Android Store derives locally without APK self-hash."""
    return {
        "package": PACKAGE, "versionCode": VERSION_CODE, "versionName": VERSION_NAME,
        "abi": "arm64-v8a", "api": API_LEVEL, "uid": UID,
        "certificateSha256": CERTIFICATE_SHA256, "debuggable": True, "minSdk": 29, "targetSdk": 36,
    }


@dataclass(frozen=True)
class AdbResult:
    argv: tuple[str, ...]
    returncode: int
    stdout: bytes
    stderr: bytes


class AdbClient:
    """Fixed ADB facade.  ``raw`` is injectable for host tests only."""

    def __init__(self, raw: Any | None = None) -> None:
        if raw is None:
            require_serial()
            raw = _RealAdb()
        self.raw = raw
        self.deadline: float | None = None

    def run(self, args: Sequence[str], *, timeout: float = 30.0) -> Any:
        if self.deadline is not None:
            timeout = min(timeout, self.deadline - time.monotonic())
            if timeout <= 0:
                raise CollectorError("host deadline reached")
        result = self.raw.run(tuple(args), timeout=timeout)
        if len(getattr(result, "stdout", b"")) > TOTAL_CAP or len(getattr(result, "stderr", b"")) > TOTAL_CAP:
            raise CollectorError("ADB output exceeded bound")
        if self.deadline is not None and time.monotonic() > self.deadline:
            raise CollectorError("ADB result returned after host deadline")
        return result

    def shell(self, *args: str, timeout: float = 30.0) -> Any:
        return self.run(("shell", "-T", *args), timeout=timeout)

    def exec_out(self, *args: str, timeout: float = 30.0) -> Any:
        return self.run(("exec-out", *args), timeout=timeout)


class DeadlineAdbFacade:
    """The only ADB object exposed to legacy UI primitives."""

    def __init__(self, client: AdbClient) -> None:
        self.client = client
        self.deadline = client.deadline

    def run(self, args: Sequence[str], *, timeout: float = 30.0) -> Any:
        if self.deadline is not None:
            timeout = min(timeout, self.deadline - time.monotonic())
            if timeout <= 0:
                raise CollectorError("UI readiness deadline reached")
        result = self.client.run(args, timeout=timeout)
        if self.deadline is not None and time.monotonic() > self.deadline:
            raise CollectorError("UI readiness deadline reached")
        return result

    def shell(self, *args: str, timeout: float = 30.0) -> Any:
        return self.run(("shell", "-T", *args), timeout=timeout)

    def exec_out(self, *args: str, timeout: float = 30.0) -> Any:
        return self.run(("exec-out", *args), timeout=timeout)


class DeadlineKeyboard:
    def __init__(self, keyboard: Any, deadline: float) -> None:
        self.keyboard = keyboard
        self.deadline = deadline

    def key(self, modifier: int, keycode: int) -> None:
        if time.monotonic() >= self.deadline:
            raise CollectorError("UI/HID deadline reached")
        try:
            self.keyboard.key(modifier, keycode)
        except Exception as exc:
            raise CollectorError("UI/HID control failed") from exc
        if time.monotonic() >= self.deadline:
            raise CollectorError("UI/HID deadline reached")


class _RealAdb:
    def run(self, args: Sequence[str], *, timeout: float) -> AdbResult:
        import subprocess
        argv = (ADB, "-s", require_serial(), *map(str, args))
        try:
            result = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, check=False)
            return AdbResult(tuple(argv), result.returncode, result.stdout or b"", result.stderr or b"")
        except (OSError, subprocess.TimeoutExpired) as exc:
            return AdbResult(tuple(argv), 125, b"", str(exc).encode())


def _helpers() -> ModuleType:
    """Load only the old USB primitives; never call its collector or alter it."""
    name = "wowforever_wine_usb_primitives"
    loaded = sys.modules.get(name)
    if loaded:
        return loaded
    path = Path(__file__).parent.parent / "usb_forever_trial.py"
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise CollectorError("USB primitives unavailable")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def _unique_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("duplicate JSON key")
        value[key] = item
    return value


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


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def sha256_file(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def _regular(path: Path, maximum: int | None = None) -> None:
    info = path.lstat()
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        raise CollectorError("artifact is not a regular non-symlink file")
    if maximum is not None and info.st_size > maximum:
        raise CollectorError("artifact exceeds bound")
    for parent in (path, *path.parents):
        if parent.is_symlink():
            raise CollectorError("artifact path contains symlink")


def _safe_output(path: Path) -> Path:
    if not path.exists() or path.is_symlink() or not path.is_dir():
        raise CollectorError("output parent must be an existing real directory")
    resolved = path.resolve()
    repo = Path(__file__).resolve().parents[3]
    if resolved == repo or repo in resolved.parents:
        raise CollectorError("output must be outside repository")
    for parent in (resolved, *resolved.parents):
        if parent.is_symlink():
            raise CollectorError("output path contains symlink")
    return resolved


@contextmanager
def device_control_lock(parent: Path):
    """Serialize this host's preparation and HID control for the fixed device."""
    directory = _safe_output(parent)
    path = directory / f".wine-supervisor-{SERIAL}.lock"
    fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_nlink != 1 or stat.S_IMODE(info.st_mode) != 0o600:
            raise CollectorError("device control lock authority is invalid")
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise CollectorError("another Wine device operation is in progress") from exc
        yield
    finally:
        os.close(fd)


def _write_once(path: Path, raw: bytes, mode: int = 0o600) -> None:
    if path.exists() or path.is_symlink():
        raise CollectorError(f"evidence rewrite refused: {path.name}")
    path.parent.mkdir(mode=0o700, exist_ok=True)
    if path.parent.is_symlink():
        raise CollectorError("evidence parent is symlink")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        fd = -1
    finally:
        if fd >= 0:
            os.close(fd)
    os.chmod(path, mode)


def _write_json_once(path: Path, value: object) -> None:
    _write_once(path, (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode())


def _sync(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _text(result: Any) -> str:
    return bytes(getattr(result, "stdout", b"")).decode("utf-8", "strict").strip()


def read_apk_provenance(apk: Path) -> dict[str, Any]:
    """Read the independently pinned APK's bounded provenance through its FD."""
    _regular(apk, 2 * 1024 * 1024 * 1024)
    fields = ("st_dev", "st_ino", "st_uid", "st_gid", "st_mode", "st_nlink", "st_size", "st_mtime_ns", "st_ctime_ns")
    identity = lambda item: tuple(getattr(item, field) for field in fields)
    before = identity(os.stat(apk, follow_symlinks=False))
    try:
        with os.fdopen(os.open(apk, os.O_RDONLY | os.O_NOFOLLOW), "rb") as held:
            if identity(os.fstat(held.fileno())) != before:
                raise CollectorError("APK path/descriptor identity changed")
            with zipfile.ZipFile(held) as archive:
                name = "assets/forever/provenance.json"
                if archive.namelist().count(name) != 1 or archive.getinfo(name).file_size > TOTAL_CAP:
                    raise CollectorError("embedded APK provenance is missing, duplicated, or oversized")
                value = strict_json(archive.read(name), cap=TOTAL_CAP)
            if identity(os.fstat(held.fileno())) != before:
                raise CollectorError("APK descriptor changed while reading provenance")
    except (zipfile.BadZipFile, KeyError) as exc:
        raise CollectorError("embedded APK provenance is unavailable") from exc
    if identity(os.stat(apk, follow_symlinks=False)) != before:
        raise CollectorError("APK path changed while reading provenance")
    return value


def validate_apk(apk: Path, audit: Path, expected_sha: str | None) -> dict[str, Any]:
    """Authenticate the retained APK without a self-attested hash fallback."""
    if expected_sha is None or not isinstance(expected_sha, str) or not SHA_RE.fullmatch(expected_sha):
        raise CollectorError("independent APK hash is mandatory")
    _regular(apk, 2 * 1024 * 1024 * 1024)
    _regular(audit, STREAM_CAP)
    digest = sha256_file(apk)
    document = strict_json(audit.read_bytes(), cap=STREAM_CAP)
    if document.get("apk") is None or str(document["apk"]) != str(apk.resolve()):
        raise CollectorError("APK audit path is not exact")
    if (document.get("result"), document.get("package"), document.get("versionCode"),
            document.get("versionName"), document.get("certificateSha256"), document.get("sha256")) != (
                "PASS", PACKAGE, VERSION_CODE, VERSION_NAME, CERTIFICATE_SHA256, digest):
        raise CollectorError("APK audit tuple or independent hash mismatch")
    if digest != expected_sha:
        raise CollectorError("retained APK differs from independent expected hash")
    provenance = document.get("provenance")
    if not isinstance(provenance, dict):
        provenance = read_apk_provenance(apk)
        if sha256_file(apk) != digest:
            raise CollectorError("APK bytes changed while reading embedded provenance")
    # A real APK carries the authoritative full provenance.  Keep the small
    # audit-only fallback for host/unit fixtures, but never let an audit
    # document replace the embedded provenance of an actual ZIP APK.
    if zipfile.is_zipfile(apk):
        embedded = read_apk_provenance(apk)
        if sha256_file(apk) != digest:
            raise CollectorError("APK bytes changed while reading embedded provenance")
        provenance = embedded
    expected_profile = {
        "profile": "server_only", "launchKind": "wine_server_isolation", "serverIsolation": True,
        "protocolSchema": 2, "graphicsProbeEnabled": False, "memoryPatching": "DISABLED",
        "authentication": "DISABLED", "nativeOnly": False,
    }
    if any(provenance.get(key) != value for key, value in expected_profile.items()):
        raise CollectorError("APK provenance is not the actual server-only profile")
    native = provenance.get("wineSupervisor")
    if not isinstance(native, dict) or native.get("variant") != NATIVE_VARIANT or native.get("manifestSha256") != MANIFEST_SHA256:
        raise CollectorError("Wine supervisor provenance is not pinned")
    files = native.get("files")
    if not isinstance(files, dict) or set(files) != set(NATIVE_PINS):
        raise CollectorError("Wine supervisor native file provenance is not exact")
    for name, (size, pin) in NATIVE_PINS.items():
        item = files.get(name)
        if not isinstance(item, dict) or item.get("bytes", item.get("size")) != size or item.get("sha256") != pin:
            raise CollectorError(f"native pin mismatch: {name}")
    baseline = provenance.get("sourceBaseline")
    if not isinstance(baseline, dict) or baseline.get("manifestSHA256") != SOURCE_BASELINE_SHA256:
        raise CollectorError("code59 source baseline is not protected")
    resources = provenance.get("resources")
    if not isinstance(resources, dict) or set(resources) != set(PREFIX_RESOURCE_PINS):
        raise CollectorError("Wine resource provenance set is not exact")
    for packaged, (size, pin) in PREFIX_RESOURCE_PINS.items():
        item = resources.get(packaged)
        if not isinstance(item, dict) or item.get("fullSHA256") != pin or item.get("packagedPath") != (packaged if packaged.startswith("lib/") else "assets/" + packaged):
            raise CollectorError(f"Wine resource provenance mismatch: {packaged}")
        if size is not None and item.get("size") != size:
            raise CollectorError(f"Wine resource size mismatch: {packaged}")
    return {
        "path": str(apk.resolve()), "bytes": apk.stat().st_size, "sha256": digest,
        "auditPath": str(audit.resolve()), "auditSha256": sha256_file(audit),
        "certificateSha256": CERTIFICATE_SHA256, "manifestSha256": MANIFEST_SHA256,
        "nativePins": {name: {"bytes": size, "sha256": pin} for name, (size, pin) in NATIVE_PINS.items()},
        "provenance": provenance,
    }


def _validate_snapshot(snapshot: object) -> None:
    if not isinstance(snapshot, str) or not snapshot.startswith("UID PID PPID NAME\n"):
        raise CollectorError("own-UID process snapshot header is not canonical")
    rows = snapshot.splitlines()
    if len(rows) < 2 or any(
        len(row.split()) != 4 or row.split()[0] != str(UID) or
        any(not field.isdigit() for field in row.split()[1:3]) or not row.split()[3]
        for row in rows[1:]
    ):
        raise CollectorError("process snapshot contains foreign metadata")


def _nofollow_components(path: Path) -> None:
    """Require every component to be non-symlink before trusting a bundle."""
    current = Path(path.anchor)
    for component in path.parts[1:]:
        current /= component
        info = current.lstat()
        if stat.S_ISLNK(info.st_mode):
            raise CollectorError("preflight bundle contains a symlink component")
        if current != path and not stat.S_ISDIR(info.st_mode):
            raise CollectorError("preflight bundle component is not a directory")


def _stable_host_file(path: Path, maximum: int) -> tuple[int, str]:
    raw = _stable_host_bytes(path, maximum)
    return len(raw), sha256_bytes(raw)


def _stable_host_bytes(path: Path, maximum: int) -> bytes:
    _nofollow_components(path)
    _regular(path, maximum)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        before = os.fstat(fd)
        chunks: list[bytes] = []
        total = 0
        while True:
            block = os.read(fd, 1024 * 1024)
            if not block:
                break
            total += len(block)
            if total > maximum:
                raise CollectorError("preflight member exceeds bound")
            chunks.append(block)
        after = os.fstat(fd)
        identity = lambda item: (item.st_dev, item.st_ino, item.st_mode, item.st_nlink, item.st_size,
                                 item.st_mtime_ns, item.st_ctime_ns)
        if identity(before) != identity(after) or identity(before) != identity(path.lstat()):
            raise CollectorError("preflight member changed while hashing")
        return b"".join(chunks)
    finally:
        os.close(fd)


def _validate_preflight_source_provenance(value: dict[str, Any], expected_sha: str) -> None:
    required = {"schema", "helper", "collector", "protocol", "apk", "interruptedDispositionContractSha256"}
    if set(value) != required or value.get("schema") != 1:
        raise CollectorError("preflight source provenance shape is not exact")
    if value.get("interruptedDispositionContractSha256") != CONTINUATION_CONTRACT_SHA256:
        raise CollectorError("preflight continuation contract provenance is not exact")

    def local_pin(item: object, label: str, expected: Path) -> None:
        if not isinstance(item, dict) or set(item) != {"path", "bytes", "sha256"}:
            raise CollectorError(f"preflight {label} source pin shape is not exact")
        if item.get("path") != str(expected.resolve()) or type(item.get("bytes")) is not int or not SHA_RE.fullmatch(str(item.get("sha256"))):
            raise CollectorError(f"preflight {label} source pin is not exact")
        size, digest = _stable_host_file(expected, 2 * 1024 * 1024)
        if item.get("bytes") != size or item.get("sha256") != digest:
            raise CollectorError(f"preflight {label} source pin changed")

    local_pin(value["helper"], "helper", Path(__file__).with_name("prepare_wine_supervisor_apk.py"))
    local_pin(value["collector"], "collector", Path(__file__))
    protocol = value["protocol"]
    protocol_value = {"name": "wowforever.wine62-preflight/v1", "serial": SERIAL, "package": PACKAGE,
                      "install": ["install", "-r", "--no-streaming"], "reboot": ["reboot"]}
    protocol_sha = sha256_bytes((json.dumps(protocol_value, sort_keys=True, separators=(",", ":")) + "\n").encode())
    if not isinstance(protocol, dict) or set(protocol) != {"name", "sha256"} or protocol.get("name") != protocol_value["name"] or protocol.get("sha256") != protocol_sha:
        raise CollectorError("preflight protocol provenance is not exact")
    apk = value["apk"]
    if not isinstance(apk, dict) or set(apk) != {"path", "bytes", "sha256", "auditPath", "auditSha256", "certificateSha256", "manifestSha256", "nativePins", "provenance"}:
        raise CollectorError("preflight APK provenance shape is not exact")
    if (not isinstance(apk.get("path"), str) or not isinstance(apk.get("auditPath"), str) or
            apk.get("sha256") != expected_sha or apk.get("certificateSha256") != CERTIFICATE_SHA256 or
            type(apk.get("bytes")) is not int or not SHA_RE.fullmatch(str(apk.get("auditSha256"))) or
            apk.get("manifestSha256") != MANIFEST_SHA256):
        raise CollectorError("preflight APK provenance identity is not exact")
    native = apk.get("nativePins")
    if not isinstance(native, dict) or set(native) != set(NATIVE_PINS):
        raise CollectorError("preflight native provenance set is not exact")
    for name, (size, digest) in NATIVE_PINS.items():
        item = native.get(name)
        if not isinstance(item, dict) or set(item) != {"bytes", "sha256"} or item.get("bytes") != size or item.get("sha256") != digest:
            raise CollectorError(f"preflight native provenance mismatch: {name}")
    try:
        authenticated = validate_apk(Path(apk["path"]), Path(apk["auditPath"]), expected_sha)
    except (CollectorError, OSError, ValueError, KeyError, TypeError) as exc:
        raise CollectorError("preflight APK provenance is not authenticated") from exc
    if any(apk.get(key) != authenticated.get(key) for key in
           ("path", "bytes", "sha256", "auditPath", "auditSha256", "certificateSha256", "manifestSha256", "nativePins")):
        raise CollectorError("preflight APK authenticated metadata is not exact")
    provenance = apk.get("provenance")
    authenticated_provenance = authenticated.get("provenance")
    if not isinstance(provenance, dict) or not isinstance(authenticated_provenance, dict) or provenance != authenticated_provenance:
        raise CollectorError("preflight APK provenance is not the authenticated full provenance")
    expected_profile = {"profile": "server_only", "launchKind": "wine_server_isolation", "serverIsolation": True,
                        "protocolSchema": 2, "graphicsProbeEnabled": False, "memoryPatching": "DISABLED",
                        "authentication": "DISABLED", "nativeOnly": False}
    if (not isinstance(provenance, dict) or
            any(provenance.get(key) != expected_value for key, expected_value in expected_profile.items())):
        raise CollectorError("preflight APK profile provenance is not exact")
    supervisor = provenance.get("wineSupervisor")
    if not isinstance(supervisor, dict) or supervisor.get("variant") != NATIVE_VARIANT or supervisor.get("manifestSha256") != MANIFEST_SHA256:
        raise CollectorError("preflight Wine supervisor provenance is not exact")
    supervisor_files = supervisor.get("files")
    if not isinstance(supervisor_files, dict) or set(supervisor_files) != set(NATIVE_PINS):
        raise CollectorError("preflight Wine supervisor file provenance is not exact")
    for name, (size, digest) in NATIVE_PINS.items():
        item = supervisor_files[name]
        if not isinstance(item, dict) or item.get("bytes", item.get("size")) != size or item.get("sha256") != digest:
            raise CollectorError(f"preflight Wine supervisor pin mismatch: {name}")
    baseline = provenance.get("sourceBaseline")
    if not isinstance(baseline, dict) or baseline.get("manifestSHA256") != SOURCE_BASELINE_SHA256:
        raise CollectorError("preflight source baseline provenance is not exact")
    resources = provenance.get("resources")
    if not isinstance(resources, dict) or set(resources) != set(PREFIX_RESOURCE_PINS):
        raise CollectorError("preflight Wine resource provenance is not exact")
    for packaged, (size, digest) in PREFIX_RESOURCE_PINS.items():
        item = resources[packaged]
        if (not isinstance(item, dict) or
                item.get("fullSHA256") != digest or item.get("size") != size or
                item.get("packagedPath") != (packaged if packaged.startswith("lib/") else "assets/" + packaged)):
            raise CollectorError(f"preflight Wine resource pin mismatch: {packaged}")


def _validate_preflight_bundle(path: Path, ready: Path, expected_sha: str) -> dict[str, Any]:
    _nofollow_components(path)
    entries = list(path.iterdir())
    expected_names = {"seal.json", "source-provenance.json", "operations.jsonl", ready.name}
    if {entry.name for entry in entries} != expected_names:
        raise CollectorError("preflight bundle member set is not exact")
    seal = path / "seal.json"
    seal_bytes = _stable_host_bytes(seal, STREAM_CAP)
    seal_value = strict_json(seal_bytes, cap=STREAM_CAP)
    if set(seal_value) != {"schema", "files"} or seal_value.get("schema") != 1 or not isinstance(seal_value.get("files"), dict):
        raise CollectorError("preflight seal shape is not exact")
    files = seal_value["files"]
    if set(files) != expected_names - {"seal.json"}:
        raise CollectorError("preflight seal member map is not exact")
    member_bytes: dict[str, bytes] = {}
    for name in sorted(files):
        item = files[name]
        if not isinstance(item, dict) or set(item) != {"bytes", "sha256"} or type(item.get("bytes")) is not int or not SHA_RE.fullmatch(str(item.get("sha256"))):
            raise CollectorError("preflight seal member metadata is not exact")
        raw = _stable_host_bytes(path / name, STREAM_CAP)
        member_bytes[name] = raw
        size, digest = len(raw), sha256_bytes(raw)
        if item["bytes"] != size or item["sha256"] != digest:
            raise CollectorError(f"preflight seal member mismatch: {name}")
    provenance_raw = member_bytes["source-provenance.json"]
    _validate_preflight_source_provenance(strict_json(provenance_raw, cap=STREAM_CAP), expected_sha)
    ready_raw = member_bytes[ready.name]
    if _stable_host_bytes(seal, STREAM_CAP) != seal_bytes:
        raise CollectorError("preflight seal changed while validating members")
    return strict_json(ready_raw, cap=STREAM_CAP)


def validate_preflight(path: Path, expected_sha: str) -> dict[str, Any]:
    path = _safe_output(path)
    candidates = sorted(path.glob("state-*-READY_FOR_WINE62_SERVER_ISOLATION.json"))
    if len(candidates) != 1:
        raise CollectorError("preflight must contain exactly one READY Wine62 record")
    candidate = candidates[0]
    _regular(candidate, STREAM_CAP)
    record = _validate_preflight_bundle(path, candidate, expected_sha)
    if (record.get("result"), record.get("serial"), record.get("package"), record.get("versionCode"),
            record.get("versionName"), record.get("approvedApkSha256"), record.get("installedApkSha256")) != (
                "READY_FOR_WINE62_SERVER_ISOLATION", SERIAL, PACKAGE, VERSION_CODE, VERSION_NAME,
                expected_sha, expected_sha):
        raise CollectorError("Wine62 preflight identity or APK pin mismatch")
    if record.get("bootComplete") is not True:
        raise CollectorError("preflight boot is not complete")
    before, after = record.get("bootBeforeReboot"), record.get("bootAfterReboot")
    try:
        continuation, _ = load_continuation_contract()
        verify_continuation_external_evidence(continuation)
        known_failure_boot = continuation["knownFailure"]["bootId"]
    except (ContractError, OSError, ValueError) as exc:
        raise CollectorError("continuation contract is not authenticated") from exc
    if (not isinstance(before, str) or not BOOT_RE.fullmatch(before) or not isinstance(after, str) or
            not BOOT_RE.fullmatch(after) or after in {before, REBOOT_SENTINEL_BOOT, known_failure_boot}):
        raise CollectorError("preflight does not bind a new boot")
    uuids = record.get("stableBootIds")
    if not isinstance(uuids, list) or len(uuids) != 2 or uuids[0] != uuids[1] or uuids[0] != after:
        raise CollectorError("preflight lacks two stable boot UUID reads")
    _validate_snapshot(record.get("preRebootOwnUidProcessSnapshot"))
    _validate_snapshot(record.get("ownUidProcessSnapshot"))
    history = record.get("historyPins")
    if not isinstance(history, dict) or history.get("oldV2CurrentSha256") != OLD_V2_CURRENT_SHA256 or history.get("oldV1CurrentSha256") != OLD_V1_CURRENT_SHA256:
        raise CollectorError("preflight historical pins are not exact")
    archive_pins = history.get("oldV2ArchiveAndReceiptSha256")
    if not isinstance(archive_pins, dict) or archive_pins != {
        f"{OLD_V2_PERSISTENT_ROOT}/{name}": digest for name, digest in OLD_V2_HISTORY_SHA256.items()
    }:
        raise CollectorError("preflight old fixture archive/receipt pins are not exact")
    return {"path": str(candidate), "sha256": sha256_file(candidate), "record": record}


def _private_path(path: str, *, run_id: str | None = None, ws_run_id: str | None = None) -> bool:
    exact_history = {
        GENERIC_CURRENT, OLD_V1_CURRENT, OLD_V2_CURRENT,
        *(f"{OLD_V2_PERSISTENT_ROOT}/{name}" for name in OLD_V2_PERSISTENT_FILES),
    }
    if path in exact_history:
        return True
    if run_id is not None and RUN_RE.fullmatch(run_id):
        root = f"{RUN_ROOT}/{run_id}"
        exact_run = {
            f"{RUN_ROOT}/{run_id}.json",
            f"{root}/wine-binding.json",
            f"{RUN_ROOT}/{run_id}-wine-result-admission.json",
            *(f"{root}/{name}" for name in WINE_FILES),
        }
        if path in exact_run:
            return True
    disposition_paths = {
        f"{RUN_ROOT}/{DISPOSITION_RUN_ID}-current-before-abandonment.json",
        f"{RUN_ROOT}/{DISPOSITION_RUN_ID}-abandonment-reservation.json",
        f"{RUN_ROOT}/{DISPOSITION_RUN_ID}-abandonment-consumption.json",
        f"{RUN_ROOT}/{DISPOSITION_RUN_ID}/abandoned-on-new-boot.json",
    }
    continuation_paths = {
        f"{RUN_ROOT}/{KNOWN_FAILURE_RUN_ID}-current-before-prelaunch-abort.json",
        f"{RUN_ROOT}/{KNOWN_FAILURE_RUN_ID}-prelaunch-abort-reservation.json",
        f"{RUN_ROOT}/{KNOWN_FAILURE_RUN_ID}-prelaunch-abort-consumption.json",
        f"{RUN_ROOT}/{KNOWN_FAILURE_RUN_ID}/prelaunch-abort.json",
    }
    if path in disposition_paths or path in continuation_paths:
        return True
    try:
        contract, _ = load_contract()
        continuation, _ = load_continuation_contract()
        fixed = {item.get("path") for item in contract.get("fixedPaths", [])}
        fixed.update(item.get("path") for item in continuation.get("fixedPaths", []))
        if path in fixed:
            return True
    except (ContractError, OSError, ValueError):
        pass
    if ws_run_id is not None and WS_RE.fullmatch(ws_run_id):
        controls = f"files/forever/wine-controls/{ws_run_id}"
        exact_ws = {
            f"{controls}/prepared.receipt",
            *(f"{controls}/prefix/.wine/.supervisor-output/{name}" for name in OUTPUT_LOGS),
        }
        if path in exact_ws:
            return True
    return False


def _check_private_no_follow(adb: AdbClient, path: str) -> None:
    """Reject a symlink at every fixed component before using ``head``."""
    if not path.startswith("files/") or any(not part for part in path.split("/")):
        raise CollectorError("private path is not a fixed relative path")
    for index in range(1, len(path.split("/")) + 1):
        prefix = "/".join(path.split("/")[:index])
        result = adb.shell("run-as", PACKAGE, "readlink", prefix, timeout=8)
        stdout, stderr = bytes(result.stdout), bytes(result.stderr)
        if stdout:
            raise CollectorError("private authority contains a symlink")
        if result.returncode != 0 and stderr and not any(marker in stderr.lower() for marker in (b"invalid argument", b"no such file or directory")):
            raise CollectorError("private authority component is unreadable")


def _device_private(adb: AdbClient, path: str, cap: int, *, run_id: str | None = None,
                    ws_run_id: str | None = None) -> tuple[bytes | None, str]:
    if not _private_path(path, run_id=run_id, ws_run_id=ws_run_id):
        raise CollectorError("device path is outside fixed Wine authority")
    _check_private_no_follow(adb, path)
    result = adb.shell("run-as", PACKAGE, "head", "-c", str(cap + 1), path, timeout=15)
    out, err = bytes(result.stdout), bytes(result.stderr)
    if result.returncode != 0:
        if not out and b"No such file" in err:
            return None, "missing"
        return None, "unreadable"
    if err or len(out) > cap:
        return None, "oversized-or-unreadable"
    return out, "ok"


def _device_fixed_stat(adb: AdbClient, path: str) -> str:
    if not _private_path(path):
        raise CollectorError("fixed status path is outside authority")
    _check_private_no_follow(adb, path)
    result = adb.shell("run-as", PACKAGE, "stat", "-c", "%F", path, timeout=15)
    out, err = bytes(result.stdout).strip(), bytes(result.stderr)
    if result.returncode != 0:
        if not out and b"No such file" in err:
            return "missing"
        raise CollectorError("fixed status path is unreadable")
    if err:
        raise CollectorError("fixed status path emitted stderr")
    if out == b"directory":
        return "present-directory"
    if out == b"regular file":
        return "present-file"
    raise CollectorError("fixed status path has an unexpected type")


PROTECTED_COMPILER_PATHS = frozenset({
    "files/forever/controls/compiler-prefix/RUN-1791497262380-683075998117/backup-d3dcompiler_47.dll-7239e3f8-b5fc-42d3-9650-15481cd22164.bak",
    "files/imagefs_shared/home/xuser-CUSTOM_GAME_1368588668/.wine/drive_c/windows/system32/d3dcompiler_47.dll",
})
PROTECTED_COMPILER_PINS = {
    "files/forever/controls/compiler-prefix/RUN-1791497262380-683075998117/backup-d3dcompiler_47.dll-7239e3f8-b5fc-42d3-9650-15481cd22164.bak":
        (4_453_888, "06dc23aaa86607e083ed1725f2e99cc40501caf93408569eb9f287b272b58e52"),
    "files/imagefs_shared/home/xuser-CUSTOM_GAME_1368588668/.wine/drive_c/windows/system32/d3dcompiler_47.dll":
        (1_114_112, "c374b107fd14890e4b602921c82198bd7c3773f7f1d27f0712be92cb52ed3add"),
}
PROTECTED_STAT_FORMAT = "%d:%i:%u:%g:%a:%h:%s:%Y:%Z:%F"


def _protected_contract_entries(entries: object) -> dict[str, dict[str, Any]]:
    expected_count = 58 if isinstance(entries, list) and len(entries) >= 50 else 33
    if not isinstance(entries, list) or len(entries) != expected_count or any(not isinstance(item, dict) for item in entries):
        raise CollectorError(f"fixed authority is not exactly the captured {expected_count} paths")
    by_path: dict[str, dict[str, Any]] = {}
    for item in entries:
        path = item.get("path")
        if not isinstance(path, str) or path in by_path:
            raise CollectorError("protected compiler authority paths are not exact")
        by_path[path] = item
    protected = {path: item for path, item in by_path.items() if item.get("kind") == "protected-compiler"}
    if set(protected) != PROTECTED_COMPILER_PATHS:
        raise CollectorError("protected compiler authority paths are not exact")
    for path, item in protected.items():
        size, digest = PROTECTED_COMPILER_PINS[path]
        if (item.get("status"), item.get("capturedStatus"), item.get("binaryCopied"),
                item.get("bytes"), item.get("expectedBytes"), item.get("sha256"),
                item.get("expectedSha256")) != (
                    "PRESENT", "ok", False, size, size, digest, digest):
            raise CollectorError(f"protected compiler provenance is not exact: {path}")
    return protected


def _protected_stat(adb: AdbClient, path: str) -> tuple[int, ...]:
    _check_private_no_follow(adb, path)
    command = f"test ! -L {shlex.quote(path)} && stat -c {shlex.quote(PROTECTED_STAT_FORMAT)} {shlex.quote(path)}"
    result = adb.shell("run-as", PACKAGE, "sh", "-c", shlex.quote(command), timeout=20)
    out, err = bytes(result.stdout), bytes(result.stderr)
    if result.returncode != 0 or err:
        raise CollectorError(f"protected compiler stat failed: {path}")
    try:
        fields = out.decode("utf-8", "strict").strip().split(":")
        if len(fields) != 10 or fields[-1] != "regular file":
            raise ValueError
        values = tuple(int(value) for value in fields[:-1])
    except (UnicodeDecodeError, ValueError):
        raise CollectorError(f"protected compiler stat metadata is invalid: {path}") from None
    if len(values) != 9:
        raise CollectorError(f"protected compiler stat metadata is incomplete: {path}")
    return values


def _protected_hash(adb: AdbClient, path: str, expected: str) -> None:
    _check_private_no_follow(adb, path)
    result = adb.shell("run-as", PACKAGE, "sha256sum", path, timeout=30)
    out, err = bytes(result.stdout), bytes(result.stderr)
    if result.returncode != 0 or err:
        raise CollectorError(f"protected compiler hash failed: {path}")
    try:
        fields = out.decode("utf-8", "strict").strip().split()
    except UnicodeDecodeError:
        raise CollectorError(f"protected compiler hash fields are invalid: {path}") from None
    if fields != [expected, path]:
        raise CollectorError(f"protected compiler hash fields are invalid: {path}")


def closed_fixed_protected_metadata(adb: AdbClient, entries: object) -> dict[str, str]:
    """Authenticate the two frozen compiler pins without returning binary data."""
    if isinstance(entries, dict):
        entries = entries.get("fixedPaths")
    protected = _protected_contract_entries(entries)
    observed: dict[str, str] = {}
    for path, item in protected.items():
        size, expected_sha = PROTECTED_COMPILER_PINS[path]
        before = _protected_stat(adb, path)
        # dev, ino, uid, gid, mode, nlink, size, mtime, ctime are all retained
        # for the race check; only the explicitly frozen fields are value-pinned.
        if (before[2], before[4], before[5], before[6]) != (UID, 771, 1, size):
            raise CollectorError(f"protected compiler metadata pin mismatch: {path}")
        _protected_hash(adb, path, expected_sha)
        after = _protected_stat(adb, path)
        if after != before:
            raise CollectorError(f"protected compiler metadata changed during hash: {path}")
        observed[path] = item["sha256"]
    return observed


def _validate_fixed_authority(adb: AdbClient, contract: dict[str, Any], *, skip_current: bool = False) -> dict[str, str]:
    entries = contract.get("fixedPaths")
    expected_count = 58 if isinstance(entries, list) and len(entries) >= 50 else 33
    if not isinstance(entries, list) or len(entries) != expected_count:
        raise CollectorError(f"fixed authority is not exactly the captured {expected_count} paths")
    observed: dict[str, str] = {}
    protected = closed_fixed_protected_metadata(adb, entries)
    for item in entries:
        path = item.get("path")
        if not isinstance(path, str):
            raise CollectorError("fixed authority path is malformed")
        if skip_current and path == GENERIC_CURRENT:
            continue
        expected = item.get("status")
        if expected == "MISSING" and item.get("kind") == "scoped-stat":
            status = _device_fixed_stat(adb, path)
            if status != "missing":
                raise CollectorError(f"fixed scoped path is not missing: {path}")
            observed[path] = "MISSING"
            continue
        if item.get("kind") == "protected-compiler":
            observed[path] = protected[path]
            continue
        cap = TOTAL_CAP
        raw, status = _device_private(adb, path, cap)
        if expected == "MISSING":
            if raw is not None or status != "missing":
                raise CollectorError(f"fixed path is not missing: {path}")
            observed[path] = "MISSING"
            continue
        if expected != "PRESENT" or status != "ok" or raw is None:
            raise CollectorError(f"fixed path is not present: {path}")
        if len(raw) != item.get("bytes") or sha256_bytes(raw) != item.get("sha256"):
            raise CollectorError(f"fixed path bytes/hash mismatch: {path}")
        observed[path] = item["sha256"]
    return observed


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
        raise CollectorError("installed APK path is not an exact base APK")
    dump = _text(adb.shell("dumpsys", "package", PACKAGE, timeout=15))
    if not re.search(rf"\bversionCode={VERSION_CODE}\b", dump) or not re.search(rf"\bversionName={re.escape(VERSION_NAME)}\b", dump):
        raise CollectorError("installed version tuple mismatch")
    if not re.search(r"\btargetSdk(?:Version)?[=:]36\b", dump):
        raise CollectorError("installed target SDK mismatch")
    if not ("debuggable=true" in dump or "DEBUGGABLE" in dump):
        raise CollectorError("installed package is not debuggable")
    if _text(adb.exec_out("run-as", PACKAGE, "id", "-u", timeout=10)) != str(UID):
        raise CollectorError("run-as UID mismatch")
    boot = _text(adb.exec_out("run-as", PACKAGE, "cat", "/proc/sys/kernel/random/boot_id", timeout=10))
    if boot != preflight["record"].get("bootAfterReboot") or not BOOT_RE.fullmatch(boot):
        raise CollectorError("device boot does not match preflight")
    installed = _text(adb.exec_out("run-as", PACKAGE, "sha256sum", match.group(1), timeout=30)).split()
    if installed != [expected_sha, match.group(1)]:
        raise CollectorError("installed APK hash mismatch")
    return {**props, "serial": SERIAL, "package": PACKAGE, "uid": UID, "versionCode": VERSION_CODE,
            "versionName": VERSION_NAME, "abi": "arm64-v8a", "certificateSha256": CERTIFICATE_SHA256,
            "apkPath": match.group(1), "apkSha256": installed[0], "bootId": boot,
            "candidateAuthority": candidate_authority()}


def _load_ui_helpers() -> ModuleType:
    return _helpers()


def _load_native_control_helpers() -> ModuleType:
    """Load only the native collector's non-mutating wake/state primitives."""
    name = "wowforever_wine_native_control_helpers"
    loaded = sys.modules.get(name)
    if loaded is not None:
        return loaded
    path = Path(__file__).with_name("run_native_supervisor_apk.py")
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise CollectorError("native control primitives unavailable")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def _nodes(state: Any, text: str | None = None, suffix: str | None = None) -> list[Any]:
    return [node for node in state.root.iter("node") if
            (text is None or node.attrib.get("text") == text) and
            (suffix is None or node.attrib.get("class", "").endswith(suffix))]


def _own_ui(state: Any) -> list[Any]:
    if not state.top_is_own() or not state.hierarchy_is_own():
        raise CollectorError("own UI hierarchy/focus guard failed")
    focused = [node for node in state.root.iter("node") if node.attrib.get("focused") == "true"]
    if len(focused) != 1 or focused[0].attrib.get("package") != PACKAGE:
        raise CollectorError("own UI does not have one own focused control")
    return focused


def _button(state: Any, labels: tuple[str, str]) -> Any | None:
    matches = [node for node in state.root.iter("node") if node.attrib.get("text") in labels and node.attrib.get("class", "").endswith("Button")]
    return matches[0] if len(matches) == 1 else None


def _guard_controls(state: Any, *, idle: bool = False) -> Any | None:
    if not state.top_is_own() or not state.hierarchy_is_own():
        raise CollectorError("foreign UI hierarchy")
    title = _nodes(state, "Forever Wine server isolation", "TextView")
    checkbox = _nodes(state, "I consent to start the isolated Wine control", "CheckBox")
    start = _button(state, ("Start control", "START CONTROL"))
    cancel = _button(state, ("Cancel control", "CANCEL CONTROL"))
    if not (len(title) == len(checkbox) == 1 and start is not None and cancel is not None):
        raise CollectorError("Wine supervisor controls are not exact")
    if any(node.attrib.get("package") != PACKAGE for node in (title[0], checkbox[0], start, cancel)):
        raise CollectorError("Wine UI contains foreign controls")
    if idle and (checkbox[0].attrib.get("enabled") != "true" or checkbox[0].attrib.get("checked") != "false" or
                 start.attrib.get("enabled") != "false" or cancel.attrib.get("enabled") != "false"):
        raise CollectorError("Wine controls are not initial idle state")
    return checkbox[0]


def _policy_nonsecure(adb: AdbClient, helper: ModuleType, deadline: float) -> None:
    if time.monotonic() >= deadline:
        raise CollectorError("keyguard policy poll deadline reached")
    if not helper.policy_is_nonsecure(adb, deadline=deadline):
        raise CollectorError("keyguard policy was not explicitly nonsecure")
    if time.monotonic() >= deadline:
        raise CollectorError("keyguard policy poll deadline reached")


READINESS_REASONS = frozenset({
    "not-ready", "global-deadline", "final-reserve", "local-deadline",
    "ui-dump-cleanup-failed", "ui-dump-late", "ui-dump-failed", "foreign-top",
    "own-top-ready", "unknown",
})


def _fixed_readiness_reason(readiness: Any) -> str:
    reason = getattr(readiness, "reason", None)
    return reason if isinstance(reason, str) and reason in READINESS_REASONS else "unknown"


def _control_deadline(adb: AdbClient) -> float:
    """Bound the complete wake/activity/consent sequence, reserving cleanup."""
    now = time.monotonic()
    deadline = now + CONTROL_SECONDS
    if adb.deadline is not None:
        deadline = min(deadline, adb.deadline - CLEANUP_SECONDS)
    if deadline <= now:
        raise CollectorError("host deadline reached")
    return deadline


def _require_before(deadline: float) -> None:
    if time.monotonic() >= deadline:
        raise CollectorError("UI/HID deadline reached")


def _control_activity_impl(adb: AdbClient, journal: list[dict[str, Any]]) -> None:
    helper = _load_ui_helpers()
    native = _load_native_control_helpers()
    deadline = _control_deadline(adb)
    cleanup_deadline = min(deadline + UI_CLEANUP_SECONDS, adb.deadline or deadline + UI_CLEANUP_SECONDS)
    facade = DeadlineAdbFacade(adb)
    facade.deadline = deadline
    _policy_nonsecure(facade, helper, deadline)
    with helper.UsbKeyboard(SERIAL) as opened:
        keyboard = DeadlineKeyboard(opened, deadline)
        _require_before(deadline)
        keyboard.key(2, 0)  # one wake packet; no unlock input
        journal.append({"action": "wake", "status": "sent"})
        try:
            native._poll_wakefulness_awake(facade, None)
        except Exception as exc:
            journal.append({"action": "awake", "status": "failed"})
            raise CollectorError("UI/HID control failed") from exc
        journal.append({"action": "awake", "status": "ready"})
        remaining = min(UI_CLEANUP_SECONDS, max(0.0, deadline - time.monotonic()))
        if remaining < 0.15:
            raise CollectorError("wakefulness poll deadline reached")
        time.sleep(0.15)
        _require_before(deadline)
        _policy_nonsecure(facade, helper, deadline)
        _require_before(deadline)
        dismissed = facade.shell("wm", "dismiss-keyguard", timeout=10)
        if dismissed.returncode != 0 or dismissed.stderr:
            raise CollectorError("nonsecure public keyguard dismissal failed")
        journal.append({"action": "dismiss", "status": "sent"})
        _policy_nonsecure(facade, helper, deadline)
        try:
            native._poll_keyguard_dismissed(facade, None)
        except Exception as exc:
            journal.append({"action": "keyguard", "status": "failed"})
            raise CollectorError("UI/HID control failed") from exc
        journal.append({"action": "keyguard", "status": "dismissed"})
        _require_before(deadline)
        journal.append({"action": "initialActivity", "status": "intent"})
        started = facade.shell("am", "start", "-n", ALIAS, timeout=15)
        if started.returncode != 0 or started.stderr:
            raise CollectorError("Wine supervisor Activity did not start")
        # The intent is recorded before transport. There is no retry path, no
        # extra Enter, and no scenario supplied by the caller.
        readiness = helper.wait_for_own_ui(facade)
        readiness_reason = _fixed_readiness_reason(readiness)
        readiness_count = getattr(readiness, "dump_count", 0)
        if type(readiness_count) is not int or readiness_count < 0 or readiness_count > 3:
            readiness_count = 0
        journal.append({
            "action": "readiness", "status": "ready" if readiness.state is not None else "not-ready",
            "reason": readiness_reason, "count": readiness_count,
        })
        if readiness.state is None:
            raise CollectorError("Wine supervisor UI readiness failed")
        ui_facade = DeadlineAdbFacade(adb)
        ui_facade.deadline = cleanup_deadline
        state = helper.dump_ui(ui_facade, deadline=deadline, cleanup_deadline=cleanup_deadline)
        checkbox = _guard_controls(state, idle=True)
        focused = [node for node in state.root.iter("node") if node.attrib.get("focused") == "true"]
        for position in range(9):
            checkbox = _nodes(state, "I consent to start the isolated Wine control", "CheckBox")[0]
            if focused and focused[0] is checkbox:
                break
            if position == 8:
                raise CollectorError("consent checkbox was not focused within eight tabs")
            if focused:
                _own_ui(state)
            _require_before(deadline)
            keyboard.key(0, 43)
            journal.append({"action": "tab", "position": position + 1})
            state = helper.dump_ui(ui_facade, deadline=deadline, cleanup_deadline=cleanup_deadline)
            _guard_controls(state, idle=True)
            _own_ui(state)
            focused = [node for node in state.root.iter("node") if node.attrib.get("focused") == "true"]
        state = helper.dump_ui(ui_facade, deadline=deadline, cleanup_deadline=cleanup_deadline)
        _guard_controls(state, idle=True)
        focused = _own_ui(state)[0]
        checkbox = _nodes(state, "I consent to start the isolated Wine control", "CheckBox")[0]
        if focused is not checkbox or checkbox.attrib.get("checked") != "false":
            raise CollectorError("consent checkbox was not initially unchecked/focused")
        _require_before(deadline)
        keyboard.key(0, 44)
        journal.append({"action": "space", "position": "checkbox"})
        state = helper.dump_ui(ui_facade, deadline=deadline, cleanup_deadline=cleanup_deadline)
        _guard_controls(state)
        focused = _own_ui(state)[0]
        checkbox = _nodes(state, "I consent to start the isolated Wine control", "CheckBox")[0]
        if focused is not checkbox or checkbox.attrib.get("checked") != "true":
            raise CollectorError("consent checkbox did not become checked")
        _require_before(deadline)
        keyboard.key(0, 43)
        journal.append({"action": "tab", "position": "start"})
        state = helper.dump_ui(ui_facade, deadline=deadline, cleanup_deadline=cleanup_deadline)
        _guard_controls(state)
        start = _button(state, ("Start control", "START CONTROL"))
        checkbox = _nodes(state, "I consent to start the isolated Wine control", "CheckBox")
        if (start is None or start.attrib.get("enabled") != "true" or len(checkbox) != 1 or
                checkbox[0].attrib.get("checked") != "true" or _own_ui(state)[0] is not start):
            raise CollectorError("Start control is not focused")
        _require_before(deadline)
        journal.append({"action": "enter", "position": "start", "status": "intent"})
        keyboard.key(0, 40)
        journal.append({"action": "enter", "position": "start", "status": "sent"})


def _control_activity(adb: AdbClient, journal: list[dict[str, Any]]) -> None:
    try:
        _control_activity_impl(adb, journal)
    except CollectorError:
        raise
    except Exception as exc:
        # Existing USB/UI primitives use their own exception classes. Keep
        # those implementation details out of durable evidence.
        raise CollectorError("UI/HID control failed") from exc


def _event(value: dict[str, Any], run_id: str, nonce: str) -> dict[str, Any]:
    if set(value) != EVENT_KEYS:
        raise CollectorError("schema2 event fields are not exact")
    ints = {
        "schema", "seq", "pid", "startTicks", "ppid", "uid0", "uid1", "uid2", "uid3",
        "exitCode", "childCount", "setResult", "getResult", "getValue", "prefixDev", "prefixIno",
        "socketDev", "socketIno", "lockDev", "lockIno",
    }
    if any(type(value[key]) is not int for key in ints) or type(value["echild"]) is not bool:
        raise CollectorError("schema2 event types are invalid")
    if value["schema"] != 2 or value["runId"] != run_id or value["nonce"] != nonce:
        raise CollectorError("schema2 event identity mismatch")
    if value["seq"] <= 0 or value["pid"] <= 0 or value["startTicks"] <= 0 or value["ppid"] <= 0 or value["childCount"] < 0:
        raise CollectorError("schema2 event identity is invalid")
    if any(value[f"uid{i}"] != UID for i in range(4)) or value["prefixDev"] <= 0 or value["prefixIno"] <= 0:
        raise CollectorError("schema2 event ownership/prefix facts are invalid")
    if value["event"] not in {"READY", "SPAWN", "OWNED_ADOPT", "EXIT", "SERVER_READY", "PHASE_DONE", "RUN_DONE", "ERROR"}:
        raise CollectorError("unknown schema2 event")
    if value["phase"] not in {"none", "server", "client"}:
        raise CollectorError("schema2 phase is invalid")
    if value["event"] != "EXIT" and value["exitCode"] != -1:
        raise CollectorError("non-EXIT event contains exit code")
    if value["event"] == "READY":
        if (value["role"], value["phase"], value["status"], value["setResult"], value["getResult"], value["getValue"]) != ("supervisor", "none", "READY", 0, 0, 1):
            raise CollectorError("READY prctl facts are invalid")
    elif (value["setResult"], value["getResult"], value["getValue"]) != (-1, -1, -1):
        raise CollectorError("non-READY prctl facts are not -1")
    if value["event"] == "SERVER_READY":
        if (value["role"], value["phase"], value["status"]) != ("server", "server", "RUNNING") or min(value["socketDev"], value["socketIno"], value["lockDev"], value["lockIno"]) <= 0:
            raise CollectorError("SERVER_READY socket/lock facts are invalid")
    elif any(value[key] != -1 for key in ("socketDev", "socketIno", "lockDev", "lockIno")):
        raise CollectorError("foreign socket/lock facts")
    return value


def parse_events(raw: bytes, run_id: str, nonce: str) -> list[dict[str, Any]]:
    if not raw or len(raw) > TOTAL_CAP or not raw.endswith(b"\n"):
        raise CollectorError("native stdout transcript is missing or oversized")
    events: list[dict[str, Any]] = []
    for line in raw.splitlines(keepends=True):
        if len(line) > LINE_CAP or b"\r" in line:
            raise CollectorError("native IPC line bound exceeded")
        events.append(_event(strict_json(line, cap=LINE_CAP), run_id, nonce))
    if not events or [item["seq"] for item in events] != list(range(1, len(events) + 1)):
        raise CollectorError("native event sequence gap or duplicate")
    return events


def _validate_process_intents(raw: bytes, run_id: str, ws: str, nonce: str) -> None:
    if not raw or len(raw) > TOTAL_CAP or not raw.endswith(b"\n"):
        raise CollectorError("process-create intent journal is missing or oversized")
    purposes: list[str] = []
    for line in raw.splitlines(keepends=True):
        if len(line) > LINE_CAP:
            raise CollectorError("process-create intent line exceeded bound")
        value = strict_json(line, cap=LINE_CAP)
        if set(value) != PROCESS_INTENT_KEYS or value.get("schema") != 1 or value.get("kind") != "process_create_intent" or value.get("runId") != run_id or value.get("wsRunId") != ws or value.get("nonce") != nonce:
            raise CollectorError("process-create intent is not the closed contract")
        if value.get("purpose") not in {"prefix-publisher", "supervisor", "witness"}:
            raise CollectorError("unknown process-create purpose")
        purposes.append(value["purpose"])
    if purposes != ["prefix-publisher", "supervisor", "witness"]:
        raise CollectorError("process-create intents are missing, reordered, or duplicated")


def _validate_witness(raw: bytes, ws: str, nonce: str) -> bool:
    if not raw or len(raw) > LINE_CAP or not raw.endswith(b"\n") or len(raw.splitlines()) != 1:
        return False
    value = strict_json(raw.splitlines()[0], cap=LINE_CAP)
    ns = "NS-" + hashlib.sha256((ws + nonce).encode()).hexdigest()[:32]
    return (set(value) == WITNESS_KEYS and value.get("schema") == 1 and value.get("runId") == ns and
            value.get("nonce") == nonce and value.get("seq") == 1 and value.get("phase") == 0 and
            value.get("event") == "READY" and value.get("role") == "witness" and value.get("status") == "READY" and
            value.get("exitCode") == -1 and value.get("childCount") == 0 and value.get("echild") is False and
            value.get("setResult") == -1 and value.get("getResult") == -1 and value.get("getValue") == -1 and
            value.get("prctl") == -1 and type(value.get("pid")) is int and value["pid"] > 0 and
            type(value.get("startTicks")) is int and value["startTicks"] > 0 and value.get("ppid", 0) > 0 and
            all(value.get(f"uid{i}") == UID for i in range(4)))


def _validate_events(events: list[dict[str, Any]], prefix: tuple[int, int]) -> dict[str, Any]:
    if events[0]["event"] != "READY":
        raise CollectorError("schema2 transcript lacks initial READY")
    supervisor = (events[0]["pid"], events[0]["startTicks"], events[0]["ppid"])
    if (events[0]["role"] != "supervisor" or events[0]["childCount"] != 0 or
            events[0]["socketDev"] != -1 or events[0]["socketIno"] != -1 or
            events[0]["lockDev"] != -1 or events[0]["lockIno"] != -1 or
            (events[0]["prefixDev"], events[0]["prefixIno"]) != prefix):
        raise CollectorError("supervisor READY identity is invalid")
    live: dict[tuple[int, int], str] = {}
    reaped: dict[tuple[int, int], int] = {}
    live_facts: dict[tuple[int, int], tuple[int, tuple[int, int, int, int]]] = {}
    reaped_facts: dict[tuple[int, int], tuple[int, tuple[int, int, int, int]]] = {}
    client_key: tuple[int, int] | None = None
    server_key: tuple[int, int] | None = None
    server_ready = False
    server_exit = False
    phase_done = False
    run_done = None
    for item in events:
        if run_done is not None:
            raise CollectorError("schema2 frames appeared after RUN_DONE")
        if item["event"] == "ERROR":
            raise CollectorError("native ERROR cannot qualify positive server isolation")
        if (item["prefixDev"], item["prefixIno"]) != prefix:
            raise CollectorError("prefix identity changed")
        identity = (item["pid"], item["startTicks"])
        if item["role"] == "supervisor" and (item["pid"], item["startTicks"], item["ppid"]) != supervisor:
            raise CollectorError("supervisor identity changed")
        if item["event"] == "SPAWN":
            if item["role"] not in {"server", "client"} or item["status"] != "RUNNING" or item["ppid"] != supervisor[0] or identity in live or identity in reaped:
                raise CollectorError("SPAWN ownership facts are invalid")
            if item["role"] == "server":
                if server_key is not None or item["phase"] != "server":
                    raise CollectorError("server birth is missing, duplicated, or reordered")
                server_key = identity
            elif not server_ready or client_key is not None or item["phase"] != "client":
                raise CollectorError("client birth preceded exact server readiness")
            live[identity] = item["role"]
            live_facts[identity] = (item["ppid"], tuple(item[f"uid{i}"] for i in range(4)))
            if item["role"] == "client":
                client_key = identity
        elif item["event"] == "OWNED_ADOPT":
            if item["role"] != "adopted" or item["status"] != "RUNNING" or item["ppid"] != supervisor[0]:
                raise CollectorError("OWNED_ADOPT ownership facts are invalid")
            if identity not in live:
                live[identity] = "adopted"  # no birth is invented for an adopted node
                live_facts[identity] = (item["ppid"], tuple(item[f"uid{i}"] for i in range(4)))
            elif live[identity] not in {"server", "client", "adopted"}:
                raise CollectorError("adopted PID role changed")
            elif live_facts[identity] != (item["ppid"], tuple(item[f"uid{i}"] for i in range(4))):
                raise CollectorError("adopted PID identity changed")
        elif item["event"] == "EXIT":
            if item["role"] not in {"server", "client", "adopted"} or identity not in live or item["status"] != "REAPED" or item["exitCode"] < 0:
                raise CollectorError("EXIT is not a reserved/reaped child")
            if live_facts[identity] != (item["ppid"], tuple(item[f"uid{i}"] for i in range(4))):
                raise CollectorError("EXIT identity does not match reserved child")
            del live[identity]
            reaped_facts[identity] = live_facts.pop(identity)
            reaped[identity] = item["exitCode"]
            if item["role"] == "server":
                if server_key != identity or not server_ready or not phase_done or server_exit:
                    raise CollectorError("server EXIT is missing, duplicated, or reordered")
                server_exit = True
        elif item["event"] == "PHASE_DONE":
            if (item["role"] != "client" or item["phase"] != "client" or item["status"] != "PASS" or
                    client_key != identity or reaped.get(identity) != 0 or
                    reaped_facts.get(identity) != (item["ppid"], tuple(item[f"uid{i}"] for i in range(4)))):
                raise CollectorError("PHASE_DONE does not match just-reaped client exit0")
            phase_done = True
        elif item["event"] == "RUN_DONE":
            if item["role"] != "supervisor" or item["status"] not in {"PASS", "CANCELLED"} or item["phase"] != "none" or item["childCount"] != 0 or not item["echild"] or live:
                raise CollectorError("RUN_DONE lacks ECHILD closure")
            run_done = item
        elif item["event"] == "SERVER_READY":
            if (item["role"] != "server" or item["phase"] != "server" or item["status"] != "RUNNING" or
                    server_key != identity or server_ready or client_key is not None):
                raise CollectorError("SERVER_READY semantics invalid")
            server_ready = True
        if item["childCount"] != len(live):
            raise CollectorError("native childCount does not match owned live children")
    if (not phase_done or run_done is None or server_key is None or not server_ready or
            not server_exit or client_key is None):
        raise CollectorError("complete server/client closure is missing")
    return {"supervisorExit": 0, "echild": True, "clientPass": True, "runDone": run_done, "reaped": reaped}


def validate_wine_result(raw: bytes, run_id: str, ws: str, nonce: str) -> dict[str, Any]:
    value = strict_json(raw, cap=JSON_CAP)
    if set(value) != RESULT_KEYS or value.get("schema") != 1 or value.get("runId") != run_id or value.get("wsRunId") != ws or value.get("nonce") != nonce:
        raise CollectorError("Wine result binding or fields mismatch")
    if value.get("terminalStatus") not in {"PASS", "CANCELLED", "UNKNOWN"} or value.get("nativeStatus") not in {"PASS", "CANCELLED", "UNKNOWN"}:
        raise CollectorError("Wine result status is invalid")
    if any(type(value.get(key)) is not bool for key in ("closureObserved", "qualification", "clientPass", "echild")):
        raise CollectorError("Wine result boolean types are invalid")
    if value["supervisorExit"] is not None and type(value["supervisorExit"]) is not int:
        raise CollectorError("Wine result supervisor exit type is invalid")
    if value["error"] is not None and not isinstance(value["error"], str):
        raise CollectorError("Wine result error type is invalid")
    proof = value.get("witnessProof")
    if not isinstance(proof, dict) or set(proof) != {"status", "stableBeforeQ", "qSent", "reaped", "exit"} or proof.get("status") != "PASS" or any(type(proof.get(k)) is not bool for k in ("stableBeforeQ", "qSent", "reaped")) or proof.get("exit") != 0:
        raise CollectorError("Wine witness proof is not PASS")
    if value["terminalStatus"] == "PASS":
        if not (value["nativeStatus"] == "PASS" and value["qualification"] and value["closureObserved"] and value["clientPass"] and value["supervisorExit"] == 0 and value["echild"] and proof["stableBeforeQ"] and proof["qSent"] and proof["reaped"] and not value["error"]):
            raise CollectorError("PASS result proof is incomplete")
    elif value["terminalStatus"] == "CANCELLED":
        if not (value["nativeStatus"] == "CANCELLED" and value["qualification"] and value["closureObserved"] and value["supervisorExit"] == 0 and value["echild"] and not value["error"]):
            raise CollectorError("CANCELLED result proof is incomplete")
    else:
        if value["qualification"] or not isinstance(value["error"], str) or not value["error"]:
            raise CollectorError("UNKNOWN result is incorrectly qualified")
    return value


def _same_terminal(current: dict[str, Any], run: dict[str, Any], result: dict[str, Any]) -> bool:
    status = result["terminalStatus"]
    generic_status = "EXITED" if status == "PASS" else "CANCELLED"
    return (current.get("runId") == run.get("runId") == result.get("runId") and
            current.get("status") == generic_status and run.get("status", run.get("terminalStatus", generic_status)) in {status, generic_status} and
            current.get("terminationUnknown") is False and current.get("quiescenceConfirmed") is True and
            status in {"PASS", "CANCELLED"})


def qualify_bundle(bundle: dict[str, bytes], bundle_after: dict[str, bytes], current_raw: bytes, current_after: bytes,
                    run_id: str, ws: str, nonce: str, generic: dict[str, Any], generic_after: dict[str, Any],
                    run_record: dict[str, Any] | None = None, run_record_after: dict[str, Any] | None = None,
                    prepared_identity: tuple[int, int] | None = None,
                    original_binding: bytes | None = None) -> dict[str, Any]:
    """Return a server-only qualification; every input must be byte-stable."""
    binding_raw = bundle.get("wine-binding.json", b"")
    _validate_wine_binding(binding_raw, run_id, ws, nonce)
    _validate_wine_binding(bundle_after.get("wine-binding.json", b""), run_id, ws, nonce)
    if (binding_raw != bundle_after.get("wine-binding.json") or
            (original_binding is not None and binding_raw != original_binding)):
        raise CollectorError("Wine binding changed during capture")
    result_raw = bundle.get("wine-result.json", b"")
    result_after = bundle_after.get("wine-result.json", b"")
    result = validate_wine_result(result_raw, run_id, ws, nonce)
    if result_raw != result_after or current_raw != current_after or generic != generic_after:
        raise CollectorError("terminal current/run/result changed during capture")
    if run_record is not None:
        if run_record != (run_record_after or {}) or run_record.get("runId") != run_id or run_record.get("status") != "EXITED":
            raise CollectorError("authoritative RUN record is not a stable EXITED binding")
        if run_record.get("terminationUnknown") is not False or run_record.get("quiescenceConfirmed") is not True:
            raise CollectorError("authoritative RUN record lacks closure state")
    commit = strict_json(bundle["wine-result-commit.json"], cap=JSON_CAP)
    quarantine = strict_json(bundle["wine-result-quarantine.json"], cap=JSON_CAP)
    admission = strict_json(bundle["wine-result-admission.json"], cap=JSON_CAP)
    result_sha = sha256_bytes(result_raw)
    for marker in (commit, quarantine, admission):
        if marker.get("runId") != run_id or marker.get("wsRunId") != ws or marker.get("nonce") != nonce:
            raise CollectorError("Wine result marker binding mismatch")
        if marker.get("terminalStatus") != result["terminalStatus"]:
            raise CollectorError("Wine result marker status mismatch")
    if (commit.get("state"), commit.get("resultSha256"), admission.get("state"), admission.get("resultSha256")) != ("COMMITTED", result_sha, "COMMITTED", result_sha):
        raise CollectorError("Wine result commit/admission hash mismatch")
    if quarantine.get("state") != "PREPARE":
        raise CollectorError("Wine result quarantine marker mismatch")
    if not _same_terminal(generic, generic, result):
        raise CollectorError("generic RUN is not a stable terminal Wine RUN")
    events = parse_events(bundle["wine-native-stdout.jsonl"], ws, nonce)
    _validate_process_intents(bundle["wine-process-create-intents.jsonl"], run_id, ws, nonce)
    facts = _validate_events(events, (events[0]["prefixDev"], events[0]["prefixIno"]))
    if prepared_identity is not None and prepared_identity != (events[0]["prefixDev"], events[0]["prefixIno"]):
        raise CollectorError("native prefix identity differs from prepared prefix")
    if not _validate_witness(bundle["wine-witness-stdout.jsonl"], ws, nonce):
        raise CollectorError("witness proof is not exact")
    if (result["terminalStatus"] != "PASS" or result["nativeStatus"] != "PASS"
            or facts["runDone"]["status"] != result["nativeStatus"] or not facts["clientPass"]):
        raise CollectorError("server/client normal gate did not PASS")
    return {
        "schema": 1, "terminalStatus": result["terminalStatus"], "nativeStatus": result["nativeStatus"],
        "closureObserved": True, "qualification": True, "clientPass": True, "supervisorExit": 0,
        "echild": True, "strictPEQualification": False, "apiQualification": False,
        "visualQualification": False, "wowQualification": False,
    }


class Evidence:
    def __init__(self, parent: Path, provenance: dict[str, Any]) -> None:
        parent = _safe_output(parent)
        for _ in range(50):
            candidate = parent / f"wine62-server-isolation-{uuid.uuid4().hex}"
            try:
                candidate.mkdir(mode=0o700)
                break
            except FileExistsError:
                continue
        else:
            raise CollectorError("cannot allocate private evidence directory")
        self.root = candidate
        self.provenance = provenance
        self.sequence = 0
        self.phase = "preflight"
        _write_json_once(self.root / "source-provenance.json", provenance)
        _sync(self.root)

    def action(self, action: str, **fields: Any) -> None:
        self.sequence += 1
        phases = {
            "validated-before-control": "preflight", "wine-binding-observed": "capture",
            "evidence-captured-before-cleanup": "capture", "evidence-capture-incomplete": "capture",
            "owned-package-force-stop-after-ambiguous-intent": "cleanup",
            "owned-package-force-stop-failed": "cleanup", "collector-failure": "error",
        }
        self.phase = phases.get(action, self.phase)
        fields.setdefault("seq", self.sequence)
        fields.setdefault("phase", self.phase)
        fields.setdefault("timestampMs", int(time.time() * 1000))
        path = self.root / "actions.jsonl"
        data = (json.dumps({"action": action, **fields}, sort_keys=True, separators=(",", ":")) + "\n").encode()
        prior = path.stat().st_size if path.exists() else 0
        if prior + len(data) > JSON_CAP:
            raise CollectorError("action journal exceeded bound")
        if path.exists():
            with path.open("ab") as stream:
                stream.write(data); stream.flush(); os.fsync(stream.fileno())
        else:
            _write_once(path, data)

    def write_capture(self, name: str, files: dict[str, bytes | None]) -> None:
        target = self.root / name
        target.mkdir(mode=0o700)
        for filename, data in files.items():
            if data is None:
                continue
            _write_once(target / filename, data)
        _sync(target)

    def finish(self, result: dict[str, Any]) -> None:
        _write_json_once(self.root / "result.json", result)
        seal: dict[str, Any] = {"schema": 1, "files": {}}
        for path in sorted(self.root.rglob("*")):
            if path.is_file() and path.name != "seal.json":
                _regular(path, TOTAL_CAP)
                seal["files"][str(path.relative_to(self.root))] = {"bytes": path.stat().st_size, "sha256": sha256_file(path)}
        _write_json_once(self.root / "seal.json", seal)
        _sync(self.root)


def _read_authority(adb: AdbClient, run_id: str, ws: str) -> tuple[dict[str, bytes | None], dict[str, Any] | None, bytes | None]:
    files: dict[str, bytes | None] = {}
    for name in WINE_FILES:
        raw, status = _device_private(adb, f"{RUN_ROOT}/{run_id}/{name}", TOTAL_CAP if name.endswith("jsonl") else JSON_CAP, run_id=run_id)
        files[name] = raw
    raw, status = _device_private(adb, f"{RUN_ROOT}/{run_id}.json", JSON_CAP, run_id=run_id)
    generic = strict_json(raw, cap=JSON_CAP) if raw is not None else None
    # Admission is rooted beside the RUN directory, not inside caller JSON.
    admission, _ = _device_private(adb, f"{RUN_ROOT}/{run_id}-wine-result-admission.json", JSON_CAP, run_id=run_id)
    files["wine-result-admission.json"] = admission
    return files, generic, raw


def _read_output_logs(adb: AdbClient, ws: str) -> dict[str, bytes | None]:
    result: dict[str, bytes | None] = {}
    for name in OUTPUT_LOGS:
        raw, status = _device_private(
            adb, f"{WINE_OUTPUT_REL.format(ws=ws)}/{name}", STREAM_CAP, ws_run_id=ws,
        )
        if status != "ok" or raw is None:
            raise CollectorError("terminal Wine output log is missing or unreadable")
        result[name] = raw
    return result


def _read_authority_best_effort(
    adb: AdbClient, run_id: str, statuses: dict[str, str] | None = None,
) -> tuple[dict[str, bytes | None], bool]:
    """Read each already-allowlisted RUN file independently for failure evidence."""
    files: dict[str, bytes | None] = {}
    incomplete = False
    for name in WINE_FILES:
        try:
            raw, status = _device_private(
                adb, f"{RUN_ROOT}/{run_id}/{name}",
                TOTAL_CAP if name.endswith("jsonl") else JSON_CAP, run_id=run_id,
            )
        except Exception:
            raw = None
            status = "read-error"
        incomplete = incomplete or status not in {"ok", "missing"} or (name == "wine-binding.json" and status != "ok")
        if statuses is not None:
            statuses[name] = status
        files[name] = raw
    for name, path, required in (
        ("wine-result-admission.json", f"{RUN_ROOT}/{run_id}-wine-result-admission.json", False),
        ("authoritative-run.json", f"{RUN_ROOT}/{run_id}.json", True),
    ):
        try:
            raw, status = _device_private(adb, path, JSON_CAP, run_id=run_id)
        except Exception:
            raw, status = None, "read-error"
        incomplete = incomplete or status not in {"ok", "missing"} or (required and status != "ok")
        if statuses is not None:
            statuses[name] = status
        files[name] = raw
    return files, incomplete


def _read_output_logs_best_effort(
    adb: AdbClient, ws: str, statuses: dict[str, str] | None = None,
) -> tuple[dict[str, bytes | None], bool]:
    """Retain every fixed output stream that remains readable after a failure."""
    result: dict[str, bytes | None] = {}
    incomplete = False
    for name in OUTPUT_LOGS:
        try:
            raw, status = _device_private(
                adb, f"{WINE_OUTPUT_REL.format(ws=ws)}/{name}", STREAM_CAP, ws_run_id=ws,
            )
        except Exception:
            raw, status = None, "read-error"
        incomplete = incomplete or status not in {"ok", "missing"}
        if statuses is not None:
            statuses[name] = status
        result[name] = raw
    return result, incomplete


def _wait_for_prepared_receipt(
    adb: AdbClient, run_id: str, ws: str, nonce: str, operation_deadline: float,
) -> tuple[bytes, dict[str, Any]]:
    """Wait for the exact prefix ticket without accepting a different RUN."""
    now = time.monotonic()
    prefix_deadline = min(operation_deadline, now + PREFIX_PREPARE_SECONDS)
    previous_deadline = adb.deadline
    if previous_deadline is not None:
        adb.deadline = min(previous_deadline, prefix_deadline)
    try:
        while time.monotonic() < prefix_deadline:
            _, current = _read_current(adb)
            if time.monotonic() >= prefix_deadline:
                raise CollectorError("prepared prefix receipt was not observed before deadline")
            if current is None or current.get("runId") != run_id:
                raise CollectorError("Wine RUN changed while preparing prefix")
            status = current.get("status")
            if status not in {"PREPARED", "ACTIVE", "STARTING", "RUNNING", "CANCELLING", "EXITED", "CANCELLED", "FAILED"}:
                raise CollectorError("Wine RUN left prefix preparation state")
            receipt, receipt_status = _device_private(
                adb, PREPARED_RECEIPT_REL.format(ws=ws), JSON_CAP, ws_run_id=ws,
            )
            if time.monotonic() >= prefix_deadline:
                raise CollectorError("prepared prefix receipt was not observed before deadline")
            if receipt_status == "ok" and receipt is not None:
                # Bind the ticket to the same generic RUN after the read; a late
                # ticket from a switched RUN is never admitted.
                _, current_after = _read_current(adb)
                if time.monotonic() >= prefix_deadline:
                    raise CollectorError("prepared prefix receipt was not observed before deadline")
                if current_after is None or current_after.get("runId") != run_id:
                    raise CollectorError("Wine RUN changed while preparing prefix")
                receipt_value = _validate_prepared_receipt(receipt, ws, nonce)
                if time.monotonic() >= prefix_deadline:
                    raise CollectorError("prepared prefix receipt was not observed before deadline")
                return receipt, receipt_value
            if receipt_status != "missing":
                raise CollectorError("prepared prefix receipt is unreadable")
            if status in {"EXITED", "CANCELLED", "FAILED"}:
                raise CollectorError("prepared prefix receipt is missing before terminal RUN")
            remaining = prefix_deadline - time.monotonic()
            if remaining <= 0:
                break
            time.sleep(min(0.25, remaining))
        raise CollectorError("prepared prefix receipt was not observed before deadline")
    finally:
        adb.deadline = previous_deadline


def _wait_for_terminal_current(
    adb: AdbClient, run_id: str, operation_deadline: float,
) -> tuple[bytes, dict[str, Any]]:
    """Positive snapshots are collected only after the same RUN is terminal."""
    previous_deadline = adb.deadline
    adb.deadline = min(previous_deadline or operation_deadline, operation_deadline)
    try:
        while time.monotonic() < operation_deadline:
            raw, current = _read_current(adb)
            if time.monotonic() >= operation_deadline:
                break
            if current is None or current.get("runId") != run_id:
                raise CollectorError("Wine RUN changed before terminal capture")
            if current.get("status") in {"EXITED", "CANCELLED", "FAILED"} and raw is not None:
                return raw, current
            if current.get("status") not in {"PREPARED", "ACTIVE", "STARTING", "RUNNING", "CANCELLING"}:
                raise CollectorError("Wine RUN has an invalid state before terminal capture")
            time.sleep(min(0.25, max(0.0, operation_deadline - time.monotonic())))
        raise CollectorError("terminal Wine RUN was not observed before deadline")
    finally:
        adb.deadline = previous_deadline


def _validate_wine_binding(raw: bytes, run_id: str, ws: str, nonce: str) -> dict[str, Any]:
    value = strict_json(raw, cap=JSON_CAP)
    if (set(value) != {"schema", "kind", "runId", "wsRunId", "nonce", "status"} or
            type(value.get("schema")) is not int or value["schema"] != 1 or
            value.get("kind") != "wine-binding" or value.get("status") != "BOUND" or
            value.get("runId") != run_id or value.get("wsRunId") != ws or value.get("nonce") != nonce or
            not RUN_RE.fullmatch(run_id) or not WS_RE.fullmatch(ws) or not NONCE_RE.fullmatch(nonce)):
        raise CollectorError("Wine binding fields or identity mismatch")
    return value


def _require_terminal_current(current: dict[str, Any] | None) -> None:
    if current is not None and (
        not isinstance(current.get("runId"), str) or not RUN_RE.fullmatch(current["runId"]) or
        current.get("status") not in {"EXITED", "CANCELLED", "FAILED"}
    ):
        raise CollectorError("authoritative RUN is not a valid terminal state")


def _validate_prepared_receipt(raw: bytes, ws: str, nonce: str) -> dict[str, Any]:
    value = strict_json(raw, cap=JSON_CAP)
    expected = {"schema", "wsRunId", "nonce", "prefix", "runtimeRoot", "prefixDev", "prefixIno", "imageManifestSha256", "imageZipSha256"}
    if set(value) != expected or value.get("schema") != "wowforever.wine-prefix-prepared/v1" or value.get("wsRunId") != ws or value.get("nonce") != nonce:
        raise CollectorError("prepared prefix receipt fields or binding mismatch")
    if (type(value.get("prefixDev")) is not int or type(value.get("prefixIno")) is not int or
            value["prefixDev"] <= 0 or value["prefixIno"] <= 0 or
            value.get("imageManifestSha256") != IMAGE_MANIFEST_SHA256 or value.get("imageZipSha256") != IMAGE_ZIP_SHA256):
        raise CollectorError("prepared prefix receipt identity or image pins mismatch")
    expected_prefixes = {
        f"/data/data/{PACKAGE}/files/forever/wine-controls/{ws}/prefix/.wine",
        f"/data/user/0/{PACKAGE}/files/forever/wine-controls/{ws}/prefix/.wine",
    }
    if (not isinstance(value.get("prefix"), str) or posixpath.normpath(value["prefix"]) != value["prefix"] or
            value["prefix"] not in expected_prefixes):
        raise CollectorError("prepared prefix receipt path is not the fixed WS prefix")
    if value.get("runtimeRoot") not in {
        "/data/data/com.wowforever.arm64probe/files/imagefs_shared/proton/proton-11.0-90624-arm64ec",
        "/data/user/0/com.wowforever.arm64probe/files/imagefs_shared/proton/proton-11.0-90624-arm64ec",
    }:
        raise CollectorError("prepared prefix receipt runtime root is not fixed")
    return value


def _read_prefix_identity(adb: AdbClient, ws: str) -> tuple[int, int]:
    if not WS_RE.fullmatch(ws):
        raise CollectorError("prepared prefix identity is not bound to a fixed WS")
    path = f"files/forever/wine-controls/{ws}/prefix/.wine"
    _check_private_no_follow(adb, path)
    result = adb.shell("run-as", PACKAGE, "stat", "-c", "%d:%i", path, timeout=10)
    if result.returncode != 0 or result.stderr:
        raise CollectorError("prepared prefix stat failed")
    match = re.fullmatch(r"([0-9]+):([0-9]+)", _text(result))
    if match is None or int(match.group(1)) <= 0 or int(match.group(2)) <= 0:
        raise CollectorError("prepared prefix identity is invalid")
    return int(match.group(1)), int(match.group(2))


def _read_current(adb: AdbClient) -> tuple[bytes | None, dict[str, Any] | None]:
    raw, status = _device_private(adb, GENERIC_CURRENT, JSON_CAP)
    if status not in {"ok", "missing"}:
        raise CollectorError("authoritative current is unreadable")
    return raw, strict_json(raw, cap=JSON_CAP) if raw is not None else None


def _history(adb: AdbClient) -> dict[str, bytes | None]:
    values: dict[str, bytes | None] = {}
    for label, path in (("old-v1-current", OLD_V1_CURRENT), ("old-v2-current", OLD_V2_CURRENT)):
        values[label], _ = _device_private(adb, path, JSON_CAP)
    for name in OLD_V2_PERSISTENT_FILES:
        values[f"old-v2/{name}"] , _ = _device_private(adb, f"{OLD_V2_PERSISTENT_ROOT}/{name}", TOTAL_CAP if name == "events.jsonl" else JSON_CAP)
    return values


def _history_stable(before: dict[str, bytes | None], after: dict[str, bytes | None]) -> None:
    if before != after:
        raise CollectorError("pre-existing fixture history changed during Wine trial")
    if before.get("old-v1-current") is None or sha256_bytes(before["old-v1-current"]) != OLD_V1_CURRENT_SHA256:
        raise CollectorError("old v1 current seal changed")
    if before.get("old-v2-current") is None or sha256_bytes(before["old-v2-current"]) != OLD_V2_CURRENT_SHA256:
        raise CollectorError("old v2 current seal changed")
    if any(before.get(f"old-v2/{name}") is None for name in OLD_V2_PERSISTENT_FILES):
        raise CollectorError("old v2 archive/receipt history is incomplete")
    for name, expected in OLD_V2_HISTORY_SHA256.items():
        if sha256_bytes(before[f"old-v2/{name}"] or b"") != expected:
            raise CollectorError(f"old v2 immutable history seal changed: {name}")


def _require_prior_wine_closed(adb: AdbClient, current: dict[str, Any] | None) -> None:
    """Block an old Wine binding unless its independent admission is complete."""
    if not current or not isinstance(current.get("runId"), str) or not RUN_RE.fullmatch(current["runId"]):
        return
    run_id = current["runId"]
    binding_raw, binding_status = _device_private(adb, f"{RUN_ROOT}/{run_id}/wine-binding.json", JSON_CAP, run_id=run_id)
    if binding_status == "missing":
        return  # A non-Wine historical RUN is not reinterpreted as Wine.
    if binding_raw is None:
        raise CollectorError("prior Wine binding is unreadable")
    binding = strict_json(binding_raw, cap=JSON_CAP)
    ws, nonce = binding.get("wsRunId"), binding.get("nonce")
    if binding.get("runId") != run_id or not WS_RE.fullmatch(str(ws)) or not NONCE_RE.fullmatch(str(nonce)):
        raise CollectorError("prior Wine binding is corrupt")
    result_raw, result_status = _device_private(adb, f"{RUN_ROOT}/{run_id}/wine-result.json", JSON_CAP, run_id=run_id)
    commit_raw, commit_status = _device_private(adb, f"{RUN_ROOT}/{run_id}/wine-result-commit.json", JSON_CAP, run_id=run_id)
    admission_raw, admission_status = _device_private(adb, f"{RUN_ROOT}/{run_id}-wine-result-admission.json", JSON_CAP, run_id=run_id)
    if result_raw is None or commit_raw is None or admission_raw is None:
        raise CollectorError("prior Wine binding lacks positive result admission")
    result = validate_wine_result(result_raw, run_id, str(ws), str(nonce))
    if result["terminalStatus"] not in {"PASS", "CANCELLED"} or current.get("terminationUnknown") is not False or current.get("quiescenceConfirmed") is not True:
        raise CollectorError("prior Wine RUN is active or unknown")
    result_sha = sha256_bytes(result_raw)
    for raw in (commit_raw, admission_raw):
        marker = strict_json(raw, cap=JSON_CAP)
        if marker.get("state") != "COMMITTED" or marker.get("resultSha256") != result_sha or marker.get("runId") != run_id or marker.get("wsRunId") != ws or marker.get("nonce") != nonce:
             raise CollectorError("prior Wine result admission is incomplete")


def _require_fixed_interrupted_prior(adb: AdbClient, current_raw: bytes | None,
                                     current: dict[str, Any] | None, preflight: dict[str, Any]) -> dict[str, Any]:
    """Authorize only the sealed Wine60 ACTIVE/PREFLIGHT state after fresh boot."""
    try:
        contract, _ = load_contract()
        verify_external_evidence(contract)
    except (ContractError, OSError, ValueError) as exc:
        raise CollectorError("fixed interrupted disposition contract is not authenticated") from exc
    _validate_fixed_authority(adb, contract)
    prior = contract["prior"]
    expected = prior["current"]
    if not isinstance(current_raw, bytes) or sha256_bytes(current_raw) != expected["sha256"] or len(current_raw) != expected["bytes"]:
        raise CollectorError("active Wine60 current is not byte-exact")
    if not isinstance(current, dict) or (current.get("runId"), current.get("status"), current.get("stage")) != (
            prior["runId"], "ACTIVE", "PREFLIGHT"):
        raise CollectorError("active Wine60 state is not the fixed interrupted authority")
    if current.get("launchAttempted") is not False or current.get("terminationUnknown") is not False or current.get("quiescenceConfirmed") is not False:
        raise CollectorError("active Wine60 state has changed markers")
    boot = preflight.get("record", {}).get("bootAfterReboot")
    if boot == prior["lastObservedBootId"] or boot == preflight.get("record", {}).get("interruptedPreviousBootId"):
        raise CollectorError("fresh boot is not distinct from the observed pre-reboot boot")
    if preflight.get("record", {}).get("interruptedDispositionContractSha256") != "c198e20f582b90e5bc691e3e4712e8b2b1ecf33434bf1f86232c30be7fab2ac6":
        raise CollectorError("preflight disposition contract pin is missing")
    if preflight.get("record", {}).get("candidateAuthority") != candidate_authority():
        raise CollectorError("preflight candidate authority is not exact")
    run_raw, run_status = _device_private(adb, f"{RUN_ROOT}/{prior['runId']}.json", JSON_CAP, run_id=prior["runId"])
    binding_raw, binding_status = _device_private(adb, f"{RUN_ROOT}/{prior['runId']}/wine-binding.json", JSON_CAP, run_id=prior["runId"])
    if run_status != "ok" or run_raw != current_raw or binding_status != "ok" or binding_raw is None:
        raise CollectorError("fixed interrupted RUN or binding is unreadable")
    if sha256_bytes(binding_raw) != prior["binding"]["sha256"] or len(binding_raw) != prior["binding"]["bytes"]:
        raise CollectorError("fixed interrupted binding hash mismatch")
    _validate_wine_binding(binding_raw, prior["runId"], prior["wsRunId"], prior["nonce"])
    for name in WINE_FILES:
        if name == "wine-binding.json":
            continue
        raw, status = _device_private(adb, f"{RUN_ROOT}/{prior['runId']}/{name}", TOTAL_CAP if name.endswith("jsonl") else JSON_CAP, run_id=prior["runId"])
        if raw is not None or status != "missing":
            raise CollectorError("fixed interrupted RUN acquired a marker or result")
    admission, status = _device_private(adb, f"{RUN_ROOT}/{prior['runId']}-wine-result-admission.json", JSON_CAP, run_id=prior["runId"])
    if admission is not None or status != "missing":
        raise CollectorError("fixed interrupted RUN has a result admission")
    _require_disposition_absent(adb)
    return contract


def _require_fixed_known_failure_prior(adb: AdbClient, current_raw: bytes | None,
                                       current: dict[str, Any] | None, preflight: dict[str, Any]) -> dict[str, Any]:
    """Authorize only the sealed Wine61 FAILED/UNKNOWN prelaunch state."""
    try:
        contract, _ = load_continuation_contract()
        verify_continuation_external_evidence(contract)
    except (ContractError, OSError, ValueError) as exc:
        raise CollectorError("continuation contract is not authenticated") from exc
    failure = contract["knownFailure"]
    expected_current = failure["current"]
    if (not isinstance(current_raw, bytes) or sha256_bytes(current_raw) != expected_current["sha256"] or
            len(current_raw) != expected_current["bytes"]):
        raise CollectorError("known Wine61 current is not byte-exact")
    if not isinstance(current, dict) or (current.get("runId"), current.get("stage"), current.get("status"),
                                         current.get("terminationUnknown"), current.get("quiescenceConfirmed")) != (
                                             failure["runId"], "EXITED", "FAILED", True, False):
        raise CollectorError("known Wine61 failure status is not exact")
    if current.get("message") != "filesDir is not the canonical private app root":
        raise CollectorError("known Wine61 failure message is not exact")
    if preflight.get("record", {}).get("candidateAuthority") != candidate_authority():
        raise CollectorError("preflight candidate authority is not exact")
    run_raw, run_status = _device_private(adb, f"{RUN_ROOT}/{failure['runId']}.json", JSON_CAP, run_id=failure["runId"])
    binding_raw, binding_status = _device_private(adb, f"{RUN_ROOT}/{failure['runId']}/wine-binding.json", JSON_CAP, run_id=failure["runId"])
    result_raw, result_status = _device_private(adb, f"{RUN_ROOT}/{failure['runId']}/wine-result.json", JSON_CAP, run_id=failure["runId"])
    if (run_status != "ok" or run_raw != current_raw or binding_status != "ok" or binding_raw is None or
            result_status != "ok" or result_raw is None):
        raise CollectorError("known Wine61 RUN evidence is unreadable")
    if (sha256_bytes(binding_raw) != failure["binding"]["sha256"] or len(binding_raw) != failure["binding"]["bytes"] or
            sha256_bytes(result_raw) != failure["wineResult"]["sha256"] or len(result_raw) != failure["wineResult"]["bytes"]):
        raise CollectorError("known Wine61 binding/result seal mismatch")
    _validate_wine_binding(binding_raw, failure["runId"], failure["wsRunId"], failure["nonce"])
    result = strict_json(result_raw)
    if (result.get("terminalStatus"), result.get("nativeStatus"), result.get("closureObserved"),
            result.get("qualification"), result.get("echild"), result.get("error")) != (
                "UNKNOWN", "UNKNOWN", False, False, False, "filesDir is not the canonical private app root"):
        raise CollectorError("known Wine61 result is not the fixed UNKNOWN result")
    for name in WINE_FILES:
        if name in {"wine-binding.json", "wine-result.json"}:
            continue
        raw, status = _device_private(adb, f"{RUN_ROOT}/{failure['runId']}/{name}", TOTAL_CAP if name.endswith("jsonl") else JSON_CAP,
                                      run_id=failure["runId"])
        if raw is not None or status != "missing":
            raise CollectorError("known Wine61 RUN acquired an unexpected marker")
    _require_continuation_absent(adb)
    return contract


def _disposition_paths() -> dict[str, str]:
    root = f"{RUN_ROOT}/{DISPOSITION_RUN_ID}"
    return {
        "archive": f"{RUN_ROOT}/{DISPOSITION_RUN_ID}-current-before-abandonment.json",
        "reservation": f"{RUN_ROOT}/{DISPOSITION_RUN_ID}-abandonment-reservation.json",
        "receipt": f"{root}/abandoned-on-new-boot.json",
        "consumption": f"{RUN_ROOT}/{DISPOSITION_RUN_ID}-abandonment-consumption.json",
    }


def _continuation_paths() -> dict[str, str]:
    root = f"{RUN_ROOT}/{KNOWN_FAILURE_RUN_ID}"
    return {
        "archive": f"{RUN_ROOT}/{KNOWN_FAILURE_RUN_ID}-current-before-prelaunch-abort.json",
        "reservation": f"{RUN_ROOT}/{KNOWN_FAILURE_RUN_ID}-prelaunch-abort-reservation.json",
        "receipt": f"{root}/prelaunch-abort.json",
        "consumption": f"{RUN_ROOT}/{KNOWN_FAILURE_RUN_ID}-prelaunch-abort-consumption.json",
    }


def _require_disposition_absent(adb: AdbClient) -> None:
    for name, path in _disposition_paths().items():
        raw, status = _device_private(adb, path, JSON_CAP)
        if raw is not None or status != "missing":
            raise CollectorError(f"interrupted disposition {name} already exists before first admission")


def _require_continuation_absent(adb: AdbClient) -> None:
    for name, path in _continuation_paths().items():
        raw, status = _device_private(adb, path, TOTAL_CAP if name == "archive" else JSON_CAP)
        if raw is not None or status != "missing":
            raise CollectorError(f"prelaunch continuation {name} already exists before first admission")


def _validate_disposition_transaction(
    adb: AdbClient,
    current_raw: bytes,
    current: dict[str, Any],
    old_current_raw: bytes,
    preflight: dict[str, Any],
    candidate_identity: dict[str, Any],
) -> dict[str, Any]:
    paths = _disposition_paths()
    values: dict[str, bytes] = {}
    for name, path in paths.items():
        raw, status = _device_private(adb, path, TOTAL_CAP if name == "archive" else JSON_CAP)
        if raw is None or status != "ok":
            raise CollectorError(f"interrupted disposition {name} is incomplete")
        values[name] = raw
    if values["archive"] != old_current_raw:
        raise CollectorError("interrupted disposition archive does not preserve old current bytes")
    reservation = strict_json(values["reservation"])
    receipt = strict_json(values["receipt"])
    consumption = strict_json(values["consumption"])
    authority = candidate_authority()
    expected_reservation = {
        "schema", "kind", "state", "oldRunId", "newRunId", "wsRunId", "nonce", "oldBootId",
        "newBootId", "candidateAuthority", "contractSha256",
    }
    expected_receipt = {
        "schema", "kind", "runId", "wsRunId", "nonce", "oldBootId", "newBootId", "terminalStatus",
        "nativeStatus", "closureObserved", "qualification", "echild", "reason", "candidateAuthority",
        "oldCurrentSha256", "oldRunSha256", "oldBindingSha256",
    }
    expected_consumption = {"schema", "state", "oldRunId", "newRunId", "wsRunId", "nonce", "candidateAuthority", "receiptSha256"}
    if set(reservation) != expected_reservation or set(receipt) != expected_receipt or set(consumption) != expected_consumption:
        raise CollectorError("interrupted disposition JSON shape is not exact")
    if (type(reservation["schema"]) is not int or type(receipt["schema"]) is not int or
            type(consumption["schema"]) is not int or
            any(not isinstance(reservation[key], str) for key in ("kind", "state", "oldRunId", "newRunId", "wsRunId", "nonce", "oldBootId", "newBootId", "contractSha256")) or
            any(not isinstance(receipt[key], str) for key in ("kind", "runId", "wsRunId", "nonce", "oldBootId", "newBootId", "terminalStatus", "nativeStatus", "reason", "oldCurrentSha256", "oldRunSha256", "oldBindingSha256")) or
            any(not isinstance(consumption[key], str) for key in ("state", "oldRunId", "newRunId", "wsRunId", "nonce", "receiptSha256")) or
            any(type(receipt[key]) is not bool for key in ("closureObserved", "qualification", "echild"))):
        raise CollectorError("interrupted disposition JSON types are not exact")
    new_run_id = reservation.get("newRunId")
    ws_run = reservation.get("wsRunId")
    nonce = reservation.get("nonce")
    boot = preflight["record"].get("bootAfterReboot")
    previous_boot = preflight["record"].get("interruptedPreviousBootId")
    if (reservation.get("schema"), reservation.get("kind"), reservation.get("state"), reservation.get("oldRunId"),
            reservation.get("oldBootId"), reservation.get("newBootId"), reservation.get("contractSha256"),
            reservation.get("candidateAuthority")) != (
                1, "interrupted-run-disposition", "RESERVED", DISPOSITION_RUN_ID, previous_boot, boot,
                "c198e20f582b90e5bc691e3e4712e8b2b1ecf33434bf1f86232c30be7fab2ac6", authority):
        raise CollectorError("interrupted disposition reservation identity mismatch")
    if (not isinstance(new_run_id, str) or not RUN_RE.fullmatch(new_run_id) or
            not isinstance(ws_run, str) or not WS_RE.fullmatch(ws_run) or
            not isinstance(nonce, str) or not NONCE_RE.fullmatch(nonce) or
            new_run_id != current.get("runId") or current.get("status") not in {"EXITED", "CANCELLED", "FAILED"}):
        raise CollectorError("interrupted disposition first RUN identity mismatch")
    if reservation.get("candidateAuthority") != candidate_identity or candidate_identity != authority:
        raise CollectorError("interrupted disposition candidate authority mismatch")
    binding_raw, binding_status = _device_private(adb, f"{RUN_ROOT}/{new_run_id}/wine-binding.json", JSON_CAP, run_id=new_run_id)
    if binding_raw is None or binding_status != "ok":
        raise CollectorError("interrupted disposition first binding is missing")
    _validate_wine_binding(binding_raw, new_run_id, ws_run, nonce)
    old_binding_raw, old_binding_status = _device_private(
        adb, f"{RUN_ROOT}/{DISPOSITION_RUN_ID}/wine-binding.json", JSON_CAP, run_id=DISPOSITION_RUN_ID,
    )
    if old_binding_raw is None or old_binding_status != "ok":
        raise CollectorError("interrupted disposition old binding is missing")
    old_run_raw, old_run_status = _device_private(
        adb, f"{RUN_ROOT}/{DISPOSITION_RUN_ID}.json", JSON_CAP, run_id=DISPOSITION_RUN_ID,
    )
    if old_run_raw is None or old_run_status != "ok" or old_run_raw != old_current_raw:
        raise CollectorError("interrupted disposition old RUN bytes changed")
    if (receipt.get("schema"), receipt.get("kind"), receipt.get("runId"), receipt.get("wsRunId"), receipt.get("nonce"),
            receipt.get("oldBootId"), receipt.get("newBootId"), receipt.get("terminalStatus"), receipt.get("nativeStatus"),
            receipt.get("closureObserved"), receipt.get("qualification"), receipt.get("echild"), receipt.get("reason"),
            receipt.get("candidateAuthority"), receipt.get("oldCurrentSha256"), receipt.get("oldRunSha256"),
            receipt.get("oldBindingSha256")) != (
                1, "ABANDONED_ON_NEW_BOOT", DISPOSITION_RUN_ID,
                strict_json(old_binding_raw)["wsRunId"], strict_json(old_binding_raw)["nonce"],
                previous_boot, boot, "ABANDONED_ON_NEW_BOOT", "UNKNOWN",
                False, False, False, "host-capture-interruption", authority,
                sha256_bytes(old_current_raw), sha256_bytes(old_current_raw), sha256_bytes(old_binding_raw)):
        raise CollectorError("interrupted disposition receipt identity or old hashes mismatch")
    if (consumption.get("schema"), consumption.get("state"), consumption.get("oldRunId"), consumption.get("newRunId"),
            consumption.get("wsRunId"), consumption.get("nonce"), consumption.get("candidateAuthority"),
            consumption.get("receiptSha256")) != (
                1, "COMMITTED", DISPOSITION_RUN_ID, new_run_id, ws_run, nonce, authority,
                sha256_bytes(values["receipt"])):
        raise CollectorError("interrupted disposition consumption is not immutable")
    return {"newRunId": new_run_id, "wsRunId": ws_run, "nonce": nonce,
            "candidateAuthority": authority, "receiptSha256": sha256_bytes(values["receipt"])}


def _validate_continuation_transaction(
    adb: AdbClient,
    current_raw: bytes,
    current: dict[str, Any],
    old_current_raw: bytes,
    preflight: dict[str, Any],
    candidate_identity: dict[str, Any],
    contract: dict[str, Any],
) -> dict[str, Any]:
    values: dict[str, bytes] = {}
    for name, path in _continuation_paths().items():
        raw, status = _device_private(adb, path, TOTAL_CAP if name == "archive" else JSON_CAP)
        if raw is None or status != "ok":
            raise CollectorError(f"prelaunch continuation {name} is incomplete")
        values[name] = raw
    if values["archive"] != old_current_raw:
        raise CollectorError("prelaunch continuation archive does not preserve old current bytes")
    reservation = strict_json(values["reservation"])
    receipt = strict_json(values["receipt"])
    consumption = strict_json(values["consumption"])
    authority = candidate_authority()
    if set(reservation) != {"schema", "kind", "state", "oldRunId", "newRunId", "oldWsRunId", "oldNonce",
                             "wsRunId", "nonce", "oldBootId", "newBootId", "candidateAuthority", "contractSha256"}:
        raise CollectorError("prelaunch continuation reservation shape is not exact")
    if set(receipt) != {"schema", "kind", "runId", "wsRunId", "nonce", "newRunId", "newWsRunId", "newNonce",
                        "newBootId", "terminalStatus", "nativeStatus", "closureObserved", "qualification", "echild", "reason"}:
        raise CollectorError("prelaunch continuation receipt shape is not exact")
    if set(consumption) != {"schema", "kind", "state", "oldRunId", "newRunId", "oldWsRunId", "oldNonce",
                            "wsRunId", "nonce", "newBootId", "receiptSha256"}:
        raise CollectorError("prelaunch continuation consumption shape is not exact")
    record = preflight["record"]
    new_boot = record.get("bootAfterReboot")
    old_boot = contract["knownFailure"]["bootId"]
    new_run = reservation["newRunId"]
    ws_run = reservation["wsRunId"]
    nonce = reservation["nonce"]
    if (reservation.get("schema"), reservation.get("kind"), reservation.get("state"), reservation.get("oldRunId"),
            reservation.get("oldWsRunId"), reservation.get("oldNonce"), reservation.get("oldBootId"),
            reservation.get("newBootId"), reservation.get("candidateAuthority"), reservation.get("contractSha256")) != (
                1, "PRELAUNCH_ABORT", "RESERVED", KNOWN_FAILURE_RUN_ID,
                contract["knownFailure"]["wsRunId"], contract["knownFailure"]["nonce"], old_boot, new_boot,
                authority, CONTINUATION_CONTRACT_SHA256):
        raise CollectorError("prelaunch continuation reservation identity mismatch")
    if (not isinstance(new_run, str) or not RUN_RE.fullmatch(new_run) or new_run != current.get("runId") or
            not isinstance(ws_run, str) or not WS_RE.fullmatch(ws_run) or
            not isinstance(nonce, str) or not NONCE_RE.fullmatch(nonce) or
            reservation.get("candidateAuthority") != candidate_identity):
        raise CollectorError("prelaunch continuation new RUN identity mismatch")
    if (receipt.get("schema"), receipt.get("kind"), receipt.get("runId"), receipt.get("wsRunId"), receipt.get("nonce"),
            receipt.get("newRunId"), receipt.get("newWsRunId"), receipt.get("newNonce"), receipt.get("newBootId"),
            receipt.get("terminalStatus"), receipt.get("nativeStatus"), receipt.get("closureObserved"),
            receipt.get("qualification"), receipt.get("echild"), receipt.get("reason")) != (
                1, "PRELAUNCH_ABORT", KNOWN_FAILURE_RUN_ID, contract["knownFailure"]["wsRunId"], contract["knownFailure"]["nonce"],
                new_run, ws_run, nonce, new_boot, "PRELAUNCH_ABORT", "UNKNOWN", False, False, False,
                "known Wine61 prefix prelaunch failure"):
        raise CollectorError("prelaunch continuation receipt identity mismatch")
    if (consumption.get("schema"), consumption.get("kind"), consumption.get("state"), consumption.get("oldRunId"),
            consumption.get("newRunId"), consumption.get("oldWsRunId"), consumption.get("oldNonce"),
            consumption.get("wsRunId"), consumption.get("nonce"), consumption.get("newBootId"),
            consumption.get("receiptSha256")) != (
                1, "PRELAUNCH_ABORT_CONSUMPTION", "COMMITTED", KNOWN_FAILURE_RUN_ID, new_run,
                contract["knownFailure"]["wsRunId"], contract["knownFailure"]["nonce"], ws_run, nonce, new_boot,
                sha256_bytes(values["receipt"])):
        raise CollectorError("prelaunch continuation consumption is not immutable")
    return {"newRunId": new_run, "wsRunId": ws_run, "nonce": nonce,
            "candidateAuthority": authority, "receiptSha256": sha256_bytes(values["receipt"])}


def _marker_result(result: str, error: str) -> dict[str, Any]:
    return {"schema": 1, "outcome": result, "serverIsolationQualification": False, "strictPEQualification": False,
            "apiQualification": False, "visualQualification": False, "wowQualification": False, "error": error}


def _capture_failure_evidence(
    adb: AdbClient, evidence: Evidence, candidate_run_id: str | None, ws_run: str | None,
    candidate_raw: bytes | None,
) -> tuple[bool, bool]:
    """Capture current first, then independently retain fixed partial authority."""
    captured_any = False
    incomplete = False
    failure_current = candidate_raw
    try:
        current_raw, _ = _read_current(adb)
        if current_raw is not None:
            failure_current = current_raw
    except Exception:
        incomplete = True
    if failure_current is not None:
        try:
            _write_once(evidence.root / "generic-current-failure.json", failure_current)
            captured_any = True
        except Exception:
            incomplete = True
    statuses: dict[str, dict[str, str]] = {}
    if candidate_run_id is not None:
        try:
            statuses["authority"] = {}
            failure_files, authority_incomplete = _read_authority_best_effort(adb, candidate_run_id, statuses["authority"])
            evidence.write_capture("capture-failure", failure_files)
            captured_any = True
            incomplete = incomplete or authority_incomplete
        except Exception:
            incomplete = True
    if ws_run is not None:
        try:
            statuses["logs"] = {}
            logs, logs_incomplete = _read_output_logs_best_effort(adb, ws_run, statuses["logs"])
            evidence.write_capture("device-logs-failure", logs)
            captured_any = True
            incomplete = incomplete or logs_incomplete
        except Exception:
            incomplete = True
    try:
        _write_json_once(evidence.root / "failure-capture-statuses.json", {"schema": 1, "files": statuses, "incomplete": incomplete})
    except Exception:
        incomplete = True
    return captured_any, incomplete


def collect(args: argparse.Namespace, *, adb: AdbClient | None = None) -> int:
    require_serial()
    with device_control_lock(Path(args.output)):
        return _collect_locked(args, adb=adb)


def _collect_locked(args: argparse.Namespace, *, adb: AdbClient | None = None) -> int:
    apk = validate_apk(Path(args.apk), Path(args.audit), args.expected_apk_sha256)
    preflight = validate_preflight(Path(args.preflight_directory), apk["sha256"])
    # All source/provenance and read-only preflight checks precede ADB control.
    adb = adb or AdbClient()
    adb.deadline = time.monotonic() + HOST_SECONDS
    device = validate_device(adb, apk["sha256"], preflight)
    candidate_identity = device["candidateAuthority"]
    history_before = _history(adb)
    current_before_raw, current_before = _read_current(adb)
    interrupted_contract: dict[str, Any] | None = None
    continuation_contract: dict[str, Any] | None = None
    fixed_before: dict[str, str] | None = None
    if current_before is not None and current_before.get("status") == "ACTIVE":
        interrupted_contract = _require_fixed_interrupted_prior(adb, current_before_raw, current_before, preflight)
        fixed_before = _validate_fixed_authority(adb, interrupted_contract)
    elif current_before is not None and current_before.get("status") == "FAILED":
        continuation_contract = _require_fixed_known_failure_prior(adb, current_before_raw, current_before, preflight)
        fixed_before = _validate_fixed_authority(adb, continuation_contract)
    else:
        _require_terminal_current(current_before)
        _require_prior_wine_closed(adb, current_before)
        if current_before_raw is not None and current_before is not None:
            archive_raw, archive_status = _device_private(
                adb, _disposition_paths()["archive"], TOTAL_CAP,
            )
            if archive_raw is not None or archive_status != "missing":
                if archive_raw is None:
                    raise CollectorError("interrupted disposition archive is unreadable")
                _validate_disposition_transaction(
                    adb, current_before_raw, current_before, archive_raw, preflight, candidate_identity,
                )
    metadata = {"schema": 1, "collector": str(Path(__file__).resolve()), "collectorSha256": sha256_file(Path(__file__)),
                 "apk": apk, "preflight": preflight, "device": device, "authority": "SERVER_ISOLATION",
                  "scenario": ("interrupted_run_disposition" if interrupted_contract else
                               "prelaunch_continuation" if continuation_contract else "normal")}
    evidence = Evidence(Path(args.output), metadata)
    evidence.action("validated-before-control", package=PACKAGE, versionCode=VERSION_CODE, alias=ALIAS)
    evidence.write_capture("history-before", history_before)
    if current_before_raw is not None:
        _write_once(evidence.root / "generic-current-before.json", current_before_raw)
    intent_unknown = False
    intent_sent = False
    ws_run: str | None = None
    candidate_run_id: str | None = None
    candidate_raw: bytes | None = None
    try:
        journal: list[dict[str, Any]] = []
        _control_activity(adb, journal)
        intent_unknown = True
        intent_sent = True
        for item in journal:
            evidence.action(str(item.pop("action")), **item)
        journal.clear()
        deadline = min(adb.deadline or time.monotonic(), time.monotonic() + HOST_SECONDS - CLEANUP_SECONDS)
        while time.monotonic() < deadline:
            candidate_raw, candidate = _read_current(adb)
            if candidate and RUN_RE.fullmatch(str(candidate.get("runId", ""))) and candidate.get("runId") != (current_before or {}).get("runId"):
                candidate_run_id = str(candidate["runId"])
                # The generic RUN does not carry WS in all store versions;
                # obtain the binding only from this candidate RUN directory.
                break
            time.sleep(0.25)
        if candidate_run_id is None:
            raise CollectorError("new Wine RUN binding was not observed")
        binding_raw, binding_status = _device_private(adb, f"{RUN_ROOT}/{candidate_run_id}/wine-binding.json", JSON_CAP, run_id=candidate_run_id)
        binding = strict_json(binding_raw or b"", cap=JSON_CAP)
        ws_run, nonce = binding.get("wsRunId"), binding.get("nonce")
        if not WS_RE.fullmatch(str(ws_run)) or not NONCE_RE.fullmatch(str(nonce)) or binding.get("runId") != candidate_run_id:
            raise CollectorError("Wine binding is invalid")
        _validate_wine_binding(binding_raw or b"", candidate_run_id, ws_run, nonce)
        intents_raw, intents_status = _device_private(
            adb, f"{RUN_ROOT}/{candidate_run_id}/wine-process-create-intents.jsonl", TOTAL_CAP,
            run_id=candidate_run_id,
        )
        if binding_raw is None or binding_status != "ok" or intents_status not in {"ok", "missing"}:
            raise CollectorError("Wine core binding/intents are not readable")
        # Binding publication precedes asynchronous prefix preparation.  Missing
        # process markers at this point are retained, not treated as a failure
        # or as evidence that no process will be created.
        evidence.write_capture("core-before", {
            "generic-current.json": candidate_raw,
            "wine-binding.json": binding_raw,
            "wine-process-create-intents.jsonl": intents_raw,
        })
        receipt, receipt_value = _wait_for_prepared_receipt(
            adb, candidate_run_id, str(ws_run), str(nonce), deadline,
        )
        prepared_identity = (receipt_value["prefixDev"], receipt_value["prefixIno"])
        if _read_prefix_identity(adb, ws_run) != prepared_identity:
            raise CollectorError("prepared prefix stat differs from receipt")
        evidence.action("wine-binding-observed", runId=candidate_run_id, wsRunId=ws_run)
        # Wait first: comparing a PREPARING snapshot to a terminal snapshot
        # would reject a legitimate normal run merely for making progress.
        terminal_current_raw, current = _wait_for_terminal_current(adb, candidate_run_id, deadline)
        receipt_after, receipt_after_value = _wait_for_prepared_receipt(
            adb, candidate_run_id, str(ws_run), str(nonce), min(deadline, time.monotonic() + 5.0),
        )
        if receipt_after != receipt or receipt_after_value != receipt_value:
            raise CollectorError("prepared prefix receipt changed after first admission")
        first_files, first_run, first_run_raw = _read_authority(adb, candidate_run_id, ws_run)
        first_logs = _read_output_logs(adb, ws_run)
        second_files, second_run, second_run_raw = _read_authority(adb, candidate_run_id, ws_run)
        second_logs = _read_output_logs(adb, ws_run)
        after_current_raw, after_current = _read_current(adb)
        history_after = _history(adb)
        _history_stable(history_before, history_after)
        if interrupted_contract:
            fixed_after = _validate_fixed_authority(adb, interrupted_contract, skip_current=True)
            for path, value in (fixed_before or {}).items():
                if path != GENERIC_CURRENT and fixed_after.get(path) != value:
                    raise CollectorError(f"fixed authority changed during disposition: {path}")
            _validate_disposition_transaction(
                adb, after_current_raw or b"", after_current or {}, current_before_raw or b"", preflight, candidate_identity,
            )
        if continuation_contract:
            fixed_after = _validate_fixed_authority(adb, continuation_contract, skip_current=True)
            for path, value in (fixed_before or {}).items():
                if path != GENERIC_CURRENT and fixed_after.get(path) != value:
                    raise CollectorError(f"fixed authority changed during continuation: {path}")
            _validate_continuation_transaction(
                adb, after_current_raw or b"", after_current or {}, current_before_raw or b"", preflight,
                candidate_identity, continuation_contract,
            )
        bundle = {key: value for key, value in first_files.items() if value is not None}
        bundle_after = {key: value for key, value in second_files.items() if value is not None}
        evidence.write_capture("capture-before", first_files)
        evidence.write_capture("capture-after", second_files)
        evidence.write_capture("device-logs-before", first_logs)
        evidence.write_capture("device-logs-after", second_logs)
        evidence.write_capture("history", history_before)
        if receipt is not None:
            _write_once(evidence.root / "prepared.receipt", receipt)
        if first_run is None or second_run is None or first_run_raw != second_run_raw or current_before_raw == after_current_raw:
            raise CollectorError("terminal RUN binding/current evidence is not stable")
        if first_logs != second_logs:
            raise CollectorError("Wine server/client output logs changed during capture")
        qualification = qualify_bundle(bundle, bundle_after, terminal_current_raw, after_current_raw or b"", candidate_run_id, ws_run, nonce, current, after_current or {}, first_run, second_run, prepared_identity, original_binding=binding_raw)
        qualification["outcome"] = "GO"
        qualification["historyStable"] = True
        evidence.finish(qualification)
        return 0
    except (CollectorError, OSError, ValueError, KeyError) as exc:
        intent_unknown = intent_unknown or any(
            item.get("action") in {"initialActivity", "activity-intent"}
            for item in locals().get("journal", [])
        )
        for item in locals().get("journal", []):
            evidence.action(str(item.pop("action")), **item)
        if "journal" in locals():
            journal.clear()
        evidence.action("collector-failure", error=re.sub(r"/(?:[^\s/]+/)+[^\s/]*", "<approved-path>", str(exc).replace("\n", " "))[:512])
        if intent_unknown or intent_sent:
            adb.deadline = time.monotonic() + CLEANUP_SECONDS
            captured_any, capture_incomplete = _capture_failure_evidence(
                adb, evidence, candidate_run_id, ws_run, candidate_raw,
            )
            if captured_any:
                evidence.action("evidence-captured-before-cleanup")
            if capture_incomplete:
                evidence.action("evidence-capture-incomplete")
            # Cleanup is bounded and package-owned only; it is never converted
            # into closure proof and is deliberately after evidence capture.
            try:
                remaining = max(1.0, min(CLEANUP_SECONDS, (adb.deadline or time.monotonic() + CLEANUP_SECONDS) - time.monotonic()))
                adb.deadline = time.monotonic() + remaining
                adb.shell("am", "force-stop", PACKAGE, timeout=min(10, remaining))
                evidence.action("owned-package-force-stop-after-ambiguous-intent")
            except Exception:
                evidence.action("owned-package-force-stop-failed")
        evidence.finish(_marker_result("UNKNOWN", str(exc)[:512]))
        return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--audit", required=True, type=Path)
    parser.add_argument("--expected-apk-sha256", required=True)
    parser.add_argument("--preflight-directory", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    try:
        return collect(build_parser().parse_args(argv))
    except (CollectorError, OSError, ValueError) as exc:
        print(f"wine60 collector: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
