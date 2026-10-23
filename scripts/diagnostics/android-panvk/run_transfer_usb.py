#!/usr/bin/env python3
"""Run the approved PanVK control-B transfer/readback observation once.

This is a deliberately narrow HOST binding.  It uploads only the pinned
native probe plus the pinned ICD into a fresh private RUN directory, starts
the probe once with a sanitized environment, captures bounded output, and
validates the strict native JSON.  A successful result is only the native
``TRANSFER_READBACK_OBSERVED`` control witness; it is not a runtime,
rendering, shader, presentation, D3D, or WoW qualification.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
import os
import re
import shlex
import stat
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path
from typing import Any


_BASE_PATH = Path(__file__).parents[1] / "android-vtest" / "run_loader_usb.py"
_BASE_SPEC = importlib.util.spec_from_file_location("panvk_transfer_runner_base", _BASE_PATH)
if _BASE_SPEC is None or _BASE_SPEC.loader is None:  # pragma: no cover
    raise ImportError(f"cannot load {_BASE_PATH}")
base = importlib.util.module_from_spec(_BASE_SPEC)
sys.modules[_BASE_SPEC.name] = base
_BASE_SPEC.loader.exec_module(base)


ADB = base.ADB
SERIAL_ENV_VAR = "WOWFOREVER_ADB_SERIAL"
SERIAL = os.environ.get(SERIAL_ENV_VAR, "")
PACKAGE = "com.wowforever.arm64probe"
UID = 10330
VERSION_CODE = 41
DEVICE_API_LEVEL = 34
PUBLISHED_MIN_API_LEVEL = 35

SOURCE_ROOT = Path(__file__).parent
ARTIFACT_ROOT = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/panvk-transfer-v1")
NATIVE_ARTIFACT = ARTIFACT_ROOT / "PanvkTransfer"
NATIVE_MANIFEST = ARTIFACT_ROOT / "manifest.json"
NATIVE_NAME = "PanvkTransfer"
ICD_NAME = "libvulkan_panfrost.so"
ICD_SOURCE = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/panvk-audit-beta17/icd/libvulkan_panfrost.so")
HEADER_SOURCE = Path("/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include/vulkan/vulkan_core.h")
HELPER_SOURCE = SOURCE_ROOT / "PanvkPrivateLoader.c"

# Pinned from the Gate B build (parent repinned after the native artifact was
# produced). Zeros are rejected by _fixed_hash and by the manifest/artifact
# size checks below, so no device operation runs on drift.
NATIVE_ARTIFACT_BYTES = 53280
NATIVE_ARTIFACT_SHA256 = "be1daa4089c0f68afc2d65d12b36614fd6af7845b3c79c1f16230389eaf3976e"
NATIVE_MANIFEST_SHA256 = "a3ac7f96fa17084170d33dace9e2150d591dc7dd10a857850e852811da181aa5"
# Aliases mirroring run_device_usb.py so the load_native() gate below keeps
# the reviewed structure.
NATIVE_BYTES = NATIVE_ARTIFACT_BYTES
NATIVE_SHA256 = NATIVE_ARTIFACT_SHA256
NATIVE_HOST_MODE = "0755"
NATIVE_DEVICE_MODE = "0700"
ICD_BYTES = 31142504
ICD_SHA256 = "5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9"
HEADER_SHA256 = "c274cdac1e77755ad2c9e12a1d4d24a91e1f72dad138bfcd8b4a54279b9c2e71"
HELPER_SHA256 = "cc8ea0c352ff582de80ef493d6060504a5bd1b11057604a1d55dd59875b3a4b7"
SOURCE_PINS: dict[str, dict[str, Any]] = {
    "PanvkTransfer.c": {"bytes": 113161, "sha256": "04214af240ff8073ef0570b1990a0058dcad1612e3899b0514bded4feb7ed3b6"},
    "README-transfer.md": {"bytes": 13456, "sha256": "6805592111c67d50f1283fd1fd269b47de0eb03d0f61fab183a62fd5ca0de2be"},
    "build_transfer_probe.sh": {"bytes": 6416, "sha256": "30b4c4075ae4addd1de902fe56ec9d8c5696dc876851e022fb09c968ac71888e"},
}
NATIVE_SCHEMA = "wowforever.android-panvk-transfer"
BUILD_SCHEMA = "wowforever.android-panvk-transfer-build/version1"
RUN_RE = re.compile(r"^PVK-[0-9a-f]{32}$")
PRIVATE_PREFIX = "files/forever/controls/panvk-loader"
STAGE_PREFIX = "/data/local/tmp/wowforever-panvk-transfer-"
NATIVE_STDOUT_LIMIT = 64 * 1024
NATIVE_STDERR_LIMIT = 64 * 1024
NATIVE_DEADLINE_SECONDS = 10
CLEANUP_DEADLINE_SECONDS = 5
MAX_NATIVE_LINE = 64 * 1024
ERROR_BYTES = 512
HEX64 = re.compile(r"^[0-9a-f]{64}$")
# The native probe hashes the 4 KiB readback with 64-bit FNV-1a, emitted as 16
# lowercase hex characters (see README-transfer.md).
HEX16 = re.compile(r"^[0-9a-f]{16}$")
APP_ROOTS = {f"/data/user/0/{PACKAGE}", f"/data/data/{PACKAGE}"}
EXPORT_NAMES = (
    "vk_icdGetInstanceProcAddr",
    "vk_icdGetPhysicalDeviceProcAddr",
    "vk_icdNegotiateLoaderICDInterfaceVersion",
)
FUNCTION_NAMES = (
    "vkEnumerateInstanceVersion", "vkCreateInstance", "vkDestroyInstance",
    "vkEnumeratePhysicalDevices", "vkGetPhysicalDeviceProperties",
    "vkGetPhysicalDeviceQueueFamilyProperties", "vkGetPhysicalDeviceMemoryProperties",
    "vkCreateDevice", "vkDestroyDevice", "vkGetDeviceProcAddr", "vkGetDeviceQueue",
    "vkCreateBuffer", "vkDestroyBuffer", "vkGetBufferMemoryRequirements",
    "vkAllocateMemory", "vkFreeMemory", "vkBindBufferMemory", "vkMapMemory",
    "vkUnmapMemory", "vkFlushMappedMemoryRanges", "vkInvalidateMappedMemoryRanges",
    "vkCreateCommandPool", "vkDestroyCommandPool", "vkAllocateCommandBuffers",
    "vkFreeCommandBuffers", "vkBeginCommandBuffer", "vkEndCommandBuffer",
    "vkCmdFillBuffer", "vkCmdPipelineBarrier", "vkCreateFence", "vkDestroyFence",
    "vkWaitForFences", "vkQueueSubmit",
)
TRANSFER_BYTES = 4096
TRANSFER_DWORDS = 1024
TRANSFER_PATTERN = 0xA1B2C3D4
TRANSFER_FENCE_TIMEOUT_NS = 1_000_000_000
TRANSFER_ALLOCATION_CAP_BYTES = 256 * 1024
# VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, the fixed pool flag used by
# the native probe (see README-transfer.md).
TRANSFER_POOL_FLAGS = 2
VALID_STATUSES = {"TRANSFER_READBACK_OBSERVED", "VK_ERROR", "NO_QUEUE", "NO_MEMORY_TYPE", "TIMEOUT", "LOADER_FAILED", "UNKNOWN"}
VALID_FAILURE_STAGES = {
    "none", "arguments", "scope", "path", "identity", "hash", "clock", "loader",
    "export", "owner", "interface", "api", "instance", "physical", "queues",
    "no_queue", "memory_type", "vulkan", "device", "queue", "buffer", "memory",
    "bind", "map", "pool", "command", "record", "fence", "submit", "wait",
    "invalidate", "readback", "timeout", "result", "cleanup", "output",
}


def unique_json_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON field: {key}")
        result[key] = value
    return result


def adb(*args: str, timeout: int = 15) -> subprocess.CompletedProcess[bytes]:
    """Use the shared implementation with this runner's fixed serial policy."""
    return base.adb(*args, timeout=timeout)


def require_adb(result: subprocess.CompletedProcess[bytes], stage: str, limit: int = 128 * 1024) -> bytes:
    return base.require_adb(result, stage, limit=limit)


def remote_status_preflight() -> dict[str, Any]:
    return base.remote_status_preflight()


def package_preflight() -> tuple[int, str, str]:
    # This is intentionally the reviewed shared API, not a duplicate check.
    return base._package_preflight()


