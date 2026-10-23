"""Offline HOST runner tests for the private guest EGL context control."""

from __future__ import annotations

import copy
import importlib.util
import json
from pathlib import Path


ROOT = Path(__file__).parents[1]
DIRECTORY = ROOT / "scripts/diagnostics/android-vtest"
SPEC = importlib.util.spec_from_file_location("guest_context_runner", DIRECTORY / "run_guest_context_usb.py")
assert SPEC and SPEC.loader
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)

RUN = "VTEST-" + "a" * 32
PRIVATE = f"/data/user/0/{runner.PACKAGE}/files/forever/controls/vtest-loader/{RUN}"


def _row(event: str, **changes: object) -> dict[str, object]:
    row: dict[str, object] = {
        "schema": runner.SCHEMA, "runId": RUN, "event": event, "timeNs": 1,
        "pid": 42, "uid": runner.UID, "pgid": 40, "sid": 40,
    }
    for key in runner.EVENT_FIELDS[event]:
        if key in runner.OPTIONAL_BOOL:
            row[key] = None
        elif key in runner.BOOL_FIELDS:
            row[key] = False
        elif key in runner.INT_FIELDS:
            row[key] = 0
        else:
            row[key] = None
    row.update(changes)
    return row


def _pass_rows() -> list[dict[str, object]]:
    rows = [_row(event, timeNs=index + 1) for index, event in enumerate(runner.EVENTS)]
    rows[0]["status"] = "STARTED"
    rows[1].update(status="VALID", scopeValid=True, runIdValid=True, uidValid=True, cwdValid=True, environmentValid=True, selectionValid=True, cwd=PRIVATE, libraryPath=PRIVATE + "/guest/lib/libEGL.so.1.1.0", serverIdentityLoaded=True, serverIdentityVerified=True, expectedServerPid=202, expectedServerStartTicks=123)
    rows[2].update(status="LOADED", loaded=True, privatePath=PRIVATE + "/guest/lib/libEGL.so.1.1.0", canonicalPath=PRIVATE + "/guest/lib/libEGL.so.1.1.0", loadError=None)
    rows[3].update(status="EXPORTED", exportCount=10, expectedExportCount=10, allExports=True, ownerExact=True, ownerPath=PRIVATE + "/guest/lib/libEGL.so.1.1.0", missingSymbol=None, wrongOwnerSymbol=None)
    rows[4].update(status="MEASURED", clientQueryValid=True, clientExtensionScanValid=True, clientExtensions="EGL_EXT_platform_base", clientExtensionScanBytes=23, clientExtensionsTruncated=False, hasEGL_EXT_platform_base=True, displayCreated=True, displayInitialized=True, displayExtensions="EGL_KHR_create_context EGL_KHR_surfaceless_context", displayExtensionScanBytes=55, displayExtensionsTruncated=False, hasEGL_KHR_create_context=True, hasEGL_KHR_surfaceless_context=True, vendor="Mesa", version="1", clientApis="OpenGL", major=1, minor=5, eglError=0x3000, serverIdentityVerified=True)
    rows[5].update(status="CURRENT", boundApi=True, configCount=1, configChosen=True, contextCreated=True, contextMajor=3, contextMinor=3, profileCore=True, makeCurrent=True, eglError=0x3000, serverIdentityVerified=True)
    rows[6].update(status="MEASURED", glExportsResolved=True, vendor="Mesa", renderer="virgl", version="4.6", glslVersion="3.30", rendererContainsVirgl=True, softwareFallback=False, glError=0, serverIdentityVerified=True)
    rows[7].update(status="MAPPED", mapsRead=True, mapsComplete=True, mapsTruncated=False, mapsBytes=1024, mapLines=8, privateLoaderMapped=True, privateVendorMapped=True, privateGalliumMapped=True, privateLlvmMapped=True, androidEglMapped=False, androidGlesMapped=False, mappingError=None)
    rows[8].update(status="CLEAN", unbindAttempted=True, unbindOk=True, destroyAttempted=True, destroyOk=True, terminateAttempted=True, terminateOk=True, handleClosed=True, cleanupFailures=0, serverIdentityRevalidated=True, cleanupStatus="CLEAN")
    rows[9].update(status="GUEST_CONTEXT_PASS", privateLoaderVerified=True, exportsVerified=True, displayVerified=True, eglContextVerified=True, virpipeVerified=True, glRendererVerified=True, identityVerified=True, mappingsVerified=True, cleanupVerified=True, nativeExit=0)
    return rows


