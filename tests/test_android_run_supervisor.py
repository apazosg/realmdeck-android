"""Offline and host-only tests for the per-RUN subreaper capacity control."""

from __future__ import annotations

import importlib.util
import json
import os
import shlex
import shutil
import subprocess
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
RUNNER_PATH = ROOT / "scripts/diagnostics/android-process/run_supervisor_capacity.py"
SPEC = importlib.util.spec_from_file_location("run_supervisor_capacity_runner", RUNNER_PATH)
assert SPEC and SPEC.loader
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)

RUN = "RS-0123456789abcdef0123456789abcdef"


def _host_binary(tmp_path: Path) -> Path:
    compiler = shutil.which("cc")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    binary = tmp_path / "RunSupervisorCapacity"
    subprocess.run([compiler, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-o", str(binary),
                    str(ROOT / "scripts/diagnostics/android-process/run_supervisor_capacity.c")], check=True)
    return binary


def _host_run(binary: Path, *extra: str) -> tuple[int, bytes]:
    completed = subprocess.run([str(binary), "--run-id", RUN, *extra], capture_output=True, timeout=25, check=False)
    assert completed.stderr == b""
    return completed.returncode, completed.stdout


def _android_uid(raw: bytes) -> bytes:
    return raw.replace(f":{os.getuid()}".encode(), b":10330")


def test_host_normal_run_proves_adoption_scoped_cleanup_and_echild(tmp_path: Path) -> None:
    binary = _host_binary(tmp_path)
    exit_code, raw = _host_run(binary)
    assert exit_code == 0
    lines = [json.loads(line) for line in raw.splitlines()]
    assert [line["event"] for line in lines] == ["root", "witness_before", "supervisor", "normal_birth", "normal_adoption", "cleanup_birth", "cleanup_adoption", "witness_exit", "result"]
    result = lines[-1]
    assert result["status"] == "PASS"
    assert result["cleanupConfirmed"] is True
    assert result["witnessStable"] is True
    assert result["waitpidEchild"] is True
    assert result["cleanupGrandchildExit"] == 143
    assert result["rootWaitpidEchild"] is True


# A partial family/IPC failure cannot claim confirmed family cleanup, even if
# the finite watchdogs subsequently let the root reap everything: UNKNOWN is
# deliberately stronger than pretending the failed operation was fully known.
@pytest.mark.parametrize("fault,expected", [("prctl", 1), ("fork", 2), ("ipc", 2), ("reap", 1), ("timeout", 2), ("kill", 2), ("deadline", 2)])
def test_host_fault_injection_is_nonpass_and_finite(tmp_path: Path, fault: str, expected: int) -> None:
    binary = _host_binary(tmp_path)
    exit_code, raw = _host_run(binary, "--fault", fault)
    assert exit_code == expected
    document = json.loads(raw.splitlines()[-1])
    assert document["event"] == "result"
    assert document["status"] in {"FAIL", "UNKNOWN"}
    assert document["status"] != "PASS"


def test_runner_accepts_only_complete_pass_evidence_and_rejects_forgery(tmp_path: Path) -> None:
    binary = _host_binary(tmp_path)
    exit_code, raw = _host_run(binary)
    assert exit_code == 0
    parsed = runner.parse_native(_android_uid(raw), RUN, adb_exit=0)
    assert parsed["status"] == "PASS"

    documents = [json.loads(line) for line in _android_uid(raw).splitlines()]
    documents[-1]["witnessStable"] = False
    forged = b"".join((json.dumps(item, separators=(",", ":")).encode() + b"\n") for item in documents)
    with pytest.raises(ValueError, match="PASS"):
        runner.parse_native(forged, RUN, adb_exit=0)

    missing = b"\n".join(_android_uid(raw).splitlines()[:-2]) + b"\n"
    with pytest.raises(ValueError, match="order|incomplete"):
        runner.parse_native(missing, RUN)

    wrong_uid = _android_uid(raw).replace(b'"witnessUid":10330', b'"witnessUid":10331', 1)
    with pytest.raises(ValueError, match="UID"):
        runner.parse_native(wrong_uid, RUN, adb_exit=0)
    wrong_euid = _android_uid(raw).replace(b'"witnessEuid":10330', b'"witnessEuid":10331', 1)
    with pytest.raises(ValueError, match="UID"):
        runner.parse_native(wrong_euid, RUN, adb_exit=0)


