"""Focused checks for the isolated synthetic Wine debugger fixture."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path


ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "scripts/diagnostics/reverse-engineering/x64-certstore-trace/wine_fixture.py"


def _module():
    spec = importlib.util.spec_from_file_location("wine_fixture", SCRIPT)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_fixture_source_is_synthetic_and_contract_shaped() -> None:
    fixture = _module()
    source = fixture.FIXTURE_SOURCE
    assert "WowB" not in source
    assert "account" not in source.lower()
    assert "synthetic_table[480]" in source
    assert "initialize_table" in source
    assert "consume_table" in source
    assert fixture.TABLE_ROWS == 12
    assert fixture.TABLE_WIDTH == 40
    assert len(fixture._synthetic_rows()) == 480


def test_docker_command_has_required_isolation_and_no_entrypoint(tmp_path: Path) -> None:
    fixture = _module()
    command = fixture._docker_command(
        tmp_path,
        "known-container",
        tmp_path / "winedbg.commands",
        tmp_path / "fixture.exe",
    )
    joined = " ".join(command)
    for required in (
        "--user 1000:1000",
        "--network none",
        "--cap-drop ALL",
        "--security-opt no-new-privileges",
        "--pids-limit 128",
        "--memory 4g",
        "--cpus 2",
        "--read-only",
        "--tmpfs /tmp:rw,size=256m,mode=1777",
        "--entrypoint /bin/sh",
    ):
        assert required in joined
    assert "start-forever.sh" not in joined
    assert "WowB.exe" not in joined
    assert "shared-runtime" not in joined


def test_failure_report_is_private_and_makes_no_auth_claim(tmp_path: Path) -> None:
    fixture = _module()
    output = tmp_path / "fixture"
    output.mkdir(mode=0o700)
    report_path = fixture._report_failure(output, RuntimeError("diagnostic blocker"), 0)
    report = json.loads(report_path.read_text())
    assert report["status"] == "blocked"
    assert report["authentication_claim"] is False
    assert report["real_wow_launched"] is False
    assert report_path.stat().st_mode & 0o777 == 0o600
    assert output.stat().st_mode & 0o777 == 0o700
    assert not any(path.name == "WowB.exe" for path in output.iterdir())


def test_commands_use_verified_symbols_and_typed_watch_lvalues() -> None:
    fixture = _module()
    identity = {
        "symbols": {
            "initialize_table": {"absolute": "0x140001234"},
            "synthetic_table": {"absolute": "0x14000abcd"},
            "consume_table": {"absolute": "0x140005678"},
        }
    }
    commands = fixture._commands(identity)
    assert "break *0x140001234" in commands
    assert "watch *(unsigned long long *)0x14000abcd" in commands
    assert "rwatch *(unsigned long long *)0x14000abcd" in commands
    assert not any("0x140001480" in command or "0x14000e040" in command for command in commands)
    assert commands.count("info thread") == 2
    assert "disable 2" in commands and "disable 3" in commands
    assert "info regs" not in commands


def test_pe_export_parser_requires_actual_fixture_symbols() -> None:
    fixture = _module()
    text = """
Export Address Table -- Ordinal Base 1
 [   0] +base[   1] 000014de Export RVA
 [   1] +base[   2] 00001480 Export RVA
 [   2] +base[   3] 0000e040 Export RVA
[Ordinal/Name Pointer] Table -- Ordinal Base 1
 [   0] +base[   1]  0000 consume_table
 [   1] +base[   2]  0001 initialize_table
 [   2] +base[   3]  0002 synthetic_table
"""
    assert fixture._parse_pe_exports(text) == {
        "consume_table": 0x14DE,
        "initialize_table": 0x1480,
        "synthetic_table": 0xE040,
    }


def test_absent_actual_writer_reader_cannot_be_observed(tmp_path: Path) -> None:
    fixture = _module()
    (tmp_path / "winedbg.log").write_text(
        "Stopped on breakpoint 1 at 0x140001480 synthetic+0x1480\n"
        "table_valid=true ids_valid=true keys_valid=true flags_valid=true "
        "padding_valid=true rows=12 width=40\nfixture_exit=0\n"
    )
    identity = {"symbols": {"synthetic_table": {"absolute": "0x14000e040"}}}
    observations = fixture._extract_observations(
        tmp_path,
        {"log": "winedbg.log", "output_truncated": False},
        identity,
    )
    assert observations["actual_writer"] is None
    assert observations["actual_reader"] is None
    assert observations["blocker"]


def test_observation_facts_are_bounded_and_region_is_private(tmp_path: Path) -> None:
    fixture = _module()
    (tmp_path / "winedbg.log").write_text(
        "Stopped on watchpoint 2 at 0x14000148f synthetic+0x148f\n"
        "0x140001487 synthetic+0x1487: mov %rax,synthetic_table(%rip)\n"
        "Backtrace:\n=>0 0x14000148f (initialize_table)\n"
        "Stopped on watchpoint 3 at 0x1400014ef synthetic+0x14ef\n"
        "0x1400014e8 synthetic+0x14e8: mov synthetic_table(%rip),%rax\n"
        "Backtrace:\n=>0 0x1400014ef (consume_table)\n"
        "region_query=ok base=0x14000e000 size=4096 protect=4 state=4096 "
        "type=131072 allocation_base=0x14000e000\n"
        "table_valid=true ids_valid=true keys_valid=true flags_valid=true "
        "padding_valid=true rows=12 width=40\nfixture_exit=0\n"
    )
    identity = {"symbols": {"synthetic_table": {"absolute": "0x14000e040"}}}
    observations = fixture._extract_observations(
        tmp_path,
        {"log": "winedbg.log", "output_truncated": False},
        identity,
    )
    assert observations["actual_writer"]["operation"] == "writer"
    assert observations["actual_reader"]["operation"] == "reader"
    assert observations["actual_writer"]["width"] == 8
    assert len(observations["actual_writer"]["stack"]) <= fixture.STACK_CAP
    assert observations["region_metadata"][0]["private"] is True
    assert observations["region_metadata"][0]["backing"] == "private-anonymous"


def test_actual_events_without_stacks_remain_partial(tmp_path: Path) -> None:
    fixture = _module()
    (tmp_path / "winedbg.log").write_text(
        "Stopped on watchpoint 2 at 0x14000149c synthetic+0x149c\n"
        "0x140001499 synthetic+0x1499: mov %rax,synthetic_table(%rip)\n"
        "Stopped on watchpoint 3 at 0x1400014ec synthetic+0x14ec\n"
        "0x1400014e9 synthetic+0x14e9: mov synthetic_table(%rip),%rax\n"
        "region_query=ok base=0x14000e000 size=4096 protect=8 state=4096 "
        "type=16777216 allocation_base=0x140000000\n"
        "table_valid=true ids_valid=true keys_valid=true flags_valid=true "
        "padding_valid=true rows=12 width=40\nfixture_exit=0\n"
    )
    identity = {"symbols": {"synthetic_table": {"absolute": "0x14000e040"}}}
    observations = fixture._extract_observations(
        tmp_path,
        {"log": "winedbg.log", "output_truncated": False},
        identity,
    )
    assert observations["actual_writer"] is not None
    assert observations["actual_reader"] is not None
    assert observations["blocker"] == (
        "WineDbg did not report a bounded call stack for both watchpoint events"
    )
