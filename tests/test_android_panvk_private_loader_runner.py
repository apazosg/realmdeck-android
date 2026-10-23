"""Offline contract tests for the fixed PanVK private-loader HOST runner."""

from __future__ import annotations

import importlib.util
import io
import json
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
PATH = ROOT / "scripts/diagnostics/android-panvk/run_private_loader_usb.py"
SPEC = importlib.util.spec_from_file_location("panvk_private_loader_runner", PATH)
assert SPEC and SPEC.loader
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)

RUN = "PVK-0123456789abcdef0123456789abcdef"
PRIVATE = f"/data/user/0/{runner.PACKAGE}/{runner._private_relative(RUN)}"


def _identity(valid: bool = True, *, inode: int = 22) -> dict[str, object]:
    if not valid:
        return {"regular": False, "ownerUid": 0, "device": 0, "inode": 0, "mode": 0, "size": 0}
    return {"regular": True, "ownerUid": runner.UID, "device": 11, "inode": inode, "mode": 0o600, "size": runner.ICD_BYTES}


def _native(status: str = "PRIVATE_ICD_LOADER_OBSERVED", **changes: object) -> dict[str, object]:
    icd = f"{PRIVATE}/{runner.ICD_NAME}"
    value: dict[str, object] = {
        "schema": runner.NATIVE_SCHEMA,
        "version": 1,
        "runId": RUN,
        "pid": 101,
        "uid": runner.UID,
        "pgid": 101,
        "sid": 101,
        "monotonicStartNs": 10,
        "monotonicEndNs": 20,
        "cwd": PRIVATE,
        "scopeValid": True,
        "icdPath": icd,
        "icdBytes": runner.ICD_BYTES,
        "icdSha256": runner.ICD_SHA256,
        "icdIdentityBefore": _identity(),
        "icdIdentityAfter": _identity(),
        "icdIdentityStable": True,
        "load": {"attempted": True, "loaded": True, "dlerror": ""},
        "exports": [
            {"name": name, "found": True, "owner": icd, "ownerExact": True, "error": ""}
            for name in runner.EXPORT_NAMES
        ],
        "cleanup": {"dlcloseAttempted": True, "dlcloseOk": True, "complete": True, "errno": 0, "dlerror": ""},
        "icdCodeLoaded": True,
        "constructorsMayHaveExecuted": True,
        "vulkanFunctionsInvoked": False,
        "apiComplete": False,
        "vulkan": False,
        "gpu": False,
        "triangle": False,
        "presentation": False,
        "d3d": False,
        "wow": False,
        "status": status,
        "failureStage": "none",
        "failureDetail": "",
        "nativeExit": 0,
    }
    value.update(changes)
    return value


def _raw(document: dict[str, object]) -> bytes:
    return (json.dumps(document, separators=(",", ":")) + "\n").encode()


def _loader_failure() -> dict[str, object]:
    value = _native(
        "LOADER_FAILED",
        load={"attempted": True, "loaded": False, "dlerror": "missing dependency libprivate.so"},
        exports=[
            {"name": name, "found": False, "owner": "", "ownerExact": False, "error": ""}
            for name in runner.EXPORT_NAMES
        ],
        cleanup={"dlcloseAttempted": False, "dlcloseOk": False, "complete": True, "errno": 0, "dlerror": ""},
        icdCodeLoaded=False,
        failureStage="loader",
        failureDetail="missing dependency libprivate.so",
        nativeExit=1,
    )
    return value


def test_current_pins_and_manifest_are_derived_and_validate_without_device() -> None:
    pytest.skip(
        "closed beta16 gate: the shared helper PanvkPrivateLoader.c advanced to beta.17 "
        "(cc8ea0c3...) and the frozen private-loader manifest binds the older helper, so "
        "load_native() now correctly fails closed. Superseded 2026-10-07."
    )
    artifact = runner.load_native()
    assert artifact["bytes"] == 23392
    assert artifact["sha256"] == "7a7861c8b5e6795a8ecee8ee9794a3abcd0d076b5d307c6d2b0d092ce198024a"
    assert artifact["manifestSha256"] == "786f25b5fd5637fec3166ea220ef64e1e27924495e4ff40ff76e3d32766be732"
    assert artifact["icd"]["sha256"] == runner.ICD_SHA256


