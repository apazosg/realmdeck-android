"""Offline HOST-side tests for bounded, prioritized process sampling."""
import importlib.util
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest


@pytest.fixture
def trial():
    path = Path(__file__).parents[1] / "scripts/diagnostics/usb_forever_trial.py"
    spec = importlib.util.spec_from_file_location("usb_probe_sampling_fixture", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def row(module, pid, name):
    return (module.UID, pid, 77, name)


def test_priority_is_graphics_prefix_then_runtime_then_app(trial):
    rows = [
        row(trial, 1, "com.wowforever.arm64probe"),
        row(trial, 2, "wine"),
        row(trial, 3, "GraphicsProbe-ARM64.e"),
        row(trial, 4, "EXPLORER.EXE"),
        row(trial, 5, "GraphicsProber"),
        row(trial, 6, "box64"),
        row(trial, 7, "app.gamenative"),
    ]

    selected = trial.prioritize_processes(rows)

    assert [item[1] for item in selected] == [3, 5, 2, 4, 6, 1, 7]
    assert trial.process_priority(row(trial, 8, "graphicsprobe-helper")) == 0
    assert trial.process_priority(row(trial, 9, "not-graphicsprobe")) == 2


def test_snapshot_limits_calls_and_keeps_identity_checks_in_order(trial, tmp_path):
    rows = [
        row(trial, 1, "com.wowforever.arm64probe"),
        row(trial, 2, "wine"),
        row(trial, 3, "GraphicsProbe-ARM64.e"),
        row(trial, 4, "explorer.exe"),
        row(trial, 5, "app.one"),
        row(trial, 6, "box64"),
        row(trial, 7, "app.two"),
        row(trial, 8, "GraphicsProbe-truncated"),
    ]
    calls = []

    class FakeAdb:
        deadline = None

        def shell(self, *args, **kwargs):
            calls.append(("ps", args, kwargs["timeout"]))
            raw = "\n".join("%d %d %d %s" % item for item in rows) + "\n"
            return SimpleNamespace(returncode=0, stdout=raw.encode(), stderr=b"")

        def exec_out(self, *args, **kwargs):
            path = args[-1]
            kind = "maps" if path.endswith("/maps") else "cmdline"
            calls.append((kind, int(path.split("/")[-2]), kwargs["timeout"]))
            output = b"map\n" if kind == "maps" else b"/usr/bin/wine\0secret-arg\0"
            return SimpleNamespace(returncode=0, stdout=output, stderr=b"")

    stat_calls = []
    uid_calls = []

    def stat(_adb, pid, *, reserve=0):
        stat_calls.append((pid, reserve))
        return (pid, 1234, 77)

    def uid(_adb, pid, *, reserve=0):
        uid_calls.append((pid, reserve))
        return True, [trial.UID] * 4

    trial.proc_stat = stat
    trial.proc_uid = uid
    journal = SimpleNamespace(state=lambda *args, **kwargs: None)
    seen = {}

    assert trial.process_snapshot(FakeAdb(), tmp_path, journal, 0, seen) == 6
    assert list(seen) == [3, 8, 2, 4, 6, 1]
    assert len({item[1] for item in calls if item[0] != "ps"}) == 6
    assert len([item for item in calls if item[0] == "maps"]) == 6
    assert len([item for item in calls if item[0] == "cmdline"]) == 6
    assert [item[0] for item in calls[1:5]] == ["maps", "cmdline", "maps", "cmdline"]
    assert [pid for pid, _reserve in stat_calls] == [3, 3, 8, 8, 2, 2, 4, 4, 6, 6, 1, 1]
    assert all(reserve == trial.FINAL_CAPTURE_RESERVE_SECONDS for _pid, reserve in uid_calls)
    assert all("secret-arg" not in path.read_text() for path in tmp_path.glob("*.jsonl"))


def test_uid_read_failure_rejects_even_well_formed_output(trial):
    class FailingAdb:
        deadline = None

        def exec_out(self, *args, **kwargs):
            return SimpleNamespace(returncode=1, stdout=b"Uid:\t10330\t10330\t10330\t10330\n", stderr=b"")

    assert trial.proc_uid(FailingAdb(), 321) == (False, [])


def test_sampling_timeout_preserves_final_reserve_and_early_polling(trial, monkeypatch):
    clock = [10.0]
    monkeypatch.setattr(trial.time, "monotonic", lambda: clock[0])
    adb = SimpleNamespace(deadline=40.0)

    assert trial.sampling_timeout(adb, 15) == 5.0
    clock[0] = 15.0
    assert trial.sampling_timeout(adb, 15) is None
    assert trial.sample_interval(10.0, now=19.99) == pytest.approx(0.25)
    assert trial.sample_interval(10.0, now=20.0) == pytest.approx(3.0)


def test_snapshot_does_not_start_when_only_final_reserve_remains(trial, tmp_path):
    calls = []

    class FakeAdb:
        deadline = 25.0

        def shell(self, *args, **kwargs):
            calls.append(args)
            raise AssertionError("ps must not start inside the final reserve")

    journal = SimpleNamespace(state=lambda *args, **kwargs: None)
    assert trial.process_snapshot(FakeAdb(), tmp_path, journal, 0, {}) == 0
    assert calls == []


def test_version_49_is_registered_without_relaxing_the_120_second_cap(trial, tmp_path, monkeypatch):
    observed = {}

    def fake_run(args):
        observed["args"] = args
        return 0

    monkeypatch.setattr(trial, "run_trial", fake_run)

    assert trial.main([
        "--output", str(tmp_path), "--version-code", "49",
        "--version-name", "0.2.25-automated", "--seconds", "120",
    ]) == 0
    assert observed["args"].version_code == 49


def test_version_55_policy_collects_all_probes_and_rejects_over_budget_before_adb(trial, tmp_path, monkeypatch):
    policy = trial.trial_policy(55, "0.2.31-automated")
    assert policy.collect_graphics and policy.collect_panvk_identity and policy.collect_probe_identity
    assert policy.max_seconds == 120
    monkeypatch.setattr(trial, "run_trial", lambda args: (_ for _ in ()).throw(AssertionError("ADB path reached")))
    with pytest.raises(SystemExit):
        trial.main(["--output", str(tmp_path), "--version-code", "55", "--version-name", "0.2.31-automated", "--seconds", "121"])
