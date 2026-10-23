"""Focused tests for encoded-byte weighting in pasted-client overlap."""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
MODULE_PATH = ROOT / "scripts/arm64-prototype/client/measure_pasted_client_overlap.py"
SPEC = importlib.util.spec_from_file_location("measure_pasted_client_overlap", MODULE_PATH)
assert SPEC and SPEC.loader
measure = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = measure
SPEC.loader.exec_module(measure)


def test_calculate_overlap_weights_shared_ekeys_by_target_encoded_size() -> None:
    target = [
        ("00000000000000000000000000000001", 100),
        ("10000000000000000000000000000001", 250),
        ("f0000000000000000000000000000001", 1_000),
    ]

    result = measure.calculate_overlap(
        target,
        {
            "00000000000000000000000000000001",
            "F0000000000000000000000000000001",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        },
    )

    assert result == measure.OverlapResult(
        target_object_count=3,
        target_encoded_bytes=1_350,
        reused_object_count=2,
        reused_encoded_bytes=1_100,
        remaining_object_count=1,
        remaining_encoded_bytes=250,
    )


def test_calculate_overlap_rejects_duplicate_or_unsorted_target_ekeys() -> None:
    key = "10000000000000000000000000000001"

    with pytest.raises(ValueError, match="sorted and unique"):
        measure.calculate_overlap([(key, 10), (key, 10)], {key})
