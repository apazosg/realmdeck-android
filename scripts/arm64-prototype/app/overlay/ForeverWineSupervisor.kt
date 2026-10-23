package app.gamenative

import android.content.Context
import android.os.Process as AndroidProcess
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class ForeverWineSupervisorState(
    val status: String = "IDLE",
    val phase: String = "none",
    val message: String = "Preparado; requiere consentimiento explícito.",
    val error: String? = null,
    val wsRunId: String? = null,
    val closureObserved: Boolean = false,
    val qualification: Boolean = false,
)

internal data class ForeverWineTimeouts(
    val prefixSeconds: Long = ForeverWineSupervisorPolicy.PREFIX_TIMEOUT_SECONDS,
    val nativeSeconds: Long = ForeverWineSupervisorPolicy.NATIVE_TIMEOUT_SECONDS,
    val cleanupSeconds: Long = ForeverWineSupervisorPolicy.CLEANUP_TIMEOUT_SECONDS,
    val watchdogSeconds: Long = ForeverWineSupervisorPolicy.WATCHDOG_SECONDS,
    val totalSeconds: Long = ForeverWineSupervisorPolicy.TOTAL_TIMEOUT_SECONDS,
)

internal interface ForeverWineProcessFactory {
    fun startSupervisor(argv: Array<String>): java.lang.Process
    fun startPublisher(argv: Array<String>): java.lang.Process
    fun startWitness(argv: Array<String>): java.lang.Process
}

internal object ForeverWineRealProcessFactory : ForeverWineProcessFactory {
    private fun start(argv: Array<String>): java.lang.Process = ProcessBuilder(*argv).apply {
        environment().clear()
        redirectErrorStream(false)
    }.start()
    override fun startSupervisor(argv: Array<String>): java.lang.Process {
        require(argv.size > 1)
        ForeverWineSupervisorPolicy.requireImmutableElfLaunch(File(argv[1]))
        return start(argv)
    }
    override fun startPublisher(argv: Array<String>): java.lang.Process {
        require(ForeverWineSupervisorPolicy.PUBLISHER_PIN_STATUS == "READY") { "publisher pin is not READY" }
        ForeverWineSupervisorPolicy.validatePublisherArgv(argv.toList())
        return start(argv)
    }
    override fun startWitness(argv: Array<String>): java.lang.Process {
        require(argv.size > 1)
        ForeverWineSupervisorPolicy.requireImmutableElfLaunch(File(argv[1]))
        return start(argv)
    }
}

internal interface ForeverWineKernel { fun read(pid: Int): ForeverNativeProcIdentity }
internal object ForeverWineRealKernel : ForeverWineKernel { override fun read(pid: Int) = ForeverNativeRealKernel.read(pid) }

internal interface ForeverWineClock { fun nanoTime(): Long }
internal object ForeverWineRealClock : ForeverWineClock { override fun nanoTime() = System.nanoTime() }

internal sealed class ForeverWineReaderMessage {
    data class Frame(val bytes: ByteArray, val text: String) : ForeverWineReaderMessage()
    data class Failure(val error: Throwable) : ForeverWineReaderMessage()
    object Eof : ForeverWineReaderMessage()
}

/** Java-owned controller for the closed SERVER_ISOLATION Wine profile only. */
class ForeverWineSupervisor private constructor(private val appContext: Context) {
    companion object {
        private val instances = java.util.concurrent.ConcurrentHashMap<String, ForeverWineSupervisor>()
        fun get(context: Context): ForeverWineSupervisor {
            val app = context.applicationContext
            ForeverWineSupervisorPolicy.requireDebugProcOnly(app)
            return instances.computeIfAbsent(app.packageName) { ForeverWineSupervisor(app) }
        }
    }

    internal constructor(
        context: Context,
        processFactory: ForeverWineProcessFactory,
        kernel: ForeverWineKernel,
        clock: ForeverWineClock = ForeverWineRealClock,
        store: ForeverRunStore = ForeverRunStoreRegistry.get(context),
        artifactResolver: (Context) -> ForeverWineSupervisorPolicy.NativeArtifactPaths,
        prefixPreparer: (
            Context, String, String, (List<String>, Long) -> Int,
        ) -> ForeverWinePrefix.Prepared = { c, ws, nonce, publisher ->
            ForeverWinePrefix.prepare(c, ws, nonce, publisher)
        },
        executor: ExecutorService = Executors.newCachedThreadPool(),
        timeouts: ForeverWineTimeouts = ForeverWineTimeouts(),
        preparedValidator: (Context, ForeverWinePrefix.Prepared) -> Unit = { c, prepared ->
            ForeverWinePrefix.verifyPrepared(c, prepared)
        },
    ) : this(context.applicationContext) {
        require(context.packageName == ForeverWineSupervisorPolicy.PACKAGE)
        this.processFactory = processFactory
        this.kernel = kernel
        this.clock = clock
        this.store = store
        this.artifactResolver = artifactResolver
        this.prefixPreparer = prefixPreparer
        this.executor = executor
        this.timeouts = timeouts
        this.preparedValidator = preparedValidator
    }

    private var processFactory: ForeverWineProcessFactory = ForeverWineRealProcessFactory
    private var kernel: ForeverWineKernel = ForeverWineRealKernel
    private var clock: ForeverWineClock = ForeverWineRealClock
    private var store: ForeverRunStore = ForeverRunStoreRegistry.get(appContext)
    private var artifactResolver: (Context) -> ForeverWineSupervisorPolicy.NativeArtifactPaths = { ForeverWineSupervisorPolicy.resolve(it) }
    private var prefixPreparer: (Context, String, String, (List<String>, Long) -> Int) -> ForeverWinePrefix.Prepared =
        { c, ws, nonce, publisher -> ForeverWinePrefix.prepare(c, ws, nonce, publisher) }
    private var executor: ExecutorService = Executors.newCachedThreadPool()
    private var timeouts: ForeverWineTimeouts = ForeverWineTimeouts()
    private var preparedValidator: (Context, ForeverWinePrefix.Prepared) -> Unit = { c, prepared ->
        ForeverWinePrefix.verifyPrepared(c, prepared)
    }

    private val monitor = Any()
    private val _state = MutableStateFlow(ForeverWineSupervisorState())
    val state: StateFlow<ForeverWineSupervisorState> get() = _state.asStateFlow()
    private val listeners = CopyOnWriteArrayList<(ForeverWineSupervisorState) -> Unit>()
    private var active: Run? = null

