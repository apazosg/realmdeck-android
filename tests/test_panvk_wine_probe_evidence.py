"""Offline-only fixtures for the bounded Wine/PanVK evidence analyzer."""
import importlib.util
import json
from pathlib import Path

import pytest


MODULE_PATH = Path(__file__).parents[1] / "scripts/diagnostics/android-panvk/analyze_wine_probe.py"
spec = importlib.util.spec_from_file_location("panvk_wine_evidence", MODULE_PATH)
analyzer = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(analyzer)

RUN = "RUN-fixture-20261008"
DRIVER = "/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-wine/RUN-fixture-20261008/libvulkan_panfrost.so"
PE = "/storage/emulated/0/Android/data/com.wowforever.arm64probe/CustomGames/WoW Forever/GraphicsProbe-ARM64.exe"


def _maps(*paths):
    return "\n".join(f"1000-2000 r-xp 00000000 fe:01 42 {path}" for path in paths) + "\n"


def make_evidence(tmp_path, *, maps=None, rows=None, driver=True, final=True, graphics=True,
                  probe=None, preflight=None):
    root = tmp_path / "evidence"
    root.mkdir()
    probe = analyzer.LEGACY_PROBE if probe is None else probe
    if driver:
        (root / "driver-identity.json").write_text(json.dumps({
            "schema": 1, "runId": RUN, "path": DRIVER, "size": analyzer.DRIVER_SIZE,
            "sha256": analyzer.DRIVER_SHA, "uid": 10330, "mode": "0600",
            "beforeAfterStable": True,
        }) + "\n")
    (root / "probe-identity.json").write_text(json.dumps({"schema": 1, "runId": RUN,
        "file": probe.file, "size": probe.size, "sha256": probe.sha256}) + "\n")
    if preflight is not None:
        (root / "preflight.json").write_text(json.dumps({
            "schema": 1, "version": {"code": preflight[0], "name": preflight[1]},
        }) + "\n")
    if final:
        (root / "private-run-final.json").write_text(json.dumps({
            "schema": 1, "runId": RUN, "rootPid": 100, "rootStartTicks": 10,
        }) + "\n")
    records = rows or [
        {"pid": 200, "ppid": 100, "sample": 0, "uid_ps": 10330, "uid": 10330,
         "uid_before": [10330] * 4, "uid_after": [10330] * 4,
         "stat_before": {"pid": 200, "start_ticks": 20}, "stat_after": {"pid": 200, "start_ticks": 20}},
        {"pid": 100, "ppid": 1, "sample": 0, "uid_ps": 10330, "uid": 10330,
         "uid_before": [10330] * 4, "uid_after": [10330] * 4,
         "stat_before": {"pid": 100, "start_ticks": 10}, "stat_after": {"pid": 100, "start_ticks": 10}},
    ]
    (root / "process-snapshots.jsonl").write_text("".join(json.dumps(row) + "\n" for row in records))
    probe_path = PE.replace(analyzer.LEGACY_PROBE.file, probe.file)
    (root / "maps-0-200-1.txt").write_text(_maps(probe_path, DRIVER) if maps is None else maps)
    (root / "maps-0-100-1.txt").write_text(_maps("/system/lib64/libvulkan.so"))
    if graphics:
        (root / "graphics-probe.jsonl").write_text(
            json.dumps({"schema": 1, "runId": RUN, "event": "probe_begin", "status": "STARTED"}) + "\n" +
            json.dumps({"schema": 1, "runId": RUN, "event": "probe_end", "status": "DEVICE_REJECTED",
                        "successfulPresents": 0, "d3dTriangleResult": False, "logDurable": True,
                        "stdoutMirrored": False}) + "\n")
    return root


def result(root):
    return analyzer.analyze_evidence(root, RUN)


