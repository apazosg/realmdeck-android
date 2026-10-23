#!/usr/bin/env python3
"""Run the approved PanVK Gate C offscreen-triangle observation once.

This is a deliberately narrow HOST binding.  It uploads only the pinned
native probe plus the pinned ICD into a fresh private RUN directory, starts
the probe once with a sanitized environment, captures bounded output, and
validates the strict native JSON.  A successful result is only the native
``TRIANGLE_OFFSCREEN_OBSERVED`` control witness; it is not a presentation,
swapchain, D3D, DXVK, Wine, or WoW qualification.
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
_BASE_SPEC = importlib.util.spec_from_file_location("panvk_triangle_runner_base", _BASE_PATH)
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
ARTIFACT_ROOT = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/panvk-triangle-v2-sync")
NATIVE_ARTIFACT = ARTIFACT_ROOT / "PanvkTriangle"
NATIVE_MANIFEST = ARTIFACT_ROOT / "manifest.json"
NATIVE_NAME = "PanvkTriangle"
ICD_NAME = "libvulkan_panfrost.so"
ICD_SOURCE = Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/panvk-audit-beta17/icd/libvulkan_panfrost.so")
HEADER_SOURCE = Path("/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include/vulkan/vulkan_core.h")
HELPER_SOURCE = SOURCE_ROOT / "PanvkPrivateLoader.c"

# Repinned from the finished Gate C native build. These are real, fixed values;
# any drift is rejected by _fixed_hash and by the manifest/artifact size checks
# below, so no device operation runs on a changed artifact.
NATIVE_ARTIFACT_BYTES = 57264
NATIVE_ARTIFACT_SHA256 = "85f8577fb35dc84ace3b513d9554defa8aca14072087fb9b2c05147ff4fbf8a7"
NATIVE_MANIFEST_SHA256 = "eba4724bfb6f4a9edfac0ede12e68aa30c43a4f1a0748b1f0cb2e7107c048ebc"
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
    "PanvkTriangle.c": {"bytes": 164308, "sha256": "7a5c242d32673553fbe7e5c271feaa920bde29e00b2f0a1a3693d1ab6b2c7391"},
    "README-triangle.md": {"bytes": 19231, "sha256": "bf299eab73b32a3aaae431200772a9e38bb8fa19e4701d37fbb8bee21f4bf9fc"},
    "build_triangle_probe.sh": {"bytes": 10653, "sha256": "7d721dfb818b6ccbbfbe000e31663ff4dd999ab528f8a8bcd035e6ebfb6122a6"},
}
NATIVE_SCHEMA = "wowforever.android-panvk-triangle"
BUILD_SCHEMA = "wowforever.android-panvk-triangle-build/version1"
RUN_RE = re.compile(r"^PVK-[0-9a-f]{32}$")
PRIVATE_PREFIX = "files/forever/controls/panvk-loader"
STAGE_PREFIX = "/data/local/tmp/wowforever-panvk-triangle-"
NATIVE_STDOUT_LIMIT = 64 * 1024
NATIVE_STDERR_LIMIT = 64 * 1024
NATIVE_DEADLINE_SECONDS = 10
CLEANUP_DEADLINE_SECONDS = 5
MAX_NATIVE_LINE = 64 * 1024
ERROR_BYTES = 512
HEX64 = re.compile(r"^[0-9a-f]{64}$")
# The native probe hashes the 64x64 RGBA readback with 64-bit FNV-1a, emitted
# as 16 lowercase hex characters (see TRIANGLE-CONTRACT.md).
HEX16 = re.compile(r"^[0-9a-f]{16}$")
APP_ROOTS = {f"/data/user/0/{PACKAGE}", f"/data/data/{PACKAGE}"}
EXPORT_NAMES = (
    "vk_icdGetInstanceProcAddr",
    "vk_icdGetPhysicalDeviceProcAddr",
    "vk_icdNegotiateLoaderICDInterfaceVersion",
)
# Deterministic PFN call order for the Gate C probe (TRIANGLE-CONTRACT.md
# sequence: Gate A/B loader/instance/device/queue, then image, view,
# render pass, framebuffer, shader modules, pipeline layout, graphics
# pipeline, host-visible staging buffer, one command buffer with
# begin-render-pass/draw/end/copy, one fenced submit, invalidate when the
# staging memory is not coherent, and readback). The native probe must
# invoke each PFN exactly once, in this order.
FUNCTION_NAMES = (
    "vkEnumerateInstanceVersion", "vkCreateInstance", "vkDestroyInstance",
    "vkEnumeratePhysicalDevices", "vkGetPhysicalDeviceProperties",
    "vkGetPhysicalDeviceQueueFamilyProperties", "vkGetPhysicalDeviceMemoryProperties",
    "vkCreateDevice", "vkDestroyDevice", "vkGetDeviceProcAddr", "vkGetDeviceQueue",
    "vkCreateImage", "vkGetImageMemoryRequirements",
    "vkAllocateMemory", "vkFreeMemory", "vkBindImageMemory",
    "vkCreateImageView", "vkDestroyImageView", "vkDestroyImage",
    "vkCreateRenderPass", "vkDestroyRenderPass",
    "vkCreateFramebuffer", "vkDestroyFramebuffer",
    "vkCreateShaderModule", "vkDestroyShaderModule",
    "vkCreatePipelineLayout", "vkDestroyPipelineLayout",
    "vkCreateGraphicsPipelines", "vkDestroyPipeline",
    "vkCreateBuffer", "vkDestroyBuffer", "vkGetBufferMemoryRequirements",
    "vkBindBufferMemory", "vkMapMemory", "vkUnmapMemory",
    "vkInvalidateMappedMemoryRanges",
    "vkCreateCommandPool", "vkDestroyCommandPool", "vkAllocateCommandBuffers",
    "vkFreeCommandBuffers", "vkBeginCommandBuffer", "vkEndCommandBuffer",
    "vkCmdBeginRenderPass", "vkCmdBindPipeline", "vkCmdDraw",
    "vkCmdEndRenderPass", "vkCmdCopyImageToBuffer",
    "vkCreateFence", "vkDestroyFence", "vkWaitForFences", "vkQueueSubmit",
)
TRIANGLE_EXTENT = 64
TRIANGLE_PIXEL_COUNT = 4096
TRIANGLE_STAGING_BYTES = 16384
TRIANGLE_FENCE_TIMEOUT_NS = 1_000_000_000
TRIANGLE_ALLOCATION_CAP_BYTES = 16 * 1024 * 1024
# VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, the fixed pool flag used by
# the native probe (see TRIANGLE-CONTRACT.md).
TRIANGLE_POOL_FLAGS = 2
# VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_TILING_OPTIMAL, EXCLUSIVE sharing.
TRIANGLE_FORMAT = 37
TRIANGLE_USAGE = 0x21
TRIANGLE_TILING = 0
VALID_STATUSES = {"TRIANGLE_OFFSCREEN_OBSERVED", "VK_ERROR", "NO_QUEUE", "NO_MEMORY_TYPE", "TIMEOUT", "LOADER_FAILED", "UNKNOWN"}
VALID_FAILURE_STAGES = {
    "none", "arguments", "scope", "path", "identity", "hash", "clock", "loader",
    "export", "owner", "interface", "api", "instance", "physical", "queues",
    "no_queue", "memory_type", "vulkan", "device", "queue", "image",
    "image_memory", "bind", "view", "render_pass", "framebuffer", "shader",
    "pipeline_layout", "pipeline", "staging", "buffer", "memory", "map",
    "requirements", "pool", "command", "record", "draw", "copy", "fence", "submit", "wait",
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
    commands.append("printf PANVK_TRIANGLE_UPLOAD_OK")
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
        if uploaded.returncode or uploaded.stdout != b"PANVK_TRIANGLE_UPLOAD_OK" or uploaded.stderr:
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
    if queue_count < 1 or not flags & (1 << 0): return None
    if flags & (1 << 2): return "transfer"
    if flags & (1 << 0): return "graphics"
    if flags & (1 << 1): return "compute"
    return None


def _power_of_two(value: int) -> bool:
    return value > 0 and (value & (value - 1)) == 0


def parse_native(raw: bytes, run: str, private_root: str, adb_exit: int | None = None) -> dict[str, Any]:
    """Strictly consume the triangle schema and recompute success gates."""
    if not raw or len(raw) >= MAX_NATIVE_LINE or not raw.endswith(b"\n") or raw.count(b"\n") != 1:
        raise ValueError("native output is not one bounded JSON line")
    top = _exact(json.loads(raw[:-1].decode("utf-8", "strict"), object_pairs_hook=unique_json_pairs), {
        "schema", "version", "runId", "pid", "uid", "pgid", "sid", "monotonicStartNs", "monotonicEndNs", "cwd", "scopeValid",
        "icdPath", "icdBytes", "icdSha256", "icdIdentityBefore", "icdIdentityAfter", "icdIdentityStable", "load", "exports", "interface",
        "functions", "api", "instance", "physical", "queues", "selection", "device", "queue", "triangle", "timing", "cleanup", "icdCodeLoaded",
        "constructorsMayHaveExecuted", "vulkanFunctionsInvoked", "apiComplete", "gpuWorkload", "triangleRendered", "presentation", "d3d", "wow",
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
    if not isinstance(functions, list) or len(functions) != 51: raise ValueError("functions are not exactly fifty-one")
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
    triangle = _exact(top["triangle"], {"image", "imageMemory", "bind", "view", "renderPass", "framebuffer", "shaders", "pipelineLayout", "pipeline", "staging", "commandPool", "commandBuffer", "record", "fence", "submit", "invalidate", "readback"}, "triangle")
    image = _exact(triangle["image"], {"createAttempted", "created", "handleReturnedOnError", "result", "format", "extentWidth", "extentHeight", "usage", "tiling", "samples", "mipLevels", "arrayLayers", "sharingMode"}, "triangle.image")
    for key in ("createAttempted", "created", "handleReturnedOnError"): _bool(image[key], f"triangle.image.{key}")
    _validate_result_int(image["result"], "triangle.image.result")
    for key in ("format", "extentWidth", "extentHeight", "usage", "tiling", "samples", "mipLevels", "arrayLayers", "sharingMode"): _uint(image[key], f"triangle.image.{key}")
    if image["created"] and (not image["createAttempted"] or image["result"] != 0): raise ValueError("triangle image created witness contradiction")
    if image["handleReturnedOnError"] and (not image["createAttempted"] or image["result"] >= 0): raise ValueError("triangle image error-handle witness contradiction")
    image_mem = _exact(triangle["imageMemory"], {"requirementsQueried", "requirementsSize", "alignment", "memoryTypeBits", "typeSelected", "typeIndex", "propertyFlags", "deviceLocal", "allocateAttempted", "allocated", "handleReturnedOnError", "allocateResult", "requestedBytes", "capacityBytes"}, "triangle.imageMemory")
    for key in ("requirementsQueried", "typeSelected", "deviceLocal", "allocateAttempted", "allocated", "handleReturnedOnError"): _bool(image_mem[key], f"triangle.imageMemory.{key}")
    for key in ("requirementsSize", "alignment", "memoryTypeBits", "typeIndex", "propertyFlags", "requestedBytes", "capacityBytes"): _uint(image_mem[key], f"triangle.imageMemory.{key}")
    _validate_result_int(image_mem["allocateResult"], "triangle.imageMemory.allocateResult")
    if image_mem["requirementsQueried"] and (image_mem["requirementsSize"] == 0 or image_mem["alignment"] == 0 or image_mem["memoryTypeBits"] == 0): raise ValueError("triangle image memory requirements are empty")
    if not image_mem["requirementsQueried"] and any(image_mem[key] != 0 for key in ("requirementsSize", "alignment", "memoryTypeBits")): raise ValueError("unqueried image memory requirements have values")
    if image_mem["typeSelected"] and not image_mem["deviceLocal"]: raise ValueError("triangle image memory type is not device local")
    if ((image_mem["propertyFlags"] & 0x1) != 0) != image_mem["deviceLocal"]: raise ValueError("triangle image memory property DEVICE_LOCAL contradiction")
    if not image_mem["typeSelected"] and image_mem["deviceLocal"]: raise ValueError("unselected image memory type claims flags")
    if image_mem["allocated"] and (not image_mem["allocateAttempted"] or image_mem["allocateResult"] != 0): raise ValueError("triangle image allocation witness contradiction")
    if image_mem["handleReturnedOnError"] and (not image_mem["allocateAttempted"] or image_mem["allocateResult"] >= 0): raise ValueError("triangle image allocation error-handle witness contradiction")
    if image_mem["allocateAttempted"] and (image_mem["requestedBytes"] < image_mem["requirementsSize"] or image_mem["capacityBytes"] > TRIANGLE_ALLOCATION_CAP_BYTES or image_mem["requestedBytes"] > TRIANGLE_ALLOCATION_CAP_BYTES): raise ValueError("triangle image allocation size is invalid")
    bind = _exact(triangle["bind"], {"attempted", "ok"}, "triangle.bind")
    _bool(bind["attempted"], "triangle.bind.attempted"); _bool(bind["ok"], "triangle.bind.ok")
    if bind["ok"] and not bind["attempted"]: raise ValueError("triangle bind ok without attempt")
    view = _exact(triangle["view"], {"createAttempted", "created", "handleReturnedOnError", "result"}, "triangle.view")
    for key in ("createAttempted", "created", "handleReturnedOnError"): _bool(view[key], f"triangle.view.{key}")
    _validate_result_int(view["result"], "triangle.view.result")
    if view["created"] and (not view["createAttempted"] or view["result"] != 0): raise ValueError("triangle view created witness contradiction")
    if view["handleReturnedOnError"] and (not view["createAttempted"] or view["result"] >= 0): raise ValueError("triangle view error-handle witness contradiction")
    render_pass = _exact(triangle["renderPass"], {"createAttempted", "created", "handleReturnedOnError", "result"}, "triangle.renderPass")
    for key in ("createAttempted", "created", "handleReturnedOnError"): _bool(render_pass[key], f"triangle.renderPass.{key}")
    _validate_result_int(render_pass["result"], "triangle.renderPass.result")
    if render_pass["created"] and (not render_pass["createAttempted"] or render_pass["result"] != 0): raise ValueError("triangle render pass created witness contradiction")
    if render_pass["handleReturnedOnError"] and (not render_pass["createAttempted"] or render_pass["result"] >= 0): raise ValueError("triangle render pass error-handle witness contradiction")
    framebuffer = _exact(triangle["framebuffer"], {"createAttempted", "created", "handleReturnedOnError", "result"}, "triangle.framebuffer")
    for key in ("createAttempted", "created", "handleReturnedOnError"): _bool(framebuffer[key], f"triangle.framebuffer.{key}")
    _validate_result_int(framebuffer["result"], "triangle.framebuffer.result")
    if framebuffer["created"] and (not framebuffer["createAttempted"] or framebuffer["result"] != 0): raise ValueError("triangle framebuffer created witness contradiction")
    if framebuffer["handleReturnedOnError"] and (not framebuffer["createAttempted"] or framebuffer["result"] >= 0): raise ValueError("triangle framebuffer error-handle witness contradiction")
    shaders = _exact(triangle["shaders"], {"vertBytes", "fragBytes", "vertCreateAttempted", "vertCreated", "vertResult", "fragCreateAttempted", "fragCreated", "fragResult"}, "triangle.shaders")
    for key in ("vertBytes", "fragBytes"): _uint(shaders[key], f"triangle.shaders.{key}")
    for key in ("vertCreateAttempted", "vertCreated", "fragCreateAttempted", "fragCreated"): _bool(shaders[key], f"triangle.shaders.{key}")
    _validate_result_int(shaders["vertResult"], "triangle.shaders.vertResult")
    _validate_result_int(shaders["fragResult"], "triangle.shaders.fragResult")
    if shaders["vertCreated"] and (not shaders["vertCreateAttempted"] or shaders["vertResult"] != 0 or shaders["vertBytes"] == 0): raise ValueError("triangle vertex shader witness contradiction")
    if shaders["fragCreated"] and (not shaders["fragCreateAttempted"] or shaders["fragResult"] != 0 or shaders["fragBytes"] == 0): raise ValueError("triangle fragment shader witness contradiction")
    if shaders["vertCreateAttempted"] and shaders["vertBytes"] == 0: raise ValueError("triangle vertex shader has no bytes")
    if shaders["fragCreateAttempted"] and shaders["fragBytes"] == 0: raise ValueError("triangle fragment shader has no bytes")
    pipeline_layout = _exact(triangle["pipelineLayout"], {"createAttempted", "created", "result"}, "triangle.pipelineLayout")
    _bool(pipeline_layout["createAttempted"], "triangle.pipelineLayout.createAttempted"); _bool(pipeline_layout["created"], "triangle.pipelineLayout.created")
    _validate_result_int(pipeline_layout["result"], "triangle.pipelineLayout.result")
    if pipeline_layout["created"] and (not pipeline_layout["createAttempted"] or pipeline_layout["result"] != 0): raise ValueError("triangle pipeline layout witness contradiction")
    pipeline = _exact(triangle["pipeline"], {"createAttempted", "created", "handleReturnedOnError", "result"}, "triangle.pipeline")
    for key in ("createAttempted", "created", "handleReturnedOnError"): _bool(pipeline[key], f"triangle.pipeline.{key}")
    _validate_result_int(pipeline["result"], "triangle.pipeline.result")
    if pipeline["created"] and (not pipeline["createAttempted"] or pipeline["result"] != 0): raise ValueError("triangle pipeline created witness contradiction")
    if pipeline["handleReturnedOnError"] and (not pipeline["createAttempted"] or pipeline["result"] >= 0): raise ValueError("triangle pipeline error-handle witness contradiction")
    staging = _exact(triangle["staging"], {"bufferCreateAttempted", "bufferCreated", "handleReturnedOnError", "bufferResult", "bufferSize", "bufferUsage", "memoryQueried", "memorySize", "alignment", "memoryTypeBits", "memoryTypeSelected", "typeIndex", "hostVisible", "hostCoherent", "allocateAttempted", "allocated", "allocateResult", "requestedBytes", "capacityBytes", "bindAttempted", "bindOk", "mapAttempted", "mapped", "nonNull"}, "triangle.staging")
    for key in ("bufferCreateAttempted", "bufferCreated", "handleReturnedOnError", "memoryTypeSelected", "hostVisible", "hostCoherent", "allocateAttempted", "allocated", "bindAttempted", "bindOk", "mapAttempted", "mapped", "nonNull"): _bool(staging[key], f"triangle.staging.{key}")
    for key in ("bufferSize", "bufferUsage", "memorySize", "alignment", "memoryTypeBits", "typeIndex", "requestedBytes", "capacityBytes"): _uint(staging[key], f"triangle.staging.{key}")
    _validate_result_int(staging["bufferResult"], "triangle.staging.bufferResult")
    _validate_result_int(staging["allocateResult"], "triangle.staging.allocateResult")
    if staging["bufferCreated"] and (not staging["bufferCreateAttempted"] or staging["bufferResult"] != 0): raise ValueError("triangle staging buffer created witness contradiction")
    if staging["handleReturnedOnError"] and (not staging["bufferCreateAttempted"] or staging["bufferResult"] >= 0): raise ValueError("triangle staging buffer error-handle witness contradiction")
    if staging["memoryQueried"] and (staging["memorySize"] == 0 or staging["alignment"] == 0 or staging["memoryTypeBits"] == 0): raise ValueError("triangle staging memory requirements are empty")
    if not staging["memoryQueried"] and any(staging[key] != 0 for key in ("memorySize", "alignment", "memoryTypeBits")): raise ValueError("unqueried staging memory requirements have values")
    if staging["memoryTypeSelected"] and not staging["hostVisible"]: raise ValueError("triangle staging memory type is not host visible")
    if not staging["memoryTypeSelected"] and any([staging["hostVisible"], staging["hostCoherent"]]): raise ValueError("unselected staging memory type claims flags")
    if staging["allocated"] and (not staging["allocateAttempted"] or staging["allocateResult"] != 0): raise ValueError("triangle staging allocation witness contradiction")
    if staging["allocateAttempted"] and (staging["requestedBytes"] < staging["memorySize"] or staging["capacityBytes"] > TRIANGLE_ALLOCATION_CAP_BYTES or staging["requestedBytes"] > TRIANGLE_ALLOCATION_CAP_BYTES): raise ValueError("triangle staging allocation size is invalid")
    if staging["bindOk"] and not staging["bindAttempted"]: raise ValueError("triangle staging bind ok without attempt")
    if staging["mapped"] and not staging["mapAttempted"]: raise ValueError("triangle staging map witness contradiction")
    if staging["nonNull"] and not staging["mapped"]: raise ValueError("triangle staging map non-null without mapping")
    pool = _exact(triangle["commandPool"], {"createAttempted", "created", "result", "flags", "queueFamilyIndex"}, "triangle.commandPool")
    _bool(pool["createAttempted"], "triangle.commandPool.createAttempted"); _bool(pool["created"], "triangle.commandPool.created")
    _validate_result_int(pool["result"], "triangle.commandPool.result")
    _uint(pool["flags"], "triangle.commandPool.flags"); _uint(pool["queueFamilyIndex"], "triangle.commandPool.queueFamilyIndex")
    if pool["created"] and (not pool["createAttempted"] or pool["result"] != 0): raise ValueError("triangle command pool witness contradiction")
    cmdbuf = _exact(triangle["commandBuffer"], {"allocateAttempted", "allocated", "result", "level"}, "triangle.commandBuffer")
    _bool(cmdbuf["allocateAttempted"], "triangle.commandBuffer.allocateAttempted"); _bool(cmdbuf["allocated"], "triangle.commandBuffer.allocated")
    _validate_result_int(cmdbuf["result"], "triangle.commandBuffer.result")
    _uint(cmdbuf["level"], "triangle.commandBuffer.level")
    if cmdbuf["allocated"] and (not cmdbuf["allocateAttempted"] or cmdbuf["result"] != 0): raise ValueError("triangle command buffer witness contradiction")
    record = _exact(triangle["record"], {"beginAttempted", "begun", "beginResult", "drawAttempted", "drawOk", "drawVertexCount", "copyAttempted", "copyOk", "endAttempted", "ended", "endResult"}, "triangle.record")
    for key in ("beginAttempted", "begun", "drawAttempted", "drawOk", "copyAttempted", "copyOk", "endAttempted", "ended"): _bool(record[key], f"triangle.record.{key}")
    _validate_result_int(record["beginResult"], "triangle.record.beginResult")
    _validate_result_int(record["endResult"], "triangle.record.endResult")
    _uint(record["drawVertexCount"], "triangle.record.drawVertexCount")
    if record["begun"] and (not record["beginAttempted"] or record["beginResult"] != 0): raise ValueError("triangle record begin contradiction")
    if record["drawOk"] and not record["drawAttempted"]: raise ValueError("triangle draw ok without attempt")
    if record["copyOk"] and not record["copyAttempted"]: raise ValueError("triangle copy ok without attempt")
    if record["ended"] and (not record["endAttempted"] or record["endResult"] != 0): raise ValueError("triangle record end contradiction")
    fence = _exact(triangle["fence"], {"createAttempted", "created", "result", "flags"}, "triangle.fence")
    _bool(fence["createAttempted"], "triangle.fence.createAttempted"); _bool(fence["created"], "triangle.fence.created")
    _validate_result_int(fence["result"], "triangle.fence.result")
    _uint(fence["flags"], "triangle.fence.flags")
    if fence["created"] and (not fence["createAttempted"] or fence["result"] != 0): raise ValueError("triangle fence witness contradiction")
    submit = _exact(triangle["submit"], {"attempted", "result", "submitCount", "waitAttempted", "waitResult", "waited", "timeoutNs", "signaled"}, "triangle.submit")
    _bool(submit["attempted"], "triangle.submit.attempted"); _bool(submit["waitAttempted"], "triangle.submit.waitAttempted")
    _bool(submit["waited"], "triangle.submit.waited"); _bool(submit["signaled"], "triangle.submit.signaled")
    _validate_result_int(submit["result"], "triangle.submit.result")
    _validate_result_int(submit["waitResult"], "triangle.submit.waitResult")
    _uint(submit["timeoutNs"], "triangle.submit.timeoutNs"); _uint(submit["submitCount"], "triangle.submit.submitCount")
    if submit["attempted"] and submit["submitCount"] == 0: raise ValueError("triangle submit has no submit info")
    if not submit["attempted"] and submit["submitCount"] != 0: raise ValueError("unattempted triangle submit has count")
    if submit["waited"] and (not submit["waitAttempted"] or not submit["attempted"]): raise ValueError("triangle wait witness contradiction")
    if submit["signaled"] and (not submit["waited"] or submit["waitResult"] != 0): raise ValueError("triangle signaled witness contradiction")
    if submit["waitAttempted"] and submit["timeoutNs"] == 0: raise ValueError("triangle wait has no timeout")
    if not submit["waitAttempted"] and (submit["waited"] or submit["signaled"]): raise ValueError("unattempted wait has result")
    invalidate = _exact(triangle["invalidate"], {"required", "attempted", "ok", "offset", "size"}, "triangle.invalidate")
    for key in ("required", "attempted", "ok"): _bool(invalidate[key], f"triangle.invalidate.{key}")
    for key in ("offset", "size"): _uint(invalidate[key], f"triangle.invalidate.{key}")
    if invalidate["ok"] and invalidate["required"] and not invalidate["attempted"]: raise ValueError("triangle invalidate ok without attempt")
    if not invalidate["required"] and invalidate["attempted"]: raise ValueError("triangle invalidate attempted when not required")
    readback = _exact(triangle["readback"], {"attempted", "verified", "byteSize", "pixelCount", "clearPixels", "trianglePixels", "hashHex"}, "triangle.readback")
    _bool(readback["attempted"], "triangle.readback.attempted"); _bool(readback["verified"], "triangle.readback.verified")
    for key in ("byteSize", "pixelCount", "clearPixels", "trianglePixels"): _uint(readback[key], f"triangle.readback.{key}")
    if not isinstance(readback["hashHex"], str): raise ValueError("triangle.readback.hashHex is not a string")
    if readback["attempted"] and readback["clearPixels"] + readback["trianglePixels"] != readback["pixelCount"]: raise ValueError("triangle readback pixel accounting mismatch")
    if readback["verified"] and (not readback["attempted"] or readback["clearPixels"] == 0 or readback["trianglePixels"] == 0): raise ValueError("triangle verified witness contradiction")
    if not readback["attempted"] and (readback["verified"] or readback["pixelCount"] != 0 or readback["byteSize"] != 0 or readback["clearPixels"] != 0 or readback["trianglePixels"] != 0): raise ValueError("unattempted readback has result")
    if readback["verified"]:
        if HEX16.fullmatch(readback["hashHex"]) is None: raise ValueError("triangle readback hash is not 16 hex")
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
    for key in ("icdCodeLoaded", "constructorsMayHaveExecuted", "vulkanFunctionsInvoked", "apiComplete", "gpuWorkload", "triangleRendered", "presentation", "d3d", "wow"): _bool(top[key], key)
    if top["icdCodeLoaded"] != load["loaded"] or top["constructorsMayHaveExecuted"] != load["attempted"] or top["vulkanFunctionsInvoked"] != (timing["callCount"] > 0): raise ValueError("scope flags overclaim or contradict witnesses")
    if any(top[key] for key in ("apiComplete", "presentation", "d3d", "wow")): raise ValueError("scope flags overclaim or contradict witnesses")
    if not isinstance(top["status"], str) or top["status"] not in VALID_STATUSES or not isinstance(top["failureStage"], str) or top["failureStage"] not in VALID_FAILURE_STAGES: raise ValueError("invalid status/failure stage")
    _bounded(top["failureDetail"], "failureDetail")
    if adb_exit is not None and top["nativeExit"] != adb_exit: raise ValueError("ADB exit differs from native exit")
    negative_results = any(item < 0 for item in (interface["result"], api["result"], instance["result"], physical["countResult"], physical["fillResult"], device["result"], image["result"], image_mem["allocateResult"], view["result"], render_pass["result"], framebuffer["result"], shaders["vertResult"], shaders["fragResult"], pipeline_layout["result"], pipeline["result"], staging["bufferResult"], staging["allocateResult"], pool["result"], cmdbuf["result"], record["beginResult"], record["endResult"], fence["result"], submit["result"], submit["waitResult"]))
    all_exports = all(item["found"] and item["ownerExact"] and not item["error"] for item in exports)
    required_functions = all(item["resolved"] and item["ownerExact"] and not item["error"] for item in functions[1:]) and (not api["enumerateInstanceVersionFound"] or (functions[0]["resolved"] and functions[0]["ownerExact"] and not functions[0]["error"]))
    staging_coherent = bool(staging["hostCoherent"])
    if invalidate["required"] != (staging["memoryTypeSelected"] and not staging_coherent): raise ValueError("triangle invalidate requirement contradicts staging memory type")
    success = (
        top["status"] == "TRIANGLE_OFFSCREEN_OBSERVED" and top["nativeExit"] == 0 and top["scopeValid"] and same_identity and top["icdIdentityStable"] and
        load["attempted"] and load["loaded"] and not load["dlerror"] and all_exports and interface["supported"] and interface["result"] == 0 and
        (api["legacy10"] or (api["enumerateInstanceVersionFound"] and api["ownerExact"] and api["result"] == 0 and _valid_vk_version(api["reported"]))) and
        required_functions and instance["createAttempted"] and instance["created"] and instance["result"] == 0 and physical["complete"] and
        physical["countResult"] == physical["fillResult"] == 0 and physical["reportedCount"] == physical["returnedCount"] == physical["retainedCount"] == 1 and
        physical["targetExact"] and queues["complete"] and selection["selected"] and device["createAttempted"] and device["created"] and
        device["result"] == 0 and device["queueCreateCount"] == 1 and device["queueFamilyIndex"] == selection["familyIndex"] and device["queueCount"] == 1 and type(device["priority"]) is float and device["priority"] == 1.0 and
        queue["getAttempted"] and queue["index"] == 0 and queue["nonNull"] and queue["observed"] and
        image["createAttempted"] and image["created"] and not image["handleReturnedOnError"] and image["result"] == 0 and image["format"] == TRIANGLE_FORMAT and image["extentWidth"] == TRIANGLE_EXTENT and image["extentHeight"] == TRIANGLE_EXTENT and image["usage"] == TRIANGLE_USAGE and image["tiling"] == TRIANGLE_TILING and image["samples"] == 1 and image["mipLevels"] == 1 and image["arrayLayers"] == 1 and image["sharingMode"] == 0 and
        image_mem["requirementsQueried"] and _power_of_two(image_mem["alignment"]) and image_mem["memoryTypeBits"] != 0 and
        image_mem["typeSelected"] and image_mem["deviceLocal"] and
        image_mem["allocateAttempted"] and image_mem["allocated"] and not image_mem["handleReturnedOnError"] and image_mem["allocateResult"] == 0 and image_mem["capacityBytes"] == TRIANGLE_ALLOCATION_CAP_BYTES and image_mem["requestedBytes"] >= image_mem["requirementsSize"] and image_mem["requestedBytes"] <= TRIANGLE_ALLOCATION_CAP_BYTES and
        bind["attempted"] and bind["ok"] and
        view["createAttempted"] and view["created"] and not view["handleReturnedOnError"] and view["result"] == 0 and
        render_pass["createAttempted"] and render_pass["created"] and not render_pass["handleReturnedOnError"] and render_pass["result"] == 0 and
        framebuffer["createAttempted"] and framebuffer["created"] and not framebuffer["handleReturnedOnError"] and framebuffer["result"] == 0 and
        shaders["vertCreateAttempted"] and shaders["vertCreated"] and shaders["vertResult"] == 0 and shaders["vertBytes"] > 0 and shaders["fragCreateAttempted"] and shaders["fragCreated"] and shaders["fragResult"] == 0 and shaders["fragBytes"] > 0 and
        pipeline_layout["createAttempted"] and pipeline_layout["created"] and pipeline_layout["result"] == 0 and
        pipeline["createAttempted"] and pipeline["created"] and not pipeline["handleReturnedOnError"] and pipeline["result"] == 0 and
        staging["bufferCreateAttempted"] and staging["bufferCreated"] and not staging["handleReturnedOnError"] and staging["bufferResult"] == 0 and staging["bufferSize"] == TRIANGLE_STAGING_BYTES and
        staging["memoryQueried"] and _power_of_two(staging["alignment"]) and staging["memoryTypeBits"] != 0 and
        staging["memoryTypeSelected"] and staging["hostVisible"] and
        staging["allocateAttempted"] and staging["allocated"] and staging["allocateResult"] == 0 and staging["capacityBytes"] == TRIANGLE_ALLOCATION_CAP_BYTES and staging["requestedBytes"] >= staging["memorySize"] and staging["requestedBytes"] <= TRIANGLE_ALLOCATION_CAP_BYTES and
        staging["bindAttempted"] and staging["bindOk"] and
        staging["mapAttempted"] and staging["mapped"] and staging["nonNull"] and
        pool["createAttempted"] and pool["created"] and pool["result"] == 0 and pool["flags"] == TRIANGLE_POOL_FLAGS and pool["queueFamilyIndex"] == selection["familyIndex"] and
        cmdbuf["allocateAttempted"] and cmdbuf["allocated"] and cmdbuf["result"] == 0 and cmdbuf["level"] == 0 and
        record["beginAttempted"] and record["begun"] and record["beginResult"] == 0 and record["drawAttempted"] and record["drawOk"] and record["drawVertexCount"] == 3 and record["copyAttempted"] and record["copyOk"] and record["endAttempted"] and record["ended"] and record["endResult"] == 0 and
        fence["createAttempted"] and fence["created"] and fence["result"] == 0 and fence["flags"] == 0 and
        submit["attempted"] and submit["result"] == 0 and submit["submitCount"] == 1 and submit["waitAttempted"] and submit["waitResult"] == 0 and submit["waited"] and submit["timeoutNs"] == TRIANGLE_FENCE_TIMEOUT_NS and submit["signaled"] and
        ((invalidate["required"] and invalidate["attempted"] and invalidate["ok"] and invalidate["offset"] == 0 and invalidate["size"] == TRIANGLE_STAGING_BYTES) or ((not invalidate["required"]) and not invalidate["attempted"])) and
        readback["attempted"] and readback["verified"] and readback["byteSize"] == TRIANGLE_STAGING_BYTES and readback["pixelCount"] == TRIANGLE_PIXEL_COUNT and readback["clearPixels"] > 0 and readback["trianglePixels"] > 0 and readback["clearPixels"] + readback["trianglePixels"] == TRIANGLE_PIXEL_COUNT and HEX16.fullmatch(readback["hashHex"]) is not None and
        timing["valid"] and timing["callCount"] > 0 and
        cleanup["deviceDestroyAttempted"] and cleanup["deviceDestroyOk"] and cleanup["instanceDestroyAttempted"] and cleanup["instanceDestroyOk"] and cleanup["dlcloseAttempted"] and cleanup["dlcloseOk"] and cleanup["complete"] and
        top["gpuWorkload"] and top["triangleRendered"] and not top["apiComplete"] and not top["presentation"] and not top["d3d"] and not top["wow"] and
        top["failureStage"] == "none" and not top["failureDetail"]
    )
    if top["nativeExit"] == 0 and top["status"] != "TRIANGLE_OFFSCREEN_OBSERVED": raise ValueError("non-success native result exited zero")
    if top["status"] == "TRIANGLE_OFFSCREEN_OBSERVED" and not success: raise ValueError("native success gates are incomplete")
    if top["status"] == "VK_ERROR" and (top["nativeExit"] != 1 or not negative_results): raise ValueError("VK_ERROR lacks a genuine signed VkResult")
    if top["status"] == "NO_QUEUE" and (top["nativeExit"] != 1 or not queues["complete"] or expected_selection is not None): raise ValueError("NO_QUEUE lacks complete count witness")
    if top["status"] == "NO_MEMORY_TYPE" and (top["nativeExit"] != 1 or not ((image_mem["requirementsQueried"] and not image_mem["typeSelected"] and not image_mem["allocated"]) or (staging["memoryQueried"] and not staging["memoryTypeSelected"] and not staging["allocated"]))): raise ValueError("NO_MEMORY_TYPE lacks queried requirements without selection")
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
        "apiComplete": False, "gpuWorkload": False, "triangleRendered": False, "presentation": False, "d3d": False, "wow": False,
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
        "native": native_document, "outputErrors": output_errors, "stopBeforePresentationRequired": status != "TRIANGLE_OFFSCREEN_OBSERVED" or runner_error,
        "boundary": "one 64x64 offscreen triangle/fence/readback control witness only; not presentation, swapchain, D3D, DXVK, Wine, WoW, or API compatibility",
        "output": str(out), "icdCodeLoaded": bool(native_document and native_document.get("icdCodeLoaded") is True),
        "constructorsMayHaveExecuted": bool(native_document and native_document.get("constructorsMayHaveExecuted") is True),
        "vulkanFunctionsInvoked": bool(native_document and native_document.get("vulkanFunctionsInvoked") is True),
        "apiComplete": False, "gpuWorkload": bool(native_document and native_document.get("gpuWorkload") is True), "triangleRendered": bool(native_document and native_document.get("triangleRendered") is True), "presentation": False, "d3d": False, "wow": False,
    }
    try: _write_exclusive(out / "summary.json", (json.dumps(summary, sort_keys=True, indent=2, default=str) + "\n").encode())
    except Exception as exc: output_errors.append(f"summary: {exc}")
    # CLI is intentionally a small, stable decision record: no public paths,
    # feature flags, or artifact details are printed.
    print(json.dumps({key: summary[key] for key in ("schema", "runId", "status", "nativeExit", "timedOut", "diagnostic", "stopBeforePresentationRequired")}, separators=(",", ":")))
    exact_pass = status == "TRIANGLE_OFFSCREEN_OBSERVED" and process is not None and process.returncode == 0 and force_stop is None and not output_errors and not remaining
    return 0 if exact_pass else 1


if __name__ == "__main__":
    raise SystemExit(main())
