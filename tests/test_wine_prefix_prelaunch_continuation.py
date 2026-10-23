"""Directed host gates for the one-shot candidate62 continuation authority."""

from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
CONTRACT = ROOT / "scripts/diagnostics/android-process/wine_prefix_prelaunch_continuation_contract.py"
spec = importlib.util.spec_from_file_location("wine_prefix_continuation", CONTRACT)
assert spec and spec.loader
continuation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(continuation)

APPLY = ROOT / "scripts/arm64-prototype/app/apply_overlay.py"
spec = importlib.util.spec_from_file_location("wine_overlay_candidate62", APPLY)
assert spec and spec.loader
overlay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(overlay)


def test_full_authority_and_seals_are_authenticated() -> None:
    value = continuation.verify_contract_source()
    assert len(value["fixedPaths"]) == 58
    assert value["admission"] == {
        "type": "PRELAUNCH_ABORT", "oneShot": True,
        "knownFailureVersionCode": 61, "knownFailureVersionName": "0.2.37-automated",
        "newVersionCode": 62, "newVersionName": "0.2.38-automated",
        "preserveKnownFailureStatus": True, "preserveUnknownNativeStatus": True,
        "requiresFreshBoot": True, "requiresFreshRunWsNonce": True,
    }
    assert sum(item["status"] == "PRESENT" for item in value["fixedPaths"]) == 19
    assert sum(item["status"] == "MISSING" for item in value["fixedPaths"]) == 39


def test_new_candidate_is_available_as_a_source_identity_without_colliding() -> None:
    assert overlay.resolve_release_version('versionCode = 61\nversionName = "0.2.37-automated"') == (61, "0.2.37-automated", True)
    assert overlay.resolve_release_version('versionCode = 62\nversionName = "0.2.38-automated"') == (62, "0.2.38-automated", True)
    with pytest.raises(ValueError, match="collision"):
        overlay.resolve_release_version('versionCode = 62\nversionName = "wrong"')


def test_contract_rejects_mutated_status_authority() -> None:
    value, _ = continuation.load_contract()
    changed = json.loads(json.dumps(value))
    changed["fixedPaths"][8]["sha256"] = hashlib.sha256(b"mutated-current").hexdigest()
    with pytest.raises(ValueError, match="captured bytes/hash mismatch"):
        continuation.verify_external_evidence(changed)


def test_contract_preserves_known_unknown_and_no_positive_closure() -> None:
    value, _ = continuation.load_contract()
    failure = value["knownFailure"]
    assert failure["current"]["status"] == "FAILED"
    assert failure["current"]["terminationUnknown"] is True
    assert failure["current"]["quiescenceConfirmed"] is False
    assert failure["wineResult"]["sha256"] == "d25aec706b9cf564ae58ff78c43d8567f0a87e2d3f55736b3e01297bb7a04f04"
    assert value["transactionShape"]["nativeStatus"] == "UNKNOWN"
    assert value["transactionShape"]["closureObserved"] is False
    assert value["transactionShape"]["qualification"] is False
    assert value["transactionShape"]["echild"] is False
