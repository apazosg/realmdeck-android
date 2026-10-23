#!/usr/bin/env python3
"""Bounded, single-attempt functional WoW-on-Mali PoC observation.

The parent provisions APK 64 and the device.  This runner only starts the
dedicated PoC Activity, drives its reviewed controls over the already-open
AOA keyboard, and records bounded observation evidence.  It deliberately
does not install, reboot, unlock, tap, inspect arbitrary processes, or claim
that WoW started successfully merely because an Activity returned.  The final
game-log capture is limited to the package-owned, fixed custom-game directory.
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
import traceback
import uuid
from types import ModuleType
from typing import Any, Sequence


ADB = "/home/adrian/android-sdk/platform-tools/adb"
SERIAL_ENV_VAR = "WOWFOREVER_ADB_SERIAL"
SERIAL = os.environ.get(SERIAL_ENV_VAR, "")
PACKAGE = "com.wowforever.arm64probe"
UID = 10330
ACTIVITY = "app.gamenative.ForeverFunctionalPocActivity"
COMPONENT = f"{PACKAGE}/{ACTIVITY}"
VERSION_CODE = 64
VERSION_NAME = "0.2.40-functional-forever-poc"

DEFAULT_SECONDS = 120
MAX_SECONDS = 120
LOGIN_MAX_SECONDS = 180
CLEANUP_SECONDS = 20
PREPARE_SECONDS = 30
UI_COMMAND_SECONDS = 10.0
UI_CLEANUP_SECONDS = 2.0
LOGCAT_BYTES = 2 * 1024 * 1024
POC_LOG_BYTES = 256 * 1024
POC_DISCOVERY_BYTES = 16 * 1024
MAX_PNG_BYTES = 8 * 1024 * 1024
SCREENSHOT_TIMES = (5, 15, 30, 60)
GAME_LOG_ROOT = "/storage/emulated/0/Android/data/com.wowforever.arm64probe/CustomGames/WoW Forever"
GAME_LOG_FIXED_ROOT = f"{GAME_LOG_ROOT}/Logs"
GAME_LOG_ERROR_ROOT = f"{GAME_LOG_ROOT}/Errors"
GAME_LOG_FIXED_NAMES = (
    "gx.log",
    "Tact.log",
    "General.log",
    "WowB-ARM64_d3d11.log",
    "WowB-ARM64_dxgi.log",
    "WowDownload.log",
    "agent.log",
)
GAME_LOG_FIXED_BYTES = 128 * 1024
GAME_LOG_TOTAL_BYTES = 1024 * 1024
GAME_LOG_ERROR_BYTES = 256 * 1024
GAME_LOG_ERROR_MAX_FILES = 3
GAME_LOG_DISCOVERY_BYTES = 64 * 1024
GAME_LOG_CAPTURE_RESERVE_SECONDS = 2.0
GAME_LOG_ERROR_RE = re.compile(
    r"^\d{4}-\d{2}-\d{2}_\d{2}\.\d{2}\.\d{2}_(?:Error|Crash)_\d+\.txt$"
)
GAME_LOG_LIST_SCRIPT = (
    "root='/storage/emulated/0/Android/data/com.wowforever.arm64probe/CustomGames/WoW Forever'; "
    "for d in Logs Errors; do dir=\"$root/$d\"; [ -d \"$dir\" ] || continue; "
    "for p in \"$dir\"/*; do "
    "[ -f \"$p\" ] && [ ! -L \"$p\" ] || continue; "
    "n=${p##*/}; s=$(stat -c '%s' -- \"$p\" 2>/dev/null) || continue; "
    "m=$(stat -c '%Y' -- \"$p\" 2>/dev/null) || continue; "
    "printf '%s\\t%s\\t%s\\t%s\\n' \"$d\" \"$n\" \"$s\" \"$m\"; "
    "done; done"
)

PROFILE_LABELS = {
    "original": "Original",
    "classic_forever": "Classic Forever",
}
PROFILE_IDS = tuple(PROFILE_LABELS)
PATCH_LABEL = "Habilitar parche de memoria Forever (opcional)"
CODE_CAPTURE_LABEL = "Capturar ventanas de código ARM64 (diagnóstico, opt-in)"
PREPARE_LABELS = ("Preparar PoC", "PREPARAR POC")
CONSENT_LABELS = (
    "Entiendo: se lanzará el cliente WoW real sin credenciales",
    "ENTIENDO: SE LANZARÁ EL CLIENTE WOW REAL SIN CREDENCIALES",
)
READY_STATUS_BY_PROFILE = {
    profile: f"Entorno listo: pulsa Lanzar WoW ({profile})"
    for profile in PROFILE_IDS
}
LAUNCH_LABELS = ("Lanzar WoW", "LANZAR WOW")
MAX_TABS = 18

POC_LOG_RE = re.compile(r"^files/functional-poc/run-([0-9]{1,32})\.jsonl$")
SENSITIVE_TEXT_RE = re.compile(
    r"(?i)(\b(?:token|password|passwd|secret|credential|cookie|email|auth)\b\s*[:=]\s*)([^\s,;]+)"
)
AUTH_PORTAL = "auth.gpon.com.co"


class PocError(RuntimeError):
    """A bounded runner failure with a stable, non-device-specific reason."""


def require_serial() -> str:
    if not SERIAL:
        raise PocError("WOWFOREVER_ADB_SERIAL is not set (explicit device selection required)")
    return SERIAL


class PrepareOnlyComplete(Exception):
    """Internal control flow marker for the no-WoW prepare-only path."""


def _helpers() -> ModuleType:
    """Load only the existing USB/UI primitives under a private module name."""
    name = "wowforever_functional_poc_usb_helpers"
    loaded = sys.modules.get(name)
    if loaded is not None:
        return loaded
    path = (Path(__file__).parent.parent / "usb_forever_trial.py").resolve()
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise PocError("existing USB helper unavailable")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def _wake_helpers() -> ModuleType:
    name = "wowforever_functional_poc_wake_helpers"
    if name in sys.modules:
        return sys.modules[name]
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name("run_native_supervisor_apk.py"))
    if spec is None or spec.loader is None:
        raise PocError("wake helpers unavailable")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def _login_module() -> ModuleType:
    name = "wowforever_functional_login"
    loaded = sys.modules.get(name)
    if loaded is not None:
        return loaded
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name("functional_login.py"))
    if spec is None or spec.loader is None:
        raise PocError("login diagnostic unavailable")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def _text(result: Any) -> str:
    return bytes(getattr(result, "stdout", b"")).decode("utf-8", "replace").strip()


def _safe_output(path: Path) -> Path:
    path = path.expanduser()
    if path.is_symlink() or not path.is_dir():
        raise PocError("--output must be an existing real directory")
    resolved = path.resolve()
    repo = Path(__file__).resolve().parents[3]
    if resolved == repo or repo in resolved.parents:
        raise PocError("--output must be outside the repository")
    for parent in (resolved, *resolved.parents):
        if parent.is_symlink():
            raise PocError("--output path contains a symlink")
    return resolved


def _new_run_dir(parent: Path) -> Path:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    for _ in range(20):
        candidate = parent / f"functional-poc-{stamp}-{uuid.uuid4().hex[:10]}"
        try:
            candidate.mkdir(mode=0o700)
            os.chmod(candidate, 0o700)
            (candidate / "screenshots").mkdir(mode=0o700)
            (candidate / "poc-logs").mkdir(mode=0o700)
            (candidate / "game-logs").mkdir(mode=0o700)
            return candidate
        except FileExistsError:
            continue
    raise PocError("could not allocate a private PoC result directory")


def _write_private(path: Path, data: bytes) -> None:
    if path.exists() or path.is_symlink():
        raise PocError("evidence path already exists")
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


def _privacy_text(value: str) -> str:
    return SENSITIVE_TEXT_RE.sub(r"\1[redacted]", value)[:256]


@dataclass
class Actions:
    path: Path
    sanitizer: Any = None
    records: int = 0

    def add(self, action: str, **fields: object) -> None:
        if self.sanitizer is not None:
            fields = _sanitize_value(fields, self.sanitizer)
        record = {
            "timestamp": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
            "action": action,
            **fields,
        }
        data = (json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n").encode()
        if self.path.exists() and self.path.stat().st_size + len(data) > 256 * 1024:
            raise PocError("actions journal exceeded bound")
        if not self.path.exists():
            _write_private(self.path, data)
        else:
            with self.path.open("ab") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
        self.records += 1


def _sanitize_value(value: Any, sanitizer: Any) -> Any:
    if isinstance(value, str):
        return sanitizer(value)
    if isinstance(value, list):
        return [_sanitize_value(item, sanitizer) for item in value]
    if isinstance(value, tuple):
        return [_sanitize_value(item, sanitizer) for item in value]
    if isinstance(value, dict):
        return {key: _sanitize_value(item, sanitizer) for key, item in value.items()}
    return value


class _WakeEvidence:
    """Adapter for the helper's wake_nonsecure action callback."""

    def __init__(self, actions: Actions) -> None:
        self.actions = actions

    def action(self, action: str, **fields: object) -> None:
        self.actions.add(f"wake-{action}", **fields)


