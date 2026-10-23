"""Offline tests for the bounded native vtest handshake runner."""

from __future__ import annotations

import importlib.util
import io
import json
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
PATH = ROOT / "scripts/diagnostics/android-vtest/run_handshake_usb.py"
SPEC = importlib.util.spec_from_file_location("vtest_handshake_runner", PATH)
assert SPEC and SPEC.loader
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


def _event(run: str, **changes: object) -> bytes:
    value: dict[str, object] = {
        "runId": run,
        "status": "CAPABILITIES_RECEIVED",
        "nativePid": 101,
        "uid": 10330,
        "peerPid": 202,
        "peerUid": 10330,
        "expectedServerPid": 202,
        "expectedServerStartTicks": 123,
        "serverIdentityVerified": True,
        "stages": runner.EXPECTED_STAGES,
        "capBytes": 33,
        "capNonzeroBytes": 32,
        "capsFNVhash": "0123456789abcdef",
        "durationMs": 12,
        **{name: False for name in runner.BROADER_FLAGS},
    }
    value.update(changes)
    return (json.dumps(value, separators=(",", ":")) + "\n").encode()


def _success(run: str, **changes: object) -> tuple[str, dict | None, str | None]:
    return runner.classify_handshake(
        run=run,
        server_stdout=b"",
        server_stderr=runner.SUCCESS_MARKER,
        client_stdout=_event(run, **changes),
        client_stderr=b"",
        server_exit=0,
        client_exit=0,
        timed_out=False,
        complete=True,
        expected_server={"pid": 202, "startTicks": 123},
    )