def test_runner_rejects_explicit_deadline_without_complete_identity_evidence(tmp_path: Path) -> None:
    binary = _host_binary(tmp_path)
    exit_code, raw = _host_run(binary, "--fault", "deadline")
    assert exit_code == 2
    parsed = runner.parse_native(_android_uid(raw), RUN, adb_exit=2)
    assert parsed["status"] == "UNKNOWN" and parsed["failureStage"] == "timeout"


def test_parser_crosslinks_terminal_identity_and_subreaper_facts(tmp_path: Path) -> None:
    binary = _host_binary(tmp_path)
    _exit_code, raw = _host_run(binary)
    documents = [json.loads(line) for line in _android_uid(raw).splitlines()]
    result = documents[-1]
    result["normalAdoptionStartTicks"] += 1
    forged = b"".join(json.dumps(item, separators=(",", ":")).encode() + b"\n" for item in documents)
    with pytest.raises(ValueError, match="cross-link"):
        runner.parse_native(forged, RUN, adb_exit=0)
    result["normalAdoptionStartTicks"] -= 1
    result["supervisorGetValue"] = 0
    forged = b"".join(json.dumps(item, separators=(",", ":")).encode() + b"\n" for item in documents)
    with pytest.raises(ValueError, match="PASS"):
        runner.parse_native(forged, RUN, adb_exit=0)


def test_explicit_prctl_failure_is_preserved_as_fail_not_reclassified_pass(tmp_path: Path) -> None:
    binary = _host_binary(tmp_path)
    exit_code, raw = _host_run(binary, "--fault", "prctl")
    assert exit_code == 1
    documents = [json.loads(line) for line in _android_uid(raw).splitlines()]
    assert documents[-1]["status"] == "FAIL" and documents[-1]["failureStage"] == "prctl"
    assert runner.parse_native(_android_uid(raw), RUN, adb_exit=1)["status"] == "FAIL"

    root_only = [documents[0], documents[-1]]
    result = root_only[-1]
    result["status"] = "FAIL"
    result["failureStage"] = "prctl"
    result["nativeExit"] = 1
    for prefix in ("supervisor", "witness", "normalIntermediate", "normalBirth", "normalAdoption",
                   "cleanupIntermediate", "cleanupBirth", "cleanupAdoption"):
        for suffix in ("Pid", "Ppid", "Uid", "Euid", "Suid", "Fsuid", "StartTicks"):
            result[f"{prefix}{suffix}"] = 0
    forged_root_failure = b"".join(json.dumps(item, separators=(",", ":")).encode() + b"\n" for item in root_only)
    assert runner.parse_native(_android_uid(forged_root_failure), RUN, adb_exit=1)["status"] == "FAIL"


def test_bounded_adb_reader_stops_on_overflow_without_unbounded_buffer() -> None:
    # The reader contract itself is tested with a bounded fake stream; it must
    # set overflow rather than retain bytes beyond the configured cap.
    class Stream:
        def __init__(self):
            self.done = False

        def read(self, _size):
            if self.done:
                return b""
            self.done = True
            return b"x" * (runner.NATIVE_STREAM_LIMIT + 1)

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

    overflow = __import__("threading").Event()
    box: dict[str, bytes] = {}
    runner._bounded_reader(Stream(), runner.NATIVE_STREAM_LIMIT, box, "stdout", overflow)
    assert overflow.is_set() and len(box["stdout"]) == runner.NATIVE_STREAM_LIMIT

    with pytest.raises(RuntimeError, match="overflow"):
        runner._bounded_subprocess([sys.executable, "-c", "import sys; sys.stdout.write('x' * 70000)"], timeout=3)


