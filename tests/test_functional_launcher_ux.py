from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).parents[1]
OVERLAY = ROOT / "scripts/arm64-prototype/app/overlay"


def _read(name: str) -> str:
    return (OVERLAY / name).read_text(encoding="utf-8")


def test_quit_cleanup_waits_for_process_proof_and_play_waits_for_its_closing_attempt() -> None:
    poc = _read("ForeverFunctionalPoc.kt")
    recovery = poc[poc.index("fun recoverStaleGuestBeforePlay"):poc.index("fun releaseActiveGuestAfterShutdown")]
    assert "closing.guestLaunchClosed.get() && closing.releaseWatcher.get()" in recovery
    assert "activeGuest.get() === closing" in recovery
    assert recovery.index("closing.releaseWatcher.get()") < recovery.index("gate.blockingReason()")
    shutdown = poc[poc.index("fun releaseGuestAfterShutdown"):poc.index("private fun clearPreparedGuest")]
    assert "uptimeMillis() + 30_000L" in shutdown
    assert "PluviaApp.xEnvironment != null || runtimeGate.observe() !== RuntimeObservation.Quiescent" in shutdown
    assert shutdown.index("runtimeGate.observe()") < shutdown.index("ProcessGuard.recoverGuest(")
    assert "recoverMatchingGuest(ticket)" in shutdown
    assert "finishGuest(ticket)" in shutdown


def test_welcome_requires_explicit_launch_and_separates_diagnostics() -> None:
    activity = _read("ForeverFunctionalPocActivity.kt")

    assert '"WoW Forever"' in activity
    assert "Servidor: ${settings.profile.displayName}" in activity
    assert "onClick = onKeyboardRequested" in activity
    assert 'Text("Lanzar WoW")' in activity
    assert "showSettings" in activity
    assert 'if (showAdvancedSettings)' in activity
    assert 'Text(" Ajustes")' in activity
    # The game is entered only from the explicit action, not from composition.
    assert "running = true" in activity
    assert "onRunningChanged(true)" in activity
    assert "ForeverFunctionalPoc.beforeLaunch" in activity


def test_home_state_derivation_drives_one_truthful_status_and_cta() -> None:
    # UX simplification (2026-10-19, approved): home observes the same-process
    # download hub and derives one state; the download dominates missing files,
    # the hardcoded build is never shown as the installed version, and no
    # unverified-ready claim is made.
    activity = _read("ForeverFunctionalPocActivity.kt")
    ux = _read("ForeverFunctionalLauncherUx.kt")

    assert "fun deriveHomeState" in ux
    assert "sealed interface HomeState" in ux
    assert "homeState = ForeverFunctionalLauncherUx.deriveHomeState(" in activity
    assert "ForeverClientUpdateExecution" in activity
    assert ".detail.collectAsState()" in activity
    assert "downloadBusy" in activity
    assert 'Text("Ver descarga")' in activity
    assert '"Pausar"' in activity
    assert '"Aplicando…"' in activity
    assert 'versión ${ForeverFunctionalLauncherUx.CLIENT_BUILD}' not in activity
    # Repair pass: the download dominates even the initial snapshot; unknown
    # storage fails closed; the launch panel is prepared-proof gated (Ready or
    # presence-only Incomplete with known/available storage and no download),
    # never a metadata integrity claim.
    assert "storageKnown = storageKnown" in activity
    assert "storageKnown = true" in activity
    assert "storageKnown = false" in activity
    assert "if (downloadBusy) {\n            prepared = null" in activity
    assert "prepared != null && !launchRequested && !downloadBusy && !graphicsResetBusy && storageKnown && readiness.storageAvailable" in activity
    assert "HomeState.Ready" in activity
    assert "HomeState.Incomplete" in activity
    assert "!downloadBusy && (busy || error != null" in activity
    assert "fun deriveHomeState(" in ux
    assert "storageKnown: Boolean" in ux
    assert "downloadBusy -> HomeState.Downloading" in ux
    assert "data object Incomplete" in ux
    assert "Incomplete(verified" not in ux
    assert "in 0.." in (ROOT / "scripts/arm64-prototype/app/overlay/ForeverFunctionalUiText.kt").read_text(encoding="utf-8")
    # The old home-only contradictory suffix is gone (the per-location dialog
    # keeps its existing honest wording, which is out of scope).
    assert "homeReadinessLabel" not in activity
    assert "Cliente y Data presentes" not in activity
    assert "ForeverFunctionalUiText.downloadStatusText" in activity
    assert "ForeverFunctionalUiText.downloadFraction" in activity
    assert "ForeverFunctionalUiText.userMessage" in activity
    assert "error = failure.message" not in activity
    # Cancellation propagates as cancellation, never as a user error.
    assert "catch (cancelled: CancellationException)" in activity
    # The manager "Jugar" path shares the single prepare-then-consent route.
    assert "prepareEnvironment(fromManager = true)" in activity
    assert "consent = true" not in activity


