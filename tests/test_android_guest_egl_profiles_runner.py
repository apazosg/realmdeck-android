"""Offline HOST tests for the fixed guest EGL profile matrix runner."""

from __future__ import annotations

import copy
import importlib.util
import io
import inspect
import json
from pathlib import Path


ROOT = Path(__file__).parents[1]
DIRECTORY = ROOT / "scripts/diagnostics/android-vtest"
SPEC = importlib.util.spec_from_file_location("guest_profiles_runner", DIRECTORY / "run_guest_profiles_usb.py")
assert SPEC and SPEC.loader
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)

RUN = "VTEST-" + "a" * 32
PRIVATE = f"/data/user/0/{runner.PACKAGE}/files/forever/controls/vtest-loader/{RUN}"
IDENTITY = {"pid": 32090, "startTicks": 8892956}


def _attempt(index: int) -> dict[str, object]:
    requests = (
        ("OpenGL3.3CORE", 3, 3, "CORE", True),
        ("OpenGL3.3COMPAT", 3, 3, "COMPAT", False),
        ("OpenGL3.2CORE", 3, 2, "CORE", True),
        ("OpenGL3.0DEFAULT", 3, 0, None, None),
    )
    name, major, minor, profile, core = requests[index]
    return {
        "name": name,
        "requested": {"major": major, "minor": minor, "profile": profile, "core": core},
        "serverLiveBefore": True, "createCalled": True, "createError": 0x3009,
        "created": False, "makeCurrentCalled": False, "makeCurrentError": None, "madeCurrent": False,
        "actual": {"valid": False, "major": None, "minor": None, "profileKnown": False, "profileMask": None,
                    "core": None, "vendor": None, "renderer": None, "version": None, "glslVersion": None,
                    "glError": 0, "rendererVirgl": False, "softwareFallback": False},
        "cleanup": {"unbindAttempted": False, "unbindOk": False, "unbindError": None,
                     "destroyAttempted": False, "destroyOk": False, "destroyError": None, "valid": True},
        "serverLiveAfter": True, "deadlineValid": True,
    }


def _document() -> dict[str, object]:
    return {
        "schema": runner.SCHEMA, "runId": RUN, "pid": 32111, "uid": runner.UID, "pgid": 32111, "sid": 32111,
        "times": {"startedNs": 1, "finishedNs": 2, "durationNs": 1},
        "status": "PROFILE_MATRIX_MEASURED", "contextQualified": False,
        "hardwareRenderingVerified": False, "triangleVerified": False, "presentationVerified": False,
        "d3dVerified": False, "wowVerified": False,
        "scope": {"valid": True, "environmentValid": True, "selectionValid": True, "cwd": PRIVATE,
                  "libraryPath": PRIVATE + "/guest/lib/libEGL.so.1.1.0", "ldLibraryPath": PRIVATE + "/guest/lib:/system/lib64",
                  "vendorJson": PRIVATE + "/guest/share/glvnd/egl_vendor.d/50_mesa.json",
                  "guestManifestSha256": runner.GUEST_MANIFEST_SHA256, "guestTreeSha256": runner.GUEST_TREE_SHA256,
                  "serverManifestSha256": runner.SERVER_MANIFEST_SHA256},
        "server": {"expectedPid": IDENTITY["pid"], "expectedStartTicks": IDENTITY["startTicks"],
                   "identityLoaded": True, "live": True, "liveBeforeTerminate": True},
        "library": {"loaded": True, "privatePath": PRIVATE + "/guest/lib/libEGL.so.1.1.0",
                    "canonicalPath": PRIVATE + "/guest/lib/libEGL.so.1.1.0", "loadError": None},
        "exports": {"expectedCount": 11, "exportCount": 11, "allExports": True, "ownerExact": True,
                    "ownerPath": PRIVATE + "/guest/lib/libEGL.so.1.1.0", "missingSymbol": None, "wrongOwnerSymbol": None},
        "display": {"clientExtensions": "EGL_EXT_platform_base", "clientExtensionBytes": 23,
                    "displayExtensions": "EGL_KHR_create_context EGL_KHR_surfaceless_context", "displayExtensionBytes": 55,
                    "vendor": "Mesa", "version": "EGL 1.5", "clientApis": "OpenGL", "displayCreated": True,
                    "displayInitialized": True, "eglError": 0x3000},
        "config": {"boundApi": True, "chosen": True, "count": 1, "attributesValid": True,
                    "bindError": 0x3000, "chooseError": 0x3000,
                   "EGL_RENDERABLE_TYPE": 8, "EGL_CONFORMANT": 8, "EGL_SURFACE_TYPE": 0, "EGL_CONFIG_ID": 3,
                   "attributeErrors": {"EGL_RENDERABLE_TYPE": 0x3000, "EGL_CONFORMANT": 0x3000,
                                       "EGL_SURFACE_TYPE": 0x3000, "EGL_CONFIG_ID": 0x3000}},
        "attempts": [_attempt(index) for index in range(4)],
        "maps": {"complete": True, "truncated": False, "bytes": 1000, "lines": 10, "privateLoader": True,
                 "privateVendor": True, "privateGallium": True, "privateLlvm": True, "androidEgl": False,
                 "androidGles": False, "loaderPath": PRIVATE + "/guest/lib/libEGL.so.1.1.0",
                 "vendorPath": PRIVATE + "/guest/lib/libEGL_mesa.so", "error": None},
        "cleanup": {"terminateAttempted": True, "terminateOk": True, "terminateError": 0x3000,
                     "handleClosed": True, "failures": 0, "serverLiveBeforeTerminate": True},
        "nativeExit": 0,
    }


