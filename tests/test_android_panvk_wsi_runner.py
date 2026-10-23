"""Offline tests for the PanVK WSI host supervisor; no ADB or phone is used."""
from __future__ import annotations

import importlib.util
import json
import struct
import zlib
from pathlib import Path

import pytest


SCRIPT = Path(__file__).parents[1] / "scripts/diagnostics/android-panvk/run_wsi_usb.py"
SPEC = importlib.util.spec_from_file_location("panvk_wsi_runner", SCRIPT)
assert SPEC and SPEC.loader
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)

RUN = "PWSI-0123456789abcdef0123456789abcdef"
ROOT = f"/data/user/0/{runner.PACKAGE}/files/forever/controls/panvk-wsi"
ICD = f"{ROOT}/{RUN}/{runner.ICD_NAME}"


def state() -> dict[str, object]:
    return {
        "schema": runner.SCHEMA, "version": 1, "runId": RUN, "generation": 7,
        "pid": 321, "startTicks": 99, "uid": runner.UID, "processName": runner.PROCESS_NAME,
        "rootPath": ROOT, "icdPath": ICD, "assetSize": runner.ICD_BYTES, "assetSha256": runner.ICD_SHA256,
        "status": "READY", "available": True, "surfaceWidth": 320, "surfaceHeight": 240,
        "holderWidth": 320, "holderHeight": 240, "bounds": [0, 100, 320, 340],
    }


def native() -> dict[str, object]:
    owner = ICD
    frames = [{"slot": i, "imageIndex": i % 2, "acquireCalled": True, "acquireResult": 0,
               "submitCalled": True, "submitResult": 0, "presentCalled": True, "presentResult": 0,
               "fenceCalled": True, "fenceResult": 0} for i in range(3)]
    return {
        "schema": runner.SCHEMA, "version": 1, "runId": RUN, "pid": 321, "uid": runner.UID,
        "generation": 7, "startNs": 10, "endNs": 20, "clockValid": True, "scopeValid": True,
        "procValid": True, "loadAttempted": True, "loadLoaded": True, "icdPath": ICD,
        "icdBytes": runner.ICD_BYTES, "icdSha256": runner.ICD_SHA256, "icdAfterSha256": runner.ICD_SHA256,
        "afterSHA": runner.ICD_SHA256, "icdIdentityStable": True, "icdOwner": owner,
        "icdOwnerBefore": owner, "icdOwnerAfter": owner,
        "surface": {"fromSurface": True, "windowOwned": True, "width": 320, "height": 240, "createResult": 0, "created": True},
        "instance": {"extensionCount": 2, "createResult": 0, "created": True},
        "physical": {"count": 1, "vendorId": 0x13B5, "deviceId": 0xA8670000, "name": "Mali-G610 MC6"},
        "queue": {"familyCount": 1, "familyIndex": 0, "flags": 1, "surfaceSupport": True},
        "device": {"extensionCount": 1, "createResult": 0, "created": True},
        "surfaceCapabilities": {"minImageCount": 1, "maxImageCount": 4, "minExtent": [1, 1], "maxExtent": [4096, 4096], "currentExtent": [320, 240], "usage": 16, "transforms": 1, "alpha": 1},
        "swapchain": {"createResult": 0, "imageCount": 2, "format": 37, "colorSpace": 0, "extentWidth": 320, "extentHeight": 240, "preTransform": 1, "compositeAlpha": 1},
        "formatCount": 1, "modeCount": 1, "frames": {"count": 3, "max": 3, "presentSuccessCount": 3, "items": frames},
        "apiPresentSuccess": True,
        "counts": {"calls": 1, "callCapacity": 96, "journalRows": 3, "journalRowCapacity": 256, "journalBytes": 0, "journalByteCapacity": 65536, "presentSuccess": 3, "physical": 1, "queueFamilies": 1, "deviceExtensions": 1},
        "pfns": [{"name": "vkCreateInstance", "ownerExact": True, "path": owner}],
        "calls": [{"callStage": "vkCreateInstance", "result": 0}], "cancelRequested": False,
        "cleanup": {"queueWaitIdleCalled": True, "queueRetired": True, "queueWaitIdleResult": 0, "complete": True, "unknownResourceRetained": False},
        "journal": {"rows": 3, "rowCapacity": 256, "bytes": 0, "byteCapacity": 65536, "ioFailed": False},
        "androidPresentationObserved": False, "wine": False, "d3d": False, "wow": False,
        "status": "API_PRESENT_COMPLETED", "failureStage": "none", "failureDetail": "", "nativeExit": 0,
    }


def raw(value: dict[str, object]) -> bytes:
    return (json.dumps(value, separators=(",", ":")) + "\n").encode()


