package app.gamenative

import android.content.Context
import android.os.Process as AndroidProcess
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal data class ForeverNativeProcIdentity(
    val pid: Int,
    val startTicks: Long,
    val ppid: Int,
    val uids: List<Int>,
) {
    init { require(pid > 0 && startTicks > 0 && ppid > 0 && uids.size == 4 && uids.all { it >= 0 }) }
    fun isAppOwned(appUid: Int): Boolean = uids.all { it == appUid }
    fun sameProcess(other: ForeverNativeProcIdentity): Boolean =
        pid == other.pid && startTicks == other.startTicks && uids == other.uids
}

internal interface ForeverNativeKernel { fun read(pid: Int): ForeverNativeProcIdentity }

/** Reads stat/status twice so a PID reuse or a cross-file race cannot form an identity. */
internal object ForeverNativeRealKernel : ForeverNativeKernel {
    override fun read(pid: Int): ForeverNativeProcIdentity {
        require(pid > 0)
        var previous: ForeverNativeProcIdentity? = null
        repeat(3) {
            val current = readOnce(pid)
            if (previous == current) return current
            previous = current
        }
        error("/proc identity was not coherent")
    }

    private fun readOnce(pid: Int): ForeverNativeProcIdentity {
        val stat = bounded(File("/proc/$pid/stat"), 8 * 1024).toString(Charsets.US_ASCII).trimEnd('\n')
        val firstSpace = stat.indexOf(' ')
        val closeName = stat.lastIndexOf(") ")
        require(firstSpace > 0 && closeName > firstSpace)
        require(stat.substring(0, firstSpace).toIntOrNull() == pid)
        val fields = stat.substring(closeName + 2).trim().split(Regex("\\s+"))
        require(fields.size > 19)
        val ppid = fields[1].toLongOrNull() ?: error("invalid proc ppid")
        val ticks = fields[19].toLongOrNull() ?: error("invalid proc start ticks")
        require(ppid in 1..Int.MAX_VALUE && ticks > 0)
        val uidLine = bounded(File("/proc/$pid/status"), 8 * 1024).toString(Charsets.US_ASCII)
            .lineSequence().firstOrNull { it.startsWith("Uid:") } ?: error("proc Uid unavailable")
        val uidParts = uidLine.substringAfter("Uid:").trim().split(Regex("\\s+"))
        require(uidParts.size == 4)
        val uids = uidParts.map { it.toLongOrNull()?.let { value -> require(value in 0..Int.MAX_VALUE); value.toInt() } ?: error("invalid proc uid") }
        return ForeverNativeProcIdentity(pid, ticks, ppid.toInt(), uids)
    }

    private fun bounded(file: File, limit: Int): ByteArray {
        file.inputStream().use { input ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count > 0 && result.size() + count <= limit)
                result.write(buffer, 0, count)
            }
            return result.toByteArray()
        }
    }
}

/** PID is accepted only from the Java Process implementation, never from a name or UID. */
internal object ForeverNativeProcessPid {
    fun read(process: java.lang.Process): Int {
        var type: Class<*>? = process.javaClass
        while (type != null) {
            val value = runCatching {
                type!!.getDeclaredField("pid").also { it.isAccessible = true }.get(process)
            }.getOrNull()
            if (value is Number && value.toLong() in 1..Int.MAX_VALUE) return value.toInt()
            type = type.superclass
        }
        var methodType: Class<*>? = process.javaClass
        while (methodType != null) {
            val method = runCatching { methodType!!.getDeclaredMethod("pid").also { it.isAccessible = true } }.getOrNull()
            val value = runCatching { method?.invoke(process) }.getOrNull()
            if (value is Number && value.toLong() in 1..Int.MAX_VALUE) return value.toInt()
            methodType = methodType.superclass
        }
        error("Java Process PID is unavailable; refusing fallback")
    }
}

internal interface ForeverNativeProcessFactory {
    fun startSupervisor(argv: Array<String>): java.lang.Process
    fun startWitness(argv: Array<String>): java.lang.Process
}

internal object ForeverNativeRealProcessFactory : ForeverNativeProcessFactory {
    private fun start(argv: Array<String>): java.lang.Process = ProcessBuilder(*argv).apply {
        environment().clear()
        redirectErrorStream(false)
    }.start()
    override fun startSupervisor(argv: Array<String>) = start(argv)
    override fun startWitness(argv: Array<String>) = start(argv)
}

