"""Focused C116 branding tests: approved emblem geometry, layer bindings and
fail-closed staging/manifest admission. No exhaustive matrix; only the
bounds that guard the safe-zone and provenance regressions."""

from __future__ import annotations

import importlib.util
import re
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "scripts/arm64-prototype/app/reconstruct_functional_source.py"
SPEC = importlib.util.spec_from_file_location("reconstruct_functional_source_branding", SCRIPT)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = module
SPEC.loader.exec_module(module)

BRANDING = module.BRANDING_ROOT
SHIELD_32 = {(16, 2), (28, 7), (26, 23), (16, 30), (6, 23), (4, 7)}
DIAMOND_32 = {(16, 8), (21, 16), (16, 25), (11, 16)}
SCALE, OFFSET = 1.75, 26.0


def _path_points(data: str) -> list[tuple[float, float]]:
    numbers = [float(item) for item in re.findall(r"-?\d+(?:\.\d+)?", data)]
    assert len(numbers) % 2 == 0
    return [(numbers[i], numbers[i + 1]) for i in range(0, len(numbers), 2)]


def test_emblem_foreground_reproduces_the_approved_glyph_inside_the_safe_zone() -> None:
    text = (BRANDING / "drawable/forever_emblem_foreground.xml").read_text(encoding="utf-8")
    assert 'viewportWidth="108"' in text
    assert text.count("@color/forever_gold") == 3
    assert "FFFFFF" not in text and "GameNative" not in text
    paths = re.findall(r'android:pathData="([^"]+)"', text)
    assert len(paths) == 2
    assert text.count('android:strokeLineJoin="round"') == 2
    assert 'android:fillAlpha="0.24"' in text
    strokes = {float(item) for item in re.findall(r'android:strokeWidth="([\d.]+)"', text)}
    assert strokes == {2.625}
    originals = [{(round((x - OFFSET) / SCALE, 6), round((y - OFFSET) / SCALE, 6)) for x, y in _path_points(data)} for data in paths]
    assert originals[0] == {(float(x), float(y)) for x, y in SHIELD_32}
    assert originals[1] == {(float(x), float(y)) for x, y in DIAMOND_32}
    for data in paths:
        for x, y in _path_points(data):
            distance = ((x - 54) ** 2 + (y - 54) ** 2) ** 0.5
            assert distance <= 33 - 2.625 / 2, f"ink outside the masked safe zone: {(x, y)}"


def test_monochrome_layer_is_a_single_neutral_paint_of_the_same_geometry() -> None:
    mono = (BRANDING / "drawable/forever_emblem_monochrome.xml").read_text(encoding="utf-8")
    fore = (BRANDING / "drawable/forever_emblem_foreground.xml").read_text(encoding="utf-8")
    assert re.findall(r'android:pathData="([^"]+)"', mono) == re.findall(r'android:pathData="([^"]+)"', fore)
    assert "forever_gold" not in mono and "forever_navy" not in mono
    paints = set(re.findall(r'android:(?:strokeColor|fillColor)="([^"]+)"', mono))
    assert paints <= {"#FFFFFF", "#00000000"}
    assert "#FFFFFF" in paints


def test_adaptive_icons_share_layers_and_gate_monochrome_behind_api33() -> None:
    base = (BRANDING / "mipmap-anydpi-v26/forever_ic_launcher.xml").read_text(encoding="utf-8")
    wide = (BRANDING / "mipmap-anydpi-v33/forever_ic_launcher.xml").read_text(encoding="utf-8")
    for text in (base, wide):
        assert "@drawable/forever_emblem_background" in text
        assert "@drawable/forever_emblem_foreground" in text
        assert "ic_launcher\"" not in text and "ic_launcher_" not in text
    assert "<monochrome" not in base
    assert "@drawable/forever_emblem_monochrome" in wide