def png(width: int, height: int, pixel) -> bytes:
    channels = 3
    rows = []
    for y in range(height):
        row = bytearray([0])
        for x in range(width):
            row.extend(pixel(x, y))
        rows.append(bytes(row))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(b"".join(rows))) + chunk(b"IEND", b"")


def test_state_requires_positive_start_ticks_and_exact_process() -> None:
    assert runner.validate_state(state(), RUN, qualification=True)["runId"] == RUN
    for key, value in (("startTicks", 0), ("processName", "com.other"), ("uid", 1000)):
        bad = state(); bad[key] = value
        with pytest.raises(ValueError):
            runner.validate_state(bad, RUN, qualification=True)


def test_native_success_binds_surface_queue_frames_and_cleanup() -> None:
    parsed = runner.parse_native(raw(native()), RUN, state())
    assert parsed["apiPresentSuccess"] is True
    assert parsed["frames"]["presentSuccessCount"] == 3


@pytest.mark.parametrize("change", [
    lambda d: d["frames"]["items"].reverse(),
    lambda d: d["pfns"][0].update(path="/other/lib.so"),
    lambda d: d["queue"].update(familyCount=33),
    lambda d: d["swapchain"].update(imageCount=1),
    lambda d: d["frames"]["items"][0].update(imageIndex=2),
])
def test_native_success_rejects_witness_tampering(change) -> None:
    document = native(); change(document)
    with pytest.raises(ValueError):
        runner.parse_native(raw(document), RUN, state())


def test_failed_native_result_and_null_partial_metadata_are_not_gpu_passes() -> None:
    document = native()
    document["apiPresentSuccess"] = False
    document["status"] = "VK_ERROR"
    document["nativeExit"] = 1
    document["surface"] = None
    document["frames"] = None
    parsed = runner.parse_native(raw(document), RUN, state())
    assert parsed["status"] == "VK_ERROR"
    assert parsed["apiPresentSuccess"] is False


def test_journal_is_bounded_and_bound_to_same_run() -> None:
    rows = []
    for event, result in (("start", None), ("before", None), ("after", 0)):
        rows.append({"schema": runner.SCHEMA, "version": 1, "runId": RUN, "pid": 321, "generation": 7,
                     "event": event, "callStage": "vkCreateInstance", "result": result})
    journal_raw = ("\n".join(json.dumps(row, separators=(",", ":")) for row in rows) + "\n").encode()
    document = native()
    document["journal"]["rows"] = 3
    document["journal"]["bytes"] = len(journal_raw)
    document["counts"]["journalRows"] = 3
    document["counts"]["journalBytes"] = len(journal_raw)
    parsed = runner.parse_native(raw(document), RUN, state())
    evidence = runner.validate_journal(journal_raw, RUN, state(), parsed)
    assert evidence["rows"] == 3
    rows[1]["runId"] = "PWSI-" + "f" * 32
    with pytest.raises(ValueError):
        runner.validate_journal(("\n".join(json.dumps(row) for row in rows) + "\n").encode(), RUN, state(), parsed)


def test_png_roi_accepts_black_ready_and_red_green_triangle() -> None:
    black = png(320, 240, lambda _x, _y: (0, 0, 0))
    after = png(320, 240, lambda x, y: (255, 0, 0) if x < 160 and y < 120 and x < y * 4 / 3 else (0, 255, 0))
    before = runner.analyze_png(black, [0, 0, 320, 240])
    visual = runner.analyze_png(after, [0, 0, 320, 240])
    assert before["blackRatio"] >= 0.95
    assert visual["redRatio"] >= 0.10 and visual["greenRatio"] >= 0.10


@pytest.mark.parametrize("bad", [b"", b"not-png", b"\x89PNG\r\n\x1a\n\x00"])
def test_png_truncated_or_corrupt_is_incomplete(bad: bytes) -> None:
    with pytest.raises(ValueError):
        runner.analyze_png(bad, [0, 0, 320, 240])


def test_png_roi_and_dimensions_are_bounded() -> None:
    image = png(320, 240, lambda _x, _y: (0, 0, 0))
    with pytest.raises(ValueError):
        runner.analyze_png(image, [1, 0, 321, 240])
    with pytest.raises(ValueError):
        runner.analyze_png(png(1, 1, lambda _x, _y: (0, 0, 0)), [0, 0, 320, 240])


def test_commands_are_fixed_to_foreground_activity_and_owned_package() -> None:
    assert runner.COMPONENT == f"{runner.PACKAGE}/app.gamenative.PanvkWsiActivity"
    assert runner.PROCESS_NAME == f"{runner.PACKAGE}:panvk_wsi"
    assert runner.activity_command() == ("shell", "am", "start", "-W", "-n", runner.COMPONENT)
    assert runner.activity_command(True)[-3:] == ("--ez", "start", "true")


