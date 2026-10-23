#!/usr/bin/env python3
"""Run the fixed, context-free kbase metadata observation on the approved host.

This runner is intentionally narrower than a GPU qualification.  It uploads one
already-pinned AArch64 ELF, starts it as the package UID, and records the native
one-line metadata result.  It never starts a loader, Vulkan, renderer, game, or
other device process.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
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


_LOADER_PATH = Path(__file__).parents[1] / "android-vtest" / "run_loader_usb.py"
_LOADER_SPEC = importlib.util.spec_from_file_location("android_vtest_loader", _LOADER_PATH)
if _LOADER_SPEC is None or _LOADER_SPEC.loader is None:  # pragma: no cover - packaging error
    raise ImportError(f"cannot load {_LOADER_PATH}")
loader = importlib.util.module_from_spec(_LOADER_SPEC)
# Register the module before execution.  This is required by helpers which use
# standard-library module metadata (and makes this file safe to import in tests).
sys.modules[_LOADER_SPEC.name] = loader
_LOADER_SPEC.loader.exec_module(loader)


ADB = loader.ADB
SERIAL = loader.SERIAL
PACKAGE = loader.PACKAGE
UID = loader.UID
VERSION_CODE = loader.VERSION_CODE

NATIVE_ARTIFACT = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/controls/kbase-metadata/KbaseMetadata"
)
NATIVE_MANIFEST = NATIVE_ARTIFACT.with_name("manifest.json")
NATIVE_NAME = "KbaseMetadata"
NATIVE_ARTIFACT_HOST_MODE = "0755"
NATIVE_DEVICE_MODE = "0700"
NATIVE_ARTIFACT_BYTES = 16696
NATIVE_ARTIFACT_SHA256 = "0714a261d69b54793b7f8018cc8b445016dde7d6fad0e129e9cc7c70fe3cda06"
NATIVE_MANIFEST_SHA256 = "0c83c84fe5ed2db7ede6f30b65c8e00cd76012aeeaa675d600c498b58678ccec"
SOURCE_PINS: dict[str, str] = {
    "KbaseMetadata.c": "e43f462b335e6891c3cb14a494a40daa34cd857ef1b0ca958fb96d751f3367ed",
    "build_kernel_probe.sh": "da3cda73cf7f7c313d98219314c0e6550e50792ca7317bed0f6930f657515f99",
    "README-kernel.md": "6314ab3b09c6834692496a8438f2a6f9644f83a5df769d2c2059002abe7437c0",
}
SOURCE_ROOT = Path(__file__).parent
PROVIDER_REPOSITORY = "https://github.com/zenithblue-oss/panvk-kbase-android"
PROVIDER_COMMIT = "de577411e8f27da9de50aec26a0d8cc43890745e"

NATIVE_SCHEMA = "wowforever.android-kbase-metadata/version1"
RUN_PREFIX = "PVK-"
RUN_RE = re.compile(r"^PVK-[0-9a-f]{32}$")
STAGE_PREFIX = "/data/local/tmp/wowforever-kbase-"
NATIVE_STDOUT_LIMIT = 16 * 1024
NATIVE_STDERR_LIMIT = 64 * 1024
NATIVE_DEADLINE_SECONDS = 10
CLEANUP_DEADLINE_SECONDS = 5
MAX_NATIVE_LINE = 8192
MAX_PROPERTIES = 16384
EXPECTED_VERSION_IOCTL = "0xc0048034"
EXPECTED_PROPERTIES_IOCTL = "0x40108003"
EXPECTED_DEVICE = "/dev/mali0"
EXPECTED_MAJOR = 10
EXPECTED_MINOR = 113
VALID_STATUSES = {
    "UNKNOWN",
    "OPEN_FAILED",
    "VERSION_FAILED",
    "VERSION_OBSERVED",
    "PROPERTIES_BLOCKED",
    "PROPERTIES_OBSERVED",
}
COMPLETE_STATUSES = {"VERSION_OBSERVED", "PROPERTIES_OBSERVED"}
OBSERVED_STATUSES = COMPLETE_STATUSES | {"PROPERTIES_BLOCKED"}
CONTROLLED_FAILURES = {"OPEN_FAILED", "VERSION_FAILED"}
FALSE_FLAGS = (
    "flagsInited",
    "gpuWorkloadVerified",
    "driverExecution",
    "deviceVerified",
    "hardwareVerified",
)
TOP_KEYS = {
    "schema", "runId", "pid", "uid", "pgid", "sid", "cwd", "time", "status", "error",
    "device", "version", "properties", *FALSE_FLAGS, "cleanup", "nativeExit",
}
TIME_KEYS = {"startedNs", "finishedNs", "durationNs"}
ERROR_KEYS = {"stage", "errno", "ret"}
DEVICE_KEYS = {"path", "major", "minor", "identityBefore", "identityAfter"}
VERSION_KEYS = {"called", "ioctl", "ret", "errno", "major", "minor"}
PROPERTY_KEYS = {
    "probeCalled", "probeRet", "probeErrno", "fillCalled", "fillRet", "fillErrno",
    "returnedBytes", "gpuIDKnown", "textureflags", "valid",
}
PROPERTY_KEYS_WITH_GPU_ID = PROPERTY_KEYS | {"gpuIDhex"}
CLEANUP_KEYS = {"closed", "closeRet", "closeErrno"}
ERROR_STAGES = {
    "none", "scope", "lstat", "lstat_identity", "open", "deadline_open", "fstat_before",
    "identity_before", "deadline_before_version", "deadline_after_version", "version",
    "deadline_before_properties", "deadline_after_properties_probe", "properties_probe",
    "properties_size", "properties_alloc", "deadline_before_properties_fill",
    "deadline_after_properties_fill", "properties_fill", "properties_bounds", "properties_malformed",
    "fstat_after", "identity_after", "close",
}
HEX8 = re.compile(r"^0x[0-9a-f]{8}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")


def unique_json_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON field: {key}")
        result[key] = value
    return result


def _regular_nosymlink(path: Path) -> os.stat_result:
    info = path.lstat()
    if stat.S_ISLNK(info.st_mode):
        raise ValueError(f"symlink is not an approved artifact: {path}")
    if not stat.S_ISREG(info.st_mode):
        raise ValueError(f"not a regular artifact: {path}")
    return info


def _fixed_hash(value: object, label: str) -> str:
    if not isinstance(value, str) or not HEX64.fullmatch(value) or value == "0" * 64:
        raise ValueError(f"{label} pin is absent or invalid")
    return value


def _source_pins() -> dict[str, str]:
    if set(SOURCE_PINS) != {"KbaseMetadata.c", "build_kernel_probe.sh", "README-kernel.md"}:
        raise ValueError("source pin set is not closed")
    return {name: _fixed_hash(value, f"source {name}") for name, value in SOURCE_PINS.items()}


def _validate_sources() -> dict[str, dict[str, Any]]:
    pins = _source_pins()
    result: dict[str, dict[str, Any]] = {}
    for name, digest in pins.items():
        path = SOURCE_ROOT / name
        info = _regular_nosymlink(path)
        if info.st_size > 128 * 1024:
            raise ValueError(f"source exceeds bound: {name}")
        with path.open("rb") as stream:
            data = stream.read(128 * 1024 + 1)
        if len(data) > 128 * 1024:
            raise ValueError(f"source exceeds bound: {name}")
        actual = hashlib.sha256(data).hexdigest()
        if actual != digest:
            raise ValueError(f"source pin mismatch: {name}")
        result[name] = {"path": str(path), "bytes": len(data), "sha256": actual,
                        "mode": format(stat.S_IMODE(info.st_mode), "04o")}
    return result


def _vaddr_to_offset(data: bytes, phdrs: list[tuple[int, int, int, int, int, int, int]], address: int) -> int:
    for p_type, p_offset, p_vaddr, p_filesz, _p_memsz, _p_flags, _p_align in phdrs:
        if p_type == 1 and p_vaddr <= address < p_vaddr + p_filesz:
            offset = p_offset + address - p_vaddr
            if offset < len(data):
                return offset
    raise ValueError("ELF dynamic address is outside loadable segments")


def _validate_elf(data: bytes) -> None:
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4:6] != b"\x02\x01":
        raise ValueError("native artifact is not ELF64 little-endian")
    if struct.unpack_from("<H", data, 16)[0] != 3 or struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError("native artifact is not AArch64 ET_DYN")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 54)
    if phentsize < 56 or phnum > 64 or phoff + phentsize * phnum > len(data):
        raise ValueError("native ELF program headers are invalid")
    phdrs: list[tuple[int, int, int, int, int, int, int]] = []
    interpreter: bytes | None = None
    dynamic: tuple[int, int] | None = None
    for index in range(phnum):
        row = struct.unpack_from("<IIQQQQQQ", data, phoff + index * phentsize)
        p_type, p_flags, p_offset, p_vaddr, _p_paddr, p_filesz, p_memsz, p_align = row
        if p_offset + p_filesz > len(data):
            raise ValueError("native ELF segment exceeds artifact")
        phdrs.append((p_type, p_offset, p_vaddr, p_filesz, p_memsz, p_flags, p_align))
        if p_type == 3:
            interpreter = data[p_offset:p_offset + p_filesz].split(b"\0", 1)[0]
        if p_type == 2:
            dynamic = (p_vaddr, p_filesz)
    if interpreter != b"/system/bin/linker64" or dynamic is None:
        raise ValueError("native artifact linker or dynamic segment is not fixed")
    dyn_vaddr, dyn_size = dynamic
    dyn_offset = _vaddr_to_offset(data, phdrs, dyn_vaddr)
    if dyn_size % 16 or dyn_offset + dyn_size > len(data):
        raise ValueError("native dynamic segment is invalid")
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
    if string_address is None or string_size is None or string_size > len(data):
        raise ValueError("native dynamic string table is invalid")
    strings_offset = _vaddr_to_offset(data, phdrs, string_address)
    if strings_offset + string_size > len(data):
        raise ValueError("native dynamic string table exceeds artifact")
    names = []
    for item in needed:
        if item >= string_size:
            raise ValueError("native dependency name is outside string table")
        end = data.find(b"\0", strings_offset + item, strings_offset + string_size)
        if end < 0:
            raise ValueError("native dependency name is unterminated")
        names.append(data[strings_offset + item:end].decode("ascii", "strict"))
    if names != ["libc.so"]:
        raise ValueError("native artifact must depend on libc.so only")


def load_native() -> dict[str, Any]:
    """Pin source, manifest, ELF shape, host mode, size, and artifact bytes."""
    sources = _validate_sources()
    artifact_digest = _fixed_hash(NATIVE_ARTIFACT_SHA256, "native artifact")
    manifest_digest = _fixed_hash(NATIVE_MANIFEST_SHA256, "native manifest")
    if not isinstance(NATIVE_ARTIFACT_BYTES, int) or isinstance(NATIVE_ARTIFACT_BYTES, bool) or not 0 < NATIVE_ARTIFACT_BYTES <= 64 * 1024:
        raise ValueError("native artifact size pin is absent or invalid")
    info = _regular_nosymlink(NATIVE_ARTIFACT)
    if format(stat.S_IMODE(info.st_mode), "04o") != NATIVE_ARTIFACT_HOST_MODE:
        raise ValueError("native artifact host mode mismatch")
    data = NATIVE_ARTIFACT.read_bytes()
    if len(data) != NATIVE_ARTIFACT_BYTES or hashlib.sha256(data).hexdigest() != artifact_digest:
        raise ValueError("native artifact hash or size mismatch")
    _validate_elf(data)
    manifest_info = _regular_nosymlink(NATIVE_MANIFEST)
    if manifest_info.st_size > 128 * 1024:
        raise ValueError("native manifest exceeds bound")
    with NATIVE_MANIFEST.open("rb") as stream:
        manifest_raw = stream.read(128 * 1024 + 1)
    if hashlib.sha256(manifest_raw).hexdigest() != manifest_digest or len(manifest_raw) > 128 * 1024:
        raise ValueError("native manifest hash mismatch")
    document = json.loads(manifest_raw.decode("ascii"), object_pairs_hook=unique_json_pairs)
    if not isinstance(document, dict) or document.get("schema") != 1 or document.get("file") != NATIVE_NAME:
        raise ValueError("native manifest schema mismatch")
    if document.get("size") != NATIVE_ARTIFACT_BYTES or document.get("sha256") != artifact_digest:
        raise ValueError("native manifest artifact pin mismatch")
    if document.get("elfType") != "ET_DYN" or document.get("machine") != 183 or document.get("pie") is not True:
        raise ValueError("native manifest ELF pin mismatch")
    if document.get("interpreter") != "/system/bin/linker64" or document.get("neededLibraries") != ["libc.so"]:
        raise ValueError("native manifest linker/dependency pin mismatch")
    authority = document.get("sourceAuthority")
    if not isinstance(authority, dict) or authority.get("portRepository") != PROVIDER_REPOSITORY or authority.get("portCommit") != PROVIDER_COMMIT or authority.get("sourceHash") != PROVIDER_COMMIT:
        raise ValueError("native provider pin mismatch")
    manifest_sources = document.get("sourceSha256")
    source_digests = {name: record["sha256"] for name, record in sources.items()}
    if manifest_sources != source_digests:
        raise ValueError("native manifest source pin mismatch")
    return {"path": NATIVE_ARTIFACT, "name": NATIVE_NAME, "bytes": len(data),
            "sha256": artifact_digest, "mode": NATIVE_DEVICE_MODE,
            "hostMode": NATIVE_ARTIFACT_HOST_MODE, "manifest": str(NATIVE_MANIFEST),
            "manifestSha256": manifest_digest, "manifestBytes": len(manifest_raw),
            "manifestMode": format(stat.S_IMODE(manifest_info.st_mode), "04o"),
            "source": sources, "provider": {"repository": PROVIDER_REPOSITORY, "commit": PROVIDER_COMMIT}}


def _q(value: str) -> str:
    return shlex.quote(value)


def _private_relative(run: str) -> str:
    if not RUN_RE.fullmatch(run):
        raise ValueError("invalid kbase run id")
    return f"files/forever/controls/panvk-kernel/{run}"


def _copy_script(stage_file: str, relative: str) -> str:
    directories = ["files", "files/forever", "files/forever/controls", "files/forever/controls/panvk-kernel"]
    commands = ["umask 077", "set -e", "set -C"]
    for directory in directories:
        quoted = _q(directory)
        commands.append(f"test ! -L {quoted} && (test -d {quoted} || mkdir -m 700 {quoted})")
    commands.extend([
        f"test ! -e {_q(relative)} && test ! -L {_q(relative)}",
        f"mkdir -m 700 {_q(relative)}",
        f"test ! -e {_q(relative + '/' + NATIVE_NAME)} && test ! -L {_q(relative + '/' + NATIVE_NAME)}",
        f"cat {_q(stage_file)} > {_q(relative + '/' + NATIVE_NAME)}",
        f"chmod {NATIVE_DEVICE_MODE} {_q(relative + '/' + NATIVE_NAME)}",
        "printf KBASE_UPLOAD_OK",
    ])
    return "; ".join(commands)


def copy_native(native: dict[str, Any], run: str) -> tuple[str, dict[str, Any]]:
    relative = _private_relative(run)
    stage = STAGE_PREFIX + uuid.uuid4().hex
    stage_file = f"{stage}/{NATIVE_NAME}"
    stage_created = False
    cleanup_failures: list[str] = []
    try:
        loader.require_adb(loader.adb("shell", "test", "-d", "/data/local/tmp"), "staging parent")
        loader.require_adb(loader.adb("shell", "mkdir", "-m", "755", stage), "exclusive staging directory")
        stage_created = True
        loader.require_adb(loader.adb("push", str(native["path"]), stage_file, timeout=30), "native sync upload")
        loader.require_adb(loader.adb("shell", "chmod", "0644", stage_file), "native staging mode")
        uploaded = loader.adb("exec-out", "run-as", PACKAGE, "/system/bin/sh", "-c",
                              _copy_script(stage_file, relative), timeout=30)
        if uploaded.returncode or uploaded.stdout != b"KBASE_UPLOAD_OK" or uploaded.stderr:
            raise RuntimeError("private native upload sentinel was not exact")
        destination = f"{relative}/{NATIVE_NAME}"
        hashed = loader.require_adb(loader.adb("exec-out", "run-as", PACKAGE, "sha256sum", destination),
                                    "device native hash", limit=4096).decode("ascii", "strict").split()
        if not hashed or hashed[0] != native["sha256"]:
            raise RuntimeError("device native artifact hash mismatch")
        mode = loader.require_adb(loader.adb("exec-out", "run-as", PACKAGE, "stat", "-c", "%a", destination),
                                  "device native mode", limit=4096).strip()
        if mode != b"700":
            raise RuntimeError("device native artifact mode mismatch")
        return relative, {"path": stage, "file": stage_file, "stagingClean": True, "failures": []}
    finally:
        if stage_created:
            try:
                result = loader.adb("shell", "rm", "-f", stage_file)
                if result.returncode or result.stdout or result.stderr:
                    cleanup_failures.append("staging-file")
            except Exception:
                cleanup_failures.append("staging-file")
            try:
                result = loader.adb("shell", "rmdir", stage)
                if result.returncode or result.stdout or result.stderr:
                    cleanup_failures.append("staging-directory")
            except Exception:
                cleanup_failures.append("staging-directory")
        if cleanup_failures:
            # The caller must stop before Popen if ownership of the public stage
            # was not completely cleaned up.
            raise RuntimeError("owned kbase staging cleanup failed; no payload executed: " + json.dumps({"path": stage, "failures": cleanup_failures}))


def shell_command(private_root: str, run: str) -> list[str]:
    relative = private_root.rsplit("/files/forever/controls/panvk-kernel/", 1)
    if len(relative) != 2 or not RUN_RE.fullmatch(relative[1]):
        raise ValueError("unapproved private kbase working directory")
    if not private_root.startswith((f"/data/user/0/{PACKAGE}/", f"/data/data/{PACKAGE}/")):
        raise ValueError("unapproved private app root")
    if relative[1] != run:
        raise ValueError("private run identity mismatch")
    root = _q(private_root)
    env = " ".join(_q(value) for value in (
        "PATH=/system/bin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
        f"LD_LIBRARY_PATH={private_root}:/system/lib64", f"TMPDIR={private_root}",
    ))
    binary = _q(f"{private_root}/{NATIVE_NAME}")
    script = f"cd {root} && exec /system/bin/env -i {env} /system/bin/linker64 {binary} --run-id {_q(run)}"
    return [ADB, "-s", SERIAL, "shell", "-T", "run-as", PACKAGE, "/system/bin/sh", "-c", _q(script)]


def _is_int(value: object) -> bool:
    return type(value) is int


def _nullable_int(value: object, *, negative: bool = False) -> None:
    if value is not None and not _is_int(value):
        raise ValueError("nullable native integer has wrong type")
    if negative and value is not None and value > 0:
        raise ValueError("native errno must be zero or negative")


def _bool(value: object, label: str) -> None:
    if type(value) is not bool:
        raise ValueError(f"{label} is not boolean")


def _exact_keys(value: object, keys: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        raise ValueError(f"{label} schema mismatch")
    return value


def parse_native(raw: bytes, run: str, private_root: str) -> dict[str, Any]:
    if not raw or len(raw) > MAX_NATIVE_LINE or not raw.endswith(b"\n") or raw.count(b"\n") != 1:
        raise ValueError("native output is not one bounded JSON line")
    value = json.loads(raw[:-1].decode("utf-8", "strict"), object_pairs_hook=unique_json_pairs)
    top = _exact_keys(value, TOP_KEYS, "native")
    if top["schema"] != NATIVE_SCHEMA or top["runId"] != run or not RUN_RE.fullmatch(top["runId"]):
        raise ValueError("native schema or run id mismatch")
    for name in ("pid", "uid", "pgid", "sid", "nativeExit"):
        if not _is_int(top[name]):
            raise ValueError(f"native {name} is not an integer")
    if top["pid"] <= 0 or top["pgid"] <= 0 or top["sid"] <= 0 or top["uid"] != UID or not 0 <= top["nativeExit"] <= 255:
        raise ValueError("native process identity or exit is invalid")
    approved_roots = {
        f"/data/user/0/{PACKAGE}/{_private_relative(run)}",
        f"/data/data/{PACKAGE}/{_private_relative(run)}",
    }
    if private_root not in approved_roots or top["cwd"] not in approved_roots:
        raise ValueError("native cwd is not the owned private root")
    if top["status"] not in VALID_STATUSES:
        raise ValueError("native status is unknown")
    timing = _exact_keys(top["time"], TIME_KEYS, "native time")
    for name in TIME_KEYS:
        if not _is_int(timing[name]):
            raise ValueError(f"native {name} is not an integer")
    if timing["startedNs"] <= 0 or timing["finishedNs"] <= 0 or timing["finishedNs"] < timing["startedNs"] or not 0 <= timing["durationNs"] <= 5_000_000_000 or timing["durationNs"] != timing["finishedNs"] - timing["startedNs"]:
        raise ValueError("native timing is outside its bound")
    error = _exact_keys(top["error"], ERROR_KEYS, "native error")
    if error["stage"] not in ERROR_STAGES:
        raise ValueError("native error stage is invalid")
    _nullable_int(error["errno"])
    if error["errno"] is not None and error["errno"] >= 0:
        raise ValueError("native error errno must be negative")
    _nullable_int(error["ret"])
    device = _exact_keys(top["device"], DEVICE_KEYS, "native device")
    if device["path"] != EXPECTED_DEVICE or not all(_is_int(device[name]) for name in ("major", "minor")) or device["major"] != EXPECTED_MAJOR or device["minor"] != EXPECTED_MINOR:
        raise ValueError("native device identity is invalid")
    _bool(device["identityBefore"], "device identityBefore")
    _bool(device["identityAfter"], "device identityAfter")
    version = _exact_keys(top["version"], VERSION_KEYS, "native version")
    _bool(version["called"], "version called")
    if version["ioctl"] != EXPECTED_VERSION_IOCTL:
        raise ValueError("version ioctl encoding mismatch")
    _nullable_int(version["ret"])
    _nullable_int(version["errno"], negative=True)
    for name in ("major", "minor"):
        if not _is_int(version[name]) or not 0 <= version[name] <= 65535:
            raise ValueError("version value is not uint16")
    if not isinstance(top["properties"], dict) or set(top["properties"]) not in (PROPERTY_KEYS, PROPERTY_KEYS_WITH_GPU_ID):
        raise ValueError("native properties schema mismatch")
    properties = top["properties"]
    for name in ("probeCalled", "fillCalled", "gpuIDKnown", "valid"):
        _bool(properties[name], f"properties {name}")
    for name in ("probeRet", "probeErrno", "fillRet", "fillErrno"):
        _nullable_int(properties[name], negative=name.endswith("Errno"))
    if not _is_int(properties["returnedBytes"]) or not 0 <= properties["returnedBytes"] <= MAX_PROPERTIES:
        raise ValueError("properties byte count is outside its bound")
    if not isinstance(properties["textureflags"], dict):
        raise ValueError("properties textureflags is not an object")
    if any(not isinstance(key, str) or key not in {"52", "53", "54", "81"} or not isinstance(value, str) or not HEX8.fullmatch(value) for key, value in properties["textureflags"].items()):
        raise ValueError("properties textureflags are invalid")
    if properties["gpuIDKnown"]:
        if "gpuIDhex" not in properties or not isinstance(properties["gpuIDhex"], str) or not HEX8.fullmatch(properties["gpuIDhex"]):
            raise ValueError("known GPU ID has no fixed hex value")
    elif "gpuIDhex" in properties:
        raise ValueError("unknown GPU ID has a value")
    cleanup = _exact_keys(top["cleanup"], CLEANUP_KEYS, "native cleanup")
    _bool(cleanup["closed"], "cleanup closed")
    for name in ("closeRet", "closeErrno"):
        if not _is_int(cleanup[name]):
            raise ValueError(f"cleanup {name} is not an integer")
    for name in FALSE_FLAGS:
        _bool(top[name], name)
        if top[name] is not False:
            raise ValueError("native output claims a broader qualification")
    empty_properties = (
        not properties["probeCalled"] and properties["probeRet"] is None and properties["probeErrno"] is None
        and not properties["fillCalled"] and properties["fillRet"] is None and properties["fillErrno"] is None
        and properties["returnedBytes"] == 0 and not properties["gpuIDKnown"] and not properties["textureflags"]
        and not properties["valid"]
    )
    if version["major"] != 1 and not empty_properties:
        raise ValueError("properties were attempted without CSF version 1")
    if top["status"] in OBSERVED_STATUSES:
        expected_exit = 1 if top["status"] == "PROPERTIES_BLOCKED" else 0
        if top["nativeExit"] != expected_exit or not device["identityBefore"] or not device["identityAfter"] or not cleanup["closed"] or cleanup["closeRet"] != 0 or cleanup["closeErrno"] != 0 or not version["called"] or version["ret"] != 0 or version["errno"] != 0:
            raise ValueError("complete native status has incomplete identity or cleanup")
        if top["status"] == "VERSION_OBSERVED":
            if version["major"] == 1 or not empty_properties or error != {"stage": "none", "errno": None, "ret": None}:
                raise ValueError("version-only observation contains properties")
        elif version["major"] != 1:
            raise ValueError("properties status has non-CSF version")
        elif top["status"] == "PROPERTIES_BLOCKED":
            blocked_probe = properties["probeCalled"] and isinstance(properties["probeRet"], int) and properties["probeRet"] < 0 and isinstance(properties["probeErrno"], int) and properties["probeErrno"] < 0
            blocked_fill = properties["fillCalled"] and isinstance(properties["fillRet"], int) and properties["fillRet"] < 0 and isinstance(properties["fillErrno"], int) and properties["fillErrno"] < 0
            expected_stage = "properties_probe" if blocked_probe else "properties_fill"
            blocked_ret = properties["probeRet"] if blocked_probe else properties["fillRet"]
            blocked_errno = properties["probeErrno"] if blocked_probe else properties["fillErrno"]
            if (blocked_probe == blocked_fill or properties["valid"] or properties["returnedBytes"] != 0
                    or properties["gpuIDKnown"] or properties["textureflags"]
                    or (blocked_probe and (properties["fillCalled"] or properties["fillRet"] is not None or properties["fillErrno"] is not None))
                    or (blocked_fill and (not properties["probeCalled"] or not isinstance(properties["probeRet"], int) or properties["probeRet"] <= 0 or properties["probeErrno"] != 0))
                    or error != {"stage": expected_stage, "errno": blocked_errno, "ret": blocked_ret}):
                raise ValueError("properties blocked status is not a genuine negative ioctl result")
        else:
            if not properties["probeCalled"] or not properties["fillCalled"] or not properties["valid"] or not isinstance(properties["probeRet"], int) or not 1 <= properties["probeRet"] <= MAX_PROPERTIES or properties["probeErrno"] != 0 or not isinstance(properties["fillRet"], int) or not 1 <= properties["fillRet"] <= properties["probeRet"] or properties["fillErrno"] != 0 or properties["returnedBytes"] != properties["fillRet"] or error != {"stage": "none", "errno": None, "ret": None}:
                raise ValueError("properties observation call sequence is invalid")
    elif top["status"] in CONTROLLED_FAILURES:
        if top["nativeExit"] == 0:
            raise ValueError("controlled native failure has zero exit")
        if top["status"] == "OPEN_FAILED":
            if version["called"] or properties["probeCalled"] or properties["fillCalled"] or error["stage"] not in {"lstat", "open"} or error["ret"] != -1 or error["errno"] is None:
                raise ValueError("open failure contains post-open metadata calls")
        elif (not version["called"] or properties["probeCalled"] or properties["fillCalled"]
              or not isinstance(version["ret"], int) or version["ret"] >= 0
              or not isinstance(version["errno"], int) or version["errno"] >= 0
              or error != {"stage": "version", "errno": version["errno"], "ret": version["ret"]}):
            raise ValueError("version failure is not a genuine negative ioctl result")
    return top


def _drain(name: str, pipe: Any, limit: int, result: dict[str, Any]) -> None:
    loader._drain(name, pipe, limit, result)


def _write_exclusive(path: Path, data: bytes) -> None:
    loader._write_exclusive(path, data)


def _external_parent(value: Path) -> Path:
    return loader._external_parent(value)


def _force_stop() -> dict[str, Any]:
    try:
        result = loader.adb("shell", "am", "force-stop", PACKAGE, timeout=3)
        return {"action": "force-stop exact qualification package", "returnCode": result.returncode,
                "stdout": result.stdout.decode(errors="replace"), "stderr": result.stderr.decode(errors="replace")}
    except Exception as exc:
        return {"action": "force-stop exact qualification package", "returnCode": None, "error": str(exc)}


def _reap(process: subprocess.Popen[bytes], deadline: float) -> bool:
    try:
        process.wait(timeout=max(0.0, deadline - time.monotonic()))
        return True
    except subprocess.TimeoutExpired:
        try:
            process.kill()  # Only the held HOST adb handle; no guessed Android PID.
        except Exception:
            return False
        try:
            process.wait(timeout=max(0.1, deadline - time.monotonic()))
            return process.returncode is not None
        except (subprocess.TimeoutExpired, OSError):
            return False


def _write_best_effort(out: Path, name: str, data: bytes, errors: list[str]) -> None:
    try:
        _write_exclusive(out / name, data)
    except Exception as exc:
        errors.append(f"{name}: {exc}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="existing external evidence parent")
    args = parser.parse_args(argv)
    parent = _external_parent(args.output)

    native = load_native()
    shell_status = loader.remote_status_preflight()
    uid, app_root, selinux_context = loader._package_preflight()
    if uid != UID:
        raise RuntimeError("unexpected app UID")
    run = RUN_PREFIX + uuid.uuid4().hex
    relative, staging = copy_native(native, run)
    private_root = f"{app_root}/{relative}"
    command = shell_command(private_root, run)
    out = parent / ("native-" + run)
    out.mkdir(mode=0o700)
    capture_native = dict(native)
    capture_native["path"] = str(native["path"])
    capture_native["source"] = {
        name: {**record, "path": str(record["path"])}
        for name, record in native["source"].items()
    }
    capture = {
        "schema": 1, "runId": run, "serial": SERIAL, "package": PACKAGE, "uid": uid,
        "versionCode": VERSION_CODE, "selinuxContext": selinux_context,
        "artifact": capture_native, "workingDirectory": private_root, "command": command,
        "stagingCleanup": staging, "shellStatusPreflight": shell_status,
        "nativeSchema": NATIVE_SCHEMA, "versionIoctl": EXPECTED_VERSION_IOCTL,
        "propertiesIoctl": EXPECTED_PROPERTIES_IOCTL, "protocolVerified": False,
        "gpuWorkloadVerified": False, "vulkanVerified": False, "triangleVerified": False,
        "presentationVerified": False, "d3dVerified": False, "wowVerified": False,
        "hardwareVerified": False, "executionStarted": False,
    }
    # This is the last operation before Popen.  A missing durable pin record
    # means no native child is allowed to start.
    _write_exclusive(out / "capture.json", (json.dumps(capture, indent=2, sort_keys=True) + "\n").encode())

    process: subprocess.Popen[bytes] | None = None
    result: dict[str, Any] = {}
    threads: list[threading.Thread] = []
    timed_out = False
    start_error: str | None = None
    cleanup_error: str | None = None
    force_stop: dict[str, Any] | None = None
    try:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        for name, pipe, limit in (("stdout", process.stdout, NATIVE_STDOUT_LIMIT), ("stderr", process.stderr, NATIVE_STDERR_LIMIT)):
            assert pipe is not None
            thread = threading.Thread(target=_drain, args=(name, pipe, limit, result), daemon=True)
            threads.append(thread)
            thread.start()
        try:
            process.wait(timeout=NATIVE_DEADLINE_SECONDS)
        except subprocess.TimeoutExpired:
            timed_out = True
    except Exception as exc:
        start_error = str(exc)
    finally:
        deadline = time.monotonic() + CLEANUP_DEADLINE_SECONDS
        if process is not None and (timed_out or process.poll() is None):
            try:
                process.terminate()
            except Exception as exc:
                cleanup_error = f"held HOST adb handle terminate failed: {exc}"
            if not _reap(process, deadline):
                cleanup_error = cleanup_error or "held HOST adb handle did not quiesce"
        elif process is not None and process.poll() is not None:
            # Reap an already-finished held handle as well.
            if not _reap(process, deadline):
                cleanup_error = "held HOST adb handle could not be reaped"
        for thread in threads:
            thread.join(timeout=max(0.0, deadline - time.monotonic()))
        if any(thread.is_alive() for thread in threads):
            cleanup_error = cleanup_error or "output drain did not quiesce"

    values = {name: result.get(name, {"data": b"", "total": 0, "truncated": False}) for name in ("stdout", "stderr")}
    output_errors: list[str] = []
    _write_best_effort(out, "stdout.txt", values["stdout"]["data"], output_errors)
    _write_best_effort(out, "stderr.txt", values["stderr"]["data"], output_errors)
    native_document: dict[str, Any] | None = None
    diagnostic: str | None = None
    status = "UNKNOWN"
    if start_error:
        diagnostic = "native child start failed: " + start_error
    elif timed_out:
        diagnostic = "native child exceeded ten-second deadline"
    elif cleanup_error:
        diagnostic = cleanup_error
    elif set(result) != {"stdout", "stderr"}:
        diagnostic = "native output drain was incomplete"
    elif values["stdout"]["truncated"] or values["stderr"]["truncated"]:
        diagnostic = "native output exceeded capture bound"
    elif values["stderr"]["data"]:
        diagnostic = "native stderr was not empty"
    else:
        try:
            native_document = parse_native(values["stdout"]["data"], run, private_root)
            status = native_document["status"]
            if process is None or process.returncode != native_document["nativeExit"]:
                status = "UNKNOWN"
                diagnostic = "host adb exit does not equal nativeExit"
        except (UnicodeError, ValueError, json.JSONDecodeError) as exc:
            diagnostic = f"native JSON validation failed: {exc}"
    native_was_unknown = status == "UNKNOWN"
    try:
        remaining = loader.own_processes(UID)
    except Exception as exc:
        remaining = []
        status = "UNKNOWN"
        diagnostic = diagnostic or f"remaining-UID read failed: {exc}"
    unknown_condition = status == "UNKNOWN" or native_was_unknown or start_error is not None or timed_out or cleanup_error is not None or bool(remaining)
    if unknown_condition or remaining:
        force_stop = _force_stop()
        if remaining:
            try:
                remaining = loader.own_processes(UID)
            except Exception as exc:
                remaining = [{"state": "UNKNOWN", "error": str(exc)}]
    summary = {
        "schema": 1, "runId": run, "status": status, "diagnostic": diagnostic,
        "nativeExit": process.returncode if process is not None else None,
        "hostAdbClientPid": process.pid if process is not None else None,
        "timedOut": timed_out, "startError": start_error, "cleanupError": cleanup_error,
        "forceStop": force_stop, "remainingOwnProcesses": remaining,
        "stdoutBytes": values["stdout"]["total"], "stderrBytes": values["stderr"]["total"],
        "stdoutTruncated": values["stdout"]["truncated"], "stderrTruncated": values["stderr"]["truncated"],
        "completePipes": not any(thread.is_alive() for thread in threads) and set(result) == {"stdout", "stderr"},
        "native": native_document, "protocolVerified": False, "gpuWorkloadVerified": False,
        "vulkanVerified": False, "triangleVerified": False, "presentationVerified": False,
        "d3dVerified": False, "wowVerified": False, "hardwareVerified": False,
        "outputErrors": output_errors, "boundary": "context-free kbase metadata only; not GPU, Vulkan, loader, renderer, presentation, D3D, WoW, or hardware qualification",
        "output": str(out),
    }
    _write_best_effort(out, "summary.json", (json.dumps(summary, indent=2, sort_keys=True) + "\n").encode(), output_errors)
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0 if status in COMPLETE_STATUSES and force_stop is None and not output_errors and not remaining else 1


if __name__ == "__main__":
    raise SystemExit(main())
