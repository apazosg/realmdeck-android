from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import stat
import sys
from types import SimpleNamespace
import zipfile

import pytest


ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "scripts/arm64-prototype/app/reconstruct_functional_source.py"
SPEC = importlib.util.spec_from_file_location("reconstruct_functional_source", SCRIPT)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = module
SPEC.loader.exec_module(module)


def _privacy_delta_fixture(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, *, candidate: bool = False, storage_candidate: bool = False,
    light_storage_candidate: bool = False,
) -> tuple[Path, SimpleNamespace]:
    old_contract = {
        "schema": "test-disposition/v1",
        "device": {"model": "synthetic", "serial": "private-test-value"},
        "candidate": {"versionCode": 61},
    }
    new_contract = json.loads(json.dumps(old_contract))
    new_contract["device"]["serial"] = "DEVICE_SERIAL"
    old_bytes = (json.dumps(old_contract, sort_keys=True) + "\n").encode()
    new_bytes = (json.dumps(new_contract, sort_keys=True) + "\n").encode()
    contract_sha = module.sha256_bytes(new_bytes)

    historical_recipe = tmp_path / "historical-apply-overlay.py"
    historical_recipe.write_text("synthetic frozen recipe\n", encoding="utf-8")
    candidate_recipe = tmp_path / "candidate-apply-overlay.py"
    candidate_recipe.write_text(
        "synthetic reviewed light recipe\n" if light_storage_candidate else "synthetic reviewed recipe\n",
        encoding="utf-8",
    )
    historical_recipe_sha = module.sha256_file(historical_recipe)
    candidate_recipe_sha = module.sha256_file(candidate_recipe)
    recipe = tmp_path / "apply_overlay.py"
    recipe.write_bytes((candidate_recipe if candidate else historical_recipe).read_bytes())
    monkeypatch.setattr(module, "APPLY_OVERLAY", recipe)
    monkeypatch.setattr(module, "C138_RECIPE_SHA256", historical_recipe_sha)
    if light_storage_candidate:
        monkeypatch.setattr(module, "REVIEWED_LIGHT_STORAGE_RECIPE_SHA256", candidate_recipe_sha)
    elif storage_candidate:
        monkeypatch.setattr(module, "REVIEWED_STORAGE_RECIPE_SHA256", candidate_recipe_sha)
    else:
        monkeypatch.setattr(module, "REVIEWED_CANDIDATE_RECIPE_SHA256", candidate_recipe_sha)
    monkeypatch.setattr(module, "CURRENT_DISPOSITION_SOURCE", tmp_path / "disposition.json")
    module.CURRENT_DISPOSITION_SOURCE.write_bytes(new_bytes)
    monkeypatch.setattr(module, "C138_DISPOSITION_SHA256", contract_sha)

    source_input = "scripts/arm64-prototype/app/overlay/ForeverClientProvisioner.kt"
    generated_path = "app/src/main/java/app/gamenative/ForeverClientProvisioner.kt"
    provenance = {
        "recipeSha256": historical_recipe_sha,
        "interruptedRunDisposition": {
            "sha256": contract_sha,
            "sourceSha256": contract_sha,
        },
        "sourceBaseline": {
            "overlayBindings": {
                "approvedReviewedOverlayDeltas": {},
                "sourceInputSHA256": {source_input: "35e78fd6b1ff0eea27da67cee267fa8619f9f440aa955a07ded0794623bcff05"},
                "generatedSHA256": {generated_path: "35e78fd6b1ff0eea27da67cee267fa8619f9f440aa955a07ded0794623bcff05"},
                "count": 63,
            },
            "wine60SourceInputs": {},
        },
    }
    historical_provenance_bytes = (json.dumps(provenance, sort_keys=True) + "\n").encode()
    monkeypatch.setattr(module, "C138_PROVENANCE_SHA256", module.sha256_bytes(historical_provenance_bytes))
    candidate_provenance = json.loads(json.dumps(provenance))
    bindings = candidate_provenance["sourceBaseline"]["overlayBindings"]
    candidate_provenance["recipeSha256"] = candidate_recipe_sha
    if storage_candidate or light_storage_candidate:
        storage_sha = (module.REVIEWED_LIGHT_STORAGE_SOURCE_SHA256 if light_storage_candidate
                       else module.REVIEWED_STORAGE_SOURCE_SHA256)
        storage_input = "scripts/arm64-prototype/app/overlay/ForeverFunctionalClientStorage.kt"
        storage_target = "app/src/main/java/app/gamenative/ForeverFunctionalClientStorage.kt"
        bindings["approvedReviewedOverlayDeltas"]["ForeverFunctionalClientStorage.kt"] = storage_sha
        bindings["sourceInputSHA256"][storage_input] = storage_sha
        bindings["generatedSHA256"][storage_target] = storage_sha
        bindings["count"] = 64
        candidate_provenance["sourceBaseline"]["wine60SourceInputs"][storage_target] = storage_sha
    else:
        bindings["approvedReviewedOverlayDeltas"]["ForeverClientProvisioner.kt"] = module.REVIEWED_CANDIDATE_PROVISIONER_SHA256
        bindings["sourceInputSHA256"][source_input] = module.REVIEWED_CANDIDATE_PROVISIONER_SHA256
        bindings["generatedSHA256"][generated_path] = module.REVIEWED_CANDIDATE_PROVISIONER_SHA256
    candidate_provenance_bytes = (json.dumps(candidate_provenance, sort_keys=True) + "\n").encode()
    if light_storage_candidate:
        monkeypatch.setattr(module, "REVIEWED_LIGHT_STORAGE_PROVENANCE_SHA256", module.sha256_bytes(candidate_provenance_bytes))
    elif storage_candidate:
        monkeypatch.setattr(module, "REVIEWED_STORAGE_PROVENANCE_SHA256", module.sha256_bytes(candidate_provenance_bytes))
    else:
        monkeypatch.setattr(module, "REVIEWED_CANDIDATE_PROVENANCE_SHA256", module.sha256_bytes(candidate_provenance_bytes))
    provenance_bytes = candidate_provenance_bytes if candidate else historical_provenance_bytes

    apk = tmp_path / "authenticated-code64.apk"
    historical = {
        module.CODE_PROVENANCE_MEMBER: historical_provenance_bytes,
        module.INTERRUPTED_DISPOSITION_MEMBER: old_bytes,
    }
    with zipfile.ZipFile(apk, "w") as archive:
        for member, data in historical.items():
            archive.writestr(member, data)
    inputs = SimpleNamespace(
        code64_apk=apk,
        code64_payloads={member: module.sha256_bytes(data) for member, data in historical.items()},
    )

    source = tmp_path / "source"
    payloads = {
        module.CODE_PROVENANCE_MEMBER: provenance_bytes,
        module.INTERRUPTED_DISPOSITION_MEMBER: new_bytes,
    }
    for member, data in payloads.items():
        target = source / module.update_asset_source_relative(member)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    return source, inputs


