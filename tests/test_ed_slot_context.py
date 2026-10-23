"""Focused tests for the bounded static Ed-slot context probe."""

from __future__ import annotations

import importlib.util
import json
import stat
from pathlib import Path


ROOT = Path(__file__).parents[1]
MODULE_PATH = ROOT / "scripts/diagnostics/reverse-engineering/EdSlotContext.py"
SPEC = importlib.util.spec_from_file_location("ed_slot_context", MODULE_PATH)
assert SPEC and SPEC.loader
analysis = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(analysis)


def test_known9_mask_reports_positions_without_bytes() -> None:
    known = bytes(range(32))
    candidate = known[:8] + bytes((value + 1) % 256 for value in known[8:])
    result = analysis.positional_mask(candidate, known)
    assert result["equal_count"] == 8
    assert result["equal_mask"] == "11111111000000000000000000000000"
    assert "bytes" not in json.dumps(result).lower()


def test_boundary_shape_distinguishes_zero_delimited_object() -> None:
    window = bytes([0] * 32 + [9] * 32 + [0] * 32)
    result = analysis.slot_shape(window, 32)
    assert result["zero_delimiter_on_both_sides"]
    assert result["nonzero_containing_span_relative"] == [0, 32]
    assert result["natural_alignment"]["16"]


def test_shape_alignment_uses_source_offset_not_centered_window_index() -> None:
    window = bytes([1] * 96)
    result = analysis.slot_shape(window, 32, 0x48F5A50)
    assert result["natural_alignment"]["16"]
    assert not result["natural_alignment"]["32"]
    assert not result["vector_boundaries"]["32"]["slot_start_aligned"]


def test_cross_arch_mask_excludes_slot() -> None:
    left = bytes(range(96))
    right = bytearray(left)
    right[32:64] = bytes((value + 1) % 256 for value in right[32:64])
    mask = [False if 32 <= index < 64 else left[index] == right[index] for index in range(96)]
    assert sum(mask) == 64
    assert all(not item for item in mask[32:64])


def test_split_halves_report_distance_without_32_byte_assembly() -> None:
    first = {
        "matches": [{"raw_offset": "0x100", "section": ".rdata"}],
    }
    second = {
        "matches": [{"raw_offset": "0x120", "section": ".rdata"}],
    }
    result = analysis.half_pair_distances(first, second)
    assert result["distances_only"]
    assert result["pairs"][0]["distance_raw"] == 32
    assert "assembled32" not in json.dumps(result)


def test_duplicate_half_positions_are_capped_without_assembly_claim() -> None:
    pattern = b"0123456789abcdef"
    positions, limit_hit = analysis.find_occurrences(pattern * 70, pattern)
    assert len(positions) == analysis.MAX_MATCH_POSITIONS
    assert limit_hit
    assert "assembly" not in json.dumps({"positions": positions}).lower()


def test_report_writer_is_private_exclusive_and_bounded(tmp_path: Path) -> None:
    output = tmp_path / "context.json"
    analysis.create_report(output, {"raw_slot_or_potential_key_bytes_emitted": False})
    assert stat.S_IMODE(output.stat().st_mode) == 0o600
    assert json.loads(output.read_text())["raw_slot_or_potential_key_bytes_emitted"] is False
    try:
        analysis.create_report(output, {"overwrite": True})
    except FileExistsError:
        pass
    else:
        raise AssertionError("report writer overwrote existing evidence")
