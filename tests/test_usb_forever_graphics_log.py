"""No phone actions: same-RUN graphics log collection and truthful status."""
import importlib.util
import json
from pathlib import Path
import sys
from types import SimpleNamespace

import pytest


@pytest.fixture
def collector():
    path = Path(__file__).parents[1] / "scripts/diagnostics/usb_forever_trial.py"
    spec = importlib.util.spec_from_file_location("usb_graphics_log_fixture", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def measurement(run="RUN-graphics-fixture", status="DEVICE_REJECTED"):
    return (json.dumps({"schema": 1, "runId": run, "event": "probe_begin", "status": "STARTED"})
            + "\n" + json.dumps({"schema": 1, "runId": run, "event": "probe_end",
                                "status": status, "successfulPresents": 0,
                                "d3dTriangleResult": False, "stdoutMirrored": False,
                                "logDurable": True}) + "\n").encode()


def readback_measurement(run="RUN-graphics-fixture", *, map_result="0x00000000", pdata=True,
                         copy_result="VOID_SUBMITTED", phase_order=None, phase_status="VERIFIED"):
    phases = phase_order or ["offscreen_clear", "offscreen_draw", "backbuffer_clear", "backbuffer_draw"]
    rows = [{"schema": 1, "runId": run, "event": "probe_begin", "status": "STARTED", "variant": "readback-v1"},
             {"schema": 1, "runId": run, "event": "readback_window", "status": "READY", "marker": "modules-d3d-shaders-ready"},
             {"schema": 1, "runId": run, "event": "readback_window", "status": "RESUMED", "marker": "modules-d3d-shaders-ready"}]
    for phase in phases:
        is_clear = phase.endswith("clear")
        rows.append({"schema": 1, "runId": run, "event": "triangle_readback" if phase == "backbuffer_draw" else "readback",
                     "phase": phase, "status": phase_status, "textureResult": "0x00000000", "rtvResult": "0x00000000",
                     "stagingResult": "0x00000000", "mapResult": map_result, "copyResult": copy_result,
                     "pDataValid": pdata, "rowPitch": 1280, "width": 320, "height": 240,
                     "clearPixels": 76800 if is_clear else 70000, "coloredPixels": 0 if is_clear else 700,
                      "rgbaFnv1a": 1, "samples": [{"x": x, "y": y, "rgba": [10, 10, 20, 255]}
                                                 for x, y in ((0, 0), (160, 120), (319, 239), (80, 60), (240, 180))],
                     "deviceRemovedReason": "0x00000000"})
    rows.append({"schema": 1, "runId": run, "event": "device_removed_reason", "result": "0x00000000"})
    rows.extend({"schema": 1, "runId": run, "event": "present", "index": index,
                 "result": "0x00000000", "successful": True} for index in range(1, 31))
    rows.append({"schema": 1, "runId": run, "event": "probe_end", "status": "PASS", "variant": "readback-v1",
                 "successfulPresents": 30, "d3dTriangleResult": True, "offscreenClear": True, "offscreenDraw": True,
                 "backbufferClear": True, "backbufferDraw": True, "apiQualificationObserved": True, "logDurable": True})
    return ("\n".join(json.dumps(row) for row in rows) + "\n").encode()


def test_terminal_binding_truncation_and_no_false_pass(collector):
    raw = measurement()
    assert collector.validate_graphics_log(raw, "RUN-graphics-fixture")[-1]["status"] == "DEVICE_REJECTED"
    for invalid in (raw[:-1], measurement("RUN-old"), measurement(status="PASS"),
                    raw + raw, raw.replace(b'"schema": 1', b'"schema": 1, "schema": 1'),
                    b"x" * (collector.MAX_GRAPHICS_LOG_BYTES + 1)):
        with pytest.raises((collector.TrialError, json.JSONDecodeError)):
            collector.validate_graphics_log(invalid, "RUN-graphics-fixture")


def test_readback_variant_requires_ordered_coherent_verified_phases(collector):
    raw = readback_measurement()
    assert collector.validate_graphics_log(raw, "RUN-graphics-fixture", collector.READBACK_PROBE)[-1]["status"] == "PASS"
    bad_map = readback_measurement(map_result="0x80004005", pdata=False, copy_result="NOT_ATTEMPTED", phase_status="FAILED")
    with pytest.raises(collector.TrialError):
        collector.validate_graphics_log(bad_map, "RUN-graphics-fixture", collector.READBACK_PROBE)
    duplicate = readback_measurement(phase_order=["offscreen_clear", "offscreen_clear", "offscreen_draw", "backbuffer_clear", "backbuffer_draw"])
    with pytest.raises(collector.TrialError):
        collector.validate_graphics_log(duplicate, "RUN-graphics-fixture", collector.READBACK_PROBE)


@pytest.mark.parametrize("key", ["textureResult", "rtvResult", "stagingResult", "deviceRemovedReason"])
def test_collector_rejects_verified_hresult_errors(collector, key):
    rows = [json.loads(line) for line in readback_measurement().splitlines()]
    next(row for row in rows if row.get("phase") == "offscreen_draw")[key] = "0x80004005"
    raw = ("".join(json.dumps(row) + "\n" for row in rows)).encode()
    with pytest.raises(collector.TrialError):
        collector.validate_graphics_log(raw, "RUN-graphics-fixture", collector.READBACK_PROBE)


@pytest.mark.parametrize("status", ["FAILED", "DEVICE_REJECTED"])
def test_collector_keeps_early_failures_before_sampling_hold(collector, status):
    rows = [json.loads(line) for line in measurement(status=status).splitlines()]
    rows[0]["variant"] = "readback-v1"
    raw = ("".join(json.dumps(row) + "\n" for row in rows)).encode()
    assert collector.validate_graphics_log(raw, "RUN-graphics-fixture", collector.READBACK_PROBE)[-1]["status"] == status


@pytest.mark.parametrize("mutation", ["missing-resume", "empty-samples", "wrong-coordinate"])
def test_collector_requires_hold_pair_and_fixed_samples(collector, mutation):
    rows = [json.loads(line) for line in readback_measurement().splitlines()]
    if mutation == "missing-resume":
        rows = [row for row in rows if row.get("status") != "RESUMED"]
    else:
        phase = next(row for row in rows if row.get("phase") == "offscreen_draw")
        if mutation == "empty-samples":
            phase["samples"] = []
        else:
            phase["samples"][0]["x"] = -1
    with pytest.raises(collector.TrialError):
        collector.validate_graphics_log(("".join(json.dumps(row) + "\n" for row in rows)).encode(),
                                        "RUN-graphics-fixture", collector.READBACK_PROBE)


def test_collection_requires_same_current_run_before_and_after(collector, tmp_path, monkeypatch):
    raw = measurement()
    current = collector.CurrentRun("RUN-graphics-fixture", {})
    events = []
    journal = SimpleNamespace(state=lambda *args, **kwargs: events.append((args, kwargs)))

    class FakeAdb:
        def exec_out(self, *args, **kwargs):
            assert args[:2] == ("sh", "-c")
            assert "test ! -L" in args[2] and str(collector.MAX_GRAPHICS_LOG_BYTES + 1) in args[2]
            assert "/RUN-graphics-fixture-graphics-probe.jsonl" in args[2]
            return SimpleNamespace(returncode=0, stdout=raw, stderr=b"")

    monkeypatch.setattr(collector, "read_current", lambda adb: (current, "ok"))
    assert collector.collect_graphics_probe(FakeAdb(), tmp_path, current, journal)
    assert (tmp_path / "graphics-probe.jsonl").read_bytes() == raw
    assert events[-1][1]["reported_probe_status"] == "DEVICE_REJECTED"
    assert events[-1][1]["gameplay_acceptance"] is False
    assert not collector.collect_graphics_probe(FakeAdb(), tmp_path, current, journal)
    assert (tmp_path / "graphics-probe.jsonl").read_bytes() == raw

    (tmp_path / "graphics-probe.jsonl").unlink()  # Own fixture only.
    states = iter([(current, "ok"), (collector.CurrentRun("RUN-other", {}), "ok")])
    monkeypatch.setattr(collector, "read_current", lambda adb: next(states))
    assert not collector.collect_graphics_probe(FakeAdb(), tmp_path, current, journal)
    assert not (tmp_path / "graphics-probe.jsonl").exists()


def test_approved_trial_map_keeps_legacy_and_selects_compiler_log_tuple(collector):
    assert collector.APPROVED_TRIAL_PROBES[(50, "0.2.26-automated")] == collector.LEGACY_PROBE
    assert collector.APPROVED_TRIAL_PROBES[(51, "0.2.27-automated")] == collector.COMPILER_LOG_PROBE
    assert collector.COMPILER_LOG_PROBE.file == "GraphicsProbeLog-ARM64.exe"
    assert collector.COMPILER_LOG_PROBE.size == 90112
    assert collector.COMPILER_LOG_PROBE.sha256 == "795acd6d878d6def7139566da6040b16bf2ee6a2a9edfb90d16d4af32a67ab9a"
    assert collector.APPROVED_TRIAL_PROBES[(55, "0.2.31-automated")] == collector.READBACK_PROBE
    assert collector.READBACK_PROBE.file == "GraphicsProbeReadback-ARM64.exe"
    assert collector.READBACK_PROBE.size == 96768
    assert collector.READBACK_PROBE.sha256 == "80c1c260d7781bda73e3d5552c7ca730e72382288a9256b4a78c7899d3f0c3de"


def test_collector_preserves_budget_timeout_without_a_post_gpu_query(collector):
    rows = [json.loads(line) for line in readback_measurement().splitlines()]
    rows = [row for row in rows if row["event"] not in {"present", "device_removed_reason"}]
    rows.insert(-1, {"schema": 1, "runId": "RUN-graphics-fixture", "event": "probe_budget", "status": "TIMEOUT"})
    rows[-1].update(status="FAILED", successfulPresents=0, d3dTriangleResult=False)
    raw = ("".join(json.dumps(row) + "\n" for row in rows)).encode()
    assert collector.validate_graphics_log(raw, "RUN-graphics-fixture", collector.READBACK_PROBE)[-1]["status"] == "FAILED"


@pytest.mark.parametrize("contradiction", ["budget-timeout", "late-device-reason"])
def test_collector_rejects_timeout_pass_and_late_health_witness(collector, contradiction):
    rows = [json.loads(line) for line in readback_measurement().splitlines()]
    if contradiction == "budget-timeout":
        rows.insert(-1, {"schema": 1, "runId": "RUN-graphics-fixture", "event": "probe_budget", "status": "TIMEOUT"})
    else:
        reason = next(row for row in rows if row["event"] == "device_removed_reason")
        rows.remove(reason)
        rows.insert(-1, reason)
    with pytest.raises(collector.TrialError):
        collector.validate_graphics_log(("".join(json.dumps(row) + "\n" for row in rows)).encode(),
                                        "RUN-graphics-fixture", collector.READBACK_PROBE)


def test_probe_identity_collector_uses_selected_new_filename_and_pin(collector, tmp_path, monkeypatch):
    current = collector.CurrentRun("RUN-graphics-fixture", {})
    journal = SimpleNamespace(state=lambda *args, **kwargs: None)
    expected = collector.COMPILER_LOG_PROBE
    lines = f"{expected.size} 42 123\n{expected.sha256}  {expected.file}\n{expected.size} 42 123\n".encode()

    class FakeAdb:
        def shell(self, *args, **kwargs):
            assert expected.file in args[0]
            return SimpleNamespace(returncode=0, stdout=lines, stderr=b"")

    monkeypatch.setattr(collector, "read_current", lambda adb: (current, "ok"))
    assert collector.collect_probe_identity(FakeAdb(), tmp_path, current, journal, expected)
    identity = json.loads((tmp_path / "probe-identity.json").read_text())
    assert identity["file"] == expected.file
    assert identity["size"] == expected.size
    assert identity["sha256"] == expected.sha256