def test_prepared_proof_reveals_launch_panel_without_claiming_integrity() -> None:
    # Regression (managed 70450): prepare() succeeds while metadata inspection
    # deliberately stays PRESENT_NOT_VERIFIED (homeState Incomplete, never
    # Ready). The launch panel/consent/"Lanzar WoW" must appear on the frozen
    # prepared proof with known/available storage and no download; Checking,
    # Downloading, StorageUnavailable and Missing must stay blocked. Static
    # host tripwire: it pins the gate shape, not Compose runtime behavior.
    activity = _read("ForeverFunctionalPocActivity.kt")
    ux = _read("ForeverFunctionalLauncherUx.kt")

    gate = activity.split('Text("Lanzar WoW")', 1)[0].rsplit("if (prepared != null", 1)[1]
    gate = gate.split(") {", 1)[0]
    gate = "if (prepared != null" + gate
    # Positive: prepared proof plus fail-closed exclusions.
    assert "prepared != null" in gate
    assert "!launchRequested" in gate
    assert "!downloadBusy" in gate
    assert "storageKnown" in gate
    assert "readiness.storageAvailable" in gate
    assert "HomeState.Ready" in gate
    assert "HomeState.Incomplete" in gate
    # Negative: no prepared, unavailable storage, or download still blocked.
    assert "Checking" not in gate
    assert "Downloading" not in gate
    assert "StorageUnavailable" not in gate
    assert "Missing" not in gate
    # Readiness semantics untouched: presence-only metadata still maps
    # PRESENT_NOT_VERIFIED to Incomplete, never Ready.
    assert "!readiness.ready -> HomeState.Incomplete" in ux
    # No integrity claim in the launch panel; consent and frozen revalidation
    # stay authoritative; nothing auto-launches.
    panel = activity.split('ForeverSectionHeader("Listo para iniciar")', 1)[1].split(
        'Text("Lanzar WoW")', 1
    )[0]
    assert "verificad" not in panel.lower()
    assert "integridad" not in panel.lower()
    assert "Checkbox(checked = consent" in panel
    assert "enabled = consent" in activity
    assert "consent = true" not in activity
    assert "consent = false" in activity
    assert "ForeverFunctionalPoc.beforeLaunch" in activity
    assert "launchRequested = true" in activity


def test_play_requests_local_preparation_without_remote_manager_or_autolaunch() -> None:
    activity = _read("ForeverFunctionalPocActivity.kt")
    home = activity.split("is ForeverFunctionalLauncherUx.HomeState.Incomplete -> {")[-1].split(
        "if (launchRequested)", 1
    )[0]
    assert "if (readiness.canRequestPlay)" in home
    assert "prepareEnvironment(fromManager = false)" in home
    assert 'Text("Preparar WoW")' not in home
    prepare = activity.split("fun prepareEnvironment(fromManager: Boolean) {", 1)[1].split(
        "fun cancelDisplayLaunch()", 1
    )[0]
    assert "if (busy || running || launchRequested || pendingDisplayIo || storageBusy || downloadBusy || graphicsResetBusy) return" in prepare
    assert "consent = false" in prepare
    assert "launchRequested = true" not in prepare
    assert "running = true" not in prepare


def test_pasted_client_review_acquires_lease_only_inside_launched_io() -> None:
    activity = _read("ForeverFunctionalPocActivity.kt")
    review = activity.split("fun reviewPastedClient() {", 1)[1].split(
        "fun returnToStorageAfterPastedAction()", 1
    )[0]
    # A cancelled scope may never enter launch: acquiring beforehand leaks the
    # process-wide guard and blocks all later storage/download/game actions.
    assert review.index("scope.launch") < review.index("withContext(Dispatchers.IO)")
    assert review.index("withContext(Dispatchers.IO)") < review.index("acquireIoLease()")
    assert "finally {\n                        lease.close()" in review