def readback_log(status="PASS", *, mutate=None, order=None):
    phases = order or ["offscreen_clear", "offscreen_draw", "backbuffer_clear", "backbuffer_draw"]
    rows = [{"schema": 1, "runId": RUN, "event": "probe_begin", "status": "STARTED", "variant": "readback-v1"},
             {"schema": 1, "runId": RUN, "event": "readback_window", "status": "READY", "marker": "modules-d3d-shaders-ready"},
             {"schema": 1, "runId": RUN, "event": "readback_window", "status": "RESUMED", "marker": "modules-d3d-shaders-ready"},
            {"schema": 1, "runId": RUN, "event": "device_created", "featureLevel": "11_0"}]
    for phase in phases:
        clear = phase.endswith("clear")
        row = {"schema": 1, "runId": RUN, "event": "triangle_readback" if phase == "backbuffer_draw" else "readback",
               "phase": phase, "status": "VERIFIED", "textureResult": "0x00000000", "rtvResult": "0x00000000",
               "stagingResult": "0x00000000", "mapResult": "0x00000000", "copyResult": "VOID_SUBMITTED",
               "pDataValid": True, "rowPitch": 1280, "width": 320, "height": 240,
               "clearPixels": 76800 if clear else 70000, "coloredPixels": 0 if clear else 700,
               "rgbaFnv1a": 1, "samples": [{"x": x, "y": y, "rgba": [10, 10, 20, 255]}
                                          for x, y in ((0, 0), (160, 120), (319, 239), (80, 60), (240, 180))],
               "deviceRemovedReason": "0x00000000"}
        if mutate and phase == mutate.get("phase"):
            row.update(mutate)
        rows.append(row)
    rows.append({"schema": 1, "runId": RUN, "event": "device_removed_reason", "result": "0x00000000"})
    rows.extend({"schema": 1, "runId": RUN, "event": "present", "index": index,
                 "result": "0x00000000", "successful": True} for index in range(1, 31))
    rows.append({"schema": 1, "runId": RUN, "event": "probe_end", "status": status, "variant": "readback-v1",
                 "successfulPresents": 30, "d3dTriangleResult": True, "offscreenClear": True, "offscreenDraw": True,
                 "backbufferClear": True, "backbufferDraw": True, "apiQualificationObserved": True, "logDurable": True})
    return "".join(json.dumps(row) + "\n" for row in rows)


def test_positive_old_metadata_shape_requires_same_process_pe_and_icd(tmp_path):
    item = result(make_evidence(tmp_path))
    assert item["status"] == "DEVICE_REJECTED"
    assert item["driverMappingObserved"] is True
    assert item["androidNativeWSIAcceptanceNotReused"] is True
    assert item["wow"] is False


def test_readback_tuple_requires_coherent_phases_and_keeps_visual_out_of_scope(tmp_path):
    root = make_evidence(tmp_path, probe=analyzer.READBACK_PROBE, preflight=(55, "0.2.31-automated"))
    (root / "graphics-probe.jsonl").write_text(readback_log())
    item = result(root)
    assert item["status"] == "INCOMPLETE"
    assert item["reportedStatus"] == "PASS"
    assert item["apiQualificationObserved"] is True
    assert item["offscreenReadbackObserved"] is True
    assert item["backbufferReadbackObserved"] is True
    assert item["visualPresentationObserved"] is False and item["wow"] is False

    (tmp_path / "bad-map").mkdir()
    bad = make_evidence(tmp_path / "bad-map", probe=analyzer.READBACK_PROBE, preflight=(55, "0.2.31-automated"))
    (bad / "graphics-probe.jsonl").write_text(readback_log(mutate={"phase": "backbuffer_draw", "mapResult": "0x80004005", "pDataValid": False, "copyResult": "NOT_ATTEMPTED"}))
    assert result(bad)["status"] == "CONTROL_INVALID"

    (tmp_path / "reordered").mkdir()
    reordered = make_evidence(tmp_path / "reordered", probe=analyzer.READBACK_PROBE, preflight=(55, "0.2.31-automated"))
    (reordered / "graphics-probe.jsonl").write_text(readback_log(order=["offscreen_clear", "offscreen_clear", "offscreen_draw", "backbuffer_clear", "backbuffer_draw"]))
    assert result(reordered)["status"] == "CONTROL_INVALID"


