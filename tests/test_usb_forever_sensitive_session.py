"""Offline fail-closed checks for assisted-session host capture boundaries."""
import importlib.util
import os
from pathlib import Path
import pwd
import subprocess
import sys
import tempfile
from types import SimpleNamespace

import pytest


@pytest.fixture
def trial():
    path = Path(__file__).parents[1] / "scripts/diagnostics/usb_forever_trial.py"
    spec = importlib.util.spec_from_file_location("usb_sensitive_session_fixture", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class SensitiveAdb:
    def __init__(self, error_type=RuntimeError):
        self.sensitive_session_detected = False
        self.error_type = error_type
        self.calls = []

    def require_no_sensitive_session(self):
        self.calls.append("marker")
        self.sensitive_session_detected = True
        raise self.error_type("assisted credential session active or marker unavailable; host capture disabled")

    def exec_out(self, *args, **kwargs):
        raise AssertionError("capture proceeded after the marker guard")

    def shell(self, *args, **kwargs):
        raise AssertionError("device command proceeded after the marker guard")


def test_adb_marker_probe_accepts_only_explicit_clear_and_fails_closed(trial, monkeypatch):
    adb = trial.Adb.__new__(trial.Adb)
    adb.path = trial.ADB
    adb.serial = "synthetic-serial"
    adb.deadline = None
    adb.sensitive_session_detected = False
    calls = []

    def shell(*args, **kwargs):
        calls.append((args, kwargs))
        return SimpleNamespace(returncode=0, stdout=b"CLEAR\n", stderr=b"")

    monkeypatch.setattr(adb, "shell", shell)
    adb.require_no_sensitive_session()
    assert calls[0][0][:3] == ("run-as", trial.PACKAGE, "sh")
    assert trial.SENSITIVE_SESSION_MARKER in calls[0][0][-1]

    monkeypatch.setattr(adb, "shell", lambda *args, **kwargs: SimpleNamespace(returncode=0, stdout=b"ACTIVE\n"))
    with pytest.raises(trial.TrialError, match="marker unavailable"):
        adb.require_no_sensitive_session()
    assert adb.sensitive_session_detected

    run_probe = []
    adb.sensitive_session_detected = False

    def clear_run_marker(*args, **kwargs):
        run_probe.append((args, kwargs))
        return SimpleNamespace(returncode=0, stdout=b"CLEAR\n", stderr=b"")

    monkeypatch.setattr(adb, "shell", clear_run_marker)
    adb.require_no_sensitive_run("RUN-synthetic")
    assert "RUN-synthetic-assisted-sensitive" in run_probe[0][0][-1]
    monkeypatch.setattr(adb, "shell", lambda *args, **kwargs: SimpleNamespace(
        returncode=0, stdout=b"ACTIVE\n", stderr=b""))
    with pytest.raises(trial.TrialError, match="sensitive RUN"):
        adb.require_no_sensitive_run("RUN-synthetic")
    assert adb.sensitive_session_detected


@pytest.mark.parametrize(
    ("probe", "blocked_directory", "mode"),
    (("session", "files/forever", 0o4), ("run", "files/forever/runs", 0o3)),
)
def test_marker_probes_fail_closed_when_existing_parent_is_unreadable(
    trial, probe, blocked_directory, mode
):
    with tempfile.TemporaryDirectory(prefix="forever-marker-denial-") as temporary:
        root = Path(temporary)
        root.chmod(0o755)
        (root / "files/forever/runs").mkdir(parents=True)
        blocked = root / blocked_directory
        blocked.chmod(mode)

        adb = trial.Adb.__new__(trial.Adb)
        adb.path = trial.ADB
        adb.serial = "synthetic-serial"
        adb.deadline = None
        adb.sensitive_session_detected = False

        def shell(*args, **kwargs):
            preexec_fn = None
            if os.geteuid() == 0:
                nobody = pwd.getpwnam("nobody")

                def drop_privileges():
                    os.setgroups([])
                    os.setgid(nobody.pw_gid)
                    os.setuid(nobody.pw_uid)

                preexec_fn = drop_privileges
            result = subprocess.run(
                ("sh", "-c", args[-1]), cwd=root, stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, check=False, preexec_fn=preexec_fn,
            )
            return SimpleNamespace(returncode=result.returncode, stdout=result.stdout, stderr=result.stderr)

        adb.shell = shell
        with pytest.raises(trial.TrialError, match="marker unavailable"):
            if probe == "session":
                adb.require_no_sensitive_session()
            else:
                adb.require_no_sensitive_run("RUN-synthetic")
        assert adb.sensitive_session_detected


def test_capture_entrypoints_refuse_marker_before_device_reads_or_host_writes(trial, tmp_path):
    adb = SensitiveAdb(trial.TrialError)
    run = trial.CurrentRun("RUN-sensitive", {})
    calls = (
        lambda: trial.dump_ui(adb),
        lambda: trial.read_private_json(adb, "files/forever/current.json"),
        lambda: trial.read_private_bytes(adb, "files/forever/private.json"),
        lambda: trial.save_run_diagnostics(adb, tmp_path, run.run_id, SimpleNamespace(state=lambda *a, **k: None)),
        lambda: trial.bounded_streams(adb, tmp_path, run, SimpleNamespace(state=lambda *a, **k: None)),
        lambda: trial.process_snapshot(adb, tmp_path, SimpleNamespace(state=lambda *a, **k: None), 0, {}),
        lambda: trial.final_screenshot(adb, tmp_path, run),
        lambda: trial.force_stop_if_current(adb, run.run_id, SimpleNamespace(state=lambda *a, **k: None)),
    )
    for call in calls:
        with pytest.raises(trial.TrialError, match="active or marker unavailable"):
            call()
    assert not list(tmp_path.iterdir())


def test_persistent_sensitive_run_taint_blocks_later_host_capture_and_cleanup(trial, tmp_path):
    class SensitiveRunAdb:
        sensitive_session_detected = False

        def require_no_sensitive_run(self, run_id):
            assert run_id == "RUN-sensitive"
            self.sensitive_session_detected = True
            raise trial.TrialError("sensitive RUN or marker unavailable; host capture disabled")

        def shell(self, *args, **kwargs):
            raise AssertionError("device access proceeded after the RUN taint guard")

        def exec_out(self, *args, **kwargs):
            raise AssertionError("device capture proceeded after the RUN taint guard")

    adb = SensitiveRunAdb()
    run = trial.CurrentRun("RUN-sensitive", {})
    for call in (
        lambda: trial.save_run_diagnostics(adb, tmp_path, run.run_id, SimpleNamespace(state=lambda *a, **k: None)),
        lambda: trial.bounded_streams(adb, tmp_path, run, SimpleNamespace(state=lambda *a, **k: None)),
        lambda: trial.process_snapshot(adb, tmp_path, SimpleNamespace(state=lambda *a, **k: None), 0, {}, run.run_id),
        lambda: trial.final_screenshot(adb, tmp_path, run),
        lambda: trial.force_stop_if_current(adb, run.run_id, SimpleNamespace(state=lambda *a, **k: None)),
    ):
        with pytest.raises(trial.TrialError, match="sensitive RUN or marker unavailable"):
            call()
    assert not list(tmp_path.iterdir())


def test_trial_preflight_and_logcat_refuse_before_creating_capture(trial, tmp_path, monkeypatch):
    class GuardAdb(SensitiveAdb):
        def __init__(self):
            super().__init__(trial.TrialError)

    monkeypatch.setattr(trial, "require_serial", lambda: "synthetic-serial")
    monkeypatch.setattr(trial, "Adb", GuardAdb)
    output_checked = []
    monkeypatch.setattr(trial, "safe_output_dir", lambda _path: output_checked.append(True))
    args = SimpleNamespace(output=tmp_path, version_code=39, version_name="0.2.15-automated", seconds=20)
    with pytest.raises(trial.TrialError, match="active or marker unavailable"):
        trial.run_trial(args)
    assert not output_checked

    monkeypatch.setattr(trial, "SERIAL", "synthetic-serial")
    popen_calls = []
    monkeypatch.setattr(trial.subprocess, "Popen", lambda *a, **k: popen_calls.append(a))
    with pytest.raises(trial.TrialError, match="active or marker unavailable"):
        trial.LogcatCapture(tmp_path, trial.UID, GuardAdb())
    assert not popen_calls
    assert not (tmp_path / "logcat.txt").exists()


def test_sensitive_abort_purges_only_content_captures(trial, tmp_path):
    (tmp_path / "journal.jsonl").write_text("safe metadata\n")
    (tmp_path / "final-screenshot.png").write_bytes(b"synthetic image")
    (tmp_path / "run-diagnostics.jsonl").write_text("synthetic raw output")
    (tmp_path / "private-run.json").write_text("possibly sensitive snapshot")
    trial.discard_sensitive_capture_outputs(tmp_path)
    assert (tmp_path / "journal.jsonl").exists()
    assert not (tmp_path / "final-screenshot.png").exists()
    assert not (tmp_path / "run-diagnostics.jsonl").exists()
    assert not (tmp_path / "private-run.json").exists()


def test_marker_appearing_after_capture_read_removes_the_partial_host_copy(trial, tmp_path):
    class MarkerRaceAdb:
        sensitive_session_detected = False

        def __init__(self):
            self.checks = 0

        def require_no_sensitive_session(self):
            self.checks += 1
            if self.checks == 2:
                self.sensitive_session_detected = True
                raise trial.TrialError("assisted credential session active or marker unavailable")

    target = tmp_path / "captured.bin"
    adb = MarkerRaceAdb()
    with pytest.raises(trial.TrialError, match="active or marker unavailable"):
        trial.write_capture_bytes(adb, target, b"synthetic captured content")
    assert adb.checks == 2
    assert not target.exists()