internal interface ForeverNativeClock { fun nanoTime(): Long; fun currentTimeMillis(): Long }
internal object ForeverNativeSystemClock : ForeverNativeClock {
    override fun nanoTime(): Long = System.nanoTime()
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

data class ForeverNativeSupervisorState(
    val runId: String? = null,
    val scenario: String? = null,
    val status: String = "IDLE",
    val phase: Int = 0,
    val message: String = "Preparado; requiere consentimiento explícito.",
    val error: String? = null,
    val supervisorPid: Int? = null,
    val childCount: Int = 0,
)

internal sealed class ForeverNativeReaderMessage {
    data class Line(val value: String) : ForeverNativeReaderMessage()
    data class Failure(val error: Throwable) : ForeverNativeReaderMessage()
    object Eof : ForeverNativeReaderMessage()
}

internal data class ForeverNativeCommand(
    val json: JSONObject,
    val seq: Long,
    val phase: Int,
    val command: String,
    val scenario: String,
    val pid: Int,
    val startTicks: Long,
    val eventSeq: Long,
) {
    companion object {
        private val KEYS = setOf("schema", "runId", "nonce", "seq", "phase", "command", "scenario", "pid", "startTicks", "eventSeq")
        private val COMMANDS = setOf("START", "ACK", "STOP", "CLOSE")
        private val SCENARIOS = setOf("normal", "hold", "none")

        fun parse(line: String, runId: String, nonce: String): ForeverNativeCommand {
            val json = ForeverNativeStrictJson.objectOf(line)
            require(json.keys().asSequence().toSet() == KEYS)
            require(long(json, "schema") == 1L && json.getString("runId") == runId && json.getString("nonce") == nonce)
            val seq = long(json, "seq", positive = true)
            val phase = boundedInt(json, "phase", 0, 2)
            val command = string(json, "command"); require(command in COMMANDS)
            val scenario = string(json, "scenario"); require(scenario in SCENARIOS)
            val pid = boundedInt(json, "pid", 0, Int.MAX_VALUE)
            val ticks = long(json, "startTicks", min = 0)
            val eventSeq = long(json, "eventSeq", min = 0)
            when (command) {
                "START" -> require(phase in 1..2 && scenario != "none" && pid == 0 && ticks == 0L && eventSeq == 0L)
                "ACK" -> require(phase in 1..2 && scenario == "none" && pid > 0 && ticks > 0 && eventSeq > 0)
                "STOP" -> require(scenario == "none" && pid == 0 && ticks == 0L && eventSeq == 0L)
                "CLOSE" -> require(phase == 0 && scenario == "none" && pid == 0 && ticks == 0L && eventSeq == 0L)
            }
            return ForeverNativeCommand(json, seq, phase, command, scenario, pid, ticks, eventSeq)
        }

        private fun string(json: JSONObject, key: String): String = json.get(key).also { require(it is String) } as String
        private fun long(json: JSONObject, key: String, min: Long = Long.MIN_VALUE, positive: Boolean = false): Long {
            val result = ForeverNativeStrictJson.longValue(json.get(key), key)
            require(result >= min && (!positive || result > 0))
            return result
        }
        private fun boundedInt(json: JSONObject, key: String, min: Int, max: Int): Int {
            val value = long(json, key, min.toLong()); require(value <= max); return value.toInt()
        }
    }
}

internal data class ForeverNativeEvent(
    val json: JSONObject,
    val seq: Long,
    val phase: Int,
    val event: String,
    val pid: Int,
    val startTicks: Long,
    val ppid: Int,
    val uids: List<Int>,
    val role: String,
    val status: String,
    val exitCode: Int,
    val childCount: Int,
    val echild: Boolean,
    val setResult: Int,
    val getResult: Int,
    val getValue: Int,
) {
    companion object {
        private val KEYS = setOf("schema", "runId", "nonce", "seq", "phase", "event", "pid", "startTicks", "ppid",
            "uid0", "uid1", "uid2", "uid3", "role", "status", "exitCode", "childCount", "echild", "setResult", "getResult", "getValue")
        private val EVENTS = setOf("READY", "SPAWN", "ADOPT", "EXIT", "PHASE_DONE", "RUN_DONE", "ERROR")
        private val ROLES = setOf("supervisor", "intermediate", "grandchild", "witness")
        private val STATUSES = setOf("READY", "RUNNING", "REAPED", "PASS", "CANCELLED", "UNKNOWN", "ERROR")

        fun parse(line: String, runId: String, nonce: String): ForeverNativeEvent {
            require(line.isNotEmpty() && '\r' !in line)
            val json = ForeverNativeStrictJson.objectOf(line)
            require(json.keys().asSequence().toSet() == KEYS)
            require(long(json, "schema") == 1L && json.getString("runId") == runId && json.getString("nonce") == nonce)
            val event = string(json, "event"); require(event in EVENTS)
            val role = string(json, "role"); require(role in ROLES)
            val status = string(json, "status"); require(status in STATUSES)
            val seq = long(json, "seq", positive = true)
            val phase = boundedInt(json, "phase", 0, 2)
            val pid = boundedInt(json, "pid", 1, Int.MAX_VALUE)
            val ticks = long(json, "startTicks", min = 1, positive = true)
            val ppid = boundedInt(json, "ppid", 1, Int.MAX_VALUE)
            val uids = (0..3).map { boundedInt(json, "uid$it", 0, Int.MAX_VALUE) }
            require(uids.distinct().size == 1) { "UID tuple is not coherent" }
            val exit = boundedInt(json, "exitCode", Int.MIN_VALUE, Int.MAX_VALUE)
            val count = boundedInt(json, "childCount", 0, Int.MAX_VALUE)
            require(json.get("echild") is Boolean)
            val set = boundedInt(json, "setResult", Int.MIN_VALUE, Int.MAX_VALUE)
            val get = boundedInt(json, "getResult", Int.MIN_VALUE, Int.MAX_VALUE)
            val value = boundedInt(json, "getValue", Int.MIN_VALUE, Int.MAX_VALUE)
            if (event == "READY") require(role == "supervisor" || role == "witness")
            if (role == "witness") require(event == "READY")
            if (event == "READY" && role == "supervisor") require(set == 0 && get == 0 && value == 1)
            if (event == "READY" && role == "witness") require(set == -1 && get == -1 && value == -1)
            if (event != "READY") require(set == -1 && get == -1 && value == -1)
            if (event != "EXIT") require(exit == -1)
            if (event == "SPAWN" || event == "ADOPT") require(role == "intermediate" || role == "grandchild")
            if (event == "PHASE_DONE") require(role == "supervisor" && status == "PASS" && phase in 1..2 && count == 0 && json.getBoolean("echild"))
            if (event == "RUN_DONE") require(role == "supervisor" && phase == 0 && count == 0 && json.getBoolean("echild"))
            return ForeverNativeEvent(json, seq, phase, event, pid, ticks, ppid, uids, role, status, exit, count,
                json.getBoolean("echild"), set, get, value)
        }

        private fun string(json: JSONObject, key: String): String = json.get(key).also { require(it is String) } as String
        private fun long(json: JSONObject, key: String, min: Long = Long.MIN_VALUE, positive: Boolean = false): Long {
            val result = ForeverNativeStrictJson.longValue(json.get(key), key)
            require(result >= min && (!positive || result > 0)); return result
        }
        private fun boundedInt(json: JSONObject, key: String, min: Int, max: Int): Int {
            val value = long(json, key, min.toLong()); require(value <= max); return value.toInt()
        }
    }
}

/** A strict flat-object parser shared by both IPC directions. */
internal object ForeverNativeStrictJson {
    fun objectOf(text: String): JSONObject {
        require(text.toByteArray(Charsets.UTF_8).size <= ForeverNativeSupervisorPolicy.MAX_LINE_BYTES)
        val keys = LinkedHashSet<String>()
        var index = skip(text, 0)
        require(index < text.length && text[index] == '{'); index++
        while (true) {
            index = skip(text, index)
            if (index < text.length && text[index] == '}') { index++; break }
            require(index < text.length && text[index] == '"')
            val end = quotedEnd(text, index)
            val key = org.json.JSONTokener(text.substring(index, end + 1)).nextValue()
            require(key is String && key.matches(Regex("[A-Za-z][A-Za-z0-9]*")) && keys.add(key))
            index = skip(text, end + 1); require(index < text.length && text[index] == ':')
            index = skipValue(text, index + 1)
            index = skip(text, index)
            require(index < text.length && (text[index] == ',' || text[index] == '}'))
            if (text[index] == '}') { index++; break }; index++
        }
        require(skip(text, index) == text.length)
        return JSONObject(text)
    }

    fun longValue(value: Any?, key: String): Long {
        require(value is Number && value !is Float && value !is Double) { "$key is not an integer" }
        val text = value.toString(); require(text.matches(Regex("-?[0-9]+")))
        return text.toLongOrNull() ?: error("$key is out of range")
    }

    private fun skip(text: String, start: Int): Int { var i = start; while (i < text.length && text[i].isWhitespace()) i++; return i }
    private fun quotedEnd(text: String, start: Int): Int {
        var escaped = false
        for (i in start + 1 until text.length) {
            val c = text[i]
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') return i
        }
        error("unterminated JSON string")
    }
    private fun skipValue(text: String, start: Int): Int {
        var i = skip(text, start); require(i < text.length)
        if (text[i] == '"') return quotedEnd(text, i) + 1
        if (text[i] == '{' || text[i] == '[') {
            val open = text[i]; val close = if (open == '{') '}' else ']'; var depth = 0; var quote = false; var escaped = false
            while (i < text.length) {
                val c = text[i]
                if (quote) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quote = false }
                else if (c == '"') quote = true else if (c == open) depth++ else if (c == close && --depth == 0) return i + 1
                i++
            }
            error("unterminated JSON value")
        }
        while (i < text.length && text[i] != ',' && text[i] != '}') i++
        return i
    }
}

class ForeverNativeSupervisor private constructor(private val appContext: Context, checkPolicy: Boolean) {
    companion object {
        private val instances = ConcurrentHashMap<String, ForeverNativeSupervisor>()
        fun get(context: Context): ForeverNativeSupervisor {
            val app = context.applicationContext
            ForeverNativeSupervisorPolicy.requireDebugProcOnly(app)
            return instances.computeIfAbsent(app.packageName) { ForeverNativeSupervisor(app, true) }
        }
    }

