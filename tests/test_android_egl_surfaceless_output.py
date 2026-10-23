"""Host acceptance gates for the fixed system-EGL surfaceless control."""
from __future__ import annotations

import copy
import importlib.util
import json
from pathlib import Path

import pytest


RUN = "EGLSF-fixture-1"
UID = 10330
ROOT = Path(__file__).parents[1]
SPEC = importlib.util.spec_from_file_location("egl_usb_surfaceless", ROOT / "scripts/diagnostics/android-egl/run_usb.py")
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def records() -> list[dict]:
    rows = [{"schema": "wowforever.android-egl-surfaceless/version1", "runId": RUN,
             "event": event, "timeNs": index + 1} for index, event in enumerate(MODULE.EVENTS)]
    rows[0].update(status="STARTED", pid=222, uid=UID)
    rows[1].update(status="MEASURED", extensionScanValid=True, hasEGLKHRCreateContext=True,
                   hasEGLKHRSurfacelessContext=True, surfaceCreated=False, noSurfacePath=True, eglError=0x3000)
    rows[2].update(status="CREATED", current=True, noSurfaceCurrent=True,
                   surface="EGL_NO_SURFACE", eglError=0x3000)
    rows[3].update(status="MEASURED", glVendor="ARM", glRenderer="Mali-G610 MC6", glVersion="OpenGL ES 3.2")
    rows[4].update(status="PASS")
    rows[5].update(status="PASS", fboCreated=True, fboComplete=True)
    rows[6].update(status="PASS", glError=0, coloredPixels=1300, rgbaFnv1a=3005607757)
    rows[7].update(status="SURFACELESS_OFFSCREEN_PASS", offscreenPass=True, cleanupFailures=0,
                   cleanupStatus="CLEAN", pid=222, uid=UID, nativeExit=0, vtestVerified=False,
                   presentationVerified=False, d3dVerified=False, wowVerified=False)
    return rows


def encode(rows: list[dict]) -> bytes:
    return ("\n".join(json.dumps(row) for row in rows) + "\n").encode()


def test_surfaced_fbo_readback_is_only_an_offscreen_prerequisite() -> None:
    accepted = MODULE.validate_output(encode(records()), RUN, UID, 0, "surfaceless")
    assert accepted["status"] == "SURFACELESS_OFFSCREEN_PASS"
    assert accepted["probeKind"] == "surfaceless"
    assert not any(accepted[key] for key in ("presentationVerified", "d3dVerified", "wowVerified", "vtestVerified"))
    for index, key, value in [(1, "hasEGLKHRCreateContext", None), (1, "hasEGLKHRSurfacelessContext", False),
                              (1, "extensionScanValid", False), (2, "noSurfaceCurrent", False),
                              (5, "fboComplete", False), (6, "rgbaFnv1a", 0), (7, "cleanupFailures", 1)]:
        changed = copy.deepcopy(records())
        changed[index][key] = value
        assert MODULE.validate_output(encode(changed), RUN, UID, 0, "surfaceless")["status"] == "NOT_ACCEPTED"
    assert MODULE.validate_output(encode(records()), RUN, UID, 1, "surfaceless")["status"] == "NOT_ACCEPTED"


def test_wrong_identity_pbuffer_schema_and_extra_acceptance_are_rejected() -> None:
    for key, value in [("runId", "EGLSF-other"), ("schema", "wowforever.android-egl-baseline/version1")]:
        changed = copy.deepcopy(records())
        changed[3][key] = value
        with pytest.raises(ValueError):
            MODULE.validate_output(encode(changed), RUN, UID, 0, "surfaceless")
    changed = records()
    changed[-1]["wowVerified"] = True
    with pytest.raises(ValueError):
        MODULE.validate_output(encode(changed), RUN, UID, 0, "surfaceless")
    with pytest.raises(ValueError):
        MODULE.validate_output(encode(records())[:-1], RUN, UID, 0, "surfaceless")
    with pytest.raises(ValueError):
        MODULE.validate_output(encode(records()), RUN, UID, 0, "arbitrary-binary")
