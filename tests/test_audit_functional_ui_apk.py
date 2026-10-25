from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys
from types import SimpleNamespace

import pytest

SCRIPT = Path(__file__).parents[1] / 'scripts/arm64-prototype/app/audit_functional_ui_apk.py'
SPEC = importlib.util.spec_from_file_location('functional_ui_apk_audit', SCRIPT)
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)
HELPER_SCRIPT = Path(__file__).parents[1] / 'scripts/arm64-prototype/app/reconstruct_functional_source.py'
HELPER_SPEC = importlib.util.spec_from_file_location('functional_source_for_apk_audit_tests', HELPER_SCRIPT)
helper = importlib.util.module_from_spec(HELPER_SPEC)
sys.modules[HELPER_SPEC.name] = helper
HELPER_SPEC.loader.exec_module(helper)


def test_auditor_requires_permission_free_functional_entry_only():
    manifest_dump = '''E: manifest
  E: application
    E: activity
      A: android:name(0x01010003)="app.gamenative.ForeverFunctionalPocActivity" (Raw: "app.gamenative.ForeverFunctionalPocActivity")
      A: android:exported(0x01010010)=(type 0x12)0xffffffff
      E: intent-filter
        A: android:name(0x01010003)="android.intent.action.MAIN"
    E: activity
      A: android:name(0x01010003)="app.gamenative.ForeverWineSupervisorActivity"
      A: android:permission(0x01010006)="android.permission.DUMP"
'''
    module.check_functional_entry_permission(manifest_dump)

    inherited_permission = manifest_dump.replace(
        '  E: application\n',
        '  E: application\n    A: android:permission(0x01010006)="android.permission.DUMP"\n',
    )
    with pytest.raises(ValueError, match='application requires a caller permission'):
        module.check_functional_entry_permission(inherited_permission)

    protected_entry = manifest_dump.replace(
        '      A: android:exported',
        '      A: android:permission(0x01010006)="android.permission.DUMP"\n      A: android:exported',
    )
    with pytest.raises(ValueError, match='functional entry Activity requires'):
        module.check_functional_entry_permission(protected_entry)

    ambiguous = manifest_dump.replace(
        '    E: activity\n      A: android:name(0x01010003)="app.gamenative.ForeverWineSupervisorActivity"',
        '    E: activity\n      A: android:name(0x01010003)="app.gamenative.ForeverFunctionalPocActivity"\n'
        '    E: activity\n      A: android:name(0x01010003)="app.gamenative.ForeverWineSupervisorActivity"',
    )
    with pytest.raises(ValueError, match='missing or ambiguous'):
        module.check_functional_entry_permission(ambiguous)


