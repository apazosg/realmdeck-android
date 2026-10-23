#!/usr/bin/env python3
"""Run exactly one pinned native per-RUN subreaper capacity observation.

This module has no import-time device or filesystem side effects.  The only
device operation performed by ``main`` is against the fixed approved package
and serial, after the supplied reboot preflight and all immutable pins pass.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shlex
import stat
import struct
import subprocess
import sys
import threading
import time
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
MODEL = "22041216G"
MANUFACTURER = "Xiaomi"
DEVICE = "xaga"
API_LEVEL = 34
PACKAGE = "com.wowforever.arm64probe"
UID = 10330
VERSION_CODE = 55
APK_SHA256 = "4818835af27aad8c9196af15976d9debeef933193a03f8cb360ceb35bd60d514"
OLD_BOOT_ID = "6147a0fe-087d-43c5-9e66-82206d8995eb"
ARTIFACT_ROOT = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/run-supervisor-capacity-v1")
NATIVE_ARTIFACT = ARTIFACT_ROOT / "RunSupervisorCapacity"
NATIVE_MANIFEST = ARTIFACT_ROOT / "manifest.json"
NATIVE_NAME = "RunSupervisorCapacity"
NATIVE_HOST_MODE = "0755"
NATIVE_DEVICE_MODE = "0700"
NATIVE_SCHEMA = "wowforever.android-run-supervisor/version2"
RUN_RE = re.compile(r"^RS-[0-9a-f]{32}$")
BOOT_RE = re.compile(r"^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
NATIVE_STREAM_LIMIT = 64 * 1024
NATIVE_LINE_LIMIT = 64 * 1024
NATIVE_DEADLINE_SECONDS = 15
CLEANUP_DEADLINE_SECONDS = 5
PRIVATE_PREFIX = "files/forever/controls/run-supervisor"

# Filled by the offline build recipe; keeping these pins in the runner makes
# an artifact replacement fail closed even when its manifest is also replaced.
NATIVE_BYTES = 32144
NATIVE_SHA256 = "519bf45e260940eda6f5a8611b182bffec65e95f57c8840795b4c074b2745761"
NATIVE_MANIFEST_SHA256 = "a6b8683e999e54abbc000371f82845f5b3a96659496da6c9046e20c1917fc92c"
COMPILER_BYTES = 174
COMPILER_SHA256 = "6d5257b2dc2953a1dff0dc1902f473dc75de0409204dfa0086c7c72249bdc326"
ACTUAL_COMPILER_BYTES = 97704928
ACTUAL_COMPILER_SHA256 = "f62d877b60e6428c42a304508cfb57990b947b604c70f026384571dd8ffa98c7"
SOURCE_PINS: dict[str, str] = {
    "run_supervisor_capacity.c": "c284a1b359065ba3be9407360d879bf4c03cc22af21bbf98f954fd434ebc406b",
    "build_run_supervisor_capacity.sh": "040eed58ee93748b883e54ce7c89799a71b18f5ff484b6189ce5f0d622bc2718",
    "README-run-supervisor.md": "1b58b95522a51cc9d9ddd76b56a8dae90941fccfab732c58b793d0f7e1bedc00",
}


def unique_json_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON field: {key}")
        result[key] = value
    return result


def _regular_nosymlink(path: Path) -> os.stat_result:
    info = path.lstat()
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        raise ValueError(f"not a regular non-symlink file: {path}")
    return info


def _sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def _fixed_hash(value: object, label: str) -> str:
    if not isinstance(value, str) or not HEX64.fullmatch(value) or value == "0" * 64:
        raise ValueError(f"{label} pin is absent or invalid")
    return value


def _validate_sources() -> dict[str, dict[str, Any]]:
    expected = {"run_supervisor_capacity.c", "build_run_supervisor_capacity.sh", "README-run-supervisor.md"}
    if set(SOURCE_PINS) != expected:
        raise ValueError("source pin set is not closed")
    result: dict[str, dict[str, Any]] = {}
    source_root = Path(__file__).parent
    for name, expected_hash in SOURCE_PINS.items():
        _fixed_hash(expected_hash, f"source {name}")
        path = source_root / name
        info = _regular_nosymlink(path)
        if info.st_size > 128 * 1024:
            raise ValueError(f"source exceeds bound: {name}")
        digest = _sha256(path)
        if digest != expected_hash:
            raise ValueError(f"source pin mismatch: {name}")
        result[name] = {"path": str(path), "bytes": info.st_size, "sha256": digest,
                        "mode": format(stat.S_IMODE(info.st_mode), "04o")}
    return result


def _vaddr_to_offset(data: bytes, phdrs: list[tuple[int, int, int, int, int, int, int]], address: int) -> int:
    for p_type, p_offset, p_vaddr, filesz, _memsz, _flags, _align in phdrs:
        if p_type == 1 and p_vaddr <= address < p_vaddr + filesz:
            return p_offset + address - p_vaddr
    raise ValueError("ELF virtual address is not in a LOAD segment")


def _validate_elf(data: bytes) -> None:
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4:6] != b"\x02\x01":
        raise ValueError("artifact is not ELF64 little-endian")
    if struct.unpack_from("<H", data, 16)[0] != 3 or struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError("artifact is not AArch64 ET_DYN")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 54)
    if phentsize < 56 or phnum > 64 or phoff + phentsize * phnum > len(data):
        raise ValueError("ELF program headers are invalid")
    phdrs: list[tuple[int, int, int, int, int, int, int]] = []
    interpreter: bytes | None = None
    dynamic: tuple[int, int] | None = None
    loads: list[int] = []
    for index in range(phnum):
        row = struct.unpack_from("<IIQQQQQQ", data, phoff + index * phentsize)
        p_type, p_flags, p_offset, p_vaddr, _paddr, p_filesz, p_memsz, p_align = row
        if p_offset + p_filesz > len(data):
            raise ValueError("ELF segment exceeds artifact")
        phdrs.append((p_type, p_offset, p_vaddr, p_filesz, p_memsz, p_flags, p_align))
        if p_type == 1:
            loads.append(p_align)
        elif p_type == 3:
            interpreter = data[p_offset:p_offset + p_filesz].split(b"\0", 1)[0]
        elif p_type == 2:
            dynamic = (p_vaddr, p_filesz)
    if interpreter != b"/system/bin/linker64" or not loads or any(item != 0x4000 for item in loads) or dynamic is None:
        raise ValueError("ELF linker or 16KiB LOAD alignment is not fixed")
    dyn_vaddr, dyn_size = dynamic
    dyn_offset = _vaddr_to_offset(data, phdrs, dyn_vaddr)
    if dyn_size % 16 or dyn_offset + dyn_size > len(data):
        raise ValueError("ELF dynamic segment is invalid")
    needed: list[int] = []
    string_address: int | None = None
    string_size: int | None = None
    for offset in range(dyn_offset, dyn_offset + dyn_size, 16):
        tag, value = struct.unpack_from("<QQ", data, offset)
        if tag == 0:
            break
        if tag == 1:
            needed.append(value)
        elif tag == 5:
            string_address = value
        elif tag == 10:
            string_size = value
    if string_address is None or string_size is None:
        raise ValueError("ELF dynamic string table is missing")
    string_offset = _vaddr_to_offset(data, phdrs, string_address)
    names: list[str] = []
    for item in needed:
        if item >= string_size:
            raise ValueError("ELF dependency name is out of bounds")
        end = data.find(b"\0", string_offset + item, string_offset + string_size)
        if end < 0:
            raise ValueError("ELF dependency name is unterminated")
        names.append(data[string_offset + item:end].decode("ascii", "strict"))
    if names != ["libc.so"]:
        raise ValueError("artifact must depend on libc.so only")


def load_native() -> dict[str, Any]:
    sources = _validate_sources()
    artifact_hash = _fixed_hash(NATIVE_SHA256, "native artifact")
    manifest_hash = _fixed_hash(NATIVE_MANIFEST_SHA256, "native manifest")
    if not isinstance(NATIVE_BYTES, int) or not 0 < NATIVE_BYTES <= 64 * 1024:
        raise ValueError("native artifact size pin is absent or invalid")
    info = _regular_nosymlink(NATIVE_ARTIFACT)
    artifact_identity = (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns)
    if format(stat.S_IMODE(info.st_mode), "04o") != NATIVE_HOST_MODE:
        raise ValueError("native artifact mode mismatch")
    data = NATIVE_ARTIFACT.read_bytes()
    if len(data) != NATIVE_BYTES or _sha256(NATIVE_ARTIFACT) != artifact_hash:
        raise ValueError("native artifact hash or size mismatch")
    if (current := _regular_nosymlink(NATIVE_ARTIFACT)) and (current.st_dev, current.st_ino, current.st_size, current.st_mtime_ns) != artifact_identity:
        raise ValueError("native artifact changed during validation")
    _validate_elf(data)
    manifest_info = _regular_nosymlink(NATIVE_MANIFEST)
    manifest_identity = (manifest_info.st_dev, manifest_info.st_ino, manifest_info.st_size, manifest_info.st_mtime_ns)
    manifest_raw = NATIVE_MANIFEST.read_bytes()
    if len(manifest_raw) > 128 * 1024 or _sha256(NATIVE_MANIFEST) != manifest_hash:
        raise ValueError("native manifest hash mismatch")
    document = json.loads(manifest_raw.decode("ascii", "strict"), object_pairs_hook=unique_json_pairs)
    if document.get("schema") != "wowforever.android-run-supervisor-build/version1" or document.get("file") != NATIVE_NAME:
        raise ValueError("native manifest schema mismatch")
    if document.get("size") != NATIVE_BYTES or document.get("sha256") != artifact_hash:
        raise ValueError("native manifest artifact pin mismatch")
    if (document.get("elfType"), document.get("machine"), document.get("pie"), document.get("interpreter"),
            document.get("loadAlignment"), document.get("neededLibraries")) != ("ET_DYN", "AArch64", True,
                                                                                       "/system/bin/linker64", "0x4000", ["libc.so"]):
        raise ValueError("native manifest ELF pin mismatch")
    if document.get("sourceSha256") != {name: item["sha256"] for name, item in sources.items()}:
        raise ValueError("native manifest source pin mismatch")
    if document.get("sourceBytes") != {name: item["bytes"] for name, item in sources.items()}:
        raise ValueError("native manifest source size pin mismatch")
    if (document.get("ndk"), document.get("target"), document.get("compiler"), document.get("compilerBytes"),
            document.get("compilerSha256")) != ("24.0.8215888", "aarch64-linux-android29",
                                                   "llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang",
                                                   COMPILER_BYTES, COMPILER_SHA256):
        raise ValueError("native compiler pin mismatch")
    if (document.get("actualCompiler"), document.get("actualCompilerBytes"), document.get("actualCompilerSha256")) != (
            "llvm/prebuilt/linux-x86_64/bin/clang-14", ACTUAL_COMPILER_BYTES, ACTUAL_COMPILER_SHA256):
        raise ValueError("native invoked compiler pin mismatch")
    if (current := _regular_nosymlink(NATIVE_MANIFEST)) and (current.st_dev, current.st_ino, current.st_size, current.st_mtime_ns) != manifest_identity:
        raise ValueError("native manifest changed during validation")
    return {"path": NATIVE_ARTIFACT, "name": NATIVE_NAME, "bytes": NATIVE_BYTES, "sha256": artifact_hash,
            "mode": NATIVE_DEVICE_MODE, "hostMode": NATIVE_HOST_MODE, "manifest": str(NATIVE_MANIFEST),
            "manifestBytes": len(manifest_raw), "manifestSha256": manifest_hash,
            "manifestMode": format(stat.S_IMODE(manifest_info.st_mode), "04o"), "source": sources,
            "ndk": document.get("ndk"), "target": document.get("target"),
            "compiler": document.get("compiler"), "compilerSha256": document.get("compilerSha256"),
            "compilerBytes": document.get("compilerBytes"), "actualCompiler": document.get("actualCompiler"),
            "actualCompilerSha256": document.get("actualCompilerSha256"),
            "actualCompilerBytes": document.get("actualCompilerBytes")}


def _bounded_text(value: bytes, label: str, limit: int = 64 * 1024) -> str:
    if len(value) > limit:
        raise ValueError(f"{label} exceeds {limit} bytes")
    return value.decode("utf-8", "strict")


def _bounded_reader(pipe: Any, limit: int, box: dict[str, bytes], name: str, overflow: threading.Event) -> None:
    data = bytearray()
    with pipe:
        while True:
            chunk = pipe.read(8192)
            if not chunk:
                break
            if len(data) + len(chunk) > limit:
                overflow.set()
                remaining = max(0, limit - len(data))
                data.extend(chunk[:remaining])
            else:
                data.extend(chunk)
    box[name] = bytes(data)


def _bounded_subprocess(command: list[str], *, timeout: float, input_data: bytes | None = None) -> subprocess.CompletedProcess[bytes]:
    deadline = time.monotonic() + timeout
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    output: dict[str, bytes] = {}
    overflow = threading.Event()
    write_errors: list[str] = []
    threads = [threading.Thread(target=_bounded_reader, args=(pipe, NATIVE_STREAM_LIMIT, output, name, overflow), daemon=True)
               for name, pipe in (("stdout", process.stdout), ("stderr", process.stderr)) if pipe is not None]
    def send_input() -> None:
        try:
            if process.stdin is not None:
                with process.stdin:
                    if input_data:
                        process.stdin.write(input_data)
                        process.stdin.flush()
        except OSError:
            write_errors.append("bounded subprocess input failed")

    threads.append(threading.Thread(target=send_input, daemon=True))
    for thread in threads:
        thread.start()
    timed_out = False
    try:
        while process.poll() is None:
            if overflow.is_set() or time.monotonic() >= deadline:
                timed_out = time.monotonic() >= deadline
                break
            time.sleep(0.01)
    finally:
        cleanup_deadline = time.monotonic() + CLEANUP_DEADLINE_SECONDS
        if process.poll() is None:
            try:
                process.kill()  # only the local subprocess handle
            except OSError:
                pass
        process.wait(timeout=max(0.1, cleanup_deadline - time.monotonic()))
        for thread in threads:
            thread.join(timeout=max(0.0, cleanup_deadline - time.monotonic()))
    if any(thread.is_alive() for thread in threads):
        raise RuntimeError("bounded subprocess output drain did not quiesce")
    if overflow.is_set():
        raise RuntimeError("bounded subprocess output overflow")
    if timed_out:
        raise RuntimeError("bounded subprocess deadline exceeded")
    if write_errors:
        raise RuntimeError(write_errors[0])
    result = subprocess.CompletedProcess(command, process.returncode, output.get("stdout", b""), output.get("stderr", b""))
    if result.returncode != 0 or result.stderr:
        raise RuntimeError(f"ADB failed: {shlex.join(command)}")
    return result


def _adb(*args: str, timeout: float = 15, input_data: bytes | None = None) -> subprocess.CompletedProcess[bytes]:
    command = [ADB, "-s", require_serial(), *args]
    return _bounded_subprocess(command, timeout=timeout, input_data=input_data)


def _text(result: subprocess.CompletedProcess[bytes]) -> str:
    return _bounded_text(result.stdout, "ADB stdout").strip()


def _safe_path(path: Path) -> None:
    if path.is_symlink() or not path.is_dir():
        raise ValueError(f"unsafe preflight/evidence directory: {path}")
    for parent in (path, *path.parents):
        if parent.is_symlink():
            raise ValueError(f"symlink in path: {parent}")


def _preflight_record(directory: Path) -> dict[str, Any]:
    _safe_path(directory)
    candidates = sorted(directory.glob("state-*-READY_FOR_SINGLE_APPROVED_TRIAL.json"))
    if len(candidates) != 1 or candidates[0].is_symlink() or not candidates[0].is_file():
        raise ValueError("preflight must contain exactly one READY state record")
    raw = candidates[0].read_bytes()
    if len(raw) > NATIVE_STREAM_LIMIT:
        raise ValueError("preflight state exceeds bound")
    record = json.loads(raw.decode("utf-8", "strict"), object_pairs_hook=unique_json_pairs)
    if (record.get("result"), record.get("serial"), record.get("package"), record.get("versionCode"),
            record.get("installedApkSha256"), record.get("retainedArtifactsVerified")) != (
                "READY_FOR_SINGLE_APPROVED_TRIAL", SERIAL, PACKAGE, VERSION_CODE, APK_SHA256, True):
        raise ValueError("preflight READY identity or APK pin mismatch")
    before = record.get("bootBeforeReboot")
    after = record.get("bootAfterReboot")
    if not isinstance(before, str) or not BOOT_RE.fullmatch(before) or before == OLD_BOOT_ID:
        raise ValueError("preflight lacks valid pre-reboot boot identity")
    if not isinstance(after, str) or not BOOT_RE.fullmatch(after) or after in {OLD_BOOT_ID, before}:
        raise ValueError("preflight lacks a new post-reboot boot identity")
    snapshot = record.get("ownUidProcessSnapshot")
    if not isinstance(snapshot, str):
        raise ValueError("preflight lacks explicit own-UID evidence")
    rows = snapshot.splitlines()
    if not rows or rows[0].split() != ["UID", "PID", "PPID", "NAME"]:
        raise ValueError("preflight own-UID process header is not exact")
    if any(len(row.split()) != 4 or row.split()[0] != str(UID) or
           any(not field.isdigit() for field in row.split()[1:3]) or row.split()[3] != "ps"
           for row in rows[1:]):
        raise ValueError("preflight own-UID process snapshot contains foreign or malformed rows")
    if len(rows) < 2:
        raise ValueError("preflight own-UID process snapshot has no exact ps row")
    return {"path": str(candidates[0]), "before": before, "after": after,
            "stateSha256": _sha256(candidates[0]), "record": record}


def device_preflight(preflight: dict[str, Any]) -> dict[str, str]:
    if _text(_adb("get-state")) != "device":
        raise RuntimeError("device is not ready")
    properties = {
        "device": _text(_adb("shell", "getprop", "ro.product.device")),
        "model": _text(_adb("shell", "getprop", "ro.product.model")),
        "manufacturer": _text(_adb("shell", "getprop", "ro.product.manufacturer")),
        "api": _text(_adb("shell", "getprop", "ro.build.version.sdk")),
    }
    if properties != {"device": DEVICE, "model": MODEL, "manufacturer": MANUFACTURER, "api": str(API_LEVEL)}:
        raise RuntimeError("fixed device identity mismatch")
    installed = _text(_adb("shell", "pm", "path", PACKAGE))
    if re.fullmatch(r"package:/data/app/[A-Za-z0-9_~+=/.-]+/base\.apk", installed) is None:
        raise RuntimeError("installed package path is not an exact base APK path")
    apk_path = installed.removeprefix("package:")
    apk_hash = _text(_adb("exec-out", "run-as", PACKAGE, "sha256sum", apk_path)).split()
    if len(apk_hash) != 2 or apk_hash[0] != APK_SHA256 or apk_hash[1] != apk_path:
        raise RuntimeError("installed code-55 APK hash mismatch")
    if _text(_adb("exec-out", "run-as", PACKAGE, "id", "-u")) != str(UID):
        raise RuntimeError("run-as UID mismatch")
    current_boot = _text(_adb("exec-out", "run-as", PACKAGE, "cat", "/proc/sys/kernel/random/boot_id"))
    if not BOOT_RE.fullmatch(current_boot) or current_boot != preflight["after"]:
        raise RuntimeError("current boot identity does not match validated reboot")
    return {**properties, "apkPath": apk_path, "apkSha256": apk_hash[0], "bootId": current_boot}


def _private_relative(run: str) -> str:
    if RUN_RE.fullmatch(run) is None:
        raise ValueError("invalid run id")
    return f"{PRIVATE_PREFIX}/{run}"


def _q(value: str) -> str:
    return shlex.quote(value)


def _private_root(app_root: str, run: str) -> str:
    if app_root not in {f"/data/user/0/{PACKAGE}", f"/data/data/{PACKAGE}"}:
        raise ValueError("run-as root is not the approved package root")
    return f"{app_root}/{_private_relative(run)}"


def upload_native(native: dict[str, Any], run: str, app_root: str) -> dict[str, Any]:
    relative = _private_relative(run)
    destination = f"{relative}/{NATIVE_NAME}"
    script = "set -eu; umask 077; set -C; " + "; ".join(
        f"test ! -L {_q(directory)} && (test -d {_q(directory)} || mkdir -m 700 {_q(directory)})"
        for directory in ("files", "files/forever", "files/forever/controls", PRIVATE_PREFIX)
    ) + f"; test ! -e {_q(relative)} && test ! -L {_q(relative)} && mkdir -m 700 {_q(relative)}; " \
        f"test ! -e {_q(destination)} && test ! -L {_q(destination)}; cat > {_q(destination)}; chmod 700 {_q(destination)}; printf RUN_SUPERVISOR_UPLOAD_OK"
    payload = Path(native["path"]).read_bytes()
    if len(payload) != native["bytes"] or hashlib.sha256(payload).hexdigest() != native["sha256"]:
        raise RuntimeError("host payload changed before private upload")
    uploaded = _adb("shell", "-T", "run-as", PACKAGE, "/system/bin/sh", "-c", _q(script),
                    timeout=30, input_data=payload)
    if uploaded.stdout != b"RUN_SUPERVISOR_UPLOAD_OK" or uploaded.stderr:
        raise RuntimeError("private upload sentinel was not exact")
    hashed = _text(_adb("exec-out", "run-as", PACKAGE, "sha256sum", destination)).split()
    if hashed != [native["sha256"], destination]:
        raise RuntimeError("private device artifact hash mismatch")
    def device_stat() -> tuple[str, ...]:
        fields = _text(_adb("exec-out", "run-as", PACKAGE, "stat", "-c", "%F %u %a %s %h %d %i", destination)).split()
        if len(fields) != 8 or fields[0:3] != ["regular", "file", str(UID)] or fields[5] != "1":
            raise RuntimeError("private device artifact is not a regular single-link owned file")
        if fields[3] != "700" or fields[4] != str(native["bytes"]):
            raise RuntimeError("private device artifact mode/size mismatch")
        return tuple(fields)

    before = device_stat()
    hashed = _text(_adb("exec-out", "run-as", PACKAGE, "sha256sum", destination)).split()
    after = device_stat()
    if hashed != [native["sha256"], destination] or before != after:
        raise RuntimeError("private device artifact identity/hash changed during verification")
    return {"relative": relative, "destination": destination, "root": _private_root(app_root, run),
            "sha256": native["sha256"], "bytes": native["bytes"], "mode": NATIVE_DEVICE_MODE,
            "stat": {"type": before[0] + " " + before[1], "uid": int(before[2]), "mode": before[3],
                     "bytes": int(before[4]), "nlink": int(before[5]), "device": int(before[6]),
                     "inode": int(before[7])}}


def shell_command(private_root: str, run: str) -> list[str]:
    if private_root != _private_root(private_root.split("/files/", 1)[0], run):
        raise ValueError("unapproved private root")
    binary = f"{private_root}/{NATIVE_NAME}"
    script = f"cd {_q(private_root)} && exec /system/bin/env -i PATH=/system/bin ANDROID_ROOT=/system ANDROID_DATA=/data TMPDIR={_q(private_root)} /system/bin/linker64 {_q(binary)} --run-id {_q(run)}"
    return [ADB, "-s", SERIAL, "shell", "-T", "run-as", PACKAGE, "/system/bin/sh", "-c", _q(script)]


def _drain(name: str, pipe: Any, result: dict[str, Any], overflow: threading.Event) -> None:
    data = bytearray()
    total = 0
    with pipe:
        while True:
            chunk = pipe.read(8192)
            if not chunk:
                break
            total += len(chunk)
            if len(data) < NATIVE_STREAM_LIMIT:
                data.extend(chunk[:NATIVE_STREAM_LIMIT - len(data)])
            if total > NATIVE_STREAM_LIMIT:
                overflow.set()
    result[name] = {"data": bytes(data), "total": total, "truncated": total > NATIVE_STREAM_LIMIT}


def _identity(value: object, label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != {"pid", "ppid", "uid", "euid", "suid", "fsuid", "startTicks"}:
        raise ValueError(f"{label} identity shape mismatch")
    if any(type(value[key]) is not int for key in value) or any(value[key] <= 0 for key in value):
        raise ValueError(f"{label} identity values invalid")
    if any(value[key] != UID for key in ("uid", "euid", "suid", "fsuid")):
        raise ValueError(f"{label} UID mismatch")
    return value


def parse_native(raw: bytes, run: str, adb_exit: int | None = None) -> dict[str, Any]:
    if not raw or len(raw) > NATIVE_STREAM_LIMIT or not raw.endswith(b"\n") or raw.count(b"\n") > 16:
        raise ValueError("native output exceeds bounded JSONL shape")
    lines = raw.splitlines()
    documents: list[dict[str, Any]] = []
    for line in lines:
        if len(line) > NATIVE_LINE_LIMIT:
            raise ValueError("native JSON line exceeds bound")
        document = json.loads(line.decode("utf-8", "strict"), object_pairs_hook=unique_json_pairs)
        if not isinstance(document, dict):
            raise ValueError("native JSON line is not an object")
        documents.append(document)
    events = [item.get("event") for item in documents]
    if not events or events[-1] != "result" or events[0] != "root":
        raise ValueError("native JSONL event order is incomplete or contradictory")
    ordered_events = ["root", "witness_before", "supervisor", "normal_birth", "normal_adoption", "cleanup_birth", "cleanup_adoption", "witness_exit", "result"]
    if len(events) != len(set(events)) or any(event not in ordered_events for event in events) or events != sorted(events, key=ordered_events.index):
        raise ValueError("native JSONL event order is incomplete or contradictory")
    event_keys = {"schema", "runId", "event", "pid", "ppid", "uid", "euid", "suid", "fsuid", "startTicks"}
    event_values: dict[str, dict[str, Any]] = {}
    for document in documents[:-1]:
        event = document["event"]
        if event == "witness_exit":
            if set(document) != {"schema", "runId", "event", "pid", "startTicks", "exitCode", "reaped"} or document["schema"] != NATIVE_SCHEMA or document["runId"] != run:
                raise ValueError("witness exit schema mismatch")
            if any(type(document[key]) is not int for key in ("pid", "startTicks", "exitCode")) or type(document["reaped"]) is not bool:
                raise ValueError("witness exit field types invalid")
            continue
        if set(document) != event_keys or document["schema"] != NATIVE_SCHEMA or document["runId"] != run:
            raise ValueError(f"{event} event schema or run identity mismatch")
        event_values[event] = _identity({key: document[key] for key in event_keys - {"schema", "runId", "event"}}, event)
    result = documents[-1]
    result_keys = {"schema", "runId", "event", "status", "failureStage", "scope", "rootSetResult", "rootGetResult", "rootGetValue",
                   "supervisorSetResult", "supervisorGetResult", "supervisorGetValue", "waitpidEchild", "rootWaitpidEchild",
                   "cleanupConfirmed", "witnessStable", "supervisorExit", "nativeExit", "normalIntermediateExit",
                   "normalGrandchildExit", "cleanupIntermediateExit", "cleanupGrandchildExit"}
    for prefix in ("root", "supervisor", "witness", "normalIntermediate", "normalBirth", "normalAdoption",
                   "cleanupIntermediate", "cleanupBirth", "cleanupAdoption"):
        result_keys.update({f"{prefix}{suffix}" for suffix in ("Pid", "Ppid", "Uid", "Euid", "Suid", "Fsuid", "StartTicks")})
    if set(result) != result_keys or result["schema"] != NATIVE_SCHEMA or result["runId"] != run or result["event"] != "result":
        raise ValueError("native result schema mismatch")
    if result["scope"] != "orchestrator-only-safety-net":
        raise ValueError("native safety-net scope is not explicit")
    for key in ("rootSetResult", "rootGetResult", "rootGetValue", "supervisorSetResult", "supervisorGetResult", "supervisorGetValue",
                "normalIntermediateExit", "normalGrandchildExit", "cleanupIntermediateExit", "cleanupGrandchildExit", "supervisorExit", "nativeExit"):
        if type(result[key]) is not int:
            raise ValueError(f"native result integer field invalid: {key}")
    for key in ("waitpidEchild", "rootWaitpidEchild", "cleanupConfirmed", "witnessStable"):
        if type(result[key]) is not bool:
            raise ValueError(f"native result boolean invalid: {key}")
    if result["status"] not in {"PASS", "FAIL", "UNKNOWN"} or not isinstance(result["failureStage"], str) or len(result["failureStage"]) > 64:
        raise ValueError("native result status invalid")
    required_prefixes = ("root",)
    optional_prefixes = ("supervisor", "witness", "normalIntermediate", "normalBirth", "normalAdoption", "cleanupIntermediate", "cleanupBirth", "cleanupAdoption")
    def result_identity(prefix: str, required: bool) -> dict[str, Any] | None:
        value = {"pid": result[f"{prefix}Pid"], "ppid": result[f"{prefix}Ppid"], "uid": result[f"{prefix}Uid"],
                 "euid": result[f"{prefix}Euid"], "suid": result[f"{prefix}Suid"], "fsuid": result[f"{prefix}Fsuid"],
                 "startTicks": result[f"{prefix}StartTicks"]}
        if any(type(item) is not int for item in value.values()):
            raise ValueError(f"native result {prefix} identity type invalid")
        if not required and value["pid"] <= 0:
            if value != {key: 0 for key in value} and value != {**{key: 0 for key in value}, "pid": -1}:
                raise ValueError(f"native unavailable {prefix} identity is not zeroed")
            return None
        return _identity(value, prefix)
    final_identities = {prefix: result_identity(prefix, True) for prefix in required_prefixes}
    optional_identities = {prefix: result_identity(prefix, False) for prefix in optional_prefixes}
    for event, prefix in (("root", "root"), ("supervisor", "supervisor"), ("witness_before", "witness"),
                          ("normal_birth", "normalBirth"), ("normal_adoption", "normalAdoption"),
                          ("cleanup_birth", "cleanupBirth"), ("cleanup_adoption", "cleanupAdoption")):
        if event in event_values:
            actual = event_values[event]
            expected = final_identities.get(prefix) or optional_identities.get(prefix)
            if expected is None or any(actual[key] != expected[key] for key in actual):
                raise ValueError(f"{event} does not cross-link to final identity")
    root = final_identities["root"]
    supervisor = optional_identities["supervisor"]
    witness = optional_identities["witness"]
    witness_exit = next((item for item in documents if item["event"] == "witness_exit"), None)
    if witness_exit is not None and (witness is None or witness_exit["pid"] != witness["pid"] or witness_exit["startTicks"] != witness["startTicks"]):
        raise ValueError("witness exit does not cross-link to held child")
    if (witness is not None and witness["ppid"] != root["pid"]) or (supervisor is not None and supervisor["ppid"] != root["pid"]):
        raise ValueError("witness/supervisor PPID is not the root orchestrator")
    for family in ("normal", "cleanup"):
        intermediate = optional_identities[family + "Intermediate"]
        birth = optional_identities[family + "Birth"]
        adoption = optional_identities[family + "Adoption"]
        if intermediate is not None and (supervisor is None or intermediate["ppid"] != supervisor["pid"]):
            raise ValueError(f"{family} intermediate PPID is not the supervisor")
        if birth is not None and (intermediate is None or birth["ppid"] != intermediate["pid"]):
            raise ValueError(f"{family} birth PPID is not its tracked intermediate")
        if adoption is not None:
            if supervisor is None or adoption["ppid"] != supervisor["pid"]:
                raise ValueError(f"{family} adoption PPID is not the supervisor")
            if birth is None or any(birth[key] != adoption[key] for key in ("pid", "uid", "euid", "suid", "fsuid", "startTicks")):
                raise ValueError(f"{family} birth/adoption process identity changed")
    if result["status"] == "PASS":
        if events != ordered_events:
            raise ValueError("native PASS event order is incomplete")
        if witness_exit is None or witness_exit["exitCode"] != 0 or not witness_exit["reaped"]:
            raise ValueError("native PASS witness exit is not confirmed normal")
        distinct = [root["pid"], supervisor["pid"] if supervisor else None, witness["pid"] if witness else None]
        distinct += [optional_identities[prefix]["pid"] if optional_identities[prefix] else None for prefix in ("normalIntermediate", "normalBirth", "cleanupIntermediate", "cleanupBirth")]
        if None in distinct or len(set(distinct)) != len(distinct):
            raise ValueError("native PASS process roles are not distinct")
        if (result["nativeExit"], result["supervisorExit"], result["rootSetResult"], result["rootGetResult"], result["rootGetValue"],
            result["supervisorSetResult"], result["supervisorGetResult"], result["supervisorGetValue"], result["normalIntermediateExit"],
            result["normalGrandchildExit"], result["cleanupIntermediateExit"], result["cleanupGrandchildExit"]) != (0, 0, 0, 0, 1, 0, 0, 1, 41, 42, 43, 143):
            raise ValueError("native PASS has contradictory control or exit facts")
        if supervisor is None or witness is None or not all(optional_identities[prefix] is not None for prefix in optional_prefixes[2:]) or not all(result[key] for key in ("waitpidEchild", "rootWaitpidEchild", "cleanupConfirmed", "witnessStable")) or result["failureStage"] != "none":
            raise ValueError("native PASS is missing a required fact")
    elif result["status"] == "UNKNOWN":
        if result["nativeExit"] != 2 or result["failureStage"] not in {"timeout", "kill", "ipc", "identity", "reap"}:
            raise ValueError("native UNKNOWN is contradictory")
    elif result["nativeExit"] != 1 or result["failureStage"] == "none":
        raise ValueError("native FAIL is contradictory")
    if adb_exit is not None and adb_exit != result["nativeExit"]:
        raise ValueError("ADB exit does not match nativeExit")
    return result


def _exclusive_write(path: Path, data: bytes) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    fd = os.open(path, flags, 0o600)
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


def _external_parent(path: Path) -> Path:
    _safe_path(path)
    repo = Path(__file__).resolve().parents[3]
    if path.resolve().is_relative_to(repo):
        raise ValueError("evidence parent must be outside the repository")
    return path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preflight-directory", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="existing external evidence parent")
    args = parser.parse_args(argv)
    parent = _external_parent(args.output)
    native = load_native()
    preflight = _preflight_record(args.preflight_directory)
    device = device_preflight(preflight)
    run = "RS-" + uuid.uuid4().hex
    app_root = _text(_adb("exec-out", "run-as", PACKAGE, "pwd"))
    _private_root(app_root, run)
    if _text(_adb("exec-out", "run-as", PACKAGE, "id", "-u")) != str(UID):
        raise RuntimeError("run-as UID changed before mutation")
    if _text(_adb("exec-out", "run-as", PACKAGE, "cat", "/proc/sys/kernel/random/boot_id")) != device["bootId"]:
        raise RuntimeError("boot identity changed before mutation")
    out = parent / f"native-{run}"
    out.mkdir(mode=0o700)
    capture = {"schema": 1, "runId": run, "serial": SERIAL, "package": PACKAGE, "uid": UID,
               "versionCode": VERSION_CODE, "device": device, "preflight": preflight,
               "artifact": native, "nativeSchema": NATIVE_SCHEMA, "privateRelative": _private_relative(run),
               "executionState": "pre-exec-capture"}
    _exclusive_write(out / "capture.json", (json.dumps(capture, indent=2, sort_keys=True, default=str) + "\n").encode())
    _exclusive_write(out / "manifest.json", NATIVE_MANIFEST.read_bytes())
    uploaded = upload_native(native, run, app_root)
    capture["upload"] = uploaded
    command = shell_command(uploaded["root"], run)
    # capture.json is deliberately exclusive and durable before the sole exec.
    _exclusive_write(out / "command.json", (json.dumps({"command": command, "executionState": "intent-persisted"}, indent=2) + "\n").encode())
    _exclusive_write(out / "execution-intent.json", (json.dumps({"runId": run, "command": command,
                                                                    "executionState": "intent-persisted"}, indent=2) + "\n").encode())
    process: subprocess.Popen[bytes] | None = None
    streams: dict[str, Any] = {}
    threads: list[threading.Thread] = []
    overflow = threading.Event()
    timed_out = False
    start_error: str | None = None
    try:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        for name, pipe in (("stdout", process.stdout), ("stderr", process.stderr)):
            if pipe is None:
                raise RuntimeError(f"missing {name} pipe")
            thread = threading.Thread(target=_drain, args=(name, pipe, streams, overflow), daemon=True)
            threads.append(thread)
            thread.start()
        deadline = time.monotonic() + NATIVE_DEADLINE_SECONDS
        while process.poll() is None:
            if overflow.is_set() or time.monotonic() >= deadline:
                timed_out = not overflow.is_set()
                try:
                    process.kill()  # only the held local ADB client
                except OSError:
                    pass
                break
            time.sleep(0.01)
    except Exception as exc:
        start_error = f"{type(exc).__name__}: {exc}"
    cleanup_error: str | None = None
    cleanup_deadline = time.monotonic() + CLEANUP_DEADLINE_SECONDS
    if process is not None:
        if timed_out or process.poll() is None:
            try:
                process.kill()  # only the held local ADB client, never a device PID
            except OSError as exc:
                cleanup_error = str(exc)
        try:
            process.wait(timeout=max(0.1, cleanup_deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            cleanup_error = cleanup_error or "local ADB client did not quiesce"
    for thread in threads:
        thread.join(timeout=max(0.0, cleanup_deadline - time.monotonic()))
    if any(thread.is_alive() for thread in threads):
        cleanup_error = cleanup_error or "output drain did not quiesce"
    values = {name: streams.get(name, {"data": b"", "total": 0, "truncated": False}) for name in ("stdout", "stderr")}
    native_result: dict[str, Any] | None = None
    diagnostic = start_error
    if diagnostic is None and timed_out:
        diagnostic = "native deadline exceeded"
    if diagnostic is None and cleanup_error is not None:
        diagnostic = cleanup_error
    if diagnostic is None and overflow.is_set():
        diagnostic = "native output exceeded bound"
    if diagnostic is None and values["stderr"]["data"]:
        diagnostic = "native stderr was not empty"
    if diagnostic is None and (values["stdout"]["truncated"] or values["stderr"]["truncated"]):
        diagnostic = "native output exceeded bound"
    if diagnostic is None:
        try:
            native_result = parse_native(values["stdout"]["data"], run, process.returncode if process else None)
        except (UnicodeError, ValueError, json.JSONDecodeError) as exc:
            diagnostic = f"native JSON validation failed: {exc}"
    status = native_result["status"] if native_result is not None else "UNKNOWN"
    if diagnostic is not None:
        status = "UNKNOWN"
    summary = {"schema": 1, "runId": run, "status": status, "diagnostic": diagnostic,
               "executionStarted": process is not None, "nativeExit": process.returncode if process else None, "timedOut": timed_out,
               "startError": start_error, "cleanupError": cleanup_error, "native": native_result,
               "stdoutBytes": values["stdout"]["total"], "stderrBytes": values["stderr"]["total"],
               "preflight": preflight, "artifact": native, "upload": uploaded, "output": str(out)}
    _exclusive_write(out / "stdout.jsonl", values["stdout"]["data"])
    _exclusive_write(out / "stderr.txt", values["stderr"]["data"])
    _exclusive_write(out / "execution-complete.json", (json.dumps({"runId": run, "executionStarted": process is not None,
                                                                      "status": status, "diagnostic": diagnostic}, indent=2) + "\n").encode())
    _exclusive_write(out / "result.json", (json.dumps(summary, indent=2, sort_keys=True, default=str) + "\n").encode())
    print(json.dumps(summary, indent=2, sort_keys=True, default=str))
    return 0 if status == "PASS" and diagnostic is None else 1


if __name__ == "__main__":
    raise SystemExit(main())