def _own_state(state: Any) -> None:
    if not state.top_is_own() or not state.hierarchy_is_own():
        raise PocError("foreign-ui")


def _nodes(state: Any, *, suffix: str | None = None, labels: tuple[str, ...] | None = None) -> list[Any]:
    return [
        node
        for node in state.root.iter("node")
        if (suffix is None or node.attrib.get("class", "").endswith(suffix)
            or (suffix == "Button" and node.attrib.get("clickable") == "true"))
        and (labels is None or (node.attrib.get("text") in labels if suffix is None
                               else any(child.attrib.get("text") in labels for child in node.iter("node"))))
    ]


def _focused(state: Any) -> list[Any]:
    return [node for node in state.root.iter("node") if node.attrib.get("focused") == "true"]


def _parent_map(state: Any) -> dict[int, Any]:
    parents: dict[int, Any] = {}
    for parent in state.root.iter("node"):
        for child in list(parent):
            parents[id(child)] = parent
    return parents


def _labelled_controls(state: Any, suffix: str, labels: tuple[str, ...]) -> list[Any]:
    """Find controls by an exact label on the same semantic parent.

    Compose exposes the control and its Text label as sibling semantics nodes.
    A control whose own text is the label is also accepted for the older merged
    semantics tree.  Arbitrary nearby text is deliberately not accepted.
    """
    parents = _parent_map(state)
    found: list[Any] = []
    known = set(labels)
    controls = [node for node in state.root.iter("node") if node.attrib.get("class", "").endswith(suffix)]
    for control in controls:
        if control.attrib.get("text", "") in known:
            found.append(control)
            continue
        parent = parents.get(id(control))
        if parent is None:
            continue
        # Compose can flatten multiple Rows into the same semantic parent.
        # Bind only the first following label, not any label in that parent.
        siblings = list(parent)
        index = next(i for i, sibling in enumerate(siblings) if sibling is control)
        for sibling in siblings[index + 1:]:
            if sibling.attrib.get("checkable") == "true" or sibling.attrib.get("class", "").endswith(("CheckBox", "RadioButton")):
                break
            text = sibling.attrib.get("text", "")
            if text:
                if text in known:
                    found.append(control)
                break
    unique: list[Any] = []
    seen: set[int] = set()
    for node in found:
        if id(node) not in seen:
            unique.append(node)
            seen.add(id(node))
    return unique


def _profile_controls(state: Any) -> dict[str, Any]:
    controls: dict[str, Any] = {}
    for profile, label in PROFILE_LABELS.items():
        matches = _labelled_controls(state, "RadioButton", (label,))
        if len(matches) == 1:
            controls[profile] = matches[0]
    return controls


def _verify_profile_selected(state: Any, profile: str) -> Any:
    controls = _profile_controls(state)
    if set(controls) != set(PROFILE_IDS):
        raise PocError("profile-radio-not-exact")
    if any(control.attrib.get("enabled") != "true" for control in controls.values()):
        raise PocError("profile-radio-disabled")
    if controls[profile].attrib.get("checked") != "true":
        raise PocError("profile-not-selected")
    if any(other != profile and control.attrib.get("checked") != "false" for other, control in controls.items()):
        raise PocError("profile-selection-ambiguous")
    return controls[profile]


def _profile_labelled_control(state: Any, profile: str) -> Any:
    if profile not in PROFILE_LABELS:
        raise PocError("unsupported-profile")
    controls = _profile_controls(state)
    if set(controls) != set(PROFILE_IDS):
        raise PocError("profile-radio-not-exact")
    control = controls[profile]
    if control.attrib.get("enabled") != "true":
        raise PocError("profile-radio-disabled")
    return control


def _patch_node(state: Any) -> Any:
    matches = _labelled_controls(state, "CheckBox", (PATCH_LABEL,))
    if len(matches) != 1:
        raise PocError("patch-checkbox-not-exact")
    return matches[0]


def _code_capture_node(state: Any) -> Any:
    matches = _labelled_controls(state, "CheckBox", (CODE_CAPTURE_LABEL,))
    if len(matches) != 1:
        raise PocError("code-capture-checkbox-not-exact")
    return matches[0]


def _consent_nodes(state: Any) -> list[Any]:
    # Compose renders the Checkbox and its label as sibling semantics nodes.
    labelled = _labelled_controls(state, "CheckBox", CONSENT_LABELS)
    if len(labelled) == 1:
        return labelled if labelled[0].attrib.get("enabled") == "true" else []

    # Compatibility with the original PoC's single-checkbox semantics tree:
    # the exact consent label is still required, but it may not be represented
    # as a sibling of the sole control.
    boxes = [node for node in state.root.iter("node") if node.attrib.get("class", "").endswith("CheckBox")]
    labels = [node for node in state.root.iter("node") if node.attrib.get("text", "") in CONSENT_LABELS]
    if len(boxes) == 1 and len(labels) == 1 and boxes[0].attrib.get("enabled") == "true":
        return boxes
    return []


def _known_texts(state: Any) -> list[str]:
    """Return bounded text only after the caller has proved own hierarchy."""
    values: list[str] = []
    for node in state.root.iter("node"):
        value = node.attrib.get("text", "").strip()
        if value and value not in values:
            values.append(_privacy_text(value))
        if len(values) >= 80:
            break
    return values


def _is_own_dialog(state: Any) -> bool:
    texts = {node.attrib.get("text", "") for node in state.root.iter("node")}
    classes = {node.attrib.get("class", "") for node in state.root.iter("node")}
    return (
        any(item.endswith("AlertDialog") for item in classes)
        or ({"Functional PoC", "Cerrar"} <= texts)
        or "PoC no preparada" in texts
    )


def _dump(adb: Any, helper: ModuleType, deadline: float, global_deadline: float) -> Any:
    if time.monotonic() >= deadline:
        raise PocError("active-deadline")
    local_deadline = min(deadline, time.monotonic() + UI_COMMAND_SECONDS)
    cleanup = min(global_deadline, local_deadline + UI_CLEANUP_SECONDS)
    try:
        return helper.dump_ui(adb, deadline=local_deadline, cleanup_deadline=cleanup)
    except Exception as exc:
        # Do not persist helper/device exception text: it may contain a foreign
        # hierarchy name or an implementation path.
        raise PocError("ui-dump-failed") from exc


