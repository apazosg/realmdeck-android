#!/usr/bin/env python3
"""Credential-free registration of the WoW login form.

The detector is deliberately a geometry gate, not an input driver.  It keeps
the approved reference and the live pixels in memory, and returns coordinates
only after several independent, non-secret anchors agree on one uniform
transform.  In particular, it never returns a permission to type.
"""

from __future__ import annotations

from array import array
from dataclasses import dataclass, field
import importlib.util
from pathlib import Path
import sys
from typing import Any, Iterable


_LOGIN_PATH = Path(__file__).with_name("functional_login.py")
_SPEC = importlib.util.spec_from_file_location("functional_login_geometry_reference", _LOGIN_PATH)
if _SPEC is None or _SPEC.loader is None:  # pragma: no cover - packaging failure
    raise RuntimeError("functional_login reference module unavailable")
login = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = login
_SPEC.loader.exec_module(login)


EXPECTED_CLIENT_BUILD = "70009"
REFERENCE_SHA256 = login.REFERENCE_SHA256
YELLOW_IOU_THRESHOLD = 0.75
STABLE_COLOR_THRESHOLD = 0.90
LABELS = ("username", "password", "login", "remember")
FIELD_INTERIOR_RECTS = {
    "username": login.MASK_RECTS[0],
    "password": login.MASK_RECTS[1],
}


Rect = tuple[int, int, int, int]


@dataclass(frozen=True)
class DetectorProfile:
    """Immutable detector identity and bounded search policy."""

    client_build: str = EXPECTED_CLIENT_BUILD
    reference_sha256: str = REFERENCE_SHA256
    scale_min: float = 0.60
    scale_max: float = 1.40
    content_aspect: float = 16 / 9


DEFAULT_PROFILE = DetectorProfile()


@dataclass(frozen=True)
class Transform:
    scale: float
    translate_x: float
    translate_y: float


@dataclass(frozen=True)
class GeometryResult:
    accepted: bool
    reason: str
    confidence: float
    transform: Transform | None = None
    label_ious: dict[str, float] = field(default_factory=dict)
    stable_ratios: tuple[float, ...] = ()
    anchor_rects: dict[str, Rect] = field(default_factory=dict)
    field_interiors: dict[str, Rect] = field(default_factory=dict)
    missing_anchors: tuple[str, ...] = ()
    plausible_candidates: int = 0
    focus_verified: bool = False
    typing_permitted: bool = False

    def as_dict(self) -> dict[str, object]:
        """Serialize non-secret geometry metrics, never pixels or input state."""
        return {
            "accepted": self.accepted,
            "reason": self.reason,
            "confidence": round(self.confidence, 6),
            "clientBuild": EXPECTED_CLIENT_BUILD,
            "referenceSha256": REFERENCE_SHA256,
            "transform": None if self.transform is None else {
                "scale": round(self.transform.scale, 6),
                "translateX": round(self.transform.translate_x, 3),
                "translateY": round(self.transform.translate_y, 3),
            },
            "labelIoU": {key: round(value, 6) for key, value in self.label_ious.items()},
            "stableColorRatios": [round(value, 6) for value in self.stable_ratios],
            "anchorRects": self.anchor_rects,
            "fieldInteriors": self.field_interiors,
            "missingAnchors": list(self.missing_anchors),
            "plausibleCandidates": self.plausible_candidates,
            "focusVerified": False,
            "typingPermitted": False,
        }


def load_pinned_reference(profile: DetectorProfile = DEFAULT_PROFILE) -> Any:
    """Load the one externally pinned reference through the existing verifier."""
    if profile.client_build != EXPECTED_CLIENT_BUILD or profile.reference_sha256 != REFERENCE_SHA256:
        raise ValueError("detector-profile-mismatch")
    return login.load_fixed_reference()


def _rect(image: Any, value: Rect, transform: Transform) -> Rect:
    left, top, right, bottom = value
    return (
        round(transform.scale * left + transform.translate_x),
        round(transform.scale * top + transform.translate_y),
        round(transform.scale * right + transform.translate_x),
        round(transform.scale * bottom + transform.translate_y),
    )


