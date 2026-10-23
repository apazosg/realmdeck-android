"""Offline gates for the fixed POCO/private epoxy child; no device commands."""
import copy
import importlib.util
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).parents[1]
DIRECTORY = ROOT / "scripts/diagnostics/android-vtest"
sys.path.insert(0, str(DIRECTORY))
SPEC = importlib.util.spec_from_file_location("egl_epoxy_runner_under_test", DIRECTORY / "run_epoxy_usb.py")
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)
RUN = "VTEST-" + "a" * 32
PRIVATE = f"/data/user/0/{runner.loader.PACKAGE}/files/forever/controls/vtest-loader/{RUN}"


def rows():
    base = {"schema": runner.SCHEMA, "runId": RUN, "pid": 42, "uid": 10330, "pgid": 40, "sid": 40}
    result = []
    for i, event in enumerate(runner.EVENTS):
        row = {**base, "event": event, "timeNs": i + 1}
        for key in runner.EVENT_FIELDS[event]:
            row[key] = (False if key in runner.BOOL_FIELDS else
                        None if key in runner.OPTIONAL_BOOL else
                        runner.INT_RANGES[key][0] if key in runner.INT_RANGES else None)
        result.append(row)
    result[0]["status"] = "STARTED"
    result[1].update(status="LOADED", scopeValid=True, slotAddressOwner=True,
                     canonicalPath=PRIVATE + "/lib/libepoxy.so", missingSymbol=None, lookupError=None)
    # Optional extension failure must not prevent a real default-display measurement.
    result[2].update(status="UNKNOWN", extensionScanValid=False, eglError=0x3008)
    result[3].update(status="MEASURED", displayValid=True, initialized=True, extensionScanValid=True,
                     eglError=0x3000, mandatoryEglErrorCount=0, major=1, minor=4, vendor="Android",
                     version="1.4", clientApis="OpenGL_ES", extensions="EGL_KHR_create_context")
    result[4].update(status="CREATED", apiBound=True, configChosen=True, configCount=1,
                     contextCreated=True, current=True, surface="EGL_NO_SURFACE", eglError=0x3000)
    result[5].update(status="MEASURED", glVendor="ARM", glRenderer="Mali-G610 MC6", glVersion="OpenGL ES 3.2",
                     implementationPathKind="UNKNOWN", glError=0)
    result[6].update(status="CLEAN", cleanupStatus="CLEAN", cleanupFailures=0)
    result[7].update(status="EPOXY_CONTEXT_PASS", nativeExit=0, eglContextVerified=True,
                     eglSuccess=True, cleanup0=True, mandatoryEglErrorCount=0,
                     **{key: False for key in runner.BROADER})
    return result


def encoded(value):
    return b"".join(json.dumps(row).encode() + b"\n" for row in value)


def test_context_only_strict_rows_and_optional_extension_failure():
    assert runner.validate_output(encoded(rows()), RUN, 0, True)[0] == "EPOXY_CONTEXT_PASS"
    for index, key, value in [(1, "canonicalPath", "/system/lib64/libepoxy.so"),
                               (1, "slotAddressOwner", False), (4, "current", False),
                               (5, "glVendor", None), (6, "cleanupFailures", 1),
                               (7, "wowVerified", True), (7, "eglSuccess", False)]:
        broken = copy.deepcopy(rows())
        broken[index][key] = value
        assert runner.validate_output(encoded(broken), RUN, 0, True)[0] == "UNKNOWN"
    broken = rows()
    broken[7].update(status="CONTEXT_FAILED", nativeExit=1, eglContextVerified=False)
    assert runner.validate_output(encoded(broken), RUN, 1, True)[0] == "CONTEXT_FAILED"


def test_stale_incomplete_duplicate_output_and_exit_are_not_pass():
    raw = encoded(rows())
    for data, run, code, complete, truncated in [(raw[:-1], RUN, 0, True, False),
                                                (raw, RUN + "b", 0, True, False),
                                                (raw, RUN, 1, True, False),
                                                (raw, RUN, 0, False, False),
                                                (raw, RUN, 0, True, True),
                                                (raw + raw, RUN, 0, True, False),
                                                (raw.replace(b'"pid": 42', b'"pid": 42, "pid": 43', 1), RUN, 0, True, False)]:
        assert runner.validate_output(data, run, code, complete, truncated)[0] == "UNKNOWN"


def test_fixed_command_and_artifact_pin():
    command = runner.native_command(PRIVATE, RUN)
    assert command[:8] == [runner.loader.ADB, "-s", runner.loader.SERIAL, "shell", "-T", "run-as", runner.loader.PACKAGE, "sh"]
    assert "cd " in command[-1] and "lib:/system/lib64" in command[-1]
    assert "EglEpoxyProbe --run-id " + RUN in command[-1]
    assert "virgl_test_server --" not in command[-1]
    import pytest
    for root, run in [(PRIVATE + "/../elsewhere", RUN), (PRIVATE, "bad"), ("/data/local/tmp/" + RUN, RUN)]:
        with pytest.raises(ValueError):
            runner.native_command(root, run)
    if runner.PROBE.exists():
        assert runner.probe_file()["sha256"] == runner.PROBE_SHA256


