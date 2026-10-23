"""Offline, host-only qualification of one Wine/PanVK evidence directory.

This module deliberately does not know how to talk to Android.  It consumes the
bounded files written by the existing trial supervisor and never treats a
native WSI result, a selection journal entry, or an Android system Vulkan
mapping as proof that Wine loaded PanVK.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
from pathlib import Path
from typing import Any

RUN_RE = re.compile(r"RUN-[A-Za-z0-9][A-Za-z0-9_.-]{0,99}\Z")
UID = 10330
DRIVER_SIZE = 31_142_504
DRIVER_SHA = "5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9"
DRIVER_NAME = "libvulkan_panfrost.so"
class ProbeIdentity:
    __slots__ = ("file", "size", "sha256")

    def __init__(self, file: str, size: int, sha256: str) -> None:
        self.file, self.size, self.sha256 = file, size, sha256

    def __eq__(self, other: object) -> bool:
        return isinstance(other, ProbeIdentity) and (self.file, self.size, self.sha256) == (other.file, other.size, other.sha256)


LEGACY_PROBE = ProbeIdentity(
    "GraphicsProbe-ARM64.exe", 89_600,
    "a19e6721647fae0f31052133c585c91194823883d6be9bb81c6d0d342abfee9f",
)
COMPILER_LOG_PROBE = ProbeIdentity(
    "GraphicsProbeLog-ARM64.exe", 90_112,
    "795acd6d878d6def7139566da6040b16bf2ee6a2a9edfb90d16d4af32a67ab9a",
)
READBACK_PROBE = ProbeIdentity(
    "GraphicsProbeReadback-ARM64.exe", 96_768,
    "80c1c260d7781bda73e3d5552c7ca730e72382288a9256b4a78c7899d3f0c3de",
)
APPROVED_PROBES = (LEGACY_PROBE, COMPILER_LOG_PROBE, READBACK_PROBE)
APPROVED_TRIAL_VERSIONS = {
    (39, "0.2.15-automated"): LEGACY_PROBE,
    (40, "0.2.16-automated"): LEGACY_PROBE,
    (41, "0.2.17-automated"): LEGACY_PROBE,
    (42, "0.2.18-automated"): LEGACY_PROBE,
    (43, "0.2.19-automated"): LEGACY_PROBE,
    (44, "0.2.20-automated"): LEGACY_PROBE,
    (45, "0.2.21-automated"): LEGACY_PROBE,
    (46, "0.2.22-automated"): LEGACY_PROBE,
    (47, "0.2.23-automated"): LEGACY_PROBE,
    (48, "0.2.24-automated"): LEGACY_PROBE,
    (49, "0.2.25-automated"): LEGACY_PROBE,
    (50, "0.2.26-automated"): LEGACY_PROBE,
    (51, "0.2.27-automated"): COMPILER_LOG_PROBE,
    (52, "0.2.28-automated"): COMPILER_LOG_PROBE,
    (53, "0.2.29-automated"): COMPILER_LOG_PROBE,
    (54, "0.2.30-automated"): COMPILER_LOG_PROBE,
    (55, "0.2.31-automated"): READBACK_PROBE,
}
# Historical callers used these names directly; retain the old public constants.
PROBE_NAME = LEGACY_PROBE.file
PROBE_SIZE = LEGACY_PROBE.size
PROBE_SHA = LEGACY_PROBE.sha256
DRIVER_ROOTS = (
    "/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-wine",
    "/data/data/com.wowforever.arm64probe/files/forever/controls/panvk-wine",
)
PE_ROOTS = (
    "/storage/emulated/0/Android/data/com.wowforever.arm64probe/CustomGames/WoW Forever",
    "/data/media/0/Android/data/com.wowforever.arm64probe/CustomGames/WoW Forever",
)
MAX_SNAPSHOTS, MAX_MAP, MAX_META, MAX_GRAPHICS = 48, 2 << 20, 16 << 10, 256 << 10
STAT = re.compile(r"^\S+\s+([rwxps-]{4})\s+\S+\s+\S+\s+\S+(?:\s+(.*))?$")


class _Problems:
    def __init__(self) -> None:
        self.level = 0  # incomplete, unknown, invalid
        self.items: list[str] = []

    def add(self, level: str, text: str) -> None:
        rank = {"INCOMPLETE": 1, "UNKNOWN": 2, "CONTROL_INVALID": 3}[level]
        self.level = max(self.level, rank)
        if text not in self.items:
            self.items.append(text)

    @property
    def status(self) -> str | None:
        return (None, "INCOMPLETE", "UNKNOWN", "CONTROL_INVALID")[self.level]


def _unique(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for key, value in pairs:
        if key in out:
            raise ValueError("duplicate JSON field")
        out[key] = value
    return out


def _json(raw: bytes | str) -> Any:
    if isinstance(raw, bytes):
        raw = raw.decode("utf-8")
    return json.loads(raw, object_pairs_hook=_unique)


def _regular(path: Path, limit: int) -> bytes:
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    with os.fdopen(fd, "rb") as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode):
            raise ValueError(f"not a regular non-symlink file: {path.name}")
        if before.st_size > limit:
            raise OverflowError(f"{path.name} exceeds {limit} bytes")
        raw = stream.read(limit + 1)
        after = os.fstat(stream.fileno())
        if len(raw) > limit or (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns) or len(raw) != before.st_size:
            raise ValueError(f"changed or short read: {path.name}")
        return raw


def _safe_child(root: Path, name: str) -> Path:
    if not name or Path(name).is_absolute() or Path(name).name != name:
        raise ValueError("source path escapes evidence directory")
    path = root / name
    if path.parent != root:
        raise ValueError("source path is not directly contained")
    return path


def _sha(path: Path) -> str | None:
    if path.stat(follow_symlinks=False).st_size > 16 << 20:
        return None
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def _expected_driver(run: str) -> tuple[str, ...]:
    return tuple(f"{root}/{run}/{DRIVER_NAME}" for root in DRIVER_ROOTS)


def _canonical_pe(path: str, probe_names: frozenset[str] | None = None) -> bool:
    if path.endswith(" (deleted)"):
        return False
    names = probe_names or frozenset(probe.file for probe in APPROVED_PROBES)
    return any(path == f"{root}/{name}" for root in PE_ROOTS for name in names)


def _parse_maps(raw: bytes, run: str, probe_names: frozenset[str] | None = None) -> tuple[bool, bool, bool]:
    pe = panvk = foreign = False
    all_names = frozenset(probe.file for probe in APPROVED_PROBES)
    names = probe_names or all_names
    for line in raw.decode("utf-8", errors="strict").splitlines():
        match = STAT.match(line.rstrip("\r"))
        if not match:
            continue
        perms, path = match.group(1), (match.group(2) or "").strip()
        rx = "r" in perms and "x" in perms
        known_probe = any(path == f"{root}/{name}" for root in PE_ROOTS for name in all_names)
        selected_probe = any(path == f"{root}/{name}" for root in PE_ROOTS for name in names)
        if path.endswith(" (deleted)") and (DRIVER_NAME in path or any(path.endswith(name + " (deleted)") for name in all_names)):
            foreign = True
        if known_probe and probe_names is not None and not selected_probe:
            foreign = True
        if selected_probe and rx:
            pe = True
        if DRIVER_NAME in path:
            if path in _expected_driver(run):
                panvk = panvk or rx
            else:
                foreign = True
    return pe, panvk, foreign


def _descriptor_probe_name(item: Any) -> str | None:
    if not isinstance(item, dict):
        return None
    values: list[tuple[str, str | None]] = []
    for key in ("file", "name", "path"):
        if key not in item:
            continue
        value = item[key]
        if not isinstance(value, str) or not value:
            raise ValueError(f"graphics probe identity field {key} is malformed")
        if key == "path":
            matches = [(root, f"{root}/{probe.file}") for root in PE_ROOTS for probe in APPROVED_PROBES
                       if value == f"{root}/{probe.file}"]
            if not matches:
                raise ValueError("graphics probe identity path is not a known PE path")
            values.append((Path(matches[0][1]).name, PE_ROOTS[0]))
        elif value in {probe.file for probe in APPROVED_PROBES}:
            values.append((value, None))
        else:
            matches = [(root, probe.file) for root in PE_ROOTS for probe in APPROVED_PROBES
                       if value == f"{root}/{probe.file}"]
            if not matches:
                raise ValueError(f"graphics probe identity field {key} is not a literal approved filename")
            values.append((matches[0][1], PE_ROOTS[0]))
    if not values or len({name for name, _root in values}) != 1:
        raise ValueError("graphics probe identity fields conflict")
    roots = {root for _name, root in values if root is not None}
    if len(roots) > 1:
        raise ValueError("graphics probe identity paths conflict")
    return values[0][0]


def _probe_descriptors(root: Path) -> list[Path]:
    return [root / name for name in ("probe-identity.json", "graphics-probe-identity.json")
            if (root / name).exists() or (root / name).is_symlink()]


def _read_json_file(root: Path, name: str, limit: int, problems: _Problems) -> Any | None:
    try:
        return _json(_regular(_safe_child(root, name), limit))
    except FileNotFoundError:
        problems.add("INCOMPLETE", f"missing {name}")
    except OverflowError as exc:
        problems.add("INCOMPLETE", str(exc))
    except (ValueError, UnicodeDecodeError, OSError) as exc:
        problems.add("INCOMPLETE", f"invalid {name}: {exc}")
    return None


def _driver(root: Path, run: str, problems: _Problems) -> dict[str, Any] | None:
    item = _read_json_file(root, "driver-identity.json", MAX_META, problems)
    if not isinstance(item, dict):
        return None
    required = {"schema", "runId", "path", "size", "sha256", "uid", "mode", "beforeAfterStable"}
    if not required <= item.keys() or type(item.get("schema")) is not int or item.get("schema") != 1 or item.get("runId") != run:
        problems.add("CONTROL_INVALID", "driver identity schema or RUN mismatch")
        return None
    mode = item.get("mode")
    mode_ok = mode == 0o600 or mode == "0600"
    if (item.get("path") not in _expected_driver(run) or type(item.get("size")) is not int or item.get("size") != DRIVER_SIZE or
            str(item.get("sha256", "")).lower() != DRIVER_SHA or type(item.get("uid")) is not int or item.get("uid") != UID or
            not mode_ok or item.get("beforeAfterStable") is not True):
        problems.add("CONTROL_INVALID", "driver identity does not match the pinned private ICD")
        return None
    if "pid" in item and (type(item["pid"]) is not int or item["pid"] <= 0):
        problems.add("CONTROL_INVALID", "driver identity PID is invalid")
        return None
    if ("owner" in item and item["owner"] != UID) or ("canonicalPath" in item and
            item["canonicalPath"] not in _expected_driver(run)):
        problems.add("CONTROL_INVALID", "driver identity owner or canonical path is wrong")
        return None
    return {"path": item["path"], "size": DRIVER_SIZE, "sha256": DRIVER_SHA, "uid": UID, "mode": "0600"}


def _probe_pin(root: Path, run: str, problems: _Problems, expected: ProbeIdentity | None) -> bool:
    """Check a descriptor/raw probe against one of the two approved identities."""
    descriptors = _probe_descriptors(root)
    if descriptors:
        try:
            observed_probes: list[ProbeIdentity] = []
            for descriptor in descriptors:
                item = _json(_regular(descriptor, MAX_META))
                size = item.get("size", item.get("bytes")) if isinstance(item, dict) else None
                name = _descriptor_probe_name(item)
                observed = next((probe for probe in APPROVED_PROBES if
                                 type(size) is int and name == probe.file and
                                 size == probe.size and item.get("sha256") == probe.sha256), None)
                if (not isinstance(item, dict) or item.get("schema") != 1 or item.get("runId", run) != run or
                        observed is None):
                    raise ValueError("graphics probe identity is not pinned")
                observed_probes.append(observed)
            if not observed_probes or any(probe != observed_probes[0] for probe in observed_probes[1:]) or (
                    expected is not None and observed_probes[0] != expected):
                raise ValueError("graphics probe identity is not pinned")
            return True
        except (ValueError, AttributeError, UnicodeDecodeError, json.JSONDecodeError, OSError, OverflowError) as exc:
            problems.add("CONTROL_INVALID", str(exc))
            return False
    raw_candidates = [root / probe.file for probe in APPROVED_PROBES]
    for raw_probe in raw_candidates:
        if not (raw_probe.exists() or raw_probe.is_symlink()):
            continue
        try:
            observed = next((probe for probe in APPROVED_PROBES if probe.file == raw_probe.name), None)
            if (observed is None or (expected is not None and observed != expected) or raw_probe.is_symlink() or
                    raw_probe.stat(follow_symlinks=False).st_size != observed.size or _sha(raw_probe) != observed.sha256):
                raise ValueError("raw graphics probe is not pinned")
            return True
        except (ValueError, OSError):
            problems.add("CONTROL_INVALID", "raw graphics probe is not pinned")
            return False
    return False


def _selected_probe(root: Path, run: str, problems: _Problems) -> ProbeIdentity | None:
    """Select the fixed identity once, preferring the typed preflight version."""
    preflight = root / "preflight.json"
    if preflight.exists() or preflight.is_symlink():
        try:
            item = _json(_regular(preflight, MAX_META))
            version = item.get("version") if isinstance(item, dict) else None
            code = version.get("code") if isinstance(version, dict) else None
            name = version.get("name") if isinstance(version, dict) else None
            selected = APPROVED_TRIAL_VERSIONS.get((code, name)) if type(code) is int and isinstance(name, str) else None
            if selected is None:
                raise ValueError("preflight version is not an approved graphics trial")
            return selected
        except (OverflowError, ValueError, TypeError, UnicodeDecodeError, json.JSONDecodeError, OSError) as exc:
            problems.add("CONTROL_INVALID", f"invalid preflight probe version: {exc}")
            return None

    descriptors = _probe_descriptors(root)
    if not descriptors:
        return None
    try:
        observed: list[ProbeIdentity] = []
        for descriptor in descriptors:
            item = _json(_regular(descriptor, MAX_META))
            identity_name = _descriptor_probe_name(item)
            probe = next((candidate for candidate in APPROVED_PROBES if isinstance(item, dict) and
                          item.get("schema") == 1 and item.get("runId", run) == run and
                          identity_name == candidate.file and type(item.get("size")) is int and
                          item.get("size") == candidate.size and item.get("sha256") == candidate.sha256), None)
            if probe is None:
                raise ValueError("graphics probe identity is not pinned")
            observed.append(probe)
        if not observed or any(probe != observed[0] for probe in observed[1:]):
            raise ValueError("graphics probe identity descriptors conflict")
        return observed[0]
    except (OverflowError, ValueError, TypeError, UnicodeDecodeError, json.JSONDecodeError, OSError):
        return None


def _is_uint(value: Any) -> bool:
    return type(value) is int and 0 <= value <= 0xFFFFFFFF


def _is_hresult(value: Any) -> bool:
    return isinstance(value, str) and re.fullmatch(r"0x[0-9A-Fa-f]{8}", value) is not None


def _valid_samples(samples: Any) -> bool:
    points = ((0, 0), (160, 120), (319, 239), (80, 60), (240, 180))
    return (isinstance(samples, list) and len(samples) == len(points) and all(
        isinstance(sample, dict) and type(sample.get("x")) is int and type(sample.get("y")) is int and
        (sample["x"], sample["y"]) == point and isinstance(sample.get("rgba"), list) and
        len(sample["rgba"]) == 4 and all(type(channel) is int and 0 <= channel <= 255 for channel in sample["rgba"])
        for sample, point in zip(samples, points)))


def _readback_contract(rows: list[dict[str, Any]], expected: ProbeIdentity | None, problems: _Problems) -> tuple[bool, bool]:
    """Return (variant_seen, complete_coherent_phases), never using last-wins maps."""
    variant = expected == READBACK_PROBE or rows[0].get("variant") == "readback-v1"
    if expected is not None and expected != READBACK_PROBE and variant:
        problems.add("CONTROL_INVALID", "readback variant conflicts with the selected legacy probe")
        return True, False
    if not variant:
        return False, False
    if rows[0].get("variant") != "readback-v1":
        problems.add("CONTROL_INVALID", "readback probe variant marker is absent")
        return True, False
    expected_phases = ("offscreen_clear", "offscreen_draw", "backbuffer_clear", "backbuffer_draw")
    phase_rows = [row for row in rows if row.get("phase") in expected_phases]
    windows = [row for row in rows if row.get("event") == "readback_window"]
    # A compiler/window/device failure may legitimately precede the sampling
    # hold. Preserve it as a measurement, never as a completed readback control.
    if windows or phase_rows or rows[-1].get("status") == "PASS":
        if ([row.get("status") for row in windows] != ["READY", "RESUMED"] or
                any(row.get("marker") != "modules-d3d-shaders-ready" for row in windows) or
                (phase_rows and rows.index(windows[-1]) >= rows.index(phase_rows[0]))):
            problems.add("CONTROL_INVALID", "readback window markers are absent, duplicated or out of order")
            return True, False
    names = [row["phase"] for row in phase_rows]
    if names != list(dict.fromkeys(names)) or names != list(expected_phases[:len(names)]):
        problems.add("CONTROL_INVALID", "readback phases are duplicated or out of order")
        return True, False
    if not phase_rows:
        return True, False
    if len(phase_rows) < len(expected_phases):
        if phase_rows[-1].get("status") != "TIMEOUT":
            problems.add("CONTROL_INVALID", "readback phase prefix lacks an explicit timeout")
        return True, False
    reasons = [row for row in rows if row.get("event") == "device_removed_reason"]
    if not reasons and rows[-1].get("status") != "PASS" and any(
            row.get("event") == "probe_budget" and row.get("status") == "TIMEOUT" for row in rows):
        return True, False
    if len(reasons) != 1 or rows.index(reasons[0]) <= rows.index(phase_rows[-1]) or not _is_hresult(reasons[0].get("result")):
        problems.add("CONTROL_INVALID", "post-readback DeviceRemovedReason witness is missing or malformed")
        return True, False
    presents = [row for row in rows if row.get("event") == "present"]
    if presents and rows.index(reasons[0]) >= rows.index(presents[0]):
        problems.add("CONTROL_INVALID", "DeviceRemovedReason witness follows the first Present")
        return True, False
    coherent = True
    for row, phase in zip(phase_rows, expected_phases):
        expected_event = "triangle_readback" if phase == "backbuffer_draw" else "readback"
        if row.get("event") != expected_event:
            problems.add("CONTROL_INVALID", f"readback event type is wrong for {phase}")
            coherent = False
        if row.get("status") == "VERIFIED":
            if (row.get("mapResult") not in {"S_OK", "0x00000000"} or row.get("pDataValid") is not True or
                    type(row.get("rowPitch")) is not int or row["rowPitch"] < 1280 or
                    type(row.get("width")) is not int or row["width"] != 320 or
                    type(row.get("height")) is not int or row["height"] != 240 or row.get("copyResult") != "VOID_SUBMITTED" or
                    any(row.get(key) != "0x00000000" for key in ("textureResult", "rtvResult", "stagingResult")) or
                    not all(_is_hresult(row.get(key)) for key in ("textureResult", "rtvResult", "stagingResult", "mapResult", "deviceRemovedReason"))):
                problems.add("CONTROL_INVALID", f"verified readback {phase} lacks coherent Map/copy/HRESULT evidence")
                coherent = False
            if (not all(_is_uint(row.get(key)) for key in ("clearPixels", "coloredPixels", "rgbaFnv1a")) or
                    row.get("clearPixels", 0) + row.get("coloredPixels", 0) > 320 * 240):
                problems.add("CONTROL_INVALID", f"verified readback {phase} pixel fields are malformed")
                coherent = False
            if not _valid_samples(row.get("samples")):
                problems.add("CONTROL_INVALID", f"verified readback {phase} samples are malformed")
                coherent = False
            if phase.endswith("clear") and (row.get("clearPixels") != 320 * 240 or row.get("coloredPixels") != 0):
                problems.add("CONTROL_INVALID", f"clear readback {phase} is not uniform")
                coherent = False
            if phase.endswith("draw") and (not _is_uint(row.get("coloredPixels")) or row["coloredPixels"] < 128):
                problems.add("CONTROL_INVALID", f"draw readback {phase} lacks 128 colored pixels")
                coherent = False
        elif row.get("status") not in {"FAILED", "TIMEOUT"}:
            problems.add("CONTROL_INVALID", f"readback {phase} status is untyped")
            coherent = False
    healthy = (reasons[0].get("result") == "0x00000000" and
               all(row.get("deviceRemovedReason") == "0x00000000" for row in phase_rows))
    return True, coherent and healthy and all(row.get("status") == "VERIFIED" for row in phase_rows)


def _graphics(root: Path, run: str, problems: _Problems, expected: ProbeIdentity | None = None) -> tuple[str | None, dict[str, Any]]:
    path = _safe_child(root, "graphics-probe.jsonl")
    try:
        raw = _regular(path, MAX_GRAPHICS)
        if not raw.endswith(b"\n") or not 2 <= raw.count(b"\n") <= 64:
            raise ValueError("graphics JSONL newline/row bound")
        rows = [_json(line) for line in raw.decode("utf-8", errors="strict").splitlines()]
        if any(not isinstance(row, dict) or row.get("schema") != 1 or row.get("runId") != run
               or not isinstance(row.get("event"), str) for row in rows):
            raise ValueError("graphics schema or RUN mismatch")
        if (rows[0].get("event") != "probe_begin" or rows[0].get("status") != "STARTED" or
                rows[-1].get("event") != "probe_end" or
                sum(row["event"] == "probe_begin" for row in rows) != 1 or
                sum(row["event"] == "probe_end" for row in rows) != 1):
            raise ValueError("graphics begin/end ordering invalid")
        terminal = rows[-1]
        status = terminal.get("status")
        if status == "FAIL_LOG_IO":
            status = "FAILED"
        if status not in {"DEVICE_REJECTED", "FAILED", "UNKNOWN", "PASS"}:
            raise ValueError("unknown graphics terminal status")
    except FileNotFoundError:
        problems.add("INCOMPLETE", "missing graphics-probe.jsonl")
        return None, {}
    except (OverflowError, ValueError, TypeError, UnicodeDecodeError, json.JSONDecodeError, OSError) as exc:
        problems.add("INCOMPLETE", f"invalid graphics-probe.jsonl: {exc}")
        return None, {}

    device = next((r for r in rows if r.get("event") == "device_created"), {})
    if status == "PASS" and any(row.get("event") == "probe_budget" and row.get("status") == "TIMEOUT" for row in rows):
        problems.add("CONTROL_INVALID", "graphics PASS contradicts a probe budget timeout")
    creates = [r for r in rows if r.get("event") == "d3d_attempt" and r.get("status") == "CREATE"]
    level = device.get("featureLevel") or (creates[-1].get("resultFeatureLevel") if creates else None)
    device_seen = bool(device) and level not in (None, "", "0", 0, "0x00000000")
    readback_variant, phases_coherent = _readback_contract(rows, expected, problems)
    phase_rows = [r for r in rows if r.get("phase") in {"offscreen_clear", "offscreen_draw", "backbuffer_clear", "backbuffer_draw"}]
    readback_phases = {r["phase"]: r for r in phase_rows}
    readback = readback_phases.get("backbuffer_draw", {})
    if not readback_variant:
        readback = next((row for row in rows if row.get("event") == "triangle_readback"), {})
    offscreen_draw = readback_phases.get("offscreen_draw", {})
    offscreen = (offscreen_draw.get("status") == "VERIFIED" and type(offscreen_draw.get("coloredPixels")) is int
                 and offscreen_draw["coloredPixels"] >= 128)
    backbuffer = (readback.get("status") == "VERIFIED" and type(readback.get("coloredPixels")) is int
                  and readback["coloredPixels"] >= 128)
    presents = [r for r in rows if r.get("event") == "present"]
    present = (len(presents) == 30 and [r.get("index") for r in presents] == list(range(1, 31)) and
               all(r.get("successful") is True and r.get("result") in {"0x00000000", "S_OK"} for r in presents))
    legacy_coherent = (not readback_variant and backbuffer and readback.get("result") in {"S_OK", "0x00000000"}
                       and readback.get("width") == 320 and readback.get("height") == 240 and _is_uint(readback.get("rgbaFnv1a")))
    info = {"terminal": terminal, "vulkanCaps": next((r for r in rows if r.get("event") == "vulkan_caps"), None),
            "modules": next((r for r in rows if r.get("event") == "modules"), None),
            "deviceLevel": level, "device": device_seen, "offscreen": offscreen, "backbuffer": backbuffer, "present": present,
            "readbackVariant": readback_variant, "readbackPhases": readback_phases,
             "apiQualificationObserved": bool(status == "PASS" and device_seen and expected is not None and present and
                                               ((phases_coherent and len(phase_rows) == 4) if readback_variant else legacy_coherent)),
            "visualPresentationObserved": False}
    summary_consistent = (terminal.get("d3dTriangleResult") is True and terminal.get("backbufferDraw") is True and
                          terminal.get("offscreenClear") is True and terminal.get("offscreenDraw") is True and
                          terminal.get("backbufferClear") is True and terminal.get("backbufferDraw") is True)
    if status == "PASS" and not (device_seen and backbuffer and present and terminal.get("d3dTriangleResult") is True
                                 and terminal.get("logDurable") is True and terminal.get("successfulPresents") == 30
                                  and ((phases_coherent and len(phase_rows) == 4 and summary_consistent)
                                       if readback_variant else legacy_coherent)):
        problems.add("CONTROL_INVALID", "graphics PASS lacks genuine D3D/readback/present witnesses")
    return status, info


def _snapshots(
    root: Path,
    run: str,
    final: dict[str, Any] | None,
    problems: _Problems,
    probe_names: frozenset[str] | None = None,
) -> tuple[bool, dict[str, Any]]:
    path = _safe_child(root, "process-snapshots.jsonl")
    try:
        raw = _regular(path, MAX_SNAPSHOTS * MAX_META + 1)
        if not raw.endswith(b"\n"):
            raise ValueError("process snapshot JSONL is truncated")
        lines = raw.splitlines()
        if not 1 <= len(lines) <= MAX_SNAPSHOTS or any(len(line) > MAX_META for line in lines):
            raise ValueError("process snapshot bounds exceeded")
        rows = [_json(line) for line in lines]
    except FileNotFoundError:
        problems.add("INCOMPLETE", "missing process-snapshots.jsonl")
        return False, {}
    except (OverflowError, ValueError, TypeError, UnicodeDecodeError, json.JSONDecodeError, OSError) as exc:
        problems.add("INCOMPLETE", f"invalid process snapshots: {exc}")
        return False, {}

    map_files: dict[tuple[int, int, int | None], Path] = {}
    try:
        for child in root.iterdir():
            match = re.fullmatch(r"maps-(\d+)-(\d+)(?:-(\d+))?\.txt", child.name)
            if match:
                if child.is_symlink():
                    raise ValueError("map source is a symlink")
                key = (int(match[1]), int(match[2]), int(match[3]) if match[3] else None)
                map_files[key] = child
    except OSError as exc:
        problems.add("INCOMPLETE", f"cannot enumerate maps: {exc}")
        return False, {}

    records: list[dict[str, Any]] = []
    seen_occ: dict[tuple[int, int], int] = {}
    seen_pid: dict[int, int] = {}
    proof: list[dict[str, Any]] = []
    for row in rows:
        if not isinstance(row, dict):
            problems.add("INCOMPLETE", "non-object process snapshot")
            continue
        try:
            pid, ppid, sample = row["pid"], row["ppid"], row["sample"]
            before, after = row["stat_before"], row["stat_after"]
            ub, ua = row["uid_before"], row["uid_after"]
            valid = (type(pid) is int and pid > 0 and type(ppid) is int and ppid >= 0 and type(sample) is int and
                     type(row.get("uid")) is int and row["uid"] == UID and row.get("uid_ps") == UID and
                     ub == [UID] * 4 and ua == [UID] * 4 and isinstance(before, dict) and isinstance(after, dict) and
                     before.get("pid") == pid and after.get("pid") == pid and before.get("start_ticks") == after.get("start_ticks") and
                      type(before.get("start_ticks")) is int and before["start_ticks"] >= 0)
            if "ppid" in before or "ppid" in after:
                valid = valid and type(before.get("ppid")) is int and before["ppid"] == ppid and after.get("ppid") == ppid
        except (KeyError, TypeError):
            valid = False
        if not valid:
            problems.add("CONTROL_INVALID", "process UID/stat identity is not stable")
            continue
        occ_key = (sample, pid)
        seen_occ[occ_key] = seen_occ.get(occ_key, 0) + 1
        seen_pid[pid] = seen_pid.get(pid, 0) + 1
        candidates = [map_files.get((sample, pid, seen_pid[pid])),
                      map_files.get((sample, pid, seen_occ[occ_key])), map_files.get((sample, pid, None))]
        map_path = next((candidate for candidate in candidates if candidate is not None), None)
        if map_path is None:
            matches = [path for (s, p, _), path in map_files.items() if s == sample and p == pid]
            if len(matches) == 1:
                map_path = matches[0]
        if map_path is None:
            problems.add("INCOMPLETE", f"missing map for snapshot {sample}/{pid}")
            continue
        try:
            map_raw = _regular(map_path, MAX_MAP)
            pe, panvk, foreign = _parse_maps(map_raw, run, probe_names)
        except (OverflowError, ValueError, UnicodeDecodeError, OSError) as exc:
            problems.add("INCOMPLETE", f"invalid map {map_path.name}: {exc}")
            continue
        record = {"pid": pid, "ppid": ppid, "start": before["start_ticks"], "sample": sample, "pe": pe, "panvk": panvk, "foreign": foreign}
        records.append(record)
        if pe and panvk:
            proof.append(record)
    if not proof:
        problems.add("CONTROL_INVALID", "no same-process PE plus pinned PanVK mapping proof")
        return False, {"records": records}
    keys = {(r["pid"], r["start"]) for r in proof}
    if len(keys) != 1:
        problems.add("UNKNOWN", "multiple possible Wine probe process identities")
        return False, {"records": records, "proofObserved": True}
    chosen = proof[0]
    if any(r["foreign"] for r in records if r["pid"] == chosen["pid"] and r["start"] == chosen["start"]):
        problems.add("CONTROL_INVALID", "Wine process contains a foreign or deleted PanVK mapping")
        return False, {"records": records, "proofObserved": True}
    if not isinstance(final, dict) or final.get("schema") != 1 or final.get("runId") != run:
        problems.add("INCOMPLETE", "missing or mismatched final current-run JSON")
        return False, {"records": records, "proofObserved": True}
    root_pid, root_start = final.get("rootPid"), final.get("rootStartTicks")
    if type(root_pid) is not int or type(root_start) is not int:
        problems.add("INCOMPLETE", "current root identity is absent")
        return False, {"records": records, "proofObserved": True}
    by_pid: dict[int, set[tuple[int, int]]] = {}
    for record in records:
        by_pid.setdefault(record["pid"], set()).add((record["start"], record["ppid"]))
    current = (chosen["pid"], chosen["start"])
    visited: set[tuple[int, int]] = set()
    for _ in range(128):
        if current in visited:
            problems.add("INCOMPLETE", "process ancestry loop")
            return False, {"records": records, "proofObserved": True}
        visited.add(current)
        pid, start = current
        options = {item for item in by_pid.get(pid, set()) if item[0] == start}
        if len(options) != 1:
            problems.add("UNKNOWN", "ambiguous process ancestry")
            return False, {"records": records, "proofObserved": True}
        if current == (root_pid, root_start):
            return True, {"pid": chosen["pid"], "startTicks": chosen["start"], "rootPid": root_pid, "rootStartTicks": root_start}
        parent = next(iter(options))[1]
        parents = {(s, p) for s, p in by_pid.get(parent, set())}
        if len(parents) != 1:
            problems.add("UNKNOWN" if parents else "INCOMPLETE", "ambiguous or incomplete process ancestry")
            return False, {"records": records, "proofObserved": True}
        current = (parent, next(iter(parents))[0])
    problems.add("INCOMPLETE", "process ancestry exceeded 128 links")
    return False, {"records": records}


def _journal_check(root: Path, run: str, problems: _Problems) -> None:
    path = root / "journal.jsonl"
    if not path.is_symlink() and not path.exists():
        return
    try:
        raw = _regular(path, 256 << 10)
        for line in raw.splitlines():
            item = _json(line)
            state = item.get("state") if isinstance(item, dict) else None
            payload = item.get("metadata", {}) if isinstance(item, dict) else {}
            if state != "panvk_wine_selection":
                continue
            if not isinstance(payload, dict):
                problems.add("CONTROL_INVALID", "PanVK selection journal metadata is malformed")
                continue
            if payload.get("runId", payload.get("run_id", run)) != run:
                problems.add("CONTROL_INVALID", "PanVK selection journal has the wrong RUN")
            selected = payload.get("path", payload.get("driverPath"))
            if selected is not None and selected not in _expected_driver(run):
                problems.add("CONTROL_INVALID", "PanVK selection journal path is foreign")
            journal_hash = payload.get("sha256", DRIVER_SHA)
            if not isinstance(journal_hash, str) or journal_hash.lower() != DRIVER_SHA:
                problems.add("CONTROL_INVALID", "PanVK selection journal hash is not pinned")
    except (OverflowError, ValueError, TypeError, UnicodeDecodeError, json.JSONDecodeError, OSError) as exc:
        problems.add("INCOMPLETE", f"invalid journal: {exc}")


def analyze_evidence(evidence: Path | str, run_id: str) -> dict[str, Any]:
    """Return a deterministic classification without writing to *evidence*."""
    problems = _Problems()
    root = Path(evidence)
    if not RUN_RE.fullmatch(run_id):
        raise ValueError("--run-id must be a strict RUN- token")
    if root.is_symlink() or not root.is_dir() or not root.is_absolute():
        raise ValueError("evidence must be an existing absolute non-symlink directory")
    marker = root / "trial-result.json"
    if marker.exists() or marker.is_symlink():
        item = _read_json_file(root, "trial-result.json", MAX_META, problems)
        if not isinstance(item, dict) or item.get("runId") not in (None, run_id) or item.get("status") == "UNKNOWN":
            problems.add("UNKNOWN", "host trial deadline/error/cleanup is unknown")
    driver = _driver(root, run_id, problems)
    expected_probe = _selected_probe(root, run_id, problems)
    probe_identity = _probe_pin(root, run_id, problems, expected_probe)
    if not probe_identity:
        problems.add("INCOMPLETE", "pinned probe file identity is absent")
    final = _read_json_file(root, "private-run-final.json", MAX_META, problems)
    _journal_check(root, run_id, problems)
    probe_names = frozenset((expected_probe.file,)) if expected_probe is not None else None
    mapping, process = _snapshots(root, run_id, final, problems, probe_names)
    reported, graphics = _graphics(root, run_id, problems, expected_probe)
    flags = {"driverMappingObserved": mapping, "D3DDeviceObserved": graphics.get("device", False),
             "offscreenReadbackObserved": graphics.get("offscreen", False), "apiPresentObserved": graphics.get("present", False),
             "backbufferReadbackObserved": graphics.get("backbuffer", False),
              "apiQualificationObserved": bool(not problems.status and driver is not None and probe_identity and mapping and graphics.get("apiQualificationObserved", False)),
             "androidNativeWSIAcceptanceNotReused": True, "wow": False}
    flags["visualPresentationObserved"] = False  # Screenshots require a separate foreground/ROI assessment.
    if driver is None and not any("missing driver" in p for p in problems.items):
        problems.add("CONTROL_INVALID", "private PanVK driver identity is not proven")
    if (not mapping and process.get("records") is not None and not process.get("proofObserved")
            and not problems.status):
        problems.add("CONTROL_INVALID", "Wine mapping proof is absent")
    if problems.status:
        status = problems.status
    elif reported == "PASS":
        status = "INCOMPLETE"  # API/readback is never an independent visual assessment.
    else:
        status = reported or "INCOMPLETE"
    expected_payload = ({"file": expected_probe.file, "size": expected_probe.size, "sha256": expected_probe.sha256}
                        if expected_probe is not None else None)
    result = {"schema": 1, "runId": run_id, "status": status, "reportedStatus": reported,
               "reason": problems.items[0] if problems.items else ("API witnesses qualified; independent visual assessment absent"
                         if reported == "PASS" and flags["apiQualificationObserved"] else "incomplete evidence"),
              "error": problems.items[0] if problems.items else None, "errors": problems.items,
              **flags, "flags": flags, "expectedProbe": expected_payload,
              "probeIdentityObserved": probe_identity, "driver": driver, "process": process,
              "graphics": graphics, "sourceFiles": []}
    for child in sorted(root.iterdir(), key=lambda p: p.name):
        if child.is_symlink() or not child.is_file():
            continue
        try:
            st = child.stat(follow_symlinks=False)
            result["sourceFiles"].append({"path": child.name, "size": st.st_size, "sha256": _sha(child)})
        except OSError:
            result["sourceFiles"].append({"path": child.name, "sha256": None})
    return result


# Small stable API for the parent evidence collector and for offline callers.
analyze = analyze_evidence


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    if args.output.exists() or args.output.is_symlink() or not args.output.is_absolute() or not args.output.parent.is_dir():
        parser.error("--output must be a new absolute JSON path with an existing parent")
    try:
        result = analyze_evidence(args.evidence, args.run_id)
        raw = (json.dumps(result, sort_keys=True, separators=(",", ":")) + "\n").encode()
        with args.output.open("xb") as stream:
            stream.write(raw)
            stream.flush()
            import os
            os.fchmod(stream.fileno(), 0o600)
            os.fsync(stream.fileno())
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
