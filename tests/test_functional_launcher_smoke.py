from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import shlex
import sys
from types import SimpleNamespace
import time
import xml.etree.ElementTree as ET

import pytest


ROOT = Path(__file__).parents[1]
RUNNER = ROOT / "scripts/diagnostics/android-process/run_functional_launcher_ux.py"
spec = importlib.util.spec_from_file_location("functional_launcher_smoke", RUNNER)
runner = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = runner
spec.loader.exec_module(runner)


class FakeAdb:
    def __init__(self, *, shell: dict[tuple[str, ...], bytes] | None = None, exec_out: dict[tuple[str, ...], bytes] | None = None):
        self.shell_values = shell or {}
        self.exec_values = exec_out or {}
        self.shell_calls: list[tuple[str, ...]] = []
        self.exec_calls: list[tuple[str, ...]] = []

    def shell(self, *args: str, **kwargs: object) -> SimpleNamespace:
        key = tuple(args)
        self.shell_calls.append(key)
        value = self.shell_values.get(key, b"")
        return SimpleNamespace(returncode=0, stdout=value, stderr=b"")

    def exec_out(self, *args: str, **kwargs: object) -> SimpleNamespace:
        key = tuple(args)
        self.exec_calls.append(key)
        value = self.exec_values.get(key, b"")
        return SimpleNamespace(returncode=0, stdout=value, stderr=b"")


def _own_tree(inner: str) -> object:
    root = ET.fromstring(
        '<hierarchy><node package="com.wowforever.arm64probe" class="android.widget.FrameLayout">'
        f"{inner}</node></hierarchy>"
    )
    for node in root.iter("node"):
        node.attrib.setdefault("package", "com.wowforever.arm64probe")
    return SimpleNamespace(
        root=root,
        top_is_own=lambda: True,
        hierarchy_is_own=lambda: all(n.attrib.get("package") == "com.wowforever.arm64probe" for n in root.iter("node")),
    )


def test_wrong_installed_version_blocks_keys_before_wake() -> None:
    adb = FakeAdb(shell={("dumpsys", "package", runner.PACKAGE): b"versionCode=67 versionName=old userId=10330"})
    with pytest.raises(runner.SmokeError, match="package-identity-mismatch"):
        runner._installed_identity(adb, time.monotonic() + 2)
    assert not any(call and call[0] == "wm" for call in adb.shell_calls)


def test_installed_identity_accepts_android_appid_field():
    adb = FakeAdb(shell={("dumpsys", "package", runner.PACKAGE): f"versionCode={runner.VERSION_CODE} versionName={runner.VERSION_NAME} appId=10330".encode()})
    assert runner._installed_identity(adb, time.monotonic() + 2)["uid"] == 10330


def test_control_visibility_requires_enabled_nonzero_bounds_inside_viewport():
    state = _own_tree('<node class="android.view.View" clickable="true" enabled="true" bounds="[10,10][110,60]"/>')
    root_node = next(state.root.iter("node"))
    root_node.set("bounds", "[0,0][1080,2460]")
    control = list(root_node)[0]
    assert runner._visible_control(state, control)
    control.set("bounds", "[10,2500][110,2560]")
    assert not runner._visible_control(state, control)
    control.set("bounds", "[0,0][0,0]")
    assert not runner._visible_control(state, control)


def test_prepared_state_expects_launch_disabled_until_explicit_consent():
    state = _own_tree(
        f'<node class="android.widget.TextView" text="{runner.READY_LABEL}"/>'
        '<node class="android.view.View">'
        f'<node class="android.widget.CheckBox" text="{runner.CONSENT_LABEL}" checkable="true" checked="false" enabled="true" bounds="[0,100][500,150]"/>'
        '<node class="android.widget.Button" text="Lanzar WoW" enabled="false" bounds="[0,160][500,220]"/>'
        '</node>'
    )
    next(state.root.iter("node")).set("bounds", "[0,0][1080,2460]")
    assert runner._prepared(state)