def test_parser_accepts_only_the_two_real_native_outcomes_and_keeps_broad_flags_false() -> None:
    passed = runner.parse_native(_raw(_native()), RUN, PRIVATE, adb_exit=0)
    assert passed["status"] == "PRIVATE_ICD_LOADER_OBSERVED"
    assert all(passed[name] is False for name in ("vulkanFunctionsInvoked", "apiComplete", "vulkan", "gpu", "triangle", "presentation", "d3d", "wow"))
    failed = runner.parse_native(_raw(_loader_failure()), RUN, PRIVATE, adb_exit=1)
    assert failed["status"] == "LOADER_FAILED"
    assert failed["load"]["loaded"] is False
    assert failed["cleanup"] == {"dlcloseAttempted": False, "dlcloseOk": False, "complete": True, "errno": 0, "dlerror": ""}


@pytest.mark.parametrize("change", [
    lambda d: d.update(pid=True),
    lambda d: d.update(scopeValid=False),
    lambda d: d.update(cwd=PRIVATE.replace("/data/user/0/", "/tmp/")),
    lambda d: d.update(monotonicEndNs=0),
    lambda d: d.update(monotonicEndNs=runner.NATIVE_DEADLINE_SECONDS * 1_000_000_000 + 11),
    lambda d: d["exports"].__setitem__(0, {**d["exports"][0], "owner": "/other/icd.so", "ownerExact": False, "error": "owner mismatch"}),
    lambda d: d.update(cleanup={"dlcloseAttempted": False, "dlcloseOk": True, "complete": True, "errno": 0, "dlerror": ""}),
])
def test_parser_rejects_invalid_scope_identity_owner_timing_and_cleanup(change) -> None:
    document = _native()
    change(document)
    with pytest.raises(ValueError):
        runner.parse_native(_raw(document), RUN, PRIVATE, adb_exit=0)


def test_parser_rejects_duplicate_keys_bools_truncation_bad_status_and_bad_load_error() -> None:
    duplicate = _raw(_native()).replace(b'"status":"PRIVATE_ICD_LOADER_OBSERVED"', b'"status":"PRIVATE_ICD_LOADER_OBSERVED","status":"PRIVATE_ICD_LOADER_OBSERVED"')
    with pytest.raises(ValueError, match="duplicate"):
        runner.parse_native(duplicate, RUN, PRIVATE)
    bool_uid = _native(uid=True)
    with pytest.raises(ValueError):
        runner.parse_native(_raw(bool_uid), RUN, PRIVATE)
    bad_status = _native(status="PASS")
    with pytest.raises(ValueError):
        runner.parse_native(_raw(bad_status), RUN, PRIVATE)
    for malformed_status in ([], {}, True, 0, None):
        with pytest.raises(ValueError, match="status"):
            runner.parse_native(_raw(_native(status=malformed_status)), RUN, PRIVATE)
    oversized = _loader_failure()
    oversized["load"] = {"attempted": True, "loaded": False, "dlerror": "é" * 300}
    with pytest.raises(ValueError, match="512"):
        runner.parse_native(_raw(oversized), RUN, PRIVATE, adb_exit=1)
    with pytest.raises(ValueError):
        runner.parse_native(_raw(_native()) + b"extra", RUN, PRIVATE, adb_exit=0)


def test_shell_command_has_exact_cwd_environment_and_no_arbitrary_loader_inputs() -> None:
    command = runner.shell_command(PRIVATE, RUN)
    script = command[-1]
    assert command[:7] == [runner.ADB, "-s", runner.SERIAL, "shell", "-T", "run-as", runner.PACKAGE]
    assert "exec /system/bin/env -i" in script
    assert all(item in script for item in ("PATH=/system/bin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data", "LD_LIBRARY_PATH=/system/lib64", f"TMPDIR={PRIVATE}"))
    assert "/system/bin/linker64" in script and "LD_PRELOAD" not in script
    with pytest.raises(ValueError):
        runner.shell_command(PRIVATE.replace(RUN, "../" + RUN), RUN)
    with pytest.raises(ValueError):
        runner.shell_command(PRIVATE.replace("/data/user/0/", "/tmp/"), RUN)


