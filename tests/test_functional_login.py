from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys
import time

import pytest


ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "scripts/diagnostics/android-process/functional_login.py"
SPEC = importlib.util.spec_from_file_location("functional_login_test_subject", SCRIPT)
assert SPEC and SPEC.loader
login = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = login
SPEC.loader.exec_module(login)

RUNNER_SCRIPT = ROOT / "scripts/diagnostics/android-process/run_functional_poc.py"
RUNNER_SPEC = importlib.util.spec_from_file_location("functional_login_runner_subject", RUNNER_SCRIPT)
assert RUNNER_SPEC and RUNNER_SPEC.loader
runner = importlib.util.module_from_spec(RUNNER_SPEC)
sys.modules[RUNNER_SPEC.name] = runner
RUNNER_SPEC.loader.exec_module(runner)


def _private_json(path: Path, value: object) -> Path:
    path.write_text(json.dumps(value), encoding="utf-8")
    path.chmod(0o600)
    return path


def test_credentials_require_private_external_file_and_supported_ascii(tmp_path: Path) -> None:
    credentials = _private_json(
        tmp_path / "credentials.json",
        {"username": "Test9@Example.com", "password": "P.2@abc"},
    )
    loaded = login.load_credentials(credentials, forbidden_roots=(tmp_path / "other",))
    assert loaded.username == "Test9@Example.com"
    assert not credentials.exists()
    assert login.hid_usage("A") == (2, 4)
    assert login.hid_usage("0") == (0, 39)

    bad = _private_json(tmp_path / "bad.json", {"username": "no-space", "password": "ok"})
    with pytest.raises(login.LoginInputError, match="unsupported-character"):
        login.load_credentials(bad)
    permissions = _private_json(tmp_path / "permissions.json", {"username": "ok", "password": "ok"})
    permissions.chmod(0o644)
    with pytest.raises(login.LoginInputError, match="permissions"):
        login.load_credentials(permissions)


def test_invalid_secret_json_is_unlinked_after_read(tmp_path: Path) -> None:
    invalid = _private_json(tmp_path / "invalid.json", {"username": "bad space", "password": "ok"})
    with pytest.raises(login.LoginInputError, match="unsupported-character"):
        login.load_credentials(invalid)
    assert not invalid.exists()


def test_permit_wrong_nonce_times_out_without_a_login_key() -> None:
    permit = Path("/tmp") / f"wowforever-test-permit-{time.time_ns()}.json"
    try:
        _private_json(permit, {"nonce": "other", "loginScreenConfirmed": True})
        assert not login.wait_for_permit(permit, "expected", time.monotonic() + 0.01, poll_seconds=0)
    finally:
        permit.unlink(missing_ok=True)


def test_submit_login_guards_each_boundary_and_sends_exactly_one_enter() -> None:
    credentials = login.Credentials("Ab2@c.", "Z9@p")
    guards: list[str] = []
    keys: list[tuple[int, int]] = []
    actions: list[str] = []

    def guard() -> None:
        guards.append("own")

    def send(modifier: int, usage: int) -> None:
        keys.append((modifier, usage))

    login.submit_login(None, credentials, guard=guard, send=send, action=actions.append)

    assert len(guards) == 4
    assert keys.count((0, 40)) == 1
    assert actions == ["login-tab-sent", "login-submit-intent"]
    assert keys[-1] == (0, 40)
    assert keys[-2] == (0, 74)

    blocked: list[tuple[int, int]] = []

    def foreign_ui() -> None:
        raise login.LoginError("foreign-ui")

    with pytest.raises(login.LoginError):
        login.submit_login(
            None, credentials, guard=foreign_ui, send=lambda m, u: blocked.append((m, u)), action=actions.append,
        )
    assert blocked == []


def test_per_run_sanitizer_redacts_literals_and_email_tokens_without_global_state() -> None:
    credentials = login.Credentials("user@example.test", "Secret9")
    sanitizer = login.make_sanitizer(credentials)
    text = "user@example.test Secret9 another.person@example.org auth.gpon.com.co"
    safe = sanitizer(text)
    assert "user@example.test" not in safe
    assert "Secret9" not in safe
    assert "another.person@example.org" not in safe
    assert "auth.gpon.com.co" in safe
    assert login.sanitize_bytes(text.encode(), sanitizer).decode() == safe
    for encoded in ("user%40example.test", "USER%40EXAMPLE.TEST", "secret9", "SECRET9"):
        assert encoded not in sanitizer(encoded)