def _wait_initial_ui(adb: Any, helper: ModuleType, deadline: float, global_deadline: float, actions: Actions) -> None:
    """Read-only readiness after cold Activity creation, before any form input."""
    end = min(deadline, time.monotonic() + 20.0)
    for attempt in range(1, 4):
        if time.monotonic() >= end:
            break
        try:
            state = _dump(adb, helper, end, global_deadline)
            _own_state(state)
            actions.add("initial-ui-ready", attempt=attempt)
            return
        except PocError as exc:
            if str(exc) not in ("ui-dump-failed", "foreign-ui"):
                raise
            actions.add("initial-ui-wait", attempt=attempt, reason=str(exc))
            time.sleep(min(0.25, max(0.0, end - time.monotonic())))
    raise PocError("initial-ui-not-ready")


def _guard_initial(state: Any) -> Any:
    _own_state(state)
    if _is_own_dialog(state):
        raise PocError("prepare-dialog:" + json.dumps(_known_texts(state), ensure_ascii=False))
    prepare = _nodes(state, suffix="Button", labels=PREPARE_LABELS)
    if len(prepare) != 1 or prepare[0].attrib.get("enabled") != "true":
        raise PocError("prepare-button-not-exact")
    return prepare[0]


def _send(helper: ModuleType, keyboard: Any, keycode: int, deadline: float) -> None:
    _send_modified(helper, keyboard, 0, keycode, deadline)


def _send_modified(helper: ModuleType, keyboard: Any, modifier: int, keycode: int, deadline: float) -> None:
    try:
        if time.monotonic() >= deadline:
            raise PocError("active-deadline")
        keyboard.key(modifier, keycode)
        if time.monotonic() >= deadline:
            raise PocError("active-deadline")
        time.sleep(min(0.15, max(0.0, deadline - time.monotonic())))
    except Exception as exc:
        if isinstance(exc, PocError):
            raise
        raise PocError("hid-control-failed") from exc


def _focus_prepare(
    adb: Any,
    helper: ModuleType,
    keyboard: Any,
    deadline: float,
    actions: Actions | None = None,
) -> None:
    state = _dump(adb, helper, deadline, getattr(adb, "deadline", deadline) or deadline)
    _own_state(state)
    if actions is not None:
        actions.add("own-initial-ui", nodes=[{
            key: _privacy_text(node.attrib.get(key, ""))
            for key in ("class", "text", "enabled", "focused", "clickable", "checkable")
        } for node in list(state.root.iter("node"))[:40]])
    for position in range(MAX_TABS + 1):
        _own_state(state)
        if _is_own_dialog(state):
            raise PocError("prepare-dialog")
        candidates = _nodes(state, suffix="Button", labels=PREPARE_LABELS)
        if len(candidates) > 1 or (candidates and candidates[0].attrib.get("enabled") != "true"):
            raise PocError("prepare-button-not-exact")
        # A scrollable profile form may not expose the button until keyboard
        # focus brings it into view. Tabs are safe within this own hierarchy;
        # Enter remains gated by the exact visible prepare control.
        prepare = candidates[0] if candidates else None
        focused = _focused(state)
        if len(focused) > 1:
            raise PocError("focus-not-unique")
        if prepare is not None and len(focused) == 1 and focused[0] is prepare:
            return
        if position == MAX_TABS:
            raise PocError("prepare-button-not-focused-within-eighteen-tabs")
        _send(helper, keyboard, 43, deadline)
        if actions is not None:
            actions.add("tab-prepare", position=position + 1)
        state = _dump(adb, helper, deadline, getattr(adb, "deadline", deadline) or deadline)


def _focus_profile(
    adb: Any,
    helper: ModuleType,
    keyboard: Any,
    deadline: float,
    profile: str,
    actions: Actions,
) -> Any:
    state = _dump(adb, helper, deadline, getattr(adb, "deadline", deadline) or deadline)
    for position in range(MAX_TABS + 1):
        _own_state(state)
        if _is_own_dialog(state):
            raise PocError("profile-dialog")
        target = _profile_labelled_control(state, profile)
        focused = _focused(state)
        if len(focused) > 1:
            raise PocError("focus-not-unique")
        if len(focused) == 1 and focused[0] is target:
            return state
        if position == MAX_TABS:
            raise PocError("profile-not-focused-within-eighteen-tabs")
        _send(helper, keyboard, 43, deadline)
        actions.add("tab-profile", profile=profile, position=position + 1)
        state = _dump(adb, helper, deadline, getattr(adb, "deadline", deadline) or deadline)
    raise PocError("profile-not-focused-within-eighteen-tabs")  # pragma: no cover


def _select_profile(
    adb: Any,
    helper: ModuleType,
    keyboard: Any,
    deadline: float,
    profile: str,
    actions: Actions,
) -> Any:
    state = _focus_profile(adb, helper, keyboard, deadline, profile, actions)
    target = _profile_labelled_control(state, profile)
    if target.attrib.get("checked") != "true":
        _send(helper, keyboard, 44, deadline)
        actions.add("profile-space", profile=profile)
        state = _dump(adb, helper, deadline, getattr(adb, "deadline", deadline) or deadline)
        _own_state(state)
    _verify_profile_selected(state, profile)
    actions.add("profile-selected", profile=profile)
    return state


def _select_patch_read_only(
    adb: Any,
    helper: ModuleType,
    keyboard: Any,
    deadline: float,
    actions: Actions,
    initial_state: Any,
) -> Any:
    """Opt into the app's patch checkbox without claiming a patch was applied."""
    state = initial_state
    for position in range(MAX_TABS + 1):
        _own_state(state)
        patch = _patch_node(state)
        if patch.attrib.get("enabled") != "true":
            raise PocError("patch-checkbox-disabled")
        focused = _focused(state)
        if len(focused) > 1:
            raise PocError("focus-not-unique")
        if len(focused) == 1 and focused[0] is patch:
            break
        if position == MAX_TABS:
            raise PocError("patch-not-focused-within-eighteen-tabs")
        _send(helper, keyboard, 43, deadline)
        actions.add("tab-patch-read-only", position=position + 1)
        state = _dump(adb, helper, deadline, getattr(adb, "deadline", deadline) or deadline)
    else:  # pragma: no cover - the bounded loop raises on its last iteration
        raise PocError("patch-not-focused-within-eighteen-tabs")

    patch = _patch_node(state)
    if patch.attrib.get("checked") != "true":
        _send(helper, keyboard, 44, deadline)
        actions.add("patch-read-only-space")
        state = _dump(adb, helper, deadline, getattr(adb, "deadline", deadline) or deadline)
        _own_state(state)
        patch = _patch_node(state)
        if patch.attrib.get("checked") != "true":
            raise PocError("patch-checkbox-did-not-become-checked")
    actions.add("patch-read-only-requested", applied=False)
    return state


def _select_code_capture_read_only(
    adb: Any,
    helper: ModuleType,
    keyboard: Any,
    deadline: float,
    actions: Actions,
    initial_state: Any,
) -> Any:
    """Explicitly opt into fixed code windows, without enabling memory patching."""
    state = initial_state
    for position in range(MAX_TABS + 1):
        _own_state(state)
        matches = _labelled_controls(state, "CheckBox", (CODE_CAPTURE_LABEL,))
        if len(matches) > 1:
            raise PocError("code-capture-checkbox-not-exact")
        # A scrollable Compose screen omits controls below the viewport.
        # Tab can reveal them; Space is never sent until the exact labelled
        # control is visible and uniquely focused in our own hierarchy.
        capture = matches[0] if matches else None
        if capture is not None and capture.attrib.get("enabled") != "true":
            raise PocError("code-capture-checkbox-disabled")
        focused = _focused(state)
        if len(focused) > 1:
            raise PocError("focus-not-unique")
        if capture is not None and len(focused) == 1 and focused[0] is capture:
            break
        if position == MAX_TABS:
            raise PocError("code-capture-not-focused-within-eighteen-tabs")
        _send(helper, keyboard, 43, deadline)
        actions.add("tab-code-capture", position=position + 1)
        state = _dump(adb, helper, deadline, getattr(adb, "deadline", deadline) or deadline)
    capture = _code_capture_node(state)
    if capture.attrib.get("checked") != "true":
        _send(helper, keyboard, 44, deadline)
        actions.add("code-capture-space")
        state = _dump(adb, helper, deadline, getattr(adb, "deadline", deadline) or deadline)
        _own_state(state)
        capture = _code_capture_node(state)
        if capture.attrib.get("checked") != "true":
            raise PocError("code-capture-checkbox-did-not-become-checked")
    actions.add("code-capture-opt-in-requested", applied=False)
    return state


