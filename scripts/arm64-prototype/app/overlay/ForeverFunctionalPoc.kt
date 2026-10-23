package app.gamenative

import android.content.Context
import android.view.Window
import com.winlator.container.Container
import com.winlator.contents.ContentsManager
import com.winlator.core.KeyValueSet
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * The intentionally unmanaged GUI path for the real external WoW client.
 *
 * This object does not consult the managed run store, patch authentication, inspect
 * other UIDs, or accept executable/argument values from an Intent. Its operation
 * lease uses bounded app-UID runtime observation to protect the selected client;
 * the PoC otherwise configures the existing custom-game container and records
 * its own bounded events.
 */
object ForeverFunctionalPoc {
    const val APP_ID = "CUSTOM_GAME_1368588668"
    const val GAME_FOLDER_NAME = "WoW Forever"
    const val EXECUTABLE_NAME = "WowB-ARM64.exe"
    const val WINDOWS_EXECUTABLE = "A:\\WowB-ARM64.exe"
    const val EXEC_ARGS_TEMPLATE = "-config BetaFunctionalPoc-<profileId>.wtf -d3d11"
    const val ROUTE = "functional-wow-aa64"

    private const val PANVK_ASSET = "forever/panvk-wsi/libvulkan_panfrost.so"
    private const val PANVK_NAME = "libvulkan_panfrost.so"
    private const val PANVK_SHA256 = "5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9"
    private const val PANVK_SIZE = 31_142_504L
    private const val LOG_DIRECTORY = "functional-poc"
    private const val LOG_BUFFER = 64 * 1024