def test_new_compiler_log_probe_tuple_is_selected_from_preflight(tmp_path):
    item = result(make_evidence(tmp_path, probe=analyzer.COMPILER_LOG_PROBE,
                                preflight=(51, "0.2.27-automated")))
    assert item["status"] == "DEVICE_REJECTED"
    assert item["expectedProbe"] == {
        "file": "GraphicsProbeLog-ARM64.exe", "size": 90112,
        "sha256": "795acd6d878d6def7139566da6040b16bf2ee6a2a9edfb90d16d4af32a67ab9a",
    }
    assert item["driverMappingObserved"] is True


@pytest.mark.parametrize("key", ["textureResult", "rtvResult", "stagingResult", "deviceRemovedReason"])
def test_verified_readback_rejects_error_hresult(tmp_path, key):
    root = make_evidence(tmp_path, probe=analyzer.READBACK_PROBE, preflight=(55, "0.2.31-automated"))
    (root / "graphics-probe.jsonl").write_text(readback_log(mutate={"phase": "offscreen_draw", key: "0x80004005"}))
    item = result(root)
    assert item["status"] == "CONTROL_INVALID"
    assert item["apiQualificationObserved"] is False


@pytest.mark.parametrize("samples", [[], [{"x": -1, "y": 0, "rgba": [10, 10, 20, 255]}] * 5])
def test_readback_requires_the_five_pinned_sample_points(tmp_path, samples):
    root = make_evidence(tmp_path, probe=analyzer.READBACK_PROBE, preflight=(55, "0.2.31-automated"))
    (root / "graphics-probe.jsonl").write_text(readback_log(mutate={"phase": "backbuffer_draw", "samples": samples}))
    assert result(root)["status"] == "CONTROL_INVALID"


@pytest.mark.parametrize("status", ["FAILED", "DEVICE_REJECTED"])
def test_early_readback_failure_is_retained_without_claiming_phases(tmp_path, status):
    root = make_evidence(tmp_path, probe=analyzer.READBACK_PROBE, preflight=(55, "0.2.31-automated"))
    rows = [{"schema": 1, "runId": RUN, "event": "probe_begin", "status": "STARTED", "variant": "readback-v1"},
            {"schema": 1, "runId": RUN, "event": "probe_end", "status": status,
             "successfulPresents": 0, "d3dTriangleResult": False, "logDurable": True}]
    (root / "graphics-probe.jsonl").write_text("".join(json.dumps(row) + "\n" for row in rows))
    item = result(root)
    assert item["status"] == status
    assert not item["apiQualificationObserved"] and not item["offscreenReadbackObserved"]


def test_legacy_positive_readback_remains_api_only_and_not_offscreen(tmp_path):
    root = make_evidence(tmp_path, probe=analyzer.COMPILER_LOG_PROBE, preflight=(54, "0.2.30-automated"))
    rows = [{"schema": 1, "runId": RUN, "event": "probe_begin", "status": "STARTED"},
            {"schema": 1, "runId": RUN, "event": "device_created", "featureLevel": "11_1"},
            {"schema": 1, "runId": RUN, "event": "triangle_readback", "status": "VERIFIED",
             "result": "0x00000000", "coloredPixels": 700, "rgbaFnv1a": 1, "width": 320, "height": 240}]
    rows.extend({"schema": 1, "runId": RUN, "event": "present", "index": index,
                 "result": "0x00000000", "successful": True} for index in range(1, 31))
    rows.append({"schema": 1, "runId": RUN, "event": "probe_end", "status": "PASS",
                 "d3dTriangleResult": True, "successfulPresents": 30, "logDurable": True})
    (root / "graphics-probe.jsonl").write_text("".join(json.dumps(row) + "\n" for row in rows))
    item = result(root)
    assert item["status"] == "INCOMPLETE" and item["reportedStatus"] == "PASS"
    assert item["apiQualificationObserved"] and item["backbufferReadbackObserved"]
    assert not item["offscreenReadbackObserved"] and not item["visualPresentationObserved"]


