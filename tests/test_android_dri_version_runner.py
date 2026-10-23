from __future__ import annotations

import importlib.util
import hashlib
import json
import shlex
import sys
from pathlib import Path


PATH = Path(__file__).parents[1] / "scripts/diagnostics/android-vtest"
sys.path.insert(0, str(PATH))
SPEC = importlib.util.spec_from_file_location("dri_version_runner_tests", PATH / "run_dri_version_usb.py")
runner = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)

RUN = "VTEST-" + "a" * 32


def record(**changes):
    row = {
        "schema": runner.SCHEMA,
        "runId": RUN,
        "pid": 123,
        "uid": 10330,
        "callSequence": 1,
        "api": 3,
        "numAttribs": 2,
        "threadSafe": True,
        "attributesObserved": True,
        "attributesOversize": False,
        "attributePairs": [[0, 3], [1, 3]],
        "providerVerified": True,
        "forwarded": True,
        "resultPresent": False,
        "errorPresent": True,
        "error": 3,
        "incomingErrno": 13,
        "returnedErrno": 13,
        "layoutVerified": True,
        "scopeMatches": True,
        "requestedVersion": 33,
        "maximumBefore": 33,
        "maximumAfter": 33,
        "beforeReadBytes": 4,
        "afterReadBytes": 4,
        "beforeReadErrno": 0,
        "afterReadErrno": 0,
        "measurementValid": True,
        "checkInference": "NOT_EXPLAINED_BY_EARLY_CHECK",
    }
    row.update(changes)
    return row


def raw(*rows, mesa=True):
    prefix = b"MESA-EGL: debug: EGL_BAD_MATCH in dri2_create_context\n" if mesa else b""
    return prefix + b"".join(runner.PREFIX + json.dumps(row, separators=(",", ":")).encode() + b"\n" for row in rows)


def observed(row=None):
    return runner.validate_guest_stderr(raw() if row is None else raw(row))


def bound(observation, **changes):
    params = {
        "run": RUN,
        "pid": 123,
        "result_present": False,
        "native_status": runner.PASS_STATUS,
        "scope_valid": True,
    }
    params.update(changes)
    return runner.bind_observation(observation, **params)


def test_valid_maximum_boundaries_and_recomputed_inference():
    for maximum, inference in (
        (0, "EARLY_CHECK_WOULD_REJECT"),
        (32, "EARLY_CHECK_WOULD_REJECT"),
        (33, "NOT_EXPLAINED_BY_EARLY_CHECK"),
        (40, "NOT_EXPLAINED_BY_EARLY_CHECK"),
    ):
        row = record(maximumBefore=maximum, maximumAfter=maximum, checkInference=inference)
        parsed = observed(row)
        assert parsed["valid"] and parsed["observerRecords"][0]["measurementValid"]
        result = bound(parsed)
        assert result["status"] == runner.PASS_STATUS
        assert result["observedMaximum"] == maximum
        assert result["derivedCheckInference"] == inference

    row = record(resultPresent=True, error=0, checkInference="NO_BAD_VERSION")
    result = bound(observed(row), result_present=True)
    assert result["status"] == runner.PASS_STATUS
    assert result["derivedCheckInference"] == "NO_BAD_VERSION"


