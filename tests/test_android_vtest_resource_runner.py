"""Offline HOST-only tests for the bounded native resource runner."""

from __future__ import annotations

import dataclasses
import importlib.util
import json
import stat
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
PATH = ROOT / "scripts/diagnostics/android-vtest/run_resource_usb.py"
SPEC = importlib.util.spec_from_file_location("vtest_resource_runner", PATH)
assert SPEC and SPEC.loader
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


def _event(run: str, **changes: object) -> bytes:
    value: dict[str, object] = {
        "runId": run,
        "status": runner.NATIVE_SUCCESS_STATUS,
        "nativePid": 101,
        "uid": 10330,
        "peerPid": 202,
        "peerUid": 10330,
        "expectedServerPid": 202,
        "expectedServerStartTicks": 123,
        "serverIdentityVerified": True,
        "stages": runner.RESOURCE_STAGES,
        "capBytes": 33,
        "capNonzeroBytes": 32,
        "capsFNVhash": "0123456789abcdef",
        "durationMs": 12,
        "resourceId": 1,
        "resourceBytes": 4,
        "writtenHex": runner.RESOURCE_HEX,
        "readbackHex": runner.RESOURCE_HEX,
        "resourceTransferVerified": True,
        "resourceUnrefSent": True,
        **{name: False for name in runner.shared.BROADER_FLAGS},
    }
    value.update(changes)
    return (json.dumps(value, separators=(",", ":")) + "\n").encode()


def _classify(run: str, **changes: object) -> tuple[str, dict | None, str | None]:
    return runner.classify_resource(
        run=run,
        server_stdout=runner.shared.SERVER_STDOUT_CAPS_SEQUENCE,
        server_stderr=b"bounded GL identity log\n",
        client_stdout=_event(run, **changes),
        client_stderr=b"",
        server_exit=0,
        client_exit=0,
        timed_out=False,
        complete=True,
        expected_server={"pid": 202, "startTicks": 123},
    )


def test_resource_event_has_exact_schema_and_roundtrip_qualification() -> None:
    run = "VTEST-resource"
    status, event, reason = _classify(run)
    assert status == runner.SUCCESS_STATUS and event and reason is None
    assert set(event) == runner.RESOURCE_KEYS
    for field in runner.shared.BROADER_FLAGS:
        assert event[field] is False


@pytest.mark.parametrize(
    "change",
    [
        {"extra": 1},
        {"runId": "VTEST-other"},
        {"peerPid": 203},
        {"expectedServerStartTicks": 124},
        {"resourceBytes": 5},
        {"readbackHex": "a1b2c3d5"},
        {"resourceUnrefSent": False},
        {"stages": runner.RESOURCE_STAGES[:-1]},
        {"resourceTransferVerified": 1},
    ],
)
def test_resource_event_rejects_schema_identity_and_transfer_changes(change: dict[str, object]) -> None:
    run = "VTEST-resource"
    raw = _event(run, **change)
    if "extra" in change:
        with pytest.raises(ValueError, match="schema"):
            runner.parse_resource_event(raw, run)
    else:
        assert runner.classify_resource(
            run=run,
            server_stdout=runner.shared.SERVER_STDOUT_CAPS_SEQUENCE,
            server_stderr=b"",
            client_stdout=raw,
            client_stderr=b"",
            server_exit=0,
            client_exit=0,
            timed_out=False,
            complete=True,
            expected_server={"pid": 202, "startTicks": 123},
        )[0] != runner.SUCCESS_STATUS


def test_resource_classifier_rejects_truncation_markers_unknown_and_raw_exit_zero() -> None:
    run = "VTEST-resource"
    base = dict(
        run=run,
        server_stdout=runner.shared.SERVER_STDOUT_CAPS_SEQUENCE,
        server_stderr=b"",
        client_stdout=_event(run),
        client_stderr=b"",
        server_exit=0,
        client_exit=0,
        timed_out=False,
        complete=True,
        expected_server={"pid": 202, "startTicks": 123},
    )
    assert runner.classify_resource(**{**base, "complete": False})[0] == "UNKNOWN"
    assert runner.classify_resource(**{**base, "client_stdout": b"0\n"})[0] != runner.SUCCESS_STATUS
    assert runner.classify_resource(**{**base, "server_stdout": b""})[0] != runner.SUCCESS_STATUS
    assert runner.classify_resource(**{**base, "server_stderr": b"failed to initialise renderer."})[0] == "FAILED"
    assert runner.classify_resource(**{**base, "forced_cleanup": True})[0] == "UNKNOWN"