    private class Job<T> {
        lateinit var future: Future<T>
        val done = CountDownLatch(1)
        @Volatile var completed = false
        @Volatile var value: T? = null
        @Volatile var failure: Throwable? = null
    }

    private class Run(
        val binding: ForeverRunStore.WineBinding,
        val token: Long,
        val overallDeadline: Long,
    ) {
        val queue: BlockingQueue<ForeverWineReaderMessage> = LinkedBlockingQueue()
        val jobs = CopyOnWriteArrayList<Job<*>>()
        val readerJobs = CopyOnWriteArrayList<Job<*>>()
        val supervisorReaderJobs = CopyOnWriteArrayList<Job<*>>()
        val publisherReaderJobs = CopyOnWriteArrayList<Job<*>>()
        val witnessReaderJobs = CopyOnWriteArrayList<Job<*>>()
        val waitJobs = CopyOnWriteArrayList<Job<*>>()
        val writerJobs = CopyOnWriteArrayList<Job<*>>()
        val commandLock = Any()
        val registrationMonitor = Any()
        val outputLock = Any()
        val outputBytesByDirection = mutableMapOf<String, Int>()
        var combinedOutputBytes = 0
        val children = linkedMapOf<Int, ForeverNativeProcIdentity>()
        val resolvedChildren = linkedMapOf<Int, ForeverNativeProcIdentity>()
        var supervisor: java.lang.Process? = null
        var supervisorIdentity: ForeverNativeProcIdentity? = null
        var supervisorWait: Job<Int>? = null
        var supervisorExit: Int? = null
        var publisher: java.lang.Process? = null
        var publisherIdentity: ForeverNativeProcIdentity? = null
        var publisherWait: Job<Int>? = null
        var witness: java.lang.Process? = null
        var witnessIdentity: ForeverNativeProcIdentity? = null
        var witnessWait: Job<Int>? = null
        var witnessInput: BufferedInputStream? = null
        var witnessDrain: Job<Unit>? = null
        var witnessStderrDrain: Job<Unit>? = null
        var witnessReady = false
        var witnessProof = false
        var witnessStartedNanos = 0L
        var witnessQSent = false
        var readerEof = false
        @Volatile var outputFailure: Throwable? = null
        var seq = 1L
        var eventSeq = 0L
        var phase = ForeverWinePhase.NONE
        var serverReady = false
        var serverPid: Int? = null
        var clientPid: Int? = null
        var clientPass = false
        var clientPhaseUnknown = false
        var nativeErrorSeen = false
        var runDone: ForeverWineEvent? = null
        var closureObserved = false
        var qualification = false
        @Volatile var cancelRequested = false
        @Volatile var stdinPoisoned = false
        @Volatile var stdinClosed = false
        @Volatile var writeInFlight = false
        var protocolError: String? = null
        var durabilityError: Throwable? = null
        var lateProcess = false
        @Volatile var terminalStatus: String? = null
        @Volatile var terminalizing = false
        var terminalDurable = false
        var nativeDeadline = 0L
        var cleanupDeadlineNanos = 0L
        var prefixDev = 0L
        var prefixIno = 0L
        var cleanupStarted = false
        @Volatile var stopRequestSubmitted = false
        var stopRequestPending = false
        var terminationCommandSent = false
        var terminationJob: Job<Unit>? = null
        @Volatile var commandGeneration = 0L
        var lateDrainJob: Job<Unit>? = null
        val completionLatch = CountDownLatch(1)
        val childRoles = linkedMapOf<Int, String>()
        val resolvedExitCodes = linkedMapOf<Int, Int>()
    }

    fun addUiListener(listener: (ForeverWineSupervisorState) -> Unit): () -> Unit {
        listeners += listener; listener(state.value); return { listeners.remove(listener) }
    }

    fun start(consent: Boolean): Boolean {
        require(consent) { "Wine supervisor requires consent" }
        var started: Run? = null
        synchronized(monitor) {
            if (active != null) return false
            val ws = "WS-" + randomHex(32)
            val nonce = randomHex(32)
            val binding = try { store.beginWine(ws, nonce) } catch (failure: Throwable) {
                publish(ForeverWineSupervisorState(status = "UNKNOWN", message = "RUN bloqueado; no se reanuda un Wine incierto.", error = failure.message))
                return false
            }
            started = Run(binding, System.nanoTime(), clock.nanoTime() + TimeUnit.SECONDS.toNanos(timeouts.totalSeconds))
            active = started; publish(ForeverWineSupervisorState("PREPARING", wsRunId = ws, message = "Preparando prefijo Wine privado…"))
        }
        // Submission is intentionally outside monitor: startup and prefix work are bounded
        // owned jobs, never synchronous work hidden behind the controller lock.
        val run = requireNotNull(started)
        executor.submit { execute(run) }
        return true
    }

    fun cancel(): Boolean {
        val run = synchronized(monitor) {
            val current = active ?: return false
            if (current.terminalizing || current.terminalStatus != null) return false
            // Cancellation is deliberately recorded without taking commandLock.
            // A writer may be blocked in native write; waiting for that lock here
            // would make the only poison path depend on the blocked writer.
            if (!current.cancelRequested) current.commandGeneration++
            current.cancelRequested = true
            current.stopRequestPending = true
            publish(_state.value.copy(status = "CANCELLING", message = "Cancelación cooperativa; conservando ownership…"))
            current
        }
        // Poison first.  requestCooperativeStop may briefly reserve commandLock,
        // but it must never be the first cancellation action.
        if (run.writeInFlight || run.writerJobs.any { !it.completed }) poisonInput(run)
        requestCooperativeStop(run)
        return true
    }

    fun onActivityDestroyed(changingConfigurations: Boolean) = Unit

    internal fun awaitCompletionForTesting(timeoutMillis: Long): Boolean =
        active?.completionLatch?.await(timeoutMillis, TimeUnit.MILLISECONDS) ?: true