    internal constructor(
        context: Context,
        processFactory: ForeverNativeProcessFactory,
        kernel: ForeverNativeKernel,
        clock: ForeverNativeClock = ForeverNativeSystemClock,
        store: ForeverNativeSupervisorStore = ForeverNativeSupervisorStore.get(context),
        artifactResolver: (Context) -> ForeverNativeSupervisorPolicy.NativeArtifactPaths,
        executor: ExecutorService = Executors.newCachedThreadPool(),
        operationTimeoutNanos: Long = TimeUnit.SECONDS.toNanos(ForeverNativeSupervisorPolicy.OPERATION_TIMEOUT_SECONDS),
        cleanupTimeoutNanos: Long = TimeUnit.SECONDS.toNanos(ForeverNativeSupervisorPolicy.CLEANUP_TIMEOUT_SECONDS),
        allowSupervisorFailureForTests: Boolean = false,
        bootProvider: ForeverNativePrelaunchDisposition.BootIdProvider = ForeverNativePrelaunchDisposition.KernelBootIdProvider,
    ) : this(context.applicationContext, false) {
        this.processFactory = processFactory; this.kernel = kernel; this.clock = clock
        this.store = store; this.artifactResolver = artifactResolver; this.executor = executor
        this.operationTimeoutNanos = operationTimeoutNanos; this.cleanupTimeoutNanos = cleanupTimeoutNanos
        this.allowSupervisorFailureForTests = allowSupervisorFailureForTests
        store.bootIdProviderForTests = bootProvider
    }

    init { if (checkPolicy) require(ForeverNativeSupervisorPolicy.shouldIsolate(appContext)) }

    private var processFactory: ForeverNativeProcessFactory = ForeverNativeRealProcessFactory
    private var kernel: ForeverNativeKernel = ForeverNativeRealKernel
    private var clock: ForeverNativeClock = ForeverNativeSystemClock
    private var store: ForeverNativeSupervisorStore = ForeverNativeSupervisorStore.get(appContext)
    private var artifactResolver: (Context) -> ForeverNativeSupervisorPolicy.NativeArtifactPaths = { ForeverNativeSupervisorPolicy.resolve(it) }
    private var executor: ExecutorService = Executors.newCachedThreadPool()
    private var operationTimeoutNanos = TimeUnit.SECONDS.toNanos(ForeverNativeSupervisorPolicy.OPERATION_TIMEOUT_SECONDS)
    private var cleanupTimeoutNanos = TimeUnit.SECONDS.toNanos(ForeverNativeSupervisorPolicy.CLEANUP_TIMEOUT_SECONDS)
    private var allowSupervisorFailureForTests = false
    private val monitor = Any()
    private val generation = AtomicLong(0)
    private val _state = MutableStateFlow(ForeverNativeSupervisorState())
    val state: StateFlow<ForeverNativeSupervisorState> get() = _state.asStateFlow()
    private val listeners = CopyOnWriteArrayList<(ForeverNativeSupervisorState) -> Unit>()
    private var active: RunContext? = null

    private data class ChildRecord(val role: String, val birth: ForeverNativeProcIdentity, var identity: ForeverNativeProcIdentity, var adopted: Boolean)
    private class RunContext(val run: ForeverNativeSupervisorStore.Run, val scenario: ForeverNativeSupervisorPolicy.Scenario,
        val token: Long, val operationDeadline: Long, cleanupTimeoutNanos: Long) {
        val cleanupDeadline = operationDeadline + cleanupTimeoutNanos
        val queue: BlockingQueue<ForeverNativeReaderMessage> = LinkedBlockingQueue()
        val streams = CopyOnWriteArrayList<java.io.Closeable>()
        val supervisorStreams = CopyOnWriteArrayList<java.io.Closeable>()
        val witnessStreams = CopyOnWriteArrayList<java.io.Closeable>()
        val startJobs = CopyOnWriteArrayList<CompletionJob<*>>()
        val waitJobs = CopyOnWriteArrayList<CompletionJob<*>>()
        val readerJobs = CopyOnWriteArrayList<CompletionJob<*>>()
        val supervisorReaderJobs = CopyOnWriteArrayList<CompletionJob<*>>()
        val witnessReaderJobs = CopyOnWriteArrayList<CompletionJob<*>>()
        val writerJobs = CopyOnWriteArrayList<CompletionJob<*>>()
        val closerJobs = CopyOnWriteArrayList<CompletionJob<*>>()
        val commandLock = Any()
        val startPhases = mutableSetOf<Int>()
        val children = linkedMapOf<Int, ChildRecord>()
        val resolved = mutableListOf<ChildRecord>()
        var supervisor: java.lang.Process? = null; var witness: java.lang.Process? = null
        var supervisorWait: CompletionJob<Int>? = null; var witnessWait: CompletionJob<Int>? = null
        var supervisorIdentity: ForeverNativeProcIdentity? = null; var witnessIdentity: ForeverNativeProcIdentity? = null
        var nextCommandSeq = 1L; var seenEventSeq = 0L; var activePhase = 0
        val phaseDone = mutableSetOf<Int>(); var stopSent = false; var closeSent = false
        @Volatile var cancelRequested = false; @Volatile var cleanupStarted = false; @Volatile var supervisorReaped = false
        @Volatile var witnessReaped = false; @Volatile var supervisorExit: Int? = null; @Volatile var protocolError: String? = null
        @Volatile var stderrOverflow = false; @Volatile var durabilityError: Throwable? = null
        @Volatile var outputFailure: Throwable? = null
        @Volatile var supervisorStdoutEof = false; @Volatile var supervisorStderrEof = false; @Volatile var witnessStderrEof = false
        @Volatile var stdinPoisoned = false; @Volatile var stdinClosed = false
        @Volatile var writeInFlight = false
        var runDone: ForeverNativeEvent? = null
        var faultDestroyIssued = false
        var cancellationEligible = false
        var witnessReadInput: java.io.InputStream? = null
        var witnessReady: CompletionJob<String>? = null
        var witnessDrain: CompletionJob<Unit>? = null
        var witnessProof = JSONObject().put("status", "UNPROVEN")
        @Volatile var terminalStatus: String? = null; @Volatile var terminalDurable = false
    }

    /** A Future is only a handle.  These flags are set by the worker's finally block. */
    private class CompletionJob<T> {
        lateinit var future: Future<T>
        val completedLatch = CountDownLatch(1)
        @Volatile var completed = false
        @Volatile var value: T? = null
        @Volatile var failure: Throwable? = null
    }

    fun start(scenarioName: String, consent: Boolean): Boolean {
        require(consent) { "native supervisor requires consent" }
        val scenario = ForeverNativeSupervisorPolicy.scenario(scenarioName)
        if (scenario == ForeverNativeSupervisorPolicy.Scenario.SUPERVISOR_FAILURE && !allowSupervisorFailureForTests) {
            synchronized(monitor) {
                _state.value = ForeverNativeSupervisorState(status = "UNKNOWN",
                    message = "supervisor_failure is unsupported by the production controller.",
                    error = "test-only fault injection")
                notifyUi()
            }
            return false
        }
        synchronized(monitor) {
            if (active != null) return false
            val runId = "NS-" + randomHex(16); val nonce = randomHex(16)
            val run = try { store.begin(runId, nonce, scenario.wireName) }
            catch (failure: Throwable) {
                // UNKNOWN remains quarantined even after local jobs finish: their
                // completion is not proof that every native descendant was reaped.
                _state.value = _state.value.copy(status = "UNKNOWN",
                    message = "No se puede iniciar otro control: RUN previo no cerrado.",
                    error = failure.message ?: "durable RUN unavailable")
                notifyUi()
                return false
            }
            store.recordIntent(run, "RUN", 0, 0)
            store.setStatus(run, "STARTING")
            val ctx = RunContext(run, scenario, generation.incrementAndGet(), clock.nanoTime() + operationTimeoutNanos, cleanupTimeoutNanos)
            active = ctx; publish(ctx, "STARTING", "Preparando supervisor nativo…")
            executor.submit { execute(ctx) }
            return true
        }
    }