def test_descriptor_delta_accepts_ui_but_rejects_unrelated_classes():
    module.check_descriptor_delta({'Lapp/gamenative/ForeverClientProcStatus;'}, set())
    with pytest.raises(ValueError, match='unapproved DEX descriptor'):
        module.check_descriptor_delta({'Lapp/gamenative/ForeverClientProcStatusUnexpected;'}, set())
    module.check_descriptor_delta({'Lapp/gamenative/ForeverFunctionalKeyboardKeys;'}, set())
    with pytest.raises(ValueError, match='unapproved DEX descriptor'):
        module.check_descriptor_delta({'Lapp/gamenative/ForeverFunctionalKeyboardKeysUnexpected;'}, set())
    module.check_descriptor_delta({'Lapp/gamenative/ForeverFunctionalLauncherUx;'}, set())
    # The shared user-text helper is a new reviewed UI class, not drift.
    module.check_descriptor_delta({'Lapp/gamenative/ForeverFunctionalUiText;'}, set())
    module.check_descriptor_delta({
        'Lapp/gamenative/ForeverFunctionalGpuCompatibility;',
        'Lapp/gamenative/ComposableSingletons$ForeverFunctionalGpuCompatibilityKt$lambda$1193275418$1;',
        'Lapp/gamenative/ForeverCasc$IncrementalObjectSpec;',
        'Lapp/gamenative/ForeverClientUpdateService$Inspection;',
        'Lapp/gamenative/ForeverClientRecoveryResult;',
        'Lapp/gamenative/UpdateStageReceipt;',
        'Lapp/gamenative/CascReplayReader;',
        'Lapp/gamenative/CascReplayBindingMismatchException;',
        'Lapp/gamenative/CascOverlapCoordinator;',
        'Lapp/gamenative/CascOverlapCoordinator$$ExternalSyntheticBackportWithForwarding0;',
        'Lapp/gamenative/OverlapPrefixFreeze;',
        'Lapp/gamenative/OverlapRawKeyRecord;',
        'Lapp/gamenative/ForeverFunctionalPoc$$ExternalSyntheticLambda7;',
        'Lapp/gamenative/ForeverFunctionalPoc$$ExternalSyntheticLambda14;',
        'Lapp/gamenative/ForeverTactMetadata;',
        'Lapp/gamenative/ForeverTactManifests;',
        'Lapp/gamenative/ForeverTactConfigs;',
        'Lapp/gamenative/ForeverTactCdnIndex;',
        'Lapp/gamenative/ForeverClientCatalogResolver;',
        'Lapp/gamenative/ForeverClientMetadataTransport;',
        'Lapp/gamenative/ForeverClientTargetBuild;',
        'Lapp/gamenative/RuntimeObservation$Active;',
        'Lapp/gamenative/ComposableSingletons$ForeverClientManagementKt$lambda$1;',
        'Lapp/gamenative/ForeverRemoteDiagnosticsUpload;',
        'Lapp/gamenative/ForeverRemoteDiagnosticsStore$Attempt;',
        'Lapp/gamenative/ForeverRemoteDiagnosticsDialogKt;',
        'Lapp/gamenative/ComposableSingletons$ForeverRemoteDiagnosticsDialogKt$lambda$1;',
    }, set())
    with pytest.raises(ValueError, match='unapproved DEX descriptor'):
        module.check_descriptor_delta({'Lapp/gamenative/UnknownClass;'}, set())
    with pytest.raises(ValueError, match='unapproved DEX descriptor'):
        module.check_descriptor_delta({'Lapp/gamenative/CascReplayReaderUnexpected;'}, set())
    with pytest.raises(ValueError, match='unapproved DEX descriptor'):
        module.check_descriptor_delta({'Lapp/gamenative/CascOverlapCoordinatorUnexpected;'}, set())
    with pytest.raises(ValueError, match='unapproved DEX descriptor'):
        module.check_descriptor_delta(set(), {'Lapp/gamenative/MainActivity;'})
    with pytest.raises(ValueError, match='unapproved DEX descriptor'):
        module.check_descriptor_delta({'Lapp/gamenative/ComposableSingletons$UnreviewedKt;'}, set())
    with pytest.raises(ValueError, match='unapproved DEX descriptor'):
        module.check_descriptor_delta({'Lapp/gamenative/ForeverFunctionalPocUnexpected;'}, set())
    with pytest.raises(ValueError, match='unapproved DEX descriptor'):
        module.check_descriptor_delta({'Lapp/gamenative/ForeverRemoteDiagnosticsUnknown;'}, set())
    with pytest.raises(ValueError, match='unapproved DEX descriptor'):
        module.check_descriptor_delta({'Lapp/gamenative/ForeverRemoteDiagnosticsUploadUnexpected;'}, set())


def test_pasted_source_descriptors_are_admitted_by_exact_name_only():
    for name in ('ForeverClientSourceOrigin', 'PastedClientReport',
                 'PastedClientVerdict', 'FreshStagedDescriptor'):
        module.check_descriptor_delta({f'Lapp/gamenative/{name};'}, set())
        with pytest.raises(ValueError, match='unapproved DEX descriptor'):
            module.check_descriptor_delta({f'Lapp/gamenative/{name}Unexpected;'}, set())