    private fun execute(run: Run) {
        var result = "UNKNOWN"
        try {
            val artifacts = artifactResolver(appContext)
            val runtime = ForeverWineSupervisorPolicy.runtimeRoot(appContext)
            val prefixJob = submit(run, run.jobs) {
                prefixPreparer(appContext, run.binding.wsRunId, run.binding.nonce) { argv, deadline ->
                store.recordWineProcessCreateIntent(run.binding.runId, run.binding.wsRunId, run.binding.nonce, "prefix-publisher")
                awaitPublisher(run, argv, deadline)
                }
            }
            val prefix = await(prefixJob, minOf(run.overallDeadline, clock.nanoTime() + TimeUnit.SECONDS.toNanos(timeouts.prefixSeconds)))
            require(prefix.wsRunId == run.binding.wsRunId && prefix.nonce == run.binding.nonce)
            require(prefix.runtimeRoot.canonicalFile == runtime)
            require(prefix.prefix.canonicalFile == prefix.prefix && prefix.prefixDev > 0 && prefix.prefixIno > 0)
            run.prefixDev = prefix.prefixDev
            run.prefixIno = prefix.prefixIno
            run.nativeDeadline = clock.nanoTime() + TimeUnit.SECONDS.toNanos(timeouts.nativeSeconds)
            run.cleanupDeadlineNanos = run.nativeDeadline + TimeUnit.SECONDS.toNanos(timeouts.cleanupSeconds)
            val argv = arrayOf(
                ForeverWineSupervisorPolicy.LINKER, artifacts.supervisor.canonicalPath,
                "--run", run.binding.wsRunId, "--nonce", run.binding.nonce,
                "--runtime-root", prefix.runtimeRoot.canonicalPath, "--prefix", prefix.prefix.canonicalPath,
            )
            require(argv.size == 10 && argv[0] == ForeverWineSupervisorPolicy.LINKER && argv[1] == artifacts.supervisor.canonicalPath)
            startSupervisor(run, argv, prefix)
            awaitReady(run)
            startWitness(run, artifacts.fixture)
            if (run.cancelRequested) {
                requestCooperativeStop(run)
            } else {
                send(run, ForeverWineCommandKind.START, ForeverWinePhase.SERVER)
                awaitServerReady(run, prefix)
                if (run.cancelRequested) requestCooperativeStop(run)
                else {
                    send(run, ForeverWineCommandKind.START, ForeverWinePhase.CLIENT)
                    awaitClientDone(run)
                    if (!run.cancelRequested && run.clientPass && !run.clientPhaseUnknown && !run.nativeErrorSeen) {
                        send(run, ForeverWineCommandKind.CLOSE, ForeverWinePhase.NONE)
                    } else requestCooperativeStop(run)
                }
            }
            awaitRunDone(run)
            finishWitness(run)
            awaitSupervisor(run, requireZero = run.runDone?.status in setOf("PASS", "CANCELLED"))
            run.closureObserved = run.runDone != null && run.runDone!!.event == "RUN_DONE" &&
                run.children.isEmpty() && run.runDone!!.echild && run.supervisorExit == 0 &&
                run.outputFailure == null && run.durabilityError == null
            result = when {
                run.runDone?.status == "PASS" && !run.cancelRequested && run.clientPass && !run.clientPhaseUnknown && !run.nativeErrorSeen &&
                    run.witnessProof && run.closureObserved -> "PASS"
                run.runDone?.status == "CANCELLED" && run.witnessProof && run.closureObserved -> "CANCELLED"
                else -> "UNKNOWN"
            }
            run.qualification = result == "PASS" || result == "CANCELLED"
        } catch (failure: Throwable) {
            run.protocolError = run.protocolError ?: (failure.message ?: failure.javaClass.simpleName)
            result = "UNKNOWN"
            cleanupAfterError(run)
        } finally {
            var terminalResult = synchronized(monitor) {
                synchronized(run.registrationMonitor) {
                    if (run.terminalizing) result else {
                        run.terminalizing = true
                        run.commandGeneration++
                        val accepted = if (result == "PASS" && run.cancelRequested) "UNKNOWN" else result
                        if (accepted != "PASS" && accepted != "CANCELLED") run.qualification = false
                        accepted
                    }
                }
            }
            if (run.writerJobs.any { !it.completed }) poisonInput(run)
            runCatching { awaitAll(run.writerJobs, cleanupDeadline(run)) }.onFailure {
                terminalResult = "UNKNOWN"
                run.qualification = false
                run.protocolError = run.protocolError ?: "writer did not quiesce before terminal commit"
            }
            complete(run, terminalResult)
        }
    }

    private fun startSupervisor(run: Run, argv: Array<String>, prepared: ForeverWinePrefix.Prepared) {
        requireCreationAllowed(run, run.nativeDeadline)
        store.recordWineProcessCreateIntent(run.binding.runId, run.binding.wsRunId, run.binding.nonce, "supervisor")
        val job = submit(run, run.jobs) {
            requireCreationAllowed(run, run.nativeDeadline)
            preparedValidator(appContext, prepared)
            val process = processFactory.startSupervisor(argv)
            val late = synchronized(run.registrationMonitor) {
                val attachedLate = registerProcess(run, "supervisor", process)
                run.supervisorWait = installWait(run, process, false)
                startReader(run, process, run.supervisorReaderJobs, "native-stdout", "native-stderr")
                attachedLate
            }
            if (late) {
                // The native supervisor owns its descendants.  Java only closes
                // stdin and drains the exact returned handles; it never destroys
                // this root or signals a PID discovered after the deadline.
                poisonInput(run)
                armLateSupervisorDrain(run)
            } else {
                run.supervisorIdentity = readOwned(ForeverNativeProcessPid.read(process), AndroidProcess.myPid())
                if (run.cancelRequested || run.outputFailure != null) requestCooperativeStop(run)
            }
        }
        await(job, run.nativeDeadline)
    }

    private fun awaitPublisher(run: Run, argv: List<String>, callbackDeadline: Long): Int {
        require(argv.isNotEmpty())
        require(ForeverWineSupervisorPolicy.PUBLISHER_PIN_STATUS == "READY") { "publisher pin is not READY" }
        ForeverWineSupervisorPolicy.validatePublisherArgv(argv)
        val deadline = minOf(run.overallDeadline, callbackDeadline)
        requireCreationAllowed(run, deadline)
        val creation = submit(run, run.jobs) {
            requireCreationAllowed(run, deadline)
            val process = processFactory.startPublisher(argv.toTypedArray())
            val late = synchronized(run.registrationMonitor) {
                val attachedLate = registerProcess(run, "publisher", process, deadline)
                run.publisherWait = installWait(run, process, false)
                startReader(run, process, run.publisherReaderJobs, "publisher-stdout", "publisher-stderr")
                attachedLate
            }
            run.publisherIdentity = runCatching { readOwned(ForeverNativeProcessPid.read(process), AndroidProcess.myPid()) }.getOrNull()
            if (late) closeLeafInput(process)
        }
        val process = await(creation, deadline)
        val result = await(requireNotNull(run.publisherWait), deadline)
        awaitAll(run.publisherReaderJobs, deadline)
        if (result != 0) run.protocolError = run.protocolError ?: "prefix publisher exited $result"
        return result
    }

