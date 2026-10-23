"""Tests for the non-interactive Android launcher smoke runner."""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).parents[1]
RUNNER = ROOT / "scripts/android-launcher-smoke.py"


def fake_adb(path: Path) -> Path:
    path.write_text(
        """#!/usr/bin/env python3
import os
import sys
import time

args = sys.argv[1:]
if "shell" in args and "--es" in args and "run_id" in args:
    run_id = args[args.index("run_id") + 1]
    open(os.environ["FAKE_RUN_ID_FILE"], "w", encoding="utf-8").write(run_id)
if "logcat" in args:
    scenario = os.environ["FAKE_SCENARIO"]
    run_file = os.environ["FAKE_RUN_ID_FILE"]
    for _ in range(100):
        if os.path.exists(run_file):
            break
        time.sleep(0.01)
    run_id = open(run_file, encoding="utf-8").read()
    if scenario == "old":
        print(f"old run_id={run_id}-old phase=selftest result=PASS", flush=True)
    elif scenario == "pass":
        payload = f"new run_id={run_id} phase=selftest result=PASS\\n".encode()
        sys.stdout.buffer.write(payload[:7])
        sys.stdout.buffer.flush()
        time.sleep(0.01)
        sys.stdout.buffer.write(payload[7:])
        sys.stdout.buffer.flush()
    elif scenario == "fail":
        print(f"new run_id={run_id} phase=selftest result=FAIL", flush=True)
    else:
        time.sleep(10)
    if scenario != "timeout":
        time.sleep(10)
    raise SystemExit(0)
print("ok")
""",
        encoding="utf-8",
    )
    path.chmod(0o755)
    return path


def invoke(tmp_path: Path, scenario: str) -> tuple[subprocess.CompletedProcess[str], dict, Path]:
    adb = fake_adb(tmp_path / "adb")
    apk = tmp_path / "app.apk"
    apk.write_bytes(b"fixture")
    output = tmp_path / "evidence"
    run_file = tmp_path / "run-id"
    env = {
        **os.environ,
        "FAKE_SCENARIO": scenario,
        "FAKE_RUN_ID_FILE": str(run_file),
    }
    completed = subprocess.run(
        [
            sys.executable,
            str(RUNNER),
            "--serial",
            "fake-serial",
            "--adb",
            str(adb),
            "--apk",
            str(apk),
            "--output-dir",
            str(output),
            "--timeout",
            "0.2",
        ],
        cwd=ROOT,
        env=env,
        capture_output=True,
        text=True,
    )
    run_dir = next(output.glob("run-*"))
    result = json.loads((run_dir / "result.json").read_text(encoding="utf-8"))
    return completed, result, run_dir


def test_runner_reports_pass_with_unique_run_marker(tmp_path: Path) -> None:
    completed, result, _ = invoke(tmp_path, "pass")
    assert completed.returncode == 0
    assert result["status"] == "PASS"
    assert result["serial"] == "fake-serial"
    assert f"run_id={result['run_id']} " in result["matched_line"]


def test_runner_reports_fail(tmp_path: Path) -> None:
    completed, result, _ = invoke(tmp_path, "fail")
    assert completed.returncode == 1
    assert result["status"] == "FAIL"


def test_runner_reports_timeout(tmp_path: Path) -> None:
    completed, result, _ = invoke(tmp_path, "timeout")
    assert completed.returncode == 2
    assert result["status"] == "TIMEOUT"


def test_runner_does_not_accept_old_logcat_result(tmp_path: Path) -> None:
    completed, result, run_dir = invoke(tmp_path, "old")
    assert completed.returncode == 2
    assert result["status"] == "TIMEOUT"
    logcat = (run_dir / "logcat.txt").read_text(encoding="utf-8")
    assert "-old phase=selftest result=PASS" in logcat
    assert f"run_id={result['run_id']} " not in logcat


def test_runner_rejects_nonfinite_timeout_and_repo_output(tmp_path: Path) -> None:
    for timeout in ("nan", "inf", "-inf"):
        completed = subprocess.run(
            [sys.executable, str(RUNNER), "--serial", "fake", "--timeout", timeout],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        assert completed.returncode == 2
    completed = subprocess.run(
        [
            sys.executable,
            str(RUNNER),
            "--serial",
            "fake",
            "--output-dir",
            str(ROOT / "test-artifacts"),
        ],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    assert completed.returncode == 2
    assert "fuera del repositorio" in completed.stderr
