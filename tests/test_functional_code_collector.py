from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import shlex
import subprocess

import pytest


ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "scripts/diagnostics/android-process/collect_functional_code.py"
SPEC = importlib.util.spec_from_file_location("functional_code_collector", SCRIPT)
assert SPEC and SPEC.loader
collector = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(collector)


def _binary(index: int) -> bytes:
    return bytes([index + 7]) * collector.SNAPSHOT_BYTES


def _metadata(index: int, *, nonce: str = "lease_1", basis: str = "current-structural-header", binary: bytes | None = None) -> bytes:
    payload = _binary(index) if binary is None else binary
    value = {
        "schema": 1,
        "kind": "arm64-runtime-code-window",
        "status": "CAPTURED" if basis == "current-structural-header" else "INCOMPLETE",
        "basis": basis,
        "moduleProof": basis == "current-structural-header",
        "cryptographicModuleProof": False,
        "writeAuthorized": False,
        "source": {
            "size": collector.SOURCE_SIZE,
            "sha256": collector.SOURCE_SHA256,
            "sha256CheckedOncePerLease": True,
            "metadataStable": True,
            "windowsValidatedAsRawBackedExecutableText": True,
        },
        "target": {
            "pid": 41,
            "startTicks": 99,
            "uid4": [10330] * 4,
            "ppid": 40,
            "cmdlineValidated": True,
            "ancestryValidated": True,
            "nonceValidated": True,
        },
        "nonce": nonce,
        "snapshot": {
            "index": index,
            "bytes": collector.SNAPSHOT_BYTES,
            "sha256": hashlib.sha256(payload).hexdigest(),
            "windows": [dict(window) for window in collector.WINDOWS],
        },
        "mapsBefore": [{"start": 0x1000, "end": 0x9000, "permissions": "r-xp", "device": "00:01", "inode": 123, "offset": 0, "pathKind": "file"}],
        "mapsAfter": [{"start": 0x1000, "end": 0x9000, "permissions": "r-xp", "device": "00:01", "inode": 123, "offset": 0, "pathKind": "file"}],
        "mapsStable": True,
    }
    return (json.dumps(value, separators=(",", ":")) + "\n").encode()


class FakeAdb:
    def __init__(self, files: dict[str, bytes]) -> None:
        self.files = files
        self.calls: list[tuple[str, ...]] = []

    def run(self, command: list[str], **kwargs):  # noqa: ANN001
        del kwargs
        self.calls.append(tuple(command))
        argv = shlex.split(command[-1])
        remote = argv[-1]
        if "for name in snapshot-0.json" in argv[4]:
            names = "".join(f"{name}\n" for name in collector.FIXED_ARTIFACTS if name in self.files)
            return type("Result", (), {"returncode": 0, "stdout": names.encode(), "stderr": b""})()
        relative = remote.rsplit("/", 1)[-1]
        if relative not in self.files:
            return type("Result", (), {"returncode": 1, "stdout": b"", "stderr": b"missing"})()
        return type("Result", (), {"returncode": 0, "stdout": self.files[relative], "stderr": b""})()


def _files(*indexes: int, nonce: str = "lease_1") -> dict[str, bytes]:
    result: dict[str, bytes] = {}
    for index in indexes:
        payload = _binary(index)
        result[f"snapshot-{index}.json"] = _metadata(index, nonce=nonce, binary=payload)
        result[f"snapshot-{index}.bin"] = payload
    return result


def _collect(monkeypatch: pytest.MonkeyPatch, tmp_path: Path, files: dict[str, bytes], nonce: str = "lease_1") -> Path:
    fake = FakeAdb(files)
    monkeypatch.setattr(collector.subprocess, "run", fake.run)
    return collector.collect("adb", "serial with spaces", tmp_path, nonce)