def _is_prepared_state(state: Any, profile: str) -> bool:
    if profile not in READY_STATUS_BY_PROFILE:
        return False
    statuses = _nodes(state, suffix="TextView", labels=(READY_STATUS_BY_PROFILE[profile],))
    consent = _consent_nodes(state)
    launch = _nodes(state, suffix="Button", labels=LAUNCH_LABELS)
    return (
        len(statuses) == 1
        and len(consent) == 1
        and consent[0].attrib.get("enabled") == "true"
        and consent[0].attrib.get("checked") == "false"
        and len(launch) == 1
    )


def _wait_prepared(adb: Any, helper: ModuleType, deadline: float, profile: str) -> Any:
    end = min(deadline, time.monotonic() + PREPARE_SECONDS)
    last_texts: list[str] = []
    while time.monotonic() < end:
        state = _dump(adb, helper, end, getattr(adb, "deadline", end) or end)
        _own_state(state)
        last_texts = _known_texts(state)
        if _is_own_dialog(state):
            raise PocError("prepare-dialog:" + json.dumps(last_texts, ensure_ascii=False))
        if _is_prepared_state(state, profile):
            return state
        time.sleep(min(0.25, max(0.0, end - time.monotonic())))
    raise PocError("prepare-timeout")


def _drive_launch(
    adb: Any,
    helper: ModuleType,
    keyboard: Any,
    deadline: float,
    actions: Actions,
    state: Any,
    profile: str,
) -> None:
    _own_state(state)
    if not _is_prepared_state(state, profile):
        raise PocError("prepared-controls-not-exact")
    consent = _consent_nodes(state)
    launch = _nodes(state, suffix="Button", labels=LAUNCH_LABELS)
    if len(consent) != 1 or len(launch) != 1 or consent[0].attrib.get("checked") != "false":
        raise PocError("prepared-controls-not-exact")

    # Focus the checkbox with at most eighteen Tabs.  Every dump remains guarded
    # by the exclusive own hierarchy; no generic Enter or XML action is used.
    for position in range(MAX_TABS + 1):
        state = _dump(adb, helper, deadline, getattr(adb, "deadline", deadline) or deadline)
        _own_state(state)
        consent = _consent_nodes(state)
        focused = _focused(state)
        if len(consent) == 1 and len(focused) == 1 and focused[0] is consent[0]:
            break
        if position == MAX_TABS:
            raise PocError("consent-not-focused-within-eighteen-tabs")
        if len(focused) > 1:
            raise PocError("focus-not-unique")
        _send(helper, keyboard, 43, deadline)
        actions.add("tab-consent", position=position + 1)
    else:  # pragma: no cover - the bounded loop raises on its last iteration
        raise PocError("consent-not-focused-within-eighteen-tabs")

    state = _dump(adb, helper, deadline, getattr(adb, "deadline", deadline) or deadline)
    _own_state(state)
    consent = _consent_nodes(state)
    focused = _focused(state)
    if len(consent) != 1 or len(focused) != 1 or focused[0] is not consent[0] or consent[0].attrib.get("checked") != "false":
        raise PocError("consent-not-unchecked-focused")
    _send(helper, keyboard, 44, deadline)
    actions.add("consent-space")

    state = _dump(adb, helper, deadline, getattr(adb, "deadline", deadline) or deadline)
    _own_state(state)
    consent = _consent_nodes(state)
    focused = _focused(state)
    if len(consent) != 1 or len(focused) != 1 or focused[0] is not consent[0] or consent[0].attrib.get("checked") != "true":
        raise PocError("consent-did-not-become-checked")

    _send(helper, keyboard, 43, deadline)
    actions.add("tab-launch")
    state = _dump(adb, helper, deadline, getattr(adb, "deadline", deadline) or deadline)
    _own_state(state)
    launch = _nodes(state, suffix="Button", labels=LAUNCH_LABELS)
    focused = _focused(state)
    if len(launch) != 1 or launch[0].attrib.get("enabled") != "true" or len(focused) != 1 or focused[0] is not launch[0]:
        raise PocError("launch-button-not-focused")
    actions.add("launch-enter")
    _send(helper, keyboard, 40, deadline)
    actions.add("launch-enter-sent")


def _capture_logcat(
    adb: Any,
    run_dir: Path,
    label: str,
    actions: Actions,
    deadline: float,
    sanitizer: Any = None,
) -> None:
    if time.monotonic() >= deadline:
        actions.add("logcat", phase=label, status="not-run")
        return
    result = adb.run(
        (
            "logcat", "-d", f"--uid={UID}", "-b", "main", "-b", "system", "-b", "crash", "-b", "events",
            "-v", "threadtime", "-T", getattr(adb, "poc_logcat_since", "1"),
        ),
        timeout=min(20.0, max(0.1, deadline - time.monotonic())),
    )
    raw = bytes(getattr(result, "stdout", b""))
    if result.returncode != 0:
        actions.add("logcat", phase=label, status="error")
        return
    truncated = len(raw) > LOGCAT_BYTES
    captured = raw[:LOGCAT_BYTES]
    if sanitizer is not None:
        captured = sanitizer(captured.decode("utf-8", "replace")).encode("utf-8")[:LOGCAT_BYTES]
    _write_private(run_dir / f"logcat-{label}.txt", captured)
    actions.add("logcat", phase=label, status="saved", bytes=len(captured), truncated=truncated)


def _discover_poc_logs(
    adb: Any,
    run_dir: Path,
    phase: str,
    actions: Actions,
    deadline: float,
    sanitizer: Any = None,
) -> list[str]:
    """Read only run-<digits>.jsonl directly under the fixed private folder."""
    if time.monotonic() >= deadline:
        actions.add("poc-logs", phase=phase, status="not-run")
        return []
    result = adb.exec_out(
        "run-as", PACKAGE, "find", "files/functional-poc", "-maxdepth", "1", "-type", "f",
        "-name", "run-*.jsonl", "-print", timeout=min(10.0, max(0.1, deadline - time.monotonic())),
    )
    raw_paths = bytes(getattr(result, "stdout", b""))
    if result.returncode != 0 or len(raw_paths) > POC_DISCOVERY_BYTES:
        actions.add("poc-logs", phase=phase, status="discovery-error")
        return []
    paths = sorted({line for line in raw_paths.decode("utf-8", "replace").splitlines() if line})
    if any(POC_LOG_RE.fullmatch(path) is None for path in paths):
        actions.add("poc-logs", phase=phase, status="path-bound-failed")
        return []
    # Accumulated historical runs must not suppress the current diagnostic.
    # Validate every returned path, then bound content capture newest-first.
    discovered = len(paths)
    paths = sorted(paths, key=lambda path: int(POC_LOG_RE.fullmatch(path).group(1)), reverse=True)[:4]
    actions.add("poc-log-selection", phase=phase, discovered=discovered, selected=len(paths))
    saved: list[str] = []
    total = 0
    for path in paths:
        match = POC_LOG_RE.fullmatch(path)
        if match is None or time.monotonic() >= deadline:
            break
        result = adb.exec_out(
            "run-as", PACKAGE, "head", "-c", str(POC_LOG_BYTES - total + 1), path,
            timeout=min(10.0, max(0.1, deadline - time.monotonic())),
        )
        data = bytes(getattr(result, "stdout", b""))
        if result.returncode != 0 or getattr(result, "stderr", b"") or len(data) > POC_LOG_BYTES - total:
            actions.add("poc-log", phase=phase, status="unreadable-or-overbound")
            continue
        safe = data.decode("utf-8", "replace")
        if sanitizer is not None:
            safe = sanitizer(safe)
        encoded = (safe + ("\n" if safe else "")).encode("utf-8")
        if total + len(encoded) > POC_LOG_BYTES:
            actions.add("poc-log", phase=phase, status="total-bound")
            break
        name = f"{phase}-{match.group(1)}.jsonl"
        destination = run_dir / "poc-logs" / name
        _write_private(destination, encoded)
        total += len(encoded)
        saved.append(str(destination))
    actions.add("poc-logs", phase=phase, status="saved", files=len(saved), bytes=total)
    return saved