    fun cancel(): Boolean {
        val ctx = synchronized(monitor) {
            val current = active ?: return false
            if (current.terminalStatus != null) return true
            current.cancelRequested = true
            store.setStatus(current.run, "CANCELLING")
            publish(current, "CANCELLING", "Cancelación cooperativa; conservando ownership…")
            current
        }
        // A blocked START/STOP writer must not keep this API behind the monitor.  Closing
        // that one held pipe poisons the channel; it is not treated as writer completion.
        if (ctx.writeInFlight || ctx.writerJobs.any { !it.completed }) poisonStdin(ctx)
        return true
    }

    fun onActivityDestroyed(changingConfigurations: Boolean) { /* application owner deliberately outlives the Activity */ }
    internal fun addUiListener(listener: (ForeverNativeSupervisorState) -> Unit): () -> Unit { listeners += listener; listener(state.value); return { listeners.remove(listener) } }
    internal fun resetForTest() = synchronized(monitor) { require(active == null); _state.value = ForeverNativeSupervisorState() }

    private fun execute(ctx: RunContext) {
        var finalStatus = "UNKNOWN"
        try {
            val artifacts = artifactResolver(appContext)
            val directory = artifacts.nativeLibraryDir.canonicalFile
            require(directory == artifacts.supervisor.canonicalFile.parentFile && directory == artifacts.fixture.canonicalFile.parentFile)
            val argv = arrayOf(ForeverNativeSupervisorPolicy.LINKER, artifacts.supervisor.canonicalPath, "--run", ctx.run.runId,
                "--nonce", ctx.run.nonce, "--fixture", artifacts.fixture.canonicalPath)
            require(argv.size == 8 && argv[0] == ForeverNativeSupervisorPolicy.LINKER && argv[6] == "--fixture" &&
                argv[7] == artifacts.fixture.canonicalPath)
            val fixtureIdentity = artifacts.fixtureIdentity
            val start = startProcess(ctx, argv, false) {
                if (fixtureIdentity != null) ForeverNativeSupervisorPolicy.requireFixtureStable(artifacts.fixture, fixtureIdentity)
            }
            val supervisor = awaitCompletion(start, ctx.operationDeadline)
            attachSupervisor(ctx, supervisor)
            require(clock.nanoTime() <= ctx.operationDeadline)
            val supervisorPid = ForeverNativeProcessPid.read(supervisor)
            val root = readOwned(supervisorPid, AndroidProcess.myPid())
            ctx.supervisorIdentity = root; store.recordIdentity(ctx.run, "supervisor", root.pid, root.startTicks, root.ppid, root.uids)
            publish(ctx, "RUNNING", "Esperando READY del supervisor…", supervisorPid = root.pid)
            val ready = nextEvent(ctx, ctx.operationDeadline)
            require(ready.event == "READY" && ready.phase == 0 && ready.role == "supervisor" && ready.status == "READY")
            requireIdentity(ready, root)

            when (ctx.scenario) {
                ForeverNativeSupervisorPolicy.Scenario.CANCEL_BEFORE_START -> {
                    startWitness(ctx, artifacts.fixture.canonicalPath)
                    sendClose(ctx); awaitRunDone(ctx, ctx.cleanupDeadline)
                    require(ctx.runDone?.status == "CANCELLED"); awaitExit(ctx, ctx.cleanupDeadline, true)
                    finishWitness(ctx, false); finalStatus = "CANCELLED"
                }
                ForeverNativeSupervisorPolicy.Scenario.INVALID_PROTOCOL -> {
                    startWitness(ctx, artifacts.fixture.canonicalPath); sendInvalidProtocol(ctx)
                    awaitRunDone(ctx, ctx.cleanupDeadline); finishWitness(ctx, false); awaitExit(ctx, ctx.cleanupDeadline, false)
                    finalStatus = "UNKNOWN"
                }
                ForeverNativeSupervisorPolicy.Scenario.SUPERVISOR_FAILURE -> {
                    startWitness(ctx, artifacts.fixture.canonicalPath); sendStart(ctx, 1, "normal")
                    awaitFirstSpawnAndDestroy(ctx, root); awaitExit(ctx, ctx.cleanupDeadline, false)
                    finishWitness(ctx, false); finalStatus = "UNKNOWN"
                }
                else -> {
                    startWitness(ctx, artifacts.fixture.canonicalPath)
                    if (ctx.cancelRequested) {
                        sendClose(ctx); awaitRunDone(ctx, ctx.cleanupDeadline); require(ctx.runDone?.status == "CANCELLED")
                        awaitExit(ctx, ctx.cleanupDeadline, true); finishWitness(ctx, false)
                        finalStatus = "UNKNOWN"
                    } else {
                        sendStart(ctx, 1, ctx.scenario.nativeScenario)
                        if (ctx.scenario == ForeverNativeSupervisorPolicy.Scenario.EOF) {
                            closeStdin(ctx); awaitRunDone(ctx, ctx.cleanupDeadline); awaitExit(ctx, ctx.cleanupDeadline, false)
                            finishWitness(ctx, false); finalStatus = "UNKNOWN"
                        } else {
                            eventLoop(ctx, ctx.operationDeadline)
                            if (ctx.scenario == ForeverNativeSupervisorPolicy.Scenario.NORMAL) {
                                require(ctx.runDone?.status == "PASS" && ctx.phaseDone == setOf(1, 2) && ctx.closeSent)
                                awaitExit(ctx, ctx.cleanupDeadline, true); verifyWitness(ctx); finalStatus = "PASS"
                            } else {
                                require(ctx.runDone?.status == "CANCELLED" && ctx.cancellationEligible); awaitExit(ctx, ctx.cleanupDeadline, true)
                                finishWitness(ctx, false); finalStatus = "CANCELLED"
                            }
                        }
                    }
                }
            }
            require(!ctx.stderrOverflow && ctx.outputFailure == null && ctx.durabilityError == null) { "evidence durability/output failed" }
        } catch (failure: Throwable) {
            ctx.protocolError = ctx.protocolError ?: (failure.message ?: failure.javaClass.simpleName)
            finalStatus = "UNKNOWN"
            cleanupAfterFailure(ctx)
        } finally {
            if (ctx.scenario != ForeverNativeSupervisorPolicy.Scenario.NORMAL && finalStatus == "PASS") finalStatus = "UNKNOWN"
            var terminalStored = false
            try {
                store.finish(ctx.run, finalStatus, ctx.supervisorExit, ctx.witnessProof, ctx.runDone?.echild == true, ctx.protocolError)
                terminalStored = true
            } catch (failure: Throwable) {
                ctx.durabilityError = failure
                finalStatus = "UNKNOWN"
                ctx.protocolError = "durability failure"
                runCatching {
                    store.finishDurabilityUnknown(ctx.run, ctx.supervisorExit, ctx.witnessProof, ctx.runDone?.echild == true)
                }.onSuccess { terminalStored = true }
            }
            synchronized(monitor) {
                if (active?.token == ctx.token) {
                    ctx.terminalStatus = finalStatus
                    ctx.terminalDurable = terminalStored
                    _state.value = _state.value.copy(status = finalStatus, message = "Control $finalStatus.", error = ctx.protocolError)
                    notifyUi(); releaseIfFullyReaped(ctx)
                }
            }
        }
    }

    private fun sendStart(ctx: RunContext, phase: Int, scenario: String) = sendCommand(ctx, "START", phase, scenario, 0, 0, 0)
    private fun sendAck(ctx: RunContext, event: ForeverNativeEvent) = sendCommand(ctx, "ACK", event.phase, "none", event.pid, event.startTicks, event.seq)
    private fun sendStop(ctx: RunContext) {
        if (!ctx.stopSent) {
            if (ctx.children.values.any { it.role == "grandchild" }) ctx.cancellationEligible = true
            sendCommand(ctx, "STOP", ctx.activePhase, "none", 0, 0, 0)
        }
    }
    private fun sendClose(ctx: RunContext) { if (!ctx.closeSent) sendCommand(ctx, "CLOSE", 0, "none", 0, 0, 0) }