def test_fetch_pipeline_diagnostic_descriptor_is_exact():
    admitted = {'Lapp/gamenative/FetchPipelineDiagnostics;'} | {
        f'Lapp/gamenative/FetchPipelineDiagnostics$$ExternalSyntheticLambda{number};'
        for number in range(6)
    }
    module.check_descriptor_delta(admitted, set())
    with pytest.raises(ValueError, match='unapproved DEX descriptor'):
        module.check_descriptor_delta(admitted | {'Lapp/gamenative/FetchPipelineDiagnosticsUnexpected;'}, set())
    with pytest.raises(ValueError, match='unapproved DEX descriptor'):
        module.check_descriptor_delta(admitted | {'Lapp/gamenative/FetchPipelineDiagnostics$$ExternalSyntheticLambda6;'}, set())


def test_archive_order_descriptors_are_admitted_by_exact_name_only():
    root = 'Lapp/gamenative/ForeverClientArchiveOrder'
    module.check_descriptor_delta({f'{root};', f'{root}$Companion;', f'{root}$Companion$CacheIdentity;',
                                   f'{root}$withObjects$1$1;',
                                   f'{root}$Companion$$ExternalSyntheticLambda0;',
                                   f'{root}$Companion$$ExternalSyntheticLambda1;'}, set())
    for neighbor in (f'{root}Unexpected;', f'{root}$Unreviewed;',
                     f'{root}$Companion$$ExternalSyntheticLambda2;'):
        with pytest.raises(ValueError, match='unapproved DEX descriptor'):
            module.check_descriptor_delta({neighbor}, set())


def test_wifi_and_rate_descriptors_are_admitted_by_exact_name_only():
    wifi = 'Lapp/gamenative/ForeverWifiDownloads'
    rate = 'Lapp/gamenative/ForeverClientDownloadRate'
    module.check_descriptor_delta({
        f'{wifi};', f'{wifi}$$ExternalSyntheticLambda0;', f'{wifi}$BoundClient;',
        f'{wifi}$Unavailable;', f'{wifi}$callback$1;', f'{rate};',
        f'{rate}$Companion;', f'{rate}$Estimate;', f'{rate}$Sample;',
    }, set())
    for neighbor in (f'{wifi}Unexpected;', f'{wifi}$Unreviewed;',
                     f'{wifi}$$ExternalSyntheticLambda1;', f'{rate}$Unreviewed;'):
        with pytest.raises(ValueError, match='unapproved DEX descriptor'):
            module.check_descriptor_delta({neighbor}, set())


def test_gated_observer_and_apply_descriptors_are_admitted_by_exact_name_only():
    root = 'Lapp/gamenative/ForeverFunctionalPocPatchSession'
    accepted = {f'{root}$$ExternalSyntheticLambda{i};' for i in (2, 3, 4, 5, 6, 7, 8, 9)}
    accepted.add(f'{root}$GatedPollSession;')
    accepted.update(f'{root}$GatedPollSession$$ExternalSyntheticLambda{i};' for i in range(10))
    accepted.update((f'{root}$PatchAttemptPhase;', f'{root}$PatchStatus;'))
    startup = 'Lapp/gamenative/ForeverFunctionalWowStartup'
    accepted.update((f'{startup};', f'{startup}$$ExternalSyntheticLambda0;'))
    module.check_descriptor_delta(accepted, set())
    for neighbor in (f'{root}Unexpected;', f'{root}$GatedPollSessionUnexpected;',
                     f'{root}$$ExternalSyntheticLambda10;',
                     f'{startup}Unexpected;', f'{startup}$$ExternalSyntheticLambda1;',
                     f'{root}$GatedPollSession$$ExternalSyntheticLambda10;',
                     f'{root}$PatchAttemptPhaseUnexpected;', f'{root}$PatchStatusUnexpected;'):
        with pytest.raises(ValueError, match='unapproved DEX descriptor'):
            module.check_descriptor_delta({neighbor}, set())