def test_auth_prose_is_not_a_false_positive_but_android_editable_is_blocked() -> None:
    prose = _own_tree('<node class="android.widget.TextView" text="Sin autologin"/>')
    runner._own(prose)
    editable = _own_tree('<node class="android.widget.EditText" text="" password="false"/>')
    with pytest.raises(runner.SmokeError, match="authentication-editable-field"):
        runner._own(editable)


def test_compose_clickable_label_is_canonicalized_once_and_focus_accepts_ancestor() -> None:
    state = _own_tree(
        '<node class="android.view.View" clickable="true" focused="true">'
        '<node class="android.widget.TextView" text="Preparar WoW"/>'
        '<node class="android.widget.Button"/></node>'
    )
    controls = runner._labelled_controls(state, (runner.PREPARE_LABEL,), "Button")
    assert len(controls) == 1
    focused = [node for node in state.root.iter("node") if node.attrib.get("focused") == "true"]
    assert runner._focus_matches(controls[0], focused[0], runner._parents(state))

    duplicate = _own_tree(
        '<node class="android.view.View" clickable="true"><node text="Preparar WoW"/></node>'
        '<node class="android.view.View" clickable="true"><node text="Preparar WoW"/></node>'
    )
    assert len(runner._labelled_controls(duplicate, (runner.PREPARE_LABEL,), "Button")) == 2


def test_compose_checkbox_uses_single_checkable_descendant() -> None:
    # The consent wording lives in the runner constants (mirroring the exact
    # home string); this test pins canonicalization, not a stale sentence copy.
    state = _own_tree(
        '<node class="android.view.View" clickable="true" focused="true">'
        '<node class="android.widget.CheckBox" checkable="true" checked="false"/>'
        f'<node class="android.widget.TextView" text="{runner.CONSENT_LABEL}"/>'
        '</node>'
    )
    controls = runner._labelled_controls(state, (runner.CONSENT_LABEL,), "CheckBox")
    assert len(controls) == 1
    assert controls[0].attrib["checkable"] == "true"
    assert runner._checked(controls[0]) == "false"
    focused = [node for node in state.root.iter("node") if node.attrib.get("focused") == "true"]
    assert runner._focus_matches(controls[0], focused[0], runner._parents(state))


def test_observed_checkbox_sibling_is_not_confused_with_launch_button():
    state = _own_tree(
        '<node class="android.view.View" clickable="false">'
        '<node class="android.widget.TextView" text="Listo para iniciar"/>'
        '<node class="android.widget.CheckBox" checkable="true" checked="false" clickable="true" focused="true"/>'
        f'<node class="android.widget.TextView" text="{runner.CONSENT_LABEL}"/>'
        '<node class="android.view.View" clickable="true" enabled="false"><node text="Lanzar WoW"/></node>'
        '</node>'
    )
    controls = runner._consent(state)
    assert len(controls) == 1
    assert controls[0].get("class") == "android.widget.CheckBox"
    assert runner._focus_matches(controls[0], runner._focused(state)[0], runner._parents(state))


def test_focus_on_a_sibling_never_authorizes_another_control() -> None:
    state = _own_tree(
        '<node class="android.widget.ScrollView">'
        '<node class="android.view.View" clickable="true"><node text="Preparar WoW"/></node>'
        '<node class="android.view.View" clickable="true" focused="true"><node text="Detalles"/></node>'
        '</node>'
    )
    control = runner._labelled_controls(state, (runner.PREPARE_LABEL,), "Button")[0]
    focused = runner._focused(state)[0]
    assert not runner._focus_matches(control, focused, runner._parents(state))