    private fun sendCommand(ctx: RunContext, command: String, phase: Int, scenario: String, pid: Int, ticks: Long, eventSeq: Long): Boolean {
        // The command lock serializes the main writer, but the monitor is never held
        // while a pipe write waits.  In particular, cancel() can poison a wedged pipe.
        synchronized(ctx.commandLock) {
            val line: ByteArray
            val deadline: Long
            synchronized(monitor) {
                require(active?.token == ctx.token)
                if (command == "START") {
                    require(!ctx.cancelRequested && clock.nanoTime() <= ctx.operationDeadline &&
                        phase !in ctx.startPhases && !ctx.closeSent && !ctx.stopSent)
                }
                require(!ctx.stdinPoisoned && !ctx.stdinClosed)
                deadline = if (command == "STOP" || command == "CLOSE") ctx.cleanupDeadline else ctx.operationDeadline
                require(clock.nanoTime() <= deadline)
                val json = JSONObject().put("schema", 1).put("runId", ctx.run.runId).put("nonce", ctx.run.nonce)
                    .put("seq", ctx.nextCommandSeq).put("phase", phase).put("command", command).put("scenario", scenario)
                    .put("pid", pid).put("startTicks", ticks).put("eventSeq", eventSeq)
                line = (json.toString() + "\n").toByteArray(Charsets.UTF_8)
                require(line.size <= ForeverNativeSupervisorPolicy.MAX_LINE_BYTES)
                ForeverNativeCommand.parse(json.toString(), ctx.run.runId, ctx.run.nonce)
                // Durable intent precedes the actual write, even if the writer later
                // times out and permanently poisons stdin.
                store.recordIntent(ctx.run, command, phase, ctx.nextCommandSeq)
                ctx.writeInFlight = true
            }
            writePipe(ctx, requireNotNull(ctx.supervisor), line, deadline, poisonOnFailure = true)
            synchronized(monitor) {
                require(active?.token == ctx.token)
                ctx.nextCommandSeq++
                when (command) { "START" -> { ctx.startPhases += phase; ctx.activePhase = phase }
                    "STOP" -> { ctx.stopSent = true; ctx.cleanupStarted = true }
                    "CLOSE" -> { ctx.closeSent = true; ctx.cleanupStarted = true } }
            }
            return true
        }
    }

    private fun sendInvalidProtocol(ctx: RunContext) {
        synchronized(ctx.commandLock) {
            val line: ByteArray
            val seq: Long
            synchronized(monitor) {
            require(!ctx.closeSent)
            val json = JSONObject().put("schema", 1).put("runId", ctx.run.runId).put("nonce", ctx.run.nonce.reversed())
                .put("seq", ctx.nextCommandSeq).put("phase", 1).put("command", "START").put("scenario", "normal")
                .put("pid", 0).put("startTicks", 0).put("eventSeq", 0)
            line = (json.toString() + "\n").toByteArray(Charsets.UTF_8); seq = ctx.nextCommandSeq
            store.recordFaultIntent(ctx.run, "invalid_protocol", "owned-pipe START nonce mismatch")
            ctx.writeInFlight = true
            }
            writePipe(ctx, requireNotNull(ctx.supervisor), line, ctx.operationDeadline, poisonOnFailure = true)
            synchronized(monitor) { ctx.nextCommandSeq = seq + 1 }
        }
    }

    private fun closeStdin(ctx: RunContext) {
        val shouldClose = synchronized(monitor) {
            if (ctx.stdinClosed) false else { ctx.stdinClosed = true; ctx.cleanupStarted = true; true }
        }
        if (shouldClose) closeOwned(ctx, requireNotNull(ctx.supervisor).outputStream)
    }

    private fun eventLoop(ctx: RunContext, deadline: Long) {
        while (ctx.runDone == null) {
            if (ctx.cancelRequested && ctx.startPhases.isNotEmpty() && !ctx.stopSent) sendStop(ctx)
            when (val message = poll(ctx, if (ctx.cleanupStarted) ctx.cleanupDeadline else deadline)) {
                is ForeverNativeReaderMessage.Line -> consumeEvent(ctx, message.value)
                is ForeverNativeReaderMessage.Failure -> error(message.error.message ?: "native stdout failed")
                ForeverNativeReaderMessage.Eof -> error("native supervisor EOF")
            }
        }
    }

    private fun consumeEvent(ctx: RunContext, line: String) {
        val event = ForeverNativeEvent.parse(line, ctx.run.runId, ctx.run.nonce)
        require(event.seq == ctx.seenEventSeq + 1) { "event sequence gap or duplicate" }
        ctx.seenEventSeq = event.seq
        store.recordEvent(ctx.run, event.json)
        handleEvent(ctx, event)
    }

    private fun handleEvent(ctx: RunContext, event: ForeverNativeEvent) {
        when (event.event) {
            "SPAWN" -> {
                require(event.phase == ctx.activePhase && event.status == "RUNNING" && event.pid !in ctx.children)
                val parent = when (event.role) {
                    "intermediate" -> requireNotNull(ctx.supervisorIdentity).pid
                    "grandchild" -> ctx.children.values.firstOrNull { it.role == "intermediate" && it.identity.pid == event.ppid }?.identity?.pid
                        ?: ctx.resolved.firstOrNull { it.role == "intermediate" && it.identity.pid == event.ppid }?.identity?.pid
                        ?: error("grandchild has no intermediate owner")
                    else -> error("invalid native child role")
                }
                require(event.ppid == parent)
                val identity = readOwned(event.pid, event.ppid); requireIdentity(event, identity)
                val record = ChildRecord(event.role, identity, identity, false)
                ctx.children[event.pid] = record; store.recordIdentity(ctx.run, event.role, identity.pid, identity.startTicks, identity.ppid, identity.uids)
                if (ctx.scenario == ForeverNativeSupervisorPolicy.Scenario.CANCEL_FORK && event.role == "grandchild") {
                    ctx.cancelRequested = true; sendStop(ctx) // deterministic: grandchild SPAWN is never ACKed
                } else if (!ctx.cancelRequested && !ctx.stopSent) sendAck(ctx, event)
            }
            "ADOPT" -> {
                val child = requireNotNull(ctx.children[event.pid]) { "ADOPT without registered SPAWN" }
                require(child.role == event.role && event.status == "RUNNING" && event.phase == ctx.activePhase)
                require(event.role == "grandchild" && event.ppid == requireNotNull(ctx.supervisorIdentity).pid)
                val identity = if (ctx.stopSent) {
                    require(child.birth.sameProcess(ForeverNativeProcIdentity(event.pid, event.startTicks, event.ppid, event.uids)))
                    ForeverNativeProcIdentity(event.pid, event.startTicks, event.ppid, event.uids)
                } else readOwned(event.pid, event.ppid)
                requireIdentity(event, identity)
                require(child.birth.sameProcess(identity) && child.identity.startTicks == event.startTicks && child.identity.uids == event.uids)
                child.identity = identity; child.adopted = true
                store.recordIdentity(ctx.run, event.role, identity.pid, identity.startTicks, identity.ppid, identity.uids)
                if (!ctx.cancelRequested && !ctx.stopSent) sendAck(ctx, event)
            }
            "EXIT" -> {
                val child = requireNotNull(ctx.children[event.pid]) { "EXIT for unknown child" }
                require(child.role == event.role && child.identity.pid == event.pid && child.identity.startTicks == event.startTicks &&
                    child.identity.ppid == event.ppid && child.identity.uids == event.uids && event.status == "REAPED" && event.exitCode >= 0)
                ctx.children.remove(event.pid); ctx.resolved += child
            }
            "PHASE_DONE" -> {
                val root = requireNotNull(ctx.supervisorIdentity)
                requireIdentity(event, root); require(event.phase == ctx.activePhase && event.childCount == 0 && event.echild && ctx.children.isEmpty())
                require(ctx.phaseDone.add(event.phase))
                publish(ctx, "RUNNING", "Fase ${event.phase} resuelta.", event.phase)
                if (event.phase == 1 && !ctx.cancelRequested) sendStart(ctx, 2, "normal")
                else if (event.phase == 2 && !ctx.cancelRequested) sendClose(ctx)
            }
            "RUN_DONE" -> {
                val root = requireNotNull(ctx.supervisorIdentity)
                requireIdentity(event, root); require(event.phase == 0 && event.childCount == 0 && event.echild && ctx.children.isEmpty())
                require(event.status != "PASS" || (ctx.scenario == ForeverNativeSupervisorPolicy.Scenario.NORMAL && ctx.phaseDone == setOf(1, 2) && ctx.closeSent))
                if (ctx.scenario != ForeverNativeSupervisorPolicy.Scenario.NORMAL) require(event.status != "PASS")
                ctx.runDone = event
            }
            "ERROR" -> error("native supervisor reported ERROR")
            "READY" -> error("duplicate READY")
        }
    }