def test_patch_summary_descriptors_are_admitted_by_exact_name_only():
    root = 'Lapp/gamenative/ForeverFunctionalPatchSummary'
    accepted = {f'{root};', f'{root}$Companion;'}
    accepted.update(f'{root}$$ExternalSyntheticLambda{i};' for i in range(5))
    accepted.update(f'{root}$Companion$$ExternalSyntheticLambda{i};' for i in range(2))
    module.check_descriptor_delta(accepted, set())
    for neighbor in (f'{root}Unexpected;', f'{root}$CompanionUnexpected;',
                     f'{root}$$ExternalSyntheticLambda5;', f'{root}$Companion$$ExternalSyntheticLambda2;'):
        with pytest.raises(ValueError, match='unapproved DEX descriptor'):
            module.check_descriptor_delta({neighbor}, set())


def test_c138_privacy_guard_descriptors_are_admitted_by_exact_name_only():
    reviewed = {
        'Lapp/gamenative/ForeverAssistedCredentialPrivacy;',
        'Lapp/gamenative/ForeverAssistedCredentialPrivacy$Session;',
        'Lapp/gamenative/ForeverAssistedCredentialPrivacy$WindowManagerFlags;',
        'Lapp/gamenative/BoundedLogRetention$$ExternalSyntheticLambda4;',
        'Lapp/gamenative/BoundedLogRetention$$ExternalSyntheticLambda5;',
        'Lapp/gamenative/ForeverRunStore$$ExternalSyntheticLambda25;',
        'Lapp/gamenative/ForeverRunStore$export$2$$ExternalSyntheticLambda2;',
    }
    module.check_descriptor_delta(reviewed, set())
    for neighbor in (
        'Lapp/gamenative/ForeverAssistedCredentialPrivacyExtra;',
        'Lapp/gamenative/ForeverAssistedCredentialPrivacy$SessionExtra;',
        'Lapp/gamenative/ForeverAssistedCredentialPrivacy$Unreviewed;',
        'Lapp/gamenative/BoundedLogRetention$$ExternalSyntheticLambda6;',
        'Lapp/gamenative/ForeverRunStore$$ExternalSyntheticLambda26;',
        'Lapp/gamenative/UnrelatedPrivacyHelper;',
    ):
        with pytest.raises(ValueError, match='unapproved DEX descriptor'):
            module.check_descriptor_delta({neighbor}, set())


def test_dex_requires_supported_header():
    with pytest.raises(ValueError, match='unsupported DEX'):
        module.dex_classes(b'not a dex')


def test_payload_inventory_accepts_only_the_four_pinned_metadata_additions():
    marker = 'assets/forever/functional-poc/provenance.json'
    baseline = {marker: 'old-marker', 'lib/arm64-v8a/libforever_patcher_jni.so': 'pinned-native'}
    assets = {
        f'assets/forever/client-update/{name}.bin': SimpleNamespace(sha256=f'pinned-{name}')
        for name in ('catalog', 'build', 'cdn', 'target')
    }
    payloads = {**baseline, **{name: pin.sha256 for name, pin in assets.items()}}
    payloads[marker] = 'new-marker'
    assert module.check_payload_inventory(payloads, baseline, marker, assets) == [marker]

    with pytest.raises(ValueError, match='payload member drift'):
        module.check_payload_inventory({**payloads, 'assets/forever/client-update/unreviewed.bin': 'extra'}, baseline, marker, assets)
    with pytest.raises(ValueError, match='outside the provenance marker'):
        module.check_payload_inventory({**payloads, 'lib/arm64-v8a/libforever_patcher_jni.so': 'changed'}, baseline, marker, assets)
    wrong_asset = dict(payloads)
    wrong_asset[next(iter(assets))] = 'untrusted-hash'
    with pytest.raises(ValueError, match='pinned update APK asset hash mismatch'):
        module.check_payload_inventory(wrong_asset, baseline, marker, assets)


def test_receipt_cannot_retag_the_new_controller_as_historical_parity():
    receipt = {
        'candidate': {'code': helper.CANDIDATE_VERSION[0], 'name': helper.CANDIDATE_VERSION[1]},
        'sourceLineage': {
            'status': 'NEW candidate source lineage; no historical parity claim',
            'historicalControllerSha256': helper.PINNED_HISTORICAL_CONTROLLER_SHA256,
            'reviewedController': {
                'historicalSha256': helper.PINNED_HISTORICAL_CONTROLLER_SHA256,
                'sourceSha256': helper.PINNED_NEW_CONTROLLER_SHA256,
                'reviewStatus': 'INDEPENDENTLY_REVIEWED_NEW_CANDIDATE_INPUT',
            },
        },
    }
    module.validate_source_receipt_lineage(receipt, helper)
    receipt['sourceLineage']['status'] = 'HISTORICAL_PARITY_PASS'
    with pytest.raises(ValueError, match='cannot claim historical parity'):
        module.validate_source_receipt_lineage(receipt, helper)


