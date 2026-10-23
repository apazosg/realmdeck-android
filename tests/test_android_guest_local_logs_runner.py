import importlib.util
from pathlib import Path
import shlex
import sys

import pytest

PATH = Path(__file__).parents[1] / "scripts/diagnostics/android-vtest/run_guest_local_logs_usb.py"
sys.path.insert(0, str(PATH.parent))
SPEC = importlib.util.spec_from_file_location("local_egl_logs_runner", PATH)
M = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = M
SPEC.loader.exec_module(M)


def test_fixed_environment_and_command():
    run = "VTEST-" + "a" * 32
    root = f"/data/user/0/{M.context.PACKAGE}/{M.context.PRIVATE_PREFIX}/{run}"
    command = M.native_command(root, run)
    assert command[:7] == [M.context.ADB, "-s", M.context.SERIAL, "shell", "-T", "run-as", M.context.PACKAGE]
    tokens = shlex.split(shlex.split(command[-1])[0])
    assert tokens.count("EGL_LOG_LEVEL=debug") == 1
    assert tokens.count("MESA_LOG=file") == 1
    assert tokens.count("MESA_LOG_LEVEL=debug") == 1
    assert "GALLIUM_DRIVER=virpipe" in tokens
    assert "MESA_DEBUG=context" not in tokens
    assert tokens[-2:] == ["--run-id", run]
    assert tokens[-3] == root + "/guest/bin/GuestEglLocalLogs"
    with pytest.raises(ValueError):
        M.native_command(root + "/other", run)
    with pytest.raises(ValueError):
        M.native_command(root, run + ";id")


def test_logs_do_not_identify_hidden_dri_error():
    raw = M.context.OPTIONAL_CACHE_DISABLED_WARNING + b"MESA-EGL: debug: EGL user error 0x3009 (EGL_BAD_MATCH) in dri2_create_context\n"
    result = M.validate_guest_logs(raw)
    assert result["valid"] and result["mappedBadMatchObserved"] and result["driCreateContextObserved"]
    assert result["exactDriErrorIdentified"] is False
    for bad in (b"", raw[:-1], raw + b"unexpected error\n", raw + b"MESA-EGL: debug: bad\x00\n", raw + b"MESA-EGL: debug: " + b"x" * 4097 + b"\n"):
        assert M.validate_guest_logs(bad)["valid"] is False


def test_artifact_matches_verified_native_build():
    item = M.probe_file()
    assert item["bytes"] == 36544
    assert item["sha256"] == M.SHA256
    assert item["destination"] == "guest/bin/GuestEglLocalLogs"


def test_existing_context_defaults_remain_strict():
    import inspect
    signature = inspect.signature(M.context.main)
    assert signature.parameters["guest_stderr_validator"].default is None
    assert signature.parameters["source_candidate"].default is None
    assert M.context.OPTIONAL_CACHE_DISABLED_WARNING == b"Failed to create /data/data/com.termux/files for shader cache (Permission denied)---disabling.\n"