    private fun awaitFirstSpawnAndDestroy(ctx: RunContext, root: ForeverNativeProcIdentity) {
        while (true) {
            when (val message = poll(ctx, ctx.operationDeadline)) {
                is ForeverNativeReaderMessage.Line -> {
                    val event = ForeverNativeEvent.parse(message.value, ctx.run.runId, ctx.run.nonce)
                    require(event.seq == ctx.seenEventSeq + 1); ctx.seenEventSeq = event.seq
                    store.recordEvent(ctx.run, event.json)
                    require(event.event == "SPAWN" && event.role == "intermediate")
                    val identity = readOwned(event.pid, event.ppid); requireIdentity(event, identity)
                    ctx.children[event.pid] = ChildRecord(event.role, identity, identity, false)
                    store.recordFaultIntent(ctx.run, "supervisor_failure", "destroy held Java Process after first SPAWN")
                    require(kernel.read(root.pid).sameProcess(root) && kernel.read(root.pid).ppid == root.ppid)
                    ctx.faultDestroyIssued = true
                    requireNotNull(ctx.supervisor).destroy() // the only allowed destroy path
                    return
                }
                is ForeverNativeReaderMessage.Failure -> error(message.error.message ?: "native output failed")
                ForeverNativeReaderMessage.Eof -> error("supervisor EOF before fault injection")
            }
        }
    }

    private fun awaitRunDone(ctx: RunContext, deadline: Long) {
        while (ctx.runDone == null) {
            when (val message = poll(ctx, deadline)) {
                is ForeverNativeReaderMessage.Line -> consumeEvent(ctx, message.value)
                is ForeverNativeReaderMessage.Failure -> error(message.error.message ?: "native output failed")
                ForeverNativeReaderMessage.Eof -> error("native supervisor EOF")
            }
        }
    }

    private fun nextEvent(ctx: RunContext, deadline: Long): ForeverNativeEvent {
        while (true) {
            when (val message = poll(ctx, deadline)) {
                is ForeverNativeReaderMessage.Line -> {
                    val event = ForeverNativeEvent.parse(message.value, ctx.run.runId, ctx.run.nonce)
                    require(event.seq == ctx.seenEventSeq + 1); ctx.seenEventSeq = event.seq; store.recordEvent(ctx.run, event.json); return event
                }
                is ForeverNativeReaderMessage.Failure -> error(message.error.message ?: "native output failed")
                ForeverNativeReaderMessage.Eof -> error("native supervisor EOF")
            }
        }
    }

    private fun poll(ctx: RunContext, deadline: Long): ForeverNativeReaderMessage {
        val remaining = deadline - clock.nanoTime(); require(remaining > 0) { "native deadline exceeded" }
        return ctx.queue.poll(remaining, TimeUnit.NANOSECONDS) ?: error("native supervisor timeout")
    }

    private fun readOwned(pid: Int, expectedPpid: Int): ForeverNativeProcIdentity {
        val identity = kernel.read(pid)
        require(identity.pid == pid && identity.ppid == expectedPpid && identity.isAppOwned(AndroidProcess.myUid())) { "unowned kernel identity" }
        require(identity.uids.size == 4)
        return identity
    }

    private fun requireIdentity(event: ForeverNativeEvent, identity: ForeverNativeProcIdentity) {
        require(event.pid == identity.pid && event.startTicks == identity.startTicks && event.ppid == identity.ppid && event.uids == identity.uids)
    }

    private fun <T> submitJob(ctx: RunContext, bucket: CopyOnWriteArrayList<CompletionJob<*>>, work: () -> T): CompletionJob<T> {
        val job = CompletionJob<T>(); bucket += job
        job.future = executor.submit<T> {
            try { work().also { job.value = it } }
            catch (failure: Throwable) { job.failure = failure; throw failure }
            finally {
                job.completed = true; job.completedLatch.countDown()
                synchronized(monitor) { releaseIfFullyReaped(ctx) }
            }
        }
        return job
    }

    private fun startProcess(ctx: RunContext, argv: Array<String>, witness: Boolean, beforeExec: () -> Unit = {}): CompletionJob<java.lang.Process> {
        // The marker is the durable boundary: once this returns, a factory may
        // be called on the worker.  A marker failure therefore prevents any
        // ambiguous Process creation and leaves the RUN UNKNOWN.
        store.recordProcessCreateIntent(ctx.run, if (witness) "witness" else "supervisor")
        return submitJob(ctx, ctx.startJobs) {
            beforeExec()
            val process = if (witness) processFactory.startWitness(argv) else processFactory.startSupervisor(argv)
            if (witness) attachWitness(ctx, process) else attachSupervisor(ctx, process)
            if (ctx.terminalStatus != null) {
                if (witness) lateWitnessCleanup(ctx) else lateSupervisorCleanup(ctx)
            }
            process
        }
    }

    private fun <T> awaitCompletion(job: CompletionJob<T>, deadline: Long): T {
        val remaining = deadline - clock.nanoTime(); require(remaining > 0) { "native deadline exceeded" }
        return try { job.future.get(remaining, TimeUnit.NANOSECONDS) }
        catch (failure: java.util.concurrent.ExecutionException) { throw (failure.cause ?: failure) }
    }

    private fun installWait(ctx: RunContext, process: java.lang.Process, witness: Boolean): CompletionJob<Int> {
        return submitJob(ctx, ctx.waitJobs) {
            val exit = process.waitFor()
            if (witness) ctx.witnessReaped = true else {
                ctx.supervisorReaped = true; ctx.supervisorExit = exit
                runCatching { store.recordNativeExit(ctx.run, exit) }.onFailure { ctx.durabilityError = it }
            }
            exit
        }
    }

    private fun awaitExit(ctx: RunContext, deadline: Long, requireZero: Boolean) {
        val wait = requireNotNull(ctx.supervisorWait)
        val exit = awaitCompletion(wait, deadline)
        if (requireZero) require(exit == 0) { "supervisor did not exit zero" }
        awaitSupervisorReaders(ctx, deadline)
        if (ctx.clockNow() >= deadline && !allJobsCompleted(ctx)) error("native cleanup jobs remain active")
        closeOwnedStreams(ctx, deadline, ctx.supervisorStreams)
        require(ctx.outputFailure == null && !ctx.stderrOverflow && ctx.durabilityError == null) { "native output was not durable" }
    }

