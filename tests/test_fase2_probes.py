"""Offline contracts for the Windows Fase 2 diagnostic probes.

The default tests inspect the source only.  The MinGW/Wine test is deliberately
opt-in because pytest must remain usable without a Windows SDK or Wine prefix.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
PROBES = ROOT / "scripts/diagnostics/probes"


def test_dyncode_has_checked_abi_and_rw_rx_alias_contract() -> None:
    source = (PROBES / "DynCodeProbe.c").read_text(encoding="utf-8")

    assert "fn_i64_pair" in source
    assert "f((int64_t)100, (int64_t)23)" in source
    assert "PAGE_EXECUTE_READWRITE" in source
    assert "FILE_MAP_READ | FILE_MAP_WRITE" in source
    assert "FILE_MAP_READ | FILE_MAP_EXECUTE" in source
    assert source.count("flush_code(") >= 3
    assert "result = FlushInstructionCache" in source
    assert "case6 FIRST RESULT=%d (esperado 17)" in source
    assert "case6 SECOND RESULT=%d (esperado 29)" in source
    assert "case6 PASS shared-section rw-write rx-execute updated=1" in source
    assert "0xEC;\n            code[n++] = 0x28" in source
    assert "0xC4;\n            code[n++] = 0x28" in source
    assert "_commit(_fileno(g_log))" in source
    assert "DYNCODE-END result=%d failures=%d" in source
    assert "if (fclose(g_log) != 0) result = 1;" in source
    assert "if (!log_flush()) result = 1;" in source


def test_slot_probe_synthetic_control_stays_read_only() -> None:
    source = (PROBES / "SlotProbe.c").read_text(encoding="utf-8")

    assert "--synthetic-target" in source
    assert "--synthetic" in source
    assert "ReadProcessMemory" in source
    assert "VirtualQueryEx" in source
    assert "WriteProcessMemory" not in source
    assert "VirtualProtectEx" not in source
    assert "action=not-killed" in source
    assert "PeekNamedPipe" in source
    assert "SYNTHETIC READY-TIMEOUT" in source
    assert "SYNTHETIC PASS read-only observation" in source


def test_dyncode_result_parser_distinguishes_pass_and_failure() -> None:
    result_line = re.compile(r"^DYNCODE-END result=(\d+) failures=(\d+)$", re.MULTILINE)

    passing = "case6 SECOND RESULT=29 (esperado 29)\nDYNCODE-END result=0 failures=0\n"
    failing = "case2 FAIL unexpected-value\nDYNCODE-END result=1 failures=1\n"
    assert result_line.search(passing).groups() == ("0", "0")
    assert result_line.search(failing).groups() == ("1", "1")


def _compile(
    compiler: str,
    source: Path,
    exe: Path,
    extra: list[str] | None = None,
) -> None:
    result = subprocess.run(
        [
            compiler,
            "-std=c11",
            "-O2",
            "-static",
            "-static-libgcc",
            "-static-libstdc++",
            "-mwindows",
            *(extra or []),
            "-o",
            str(exe),
            str(source),
            *( ["-lpsapi"] if source.name.startswith("SlotProbe") else []),
        ],
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert result.returncode == 0, result.stdout + result.stderr


def _run(wine: str, env: dict[str, str], exe: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [wine, str(exe), *args], env=env, capture_output=True, text=True, timeout=30
    )


@pytest.mark.skipif(
    os.environ.get("WOWFOREVER_RUN_PROBE_INTEGRATION") != "1",
    reason="opt-in: compiles MinGW probes and runs them under an isolated Wine prefix",
)
def test_compiled_probes_pass_and_fail_under_wine() -> None:
    compiler = shutil.which("x86_64-w64-mingw32-gcc")
    wine = shutil.which("wine64") or shutil.which("wine")
    wineboot = shutil.which("wineboot") or shutil.which("wineboot64")
    if not compiler or not wine or not wineboot:
        pytest.skip("MinGW-w64 and Wine are required for the opt-in integration test")

    artifacts = Path(
        os.environ.get("WOWFOREVER_PROBE_ARTIFACTS", "/home/adrian/wowforever-artifacts")
    )
    if not artifacts.is_dir():
        pytest.skip(f"artifact root does not exist: {artifacts}")
    work = Path(tempfile.mkdtemp(prefix="probes-pytest-", dir=artifacts))
    try:
        prefix = work / "wineprefix"
        diag = prefix / "drive_c" / "wow" / "diag"
        diag.mkdir(parents=True)
        env = {**os.environ, "WINEPREFIX": str(prefix), "WINEDEBUG": "-all"}
        boot = subprocess.run(
            [wineboot, "-u"], env=env, capture_output=True, text=True, timeout=30
        )
        assert boot.returncode == 0, boot.stdout + boot.stderr

        api = diag / "ApiProbe.exe"
        dyn = diag / "DynCodeProbe.exe"
        slot = diag / "SlotProbe.exe"
        _compile(compiler, PROBES / "ApiProbe.c", api)
        _compile(compiler, PROBES / "DynCodeProbe.c", dyn)
        _compile(compiler, PROBES / "SlotProbe.c", slot)

        assert _run(wine, env, api).returncode == 0
        assert _run(wine, env, dyn).returncode == 0
        assert _run(wine, env, slot, "--synthetic").returncode == 0

        dyncode_log = (diag / "dyncode-probe.txt").read_text(encoding="utf-8")
        assert "DYNCODE-END result=0 failures=0" in dyncode_log
        assert "case6 PASS shared-section rw-write rx-execute updated=1" in dyncode_log
        slot_log = (diag / "slot-probe.txt").read_text(encoding="utf-8")
        assert "SYNTHETIC PASS read-only observation" in slot_log

        _compile(compiler, PROBES / "DynCodeProbe.c", dyn, ["-DDYNCODE_FORCE_API_MISSING=1"])
        failed = _run(wine, env, dyn)
        assert failed.returncode != 0
        dyncode_log = (diag / "dyncode-probe.txt").read_text(encoding="utf-8")
        assert "case4 FAIL API-UNAVAILABLE" in dyncode_log
        assert "case4 SKIP" not in dyncode_log
        assert "DYNCODE-END result=1" in dyncode_log

        _compile(compiler, PROBES / "DynCodeProbe.c", dyn, ["-DDYNCODE_FORCE_FLUSH_FAILURE=1"])
        failed = _run(wine, env, dyn)
        assert failed.returncode != 0
        dyncode_log = (diag / "dyncode-probe.txt").read_text(encoding="utf-8")
        assert "FLUSH-FAIL" in dyncode_log
        assert "DYNCODE-END result=1" in dyncode_log

        mutated = work / "DynCodeProbe-wrong-constant.c"
        mutated.write_text(
            (PROBES / "DynCodeProbe.c")
            .read_text(encoding="utf-8")
            .replace("0x2A, 0x00", "0x2B, 0x00", 1),
            encoding="utf-8",
        )
        _compile(compiler, mutated, dyn)
        failed = _run(wine, env, dyn)
        assert failed.returncode != 0
        dyncode_log = (diag / "dyncode-probe.txt").read_text(encoding="utf-8")
        assert "case1 RESULT=43" in dyncode_log
        assert "case1 FAIL unexpected-value" in dyncode_log

        no_ready = work / "SlotProbe-no-ready.c"
        no_ready_source = (PROBES / "SlotProbe.c").read_text(encoding="utf-8")
        no_ready_source = no_ready_source.replace("Sleep(1000);", "Sleep(2500);", 1)
        no_ready.write_text(
            re.sub(
                r'    printf\("SYNTHETIC-READY.*?&synthetic_marker\);\n',
                "    /* intentionally silent negative fixture */\n",
                no_ready_source,
                count=1,
                flags=re.DOTALL,
            ),
            encoding="utf-8",
        )
        _compile(compiler, no_ready, slot)
        failed = _run(wine, env, slot, "--synthetic")
        assert failed.returncode != 0
        slot_log = (diag / "slot-probe.txt").read_text(encoding="utf-8")
        assert "SYNTHETIC READY-TIMEOUT" in slot_log
        assert "action=not-killed" in slot_log
        assert "SYNTHETIC PASS" not in slot_log
    finally:
        shutil.rmtree(work)
