package app.gamenative

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class ForeverDownloadPlan(
    val downloadBytes: Long,
    val workspaceBytes: Long,
    val runtimeWorkspaceNote: String,
)

data class ForeverLauncherState(
    val stage: ForeverStage? = null,
    val message: String? = null,
    val error: String? = null,
    val plan: ForeverDownloadPlan? = null,
    val runId: String? = null,
    val appId: String? = null,
    val verifiedExe: File? = null,
    val gameVisible: Boolean = false,
    val operationActive: Boolean = false,
    val blockedByUnknownProcess: Boolean = false,
    val processStarted: Boolean = false,
    val gameIdentityObserved: Boolean = false,
    val renderState: String = "unverified",
    val loginState: String = "unverified",
)

/** Application-scoped coordinator.  The Activity/Compose tree is not its lifetime owner. */
class ForeverLaunchController private constructor(context: Context) : ForeverProcessDiagnostics.Listener {
    companion object {
        private val instances = ConcurrentHashMap<String, ForeverLaunchController>()

        fun get(context: Context): ForeverLaunchController {
            val appContext = context.applicationContext
            return instances.computeIfAbsent(appContext.packageName) { ForeverLaunchController(appContext) }
        }
    }

    private val appContext = context.applicationContext
    private val store = ForeverRunStoreRegistry.get(appContext)
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtimeBootstrap = ForeverRuntimeBootstrap()
    private val generation = AtomicLong(0)
    private val recovered: ForeverRunState?
    private val _state: MutableStateFlow<ForeverLauncherState>
    val state: StateFlow<ForeverLauncherState>
        get() = _state.asStateFlow()

    private var operation: Job? = null
    private var activeRunId: String? = null
    private var activeToken: Long? = null
    private var terminalGate = false
    private var blockedByUnknownProcess = false

    init {
        ForeverProcessDiagnostics.initialize(appContext)
        // A fresh host never reclassifies an ACTIVE RUN.  The only exception is
        // the fixed Wine disposition consumed inside beginWine after a verified
        // new boot; the generic launcher path stays fail-closed.
        val recovery = runCatching { store.readCurrent() }
        recovered = recovery.getOrNull()
        blockedByUnknownProcess = recovery.isFailure || (recovered?.status == ForeverRunStatus.ACTIVE) ||
            (recovered?.let { !ForeverProcessDiagnostics.canStartNewRun(it) } ?: false)
        _state = MutableStateFlow(
            ForeverLauncherState(
                plan = loadPlan(),
                error = if (recovery.isFailure) "Metadatos de la corrida ilegibles o inválidos. Se conservan y se bloquea otro arranque; no borres datos." else null,
                message = recovered?.takeIf { it.status == ForeverRunStatus.INTERRUPTED }?.let {
                    if (blockedByUnknownProcess) "La app perdió la supervisión de la corrida. Exporta el informe y reinicia Android antes de otro intento; no borres datos."
                    else "Corrida anterior interrumpida; los recursos verificados se conservan."
                },
                blockedByUnknownProcess = blockedByUnknownProcess,
                runId = recovered?.runId,
                stage = recovered?.let { if (it.status == ForeverRunStatus.INTERRUPTED) ForeverStage.INTERRUPTED else it.stage },
            ),
        )
        ForeverProcessDiagnostics.listener = this
    }

    fun prepareAndLaunch(@Suppress("UNUSED_PARAMETER") ignoredScope: CoroutineScope) {
        synchronized(this) {
            if (operation?.isActive == true || _state.value.operationActive || _state.value.gameVisible) return
            val previous = runCatching { store.readCurrent() }.getOrElse {
                blockedByUnknownProcess = true
                _state.value = _state.value.copy(blockedByUnknownProcess = true, error = "Metadatos de la corrida inválidos; no se iniciará otro proceso.")
                return
            }
            if (blockedByUnknownProcess || !ForeverProcessDiagnostics.canStartNewRun(previous)) {
                blockedByUnknownProcess = true
                _state.value = _state.value.copy(
                    blockedByUnknownProcess = true,
                    error = "La corrida anterior tiene una terminación desconocida. Exporta el informe; reinicia Android para garantizar que no quedan procesos antes de reintentar.",
                )
                return
            }
            val token = generation.incrementAndGet()
            val started = runCatching { store.begin() }.getOrElse { failure ->
                _state.value = _state.value.copy(error = failure.message ?: "Ya existe una corrida activa")
                return
            }
            activeRunId = started.runId
            activeToken = token
            terminalGate = false
            operation = worker.launch { prepare(token, started.runId) }
        }
    }

