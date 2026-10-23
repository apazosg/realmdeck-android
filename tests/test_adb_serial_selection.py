"""Host-only regression: ADB device selection comes from WOWFOREVER_ADB_SERIAL.

No test here touches a device, ADB/USB libraries, or any private serial value.
Representative runners cover each enforcement pattern; all fourteen share the
same environment-derived SERIAL global.
"""
from __future__ import annotations

import importlib.util
import json
import re
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

ROOT = Path(__file__).parents[1]
ENV_VAR = "WOWFOREVER_ADB_SERIAL"

RUNNERS = [
    "scripts/diagnostics/usb_forever_trial.py",
    "scripts/diagnostics/android-egl/run_usb.py",
    "scripts/diagnostics/android-panvk/run_device_usb.py",
    "scripts/diagnostics/android-panvk/run_instance_usb.py",
    "scripts/diagnostics/android-panvk/run_private_loader_usb.py",
    "scripts/diagnostics/android-panvk/run_transfer_usb.py",
    "scripts/diagnostics/android-panvk/run_triangle_usb.py",
    "scripts/diagnostics/android-panvk/run_wsi_usb.py",
    "scripts/diagnostics/android-process/run_functional_launcher_ux.py",
    "scripts/diagnostics/android-process/run_functional_poc.py",
    "scripts/diagnostics/android-process/run_native_supervisor_apk.py",
    "scripts/diagnostics/android-process/run_supervisor_capacity.py",
    "scripts/diagnostics/android-process/run_wine_supervisor_apk.py",
    "scripts/diagnostics/android-vtest/run_loader_usb.py",
]


def _load(name: str, relative: str):
    path = ROOT / relative
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def test_serial_comes_from_environment_with_no_runnable_default() -> None:
    for relative in RUNNERS:
        source = (ROOT / relative).read_text()
        assert ENV_VAR in source, relative
        assert re.search(r'^SERIAL\s*=\s*os\.environ\.get\(', source, re.M), relative
        assert not re.search(r'^SERIAL\s*=\s*["\']', source, re.M), relative


def test_contract_uses_device_serial_placeholder_and_keeps_pins() -> None:
    path = ROOT / "scripts/diagnostics/android-process/wine-interrupted-run-disposition-contract.json"
    value = json.loads(path.read_text())
    device = value["device"]
    assert device["serial"] == "DEVICE_SERIAL"
    assert device["package"] == "com.wowforever.arm64probe"
    assert device["uid"] == 10330
    assert device["device"] == "xaga"
    assert device["model"] == "22041216G"
    assert device["manufacturer"] == "Xiaomi"
    assert device["api"] == 34


def test_usb_trial_requires_serial_before_any_adb_or_usb(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(ENV_VAR, raising=False)
    module = _load("serial_usb_trial", RUNNERS[0])
    assert module.SERIAL == ""
    monkeypatch.setattr(module.subprocess, "run", lambda *a, **k: (_ for _ in ()).throw(AssertionError("ADB reached")))
    monkeypatch.setattr(module.subprocess, "Popen", lambda *a, **k: (_ for _ in ()).throw(AssertionError("Popen reached")))
    with pytest.raises(module.TrialError, match=ENV_VAR):
        module.require_serial()
    with pytest.raises(module.TrialError, match=ENV_VAR):
        module.Adb()
    with pytest.raises(module.TrialError, match=ENV_VAR):
        module.UsbKeyboard()
    with pytest.raises(module.TrialError, match=ENV_VAR):
        module.run_trial(SimpleNamespace(output=Path("/nonexistent"), version_code=39, version_name="x"))


def test_vtest_loader_wrapper_fails_before_subprocess(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(ENV_VAR, raising=False)
    module = _load("serial_vtest_loader", RUNNERS[13])
    assert module.SERIAL == ""
    monkeypatch.setattr(module.subprocess, "run", lambda *a, **k: (_ for _ in ()).throw(AssertionError("ADB reached")))
    with pytest.raises(RuntimeError, match=ENV_VAR):
        module.adb("get-state")


def test_capacity_wrapper_fails_before_subprocess(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(ENV_VAR, raising=False)
    module = _load("serial_capacity", "scripts/diagnostics/android-process/run_supervisor_capacity.py")
    assert module.SERIAL == ""
    with pytest.raises(RuntimeError, match=ENV_VAR):
        module._adb("get-state")


def test_native_supervisor_real_adb_client_requires_serial(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(ENV_VAR, raising=False)
    module = _load("serial_native_supervisor", "scripts/diagnostics/android-process/run_native_supervisor_apk.py")
    assert module.SERIAL == ""
    monkeypatch.setattr(module, "_helpers", lambda: (_ for _ in ()).throw(AssertionError("USB helper reached")))
    with pytest.raises(module.CollectorError, match=ENV_VAR):
        module.AdbClient()
    # Injectable fakes stay usable for host-only tests.
    assert module.AdbClient(raw=object()).raw is not None


def test_functional_launcher_smoke_requires_serial_before_touch(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(ENV_VAR, raising=False)
    module = _load("serial_functional_launcher", "scripts/diagnostics/android-process/run_functional_launcher_ux.py")
    assert module.SERIAL == ""
    monkeypatch.setattr(module, "_helpers", lambda: (_ for _ in ()).throw(AssertionError("USB helper reached")))
    with pytest.raises(module.SmokeError, match=ENV_VAR):
        module.run_smoke(tmp_path, 5)


def test_wsi_help_is_usable_without_a_device(monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]) -> None:
    monkeypatch.delenv(ENV_VAR, raising=False)
    module = _load("serial_wsi_help", "scripts/diagnostics/android-panvk/run_wsi_usb.py")
    assert module.SERIAL == ""
    with pytest.raises(SystemExit) as exc:
        module.main(["--help"])
    assert exc.value.code == 0
    assert "--output" in capsys.readouterr().out


def test_explicit_serial_reaches_the_adb_command(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(ENV_VAR, "TEST-HOST-ONLY-SERIAL")
    module = _load("serial_vtest_explicit", RUNNERS[13])
    assert module.SERIAL == "TEST-HOST-ONLY-SERIAL"
    captured: list[list[str]] = []

    def fake_run(argv, **kwargs):
        captured.append(list(argv))
        return subprocess.CompletedProcess(argv, 0, b"", b"")

    monkeypatch.setattr(module.subprocess, "run", fake_run)
    module.adb("get-state")
    assert captured[0][:3] == [module.ADB, "-s", "TEST-HOST-ONLY-SERIAL"]