def test_invalid_device_stops_before_private_upload_or_mutation(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    preflight = tmp_path / "preflight"
    preflight.mkdir()
    record = {
        "result": "READY_FOR_SINGLE_APPROVED_TRIAL", "serial": runner.SERIAL, "package": runner.PACKAGE,
        "versionCode": 55, "installedApkSha256": runner.APK_SHA256, "retainedArtifactsVerified": True,
        "bootBeforeReboot": "11111111-1111-1111-1111-111111111111",
        "bootAfterReboot": "22222222-2222-2222-2222-222222222222", "ownUidProcessSnapshot": f"UID PID PPID NAME\n{runner.UID} 1 1 ps\n",
    }
    (preflight / "state-00-READY_FOR_SINGLE_APPROVED_TRIAL.json").write_text(json.dumps(record), encoding="utf-8")
    evidence = tmp_path / "evidence"
    evidence.mkdir()
    monkeypatch.setattr(runner, "device_preflight", lambda _value: (_ for _ in ()).throw(RuntimeError("invalid device")))
    uploaded = False

    def forbidden_upload(*_args: object, **_kwargs: object) -> None:
        nonlocal uploaded
        uploaded = True
        raise AssertionError("upload must not run")

    monkeypatch.setattr(runner, "upload_native", forbidden_upload)
    with pytest.raises(RuntimeError, match="invalid device"):
        runner.main(["--preflight-directory", str(preflight), "--output", str(evidence)])
    assert uploaded is False
    assert list(evidence.iterdir()) == []


def test_artifact_mismatch_stops_before_any_device_action(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(runner, "NATIVE_SHA256", "f" * 64)
    monkeypatch.setattr(runner, "_adb", lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("ADB must not run")))
    with pytest.raises(ValueError, match="artifact hash"):
        runner.main(["--preflight-directory", str(tmp_path), "--output", str(tmp_path)])


def test_private_command_has_fixed_linker_and_no_shared_staging() -> None:
    root = f"/data/user/0/{runner.PACKAGE}/{runner._private_relative(RUN)}"
    command = runner.shell_command(root, RUN)
    assert command[:7] == [runner.ADB, "-s", runner.SERIAL, "shell", "-T", "run-as", runner.PACKAGE]
    script = command[-1]
    assert shlex.split(script) == [script[1:-1]]
    assert "/system/bin/linker64" in script
    assert "/data/local/tmp" not in script
    assert "LD_PRELOAD" not in script


def test_upload_is_one_quoted_script_and_checks_correct_stat_columns(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    binary = tmp_path / "native"
    binary.write_bytes(b"test")
    native = {"path": binary, "sha256": __import__("hashlib").sha256(b"test").hexdigest(), "bytes": 4}
    destination = runner._private_relative(RUN) + "/" + runner.NATIVE_NAME
    calls = []
    def fake_adb(*args, **kwargs):
        calls.append(args)
        if args[0] == "shell":
            script = shlex.split(args[-1])
            assert len(script) == 1 and "set -eu;" in script[0]
            assert kwargs["input_data"] == b"test"
            return subprocess.CompletedProcess(args, 0, b"RUN_SUPERVISOR_UPLOAD_OK", b"")
        if "sha256sum" in args:
            value = f"{native['sha256']}  {destination}\n"
        else:
            value = f"regular file {runner.UID} 700 4 1 65079 12345\n"
        return subprocess.CompletedProcess(args, 0, value.encode(), b"")
    monkeypatch.setattr(runner, "_adb", fake_adb)
    uploaded = runner.upload_native(native, RUN, f"/data/user/0/{runner.PACKAGE}")
    assert uploaded["stat"]["mode"] == "700" and uploaded["stat"]["uid"] == runner.UID
    assert len([call for call in calls if call[0] == "shell"]) == 1


def test_blocked_upload_stdin_shares_the_command_deadline() -> None:
    import time
    start = time.monotonic()
    with pytest.raises(RuntimeError, match="deadline"):
        runner._bounded_subprocess([sys.executable, "-c", "import time; time.sleep(10)"], timeout=0.1, input_data=b"x" * 65536)
    assert time.monotonic() - start < 3


@pytest.mark.parametrize("change", ["intermediate-parent", "adoption-identity", "duplicate-event"])
def test_parser_rejects_internally_linked_but_false_ancestry(tmp_path: Path, change: str) -> None:
    binary = _host_binary(tmp_path)
    code, raw = _host_run(binary)
    assert code == 0
    documents = [json.loads(line) for line in _android_uid(raw).splitlines()]
    terminal = documents[-1]
    if change == "intermediate-parent":
        terminal["normalIntermediatePpid"] = terminal["rootPid"]
    elif change == "adoption-identity":
        terminal["normalAdoptionStartTicks"] += 1
        next(item for item in documents if item["event"] == "normal_adoption")["startTicks"] += 1
    else:
        documents.insert(4, documents[3])
    forged = b"".join(json.dumps(item, separators=(",", ":")).encode() + b"\n" for item in documents)
    with pytest.raises(ValueError, match="PPID|identity|order"):
        runner.parse_native(forged, RUN, adb_exit=0)
