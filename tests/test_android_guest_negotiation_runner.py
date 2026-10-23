"""Offline HOST gates for the fixed negotiation logging replay."""

from __future__ import annotations

import copy
import importlib.util
import json
import sys
from pathlib import Path


ROOT = Path(__file__).parents[1]
DIRECTORY = ROOT / "scripts/diagnostics/android-vtest"
sys.path.insert(0, str(DIRECTORY))
SPEC = importlib.util.spec_from_file_location("guest_negotiation_runner", DIRECTORY / "run_guest_negotiation_usb.py")
assert SPEC and SPEC.loader
runner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)

RUN = "VTEST-" + "b" * 32


def _line(row: dict[str, object]) -> bytes:
    return b"WFVTEST_NEGOTIATION " + (json.dumps(row, separators=(",", ":")) + "\n").encode()


def _stderr(*rows: dict[str, object]) -> bytes:
    identity = b"WFVTEST_ANDROID_DEFAULT gl.vendor=Mesa\n"
    identity += b"WFVTEST_ANDROID_DEFAULT gl.renderer=virgl\n"
    identity += b"WFVTEST_ANDROID_DEFAULT gl.version=1\n"
    return identity + b"".join(_line(row) for row in rows)


def _trace_rows() -> list[dict[str, object]]:
    return [
        {"schema": 1, "event": "command", "sequence": 1, "length": 16, "id": 1},
        {"schema": 1, "event": "protocol", "sequence": 2, "requested": 2, "selected": 2},
        {"schema": 1, "event": "cap_request", "sequence": 3, "id": 2, "version": 1, "maxVersion": 2, "maxBytes": 1284, "valid": 1},
        {
            "schema": 1, "event": "cap_reply", "sequence": 4, "id": 2, "version": 1, "bytes": 1284, "valid": 1,
            "glsl_level": 4, "bset": 1, "rawbitmask": 1, "primitiveMask": 1,
            "max_texture_array_layers": 1, "max_streamout_buffers": 1,
            "max_dual_source_render_targets": 1, "max_render_targets": 1, "max_samples": 1,
            "max_tbo_size": 1, "max_uniform_blocks": 1, "max_viewports": 1,
            "max_texture_gather_components": 1, "capability_bits": None,
        },
    ]


def test_negotiation_candidate_is_registered_and_optionally_host_loadable() -> None:
    assert runner.source_runner.SOURCE_CANDIDATES["android-negotiation"] is runner.source_runner.NEGOTIATION
    candidate = runner.source_runner.NEGOTIATION
    assert candidate.manifest_sha256 == "e1aa871dc22f53e1a9bc68fd86a4d9838c4138d2c9ecf9a7da2d83b2cee37ba3"
    assert candidate.server_sha256 == "6a73e38866ac4a4fe6eeae00e792d9ff1ce2ac21aae925afe5c322cf85e30476"
    assert candidate.library_sha256 == "b6e4f9f91a7c1d97896e374e60d86756e194534a15a364350962ee9e0edfff4e"
    if candidate.manifest.is_file():
        document, files, _raw = runner.source_runner.load_candidate(candidate=candidate)
        assert document["candidateKind"] == "official_source_android_negotiation"
        assert len(files) == 10


def test_typed_trace_and_context_failure_is_observed_not_context_pass() -> None:
    result = runner.validate_negotiation_server_stderr(_stderr(*_trace_rows()))
    assert result["valid"] is True
    assert result["hasCapRequests"] is True and result["capRequestCount"] == 1
    assert result["protocolMetadata"] == {"requested": 2, "selected": 2}

    context_rows = _context_failure_rows()
    encoded = b"".join((json.dumps(row, separators=(",", ":")) + "\n").encode() for row in context_rows)
    status, _events, actual = runner.validate_negotiation_output(
        encoded, RUN, 1, True, server_identity=None,
    )
    assert status == "NEGOTIATION_OBSERVED"
    assert actual == "CONTEXT_FAILED"


def test_empty_caps_is_a_valid_observation_without_fabricated_zero() -> None:
    result = runner.validate_negotiation_server_stderr(_stderr(_trace_rows()[0], _trace_rows()[1]))
    assert result["valid"] is True
    assert result["hasCapRequests"] is True  # Command 1 is a legacy caps request.
    assert result["capRequestCount"] == 1
    assert result["modernCapsetRequestMetadataCount"] == 0


def test_actual_legacy_capset_two_size_and_requests_are_observed() -> None:
    rows = _trace_rows()
    rows[0].update(id=9, length=0)
    rows[3].update(bytes=1408, glsl_level=430, capability_bits=387901405)
    result = runner.validate_negotiation_server_stderr(_stderr(*rows))
    assert result["valid"] is True and result["capRequestCount"] == 1
    rows[3]["bytes"] = 1409
    assert runner.validate_negotiation_server_stderr(_stderr(*rows))["valid"] is False


def test_trace_rejects_malformed_types_extra_identity_and_truncation() -> None:
    valid = _trace_rows()
    for mutation in (
        lambda rows: rows[2].update(valid=True),
        lambda rows: rows[0].update(extra=1),
        lambda rows: rows[0].update(sequence=2),
        lambda rows: rows[3].update(valid=0),
        lambda rows: rows[3].update(bytes=0),
    ):
        broken = copy.deepcopy(valid)
        mutation(broken)
        assert runner.validate_negotiation_server_stderr(_stderr(*broken))["valid"] is False

    extra_identity = _stderr(*valid) + b"WFVTEST_ANDROID_DEFAULT gl.extra=reject\n"
    assert runner.validate_negotiation_server_stderr(extra_identity)["valid"] is False
    truncated = _stderr(*valid) + _line({"schema": 1, "event": "trace_truncated"})
    parsed = runner.validate_negotiation_server_stderr(truncated)
    assert parsed["traceTruncated"] is True and parsed["valid"] is False


def _context_failure_rows() -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    for index, event in enumerate(runner.context_runner.EVENTS):
        row: dict[str, object] = {
            "schema": runner.context_runner.SCHEMA, "runId": RUN, "event": event,
            "timeNs": index + 1, "pid": 42, "uid": runner.context_runner.UID,
            "pgid": 40, "sid": 40,
        }
        for key in runner.context_runner.EVENT_FIELDS[event]:
            if key in runner.context_runner.OPTIONAL_BOOL:
                row[key] = None
            elif key in runner.context_runner.BOOL_FIELDS:
                row[key] = False
            elif key in runner.context_runner.INT_FIELDS:
                row[key] = 0
            else:
                row[key] = "OK" if key in runner.context_runner.NONNULL_STRING else None
        rows.append(row)
    rows[0]["status"] = "STARTED"
    rows[-1].update(status="CONTEXT_FAILED", nativeExit=1)
    return rows
