#!/usr/bin/env python3
"""Apply the bounded first Wine server-isolation APK package.

This recipe is intentionally much narrower than the historical launcher recipe.
It changes the release identity, adds the isolated Wine entry point and adds
the two new, externally-produced Wine resources.  It does not rewrite the
runtime, probes, JNI, old native fixtures, or any GameNative launch path.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
import re
import shutil
import struct
import importlib.util
import zipfile
from pathlib import Path
from typing import Iterator

try:
    from wine_interrupted_run_contract import load_contract, verify_external_evidence
except ModuleNotFoundError:  # imported by an offline test through a file spec
    _contract_spec = importlib.util.spec_from_file_location(
        "wine_interrupted_run_contract", Path(__file__).parents[2] / "diagnostics/android-process/wine_interrupted_run_contract.py"
    )
    if _contract_spec is None or _contract_spec.loader is None:
        raise
    _contract_module = importlib.util.module_from_spec(_contract_spec)
    _contract_spec.loader.exec_module(_contract_module)
    load_contract = _contract_module.load_contract
    verify_external_evidence = _contract_module.verify_external_evidence


PACKAGE = "com.wowforever.arm64probe"
TARGET_VERSION_CODE = 62
TARGET_VERSION_NAME = "0.2.38-automated"
SOURCE_CODE = 60
SOURCE_NAME = "0.2.36-automated"
BASELINE_SOURCE_NAME = "0.2.35-automated"
FRESH_SOURCE_CODE = 23
FRESH_SOURCE_NAME = "1.2.1"
PRIOR_WINE_CODE = 61
PRIOR_WINE_NAME = "0.2.37-automated"

WINE_SUPERVISOR_VARIANT = "wine-run-supervisor-v1"
WINE_SUPERVISOR_FILES = ("libforever_wine_supervisor.so",)
WINE_SUPERVISOR_MANIFEST_SHA256 = "8c9bcde26c1ae3a7ebcb620db47b5a5d4f9dc450f021ae4b4d97b28785b3b894"
WINE_SUPERVISOR_PINS = {
    "libforever_wine_supervisor.so": (
        45056,
        "6f641c3683b239eb58a3986e947afe2099555ef138d495f8490a000b5b52f0da",
    ),
}
WINE_SUPERVISOR_PIN_STATUS = "READY"

# Prefix output pins are deliberately not guessed; these are the reconciled
# publisher/image manifests supplied by the approved producer handoff.
PREFIX_INPUTS_STATUS = "READY"
PREFIX_INPUT_PINS = {
    "libforever_wine_prefix.so": (123560, "f479ea8def9dc3cffd42affa489ae1554f9214273b13af7d79053a2773ad6914"),
    "publisher-manifest.json": (None, "63d5b1bf80e9039030839c7fd37139f9d9ea9c8f78cd2d3f3eee7de5ba99922d"),
    "prefix-image.zip": (47680132, "42f9e9119682f5e3cbd9071efe3f079d0cac76896fe3ce0fa9b2ef0d41048853"),
    "prefix-image-manifest.json": (124991, "2318de72bb020da9263300511a2139abbd06c228f46fac1708d0fb759e625ba9"),
}
PREFIX_MANIFEST_SHA256 = "306bed4ade9aac21809919f1b1272cdbc9c389e77704a13374033dcee7b0bcfa"
PREFIX_EXPECTED_COUNTS = {"regular": 411, "directory": 163, "symlink": 3}
PREFIX_REPLACEMENT_PINS = {
    ".wine/drive_c/windows/system32/d3dcompiler_47.dll": (1114112, "c374b107fd14890e4b602921c82198bd7c3773f7f1d27f0712be92cb52ed3add"),
    ".wine/drive_c/windows/system32/dxgi.dll": (3059712, "136a8703e9f6aede96ae671ba16f88de1191e79d59e18a617bbbe85fad0c74fc"),
    ".wine/drive_c/windows/system32/d3d11.dll": (4907008, "5e2167882c88e8e4013e5d5aa1ba221ce9a3ddb847ed901c06b652ce97a07c0f"),
}
PREFIX_FILES = (
    "libforever_wine_prefix.so",
    "publisher-manifest.json",
    "prefix-image.zip",
    "prefix-image-manifest.json",
)
SOURCE_BASELINE = Path(__file__).with_name("code59-source-baseline.json")
SOURCE_BASELINE_BYTES = 831_917
SOURCE_BASELINE_SHA256 = "0a3d039a1d018230a9a99ab7c4de45d7e5753b75626868c8f06cad3678fdd0d8"
RETAINED_BASELINE_APK_SHA256 = "b8cace99ded43adb1841a2047f3802976b4b2d113aa3c5a67091bdd7270d2435"
HISTORICAL_RECIPE_SHA256 = "historical-source-not-recovered"

PRELAUNCH_DISPOSITION_ASSET = "src/main/assets/forever/native-supervisor/prelaunch-disposition.json"
PRELAUNCH_DISPOSITION_SOURCE = Path(__file__).parents[3] / "scripts/diagnostics/android-process/NATIVE-PRELAUNCH-DISPOSITION.json"
PRELAUNCH_DISPOSITION_SHA256 = "423ed396ac54c9caa5edf43c06ec645dc5b68275928003c064729b85ecb44c62"
INTERRUPTED_DISPOSITION_ASSET = "src/main/assets/forever/wine-interrupted-run-disposition-contract.json"
INTERRUPTED_DISPOSITION_SOURCE = Path(__file__).parents[2] / "diagnostics/android-process/wine-interrupted-run-disposition-contract.json"
INTERRUPTED_DISPOSITION_SHA256 = "c198e20f582b90e5bc691e3e4712e8b2b1ecf33434bf1f86232c30be7fab2ac6"
CONTINUATION_DISPOSITION_ASSET = "src/main/assets/forever/wine-prefix-prelaunch-continuation-contract.json"
CONTINUATION_DISPOSITION_SOURCE = Path(__file__).parents[2] / "diagnostics/android-process/wine-prefix-prelaunch-continuation-contract.json"
CONTINUATION_DISPOSITION_SHA256 = "0008db0586756da7b8e20a88725788ce64dc4c290f2d04d360068ff65a64da27"

PAGE_16K = 16 * 1024
ELF_MACHINE_AARCH64 = 183
ELF_TYPE_DYN = 3
NEW_MAIN_SOURCES = (
    "ForeverWineSupervisor.kt",
    "ForeverWineSupervisorPolicy.kt",
    "ForeverWineSupervisorActivity.kt",
    "ForeverWinePrefix.kt",
    "ForeverRunStore.kt",
    "ForeverInterruptedRunDisposition.kt",
    "ForeverPrelaunchContinuation.kt",
)
NEW_TEST_SOURCES = (
    "ForeverWineSupervisorTest.kt",
    "ForeverWineSupervisorPolicyTest.kt",
    "ForeverWinePrefixTest.kt",
    "ForeverWineRunStoreTest.kt",
    "ForeverInterruptedRunDispositionTest.kt",
    "ForeverPrelaunchContinuationTest.kt",
)
NEW_SOURCES = NEW_MAIN_SOURCES + NEW_TEST_SOURCES
APPROVED_WINE_OVERLAY_DELTAS = {"ForeverRunStore.kt", "ForeverLaunchController.kt"}
# Exact current source pins for reviewed changes since the authenticated 59 baseline.
# The historical baseline remains immutable; every other binding still matches it.
APPROVED_REVIEWED_OVERLAY_DELTAS = {
    "ForeverBoundedLogRetention.kt": "7b92dffd6ed05ae9c6b7ab72f90476cff8c7fcdfe073ed4e49c0f74bca3c03f7",
    "ForeverClientProvisioner.kt": "98c25790d3d126ec9bbf2ead1eb2ba976abece91dfa11511c77d09ed4082122d",
    "ForeverHostSafetyTest.kt": "4698d034e0f0cacc20528641c4a702db2df51aefa8e08891e93017ccf98c6d45",
    "ForeverLaunchController.kt": "8e89d2b0a79af9c6d6470cbf7897fdcb3941437febac96cd819a48258fdd1dac",
    "ForeverProcessDiagnostics.kt": "8011dfbc1b88751fd655745a76ed888a2d2c8aa80a087d243def776196a9299e",
    "ForeverRunStore.kt": "b6b699cbe5cdca69d08409424bae2b66433a34890ea4af1f386b300a99e9d744",
    "ForeverFunctionalClientStorage.kt": "23aabcabc3eda09705ed19c433ce565f64baffddf9188f4caa3ba28b391f8189",
}
REVIEWED_FUNCTIONAL_SOURCE = (
    "ForeverFunctionalClientStorage.kt",
    Path("app/src/main/java/app/gamenative/ForeverFunctionalClientStorage.kt"),
)
PREVALIDATION_MANIFEST_REPAIR = (
    Path("app/src/main/AndroidManifest.xml"),
    "3a2377c5001323e7dfdcbe79d1eaf161c58137da0100530c9ad49b7737c3c652",
)
PREVALIDATION_GRADLE_ASSET_REPAIR = (
    Path("app/build.gradle.kts"),
    "983615e66019e3d8727a01b0f1674529c9bf8bdb62289282e198a155e6477e35",
)
PREVALIDATION_SOURCE_REPAIRS = (PREVALIDATION_MANIFEST_REPAIR, PREVALIDATION_GRADLE_ASSET_REPAIR)


class InputBlockedError(ValueError):
    """An approved input is not available or has not been reconciled."""


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def _stable_sha256(path: Path, *, max_bytes: int | None = None) -> tuple[int, str]:
    """Hash one regular file through an O_NOFOLLOW descriptor and re-stat it."""
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags)
    try:
        before = os.fstat(fd)
        if not stat_is_regular(before.st_mode) or (max_bytes is not None and before.st_size > max_bytes):
            raise ValueError(f"input is not a bounded regular file: {path}")
        digest = hashlib.sha256()
        total = 0
        while True:
            data = os.read(fd, 1024 * 1024)
            if not data:
                break
            total += len(data)
            if max_bytes is not None and total > max_bytes:
                raise ValueError(f"input exceeds bound: {path}")
            digest.update(data)
        after = os.fstat(fd)
        if (before.st_dev, before.st_ino, before.st_size) != (after.st_dev, after.st_ino, after.st_size):
            raise ValueError(f"input changed while hashing: {path}")
        return total, digest.hexdigest()
    finally:
        os.close(fd)


@contextmanager
def _stable_zip(path: Path, *, max_bytes: int | None = None) -> Iterator[zipfile.ZipFile]:
    """Open a ZIP through a held no-follow FD and reject replacement/mutation."""
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags)
    try:
        before = os.fstat(fd)
        if not stat_is_regular(before.st_mode) or (max_bytes is not None and before.st_size > max_bytes):
            raise ValueError(f"input is not a bounded regular file: {path}")
        with os.fdopen(os.dup(fd), "rb") as stream:
            with zipfile.ZipFile(stream) as archive:
                yield archive
        after = os.fstat(fd)
        if (before.st_dev, before.st_ino, before.st_size) != (after.st_dev, after.st_ino, after.st_size):
            raise ValueError(f"input changed while reading ZIP: {path}")
    finally:
        os.close(fd)


def stat_is_regular(mode: int) -> bool:
    return (mode & 0o170000) == 0o100000


def load_code59_source_baseline(path: Path = SOURCE_BASELINE) -> dict[str, object]:
    size, digest = _stable_sha256(path, max_bytes=2 * 1024 * 1024)
    if (size, digest) != (SOURCE_BASELINE_BYTES, SOURCE_BASELINE_SHA256):
        raise ValueError("code59 source baseline manifest pin mismatch")
    value = json.loads(path.read_bytes().decode("utf-8"))
    if value.get("version59") != {"code": 59, "name": BASELINE_SOURCE_NAME} or value.get("changedPathReasons", {}).keys() != {
        "app/build.gradle.kts", "app/src/main/AndroidManifest.xml", "app/src/main/java/app/gamenative/MainActivity.kt",
        "app/src/main/java/app/gamenative/PluviaApp.kt", "app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt",
        "app/src/main/java/com/winlator/container/Container.java", "app/src/main/java/com/winlator/core/DXVKHelper.java",
        "app/src/main/java/com/winlator/core/ProcessHelper.java", "app/src/main/java/com/winlator/core/WineInfo.java",
        "app/src/main/java/com/winlator/core/WineUtils.java", "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java",
        "app/src/main/res/values/strings.xml"}:
        raise ValueError("code59 baseline manifest fields are incomplete")
    if value.get("sourceArchiveSHA256") != "9151ca5a20168a8901797d7fcc2946706f57532088632527876efa2931bdc361":
        raise ValueError("code59 source archive authority is unexpected")
    if value.get("overlayAuthority", {}).get("counts") != {"derived": 1, "expected": 63, "literal": 62, "matched": 63}:
        raise ValueError("code59 overlay authority counts are unexpected")
    return value


def validate_source_archive(baseline: dict[str, object]) -> Path:
    archive = Path(str(baseline["sourceArchivePath"]))
    size, digest = _stable_sha256(archive, max_bytes=2 * 1024 * 1024 * 1024)
    if digest != baseline["sourceArchiveSHA256"] or digest != "9151ca5a20168a8901797d7fcc2946706f57532088632527876efa2931bdc361":
        raise ValueError("pinned GameNative source archive changed")
    return archive


def _assert_regular_ancestors(root: Path, target: Path) -> None:
    """Check every existing ancestor without following a symlink."""
    root_stat = os.lstat(root)
    if not stat_is_directory(root_stat.st_mode):
        raise ValueError(f"source root is not a directory: {root}")
    relative = target.relative_to(root)
    current = root
    for part in relative.parts[:-1]:
        current /= part
        try:
            item = os.lstat(current)
        except FileNotFoundError:
            break
        if not stat_is_directory(item.st_mode):
            raise ValueError(f"non-directory or symlink ancestor: {current}")


def _mkdir_regular_ancestors(root: Path, target: Path) -> None:
    _assert_regular_ancestors(root, target)
    current = root
    for part in target.relative_to(root).parts[:-1]:
        current /= part
        try:
            item = os.lstat(current)
        except FileNotFoundError:
            os.mkdir(current, 0o755)
            continue
        if not stat_is_directory(item.st_mode):
            raise ValueError(f"non-directory or symlink ancestor: {current}")


def stat_is_directory(mode: int) -> bool:
    return (mode & 0o170000) == 0o40000


def _zip_member_hash(archive: zipfile.ZipFile, info: zipfile.ZipInfo) -> tuple[int, str]:
    digest = hashlib.sha256()
    total = 0
    with archive.open(info) as stream:
        while True:
            data = stream.read(1024 * 1024)
            if not data:
                break
            total += len(data)
            digest.update(data)
    return total, digest.hexdigest()


def _source_payload_path(member: str) -> Path:
    if member.startswith("assets/"):
        return Path("app/src/main") / member
    if member.startswith("lib/arm64-v8a/"):
        return Path("app/src/main/jniLibs") / member.removeprefix("lib/")
    raise ValueError(f"unsupported baseline APK payload: {member}")


def _source_archive_payload_authority(archive_path: Path) -> dict[Path, str]:
    """Return only archive-authenticated upstream asset/JNI preimages."""
    authority: dict[Path, str] = {}
    with _stable_zip(archive_path, max_bytes=2 * 1024 * 1024 * 1024) as archive:
        members = [info for info in archive.infolist() if info.filename.endswith("/app/build.gradle.kts")]
        if len(members) != 1:
            raise ValueError("source archive must contain exactly one app/build.gradle.kts")
        prefix = members[0].filename[:-len("app/build.gradle.kts")]
        for info in archive.infolist():
            if info.is_dir() or not info.filename.startswith(prefix + "app/src/main/"):
                continue
            relative = Path(info.filename[len(prefix):])
            if not (relative.parts[3:4] == ("assets",) or relative.parts[3:4] == ("jniLibs",)):
                continue
            if relative in authority:
                raise ValueError(f"duplicate source archive payload path: {relative}")
            _size, authority[relative] = _zip_member_hash(archive, info)
    return authority


def _transform_gradle_to(text: str, target_code: int, target_name: str) -> str:
    code, name, already = resolve_release_version(text)
    if already and (code, name) == (target_code, target_name):
        return text
    if (code, name) not in ((59, BASELINE_SOURCE_NAME), (SOURCE_CODE, SOURCE_NAME),
                            (FRESH_SOURCE_CODE, FRESH_SOURCE_NAME), (60, "0.2.36-automated"),
                            (PRIOR_WINE_CODE, PRIOR_WINE_NAME)):
        raise ValueError("candidate version collision")
    text = re.sub(r"\bversionCode\s*=\s*\d+\b", f"versionCode = {target_code}", text, count=1)
    text = re.sub(r'\bversionName\s*=\s*"[^"]+"', f'versionName = "{target_name}"', text, count=1)
    if "useLegacyPackaging" not in text:
        text = text.replace("android {", "android {\n    packaging { jniLibs { useLegacyPackaging = true } }", 1)
    keep = 'keepDebugSymbols += setOf("**/libforever_wine_supervisor.so", "**/libforever_wine_prefix.so")'
    if keep not in text:
        text = text.replace("android {", f'android {{\n    packaging {{ jniLibs {{ {keep} }} }}', 1)
    # Main is inherited; registering main alongside modern in one source set
    # gives replayed redirect libraries equal priority and duplicate errors.
    old_assets = 'srcDirs("src/modern/assets", "src/main/assets")'
    if text.count(old_assets) == 2:
        text = text.replace(old_assets, 'srcDirs("src/modern/assets")')
    elif text.count('srcDirs("src/modern/assets")') != 2:
        raise ValueError("modern asset source-set anchors changed")
    return text


def _transform_gradle(text: str) -> str:
    return _transform_gradle_to(text, TARGET_VERSION_CODE, TARGET_VERSION_NAME)


def _code60_postimages(baseline: dict[str, object]) -> dict[Path, bytes]:
    """Calculate the retained candidate60 source postimages without writing."""
    result: dict[Path, bytes] = {}
    for entry in baseline["entries"]:
        relative = Path(str(entry["path"]))
        data = str(entry["utf8Postimage"]).encode("utf-8")
        if relative.name == "build.gradle.kts":
            data = _transform_gradle_to(data.decode("utf-8"), 60, "0.2.36-automated").encode("utf-8")
        elif relative.name == "AndroidManifest.xml":
            data = _transform_wine_manifest(data.decode("utf-8")).encode("utf-8")
        elif relative.name == "PluviaApp.kt":
            data = _transform_wine_application(data.decode("utf-8")).encode("utf-8")
        result[relative] = data
    return result


def _code61_postimages(baseline: dict[str, object]) -> dict[Path, bytes]:
    result = _code60_postimages(baseline)
    gradle = Path("app/build.gradle.kts")
    result[gradle] = _transform_gradle_to(result[gradle].decode("utf-8"), TARGET_VERSION_CODE, TARGET_VERSION_NAME).encode("utf-8")
    return result


def _prior_code61_postimages(baseline: dict[str, object]) -> dict[Path, bytes]:
    result = _code60_postimages(baseline)
    gradle = Path("app/build.gradle.kts")
    result[gradle] = _transform_gradle_to(result[gradle].decode("utf-8"), PRIOR_WINE_CODE, PRIOR_WINE_NAME).encode("utf-8")
    return result


def _source_file(path: Path) -> bytes:
    if path.is_symlink():
        raise ValueError(f"source path is a symlink: {path}")
    return path.read_bytes()


def validate_code59_source_tree(source: Path, baseline: dict[str, object], archive: Path | None = None) -> list[tuple[Path, bytes]]:
    """Preflight fresh, retained-code59, and exact recipe-code60 source states."""
    changed = {Path(str(entry["path"])): entry for entry in baseline["entries"]}
    code60 = _code60_postimages(baseline)
    code61 = _code61_postimages(baseline)
    prior61 = _prior_code61_postimages(baseline)
    if archive is not None:
        with _stable_zip(archive, max_bytes=2 * 1024 * 1024 * 1024) as bundle:
            members = [info for info in bundle.infolist() if info.filename.endswith("/app/build.gradle.kts")]
            if len(members) != 1:
                raise ValueError("source archive must contain exactly one app/build.gradle.kts")
            prefix = members[0].filename[:-len("app/build.gradle.kts")]
            upstream_paths: dict[Path, str] = {}
            for info in bundle.infolist():
                if not info.filename.startswith(prefix + "app/") or info.is_dir():
                    continue
                relative = Path(info.filename[len(prefix):])
                if any(part in {"assets", "jniLibs", "build"} for part in relative.parts):
                    continue
                if relative in upstream_paths:
                    raise ValueError(f"duplicate source archive path: {relative}")
                _size, upstream_paths[relative] = _zip_member_hash(bundle, info)
            expected_files = baseline["baseInventory"]["upstreamFiles"]
            if len(upstream_paths) != expected_files:
                raise ValueError("pinned source archive app inventory count differs")
            for relative, expected in upstream_paths.items():
                target = source / relative
                _assert_regular_ancestors(source, target)
                if target.is_symlink() or not target.is_file():
                    raise ValueError(f"source inventory path is not regular: {relative}")
                _size, digest = _stable_sha256(target, max_bytes=2 * 1024 * 1024)
                allowed = {expected}
                if relative in changed:
                    allowed.add(changed[relative]["target59SHA256"])
                    if relative in code60:
                        allowed.add(hashlib.sha256(code60[relative]).hexdigest())
                    if relative in code61:
                        allowed.add(hashlib.sha256(code61[relative]).hexdigest())
                    if relative in prior61:
                        allowed.add(hashlib.sha256(prior61[relative]).hexdigest())
                for repair_path, repair_digest in PREVALIDATION_SOURCE_REPAIRS:
                    if relative == repair_path:
                        allowed.add(repair_digest)
                if digest not in allowed:
                    raise ValueError(f"source inventory mismatch: {relative}")

    plan: list[tuple[Path, bytes]] = []
    for entry in changed.values():
        relative = Path(str(entry["path"]))
        target = source / relative
        _assert_regular_ancestors(source, target)
        if target.is_symlink() or not target.is_file():
            raise ValueError(f"source path is not regular: {relative}")
        _size, digest = _stable_sha256(target, max_bytes=2 * 1024 * 1024)
        allowed = {entry["upstreamSHA256"], entry["target59SHA256"]}
        if relative in code60:
            allowed.add(hashlib.sha256(code60[relative]).hexdigest())
        if relative in code61:
            allowed.add(hashlib.sha256(code61[relative]).hexdigest())
        if relative in prior61:
            allowed.add(hashlib.sha256(prior61[relative]).hexdigest())
        for repair_path, repair_digest in PREVALIDATION_SOURCE_REPAIRS:
            if relative == repair_path:
                allowed.add(repair_digest)
        if digest not in allowed:
            raise ValueError(f"unknown code59 source state: {relative}")
        postimage = str(entry["utf8Postimage"]).encode("utf-8")
        if len(postimage) != entry["target59Bytes"] or hashlib.sha256(postimage).hexdigest() != entry["target59SHA256"]:
            raise ValueError(f"invalid code59 postimage authority: {relative}")
        plan.append((target, postimage))
    return plan


def _arm64_diagnostics_postimage(data: bytes) -> bytes:
    text = data.decode("utf-8")
    replacements = (
        ("context.assets.open(\"$APP_ASSET_ROOT/$path\").use { input ->",
         "context.assets.open(if (path.startsWith(\"runtime/\")) \"forever/$path\" else \"$APP_ASSET_ROOT/$path\").use { input ->"),
        ("context.assets.open(\"$APP_ASSET_ROOT/$path\").use { peMachine(it.readBytes()) }",
         "context.assets.open(if (path.startsWith(\"runtime/\")) \"forever/$path\" else \"$APP_ASSET_ROOT/$path\").use { peMachine(it.readBytes()) }"),
        ("context.assets.open(asset).use { input -> destination.outputStream().use(input::copyTo) }",
         "context.assets.open(if (asset.startsWith(\"arm64-diagnostics/runtime/\")) asset.replaceFirst(\"arm64-diagnostics/\", \"forever/\") else asset).use { input -> destination.outputStream().use(input::copyTo) }"),
    )
    for old, new in replacements:
        if text.count(old) != 1:
            raise ValueError(f"Arm64Diagnostics transformation anchor count is not one: {old}")
        text = text.replace(old, new)
    return text.encode("utf-8")


def validate_overlay_authority(baseline: dict[str, object]) -> dict[Path, bytes]:
    """Validate the pinned baseline bindings and the exact reviewed storage source."""
    root = Path(__file__).parents[3]
    output: dict[Path, bytes] = {}
    bindings = baseline["overlayAuthority"]["bindings"]
    if len(bindings) != 63:
        raise ValueError("overlay binding count is not 63")
    for binding in bindings:
        source = root / str(binding["sourceInputPath"])
        raw = _source_file(source)
        source_digest = hashlib.sha256(raw).hexdigest()
        name = str(binding["name"])
        approved_delta_sha256 = APPROVED_REVIEWED_OVERLAY_DELTAS.get(name)
        if approved_delta_sha256 is not None:
            expected_source = root / "scripts/arm64-prototype/app/overlay" / name
            if source != expected_source or source_digest != approved_delta_sha256:
                raise ValueError(f"reviewed overlay delta pin mismatch: {name}")
            # Keep the additional semantic checks for the previously approved
            # Wine Store/controller replacements; Home/Wi-Fi and privacy deltas
            # are admitted solely by their exact reviewed content hashes.
            if name in APPROVED_WINE_OVERLAY_DELTAS:
                text = raw.decode("utf-8")
                valid_delta = ("beginWine" in text if name == "ForeverRunStore.kt"
                               else "recoverActiveRun(" not in text and "class ForeverLaunchController" in text)
                if not valid_delta:
                    raise ValueError("Wine admission delta has an invalid source boundary")
            output[Path(str(binding["path"]))] = raw
            continue
        current_pin = binding["currentRepoSourceSHA256"]
        if source_digest != current_pin:
            raise ValueError(f"overlay source pin mismatch: {binding['name']}")
        generated = _arm64_diagnostics_postimage(raw) if binding["name"] == "Arm64Diagnostics.kt" else raw
        expected_digest = binding["generatedSHA256"] if binding.get("currentRepoSourceMatchesHistoricalInput", True) else current_pin
        if len(generated) != (binding["generatedBytes"] if binding.get("currentRepoSourceMatchesHistoricalInput", True) else len(raw)) or hashlib.sha256(generated).hexdigest() != expected_digest:
            raise ValueError(f"overlay generated pin mismatch: {binding['name']}")
        output[Path(str(binding["path"]))] = generated
    storage_name, storage_target = REVIEWED_FUNCTIONAL_SOURCE
    storage_source = root / "scripts/arm64-prototype/app/overlay" / storage_name
    storage_data = _source_file(storage_source)
    if hashlib.sha256(storage_data).hexdigest() != APPROVED_REVIEWED_OVERLAY_DELTAS[storage_name]:
        raise ValueError(f"reviewed overlay delta pin mismatch: {storage_name}")
    output[storage_target] = storage_data
    return output


def validate_new_wine_overlay_sources(pinned: dict[Path, bytes]) -> dict[Path, bytes]:
    """Load only the eight approved Wine60 additions outside the 63 baseline bindings."""
    overlay = Path(__file__).with_name("overlay")
    names = set(NEW_MAIN_SOURCES) | set(NEW_TEST_SOURCES)
    baseline_names = {path.name for path in pinned}
    result = dict(pinned)
    for name in sorted(names - baseline_names):
        source = overlay / name
        if not source.is_file() or source.is_symlink():
            raise InputBlockedError(f"reviewed Wine60 source missing: {source}")
        data = source.read_bytes()
        relative = Path("app/src/test/java/app/gamenative" if name.endswith("Test.kt") else "app/src/main/java/app/gamenative") / name
        result[relative] = data
    return result


def apply_code59_source_baseline(plan: list[tuple[Path, bytes]], source: Path, baseline: dict[str, object]) -> None:
    entries = {Path(str(entry["path"])): entry for entry in baseline["entries"]}
    code60 = _code60_postimages(baseline)
    code61 = _code61_postimages(baseline)
    prior61 = _prior_code61_postimages(baseline)
    for target, data in plan:
        relative = target.relative_to(source)
        entry = entries.get(relative)
        if entry is None:
            raise ValueError(f"baseline plan path is not manifest-authorized: {relative}")
        current = target.read_bytes()
        if current == data:
            continue
        current_digest = hashlib.sha256(current).hexdigest()
        if (relative, current_digest) in PREVALIDATION_SOURCE_REPAIRS:
            # Restore only exact diagnosed unpublished outputs from the
            # authenticated baseline, then render the bounded correction.
            target.write_bytes(data)
            continue
        if current_digest == hashlib.sha256(code60[relative]).hexdigest():
            continue
        if current_digest == hashlib.sha256(code61[relative]).hexdigest():
            continue
        if current_digest == hashlib.sha256(prior61[relative]).hexdigest():
            continue
        if current_digest != entry["upstreamSHA256"]:
            raise ValueError(f"baseline destination collision: {target}")
        target.write_bytes(data)


def _approved_overlay_preimages(baseline: dict[str, object]) -> dict[Path, set[str]]:
    allowed: dict[Path, set[str]] = {}
    for binding in baseline["overlayAuthority"]["bindings"]:
        relative = Path(str(binding["path"]))
        allowed[relative] = {str(binding["generatedSHA256"]), str(binding["currentRepoSourceSHA256"]), str(binding["sourceInputSHA256"])}
    return allowed


def authenticated_prior_source_outputs(source: Path, expected_sha256: str | None,
                                       payloads: dict[Path, bytes]) -> dict[Path, set[str]]:
    """Authorize edits only against independently pinned, previous lab output."""
    if expected_sha256 is None:
        return {}
    if re.fullmatch(r"[0-9a-f]{64}", expected_sha256) is None:
        raise ValueError("prior source provenance requires an independent SHA-256")
    path = source / "app/src/main/assets/forever/provenance.json"
    _assert_regular_ancestors(source, path)
    size, digest = _stable_sha256(path, max_bytes=256 * 1024)
    raw = _source_file(path)
    if len(raw) != size or hashlib.sha256(raw).hexdigest() != digest or digest != expected_sha256:
        raise ValueError("prior source provenance changed or is not independently pinned")
    value = json.loads(raw)
    if (value.get("versionCode"), value.get("versionName")) not in {
            (60, "0.2.36-automated"), (PRIOR_WINE_CODE, PRIOR_WINE_NAME), (TARGET_VERSION_CODE, TARGET_VERSION_NAME)}:
        raise ValueError("prior source provenance is not candidate60/61/62")
    authority = value.get("sourceBaseline", {})
    if authority.get("manifestSHA256") != SOURCE_BASELINE_SHA256:
        raise ValueError("prior source provenance has a foreign baseline")
    outputs = authority.get("overlayBindings", {}).get("generatedSHA256", {})
    expected_scope = {str(path) for path in payloads}
    if value.get("versionCode") == 60:
        expected_scope -= {
            "app/src/main/java/app/gamenative/ForeverInterruptedRunDisposition.kt",
            "app/src/test/java/app/gamenative/ForeverInterruptedRunDispositionTest.kt",
        }
    if value.get("versionCode") in {60, PRIOR_WINE_CODE}:
        expected_scope -= {
            "app/src/main/java/app/gamenative/ForeverPrelaunchContinuation.kt",
            "app/src/test/java/app/gamenative/ForeverPrelaunchContinuationTest.kt",
        }
    if not isinstance(outputs, dict) or set(outputs) != expected_scope:
        raise ValueError("prior source output scope differs")
    result = {}
    for relative, digest in outputs.items():
        if not isinstance(digest, str) or re.fullmatch(r"[0-9a-f]{64}", digest) is None:
            raise ValueError("invalid prior source output digest")
        result[Path(relative)] = {digest}
    return result


def validate_materialization_destinations(source: Path, payloads: dict[Path, bytes], baseline: dict[str, object],
                                          prior_outputs: dict[Path, set[str]] | None = None) -> None:
    """Reject all recipe-owned destination collisions before the first write."""
    approved = _approved_overlay_preimages(baseline)
    for relative, digests in (prior_outputs or {}).items():
        if relative not in payloads:
            raise ValueError("prior output is outside the recipe-owned source scope")
        approved.setdefault(relative, set()).update(digests)
    for relative, data in payloads.items():
        target = source / relative
        _assert_regular_ancestors(source, target)
        try:
            item = os.lstat(target)
        except FileNotFoundError:
            continue
        if not stat_is_regular(item.st_mode):
            raise ValueError(f"recipe-owned destination collision: {target}")
        _size, digest = _stable_sha256(target, max_bytes=2 * 1024 * 1024 * 1024)
        if digest != hashlib.sha256(data).hexdigest() and digest not in approved.get(relative, set()):
            raise ValueError(f"recipe-owned destination collision: {target}")


def authenticated_prior_wine_resources(source: Path, expected_sha256: str | None) -> dict[Path, set[str]]:
    """Authorize only the two unreleased Wine native outputs against the same pinned receipt."""
    if expected_sha256 is None:
        return {}
    path = source / "app/src/main/assets/forever/provenance.json"
    _assert_regular_ancestors(source, path)
    raw = _source_file(path)
    if hashlib.sha256(raw).hexdigest() != expected_sha256:
        raise ValueError("prior native resource provenance changed")
    value = json.loads(raw)
    if value.get("versionCode") not in {60, PRIOR_WINE_CODE, TARGET_VERSION_CODE} or value.get("sourceBaseline", {}).get("manifestSHA256") != SOURCE_BASELINE_SHA256:
        raise ValueError("prior native resource provenance is foreign")
    result = {}
    for relative, key, packaged in (
        ("app/src/main/assets/forever/wine-supervisor/manifest.json", "forever/wine-supervisor/manifest.json", "assets/forever/wine-supervisor/manifest.json"),
        ("app/src/main/jniLibs/arm64-v8a/libforever_wine_supervisor.so", "lib/arm64-v8a/libforever_wine_supervisor.so", "lib/arm64-v8a/libforever_wine_supervisor.so"),
    ):
        item = value.get("resources", {}).get(key, {})
        digest = item.get("fullSHA256")
        if not isinstance(digest, str) or re.fullmatch(r"[0-9a-f]{64}", digest) is None or item.get("packagedPath") != packaged:
            raise ValueError("prior Wine native resource lacks exact provenance")
        result[Path(relative)] = {digest}
    return result


def validate_exact_destinations(source: Path, payloads: dict[Path, bytes],
                                allowed_existing: dict[Path, set[str]] | None = None) -> None:
    """Preflight exact new resource destinations, rejecting unknown content."""
    for relative, data in payloads.items():
        target = source / relative
        _assert_regular_ancestors(source, target)
        try:
            item = os.lstat(target)
        except FileNotFoundError:
            continue
        if not stat_is_regular(item.st_mode):
            raise ValueError(f"generated destination collision: {target}")
        _size, digest = _stable_sha256(target, max_bytes=512 * 1024 * 1024)
        if digest != hashlib.sha256(data).hexdigest() and digest not in (allowed_existing or {}).get(relative, set()):
            raise ValueError(f"generated destination collision: {target}")


def validate_exact_file_destinations(source: Path, payloads: dict[Path, tuple[int, str]]) -> None:
    for relative, (expected_size, expected_digest) in payloads.items():
        target = source / relative
        _assert_regular_ancestors(source, target)
        try:
            item = os.lstat(target)
        except FileNotFoundError:
            continue
        if not stat_is_regular(item.st_mode):
            raise ValueError(f"generated destination collision: {target}")
        actual_size, actual_digest = _stable_sha256(target, max_bytes=512 * 1024 * 1024)
        if (actual_size, actual_digest) != (expected_size, expected_digest):
            raise ValueError(f"generated destination collision: {target}")


def validate_retained_baseline_apk(baseline: dict[str, object], override: Path | None = None) -> dict[str, object]:
    apk = override or Path(str(baseline["baselineAPK"]["path"]))
    size, digest = _stable_sha256(apk, max_bytes=512 * 1024 * 1024)
    if digest != RETAINED_BASELINE_APK_SHA256 or digest != baseline["baselineAPK"]["sha256"]:
        raise ValueError("retained code59 APK changed")
    members: dict[str, tuple[int, str]] = {}
    with _stable_zip(apk, max_bytes=512 * 1024 * 1024) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("retained code59 APK has duplicate ZIP entries")
        expected: dict[str, str] = {}
        for group in baseline["inputGroups"].values():
            expected.update(group["sha256"])
        for name in names:
            if Path(name).is_absolute() or ".." in Path(name).parts or ((archive.getinfo(name).external_attr >> 16) & 0o170000) == 0o120000:
                raise ValueError(f"retained code59 APK has unsafe ZIP member: {name}")
        for name, digest in expected.items():
            if name not in names:
                raise ValueError(f"retained code59 APK member changed: {name}")
            info = archive.getinfo(name)
            member_size, member_digest = _zip_member_hash(archive, info)
            if member_digest != digest:
                raise ValueError(f"retained code59 APK member changed: {name}")
            members[name] = (member_size, member_digest)
        if set(name for name in names if name.startswith("assets/")) != {name for name in expected if name.startswith("assets/")}:
            raise ValueError("retained code59 APK asset inventory differs")
        if set(name for name in names if name.startswith("lib/")) != {name for name in expected if name.startswith("lib/")}:
            raise ValueError("retained code59 APK native inventory differs")
    return {"path": apk, "members": members}


def _baseline_replay_destinations(members: dict[str, tuple[int, str]]) -> dict[Path, tuple[int, str]]:
    result: dict[Path, tuple[int, str]] = {}
    for member, pin in members.items():
        destination = _source_payload_path(member)
        if destination in result:
            raise ValueError(f"duplicate baseline replay destination: {destination}")
        result[destination] = pin
    return result


def validate_baseline_replay_destinations(
    source: Path,
    members: dict[str, tuple[int, str]],
    source_authority: dict[Path, str] | None = None,
) -> dict[Path, tuple[int, str]]:
    """Preflight every retained APK payload before any source mutation."""
    destinations = _baseline_replay_destinations(members)
    source_authority = source_authority or {}
    for relative, (_size, expected_digest) in destinations.items():
        target = source / relative
        _assert_regular_ancestors(source, target)
        try:
            item = os.lstat(target)
        except FileNotFoundError:
            continue
        if not stat_is_regular(item.st_mode):
            raise ValueError(f"baseline replay destination collision: {target}")
        _actual_size, actual_digest = _stable_sha256(target, max_bytes=512 * 1024 * 1024)
        if actual_digest == expected_digest:
            continue
        if relative == Path("app/src/main/assets/forever/provenance.json"):
            validate_provenance_destination(target)
            continue
        upstream_digest = source_authority.get(relative)
        if upstream_digest != actual_digest:
            raise ValueError(f"baseline replay destination collision: {target}")
    return destinations


def _stream_zip_member_to_path(source: Path, archive: zipfile.ZipFile, info: zipfile.ZipInfo, target: Path,
                               expected_size: int, expected_digest: str) -> None:
    _mkdir_regular_ancestors(source, target)
    # The caller has already checked the source root and all collision states.
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(target, flags, 0o644)
    try:
        digest = hashlib.sha256()
        total = 0
        with archive.open(info) as stream:
            while True:
                data = stream.read(1024 * 1024)
                if not data:
                    break
                view = memoryview(data)
                while view:
                    view = view[os.write(fd, view):]
                total += len(data)
                digest.update(data)
        if (total, digest.hexdigest()) != (expected_size, expected_digest):
            raise ValueError(f"retained APK member changed while replaying: {info.filename}")
    finally:
        os.close(fd)


def materialize_baseline_payloads(source: Path, retained: dict[str, object],
                                  source_authority: dict[Path, str] | None = None) -> None:
    """Replay authenticated assets/JNI streams into the source tree, idempotently."""
    members = retained["members"]
    destinations = validate_baseline_replay_destinations(source, members, source_authority)
    apk = Path(str(retained["path"]))
    with _stable_zip(apk, max_bytes=512 * 1024 * 1024) as archive:
        for member, (expected_size, expected_digest) in members.items():
            relative = _source_payload_path(member)
            target = source / relative
            try:
                item = os.lstat(target)
            except FileNotFoundError:
                item = None
            if item is not None and stat_is_regular(item.st_mode):
                _actual_size, actual_digest = _stable_sha256(target, max_bytes=512 * 1024 * 1024)
                if actual_digest == expected_digest:
                    continue
            info = archive.getinfo(member)
            _stream_zip_member_to_path(source, archive, info, target, expected_size, expected_digest)


def _version_pairs() -> dict[tuple[int, str], bool]:
    return {(59, BASELINE_SOURCE_NAME): False, (SOURCE_CODE, SOURCE_NAME): False,
            (FRESH_SOURCE_CODE, FRESH_SOURCE_NAME): False, (PRIOR_WINE_CODE, PRIOR_WINE_NAME): False,
            (TARGET_VERSION_CODE, TARGET_VERSION_NAME): True}


def resolve_release_version(gradle_text: str) -> tuple[int, str, bool]:
    code_match = re.search(r"\bversionCode\s*=\s*(\d+)\b", gradle_text)
    name_match = re.search(r'\bversionName\s*=\s*"([^"]+)"', gradle_text)
    if not code_match or not name_match:
        raise ValueError("versionCode/versionName anchors are missing")
    pair = (int(code_match.group(1)), name_match.group(1))
    if pair not in _version_pairs():
        if pair[0] == TARGET_VERSION_CODE or pair[1] == TARGET_VERSION_NAME:
            raise ValueError("unknown versionCode/versionName collision with candidate 62")
        raise ValueError(f"unknown or mismatched source version collision: code={pair[0]} name={pair[1]}")
    return pair[0], pair[1], _version_pairs()[pair]


def _map_virtual_address(loads: list[tuple[int, int, int, int]], address: int) -> int:
    for offset, virtual, filesz, _flags in loads:
        if virtual <= address < virtual + filesz:
            return offset + address - virtual
    raise ValueError("ELF virtual address is not backed by a LOAD segment")


def inspect_native_elf(path: Path) -> dict[str, object]:
    """Validate the complete production ELF without executing it."""
    data = path.read_bytes()
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError(f"{path.name}: not a little-endian ELF64")
    elf_type, machine = struct.unpack_from("<HH", data, 16)
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 54)
    if elf_type != ELF_TYPE_DYN or machine != ELF_MACHINE_AARCH64:
        raise ValueError(f"{path.name}: expected AA64 ET_DYN PIE")
    if phentsize != 56 or phoff + phentsize * phnum > len(data):
        raise ValueError(f"{path.name}: invalid program-header table")
    loads: list[tuple[int, int, int, int]] = []
    interpreter: bytes | None = None
    dynamic: tuple[int, int] | None = None
    for index in range(phnum):
        base = phoff + index * phentsize
        p_type, flags, offset, virtual, _paddr, filesz, _memsz, align = struct.unpack_from("<IIQQQQQQ", data, base)
        if offset + filesz > len(data):
            raise ValueError(f"{path.name}: program segment exceeds file")
        if p_type == 1:
            if align != PAGE_16K or offset % PAGE_16K != virtual % PAGE_16K:
                raise ValueError(f"{path.name}: LOAD segment is not 16KiB aligned")
            if (flags & 3) == 3:
                raise ValueError(f"{path.name}: writable executable LOAD segment")
            loads.append((offset, virtual, filesz, flags))
        elif p_type == 3:
            interpreter = data[offset:offset + filesz].rstrip(b"\0")
        elif p_type == 2:
            dynamic = (offset, filesz)
    if not loads or interpreter != b"/system/bin/linker64":
        raise ValueError(f"{path.name}: missing /system/bin/linker64 or LOAD segment")
    needed: list[str] = []
    if dynamic:
        dynamic_offset, dynamic_size = dynamic
        if dynamic_size % 16:
            raise ValueError(f"{path.name}: malformed PT_DYNAMIC")
        strtab = strsz = None
        entries: list[tuple[int, int]] = []
        for offset in range(dynamic_offset, dynamic_offset + dynamic_size, 16):
            tag, value = struct.unpack_from("<QQ", data, offset)
            entries.append((tag, value))
            if tag == 0:
                break
            if tag == 5:
                strtab = value
            elif tag == 10:
                strsz = value
        if strtab is None or strsz is None:
            raise ValueError(f"{path.name}: missing dynamic string table")
        string_offset = _map_virtual_address(loads, strtab)
        if string_offset + strsz > len(data):
            raise ValueError(f"{path.name}: dynamic string table exceeds file")
        strings = data[string_offset:string_offset + strsz]
        for tag, value in entries:
            if tag == 1:
                if value >= len(strings):
                    raise ValueError(f"{path.name}: invalid DT_NEEDED string offset")
                needed.append(strings[value:].split(b"\0", 1)[0].decode("ascii"))
            elif tag in (15, 29):
                raise ValueError(f"{path.name}: runtime search path is not libc-only")
    if set(needed) - {"libc.so"}:
        raise ValueError(f"{path.name}: non-libc dependency: {needed}")
    return {"file": path.name, "size": len(data), "sha256": hashlib.sha256(data).hexdigest(),
            "machine": ELF_MACHINE_AARCH64, "type": "ET_DYN", "interpreter": "/system/bin/linker64",
            "loadAlignment": PAGE_16K, "needed": needed}


def _artifact_entries(manifest: dict[str, object]) -> dict[str, dict[str, object]]:
    raw = manifest.get("artifacts")
    if not isinstance(raw, dict):
        raise ValueError("Wine supervisor manifest has no artifacts map")
    return {str(name): item for name, item in raw.items() if isinstance(item, dict)}


def validate_wine_supervisor_inputs(source: Path) -> tuple[dict[str, object], dict[str, dict[str, object]]]:
    manifest_path = source / "manifest.json"
    if not manifest_path.is_file():
        raise InputBlockedError(f"Wine supervisor manifest missing: {manifest_path}")
    if manifest_path.stat().st_size > 64 * 1024:
        raise ValueError("Wine supervisor manifest is oversized")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schema") != 1 or manifest.get("variant") != WINE_SUPERVISOR_VARIANT:
        raise ValueError("unexpected Wine supervisor manifest identity")
    if WINE_SUPERVISOR_PIN_STATUS != "READY":
        raise InputBlockedError("Wine supervisor pins are blocked")
    if sha256(manifest_path) != WINE_SUPERVISOR_MANIFEST_SHA256:
        raise ValueError("Wine supervisor manifest pin mismatch")
    for key in ("source", "recipe", "provenance"):
        if not isinstance(manifest.get(key), dict) or not manifest[key]:
            raise ValueError(f"Wine supervisor manifest missing {key} provenance")
    entries = _artifact_entries(manifest)
    if set(entries) != set(WINE_SUPERVISOR_FILES):
        raise ValueError("Wine supervisor manifest file set differs from approved package")
    for name in WINE_SUPERVISOR_FILES:
        path = source / name
        if not path.is_file() or not path.stat().st_mode & 0o111:
            raise ValueError(f"Wine supervisor ELF missing or not executable: {path}")
        inspected = inspect_native_elf(path)
        expected_size, expected_sha = WINE_SUPERVISOR_PINS[name]
        entry = entries[name]
        if (inspected["size"], inspected["sha256"]) != (expected_size, expected_sha):
            raise ValueError(f"Wine supervisor native pin mismatch: {name}")
        if entry.get("bytes", entry.get("size")) != expected_size or entry.get("sha256") != expected_sha:
            raise ValueError(f"Wine supervisor manifest pin mismatch: {name}")
        if entry.get("elfMachine", entry.get("machine")) not in (None, 183):
            raise ValueError(f"Wine supervisor ELF machine metadata mismatch: {name}")
        if entry.get("interpreter") not in (None, "/system/bin/linker64"):
            raise ValueError(f"Wine supervisor ELF interpreter metadata mismatch: {name}")
        if entry.get("loadAlignment", entry.get("loadSegmentAlignment")) not in (None, 16384):
            raise ValueError(f"Wine supervisor ELF alignment metadata mismatch: {name}")
        if entry.get("neededLibraries", entry.get("needed")) not in (None, ["libc.so"]):
            raise ValueError(f"Wine supervisor dependency metadata mismatch: {name}")
    protocol = manifest.get("protocol")
    if not isinstance(protocol, dict) or protocol.get("schema") != 2:
        raise ValueError("Wine supervisor manifest is not protocol schema 2")
    return manifest, entries


def validate_prefix_inputs(source: Path) -> tuple[dict[str, object], dict[str, Path]]:
    if PREFIX_INPUTS_STATUS != "READY":
        raise InputBlockedError("wine-prefix-v1 inputs are BLOCKED pending parent reconciliation")
    required = {name: source / name for name in PREFIX_FILES}
    missing = [str(path) for path in required.values() if not path.is_file()]
    if missing:
        raise InputBlockedError("wine-prefix-v1 inputs missing: " + ", ".join(missing))
    for name, (expected_size, expected_digest) in PREFIX_INPUT_PINS.items():
        size, digest = _stable_sha256(required[name], max_bytes=256 * 1024 * 1024)
        if digest != expected_digest or (expected_size is not None and size != expected_size):
            raise ValueError(f"independent prefix input pin mismatch: {name}")
    publisher = json.loads(required["publisher-manifest.json"].read_text(encoding="utf-8"))
    if publisher.get("schema") != "wowforever.wine-prefix-publisher/v1" or publisher.get("variant") != "wine-prefix-v1":
        raise ValueError("unexpected prefix publisher manifest identity")
    native = publisher.get("publisher")
    if not isinstance(native, dict):
        raise ValueError("prefix publisher native metadata missing")
    native_path = required["libforever_wine_prefix.so"]
    inspected = inspect_native_elf(native_path)
    if (native.get("bytes"), native.get("sha256")) != (inspected["size"], inspected["sha256"]):
        raise ValueError("prefix worker native pin mismatch")
    for key, expected in (("elfType", "ET_DYN"), ("machine", 183), ("interpreter", "/system/bin/linker64"),
                          ("loadAlignment", 16384), ("loadSegmentAlignment", 16384), ("neededLibraries", ["libc.so"])):
        if native.get(key) != expected:
            raise ValueError(f"prefix worker ELF metadata mismatch: {key}")
    image_manifest = json.loads(required["prefix-image-manifest.json"].read_text(encoding="utf-8"))
    image_pin = image_manifest.get("zip")
    if not isinstance(image_pin, dict) or image_manifest.get("schema") != "wowforever.wine-prefix-image/v1":
        raise ValueError("unexpected prefix image manifest identity")
    if image_pin.get("sha256") != sha256(required["prefix-image.zip"]):
        raise ValueError("prefix image hash mismatch")
    if image_pin.get("bytes") != required["prefix-image.zip"].stat().st_size:
        raise ValueError("prefix image size mismatch")
    if image_manifest.get("source", {}).get("templateSha256") != PREFIX_MANIFEST_SHA256:
        raise ValueError("prefix template manifest pin mismatch")
    counts = image_manifest.get("counts")
    if not isinstance(counts, dict) or any(counts.get(key) != value for key, value in {
        "regular": 411, "directories": 163, "links": 3}.items()):
        raise ValueError("prefix manifest entry counts differ from approved template")
    image_manifest_hash = sha256(required["prefix-image-manifest.json"])
    image = publisher.get("image")
    if not isinstance(image, dict) or image.get("manifestSha256") != image_manifest_hash or \
            image.get("zipSha256") != sha256(required["prefix-image.zip"]) or image.get("zipBytes") != required["prefix-image.zip"].stat().st_size:
        raise ValueError("prefix publisher/image provenance mismatch")
    if len(image_manifest.get("entries", [])) != 574 or len(image_manifest.get("links", [])) != 3:
        raise ValueError("prefix image entry inventory is not bounded")
    entries = {item.get("path"): item for item in image_manifest.get("entries", []) if isinstance(item, dict)}
    for path, (size, digest) in PREFIX_REPLACEMENT_PINS.items():
        item = entries.get(path)
        if not isinstance(item, dict) or item.get("type") != "regular" or item.get("bytes") != size or item.get("sha256") != digest:
            raise ValueError(f"prefix replacement pin mismatch: {path}")
    return publisher, required


def validate_prelaunch_disposition_source() -> bytes:
    if not PRELAUNCH_DISPOSITION_SOURCE.is_file() or sha256(PRELAUNCH_DISPOSITION_SOURCE) != PRELAUNCH_DISPOSITION_SHA256:
        raise ValueError("frozen prelaunch disposition source pin mismatch")
    data = PRELAUNCH_DISPOSITION_SOURCE.read_bytes()
    value = json.loads(data.decode("utf-8"))
    if value.get("kind") != "EXACT_HISTORICAL_PRELAUNCH_AUTHORIZATION" or value.get("priorStatus") != "UNKNOWN":
        raise ValueError("prelaunch disposition is not the frozen historical authorization")
    return data


def validate_interrupted_disposition_contract() -> bytes:
    if not INTERRUPTED_DISPOSITION_SOURCE.is_file() or sha256(INTERRUPTED_DISPOSITION_SOURCE) != INTERRUPTED_DISPOSITION_SHA256:
        raise InputBlockedError("fixed interrupted-run disposition contract is missing or changed")
    value, data = load_contract(INTERRUPTED_DISPOSITION_SOURCE)
    verify_external_evidence(value)
    prior = value.get("prior", {})
    candidate = value.get("candidate", {})
    receipt = value.get("receipt", {})
    if (prior.get("versionCode"), prior.get("versionName"), candidate.get("versionCode"),
            candidate.get("versionName")) != (60, "0.2.36-automated", 61, "0.2.37-automated"):
        raise ValueError("interrupted disposition candidate identity is not exact")
    if (receipt.get("terminalStatus"), receipt.get("closureObserved"), receipt.get("qualification"), receipt.get("echild")) != (
            "ABANDONED_ON_NEW_BOOT", False, False, False):
        raise ValueError("interrupted disposition receipt can claim closure")
    return data


def validate_continuation_disposition_contract() -> bytes:
    if not CONTINUATION_DISPOSITION_SOURCE.is_file() or sha256(CONTINUATION_DISPOSITION_SOURCE) != CONTINUATION_DISPOSITION_SHA256:
        raise InputBlockedError("candidate62 continuation contract is missing or changed")
    module_path = Path(__file__).parents[2] / "diagnostics/android-process/wine_prefix_prelaunch_continuation_contract.py"
    spec = importlib.util.spec_from_file_location("wine_prelaunch_continuation_contract", module_path)
    if spec is None or spec.loader is None:
        raise InputBlockedError("candidate62 continuation verifier is unavailable")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    value, data = module.load_contract(CONTINUATION_DISPOSITION_SOURCE)
    module.verify_external_evidence(value)
    candidate = value.get("candidate", {})
    admission = value.get("admission", {})
    if (candidate.get("versionCode"), candidate.get("versionName"), admission.get("type"), admission.get("oneShot")) != (62, "0.2.38-automated", "PRELAUNCH_ABORT", True):
        raise ValueError("continuation contract candidate identity is not exact")
    if value.get("evidence", {}).get("fixedStatusCount") != 58:
        raise ValueError("continuation contract does not authenticate all 58 status paths")
    return data


def _new_resource_paths() -> set[Path]:
    return {Path("src/main/assets/forever/wine-supervisor/manifest.json"),
            Path("src/main/assets/forever/wine-prefix/publisher-manifest.json"),
            Path("src/main/assets/forever/wine-prefix/prefix-image.zip"),
            Path("src/main/assets/forever/wine-prefix/image-manifest.json"),
            Path("src/main/jniLibs/arm64-v8a/libforever_wine_supervisor.so"),
            Path("src/main/jniLibs/arm64-v8a/libforever_wine_prefix.so"),
            *(Path("src/main/java/app/gamenative") / name for name in NEW_MAIN_SOURCES),
             *(Path("src/test/java/app/gamenative") / name for name in NEW_TEST_SOURCES),
              Path("src/main/assets/forever/provenance.json"), INTERRUPTED_DISPOSITION_ASSET,
              CONTINUATION_DISPOSITION_ASSET}


def snapshot_preserved_payloads(app: Path) -> dict[Path, str]:
    excluded = _new_resource_paths()
    result: dict[Path, str] = {}
    for root in (app / "src/main/assets", app / "src/main/jniLibs", app / "src/modern/assets"):
        if root.is_dir():
            for path in root.rglob("*"):
                if path.is_file() and path.relative_to(app) not in excluded:
                    result[path] = sha256(path)
    return result


def assert_preserved_payloads(snapshot: dict[Path, str]) -> None:
    for path, expected in snapshot.items():
        if not path.is_file() or sha256(path) != expected:
            raise ValueError(f"existing payload changed: {path}")


def _set_attr(opening: str, name: str, value: str) -> str:
    pattern = rf'\s+android:{name}="[^"]*"'
    if re.search(pattern, opening):
        return re.sub(pattern, f' android:{name}="{value}"', opening)
    if opening.endswith("/>"):
        return opening[:-2] + f' android:{name}="{value}" />'
    if opening.endswith(">"):
        return opening[:-1] + f' android:{name}="{value}">'
    raise ValueError("component opening tag is malformed")


def _transform_wine_manifest(text: str) -> str:
    if "FOREVER_WINE_SUPERVISOR" not in text:
        # Disable legacy launcher/fixture/WSI declarations without touching any
        # source implementation.  Explicit false is required for aapt auditing.
        patterns = (
            r'<activity\b([^>]*android:name="[^"]*(?:MainActivity|ForeverNativeSupervisorActivity|PanvkWsiActivity)[^"]*"[^>]*)>',
            r'<activity-alias\b([^>]*(?:targetActivity="[^"]*(?:MainActivity|ForeverNativeSupervisorActivity)[^"]*"|android:name="[^"]*ForeverNativeSupervisorLauncher[^"]*")[^>]*)>',
        )
        for pattern in patterns:
            def disable(match: re.Match[str]) -> str:
                opening = match.group(0)
                return _set_attr(opening, "enabled", "false")
            text = re.sub(pattern, disable, text)
        component = '''        <!-- FOREVER_WINE_SUPERVISOR: isolated server-only first Wine gate. -->
        <activity android:name="app.gamenative.ForeverWineSupervisorActivity"
            android:exported="true" android:enabled="true" android:permission="android.permission.DUMP"
            android:process=":wine_supervisor" android:launchMode="singleTop" />
        <activity-alias android:name="app.gamenative.ForeverWineSupervisorLauncher"
            android:targetActivity="app.gamenative.ForeverWineSupervisorActivity"
            android:exported="true" android:enabled="true" android:permission="android.permission.DUMP"
            android:label="Forever">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity-alias>
'''
        if text.count("</application>") != 1:
            raise ValueError("ambiguous application end")
        text = text.replace("</application>", component + "    </application>")
    elif text.count("FOREVER_WINE_SUPERVISOR") != 1:
        raise ValueError("ambiguous Wine supervisor manifest marker")
    app_match = re.search(r"(<application\b[^>]*)(>)", text)
    if not app_match:
        raise ValueError("application manifest anchor missing")
    opening = app_match.group(1)
    if "android:extractNativeLibs" in opening and 'android:extractNativeLibs="true"' not in opening:
        raise ValueError("native library extraction is disabled")
    if "android:extractNativeLibs" not in opening:
        text = text[:app_match.start()] + opening + ' android:extractNativeLibs="true"' + app_match.group(2) + text[app_match.end():]
    # A second pass makes a partially-applied manifest deterministic and also
    # disables old aliases introduced by a previous candidate.
    for pattern in (r'<activity\b([^>]*android:name="[^"]*(?:MainActivity|ForeverNativeSupervisorActivity|PanvkWsiActivity)[^"]*"[^>]*)>',
                    r'<activity-alias\b([^>]*(?:targetActivity="[^"]*(?:MainActivity|ForeverNativeSupervisorActivity)[^"]*"|android:name="[^"]*ForeverNativeSupervisorLauncher[^"]*")[^>]*)>'):
        text = re.sub(pattern, lambda m: m.group(0) if "ForeverWineSupervisor" in m.group(0) else _set_attr(m.group(0), "enabled", "false"), text)
    return text


def configure_wine_manifest(manifest: Path) -> None:
    manifest.write_text(_transform_wine_manifest(manifest.read_text(encoding="utf-8")), encoding="utf-8")


def _kotlin_sources() -> dict[str, str]:
    """Load the reviewed overlay sources; never synthesize a Kotlin fallback."""
    overlay = Path(__file__).with_name("overlay")
    required = set(NEW_MAIN_SOURCES) | {"ForeverLaunchState.kt", "ForeverProcessIdentity.kt"}
    supplied: dict[str, str] = {}
    for path in sorted([*overlay.glob("Forever*.kt"), *overlay.glob("PanvkWsi*.kt")]):
        if path.name.endswith("Test.kt"):
            continue
        supplied[path.name] = path.read_text(encoding="utf-8")
    missing = sorted(required - set(supplied))
    if missing:
        raise InputBlockedError("reviewed Kotlin overlay sources missing: " + ", ".join(missing))
    return supplied


def _kotlin_tests() -> dict[str, str]:
    overlay = Path(__file__).with_name("overlay")
    tests = {path.name: path.read_text(encoding="utf-8")
             for path in sorted([*overlay.glob("Forever*Test.kt"), *overlay.glob("PanvkWsi*Test.kt")])}
    required = set(NEW_TEST_SOURCES)
    missing = sorted(required - set(tests))
    if missing:
        raise InputBlockedError("reviewed Kotlin tests missing: " + ", ".join(missing))
    return tests


def _transform_wine_application(text: str) -> str:
    """Short-circuit Pluvia before Hilt/GameNative in the isolated process."""
    marker = "FOREVER_WINE_APPLICATION_ISOLATION"
    if marker not in text:
        match = re.search(r"(override\s+fun\s+onCreate\s*\(\s*\)\s*\{)", text)
        if not match:
            raise ValueError("Pluvia onCreate anchor missing")
        insertion = (match.group(1) + "\n"
                     "        // FOREVER_WINE_APPLICATION_ISOLATION: before Hilt/GameNative.\n"
                     "        if (ForeverWineSupervisorPolicy.shouldIsolate(this)) return")
        text = text[:match.start()] + insertion + text[match.end():]
    elif text.count(marker) != 1:
        raise ValueError("ambiguous Pluvia Wine isolation marker")
    if "ForeverWineSupervisorPolicy.shouldIsolate(this)" not in text:
        raise ValueError("Pluvia Wine isolation hook missing")
    return text


def configure_wine_application(path: Path) -> None:
    path.write_text(_transform_wine_application(path.read_text(encoding="utf-8")), encoding="utf-8")


def _write_exact(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        item = os.lstat(path)
    except FileNotFoundError:
        item = None
    if item is not None and (not stat_is_regular(item.st_mode) or _stable_sha256(path)[1] != hashlib.sha256(data).hexdigest()):
        raise ValueError(f"generated destination collision: {path}")
    if item is None:
        path.write_bytes(data)


def _write_exact_file(path: Path, source: Path, expected_size: int, expected_digest: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        item = os.lstat(path)
    except FileNotFoundError:
        item = None
    if item is not None:
        if not stat_is_regular(item.st_mode):
            raise ValueError(f"generated destination collision: {path}")
        if _stable_sha256(path, max_bytes=512 * 1024 * 1024) == (expected_size, expected_digest):
            return
        raise ValueError(f"generated destination collision: {path}")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    input_fd = os.open(source, flags)
    output_fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o644)
    try:
        before = os.fstat(input_fd)
        if not stat_is_regular(before.st_mode) or (before.st_size != expected_size):
            raise ValueError(f"input changed while copying: {source}")
        digest = hashlib.sha256()
        total = 0
        while True:
            data = os.read(input_fd, 1024 * 1024)
            if not data:
                break
            view = memoryview(data)
            while view:
                view = view[os.write(output_fd, view):]
            total += len(data)
            digest.update(data)
        after = os.fstat(input_fd)
        if (before.st_dev, before.st_ino, before.st_size) != (after.st_dev, after.st_ino, after.st_size) or \
                (total, digest.hexdigest()) != (expected_size, expected_digest):
            raise ValueError(f"input changed while copying: {source}")
    finally:
        os.close(input_fd)
        os.close(output_fd)


def _write_recipe_owned(path: Path, data: bytes, allowed_existing: set[str] | None = None) -> None:
    """Materialize a recipe-owned path, rejecting an unknown collision."""
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        item = os.lstat(path)
    except FileNotFoundError:
        item = None
    if item is not None:
        existing = path.read_bytes() if stat_is_regular(item.st_mode) else None
        if existing == data:
            return
        if existing is None or hashlib.sha256(existing).hexdigest() not in (allowed_existing or set()):
            raise ValueError(f"recipe-owned destination collision: {path}")
        path.write_bytes(data)
        return
    path.write_bytes(data)


def _validate_source_artifacts(args: argparse.Namespace, app: Path) -> None:
    for label, path in (("bundle", args.bundle), ("probes", args.probes), ("catalog", args.catalog), ("contract", args.contract), ("JNI", args.native)):
        if not path.is_file() and not path.is_dir():
            raise InputBlockedError(f"{label} input missing: {path}")
    target_jni = app / "src/main/jniLibs/arm64-v8a/libforever_patcher_jni.so"
    if target_jni.is_file() and args.native.is_file() and sha256(target_jni) != sha256(args.native):
        raise ValueError("production JNI differs from retained baseline")


def _resource_provenance(path: Path, recipe: object) -> dict[str, object]:
    name = path.as_posix()
    if "/src/main/assets/" in name:
        packaged = "assets/" + name.split("/src/main/assets/", 1)[1]
    elif "/src/main/jniLibs/" in name:
        packaged = "lib/" + name.split("/src/main/jniLibs/", 1)[1]
    else:
        raise ValueError("resource provenance is not an APK payload")
    return {"fullSHA256": sha256(path), "size": path.stat().st_size,
            "sourceRecipe": recipe, "packagedPath": packaged}


def _load_or_create_provenance(path: Path) -> dict[str, object]:
    if not path.exists():
        return {"schema": 1, "history": ["code59-retained-baseline"]}
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("existing provenance is not an object")
    return value


def validate_provenance_destination(path: Path) -> None:
    """Preflight the one explicitly recipe-owned provenance replacement."""
    if not path.exists():
        return
    item = os.lstat(path)
    if not stat_is_regular(item.st_mode):
        raise ValueError(f"provenance destination collision: {path}")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("existing provenance is not an object")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--probes", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--native", type=Path, required=True)
    parser.add_argument("--contract", type=Path, default=Path(__file__).parents[3] / "forever/contract.json")
    parser.add_argument("--baseline-apk", type=Path)
    parser.add_argument("--prior-source-provenance-sha256")
    args = parser.parse_args()
    source = args.source.resolve()
    app = source / "app"
    if not (app / "build.gradle.kts").is_file() or not (app / "src/main/AndroidManifest.xml").is_file():
        raise SystemExit(f"not a GameNative source tree: {source}")

    # Everything below is read-only until every input, including the approved
    # prefix handoff, has passed.  This is the pre-mutation boundary.
    _validate_source_artifacts(args, app)
    baseline = load_code59_source_baseline()
    source_archive = validate_source_archive(baseline)
    source_authority = _source_archive_payload_authority(source_archive)
    retained = validate_retained_baseline_apk(baseline, args.baseline_apk)
    baseline_plan = validate_code59_source_tree(source, baseline, source_archive)
    validate_baseline_replay_destinations(source, retained["members"], source_authority)
    overlay_sources = validate_overlay_authority(baseline)
    overlay_sources = validate_new_wine_overlay_sources(overlay_sources)
    prior_outputs = authenticated_prior_source_outputs(source, args.prior_source_provenance_sha256, overlay_sources)
    prior_resources = authenticated_prior_wine_resources(source, args.prior_source_provenance_sha256)
    validate_materialization_destinations(source, overlay_sources, baseline, prior_outputs)
    supervisor_root = args.native.resolve().parent.parent / "controls" / WINE_SUPERVISOR_VARIANT
    wine_manifest, wine_entries = validate_wine_supervisor_inputs(supervisor_root)
    prefix_root = args.native.resolve().parent.parent / "controls" / "wine-prefix-v1"
    prefix_publisher, prefix_files = validate_prefix_inputs(prefix_root)
    disposition = validate_prelaunch_disposition_source()
    interrupted_disposition = validate_interrupted_disposition_contract()
    continuation_disposition = validate_continuation_disposition_contract()
    validate_exact_destinations(source, {
        Path("app/src/main/assets/forever/wine-supervisor/manifest.json"): (supervisor_root / "manifest.json").read_bytes(),
        Path("app/src/main/assets/forever/wine-prefix/publisher-manifest.json"): prefix_files["publisher-manifest.json"].read_bytes(),
        Path("app/src/main/assets/forever/wine-prefix/image-manifest.json"): prefix_files["prefix-image-manifest.json"].read_bytes(),
        Path("app/src/main/jniLibs/arm64-v8a/libforever_wine_supervisor.so"): (supervisor_root / WINE_SUPERVISOR_FILES[0]).read_bytes(),
        Path("app/src/main/jniLibs/arm64-v8a/libforever_wine_prefix.so"): prefix_files["libforever_wine_prefix.so"].read_bytes(),
        PRELAUNCH_DISPOSITION_ASSET: disposition,
        INTERRUPTED_DISPOSITION_ASSET: interrupted_disposition,
        CONTINUATION_DISPOSITION_ASSET: continuation_disposition,
    }, prior_resources)
    validate_exact_file_destinations(source, {
        Path("app/src/main/assets/forever/wine-prefix/prefix-image.zip"): PREFIX_INPUT_PINS["prefix-image.zip"],
    })
    validate_provenance_destination(app / "src/main/assets/forever/provenance.json")
    gradle = app / "build.gradle.kts"
    code, name, already = resolve_release_version(gradle.read_text(encoding="utf-8"))
    if already and (code, name) != (TARGET_VERSION_CODE, TARGET_VERSION_NAME):
        raise ValueError("candidate version collision")

    apply_code59_source_baseline(baseline_plan, source, baseline)
    materialize_baseline_payloads(source, retained, source_authority)
    # Replay is an authenticated replacement of the retained payload surface;
    # take the preservation snapshot after it, so only subsequent recipe work
    # can trip the unchanged-runtime gate.
    preserved = snapshot_preserved_payloads(app)
    approved_preimages = _approved_overlay_preimages(baseline)
    for relative, digests in prior_outputs.items():
        approved_preimages.setdefault(relative, set()).update(digests)
    for relative, data in overlay_sources.items():
        _write_recipe_owned(source / relative, data, approved_preimages.get(relative))

    # Version is the only existing source identity changed.  No Bionic,
    # ProcessHelper, MainActivity, start/stop, or runtime source is touched.
    gradle.write_text(_transform_gradle(gradle.read_text(encoding="utf-8")), encoding="utf-8")
    configure_wine_manifest(app / "src/main/AndroidManifest.xml")

    pluvia = app / "src/main/java/app/gamenative/PluviaApp.kt"
    if not pluvia.is_file():
        raise InputBlockedError(f"Pluvia application source missing: {pluvia}")
    configure_wine_application(pluvia)

    assets = app / "src/main/assets/forever"
    supervisor_asset = assets / "wine-supervisor/manifest.json"
    prefix_asset = assets / "wine-prefix"
    _write_recipe_owned(supervisor_asset, (supervisor_root / "manifest.json").read_bytes(),
                        prior_resources.get(Path("app/src/main/assets/forever/wine-supervisor/manifest.json")))
    _write_exact(prefix_asset / "publisher-manifest.json", prefix_files["publisher-manifest.json"].read_bytes())
    _write_exact_file(prefix_asset / "prefix-image.zip", prefix_files["prefix-image.zip"], *PREFIX_INPUT_PINS["prefix-image.zip"])
    _write_exact(prefix_asset / "image-manifest.json", prefix_files["prefix-image-manifest.json"].read_bytes())
    native_destination = app / "src/main/jniLibs/arm64-v8a/libforever_wine_supervisor.so"
    _write_recipe_owned(native_destination, (supervisor_root / WINE_SUPERVISOR_FILES[0]).read_bytes(),
                        prior_resources.get(Path("app/src/main/jniLibs/arm64-v8a/libforever_wine_supervisor.so")))
    prefix_native_destination = app / "src/main/jniLibs/arm64-v8a/libforever_wine_prefix.so"
    _write_exact(prefix_native_destination, prefix_files["libforever_wine_prefix.so"].read_bytes())
    disposition_destination = app / PRELAUNCH_DISPOSITION_ASSET
    _write_exact(disposition_destination, disposition)
    interrupted_destination = app / INTERRUPTED_DISPOSITION_ASSET
    _write_exact(interrupted_destination, interrupted_disposition)
    continuation_destination = app / CONTINUATION_DISPOSITION_ASSET
    _write_exact(continuation_destination, continuation_disposition)

    provenance_path = assets / "provenance.json"
    provenance = _load_or_create_provenance(provenance_path)
    provenance.update({"versionCode": TARGET_VERSION_CODE, "versionName": TARGET_VERSION_NAME,
        "profile": "server_only", "launchKind": "wine_server_isolation", "launchRoute": "dedicated-wine-supervisor-alias",
        "serverIsolation": True, "protocolSchema": 2, "graphicsProbeEnabled": False,
        "memoryPatching": "DISABLED", "authentication": "DISABLED", "nativeOnly": False,
        "sourceBaseline": {"manifestSHA256": SOURCE_BASELINE_SHA256,
            "archiveSHA256": baseline["sourceArchiveSHA256"], "historicalRecipeSHA": HISTORICAL_RECIPE_SHA256,
            "retainedAPK": {"path": str(args.baseline_apk or baseline["baselineAPK"]["path"]),
                "bytes": Path(args.baseline_apk or baseline["baselineAPK"]["path"]).stat().st_size,
                "sha256": RETAINED_BASELINE_APK_SHA256},
            "changedFiles": len(baseline["entries"]),
            "changedPathSHA256": {str(entry["path"]): entry["target59SHA256"] for entry in baseline["entries"]},
            "overlayBindings": {"count": len(baseline["overlayAuthority"]["bindings"]) + 1,
             "sourceInputSHA256": ({str(binding["sourceInputPath"]): hashlib.sha256(_source_file(Path(__file__).parents[3] / str(binding["sourceInputPath"]))).hexdigest() for binding in baseline["overlayAuthority"]["bindings"]} | {
                 str(Path("scripts/arm64-prototype/app/overlay") / REVIEWED_FUNCTIONAL_SOURCE[0]): hashlib.sha256(_source_file(Path(__file__).parents[3] / "scripts/arm64-prototype/app/overlay" / REVIEWED_FUNCTIONAL_SOURCE[0])).hexdigest()}),
             "approvedWineDeltas": sorted(APPROVED_WINE_OVERLAY_DELTAS),
             "approvedReviewedOverlayDeltas": dict(sorted(APPROVED_REVIEWED_OVERLAY_DELTAS.items())),
             "generatedSHA256": {str(relative): hashlib.sha256(data).hexdigest() for relative, data in overlay_sources.items()}},
            "wine60SourceInputs": {str(relative): hashlib.sha256(data).hexdigest() for relative, data in overlay_sources.items()
                if relative not in {Path(str(binding["path"])) for binding in baseline["overlayAuthority"]["bindings"]}}},
        "wineSupervisor": {"variant": WINE_SUPERVISOR_VARIANT, "manifestSha256": sha256(supervisor_asset),
            "files": {name: wine_entries[name] for name in WINE_SUPERVISOR_FILES},
            "source": wine_manifest["source"], "recipe": wine_manifest["recipe"]},
        "frozenPrelaunchDisposition": {"sha256": sha256(disposition_destination), "sourceSha256": PRELAUNCH_DISPOSITION_SHA256},
        "interruptedRunDisposition": {"sha256": sha256(interrupted_destination), "sourceSha256": INTERRUPTED_DISPOSITION_SHA256,
            "kind": "ABANDONED_ON_NEW_BOOT", "candidateVersionCode": 61},
        "prelaunchContinuation": {"sha256": sha256(continuation_destination), "sourceSha256": CONTINUATION_DISPOSITION_SHA256,
            "kind": "PRELAUNCH_ABORT", "candidateVersionCode": 62, "fixedStatusCount": 58},
        "recipeSha256": sha256(Path(__file__))})
    resources = provenance.setdefault("resources", {})
    if not isinstance(resources, dict):
        raise ValueError("existing provenance resources is not an object")
    resources.update({
        "forever/wine-supervisor/manifest.json": _resource_provenance(supervisor_asset, wine_manifest["recipe"]),
        "forever/wine-prefix/publisher-manifest.json": _resource_provenance(prefix_asset / "publisher-manifest.json", prefix_publisher.get("recipe", "wine-prefix-v1")),
        "forever/wine-prefix/prefix-image.zip": _resource_provenance(prefix_asset / "prefix-image.zip", prefix_publisher.get("recipe", "wine-prefix-v1")),
        "forever/wine-prefix/image-manifest.json": _resource_provenance(prefix_asset / "image-manifest.json", prefix_publisher.get("recipe", "wine-prefix-v1")),
        "lib/arm64-v8a/libforever_wine_supervisor.so": _resource_provenance(native_destination, wine_manifest["recipe"]),
        "lib/arm64-v8a/libforever_wine_prefix.so": _resource_provenance(prefix_native_destination, prefix_publisher.get("provenance", "wine-prefix-v1")),
    })
    provenance_path.write_text(json.dumps(provenance, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    assert_preserved_payloads(preserved)
    print(f"Applied bounded Wine SERVER_ISOLATION overlay to {source}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
