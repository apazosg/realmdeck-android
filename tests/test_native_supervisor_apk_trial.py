"""Offline contract tests for the native-only APK59 collector.

Every device/USB seam here is fake; importing this module must not discover a
device, open libusb, install an APK, or start an Activity.
"""
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import xml.etree.ElementTree as ET

import pytest


@pytest.fixture
def trial():
    path = Path(__file__).parents[1] / "scripts/diagnostics/android-process/run_native_supervisor_apk.py"
    spec = importlib.util.spec_from_file_location("native_supervisor_apk_trial", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _event(m, run, nonce, seq, phase, name, role, status, pid, ppid, *, start=10, exit_code=-1, children=0, echild=False):
    values = {
        "schema": 1, "runId": run, "nonce": nonce, "seq": seq, "phase": phase,
        "event": name, "pid": pid, "startTicks": start, "ppid": ppid,
        "uid0": m.UID, "uid1": m.UID, "uid2": m.UID, "uid3": m.UID,
        "role": role, "status": status, "exitCode": exit_code,
        "childCount": children, "echild": echild, "setResult": -1,
        "getResult": -1, "getValue": -1,
    }
    return values


def _fixture(m, scenario="normal", *, bad=None):
    run = "NS-" + "a" * 32
    nonce = "b" * 32
    supervisor, intermediate = 100, 101
    grandchild = 102
    phase_events = []
    seq = 2
    phase_events.append(_event(m, run, nonce, seq, 1, "SPAWN", "intermediate", "RUNNING", intermediate, supervisor, start=11, children=1)); seq += 1
    phase_events.append(_event(m, run, nonce, seq, 1, "SPAWN", "grandchild", "RUNNING", grandchild, intermediate, start=12, children=2)); seq += 1
    phase_events.append(_event(m, run, nonce, seq, 1, "ADOPT", "grandchild", "RUNNING", grandchild, supervisor, start=12, children=1)); seq += 1
    phase_events.append(_event(m, run, nonce, seq, 1, "EXIT", "intermediate", "REAPED", intermediate, supervisor, start=11, exit_code=0)); seq += 1
    phase_events.append(_event(m, run, nonce, seq, 1, "EXIT", "grandchild", "REAPED", grandchild, supervisor, start=12, exit_code=0)); seq += 1
    phase_events.append(_event(m, run, nonce, seq, 1, "PHASE_DONE", "supervisor", "PASS", supervisor, 99, start=10, children=0, echild=True)); seq += 1
    events = [_event(m, run, nonce, 1, 0, "READY", "supervisor", "READY", supervisor, 99, start=10)]
    events[0].update(setResult=0, getResult=0, getValue=1)
    events.extend(phase_events)
    if scenario == "normal":
        second = []
        for item in phase_events[:-1]:
            clone = dict(item)
            clone["seq"] = seq
            clone["phase"] = 2
            if clone["role"] == "intermediate":
                clone["pid"] = 103; clone["startTicks"] = 13; clone["ppid"] = supervisor
            elif clone["role"] == "grandchild":
                clone["pid"] = 104; clone["startTicks"] = 14
                if clone["event"] == "SPAWN": clone["ppid"] = 103
                else: clone["ppid"] = supervisor
            second.append(clone); seq += 1
        done = dict(phase_events[-1]); done["seq"] = seq; done["phase"] = 2; second.append(done); seq += 1
        events.extend(second)
        events.append(_event(m, run, nonce, seq, 0, "RUN_DONE", "supervisor", "PASS", supervisor, 99, start=10, children=0, echild=True))
        intents = ["RUN", "START", "ACK", "ACK", "ACK", "START", "ACK", "ACK", "ACK", "CLOSE"]
        status, native_exit = "PASS", 0
    elif scenario == "cancel_fork":
        events = [events[0], events[1], events[2], events[4], events[3], events[5]]
        for event_seq, item in enumerate(events, 1):
            item["seq"] = event_seq
        events[3]["exitCode"] = 125
        events[5]["exitCode"] = 143
        events.append(_event(m, run, nonce, 7, 0, "RUN_DONE", "supervisor", "CANCELLED", supervisor, 99, start=10, children=0, echild=True))
        intents = ["RUN", "START", "ACK", "STOP"]
        status, native_exit = "CANCELLED", 0
    elif scenario == "cancel_before_start":
        events = [events[0], _event(m, run, nonce, 2, 0, "RUN_DONE", "supervisor", "CANCELLED", supervisor, 99, start=10, children=0, echild=True)]
        intents = ["RUN", "CLOSE"]
        status, native_exit = "CANCELLED", 0
    else:
        intents = ["RUN", "START"]
        status, native_exit = "UNKNOWN", 2
    if bad == "uid": events[1]["uid3"] = 7
    if bad == "ppid": events[1]["ppid"] = 777
    if bad == "nonzero":
        for item in events:
            if item["event"] == "EXIT": item["exitCode"] = 9
    if bad == "missing-exit": events = [item for item in events if not (item["event"] == "EXIT" and item["role"] == "grandchild")]
    if bad == "nonce": events[0]["nonce"] = "c" * 32
    if bad == "sequence": events[1]["seq"] = 9
    identities = []
    seen = set()
    for item in events:
        if item["role"] == "witness": continue
        key = (item["role"], item["pid"], item["startTicks"], item["ppid"])
        if key not in seen:
            seen.add(key)
            identities.append({"kind": item["role"], "pid": item["pid"], "startTicks": item["startTicks"], "ppid": item["ppid"], "uid0": m.UID, "uid1": m.UID, "uid2": m.UID, "uid3": m.UID})
    identities.append({"kind": "witness", "pid": 200, "startTicks": 20, "ppid": 99, "uid0": m.UID, "uid1": m.UID, "uid2": m.UID, "uid3": m.UID})
    run_record = {"schema": 1, "variant": "native-run-supervisor-v2", "runId": run, "nonce": nonce,
                  "scenario": scenario, "status": status, "nativeExit": native_exit,
                  "witnessProof": {"status": "PASS", "pid": 200, "startTicks": 20, "stableBeforeQ": True, "qSent": True, "reaped": True, "exit": 0},
                  "identities": identities, "echild": True}
    records = []
    for seq_no, intent in enumerate(intents):
        phase = (0 if intent in {"RUN", "CLOSE"} else
                 2 if scenario == "normal" and seq_no >= 5 else 1)
        records.append({"kind": "intent", "intent": intent, "phase": phase, "seq": seq_no})
    # Interleaving is immaterial to the two independently monotonic streams.
    supervisor_marker = {"kind": "process_create_intent", "role": "supervisor", "runId": run, "nonce": nonce}
    witness_marker = {"kind": "process_create_intent", "role": "witness", "runId": run, "nonce": nonce}
    event_raw = (json.dumps(records[0], separators=(",", ":")) + "\n").encode()
    event_raw += (json.dumps(supervisor_marker, separators=(",", ":")) + "\n").encode()
    event_raw += (json.dumps({"kind": "event", **events[0]}, separators=(",", ":")) + "\n").encode()
    event_raw += b"".join((json.dumps({"kind": "event", **item}, separators=(",", ":")) + "\n").encode() for item in events[1:])
    event_raw += (json.dumps(witness_marker, separators=(",", ":")) + "\n").encode()
    event_raw += b"".join((json.dumps(item, separators=(",", ":")) + "\n").encode() for item in records[1:])
    witness = json.dumps(_event(m, run, nonce, 1, 0, "READY", "witness", "READY", 200, 99, start=20), separators=(",", ":")).encode() + b"\n"
    return run_record, event_raw, witness


def test_cancel_fork_requires_reap_then_adopt_with_complete_identity_proof(trial):
    run, raw, witness = _fixture(trial, "cancel_fork")
    assert trial.qualify_native(run, raw, witness, "cancel_fork")["outcome"] == "CANCELLED"
    rows = [json.loads(line) for line in raw.splitlines()]
    positions = [i for i, item in enumerate(rows) if item.get("kind") == "event"]
    first_exit, adoption = positions[3], positions[4]
    rows[first_exit], rows[adoption] = rows[adoption], rows[first_exit]
    for seq, index in enumerate(positions, 1):
        rows[index]["seq"] = seq
    invalid = b"".join((json.dumps(row) + "\n").encode() for row in rows)
    assert trial.qualify_native(run, invalid, witness, "cancel_fork")["outcome"] == "UNKNOWN"
    run, raw, witness = _fixture(trial, "cancel_fork", bad="missing-exit")
    assert trial.qualify_native(run, raw, witness, "cancel_fork")["outcome"] == "UNKNOWN"


@pytest.mark.parametrize("bad", ["uid", "ppid", "nonce", "sequence", "missing-exit", "nonzero"])
def test_normal_never_qualifies_tampered_transcript(trial, bad):
    run, events, witness = _fixture(trial, bad=bad)
    result = trial.qualify_native(run, events, witness, "normal")
    assert result["outcome"] != "GO"


@pytest.mark.parametrize("index,field,value", [
    (1, "status", "PASS"), (3, "status", "READY"),
    (6, "role", "intermediate"), (6, "status", "UNKNOWN"),
    (-1, "role", "grandchild"), (1, "setResult", 0),
    (3, "getValue", 1), (6, "exitCode", 0),
    (1, "pid", 1 << 31), (1, "startTicks", 1 << 63),
])
def test_semantically_invalid_ipc_cannot_qualify(trial, index, field, value):
    run, raw, witness = _fixture(trial)
    rows = [json.loads(line) for line in raw.splitlines()]
    events = [row for row in rows if row["kind"] == "event"]
    events[index][field] = value
    altered = b"".join((json.dumps(row) + "\n").encode() for row in rows)
    assert trial.qualify_native(run, altered, witness, "normal")["outcome"] != "GO"


def test_ready_witness_receipt_is_not_positive_cleanup(trial):
    run, raw, witness = _fixture(trial)
    run["witnessProof"]["status"] = "READY"
    assert trial.qualify_native(run, raw, witness, "normal")["outcome"] != "GO"


@pytest.mark.parametrize("index", [4, 6, -1])
def test_process_identity_crosslinks_remain_exact(trial, index):
    run, raw, witness = _fixture(trial)
    rows = [json.loads(line) for line in raw.splitlines()]
    event = [row for row in rows if row["kind"] == "event"][index]
    event["startTicks"] += 100
    # Even a correspondingly fabricated recorded identity cannot substitute for
    # the birth/READY identity held earlier in this same transcript.
    run["identities"].append({"kind": event["role"], **{
        key: event[key] for key in ("pid", "startTicks", "ppid", "uid0", "uid1", "uid2", "uid3")}})
    altered = b"".join((json.dumps(row) + "\n").encode() for row in rows)
    assert trial.qualify_native(run, altered, witness, "normal")["outcome"] != "GO"


def test_normal_strong_evidence_is_native_only_go(trial):
    run, events, witness = _fixture(trial)
    result = trial.qualify_native(run, events, witness, "normal", app_status="PASS")
    assert result == {
        "reportedNativeStatus": "PASS", "reportedAppStatus": "PASS",
        "apiQualification": False, "visualQualification": False, "wowQualification": False,
        "nativeFixtureQualification": "GO", "outcome": "GO",
    }


@pytest.mark.parametrize("mutation", [
    lambda run: {**run, "status": "UNKNOWN"},
    lambda run: {**run, "scenario": "cancel_fork"},
    lambda run: {**run, "witnessProof": {}},
])
def test_terminal_current_and_run_must_match_typed_proof(trial, mutation):
    run, _events, _witness = _fixture(trial)
    current = mutation(run)
    assert not trial.terminal_semantic_equal(current, run)


def test_terminal_current_change_after_evidence_is_not_stable(trial):
    run, _events, _witness = _fixture(trial)
    assert trial.terminal_evidence_stable(run, run, run, run)
    changed = {**run, "status": "UNKNOWN"}
    assert not trial.terminal_evidence_stable(run, run, changed, changed)
    changed_nonce = {**run, "nonce": "c" * 32}
    assert not trial.terminal_evidence_stable(run, run, changed_nonce, changed_nonce)


@pytest.mark.parametrize("scenario", ["eof", "invalid_protocol", "supervisor_failure"])
def test_fault_scenarios_cannot_be_global_pass(trial, scenario):
    run, events, witness = _fixture(trial)
    run["status"] = "PASS"; run["nativeExit"] = 0
    result = trial.qualify_native(run, events, witness, scenario, app_status="UNKNOWN")
    assert result["outcome"] == "INVALID"
    assert result["nativeFixtureQualification"] == "INVALID"
    assert result["reportedAppStatus"] == "UNKNOWN"


def test_cancel_contracts_are_distinct_and_cancel_fork_requires_born_history(trial):
    before, events, witness = _fixture(trial, "cancel_before_start")
    assert trial.qualify_native(before, events, witness, "cancel_before_start")["outcome"] == "CANCELLED"
    fork, events, witness = _fixture(trial, "cancel_fork")
    assert trial.qualify_native(fork, events, witness, "cancel_fork")["outcome"] == "CANCELLED"
    fork["status"] = "PASS"
    assert trial.qualify_native(fork, events, witness, "cancel_fork")["outcome"] != "GO"


def test_duplicate_fields_and_line_cap_fail_closed(trial):
    duplicate = b'{"kind":"event","kind":"event"}\n'
    with pytest.raises(trial.CollectorError):
        trial.parse_events(duplicate, "NS-" + "a" * 32, "b" * 32)
    with pytest.raises(trial.CollectorError):
        trial.parse_events(b"{" + b"x" * trial.LINE_CAP + b"}\n", "NS-" + "a" * 32, "b" * 32)


@pytest.mark.parametrize("mutation", ["missing", "duplicate", "bad-role", "bad-order"])
def test_process_create_markers_are_exact_and_ordered(trial, mutation):
    run, raw, _witness = _fixture(trial)
    rows = [json.loads(line) for line in raw.splitlines()]
    markers = [row for row in rows if row.get("kind") == "process_create_intent"]
    if mutation == "missing":
        rows.remove(markers[1])
    elif mutation == "duplicate":
        rows.insert(2, dict(markers[0]))
    elif mutation == "bad-role":
        markers[1]["role"] = "fixture"
    else:
        witness_index = rows.index(markers[1])
        rows.insert(witness_index, {"kind": "intent", "intent": "START", "phase": 1, "seq": 1})
    altered = b"".join((json.dumps(row, separators=(",", ":")) + "\n").encode() for row in rows)
    with pytest.raises(trial.CollectorError):
        trial.parse_events(altered, run["runId"], run["nonce"])


def test_new_run_guard_and_historical_nonterminal(trial):
    old = {"runId": "NS-" + "a" * 32, "nonce": "b" * 32}
    assert not trial._new_run_id(old, old)
    assert not trial._new_run_id(old, {"runId": "NS-" + "b" * 32, "nonce": "b" * 32})
    assert trial._new_run_id(old, {"runId": "NS-" + "b" * 32, "nonce": "c" * 32})
    assert "RUNNING" in trial.NON_TERMINAL_STATUSES


def _ready_preflight(trial, *, legacy=None, before="11111111-1111-1111-1111-111111111111",
                     after="22222222-2222-2222-2222-222222222222"):
    return {
        "result": "READY_FOR_SINGLE_APPROVED_TRIAL", "serial": trial.SERIAL,
        "package": trial.PACKAGE, "versionCode": trial.VERSION_CODE,
        "versionName": trial.VERSION_NAME, "installedApkSha256": "a" * 64,
        "retainedArtifactsVerified": True, "bootBeforeReboot": before,
        "bootAfterReboot": after, "ownUidProcessSnapshot": "UID PID PPID NAME\n10330 1 0 ps\n",
        "legacyNative56": trial.LEGACY_NATIVE56 if legacy is None else legacy,
        "legacyNative57": trial.LEGACY_NATIVE57,
    }


@pytest.mark.parametrize("mutation", [
    lambda value: {**value, "runId": "NS-" + "0" * 32},
    lambda value: {**value, "status": "PASS"},
    lambda value: {**value, "nativeExit": 0},
    lambda value: {**value, "currentSha256": "0" * 64},
])
def test_preflight_rejects_stale_or_unbound_legacy_receipt(trial, tmp_path, mutation):
    value = mutation(trial.LEGACY_NATIVE56)
    record = _ready_preflight(trial, legacy=value)
    path = tmp_path / "state-00-READY_FOR_SINGLE_APPROVED_TRIAL.json"
    path.write_text(json.dumps(record), encoding="utf-8")
    with pytest.raises(trial.CollectorError, match="legacy native56 receipt"):
        trial._preflight(tmp_path, "a" * 64)


def test_preflight_rejects_legacy_boot_reuse_and_missing_history(trial, tmp_path):
    record = _ready_preflight(trial, after=trial.LEGACY_NATIVE56["lastControlBootId"])
    path = tmp_path / "state-00-READY_FOR_SINGLE_APPROVED_TRIAL.json"
    path.write_text(json.dumps(record), encoding="utf-8")
    with pytest.raises(trial.CollectorError, match="post-reboot boot"):
        trial._preflight(tmp_path, "a" * 64)


def test_preflight_accepts_first_migration_reboot_from_legacy_boot(trial, tmp_path):
    record = _ready_preflight(trial, before=trial.LEGACY_NATIVE56["lastControlBootId"])
    path = tmp_path / "state-00-READY_FOR_SINGLE_APPROVED_TRIAL.json"
    path.write_text(json.dumps(record), encoding="utf-8")
    assert trial._preflight(tmp_path, "a" * 64)["record"] == record
    path.write_text(json.dumps(_ready_preflight(trial, legacy={})), encoding="utf-8")
    with pytest.raises(trial.CollectorError, match="legacy native56 receipt"):
        trial._preflight(tmp_path, "a" * 64)


def _dynamic_receipts(trial, run, boot):
    disposition = {
        "schema": 1, "kind": "PRELAUNCH_ABORT", "variant": trial.NATIVE_SUPERVISOR_VARIANT,
        "priorRunId": trial.LEGACY_NATIVE57["runId"], "priorNonce": trial.LEGACY_NATIVE57["nonce"],
        "priorStatus": "UNKNOWN", "priorCurrentSha256": trial.LEGACY_NATIVE57["currentSha256"],
        "priorRunSha256": trial.HISTORICAL_NATIVE57_RUN_FILES["run.json"][1],
        "priorEventsSha256": trial.HISTORICAL_NATIVE57_RUN_FILES["events.jsonl"][1],
        "apk57Sha256": trial.LEGACY_NATIVE57_APK_SHA256,
        "attemptBootId": trial.LEGACY_NATIVE57["attemptBootId"], "freshBootId": boot,
        "processCreate": "NOT_REACHED_BY_PINNED_SOURCE", "echild": False,
        "sourceSha256": trial.PRELAUNCH_DISPOSITION_SHA256,
        "sealSha256": trial.LEGACY_NATIVE57["originalSealSha256"],
        "priorStreamPresence": {name: "MISSING" for name in sorted(trial.HISTORICAL_NATIVE57_ABSENT_STREAMS)},
    }
    disposition_raw = (json.dumps(disposition, separators=(",", ":")) + "\n").encode()
    consumption = {
        "schema": 1, "kind": "RESERVED_PRELAUNCH_CONSUMPTION",
        "priorRunId": trial.LEGACY_NATIVE57["runId"], "newRunId": run["runId"],
        "newNonce": run["nonce"], "newScenario": run["scenario"], "freshBootId": boot,
        "dispositionSha256": trial.sha256_bytes(disposition_raw),
        "sourceSha256": trial.PRELAUNCH_DISPOSITION_SHA256,
        "firstAdmittedRunId": run["runId"],
    }
    consumption_raw = (json.dumps(consumption, separators=(",", ":")) + "\n").encode()
    return disposition_raw, consumption_raw, consumption


def test_first59_historical_reader_requires_all_three_receipts_missing(trial, monkeypatch):
    current_raw = b"old-current"
    run = {"runId": trial.LEGACY_NATIVE57["runId"], "nonce": trial.LEGACY_NATIVE57["nonce"]}
    files = {"run.json": b"x", "events.jsonl": b"x"}
    files.update({name: None for name in trial.HISTORICAL_NATIVE57_ABSENT_STREAMS})
    def private(_adb, path, _cap):
        if path == trial.CURRENT_DEVICE:
            return current_raw, "ok"
        if path in trial.LEGACY_NATIVE57_RECEIPT_PATHS:
            return None, "missing"
        raise AssertionError(path)
    monkeypatch.setattr(trial, "_device_private", private)
    monkeypatch.setattr(trial, "sha256_bytes", lambda _raw: trial.LEGACY_NATIVE57["currentSha256"])
    monkeypatch.setattr(trial, "_validate_historical57_current", lambda _raw: {
        "runId": trial.LEGACY_NATIVE57["runId"], "nonce": trial.LEGACY_NATIVE57["nonce"],
    })
    monkeypatch.setattr(trial, "_history_snapshot", lambda *_args: (current_raw, files))
    monkeypatch.setattr(trial, "frozen_prelaunch_disposition", lambda: (_ for _ in ()).throw(AssertionError("asset used as receipt")))
    _current, archived, _sha = trial._read_historical_native57(object())
    assert archived["stdout"] is None and archived["witness-stderr"] is None
    assert archived["prelaunch-disposition.json"] is None
    assert archived["prelaunch-consumption.json"] is None


@pytest.mark.parametrize("mode", ["valid", "live-unknown", "partial", "mutated-archive", "reused-legacy-run"])
def test_historical_reader_distinguishes_live_subsequent_current_from_archive(trial, monkeypatch, mode):
    run = {"runId": "NS-" + "c" * 32, "nonce": "d" * 32, "scenario": "normal"}
    boot = "33333333-3333-3333-3333-333333333333"
    disposition, consumption, _value = _dynamic_receipts(trial, run, boot)
    live = {
        "schema": 1, "variant": trial.NATIVE_SUPERVISOR_VARIANT,
        "runId": run["runId"], "nonce": run["nonce"],
        "status": "UNKNOWN" if mode == "live-unknown" else "PASS",
        "nativeExit": 0, "echild": True,
    }
    if mode == "reused-legacy-run":
        live["runId"] = trial.LEGACY_NATIVE57["runId"]
    live_raw = (json.dumps(live, separators=(",", ":")) + "\n").encode()
    archive_raw = b"mutated-archive" if mode == "mutated-archive" else b"archive-old"
    files = {
        "run.json": b"run", "events.jsonl": b"events",
        **{name: None for name in trial.HISTORICAL_NATIVE57_ABSENT_STREAMS},
    }
    values = {
        trial.CURRENT_DEVICE: (live_raw, "ok"),
        f"{trial.LEGACY_NATIVE57_ROOT_DEVICE}/current-before-disposition.json": (archive_raw, "ok"),
        f"{trial.LEGACY_NATIVE57_ROOT_DEVICE}/prelaunch-disposition.json": (disposition, "ok"),
        f"{trial.LEGACY_NATIVE57_ROOT_DEVICE}/prelaunch-consumption.json": (consumption, "ok"),
    }
    if mode == "partial":
        values.pop(f"{trial.LEGACY_NATIVE57_ROOT_DEVICE}/prelaunch-consumption.json")
    monkeypatch.setattr(trial, "_device_private", lambda _adb, path, _cap: values.get(path, (None, "missing")))
    def validate_archive(raw):
        if raw != b"archive-old":
            raise trial.CollectorError("historical native57 current hash changed")
        return trial.LEGACY_NATIVE57
    monkeypatch.setattr(trial, "_validate_historical57_current", validate_archive)
    monkeypatch.setattr(trial, "_history_snapshot", lambda _adb, archive: (archive, files))

    if mode == "valid":
        current, archived, _sha = trial._read_historical_native57(object())
        assert current == live
        assert archived["current-before-disposition.json"] == archive_raw
        assert archived["prelaunch-disposition.json"] == disposition
        assert archived["prelaunch-consumption.json"] == consumption
    else:
        with pytest.raises(trial.CollectorError):
            trial._read_historical_native57(object())


def test_partial_receipt_set_and_frozen_asset_bytes_are_rejected(trial, monkeypatch):
    current_raw = b"old-current"
    def private(_adb, path, _cap):
        if path == trial.CURRENT_DEVICE:
            return current_raw, "ok"
        if path.endswith("/current-before-disposition.json"):
            return None, "missing"
        if path.endswith("/prelaunch-disposition.json"):
            return b"frozen-or-forged", "ok"
        return None, "missing"
    monkeypatch.setattr(trial, "_device_private", private)
    monkeypatch.setattr(trial, "sha256_bytes", lambda _raw: trial.LEGACY_NATIVE57["currentSha256"])
    monkeypatch.setattr(trial, "_validate_historical57_current", lambda _raw: {"runId": trial.LEGACY_NATIVE57["runId"]})
    monkeypatch.setattr(trial, "_history_snapshot", lambda *_args: (current_raw, {
        "run.json": b"x", "events.jsonl": b"x",
        **{name: None for name in trial.HISTORICAL_NATIVE57_ABSENT_STREAMS},
    }))
    with pytest.raises(trial.CollectorError, match="partial"):
        trial._read_historical_native57(object())


def test_dynamic_app_receipt_is_not_required_to_equal_frozen_asset(trial):
    run = {"runId": "NS-" + "c" * 32, "nonce": "d" * 32, "scenario": "normal"}
    boot = "33333333-3333-3333-3333-333333333333"
    disposition, consumption, _value = _dynamic_receipts(trial, run, boot)
    values = {
        f"{trial.LEGACY_NATIVE57_ROOT_DEVICE}/prelaunch-disposition.json": (disposition, "ok"),
        f"{trial.LEGACY_NATIVE57_ROOT_DEVICE}/prelaunch-consumption.json": (consumption, "ok"),
    }
    class Adb: pass
    original = trial._device_private
    trial._device_private = lambda _adb, path, _cap: values[path]
    try:
        assert trial._read_historical_receipts_after(Adb(), run, boot, first_admission=True, persistent_receipt=None) == (disposition, consumption)
    finally:
        trial._device_private = original


@pytest.mark.parametrize("mutation", ["invalid", "partial"])
def test_first_admission_rejects_invalid_or_partial_dynamic_receipts(trial, monkeypatch, mutation):
    run = {"runId": "NS-" + "c" * 32, "nonce": "d" * 32, "scenario": "normal"}
    boot = "33333333-3333-3333-3333-333333333333"
    disposition, consumption, _value = _dynamic_receipts(trial, run, boot)
    if mutation == "invalid":
        value = json.loads(disposition)
        value["sourceSha256"] = "0" * 64
        disposition = (json.dumps(value, separators=(",", ":")) + "\n").encode()
    values = {
        f"{trial.LEGACY_NATIVE57_ROOT_DEVICE}/prelaunch-disposition.json": (disposition, "ok"),
        f"{trial.LEGACY_NATIVE57_ROOT_DEVICE}/prelaunch-consumption.json": (consumption, "ok"),
    }
    if mutation == "partial":
        values.pop(f"{trial.LEGACY_NATIVE57_ROOT_DEVICE}/prelaunch-consumption.json")
    monkeypatch.setattr(trial, "_device_private", lambda _adb, path, _cap: values.get(path, (None, "missing")))
    with pytest.raises(trial.CollectorError):
        trial._read_historical_receipts_after(
            object(), run, boot, first_admission=True, persistent_receipt=None)


@pytest.mark.parametrize("changed", ["prelaunch-disposition.json", "prelaunch-consumption.json"])
def test_first_admission_rejects_receipt_mutation_after_validation(trial, monkeypatch, changed):
    base = {
        "current-before-disposition.json": b"archive",
        "run.json": b"run",
        "events.jsonl": b"events",
        "stdout": None, "stderr": None, "witness-stdout": None, "witness-stderr": None,
        "prelaunch-disposition.json": b"disposition",
        "prelaunch-consumption.json": b"consumption",
    }
    monkeypatch.setattr(trial, "_history_snapshot", lambda _adb: (base["current-before-disposition.json"], {
        name: base[name] for name in trial.HISTORICAL_NATIVE57_RUN_FILES
    } | {name: base[name] for name in trial.HISTORICAL_NATIVE57_ABSENT_STREAMS}))
    changed_path = f"{trial.LEGACY_NATIVE57_ROOT_DEVICE}/{changed}"
    monkeypatch.setattr(trial, "_device_private", lambda _adb, path, _cap: (
        (b"changed", "ok") if path == changed_path else (base[path.rsplit("/", 1)[-1]], "ok")
    ))
    with pytest.raises(trial.CollectorError, match="receipt changed"):
        trial._verify_historical_native57_stable(object(), base, receipts_must_exist=True)


@pytest.mark.parametrize("changed", ["prelaunch-disposition.json", "prelaunch-consumption.json"])
def test_subsequent_receipt_bytes_cannot_rebase_the_baseline(trial, changed):
    first = {"runId": "NS-" + "c" * 32, "nonce": "d" * 32, "scenario": "normal"}
    disposition, consumption, _ = _dynamic_receipts(
        trial, first, "33333333-3333-3333-3333-333333333333")
    baseline = {"prelaunch-disposition.json": disposition,
                "prelaunch-consumption.json": consumption}
    original = dict(baseline)
    rewritten = json.dumps(json.loads(baseline[changed]), indent=2).encode()
    assert rewritten != baseline[changed]
    assert json.loads(rewritten) == json.loads(baseline[changed])
    with pytest.raises(trial.CollectorError, match="receipt changed"):
        trial._bind_historical_receipt_bytes(
            baseline,
            rewritten if changed == "prelaunch-disposition.json" else disposition,
            rewritten if changed == "prelaunch-consumption.json" else consumption,
            first_admission=False)
    assert baseline == original
    trial._bind_historical_receipt_bytes(
        baseline, disposition, consumption, first_admission=False)
    assert baseline == original


def test_first_receipt_binding_requires_missing_baselines(trial):
    baseline = {"prelaunch-disposition.json": None, "prelaunch-consumption.json": None}
    trial._bind_historical_receipt_bytes(baseline, b"disposition", b"consumption", first_admission=True)
    original = dict(baseline)
    with pytest.raises(trial.CollectorError, match="not missing"):
        trial._bind_historical_receipt_bytes(baseline, b"disposition", b"consumption", first_admission=True)
    assert baseline == original


def test_no_run_keeps_historical_receipts_missing(trial, monkeypatch):
    before = {
        "current-before-disposition.json": b"archive",
        "run.json": b"run",
        "events.jsonl": b"events",
        "stdout": None, "stderr": None, "witness-stdout": None, "witness-stderr": None,
        "prelaunch-disposition.json": None,
        "prelaunch-consumption.json": None,
    }
    monkeypatch.setattr(trial, "_history_snapshot", lambda _adb: (b"archive", {
        name: before[name] for name in trial.HISTORICAL_NATIVE57_RUN_FILES
    } | {name: before[name] for name in trial.HISTORICAL_NATIVE57_ABSENT_STREAMS}))
    monkeypatch.setattr(trial, "_device_private", lambda *_args: (None, "missing"))
    trial._verify_historical_native57_stable(object(), before, receipts_must_exist=False)


def test_subsequent_current_binds_preserved_first_admitted_run_not_future_run(trial):
    first = {"runId": "NS-" + "c" * 32, "nonce": "d" * 32, "scenario": "normal"}
    future = {"runId": "NS-" + "e" * 32, "nonce": "f" * 32, "scenario": "cancel_fork"}
    boot = "33333333-3333-3333-3333-333333333333"
    disposition, consumption, value = _dynamic_receipts(trial, first, boot)
    values = {
        f"{trial.LEGACY_NATIVE57_ROOT_DEVICE}/prelaunch-disposition.json": (disposition, "ok"),
        f"{trial.LEGACY_NATIVE57_ROOT_DEVICE}/prelaunch-consumption.json": (consumption, "ok"),
    }
    original = trial._device_private
    trial._device_private = lambda _adb, path, _cap: values[path]
    try:
        assert trial._read_historical_receipts_after( object(), future, "44444444-4444-4444-4444-444444444444",
                                                     first_admission=False, persistent_receipt=value) == (disposition, consumption)
    finally:
        trial._device_private = original


def test_receipt_paths_are_old_run_only_and_not_forgable_root_inputs(trial):
    assert all(path.startswith(trial.LEGACY_NATIVE57_ROOT_DEVICE + "/") for path in trial.LEGACY_NATIVE57_RECEIPT_PATHS)
    with pytest.raises(trial.CollectorError, match="outside"):
        trial._device_private(object(), f"{trial.RUN_ROOT_DEVICE}/prelaunch-disposition.json", 64)


def test_legacy_current_reader_requires_exact_v1_identity_and_hash(trial, monkeypatch):
    raw = json.dumps({"schema": 1, "variant": "native-run-supervisor-v1",
                      "runId": trial.LEGACY_NATIVE56["runId"], "status": "UNKNOWN",
                      "nativeExit": 2}).encode()
    monkeypatch.setattr(trial, "sha256_bytes", lambda _raw: trial.LEGACY_CURRENT_SHA256)
    value = trial._legacy_current_payload(raw)
    assert value["runId"] == trial.LEGACY_NATIVE56["runId"]
    with pytest.raises(trial.CollectorError, match="identity"):
        trial._legacy_current_payload(raw.replace(b"UNKNOWN", b"PASS"))


def test_missing_independent_apk_pin_is_rejected_before_file_access(trial, tmp_path):
    with pytest.raises(trial.CollectorError, match="independent APK hash"):
        trial.validate_apk(tmp_path / "missing.apk", tmp_path / "missing-audit.json", None)


def test_v1_current_and_cross_namespace_records_are_rejected(trial):
    run = {"schema": 1, "variant": "native-run-supervisor-v1", "runId": "NS-" + "a" * 32,
           "nonce": "b" * 32}
    with pytest.raises(trial.CollectorError):
        trial._current_payload(json.dumps(run).encode())
    assert "native-supervisor-v2" in trial.CURRENT_DEVICE
    assert "native-supervisor-v2" in trial.RUN_ROOT_DEVICE
    with pytest.raises(trial.CollectorError, match="outside"):
        trial._device_private(SimpleNamespace(shell=lambda *_args, **_kwargs: None),
                               "files/forever/controls/native-supervisor-v1/current.json", 64)


def test_output_root_and_all_repository_descendants_are_rejected(trial, tmp_path):
    repo = Path(trial.__file__).resolve().parents[3]
    for path in (repo, repo / "docs", repo / "tests"):
        with pytest.raises(trial.CollectorError, match="outside"):
            trial._safe_directory(path, outside_repo=True)
    link = tmp_path / "repo-link"
    link.symlink_to(repo, target_is_directory=True)
    with pytest.raises(trial.CollectorError, match="symlink"):
        trial._safe_directory(link, outside_repo=True)


def test_supervisor_failure_is_rejected_before_any_adb_or_usb(monkeypatch, trial, tmp_path):
    args = SimpleNamespace(scenario="supervisor_failure", apk=tmp_path / "candidate.apk",
                           audit=tmp_path / "audit.json", expected_apk_sha256=None,
                           preflight_directory=tmp_path, output=tmp_path)
    monkeypatch.setattr(trial, "validate_apk", lambda *_args: (_ for _ in ()).throw(AssertionError("ADB/validation reached")))
    monkeypatch.setattr(trial, "AdbClient", lambda: (_ for _ in ()).throw(AssertionError("ADB reached")))
    with pytest.raises(trial.CollectorError, match="HOST/JVM-only"):
        trial.collect(args)


def test_parser_excludes_supervisor_failure_from_physical_cli(trial):
    with pytest.raises(SystemExit):
        trial.build_parser().parse_args(["--apk", "a", "--audit", "b", "--preflight-directory", "c",
                                         "--output", "d", "--scenario", "supervisor_failure"])
    assert trial.PHYSICAL_SCENARIOS == {"normal", "cancel_before_start", "cancel_fork", "eof", "invalid_protocol"}


def test_cli_requires_independent_apk_pin(trial):
    with pytest.raises(SystemExit):
        trial.build_parser().parse_args(["--apk", "a", "--audit", "b", "--preflight-directory", "c",
                                         "--output", "d", "--scenario", "normal"])


def test_fake_adb_deadline_fails_before_subprocess(trial, monkeypatch):
    calls = []
    class Fake:
        def run(self, *args, **kwargs):
            calls.append(args)
            return SimpleNamespace(returncode=0, stdout=b"device", stderr=b"")
    monkeypatch.setattr(trial.time, "monotonic", lambda: 10.0)
    adb = trial.AdbClient(Fake()); adb.deadline = 9.0
    with pytest.raises(trial.CollectorError, match="deadline"):
        adb.run(("get-state",))
    assert calls == []


def test_failed_own_focus_sends_no_hid_input(trial, monkeypatch):
    calls = []
    class FakeHelper:
        class UsbKeyboard:
            def __init__(self, *_args): calls.append("usb-open")
        def wait_for_own_ui(self, *_args): return SimpleNamespace(state=SimpleNamespace())
        def dump_ui(self, *_args):
            root = ET.Element("node", {"package": "foreign.package"})
            return SimpleNamespace(root=root, top_is_own=lambda: False, hierarchy_is_own=lambda: False)
    monkeypatch.setattr(trial, "_helpers", lambda: FakeHelper())
    with pytest.raises(trial.CollectorError):
        trial.drive_consent(SimpleNamespace(raw=object()), "normal", [])
    assert calls == []


@pytest.mark.parametrize("nonsecure", [False, True])
def test_wake_is_only_permitted_after_explicit_nonsecure_policy(trial, monkeypatch, nonsecure):
    calls = []
    class Keyboard:
        def __init__(self, serial): assert serial == trial.SERIAL
        def __enter__(self): return self
        def __exit__(self, *_args): pass
        def key(self, modifier, keycode): calls.append((modifier, keycode))
    helper = SimpleNamespace(policy_is_nonsecure=lambda *_args, **_kwargs: nonsecure, UsbKeyboard=Keyboard)
    monkeypatch.setattr(trial, "_helpers", lambda: helper)
    class Fake:
        raw = object()
        deadline = None
        def shell(self, *args, **kwargs):
            if args == ("dumpsys", "power"):
                return SimpleNamespace(returncode=0, stdout=b"mWakefulness=Awake\n", stderr=b"")
            if args == ("dumpsys", "window", "policy"):
                return SimpleNamespace(returncode=0, stdout=(
                    b"KeyguardServiceDelegate:\n  showing=false\n  secure=false\n"
                ), stderr=b"")
            assert args == ("wm", "dismiss-keyguard")
            calls.append("dismiss-nonsecure")
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")
    evidence = SimpleNamespace(action=lambda *_args, **_kwargs: None)
    if not nonsecure:
        with pytest.raises(trial.CollectorError, match="not explicitly nonsecure"):
            trial.wake_nonsecure(Fake(), evidence)
        assert calls == []
    else:
        trial.wake_nonsecure(Fake(), evidence)
        assert calls == [(2, 0), "dismiss-nonsecure"]


def test_evidence_stream_path_caps_and_private_shape(trial, tmp_path):
    evidence = trial.Evidence(tmp_path, {"schema": 1})
    run, events, _witness = _fixture(trial)
    with pytest.raises(trial.CollectorError, match="exceeded bound"):
        evidence.capture(run, {"events.jsonl": b"x" * (trial.EVENTS_CAP + 1)})
    evidence.capture(run, {"events.jsonl": events})
    native = evidence.native / run["runId"]
    assert native.is_dir() and (native / "events.jsonl").stat().st_mode & 0o777 == 0o600


def test_evidence_actions_keep_event_and_reason_distinct(trial, tmp_path):
    evidence = trial.Evidence(tmp_path, {"schema": 1})
    evidence.action("transcript-missing", reason="no-different-native-run")
    evidence.action("owned-package-force-stop-after-evidence", reason="missing-run-cleanup")
    evidence.action("prelaunch-abort", reason="bounded-control-failure")

    rows = [json.loads(line) for line in (evidence.root / "actions.jsonl").read_text().splitlines()]
    assert [row["action"] for row in rows] == [
        "transcript-missing", "owned-package-force-stop-after-evidence", "prelaunch-abort",
    ]
    assert [row["reason"] for row in rows] == [
        "no-different-native-run", "missing-run-cleanup", "bounded-control-failure",
    ]


def test_prelaunch_abort_finishes_and_seals_with_fake_helper(monkeypatch, trial, tmp_path):
    args = SimpleNamespace(
        scenario="normal", apk=tmp_path / "candidate.apk", audit=tmp_path / "audit.json",
        expected_apk_sha256=None, preflight_directory=tmp_path, output=tmp_path,
    )
    monkeypatch.setattr(trial, "validate_apk", lambda *_args: {
        "sha256": "a" * 64, "manifestSha256": trial.MANIFEST_SHA256,
    })
    monkeypatch.setattr(trial, "_preflight", lambda *_args: {"record": {}})
    monkeypatch.setattr(trial, "validate_device", lambda *_args: {})
    monkeypatch.setattr(trial, "read_current", lambda *_args: (trial.LEGACY_NATIVE57, "ok"))
    monkeypatch.setattr(trial, "read_legacy_current",
                        lambda *_args: (trial.LEGACY_NATIVE56, trial.LEGACY_CURRENT_SHA256))
    monkeypatch.setattr(trial, "_read_historical_native57", lambda *_args: (
        trial.LEGACY_NATIVE57,
        {name: b"" for name in (
            "current-before-disposition.json", "run.json", "events.jsonl", "stdout", "stderr",
            "witness-stdout", "witness-stderr", "prelaunch-disposition.json", "prelaunch-consumption.json",
        )},
        trial.LEGACY_NATIVE57["currentSha256"],
    ))
    monkeypatch.setattr(trial, "_device_private", lambda *_args, **_kwargs: (None, "missing"))

    class FakeAdb:
        def __init__(self):
            self.raw = SimpleNamespace(deadline=None)
            self.deadline = None

        def shell(self, *args, timeout=0):
            if args == ("dumpsys", "power"):
                return SimpleNamespace(returncode=0, stdout=b"mWakefulness=Awake\n", stderr=b"")
            if args == ("dumpsys", "window", "policy"):
                return SimpleNamespace(returncode=0, stdout=(
                    b"KeyguardServiceDelegate:\n  showing=false\n  secure=false\n"
                ), stderr=b"")
            if args == ("wm", "dismiss-keyguard"):
                return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")
            assert args == ("am", "start", "-n", trial.ALIAS, "--es", "scenario", "normal")
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")

    class FakeKeyboard:
        def __init__(self, _serial): pass
        def __enter__(self): return self
        def __exit__(self, *_args): pass
        def key(self, *_args): pass

    class FakeHelper:
        policy_is_nonsecure = lambda self, *_args, **_kwargs: True
        UsbKeyboard = FakeKeyboard

        def send_ui_key(self, *_args): raise AssertionError("consent should not be attempted")

        def wait_for_own_ui(self, _raw):
            return SimpleNamespace(state=None, reason="foreign-top")

    monkeypatch.setattr(trial, "AdbClient", FakeAdb)
    monkeypatch.setattr(trial, "_helpers", lambda: FakeHelper())

    assert trial.collect(args) == 1
    roots = list(tmp_path.glob("native-supervisor-trial-*"))
    assert len(roots) == 1
    actions = [json.loads(line) for line in (roots[0] / "actions.jsonl").read_text().splitlines()]
    assert actions[-1] == {
        "action": "prelaunch-abort", "detail": "own UI readiness failed",
        "reason": "bounded-control-failure",
    }
    seal = json.loads((roots[0] / "seal.json").read_text())
    assert "result.json" in seal["files"] and "actions.jsonl" in seal["files"]


def test_first_admission_reads_receipts_before_postcapture_stability(monkeypatch, trial, tmp_path):
    args = SimpleNamespace(
        scenario="normal", apk=tmp_path / "candidate.apk", audit=tmp_path / "audit.json",
        expected_apk_sha256=None, preflight_directory=tmp_path, output=tmp_path,
    )
    run, events, witness = _fixture(trial)
    boot = "33333333-3333-3333-3333-333333333333"
    disposition, consumption, _value = _dynamic_receipts(trial, run, boot)
    historical_files = {
        "current-before-disposition.json": b"archive",
        "run.json": b"run",
        "events.jsonl": b"events",
        "stdout": None, "stderr": None, "witness-stdout": None, "witness-stderr": None,
        "prelaunch-disposition.json": None, "prelaunch-consumption.json": None,
    }
    phases = []

    monkeypatch.setattr(trial, "validate_apk", lambda *_args: {
        "sha256": "a" * 64, "manifestSha256": trial.MANIFEST_SHA256,
    })
    monkeypatch.setattr(trial, "_preflight", lambda *_args: {"record": {}})
    monkeypatch.setattr(trial, "validate_device", lambda *_args: {"bootId": boot})
    monkeypatch.setattr(trial, "read_legacy_current",
                        lambda *_args: (trial.LEGACY_NATIVE56, trial.LEGACY_CURRENT_SHA256))
    reads = iter([(trial.LEGACY_NATIVE57, "ok"), (run, "ok"), (run, "ok"), (run, "ok")])
    monkeypatch.setattr(trial, "read_current", lambda *_args: next(reads))
    monkeypatch.setattr(trial, "_read_historical_native57", lambda *_args: (
        trial.LEGACY_NATIVE57, dict(historical_files), "a" * 64))
    monkeypatch.setattr(trial, "_read_run_files", lambda *_args: (
        run, {"run.json": json.dumps(run).encode(), "events.jsonl": events,
              "stdout": b"", "stderr": b"", "witness-stdout": witness, "witness-stderr": b""}, "ok"))
    monkeypatch.setattr(trial, "_control_activity", lambda *_args: None)
    monkeypatch.setattr(trial, "_verify_legacy_unchanged", lambda *_args: None)

    values = {
        f"{trial.LEGACY_NATIVE57_ROOT_DEVICE}/prelaunch-disposition.json": (disposition, "ok"),
        f"{trial.LEGACY_NATIVE57_ROOT_DEVICE}/prelaunch-consumption.json": (consumption, "ok"),
        f"{trial.RUN_ROOT_DEVICE}/{run['runId']}/run.json": (
            json.dumps(run).encode(), "ok"),
    }
    monkeypatch.setattr(trial, "_device_private", lambda _adb, path, _cap: values[path])

    original_receipts = trial._read_historical_receipts_after
    def read_receipts(*call_args, **call_kwargs):
        phases.append("receipts")
        return original_receipts(*call_args, **call_kwargs)
    monkeypatch.setattr(trial, "_read_historical_receipts_after", read_receipts)

    def verify(_adb, files, *, receipts_must_exist):
        phases.append("stability")
        assert receipts_must_exist is True
        assert files["prelaunch-disposition.json"] == disposition
        assert files["prelaunch-consumption.json"] == consumption
    monkeypatch.setattr(trial, "_verify_historical_native57_stable", verify)

    class FakeAdb:
        def __init__(self):
            self.raw = SimpleNamespace(deadline=None)
            self.deadline = None
    monkeypatch.setattr(trial, "AdbClient", FakeAdb)

    assert trial.collect(args) == 0
    assert phases == ["receipts", "stability"]
    root = next(tmp_path.glob("native-supervisor-trial-*"))
    saved = root / "historical-native57"
    assert (saved / "prelaunch-disposition.json").read_bytes() == disposition
    assert (saved / "prelaunch-consumption.json").read_bytes() == consumption
    result = json.loads((root / "result.json").read_text())
    assert result["historicalAdmission"]["priorRunId"] == trial.LEGACY_NATIVE57["runId"]


def test_control_activity_uses_one_hid_context_and_never_retries(monkeypatch, trial):
    calls = []
    window_states = [True, False]
    root = ET.Element("hierarchy")
    checkbox = ET.SubElement(root, "node", {
        "package": trial.PACKAGE, "class": "android.widget.CheckBox",
        "text": "I consent to start this native fixture control", "focused": "false",
        "enabled": "true", "checked": "false",
    })
    start = ET.SubElement(root, "node", {
        "package": trial.PACKAGE, "class": "android.widget.Button",
        "text": "START CONTROL", "focused": "false", "enabled": "false",
    })
    ET.SubElement(root, "node", {
        "package": trial.PACKAGE, "class": "android.widget.Button",
        "text": "CANCEL CONTROL", "focused": "false", "enabled": "false",
    })
    state = SimpleNamespace(
        root=root, top_is_own=lambda: True, hierarchy_is_own=lambda: True,
    )

    class Keyboard:
        def __init__(self, _serial): pass
        def __enter__(self): calls.append("hid-enter"); return self
        def __exit__(self, *_args): calls.append("hid-exit")
        def key(self, modifier, keycode): calls.append(("wake", modifier, keycode))

    class Helper:
        UsbKeyboard = Keyboard

        def policy_is_nonsecure(self, *_args, **_kwargs):
            calls.append("policy")
            return True

        def wait_for_own_ui(self, _raw):
            calls.append("readiness")
            return SimpleNamespace(state=state, reason="own-top-ready")

        def dump_ui(self, _raw):
            calls.append("dump")
            return state

        def send_ui_key(self, _keyboard, keycode):
            calls.append(("consent", keycode))
            if keycode == 44:
                checkbox.attrib["checked"] = "true"
            elif keycode == 43:
                if checkbox.attrib["checked"] == "false":
                    checkbox.attrib["focused"] = "true"
                else:
                    checkbox.attrib["focused"] = "false"
                    start.attrib["focused"] = "true"
                    start.attrib["enabled"] = "true"

    class Adb:
        raw = object()
        deadline = None

        def shell(self, *args, **_kwargs):
            calls.append(args[0])
            if args == ("dumpsys", "power"):
                return SimpleNamespace(returncode=0, stdout=b"mWakefulness=Awake\n", stderr=b"")
            if args == ("dumpsys", "window", "policy"):
                showing = b"true" if window_states.pop(0) else b"false"
                return SimpleNamespace(returncode=0, stdout=(
                    b"KeyguardServiceDelegate:\n  showing=" + showing + b"\n  secure=false\n"
                ), stderr=b"")
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")

    monkeypatch.setattr(trial, "_helpers", lambda: Helper())
    monkeypatch.setattr(trial.time, "sleep", lambda seconds: calls.append(("settle", seconds)))
    evidence = SimpleNamespace(action=lambda event, **_fields: calls.append(("evidence", event)))
    journal = []

    trial._control_activity(Adb(), "normal", evidence, journal)

    assert calls.count("hid-enter") == calls.count("hid-exit") == 1
    assert calls.count("am") == 1
    assert calls.index("readiness") > calls.index("am")
    assert calls.index(("settle", 0.15)) < calls.index("wm") < calls.index("am")
    assert calls.count("wm") == 1
    assert calls.count("dumpsys") == 3
    assert [item for item in calls if isinstance(item, tuple) and item[0] == "consent"] == [
        ("consent", 43), ("consent", 44), ("consent", 43), ("consent", 40),
    ]


@pytest.mark.parametrize("failure", ["multiple-focus", "foreign-hierarchy"])
def test_initial_focus_guard_sends_no_input(trial, failure):
    root = ET.Element("hierarchy")
    checkbox = ET.SubElement(root, "node", {
        "package": trial.PACKAGE, "class": "android.widget.CheckBox",
        "text": "I consent to start this native fixture control",
        "focused": "true" if failure == "multiple-focus" else "false",
        "enabled": "true", "checked": "false",
    })
    ET.SubElement(root, "node", {
        "package": trial.PACKAGE, "class": "android.widget.Button",
        "text": "START CONTROL",
        "focused": "true" if failure == "multiple-focus" else "false",
        "enabled": "false",
    })
    ET.SubElement(root, "node", {
        "package": trial.PACKAGE, "class": "android.widget.Button",
        "text": "CANCEL CONTROL", "focused": "false", "enabled": "false",
    })
    own = failure != "foreign-hierarchy"
    state = SimpleNamespace(root=root, top_is_own=lambda: own, hierarchy_is_own=lambda: own)
    calls = []

    class Helper:
        def wait_for_own_ui(self, _raw):
            return SimpleNamespace(state=state, reason="own-top-ready")

        def dump_ui(self, _raw):
            return state

        def send_ui_key(self, *_args):
            calls.append("input")

    trial._helpers = lambda: Helper()
    with pytest.raises(trial.CollectorError):
        trial.drive_consent(SimpleNamespace(raw=object()), "normal", [], keyboard=object())
    assert calls == []


@pytest.mark.parametrize("invalid", ["checked", "checkbox-disabled", "start-enabled", "cancel-enabled"])
def test_invalid_initial_controls_send_no_input(trial, invalid):
    root = ET.Element("hierarchy")
    ET.SubElement(root, "node", {
        "package": trial.PACKAGE, "class": "android.widget.CheckBox",
        "text": "I consent to start this native fixture control", "focused": "false",
        "enabled": "false" if invalid == "checkbox-disabled" else "true",
        "checked": "true" if invalid == "checked" else "false",
    })
    ET.SubElement(root, "node", {
        "package": trial.PACKAGE, "class": "android.widget.Button",
        "text": "START CONTROL", "focused": "false",
        "enabled": "true" if invalid == "start-enabled" else "false",
    })
    ET.SubElement(root, "node", {
        "package": trial.PACKAGE, "class": "android.widget.Button",
        "text": "CANCEL CONTROL", "focused": "false",
        "enabled": "true" if invalid == "cancel-enabled" else "false",
    })
    state = SimpleNamespace(root=root, top_is_own=lambda: True, hierarchy_is_own=lambda: True)
    calls = []

    class Helper:
        def wait_for_own_ui(self, _raw):
            return SimpleNamespace(state=state, reason="own-top-ready")

        def dump_ui(self, _raw):
            return state

        def send_ui_key(self, *_args):
            calls.append("input")

    trial._helpers = lambda: Helper()
    with pytest.raises(trial.CollectorError):
        trial.drive_consent(SimpleNamespace(raw=object()), "normal", [], keyboard=object())
    assert calls == []


def test_wakefulness_poll_accepts_only_a_fresh_awake_state(trial, monkeypatch):
    now = [0.0]
    monkeypatch.setattr(trial.time, "monotonic", lambda: now[0])
    monkeypatch.setattr(trial.time, "sleep", lambda seconds: now.__setitem__(0, now[0] + seconds))
    states = [b"mWakefulness=Asleep\n", b"mWakefulness=Awake\n"]

    class Adb:
        deadline = None

        def shell(self, *args, **_kwargs):
            assert args == ("dumpsys", "power")
            return SimpleNamespace(returncode=0, stdout=states.pop(0), stderr=b"")

    journal = []
    trial._poll_wakefulness_awake(Adb(), journal)
    assert journal == [{"action": "wakefulness", "awake": True}]


@pytest.mark.parametrize("mode", ["timeout", "secure", "contradictory", "late"])
def test_control_state_failure_is_fail_closed_before_activity_or_consent(trial, monkeypatch, mode):
    now = [0.0]
    monkeypatch.setattr(trial.time, "monotonic", lambda: now[0])
    monkeypatch.setattr(trial.time, "sleep", lambda seconds: now.__setitem__(0, now[0] + seconds))
    calls = []

    class Keyboard:
        def __init__(self, _serial): pass
        def __enter__(self): return self
        def __exit__(self, *_args): pass
        def key(self, *_args): calls.append("wake")

    class Helper:
        UsbKeyboard = Keyboard

        def policy_is_nonsecure(self, *_args, **_kwargs):
            return True

    class Adb:
        raw = object()
        deadline = None

        def shell(self, *args, **_kwargs):
            calls.append(args[0])
            if args == ("dumpsys", "power"):
                if mode == "timeout":
                    return SimpleNamespace(returncode=0, stdout=b"mWakefulness=Asleep\n", stderr=b"")
                if mode == "late":
                    now[0] = 2.1
                return SimpleNamespace(returncode=0, stdout=b"mWakefulness=Awake\n", stderr=b"")
            if args == ("wm", "dismiss-keyguard"):
                return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")
            if args == ("dumpsys", "window", "policy"):
                if mode == "secure":
                    raw = b"KeyguardServiceDelegate:\n  showing=false\n  secure=true\n"
                elif mode == "contradictory":
                    raw = b"KeyguardServiceDelegate:\n  showing=false\n  showing=true\n  secure=false\n"
                else:
                    raw = b"KeyguardServiceDelegate:\n  showing=false\n  secure=false\n"
                return SimpleNamespace(returncode=0, stdout=raw, stderr=b"")
            if args[:2] == ("am", "start"):
                calls.append("activity")
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")

    monkeypatch.setattr(trial, "_helpers", lambda: Helper())
    journal = []
    with pytest.raises(trial.CollectorError):
        trial._control_activity(Adb(), "normal", SimpleNamespace(action=lambda *_a, **_k: None), journal)
    assert calls.count("wake") == 1
    assert calls.count("wm") == (0 if mode in {"timeout", "late"} else 1)
    assert "activity" not in calls
    assert all(set(item) <= {"action", "awake", "showing", "secure", "reason"} for item in journal)


@pytest.mark.parametrize("heading", ["KeyguardServiceDelegate", "KeyguardServiceDelegate:"])
def test_keyguard_parser_ignores_unrelated_showing_text_but_rejects_ambiguity(trial, heading):
    raw = (
        b"SomeOtherState: showing=false\n"
        + heading.encode() + b"\n  showing=false\n  secure=false\n"
    )
    assert trial._keyguard_delegate_state(raw) == (False, False)
    with pytest.raises(trial.CollectorError, match="ambiguous"):
        trial._keyguard_delegate_state(
            b"KeyguardServiceDelegate:\n  showing=false\n  secure=false\n"
            b"KeyguardServiceDelegate:\n  showing=false\n  secure=false\n"
        )


def test_late_awake_result_is_not_accepted(trial, monkeypatch):
    now = [0.0]
    monkeypatch.setattr(trial.time, "monotonic", lambda: now[0])

    class Adb:
        deadline = None

        def shell(self, *args, **_kwargs):
            assert args == ("dumpsys", "power")
            now[0] = 2.1
            return SimpleNamespace(returncode=0, stdout=b"mWakefulness=Awake\n", stderr=b"")

    with pytest.raises(trial.CollectorError, match="deadline"):
        trial._poll_wakefulness_awake(Adb(), [])


def test_readiness_reason_is_fixed_and_foreign_detail_is_not_persisted(trial, monkeypatch):
    class Helper:
        def wait_for_own_ui(self, _raw):
            return SimpleNamespace(state=None, reason="native stderr leaked here")

    monkeypatch.setattr(trial, "_helpers", lambda: Helper())
    journal = []
    with pytest.raises(trial.CollectorError, match="own UI readiness failed"):
        trial.drive_consent(SimpleNamespace(raw=object()), "normal", journal, keyboard=object())
    assert journal == [{"action": "readiness-failure", "reason": "unknown"}]
    assert trial._safe_control_detail(trial.CollectorError("native stderr leaked here")) is None


@pytest.mark.parametrize("mode", ["missing", "denied", "ok", "oversized"])
def test_private_reads_require_shell_v2_status(trial, mode):
    path = trial.CURRENT_DEVICE
    class Fake:
        def shell(self, *args, timeout=0):
            if args[:4] == ("-T", "run-as", trial.PACKAGE, "readlink"):
                return SimpleNamespace(returncode=1, stdout=b"", stderr=b"")
            assert args == ("-T", "run-as", trial.PACKAGE, "head", "-c", "17", path)
            if mode == "missing":
                return SimpleNamespace(returncode=1, stdout=b"", stderr=f"head: {path}: No such file or directory\n".encode())
            if mode == "denied":
                return SimpleNamespace(returncode=1, stdout=b"", stderr=b"Permission denied\n")
            return SimpleNamespace(returncode=0, stdout=b"{}" if mode == "ok" else b"x" * 17, stderr=b"")
        def exec_out(self, *_args, **_kwargs):
            raise AssertionError("legacy exec transport must not be used")
    data, status = trial._device_private(Fake(), path, 16)
    assert status == {"missing": "missing", "denied": "unreadable", "ok": "ok", "oversized": "oversized-or-unreadable"}[mode]
    assert data == (b"{}" if mode == "ok" else None)


def test_private_read_genuine_absence_is_distinguished_from_symlinks(trial):
    path = trial.CURRENT_DEVICE
    calls = []

    class Fake:
        def shell(self, *args, timeout=0):
            calls.append(args)
            if args[3] == "readlink":
                return SimpleNamespace(returncode=1, stdout=b"", stderr=b"")
            assert args == ("-T", "run-as", trial.PACKAGE, "head", "-c", "17", path)
            return SimpleNamespace(returncode=1, stdout=b"", stderr=f"head: {path}: No such file or directory\n".encode())

    assert trial._device_private(Fake(), path, 16) == (None, "missing")
    assert calls[-1][3] == "head"


def test_private_read_dangling_leaf_symlink_is_never_classified_missing(trial):
    path = trial.CURRENT_DEVICE
    calls = []

    class Fake:
        def shell(self, *args, timeout=0):
            calls.append(args)
            if args[3] == "readlink":
                if args[4] == path:
                    return SimpleNamespace(returncode=0, stdout=b"missing-target\n", stderr=b"")
                return SimpleNamespace(returncode=1, stdout=b"", stderr=b"")
            raise AssertionError("head must not classify a symlink as absent")

    with pytest.raises(trial.CollectorError, match="symbolic link"):
        trial._device_private(Fake(), path, 16)
    assert all(args[3] == "readlink" for args in calls)


def test_private_read_symlinked_ancestor_is_never_classified_missing(trial):
    path = trial.CURRENT_DEVICE
    ancestor = "files/forever/controls"
    calls = []

    class Fake:
        def shell(self, *args, timeout=0):
            calls.append(args)
            if args[3] == "readlink":
                if args[4] == ancestor:
                    return SimpleNamespace(returncode=0, stdout=b"redirected\n", stderr=b"")
                return SimpleNamespace(returncode=1, stdout=b"", stderr=b"")
            raise AssertionError("head must not classify an ancestor symlink as absent")

    with pytest.raises(trial.CollectorError, match="symbolic link"):
        trial._device_private(Fake(), path, 16)
    assert all(args[3] == "readlink" for args in calls)


@pytest.mark.parametrize("installed_matches", [True, False])
def test_fake_installed_tuple_is_checked_independently(trial, installed_matches):
    expected = "d" * 64
    class Fake:
        def run(self, args, timeout=0): return SimpleNamespace(returncode=0, stdout=b"device\n", stderr=b"")
        def shell(self, *args, timeout=0):
            if args[0] == "getprop":
                values = {"ro.product.device": "xaga", "ro.product.model": "22041216G", "ro.product.manufacturer": "Xiaomi", "ro.build.version.sdk": "34"}
                return SimpleNamespace(returncode=0, stdout=(values[args[1]] + "\n").encode(), stderr=b"")
            if args[0:2] == ("pm", "path"):
                return SimpleNamespace(returncode=0, stdout=b"package:/data/app/com.wowforever.arm64probe/base.apk\n", stderr=b"")
            return SimpleNamespace(returncode=0, stdout=b"versionCode=59 versionName=0.2.35-automated flags=[DEBUGGABLE] signatures:[d7420563]", stderr=b"")
        def exec_out(self, *args, timeout=0):
            if args[-2:] == ("id", "-u"): value = b"10330\n"
            elif any("boot_id" in item for item in args): value = b"11111111-1111-1111-1111-111111111111\n"
            else: value = ((expected if installed_matches else "a" * 64) + " /data/app/com.wowforever.arm64probe/base.apk\n").encode()
            return SimpleNamespace(returncode=0, stdout=value, stderr=b"")
    preflight = {"record": {"bootAfterReboot": "11111111-1111-1111-1111-111111111111"}}
    if not installed_matches:
        with pytest.raises(trial.CollectorError, match="installed base APK hash mismatch"):
            trial.validate_device(Fake(), expected, preflight)
        return
    result = trial.validate_device(Fake(), expected, preflight)
    assert result["apkSha256"] == expected and result["uid"] == trial.UID
    assert result["certificateBinding"] == "exact-installed-audited-apk-sha256"