def own_processes(uid: int = UID) -> list[dict[str, Any]]:
    return base.own_processes(uid)


def _is_int(value: object) -> bool:
    return type(value) is int


def _bool(value: object, label: str) -> None:
    if type(value) is not bool:
        raise ValueError(f"{label} is not boolean")


def _exact(value: object, keys: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        raise ValueError(f"{label} schema mismatch")
    return value


def _bounded(value: object, label: str, limit: int = ERROR_BYTES) -> None:
    if not isinstance(value, str) or len(value.encode("utf-8")) > limit:
        raise ValueError(f"{label} exceeds {limit} UTF-8 bytes")


def _digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _regular_nosymlink(path: Path) -> os.stat_result:
    info = path.lstat()
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        raise ValueError(f"artifact is not a regular non-symlink: {path}")
    return info


def _fixed_hash(value: object, label: str) -> str:
    if not isinstance(value, str) or HEX64.fullmatch(value) is None or value == "0" * 64:
        raise ValueError(f"{label} pin is absent or invalid")
    return value


def load_native() -> dict[str, Any]:
    """Validate the complete current manifest and all host-side inputs."""
    _fixed_hash(NATIVE_ARTIFACT_SHA256, "native artifact pin")
    _fixed_hash(NATIVE_MANIFEST_SHA256, "native manifest pin")
    if not NATIVE_ARTIFACT_BYTES:
        raise ValueError("native artifact size pin is absent")
    manifest_info = _regular_nosymlink(NATIVE_MANIFEST)
    manifest_raw = NATIVE_MANIFEST.read_bytes()
    if manifest_info.st_size != len(manifest_raw) or len(manifest_raw) > 128 * 1024 or _digest(NATIVE_MANIFEST) != NATIVE_MANIFEST_SHA256:
        raise ValueError("native manifest hash or size mismatch")
    manifest = json.loads(manifest_raw.decode("ascii", "strict"), object_pairs_hook=unique_json_pairs)
    if not isinstance(manifest, dict) or manifest.get("schema") != BUILD_SCHEMA:
        raise ValueError("native manifest schema mismatch")
    if manifest.get("file") != NATIVE_NAME or manifest.get("size") != NATIVE_BYTES or manifest.get("sha256") != NATIVE_SHA256:
        raise ValueError("native artifact manifest pin mismatch")
    if manifest.get("mode") != NATIVE_HOST_MODE or manifest.get("interpreter") != "/system/bin/linker64":
        raise ValueError("native mode or interpreter pin mismatch")
    if manifest.get("elfType") != "ET_DYN" or manifest.get("machine") != "AArch64" or manifest.get("pie") is not True:
        raise ValueError("native ELF pin mismatch")
    if manifest.get("ndk") != "24.0.8215888" or manifest.get("target") != "aarch64-linux-android29":
        raise ValueError("native toolchain pin mismatch")
    if manifest.get("neededLibraries") != ["libc.so", "libdl.so"] or manifest.get("linkLibraries") != ["c", "dl"]:
        raise ValueError("native dependency pin mismatch")
    if manifest.get("sourceBytes") != {name: item["bytes"] for name, item in SOURCE_PINS.items()} or manifest.get("sourceSha256") != {name: item["sha256"] for name, item in SOURCE_PINS.items()}:
        raise ValueError("native source pin mismatch")
    header = manifest.get("header")
    if not isinstance(header, dict) or header.get("path") != str(HEADER_SOURCE) or header.get("bytes") != 1060766 or header.get("sha256") != HEADER_SHA256:
        raise ValueError("Vulkan header pin mismatch")
    helper = manifest.get("helperSource")
    if not isinstance(helper, dict) or helper.get("path") != str(HELPER_SOURCE) or helper.get("bytes") != 41294 or helper.get("sha256") != HELPER_SHA256:
        raise ValueError("loader helper pin mismatch")
    icd = manifest.get("icdPin")
    if not isinstance(icd, dict) or icd.get("sourcePath") != str(ICD_SOURCE) or icd.get("bytes") != ICD_BYTES or icd.get("sha256") != ICD_SHA256 or icd.get("mode") != "0600":
        raise ValueError("ICD manifest pin mismatch")
    source: dict[str, dict[str, Any]] = {}
    for name, pin in SOURCE_PINS.items():
        _fixed_hash(pin["sha256"], f"source {name}")
        if not pin["bytes"]:
            raise ValueError(f"source pin is absent: {name}")
        path = SOURCE_ROOT / name
        info = _regular_nosymlink(path)
        if info.st_size != pin["bytes"] or _digest(path) != pin["sha256"]:
            raise ValueError(f"source pin mismatch: {name}")
        source[name] = {"path": str(path), **pin, "mode": format(stat.S_IMODE(info.st_mode), "04o")}
    for path, expected, label in ((HEADER_SOURCE, HEADER_SHA256, "header"), (HELPER_SOURCE, HELPER_SHA256, "helper")):
        info = _regular_nosymlink(path)
        if _digest(path) != expected or info.st_size <= 0:
            raise ValueError(f"{label} pin mismatch")
    native_info = _regular_nosymlink(NATIVE_ARTIFACT)
    native_data = NATIVE_ARTIFACT.read_bytes()
    if stat.S_IMODE(native_info.st_mode) != int(NATIVE_HOST_MODE, 8) or len(native_data) != NATIVE_BYTES or _digest(NATIVE_ARTIFACT) != NATIVE_SHA256:
        raise ValueError("native artifact hash, size, or mode mismatch")
    icd_info = _regular_nosymlink(ICD_SOURCE)
    if stat.S_IMODE(icd_info.st_mode) != 0o600 or icd_info.st_size != ICD_BYTES or _digest(ICD_SOURCE) != ICD_SHA256:
        raise ValueError("ICD hash, size, or mode mismatch")
    return {
        "path": NATIVE_ARTIFACT, "name": NATIVE_NAME, "bytes": NATIVE_BYTES, "sha256": NATIVE_SHA256,
        "mode": NATIVE_DEVICE_MODE, "hostMode": NATIVE_HOST_MODE, "manifest": str(NATIVE_MANIFEST),
        "manifestBytes": len(manifest_raw), "manifestSha256": NATIVE_MANIFEST_SHA256,
        "source": source, "header": {"path": str(HEADER_SOURCE), "bytes": 1060766, "sha256": HEADER_SHA256},
        "helper": {"path": str(HELPER_SOURCE), "bytes": 41294, "sha256": HELPER_SHA256},
        "icd": {"path": str(ICD_SOURCE), "bytes": ICD_BYTES, "sha256": ICD_SHA256, "mode": "0600"},
    }


def _private_relative(run: str) -> str:
    if RUN_RE.fullmatch(run) is None:
        raise ValueError("invalid run id")
    return f"{PRIVATE_PREFIX}/{run}"


def _q(value: str) -> str:
    return shlex.quote(value)


def _copy_script(stage: str, relative: str) -> str:
    if RUN_RE.fullmatch(relative.rsplit("/", 1)[-1]) is None or relative.rsplit("/", 1)[0] != PRIVATE_PREFIX:
        raise ValueError("invalid private destination")
    commands = ["umask 077", "set -e", "set -C"]
    for directory in ("files", "files/forever", "files/forever/controls", PRIVATE_PREFIX):
        qdir = _q(directory)
        commands.append(f"test ! -L {qdir} && (test -d {qdir} || mkdir -m 700 {qdir})")
    commands.append(f"test ! -e {_q(relative)} && test ! -L {_q(relative)} && mkdir -m 700 {_q(relative)}")
    for name, mode in ((NATIVE_NAME, "0700"), (ICD_NAME, "0600")):
        destination = f"{relative}/{name}"
        commands.extend([
            f"test ! -e {_q(destination)} && test ! -L {_q(destination)}",
            f"cat {_q(stage + '/' + name)} > {_q(destination)}",
            f"chmod {mode} {_q(destination)}",
        ])
    commands.append("printf PANVK_TRANSFER_UPLOAD_OK")
    return "; ".join(commands)


def _device_hash(path: str, expected: str) -> None:
    raw = require_adb(adb("exec-out", "run-as", PACKAGE, "sha256sum", path), "device artifact hash", 4096)
    if raw.decode("ascii", "strict").split() != [expected, path]:
        raise RuntimeError(f"device artifact hash mismatch: {path}")


def _device_mode(path: str, expected: bytes) -> None:
    if require_adb(adb("exec-out", "run-as", PACKAGE, "stat", "-c", "%a", path), "device artifact mode", 4096).strip() != expected:
        raise RuntimeError(f"device artifact mode mismatch: {path}")


def copy_native(native: dict[str, Any], run: str) -> tuple[str, dict[str, Any]]:
    """Upload only the pinned pair through a unique, exclusive public stage."""
    relative = _private_relative(run)
    stage = STAGE_PREFIX + uuid.uuid4().hex
    stage_files = (f"{stage}/{NATIVE_NAME}", f"{stage}/{ICD_NAME}")
    made = False
    uncertain = False
    failures: list[str] = []
    try:
        require_adb(adb("shell", "test", "-d", "/data/local/tmp"), "staging parent")
        try:
            require_adb(adb("shell", "mkdir", "-m", "755", stage), "exclusive staging directory")
            made = True
        except (subprocess.TimeoutExpired, TimeoutError):
            uncertain = True
            raise
        require_adb(adb("push", str(native["path"]), stage_files[0], timeout=30), "probe upload")
        require_adb(adb("push", str(ICD_SOURCE), stage_files[1], timeout=30), "ICD upload")
        require_adb(adb("shell", "chmod", "0644", stage_files[0]), "probe staging mode")
        require_adb(adb("shell", "chmod", "0644", stage_files[1]), "ICD staging mode")
        uploaded = adb("exec-out", "run-as", PACKAGE, "/system/bin/sh", "-c", _copy_script(stage, relative), timeout=30)
        if uploaded.returncode or uploaded.stdout != b"PANVK_TRANSFER_UPLOAD_OK" or uploaded.stderr:
            raise RuntimeError("private upload sentinel was not exact")
        probe = f"{relative}/{NATIVE_NAME}"
        icd = f"{relative}/{ICD_NAME}"
        _device_hash(probe, native["sha256"]); _device_hash(icd, ICD_SHA256)
        _device_mode(probe, b"700"); _device_mode(icd, b"600")
        return relative, {"path": stage, "stagingClean": True, "failures": [], "device": {
            "probe": {"path": probe, "sha256": native["sha256"], "mode": "0700"},
            "icd": {"path": icd, "sha256": ICD_SHA256, "mode": "0600"},
        }}
    finally:
        if made:
            for path in stage_files:
                try:
                    result = adb("shell", "rm", "-f", path, timeout=3)
                    if result.returncode or result.stdout or result.stderr: failures.append(path)
                except Exception:
                    failures.append(path)
            try:
                result = adb("shell", "rmdir", stage, timeout=3)
                if result.returncode or result.stdout or result.stderr: failures.append("staging-directory")
            except Exception:
                failures.append("staging-directory")
        elif uncertain:
            try:
                result = adb("shell", "rmdir", stage, timeout=3)
                if result.returncode or result.stdout or result.stderr: failures.append("uncertain-empty-staging-directory")
            except Exception:
                failures.append("uncertain-empty-staging-directory")
        if failures:
            raise RuntimeError("owned staging cleanup failed: " + json.dumps(failures))


def shell_command(private_root: str, run: str) -> list[str]:
    if private_root not in {f"{root}/{_private_relative(run)}" for root in APP_ROOTS}:
        raise ValueError("unapproved private working directory")
    env = ("PATH=/system/bin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data", "LD_LIBRARY_PATH=/system/lib64", f"TMPDIR={private_root}")
    script = f"cd {_q(private_root)} && exec /system/bin/env -i " + " ".join(_q(value) for value in env) + f" /system/bin/linker64 {_q(private_root + '/' + NATIVE_NAME)} --run-id {_q(run)}"
    return [ADB, "-s", SERIAL, "shell", "-T", "run-as", PACKAGE, "/system/bin/sh", "-c", _q(script)]


def _api_preflight() -> int:
    raw = require_adb(adb("shell", "getprop", "ro.build.version.sdk"), "device API level", 128)
    value = raw.decode("ascii", "strict").strip()
    if value != str(DEVICE_API_LEVEL):
        raise RuntimeError("unexpected device API level")
    return int(value)


def _identity(value: object, label: str) -> dict[str, Any]:
    item = _exact(value, {"regular", "ownerUid", "device", "inode", "mode", "size"}, label)
    _bool(item["regular"], f"{label}.regular")
    for key in ("ownerUid", "device", "inode", "mode", "size"):
        if not _is_int(item[key]) or item[key] < 0: raise ValueError(f"invalid {label}.{key}")
    if item["regular"]:
        if item["ownerUid"] != UID or item["mode"] != 0o600 or item["size"] != ICD_BYTES or item["device"] <= 0 or item["inode"] <= 0:
            raise ValueError(f"unpinned {label}")
    elif any(item[key] != 0 for key in ("ownerUid", "device", "inode", "mode", "size")):
        raise ValueError(f"failed {label} is not zeroed")
    return item


def _valid_vk_version(value: object) -> bool:
    return _is_int(value) and value >= (1 << 22) and (value >> 29) == 0 and ((value >> 22) & 0x7F) == 1


def _validate_result_int(value: object, label: str) -> None:
    if not _is_int(value): raise ValueError(f"{label} is not an integer")


def _uint(value: object, label: str) -> None:
    if not _is_int(value) or value < 0: raise ValueError(f"{label} is not a non-negative integer")


def _eligible(flags: int, queue_count: int) -> str | None:
    if queue_count < 1: return None
    if flags & (1 << 2): return "transfer"
    if flags & (1 << 0): return "graphics"
    if flags & (1 << 1): return "compute"
    return None


def _power_of_two(value: int) -> bool:
    return value > 0 and (value & (value - 1)) == 0


def parse_native(raw: bytes, run: str, private_root: str, adb_exit: int | None = None) -> dict[str, Any]:
    """Strictly consume the transfer schema and recompute success gates."""
    if not raw or len(raw) >= MAX_NATIVE_LINE or not raw.endswith(b"\n") or raw.count(b"\n") != 1:
        raise ValueError("native output is not one bounded JSON line")
    top = _exact(json.loads(raw[:-1].decode("utf-8", "strict"), object_pairs_hook=unique_json_pairs), {
        "schema", "version", "runId", "pid", "uid", "pgid", "sid", "monotonicStartNs", "monotonicEndNs", "cwd", "scopeValid",
        "icdPath", "icdBytes", "icdSha256", "icdIdentityBefore", "icdIdentityAfter", "icdIdentityStable", "load", "exports", "interface",
        "functions", "api", "instance", "physical", "queues", "selection", "device", "queue", "transfer", "timing", "cleanup", "icdCodeLoaded",
        "constructorsMayHaveExecuted", "vulkanFunctionsInvoked", "apiComplete", "gpuWorkload", "triangle", "presentation", "d3d", "wow",
        "status", "failureStage", "failureDetail", "nativeExit",
    }, "native")
    if top["schema"] != NATIVE_SCHEMA or type(top["version"]) is not int or top["version"] != 1 or top["runId"] != run or RUN_RE.fullmatch(run) is None:
        raise ValueError("native schema or run id mismatch")
    for key in ("pid", "uid", "pgid", "sid", "monotonicStartNs", "monotonicEndNs", "icdBytes", "nativeExit"):
        if not _is_int(top[key]): raise ValueError(f"native {key} is not an integer")
    if any(top[key] <= 0 for key in ("pid", "uid", "pgid", "sid", "monotonicStartNs", "monotonicEndNs")) or top["uid"] != UID or not 0 <= top["nativeExit"] <= 255:
        raise ValueError("native identity, UID, clock, or exit is invalid")
    if top["monotonicEndNs"] < top["monotonicStartNs"] or top["monotonicEndNs"] - top["monotonicStartNs"] > NATIVE_DEADLINE_SECONDS * 1_000_000_000:
        raise ValueError("native timing is outside bound")
    if top["cwd"] != private_root or private_root not in {f"{root}/{_private_relative(run)}" for root in APP_ROOTS}:
        raise ValueError("native cwd is not owned")
    _bool(top["scopeValid"], "scopeValid")
    if top["icdPath"] != f"{private_root}/{ICD_NAME}" or top["icdBytes"] != ICD_BYTES or top["icdSha256"] != ICD_SHA256:
        raise ValueError("native ICD binding is not pinned")
    before = _identity(top["icdIdentityBefore"], "icdIdentityBefore"); after = _identity(top["icdIdentityAfter"], "icdIdentityAfter")
    _bool(top["icdIdentityStable"], "icdIdentityStable")
    same_identity = before["regular"] and after["regular"] and all(before[key] == after[key] for key in ("ownerUid", "device", "inode", "mode", "size"))
    if top["icdIdentityStable"] != same_identity: raise ValueError("contradictory ICD identity witness")
    load = _exact(top["load"], {"attempted", "loaded", "dlerror"}, "load")
    _bool(load["attempted"], "load.attempted"); _bool(load["loaded"], "load.loaded"); _bounded(load["dlerror"], "load.dlerror")
    if load["loaded"] and (not load["attempted"] or load["dlerror"]): raise ValueError("contradictory load witness")
    if not load["attempted"] and (load["loaded"] or load["dlerror"]): raise ValueError("unattempted load has result")
    exports = top["exports"]
    if not isinstance(exports, list) or len(exports) != 3: raise ValueError("exports are not exactly three")
    for index, name in enumerate(EXPORT_NAMES):
        item = _exact(exports[index], {"name", "found", "owner", "ownerExact", "error"}, f"export {index}")
        if item["name"] != name: raise ValueError("export order mismatch")
        _bool(item["found"], "export.found"); _bool(item["ownerExact"], "export.ownerExact"); _bounded(item["owner"], "export.owner"); _bounded(item["error"], "export.error")
        if item["ownerExact"] and (not item["found"] or item["owner"] != top["icdPath"]): raise ValueError("contradictory export owner")
        if item["found"] and item["ownerExact"] and item["error"]: raise ValueError("successful export has error")
    interface = _exact(top["interface"], {"input", "result", "output", "supported"}, "interface")
    for key in ("input", "result", "output"): _validate_result_int(interface[key], f"interface.{key}")
    _bool(interface["supported"], "interface.supported")
    if interface["input"] == 0:
        if interface["result"] != 0 or interface["output"] != 0 or interface["supported"]: raise ValueError("unattempted interface witness contradiction")
    elif interface["input"] != 7 or interface["supported"] != (interface["result"] == 0 and 2 <= interface["output"] <= 7):
        raise ValueError("interface witness contradiction")
    functions = top["functions"]
    if not isinstance(functions, list) or len(functions) != 33: raise ValueError("functions are not exactly thirty-three")
    for index, name in enumerate(FUNCTION_NAMES):
        item = _exact(functions[index], {"name", "resolved", "owner", "ownerExact", "error"}, f"function {index}")
        if item["name"] != name: raise ValueError("function order mismatch")
        _bool(item["resolved"], "function.resolved"); _bool(item["ownerExact"], "function.ownerExact"); _bounded(item["owner"], "function.owner"); _bounded(item["error"], "function.error")
        if item["ownerExact"] and (not item["resolved"] or item["owner"] != top["icdPath"]): raise ValueError("contradictory function owner")
        if item["resolved"] and item["ownerExact"] and item["error"]: raise ValueError("resolved function has error")
    api = _exact(top["api"], {"enumerateInstanceVersionFound", "ownerExact", "legacy10", "result", "reported", "requested"}, "api")
    for key in ("enumerateInstanceVersionFound", "ownerExact", "legacy10"): _bool(api[key], f"api.{key}")
    for key in ("result", "reported", "requested"): _validate_result_int(api[key], f"api.{key}")
    if api["enumerateInstanceVersionFound"] and not api["ownerExact"] and api["result"] == 0: raise ValueError("API PFN owner is not exact")
    if not api["enumerateInstanceVersionFound"]:
        if api["legacy10"] != (api["ownerExact"] and api["result"] == 0 and api["reported"] == (1 << 22) and api["requested"] == (1 << 22)): raise ValueError("legacy API witness mismatch")
    elif api["result"] == 0:
        if not _valid_vk_version(api["reported"]): raise ValueError("reported API version invalid")
        major = (api["reported"] >> 22) & 0x7F; minor = (api["reported"] >> 12) & 0x3FF
        expected = (major << 22) | (min(minor, 3) << 12)
        if api["requested"] != expected: raise ValueError("requested API is not capped at Vulkan 1.3")
    instance = _exact(top["instance"], {"createAttempted", "created", "handleReturnedOnError", "result", "layerCount", "extensionCount", "allocatorNull"}, "instance")
    for key in ("createAttempted", "created", "handleReturnedOnError", "allocatorNull"): _bool(instance[key], f"instance.{key}")
    for key in ("result", "layerCount", "extensionCount"): _validate_result_int(instance[key], f"instance.{key}")
    if instance["created"] and (not instance["createAttempted"] or instance["result"] != 0): raise ValueError("created instance witness contradiction")
    if instance["handleReturnedOnError"] and (not instance["createAttempted"] or instance["result"] == 0): raise ValueError("instance error-handle witness contradiction")
    if instance["layerCount"] != 0 or instance["extensionCount"] != 0 or not instance["allocatorNull"]: raise ValueError("instance policy mismatch")
    physical = _exact(top["physical"], {"reportedCount", "returnedCount", "retainedCount", "capacity", "countResult", "fillResult", "complete", "vendorId", "deviceId", "name", "propertiesValid", "targetExact"}, "physical")
    for key in ("reportedCount", "returnedCount", "retainedCount", "capacity", "vendorId", "deviceId"):
        if not _is_int(physical[key]) or physical[key] < 0: raise ValueError(f"physical.{key} invalid")
    for key in ("countResult", "fillResult"): _validate_result_int(physical[key], f"physical.{key}")
    _bool(physical["complete"], "physical.complete"); _bool(physical["propertiesValid"], "physical.propertiesValid"); _bool(physical["targetExact"], "physical.targetExact")
    if not isinstance(physical["name"], str) or len(physical["name"].encode()) > 256: raise ValueError("physical name invalid")
    if physical["capacity"] != 8 or physical["retainedCount"] > physical["capacity"] or physical["returnedCount"] < physical["retainedCount"]: raise ValueError("physical capacity/count mismatch")
    if physical["complete"] and not (physical["countResult"] == physical["fillResult"] == 0 and physical["reportedCount"] == physical["returnedCount"] == physical["retainedCount"] <= physical["capacity"]): raise ValueError("physical complete witness contradiction")
    if physical["targetExact"] != (physical["propertiesValid"] and physical["vendorId"] == 0x13B5 and physical["deviceId"] == 0xA8670000 and physical["name"] == "Mali-G610 MC6"): raise ValueError("physical target witness contradiction")
    queues = _exact(top["queues"], {"reportedCount", "returnedCount", "retainedCount", "capacity", "complete", "families"}, "queues")
    for key in ("reportedCount", "returnedCount", "retainedCount", "capacity"):
        if not _is_int(queues[key]) or queues[key] < 0: raise ValueError(f"queues.{key} invalid")
    _bool(queues["complete"], "queues.complete")
    families = queues["families"]
    if not isinstance(families, list) or len(families) > 16 or queues["capacity"] != 16 or len(families) != queues["retainedCount"] or queues["retainedCount"] > min(queues["returnedCount"], 16): raise ValueError("queue capacity mismatch")
    for index, family in enumerate(families):
        item = _exact(family, {"index", "flags", "queueCount"}, f"queue family {index}")
        for key in ("index", "flags", "queueCount"):
            if not _is_int(item[key]) or item[key] < 0: raise ValueError(f"queue family {key} invalid")
        if item["index"] != index: raise ValueError("queue families are not sorted")
    if queues["complete"] and not (queues["reportedCount"] == queues["returnedCount"] == queues["retainedCount"] == len(families) <= 16): raise ValueError("queue complete witness contradiction")
    selection = _exact(top["selection"], {"selected", "familyIndex", "flags", "queueCount", "kind"}, "selection")
    _bool(selection["selected"], "selection.selected")
    for key in ("familyIndex", "flags", "queueCount"):
        if not _is_int(selection[key]) or selection[key] < 0: raise ValueError(f"selection.{key} invalid")
    if not isinstance(selection["kind"], str) or selection["kind"] not in {"", "transfer", "graphics", "compute"}: raise ValueError("selection kind invalid")
    expected_selection: tuple[int, str] | None = None
    for index, family in enumerate(families):
        kind = _eligible(family["flags"], family["queueCount"])
        if kind is not None: expected_selection = (index, kind); break
    if selection["selected"]:
        if not queues["complete"] or expected_selection is None or selection["familyIndex"] != expected_selection[0] or selection["kind"] != expected_selection[1]: raise ValueError("selection is not first eligible family")
        family = families[selection["familyIndex"]]
        if selection["flags"] != family["flags"] or selection["queueCount"] != family["queueCount"]: raise ValueError("selection metadata mismatch")
    elif queues["complete"] and expected_selection is not None: raise ValueError("eligible complete queue was not selected")
    device = _exact(top["device"], {"createAttempted", "created", "handleReturnedOnError", "result", "queueCreateCount", "queueFamilyIndex", "queueCount", "priority", "featuresNull", "layerCount", "extensionCount", "allocatorNull"}, "device")
    for key in ("createAttempted", "created", "handleReturnedOnError", "featuresNull", "allocatorNull"): _bool(device[key], f"device.{key}")
    for key in ("result", "queueCreateCount", "queueFamilyIndex", "queueCount", "layerCount", "extensionCount"): _validate_result_int(device[key], f"device.{key}")
    if not isinstance(device["priority"], (int, float)) or isinstance(device["priority"], bool) or (isinstance(device["priority"], float) and not math.isfinite(device["priority"])): raise ValueError("device priority invalid")
    if device["created"] and (not device["createAttempted"] or device["result"] != 0): raise ValueError("created device witness contradiction")
    if device["handleReturnedOnError"] and (not device["createAttempted"] or device["result"] >= 0): raise ValueError("device error-handle witness contradiction")
    if device["layerCount"] != 0 or device["extensionCount"] != 0 or not device["featuresNull"] or not device["allocatorNull"]: raise ValueError("device policy mismatch")
    queue = _exact(top["queue"], {"getAttempted", "index", "nonNull", "observed"}, "queue")
    for key in ("getAttempted", "nonNull", "observed"): _bool(queue[key], f"queue.{key}")
    if not _is_int(queue["index"]) or queue["index"] < 0: raise ValueError("queue index invalid")
    if queue["nonNull"] and not queue["getAttempted"] or queue["observed"] != queue["nonNull"]: raise ValueError("queue witness contradiction")
    transfer = _exact(top["transfer"], {"buffer", "memoryRequirements", "memoryType", "allocation", "bind", "map", "hostWrite", "commandPool", "commandBuffer", "record", "fence", "submit", "invalidate", "readback"}, "transfer")
    tbuffer = _exact(transfer["buffer"], {"createAttempted", "created", "handleReturnedOnError", "result", "size", "usage", "sharingMode"}, "transfer.buffer")
    for key in ("createAttempted", "created", "handleReturnedOnError"): _bool(tbuffer[key], f"transfer.buffer.{key}")
    _validate_result_int(tbuffer["result"], "transfer.buffer.result")
    for key in ("size", "usage", "sharingMode"): _uint(tbuffer[key], f"transfer.buffer.{key}")
    if tbuffer["created"] and (not tbuffer["createAttempted"] or tbuffer["result"] != 0): raise ValueError("transfer buffer created witness contradiction")
    if tbuffer["handleReturnedOnError"] and (not tbuffer["createAttempted"] or tbuffer["result"] >= 0): raise ValueError("transfer buffer error-handle witness contradiction")
    memreq = _exact(transfer["memoryRequirements"], {"queried", "size", "alignment", "memoryTypeBits"}, "transfer.memoryRequirements")
    _bool(memreq["queried"], "transfer.memoryRequirements.queried")
    for key in ("size", "alignment", "memoryTypeBits"): _uint(memreq[key], f"transfer.memoryRequirements.{key}")
    if memreq["queried"] and (memreq["size"] == 0 or memreq["alignment"] == 0 or memreq["memoryTypeBits"] == 0): raise ValueError("transfer memory requirements are empty")
    if not memreq["queried"] and any(memreq[key] != 0 for key in ("size", "alignment", "memoryTypeBits")): raise ValueError("unqueried memory requirements have values")
    memtype = _exact(transfer["memoryType"], {"selected", "typeIndex", "propertyFlags", "hostVisible", "hostCoherent", "deviceLocal"}, "transfer.memoryType")
    _bool(memtype["selected"], "transfer.memoryType.selected")
    _uint(memtype["typeIndex"], "transfer.memoryType.typeIndex"); _uint(memtype["propertyFlags"], "transfer.memoryType.propertyFlags")
    for key in ("hostVisible", "hostCoherent", "deviceLocal"): _bool(memtype[key], f"transfer.memoryType.{key}")
    if memtype["selected"] and not memtype["hostVisible"]: raise ValueError("transfer memory type is not host visible")
    if ((memtype["propertyFlags"] & 0x1) != 0) != memtype["hostVisible"]: raise ValueError("transfer memory property HOST_VISIBLE contradiction")
    if ((memtype["propertyFlags"] & 0x2) != 0) != memtype["hostCoherent"]: raise ValueError("transfer memory property HOST_COHERENT contradiction")
    if ((memtype["propertyFlags"] & 0x4) != 0) != memtype["deviceLocal"]: raise ValueError("transfer memory property DEVICE_LOCAL contradiction")
    if not memtype["selected"] and any([memtype["hostVisible"], memtype["hostCoherent"], memtype["deviceLocal"]]): raise ValueError("unselected memory type claims flags")
    alloc = _exact(transfer["allocation"], {"attempted", "allocated", "handleReturnedOnError", "result", "requestedBytes", "capacityBytes"}, "transfer.allocation")
    for key in ("attempted", "allocated", "handleReturnedOnError"): _bool(alloc[key], f"transfer.allocation.{key}")
    _validate_result_int(alloc["result"], "transfer.allocation.result")
    for key in ("requestedBytes", "capacityBytes"): _uint(alloc[key], f"transfer.allocation.{key}")
    if alloc["allocated"] and (not alloc["attempted"] or alloc["result"] != 0): raise ValueError("transfer allocation witness contradiction")
    if alloc["handleReturnedOnError"] and (not alloc["attempted"] or alloc["result"] >= 0): raise ValueError("transfer allocation error-handle witness contradiction")
    if alloc["attempted"] and alloc["capacityBytes"] > TRANSFER_ALLOCATION_CAP_BYTES: raise ValueError("transfer allocation exceeds cap")
    bind = _exact(transfer["bind"], {"attempted", "ok"}, "transfer.bind")
    _bool(bind["attempted"], "transfer.bind.attempted"); _bool(bind["ok"], "transfer.bind.ok")
    if bind["ok"] and not bind["attempted"]: raise ValueError("transfer bind ok without attempt")
    tmap = _exact(transfer["map"], {"attempted", "mapped", "nonNull"}, "transfer.map")
    for key in ("attempted", "mapped", "nonNull"): _bool(tmap[key], f"transfer.map.{key}")
    if tmap["mapped"] and not tmap["attempted"]: raise ValueError("transfer map witness contradiction")
    if tmap["nonNull"] and not tmap["mapped"]: raise ValueError("transfer map non-null without mapping")
    hostwrite = _exact(transfer["hostWrite"], {"performed", "bytes", "coherent", "flushAttempted", "flushOk", "flushOffset", "flushSize"}, "transfer.hostWrite")
    for key in ("performed", "coherent", "flushAttempted", "flushOk"): _bool(hostwrite[key], f"transfer.hostWrite.{key}")
    for key in ("bytes", "flushOffset", "flushSize"): _uint(hostwrite[key], f"transfer.hostWrite.{key}")
    if hostwrite["flushOk"] and not hostwrite["flushAttempted"]: raise ValueError("transfer flush ok without attempt")
    if not hostwrite["flushAttempted"] and (hostwrite["flushOk"] or hostwrite["flushOffset"] != 0 or hostwrite["flushSize"] != 0): raise ValueError("unattempted flush has result")
    if hostwrite["performed"] and hostwrite["bytes"] == 0: raise ValueError("transfer host write has no bytes")
    if not hostwrite["performed"] and (hostwrite["bytes"] != 0 or hostwrite["flushAttempted"] or hostwrite["flushOk"]): raise ValueError("unperformed host write has result")
    pool = _exact(transfer["commandPool"], {"createAttempted", "created", "result", "flags", "queueFamilyIndex"}, "transfer.commandPool")
    _bool(pool["createAttempted"], "transfer.commandPool.createAttempted"); _bool(pool["created"], "transfer.commandPool.created")
    _validate_result_int(pool["result"], "transfer.commandPool.result")
    _uint(pool["flags"], "transfer.commandPool.flags"); _uint(pool["queueFamilyIndex"], "transfer.commandPool.queueFamilyIndex")
    if pool["created"] and (not pool["createAttempted"] or pool["result"] != 0): raise ValueError("transfer command pool witness contradiction")
    cmdbuf = _exact(transfer["commandBuffer"], {"allocateAttempted", "allocated", "result", "level"}, "transfer.commandBuffer")
    _bool(cmdbuf["allocateAttempted"], "transfer.commandBuffer.allocateAttempted"); _bool(cmdbuf["allocated"], "transfer.commandBuffer.allocated")
    _validate_result_int(cmdbuf["result"], "transfer.commandBuffer.result")
    _uint(cmdbuf["level"], "transfer.commandBuffer.level")
    if cmdbuf["allocated"] and (not cmdbuf["allocateAttempted"] or cmdbuf["result"] != 0): raise ValueError("transfer command buffer witness contradiction")
    record = _exact(transfer["record"], {"beginAttempted", "begun", "beginResult", "fillAttempted", "fillOk", "fillOffset", "fillSize", "fillData", "barrierAttempted", "barrierOk", "endAttempted", "ended", "endResult"}, "transfer.record")
    for key in ("beginAttempted", "begun", "fillAttempted", "fillOk", "barrierAttempted", "barrierOk", "endAttempted", "ended"): _bool(record[key], f"transfer.record.{key}")
    _validate_result_int(record["beginResult"], "transfer.record.beginResult")
    _validate_result_int(record["endResult"], "transfer.record.endResult")
    for key in ("fillOffset", "fillSize", "fillData"): _uint(record[key], f"transfer.record.{key}")
    if record["begun"] and (not record["beginAttempted"] or record["beginResult"] != 0): raise ValueError("transfer record begin contradiction")
    if record["fillOk"] and not record["fillAttempted"]: raise ValueError("transfer fill ok without attempt")
    if record["barrierOk"] and not record["barrierAttempted"]: raise ValueError("transfer barrier ok without attempt")
    if record["ended"] and (not record["endAttempted"] or record["endResult"] != 0): raise ValueError("transfer record end contradiction")
    fence = _exact(transfer["fence"], {"createAttempted", "created", "result", "flags"}, "transfer.fence")
    _bool(fence["createAttempted"], "transfer.fence.createAttempted"); _bool(fence["created"], "transfer.fence.created")
    _validate_result_int(fence["result"], "transfer.fence.result")
    _uint(fence["flags"], "transfer.fence.flags")
    if fence["created"] and (not fence["createAttempted"] or fence["result"] != 0): raise ValueError("transfer fence witness contradiction")
    submit = _exact(transfer["submit"], {"attempted", "result", "waitAttempted", "waitResult", "waited", "timeoutNs", "signaled"}, "transfer.submit")
    _bool(submit["attempted"], "transfer.submit.attempted"); _bool(submit["waitAttempted"], "transfer.submit.waitAttempted")
    _bool(submit["waited"], "transfer.submit.waited"); _bool(submit["signaled"], "transfer.submit.signaled")
    _validate_result_int(submit["result"], "transfer.submit.result")
    _validate_result_int(submit["waitResult"], "transfer.submit.waitResult")
    _uint(submit["timeoutNs"], "transfer.submit.timeoutNs")
    if submit["waited"] and (not submit["waitAttempted"] or not submit["attempted"]): raise ValueError("transfer wait witness contradiction")
    if submit["signaled"] and (not submit["waited"] or submit["waitResult"] != 0): raise ValueError("transfer signaled witness contradiction")
    if submit["waitAttempted"] and submit["timeoutNs"] == 0: raise ValueError("transfer wait has no timeout")
    if not submit["waitAttempted"] and (submit["waited"] or submit["signaled"]): raise ValueError("unattempted wait has result")
    invalidate = _exact(transfer["invalidate"], {"required", "attempted", "ok", "offset", "size"}, "transfer.invalidate")
    for key in ("required", "attempted", "ok"): _bool(invalidate[key], f"transfer.invalidate.{key}")
    for key in ("offset", "size"): _uint(invalidate[key], f"transfer.invalidate.{key}")
    if invalidate["ok"] and invalidate["required"] and not invalidate["attempted"]: raise ValueError("transfer invalidate ok without attempt")
    if not invalidate["required"] and invalidate["attempted"]: raise ValueError("transfer invalidate attempted when not required")
    readback = _exact(transfer["readback"], {"attempted", "verified", "dwordCount", "mismatches", "firstMismatchIndex", "pattern", "hashHex"}, "transfer.readback")
    _bool(readback["attempted"], "transfer.readback.attempted"); _bool(readback["verified"], "transfer.readback.verified")
    for key in ("dwordCount", "mismatches"): _uint(readback[key], f"transfer.readback.{key}")
    if not _is_int(readback["firstMismatchIndex"]): raise ValueError("transfer.readback.firstMismatchIndex is not an integer")
    _uint(readback["pattern"], "transfer.readback.pattern")
    if not isinstance(readback["hashHex"], str): raise ValueError("transfer.readback.hashHex is not a string")
    if readback["verified"] and (not readback["attempted"] or readback["mismatches"] != 0): raise ValueError("transfer verified witness contradiction")
    if readback["verified"] and readback["firstMismatchIndex"] != -1: raise ValueError("transfer verified mismatch index contradiction")
    if not readback["attempted"] and (readback["verified"] or readback["dwordCount"] != 0 or readback["mismatches"] != 0): raise ValueError("unattempted readback has result")
    if readback["mismatches"] > 0 and not (0 <= readback["firstMismatchIndex"] < readback["dwordCount"]): raise ValueError("transfer mismatch index out of range")
    if readback["verified"]:
        if HEX16.fullmatch(readback["hashHex"]) is None: raise ValueError("transfer readback hash is not 16 hex")
    elif readback["hashHex"] != "":
        raise ValueError("unverified readback has hash")
    timing = _exact(top["timing"], {"callCount", "monotonicChecks", "valid"}, "timing")
    for key in ("callCount", "monotonicChecks"):
        if not _is_int(timing[key]) or timing[key] < 0: raise ValueError(f"timing.{key} invalid")
    _bool(timing["valid"], "timing.valid")
    if timing["valid"] and timing["monotonicChecks"] < timing["callCount"] * 2: raise ValueError("timing witness is incomplete")
    cleanup = _exact(top["cleanup"], {"deviceDestroyAttempted", "deviceDestroyOk", "instanceDestroyAttempted", "instanceDestroyOk", "dlcloseAttempted", "dlcloseOk", "complete"}, "cleanup")
    for key in cleanup: _bool(cleanup[key], f"cleanup.{key}")
    for ok, attempted in (("deviceDestroyOk", "deviceDestroyAttempted"), ("instanceDestroyOk", "instanceDestroyAttempted"), ("dlcloseOk", "dlcloseAttempted")):
        if cleanup[ok] and not cleanup[attempted]: raise ValueError("cleanup witness contradiction")
    if cleanup["complete"]:
        if device["created"] and not cleanup["deviceDestroyOk"] or instance["created"] and not cleanup["instanceDestroyOk"] or load["loaded"] and not cleanup["dlcloseOk"]: raise ValueError("cleanup complete without owned cleanup")
    for key in ("icdCodeLoaded", "constructorsMayHaveExecuted", "vulkanFunctionsInvoked", "apiComplete", "gpuWorkload", "triangle", "presentation", "d3d", "wow"): _bool(top[key], key)
    if top["icdCodeLoaded"] != load["loaded"] or top["constructorsMayHaveExecuted"] != load["attempted"] or top["vulkanFunctionsInvoked"] != (timing["callCount"] > 0): raise ValueError("scope flags overclaim or contradict witnesses")
    if any(top[key] for key in ("apiComplete", "triangle", "presentation", "d3d", "wow")): raise ValueError("scope flags overclaim or contradict witnesses")
    if not isinstance(top["status"], str) or top["status"] not in VALID_STATUSES or not isinstance(top["failureStage"], str) or top["failureStage"] not in VALID_FAILURE_STAGES: raise ValueError("invalid status/failure stage")
    _bounded(top["failureDetail"], "failureDetail")
    if adb_exit is not None and top["nativeExit"] != adb_exit: raise ValueError("ADB exit differs from native exit")
    negative_results = any(item < 0 for item in (interface["result"], api["result"], instance["result"], physical["countResult"], physical["fillResult"], device["result"], tbuffer["result"], alloc["result"], pool["result"], cmdbuf["result"], record["beginResult"], record["endResult"], fence["result"], submit["result"]))
    all_exports = all(item["found"] and item["ownerExact"] and not item["error"] for item in exports)
    required_functions = all(item["resolved"] and item["ownerExact"] and not item["error"] for item in functions[1:]) and (not api["enumerateInstanceVersionFound"] or (functions[0]["resolved"] and functions[0]["ownerExact"] and not functions[0]["error"]))
    coherent = bool(hostwrite["coherent"])
    if coherent != bool(memtype["hostCoherent"]): raise ValueError("host write coherence contradicts memory type")
    success = (
        top["status"] == "TRANSFER_READBACK_OBSERVED" and top["nativeExit"] == 0 and top["scopeValid"] and same_identity and top["icdIdentityStable"] and
        load["attempted"] and load["loaded"] and not load["dlerror"] and all_exports and interface["supported"] and interface["result"] == 0 and
        (api["legacy10"] or (api["enumerateInstanceVersionFound"] and api["ownerExact"] and api["result"] == 0 and _valid_vk_version(api["reported"]))) and
        required_functions and instance["createAttempted"] and instance["created"] and instance["result"] == 0 and physical["complete"] and
        physical["countResult"] == physical["fillResult"] == 0 and physical["reportedCount"] == physical["returnedCount"] == physical["retainedCount"] == 1 and
        physical["targetExact"] and queues["complete"] and selection["selected"] and device["createAttempted"] and device["created"] and
        device["result"] == 0 and device["queueCreateCount"] == 1 and device["queueFamilyIndex"] == selection["familyIndex"] and device["queueCount"] == 1 and type(device["priority"]) is float and device["priority"] == 1.0 and
        queue["getAttempted"] and queue["index"] == 0 and queue["nonNull"] and queue["observed"] and
        tbuffer["createAttempted"] and tbuffer["created"] and not tbuffer["handleReturnedOnError"] and tbuffer["result"] == 0 and tbuffer["size"] == TRANSFER_BYTES and tbuffer["usage"] == 2 and tbuffer["sharingMode"] == 0 and
        memreq["queried"] and memreq["size"] == TRANSFER_BYTES and _power_of_two(memreq["alignment"]) and memreq["memoryTypeBits"] != 0 and
        memtype["selected"] and memtype["hostVisible"] and
        alloc["attempted"] and alloc["allocated"] and not alloc["handleReturnedOnError"] and alloc["result"] == 0 and alloc["requestedBytes"] == TRANSFER_BYTES and alloc["capacityBytes"] == TRANSFER_ALLOCATION_CAP_BYTES and
        bind["attempted"] and bind["ok"] and
        tmap["attempted"] and tmap["mapped"] and tmap["nonNull"] and
        hostwrite["performed"] and hostwrite["bytes"] == TRANSFER_BYTES and
        ((coherent and not hostwrite["flushAttempted"] and not hostwrite["flushOk"] and hostwrite["flushOffset"] == 0 and hostwrite["flushSize"] == 0) or
         ((not coherent) and hostwrite["flushAttempted"] and hostwrite["flushOk"] and hostwrite["flushOffset"] == 0 and hostwrite["flushSize"] == TRANSFER_BYTES)) and
        pool["createAttempted"] and pool["created"] and pool["result"] == 0 and pool["flags"] == TRANSFER_POOL_FLAGS and pool["queueFamilyIndex"] == selection["familyIndex"] and
        cmdbuf["allocateAttempted"] and cmdbuf["allocated"] and cmdbuf["result"] == 0 and cmdbuf["level"] == 0 and
        record["beginAttempted"] and record["begun"] and record["beginResult"] == 0 and record["fillAttempted"] and record["fillOk"] and record["fillOffset"] == 0 and record["fillSize"] == TRANSFER_BYTES and record["fillData"] == TRANSFER_PATTERN and record["barrierAttempted"] and record["barrierOk"] and record["endAttempted"] and record["ended"] and record["endResult"] == 0 and
        fence["createAttempted"] and fence["created"] and fence["result"] == 0 and fence["flags"] == 0 and
        submit["attempted"] and submit["result"] == 0 and submit["waitAttempted"] and submit["waitResult"] == 0 and submit["waited"] and submit["timeoutNs"] == TRANSFER_FENCE_TIMEOUT_NS and submit["signaled"] and
        invalidate["required"] == (not memtype["hostCoherent"]) and ((invalidate["required"] and invalidate["attempted"] and invalidate["ok"] and invalidate["offset"] == 0 and invalidate["size"] == TRANSFER_BYTES) or ((not invalidate["required"]) and not invalidate["attempted"])) and
        readback["attempted"] and readback["verified"] and readback["dwordCount"] == TRANSFER_DWORDS and readback["mismatches"] == 0 and readback["firstMismatchIndex"] == -1 and readback["pattern"] == TRANSFER_PATTERN and HEX16.fullmatch(readback["hashHex"]) is not None and
        timing["valid"] and timing["callCount"] > 0 and
        cleanup["deviceDestroyAttempted"] and cleanup["deviceDestroyOk"] and cleanup["instanceDestroyAttempted"] and cleanup["instanceDestroyOk"] and cleanup["dlcloseAttempted"] and cleanup["dlcloseOk"] and cleanup["complete"] and
        top["gpuWorkload"] and not top["apiComplete"] and not top["triangle"] and not top["presentation"] and not top["d3d"] and not top["wow"] and
        top["failureStage"] == "none" and not top["failureDetail"]
    )
    if top["nativeExit"] == 0 and top["status"] != "TRANSFER_READBACK_OBSERVED": raise ValueError("non-success native result exited zero")
    if top["status"] == "TRANSFER_READBACK_OBSERVED" and not success: raise ValueError("native success gates are incomplete")
    if top["status"] == "VK_ERROR" and (top["nativeExit"] != 1 or not negative_results): raise ValueError("VK_ERROR lacks a genuine signed VkResult")
    if top["status"] == "NO_QUEUE" and (top["nativeExit"] != 1 or not queues["complete"] or expected_selection is not None): raise ValueError("NO_QUEUE lacks complete count witness")
    if top["status"] == "NO_MEMORY_TYPE" and (top["nativeExit"] != 1 or not memreq["queried"] or memtype["selected"] or alloc["allocated"]): raise ValueError("NO_MEMORY_TYPE lacks queried requirements without selection")
    if top["status"] == "TIMEOUT" and (top["nativeExit"] != 1 or not submit["attempted"] or not submit["waitAttempted"] or submit["waited"] or submit["signaled"] or submit["waitResult"] == 0): raise ValueError("TIMEOUT lacks an unsignaled fence wait")
    if top["status"] == "LOADER_FAILED" and (top["nativeExit"] != 1 or not load["attempted"] or load["loaded"] or not load["dlerror"] or not cleanup["complete"]): raise ValueError("LOADER_FAILED witness is incomplete")
    if top["status"] == "UNKNOWN" and top["nativeExit"] == 0: raise ValueError("UNKNOWN exited zero")
    return top


def _drain(name: str, pipe: Any, limit: int, result: dict[str, Any]) -> None:
    data = bytearray(); total = 0
    with pipe:
        while True:
            chunk = pipe.read(8192)
            if not chunk: break
            total += len(chunk)
            if len(data) < limit: data.extend(chunk[: limit - len(data)])
    result[name] = {"data": bytes(data), "total": total, "truncated": total > limit}


def _write_exclusive(path: Path, data: bytes, mode: int = 0o600) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags, mode)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data); stream.flush(); os.fsync(stream.fileno())
    except Exception:
        try: os.close(fd)
        except OSError: pass
        raise


