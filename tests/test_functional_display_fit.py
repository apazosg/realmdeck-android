from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).parents[1]
OVERLAY = ROOT / "scripts/arm64-prototype/app/overlay"


def _read(name: str) -> str:
    return (OVERLAY / name).read_text(encoding="utf-8")


def test_display_fit_is_pure_and_has_approved_modes_and_guardrails() -> None:
    source = _read("ForeverFunctionalDisplayFit.kt")

    assert "fun calculate(mode: Mode, viewport: Viewport): Plan" in source
    assert 'AUTO("AUTO", "Automática · 720", 720)' in source
    assert 'PERFORMANCE("PERFORMANCE", "Resolución 540", 540)' in source
    assert 'QUALITY("QUALITY", "Resolución 900", 900)' in source
    assert 'NATIVE("NATIVE", "Nativa", null)' in source
    assert '"Rendimiento"' not in source
    assert '"Calidad"' not in source
    assert "require(usableWidth > usableHeight)" in source
    assert "fun calculateOverlappingInsets(" in source
    assert "viewLeft: Int" in source
    assert "physicalCutout: Insets?" in source
    assert "candidateWidth % 2 != 0" in source
    assert "candidateHeight % 2 != 0" in source
    assert "timeoutMs: Long = 5_000L" in source


def test_idle_preparation_is_auto_closed_by_settings_instead_of_blocking() -> None:
    # US-004 AC-07/FR-017: a completed, unlaunched preparation is frozen data,
    # not work. Entering resolution or confirming a graphics reset discards
    # the idle snapshot and its consent automatically; real work stays
    # excluded by the session/IO guards. No internal-preparation block remains.
    activity = _read("ForeverFunctionalPocActivity.kt")

    assert "fun discardIdlePreparedSnapshot()" in activity
    # Only a snapshot with no guest handles is discarded; a reservation held
    # for a launch is never dropped here.
    assert "snapshot.durableGuestTicket == null && snapshot.guestReservation == null" in activity
    assert "preparación activa" not in activity
    # Resolution entry is gated only by real session/IO work, never by the
    # idle snapshot, and discards it on entry (selection keeps its discard).
    settings = activity.split("if (showSettings) {", 1)[1].split("if (showAdvancedSettings)", 1)[0]
    label_at = settings.index('"Pantalla / resolución: ${displayMode.label}"')
    window = settings[label_at : label_at + 800]
    assert "enabled = !sessionOrIoLocked" in window
    assert "enabled = !controlsLocked" not in window
    assert "discardIdlePreparedSnapshot()" in window
    assert "showDisplayModeOptions = true" in window
    # A real in-flight operation keeps a distinct, user-relevant message.
    assert "Hay una operación en curso" in activity
    # Reset confirm discards the idle snapshot; the matching stale-guest
    # recovery wiring itself is pinned in test_aspect_ratio (same file).
    reset = activity.split("fun resetGraphicsToDefaults()", 1)[1].split(
        "Single launch path shared by the home CTA", 1
    )[0]
    assert "discardIdlePreparedSnapshot()" in reset


def test_display_application_is_derived_file_only_and_coordinates_wine_and_wow() -> None:
    poc = _read("ForeverFunctionalPoc.kt")
    profiles = _read("ForeverFunctionalServerProfiles.kt")

    assert "fun applyDisplayPlan(" in poc
    assert "prepared.container.setScreenSize(plan.size)" in poc
    assert "BetaSuspendedTest.wtf" in poc
    assert 'always["gxResolution"] = displayPlan.size' in profiles
    assert 'always["gxWindowedResolution"] = displayPlan.size' in profiles
    assert 'always["gxFullscreenResolution"] = displayPlan.size' in profiles
    assert 'always["gxMaximize"] = "1"' in profiles
    assert '"gxWindow"' not in profiles
    # Graphics saved in-game survive prepare/resolution; only an explicit
    # reset restores the one-time preset. The derived file is the writable
    # source once created; the original is only the first-creation fallback.
    assert "GRAPHICS_PRESET_DEFAULTS" in profiles
    assert "fun mergeDerivedPreservingGraphics(" in profiles
    assert "fun resetGraphicsPreset(" in profiles
    assert "mergeDerivedPreservingGraphics(" in poc
    assert "derivedUsable" in poc
    assert "fun resetDerivedGraphics(" in poc
    assert '"game_running"' in poc


def test_settings_selector_is_visible_and_persisted_without_touching_auth() -> None:
    fit = _read("ForeverFunctionalDisplayFit.kt")
    activity = _read("ForeverFunctionalPocActivity.kt")

    assert 'private const val PREFS = "functional_poc_display_fit"' in fit
    assert "fun loadMode(context: Context): Mode" in fit
    assert "fun saveMode(context: Context, mode: Mode)" in fit
    assert 'label = "Pantalla / resolución: ${displayMode.label}"' in activity
    assert 'title = { Text("Resolución"' in activity
    assert "ForeverFunctionalDisplayFit.Mode.values()" in activity
    assert "displayMode.nativeWarning" in activity
    assert "ForeverFunctionalAutologinPreferences" not in fit
    # Resolution-only selector: whole-row selectable radio rows, copy that
    # keeps quality inside the game, and the explicit graphics-only reset.
    assert ".selectable(" in activity
    assert "onClick = null," in activity
    assert "La calidad se cambia dentro del juego y se conserva entre sesiones" in activity
    assert '"Restablecer gráficos"' in activity
    assert "fun resetGraphicsToDefaults()" in activity
    assert "showGraphicsResetConfirm" in activity
    assert "resetDerivedGraphics(context, configuration)" in activity
    # Reset invalidates consent at start and hides the launch panel while it
    # runs, so Play/prepare/launch cannot start mid-reset and vice versa.
    reset = activity.split("fun resetGraphicsToDefaults()", 1)[1].split(
        "Single launch path shared by the home CTA", 1
    )[0]
    assert "graphicsResetBusy = true" in reset
    assert "consent = false" in reset
    assert "val controlsLocked = busy || prepared != null || running || launchRequested || pendingDisplayIo || storageBusy || graphicsResetBusy" in activity
    assert "val sessionOrIoLocked = busy || running || launchRequested || pendingDisplayIo || storageBusy || graphicsResetBusy" in activity
    assert "!graphicsResetBusy && storageKnown" in activity
    assert "onPendingDisplayCancellationChanged" in activity
    assert "CancellationException" in activity
    assert "getLocationInWindow" in activity
    assert "currentWindowMetrics" in activity
    assert "left = displayInsets.left.toDp()" in activity