    private fun startWitness(run: Run, fixture: File) {
        val ns = "NS-" + ForeverWineSupervisorPolicy.sha256((run.binding.wsRunId + run.binding.nonce).toByteArray()).substring(0, 32)
        val argv = arrayOf(ForeverWineSupervisorPolicy.LINKER, fixture.canonicalPath, "--witness", ns, run.binding.nonce)
        store.recordWineProcessCreateIntent(run.binding.runId, run.binding.wsRunId, run.binding.nonce, "witness")
        requireCreationAllowed(run, run.nativeDeadline)
        val job = submit(run, run.jobs) {
            requireCreationAllowed(run, run.nativeDeadline)
            val process = processFactory.startWitness(argv)
            val late = synchronized(run.registrationMonitor) {
                val attachedLate = registerProcess(run, "witness", process)
                run.witnessStartedNanos = clock.nanoTime()
                run.witnessWait = installWait(run, process, true)
                attachedLate
            }
            if (late) {
                closeLeafInput(process)
                armLateWitnessDrain(run, process)
            } else {
                val identity = runCatching { readOwned(ForeverNativeProcessPid.read(process), AndroidProcess.myPid()) }
                    .onFailure { run.protocolError = run.protocolError ?: "witness identity read failed" }
                    .getOrNull()
                run.witnessIdentity = identity
                requireNotNull(identity)
                val readyJob = submit(run, run.witnessReaderJobs) { readOneWitness(run, process, ns) }
                val line = await(readyJob, run.nativeDeadline)
                val parsed = readWitnessReady(line, ns, run.binding.nonce, identity)
                require(parsed)
                run.witnessReady = true
                run.witnessDrain = submit(run, run.witnessReaderJobs) {
                    try { drainBounded(run, requireNotNull(run.witnessInput), "witness-stdout"); Unit }
                    catch (failure: Throwable) { markOutputUnknown(run, failure); Unit }
                }
                run.witnessStderrDrain = submit(run, run.witnessReaderJobs) {
                    try { process.errorStream.use { drainBounded(run, it, "witness-stderr") }; Unit }
                    catch (failure: Throwable) { markOutputUnknown(run, failure); Unit }
                }
            }
        }
        await(job, run.nativeDeadline)
    }

    private fun requireCreationAllowed(run: Run, deadline: Long) {
        synchronized(run.registrationMonitor) {
            require(!run.terminalizing && run.terminalStatus == null && !run.cancelRequested &&
                run.outputFailure == null && clock.nanoTime() < deadline) {
                "Wine process creation is no longer allowed"
            }
        }
    }

    /** Assign the returned Java handle before any PID read, wait, or stream read. */
    private fun registerProcess(run: Run, role: String, process: java.lang.Process, creationDeadline: Long = run.nativeDeadline): Boolean {
        synchronized(run.registrationMonitor) {
            val late = run.terminalizing || run.terminalStatus != null || run.cancelRequested ||
                (creationDeadline != 0L && clock.nanoTime() >= creationDeadline)
            when (role) {
                "supervisor" -> run.supervisor = process
                "publisher" -> run.publisher = process
                "witness" -> run.witness = process
                else -> error("unknown Wine process role")
            }
            if (late) run.lateProcess = true
            return late
        }
    }

    private fun readWitnessReady(line: String, ns: String, nonce: String, identity: ForeverNativeProcIdentity): Boolean {
        val json = ForeverWineFlatJson.objectOf(line)
        val keys = json.keys().asSequence().toSet()
        val expected = setOf(
            "schema", "runId", "nonce", "seq", "phase", "event", "pid", "startTicks", "ppid",
            "uid0", "uid1", "uid2", "uid3", "role", "status", "exitCode", "childCount", "echild",
            "setResult", "getResult", "getValue", "prctl",
        )
        require(keys == expected)
        require(ForeverWineFlatJson.long(json, "schema") == 1L)
        require(ForeverWineFlatJson.string(json, "runId") == ns)
        require(ForeverWineFlatJson.string(json, "nonce") == nonce)
        require(ForeverWineFlatJson.long(json, "seq") == 1L && ForeverWineFlatJson.long(json, "phase") == 0L)
        require(ForeverWineFlatJson.string(json, "event") == "READY" &&
            ForeverWineFlatJson.string(json, "role") == "witness" &&
            ForeverWineFlatJson.string(json, "status") == "READY")
        require(ForeverWineFlatJson.long(json, "pid") == identity.pid.toLong() &&
            ForeverWineFlatJson.long(json, "startTicks") == identity.startTicks &&
            ForeverWineFlatJson.long(json, "ppid") == identity.ppid.toLong())
        require((0..3).all { ForeverWineFlatJson.long(json, "uid$it") == identity.uids[it].toLong() })
        require(ForeverWineFlatJson.long(json, "exitCode") == -1L &&
            ForeverWineFlatJson.long(json, "childCount") == 0L &&
            json.get("echild") == false && ForeverWineFlatJson.long(json, "prctl") == -1L)
        require(ForeverWineFlatJson.long(json, "setResult") == -1L &&
            ForeverWineFlatJson.long(json, "getResult") == -1L && ForeverWineFlatJson.long(json, "getValue") == -1L)
        return true
    }

    private fun awaitReady(run: Run) {
        val event = nextEvent(run)
        require(event.event == "READY" && event.phase == ForeverWinePhase.NONE && event.role == "supervisor")
        requireIdentity(run.supervisorIdentity, event)
        publish(_state.value.copy(status = "READY", phase = "none", message = "Supervisor Wine READY.", wsRunId = run.binding.wsRunId))
    }

    private fun awaitServerReady(run: Run, prepared: ForeverWinePrefix.Prepared) {
        while (!run.serverReady && run.runDone == null) {
            val event = nextEvent(run)
            consumeChild(run, event)
            if (event.event == "SERVER_READY") {
                require(event.prefixDev == prepared.prefixDev && event.prefixIno == prepared.prefixIno)
                require(event.json.getLong("socketDev") > 0 && event.json.getLong("socketIno") > 0)
                require(event.json.getLong("lockDev") > 0 && event.json.getLong("lockIno") > 0)
                require(event.role == "server" && event.phase == ForeverWinePhase.SERVER)
                run.serverReady = true; publish(_state.value.copy(status = "RUNNING", phase = "server", message = "Wine server namespace ready."))
            }
        }
        if (run.cancelRequested && run.runDone?.status == "CANCELLED") return
        require(run.serverReady) { run.protocolError ?: "server did not become ready" }
    }