def test_client_pin_rejects_symlink_and_wrong_mode(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    artifact = tmp_path / "VtestHandshake"
    artifact.write_bytes(b"x" * runner.CLIENT_BYTES)
    artifact.chmod(runner.CLIENT_MODE)
    monkeypatch.setattr(runner, "CLIENT", artifact)
    monkeypatch.setattr(runner, "CLIENT_SHA256", __import__("hashlib").sha256(artifact.read_bytes()).hexdigest())
    assert runner.load_client()["bytes"] == runner.CLIENT_BYTES
    artifact.chmod(0o644)
    with pytest.raises(ValueError, match="metadata"):
        runner.load_client()
    artifact.chmod(runner.CLIENT_MODE)
    replacement = tmp_path / "replacement"
    replacement.write_bytes(artifact.read_bytes())
    artifact.unlink()
    artifact.symlink_to(replacement)
    with pytest.raises(ValueError, match="symlink"):
        runner.load_client()


def test_event_requires_exact_schema_identity_caps_and_false_broader_flags() -> None:
    run = "VTEST-test"
    assert runner.parse_client_event(_event(run), run)["capBytes"] == 33
    with pytest.raises(ValueError, match="strict JSON"):
        runner.parse_client_event(_event(run).replace(b'"status":"CAPABILITIES_RECEIVED"', b'"status":"CAPABILITIES_RECEIVED","status":"CAPABILITIES_RECEIVED"'), run)
    with pytest.raises(ValueError, match="broader"):
        runner.parse_client_event(_event(run, gpu=True), run)
    with pytest.raises(ValueError, match="capability size"):
        runner.parse_client_event(_event(run, capBytes=0), run)
    with pytest.raises(ValueError, match="schema"):
        extra = json.loads(_event(run))
        extra["schema"] = 1
        runner.parse_client_event((json.dumps(extra) + "\n").encode(), run)


def test_classification_never_turns_exit_zero_or_text_into_pass() -> None:
    run = "VTEST-test"
    status, event, _ = _success(run)
    assert status == "VTEST_CAPS_RECEIVED" and event
    for kwargs in (
        {"server_exit": 7},
        {"client_exit": 7},
        {"server_stdout": b"unexpected"},
        {"server_stderr": runner.SUCCESS_MARKER + b"\nfailed to initialise renderer."},
    ):
        base = {
            "run": run,
            "server_stdout": b"",
            "server_stderr": runner.SUCCESS_MARKER,
            "client_stdout": _event(run),
            "client_stderr": b"",
            "server_exit": 0,
            "client_exit": 0,
            "timed_out": False,
            "complete": True,
            "expected_server": {"pid": 202, "startTicks": 123},
        }
        base.update(kwargs)
        assert runner.classify_handshake(**base)[0] == "FAILED"
    assert runner.classify_handshake(
        run=run, server_stdout=b"", server_stderr=runner.SUCCESS_MARKER,
        client_stdout=b"", client_stderr=b"", server_exit=0, client_exit=0,
        timed_out=False, complete=False,
    )[0] == "UNKNOWN"
    assert runner.classify_handshake(
        run=run, server_stdout=b"", server_stderr=b"", client_stdout=_event(run),
        client_stderr=b"", server_exit=0, client_exit=0, timed_out=False, complete=True,
    )[0] == "UNKNOWN"
    assert runner.classify_handshake(
        run=run, server_stdout=b"", server_stderr=runner.SUCCESS_MARKER, client_stdout=_event(run),
        client_stderr=b"", server_exit=0, client_exit=0, timed_out=False, complete=True,
        expected_server={"pid": 203, "startTicks": 123},
    )[0] == "FAILED"


def test_server_binding_revalidates_instance_and_bounds_each_adb_call(monkeypatch: pytest.MonkeyPatch) -> None:
    root = "/data/user/0/com.wowforever.arm64probe/files/forever/controls/vtest-loader/VTEST-test"
    relative = "files/forever/controls/vtest-loader/VTEST-test"
    fields = ["S", "1", *(["0"] * 17), "123", "0"]
    stat_bytes = ("202 (virgl server) " + " ".join(fields)).encode()
    calls = []
    def fake_adb(*args, timeout):
        assert 0 < timeout <= 3
        calls.append(args)
        if args[-1] == relative + "/server.pid":
            data = b"202\n"
        elif args[-1] == "/proc/202/stat":
            data = stat_bytes
        elif args[-1] == "/proc/202/status":
            data = b"Name:\tvirgl_test_server\nUid:\t10330\t10330\t10330\t10330\n"
        elif args[-1] == "/proc/202/cmdline":
            data = (root + "/bin/virgl_test_server\0--no-loop-or-fork\0--use-gles\0--socket-path\0vtest.sock\0").encode()
        else:
            assert "server.identity" in args[-1] and "202 123" in args[-1]
            data = b"VTEST_SERVER_BOUND"
        return subprocess.CompletedProcess(args, 0, data, b"")
    monkeypatch.setattr(runner, "adb", fake_adb)
    result = runner.bind_server_instance(root, relative)
    assert result["pid"] == 202 and result["startTicks"] == 123
    assert sum(call[-1] == "/proc/202/stat" for call in calls) == 2


def test_official_source_stdout_sequence_requires_the_same_native_peer_caps_gates() -> None:
    run = "VTEST-test"
    base = {
        "run": run,
        "server_stdout": runner.SERVER_STDOUT_CAPS_SEQUENCE,
        "server_stderr": b"",
        "client_stdout": _event(run),
        "client_stderr": b"",
        "server_exit": 0,
        "client_exit": 0,
        "timed_out": False,
        "complete": True,
        "expected_server": {"pid": 202, "startTicks": 123},
    }
    status, event, reason = runner.classify_handshake(**base)
    assert status == "VTEST_CAPS_RECEIVED" and event and reason is None
    assert all(event[key] is False for key in runner.BROADER_FLAGS)
    for change in (
        {"server_stdout": runner.SERVER_STDOUT_CAPS_SEQUENCE + b"unexpected\n"},
        {"server_stdout": runner.SUCCESS_MARKER + b"\n"},
        {"server_stderr": b"failed to initialise renderer."},
        {"server_exit": 1},
        {"client_exit": 1},
        {"expected_server": {"pid": 203, "startTicks": 123}},
        {"client_stdout": _event(run, capBytes=0)},
    ):
        assert runner.classify_handshake(**{**base, **change})[0] == "FAILED"
    assert runner.classify_handshake(**{**base, "complete": False})[0] == "UNKNOWN"


def test_shell_command_has_only_fixed_cwd_env_and_no_network_port() -> None:
    command = runner._shell_command(
        "/data/user/0/com.wowforever.arm64probe/files/forever/controls/vtest-loader/VTEST-test",
        "virgl_test_server", ["--no-loop-or-fork", "--use-gles", "--socket-path", "vtest.sock"],
    )
    assert command[4] == "-T"
    script = command[-1]
    assert "cd /data/user/0/com.wowforever.arm64probe/files/forever/controls/vtest-loader/VTEST-test" in script
    assert "PATH=/system/bin" in script
    assert "ANDROID_ROOT=/system" in script and "ANDROID_DATA=/data" in script
    assert "--no-loop-or-fork --use-gles --socket-path vtest.sock" in script
    assert "--run-id" not in script
    assert "127.0.0.1" not in script and "tcp" not in script
    import shlex
    assert len(shlex.split(script)) == 1
    with pytest.raises(ValueError, match="arguments"):
        runner._shell_command(
            "/data/user/0/com.wowforever.arm64probe/files/forever/controls/vtest-loader/VTEST-test",
            "virgl_test_server", ["--use-glx"],
        )
    with pytest.raises(ValueError, match="working directory"):
        runner._shell_command("/data/user/0/com.wowforever.arm64probe/files", "VtestHandshake", ["--run-id", "VTEST-test"])
    assert runner.server_arguments("surfaceless") == ["--no-loop-or-fork", "--use-gles", "--use-egl-surfaceless", "--socket-path", "vtest.sock"]
    with pytest.raises(ValueError, match="mode"):
        runner.server_arguments("glx")


def test_fake_process_contract_uses_closed_stdin_and_preserves_capture_before_popen(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    run = "VTEST-fake"
    monkeypatch.setattr(runner, "_external_parent", lambda value: tmp_path)
    monkeypatch.setattr(runner, "load_candidate", lambda: ({}, [{"destination": "bin/virgl_test_server", "bytes": 1, "sha256": "x"}], b"manifest"))
    monkeypatch.setattr(runner, "load_client", lambda: {"path": tmp_path / "VtestHandshake", "bytes": runner.CLIENT_BYTES, "sha256": "c" * 64, "mode": "0700"})
    monkeypatch.setattr(runner, "remote_status_preflight", lambda: {"shellV2": True})
    monkeypatch.setattr(runner, "package_preflight", lambda: (10330, "/data/user/0/" + runner.PACKAGE, "ctx"))
    monkeypatch.setattr(runner, "system_alias_evidence", lambda: [])
    monkeypatch.setattr(runner, "own_processes", lambda uid=runner.UID: [])
    monkeypatch.setattr(runner, "copy_payload", lambda files, run: ("files/forever/controls/vtest-loader/" + run, {"stagingClean": True}))
    monkeypatch.setattr(runner, "copy_client", lambda client, private: {"destination": private + "/bin/VtestHandshake", "bytes": runner.CLIENT_BYTES, "sha256": "c" * 64, "mode": "0700", "cleanup": {"stagingClean": True}})
    monkeypatch.setattr(runner, "bind_server_instance", lambda *args: {"pid": 202, "startTicks": 123})
    commands: list[list[str]] = []

    class FakeProcess:
        next_pid = 700

        def __init__(self, command: list[str], stdout: bytes, stderr: bytes) -> None:
            self.command = command
            self.pid = FakeProcess.next_pid
            FakeProcess.next_pid += 1
            self.returncode = 0
            self.stdout = io.BytesIO(stdout)
            self.stderr = io.BytesIO(stderr)

        def poll(self) -> int:
            return self.returncode

        def wait(self, timeout: float | None = None) -> int:
            return self.returncode

        def terminate(self) -> None:
            self.returncode = -15

    def start(command: list[str], **kwargs: object) -> FakeProcess:
        assert kwargs["stdin"] == subprocess.DEVNULL
        assert kwargs.get("shell", False) is False
        commands.append(command)
        if len(commands) == 1:
            return FakeProcess(command, b"", runner.SUCCESS_MARKER)
        return FakeProcess(command, _event(run), b"")

    monkeypatch.setattr(runner.subprocess, "Popen", start)
    # The generated UUID is fixed so the fake event has the exact run ID.
    monkeypatch.setattr(runner.uuid, "uuid4", lambda: type("UUID", (), {"hex": "fake"})())
    result = runner.main(["--output", str(tmp_path), "--seconds", "5"])
    assert result == 0  # protocol result only; summary still keeps all broad flags false
    assert len(commands) == 2
    assert all(command[4] == "-T" for command in commands)
    capture = next(tmp_path.glob("native-*/capture.json"))
    assert capture.exists()
    commands.clear()
    monkeypatch.setattr(runner.uuid, "uuid4", lambda: type("UUID", (), {"hex": "failure"})())
    def failed_capture(*args: object) -> None:
        raise OSError("evidence full")
    monkeypatch.setattr(runner, "_start_capture", failed_capture)
    with pytest.raises(OSError, match="evidence full"):
        runner.main(["--output", str(tmp_path), "--seconds", "5"])
    assert commands == []
