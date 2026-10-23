#!/usr/bin/env python3
"""Run the fixed PanVK private-loader control on the approved HOST.

The command is intentionally a host-side binding, not a Vulkan or GPU
qualification tool.  It uploads only the reviewed probe and ICD into a new
run-as directory, executes the probe once, and preserves the native evidence.
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
_LOADER_SPEC = importlib.util.spec_from_file_location("android_private_loader_base", _LOADER_PATH)
if _LOADER_SPEC is None or _LOADER_SPEC.loader is None:  # pragma: no cover
    raise ImportError(f"cannot load {_LOADER_PATH}")
loader = importlib.util.module_from_spec(_LOADER_SPEC)
sys.modules[_LOADER_SPEC.name] = loader
_LOADER_SPEC.loader.exec_module(loader)


ADB = loader.ADB
SERIAL_ENV_VAR = "WOWFOREVER_ADB_SERIAL"
SERIAL = os.environ.get(SERIAL_ENV_VAR, "")
PACKAGE = "com.wowforever.arm64probe"
UID = 10330
VERSION_CODE = 41
DEVICE_API_LEVEL = 34
PUBLISHED_MIN_API_LEVEL = 35

SOURCE_ROOT = Path(__file__).parent
ARTIFACT_ROOT = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/panvk-private-loader-v2")
NATIVE_ARTIFACT = ARTIFACT_ROOT / "PanvkPrivateLoader"
NATIVE_MANIFEST = ARTIFACT_ROOT / "manifest.json"
NATIVE_NAME = "PanvkPrivateLoader"
ICD_NAME = "libvulkan_panfrost.so"
ICD_SOURCE = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/controls/"
    "panvk-audit-beta16/icd/libvulkan_panfrost.so"
)

NATIVE_BYTES = 23392
NATIVE_SHA256 = "7a7861c8b5e6795a8ecee8ee9794a3abcd0d076b5d307c6d2b0d092ce198024a"
NATIVE_MANIFEST_SHA256 = "786f25b5fd5637fec3166ea220ef64e1e27924495e4ff40ff76e3d32766be732"
NATIVE_HOST_MODE = "0755"
NATIVE_DEVICE_MODE = "0700"
ICD_BYTES = 30972472
ICD_SHA256 = "c0e54909059182938ed823df1640848ecd40d75f6885cdf3b9d04530dc93e60c"
ICD_REPORT_SHA256 = "afed56f5a430baabd136230d8de1bbe7f0f08ed224b365dcd6d44f3881a216a3"
SOURCE_PINS = {
    "PanvkPrivateLoader.c": "c15a849033826a881f1da424de1f2560a5dafabb13da298f1ce380e3639af29a",
    "README-private-loader.md": "182d90acfb56d78783ee2a3b884dd556c93d526d6dd8e2b5fe77ae8549cf99c4",
    "build_private_loader.sh": "5f0dd7f9a7b3c95bbde0a69432a0e18cf7212baf5eeb34b72eb8aef382998bf3",
}

NATIVE_SCHEMA = "wowforever.android-panvk-private-loader"
RUN_PREFIX = "PVK-"
RUN_RE = re.compile(r"^PVK-[0-9a-f]{32}$")
STAGE_PREFIX = "/data/local/tmp/wowforever-panvk-private-"
NATIVE_STDOUT_LIMIT = 16 * 1024
NATIVE_STDERR_LIMIT = 64 * 1024
NATIVE_DEADLINE_SECONDS = 10
CLEANUP_DEADLINE_SECONDS = 5
MAX_NATIVE_LINE = 16 * 1024
ERROR_BYTES = 512
HEX64 = re.compile(r"^[0-9a-f]{64}$")
EXPORT_NAMES = (
    "vk_icdGetInstanceProcAddr",
    "vk_icdGetPhysicalDeviceProcAddr",
    "vk_icdNegotiateLoaderICDInterfaceVersion",
)
PRIVATE_PREFIX = "files/forever/controls/panvk-loader"
APP_ROOTS = {
    f"/data/user/0/{PACKAGE}",
    f"/data/data/{PACKAGE}",
}
ENVIRONMENT_NAMES = ("PATH", "ANDROID_ROOT", "ANDROID_DATA", "LD_LIBRARY_PATH", "TMPDIR")
VALID_FAILURE_STAGES = {
    "none", "arguments", "scope", "path", "identity", "hash", "clock",
    "loader", "export", "owner", "cleanup", "output",
}


def adb(*args: str, timeout: int = 15) -> subprocess.CompletedProcess[bytes]:
    """Use the reviewed, fixed-serial ADB helper without opening a new policy."""
    return loader.adb(*args, timeout=timeout)


def remote_status_preflight() -> dict[str, Any]:
    return loader.remote_status_preflight()


def package_preflight() -> tuple[int, str, str]:
    return loader._package_preflight()


def own_processes(uid: int = UID) -> list[dict[str, Any]]:
    return loader.own_processes(uid)


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
    if not isinstance(value, str) or HEX64.fullmatch(value) is None or value == "0" * 64:
        raise ValueError(f"{label} pin is absent or invalid")
    return value


def _bounded_error(value: object, label: str = "error") -> str:
    if not isinstance(value, str) or len(value.encode("utf-8")) > ERROR_BYTES:
        raise ValueError(f"{label} exceeds 512 UTF-8 bytes")
    return value


def _is_int(value: object) -> bool:
    return type(value) is int


def _bool(value: object, label: str) -> None:
    if type(value) is not bool:
        raise ValueError(f"{label} is not boolean")


def _exact_keys(value: object, keys: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        raise ValueError(f"{label} schema mismatch")
    return value


def _validate_sources() -> dict[str, dict[str, Any]]:
    if set(SOURCE_PINS) != {"PanvkPrivateLoader.c", "README-private-loader.md", "build_private_loader.sh"}:
        raise ValueError("source pin set is not closed")
    result: dict[str, dict[str, Any]] = {}
    for name, digest in SOURCE_PINS.items():
        _fixed_hash(digest, f"source {name}")
        path = SOURCE_ROOT / name
        info = _regular_nosymlink(path)
        if info.st_size > 128 * 1024:
            raise ValueError(f"source exceeds bound: {name}")
        data = path.read_bytes()
        if len(data) != info.st_size or hashlib.sha256(data).hexdigest() != digest:
            raise ValueError(f"source pin mismatch: {name}")
        result[name] = {
            "path": str(path), "bytes": len(data), "sha256": digest,
            "mode": format(stat.S_IMODE(info.st_mode), "04o"),
        }
    return result


def _vaddr_to_offset(data: bytes, phdrs: list[tuple[int, int, int, int, int, int, int]], address: int) -> int:
    for p_type, p_offset, p_vaddr, p_filesz, _p_memsz, _p_flags, _p_align in phdrs:
        if p_type == 1 and p_vaddr <= address < p_vaddr + p_filesz:
            offset = p_offset + address - p_vaddr
            if offset < len(data):
                return offset
    raise ValueError("native dynamic address is outside loadable segments")


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
    names: list[str] = []
    for item in needed:
        if item >= string_size:
            raise ValueError("native dependency name is outside string table")
        end = data.find(b"\0", strings_offset + item, strings_offset + string_size)
        if end < 0:
            raise ValueError("native dependency name is unterminated")
        names.append(data[strings_offset + item:end].decode("ascii", "strict"))
    if names != ["libdl.so", "libc.so"]:
        raise ValueError("native artifact dependencies are not exactly libc.so/libdl.so")


def load_native() -> dict[str, Any]:
    """Validate the manifest, source closure, and immutable host artifact."""
    sources = _validate_sources()
    _regular_nosymlink(NATIVE_MANIFEST)
    manifest_raw = NATIVE_MANIFEST.read_bytes()
    if len(manifest_raw) > 128 * 1024 or hashlib.sha256(manifest_raw).hexdigest() != NATIVE_MANIFEST_SHA256:
        raise ValueError("native manifest hash mismatch")
    document = json.loads(manifest_raw.decode("ascii", "strict"), object_pairs_hook=unique_json_pairs)
    if not isinstance(document, dict) or document.get("schema") != "wowforever.android-panvk-private-loader-build/version1":
        raise ValueError("native manifest schema mismatch")
    if document.get("file") != NATIVE_NAME or document.get("size") != NATIVE_BYTES or document.get("sha256") != NATIVE_SHA256:
        raise ValueError("native manifest artifact pin mismatch")
    if document.get("mode") != NATIVE_HOST_MODE or document.get("interpreter") != "/system/bin/linker64":
        raise ValueError("native manifest mode or interpreter pin mismatch")
    if document.get("elfType") != "ET_DYN" or document.get("machine") != "AArch64" or document.get("pie") is not True:
        raise ValueError("native manifest ELF pin mismatch")
    if document.get("neededLibraries") != ["libc.so", "libdl.so"] or document.get("linkLibraries") != ["c", "dl"]:
        raise ValueError("native manifest dependency pin mismatch")
    if document.get("sourceSha256") != {name: item["sha256"] for name, item in sources.items()}:
        raise ValueError("native manifest source pin mismatch")
    icd_pin = document.get("icdPin")
    if (not isinstance(icd_pin, dict) or icd_pin.get("bytes") != ICD_BYTES or icd_pin.get("sha256") != ICD_SHA256
            or icd_pin.get("sourcePath") != str(ICD_SOURCE) or icd_pin.get("mode") != "0600"
            or icd_pin.get("elf") != {"machine": "AArch64", "reportSha256": ICD_REPORT_SHA256, "type": "ET_DYN"}):
        raise ValueError("native manifest ICD pin mismatch")
    info = _regular_nosymlink(NATIVE_ARTIFACT)
    if format(stat.S_IMODE(info.st_mode), "04o") != NATIVE_HOST_MODE:
        raise ValueError("native artifact host mode mismatch")
    data = NATIVE_ARTIFACT.read_bytes()
    if len(data) != NATIVE_BYTES or hashlib.sha256(data).hexdigest() != NATIVE_SHA256:
        raise ValueError("native artifact hash or size mismatch")
    _validate_elf(data)
    icd_info = _regular_nosymlink(ICD_SOURCE)
    if format(stat.S_IMODE(icd_info.st_mode), "04o") != "0600":
        raise ValueError("ICD host mode mismatch")
    icd_data = ICD_SOURCE.read_bytes()
    if len(icd_data) != ICD_BYTES or hashlib.sha256(icd_data).hexdigest() != ICD_SHA256:
        raise ValueError("ICD host hash or size mismatch")
    return {
        "path": NATIVE_ARTIFACT, "name": NATIVE_NAME, "bytes": len(data), "sha256": NATIVE_SHA256,
        "mode": NATIVE_DEVICE_MODE, "hostMode": NATIVE_HOST_MODE,
        "manifest": str(NATIVE_MANIFEST), "manifestBytes": len(manifest_raw),
        "manifestSha256": NATIVE_MANIFEST_SHA256,
        "manifestMode": format(stat.S_IMODE(NATIVE_MANIFEST.stat().st_mode), "04o"),
        "source": sources,
        "icd": {"path": str(ICD_SOURCE), "bytes": len(icd_data), "sha256": ICD_SHA256, "mode": "0600"},
    }


def _private_relative(run: str) -> str:
    if RUN_RE.fullmatch(run) is None:
        raise ValueError("invalid private loader run id")
    return f"{PRIVATE_PREFIX}/{run}"


def _q(value: str) -> str:
    return shlex.quote(value)


def _copy_script(stage: str, relative: str) -> str:
    if RUN_RE.fullmatch(relative.rsplit("/", 1)[-1]) is None or relative.rsplit("/", 1)[0] != PRIVATE_PREFIX:
        raise ValueError("invalid private destination")
    directories = ["files", "files/forever", "files/forever/controls", PRIVATE_PREFIX]
    commands = ["umask 077", "set -e", "set -C"]
    for directory in directories:
        qdir = _q(directory)
        commands.append(f"test ! -L {qdir} && (test -d {qdir} || mkdir -m 700 {qdir})")
    commands.append(f"test ! -e {_q(relative)} && test ! -L {_q(relative)} && mkdir -m 700 {_q(relative)}")
    for name, mode in ((NATIVE_NAME, NATIVE_DEVICE_MODE), (ICD_NAME, "0600")):
        destination = f"{relative}/{name}"
        staged = f"{stage}/{name}"
        commands.extend([
            f"test ! -e {_q(destination)} && test ! -L {_q(destination)}",
            f"cat {_q(staged)} > {_q(destination)}",
            f"chmod {mode} {_q(destination)}",
        ])
    commands.append("printf PANVK_PRIVATE_UPLOAD_OK")
    return "; ".join(commands)


def _require_adb(result: subprocess.CompletedProcess[bytes], stage: str, limit: int = 128 * 1024) -> bytes:
    return loader.require_adb(result, stage, limit=limit)


def _device_hash(path: str, expected: str) -> None:
    raw = _require_adb(adb("exec-out", "run-as", PACKAGE, "sha256sum", path), "device artifact hash", limit=4096)
    fields = raw.decode("ascii", "strict").split()
    if len(fields) != 2 or fields[0] != expected or fields[1] != path:
        raise RuntimeError(f"device artifact hash mismatch: {path}")


def _device_mode(path: str, expected: bytes) -> None:
    raw = _require_adb(adb("exec-out", "run-as", PACKAGE, "stat", "-c", "%a", path), "device artifact mode", limit=4096)
    if raw.strip() != expected or raw.strip() not in {b"600", b"700"}:
        raise RuntimeError(f"device artifact mode mismatch: {path}")


def copy_native(native: dict[str, Any], run: str) -> tuple[str, dict[str, Any]]:
    """Upload exactly two pinned files through one owned, exclusive stage."""
    relative = _private_relative(run)
    stage = STAGE_PREFIX + uuid.uuid4().hex
    stage_files = (f"{stage}/{NATIVE_NAME}", f"{stage}/{ICD_NAME}")
    stage_created = False
    mkdir_uncertain = False
    failures: list[str] = []
    try:
        _require_adb(adb("shell", "test", "-d", "/data/local/tmp"), "staging parent")
        try:
            # Shell owns this public staging directory; run-as must traverse it
            # to copy the two public pinned binaries. Private RUN remains 0700.
            _require_adb(adb("shell", "mkdir", "-m", "755", stage), "exclusive staging directory")
        except (subprocess.TimeoutExpired, TimeoutError):
            mkdir_uncertain = True
            raise
        stage_created = True
        _require_adb(adb("push", str(native["path"]), stage_files[0], timeout=30), "probe sync upload")
        _require_adb(adb("push", str(ICD_SOURCE), stage_files[1], timeout=30), "ICD sync upload")
        _require_adb(adb("shell", "chmod", "0644", stage_files[0]), "probe staging mode")
        _require_adb(adb("shell", "chmod", "0644", stage_files[1]), "ICD staging mode")
        uploaded = adb(
            "exec-out", "run-as", PACKAGE, "/system/bin/sh", "-c",
            _copy_script(stage, relative), timeout=30,
        )
        if uploaded.returncode or uploaded.stdout != b"PANVK_PRIVATE_UPLOAD_OK" or uploaded.stderr:
            raise RuntimeError("private upload sentinel was not exact: " + json.dumps({
                "exit": uploaded.returncode,
                "stdout": uploaded.stdout[:1024].decode(errors="replace"),
                "stderr": uploaded.stderr[:1024].decode(errors="replace"),
                "payloadExecuted": False,
            }))
        destination_probe = f"{relative}/{NATIVE_NAME}"
        destination_icd = f"{relative}/{ICD_NAME}"
        _device_hash(destination_probe, native["sha256"])
        _device_hash(destination_icd, ICD_SHA256)
        _device_mode(destination_probe, b"700")
        _device_mode(destination_icd, b"600")
        return relative, {
            "path": stage, "files": list(stage_files), "stagingClean": True, "failures": [],
            "device": {
                "probe": {"path": destination_probe, "sha256": native["sha256"], "mode": "0700"},
                "icd": {"path": destination_icd, "sha256": ICD_SHA256, "mode": "0600"},
            },
        }
    finally:
        if stage_created:
            for stage_file in stage_files:
                try:
                    result = adb("shell", "rm", "-f", stage_file)
                    if result.returncode or result.stdout or result.stderr:
                        failures.append(stage_file)
                except Exception:
                    failures.append(stage_file)
            try:
                result = adb("shell", "rmdir", stage)
                if result.returncode or result.stdout or result.stderr:
                    failures.append("staging-directory")
            except Exception:
                failures.append("staging-directory")
        elif mkdir_uncertain:
            try:
                result = adb("shell", "rmdir", stage, timeout=3)
                if result.returncode or result.stdout or result.stderr:
                    failures.append("uncertain-empty-staging-directory")
            except Exception:
                failures.append("uncertain-empty-staging-directory")
        if failures:
            raise RuntimeError("owned private staging cleanup failed; no payload executed: " + json.dumps({"path": stage, "failures": failures}))


def _approved_root(private_root: str, run: str) -> bool:
    return private_root in {f"{root}/{_private_relative(run)}" for root in APP_ROOTS}


def shell_command(private_root: str, run: str) -> list[str]:
    """Return the only allowed remote exec command."""
    if not _approved_root(private_root, run):
        raise ValueError("unapproved private loader working directory")
    environment = (
        "PATH=/system/bin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
        "LD_LIBRARY_PATH=/system/lib64", f"TMPDIR={private_root}",
    )
    script = (
        f"cd {_q(private_root)} && exec /system/bin/env -i "
        + " ".join(_q(item) for item in environment)
        + f" /system/bin/linker64 {_q(private_root + '/' + NATIVE_NAME)} --run-id {_q(run)}"
    )
    return [ADB, "-s", SERIAL, "shell", "-T", "run-as", PACKAGE, "/system/bin/sh", "-c", _q(script)]


def _api_preflight() -> int:
    raw = _require_adb(adb("shell", "getprop", "ro.build.version.sdk"), "device API level", limit=128)
    value = raw.decode("ascii", "strict").strip()
    if value != str(DEVICE_API_LEVEL):
        raise RuntimeError("unexpected device API level")
    return int(value)


def parse_native(raw: bytes, run: str, private_root: str, adb_exit: int | None = None) -> dict[str, Any]:
    """Parse and classify one exact native JSON line without granting compatibility."""
    if not raw or len(raw) > MAX_NATIVE_LINE or not raw.endswith(b"\n") or raw.count(b"\n") != 1:
        raise ValueError("native output is not one bounded JSON line")
    document = json.loads(raw[:-1].decode("utf-8", "strict"), object_pairs_hook=unique_json_pairs)
    top_keys = {
        "schema", "version", "runId", "pid", "uid", "pgid", "sid", "monotonicStartNs", "monotonicEndNs", "cwd",
        "scopeValid", "icdPath", "icdBytes", "icdSha256", "icdIdentityBefore", "icdIdentityAfter",
        "icdIdentityStable", "load", "exports", "cleanup", "icdCodeLoaded", "constructorsMayHaveExecuted",
        "vulkanFunctionsInvoked", "apiComplete", "vulkan", "gpu", "triangle", "presentation", "d3d", "wow",
        "status", "failureStage", "failureDetail", "nativeExit",
    }
    top = _exact_keys(document, top_keys, "native")
    if top["schema"] != NATIVE_SCHEMA or type(top["version"]) is not int or top["version"] != 1 or top["runId"] != run or RUN_RE.fullmatch(run) is None:
        raise ValueError("native schema or run id mismatch")
    if not isinstance(top["runId"], str):
        raise ValueError("native run id is not a string")
    for name in ("pid", "uid", "pgid", "sid", "monotonicStartNs", "monotonicEndNs", "icdBytes", "nativeExit"):
        if not _is_int(top[name]):
            raise ValueError(f"native {name} is not an integer")
    if any(top[name] <= 0 for name in ("pid", "uid", "pgid", "sid", "monotonicStartNs", "monotonicEndNs")):
        raise ValueError("native identity or clock is not positive")
    if top["uid"] != UID or not 0 <= top["nativeExit"] <= 255:
        raise ValueError("native UID or exit is invalid")
    if top["monotonicEndNs"] < top["monotonicStartNs"] or top["monotonicEndNs"] - top["monotonicStartNs"] > NATIVE_DEADLINE_SECONDS * 1_000_000_000:
        raise ValueError("native timing is outside its bound")
    if not isinstance(top["cwd"], str) or top["cwd"] != private_root or not _approved_root(private_root, run):
        raise ValueError("native cwd is not the owned private root")
    _bool(top["scopeValid"], "scopeValid")
    expected_icd = f"{private_root}/{ICD_NAME}"
    if top["icdPath"] != expected_icd or top["icdBytes"] != ICD_BYTES or top["icdSha256"] != ICD_SHA256:
        raise ValueError("native ICD binding is not pinned")
    _bool(top["icdIdentityStable"], "icdIdentityStable")

    def identity(value: object, label: str) -> dict[str, Any]:
        item = _exact_keys(value, {"regular", "ownerUid", "device", "inode", "mode", "size"}, label)
        _bool(item["regular"], f"{label} regular")
        for name in ("ownerUid", "device", "inode", "mode", "size"):
            if not _is_int(item[name]) or item[name] < 0:
                raise ValueError(f"{label} {name} is invalid")
        if not item["regular"]:
            if any(item[name] != 0 for name in ("ownerUid", "device", "inode", "mode", "size")):
                raise ValueError(f"{label} failed snapshot is not zeroed")
        elif item["ownerUid"] != UID or item["mode"] != 0o600 or item["size"] != ICD_BYTES or item["device"] <= 0 or item["inode"] <= 0:
            raise ValueError(f"{label} regular snapshot is not pinned")
        return item

    before = identity(top["icdIdentityBefore"], "icdIdentityBefore")
    after = identity(top["icdIdentityAfter"], "icdIdentityAfter")
    both_valid = before["regular"] and after["regular"]
    same_identity = both_valid and all(before[name] == after[name] for name in ("ownerUid", "device", "inode", "mode", "size"))
    if top["icdIdentityStable"] != same_identity:
        raise ValueError("native identity stable flag is contradictory")

    load = _exact_keys(top["load"], {"attempted", "loaded", "dlerror"}, "native load")
    _bool(load["attempted"], "load attempted"); _bool(load["loaded"], "load loaded")
    _bounded_error(load["dlerror"], "dlerror")
    if load["loaded"] and (not load["attempted"] or load["dlerror"]):
        raise ValueError("loaded result has contradictory load state")
    if not load["attempted"] and (load["loaded"] or load["dlerror"]):
        raise ValueError("unattempted load has a result")

    exports = top["exports"]
    if not isinstance(exports, list) or len(exports) != 3:
        raise ValueError("native export list is not exactly three entries")
    for index, name in enumerate(EXPORT_NAMES):
        item = _exact_keys(exports[index], {"name", "found", "owner", "ownerExact", "error"}, f"native export {index}")
        if item["name"] != name:
            raise ValueError("native export order is not fixed")
        _bool(item["found"], f"export {name} found"); _bool(item["ownerExact"], f"export {name} ownerExact")
        if not isinstance(item["owner"], str):
            raise ValueError("export owner is not a string")
        _bounded_error(item["owner"], "export owner"); _bounded_error(item["error"], "export error")
        if item["ownerExact"] and (not item["found"] or item["owner"] != expected_icd):
            raise ValueError("export ownerExact is contradictory")
        if item["found"] and item["ownerExact"] and item["error"]:
            raise ValueError("successful export has an error")
        if not item["found"] and item["ownerExact"]:
            raise ValueError("missing export claims exact owner")

    cleanup = _exact_keys(top["cleanup"], {"dlcloseAttempted", "dlcloseOk", "complete", "errno", "dlerror"}, "native cleanup")
    for name in ("dlcloseAttempted", "dlcloseOk", "complete"):
        _bool(cleanup[name], f"cleanup {name}")
    if not _is_int(cleanup["errno"]):
        raise ValueError("cleanup errno is not an integer")
    _bounded_error(cleanup["dlerror"], "cleanup dlerror")
    if cleanup["dlcloseOk"] and not cleanup["dlcloseAttempted"]:
        raise ValueError("cleanup success without attempt")
    if not cleanup["dlcloseAttempted"] and cleanup["dlcloseOk"]:
        raise ValueError("cleanup result is contradictory")

    for name in ("icdCodeLoaded", "constructorsMayHaveExecuted", "vulkanFunctionsInvoked", "apiComplete", "vulkan", "gpu", "triangle", "presentation", "d3d", "wow"):
        _bool(top[name], name)
    if top["icdCodeLoaded"] != load["loaded"] or top["constructorsMayHaveExecuted"] != load["attempted"]:
        raise ValueError("native load flags are contradictory")
    if any(top[name] for name in ("vulkanFunctionsInvoked", "apiComplete", "vulkan", "gpu", "triangle", "presentation", "d3d", "wow")):
        raise ValueError("native output claims a broader qualification")
    if not isinstance(top["status"], str) or top["status"] not in {"PRIVATE_ICD_LOADER_OBSERVED", "LOADER_FAILED", "UNKNOWN"}:
        raise ValueError("native status is unknown")
    if not isinstance(top["failureStage"], str) or top["failureStage"] not in VALID_FAILURE_STAGES or not isinstance(top["failureDetail"], str):
        raise ValueError("native failure fields are invalid")
    _bounded_error(top["failureDetail"], "failureDetail")
    if adb_exit is not None and top["nativeExit"] != adb_exit:
        raise ValueError("host ADB exit does not equal nativeExit")

    all_exports = all(item["found"] and item["ownerExact"] and item["error"] == "" for item in exports)
    if top["status"] == "PRIVATE_ICD_LOADER_OBSERVED":
        if top["nativeExit"] != 0 or not top["scopeValid"] or not both_valid or not top["icdIdentityStable"] or not load["attempted"] or not load["loaded"] or load["dlerror"] or not all_exports or not cleanup["dlcloseAttempted"] or not cleanup["dlcloseOk"] or not cleanup["complete"] or cleanup["dlerror"] or top["failureStage"] != "none" or top["failureDetail"]:
            raise ValueError("native private-loader success gates are incomplete")
    elif top["status"] == "LOADER_FAILED":
        failed_exports = all(not item["found"] and not item["owner"] and not item["ownerExact"] and not item["error"] for item in exports)
        if top["nativeExit"] != 1 or not top["scopeValid"] or not both_valid or not top["icdIdentityStable"] or not load["attempted"] or load["loaded"] or not load["dlerror"] or not failed_exports or top["failureStage"] != "loader" or not top["failureDetail"] or cleanup["dlcloseAttempted"] or cleanup["dlcloseOk"] or not cleanup["complete"]:
            raise ValueError("native loader failure gates are incomplete")
    elif top["nativeExit"] == 0:
        raise ValueError("UNKNOWN native result must exit nonzero")
    return top


def _drain(name: str, pipe: Any, limit: int, result: dict[str, Any]) -> None:
    data = bytearray()
    total = 0
    with pipe:
        while True:
            chunk = pipe.read(8192)
            if not chunk:
                break
            total += len(chunk)
            if len(data) < limit:
                data.extend(chunk[: limit - len(data)])
    result[name] = {"data": bytes(data), "total": total, "truncated": total > limit}


def _write_exclusive(path: Path, data: bytes, mode: int = 0o600) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    fd = os.open(path, flags, mode)
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


def _external_parent(value: Path) -> Path:
    parent = value.absolute()
    repo = Path(__file__).resolve().parents[3]
    if not parent.is_dir() or parent.is_symlink() or parent.resolve().is_relative_to(repo):
        raise ValueError("evidence parent must exist outside the repo without a symlink")
    for component in (parent, *parent.parents):
        if component.is_symlink():
            raise ValueError("symlink in evidence parent")
    return parent


def _force_stop() -> dict[str, Any]:
    try:
        result = adb("shell", "am", "force-stop", PACKAGE, timeout=3)
        return {"action": "force-stop exact qualification package", "returnCode": result.returncode,
                "stdout": result.stdout.decode(errors="replace"), "stderr": result.stderr.decode(errors="replace")}
    except Exception as exc:
        return {"action": "force-stop exact qualification package", "returnCode": None, "error": str(exc)}


def _reap(process: subprocess.Popen[bytes], deadline: float) -> bool:
    try:
        process.wait(timeout=max(0.0, deadline - time.monotonic()))
        return process.returncode is not None
    except subprocess.TimeoutExpired:
        try:
            process.terminate()
        except Exception:
            return False
        try:
            process.wait(timeout=max(0.1, deadline - time.monotonic()))
            if process.returncode is not None:
                return True
        except (subprocess.TimeoutExpired, OSError):
            pass
        try:
            process.kill()
            process.wait(timeout=max(0.1, deadline - time.monotonic()))
            return process.returncode is not None
        except (subprocess.TimeoutExpired, OSError):
            return False
    except OSError:
        # Even an ADB wait failure must not abandon the HOST handle.  Do not
        # address a guest PID or process group; only this Popen identity is
        # eligible for the bounded local reap.
        try:
            process.terminate()
            process.wait(timeout=max(0.1, deadline - time.monotonic()))
            return process.returncode is not None
        except (OSError, subprocess.TimeoutExpired):
            try:
                process.kill()
                process.wait(timeout=max(0.1, deadline - time.monotonic()))
                return process.returncode is not None
            except (OSError, subprocess.TimeoutExpired):
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
    shell_status = remote_status_preflight()
    uid, app_root, selinux_context = package_preflight()
    if uid != UID or app_root not in APP_ROOTS:
        raise RuntimeError("unexpected app identity or private root")
    api_level = _api_preflight()
    run = RUN_PREFIX + uuid.uuid4().hex
    _private_relative(run)
    out = parent / ("native-" + run)
    out.mkdir(mode=0o700)
    relative, staging = copy_native(native, run)
    private_root = f"{app_root}/{relative}"
    command = shell_command(private_root, run)
    environment = ["PATH=/system/bin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data", "LD_LIBRARY_PATH=/system/lib64", f"TMPDIR={private_root}"]
    capture = {
        "schema": 1, "runId": run, "serial": SERIAL, "package": PACKAGE, "uid": uid,
        "versionCode": VERSION_CODE, "deviceApiLevel": api_level, "publishedMinimumApiLevel": PUBLISHED_MIN_API_LEVEL,
        "apiCompatibilityProven": False, "selinuxContext": selinux_context,
        "artifact": native, "icd": {"path": str(ICD_SOURCE), "bytes": ICD_BYTES, "sha256": ICD_SHA256, "mode": "0600"},
        "privateRelative": relative, "workingDirectory": private_root, "environment": environment,
        "command": command, "stagingCleanup": staging, "shellStatusPreflight": shell_status,
        "nativeSchema": NATIVE_SCHEMA, "staticProviderAudit": {"imports": 269, "runtimePass": False, "qualification": False},
        "executionStarted": False, "icdCodeLoaded": False, "constructorsMayHaveExecuted": False,
        "vulkanFunctionsInvoked": False, "apiComplete": False, "vulkan": False, "gpu": False,
        "triangle": False, "presentation": False, "d3d": False, "wow": False,
    }
    _write_exclusive(out / "capture.json", (json.dumps(capture, indent=2, sort_keys=True, default=str) + "\n").encode())

    process: subprocess.Popen[bytes] | None = None
    result: dict[str, Any] = {}
    threads: list[threading.Thread] = []
    timed_out = False
    start_error: str | None = None
    cleanup_error: str | None = None
    force_stop: dict[str, Any] | None = None
    try:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=False)
        capture["executionStarted"] = True
        capture["hostAdbClientPid"] = process.pid
        # capture.json was durably written before Popen; this second in-memory update
        # is reflected in summary without replacing the original pre-exec evidence.
        for name, pipe, limit in (("stdout", process.stdout, NATIVE_STDOUT_LIMIT), ("stderr", process.stderr, NATIVE_STDERR_LIMIT)):
            if pipe is None:
                raise RuntimeError(f"missing native {name} pipe")
            thread = threading.Thread(target=_drain, args=(name, pipe, limit, result), daemon=True)
            threads.append(thread)
            thread.start()
        try:
            process.wait(timeout=NATIVE_DEADLINE_SECONDS)
        except subprocess.TimeoutExpired:
            timed_out = True
            force_stop = _force_stop()
    except Exception as exc:
        start_error = str(exc)
        if process is not None:
            force_stop = _force_stop()
    finally:
        deadline = time.monotonic() + CLEANUP_DEADLINE_SECONDS
        if process is not None and not _reap(process, deadline):
            cleanup_error = "held HOST adb handle did not quiesce"
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
            native_document = parse_native(values["stdout"]["data"], run, private_root, process.returncode if process is not None else None)
            status = native_document["status"]
        except (UnicodeError, ValueError, json.JSONDecodeError) as exc:
            diagnostic = f"native JSON validation failed: {exc}"

    remaining_error: str | None = None
    try:
        remaining = own_processes(UID)
    except Exception as exc:
        remaining = [{"state": "UNKNOWN", "error": str(exc)}]
        remaining_error = str(exc)
    runner_error = (
        start_error is not None or timed_out or cleanup_error is not None
        or (diagnostic is not None and status == "UNKNOWN")
        or remaining_error is not None or bool(remaining)
    )
    if runner_error and force_stop is None:
        force_stop = _force_stop()
    if force_stop is not None and remaining:
        try:
            remaining_after_stop = own_processes(UID)
        except Exception as exc:
            remaining_after_stop = [{"state": "UNKNOWN", "error": str(exc)}]
        remaining = remaining_after_stop
    summary = {
        "schema": 1, "runId": run, "status": status, "diagnostic": diagnostic,
        "deviceApiLevel": api_level, "publishedMinimumApiLevel": PUBLISHED_MIN_API_LEVEL, "apiCompatibilityProven": False,
        "nativeExit": process.returncode if process is not None else None,
        "hostAdbClientPid": process.pid if process is not None else None, "timedOut": timed_out,
        "startError": start_error, "cleanupError": cleanup_error, "forceStop": force_stop,
        "remainingOwnProcesses": remaining, "stdoutBytes": values["stdout"]["total"], "stderrBytes": values["stderr"]["total"],
        "stdoutTruncated": values["stdout"]["truncated"], "stderrTruncated": values["stderr"]["truncated"],
        "completePipes": not any(thread.is_alive() for thread in threads) and set(result) == {"stdout", "stderr"},
        "native": native_document, "icdCodeLoaded": bool(native_document and native_document.get("icdCodeLoaded") is True),
        "constructorsMayHaveExecuted": bool(native_document and native_document.get("constructorsMayHaveExecuted") is True),
        "vulkanFunctionsInvoked": False, "apiComplete": False, "vulkan": False, "gpu": False, "triangle": False,
        "presentation": False, "d3d": False, "wow": False, "outputErrors": output_errors,
        "boundary": "private ICD loader observation only; not API compatibility, GPU, Vulkan, WSI, D3D, WoW, or readiness",
        "output": str(out),
    }
    _write_best_effort(out, "summary.json", (json.dumps(summary, indent=2, sort_keys=True, default=str) + "\n").encode(), output_errors)
    print(json.dumps(summary, indent=2, sort_keys=True, default=str))
    pipeline_pass = status == "PRIVATE_ICD_LOADER_OBSERVED" and process is not None and process.returncode == 0 and force_stop is None and not output_errors and not remaining
    return 0 if pipeline_pass else 1


if __name__ == "__main__":
    raise SystemExit(main())
