from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).parents[1]
MANAGEMENT = ROOT / "scripts/arm64-prototype/app/updater-gate/ForeverClientManagement.kt"
UPDATE_SERVICE = ROOT / "scripts/arm64-prototype/app/updater-gate/ForeverClientUpdateService.kt"
FOREGROUND_SERVICE = ROOT / "scripts/arm64-prototype/app/updater-gate/ForeverClientUpdateForegroundService.kt"


def _text() -> str:
    return MANAGEMENT.read_text(encoding="utf-8")


def test_opening_dialog_performs_only_local_inspection() -> None:
    # Acceptance (preparation-reuse plan): opening the manager must not consult
    # remote versions. The old combined detect() flow is gone.
    src = _text()
    assert "if (!execution.busy.value && execution.pendingPlan == null && missingHome == null) inspectLocal()" in src
    assert "detect()" not in src
    local_block = src[src.index("fun inspectLocal()") : src.index("fun checkForUpdates()")]
    assert "service.inspect()" in local_block
    assert "checkVersions" not in local_block
    for anchor in ("LaunchedEffect(Unit)",):
        first = src.index(anchor)
        window = src[first : first + 600]
        assert "checkVersions" not in window
        assert "preparePublishedVersion" not in window
        assert "startUpdate" not in window


def test_remote_check_is_explicit_and_keeps_local_state_on_failure() -> None:
    # A manual check still refreshes the local inspection first, so a network
    # failure surfaces a short error without hiding an installed client.
    src = _text()
    block = src[src.index("fun checkForUpdates()") : src.index("LaunchedEffect(Unit)")]
    assert "service.checkVersions()" in block
    assert block.count("inspection = local") >= 3
    assert "throw failure" in block
    assert "network versions unavailable" in block


def test_play_available_with_installed_client_without_known_remote() -> None:
    # Jugar is offered whenever an installed version exists, even with no
    # published fingerprint (fresh open or failed check). The remote check is
    # a separate explicit button, not a gate.
    src = _text()
    assert "Buscar actualizaciones" in src
    branch = src[src.index("} else if (installed != null) {") :]
    branch = branch[: branch.index("} else if (latest != null) {")]
    assert 'Text("Jugar")' in branch
    assert "onPlay" in branch


def test_unversioned_present_client_can_continue_without_a_separate_remote_check() -> None:
    src = _text()
    branch = src[src.index("} else if (latest != null) {") :]
    branch = branch[: branch.index("} else {", branch.index("local.clientPresent && installed == null"))]
    assert "local.clientPresent && installed == null" in branch
    assert 'Text("Continuar")' in branch
    assert "onClick = ::continueInstallation" in branch
    assert "Buscar actualizaciones" not in branch


def test_concrete_destination_path_is_shown_before_review_and_consent() -> None:
    src = _text()
    assert "val destinationPath: String? = null" in (ROOT / "scripts/arm64-prototype/app/updater-gate/ForeverClientUpdateService.kt").read_text(encoding="utf-8")
    assert "destinationPath = target?.root?.absolutePath" in (ROOT / "scripts/arm64-prototype/app/updater-gate/ForeverClientUpdateService.kt").read_text(encoding="utf-8")
    assert 'Text("Ruta: ${current.destinationPath?.takeIf { it.isNotBlank() } ?: "No disponible"}")' in src
    portable_summary = src[src.index("if (portable != null)") : src.index("} else if (readyPlan != null)")]
    plan_summary = src[src.index("} else if (readyPlan != null)") : src.index("} else if (inline && execution.resumePlan")]
    assert 'Text("Destino: ${portable.candidate.destinationPath.takeIf { it.isNotBlank() } ?: "No disponible"}")' in portable_summary
    assert 'Text("Versión: ${portable.candidate.admission.target.version}")' in portable_summary
    assert 'Text("Descarga prevista, incluidos archivos sueltos: hasta ' in portable_summary
    assert 'Text("Espacio adicional previsto: ' in portable_summary
    assert 'Text("Destino: ${readyPlan.destinationPath.takeIf { it.isNotBlank() } ?: "No disponible"}")' in plan_summary
    assert "local?.destinationPath" not in portable_summary
    assert "local?.destinationPath" not in plan_summary


