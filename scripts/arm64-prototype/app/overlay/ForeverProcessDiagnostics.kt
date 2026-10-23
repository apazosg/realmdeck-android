package app.gamenative

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.wowforever.launcher.ForeverPatchBridge
import com.winlator.core.ProcessHelper
import java.io.File
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import org.json.JSONArray
import org.json.JSONObject

internal enum class ForeverPatchAction { RETRY_READ_ONLY, APPLY, REJECT }

internal class ForeverSpawnClaim {
    private val claimed = AtomicBoolean(false)
    fun tryClaim(): Boolean = claimed.compareAndSet(false, true)
}

internal object ForeverPatchPolicy {
    fun discovery(status: String): ForeverPatchAction = when (status) {
        "NOT_FOUND", "INCOMPLETE", "FOUND", "VALIDATED" -> ForeverPatchAction.RETRY_READ_ONLY
        else -> ForeverPatchAction.REJECT
    }

    fun inspectionAllowsApply(status: String): Boolean = status == "VALIDATED" || status == "ALREADY_PATCHED"

    fun quiescent(rootExited: Boolean, streamsDrained: Boolean, patchStopped: Boolean, descendantsQuiescent: Boolean): Boolean =
        rootExited && streamsDrained && patchStopped && descendantsQuiescent

    fun treeQuiescent(trackedGone: Boolean, containmentConfirmed: Boolean): Boolean = trackedGone && containmentConfirmed
}

internal object ForeverSpawnGate {
    fun uniqueRunId(env: Array<String>?): String? {
        val entries = env?.filter { it.startsWith("WOWFOREVER_RUN_ID=") } ?: return null
        return entries.singleOrNull()?.substringAfter('=')
    }
    fun probeCommandMatches(command: String, runId: String, wine: File): Boolean = runCatching {
        val argv = ProcessHelper.splitCommand(command).toList()
        val offset = if (argv.firstOrNull() == "/system/bin/linker64") 1 else 0
        if (argv.getOrNull(offset) != wine.absolutePath) return false
        val guest = argv.drop(offset + 1)
        if (guest.size != 7 || guest[0] != "explorer" || !guest[1].matches(Regex("/desktop=shell,[0-9]{1,5}x[0-9]{1,5}"))) return false
        guest.drop(2) == listOf(ForeverGraphicsProbe.WINDOWS_TARGET) + ForeverGraphicsProbe.exactArgList(runId)
    }.getOrDefault(false)
    fun verifiedPathMatches(executable: File, command: String, cwd: File?): Boolean = runCatching {
        val verified = executable.canonicalFile
        val working = cwd?.canonicalFile ?: return false
        if (working != verified.parentFile) return false
        splitCommandForGate(command).any { raw ->
            val target = when {
                raw.startsWith("A:\\", ignoreCase = true) || raw.startsWith("A:/", ignoreCase = true) ->
                    File(working, raw.substring(3).replace('\\', '/'))
                raw.matches(Regex("^[A-Za-z]:.*")) -> return@any false
                File(raw).isAbsolute -> File(raw)
                else -> File(working, raw.replace('\\', '/'))
            }
            target.canonicalFile == verified
        }
    }.getOrDefault(false)
    fun matches(runId: String, executableName: String, command: String, env: Array<String>?, allowMissingEnv: Boolean = false): Boolean {
        val argv = splitCommandForGate(command).map { it.lowercase() }
        val suppliedId = uniqueRunId(env)
        return ((env == null && allowMissingEnv) || suppliedId == runId) && argv.any {
            it.substringAfterLast('\\').substringAfterLast('/').equals(executableName, ignoreCase = true)
        }
    }

    fun matchesManagedRoot(command: String, env: Array<String>?, allowMissingEnv: Boolean = false): Boolean =
        matches("", "WowB-ARM64.exe", command, env, allowMissingEnv) ||
            (ForeverLaunchDebug.GRAPHICS_PROBE_ENABLED &&
                matches("", ForeverGraphicsProbe.FILE_NAME, command, env, allowMissingEnv))

    private fun splitCommandForGate(command: String): List<String> = Regex("\\\"([^\\\"]*)\\\"|'([^']*)'|(\\S+)").findAll(command).map {
        it.groups[1]?.value ?: it.groups[2]?.value ?: it.groups[3]?.value.orEmpty()
    }.toList()
}

/** Pure classification rules so empty discovery is not mistaken for absence. */
internal object ForeverProcessEvidencePolicy {
    private val observedExecutables = setOf("winhandler.exe", "wowb-arm64.exe", "graphicsprobe-arm64.exe", "graphicsprobelog-arm64.exe", "graphicsprobereadback-arm64.exe", "explorer.exe", "wineboot.exe", "wineserver")

    fun executableHints(tokens: List<String>): List<String> = tokens.asSequence().take(64)
        .map { it.substringAfterLast('/').substringAfterLast('\\').lowercase() }
        .filter { it in observedExecutables }.distinct().toList()

    fun gameIdentity(candidateCount: Int, eligibleCount: Int): JSONObject = JSONObject()
        .put("gameIdentity", if (eligibleCount == 1) "observed" else "not_observed")
        .put("pidProof", when {
            candidateCount == 0 -> "not_available"
            eligibleCount == 0 -> "rejected"
            else -> "accepted"
        })
        .put("absence", if (candidateCount == 0) "not_proven" else JSONObject.NULL)