def test_home_refresh_preserves_the_active_guest_release_handle() -> None:
    activity = _read("ForeverFunctionalPocActivity.kt")
    refresh = activity.split("fun refreshReadiness() {", 1)[1].split(
        "// Returning to the home surface", 1
    )[0]
    # prepared is also used by XServer's exit callbacks to release the guest:
    # guard both entry and IO completion/failure, not just a captured idle flag.
    guard = "if (running || launchRequested || pendingDisplayIo || busy) return"
    assert refresh.count(guard) == 3
    assert refresh.index(guard) < refresh.index("scope.launch")


def test_server_profile_is_build_time_and_gpu_card_lives_in_settings() -> None:
    # UX simplification (2026-10-19, approved): no profile radio choice in the
    # APK (build-time Classic Forever wins over stored prefs); the GPU card
    # moved out of home into its own Ajustes dialog.
    activity = _read("ForeverFunctionalPocActivity.kt")
    profiles = _read("ForeverFunctionalServerProfiles.kt")

    assert "EFFECTIVE_PROFILE" in profiles
    assert "BuildConfig.FOREVER_SERVER_PROFILE" in profiles
    assert "FunctionalServerProfile.values().forEach" not in activity
    assert "Perfil de servidor" in activity
    assert "fijado por la compilación" in activity
    assert "showGpuCompatibility" in activity
    assert 'label = "Compatibilidad gráfica"' in activity
    assert "ForeverFunctionalGpuCompatibilityCard(" in activity


def test_settings_actions_share_a_left_aligned_grid_without_per_button_margins() -> None:
    activity = _read("ForeverFunctionalPocActivity.kt")
    action = activity.split("private fun ForeverSettingsAction(", 1)[1].split("@Composable", 1)[0]
    settings = activity.split("if (showSettings) {", 1)[1].split("if (showAdvancedSettings)", 1)[0]
    assert settings.count("ForeverSettingsAction(") == 6
    assert '"Pantalla / resolución: ${displayMode.label}"' in settings
    assert '"Restablecer gráficos"' in settings
    assert "verticalArrangement = Arrangement.spacedBy(12.dp)" in settings
    assert ".padding(top = 8.dp)" not in settings
    assert "Modifier.fillMaxWidth().heightIn(min = 64.dp)" in action
    assert "PaddingValues(horizontal = 16.dp, vertical = 12.dp)" in action
    assert "horizontalArrangement = Arrangement.spacedBy(12.dp)" in action
    assert "Modifier.size(20.dp)" in action
    assert "Text(label, modifier = Modifier.weight(1f))" in action
    assert "contentDescription = null" in action


def test_launcher_polish_is_compose_only_and_keeps_primary_flow_ordered() -> None:
    activity = _read("ForeverFunctionalPocActivity.kt")

    assert "private fun ForeverLauncherTheme" in activity
    assert "darkColorScheme" in activity
    assert "FontFamily.Serif" in activity
    assert "FontFamily.SansSerif" in activity
    assert "private fun ForeverStonePanel" in activity
    assert "ForeverBurgundy" in activity
    assert "ForeverGold" in activity
    assert "windowInsetsPadding(WindowInsets.systemBars)" in activity
    assert ".imePadding()" in activity
    assert ".verticalScroll(rememberScrollState())" in activity
    assert ".heightIn(min = 48.dp)" in activity
    # UX simplification (2026-10-19, approved): the home CTA is now a single
    # state-driven action (deriveHomeState), not the unconditional
    # `} else if (prepared == null) {` prepare branch. Play now requests local
    # preparation directly without a separate "Preparar WoW" action.
    assert "} else when (val state = homeState) {" in activity
    assert "} else if (prepared == null) {" not in activity
    assert 'Text("Preparar WoW")' not in activity
    assert 'Text("Jugar")' in activity
    assert 'Text("Lanzar WoW")' in activity
    assert 'Text("Descargar cliente")' in activity
    assert 'Text(if (readiness.canRequestPlay) "Jugar" else "Completa la instalación")' in activity
    assert 'Text("Elegir almacenamiento")' in activity