def test_payload_inventory_admits_only_the_reviewed_candidate_native():
    marker = helper.FUNCTIONAL_MARKER_MEMBER
    native = helper.CODE64_NATIVE_MEMBER
    baseline = {marker: 'old-marker', native: helper.PINNED_CODE64_NATIVE_SHA256, 'assets/keep.bin': 'keep'}
    assets = {
        f'assets/forever/client-update/{name}.bin': SimpleNamespace(sha256=f'pinned-{name}')
        for name in ('catalog', 'build', 'cdn', 'target')
    }
    payloads = {**baseline, **{name: pin.sha256 for name, pin in assets.items()}}
    payloads[marker] = 'new-marker'
    payloads[native] = helper.PINNED_CANDIDATE_NATIVE_SHA256
    changed = module.check_payload_inventory(
        payloads, baseline, marker, assets,
        candidate_native_member=native,
        candidate_native_sha256=helper.PINNED_CANDIDATE_NATIVE_SHA256,
    )
    assert set(changed) == {marker, native}

    with pytest.raises(ValueError, match='outside the provenance marker'):
        module.check_payload_inventory(payloads, baseline, marker, assets)
    tampered = dict(payloads)
    tampered[native] = 'untrusted-hash'
    with pytest.raises(ValueError, match='not the reviewed hash'):
        module.check_payload_inventory(
            tampered, baseline, marker, assets,
            candidate_native_member=native,
            candidate_native_sha256=helper.PINNED_CANDIDATE_NATIVE_SHA256,
        )
    drifted = dict(payloads)
    drifted['assets/keep.bin'] = 'changed'
    with pytest.raises(ValueError, match='outside the provenance marker'):
        module.check_payload_inventory(
            drifted, baseline, marker, assets,
            candidate_native_member=native,
            candidate_native_sha256=helper.PINNED_CANDIDATE_NATIVE_SHA256,
        )
    with pytest.raises(ValueError, match='both member and hash'):
        module.check_payload_inventory(payloads, baseline, marker, assets,
                                       candidate_native_member=native)


def _c138_privacy_case(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, *, candidate: bool = False):
    contract_member = helper.INTERRUPTED_DISPOSITION_MEMBER
    provenance_member = helper.CODE_PROVENANCE_MEMBER
    old_contract = json.dumps({'device': {'serial': 'private-device'}}).encode()
    new_contract = json.dumps({'device': {'serial': 'DEVICE_SERIAL'}}).encode()
    contract_sha = helper.sha256_bytes(new_contract)
    recipe = tmp_path / 'apply_overlay.py'
    recipe.write_text('reviewed audit recipe\n' if candidate else 'historical audit recipe\n')
    recipe_sha = helper.sha256_file(recipe)
    monkeypatch.setattr(helper, 'APPLY_OVERLAY', recipe)
    monkeypatch.setattr(helper, 'REVIEWED_CANDIDATE_RECIPE_SHA256' if candidate else 'C138_RECIPE_SHA256', recipe_sha)
    provenance = json.dumps({
        'recipeSha256': recipe_sha,
        'interruptedRunDisposition': {
            'sha256': contract_sha,
            'sourceSha256': contract_sha,
        },
    }, sort_keys=True).encode()
    provenance_sha = helper.sha256_bytes(provenance)
    monkeypatch.setattr(helper, 'C138_DISPOSITION_SHA256', contract_sha)
    monkeypatch.setattr(helper, 'REVIEWED_CANDIDATE_PROVENANCE_SHA256' if candidate else 'C138_PROVENANCE_SHA256', provenance_sha)

    source = tmp_path / 'source'
    source_inventory = {}
    payloads = {}
    for member, data in ((contract_member, new_contract), (provenance_member, provenance)):
        relative = helper.update_asset_source_relative(member)
        path = source / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        digest = helper.sha256_bytes(data)
        source_inventory[relative.as_posix()] = {'bytes': len(data), 'sha256': digest}
        payloads[member] = digest

    baseline_payloads = {
        contract_member: helper.sha256_bytes(old_contract),
        provenance_member: 'b' * 64,
    }
    receipt = {
        'approvedPrivacySanitization': {
            'status': 'PASS_EXACT_DEVICE_SERIAL_REDACTION',
            'changedField': 'device.serial',
            'replacement': 'DEVICE_SERIAL',
            'recipeSha256': recipe_sha,
            'payloads': {
                contract_member: {
                    'beforeSha256': baseline_payloads[contract_member],
                    'afterSha256': contract_sha,
                },
                provenance_member: {
                    'beforeSha256': baseline_payloads[provenance_member],
                    'afterSha256': provenance_sha,
                },
            },
        },
    }
    return (receipt, source, source_inventory, payloads, baseline_payloads, old_contract,
            {contract_member: contract_sha, provenance_member: provenance_sha})


