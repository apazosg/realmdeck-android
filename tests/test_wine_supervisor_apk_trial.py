"""Offline contract tests for the approved Wine60 host collector.

All device and HID objects in this file are fakes.  Nothing here builds,
installs, starts Wine, or talks to a real ADB serial.
"""
from __future__ import annotations

import hashlib
import copy
import ast
import importlib.util
import json
import os
from pathlib import Path
import shlex
import sys
from types import SimpleNamespace
import xml.etree.ElementTree as ET
import zipfile

import pytest


ROOT = Path(__file__).parents[1]
SOURCE = ROOT / "scripts/diagnostics/android-process/run_wine_supervisor_apk.py"
SPEC = importlib.util.spec_from_file_location("wine60_trial", SOURCE)
assert SPEC and SPEC.loader
wine = importlib.util.module_from_spec(SPEC)
sys.modules["wine60_trial"] = wine
SPEC.loader.exec_module(wine)
HELPER_SOURCE = ROOT / "scripts/diagnostics/android-process/prepare_wine_supervisor_apk.py"
HELPER_SPEC = importlib.util.spec_from_file_location("prepare_wine_supervisor_apk", HELPER_SOURCE)
assert HELPER_SPEC and HELPER_SPEC.loader
helper = importlib.util.module_from_spec(HELPER_SPEC)
sys.modules["prepare_wine_supervisor_apk"] = helper
HELPER_SPEC.loader.exec_module(helper)


def j(value: object) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


def approved_audit(apk: Path) -> dict[str, object]:
    resources = {
        path: {
            "fullSHA256": digest, "size": size,
            "packagedPath": path if path.startswith("lib/") else "assets/" + path,
            "sourceRecipe": "wine-prefix-v1",
        }
        for path, (size, digest) in wine.PREFIX_RESOURCE_PINS.items()
    }
    provenance = {
        "schema": 1, "profile": "server_only", "launchKind": "wine_server_isolation",
        "serverIsolation": True, "protocolSchema": 2, "graphicsProbeEnabled": False,
        "memoryPatching": "DISABLED", "authentication": "DISABLED", "nativeOnly": False,
        "clientBuild": "1.60.1.70009", "runtime": "proton-11.0-90624-arm64ec",
        "inputs": {"contract.json": "c" * 64},
        "sourceBaseline": {
            "manifestSHA256": wine.SOURCE_BASELINE_SHA256, "archiveSHA256": "a" * 64,
            "changedFiles": 0, "historicalRecipeSHA": "historical-source-not-recovered",
        },
        "wineSupervisor": {
            "variant": wine.NATIVE_VARIANT, "manifestSha256": wine.MANIFEST_SHA256,
            "files": {
                name: {"bytes": size, "sha256": digest, "mode": "0755"}
                for name, (size, digest) in wine.NATIVE_PINS.items()
            },
            "recipe": {"file": "build_wine_run_supervisor.sh", "sha256": "b" * 64},
            "source": {"sha256": {"wine_run_supervisor.c": "d" * 64}},
        },
        "resources": resources,
    }
    return {
        "apk": str(apk.resolve()), "result": "PASS", "package": wine.PACKAGE,
        "versionCode": wine.VERSION_CODE, "versionName": wine.VERSION_NAME,
        "certificateSha256": wine.CERTIFICATE_SHA256,
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "provenance": provenance,
    }


def test_independent_apk_hash_is_mandatory_and_audit_path_is_exact(tmp_path: Path) -> None:
    apk = tmp_path / "retained.apk"
    apk.write_bytes(b"approved-apk")
    audit = tmp_path / "audit.json"
    audit.write_bytes(j(approved_audit(apk)))
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    assert wine.validate_apk(apk, audit, digest)["sha256"] == digest
    with pytest.raises(wine.CollectorError, match="mandatory"):
        wine.validate_apk(apk, audit, None)
    forged = approved_audit(apk)
    forged["apk"] = str(tmp_path / "other.apk")
    audit.write_bytes(j(forged))
    with pytest.raises(wine.CollectorError, match="path"):
        wine.validate_apk(apk, audit, digest)


@pytest.mark.parametrize(("field", "value", "message"), [
    ("nativeOnly", True, "server-only"),
    ("profile", "native_fixture", "server-only"),
    ("launchKind", "native_run_supervisor", "server-only"),
])
def test_actual_server_only_provenance_rejects_legacy_metadata(tmp_path: Path, field: str, value: object, message: str) -> None:
    apk = tmp_path / "retained.apk"
    apk.write_bytes(b"approved-apk")
    audit = tmp_path / "audit.json"
    document = approved_audit(apk)
    document["provenance"] = copy.deepcopy(document["provenance"])
    document["provenance"][field] = value  # type: ignore[index]
    audit.write_bytes(j(document))
    with pytest.raises(wine.CollectorError, match=message):
        wine.validate_apk(apk, audit, hashlib.sha256(apk.read_bytes()).hexdigest())


def test_foreign_ui_is_rejected_before_any_input() -> None:
    root = ET.fromstring(
        '<node package="foreign"><node class="android.widget.TextView" text="Forever Wine server isolation"/></node>'
    )

    class State:
        def __init__(self) -> None:
            self.root = root

        def top_is_own(self) -> bool:
            return False

        def hierarchy_is_own(self) -> bool:
            return False

    with pytest.raises(wine.CollectorError, match="foreign"):
        wine._guard_controls(State(), idle=True)


def _event(seq: int, event: str, role: str, phase: str, status: str, pid: int, ticks: int,
           ppid: int, child_count: int, *, exit_code: int = -1, sockets: bool = False) -> dict[str, object]:
    return {
        "schema": 2, "runId": "WS-" + "a" * 32, "nonce": "b" * 32, "seq": seq,
        "event": event, "phase": phase, "pid": pid, "startTicks": ticks, "ppid": ppid,
        "uid0": wine.UID, "uid1": wine.UID, "uid2": wine.UID, "uid3": wine.UID,
        "role": role, "status": status, "exitCode": exit_code, "childCount": child_count,
        "echild": event == "RUN_DONE", "setResult": 0 if event == "READY" else -1,
        "getResult": 0 if event == "READY" else -1, "getValue": 1 if event == "READY" else -1,
        "prefixDev": 11, "prefixIno": 22, "socketDev": 33 if sockets else -1,
        "socketIno": 44 if sockets else -1, "lockDev": 55 if sockets else -1,
        "lockIno": 66 if sockets else -1,
    }