    private fun awaitClientDone(run: Run) {
        while (!run.clientPass && run.runDone == null && !run.clientPhaseUnknown) {
            val event = nextEvent(run); consumeChild(run, event)
            if (event.event == "PHASE_DONE") {
                require(event.phase == ForeverWinePhase.CLIENT && event.role == "client")
                if (event.status == "PASS") run.clientPass = true
            }
        }
        if (run.runDone?.status == "CANCELLED" && run.cancelRequested) return
        require(run.clientPass) { run.protocolError ?: "client phase did not pass" }
    }

    private fun awaitRunDone(run: Run) {
        while (run.runDone == null) {
            val event = nextEvent(run); consumeChild(run, event)
            if (event.event == "RUN_DONE") {
                require(event.phase == ForeverWinePhase.NONE && event.role == "supervisor" && event.childCount == 0L && event.echild)
                require(run.children.isEmpty())
                run.runDone = event
            }
        }
    }

    private fun consumeChild(run: Run, event: ForeverWineEvent) {
        require(event.seq == run.eventSeq)
        when (event.event) {
            "READY", "ERROR" -> requireIdentity(run.supervisorIdentity, event)
            "RUN_DONE" -> {
                requireIdentity(run.supervisorIdentity, event)
                require(event.phase == ForeverWinePhase.NONE && event.childCount == 0L && event.echild && run.children.isEmpty())
            }
            "SERVER_READY" -> {
                require(run.serverPid == event.pid.toInt() && run.children.containsKey(event.pid.toInt()))
                requireIdentity(requireNotNull(run.children[event.pid.toInt()]), event)
            }
            "PHASE_DONE" -> {
                // PHASE_DONE is emitted with the just-reaped client identity.
                // It is not a second supervisor completion event.
                require(event.role == "client" && event.phase == ForeverWinePhase.CLIENT)
                require(run.clientPid == event.pid.toInt())
                val identity = requireNotNull(run.resolvedChildren[event.pid.toInt()])
                requireIdentity(identity, event)
                require(run.childRoles[event.pid.toInt()] == "client")
                require(run.serverPid != null && run.children.containsKey(run.serverPid)) {
                    "client phase completed without a live server"
                }
                if (event.status == "PASS" && run.resolvedExitCodes[event.pid.toInt()] == 0) {
                    require(event.childCount == run.children.size.toLong())
                    run.clientPass = true
                } else {
                    run.clientPhaseUnknown = true
                    run.clientPass = false
                    run.protocolError = run.protocolError ?: "client phase is UNKNOWN"
                }
            }
            "SPAWN" -> {
                require(event.role in setOf("server", "client") && event.pid > 0 && event.ppid == run.supervisorIdentity!!.pid.toLong())
                val identity = identityFromEvent(event)
                require(run.children.put(event.pid.toInt(), identity) == null)
                require(run.childRoles.put(event.pid.toInt(), event.role) == null)
                if (event.role == "server") {
                    require(run.serverPid == null)
                    run.serverPid = event.pid.toInt()
                } else {
                    require(run.clientPid == null)
                    run.clientPid = event.pid.toInt()
                }
            }
            "OWNED_ADOPT" -> {
                require(event.role == "adopted" && event.ppid == run.supervisorIdentity!!.pid.toLong())
                val identity = identityFromEvent(event)
                val prior = run.children[event.pid.toInt()]
                if (prior != null) require(prior.startTicks == identity.startTicks && prior.uids == identity.uids)
                else require(event.pid.toInt() !in run.resolvedChildren)
                run.children[event.pid.toInt()] = identity
                run.childRoles[event.pid.toInt()] = "adopted"
            }
            "EXIT" -> {
                val identity = requireNotNull(run.children.remove(event.pid.toInt()))
                requireIdentity(identity, event); require(event.status == "REAPED" && event.exitCode >= 0)
                run.resolvedChildren[event.pid.toInt()] = identity
                run.resolvedExitCodes[event.pid.toInt()] = event.exitCode.toInt()
            }
        }
        require(event.childCount == run.children.size.toLong()) {
            "native childCount does not match Java-owned live children"
        }
        if (event.event == "ERROR") {
            run.nativeErrorSeen = true
            run.clientPhaseUnknown = true
            run.protocolError = run.protocolError ?: "native supervisor ERROR"
        }
        if (event.event == "RUN_DONE") run.runDone = event
    }

    private fun nextEvent(run: Run): ForeverWineEvent {
        val message = poll(run)
        require(message is ForeverWineReaderMessage.Frame)
        val event = ForeverWineEvent.parse(message.text, run.binding.wsRunId, run.binding.nonce)
        require(event.seq == run.eventSeq + 1L) { "Wine event sequence gap or duplicate" }
        require(event.prefixDev == run.prefixDev && event.prefixIno == run.prefixIno) {
            "Wine prefix identity changed during the RUN"
        }
        run.eventSeq = event.seq
        return event
    }

    private fun send(run: Run, kind: ForeverWineCommandKind, phase: ForeverWinePhase) {
        val job: Job<Unit>
        val deadline: Long
        synchronized(run.commandLock) {
            if (kind != ForeverWineCommandKind.START && run.terminationCommandSent) return
            require(!run.stdinPoisoned && !run.stdinClosed)
            require(!run.terminalizing && run.terminalStatus == null)
            require(kind != ForeverWineCommandKind.START || (!run.cancelRequested && run.outputFailure == null &&
                !run.terminationCommandSent &&
                clock.nanoTime() < minOf(run.overallDeadline, run.nativeDeadline)))
            if (kind != ForeverWineCommandKind.START) run.cleanupStarted = true
            if (kind != ForeverWineCommandKind.START) run.terminationCommandSent = true
            val command = ForeverWineCommand.create(run.seq++, run.binding.wsRunId, run.binding.nonce, kind, phase)
            if (kind == ForeverWineCommandKind.START) run.phase = phase
            val generation = run.commandGeneration
            val bytes = (command.json.toString() + "\n").toByteArray(Charsets.UTF_8)
            store.recordWineRawFrame(run.binding.runId, run.binding.wsRunId, run.binding.nonce, "native-stdin", bytes)
            run.writeInFlight = true
            job = submit(run, run.writerJobs) {
                val allowed = synchronized(run.registrationMonitor) {
                    !run.terminalizing && run.terminalStatus == null && !run.stdinPoisoned &&
                        generation == run.commandGeneration &&
                        (kind != ForeverWineCommandKind.START || !run.cancelRequested)
                }
                require(allowed) { "stale writer may not cross terminalization" }
                val output = requireNotNull(run.supervisor).outputStream
                output.write(bytes); output.flush()
            }
            deadline = if (kind == ForeverWineCommandKind.START) run.nativeDeadline else cleanupDeadline(run)
        }
        // Never await a writer while holding commandLock: cancellation must be
        // able to poison a blocked stream without first acquiring this lock.
        try { await(job, deadline) }
        catch (failure: Throwable) { run.stdinPoisoned = true; poisonInput(run); throw failure }
        finally { run.writeInFlight = false }
        if (kind != ForeverWineCommandKind.START) {
            run.stdinClosed = true
            runCatching { run.supervisor?.outputStream?.close() }
        }
    }

