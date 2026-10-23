#!/usr/bin/env python3
"""Private, single-attempt login controls for the functional PoC.

This module intentionally contains no ADB or USB implementation.  The runner
supplies the already-reviewed UI guard and the existing HID ``key`` method.
Keeping the credential and permit handling here makes it possible to exercise
the safety gates with fake devices without ever opening a device on the host.
"""

from __future__ import annotations

from dataclasses import dataclass
from io import BytesIO
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import struct
import time
from typing import Any, Callable
import zlib
from urllib.parse import quote, quote_plus

try:  # Pillow is used when present; the bounded fallback keeps the PoC dependency-free.
    from PIL import Image as PILImage
    from PIL import ImageDraw as PILImageDraw
except ModuleNotFoundError:  # pragma: no cover - exercised on the minimal host image
    PILImage = None
    PILImageDraw = None


MAX_FILE_BYTES = 4 * 1024
MAX_CREDENTIAL_CHARS = 128
PRIVATE_MODE = 0o600
SUPPORTED_TEXT_RE = re.compile(r"^[A-Za-z0-9@.]*$")
EMAIL_RE = re.compile(
    r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@"
    r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
    r"(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+"
)
REFERENCE_PATH = Path(
    "/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/"
    "functional-poc-20261012T124623761464Z-26fd4d2948/screenshots/launch-060.png"
)
# The reviewed image is displayed scaled by the read tool, but its actual
# screencap IHDR is 2460x1080. Convert reviewed display coordinates to pixels.
REFERENCE_SIZE = (2460, 1080)
REFERENCE_SHA256 = "6c775bf4756fa1617b543247a0eec4559252925c44b94f07363e8ee95c9be754"
def _native_rect(rect: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
    return tuple(round(value * (2460 / 2000 if index % 2 == 0 else 1080 / 878))
                 for index, value in enumerate(rect))

LOGIN_ROIS = {key: _native_rect(value) for key, value in {
    "username": (945, 426, 1140, 452),
    "password": (974, 505, 1105, 530),
    "login": (1000, 650, 1080, 679),
    "remember": (931, 596, 1176, 626),
}.items()}
MASK_RECTS = tuple(_native_rect(value) for value in ((886, 456, 1196, 494), (886, 533, 1196, 574), (0, 0, 2000, 82), (0, 840, 2000, 878)))
CARET_RANGES = {key: (round(value[0] * 2460 / 2000), round(value[1] * 2460 / 2000),
                       round(value[2] * 1080 / 878), round(value[3] * 1080 / 878)) for key, value in {
    "username": (891, 899, 461, 489),
    "password": (891, 899, 539, 568),
}.items()}
REMEMBER_INTERIOR = _native_rect((910, 609, 923, 620))
# Fixed non-secret artwork and field borders, not the animated world or input text.
STABLE_ROIS = tuple(_native_rect(rect) for rect in (
    (420, 99, 570, 209), (997, 761, 1084, 805),
    (889, 453, 1190, 458), (889, 569, 1190, 574),
))


class LoginInputError(RuntimeError):
    """Stable input failure that never includes secret-file contents."""


class LoginError(RuntimeError):
    """Stable, non-secret login control failure."""


@dataclass(frozen=True)
class Credentials:
    username: str
    password: str


class PixelImage:
    """Small RGB image seam used when Pillow is unavailable on the host."""

    def __init__(self, width: int, height: int, fill: tuple[int, int, int] = (0, 0, 0)) -> None:
        self.width = width
        self.height = height
        self.pixels = bytearray(bytes(fill) * (width * height))

    @property
    def size(self) -> tuple[int, int]:
        return self.width, self.height

    def copy(self) -> "PixelImage":
        result = PixelImage(self.width, self.height)
        result.pixels[:] = self.pixels
        return result

    def getpixel(self, point: tuple[int, int]) -> tuple[int, int, int]:
        x, y = point
        offset = (y * self.width + x) * 3
        return tuple(self.pixels[offset:offset + 3])  # type: ignore[return-value]

    def putpixel(self, point: tuple[int, int], value: tuple[int, int, int]) -> None:
        x, y = point
        offset = (y * self.width + x) * 3
        self.pixels[offset:offset + 3] = bytes(value)

    def fill_rect(self, left: int, top: int, right: int, bottom: int, value: tuple[int, int, int]) -> None:
        for y in range(top, bottom):
            start = (y * self.width + left) * 3
            self.pixels[start:(y * self.width + right) * 3] = bytes(value) * (right - left)


def _png_chunk(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff)


def _decode_png(data: bytes) -> PixelImage:
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise LoginError("login-frame-invalid")
    offset = 8
    width = height = color_type = bit_depth = interlace = None
    chunks: list[bytes] = []
    while offset + 12 <= len(data):
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        end = offset + 12 + length
        if end > len(data):
            raise LoginError("login-frame-invalid")
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + length]
        offset = end
        if kind == b"IHDR":
            width, height, bit_depth, color_type, _compression, _filter, interlace = struct.unpack(">IIBBBBB", payload)
        elif kind == b"IDAT":
            chunks.append(payload)
        elif kind == b"IEND":
            break
    if (
        width is None or height is None or (width, height) != REFERENCE_SIZE
        or bit_depth != 8 or color_type not in (2, 6) or interlace != 0
    ):
        raise LoginError("login-frame-format")
    channels = 3 if color_type == 2 else 4
    stride = width * channels
    try:
        encoded = zlib.decompress(b"".join(chunks))
    except zlib.error as exc:
        raise LoginError("login-frame-invalid") from exc
    if len(encoded) != height * (stride + 1):
        raise LoginError("login-frame-bounds")
    image = PixelImage(width, height)
    previous = bytearray(stride)
    cursor = 0
    for y in range(height):
        filter_type = encoded[cursor]
        cursor += 1
        source = encoded[cursor:cursor + stride]
        cursor += stride
        row = bytearray(stride)
        for index, value in enumerate(source):
            left = row[index - channels] if index >= channels else 0
            above = previous[index]
            upper_left = previous[index - channels] if index >= channels else 0
            if filter_type == 0:
                predictor = 0
            elif filter_type == 1:
                predictor = left
            elif filter_type == 2:
                predictor = above
            elif filter_type == 3:
                predictor = (left + above) // 2
            elif filter_type == 4:
                estimate = left + above - upper_left
                distances = (abs(estimate - left), abs(estimate - above), abs(estimate - upper_left))
                predictor = (left, above, upper_left)[distances.index(min(distances))]
            else:
                raise LoginError("login-frame-filter")
            row[index] = (value + predictor) & 0xff
        if channels == 3:
            start = y * width * 3
            image.pixels[start:start + width * 3] = row
        else:
            rgb = bytearray(width * 3)
            for x in range(width):
                source_base = x * 4
                target_base = x * 3
                rgb[target_base:target_base + 3] = row[source_base:source_base + 3]
            start = y * width * 3
            image.pixels[start:start + width * 3] = rgb
        previous = row
    return image