def _positive_bundle() -> tuple[dict[str, bytes], dict[str, bytes], bytes, dict[str, object], dict[str, object]]:
    run_id = "RUN-wine-test"
    ws = "WS-" + "a" * 32
    nonce = "b" * 32
    events = [
        _event(1, "READY", "supervisor", "none", "READY", 100, 1, 1, 0),
        _event(2, "SPAWN", "server", "server", "RUNNING", 101, 2, 100, 1),
        _event(3, "SERVER_READY", "server", "server", "RUNNING", 101, 2, 100, 1, sockets=True),
        _event(4, "SPAWN", "client", "client", "RUNNING", 102, 3, 100, 2),
        _event(5, "EXIT", "client", "client", "REAPED", 102, 3, 100, 1, exit_code=0),
        _event(6, "PHASE_DONE", "client", "client", "PASS", 102, 3, 100, 1),
        _event(7, "EXIT", "server", "server", "REAPED", 101, 2, 100, 0, exit_code=0),
        _event(8, "RUN_DONE", "supervisor", "none", "PASS", 100, 1, 1, 0),
    ]
    event_raw = b"".join(j(item) for item in events)
    witness = {
        "schema": 1, "runId": "NS-" + hashlib.sha256((ws + nonce).encode()).hexdigest()[:32], "nonce": nonce,
        "seq": 1, "phase": 0, "event": "READY", "pid": 200, "startTicks": 9, "ppid": 1,
        "uid0": wine.UID, "uid1": wine.UID, "uid2": wine.UID, "uid3": wine.UID,
        "role": "witness", "status": "READY", "exitCode": -1, "childCount": 0, "echild": False,
        "setResult": -1, "getResult": -1, "getValue": -1, "prctl": -1,
    }
    result = {
        "schema": 1, "runId": run_id, "wsRunId": ws, "nonce": nonce, "terminalStatus": "PASS",
        "nativeStatus": "PASS", "closureObserved": True, "qualification": True, "clientPass": True,
        "supervisorExit": 0, "echild": True,
        "witnessProof": {"status": "PASS", "stableBeforeQ": True, "qSent": True, "reaped": True, "exit": 0},
        "error": None,
    }
    result_raw = j(result)
    marker = {"schema": 1, "state": "COMMITTED", "runId": run_id, "wsRunId": ws, "nonce": nonce,
              "resultSha256": hashlib.sha256(result_raw).hexdigest(), "terminalStatus": "PASS"}
    quarantine = {"schema": 1, "state": "PREPARE", "runId": run_id, "wsRunId": ws, "nonce": nonce, "terminalStatus": "PASS"}
    intents = b"".join(j({"schema": 1, "kind": "process_create_intent", "runId": run_id, "wsRunId": ws, "nonce": nonce, "purpose": purpose}) for purpose in ("prefix-publisher", "supervisor", "witness"))
    bundle = {
        "wine-binding.json": j({"schema": 1, "kind": "wine-binding", "runId": run_id,
                               "wsRunId": ws, "nonce": nonce, "status": "BOUND"}),
        "wine-native-stdout.jsonl": event_raw, "wine-witness-stdout.jsonl": j(witness),
        "wine-process-create-intents.jsonl": intents, "wine-result.json": result_raw,
        "wine-result-commit.json": j(marker), "wine-result-quarantine.json": j(quarantine),
        "wine-result-admission.json": j(marker),
    }
    generic = {"runId": run_id, "status": "EXITED", "terminationUnknown": False, "quiescenceConfirmed": True}
    return bundle, dict(bundle), result_raw, generic, generic


def test_pid_reap_nonce_sequence_and_root_commit_are_fail_closed() -> None:
    bundle, after, result_raw, generic, run = _positive_bundle()
    assert wine.qualify_bundle(bundle, after, j(generic), j(generic), "RUN-wine-test", "WS-" + "a" * 32, "b" * 32, run, run)["qualification"] is True
    bad = dict(bundle)
    bad["wine-result-commit.json"] = j({"state": "COMMITTED", "runId": "RUN-wine-test"})
    with pytest.raises(wine.CollectorError, match="binding|hash"):
        wine.qualify_bundle(bad, after, j(generic), j(generic), "RUN-wine-test", "WS-" + "a" * 32, "b" * 32, run, run)
    events = json.loads(bundle["wine-native-stdout.jsonl"].splitlines()[1])
    events["nonce"] = "c" * 32
    broken = dict(bundle)
    broken["wine-native-stdout.jsonl"] = b"".join((j(events) if index == 1 else line) for index, line in enumerate(bundle["wine-native-stdout.jsonl"].splitlines(keepends=True)))
    with pytest.raises(wine.CollectorError, match="identity|sequence"):
        wine.qualify_bundle(broken, after, j(generic), j(generic), "RUN-wine-test", "WS-" + "a" * 32, "b" * 32, run, run)
    assert result_raw


@pytest.mark.parametrize("mutation", ["serverless", "reordered", "late", "pid"])
def test_native_server_shape_rejects_serverless_reordered_late_and_pid_tamper(mutation: str) -> None:
    bundle, _, _, _, _ = _positive_bundle()
    events = [json.loads(line) for line in bundle["wine-native-stdout.jsonl"].splitlines()]
    if mutation == "serverless":
        events = [item for item in events if item["role"] != "server"]
    elif mutation == "reordered":
        events[2], events[3] = events[3], events[2]
        for seq, item in enumerate(events, 1):
            item["seq"] = seq
    elif mutation == "late":
        late = dict(events[-1])
        late["seq"] = len(events) + 1
        events.append(late)
    else:
        events[6]["ppid"] = 999
    with pytest.raises(wine.CollectorError):
        wine._validate_events(events, (11, 22))


def test_history_tamper_and_terminal_mutation_are_rejected() -> None:
    before = {"old-v1-current": b"v1", "old-v2-current": b"v2"}
    with pytest.raises(wine.CollectorError, match="history"):
        wine._history_stable(before, {**before, "old-v1-current": b"tampered"})
    bundle, after, _, generic, run = _positive_bundle()
    mutated = dict(after)
    value = json.loads(mutated["wine-result.json"])
    value["clientPass"] = False
    mutated["wine-result.json"] = j(value)
    with pytest.raises(wine.CollectorError, match="changed|PASS"):
        wine.qualify_bundle(bundle, mutated, j(generic), j(generic), "RUN-wine-test", "WS-" + "a" * 32, "b" * 32, run, run)


def test_bounds_and_fixed_ui_contract_are_host_only() -> None:
    assert wine.HOST_SECONDS == 240 and wine.CLEANUP_SECONDS == 20
    assert wine.LINE_CAP == 8 * 1024 and wine.TOTAL_CAP == 256 * 1024 and wine.STREAM_CAP == 64 * 1024
    assert wine.ALIAS.endswith("ForeverWineSupervisorLauncher")
    assert "--scenario" not in SOURCE.read_text(encoding="utf-8")


def test_fake_hid_contract_has_one_wake_and_one_start_enter() -> None:
    class FakeHID:
        def __init__(self) -> None:
            self.keys: list[tuple[int, int]] = []

        def key(self, modifier: int, keycode: int) -> None:
            self.keys.append((modifier, keycode))

    hid = FakeHID()
    hid.key(2, 0)
    hid.key(0, 40)
    assert hid.keys == [(2, 0), (0, 40)]
    source = SOURCE.read_text(encoding="utf-8")
    assert source.count("keyboard.key(0, 40)") == 1
    assert "am\", \"force-stop\"" in source


def test_deadline_keyboard_normalizes_late_hid_failure() -> None:
    class FakeKeyboard:
        def key(self, _modifier: int, _keycode: int) -> None:
            raise RuntimeError("fake HID failure")

    with pytest.raises(wine.CollectorError, match="UI/HID control failed"):
        wine.DeadlineKeyboard(FakeKeyboard(), float("inf")).key(0, 40)
    with pytest.raises(wine.CollectorError, match="deadline"):
        wine.DeadlineKeyboard(FakeKeyboard(), 0.0).key(0, 40)


