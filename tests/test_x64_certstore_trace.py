"""Regression checks for the bounded native synthetic certstore trace."""

from __future__ import annotations

import json
import os
import platform
import subprocess
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "scripts/diagnostics/reverse-engineering/x64-certstore-trace/trace_fixture.py"


@pytest.mark.skipif(platform.system() != "Linux" or platform.machine() != "x86_64",
                    reason="fixture uses Linux x86-64 debug registers")
def test_synthetic_watchpoint_proves_write_then_read(tmp_path: Path) -> None:
    reports = []
    for index in range(2):
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--output-dir", str(tmp_path / f"trace-{index}")],
            check=True, capture_output=True, text=True, timeout=45,
        )
        summary = json.loads(result.stdout)
        report_path = Path(summary["report"])
        reports.append((report_path, json.loads(report_path.read_text())))

    report_path, report = reports[0]

    assert report["kind"].startswith("HOST SYNTHETIC")
    assert report["table"] == {
        "bytes": 480,
        "complete_validation": True,
        "flags_valid": True,
        "ids_valid": True,
        "keys_valid": True,
        "padding_valid": True,
        "rows": 12,
        "values_reported": False,
        "width": 40,
    }
    assert [event["access"] for event in report["events"]] == ["write", "read"]
    assert [event["phase"] for event in report["events"]] == ["initialization", "later-access"]
    for event in report["events"]:
        assert event["instruction_address"] != event["posttrap_ip"]
        assert event["target"] == report["watchpoint"]["address"]
        assert len(event["callstack"]) <= 16
        assert event["region"]["permissions"].startswith("rw")
        assert event["region"]["private"] is True
        assert event["module"]["module"] == "fixture"
        assert event["module"]["module_offset"]
        assert "value" not in event
        assert "stack_bytes" not in event
    assert report["classified_event_count"] == 2
    assert report["event_count"] <= 64
    assert report["identity"]["verified"] is True
    assert set(report["identity"]["uid"]) == {"real", "effective", "saved_set", "filesystem"}
    assert report["identity"]["ppid"] > 0
    assert report["exit_status"]["exit_code"] == 0
    assert all(set(check["before"]["uid"]) == set(report["identity"]["uid"])
               for check in report["identity_checks"])
    assert report["region_stability"]["properties_stable"] is True
    assert report["region_stability"]["address_reuse_compared"] is False
    assert report["artifacts"]["source_sha256"]
    assert report["artifacts"]["binary_sha256"]
    assert report["commands"]["compile"]
    assert report["tools"]["kernel"]["machine"] == "x86_64"
    assert report_path.stat().st_mode & 0o777 == 0o600
    assert report_path.parent.stat().st_mode & 0o777 == 0o700
    assert os.path.exists(report["identity"]["executable"])

    # Compare stable region properties across independent children, never their
    # addresses: ASLR/address reuse is not evidence of object identity.
    first_properties = reports[0][1]["region_stability"]["before"]
    second_properties = reports[1][1]["region_stability"]["before"]
    assert first_properties == second_properties