def _json_changed_paths(before: object, after: object, prefix: str = "") -> set[str]:
    if isinstance(before, dict) and isinstance(after, dict):
        paths = set()
        for key in before.keys() | after.keys():
            path = f"{prefix}.{key}" if prefix else str(key)
            if key not in before or key not in after:
                paths.add(path)
            else:
                paths.update(_json_changed_paths(before[key], after[key], path))
        return paths
    return set() if before == after else {prefix}


def test_candidate_build_requires_exact_code62_and_uses_code97() -> None:
    source = 'android {\n    versionCode = 62\n    versionName = "0.2.38-automated"\n}\n'
    result = module.transform_build_62_to_candidate(source)
    assert f'versionCode = {module.CANDIDATE_VERSION[0]}' in result
    assert f'versionName = "{module.CANDIDATE_VERSION[1]}"' in result
    assert 'buildConfigField("String", "FOREVER_SERVER_PROFILE", "\\"classic_forever\\"")' in result
    assert 'configurations.named("androidTestImplementation")' in result
    assert 'dependencies.clear()' in result
    assert 'testInstrumentationRunner = "app.gamenative.ForeverFunctionalAutologinProviderInstrumentation"' in result
    with pytest.raises(module.ReconstructionError, match="exact code62"):
        module.transform_build_62_to_candidate(source.replace("versionCode = 62", "versionCode = 63"))


def test_canonical_source_transforms_include_owned_audio_shutdown(tmp_path: Path) -> None:
    functional = module.load_function(module.APPLY_FUNCTIONAL, "audio_reconstruction_regression")
    source = tmp_path / "source"
    fixtures = {
        module.BUILD: 'android {\n    versionCode = 62\n    versionName = "0.2.38-automated"\n}\n',
        module.MANIFEST: "synthetic manifest",
        module.XSERVER: "synthetic XServer",
        functional.PULSE_AUDIO: functional.AUDIO_STOP_ORIGINAL,
    }
    for relative, text in fixtures.items():
        target = source / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
    transforms = module.apply_candidate_source_transforms(source, SimpleNamespace(
        transform_manifest=lambda text: text + " transformed",
        transform_xserver=lambda text: text + " transformed",
        PULSE_AUDIO=functional.PULSE_AUDIO,
        transform_pulse_audio=functional.transform_pulse_audio,
    ))
    assert (source / functional.PULSE_AUDIO).read_text() == functional.AUDIO_STOP_OWNED
    audio = next(entry for entry in transforms if entry["path"] == functional.PULSE_AUDIO.as_posix())
    assert audio["beforeSha256"] == module.sha256_bytes(functional.AUDIO_STOP_ORIGINAL.encode())
    assert audio["afterSha256"] == module.sha256_bytes(functional.AUDIO_STOP_OWNED.encode())


def test_candidate_server_profile_field_is_idempotent_and_validated() -> None:
    source = 'android {\n    versionCode = 62\n    versionName = "0.2.38-automated"\n}\n'
    once = module.transform_build_62_to_candidate(source)
    assert once.count("FOREVER_SERVER_PROFILE") == 1
    # Idempotent on a second pass over the already-migrated text.
    assert module._ensure_candidate_server_profile_field(once) == once
    with pytest.raises(module.ReconstructionError, match="differs from classic_forever"):
        module._ensure_candidate_server_profile_field(once.replace("classic_forever", "original"))