def test_prepared_receipt_requires_exact_canonical_package_prefix() -> None:
    ws, nonce = "WS-" + "a" * 32, "b" * 32
    receipt = {
        "schema": "wowforever.wine-prefix-prepared/v1", "wsRunId": ws, "nonce": nonce,
        "prefix": f"/data/user/0/{wine.PACKAGE}/files/forever/wine-controls/{ws}/prefix/.wine",
        "runtimeRoot": f"/data/user/0/{wine.PACKAGE}/files/imagefs_shared/proton/proton-11.0-90624-arm64ec",
        "prefixDev": 11, "prefixIno": 22, "imageManifestSha256": wine.IMAGE_MANIFEST_SHA256,
        "imageZipSha256": wine.IMAGE_ZIP_SHA256,
    }
    assert wine._validate_prepared_receipt(j(receipt), ws, nonce)["prefixDev"] == 11
    receipt["prefix"] += "/foreign"
    with pytest.raises(wine.CollectorError, match="fixed WS prefix"):
        wine._validate_prepared_receipt(j(receipt), ws, nonce)


def test_prepare_helper_ast_is_mutating_only_in_the_authorized_bounded_path() -> None:
    helper = HELPER_SOURCE
    tree = ast.parse(helper.read_text(encoding="utf-8"), filename=str(helper))
    assert tree and '"install", "-r"' in helper.read_text(encoding="utf-8")
    assert '"reboot"' in helper.read_text(encoding="utf-8") and "timeout=180" in helper.read_text(encoding="utf-8")
    text = helper.read_text(encoding="utf-8")
    assert "uiautomator" not in text and "force-stop" not in text and "--boot-before" not in text


@pytest.mark.parametrize("already_installed", [False, True])
def test_prepare_helper_fake_install_and_reboot_are_exactly_one_each(monkeypatch: pytest.MonkeyPatch, tmp_path: Path, already_installed: bool) -> None:
    approved = hashlib.sha256(b"apk").hexdigest()
    apk = tmp_path / "approved.apk"
    apk.write_bytes(b"apk")
    calls: list[tuple[str, ...]] = []

    class Raw:
        def run(self, args: tuple[str, ...], timeout: float) -> SimpleNamespace:
            calls.append(args)
            if args[:2] == ("exec-out", "run-as"):
                return SimpleNamespace(returncode=0, stdout=b"11111111-1111-1111-1111-111111111111\n", stderr=b"")
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")

    candidate = (approved, "/data/app/x/base.apk", "versionCode=62 versionName=0.2.38-automated targetSdk=36 debuggable=true")
    installed = iter([candidate if already_installed else (helper.OLD_APK60_SHA256, "/data/app/x/base.apk", "versionCode=60 versionName=0.2.36-automated targetSdk=36 debuggable=true"),
                      (approved, "/data/app/x/base.apk", "versionCode=62 versionName=0.2.38-automated targetSdk=36 debuggable=true"),
                      (approved, "/data/app/x/base.apk", "versionCode=62 versionName=0.2.38-automated targetSdk=36 debuggable=true")])
    contract, _ = wine.load_contract()
    history = ({"oldV1CurrentSha256": helper.OLD_V1_CURRENT_SHA256, "oldV2CurrentSha256": helper.OLD_V2_CURRENT_SHA256}, j({"status": "ACTIVE", "runId": contract["prior"]["runId"]}))
    monkeypatch.setattr(helper, "_fixed_boot", lambda _adb: contract["prior"]["lastObservedBootId"])
    monkeypatch.setattr(helper, "_device_properties", lambda _adb: {"device": "xaga", "model": "22041216G", "manufacturer": "Xiaomi", "api": "34"})
    monkeypatch.setattr(helper, "_installed_package", lambda _adb: next(installed))
    monkeypatch.setattr(helper, "_history_snapshot", lambda _adb: history)
    monkeypatch.setattr(helper, "_guard_prior_wine", lambda *_args: None)
    monkeypatch.setattr(helper, "_history_stable", lambda before, after: before if before == after else pytest.fail("changed history"))
    monkeypatch.setattr(helper, "_protected_pins", lambda _adb: {"compiler": "verified"})
    monkeypatch.setattr(helper, "own_ps", lambda _adb: "UID PID PPID NAME\n10330 1 0 ps\n")
    monkeypatch.setattr(helper, "_wait_for_boot", lambda _adb, _before: ("1" * 8 + "-1111-1111-1111-111111111111", "1" * 8 + "-1111-1111-1111-111111111111"))
    result = helper.device_preflight(helper.Adb(Raw()), approved, apk)
    assert result["installedApkSha256"] == approved
    expected = [("reboot",)] if already_installed else [("install", "-r", "--no-streaming", str(apk)), ("reboot",)]
    assert [args for args in calls if args[0] in {"install", "reboot"}] == expected


def test_prepare_helper_ps_filters_foreign_rows_before_persistence() -> None:
    class Raw:
        def run(self, args: tuple[str, ...], timeout: float) -> SimpleNamespace:
            return SimpleNamespace(returncode=0, stdout=b"  UID   PID  PPID NAME\n1000 2 1 foreign\n10330 3 1 com.wowforever.arm64probe\n", stderr=b"")

    assert helper.own_ps(helper.Adb(Raw())) == "UID PID PPID NAME\n10330 3 1 com.wowforever.arm64probe\n"


def test_preflight_authenticates_embedded_profile_when_audit_is_small(tmp_path) -> None:
    import zipfile
    apk, audit = tmp_path / "signed.apk", tmp_path / "audit.json"
    apk.write_bytes(b"temporary")
    metadata = approved_audit(apk)
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("assets/forever/provenance.json", j(metadata["provenance"]))
    metadata = approved_audit(apk)
    del metadata["provenance"]
    audit.write_bytes(j(metadata))
    pin = hashlib.sha256(apk.read_bytes()).hexdigest()
    assert helper.host_authenticate(apk, audit, pin)["provenance"]["serverIsolation"] is True


def test_error_frame_and_extra_witness_output_never_qualify() -> None:
    bundle, _history, _current, _binding, _run = _positive_bundle()
    events = [json.loads(line) for line in bundle["wine-native-stdout.jsonl"].splitlines()]
    events.insert(6, _event(7, "ERROR", "supervisor", "none", "UNKNOWN", 100, 1, 1, 1))
    for sequence, event in enumerate(events, 1):
        event["seq"] = sequence
    with pytest.raises(wine.CollectorError, match="native ERROR"):
        wine._validate_events(events, (11, 22))
    witness = bundle["wine-witness-stdout.jsonl"]
    assert wine._validate_witness(witness, "WS-" + "a" * 32, "b" * 32)
    for extra in (b"\n", witness, b"trailing"):
        assert not wine._validate_witness(witness + extra, "WS-" + "a" * 32, "b" * 32)


def test_cancelled_native_terminal_cannot_match_positive_app_result() -> None:
    bundle, _after, _raw, generic, run = _positive_bundle()
    events = [json.loads(line) for line in bundle["wine-native-stdout.jsonl"].splitlines()]
    events[-1]["status"] = "CANCELLED"
    bundle["wine-native-stdout.jsonl"] = b"".join(j(event) for event in events)
    with pytest.raises(wine.CollectorError, match="normal gate did not PASS"):
        wine.qualify_bundle(bundle, dict(bundle), j(generic), j(generic), "RUN-wine-test",
                            "WS-" + "a" * 32, "b" * 32, run, run)


def test_shell_and_exec_out_share_local_ui_deadline(monkeypatch) -> None:
    calls = []
    client = SimpleNamespace(deadline=1100.0, run=lambda args, timeout: calls.append((args, timeout)))
    monkeypatch.setattr(wine.time, "monotonic", lambda: 1000.0)
    facade = wine.DeadlineAdbFacade(client)
    facade.deadline = 1003.0
    facade.shell("dumpsys", "window", "policy", timeout=30)
    facade.exec_out("test", timeout=30)
    assert [call[1] for call in calls] == [3.0, 3.0]
    facade.deadline = 999.0
    with pytest.raises(wine.CollectorError, match="deadline"):
        facade.shell("test")
    assert len(calls) == 2


