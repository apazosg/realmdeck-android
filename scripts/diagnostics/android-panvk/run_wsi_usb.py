#!/usr/bin/env python3
"""Host-only supervisor for the approved PanVK Android WSI witness.

This program deliberately does not install or copy anything to the device.  The
debug APK owns the private WSI directory and creates its run id.  The host only
starts the already-installed activity, reads that directory through ``run-as``,
and binds the native witness to two carefully scoped screenshots.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shlex
import struct
import subprocess
import time
import uuid
import zlib
from pathlib import Path
from typing import Any, Iterable


ADB = "/home/adrian/android-sdk/platform-tools/adb"
SERIAL_ENV_VAR = "WOWFOREVER_ADB_SERIAL"
SERIAL = os.environ.get(SERIAL_ENV_VAR, "")


def require_serial() -> str:
    if not SERIAL:
        raise RuntimeError("WOWFOREVER_ADB_SERIAL is not set (explicit device selection required)")
    return SERIAL
PACKAGE = "com.wowforever.arm64probe"
UID = 10330
VERSION_CODE = 47
VERSION_NAME = "0.2.23-automated"
API_LEVEL = 34
ACTIVITY = "app.gamenative.PanvkWsiActivity"
COMPONENT = f"{PACKAGE}/{ACTIVITY}"
PROCESS_NAME = f"{PACKAGE}:panvk_wsi"
PRIVATE_PREFIX = "files/forever/controls/panvk-wsi"
CURRENT_RELATIVE = f"{PRIVATE_PREFIX}/current.json"
RUN_RE = re.compile(r"^PWSI-[0-9a-f]{32}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
ICD_NAME = "libvulkan_panfrost.so"
ICD_BYTES = 31_142_504
ICD_SHA256 = "5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9"
SURFACE_W = 320
SURFACE_H = 240
NATIVE_LIMIT = 64 * 1024
JOURNAL_LIMIT = 64 * 1024
JOURNAL_ROWS = 256
DEADLINE_SECONDS = 20.0
MAX_PNG_BYTES = 8 * 1024 * 1024
MAX_SCREEN_DIMENSION = 4096

SCHEMA = "wowforever.android-panvk-wsi"
VALID_NATIVE_STATUSES = {"API_PRESENT_COMPLETED", "VK_ERROR", "UNSUPPORTED", "CANCELLED", "UNKNOWN"}


def unique_json_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON field: {key}")
        result[key] = value
    return result


def _exact(value: object, keys: Iterable[str], label: str) -> dict[str, Any]:
    expected = set(keys)
    if not isinstance(value, dict) or set(value) != expected:
        raise ValueError(f"{label} schema mismatch")
    return value


def _int(value: object, label: str, *, positive: bool = False, nonnegative: bool = False) -> int:
    if type(value) is not int or (positive and value <= 0) or (nonnegative and value < 0):
        raise ValueError(f"{label} is not a valid integer")
    return value


def _bool(value: object, label: str) -> bool:
    if type(value) is not bool:
        raise ValueError(f"{label} is not boolean")
    return value


def _string(value: object, label: str, limit: int = 4096) -> str:
    if not isinstance(value, str) or len(value.encode("utf-8")) > limit:
        raise ValueError(f"{label} is not a bounded string")
    return value


def adb(*args: str, timeout: float = 15) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run([ADB, "-s", require_serial(), *args], capture_output=True, timeout=timeout, check=False)


def _require(result: subprocess.CompletedProcess[bytes], stage: str, limit: int = 128 * 1024) -> bytes:
    if len(result.stdout) > limit or len(result.stderr) > limit:
        raise RuntimeError(f"{stage} output exceeded bound")
    if result.returncode:
        detail = result.stderr[:512].decode("utf-8", "replace")
        raise RuntimeError(f"{stage} failed: {detail}")
    return result.stdout


def _external_parent(value: Path) -> Path:
    parent = value.absolute()
    repo = Path(__file__).resolve().parents[3]
    if not parent.is_dir() or parent.is_symlink() or parent.resolve().is_relative_to(repo):
        raise ValueError("evidence parent must be an existing external directory")
    if any(part.is_symlink() for part in (parent, *parent.parents)):
        raise ValueError("evidence parent contains a symlink")
    return parent


def _run_path(run: str) -> str:
    if RUN_RE.fullmatch(run) is None:
        raise ValueError("invalid WSI run id")
    return f"{PRIVATE_PREFIX}/{run}"


def _read_owned(relative: str, limit: int) -> bytes:
    allowed = {CURRENT_RELATIVE}
    match = re.fullmatch(rf"{re.escape(PRIVATE_PREFIX)}/(PWSI-[0-9a-f]{{32}})/(state\.json|result\.json|native\.jsonl)", relative)
    if match:
        allowed.add(relative)
    if relative not in allowed:
        raise ValueError("unapproved private read path")
    quoted = shlex.quote(relative)
    script = f"if test ! -e {quoted}; then printf 'PWSI_MISSING\\n'; else test ! -L {quoted} && test -f {quoted} && printf 'PWSI_DATA\\n' && head -c {limit + 1} {quoted}; fi"
    raw = _require(adb("exec-out", "run-as", PACKAGE, "sh", "-c", script, timeout=5), f"read {relative}", limit + 32)
    if raw == b"PWSI_MISSING\n":
        raise FileNotFoundError(relative)
    if not raw.startswith(b"PWSI_DATA\n") or len(raw[10:]) > limit:
        raise ValueError("private WSI file missing sentinel or exceeds bound")
    return raw[10:]


def _package_preflight() -> dict[str, Any]:
    if _require(adb("get-state"), "device authorization").strip() != b"device":
        raise RuntimeError("device is not authorized")
    package = _require(adb("exec-out", "dumpsys", "package", PACKAGE), "package metadata", 256 * 1024).decode("utf-8", "replace")
    if not re.search(rf"\bversionCode={VERSION_CODE}(?:\D|$)", package):
        raise RuntimeError("installed package versionCode is not the fixed WSI build")
    if not re.search(rf"\bversionName={re.escape(VERSION_NAME)}(?:\s|$)", package):
        raise RuntimeError("installed package versionName is not the fixed WSI build")
    if "DEBUGGABLE" not in package:
        raise RuntimeError("installed WSI package is not debuggable")
    uid = _require(adb("exec-out", "run-as", PACKAGE, "id", "-u"), "app UID", 128).strip()
    if uid != str(UID).encode():
        raise RuntimeError("app UID is not 10330")
    api = _require(adb("shell", "getprop", "ro.build.version.sdk"), "device API", 128).strip()
    if api != str(API_LEVEL).encode():
        raise RuntimeError("device API level is not 34")
    root = _require(adb("exec-out", "run-as", PACKAGE, "pwd"), "app data root", 512).decode().strip()
    if root not in {f"/data/user/0/{PACKAGE}", f"/data/data/{PACKAGE}"}:
        raise RuntimeError("unexpected app data root")
    return {"uid": UID, "packageRoot": root, "versionCode": VERSION_CODE, "versionName": VERSION_NAME, "api": API_LEVEL}


def activity_command(start: bool = False) -> tuple[str, ...]:
    command = ("shell", "am", "start", "-W", "-n", COMPONENT)
    return command + (("--ez", "start", "true") if start else ())


def own_processes() -> list[dict[str, Any]]:
    raw = _require(adb("shell", "ps", "-A", "-o", "UID,PID,NAME"), "own UID process list", 32 * 1024)
    aliases = {str(UID), f"u0_a{UID - 10000}"}
    rows: list[dict[str, Any]] = []
    for line in raw.decode("utf-8", "replace").splitlines()[1:]:
        fields = line.split(None, 2)
        if len(fields) == 3 and fields[0] in aliases and fields[1].isdigit():
            rows.append({"uid": UID, "pid": int(fields[1]), "name": fields[2]})
    return rows


def _keyguard() -> dict[str, bool]:
    raw = _require(adb("exec-out", "dumpsys", "window", "policy"), "keyguard state", 64 * 1024).decode("utf-8", "replace")
    secure_match = re.search(r"(?:isKeyguardSecure|mIsKeyguardSecure)\s*=\s*(true|false)", raw, re.I)
    if secure_match is None:
        secure_match = re.search(r"(?m)^\s*secure=(true|false)\s*$", raw)
    if not secure_match:
        raise RuntimeError("keyguard secure state was not explicitly observed")
    secure = secure_match.group(1).lower() == "true"
    locked_match = re.search(r"(?:isKeyguardLocked|mShowingLockscreen|mKeyguardShowing)\s*=\s*(true|false)", raw, re.I) or re.search(r"(?m)^\s*showing=(true|false)\s*$", raw)
    if locked_match is None:
        raise RuntimeError("keyguard lock state was not explicitly observed")
    locked = locked_match.group(1).lower() == "true"
    if secure and locked:
        raise RuntimeError("secure keyguard is locked; owner must unlock the device")
    if locked and not secure:
        _require(adb("shell", "wm", "dismiss-keyguard"), "dismiss insecure keyguard", 1024)
    return {"secure": secure, "locked": locked}


def _foreground() -> dict[str, Any]:
    raw = _require(adb("exec-out", "dumpsys", "window"), "foreground window", 96 * 1024).decode("utf-8", "replace")
    values: dict[str, str | None] = {"mCurrentFocus": None, "mFocusedApp": None}
    for key in values:
        line = next((line for line in raw.splitlines() if key in line), "")
        match = re.search(rf"{key}=.*?\b(u\d+\s+)?({re.escape(PACKAGE)}/[A-Za-z0-9_.$]+)", line)
        values[key] = match.group(2) if match else None
    exact = all(values[key] == COMPONENT for key in values)
    return {"currentFocus": values["mCurrentFocus"], "focusedApp": values["mFocusedApp"], "exact": exact, "component": COMPONENT}


def _proc_stat(pid: int) -> dict[str, Any]:
    if pid <= 0:
        raise ValueError("invalid process id")
    stat_raw = _read_proc(pid, "stat", 8192).decode("utf-8", "replace").strip()
    closing = stat_raw.rfind(")")
    if closing < 0:
        raise ValueError("malformed /proc stat")
    fields = stat_raw[closing + 2 :].split()
    if len(fields) <= 19 or fields[0] in {"Z", "X"} or int(stat_raw.split(" ", 1)[0]) != pid:
        raise ValueError("truncated /proc stat")
    start_ticks = _int(int(fields[19]), "process startTicks", positive=True)
    status = _read_proc(pid, "status", 8192).decode("utf-8", "replace")
    uid_match = re.search(r"^Uid:\s+(\d+)", status, re.M)
    if not uid_match or int(uid_match.group(1)) != UID:
        raise ValueError("process UID is not owned")
    cmdline = _read_proc(pid, "cmdline", 1024).replace(b"\0", b"").decode("utf-8", "replace")
    return {"pid": pid, "uid": UID, "startTicks": start_ticks, "processName": cmdline}


def _read_proc(pid: int, name: str, limit: int) -> bytes:
    if name not in {"stat", "status", "cmdline"}:
        raise ValueError("unapproved proc file")
    return _require(adb("exec-out", "run-as", PACKAGE, "cat", f"/proc/{pid}/{name}", timeout=5), f"read process {name}", limit + 1)


def _state_shape(value: object, label: str = "state") -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{label} is not an object")
    for key in ("schema", "version", "runId", "generation", "pid", "uid", "startTicks", "processName", "rootPath", "status", "available", "surfaceWidth", "surfaceHeight", "bounds"):
        if key not in value:
            raise ValueError(f"{label} missing {key}")
    if value["schema"] != SCHEMA or value["version"] != 1 or RUN_RE.fullmatch(value["runId"]) is None:
        raise ValueError(f"{label} schema or run id mismatch")
    _int(value["generation"], f"{label}.generation", positive=True)
    _int(value["pid"], f"{label}.pid", positive=True)
    if value["startTicks"] is not None:
        _int(value["startTicks"], f"{label}.startTicks", positive=True)
    _int(value["uid"], f"{label}.uid", positive=True)
    if value["uid"] != UID or value["processName"] != PROCESS_NAME:
        raise ValueError(f"{label} process identity mismatch")
    root = _string(value["rootPath"], f"{label}.rootPath")
    allowed_roots = {f"/data/user/0/{PACKAGE}/files/forever/controls/panvk-wsi", f"/data/data/{PACKAGE}/files/forever/controls/panvk-wsi"}
    if root not in allowed_roots:
        raise ValueError(f"{label} root is outside WSI scope")
    if value["status"] not in {"READY", "STARTING", "RUNNING", "RESULT", "CANCELLED", "UNKNOWN", "ERROR"}:
        raise ValueError(f"{label} status is invalid")
    _bool(value["available"], f"{label}.available")
    holder = value.get("holder")
    holder_width = value.get("holderWidth") if holder is None else holder.get("width") if isinstance(holder, dict) else None
    holder_height = value.get("holderHeight") if holder is None else holder.get("height") if isinstance(holder, dict) else None
    dimensions = (("surfaceWidth", value["surfaceWidth"]), ("surfaceHeight", value["surfaceHeight"]), ("holderWidth", holder_width), ("holderHeight", holder_height))
    for key, dimension in dimensions:
        if _int(dimension, f"{label}.{key}") != (SURFACE_W if "Width" in key else SURFACE_H):
            raise ValueError(f"{label} surface dimensions are not 320x240")
    bounds = value["bounds"]
    if isinstance(bounds, dict):
        bounds = [bounds.get(name) for name in ("x0", "y0", "x1", "y1")]
    if not isinstance(bounds, list) or len(bounds) != 4 or any(type(item) is not int for item in bounds):
        raise ValueError(f"{label}.bounds is invalid")
    if bounds[0] < 0 or bounds[1] < 0 or bounds[2] - bounds[0] != SURFACE_W or bounds[3] - bounds[1] != SURFACE_H:
        raise ValueError(f"{label}.bounds is not the physical 320x240 ROI")
    roi = value.get("physicalROI", [SURFACE_W, SURFACE_H])
    if isinstance(roi, dict):
        roi_size = (roi.get("width"), roi.get("height"))
    elif isinstance(roi, list) and len(roi) == 2:
        roi_size = (roi[0], roi[1])
    elif isinstance(roi, list) and len(roi) == 4 and roi[0] == 0 and roi[1] == 0:
        roi_size = (roi[2], roi[3])
    else:
        raise ValueError(f"{label}.physicalROI is invalid")
    if roi_size != (SURFACE_W, SURFACE_H):
        raise ValueError(f"{label}.physicalROI is not 320x240")
    pin = value.get("icdPin", value.get("assetMetadata", value.get("asset")))
    if pin is None:
        pin = {"path": value.get("icdPath"), "bytes": value.get("assetSize"), "sha256": value.get("assetSha256")}
    if not isinstance(pin, dict):
        raise ValueError(f"{label} ICD pin is missing")
    if pin.get("path") != f"{root}/{value['runId']}/{ICD_NAME}" or pin.get("bytes") != ICD_BYTES or pin.get("sha256") != ICD_SHA256:
        raise ValueError(f"{label} ICD pin mismatch")
    return {**value, "icdPin": pin}


def validate_state(value: object, run: str | None = None, *, qualification: bool = False) -> dict[str, Any]:
    state = _state_shape(value)
    if run is not None and state["runId"] != run:
        raise ValueError("state run id mismatch")
    if qualification and (state["status"] != "READY" or state["available"] is not True or state["startTicks"] is None):
        raise ValueError("state is not a qualifying READY witness")
    return state


parse_state = validate_state


def read_state(run: str | None = None) -> dict[str, Any]:
    current = json.loads(_read_owned(CURRENT_RELATIVE, 128 * 1024).decode("utf-8"), object_pairs_hook=unique_json_pairs)
    if not isinstance(current, dict) or not isinstance(current.get("runId"), str):
        raise ValueError("current.json is malformed")
    actual_run = current["runId"]
    if run is not None and actual_run != run:
        raise ValueError("current run does not match expected run")
    expected_state_suffix = f"/files/forever/controls/panvk-wsi/{actual_run}/state.json"
    for key in ("statePath", "stateFile"):
        if key in current and (not isinstance(current[key], str) or not current[key].endswith(expected_state_suffix)):
            raise ValueError("current state path is outside the selected run")
    state_raw = _read_owned(f"{PRIVATE_PREFIX}/{actual_run}/state.json", 128 * 1024)
    state = validate_state(json.loads(state_raw.decode("utf-8"), object_pairs_hook=unique_json_pairs), actual_run)
    for key in ("runId", "generation", "pid", "uid", "startTicks", "processName", "rootPath"):
        if key in current and current[key] != state[key]:
            raise ValueError(f"current/state metadata mismatch: {key}")
    latest = json.loads(_read_owned(CURRENT_RELATIVE, 128 * 1024).decode("utf-8"), object_pairs_hook=unique_json_pairs)
    if not isinstance(latest, dict) or any(latest.get(key) != current.get(key) for key in ("runId", "generation", "pid", "uid")):
        raise ValueError("current identity changed during state snapshot")
    # State and current are separate atomic publications. Marker status is a hint,
    # not an equality witness across READY -> STARTING -> RUNNING -> RESULT.
    return state


def icd_owner_matches(actual: str, requested: str) -> bool:
    roots = (f"/data/data/{PACKAGE}/", f"/data/user/0/{PACKAGE}/")
    for root in roots:
        if requested.startswith(root):
            return actual in {other + requested[len(root):] for other in roots}
    return False


def _png_rows(raw: bytes) -> tuple[int, int, list[bytes]]:
    if len(raw) > MAX_PNG_BYTES or not raw.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError("screenshot is not a bounded PNG")
    pos = 8
    idat = bytearray()
    width = height = None
    color = None
    saw_iend = False
    while pos < len(raw):
        if pos + 12 > len(raw):
            raise ValueError("truncated PNG chunk")
        size = struct.unpack(">I", raw[pos : pos + 4])[0]
        kind = raw[pos + 4 : pos + 8]
        end = pos + 12 + size
        if size > MAX_PNG_BYTES or end > len(raw):
            raise ValueError("oversized or truncated PNG chunk")
        data = raw[pos + 8 : pos + 8 + size]
        crc = struct.unpack(">I", raw[pos + 8 + size : end])[0]
        if zlib.crc32(kind + data) & 0xFFFFFFFF != crc:
            raise ValueError("PNG chunk checksum mismatch")
        if kind == b"IHDR":
            if len(data) != 13 or width is not None:
                raise ValueError("invalid PNG IHDR")
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", data)
            if not (0 < width <= MAX_SCREEN_DIMENSION and 0 < height <= MAX_SCREEN_DIMENSION and depth == 8 and color in (2, 6) and compression == filtering == interlace == 0):
                raise ValueError("unsupported PNG dimensions or format")
        elif kind == b"IDAT":
            idat.extend(data)
            if len(idat) > MAX_PNG_BYTES:
                raise ValueError("PNG IDAT exceeds bound")
        elif kind == b"IEND":
            if size != 0:
                raise ValueError("invalid PNG IEND")
            saw_iend = True
            break
        pos = end
    if width is None or height is None or not idat or not saw_iend or pos + 12 + size != len(raw):
        raise ValueError("incomplete PNG")
    channels = 4 if color == 6 else 3
    expected = height * (1 + width * channels)
    try:
        decompressor = zlib.decompressobj()
        inflated = decompressor.decompress(bytes(idat), expected + 1)
        if not decompressor.eof or decompressor.unused_data or decompressor.unconsumed_tail:
            raise ValueError("PNG compressed body exceeds exact scanline bound")
    except zlib.error as exc:
        raise ValueError("corrupt PNG compressed data") from exc
    if len(inflated) != expected:
        raise ValueError("PNG scanline length mismatch")
    rows: list[bytes] = []
    previous = bytearray(width * channels)
    offset = 0
    for _ in range(height):
        filter_type = inflated[offset]
        source = inflated[offset + 1 : offset + 1 + width * channels]
        offset += 1 + width * channels
        if filter_type > 4:
            raise ValueError("PNG filter is out of range")
        row = bytearray(source)
        for i in range(len(row)):
            left = row[i - channels] if i >= channels else 0
            up = previous[i]
            upper_left = previous[i - channels] if i >= channels else 0
            if filter_type == 1:
                row[i] = (row[i] + left) & 255
            elif filter_type == 2:
                row[i] = (row[i] + up) & 255
            elif filter_type == 3:
                row[i] = (row[i] + ((left + up) // 2)) & 255
            elif filter_type == 4:
                p = left + up - upper_left
                pa, pb, pc = abs(p - left), abs(p - up), abs(p - upper_left)
                predictor = left if pa <= pb and pa <= pc else up if pb <= pc else upper_left
                row[i] = (row[i] + predictor) & 255
        rows.append(bytes(row))
        previous = row
    return width, height, rows


def analyze_png(raw: bytes, bounds: list[int] | dict[str, int]) -> dict[str, Any]:
    width, height, rows = _png_rows(raw)
    if isinstance(bounds, dict):
        bounds = [bounds[name] for name in ("x0", "y0", "x1", "y1")]
    if len(bounds) != 4 or bounds[2] - bounds[0] != SURFACE_W or bounds[3] - bounds[1] != SURFACE_H:
        raise ValueError("screenshot ROI is not 320x240")
    x0, y0, x1, y1 = bounds
    if not (0 <= x0 < x1 <= width and 0 <= y0 < y1 <= height):
        raise ValueError("screenshot ROI is outside the PNG")
    channels = len(rows[0]) // width
    pixels = [rows[y][x0 * channels : x1 * channels] for y in range(y0, y1)]
    total = SURFACE_W * SURFACE_H
    black = red = green = 0
    for row in pixels:
        for x in range(0, len(row), channels):
            r, g, b = row[x : x + 3]
            black += r <= 8 and g <= 8 and b <= 8
            red += r > 200 and g < 50 and b < 50
            green += g > 200 and r < 50 and b < 50
    return {"width": width, "height": height, "roi": [x0, y0, x1, y1], "blackRatio": black / total, "redRatio": red / total, "greenRatio": green / total, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}


def _native_exact(raw: bytes) -> dict[str, Any]:
    if not raw or len(raw) > NATIVE_LIMIT or raw.count(b"\n") > 1:
        raise ValueError("native result is not one bounded JSON object")
    document = raw[:-1] if raw.endswith(b"\n") else raw
    value = json.loads(document.decode("utf-8"), object_pairs_hook=unique_json_pairs)
    if not isinstance(value, dict):
        raise ValueError("native result is not an object")
    return value


def parse_native(raw: bytes, run: str, state: dict[str, Any], *, proc: dict[str, Any] | None = None) -> dict[str, Any]:
    state = validate_state(state, run)
    top = _native_exact(raw)
    keys = {"schema", "version", "runId", "pid", "uid", "generation", "startNs", "endNs", "clockValid", "scopeValid", "procValid", "loadAttempted", "loadLoaded", "icdPath", "icdBytes", "icdSha256", "icdAfterSha256", "afterSHA", "icdIdentityStable", "icdOwner", "icdOwnerBefore", "icdOwnerAfter", "surface", "instance", "physical", "queue", "device", "surfaceCapabilities", "swapchain", "formatCount", "modeCount", "frames", "apiPresentSuccess", "counts", "pfns", "calls", "cancelRequested", "cleanup", "journal", "androidPresentationObserved", "wine", "d3d", "wow", "status", "failureStage", "failureDetail", "nativeExit"}
    if set(top) != keys or top["schema"] != SCHEMA or type(top["version"]) is not int or top["version"] != 1 or top["runId"] != run:
        raise ValueError("native schema mismatch")
    for key in ("pid", "uid", "generation", "startNs", "endNs", "nativeExit", "icdBytes"):
        _int(top[key], f"native.{key}", nonnegative=key == "nativeExit")
    if top["pid"] != state["pid"] or top["uid"] != UID or top["generation"] != state["generation"]:
        raise ValueError("native process binding mismatch")
    if proc and (proc["pid"] != top["pid"] or proc["startTicks"] != state["startTicks"]):
        raise ValueError("native /proc binding mismatch")
    if top["endNs"] < top["startNs"] or not all(_bool(top[key], key) for key in ("clockValid", "scopeValid", "procValid", "icdIdentityStable")):
        raise ValueError("native timing/scope witness invalid")
    state_pin = state.get("icdPin", state.get("assetMetadata", state.get("asset")))
    expected_icd = state_pin.get("path") if isinstance(state_pin, dict) else None
    if top["icdBytes"] != ICD_BYTES or top["icdSha256"] != ICD_SHA256 or top["icdAfterSha256"] != ICD_SHA256 or top["afterSHA"] != ICD_SHA256 or top["icdPath"] != expected_icd:
        raise ValueError("native ICD pin mismatch")
    for key in ("icdPath", "icdOwner", "icdOwnerBefore", "icdOwnerAfter"):
        _string(top[key], f"native.{key}")
    if not (top["icdOwner"] == top["icdOwnerBefore"] == top["icdOwnerAfter"] and icd_owner_matches(top["icdOwner"], top["icdPath"])):
        raise ValueError("native ICD owner changed")
    if top["status"] not in VALID_NATIVE_STATUSES or type(top["apiPresentSuccess"]) is not bool or type(top["cancelRequested"]) is not bool:
        raise ValueError("native status or result flags are invalid")
    if top["apiPresentSuccess"] is False:
        if top["status"] == "API_PRESENT_COMPLETED" or any(top[name] is not False for name in ("androidPresentationObserved", "wine", "d3d", "wow")):
            raise ValueError("failed native result overclaims presentation")
        return top
    if top["status"] != "API_PRESENT_COMPLETED" or top["nativeExit"] != 0 or top["cancelRequested"] is not False:
        raise ValueError("successful API witness has a failed native status")
    if top["loadAttempted"] is not True or top["loadLoaded"] is not True or top["failureStage"] != "none" or not isinstance(top["failureDetail"], str):
        raise ValueError("successful native load witness is incomplete")
    numeric_blocks = {
        "surface": ("width", "height", "createResult"),
        "instance": ("extensionCount", "createResult"),
        "physical": ("count", "vendorId", "deviceId"),
        "queue": ("familyCount", "familyIndex", "flags"),
        "device": ("extensionCount", "createResult"),
        "surfaceCapabilities": ("minImageCount", "maxImageCount", "usage", "transforms", "alpha"),
        "swapchain": ("createResult", "imageCount", "format", "colorSpace", "extentWidth", "extentHeight", "preTransform", "compositeAlpha"),
        "frames": ("count", "max", "presentSuccessCount"),
        "counts": ("calls", "callCapacity", "journalRows", "journalRowCapacity", "journalBytes", "journalByteCapacity", "presentSuccess", "physical", "queueFamilies", "deviceExtensions"),
        "cleanup": ("queueWaitIdleResult",),
        "journal": ("rows", "rowCapacity", "bytes", "byteCapacity"),
    }
    for block, fields in numeric_blocks.items():
        if not isinstance(top.get(block), dict):
            raise ValueError("native numeric block missing")
        for field in fields:
            _int(top[block].get(field), f"{block}.{field}")
    surface = _exact(top["surface"], {"fromSurface", "windowOwned", "width", "height", "createResult", "created"}, "surface")
    if surface["width"] != SURFACE_W or surface["height"] != SURFACE_H or not _bool(surface["fromSurface"], "surface.fromSurface") or not _bool(surface["windowOwned"], "surface.windowOwned") or not _bool(surface["created"], "surface.created") or surface["createResult"] != 0:
        raise ValueError("surface witness is incomplete")
    instance = _exact(top["instance"], {"extensionCount", "createResult", "created"}, "instance")
    if not (0 <= _int(instance["extensionCount"], "instance.extensionCount") <= 256 and instance["createResult"] == 0 and instance["created"] is True):
        raise ValueError("instance witness is incomplete")
    physical = _exact(top["physical"], {"count", "vendorId", "deviceId", "name"}, "physical")
    if any(type(physical[key]) is not int for key in ("count", "vendorId", "deviceId")) or not isinstance(physical["name"], str) or physical["count"] != 1 or physical["vendorId"] != 0x13B5 or physical["deviceId"] != 0xA8670000 or physical["name"] != "Mali-G610 MC6":
        raise ValueError("physical device is not the approved target")
    queue = _exact(top["queue"], {"familyCount", "familyIndex", "flags", "surfaceSupport"}, "queue")
    if any(type(queue[key]) is not int for key in ("familyCount", "familyIndex", "flags")) or not (0 < queue["familyCount"] <= 32 and queue["familyIndex"] >= 0 and queue["flags"] & 1 and queue["surfaceSupport"] is True):
        raise ValueError("queue family witness is incomplete")
    device = _exact(top["device"], {"extensionCount", "createResult", "created"}, "device")
    if not (0 <= device["extensionCount"] <= 256 and device["createResult"] == 0 and device["created"] is True):
        raise ValueError("device witness is incomplete")
    caps = _exact(top["surfaceCapabilities"], {"minImageCount", "maxImageCount", "minExtent", "maxExtent", "currentExtent", "usage", "transforms", "alpha"}, "surfaceCapabilities")
    for key in ("minExtent", "maxExtent", "currentExtent"):
        if not isinstance(caps[key], list) or len(caps[key]) != 2 or any(type(item) is not int for item in caps[key]):
            raise ValueError("surface capability extent is malformed")
    if caps["currentExtent"] != [SURFACE_W, SURFACE_H] or caps["minExtent"][0] > SURFACE_W or caps["minExtent"][1] > SURFACE_H or caps["maxExtent"][0] < SURFACE_W or caps["maxExtent"][1] < SURFACE_H:
        raise ValueError("surface capability bounds do not contain 320x240")
    swap = _exact(top["swapchain"], {"createResult", "imageCount", "format", "colorSpace", "extentWidth", "extentHeight", "preTransform", "compositeAlpha"}, "swapchain")
    if not (swap["createResult"] == 0 and 2 <= swap["imageCount"] <= 4 and swap["format"] == 37 and swap["colorSpace"] == 0 and swap["extentWidth"] == SURFACE_W and swap["extentHeight"] == SURFACE_H and swap["preTransform"] == 1 and swap["compositeAlpha"] == 1):
        raise ValueError("swapchain witness is incomplete")
    if type(top["formatCount"]) is not int or type(top["modeCount"]) is not int or not (0 <= top["formatCount"] <= 32 and 0 <= top["modeCount"] <= 16):
        raise ValueError("format/mode bounds exceeded")
    frames = _exact(top["frames"], {"count", "max", "presentSuccessCount", "items"}, "frames")
    items = frames["items"]
    if frames["count"] != 3 or frames["max"] != 3 or frames["presentSuccessCount"] != 3 or not isinstance(items, list) or len(items) != 3:
        raise ValueError("frame witness is incomplete")
    for index, frame in enumerate(items):
        item = _exact(frame, {"slot", "imageIndex", "acquireCalled", "acquireResult", "submitCalled", "submitResult", "presentCalled", "presentResult", "fenceCalled", "fenceResult"}, f"frame {index}")
        for name in ("slot", "imageIndex", "acquireResult", "submitResult", "presentResult", "fenceResult"):
            _int(item[name], f"frame.{name}")
        if item["slot"] != index or not (0 <= item["imageIndex"] < swap["imageCount"]):
            raise ValueError("frame slot/index mismatch")
        for name in ("acquire", "submit", "present", "fence"):
            if item[f"{name}Called"] is not True or item[f"{name}Result"] != 0:
                raise ValueError("frame API result is not VK_SUCCESS")
    counts = _exact(top["counts"], {"calls", "callCapacity", "journalRows", "journalRowCapacity", "journalBytes", "journalByteCapacity", "presentSuccess", "physical", "queueFamilies", "deviceExtensions"}, "counts")
    if not (0 <= counts["calls"] <= 96 and counts["callCapacity"] == 96 and 0 <= counts["journalRows"] <= JOURNAL_ROWS and counts["journalRowCapacity"] == JOURNAL_ROWS and 0 <= counts["journalBytes"] <= JOURNAL_LIMIT and counts["journalByteCapacity"] == JOURNAL_LIMIT and counts["presentSuccess"] == 3):
        raise ValueError("native count bound exceeded")
    pfns = top["pfns"]
    if not isinstance(pfns, list) or len(pfns) > 64:
        raise ValueError("PFN witness exceeds bound")
    for item in pfns:
        pfn = _exact(item, {"name", "ownerExact", "path"}, "PFN")
        if not _string(pfn["name"], "PFN.name") or pfn["ownerExact"] is not True or pfn["path"] != top["icdOwner"]:
            raise ValueError("PFN is not owned by the ICD")
    if not isinstance(top["calls"], list) or len(top["calls"]) > 96 or any(set(call) != {"callStage", "result"} for call in top["calls"]):
        raise ValueError("call journal witness is malformed")
    if len(top["calls"]) != counts["calls"]:
        raise ValueError("call count does not match the complete call witness")
    for call in top["calls"]:
        _string(call["callStage"], "call stage", 256)
        _int(call["result"], "call result")
    cleanup = _exact(top["cleanup"], {"queueWaitIdleCalled", "queueRetired", "queueWaitIdleResult", "complete", "unknownResourceRetained"}, "cleanup")
    if not (cleanup["queueWaitIdleCalled"] is True and cleanup["queueRetired"] is True and cleanup["queueWaitIdleResult"] == 0 and cleanup["complete"] is True and cleanup["unknownResourceRetained"] is False):
        raise ValueError("cleanup witness is incomplete")
    journal = _exact(top["journal"], {"rows", "rowCapacity", "bytes", "byteCapacity", "ioFailed"}, "journal")
    if not (0 <= journal["rows"] <= JOURNAL_ROWS and journal["rowCapacity"] == JOURNAL_ROWS and 0 <= journal["bytes"] <= JOURNAL_LIMIT and journal["byteCapacity"] == JOURNAL_LIMIT and journal["ioFailed"] is False):
        raise ValueError("journal witness is incomplete")
    if counts["journalRows"] != journal["rows"] or counts["journalBytes"] != journal["bytes"]:
        raise ValueError("journal count witnesses disagree")
    for name in ("androidPresentationObserved", "wine", "d3d", "wow"):
        if top[name] is not False:
            raise ValueError(f"native {name} overclaims")
    return top


validate_native = parse_native


def parse_result_wrapper(raw: bytes, run: str, state: dict[str, Any], proc: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    if len(raw) > NATIVE_LIMIT:
        raise ValueError("WSI result wrapper exceeds bound")
    wrapper = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_json_pairs)
    expected_keys = {"schema", "version", "runId", "generation", "pid", "uid", "status", "androidPresentationObserved", "native"}
    if not isinstance(wrapper, dict) or set(wrapper) != expected_keys or not isinstance(wrapper.get("runId"), str) or any(type(wrapper.get(key)) is not int for key in ("version", "generation", "pid", "uid")):
        raise ValueError("WSI result wrapper types or keyset mismatch")
    if not isinstance(wrapper, dict) or any(wrapper.get(key) != state[key] for key in ("runId", "generation", "pid", "uid")) or wrapper.get("schema") != SCHEMA or type(wrapper.get("version")) is not int or wrapper.get("version") != 1 or wrapper.get("status") != "RESULT" or wrapper.get("androidPresentationObserved") is not False or not isinstance(wrapper.get("native"), dict):
        raise ValueError("WSI result wrapper identity mismatch")
    return wrapper, parse_native(json.dumps(wrapper["native"]).encode(), run, state, proc=proc)


def validate_journal(raw: bytes, run: str, state: dict[str, Any], result: dict[str, Any]) -> dict[str, Any]:
    """Validate the bounded native journal without retaining personal logs."""
    if not raw or len(raw) > JOURNAL_LIMIT or not raw.endswith(b"\n") or raw.count(b"\n") > JOURNAL_ROWS:
        raise ValueError("native journal exceeds its bounded witness")
    rows = []
    for line in raw.splitlines():
        item = json.loads(line.decode("utf-8"), object_pairs_hook=unique_json_pairs)
        if not isinstance(item, dict) or set(item) != {"schema", "version", "runId", "pid", "generation", "event", "callStage", "result"}:
            raise ValueError("native journal row schema mismatch")
        if item["schema"] != SCHEMA or type(item["version"]) is not int or item["version"] != 1 or item["runId"] != run or item["pid"] != state["pid"] or item["generation"] != state["generation"]:
            raise ValueError("native journal process binding mismatch")
        if item["event"] not in {"start", "before", "after"} or not isinstance(item["callStage"], str) or len(item["callStage"].encode()) > 256:
            raise ValueError("native journal event is invalid")
        if item["result"] is not None and type(item["result"]) is not int:
            raise ValueError("native journal result is invalid")
        rows.append(item)
    if result["apiPresentSuccess"] and len(rows) < 2:
        raise ValueError("successful native result has no journal witness")
    if result["apiPresentSuccess"]:
        calls = result["calls"]
        if len(calls) != result["counts"]["calls"] or len(rows) != 1 + 2 * len(calls) or rows[0]["event"] != "start":
            raise ValueError("native journal/call count correspondence missing")
        for index, call in enumerate(calls):
            before, after = rows[1 + 2 * index:3 + 2 * index]
            if before["event"] != "before" or after["event"] != "after" or before["callStage"] != call["callStage"] or after["callStage"] != call["callStage"] or before["result"] is not None or after["result"] != call["result"]:
                raise ValueError("native journal before/after correspondence missing")
    if result["apiPresentSuccess"] and (len(rows) != result["journal"]["rows"] or len(raw) != result["journal"]["bytes"]):
        raise ValueError("native journal counts differ from result")
    return {"rows": len(rows), "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}


def _focus_and_process(state: dict[str, Any]) -> dict[str, Any]:
    focus = _foreground()
    if not focus["exact"]:
        raise RuntimeError("qualification activity is not the exact foreground component")
    proc = _proc_stat(state["pid"])
    if proc["processName"] != PROCESS_NAME:
        raise RuntimeError("foreground WSI process identity is not exact")
    if proc["startTicks"] != state["startTicks"]:
        raise RuntimeError("foreground WSI process start-time does not match state")
    return {"focus": focus, "process": proc}


def _force_stop() -> dict[str, Any]:
    result = adb("shell", "am", "force-stop", PACKAGE, timeout=5)
    if result.returncode or result.stdout or result.stderr:
        raise RuntimeError("owned package force-stop failed")
    return {"package": PACKAGE, "returnCode": result.returncode}


def _write(path: Path, value: object) -> None:
    data = (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")
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


def _write_bytes(path: Path, data: bytes) -> None:
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


def _fsync_directory(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _capture_screenshot(label: str) -> tuple[bytes, dict[str, Any]]:
    started = time.monotonic_ns()
    raw = _require(adb("exec-out", "screencap", "-p", timeout=10), f"{label} screenshot", MAX_PNG_BYTES + 1)
    elapsed_ms = (time.monotonic_ns() - started) / 1_000_000
    if elapsed_ms > 10_000:
        raise TimeoutError(f"{label} screenshot exceeded the 10 second bound")
    return raw, {"capturedAtNs": started, "captureElapsedMs": elapsed_ms}


def run_supervisor(parent: Path) -> tuple[int, dict[str, Any]]:
    host_id = uuid.uuid4().hex
    evidence = parent / f"panvk-wsi-{host_id}"
    evidence.mkdir(mode=0o700)
    launched = False
    summary: dict[str, Any] = {"schema": "wowforever.android-panvk-wsi-host", "version": 1, "hostRunId": host_id, "status": "OBSERVATION_INCOMPLETE", "acceptAndroidPresentation": False, "androidPresentationObserved": False, "wine": False, "d3d": False, "wow": False, "native": None, "screenshots": {}}
    try:
        summary["preflight"] = _package_preflight()
        before_processes = own_processes()
        if before_processes:
            raise RuntimeError("owned UID already has a process; refusing stale WSI/game binding")
        summary["keyguard"] = _keyguard()
        start = adb(*activity_command(), timeout=10)
        _require(start, "open WSI activity", 8192)
        launched = True
        deadline = time.monotonic() + DEADLINE_SECONDS
        state: dict[str, Any] | None = None
        while time.monotonic() < deadline:
            try:
                candidate = read_state()
                if candidate["status"] == "READY" and candidate["available"] and candidate["startTicks"]:
                    state = validate_state(candidate, qualification=True)
                    break
            except (ValueError, RuntimeError, json.JSONDecodeError):
                pass
            time.sleep(0.2)
        if state is None:
            raise TimeoutError("READY state did not appear before the 20 second preparation deadline")
        _write(evidence / "state.json", state)
        binding = _focus_and_process(state)
        before_raw, before_timing = _capture_screenshot("READY")
        before = analyze_png(before_raw, state["bounds"])
        before.update(before_timing)
        if before["blackRatio"] < 0.95:
            raise RuntimeError("READY screenshot ROI is not black")
        _write_bytes(evidence / "before.png", before_raw)
        _focus_and_process(state)
        start2 = adb(*activity_command(True), timeout=10)
        _require(start2, "start one WSI run", 8192)
        execution_deadline = time.monotonic() + DEADLINE_SECONDS
        result: dict[str, Any] | None = None
        while time.monotonic() < execution_deadline:
            try:
                current = read_state(state["runId"])
                if current["pid"] != state["pid"] or current["generation"] != state["generation"] or current.get("startTicks") != state.get("startTicks"):
                    raise RuntimeError("WSI run identity changed")
                if current["status"] in {"CANCELLED", "UNKNOWN", "ERROR"}:
                    _write(evidence / "terminal-state.json", current)
                    try:
                        partial_journal = _read_owned(f"{PRIVATE_PREFIX}/{state['runId']}/native.jsonl", JOURNAL_LIMIT)
                        _write_bytes(evidence / "partial-native.jsonl", partial_journal)
                    except FileNotFoundError:
                        pass
                    raise RuntimeError(f"WSI terminal {current['status']}: {str(current.get('error', ''))[:180]}; no presentation accepted")
                raw_result = _read_owned(f"{PRIVATE_PREFIX}/{state['runId']}/result.json", NATIVE_LIMIT)
                _write_bytes(evidence / "result-wrapper-raw.json", raw_result)
                wrapper, result = parse_result_wrapper(raw_result, state["runId"], state, _proc_stat(state["pid"]))
                _write(evidence / "result-wrapper.json", wrapper)
                break
            except FileNotFoundError:
                pass
            except (ValueError, RuntimeError, json.JSONDecodeError):
                # A malformed result is evidence of bad metadata, not a GPU capability verdict.
                raise
            time.sleep(0.2)
        if result is None:
            raise TimeoutError("native result did not appear before the 20 second execution deadline")
        journal_raw = _read_owned(f"{PRIVATE_PREFIX}/{state['runId']}/native.jsonl", JOURNAL_LIMIT)
        journal = validate_journal(journal_raw, state["runId"], state, result)
        _write(evidence / "native-result.json", result)
        summary["native"] = {"status": result["status"], "apiPresentSuccess": result["apiPresentSuccess"], "nativeExit": result["nativeExit"], "failureStage": result["failureStage"]}
        summary["journal"] = journal
        binding_after = _focus_and_process(state)
        if binding_after["process"]["startTicks"] != binding["process"]["startTicks"]:
            raise RuntimeError("WSI process changed before final capture")
        after_raw, after_timing = _capture_screenshot("post-run")
        after = analyze_png(after_raw, state["bounds"])
        after.update(after_timing)
        _write_bytes(evidence / "after.png", after_raw)
        final_state = read_state(state["runId"])
        if any(final_state[key] != state[key] for key in ("runId", "generation", "pid", "startTicks", "bounds", "surfaceWidth", "surfaceHeight")):
            raise RuntimeError("WSI run metadata changed before final evidence binding")
        focus_after_capture = _focus_and_process(state)
        summary["screenshots"] = {"before": before, "after": after, "focusBefore": binding["focus"], "focusAfter": focus_after_capture["focus"]}
        visual = after["redRatio"] >= 0.10 and after["greenRatio"] >= 0.10 and after["blackRatio"] < 0.95
        accepted = bool(result["apiPresentSuccess"] and visual and before["blackRatio"] >= 0.95 and binding["focus"]["exact"] and binding_after["focus"]["exact"] and focus_after_capture["focus"]["exact"])
        summary["acceptAndroidPresentation"] = accepted
        summary["androidPresentationObserved"] = accepted
        summary["status"] = "ACCEPTED" if accepted else "OBSERVATION_INCOMPLETE"
        return (0 if accepted else 1), summary
    except Exception as exc:
        summary["error"] = str(exc)[:1024]
        return 1, summary
    finally:
        if launched:
            try:
                summary["forceStop"] = _force_stop()
            except Exception as exc:
                summary["forceStopError"] = str(exc)[:512]
        _write(evidence / "summary.json", summary)
        _fsync_directory(evidence)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="existing evidence directory outside the repository")
    args = parser.parse_args(argv)
    try:
        require_serial()
    except RuntimeError as exc:
        parser.error(str(exc))
    try:
        return run_supervisor(_external_parent(args.output))[0]
    except Exception as exc:
        print(f"run_wsi_usb: {exc}", flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