def test_budget_timeout_preserves_readback_without_claiming_gpu_or_api_success(tmp_path):
    root = make_evidence(tmp_path, probe=analyzer.READBACK_PROBE, preflight=(55, "0.2.31-automated"))
    rows = [json.loads(line) for line in readback_log().splitlines()]
    rows = [row for row in rows if row["event"] not in {"present", "device_removed_reason"}]
    rows.insert(-1, {"schema": 1, "runId": RUN, "event": "probe_budget", "status": "TIMEOUT"})
    rows[-1].update(status="FAILED", successfulPresents=0, d3dTriangleResult=False)
    (root / "graphics-probe.jsonl").write_text("".join(json.dumps(row) + "\n" for row in rows))
    item = result(root)
    assert item["status"] == "FAILED"
    assert not item["apiQualificationObserved"] and not item["visualPresentationObserved"]


@pytest.mark.parametrize("contradiction", ["budget-timeout", "late-device-reason"])
def test_readback_api_rejects_timeout_pass_and_late_health_witness(tmp_path, contradiction):
    root = make_evidence(tmp_path, probe=analyzer.READBACK_PROBE, preflight=(55, "0.2.31-automated"))
    rows = [json.loads(line) for line in readback_log().splitlines()]
    if contradiction == "budget-timeout":
        rows.insert(-1, {"schema": 1, "runId": RUN, "event": "probe_budget", "status": "TIMEOUT"})
    else:
        reason = next(row for row in rows if row["event"] == "device_removed_reason")
        rows.remove(reason)
        rows.insert(-1, reason)
    (root / "graphics-probe.jsonl").write_text("".join(json.dumps(row) + "\n" for row in rows))
    item = result(root)
    assert item["status"] == "CONTROL_INVALID" and not item["apiQualificationObserved"]


def test_foreign_full_probe_identity_path_is_rejected_with_valid_maps(tmp_path):
    root = make_evidence(tmp_path, probe=analyzer.COMPILER_LOG_PROBE,
                         preflight=(51, "0.2.27-automated"))
    descriptor = json.loads((root / "probe-identity.json").read_text())
    descriptor["path"] = "/foreign/GraphicsProbeLog-ARM64.exe"
    (root / "probe-identity.json").write_text(json.dumps(descriptor) + "\n")
    item = result(root)
    assert item["status"] == "CONTROL_INVALID"
    assert item["driverMappingObserved"] is True


def test_conflicting_second_probe_identity_descriptor_is_rejected_with_valid_maps(tmp_path):
    root = make_evidence(tmp_path, probe=analyzer.COMPILER_LOG_PROBE,
                         preflight=(51, "0.2.27-automated"))
    (root / "graphics-probe-identity.json").write_text(json.dumps({
        "schema": 1, "runId": RUN, "file": analyzer.LEGACY_PROBE.file,
        "size": analyzer.LEGACY_PROBE.size, "sha256": analyzer.LEGACY_PROBE.sha256,
    }) + "\n")
    item = result(root)
    assert item["status"] == "CONTROL_INVALID"
    assert item["driverMappingObserved"] is True


def test_preflight_and_typed_probe_mismatch_is_control_invalid(tmp_path):
    item = result(make_evidence(tmp_path, probe=analyzer.LEGACY_PROBE,
                                preflight=(51, "0.2.27-automated")))
    assert item["status"] == "CONTROL_INVALID"
    assert item["probeIdentityObserved"] is False


