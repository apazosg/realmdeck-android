"""HOST-only fixed triangle control; no ADB/ARM execution."""
import importlib.util
import json
from pathlib import Path

import pytest

PATH = Path(__file__).parents[1] / "scripts/diagnostics/android-vtest/run_triangle_usb.py"
SPEC = importlib.util.spec_from_file_location("vtest_triangle_runner", PATH)
assert SPEC and SPEC.loader
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


def event(**changes):
    value = {
        "runId": "VTEST-test", "status": runner.NATIVE_SUCCESS_STATUS,
        "nativePid": 101, "uid": 10330, "peerPid": 202, "peerUid": 10330,
        "expectedServerPid": 202, "expectedServerStartTicks": 123, "serverIdentityVerified": True,
        "stages": runner.STAGES, "capBytes": 308, "capNonzeroBytes": 67,
        "capsFNVhash": "388f8b4b8a652997", "durationMs": 99,
        "protocolCaps": {"capBytes": 308, "capNonzeroBytes": 67, "capsFNVhash": "388f8b4b8a652997"},
        "width": 64, "height": 64, "readbackBytes": 16384, "clearPixels": 4096,
        "trianglePixels": 1300, "greenPixels": 2796, "readbackFNVhash": "0123456789abcdef",
        **{key: True for key in ("clearVerified", "nonuniformRGB", "unrefSent", "resourceUnrefSent", "triangleVerified", "offscreenDrawVerified")},
        **{key: False for key in (*runner.shared.BROADER_FLAGS, "presentationVerified", "d3dVerified", "wowVerified", "hardwareRenderingVerified")},
    }
    value.update(changes)
    return (json.dumps(value, separators=(",", ":")) + "\n").encode()


def classify(**changes):
    values = dict(
        run="VTEST-test", server_stdout=runner.shared.SERVER_STDOUT_CAPS_SEQUENCE,
        server_stderr=b"bounded GL metadata\n", client_stdout=event(), client_stderr=b"",
        server_exit=0, client_exit=0, timed_out=False, complete=True,
        expected_server={"pid": 202, "startTicks": 123},
    )
    values.update(changes)
    return runner.classify_triangle(**values)


def test_offscreen_gate_and_no_broader_claims():
    status, parsed, reason = classify()
    assert status == runner.SUCCESS_STATUS and reason is None
    assert parsed["triangleVerified"] is True
    assert parsed["presentationVerified"] is False
    assert parsed["d3dVerified"] is False
    assert parsed["wowVerified"] is False
    assert runner.shared.TRIANGLE_BOUNDARY == runner.BOUNDARY
    assert (runner.CLIENT_NAME, runner.SUCCESS_STATUS, runner.BOUNDARY) in runner.shared._REGISTERED_INTEGRATIONS


@pytest.mark.parametrize("changes", [
    {"extra": 1}, {"runId": "VTEST-old"}, {"status": "VTEST_TRIANGLE_OFFSCREEN"},
    {"expectedServerStartTicks": True}, {"peerPid": 203}, {"durationMs": 15001},
    {"trianglePixels": 0, "greenPixels": 4096}, {"greenPixels": 0},
    {"clearPixels": 4095}, {"clearVerified": 1}, {"nonuniformRGB": False},
    {"unrefSent": False}, {"readbackBytes": 16383}, {"readbackFNVhash": "0" * 16},
    {"presentationVerified": True}, {"wowVerified": True},
    {"stages": runner.STAGES[:-1]},
    {"protocolCaps": {"capBytes": True, "capNonzeroBytes": 67, "capsFNVhash": "388f8b4b8a652997"}},
])
def test_rejects_bad_identity_schema_pixels_and_claims(changes):
    assert classify(client_stdout=event(**changes))[0] != runner.SUCCESS_STATUS


def test_rejects_truncation_errors_exit_and_peer():
    for changes in (
        {"timed_out": True}, {"forced_cleanup": True}, {"ownership_error": "UNKNOWN"},
        {"complete": False}, {"client_stdout_truncated": True}, {"client_exit": 1},
        {"server_exit": None}, {"expected_server": {"pid": 203, "startTicks": 123}},
        {"client_stdout": b"0\n"}, {"client_stdout": event()[:-1]},
        {"server_stderr": b"failed to initialise renderer."}, {"server_stdout": b""},
    ):
        assert classify(**changes)[0] != runner.SUCCESS_STATUS
    duplicate = event().replace(b'"width":64', b'"width":64,"width":64')
    assert classify(client_stdout=duplicate)[0] != runner.SUCCESS_STATUS


def test_fixed_cli_and_artifact_source_gates(monkeypatch, tmp_path):
    calls = []
    monkeypatch.setattr(runner.shared, "main", lambda args, **kw: calls.append((args, kw)) or 0)
    assert runner.main(["--output", str(tmp_path)]) == 0
    assert calls[0][0][-1] == "20"
    assert calls[0][1]["client_name"] == "VtestTriangle"
    for invalid in ("4", "21", "30"):
        with pytest.raises(SystemExit):
            runner.main(["--output", str(tmp_path), "--seconds", invalid])
    with pytest.raises(SystemExit):
        runner.main(["--output", str(tmp_path), "--client", "other"])
    loaded = runner.load_client()
    assert loaded["sha256"] == runner.CLIENT_SHA256
    assert loaded["source"]["sha256"] == runner.KNOWN_SOURCE_SHA256
    assert loaded["includedSource"]["sha256"] == runner.resource.KNOWN_INCLUDED_SOURCE_SHA256