def test_preflight_requires_both_uid_filtered_snapshots(tmp_path) -> None:
    before, after = "1" * 8 + "-1111-1111-1111-111111111111", "2" * 8 + "-2222-2222-2222-222222222222"
    input_dir = tmp_path.parent / f"{tmp_path.name}-inputs"
    input_dir.mkdir()
    apk_path = input_dir / "approved.apk"
    with zipfile.ZipFile(apk_path, "w") as archive:
        archive.writestr("assets/forever/provenance.json", j(approved_audit(apk_path)["provenance"]))
    pin = hashlib.sha256(apk_path.read_bytes()).hexdigest()
    audit_path = input_dir / "audit.json"
    audit_path.write_bytes(j(approved_audit(apk_path)))
    authenticated_apk = wine.validate_apk(apk_path, audit_path, pin)
    record = {"result": "READY_FOR_WINE62_SERVER_ISOLATION", "serial": wine.SERIAL, "package": wine.PACKAGE,
              "versionCode": wine.VERSION_CODE, "versionName": wine.VERSION_NAME, "approvedApkSha256": pin,
              "installedApkSha256": pin, "bootComplete": True, "bootBeforeReboot": before,
              "bootAfterReboot": after, "stableBootIds": [after, after],
              "ownUidProcessSnapshot": "UID PID PPID NAME\n10330 1 0 ps\n",
              "preRebootOwnUidProcessSnapshot": "UID PID PPID NAME\n10330 1 0 ps\n",
              "historyPins": {"oldV2CurrentSha256": wine.OLD_V2_CURRENT_SHA256,
                              "oldV1CurrentSha256": wine.OLD_V1_CURRENT_SHA256,
                              "oldV2ArchiveAndReceiptSha256": {f"{wine.OLD_V2_PERSISTENT_ROOT}/{name}": digest
                                                               for name, digest in wine.OLD_V2_HISTORY_SHA256.items()}}}

    def write_bundle(value: dict[str, object]) -> None:
        source = helper._source_provenance(authenticated_apk)
        members = {
            "source-provenance.json": j(source),
            "operations.jsonl": b'{"operation":"ready"}\n',
            "state-00-READY_FOR_WINE62_SERVER_ISOLATION.json": j(value),
        }
        for name, raw in members.items():
            (tmp_path / name).write_bytes(raw)
        seal = {name: {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()} for name, raw in members.items()}
        (tmp_path / "seal.json").write_bytes(j({"schema": 1, "files": seal}))

    write_bundle(record)
    assert wine.validate_preflight(tmp_path, pin)["record"] == record
    known_boot = wine.load_continuation_contract()[0]["knownFailure"]["bootId"]
    record["bootAfterReboot"] = known_boot
    record["stableBootIds"] = [known_boot, known_boot]
    write_bundle(record)
    with pytest.raises(wine.CollectorError, match="new boot"):
        wine.validate_preflight(tmp_path, pin)
    record["bootAfterReboot"] = after
    record["stableBootIds"] = [after, after]
    del record["preRebootOwnUidProcessSnapshot"]
    write_bundle(record)
    with pytest.raises(wine.CollectorError, match="snapshot"):
        wine.validate_preflight(tmp_path, pin)


@pytest.mark.parametrize("mutation", ["extra", "core", "compact"])
def test_preflight_binds_full_recorded_provenance_to_authenticated_apk(tmp_path: Path, mutation: str) -> None:
    apk = tmp_path / "approved.apk"
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("assets/forever/provenance.json", j(approved_audit(apk)["provenance"]))
    pin = hashlib.sha256(apk.read_bytes()).hexdigest()
    audit = tmp_path / "audit.json"
    audit_document = approved_audit(apk)
    del audit_document["provenance"]
    audit.write_bytes(j(audit_document))
    authenticated = wine.validate_apk(apk, audit, pin)
    assert authenticated["provenance"]["clientBuild"] == "1.60.1.70009"
    source = helper._source_provenance(authenticated)
    if mutation == "extra":
        source["apk"]["provenance"]["unrecordedExtra"] = "not-from-apk"  # type: ignore[index]
    elif mutation == "core":
        source["apk"]["provenance"]["profile"] = "native_fixture"  # type: ignore[index]
    else:
        source["apk"]["provenance"] = {
            key: authenticated["provenance"][key]
            for key in ("profile", "launchKind", "serverIsolation", "protocolSchema",
                        "graphicsProbeEnabled", "memoryPatching", "authentication", "nativeOnly")
        }
    with pytest.raises(wine.CollectorError, match="provenance"):
        wine._validate_preflight_source_provenance(source, pin)


def test_current_unreadable_is_not_treated_as_missing(monkeypatch) -> None:
    monkeypatch.setattr(wine, "_device_private", lambda *_args: (None, "unreadable"))
    with pytest.raises(wine.CollectorError, match="current is unreadable"):
        wine._read_current(object())


def test_host_missing_status_entry_cannot_be_forged_as_authority() -> None:
    contract, _ = wine.load_contract()
    forged = {"fixedPaths": contract["fixedPaths"][:-1]}
    with pytest.raises(wine.CollectorError, match="33"):
        wine._validate_fixed_authority(object(), forged)


def _protected_metadata_fake(*, bad: str | None = None, nofollow: bool = False):
    contract, _ = wine.load_contract()
    entries = copy.deepcopy(contract["fixedPaths"])
    calls: list[tuple[str, ...]] = []
    stat_calls: dict[str, int] = {}
    pins = wine.PROTECTED_COMPILER_PINS

    class Raw:
        def run(self, args, timeout):
            calls.append(args)
            if args[0] == "shell" and "readlink" in args:
                return SimpleNamespace(returncode=0 if nofollow else 1,
                                       stdout=b"target\n" if nofollow else b"", stderr=b"")
            if args[0] == "shell" and "sha256sum" in args:
                path = args[-1]
                expected = pins[path][1]
                if bad == "sha":
                    expected = "0" * 64
                return SimpleNamespace(returncode=0, stdout=f"{expected}  {path}\n".encode(), stderr=b"")
            assert args[:6] == ("shell", "-T", "run-as", wine.PACKAGE, "sh", "-c")
            script = shlex.split(args[-1])[0]
            parsed = shlex.split(script)
            assert parsed[parsed.index("-c") + 1] == wine.PROTECTED_STAT_FORMAT
            path = parsed[-1]
            size, _ = pins[path]
            values = [65079, 1000 + list(pins).index(path), wine.UID, 10330, 771, 1, size, 1790964542, 1790964543]
            stat_calls[path] = stat_calls.get(path, 0) + 1
            if bad == "size":
                values[6] += 1
            elif bad == "uid":
                values[2] += 1
            elif bad == "mode":
                values[4] = 770
            elif bad == "nlink":
                values[5] = 2
            elif bad == "inode" and stat_calls[path] == 2:
                values[1] += 1
            kind = "directory" if bad == "type" else "regular file"
            return SimpleNamespace(returncode=0, stdout=(":".join(map(str, values)) + f":{kind}\n").encode(), stderr=b"")

    return wine.AdbClient(Raw()), entries, calls


