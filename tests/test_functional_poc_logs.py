from __future__ import annotations

from dataclasses import dataclass
import importlib.util
from pathlib import Path
import sys
import time


ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "scripts/diagnostics/android-process/run_functional_poc.py"
SPEC = importlib.util.spec_from_file_location("functional_poc_logs", SCRIPT)
assert SPEC and SPEC.loader
poc = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = poc
SPEC.loader.exec_module(poc)


@dataclass
class Result:
    returncode: int = 0
    stdout: bytes = b""
    stderr: bytes = b""


class FakeAdb:
    def __init__(self, listings: list[bytes], files: dict[str, bytes]) -> None:
        self.listings = list(listings)
        self.files = files
        self.calls: list[tuple[str, ...]] = []

    def shell(self, *args: str, timeout: float = 30.0) -> Result:
        del timeout
        self.calls.append(args)
        if len(self.calls) == 1:
            return Result(stdout=self.listings.pop(0))
        script = args[-1]
        name = next(name for name in self.files if name in script)
        cap = int(script.split("head -c ", 1)[1].split(" ", 1)[0])
        return Result(stdout=self.files.get(name, b"")[:cap])


def test_accumulated_poc_logs_capture_newest_without_lifting_content_budget(tmp_path: Path) -> None:
    class Adb:
        calls = []

        def exec_out(self, *args, **kwargs):
            self.calls.append(args)
            if "find" in args:
                return Result(stdout=b"".join(f"files/functional-poc/run-{n}.jsonl\n".encode() for n in range(20)))
            return Result(stdout=b"{}\n")

    adb = Adb()
    (tmp_path / "poc-logs").mkdir()
    actions = poc.Actions(tmp_path / "actions.jsonl")
    saved = poc._discover_poc_logs(adb, tmp_path, "final", actions, time.monotonic() + 30)
    assert [Path(path).name for path in saved] == [f"final-{n}.jsonl" for n in (19, 18, 17, 16)]
    assert len(adb.calls) == 5
    assert all("run-0.jsonl" not in call for call in adb.calls)


def test_foreign_poc_log_path_is_not_filtered_into_acceptance(tmp_path: Path) -> None:
    class Adb:
        calls = []

        def exec_out(self, *args, **kwargs):
            self.calls.append(args)
            return Result(stdout=b"files/functional-poc/run-20.jsonl\n../other/run-21.jsonl\n")

    adb = Adb()
    actions = poc.Actions(tmp_path / "actions.jsonl")
    assert poc._discover_poc_logs(adb, tmp_path, "final", actions, time.monotonic() + 30) == []
    assert len(adb.calls) == 1


def _listing(*entries: tuple[str, str, int, int]) -> bytes:
    return "".join(f"{directory}\t{name}\t{size}\t{mtime}\n" for directory, name, size, mtime in entries).encode()


def _capture(tmp_path: Path, adb: FakeAdb, start: dict[str, dict[str, int]] | None = None):
    run_dir = tmp_path / "run"
    (run_dir / "game-logs").mkdir(parents=True)
    actions = poc.Actions(run_dir / "actions.jsonl")
    return poc._capture_game_logs(adb, run_dir, actions, time.monotonic() + 30, start)


def test_fixed_path_and_allowlist_are_read_only_and_metadata_is_retained(tmp_path: Path) -> None:
    listing = _listing(
        ("Logs", "gx.log", 3, 10),
        ("Errors", "2026-10-12_00.24.57_Error_220.txt", 4, 11),
        ("Logs", "Config.wtf", 99, 12),
        ("Errors", "2026-10-12_00.24.57_Error_220.dmp", 99, 13),
        ("Errors", "Error bad.txt", 99, 14),
    )
    error_name = "2026-10-12_00.24.57_Error_220.txt"
    adb = FakeAdb([listing], {"gx.log": b"abc", error_name: b"oops"})
    records = _capture(tmp_path, adb, {"gx.log": {"size": 2, "mtime": 9}})

    saved = {record["name"]: record for record in records if record["status"] == "saved"}
    assert set(saved) == {"gx.log", error_name}
    assert saved["gx.log"]["preexistingAtRunStart"] is True
    assert saved["gx.log"]["runStart"] == {"deviceSize": 2, "deviceMtime": 9}
    assert saved[error_name]["preexistingAtRunStart"] is False
    assert all("Config.wtf" not in call and "dump.dmp" not in call for call in adb.calls)
    assert poc.GAME_LOG_ROOT in adb.calls[0][-1]
    assert all(call[0:2] == ("sh", "-c") for call in adb.calls)


