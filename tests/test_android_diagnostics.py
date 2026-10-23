"""Offline tests for the bounded Android diagnostics runner."""

from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "scripts/diagnostics/android_diagnostics.py"
SPEC = importlib.util.spec_from_file_location("android_diagnostics", MODULE_PATH)
assert SPEC and SPEC.loader
diagnostics = importlib.util.module_from_spec(SPEC)
sys.modules["android_diagnostics"] = diagnostics
SPEC.loader.exec_module(diagnostics)


def fake_adb(responses: list[tuple[int, bytes, bytes]]):
    calls: list[list[str]] = []

    def run(argv, **kwargs):
        calls.append(argv)
        return subprocess.CompletedProcess(argv, *responses.pop(0))

    run.calls = calls  # type: ignore[attr-defined]
    return run


def test_existing_probe_is_collected_without_ui_launch(tmp_path: Path) -> None:
    output = tmp_path / "runs"
    output.mkdir()
    responses = [
        (0, b"device\n", b""),
        (0, b"package:/data/app/base.apk\n", b""),
        (0, b"versionName=7.1 versionCode=16\n", b""),
        (0, b"priority=0\ncom.winlator.llm/com.winlator.MainActivity\n", b""),
        (0, b"1727470000:321\n", b""),
        (0, b"PROBE-BEGIN\nSLOT-CHANGE slot=0x1234 destination=0x9\n", b""),
    ]
    runner = fake_adb(responses)

    summary = diagnostics.capture(
        adb_path="/adb",
        serial="SERIAL",
        package="com.winlator.llm",
        output_dir=output,
        probe_path="/private/slot-probe.txt",
        probe_exe=tmp_path / "SlotProbe.exe",
        source=tmp_path / "SlotProbe.c",
        run_id="existing",
        runner=runner,
        output_fn=lambda _: None,
    )

    assert summary["status"] == "stale-result"
    assert summary["launch_attempts"] == 0
    assert all(" am " not in " ".join(call) for call in runner.calls)  # type: ignore[attr-defined]
    assert (output / "existing/slot-probe.txt").read_text() == "PROBE-BEGIN\nSLOT-CHANGE slot=0x1234 destination=0x9\n"
    stored = json.loads((output / "existing/summary.json").read_text())
    assert stored["slot_values_read"] == ["0x1234"]


def test_missing_probe_allows_at_most_two_explicit_activity_launches(tmp_path: Path) -> None:
    output = tmp_path / "runs"
    output.mkdir()
    missing = (1, b"", b"run-as: file missing\n")
    responses = [
        (0, b"device\n", b""),
        (0, b"package:/data/app/base.apk\n", b""),
        (0, b"versionName=7.1 versionCode=16\n", b""),
        (0, b"com.winlator.llm/com.winlator.MainActivity\n", b""),
        missing,
        (0, b"Starting: Intent { cmp=com.winlator.llm/.MainActivity }\n", b""),
        missing,
        (0, b"Starting: Intent { cmp=com.winlator.llm/.MainActivity }\n", b""),
        missing,
    ]
    runner = fake_adb(responses)
    prompts: list[str] = []

    summary = diagnostics.capture(
        adb_path="/adb",
        serial="SERIAL",
        package="com.winlator.llm",
        output_dir=output,
        probe_path="/private/slot-probe.txt",
        probe_exe=tmp_path / "SlotProbe.exe",
        source=tmp_path / "SlotProbe.c",
        run_id="blocked",
        wait_seconds=1,
        runner=runner,
        input_fn=lambda prompt: prompts.append(prompt) or "",
        output_fn=lambda _: None,
    )

    assert summary["status"] == "blocked-human-action-required"
    assert summary["launch_attempts"] == 2
    launch_calls = [call for call in runner.calls if "am" in call]  # type: ignore[attr-defined]
    assert len(launch_calls) == 2
    assert all(call[-2:] == ["-n", "com.winlator.llm/com.winlator.MainActivity"] for call in launch_calls)
    assert len(prompts) == 2
