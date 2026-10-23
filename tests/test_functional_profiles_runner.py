from __future__ import annotations

from dataclasses import dataclass
import importlib.util
from pathlib import Path
import sys
import time
import xml.etree.ElementTree as ET

import pytest


ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "scripts/diagnostics/android-process/run_functional_poc.py"
SPEC = importlib.util.spec_from_file_location("functional_profiles_runner", SCRIPT)
assert SPEC and SPEC.loader
runner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)


@dataclass
class State:
    root: ET.Element

    def top_is_own(self) -> bool:
        return True

    def hierarchy_is_own(self) -> bool:
        return True


@dataclass
class Result:
    returncode: int = 0
    stdout: bytes = b""
    stderr: bytes = b""


class FakeHelper:
    def __init__(self, states: list[State]) -> None:
        self.states = iter(states)

    def dump_ui(self, adb: object, *, deadline: float, cleanup_deadline: float) -> State:
        del adb, deadline, cleanup_deadline
        return next(self.states)


class FakeKeyboard:
    def __init__(self) -> None:
        self.keys: list[tuple[int, int]] = []

    def key(self, modifier: int, keycode: int) -> None:
        self.keys.append((modifier, keycode))


class FakeAdb:
    deadline = time.monotonic() + 60


def test_flattened_compose_profile_rows_bind_only_the_next_label():
    root = ET.Element("hierarchy")
    parent = ET.SubElement(root, "node", {"class": "android.widget.ScrollView"})
    first = ET.SubElement(parent, "node", {"class": "android.widget.RadioButton", "checked": "true", "enabled": "true"})
    ET.SubElement(parent, "node", {"text": "Original"})
    second = ET.SubElement(parent, "node", {"class": "android.widget.RadioButton", "checked": "false", "enabled": "true"})
    ET.SubElement(parent, "node", {"text": "Classic Forever"})
    assert runner._profile_controls(State(root)) == {"original": first, "classic_forever": second}


def _node(parent: ET.Element, **attrs: str) -> ET.Element:
    return ET.SubElement(parent, "node", attrs)


def _screen(
    *,
    selected: str = "original",
    focused: str | None = "original",
    ready: str | None = None,
    consent_checked: str = "false",
    consent_enabled: str = "true",
    patch_checked: str = "false",
    patch_enabled: str = "false",
    include_patch: bool = True,
    merged_consent: bool = False,
) -> State:
    root = ET.Element("hierarchy")
    content = _node(root, **{"class": "android.view.View", "text": "Functional PoC"})
    for profile, label in runner.PROFILE_LABELS.items():
        row = _node(content, **{"class": "android.view.View", "text": ""})
        _node(
            row,
            **{
                "class": "android.widget.RadioButton",
                "checked": "true" if profile == selected else "false",
                "enabled": "true",
                "focused": "true" if profile == focused else "false",
            },
        )
        _node(row, **{"class": "android.widget.TextView", "text": label})
    if include_patch:
        row = _node(content, **{"class": "android.view.View", "text": ""})
        _node(
            row,
            **{
                "class": "android.widget.CheckBox",
                "checked": patch_checked,
                "enabled": patch_enabled,
                "focused": "false",
            },
        )
        _node(row, **{"class": "android.widget.TextView", "text": runner.PATCH_LABEL})
    if ready is not None:
        _node(content, **{"class": "android.widget.TextView", "text": ready})
        row = _node(content, **{"class": "android.view.View", "text": ""})
        consent_attrs = {
            "class": "android.widget.CheckBox",
            "checked": consent_checked,
            "enabled": consent_enabled,
            "focused": "false",
        }
        consent = _node(row, **consent_attrs)
        if not merged_consent:
            _node(row, **{"class": "android.widget.TextView", "text": runner.CONSENT_LABELS[0]})
        else:
            consent.set("text", runner.CONSENT_LABELS[0])
        _node(content, **{"class": "android.widget.Button", "text": "Lanzar WoW", "enabled": "true"})
    _node(content, **{"class": "android.widget.Button", "text": "Preparar PoC", "enabled": "true"})
    return State(root)


def test_profile_radio_is_selected_by_bounded_tab_and_space(tmp_path: Path) -> None:
    initial = _screen(selected="original", focused="original")
    focused = _screen(selected="original", focused="classic_forever")
    selected = _screen(selected="classic_forever", focused="classic_forever")
    helper = FakeHelper([initial, focused, selected])
    keyboard = FakeKeyboard()
    actions = runner.Actions(tmp_path / "actions.jsonl")

    result = runner._select_profile(
        FakeAdb(), helper, keyboard, time.monotonic() + 10, "classic_forever", actions,
    )

    assert runner._verify_profile_selected(result, "classic_forever").attrib["checked"] == "true"
    assert keyboard.keys == [(0, 43), (0, 44)]


def test_consent_chooses_enabled_labelled_checkbox_and_skips_disabled_patch() -> None:
    state = _screen(ready=runner.READY_STATUS_BY_PROFILE["classic_forever"])
    consent = runner._consent_nodes(state)
    assert len(consent) == 1
    assert consent[0].attrib["enabled"] == "true"
    assert runner._patch_node(state).attrib["enabled"] == "false"


def test_original_single_checkbox_semantics_remains_supported() -> None:
    state = _screen(
        ready=runner.READY_STATUS_BY_PROFILE["original"],
        include_patch=False,
        merged_consent=True,
    )
    assert len(runner._consent_nodes(state)) == 1


def test_ready_status_is_exact_and_unselected_profile_cannot_start() -> None:
    ready = _screen(ready=runner.READY_STATUS_BY_PROFILE["classic_forever"])
    malformed = _screen(ready="Entorno listo: pulsa Lanzar WoW (classic_forever) extra")
    assert runner._is_prepared_state(ready, "classic_forever")
    assert not runner._is_prepared_state(malformed, "classic_forever")

    unselected = _screen(selected="original")
    with pytest.raises(runner.PocError, match="profile-not-selected"):
        runner._verify_profile_selected(unselected, "classic_forever")


def test_patch_read_only_is_explicitly_classic_only() -> None:
    with pytest.raises(runner.PocError, match="requires --profile classic_forever"):
        runner.run_trial(Path("/outside"), 1, profile="original", patch_read_only=True)