def _raw(document: dict[str, object]) -> bytes:
    return (json.dumps(document, separators=(",", ":")) + "\n").encode()


def test_fixed_artifact_pin_and_command_are_closed() -> None:
    assert runner.NATIVE_ARTIFACT_BYTES == 39008
    assert runner.NATIVE_ARTIFACT_SHA256 == "d912d2a4e872ab7b1981e6f1f8b5f0156b02e79719e31e7f5d6af19f0fbc3726"
    command = runner.native_command(PRIVATE, RUN)[-1]
    assert "GuestEglProfiles --run-id " + RUN in command
    assert "GALLIUM_DRIVER=virpipe" in command and "VTEST_SOCKET_NAME=vtest.sock" in command
    assert "DISPLAY" not in command and "LD_PRELOAD" not in command


def test_exact_fixture_is_measured_and_support_flags_stay_false() -> None:
    status, rows = runner.validate_output(_raw(_document()), RUN, 0, True, server_identity=IDENTITY)
    assert status == "PROFILE_MATRIX_MEASURED"
    assert rows[0]["contextQualified"] is False
    assert all(rows[0][key] is False for key in runner.BROADER_FLAGS)


def test_duplicate_keys_boolean_ints_and_wrong_order_are_unknown() -> None:
    raw = _raw(_document())
    duplicate = raw.replace(b'"pid":32111,', b'"pid":32111,"pid":32111,', 1)
    assert runner.validate_output(duplicate, RUN, 0, True, server_identity=IDENTITY)[0] == "UNKNOWN"
    boolean_int = copy.deepcopy(_document())
    boolean_int["pid"] = True
    assert runner.validate_output(_raw(boolean_int), RUN, 0, True, server_identity=IDENTITY)[0] == "UNKNOWN"
    wrong_order = copy.deepcopy(_document())
    wrong_order["attempts"][1]["requested"]["profile"] = "CORE"
    assert runner.validate_output(_raw(wrong_order), RUN, 0, True, server_identity=IDENTITY)[0] == "UNKNOWN"


def test_contradictory_created_state_and_lower_context_do_not_become_33_pass() -> None:
    contradictory = copy.deepcopy(_document())
    contradictory["attempts"][0]["created"] = True
    assert runner.validate_output(_raw(contradictory), RUN, 0, True, server_identity=IDENTITY)[0] == "UNKNOWN"
    lower = copy.deepcopy(_document())
    attempt = lower["attempts"][3]
    attempt.update({"createError": 0x3000, "created": True, "makeCurrentCalled": True,
                    "makeCurrentError": 0x3000, "madeCurrent": True})
    attempt["actual"].update({"valid": True, "major": 3, "minor": 0, "vendor": "Mesa",
                               "renderer": "virgl", "rendererVirgl": True, "version": "3.0", "glslVersion": "1.30"})
    attempt["cleanup"].update({"unbindAttempted": True, "unbindOk": True, "unbindError": 0x3000,
                                "destroyAttempted": True, "destroyOk": True, "destroyError": 0x3000})
    assert runner.validate_output(_raw(lower), RUN, 0, True, server_identity=IDENTITY)[0] == "PROFILE_MATRIX_MEASURED"
    assert lower["contextQualified"] is False
    bad_renderer = copy.deepcopy(lower)
    bad_renderer["attempts"][3]["actual"]["renderer"] = "llvmpipe"
    assert runner.validate_output(_raw(bad_renderer), RUN, 0, True, server_identity=IDENTITY)[0] == "UNKNOWN"
    bad_profile = copy.deepcopy(lower)
    bad_profile["attempts"][3]["actual"].update({"major": 4, "minor": 0, "profileKnown": True, "profileMask": 1, "core": False})
    assert runner.validate_output(_raw(bad_profile), RUN, 0, True, server_identity=IDENTITY)[0] == "UNKNOWN"