def _game_log_allowed(directory: str, name: str) -> bool:
    return (
        (directory == "Logs" and name in GAME_LOG_FIXED_NAMES)
        or (directory == "Errors" and GAME_LOG_ERROR_RE.fullmatch(name) is not None)
    )


def _list_game_log_metadata(adb: Any, deadline: float) -> dict[str, dict[str, Any]] | None:
    """List metadata in the one approved game-log directory, never its contents."""
    if time.monotonic() >= deadline:
        return None
    result = adb.shell(
        "sh", "-c", shlex.quote(GAME_LOG_LIST_SCRIPT),
        timeout=min(10.0, max(0.1, deadline - time.monotonic())),
    )
    raw = bytes(getattr(result, "stdout", b""))
    stderr = bytes(getattr(result, "stderr", b""))
    if result.returncode != 0 or stderr or len(raw) > GAME_LOG_DISCOVERY_BYTES:
        return None
    metadata: dict[str, dict[str, Any]] = {}
    for line in raw.decode("utf-8", "replace").splitlines():
        fields = line.split("\t")
        if len(fields) != 4:
            continue
        directory, name, size_text, mtime_text = fields
        if not _game_log_allowed(directory, name) or "/" in name or "\\" in name or "\x00" in name:
            continue
        try:
            size = int(size_text)
            mtime = int(mtime_text)
        except ValueError:
            continue
        if size < 0 or mtime < 0 or name in metadata:
            continue
        metadata[name] = {"size": size, "mtime": mtime, "directory": directory}
    return metadata


def _game_log_device_path(name: str, directory: str) -> str:
    root = GAME_LOG_FIXED_ROOT if directory == "Logs" else GAME_LOG_ERROR_ROOT
    return f"{root}/{name}"


def _game_log_record(name: str, metadata: dict[str, Any], run_start: dict[str, dict[str, int]] | None) -> dict[str, Any]:
    start = None if run_start is None else run_start.get(name)
    return {
        "name": name,
        "devicePath": _game_log_device_path(name, metadata["directory"]),
        "deviceSize": metadata["size"],
        "deviceMtime": metadata["mtime"],
        "preexistingAtRunStart": None if run_start is None else start is not None,
        "runStart": None if start is None else {
            "deviceSize": start["size"],
            "deviceMtime": start["mtime"],
        },
    }


def _read_game_log(
    adb: Any,
    name: str,
    directory: str,
    cap: int,
    deadline: float,
) -> tuple[bytes | None, str]:
    if time.monotonic() >= deadline:
        return None, "deadline"
    # Both the directory and name have already passed fixed allowlists.  The
    # shell UID can read this app-owned external path; run-as cannot.
    device_path = _game_log_device_path(name, directory)
    script = f"test ! -L {shlex.quote(device_path)} && head -c {cap + 1} -- {shlex.quote(device_path)}"
    result = adb.shell(
        "sh", "-c", shlex.quote(script),
        timeout=min(10.0, max(0.1, deadline - time.monotonic())),
    )
    data = bytes(getattr(result, "stdout", b""))
    if result.returncode != 0 or getattr(result, "stderr", b""):
        return None, "read-error"
    if len(data) > cap:
        return None, "overbound"
    return data, "ok"


def _capture_game_logs(
    adb: Any,
    run_dir: Path,
    actions: Actions,
    deadline: float,
    run_start: dict[str, dict[str, int]] | None,
    sanitizer: Any = None,
) -> list[dict[str, Any]]:
    """Capture only allowlisted logs, with metadata and independent byte caps."""
    if time.monotonic() >= deadline:
        actions.add("game-logs", status="not-run")
        return []
    try:
        metadata = _list_game_log_metadata(adb, deadline)
    except Exception:
        metadata = None
    if metadata is None:
        actions.add("game-logs", status="discovery-error")
        return []

    destination_root = run_dir / "game-logs"
    records: list[dict[str, Any]] = []
    total_remaining = GAME_LOG_TOTAL_BYTES
    names = list(GAME_LOG_FIXED_NAMES)
    names.extend(sorted(
        (name for name in metadata if GAME_LOG_ERROR_RE.fullmatch(name)),
        key=lambda name: (-metadata[name]["mtime"], name),
    )[:GAME_LOG_ERROR_MAX_FILES])
    for name in names:
        item = metadata.get(name)
        if item is None:
            record = {
                "name": name,
                "devicePath": _game_log_device_path(name, "Logs"),
                "status": "missing",
            }
            if run_start is not None:
                record["preexistingAtRunStart"] = False
            else:
                record["preexistingAtRunStart"] = None
            records.append(record)
            continue
        record = _game_log_record(name, item, run_start)
        is_fixed = name in GAME_LOG_FIXED_NAMES
        available = total_remaining
        cap = min(GAME_LOG_FIXED_BYTES if is_fixed else GAME_LOG_ERROR_BYTES, available)
        if cap <= 0:
            record["status"] = "budget"
            records.append(record)
            continue
        data, status = _read_game_log(adb, name, item["directory"], cap, deadline)
        if data is None:
            record["status"] = status
            records.append(record)
            if status == "deadline":
                break
            continue
        if sanitizer is not None:
            data = sanitizer(data.decode("utf-8", "replace")).encode("utf-8")[:cap]
        destination = destination_root / name
        try:
            _write_private(destination, data)
        except Exception:
            record["status"] = "local-write-error"
            records.append(record)
            continue
        record.update({"status": "saved", "path": str(destination), "bytes": len(data)})
        records.append(record)
        total_remaining -= len(data)
    actions.add(
        "game-logs",
        status="saved",
        files=sum(record.get("status") == "saved" for record in records),
        bytes=sum(record.get("bytes", 0) for record in records),
    )
    return records


def _prepared_record_has_forever_metadata(value: Any) -> bool:
    if not isinstance(value, dict):
        return False
    if (
        value.get("event") == "prepared"
        and value.get("profileId") == "classic_forever"
        and value.get("portalHost") == AUTH_PORTAL
        and value.get("memoryPatchEnabled") is True
    ):
        return True
    return False


def _verify_prepared_metadata(paths: list[str]) -> bool:
    """Verify only the newest bounded PoC log's selected Classic metadata."""
    if not paths:
        return False
    def run_number(path: str) -> int:
        match = re.search(r"-(\d+)\.jsonl$", path)
        return -1 if match is None else int(match.group(1))

    newest = max(paths, key=run_number)
    try:
        with open(newest, "rb") as stream:
            data = stream.read(POC_LOG_BYTES)
    except OSError:
        return False
    for line in data.decode("utf-8", "replace").splitlines():
        try:
            if _prepared_record_has_forever_metadata(json.loads(line)):
                return True
        except (json.JSONDecodeError, TypeError, ValueError):
            continue
    return False