    fun historicalAttribution(identityMatch: Boolean, timeMatch: Boolean): String =
        if (identityMatch && timeMatch) "launcher" else "unattributed"

    fun hostSummary(runId: String): ByteArray = "forever_run=$runId".toByteArray(Charsets.US_ASCII)

    fun summaryMatches(summary: ByteArray?, runId: String): Boolean =
        summary != null && summary.contentEquals(hostSummary(runId))
}

/** Process evidence is collected from GameNative's own ProcessHelper observer. */
object ForeverProcessDiagnostics {
    private val handles = ConcurrentHashMap<String, ManagedProcess>()
    private val setupFailedProcesses = ConcurrentHashMap<String, Process>()
    private val pending = ConcurrentHashMap<String, SpawnToken>()
    private val patchWorkers = ConcurrentHashMap<String, Thread>()
    private val cancelledRuns = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var initialized = false
    @Volatile private var store: ForeverRunStore? = null
    @Volatile private var appContext: Context? = null
    @Volatile var listener: Listener? = null

    interface Listener {
        fun onProcessStarted(runId: String, pid: Int?, startTicks: Long?, argv: List<String>, cwd: String?)
        fun onGameIdentityObserved(runId: String, pid: Int, startTicks: Long)
        fun onProcessFinished(runId: String, exitCode: Int, durationMs: Long, terminationUnknown: Boolean)
        fun onProcessStartFailed(runId: String, error: String)
    }

    enum class StopResult { NOT_RUNNING, TERMINATED, UNKNOWN }

    internal fun isLaunchCancelled(runId: String): Boolean = cancelledRuns.contains(runId)

