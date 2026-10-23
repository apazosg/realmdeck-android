"""Focused authentication tests for the effective code59 source receipt."""

from __future__ import annotations

import hashlib
import json
import zipfile
from pathlib import Path, PurePosixPath

import pytest


ROOT = Path(__file__).parents[1]
BASELINE = ROOT / "scripts/arm64-prototype/app/code59-source-baseline.json"
ARCHIVE = Path("/home/adrian/wowforever-artifacts/arm64-prototype/toolchain/GameNative-205cbf8ddc06f855a1b85ba57c78c4be9a6930aa.zip")
APK = Path("/home/adrian/wowforever-artifacts/arm64-prototype/delivery/retained-0.2.35-code59.apk")
EXTERNAL = Path("/home/adrian/wowforever-artifacts/arm64-prototype/toolchain/GameNative")
OVERLAY = ROOT / "scripts/arm64-prototype/app/overlay"
ARCHIVE_SHA256 = "9151ca5a20168a8901797d7fcc2946706f57532088632527876efa2931bdc361"
APK_SHA256 = "b8cace99ded43adb1841a2047f3802976b4b2d113aa3c5a67091bdd7270d2435"
PROVENANCE_SHA256 = "9746c76df4eab025ab9262ef132e94cb05b9ca49ff50b9fafcfdc50fb391876a"
RECIPE_SHA256 = "752eca7c81798fa140d1cf8b2aa78148efb35c1f45cc2abf7128b54742f32b62"
COMMIT = "205cbf8ddc06f855a1b85ba57c78c4be9a6930aa"
MAX_POSTIMAGE_BYTES = 2 * 1024 * 1024
DERIVED = "Arm64Diagnostics.kt"
OLD_RUNTIME = 'context.assets.open("$APP_ASSET_ROOT/$path")'
NEW_RUNTIME = 'context.assets.open(if (path.startsWith("runtime/")) "forever/$path" else "$APP_ASSET_ROOT/$path")'
OLD_COPY = 'context.assets.open(asset).use { input -> destination.outputStream().use(input::copyTo) }'
NEW_COPY = 'context.assets.open(if (asset.startsWith("arm64-diagnostics/runtime/")) asset.replaceFirst("arm64-diagnostics/", "forever/") else asset).use { input -> destination.outputStream().use(input::copyTo) }'


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_sha(path: Path) -> str:
    return sha(path.read_bytes())


def require_regular(path: Path) -> None:
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"not a regular source file: {path}")


def bounded_utf8(data: bytes) -> str:
    if len(data) > MAX_POSTIMAGE_BYTES:
        raise ValueError("postimage exceeds bound")
    return data.decode("utf-8")


def validate_unique_paths(paths: list[str]) -> None:
    if len(paths) != len(set(paths)):
        raise ValueError("duplicate path")


def validate_safe_path(value: str) -> None:
    path = PurePosixPath(value)
    if "\x00" in value or "\\" in value or path.is_absolute() or ".." in path.parts or "." in path.parts:
        raise ValueError("unsafe path")


def load() -> dict:
    return json.loads(BASELINE.read_text(encoding="utf-8"))


def test_baseline_authenticates_pinned_inputs_and_scope() -> None:
    value = load()
    assert file_sha(BASELINE) == "0a3d039a1d018230a9a99ab7c4de45d7e5753b75626868c8f06cad3678fdd0d8"
    assert value["schema"] == 1
    assert value["kind"] == "effective-code59-source-migration"
    assert value["sourceMode"] == "effective-source-at-collection"
    assert value["sourceArchiveSHA256"] == ARCHIVE_SHA256
    assert value["sourceArchiveSHA"] == ARCHIVE_SHA256
    assert value["packageCommit"] == COMMIT
    assert value["version59"] == {"code": 59, "name": "0.2.35-automated"}
    assert value["recipeHistoricalSHA"] == RECIPE_SHA256
    assert value["recoveryDecision"]["oldRecipeRecovered"] is False
    assert file_sha(ARCHIVE) == ARCHIVE_SHA256
    assert value["baselineAPK"]["wholeSHA256"] == APK_SHA256
    assert value["baselineAPK"]["sha256"] == APK_SHA256
    assert file_sha(APK) == APK_SHA256
    assert value["provenanceSHA256"] == PROVENANCE_SHA256
    assert value["provenanceSHA"] == PROVENANCE_SHA256
    assert value["baselineAPK"]["provenance"]["externalBytesEqual"] is True
    assert value["overlayAuthority"]["counts"] == {"expected": 63, "matched": 63, "literal": 62, "derived": 1}
    assert len(value["entries"]) == 12
    assert value["baseInventory"]["missingFiles"] == 0
    assert value["baseInventory"]["binaryMismatchFiles"] == 0
    assert value["baseInventory"]["originalAllBase"]["inventorySHA256"] == value["baseInventory"]["upstreamInventorySHA256"]


