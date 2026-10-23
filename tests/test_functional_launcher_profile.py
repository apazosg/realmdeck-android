from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).parents[1]
PROFILES = ROOT / "scripts/arm64-prototype/app/overlay/ForeverFunctionalServerProfiles.kt"


def test_crlf_lines_are_matched_without_discarding_foreign_line_bytes() -> None:
    source = PROFILES.read_text(encoding="utf-8")
    assert 'PORTAL_LINE.matches(line.removeSuffix("\\r"))' in source
    assert 'SET_KEY_LINE.matchEntire(line.removeSuffix("\\r"))' in source
    assert "windowsLineEndingsDoNotRetainDuplicateManagedKeysOrPortals" in (
        PROFILES.with_name("ForeverFunctionalServerProfilesTest.kt").read_text(encoding="utf-8")
    )


def test_profile_merge_manages_full_first_ux_preset_and_keeps_resource_override_separate() -> None:
    source = PROFILES.read_text(encoding="utf-8")
    expected = {
        '"gxApi" to "D3D11"',
        '"vsync" to "0"',
        '"hwDetect" to "0"',
        '"maxFPS" to "30"',
        '"maxFPSBk" to "30"',
        '"useMaxFPS" to "1"',
        '"useMaxFPSBk" to "1"',
        '"useTargetFPS" to "0"',
        '"graphicsQuality" to "0"',
        '"graphicsShadowQuality" to "0"',
        '"graphicsViewDistance" to "0"',
        '"graphicsEnvironmentDetail" to "0"',
        '"graphicsGroundClutter" to "0"',
        '"graphicsTextureResolution" to "0"',
        '"graphicsParticleDensity" to "1"',
        '"graphicsSpellDensity" to "1"',
        '"GamePadEnable" to "1"',
        '"InputDeviceInterfaceStyle" to "1"',
    }
    assert all(item in source for item in expected)
    assert '"showPhotosensitivityWarning" to "0"' in source
    assert '"enableBGDL" to "0"' in source
    assert '"accessibilityScreenNarrationEnabled" to "0"' in source
    assert '"Sound_MasterVolume" to "0.03"' in source
    assert '"showPhotosensitivityWarning" to "0"' in source
    assert "Config.wtf" not in source
    # The preset is one-time: later preparations preserve in-game graphics
    # through mergeDerivedPreservingGraphics; only resetGraphicsPreset
    # restores GRAPHICS_PRESET_DEFAULTS on explicit user action.
    assert "GRAPHICS_PRESET_DEFAULTS" in source
    assert "fun mergeDerivedPreservingGraphics(" in source
    assert "fun resetGraphicsPreset(" in source
    assert "mergeDerivedPreservingGraphics" in (
        PROFILES.with_name("ForeverFunctionalPoc.kt").read_text(encoding="utf-8")
    )