def test_copy_uses_two_exclusive_files_verifies_hash_modes_and_cleans_only_owned_stage(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[tuple[tuple[str, ...], dict[str, object]]] = []
    monkeypatch.setattr(runner.uuid, "uuid4", lambda: type("U", (), {"hex": "a"})())

    def fake_adb(*args: str, timeout: int = 15) -> subprocess.CompletedProcess[bytes]:
        calls.append((args, {"timeout": timeout}))
        if args[:2] == ("exec-out", "run-as") and args[3] == "sha256sum":
            path = args[-1]
            digest = runner.ICD_SHA256 if path.endswith(runner.ICD_NAME) else "h" * 64
            expected = runner.ICD_SHA256 if path.endswith(runner.ICD_NAME) else "p" * 64
            # The fake native pin is supplied below; both paths still receive an exact response.
            digest = expected
            return subprocess.CompletedProcess(args, 0, f"{digest} {path}\n".encode(), b"")
        if args[:2] == ("exec-out", "run-as") and args[3] == "stat":
            return subprocess.CompletedProcess(args, 0, (b"600\n" if args[-1].endswith(runner.ICD_NAME) else b"700\n"), b"")
        if args[:3] == ("exec-out", "run-as", runner.PACKAGE) and args[3] == "/system/bin/sh":
            return subprocess.CompletedProcess(args, 0, b"PANVK_PRIVATE_UPLOAD_OK", b"")
        return subprocess.CompletedProcess(args, 0, b"", b"")

    monkeypatch.setattr(runner, "adb", fake_adb)
    native = {"path": Path("probe"), "sha256": "p" * 64}
    relative, cleanup = runner.copy_native(native, RUN)
    assert relative.endswith(RUN)
    assert cleanup["device"]["probe"]["mode"] == "0700"
    assert next(args for args, _ in calls if args[:2] == ("shell", "mkdir"))[2:4] == ("-m", "755")
    copy = next(args[-1] for args, _ in calls if args[:3] == ("exec-out", "run-as", runner.PACKAGE) and args[3] == "/system/bin/sh")
    assert "mkdir -m 700" in copy and "chmod 0600" in copy and "chmod 0700" in copy
    pushes = [args for args, _ in calls if args and args[0] == "push"]
    assert len(pushes) == 2 and pushes[0][2] != pushes[1][2]
    assert [args[1] for args, _ in calls if args and args[0] == "shell" and args[1] in {"rm", "rmdir"}] == ["rm", "rm", "rmdir"]


def test_mkdir_timeout_attempts_only_empty_owned_directory_cleanup(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[tuple[str, ...]] = []

    def fake_adb(*args: str, **kwargs: object) -> subprocess.CompletedProcess[bytes]:
        calls.append(args)
        if args[:2] == ("shell", "mkdir"):
            raise subprocess.TimeoutExpired(args, 15)
        return subprocess.CompletedProcess(args, 0, b"", b"")

    monkeypatch.setattr(runner, "adb", fake_adb)
    with pytest.raises(subprocess.TimeoutExpired):
        runner.copy_native({"path": Path("probe"), "sha256": "p" * 64}, RUN)
    assert [args[1] for args in calls] == ["test", "mkdir", "rmdir"]
    assert calls[-1][-1].startswith(runner.STAGE_PREFIX)  # only the owned UUID stage is removed


def test_capture_is_durable_before_popen_stdin_closed_and_native_failure_is_preserved(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(runner, "_external_parent", lambda value: tmp_path)
    monkeypatch.setattr(runner, "load_native", lambda: {"path": Path("probe"), "sha256": "p" * 64, "bytes": 1, "source": {}})
    monkeypatch.setattr(runner, "remote_status_preflight", lambda: {"shellV2": True, "sentinelExitCode": 37})
    monkeypatch.setattr(runner, "package_preflight", lambda: (runner.UID, f"/data/user/0/{runner.PACKAGE}", "ctx"))
    monkeypatch.setattr(runner, "_api_preflight", lambda: runner.DEVICE_API_LEVEL)
    monkeypatch.setattr(runner, "copy_native", lambda native, run: (runner._private_relative(run), {"stagingClean": True}))
    monkeypatch.setattr(runner.uuid, "uuid4", lambda: type("U", (), {"hex": RUN[4:]})())
    monkeypatch.setattr(runner, "own_processes", lambda uid: [])
    observed: list[dict[str, object]] = []

    class Process:
        pid = 700
        returncode = 1
        stdout = io.BytesIO(_raw(_loader_failure()))
        stderr = io.BytesIO()

        def wait(self, timeout: float | None = None) -> int:
            return self.returncode

        def poll(self) -> int:
            return self.returncode

    def start(command: list[str], **kwargs: object) -> Process:
        capture = tmp_path / ("native-" + RUN) / "capture.json"
        observed.append(json.loads(capture.read_text()))
        assert kwargs["stdin"] is subprocess.DEVNULL
        assert kwargs["shell"] is False
        return Process()

    monkeypatch.setattr(runner.subprocess, "Popen", start)
    assert runner.main(["--output", str(tmp_path)]) == 1
    assert observed[0]["executionStarted"] is False
    summary = json.loads((tmp_path / ("native-" + RUN) / "summary.json").read_text())
    assert summary["status"] == "LOADER_FAILED"
    assert summary["apiCompatibilityProven"] is False
    assert summary["vulkanFunctionsInvoked"] is False


def test_capture_write_failure_prevents_popen(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(runner, "_external_parent", lambda value: tmp_path)
    monkeypatch.setattr(runner, "load_native", lambda: {"path": Path("probe"), "sha256": "p" * 64, "bytes": 1, "source": {}})
    monkeypatch.setattr(runner, "remote_status_preflight", lambda: {"shellV2": True, "sentinelExitCode": 37})
    monkeypatch.setattr(runner, "package_preflight", lambda: (runner.UID, f"/data/user/0/{runner.PACKAGE}", "ctx"))
    monkeypatch.setattr(runner, "_api_preflight", lambda: runner.DEVICE_API_LEVEL)
    monkeypatch.setattr(runner, "copy_native", lambda native, run: (runner._private_relative(run), {"stagingClean": True}))
    monkeypatch.setattr(runner, "_write_exclusive", lambda *args: (_ for _ in ()).throw(OSError("evidence full")))
    monkeypatch.setattr(runner.subprocess, "Popen", lambda *args, **kwargs: pytest.fail("Popen after capture failure"))
    with pytest.raises(OSError, match="evidence full"):
        runner.main(["--output", str(tmp_path)])


def test_timeout_and_adb_force_stop_exception_still_reap_the_held_host_handle(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(runner, "_external_parent", lambda value: tmp_path)
    monkeypatch.setattr(runner, "load_native", lambda: {"path": Path("probe"), "sha256": "p" * 64, "bytes": 1, "source": {}})
    monkeypatch.setattr(runner, "remote_status_preflight", lambda: {"shellV2": True, "sentinelExitCode": 37})
    monkeypatch.setattr(runner, "package_preflight", lambda: (runner.UID, f"/data/user/0/{runner.PACKAGE}", "ctx"))
    monkeypatch.setattr(runner, "_api_preflight", lambda: runner.DEVICE_API_LEVEL)
    monkeypatch.setattr(runner, "copy_native", lambda native, run: (runner._private_relative(run), {"stagingClean": True}))
    monkeypatch.setattr(runner.uuid, "uuid4", lambda: type("U", (), {"hex": RUN[4:]})())
    monkeypatch.setattr(runner, "own_processes", lambda uid: [])

    class SlowProcess:
        pid = 701
        returncode: int | None = None
        stdout = io.BytesIO()
        stderr = io.BytesIO()

        def __init__(self) -> None:
            self.waits = 0
            self.terminated = False

        def wait(self, timeout: float | None = None) -> int:
            self.waits += 1
            if self.waits == 1:
                raise subprocess.TimeoutExpired("adb", timeout)
            self.returncode = -15
            return self.returncode

        def poll(self) -> int | None:
            return self.returncode

        def terminate(self) -> None:
            self.terminated = True

        def kill(self) -> None:
            self.returncode = -9

    child = SlowProcess()
    monkeypatch.setattr(runner.subprocess, "Popen", lambda *args, **kwargs: child)
    monkeypatch.setattr(runner, "adb", lambda *args, **kwargs: (_ for _ in ()).throw(OSError("ADB unavailable")))
    assert runner.main(["--output", str(tmp_path)]) == 1
    assert child.waits >= 2
    summary = json.loads((tmp_path / ("native-" + RUN) / "summary.json").read_text())
    assert summary["timedOut"] is True
    assert summary["forceStop"]["returnCode"] is None