def test_error_crash_selection_is_strict_newest_and_capped(tmp_path: Path) -> None:
    listing = _listing(
        ("Errors", "2026-10-12_00.24.57_Error_1.txt", 300_000, 1),
        ("Errors", "2026-10-12_00.24.58_Crash_2.txt", 300_000, 4),
        ("Errors", "2026-10-12_00.24.59_Error_3.txt", 300_000, 3),
        ("Errors", "2026-10-12_00.25.00_Crash_4.txt", 300_000, 2),
        ("Errors", "2026-10-12_00.25.01_Crash_5.bin", 300_000, 9),
        ("Errors", "Error bad.txt", 300_000, 10),
    )
    data = {name: b"x" * 300_000 for name in (
        "2026-10-12_00.24.57_Error_1.txt",
        "2026-10-12_00.24.58_Crash_2.txt",
        "2026-10-12_00.24.59_Error_3.txt",
        "2026-10-12_00.25.00_Crash_4.txt",
    )}
    adb = FakeAdb([listing], data)
    records = _capture(tmp_path, adb)

    selected = [record["name"] for record in records if record["name"].endswith(".txt")]
    assert selected == [
        "2026-10-12_00.24.58_Crash_2.txt",
        "2026-10-12_00.24.59_Error_3.txt",
        "2026-10-12_00.25.00_Crash_4.txt",
    ]
    assert all(record["status"] == "overbound" for record in records if record["name"].endswith(".txt"))
    assert all(
        int(call[-1].split("head -c ", 1)[1].split(" ", 1)[0]) == poc.GAME_LOG_ERROR_BYTES + 1
        for call in adb.calls[1:]
    )


def test_total_budget_covers_fixed_and_error_logs(tmp_path: Path) -> None:
    fixed = [("Logs", name, poc.GAME_LOG_FIXED_BYTES, 1) for name in poc.GAME_LOG_FIXED_NAMES]
    error_name = "2026-10-12_00.24.57_Error_220.txt"
    crash_name = "2026-10-12_00.24.58_Crash_221.txt"
    listing = _listing(*fixed, ("Errors", error_name, 1, 20), ("Errors", crash_name, 1, 19))
    files = {name: b"f" * poc.GAME_LOG_FIXED_BYTES for name in poc.GAME_LOG_FIXED_NAMES}
    files.update({error_name: b"e" * (poc.GAME_LOG_TOTAL_BYTES - 7 * poc.GAME_LOG_FIXED_BYTES), crash_name: b"c"})
    adb = FakeAdb([listing], files)
    records = _capture(tmp_path, adb)

    assert sum(record.get("bytes", 0) for record in records) <= poc.GAME_LOG_TOTAL_BYTES
    assert [record for record in records if record["name"] == error_name][0]["status"] == "saved"
    assert [record for record in records if record["name"] == crash_name][0]["status"] == "budget"


def test_listing_failure_and_deadline_are_nonfatal(tmp_path: Path) -> None:
    adb = FakeAdb([b"not-a-metadata-line\n"], {})
    records = _capture(tmp_path, adb)
    assert records == [
        {
            "name": name,
            "devicePath": f"{poc.GAME_LOG_FIXED_ROOT}/{name}",
            "status": "missing",
            "preexistingAtRunStart": None,
        }
        for name in poc.GAME_LOG_FIXED_NAMES
    ]

    run_dir = tmp_path / "late-run"
    (run_dir / "game-logs").mkdir(parents=True)
    actions = poc.Actions(run_dir / "actions.jsonl")
    assert poc._capture_game_logs(adb, run_dir, actions, time.monotonic() - 1, None) == []
    assert len(adb.calls) == 1
