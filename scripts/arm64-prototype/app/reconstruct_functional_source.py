#!/usr/bin/env python3
"""Authenticate inputs and stage a new, reviewed functional source candidate.

The code59->62 transition is still delegated to the existing recipe, but this
wrapper independently authenticates the reviewed current source input before
the recipe runs.  It records a new-candidate lineage and never claims the
unavailable historical ForeverLaunchController bytes.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[3]
APP_ROOT = ROOT / "scripts/arm64-prototype/app"
BASELINE = APP_ROOT / "code59-source-baseline.json"
APPLY_OVERLAY = APP_ROOT / "apply_overlay.py"
APPLY_FUNCTIONAL = APP_ROOT / "apply_functional_poc.py"
UPSTREAM_ARCHIVE = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/"
    "toolchain/GameNative-205cbf8ddc06f855a1b85ba57c78c4be9a6930aa.zip"
)
RECIPE_BUNDLE = Path("/home/adrian/wowforever-artifacts/work/community-bundle-readback.zip")
PROBES_ROOT = Path("/home/adrian/wowforever-artifacts/arm64-prototype/probes")
CATALOG = Path("/home/adrian/wowforever-artifacts/arm64-prototype/client-bootstrap/catalog.json")
CLIENT_TARGET_SOURCE = ROOT / "forever/client-target.json"
UPDATE_ASSET_ROOT = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/client-updater-packaged-catalog"
)
CODE62_NATIVE = Path("/home/adrian/wowforever-artifacts/arm64-prototype/native/libforever_patcher_jni.so")
RETAINED_APK = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/delivery/retained-0.2.35-code59.apk"
)
CODE64_APK = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/"
    "functional-poc-candidate-1d91f727f0322d4100e43a37277ed5fe6714f24fb66aeb9e062b702bd5fe4324.apk"
)
CODE64_AUDIT = CODE64_APK.with_name(CODE64_APK.stem + "-audit.json")
CODE64_NATIVE_SIDECAR = CODE64_APK.with_name(CODE64_APK.stem + "-native-provenance.json")
CURRENT_DISPOSITION_SOURCE = ROOT / "scripts/diagnostics/android-process/wine-interrupted-run-disposition-contract.json"
C138_RECIPE_SHA256 = "f3deeab1112963bfa1b805f1fec465375f64801696cbe918c365855755e29127"
C138_DISPOSITION_SHA256 = "c198e20f582b90e5bc691e3e4712e8b2b1ecf33434bf1f86232c30be7fab2ac6"
# Set only after the frozen C138 inputs produce the reviewed provenance payload.
# Until then, C138 sanitization fails closed rather than admitting a dynamic hash.
C138_PROVENANCE_SHA256 = "5254e4a33471f7c9600140a7c5f0ab6e6f90be493c85d1824b386b6723acda35"
# Exact current recipe/provenance pair after admitting the reviewed Home/Wi-Fi
# Provisioner overlay delta. The historical C138 pins above remain immutable.
REVIEWED_CANDIDATE_RECIPE_SHA256 = "385e1c4c443aa4a192a14769caacc4af959d2a2ee498e82485fb9eb6cbc4e151"
REVIEWED_CANDIDATE_PROVENANCE_SHA256 = "aa8f0dc18cda8f92e2a6dac73349a36845d67493a2c2cf712f622b9594d10a20"
REVIEWED_CANDIDATE_PROVISIONER_SHA256 = "98c25790d3d126ec9bbf2ead1eb2ba976abece91dfa11511c77d09ed4082122d"
# Exact current recipe/provenance pair after admitting the reviewed storage
# revalidation diagnostics source. The preceding reviewed pair remains frozen.
REVIEWED_STORAGE_RECIPE_SHA256 = "df97574d8c5dce73ea53b535cf52e1bd28d556bcb2bcef0eef92f5704a5060df"
REVIEWED_STORAGE_PROVENANCE_SHA256 = "867d8d4a51b86865d6232d14c7b746f8117bdb7022addbd86eb840eb9e2b1efa"
REVIEWED_STORAGE_SOURCE_SHA256 = "9497a0aa068f93c3be0d2a64e5ef2b4eab3fbf22510d1510e6ae9df63c622443"
INTERRUPTED_DISPOSITION_MEMBER = "assets/forever/wine-interrupted-run-disposition-contract.json"
CODE_PROVENANCE_MEMBER = "assets/forever/provenance.json"
NEW_CONTROLLER_SOURCE = APP_ROOT / "overlay/ForeverLaunchController.kt"
BRIDGE_SOURCE = ROOT / "scripts/arm64-prototype/native/ForeverPatchBridge.kt"

PINNED_ARCHIVE_SHA256 = "9151ca5a20168a8901797d7fcc2946706f57532088632527876efa2931bdc361"
PINNED_BASELINE_SHA256 = "0a3d039a1d018230a9a99ab7c4de45d7e5753b75626868c8f06cad3678fdd0d8"
PINNED_RETAINED_APK_SHA256 = "b8cace99ded43adb1841a2047f3802976b4b2d113aa3c5a67091bdd7270d2435"
PINNED_BUNDLE_SHA256 = "d46710b61b899309e3e85dd6724d602e9dc5440ec0831d6c5ebe5f889674caa1"
PINNED_CATALOG_SHA256 = "3cee581d681a5439c138955edd3c39ff2c799689b3889a47cd41c6762e6ff1ce"
PINNED_CONTRACT_SHA256 = "212cda7a7972ed93541a75728b78018c3627667391ecbe28426e8695ef846469"
PINNED_CODE62_NATIVE_SHA256 = "2edcd3cf9cc80df58a80d103709e33400080d22f9b0229e90a79e7f4fcdf5bf4"
PINNED_CODE64_APK_SHA256 = "1d91f727f0322d4100e43a37277ed5fe6714f24fb66aeb9e062b702bd5fe4324"
PINNED_CODE64_AUDIT_SHA256 = "325fcad6cb9cc5898628c04568f82636cd59aa129f0fc726ffdddcea17b4e0e8"
PINNED_CODE64_NATIVE_SIDECAR_SHA256 = "7318ed2b4f7b14d1fe1d1cd520a7a597c1d938c4cb66b02a403218b7a7f8e6af"
PINNED_CODE64_NATIVE_SHA256 = "ec8573de09cddc6f28606a6b361e675a74432d0c3349a4daef0eb4cfe72189b1"
# C141: apply-eligible scan, retaining C137 matching and guarded application. The
# historical code64 JNI above remains the authenticated preimage; it is never
# retagged as this candidate. Only this exact sidecar/SO pair is admitted.
CANDIDATE_NATIVE_SO = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/"
    "functional-capture-build.tFcrMz/libforever_patcher_jni.so"
)
CANDIDATE_NATIVE_SIDECAR = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/"
    "functional-capture-build.tFcrMz/provenance.json"
)
PINNED_CANDIDATE_NATIVE_SHA256 = "79af82848e15de5395ddfb52f916c95ff3212fdb0e579cc69b96e2d32e4c1bd3"
PINNED_CANDIDATE_NATIVE_BYTES = 1005224
PINNED_CANDIDATE_NATIVE_SIDECAR_SHA256 = "15f64c0185e65a8a592156f5041aaadfa3ca4b7d2a2a6d17e3f358fd0a9e3b41"
CANDIDATE_NATIVE_ORIGIN = "candidate-native-c141-eligible-scan"
PINNED_HISTORICAL_CONTROLLER_SHA256 = "45f80f52a1c840cde21cfff45643532af22787dc9025ec030ba4dfb352aded68"
PINNED_NEW_CONTROLLER_SHA256 = "8e89d2b0a79af9c6d6470cbf7897fdcb3941437febac96cd819a48258fdd1dac"
PINNED_BRIDGE_SHA256 = "a5071436a7fb102141d31ad12a056b91410a22c27e04edf37f71827c7d3ccaf2"
PINNED_UPDATER_CASC_PREIMAGES = {
    "ForeverCasc.kt": "b482531fb4970ba279448fac22ea69c470093fc8b09917c5b1ec0dd7a9ad57e4",
    "ForeverCascTest.kt": "19cb155b99ea60b3620259974e0fa40694ccd170149b7ce48287ada34c63194f",
}
# Native V7 reserve, durable commit groups and exact recovery membership; frames unchanged.
PINNED_UPDATER_CASC_POSTIMAGES = {
    "ForeverCasc.kt": "70c3f28dddacee951e4738897a6ac977b281a4bec1d444953061bb49f32fc74b",
    "ForeverCascTest.kt": "7a0150bf275a3d1af8ee40c604deb1602892aca671f95343a466a16c9a26b871",
}
# Reviewed reconstruction edit (2026-10-18): TACT-encrypted ('E') BLTE frames are
# verified without decryption for CASC reconstruction consumers; strict decode is
# untouched. The recipe writes the historical overlay, then the authenticated
# preimage/postimage pair below replaces it, exactly like the ForeverCasc flow.
PINNED_UPDATER_BLTE_PREIMAGES = {
    "ForeverBlte.kt": "0dba2c497bea3be99bdf6533cf04084b59926f7027380b5019da551c2f4b78af",
}
PINNED_UPDATER_BLTE_POSTIMAGES = {
    "ForeverBlte.kt": "4a19ccb993fe4e9545abd57d08bd9a32e524d8c556c4483e1972111a7209c8c6",
}
HISTORICAL_CONTROLLER_PATH = Path("app/src/main/java/app/gamenative/ForeverLaunchController.kt")
NEW_CONTROLLER_TARGET = HISTORICAL_CONTROLLER_PATH
BRIDGE_TARGET = Path("app/src/main/java/com/wowforever/launcher/ForeverPatchBridge.kt")
CODE64_NATIVE_MEMBER = "lib/arm64-v8a/libforever_patcher_jni.so"
FUNCTIONAL_MARKER_MEMBER = "assets/forever/functional-poc/provenance.json"
SOURCE_VERSION = (62, "0.2.38-automated")
CANDIDATE_VERSION = (141, "0.2.117-eligible-scan")
FUNCTIONAL_OVERLAY_NAMES = (
    "ForeverFunctionalPoc.kt",
    "ForeverFunctionalPocActivity.kt",
    "ForeverFunctionalLauncherUx.kt",
    "ForeverFunctionalLauncherUxTest.kt",
    "ForeverFunctionalUiText.kt",
    "ForeverFunctionalIcons.kt",
    "ForeverFunctionalClientStorage.kt",
    "ForeverFunctionalClientStorageTest.kt",
    "ForeverCascReplayReaderAndroid.kt",
    "ForeverCascReplayPreflight.kt",
    "ForeverCascReplayPreflightTest.kt",
    "ForeverFunctionalDisplayFit.kt",
    "ForeverFunctionalDisplayFitTest.kt",
    "ForeverFunctionalGpuCompatibility.kt",
    "ForeverFunctionalServerProfiles.kt",
    "ForeverFunctionalPocPatchBridge.kt",
    "ForeverFunctionalPocPatchSession.kt",
    "ForeverFunctionalPatchSummary.kt",
    "ForeverFunctionalPatchSummaryTest.kt",
    "ForeverFunctionalWowStartup.kt",
    "ForeverFunctionalWowStartupTest.kt",
    "ForeverFunctionalServerProfilesTest.kt",
    "ForeverFunctionalGpuCompatibilityTest.kt",
    "ForeverFunctionalAutologinCredentials.kt",
    "ForeverFunctionalAutologinCredentialsTest.kt",
    "ForeverFunctionalAutologinAssistedEntry.kt",
    "ForeverFunctionalAutologinAssistedEntryTest.kt",
    "ForeverFunctionalAutologinAttempt.kt",
    "ForeverFunctionalAutologinAttemptTest.kt",
    "ForeverFunctionalAutologinSettings.kt",
    "ForeverFunctionalAutologinSettingsTest.kt",
    "ForeverClientUpdateCheck.kt",
    "ForeverClientUpdateCheckTest.kt",
    "ForeverRemoteDiagnosticsContract.kt",
    "ForeverRemoteDiagnosticsSanitizer.kt",
    "ForeverRemoteDiagnosticsStore.kt",
    "ForeverRemoteDiagnosticsReport.kt",
    "ForeverRemoteDiagnosticsFrozenConfig.kt",
    "ForeverRemoteDiagnosticsLocalCapture.kt",
    "ForeverRemoteDiagnosticsUpload.kt",
    "ForeverRemoteDiagnosticsAndroid.kt",
    "ForeverRemoteDiagnosticsDialog.kt",
    "ForeverRemoteDiagnosticsWineLog.kt",
    "ForeverRemoteDiagnosticsSanitizerTest.kt",
    "ForeverRemoteDiagnosticsStoreTest.kt",
    "ForeverRemoteDiagnosticsReportTest.kt",
    "ForeverRemoteDiagnosticsFrozenConfigTest.kt",
    "ForeverRemoteDiagnosticsLocalCaptureTest.kt",
    "ForeverRemoteDiagnosticsUploadTest.kt",
    "ForeverRemoteDiagnosticsWineLogTest.kt",
    "ForeverRemoteDiagnosticsCredentialPrivacyTest.kt",
)
# C116 app branding (approved gold-shield/diamond emblem, no upstream imagery).
# Each entry is an explicit (branding-res origin, staged source target) pair;
# no directory or prefix wildcard admission. Resources compile into
# resources.arsc, so ASSET_ROOTS and the code64 payload allowlist are untouched.
BRANDING_ROOT = APP_ROOT / "branding-res"
BRANDING_RESOURCE_FILES = (
    ("drawable/forever_emblem_background.xml", "app/src/main/res/drawable/forever_emblem_background.xml"),
    ("drawable/forever_emblem_foreground.xml", "app/src/main/res/drawable/forever_emblem_foreground.xml"),
    ("drawable/forever_emblem_monochrome.xml", "app/src/main/res/drawable/forever_emblem_monochrome.xml"),
    ("drawable/forever_splash_legacy.xml", "app/src/main/res/drawable/forever_splash_legacy.xml"),
    ("mipmap-anydpi-v26/forever_ic_launcher.xml", "app/src/main/res/mipmap-anydpi-v26/forever_ic_launcher.xml"),
    ("mipmap-anydpi-v33/forever_ic_launcher.xml", "app/src/main/res/mipmap-anydpi-v33/forever_ic_launcher.xml"),
    ("values/forever_branding.xml", "app/src/main/res/values/forever_branding.xml"),
    ("values/forever_branding_themes.xml", "app/src/main/res/values/forever_branding_themes.xml"),
    ("values-v31/forever_branding_themes.xml", "app/src/main/res/values-v31/forever_branding_themes.xml"),
)
BRANDING_ICON = "@mipmap/forever_ic_launcher"
BRANDING_SPLASH_THEME = "@style/Theme.ForeverFunctionalSplash"
UPDATER_GATE_ROOT = APP_ROOT / "updater-gate"
UPDATER_GATE_MAIN_NAMES = (
    "ForeverWifiDownloads.kt",
    "ForeverClientDownloadRate.kt",
    "ForeverClientManagement.kt",
    "ForeverClientOperationLease.kt",
    "ForeverClientRuntimeGate.kt",
    "ForeverClientTargetBuild.kt",
    "ForeverClientCatalogResolver.kt",
    "ForeverClientUpdateCatalog.kt",
    "ForeverClientArchiveOrder.kt",
    "ForeverClientUpdateService.kt",
    "ForeverClientUpdateTransaction.kt",
    "ForeverClientUpdateForegroundService.kt",
    "ForeverClientUpdateTransfer.kt",
    "ForeverClientUpdater.kt",
    "ForeverClientUpdaterQualification.kt",
    "ForeverTactMetadata.kt",
    "ForeverTactManifests.kt",
    "ForeverTactConfigs.kt",
    "ForeverTactCdnIndex.kt",
    "ForeverClientMetadataTransport.kt",
)
UPDATER_GATE_TEST_NAMES = (
    "ForeverWifiDownloadsTest.kt",
    "ForeverClientDownloadRateTest.kt",
    "ForeverClientOperationLeaseTest.kt",
    "ForeverClientRuntimeGateTest.kt",
    "ForeverClientTargetBuildTest.kt",
    "ForeverClientCatalogResolverTest.kt",
    "ForeverClientUpdateCatalogTest.kt",
    "ForeverClientUpdateServiceTest.kt",
    "ForeverClientUpdateForegroundServiceTest.kt",
    "ForeverClientUpdateTransactionTest.kt",
    "ForeverClientUpdateTransferTest.kt",
    "ForeverClientUpdaterQualificationTest.kt",
    "ForeverClientUpdaterTest.kt",
    "ForeverClientFinalizationProgressTest.kt",
    "ForeverBlteRangeTest.kt",
    "ForeverTactManifestsTest.kt",
    "ForeverTactConfigsTest.kt",
    "ForeverTactCdnIndexTest.kt",
    "ForeverClientMetadataTransportTest.kt",
)
UPDATER_GATE_NAMES = frozenset(
    (*UPDATER_GATE_MAIN_NAMES, *UPDATER_GATE_TEST_NAMES, *PINNED_UPDATER_CASC_PREIMAGES, *PINNED_UPDATER_BLTE_PREIMAGES)
)
UPDATE_ASSET_GENERATION = "wow_classic_beta-70425-arm64-enUS"
PINNED_UPDATE_CATALOG_DECODED_SHA256 = "8327794ea6867113dec55b07b4f3755c70fe808a67502eb12242ccbf742a69fc"
PINNED_UPDATE_CATALOG_DECODED_BYTES = 99647488
CLIENT_TARGET_MEMBER = "assets/forever/client-update/target.json"
CLIENT_TARGET_MAX_BYTES = 32 * 1024
CLIENT_TARGET_VERSION = "1.60.1.70425"
CLIENT_TARGET_BUILD_CONFIG_HASH = "ea9c525eef74f1d682c00a8ef39a5d2b"
CLIENT_TARGET_CDN_CONFIG_HASH = "fcab7fec2ccc6b8158b56b6938ec4551"
CLIENT_TARGET_AUTHORITY = "initial-offline-fallback-only; not future update admission authority"


@dataclass(frozen=True)
class PinnedUpdateAsset:
    source: Path
    size: int
    sha256: str


PINNED_UPDATE_ASSETS = {
    # AAPT transparently expands .gz assets and removes their suffix. Keep the
    # reviewed gzip bytes intact under a non-special suffix for bounded import.
    "assets/forever/client-update/catalog.sqlite3.gzip": PinnedUpdateAsset(
        UPDATE_ASSET_ROOT / "client-catalog.sqlite3.gz",
        63202489,
        "92cfb71b680a21e5fe35c487564454bfc9096dd84c16369c9575236acdb0adb4",
    ),
    "assets/forever/client-update/build-config.txt": PinnedUpdateAsset(
        UPDATE_ASSET_ROOT / "build-config.txt",
        59537,
        "487140959a68e1443f2fd539f3011581eefe28333a9db8e8750bf1d571635aed",
    ),
    "assets/forever/client-update/cdn-config.txt": PinnedUpdateAsset(
        UPDATE_ASSET_ROOT / "cdn-config.txt",
        59257,
        "56ec0775976db5dd441f05e6c5f03ea011a343739bbc8f45a690a4918bf75b5e",
    ),
}
PRODUCTION_EXPORTS = frozenset({
    "Java_com_wowforever_launcher_ForeverPatchBridge_findOwnedGameProcesses",
    "Java_com_wowforever_launcher_ForeverPatchBridge_inspectOwnedGame",
    "Java_com_wowforever_launcher_ForeverPatchBridge_armSignalTrace",
    "Java_com_wowforever_launcher_ForeverPatchBridge_signalTraceStatus",
})
FUNCTIONAL_EXPORTS = frozenset({
    "Java_app_gamenative_ForeverFunctionalPocPatchBridge_nativeBegin",
    "Java_app_gamenative_ForeverFunctionalPocPatchBridge_nativePoll",
    "Java_app_gamenative_ForeverFunctionalPocPatchBridge_nativeEnd",
})
REQUIRED_EXPORTS = PRODUCTION_EXPORTS | FUNCTIONAL_EXPORTS
# The retained CODE64 preimage remains authenticated with its historical ABI.
CANDIDATE_REQUIRED_EXPORTS = REQUIRED_EXPORTS | frozenset({
    "Java_app_gamenative_ForeverFunctionalPocPatchBridge_nativeApply",
})
CANDIDATE_NATIVE_CAPABILITY = "guarded-group8-public-key-apply"
PROBE_PINS = {
    "ApiArm64.exe": "d4f1ef4928fab02f96e1eea35c207742ba6f6389e1a7fa0eeee60fc581d24043",
    "DynCodeArm64.exe": "3568ec5f7c749ad2f7b7889390141ae45587f888d14df00f685cb3f35eb32e49",
    "HelloArm64.exe": "ef658461aedd96516c607053c20c4ef3076088889d37afa67d3ab9e31826fd70",
    "manifest.sha256": "9cb7139b03f1ca1fc69bacfdd9258426e7406e51a38a8ac107fdf2cbecb3fd44",
}
BUILD = Path("app/build.gradle.kts")
MANIFEST = Path("app/src/main/AndroidManifest.xml")
XSERVER = Path("app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt")
JNI_RELATIVE = Path("app/src/main/jniLibs/arm64-v8a/libforever_patcher_jni.so")
MARKER = Path("app/src/main/assets/forever/functional-poc/provenance.json")
CODE62_PROVENANCE = Path("app/src/main/assets/forever/provenance.json")
ASSET_ROOTS = (Path("app/src/main/assets"), Path("app/src/modern/assets"), Path("app/src/main/jniLibs"))


class ReconstructionError(ValueError):
    """An authenticated input or bounded source transition is unavailable."""


class ReconstructionBlocked(ReconstructionError):
    def __init__(self, report: Path):
        super().__init__(f"reconstruction blocked; review report: {report}")
        self.report = report


def privacy_provenance_pin_for_recipe(recipe_sha256: str) -> str:
    """Return only a frozen historical or exact reviewed-candidate privacy pin."""
    if C138_PROVENANCE_SHA256 == "UNFROZEN_AFTER_SOURCE_FREEZE":
        raise ReconstructionError("C138 provenance SHA-256 pin is unfrozen; refusing privacy payload parity")
    pairs = {
        C138_RECIPE_SHA256: C138_PROVENANCE_SHA256,
        REVIEWED_CANDIDATE_RECIPE_SHA256: REVIEWED_CANDIDATE_PROVENANCE_SHA256,
        REVIEWED_STORAGE_RECIPE_SHA256: REVIEWED_STORAGE_PROVENANCE_SHA256,
    }
    if recipe_sha256 not in pairs:
        raise ReconstructionError("recipe SHA-256 has no frozen privacy provenance pair")
    return pairs[recipe_sha256]


@dataclass(frozen=True)
class AuthenticatedInputs:
    bundle: Path
    probes: Path
    catalog: Path
    contract: Path
    code62_native: Path
    code64_apk: Path
    code64_audit: Path
    code64_native_sidecar: Path
    code64_payloads: dict[str, str]
    code64_native_bytes: bytes
    code64_native_audit: dict[str, object]
    input_manifests: dict[str, object]
    client_target_bytes: bytes
    client_target_manifest: dict[str, object]


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _stat_signature(item: os.stat_result) -> tuple[int, int, int, int, int, int]:
    return (item.st_dev, item.st_ino, item.st_mode, item.st_nlink, item.st_size, item.st_mtime_ns)


def regular(path: Path, label: str) -> None:
    try:
        item = os.lstat(path)
    except FileNotFoundError as exc:
        raise ReconstructionError(f"{label} must be a regular file: {path}") from exc
    if not stat.S_ISREG(item.st_mode):
        raise ReconstructionError(f"{label} must be a regular file: {path}")


def sha256_file(path: Path, label: str = "file") -> str:
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode):
            raise ReconstructionError(f"{label} must be a regular file: {path}")
        digest = hashlib.sha256()
        with os.fdopen(fd, "rb", closefd=False) as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        after = os.fstat(fd)
        if _stat_signature(before) != _stat_signature(after):
            raise ReconstructionError(f"{label} changed while being read: {path}")
        return digest.hexdigest()
    finally:
        os.close(fd)


def pinned_update_asset_manifest() -> dict[str, object]:
    return {
        "generation": UPDATE_ASSET_GENERATION,
        "fingerprint": {"build": 70425, "architecture": "arm64", "locale": "enUS"},
        "catalogDecodedSha256": PINNED_UPDATE_CATALOG_DECODED_SHA256,
        "gamePayloadsIncluded": False,
        "assets": [
            {
                "member": member,
                "sourcePath": str(pin.source),
                "bytes": pin.size,
                "sha256": pin.sha256,
            }
            for member, pin in PINNED_UPDATE_ASSETS.items()
        ],
    }


def validate_client_target_source() -> tuple[bytes, dict[str, object]]:
    """Bind the packaged initial fallback target to the three reviewed metadata pins."""
    raw = read_stable_file(CLIENT_TARGET_SOURCE, "public client target", max_bytes=CLIENT_TARGET_MAX_BYTES)
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ReconstructionError("public client target is not valid UTF-8 JSON") from exc
    if not isinstance(value, dict):
        raise ReconstructionError("public client target must be a JSON object")

    expected_identity = {
        "schema": "wowforever.client-target",
        "schema_version": 1,
        "product": "wow_classic_beta",
        "region": "us",
        "platform": "Windows",
        "architecture": "arm64",
        "locale": "enUS",
        "version": CLIENT_TARGET_VERSION,
        "build_id": 70425,
    }
    if any(value.get(key) != expected for key, expected in expected_identity.items()):
        raise ReconstructionError("public client target identity differs from the reviewed 70425 fallback")

    build_pin = PINNED_UPDATE_ASSETS["assets/forever/client-update/build-config.txt"]
    cdn_pin = PINNED_UPDATE_ASSETS["assets/forever/client-update/cdn-config.txt"]
    catalog_pin = PINNED_UPDATE_ASSETS["assets/forever/client-update/catalog.sqlite3.gzip"]
    build = value.get("build_config")
    cdn = value.get("cdn_config")
    catalog = value.get("catalog")
    if not isinstance(build, dict) or (build.get("hash"), build.get("bytes"), build.get("sha256")) != (
        CLIENT_TARGET_BUILD_CONFIG_HASH, build_pin.size, build_pin.sha256
    ):
        raise ReconstructionError("public client target build-config binding differs from the pinned fallback")
    if not isinstance(cdn, dict) or (cdn.get("hash"), cdn.get("bytes"), cdn.get("sha256")) != (
        CLIENT_TARGET_CDN_CONFIG_HASH, cdn_pin.size, cdn_pin.sha256
    ):
        raise ReconstructionError("public client target CDN-config binding differs from the pinned fallback")
    if not isinstance(catalog, dict) or (
        catalog.get("bytes"), catalog.get("sha256"), catalog.get("gzip_bytes"), catalog.get("gzip_sha256")
    ) != (
        PINNED_UPDATE_CATALOG_DECODED_BYTES,
        PINNED_UPDATE_CATALOG_DECODED_SHA256,
        catalog_pin.size,
        catalog_pin.sha256,
    ):
        raise ReconstructionError("public client target catalog binding differs from the pinned fallback")
    native = value.get("native_validation")
    if not isinstance(native, dict) or native.get("status") != "pending" or native.get("evidence_sha256") is not None:
        raise ReconstructionError("public client target cannot claim unproven native validation")

    bindings = {
        **expected_identity,
        "build_config": {"hash": build["hash"], "bytes": build["bytes"], "sha256": build["sha256"]},
        "cdn_config": {"hash": cdn["hash"], "bytes": cdn["bytes"], "sha256": cdn["sha256"]},
        "catalog": {
            "bytes": catalog["bytes"], "sha256": catalog["sha256"],
            "gzip_bytes": catalog["gzip_bytes"], "gzip_sha256": catalog["gzip_sha256"],
        },
        "native_validation": {"status": "pending", "evidence_sha256": None},
    }
    manifest = {
        "member": CLIENT_TARGET_MEMBER,
        "sourcePath": str(CLIENT_TARGET_SOURCE),
        "bytes": len(raw),
        "sha256": sha256_bytes(raw),
        "authority": CLIENT_TARGET_AUTHORITY,
        "bindings": bindings,
    }
    return raw, manifest


def update_asset_pins(inputs: AuthenticatedInputs) -> dict[str, PinnedUpdateAsset]:
    """Return the exact four metadata members, including the receipt-bound target JSON."""
    return PINNED_UPDATE_ASSETS | {
        CLIENT_TARGET_MEMBER: PinnedUpdateAsset(
            CLIENT_TARGET_SOURCE,
            len(inputs.client_target_bytes),
            str(inputs.client_target_manifest["sha256"]),
        )
    }


def validate_pinned_update_assets() -> dict[str, dict[str, object]]:
    """Authenticate the small metadata set through no-follow descriptors and streaming hashes."""
    try:
        root_stat = os.lstat(UPDATE_ASSET_ROOT)
    except FileNotFoundError as exc:
        raise ReconstructionError(f"pinned update asset directory is missing: {UPDATE_ASSET_ROOT}") from exc
    if not stat.S_ISDIR(root_stat.st_mode):
        raise ReconstructionError(f"pinned update asset directory is not a directory: {UPDATE_ASSET_ROOT}")

    result: dict[str, dict[str, object]] = {}
    for member, pin in PINNED_UPDATE_ASSETS.items():
        relative = PurePosixPath(member)
        if relative.is_absolute() or ".." in relative.parts or not member.startswith("assets/"):
            raise ReconstructionError(f"unsafe pinned update asset member: {member}")
        fd = os.open(pin.source, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
        try:
            before = os.fstat(fd)
            if not stat.S_ISREG(before.st_mode):
                raise ReconstructionError(f"pinned update asset must be a regular file: {pin.source}")
            if before.st_size != pin.size:
                raise ReconstructionError(f"pinned update asset size mismatch: {member}")
            digest = hashlib.sha256()
            with os.fdopen(fd, "rb", closefd=False) as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
            after = os.fstat(fd)
            if _stat_signature(before) != _stat_signature(after):
                raise ReconstructionError(f"pinned update asset changed while being read: {pin.source}")
            actual = digest.hexdigest()
            if actual != pin.sha256:
                raise ReconstructionError(f"pinned update asset SHA-256 mismatch: {member}")
            result[member] = {"sourcePath": str(pin.source), "bytes": before.st_size, "sha256": actual}
        finally:
            os.close(fd)
    return result


def read_stable_file(path: Path, label: str = "file", max_bytes: int | None = None) -> bytes:
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode):
            raise ReconstructionError(f"{label} must be a regular file: {path}")
        if max_bytes is not None and before.st_size > max_bytes:
            raise ReconstructionError(f"{label} exceeds the {max_bytes}-byte limit: {path}")
        with os.fdopen(fd, "rb", closefd=False) as stream:
            data = stream.read() if max_bytes is None else stream.read(max_bytes + 1)
        after = os.fstat(fd)
        if _stat_signature(before) != _stat_signature(after):
            raise ReconstructionError(f"{label} changed while being read: {path}")
        if max_bytes is not None and len(data) > max_bytes:
            raise ReconstructionError(f"{label} exceeds the {max_bytes}-byte limit: {path}")
        return data
    finally:
        os.close(fd)


def load_function(module_path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, module_path)
    if spec is None or spec.loader is None:
        raise ReconstructionError(f"cannot load helper: {module_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def regular_tree_inventory(root: Path, label: str) -> dict[str, dict[str, object]]:
    """Hash a directory through lstat and reject links/special files."""
    try:
        root_item = os.lstat(root)
    except FileNotFoundError as exc:
        raise ReconstructionError(f"{label} directory is missing: {root}") from exc
    if not stat.S_ISDIR(root_item.st_mode):
        raise ReconstructionError(f"{label} must be a directory: {root}")
    result: dict[str, dict[str, object]] = {}
    stack = [root]
    while stack:
        current = stack.pop()
        with os.scandir(current) as entries:
            for entry in sorted(entries, key=lambda value: value.name, reverse=True):
                item = entry.stat(follow_symlinks=False)
                relative = Path(entry.path).relative_to(root).as_posix()
                if stat.S_ISLNK(item.st_mode):
                    raise ReconstructionError(f"{label} contains a symlink: {relative}")
                if stat.S_ISDIR(item.st_mode):
                    stack.append(Path(entry.path))
                    continue
                if not stat.S_ISREG(item.st_mode):
                    raise ReconstructionError(f"{label} contains a special file: {relative}")
                result[relative] = {"bytes": item.st_size, "sha256": sha256_file(Path(entry.path), label)}
    return result


def validate_pinned_tree(root: Path, label: str, pins: dict[str, str]) -> dict[str, dict[str, object]]:
    inventory = regular_tree_inventory(root, label)
    if set(inventory) != set(pins):
        raise ReconstructionError(f"{label} inventory differs from its authenticated allowlist")
    for relative, expected in pins.items():
        if inventory[relative]["sha256"] != expected:
            raise ReconstructionError(f"{label} hash mismatch: {relative}")
    return inventory


def validate_json_catalog(path: Path) -> dict[str, object]:
    regular(path, "client catalog")
    data = read_stable_file(path, "client catalog")
    if sha256_bytes(data) != PINNED_CATALOG_SHA256:
        raise ReconstructionError("validated client catalog SHA-256 changed")
    value = json.loads(data.decode("utf-8"))
    if value.get("schema") != "wowforever.arm64-client-catalog" or value.get("schema_version") != 1:
        raise ReconstructionError("client catalog schema is not the validated ARM64 catalog")
    provenance = value.get("provenance", {})
    if (provenance.get("product"), provenance.get("version"), provenance.get("arch")) != (
        "wow_classic_beta", "1.60.1.70009", "arm64"
    ):
        raise ReconstructionError("client catalog provenance identity is not pinned")
    if value.get("completeness", {}).get("launch_ready") is not True:
        raise ReconstructionError("client catalog is not launch-ready")
    return value


def validate_zip_inventory(path: Path, expected_sha: str, label: str, expected_members: dict[str, str]) -> dict[str, object]:
    regular(path, label)
    data = read_stable_file(path, label)
    if sha256_bytes(data) != expected_sha:
        raise ReconstructionError(f"{label} SHA-256 changed")
    members: dict[str, dict[str, object]] = {}
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for info in archive.infolist():
            if info.filename in members:
                raise ReconstructionError(f"{label} contains a duplicate member: {info.filename}")
            relative = PurePosixPath(info.filename)
            if relative.is_absolute() or ".." in relative.parts or "\\" in info.filename:
                raise ReconstructionError(f"{label} contains an unsafe member: {info.filename}")
            mode = (info.external_attr >> 16) & 0o170000
            if mode not in (0, stat.S_IFREG, stat.S_IFDIR) or mode == stat.S_IFLNK:
                raise ReconstructionError(f"{label} contains a special member: {info.filename}")
            if info.is_dir():
                continue
            members[info.filename] = {"bytes": info.file_size, "sha256": sha256_bytes(archive.read(info))}
    if set(members) != set(expected_members):
        raise ReconstructionError(f"{label} member inventory differs from its authenticated allowlist")
    for name, expected in expected_members.items():
        if members[name]["sha256"] != expected:
            raise ReconstructionError(f"{label} member hash mismatch: {name}")
    return {"path": str(path), "sha256": expected_sha, "members": members}


def _safe_directory(root: Path, relative: PurePosixPath) -> Path:
    current = root
    for part in relative.parts:
        current = current / part
        try:
            item = os.lstat(current)
        except FileNotFoundError:
            current.mkdir()
            item = os.lstat(current)
        if not stat.S_ISDIR(item.st_mode):
            raise ReconstructionError(f"extraction ancestor is not a directory: {current}")
    return current


def _safe_zip_member(info: zipfile.ZipInfo) -> PurePosixPath:
    relative = PurePosixPath(info.filename)
    if not relative.parts or relative.is_absolute() or ".." in relative.parts or "\\" in info.filename:
        raise ReconstructionError(f"unsafe source archive member: {info.filename}")
    mode = (info.external_attr >> 16) & 0o170000
    if mode not in (0, stat.S_IFREG, stat.S_IFDIR):
        raise ReconstructionError(f"source archive contains a special entry: {info.filename}")
    return relative


def source_inventory(root: Path) -> dict[str, dict[str, object]]:
    return regular_tree_inventory(root, "source inventory")


def safe_extract(archive_path: Path, destination: Path) -> Path:
    """Extract one pinned ZIP through one no-follow descriptor and verify output."""
    regular(archive_path, "source archive")
    if destination.exists():
        raise ReconstructionError(f"new reconstruction directory already exists: {destination}")
    fd = os.open(archive_path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode):
            raise ReconstructionError("source archive must be regular")
        with os.fdopen(fd, "rb", closefd=False) as stream:
            digest = hashlib.sha256()
            while chunk := stream.read(1024 * 1024):
                digest.update(chunk)
            if digest.hexdigest() != PINNED_ARCHIVE_SHA256:
                raise ReconstructionError("pinned source archive SHA-256 changed")
            stream.seek(0)
            with zipfile.ZipFile(stream) as archive:
                infos = archive.infolist()
                names = set()
                roots = set()
                expected: dict[str, dict[str, object]] = {}
                for info in infos:
                    relative = _safe_zip_member(info)
                    if info.filename in names:
                        raise ReconstructionError(f"source archive contains a duplicate member: {info.filename}")
                    names.add(info.filename)
                    if relative.parts:
                        roots.add(relative.parts[0])
                    if not info.is_dir():
                        expected[relative.as_posix()] = {"bytes": info.file_size, "sha256": sha256_bytes(archive.read(info))}
                if len(roots) != 1:
                    raise ReconstructionError("source archive does not have one stable root")
                destination.mkdir(parents=True)
                for info in infos:
                    relative = _safe_zip_member(info)
                    target = destination.joinpath(*relative.parts)
                    if info.is_dir():
                        _safe_directory(destination, relative)
                        continue
                    _safe_directory(destination, relative.parent)
                    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC
                    output_fd = os.open(target, flags, 0o600)
                    try:
                        with os.fdopen(output_fd, "wb", closefd=False) as output:
                            with archive.open(info) as source_stream:
                                shutil.copyfileobj(source_stream, output, 1024 * 1024)
                            output.flush()
                            os.fsync(output.fileno())
                    finally:
                        os.close(output_fd)
                actual = source_inventory(destination)
                if set(actual) != set(expected) or any(actual[name] != expected[name] for name in expected):
                    raise ReconstructionError("source extraction inventory differs from the authenticated ZIP")
        after = os.fstat(fd)
        if _stat_signature(before) != _stat_signature(after):
            raise ReconstructionError("source archive changed during extraction")
    finally:
        os.close(fd)
    source = destination / next(iter(roots))
    if not (source / BUILD).is_file():
        raise ReconstructionError("extracted source root has no app/build.gradle.kts")
    return source


def _vaddr_to_offset(load_segments: list[tuple[int, int, int, int]], address: int) -> int:
    for vaddr, memsz, offset, filesz in load_segments:
        if vaddr <= address < vaddr + memsz and address - vaddr < filesz:
            return offset + address - vaddr
    raise ReconstructionError("ELF dynamic address is not inside a file-backed PT_LOAD")


def elf_audit(data: bytes, required_exports: frozenset[str]) -> dict[str, object]:
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise ReconstructionError("native input is not a little-endian ELF64")
    machine = struct.unpack_from("<H", data, 18)[0]
    if machine != 183:
        raise ReconstructionError(f"native input is not AArch64: e_machine={machine}")
    phoff, phentsize, phnum = struct.unpack_from("<QHH", data, 32)[0], struct.unpack_from("<H", data, 54)[0], struct.unpack_from("<H", data, 56)[0]
    loads: list[tuple[int, int, int, int]] = []
    dynamic = None
    aligns: list[int] = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        if offset + 56 > len(data):
            raise ReconstructionError("native ELF program headers exceed file")
        p_type, p_flags = struct.unpack_from("<II", data, offset)
        p_offset, p_vaddr, _p_paddr, p_filesz, p_memsz, p_align = struct.unpack_from("<QQQQQQ", data, offset + 8)
        if p_offset + p_filesz > len(data):
            raise ReconstructionError("native ELF segment exceeds file")
        if p_type == 1:
            loads.append((p_vaddr, p_memsz, p_offset, p_filesz))
            aligns.append(p_align)
        elif p_type == 2:
            dynamic = (p_offset, p_filesz)
    if not loads or any(align != 16384 for align in aligns):
        raise ReconstructionError("native ELF lacks the required 16KiB AArch64 load alignment")
    if dynamic is None:
        raise ReconstructionError("native ELF has no dynamic symbol table")
    tags: dict[int, int] = {}
    for offset in range(dynamic[0], dynamic[0] + dynamic[1], 16):
        tag, value = struct.unpack_from("<QQ", data, offset)
        if tag == 0:
            break
        tags[tag] = value
    for tag in (5, 6, 10, 11):
        if tag not in tags:
            raise ReconstructionError(f"native ELF dynamic tag {tag} is missing")
    strtab = _vaddr_to_offset(loads, tags[5])
    symtab = _vaddr_to_offset(loads, tags[6])
    strsz, syment = tags[10], tags[11]
    if syment < 24 or strtab + strsz > len(data) or strtab <= symtab:
        raise ReconstructionError("native ELF dynamic symbol table bounds are invalid")
    exports: set[str] = set()
    for offset in range(symtab, strtab, syment):
        if offset + 24 > len(data):
            break
        name, _info, _other, _shndx, _value, _size = struct.unpack_from("<IBBHQQ", data, offset)
        if name >= strsz:
            continue
        end = data.find(b"\0", strtab + name, strtab + strsz)
        if end >= 0:
            exports.add(data[strtab + name:end].decode("utf-8", errors="strict"))
    missing = sorted(required_exports - exports)
    if missing:
        raise ReconstructionError("native ELF is missing required JNI exports: " + ", ".join(missing))
    return {"bytes": len(data), "sha256": sha256_bytes(data), "elfClass": 64, "machine": "AArch64", "ptLoadAlignments": aligns, "exports": sorted(required_exports)}


def _read_zip_member(archive: zipfile.ZipFile, name: str) -> bytes:
    infos = [info for info in archive.infolist() if info.filename == name]
    if len(infos) != 1:
        raise ReconstructionError(f"authenticated APK member is not unique: {name}")
    info = infos[0]
    _safe_zip_member(info)
    return archive.read(info)


def validate_sanitized_c138_payloads(
    source: Path,
    inputs: AuthenticatedInputs,
) -> dict[str, object]:
    """Admit the exact serial redaction with a frozen historical or candidate provenance pair."""
    recipe_sha = sha256_file(APPLY_OVERLAY, "C138 recipe")
    provenance_sha = privacy_provenance_pin_for_recipe(recipe_sha)

    with zipfile.ZipFile(inputs.code64_apk) as archive:
        old_contract_bytes = _read_zip_member(archive, INTERRUPTED_DISPOSITION_MEMBER)
    if sha256_bytes(old_contract_bytes) != inputs.code64_payloads.get(INTERRUPTED_DISPOSITION_MEMBER):
        raise ReconstructionError("authenticated code64 disposition preimage mismatch")

    old_contract = json.loads(old_contract_bytes)
    if (
        not isinstance(old_contract, dict)
        or not isinstance(old_contract.get("device"), dict)
        or not isinstance(old_contract["device"].get("serial"), str)
        or not old_contract["device"]["serial"]
        or old_contract["device"]["serial"] == "DEVICE_SERIAL"
    ):
        raise ReconstructionError("authenticated code64 disposition has no serial field to redact")

    contract_source = read_stable_file(CURRENT_DISPOSITION_SOURCE, "C138 disposition source")
    contract_path = source / update_asset_source_relative(INTERRUPTED_DISPOSITION_MEMBER)
    contract = read_stable_file(contract_path, "C138 packaged disposition")
    new_contract = json.loads(contract)
    new_device = new_contract.get("device") if isinstance(new_contract, dict) else None
    if not isinstance(new_device, dict) or new_device.get("serial") != "DEVICE_SERIAL":
        raise ReconstructionError("C138 disposition does not use the approved serial placeholder")
    expected_contract = json.loads(old_contract_bytes)
    expected_contract["device"]["serial"] = "DEVICE_SERIAL"
    if new_contract != expected_contract:
        raise ReconstructionError("C138 disposition differs beyond the approved device.serial redaction")
    if contract != contract_source:
        raise ReconstructionError("packaged C138 disposition differs from its authenticated source")

    pinned_after = {
        CODE_PROVENANCE_MEMBER: provenance_sha,
        INTERRUPTED_DISPOSITION_MEMBER: C138_DISPOSITION_SHA256,
    }
    current_payloads = {INTERRUPTED_DISPOSITION_MEMBER: contract}
    current_payloads[CODE_PROVENANCE_MEMBER] = read_stable_file(
        source / update_asset_source_relative(CODE_PROVENANCE_MEMBER), "C138 packaged provenance"
    )
    for member, expected_sha256 in pinned_after.items():
        actual_sha256 = sha256_bytes(current_payloads[member])
        if actual_sha256 != expected_sha256:
            raise ReconstructionError(f"C138 sanitized payload does not match its frozen pin: {member}")

    provenance = json.loads(current_payloads[CODE_PROVENANCE_MEMBER])
    disposition = provenance.get("interruptedRunDisposition") if isinstance(provenance, dict) else None
    if (
        not isinstance(disposition, dict)
        or provenance.get("recipeSha256") != recipe_sha
        or disposition.get("sha256") != C138_DISPOSITION_SHA256
        or disposition.get("sourceSha256") != C138_DISPOSITION_SHA256
    ):
        raise ReconstructionError("C138 provenance does not bind the frozen recipe and disposition contract")

    return {
        "status": "PASS_EXACT_DEVICE_SERIAL_REDACTION",
        "changedField": "device.serial",
        "replacement": "DEVICE_SERIAL",
        "recipeSha256": recipe_sha,
        "payloads": {
            member: {
                "beforeSha256": inputs.code64_payloads[member],
                "afterSha256": pinned_after[member],
            }
            for member in (CODE_PROVENANCE_MEMBER, INTERRUPTED_DISPOSITION_MEMBER)
        },
    }

def authenticate_inputs(contract: Path) -> AuthenticatedInputs:
    regular(BASELINE, "code59 receipt")
    if sha256_bytes(read_stable_file(BASELINE, "code59 receipt")) != PINNED_BASELINE_SHA256:
        raise ReconstructionError("code59 receipt SHA-256 changed")
    regular(contract, "contract")
    if sha256_file(contract, "contract") != PINNED_CONTRACT_SHA256:
        raise ReconstructionError("contract SHA-256 changed")
    bundle_members = {
        "components/proton-11.0-90624-arm64ec.wcp": "137807f4145b1b440075f83b2b1b3501d19c4eb618784b1a28418652cae3db03",
        "components/dxvk-2.4.1-wow-aarch64-test.wcp": "f660b5c6422b3f93cec65664f309718942e58581b5b96fa2902d55d63ce1db8a",
    }
    bundle_manifest = validate_zip_inventory(RECIPE_BUNDLE, PINNED_BUNDLE_SHA256, "community bundle", bundle_members)
    probe_manifest = validate_pinned_tree(PROBES_ROOT, "diagnostic probes", PROBE_PINS)
    catalog = validate_json_catalog(CATALOG)
    update_assets = validate_pinned_update_assets()
    client_target_bytes, client_target_manifest = validate_client_target_source()
    regular(CODE62_NATIVE, "code62 JNI")
    if sha256_file(CODE62_NATIVE, "code62 JNI") != PINNED_CODE62_NATIVE_SHA256:
        raise ReconstructionError("code62 JNI SHA-256 changed")
    regular(RETAINED_APK, "retained code59 APK")
    if sha256_file(RETAINED_APK, "retained code59 APK") != PINNED_RETAINED_APK_SHA256:
        raise ReconstructionError("retained code59 APK SHA-256 changed")
    regular(CODE64_APK, "retained code64 APK")
    if sha256_file(CODE64_APK, "retained code64 APK") != PINNED_CODE64_APK_SHA256:
        raise ReconstructionError("retained code64 APK SHA-256 changed")
    regular(CODE64_AUDIT, "code64 APK audit")
    if sha256_file(CODE64_AUDIT, "code64 APK audit") != PINNED_CODE64_AUDIT_SHA256:
        raise ReconstructionError("code64 APK audit SHA-256 changed")
    regular(CODE64_NATIVE_SIDECAR, "code64 native provenance")
    if sha256_file(CODE64_NATIVE_SIDECAR, "code64 native provenance") != PINNED_CODE64_NATIVE_SIDECAR_SHA256:
        raise ReconstructionError("code64 native provenance SHA-256 changed")
    audit = json.loads(read_stable_file(CODE64_AUDIT, "code64 APK audit").decode("utf-8"))
    if audit.get("apk", {}).get("sha256") != PINNED_CODE64_APK_SHA256 or audit.get("aaptCertificateAudit", {}).get("versionCode") != 64:
        raise ReconstructionError("code64 APK audit identity is not pinned")
    sidecar = json.loads(read_stable_file(CODE64_NATIVE_SIDECAR, "code64 native provenance").decode("utf-8"))
    native_meta = sidecar.get("after", {}).get("nativeElf", {})
    if (native_meta.get("sha256"), native_meta.get("bytes"), native_meta.get("elfClass"), native_meta.get("machine")) != (
        PINNED_CODE64_NATIVE_SHA256, 939840, 64, "AArch64"
    ):
        raise ReconstructionError("code64 native sidecar identity is not pinned")
    with zipfile.ZipFile(io.BytesIO(read_stable_file(CODE64_APK, "retained code64 APK"))) as apk:
        names = [info.filename for info in apk.infolist()]
        if len(names) != len(set(names)):
            raise ReconstructionError("code64 APK contains duplicate members")
        payloads: dict[str, str] = {}
        for info in apk.infolist():
            _safe_zip_member(info)
            if info.filename.startswith("assets/") or info.filename.startswith("lib/"):
                payloads[info.filename] = sha256_bytes(apk.read(info))
        native_bytes = _read_zip_member(apk, CODE64_NATIVE_MEMBER)
    if len(payloads) != 174 or payloads.get(CODE64_NATIVE_MEMBER) != PINNED_CODE64_NATIVE_SHA256:
        raise ReconstructionError("code64 APK payload allowlist is not pinned")
    native_audit = elf_audit(native_bytes, REQUIRED_EXPORTS)
    if native_audit["sha256"] != PINNED_CODE64_NATIVE_SHA256:
        raise ReconstructionError("code64 APK JNI hash differs from ELF audit")
    return AuthenticatedInputs(
        RECIPE_BUNDLE, PROBES_ROOT, CATALOG, contract, CODE62_NATIVE, CODE64_APK, CODE64_AUDIT,
        CODE64_NATIVE_SIDECAR, payloads, native_bytes, native_audit,
        {
            "bundle": bundle_manifest,
            "probes": probe_manifest,
            "catalog": {"path": str(CATALOG), "sha256": PINNED_CATALOG_SHA256, "schema": catalog["schema"]},
            "pinnedUpdateAssets": pinned_update_asset_manifest() | {"validatedFiles": update_assets},
            "clientTarget": client_target_manifest,
        },
        client_target_bytes,
        client_target_manifest,
    )


def validate_new_controller_source() -> tuple[bytes, dict[str, object]]:
    """Authenticate current source separately from the unavailable historical pin."""
    baseline = json.loads(read_stable_file(BASELINE, "code59 receipt").decode("utf-8"))
    historical = next(
        (item for item in baseline.get("overlayAuthority", {}).get("bindings", [])
         if item.get("name") == "ForeverLaunchController.kt"),
        None,
    )
    if historical is None or historical.get("currentRepoSourceSHA256") != PINNED_HISTORICAL_CONTROLLER_SHA256:
        raise ReconstructionError("historical controller pin no longer matches the unchanged code59 receipt")
    regular(NEW_CONTROLLER_SOURCE, "reviewed new ForeverLaunchController source")
    data = read_stable_file(NEW_CONTROLLER_SOURCE, "reviewed new ForeverLaunchController source")
    digest = sha256_bytes(data)
    if digest != PINNED_NEW_CONTROLLER_SHA256:
        raise ReconstructionError("reviewed new ForeverLaunchController source hash changed")
    return data, {
        "historicalSha256": PINNED_HISTORICAL_CONTROLLER_SHA256,
        "sourcePath": str(NEW_CONTROLLER_SOURCE),
        "sourceSha256": digest,
        "targetPath": NEW_CONTROLLER_TARGET.as_posix(),
        "reviewStatus": "INDEPENDENTLY_REVIEWED_NEW_CANDIDATE_INPUT",
    }


def validate_new_controller_target(source: Path, expected: bytes) -> dict[str, object]:
    target = source / NEW_CONTROLLER_TARGET
    regular(target, "new candidate ForeverLaunchController target")
    actual = read_stable_file(target, "new candidate ForeverLaunchController target")
    if actual != expected or sha256_bytes(actual) != PINNED_NEW_CONTROLLER_SHA256:
        raise ReconstructionError("legacy recipe did not materialize the exact reviewed new controller input")
    return {
        "path": NEW_CONTROLLER_TARGET.as_posix(),
        "origin": "reviewed-new-controller-input",
        "sha256": PINNED_NEW_CONTROLLER_SHA256,
        "historicalSha256": PINNED_HISTORICAL_CONTROLLER_SHA256,
    }


def validate_bridge_source() -> tuple[bytes, dict[str, object]]:
    regular(BRIDGE_SOURCE, "reviewed production bridge source")
    data = read_stable_file(BRIDGE_SOURCE, "reviewed production bridge source")
    if sha256_bytes(data) != PINNED_BRIDGE_SHA256:
        raise ReconstructionError("reviewed production bridge source hash changed")
    text = data.decode("utf-8")
    required_declarations = (
        "external fun findOwnedGameProcesses",
        "external fun inspectOwnedGame",
        "external fun armSignalTrace",
        "external fun signalTraceStatus",
    )
    if "package com.wowforever.launcher" not in text or any(item not in text for item in required_declarations):
        raise ReconstructionError("reviewed production bridge source is missing an approved method")
    return data, {"origin": str(BRIDGE_SOURCE), "sha256": PINNED_BRIDGE_SHA256, "target": BRIDGE_TARGET.as_posix()}


def stage_new_bridge(source: Path, data: bytes) -> dict[str, object]:
    target = source / BRIDGE_TARGET
    if target.exists() or target.is_symlink():
        raise ReconstructionError(f"new production bridge destination collision: {BRIDGE_TARGET}")
    _safe_directory(source, PurePosixPath(*BRIDGE_TARGET.parts[:-1]))
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC
    fd = os.open(target, flags, 0o600)
    try:
        with os.fdopen(fd, "wb", closefd=False) as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
    finally:
        os.close(fd)
    actual = read_stable_file(target, "staged production bridge")
    if actual != data or sha256_bytes(actual) != PINNED_BRIDGE_SHA256:
        raise ReconstructionError("staged production bridge hash mismatch")
    return {"path": BRIDGE_TARGET.as_posix(), "origin": str(BRIDGE_SOURCE), "sha256": PINNED_BRIDGE_SHA256}


def run_code62_recipe(source: Path, inputs: AuthenticatedInputs) -> str:
    command = [
        sys.executable, str(APPLY_OVERLAY), "--source", str(source), "--bundle", str(inputs.bundle),
        "--probes", str(inputs.probes), "--catalog", str(inputs.catalog), "--native", str(inputs.code62_native),
        "--contract", str(inputs.contract), "--baseline-apk", str(RETAINED_APK),
    ]
    completed = subprocess.run(command, check=False, capture_output=True, text=True)
    if completed.returncode != 0:
        raise ReconstructionError("code59->62 recipe failed:\n" + (completed.stdout + completed.stderr)[-12000:])
    return "\n".join(line for line in completed.stdout.splitlines() if line.strip())


def replace_code64_native(source: Path, native_bytes: bytes) -> dict[str, object]:
    target = source / JNI_RELATIVE
    regular(target, "code62 JNI destination")
    if sha256_file(target, "code62 JNI destination") != PINNED_CODE62_NATIVE_SHA256:
        raise ReconstructionError("code62 JNI destination is not the retained preimage")
    parent = target.parent
    _safe_directory(source, PurePosixPath("app/src/main/jniLibs/arm64-v8a"))
    with tempfile.NamedTemporaryFile(dir=parent, prefix=".reconstruct-native-", delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(native_bytes)
        stream.flush()
        os.fsync(stream.fileno())
    try:
        os.replace(temporary, target)
    finally:
        if temporary.exists():
            temporary.unlink()
    if sha256_file(target, "code64 JNI destination") != PINNED_CODE64_NATIVE_SHA256:
        raise ReconstructionError("extracted code64 JNI hash mismatch")
    return {"path": JNI_RELATIVE.as_posix(), "sha256": PINNED_CODE64_NATIVE_SHA256, "origin": "retained-code64-apk-member"}


def authenticate_candidate_native() -> tuple[dict[str, object], bytes]:
    """Authenticate the reviewed candidate native against its pins and full provenance.

    Reuses the existing ``apply_functional_poc._validate_native_provenance``
    validator (independent before/after sidecar, current 23 compiled sources,
    approved API29/NDK24 toolchain, actual ELF size/SHA and 16KiB PT_LOAD
    audit) and additionally binds the 8 candidate JNI exports through the local
    ``elf_audit``. Returns the JSON-safe lineage binding and the raw SO bytes.
    """
    regular(CANDIDATE_NATIVE_SIDECAR, "candidate native provenance sidecar")
    if sha256_file(CANDIDATE_NATIVE_SIDECAR, "candidate native provenance sidecar") != PINNED_CANDIDATE_NATIVE_SIDECAR_SHA256:
        raise ReconstructionError("candidate native provenance sidecar SHA-256 changed")
    regular(CANDIDATE_NATIVE_SO, "candidate native JNI")
    candidate_stat = os.lstat(CANDIDATE_NATIVE_SO)
    if candidate_stat.st_size != PINNED_CANDIDATE_NATIVE_BYTES:
        raise ReconstructionError("candidate native JNI size differs from its explicit pin")
    if sha256_file(CANDIDATE_NATIVE_SO, "candidate native JNI") != PINNED_CANDIDATE_NATIVE_SHA256:
        raise ReconstructionError("candidate native JNI SHA-256 changed")
    functional = load_function(APPLY_FUNCTIONAL, "functional_native_provenance_for_candidate")
    sidecar, native, sidecar_sha, native_bytes = functional._validate_native_provenance(
        CANDIDATE_NATIVE_SIDECAR, CANDIDATE_NATIVE_SO
    )
    if sidecar_sha != PINNED_CANDIDATE_NATIVE_SIDECAR_SHA256:
        raise ReconstructionError("candidate native sidecar digest is not the reviewed pin")
    if sha256_bytes(native_bytes) != PINNED_CANDIDATE_NATIVE_SHA256 or len(native_bytes) != PINNED_CANDIDATE_NATIVE_BYTES:
        raise ReconstructionError("candidate native bytes are not the reviewed candidate payload")
    audit = elf_audit(native_bytes, CANDIDATE_REQUIRED_EXPORTS)
    if audit["sha256"] != PINNED_CANDIDATE_NATIVE_SHA256:
        raise ReconstructionError("candidate native ELF audit hash differs from its explicit pin")
    after = sidecar.get("after")
    if not isinstance(after, dict):
        raise ReconstructionError("candidate native provenance has no after phase")
    binding: dict[str, object] = {
        "member": CODE64_NATIVE_MEMBER,
        "beforeSha256": PINNED_CODE64_NATIVE_SHA256,
        "afterSha256": PINNED_CANDIDATE_NATIVE_SHA256,
        "bytes": PINNED_CANDIDATE_NATIVE_BYTES,
        "sourcePath": str(CANDIDATE_NATIVE_SO),
        "sidecarPath": str(CANDIDATE_NATIVE_SIDECAR),
        "sidecarSha256": PINNED_CANDIDATE_NATIVE_SIDECAR_SHA256,
        "origin": CANDIDATE_NATIVE_ORIGIN,
        "elfAudit": audit,
        "capability": CANDIDATE_NATIVE_CAPABILITY,
        "sourceSha256": after.get("sourceSha256"),
        "toolchain": after.get("toolchain"),
    }
    return binding, native_bytes


def replace_candidate_native(source: Path, candidate_bytes: bytes) -> dict[str, object]:
    """Replace the authenticated code64 preimage with the reviewed candidate native.

    The code64 JNI must still be the exact retained preimage; it is recorded as
    the before image, never rewritten in place without an explicit receipt.
    """
    if sha256_bytes(candidate_bytes) != PINNED_CANDIDATE_NATIVE_SHA256 or len(candidate_bytes) != PINNED_CANDIDATE_NATIVE_BYTES:
        raise ReconstructionError("candidate native bytes are not the reviewed candidate payload")
    target = source / JNI_RELATIVE
    regular(target, "code64 JNI preimage")
    if sha256_file(target, "code64 JNI preimage") != PINNED_CODE64_NATIVE_SHA256:
        raise ReconstructionError("code64 JNI preimage is not the retained code64 payload")
    parent = target.parent
    _safe_directory(source, PurePosixPath("app/src/main/jniLibs/arm64-v8a"))
    with tempfile.NamedTemporaryFile(dir=parent, prefix=".reconstruct-candidate-native-", delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(candidate_bytes)
        stream.flush()
        os.fsync(stream.fileno())
    try:
        os.replace(temporary, target)
    finally:
        if temporary.exists():
            temporary.unlink()
    if sha256_file(target, "candidate JNI destination") != PINNED_CANDIDATE_NATIVE_SHA256:
        raise ReconstructionError("staged candidate JNI hash mismatch")
    return {
        "path": JNI_RELATIVE.as_posix(),
        "beforeSha256": PINNED_CODE64_NATIVE_SHA256,
        "afterSha256": PINNED_CANDIDATE_NATIVE_SHA256,
        "origin": CANDIDATE_NATIVE_ORIGIN,
        "sourcePath": str(CANDIDATE_NATIVE_SO),
        "sidecarSha256": PINNED_CANDIDATE_NATIVE_SIDECAR_SHA256,
        "changed": True,
    }


def transform_build_62_to_candidate(text: str) -> str:
    if text.count("versionCode = ") != 1 or text.count('versionName = "') != 1:
        raise ReconstructionError("candidate build anchors are not unique")
    import re
    code = re.search(r"\bversionCode\s*=\s*(\d+)\b", text)
    name = re.search(r'\bversionName\s*=\s*"([^"]+)"', text)
    if not code or not name or (int(code.group(1)), name.group(1)) != SOURCE_VERSION:
        raise ReconstructionError("candidate transform requires exact code62 preimage")
    text = text.replace(f"versionCode = {SOURCE_VERSION[0]}", f"versionCode = {CANDIDATE_VERSION[0]}", 1)
    text = text.replace(f'versionName = "{SOURCE_VERSION[1]}"', f'versionName = "{CANDIDATE_VERSION[1]}"', 1)
    text = _ensure_candidate_server_profile_field(text)
    # The provider check extends framework Instrumentation and has no AndroidX
    # runner/Espresso dependency. Keep unavailable test-only packages out of its
    # classpath; main and JVM unit-test dependencies remain untouched.
    return text + '''

// FOREVER_AUTOLOGIN_PROVIDER: framework-only, synthetic androidTest harness.
android.defaultConfig.testInstrumentationRunner = "app.gamenative.ForeverFunctionalAutologinProviderInstrumentation"
configurations.named("androidTestImplementation") {
    // Replace only the stock Espresso/Compose/JUnit Android-test harness.
    // The custom provider instrumentation uses framework APIs exclusively.
    dependencies.clear()
}
'''


def _ensure_candidate_server_profile_field(text: str) -> str:
    """Build-time server profile for the candidate: Classic Forever, idempotent.

    Mirrors ensure_server_profile_field in apply_functional_poc.py without
    importing the old recipe: each recipe authenticates its own transform.
    """
    import re

    if "FOREVER_SERVER_PROFILE" in text:
        if '\\"classic_forever\\"' not in text:
            raise ReconstructionError("candidate server profile build field differs from classic_forever")
        if text.count("FOREVER_SERVER_PROFILE") != 1:
            raise ReconstructionError("candidate server profile build field anchor is not unique")
        return text
    name = re.search(r'\bversionName\s*=\s*"[^"]+"', text)
    if not name:
        raise ReconstructionError("candidate version name anchor is missing for the server profile field")
    line_end = text.index("\n", name.end())
    field = 'buildConfigField("String", "FOREVER_SERVER_PROFILE", "\\"classic_forever\\"")'
    return text[:line_end] + "\n        " + field + text[line_end:]


def read_text(source: Path, relative: Path) -> str:
    path = source / relative
    regular(path, str(relative))
    return path.read_text(encoding="utf-8")


def write_known(source: Path, relative: Path, data: bytes, origin: str) -> dict[str, object]:
    target = source / relative
    if target.exists() or target.is_symlink():
        regular(target, str(relative))
        if target.read_bytes() != data:
            raise ReconstructionError(f"unknown functional destination collision: {relative}")
        changed = False
    else:
        _safe_directory(source, PurePosixPath(*relative.parts[:-1]))
        target.write_bytes(data)
        changed = True
    return {"path": relative.as_posix(), "origin": origin, "sha256": sha256_bytes(data), "changed": changed}


def stage_functional_sources(source: Path) -> list[dict[str, object]]:
    overlay = APP_ROOT / "overlay"
    result = []
    for name in FUNCTIONAL_OVERLAY_NAMES:
        origin = overlay / name
        regular(origin, f"functional overlay {name}")
        relative = Path("app/src/test/java/app/gamenative" if name.endswith("Test.kt") else "app/src/main/java/app/gamenative") / name
        result.append(write_known(source, relative, origin.read_bytes(), f"repo-functional-overlay:{name}"))
    return result


def transform_manifest_branding(text: str) -> str:
    """Point the app icon and both launcher aliases at the Forever emblem.

    Fail-closed: every binding anchor must be present exactly once, and no
    upstream icon reference may survive. Permissions, exported flags, activity
    entries and the rest of the manifest are untouched.
    """
    app_icon = 'android:icon="${icon}"'
    if text.count(app_icon) != 1:
        raise ReconstructionError("branding application icon placeholder anchor is not unique")
    text = text.replace(
        app_icon,
        f'android:icon="{BRANDING_ICON}"\n        android:roundIcon="{BRANDING_ICON}"',
        1,
    )
    for old, label in (
        ('@mipmap/ic_launcher"', "default launcher alias icon"),
        ('@mipmap/ic_launcher_alt"', "alt launcher alias icon"),
    ):
        if text.count(old) != 1:
            raise ReconstructionError(f"branding {label} anchor is not unique")
        text = text.replace(old, f'{BRANDING_ICON}"', 1)

    activity_name = 'android:name="app.gamenative.ForeverFunctionalPocActivity"'
    if text.count(activity_name) != 1:
        raise ReconstructionError("branding functional entry activity anchor is not unique")
    block_start = text.rfind("<activity", 0, text.index(activity_name))
    block_end = text.index("</activity>", text.index(activity_name)) + len("</activity>")
    if block_start < 0:
        raise ReconstructionError("branding functional entry activity block is missing")
    block = text[block_start:block_end]
    theme = 'android:theme="@style/Theme.Pluvia"'
    if block.count(theme) != 1:
        raise ReconstructionError("branding functional entry theme anchor is not unique")
    block = block.replace(theme, f'android:theme="{BRANDING_SPLASH_THEME}"', 1)
    text = text[:block_start] + block + text[block_end:]

    for leftover in ('${icon}', '@mipmap/ic_launcher"', '@mipmap/ic_launcher_alt"'):
        if leftover in text:
            raise ReconstructionError(f"branding left an upstream icon binding: {leftover}")
    return text


def stage_branding_resources(source: Path) -> list[dict[str, object]]:
    """Stage only the explicitly admitted branding XML resources, fail-closed."""
    actual = {
        path.relative_to(BRANDING_ROOT).as_posix()
        for path in BRANDING_ROOT.rglob("*")
        if path.is_file()
    }
    expected = {origin for origin, _target in BRANDING_RESOURCE_FILES}
    if actual != expected:
        raise ReconstructionError(
            "branding resource admission differs from its explicit file list: "
            f"missing={sorted(expected - actual)}, extra={sorted(actual - expected)}"
        )
    result = []
    for origin_name, target_name in BRANDING_RESOURCE_FILES:
        origin = BRANDING_ROOT / origin_name
        regular(origin, f"branding resource {origin_name}")
        relative = Path(*PurePosixPath(target_name).parts)
        entry = write_known(source, relative, origin.read_bytes(), f"repo-branding-res:{origin_name}")
        entry["originSha256"] = sha256_bytes(origin.read_bytes())
        entry["reviewStatus"] = "EXPLICITLY_ADMITTED_BRANDING_RESOURCE"
        result.append(entry)
    return result


def _replace_authenticated_preimage(
    source: Path,
    relative: Path,
    postimage: bytes,
    expected_before: str,
    expected_after: str,
    origin: str,
) -> dict[str, object]:
    target = source / relative
    regular(target, f"historical updater-gate preimage {relative}")
    before = sha256_file(target, f"historical updater-gate preimage {relative}")
    if before != expected_before:
        raise ReconstructionError(f"historical updater-gate preimage hash mismatch: {relative}")
    origin_hash = sha256_bytes(postimage)
    if origin_hash != expected_after:
        raise ReconstructionError(f"reviewed updater-gate postimage hash mismatch: {origin}")

    with tempfile.NamedTemporaryFile(dir=target.parent, prefix=".updater-gate-", delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(postimage)
        stream.flush()
        os.fsync(stream.fileno())
    try:
        os.replace(temporary, target)
    finally:
        if temporary.exists():
            temporary.unlink()
    after = sha256_file(target, f"staged updater-gate postimage {relative}")
    if after != expected_after:
        raise ReconstructionError(f"staged updater-gate postimage hash mismatch: {relative}")
    return {
        "path": relative.as_posix(),
        "origin": origin,
        "beforeSha256": before,
        "afterSha256": after,
        "originSha256": origin_hash,
        "changed": True,
        "reviewStatus": "EXPLICITLY_REVIEWED_UPDATER_GATE_POSTIMAGE",
    }


def stage_wifi_runtime_backend(source: Path) -> dict[str, object]:
    """Bind the existing shared runtime/preload downloader, not game sockets."""
    relative = Path("app/src/main/java/app/gamenative/service/SteamService.kt")
    old = read_stable_file(source / relative, "runtime download backend")
    expected = "a9987702c7213e4262bc85b531104ca665fcbf230caead556bcc1188ba7d9743"
    if sha256_bytes(old) != expected:
        raise ReconstructionError("runtime download backend preimage is not the reviewed source")
    text = old.decode("utf-8")
    patches = (
        (
            'val req = Request.Builder().url(url).build()\n                http.newCall(req).execute().use { rsp ->',
            'val req = Request.Builder().url(url).build()\n                app.gamenative.ForeverWifiDownloads.newCall(http, req).execute().use { rsp ->',
        ),
        (
            'val primaryUrl = "https://downloads.gamenative.app/$fileName"',
            'app.gamenative.ForeverWifiDownloads.initialize(context)\n            val primaryUrl = "https://downloads.gamenative.app/$fileName"',
        ),
    )
    for before, after in patches:
        if text.count(before) != 1:
            raise ReconstructionError("runtime Wi-Fi patch anchor differs from reviewed source")
        text = text.replace(before, after, 1)
    data = text.encode("utf-8")
    return _replace_authenticated_preimage(
        source, relative, data, expected,
        sha256_bytes(data), "repo-wifi-runtime-backend",
    )


def stage_updater_gate_sources(source: Path) -> list[dict[str, object]]:
    """Stage only the explicitly admitted updater sources; preserve the historic CASC bindings."""
    try:
        actual_names = {item.name for item in UPDATER_GATE_ROOT.iterdir() if item.name.endswith(".kt")}
    except FileNotFoundError as exc:
        raise ReconstructionError(f"updater-gate source directory is missing: {UPDATER_GATE_ROOT}") from exc
    if actual_names != UPDATER_GATE_NAMES:
        raise ReconstructionError(
            "updater-gate Kotlin source admission differs from its explicit name list: "
            f"missing={sorted(UPDATER_GATE_NAMES - actual_names)}, extra={sorted(actual_names - UPDATER_GATE_NAMES)}"
        )

    result: list[dict[str, object]] = []
    for names, source_root in (
        (UPDATER_GATE_MAIN_NAMES, Path("app/src/main/java/app/gamenative")),
        (UPDATER_GATE_TEST_NAMES, Path("app/src/test/java/app/gamenative")),
    ):
        for name in names:
            origin = UPDATER_GATE_ROOT / name
            data = read_stable_file(origin, f"reviewed updater-gate source {name}")
            relative = source_root / name
            entry = write_known(source, relative, data, f"repo-updater-gate:{name}")
            entry["originSha256"] = sha256_bytes(data)
            entry["reviewStatus"] = "EXPLICITLY_ADMITTED_UPDATER_GATE_SOURCE"
            result.append(entry)

    for name, target_root, preimages, postimages in (
        ("ForeverCasc.kt", Path("app/src/main/java/app/gamenative"), PINNED_UPDATER_CASC_PREIMAGES, PINNED_UPDATER_CASC_POSTIMAGES),
        ("ForeverCascTest.kt", Path("app/src/test/java/app/gamenative"), PINNED_UPDATER_CASC_PREIMAGES, PINNED_UPDATER_CASC_POSTIMAGES),
        ("ForeverBlte.kt", Path("app/src/main/java/app/gamenative"), PINNED_UPDATER_BLTE_PREIMAGES, PINNED_UPDATER_BLTE_POSTIMAGES),
    ):
        origin = UPDATER_GATE_ROOT / name
        data = read_stable_file(origin, f"reviewed updater-gate postimage {name}")
        result.append(
            _replace_authenticated_preimage(
                source,
                target_root / name,
                data,
                preimages[name],
                postimages[name],
                f"repo-updater-gate:{name}",
            )
        )
    return result


def stage_pinned_update_assets(source: Path) -> list[dict[str, object]]:
    """Stream the three authenticated metadata assets into fresh Android assets paths."""
    validate_pinned_update_assets()
    result: list[dict[str, object]] = []
    for member, pin in PINNED_UPDATE_ASSETS.items():
        relative_member = PurePosixPath(member)
        if relative_member.name.endswith(".gz"):
            raise ReconstructionError(f"AAPT would expand the pinned gzip package member: {member}")
        relative = Path("app/src/main").joinpath(*relative_member.parts)
        target = source / relative
        if target.exists() or target.is_symlink():
            raise ReconstructionError(f"pinned update asset destination collision: {relative}")
        _safe_directory(source, PurePosixPath(*relative.parts[:-1]))
        input_fd = os.open(pin.source, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
        output_fd = -1
        try:
            before = os.fstat(input_fd)
            if not stat.S_ISREG(before.st_mode) or before.st_size != pin.size:
                raise ReconstructionError(f"pinned update asset is not the authenticated regular file: {pin.source}")
            flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC
            output_fd = os.open(target, flags, 0o600)
            digest = hashlib.sha256()
            copied = 0
            with os.fdopen(input_fd, "rb", closefd=False) as input_stream:
                with os.fdopen(output_fd, "wb", closefd=False) as output_stream:
                    while chunk := input_stream.read(1024 * 1024):
                        digest.update(chunk)
                        copied += len(chunk)
                        output_stream.write(chunk)
                    output_stream.flush()
                    os.fsync(output_stream.fileno())
            after = os.fstat(input_fd)
            actual = digest.hexdigest()
            if _stat_signature(before) != _stat_signature(after):
                raise ReconstructionError(f"pinned update asset changed during staging: {pin.source}")
            if copied != pin.size or actual != pin.sha256:
                raise ReconstructionError(f"pinned update asset changed during staging: {member}")
        finally:
            os.close(input_fd)
            if output_fd >= 0:
                os.close(output_fd)
        result.append(
            {
                "path": relative.as_posix(),
                "origin": f"pinned-update-metadata:{UPDATE_ASSET_GENERATION}",
                "sourcePath": str(pin.source),
                "bytes": pin.size,
                "beforeSha256": None,
                "afterSha256": pin.sha256,
                "originSha256": pin.sha256,
                "member": member,
                "generation": UPDATE_ASSET_GENERATION,
                "changed": True,
            }
        )
    return result


def stage_client_target_asset(
    source: Path, target_bytes: bytes, target_manifest: dict[str, object]
) -> dict[str, object]:
    """Package the exact bounded public target contract authenticated before extraction."""
    current, current_manifest = validate_client_target_source()
    if current != target_bytes or current_manifest != target_manifest:
        raise ReconstructionError("public client target changed after it was authenticated")
    relative_member = PurePosixPath(CLIENT_TARGET_MEMBER)
    relative = Path("app/src/main").joinpath(*relative_member.parts)
    target = source / relative
    if target.exists() or target.is_symlink():
        raise ReconstructionError(f"public client target destination collision: {relative}")
    _safe_directory(source, PurePosixPath(*relative.parts[:-1]))
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC
    fd = os.open(target, flags, 0o600)
    try:
        with os.fdopen(fd, "wb", closefd=False) as stream:
            stream.write(target_bytes)
            stream.flush()
            os.fsync(stream.fileno())
    finally:
        os.close(fd)
    actual = read_stable_file(target, "staged public client target", max_bytes=CLIENT_TARGET_MAX_BYTES)
    if actual != target_bytes or sha256_bytes(actual) != target_manifest["sha256"]:
        raise ReconstructionError("staged public client target differs from its authenticated source")
    return {
        "path": relative.as_posix(),
        "origin": "public-client-target-contract",
        "sourcePath": str(CLIENT_TARGET_SOURCE),
        "member": CLIENT_TARGET_MEMBER,
        "bytes": len(actual),
        "sha256": str(target_manifest["sha256"]),
        "authority": CLIENT_TARGET_AUTHORITY,
        "targetBindings": target_manifest["bindings"],
        "changed": True,
    }


def stage_provider_validation(source: Path) -> list[dict[str, object]]:
    """Stage the synthetic provider harness in androidTest, never the main APK."""
    root = APP_ROOT / "instrumentation"
    mapping = {
        "ForeverFunctionalAutologinProviderInstrumentation.kt":
            Path("app/src/androidTest/java/app/gamenative/ForeverFunctionalAutologinProviderInstrumentation.kt"),
        "AndroidManifest.xml": Path("app/src/androidTest/AndroidManifest.xml"),
    }
    result = []
    for name, destination in mapping.items():
        origin = root / name
        regular(origin, f"provider validation source {name}")
        result.append(write_known(source, destination, read_stable_file(origin), f"repo-android-test-only:{name}"))
    return result


def source_payload_inventory(source: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for root in ASSET_ROOTS:
        absolute = source / root
        if not absolute.is_dir():
            continue
        for relative, item in regular_tree_inventory(absolute, f"source payload {root}").items():
            local = (root / relative).as_posix()
            if local.startswith("app/src/main/assets/"):
                packaged = "assets/" + local.removeprefix("app/src/main/assets/")
            elif local.startswith("app/src/modern/assets/"):
                packaged = "assets/" + local.removeprefix("app/src/modern/assets/")
            else:
                packaged = "lib/" + local.removeprefix("app/src/main/jniLibs/")
            result[packaged] = item["sha256"]
    return result


def update_asset_source_relative(member: str) -> Path:
    relative = PurePosixPath(member)
    if not relative.parts or relative.parts[0] != "assets" or relative.is_absolute() or ".." in relative.parts:
        raise ReconstructionError(f"unsafe packaged update-asset member: {member}")
    return Path("app/src/main/assets").joinpath(*relative.parts[1:])


def compare_code64_payloads(
    source: Path,
    inputs: AuthenticatedInputs,
    code62_payloads: dict[str, str],
    candidate_native_sha256: str | None = None,
    allow_sanitized_privacy_delta: bool = False,
) -> dict[str, object]:
    final = source_payload_inventory(source)
    update_pins = update_asset_pins(inputs)
    update_members = set(update_pins)
    if update_members & set(inputs.code64_payloads):
        raise ReconstructionError("pinned update metadata collides with an authenticated code64 payload")
    if candidate_native_sha256 is not None and candidate_native_sha256 != PINNED_CANDIDATE_NATIVE_SHA256:
        raise ReconstructionError("candidate native pin is not the reviewed candidate payload")
    expected: dict[str, str] = dict(inputs.code64_payloads)
    for key in update_members:
        expected[key] = update_pins[key].sha256
    if allow_sanitized_privacy_delta:
        recipe_sha = sha256_file(APPLY_OVERLAY, "privacy recipe")
        expected[CODE_PROVENANCE_MEMBER] = privacy_provenance_pin_for_recipe(recipe_sha)
        expected[INTERRUPTED_DISPOSITION_MEMBER] = C138_DISPOSITION_SHA256
    if candidate_native_sha256 is not None:
        expected[CODE64_NATIVE_MEMBER] = candidate_native_sha256
    compare_paths = (set(inputs.code64_payloads) - {FUNCTIONAL_MARKER_MEMBER}) | update_members
    source_compare = {key: value for key, value in final.items() if key != FUNCTIONAL_MARKER_MEMBER}
    if set(source_compare) != compare_paths:
        missing = sorted(compare_paths - set(source_compare))
        extra = sorted(set(source_compare) - compare_paths)
        raise ReconstructionError(f"code64 payload path parity failed: missing={missing}, extra={extra}")
    mismatches = sorted(key for key in compare_paths if source_compare[key] != expected[key])
    if mismatches:
        raise ReconstructionError("code64/pinned update payload hash parity failed: " + ", ".join(mismatches))
    changed = sorted(key for key in compare_paths if code62_payloads.get(key) != source_compare[key])
    non_metadata_runtime_changes = [
        key for key in changed if key != CODE64_NATIVE_MEMBER and key not in update_members
    ]
    guarded_patch_native_changed = (
        candidate_native_sha256 is not None
        and candidate_native_sha256 != inputs.code64_payloads.get(CODE64_NATIVE_MEMBER)
        and source_compare.get(CODE64_NATIVE_MEMBER) == candidate_native_sha256
    )
    candidate_native: dict[str, object] | None = None
    if candidate_native_sha256 is not None:
        candidate_native = {
            "member": CODE64_NATIVE_MEMBER,
            "beforeSha256": PINNED_CODE64_NATIVE_SHA256,
            "afterSha256": candidate_native_sha256,
            "bytes": PINNED_CANDIDATE_NATIVE_BYTES,
            "sidecarSha256": PINNED_CANDIDATE_NATIVE_SIDECAR_SHA256,
            "origin": CANDIDATE_NATIVE_ORIGIN,
            "capability": CANDIDATE_NATIVE_CAPABILITY,
        }
    return {
        "status": "PASS",
        "code64PayloadMembers": len(inputs.code64_payloads),
        "sourcePayloadMembers": len(final),
        "excluded": [FUNCTIONAL_MARKER_MEMBER],
        "changedFromCode62": changed,
        "addedPinnedUpdateMetadata": sorted(update_members),
        "nativeRuntimeDriverChanged": bool(non_metadata_runtime_changes),
        "readOnlyNativeChanged": False,
        "guardedPatchNativeChanged": guarded_patch_native_changed,
        "candidateNative": candidate_native,
    }


def asset_origins(
    source: Path,
    code62_provenance: dict[str, object],
    baseline: dict[str, object],
    generated_paths: set[str],
    client_target_manifest: dict[str, object],
) -> dict[str, dict[str, object]]:
    apk_hashes = {}
    for group in baseline.get("inputGroups", {}).values():
        apk_hashes.update(group.get("sha256", {}))
    resources = code62_provenance.get("resources", {})
    archive_hashes: dict[str, str] = {}
    with zipfile.ZipFile(UPSTREAM_ARCHIVE) as archive:
        prefix = next(name for name in archive.namelist() if name.endswith("/app/build.gradle.kts"))[:-len("app/build.gradle.kts")]
        for info in archive.infolist():
            if info.is_dir() or not info.filename.startswith(prefix + "app/"):
                continue
            relative = info.filename[len(prefix):]
            archive_hashes[relative] = sha256_bytes(archive.read(info))
    result = {}
    for root in ASSET_ROOTS:
        absolute = source / root
        if not absolute.is_dir():
            continue
        for path in sorted(absolute.rglob("*")):
            if not path.is_file():
                continue
            relative = path.relative_to(source).as_posix()
            if relative.startswith("app/src/main/jniLibs/"):
                packaged = "lib/" + relative.removeprefix("app/src/main/jniLibs/")
            else:
                packaged = relative.removeprefix("app/src/main/")
            if relative.startswith("app/src/modern/assets/"):
                packaged = relative.removeprefix("app/src/modern/")
            digest = sha256_file(path, "source payload")
            if packaged in apk_hashes and apk_hashes[packaged] == digest:
                origin = {"kind": "retained-code59-apk", "sha256": digest, "member": packaged}
            elif packaged in PINNED_UPDATE_ASSETS:
                pin = PINNED_UPDATE_ASSETS[packaged]
                if digest != pin.sha256 or path.stat().st_size != pin.size:
                    raise ReconstructionError(f"pinned update asset changed after staging: {relative}")
                origin = {
                    "kind": "pinned-update-metadata",
                    "sha256": digest,
                    "bytes": pin.size,
                    "member": packaged,
                    "sourcePath": str(pin.source),
                    "generation": UPDATE_ASSET_GENERATION,
                }
            elif packaged == CLIENT_TARGET_MEMBER:
                if (
                    digest != client_target_manifest.get("sha256")
                    or path.stat().st_size != client_target_manifest.get("bytes")
                ):
                    raise ReconstructionError(f"public client target changed after staging: {relative}")
                origin = {
                    "kind": "public-client-target-contract",
                    "sha256": digest,
                    "bytes": client_target_manifest["bytes"],
                    "member": CLIENT_TARGET_MEMBER,
                    "sourcePath": str(CLIENT_TARGET_SOURCE),
                    "authority": CLIENT_TARGET_AUTHORITY,
                    "targetBindings": client_target_manifest["bindings"],
                }
            elif packaged in resources and resources[packaged].get("fullSHA256") == digest:
                origin = {"kind": "code62-pinned-resource", "sha256": digest, "sourceRecipe": resources[packaged].get("sourceRecipe")}
            elif relative == JNI_RELATIVE.as_posix() and digest in (
                PINNED_CODE64_NATIVE_SHA256, PINNED_CANDIDATE_NATIVE_SHA256
            ):
                if digest == PINNED_CANDIDATE_NATIVE_SHA256:
                    origin = {
                        "kind": CANDIDATE_NATIVE_ORIGIN,
                        "sha256": digest,
                        "member": CODE64_NATIVE_MEMBER,
                        "sourcePath": str(CANDIDATE_NATIVE_SO),
                        "sidecarSha256": PINNED_CANDIDATE_NATIVE_SIDECAR_SHA256,
                    }
                else:
                    origin = {"kind": "retained-code64-apk", "sha256": digest, "member": CODE64_NATIVE_MEMBER}
            elif relative in archive_hashes and archive_hashes[relative] == digest:
                origin = {"kind": "pinned-upstream-archive", "sha256": digest, "member": relative}
            elif relative == CODE62_PROVENANCE.as_posix():
                origin = {"kind": "code62-recipe-provenance", "sha256": digest, "recipe": str(APPLY_OVERLAY)}
            elif relative in generated_paths:
                origin = {"kind": "code62-recipe-generated", "sha256": digest, "recipe": str(APPLY_OVERLAY)}
            elif relative == MARKER.as_posix():
                origin = {"kind": "candidate-provenance", "sha256": digest}
            else:
                raise ReconstructionError(f"unaccounted mutable asset: {relative}")
            result[relative] = origin
    return result


def candidate_marker(code62_inventory: dict[str, dict[str, object]], transforms: list[dict[str, object]],
                     native_runtime_driver_changed: bool, controller: dict[str, object], bridge: dict[str, object],
                     candidate_native: dict[str, object]) -> bytes:
    value = {
        "schema": 1,
        "variant": "wowforever.functional-launcher-ux/new-candidate-source-lineage",
        "package": "com.wowforever.arm64probe",
        "versionCode": CANDIDATE_VERSION[0],
        "versionName": CANDIDATE_VERSION[1],
        "lineage": {
            "effectiveSource": "code62-plus-reviewed-new-inputs",
            "status": "NEW candidate source lineage; no historical parity claim",
            "sourceVersion": {"code": SOURCE_VERSION[0], "name": SOURCE_VERSION[1]},
            "sourceInventorySha256": sha256_bytes(json.dumps(code62_inventory, sort_keys=True).encode()),
            "functionalRecipeSha256": sha256_file(APPLY_FUNCTIONAL, "functional recipe"),
            "candidateRecipeSha256": sha256_file(Path(__file__), "candidate recipe"),
        },
        "controller": controller,
        "productionBridge": bridge,
        "nativeRuntimeDriverChanged": native_runtime_driver_changed,
        "readOnlyNativeChanged": False,
        "guardedPatchNativeChanged": True,
        "candidateNative": candidate_native,
        "assistedEntrySecurityGate": {
            "status": "ENABLED_PHYSICAL_CAPTURE_VERIFICATION_PENDING",
            "enabled": True,
            "physicalCaptureVerification": "PENDING",
        },
        "transforms": transforms,
    }
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def apply_candidate_source_transforms(source: Path, functional) -> list[dict[str, object]]:
    transforms = []
    for relative, transform in (
        (BUILD, transform_build_62_to_candidate),
        (MANIFEST, functional.transform_manifest),
        (XSERVER, functional.transform_xserver),
        (functional.PULSE_AUDIO, functional.transform_pulse_audio),
    ):
        before = (source / relative).read_bytes()
        after = transform(before.decode("utf-8")).encode("utf-8")
        if after == before:
            raise ReconstructionError(f"candidate transform made no change: {relative}")
        (source / relative).write_bytes(after)
        transforms.append({"path": relative.as_posix(), "beforeSha256": sha256_bytes(before), "afterSha256": sha256_bytes(after)})
    return transforms


def reconstruct(args: argparse.Namespace) -> Path:
    work = args.work_dir.resolve()
    source_parent = work / "source"
    inputs = authenticate_inputs(args.contract.resolve())
    candidate_native_binding, candidate_native_bytes = authenticate_candidate_native()
    controller_bytes, controller = validate_new_controller_source()
    bridge_bytes, bridge = validate_bridge_source()
    source = safe_extract(args.archive.resolve(), source_parent)
    before_inventory = source_inventory(source)
    recipe_output = run_code62_recipe(source, inputs)
    code62_inventory = source_inventory(source)
    controller_entry = validate_new_controller_target(source, controller_bytes)
    version = read_text(source, BUILD)
    if "versionCode = 62" not in version or 'versionName = "0.2.38-automated"' not in version:
        raise ReconstructionError("existing bounded recipe did not produce exact code62")
    code62_payloads = source_payload_inventory(source)
    privacy_delta = validate_sanitized_c138_payloads(source, inputs)
    native_entry = replace_code64_native(source, inputs.code64_native_bytes)
    candidate_native_entry = replace_candidate_native(source, candidate_native_bytes)
    bridge_entry = stage_new_bridge(source, bridge_bytes)
    functional = load_function(APPLY_FUNCTIONAL, "functional_recipe_for_reconstruction")
    transforms = apply_candidate_source_transforms(source, functional)
    transforms.extend(stage_functional_sources(source))
    before_branding = (source / MANIFEST).read_bytes()
    after_branding = transform_manifest_branding(before_branding.decode("utf-8")).encode("utf-8")
    if after_branding == before_branding:
        raise ReconstructionError(f"candidate branding transform made no change: {MANIFEST}")
    (source / MANIFEST).write_bytes(after_branding)
    transforms.append({"path": MANIFEST.as_posix(), "beforeSha256": sha256_bytes(before_branding), "afterSha256": sha256_bytes(after_branding), "kind": "candidate-branding-manifest"})
    transforms.extend(stage_branding_resources(source))
    transforms.extend(stage_provider_validation(source))
    transforms.extend(stage_updater_gate_sources(source))
    transforms.append(stage_wifi_runtime_backend(source))
    transforms.extend(stage_pinned_update_assets(source))
    transforms.append(stage_client_target_asset(source, inputs.client_target_bytes, inputs.client_target_manifest))
    parity = compare_code64_payloads(
        source, inputs, code62_payloads, PINNED_CANDIDATE_NATIVE_SHA256,
        allow_sanitized_privacy_delta=True,
    )
    final_inventory = source_inventory(source)
    transforms.append(native_entry)
    transforms.append(candidate_native_entry)
    transforms.append(bridge_entry)
    transforms.append(controller_entry)
    marker_data = candidate_marker(code62_inventory, transforms, parity["nativeRuntimeDriverChanged"], controller, bridge, candidate_native_binding)
    marker_entry = write_known(source, MARKER, marker_data, "candidate-provenance")
    transforms.append(marker_entry)
    baseline = json.loads(read_stable_file(BASELINE, "code59 receipt").decode("utf-8"))
    code62_provenance = json.loads((source / CODE62_PROVENANCE).read_text(encoding="utf-8"))
    receipt = {
        "schema": 2,
        "kind": "functional-source-reconstruction",
        "candidate": {"code": CANDIDATE_VERSION[0], "name": CANDIDATE_VERSION[1]},
        "source": {"code": SOURCE_VERSION[0], "name": SOURCE_VERSION[1]},
        "sourceLineage": {
            "status": "NEW candidate source lineage; no historical parity claim",
            "historicalControllerSha256": PINNED_HISTORICAL_CONTROLLER_SHA256,
            "reviewedController": controller,
            "productionBridge": bridge,
        },
        "preparedTree": str(source),
        "inputs": {
            "sourceArchive": {"path": str(args.archive), "sha256": PINNED_ARCHIVE_SHA256},
            "code59Receipt": {"path": str(BASELINE), "sha256": PINNED_BASELINE_SHA256},
            "retainedCode59Apk": {"path": str(RETAINED_APK), "sha256": PINNED_RETAINED_APK_SHA256},
            "communityBundle": inputs.input_manifests["bundle"],
            "probes": {"path": str(PROBES_ROOT), "inventory": inputs.input_manifests["probes"]},
            "catalog": inputs.input_manifests["catalog"],
            "contract": {"path": str(inputs.contract), "sha256": PINNED_CONTRACT_SHA256},
            "code62Native": {"path": str(CODE62_NATIVE), "sha256": PINNED_CODE62_NATIVE_SHA256},
            "code64Apk": {"path": str(CODE64_APK), "sha256": PINNED_CODE64_APK_SHA256},
            "code64NativeMember": {"apkMember": CODE64_NATIVE_MEMBER, "sha256": PINNED_CODE64_NATIVE_SHA256, "elf": inputs.code64_native_audit},
            "candidateNative": candidate_native_binding,
            "pinnedUpdateAssets": inputs.input_manifests["pinnedUpdateAssets"],
            "clientTarget": inputs.client_target_manifest,
        },
        "code59To62": {"recipe": str(APPLY_OVERLAY), "stdout": recipe_output, "beforeInventory": before_inventory, "afterInventory": code62_inventory, "newControllerInput": controller_entry},
        "code62ToCandidate": {"explicitPreimage": {"code": SOURCE_VERSION[0], "name": SOURCE_VERSION[1]}, "transforms": transforms},
        "code64PayloadParity": parity,
        "approvedPrivacySanitization": privacy_delta,
        "assetOrigins": asset_origins(
            source, code62_provenance, baseline, set(code62_inventory) - set(before_inventory),
            inputs.client_target_manifest,
        ),
        "finalInventory": source_inventory(source),
    }
    receipt_path = work / "reconstruction-receipt.json"
    receipt_path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return receipt_path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--archive", type=Path, default=UPSTREAM_ARCHIVE)
    parser.add_argument("--contract", type=Path, default=ROOT / "forever/contract.json")
    args = parser.parse_args()
    print(reconstruct(args))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