@pytest.mark.parametrize('candidate', [False, True])
def test_c138_privacy_payloads_require_exact_receipt_source_and_packaged_pins(tmp_path, monkeypatch, candidate):
    (receipt, source, inventory, payloads, baseline, old_contract, pins) = _c138_privacy_case(
        tmp_path, monkeypatch, candidate=candidate,
    )
    admitted = module.validate_c138_privacy_sanitization(
        receipt, source, inventory, payloads, baseline, old_contract, helper,
    )
    assert admitted == pins
    baseline.update({helper.FUNCTIONAL_MARKER_MEMBER: 'old-marker', 'assets/unchanged.bin': 'same'})
    payloads.update({helper.FUNCTIONAL_MARKER_MEMBER: 'new-marker', 'assets/unchanged.bin': 'same'})
    changed = module.check_payload_inventory(
        payloads, baseline, helper.FUNCTIONAL_MARKER_MEMBER, {},
        c138_privacy_payload_pins=admitted,
    )
    assert set(changed) == {helper.FUNCTIONAL_MARKER_MEMBER, *pins}

    with pytest.raises(ValueError, match='omits or adds a C138 privacy payload binding'):
        bad_receipt = json.loads(json.dumps(receipt))
        del bad_receipt['approvedPrivacySanitization']['payloads'][helper.CODE_PROVENANCE_MEMBER]
        module.validate_c138_privacy_sanitization(
            bad_receipt, source, inventory, payloads, baseline, old_contract, helper,
        )

    wrong_payloads = dict(payloads)
    wrong_payloads[helper.CODE_PROVENANCE_MEMBER] = 'wrong-packaged-hash'
    with pytest.raises(ValueError, match='packaged C138 privacy payload hash mismatch'):
        module.validate_c138_privacy_sanitization(
            receipt, source, inventory, wrong_payloads, baseline, old_contract, helper,
        )

    extra_drift = dict(payloads)
    extra_drift['assets/unchanged.bin'] = 'changed'
    with pytest.raises(ValueError, match='outside the provenance marker'):
        module.check_payload_inventory(
            extra_drift, baseline, helper.FUNCTIONAL_MARKER_MEMBER, {},
            c138_privacy_payload_pins=admitted,
        )

    wrong_privacy_hash = dict(payloads)
    wrong_privacy_hash[helper.INTERRUPTED_DISPOSITION_MEMBER] = 'wrong-contract-hash'
    with pytest.raises(ValueError, match='packaged C138 privacy payload hash mismatch'):
        module.validate_c138_privacy_sanitization(
            receipt, source, inventory, wrong_privacy_hash, baseline, old_contract, helper,
        )

    with pytest.raises(ValueError, match='exactly the two reviewed payloads'):
        module.check_payload_inventory(
            payloads, baseline, helper.FUNCTIONAL_MARKER_MEMBER, {},
            c138_privacy_payload_pins={helper.INTERRUPTED_DISPOSITION_MEMBER: pins[helper.INTERRUPTED_DISPOSITION_MEMBER]},
        )
    helper.APPLY_OVERLAY.write_text('unreviewed audit recipe\n')
    with pytest.raises(ValueError, match='no admitted provenance pair'):
        module.validate_c138_privacy_sanitization(
            receipt, source, inventory, payloads, baseline, old_contract, helper,
        )


