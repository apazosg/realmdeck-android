"""Offline tests for the bounded post-activity UI ownership gate."""
import importlib.util
from itertools import repeat
import json
from pathlib import Path
import sys
from types import SimpleNamespace

import pytest


@pytest.fixture
def trial():
    path = Path(__file__).parents[1] / "scripts/diagnostics/usb_forever_trial.py"
    spec = importlib.util.spec_from_file_location("usb_start_readiness_fixture", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class FakeClock:
    def __init__(self, now=100.0):
        self.now = now

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class FakeAdb:
    def __init__(self, deadline=200.0):
        self.deadline = deadline
        self.calls = []

    def shell(self, *args, **kwargs):
        self.calls.append(("shell", args))
        raise AssertionError("UI readiness must not send shell input")


def ui_state(module, package):
    return module.UiState(module.ET.Element("node", {"package": package}), {})


def journal_events():
    events = []
    return events, SimpleNamespace(state=lambda *args, **kwargs: events.append((args, kwargs)))


def test_late_own_ui_is_observed_without_input_and_with_three_dump_cap(trial, monkeypatch):
    clock = FakeClock()
    adb = FakeAdb()
    events, journal = journal_events()
    states = iter([
        ui_state(trial, "foreign.example"),
        ui_state(trial, "android.systemui"),
        ui_state(trial, trial.PACKAGE),
    ])
    dump_deadlines = []
    cleanup_deadlines = []

    def dump(_adb, *, deadline, cleanup_deadline):
        dump_deadlines.append(deadline)
        cleanup_deadlines.append(cleanup_deadline)
        return next(states)

    monkeypatch.setattr(trial, "dump_ui", dump)
    result = trial.wait_for_own_ui(
        adb, journal=journal, clock=clock.monotonic, sleeper=clock.sleep,
    )

    assert result.state is not None and result.reason == "own-top-ready"
    assert result.dump_count == 3 and 4_000 <= result.elapsed_ms <= 8_000
    assert dump_deadlines == [103.0, 105.0, 107.0]
    assert cleanup_deadlines == [103.5, 105.5, 107.5]
    assert adb.calls == []
    assert events[-1][0] == ("ui-readiness",)
    assert events[-1][1] == {
        "status": "ready", "reason": "own-top-ready", "elapsed_ms": result.elapsed_ms,
        "dump_count": 3,
    }
    assert "foreign.example" not in json.dumps(events)
    assert "android.systemui" not in json.dumps(events)


def test_permanent_foreign_ui_returns_fixed_reason_and_no_foreign_text(trial, monkeypatch):
    clock = FakeClock()
    adb = FakeAdb()
    events, journal = journal_events()
    monkeypatch.setattr(
        trial, "dump_ui", lambda _adb, *, deadline, cleanup_deadline: ui_state(
            trial, "foreign.permanent"),
    )

    result = trial.wait_for_own_ui(
        adb, journal=journal, clock=clock.monotonic, sleeper=clock.sleep,
    )

    assert result.state is None
    assert result.reason == "foreign-top"
    assert result.dump_count == trial.UI_READINESS_MAX_DUMPS
    assert result.elapsed_ms <= 8_000
    assert "foreign.permanent" not in json.dumps(events)


def test_dump_failures_are_bounded_and_persist_only_fixed_reason(trial, monkeypatch):
    clock = FakeClock()
    adb = FakeAdb()
    events, journal = journal_events()

    def failed(_adb, *, deadline, cleanup_deadline):
        raise trial.TrialError("XML contains a foreign package that must not persist")

    monkeypatch.setattr(trial, "dump_ui", failed)
    result = trial.wait_for_own_ui(
        adb, journal=journal, clock=clock.monotonic, sleeper=clock.sleep,
    )

    assert result.state is None
    assert result.reason == "ui-dump-failed"
    assert result.dump_count == trial.UI_READINESS_MAX_DUMPS
    assert "foreign package" not in json.dumps(events)
    assert "XML" not in json.dumps(events)


@pytest.mark.parametrize(
    ("deadline", "reason"),
    [(100.0, "global-deadline"), (110.0, "final-reserve")],
)
def test_global_deadline_and_final_reserve_prevent_any_readiness_dump(trial, monkeypatch, deadline, reason):
    clock = FakeClock()
    adb = FakeAdb(deadline=deadline)
    calls = []

    def dump(_adb, *, deadline, cleanup_deadline):
        calls.append(deadline)
        raise AssertionError("readiness dump must not start after the deadline gate")

    monkeypatch.setattr(trial, "dump_ui", dump)
    result = trial.wait_for_own_ui(
        adb, clock=clock.monotonic, sleeper=clock.sleep,
    )

    assert result.state is None and result.reason == reason and result.dump_count == 0
    assert calls == []


def test_local_deadline_after_a_slow_dump_does_not_retry_or_send_input(trial, monkeypatch):
    clock = FakeClock()
    adb = FakeAdb()
    calls = []

    def slow_foreign(_adb, *, deadline, cleanup_deadline):
        calls.append(deadline)
        clock.now = 109.0
        return ui_state(trial, "foreign.slow")

    monkeypatch.setattr(trial, "dump_ui", slow_foreign)
    result = trial.wait_for_own_ui(
        adb, clock=clock.monotonic, sleeper=clock.sleep,
    )

    assert result.state is None and result.reason == "local-deadline"
    assert result.dump_count == 1 and calls == [103.0] and adb.calls == []


class DumpAdb:
    deadline = None

    def __init__(self, *, rm_codes=None, clock=None):
        self.rm_codes = iter(rm_codes) if rm_codes is not None else repeat(0)
        self.clock = clock
        self.calls = []

    def shell(self, *args, **kwargs):
        self.calls.append(("shell", args, kwargs["timeout"]))
        return SimpleNamespace(returncode=next(self.rm_codes), stdout=b"", stderr=b"")

    def exec_out(self, *args, **kwargs):
        self.calls.append(("exec_out", args, kwargs["timeout"]))
        if args[0] == "uiautomator":
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")
        return SimpleNamespace(
            returncode=0,
            stdout=b'<node package="com.wowforever.arm64probe" />',
            stderr=b"",
        )


def dump_paths(adb):
    return [args[3] for kind, args, _timeout in adb.calls
            if kind == "exec_out" and args[0] == "uiautomator"]


def test_dump_ui_uses_unique_exact_paths_and_cleans_before_accepting(trial):
    adb = DumpAdb()

    first = trial.dump_ui(adb)
    second = trial.dump_ui(adb)

    assert first.top_is_own() and second.top_is_own()
    paths = dump_paths(adb)
    assert len(paths) == 2 and paths[0] != paths[1]
    assert all(path.startswith(trial.UI_DUMP_PREFIX) and path.endswith(trial.UI_DUMP_SUFFIX)
               for path in paths)
    for path in paths:
        path_calls = [(kind, args) for kind, args, _timeout in adb.calls
                      if args[-1] == path]
        assert path_calls == [
            ("shell", ("rm", "-f", path)),
            ("exec_out", ("uiautomator", "dump", "--compressed", path)),
            ("exec_out", ("cat", path)),
            ("shell", ("rm", "-f", path)),
        ]
    assert all(timeout == trial.UI_COMMAND_TIMEOUT
               for kind, _args, timeout in adb.calls if kind == "exec_out")


def test_dump_ui_clear_failure_never_generates_and_cleanup_is_exact(trial):
    adb = DumpAdb(rm_codes=(1, 0))

    with pytest.raises(trial.TrialError, match="path clear failed"):
        trial.dump_ui(adb)

    assert not [call for call in adb.calls if call[0] == "exec_out"]
    paths = [args[-1] for _kind, args, _timeout in adb.calls]
    assert len(paths) == 2 and paths[0] == paths[1]
    assert paths[0].startswith(trial.UI_DUMP_PREFIX)


def test_dump_ui_cleanup_failure_rejects_a_valid_ui(trial):
    adb = DumpAdb(rm_codes=(0, 1))

    with pytest.raises(trial.TrialError, match="cleanup failed"):
        trial.dump_ui(adb)

    assert dump_paths(adb)


def test_dump_ui_rejects_late_success_and_attempts_bounded_cleanup(trial, monkeypatch):
    clock = FakeClock()
    adb = DumpAdb(clock=clock)
    original_exec_out = adb.exec_out

    def late_exec_out(*args, **kwargs):
        result = original_exec_out(*args, **kwargs)
        if args[0] == "uiautomator":
            clock.now = 103.1
        return result

    monkeypatch.setattr(trial.time, "monotonic", clock.monotonic)
    monkeypatch.setattr(adb, "exec_out", late_exec_out)
    with pytest.raises(trial.TrialError, match="returned after deadline"):
        trial.dump_ui(adb, deadline=103.0, cleanup_deadline=103.5)

    assert dump_paths(adb)
    cleanup = [call for call in adb.calls if call[0] == "shell"][-1]
    assert cleanup[1][0:2] == ("rm", "-f") and cleanup[2] == pytest.approx(0.4)


def test_readiness_cleanup_failure_is_fixed_and_does_not_send_input(trial, monkeypatch):
    clock = FakeClock()
    adb = FakeAdb()
    monkeypatch.setattr(
        trial, "dump_ui", lambda *_args, **_kwargs: (_ for _ in ()).throw(
            trial.TrialError("UI dump cleanup failed")),
    )

    result = trial.wait_for_own_ui(
        adb, clock=clock.monotonic, sleeper=clock.sleep,
    )

    assert result.reason == "ui-dump-cleanup-failed"
    assert result.dump_count == 1
    assert adb.calls == []


def test_single_prepare_intent_has_no_later_activity_launch(trial):
    source = Path(trial.__file__).read_text(encoding="utf-8")
    assert source.count('"am", "start"') == 1
    assert source.index('"am", "start"') < source.index('"prepare-intent"')
    assert source.count('journal.state("prepare-intent"') == 1