def test_protected_compiler_metadata_is_bounded_and_never_reads_binary() -> None:
    adb, entries, calls = _protected_metadata_fake()
    observed = wine.closed_fixed_protected_metadata(adb, entries)
    assert observed == {path: digest for path, (_size, digest) in wine.PROTECTED_COMPILER_PINS.items()}
    assert all("head" not in args and "dd" not in args for call in calls for args in call)
    assert sum("sha256sum" in call for call in calls) == 2
    assert sum("stat -c" in call[-1] for call in calls) == 4
    assert all(call[0] == "shell" and call[1] == "-T" for call in calls)


@pytest.mark.parametrize("bad", ["sha", "size", "uid", "mode", "nlink", "type", "inode"])
def test_protected_compiler_metadata_rejects_hash_metadata_or_inode_tamper(bad: str) -> None:
    adb, entries, _calls = _protected_metadata_fake(bad=bad)
    with pytest.raises(wine.CollectorError, match="protected compiler"):
        wine.closed_fixed_protected_metadata(adb, entries)


@pytest.mark.parametrize("field", ["kind", "status", "binaryCopied", "sha256", "expectedSha256"])
def test_protected_compiler_contract_rejects_unexpected_authority_fields(field: str) -> None:
    contract, _ = wine.load_contract()
    entries = copy.deepcopy(contract["fixedPaths"])
    item = next(item for item in entries if item.get("kind") == "protected-compiler")
    item[field] = {"kind": "history", "status": "MISSING", "binaryCopied": True,
                   "sha256": "0" * 64, "expectedSha256": "0" * 64}[field]
    with pytest.raises(wine.CollectorError, match="protected compiler"):
        wine.closed_fixed_protected_metadata(object(), entries)


def test_protected_compiler_no_follow_rejects_symlink_components() -> None:
    adb, entries, calls = _protected_metadata_fake(nofollow=True)
    with pytest.raises(wine.CollectorError, match="symlink"):
        wine.closed_fixed_protected_metadata(adb, entries)
    assert not any("sha256sum" in call for call in calls)


def test_original_sentinel_boot_is_valid_before_reboot_and_new_boot_is_read_twice(monkeypatch) -> None:
    new_boot = "11111111-1111-1111-1111-111111111111"
    reads = []
    class FakeAdb:
        def exec_out(self, *args, **kwargs):
            reads.append(args)
            return SimpleNamespace(returncode=0, stdout=(helper.REBOOT_SENTINEL_BOOT + "\n").encode(), stderr=b"")
        def shell(self, *args, **kwargs):
            return SimpleNamespace(returncode=0, stdout=b"1\n", stderr=b"")
    assert helper._fixed_boot(FakeAdb()) == helper.REBOOT_SENTINEL_BOOT
    monkeypatch.setattr(helper, "_fixed_boot", lambda _adb: reads.append("after") or new_boot)
    assert helper._wait_for_boot(FakeAdb(), helper.REBOOT_SENTINEL_BOOT) == (new_boot, new_boot)
    assert reads.count("after") == 2


def test_protected_metadata_remote_script_is_quoted_and_hash_bound() -> None:
    import shlex
    commands = []
    item = helper.PROTECTED["compiler"]
    class Raw:
        def run(self, args, timeout):
            commands.append(args)
            if args[-2] == "sha256sum":
                out = f"{item['sha256']}  {item['path']}\n".encode()
                return SimpleNamespace(returncode=0, stdout=out, stderr=b"")
            if "readlink" in args:
                return SimpleNamespace(returncode=1, stdout=b"", stderr=b"")
            assert args[:6] == ("shell", "-T", "run-as", helper.PACKAGE, "sh", "-c")
            assert len(shlex.split(args[-1])) == 1
            script = shlex.split(args[-1])[0]
            parsed = shlex.split(script)
            assert parsed[parsed.index("-c") + 1] == "%u:%a:%h:%d:%i:%Y:%s:%F"
            return SimpleNamespace(returncode=0, stdout=b"10330:771:1:65079:1361184:1790964542:1114112:regular file\n", stderr=b"")
    assert helper._protected_one(helper.Adb(Raw()), item)["sha256"] == item["sha256"]
    assert sum("sh" in args for args in commands) == 2


def test_prior_unknown_blocks_all_preparation_mutations(monkeypatch, tmp_path) -> None:
    calls = []
    class Raw:
        def run(self, args, timeout):
            calls.append(args)
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")
    monkeypatch.setattr(helper, "_device_properties", lambda _adb: {"device": "xaga"})
    monkeypatch.setattr(helper, "_installed_package", lambda _adb: (helper.OLD_APK60_SHA256, "/data/app/base.apk", "versionCode=60"))
    monkeypatch.setattr(helper, "_fixed_boot", lambda _adb: helper.REBOOT_SENTINEL_BOOT)
    monkeypatch.setattr(helper, "_history_snapshot", lambda _adb: ({}, b"old"))
    def reject(*_args):
        raise helper.PrepareError("prior Wine RUN is active or unknown")
    monkeypatch.setattr(helper, "_guard_prior_wine", reject)
    with pytest.raises(helper.PrepareError, match="unknown"):
        helper.device_preflight(helper.Adb(Raw()), "a" * 64, tmp_path / "apk")
    assert not calls


def test_preparation_ignores_only_its_ps_collector_pid_changes(monkeypatch) -> None:
    monkeypatch.setattr(helper, "_history_snapshot", lambda _adb: ({}, b"stable"))
    monkeypatch.setattr(helper, "_history_stable", lambda before, after: {})
    monkeypatch.setattr(helper, "own_ps", lambda _adb: "UID PID PPID NAME\n10330 54321 1 ps\n")
    helper._stable_before_mutation(object(), {}, b"stable", "UID PID PPID NAME\n10330 12345 1 ps\n")
    monkeypatch.setattr(helper, "own_ps", lambda _adb: "UID PID PPID NAME\n10330 54321 1 wine\n")
    with pytest.raises(helper.PrepareError, match="unexpected own-UID"):
        helper._stable_before_mutation(object(), {}, b"stable", "UID PID PPID NAME\n10330 12345 1 ps\n")


def test_sealed_install_alias_and_owned_directory_are_removed(tmp_path) -> None:
    original = tmp_path / "candidate.apk"
    original.write_bytes(b"approved bytes")
    expected = hashlib.sha256(original.read_bytes()).hexdigest()
    with helper._sealed_apk_handoff(tmp_path, original, expected) as handoff:
        alias, directory = handoff.alias, handoff.directory
        assert alias and alias.read_bytes() == b"approved bytes"
    assert alias is not None and not alias.is_symlink()
    assert directory is not None and not directory.exists()


def test_only_exact_host_adb_bootstrap_is_allowed_for_get_state() -> None:
    result = SimpleNamespace(returncode=0, stdout=b"device\n", stderr=b"* daemon not running; starting now at tcp:5037\n* daemon started successfully\n")
    assert helper._ok(result, "get-state") == b"device\n"
    with pytest.raises(helper.PrepareError):
        helper._ok(result, "install")
    result.stderr += b"unrecognized diagnostic\n"
    with pytest.raises(helper.PrepareError):
        helper._ok(result, "get-state")


@pytest.mark.parametrize("current", [
    {"runId": "RUN-old", "status": "ACTIVE"},
    {"status": "EXITED"},
    {"runId": "not-a-run", "status": "EXITED"},
])
def test_generic_current_identity_and_terminal_guard_precede_binding(monkeypatch, current) -> None:
    called = []
    monkeypatch.setattr(helper.collector, "_require_prior_wine_closed", lambda *_args: called.append(True))
    with pytest.raises(helper.PrepareError):
        helper._guard_prior_wine(helper.Adb(SimpleNamespace(run=lambda *_args, **_kwargs: None)), j(current))
    assert not called