def _candidate_native_documents():
    binding = {
        'member': helper.CODE64_NATIVE_MEMBER,
        'beforeSha256': helper.PINNED_CODE64_NATIVE_SHA256,
        'afterSha256': helper.PINNED_CANDIDATE_NATIVE_SHA256,
        'bytes': helper.PINNED_CANDIDATE_NATIVE_BYTES,
        'sourcePath': str(helper.CANDIDATE_NATIVE_SO),
        'sidecarPath': str(helper.CANDIDATE_NATIVE_SIDECAR),
        'sidecarSha256': helper.PINNED_CANDIDATE_NATIVE_SIDECAR_SHA256,
        'origin': helper.CANDIDATE_NATIVE_ORIGIN,
        'capability': helper.CANDIDATE_NATIVE_CAPABILITY,
    }
    receipt = {
        'candidate': {'code': helper.CANDIDATE_VERSION[0], 'name': helper.CANDIDATE_VERSION[1]},
        # Actual reconstruct shape: the binding lives under inputs, never top level.
        'inputs': {'candidateNative': dict(binding)},
    }
    marker = {
        'candidateNative': dict(binding),
        'readOnlyNativeChanged': False,
        'guardedPatchNativeChanged': True,
        'nativeRuntimeDriverChanged': False,
    }
    return receipt, marker


def test_candidate_native_lineage_binding_requires_the_reviewed_payload():
    receipt, marker = _candidate_native_documents()
    module.validate_candidate_native_lineage(receipt, marker, helper)
    tampered = dict(marker['candidateNative'])
    tampered['afterSha256'] = 'untrusted-hash'
    with pytest.raises(ValueError, match='not the reviewed candidate payload'):
        module.validate_candidate_native_lineage(receipt, {**marker, 'candidateNative': tampered}, helper)
    tampered_sidecar = dict(marker['candidateNative'])
    tampered_sidecar['sidecarSha256'] = 'untrusted-sidecar'
    with pytest.raises(ValueError, match='not the reviewed provenance'):
        module.validate_candidate_native_lineage(receipt, {**marker, 'candidateNative': tampered_sidecar}, helper)
    drifted_inputs = dict(receipt['inputs']['candidateNative'])
    drifted_inputs['note'] = 'drift'
    with pytest.raises(ValueError, match='differs from its source receipt'):
        module.validate_candidate_native_lineage(
            {**receipt, 'inputs': {'candidateNative': drifted_inputs}}, marker, helper)
    with pytest.raises(ValueError, match='guarded-write native change'):
        module.validate_candidate_native_lineage(receipt, {**marker, 'readOnlyNativeChanged': True}, helper)
    omitted_capability = dict(marker['candidateNative'])
    omitted_capability.pop('capability')
    with pytest.raises(ValueError, match='guarded-write capability'):
        module.validate_candidate_native_lineage(
            receipt, {**marker, 'candidateNative': omitted_capability}, helper)
    with pytest.raises(ValueError, match='runtime/driver change'):
        module.validate_candidate_native_lineage(receipt, {**marker, 'nativeRuntimeDriverChanged': True}, helper)
    with pytest.raises(ValueError, match='inputs are missing'):
        module.validate_candidate_native_lineage({}, marker, helper)
    with pytest.raises(ValueError, match='candidate native lineage is missing'):
        module.validate_candidate_native_lineage({**receipt, 'inputs': {}}, marker, helper)
    moved = {**receipt, 'candidateNative': dict(receipt['inputs']['candidateNative']), 'inputs': {}}
    with pytest.raises(ValueError, match='moved top-level'):
        module.validate_candidate_native_lineage(moved, marker, helper)
