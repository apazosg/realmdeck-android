import importlib.util
import json
from pathlib import Path

import pytest


PATH = Path(__file__).parents[1] / "scripts/diagnostics/android-egl/run_usb.py"
spec = importlib.util.spec_from_file_location("egl_usb_test", PATH)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def output():
    rows = [{"schema": probe.SCHEMA, "runId": "EGL-test", "event": name, "timeNs": 1 + i}
            for i, name in enumerate(probe.EVENTS)]
    rows[0].update(status="STARTED", pid=100, uid=10330)
    rows[1].update(status="MEASURED")
    rows[2].update(status="CREATED", current=True)
    rows[3].update(status="MEASURED", glRenderer="Mali-G610 MC6", glVendor="ARM", glVersion="OpenGL ES 3.2")
    rows[4].update(status="PASS")
    rows[5].update(status="PASS")
    rows[6].update(status="PASS", glError=0, coloredPixels=1300)
    rows[7].update(status="OFFSCREEN_PASS", offscreenPass=True, cleanupFailures=0, cleanupStatus="CLEAN",
                   pid=100, uid=10330, presentationVerified=False, d3dVerified=False, wowVerified=False)
    return rows


def serialize(rows):
    return ("\n".join(json.dumps(row) for row in rows) + "\n").encode()


def test_native_pass_does_not_accept_wine_or_presentation():
    summary = probe.validate_output(serialize(output()), "EGL-test", 10330, 0)
    assert summary["status"] == "OFFSCREEN_PASS"
    assert not any(summary[key] for key in ("presentationVerified", "d3dVerified", "wowVerified"))
    rows = output()
    rows[7]["cleanupFailures"] = 1
    assert probe.validate_output(serialize(rows), "EGL-test", 10330, 0)["status"] == "NOT_ACCEPTED"
    assert probe.validate_output(serialize(output()), "EGL-test", 10330, 1)["status"] == "NOT_ACCEPTED"


def test_unknown_or_misattributed_output_cannot_pass():
    rows = output()
    rows[6]["coloredPixels"] = 0
    assert probe.validate_output(serialize(rows), "EGL-test", 10330, 0)["status"] == "NOT_ACCEPTED"
    for changed in ("run", "pid", "order", "expanded", "time", "incomplete"):
        rows = output()
        if changed == "run": rows[3]["runId"] = "EGL-other"
        if changed == "pid": rows[7]["pid"] = 101
        if changed == "order": rows[3]["event"] = "end"
        if changed == "expanded": rows[7]["d3dVerified"] = True
        if changed == "time": rows[3]["timeNs"] = 0
        if changed == "incomplete": rows.pop()
        with pytest.raises(ValueError):
            probe.validate_output(serialize(rows), "EGL-test", 10330, 0)