def _visual_fixture(with_caret: str | None = None):
    image = login.PixelImage(*login.REFERENCE_SIZE, fill=(20, 20, 20))
    for left, top, right, bottom in login.LOGIN_ROIS.values():
        image.fill_rect(left + 4, top + 4, right - 4, bottom - 4, (255, 220, 0))
    if with_caret is not None:
        x_start, _x_end, y_start, y_end = login.CARET_RANGES[with_caret]
        for y in range(y_start, y_end):
            image.putpixel((x_start + 2, y), (255, 255, 255))
    return image


def test_fixed_visual_guard_positive_mismatch_and_blinking_focus() -> None:
    reference = _visual_fixture()
    positive = _visual_fixture("username")
    assert login.frame_matches(positive, reference, "username")

    modal = _visual_fixture("username")
    modal.fill_rect(*login.LOGIN_ROIS["username"], (20, 20, 20))
    assert not login.frame_matches(modal, reference, "username")
    assert not login.frame_matches(reference, reference, "username")
    assert login.frame_matches(_visual_fixture("password"), reference, "password")

    checked = _visual_fixture("username")
    checked.fill_rect(*login.REMEMBER_INTERIOR, (255, 230, 200))
    assert not login.frame_matches(checked, reference, "username")
    altered_art = _visual_fixture("username")
    altered_art.fill_rect(*login.STABLE_ROIS[0], (240, 240, 240))
    assert not login.frame_matches(altered_art, reference, "username")


def test_masked_preview_never_persists_raw_login_interiors() -> None:
    raw = _visual_fixture("username")
    raw.fill_rect(*login.MASK_RECTS[0], (12, 34, 56))
    masked = login.masked_preview(raw)
    output = login._as_image(masked)
    left, top, _, _ = login.MASK_RECTS[0]
    assert output.getpixel((left + 10, top + 10)) == (0, 0, 0)
    assert output.getpixel((10, 10)) == (0, 0, 0)
    assert output.getpixel((10, login.MASK_RECTS[2][3] + 10)) == (20, 20, 20)


def test_opt_in_result_keeps_test_account_dialogs_but_hides_system_bars() -> None:
    raw = _visual_fixture("username")
    raw.fill_rect(*login.MASK_RECTS[0], (12, 34, 56))
    output = login._as_image(login.login_result_preview(raw))
    left, top, _, _ = login.MASK_RECTS[0]
    assert output.getpixel((left + 10, top + 10)) == (12, 34, 56)
    assert output.getpixel((10, 10)) == (0, 0, 0)
    assert output.getpixel((10, login.MASK_RECTS[3][1] + 1)) == (0, 0, 0)
    with pytest.raises(login.LoginError, match="size"):
        login.login_result_preview(login.PixelImage(10, 10))


def test_result_capture_requires_login_and_is_off_by_default(tmp_path: Path) -> None:
    with pytest.raises(runner.PocError, match="authorized login"):
        runner.run_trial(tmp_path, 180, capture_login_result=True)
    import inspect
    assert inspect.signature(runner.run_trial).parameters["capture_login_result"].default is False


def test_focus_burst_accepts_blink_but_not_changed_form_or_late_capture(monkeypatch) -> None:
    reference = _visual_fixture()
    hidden = login._encode_png(reference)
    visible = login._encode_png(_visual_fixture("password"))
    login.poll_visual_guard(lambda _: (hidden, visible, hidden), reference,
                            focus="password", deadline=time.monotonic() + 20)
    changed = _visual_fixture("password")
    changed.fill_rect(*login.REMEMBER_INTERIOR, (255, 230, 200))
    now = [0.0]
    monkeypatch.setattr(login.time, "monotonic", lambda: now[0])
    def late(_):
        now[0] = 13.0
        return (visible,)
    with pytest.raises(login.LoginError):
        login.poll_visual_guard(late, reference, focus="password", deadline=20)
    now[0] = 0.0
    def mixed(_):
        now[0] += 6.0
        return (visible, login._encode_png(changed))
    with pytest.raises(login.LoginError):
        login.poll_visual_guard(mixed, reference, focus="password", deadline=20)