def test_collector_reads_fixed_artifacts_and_preserves_actual_contents_and_modes(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    output = _collect(monkeypatch, tmp_path, _files(0, 1))

    assert output.is_dir()
    assert oct(output.stat().st_mode & 0o777) == "0o700"
    assert {path.name for path in output.iterdir()} == {"snapshot-0.bin", "snapshot-0.json", "snapshot-1.bin", "snapshot-1.json", "manifest.json"}
    assert output.joinpath("snapshot-0.bin").read_bytes() == _binary(0)
    assert output.joinpath("snapshot-1.bin").read_bytes() == _binary(1)
    assert output.joinpath("snapshot-0.json").read_bytes() == _metadata(0)
    assert all(oct(path.stat().st_mode & 0o777) == "0o600" for path in output.iterdir())
    manifest = json.loads(output.joinpath("manifest.json").read_text())
    assert manifest["status"] == "CAPTURED"
    assert manifest["totalBytes"] == collector.TOTAL_BYTES


def test_exec_out_uses_one_shell_quoted_command_with_fixed_positional_arguments(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    captured: list[list[str]] = []

    def fake_run(command: list[str], **kwargs):  # noqa: ANN001
        del kwargs
        captured.append(command)
        return type("Result", (), {"returncode": 0, "stdout": b"payload", "stderr": b""})()

    monkeypatch.setattr(collector.subprocess, "run", fake_run)
    assert collector._run("adb", "serial with spaces", "files/functional-poc/captures/lease_1/snapshot-0.bin", 7) == b"payload"
    assert len(captured) == 1
    command = captured[0]
    assert command[-2] == "exec-out"
    assert shlex.split(command[-1]) == [
        "run-as",
        collector.PACKAGE,
        "sh",
        "-c",
        'test ! -L "$2" && head -c "$1" "$2"',
        "collector",
        "8",
        "files/functional-poc/captures/lease_1/snapshot-0.bin",
    ]

    captured.clear()

    def fake_listing(command: list[str], **kwargs):  # noqa: ANN001
        del kwargs
        captured.append(command)
        return type("Result", (), {"returncode": 0, "stdout": b"snapshot-0.json\n", "stderr": b""})()

    monkeypatch.setattr(collector.subprocess, "run", fake_listing)
    assert collector._run_listing("adb", "serial with spaces", "files/functional-poc/captures/lease_1") == {"snapshot-0.json"}
    listing_argv = shlex.split(captured[0][-1])
    assert listing_argv[:4] == ["run-as", collector.PACKAGE, "sh", "-c"]
    assert listing_argv[5:] == ["collector", "files/functional-poc/captures/lease_1"]


def test_fixed_read_script_runs_locally_only_against_the_supplied_fixture(tmp_path: Path) -> None:
    fixture = tmp_path / "fixture with spaces.bin"
    fixture.write_bytes(b"0123456789")
    script = 'test ! -L "$2" && head -c "$1" "$2"'
    result = subprocess.run(["sh", "-c", script, "collector", "5", str(fixture)], check=False, stdout=subprocess.PIPE)
    assert result.returncode == 0
    assert result.stdout == b"01234"


def test_one_snapshot_is_accepted_and_historical_capture_is_incomplete(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    output = _collect(monkeypatch, tmp_path, _files(0))
    assert json.loads(output.joinpath("manifest.json").read_text())["status"] == "CAPTURED"

    historical = _files(0, nonce="lease_2")
    historical["snapshot-0.json"] = _metadata(0, nonce="lease_2", basis="earlier-structural-header")
    output = _collect(monkeypatch, tmp_path, historical, nonce="lease_2")
    assert json.loads(output.joinpath("manifest.json").read_text())["status"] == "INCOMPLETE"


@pytest.mark.parametrize(
    ("mutation", "message"),
    [
        (lambda value: value.update(nonce="other"), "nonce"),
        (lambda value: value["snapshot"].update(index=True), "snapshot size"),
        (lambda value: value["target"].update(uid4=[1, 2, 3]), "target identity"),
        (lambda value: value.update(cryptographicModuleProof=True), "cryptographic module proof"),
    ],
)
def test_metadata_identity_fields_are_bound_to_the_requested_capture(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, mutation, message: str) -> None:  # noqa: ANN001
    files = _files(0)
    value = json.loads(files["snapshot-0.json"])
    mutation(value)
    files["snapshot-0.json"] = (json.dumps(value) + "\n").encode()
    with pytest.raises(collector.CollectorError, match=message):
        _collect(monkeypatch, tmp_path, files)
    assert list(tmp_path.iterdir()) == []


def test_digest_mismatch_is_not_treated_as_a_missing_snapshot(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    files = _files(0)
    files["snapshot-0.bin"] = b"wrong" * (collector.SNAPSHOT_BYTES // 5) + b"x" * (collector.SNAPSHOT_BYTES % 5)
    with pytest.raises(collector.CollectorError, match="hash"):
        _collect(monkeypatch, tmp_path, files)
    assert list(tmp_path.iterdir()) == []


def test_orphan_and_zero_snapshot_leases_fail_explicitly(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    with pytest.raises(collector.CollectorError, match="present together"):
        _collect(monkeypatch, tmp_path, {"snapshot-0.json": _metadata(0)})
    assert list(tmp_path.iterdir()) == []

    with pytest.raises(collector.CollectorError, match="no complete fixed snapshots"):
        _collect(monkeypatch, tmp_path, {})
    assert list(tmp_path.iterdir()) == []


def test_tampered_source_provenance_is_rejected_without_claiming_the_source_hash(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    files = _files(0)
    value = json.loads(files["snapshot-0.json"])
    value["source"]["sha256"] = "0" * 64
    files["snapshot-0.json"] = (json.dumps(value) + "\n").encode()
    with pytest.raises(collector.CollectorError, match="source provenance"):
        _collect(monkeypatch, tmp_path, files)


def test_commit_rolls_back_after_failed_unlink(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    directory = tmp_path / "private"
    directory.mkdir(mode=0o700)
    real_unlink = collector._unlink_if_identity
    calls = 0

    def fail_first(path: Path, identity: tuple[int, int]) -> bool:
        nonlocal calls
        calls += 1
        if calls == 1:
            return False
        return real_unlink(path, identity)

    monkeypatch.setattr(collector, "_unlink_if_identity", fail_first)
    with pytest.raises(collector.CollectorError, match="commit"):
        collector._commit_private(directory, "artifact", b"data")
    assert list(directory.iterdir()) == []


def test_commit_rolls_back_after_failed_directory_fsync(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    directory = tmp_path / "private"
    directory.mkdir(mode=0o700)
    real_fsync = collector.os.fsync
    calls = 0

    def fail_directory_fsync(fd: int) -> None:
        nonlocal calls
        calls += 1
        if calls == 2:
            raise OSError("injected directory fsync failure")
        real_fsync(fd)

    monkeypatch.setattr(collector.os, "fsync", fail_directory_fsync)
    with pytest.raises(collector.CollectorError, match="commit"):
        collector._commit_private(directory, "artifact", b"data")
    assert list(directory.iterdir()) == []


def test_commit_rolls_back_if_hardlink_reports_failure_after_creating_final(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    directory = tmp_path / "private"
    directory.mkdir(mode=0o700)
    real_link = collector.os.link

    def link_then_fail(source: Path, destination: Path, **kwargs) -> None:  # noqa: ANN003
        real_link(source, destination, **kwargs)
        raise OSError("injected hardlink failure")

    monkeypatch.setattr(collector.os, "link", link_then_fail)
    with pytest.raises(collector.CollectorError, match="commit"):
        collector._commit_private(directory, "artifact", b"data")
    assert list(directory.iterdir()) == []


def test_commit_rolls_back_on_early_hardlink_oserror(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    directory = tmp_path / "private"
    directory.mkdir(mode=0o700)

    def fail_link(*args, **kwargs):  # noqa: ANN002, ANN003
        del args, kwargs
        raise OSError("injected early hardlink failure")

    monkeypatch.setattr(collector.os, "link", fail_link)
    with pytest.raises(collector.CollectorError, match="commit"):
        collector._commit_private(directory, "artifact", b"data")
    assert list(directory.iterdir()) == []


def test_commit_keeps_foreign_final_on_collision_and_removes_only_its_temporary(tmp_path: Path) -> None:
    directory = tmp_path / "private"
    directory.mkdir(mode=0o700)
    foreign = directory / "artifact"
    foreign.write_bytes(b"foreign")

    with pytest.raises(collector.CollectorError, match="already exists"):
        collector._commit_private(directory, "artifact", b"data")
    assert foreign.read_bytes() == b"foreign"
    assert [path.name for path in directory.iterdir()] == ["artifact"]


def test_transaction_cleanup_does_not_delete_an_unowned_foreign_file(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    original_commit = collector._commit_private
    calls = 0

    def fail_after_foreign(directory: Path, name: str, data: bytes) -> Path:
        nonlocal calls
        calls += 1
        if calls == 2:
            (directory / "foreign").write_bytes(b"keep")
            raise collector.CollectorError("injected transaction failure")
        return original_commit(directory, name, data)

    monkeypatch.setattr(collector, "_commit_private", fail_after_foreign)
    with pytest.raises(collector.CollectorError, match="transaction"):
        _collect(monkeypatch, tmp_path, _files(0))
    directories = list(tmp_path.iterdir())
    assert len(directories) == 1
    assert directories[0].joinpath("foreign").read_bytes() == b"keep"
    assert not directories[0].joinpath("snapshot-0.json").exists()


def test_collector_rejects_unbounded_or_path_like_nonce(tmp_path: Path) -> None:
    with pytest.raises(collector.CollectorError, match="bounded lease"):
        collector.collect("adb", "serial", tmp_path, "../old-run")
