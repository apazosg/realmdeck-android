"""Offline regression: raw and base64 stream budgets are separate."""
import base64
import importlib.util
import json
from pathlib import Path
import sys
from types import SimpleNamespace


def test_adb_calls_share_one_absolute_deadline(monkeypatch):
    path = Path(__file__).parents[1] / "scripts/diagnostics/usb_forever_trial.py"
    spec = importlib.util.spec_from_file_location("usb_deadline_fixture", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    clock = [10.0]
    calls = []
    monkeypatch.setattr(module.time, "monotonic", lambda: clock[0])

    def run(argv, **kwargs):
        calls.append(kwargs["timeout"])
        clock[0] += kwargs["timeout"]
        return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")

    monkeypatch.setattr(module.subprocess, "run", run)
    monkeypatch.setattr(module, "SERIAL", "TEST-HOST-ONLY-SERIAL")
    adb = module.Adb()
    adb.deadline = 12.0
    adb.shell("true", timeout=45)
    assert calls == [2.0]
    import pytest
    with pytest.raises(module.TrialError, match="absolute host"):
        adb.exec_out("true", timeout=45)
    assert calls == [2.0]


def test_full_stderr_and_stdout_fit_the_bounded_collector(tmp_path):
    path = Path(__file__).parents[1] / "scripts/diagnostics/usb_forever_trial.py"
    spec = importlib.util.spec_from_file_location("usb_forever_stream_fixture", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    run_id = "RUN-stream-fixture"
    payloads = {}
    for stream in ("stdout", "stderr"):
        head = b"h" * module.STREAM_HEAD_BYTES
        tail = b"t" * module.STREAM_TAIL_BYTES
        payloads[stream] = {
            "schema": 1, "runId": run_id, "stream": stream,
            "headBase64": base64.b64encode(head).decode(),
            "tailBase64": base64.b64encode(tail).decode(),
            "totalBytes": 2_000_000, "truncated": True,
        }

    class FakeAdb:
        def exec_out(self, *args, **kwargs):
            stream = "stderr" if "-stderr-" in args[-1] else "stdout"
            raw = json.dumps(payloads[stream]).encode()
            assert len(raw) < module.MAX_STREAM_JSON
            assert int(args[-2]) == module.MAX_STREAM_JSON + 1
            return SimpleNamespace(returncode=0, stdout=raw, stderr=b"")

    journal = SimpleNamespace(state=lambda *args, **kwargs: None)
    module.bounded_streams(FakeAdb(), tmp_path, module.CurrentRun(run_id, {}), journal)
    status = json.loads((tmp_path / "stream-status.json").read_text())
    for stream in ("stdout", "stderr"):
        assert status["streams"][stream]["status"] == "saved"
        assert json.loads((tmp_path / f"{stream}-snapshot.json").read_text()) == payloads[stream]
