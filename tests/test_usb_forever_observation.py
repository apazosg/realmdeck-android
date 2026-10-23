"""Offline regression tests for bounded host observability."""
import importlib.util
import json
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest


@pytest.fixture
def trial():
    path = Path(__file__).parents[1] / "scripts/diagnostics/usb_forever_trial.py"
    spec = importlib.util.spec_from_file_location("usb_observation_fixture", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def ps_row(module, pid, name, uid=None, ppid=77):
    return f"{module.UID if uid is None else uid} {pid} {ppid} {name}\n"


class PsAdb:
    deadline = None

    def __init__(self, raw, *, maps=b"map\n", cmdline=b"/usr/bin/wine\0"):
        self.raw = raw
        self.maps = maps
        self.cmdline = cmdline
        self.calls = []

    def shell(self, *args, **kwargs):
        self.calls.append(("shell", args))
        return SimpleNamespace(returncode=0, stdout=self.raw, stderr=b"")

    def exec_out(self, *args, **kwargs):
        path = args[-1]
        kind = "maps" if path.endswith("/maps") else "cmdline"
        self.calls.append((kind, path))
        output = self.maps if kind == "maps" else self.cmdline
        return SimpleNamespace(returncode=0, stdout=output, stderr=b"")


def journal():
    return SimpleNamespace(state=lambda *args, **kwargs: None)


def own_identity(monkeypatch, trial, *, before=(1, 77), after=None):
    after = before if after is None else after
    monkeypatch.setattr(trial, "proc_stat", lambda _adb, pid, **_: (pid, before[0], before[1]))
    monkeypatch.setattr(trial, "proc_uid", lambda _adb, _pid, **_: (True, [trial.UID] * 4))
    if after != before:
        # The first call is the before observation; subsequent calls are reused
        # for the after observation and any later candidate.
        calls = {"count": 0}

        def stat(_adb, pid, **_kwargs):
            calls["count"] += 1
            return (pid, before[0], before[1]) if calls["count"] == 1 else (pid, after[0], after[1])

        monkeypatch.setattr(trial, "proc_stat", stat)


def test_exhausted_old_candidates_are_filtered_before_six_selection(trial, tmp_path, monkeypatch):
    rows = "UID PID PPID NAME\n" + "".join(ps_row(trial, pid, "app.old") for pid in range(1, 7)) + ps_row(trial, 99, "GraphicsProbeRe")
    adb = PsAdb(rows.encode())
    own_identity(monkeypatch, trial)

    assert trial.process_snapshot(adb, tmp_path, journal(), 0, {pid: 3 for pid in range(1, 7)}) == 1
    assert [path for kind, path in adb.calls if kind in {"maps", "cmdline"}] == [
        "/proc/99/maps", "/proc/99/cmdline"
    ]
    observations = [json.loads(line) for line in (tmp_path / "process-observations.jsonl").read_text().splitlines()]
    assert sum(item.get("reason") == "seen-quota" for item in observations) == 6
    assert any(item.get("pid") == 99 and item.get("selection_index") == 0 for item in observations)


def test_six_exhausted_high_priority_candidates_do_not_starve_fresh_low_priority(trial, tmp_path, monkeypatch):
    rows = "UID PID PPID NAME\n" + "".join(
        ps_row(trial, pid, "GraphicsProbeRe") for pid in range(1, 7)
    ) + ps_row(trial, 99, "wine")
    adb = PsAdb(rows.encode())
    own_identity(monkeypatch, trial)

    assert trial.process_snapshot(adb, tmp_path, journal(), 0, {pid: 3 for pid in range(1, 7)}) == 1
    assert "/proc/99/maps" in [path for kind, path in adb.calls if kind == "maps"]
    observations = [json.loads(line) for line in (tmp_path / "process-observations.jsonl").read_text().splitlines()]
    assert any(item.get("pid") == 99 and item.get("selected") is True for item in observations)


def test_fresh_wine_candidate_precedes_six_partially_sampled_runtime_pids(trial, tmp_path, monkeypatch):
    # Reproduce sample20: actual PE comm was GraphicsProbeRe, PS NAME was wine.
    rows = "UID PID PPID NAME\n" + "".join(
        ps_row(trial, pid, "wine") for pid in range(1, 8)
    )
    adb = PsAdb(rows.encode())
    own_identity(monkeypatch, trial)
    seen = {pid: 2 for pid in range(1, 7)}
    assert trial.process_snapshot(adb, tmp_path, journal(), 0, seen) == 6
    paths = [path for kind, path in adb.calls if kind == "maps"]
    assert paths[0] == "/proc/7/maps" and len(paths) == 6
    observations = [json.loads(line) for line in (tmp_path / "process-observations.jsonl").read_text().splitlines()]
    assert any(item.get("pid") == 7 and item.get("selection_index") == 0 for item in observations)
    assert any(item.get("pid") == 6 and item.get("reason") == "selection-cap" for item in observations)


def test_named_probe_hint_still_precedes_fresh_wine_candidates(trial, tmp_path, monkeypatch):
    rows = "UID PID PPID NAME\n" + "".join(
        ps_row(trial, pid, "wine") for pid in range(1, 8)
    ) + ps_row(trial, 99, "GraphicsProbeRe")
    adb = PsAdb(rows.encode())
    own_identity(monkeypatch, trial)
    assert trial.process_snapshot(adb, tmp_path, journal(), 0, {99: 2}) == 6
    assert [path for kind, path in adb.calls if kind == "maps"][0] == "/proc/99/maps"


def test_name_truncation_and_graphics_probe_prefix_are_observable(trial, tmp_path, monkeypatch):
    long_name = "GraphicsProbeRe" + "x" * 140
    raw = ("UID PID PPID NAME\n" + ps_row(trial, 321, long_name)).encode()
    monkeypatch.setattr(trial, "proc_stat", lambda _adb, pid, **_: (pid, 10, 77))
    monkeypatch.setattr(trial, "proc_uid", lambda _adb, _pid, **_: (True, [trial.UID] * 4))
    adb = PsAdb(raw)

    assert trial.process_snapshot(adb, tmp_path, journal(), 0, {}) == 1
    parsed = trial.parse_ps(raw.decode())
    assert parsed[0][1] == 321 and len(parsed[0][3]) == 128
    observations = [json.loads(line) for line in (tmp_path / "process-observations.jsonl").read_text().splitlines()]
    selected = next(item for item in observations if item.get("selection_index") == 0)
    assert selected["priority"] == 0 and selected["name_truncated"] is True
    snapshot = json.loads((tmp_path / "process-snapshots.jsonl").read_text())
    map_path = tmp_path / snapshot["map_file"]
    assert snapshot["map_file"] == "maps-00-321-1.txt"
    assert snapshot["maps_bytes"] == map_path.stat().st_size == len(map_path.read_bytes())
    assert snapshot["maps_sha256"] == trial.sha256(map_path.read_bytes())


def test_foreign_uid_is_excluded_from_parse_and_raw_persistence(trial, tmp_path):
    raw = ("UID PID PPID NAME\n" + ps_row(trial, 100, "foreign", uid=1000) + ps_row(trial, 200, "wine")).encode()
    assert [row[1] for row in trial.parse_ps(raw.decode())] == [200]
    saved = trial.save_ps_capture(tmp_path, 0, raw, start="start", end="end", parameters=("ps",))
    assert saved is not None
    persisted = (tmp_path / "ps-raw-00.txt").read_bytes()
    assert b"1000" not in persisted and b"foreign" not in persisted
    assert b"200 77 wine" in persisted
    metadata = (tmp_path / "ps-observations.jsonl").read_text()
    assert "1000" not in metadata and '"own_uid": 10330' in metadata


def test_pid_reuse_before_after_is_rejected_without_map_persistence(trial, tmp_path, monkeypatch):
    adb = PsAdb(("UID PID PPID NAME\n" + ps_row(trial, 200, "wine")).encode())
    calls = {"stat": 0}

    def stat(_adb, pid, **_kwargs):
        calls["stat"] += 1
        return (pid, 10 if calls["stat"] == 1 else 11, 77)

    monkeypatch.setattr(trial, "proc_stat", stat)
    monkeypatch.setattr(trial, "proc_uid", lambda _adb, _pid, **_: (True, [trial.UID] * 4))
    assert trial.process_snapshot(adb, tmp_path, journal(), 0, {}) == 0
    assert not list(tmp_path.glob("maps-*.txt"))
    observations = [json.loads(line) for line in (tmp_path / "process-observations.jsonl").read_text().splitlines()]
    identity = next(item for item in observations if item.get("reason") == "identity-changed")
    assert identity["stat_before"]["start_ticks"] == 10
    assert identity["stat_after"]["start_ticks"] == 11
    assert identity["uid_before"] == identity["uid_after"] == [trial.UID] * 4


@pytest.mark.parametrize("maps, returncode, reason", [
    (b"", 1, "maps-error"),
    (b"m" * (2 * 1024 * 1024 + 1), 0, "maps-truncated"),
])
def test_maps_error_and_oversize_are_bounded_and_recorded(trial, tmp_path, monkeypatch, maps, returncode, reason):
    adb = PsAdb(("UID PID PPID NAME\n" + ps_row(trial, 200, "wine")).encode(), maps=maps)
    if returncode:
        original = adb.exec_out

        def failed(*args, **kwargs):
            result = original(*args, **kwargs)
            return SimpleNamespace(returncode=1, stdout=result.stdout, stderr=b"permission denied")

        monkeypatch.setattr(adb, "exec_out", failed)
    monkeypatch.setattr(trial, "proc_stat", lambda _adb, pid, **_: (pid, 10, 77))
    monkeypatch.setattr(trial, "proc_uid", lambda _adb, _pid, **_: (True, [trial.UID] * 4))
    assert trial.process_snapshot(adb, tmp_path, journal(), 0, {}) == 0
    assert any(json.loads(line).get("reason") == reason
               for line in (tmp_path / "process-observations.jsonl").read_text().splitlines())
    assert not list(tmp_path.glob("maps-*.txt"))


def test_cmdline_truncation_has_no_inline_retry_but_later_pid_observation_is_allowed(trial, tmp_path, monkeypatch):
    raw = ("UID PID PPID NAME\n" + ps_row(trial, 200, "wine")).encode()
    adb = PsAdb(raw, cmdline=b"x" * (trial.MAX_CMDLINE_BYTES + 1))
    starts = iter((10, 20))
    monkeypatch.setattr(trial, "proc_stat", lambda _adb, pid, **_: (pid, next(starts), 77))
    monkeypatch.setattr(trial, "proc_uid", lambda _adb, _pid, **_: (True, [trial.UID] * 4))
    assert trial.process_snapshot(adb, tmp_path, journal(), 0, {}) == 0
    first_cmdline_calls = len([call for call in adb.calls if call[0] == "cmdline"])
    assert trial.process_snapshot(adb, tmp_path, journal(), 1, {}) == 0
    assert len([call for call in adb.calls if call[0] == "cmdline"]) == first_cmdline_calls + 1 == 2
    reasons = [json.loads(line).get("reason")
               for line in (tmp_path / "process-observations.jsonl").read_text().splitlines()]
    assert reasons.count("cmdline-truncated") == 2


def test_failed_uid_read_does_not_persist_stat_or_foreign_uid_values(trial, tmp_path, monkeypatch):
    raw = ("UID PID PPID NAME\n" + ps_row(trial, 200, "wine")).encode()
    adb = PsAdb(raw)
    monkeypatch.setattr(trial, "proc_stat", lambda _adb, pid, **_: (pid, 10, 77))
    monkeypatch.setattr(trial, "proc_uid", lambda _adb, _pid, **_: (False, [1000] * 4))
    assert trial.process_snapshot(adb, tmp_path, journal(), 0, {}) == 0
    raw_observations = (tmp_path / "process-observations.jsonl").read_text()
    assert "1000" not in raw_observations
    observation = json.loads(raw_observations.splitlines()[-1])
    assert observation["uid_before"] is None
    assert "stat_before" not in observation
    assert observation["stat_before_uid_verified"] is False


def test_raw_ps_budget_truncation_and_final_reserve(trial, tmp_path):
    line = ps_row(trial, 200, "wine").encode()
    raw = b"UID PID PPID NAME\n" + line * ((trial.MAX_PS_SAMPLE_BYTES // len(line)) + 20)
    saved = trial.save_ps_capture(tmp_path, 0, raw, start="start", end="end", parameters=("ps", "-A"))
    assert saved is not None and saved[2] is True
    assert (tmp_path / "ps-raw-00.txt").stat().st_size <= trial.MAX_PS_SAMPLE_BYTES
    metadata = json.loads((tmp_path / "ps-observations.jsonl").read_text().splitlines()[0])
    assert metadata["truncated"] is True and metadata["start"] == "start" and metadata["end"] == "end"

    calls = []

    class ReserveAdb:
        deadline = 25.0

        def shell(self, *args, **kwargs):
            calls.append(args)
            raise AssertionError("ps must not run in final reserve")

    assert trial.process_snapshot(ReserveAdb(), tmp_path, journal(), 1, {}) == 0
    assert calls == []


def test_ps_header_is_exactly_validated_and_all_raw_budgets_are_enforced(trial, tmp_path):
    header = b"UID PID PPID NAME\r\n"
    raw = header + ps_row(trial, 200, "wine").encode()
    filtered, status = trial.filter_own_ps_details(raw)
    assert status == "header-valid" and filtered.startswith(header)

    malformed = b"UID BAD PPID NAME\nforeign metadata\n" + ps_row(trial, 201, "wine").encode()
    filtered, status = trial.filter_own_ps_details(malformed)
    assert status == "header-invalid" and b"foreign metadata" not in filtered and b"UID BAD" not in filtered

    oversized = b"UID" + (b" " * trial.MAX_PS_HEADER_BYTES) + b"PID PPID NAME\n" + ps_row(trial, 202, "wine").encode()
    filtered, status = trial.filter_own_ps_details(oversized)
    assert status == "header-oversized" and b"UID" not in filtered and b"202 77 wine" in filtered

    class NoPsAdb:
        deadline = None

        def shell(self, *args, **kwargs):
            raise AssertionError("sample cap must not invoke ps")

    assert trial.process_snapshot(NoPsAdb(), tmp_path, journal(), trial.MAX_PS_SAMPLES, {}) == 0
    cap_record = json.loads((tmp_path / "ps-observations.jsonl").read_text().splitlines()[-1])
    assert cap_record["status"] == "raw-ps-sample-cap"

    for index in range(trial.MAX_PS_SAMPLES):
        trial.write_bytes(tmp_path / f"ps-raw-{index:02d}.txt", b"x" * trial.MAX_PS_SAMPLE_BYTES)
    assert trial.save_ps_capture(tmp_path, 0, raw, start="start", end="end", parameters=("ps",)) is None
    assert sum(path.stat().st_size for path in tmp_path.glob("ps-raw-*.txt")) == trial.MAX_PS_TOTAL_BYTES


def test_screenshot_guard_reasons_and_foreign_image_is_never_saved(trial, tmp_path, monkeypatch):
    root = trial.ET.Element("node", {"package": "foreign.package"})
    foreign = trial.UiState(root, {})
    events = []
    log = SimpleNamespace(state=lambda *args, **kwargs: events.append(kwargs))
    calls = []

    class FakeAdb:
        def exec_out(self, *args, **kwargs):
            calls.append(args)
            return SimpleNamespace(returncode=0, stdout=b"\x89PNG\r\n\x1a\n", stderr=b"")

    monkeypatch.setattr(trial, "dump_ui", lambda _adb: foreign)
    assert trial.final_screenshot(FakeAdb(), tmp_path, trial.CurrentRun("RUN-x", {}), log) is False
    assert calls == [] and not (tmp_path / "final-screenshot.png").exists()
    assert events[-1]["reason"] == "own-focus-guard-failed" and events[-1]["screenshot"] is False


@pytest.mark.parametrize(("result", "reason"), [
    (SimpleNamespace(returncode=1, stdout=b"", stderr=b"ignored"), "screencap-error"),
    (SimpleNamespace(returncode=0, stdout=b"not-png", stderr=b""), "screencap-not-png"),
    (SimpleNamespace(returncode=0, stdout=b"\x89PNG\r\n\x1a\n" + b"x" * (16 * 1024 * 1024), stderr=b""), "screencap-oversized"),
])
def test_screenshot_nonvisual_rejection_branches_do_not_save(trial, tmp_path, monkeypatch, result, reason):
    root = trial.ET.Element("node", {"package": trial.PACKAGE})
    own = trial.UiState(root, {})
    events = []
    monkeypatch.setattr(trial, "dump_ui", lambda _adb: own)
    monkeypatch.setattr(trial, "verify_top", lambda _state: None)
    monkeypatch.setattr(trial, "write_bytes", lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("must not save")))
    log = SimpleNamespace(state=lambda *args, **kwargs: events.append(kwargs))
    adb = SimpleNamespace(exec_out=lambda *args, **kwargs: result)
    assert trial.final_screenshot(adb, tmp_path, trial.CurrentRun("RUN-x", {}), log) is False
    assert events[-1]["reason"] == reason and not (tmp_path / "final-screenshot.png").exists()


def test_screenshot_dump_failure_and_mixed_hierarchy_are_fixed_non_content_reasons(trial, tmp_path, monkeypatch):
    events = []
    log = SimpleNamespace(state=lambda *args, **kwargs: events.append(kwargs))
    adb = SimpleNamespace(exec_out=lambda *args, **kwargs: (_ for _ in ()).throw(AssertionError("no capture")))
    monkeypatch.setattr(trial, "dump_ui", lambda _adb: (_ for _ in ()).throw(trial.TrialError("UI XML malformed")))
    assert trial.final_screenshot(adb, tmp_path, trial.CurrentRun("RUN-x", {}), log) is False
    assert events[-1]["reason"] == "ui-dump-failed"

    root = trial.ET.Element("node", {"package": trial.PACKAGE})
    trial.ET.SubElement(root, "node", {"package": "android.systemui"})
    mixed = trial.UiState(root, {})
    monkeypatch.setattr(trial, "dump_ui", lambda _adb: mixed)
    assert trial.final_screenshot(adb, tmp_path, trial.CurrentRun("RUN-x", {}), log) is False
    assert events[-1]["reason"] == "own-hierarchy-guard-failed"


def test_screenshot_after_capture_hierarchy_race_never_saves(trial, tmp_path, monkeypatch):
    own_root = trial.ET.Element("node", {"package": trial.PACKAGE})
    foreign_root = trial.ET.Element("node", {"package": trial.PACKAGE})
    trial.ET.SubElement(foreign_root, "node", {"package": "android.systemui"})
    states = iter([trial.UiState(own_root, {}), trial.UiState(foreign_root, {})])
    events = []
    monkeypatch.setattr(trial, "dump_ui", lambda _adb: next(states))
    adb = SimpleNamespace(exec_out=lambda *args, **kwargs: SimpleNamespace(
        returncode=0, stdout=b"\x89PNG\r\n\x1a\n", stderr=b""))
    log = SimpleNamespace(state=lambda *args, **kwargs: events.append(kwargs))
    assert trial.final_screenshot(adb, tmp_path, trial.CurrentRun("RUN-x", {}), log) is False
    assert events[-1]["reason"] == "own-hierarchy-changed"
    assert not (tmp_path / "final-screenshot.png").exists()


def test_screenshot_after_capture_dump_failure_never_saves(trial, tmp_path, monkeypatch):
    root = trial.ET.Element("node", {"package": trial.PACKAGE})
    states = iter([trial.UiState(root, {}), trial.TrialError("UI XML malformed")])

    def dump(_adb):
        state = next(states)
        if isinstance(state, Exception):
            raise state
        return state

    monkeypatch.setattr(trial, "dump_ui", dump)
    adb = SimpleNamespace(exec_out=lambda *args, **kwargs: SimpleNamespace(
        returncode=0, stdout=b"\x89PNG\r\n\x1a\n", stderr=b""))
    events = []
    log = SimpleNamespace(state=lambda *args, **kwargs: events.append(kwargs))
    assert trial.final_screenshot(adb, tmp_path, trial.CurrentRun("RUN-x", {}), log) is False
    assert events[-1]["reason"] == "ui-dump-failed-after-capture"
    assert not (tmp_path / "final-screenshot.png").exists()


def test_exclusive_hierarchy_guard_does_not_change_consent_top_policy(trial):
    root = trial.ET.Element("node", {"package": trial.PACKAGE})
    trial.ET.SubElement(root, "node", {"package": "android.systemui"})
    state = trial.UiState(root, {})
    trial.verify_top(state)
    with pytest.raises(trial.TrialError, match="foreign or unknown package"):
        trial.verify_capture_ui(state)