def test_launcher_polish_does_not_add_proprietary_assets_or_claim_official_design() -> None:
    activity = _read("ForeverFunctionalPocActivity.kt")

    assert "painterResource" not in activity
    assert "Font(R.font" not in activity
    assert "downloaded" not in activity.lower()


def test_game_presentation_uses_existing_immersive_and_ime_seams() -> None:
    activity = _read("ForeverFunctionalPocActivity.kt")
    ux = _read("ForeverFunctionalLauncherUx.kt")

    assert "AppUtils.hideSystemUI(activity, true)" in ux
    assert "AppUtils.hideSystemUI(activity, false)" in ux
    assert "WindowInsetsCompat.Type.ime()" in ux
    assert "triggerShowKeyboard()" in ux
    assert "Icons.Default.Keyboard" in activity
    assert 'contentDescription = "Abrir teclado"' in activity
    assert 'android:windowSoftInputMode="adjustNothing"' in (
        ROOT / "scripts/arm64-prototype/app/apply_functional_poc.py"
    ).read_text(encoding="utf-8")
    assert 'android:screenOrientation="portrait"' in (
        ROOT / "scripts/arm64-prototype/app/apply_functional_poc.py"
    ).read_text(encoding="utf-8")
    assert "imeRequestPending" in activity
    assert "syncGamePresentation(imeVisibleOverride = imeVisible)" in activity
    assert "syncGamePresentation()" in activity
    assert "installTouchscreenGamepadSuppression" in ux
    assert "delay(250)" not in activity


def test_touchscreen_overlay_is_hidden_without_disabling_physical_controller_path() -> None:
    activity = _read("ForeverFunctionalPocActivity.kt")
    ux = _read("ForeverFunctionalLauncherUx.kt")
    poc = _read("ForeverFunctionalPoc.kt")

    assert "ControllerManager.getInstance().init(applicationContext)" in activity
    assert "setShowTouchscreenControls(false)" in ux
    assert "refreshControllerMappingsForHotplug()" in ux
    assert "hideProfileForOverlay()" in ux
    assert "container.setTouchscreenMode(true)" in poc
    assert "ControllerManager" not in ux or "disable" not in ux.lower()


def test_aspect_ratio_is_not_overridden_by_the_launcher_preset() -> None:
    profiles = _read("ForeverFunctionalServerProfiles.kt")
    activity = _read("ForeverFunctionalPocActivity.kt")
    poc = _read("ForeverFunctionalPoc.kt")

    # Resolution is now owned only by the measured display plan, not by a
    # portrait preparation pass or a hardcoded 16:9 preset.
    assert "displayPlan: ForeverFunctionalDisplayFit.Plan? = null" in profiles
    assert 'always["gxResolution"] = displayPlan.size' in profiles
    assert 'always["gxWindowedResolution"] = displayPlan.size' in profiles
    assert 'always["gxFullscreenResolution"] = displayPlan.size' in profiles
    assert '"gxWindow"' not in profiles
    assert '"graphicsQuality" to "0"' in profiles
    # The graphics reset is bidirectionally excluded with every session and
    # storage gate, and fails closed on any live/durable runtime verdict. A
    # matching stale guest is recovered with the same checks as Play before
    # the write (US-004 AC-11/FR-016); active/unknown/updater state and a
    # failed recovery prevent the write.
    assert "|| graphicsResetBusy" in activity
    assert "enabled = consent && !graphicsResetBusy" in activity
    assert "private fun requireResetIdle(context: Context)" in poc
    assert "blockingReason() == null" in poc
    assert '"reset_blocked_runtime_not_idle"' in poc
    assert "PluviaApp.xEnvironment == null" in poc
    assert "requireResetIdle(context)" in poc
    reset = activity.split("fun resetGraphicsToDefaults()", 1)[1].split(
        "Single launch path shared by the home CTA", 1
    )[0]
    assert "discardIdlePreparedSnapshot()" in reset
    assert "ForeverFunctionalPoc.recoverStaleGuestBeforePlay(context)" in reset
    assert reset.index("recoverStaleGuestBeforePlay") < reset.index(
        "resetDerivedGraphics(context, configuration)"
    )


