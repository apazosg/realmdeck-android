#!/usr/bin/env python3
"""One bounded, credential-free smoke of the installed functional launcher.

This runner is intentionally a new code-69 path.  It imports only the existing
ADB/AOA/UI primitives, never uses shell input, and never implements Login.  It
records selected public window state and allowlisted prepared-config values;
game screenshots and raw UI XML are deliberately not retained.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import sys
import time
import uuid
from types import ModuleType
from typing import Any, Sequence
import xml.etree.ElementTree as ET


ADB = "/home/adrian/android-sdk/platform-tools/adb"
SERIAL_ENV_VAR = "WOWFOREVER_ADB_SERIAL"
SERIAL = os.environ.get(SERIAL_ENV_VAR, "")
PACKAGE = "com.wowforever.arm64probe"
UID = 10330
ACTIVITY = "app.gamenative.ForeverFunctionalPocActivity"
COMPONENT = f"{PACKAGE}/{ACTIVITY}"
VERSION_CODE = 117
VERSION_NAME = "0.2.93-pinned-resume"

DEFAULT_SECONDS = 180
MAX_SECONDS = 180
MAX_TABS = 18
PREFLIGHT_SECONDS = 35
UI_SECONDS = 105
UI_COMMAND_SECONDS = 10.0
MAX_SCREENSHOT_BYTES = 8 * 1024 * 1024
MAX_CONFIG_BYTES = 256 * 1024
MAX_LOG_BYTES = 64 * 1024
GAME_ROOT = "/storage/emulated/0/Android/data/com.wowforever.arm64probe/CustomGames/WoW Forever"
ORIGINAL_CONFIG = f"{GAME_ROOT}/WTF/BetaSuspendedTest.wtf"
ORIGINAL_PE = f"{GAME_ROOT}/WowB-ARM64.exe"
DERIVED_CONFIGS = (
    f"{GAME_ROOT}/WTF/BetaFunctionalPoc-classic_forever.wtf",
)
PREPARE_LABEL = "Preparar WoW"
# These mirror the exact home strings: the prepared status set after
# ForeverFunctionalPoc.prepare succeeds and the explicit consent sentence.
READY_LABEL = "Entorno preparado. Revisa y pulsa Lanzar WoW."
CONSENT_LABEL = "Se abrirá WoW. El inicio de sesión será manual."
LAUNCH_LABEL = "Lanzar WoW"
KEYBOARD_LABEL = "Abrir teclado"
AUTH_BUTTON_LABELS = (
    "Login", "Log in", "Log In", "Sign in", "Sign In", "Iniciar sesión", "Acceder",
)
PREFS_PATH = "shared_prefs/functional_poc_server_profiles.xml"
EXPECTED_PROFILE_ID = "classic_forever"
RUNTIME_PROCESS_RE = re.compile(r"(?i)^(?:wowb(?:-arm64)?(?:\.exe)?|wine(?:64)?|wineserver|box64|fex(?:64)?)$")
AUTH_EDITABLE_RE = re.compile(r"(?i)(?:edittext|autocompletetextview)$")
ALLOWLISTED_KEYS = (
    "maxFPS", "maxFPSBk", "useMaxFPS", "useMaxFPSBk", "useTargetFPS",
    "graphicsQuality", "GamePadEnable", "InputDeviceInterfaceStyle",
)
EXPECTED_PRESET = {
    "maxFPS": "30", "maxFPSBk": "30", "useMaxFPS": "1", "useMaxFPSBk": "1",
    "useTargetFPS": "0", "graphicsQuality": "0", "GamePadEnable": "1",
    "InputDeviceInterfaceStyle": "1",
}


class SmokeError(RuntimeError):
    pass


def require_serial() -> str:
    if not SERIAL:
        raise SmokeError("WOWFOREVER_ADB_SERIAL is not set (explicit device selection required)")
    return SERIAL


def _helpers() -> ModuleType:
    name = "wowforever_functional_launcher_usb_helpers"
    loaded = sys.modules.get(name)
    if loaded is not None:
        return loaded
    path = (Path(__file__).parents[1] / "usb_forever_trial.py").resolve()
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise SmokeError("existing USB helper unavailable")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def _safe_output(parent: Path) -> Path:
    parent = parent.expanduser()
    if parent.is_symlink() or not parent.is_dir():
        raise SmokeError("--output must be an existing real directory")
    resolved = parent.resolve()
    repo = Path(__file__).resolve().parents[3]
    if resolved == repo or repo in resolved.parents:
        raise SmokeError("--output must be outside the repository")
    if any(item.is_symlink() for item in (resolved, *resolved.parents)):
        raise SmokeError("--output path contains a symlink")
    return resolved


def _new_run_dir(parent: Path) -> Path:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    for _ in range(20):
        candidate = parent / f"functional-launcher-ux-{stamp}-{uuid.uuid4().hex[:10]}"
        try:
            candidate.mkdir(mode=0o700)
            (candidate / "screenshots").mkdir(mode=0o700)
            return candidate
        except FileExistsError:
            continue
    raise SmokeError("could not allocate a private smoke directory")


def _write_private(path: Path, data: bytes) -> None:
    if path.exists() or path.is_symlink():
        raise SmokeError("evidence path already exists")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        fd = -1
    finally:
        if fd >= 0:
            os.close(fd)
    os.chmod(path, 0o600)


def _write_json(path: Path, value: object) -> None:
    _write_private(path, (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode())


@dataclass
class Journal:
    path: Path
    records: int = 0

    def add(self, event: str, **fields: object) -> None:
        record = {
            "timestamp": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
            "event": event,
            **fields,
        }
        encoded = (json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n").encode()
        if self.path.exists() and self.path.stat().st_size + len(encoded) > 128 * 1024:
            raise SmokeError("smoke journal exceeded bound")
        if not self.path.exists():
            _write_private(self.path, encoded)
        else:
            with self.path.open("ab") as stream:
                stream.write(encoded)
                stream.flush()
                os.fsync(stream.fileno())
        self.records += 1


def _text(result: Any) -> str:
    return bytes(getattr(result, "stdout", b"")).decode("utf-8", "replace").strip()


def _stage_deadline(overall_deadline: float, budget_seconds: float, stage: str) -> float:
    deadline = min(overall_deadline, time.monotonic() + budget_seconds)
    if time.monotonic() >= deadline:
        raise SmokeError(f"stage-cancelled:{stage}")
    return deadline


def _dump(
    adb: Any,
    helper: ModuleType,
    deadline: float,
    *,
    journal: Journal | None = None,
    expected_label: str | None = None,
) -> Any:
    if time.monotonic() >= deadline:
        raise SmokeError("deadline")
    try:
        return helper.dump_ui(adb, deadline=min(deadline, time.monotonic() + UI_COMMAND_SECONDS))
    except Exception as exc:
        reason = f"{type(exc).__name__}:{expected_label or 'unknown-control'}"
        if journal is not None:
            journal.add("ui-dump-failed", reason=reason)
        raise SmokeError(f"ui-dump-failed:{reason}") from exc


def _own(state: Any) -> None:
    if not state.top_is_own() or not state.hierarchy_is_own():
        raise SmokeError("foreign-ui")
    parents = _parents(state)
    for node in state.root.iter("node"):
        class_name = node.attrib.get("class", "")
        if AUTH_EDITABLE_RE.search(class_name):
            raise SmokeError("authentication-editable-field")
        label = node.attrib.get("text", "") or node.attrib.get("content-desc", "")
        if label not in AUTH_BUTTON_LABELS:
            continue
        current = node
        for _ in range(5):
            current_class = current.attrib.get("class", "")
            if current.attrib.get("clickable") == "true" or current_class.endswith(("Button", "View")):
                raise SmokeError("explicit-login-button")
            current = parents.get(id(current))
            if current is None:
                break


def _parents(state: Any) -> dict[int, Any]:
    return {id(child): parent for parent in state.root.iter("node") for child in parent}


def _actionable(node: Any, suffix: str) -> bool:
    class_name = node.attrib.get("class", "")
    if class_name.endswith("ScrollView") or class_name.endswith("ComposeView"):
        return False
    if suffix == "CheckBox":
        return (
            class_name.endswith("CheckBox")
            or node.attrib.get("checkable") == "true"
        )
    return class_name.endswith(suffix) or node.attrib.get("clickable") == "true"


def _canonical_label_control(state: Any, label: str, suffix: str) -> list[Any]:
    parents = _parents(state)
    labels = [
        node for node in state.root.iter("node")
        if node.attrib.get("text", "") == label or node.attrib.get("content-desc", "") == label
    ]
    candidates: list[Any] = []
    for label_node in labels:
        current = label_node
        matched = False
        for _ in range(5):
            if _actionable(current, suffix):
                candidates.append(current)
                matched = True
                break
            current = parents.get(id(current))
            if current is None:
                break
            if current.attrib.get("class", "").endswith(("ScrollView", "ComposeView")):
                break
        if matched:
            continue
        parent = parents.get(id(label_node))
        if parent is None:
            continue
        siblings = list(parent)
        nearby = [sibling for sibling in siblings if _actionable(sibling, suffix)]
        if len(nearby) == 1:
            candidates.append(nearby[0])

    unique = list({id(item): item for item in candidates}.values())
    if len(unique) != 1:
        return unique
    control = unique[0]
    # Compose may expose a clickable ancestor plus an empty CheckBox child.
    # Canonicalize to the one actual checkable node when there is exactly one.
    if suffix == "CheckBox":
        checkable = [
            node for node in control.iter("node")
            if node.attrib.get("class", "").endswith("CheckBox") or node.attrib.get("checkable") == "true"
        ]
        if len(checkable) == 1:
            return [checkable[0]]
    return [control]


def _labelled_controls(state: Any, labels: tuple[str, ...], suffix: str) -> list[Any]:
    """Return canonical actionable controls for exact labels; callers reject duplicates."""
    found = []
    for label in labels:
        found.extend(_canonical_label_control(state, label, suffix))
    return list({id(item): item for item in found}.values())


def _focused(state: Any) -> list[Any]:
    return [node for node in state.root.iter("node") if node.attrib.get("focused") == "true"]


def _focus_matches(control: Any, focused: Any, parents: dict[int, Any]) -> bool:
    current = focused
    for _ in range(5):
        if current is control:
            return True
        current = parents.get(id(current))
        if current is None:
            break
    current = parents.get(id(control))
    for _ in range(4):
        if current is None or current.attrib.get("class", "").endswith(("ScrollView", "ComposeView", "FrameLayout", "LinearLayout")):
            break
        if current is focused:
            return current.attrib.get("clickable") == "true"
        current = parents.get(id(current))
    return False


def _checked(control: Any) -> str:
    values = [node.attrib.get("checked") for node in control.iter("node") if "checked" in node.attrib]
    return values[-1] if values else control.attrib.get("checked", "")


def _visible_control(state: Any, control: Any, *, require_enabled: bool = True) -> bool:
    if (require_enabled and control.attrib.get("enabled") != "true") or control.attrib.get("visible-to-user") == "false":
        return False
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", control.attrib.get("bounds", ""))
    if match is None:
        return False
    left, top, right, bottom = map(int, match.groups())
    if right <= left or bottom <= top:
        return False
    # Viewport is provided by the owned hierarchy rather than fixed resolution.
    root = next(iter(state.root.iter("node")), None)
    viewport = None if root is None else re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", root.attrib.get("bounds", ""))
    if viewport is None:
        return False
    vl, vt, vr, vb = map(int, viewport.groups())
    return vl <= left < right <= vr and vt <= top < bottom <= vb


def _send(keyboard: Any, keycode: int, deadline: float) -> None:
    if keycode not in (43, 44, 40, 41):
        raise SmokeError("unallowlisted-HID-key")
    if time.monotonic() >= deadline:
        raise SmokeError("deadline")
    try:
        keyboard.key(0, keycode)
        time.sleep(min(0.15, max(0.0, deadline - time.monotonic())))
    except Exception as exc:
        raise SmokeError("AOA-keyboard-failed") from exc


def _focus_label(
    adb: Any,
    helper: ModuleType,
    keyboard: Any,
    label: str,
    suffix: str,
    deadline: float,
    *,
    allow_hidden: bool = True,
    journal: Journal | None = None,
) -> Any:
    state = _dump(adb, helper, deadline, journal=journal, expected_label=label)
    for position in range(MAX_TABS + 1):
        _own(state)
        controls = _labelled_controls(state, (label,), suffix)
        if len(controls) > 1:
            raise SmokeError(f"non-unique-control:{label}")
        if not controls and not allow_hidden:
            raise SmokeError(f"control-not-visible:{label}")
        focused = _focused(state)
        if len(focused) > 1:
            raise SmokeError("focus-not-unique")
        if len(controls) == 1 and len(focused) == 1 and _focus_matches(controls[0], focused[0], _parents(state)):
            if not _visible_control(state, controls[0]):
                raise SmokeError(f"control-not-visible-enabled:{label}")
            return state
        if position == MAX_TABS:
            raise SmokeError(f"control-not-focused-within-eighteen-tabs:{label}")
        _send(keyboard, 43, deadline)
        state = _dump(adb, helper, deadline, journal=journal, expected_label=label)
    raise SmokeError("focus-loop")


def _has_label(state: Any, label: str) -> bool:
    return any(node.attrib.get("text", "") == label for node in state.root.iter("node"))


def _consent(state: Any) -> list[Any]:
    return _labelled_controls(state, (CONSENT_LABEL,), "CheckBox")


def _prepared(state: Any) -> bool:
    consent = _consent(state)
    buttons = _labelled_controls(state, (LAUNCH_LABEL,), "Button")
    return (_has_label(state, READY_LABEL) and len(consent) == 1 and len(buttons) == 1
            and _checked(consent[0]) == "false" and _visible_control(state, consent[0])
            and _visible_control(state, buttons[0], require_enabled=False))


def _screenshot(adb: Any, helper: ModuleType, run_dir: Path, name: str, deadline: float, journal: Journal) -> None:
    state = _dump(adb, helper, deadline)
    _own(state)
    result = adb.exec_out("screencap", "-p", timeout=min(10, max(0.1, deadline - time.monotonic())))
    raw = bytes(getattr(result, "stdout", b""))
    if result.returncode != 0 or len(raw) > MAX_SCREENSHOT_BYTES or not raw.startswith(b"\x89PNG\r\n\x1a\n"):
        raise SmokeError("owned-screenshot-invalid")
    _own(_dump(adb, helper, deadline))
    destination = run_dir / "screenshots" / f"{name}.png"
    _write_private(destination, raw)
    journal.add("owned-screenshot", name=name, bytes=len(raw))


def _shell_text(adb: Any, args: tuple[str, ...], deadline: float, limit: int = 512 * 1024) -> str | None:
    if time.monotonic() >= deadline:
        return None
    result = adb.shell(*args, timeout=min(15, max(0.1, deadline - time.monotonic())))
    raw = bytes(getattr(result, "stdout", b""))
    if result.returncode != 0 or getattr(result, "stderr", b"") or len(raw) > limit:
        return None
    return raw.decode("utf-8", "replace").strip()


def _exec_out_command(adb: Any, command: str, deadline: float, limit: int) -> str | None:
    """Run one fixed, fully quoted remote command through exec-out sh -c."""
    if time.monotonic() >= deadline:
        return None
    result = adb.exec_out(
        "sh", "-c", command,
        timeout=min(15, max(0.1, deadline - time.monotonic())),
    )
    raw = bytes(getattr(result, "stdout", b""))
    if result.returncode != 0 or getattr(result, "stderr", b"") or len(raw) > limit:
        return None
    return raw.decode("utf-8", "replace").strip()


def _fixed_hash(adb: Any, path: str, deadline: float) -> str | None:
    output = _exec_out_command(adb, f"sha256sum {shlex.quote(path)}", deadline, 512)
    if output is None:
        return None
    match = re.fullmatch(r"\s*([0-9a-fA-F]{64})\s+.*", output)
    return None if match is None else match.group(1).lower()


def _config_values(adb: Any, deadline: float) -> dict[str, Any]:
    key_re = re.compile(r'^\s*SET\s+([A-Za-z][A-Za-z0-9_]*)\s+"([^"]*)"\s*$', re.I)
    for path in DERIVED_CONFIGS:
        output = _exec_out_command(adb, f"head -c {MAX_CONFIG_BYTES} {shlex.quote(path)}", deadline, MAX_CONFIG_BYTES)
        if output is None:
            continue
        values: dict[str, list[str]] = {}
        for line in output.splitlines():
            match = key_re.fullmatch(line)
            if match is None:
                continue
            key = next((item for item in ALLOWLISTED_KEYS if item.casefold() == match.group(1).casefold()), None)
            if key is not None:
                values.setdefault(key, []).append(match.group(2))
        if all(key in values for key in EXPECTED_PRESET):
            return {
                "file": path.rsplit("/", 1)[-1],
                "values": {key: items[-1] for key, items in values.items()},
                "counts": {key: len(items) for key, items in values.items()},
                "presetMatches": all(values[key] == [EXPECTED_PRESET[key]] for key in EXPECTED_PRESET),
            }
    return {"file": None, "values": {}, "counts": {}, "presetMatches": False}


def _installed_identity(adb: Any, deadline: float) -> dict[str, Any]:
    output = _shell_text(adb, ("dumpsys", "package", PACKAGE), deadline, 512 * 1024)
    if output is None:
        raise SmokeError("package-identity-unavailable")
    code = re.search(r"\bversionCode=(\d+)", output)
    name = re.search(r"\bversionName=([^\s]+)", output)
    uid = re.search(r"\b(?:appId|userId)=(\d+)", output)
    identity = {
        "package": PACKAGE,
        "versionCode": None if code is None else int(code.group(1)),
        "versionName": None if name is None else name.group(1),
        "uid": None if uid is None else int(uid.group(1)),
    }
    if (identity["versionCode"], identity["versionName"], identity["uid"]) != (VERSION_CODE, VERSION_NAME, UID):
        raise SmokeError("package-identity-mismatch")
    return identity


def _shared_preferences(adb: Any, deadline: float) -> dict[str, Any]:
    if time.monotonic() >= deadline:
        raise SmokeError("stage-cancelled:preflight")
    result = adb.exec_out("run-as", PACKAGE, "cat", PREFS_PATH, timeout=min(10, max(0.1, deadline - time.monotonic())))
    raw = bytes(getattr(result, "stdout", b""))
    if result.returncode != 0 or getattr(result, "stderr", b"") or len(raw) > 16 * 1024:
        raise SmokeError("shared-preferences-unavailable")
    try:
        root = ET.fromstring(raw)
    except ET.ParseError as exc:
        raise SmokeError("shared-preferences-malformed") from exc
    allowed = {"profile_id", "memory_patch_enabled", "code_capture_enabled"}
    values: dict[str, str] = {}
    for item in root:
        key = item.attrib.get("name")
        if key not in allowed or key in values:
            continue
        values[key] = item.attrib.get("value", "") if item.tag == "boolean" else (item.text or "")
    expected = {
        "profile_id": EXPECTED_PROFILE_ID,
        "memory_patch_enabled": "false",
        "code_capture_enabled": "false",
    }
    if values != expected:
        raise SmokeError("shared-preferences-not-safe")
    return {"profileId": values["profile_id"], "memoryPatchEnabled": False, "codeCaptureEnabled": False}


def _own_process_guard(adb: Any, deadline: float) -> dict[str, Any]:
    output = _shell_text(adb, ("ps", "-A", "-o", "UID,PID,PPID,NAME"), deadline, 64 * 1024)
    if output is None:
        raise SmokeError("own-process-guard-unavailable")
    own: list[dict[str, int | str]] = []
    runtime: list[str] = []
    for line in output.splitlines()[1:]:
        fields = line.split()
        if len(fields) < 4:
            continue
        try:
            row_uid, pid, ppid = (int(fields[0]), int(fields[1]), int(fields[2]))
        except ValueError:
            continue
        if row_uid != UID:
            continue
        name = fields[3]
        own.append({"pid": pid, "ppid": ppid, "name": name})
        if RUNTIME_PROCESS_RE.fullmatch(name) or RUNTIME_PROCESS_RE.search(name):
            runtime.append(name)
    return {"ownUid": bool(own), "processCount": len(own), "pids": {int(item["pid"]) for item in own}, "runtime": sorted(set(runtime))}


def _prepared_event(adb: Any, deadline: float, prior_paths: set[str], attempt_start_ms: int) -> dict[str, Any]:
    """Bind preparation to a unique newly created log, not a host wall-clock cutoff.

    The filename and timestampMs use the Android clock. attempt_start_ms is a
    host journal timestamp and must not be compared across those clock domains.
    """
    listing = _shell_text(adb, ("run-as", PACKAGE, "find", "files/functional-poc", "-maxdepth", "1", "-type", "f", "-name", "run-*.jsonl", "-print"), deadline, 16 * 1024)
    if listing is None:
        return {"found": False, "memoryPatchEnabled": None, "codeCaptureEnabled": None}
    paths = [line for line in listing.splitlines() if re.fullmatch(r"files/functional-poc/run-[0-9]{1,32}\.jsonl", line)]
    paths = [path for path in paths if path not in prior_paths]
    if not paths:
        return {"found": False, "memoryPatchEnabled": None, "codeCaptureEnabled": None}
    if len(paths) != 1:
        return {"found": False, "memoryPatchEnabled": None, "codeCaptureEnabled": None, "reason": "new-run-not-unique"}
    newest = paths[0]
    run_id = int(re.search(r"(\d+)\.jsonl$", newest).group(1))
    output = _shell_text(adb, ("run-as", PACKAGE, "head", "-c", str(MAX_LOG_BYTES), newest), deadline, MAX_LOG_BYTES)
    if output is None:
        return {"found": False, "memoryPatchEnabled": None, "codeCaptureEnabled": None}
    for line in reversed(output.splitlines()):
        try:
            item = json.loads(line)
        except (TypeError, ValueError, json.JSONDecodeError):
            continue
        if isinstance(item, dict) and item.get("event") == "prepared" and isinstance(item.get("timestampMs"), int) and item["timestampMs"] >= run_id:
            return {
                "found": True,
                "logId": run_id,
                "timestampMs": item["timestampMs"],
                "profileId": item.get("profileId") if isinstance(item.get("profileId"), str) else None,
                "memoryPatchEnabled": item.get("memoryPatchEnabled") if isinstance(item.get("memoryPatchEnabled"), bool) else None,
                "codeCaptureEnabled": item.get("codeCaptureEnabled") if isinstance(item.get("codeCaptureEnabled"), bool) else None,
            }
    return {"found": False, "memoryPatchEnabled": None, "codeCaptureEnabled": None}


def _run_log_paths(adb: Any, deadline: float) -> set[str]:
    listing = _shell_text(
        adb,
        ("run-as", PACKAGE, "find", "files/functional-poc", "-maxdepth", "1", "-type", "f", "-name", "run-*.jsonl", "-print"),
        deadline,
        16 * 1024,
    )
    if listing is None:
        raise SmokeError("prepared-log-list-unavailable")
    return {
        line for line in listing.splitlines()
        if re.fullmatch(r"files/functional-poc/run-[0-9]{1,32}\.jsonl", line)
    }


def _component(raw: str) -> str | None:
    match = re.search(rf"({re.escape(PACKAGE)}/[A-Za-z0-9_.$]+)", raw)
    return match.group(1) if match else None


def _window_observation(adb: Any, deadline: float) -> dict[str, Any]:
    windows = _shell_text(adb, ("dumpsys", "window", "windows"), deadline, 512 * 1024) or ""
    displays = _shell_text(adb, ("dumpsys", "window", "displays"), deadline, 256 * 1024) or ""
    activities = _shell_text(adb, ("dumpsys", "activity", "activities"), deadline, 256 * 1024) or ""
    focus = None
    focused_app = None
    current_match = re.search(r"mCurrentFocus=Window\{[^}]*\s([^\s}]+)", windows)
    focused_match = re.search(r"mFocusedApp=ActivityRecord\{[^}]*\s([^\s}]+)", windows)
    if current_match:
        focus = current_match.group(1) if PACKAGE in current_match.group(1) else "foreign"
    if focused_match:
        focused_app = focused_match.group(1) if PACKAGE in focused_match.group(1) else "foreign"
    rotation_match = re.search(r"(?:mCurrentRotation|mRotation)=ROTATION_(\d+)", displays + windows)
    if rotation_match is None:
        rotation_match = re.search(r"(?:mCurrentRotation|mRotation)=(\d+)", displays + windows)
    frame = None
    lines = windows.splitlines()
    for index, line in enumerate(lines):
        if PACKAGE not in line:
            continue
        for following in lines[index:index + 12]:
            frame_match = re.search(r"mFrame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", following)
            if frame_match:
                frame = [int(value) for value in frame_match.groups()]
                break
        if frame is not None:
            break
    resumed = None
    for line in activities.splitlines():
        if "mResumedActivity:" in line or "ResumedActivity:" in line:
            resumed = _component(line)
            if resumed is not None:
                break
    return {
        "rotation": None if rotation_match is None else int(rotation_match.group(1)),
        "currentFocus": focus,
        "focusedApp": focused_app,
        "currentActivity": resumed,
        "windowFrame": frame,
        "wowWindowSeen": bool(re.search(r"(?i)wowb|wine|box64|fex", windows)),
    }


def _ime_visible(adb: Any, deadline: float) -> bool | None:
    output = _shell_text(adb, ("dumpsys", "input_method"), deadline, 256 * 1024)
    if output is None:
        return None
    matches = re.findall(r"(?i)(?:mInputShown|mIsInputViewShown|inputShown)\s*[=:]\s*(true|false)", output)
    if not matches:
        return None
    return any(value.casefold() == "true" for value in matches[-4:])


def _physical_gamepad_observed(adb: Any, deadline: float) -> bool | None:
    output = _shell_text(adb, ("dumpsys", "input"), deadline, 512 * 1024)
    if output is None:
        return None
    return bool(re.search(r"(?i)gamepad|joystick|xbox|dualsense|8bitdo|controller", output))


def _wake(adb: Any, helper: ModuleType, keyboard: Any, journal: Journal, deadline: float) -> None:
    if not helper.policy_is_nonsecure(adb, deadline=min(deadline, time.monotonic() + 5)):
        raise SmokeError("nonsecure-policy-required")
    keyboard.key(2, 0)
    time.sleep(0.5)
    if not helper.policy_is_nonsecure(adb, deadline=min(deadline, time.monotonic() + 5)):
        raise SmokeError("keyguard-policy-changed")
    dismissed = adb.shell("wm", "dismiss-keyguard", timeout=min(5, max(0.1, deadline - time.monotonic())))
    if dismissed.returncode != 0 or getattr(dismissed, "stderr", b""):
        raise SmokeError("keyguard-dismiss-failed")
    journal.add("device-awake", policy="nonsecure", shellDismiss=True)


def run_smoke(output: Path, seconds: int = DEFAULT_SECONDS) -> tuple[Path, dict[str, Any]]:
    require_serial()
    if seconds < 1 or seconds > MAX_SECONDS:
        raise SmokeError(f"--seconds must be between 1 and {MAX_SECONDS}")
    helper = _helpers()
    run_dir = _new_run_dir(_safe_output(output))
    journal = Journal(run_dir / "actions.jsonl")
    started = time.monotonic()
    deadline = started + seconds
    cleanup_deadline = deadline + 20
    adb = helper.Adb(ADB, SERIAL)
    adb.deadline = cleanup_deadline
    result: dict[str, Any] = {
        "schema": 1,
        "kind": "wowforever.functional-launcher-ux-smoke",
        "serial": SERIAL,
        "package": PACKAGE,
        "uid": UID,
        "activity": ACTIVITY,
        "versionCode": VERSION_CODE,
        "versionName": VERSION_NAME,
        "requestedSeconds": seconds,
        "status": "UNKNOWN",
        "launchRequested": False,
        "loginAttempted": False,
        "typingValidated": False,
        "memoryPatchEnabled": False,
        "codeCaptureEnabled": False,
        "orientation": [],
        "ime": {"attempted": False, "typingValidated": False, "closed": False},
        "preset": None,
        "hashes": {"originalPeBefore": None, "originalPeAfter": None, "originalConfigBefore": None, "originalConfigAfter": None},
        "physicalGamepad": "unverified",
        "screenshots": [],
        "error": None,
        "packageIdentity": None,
        "sharedPreferences": None,
        "preparedEvent": None,
    }
    keyboard: Any | None = None
    launchRequested = False
    sessionIdentityProven = False
    preflight_deadline = _stage_deadline(deadline, PREFLIGHT_SECONDS, "preflight")
    ui_deadline: float | None = None
    prior_run_paths: set[str] = set()

    def observe(label: str) -> dict[str, Any]:
        observation = _window_observation(adb, cleanup_deadline)
        result["orientation"].append({"label": label, **observation})
        journal.add("window-observation", label=label, **observation)
        return observation

    try:
        # This is a read-only preflight: do not touch the AOA keyboard while a
        # guest/runtime window is already active.
        initial = observe("before-touch")
        result["packageIdentity"] = _installed_identity(adb, preflight_deadline)
        result["sharedPreferences"] = _shared_preferences(adb, preflight_deadline)
        process_before = _own_process_guard(adb, preflight_deadline)
        result["preflightProcesses"] = {"ownUid": process_before["ownUid"], "processCount": process_before["processCount"], "runtime": process_before["runtime"]}
        if not process_before["ownUid"] or process_before["runtime"]:
            raise SmokeError("active-wow-process-before-touch")
        if initial["wowWindowSeen"]:
            raise SmokeError("active-wow-window-before-touch")
        result["hashes"]["originalPeBefore"] = _fixed_hash(adb, ORIGINAL_PE, preflight_deadline)
        result["hashes"]["originalConfigBefore"] = _fixed_hash(adb, ORIGINAL_CONFIG, preflight_deadline)
        if result["hashes"]["originalPeBefore"] is None or result["hashes"]["originalConfigBefore"] is None:
            raise SmokeError("original-integrity-preflight-unavailable")
        prior_run_paths = _run_log_paths(adb, preflight_deadline)
        if not helper.policy_is_nonsecure(adb, deadline=min(preflight_deadline, time.monotonic() + 5)):
            raise SmokeError("nonsecure-policy-required")
        with helper.UsbKeyboard(SERIAL) as opened:
            keyboard = opened
            _wake(adb, helper, keyboard, journal, preflight_deadline)
            started_activity = adb.shell("am", "start", "-W", "-n", COMPONENT, timeout=min(15, max(0.1, deadline - time.monotonic())))
            if started_activity.returncode != 0:
                raise SmokeError("activity-start-failed")
            journal.add("activity-started", component=COMPONENT)
            ui_deadline = _stage_deadline(deadline, UI_SECONDS, "launcher-ui")
            state = _dump(adb, helper, ui_deadline, journal=journal)
            _own(state)
            _screenshot(adb, helper, run_dir, "welcome", ui_deadline, journal)
            state = _focus_label(adb, helper, keyboard, PREPARE_LABEL, "Button", ui_deadline, journal=journal)
            attempt_start_ms = int(time.time() * 1000)
            _send(keyboard, 40, ui_deadline)
            journal.add("prepare-enter-sent")
            end = min(ui_deadline, time.monotonic() + UI_SECONDS)
            while time.monotonic() < end:
                state = _dump(adb, helper, ui_deadline, journal=journal)
                _own(state)
                if _prepared(state):
                    break
                if _has_label(state, READY_LABEL):
                    # Keyboard focus scrolls the prepared consent into view;
                    # Tab alone is safe and no activation is sent here.
                    state = _focus_label(adb, helper, keyboard, CONSENT_LABEL, "CheckBox", ui_deadline, journal=journal)
                    if _prepared(state):
                        break
                time.sleep(min(0.4, max(0.0, end - time.monotonic())))
            else:
                raise SmokeError("stage-cancelled:launcher-ui-prepared")
            result["hashes"]["originalPeAfter"] = _fixed_hash(adb, ORIGINAL_PE, ui_deadline)
            result["hashes"]["originalConfigAfter"] = _fixed_hash(adb, ORIGINAL_CONFIG, ui_deadline)
            if result["hashes"]["originalPeAfter"] != result["hashes"]["originalPeBefore"] or result["hashes"]["originalConfigAfter"] != result["hashes"]["originalConfigBefore"]:
                raise SmokeError("original-integrity-changed-before-launch")
            result["preset"] = _config_values(adb, ui_deadline)
            prepared_event = _prepared_event(adb, ui_deadline, prior_run_paths, attempt_start_ms)
            result["memoryPatchEnabled"] = prepared_event.get("memoryPatchEnabled") is True
            result["codeCaptureEnabled"] = prepared_event.get("codeCaptureEnabled") is True
            result["preparedEvent"] = prepared_event
            if result["preset"]["presetMatches"] is not True:
                raise SmokeError("prepared-preset-not-allowlisted")
            if prepared_event.get("found") is not True or result["memoryPatchEnabled"] or result["codeCaptureEnabled"]:
                raise SmokeError("prepared-diagnostics-not-off")
            _screenshot(adb, helper, run_dir, "prepared", ui_deadline, journal)
            state = _focus_label(adb, helper, keyboard, CONSENT_LABEL, "CheckBox", ui_deadline, journal=journal)
            consent = _consent(state)
            if len(consent) != 1 or _checked(consent[0]) != "false":
                raise SmokeError("consent-not-unchecked-focused")
            _send(keyboard, 44, ui_deadline)
            state = _dump(adb, helper, ui_deadline, journal=journal, expected_label=CONSENT_LABEL)
            _own(state)
            consent = _consent(state)
            if len(consent) != 1 or _checked(consent[0]) != "true":
                raise SmokeError("consent-did-not-become-checked")
            _focus_label(adb, helper, keyboard, LAUNCH_LABEL, "Button", ui_deadline, journal=journal)
            _send(keyboard, 40, ui_deadline)
            launch_started = time.monotonic()
            result["launchRequested"] = True
            launchRequested = True
            journal.add("launch-enter-sent", credentials="none")
            gamepad = _physical_gamepad_observed(adb, cleanup_deadline)
            result["physicalGamepad"] = "present-observed-no-input-sent" if gamepad else "not-observed"
            observation = observe("launch-0")
            session_deadline = min(deadline, time.monotonic() + 10)
            process_after_launch = _own_process_guard(adb, session_deadline)
            new_session_pids = process_after_launch["pids"] - process_before["pids"]
            while not new_session_pids and not process_after_launch["runtime"] and time.monotonic() < session_deadline:
                time.sleep(min(0.5, max(0.0, session_deadline - time.monotonic())))
                process_after_launch = _own_process_guard(adb, session_deadline)
                new_session_pids = process_after_launch["pids"] - process_before["pids"]
            sessionIdentityProven = (
                observation.get("currentActivity") == COMPONENT
                and process_after_launch["ownUid"]
                and bool(new_session_pids or process_after_launch["runtime"])
            )
            result["sessionIdentityProven"] = sessionIdentityProven
            result["sessionProcessCount"] = len(new_session_pids)
            result["sessionRuntime"] = process_after_launch["runtime"]
            if not sessionIdentityProven:
                raise SmokeError("launch-session-identity-unproven")
            ime_attempted = False
            ime_closed = False
            for delay in (2, 5, 10, 20, 40, 60, 90, 120, 180):
                if launch_started + delay >= deadline or time.monotonic() >= deadline:
                    break
                time.sleep(min(max(0.0, launch_started + delay - time.monotonic()), max(0.0, deadline - time.monotonic())))
                observation = observe(f"launch-{delay}")
                if not ime_attempted:
                    try:
                        state = _dump(adb, helper, deadline)
                        _own(state)
                        buttons = _labelled_controls(state, (KEYBOARD_LABEL,), "Button")
                        if len(buttons) == 1:
                            raw_bounds = buttons[0].attrib.get("bounds", "")
                            bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", raw_bounds)
                            if bounds and 20 <= int(bounds.group(3)) - int(bounds.group(1)) <= 240 and 20 <= int(bounds.group(4)) - int(bounds.group(2)) <= 240:
                                focused_state = _focus_label(
                                    adb, helper, keyboard, KEYBOARD_LABEL, "Button", deadline,
                                    allow_hidden=False,
                                    journal=journal,
                                )
                                target = _labelled_controls(focused_state, (KEYBOARD_LABEL,), "Button")
                                focused = _focused(focused_state)
                                if len(target) == 1 and len(focused) == 1 and _focus_matches(target[0], focused[0], _parents(focused_state)):
                                    _send(keyboard, 40, deadline)
                                    ime_attempted = True
                                    result["ime"]["attempted"] = True
                                    visible = _ime_visible(adb, cleanup_deadline)
                                    result["ime"]["visibleAfterRequest"] = visible
                                    journal.add("ime-requested", visible=visible, typingValidated=False)
                                    if visible is True:
                                        _send(keyboard, 41, cleanup_deadline)
                                        time.sleep(0.5)
                                        closed = _ime_visible(adb, cleanup_deadline)
                                        ime_closed = closed is False
                                        result["ime"]["closed"] = ime_closed
                                        journal.add("ime-closed", visibleAfterBack=closed)
                    except SmokeError:
                        # The keyboard control is optional while a guest window
                        # is taking over; absence is evidence, not permission to
                        # broaden focus or use shell taps.
                        journal.add("ime-not-validated", reason="unique-owned-small-button-not-confirmed")
                        ime_attempted = True
            result["ime"]["typingValidated"] = False
            result["ime"]["typingNote"] = "not attempted: no safe noncredential field was authorized"
            result["portraitObserved"] = initial.get("rotation") == 0
            result["landscapeObserved"] = any(item.get("rotation") in (1, 3, 90, 270) for item in result["orientation"] if item["label"].startswith("launch-"))
            if not result["landscapeObserved"]:
                raise SmokeError("landscape-transition-not-observed")
            result["hashes"]["originalPeAfter"] = _fixed_hash(adb, ORIGINAL_PE, cleanup_deadline)
            result["hashes"]["originalConfigAfter"] = _fixed_hash(adb, ORIGINAL_CONFIG, cleanup_deadline)
            if result["hashes"]["originalPeAfter"] != result["hashes"]["originalPeBefore"] or result["hashes"]["originalConfigAfter"] != result["hashes"]["originalConfigBefore"]:
                raise SmokeError("original-integrity-changed-after-smoke")
            result["status"] = "OBSERVED"
    except SmokeError as exc:
        result["status"] = "error"
        result["error"] = str(exc)[:160]
        journal.add("runner-error", reason=str(exc)[:160])
    finally:
        if launchRequested and sessionIdentityProven:
            try:
                observation = observe("before-cleanup")
                journal.add("cleanup-identity", currentActivity=observation.get("currentActivity"), sessionProven=True)
            except Exception:
                journal.add("cleanup", status="observation-error")
            # The AOA context is already closed here.  Never send a HID key
            # against an invalid handle; exact-package cleanup is the only
            # permitted fallback after the session identity proof above.
            stopped = adb.shell("am", "force-stop", PACKAGE, timeout=min(10, max(0.1, cleanup_deadline - time.monotonic())))
            journal.add("exact-package-cleanup", status="requested" if stopped.returncode == 0 else "error")
            result["cleanup"] = "requested" if stopped.returncode == 0 else "failed"
            if stopped.returncode != 0:
                result["status"] = "error"
                result["error"] = "exact-package-cleanup-failed"
            else:
                try:
                    remaining = _own_process_guard(adb, cleanup_deadline)
                except SmokeError:
                    remaining = None
                result["cleanup"] = "runtime-not-observed" if remaining is not None and not remaining["runtime"] else "unconfirmed"
                if remaining is None or remaining["runtime"]:
                    result["status"] = "error"
                    result["error"] = "owned-runtime-cleanup-unconfirmed"
        elif launchRequested:
            journal.add("cleanup", status="identity-unproven-no-force-stop")
        result["finishedAt"] = datetime.now(timezone.utc).isoformat(timespec="milliseconds")
        result["actionRecords"] = journal.records
        result["screenshots"] = [str(path) for path in sorted((run_dir / "screenshots").glob("*.png"))]
        _write_json(run_dir / "result.json", result)
    return run_dir, result


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="existing external artifact parent")
    parser.add_argument("--seconds", type=int, default=DEFAULT_SECONDS, help="active smoke bound (1..180)")
    args = parser.parse_args(argv)
    try:
        run_dir, result = run_smoke(args.output, args.seconds)
    except SmokeError as exc:
        parser.error(str(exc))
        return 2
    print(json.dumps({"result": str(run_dir / "result.json"), "status": result["status"]}, separators=(",", ":")))
    return 1 if result["status"] == "error" else 0


if __name__ == "__main__":
    raise SystemExit(main())