def test_provider_validation_harness_is_not_staged_in_main_apk(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    entries = module.stage_provider_validation(source)
    assert len(entries) == 2
    assert all(item["path"].startswith("app/src/androidTest/") for item in entries)
    assert not (source / "app/src/main/java/app/gamenative/ForeverFunctionalAutologinProviderInstrumentation.kt").exists()


def test_candidate_transform_uses_only_known_functional_overlay_sources(tmp_path: Path) -> None:
    source = tmp_path / "source"
    (source / "app/src/main/java/app/gamenative").mkdir(parents=True)
    known = module.APP_ROOT / "overlay/ForeverFunctionalPoc.kt"
    target = source / "app/src/main/java/app/gamenative/ForeverFunctionalPoc.kt"
    target.write_bytes(known.read_bytes())
    entries = module.stage_functional_sources(source)
    assert len(entries) == len(module.FUNCTIONAL_OVERLAY_NAMES)
    assert all(entry["origin"].startswith("repo-functional-overlay:") for entry in entries)
    icons = "ForeverFunctionalIcons.kt"
    assert (source / "app/src/main/java/app/gamenative" / icons).read_bytes() == (
        module.APP_ROOT / "overlay" / icons
    ).read_bytes()

    target.write_bytes(b"unknown collision")
    with pytest.raises(module.ReconstructionError, match="unknown functional destination collision"):
        module.stage_functional_sources(source)


def test_source_inventory_rejects_symlinks(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    regular = source / "regular.txt"
    regular.write_text("ok", encoding="utf-8")
    (source / "link.txt").symlink_to(regular)
    with pytest.raises(module.ReconstructionError, match="contains a symlink"):
        module.source_inventory(source)


def test_pinned_tree_rejects_altered_assets_and_symlinks(tmp_path: Path) -> None:
    root = tmp_path / "probes"
    root.mkdir()
    payload = root / "probe.bin"
    payload.write_bytes(b"pinned")
    pins = {"probe.bin": module.sha256_file(payload)}
    assert module.validate_pinned_tree(root, "test probes", pins)["probe.bin"]["bytes"] == 6

    payload.write_bytes(b"altered")
    with pytest.raises(module.ReconstructionError, match="hash mismatch"):
        module.validate_pinned_tree(root, "test probes", pins)
    payload.write_bytes(b"pinned")
    (root / "link").symlink_to(payload)
    with pytest.raises(module.ReconstructionError, match="contains a symlink"):
        module.validate_pinned_tree(root, "test probes", pins)


def test_zip_member_rejects_traversal_and_special_entries() -> None:
    with pytest.raises(module.ReconstructionError, match="unsafe"):
        module._safe_zip_member(zipfile.ZipInfo("../escape"))
    special = zipfile.ZipInfo("special")
    special.external_attr = (stat.S_IFLNK | 0o777) << 16
    with pytest.raises(module.ReconstructionError, match="special"):
        module._safe_zip_member(special)


def test_safe_extract_rejects_unsafe_archive_members(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    archive = tmp_path / "source.zip"
    with zipfile.ZipFile(archive, "w") as output:
        output.writestr("GameNative/app/build.gradle.kts", "versionCode = 62\n")
        output.writestr("../escape", "no")
    monkeypatch.setattr(module, "PINNED_ARCHIVE_SHA256", module.sha256_file(archive))
    with pytest.raises(module.ReconstructionError, match="unsafe source archive member"):
        module.safe_extract(archive, tmp_path / "extracted")


def test_new_controller_source_is_distinct_from_historical_pin() -> None:
    data, metadata = module.validate_new_controller_source()
    assert module.sha256_bytes(data) == module.PINNED_NEW_CONTROLLER_SHA256
    assert metadata["historicalSha256"] == module.PINNED_HISTORICAL_CONTROLLER_SHA256
    assert metadata["sourceSha256"] == module.PINNED_NEW_CONTROLLER_SHA256
    assert metadata["reviewStatus"] == "INDEPENDENTLY_REVIEWED_NEW_CANDIDATE_INPUT"


def test_new_bridge_rejects_destination_collision(tmp_path: Path) -> None:
    data, metadata = module.validate_bridge_source()
    assert metadata["sha256"] == module.PINNED_BRIDGE_SHA256
    source = tmp_path / "source"
    target = source / module.BRIDGE_TARGET
    target.parent.mkdir(parents=True)
    target.write_bytes(b"collision")
    with pytest.raises(module.ReconstructionError, match="destination collision"):
        module.stage_new_bridge(source, data)


def test_elf_audit_rejects_wrong_abi() -> None:
    bad = bytearray(64)
    bad[:4] = b"\x7fELF"
    bad[4:6] = bytes((2, 1))
    bad[18:20] = (62).to_bytes(2, "little")
    with pytest.raises(module.ReconstructionError, match="not AArch64"):
        module.elf_audit(bytes(bad), module.FUNCTIONAL_EXPORTS)


def test_authenticated_code64_jni_has_all_production_and_functional_exports() -> None:
    inputs = module.authenticate_inputs(ROOT / "forever/contract.json")
    assert len(inputs.code64_payloads) == 174
    assert inputs.code64_native_audit["sha256"] == module.PINNED_CODE64_NATIVE_SHA256
    assert set(inputs.code64_native_audit["exports"]) == module.REQUIRED_EXPORTS


def test_updater_gate_sources_are_explicit_and_historical_casc_preimages_are_preserved(tmp_path: Path) -> None:
    source = tmp_path / "source"
    main = source / "app/src/main/java/app/gamenative"
    tests = source / "app/src/test/java/app/gamenative"
    main.mkdir(parents=True)
    tests.mkdir(parents=True)
    preimages = {**module.PINNED_UPDATER_CASC_PREIMAGES, **module.PINNED_UPDATER_BLTE_PREIMAGES}
    postimages = {**module.PINNED_UPDATER_CASC_POSTIMAGES, **module.PINNED_UPDATER_BLTE_POSTIMAGES}
    for name, target in (("ForeverCasc.kt", main), ("ForeverCascTest.kt", tests), ("ForeverBlte.kt", main)):
        original = module.APP_ROOT / "overlay" / name
        assert module.sha256_file(original) == preimages[name]
        (target / name).write_bytes(original.read_bytes())

    entries = module.stage_updater_gate_sources(source)
    assert len(entries) == len(module.UPDATER_GATE_NAMES)
    replaced_entries = {Path(item["path"]).name: item for item in entries if Path(item["path"]).name in preimages}
    assert set(replaced_entries) == set(preimages)
    for name, entry in replaced_entries.items():
        assert entry["beforeSha256"] == preimages[name]
        assert entry["afterSha256"] == postimages[name]
        assert entry["originSha256"] == postimages[name]
        assert entry["reviewStatus"] == "EXPLICITLY_REVIEWED_UPDATER_GATE_POSTIMAGE"
        assert module.sha256_file(source / entry["path"]) == postimages[name]

    for name in (*module.UPDATER_GATE_MAIN_NAMES, *module.UPDATER_GATE_TEST_NAMES):
        item = next(entry for entry in entries if Path(entry["path"]).name == name)
        assert item["origin"] == f"repo-updater-gate:{name}"
        assert item["originSha256"] == module.sha256_file(module.UPDATER_GATE_ROOT / name)

    assert module.sha256_file(module.APP_ROOT / "overlay/ForeverCasc.kt") == module.PINNED_UPDATER_CASC_PREIMAGES["ForeverCasc.kt"]
    assert module.sha256_file(module.APP_ROOT / "overlay/ForeverCascTest.kt") == module.PINNED_UPDATER_CASC_PREIMAGES["ForeverCascTest.kt"]
    assert module.sha256_file(module.APP_ROOT / "overlay/ForeverBlte.kt") == module.PINNED_UPDATER_BLTE_PREIMAGES["ForeverBlte.kt"]


def test_wifi_runtime_patch_rejects_unreviewed_backend_without_modifying_it(tmp_path: Path) -> None:
    target = tmp_path / "app/src/main/java/app/gamenative/service/SteamService.kt"
    target.parent.mkdir(parents=True)
    original = b"unreviewed runtime backend"
    target.write_bytes(original)
    with pytest.raises(module.ReconstructionError, match="preimage is not the reviewed source"):
        module.stage_wifi_runtime_backend(tmp_path)
    assert target.read_bytes() == original


def test_updater_gate_replacement_rejects_unpinned_historical_casc_preimage(tmp_path: Path) -> None:
    source = tmp_path / "source"
    target = source / "app/src/main/java/app/gamenative/ForeverCasc.kt"
    target.parent.mkdir(parents=True)
    target.write_bytes(b"not the historical code63 binding")
    with pytest.raises(module.ReconstructionError, match="preimage hash mismatch"):
        module._replace_authenticated_preimage(
            source,
            Path("app/src/main/java/app/gamenative/ForeverCasc.kt"),
            (module.UPDATER_GATE_ROOT / "ForeverCasc.kt").read_bytes(),
            module.PINNED_UPDATER_CASC_PREIMAGES["ForeverCasc.kt"],
            module.PINNED_UPDATER_CASC_POSTIMAGES["ForeverCasc.kt"],
            "repo-updater-gate:ForeverCasc.kt",
        )


def test_payload_parity_accepts_only_three_pinned_assets_and_the_source_target_contract(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source = tmp_path / "source"
    assets = source / "app/src/main/assets"
    native = source / "app/src/main/jniLibs/arm64-v8a"
    marker = assets / "forever/functional-poc/provenance.json"
    marker.parent.mkdir(parents=True)
    marker.write_bytes(b"candidate marker")
    native.mkdir(parents=True)
    native_bytes = b"baseline jni"
    (native / "libforever_patcher_jni.so").write_bytes(native_bytes)

    target_bytes, target_manifest = module.validate_client_target_source()
    pins = {}
    for name, content in (("catalog.sqlite3.gzip", b"catalog"), ("build-config.txt", b"build"), ("cdn-config.txt", b"cdn")):
        member = f"assets/forever/client-update/{name}"
        path = tmp_path / name
        path.write_bytes(content)
        pins[member] = module.PinnedUpdateAsset(path, len(content), module.sha256_bytes(content))
        target = assets / "forever/client-update" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    monkeypatch.setattr(module, "PINNED_UPDATE_ASSETS", pins)
    target_member = module.CLIENT_TARGET_MEMBER
    target_path = source / module.update_asset_source_relative(target_member)
    target_path.parent.mkdir(parents=True, exist_ok=True)
    target_path.write_bytes(target_bytes)

    baseline = {
        module.FUNCTIONAL_MARKER_MEMBER: "old-marker-hash",
        "lib/arm64-v8a/libforever_patcher_jni.so": module.sha256_bytes(native_bytes),
    }
    result = module.compare_code64_payloads(
        source,
        SimpleNamespace(
            code64_payloads=baseline,
            client_target_bytes=target_bytes,
            client_target_manifest=target_manifest,
        ),
        dict(baseline),
    )
    assert result["sourcePayloadMembers"] == 6
    assert result["code64PayloadMembers"] == 2
    assert result["addedPinnedUpdateMetadata"] == sorted((*pins, target_member))
    assert result["nativeRuntimeDriverChanged"] is False
    assert not any(member.endswith(".gz") for member in result["addedPinnedUpdateMetadata"])

    unknown = assets / "forever/client-update/unreviewed.bin"
    unknown.write_bytes(b"unreviewed")
    with pytest.raises(module.ReconstructionError, match="payload path parity failed"):
        module.compare_code64_payloads(
            source,
            SimpleNamespace(
                code64_payloads=baseline,
                client_target_bytes=target_bytes,
                client_target_manifest=target_manifest,
            ),
            dict(baseline),
        )


def test_pinned_update_assets_require_nofollow_regular_files_and_stream_into_source(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    external = tmp_path / "external"
    external.mkdir()
    pins = {}
    for name, content in (("catalog.gzip", b"catalog metadata"), ("build.txt", b"build config"), ("cdn.txt", b"cdn config")):
        path = external / name
        path.write_bytes(content)
        pins[f"assets/forever/client-update/{name}"] = module.PinnedUpdateAsset(
            path, len(content), module.sha256_bytes(content)
        )
    monkeypatch.setattr(module, "UPDATE_ASSET_ROOT", external)
    monkeypatch.setattr(module, "PINNED_UPDATE_ASSETS", pins)
    assert set(module.validate_pinned_update_assets()) == set(pins)

    source = tmp_path / "source"
    source.mkdir()
    entries = module.stage_pinned_update_assets(source)
    assert {item["member"] for item in entries} == set(pins)
    for member, pin in pins.items():
        target = source / module.update_asset_source_relative(member)
        assert target.read_bytes() == pin.source.read_bytes()
        assert module.sha256_file(target) == pin.sha256

    bad = external / "cdn.txt"
    bad.unlink()
    bad.symlink_to(external / "build.txt")
    with pytest.raises(OSError):
        module.validate_pinned_update_assets()


def test_public_target_is_small_stable_and_bound_to_known_fallback_pins(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    target = tmp_path / "target.json"
    source_data = module.CLIENT_TARGET_SOURCE.read_bytes()
    target.write_bytes(source_data)
    monkeypatch.setattr(module, "CLIENT_TARGET_SOURCE", target)
    raw, manifest = module.validate_client_target_source()
    assert raw == source_data
    assert manifest["member"] == "assets/forever/client-update/target.json"
    assert manifest["sha256"] == module.sha256_bytes(raw)
    assert manifest["authority"] == module.CLIENT_TARGET_AUTHORITY
    assert manifest["bindings"]["native_validation"] == {"status": "pending", "evidence_sha256": None}

    changed = json.loads(source_data)
    changed["build_config"]["hash"] = "changed-config-key"
    target.write_text(json.dumps(changed), encoding="utf-8")
    with pytest.raises(module.ReconstructionError, match="build-config binding"):
        module.validate_client_target_source()

    changed = json.loads(source_data)
    changed["native_validation"] = {"status": "verified", "evidence_sha256": "fabricated"}
    target.write_text(json.dumps(changed), encoding="utf-8")
    with pytest.raises(module.ReconstructionError, match="cannot claim unproven native validation"):
        module.validate_client_target_source()

    target.write_bytes(b" " * (module.CLIENT_TARGET_MAX_BYTES + 1))
    with pytest.raises(module.ReconstructionError, match="32768-byte limit"):
        module.validate_client_target_source()


def test_public_target_staging_preserves_exact_authenticated_source_bytes(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    target = tmp_path / "target.json"
    target.write_bytes(module.CLIENT_TARGET_SOURCE.read_bytes())
    monkeypatch.setattr(module, "CLIENT_TARGET_SOURCE", target)
    raw, manifest = module.validate_client_target_source()
    source = tmp_path / "source"
    source.mkdir()
    entry = module.stage_client_target_asset(source, raw, manifest)
    packaged = source / module.update_asset_source_relative(module.CLIENT_TARGET_MEMBER)
    assert entry["origin"] == "public-client-target-contract"
    assert packaged.read_bytes() == raw
    assert entry["sha256"] == module.sha256_bytes(packaged.read_bytes())
    assert entry["targetBindings"] == manifest["bindings"]


def test_updater_gate_admission_matches_exact_current_kotlin_inventory() -> None:
    actual = {path.name for path in module.UPDATER_GATE_ROOT.iterdir() if path.name.endswith(".kt")}
    assert actual == module.UPDATER_GATE_NAMES
    assert "ForeverTactMetadata.kt" in module.UPDATER_GATE_MAIN_NAMES
    assert "ForeverTactMetadataTest.kt" not in module.UPDATER_GATE_NAMES


def test_candidate_version_and_privacy_hash_pins_match_frozen_recipe_output() -> None:
    assert module.CANDIDATE_VERSION == (145, "0.2.121-commit-group-16")
    assert module.C138_DISPOSITION_SHA256 == "c198e20f582b90e5bc691e3e4712e8b2b1ecf33434bf1f86232c30be7fab2ac6"
    assert module.C138_RECIPE_SHA256 == "f3deeab1112963bfa1b805f1fec465375f64801696cbe918c365855755e29127"
    assert module.C138_PROVENANCE_SHA256 == "5254e4a33471f7c9600140a7c5f0ab6e6f90be493c85d1824b386b6723acda35"
    assert module.REVIEWED_CANDIDATE_RECIPE_SHA256 == "385e1c4c443aa4a192a14769caacc4af959d2a2ee498e82485fb9eb6cbc4e151"
    assert module.REVIEWED_CANDIDATE_PROVENANCE_SHA256 == "aa8f0dc18cda8f92e2a6dac73349a36845d67493a2c2cf712f622b9594d10a20"
    assert module.REVIEWED_STORAGE_SOURCE_SHA256 == "9497a0aa068f93c3be0d2a64e5ef2b4eab3fbf22510d1510e6ae9df63c622443"
    assert module.REVIEWED_STORAGE_RECIPE_SHA256 == "df97574d8c5dce73ea53b535cf52e1bd28d556bcb2bcef0eef92f5704a5060df"
    assert module.REVIEWED_STORAGE_PROVENANCE_SHA256 == "867d8d4a51b86865d6232d14c7b746f8117bdb7022addbd86eb840eb9e2b1efa"
    assert module.sha256_file(module.APPLY_OVERLAY) == module.REVIEWED_LIGHT_STORAGE_RECIPE_SHA256
    assert module.privacy_provenance_pin_for_recipe(module.C138_RECIPE_SHA256) == module.C138_PROVENANCE_SHA256
    assert module.privacy_provenance_pin_for_recipe(module.REVIEWED_CANDIDATE_RECIPE_SHA256) == module.REVIEWED_CANDIDATE_PROVENANCE_SHA256
    assert module.privacy_provenance_pin_for_recipe(module.REVIEWED_STORAGE_RECIPE_SHA256) == module.REVIEWED_STORAGE_PROVENANCE_SHA256
    with pytest.raises(module.ReconstructionError, match="no frozen privacy provenance pair"):
        module.privacy_provenance_pin_for_recipe("0" * 64)
    assert module.PINNED_CODE64_APK_SHA256 == "1d91f727f0322d4100e43a37277ed5fe6714f24fb66aeb9e062b702bd5fe4324"
    assert module.PINNED_CODE64_AUDIT_SHA256 == "325fcad6cb9cc5898628c04568f82636cd59aa129f0fc726ffdddcea17b4e0e8"
    assert module.PINNED_CODE64_NATIVE_SIDECAR_SHA256 == "7318ed2b4f7b14d1fe1d1cd520a7a597c1d938c4cb66b02a403218b7a7f8e6af"
    assert module.PINNED_CODE64_NATIVE_SHA256 == "ec8573de09cddc6f28606a6b361e675a74432d0c3349a4daef0eb4cfe72189b1"
    assert module.PINNED_CANDIDATE_NATIVE_SHA256 == "79af82848e15de5395ddfb52f916c95ff3212fdb0e579cc69b96e2d32e4c1bd3"
    assert module.PINNED_CANDIDATE_NATIVE_BYTES == 1005224
    assert module.PINNED_CANDIDATE_NATIVE_SIDECAR_SHA256 == "15f64c0185e65a8a592156f5041aaadfa3ca4b7d2a2a6d17e3f358fd0a9e3b41"
    assert module.CANDIDATE_NATIVE_ORIGIN == "candidate-native-c141-eligible-scan"


def test_privacy_sanitization_accepts_only_serial_redaction_with_frozen_payload_hashes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source, inputs = _privacy_delta_fixture(tmp_path, monkeypatch)
    receipt = module.validate_sanitized_c138_payloads(source, inputs)
    assert receipt["status"] == "PASS_EXACT_DEVICE_SERIAL_REDACTION"
    assert receipt["changedField"] == "device.serial"
    assert receipt["replacement"] == "DEVICE_SERIAL"
    assert set(receipt) == {"status", "changedField", "replacement", "recipeSha256", "payloads"}
    assert receipt["recipeSha256"] == module.C138_RECIPE_SHA256
    assert receipt["payloads"][module.CODE_PROVENANCE_MEMBER]["afterSha256"] == module.C138_PROVENANCE_SHA256
    assert receipt["payloads"][module.INTERRUPTED_DISPOSITION_MEMBER]["afterSha256"] == module.C138_DISPOSITION_SHA256


def test_privacy_sanitization_admits_exact_reviewed_candidate_provenance_delta(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source, inputs = _privacy_delta_fixture(tmp_path, monkeypatch, candidate=True)
    receipt = module.validate_sanitized_c138_payloads(source, inputs)
    assert receipt["status"] == "PASS_EXACT_DEVICE_SERIAL_REDACTION"
    assert set(receipt) == {"status", "changedField", "replacement", "recipeSha256", "payloads"}
    assert receipt["recipeSha256"] == module.REVIEWED_CANDIDATE_RECIPE_SHA256
    assert receipt["payloads"][module.CODE_PROVENANCE_MEMBER]["afterSha256"] == module.REVIEWED_CANDIDATE_PROVENANCE_SHA256
    assert receipt["payloads"][module.INTERRUPTED_DISPOSITION_MEMBER]["afterSha256"] == module.C138_DISPOSITION_SHA256

    with zipfile.ZipFile(inputs.code64_apk) as archive:
        old_provenance = json.loads(archive.read(module.CODE_PROVENANCE_MEMBER))
    current_provenance = json.loads(
        (source / module.update_asset_source_relative(module.CODE_PROVENANCE_MEMBER)).read_bytes()
    )
    assert _json_changed_paths(old_provenance, current_provenance) == {
        "recipeSha256",
        "sourceBaseline.overlayBindings.approvedReviewedOverlayDeltas.ForeverClientProvisioner.kt",
        "sourceBaseline.overlayBindings.generatedSHA256.app/src/main/java/app/gamenative/ForeverClientProvisioner.kt",
        "sourceBaseline.overlayBindings.sourceInputSHA256.scripts/arm64-prototype/app/overlay/ForeverClientProvisioner.kt",
    }
    assert old_provenance["interruptedRunDisposition"] == current_provenance["interruptedRunDisposition"]


@pytest.mark.parametrize("light", [False, True])
def test_privacy_sanitization_admits_exact_reviewed_storage_provenance_delta(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, light: bool
) -> None:
    source, inputs = _privacy_delta_fixture(tmp_path, monkeypatch, candidate=True,
                                          storage_candidate=not light, light_storage_candidate=light)
    recipe_sha = module.REVIEWED_LIGHT_STORAGE_RECIPE_SHA256 if light else module.REVIEWED_STORAGE_RECIPE_SHA256
    provenance_sha = module.REVIEWED_LIGHT_STORAGE_PROVENANCE_SHA256 if light else module.REVIEWED_STORAGE_PROVENANCE_SHA256
    storage_sha = module.REVIEWED_LIGHT_STORAGE_SOURCE_SHA256 if light else module.REVIEWED_STORAGE_SOURCE_SHA256
    receipt = module.validate_sanitized_c138_payloads(source, inputs)
    assert receipt["status"] == "PASS_EXACT_DEVICE_SERIAL_REDACTION"
    assert set(receipt) == {"status", "changedField", "replacement", "recipeSha256", "payloads"}
    assert receipt["recipeSha256"] == recipe_sha
    assert receipt["payloads"][module.CODE_PROVENANCE_MEMBER]["afterSha256"] == provenance_sha
    assert receipt["payloads"][module.INTERRUPTED_DISPOSITION_MEMBER]["afterSha256"] == module.C138_DISPOSITION_SHA256

    with zipfile.ZipFile(inputs.code64_apk) as archive:
        old_provenance = json.loads(archive.read(module.CODE_PROVENANCE_MEMBER))
    current_provenance = json.loads(
        (source / module.update_asset_source_relative(module.CODE_PROVENANCE_MEMBER)).read_bytes()
    )
    assert _json_changed_paths(old_provenance, current_provenance) == {
        "recipeSha256",
        "sourceBaseline.overlayBindings.approvedReviewedOverlayDeltas.ForeverFunctionalClientStorage.kt",
        "sourceBaseline.overlayBindings.count",
        "sourceBaseline.overlayBindings.generatedSHA256.app/src/main/java/app/gamenative/ForeverFunctionalClientStorage.kt",
        "sourceBaseline.overlayBindings.sourceInputSHA256.scripts/arm64-prototype/app/overlay/ForeverFunctionalClientStorage.kt",
        "sourceBaseline.wine60SourceInputs.app/src/main/java/app/gamenative/ForeverFunctionalClientStorage.kt",
    }
    assert current_provenance["sourceBaseline"]["overlayBindings"]["approvedReviewedOverlayDeltas"]["ForeverFunctionalClientStorage.kt"] == storage_sha
    assert old_provenance["interruptedRunDisposition"] == current_provenance["interruptedRunDisposition"]


def test_heavy_to_light_storage_provenance_changes_exactly_five_leaves(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    provenances = []
    for name, light in (("heavy", False), ("light", True)):
        directory = tmp_path / name
        directory.mkdir()
        with monkeypatch.context() as patch:
            source, inputs = _privacy_delta_fixture(
                directory, patch, candidate=True,
                storage_candidate=not light, light_storage_candidate=light,
            )
            receipt = module.validate_sanitized_c138_payloads(source, inputs)
            assert receipt["status"] == "PASS_EXACT_DEVICE_SERIAL_REDACTION"
            provenances.append(json.loads(
                (source / module.update_asset_source_relative(module.CODE_PROVENANCE_MEMBER)).read_bytes()
            ))
    before, after = provenances
    # Both recipes already admit the same 64 source bindings. This is not
    # the earlier historical 63-to-64-source admission.
    assert before["sourceBaseline"]["overlayBindings"]["count"] == 64
    assert after["sourceBaseline"]["overlayBindings"]["count"] == 64
    assert _json_changed_paths(before, after) == {
        "recipeSha256",
        "sourceBaseline.overlayBindings.approvedReviewedOverlayDeltas.ForeverFunctionalClientStorage.kt",
        "sourceBaseline.overlayBindings.generatedSHA256.app/src/main/java/app/gamenative/ForeverFunctionalClientStorage.kt",
        "sourceBaseline.overlayBindings.sourceInputSHA256.scripts/arm64-prototype/app/overlay/ForeverFunctionalClientStorage.kt",
        "sourceBaseline.wine60SourceInputs.app/src/main/java/app/gamenative/ForeverFunctionalClientStorage.kt",
    }


@pytest.mark.parametrize("tamper", ["recipe", "provenance"])
@pytest.mark.parametrize("light", [False, True])
def test_privacy_sanitization_rejects_unpaired_reviewed_storage_recipe_or_provenance(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, tamper: str, light: bool
) -> None:
    source, inputs = _privacy_delta_fixture(tmp_path, monkeypatch, candidate=True,
                                          storage_candidate=not light, light_storage_candidate=light)
    if tamper == "recipe":
        module.APPLY_OVERLAY.write_text("unreviewed storage recipe\n", encoding="utf-8")
        expected_error = "no frozen privacy provenance pair"
    else:
        target = source / module.update_asset_source_relative(module.CODE_PROVENANCE_MEMBER)
        changed = json.loads(target.read_bytes())
        changed["unreviewed"] = True
        target.write_text(json.dumps(changed, sort_keys=True) + "\n", encoding="utf-8")
        expected_error = "frozen pin"
    with pytest.raises(module.ReconstructionError, match=expected_error):
        module.validate_sanitized_c138_payloads(source, inputs)


@pytest.mark.parametrize("tamper", ["recipe", "provenance"])
def test_privacy_sanitization_rejects_unpaired_reviewed_candidate_recipe_or_provenance(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, tamper: str
) -> None:
    source, inputs = _privacy_delta_fixture(tmp_path, monkeypatch, candidate=True)
    if tamper == "recipe":
        module.APPLY_OVERLAY.write_text("unreviewed recipe\n", encoding="utf-8")
        expected_error = "no frozen privacy provenance pair"
    else:
        target = source / module.update_asset_source_relative(module.CODE_PROVENANCE_MEMBER)
        changed = json.loads(target.read_bytes())
        changed["unreviewed"] = True
        target.write_text(json.dumps(changed, sort_keys=True) + "\n", encoding="utf-8")
        expected_error = "frozen pin"
    with pytest.raises(module.ReconstructionError, match=expected_error):
        module.validate_sanitized_c138_payloads(source, inputs)


@pytest.mark.parametrize("member", [module.INTERRUPTED_DISPOSITION_MEMBER, module.CODE_PROVENANCE_MEMBER])
def test_privacy_sanitization_rejects_changed_contract_or_provenance_even_if_inventory_agrees(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, member: str
) -> None:
    source, inputs = _privacy_delta_fixture(tmp_path, monkeypatch)
    target = source / module.update_asset_source_relative(member)
    changed = json.loads(target.read_bytes())
    if member == module.INTERRUPTED_DISPOSITION_MEMBER:
        changed["candidate"]["versionCode"] = 99
    else:
        changed["unapproved"] = True
    target.write_text(json.dumps(changed, sort_keys=True) + "\n", encoding="utf-8")
    with pytest.raises(module.ReconstructionError, match="differs beyond|frozen pin"):
        module.validate_sanitized_c138_payloads(source, inputs)


def test_c138_enables_reviewed_assisted_sender_and_marks_capture_check_pending() -> None:
    assert "ForeverFunctionalAutologinAssistedEntry.kt" in module.FUNCTIONAL_OVERLAY_NAMES
    assert "ForeverFunctionalAutologinAssistedEntryTest.kt" in module.FUNCTIONAL_OVERLAY_NAMES
    activity = (module.APP_ROOT / "overlay/ForeverFunctionalPocActivity.kt").read_text()
    assert "private const val ASSISTED_ENTRY_ENABLED = true" in activity
    marker = module.candidate_marker({}, [], False, {}, {}, {})
    metadata = json.loads(marker)
    assert metadata["versionCode"] == 145
    assert metadata["versionName"] == "0.2.121-commit-group-16"
    assert metadata["assistedEntrySecurityGate"] == {
        "status": "ENABLED_PHYSICAL_CAPTURE_VERIFICATION_PENDING",
        "enabled": True,
        "physicalCaptureVerification": "PENDING",
    }


def test_candidate_native_provenance_binds_sidecar_toolchain_and_exports() -> None:
    binding, native_bytes = module.authenticate_candidate_native()
    assert binding["member"] == module.CODE64_NATIVE_MEMBER
    assert binding["beforeSha256"] == module.PINNED_CODE64_NATIVE_SHA256
    assert binding["afterSha256"] == module.PINNED_CANDIDATE_NATIVE_SHA256
    assert binding["bytes"] == module.PINNED_CANDIDATE_NATIVE_BYTES == len(native_bytes)
    assert binding["sidecarSha256"] == module.PINNED_CANDIDATE_NATIVE_SIDECAR_SHA256
    assert binding["origin"] == module.CANDIDATE_NATIVE_ORIGIN
    assert module.sha256_bytes(native_bytes) == module.PINNED_CANDIDATE_NATIVE_SHA256
    assert binding["elfAudit"]["sha256"] == module.PINNED_CANDIDATE_NATIVE_SHA256
    assert set(binding["elfAudit"]["exports"]) == module.CANDIDATE_REQUIRED_EXPORTS
    assert binding["capability"] == "guarded-group8-public-key-apply"
    apply_export = "Java_app_gamenative_ForeverFunctionalPocPatchBridge_nativeApply"
    assert apply_export not in module.REQUIRED_EXPORTS
    assert module.CANDIDATE_REQUIRED_EXPORTS == module.REQUIRED_EXPORTS | {apply_export}
    with zipfile.ZipFile(module.CODE64_APK) as apk:
        historical_native = apk.read(module.CODE64_NATIVE_MEMBER)
    module.elf_audit(historical_native, module.REQUIRED_EXPORTS)
    with pytest.raises(module.ReconstructionError, match="missing required JNI exports"):
        module.elf_audit(historical_native, module.CANDIDATE_REQUIRED_EXPORTS)
    assert binding["toolchain"]["api"] == 29
    assert binding["toolchain"]["elfPageSize"] == 16384
    assert isinstance(binding["sourceSha256"], dict) and len(binding["sourceSha256"]) == 23
    assert "crates/forever-patcher-jni/src/functional_full_scan.rs" in binding["sourceSha256"]


def test_candidate_native_replaces_only_the_code64_preimage(tmp_path: Path) -> None:
    _, candidate_bytes = module.authenticate_candidate_native()
    source = tmp_path / "source"
    target = source / module.JNI_RELATIVE
    target.parent.mkdir(parents=True)
    target.write_bytes(b"not the retained preimage")
    with pytest.raises(module.ReconstructionError, match="code64 JNI preimage"):
        module.replace_candidate_native(source, candidate_bytes)
    with zipfile.ZipFile(module.CODE64_APK) as apk:
        target.write_bytes(apk.read(module.CODE64_NATIVE_MEMBER))
    entry = module.replace_candidate_native(source, candidate_bytes)
    assert entry["beforeSha256"] == module.PINNED_CODE64_NATIVE_SHA256
    assert entry["afterSha256"] == module.PINNED_CANDIDATE_NATIVE_SHA256
    assert entry["origin"] == module.CANDIDATE_NATIVE_ORIGIN
    assert entry["sidecarSha256"] == module.PINNED_CANDIDATE_NATIVE_SIDECAR_SHA256
    assert target.read_bytes() == candidate_bytes
    with pytest.raises(module.ReconstructionError, match="reviewed candidate payload"):
        module.replace_candidate_native(source, b"tampered")
    with pytest.raises(module.ReconstructionError, match="retained code64 payload"):
        module.replace_candidate_native(source, candidate_bytes)


def test_candidate_native_authentication_rejects_tampered_sidecar_and_so(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    orig_sidecar = module.CANDIDATE_NATIVE_SIDECAR
    orig_so = module.CANDIDATE_NATIVE_SO
    sidecar_copy = tmp_path / "provenance.json"
    sidecar_copy.write_bytes(orig_sidecar.read_bytes())
    with sidecar_copy.open("r+b") as stream:
        stream.seek(-1, 2)
        stream.write(b"\x00")
    monkeypatch.setattr(module, "CANDIDATE_NATIVE_SIDECAR", sidecar_copy)
    with pytest.raises(module.ReconstructionError, match="sidecar SHA-256 changed"):
        module.authenticate_candidate_native()

    so_copy = tmp_path / "libforever_patcher_jni.so"
    so_copy.write_bytes(orig_so.read_bytes())
    with so_copy.open("r+b") as stream:
        stream.write(b"\x00")
    monkeypatch.setattr(module, "CANDIDATE_NATIVE_SIDECAR", orig_sidecar)
    monkeypatch.setattr(module, "CANDIDATE_NATIVE_SO", so_copy)
    with pytest.raises(module.ReconstructionError, match="candidate native JNI SHA-256 changed"):
        module.authenticate_candidate_native()


def test_compare_code64_payloads_admits_only_the_reviewed_candidate_native(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source = tmp_path / "source"
    assets = source / "app/src/main/assets"
    native = source / "app/src/main/jniLibs/arm64-v8a"
    marker = assets / "forever/functional-poc/provenance.json"
    marker.parent.mkdir(parents=True)
    marker.write_bytes(b"candidate marker")
    native.mkdir(parents=True)
    old_bytes, new_bytes = b"baseline jni", b"candidate jni"
    (native / "libforever_patcher_jni.so").write_bytes(new_bytes)

    target_bytes, target_manifest = module.validate_client_target_source()
    pins = {}
    for name, content in (("catalog.sqlite3.gzip", b"catalog"), ("build-config.txt", b"build"), ("cdn-config.txt", b"cdn")):
        member = f"assets/forever/client-update/{name}"
        path = tmp_path / name
        path.write_bytes(content)
        pins[member] = module.PinnedUpdateAsset(path, len(content), module.sha256_bytes(content))
        staged = assets / "forever/client-update" / name
        staged.parent.mkdir(parents=True, exist_ok=True)
        staged.write_bytes(content)
    monkeypatch.setattr(module, "PINNED_UPDATE_ASSETS", pins)
    target_member = module.CLIENT_TARGET_MEMBER
    target_path = source / module.update_asset_source_relative(target_member)
    target_path.parent.mkdir(parents=True, exist_ok=True)
    target_path.write_bytes(target_bytes)

    baseline = {
        module.FUNCTIONAL_MARKER_MEMBER: "old-marker-hash",
        "lib/arm64-v8a/libforever_patcher_jni.so": module.sha256_bytes(old_bytes),
    }
    namespace = SimpleNamespace(
        code64_payloads=dict(baseline),
        client_target_bytes=target_bytes,
        client_target_manifest=target_manifest,
    )
    with pytest.raises(module.ReconstructionError, match="hash parity failed"):
        module.compare_code64_payloads(source, namespace, dict(baseline))
    with pytest.raises(module.ReconstructionError, match="not the reviewed candidate payload"):
        module.compare_code64_payloads(source, namespace, dict(baseline), module.sha256_bytes(b"other"))

    monkeypatch.setattr(module, "PINNED_CANDIDATE_NATIVE_SHA256", module.sha256_bytes(new_bytes))
    monkeypatch.setattr(module, "PINNED_CANDIDATE_NATIVE_BYTES", len(new_bytes))
    monkeypatch.setattr(module, "PINNED_CANDIDATE_NATIVE_SIDECAR_SHA256", "fake-reviewed-sidecar")
    result = module.compare_code64_payloads(
        source, namespace, dict(baseline), module.sha256_bytes(new_bytes)
    )
    assert result["status"] == "PASS"
    assert result["readOnlyNativeChanged"] is False
    assert result["guardedPatchNativeChanged"] is True
    assert result["nativeRuntimeDriverChanged"] is False
    assert result["candidateNative"] == {
        "member": "lib/arm64-v8a/libforever_patcher_jni.so",
        "beforeSha256": module.PINNED_CODE64_NATIVE_SHA256,
        "afterSha256": module.sha256_bytes(new_bytes),
        "bytes": len(new_bytes),
        "sidecarSha256": "fake-reviewed-sidecar",
        "origin": module.CANDIDATE_NATIVE_ORIGIN,
        "capability": module.CANDIDATE_NATIVE_CAPABILITY,
    }

    (assets / "forever/client-update/build-config.txt").write_bytes(b"tampered")
    with pytest.raises(module.ReconstructionError, match="hash parity failed"):
        module.compare_code64_payloads(source, namespace, dict(baseline), module.sha256_bytes(new_bytes))