def test_partial_adoption_uses_one_review_bound_confirmation() -> None:
    src = _text()
    adoption = src[src.index("fun adoptPortableDownload(") : src.index("fun recoverPortableDownload(")]
    assert "service.confirmPartialPortabilityAdoption(" in adoption
    assert "val computed = service.plan(" in adoption
    assert "plan = computed" in adoption
    assert "handoff(computed, authorization)" in adoption
    assert "computed.snapshotHash == adoption.verification.review.snapshotHash" in adoption
    assert "computed.looseChangePaths == costs.looseChangePaths" in adoption
    for incomparable in (
        "computed.reusableObjects == costs.reusableObjects",
        "computed.missingObjects == costs.missingObjects",
        "computed.cascNewSegmentBytes == costs.cascNewSegmentBytes",
        "computed.cascIndexBytes == costs.cascIndexBytes",
        "computed.cascNewSegmentCount == costs.cascNewSegmentCount",
        "computed.transactionFileCount == costs.transactionFileCount",
    ):
        assert incomparable not in adoption
    assert "El plan actualizado ha cambiado o no hay espacio suficiente." in adoption
    assert "Al confirmar se reutilizarán los datos localizados; cada objeto se verifica al usarlo y, si el plan actualizado se mantiene dentro de estos límites, comenzará la descarga." in src
    plan_summary = src[src.index("} else if (readyPlan != null)") : src.index("} else if (inline && execution.resumePlan")]
    assert "planNotice?.let { Text(it) }" in plan_summary
    assert "onClick = { beginDownload(readyPlan) }" in plan_summary


def test_portability_authorization_stays_internal_and_rechecks_review_snapshot() -> None:
    service = UPDATE_SERVICE.read_text(encoding="utf-8")
    foreground = FOREGROUND_SERVICE.read_text(encoding="utf-8")
    public_execute = service[service.index("    fun execute(") : service.index("    /** Internal adoption continuation")]
    assert "PortabilityContinuationAuthorization" not in public_execute
    assert "internal fun executeWithPortabilityAuthorization(" in service
    assert "authorization.snapshotHash == plan.snapshotHash" in service
    assert "val snapshotHash: String" in service
    assert "@Volatile internal var pendingPortabilityAuthorization" in foreground
    assert "fun resetFor(plan: ForeverClientUpdatePlan)" in foreground
    assert "internal fun resetForWithPortabilityAuthorization(" in foreground


def test_continue_routes_partial_staging_to_review_and_blocks_ambiguous_state() -> None:
    src = _text()
    for name, end in (("startUpdate", "continueInstallation"), ("continueInstallation", "adoptPortableDownload")):
        block = src[src.index(f"fun {name}(") : src.index(f"fun {end}(")]
        assert "service.discoverPartialPortabilitySource()" in block
        assert block.index("PortabilitySourceKind.PARTIAL_CANDIDATE") < block.index("reviewPartial(service, target)")
        assert block.index("reviewPartial(service, target)") < block.index("service.preparePublishedVersion(")
        assert "PortabilitySourceKind.UNREADABLE_OR_AMBIGUOUS" in block
        assert "return@runOperation" in block


def test_update_planning_stays_behind_explicit_confirmed_action() -> None:
    # Metadata consent + planning only run from the explicit update buttons;
    # play-with-installed never triggers them.
    src = _text()
    assert src.count("startUpdate(latest)") == 2
    assert "fun startUpdate(target: ForeverClientUpdateCheck.Fingerprint)" in src
    assert "service.preparePublishedVersion(" in src