def _inside(rect: Rect, width: int, height: int) -> bool:
    left, top, right, bottom = rect
    return 0 <= left < right <= width and 0 <= top < bottom <= height


def _yellow(pixel: tuple[int, int, int]) -> bool:
    red, green, blue = pixel
    return red >= 130 and green >= 90 and blue <= 140 and red > blue * 1.35 and green > blue * 1.15


def _points(image: Any, roi: Rect, *, limit: int = 160) -> list[tuple[int, int]]:
    left, top, right, bottom = roi
    points = [
        (x, y)
        for y in range(max(0, top), min(image.size[1], bottom))
        for x in range(max(0, left), min(image.size[0], right))
        if _yellow(image.getpixel((x, y)))
    ]
    if len(points) <= limit:
        return points
    stride = max(1, len(points) // limit)
    return points[::stride][:limit]


def _yellow_area(image: Any, roi: Rect) -> int:
    left, top, right, bottom = roi
    return sum(
        _yellow(image.getpixel((x, y)))
        for y in range(max(0, top), min(image.size[1], bottom))
        for x in range(max(0, left), min(image.size[0], right))
    )


def _yellow_centroid(image: Any, roi: Rect) -> tuple[float, float] | None:
    left, top, right, bottom = roi
    points = [
        (x, y)
        for y in range(max(0, top), min(image.size[1], bottom))
        for x in range(max(0, left), min(image.size[0], right))
        if _yellow(image.getpixel((x, y)))
    ]
    if not points:
        return None
    return sum(x for x, _ in points) / len(points), sum(y for _, y in points) / len(points)


def _without_interiors(point: tuple[int, int]) -> bool:
    x, y = point
    for left, top, right, bottom in (*FIELD_INTERIOR_RECTS.values(), login.REMEMBER_INTERIOR):
        if left <= x < right and top <= y < bottom:
            # A thin perimeter is a border anchor, not field content.  This
            # retains the existing bottom-border ROI while excluding typed
            # interiors and the remember checkbox fill.
            if min(x - left, right - 1 - x, y - top, bottom - 1 - y) > 2:
                return False
    return True


def _stable_points(image: Any, roi: Rect, *, limit: int = 80) -> list[tuple[int, int]]:
    left, top, right, bottom = roi
    points = [
        (x, y)
        for y in range(max(0, top), min(image.size[1], bottom), 2)
        for x in range(max(0, left), min(image.size[0], right), 2)
        if _without_interiors((x, y))
    ]
    if len(points) <= limit:
        return points
    stride = max(1, len(points) // limit)
    return points[::stride][:limit]


class _YellowIndex:
    """Bounded integral mask; it is discarded with the live image."""

    def __init__(self, image: Any) -> None:
        self.width, self.height = image.size
        stride = self.width + 1
        self._prefix = array("I", [0]) * (stride * (self.height + 1))
        for y in range(self.height):
            row_total = 0
            row = (y + 1) * stride
            previous = y * stride
            for x in range(self.width):
                row_total += _yellow(image.getpixel((x, y)))
                self._prefix[row + x + 1] = self._prefix[previous + x + 1] + row_total

    def area(self, roi: Rect) -> int:
        left, top, right, bottom = roi
        left, top = max(0, left), max(0, top)
        right, bottom = min(self.width, right), min(self.height, bottom)
        if left >= right or top >= bottom:
            return 0
        stride = self.width + 1
        return (
            self._prefix[bottom * stride + right]
            - self._prefix[top * stride + right]
            - self._prefix[bottom * stride + left]
            + self._prefix[top * stride + left]
        )

    def is_yellow(self, x: int, y: int) -> bool:
        return 0 <= x < self.width and 0 <= y < self.height and self.area((x, y, x + 1, y + 1)) == 1


def _label_iou(source: list[tuple[int, int]], source_area: int, roi: Rect, transform: Transform, target: _YellowIndex) -> float:
    if not source:
        return 0.0
    mapped = {(round(transform.scale * x + transform.translate_x), round(transform.scale * y + transform.translate_y)) for x, y in source}
    sample_intersection = sum(target.is_yellow(x, y) for x, y in mapped)
    expected = max(1, round(source_area * transform.scale * transform.scale))
    intersection = min(expected, round(expected * sample_intersection / len(mapped)))
    actual = target.area(_rect(None, roi, transform))
    union = expected + actual - intersection
    return intersection / union if union else 0.0


def _stable_ratio(reference: Any, live: Any, points: Iterable[tuple[int, int]], transform: Transform) -> float:
    matched = total = 0
    width, height = live.size
    for x, y in points:
        tx, ty = round(transform.scale * x + transform.translate_x), round(transform.scale * y + transform.translate_y)
        if not (0 <= tx < width and 0 <= ty < height):
            continue
        total += 1
        if max(abs(a - b) for a, b in zip(reference.getpixel((x, y)), live.getpixel((tx, ty)))) <= 50:
            matched += 1
    return matched / total if total else 0.0


def _candidate_transforms(reference: Any, live: Any, profile: DetectorProfile) -> Iterable[Transform]:
    """Generate a fixed, aspect-preserving search around the guest content."""
    if profile.scale_min <= 0 or profile.scale_max < profile.scale_min:
        return ()
    ref_width, ref_height = reference.size
    live_width, live_height = live.size
    content_width = min(live_width, round(live_height * profile.content_aspect))
    content_left = (live_width - content_width) / 2
    ref_center_x, ref_center_y = ref_width / 2, ref_height / 2
    scales = {round(profile.scale_min + i * 0.04, 4) for i in range(64) if profile.scale_min + i * 0.04 <= profile.scale_max}
    scales.add(round(profile.scale_max, 4))
    for scale in sorted(scales):
        center_x = content_left + content_width / 2 - scale * ref_center_x
        center_y = live_height / 2 - scale * ref_center_y
        for dx in range(-420, 421, 20):
            for dy in range(-180, 181, 20):
                yield Transform(scale, center_x + dx, center_y + dy)


def _guest_content_rect(image: Any, aspect: float) -> Rect:
    width, height = image.size
    content_width = min(width, round(height * aspect))
    left = (width - content_width) // 2
    return left, 0, left + content_width, height


def _score(reference: Any, live: Any, source_labels: dict[str, tuple[list[tuple[int, int]], int]], target: _YellowIndex, candidate: Transform) -> tuple[float, dict[str, float]]:
    ious = {
        key: _label_iou(source_labels[key][0], source_labels[key][1], login.LOGIN_ROIS[key], candidate, target)
        for key in LABELS
    }
    return sum(ious.values()) / len(ious), ious


def register_login_form(
    reference: Any,
    live: Any,
    *,
    profile: DetectorProfile = DEFAULT_PROFILE,
) -> GeometryResult:
    """Register one live frame, failing closed for every uncertain shape."""
    if profile.client_build != EXPECTED_CLIENT_BUILD or not profile.reference_sha256:
        return GeometryResult(False, "detector-profile-mismatch", 0.0)
    if not hasattr(reference, "size") or not hasattr(live, "size") or reference.size != login.REFERENCE_SIZE:
        return GeometryResult(False, "reference-size-mismatch", 0.0)
    if live.size[0] < 640 or live.size[1] < 360:
        return GeometryResult(False, "live-size-invalid", 0.0)
    source_labels = {
        key: (_points(reference, login.LOGIN_ROIS[key]), _yellow_area(reference, login.LOGIN_ROIS[key]))
        for key in LABELS
    }
    missing = tuple(key for key, (points, area) in source_labels.items() if not points or not area)
    if missing:
        return GeometryResult(False, "missing-reference-anchor", 0.0, missing_anchors=missing)

    target = _YellowIndex(live)
    # Keep only a small beam of candidates; all four labels participate in it.
    beam: list[tuple[float, Transform]] = []
    for candidate in _candidate_transforms(reference, live, profile):
        score, _ = _score(reference, live, source_labels, target, candidate)
        beam.append((score, candidate))
        beam.sort(key=lambda item: item[0], reverse=True)
        if len(beam) > 8:
            beam.pop()

    refined: list[tuple[float, Transform, dict[str, float], tuple[float, ...]]] = []
    for coarse_score, coarse in beam:
        for scale_step in range(-2, 3):
            scale = round(coarse.scale + scale_step * 0.01, 4)
            if not profile.scale_min <= scale <= profile.scale_max:
                continue
            for dx in range(-12, 13, 4):
                for dy in range(-12, 13, 4):
                    candidate = Transform(scale, coarse.translate_x + dx, coarse.translate_y + dy)
                    score, ious = _score(reference, live, source_labels, target, candidate)
                    stable = tuple(
                        _stable_ratio(reference, live, _stable_points(reference, roi), candidate)
                        for roi in login.STABLE_ROIS
                    )
                    refined.append((score, candidate, ious, stable))
    plausible: list[tuple[float, Transform, dict[str, float], tuple[float, ...]]] = []
    for item in sorted(refined, key=lambda value: value[0], reverse=True):
        _, transform, ious, stable = item
        if min(ious.values()) < YELLOW_IOU_THRESHOLD or min(stable) < STABLE_COLOR_THRESHOLD:
            continue
        if any(
            abs(transform.scale - old[1].scale) <= 0.025
            and abs(transform.translate_x - old[1].translate_x) <= 14
            and abs(transform.translate_y - old[1].translate_y) <= 14
            for old in plausible
        ):
            continue
        plausible.append(item)
    if not plausible:
        return GeometryResult(False, "missing-or-unstable-form-anchors", 0.0)
    if len(plausible) > 1:
        return GeometryResult(False, "ambiguous-alignment", 0.0, plausible_candidates=len(plausible))

    score, transform, ious, stable = plausible[0]
    # IoU alone can accept a uniformly coloured rectangle after a non-uniform
    # warp.  A centroid check across all four independent labels verifies the
    # same single transform, without learning any credential text.
    for key in LABELS:
        source_centroid = _yellow_centroid(reference, login.LOGIN_ROIS[key])
        live_centroid = _yellow_centroid(live, _rect(live, login.LOGIN_ROIS[key], transform))
        if source_centroid is None or live_centroid is None:
            return GeometryResult(False, "missing-or-unstable-form-anchors", 0.0)
        expected_centroid = (
            transform.scale * source_centroid[0] + transform.translate_x,
            transform.scale * source_centroid[1] + transform.translate_y,
        )
        if max(abs(expected_centroid[0] - live_centroid[0]), abs(expected_centroid[1] - live_centroid[1])) > 8.0:
            return GeometryResult(False, "nonuniform-scale", 0.0)
    anchor_rects = {key: _rect(live, login.LOGIN_ROIS[key], transform) for key in LABELS}
    field_interiors = {key: _rect(live, roi, transform) for key, roi in FIELD_INTERIOR_RECTS.items()}
    guest_left, guest_top, guest_right, guest_bottom = _guest_content_rect(live, profile.content_aspect)
    if not all(
        guest_left <= rect[0] < rect[2] <= guest_right and guest_top <= rect[1] < rect[3] <= guest_bottom
        for rect in (*anchor_rects.values(), *field_interiors.values())
    ):
        return GeometryResult(False, "guest-content-constraint-failed", 0.0)
    confidence = min(min(ious.values()) / YELLOW_IOU_THRESHOLD, min(stable) / STABLE_COLOR_THRESHOLD, 1.0)
    return GeometryResult(
        True,
        "form-geometry-confirmed-focus-unverified",
        confidence,
        transform,
        ious,
        stable,
        anchor_rects,
        field_interiors,
        plausible_candidates=1,
    )


# Short alias for callers that use detector terminology.
detect_login_geometry = register_login_form