def test_current_interleaving_stops_before_install(monkeypatch, tmp_path) -> None:
    calls = []
    class Raw:
        def run(self, args, timeout):
            calls.append(args)
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")
    snapshots = iter([({}, b"stable"), ({}, b"interleaved")])
    monkeypatch.setattr(helper, "_device_properties", lambda _adb: {"device": "xaga"})
    monkeypatch.setattr(helper, "_installed_package", lambda _adb: (helper.OLD_APK60_SHA256, "/data/app/base.apk", "versionCode=60"))
    monkeypatch.setattr(helper, "_fixed_boot", lambda _adb: helper.REBOOT_SENTINEL_BOOT)
    monkeypatch.setattr(helper, "_history_snapshot", lambda _adb: next(snapshots))
    monkeypatch.setattr(helper, "_guard_prior_wine", lambda *_args: None)
    monkeypatch.setattr(helper, "_protected_pins", lambda _adb: {"compiler": "verified"})
    monkeypatch.setattr(helper, "own_ps", lambda _adb: "UID PID PPID NAME\n10330 1 0 ps\n")
    monkeypatch.setattr(helper, "_history_stable", lambda before, after: before)
    with pytest.raises(helper.PrepareError, match="changed"):
        helper.device_preflight(helper.Adb(Raw()), "a" * 64, tmp_path / "apk")
    assert not [args for args in calls if args[0] in {"install", "reboot"}]


def test_sealed_handoff_survives_original_replacement_and_is_immutable(tmp_path) -> None:
    apk = tmp_path / "source.apk"
    original = b"original-approved-apk"
    apk.write_bytes(original)
    root = tmp_path / "evidence"
    root.mkdir()
    digest = hashlib.sha256(original).hexdigest()
    with helper._sealed_apk_handoff(root, apk, digest) as handoff:
        assert handoff.alias and handoff.alias.read_bytes() == original
        assert helper.fcntl.fcntl(handoff.fd, helper.fcntl.F_GET_SEALS) == (
            helper.fcntl.F_SEAL_WRITE | helper.fcntl.F_SEAL_GROW |
            helper.fcntl.F_SEAL_SHRINK | helper.fcntl.F_SEAL_SEAL)
        apk.write_bytes(b"replacement")
        assert handoff.alias.read_bytes() == original
        with pytest.raises(OSError):
            os.write(handoff.fd, b"x")


def test_sealed_handoff_bad_hash_and_write_failure_happen_before_adb(monkeypatch, tmp_path) -> None:
    apk = tmp_path / "source.apk"
    apk.write_bytes(b"approved")
    root = tmp_path / "evidence"
    root.mkdir()
    with pytest.raises(helper.PrepareError, match="hash"):
        with helper._sealed_apk_handoff(root, apk, "0" * 64):
            pytest.fail("bad hash unexpectedly reached the installer")

    real_write = helper.os.write
    def denied(fd, data):
        raise PermissionError("sealed copy denied")
    monkeypatch.setattr(helper.os, "write", denied)
    with pytest.raises(helper.PrepareError, match="sealed APK copy failed"):
        with helper._sealed_apk_handoff(root, apk, hashlib.sha256(b"approved").hexdigest()):
            pytest.fail("write failure unexpectedly reached the installer")
    monkeypatch.setattr(helper.os, "write", real_write)


def test_prepare_fails_before_adb_when_shared_host_lock_is_held(monkeypatch, tmp_path) -> None:
    class NeverAdb:
        def __init__(self, *_args, **_kwargs):
            raise AssertionError("ADB constructed while host lock was held")
    monkeypatch.setattr(helper, "Adb", NeverAdb)
    args = SimpleNamespace(apk=tmp_path / "missing.apk", audit=tmp_path / "missing.json",
                           expected_apk_sha256="0" * 64, output=tmp_path)
    with helper.collector.device_control_lock(tmp_path):
        with pytest.raises(helper.PrepareError, match="another Wine device operation"):
            helper.prepare(args)


def _wine_control_fakes(monkeypatch: pytest.MonkeyPatch, *, readiness_state: object | None = True,
                        clock_start: float = 0.0):
    clock = [clock_start]
    phases: list[str] = []
    calls: list[tuple[str, ...]] = []
    keys: list[tuple[int, int]] = []

    class Raw:
        def run(self, args: tuple[str, ...], timeout: float) -> SimpleNamespace:
            calls.append(args)
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")

    class Keyboard:
        def __init__(self, _serial: str) -> None:
            pass

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return None

        def key(self, modifier: int, keycode: int) -> None:
            keys.append((modifier, keycode))
            phases.append(f"key:{modifier}:{keycode}")
            clock[0] += 0.2

    class State:
        def __init__(self, focused: str | None, checked: bool = False,
                     start_enabled: bool = False) -> None:
            focus = {"checkbox": "true", "start": "true"}.get(focused)
            self.root = ET.fromstring(
                '<node package="com.wowforever.arm64probe">'
                '<node class="android.widget.TextView" text="Forever Wine server isolation" package="com.wowforever.arm64probe"/>'
                '<node class="android.widget.CheckBox" text="I consent to start the isolated Wine control" '
                f'checked="{"true" if checked else "false"}" enabled="true" package="com.wowforever.arm64probe" '
                f'focused="{"true" if focused == "checkbox" else "false"}"/>'
                f'<node class="android.widget.Button" text="Start control" enabled="{"true" if start_enabled else "false"}" '
                f'package="com.wowforever.arm64probe" focused="{"true" if focused == "start" else "false"}"/>'
                '<node class="android.widget.Button" text="Cancel control" enabled="false" package="com.wowforever.arm64probe" focused="false"/>'
                '</node>'
            )

        def top_is_own(self) -> bool:
            return True

        def hierarchy_is_own(self) -> bool:
            return True

    # Initial focus is deliberately absent; the first Tab is the only focus acquisition.
    states = iter([
        State(None), State("start"), State("checkbox"), State("checkbox"),
        State("checkbox", checked=True), State("start", checked=True, start_enabled=True),
    ])

    class UiHelper:
        UsbKeyboard = Keyboard

        @staticmethod
        def policy_is_nonsecure(_adb, *, deadline: float) -> bool:
            phases.append("policy")
            return clock[0] < deadline

        @staticmethod
        def wait_for_own_ui(adb):
            assert adb.deadline - clock[0] >= 50
            clock[0] += 2.0  # Readiness remains below eight seconds; the complete sequence does not.
            return SimpleNamespace(state=readiness_state, reason="own-top-ready" if readiness_state is not None else "foreign-top", dump_count=3)

        @staticmethod
        def dump_ui(_adb, **_kwargs):
            return next(states)

    class NativeHelper:
        @staticmethod
        def _poll_wakefulness_awake(_adb, _journal):
            phases.append("awake")
            clock[0] += 3.0

        @staticmethod
        def _poll_keyguard_dismissed(_adb, _journal):
            phases.append("keyguard")
            clock[0] += 3.0

    monkeypatch.setattr(wine, "_load_ui_helpers", lambda: UiHelper)
    monkeypatch.setattr(wine, "_load_native_control_helpers", lambda: NativeHelper)
    monkeypatch.setattr(wine.time, "monotonic", lambda: clock[0])
    monkeypatch.setattr(wine.time, "sleep", lambda seconds: clock.__setitem__(0, clock[0] + seconds))
    return wine.AdbClient(Raw()), calls, keys, phases, clock