    /** Stop is a two-phase operation: invalidate callbacks, then drain the owned process. */
    fun cancel() {
        val runId: String
        val token: Long
        var cancelledJob: Job? = null
        synchronized(this) {
            runId = activeRunId ?: return
            token = activeToken ?: return
            if (terminalGate) return
            terminalGate = true
            generation.incrementAndGet()
            ForeverProcessDiagnostics.cancelExpected(runId)
            _state.value = _state.value.copy(
                stage = ForeverStage.STOPPING,
                message = "Deteniendo la corrida propia…",
                operationActive = true,
                gameVisible = false,
            )
            runCatching { store.update(runId) { it.copy(stage = ForeverStage.STOPPING, events = it.events + "cancel_requested") } }
            cancelledJob = operation
            cancelledJob?.cancel()
        }
        worker.launch {
            cancelledJob?.join()
            val result = ForeverProcessDiagnostics.stopOwned(runId)
            finishAfterStop(token, runId, result)
        }
    }

    fun onActivityDestroyed(changingConfigurations: Boolean) {
        if (!changingConfigurations) cancel()
    }

    /**
     * Records host lifecycle without changing launch ownership.  MainActivity
     * may call this from onCreate/onPause/onResume/onStop/onDestroy; it never
     * cancels, respawns, or reattaches a process.
     */
    @Synchronized
    fun recordHostLifecycle(
        event: String,
        changingConfigurations: Boolean = false,
        isFinishing: Boolean = false,
    ) {
        val current = activeRunId ?: store.readCurrent()?.runId ?: return
        val state = store.read(current) ?: return
        append(current, JSONObject()
            .put("event", "host_lifecycle")
            .put("lifecycleEvent", event.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64))
            .put("stage", state.stage.name)
            .put("status", state.status.name)
            .put("hostPid", Process.myPid())
            .put("changingConfigurations", changingConfigurations)
            .put("isFinishing", isFinishing))
    }

    @Synchronized
    fun onGameExit(runId: String? = null) {
        if (terminalGate) return
        val token = activeToken ?: return
        val current = activeRunId ?: return
        if (runId != null && runId != current) return
        val evidence = store.read(current)
        if (evidence?.launchAttempted == true || evidence?.spawnAt != null) {
            blockedByUnknownProcess = true
            store.recordQuiescence(current, false)
        }
        append(current, JSONObject()
            .put("event", "game_exit")
            .put("source", "xserver_callback")
            .put("processRole", "wine_wrapper")
            .put("rootPid", evidence?.rootPid ?: JSONObject.NULL)
            .put("rootStartTicks", evidence?.rootStartTicks ?: JSONObject.NULL)
            .put("gamePid", evidence?.gamePid ?: JSONObject.NULL)
            .put("gameStartTicks", evidence?.gameStartTicks ?: JSONObject.NULL)
            .put("gameIdentity", if (evidence?.gamePid != null && evidence.gameStartTicks != null) "observed" else "unattributed"))
        finishCurrent(token, current, ForeverRunStatus.EXITED, "runtime_view_closed", "La vista del runtime se cerró; la terminación de Wine, el arranque de WoW y el render no quedan demostrados por este callback.")
    }

    @Synchronized
    fun onLaunchError(runId: String?, error: String) {
        if (terminalGate) return
        val current = activeRunId ?: return
        if (runId != null && runId != current) return
        val token = activeToken ?: return
        val wineStarted = store.read(current)?.spawnAt != null
        if (wineStarted) {
            blockedByUnknownProcess = true
            runCatching { store.recordQuiescence(current, false) }
        }
        ForeverProcessDiagnostics.cancelExpected(current)
        ForeverProcessDiagnostics.recordLaunchError(appContext, current, error)
        append(current, JSONObject()
            .put("event", "launcher_exit")
            .put("exitKind", if (wineStarted) "setup_failed_after_spawn" else "spawn_failed")
            .put("processRole", "wine_wrapper")
            .put("wineStarted", wineStarted)
            .put("gameIdentity", "not_observed"))
        finishCurrent(token, current, ForeverRunStatus.FAILED, error.take(2000), null)
    }

    fun export(runId: String, destination: android.net.Uri, scope: CoroutineScope) {
        scope.launch {
            store.export(runId, destination).fold(
                onSuccess = { _state.value = _state.value.copy(message = "Informe de $runId exportado.") },
                onFailure = { _state.value = _state.value.copy(error = "No se pudo exportar: ${it.message}") },
            )
        }
    }

    @Synchronized
    override fun onProcessStarted(runId: String, pid: Int?, startTicks: Long?, argv: List<String>, cwd: String?) {
        val token = activeToken ?: return
        if (runId != activeRunId || terminalGate) return
        runCatching {
            store.updateObservedProcess(runId, argv = argv, cwd = cwd, pid = pid, startTicks = startTicks, spawnAt = System.currentTimeMillis())
            store.updateObservedProcess(
                runId,
                terminationUnknown = pid == null || startTicks == null,
                rootPid = pid,
                rootStartTicks = startTicks,
            )
            store.update(runId) { it.copy(stage = ForeverStage.RUNNING, events = it.events + "process_started") }
        }
        _state.value = _state.value.copy(
            stage = ForeverStage.RUNNING,
            message = if (ForeverGraphicsProbe.isProbeArgv(argv)) {
                "Sonda gráfica iniciada; la identidad de WoW no se evalúa en este perfil."
            } else {
                "Wine iniciado; la identidad de WoW aún no está demostrada."
            },
            processStarted = true,
            gameIdentityObserved = false,
            gameVisible = true,
            operationActive = false,
        )
        // A process start is the only point at which native discovery is allowed.
        ForeverProcessDiagnostics.patchOwned(runId, token, generation.get())
    }

    @Synchronized
    override fun onGameIdentityObserved(runId: String, pid: Int, startTicks: Long) {
        if (runId != activeRunId || terminalGate) return
        val state = store.read(runId)
        if (state?.argv?.let(ForeverGraphicsProbe::isProbeArgv) == true) return
        _state.value = _state.value.copy(
            message = "Wine iniciado; identidad de WoW observada, render aún sin verificar.",
            gameIdentityObserved = true,
        )
    }

    @Synchronized
    override fun onProcessFinished(runId: String, exitCode: Int, durationMs: Long, terminationUnknown: Boolean) {
        val token = activeToken ?: return
        if (runId != activeRunId || terminalGate) return
        runCatching {
            store.updateObservedProcess(
                runId,
                exitCode = exitCode,
                durationMs = durationMs,
                terminationUnknown = terminationUnknown,
            )
        }
        if (terminationUnknown) {
            blockedByUnknownProcess = true
            _state.value = _state.value.copy(blockedByUnknownProcess = true)
        }
        val evidence = store.read(runId)
        append(runId, JSONObject()
            .put("event", "launcher_exit")
            .put("exitKind", if (terminationUnknown) "termination_unknown" else "process_exit")
            .put("processRole", "wine_wrapper")
            .put("rootPid", evidence?.rootPid ?: JSONObject.NULL)
            .put("rootStartTicks", evidence?.rootStartTicks ?: JSONObject.NULL)
            .put("gamePid", evidence?.gamePid ?: JSONObject.NULL)
            .put("gameStartTicks", evidence?.gameStartTicks ?: JSONObject.NULL)
            .put("gameIdentity", if (evidence?.gamePid != null && evidence.gameStartTicks != null) "observed" else "not_observed"))
        finishCurrent(token, runId, ForeverRunStatus.EXITED,
            if (terminationUnknown) "termination_unknown" else "process_exit",
            "Wine terminó; WoW, render y login siguen sin quedar verificados.")
    }

    override fun onProcessStartFailed(runId: String, error: String) {
        onLaunchError(runId, error)
    }

    private suspend fun prepare(token: Long, runId: String) {
        try {
            update(token, runId, ForeverStage.PREFLIGHT, "Comprobando espacio, contrato y consentimiento…")
            val provisioner = ForeverProvisionerRegistry.implementation
            val plan = _state.value.plan ?: error("client catalog is unavailable")
            require(plan.downloadBytes <= 1_073_741_824L) { "catalog exceeds the 1 GiB transfer cap" }
            checkCurrent(token, runId)

            // A previous generation may have returned after cancellation while stock ImageFS
            // migration/extraction is still owned by runtimeBootstrap. Join it before importing
            // or validating profiles against the same shared roots.
            runtimeBootstrap.awaitPending { !isCurrent(token, runId) }
            checkCurrent(token, runId)

            update(token, runId, ForeverStage.RUNTIME, "Configurando runtime ARM64 dedicado…")
            val profiles = ForeverRuntimeSetup.ensure(appContext, { progress ->
                if (isCurrent(token, runId)) _state.value = _state.value.copy(message = progress)
            }, { !isCurrent(token, runId) })
            checkCurrent(token, runId)

            update(token, runId, ForeverStage.CLIENT, "Preparando cliente WoW ARM64…")
            val clientDir = File(CustomGameScanner.defaultRootPath, "WoW Forever")
            val verified = provisioner.ensure(appContext, clientDir, { !isCurrent(token, runId) }) { progress ->
                if (isCurrent(token, runId)) _state.value = _state.value.copy(message = progress)
            }
            checkCurrent(token, runId)
            require(verified.isFile && verified.name.equals("WowB-ARM64.exe", ignoreCase = true)) {
                "provisioner returned an unexpected executable"
            }

            update(token, runId, ForeverStage.CONFIGURATION, "Escribiendo configuración Forever y prefijo dedicado…")
            val contractPath = ForeverContract.installPrivateCopy(appContext)
            ForeverContract.writeWtf(appContext, clientDir)
            val appId = registerManagedClient(clientDir)
            val container = ContainerUtils.getOrCreateContainer(appContext, appId)
            ForeverRuntimeSetup.configureContainer(container, profiles, verified, runId, hostPackage = appContext.packageName)
            val configuredProbe = if (ForeverLaunchDebug.GRAPHICS_PROBE_ENABLED) {
                ForeverGraphicsProbe.configure(appContext, container, runId)
            } else {
                container.putExtra("foreverLaunchKind", "wow_client")
                container.saveData()
                null
            }
            append(runId, JSONObject().put("event", "configuration_ready").put("appId", appId).put("verifiedExe", verified.absolutePath)
                .put("wineEntry", com.winlator.contents.ContentsManager.getEntryName(profiles.wine))
                .put("dxvkEntry", com.winlator.contents.ContentsManager.getEntryName(profiles.dxvk))
                .put("graphicsSelected", "wrapper / android-system-vulkan").put("gpuEffective", "unmeasured")
                .put("contractPath", contractPath.absolutePath)
                .put("launchKind", configuredProbe?.kind ?: "wow_client"))
            configuredProbe?.let { probe ->
                append(runId, JSONObject()
                    .put("event", "graphics_probe_configured")
                    .put("profile", probe.profile)
                    .put("verifiedExe", probe.verified.file.absolutePath)
                    .put("sha256", probe.verified.sha256)
                    .put("size", probe.verified.size)
                    .put("peMachine", "0xaa64")
                    .put("peManifestMachine", ForeverGraphicsProbe.MANIFEST_MACHINE)
                    .put("args", probe.verified.args)
                    .put("logPath", ForeverGraphicsProbe.logPath(runId)))
            }
            checkCurrent(token, runId)

            // ImageFS is a launch dependency, not a best-effort background warm-up.  Keep
            // gameVisible false until the complete stock Bionic bootstrap has been validated.
            runtimeBootstrap.ensure(appContext, container, { progress ->
                if (isCurrent(token, runId)) _state.value = _state.value.copy(message = progress)
            }, { !isCurrent(token, runId) })
            checkCurrent(token, runId)
            append(runId, JSONObject().put("event", "runtime_bootstrap_ready")
                .put("imageFsVariant", container.containerVariant)
                .put("imageFsLatest", com.winlator.xenvironment.ImageFsInstaller.LATEST_VERSION))

            val launchVerified = configuredProbe?.verified?.file ?: verified
            val launchArgs = configuredProbe?.verified?.args ?: container.execArgs
            update(token, runId, ForeverStage.LAUNCHING,
                if (configuredProbe != null) "Lanzando sonda gráfica con Wine…" else "Lanzando WowB-ARM64.exe con Wine…")
            ForeverProcessDiagnostics.expectLaunch(runId, launchVerified, contractPath.absolutePath)
            append(runId, JSONObject().put("event", "launch_route_ready")
                .put("launchKind", configuredProbe?.kind ?: "wow_client")
                .put("argv", listOf(launchVerified.name) + launchArgs.split(Regex("\\s+"))))
            synchronized(this) {
                checkCurrent(token, runId)
                _state.value = _state.value.copy(
                    stage = ForeverStage.LAUNCHING,
                    message = "Proceso solicitado; esperando evidencia de spawn…",
                    runId = runId,
                    appId = appId,
                    verifiedExe = launchVerified,
                    gameVisible = true,
                    operationActive = false,
                )
            }
        } catch (cancelled: CancellationException) {
            if (!terminalGate && isCurrent(token, runId)) finishCurrent(token, runId, ForeverRunStatus.CANCELLED, "cancelled", null)
            throw cancelled
        } catch (failure: Throwable) {
            if (isCurrent(token, runId) && !terminalGate) {
                finishCurrent(token, runId, ForeverRunStatus.FAILED, failure.message ?: failure.javaClass.simpleName, null)
            }
        } finally {
            synchronized(this) {
                if (activeRunId == runId && activeToken == token && !(_state.value.gameVisible && !terminalGate)) {
                    operation = null
                }
            }
        }
    }

    private fun finishAfterStop(token: Long, runId: String, result: ForeverProcessDiagnostics.StopResult) {
        if (result == ForeverProcessDiagnostics.StopResult.UNKNOWN) blockedByUnknownProcess = true
        if (result == ForeverProcessDiagnostics.StopResult.UNKNOWN) store.recordQuiescence(runId, false)
        val message = if (result == ForeverProcessDiagnostics.StopResult.UNKNOWN) "termination_unknown" else "cancelled_by_user"
        finishCurrent(token, runId, ForeverRunStatus.CANCELLED, message, "Cancelado; los recursos verificados se conservan.")
        _state.value = _state.value.copy(blockedByUnknownProcess = blockedByUnknownProcess)
    }

    private fun finishCurrent(token: Long, runId: String, status: ForeverRunStatus, message: String, uiMessage: String?) {
        synchronized(this) {
            if (activeRunId != runId || activeToken != token || (terminalGate && status != ForeverRunStatus.CANCELLED)) return
            terminalGate = true
            generation.incrementAndGet()
            ForeverProcessDiagnostics.cancelExpected(runId)
            runCatching { store.finish(runId, status, message) }
            activeRunId = null
            activeToken = null
            operation = null
            _state.value = _state.value.copy(
                stage = when (status) {
                    ForeverRunStatus.CANCELLED -> ForeverStage.CANCELLED
                    ForeverRunStatus.EXITED -> ForeverStage.EXITED
                    ForeverRunStatus.INTERRUPTED -> ForeverStage.INTERRUPTED
                    else -> ForeverStage.FAILED
                },
                message = uiMessage ?: _state.value.message,
                error = if (status == ForeverRunStatus.FAILED) message else _state.value.error,
                gameVisible = false,
                operationActive = false,
                processStarted = if (status == ForeverRunStatus.FAILED || status == ForeverRunStatus.CANCELLED) false else _state.value.processStarted,
                gameIdentityObserved = if (status == ForeverRunStatus.FAILED || status == ForeverRunStatus.CANCELLED) false else _state.value.gameIdentityObserved,
                runId = runId,
                blockedByUnknownProcess = blockedByUnknownProcess,
            )
        }
    }

    private fun registerManagedClient(clientDir: File): String {
        val root = File(CustomGameScanner.defaultRootPath).canonicalFile
        val canonical = clientDir.canonicalFile
        require(canonical.toPath().startsWith(root.toPath())) { "managed client path escaped CustomGames root" }
        val folders = PrefManager.customGameManualFolders.toMutableSet()
        val existing = folders.firstOrNull { runCatching { File(it).canonicalFile == canonical }.getOrDefault(false) }
        if (existing == null) {
            check(folders.none { runCatching { File(it).canonicalFile == canonical }.getOrDefault(false) }) { "managed client registration collision" }
            folders += canonical.absolutePath
            PrefManager.customGameManualFolders = folders
            CustomGameScanner.invalidateCache()
        }
        val item = CustomGameScanner.createLibraryItemFromFolder(canonical.absolutePath)
            ?: error("GameNative rejected managed client folder")
        require(item.gameSource == GameSource.CUSTOM_GAME)
        val registered = CustomGameScanner.getFolderPathFromAppId(item.appId)
            ?: error("managed client registration disappeared")
        require(File(registered).canonicalFile == canonical) { "custom-game registration collision for ${item.appId}" }
        return item.appId
    }

    @Synchronized
    private fun update(token: Long, runId: String, stage: ForeverStage, message: String) {
        checkCurrent(token, runId)
        store.update(runId) { current -> ForeverRunMachine.move(current, stage, message) }
        _state.value = _state.value.copy(stage = stage, message = message, operationActive = true, runId = runId)
    }

    private fun append(runId: String, event: JSONObject) = store.appendRaw(runId, event)

    private fun checkCurrent(token: Long, runId: String) {
        check(isCurrent(token, runId)) { "operation superseded or cancelled" }
    }

    @Synchronized
    private fun isCurrent(token: Long, runId: String): Boolean =
        generation.get() == token && activeRunId == runId && !terminalGate

    private fun loadPlan(): ForeverDownloadPlan? = runCatching {
        val actualPlan = ForeverProvisionerRegistry.implementation.plan(appContext)
        ForeverDownloadPlan(
            downloadBytes = actualPlan.downloadBytes,
            workspaceBytes = actualPlan.workspaceBytes,
            runtimeWorkspaceNote = "ImageFS, prefijo dedicado y la red de sus componentes requieren espacio adicional (varios GB).",
        )
    }.getOrNull()
}
