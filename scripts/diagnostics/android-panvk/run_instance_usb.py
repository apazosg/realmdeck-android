#!/usr/bin/env python3
"""Run the fixed PanVK instance/physical-device metadata control.

This host binding performs one private, pinned child execution.  It does not
start an APK, a Vulkan loader, a device, a queue, a workload, Wine, or WoW.
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


_BASE_PATH = Path(__file__).parents[1] / "android-vtest" / "run_loader_usb.py"
_BASE_SPEC = importlib.util.spec_from_file_location("panvk_instance_adb_base", _BASE_PATH)
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
ARTIFACT_ROOT = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/panvk-instance-metadata-v4")
NATIVE_ARTIFACT = ARTIFACT_ROOT / "PanvkInstanceMetadata"
NATIVE_MANIFEST = ARTIFACT_ROOT / "manifest.json"
NATIVE_NAME = "PanvkInstanceMetadata"
ICD_SOURCE = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/panvk-audit-beta16/icd/libvulkan_panfrost.so")
HEADER_SOURCE = Path("/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include/vulkan/vulkan_core.h")
HELPER_SOURCE = SOURCE_ROOT / "PanvkPrivateLoader.c"

# These are filled from the immutable output of build_instance_probe.sh.
NATIVE_BYTES = 37400
NATIVE_SHA256 = "694c925e61d935b95f6d92fa8f314ad8a5d151d9cd2fb10702fa513012d4bfc1"
NATIVE_MANIFEST_SHA256 = "f7bcb9a9eb8af7405de8d0f393fdf7144fa2923db8617b3340e0411f99175e3e"
NATIVE_HOST_MODE = "0755"
NATIVE_DEVICE_MODE = "0700"
ICD_BYTES = 30972472
ICD_SHA256 = "c0e54909059182938ed823df1640848ecd40d75f6885cdf3b9d04530dc93e60c"
HEADER_SHA256 = "c274cdac1e77755ad2c9e12a1d4d24a91e1f72dad138bfcd8b4a54279b9c2e71"
HELPER_SHA256 = "c15a849033826a881f1da424de1f2560a5dafabb13da298f1ce380e3639af29a"
SOURCE_PINS: dict[str, str] = {
    "PanvkInstanceMetadata.c": "a98b7d46ef12b985f6ab5ae37a759a3117e32a2361297bf2434c46b95e87b9a3",
    "README-instance.md": "35ad5eb54bd2110f661f7287f71e47b068bdea11fd36224a08f057b1bd15f412",
    "build_instance_probe.sh": "1b1b10b3c87091c4aaad6c9af53136f5769c809f2494f623608e707f5fb293b1",
}

NATIVE_SCHEMA = "wowforever.android-panvk-instance-metadata"
BUILD_SCHEMA = "wowforever.android-panvk-instance-metadata-build/version1"
RUN_PREFIX = "PVK-"
RUN_RE = re.compile(r"^PVK-[0-9a-f]{32}$")
PRIVATE_PREFIX = "files/forever/controls/panvk-loader"
STAGE_PREFIX = "/data/local/tmp/wowforever-panvk-instance-"
NATIVE_STDOUT_LIMIT = 64 * 1024
NATIVE_STDERR_LIMIT = 64 * 1024
NATIVE_DEADLINE_SECONDS = 10
CLEANUP_DEADLINE_SECONDS = 5
MAX_NATIVE_LINE = 64 * 1024
ERROR_BYTES = 512
HEX64 = re.compile(r"^[0-9a-f]{64}$")
EXPORT_NAMES = (
    "vk_icdGetInstanceProcAddr",
    "vk_icdGetPhysicalDeviceProcAddr",
    "vk_icdNegotiateLoaderICDInterfaceVersion",
)
FUNCTION_NAMES = (
    "vkCreateInstance", "vkDestroyInstance", "vkEnumeratePhysicalDevices",
    "vkGetPhysicalDeviceProperties", "vkGetPhysicalDeviceFeatures",
    "vkEnumerateDeviceExtensionProperties", "vkGetPhysicalDeviceFeatures2",
    "vkEnumerateInstanceVersion",
)
APP_ROOTS = {f"/data/user/0/{PACKAGE}", f"/data/data/{PACKAGE}"}
VALID_STATUSES = {"PHYSICAL_METADATA_OBSERVED", "VK_ERROR", "ZERO_DEVICES", "LOADER_FAILED", "UNKNOWN"}
VALID_FAILURE_STAGES = {
    "none", "arguments", "scope", "path", "identity", "hash", "clock", "loader",
    "export", "owner", "interface", "api", "function", "vulkan", "physical", "cleanup", "output",
}
VK_EXT_TRANSFORM_FEEDBACK = "VK_EXT_transform_feedback"
VK_EXT_ROBUSTNESS_2 = "VK_EXT_robustness2"


def adb(*args: str, timeout: int = 15) -> subprocess.CompletedProcess[bytes]:
    return base.adb(*args, timeout=timeout)


def require_adb(result: subprocess.CompletedProcess[bytes], stage: str, limit: int = 128 * 1024) -> bytes:
    return base.require_adb(result, stage, limit=limit)


def remote_status_preflight() -> dict[str, Any]:
    return base.remote_status_preflight()


def package_preflight() -> tuple[int, str, str]:
    return base._package_preflight()


def own_processes(uid: int = UID) -> list[dict[str, Any]]:
    return base.own_processes(uid)


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
        raise ValueError(f"artifact is not a regular non-symlink: {path}")
    return info


def _digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _fixed_hash(value: object, label: str) -> str:
    if not isinstance(value, str) or HEX64.fullmatch(value) is None or value == "0" * 64:
        raise ValueError(f"{label} pin is absent or invalid")
    return value


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


def load_native() -> dict[str, Any]:
    """Validate all host pins before any device operation is attempted."""
    if not NATIVE_BYTES or not NATIVE_SHA256 or not NATIVE_MANIFEST_SHA256 or not HEADER_SHA256:
        raise ValueError("final native artifact pins are not populated")
    for name, expected in SOURCE_PINS.items():
        _fixed_hash(expected, f"source {name}")
        path = SOURCE_ROOT / name
        info = _regular_nosymlink(path)
        raw = path.read_bytes()
        if len(raw) != info.st_size or _digest(path) != expected:
            raise ValueError(f"source pin mismatch: {name}")
    helper_info = _regular_nosymlink(HELPER_SOURCE)
    if helper_info.st_size != HELPER_SOURCE.stat().st_size or _digest(HELPER_SOURCE) != HELPER_SHA256:
        raise ValueError("helper source pin mismatch")
    _regular_nosymlink(HEADER_SOURCE)
    if _digest(HEADER_SOURCE) != HEADER_SHA256:
        raise ValueError("Vulkan header pin mismatch")
    _regular_nosymlink(NATIVE_MANIFEST)
    manifest_raw = NATIVE_MANIFEST.read_bytes()
    if len(manifest_raw) > 128 * 1024 or _digest(NATIVE_MANIFEST) != NATIVE_MANIFEST_SHA256:
        raise ValueError("native manifest hash mismatch")
    manifest = json.loads(manifest_raw.decode("ascii", "strict"), object_pairs_hook=unique_json_pairs)
    if not isinstance(manifest, dict) or manifest.get("schema") != BUILD_SCHEMA:
        raise ValueError("native manifest schema mismatch")
    if manifest.get("file") != NATIVE_NAME or manifest.get("size") != NATIVE_BYTES or manifest.get("sha256") != NATIVE_SHA256:
        raise ValueError("native manifest artifact pin mismatch")
    if manifest.get("mode") != NATIVE_HOST_MODE or manifest.get("interpreter") != "/system/bin/linker64":
        raise ValueError("native manifest mode or interpreter mismatch")
    if manifest.get("neededLibraries") != ["libc.so", "libdl.so"] or manifest.get("linkLibraries") != ["c", "dl"]:
        raise ValueError("native dependency pin mismatch")
    if manifest.get("sourceSha256") != SOURCE_PINS:
        raise ValueError("native manifest source pin mismatch")
    helper = manifest.get("helperSource")
    if not isinstance(helper, dict) or helper.get("path") != str(HELPER_SOURCE) or helper.get("sha256") != HELPER_SHA256:
        raise ValueError("native manifest helper pin mismatch")
    header = manifest.get("header")
    if not isinstance(header, dict) or header.get("path") != str(HEADER_SOURCE) or header.get("sha256") != HEADER_SHA256:
        raise ValueError("native manifest header pin mismatch")
    icd = manifest.get("icdPin")
    if not isinstance(icd, dict) or icd.get("sourcePath") != str(ICD_SOURCE) or icd.get("bytes") != ICD_BYTES or icd.get("sha256") != ICD_SHA256 or icd.get("mode") != "0600":
        raise ValueError("native manifest ICD pin mismatch")
    info = _regular_nosymlink(NATIVE_ARTIFACT)
    if stat.S_IMODE(info.st_mode) != int(NATIVE_HOST_MODE, 8):
        raise ValueError("native artifact host mode mismatch")
    data = NATIVE_ARTIFACT.read_bytes()
    if len(data) != NATIVE_BYTES or hashlib.sha256(data).hexdigest() != NATIVE_SHA256:
        raise ValueError("native artifact hash or size mismatch")
    icd_info = _regular_nosymlink(ICD_SOURCE)
    if stat.S_IMODE(icd_info.st_mode) != 0o600 or icd_info.st_size != ICD_BYTES or _digest(ICD_SOURCE) != ICD_SHA256:
        raise ValueError("ICD pin mismatch")
    return {"path": NATIVE_ARTIFACT, "name": NATIVE_NAME, "bytes": len(data), "sha256": NATIVE_SHA256,
            "mode": NATIVE_DEVICE_MODE, "hostMode": NATIVE_HOST_MODE, "manifest": str(NATIVE_MANIFEST),
            "manifestBytes": len(manifest_raw), "manifestSha256": NATIVE_MANIFEST_SHA256,
            "source": {name: {"path": str(SOURCE_ROOT / name), "bytes": len((SOURCE_ROOT / name).read_bytes()), "sha256": digest}
                       for name, digest in SOURCE_PINS.items()},
            "header": {"path": str(HEADER_SOURCE), "sha256": HEADER_SHA256},
            "helper": {"path": str(HELPER_SOURCE), "sha256": HELPER_SHA256},
            "icd": {"path": str(ICD_SOURCE), "bytes": ICD_BYTES, "sha256": ICD_SHA256, "mode": "0600"}}


def _private_relative(run: str) -> str:
    if RUN_RE.fullmatch(run) is None:
        raise ValueError("invalid run id")
    return f"{PRIVATE_PREFIX}/{run}"


def _q(value: str) -> str:
    return shlex.quote(value)


def _device_hash(path: str, expected: str) -> None:
    raw = require_adb(adb("exec-out", "run-as", PACKAGE, "sha256sum", path), "device artifact hash", 4096)
    fields = raw.decode("ascii", "strict").split()
    if fields != [expected, path]:
        raise RuntimeError(f"device artifact hash mismatch: {path}")


def _device_mode(path: str, expected: bytes) -> None:
    actual = require_adb(adb("exec-out", "run-as", PACKAGE, "stat", "-c", "%a", path), "device artifact mode", 4096).strip()
    if actual != expected:
        raise RuntimeError(f"device artifact mode mismatch: {path}")


def _copy_script(stage: str, relative: str) -> str:
    if RUN_RE.fullmatch(relative.rsplit("/", 1)[-1]) is None:
        raise ValueError("invalid destination run")
    directories = ["files", "files/forever", "files/forever/controls", PRIVATE_PREFIX]
    commands = ["umask 077", "set -e", "set -C"]
    for directory in directories:
        qdir = _q(directory)
        commands.append(f"test ! -L {qdir} && (test -d {qdir} || mkdir -m 700 {qdir})")
    commands.append(f"test ! -e {_q(relative)} && test ! -L {_q(relative)} && mkdir -m 700 {_q(relative)}")
    for name, mode in ((NATIVE_NAME, "0700"), ("libvulkan_panfrost.so", "0600")):
        destination = f"{relative}/{name}"
        commands.extend([f"test ! -e {_q(destination)} && test ! -L {_q(destination)}",
                         f"cat {_q(stage + '/' + name)} > {_q(destination)}", f"chmod {mode} {_q(destination)}"])
    commands.append("printf PANVK_INSTANCE_UPLOAD_OK")
    return "; ".join(commands)


def copy_native(native: dict[str, Any], run: str) -> tuple[str, dict[str, Any]]:
    relative = _private_relative(run)
    stage = STAGE_PREFIX + uuid.uuid4().hex
    files = (f"{stage}/{NATIVE_NAME}", f"{stage}/libvulkan_panfrost.so")
    made = False
    mkdir_uncertain = False
    failures: list[str] = []
    try:
        require_adb(adb("shell", "test", "-d", "/data/local/tmp"), "staging parent")
        try:
            require_adb(adb("shell", "mkdir", "-m", "755", stage), "exclusive staging directory")
        except (subprocess.TimeoutExpired, TimeoutError):
            mkdir_uncertain = True
            raise
        made = True
        require_adb(adb("push", str(native["path"]), files[0], timeout=30), "probe upload")
        require_adb(adb("push", str(ICD_SOURCE), files[1], timeout=30), "ICD upload")
        require_adb(adb("shell", "chmod", "0644", files[0]), "probe staging mode")
        require_adb(adb("shell", "chmod", "0644", files[1]), "ICD staging mode")
        uploaded = adb("exec-out", "run-as", PACKAGE, "/system/bin/sh", "-c", _copy_script(stage, relative), timeout=30)
        if uploaded.returncode or uploaded.stdout != b"PANVK_INSTANCE_UPLOAD_OK" or uploaded.stderr:
            raise RuntimeError("private upload sentinel was not exact")
        probe = f"{relative}/{NATIVE_NAME}"
        icd = f"{relative}/libvulkan_panfrost.so"
        _device_hash(probe, native["sha256"]); _device_hash(icd, ICD_SHA256)
        _device_mode(probe, b"700"); _device_mode(icd, b"600")
        return relative, {"path": stage, "stagingClean": True, "failures": [],
                          "device": {"probe": {"path": probe, "sha256": native["sha256"], "mode": "0700"},
                                     "icd": {"path": icd, "sha256": ICD_SHA256, "mode": "0600"}}}
    finally:
        if made:
            for path in files:
                try:
                    result = adb("shell", "rm", "-f", path)
                    if result.returncode or result.stdout or result.stderr: failures.append(path)
                except Exception:
                    failures.append(path)
            try:
                result = adb("shell", "rmdir", stage)
                if result.returncode or result.stdout or result.stderr: failures.append("staging-directory")
            except Exception:
                failures.append("staging-directory")
        elif mkdir_uncertain:
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
    environment = ("PATH=/system/bin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
                   "LD_LIBRARY_PATH=/system/lib64", f"TMPDIR={private_root}")
    script = f"cd {_q(private_root)} && exec /system/bin/env -i " + " ".join(_q(item) for item in environment) + \
             f" /system/bin/linker64 {_q(private_root + '/' + NATIVE_NAME)} --run-id {_q(run)}"
    return [ADB, "-s", SERIAL, "shell", "-T", "run-as", PACKAGE, "/system/bin/sh", "-c", _q(script)]


def _exact_identity(value: object, label: str) -> dict[str, Any]:
    item = _exact(value, {"regular", "ownerUid", "device", "inode", "mode", "size"}, label)
    _bool(item["regular"], f"{label}.regular")
    for name in ("ownerUid", "device", "inode", "mode", "size"):
        if not _is_int(item[name]) or item[name] < 0: raise ValueError(f"invalid {label}.{name}")
    if item["regular"]:
        if item["ownerUid"] != UID or item["mode"] != 0o600 or item["size"] != ICD_BYTES or item["device"] <= 0 or item["inode"] <= 0:
            raise ValueError(f"{label} is not pinned")
    elif any(item[name] != 0 for name in ("ownerUid", "device", "inode", "mode", "size")):
        raise ValueError(f"failed {label} is not zeroed")
    return item


def _valid_device_api(value: object) -> bool:
    return type(value) is int and value >= 4194304 and (value >> 29) == 0 and ((value >> 22) & 0x7f) == 1


def parse_native(raw: bytes, run: str, private_root: str, adb_exit: int | None = None) -> dict[str, Any]:
    if not raw or len(raw) >= MAX_NATIVE_LINE or not raw.endswith(b"\n") or raw.count(b"\n") != 1:
        raise ValueError("native output is not one bounded JSON line")
    document = json.loads(raw[:-1].decode("utf-8", "strict"), object_pairs_hook=unique_json_pairs)
    top_keys = {"schema", "version", "runId", "pid", "uid", "pgid", "sid", "monotonicStartNs", "monotonicEndNs", "cwd", "scopeValid", "icdPath", "icdBytes", "icdSha256", "icdIdentityBefore", "icdIdentityAfter", "icdIdentityStable", "load", "exports", "interface", "functions", "api", "instance", "physicalDevices", "cleanup", "icdCodeLoaded", "constructorsMayHaveExecuted", "vulkanFunctionsInvoked", "apiComplete", "gpuWorkload", "triangle", "presentation", "d3d", "wow", "status", "failureStage", "failureDetail", "nativeExit"}
    top = _exact(document, top_keys, "native")
    if not isinstance(top["schema"], str) or not isinstance(top["runId"], str) or type(top["version"]) is not int or top["schema"] != NATIVE_SCHEMA or top["version"] != 1 or top["runId"] != run or RUN_RE.fullmatch(run) is None:
        raise ValueError("native schema or run id mismatch")
    for name in ("pid", "uid", "pgid", "sid", "monotonicStartNs", "monotonicEndNs", "icdBytes", "nativeExit"):
        if not _is_int(top[name]): raise ValueError(f"native {name} is not an integer")
    if any(top[name] <= 0 for name in ("pid", "uid", "pgid", "sid", "monotonicStartNs", "monotonicEndNs")) or top["uid"] != UID or not 0 <= top["nativeExit"] <= 255:
        raise ValueError("native identity, UID, clock, or exit is invalid")
    if top["monotonicEndNs"] < top["monotonicStartNs"] or top["monotonicEndNs"] - top["monotonicStartNs"] > NATIVE_DEADLINE_SECONDS * 1_000_000_000:
        raise ValueError("native timing is outside bound")
    if not isinstance(top["cwd"], str) or top["cwd"] != private_root or private_root not in {f"{root}/{_private_relative(run)}" for root in APP_ROOTS}: raise ValueError("native cwd is not owned")
    _bool(top["scopeValid"], "scopeValid")
    if top["icdPath"] != f"{private_root}/libvulkan_panfrost.so" or top["icdBytes"] != ICD_BYTES or top["icdSha256"] != ICD_SHA256: raise ValueError("native ICD binding is not pinned")
    _bool(top["icdIdentityStable"], "icdIdentityStable")
    before = _exact_identity(top["icdIdentityBefore"], "before"); after = _exact_identity(top["icdIdentityAfter"], "after")
    same = before["regular"] and after["regular"] and all(before[k] == after[k] for k in ("ownerUid", "device", "inode", "mode", "size"))
    if top["icdIdentityStable"] != same: raise ValueError("identity flag contradicts snapshots")
    load = _exact(top["load"], {"attempted", "loaded", "dlerror"}, "load")
    _bool(load["attempted"], "load.attempted"); _bool(load["loaded"], "load.loaded"); _bounded(load["dlerror"], "load.dlerror")
    if (load["loaded"] and (not load["attempted"] or load["dlerror"])) or (not load["attempted"] and (load["loaded"] or load["dlerror"])): raise ValueError("contradictory load")
    exports = top["exports"]
    if not isinstance(exports, list) or len(exports) != 3: raise ValueError("exports are not exactly three")
    for index, name in enumerate(EXPORT_NAMES):
        item = _exact(exports[index], {"name", "found", "owner", "ownerExact", "error"}, f"export {index}")
        if item["name"] != name: raise ValueError("export order mismatch")
        _bool(item["found"], "export.found"); _bool(item["ownerExact"], "export.ownerExact"); _bounded(item["owner"], "export.owner"); _bounded(item["error"], "export.error")
        if item["ownerExact"] and (not item["found"] or item["owner"] != top["icdPath"]): raise ValueError("contradictory export owner")
        if item["found"] and item["ownerExact"] and item["error"]: raise ValueError("successful export has error")
    interface = _exact(top["interface"], {"input", "result", "output", "supported"}, "interface")
    for key in ("input", "result", "output"):
        if not _is_int(interface[key]): raise ValueError("interface integer invalid")
    _bool(interface["supported"], "interface.supported")
    if interface["input"] != 7 or interface["supported"] != (interface["result"] == 0 and 2 <= interface["output"] <= 7): raise ValueError("interface negotiation contradiction")
    functions = top["functions"]
    if not isinstance(functions, list) or len(functions) != 8: raise ValueError("functions are not exactly eight")
    for index, expected in enumerate(FUNCTION_NAMES):
        item = _exact(functions[index], {"name", "resolved", "owner", "ownerExact", "error"}, f"function {index}")
        if item["name"] != expected: raise ValueError("function order mismatch")
        _bool(item["resolved"], "function.resolved"); _bool(item["ownerExact"], "function.ownerExact"); _bounded(item["owner"], "function.owner"); _bounded(item["error"], "function.error")
        if item["ownerExact"] and (not item["resolved"] or item["owner"] != top["icdPath"]): raise ValueError("contradictory function owner")
    api = _exact(top["api"], {"enumerateInstanceVersionFound", "ownerExact", "legacy10", "result", "reported", "requested"}, "api")
    for key in ("enumerateInstanceVersionFound", "ownerExact", "legacy10"): _bool(api[key], f"api.{key}")
    for key in ("result", "reported", "requested"):
        if not _is_int(api[key]): raise ValueError("api integer invalid")
    if api["legacy10"] != (not api["enumerateInstanceVersionFound"] and api["reported"] == 4194304 and api["requested"] == 4194304): raise ValueError("legacy API witness mismatch")
    if not api["enumerateInstanceVersionFound"]:
        if not api["ownerExact"] or api["result"] != 0: raise ValueError("legacy API witness is invalid")
    elif api["result"] == 0:
        reported = api["reported"]
        if reported < 4194304 or (reported >> 29) != 0 or ((reported >> 22) & 0x7f) != 1: raise ValueError("API version witness is invalid")
        major = (reported >> 22) & 0x7f
        minor = (reported >> 12) & 0x3ff
        expected = (major << 22) | (min(minor, 3) << 12)
        if api["requested"] != expected: raise ValueError("requested API is not min(reported, 1.3)")
    instance = _exact(top["instance"], {"createAttempted", "created", "result", "layerCount", "extensionCount", "allocatorNull"}, "instance")
    for key in ("createAttempted", "created", "allocatorNull"): _bool(instance[key], f"instance.{key}")
    for key in ("result", "layerCount", "extensionCount"):
        if not _is_int(instance[key]): raise ValueError("instance integer invalid")
    if instance["layerCount"] != 0 or instance["extensionCount"] != 0 or not instance["allocatorNull"] or instance["created"] and not instance["createAttempted"] or instance["created"] and instance["result"] != 0: raise ValueError("instance policy mismatch")
    physical = _exact(top["physicalDevices"], {"countQueryResult", "fillResult", "reportedCount", "returnedCount", "retainedCount", "capacity", "complete", "devices"}, "physicalDevices")
    for key in ("countQueryResult", "fillResult", "reportedCount", "returnedCount", "retainedCount", "capacity"):
        if not _is_int(physical[key]): raise ValueError("physical integer invalid")
    _bool(physical["complete"], "physical.complete")
    devices = physical["devices"]
    if not isinstance(devices, list) or len(devices) > 8 or physical["capacity"] != 8 or physical["retainedCount"] != len(devices) or physical["retainedCount"] > physical["capacity"] or physical["returnedCount"] < 0 or physical["reportedCount"] < 0: raise ValueError("physical count mismatch")
    for index, device in enumerate(devices):
        item = _exact(device, {"metadataStatus", "vendorId", "deviceId", "apiVersion", "driverVersion", "deviceName", "propertiesValid", "extensionsValid", "extensionCount", "extensionReportedCount", "extensionReturnedCount", "extensionRetainedCount", "extensionCapacity", "extensionQueryResult", "extensionFillResult", "extensionsComplete", "extensions", "features", "features2", "error"}, f"device {index}")
        if item["metadataStatus"] not in {"OBSERVED", "UNKNOWN"} or (item["deviceName"] is not None and (not isinstance(item["deviceName"], str) or len(item["deviceName"].encode()) > 256)): raise ValueError("device metadata identity invalid")
        for key in ("vendorId", "deviceId", "apiVersion", "driverVersion", "extensionCount", "extensionReportedCount", "extensionReturnedCount", "extensionRetainedCount", "extensionCapacity"):
            if not _is_int(item[key]) or item[key] < 0: raise ValueError("device integer invalid")
        _bool(item["propertiesValid"], "propertiesValid"); _bool(item["extensionsValid"], "extensionsValid"); _bounded(item["error"], "device.error")
        _bool(item["extensionsComplete"], "extensionsComplete")
        for key in ("extensionQueryResult", "extensionFillResult"):
            if not _is_int(item[key]): raise ValueError("extension result is not an integer")
        if not isinstance(item["extensions"], list) or len(item["extensions"]) > 256 or item["extensionCapacity"] != 256 or item["extensionRetainedCount"] != len(item["extensions"]) or item["extensionCount"] != len(item["extensions"]) or item["extensionRetainedCount"] > item["extensionCapacity"] or item["extensionReturnedCount"] < item["extensionRetainedCount"]: raise ValueError("extension count mismatch")
        for extension in item["extensions"]:
            if not isinstance(extension, str) or len(extension.encode()) > 255: raise ValueError("extension name invalid")
        extension_names = set(item["extensions"])
        _validate_features(item["features"], "features")
        _validate_features(item["features2"], "features2", item["apiVersion"], extension_names)
        if item["metadataStatus"] == "OBSERVED" and (not item["propertiesValid"] or not item["extensionsValid"] or item["error"] or not item["extensionsComplete"]): raise ValueError("observed device is incomplete")
    cleanup = _exact(top["cleanup"], {"destroyAttempted", "destroyOk", "dlcloseAttempted", "dlcloseOk", "complete"}, "cleanup")
    for key in cleanup: _bool(cleanup[key], f"cleanup.{key}")
    if cleanup["destroyOk"] and not cleanup["destroyAttempted"] or cleanup["dlcloseOk"] and not cleanup["dlcloseAttempted"]: raise ValueError("contradictory cleanup")
    for key in ("icdCodeLoaded", "constructorsMayHaveExecuted", "vulkanFunctionsInvoked", "apiComplete", "gpuWorkload", "triangle", "presentation", "d3d", "wow"): _bool(top[key], key)
    if top["icdCodeLoaded"] != load["loaded"] or top["constructorsMayHaveExecuted"] != load["attempted"] or any(top[key] for key in ("apiComplete", "gpuWorkload", "triangle", "presentation", "d3d", "wow")): raise ValueError("scope flags overclaim")
    if not isinstance(top["status"], str) or top["status"] not in VALID_STATUSES or not isinstance(top["failureStage"], str) or top["failureStage"] not in VALID_FAILURE_STAGES: raise ValueError("invalid status/failure stage")
    _bounded(top["failureDetail"], "failureDetail")
    if adb_exit is not None and top["nativeExit"] != adb_exit: raise ValueError("ADB exit differs from native exit")
    if top["status"] == "PHYSICAL_METADATA_OBSERVED":
        required_functions = list(functions[:6])
        if api["requested"] >= ((1 << 22) | (1 << 12)): required_functions.append(functions[6])
        if api["enumerateInstanceVersionFound"]: required_functions.append(functions[7])
        api_success = (not api["enumerateInstanceVersionFound"] and api["legacy10"] and api["ownerExact"] and api["result"] == 0) or (api["enumerateInstanceVersionFound"] and api["ownerExact"] and api["result"] == 0 and api["requested"] >= 4194304)
        if top["nativeExit"] != 0 or not top["scopeValid"] or not same or not top["icdIdentityStable"] or not load["loaded"] or not cleanup["complete"] or not cleanup["destroyOk"] or not cleanup["dlcloseOk"] or top["failureStage"] != "none" or top["failureDetail"] or not interface["supported"] or interface["result"] != 0 or not api_success or not instance["createAttempted"] or not instance["created"] or instance["result"] != 0 or physical["countQueryResult"] != 0 or physical["fillResult"] != 0 or not physical["complete"] or physical["reportedCount"] != physical["returnedCount"] or physical["reportedCount"] != physical["retainedCount"] or not devices or any(item["metadataStatus"] != "OBSERVED" or not _features_success_valid(item, api["requested"]) for item in devices) or any(not item["resolved"] or not item["ownerExact"] or item["error"] for item in required_functions) or any(not item["found"] or not item["ownerExact"] or item["error"] for item in exports): raise ValueError("native success gates incomplete")
    elif top["nativeExit"] == 0: raise ValueError("non-success status must exit nonzero")
    return top


def _validate_features(value: object, label: str, api_version: int | None = None, extensions: set[str] | None = None) -> None:
    if not isinstance(value, dict): raise ValueError(f"{label} is not an object")
    if label == "features": expected = {"fillModeNonSolid", "shaderClipDistance", "textureCompressionBC", "geometryShader", "tessellationShader", "sampleRateShading", "samplerAnisotropy", "robustBufferAccess"}
    else: expected = {"queried", "core", "vulkan11ShaderDrawParameters", "vulkan12", "vulkan13", "transformFeedback", "robustness2", "valid"}
    if set(value) != expected: raise ValueError(f"{label} keyset mismatch")
    if label == "features":
        for item in value.values():
            if item is not None: _bool(item, label)
    else:
        _bool(value["queried"], f"{label}.queried"); _bool(value["valid"], f"{label}.valid")
        _validate_features(value["core"], "features")
        for key in ("vulkan11ShaderDrawParameters",):
            if value[key] is not None: _bool(value[key], key)
        for key, keys in (("vulkan12", {"descriptorIndexing", "bufferDeviceAddress"}), ("vulkan13", {"shaderDemoteToHelperInvocation", "synchronization2", "dynamicRendering"}), ("transformFeedback", {"transformFeedback", "geometryStreams"}), ("robustness2", {"robustBufferAccess2", "nullDescriptor"})):
            item = _exact(value[key], keys, f"{label}.{key}")
            for flag in item.values():
                if flag is not None: _bool(flag, f"{label}.{key}")
        if api_version is not None:
            if type(api_version) is not int or api_version < 4194304 or (api_version >> 29) != 0 or ((api_version >> 22) & 0x7f) != 1:
                return
            minor = (api_version >> 12) & 0x3ff
            if not value["queried"]:
                leaves = [value["vulkan11ShaderDrawParameters"], *value["vulkan12"].values(), *value["vulkan13"].values(), *value["transformFeedback"].values(), *value["robustness2"].values()]
                if any(item is not None for item in leaves): raise ValueError("unqueryable feature witness is non-null")
            else:
                if minor < 1 and value["vulkan11ShaderDrawParameters"] is not None: raise ValueError("Vulkan 1.1 witness is not legal")
                if minor < 2 and any(item is not None for item in value["vulkan12"].values()): raise ValueError("Vulkan 1.2 witness is not legal")
                if minor < 3 and any(item is not None for item in value["vulkan13"].values()): raise ValueError("Vulkan 1.3 witness is not legal")
                extensions = extensions or set()
                if VK_EXT_TRANSFORM_FEEDBACK not in extensions and any(item is not None for item in value["transformFeedback"].values()): raise ValueError("transform feedback witness is not legal")
                if VK_EXT_ROBUSTNESS_2 not in extensions and any(item is not None for item in value["robustness2"].values()): raise ValueError("robustness2 witness is not legal")


def _features_success_valid(device: dict[str, Any], instance_api: int) -> bool:
    if not _valid_device_api(device["apiVersion"]) or not device["propertiesValid"] or not isinstance(device["deviceName"], str) or not device["extensionsValid"] or not device["extensionsComplete"] or device["error"]:
        return False
    if any(type(value) is not bool for value in device["features"].values()): return False
    if device["extensionQueryResult"] != 0 or device["extensionFillResult"] != 0 or not (
        device["extensionReportedCount"] == device["extensionReturnedCount"] ==
        device["extensionRetainedCount"] == device["extensionCount"] == len(device["extensions"])
    ): return False
    features2 = device["features2"]
    api_minor = min((device["apiVersion"] >> 12) & 0x3ff, (instance_api >> 12) & 0x3ff)
    if features2["valid"] is not True or features2["queried"] != (api_minor >= 1): return False
    if any(type(value) is not bool for value in features2["core"].values()): return False
    extensions = set(device["extensions"])
    required = []
    if api_minor >= 1: required.append(features2["vulkan11ShaderDrawParameters"])
    if api_minor >= 2: required.extend(features2["vulkan12"].values())
    if api_minor >= 3: required.extend(features2["vulkan13"].values())
    if features2["queried"]:
        if VK_EXT_TRANSFORM_FEEDBACK in extensions: required.extend(features2["transformFeedback"].values())
        if VK_EXT_ROBUSTNESS_2 in extensions: required.extend(features2["robustness2"].values())
    else:
        if any(value is not None for value in (
            features2["vulkan11ShaderDrawParameters"], *features2["vulkan12"].values(),
            *features2["vulkan13"].values(), *features2["transformFeedback"].values(),
            *features2["robustness2"].values(),
        )): return False
    if api_minor < 2 and any(value is not None for value in features2["vulkan12"].values()): return False
    if api_minor < 3 and any(value is not None for value in features2["vulkan13"].values()): return False
    return all(type(value) is bool for value in required)


def _drain(name: str, pipe: Any, limit: int, result: dict[str, Any]) -> None:
    data = bytearray(); total = 0
    with pipe:
        while True:
            chunk = pipe.read(8192)
            if not chunk: break
            total += len(chunk)
            if len(data) < limit: data.extend(chunk[:limit - len(data)])
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
    if not parent.is_dir() or parent.is_symlink() or parent.resolve().is_relative_to(repo): raise ValueError("output must be existing external parent")
    if any(component.is_symlink() for component in (parent, *parent.parents)): raise ValueError("output parent contains symlink")
    return parent


def _force_stop() -> dict[str, Any]:
    try:
        result = adb("shell", "am", "force-stop", PACKAGE, timeout=3)
        return {"action": "force-stop exact qualification package", "returnCode": result.returncode, "stdout": result.stdout.decode(errors="replace"), "stderr": result.stderr.decode(errors="replace")}
    except Exception as exc:
        return {"action": "force-stop exact qualification package", "returnCode": None, "error": str(exc)}


def _reap(process: subprocess.Popen[bytes], deadline: float) -> bool:
    try:
        process.wait(timeout=max(0.0, deadline - time.monotonic())); return process.returncode is not None
    except (subprocess.TimeoutExpired, OSError):
        try: process.terminate()
        except OSError: pass
        try:
            process.wait(timeout=max(0.1, deadline - time.monotonic())); return process.returncode is not None
        except (subprocess.TimeoutExpired, OSError):
            try: process.kill(); process.wait(timeout=max(0.1, deadline - time.monotonic())); return process.returncode is not None
            except (subprocess.TimeoutExpired, OSError): return False


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv); parent = _external_parent(args.output)
    native = load_native(); shell_status = remote_status_preflight(); uid, app_root, selinux = package_preflight()
    if uid != UID or app_root not in APP_ROOTS: raise RuntimeError("unexpected app identity")
    api_raw = require_adb(adb("shell", "getprop", "ro.build.version.sdk"), "device API", 128).decode("ascii", "strict").strip()
    if api_raw != str(DEVICE_API_LEVEL): raise RuntimeError("unexpected device API level")
    api_level = int(api_raw); run = RUN_PREFIX + uuid.uuid4().hex; relative = _private_relative(run); out = parent / ("native-" + run); out.mkdir(mode=0o700)
    relative, staging = copy_native(native, run); private_root = f"{app_root}/{relative}"; command = shell_command(private_root, run)
    capture = {"schema": 1, "runId": run, "serial": SERIAL, "package": PACKAGE, "uid": uid, "versionCode": VERSION_CODE, "deviceApiLevel": api_level, "publishedMinimumApiLevel": PUBLISHED_MIN_API_LEVEL, "apiCompatibilityProven": False, "selinuxContext": selinux, "artifact": native, "stagingCleanup": staging, "privateRelative": relative, "workingDirectory": private_root, "environment": ["PATH=/system/bin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data", "LD_LIBRARY_PATH=/system/lib64", f"TMPDIR={private_root}"], "command": command, "executionStarted": False, "icdCodeLoaded": False, "constructorsMayHaveExecuted": False, "vulkanFunctionsInvoked": False, "apiComplete": False, "gpuWorkload": False, "triangle": False, "presentation": False, "d3d": False, "wow": False}
    _write_exclusive(out / "capture.json", (json.dumps(capture, sort_keys=True, indent=2, default=str) + "\n").encode())
    process: subprocess.Popen[bytes] | None = None; result: dict[str, Any] = {}; threads: list[threading.Thread] = []; timed_out = False; start_error: str | None = None; cleanup_error: str | None = None; force_stop: dict[str, Any] | None = None
    try:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=False); capture["executionStarted"] = True; capture["hostAdbClientPid"] = process.pid
        if process.stdout is None or process.stderr is None: raise RuntimeError("missing native pipe")
        for name, pipe, limit in (("stdout", process.stdout, NATIVE_STDOUT_LIMIT), ("stderr", process.stderr, NATIVE_STDERR_LIMIT)):
            thread = threading.Thread(target=_drain, args=(name, pipe, limit, result), daemon=True); threads.append(thread); thread.start()
        try: process.wait(timeout=NATIVE_DEADLINE_SECONDS)
        except subprocess.TimeoutExpired: timed_out = True; force_stop = _force_stop()
    except Exception as exc:
        start_error = str(exc)
        if process is not None: force_stop = _force_stop()
    finally:
        deadline = time.monotonic() + CLEANUP_DEADLINE_SECONDS
        if process is not None and not _reap(process, deadline): cleanup_error = "held HOST adb handle did not quiesce"
        for thread in threads: thread.join(timeout=max(0.0, deadline - time.monotonic()))
        if any(thread.is_alive() for thread in threads): cleanup_error = cleanup_error or "output drain did not quiesce"
    values = {name: result.get(name, {"data": b"", "total": 0, "truncated": False}) for name in ("stdout", "stderr")}; output_errors: list[str] = []
    for name in ("stdout", "stderr"):
        try: _write_exclusive(out / f"{name}.txt", values[name]["data"])
        except Exception as exc: output_errors.append(f"{name}: {exc}")
    native_document: dict[str, Any] | None = None; diagnostic: str | None = None; status = "UNKNOWN"
    if start_error: diagnostic = "native child start failed: " + start_error
    elif timed_out: diagnostic = "native child exceeded ten-second deadline"
    elif cleanup_error: diagnostic = cleanup_error
    elif set(result) != {"stdout", "stderr"}: diagnostic = "native output drain was incomplete"
    elif values["stdout"]["truncated"] or values["stderr"]["truncated"]: diagnostic = "native output exceeded capture bound"
    elif values["stderr"]["data"]: diagnostic = "native stderr was not empty"
    else:
        try: native_document = parse_native(values["stdout"]["data"], run, private_root, process.returncode if process is not None else None); status = native_document["status"]
        except (UnicodeError, ValueError, json.JSONDecodeError) as exc: diagnostic = f"native JSON validation failed: {exc}"
    remaining_error: str | None = None
    try: remaining = own_processes(UID)
    except Exception as exc: remaining = [{"state": "UNKNOWN", "error": str(exc)}]; remaining_error = str(exc)
    runner_error = start_error is not None or timed_out or cleanup_error is not None or status == "UNKNOWN" or remaining_error is not None or bool(remaining)
    if runner_error and force_stop is None: force_stop = _force_stop()
    if force_stop is not None and remaining:
        try: remaining = own_processes(UID)
        except Exception as exc: remaining = [{"state": "UNKNOWN", "error": str(exc)}]
    summary = {"schema": 1, "runId": run, "status": status, "diagnostic": diagnostic, "deviceApiLevel": api_level, "publishedMinimumApiLevel": PUBLISHED_MIN_API_LEVEL, "apiCompatibilityProven": False, "nativeExit": process.returncode if process is not None else None, "hostAdbClientPid": process.pid if process is not None else None, "timedOut": timed_out, "startError": start_error, "cleanupError": cleanup_error, "forceStop": force_stop, "remainingOwnProcesses": remaining, "stdoutBytes": values["stdout"]["total"], "stderrBytes": values["stderr"]["total"], "stdoutTruncated": values["stdout"]["truncated"], "stderrTruncated": values["stderr"]["truncated"], "completePipes": not any(thread.is_alive() for thread in threads) and set(result) == {"stdout", "stderr"}, "native": native_document, "icdCodeLoaded": bool(native_document and native_document.get("icdCodeLoaded") is True), "constructorsMayHaveExecuted": bool(native_document and native_document.get("constructorsMayHaveExecuted") is True), "vulkanFunctionsInvoked": bool(native_document and native_document.get("vulkanFunctionsInvoked") is True), "apiComplete": False, "gpuWorkload": False, "triangle": False, "presentation": False, "d3d": False, "wow": False, "outputErrors": output_errors, "boundary": "private ICD instance/physical-device metadata only; not compatibility, device, workload, WSI, D3D, WoW, or readiness", "output": str(out)}
    try: _write_exclusive(out / "summary.json", (json.dumps(summary, sort_keys=True, indent=2, default=str) + "\n").encode())
    except Exception as exc: output_errors.append(f"summary: {exc}")
    return 0 if status == "PHYSICAL_METADATA_OBSERVED" and process is not None and process.returncode == 0 and force_stop is None and not output_errors and not remaining else 1


if __name__ == "__main__":
    raise SystemExit(main())