def _capture_screenshot(
    adb: Any,
    helper: ModuleType,
    run_dir: Path,
    elapsed: int,
    deadline: float,
    actions: Actions,
    prefix: str = "launch",
) -> str | None:
    label = f"{elapsed:03d}"
    try:
        state = _dump(adb, helper, deadline, getattr(adb, "deadline", deadline) or deadline)
        _own_state(state)
    except PocError:
        actions.add("screenshot", atSeconds=elapsed, status="skipped", reason="pre-ui-not-own")
        return None
    try:
        result = adb.exec_out("screencap", "-p", timeout=min(15.0, max(0.1, deadline - time.monotonic())))
    except Exception:
        actions.add("screenshot", atSeconds=elapsed, status="skipped", reason="screencap-error")
        return None
    raw = bytes(getattr(result, "stdout", b""))
    if result.returncode != 0:
        actions.add("screenshot", atSeconds=elapsed, status="skipped", reason="screencap-error")
        return None
    if len(raw) > MAX_PNG_BYTES:
        actions.add("screenshot", atSeconds=elapsed, status="skipped", reason="png-oversized")
        return None
    if not raw.startswith(b"\x89PNG\r\n\x1a\n"):
        actions.add("screenshot", atSeconds=elapsed, status="skipped", reason="not-png")
        return None
    try:
        state = _dump(adb, helper, deadline, getattr(adb, "deadline", deadline) or deadline)
        _own_state(state)
    except PocError:
        actions.add("screenshot", atSeconds=elapsed, status="skipped", reason="post-ui-not-own")
        return None
    destination = run_dir / "screenshots" / f"{prefix}-{label}.png"
    _write_private(destination, raw)
    actions.add("screenshot", atSeconds=elapsed, status="saved", path=str(destination), bytes=len(raw))
    return str(destination)


def _capture_login_frame(
    adb: Any,
    helper: ModuleType,
    deadline: float,
    global_deadline: float,
) -> bytes:
    """Capture a login frame only between two own-hierarchy guards."""
    state = _dump(adb, helper, deadline, global_deadline)
    _own_state(state)
    try:
        result = adb.exec_out(
            "screencap", "-p", timeout=min(15.0, max(0.1, deadline - time.monotonic()))
        )
    except Exception as exc:
        raise PocError("login-screencap-failed") from exc
    raw = bytes(getattr(result, "stdout", b""))
    state = _dump(adb, helper, deadline, global_deadline)
    _own_state(state)
    if result.returncode != 0 or len(raw) > MAX_PNG_BYTES or not raw.startswith(b"\x89PNG\r\n\x1a\n"):
        raise PocError("login-screencap-invalid")
    return raw


def _capture_login_focus_frames(adb: Any, helper: ModuleType, deadline: float,
                                global_deadline: float) -> tuple[bytes, ...]:
    """Three transient frames between own-UI checks to observe a blinking caret."""
    _own_state(_dump(adb, helper, deadline, global_deadline))
    frames = []
    for index in range(3):
        if time.monotonic() >= deadline:
            raise PocError("login-focus-deadline")
        response = adb.exec_out("screencap", "-p", timeout=min(5.0, deadline - time.monotonic()))
        raw = bytes(getattr(response, "stdout", b""))
        if response.returncode != 0 or len(raw) > MAX_PNG_BYTES or not raw.startswith(b"\x89PNG\r\n\x1a\n"):
            raise PocError("login-screencap-invalid")
        frames.append(raw)
        if index < 2:
            time.sleep(min(0.2, max(0.0, deadline - time.monotonic())))
    _own_state(_dump(adb, helper, deadline, global_deadline))
    return tuple(frames)


def _force_stop(adb: Any, actions: Actions, deadline: float) -> None:
    try:
        result = adb.shell("am", "force-stop", PACKAGE, timeout=min(10.0, max(0.1, deadline - time.monotonic())))
        actions.add("force-stop", status="requested" if result.returncode == 0 else "error")
    except Exception:
        actions.add("force-stop", status="error")


