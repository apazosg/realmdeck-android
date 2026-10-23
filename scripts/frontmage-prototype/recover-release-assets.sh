#!/usr/bin/env bash
set -euo pipefail

ARTIFACT_ROOT="${FRONTMAGE_PROTOTYPE_ARTIFACT_ROOT:-/home/adrian/wowforever-artifacts/frontmage-prototype}"
RELEASE_APK="${FRONTMAGE_PROTOTYPE_RELEASE_APK:-/home/adrian/wowforever-artifacts/frontmage-ab/app-debug.apk}"
BASELINE_APK="$ARTIFACT_ROOT/build/app/build/outputs/apk/debug/app-debug.apk"
GENERATED_ROOT="$ARTIFACT_ROOT/generated-assets"
GENERATED_ASSETS="$GENERATED_ROOT/app/src/main/assets"
INVENTORY="$ARTIFACT_ROOT/release-apk-asset-inventory.tsv"
ZIP_ENTRIES="$ARTIFACT_ROOT/release-apk-zip-entries.txt"
MANIFEST="$GENERATED_ROOT/asset-provenance.txt"

EXPECTED_RELEASE_APK_SHA256="01d364cafc77bb919a89668ac20618a108763c4e46ef3a81a104a4dc4bf98b2e"
EXPECTED_SOURCE_COMMIT="4293b75b449d11fdf66da567791c9165fa6df6a0"
EXPECTED_FEX_SHA256="0afc4ded5830c2038237498ffe34c830533c6152ca7197349c06de4f44aee4a3"
EXPECTED_PROTON9_SHA256="e9df3e74c4a4a17660902092e30b7ceaa5a91cb465f05f28035704808512e180"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 2; }
[[ -f "$RELEASE_APK" ]] || fail "known release APK missing: $RELEASE_APK"
[[ -f "$BASELINE_APK" ]] || fail "clean source baseline APK missing: $BASELINE_APK"

release_sha256="$(sha256sum "$RELEASE_APK" | cut -d' ' -f1)"
[[ "$release_sha256" == "$EXPECTED_RELEASE_APK_SHA256" ]] \
    || fail "release APK hash mismatch: expected $EXPECTED_RELEASE_APK_SHA256, got $release_sha256"

mkdir -p "$GENERATED_ASSETS/preinstall"
export RELEASE_APK BASELINE_APK GENERATED_ASSETS INVENTORY ZIP_ENTRIES MANIFEST
export EXPECTED_RELEASE_APK_SHA256 EXPECTED_SOURCE_COMMIT EXPECTED_FEX_SHA256 EXPECTED_PROTON9_SHA256
python3 <<'PY'
import hashlib
import os
import pathlib
import zipfile

release_apk = pathlib.Path(os.environ["RELEASE_APK"])
baseline_apk = pathlib.Path(os.environ["BASELINE_APK"])
generated_assets = pathlib.Path(os.environ["GENERATED_ASSETS"])
inventory = pathlib.Path(os.environ["INVENTORY"])
zip_entries = pathlib.Path(os.environ["ZIP_ENTRIES"])
manifest = pathlib.Path(os.environ["MANIFEST"])

selected = {
    "assets/imagefs.txz": ("imagefs.txz", "f96d362b7e148e86ab0d2c290978bf39b38e5c7ffc8ae4adf1d2a65c62bbb780"),
    "assets/imagefs_patches.tzst": ("imagefs_patches.tzst", "398f863a69957da8be6ee9b73134cecead64b833f82afcb84b60d1a6725eef5a"),
    "assets/preinstall/proton-10-arm64ec.wcp.xz": (
        "preinstall/proton-10-arm64ec.wcp.xz",
        "2501af75ffe4cffad0671e4e6613f0c8e6113fcf16f5a474a4dd3569135010d5",
    ),
}
comparison = {
    "assets/fexcore/fexcore-2508.tzst": os.environ["EXPECTED_FEX_SHA256"],
    "assets/proton-9.0-arm64ec.txz": os.environ["EXPECTED_PROTON9_SHA256"],
}