    /** Bound by the parent patch-session worker after its own gate is complete. */
    @Volatile
    var patchLaunchHook: ((Context, Prepared) -> (() -> Unit))? = { context, prepared ->
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "")
        val env = EnvVars(prepared.container.envVars)
        env.put("WOWFOREVER_POC_RUN_ID", nonce)
        prepared.container.setEnvVars(env.toString())
        prepared.container.saveData()
        val statusListener = prepared.patchStatusListener
        ForeverFunctionalPocPatchSession.start(
            context,
            prepared.executable,
            prepared.logFile,
            nonce,
            prepared.codeCaptureEnabled,
            // Explicit writer gate, not a new option: the stored memory-patch
            // opt-in authorizes group-8 writes only for the Classic Forever
            // profile and never alongside capture. The default observer stays
            // read-only.
            prepared.memoryPatchEnabled &&
                prepared.profileId == FunctionalServerProfile.CLASSIC_FOREVER.id &&
                !prepared.codeCaptureEnabled,
            // Advisory-only, data-only observer: never authorizes writes.
            // Exceptions are swallowed by the session; the listener only
            // re-posts to the UI thread under its own attempt guard.
            onStatus = { snapshot -> runCatching { statusListener?.invoke(snapshot) } },
            timingOriginMs = prepared.wowStartup?.startedAtMs ?: System.nanoTime() / 1_000_000L,
            summary = prepared.patchSummary,
        )
    }

    data class Prepared(
        val container: Container,
        val gameFolder: File,
        val logFile: File,
        val profileId: String,
        val memoryPatchEnabled: Boolean,
        val executable: File,
        val codeCaptureEnabled: Boolean = false,
        val configuration: FunctionalServerProfileConfig? = null,
        val displayPlan: ForeverFunctionalDisplayFit.Plan? = null,
        val storageIdentity: String = "",
        val storageRevision: Long = 0L,
        val frozenRoot: File = gameFolder,
        val storageTarget: ForeverFunctionalClientStorage.ResolvedTarget? = null,
        @Transient var durableGuestTicket: GuestTicket? = null,
        @Transient val durableGuestStarted: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        @Transient val guestLaunchCommitted: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        @Transient val guestStartWatcher: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        @Transient val guestLaunchClosed: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        @Transient var guestReservation: ForeverFunctionalClientStorage.ProcessGuard.GuestReservation? = null,
        @Transient val releaseWatcher: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        /**
         * Ephemeral per-attempt patch-status observer (US-004 AC-12).
         * Advisory-only and data-only: never authorizes writes, never read
         * by the launch or guest guards. Not part of the frozen proof;
         * `copy()` callers that start a new attempt replace it explicitly.
         */
        @Transient val patchStatusListener: ((ForeverFunctionalPocPatchSession.PatchStatus) -> Unit)? = null,
        @Transient var wowStartup: ForeverFunctionalWowStartup? = null,
        @Transient var patchSummary: ForeverFunctionalPatchSummary? = null,
        /** Shared across Prepared copies and Activity recreation for this actual game launch. */
        @Transient val assistedEntryState: ForeverFunctionalAutologinAssistedEntryState =
            ForeverFunctionalAutologinAssistedEntryState(),
    )

    private val activeGuest = AtomicReference<Prepared?>(null)

    /** Captured once by this guest's XServer listener, never looked up per event. */
    fun currentWowStartup(): ForeverFunctionalWowStartup? = activeGuest.get()?.wowStartup

    /** Protect a recreated Activity window while the real guest retains a sensitive session. */
    fun protectActiveAssistedCredentialWindow(window: Window): Boolean =
        activeGuest.get()?.assistedEntryState?.protectSensitiveCredentialWindow(window) ?: true

    /** Called only inside quiescent, recovered guest admission, before reservation. */
    internal fun reconcileTerminatedAssistedSession(context: Context) {
        // Local release drops ProcessGuard before its main-thread privacy cleanup.
        // Do not retire that live handle's marker or let its late end hit a new one.
        check(activeGuest.get() == null) { "previous_guest_cleanup_pending" }
        check(PluviaApp.xEnvironment == null) { "previous_environment_not_closed" }
        check(ForeverAssistedCredentialPrivacy.finishOrphanedSessionAfterTermination(context.filesDir)) {
            "terminated_session_cleanup_incomplete"
        }
    }

    private val runtimeBootstrap = ForeverRuntimeBootstrap()

    fun isRoute(enabled: Boolean, customGame: Boolean, bootToContainer: Boolean, testGraphics: Boolean): Boolean =
        enabled && customGame && !bootToContainer && !testGraphics

    /** Returns the one fixed Windows target; no caller can select another PE. */
    fun command(gameFolderPath: String?, executablePath: String): String {
        require(!gameFolderPath.isNullOrBlank()) { "functional PoC requires an A: game folder" }
        require(executablePath.equals(EXECUTABLE_NAME, ignoreCase = true)) {
            "functional PoC container executable is not WowB-ARM64.exe"
        }
        // ProcessHelper retains quote characters in argv. This fixed Windows
        // path contains no spaces, so pass it without shell-style grouping.
        return WINDOWS_EXECUTABLE
    }

    /**
     * Resolve the frozen client root for launch. Updater-managed installs
     * ship Data plus loose files, never WTF/: when the original config is
     * the missing piece, provision the contract-owned original once (portal
     * and text locale only, existing keys preserved, Config.wtf untouched)
     * and retry once. The executable (and Data) is fully proved before
     * anything is written, so an arbitrary unverified root never receives
     * configuration. Any other failure, or a still-unresolved client,
     * propagates with its precise storage code.
     */
    internal fun resolveClientForLaunch(
        context: Context,
        storage: ForeverFunctionalClientStorage.Service,
        ensureOriginalConfig: (File) -> Unit = { root -> ForeverContract.writeWtf(context, root) },
    ): ForeverFunctionalClientStorage.ResolvedTarget {
        try {
            return storage.resolveForPrepare()
        } catch (failure: ForeverFunctionalClientStorage.StorageException) {
            if (failure.code != "client_incomplete") throw failure
        }
        val root = storage.provenExecutableRoot()
        ensureOriginalConfig(root)
        return storage.resolveForPrepare()
    }

    /** Configure the existing container and prepare only app-owned driver controls. */
    fun prepare(
        context: Context,
        configuration: FunctionalServerProfileConfig = ForeverFunctionalServerProfiles.resolve(
            context,
            ForeverFunctionalServerProfiles.load(context),
        ),
        progress: (String) -> Unit = {},
    ): Prepared {
        val storage = ForeverFunctionalClientStorage.service(context)
        val ioLease = storage.acquireIoLease() ?: throw IllegalStateException("storage_busy")
        try {
            val prepareStart = System.nanoTime()
            val resolved = resolveClientForLaunch(context, storage)
            val resolveMs = elapsedMs(prepareStart)
            val gameFolder = resolved.root.canonicalFile
            val executable = File(gameFolder, EXECUTABLE_NAME)
            val log = newLogFile(context)
            // A cancelled generation may have returned while stock ImageFS
            // migration/extraction is still owned by the bootstrap. Join it
            // before importing runtime profiles against the same shared roots.
            runBlocking { runtimeBootstrap.awaitPending() }
            progress("PoC: resolving existing ARM64EC Wine/DXVK profiles")
            val runtimeStart = System.nanoTime()
            val profiles = ForeverRuntimeSetup.ensure(context, progress)
            val runtimeMs = elapsedMs(runtimeStart)
            // First-run bootstrap for the fixed functional client: a missing
            // container is created through the established custom-game flow
            // only after the registration guard proves it denotes this exact
            // root. A: mapping is still validated by the strict gate inside
            // ensureContainer; the storage lease prevents a concurrent
            // Activity from committing a different selection. The intermediate
            // frozen-target checks below skip the full client-tree traversal
            // (already proved at resolve, re-proved at beforeLaunch); the
            // lease held for this whole operation keeps the anti-race guards.
            progress("PoC: ensuring existing container…")
            val containerStart = System.nanoTime()
            val container = storage.ensureContainerPrepared(resolved)
            require(container.id == APP_ID) { "custom-game container identity changed" }
            // The stock ImageFS bootstrap resolves the Proton symlink and
            // legacy migration from the container's Wine version, exactly as
            // the managed launch flow configures it before bootstrapping.
            // Set both pinned selectors here; the remaining Wine/DXVK
            // configuration below needs the ImageFS hooks directory, so it
            // follows after the bootstrap proves the ImageFS present.
            val pinnedWine = ContentsManager.getEntryName(profiles.wine)
            if (container.wineVersion != pinnedWine || container.containerVariant != Container.BIONIC) {
                container.setWineVersion(pinnedWine)
                container.setContainerVariant(Container.BIONIC)
                container.saveData()
            }
            val containerMs = elapsedMs(containerStart)
            if (configuration.codeCaptureEnabled) {
                ForeverFunctionalPocPatchSession.preflightCapture(context, executable, log)
            }
            // Preserve the original configuration. Derived profile files are private
            // PoC files; neither Config.wtf nor BetaSuspendedTest.wtf is ever written.
            // Once a derived file exists it becomes the writable source: later
            // preparations reuse it so graphics choices saved inside the game
            // survive. Only a missing derived file is built from the read-only
            // original with the one-time initial preset.
            val derivedStart = System.nanoTime()
            storage.revalidatePrepared(resolved)
            val originalConfig = File(gameFolder, "WTF/BetaSuspendedTest.wtf")
            val originalText = originalConfig.readText(Charsets.UTF_8)
            val derivedFile = File(gameFolder, "WTF/${configuration.configFileName}")
            val derivedExists = try {
                Files.isRegularFile(derivedFile.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(derivedFile.toPath())
            } catch (_: Throwable) {
                false
            }
            val expectedDerived = if (derivedExists) {
                ForeverFunctionalServerProfiles.mergeDerivedPreservingGraphics(
                    ForeverFunctionalServerProfiles.deriveConfig(
                        derivedFile.readText(Charsets.UTF_8),
                        configuration,
                    ),
                    null,
                )
            } else {
                ForeverFunctionalServerProfiles.applyPocOverrides(
                    ForeverFunctionalServerProfiles.deriveConfig(originalText, configuration),
                    null,
                )
            }
            val (derivedConfig, derivedReused) =
                writeDerivedConfigIfUnchanged(gameFolder, configuration.configFileName, expectedDerived)
            val derivedMs = elapsedMs(derivedStart)

        progress("PoC: preparing private PanVK/noGL controls")
        val controlsStart = System.nanoTime()
        val panvk = materializePanvk(context)
        val noGl = ForeverNoOpenGL.prepare(context)
        val controlsMs = elapsedMs(controlsStart)
        progress("PoC: ensuring Bionic ImageFS…")
        // Established stock bootstrap with its own visible progress
        // (download/install share of the ~175 MiB Bionic archive on first
        // run, instant when already valid). No new downloader, URL, or
        // contract: this is the exact owned flow, joined above when pending.
        val imageFsStart = System.nanoTime()
        runBlocking { runtimeBootstrap.ensure(context, container, progress) }
        val imageFs = ImageFs.find(context)
        val hooks = File(imageFs.rootDir, "usr/lib").canonicalFile
        require(Files.isDirectory(hooks.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "existing ImageFS hook directory is missing"
        }
        val imageFsMs = elapsedMs(imageFsStart)

        val finalizeStart = System.nanoTime()
        container.setExecutablePath(EXECUTABLE_NAME)
        val execArgs = "-config ${derivedConfig.name} -d3d11"
        container.setExecArgs(execArgs)
        container.setWineVersion(ContentsManager.getEntryName(profiles.wine))
        container.setDXWrapper("dxvk-${profiles.dxvk.verName}")
        container.setDXWrapperConfig("version=${profiles.dxvk.verName},framerate=0,maxDeviceMemory=0,async=0,asyncCache=0")
        container.setEmulator("FEXCore")
        container.setContainerVariant(Container.BIONIC)
        container.setGraphicsDriver("wrapper")
        container.setGraphicsDriverVersion("System")
        // Isolate wrapper BC handling from PanVK's independently logged BC
        // emulation. Keep compute disabled and the resource/cache settings fixed.
        val graphics = KeyValueSet(container.graphicsDriverConfig)
        graphics.put("bcnEmulationType", "software")
        graphics.put("bcnEmulation", "none")
        graphics.put("bcnEmulationCache", "0")
        // Isolate wrapper image sharing from BC decoding; no driver change.
        graphics.put("resourceType", "ahb")
        container.setGraphicsDriverConfig(graphics.toString())
        container.setDisplayRenderer("vulkan")
        container.setAudioDriver("pulseaudio")
        container.setStartupSelection(Container.STARTUP_SELECTION_ESSENTIAL)
        // This affects only the drawn virtual gamepad.  XServerScreen's physical
        // ControllerManager/WinHandler path remains enabled for real devices.
        container.setTouchscreenMode(true)

        val env = EnvVars(container.envVars)
        env.put("WINEESYNC", "0")
        env.put("WINEDEBUG", "+timestamp,+pid,err+module,warn+module")
        env.put("WINEMU_HOST_PKG", context.packageName)
        env.put("HOST_PKG", context.packageName)
        val overrides = env.get("WINEDLLOVERRIDES").orEmpty().split(';')
            .filter { it.isNotBlank() && !it.substringBefore('=').split(',').contains("d3dcompiler_47") }
        env.put("WINEDLLOVERRIDES", (overrides + "d3dcompiler_47=b").joinToString(";"))
        env.put("DXVK_LOG_LEVEL", "info")
        env.put("DXVK_LOG_PATH", "A:\\Logs")
        env.put("ADRENOTOOLS_DRIVER_PATH", panvk.parentFile.canonicalPath + File.separator)
        env.put("ADRENOTOOLS_DRIVER_NAME", PANVK_NAME)
        env.put("ADRENOTOOLS_HOOKS_PATH", hooks.canonicalPath)
        val runtimeLibraries = listOf(
            noGl.absolutePath,
            File(imageFs.rootDir, "usr/lib").absolutePath,
            "/system/lib64",
            File(imageFs.winePath, "lib").absolutePath,
        )
        env.put("LD_LIBRARY_PATH", runtimeLibraries.distinct().joinToString(":"))
        container.setEnvVars(env.toString())

        // This flag is the exact XServerScreen branch selector.  Explicitly
        // clear the old managed bit so legacy gates and probes remain unused.
        container.putExtra("foreverFunctionalPoc", "true")
        container.putExtra("foreverManaged", "false")
        container.putExtra("foreverLaunchKind", ROUTE)
        container.saveData()
        val finalizeMs = elapsedMs(finalizeStart)

        record(log, "prepared", JSONObject()
            .put("route", ROUTE)
            .put("appId", APP_ID)
            .put("gameFolderName", gameFolder.name)
            .put("windowsExecutableName", EXECUTABLE_NAME)
            .put("arguments", execArgs)
            .put("profileId", configuration.profileId)
            .put("portalHost", configuration.portalHost)
            .put("memoryPatchEnabled", configuration.memoryPatchEnabled)
            .put("codeCaptureEnabled", configuration.codeCaptureEnabled)
            .put("patchContractId", configuration.patchContractId)
            .put("compatibleBuild", configuration.compatibleBuild)
            .put("wine", container.wineVersion)
            .put("dxvk", container.dxWrapper)
            .put("bcnEmulationType", graphics.get("bcnEmulationType"))
            .put("bcnEmulation", graphics.get("bcnEmulation"))
            .put("bcnEmulationCache", graphics.get("bcnEmulationCache"))
            .put("resourceType", graphics.get("resourceType"))
            .put("panvkMaterialized", panvk.isFile)
            .put("noGlPrepared", noGl.isFile)
            .put("prefixConfigured", container.rootDir != null))
        record(log, "prepare_stages", JSONObject()
            .put("resolveMs", resolveMs)
            .put("runtimeProfilesMs", runtimeMs)
            .put("containerMs", containerMs)
            .put("derivedConfigMs", derivedMs)
            .put("controlsMs", controlsMs)
            .put("imageFsMs", imageFsMs)
            .put("finalizeMs", finalizeMs)
            .put("totalMs", elapsedMs(prepareStart))
            .put("derivedReused", derivedReused))
            val prepared = Prepared(
                container = container,
                gameFolder = gameFolder,
                logFile = log,
                profileId = configuration.profileId,
                memoryPatchEnabled = configuration.memoryPatchEnabled,
                executable = executable,
                codeCaptureEnabled = configuration.codeCaptureEnabled,
                configuration = configuration,
                storageIdentity = resolved.identity.id,
                storageRevision = resolved.revision,
                frozenRoot = gameFolder,
                storageTarget = resolved,
            )
            return prepared
        } finally {
            ioLease.close()
        }
    }

    /**
     * Applies the one frozen display plan immediately before XServerScreen is
     * mounted.  Preparation may run in portrait, but this method must receive
     * a plan measured from the final landscape/root viewport.
     */
    fun applyDisplayPlan(
        context: Context,
        prepared: Prepared,
        plan: ForeverFunctionalDisplayFit.Plan,
        ioLease: ForeverFunctionalClientStorage.ProcessGuard.IoLease? = null,
    ): Prepared {
        val storage = ForeverFunctionalClientStorage.service(context)
        val ownedLease = ioLease ?: storage.acquireIoLease() ?: throw IllegalStateException("storage_busy")
        try {
            // Same-operation intermediate gates: the full client-tree
            // traversal already ran at resolve and runs again at beforeLaunch.
            prepared.storageTarget?.let { storage.revalidatePrepared(it) }
            val verified = ForeverFunctionalDisplayFit.calculate(plan.mode, plan.viewport)
            require(verified == plan) { "display plan is not a valid policy result" }
            val existing = prepared.displayPlan
            if (existing != null) {
                require(existing == plan) { "display plan is frozen for this launch" }
                return prepared
            }
            val configuration = prepared.configuration ?: ForeverFunctionalServerProfiles.resolve(
                context,
                ForeverFunctionalServerProfiles.load(context),
            )
            val originalConfig = File(prepared.gameFolder, "WTF/BetaSuspendedTest.wtf")
            require(Files.isRegularFile(originalConfig.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "read-only BetaSuspendedTest.wtf is missing"
            }
            // Revalidate immediately before the only client-root write in this phase.
            prepared.storageTarget?.let { storage.revalidatePrepared(it) }
            // Reuse the existing derived file when present so in-game graphics
            // choices survive a resolution change; only a missing derived file
            // is built from the original with the one-time initial preset.
            val derivedFile = File(prepared.gameFolder, "WTF/${configuration.configFileName}")
            val derivedUsable = try {
                Files.isRegularFile(derivedFile.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(derivedFile.toPath())
            } catch (_: Throwable) {
                false
            }
            val expectedDerived = if (derivedUsable) {
                ForeverFunctionalServerProfiles.mergeDerivedPreservingGraphics(
                    ForeverFunctionalServerProfiles.deriveConfig(
                        derivedFile.readText(Charsets.UTF_8),
                        configuration,
                    ),
                    plan,
                )
            } else {
                ForeverFunctionalServerProfiles.applyPocOverrides(
                    ForeverFunctionalServerProfiles.deriveConfig(
                        originalConfig.readText(Charsets.UTF_8),
                        configuration,
                    ),
                    plan,
                )
            }
            val (derivedConfig, _) = writeDerivedConfigIfUnchanged(
                prepared.gameFolder,
                configuration.configFileName,
                expectedDerived,
            )
            prepared.container.setScreenSize(plan.size)
            prepared.container.setExecArgs("-config ${derivedConfig.name} -d3d11")
            prepared.container.saveData()
            val details = org.json.JSONObject()
            plan.logFields(applied = true).forEach { (key, value) -> details.put(key, value) }
            record(prepared.logFile, "display_plan_applied", details)
            return prepared.copy(configuration = configuration, displayPlan = plan)
        } finally {
            if (ioLease == null) ownedLease.close()
        }
    }

    /**
     * Fail-closed idle proof for an explicit graphics reset: no in-memory
     * guest, no Wine environment, and no durable/live runtime verdict from
     * the existing operation gate. A matching stale durable record is
     * recovered by the caller (same shared recovery as explicit Play) before
     * this gate runs; anything still non-idle here retains exclusion.
     */
    private fun requireResetIdle(context: Context) {
        require(activeGuest.get() == null) { "game_running" }
        require(PluviaApp.xEnvironment == null) { "game_running" }
        require(ForeverClientRuntimeGate.forContext(context).blockingReason() == null) {
            "reset_blocked_runtime_not_idle"
        }
    }

    /**
     * Explicit graphics-only reset for the private derived file. The game must
     * be closed (no active guest); the caller holds no session. Acquires the
     * storage IO lease, reuses the existing resolve/revalidate guards, and
     * touches only the graphics-preset keys. Resolution, portal, input,
     * account, legacy and foreign lines are preserved, as are client data and
     * the read-only original. A missing derived file is created from the
     * original with the one-time initial preset instead of being marked.
     */
    fun resetDerivedGraphics(
        context: Context,
        configuration: FunctionalServerProfileConfig = ForeverFunctionalServerProfiles.resolve(
            context,
            ForeverFunctionalServerProfiles.load(context),
        ),
    ): File {
        requireResetIdle(context)
        val storage = ForeverFunctionalClientStorage.service(context)
        val ioLease = storage.acquireIoLease() ?: throw IllegalStateException("storage_busy")
        try {
            // Re-prove idleness once the lease is held: Wine may have started
            // (or the gate verdict changed) between the first check and here.
            requireResetIdle(context)
            val resolved = resolveClientForLaunch(context, storage)
            storage.revalidatePrepared(resolved)
            val gameFolder = resolved.root.canonicalFile
            require(Files.isDirectory(File(gameFolder, "WTF").toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "WTF directory is missing"
            }
            val derived = File(gameFolder, "WTF/${configuration.configFileName}")
            val derivedUsable = try {
                Files.isRegularFile(derived.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(derived.toPath())
            } catch (_: Throwable) {
                false
            }
            val reset = if (derivedUsable) {
                ForeverFunctionalServerProfiles.resetGraphicsPreset(derived.readText(Charsets.UTF_8))
            } else {
                val original = File(gameFolder, "WTF/BetaSuspendedTest.wtf")
                require(Files.isRegularFile(original.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    "read-only BetaSuspendedTest.wtf is missing"
                }
                ForeverFunctionalServerProfiles.applyPocOverrides(
                    ForeverFunctionalServerProfiles.deriveConfig(original.readText(Charsets.UTF_8), configuration),
                    null,
                )
            }
            return writeDerivedConfigIfUnchanged(gameFolder, configuration.configFileName, reset).first
        } finally {
            ioLease.close()
        }
    }

    /**
     * The only managed-to-session seam. ORIGINAL (and every disabled toggle)
     * must not even call the parent worker. A missing enabled hook is reported
     * as unavailable rather than being presented as an applied patch.
     */
    fun beforeLaunch(
        context: Context,
        prepared: Prepared,
        ioLease: ForeverFunctionalClientStorage.ProcessGuard.IoLease? = null,
    ): () -> Unit {
        require(prepared.durableGuestTicket == null && prepared.guestReservation == null) {
            "prepared_guest_operation_unresolved"
        }
        prepared.guestLaunchClosed.set(false)
        prepared.durableGuestStarted.set(false)
        prepared.guestLaunchCommitted.set(false)
        val storage = ForeverFunctionalClientStorage.service(context)
        val ownedLease = if (prepared.storageTarget != null && ioLease == null) {
            storage.acquireIoLease() ?: throw IllegalStateException("storage_busy")
        } else null
        val heldLease = ioLease ?: ownedLease
        var ticket: GuestTicket? = null
        var reservation: ForeverFunctionalClientStorage.ProcessGuard.GuestReservation? = null
        try {
            prepared.storageTarget?.let {
                requireNotNull(heldLease) { "storage IO lease required" }
                storage.revalidate(it)
                ticket = ForeverClientRuntimeGate.forContext(context).operations.beginGuest(storage.operationStorageId(it)) {
                    // An APK update/process death can leave the old durable marker
                    // without its in-memory Session. Admission already proves no
                    // old runtime/ticket remains and serializes the next launch.
                    reconcileTerminatedAssistedSession(context)
                }
                prepared.durableGuestTicket = ticket
                reservation = storage.reserveGuest(heldLease) ?: throw IllegalStateException("guest_reservation_failed")
            }
        } catch (failure: Throwable) {
            abortGuestBeforeStart(context, prepared, storage, reservation, ticket)
            ownedLease?.close()
            throw failure
        }
        prepared.guestReservation = reservation
        if (reservation != null) activeGuest.set(prepared)
        try {
            val plan = prepared.displayPlan
            prepared.wowStartup = ForeverFunctionalWowStartup(
                expectedWidth = plan?.width ?: 0,
                expectedHeight = plan?.height ?: 0,
                targetClass = EXECUTABLE_NAME,
                isCurrent = { activeGuest.get() === prepared && !prepared.guestLaunchClosed.get() },
                onReady = { elapsedMs ->
                    // Exactly one tiny scheduling task per observed startup.
                    // No JNI or file IO on the XServer/render/UI callback.
                    Thread({
                        if (prepared.wowStartup?.isActive() == true) {
                            runCatching {
                                record(prepared.logFile, "wow_start_observed", JSONObject()
                                    .put("elapsedSinceWatchStartMs", elapsedMs))
                            }
                            ForeverFunctionalPocPatchSession.tryArmFromWowStartup(prepared.logFile.name)
                        }
                    }, "forever-wow-startup").apply { isDaemon = true }.start()
                },
            )
            require(!(prepared.memoryPatchEnabled && prepared.codeCaptureEnabled)) {
                "memory patch and code capture are mutually exclusive"
            }
            prepared.patchSummary = if (prepared.memoryPatchEnabled && !prepared.codeCaptureEnabled &&
                prepared.profileId == FunctionalServerProfile.CLASSIC_FOREVER.id
            ) {
                runCatching {
                    ForeverFunctionalPatchSummary.begin(File(context.filesDir, "patch-summary.json"),
                        requireNotNull(prepared.wowStartup).startedAtMs)
                }.getOrNull()
            } else null
            val patchCancel: () -> Unit = if (!prepared.memoryPatchEnabled && !prepared.codeCaptureEnabled) {
                {}
            } else {
                val hook = patchLaunchHook
                if (hook == null) {
                    prepared.patchSummary?.notePhase(ForeverFunctionalPocPatchSession.PatchAttemptPhase.FAILED)
                    record(prepared.logFile, "patch_unavailable", JSONObject().put("message", "patch unavailable"))
                    android.util.Log.w("ForeverFunctionalPoc", "patch unavailable")
                    if (prepared.codeCaptureEnabled) throw IllegalStateException("capture_session_unavailable")
                    ({})
                } else hook(context, prepared)
            }
            ownedLease?.close()
            return {
                try {
                    prepared.wowStartup?.close()
                    patchCancel.invoke()
                } finally {
                    if (prepared.guestLaunchCommitted.get()) {
                        prepared.guestLaunchClosed.set(true)
                    } else {
                        abortGuestBeforeStart(context, prepared, storage, reservation, ticket)
                    }
                }
            }
        } catch (failure: Throwable) {
            prepared.wowStartup?.close()
            prepared.patchSummary?.notePhase(ForeverFunctionalPocPatchSession.PatchAttemptPhase.FAILED)
            abortGuestBeforeStart(context, prepared, storage, reservation, ticket)
            ownedLease?.close()
            throw failure
        }
    }

    /**
     * The lifecycle callback arms bounded background observation. The durable STARTED
     * transition is written only after XEnvironment exists and /proc reports Active or
     * Unknown; an early Quiescent result retains the RESERVED record.
     */
    fun markGuestStarted(context: Context, prepared: Prepared): Boolean {
        if (prepared.guestLaunchClosed.get()) return false
        val reservation = prepared.guestReservation ?: return false
        val storage = ForeverFunctionalClientStorage.service(context)
        if (!storage.markGuestStarted(reservation)) return false
        prepared.guestLaunchCommitted.set(true)
        val ticket = prepared.durableGuestTicket ?: return true
        if (prepared.durableGuestStarted.get()) return true
        if (!prepared.guestStartWatcher.compareAndSet(false, true)) return true

        val appContext = context.applicationContext
        try {
            Thread({
                try {
                    val runtimeGate = ForeverClientRuntimeGate.forContext(appContext)
                    val deadline = android.os.SystemClock.uptimeMillis() + 30_000L
                    while (android.os.SystemClock.uptimeMillis() < deadline &&
                        prepared.durableGuestTicket === ticket
                    ) {
                        if (PluviaApp.xEnvironment != null) {
                            when (runtimeGate.observe()) {
                                RuntimeObservation.Quiescent -> Unit
                                is RuntimeObservation.Active, is RuntimeObservation.Unknown -> {
                                    try {
                                        runtimeGate.operations.markGuestStarted(ticket)
                                        prepared.durableGuestStarted.set(true)
                                        return@Thread
                                    } catch (_: Exception) {
                                        // A changing /proc snapshot is not permission to
                                        // drop the reservation; retry only within this bound.
                                    }
                                }
                            }
                        }
                        Thread.sleep(250L)
                    }
                } finally {
                    prepared.guestStartWatcher.set(false)
                }
            }, "forever-runtime-start-observer").apply { isDaemon = true }.start()
        } catch (_: Exception) {
            prepared.guestStartWatcher.set(false)
            // Keep the local and durable reservation fail-closed if a watcher cannot start.
        }
        return true
    }

    fun rollbackGuest(context: Context, prepared: Prepared) {
        if (prepared.guestLaunchCommitted.get()) {
            prepared.guestLaunchClosed.set(true)
            val reservation = prepared.guestReservation ?: return
            val ticket = prepared.durableGuestTicket ?: return
            val storage = ForeverFunctionalClientStorage.service(context)
            val recovered = ForeverFunctionalClientStorage.ProcessGuard.recoverGuest(reservation) {
                recoverPreparedGuestTicket(context, storage, prepared, reservation, ticket) {
                    ForeverClientRuntimeGate.forContext(context).operations.recoverMatchingGuest(ticket)
                }
            }
            if (recovered) clearPreparedGuest(prepared, reservation, ticket)
            return
        }
        abortGuestBeforeStart(
            context,
            prepared,
            ForeverFunctionalClientStorage.service(context),
            prepared.guestReservation,
            prepared.durableGuestTicket,
        )
    }

    /** Explicit recovery for the selected frozen destination; never clears updater state. */
    fun recoverPreviousGuestOperation(context: Context) {
        val storage = ForeverFunctionalClientStorage.service(context)
        val localPrepared = activeGuest.get()
        if (localPrepared != null) {
            val ticket = localPrepared.durableGuestTicket
                ?: throw IllegalStateException("guest_recovery_ticket_missing")
            val reservation = localPrepared.guestReservation
                ?: throw IllegalStateException("guest_recovery_reservation_missing")
            val recovered = ForeverFunctionalClientStorage.ProcessGuard.recoverGuest(reservation) {
                recoverPreparedGuestTicket(context, storage, localPrepared, reservation, ticket) {
                    ForeverClientRuntimeGate.forContext(context).operations.recoverMatchingGuest(ticket)
                }
            }
            if (!recovered) throw IllegalStateException("guest_recovery_not_proven")
            clearPreparedGuest(localPrepared, reservation, ticket)
            return
        }

        // A restarted host has no in-memory reservation. Only that case uses the
        // normal IO admission path before selected-storage durable recovery.
        val lease = storage.acquireIoLease() ?: throw IllegalStateException("storage_busy")
        try {
            val target = storage.resolveForUpdate()
            val storageId = storage.operationStorageId(target)
            ForeverClientRuntimeGate.forContext(context).operations.recoverGuest(storageId)
        } finally {
            lease.close()
        }
    }

    /** Fail-closed Play gate: carries the fresh verdict that keeps a launch blocked. */
    class PlayGuestBlockedException(val blockingReason: String) : IllegalStateException(blockingReason)

    /**
     * App-owned recovery of a stale guest reservation before explicit Play
     * or a graphics reset (US-004 AC-09/AC-11).
     * Only a durable guest record for the selected storage is recovered, using
     * the existing exclusion/ownership checks and a fresh quiescence proof;
     * updater reservations are never touched. Returns true when a recovery
     * completed and the caller may continue, or false when the gate was
     * already idle. Any other verdict (live or unknown
     * runtime, updater state, unreadable gate, held lock) throws
     * [PlayGuestBlockedException] and the caller stays blocked with a retry
     * path. Call on a worker thread: this performs fresh /proc and
     * durable-state IO. Must be called once per explicit user action;
     * concurrent requests are excluded by the caller's busy/coordinator
     * guard, and the file and in-memory locks serialize any residual race
     * fail-closed.
     */
    fun recoverStaleGuestBeforePlay(context: Context): Boolean {
        // Normal audio stop is asynchronous. Let this terminal launch's bounded
        // release finish before triage, without admitting or recovering anything here.
        val closing = activeGuest.get()
        if (closing != null && closing.guestLaunchClosed.get() && closing.releaseWatcher.get()) {
            val deadline = android.os.SystemClock.uptimeMillis() + 30_000L
            while (activeGuest.get() === closing && closing.releaseWatcher.get() &&
                android.os.SystemClock.uptimeMillis() < deadline
            ) Thread.sleep(100L)
        }
        val gate = ForeverClientRuntimeGate.forContext(context)
        val reason = gate.blockingReason()
        when (ForeverGuestRecoveryProof.playActionFor(reason)) {
            ForeverGuestRecoveryProof.PlayAction.PROCEED_FRESH -> return false
            ForeverGuestRecoveryProof.PlayAction.RETAIN_EXCLUSION ->
                throw PlayGuestBlockedException(reason ?: "operation_state_unreadable")
            ForeverGuestRecoveryProof.PlayAction.RECOVER_MATCHING_GUEST -> Unit
        }
        try {
            recoverPreviousGuestOperation(context)
        } catch (failure: Throwable) {
            // The raw failure stays in the caller's bounded handling; the
            // user-facing retry text follows the fresh observer verdict so a
            // live runtime and an unproven shutdown stay distinct. Anything
            // else (changed destination, foreign ticket, lock race) rethrows
            // unchanged and fail-closed: no old ticket is ever reused.
            val fresh = runCatching { gate.blockingReason() }.getOrNull()
            if (fresh == "own_runtime_active" ||
                (fresh != null && fresh.startsWith("own_runtime_unknown"))
            ) {
                throw PlayGuestBlockedException(fresh)
            }
            throw failure
        }
        return true
    }

    /** Releases only this launch's reservation after the existing environment is truly gone. */
    fun releaseGuestAfterShutdown(context: Context, prepared: Prepared) {
        val reservation = prepared.guestReservation ?: return
        val ticket = prepared.durableGuestTicket ?: return
        // Called only from a terminal close/exit/error callback: no later launch may
        // consume this Prepared's reservation, even if STARTED was never persisted.
        prepared.guestLaunchClosed.set(true)
        if (!prepared.releaseWatcher.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        Thread {
            try {
                val deadline = android.os.SystemClock.uptimeMillis() + 30_000L
                val runtimeGate = ForeverClientRuntimeGate.forContext(appContext)
                while (android.os.SystemClock.uptimeMillis() < deadline) {
                    // Environment removal is not process proof. Wait for the
                    // queued audio exit and fresh quiescence, off the UI thread.
                    if (PluviaApp.xEnvironment != null || runtimeGate.observe() !== RuntimeObservation.Quiescent) {
                        Thread.sleep(100L)
                        continue
                    }
                    val storage = ForeverFunctionalClientStorage.service(appContext)
                    val recovered = ForeverFunctionalClientStorage.ProcessGuard.recoverGuest(reservation) {
                        recoverPreparedGuestTicket(appContext, storage, prepared, reservation, ticket) {
                            if (prepared.durableGuestStarted.get()) {
                                runtimeGate.operations.finishGuest(ticket)
                            } else {
                                // Shutdown may beat the asynchronous STARTED write.
                                // Exact-ticket recovery may clear RESERVED only after
                                // this terminal callback and a Quiescent observation.
                                runtimeGate.operations.recoverMatchingGuest(ticket)
                            }
                        }
                    }
                    if (recovered) clearPreparedGuest(prepared, reservation, ticket)
                    break
                }
                // xEnvironment == null is only a teardown hint, never process-exit
                // proof. Unknown/active observation retains both guest guards.
            } finally {
                prepared.releaseWatcher.set(false)
            }
        }.apply { name = "forever-guest-release"; isDaemon = true }.start()
    }

    fun releaseActiveGuestAfterShutdown(context: Context) {
        activeGuest.get()?.let { releaseGuestAfterShutdown(context, it) }
    }

    private fun abortGuestBeforeStart(
        context: Context,
        prepared: Prepared,
        storage: ForeverFunctionalClientStorage.Service,
        reservation: ForeverFunctionalClientStorage.ProcessGuard.GuestReservation?,
        ticket: GuestTicket?,
    ) {
        prepared.guestLaunchClosed.set(true)
        if (prepared.guestLaunchCommitted.get() || PluviaApp.xEnvironment != null) return
        if (ticket != null) {
            val durable = ForeverClientRuntimeGate.forContext(context).operations
            val aborted = runCatching {
                if (reservation == null) {
                    durable.abortGuestBeforeStart(ticket)
                    true
                } else {
                    ForeverFunctionalClientStorage.ProcessGuard.recoverGuest(reservation) {
                        durable.abortGuestBeforeStart(ticket)
                        true
                    }
                }
            }.getOrDefault(false)
            if (!aborted) return
            if (!prepared.assistedEntryState.endSensitiveCredentialSessionAfterTermination()) return
            // The durable ticket was cleared first while ProcessGuard coordination
            // prevented a stale callback from releasing a newer local reservation.
            prepared.durableGuestTicket = null
            prepared.durableGuestStarted.set(false)
            prepared.guestReservation = null
            activeGuest.compareAndSet(prepared, null)
            return
        }

        if (reservation != null && storage.rollbackGuest(reservation)) {
            if (!prepared.assistedEntryState.endSensitiveCredentialSessionAfterTermination()) return
            prepared.guestReservation = null
            activeGuest.compareAndSet(prepared, null)
        }
    }

    private fun recoverPreparedGuestTicket(
        context: Context,
        storage: ForeverFunctionalClientStorage.Service,
        prepared: Prepared,
        reservation: ForeverFunctionalClientStorage.ProcessGuard.GuestReservation,
        ticket: GuestTicket,
        recoverDurableTicket: () -> Unit,
    ): Boolean {
        if (!prepared.guestLaunchClosed.get() || PluviaApp.xEnvironment != null || activeGuest.get() !== prepared ||
            prepared.guestReservation !== reservation || prepared.durableGuestTicket !== ticket
        ) return false

        val frozen = prepared.storageTarget ?: return false
        val selected = runCatching { storage.resolveForUpdate() }.getOrNull() ?: return false
        val selectedStorageMatches = selected.identity == frozen.identity &&
            selected.identity.id == prepared.storageIdentity && selected.root == frozen.root &&
            selected.root == prepared.frozenRoot.canonicalFile && selected.rootIdentity == frozen.rootIdentity &&
            selected.baseIdentity == frozen.baseIdentity && selected.mountIdentity == frozen.mountIdentity &&
            runCatching { storage.operationStorageId(selected) == ticket.storageId }.getOrDefault(false)
        val observation = ForeverClientRuntimeGate.forContext(context).observe()
        if (!ForeverGuestRecoveryProof.isComplete(
                activePreparedMatches = activeGuest.get() === prepared,
                selectedStorageMatches = selectedStorageMatches,
                durableTicketMatches = prepared.durableGuestTicket === ticket &&
                    prepared.guestReservation === reservation,
                noFutureLaunchConfirmed = prepared.guestLaunchClosed.get(),
                environmentGone = PluviaApp.xEnvironment == null,
                observation = observation,
            )
        ) return false

        recoverDurableTicket()
        return true
    }

    private fun clearPreparedGuest(
        prepared: Prepared,
        reservation: ForeverFunctionalClientStorage.ProcessGuard.GuestReservation,
        ticket: GuestTicket,
    ) {
        if (prepared.guestReservation !== reservation || prepared.durableGuestTicket !== ticket) return
        // This helper is reached only after recoverPreparedGuestTicket proves the
        // environment is gone and the runtime observation is quiescent.
        if (!prepared.assistedEntryState.endSensitiveCredentialSessionAfterTermination()) return
        prepared.durableGuestTicket = null
        prepared.guestReservation = null
        prepared.durableGuestStarted.set(false)
        prepared.guestLaunchCommitted.set(false)
        activeGuest.compareAndSet(prepared, null)
    }

    fun record(logFile: File, event: String, detail: JSONObject = JSONObject()) {
        val line = JSONObject(detail.toString())
            .put("timestampMs", System.currentTimeMillis())
            .put("event", event)
            .put("route", ROUTE)
            .toString()
        logFile.parentFile?.mkdirs()
        logFile.appendText(line + "\n", Charsets.UTF_8)
    }

    private fun newLogFile(context: Context): File {
        val directory = File(context.filesDir, LOG_DIRECTORY)
        require(!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS) || directory.isDirectory)
        directory.mkdirs()
        val file = File(directory, "run-${System.currentTimeMillis()}.jsonl")
        require(!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        return file
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000L

    /**
     * Byte-exact equal-write skip for the private derived PoC config. The
     * caller computes the expected text through the same pure pipeline as
     * [ForeverFunctionalServerProfiles.writeDerivedConfig]; when the target
     * already holds those exact bytes it is left untouched and the result
     * reports the reuse. Comparison is on UTF-8 bytes, never on decoded
     * text (invalid sequences must not compare equal via replacement), and
     * the read is bounded by the expected size plus one probe byte. Guards
     * (WTF directory, symlink refusal) match the writer, so a skipped write
     * proves the same preconditions. Never touches the original
     * BetaSuspendedTest.wtf or Config.wtf.
     */
    internal fun writeDerivedConfigIfUnchanged(
        gameFolder: File,
        fileName: String,
        expectedText: String,
    ): Pair<File, Boolean> {
        val directory = File(gameFolder, "WTF")
        require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) { "WTF directory is missing" }
        val target = File(directory, fileName)
        require(!Files.isSymbolicLink(target.toPath())) { "derived PoC config is a symlink" }
        val expected = expectedText.toByteArray(Charsets.UTF_8)
        if (Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (Files.size(target.toPath()) == expected.size.toLong()) {
                Files.newInputStream(target.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
                    val actual = ByteArray(expected.size)
                    var offset = 0
                    while (offset < actual.size) {
                        val count = input.read(actual, offset, actual.size - offset)
                        if (count < 0) break
                        offset += count
                    }
                    if (offset == actual.size && actual.contentEquals(expected) && input.read() == -1) {
                        return target to true
                    }
                }
            }
        }
        // Refuse a link at open time too; a failed comparison must never fall
        // back to a following write after the target changes.
        Files.newOutputStream(
            target.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS,
        ).use { it.write(expected) }
        return target to false
    }

    private fun materializePanvk(context: Context): File {
        val root = File(context.filesDir, "$LOG_DIRECTORY/panvk")
        require(!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS) || root.isDirectory)
        root.mkdirs()
        require(!Files.isSymbolicLink(root.toPath()))
        val target = File(root, PANVK_NAME)
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            verifyPanvk(target)
            return target.canonicalFile
        }
        val temporary = File(root, ".${PANVK_NAME}.part")
        require(!Files.exists(temporary.toPath(), LinkOption.NOFOLLOW_LINKS))
        try {
            context.assets.open(PANVK_ASSET).use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output, LOG_BUFFER) }
            }
            verifyPanvk(temporary)
            Files.move(temporary.toPath(), target.toPath())
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
        verifyPanvk(target)
        return target.canonicalFile
    }

    private fun verifyPanvk(file: File) {
        require(!Files.isSymbolicLink(file.toPath()))
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(LOG_BUFFER)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count > 0)
                size += count
                require(size <= PANVK_SIZE)
                digest.update(buffer, 0, count)
            }
        }
        require(size == PANVK_SIZE && digest.digest().joinToString("") { "%02x".format(it) } == PANVK_SHA256) {
            "private PanVK asset does not match the known Mali control"
        }
    }
}