def test_control_reuses_ordered_awake_and_keyguard_polls_and_allows_slow_ui(monkeypatch) -> None:
    adb, calls, keys, phases, clock = _wine_control_fakes(monkeypatch)
    adb.deadline = 240.0
    journal: list[dict[str, object]] = []
    wine._control_activity_impl(adb, journal)
    assert clock[0] > 8.0 and clock[0] < 60.0
    assert keys == [(2, 0), (0, 43), (0, 43), (0, 44), (0, 43), (0, 40)]
    assert phases.index("awake") < phases.index("keyguard")
    assert [entry["action"] for entry in journal][:5] == ["wake", "awake", "dismiss", "keyguard", "initialActivity"]
    assert any(args[:3] == ("shell", "-T", "am") for args in calls)


def test_permanently_foreign_readiness_never_reaches_start_key(monkeypatch) -> None:
    adb, calls, keys, _phases, _clock = _wine_control_fakes(monkeypatch, readiness_state=None)
    adb.deadline = 240.0
    journal: list[dict[str, object]] = []
    with pytest.raises(wine.CollectorError, match="readiness failed"):
        wine._control_activity_impl(adb, journal)
    assert keys == [(2, 0)]
    assert journal[-1] == {"action": "readiness", "status": "not-ready", "reason": "foreign-top", "count": 3}


def test_control_deadline_is_sixty_seconds_clipped_by_host_reserve_and_rejects_late_commands(monkeypatch) -> None:
    now = [100.0]
    calls: list[tuple[str, ...]] = []

    class Raw:
        def run(self, args, timeout):
            calls.append(args)
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")

    adb = wine.AdbClient(Raw())
    adb.deadline = 130.0
    monkeypatch.setattr(wine.time, "monotonic", lambda: now[0])
    deadline = wine._control_deadline(adb)
    assert deadline == 110.0  # min(now + 60, host deadline - 20)
    facade = wine.DeadlineAdbFacade(adb)
    facade.deadline = deadline
    facade.shell("true")
    now[0] = deadline
    with pytest.raises(wine.CollectorError, match="deadline"):
        facade.shell("late")
    assert calls == [("shell", "-T", "true")]


def test_required_wake_polls_are_not_optional_or_hasattr_skipped() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    assert "hasattr" not in source
    assert "native._poll_wakefulness_awake" in source
    assert "native._poll_keyguard_dismissed" in source


def test_wine_authority_paths_are_exactly_bound_to_run_or_ws_before_adb() -> None:
    run_id = "RUN-bound"
    ws = "WS-" + "a" * 32
    allowed = {
        f"{wine.RUN_ROOT}/{run_id}/wine-binding.json",
        f"{wine.RUN_ROOT}/{run_id}/wine-process-create-intents.jsonl",
        f"files/forever/wine-controls/{ws}/prepared.receipt",
        f"files/forever/wine-controls/{ws}/prefix/.wine/.supervisor-output/server.stdout",
        f"files/forever/wine-controls/{ws}/prefix/.wine/.supervisor-output/client.stderr",
    }
    assert wine._private_path(f"{wine.RUN_ROOT}/{run_id}/wine-binding.json", run_id=run_id)
    assert wine._private_path(f"files/forever/wine-controls/{ws}/prepared.receipt", ws_run_id=ws)
    assert all(wine._private_path(path, run_id=run_id, ws_run_id=ws) for path in allowed)
    rejected = {
        f"{wine.RUN_ROOT}/{run_id}/nested/wine-binding.json",
        f"{wine.RUN_ROOT}/{run_id}/../{run_id}/wine-binding.json",
        f"{wine.RUN_ROOT}/RUN-other/wine-binding.json",
        f"files/forever/wine-controls/{ws}/prefix/.wine",
        f"files/forever/wine-controls/{ws}/prefix/.wine/.supervisor-output/rogue.stdout",
        f"files/forever/wine-controls/{ws}/prepared.receipt/../prepared.receipt",
        f"files/forever/wine-controls/{'WS-' + 'b' * 32}/prepared.receipt",
    }
    assert all(not wine._private_path(path, run_id=run_id, ws_run_id=ws) for path in rejected)

    class NeverAdb:
        def shell(self, *_args, **_kwargs):
            raise AssertionError("rejected path reached ADB")

    with pytest.raises(wine.CollectorError, match="outside fixed"):
        wine._device_private(NeverAdb(), next(iter(rejected)), wine.JSON_CAP, ws_run_id=ws)


def test_output_log_reader_reaches_all_four_exact_bound_paths_without_device() -> None:
    ws = "WS-" + "c" * 32
    heads: list[str] = []

    class Raw:
        def run(self, args, timeout):
            if args[0] == "shell" and "readlink" in args:
                return SimpleNamespace(returncode=1, stdout=b"", stderr=b"")
            if args[0] == "shell" and "head" in args:
                heads.append(args[-1])
                return SimpleNamespace(returncode=0, stdout=b"log", stderr=b"")
            raise AssertionError(f"unexpected command: {args}")

    result = wine._read_output_logs(wine.AdbClient(Raw()), ws)
    assert set(result) == set(wine.OUTPUT_LOGS)
    assert heads == [f"{wine.WINE_OUTPUT_REL.format(ws=ws)}/{name}" for name in wine.OUTPUT_LOGS]


def _receipt_wait_fakes(monkeypatch: pytest.MonkeyPatch, current_states: list[dict[str, object]],
                        receipt_states: list[tuple[bytes | None, str]]):
    clock = [0.0]
    current_iter = iter(current_states)
    receipt_iter = iter(receipt_states)
    receipt_calls: list[dict[str, object]] = []
    adb = SimpleNamespace(deadline=1000.0)

    def read_current(_adb):
        value = next(current_iter)
        return j(value), value

    def read_private(_adb, path, cap, *, run_id=None, ws_run_id=None):
        receipt_calls.append({"path": path, "run_id": run_id, "ws_run_id": ws_run_id})
        raw, status = next(receipt_iter)
        return raw, status

    monkeypatch.setattr(wine, "_read_current", read_current)
    monkeypatch.setattr(wine, "_device_private", read_private)
    monkeypatch.setattr(wine, "_validate_prepared_receipt", lambda raw, ws, nonce: {"prefixDev": 11, "prefixIno": 22})
    monkeypatch.setattr(wine.time, "monotonic", lambda: clock[0])
    monkeypatch.setattr(wine.time, "sleep", lambda seconds: clock.__setitem__(0, clock[0] + seconds))
    return adb, receipt_calls, clock


def test_receipt_wait_admits_after_preparation_polls_without_new_start_or_input(monkeypatch) -> None:
    run_id, ws, nonce = "RUN-bound", "WS-" + "d" * 32, "e" * 32
    receipt = b"prepared"
    adb, calls, _clock = _receipt_wait_fakes(
        monkeypatch,
        [{"runId": run_id, "status": "PREPARED"}, {"runId": run_id, "status": "ACTIVE"},
         {"runId": run_id, "status": "ACTIVE"}, {"runId": run_id, "status": "ACTIVE"}],
        [(None, "missing"), (None, "missing"), (receipt, "ok")],
    )
    raw, value = wine._wait_for_prepared_receipt(adb, run_id, ws, nonce, 100.0)
    assert raw == receipt and value == {"prefixDev": 11, "prefixIno": 22}
    assert len(calls) == 3 and all(call["ws_run_id"] == ws for call in calls)
    assert not hasattr(adb, "start") and not hasattr(adb, "key")


