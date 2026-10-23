#!/usr/bin/env python3
"""Audit the actual APK for the bounded Wine SERVER_ISOLATION candidate."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path

from apply_overlay import (
    PACKAGE,
    PRELAUNCH_DISPOSITION_SHA256,
    TARGET_VERSION_CODE,
    TARGET_VERSION_NAME,
    WINE_SUPERVISOR_FILES,
    WINE_SUPERVISOR_MANIFEST_SHA256,
    WINE_SUPERVISOR_PIN_STATUS,
    WINE_SUPERVISOR_PINS,
    WINE_SUPERVISOR_VARIANT,
    INTERRUPTED_DISPOSITION_SHA256,
    CONTINUATION_DISPOSITION_SHA256,
    inspect_native_elf,
    load_code59_source_baseline,
    PREFIX_INPUT_PINS,
    sha256,
    validate_prefix_inputs,
    validate_wine_supervisor_inputs,
)

CERTIFICATE = "2b082e8200e066e665f73e71f547f827d6674634298024cb0b823be22adb242b"
OLD_NATIVE_PINS = {
    "libforever_run_supervisor.so": (30344, "fd8727d3d4b3b76e00cbf17c74bcee75684d7945aa8736f644ecbbaf55f7385c"),
    "libforever_run_fixture.so": (12784, "7f1b6d256939c9a7310475c3d3bc7dbdfeb22ad46e3de4270413db05a663e67f"),
}
NEW_ASSETS = (
    "assets/forever/wine-supervisor/manifest.json",
    "assets/forever/wine-interrupted-run-disposition-contract.json",
    "assets/forever/wine-prefix-prelaunch-continuation-contract.json",
    "assets/forever/wine-prefix/publisher-manifest.json",
    "assets/forever/wine-prefix/prefix-image.zip",
    "assets/forever/wine-prefix/image-manifest.json",
)
PREFIX_NATIVE = "lib/arm64-v8a/libforever_wine_prefix.so"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def _component_blocks(dump: str, kind: str) -> list[str]:
    lines = dump.splitlines()
    result: list[str] = []
    prefix = f"E: {kind} "
    for index, line in enumerate(lines):
        if not line.lstrip().startswith(prefix):
            continue
        indent = len(line) - len(line.lstrip())
        end = index + 1
        while end < len(lines):
            candidate = lines[end]
            if candidate.lstrip().startswith("E:") and len(candidate) - len(candidate.lstrip()) <= indent:
                break
            end += 1
        result.append("\n".join(lines[index:end]))
    return result


def _name(block: str) -> str | None:
    match = re.search(r'"([A-Za-z0-9_.$]+)"', block)
    return match.group(1) if match else None


def _enabled(block: str) -> bool | None:
    match = re.search(r"android:enabled\([^\n]*\)=(?:\(type 0x12\))([^\s]+)", block)
    if not match:
        return None
    return match.group(1) in ("0xffffffff", "0x1")


def _exported(block: str) -> bool | None:
    match = re.search(r"android:exported\([^\n]*\)=(?:\(type 0x12\))([^\s]+)", block)
    if not match:
        return None
    return match.group(1) in ("0xffffffff", "0x1")


def _asset_sha(archive: zipfile.ZipFile, name: str) -> str:
    with archive.open(name) as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def _baseline_members() -> dict[str, str]:
    baseline = load_code59_source_baseline()
    members: dict[str, str] = {}
    for group in baseline["inputGroups"].values():
        members.update(group["sha256"])
    members.pop("assets/forever/provenance.json", None)
    return members


def _resource_record(item: object, path: str) -> str:
    require(isinstance(item, dict) and set(("fullSHA256", "size", "sourceRecipe", "packagedPath")) <= set(item),
            f"incomplete provenance for {path}")
    packaged = path if path.startswith("lib/") else "assets/" + path
    require(item["packagedPath"] == packaged, f"resource packaged path mismatch: {path}")
    require(isinstance(item["sourceRecipe"], (str, dict)) and bool(item["sourceRecipe"]),
            f"missing source recipe: {path}")
    return packaged


def _audit_components(manifest_dump: str) -> None:
    activities = _component_blocks(manifest_dump, "activity")
    aliases = _component_blocks(manifest_dump, "activity-alias")
    supervisor = [b for b in activities if '"app.gamenative.ForeverWineSupervisorActivity"' in b]
    launcher = [b for b in aliases if '"app.gamenative.ForeverWineSupervisorLauncher"' in b]
    require(len(supervisor) == 1 and len(launcher) == 1, "Wine supervisor component/alias count is not exact")
    activity = supervisor[0]
    alias = launcher[0]
    for block, label in ((activity, "Wine supervisor activity"), (alias, "Wine supervisor alias")):
        require(_enabled(block) is True and _exported(block) is True, f"{label} exported/enabled is not explicit true")
        require('"android.permission.DUMP"' in block, f"{label} is not DUMP-protected")
    require('":wine_supervisor"' in activity, "Wine supervisor process isolation tag missing")
    require('"app.gamenative.ForeverWineSupervisorActivity"' in alias, "Wine alias target is wrong")
    require("android.intent.action.MAIN" in alias and "android.intent.category.LAUNCHER" in alias,
            "Wine alias is not the launcher")

    old_markers = ("MainActivity", "ForeverNativeSupervisor", "PanvkWsi")
    old_blocks = [b for b in activities + aliases if any(marker in b for marker in old_markers)]
    require(old_blocks, "retained legacy entrypoint blocks are not auditable")
    for block in old_blocks:
        require(_enabled(block) is False, "legacy Main/fixture/WSI entrypoint remains enabled")
    # Disabled historical aliases retain their filters but cannot launch. An
    # omitted enabled attribute defaults to true and must still be audited.
    launchers = [b for b in activities + aliases if _enabled(b) is not False
                 and "android.intent.action.MAIN" in b and "android.intent.category.LAUNCHER" in b]
    require(len(launchers) == 1 and launchers[0] == alias, "only the new Wine alias may be an enabled launcher")


def _audit_application(manifest_dump: str) -> None:
    applications = _component_blocks(manifest_dump, "application")
    require(len(applications) == 1, "application block is not unique")
    block = applications[0]
    require(re.search(r"android:extractNativeLibs\([^\n]*\)=\(type 0x12\)0xffffffff", block) is not None,
            "extractNativeLibs is not explicitly true")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--native", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[3]
    output = args.output.resolve()
    require(not output.is_relative_to(repo), "audit output must be outside the repository")
    require(output.parent.is_dir(), "audit output parent must exist")

    badging = subprocess.check_output([str(args.build_tools / "aapt"), "dump", "badging", str(args.apk)], text=True)
    require(f"package: name='{PACKAGE}' versionCode='{TARGET_VERSION_CODE}' versionName='{TARGET_VERSION_NAME}'" in badging,
            "unexpected APK identity")
    require("sdkVersion:'29'" in badging and "targetSdkVersion:'36'" in badging, "unexpected SDK levels")
    require("native-code: 'arm64-v8a'\n" in badging, "unexpected Android ABI")
    labels = re.findall(r"^application-label[^:]*:'([^']*)'", badging, re.MULTILINE)
    require(bool(labels) and set(labels) == {"Forever"}, "localized launcher label is not Forever")
    signature = subprocess.check_output([str(args.build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(args.apk)], text=True)
    match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", signature)
    require(match is not None and match.group(1) == CERTIFICATE, "APK certificate differs from the trusted baseline")
    require("Verified using v2 scheme (APK Signature Scheme v2): true" in signature, "missing protocol-2 APK signature")

    manifest_dump = subprocess.check_output([str(args.build_tools / "aapt"), "dump", "xmltree", str(args.apk), "AndroidManifest.xml"], text=True)
    _audit_components(manifest_dump)
    _audit_application(manifest_dump)

    with zipfile.ZipFile(args.apk) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "duplicate APK ZIP entries")
        require(not any(Path(n).name.lower() in {"wowb.exe", "wowb-arm64.exe", ".build.info"} or "data/data/" in n.lower() for n in names),
                "Blizzard client or CASC payload in APK")
        for name in NEW_ASSETS:
            require(name in names, f"missing new Wine resource: {name}")
        for name, expected in _baseline_members().items():
            require(name in names and _asset_sha(archive, name) == expected,
                    f"baseline payload changed or missing: {name}")
        native_names = [n for n in names if n.startswith("lib/") and not n.endswith("/")]
        require(native_names and all(n.startswith("lib/arm64-v8a/") for n in native_names), "non-AA64 native library packaged")
        for name in native_names:
            with archive.open(name) as stream:
                header = stream.read(64)
            require(header[:6] == b"\x7fELF\x02\x01" and int.from_bytes(header[18:20], "little") == 183,
                    f"non-AArch64 ELF: {name}")
            require(archive.getinfo(name).compress_type != zipfile.ZIP_STORED, f"native library was not extracted: {name}")
        new_native = "lib/arm64-v8a/libforever_wine_supervisor.so"
        require(new_native in names, "new Wine supervisor library missing")
        data = archive.read(new_native)
        expected_size, expected_sha = WINE_SUPERVISOR_PINS[WINE_SUPERVISOR_FILES[0]]
        require((len(data), hashlib.sha256(data).hexdigest()) == (expected_size, expected_sha), "Wine supervisor native pin mismatch")
        require(PREFIX_NATIVE in names, "prefix worker native library missing")
        prefix_publisher = json.loads(archive.read("assets/forever/wine-prefix/publisher-manifest.json"))
        prefix_pin = prefix_publisher.get("publisher", {})
        require(len(archive.read(PREFIX_NATIVE)) == prefix_pin.get("bytes") and _asset_sha(archive, PREFIX_NATIVE) == prefix_pin.get("sha256"),
         "prefix worker native pin mismatch")
        # The APK must agree with independently pinned producer inputs, not
        # merely with the publisher/image manifests it carries itself.
        external_prefix = args.native.resolve().parent.parent / "controls" / "wine-prefix-v1"
        for packaged, input_name in (
                ("assets/forever/wine-prefix/publisher-manifest.json", "publisher-manifest.json"),
                ("assets/forever/wine-prefix/prefix-image.zip", "prefix-image.zip"),
                ("assets/forever/wine-prefix/image-manifest.json", "prefix-image-manifest.json")):
            expected_size, expected_digest = PREFIX_INPUT_PINS[input_name]
            require(_asset_sha(archive, packaged) == expected_digest,
                    f"independent prefix input pin mismatch: {input_name}")
            require(sha256(external_prefix / input_name) == expected_digest,
                    f"external prefix input pin mismatch: {input_name}")
            if expected_size is not None:
                require(archive.getinfo(packaged).file_size == expected_size,
                        f"independent prefix input size mismatch: {input_name}")

        wine_manifest = json.loads(archive.read("assets/forever/wine-supervisor/manifest.json"))
        require(wine_manifest.get("variant") == WINE_SUPERVISOR_VARIANT and wine_manifest.get("protocol", {}).get("schema") == 2,
                "Wine supervisor is not the approved protocol-2 profile")
        require(_asset_sha(archive, "assets/forever/wine-supervisor/manifest.json") == WINE_SUPERVISOR_MANIFEST_SHA256,
                "Wine supervisor manifest pin mismatch")
        provenance = json.loads(archive.read("assets/forever/provenance.json"))
        require(provenance.get("versionCode") == TARGET_VERSION_CODE and provenance.get("versionName") == TARGET_VERSION_NAME,
                "stale candidate provenance")
        require(provenance.get("profile") == "server_only" and provenance.get("serverIsolation") is True and provenance.get("protocolSchema") == 2,
                "server-only profile provenance is missing")
        require(provenance.get("graphicsProbeEnabled") is False and provenance.get("memoryPatching") == "DISABLED" and provenance.get("authentication") == "DISABLED",
                "frozen gates were enabled")
        require(WINE_SUPERVISOR_PIN_STATUS == "READY" and provenance.get("wineSupervisor", {}).get("manifestSha256") == WINE_SUPERVISOR_MANIFEST_SHA256,
                "Wine supervisor provenance is stale")
        disposition_name = "assets/forever/native-supervisor/prelaunch-disposition.json"
        require(disposition_name in names and _asset_sha(archive, disposition_name) == PRELAUNCH_DISPOSITION_SHA256,
                 "frozen prelaunch disposition changed")
        interrupted_name = "assets/forever/wine-interrupted-run-disposition-contract.json"
        require(_asset_sha(archive, interrupted_name) == INTERRUPTED_DISPOSITION_SHA256,
                "fixed interrupted-run disposition contract changed")
        disposition = provenance.get("interruptedRunDisposition")
        require(isinstance(disposition, dict) and disposition.get("sha256") == INTERRUPTED_DISPOSITION_SHA256 and
                disposition.get("kind") == "ABANDONED_ON_NEW_BOOT" and disposition.get("candidateVersionCode") == 61,
                "interrupted-run disposition provenance is stale")
        continuation_name = "assets/forever/wine-prefix-prelaunch-continuation-contract.json"
        require(_asset_sha(archive, continuation_name) == CONTINUATION_DISPOSITION_SHA256,
                "candidate62 continuation contract changed")
        continuation = provenance.get("prelaunchContinuation")
        require(isinstance(continuation, dict) and continuation.get("sha256") == CONTINUATION_DISPOSITION_SHA256 and
                continuation.get("kind") == "PRELAUNCH_ABORT" and continuation.get("candidateVersionCode") == 62 and
                continuation.get("fixedStatusCount") == 58,
                "candidate62 continuation provenance is stale")
        resources = provenance.get("resources")
        require(isinstance(resources, dict) and set(resources) == {
            "forever/wine-supervisor/manifest.json", "forever/wine-prefix/publisher-manifest.json",
            "forever/wine-prefix/prefix-image.zip", "forever/wine-prefix/image-manifest.json",
            "lib/arm64-v8a/libforever_wine_supervisor.so", "lib/arm64-v8a/libforever_wine_prefix.so"}, "new resource provenance set is not exact")
        for path, item in resources.items():
            packaged = _resource_record(item, path)
            require(packaged in names and item["fullSHA256"] == _asset_sha(archive, packaged) and item["size"] == archive.getinfo(packaged).file_size,
                    f"resource provenance mismatch: {path}")

        # Historical fixture libraries remain present and byte-exact.  Their
        # source assets are intentionally not revalidated through the new pin.
        for name, (size, digest) in OLD_NATIVE_PINS.items():
            packaged = "lib/arm64-v8a/" + name
            require(packaged in names and archive.getinfo(packaged).file_size == size and _asset_sha(archive, packaged) == digest,
                    f"old native fixture changed: {name}")

    # Validate the externally supplied identities as a read-only second source
    # check; APK self-attestation is not sufficient.
    supervisor_root = args.native.resolve().parent.parent / "controls" / WINE_SUPERVISOR_VARIANT
    validate_wine_supervisor_inputs(supervisor_root)
    validate_prefix_inputs(args.native.resolve().parent.parent / "controls" / "wine-prefix-v1")
    result = {"schema": "wowforever.private-apk-audit.v2", "result": "PASS", "apk": str(args.apk.resolve()),
              "bytes": args.apk.stat().st_size, "sha256": sha256(args.apk), "package": PACKAGE,
              "versionCode": TARGET_VERSION_CODE, "versionName": TARGET_VERSION_NAME,
              "profile": "server_only", "protocol": 2, "certificateSha256": CERTIFICATE,
              "validationBoundary": "Host packaging only; no Wine/device/graphics/client/auth GO."}
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"APK-AUDIT-PASS {result['bytes']} bytes sha256={result['sha256']} -> {output}")


if __name__ == "__main__":
    main()
