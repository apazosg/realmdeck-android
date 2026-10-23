from __future__ import annotations

import hashlib
import importlib.util
import json
import zipfile
from pathlib import Path


ROOT = Path(__file__).parents[1]
MODULE_PATH = ROOT / "scripts/diagnostics/android-process/build_wine_prefix_image.py"
SPEC = importlib.util.spec_from_file_location("wine_prefix_image", MODULE_PATH)
assert SPEC and SPEC.loader
image = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(image)

ARTIFACTS = Path("/home/adrian/wowforever-artifacts/arm64-prototype")
RUNTIME = ARTIFACTS / "runtime"
ARCHIVE = RUNTIME / "proton-11.0-90624-arm64ec/prefixPack.txz"
COMPILER = RUNTIME / "proton-11.0-90624-arm64ec/lib/wine/aarch64-windows/d3dcompiler_47.dll"
DXVK = RUNTIME / "dxvk-2.4.1-wow-aarch64-test/system32"


def test_real_pinned_source_builds_411_regular_163_directory_image(tmp_path: Path):
    out = tmp_path / "image"
    header = tmp_path / "wine_prefix_publish_manifest.h"
    image.build(ARCHIVE, COMPILER, DXVK / "dxgi.dll", DXVK / "d3d11.dll", out, header)
    manifest_path = out / "prefix-image-manifest.json"
    manifest = json.loads(manifest_path.read_text("ascii"))
    assert manifest["counts"] == {"directories": 163, "entries": 574, "links": 3, "regular": 411}
    assert hashlib.sha256((out / "prefix-image.zip").read_bytes()).hexdigest() == manifest["zip"]["sha256"]
    with zipfile.ZipFile(out / "prefix-image.zip") as archive:
        assert len(archive.infolist()) == 574
        assert sum(info.filename.endswith("/") for info in archive.infolist()) == 163
        assert all(not info.is_dir() for info in archive.infolist() if info.filename.endswith(".dll"))
    header_text = header.read_text("ascii")
    assert "wpp_member_count" in header_text
    assert header_text.count("WPP_REGULAR") == 412  # enum declaration plus 411 records


def test_builder_rejects_archive_pin_before_writing(tmp_path: Path):
    altered = tmp_path / "prefixPack.txz"
    altered.write_bytes(ARCHIVE.read_bytes())
    with altered.open("r+b") as stream:
        stream.seek(0)
        stream.write(b"bad")
    try:
        image.build(altered, COMPILER, DXVK / "dxgi.dll", DXVK / "d3d11.dll", tmp_path / "out", tmp_path / "header")
    except image.ImageError:
        pass
    else:
        raise AssertionError("untrusted archive was accepted")