def test_hid_allowlist_excludes_android_back_key(monkeypatch: pytest.MonkeyPatch) -> None:
    history: list[tuple[int, int]] = []
    keyboard = SimpleNamespace(key=lambda modifier, keycode: history.append((modifier, keycode)))
    monkeypatch.setattr(runner.time, "sleep", lambda _: None)
    deadline = time.monotonic() + 2
    for keycode in (43, 44, 40, 41):
        runner._send(keyboard, keycode, deadline)
    assert history == [(0, 43), (0, 44), (0, 40), (0, 41)]
    with pytest.raises(runner.SmokeError, match="unallowlisted-HID-key"):
        runner._send(keyboard, 4, deadline)


def test_fixed_hash_uses_one_quoted_exec_out_command_token() -> None:
    path = "/storage/emulated/0/Android/data/pkg/CustomGames/WoW Forever/WowB-ARM64.exe"
    digest = "a" * 64
    adb = FakeAdb(exec_out={("sh", "-c", f"sha256sum {shlex.quote(path)}"): (digest + "  " + path).encode()})
    assert runner._fixed_hash(adb, path, time.monotonic() + 2) == digest
    assert adb.exec_calls == [("sh", "-c", f"sha256sum {shlex.quote(path)}")]


def test_shared_preferences_accept_only_forever_profile_with_diagnostics_off() -> None:
    xml = (
        '<map><string name="profile_id">classic_forever</string>'
        '<boolean name="memory_patch_enabled" value="false"/>'
        '<boolean name="code_capture_enabled" value="false"/></map>'
    ).encode()
    adb = FakeAdb(exec_out={("run-as", runner.PACKAGE, "cat", runner.PREFS_PATH): xml})
    assert runner._shared_preferences(adb, time.monotonic() + 2)["profileId"] == "classic_forever"


def test_prepared_event_must_be_new_and_timestamped_for_attempt() -> None:
    path = "files/functional-poc/run-2000.jsonl"
    event = {"event": "prepared", "timestampMs": 2000, "profileId": "classic_forever", "memoryPatchEnabled": False, "codeCaptureEnabled": False}
    adb = FakeAdb(
        shell={
            ("run-as", runner.PACKAGE, "find", "files/functional-poc", "-maxdepth", "1", "-type", "f", "-name", "run-*.jsonl", "-print"): (path + "\n").encode(),
            ("run-as", runner.PACKAGE, "head", "-c", str(runner.MAX_LOG_BYTES), path): (json.dumps(event) + "\n").encode(),
        }
    )
    assert runner._prepared_event(adb, time.monotonic() + 2, {path}, 1000)["found"] is False
    assert runner._prepared_event(adb, time.monotonic() + 2, set(), 1000)["found"] is True
    # Android time can precede the host wall clock. The new log identity and
    # its internally ordered Android timestamps remain the authoritative proof.
    assert runner._prepared_event(adb, time.monotonic() + 2, set(), 3000)["found"] is True


def test_stage_deadline_cancels_without_extending_overall_budget() -> None:
    with pytest.raises(runner.SmokeError, match="stage-cancelled"):
        runner._stage_deadline(time.monotonic() - 1, 30, "ui")


def test_runner_labels_mirror_home_strings() -> None:
    # The device smoke looks for exact home strings: the constants must match
    # the Activity sources, closing the pre-existing READY/CONSENT drift.
    activity = (ROOT / "scripts/arm64-prototype/app/overlay/ForeverFunctionalPocActivity.kt").read_text()
    assert f'status = "{runner.READY_LABEL}"' in activity
    assert f'"{runner.CONSENT_LABEL}"' in activity
    assert f'Text("{runner.PREPARE_LABEL}")' in activity
    assert f'Text("{runner.LAUNCH_LABEL}")' in activity


def test_runner_has_no_postlaunch_screenshot_or_hid_back_path() -> None:
    source = RUNNER.read_text(encoding="utf-8")
    assert source.count('_screenshot(adb, helper, run_dir') == 2
    assert "_send(keyboard, 4," not in source
    assert "with helper.UsbKeyboard(SERIAL) as opened" in source
    assert "if launchRequested and sessionIdentityProven" in source