def test_preexec_capture_failure_starts_no_process(monkeypatch, tmp_path):
    import pytest
    monkeypatch.setattr(runner.loader, "_external_parent", lambda path: tmp_path)
    monkeypatch.setattr(runner.loader, "load_candidate", lambda: ({}, [], b"fixture"))
    monkeypatch.setattr(runner, "probe_file", lambda: {"destination": "bin/EglEpoxyProbe", "bytes": 1, "sha256": "a" * 64, "mode": "0700"})
    monkeypatch.setattr(runner.loader, "remote_status_preflight", lambda: {"shellV2": True})
    monkeypatch.setattr(runner.loader, "_package_preflight", lambda: (10330, f"/data/user/0/{runner.loader.PACKAGE}", "runas_app"))
    monkeypatch.setattr(runner.loader, "system_alias_evidence", lambda: [])
    monkeypatch.setattr(runner.loader, "copy_payload", lambda files, run: ("files/forever/controls/vtest-loader/" + run, {"stagingClean": True}))
    calls = []
    monkeypatch.setattr(runner.subprocess, "Popen", lambda *args, **kwargs: calls.append((args, kwargs)))
    def failed_write(*args, **kwargs):
        raise OSError("fixture disk full")
    monkeypatch.setattr(runner.loader, "_write_exclusive", failed_write)
    with pytest.raises(OSError, match="disk full"):
        runner.main(["--output", str(tmp_path)])
    assert calls == []


def test_bool_as_integer_and_extra_schema_fields_never_pass():
    for index, key, value in [(0, "pgid", True), (1, "extra", "bad"), (3, "mandatoryEglErrorCount", False),
                               (4, "configCount", True), (6, "cleanupFailures", False), (7, "nativeExit", False)]:
        broken = rows()
        broken[index][key] = value
        assert runner.validate_output(encoded(broken), RUN, 0, True)[0] == "UNKNOWN"


def test_cleanup_adb_errors_still_terminate_held_host_handle(monkeypatch):
    class Process:
        returncode = None
        terminated = False
        def poll(self):
            return self.returncode
        def terminate(self):
            self.terminated = True
            self.returncode = -15
        def wait(self, timeout):
            return self.returncode
    process = Process()
    def adb_failure(*args, **kwargs):
        raise subprocess.TimeoutExpired("fixed adb fixture", 1)
    monkeypatch.setattr(runner, "owned_processes", adb_failure)
    monkeypatch.setattr(runner, "force_stop", adb_failure)
    cleaned = runner.cleanup_child(process, [], True)
    assert process.terminated
    assert cleaned["forcedCleanup"]["status"] == "UNKNOWN"
    assert cleaned["remainingOwnProcesses"] == [{"state": "UNKNOWN"}]
    assert len(cleaned["errors"]) == 3
    # A timeout may outlive the host handle; still attempt remote package cleanup.
    process.returncode = 0
    attempts = []
    monkeypatch.setattr(runner, "owned_processes", lambda timeout: [])
    monkeypatch.setattr(runner, "force_stop", lambda timeout: attempts.append(timeout) or {"returnCode": 0, "stdout": "", "stderr": ""})
    runner.cleanup_child(process, [], True)
    assert len(attempts) == 1


def test_timeout_cleanup_failure_preserves_summary_and_closed_stdin(monkeypatch, tmp_path):
    import io
    from uuid import UUID
    monkeypatch.setattr(runner.uuid, "uuid4", lambda: UUID("a" * 32))
    monkeypatch.setattr(runner.loader, "_external_parent", lambda path: tmp_path)
    monkeypatch.setattr(runner.loader, "load_candidate", lambda: ({}, [], b"fixture"))
    monkeypatch.setattr(runner, "probe_file", lambda: {"destination": "bin/EglEpoxyProbe", "bytes": 1, "sha256": "a" * 64, "mode": "0700"})
    monkeypatch.setattr(runner.loader, "remote_status_preflight", lambda: {"shellV2": True})
    monkeypatch.setattr(runner.loader, "_package_preflight", lambda: (10330, f"/data/user/0/{runner.loader.PACKAGE}", "runas_app"))
    monkeypatch.setattr(runner.loader, "system_alias_evidence", lambda: [])
    monkeypatch.setattr(runner.loader, "copy_payload", lambda files, run: ("files/forever/controls/vtest-loader/" + run, {"stagingClean": True}))
    out = tmp_path / ("native-EPOXY-" + RUN)
    class Process:
        pid = 123
        returncode = None
        terminated = False
        stdout = io.BytesIO(encoded(rows()))
        stderr = io.BytesIO(b"")
        def poll(self):
            return self.returncode
        def wait(self, timeout):
            if self.returncode is None:
                raise subprocess.TimeoutExpired("held host adb fixture", timeout)
            return self.returncode
        def terminate(self):
            self.terminated = True
            self.returncode = -15
    process = Process()
    def start(*args, **kwargs):
        assert kwargs["stdin"] == subprocess.DEVNULL
        assert (out / "capture.json").is_file()
        return process
    def adb_failure(*args, **kwargs):
        raise subprocess.TimeoutExpired("post-start fixed adb fixture", 1)
    monkeypatch.setattr(runner.subprocess, "Popen", start)
    monkeypatch.setattr(runner, "owned_processes", adb_failure)
    monkeypatch.setattr(runner, "force_stop", adb_failure)
    assert runner.main(["--output", str(tmp_path)]) == 1
    assert process.terminated
    saved = json.loads((out / "summary.json").read_text())
    assert saved["status"] == "UNKNOWN" and saved["timedOut"] and saved["cleanupErrors"]
    assert saved["eglContextVerified"] is False
    assert (out / "stdout.txt").is_file() and (out / "stderr.txt").is_file()