def hash_member(zf: zipfile.ZipFile, name: str) -> tuple[int, str]:
    digest = hashlib.sha256()
    total = 0
    with zf.open(name) as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
            total += len(chunk)
    return total, digest.hexdigest()

with zipfile.ZipFile(release_apk) as release:
    names = set(release.namelist())
    selected_lines = []
    for entry, (destination, expected) in selected.items():
        if entry not in names:
            raise SystemExit(f"missing selected release entry: {entry}")
        size, actual = hash_member(release, entry)
        if actual != expected:
            raise SystemExit(f"selected entry hash mismatch: {entry}: {actual}")
        destination_path = generated_assets / destination
        destination_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination_path.with_name(destination_path.name + ".tmp")
        with release.open(entry) as source, temporary.open("wb") as target:
            while chunk := source.read(1024 * 1024):
                target.write(chunk)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != expected:
            temporary.unlink(missing_ok=True)
            raise SystemExit(f"extracted entry hash mismatch: {entry}")
        temporary.replace(destination_path)
        selected_lines.append((destination, size, expected))

    comparison_lines = []
    for entry, expected in comparison.items():
        if entry not in names:
            raise SystemExit(f"missing release pairing entry: {entry}")
        size, actual = hash_member(release, entry)
        if actual != expected:
            raise SystemExit(f"release pairing hash mismatch: {entry}: {actual}")
        comparison_lines.append((entry, size, actual))

    asset_rows = []
    for info in sorted(release.infolist(), key=lambda item: item.filename):
        if info.filename.startswith("assets/") and not info.is_dir():
            size, actual = hash_member(release, info.filename)
            asset_rows.append((info.filename, size, actual))

inventory.parent.mkdir(parents=True, exist_ok=True)
with inventory.open("w", encoding="utf-8") as output:
    output.write("entry\tsize_bytes\tsha256\n")
    for entry, size, digest in asset_rows:
        output.write(f"{entry}\t{size}\t{digest}\n")

with zip_entries.open("w", encoding="utf-8") as output:
    for name in sorted(names):
        output.write(f"{name}\n")

with zipfile.ZipFile(baseline_apk) as baseline:
    for entry, expected in comparison.items():
        if entry not in baseline.namelist():
            raise SystemExit(f"missing source baseline pairing entry: {entry}")
        size, actual = hash_member(baseline, entry)
        if actual != expected:
            raise SystemExit(f"source/release pairing drift: {entry}: {actual}")

with manifest.open("w", encoding="utf-8") as output:
    output.write("kind=frontmage-exact-release-asset-recovery\n")
    output.write(f"release_apk={release_apk}\n")
    output.write(f"release_apk_sha256={os.environ['EXPECTED_RELEASE_APK_SHA256']}\n")
    output.write("release_url=https://github.com/FrontMage/winlator-llm/releases/download/llm-apk-20260216-144633/app-debug.apk\n")
    output.write("release_tag=llm-apk-20260216-144633\n")
    output.write(f"source_commit={os.environ['EXPECTED_SOURCE_COMMIT']}\n")
    output.write("release_package=com.winlator.llm\n")
    output.write("release_version=7.1\n")
    output.write("release_version_code=16\n")
    output.write("release_abi=arm64-v8a\n")
    output.write(f"release_zip_entry_count={len(names)}\n")
    output.write("asset_policy=copy_only_exact_entries; no FEX DLL recombination\n")
    for destination, size, expected in selected_lines:
        output.write(f"selected={destination}\tsize_bytes={size}\tsha256={expected}\n")
    for entry, size, digest in comparison_lines:
        output.write(f"pairing_check={entry}\tsize_bytes={size}\tsha256={digest}\n")
    output.write("license_note=release APK and runtime payloads are external reference artifacts; audit redistribution rights before distribution\n")
PY

printf 'RECOVERED_ASSETS=%s\nMANIFEST=%s\nINVENTORY=%s\n' "$GENERATED_ASSETS" "$MANIFEST" "$INVENTORY"
