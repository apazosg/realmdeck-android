from __future__ import annotations

import importlib.util
import struct
import sys
import traceback
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
ADAPTER_SCRIPT = ROOT / "scripts/diagnostics/android-process/functional_login_uhid.py"
ADAPTER_SPEC = importlib.util.spec_from_file_location(
    "functional_login_uhid_test_subject", ADAPTER_SCRIPT
)
assert ADAPTER_SPEC and ADAPTER_SPEC.loader
adapter = importlib.util.module_from_spec(ADAPTER_SPEC)
sys.modules[ADAPTER_SPEC.name] = adapter
ADAPTER_SPEC.loader.exec_module(adapter)

LOGIN_SCRIPT = ROOT / "scripts/diagnostics/android-process/functional_login.py"
LOGIN_SPEC = importlib.util.spec_from_file_location(
    "functional_login_uhid_login_peer", LOGIN_SCRIPT
)
assert LOGIN_SPEC and LOGIN_SPEC.loader
login = importlib.util.module_from_spec(LOGIN_SPEC)
sys.modules[LOGIN_SPEC.name] = login
LOGIN_SPEC.loader.exec_module(login)


class FakeSocket:
    def __init__(self, *, fail_on: int = 0) -> None:
        self.sent: list[bytes] = []
        self.calls = 0
        self.fail_on = fail_on

    def sendall(self, data: bytes) -> None:
        self.calls += 1
        if self.fail_on and self.calls == self.fail_on:
            raise OSError("transport-down")
        self.sent.append(bytes(data))


def test_press_preserves_report_hold_and_release(monkeypatch):
    events = []

    class Socket:
        def sendall(self, report):
            events.append(("send", report))

    monkeypatch.setattr(adapter.time, "sleep", lambda delay: events.append(("sleep", delay)))
    send = adapter.make_wifi_uhid_send(Socket())
    send(0, 42)
    prefix = struct.pack(">BHH", 13, 1, 8)
    assert events == [
        ("send", prefix + bytes((0, 0, 42, 0, 0, 0, 0, 0))),
        ("sleep", 0.08),
        ("send", prefix + bytes(8)),
        ("sleep", 0.08),
    ]


def _expected(modifier: int, usage: int) -> bytes:
    return struct.pack(">BHH", 13, 1, 8) + bytes(
        (modifier, 0, usage, 0, 0, 0, 0, 0)
    )


_RELEASE = struct.pack(">BHH", 13, 1, 8) + bytes(8)


def test_press_and_release_framing_matches_probe_layout() -> None:
    sock = FakeSocket()
    send = adapter.make_wifi_uhid_send(sock)
    send(0, 4)  # 'a'
    send(2, 31)  # '@'
    send(1, 4)  # Ctrl+A
    assert sock.sent == [
        _expected(0, 4),
        _RELEASE,
        _expected(2, 31),
        _RELEASE,
        _expected(1, 4),
        _RELEASE,
    ]


def test_arbitrary_modifier_and_usage_are_sent() -> None:
    sock = FakeSocket()
    send = adapter.make_wifi_uhid_send(sock)
    for modifier, usage in ((8, 4), (4, 4), (0, 99), (255, 255)):
        send(modifier, usage)
    assert sock.sent == [
        _expected(8, 4),
        _RELEASE,
        _expected(4, 4),
        _RELEASE,
        _expected(0, 99),
        _RELEASE,
        _expected(255, 255),
        _RELEASE,
    ]


def test_opaque_socket_text_never_reaches_traceback() -> None:
    canary = "opaque-canary-9f3a"

    class CanarySocket(FakeSocket):
        def sendall(self, data: bytes) -> None:
            self.calls += 1
            raise OSError(canary)

    send = adapter.make_wifi_uhid_send(CanarySocket())
    with pytest.raises(adapter.WifiUhidError, match="transport-failed"):
        try:
            send(0, 4)
        except adapter.WifiUhidError:
            assert canary not in traceback.format_exc()
            raise


def test_invalid_modifier_usage_rejected_without_device_traffic() -> None:
    sock = FakeSocket()
    send = adapter.make_wifi_uhid_send(sock)
    for modifier, usage in (
        (0, 256),
        (256, 0),
        (-1, 4),
        (0, -1),
        (True, 4),  # bool is not an HID byte
    ):
        with pytest.raises(adapter.WifiUhidError):
            send(modifier, usage)  # type: ignore[arg-type]
    with pytest.raises(adapter.WifiUhidError):
        send("0", 4)  # type: ignore[arg-type]
    assert sock.sent == [] and sock.calls == 0


def test_release_attempted_even_when_press_fails() -> None:
    sock = FakeSocket(fail_on=1)
    send = adapter.make_wifi_uhid_send(sock)
    with pytest.raises(adapter.WifiUhidError, match="transport-failed"):
        send(0, 4)
    assert sock.calls == 2
    assert sock.sent == [_RELEASE]


def test_send_is_compatible_with_submit_login() -> None:
    sock = FakeSocket()
    send = adapter.make_wifi_uhid_send(sock)
    phases: list[str] = []
    credentials = login.Credentials("aB1", "cD2")
    login.submit_login(
        None,
        credentials,
        phase_guard=phases.append,
        send=send,
        action=lambda _name: None,
    )
    assert phases == ["username", "tab", "password", "submit"]
    # username(3) + Ctrl+A + tab + password(3) + Ctrl+A + home + enter
    assert len(sock.sent) == 2 * (3 + 1 + 1 + 3 + 1 + 1 + 1)
    assert sock.sent[-2] == _expected(0, 40)
    assert sock.sent[-1] == _RELEASE
