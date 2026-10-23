#!/usr/bin/env python3
"""Run the fixed own-context kbase metadata control on the approved HOST.

This runner observes only the native control's bounded metadata result.  It
never loads an ICD, starts a renderer, allocates GPU memory, or claims GPU
readiness.
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
_LOADER_SPEC = importlib.util.spec_from_file_location("android_context_loader", _LOADER_PATH)
if _LOADER_SPEC is None or _LOADER_SPEC.loader is None:  # pragma: no cover
    raise ImportError(f"cannot load {_LOADER_PATH}")
loader = importlib.util.module_from_spec(_LOADER_SPEC)
sys.modules[_LOADER_SPEC.name] = loader
_LOADER_SPEC.loader.exec_module(loader)

ADB = loader.ADB
SERIAL = loader.SERIAL
PACKAGE = loader.PACKAGE
UID = loader.UID
VERSION_CODE = loader.VERSION_CODE

NATIVE_ARTIFACT = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/controls/"
    "kbase-context-metadata/KbaseContextMetadata"
)
NATIVE_MANIFEST = NATIVE_ARTIFACT.with_name("manifest.json")
NATIVE_NAME = "KbaseContextMetadata"
NATIVE_ARTIFACT_HOST_MODE = "0755"
NATIVE_DEVICE_MODE = "0700"
# Final pins from the successful pinned build; upload fails closed on drift.
NATIVE_ARTIFACT_BYTES = 17216
NATIVE_ARTIFACT_SHA256 = "e739ce1ee26c46d5167af21de703d7d7264ebf313c544a36253ac01b4735ec2f"
NATIVE_MANIFEST_SHA256 = "3082bac1793efa10e2f77424434b13912bec927c8ac9bf2391af673cee162b94"
SOURCE_PINS: dict[str, str] = {
    "KbaseContextMetadata.c": "413fc535dbd741d516f827dc51b47439e15587787628beb68da79d408b30f011",
    "build_context_probe.sh": "c77ccf05afb3f0472be30ead00e205a33c5413d5cfb4f0119095cb27306ff282",
    "README-context.md": "4e5803337aa65a1ea77980efb6a1c0511f96be1c728a11a68c2159b1d3ef1e0e",
}
SOURCE_ROOT = Path(__file__).parent
PROVIDER_REPOSITORY = "https://github.com/zenithblue-oss/panvk-kbase-android"
PROVIDER_COMMIT = "de577411e8f27da9de50aec26a0d8cc43890745e"

NATIVE_SCHEMA = "wowforever.android-kbase-context-metadata/version1"
RUN_PREFIX = "PVK-"
RUN_RE = re.compile(r"^PVK-[0-9a-f]{32}$")
STAGE_PREFIX = "/data/local/tmp/wowforever-kbase-context-"
NATIVE_STDOUT_LIMIT = 16 * 1024
NATIVE_STDERR_LIMIT = 64 * 1024
NATIVE_DEADLINE_SECONDS = 10
CLEANUP_DEADLINE_SECONDS = 5
MAX_NATIVE_LINE = 8192
MAX_PROPERTIES = 16384
EXPECTED_VERSION_IOCTL = "0xc0048034"
EXPECTED_CONTEXT_IOCTL = "0x40048001"
EXPECTED_PROPERTIES_IOCTL = "0x40108003"
EXPECTED_DEVICE = "/dev/mali0"
EXPECTED_MAJOR = 10
EXPECTED_MINOR = 113
VALID_STATUSES = {
    "UNKNOWN", "OPEN_FAILED", "VERSION_FAILED", "VERSION_OBSERVED", "VERSION_MISMATCH",
    "FLAGS_BLOCKED", "PROPERTIES_BLOCKED", "PROPERTIES_OBSERVED",
}
COMPLETE_STATUSES = {"PROPERTIES_OBSERVED"}
CONTROLLED_FAILURES = {"OPEN_FAILED", "VERSION_FAILED", "VERSION_MISMATCH", "FLAGS_BLOCKED"}
FALSE_FLAGS = ("gpuWorkloadVerified", "driverExecution", "deviceVerified", "hardwareVerified")
HEX8 = re.compile(r"^0x[0-9a-f]{8}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
ERROR_STAGES = {
    "none", "scope", "lstat", "lstat_identity", "open", "deadline_open", "fstat_before",
    "identity_before", "deadline_before_version", "deadline_after_version", "version",
    "deadline_after_set_flags", "set_flags", "deadline_before_properties",
    "deadline_after_properties_probe", "properties_probe", "properties_size", "properties_alloc",
    "deadline_before_properties_fill", "deadline_after_properties_fill", "properties_fill",
    "properties_bounds", "properties_malformed", "fstat_after", "identity_after", "close",
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
    expected = {"KbaseContextMetadata.c", "build_context_probe.sh", "README-context.md"}
    if set(SOURCE_PINS) != expected:
        raise ValueError("source pin set is not closed")
    return {name: _fixed_hash(value, f"source {name}") for name, value in SOURCE_PINS.items()}


def _validate_sources() -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for name, digest in _source_pins().items():
        path = SOURCE_ROOT / name
        info = _regular_nosymlink(path)
        if info.st_size > 128 * 1024:
            raise ValueError(f"source exceeds bound: {name}")
        data = path.read_bytes()
        if len(data) > 128 * 1024 or hashlib.sha256(data).hexdigest() != digest:
            raise ValueError(f"source pin mismatch: {name}")
        result[name] = {"path": str(path), "bytes": len(data), "sha256": digest,
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
    sources = _validate_sources()
    artifact_digest = _fixed_hash(NATIVE_ARTIFACT_SHA256, "native artifact")
    manifest_digest = _fixed_hash(NATIVE_MANIFEST_SHA256, "native manifest")
    if type(NATIVE_ARTIFACT_BYTES) is not int or not 0 < NATIVE_ARTIFACT_BYTES <= 64 * 1024:
        raise ValueError("native artifact size pin is absent or invalid")
    info = _regular_nosymlink(NATIVE_ARTIFACT)
    if format(stat.S_IMODE(info.st_mode), "04o") != NATIVE_ARTIFACT_HOST_MODE:
        raise ValueError("native artifact host mode mismatch")
    data = NATIVE_ARTIFACT.read_bytes()
    if len(data) != NATIVE_ARTIFACT_BYTES or hashlib.sha256(data).hexdigest() != artifact_digest:
        raise ValueError("native artifact hash or size mismatch")
    _validate_elf(data)
    manifest_info = _regular_nosymlink(NATIVE_MANIFEST)
    manifest_raw = NATIVE_MANIFEST.read_bytes()
    if len(manifest_raw) > 128 * 1024 or hashlib.sha256(manifest_raw).hexdigest() != manifest_digest:
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
    if document.get("sourceSha256") != {name: record["sha256"] for name, record in sources.items()}:
        raise ValueError("native manifest source pin mismatch")
    return {"path": NATIVE_ARTIFACT, "name": NATIVE_NAME, "bytes": len(data), "sha256": artifact_digest,
            "mode": NATIVE_DEVICE_MODE, "hostMode": NATIVE_ARTIFACT_HOST_MODE, "manifest": str(NATIVE_MANIFEST),
            "manifestSha256": manifest_digest, "manifestBytes": len(manifest_raw),
            "manifestMode": format(stat.S_IMODE(manifest_info.st_mode), "04o"), "source": sources,
            "provider": {"repository": PROVIDER_REPOSITORY, "commit": PROVIDER_COMMIT}}


def _q(value: str) -> str:
    return shlex.quote(value)


def _private_relative(run: str) -> str:
    if not RUN_RE.fullmatch(run):
        raise ValueError("invalid kbase context run id")
    return f"files/forever/controls/panvk-kernel/{run}"


def _copy_script(stage_file: str, relative: str) -> str:
    directories = ["files", "files/forever", "files/forever/controls", "files/forever/controls/panvk-kernel"]
    commands = ["umask 077", "set -e", "set -C"]
    for directory in directories:
        quoted = _q(directory)
        commands.append(f"test ! -L {quoted} && (test -d {quoted} || mkdir -m 700 {quoted})")
    commands.extend([
        f"test ! -e {_q(relative)} && test ! -L {_q(relative)}", f"mkdir -m 700 {_q(relative)}",
        f"test ! -e {_q(relative + '/' + NATIVE_NAME)} && test ! -L {_q(relative + '/' + NATIVE_NAME)}",
        f"cat {_q(stage_file)} > {_q(relative + '/' + NATIVE_NAME)}", f"chmod {NATIVE_DEVICE_MODE} {_q(relative + '/' + NATIVE_NAME)}",
        "printf KBASE_CONTEXT_UPLOAD_OK",
    ])
    return "; ".join(commands)


def copy_native(native: dict[str, Any], run: str) -> tuple[str, dict[str, Any]]:
    relative = _private_relative(run)
    stage = STAGE_PREFIX + uuid.uuid4().hex
    stage_file = f"{stage}/{NATIVE_NAME}"
    stage_created = False
    stage_creation_uncertain = False
    failures: list[str] = []
    try:
        loader.require_adb(loader.adb("shell", "test", "-d", "/data/local/tmp"), "staging parent")
        try:
            loader.require_adb(loader.adb("shell", "mkdir", "-m", "755", stage), "exclusive staging directory")
        except (subprocess.TimeoutExpired, TimeoutError):
            stage_creation_uncertain = True
            raise
        stage_created = True
        loader.require_adb(loader.adb("push", str(native["path"]), stage_file, timeout=30), "native sync upload")
        loader.require_adb(loader.adb("shell", "chmod", "0644", stage_file), "native staging mode")
        uploaded = loader.adb("exec-out", "run-as", PACKAGE, "/system/bin/sh", "-c", _copy_script(stage_file, relative), timeout=30)
        if uploaded.returncode or uploaded.stdout != b"KBASE_CONTEXT_UPLOAD_OK" or uploaded.stderr:
            raise RuntimeError("private context upload sentinel was not exact")
        destination = f"{relative}/{NATIVE_NAME}"
        hashed = loader.require_adb(loader.adb("exec-out", "run-as", PACKAGE, "sha256sum", destination), "device native hash", limit=4096).decode("ascii", "strict").split()
        if not hashed or hashed[0] != native["sha256"]:
            raise RuntimeError("device native artifact hash mismatch")
        mode = loader.require_adb(loader.adb("exec-out", "run-as", PACKAGE, "stat", "-c", "%a", destination), "device native mode", limit=4096).strip()
        if mode != b"700":
            raise RuntimeError("device native artifact mode mismatch")
        return relative, {"path": stage, "file": stage_file, "stagingClean": True, "failures": []}
    finally:
        if stage_created:
            for name, args in (("staging-file", ("shell", "rm", "-f", stage_file)), ("staging-directory", ("shell", "rmdir", stage))):
                try:
                    result = loader.adb(*args)
                    if result.returncode or result.stdout or result.stderr:
                        failures.append(name)
                except Exception:
                    failures.append(name)
        elif stage_creation_uncertain:
            # No file was uploaded: remove only an empty unique directory, never
            # contents of a directory whose creation could not be confirmed.
            try:
                result = loader.adb("shell", "rmdir", stage, timeout=3)
                if result.returncode or result.stdout or result.stderr:
                    failures.append("uncertain-empty-staging-directory")
            except Exception:
                failures.append("uncertain-empty-staging-directory")
        if failures:
            raise RuntimeError("owned context staging cleanup failed; no payload executed: " + json.dumps({"path": stage, "failures": failures}))


def shell_command(private_root: str, run: str) -> list[str]:
    approved = {
        f"/data/user/0/{PACKAGE}/{_private_relative(run)}",
        f"/data/data/{PACKAGE}/{_private_relative(run)}",
    }
    if private_root not in approved:
        raise ValueError("unapproved private context working directory")
    root = _q(private_root)
    env = " ".join(_q(value) for value in ("PATH=/system/bin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
                                             f"LD_LIBRARY_PATH={private_root}:/system/lib64", f"TMPDIR={private_root}"))
    binary = _q(f"{private_root}/{NATIVE_NAME}")
    script = f"cd {root} && exec /system/bin/env -i {env} /system/bin/linker64 {binary} --run-id {_q(run)}"
    return [ADB, "-s", SERIAL, "shell", "-T", "run-as", PACKAGE, "/system/bin/sh", "-c", _q(script)]


def _is_int(value: object) -> bool:
    return type(value) is int


def _nullable_int(value: object) -> None:
    if value is not None and not _is_int(value):
        raise ValueError("nullable native integer has wrong type")


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
    top_keys = {"schema", "runId", "pid", "uid", "pgid", "sid", "cwd", "time", "status", "error", "device",
                "version", "context", "properties", "flagsInited", *FALSE_FLAGS, "cleanup", "nativeExit"}
    top = _exact_keys(value, top_keys, "native")
    if top["schema"] != NATIVE_SCHEMA or not isinstance(top["runId"], str) or top["runId"] != run or not RUN_RE.fullmatch(run):
        raise ValueError("native schema or run id mismatch")
    for name in ("pid", "uid", "pgid", "sid", "nativeExit"):
        if not _is_int(top[name]):
            raise ValueError(f"native {name} is not an integer")
    if top["pid"] <= 0 or top["pgid"] <= 0 or top["sid"] <= 0 or top["uid"] != UID or not 0 <= top["nativeExit"] <= 255:
        raise ValueError("native process identity or exit is invalid")
    roots = {f"/data/user/0/{PACKAGE}/{_private_relative(run)}", f"/data/data/{PACKAGE}/{_private_relative(run)}"}
    if top["cwd"] not in roots or private_root not in roots:
        raise ValueError("native cwd is not the owned private root")
    if top["status"] not in VALID_STATUSES:
        raise ValueError("native status is unknown")
    timing = _exact_keys(top["time"], {"startedNs", "finishedNs", "durationNs"}, "native time")
    if any(not _is_int(timing[name]) for name in timing) or timing["startedNs"] <= 0 or timing["finishedNs"] < timing["startedNs"] or timing["durationNs"] != timing["finishedNs"] - timing["startedNs"] or timing["durationNs"] > 5_000_000_000:
        raise ValueError("native timing is outside its bound")
    error = _exact_keys(top["error"], {"stage", "errno", "ret"}, "native error")
    if error["stage"] not in ERROR_STAGES or any(error[name] is not None and not _is_int(error[name]) for name in ("errno", "ret")):
        raise ValueError("native error is invalid")
    if error["errno"] is not None and error["errno"] >= 0:
        raise ValueError("native error errno must be negative")
    device = _exact_keys(top["device"], {"path", "major", "minor", "identityBefore", "identityAfter"}, "native device")
    if device["path"] != EXPECTED_DEVICE or not all(_is_int(device[name]) for name in ("major", "minor")) or device["major"] != EXPECTED_MAJOR or device["minor"] != EXPECTED_MINOR:
        raise ValueError("native device identity is invalid")
    _bool(device["identityBefore"], "device identityBefore"); _bool(device["identityAfter"], "device identityAfter")
    version = _exact_keys(top["version"], {"called", "ioctl", "ret", "errno", "major", "minor"}, "native version")
    _bool(version["called"], "version called")
    if version["ioctl"] != EXPECTED_VERSION_IOCTL or any(version[name] is not None and not _is_int(version[name]) for name in ("ret", "errno")):
        raise ValueError("version ABI result is invalid")
    if version["errno"] is not None and version["errno"] > 0:
        raise ValueError("version errno must be zero or negative")
    if any(not _is_int(version[name]) or not 0 <= version[name] <= 65535 for name in ("major", "minor")):
        raise ValueError("version value is not uint16")
    context = _exact_keys(top["context"], {"called", "ioctl", "createFlags", "ret", "errno"}, "native context")
    _bool(context["called"], "context called")
    if context["ioctl"] != EXPECTED_CONTEXT_IOCTL or not _is_int(context["createFlags"]) or context["createFlags"] != 0:
        raise ValueError("context ABI or flags are invalid")
    if any(context[name] is not None and not _is_int(context[name]) for name in ("ret", "errno")):
        raise ValueError("context result is invalid")
    if context["errno"] is not None and context["errno"] > 0:
        raise ValueError("context errno must be zero or negative")
    if not context["called"] and any(context[name] is not None for name in ("ret", "errno")):
        raise ValueError("un-called context has a result")
    properties = top["properties"]
    property_keys = {"probeCalled", "probeRet", "probeErrno", "fillCalled", "fillRet", "fillErrno", "returnedBytes", "gpuIDKnown", "textureflags", "valid"}
    if not isinstance(properties, dict) or set(properties) not in (property_keys, property_keys | {"gpuIDhex"}):
        raise ValueError("native properties schema mismatch")
    for name in ("probeCalled", "fillCalled", "gpuIDKnown", "valid"):
        _bool(properties[name], f"properties {name}")
    for name in ("probeRet", "probeErrno", "fillRet", "fillErrno"):
        _nullable_int(properties[name])
        if name.endswith("Errno") and properties[name] is not None and properties[name] > 0:
            raise ValueError("properties errno must be zero or negative")
    if not _is_int(properties["returnedBytes"]) or not 0 <= properties["returnedBytes"] <= MAX_PROPERTIES or not isinstance(properties["textureflags"], dict):
        raise ValueError("properties bounds or textureflags are invalid")
    if any(key not in {"52", "53", "54", "81"} or not isinstance(value, str) or not HEX8.fullmatch(value) for key, value in properties["textureflags"].items()):
        raise ValueError("properties textureflags are invalid")
    if properties["gpuIDKnown"] != ("gpuIDhex" in properties) or (properties["gpuIDKnown"] and not HEX8.fullmatch(properties["gpuIDhex"])):
        raise ValueError("GPU ID result is invalid")
    if not properties["probeCalled"] and any(properties[name] is not None for name in ("probeRet", "probeErrno")):
        raise ValueError("un-called properties probe has a result")
    if not properties["fillCalled"] and any(properties[name] is not None for name in ("fillRet", "fillErrno")):
        raise ValueError("un-called properties fill has a result")
    _bool(top["flagsInited"], "flagsInited")
    for name in FALSE_FLAGS:
        _bool(top[name], name)
        if top[name] is not False:
            raise ValueError("native output claims a broader qualification")
    cleanup = _exact_keys(top["cleanup"], {"closed", "closeRet", "closeErrno"}, "native cleanup")
    _bool(cleanup["closed"], "cleanup closed")
    if not _is_int(cleanup["closeRet"]) or not _is_int(cleanup["closeErrno"]):
        raise ValueError("cleanup result is invalid")
    empty = (not properties["probeCalled"] and properties["probeRet"] is None and properties["probeErrno"] is None and
             not properties["fillCalled"] and properties["fillRet"] is None and properties["fillErrno"] is None and
             properties["returnedBytes"] == 0 and not properties["gpuIDKnown"] and not properties["textureflags"] and not properties["valid"])
    if top["status"] == "UNKNOWN" and top["nativeExit"] != 1:
        raise ValueError("unknown native result must exit nonzero")
    if not version["called"]:
        if (version["ret"] is not None or version["errno"] is not None
                or version["major"] != 0 or version["minor"] != 0
                or context["called"] or top["flagsInited"] or not empty
                or top["status"] not in {"OPEN_FAILED", "UNKNOWN"}
                or top["nativeExit"] != 1
                or error["stage"] not in {"scope", "lstat", "lstat_identity", "open",
                                         "deadline_open", "fstat_before", "identity_before",
                                         "deadline_before_version", "fstat_after", "identity_after", "close"}):
            raise ValueError("metadata calls occurred before version")
    elif version["ret"] is None:
        raise ValueError("called version has no return")
    elif version["ret"] == 0 and version["errno"] != 0:
        raise ValueError("successful version has a contradictory errno")
    elif version["ret"] < 0:
        if version["errno"] is None or version["errno"] >= 0 or context["called"] or top["flagsInited"] or not empty or top["status"] not in {"VERSION_FAILED", "UNKNOWN"} or error not in ({"stage": "version", "errno": version["errno"], "ret": version["ret"]}, {"stage": "deadline_after_version", "errno": version["errno"], "ret": version["ret"]}) or top["nativeExit"] != 1:
            raise ValueError("version failure contains later calls")
    elif version["ret"] > 0:
        if version["errno"] != 0 or context["called"] or top["flagsInited"] or not empty or top["status"] != "UNKNOWN" or error not in ({"stage": "version", "errno": None, "ret": version["ret"]}, {"stage": "deadline_after_version", "errno": 0, "ret": version["ret"]}) or top["nativeExit"] != 1:
            raise ValueError("positive version result is not unknown")
    elif (version["major"], version["minor"]) != (1, 18):
        if version["errno"] != 0 or context["called"] or top["flagsInited"] or not empty or top["status"] not in {"VERSION_MISMATCH", "UNKNOWN"} or top["nativeExit"] != 1 or error not in ({"stage": "none", "errno": None, "ret": None}, {"stage": "deadline_after_version", "errno": 0, "ret": version["ret"]}):
            raise ValueError("version mismatch contains later calls")
    else:
        if not context["called"]:
            if top["status"] != "UNKNOWN" or top["nativeExit"] != 1 or top["flagsInited"] or not empty or error["stage"] != "deadline_after_version":
                raise ValueError("1.18 did not initialize its own context")
            return top
        if context["ret"] is None:
            raise ValueError("called SET_FLAGS has no return")
        if context["ret"] < 0:
            if context["errno"] is None or context["errno"] >= 0 or top["flagsInited"] or not empty or top["status"] not in {"FLAGS_BLOCKED", "UNKNOWN"} or top["nativeExit"] != 1 or error not in ({"stage": "set_flags", "errno": context["errno"], "ret": context["ret"]}, {"stage": "deadline_after_set_flags", "errno": context["errno"], "ret": context["ret"]}):
                raise ValueError("blocked context contains later calls")
        elif context["ret"] > 0:
            if context["errno"] != 0 or top["flagsInited"] or not empty or top["status"] != "UNKNOWN" or error != {"stage": "set_flags", "errno": None, "ret": context["ret"]}:
                raise ValueError("positive SET_FLAGS result is not unknown")
        elif context["errno"] != 0 or (not top["flagsInited"] and not (top["status"] == "UNKNOWN" and error["stage"] == "set_flags")):
            raise ValueError("successful SET_FLAGS did not set flagsInited")
        elif top["status"] == "PROPERTIES_BLOCKED":
            blocked_probe = properties["probeCalled"] and isinstance(properties["probeRet"], int) and properties["probeRet"] < 0 and isinstance(properties["probeErrno"], int) and properties["probeErrno"] < 0
            blocked_fill = properties["fillCalled"] and isinstance(properties["fillRet"], int) and properties["fillRet"] < 0 and isinstance(properties["fillErrno"], int) and properties["fillErrno"] < 0
            if blocked_probe and (properties["fillCalled"] or properties["fillRet"] is not None or properties["fillErrno"] is not None):
                raise ValueError("properties fill occurred after a failed probe")
            if blocked_fill and (not properties["probeCalled"] or not isinstance(properties["probeRet"], int) or not 1 <= properties["probeRet"] <= MAX_PROPERTIES or properties["probeErrno"] != 0):
                raise ValueError("blocked fill has no bounded successful probe")
            expected_stage = "properties_probe" if blocked_probe else "properties_fill"
            blocked_ret = properties["probeRet"] if blocked_probe else properties["fillRet"]
            blocked_errno = properties["probeErrno"] if blocked_probe else properties["fillErrno"]
            if blocked_probe == blocked_fill or properties["valid"] or properties["returnedBytes"] != 0 or properties["gpuIDKnown"] or properties["textureflags"] or error != {"stage": expected_stage, "errno": blocked_errno, "ret": blocked_ret} or top["nativeExit"] != 1:
                raise ValueError("properties blocked is not a negative ioctl result")
        elif top["status"] == "PROPERTIES_OBSERVED":
            if not properties["probeCalled"] or not properties["fillCalled"] or not properties["valid"] or properties["probeErrno"] != 0 or properties["fillErrno"] != 0 or not isinstance(properties["probeRet"], int) or not 1 <= properties["probeRet"] <= MAX_PROPERTIES or not isinstance(properties["fillRet"], int) or not 1 <= properties["fillRet"] <= properties["probeRet"] or properties["returnedBytes"] != properties["fillRet"] or error != {"stage": "none", "errno": None, "ret": None} or top["nativeExit"] != 0:
                raise ValueError("properties observation sequence is invalid")
        elif top["status"] == "UNKNOWN":
            if error["stage"] not in {"deadline_before_properties", "deadline_after_properties_probe", "properties_probe", "properties_size", "properties_alloc", "deadline_before_properties_fill", "deadline_after_properties_fill", "properties_fill", "properties_bounds", "properties_malformed", "fstat_after", "identity_after", "close"}:
                raise ValueError("unknown properties result has invalid error stage")
        else:
            raise ValueError("1.18 result has invalid status")
    if top["status"] in {"PROPERTIES_OBSERVED", "PROPERTIES_BLOCKED", "VERSION_MISMATCH", "FLAGS_BLOCKED"}:
        if not device["identityBefore"] or not device["identityAfter"] or not cleanup["closed"] or cleanup["closeRet"] != 0 or cleanup["closeErrno"] != 0:
            raise ValueError("controlled result has incomplete cleanup or identity")
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
            process.kill()
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
    capture_native["source"] = {name: {**record, "path": str(record["path"])} for name, record in native["source"].items()}
    capture = {
        "schema": 1, "runId": run, "serial": SERIAL, "package": PACKAGE, "uid": uid, "versionCode": VERSION_CODE,
        "selinuxContext": selinux_context, "artifact": capture_native, "workingDirectory": private_root, "command": command,
        "stagingCleanup": staging, "shellStatusPreflight": shell_status, "nativeSchema": NATIVE_SCHEMA,
        "versionIoctl": EXPECTED_VERSION_IOCTL, "contextIoctl": EXPECTED_CONTEXT_IOCTL, "propertiesIoctl": EXPECTED_PROPERTIES_IOCTL,
        "metadataObserved": False, "flagsInited": False, "gpuWorkloadVerified": False, "driverExecution": False,
        "vulkanVerified": False, "triangleVerified": False, "presentationVerified": False, "d3dVerified": False,
        "wowVerified": False, "hardwareVerified": False, "executionStarted": False,
    }
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
            threads.append(thread); thread.start()
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
        elif process is not None and not _reap(process, deadline):
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
                status = "UNKNOWN"; diagnostic = "host adb exit does not equal nativeExit"
        except (UnicodeError, ValueError, json.JSONDecodeError) as exc:
            diagnostic = f"native JSON validation failed: {exc}"
    try:
        remaining = loader.own_processes(UID)
    except Exception as exc:
        remaining = []; status = "UNKNOWN"; diagnostic = diagnostic or f"remaining-UID read failed: {exc}"
    unknown_condition = status == "UNKNOWN" or start_error is not None or timed_out or cleanup_error is not None or bool(remaining)
    if unknown_condition:
        force_stop = _force_stop()
        if remaining:
            try:
                remaining = loader.own_processes(UID)
            except Exception as exc:
                remaining = [{"state": "UNKNOWN", "error": str(exc)}]
    summary = {
        "schema": 1, "runId": run, "status": status, "diagnostic": diagnostic,
        "nativeExit": process.returncode if process is not None else None, "hostAdbClientPid": process.pid if process is not None else None,
        "timedOut": timed_out, "startError": start_error, "cleanupError": cleanup_error, "forceStop": force_stop,
        "remainingOwnProcesses": remaining, "stdoutBytes": values["stdout"]["total"], "stderrBytes": values["stderr"]["total"],
        "stdoutTruncated": values["stdout"]["truncated"], "stderrTruncated": values["stderr"]["truncated"],
        "completePipes": not any(thread.is_alive() for thread in threads) and set(result) == {"stdout", "stderr"},
        "native": native_document, "metadataObserved": native_document is not None and status in {"PROPERTIES_OBSERVED", "PROPERTIES_BLOCKED"},
        "flagsInited": bool(native_document and native_document.get("flagsInited") is True), "gpuWorkloadVerified": False,
        "driverExecution": False, "vulkanVerified": False, "triangleVerified": False, "presentationVerified": False,
        "d3dVerified": False, "wowVerified": False, "hardwareVerified": False, "outputErrors": output_errors,
        "boundary": "own kbase context metadata only; not GPU, Vulkan, ICD, renderer, presentation, D3D, WoW, or readiness",
        "output": str(out),
    }
    _write_best_effort(out, "summary.json", (json.dumps(summary, indent=2, sort_keys=True) + "\n").encode(), output_errors)
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0 if status in COMPLETE_STATUSES and force_stop is None and not output_errors and not remaining else 1


if __name__ == "__main__":
    raise SystemExit(main())