def test_selected_probe_rejects_other_approved_probe_mapping_in_same_process(tmp_path):
    new_path = PE.replace(analyzer.LEGACY_PROBE.file, analyzer.COMPILER_LOG_PROBE.file)
    item = result(make_evidence(
        tmp_path,
        probe=analyzer.COMPILER_LOG_PROBE,
        preflight=(51, "0.2.27-automated"),
        maps=_maps(new_path, PE, DRIVER),
    ))
    assert item["status"] == "CONTROL_INVALID"
    assert any("foreign" in error for error in item["errors"])


def test_missing_new_probe_identity_stays_incomplete_and_keeps_selected_pin(tmp_path):
    root = make_evidence(tmp_path, probe=analyzer.COMPILER_LOG_PROBE,
                         preflight=(51, "0.2.27-automated"))
    (root / "probe-identity.json").unlink()
    item = result(root)
    assert item["status"] == "INCOMPLETE"
    assert item["expectedProbe"]["file"] == "GraphicsProbeLog-ARM64.exe"


def test_legitimate_non_executable_segments_are_not_foreign_driver(tmp_path):
    maps = _maps(PE, DRIVER) + f"3000-4000 r--p 00002000 fe:01 42 {DRIVER}\n" + f"4000-5000 rw-p 00003000 fe:01 42 {DRIVER}\n"
    item = result(make_evidence(tmp_path, maps=maps))
    assert item["status"] == "DEVICE_REJECTED"
    assert item["driverMappingObserved"] is True
    assert item["visualPresentationObserved"] is False


def test_non_hex_driver_identity_integer_types_are_rejected(tmp_path):
    root = make_evidence(tmp_path)
    path = root / "driver-identity.json"
    item = json.loads(path.read_text())
    item["size"] = float(item["size"])
    path.write_text(json.dumps(item))
    assert result(root)["status"] == "CONTROL_INVALID"


def test_missing_probe_pin_and_changed_parent_do_not_qualify(tmp_path):
    root = make_evidence(tmp_path)
    (root / "probe-identity.json").unlink()
    assert result(root)["status"] == "INCOMPLETE"
    (root / "probe-identity.json").write_text(json.dumps({"schema": 1, "runId": RUN,
        "file": analyzer.PROBE_NAME, "size": analyzer.PROBE_SIZE, "sha256": analyzer.PROBE_SHA}))
    rows = [json.loads(line) for line in (root / "process-snapshots.jsonl").read_text().splitlines()]
    rows[0]["stat_before"]["ppid"] = 100
    rows[0]["stat_after"]["ppid"] = 999
    (root / "process-snapshots.jsonl").write_text("".join(json.dumps(row) + "\n" for row in rows))
    assert result(root)["status"] == "CONTROL_INVALID"


def test_host_deadline_unknown_is_not_a_graphics_result(tmp_path):
    root = make_evidence(tmp_path)
    (root / "trial-result.json").write_text(json.dumps({"schema": 1, "runId": RUN,
        "status": "UNKNOWN", "error": "absolute host trial deadline reached"}))
    assert result(root)["status"] == "UNKNOWN"
    assert result(root)["reportedStatus"] == "DEVICE_REJECTED"


@pytest.mark.parametrize("bad_maps, expected", [
    (_maps(PE, DRIVER.replace("RUN-fixture-20261008", "RUN-old")), "CONTROL_INVALID"),
    (_maps("/system/lib64/libvulkan.so"), "CONTROL_INVALID"),
    (_maps(DRIVER), "CONTROL_INVALID"),
    (_maps(PE, DRIVER + " (deleted)"), "CONTROL_INVALID"),
])
def test_mapping_negatives_never_become_gpu_unsupported(tmp_path, bad_maps, expected):
    item = result(make_evidence(tmp_path, maps=bad_maps))
    assert item["status"] == expected
    assert item["wow"] is False


def test_stale_uid_or_start_ticks_is_invalid(tmp_path):
    root = make_evidence(tmp_path)
    row = json.loads((root / "process-snapshots.jsonl").read_text().splitlines()[0])
    row["uid_after"][3] = 1000
    (root / "process-snapshots.jsonl").write_text(json.dumps(row) + "\n")
    assert result(root)["status"] == "CONTROL_INVALID"


