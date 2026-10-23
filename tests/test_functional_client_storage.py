from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).parents[1]
OVERLAY = ROOT / "scripts/arm64-prototype/app/overlay"


def _read(name: str) -> str:
    return (OVERLAY / name).read_text(encoding="utf-8")


def test_storage_selector_is_an_actual_identity_based_root_contract() -> None:
    storage = _read("ForeverFunctionalClientStorage.kt")
    poc = _read("ForeverFunctionalPoc.kt")
    assert "getExternalFilesDirs(null)" in storage
    assert "getStorageVolume" in storage
    assert 'primary:${candidate.uuid ?: "emulated"}' in storage
    assert 'CustomGames/${ForeverFunctionalPoc.GAME_FOLDER_NAME}' in storage
    assert "selected_id" in storage
    assert "SelectionCorruptException" in storage
    assert "client_selection_changed" not in storage
    assert "storage.resolveForPrepare()" in poc
    assert "storage.prepareContainer(resolved)" in poc
    assert "storageIdentity: String = \"\"" in poc
    assert "frozenRoot: File = gameFolder" in poc


def test_storage_preflight_is_fail_closed_and_does_not_hash_data() -> None:
    storage = _read("ForeverFunctionalClientStorage.kt")
    assert "MEDIA_MOUNTED" in storage
    assert "CREATE_NEW" in storage
    assert "Files.move(probe.toPath(), renamed.toPath())" in storage
    assert "ByteArray(16)" in storage
    assert "safeSegments" in storage
    assert "validateOptionalFixedNodes" in storage
    assert "EXECUTABLE_SIZE = 94_286_544L" in storage
    assert "EXECUTABLE_SHA256" in storage
    assert "WTF/BetaSuspendedTest.wtf" in storage
    assert "Files.walk" not in storage
    assert "readText()" not in storage
    assert "COMMITTING" in storage
    assert "mountIdentityFor" in storage
    assert "sha256(data" not in storage
    assert "Hash Data" not in storage


def test_ui_exposes_storage_and_account_without_implicit_installation() -> None:
    activity = _read("ForeverFunctionalPocActivity.kt")
    ux = _read("ForeverFunctionalLauncherUx.kt")
    assert 'Text("Almacenamiento"' in activity
    assert 'label = "Cuenta"' in activity
    assert "sessionOrIoLocked" in activity
    assert "enabled = !sessionOrIoLocked" in activity
    assert 'Text("Usar esta ubicación")' in activity
    assert 'Text("Buscar unidades")' in activity
    assert "No hay una unidad USB o SD disponible." in activity
    assert "clientState" not in activity
    # Home now observes the existing downloader; selecting storage still does
    # not install anything or own a download execution.
    assert "ForeverClientUpdateExecution" in activity
    assert "ForeverClientUpdateExecution" not in _read("ForeverFunctionalClientStorage.kt")
    assert "inspectClient(context: android.content.Context)" in ux
    assert "PRESENT_NOT_VERIFIED" in ux


def test_selection_leases_and_container_mapping_preserve_other_drives() -> None:
    storage = _read("ForeverFunctionalClientStorage.kt")
    activity = _read("ForeverFunctionalPocActivity.kt")
    assert "acquireSelection" in storage
    assert "GuestReservation" in storage
    assert "Owner.SELECTION" in storage
    assert '"container_a_drive_unknown"' in storage
    assert "replaceDriveA" in storage
    assert "container_duplicate_a_drive" in storage
    assert "container.saveData()" in storage
    assert "storageLease" in activity


def test_focused_kotlin_regression_tests_cover_empty_unknown_and_cancellation() -> None:
    tests = _read("ForeverFunctionalClientStorageTest.kt")
    assert "defaultSelectionUsesTheLegacyRoot" in tests
    assert "selectingAnEmptyDestination" in tests
    assert "wrongTypePreferenceFailsClosed" in tests
    assert "cancelledAsyncSelectionCannotCommit" in tests
    assert "selectionIsBlockedByProcessIoAndGuestLeases" in tests
    assert "symlinkAndForeignMarkerAreRejected" in tests
    assert "dataAndOriginalWtfLinksAreRejected" in tests
    assert "driveReplacementPreservesDirectContainerSerialization" in tests
    assert "explicitInternalVerifierCanProveAPositiveFixture" in tests