    internal fun cancelExpectedUnderStore(runId: String, runStore: ForeverRunStore?) {
        if (runStore == null) cancelledRuns.add(runId)
        else synchronized(runStore) { cancelledRuns.add(runId) }
    }

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            store = ForeverRunStoreRegistry.get(context.applicationContext)
            appContext = context.applicationContext
            ForeverRunDiagnosticsStore.current = store
            ProcessHelper.setExecObserver(object : ProcessHelper.ExecObserver {
                override fun beforeStart(command: String?, envp: Array<String>?, workingDir: File?): Boolean {
                    val gameRoot = ForeverSpawnGate.matchesManagedRoot(command.orEmpty(), null, allowMissingEnv = true)
                    if ((envp?.count { it.startsWith("WOWFOREVER_RUN_ID=") } ?: 0) > 1) return false
                    val runId = ForeverSpawnGate.uniqueRunId(envp)
                    if (runId == null) return !gameRoot
                    if (cancelledRuns.contains(runId)) return false
                    val expected = pending[runId]
                    if (expected != null && expected.matches(command.orEmpty(), envp, workingDir)) {
                        if (!expected.claim.tryClaim()) {
                            append(runId, JSONObject().put("event", "spawn_vetoed").put("reason", "duplicate_root_claim"))
                            return false
                        }
                        // Durable intent BEFORE exec closes the crash window in
                        // which a root exists but onStarted has not recorded it.
                        val durable = runCatching {
                            val currentStore = requireNotNull(store)
                            synchronized(currentStore) {
                                if (ForeverLaunchDebug.COMPILER_PREFIX_REPAIR &&
                                    ForeverLaunchDebug.PANVK_WINE_PROBE && expected.launchKind == ForeverGraphicsProbe.PROFILE) {
                                    val prefixes = envp.orEmpty().filter { it.startsWith("WINEPREFIX=") }
                                    require(prefixes.size == 1) { "compiler repair requires exactly one WINEPREFIX" }
                                    ForeverCompilerPrefixRepair.verifyBeforeSpawn(requireNotNull(appContext), runId,
                                        prefixes.single().substringAfter('='))
                                }
                                require(!cancelledRuns.contains(runId)) { "managed spawn cancelled before exec" }
                                currentStore.markLaunchAttempt(runId)
                            }
                            true
                        }.onFailure { failure ->
                            append(runId, JSONObject().put("event", "spawn_vetoed")
                                .put("reason", "compiler_repair_or_launch_intent_failed")
                                .put("detail", (failure.message ?: failure.javaClass.simpleName).take(256)))
                        }.getOrDefault(false)
                        if (durable) armHostSignal(runId)
                        return durable
                    }
                    if (gameRoot) {
                        append(runId, JSONObject().put("event", "spawn_vetoed").put("reason", "unarmed_or_existing_game_root"))
                        return false
                    }
                    return true
                }

                override fun onStarted(command: String?, envp: Array<String>?, workingDir: File?, process: Process?): Boolean {
                    return process != null && onProcessStarted(command.orEmpty(), envp, workingDir, process)
                }

                override fun onFinished(process: Process?, status: Int) {
                    if (process != null) onProcessFinished(process, status)
                }

                override fun onStartFailed(command: String?, envp: Array<String>?, error: Throwable?) {
                    onProcessStartFailed(command.orEmpty(), envp, error ?: IllegalStateException("process start failed"))
                }
            })
            initialized = true
        }
    }

    fun expectLaunch(runId: String, verifiedExe: File, contractPath: String) {
        val currentStore = store
        if (currentStore == null) cancelledRuns.remove(runId)
        else synchronized(currentStore) { cancelledRuns.remove(runId) }
        val token = SpawnToken(runId, verifiedExe.canonicalFile, contractPath)
        pending[runId] = token
        append(runId, JSONObject().put("event", "spawn_armed")
            .put("expectedExecutable", token.executable.absolutePath)
            .put("expectedIdentity", token.launchKind))
    }

    fun cancelExpected(runId: String) {
        // Retain the immutable token until the observer sees/rejects a late root.
        // Removing it would let a cancelled launch leak and a later run match its basename.
        cancelExpectedUnderStore(runId, store)
        append(runId, JSONObject().put("event", "spawn_gate_closed"))
    }

    fun canStartNewRun(previous: ForeverRunState?): Boolean {
        return previous == null || isProcessQuiescent(previous)
    }

    fun isProcessQuiescent(state: ForeverRunState): Boolean {
        reconcileHandle(state.runId)
        if (handles.containsKey(state.runId) || setupFailedProcesses.containsKey(state.runId) || patchWorkers.containsKey(state.runId)) return false
        val saved = runCatching { store?.read(state.runId) ?: state }.getOrNull() ?: return false
        return ForeverProcIdentityPolicy.recoveredQuiescent(saved, ForeverProcIdentityPolicy.bootId())
    }

    fun stopOwned(runId: String): StopResult {
        cancelExpectedUnderStore(runId, store)
        setupFailedProcesses[runId]?.let { process ->
            runCatching { process.destroy() }
            runCatching { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly() }
            return StopResult.UNKNOWN // no descendant containment proof
        }
        pending[runId]?.let { append(runId, JSONObject().put("event", "spawn_gate_closed")) }
        val handle = handles[runId]
        if (handle == null) {
            val patch = patchWorkers[runId]
            if (patch != null) {
                patch.join(5_000)
                if (patch.isAlive) return StopResult.UNKNOWN
            }
            // A beforeStart claim may already have passed the gate while the
            // kernel exec callback has not arrived. Keep its immutable token so
            // onStarted can own and destroy that exact late Process.
            val state = runCatching { store?.read(runId) }.getOrElse { return StopResult.UNKNOWN }
            return if (state == null || isProcessQuiescent(state)) StopResult.NOT_RUNNING else StopResult.UNKNOWN
        }
        handle.terminationRequested.set(true)
        append(runId, JSONObject().put("event", "termination_requested"))
        runCatching { handle.process.destroy() }
        var exited = runCatching { handle.process.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!exited) {
            runCatching { handle.process.destroyForcibly() }
            exited = runCatching { handle.process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)
        }
        val streams = handle.joinDrainers(2_000)
        val patch = awaitPatch(runId, 5_000)
        val descendants = handle.descendantsQuiescent()
        val quiescent = ForeverPatchPolicy.quiescent(exited, streams, patch, descendants)
        if (quiescent) {
            handles.remove(runId, handle)
            pending.remove(runId)
        } else {
            append(runId, JSONObject().put("event", "termination_unknown").put("rootExited", exited).put("streamsDrained", streams).put("patchWorkerStopped", patch).put("descendantsQuiescent", descendants))
        }
        store?.recordQuiescence(runId, quiescent)
        return if (quiescent) StopResult.TERMINATED else StopResult.UNKNOWN
    }

    fun recordHostRun(context: Context, runId: String) {
        if (Build.VERSION.SDK_INT < 30) return
        val summary = ForeverProcessEvidencePolicy.hostSummary(runId)
        require(summary.size <= 128) { "host process summary is too large" }
        runCatching { context.getSystemService(ActivityManager::class.java)?.setProcessStateSummary(summary) }
            .onFailure { append(runId, JSONObject().put("event", "host_run_summary_unavailable").put("error", it.javaClass.simpleName)) }
    }

    fun recordHistoricalExitEvidence(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val run = runCatching { store?.readCurrent() }.getOrNull() ?: return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val reasons = manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        val now = System.currentTimeMillis()
        val evidence = JSONArray()
        reasons.forEach { reason ->
            val pid = reason.pid
            val processName = reason.processName
            val pidNameMatch = pid > 0 && run.hostPid == pid && processName == context.packageName
            val runSummaryMatch = ForeverProcessEvidencePolicy.summaryMatches(reason.processStateSummary, run.runId)
            val identityMatch = pidNameMatch && runSummaryMatch
            val timeMatch = run.spawnAt?.let { reason.timestamp in (it - 5_000)..(now + 5_000) } == true
            evidence.put(JSONObject()
                .put("source", "ApplicationExitInfo")
                .put("reason", reason.reason)
                .put("status", reason.status)
                .put("importance", reason.importance)
                .put("timestamp", reason.timestamp)
                .put("pid", if (pid > 0) pid else JSONObject.NULL)
                .put("processName", processName ?: JSONObject.NULL)
                .put("identityMatch", identityMatch)
                .put("pidNameMatch", pidNameMatch)
                .put("runSummaryMatch", runSummaryMatch)
                .put("timeMatch", timeMatch)
                .put("attribution", ForeverProcessEvidencePolicy.historicalAttribution(identityMatch, timeMatch))
                .put("description", reason.description?.take(500) ?: JSONObject.NULL))
        }
        append(run.runId, JSONObject().put("event", "historical_exit_evidence").put("items", evidence))
    }

    fun recordLaunchError(context: Context, runId: String, error: String) {
        initialize(context)
        append(runId, JSONObject().put("event", "launcher_error").put("error", error.take(2000)))
    }

    /** Arms only after the durable launch journal and this spawn claim. Any
     * identity/path/native failure is explicit NO_SIGNAL_COVERAGE. */
    private fun armHostSignal(runId: String) {
        val runStore = store ?: return
        val host = runCatching {
            val pid = android.os.Process.myPid()
            val first = ForeverProcIdentityPolicy.read(pid)
            ForeverProcIdentityPolicy.groupEvidence(pid, first.ticks)
        }.getOrNull()
        val bootId = ForeverProcIdentityPolicy.bootId()
        if (host == null || bootId == null) {
            append(runId, JSONObject().put("event", "signal_trace_arm")
                .put("status", "NO_SIGNAL_COVERAGE").put("reason", "host_identity_or_boot_unavailable"))
            return
        }
        val path = runCatching { runStore.signalTracePath(runId) }.getOrElse {
            append(runId, JSONObject().put("event", "signal_trace_arm")
                .put("status", "NO_SIGNAL_COVERAGE").put("reason", "owned_path_unavailable"))
            return
        }
        runCatching { runStore.recordSignalIdentity(runId, host, bootId) }.getOrElse {
            append(runId, JSONObject().put("event", "signal_trace_arm")
                .put("status", "NO_SIGNAL_COVERAGE").put("reason", "independent_host_binding_unavailable"))
            return
        }
        val outcome = ForeverSignalTraceArm.arm(
            ForeverNativeSignalTraceAdapter,
            path.absolutePath,
            SignalTraceIdentity(runId, bootId, host.pid, host.ticks),
        )
        append(runId, JSONObject().put("event", "signal_trace_arm")
            .put("status", if (outcome.coverage) "ARMED" else "NO_SIGNAL_COVERAGE")
            .put("nativeStatus", outcome.status).put("detail", outcome.detail))
    }

    fun patchOwned(runId: String, token: Long, generation: Long) {
        val ownedHandle = handles[runId]
        if (ownedHandle?.launchKind == ForeverGraphicsProbe.PROFILE) {
            // This guard is intentionally before any native discovery. A probe
            // is never a WoW process, even if memory patching is later enabled
            // in a different APK build or a delayed callback arrives.
            appendPatch(runId, "DISABLED_FOR_GRAPHICS_PROBE", JSONObject().put("reason", "probe_is_not_the_WoW_client"))
            return
        }
        if (!ForeverLaunchDebug.MEMORY_PATCHING_ENABLED) {
            appendPatch(runId, "DISABLED_FOR_LAUNCH_DEBUG", JSONObject().put("reason", "window_and_render_before_private_auth"))
            return
        }
        val handle = ownedHandle ?: return
        val executable = handle.executable.absolutePath
        val contract = File(handle.contractPath)
        if (!contract.isFile || cancelledRuns.contains(runId) || patchWorkers.containsKey(runId)) return
        val worker = Thread {
            runCatching {
                if (!handle.rootProof()) {
                    appendPatch(runId, "ERROR_ROOT_IDENTITY", JSONObject().put("error", "managed root identity unavailable or changed"))
                    return@runCatching
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                var lastStatus = "NOT_FOUND"
                var maintenance = false
                while (!cancelledRuns.contains(runId) && (maintenance || System.nanoTime() < deadline)) {
                    if (!handle.rootProof()) break
                    handle.refreshDescendants()
                    val discovery = JSONObject(ForeverPatchBridge.findOwnedGameProcesses(executable))
                    val discoveryStatus = discovery.optString("status", "ERROR")
                    appendPatch(runId, "DISCOVERY_$discoveryStatus", discovery)
                    // INCOMPLETE discovery is not permission to use its candidate list.
                    if (ForeverPatchPolicy.discovery(discoveryStatus) == ForeverPatchAction.REJECT) {
                        break
                    }
                    if (discoveryStatus == "INCOMPLETE") {
                        Thread.sleep(500)
                        continue
                    }
                    val candidates = discovery.optJSONArray("candidates") ?: JSONArray()
                    val eligible = (0 until candidates.length()).mapNotNull { index ->
                        val candidate = candidates.getJSONObject(index)
                        val pid = candidate.optInt("pid", 0)
                        val ticks = candidate.optLong("startTicks", 0L)
                        if (pid > 0 && ticks > 0L && handle.ownsCandidate(pid, ticks)) candidate else null
                    }
                    val identity = ForeverProcessEvidencePolicy.gameIdentity(candidates.length(), eligible.size)
                    append(runId, JSONObject()
                        .put("event", "game_discovery")
                        .put("status", discoveryStatus)
                        .put("candidateCount", candidates.length())
                        .put("eligibleCount", eligible.size)
                        .put("gameIdentity", identity.getString("gameIdentity"))
                        .put("pidProof", identity.getString("pidProof"))
                        .put("absence", identity.opt("absence")))
                    if (eligible.size != 1) {
                        if (eligible.size > 1) appendPatch(runId, "REJECTED_MULTIPLE_TARGETS", JSONObject().put("eligible", eligible.size))
                        Thread.sleep(500)
                        continue
                    }
                    val candidate = eligible.single()
                    if (cancelledRuns.contains(runId)) break
                    val pid = candidate.getInt("pid")
                    val ticks = candidate.getLong("startTicks")
                    append(runId, JSONObject().put("event", "ownership_proof").put("rootPid", handle.rootPid ?: JSONObject.NULL).put("rootStartTicks", handle.rootStartTicks ?: JSONObject.NULL).put("gamePid", pid).put("gameStartTicks", ticks))
                    store?.recordGameIdentity(runId, pid, ticks)
                    listener?.onGameIdentityObserved(runId, pid, ticks)
                    if (!handle.ownsCandidate(pid, ticks)) break
                    val inspected = JSONObject(ForeverPatchBridge.inspectOwnedGame(pid, ticks, executable, contract.absolutePath, false))
                    lastStatus = inspected.optString("status", "ERROR")
                    appendPatch(runId, lastStatus, inspected)
                    if (lastStatus == "INCOMPLETE" || lastStatus == "NOT_FOUND") {
                        Thread.sleep(500)
                        continue
                    }
                    if (!ForeverPatchPolicy.inspectionAllowsApply(lastStatus)) break
                    if (cancelledRuns.contains(runId) || !handle.ownsCandidate(pid, ticks)) break
                    val patched = JSONObject(ForeverPatchBridge.inspectOwnedGame(pid, ticks, executable, contract.absolutePath, true))
                    lastStatus = patched.optString("status", "ERROR")
                    appendPatch(runId, lastStatus, patched)
                    if (lastStatus !in setOf("PATCHED", "ALREADY_PATCHED")) break
                    maintenance = true
                    Thread.sleep(5_000)
                }
                appendPatch(runId, if (cancelledRuns.contains(runId)) "CANCELLED" else "TIMEOUT_$lastStatus", JSONObject().put("status", lastStatus))
            }.onFailure { appendPatch(runId, "ERROR", JSONObject().put("error", it.message ?: it.javaClass.simpleName)) }
            patchWorkers.remove(runId, Thread.currentThread())
        }.apply { name = "forever-patch-$runId-$token-$generation" }
        if (patchWorkers.putIfAbsent(runId, worker) == null) worker.start()
    }

    private fun onProcessStarted(command: String, env: Array<String>?, cwd: File?, process: Process): Boolean {
        val token = pending.values.firstOrNull { it.matches(command, env, cwd) } ?: return false
        val pid = processPid(process)
        val managed = try {
            ManagedProcess(token.runId, process, token.executable, token.contractPath, token.launchKind, pid,
                requireNotNull(store).outputDirectory(token.runId), appContext?.filesDir)
        } catch (error: Exception) {
            // Runtime.exec has already returned this EXACT owned Process. Never
            // describe this as a pre-exec failure or lose the only handle when
            // directory/retention setup fails (for example on a full filesystem).
            setupFailedProcesses[token.runId] = process
            cancelledRuns.add(token.runId)
            pending.remove(token.runId, token)
            runCatching { process.destroy() }
            runCatching { store?.updateObservedProcess(token.runId, pid = pid, rootPid = pid,
                spawnAt = System.currentTimeMillis(), terminationUnknown = true) }
            runCatching { append(token.runId, JSONObject().put("event", "post_exec_supervision_failed")
                .put("wineCreated", true).put("termination", "unknown")
                .put("pid", pid ?: JSONObject.NULL).put("error", error.javaClass.simpleName)
                .put("reason", error.message?.take(500) ?: JSONObject.NULL)) }
            runCatching { listener?.onProcessStartFailed(token.runId,
                "Wine was created but output/ownership setup failed; termination unknown; Android reboot required") }
            return false
        }
        handles[token.runId] = managed
        pending.remove(token.runId, token)
        if (cancelledRuns.contains(token.runId)) {
            append(token.runId, JSONObject().put("event", "late_spawn_rejected"))
            managed.terminationRequested.set(true)
            runCatching { process.destroy() }
            return true
        }
        if (pid == null) append(token.runId, JSONObject().put("event", "pid_unavailable").put("termination", "unknown"))
        val ticks = managed.rootStartTicks
        runCatching { store?.updateObservedProcess(token.runId, rootPid = pid, rootStartTicks = ticks,
            terminationUnknown = pid == null || ticks == null) }
        val argv = sanitizedArgv(splitCommand(command))
        val envMap = sanitizedEnvMap(env).toMutableMap().apply {
            env?.firstOrNull { it.startsWith("WOWFOREVER_GL_CONTROL=") }?.let {
                put("WOWFOREVER_GL_CONTROL", it.substringAfter('='))
            }
        }
        append(token.runId, JSONObject()
            .put("event", "spawn_started")
            .put("argv", JSONArray(argv))
            .put("cwd", cwd?.canonicalPath ?: JSONObject.NULL)
            .put("env", JSONObject(envMap))
            .put("pid", pid ?: JSONObject.NULL)
            .put("startTicks", ticks ?: JSONObject.NULL)
            .put("processRole", "wine_wrapper")
            .put("expectedIdentity", managed.launchKind)
            .put("wineStarted", true)
            .put("gameIdentity", "not_observed"))
        append(token.runId, JSONObject()
            .put("event", "runtime_evidence")
            .put("source", "ForeverRuntimeEvidence")
            .put("evidence", runCatching {
                val context = requireNotNull(appContext) { "diagnostics context unavailable" }
                ForeverRuntimeEvidence.inspect(context, token.runId, argv, envMap)
            }.getOrElse { JSONObject().put("status", "ERROR").put("error", it.message?.take(500) ?: it.javaClass.simpleName) }))
        val winePrefix = env?.firstOrNull { it.startsWith("WINEPREFIX=") }?.substringAfter('=')
        append(token.runId, JSONObject().put("event", "wine_drive_mapping")
            .put("winePrefix", winePrefix ?: JSONObject.NULL)
            .put("driveA", winePrefix?.let { runCatching { File(it, "dosdevices/a:").canonicalPath }.getOrNull() } ?: JSONObject.NULL))
        managed.recordSnapshot("root", pid, ticks, null)
        listener?.onProcessStarted(token.runId, pid, ticks, argv, cwd?.canonicalPath)
        return true
    }

    private fun onProcessFinished(process: Process, status: Int) {
        val managed = handles.entries.firstOrNull { it.value.process === process }?.value
        if (managed == null) {
            val failed = setupFailedProcesses.entries.firstOrNull { it.value === process } ?: return
            runCatching { append(failed.key, JSONObject().put("event", "post_exec_failed_root_finished")
                .put("exitCode", status).put("termination", "unknown")) }
            runCatching { listener?.onProcessFinished(failed.key, status, 0, true) }
            return
        }
        cancelledRuns.add(managed.runId)
        val streams = managed.joinDrainers(2_000)
        val descendants = managed.descendantsQuiescent()
        val patch = awaitPatch(managed.runId, 2_000)
        val rootExited = !runCatching { process.isAlive }.getOrDefault(true)
        val unknown = status < 0 || !rootExited || !streams || !descendants || !patch
        val duration = System.currentTimeMillis() - managed.createdAt
        val output = managed.output()
        append(managed.runId, JSONObject()
            .put("event", "spawn_finished")
            .put("exitCode", status)
            .put("durationMs", duration)
            .put("stdoutSnapshot", output.stdoutSnapshot)
            .put("stderrSnapshot", output.stderrSnapshot)
            .put("streamsDrained", streams)
            .put("descendantsQuiescent", descendants)
            .put("treeBoundary", "sampled-only; no kernel containment proof; reboot required before retry")
            .put("patchWorkerStopped", patch)
            .put("closure", if (unknown) "termination_unknown" else "process_exit"))
        managed.closeOutput()
        if (!unknown) handles.remove(managed.runId, managed)
        store?.recordQuiescence(managed.runId, !unknown)
        listener?.onProcessFinished(managed.runId, status, duration, unknown)
    }

    private fun onProcessStartFailed(command: String, env: Array<String>?, error: Throwable) {
        val token = pending.values.firstOrNull { it.matches(command, env) } ?: return
        pending.remove(token.runId, token)
        store?.recordQuiescence(token.runId, true) // ProcessBuilder never returned a Process
        if (cancelledRuns.contains(token.runId)) {
            append(token.runId, JSONObject().put("event", "late_spawn_failed"))
            return
        }
        append(token.runId, JSONObject().put("event", "spawn_failed").put("error", error.message ?: error.javaClass.simpleName))
        listener?.onProcessStartFailed(token.runId, error.message ?: error.javaClass.simpleName)
    }

    private data class SpawnToken(val runId: String, val executable: File, val contractPath: String) {
        val claim = ForeverSpawnClaim()
        val launchKind = if (executable.name.equals(ForeverGraphicsProbe.FILE_NAME, ignoreCase = true)) {
            ForeverGraphicsProbe.PROFILE
        } else {
            "wow_client"
        }
        fun matches(command: String, env: Array<String>?, cwd: File? = executable.parentFile, allowMissingEnv: Boolean = false): Boolean {
            if (launchKind == ForeverGraphicsProbe.PROFILE) {
                val context = appContext ?: return false
                val wine = File(context.filesDir, "imagefs_shared/proton/proton-11.0-90624-arm64ec/bin/wine")
                if (!ForeverSpawnGate.probeCommandMatches(command, runId, wine)) return false
            }
            return ForeverSpawnGate.matches(runId, executable.name, command, env, allowMissingEnv) &&
                ForeverSpawnGate.verifiedPathMatches(executable, command, cwd)
        }
    }

    private class ManagedProcess(
        val runId: String,
        val process: Process,
        val executable: File,
        val contractPath: String,
        val launchKind: String,
        val rootPid: Int?,
        outputDirectory: File,
        sensitiveFilesDir: File?,
    ) {
        val createdAt = System.currentTimeMillis()
        val terminationRequested = AtomicBoolean(false)
        private val root = rootPid?.let { runCatching { ForeverProcIdentityPolicy.read(it) }.getOrNull() }
        val rootStartTicks: Long? = root?.ticks
        private val coverageKnown = AtomicBoolean(root != null)
        private val tracked = ConcurrentHashMap<Int, Long>()
        private val snapshots = ConcurrentHashMap.newKeySet<Int>()
        private val output = BoundedProcessOutput(outputDirectory, runId, process.inputStream, process.errorStream,
            sensitiveFilesDir = sensitiveFilesDir) { stream, error ->
            runCatching { append(runId, JSONObject().put("event", "output_persistence_error")
                .put("stream", stream).put("error", error.take(500))) }
        }

        init {
            if (root != null) tracked[root.pid] = root.ticks
            refreshDescendants()
            Thread({
                while (runCatching { process.isAlive }.getOrDefault(false)) {
                    refreshDescendants()
                    Thread.sleep(250)
                }
            }, "forever-owned-children-$runId").apply { isDaemon = true; start() }
        }

        fun joinDrainers(timeoutMs: Long): Boolean {
            output.join(timeoutMs)
            return streamsDrained()
        }

        fun streamsDrained(): Boolean = output.streamsDrained()

        fun descendantsQuiescent(): Boolean {
            if (tracked.isEmpty() || !coverageKnown.get()) return false
            val trackedGone = tracked.all { (pid, ticks) -> ForeverProcIdentityPolicy.state(pid, ticks) == ForeverProcState.GONE }
            // Sampling cannot see a child forked/reparented just before root
            // exit. Until we have a kernel-owned process group/waiting wrapper,
            // NEVER advertise complete termination on that evidence alone.
            // The persisted boot ID provides a safe recovery boundary instead.
            return ForeverPatchPolicy.treeQuiescent(trackedGone, containmentConfirmed = false)
        }

        @Synchronized
        fun refreshDescendants() {
            val visited = HashSet<Int>()
            val queue = java.util.ArrayDeque(tracked.keys)
            while (queue.isNotEmpty()) {
                val pid = queue.removeFirst()
                if (!visited.add(pid)) continue
                if (visited.size > 512) { coverageKnown.set(false); return }
                when (ForeverProcIdentityPolicy.state(pid, tracked.getValue(pid))) {
                    ForeverProcState.GONE -> continue
                    ForeverProcState.UNKNOWN -> { coverageKnown.set(false); continue }
                    ForeverProcState.ALIVE -> Unit
                }
                try {
                    Files.newDirectoryStream(File("/proc/$pid/task").toPath()).use { tasks ->
                        for (task in tasks) {
                            val children = String(Files.readAllBytes(task.resolve("children")), Charsets.US_ASCII)
                            children.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }.forEach { child ->
                                try {
                                    val childStat = ForeverProcIdentityPolicy.read(child)
                                    if (childStat.parent == pid) {
                                        if (tracked.putIfAbsent(child, childStat.ticks) == null) {
                                            recordSnapshot("descendant", child, childStat.ticks, pid)
                                        }
                                        queue.add(child)
                                    }
                                } catch (_: NoSuchFileException) { /* already gone */ }
                            }
                        }
                    }
                } catch (_: NoSuchFileException) { /* process or thread finished */ }
                catch (_: Exception) { coverageKnown.set(false) }
            }
        }

        fun rootProof(): Boolean = root?.let { ForeverProcIdentityPolicy.state(it.pid, it.ticks) == ForeverProcState.ALIVE } == true

        fun ownsCandidate(pid: Int, ticks: Long): Boolean = root?.let {
            ForeverProcIdentityPolicy.belongsTo(it, pid, ticks)
        } == true

        fun output(): ProcessOutput = ProcessOutput(
            output.stdoutText(), output.stderrText(),
            snapshotJson(output.stdoutSnapshot()), snapshotJson(output.stderrSnapshot()),
        )

        fun closeOutput() = output.close()

        fun recordSnapshot(role: String, pid: Int?, ticks: Long?, parentPid: Int?) {
            if (pid == null || ticks == null || (role != "root" && snapshots.size >= 64) || !snapshots.add(pid)) return
            if (!ownsCandidate(pid, ticks)) return
            val image = runCatching { Files.readSymbolicLink(java.nio.file.Paths.get("/proc/$pid/exe")).toString() }.getOrNull()
            val cwd = runCatching { Files.readSymbolicLink(java.nio.file.Paths.get("/proc/$pid/cwd")).toString() }.getOrNull()
            val hints = runCatching {
                Files.newInputStream(java.nio.file.Paths.get("/proc/$pid/cmdline")).use { input ->
                    val bytes = ByteArray(8192)
                    var length = 0
                    while (length < bytes.size) {
                        val count = input.read(bytes, length, bytes.size - length)
                        if (count <= 0) break
                        length += count
                    }
                    ForeverProcessEvidencePolicy.executableHints(String(bytes, 0, length, Charsets.UTF_8).split('\u0000'))
                }
            }.getOrDefault(emptyList())
            if (!ownsCandidate(pid, ticks)) return
            append(runId, JSONObject()
                .put("event", "process_snapshot")
                .put("role", role)
                .put("pid", pid)
                .put("startTicks", ticks)
                .put("parentPid", parentPid ?: JSONObject.NULL)
                .put("executableHints", JSONArray(hints))
                .put("verificationBoundary", "owned PID/startTicks metadata only; executable hints do not prove WoW identity or rendering")
                .put("imageBasename", image?.substringAfterLast('/') ?: JSONObject.NULL)
                .put("pathHints", JSONArray(listOfNotNull(image?.take(256), cwd?.take(256)))))
        }
    }

    data class ProcessOutput(
        val stdout: String,
        val stderr: String,
        val stdoutSnapshot: JSONObject,
        val stderrSnapshot: JSONObject,
    )

    private fun snapshotJson(snapshot: BoundedLogSnapshot): JSONObject = JSONObject()
        .put("schema", snapshot.schema)
        .put("stream", snapshot.stream)
        .put("runId", snapshot.runId)
        .put("totalBytes", snapshot.totalBytes)
        .put("headBytes", snapshot.headBytes)
        .put("tailStart", snapshot.tailStart)
        .put("tailBytes", snapshot.tailBytes)
        .put("truncated", snapshot.truncated)
        .put("capturedAtMs", snapshot.capturedAtMs)
        .put("flushedAtMs", snapshot.flushedAtMs)

    private fun append(runId: String, value: JSONObject) {
        store?.appendRaw(runId, value.put("at", System.currentTimeMillis()))
    }

    private fun appendPatch(runId: String, status: String, response: JSONObject) {
        val runStore = store ?: return
        runCatching { runStore.updatePatchState(runId, status) }
        runStore.appendRaw(runId, JSONObject().put("event", "patch_pass").put("patchState", status).put("response", response))
    }

    private fun awaitPatch(runId: String, timeoutMs: Long): Boolean {
        val worker = patchWorkers[runId] ?: return true
        worker.join(timeoutMs)
        val stopped = !worker.isAlive
        if (stopped) patchWorkers.remove(runId, worker)
        return stopped
    }

    private fun reconcileHandle(runId: String) {
        val handle = handles[runId] ?: return
        if (!runCatching { handle.process.isAlive }.getOrDefault(true) && patchWorkers[runId] == null && handle.descendantsQuiescent() && handle.streamsDrained()) {
            handles.remove(runId, handle)
            store?.recordQuiescence(runId, true)
        }
    }

    private val safeEnvKeys = setOf("HOME", "PATH", "LD_LIBRARY_PATH", "WINEPREFIX", "TMPDIR", "DISPLAY", "PREFIX", "XDG_DATA_DIRS", "WINEESYNC", "ANDROID_SYSVSHM_SERVER", "PULSE_SERVER", "HODLL", "WINEPATH", "LD_PRELOAD", "EVSHIM_BASE_PATH", "WOWFOREVER_RUN_ID", "WINEMU_HOST_PKG", "HOST_PKG", "WINEDEBUG", "WINEDLLOVERRIDES", "WINELOADER", "REDIRECT_EXEC__PROC_SELF_EXE")
    private val declaredWineDebugChannels = setOf(
        "seh", "timestamp", "pid", "process", "module", "loaddll",
        "winex11", "x11drv", "win32u", "d3d11", "dxgi", "wined3d", "vulkan",
    )

    private fun sanitizedEnvMap(env: Array<String>?): Map<String, String> = buildMap {
        env.orEmpty().forEach { value ->
            val index = value.indexOf('=')
            if (index <= 0) return@forEach
            val key = value.substring(0, index)
            if (key !in safeEnvKeys) return@forEach
            val raw = value.substring(index + 1)
            val sanitized = when (key) {
                "WINEDEBUG" -> raw.split(',').mapNotNull { token ->
                    val trimmed = token.trim()
                    val plus = trimmed.indexOf('+')
                    when {
                        trimmed.isEmpty() -> null
                        // "+channel" enables trace for a declared channel.
                        plus == 0 -> trimmed.drop(1).takeIf { it in declaredWineDebugChannels }
                        // "class+channel" (err/warn/fixme/trace) for a declared channel.
                        plus > 0 -> {
                            val cls = trimmed.substring(0, plus)
                            val channel = trimmed.substring(plus + 1)
                            trimmed.takeIf { cls in setOf("err", "warn", "fixme", "trace") && channel in declaredWineDebugChannels }
                        }
                        // "-channel" disables a declared channel.
                        trimmed.startsWith('-') -> trimmed.drop(1).takeIf { it in declaredWineDebugChannels }?.let { "-$it" }
                        else -> null
                    }
                }.joinToString(",")
                "HOST_PKG", "WINEMU_HOST_PKG" -> raw.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,200}")) } ?: return@forEach
                else -> raw.take(2000)
            }
            put(key, sanitized)
        }
    }

    private fun sanitizedArgv(argv: List<String>): List<String> {
        val sensitive = setOf("--password", "-password", "--token", "-token", "--secret", "-secret", "--auth")
        var redactNext = false
        return argv.take(64).map { argument ->
            val lower = argument.lowercase()
            if (redactNext) {
                redactNext = false
                "<redacted>"
            } else if (lower in sensitive || sensitive.any { lower.startsWith("$it=") }) {
                redactNext = !lower.contains('=')
                if (lower.contains('=')) argument.substringBefore('=') + "=<redacted>" else argument
            } else argument.take(512)
        }
    }

    private fun splitCommand(command: String): List<String> = Regex("\\\"([^\\\"]*)\\\"|'([^']*)'|(\\S+)").findAll(command).map {
        it.groups[1]?.value ?: it.groups[2]?.value ?: it.groups[3]?.value.orEmpty()
    }.toList()

    private fun processPid(process: Process): Int? = runCatching {
        process.javaClass.getMethod("pid").invoke(process) as Long
    }.getOrNull()?.toInt()?.takeIf { it > 0 }
        ?: runCatching {
            val field: Field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process)
        }.getOrNull()?.takeIf { it > 0 }

}

private object ForeverRunDiagnosticsStore {
    @Volatile var current: ForeverRunStore? = null
}
