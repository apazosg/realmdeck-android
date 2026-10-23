"""Focused regression tests for the exact static-Ed-slot consumer probe."""

from __future__ import annotations

import importlib.util
import stat
from pathlib import Path


ROOT = Path(__file__).parents[1]
MODULE_PATH = ROOT / "scripts/diagnostics/reverse-engineering/StaticEdSlotConsumers.py"
SPEC = importlib.util.spec_from_file_location("static_ed_slot_consumers", MODULE_PATH)
assert SPEC and SPEC.loader
analysis = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(analysis)


def test_arm64_address_decoder_uses_immlo_and_immhi() -> None:
    for immediate in (1, 2, 3, -1, -2, -3, 0x12345, -0x12345):
        encoded = immediate & ((1 << 21) - 1)
        word = 0x10000000 | ((encoded & 3) << 29) | ((encoded >> 2) << 5)
        assert analysis.arm_address_target(0x140100103, word) == (0x140100103 + immediate, "ADR")


def test_exact_slot_range_rejects_adjacent_data() -> None:
    target = {0x5000: {"start": 0x5000, "source_rva": 0x5000, "kind": "new_static_ed_slot", "span": analysis.SLOT_BYTES}}
    assert analysis.target_match(target, 0x5000, 32) is not None
    assert analysis.target_match(target, 0x501F, 1) is not None
    assert analysis.target_match(target, 0x5020, 1) is None


def test_x64_rip_relative_reference_is_decoded_without_raw_output() -> None:
    sections = [{
        "name": ".text", "rva": 0, "virtual_size": 32, "raw_size": 32,
        "raw_offset": 0, "characteristics": 0x60000020,
    }]
    decoded = analysis.decode_x64_rip(b"\x48\x8b\x05\xf9\x0f\x00\x00" + b"\0" * 10, sections, 0)
    assert decoded == {"target": 0x1000, "width": 8, "kind": "load", "form": "RIP-relative"}


def test_report_writer_is_exclusive_private_and_fsyncs(tmp_path: Path) -> None:
    output = tmp_path / "report.json"
    analysis.create_report(output, {"raw_bytes_emitted": False})
    assert stat.S_IMODE(output.stat().st_mode) == 0o600
    try:
        analysis.create_report(output, {"overwrite": True})
    except FileExistsError:
        pass
    else:
        raise AssertionError("report writer overwrote an existing artifact")