def test_prepared_metadata_matches_actual_producer_schema_only() -> None:
    prepared = json.loads(
        '{"timestampMs":1,"event":"prepared","route":"functional-poc",'
        '"profileId":"classic_forever","portalHost":"auth.gpon.com.co",'
        '"memoryPatchEnabled":true,"patchContractId":"forever-v1"}'
    )
    assert runner._prepared_record_has_forever_metadata(prepared)
    assert not runner._prepared_record_has_forever_metadata({**prepared, "profileId": "original"})
    assert not runner._prepared_record_has_forever_metadata({**prepared, "memoryPatchEnabled": False})
    assert not runner._prepared_record_has_forever_metadata({**prepared, "event": "launch"})


def test_known_screen_confirmation_does_not_bypass_credentials_or_profile(tmp_path: Path) -> None:
    with pytest.raises(runner.PocError, match="credentials"):
        runner.run_trial(tmp_path, 180, confirm_known_login_screen=True)
    with pytest.raises(runner.PocError, match="mutually exclusive"):
        runner.run_trial(tmp_path, 180, login_credentials_file=tmp_path / "not-read.json",
                         login_permit_file=tmp_path / "not-read-permit.json", confirm_known_login_screen=True)
    with pytest.raises(runner.PocError, match="classic_forever"):
        runner.run_trial(tmp_path, 180, login_credentials_file=tmp_path / "not-read.json",
                         confirm_known_login_screen=True)


def test_initial_readiness_retries_reads_only_before_any_form_input(monkeypatch) -> None:
    sequence = iter((runner.PocError("ui-dump-failed"), object()))
    events = []
    def dump(*_):
        value = next(sequence)
        if isinstance(value, Exception):
            raise value
        return value
    monkeypatch.setattr(runner, "_dump", dump)
    monkeypatch.setattr(runner, "_own_state", lambda _: None)
    monkeypatch.setattr(runner.time, "sleep", lambda _: None)
    class Actions:
        def add(self, name, **values):
            events.append(name)
    runner._wait_initial_ui(None, None, time.monotonic() + 30, time.monotonic() + 50, Actions())
    assert events == ["initial-ui-wait", "initial-ui-ready"]


def _fixture_70450(field: str | None = None, *, caret_x: int | None = None):
    image = login.PixelImage(*login.REFERENCE_SIZE, fill=(20, 20, 20))
    for left, top, right, bottom in login.LOGIN_ROIS_70450.values():
        image.fill_rect(left + 2, top + 2, right - 2, bottom - 2, (255, 220, 0))
    for left, top, right, bottom in login.STABLE_ROIS_70450:
        image.fill_rect(left, top, right, bottom, (92, 63, 41))
    image.fill_rect(*login.REMEMBER_BOX_70450, (40, 40, 40))
    if field is not None:
        x_start, y_start, x_end, y_end = login.FIELD_SCAN_70450[field]
        chosen = x_start + 7 if caret_x is None else caret_x
        assert x_start <= chosen < x_end
        for y in range(y_start, y_end):
            image.putpixel((chosen, y), (255, 255, 255))
            if field == "password" and chosen + 1 < x_end:
                image.putpixel((chosen + 1, y), (255, 255, 255))
    return image


def test_70450_form_rejects_wrong_profile_and_masked_pixels() -> None:
    reference = _fixture_70450()
    assert login.frame_matches_70450(reference, reference)
    legacy = login.PixelImage(*login.REFERENCE_SIZE, fill=(20, 20, 20))
    for left, top, right, bottom in login.LOGIN_ROIS.values():
        legacy.fill_rect(left + 4, top + 4, right - 4, bottom - 4, (255, 220, 0))
    assert not login.frame_matches_70450(legacy, reference)
    modal = _fixture_70450()
    modal.fill_rect(*login.LOGIN_ROIS_70450["username"], (20, 20, 20))
    assert not login.frame_matches_70450(modal, reference)
    masked_diff = _fixture_70450()
    masked_diff.fill_rect(*login.MASK_RECTS_70450[0], (12, 34, 56))
    assert login.frame_matches_70450(masked_diff, reference)
    altered = _fixture_70450()
    altered.fill_rect(*login.STABLE_ROIS_70450[0], (240, 240, 240))
    assert not login.frame_matches_70450(altered, reference)


