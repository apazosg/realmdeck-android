from __future__ import annotations

import importlib.util
from pathlib import Path
import sys


ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "scripts/diagnostics/android-process/functional_login_geometry.py"
SPEC = importlib.util.spec_from_file_location("functional_login_geometry_test_subject", SCRIPT)
assert SPEC and SPEC.loader
geometry = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = geometry
SPEC.loader.exec_module(geometry)


def _paint(image, rect, colour, transform=(1.0, 0.0, 0.0)):
    scale, dx, dy = transform
    left, top, right, bottom = rect
    left, top = round(scale * left + dx), round(scale * top + dy)
    right, bottom = round(scale * right + dx), round(scale * bottom + dy)
    if hasattr(image, "fill_rect"):
        image.fill_rect(left, top, right, bottom, colour)


def _fixture(transform=(1.0, 0.0, 0.0), *, duplicate=None):
    reference = geometry.login.PixelImage(*geometry.login.REFERENCE_SIZE, fill=(20, 20, 20))
    live = geometry.login.PixelImage(*geometry.login.REFERENCE_SIZE, fill=(20, 20, 20))
    for rect in geometry.login.LOGIN_ROIS.values():
        _paint(reference, rect, (255, 220, 0))
        _paint(live, rect, (255, 220, 0), transform)
    for rect in geometry.login.STABLE_ROIS:
        _paint(reference, rect, (92, 63, 41))
        _paint(live, rect, (92, 63, 41), transform)
    if duplicate is not None:
        for rect in geometry.login.LOGIN_ROIS.values():
            _paint(live, rect, (255, 220, 0), duplicate)
        for rect in geometry.login.STABLE_ROIS:
            _paint(live, rect, (92, 63, 41), duplicate)
    return reference, live


def _profile(**values):
    return geometry.DetectorProfile(reference_sha256="synthetic", **values)


def test_translation_and_uniform_scale_register_all_independent_anchors() -> None:
    reference, live = _fixture((0.82, 230.0, 95.0))
    result = geometry.register_login_form(reference, live, profile=_profile(scale_min=.80, scale_max=.84))
    assert result.accepted
    assert result.transform is not None
    assert abs(result.transform.scale - .82) <= .02
    assert result.focus_verified is False
    assert result.typing_permitted is False
    assert set(result.field_interiors) == {"username", "password"}
    assert min(result.label_ious.values()) >= geometry.YELLOW_IOU_THRESHOLD
    assert min(result.stable_ratios) >= geometry.STABLE_COLOR_THRESHOLD


def test_wrong_aspect_is_rejected_by_uniform_registration() -> None:
    reference, live = _fixture((0.82, 230.0, 95.0))
    # Stretching only the vertical coordinate cannot be explained by one scale.
    warped = geometry.login.PixelImage(*geometry.login.REFERENCE_SIZE, fill=(20, 20, 20))
    for rect in geometry.login.LOGIN_ROIS.values():
        left, top, right, bottom = rect
        warped.fill_rect(round(.82 * left + 230), round(.90 * top + 50), round(.82 * right + 230), round(.90 * bottom + 50), (255, 220, 0))
    for rect in geometry.login.STABLE_ROIS:
        left, top, right, bottom = rect
        warped.fill_rect(round(.82 * left + 230), round(.90 * top + 50), round(.82 * right + 230), round(.90 * bottom + 50), (92, 63, 41))
    result = geometry.register_login_form(reference, warped, profile=_profile(scale_min=.80, scale_max=.84))
    assert not result.accepted


def test_modal_overlay_and_missing_form_are_fail_closed() -> None:
    reference, live = _fixture()
    live.fill_rect(geometry.login.LOGIN_ROIS["username"][0] - 30, 380, geometry.login.LOGIN_ROIS["login"][2] + 30, 710, (42, 42, 42))
    result = geometry.register_login_form(reference, live, profile=_profile(scale_min=1.0, scale_max=1.0))
    assert not result.accepted
    black = geometry.login.PixelImage(*geometry.login.REFERENCE_SIZE, fill=(0, 0, 0))
    result = geometry.register_login_form(reference, black, profile=_profile(scale_min=1.0, scale_max=1.0))
    assert not result.accepted
    assert result.reason in {"missing-or-unstable-form-anchors", "ambiguous-alignment"}


def test_duplicate_forms_are_ambiguous_not_autoselected() -> None:
    reference, live = _fixture((1.0, 0.0, 0.0), duplicate=(1.0, -260.0, 0.0))
    result = geometry.register_login_form(reference, live, profile=_profile(scale_min=1.0, scale_max=1.0))
    assert not result.accepted
    assert result.reason == "ambiguous-alignment"


def test_black_or_background_motion_never_becomes_positive() -> None:
    reference, _ = _fixture()
    for colour in ((0, 0, 0), (21, 22, 24)):
        live = geometry.login.PixelImage(*geometry.login.REFERENCE_SIZE, fill=colour)
        result = geometry.register_login_form(reference, live, profile=_profile(scale_min=1.0, scale_max=1.0))
        assert not result.accepted


def test_profile_is_immutable_and_public_result_has_no_input_permission() -> None:
    profile = geometry.DetectorProfile()
    try:
        profile.client_build = "other"  # type: ignore[misc]
    except Exception:
        pass
    else:
        raise AssertionError("detector profile must be immutable")
    assert profile.client_build == "70009"
    result = geometry.GeometryResult(False, "no-form", 0.0)
    assert result.as_dict()["typingPermitted"] is False
