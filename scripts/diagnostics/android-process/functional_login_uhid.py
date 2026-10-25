#!/usr/bin/env python3
"""HOST-ONLY WiFi UHID send adapter over an existing scrcpy control socket.

This module creates no transport of its own: no ADB, no USB/AOA, no scrcpy
client or server process, no new keyboard device, and no login framework.
The caller passes the *already-created* official scrcpy WiFi UHID control
socket (the one ``WifiUhidKeyboard`` opened in the external probe). The
returned ``send(modifier, usage)`` callback is directly compatible with
``functional_login.submit_login``.

The adapter applies no key allowlist and no guard callback: any 8-bit HID
modifier and usage is sent unchanged. Callers decide which presses their
session needs.

Framing (copied exactly from the external probe, not guessed)::

    struct.pack('>BHH', 13, 1, len(report)) + report

``13`` is the scrcpy control message type for UHID input, ``1`` is the
uint16-BE UHID device id used at creation, and ``report`` is the 8-byte boot
keyboard report ``bytes((modifier, 0, usage, 0, 0, 0, 0, 0))`` followed by
the all-zero release ``bytes(8)``.

The release report is always sent after a press attempt, mirroring the
existing USB ``key()`` finally-release behaviour. Each report is held for
80 ms, matching the pinned WiFi navigation helper.

Failures carry only stable codes (``wifi-uhid-*``). Socket exceptions are
translated with ``from None`` so no transport detail or traceback chain
surfaces.
"""

from __future__ import annotations

import struct
import time
from typing import Any, Callable


class WifiUhidError(RuntimeError):
    """Stable, non-secret WiFi UHID adapter failure."""


_UHID_INPUT_PREFIX = struct.pack(">BHH", 13, 1, 8)
_UHID_RELEASE_MESSAGE = _UHID_INPUT_PREFIX + bytes(8)


def make_wifi_uhid_send(sock: Any) -> Callable[[int, int], None]:
    """Build a submit_login-compatible send over an existing UHID socket.

    :param sock: Already-created scrcpy WiFi UHID control socket (``sendall``).
    """
    if sock is None or not callable(getattr(sock, "sendall", None)):
        raise WifiUhidError("wifi-uhid-socket-unavailable")

    def send(modifier: int, usage: int) -> None:
        if type(modifier) is not int or type(usage) is not int:
            raise WifiUhidError("wifi-uhid-combination-rejected")
        if not 0 <= modifier <= 255 or not 0 <= usage <= 255:
            raise WifiUhidError("wifi-uhid-combination-rejected")
        press = _UHID_INPUT_PREFIX + bytes((modifier, 0, usage, 0, 0, 0, 0, 0))
        try:
            try:
                sock.sendall(press)
                time.sleep(0.08)  # Match the existing scrcpy navigation helper.
            finally:
                sock.sendall(_UHID_RELEASE_MESSAGE)
                time.sleep(0.08)
        except Exception:
            raise WifiUhidError("wifi-uhid-transport-failed") from None

    return send