def test_changed_unread_partial_permission_and_out_of_range_are_not_measurements():
    cases = (
        {"maximumBefore": 32, "maximumAfter": 33, "measurementValid": False, "checkInference": "UNKNOWN"},
        {"maximumBefore": None, "maximumAfter": None, "beforeReadBytes": None, "afterReadBytes": None,
         "beforeReadErrno": None, "afterReadErrno": None, "measurementValid": False, "checkInference": "UNKNOWN"},
        {"maximumBefore": None, "maximumAfter": None, "beforeReadBytes": 2, "afterReadBytes": 2,
         "beforeReadErrno": 5, "afterReadErrno": 5, "measurementValid": False, "checkInference": "UNKNOWN"},
        {"maximumBefore": None, "maximumAfter": None, "beforeReadBytes": -1, "afterReadBytes": -1,
         "beforeReadErrno": 1, "afterReadErrno": 1, "measurementValid": False, "checkInference": "UNKNOWN"},  # EPERM
        {"maximumBefore": None, "maximumAfter": None, "beforeReadBytes": -1, "afterReadBytes": -1,
         "beforeReadErrno": 14, "afterReadErrno": 14, "measurementValid": False, "checkInference": "UNKNOWN"},  # EFAULT
        {"maximumBefore": 100, "maximumAfter": 100, "measurementValid": False, "checkInference": "UNKNOWN"},
    )
    for changes in cases:
        parsed = observed(record(**changes))
        assert parsed["valid"]
        result = bound(parsed)
        assert result["status"] == runner.MEASUREMENT_UNKNOWN
        assert result["observedMaximum"] is None
        assert result["derivedCheckInference"] is None


def test_no_records_overread_more_than_four_and_bad_schema_are_rejected_or_unobserved():
    empty = observed()
    assert empty["valid"]
    assert bound(empty)["status"] == runner.TRACE_NOT_OBSERVED

    assert not observed(record(beforeReadBytes=5, maximumBefore=None, maximumAfter=None,
                               afterReadBytes=5, beforeReadErrno=0, afterReadErrno=0,
                               measurementValid=False, checkInference="UNKNOWN"))["valid"]
    too_many = raw(*(record(callSequence=index) for index in range(1, 6)))
    assert not runner.validate_guest_stderr(too_many)["valid"]

    malformed = record(schema="wrong")
    assert not observed(malformed)["valid"]
    duplicate = raw(record()).replace(b'"schema":', b'"schema":"duplicate","schema":', 1)
    assert not runner.validate_guest_stderr(duplicate)["valid"]


def test_scope_wrong_run_pid_result_stale_params_and_native_unknown_disable_binding():
    wrong_scope = record(api=2, scopeMatches=False, requestedVersion=None,
                         maximumBefore=None, maximumAfter=None, beforeReadBytes=None,
                         afterReadBytes=None, beforeReadErrno=None, afterReadErrno=None,
                         measurementValid=False, checkInference="UNKNOWN")
    parsed = observed(wrong_scope)
    assert parsed["valid"]
    assert bound(parsed)["status"] == runner.TRACE_NOT_OBSERVED
    for changes in (
        {"run": "VTEST-" + "b" * 32},
        {"pid": 124},
        {"result_present": True},
        {"native_status": "UNKNOWN"},
        {"scope_valid": False},
    ):
        assert bound(observed(record()), **changes)["status"] == runner.TRACE_NOT_OBSERVED


def test_forged_inference_and_duplicate_or_stale_sequences_do_not_pass():
    assert not observed(record(checkInference="NO_BAD_VERSION"))["valid"]
    assert not observed(record(callSequence=2))["valid"]
    duplicate_sequence = raw(record(), record())
    assert not runner.validate_guest_stderr(duplicate_sequence)["valid"]


def test_highest_sequence_is_selected_not_last_write_order():
    first = record(callSequence=1, maximumBefore=32, maximumAfter=32, checkInference="EARLY_CHECK_WOULD_REJECT")
    second = record(callSequence=2, maximumBefore=40, maximumAfter=40, checkInference="NOT_EXPLAINED_BY_EARLY_CHECK")
    parsed = runner.validate_guest_stderr(raw(second, first))
    assert parsed["valid"]
    result = bound(parsed)
    assert result["status"] == runner.PASS_STATUS
    assert result["observedMaximum"] == 40 and result["observedCallSequence"] == 2