def test_splash_themes_inherit_pluvia_and_keep_api31_attrs_gated() -> None:
    base = (BRANDING / "values/forever_branding_themes.xml").read_text(encoding="utf-8")
    gated = (BRANDING / "values-v31/forever_branding_themes.xml").read_text(encoding="utf-8")
    legacy = (BRANDING / "drawable/forever_splash_legacy.xml").read_text(encoding="utf-8")
    assert "<layer-list" in legacy
    assert "@color/forever_navy_bottom" in legacy
    assert "@drawable/forever_emblem_foreground" in legacy
    assert 'android:gravity="center"' in legacy
    assert 'android:width="192dp"' in legacy and 'android:height="192dp"' in legacy
    for text in (base, gated):
        assert 'parent="@style/Theme.Pluvia"' in text
    assert "@drawable/forever_splash_legacy" in base
    assert "windowSplashScreen" not in base
    assert '<item name="android:windowBackground">@color/forever_navy_bottom</item>' in gated
    assert "windowSplashScreenBackground" in gated
    assert "@drawable/forever_emblem_foreground" in gated
    assert "forever_splash_legacy" not in gated
    assert "windowSplashScreenAnimationDuration" not in gated
    assert "BrandingImage" not in gated


def _branded_manifest_sample() -> str:
    return """<application
        android:icon="${icon}"
        android:label="Forever">
        <activity
            android:name="app.gamenative.ForeverFunctionalPocActivity"
            android:exported="true"
            android:theme="@style/Theme.Pluvia">
         </activity>
        <activity-alias
            android:name=".MainActivityAliasDefault"
            android:icon="@mipmap/ic_launcher" />
        <activity-alias
            android:name=".MainActivityAliasAlt"
            android:icon="@mipmap/ic_launcher_alt" />
    </application>
"""


def test_manifest_branding_binds_app_alias_and_entry_theme() -> None:
    result = module.transform_manifest_branding(_branded_manifest_sample())
    assert 'android:icon="@mipmap/forever_ic_launcher"' in result
    assert 'android:roundIcon="@mipmap/forever_ic_launcher"' in result
    assert result.count("@mipmap/forever_ic_launcher") == 4
    assert 'android:theme="@style/Theme.ForeverFunctionalSplash"' in result
    for leftover in ("${icon}", '@mipmap/ic_launcher"', '@mipmap/ic_launcher_alt"'):
        assert leftover not in result


def test_manifest_branding_is_fail_closed() -> None:
    with pytest.raises(module.ReconstructionError, match="not unique"):
        module.transform_manifest_branding(_branded_manifest_sample().replace("${icon}", "@mipmap/ic_launcher"))
    with pytest.raises(module.ReconstructionError, match="alias icon anchor"):
        module.transform_manifest_branding(_branded_manifest_sample().replace('@mipmap/ic_launcher_alt"', '@mipmap/ic_launcher_unknown"'))
    with pytest.raises(module.ReconstructionError, match="entry activity anchor"):
        module.transform_manifest_branding(_branded_manifest_sample().replace("ForeverFunctionalPocActivity", "RemovedActivity"))
    with pytest.raises(module.ReconstructionError, match="entry theme anchor"):
        module.transform_manifest_branding(
            _branded_manifest_sample().replace('android:theme="@style/Theme.Pluvia"', 'android:theme="@style/Theme.Other"')
        )


def test_branding_staging_is_explicit_and_fail_closed(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    import shutil

    root = tmp_path / "branding-res"
    shutil.copytree(BRANDING, root)
    monkeypatch.setattr(module, "BRANDING_ROOT", root)
    source = tmp_path / "source"
    source.mkdir()
    entries = module.stage_branding_resources(source)
    assert len(entries) == len(module.BRANDING_RESOURCE_FILES) == 9
    for (origin_name, target_name), entry in zip(module.BRANDING_RESOURCE_FILES, entries):
        assert entry["origin"] == f"repo-branding-res:{origin_name}"
        assert entry["path"] == target_name
        assert entry["reviewStatus"] == "EXPLICITLY_ADMITTED_BRANDING_RESOURCE"
        assert (source / target_name).read_bytes() == (root / origin_name).read_bytes()
        assert entry["sha256"] == entry["originSha256"] == module.sha256_file(root / origin_name)

    collision_target = source / module.BRANDING_RESOURCE_FILES[0][1]
    collision_target.write_bytes(b"unknown collision")
    with pytest.raises(module.ReconstructionError, match="unknown functional destination collision"):
        module.stage_branding_resources(source)

    (root / "values" / "stray-test.xml").write_bytes(b"stray")
    with pytest.raises(module.ReconstructionError, match="explicit file list"):
        module.stage_branding_resources(tmp_path / "other")