def test_70450_blink_rejects_static_glyph_and_wrong_field() -> None:
    reference = _fixture_70450()
    on_user = _fixture_70450("username")
    off = _fixture_70450()
    login.verify_70450_blink([on_user, off], reference, "username")
    login.verify_70450_blink([on_user, off], reference, "tab")
    with pytest.raises(login.LoginError, match="password-focus-not-confirmed"):
        login.verify_70450_blink([on_user, off], reference, "password")
    on_pass = _fixture_70450("password")
    login.verify_70450_blink([on_pass, off], reference, "password")
    login.verify_70450_blink([on_pass, off], reference, "submit")
    with pytest.raises(login.LoginError, match="focus-not-confirmed"):
        login.verify_70450_blink([on_user, on_user], reference, "username")
    with pytest.raises(login.LoginError, match="focus-not-confirmed"):
        login.verify_70450_blink([off, off], reference, "username")
    with pytest.raises(login.LoginError, match="sample-bound"):
        login.verify_70450_blink([on_user], reference, "username")
    both_on = _fixture_70450("username")
    x_start, y_start, x_end, y_end = login.FIELD_SCAN_70450["password"]
    for y in range(y_start, y_end):
        both_on.putpixel((x_start + 7, y), (255, 255, 255))
    with pytest.raises(login.LoginError, match="ambiguous"):
        login.verify_70450_blink([both_on, off], reference, "username")
    with pytest.raises(login.LoginError, match="phase-invalid"):
        login.verify_70450_blink([on_user, off], reference, "enter")
    wide = _fixture_70450()
    x_start, y_start, x_end, y_end = login.FIELD_SCAN_70450["username"]
    for x in range(x_start, x_start + 40):
        for y in range(y_start, y_end):
            wide.putpixel((x, y), (255, 255, 255))
    with pytest.raises(login.LoginError, match="focus-not-confirmed"):
        login.verify_70450_blink([wide, off], reference, "username")


def test_70450_tracker_never_reuses_stale_proof() -> None:
    reference = _fixture_70450()
    on_user = login._encode_png(_fixture_70450("username"))
    on_pass = login._encode_png(_fixture_70450("password"))
    off = login._encode_png(_fixture_70450())
    user_burst = (on_user, off)
    tracker = login.Tracker70450(lambda: user_burst, reference)
    assert tracker.press_guard() is False
    tracker.phase_guard("username")
    assert tracker.current == "username"
    assert tracker.press_guard() is True
    tracker.current = "password"
    assert tracker.press_guard() is False
    tracker._capture = lambda: (on_pass, off)
    tracker.phase_guard("password")
    assert tracker.press_guard() is True
    tracker._capture = lambda: (off, off)
    assert tracker.press_guard() is False
    with pytest.raises(login.LoginError, match="focus-not-confirmed"):
        tracker.phase_guard("username")
    assert tracker.current is None
    assert tracker.press_guard() is False
    with pytest.raises(login.LoginError, match="sample-bound"):
        login.Tracker70450(lambda: (on_user,), reference).phase_guard("username")
    # Failed password phase clears a prior successful username phase.
    tracker = login.Tracker70450(lambda: user_burst, reference)
    tracker.phase_guard("username")
    assert tracker.current == "username"
    tracker._capture = lambda: user_burst
    with pytest.raises(login.LoginError, match="password-focus-not-confirmed"):
        tracker.phase_guard("password")
    assert tracker.current is None
    assert tracker.press_guard() is False
    # Static typed glyph (always ON, no OFF) never authorizes a press.
    tracker = login.Tracker70450(lambda: user_burst, reference)
    tracker.phase_guard("username")
    tracker._capture = lambda: (on_user, on_user)
    assert tracker.press_guard() is False
    # Wrong-field fresh burst never authorizes the stale phase.
    tracker._capture = lambda: (on_pass, off)
    assert tracker.press_guard() is False
    # Mixed burst (one valid frame, one wrong-geometry frame) fails closed.
    modal = _fixture_70450()
    modal.fill_rect(*login.LOGIN_ROIS_70450["username"], (20, 20, 20))
    modal_png = login._encode_png(modal)
    tracker._capture = lambda: (on_user, modal_png)
    assert tracker.press_guard() is False
    with pytest.raises(login.LoginError, match="geometry-not-confirmed"):
        tracker.phase_guard("username")