def test_display_fit_policy_and_settings_selector_are_exposed() -> None:
    fit = _read("ForeverFunctionalDisplayFit.kt")
    activity = _read("ForeverFunctionalPocActivity.kt")

    assert 'AUTO("AUTO", "Automática · 720", 720)' in fit
    assert 'PERFORMANCE("PERFORMANCE", "Resolución 540", 540)' in fit
    assert 'QUALITY("QUALITY", "Resolución 900", 900)' in fit
    assert 'NATIVE("NATIVE", "Nativa", null)' in fit
    assert "class ForeverFunctionalDisplayFitLaunchGate" in fit
    assert "class ForeverFunctionalDisplayFitLaunchCoordinator" in fit
    assert "stableFramesRequired: Int = 2" in fit
    assert "timeoutMs: Long = 5_000L" in fit
    assert 'label = "Pantalla / resolución: ${displayMode.label}"' in activity
    assert "ForeverFunctionalDisplayFit.Mode.values()" in activity
    assert "displayMode.nativeWarning" in activity
    assert "ForeverFunctionalPoc.applyDisplayPlan" in activity


def test_original_vector_ornaments_keep_explicit_panel_contrast() -> None:
    activity = _read("ForeverFunctionalPocActivity.kt")
    assert "private fun ForeverCrest()" in activity
    assert "private fun ForeverRealmBackdrop()" in activity
    assert "contentColor = MaterialTheme.colorScheme.onSurface" in activity
    assert "textButtonColors(contentColor = ForeverGold)" in activity
    assert "Original vector decoration" in activity


def test_manager_back_never_pauses_implicitly() -> None:
    # Acceptance: returning from the manager to home (or Settings) must NOT
    # implicitly pause the download. Back navigates via onDismiss; pause stays
    # explicit through the ACTION_PAUSE buttons.
    management = (
        ROOT / "scripts/arm64-prototype/app/updater-gate/ForeverClientManagement.kt"
    ).read_text(encoding="utf-8")
    back_start = management.index("BackHandler(enabled = busy || executionBusy)")
    back_block = management[back_start : back_start + 400]
    assert "onDismiss()" in back_block
    assert "ACTION_PAUSE" not in back_block
    assert "executionBusy && !executionApplying -> onDismiss()" in management


def test_account_unavailable_state_cannot_offer_a_destructive_save_as_account_save() -> None:
    account = _read("ForeverFunctionalAutologinSettings.kt")
    assert 'Text("Cuenta",' in account
    assert "Inicio automático no disponible todavía. Inicia sesión dentro de WoW." in account
    assert "enabled = runtimeReady && !busy && !loading" in account
    assert 'desiredEnabled -> "Guardar cuenta"' in account
    assert 'else -> "Desactivar y borrar"' in account
    assert "SecureFlagPolicy.SecureOn" in account
    assert "PasswordVisualTransformation()" in account


def test_patch_terminal_state_survives_prepared_clearing_without_new_cta() -> None:
    # US-004 AC-12: the terminal patch label is driven by patchStatus, not by
    # prepared, so clearing prepared (navigateBack/onExit/onGameLaunchError)
    # keeps the welcome surface truthful without adding a competing action or
    # a login policy.
    activity = _read("ForeverFunctionalPocActivity.kt")
    home = activity.split("Terminal stays visible after prepared is cleared", 1)[1].split("if (busy) {", 1)[0]
    assert "patchStatus" in home
    assert "patchAttemptLabel" in home
    assert "Button(" not in home
    assert "onClick" not in home


def test_game_overlay_shows_noninteractive_patch_state_without_viewport_changes() -> None:
    # US-004 AC-12: a small noninteractive status label lives in the existing
    # game overlay (visible before the manual login). It adds no viewport,
    # inset, input, or IME change and no login action.
    activity = _read("ForeverFunctionalPocActivity.kt")
    overlay = activity.split(
        "Advisory-only per-attempt patch state, visible before the", 1)[1].split("Surface(", 1)[0]
    assert "patchAttemptLabel" in overlay
    assert "Alignment.TopCenter" in overlay
    assert "onClick" not in overlay
    assert "Button(" not in overlay
