#!/usr/bin/env python3
"""HOST-ONLY WiFi UHID send adapter for the already-authorized login PoC.

This module creates no transport of its own: no ADB, no USB/AOA, no scrcpy
client or server process, no new keyboard device, and no login framework.
The caller passes the *already-created* official scrcpy WiFi UHID control
socket (the one ``WifiUhidKeyboard`` opened in the external probe) together
with the *already-reviewed* native-target/focus guard. The returned
``send(modifier, usage)`` callback is directly compatible with
``functional_login.submit_login``.

Framing (copied exactly from the external probe, not guessed)::

    struct.pack('>BHH', 13, 1, len(report)) + report

``13`` is the scrcpy control message type for UHID input, ``1`` is the
uint16-BE UHID device id used at creation, and ``report`` is the 8-byte boot
keyboard report ``bytes((modifier, 0, usage, 0, 0, 0, 0, 0))`` followed by
the all-zero release ``bytes(8)``. The probe's own ``key()`` hard-codes
modifier ``0``; the generalization to the login's modifiers here is bounded
by the allowlist below, and the release report is always sent.

Caller obligations (the adapter alone authorizes nothing):

* ``guard`` is required, keyword-only, returns ``bool``, and has no
  default. It must be the caller's existing owned-game identity plus
  phase/focus proof (for example the runner's own-hierarchy dump combined
  with ``poll_visual_guard`` for the current field), checked fresh before
  every press. Only an explicit ``True`` authorizes a press: ``False``,
  ``None``, ``0``, truthy non-bool values, and exceptions all deny it and
  nothing is sent (fail closed).
* An older assertion-style phase guard (returns ``None``, raises on failure)
  must be wrapped to return ``True`` only AFTER all current checks pass.
  The caller must track the current phase and refresh its target/focus proof
  for each press: a fixed username-field proof must never authorize password,
  Tab, or Enter presses. This adapter tracks neither fields nor phases.
  A permissive wrapper that returns ``True`` without checks is unacceptable.
* Geometry, screenshots, or a remembered confirmation never authorize
  input; only the fresh per-press guard does. This adapter performs no
  blind typing on its own.

Safety properties:

* Only the login HID combinations and public-probe cleanup are accepted:
  the ``hid_usage`` mapping for ``A-Za-z0-9@.`` (reused from the existing
  module, never duplicated here) plus the four fixed controls Ctrl+A
  ``(1, 4)``, Tab ``(0, 43)``, Home ``(0, 74)``, and Enter ``(0, 40)``,
  plus Backspace ``(0, 42)`` for guarded cleanup of known public probe text.
  GUI/Alt modifiers and every other usage are rejected. The caller must not
  assume Ctrl+A selects text; prove empty fields before entering credentials.
* The release report is always sent after a press attempt, mirroring the
  existing USB ``key()`` finally-release behaviour. Each report is held for
  80 ms, matching the pinned WiFi navigation helper.
* Failures carry only stable codes (``wifi-uhid-*``). Guard and socket
  exceptions are translated with ``from None`` so no caller exception
  text, modifier, usage, key sequence, credential, or transport detail
  ever surfaces in messages or traceback chains.
"""

from __future__ import annotations

import importlib.util
import struct
import sys
import time
from pathlib import Path
from typing import Any, Callable


class WifiUhidError(RuntimeError):
    """Stable, non-secret WiFi UHID adapter failure."""


class WifiUhidGuardError(WifiUhidError):
    """The caller-supplied native-target/focus guard denied the press."""


def _load_sibling_login() -> Any:
    """Load the sibling functional_login module from its known path."""
    path = Path(__file__).with_name("functional_login.py")
    spec = importlib.util.spec_from_file_location(
        "wowforever_functional_login_peer", path
    )
    if spec is None or spec.loader is None:
        raise WifiUhidError("wifi-uhid-login-mapping-unavailable")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    try:
        spec.loader.exec_module(module)
    except Exception:
        raise WifiUhidError("wifi-uhid-login-mapping-unavailable") from None
    return module


try:
    from functional_login import LoginError as _LoginError
    from functional_login import hid_usage as _hid_usage
except ImportError:
    _peer = _load_sibling_login()
    _LoginError = _peer.LoginError
    _hid_usage = _peer.hid_usage


_UHID_INPUT_PREFIX = struct.pack(">BHH", 13, 1, 8)
_UHID_RELEASE_MESSAGE = _UHID_INPUT_PREFIX + bytes(8)

# Login controls plus guarded Backspace for clearing public keyboard-probe text.
_FIXED_CONTROLS = frozenset({(1, 4), (0, 43), (0, 74), (0, 40), (0, 42)})


def _build_allowed() -> frozenset[tuple[int, int]]:
    """Permitted login/public-probe pairs, without duplicating the key map."""
    pairs = set(_FIXED_CONTROLS)
    for code in range(128):
        try:
            pairs.add(_hid_usage(chr(code)))
        except _LoginError:
            continue
    return frozenset(pairs)


_ALLOWED = _build_allowed()


def make_wifi_uhid_send(
    sock: Any, *, guard: Callable[[], bool]
) -> Callable[[int, int], None]:
    """Build a submit_login-compatible send over an existing UHID socket.

    :param sock: Already-created scrcpy WiFi UHID control socket (``sendall``).
    :param guard: Required caller-owned native-target/focus proof returning
        ``True``. Checked before every press; anything other than an
        explicit ``True`` (or an exception) denies the press.
    """
    if sock is None or not callable(getattr(sock, "sendall", None)):
        raise WifiUhidError("wifi-uhid-socket-unavailable")
    if not callable(guard):
        raise WifiUhidError("wifi-uhid-guard-required")

    def send(modifier: int, usage: int) -> None:
        if type(modifier) is not int or type(usage) is not int:
            raise WifiUhidError("wifi-uhid-combination-rejected")
        if not 0 <= modifier <= 255 or not 0 <= usage <= 255:
            raise WifiUhidError("wifi-uhid-combination-rejected")
        if (modifier, usage) not in _ALLOWED:
            raise WifiUhidError("wifi-uhid-combination-rejected")
        try:
            verdict = guard()
        except Exception:
            raise WifiUhidGuardError("wifi-uhid-guard-denied") from None
        if verdict is not True:
            raise WifiUhidGuardError("wifi-uhid-guard-denied")
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
