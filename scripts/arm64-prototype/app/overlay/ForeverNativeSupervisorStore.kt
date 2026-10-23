package app.gamenative

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Durable, owner-private evidence. Any durability error is fatal to the RUN. */
class ForeverNativeSupervisorStore private constructor(
    private val base: File,
    private val lock: Any,
    bootProvider: ForeverNativePrelaunchDisposition.BootIdProvider,
) {
    companion object {
        private val instances = ConcurrentHashMap<String, ForeverNativeSupervisorStore>()
        private val locks = ConcurrentHashMap<String, Any>()
        private const val MAX_RECORD_BYTES = 64 * 1024
        private const val MAX_JOURNAL_BYTES = 64 * 1024
        private val NON_TERMINAL = setOf("PREPARED", "STARTING", "RUNNING", "CANCELLING", "UNKNOWN")

        fun get(context: Context): ForeverNativeSupervisorStore {
            val directory = File(context.applicationContext.filesDir, "forever/controls/native-supervisor-v2")
            val key = directory.canonicalFile.path
            return instances.computeIfAbsent(key) {
                ForeverNativeSupervisorStore(directory.canonicalFile, lockFor(key),
                    ForeverNativePrelaunchDisposition.KernelBootIdProvider)
            }
        }

        internal fun forDirectory(
            directory: File,
            bootProvider: ForeverNativePrelaunchDisposition.BootIdProvider = ForeverNativePrelaunchDisposition.KernelBootIdProvider,
        ): ForeverNativeSupervisorStore {
            val canonical = directory.canonicalFile
            val key = canonical.path
            return instances.computeIfAbsent(key) { ForeverNativeSupervisorStore(canonical, lockFor(key), bootProvider) }
        }

        private fun lockFor(key: String): Any = locks.computeIfAbsent(key) { Any() }
    }

    private val current = File(base, "current.json")
    private val runPattern = Regex("NS-[0-9a-f]{32}")
    internal var bootIdProviderForTests: ForeverNativePrelaunchDisposition.BootIdProvider = bootProvider
    internal var failBeforeStepForTests: ((String) -> Unit)? = null
    internal var failBeforeProcessCreateIntentForTests: (() -> Unit)? = null
    internal var failNextAtomicWriteForTests: Boolean = false

    data class Run(val runId: String, val nonce: String, val scenario: String, val directory: File)

    fun begin(
        runId: String,
        nonce: String,
        scenario: String,
    ): Run = synchronized(lock) {
        require(runPattern.matches(runId))
        require(nonce.matches(Regex("[0-9a-f]{32}")))
        require(scenario.matches(Regex("[a-z_]+")))
        ensureDirectoryChain(base)
        val previous = readCurrentUnlocked()
        val directory = File(base, runId)
        require(child(directory, base) == directory)
        require(!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) { "RUN already exists" }
        val historicalAdmission = if (previous?.optString("status") == "UNKNOWN") {
            ForeverNativePrelaunchDisposition.reserve(base, current, previous, runId, nonce, scenario,
                bootIdProviderForTests, ::failBeforeStep)
        } else null
        require(previous == null || previous.optString("status") !in NON_TERMINAL || historicalAdmission != null) {
            "previous native RUN is still active"
        }
        failBeforeStep("new_run_directory")
        Files.createDirectory(directory.toPath())
        makePrivateDirectory(directory)
        forceDirectory(directory)
        val run = Run(runId, nonce, scenario, directory)
        val initial = JSONObject()
            .put("schema", 1).put("variant", ForeverNativeSupervisorPolicy.MANIFEST_VARIANT)
            .put("runId", runId).put("nonce", nonce).put("scenario", scenario)
            .put("status", "PREPARED").put("nativeExit", JSONObject.NULL)
            .put("witnessProof", JSONObject().put("status", "UNPROVEN"))
            .put("identities", JSONArray()).put("echild", false)
            .put("errors", JSONArray())
        historicalAdmission?.let { initial.put("historicalAdmission", JSONObject(it.historicalAdmission.toString())) }
        failBeforeStep("new_run_write")
        writeRun(run, initial)
        failBeforeStep("current_write")
        historicalAdmission?.let {
            ForeverNativePrelaunchDisposition.verifyConsumed(base, current, requireNotNull(previous), it)
        }
        replaceCurrent(run, initial, allowTerminalReplacement = true)
        run
    }

    fun setStatus(run: Run, status: String, error: String? = null) = synchronized(lock) {
        update(run) { json ->
            json.put("status", status)
            if (error != null) json.getJSONArray("errors").put(error.take(512))
        }
    }

    fun recordIntent(run: Run, intent: String, phase: Int, seq: Long) = synchronized(lock) {
        require(intent in setOf("RUN", "START", "ACK", "STOP", "CLOSE"))
        require(phase in 0..2 && seq >= 0)
        append(run, JSONObject().put("kind", "intent").put("intent", intent).put("phase", phase).put("seq", seq))
    }

    /** Durable marker immediately before either Java Process factory is invoked. */
    fun recordProcessCreateIntent(run: Run, role: String) = synchronized(lock) {
        require(role == "supervisor" || role == "witness")
        require(readCurrentUnlocked()?.optString("runId") == run.runId) { "stale RUN may not append process intent" }
        val existing = readProcessCreateIntents(run)
        require(existing.none { it.getString("role") == role }) { "process creation intent already recorded for $role" }
        failBeforeProcessCreateIntentForTests?.invoke()
        append(run, JSONObject()
            .put("kind", "process_create_intent")
            .put("role", role)
            .put("runId", run.runId)
            .put("nonce", run.nonce))
    }

    fun recordFaultIntent(run: Run, fault: String, detail: String) = synchronized(lock) {
        require(fault.matches(Regex("[a-z_]+")))
        append(run, JSONObject().put("kind", "fault_intent").put("fault", fault).put("detail", detail.take(128)))
        update(run) { it.getJSONArray("errors").put("fault:$fault") }
    }

    fun recordIdentity(run: Run, kind: String, pid: Int, startTicks: Long, ppid: Int, uids: List<Int>) = synchronized(lock) {
        require(kind in setOf("supervisor", "intermediate", "grandchild", "witness"))
        require(pid > 0 && startTicks > 0 && ppid > 0 && uids.size == 4 && uids.all { it >= 0 })
        update(run) { json ->
            json.getJSONArray("identities").put(JSONObject()
                .put("kind", kind).put("pid", pid).put("startTicks", startTicks).put("ppid", ppid)
                .put("uid0", uids[0]).put("uid1", uids[1]).put("uid2", uids[2]).put("uid3", uids[3]))
        }
    }

    fun recordEvent(run: Run, event: JSONObject) = synchronized(lock) {
        append(run, JSONObject(event.toString()).put("kind", "event"))
    }

    fun recordOutput(run: Run, stream: String, bytes: ByteArray): Boolean = synchronized(lock) {
        require(stream in setOf("stdout", "stderr", "witness-stdout", "witness-stderr"))
        if (bytes.size > ForeverNativeSupervisorPolicy.MAX_STREAM_BYTES) return false
        require(readCurrentUnlocked()?.optString("runId") == run.runId) { "stale RUN may not write output" }
        val file = child(run.directory, run.directory, stream)
        requireManaged(run, file)
        val oldSize = if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.size(file.toPath()) else 0L
        if (oldSize + bytes.size > ForeverNativeSupervisorPolicy.MAX_STREAM_BYTES) return false
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            createPrivateFile(file)
        }
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        forceDirectory(run.directory)
        true
    }

    fun finish(run: Run, status: String, nativeExit: Int?, witnessProof: JSONObject, echild: Boolean, error: String? = null) = synchronized(lock) {
        require(status in setOf("PASS", "CANCELLED", "UNKNOWN", "ERROR"))
        update(run) { json ->
            json.put("status", status).put("nativeExit", nativeExit ?: JSONObject.NULL)
                .put("witnessProof", JSONObject(witnessProof.toString())).put("echild", echild)
            if (error != null) json.getJSONArray("errors").put(error.take(512))
        }
    }

    /** Best effort only: used when the normal terminal write itself failed. */
    internal fun finishDurabilityUnknown(run: Run, nativeExit: Int?, witnessProof: JSONObject, echild: Boolean) = synchronized(lock) {
        update(run) { json ->
            json.put("status", "UNKNOWN").put("nativeExit", nativeExit ?: JSONObject.NULL)
                .put("witnessProof", JSONObject(witnessProof.toString())).put("echild", echild)
                .getJSONArray("errors").put("durability failure")
        }
    }

    fun recordNativeExit(run: Run, exit: Int) = synchronized(lock) {
        update(run) { it.put("nativeExit", exit) }
    }

    fun read(runId: String): JSONObject? = synchronized(lock) {
        if (!runPattern.matches(runId)) return null
        val file = File(base, "$runId/run.json")
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        JSONObject(readBounded(file).toString(Charsets.UTF_8))
    }

    fun readCurrent(): JSONObject? = synchronized(lock) { readCurrentUnlocked() }

    private fun update(run: Run, transform: (JSONObject) -> Unit) {
        val file = child(run.directory, run.directory, "run.json")
        requireManaged(run, file)
        val json = JSONObject(readBounded(file).toString(Charsets.UTF_8))
        require(json.getString("runId") == run.runId && json.getString("nonce") == run.nonce)
        require(readCurrentUnlocked()?.optString("runId") == run.runId) { "stale RUN may not mutate current" }
        transform(json)
        writeRun(run, json)
        replaceCurrent(run, json)
    }

    private fun writeRun(run: Run, json: JSONObject) {
        val file = child(run.directory, run.directory, "run.json")
        requireManaged(run, file)
        atomicWrite(file, json.toString(2))
    }

    private fun replaceCurrent(run: Run, json: JSONObject, allowTerminalReplacement: Boolean = false) {
        requireManaged(run, current, allowTerminalReplacement)
        atomicWrite(current, json.toString(2))
    }

    private fun append(run: Run, value: JSONObject) {
        require(readCurrentUnlocked()?.optString("runId") == run.runId) { "stale RUN may not append evidence" }
        val file = child(run.directory, run.directory, "events.jsonl")
        requireManaged(run, file)
        val bytes = (value.toString() + "\n").toByteArray(Charsets.UTF_8)
        val old = if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.size(file.toPath()) else 0L
        require(old + bytes.size <= MAX_JOURNAL_BYTES) { "native supervisor journal overflow" }
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) createPrivateFile(file)
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        forceDirectory(run.directory)
    }

    private fun readProcessCreateIntents(run: Run): List<JSONObject> {
        val file = child(run.directory, run.directory, "events.jsonl")
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return readBounded(file).toString(Charsets.UTF_8).lineSequence()
            .filter { it.isNotEmpty() }
            .map { line ->
                val json = JSONObject(line)
                if (json.optString("kind") == "process_create_intent") {
                    require(json.keys().asSequence().toSet() == setOf("kind", "role", "runId", "nonce")) {
                        "invalid process creation intent"
                    }
                    require(json.getString("role") == "supervisor" || json.getString("role") == "witness") {
                        "unknown process creation role"
                    }
                    require(json.getString("runId") == run.runId && json.getString("nonce") == run.nonce) {
                        "process creation intent identity mismatch"
                    }
                    json
                } else JSONObject().put("kind", "other")
            }
            .filter { it.optString("kind") == "process_create_intent" }
            .toList()
    }

    private fun failBeforeStep(step: String) {
        failBeforeStepForTests?.invoke(step)
    }

    private fun readCurrentUnlocked(): JSONObject? {
        requireNoSymlink(current)
        if (!Files.isRegularFile(current.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        return JSONObject(readBounded(current).toString(Charsets.UTF_8))
    }

    private fun ensureDirectoryChain(directory: File) {
        val absolute = directory.absoluteFile
        requireNoSymlink(absolute)
        val missing = mutableListOf<File>()
        var cursor: File? = absolute
        while (cursor != null && !Files.exists(cursor.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            missing += cursor
            cursor = cursor.parentFile
        }
        require(cursor != null && Files.isDirectory(cursor.toPath(), LinkOption.NOFOLLOW_LINKS))
        for (entry in missing.asReversed()) {
            requireNoSymlink(entry)
            Files.createDirectory(entry.toPath())
            makePrivateDirectory(entry)
            forceDirectory(entry.parentFile ?: entry)
        }
        require(Files.isDirectory(absolute.toPath(), LinkOption.NOFOLLOW_LINKS))
        makePrivateDirectory(absolute)
        forceDirectory(absolute)
    }

    private fun requireManaged(run: Run, file: File, allowTerminalReplacement: Boolean = false) {
        rejectSymlinkAncestors(file)
        if (file == current) {
            val existing = readCurrentUnlocked()
            require(allowTerminalReplacement || existing == null || existing.optString("runId") == run.runId) {
                "current RUN pointer changed"
            }
        } else {
            require(file.parentFile?.canonicalFile == run.directory.canonicalFile)
        }
    }

    private fun child(file: File, parent: File, name: String = file.name): File {
        require(name.matches(Regex("[A-Za-z0-9._-]+")))
        val result = if (file == parent) File(parent, name) else file
        require(result.parentFile?.canonicalFile == parent.canonicalFile) { "managed path escaped parent" }
        require(!Files.isSymbolicLink(result.toPath()))
        return result
    }

    private fun rejectSymlinkAncestors(file: File) {
        var cursor: File? = file.absoluteFile
        while (cursor != null) {
            requireNoSymlink(cursor)
            cursor = cursor.parentFile
        }
    }

    private fun requireNoSymlink(file: File) {
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) require(!Files.isSymbolicLink(file.toPath())) {
            "managed path contains symlink"
        }
    }

    private fun makePrivateDirectory(directory: File) {
        setMode(directory, 0x1c0) // 0700
    }

    private fun createPrivateFile(file: File) {
        require(!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        setMode(file, 0x180) // 0600
        forceDirectory(file.parentFile ?: error("file has no parent"))
    }

    private fun setMode(file: File, mode: Int) {
        try {
            android.system.Os.chmod(file.absolutePath, mode)
            require((android.system.Os.stat(file.absolutePath).st_mode and 0x1ff) == mode)
        } catch (failure: Throwable) {
            try {
                val permissions = when (mode) {
                    0x1c0 -> setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE)
                    0x180 -> setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)
                    else -> error("unsupported private mode")
                }
                Files.setPosixFilePermissions(file.toPath(), permissions)
                require(Files.getPosixFilePermissions(file.toPath()) == permissions)
            } catch (fallback: Throwable) {
                throw IllegalStateException("cannot establish private mode", failure)
            }
        }
    }

    private fun atomicWrite(destination: File, text: String) {
        if (failNextAtomicWriteForTests) {
            failNextAtomicWriteForTests = false
            error("forced durability failure")
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_RECORD_BYTES)
        require(destination.parentFile != null)
        ensureDirectoryChain(requireNotNull(destination.parentFile))
        require(!Files.isSymbolicLink(destination.toPath()))
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        require(temporary.parentFile?.canonicalFile == destination.parentFile.canonicalFile)
        try {
            FileChannel.open(temporary.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            setMode(temporary, 0x180)
            require(!Files.isSymbolicLink(temporary.toPath()))
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            require(!Files.isSymbolicLink(destination.toPath()))
            forceFile(destination)
            forceDirectory(requireNotNull(destination.parentFile))
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun forceFile(file: File) {
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
    }

    private fun forceDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
    }

    private fun readBounded(file: File): ByteArray {
        rejectSymlinkAncestors(file)
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            require(channel.size() <= MAX_RECORD_BYTES)
            val output = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(8 * 1024)
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                require(count > 0) { "bounded read made no progress" }
                buffer.flip()
                val bytes = ByteArray(count)
                buffer.get(bytes)
                output.write(bytes)
                require(output.size() <= MAX_RECORD_BYTES)
            }
            return output.toByteArray()
        }
    }

}