def test_context_main_exposes_only_internal_di_and_old_defaults_remain() -> None:
    parameters = inspect.signature(runner.context_runner.main).parameters
    assert {"probe_loader", "payload_copier", "native_command_builder", "classifier", "pass_status", "boundary"} <= set(parameters)
    assert runner.context_runner.main.__defaults__ == (None,)
    assert "native-CONTEXT-" in (DIRECTORY / "run_guest_context_usb.py").read_text()
    assert "GUEST_CONTEXT_PASS" in (DIRECTORY / "run_guest_context_usb.py").read_text()


def test_config_errors_bitmasks_and_contradictory_actual_observations() -> None:
    good = _document()
    good["config"]["EGL_RENDERABLE_TYPE"] = 13
    good["config"]["EGL_SURFACE_TYPE"] = 1
    assert runner.validate_output(_raw(good), RUN, 0, True, server_identity=IDENTITY)[0] == "PROFILE_MATRIX_MEASURED"
    for key in ("bindError", "chooseError"):
        bad = copy.deepcopy(good)
        bad["config"][key] = 0x3009
        assert runner.validate_output(_raw(bad), RUN, 0, True, server_identity=IDENTITY)[0] == "UNKNOWN"
    bad = copy.deepcopy(good)
    bad["contextQualified"] = True
    assert runner.validate_output(_raw(bad), RUN, 0, True, server_identity=IDENTITY)[0] == "UNKNOWN"


def test_profile_capture_seam_records_profile_name_without_running_device(tmp_path, monkeypatch) -> None:
    context = runner.context_runner
    monkeypatch.setattr(context.shared, "_external_parent", lambda value: tmp_path)
    monkeypatch.setattr(context.source_runner, "load_candidate", lambda *, candidate: ({}, [], b"server"))
    monkeypatch.setattr(context.guest_loader, "load_candidate", lambda: ({}, [], b"guest"))
    monkeypatch.setattr(context.shared, "remote_status_preflight", lambda: {})
    monkeypatch.setattr(context.shared, "package_preflight", lambda: (runner.UID, f"/data/user/0/{runner.PACKAGE}", "u:r:debug:s0"))
    monkeypatch.setattr(context.shared, "own_processes", lambda uid: [])
    monkeypatch.setattr(context.source_runner, "copy_payload", lambda files, run, *, candidate: (context.PRIVATE_PREFIX + "/" + run, {"clean": True}))
    monkeypatch.setattr(context, "shared_server_placeholder", None, raising=False)
    monkeypatch.setattr(context.shared, "server_arguments", lambda mode: [])
    monkeypatch.setattr(context.shared, "_shell_command", lambda *args: ["server"])
    monkeypatch.setattr(context.shared, "bind_server_instance", lambda *args: IDENTITY)
    monkeypatch.setattr(context, "_write", lambda *args: None)
    monkeypatch.setattr(context.shared, "_terminate_held", lambda processes: True)

    class Process:
        _next = 300
        def __init__(self, command, **kwargs):
            self.command = command
            self.stdout = io.BytesIO(b"" if command == ["native"] else context.shared.SERVER_STDOUT_CAPS_SEQUENCE)
            self.stderr = io.BytesIO(b"" if command == ["native"] else b"WFVTEST_ANDROID_DEFAULT gl.vendor=Mesa\nWFVTEST_ANDROID_DEFAULT gl.renderer=virgl\nWFVTEST_ANDROID_DEFAULT gl.version=1\n")
            self.returncode = 0
            self.pid = Process._next
            Process._next += 1
        def wait(self, timeout=None): return self.returncode
        def poll(self): return self.returncode

    monkeypatch.setattr(context.subprocess, "Popen", Process)
    result = context.main(
        ["--output", str(tmp_path)], probe_loader=lambda: {"destination": "guest/bin/GuestEglProfiles", "bytes": 1, "sha256": "a" * 64, "mode": "0700"},
        payload_copier=lambda *args, **kwargs: (context.PRIVATE_PREFIX + "/" + RUN, {"clean": True}),
        native_command_builder=lambda root, run: ["native"], classifier=lambda *args, **kwargs: ("PROFILE_MATRIX_MEASURED", []),
        native_name="GuestEglProfiles", output_prefix="native-PROFILES-", pass_status="PROFILE_MATRIX_MEASURED",
        boundary="profile matrix only",
    )
    assert result == 0
    evidence = next(tmp_path.glob("native-PROFILES-*"))
    capture = json.loads((evidence / "capture.json").read_text())
    assert capture["nativeArtifact"]["name"] == "GuestEglProfiles"
    summary = json.loads((evidence / "summary.json").read_text())
    assert summary["passStatus"] == "PROFILE_MATRIX_MEASURED"
    assert "contextExitCode" not in summary and summary["nativeExitCode"] == 0
    assert "eglContextVerified" not in summary
