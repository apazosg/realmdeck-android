from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import time
import xml.etree.ElementTree as ET
from types import SimpleNamespace

import pytest


ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "scripts/diagnostics/android-process/run_functional_poc.py"
SPEC = importlib.util.spec_from_file_location("functional_poc_runner_capture", SCRIPT)
assert SPEC and SPEC.loader
runner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)


def test_code_capture_is_a_separate_classic_forever_opt_in_without_login() -> None:
    with pytest.raises(runner.PocError, match="code-capture requires"):
        runner.run_trial(Path("/outside"), 120, profile="original", code_capture=True)
    text = SCRIPT.read_text(encoding="utf-8")
    assert '"--code-capture"' in text
    assert "no Login and no memory write" in text
    assert '"codeCaptureRequested": code_capture' in text


def test_prepare_only_requires_capture_and_never_enters_launch_path(monkeypatch):
    monkeypatch.setattr(runner, "_drive_launch", lambda *args: pytest.fail("prepare-only entered WoW launch"))
    with pytest.raises(runner.PocError, match="requires --code-capture"):
        runner.run_trial(Path("/outside"), 120, profile="classic_forever", prepare_only=True)
    with pytest.raises(runner.PocError, match="profile"):
        runner.run_trial(Path("/outside"), 120, profile="original", code_capture=True, prepare_only=True)
    text = SCRIPT.read_text(encoding="utf-8")
    assert "prepare-only-ready" in text
    assert "raise PrepareOnlyComplete" in text


def _state(*, visible=False, checked=False, duplicate=False, own=True):
    root = ET.Element("hierarchy")
    parent = ET.SubElement(root, "node", {"class": "android.widget.ScrollView"})
    if visible:
        for _ in range(2 if duplicate else 1):
            ET.SubElement(parent, "node", {"class": "android.widget.CheckBox", "enabled": "true", "focused": "true", "checked": str(checked).lower()})
            ET.SubElement(parent, "node", {"class": "android.widget.TextView", "text": runner.CODE_CAPTURE_LABEL})
    else:
        ET.SubElement(parent, "node", {"class": "android.widget.RadioButton", "focused": "true"})
    return SimpleNamespace(root=root, top_is_own=lambda: own, hierarchy_is_own=lambda: own)


def test_offscreen_capture_is_revealed_before_any_space(monkeypatch, tmp_path):
    keys = []
    states = iter([_state(visible=True), _state(visible=True, checked=True)])
    monkeypatch.setattr(runner, "_send", lambda helper, keyboard, key, deadline: keys.append(key))
    monkeypatch.setattr(runner, "_dump", lambda *args: next(states))
    result = runner._select_code_capture_read_only(SimpleNamespace(), None, None, time.monotonic()+10, runner.Actions(tmp_path / "actions.jsonl"), _state())
    assert keys == [43, 44]
    assert runner._code_capture_node(result).attrib["checked"] == "true"


@pytest.mark.parametrize("initial,error", [(_state(own=False), "foreign-ui"), (_state(visible=True, duplicate=True), "code-capture-checkbox-not-exact")])
def test_capture_rejects_foreign_or_ambiguous_controls_before_input(monkeypatch, tmp_path, initial, error):
    monkeypatch.setattr(runner, "_send", lambda *args: pytest.fail("input before exact own control"))
    with pytest.raises(runner.PocError, match=error):
        runner._select_code_capture_read_only(SimpleNamespace(), None, None, time.monotonic()+10, runner.Actions(tmp_path / "actions.jsonl"), initial)


def test_capture_missing_after_bound_never_sends_space(monkeypatch, tmp_path):
    keys = []
    monkeypatch.setattr(runner, "MAX_TABS", 2)
    monkeypatch.setattr(runner, "_send", lambda helper, keyboard, key, deadline: keys.append(key))
    monkeypatch.setattr(runner, "_dump", lambda *args: _state())
    with pytest.raises(runner.PocError, match="code-capture-not-focused"):
        runner._select_code_capture_read_only(SimpleNamespace(), None, None, time.monotonic()+10, runner.Actions(tmp_path / "actions.jsonl"), _state())
    assert keys == [43, 43]


@pytest.mark.parametrize("login_option", ["login_credentials_file", "login_permit_file", "confirm_known_login_screen", "capture_login_result"])
def test_capture_rejects_every_login_option_before_device_access(login_option):
    value = True if login_option in ("confirm_known_login_screen", "capture_login_result") else Path("/unused")
    with pytest.raises(runner.PocError, match="mutually exclusive"):
        runner.run_trial(Path("/unused"), 120, profile="classic_forever", code_capture=True, prepare_only=True, **{login_option: value})
