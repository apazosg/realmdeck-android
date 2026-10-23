package app.gamenative

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Base64
import org.json.JSONObject

data class BoundedLogSnapshot(
    val stream: String, val runId: String, val totalBytes: Long, val headBytes: Int,
    val tailStart: Long, val tailBytes: Int, val truncated: Boolean,
    val capturedAtMs: Long, val flushedAtMs: Long, val schema: Int = 1,
)

data class PersistedOutput(val metadata: BoundedLogSnapshot, val head: ByteArray, val tail: ByteArray, val persistenceError: String? = null)

/** Bounded head/tail files are written independently of the pipe reader. */
class BoundedLogRetention(
    private val directory: File,
    val runId: String,
    val stream: String,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val beforePublish: () -> Unit = {},
    private val recoverExisting: Boolean = false,
    private val onPersistenceError: (String) -> Unit = {},
    private val sensitiveFilesDir: File? = null,
) : AutoCloseable {
    companion object {
        const val HEAD_LIMIT = 128 * 1024
        const val TAIL_LIMIT = 384 * 1024
        const val TOTAL_LIMIT = HEAD_LIMIT + TAIL_LIMIT
        const val SNAPSHOT_LIMIT = 800 * 1024

        fun readPersisted(directory: File, runId: String, stream: String): PersistedOutput {
            require(validRunId(runId) && stream in setOf("stdout", "stderr"))
            val file = File(directory, "$runId-$stream-snapshot.json")
            require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
            val bytes = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use {
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= SNAPSHOT_LIMIT)
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            require(json.getInt("schema") == 1 && json.getString("runId") == runId && json.getString("stream") == stream)
            val head = Base64.getDecoder().decode(json.getString("headBase64"))
            val tail = Base64.getDecoder().decode(json.getString("tailBase64"))
            val total = json.getLong("totalBytes")
            val start = json.getLong("tailStart")
            require(head.size <= HEAD_LIMIT && tail.size <= TAIL_LIMIT && total >= 0)
            require(json.getInt("headBytes") == head.size && json.getInt("tailBytes") == tail.size)
            require(head.size.toLong() == minOf(total, HEAD_LIMIT.toLong()))
            require(tail.size.toLong() == minOf(maxOf(0L, total - HEAD_LIMIT), TAIL_LIMIT.toLong()))
            require(start == total - tail.size && json.getBoolean("truncated") == (total > TOTAL_LIMIT))
            val captured = json.getLong("capturedAtMs")
            val flushed = json.getLong("flushedAtMs")
            require(captured >= 0 && flushed >= captured)
            val errorFile = File(directory, "$runId-$stream-error.json")
            val persistedError = if (Files.exists(errorFile.toPath(), LinkOption.NOFOLLOW_LINKS)) runCatching {
                require(Files.isRegularFile(errorFile.toPath(), LinkOption.NOFOLLOW_LINKS) && errorFile.length() <= 4096)
                val errorJson = JSONObject(Files.newInputStream(errorFile.toPath(), LinkOption.NOFOLLOW_LINKS).use {
                    val bytes = ByteArray(4097); var used = 0
                    while (used < bytes.size) { val count = it.read(bytes, used, bytes.size - used); if (count < 0) break; used += count }
                    require(used <= 4096); String(bytes, 0, used, StandardCharsets.UTF_8)
                })
                require(errorJson.getString("runId") == runId && errorJson.getString("stream") == stream)
                errorJson.getString("error").take(500)
            }.getOrDefault("persistence_error_record_unreadable") else null
            return PersistedOutput(BoundedLogSnapshot(stream, runId, total, head.size, start, tail.size,
                total > TOTAL_LIMIT, captured, flushed), head, tail, persistedError)
        }
    }

    private val lock = Any()
    private val head = ByteArray(HEAD_LIMIT)
    private val tail = ByteArray(TAIL_LIMIT)
    private var headSize = 0
    private var tailSize = 0
    private var tailCursor = 0
    private var total = 0L
    private var lastCaptured = clockMs()
    private var snapshot: BoundedLogSnapshot? = null
    private var lastError: String? = null
    private var flusher: Thread? = null
    private val closed = AtomicBoolean(false)
    private val manifestFile: File

    init {
        require(validRunId(runId))
        require(stream == "stdout" || stream == "stderr")
        require(directory.exists() || directory.mkdirs())
        require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
        manifestFile = ownedFile("$runId-$stream-snapshot.json")
        if (manifestFile.exists()) {
            require(recoverExisting) { "retention files already exist" }
            loadExisting()
        } else {
            createExclusive(manifestFile)
            snapshot = BoundedLogSnapshot(stream, runId, 0, 0, 0, 0, false, clockMs(), 0)
            flushLocked()
        }
    }

    fun startPeriodicPersistence() {
        synchronized(lock) {
            if (flusher != null || closed.get()) return
            flusher = Thread({
                while (!closed.get()) {
                    try { Thread.sleep(750L) } catch (_: InterruptedException) { }
                    if (!closed.get()) flush()
                }
            }, "forever-$stream-flusher").also { it.isDaemon = true; it.start() }
        }
    }

    fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        synchronized(lock) {
            if (closed.get()) return
            val append = {
                var source = offset
                var remaining = length
                val copiedHead = minOf(HEAD_LIMIT - headSize, remaining)
                bytes.copyInto(head, headSize, source, source + copiedHead)
                headSize += copiedHead; source += copiedHead; remaining -= copiedHead
                while (remaining > 0) {
                    val copied = minOf(remaining, TAIL_LIMIT - tailCursor)
                    bytes.copyInto(tail, tailCursor, source, source + copied)
                    tailCursor = (tailCursor + copied) % TAIL_LIMIT
                    tailSize = minOf(TAIL_LIMIT, tailSize + copied)
                    source += copied; remaining -= copied
                }
                total = Math.addExact(total, length.toLong())
                if (length > 0) lastCaptured = clockMs()
            }
            if (sensitiveFilesDir == null) append()
            else ForeverAssistedCredentialPrivacy.runIfDiagnosticsAllowed(sensitiveFilesDir, runId, append)
        }
    }

    fun flush(): BoundedLogSnapshot = synchronized(lock) { flushLocked() }
    fun snapshot(): BoundedLogSnapshot = synchronized(lock) { snapshot ?: error("snapshot unavailable") }
    fun error(): String? = synchronized(lock) { lastError }

    fun boundedText(): String = synchronized(lock) {
        val prefix = head.copyOf(headSize)
        val recent = orderedTail()
        val overlap = maxOf(0L, prefix.size.toLong() - (total - recent.size)).toInt().coerceAtMost(recent.size)
        val combined = ByteArray(prefix.size + recent.size - overlap)
        prefix.copyInto(combined); recent.copyInto(combined, prefix.size, overlap)
        val text = String(combined, StandardCharsets.UTF_8)
        if (total > TOTAL_LIMIT) "[output truncated: head=128KiB tail=384KiB]\n$text" else text
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        flusher?.interrupt(); flusher?.join(2_000L)
        synchronized(lock) { flushLocked() }
    }

    private fun orderedTail(): ByteArray {
        if (tailSize == 0) return ByteArray(0)
        val result = ByteArray(tailSize)
        val start = if (tailSize == TAIL_LIMIT) tailCursor else 0
        val first = minOf(tailSize, TAIL_LIMIT - start)
        tail.copyInto(result, 0, start, start + first)
        if (first < tailSize) tail.copyInto(result, first, 0, tailSize - first)
        return result
    }

    private fun flushLocked(): BoundedLogSnapshot {
        if (sensitiveFilesDir != null) {
            var published: BoundedLogSnapshot? = null
            val allowed = ForeverAssistedCredentialPrivacy.runIfDiagnosticsAllowed(sensitiveFilesDir, runId) {
                published = flushSnapshotLocked()
            }
            return if (allowed) requireNotNull(published) else snapshot ?: error("snapshot unavailable")
        }
        return flushSnapshotLocked()
    }

    private fun flushSnapshotLocked(): BoundedLogSnapshot {
        val now = clockMs(); val ordered = orderedTail()
        try {
            val next = BoundedLogSnapshot(stream, runId, total, headSize, total - ordered.size,
                ordered.size, total > TOTAL_LIMIT, lastCaptured, maxOf(now, lastCaptured))
            atomicManifest(next, head.copyOf(headSize), ordered); snapshot = next
            // This is an owned tiny sidecar. Clear it only AFTER a successful
            // snapshot publication, never before or on a failed flush.
            Files.deleteIfExists(ownedFile("$runId-$stream-error.json").toPath())
            lastError = null
        } catch (error: IOException) {
            lastError = "${error::class.java.simpleName}: ${error.message}"
            persistError()
            runCatching { onPersistenceError(requireNotNull(lastError)) }
        } catch (error: SecurityException) {
            lastError = "${error::class.java.simpleName}: ${error.message}"
            persistError()
            runCatching { onPersistenceError(requireNotNull(lastError)) }
        }
        return snapshot!!
    }

    private fun atomicManifest(value: BoundedLogSnapshot, prefix: ByteArray, recent: ByteArray) {
        // One atomic generation contains bytes AND metadata. A host death during
        // flush cannot leave a newer tail paired with an older manifest.
        val temp = ownedFile(".${manifestFile.name}.${System.nanoTime()}.tmp")
        try {
            val json = JSONObject().put("schema", 1).put("runId", value.runId).put("stream", value.stream)
                .put("totalBytes", value.totalBytes).put("headBytes", value.headBytes).put("tailStart", value.tailStart)
                .put("tailBytes", value.tailBytes).put("truncated", value.truncated)
                .put("capturedAtMs", value.capturedAtMs).put("flushedAtMs", value.flushedAtMs)
                .put("headBase64", Base64.getEncoder().encodeToString(prefix))
                .put("tailBase64", Base64.getEncoder().encodeToString(recent))
                .toString().toByteArray(StandardCharsets.UTF_8)
            require(json.size <= SNAPSHOT_LIMIT)
            java.nio.channels.FileChannel.open(temp.toPath(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
                val buffer = java.nio.ByteBuffer.wrap(json)
                while (buffer.hasRemaining()) output.write(buffer)
                output.force(true)
            }
            beforePublish()
            Files.move(temp.toPath(), manifestFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { if (temp.exists()) temp.delete() }
    }

    private fun loadExisting() {
        val saved = readPersisted(directory, runId, stream)
        saved.head.copyInto(head); saved.tail.copyInto(tail)
        headSize = saved.head.size; tailSize = saved.tail.size
        tailCursor = if (tailSize == TAIL_LIMIT) 0 else tailSize
        total = saved.metadata.totalBytes; lastCaptured = saved.metadata.capturedAtMs
        snapshot = saved.metadata
    }

    private fun persistError() {
        // Best effort: if the entire filesystem cannot accept even this small
        // record, the older snapshot is still explicitly PARTIAL in the export.
        runCatching {
            val target = ownedFile("$runId-$stream-error.json")
            val temporary = ownedFile(".${target.name}.${System.nanoTime()}.tmp")
            val bytes = JSONObject().put("schema", 1).put("runId", runId).put("stream", stream)
                .put("error", lastError?.take(500)).put("at", clockMs()).toString().toByteArray()
            try {
                java.nio.channels.FileChannel.open(temporary.toPath(), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    val buffer = java.nio.ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary.toPath()) }
        }
    }

    private fun ownedFile(name: String): File {
        require(name.matches(Regex("[A-Za-z0-9._-]+")))
        val result = File(directory, name)
        require(result.parentFile == directory.canonicalFile)
        require(!Files.isSymbolicLink(result.toPath()))
        return result
    }
    private fun createExclusive(file: File) { Files.newByteChannel(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { } }
}

private fun validRunId(value: String) = value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"))
private fun String.json(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

class ProcessOutputDrainer(private val input: InputStream, private val retention: BoundedLogRetention) {
    private var thread: Thread? = null
    @Volatile var finished: Boolean = false
        private set
    fun start(): Thread {
        check(thread == null); retention.startPeriodicPersistence()
        return Thread({
            try {
                val buffer = ByteArray(16 * 1024)
                while (true) { val read = input.read(buffer); if (read < 0) break; if (read > 0) retention.append(buffer, 0, read) }
            } catch (_: IOException) {
                retention.flush()
            } finally { retention.close(); finished = true }
        }, "forever-${retention.stream}-drainer").also { thread = it; it.start() }
    }
    fun join(timeoutMs: Long): Boolean { thread?.join(timeoutMs); return finished }
    fun close() { runCatching { input.close() }; thread?.join(2_000L); retention.close() }
}

class BoundedProcessOutput(
    directory: File,
    runId: String,
    stdoutInput: InputStream,
    stderrInput: InputStream,
    private val sensitiveFilesDir: File? = null,
    onPersistenceError: (String, String) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val stdout = BoundedLogRetention(directory, runId, "stdout", onPersistenceError = { onPersistenceError("stdout", it) },
        sensitiveFilesDir = sensitiveFilesDir)
    private val stderr = BoundedLogRetention(directory, runId, "stderr", onPersistenceError = { onPersistenceError("stderr", it) },
        sensitiveFilesDir = sensitiveFilesDir)
    private val stdoutDrainer = ProcessOutputDrainer(stdoutInput, stdout).also { it.start() }
    private val stderrDrainer = ProcessOutputDrainer(stderrInput, stderr).also { it.start() }
    fun join(timeoutMs: Long): Boolean = stdoutDrainer.join(timeoutMs) && stderrDrainer.join(timeoutMs)
    fun streamsDrained() = stdoutDrainer.finished && stderrDrainer.finished
    fun stdoutText() = stdout.boundedText()
    fun stderrText() = stderr.boundedText()
    fun stdoutSnapshot() = stdout.snapshot()
    fun stderrSnapshot() = stderr.snapshot()
    override fun close() { stdoutDrainer.close(); stderrDrainer.close() }
}
