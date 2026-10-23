"""Host-only regression tests for native ownership and failure cleanup."""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
SOURCE = ROOT / "scripts/diagnostics/android-process/run_supervisor_capacity.c"
RUN_ID = "RS-0123456789abcdef0123456789abcdef"


@pytest.fixture(scope="module")
def native_binary(tmp_path_factory: pytest.TempPathFactory) -> Path:
    compiler = shutil.which("cc")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    output = tmp_path_factory.mktemp("native-supervisor") / "RunSupervisorCapacity"
    subprocess.run(
        [compiler, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie", "-o", str(output), str(SOURCE)],
        check=True,
    )
    return output


def run_native(binary: Path, *args: str) -> tuple[int, list[dict[str, object]]]:
    completed = subprocess.run(
        [str(binary), "--run-id", RUN_ID, *args],
        capture_output=True,
        timeout=25,
        check=False,
    )
    assert completed.stderr == b""
    return completed.returncode, [json.loads(line) for line in completed.stdout.splitlines()]


def test_normal_native_run_requires_direct_exit_and_echild_facts(native_binary: Path) -> None:
    exit_code, documents = run_native(native_binary)
    result = documents[-1]
    assert exit_code == 0
    assert result["status"] == "PASS"
    assert result["normalIntermediateExit"] == 41
    assert result["normalGrandchildExit"] == 42
    assert result["cleanupIntermediateExit"] == 43
    assert result["cleanupGrandchildExit"] == 143
    assert result["rootWaitpidEchild"] is True
    assert result["waitpidEchild"] is True


@pytest.mark.parametrize("fault", ["family/create", "eventpipe", "supervisorfork", "witness-ipc"])
def test_setup_faults_are_bounded_and_confirm_root_cleanup(native_binary: Path, fault: str) -> None:
    exit_code, documents = run_native(native_binary, "--fault", fault)
    result = documents[-1]
    assert exit_code == 1
    assert result["event"] == "result"
    assert result["status"] == "FAIL"
    assert result["failureStage"] == fault
    assert result["cleanupConfirmed"] is True
    assert result["rootWaitpidEchild"] is True


def test_adoption_after_intermediate_exit_uses_reserved_birth_parent(tmp_path: Path) -> None:
    """Force the Android-observed scheduling window without another phone run."""
    compiler = shutil.which("cc")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    source = SOURCE.read_text()
    marker = "pid_t original_parent = (pid_t)birth.ppid;"
    assert source.count(marker) == 1
    outcomes = {}
    for name, expression in (("old", "getppid()"), ("fixed", "(pid_t)birth.ppid")):
        # Delay only after the intermediate is allowed to exit.  The old code
        # now reliably sees the supervisor as 'original_parent' and blocks.
        rewritten = source.replace(marker, f"(void)poll(NULL, 0, 100); pid_t original_parent = {expression};")
        path = tmp_path / f"{name}.c"
        path.write_text(rewritten)
        binary = tmp_path / name
        subprocess.run([compiler, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-o", str(binary), str(path)], check=True)
        code, rows = run_native(binary)
        outcomes[name] = (code, rows[-1])
    assert outcomes["old"][0] != 0 and outcomes["old"][1]["status"] != "PASS"
    assert outcomes["fixed"][0] == 0 and outcomes["fixed"][1]["status"] == "PASS"
    assert outcomes["fixed"][1]["waitpidEchild"] is True
    assert outcomes["fixed"][1]["witnessStable"] is True


def test_cooperative_witness_exit_is_not_preempted_by_cleanup(tmp_path: Path) -> None:
    compiler = shutil.which("cc")
    if compiler is None:
        pytest.skip("host C compiler unavailable")
    source = SOURCE.read_text()
    marker = "if (command == 'Q') {\n            _exit(0);"
    assert source.count(marker) == 1
    source = source.replace(marker, "if (command == 'Q') {\n            (void)poll(NULL, 0, 50);\n            _exit(0);")
    for name, grace in (("old", "0U"), ("fixed", "200U")):
        rewritten = source.replace("uint64_t witness_grace = monotonic_ms() + 200U;", f"uint64_t witness_grace = monotonic_ms() + {grace};")
        path = tmp_path / f"witness-{name}.c"
        path.write_text(rewritten)
        binary = tmp_path / f"witness-{name}"
        subprocess.run([compiler, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-o", str(binary), str(path)], check=True)
        code, rows = run_native(binary)
        witness_exit = next(item for item in rows if item["event"] == "witness_exit")
        if name == "old":
            assert code != 0 and rows[-1]["status"] != "PASS"
            assert witness_exit["exitCode"] == 143
        else:
            assert code == 0 and rows[-1]["status"] == "PASS"
            assert witness_exit["exitCode"] == 0 and witness_exit["reaped"] is True