@pytest.mark.parametrize("mode", ["terminal", "switched", "late"])
def test_receipt_wait_fail_closed_for_terminal_switched_or_late_result(monkeypatch, mode: str) -> None:
    run_id, ws, nonce = "RUN-bound", "WS-" + "f" * 32, "1" * 32
    if mode == "terminal":
        states = [{"runId": run_id, "status": "FAILED"}]
        receipts = [(None, "missing")]
    elif mode == "switched":
        states = [{"runId": run_id, "status": "ACTIVE"}, {"runId": "RUN-other", "status": "ACTIVE"}]
        receipts = [(None, "missing")]
    else:
        states = [{"runId": run_id, "status": "ACTIVE"}] * 4
        receipts = [(None, "missing")] * 4
    adb, _calls, clock = _receipt_wait_fakes(monkeypatch, states, receipts)
    deadline = 0.5 if mode == "late" else 100.0
    with pytest.raises(wine.CollectorError, match="receipt|changed"):
        wine._wait_for_prepared_receipt(adb, run_id, ws, nonce, deadline)
    if mode == "late":
        assert clock[0] >= deadline


def test_failure_capture_preserves_current_before_partial_authority_and_intents(monkeypatch, tmp_path: Path) -> None:
    class FakeEvidence:
        def __init__(self):
            self.root = tmp_path
            self.captures: dict[str, dict[str, bytes | None]] = {}

        def write_capture(self, name, files):
            self.captures[name] = files

    evidence = FakeEvidence()
    current = j({"runId": "RUN-bound", "status": "FAILED"})
    monkeypatch.setattr(wine, "_read_current", lambda _adb: (current, {"runId": "RUN-bound", "status": "FAILED"}))
    monkeypatch.setattr(wine, "_read_authority_best_effort", lambda *_args: ({"wine-process-create-intents.jsonl": b"intents"}, False))
    monkeypatch.setattr(wine, "_read_output_logs_best_effort", lambda *_args: ({name: None for name in wine.OUTPUT_LOGS}, False))
    captured, incomplete = wine._capture_failure_evidence(
        SimpleNamespace(), evidence, "RUN-bound", "WS-" + "a" * 32, None,
    )
    assert captured is True and incomplete is False
    assert (tmp_path / "generic-current-failure.json").read_bytes() == current
    assert evidence.captures["capture-failure"]["wine-process-create-intents.jsonl"] == b"intents"


@pytest.mark.parametrize("current", [
    {"runId": "RUN-bound", "status": "ACTIVE", "stage": "PREFLIGHT"},
    {"runId": "RUN-bound", "status": "PREFLIGHT"},
    {"runId": "RUN-bound", "status": "UNKNOWN"},
    {"status": "EXITED"},
    {"runId": "foreign/run", "status": "FAILED"},
])
def test_collector_admission_rejects_every_nonterminal_or_malformed_current(current) -> None:
    with pytest.raises(wine.CollectorError, match="terminal"):
        wine._require_terminal_current(current)


def test_positive_qualification_requires_original_and_snapshot_binding_bytes() -> None:
    bundle, after, _, generic, run = _positive_bundle()
    run_id, ws, nonce = "RUN-wine-test", "WS-" + "a" * 32, "b" * 32
    changed = dict(after)
    changed["wine-binding.json"] = json.dumps(json.loads(after["wine-binding.json"]), indent=2).encode()
    with pytest.raises(wine.CollectorError, match="binding changed"):
        wine.qualify_bundle(bundle, changed, j(generic), j(generic), run_id, ws, nonce, run, run)
    with pytest.raises(wine.CollectorError, match="binding changed"):
        wine.qualify_bundle(bundle, after, j(generic), j(generic), run_id, ws, nonce, run, run,
                            original_binding=changed["wine-binding.json"])
    missing = dict(bundle)
    del missing["wine-binding.json"]
    with pytest.raises(wine.CollectorError):
        wine.qualify_bundle(missing, after, j(generic), j(generic), run_id, ws, nonce, run, run)


def test_terminal_run_with_existing_receipt_is_captured_not_rejected(monkeypatch) -> None:
    run_id, ws, nonce = "RUN-bound", "WS-" + "a" * 32, "b" * 32
    adb, calls, _ = _receipt_wait_fakes(
        monkeypatch, [{"runId": run_id, "status": "EXITED"}] * 2, [(b"prepared", "ok")],
    )
    assert wine._wait_for_prepared_receipt(adb, run_id, ws, nonce, 100.0)[0] == b"prepared"
    assert len(calls) == 1


def test_terminal_wait_accepts_only_same_run_before_deadline(monkeypatch) -> None:
    adb, _, clock = _receipt_wait_fakes(
        monkeypatch,
        [{"runId": "RUN-bound", "status": "ACTIVE"}, {"runId": "RUN-bound", "status": "EXITED"}], [],
    )
    assert wine._wait_for_terminal_current(adb, "RUN-bound", 100.0)[1]["status"] == "EXITED"
    assert clock[0] == 0.25
    monkeypatch.setattr(wine, "_read_current", lambda _adb: (b"{}", {"runId": "RUN-other", "status": "EXITED"}))
    with pytest.raises(wine.CollectorError, match="changed"):
        wine._wait_for_terminal_current(adb, "RUN-bound", 100.0)
    assert adb.deadline == 1000.0


def test_failure_capture_keeps_run_without_ws_and_marks_unreadable(monkeypatch, tmp_path) -> None:
    class FakeEvidence:
        root = tmp_path
        captures = {}

        def write_capture(self, name, files):
            self.captures[name] = files

    current = j({"runId": "RUN-bound", "status": "ACTIVE"})
    monkeypatch.setattr(wine, "_read_current", lambda _adb: (current, json.loads(current)))

    def read_private(_adb, path, _cap, **_kwargs):
        if path.endswith("wine-binding.json"):
            return b"binding", "ok"
        if path.endswith("wine-native-stdout.jsonl"):
            return None, "oversized-or-unreadable"
        if path == f"{wine.RUN_ROOT}/RUN-bound.json":
            return current, "ok"
        return None, "missing"

    monkeypatch.setattr(wine, "_device_private", read_private)
    evidence = FakeEvidence()
    captured, incomplete = wine._capture_failure_evidence(SimpleNamespace(), evidence, "RUN-bound", None, None)
    assert captured and incomplete
    assert evidence.captures["capture-failure"]["authoritative-run.json"] == current
    status = json.loads((tmp_path / "failure-capture-statuses.json").read_bytes())
    assert status["files"]["authority"]["wine-native-stdout.jsonl"] == "oversized-or-unreadable"


def test_terminal_logs_are_required_even_when_a_stream_is_empty(monkeypatch) -> None:
    monkeypatch.setattr(wine, "_device_private", lambda *_args, **_kwargs: (b"", "ok"))
    assert all(raw == b"" for raw in wine._read_output_logs(SimpleNamespace(), "WS-" + "a" * 32).values())
    monkeypatch.setattr(wine, "_device_private", lambda *_args, **_kwargs: (None, "missing"))
    with pytest.raises(wine.CollectorError, match="output log"):
        wine._read_output_logs(SimpleNamespace(), "WS-" + "a" * 32)
