"""Host-only tests for the single sealed Wine60 interruption authorization."""

from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]
SOURCE = ROOT / "scripts/diagnostics/android-process/wine_interrupted_run_contract.py"
SPEC = importlib.util.spec_from_file_location("wine_interrupted_contract", SOURCE)
assert SPEC and SPEC.loader
contract = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(contract)


def test_external_seals_and_fixed_snapshot_paths_are_authenticated() -> None:
    value = contract.verify_contract_source()
    assert value["prior"]["current"] == {
        "status": "ACTIVE", "stage": "PREFLIGHT", "bytes": 575,
        "sha256": "745eff1e97de89de1de4c5665ce064564022686cf8409b613cce71aebc022cf2",
    }
    assert sum(item["status"] == "MISSING" for item in value["fixedPaths"]) > 0
    assert value["receipt"]["closureObserved"] is False
    assert value["receipt"]["qualification"] is False
    assert value["receipt"]["echild"] is False


def test_contract_rejects_changed_bytes_and_wrong_binding() -> None:
    value, _ = contract.load_contract()
    prior = value["prior"]
    current = b"x" * prior["current"]["bytes"]
    with pytest.raises(ValueError, match="prior authority"):
        # The pure check is intentionally strict before any receipt can be written.
        contract_value = dict(value)
        contract_value["prior"] = dict(prior)
        contract_value["prior"]["current"] = dict(prior["current"])
        contract_value["prior"]["current"]["sha256"] = hashlib.sha256(current).hexdigest()
        contract.verify_external_evidence(contract_value)


def test_fixed_contract_has_no_generic_recovery_namespace() -> None:
    value, raw = contract.load_contract()
    assert hashlib.sha256(raw).hexdigest() == contract.CONTRACT_SHA256
    assert value["kind"] == "ABANDONED_ON_NEW_BOOT"
    assert value["prior"]["runId"] == "RUN-1791718684192-65783495695"
    assert all("EOF59" not in json.dumps(item) for item in value["fixedPaths"])


def test_external_pin_rejects_symlink_leaf_and_ancestor(tmp_path: Path) -> None:
    root = tmp_path / "snapshot"
    root.mkdir()
    leaf = root / "leaf"
    leaf.symlink_to("missing")
    with pytest.raises(contract.ContractError, match="missing path"):
        contract._pin(leaf, {"status": "MISSING", "path": "leaf"}, root)
    ancestor = root / "linked"
    ancestor.symlink_to(tmp_path / "elsewhere", target_is_directory=True)
    with pytest.raises(contract.ContractError, match="symlink"):
        contract._pin(ancestor / "leaf", {"status": "MISSING", "path": "linked/leaf"}, root)


@pytest.mark.parametrize("relative", [
    "files/forever/wine-controls/WS-024cc10c5e18649fad0c6567191aa18b",
    "files/forever/wine-controls/WS-024cc10c5e18649fad0c6567191aa18b/prefix-stage/.wine",
    "files/forever/wine-controls/WS-024cc10c5e18649fad0c6567191aa18b/prefix/.wine",
])
def test_each_missing_wine_root_rejects_a_symlink(tmp_path: Path, relative: str) -> None:
    root = tmp_path / "snapshot"
    target = root / relative
    target.parent.mkdir(parents=True)
    target.symlink_to("missing")
    with pytest.raises(contract.ContractError, match="missing path"):
        contract._pin(target, {"status": "MISSING", "path": relative}, root)


def test_protected_compiler_present_tamper_is_not_accepted(tmp_path: Path) -> None:
    root = tmp_path / "snapshot"
    root.mkdir()
    protected = root / "compiler.bin"
    protected.write_bytes(b"tampered")
    with pytest.raises(contract.ContractError, match="hash mismatch"):
        contract._pin(protected, {
            "status": "PRESENT", "path": "compiler.bin", "bytes": 8, "sha256": "0" * 64,
        }, root)


def test_prospective_receipts_are_not_sealed_fixed_paths() -> None:
    value, _ = contract.load_contract()
    fixed = {item["path"] for item in value["fixedPaths"]}
    prospective = {item["path"] for item in value["transactionPreconditions"]}
    assert len(fixed) == 33
    assert fixed.isdisjoint(prospective)