def _external_parent(value: Path) -> Path:
    parent = value.absolute(); repo = Path(__file__).resolve().parents[3]
    if not parent.is_dir() or parent.is_symlink() or parent.resolve().is_relative_to(repo): raise ValueError("output parent must be existing and external")
    if any(item.is_symlink() for item in (parent, *parent.parents)): raise ValueError("output parent contains symlink")
    return parent


def _force_stop() -> dict[str, Any]:
    try:
        result = adb("shell", "am", "force-stop", PACKAGE, timeout=3)
        return {"action": "force-stop exact package", "returnCode": result.returncode, "stdout": result.stdout.decode(errors="replace"), "stderr": result.stderr.decode(errors="replace")}
    except Exception as exc:
        return {"action": "force-stop exact package", "returnCode": None, "error": str(exc)}


def _requires_force_stop(status: str, *, start_error: str | None, timed_out: bool,
                         cleanup_error: str | None, diagnostic: str | None,
                         remaining_error: str | None, remaining: list[dict[str, Any]],
                         output_errors: list[str], native_cleanup_complete: bool) -> bool:
    """A known, fully reaped Vulkan rejection is not uncertain process ownership."""
    return bool(start_error or timed_out or cleanup_error or diagnostic or
                remaining_error or remaining or output_errors or status == "UNKNOWN" or
                not native_cleanup_complete)