def test_ambiguous_ancestry_is_unknown(tmp_path):
    root = make_evidence(tmp_path)
    rows = [json.loads(line) for line in (root / "process-snapshots.jsonl").read_text().splitlines()]
    rows.append({**rows[1], "stat_before": {"pid": 100, "start_ticks": 11},
                 "stat_after": {"pid": 100, "start_ticks": 11}})
    (root / "process-snapshots.jsonl").write_text("".join(json.dumps(row) + "\n" for row in rows))
    assert result(root)["status"] == "UNKNOWN"


def test_pass_without_genuine_witnesses_is_not_pass(tmp_path):
    root = make_evidence(tmp_path)
    (root / "graphics-probe.jsonl").write_text(
        json.dumps({"schema": 1, "runId": RUN, "event": "probe_begin", "status": "STARTED"}) + "\n" +
        json.dumps({"schema": 1, "runId": RUN, "event": "probe_end", "status": "PASS",
                    "successfulPresents": 30, "d3dTriangleResult": True, "logDurable": True}) + "\n")
    assert result(root)["status"] == "CONTROL_INVALID"


def test_unpinned_probe_descriptor_is_control_invalid(tmp_path):
    root = make_evidence(tmp_path)
    (root / "probe-identity.json").write_text(json.dumps({
        "schema": 1, "runId": RUN, "file": "GraphicsProbe-ARM64.exe", "size": 1, "sha256": "0" * 64,
    }) + "\n")
    assert result(root)["status"] == "CONTROL_INVALID"


@pytest.mark.parametrize("descriptor", [
    b'{"schema":1,"runId":"' + RUN.encode() + b'","file":"GraphicsProbeLog-ARM64.exe","file":"GraphicsProbeLog-ARM64.exe","size":90112,"sha256":"795acd6d878d6def7139566da6040b16bf2ee6a2a9edfb90d16d4af32a67ab9a"}\n',
    {"schema": 1, "runId": RUN, "file": "GraphicsProbeLog-ARM64.exe",
     "name": "GraphicsProbe-ARM64.exe", "size": 90112,
     "sha256": "795acd6d878d6def7139566da6040b16bf2ee6a2a9edfb90d16d4af32a67ab9a"},
])
def test_probe_identity_duplicate_or_conflicting_fields_are_invalid(tmp_path, descriptor):
    root = make_evidence(tmp_path, probe=analyzer.COMPILER_LOG_PROBE,
                         preflight=(51, "0.2.27-automated"))
    path = root / "probe-identity.json"
    path.write_bytes(descriptor if isinstance(descriptor, bytes) else (json.dumps(descriptor) + "\n").encode())
    assert result(root)["status"] == "CONTROL_INVALID"


@pytest.mark.parametrize("name, content", [
    ("graphics-probe.jsonl", b"{}\n"),
    ("process-snapshots.jsonl", b"{" + b"x" * analyzer.MAX_META + b"}\n"),
])
def test_truncated_or_bounded_sources_are_incomplete(tmp_path, name, content):
    root = make_evidence(tmp_path)
    (root / name).write_bytes(content)
    assert result(root)["status"] == "INCOMPLETE"


def test_duplicate_json_and_missing_files_are_safe(tmp_path):
    root = make_evidence(tmp_path)
    (root / "graphics-probe.jsonl").write_bytes(
        b'{"schema":1,"schema":1,"runId":"' + RUN.encode() + b'","event":"probe_begin","status":"STARTED"}\n'
        b'{"schema":1,"runId":"' + RUN.encode() + b'","event":"probe_end","status":"UNKNOWN"}\n')
    assert result(root)["status"] == "INCOMPLETE"
    (root / "graphics-probe.jsonl").unlink()
    assert result(root)["status"] == "INCOMPLETE"