def run_trial(
    output: Path,
    seconds: int,
    profile: str = "original",
    patch_read_only: bool = False,
    code_capture: bool = False,
    prepare_only: bool = False,
    login_credentials_file: Path | None = None,
    login_permit_file: Path | None = None,
    confirm_known_login_screen: bool = False,
    capture_login_result: bool = False,
) -> tuple[Path, dict[str, Any]]:
    login_mode = login_credentials_file is not None or login_permit_file is not None or confirm_known_login_screen
    if prepare_only and not code_capture:
        raise PocError("--prepare-only requires --code-capture")
    if code_capture and (login_mode or capture_login_result):
        raise PocError("--code-capture is mutually exclusive with every Login or post-login capture option")
    if capture_login_result and not login_mode:
        raise PocError("login-result capture requires an authorized login")
    if login_mode and (login_credentials_file is None or (login_permit_file is None and not confirm_known_login_screen)):
        raise PocError("login credentials and one screen-confirmation mode are required")
    if confirm_known_login_screen and login_permit_file is not None:
        raise PocError("login screen-confirmation modes are mutually exclusive")
    if profile not in PROFILE_IDS:
        raise PocError("--profile must be original or classic_forever")
    if patch_read_only and profile != "classic_forever":
        raise PocError("--patch-read-only requires --profile classic_forever")
    if code_capture and profile != "classic_forever":
        raise PocError("--code-capture requires --profile classic_forever")
    max_seconds = LOGIN_MAX_SECONDS if login_mode else MAX_SECONDS
    if seconds < 1 or seconds > max_seconds:
        raise PocError(f"--seconds must be between 1 and {max_seconds}")
    require_serial()
    output_root = _safe_output(output)
    login = _login_module() if login_mode else None
    credentials: Any | None = None
    sanitizer: Any = None
    permit_path: Path | None = None
    reference: Any | None = None
    if login_mode:
        if profile != "classic_forever" or not patch_read_only:
            raise PocError("login requires --profile classic_forever and --patch-read-only")
        try:
            credentials = login.load_credentials(
                login_credentials_file, forbidden_roots=(output_root,)
            )
            if login_permit_file is not None:
                permit_path = login.validate_permit_path(
                    login_permit_file, forbidden_roots=(output_root,)
                )
            reference = login.load_fixed_reference()
        except login.LoginInputError as exc:
            raise PocError(str(exc)) from None
        sanitizer = login.make_sanitizer(credentials)
    helper = _helpers()
    run_dir = _new_run_dir(output_root)
    actions = Actions(run_dir / "actions.jsonl", sanitizer=sanitizer)
    started_at = time.monotonic()
    active_deadline = started_at + seconds
    global_deadline = active_deadline + CLEANUP_SECONDS
    adb = helper.Adb(ADB, SERIAL)
    # Wake primitives use the same deadline-bound helper through a .raw facade.
    adb.raw = adb
    # Active device commands cannot run into cleanup.  The deadline is widened
    # only after launch observation, for the two final read-only captures and
    # the one owned-package force-stop.
    adb.deadline = active_deadline
    result: dict[str, Any] = {
        "schema": 1,
        "kind": "wowforever.functional-poc-observation",
        "serial": SERIAL,
        "package": PACKAGE,
        "uid": UID,
        "activity": ACTIVITY,
        "versionCode": VERSION_CODE,
        "versionName": VERSION_NAME,
        "profile": profile,
        "patchReadOnlyRequested": patch_read_only,
        "codeCaptureRequested": code_capture,
        "prepareOnlyRequested": prepare_only,
        "requestedSeconds": seconds,
        "launchRequested": False,
        "loginRequested": False,
        "loginSubmitted": False,
        "loginResultCaptureRequested": capture_login_result,
        "readOnlyMemory": True,
        "bytesWritten": 0,
        "status": "UNKNOWN",
        "screenshots": [],
        "preAuthScreenshot": None,
        "pocLogs": [],
        "gameLogs": [],
        "error": None,
    }
    keyboard: Any | None = None
    login_active = False
    run_start_game_logs: dict[str, dict[str, int]] | None = None
    game_logs_captured = False
    text_logs_captured = False

    def capture_final_game_logs() -> None:
        nonlocal game_logs_captured
        if game_logs_captured:
            return
        game_logs_captured = True
        # Keep a small portion of the fixed 20-second cleanup window for the
        # existing owned-package force-stop.  Log capture is observational and
        # must never consume cleanup or turn an absent log into a failure.
        capture_deadline = global_deadline - GAME_LOG_CAPTURE_RESERVE_SECONDS
        try:
            adb.deadline = global_deadline
            result["gameLogs"] = _capture_game_logs(
                adb, run_dir, actions, capture_deadline, run_start_game_logs,
                sanitizer,
            )
        except Exception:
            actions.add("game-logs", status="runner-error")

    def capture_final_text_logs() -> None:
        nonlocal text_logs_captured
        if text_logs_captured:
            return
        text_logs_captured = True
        adb.deadline = global_deadline
        try:
            result["pocLogs"] = _discover_poc_logs(
                adb, run_dir, "final", actions, global_deadline, sanitizer,
            )
            _capture_logcat(adb, run_dir, "final", actions, global_deadline, sanitizer)
        except Exception:
            actions.add("final-text-logs", status="runner-error")

    try:
        actions.add("trial-start", requestedSeconds=seconds)
        try:
            run_start_game_logs = _list_game_log_metadata(adb, active_deadline)
            actions.add(
                "game-logs-run-start",
                status="known" if run_start_game_logs is not None else "unavailable",
                files=0 if run_start_game_logs is None else len(run_start_game_logs),
            )
        except Exception:
            run_start_game_logs = None
            actions.add("game-logs-run-start", status="unavailable", files=0)
        date = adb.shell("date", "+%m-%dT%H:%M:%S.000", timeout=5)
        since = _text(date)
        if date.returncode == 0 and re.fullmatch(r"\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.000", since):
            adb.poc_logcat_since = since.replace("T", " ")
        if time.monotonic() >= active_deadline:
            raise PocError("active-deadline")
        policy_deadline = min(active_deadline, time.monotonic() + 2.0)
        if not helper.policy_is_nonsecure(adb, deadline=policy_deadline):
            raise PocError("nonsecure-policy-required")
        with helper.UsbKeyboard(SERIAL) as opened:
            keyboard = opened
            # wake_nonsecure performs the explicit nonsecure policy check,
            # sends exactly modifier=2/keycode=0, and uses only public
            # wm dismiss-keyguard.  The same HID context remains open below.
            prior_deadline = adb.deadline
            adb.deadline = min(global_deadline, active_deadline)
            _wake_helpers().wake_nonsecure(adb, _WakeEvidence(actions), keyboard)
            adb.deadline = prior_deadline
            if time.monotonic() >= active_deadline:
                raise PocError("active-deadline")
            actions.add("activity-start-intent", component=COMPONENT)
            started = adb.shell("am", "start", "-n", COMPONENT, timeout=15)
            if started.returncode != 0:
                raise PocError("activity-start-failed")
            _capture_logcat(adb, run_dir, "early", actions, active_deadline, sanitizer)
            _wait_initial_ui(adb, helper, active_deadline, global_deadline, actions)
            selected_state = _select_profile(
                adb, helper, keyboard, active_deadline, profile, actions,
            )
            if patch_read_only:
                selected_state = _select_patch_read_only(
                    adb, helper, keyboard, active_deadline, actions, selected_state,
                )
            if code_capture:
                selected_state = _select_code_capture_read_only(
                    adb, helper, keyboard, active_deadline, actions, selected_state,
                )
            _focus_prepare(adb, helper, keyboard, active_deadline, actions)
            actions.add("prepare-enter")
            _send(helper, keyboard, 40, active_deadline)
            actions.add("prepare-enter-sent")
            try:
                prepared_state = _wait_prepared(adb, helper, active_deadline, profile)
            except PocError as exc:
                reason = str(exc)
                if reason.startswith("prepare-dialog:"):
                    try:
                        texts = json.loads(reason.split(":", 1)[1])
                    except json.JSONDecodeError:
                        texts = []
                    actions.add("prepare-error-dialog", ownUiTexts=texts)
                raise
            prepared_logs = _discover_poc_logs(
                adb, run_dir, "early", actions, active_deadline, sanitizer,
            )
            if login_mode and not _verify_prepared_metadata(prepared_logs):
                actions.add("login-prepared-metadata", status="mismatch")
                raise PocError("prepared-metadata-mismatch")
            if prepare_only:
                actions.add("prepare-only-ready", status="prepared", launchRequested=False, codeCaptureRequested=True)
                result["status"] = "PREPARED"
                capture_final_game_logs()
                capture_final_text_logs()
                raise PrepareOnlyComplete
            _drive_launch(adb, helper, keyboard, active_deadline, actions, prepared_state, profile)
            result["launchRequested"] = True
            launch_started = time.monotonic()
            actions.add("launch-requested")
            screenshot_paths: list[str] = []
            if login_mode:
                preauth_at = 40
                wait_until = launch_started + preauth_at
                if wait_until >= active_deadline:
                    raise PocError("login-preauth-deadline")
                time.sleep(max(0.0, wait_until - time.monotonic()))
                raw_preauth = _capture_login_frame(adb, helper, active_deadline, global_deadline)
                reference_confirmed = login.frame_matches(raw_preauth, reference, None)
                try:
                    preview = login.masked_preview(raw_preauth)
                except login.LoginError as exc:
                    raise PocError(str(exc)) from None
                del raw_preauth
                preauth_path = run_dir / "screenshots" / "preauth-040.png"
                _write_private(preauth_path, preview)
                actions.add(
                    "screenshot", atSeconds=preauth_at, status="saved",
                    path=str(preauth_path), bytes=len(preview), masked=True,
                )
                result["preAuthScreenshot"] = str(preauth_path)
                nonce = uuid.uuid4().hex
                _write_json(
                    run_dir / "login-ready.json",
                    {
                        "schema": 1,
                        "kind": "wowforever.functional-poc-login-ready",
                        "runDirIdentity": run_dir.name,
                        "nonce": nonce,
                        "profile": "classic_forever",
                        "portal": AUTH_PORTAL,
                        "preAuthScreenshot": str(preauth_path),
                    },
                )
                actions.add("login-ready", status="written")
                if confirm_known_login_screen:
                    if not reference_confirmed:
                        raise PocError("login-known-form-not-confirmed")
                    actions.add("login-known-form-confirmation", status="confirmed")
                else:
                    permit_deadline = min(active_deadline - 20.0, time.monotonic() + 60.0)
                    if permit_deadline <= time.monotonic():
                        raise PocError("login-permit-timeout")
                    actions.add("login-permit-wait", status="started")
                    if not login.wait_for_permit(permit_path, nonce, permit_deadline):
                        actions.add("login-permit-wait", status="timeout")
                        raise PocError("login-permit-timeout")
                    actions.add("login-permit-wait", status="confirmed")
                result["loginRequested"] = True
                login_active = True

                def login_frame(capture_deadline: float) -> tuple[bytes, ...]:
                    return _capture_login_focus_frames(adb, helper, capture_deadline, global_deadline)

                def login_phase_guard(phase: str) -> None:
                    actions.add("login-phase", phase=phase, status="guard-start")
                    if phase == "tab":
                        state = _dump(adb, helper, active_deadline, global_deadline)
                        _own_state(state)
                        return
                    focus = "username" if phase == "username" else "password"
                    try:
                        login.poll_visual_guard(
                            login_frame, reference, focus=focus, deadline=active_deadline,
                            diagnostic=lambda facts: actions.add("login-frame-check", phase=phase, **facts),
                        )
                    except (login.LoginError, PocError) as exc:
                        actions.add("login-guard-error", phase=phase, errorType=type(exc).__name__)
                        raise PocError(f"login-{phase}-guard-failed") from exc

                def login_send(modifier: int, usage: int) -> None:
                    _send(helper, keyboard, usage, active_deadline) if modifier == 0 else _send_modified(
                        helper, keyboard, modifier, usage, active_deadline,
                    )

                login.submit_login(
                    keyboard,
                    credentials,
                    phase_guard=login_phase_guard,
                    send=login_send,
                    action=lambda name: actions.add(name),
                )
                result["loginSubmitted"] = True
                actions.add("login-submitted", status="one-enter")
                submitted_at = time.monotonic()
                if capture_login_result:
                    # Explicit opt-in for the supplied test account. No input,
                    # retries, or dialog dismissal is performed during capture.
                    for delay in (1, 8, 20, 35):
                        capture_at = submitted_at + delay
                        if capture_at >= active_deadline - 8.0:
                            break
                        time.sleep(max(0.0, capture_at - time.monotonic()))
                        try:
                            raw = _capture_login_frame(adb, helper, active_deadline, global_deadline)
                            preview = login.login_result_preview(raw)
                            del raw
                            path = run_dir / "screenshots" / f"postauth-{delay:03}.png"
                            _write_private(path, preview)
                            screenshot_paths.append(str(path))
                            actions.add("login-result-screenshot", afterSubmitSeconds=delay,
                                        status="saved", path=str(path), systemBarsMasked=True,
                                        testAccountFieldsVisible=True)
                        except Exception as exc:
                            actions.add("login-result-screenshot", afterSubmitSeconds=delay,
                                        status="skipped", errorType=type(exc).__name__)
                # Without the explicit option, post-secret images stay disabled.
                time.sleep(max(0.0, active_deadline - time.monotonic()))
            else:
                for target in SCREENSHOT_TIMES:
                    if target > seconds:
                        break
                    wait_until = launch_started + target
                    if wait_until >= active_deadline:
                        break
                    time.sleep(max(0.0, wait_until - time.monotonic()))
                    if time.monotonic() >= active_deadline:
                        break
                    path = _capture_screenshot(adb, helper, run_dir, target, active_deadline, actions)
                    if path is not None:
                        screenshot_paths.append(path)
            result["screenshots"] = screenshot_paths
            result["status"] = "OBSERVED" if (screenshot_paths or result.get("preAuthScreenshot")) else "UNKNOWN"
            capture_final_game_logs()
            capture_final_text_logs()
    except PrepareOnlyComplete:
        pass
    except PocError as exc:
        result["status"] = "error"
        reason = str(exc)
        if reason.startswith("prepare-dialog:"):
            reason = "prepare-dialog"
        result["error"] = sanitizer(reason)[:128] if sanitizer is not None else reason[:128]
        if login_active:
            result["status"] = "UNKNOWN"
        if result["launchRequested"] is False:
            # The final log and private PoC log remain useful even for a
            # preparation failure; they are still bounded and read-only.
            capture_final_game_logs()
            capture_final_text_logs()
    except Exception as exc:
        result["status"] = "UNKNOWN" if login_active else "error"
        result["error"] = "runner-failure"
        actions.add("host-exception", exceptionType=type(exc).__name__, frames=[
            {"file": Path(frame.filename).name, "line": frame.lineno, "function": frame.name}
            for frame in traceback.extract_tb(exc.__traceback__)
        ])
        capture_final_game_logs()
        capture_final_text_logs()
    finally:
        capture_final_game_logs()
        capture_final_text_logs()
        # Cleanup is deliberately the only post-evidence device mutation and
        # reports a request result, never process quiescence or WoW exit.
        adb.deadline = global_deadline
        _force_stop(adb, actions, global_deadline)
        result["actionRecords"] = actions.records
        result["finishedAt"] = datetime.now(timezone.utc).isoformat(timespec="milliseconds")
        _write_json(run_dir / "result.json", result)
    return run_dir, result


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="existing external parent directory")
    parser.add_argument("--seconds", type=int, default=DEFAULT_SECONDS, help="bounded active PoC time (1..120, or 180 with login)")
    parser.add_argument(
        "--profile",
        choices=PROFILE_IDS,
        default="original",
        help="connection profile selected through the PoC UI",
    )
    parser.add_argument(
        "--patch-read-only",
        action="store_true",
        help="explicitly opt into Classic Forever's patch checkbox; never claims a patch was applied",
    )
    parser.add_argument(
        "--code-capture",
        action="store_true",
        help="explicitly opt into the two fixed late ARM64 .text windows; no Login and no memory write",
    )
    parser.add_argument(
        "--prepare-only",
        action="store_true",
        help="with --code-capture, stop after the app reports PoC prepared; never press launch",
    )
    parser.add_argument(
        "--login-credentials-file",
        type=Path,
        help="external 0600 JSON credentials file; requires --login-permit-file",
    )
    parser.add_argument(
        "--login-permit-file",
        type=Path,
        help="external 0600 parent confirmation JSON file; requires --login-credentials-file",
    )
    parser.add_argument(
        "--confirm-known-login-screen", action="store_true",
        help="confirm the fixed reviewed Classic Forever login form in memory; fresh field guards remain mandatory",
    )
    parser.add_argument(
        "--capture-login-result", action="store_true",
        help="opt-in test-account game screenshots after the single Login; system bars masked, account fields may be visible",
    )
    args = parser.parse_args(argv)
    if args.code_capture and (
        args.login_credentials_file is not None
        or args.login_permit_file is not None
        or args.confirm_known_login_screen
        or args.capture_login_result
    ):
        parser.error("--code-capture is mutually exclusive with every Login or post-login capture option")
    if args.prepare_only and not args.code_capture:
        parser.error("--prepare-only requires --code-capture")
    if args.patch_read_only and args.profile != "classic_forever":
        parser.error("--patch-read-only requires --profile classic_forever")
    confirmation = args.login_permit_file is not None or args.confirm_known_login_screen
    if (args.login_credentials_file is not None) != confirmation:
        parser.error("login credentials require one screen-confirmation mode")
    if args.login_permit_file is not None and args.confirm_known_login_screen:
        parser.error("login screen-confirmation modes are mutually exclusive")
    if (args.login_credentials_file is not None and (
        args.profile != "classic_forever" or not args.patch_read_only
    )):
        parser.error("login requires --profile classic_forever and --patch-read-only")
    try:
        run_dir, result = run_trial(
            args.output,
            args.seconds,
            profile=args.profile,
            patch_read_only=args.patch_read_only,
            code_capture=args.code_capture,
            prepare_only=args.prepare_only,
            login_credentials_file=args.login_credentials_file,
            login_permit_file=args.login_permit_file,
            confirm_known_login_screen=args.confirm_known_login_screen,
            capture_login_result=args.capture_login_result,
        )
    except PocError as exc:
        parser.error(str(exc))
        return 2
    print(json.dumps({"result": str(run_dir / "result.json"), "status": result["status"]}, separators=(",", ":")))
    return 1 if result["status"] == "error" else 0


if __name__ == "__main__":
    raise SystemExit(main())