def test_overlay_authority_has_62_literal_and_exact_derived_output() -> None:
    value = load()
    bindings = value["overlayAuthority"]["bindings"]
    assert len(bindings) == 63
    assert sum(binding["authority"] == "literal-overlay-source" for binding in bindings) == 62
    derived = next(binding for binding in bindings if binding["name"] == DERIVED)
    assert derived["authority"] == "verified-recipe-output-transform"
    assert derived["provenanceSHA256"] == "73778dbb0f30f655860c44b10fed0c8f384c2e79b4b281704234f8462111f126"
    input_bytes = (OVERLAY / DERIVED).read_bytes()
    target = EXTERNAL / derived["path"]
    require_regular(OVERLAY / DERIVED)
    require_regular(target)
    text = bounded_utf8(input_bytes)
    assert text.count(OLD_RUNTIME) == 2
    assert text.count(OLD_COPY) == 1
    transformed = text.replace(OLD_RUNTIME, NEW_RUNTIME).replace(OLD_COPY, NEW_COPY).encode("utf-8")
    assert transformed == target.read_bytes()
    assert derived["sourceInputSHA256"] == sha(input_bytes)
    assert derived["generatedSHA256"] == sha(transformed)


def test_overlay_bindings_preserve_history_and_explicit_wine_store_delta() -> None:
    value = load()
    for binding in value["overlayAuthority"]["bindings"]:
        validate_safe_path(binding["path"])
        source = OVERLAY / binding["name"]
        target = EXTERNAL / binding["path"]
        require_regular(source)
        require_regular(target)
        if binding["name"] == "ForeverRunStore.kt":
            # The snapshot records code59 and the then-current migration input;
            # the approved Wine Store is intentionally a newer reviewed delta.
            assert source.read_bytes() == target.read_bytes()
            assert "beginWine" in source.read_text()
            assert binding["generatedSHA256"] == "720a35c8918def4d03f386934d65ab6b9ac68a22f95ca286b64b138d8e9c0ea8"
        elif binding["name"] == "ForeverLaunchController.kt":
            # The code59 receipt keeps the historical controller pin; the reviewed
            # postimage evolved separately (see reconstruct's controller preimage).
            assert binding["currentRepoSourceSHA256"] == "45f80f52a1c840cde21cfff45643532af22787dc9025ec030ba4dfb352aded68"
            assert sha(target.read_bytes()) == "8e89d2b0a79af9c6d6470cbf7897fdcb3941437febac96cd819a48258fdd1dac"
        else:
            assert binding["currentRepoSourceSHA256"] == sha(source.read_bytes())
            assert binding["generatedSHA256"] == sha(target.read_bytes())
        if binding["name"] != DERIVED:
            assert binding["generatedSHA256"] == binding["provenanceSHA256"]
    store = next(item for item in value["overlayAuthority"]["bindings"] if item["name"] == "ForeverRunStore.kt")
    assert store["currentRepoSourceMatchesHistoricalInput"] is False


def test_changed_entries_have_exact_upstream_identity_and_utf8_postimages() -> None:
    value = load()
    with zipfile.ZipFile(ARCHIVE) as archive:
        prefix = next(name for name in archive.namelist() if name.endswith("/app/build.gradle.kts"))[:-len("app/build.gradle.kts")]
        for entry in value["entries"]:
            path = entry["path"]
            upstream = archive.read(prefix + path)
            # Authenticate the immutable recorded postimage, not a mutable
            # external tree that now legitimately contains candidate60.
            postimage = entry["utf8Postimage"].encode("utf-8")
            assert sha(upstream) == entry["upstreamSHA256"]
            assert sha(upstream) == entry["upstreamSHA"]
            assert len(upstream) == entry["upstreamBytes"]
            assert sha(postimage) == entry["target59SHA256"]
            assert sha(postimage) == entry["target59SHA"]
            assert len(postimage) == entry["target59Bytes"]
            assert bounded_utf8(postimage) == entry["utf8Postimage"]
            assert entry["sourceBefore"]["identity"] == "pinned-upstream-archive-entry"


def test_negative_missing_or_modified_source_is_rejected(tmp_path: Path) -> None:
    entry = load()["entries"][0]
    missing = tmp_path / "missing.kt"
    with pytest.raises(ValueError, match="regular"):
        require_regular(missing)
    modified = tmp_path / "modified.kt"
    modified.write_bytes(entry["utf8Postimage"].encode("utf-8") + b"\nchanged")
    assert file_sha(modified) != entry["target59SHA256"]


def test_negative_symlink_duplicate_path_and_unsafe_utf8_bounds(tmp_path: Path) -> None:
    target = tmp_path / "target"
    target.write_bytes(b"x")
    link = tmp_path / "link"
    link.symlink_to(target)
    with pytest.raises(ValueError, match="regular"):
        require_regular(link)
    with pytest.raises(ValueError, match="duplicate"):
        validate_unique_paths(["app/a.kt", "app/a.kt"])
    with pytest.raises(ValueError, match="unsafe"):
        validate_safe_path("../escape.kt")
    with pytest.raises(UnicodeDecodeError):
        bounded_utf8(b"\xff")
    with pytest.raises(ValueError, match="bound"):
        bounded_utf8(b"x" * (MAX_POSTIMAGE_BYTES + 1))


def test_source_overlay_hash_comparison_is_separate_from_base_postimages() -> None:
    value = load()
    assert value["overlayAuthority"]["counts"]["matched"] == 63
    assert value["baseInventory"]["changedFiles"] == len(value["entries"]) == 12
    assert not set(binding["path"] for binding in value["overlayAuthority"]["bindings"]) & set(entry["path"] for entry in value["entries"])