def test_native_status_is_distinct_from_host_qualification_and_host_limit_is_fixed(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path,
) -> None:
    assert runner.NATIVE_SUCCESS_STATUS == "RESOURCE_ROUNDTRIP"
    assert runner.SUCCESS_STATUS == "VTEST_RESOURCE_ROUNDTRIP"
    assert _classify("VTEST-resource")[0] == runner.SUCCESS_STATUS
    assert _classify("VTEST-resource", status=runner.SUCCESS_STATUS)[0] == "FAILED"
    calls = []
    monkeypatch.setattr(runner.shared, "main", lambda args, **kwargs: calls.append(args) or 0)
    assert runner.main(["--output", str(tmp_path)]) == 0
    assert calls == [["--output", str(tmp_path), "--seconds", "20"]]
    for seconds in ("4", "21", "30"):
        with pytest.raises(SystemExit):
            runner.main(["--output", str(tmp_path), "--seconds", seconds])
    assert len(calls) == 1


def test_fixed_candidate_is_frozen_and_loaded_explicitly(monkeypatch: pytest.MonkeyPatch) -> None:
    assert dataclasses.is_dataclass(runner.SourceCandidate)
    assert runner.SourceCandidate.__dataclass_params__.frozen is True
    observed: dict[str, object] = {}

    def load(*, candidate):
        observed["candidate"] = candidate
        return {}, [], b"manifest"

    monkeypatch.setattr(runner.source_runner, "load_candidate", load)
    assert runner.load_candidate() == ({}, [], b"manifest")
    assert observed["candidate"] is runner.ANDROID_DEFAULT


def test_resource_artifact_pin_and_fixed_command_boundaries(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    artifact = tmp_path / runner.CLIENT_NAME
    artifact.write_bytes(b"x" * runner.CLIENT_BYTES)
    artifact.chmod(runner.CLIENT_MODE)
    monkeypatch.setattr(runner, "CLIENT", artifact)
    monkeypatch.setattr(runner, "CLIENT_SHA256", __import__("hashlib").sha256(artifact.read_bytes()).hexdigest())
    monkeypatch.setattr(runner.shared.loader, "_elf64_aarch64_dyn", lambda *args, **kwargs: None)
    monkeypatch.setattr(runner, "SOURCE", tmp_path / "source.c")
    monkeypatch.setattr(runner, "INCLUDED_SOURCE", tmp_path / "included.c")
    runner.SOURCE.write_bytes(b"source")
    runner.INCLUDED_SOURCE.write_bytes(b"included")
    monkeypatch.setattr(runner, "KNOWN_SOURCE_SHA256", __import__("hashlib").sha256(b"source").hexdigest())
    monkeypatch.setattr(runner, "KNOWN_INCLUDED_SOURCE_SHA256", __import__("hashlib").sha256(b"included").hexdigest())
    loaded = runner.load_client()
    assert loaded["bytes"] == runner.CLIENT_BYTES
    assert loaded["source"]["sha256"] != loaded["includedSource"]["sha256"]
    artifact.chmod(stat.S_IRUSR | stat.S_IWUSR)
    with pytest.raises(ValueError, match="metadata"):
        runner.load_client()

    with pytest.raises(SystemExit):
        runner.main(["--output", str(tmp_path), "--egl-mode", "default"])


def test_source_provenance_is_pinned_and_bounded(tmp_path: Path) -> None:
    path = tmp_path / "source.c"
    path.write_bytes(b"source")
    digest = __import__("hashlib").sha256(b"source").hexdigest()
    assert runner._source_record(path, digest)["sha256"] == digest
    with pytest.raises(ValueError, match="differs from the built"):
        runner._source_record(path, "0" * 64)
    path.write_bytes(b"x" * (runner.SOURCE_LIMIT + 1))
    with pytest.raises(ValueError, match="bound"):
        runner._source_record(path, digest)
