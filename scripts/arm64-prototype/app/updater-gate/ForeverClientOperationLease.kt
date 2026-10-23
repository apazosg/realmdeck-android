package app.gamenative

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** A bounded identity supplied by the caller's app-UID/session-aware runtime observer. */
data class RuntimeIdentity(
    val pid: Int,
    val startTicks: Long,
    val bootId: String,
    val uid: Int? = null,
)

/** Typed result of observing only this application's selected Wine runtime. */
sealed class RuntimeObservation {
    object Quiescent : RuntimeObservation()
    data class Active(val identities: List<RuntimeIdentity>) : RuntimeObservation()
    data class Unknown(val reasonCode: String) : RuntimeObservation()
}

/** Opaque launch reservation. Its UUID binds later lifecycle calls to the durable record. */
class GuestTicket internal constructor(
    val storageId: String,
    val token: String,
)

/**
 * Durable, process-wide exclusion between the updater and the one selected Wine runtime.
 * The caller must place [stateDirectory] under app-private no-backup storage and supply a
 * trusted app-UID/start-time/boot-aware observer. This class deliberately does not discover
 * or kill processes itself.
 */
class ForeverClientOperationLease(
    stateDirectory: File,
    private val observeOwnRuntime: () -> RuntimeObservation,
) {
    private val directory = stateDirectory.toPath().toAbsolutePath().normalize()
    private val lockPath = directory.resolve(LOCK_FILE)
    private val statePath = directory.resolve(STATE_FILE)
    private val temporaryPath = directory.resolve(TEMP_FILE)
    private val processSlotKey = directory.toString()
    private val processSlot = processSlots.computeIfAbsent(processSlotKey) { ProcessSlot() }

    /** Acquire the updater gate. Omitting the journal probe conservatively retains UPDATING. */
    fun acquireUpdate(
        storageId: String,
        hasPendingTransaction: () -> Boolean = { true },
    ): Lease {
        validateStorageId(storageId)
        val held = acquireFileLock(create = true)
            ?: throw IllegalStateException("another client operation currently holds the global gate")
        try {
            val previous = readRecord()
            requireQuiescent("updater admission")
            when (previous?.status) {
                null, Status.IDLE -> Unit
                Status.UPDATING -> require(previous.storageId == storageId) {
                    "unfinished update belongs to a different frozen storage ID"
                }
                Status.GUEST_RESERVED, Status.GUEST_STARTED ->
                    throw IllegalStateException("recover the unfinished guest operation before updating")
            }
            val token = UUID.randomUUID().toString()
            writeRecord(Record(Status.UPDATING, storageId, token))
            return Lease(this, held, storageId, token, hasPendingTransaction)
        } catch (failure: Throwable) {
            held.close()
            throw failure
        }
    }

    /** Persist a guest reservation before the caller attempts to spawn Wine. */
    fun beginGuest(storageId: String, beforeReservation: () -> Unit = {}): GuestTicket {
        validateStorageId(storageId)
        return withExclusiveLock {
            val previous = readRecord()
            requireQuiescent("guest admission")
            require(previous == null || previous.status == Status.IDLE) {
                "recover the unfinished client operation before launching the guest"
            }
            // Reconcile terminated-session state under the same admission lock,
            // after quiescence/old-ticket recovery and before any new Wine spawn.
            beforeReservation()
            val token = UUID.randomUUID().toString()
            writeRecord(Record(Status.GUEST_RESERVED, storageId, token))
            GuestTicket(storageId, token)
        }
    }

    /** Record post-spawn state. Active and Unknown both retain the durable guest gate. */
    fun markGuestStarted(ticket: GuestTicket) = withExclusiveLock {
        val record = requireTicket(ticket, Status.GUEST_RESERVED)
        when (val observation = observeSafely()) {
            is RuntimeObservation.Active -> writeRecord(
                record.copy(status = Status.GUEST_STARTED, observation = StoredObservation.active(observation.identities)),
            )
            is RuntimeObservation.Unknown -> writeRecord(
                record.copy(status = Status.GUEST_STARTED, observation = StoredObservation.unknown(observation.reasonCode)),
            )
            RuntimeObservation.Quiescent -> throw IllegalStateException(
                "guest start was not observed; reservation remains durable",
            )
        }
    }

    /** Cancel only a still-reserved launch after an explicit Quiescent observation. */
    fun abortGuestBeforeStart(ticket: GuestTicket) = withExclusiveLock {
        requireTicket(ticket, Status.GUEST_RESERVED)
        requireQuiescent("guest reservation abort")
        writeRecord(Record.IDLE)
    }

    /** Complete only the matching started guest after the observer proves quiescence. */
    fun finishGuest(ticket: GuestTicket) = withExclusiveLock {
        requireTicket(ticket, Status.GUEST_STARTED)
        requireQuiescent("guest completion")
        writeRecord(Record.IDLE)
    }

    /** Explicit restart recovery for a durable guest record; never infers quiescence from a PID. */
    fun recoverGuest(storageId: String) = withExclusiveLock {
        validateStorageId(storageId)
        val record = readRecord()
        require(record?.status == Status.GUEST_RESERVED || record?.status == Status.GUEST_STARTED) {
            "there is no unfinished guest operation to recover"
        }
        require(record.storageId == storageId) { "guest recovery storage ID does not match the frozen record" }
        requireQuiescent("guest recovery")
        writeRecord(Record.IDLE)
    }

    /** Recover only the exact in-process guest ticket; accepts RESERVED after shutdown proof too. */
    fun recoverMatchingGuest(ticket: GuestTicket) = withExclusiveLock {
        validateStorageId(ticket.storageId)
        require(isUuid(ticket.token)) { "invalid guest recovery ticket" }
        val record = readRecord()
        require(
            record?.status == Status.GUEST_RESERVED || record?.status == Status.GUEST_STARTED,
        ) { "there is no unfinished guest operation to recover" }
        require(record.storageId == ticket.storageId && record.token == ticket.token) {
            "guest recovery ticket does not own the durable record"
        }
        requireQuiescent("matching guest recovery")
        writeRecord(Record.IDLE)
    }

    /**
     * Clear a crashed updater record only after the transaction owner explicitly reports that
     * its journal is clean and the runtime observer reports Quiescent.
     */
    fun recoverUpdate(storageId: String, hasPendingTransaction: () -> Boolean) = withExclusiveLock {
        validateStorageId(storageId)
        val record = readRecord()
        require(record?.status == Status.UPDATING) { "there is no unfinished update operation to recover" }
        require(record.storageId == storageId) { "update recovery storage ID does not match the frozen record" }
        requireQuiescent("update recovery")
        require(!hasPendingTransaction()) { "recover the transaction journal before clearing the updater gate" }
        writeRecord(Record.IDLE)
    }

    /** True for a durable operation, a held lock, an active runtime, or an unknown observation. */
    fun hasUnfinishedOperation(): Boolean = blockingReason() != null

    /** Read-only fail-closed gate status. Null means idle and the observer proved Quiescent. */
    fun blockingReason(): String? {
        return try {
            validatePathAncestors()
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(statePath, LinkOption.NOFOLLOW_LINKS) ||
                    Files.exists(temporaryPath, LinkOption.NOFOLLOW_LINKS) ||
                    Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                ) return "operation_state_unreadable"
                return observationReason(observeSafely(), null)
            }
            validateStateDirectory()
            val lockExists = Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
            if (lockExists) {
                val held = acquireFileLock(create = false) ?: return "operation_lock_held"
                try {
                    observationReason(observeSafely(), readRecord())
                } finally {
                    held.close()
                }
            } else {
                if (Files.exists(statePath, LinkOption.NOFOLLOW_LINKS) ||
                    Files.exists(temporaryPath, LinkOption.NOFOLLOW_LINKS)
                ) return "operation_state_unreadable"
                observationReason(observeSafely(), null)
            }
        } catch (_: Exception) {
            "operation_state_unreadable"
        }
    }

    private fun closeUpdate(storageId: String, token: String, hasPendingTransaction: () -> Boolean) {
        val record = readRecord()
        require(record?.status == Status.UPDATING && record.storageId == storageId && record.token == token) {
            "updater lease no longer owns the durable operation record; it was retained"
        }
        if (hasPendingTransaction()) return
        if (observeSafely() !== RuntimeObservation.Quiescent) return
        writeRecord(Record.IDLE)
    }

    private fun requireTicket(ticket: GuestTicket, expectedStatus: Status): Record {
        validateStorageId(ticket.storageId)
        require(isUuid(ticket.token)) { "invalid guest ticket token" }
        val record = readRecord()
        require(
            record?.status == expectedStatus && record.storageId == ticket.storageId && record.token == ticket.token,
        ) { "stale guest ticket does not own the durable operation record" }
        return record
    }

    private fun requireQuiescent(action: String) {
        when (val observation = observeSafely()) {
            RuntimeObservation.Quiescent -> Unit
            is RuntimeObservation.Active -> throw IllegalStateException("own Wine runtime is active during $action")
            is RuntimeObservation.Unknown -> throw IllegalStateException(
                "own Wine runtime is unknown during $action (${observation.reasonCode})",
            )
        }
    }

    private fun observeSafely(): RuntimeObservation = try {
        when (val observation = observeOwnRuntime()) {
            RuntimeObservation.Quiescent -> RuntimeObservation.Quiescent
            is RuntimeObservation.Active -> {
                if (observation.identities.isEmpty() || observation.identities.size > MAX_IDENTITIES ||
                    observation.identities.any { !validIdentity(it) }
                ) RuntimeObservation.Unknown("invalid_identity")
                else RuntimeObservation.Active(observation.identities.toList())
            }
            is RuntimeObservation.Unknown -> if (validReasonCode(observation.reasonCode)) observation
            else RuntimeObservation.Unknown("invalid_reason")
        }
    } catch (_: Exception) {
        RuntimeObservation.Unknown("observer_failed")
    }

    private fun observationReason(observation: RuntimeObservation, record: Record?): String? {
        when (observation) {
            is RuntimeObservation.Active -> return "own_runtime_active"
            is RuntimeObservation.Unknown -> return "own_runtime_unknown:${observation.reasonCode}"
            RuntimeObservation.Quiescent -> Unit
        }
        return when (record?.status) {
            null, Status.IDLE -> null
            Status.GUEST_RESERVED -> "guest_start_reserved"
            Status.GUEST_STARTED -> "guest_completion_unconfirmed"
            Status.UPDATING -> "update_recovery_required"
        }
    }

    private fun <T> withExclusiveLock(action: () -> T): T {
        val held = acquireFileLock(create = true)
            ?: throw IllegalStateException("another client operation currently holds the global gate")
        try {
            return action()
        } finally {
            held.close()
        }
    }

    private fun acquireFileLock(create: Boolean): HeldLock? {
        if (create) {
            ensureDirectoryChain()
            if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) &&
                (Files.exists(statePath, LinkOption.NOFOLLOW_LINKS) ||
                    Files.exists(temporaryPath, LinkOption.NOFOLLOW_LINKS))
            ) {
                throw IllegalStateException("operation state exists without its persistent lock file")
            }
        } else {
            validatePathAncestors()
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return null
            validateStateDirectory()
            if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) return null
        }
        synchronized(processSlot) {
            if (processSlot.held) return null
            processSlot.held = true
            var channel: FileChannel? = null
            try {
                if (create) ensureLockFile()
                validateRegularNoSymlink(lockPath, "operation lock")
                channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                )
                val lock = try {
                    channel!!.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    channel.close()
                    processSlot.held = false
                    return null
                }
                return HeldLock(processSlot, channel, lock)
            } catch (failure: Throwable) {
                try {
                    channel?.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                processSlot.held = false
                throw failure
            }
        }
    }

    private fun ensureLockFile() {
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            validateRegularNoSymlink(lockPath, "operation lock")
            return
        }
        try {
            FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { it.force(true) }
            forceDirectory(directory.toFile())
        } catch (_: FileAlreadyExistsException) {
            validateRegularNoSymlink(lockPath, "operation lock")
        }
    }

    private fun readRecord(): Record? {
        validatePathAncestors()
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return null
        validateStateDirectory()
        if (Files.exists(temporaryPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("an uncommitted operation-state temporary exists")
        }
        if (!Files.exists(statePath, LinkOption.NOFOLLOW_LINKS)) return null
        validateRegularNoSymlink(statePath, "operation state")
        val bytes = readBounded(statePath)
        val envelope = JSONObject(String(bytes, Charsets.UTF_8))
        require(envelope.getInt("schema") == SCHEMA) { "unsupported operation-state schema" }
        val payloadText = envelope.getString("payload")
        val expectedChecksum = envelope.getString("sha256")
        require(expectedChecksum.matches(Regex("[0-9a-f]{64}"))) { "invalid operation-state checksum" }
        val payloadBytes = payloadText.toByteArray(Charsets.UTF_8)
        require(sha256(payloadBytes) == expectedChecksum) { "operation-state checksum mismatch" }
        return parseRecord(JSONObject(payloadText))
    }

    private fun writeRecord(record: Record) {
        ensureDirectoryChain()
        validateStateFiles()
        require(!Files.exists(temporaryPath, LinkOption.NOFOLLOW_LINKS)) {
            "an uncommitted operation-state temporary exists"
        }
        val payloadText = encodeRecord(record).toString()
        val payloadBytes = payloadText.toByteArray(Charsets.UTF_8)
        val envelope = JSONObject()
            .put("schema", SCHEMA)
            .put("payload", payloadText)
            .put("sha256", sha256(payloadBytes))
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(envelope.size <= MAX_STATE_BYTES) { "operation-state record exceeds its bounded size" }
        FileChannel.open(
            temporaryPath,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(envelope)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        try {
            Files.move(temporaryPath, statePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            forceDirectory(directory.toFile())
        } finally {
            // A crash-created temp is deliberately treated as an unresolved journal on next use.
            // Cleanup here is only for a failed in-process write before the atomic rename.
            if (Files.exists(temporaryPath, LinkOption.NOFOLLOW_LINKS)) Files.delete(temporaryPath)
        }
    }

    private fun encodeRecord(record: Record): JSONObject {
        val identities = JSONArray()
        record.observation.identities.forEach { identities.put(encodeIdentity(it)) }
        return JSONObject()
            .put("status", record.status.name)
            .put("storageId", record.storageId)
            .put("token", record.token ?: JSONObject.NULL)
            .put("observation", record.observation.kind)
            .put("unknownReason", record.observation.reason ?: JSONObject.NULL)
            .put("identities", identities)
    }

    private fun parseRecord(json: JSONObject): Record {
        val status = Status.valueOf(json.getString("status"))
        val storageId = json.getString("storageId")
        val rawToken = json.get("token")
        val token = if (rawToken == JSONObject.NULL) null else rawToken as? String
            ?: throw IllegalArgumentException("invalid operation-state token")
        val observationKind = json.getString("observation")
        val rawReason = json.get("unknownReason")
        val reason = if (rawReason == JSONObject.NULL) null else rawReason as? String
            ?: throw IllegalArgumentException("invalid operation-state reason")
        val identitiesJson = json.getJSONArray("identities")
        require(identitiesJson.length() <= MAX_IDENTITIES) { "too many recorded runtime identities" }
        val identities = (0 until identitiesJson.length()).map { parseIdentity(identitiesJson.getJSONObject(it)) }
        val observation = when (observationKind) {
            "QUIESCENT" -> {
                require(reason == null && identities.isEmpty())
                StoredObservation.quiescent()
            }
            "ACTIVE" -> {
                require(reason == null && identities.isNotEmpty())
                StoredObservation.active(identities)
            }
            "UNKNOWN" -> {
                require(identities.isEmpty() && reason != null && validReasonCode(reason))
                StoredObservation.unknown(reason)
            }
            else -> throw IllegalArgumentException("invalid recorded observation")
        }
        when (status) {
            Status.IDLE -> require(storageId.isEmpty() && token == null)
            Status.GUEST_RESERVED, Status.GUEST_STARTED, Status.UPDATING -> {
                validateStorageId(storageId)
                require(token != null && isUuid(token))
            }
        }
        if (status == Status.GUEST_RESERVED || status == Status.UPDATING) require(observation.kind == "QUIESCENT")
        return Record(status, storageId, token, observation)
    }

    private fun encodeIdentity(identity: RuntimeIdentity): JSONObject = JSONObject()
        .put("pid", identity.pid)
        .put("startTicks", identity.startTicks)
        .put("bootId", identity.bootId)
        .put("uid", identity.uid ?: JSONObject.NULL)

    private fun parseIdentity(json: JSONObject): RuntimeIdentity {
        val rawUid = json.get("uid")
        val uid = if (rawUid == JSONObject.NULL) null else rawUid as? Int
            ?: throw IllegalArgumentException("invalid runtime identity UID")
        val identity = RuntimeIdentity(json.getInt("pid"), json.getLong("startTicks"), json.getString("bootId"), uid)
        require(validIdentity(identity)) { "invalid recorded runtime identity" }
        return identity
    }

    private fun validateStateFiles() {
        validatePathAncestors()
        validateStateDirectory()
        for ((path, label) in listOf(statePath to "operation state", temporaryPath to "operation-state temporary")) {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) validateRegularNoSymlink(path, label)
        }
    }

    private fun ensureDirectoryChain() {
        val root = directory.root ?: throw IllegalStateException("operation-state path must be absolute")
        var current = root
        for (part in directory) {
            current = current.resolve(part)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(current)) { "operation-state directory contains a symlink" }
                require(Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    "operation-state path component is not a directory"
                }
            } else {
                try {
                    Files.createDirectory(current)
                    forceDirectory(current.parent.toFile())
                    forceDirectory(current.toFile())
                } catch (_: FileAlreadyExistsException) {
                    require(!Files.isSymbolicLink(current) && Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                        "concurrent operation-state directory creation was unsafe"
                    }
                }
            }
        }
        validateStateDirectory()
    }

    private fun validatePathAncestors() {
        val root = directory.root ?: throw IllegalStateException("operation-state path must be absolute")
        var current = root
        require(!Files.isSymbolicLink(current)) { "operation-state path contains a symlink" }
        for (part in directory) {
            current = current.resolve(part)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(current)) { "operation-state path contains a symlink" }
            }
        }
    }

    private fun validateStateDirectory() {
        validatePathAncestors()
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(directory) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                "operation-state directory is not a regular directory"
            }
        }
    }

    private fun validateRegularNoSymlink(path: java.nio.file.Path, label: String) {
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "$label is not a regular non-symlink file"
        }
    }

    private fun readBounded(path: java.nio.file.Path): ByteArray {
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            require(channel.size() in 1..MAX_STATE_BYTES.toLong()) { "operation-state record is empty or oversized" }
            val output = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(4096)
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                require(count > 0) { "operation-state read made no progress" }
                buffer.flip()
                val bytes = ByteArray(count)
                buffer.get(bytes)
                output.write(bytes)
                require(output.size() <= MAX_STATE_BYTES) { "operation-state record is oversized" }
            }
            return output.toByteArray()
        }
    }

    private fun forceDirectory(path: File) {
        FileChannel.open(path.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
    }

    /** Lifetime-held updater lease. A failed/unknown cleanup retains UPDATING before releasing lock. */
    class Lease internal constructor(
        private val owner: ForeverClientOperationLease,
        private val held: HeldLock,
        private val storageId: String,
        private val token: String,
        private val hasPendingTransaction: () -> Boolean,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            var failure: Throwable? = null
            try {
                owner.closeUpdate(storageId, token, hasPendingTransaction)
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                try {
                    held.close()
                } catch (caught: Throwable) {
                    if (failure == null) failure = caught else failure.addSuppressed(caught)
                }
            }
            failure?.let { throw it }
        }
    }

    internal class HeldLock(
        private val slot: ProcessSlot,
        private val channel: FileChannel,
        private val lock: java.nio.channels.FileLock,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            synchronized(slot) {
                var failure: Throwable? = null
                try {
                    lock.release()
                } catch (caught: Throwable) {
                    failure = caught
                }
                try {
                    channel.close()
                } catch (caught: Throwable) {
                    if (failure == null) failure = caught else failure.addSuppressed(caught)
                } finally {
                    slot.held = false
                }
                failure?.let { throw it }
            }
        }
    }

    internal class ProcessSlot(var held: Boolean = false)

    private data class Record(
        val status: Status,
        val storageId: String,
        val token: String?,
        val observation: StoredObservation = StoredObservation.quiescent(),
    ) {
        companion object {
            val IDLE = Record(Status.IDLE, "", null)
        }
    }

    private data class StoredObservation(
        val kind: String,
        val identities: List<RuntimeIdentity> = emptyList(),
        val reason: String? = null,
    ) {
        companion object {
            fun quiescent() = StoredObservation("QUIESCENT")
            fun active(identities: List<RuntimeIdentity>) = StoredObservation("ACTIVE", identities.toList())
            fun unknown(reason: String) = StoredObservation("UNKNOWN", reason = reason)
        }
    }

    private enum class Status { IDLE, GUEST_RESERVED, GUEST_STARTED, UPDATING }

    companion object {
        private const val SCHEMA = 1
        private const val MAX_STATE_BYTES = 16 * 1024
        private const val MAX_IDENTITIES = 32
        private const val LOCK_FILE = ".forever-client-operation.lock"
        private const val STATE_FILE = ".forever-client-operation.json"
        private const val TEMP_FILE = ".forever-client-operation.tmp"
        private val processSlots = ConcurrentHashMap<String, ProcessSlot>()

        private fun validateStorageId(storageId: String) {
            require(storageId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
                "frozen storage ID is invalid or exceeds its bound"
            }
        }

        private fun validIdentity(identity: RuntimeIdentity): Boolean =
            identity.pid > 0 && identity.startTicks > 0 &&
                identity.bootId.matches(Regex("[A-Za-z0-9._:-]{1,128}")) &&
                (identity.uid == null || identity.uid >= 0)

        private fun validReasonCode(reason: String): Boolean = reason.matches(Regex("[a-z0-9_]{1,64}"))

        private fun isUuid(value: String): Boolean = try {
            UUID.fromString(value).toString() == value
        } catch (_: IllegalArgumentException) {
            false
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