    private fun requestCooperativeStop(run: Run) {
        synchronized(run.commandLock) {
            run.stopRequestPending = true
            if (run.supervisor == null || run.stopRequestSubmitted || run.terminationCommandSent ||
                run.stdinPoisoned || run.stdinClosed) return
            run.stopRequestSubmitted = true
            val generation = run.commandGeneration
            val kind = if (run.phase == ForeverWinePhase.NONE) ForeverWineCommandKind.CLOSE else ForeverWineCommandKind.STOP
            run.terminationJob = submit(run, run.jobs) {
                val stale = generation != run.commandGeneration || !run.stopRequestPending ||
                    run.terminationCommandSent || run.stdinPoisoned || run.stdinClosed
                if (!stale) {
                    runCatching { send(run, kind, ForeverWinePhase.NONE) }
                        .onFailure { run.protocolError = run.protocolError ?: (it.message ?: "cooperative cancellation failed") }
                        .getOrThrow()
                }
                Unit
            }
        }
    }

    private fun cleanupAfterError(run: Run) {
        run.protocolError = run.protocolError ?: "controller error"
        run.cleanupStarted = true
        if (run.cleanupDeadlineNanos == 0L) run.cleanupDeadlineNanos = clock.nanoTime() + TimeUnit.SECONDS.toNanos(timeouts.cleanupSeconds)
        if (run.supervisor != null && !run.stdinPoisoned && !run.stdinClosed) {
            synchronized(run.commandLock) { run.stopRequestPending = true }
            runCatching { requestCooperativeStop(run) }
        }
        val deadline = cleanupDeadline(run)
        while (run.runDone == null && clock.nanoTime() < deadline) {
            val message = runCatching { poll(run, deadline) }.getOrNull() ?: break
            if (message is ForeverWineReaderMessage.Frame) {
                runCatching {
                    val event = ForeverWineEvent.parse(message.text, run.binding.wsRunId, run.binding.nonce)
                    require(event.seq == run.eventSeq + 1L) { "Wine cleanup event sequence gap" }
                    require(event.prefixDev == run.prefixDev && event.prefixIno == run.prefixIno) {
                        "Wine prefix identity changed during cleanup"
                    }
                    run.eventSeq = event.seq
                    consumeChild(run, event)
                    if (event.event == "RUN_DONE") run.runDone = event
                }.onFailure { run.protocolError = run.protocolError ?: (it.message ?: "invalid cleanup transcript") }
            }
        }
        runCatching { awaitSupervisor(run, false) }
        runCatching { finishWitness(run) }.onFailure {
            run.witness?.let(::closeLeafInput)
            terminateNonRoot(run.witness, run.witnessIdentity)
            armWitnessQuarantineDrain(run)
            quarantineLeaf(run.witnessWait, run.witnessReaderJobs, cleanupDeadline(run))
        }
        if (run.publisherWait?.completed != true) {
            run.publisher?.let(::closeLeafInput)
            terminateNonRoot(run.publisher, run.publisherIdentity)
            quarantineLeaf(run.publisherWait, run.publisherReaderJobs, cleanupDeadline(run))
        }
        runCatching {
            run.publisherWait?.let { await(it, cleanupDeadline(run)) }
            awaitAll(run.publisherReaderJobs, cleanupDeadline(run))
        }
        run.closureObserved = run.runDone != null && run.children.isEmpty() && run.runDone!!.echild &&
            run.supervisorExit == 0 && run.outputFailure == null && run.durabilityError == null
    }

    private fun finishWitness(run: Run) {
        val witness = run.witness ?: return
        require(run.witnessReady && run.witnessIdentity != null)
        require(clock.nanoTime() - run.witnessStartedNanos <=
            TimeUnit.SECONDS.toNanos(ForeverWineSupervisorPolicy.WITNESS_TTL_SECONDS)) {
            "witness proof exceeded fixture lifetime"
        }
        require(run.witnessWait?.completed != true) { "witness exited before Q" }
        require(kernel.read(run.witnessIdentity!!.pid).sameProcess(run.witnessIdentity!!))
        if (!run.witnessQSent) {
            val q = submit(run, run.writerJobs) {
                witness.outputStream.write("Q\n".toByteArray()); witness.outputStream.flush(); Unit
            }
            await(q, cleanupDeadline(run)); run.witnessQSent = true
        }
        val exit = await(requireNotNull(run.witnessWait), cleanupDeadline(run))
        require(exit == 0)
        // The reader job must observe EOF and no post-Q frame; process ownership is retained.
        await(requireNotNull(run.witnessDrain), cleanupDeadline(run))
        await(requireNotNull(run.witnessStderrDrain), cleanupDeadline(run))
        run.witnessInput?.close()
        run.witnessProof = true
    }

    private fun awaitSupervisor(run: Run, requireZero: Boolean) {
        val exit = await(requireNotNull(run.supervisorWait), cleanupDeadline(run))
        run.supervisorExit = exit
        if (requireZero) require(exit == 0)
        awaitAll(run.supervisorReaderJobs, cleanupDeadline(run))
        run.readerEof = true
    }

    private fun armLateSupervisorDrain(run: Run) {
        if (run.lateDrainJob != null) return
        run.lateDrainJob = submit(run, run.jobs) {
            val deadline = clock.nanoTime() + TimeUnit.SECONDS.toNanos(timeouts.watchdogSeconds)
            runCatching {
                await(requireNotNull(run.supervisorWait), deadline)
                awaitAll(run.supervisorReaderJobs, deadline)
            }.onFailure {
                run.protocolError = run.protocolError ?: (it.message ?: "late supervisor drain failed")
                // A late supervisor is never a qualification witness.  Its
                // stdin was poisoned above; native EOF/watchdog owns cleanup.
            }
            Unit
        }
    }

