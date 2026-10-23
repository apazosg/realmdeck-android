#!/usr/bin/env python3
"""Fresh/idempotent verification for the bounded Wine packaging recipe."""

from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

from apply_overlay import (
    NEW_MAIN_SOURCES,
    NEW_TEST_SOURCES,
    _source_payload_path,
    load_code59_source_baseline,
    sha256,
)


def _copy_archive_tree(archive_path: Path, destination: Path) -> None:
    """Extract one source archive while refusing ZIP path escapes."""
    with zipfile.ZipFile(archive_path) as archive:
        members = [info for info in archive.infolist() if info.filename.endswith("/app/build.gradle.kts")]
        if len(members) != 1:
            raise ValueError("source archive must contain exactly one app/build.gradle.kts")
        prefix = members[0].filename[:-len("app/build.gradle.kts")]
        for info in archive.infolist():
            if not info.filename.startswith(prefix):
                continue
            relative = Path(info.filename[len(prefix):])
            if relative.is_absolute() or ".." in relative.parts:
                raise ValueError("source archive path escape")
            target = destination / relative
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(info) as stream, target.open("wb") as output:
                    shutil.copyfileobj(stream, output, length=1024 * 1024)


def _owned_paths(root: Path) -> set[Path]:
    baseline = load_code59_source_baseline()
    paths = {
        root / "app/build.gradle.kts",
        root / "app/src/main/AndroidManifest.xml",
        root / "app/src/main/java/app/gamenative/PluviaApp.kt",
        root / "app/src/main/assets/forever/provenance.json",
        root / "app/src/main/assets/forever/wine-supervisor/manifest.json",
        root / "app/src/main/assets/forever/wine-prefix/publisher-manifest.json",
        root / "app/src/main/assets/forever/wine-prefix/prefix-image.zip",
        root / "app/src/main/assets/forever/wine-prefix/image-manifest.json",
        root / "app/src/main/assets/forever/wine-interrupted-run-disposition-contract.json",
        root / "app/src/main/assets/forever/wine-prefix-prelaunch-continuation-contract.json",
        root / "app/src/main/jniLibs/arm64-v8a/libforever_wine_supervisor.so",
        root / "app/src/main/jniLibs/arm64-v8a/libforever_wine_prefix.so",
    }
    paths.update(root / str(entry["path"]) for entry in baseline["entries"])
    for group in baseline["inputGroups"].values():
        paths.update(root / _source_payload_path(str(member)) for member in group["sha256"])
    paths.update(root / "app/src/main/java/app/gamenative" / name for name in NEW_MAIN_SOURCES)
    paths.update(root / "app/src/test/java/app/gamenative" / name for name in NEW_TEST_SOURCES)
    overlay = Path(__file__).with_name("overlay")
    paths.update(root / "app/src/main/java/app/gamenative" / path.name
                 for path in [*overlay.glob("Forever*.kt"), *overlay.glob("PanvkWsi*.kt")]
                 if not path.name.endswith("Test.kt"))
    paths.update(root / "app/src/test/java/app/gamenative" / path.name
                 for path in [*overlay.glob("Forever*Test.kt"), *overlay.glob("PanvkWsi*Test.kt")])
    return paths


def _snapshot(paths: set[Path]) -> dict[str, str]:
    return {str(path): sha256(path) for path in paths if path.is_file()}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    args, inputs = parser.parse_known_args()
    if not args.work_dir.is_dir():
        raise ValueError("external work parent must already exist")
    if not args.archive.is_file() or not args.source.is_dir():
        raise ValueError("archive/source input missing")
    recipe = Path(__file__).with_name("apply_overlay.py")
    command = [sys.executable, str(recipe), "--source"]
    with tempfile.TemporaryDirectory(prefix="wine-overlay-", dir=args.work_dir) as temporary:
        fresh = Path(temporary) / "fresh"
        fresh.mkdir()
        _copy_archive_tree(args.archive, fresh)
        subprocess.run(command + [str(fresh), *inputs], check=True)
        owned = _owned_paths(fresh)
        expected = _snapshot(owned)
        required = [fresh / "app/src/main/java/app/gamenative" / name for name in NEW_MAIN_SOURCES]
        required += [fresh / "app/src/test/java/app/gamenative" / name for name in NEW_TEST_SOURCES]
        if not all(path.is_file() for path in required):
            raise AssertionError("new Wine Kotlin sources were not materialized in main/test roots")
        subprocess.run(command + [str(fresh), *inputs], check=True)
        if expected != _snapshot(owned):
            raise AssertionError("bounded overlay is not idempotent")

        # Compare the complete recipe-owned source surface with the actual
        # arbitrary-path source tree.  Existing upstream files are not copied
        # or normalized by this candidate; only these explicit paths are owned.
        actual_paths = _owned_paths(args.source)
        mismatches = []
        for fresh_path, digest in expected.items():
            relative = Path(fresh_path).relative_to(fresh)
            actual = args.source / relative
            if not actual.is_file() or sha256(actual) != digest:
                mismatches.append(str(relative))
        if mismatches:
            raise AssertionError("fresh recipe differs from actual source: " + ", ".join(mismatches))
        # The replay surface is authenticated independently of the new Wine
        # resources: every old APK asset/JNI member must be present and exact.
        replay_mismatches = []
        for group in load_code59_source_baseline()["inputGroups"].values():
            for member, expected in group["sha256"].items():
                if member == "assets/forever/provenance.json":
                    continue  # explicitly authorized provenance replacement
                relative = _source_payload_path(str(member))
                actual = args.source / relative
                if not actual.is_file() or sha256(actual) != expected:
                    replay_mismatches.append(str(relative))
        if replay_mismatches:
            raise AssertionError("baseline APK replay differs from actual source: " + ", ".join(replay_mismatches))
        provenance = fresh / "app/src/main/assets/forever/provenance.json"
        if provenance.is_file():
            import json
            value = json.loads(provenance.read_text(encoding="utf-8"))
            if value.get("recipeSha256") != sha256(recipe):
                raise AssertionError("provenance recipe SHA does not identify this recipe")
        print(f"FRESH-WINE-OVERLAY-PASS {len(expected)} owned inputs; idempotence and actual-source equivalence")


if __name__ == "__main__":
    main()