def _reap(process: subprocess.Popen[bytes], deadline: float) -> bool:
    try:
        process.wait(timeout=max(0.0, deadline - time.monotonic())); return process.returncode is not None
    except (subprocess.TimeoutExpired, OSError):
        try: process.terminate()
        except OSError: pass
        try: process.wait(timeout=max(0.1, deadline - time.monotonic())); return process.returncode is not None
        except (subprocess.TimeoutExpired, OSError):
            try: process.kill(); process.wait(timeout=max(0.1, deadline - time.monotonic())); return process.returncode is not None
            except (subprocess.TimeoutExpired, OSError): return False


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    parent = _external_parent(args.output)
    native = load_native()
    shell_status = remote_status_preflight()
    uid, app_root, selinux = package_preflight()
    if uid != UID or app_root not in APP_ROOTS: raise RuntimeError("unexpected app identity")
    api_level = _api_preflight()
    run = "PVK-" + uuid.uuid4().hex
    relative, staging = copy_native(native, run)
    private_root = f"{app_root}/{relative}"
    command = shell_command(private_root, run)
    out = parent / ("native-" + run)
    out.mkdir(mode=0o700)
    capture = {
        "schema": 1, "runId": run, "serial": SERIAL, "package": PACKAGE, "uid": uid, "versionCode": VERSION_CODE,
        "deviceApiLevel": api_level, "publishedMinimumApiLevel": PUBLISHED_MIN_API_LEVEL, "apiCompatibilityProven": False,
        "selinuxContext": selinux, "artifact": native, "stagingCleanup": staging, "privateRelative": relative,
        "workingDirectory": private_root, "command": command, "shellStatusPreflight": shell_status, "nativeSchema": NATIVE_SCHEMA,
        "executionStarted": False, "icdCodeLoaded": False, "constructorsMayHaveExecuted": False, "vulkanFunctionsInvoked": False,
        "apiComplete": False, "gpuWorkload": False, "triangle": False, "presentation": False, "d3d": False, "wow": False,
    }
    # Durable exclusive capture is the last HOST write before Popen.
    _write_exclusive(out / "capture.json", (json.dumps(capture, sort_keys=True, indent=2, default=str) + "\n").encode())
    process: subprocess.Popen[bytes] | None = None
    captured: dict[str, Any] = {}; threads: list[threading.Thread] = []
    timed_out = False; start_error: str | None = None; cleanup_error: str | None = None; force_stop: dict[str, Any] | None = None
    try:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=False)
        capture["executionStarted"] = True; capture["hostAdbClientPid"] = process.pid
        if process.stdout is None or process.stderr is None: raise RuntimeError("missing native pipe")
        for name, pipe, limit in (("stdout", process.stdout, NATIVE_STDOUT_LIMIT), ("stderr", process.stderr, NATIVE_STDERR_LIMIT)):
            thread = threading.Thread(target=_drain, args=(name, pipe, limit, captured), daemon=True); threads.append(thread); thread.start()
        try: process.wait(timeout=NATIVE_DEADLINE_SECONDS)
        except subprocess.TimeoutExpired:
            timed_out = True; force_stop = _force_stop()
    except Exception as exc:
        start_error = str(exc)
        if process is not None: force_stop = _force_stop()
    finally:
        deadline = time.monotonic() + CLEANUP_DEADLINE_SECONDS
        if process is not None and not _reap(process, deadline): cleanup_error = "held HOST adb handle did not quiesce"
        for thread in threads: thread.join(timeout=max(0.0, deadline - time.monotonic()))
        if any(thread.is_alive() for thread in threads): cleanup_error = cleanup_error or "output drain did not quiesce"
    values = {name: captured.get(name, {"data": b"", "total": 0, "truncated": False}) for name in ("stdout", "stderr")}
    output_errors: list[str] = []
    for name in ("stdout", "stderr"):
        try: _write_exclusive(out / f"{name}.txt", values[name]["data"])
        except Exception as exc: output_errors.append(f"{name}: {exc}")
    native_document: dict[str, Any] | None = None; diagnostic: str | None = None; status = "UNKNOWN"
    if start_error: diagnostic = "native child start failed: " + start_error
    elif timed_out: diagnostic = "native child exceeded ten-second deadline"
    elif cleanup_error: diagnostic = cleanup_error
    elif set(captured) != {"stdout", "stderr"}: diagnostic = "native output drain was incomplete"
    elif values["stdout"]["truncated"] or values["stderr"]["truncated"]: diagnostic = "native output exceeded capture bound"
    elif values["stderr"]["data"]: diagnostic = "native stderr was not empty"
    else:
        try: native_document = parse_native(values["stdout"]["data"], run, private_root, process.returncode if process else None); status = native_document["status"]
        except (UnicodeError, ValueError, json.JSONDecodeError) as exc: diagnostic = f"native JSON validation failed: {exc}"
    remaining_error: str | None = None
    try: remaining = own_processes(UID)
    except Exception as exc: remaining = [{"state": "UNKNOWN", "error": str(exc)}]; remaining_error = str(exc)
    runner_error = _requires_force_stop(status, start_error=start_error, timed_out=timed_out,
                                        cleanup_error=cleanup_error, diagnostic=diagnostic,
                                        remaining_error=remaining_error, remaining=remaining,
                                        output_errors=output_errors,
                                        native_cleanup_complete=bool(native_document and native_document["cleanup"]["complete"] is True))
    if runner_error and force_stop is None: force_stop = _force_stop()
    summary = {
        "schema": 1, "runId": run, "status": status, "diagnostic": diagnostic, "deviceApiLevel": api_level,
        "publishedMinimumApiLevel": PUBLISHED_MIN_API_LEVEL, "apiCompatibilityProven": False,
        "nativeExit": process.returncode if process else None, "hostAdbClientPid": process.pid if process else None,
        "timedOut": timed_out, "startError": start_error, "cleanupError": cleanup_error, "forceStop": force_stop,
        "remainingOwnProcesses": remaining, "stdoutBytes": values["stdout"]["total"], "stderrBytes": values["stderr"]["total"],
        "stdoutTruncated": values["stdout"]["truncated"], "stderrTruncated": values["stderr"]["truncated"],
        "completePipes": not any(thread.is_alive() for thread in threads) and set(captured) == {"stdout", "stderr"},
        "native": native_document, "outputErrors": output_errors, "stopBeforeShaderRequired": status != "TRANSFER_READBACK_OBSERVED" or runner_error,
        "boundary": "one 4KiB transfer/fence/readback control witness only; not shader, render, present, D3D, WoW, or API compatibility",
        "output": str(out), "icdCodeLoaded": bool(native_document and native_document.get("icdCodeLoaded") is True),
        "constructorsMayHaveExecuted": bool(native_document and native_document.get("constructorsMayHaveExecuted") is True),
        "vulkanFunctionsInvoked": bool(native_document and native_document.get("vulkanFunctionsInvoked") is True),
        "apiComplete": False, "gpuWorkload": bool(native_document and native_document.get("gpuWorkload") is True), "triangle": False, "presentation": False, "d3d": False, "wow": False,
    }
    try: _write_exclusive(out / "summary.json", (json.dumps(summary, sort_keys=True, indent=2, default=str) + "\n").encode())
    except Exception as exc: output_errors.append(f"summary: {exc}")
    # CLI is intentionally a small, stable decision record: no public paths,
    # feature flags, or artifact details are printed.
    print(json.dumps({key: summary[key] for key in ("schema", "runId", "status", "nativeExit", "timedOut", "diagnostic", "stopBeforeShaderRequired")}, separators=(",", ":")))
    exact_pass = status == "TRANSFER_READBACK_OBSERVED" and process is not None and process.returncode == 0 and force_stop is None and not output_errors and not remaining
    return 0 if exact_pass else 1


if __name__ == "__main__":
    raise SystemExit(main())