    private fun RunContext.clockNow(): Long = clock.nanoTime()

    private fun startWitness(ctx: RunContext, fixture: String) {
        val argv = arrayOf(ForeverNativeSupervisorPolicy.LINKER, fixture, "--witness", ctx.run.runId, ctx.run.nonce)
        require(argv.size == 5 && argv[0] == ForeverNativeSupervisorPolicy.LINKER && argv[1] == fixture && argv[2] == "--witness")
        val witness = awaitCompletion(startProcess(ctx, argv, true), ctx.operationDeadline)
        require(clock.nanoTime() <= ctx.operationDeadline)
        val identity = requireNotNull(ctx.witnessIdentity)
        val ready = startWitnessReader(ctx, witness)
        val line = awaitCompletion(ready, ctx.operationDeadline)
        val event = ForeverNativeEvent.parse(line, ctx.run.runId, ctx.run.nonce)
        require(event.event == "READY" && event.role == "witness" && event.phase == 0 && event.status == "READY")
        requireIdentity(event, identity); ctx.witnessProof = JSONObject().put("status", "READY").put("pid", identity.pid).put("startTicks", identity.startTicks)
    }

    private fun startWitnessReader(ctx: RunContext, process: java.lang.Process): CompletionJob<String> {
        if (ctx.witnessReady != null) return requireNotNull(ctx.witnessReady)
        val ready = submitJob(ctx, ctx.readerJobs) { readWitnessLine(ctx, process) }
        ctx.witnessReady = ready
        ctx.witnessReaderJobs += ready
        return ready
    }

    /** READY is the first witness line; after Q the same held stream must reach EOF. */
    private fun startWitnessDrain(ctx: RunContext): CompletionJob<Unit> {
        if (ctx.witnessDrain != null) return requireNotNull(ctx.witnessDrain)
        val input = requireNotNull(ctx.witnessReadInput)
        val drain = submitJob(ctx, ctx.readerJobs) {
            val buffer = ByteArray(4096); var total = 0; var eof = false
            while (!eof) {
                val count = input.read(buffer)
                if (count < 0) { eof = true; continue }
                total += count
                if (total > ForeverNativeSupervisorPolicy.MAX_STREAM_BYTES) {
                    ctx.outputFailure = IllegalStateException("witness stdout overflow")
                } else if (count > 0) {
                    ctx.outputFailure = ctx.outputFailure ?: IllegalStateException("unexpected witness stdout after READY")
                }
            }
            Unit
        }
        ctx.witnessDrain = drain
        ctx.witnessReaderJobs += drain
        return drain
    }

    private fun readWitnessLine(ctx: RunContext, process: java.lang.Process): String {
        val input = BufferedInputStream(process.inputStream); ctx.witnessReadInput = input; ctx.streams += input; ctx.witnessStreams += input
        val output = ByteArrayOutputStream(); var total = 0
        while (true) {
            val byte = input.read(); require(byte >= 0) { "witness EOF before READY" }; total++; require(total <= ForeverNativeSupervisorPolicy.MAX_STREAM_BYTES)
            output.write(byte); require(output.size() <= ForeverNativeSupervisorPolicy.MAX_LINE_BYTES)
            if (byte == '\n'.code) { val bytes = output.toByteArray(); require(store.recordOutput(ctx.run, "witness-stdout", bytes)); return decodeUtf8(bytes.copyOf(bytes.size - 1)) }
        }
    }

    private fun verifyWitness(ctx: RunContext) { finishWitness(ctx, true) }
    private fun finishWitness(ctx: RunContext, pass: Boolean) {
        val wait = ctx.witnessWait ?: return; val identity = requireNotNull(ctx.witnessIdentity)
        require(!wait.completed && requireNotNull(ctx.witness).isAlive) { "witness exited before Q handshake" }
        require(kernel.read(identity.pid) == identity) { "witness identity changed" }
        writePipe(ctx, requireNotNull(ctx.witness), "Q\n".toByteArray(Charsets.US_ASCII), ctx.cleanupDeadline, poisonOnFailure = true)
        startWitnessDrain(ctx)
        val exit = awaitCompletion(wait, ctx.cleanupDeadline)
        require(exit == 0); ctx.witnessReaped = true
        awaitWitnessReaders(ctx, ctx.cleanupDeadline)
        closeOwnedStreams(ctx, ctx.cleanupDeadline, ctx.witnessStreams)
        require(ctx.outputFailure == null && !ctx.stderrOverflow) { "witness output was not durable" }
        ctx.witnessProof = JSONObject(ctx.witnessProof.toString()).put("status", "PASS")
            .put("stableBeforeQ", true).put("qSent", true).put("exit", exit).put("reaped", true)
    }

    private fun cleanupAfterFailure(ctx: RunContext) {
        runCatching {
            if (ctx.supervisor != null && !ctx.supervisorReaped) {
                if (ctx.runDone == null && !ctx.faultDestroyIssued && !ctx.stdinClosed && !ctx.stdinPoisoned) {
                    if (ctx.startPhases.isNotEmpty() && !ctx.stopSent && !ctx.closeSent) sendStop(ctx) else if (ctx.startPhases.isEmpty() && !ctx.closeSent) sendClose(ctx)
                }
                awaitExit(ctx, ctx.cleanupDeadline, false)
            }
        }.onFailure { ctx.protocolError = ctx.protocolError ?: "supervisor cleanup incomplete"; closeOwnedStreams(ctx, ctx.cleanupDeadline) }
        runCatching { if (ctx.witness != null && !ctx.witnessReaped) finishWitness(ctx, false) }
            .onFailure { ctx.protocolError = ctx.protocolError ?: "witness cleanup incomplete"; closeOwnedStreams(ctx, ctx.cleanupDeadline) }
    }

    private fun attachSupervisor(ctx: RunContext, process: java.lang.Process) {
        synchronized(ctx.commandLock) {
            if (ctx.supervisor != null) return
            ctx.supervisor = process; ctx.streams += process.outputStream; ctx.supervisorStreams += process.outputStream
            ctx.supervisorWait = installWait(ctx, process, false); startReaders(ctx, process)
            runCatching {
                val identity = readOwned(ForeverNativeProcessPid.read(process), AndroidProcess.myPid())
                ctx.supervisorIdentity = identity
                store.recordIdentity(ctx.run, "supervisor", identity.pid, identity.startTicks, identity.ppid, identity.uids)
            }
        }
    }

    private fun attachWitness(ctx: RunContext, process: java.lang.Process) {
        synchronized(ctx.commandLock) {
            if (ctx.witness != null) return
            ctx.witness = process; ctx.streams += process.outputStream; ctx.streams += process.errorStream
            ctx.witnessStreams += process.outputStream; ctx.witnessStreams += process.errorStream
            ctx.witnessWait = installWait(ctx, process, true)
            runCatching {
                val identity = readOwned(ForeverNativeProcessPid.read(process), AndroidProcess.myPid())
                ctx.witnessIdentity = identity
                store.recordIdentity(ctx.run, "witness", identity.pid, identity.startTicks, identity.ppid, identity.uids)
            }
            val stderr = submitJob(ctx, ctx.readerJobs) { readStderr(ctx, process.errorStream, "witness-stderr") }
            ctx.witnessReaderJobs += stderr
        }
    }

    private fun lateSupervisorCleanup(ctx: RunContext) {
        runCatching {
            if (ctx.supervisorIdentity != null && clock.nanoTime() <= ctx.cleanupDeadline && !ctx.closeSent && !ctx.stdinPoisoned) sendClose(ctx)
            else closeStdin(ctx)
            awaitExit(ctx, ctx.cleanupDeadline, false)
        }.onFailure { ctx.protocolError = ctx.protocolError ?: "late supervisor cleanup incomplete"; closeOwnedStreams(ctx, ctx.cleanupDeadline) }
    }