def test_command_is_private_fixed_child_with_one_preload_and_exact_environment():
    root = f"/data/user/0/{runner.context.PACKAGE}/{runner.context.PRIVATE_PREFIX}/{RUN}"
    command = runner.native_command(root, RUN)
    assert command[:7] == [runner.context.ADB, "-s", runner.context.SERIAL, "shell", "-T", "run-as", runner.context.PACKAGE]
    inner = shlex.split(command[-1])[0]
    tokens = shlex.split(inner)
    preload = [token for token in tokens if token.startswith("LD_PRELOAD=")]
    assert preload == [f"LD_PRELOAD={root}/guest/observer/{runner.OBSERVER}"]
    assert tokens.count(preload[0]) == 1
    env_start = tokens.index("-i") + 1
    assert tokens[env_start:env_start + 12] == [
        "PATH=/system/bin:/system/xbin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
        f"LD_LIBRARY_PATH={root}/guest/lib:/system/lib64", f"TMPDIR={root}",
        f"__EGL_VENDOR_LIBRARY_FILENAMES={root}/guest/share/glvnd/egl_vendor.d/50_mesa.json",
        "GALLIUM_DRIVER=virpipe", "VTEST_SOCKET_NAME=vtest.sock", "EGL_LOG_LEVEL=debug",
        "MESA_LOG=file", "MESA_LOG_LEVEL=debug", preload[0],
    ]
    assert tokens[-3:] == [f"{root}/guest/bin/{runner.NAME}", "--run-id", RUN]
    assert "GALLIUM_DRIVER=virpipe" in tokens
    assert "VTEST_SOCKET_NAME=vtest.sock" in tokens
    assert "__EGL_VENDOR_LIBRARY_FILENAMES=" + root + "/guest/share/glvnd/egl_vendor.d/50_mesa.json" in tokens
    try:
        runner.native_command("/data/local/tmp/not-private", RUN)
    except ValueError:
        pass
    else:
        raise AssertionError("unapproved private root was accepted")


def test_fixed_source_artifact_guest_provider_and_negotiation_pins_are_explicit():
    assert runner.PASS_STATUS == "DRI_VERSION_CONTROL_OBSERVED"
    assert runner.BASE == Path("/home/adrian/wowforever-artifacts/arm64-prototype/controls/dri-version-limit")
    assert runner.ARTIFACT_PINS == {
        runner.NAME: (36736, "f23e616bfc20fe63249c8ac47004587bf83bddc81ed0f257edb859e405ab8a37"),
        runner.OBSERVER: (25456, "46d7aaa9be73557bb9129b72a4b3b5ea38cafdd2feda428c999b114aafc222cb"),
    }
    assert runner.SOURCE_PINS["DriVersionObserver.c"] == "0cd2c64d30d577bce2853095c00f7f8f3fa096df417db2ae356ff152ff102330"
    for source, digest in runner.SOURCE_PINS.items():
        assert len(digest) == 64
        assert hashlib.sha256((PATH / source).read_bytes()).hexdigest() == digest
    assert runner.SOURCE_PINS["guest-egl-dri-version.patch"] == "a7f9b09745d58563d98a21bc3af0f4c06fdb2e70f5058d8440dd9195c0132da9"
    assert runner.SOURCE_PINS["build_dri_version_observer.sh"] == "d600bf75c9f984f7abfbc581a416f9bac553316d54f6ee3eeb572df758a56aaa"
    assert runner.PROVIDER_BYTES == 17656040
    assert runner.PROVIDER_SHA256 == "910cc41d1a77427dafd16c8049425504576879a30767aa7473ab70629ed348b5"
    assert runner.GUEST_MANIFEST_SHA256 == "30c43814697a22dc72ee4e3b1eeb8ec9d6b6af228ee9c4ffc21e15b3fe8d9ce9"
    assert runner.GUEST_TREE_SHA256 == "1cc803fbcbef9a7d1743966702c733fc63b8e311a3aa77f8020ee7594e11d216"
    assert runner.NEGOTIATION_MANIFEST_SHA256 == "e1aa871dc22f53e1a9bc68fd86a4d9838c4138d2c9ecf9a7da2d83b2cee37ba3"