def test_store_actual_wrapper_and_nonzero_global_surface_origin() -> None:
    actual = state()
    validated = runner.validate_state(actual, RUN, qualification=True)
    assert validated["bounds"] == [0, 100, 320, 340]
    assert validated["icdPin"]["path"] == ICD
    wrapper = {"schema": runner.SCHEMA, "version": 1, "runId": RUN, "generation": 7, "pid": 321, "uid": runner.UID,
               "status": "RESULT", "androidPresentationObserved": False, "native": native()}
    proc = {"pid": 321, "startTicks": 99}
    retained, result = runner.parse_result_wrapper(raw(wrapper), RUN, actual, proc)
    assert retained == wrapper and result["apiPresentSuccess"] is True
    wrapper["generation"] = 8
    with pytest.raises(ValueError):
        runner.parse_result_wrapper(raw(wrapper), RUN, actual, proc)


def test_journal_missing_ordered_pair_rejected() -> None:
    document = native()
    rows = [{"schema": runner.SCHEMA, "version": 1, "runId": RUN, "pid": 321, "generation": 7,
             "event": "after", "callStage": "vkCreateInstance", "result": 0}]
    journal_raw = raw(rows[0])
    document["counts"].update(journalRows=1, journalBytes=len(journal_raw))
    document["journal"].update(rows=1, bytes=len(journal_raw))
    with pytest.raises(ValueError):
        runner.validate_journal(journal_raw, RUN, state(), document)


def test_secure_but_unlocked_is_not_a_permission_bypass(monkeypatch) -> None:
    import subprocess
    monkeypatch.setattr(runner, "adb", lambda *args, **kwargs: subprocess.CompletedProcess(args, 0, b"secure=true\nshowing=false\n", b""))
    assert runner._keyguard() == {"secure": True, "locked": False}


def test_result_wrapper_rejects_boolean_identity() -> None:
    wrapper = {"schema": runner.SCHEMA, "version": 1, "runId": RUN, "generation": True, "pid": 321, "uid": runner.UID,
               "status": "RESULT", "androidPresentationObserved": False, "native": native()}
    with pytest.raises(ValueError):
        runner.parse_result_wrapper(raw(wrapper), RUN, state(), {"pid": 321, "startTicks": 99})


@pytest.mark.parametrize("block,key,value", [("surface", "width", 320.0), ("surface", "createResult", False), ("swapchain", "format", 37.0), ("frames", "count", 3.0), ("cleanup", "queueWaitIdleResult", False)])
def test_success_requires_exact_numeric_witness_types(block, key, value) -> None:
    document = native()
    document[block][key] = value
    with pytest.raises(ValueError):
        runner.parse_native(raw(document), RUN, state())


def test_icd_size_float_is_not_a_typed_native_witness() -> None:
    document = native()
    document["icdBytes"] = float(runner.ICD_BYTES)
    with pytest.raises(ValueError):
        runner.parse_native(raw(document), RUN, state())


def test_journal_boolean_version_is_not_a_schema_integer() -> None:
    row = {"schema": runner.SCHEMA, "version": True, "runId": RUN, "pid": 321, "generation": 7,
           "event": "start", "callStage": "start", "result": None}
    with pytest.raises(ValueError):
        runner.validate_journal(raw(row), RUN, state(), native())


def test_marker_status_hint_can_lag_atomic_state_without_changing_identity(monkeypatch) -> None:
    marker = {"runId": RUN, "generation": 7, "pid": 321, "uid": runner.UID, "status": "STARTING"}
    actual = state()
    actual["status"] = "RUNNING"
    values = iter([raw(marker), raw(actual), raw({**marker, "status": "RUNNING"})])
    monkeypatch.setattr(runner, "_read_owned", lambda *args: next(values))
    assert runner.read_state(RUN)["status"] == "RUNNING"


def test_native_canonical_owner_is_the_same_pinned_app_run_not_another_path() -> None:
    document = native()
    actual = ICD.replace("/data/user/0/", "/data/data/")
    for key in ("icdOwner", "icdOwnerBefore", "icdOwnerAfter"):
        document[key] = actual
    document["pfns"][0]["path"] = actual
    assert runner.parse_native(raw(document), RUN, state())["icdOwner"] == actual
    document["icdOwnerAfter"] = actual.replace("/panvk-wsi/", "/elsewhere/")
    with pytest.raises(ValueError):
        runner.parse_native(raw(document), RUN, state())