def _encoded(rows: list[dict[str, object]]) -> bytes:
    return b"".join((json.dumps(row, separators=(",", ":")) + "\n").encode() for row in rows)


def test_native_command_is_fixed_and_has_no_display_or_preload() -> None:
    command = runner.native_command(PRIVATE, RUN)
    text = command[-1]
    assert "GuestEglContext --run-id " + RUN in text
    assert "guest/lib:/system/lib64" in text and "GALLIUM_DRIVER=virpipe" in text
    assert "__EGL_VENDOR_LIBRARY_FILENAMES=" in text and "VTEST_SOCKET_NAME=vtest.sock" in text
    assert "DISPLAY" not in text and "LD_PRELOAD" not in text and "virgl_test_server" not in text


def test_classifier_requires_context_renderer_identity_mappings_and_cleanup() -> None:
    raw = _encoded(_pass_rows())
    assert runner.validate_output(raw, RUN, 0, True, server_identity={"pid": 202, "startTicks": 123})[0] == "GUEST_CONTEXT_PASS"
    for index, key, value in ((6, "rendererContainsVirgl", False), (6, "softwareFallback", True), (8, "cleanupFailures", 1), (9, "virpipeVerified", False)):
        broken = copy.deepcopy(_pass_rows())
        broken[index][key] = value
        assert runner.validate_output(_encoded(broken), RUN, 0, True, server_identity={"pid": 202, "startTicks": 123})[0] == "UNKNOWN"


def test_classifier_marks_native_context_failure_without_overclaim() -> None:
    rows = _pass_rows()
    rows[9]["status"] = "CONTEXT_FAILED"
    rows[9]["nativeExit"] = 1
    assert runner.validate_output(_encoded(rows), RUN, 1, True, server_identity={"pid": 202, "startTicks": 123})[0] == "CONTEXT_FAILED"


def test_partial_or_wrong_context_evidence_never_passes() -> None:
    for index, key, value in ((5, "eglError", 0x3009), (5, "profileCore", False), (5, "contextMajor", 2), (5, "configCount", 2), (0, "timeNs", 0), (7, "androidEglMapped", True), (9, "wowVerified", True), (1, "expectedServerStartTicks", 124), (3, "missingSymbol", "x"), (5, "serverIdentityVerified", False), (6, "version", None)):
        rows = _pass_rows()
        rows[index][key] = value
        assert runner.validate_output(_encoded(rows), RUN, 0, True, server_identity={"pid": 202, "startTicks": 123})[0] == "UNKNOWN"
    assert runner.validate_output(_encoded(_pass_rows())[:-1], RUN, 0, True)[0] == "UNKNOWN"


def test_context_copier_rejects_unapproved_source_manifest(monkeypatch) -> None:
    guest = runner.guest_loader
    monkeypatch.setattr(guest, "_canonical_app_root", lambda root: root)
    monkeypatch.setattr(guest, "_validate_copy_inputs", lambda *_: None)
    import pytest
    with pytest.raises(ValueError, match="fixed Android-default"):
        guest.copy_payload_to_existing_run([], {}, RUN, [], app_root=f"/data/user/0/{runner.PACKAGE}", server_files=[], server_manifest_sha256="0" * 64)


def test_shared_runner_api_calls_resolve_without_device_actions() -> None:
    import ast
    tree = ast.parse((DIRECTORY / "run_guest_context_usb.py").read_text())
    for node in ast.walk(tree):
        if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name) and node.value.id == "shared":
            assert hasattr(runner.shared, node.attr), node.attr