    private fun lateWitnessCleanup(ctx: RunContext) {
        runCatching {
            val witness = requireNotNull(ctx.witness)
            val ready = startWitnessReader(ctx, witness)
            val line = awaitCompletion(ready, ctx.cleanupDeadline)
            val event = ForeverNativeEvent.parse(line, ctx.run.runId, ctx.run.nonce)
            require(event.event == "READY" && event.role == "witness")
            require(kernel.read(requireNotNull(ctx.witnessIdentity).pid) == ctx.witnessIdentity)
            writePipe(ctx, witness, "Q\n".toByteArray(Charsets.US_ASCII), ctx.cleanupDeadline, poisonOnFailure = false)
            startWitnessDrain(ctx)
            awaitCompletion(requireNotNull(ctx.witnessWait), ctx.cleanupDeadline)
            awaitWitnessReaders(ctx, ctx.cleanupDeadline)
            closeOwnedStreams(ctx, ctx.cleanupDeadline, ctx.witnessStreams)
        }.onFailure { ctx.protocolError = ctx.protocolError ?: "late witness cleanup incomplete"; closeOwnedStreams(ctx, ctx.cleanupDeadline) }
    }

    private fun startReaders(ctx: RunContext, process: java.lang.Process) {
        ctx.streams += process.inputStream; ctx.streams += process.errorStream
        val stdout = submitJob(ctx, ctx.readerJobs) { readStdout(ctx, process.inputStream) }
        val stderr = submitJob(ctx, ctx.readerJobs) { readStderr(ctx, process.errorStream, "stderr") }
        ctx.supervisorReaderJobs += stdout; ctx.supervisorReaderJobs += stderr
    }

    private fun readStdout(ctx: RunContext, input: java.io.InputStream) {
        val stream = BufferedInputStream(input); ctx.streams += stream; ctx.supervisorStreams += stream; val line = ByteArrayOutputStream(); var total = 0
        try {
            while (true) {
                val byte = stream.read()
                if (byte < 0) { if (line.size() != 0) error("stdout EOF in middle of line"); ctx.supervisorStdoutEof = true; ctx.queue.put(ForeverNativeReaderMessage.Eof); return }
                total++; require(total <= ForeverNativeSupervisorPolicy.MAX_STREAM_BYTES); line.write(byte); require(line.size() <= ForeverNativeSupervisorPolicy.MAX_LINE_BYTES)
                if (byte == '\n'.code) { val bytes = line.toByteArray(); require(store.recordOutput(ctx.run, "stdout", bytes)); ctx.queue.put(ForeverNativeReaderMessage.Line(decodeUtf8(bytes.copyOf(bytes.size - 1)))); line.reset() }
            }
        } catch (failure: Throwable) { ctx.outputFailure = failure; ctx.queue.offer(ForeverNativeReaderMessage.Failure(failure)) }
    }

    private fun readStderr(ctx: RunContext, input: java.io.InputStream, streamName: String) {
        val buffer = ByteArray(4096); var total = 0
        try {
            while (true) { val count = input.read(buffer); if (count < 0) { if (streamName == "stderr") ctx.supervisorStderrEof = true else ctx.witnessStderrEof = true; return }; total += count
                if (total > ForeverNativeSupervisorPolicy.MAX_STREAM_BYTES || !store.recordOutput(ctx.run, streamName, buffer.copyOf(count))) { ctx.stderrOverflow = true; ctx.outputFailure = IllegalStateException("$streamName overflow"); return } }
        } catch (failure: Throwable) { ctx.outputFailure = failure }
    }

    private fun writePipe(ctx: RunContext, process: java.lang.Process, bytes: ByteArray, deadline: Long, poisonOnFailure: Boolean) {
        require(bytes.size <= ForeverNativeSupervisorPolicy.MAX_LINE_BYTES)
        val remaining = deadline - clock.nanoTime(); require(remaining > 0)
        try {
            val writer = submitJob(ctx, ctx.writerJobs) { process.outputStream.write(bytes); process.outputStream.flush() }
            awaitCompletion(writer, deadline)
        }
        catch (failure: Throwable) {
            if (poisonOnFailure) poisonStdin(ctx)
            throw failure
        } finally { ctx.writeInFlight = false }
    }

    private fun poisonStdin(ctx: RunContext) {
        ctx.stdinPoisoned = true
        val process = ctx.supervisor ?: return
        runCatching { closeOwned(ctx, process.outputStream) }
    }

    private fun closeOwned(ctx: RunContext, stream: java.io.Closeable) {
        val closer = submitJob(ctx, ctx.closerJobs) { stream.close() }
        val remaining = minOf(TimeUnit.MILLISECONDS.toNanos(100), maxOf(1L, ctx.cleanupDeadline - clock.nanoTime()))
        runCatching { closer.future.get(remaining, TimeUnit.NANOSECONDS) }
            .onFailure { ctx.outputFailure = ctx.outputFailure ?: it }
    }

    private fun closeOwnedStreams(ctx: RunContext, deadline: Long, owned: List<java.io.Closeable> = ctx.streams) {
        owned.toList().forEach { runCatching { closeOwned(ctx, it) } }
        if (clock.nanoTime() > deadline) ctx.protocolError = ctx.protocolError ?: "owned streams did not drain before cleanup deadline"
    }

    private fun awaitReaderJobs(jobs: List<CompletionJob<*>>, deadline: Long) {
        jobs.forEach { job ->
            val remaining = deadline - clock.nanoTime(); require(remaining > 0) { "native cleanup jobs remain active" }
            try { job.future.get(remaining, TimeUnit.NANOSECONDS) }
            catch (failure: java.util.concurrent.ExecutionException) { throw (failure.cause ?: failure) }
        }
    }

    private fun awaitSupervisorReaders(ctx: RunContext, deadline: Long) {
        awaitReaderJobs(ctx.supervisorReaderJobs.toList(), deadline)
        require(ctx.supervisorStdoutEof && ctx.supervisorStderrEof) { "supervisor streams did not reach EOF" }
    }

    private fun awaitWitnessReaders(ctx: RunContext, deadline: Long) {
        awaitReaderJobs(ctx.witnessReaderJobs.toList(), deadline)
        require(ctx.witnessStderrEof && ctx.witnessDrain?.completed == true) { "witness streams did not reach EOF" }
    }

    private fun allJobsCompleted(ctx: RunContext): Boolean = (ctx.startJobs + ctx.waitJobs + ctx.readerJobs + ctx.writerJobs + ctx.closerJobs).all { it.completed }

    private fun releaseIfFullyReaped(ctx: RunContext) {
        if (active?.token == ctx.token && ctx.terminalStatus != null && ctx.terminalDurable && ctx.durabilityError == null &&
            (ctx.supervisor == null || ctx.supervisorWait?.completed == true) && (ctx.witness == null || ctx.witnessWait?.completed == true) && allJobsCompleted(ctx)) active = null
    }
    private fun publish(ctx: RunContext, status: String, message: String, phase: Int = ctx.activePhase, supervisorPid: Int? = _state.value.supervisorPid) {
        synchronized(monitor) { if (active?.token != ctx.token) return; _state.value = _state.value.copy(runId = ctx.run.runId, scenario = ctx.scenario.wireName, status = status, phase = phase, message = message, supervisorPid = supervisorPid, childCount = ctx.children.size); notifyUi() }
    }
    private fun notifyUi() = listeners.forEach { runCatching { it(_state.value) } }
    private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    private fun randomHex(bytes: Int): String = ByteArray(bytes).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
