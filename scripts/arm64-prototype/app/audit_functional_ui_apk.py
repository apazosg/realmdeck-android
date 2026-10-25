#!/usr/bin/env python3
"""Audit the rebuilt UI APK against its explicit source receipt and code64 payloads.

DEX descriptors are inventoried, not presented as a proof of method equivalence.
Code provenance comes from the recorded isolated source build, not DEX reuse.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile


TOOLS = Path('/home/adrian/android-sdk/build-tools/36.0.0')
CERTIFICATE = '2b082e8200e066e665f73e71f547f827d6674634298024cb0b823be22adb242b'
C138_PRIVACY_MEMBERS = {
    'assets/forever/provenance.json',
    'assets/forever/wine-interrupted-run-disposition-contract.json',
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def check_descriptor_delta(added: set[str], removed: set[str]) -> None:
    # Full recompilation changes method encodings/indices; only descriptor
    # additions/removals are constrained here. Core source lineage is checked
    # independently against the reconstruction inventory.
    prefixes = (
        'Lapp/gamenative/ForeverFunctionalLauncherUx',
        'Lapp/gamenative/ForeverFunctionalUiText',
        'Lapp/gamenative/ForeverFunctionalIcons',
        'Lapp/gamenative/ForeverFunctionalDisplay',
        'Lapp/gamenative/ForeverFunctionalClientStorage',
        'Lapp/gamenative/ForeverFunctionalPocActivity',
        'Lapp/gamenative/ForeverFunctionalServerProfiles',
        'Lapp/gamenative/ForeverFunctionalGpu',
        'Lapp/gamenative/ForeverFunctionalAutologin',
        'Lapp/gamenative/ComposableSingletons$ForeverFunctionalPocActivityKt',
        'Lapp/gamenative/ComposableSingletons$ForeverFunctionalGpuCompatibilityKt',
        'Lapp/gamenative/ComposableSingletons$ForeverFunctionalAutologinSettingsKt',
        'Lapp/gamenative/ForeverCasc',
        'Lapp/gamenative/ForeverBlte',
        'Lapp/gamenative/ForeverClientUpdateCatalog',
        'Lapp/gamenative/ForeverClientUpdate',
        'Lapp/gamenative/ForeverClientUpdateTransfer',
        'Lapp/gamenative/ForeverClientOperationLease',
        'Lapp/gamenative/ForeverClientUpdaterQualification',
        'Lapp/gamenative/ForeverClientUpdater',
        'Lapp/gamenative/ForeverClientUpdateTransaction',
        'Lapp/gamenative/ForeverClientRuntimeGate',
        'Lapp/gamenative/ForeverClientUpdateService',
        'Lapp/gamenative/ForeverClientManagementKt',
        'Lapp/gamenative/ComposableSingletons$ForeverClientManagementKt',
        'Lapp/gamenative/ForeverTact',
        'Lapp/gamenative/ForeverClientCatalogResolver',
        'Lapp/gamenative/ForeverClientMetadataTransport',
        'Lapp/gamenative/ForeverClientTargetBuild',
        'Lapp/gamenative/RuntimeIdentity',
        'Lapp/gamenative/RuntimeObservation',
        'Lapp/gamenative/GuestTicket',
        'Lapp/gamenative/ForeverGuestRecoveryProof',
        'Lapp/gamenative/ForeverClientRuntimeObserver',
        'Lapp/gamenative/ForeverClientProcAccess',
        'Lapp/gamenative/AndroidForeverClientProcAccess',
        'Lapp/gamenative/ProcObservationFailure',
        'Lapp/gamenative/FileFingerprint',
        'Lapp/gamenative/InputFile',
        'Lapp/gamenative/UpdateStagingArea',
        'Lapp/gamenative/TransactionPlan',
        'Lapp/gamenative/ClientManagementOutcome',
        'Lapp/gamenative/UpdaterUiError',
        'Lapp/gamenative/MetadataTransportFaultPoint;',
        'Lapp/gamenative/MetadataUiProgress;',
    )
    exact_descriptors = {
        # Bounded aggregate-only fetch pipeline diagnostics; no new prefix.
        'Lapp/gamenative/FetchPipelineDiagnostics;',
        'Lapp/gamenative/FetchPipelineDiagnostics$$ExternalSyntheticLambda0;',
        'Lapp/gamenative/FetchPipelineDiagnostics$$ExternalSyntheticLambda1;',
        'Lapp/gamenative/FetchPipelineDiagnostics$$ExternalSyntheticLambda2;',
        'Lapp/gamenative/FetchPipelineDiagnostics$$ExternalSyntheticLambda3;',
        'Lapp/gamenative/FetchPipelineDiagnostics$$ExternalSyntheticLambda4;',
        'Lapp/gamenative/FetchPipelineDiagnostics$$ExternalSyntheticLambda5;',
        # C141: reviewed typed summary, its bounded callbacks/IO writer and
        # the existing session observer wrapper. Exact names, not a new prefix.
        'Lapp/gamenative/ForeverFunctionalPatchSummary;',
        'Lapp/gamenative/ForeverFunctionalPatchSummary$Companion;',
        'Lapp/gamenative/ForeverFunctionalPatchSummary$$ExternalSyntheticLambda0;',
        'Lapp/gamenative/ForeverFunctionalPatchSummary$$ExternalSyntheticLambda1;',
        'Lapp/gamenative/ForeverFunctionalPatchSummary$$ExternalSyntheticLambda2;',
        'Lapp/gamenative/ForeverFunctionalPatchSummary$$ExternalSyntheticLambda3;',
        'Lapp/gamenative/ForeverFunctionalPatchSummary$$ExternalSyntheticLambda4;',
        'Lapp/gamenative/ForeverFunctionalPatchSummary$Companion$$ExternalSyntheticLambda0;',
        'Lapp/gamenative/ForeverFunctionalPatchSummary$Companion$$ExternalSyntheticLambda1;',
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$$ExternalSyntheticLambda9;',
        # C139: UID/thread-count proof from the same bounded proc status read.
        'Lapp/gamenative/ForeverClientProcStatus;',
        # C129: app-owned keyboard accessory key/modifier state (US-004).
        'Lapp/gamenative/ForeverFunctionalKeyboardKeys;',
        # C124: independently reviewed Enter-gated observer and its compiled
        # lambdas. Exact descriptors only; do not admit the whole session prefix.
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$$ExternalSyntheticLambda2;',
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$$ExternalSyntheticLambda3;',
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$$ExternalSyntheticLambda4;',
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$$ExternalSyntheticLambda5;',
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$$ExternalSyntheticLambda6;',
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$GatedPollSession;',
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$GatedPollSession$$ExternalSyntheticLambda0;',
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$GatedPollSession$$ExternalSyntheticLambda1;',
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$GatedPollSession$$ExternalSyntheticLambda2;',
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$GatedPollSession$$ExternalSyntheticLambda3;',
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$GatedPollSession$$ExternalSyntheticLambda4;',
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$GatedPollSession$$ExternalSyntheticLambda5;',
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$GatedPollSession$$ExternalSyntheticLambda6;',
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$GatedPollSession$$ExternalSyntheticLambda7;',
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$GatedPollSession$$ExternalSyntheticLambda8;',
        # C133: reviewed advisory status types and their two generated observer
        # lambdas. Keep exact names; no session-wide descriptor prefix.
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$PatchAttemptPhase;',
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$PatchStatus;',
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$$ExternalSyntheticLambda7;',
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$GatedPollSession$$ExternalSyntheticLambda9;',
        # C135: observed-startup classifier/monotonic clock and timed poll wrapper.
        # Exact additions only; neither a helper nor session-wide prefix.
        'Lapp/gamenative/ForeverFunctionalWowStartup;',
        'Lapp/gamenative/ForeverFunctionalWowStartup$$ExternalSyntheticLambda0;',
        'Lapp/gamenative/ForeverFunctionalPocPatchSession$$ExternalSyntheticLambda8;',
        # Reviewed USB source admission/report types (2026-10-20); exact names
        # only, never a broad prefix that could admit unrelated classes.
        'Lapp/gamenative/ForeverClientSourceOrigin;',
        'Lapp/gamenative/PastedClientReport;',
        'Lapp/gamenative/PastedClientVerdict;',
        # Preexisting fresh-only finalization input in source commit 2649bd4;
        # reviewed separately from the paste flow before admitting this delta.
        'Lapp/gamenative/FreshStagedDescriptor;',
        'Lapp/gamenative/ForeverClientRecoveryResult;',
        'Lapp/gamenative/UpdateStageReceipt;',
        # Reviewed C138 privacy guard: the helper, its two nested types, and
        # exact call-site lambdas gating bounded log writes/flushes and exports.
        'Lapp/gamenative/ForeverAssistedCredentialPrivacy;',
        'Lapp/gamenative/ForeverAssistedCredentialPrivacy$Session;',
        'Lapp/gamenative/ForeverAssistedCredentialPrivacy$WindowManagerFlags;',
        'Lapp/gamenative/BoundedLogRetention$$ExternalSyntheticLambda4;',
        'Lapp/gamenative/BoundedLogRetention$$ExternalSyntheticLambda5;',
        'Lapp/gamenative/ForeverRunStore$$ExternalSyntheticLambda25;',
        'Lapp/gamenative/ForeverRunStore$export$2$$ExternalSyntheticLambda2;',
        # Reviewed C110: portable replay-reader contract and the Android
        # binding-mismatch subtype required by the physical preflight gate.
        'Lapp/gamenative/CascReplayReader;',
        'Lapp/gamenative/CascReplayBindingMismatchException;',
        # Reviewed bounded archive-order hint; do not admit neighbouring helpers.
        'Lapp/gamenative/ForeverClientArchiveOrder;',
        'Lapp/gamenative/ForeverClientArchiveOrder$Companion;',
        'Lapp/gamenative/ForeverClientArchiveOrder$Companion$CacheIdentity;',
        'Lapp/gamenative/ForeverClientArchiveOrder$withObjects$1$1;',
        'Lapp/gamenative/ForeverClientArchiveOrder$Companion$$ExternalSyntheticLambda0;',
        'Lapp/gamenative/ForeverClientArchiveOrder$Companion$$ExternalSyntheticLambda1;',
        # Existing reviewed Home Wi-Fi policy and bounded speed estimator;
        # exact additions observed in the canonical candidate's D8 output.
        'Lapp/gamenative/ForeverWifiDownloads;',
        'Lapp/gamenative/ForeverWifiDownloads$$ExternalSyntheticLambda0;',
        'Lapp/gamenative/ForeverWifiDownloads$BoundClient;',
        'Lapp/gamenative/ForeverWifiDownloads$Unavailable;',
        'Lapp/gamenative/ForeverWifiDownloads$callback$1;',
        'Lapp/gamenative/ForeverClientDownloadRate;',
        'Lapp/gamenative/ForeverClientDownloadRate$Companion;',
        'Lapp/gamenative/ForeverClientDownloadRate$Estimate;',
        'Lapp/gamenative/ForeverClientDownloadRate$Sample;',
        # C111 reviewed architecture: per-invocation coordinator and immutable
        # prefix snapshot; no broad replay-related descriptor prefix is allowed.
        'Lapp/gamenative/CascOverlapCoordinator;',
        # D8 backport generated for the reviewed C111 coordinator on minSdk26.
        'Lapp/gamenative/CascOverlapCoordinator$$ExternalSyntheticBackportWithForwarding0;',
        'Lapp/gamenative/OverlapPrefixFreeze;',
        'Lapp/gamenative/OverlapRawKeyRecord;',
        # Reviewed 2026-10-18: a fresh toolchain recompile (verified by rebuilding
        # the code96 tree unchanged, which reproduces the same drift) resolves
        # these javac anonymous classes as d8 synthetic lambdas, dropping the $1
        # descriptors. No winlator source changed.
        'Lcom/winlator/alsaserver/ALSAClient$1;',
        'Lcom/winlator/core/GeneralComponents$1;',
        'Lcom/winlator/inputcontrols/Binding$1;',
        'Lcom/winlator/inputcontrols/ControlElement$1;',
        'Lcom/winlator/winhandler/WinHandler$1;',
        'Lcom/winlator/xserver/Property$1;',
        'Lcom/winlator/xserver/requests/DrawRequests$1;',
    }
    poc_nested_prefix = 'Lapp/gamenative/ForeverFunctionalPoc$'
    diagnostics_roots = {
        f'Lapp/gamenative/ForeverRemoteDiagnostics{name}'
        for name in ('Contract', 'Sanitizer', 'Store', 'Report', 'FrozenConfig',
                     'LocalCapture', 'Upload', 'Android', 'DialogKt', 'WineLog')
    } | {'Lapp/gamenative/ComposableSingletons$ForeverRemoteDiagnosticsDialogKt'}
    def diagnostics_descriptor(name: str) -> bool:
        return any(name == root + ';' or name.startswith(root + '$')
                   for root in diagnostics_roots)
    unexpected = sorted(
        name for name in added | removed
        if name not in exact_descriptors and not name.startswith(prefixes)
        and not name.startswith(poc_nested_prefix) and not diagnostics_descriptor(name)
    )
    require(not unexpected, 'unapproved DEX descriptor delta: ' + ', '.join(unexpected))


def validate_source_receipt_lineage(receipt: dict[str, object], helper) -> None:
    code, version = helper.CANDIDATE_VERSION
    require(receipt.get('candidate') == {'code': code, 'name': version}, 'source receipt candidate identity mismatch')
    lineage = receipt.get('sourceLineage')
    require(isinstance(lineage, dict), 'source receipt lineage is missing')
    require(
        lineage.get('status') == 'NEW candidate source lineage; no historical parity claim',
        'source receipt cannot claim historical parity for this candidate',
    )
    require(
        lineage.get('historicalControllerSha256') == helper.PINNED_HISTORICAL_CONTROLLER_SHA256,
        'source receipt historical controller pin mismatch',
    )
    controller = lineage.get('reviewedController')
    require(isinstance(controller, dict), 'reviewed new controller lineage is missing')
    require(
        controller.get('historicalSha256') == helper.PINNED_HISTORICAL_CONTROLLER_SHA256
        and controller.get('sourceSha256') == helper.PINNED_NEW_CONTROLLER_SHA256
        and controller.get('reviewStatus') == 'INDEPENDENTLY_REVIEWED_NEW_CANDIDATE_INPUT',
        'source receipt cannot retag the historical controller as the reviewed new input',
    )


def check_payload_inventory(
    payloads: dict[str, str],
    baseline_payloads: dict[str, str],
    marker_member: str,
    update_assets: dict[str, object],
    candidate_native_member: str | None = None,
    candidate_native_sha256: str | None = None,
    c138_privacy_payload_pins: dict[str, str] | None = None,
) -> list[str]:
    new_members = set(update_assets)
    require(not new_members & set(baseline_payloads), 'pinned update assets collide with code64 payload members')
    require(set(payloads) == set(baseline_payloads) | new_members, 'payload member drift')
    changed_baseline = sorted(
        name for name in baseline_payloads if payloads[name] != baseline_payloads[name]
    )
    allowed_changed = {marker_member}
    if candidate_native_member is not None or candidate_native_sha256 is not None:
        # Exactly one explicitly reviewed native payload may move;
        # no generic allowance list, prefix, or flag is admitted here.
        require(candidate_native_member is not None and candidate_native_sha256 is not None,
                'candidate native admission requires both member and hash')
        require(candidate_native_member in baseline_payloads, 'candidate native member is not a baseline payload')
        require(payloads[candidate_native_member] == candidate_native_sha256,
                'candidate native payload is not the reviewed hash')
        allowed_changed.add(candidate_native_member)
    if c138_privacy_payload_pins is not None:
        require(set(c138_privacy_payload_pins) == C138_PRIVACY_MEMBERS,
                'C138 privacy admission must bind exactly the two reviewed payloads')
        for member, expected_sha256 in c138_privacy_payload_pins.items():
            require(member in baseline_payloads, f'C138 privacy payload is not a baseline member: {member}')
            require(payloads.get(member) == expected_sha256,
                    f'C138 privacy payload is not its frozen hash: {member}')
            allowed_changed.add(member)
    require(set(changed_baseline) <= allowed_changed,
            f'native/runtime/asset drift outside the provenance marker: {changed_baseline}')
    for member, pin in update_assets.items():
        require(payloads[member] == pin.sha256, f'pinned update APK asset hash mismatch: {member}')
    return changed_baseline


def validate_c138_privacy_sanitization(
    receipt: dict[str, object],
    source: Path,
    source_inventory: dict[str, object],
    payloads: dict[str, str],
    baseline_payloads: dict[str, str],
    baseline_contract_bytes: bytes,
    helper,
) -> dict[str, str]:
    """Validate serial redaction bound to one exact admitted recipe/provenance pair."""
    require(helper.C138_PROVENANCE_SHA256 != 'UNFROZEN_AFTER_SOURCE_FREEZE',
            'C138 provenance pin is unfrozen')
    recipe_sha256 = helper.sha256_file(helper.APPLY_OVERLAY)
    try:
        provenance_sha256 = helper.privacy_provenance_pin_for_recipe(recipe_sha256)
    except helper.ReconstructionError as failure:
        raise ValueError('C138 recipe has no admitted provenance pair') from failure

    contract_member = helper.INTERRUPTED_DISPOSITION_MEMBER
    provenance_member = helper.CODE_PROVENANCE_MEMBER
    expected_pins = {
        contract_member: helper.C138_DISPOSITION_SHA256,
        provenance_member: provenance_sha256,
    }
    require(set(expected_pins) == C138_PRIVACY_MEMBERS,
            'frozen C138 privacy members differ from the reviewed payload pair')
    sanitization = receipt.get('approvedPrivacySanitization')
    require(isinstance(sanitization, dict)
            and set(sanitization) == {'status', 'changedField', 'replacement', 'recipeSha256', 'payloads'}
            and sanitization.get('status') == 'PASS_EXACT_DEVICE_SERIAL_REDACTION'
            and sanitization.get('changedField') == 'device.serial'
            and sanitization.get('replacement') == 'DEVICE_SERIAL'
            and sanitization.get('recipeSha256') == recipe_sha256,
            'source receipt does not bind the exact frozen C138 privacy sanitization')
    before_contract_sha256 = helper.sha256_bytes(baseline_contract_bytes)
    require(baseline_payloads.get(contract_member) == before_contract_sha256,
            'authenticated code64 disposition bytes do not match the payload inventory')
    before_pins = {
        contract_member: before_contract_sha256,
        provenance_member: baseline_payloads.get(provenance_member),
    }
    receipt_payloads = sanitization.get('payloads')
    require(isinstance(receipt_payloads, dict) and set(receipt_payloads) == C138_PRIVACY_MEMBERS,
            'source receipt omits or adds a C138 privacy payload binding')

    old_contract = json.loads(baseline_contract_bytes)
    require(isinstance(old_contract, dict) and isinstance(old_contract.get('device'), dict)
            and isinstance(old_contract['device'].get('serial'), str)
            and old_contract['device']['serial'] not in ('', 'DEVICE_SERIAL'),
            'authenticated code64 disposition has no private serial preimage')
    expected_contract = json.loads(baseline_contract_bytes)
    expected_contract['device']['serial'] = 'DEVICE_SERIAL'
    for member, expected_sha256 in expected_pins.items():
        require(isinstance(before_pins[member], str),
                f'authenticated code64 payload hash is missing: {member}')
        binding = receipt_payloads.get(member)
        require(binding == {
            'beforeSha256': before_pins[member],
            'afterSha256': expected_sha256,
        }, f'source receipt privacy payload binding mismatch: {member}')

        relative = helper.update_asset_source_relative(member).as_posix()
        identity = source_inventory.get(relative)
        source_bytes = helper.read_stable_file(source / relative, f'C138 privacy source {member}')
        require(identity == {'bytes': len(source_bytes), 'sha256': expected_sha256}
                and helper.sha256_bytes(source_bytes) == expected_sha256,
                f'C138 privacy source is not bound to its frozen hash: {member}')
        require(payloads.get(member) == expected_sha256,
                f'packaged C138 privacy payload hash mismatch: {member}')
        if member == contract_member:
            contract = json.loads(source_bytes)
            require(contract == expected_contract,
                    'C138 disposition differs beyond the approved device.serial redaction')

    provenance_bytes = helper.read_stable_file(
        source / helper.update_asset_source_relative(provenance_member), 'C138 privacy provenance source'
    )
    provenance = json.loads(provenance_bytes)
    disposition = provenance.get('interruptedRunDisposition') if isinstance(provenance, dict) else None
    require(isinstance(disposition, dict)
            and provenance.get('recipeSha256') == recipe_sha256
            and disposition.get('sha256') == helper.C138_DISPOSITION_SHA256
            and disposition.get('sourceSha256') == helper.C138_DISPOSITION_SHA256,
            'C138 provenance does not bind the frozen recipe and disposition contract')
    return expected_pins


def _require_candidate_native_binding(label: str, native: object, helper) -> None:
    require(isinstance(native, dict), f'{label} candidate native lineage is missing')
    require(native.get('member') == helper.CODE64_NATIVE_MEMBER,
            f'{label} candidate native member is not the packaged JNI')
    require(native.get('beforeSha256') == helper.PINNED_CODE64_NATIVE_SHA256,
            f'{label} candidate native preimage is not the retained code64 payload')
    require(native.get('afterSha256') == helper.PINNED_CANDIDATE_NATIVE_SHA256,
            f'{label} candidate native is not the reviewed candidate payload')
    require(native.get('bytes') == helper.PINNED_CANDIDATE_NATIVE_BYTES,
            f'{label} candidate native size is not the reviewed pin')
    require(native.get('sidecarSha256') == helper.PINNED_CANDIDATE_NATIVE_SIDECAR_SHA256,
            f'{label} candidate native sidecar is not the reviewed provenance')
    require(native.get('origin') == helper.CANDIDATE_NATIVE_ORIGIN,
            f'{label} candidate native origin is not explicit')
    require(native.get('capability') == helper.CANDIDATE_NATIVE_CAPABILITY,
            f'{label} candidate native guarded-write capability is not explicit')


def validate_candidate_native_lineage(receipt: dict[str, object], marker: dict[str, object], helper) -> None:
    """Bind the guarded patch native to its reviewed SO/sidecar pins in both records.

    The receipt carries the binding under ``inputs`` (where reconstruct emits
    it); the marker carries it top level. A top-level receipt binding is not
    read: the moved shape is rejected. The historical code64 JNI stays the
    authenticated preimage; neither record may claim the new SO has historical
    parity, and neither may claim the runtime/driver capture changed.
    """
    require('candidateNative' not in receipt,
            'source receipt must not carry a moved top-level candidate native binding')
    inputs = receipt.get('inputs')
    require(isinstance(inputs, dict), 'source receipt inputs are missing')
    _require_candidate_native_binding('source receipt', inputs.get('candidateNative'), helper)
    _require_candidate_native_binding('candidate marker', marker.get('candidateNative'), helper)
    require(marker.get('candidateNative') == inputs.get('candidateNative'),
            'candidate marker native lineage differs from its source receipt')
    require(marker.get('readOnlyNativeChanged') is False
            and marker.get('guardedPatchNativeChanged') is True,
            'candidate marker must report the guarded-write native change')
    require(marker.get('nativeRuntimeDriverChanged') is False,
            'candidate marker cannot claim a runtime/driver change')


def dex_classes(data: bytes) -> set[str]:
    require(data[:4] == b'dex\n', 'unsupported DEX header')
    def u32(offset: int) -> int:
        return struct.unpack_from('<I', data, offset)[0]
    strings_count, strings_offset = u32(56), u32(60)
    types_count, types_offset = u32(64), u32(68)
    classes_count, classes_offset = u32(96), u32(100)
    require(strings_offset + strings_count * 4 <= len(data), 'invalid DEX strings')
    require(types_offset + types_count * 4 <= len(data), 'invalid DEX types')
    require(classes_offset + classes_count * 32 <= len(data), 'invalid DEX classes')
    result = set()
    for index in range(classes_count):
        type_index = u32(classes_offset + index * 32)
        require(type_index < types_count, 'invalid DEX class index')
        string_index = u32(types_offset + type_index * 4)
        require(string_index < strings_count, 'invalid DEX type index')
        offset = u32(strings_offset + string_index * 4)
        # Skip the bounded ULEB128 UTF-16 length. Class descriptors are ASCII.
        for _ in range(5):
            value = data[offset]
            offset += 1
            if not value & 128:
                break
        else:
            raise ValueError('invalid DEX string length')
        end = data.index(b'\0', offset)
        descriptor = data[offset:end].decode('ascii')
        require(descriptor not in result, 'duplicate class descriptor')
        result.add(descriptor)
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    helper_path = Path(__file__).with_name('reconstruct_functional_source.py')
    spec = importlib.util.spec_from_file_location('functional_reconstruction_audit', helper_path)
    helper = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = helper
    spec.loader.exec_module(helper)
    receipt = json.loads(helper.read_stable_file(args.receipt))
    require(receipt['schema'] == 2, 'unreviewed source receipt schema')
    validate_source_receipt_lineage(receipt, helper)
    source = Path(receipt['preparedTree'])
    expected = receipt['finalInventory']
    for relative, identity in expected.items():
        path = source / relative
        require(helper.sha256_file(path) == identity['sha256'], f'build source changed: {relative}')
    # No unrecorded compilation input is allowed in source sets.
    actual_sources = helper.regular_tree_inventory(source / 'app/src', 'compiled source sets')
    require({f'app/src/{p}' for p in actual_sources} ==
            {p for p in expected if p.startswith('app/src/')}, 'unrecorded source-set input')
    marker = json.loads(helper.read_stable_file(source / helper.MARKER, 'candidate provenance marker'))
    require(marker.get('versionCode') == helper.CANDIDATE_VERSION[0]
            and marker.get('versionName') == helper.CANDIDATE_VERSION[1], 'candidate marker identity mismatch')
    require(marker.get('lineage', {}).get('status') == 'NEW candidate source lineage; no historical parity claim',
            'candidate marker cannot claim historical parity')
    require(marker.get('controller') == receipt['sourceLineage']['reviewedController'],
            'candidate marker controller lineage differs from its source receipt')
    validate_candidate_native_lineage(receipt, marker, helper)
    inputs = helper.authenticate_inputs(helper.ROOT / 'forever/contract.json')
    candidate_binding, _candidate_bytes = helper.authenticate_candidate_native()
    require(candidate_binding['afterSha256'] == helper.PINNED_CANDIDATE_NATIVE_SHA256,
            'candidate native binding is not the reviewed candidate payload')
    require(receipt.get('inputs', {}).get('pinnedUpdateAssets') == inputs.input_manifests['pinnedUpdateAssets'],
            'source receipt pinned update-asset lineage mismatch')
    require(receipt.get('inputs', {}).get('clientTarget') == inputs.client_target_manifest,
            'source receipt public client-target lineage mismatch')
    update_assets = helper.update_asset_pins(inputs)
    commands = {}
    for name, command in {
        'signature': [str(TOOLS / 'apksigner'), 'verify', '--verbose', '--print-certs', str(args.apk)],
        'alignment': [str(TOOLS / 'zipalign'), '-c', '-P', '16', '4', str(args.apk)],
        'badging': [str(TOOLS / 'aapt'), 'dump', 'badging', str(args.apk)],
        'manifest': [str(TOOLS / 'aapt'), 'dump', 'xmltree', str(args.apk), 'AndroidManifest.xml'],
    }.items():
        done = subprocess.run(command, capture_output=True, text=True, check=True)
        commands[name] = done.stdout
    require(f'Signer #1 certificate SHA-256 digest: {CERTIFICATE}' in commands['signature'], 'signer mismatch')
    require('Number of signers: 1' in commands['signature'], 'unexpected signer count')
    badging = commands['badging']
    code, version = helper.CANDIDATE_VERSION
    require(f"name='com.wowforever.arm64probe' versionCode='{code}' versionName='{version}'" in badging,
            'candidate identity mismatch')
    require("native-code: 'arm64-v8a'" in badging, 'candidate ABI mismatch')
    require("sdkVersion:'29'" in badging and "targetSdkVersion:'36'" in badging, 'SDK drift')
    require("launchable-activity: name='app.gamenative.ForeverFunctionalPocActivity'" in badging, 'wrong launcher')
    require('MANAGE_EXTERNAL_STORAGE' not in commands['manifest'], 'broad storage permission introduced')
    branding_manifest = helper.read_stable_file(source / helper.MANIFEST, 'branded source manifest').decode('utf-8')
    require('android:icon="@mipmap/forever_ic_launcher"' in branding_manifest, 'branded app icon binding is missing')
    require('android:roundIcon="@mipmap/forever_ic_launcher"' in branding_manifest, 'branded app round icon binding is missing')
    require('android:theme="@style/Theme.ForeverFunctionalSplash"' in branding_manifest,
            'branded functional entry theme binding is missing')
    for upstream_icon in ('${icon}', '@mipmap/ic_launcher"', '@mipmap/ic_launcher_alt"'):
        require(upstream_icon not in branding_manifest, f'upstream icon binding survived branding: {upstream_icon}')
    poc_activity = branding_manifest.split('android:name="app.gamenative.ForeverFunctionalPocActivity"')
    require(len(poc_activity) == 2 and helper.BRANDING_SPLASH_THEME.removeprefix('@style/') in poc_activity[1].split('</activity>')[0],
            'branded splash theme is not bound to the functional entry activity')
    resources = subprocess.run([str(TOOLS / 'aapt'), 'dump', 'resources', str(args.apk)],
                               capture_output=True, text=True, check=True).stdout
    branding_resources = (
        'mipmap/forever_ic_launcher',
        'drawable/forever_emblem_background',
        'drawable/forever_emblem_foreground',
        'drawable/forever_emblem_monochrome',
        'drawable/forever_splash_legacy',
        'style/Theme.ForeverFunctionalSplash',
    )
    for name in branding_resources:
        require(name in resources, f'branded compiled resource is missing: {name}')
    with zipfile.ZipFile(args.apk) as apk, zipfile.ZipFile(helper.CODE64_APK) as baseline:
        names = apk.namelist()
        require(len(names) == len(set(names)), 'duplicate APK members')
        payloads = {n: helper.sha256_bytes(apk.read(n)) for n in names if n.startswith(('assets/', 'lib/'))}
        new_members = set(update_assets)
        privacy_payload_pins = validate_c138_privacy_sanitization(
            receipt, source, expected, payloads, inputs.code64_payloads,
            baseline.read(helper.INTERRUPTED_DISPOSITION_MEMBER), helper,
        )
        changed_baseline = check_payload_inventory(
            payloads, inputs.code64_payloads, helper.FUNCTIONAL_MARKER_MEMBER, update_assets,
            candidate_native_member=helper.CODE64_NATIVE_MEMBER,
            candidate_native_sha256=helper.PINNED_CANDIDATE_NATIVE_SHA256,
            c138_privacy_payload_pins=privacy_payload_pins,
        )
        require(apk.read(helper.FUNCTIONAL_MARKER_MEMBER) == (source / helper.MARKER).read_bytes(), 'marker drift')
        parity = receipt.get('code64PayloadParity')
        require(isinstance(parity, dict)
                and parity.get('status') == 'PASS'
                and parity.get('code64PayloadMembers') == 174
                # Reconstruction records parity before writing its new marker;
                # the APK inventory above independently includes that marker.
                and parity.get('sourcePayloadMembers') == len(inputs.code64_payloads) - 1 + len(new_members)
                and parity.get('excluded') == [helper.FUNCTIONAL_MARKER_MEMBER]
                and parity.get('addedPinnedUpdateMetadata') == sorted(new_members)
                and parity.get('nativeRuntimeDriverChanged') is False
                and parity.get('readOnlyNativeChanged') is False
                and parity.get('guardedPatchNativeChanged') is True
                and isinstance(parity.get('candidateNative'), dict)
                and parity['candidateNative'].get('member') == helper.CODE64_NATIVE_MEMBER
                and parity['candidateNative'].get('beforeSha256') == helper.PINNED_CODE64_NATIVE_SHA256
                and parity['candidateNative'].get('afterSha256') == helper.PINNED_CANDIDATE_NATIVE_SHA256
                and parity['candidateNative'].get('sidecarSha256') == helper.PINNED_CANDIDATE_NATIVE_SIDECAR_SHA256
                and parity['candidateNative'].get('capability') == helper.CANDIDATE_NATIVE_CAPABILITY,
                'source receipt code64 payload parity is not the reviewed native/metadata delta')
        for member, pin in helper.PINNED_UPDATE_ASSETS.items():
            digest = helper.sha256_bytes(apk.read(member))
            relative = helper.update_asset_source_relative(member).as_posix()
            identity = expected.get(relative)
            require(identity == {'bytes': pin.size, 'sha256': pin.sha256},
                    f'pinned update APK asset is not bound to the recorded source inventory: {member}')
            origin = receipt.get('assetOrigins', {}).get(relative)
            require(isinstance(origin, dict)
                    and origin.get('kind') == 'pinned-update-metadata'
                    and origin.get('generation') == helper.UPDATE_ASSET_GENERATION
                    and origin.get('sourcePath') == str(pin.source)
                    and origin.get('member') == member
                    and origin.get('bytes') == pin.size
                    and origin.get('sha256') == pin.sha256,
                    f'pinned update APK asset origin is not explicit: {member}')
        target_member = helper.CLIENT_TARGET_MEMBER
        target_relative = helper.update_asset_source_relative(target_member).as_posix()
        target_origin = receipt.get('assetOrigins', {}).get(target_relative)
        target_identity = expected.get(target_relative)
        require(apk.read(target_member) == inputs.client_target_bytes,
                'packaged public client target differs from its bounded source bytes')
        require(target_identity == {
            'bytes': len(inputs.client_target_bytes), 'sha256': inputs.client_target_manifest['sha256']
        }, 'public client target is not bound to the recorded source inventory')
        require(isinstance(target_origin, dict)
                and target_origin.get('kind') == 'public-client-target-contract'
                and target_origin.get('sourcePath') == str(helper.CLIENT_TARGET_SOURCE)
                and target_origin.get('member') == target_member
                and target_origin.get('bytes') == len(inputs.client_target_bytes)
                and target_origin.get('sha256') == inputs.client_target_manifest['sha256']
                and target_origin.get('authority') == helper.CLIENT_TARGET_AUTHORITY
                and target_origin.get('targetBindings') == inputs.client_target_manifest['bindings'],
                'public client target APK origin is not explicitly bound to its source contract')
        candidate_so = apk.read(helper.CODE64_NATIVE_MEMBER)
        require(helper.sha256_bytes(candidate_so) == helper.PINNED_CANDIDATE_NATIVE_SHA256,
                'packaged native is not the reviewed candidate payload')
        helper.elf_audit(candidate_so, helper.CANDIDATE_REQUIRED_EXPORTS)
        classes = set()
        dex_hashes = {}
        for name in names:
            if re.fullmatch(r'classes(?:\d+)?\.dex', name):
                data = apk.read(name)
                defined = dex_classes(data)
                require(not classes & defined, 'classes duplicated across DEX files')
                classes |= defined
                dex_hashes[name] = helper.sha256_bytes(data)
        previous_classes = set()
        for name in baseline.namelist():
            if re.fullmatch(r'classes(?:\d+)?\.dex', name):
                previous_classes |= dex_classes(baseline.read(name))
        for required in ('Lapp/gamenative/ForeverFunctionalPocActivity;', 'Lapp/gamenative/ForeverFunctionalLauncherUx;',
                         'Lapp/gamenative/ForeverFunctionalServerProfiles;', 'Lcom/wowforever/launcher/ForeverPatchBridge;',
                         'Lapp/gamenative/ForeverRemoteDiagnosticsAndroid;', 'Lapp/gamenative/ForeverRemoteDiagnosticsUpload;',
                         'Lapp/gamenative/ForeverRemoteDiagnosticsWineLog;'):
            require(required in classes, f'compiled class absent: {required}')
        check_descriptor_delta(classes - previous_classes, previous_classes - classes)
    report = {
        'schema': 1, 'result': 'PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT',
        'apk': {'path': str(args.apk), 'sha256': helper.sha256_file(args.apk), 'bytes': args.apk.stat().st_size},
        'sourceReceipt': {'path': str(args.receipt), 'sha256': helper.sha256_file(args.receipt)},
        'sourceFilesUnchanged': len(expected), 'certificateSha256': CERTIFICATE,
        'payloadCount': len(payloads),
        'payloadDeltaFromCode64': sorted(set(changed_baseline) | new_members),
        'pinnedUpdateMetadataAssets': sorted(new_members),
        'brandingResources': sorted(branding_resources),
        'nativeUnchangedFromCode64': False, 'readOnlyNativeChanged': False,
        'guardedPatchNativeChanged': True,
        'candidateNativeSha256': helper.PINNED_CANDIDATE_NATIVE_SHA256,
        'candidateNativeSidecarSha256': helper.PINNED_CANDIDATE_NATIVE_SIDECAR_SHA256,
        'requiredJniExports': sorted(helper.CANDIDATE_REQUIRED_EXPORTS),
        'dex': {'hashes': dex_hashes, 'classCount': len(classes),
                'addedClasses': sorted(classes - previous_classes), 'removedClasses': sorted(previous_classes - classes),
                'boundary': 'New full DEX compilation from recorded source; descriptor inventory is not method equivalence.'},
        'commands': commands,
    }
    with args.output.open('x', encoding='utf-8') as output:
        json.dump(report, output, indent=2, sort_keys=True)
        output.write('\n')
    print(json.dumps({k: report[k] for k in ('result', 'apk', 'sourceFilesUnchanged', 'payloadCount', 'payloadDeltaFromCode64')}))


if __name__ == '__main__':
    main()