    private fun armLateWitnessDrain(run: Run, process: java.lang.Process) {
        run.witnessDrain = submit(run, run.witnessReaderJobs) {
            runCatching { process.inputStream.use { drainBounded(run, it, "witness-stdout") } }
                .onFailure { markOutputUnknown(run, it) }
            Unit
        }
        run.witnessStderrDrain = submit(run, run.witnessReaderJobs) {
            runCatching { process.errorStream.use { drainBounded(run, it, "witness-stderr") } }
                .onFailure { markOutputUnknown(run, it) }
            Unit
        }
    }

    private fun armWitnessQuarantineDrain(run: Run) {
        val process = run.witness ?: return
        if (run.witnessReaderJobs.any { !it.completed }) return
        run.witnessDrain = submit(run, run.witnessReaderJobs) {
            runCatching { (run.witnessInput ?: process.inputStream).use { drainBounded(run, it, "witness-stdout") } }
                .onFailure { markOutputUnknown(run, it) }
            Unit
        }
        run.witnessStderrDrain = submit(run, run.witnessReaderJobs) {
            runCatching { process.errorStream.use { drainBounded(run, it, "witness-stderr") } }
                .onFailure { markOutputUnknown(run, it) }
            Unit
        }
    }

    private fun closeLeafInput(process: java.lang.Process) {
        runCatching { process.outputStream.close() }
    }

    private fun complete(run: Run, result: String) {
        val status = when (result) { "PASS" -> "PASS"; "CANCELLED" -> "CANCELLED"; else -> "UNKNOWN" }
        // The generic RUN state is prepared and fsynced first.  wine-result is
        // the final positive commit; a fault at any earlier boundary leaves no
        // durable protocol result, and a fault here leaves admission blocked.
        val durable = synchronized(run.registrationMonitor) {
            // Close the creation gate before the first generic terminal write.
            // A factory already in flight will register as late and be drained.
            run.terminalStatus = status
            runCatching {
                store.update(run.binding.runId) {
                    it.copy(stage = ForeverStage.EXITED, terminationUnknown = status == "UNKNOWN",
                        quiescenceConfirmed = run.closureObserved)
                }
                store.finish(run.binding.runId,
                    when (status) { "PASS" -> ForeverRunStatus.EXITED; "CANCELLED" -> ForeverRunStatus.CANCELLED; else -> ForeverRunStatus.FAILED },
                    run.protocolError ?: status)
                store.writeWineResult(run.binding.runId, run.binding.wsRunId, run.binding.nonce,
                    ForeverRunStore.WineResult(
                        terminalStatus = status,
                        nativeStatus = run.runDone?.status ?: "UNKNOWN",
                        closureObserved = run.closureObserved,
                        qualification = run.qualification,
                        clientPass = run.clientPass,
                        supervisorExit = run.supervisorExit,
                        echild = run.runDone?.echild == true,
                        witnessProof = ForeverRunStore.WineWitnessProof(
                            status = if (run.witnessProof) "PASS" else "UNKNOWN",
                            stableBeforeQ = run.witnessProof,
                            qSent = run.witnessQSent,
                            reaped = run.witnessProof,
                            exit = if (run.witnessProof) 0 else -1,
                        ),
                        error = run.protocolError ?: if (status == "UNKNOWN") "UNKNOWN" else null,
                    ))
                true
            }.getOrElse { run.durabilityError = it; false }
        }
        val publishedStatus = if (durable) status else "UNKNOWN"
        if (!durable) {
            run.qualification = false
            run.protocolError = run.protocolError ?: "terminal durability failed"
        }
        synchronized(run.registrationMonitor) {
            run.terminalStatus = publishedStatus
            run.terminalDurable = durable
        }
        synchronized(monitor) {
            if (active === run) {
                publish(ForeverWineSupervisorState(publishedStatus, run.phase.wire, "Wine control $publishedStatus.", run.protocolError, run.binding.wsRunId,
                    run.closureObserved, run.qualification && durable))
                if (durable) maybeReleaseActive(run)
            }
        }
    }

    private fun startReader(
        run: Run,
        process: java.lang.Process,
        group: CopyOnWriteArrayList<Job<*>>,
        stdoutDirection: String,
        stderrDirection: String,
    ) {
        submit(run, group) {
            val input = BufferedInputStream(process.inputStream)
            try {
                if (stdoutDirection == "publisher-stdout") {
                    // Publisher output is a diagnostic stream, not schema2 IPC.
                    // Never enqueue its JSON report as a Wine supervisor frame.
                    drainBounded(run, input, stdoutDirection)
                    return@submit Unit
                }
                while (true) {
                    val bytes = readFrame(input) ?: break
                    if (!isQuarantine(run)) {
                        if (!acceptOutput(run, stdoutDirection, bytes.size)) return@submit Unit
                        store.recordWineRawFrame(run.binding.runId, run.binding.wsRunId, run.binding.nonce, stdoutDirection, bytes)
                        run.queue.put(ForeverWineReaderMessage.Frame(bytes, String(bytes, 0, bytes.size - 1, Charsets.UTF_8)))
                    }
                }
                run.readerEof = true
                if (!isQuarantine(run)) run.queue.put(ForeverWineReaderMessage.Eof)
            } catch (failure: Throwable) {
                markOutputUnknown(run, failure)
                if (!isQuarantine(run)) run.queue.put(ForeverWineReaderMessage.Failure(failure))
                throw failure
            } finally { input.close() }
            Unit
        }
        submit(run, group) {
            try { process.errorStream.use { drainBounded(run, it, stderrDirection) }; Unit }
            catch (failure: Throwable) { markOutputUnknown(run, failure); throw failure }
        }
    }

    private fun readOneWitness(run: Run, process: java.lang.Process, ns: String): String {
        val input = BufferedInputStream(process.inputStream); run.witnessInput = input
        val bytes = readFrame(input) ?: error("witness EOF before READY")
        if (!acceptOutput(run, "witness-stdout", bytes.size)) error("witness output overflow")
        store.recordWineRawFrame(run.binding.runId, run.binding.wsRunId, run.binding.nonce, "witness-stdout", bytes)
        return String(bytes, 0, bytes.size - 1, Charsets.UTF_8)
    }

    private fun installWait(run: Run, process: java.lang.Process, witness: Boolean): Job<Int> = submit(run, run.waitJobs) {
        val exit = process.waitFor()
        if (witness) Unit else Unit
        exit
    }