def _encode_png(image: PixelImage) -> bytes:
    raw = b"".join(b"\0" + bytes(image.pixels[y * image.width * 3:(y + 1) * image.width * 3]) for y in range(image.height))
    header = struct.pack(">IIBBBBB", image.width, image.height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + _png_chunk(b"IHDR", header) + _png_chunk(b"IDAT", zlib.compress(raw, 6)) + _png_chunk(b"IEND", b"")


def _reference_path_is_approved(path: Path) -> None:
    if path != REFERENCE_PATH:
        raise LoginError("login-reference-not-approved")
    if not _ancestors_are_real(path):
        raise LoginError("login-reference-symlink")
    try:
        info = os.stat(path, follow_symlinks=False)
    except OSError as exc:
        raise LoginError("login-reference-unavailable") from exc
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1:
        raise LoginError("login-reference-identity")
    if info.st_size <= 0 or info.st_size > MAX_FILE_BYTES * 2048:
        raise LoginError("login-reference-bounds")


def load_fixed_reference() -> Any:
    """Load the one reviewed empty login image; no caller-supplied path exists."""
    _reference_path_is_approved(REFERENCE_PATH)
    try:
        fd = os.open(REFERENCE_PATH, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
        try:
            info = os.fstat(fd)
            if info.st_nlink != 1 or info.st_size <= 0 or info.st_size > MAX_FILE_BYTES * 2048:
                raise LoginError("login-reference-identity")
            data = os.read(fd, info.st_size + 1)
        finally:
            os.close(fd)
        if len(data) > MAX_FILE_BYTES * 2048:
            raise LoginError("login-reference-bounds")
        if hashlib.sha256(data).hexdigest() != REFERENCE_SHA256:
            raise LoginError("login-reference-digest")
        image = _as_image(data)
    except Exception as exc:
        if isinstance(exc, LoginError):
            raise
        raise LoginError("login-reference-invalid") from exc
    if image.size != REFERENCE_SIZE:
        raise LoginError("login-reference-size")
    return image


def _yellow(pixel: tuple[int, int, int]) -> bool:
    red, green, blue = pixel
    return red >= 130 and green >= 90 and blue <= 140 and red > blue * 1.35 and green > blue * 1.15


def _yellow_mask(image: Any, roi: tuple[int, int, int, int]) -> set[tuple[int, int]]:
    left, top, right, bottom = roi
    return {
        (x, y)
        for y in range(top, bottom)
        for x in range(left, right)
        if _yellow(image.getpixel((x, y)))
    }


def _mask_iou(left: set[tuple[int, int]], right: set[tuple[int, int]]) -> float:
    union = left | right
    return len(left & right) / len(union) if union else 0.0


def _as_image(frame: bytes | Any) -> Any:
    if isinstance(frame, PixelImage):
        return frame.copy()
    if PILImage is not None and isinstance(frame, PILImage.Image):
        return frame.convert("RGB")
    if not isinstance(frame, (bytes, bytearray)):
        raise LoginError("login-frame-invalid")
    try:
        if PILImage is not None:
            with PILImage.open(BytesIO(frame)) as source:
                return source.convert("RGB")
        return _decode_png(bytes(frame))
    except Exception as exc:
        raise LoginError("login-frame-invalid") from exc


def frame_matches(frame: bytes | Any, reference: Any, focus: str | None) -> bool:
    """Check stable form labels plus the expected live caret, without storing frame data."""
    image = _as_image(frame)
    if image.size != REFERENCE_SIZE or reference.size != REFERENCE_SIZE:
        return False
    for roi in LOGIN_ROIS.values():
        if _mask_iou(_yellow_mask(image, roi), _yellow_mask(reference, roi)) < 0.75:
            return False
    if not _region_matches(image, reference, REMEMBER_INTERIOR, threshold=30, ratio=0.95):
        return False
    if not all(_region_matches(image, reference, roi) for roi in STABLE_ROIS):
        return False
    if focus is not None and not caret_present(image, focus):
        return False
    return True


def _region_matches(image: Any, reference: Any, roi: tuple[int, int, int, int],
                    *, threshold: int = 50, ratio: float = 0.9) -> bool:
    left, top, right, bottom = roi
    matched = 0
    total = 0
    for y in range(top, bottom, 2):
        for x in range(left, right, 2):
            total += 1
            if max(abs(a - b) for a, b in zip(image.getpixel((x, y)), reference.getpixel((x, y)))) <= threshold:
                matched += 1
    return total > 0 and matched / total >= ratio


def caret_present(image: Any, field: str) -> bool:
    try:
        x_start, x_end, y_start, y_end = CARET_RANGES[field]
    except KeyError as exc:
        raise LoginError("login-focus-field-invalid") from exc
    for x in range(x_start, x_end + 1):
        count = sum(
            all(channel > 155 for channel in image.getpixel((x, y)))
            for y in range(y_start, y_end + 1)
        )
        if count >= 8:
            return True
    return False


# Explicit 70450 login profile (Classic Forever ARM64, client 70450).
#
# Legacy 70009 constants above remain the default and are never reinterpreted
# as 70450.  This second profile is bound to its own approved opaque reference
# (field contents guaranteed opaque, masks contain ALL input pixels plus
# margin) by fixed path plus SHA256.  Masked pixels are never compared as
# stable anchors.  All thresholds stay strict (0.75 IoU, 0.90 stable).
REFERENCE_70450_PATH = Path(
    "/home/adrian/wowforever-artifacts/work/client123-resumed-20261021T125840Z/"
    "public-login-layout-fields-opaque.png"
)
REFERENCE_70450_SHA256 = "5c17e08c0be1a0372394c31de9e37d7f163add9be7dc1c91a1d2e7f2330f8ca7"
CLIENT_BUILD_70450 = "70450"
LOGIN_ROIS_70450: dict[str, tuple[int, int, int, int]] = {
    "username": (1140, 490, 1410, 525),
    "password": (1147, 602, 1357, 640),
    "login": (1224, 808, 1331, 847),
    "remember": (1127, 735, 1512, 776),
}
FIELD_INTERIORS_70450: dict[str, tuple[int, int, int, int]] = {
    "username": (1061, 539, 1500, 579),
    "password": (1061, 650, 1500, 695),
}
# Caret scan windows exclude field edges; the proven password caret lives at
# x1072..1073 with >=18 continuous white rows inside y648..696.
FIELD_SCAN_70450: dict[str, tuple[int, int, int, int]] = {
    "username": (1065, 543, 1496, 575),
    "password": (1065, 654, 1496, 691),
}
MASK_RECTS_70450 = ((950, 525, 1600, 605), (950, 640, 1600, 735))
STABLE_ROIS_70450 = (
    (290, 1008, 640, 1048),
    (940, 1028, 1490, 1050),
    (1135, 920, 1325, 998),
    (1105, 772, 1365, 786),
)
REMEMBER_BOX_70450 = (1093, 742, 1117, 772)
CARET_MIN_WHITE_70450 = 18
CARET_MAX_WIDTH_70450 = 4
_PHASE_TO_FOCUS_70450 = {
    "username": "username",
    "tab": "username",
    "password": "password",
    "submit": "password",
}


def load_70450_reference() -> Any:
    """Load the approved opaque 70450 reference; no caller-supplied path exists."""
    if REFERENCE_70450_PATH != Path(
        "/home/adrian/wowforever-artifacts/work/client123-resumed-20261021T125840Z/"
        "public-login-layout-fields-opaque.png"
    ):
        raise LoginError("login-reference-not-approved")
    if not _ancestors_are_real(REFERENCE_70450_PATH):
        raise LoginError("login-reference-symlink")
    try:
        info = os.stat(REFERENCE_70450_PATH, follow_symlinks=False)
    except OSError as exc:
        raise LoginError("login-reference-unavailable") from exc
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1:
        raise LoginError("login-reference-identity")
    if info.st_size <= 0 or info.st_size > MAX_FILE_BYTES * 2048:
        raise LoginError("login-reference-bounds")
    try:
        fd = os.open(REFERENCE_70450_PATH, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
        try:
            info = os.fstat(fd)
            if info.st_nlink != 1 or info.st_size <= 0 or info.st_size > MAX_FILE_BYTES * 2048:
                raise LoginError("login-reference-identity")
            data = os.read(fd, info.st_size + 1)
        finally:
            os.close(fd)
        if len(data) > MAX_FILE_BYTES * 2048:
            raise LoginError("login-reference-bounds")
        if hashlib.sha256(data).hexdigest() != REFERENCE_70450_SHA256:
            raise LoginError("login-reference-digest")
        image = _as_image(data)
    except Exception as exc:
        if isinstance(exc, LoginError):
            raise
        raise LoginError("login-reference-invalid") from exc
    if image.size != REFERENCE_SIZE:
        raise LoginError("login-reference-size")
    return image


def frame_matches_70450(frame: bytes | Any, reference: Any) -> bool:
    """Strict 70450 form check; never compares masked input pixels."""
    image = _as_image(frame)
    if image.size != REFERENCE_SIZE or reference.size != REFERENCE_SIZE:
        return False
    for roi in LOGIN_ROIS_70450.values():
        if _mask_iou(_yellow_mask(image, roi), _yellow_mask(reference, roi)) < 0.75:
            return False
    if not _region_matches(image, reference, REMEMBER_BOX_70450, threshold=30, ratio=0.95):
        return False
    if not all(_region_matches(image, reference, roi) for roi in STABLE_ROIS_70450):
        return False
    return True


def _thin_cols_70450(image: Any, field: str) -> list[int]:
    try:
        x_start, y_start, x_end, y_end = FIELD_SCAN_70450[field]
    except KeyError as exc:
        raise LoginError("login-focus-field-invalid") from exc
    found: list[int] = []
    for x in range(x_start, x_end):
        count = sum(
            all(channel > 155 for channel in image.getpixel((x, y)))
            for y in range(y_start, y_end)
        )
        if count >= CARET_MIN_WHITE_70450:
            found.append(x)
    return found


def _thin_groups_70450(columns: list[int]) -> list[tuple[int, int]]:
    groups: list[tuple[int, int]] = []
    if not columns:
        return groups
    start = previous = columns[0]
    for column in columns[1:]:
        if column == previous + 1:
            previous = column
            continue
        groups.append((start, previous))
        start = previous = column
    groups.append((start, previous))
    return [group for group in groups if group[1] - group[0] + 1 <= CARET_MAX_WIDTH_70450]


def verify_70450_blink(images: list[Any] | tuple[Any, ...], reference: Any, phase: str) -> None:
    """Require 2-4 frames: stable 70450 form plus one blinking caret column.

    A static glyph (always present) or an empty field (always absent) both
    fail: some thin column in the expected field must appear in at least one
    frame and disappear in another, while the other field shows no such
    appearance/disappearance.  This keeps typed text (static) from posing as
    focus and never reuses a stale initial caret.
    """
    try:
        expected = _PHASE_TO_FOCUS_70450[phase]
    except KeyError as exc:
        raise LoginError("login-phase-invalid") from exc
    if not isinstance(images, (list, tuple)) or not 2 <= len(images) <= 4:
        raise LoginError("login-focus-sample-bound")
    frames = [_as_image(frame) for frame in images]
    if any(frame.size != REFERENCE_SIZE for frame in frames):
        raise LoginError("login-form-geometry-not-confirmed")
    if not all(frame_matches_70450(frame, reference) for frame in frames):
        raise LoginError("login-form-geometry-not-confirmed")
    other = "password" if expected == "username" else "username"
    exp_sets = [set(_thin_cols_70450(frame, expected)) for frame in frames]
    oth_sets = [set(_thin_cols_70450(frame, other)) for frame in frames]
    # Thin groups wider than the caret limit never count, even if blinking.
    exp_thin = [col for cols in exp_sets for col in cols
                if any(start <= col <= end for start, end in _thin_groups_70450(sorted(cols)))]
    if not exp_thin:
        raise LoginError(
            "login-username-focus-not-confirmed"
            if expected == "username" else "login-password-focus-not-confirmed"
        )
    union_exp = set.union(*exp_sets) if exp_sets else set()
    inter_exp = set.intersection(*exp_sets) if exp_sets else set()
    blinking = {col for col in union_exp - inter_exp
                if any(start <= col <= end for start, end in _thin_groups_70450(sorted(union_exp)))}
    # The other field must show no blinking thin column at all.
    union_oth = set.union(*oth_sets) if oth_sets else set()
    inter_oth = set.intersection(*oth_sets) if oth_sets else set()
    other_blink = {col for col in union_oth - inter_oth
                   if any(start <= col <= end for start, end in _thin_groups_70450(sorted(union_oth)))}
    if not blinking:
        raise LoginError(
            "login-username-focus-not-confirmed"
            if expected == "username" else "login-password-focus-not-confirmed"
        )
    if other_blink:
        raise LoginError("login-focus-ambiguous")


class Tracker70450:
    """Phase-bound blink proof for the single authorized 70450 login."""

    def __init__(self, capture: Callable[[], tuple[Any, ...] | list[Any]], reference: Any) -> None:
        if not callable(capture):
            raise LoginError("login-guard-unavailable")
        self._capture = capture
        self._reference = reference
        self.current: str | None = None

    def _fresh(self) -> list[Any]:
        try:
            captured = self._capture()
        except Exception:
            raise LoginError("login-form-or-focus-not-confirmed") from None
        if not isinstance(captured, (list, tuple)) or not 2 <= len(captured) <= 4:
            raise LoginError("login-focus-sample-bound")
        try:
            return [_as_image(frame) for frame in captured]
        except Exception:
            raise LoginError("login-form-or-focus-not-confirmed") from None

    def phase_guard(self, phase: str) -> None:
        self.current = None
        if phase not in _PHASE_TO_FOCUS_70450:
            raise LoginError("login-phase-invalid")
        try:
            images = self._fresh()
        except LoginError:
            raise
        except Exception:
            raise LoginError("login-form-or-focus-not-confirmed") from None
        verify_70450_blink(images, self._reference, phase)
        self.current = phase

    def press_guard(self) -> bool:
        if self.current is None:
            return False
        try:
            verify_70450_blink(self._fresh(), self._reference, self.current)
        except Exception:
            return False
        return True


def poll_visual_guard(
    capture: Callable[[float], bytes | tuple[bytes, ...]],
    reference: Any,
    *,
    focus: str,
    deadline: float,
    local_seconds: float = 12.0,
    diagnostic: Callable[[dict[str, Any]], None] | None = None,
) -> None:
    end = min(deadline, time.monotonic() + local_seconds)
    while time.monotonic() < end:
        captured = capture(min(end, deadline))
        frames = captured if isinstance(captured, tuple) else (captured,)
        if not 1 <= len(frames) <= 3:
            raise LoginError("login-focus-sample-bound")
        images = [_as_image(frame) for frame in frames]
        forms_match = all(frame_matches(image, reference, None) for image in images)
        if diagnostic is not None:
            for image in images:
                diagnostic({
                    "sizeMatches": image.size == REFERENCE_SIZE,
                    "formMatches": frame_matches(image, reference, None),
                    "usernameCaret": caret_present(image, "username") if image.size == REFERENCE_SIZE else False,
                    "passwordCaret": caret_present(image, "password") if image.size == REFERENCE_SIZE else False,
                })
        if time.monotonic() >= end:
            break
        if forms_match and any(caret_present(image, focus) for image in images):
            return
        time.sleep(min(0.1, max(0.0, end - time.monotonic())))
    raise LoginError("login-form-or-focus-not-confirmed")


def masked_preview(frame: bytes | Any) -> bytes:
    """Return only the parent-review image; account/password interiors are black."""
    image = _as_image(frame)
    if image.size != REFERENCE_SIZE:
        raise LoginError("login-frame-size")
    if isinstance(image, PixelImage):
        for left, top, right, bottom in MASK_RECTS:
            image.fill_rect(left, top, right, bottom, (0, 0, 0))
        return _encode_png(image)
    draw = PILImageDraw.Draw(image)
    for left, top, right, bottom in MASK_RECTS:
        draw.rectangle((left, top, right - 1, bottom - 1), fill=(0, 0, 0))
    output = BytesIO()
    image.save(output, format="PNG", optimize=False)
    return output.getvalue()


def login_result_preview(frame: bytes | Any) -> bytes:
    """Opt-in test-account result: keep game/dialogs visible, mask system bars."""
    image = _as_image(frame)
    if image.size != REFERENCE_SIZE:
        raise LoginError("login-frame-size")
    if isinstance(image, PixelImage):
        for rectangle in MASK_RECTS[2:]:
            image.fill_rect(*rectangle, (0, 0, 0))
        return _encode_png(image)
    draw = PILImageDraw.Draw(image)
    for left, top, right, bottom in MASK_RECTS[2:]:
        draw.rectangle((left, top, right - 1, bottom - 1), fill=(0, 0, 0))
    output = BytesIO()
    image.save(output, format="PNG", optimize=False)
    return output.getvalue()


def _ancestors_are_real(path: Path) -> bool:
    current = path.expanduser()
    if not current.is_absolute():
        current = (Path.cwd() / current).absolute()
    # lstat is deliberate: resolve() would hide the symlink being rejected.
    for item in (current, *current.parents):
        try:
            if stat.S_ISLNK(os.lstat(item).st_mode):
                return False
        except OSError:
            # A permit file may not exist yet; its existing parents still must
            # be checked.  The caller performs the full check once it appears.
            if item == current:
                continue
            return False
    return True


def _under(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
    except ValueError:
        return False
    return True


def validate_private_path(
    path: Path,
    *,
    forbidden_roots: tuple[Path, ...] = (),
    require_file: bool = True,
) -> Path:
    """Validate a host-private path without reading its contents."""
    candidate = path.expanduser()
    if not candidate.is_absolute():
        candidate = (Path.cwd() / candidate).absolute()
    if not _ancestors_are_real(candidate):
        raise LoginInputError("private-file-symlink")
    try:
        resolved = candidate.resolve(strict=False)
    except OSError as exc:
        raise LoginInputError("private-file-path") from exc
    roots = tuple(root.expanduser().resolve(strict=False) for root in forbidden_roots)
    if any(_under(resolved, root) for root in roots):
        raise LoginInputError("private-file-location")
    if not require_file:
        return resolved
    try:
        info = os.stat(resolved, follow_symlinks=False)
    except OSError as exc:
        raise LoginInputError("private-file-unavailable") from exc
    if not stat.S_ISREG(info.st_mode):
        raise LoginInputError("private-file-not-regular")
    if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != PRIVATE_MODE:
        raise LoginInputError("private-file-permissions")
    if info.st_nlink != 1 or info.st_size > MAX_FILE_BYTES:
        raise LoginInputError("private-file-bounds")
    return resolved


def validate_permit_path(path: Path, *, forbidden_roots: tuple[Path, ...] = ()) -> Path:
    """Validate permit location now; its file may be created after launch."""
    return validate_private_path(path, forbidden_roots=forbidden_roots, require_file=False)


def _read_private_json(path: Path) -> Any:
    try:
        info_path = validate_private_path(path)
        fd = os.open(info_path, os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW)
        try:
            info = os.fstat(fd)
            if (
                not stat.S_ISREG(info.st_mode)
                or info.st_uid != os.getuid()
                or stat.S_IMODE(info.st_mode) != PRIVATE_MODE
                or info.st_nlink != 1
                or info.st_size > MAX_FILE_BYTES
            ):
                raise LoginInputError("private-file-permissions")
            data = os.read(fd, MAX_FILE_BYTES + 1)
        finally:
            os.close(fd)
        if len(data) > MAX_FILE_BYTES:
            raise LoginInputError("private-file-bounds")
        return json.loads(data.decode("utf-8"))
    except LoginInputError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError, TypeError, ValueError) as exc:
        raise LoginInputError("private-file-invalid") from exc


def _read_credentials_once_and_unlink(path: Path) -> Any:
    """Read one validated inode, unlink it immediately, then parse in memory."""
    private = validate_private_path(path)
    parent_fd = -1
    fd = -1
    try:
        parent_fd = os.open(private.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
        fd = os.open(private.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=parent_fd)
        info = os.fstat(fd)
        if (
            not stat.S_ISREG(info.st_mode)
            or info.st_uid != os.getuid()
            or stat.S_IMODE(info.st_mode) != PRIVATE_MODE
            or info.st_nlink != 1
            or info.st_size > MAX_FILE_BYTES
        ):
            raise LoginInputError("private-file-permissions")
        data = os.read(fd, MAX_FILE_BYTES + 1)
        if len(data) > MAX_FILE_BYTES:
            raise LoginInputError("private-file-bounds")
        current = os.stat(private.name, dir_fd=parent_fd, follow_symlinks=False)
        if (current.st_dev, current.st_ino) != (info.st_dev, info.st_ino):
            raise LoginInputError("credentials-file-identity")
        os.unlink(private.name, dir_fd=parent_fd)
    except LoginInputError:
        raise
    except (OSError, UnicodeError) as exc:
        raise LoginInputError("credentials-file-read") from exc
    finally:
        if fd >= 0:
            os.close(fd)
        if parent_fd >= 0:
            os.close(parent_fd)
    try:
        return json.loads(data.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError, TypeError, ValueError) as exc:
        raise LoginInputError("credentials-invalid") from exc


def _validate_text(value: Any) -> str:
    if not isinstance(value, str) or not value or len(value) > MAX_CREDENTIAL_CHARS:
        raise LoginInputError("credentials-invalid")
    try:
        value.encode("ascii")
    except UnicodeEncodeError as exc:
        raise LoginInputError("credentials-invalid") from exc
    if SUPPORTED_TEXT_RE.fullmatch(value) is None:
        raise LoginInputError("credentials-unsupported-character")
    return value


def load_credentials(path: Path, *, forbidden_roots: tuple[Path, ...] = ()) -> Credentials:
    """Read and fully validate credentials without exposing their values."""
    private = validate_private_path(path, forbidden_roots=forbidden_roots)
    value = _read_credentials_once_and_unlink(private)
    if not isinstance(value, dict) or set(value) != {"username", "password"}:
        raise LoginInputError("credentials-invalid")
    return Credentials(_validate_text(value.get("username")), _validate_text(value.get("password")))


def permit_is_confirmed(path: Path, nonce: str) -> bool:
    """Accept only the one confirmation bound to this run's nonce."""
    try:
        value = _read_private_json(path)
    except LoginInputError:
        return False
    return (
        isinstance(value, dict)
        and value.get("nonce") == nonce
        and value.get("loginScreenConfirmed") is True
    )


def wait_for_permit(path: Path, nonce: str, deadline: float, *, poll_seconds: float = 0.25) -> bool:
    while time.monotonic() < deadline:
        if permit_is_confirmed(path, nonce):
            return True
        time.sleep(min(poll_seconds, max(0.0, deadline - time.monotonic())))
    return False


def hid_usage(character: str) -> tuple[int, int]:
    """Return the exact modifier/usage pair for the deliberately tiny map."""
    if "a" <= character <= "z":
        return 0, ord(character) - ord("a") + 4
    if "A" <= character <= "Z":
        return 2, ord(character.lower()) - ord("a") + 4
    if "1" <= character <= "9":
        return 0, ord(character) - ord("1") + 30
    if character == "0":
        return 0, 39
    if character == "@":
        return 2, 31  # Shift+2 on the US HID layout.
    if character == ".":
        return 0, 55
    raise LoginError("unsupported-login-character")


def _validate_hid_text(value: str) -> tuple[tuple[int, int], ...]:
    try:
        return tuple(hid_usage(character) for character in value)
    except (TypeError, ValueError, LoginError) as exc:
        raise LoginError("unsupported-login-character") from exc


def submit_login(
    keyboard: Any,
    credentials: Credentials,
    *,
    guard: Callable[[], None] | None = None,
    phase_guard: Callable[[str], None] | None = None,
    send: Callable[[int, int], None],
    action: Callable[[str], None],
) -> None:
    """Type one login through fixed visual phases; no arbitrary UI selector exists."""
    username_keys = _validate_hid_text(credentials.username)
    password_keys = _validate_hid_text(credentials.password)
    del keyboard  # The runner's ``send`` owns the deadline/error translation.

    def phase(name: str) -> None:
        if phase_guard is not None:
            phase_guard(name)
        elif guard is not None:
            guard()
        else:
            raise LoginError("login-guard-unavailable")

    phase("username")
    send(1, 4)  # Ctrl+A: username field is visually confirmed by the parent.
    for modifier, usage in username_keys:
        send(modifier, usage)

    phase("tab")
    send(0, 43)  # Tab to password.
    action("login-tab-sent")

    phase("password")
    send(1, 4)  # Ctrl+A: clear any pre-existing password text.
    for modifier, usage in password_keys:
        send(modifier, usage)

    # Return the live password caret to its reviewed location. This changes only
    # cursor position; the final visual guard still precedes the single Enter.
    send(0, 74)  # Home
    phase("submit")
    action("login-submit-intent")
    send(0, 40)  # Exactly one Enter; no retry is permitted.


def make_sanitizer(credentials: Credentials) -> Callable[[str], str]:
    """Create a per-run textual sanitizer; unrelated runs have no state change."""
    forms = set()
    for value in (credentials.username, credentials.password):
        for normalized in (value, value.lower(), value.upper()):
            forms.update((normalized, quote(normalized, safe=""), quote_plus(normalized),
                          json.dumps(normalized, ensure_ascii=True)[1:-1]))
    literals = tuple(sorted((value for value in forms if value), key=len, reverse=True))

    def sanitize(value: str) -> str:
        for literal in literals:
            value = value.replace(literal, "[redacted]")
        return EMAIL_RE.sub("[redacted-email]", value)

    return sanitize


def sanitize_bytes(data: bytes, sanitizer: Callable[[str], str]) -> bytes:
    return sanitizer(data.decode("utf-8", "replace")).encode("utf-8")