    private fun readFrame(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (output.size() == 0) null else error("truncated Wine frame")
            require(value != '\r'.code)
            output.write(value)
            require(output.size() <= ForeverWineSupervisorPolicy.MAX_LINE_BYTES)
            if (value == '\n'.code) return output.toByteArray()
        }
    }

    private fun drainBounded(run: Run, input: InputStream, direction: String) {
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0 && !isQuarantine(run)) {
                if (!acceptOutput(run, direction, count)) return
                store.recordWineRawOutput(
                    run.binding.runId, run.binding.wsRunId, run.binding.nonce, direction, buffer.copyOf(count),
                )
            }
        }
    }

    private fun acceptOutput(run: Run, direction: String, bytes: Int): Boolean {
        synchronized(run.outputLock) {
            val stream = (run.outputBytesByDirection[direction] ?: 0) + bytes
            val combined = run.combinedOutputBytes + bytes
            run.outputBytesByDirection[direction] = stream
            run.combinedOutputBytes = combined
            if (stream > ForeverWineSupervisorPolicy.MAX_STREAM_BYTES || combined > ForeverWineSupervisorPolicy.MAX_OUTPUT_BYTES) {
                markOutputUnknown(run, IllegalStateException("Wine output bound exceeded: $direction"))
                return false
            }
            return true
        }
    }

    private fun markOutputUnknown(run: Run, failure: Throwable) {
        val firstFailure = synchronized(run.outputLock) {
            val first = run.outputFailure == null
            run.outputFailure = run.outputFailure ?: failure
            first
        }
        run.clientPhaseUnknown = true
        if (firstFailure) {
            // Output overflow is a terminal protocol failure, not a reason to
            // keep a native writer or reader alive until the normal deadline.
            // Poison only the exact held stdin handle when a writer is active;
            // otherwise request the native STOP/CLOSE handshake.  Cleanup still
            // requires the real supervisor RUN_DONE/echild evidence.
            if (run.writeInFlight || run.writerJobs.any { !it.completed }) poisonInput(run)
            runCatching { requestCooperativeStop(run) }
        }
    }

    private fun isQuarantine(run: Run): Boolean = run.lateProcess || run.terminalStatus != null

    private fun readOwned(pid: Int, expectedPpid: Int): ForeverNativeProcIdentity {
        val identity = kernel.read(pid)
        require(identity.pid == pid && identity.ppid == expectedPpid && identity.uids.size == 4 &&
            identity.uids.all { it == AndroidProcess.myUid() })
        return identity
    }

    private fun quarantineLeaf(wait: Job<Int>?, readers: Collection<Job<*>>, deadline: Long) {
        runCatching { wait?.let { await(it, deadline) } }
        runCatching { awaitAll(readers, deadline) }
    }

    private fun terminateNonRoot(process: java.lang.Process?, identity: ForeverNativeProcIdentity?) {
        if (process == null || identity == null) return
        if (runCatching { kernel.read(identity.pid).sameProcess(identity) }.getOrDefault(false)) {
            // Only an exact held publisher/witness handle may be terminated;
            // this helper is never used for the native supervisor root.
            runCatching { process.destroy() }
        }
    }

    private fun identityFromEvent(event: ForeverWineEvent): ForeverNativeProcIdentity {
        require(event.pid > 0 && event.startTicks > 0 && event.ppid > 0)
        val uids = (0..3).map { event.json.getLong("uid$it").also { value -> require(value >= 0 && value <= Int.MAX_VALUE) }.toInt() }
        require(uids.distinct().size == 1)
        return ForeverNativeProcIdentity(event.pid.toInt(), event.startTicks, event.ppid.toInt(), uids)
    }

    private fun requireIdentity(identity: ForeverNativeProcIdentity?, event: ForeverWineEvent) {
        val value = requireNotNull(identity)
        require(value.pid == event.pid.toInt() && value.startTicks == event.startTicks && value.ppid.toLong() == event.ppid)
        require((0..3).all { event.json.getLong("uid$it") == value.uids[it].toLong() })
    }

    private fun poisonInput(run: Run) {
        if (run.stdinPoisoned) return
        run.stdinPoisoned = true
        // Closing the exact held Java handle is intentionally lock-free.  This
        // is the cancellation escape hatch for a writer blocked in write().
        runCatching { run.supervisor?.outputStream?.close() }
    }

    private fun <T> submit(run: Run, bucket: CopyOnWriteArrayList<Job<*>>, work: () -> T): Job<T> {
        val job = Job<T>(); bucket += job; run.jobs += job
        job.future = executor.submit<T> {
            try { work().also { job.value = it } }
            catch (failure: Throwable) { job.failure = failure; throw failure }
            finally {
                job.completed = true
                job.done.countDown()
                maybeReleaseActive(run)
            }
        }
        return job
    }

    private fun maybeReleaseActive(run: Run) {
        if (run.terminalDurable && allJobsCompleted(run)) {
            synchronized(monitor) {
                if (active === run) {
                    active = null
                    run.completionLatch.countDown()
                }
            }
        }
    }

    private fun <T> await(job: Job<T>, deadline: Long): T {
        val remaining = deadline - clock.nanoTime(); require(remaining > 0) { "Wine deadline exceeded" }
        require(job.done.await(remaining, TimeUnit.NANOSECONDS)) { "Wine job timeout" }
        job.failure?.let { throw it }
        return requireNotNull(job.value)
    }

    private fun awaitAll(jobs: Collection<Job<*>>, deadline: Long) {
        jobs.forEach {
            try { await(it, deadline); require(it.completed) }
            catch (failure: Throwable) { throw failure }
        }
    }
    private fun poll(run: Run, deadlineOverride: Long? = null): ForeverWineReaderMessage {
        val runDeadline = if (run.cleanupStarted) cleanupDeadline(run) else if (run.nativeDeadline > 0L) run.nativeDeadline else run.overallDeadline
        val deadline = deadlineOverride?.let { minOf(it, runDeadline) } ?: runDeadline
        val remaining = deadline - clock.nanoTime()
        require(remaining > 0L) { "Wine supervisor timeout" }
        return run.queue.poll(remaining, TimeUnit.NANOSECONDS)
        ?: error("Wine supervisor timeout")
    }
    private fun cleanupDeadline(run: Run): Long {
        if (run.cleanupDeadlineNanos == 0L) run.cleanupDeadlineNanos = clock.nanoTime() + TimeUnit.SECONDS.toNanos(timeouts.cleanupSeconds)
        return run.cleanupDeadlineNanos
    }
    private fun allJobsCompleted(run: Run) = run.jobs.all { it.completed }
    private fun publish(value: ForeverWineSupervisorState) { _state.value = value; listeners.forEach { runCatching { it(value) } } }
    private fun randomHex(bytes: Int) = ByteArray(bytes / 2).also { java.security.SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
}
