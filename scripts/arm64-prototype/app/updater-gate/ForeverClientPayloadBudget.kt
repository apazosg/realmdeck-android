package app.gamenative

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Durable cumulative response-body reservation for one authorized foreign operation.
 * Caller must hold the updater's exclusive operation lease and pass ONE shared instance
 * to all in-process worker transfers. Synchronization is per instance; the lease excludes
 * other operation owners. Reservations are never refunded, including after uncertain I/O.
 */
internal class ForeverClientPayloadBudget private constructor(
    private val directory: java.nio.file.Path,
    private val binding: String,
    private val ceiling: Long,
    private var reserved: Long,
) {
    private val record = directory.resolve(FILE_NAME)
    private var failed = false

    /** Charge before starting a request. Failure poisons this instance and prevents reuse. */
    @Synchronized
    fun reserve(amount: Long) {
        check(!failed) { "payload budget is unavailable" }
        try {
            require(amount >= 0L) { "payload reservation must be nonnegative" }
            validateDirectory(directory)
            val current = readRecord(record, binding, ceiling)
            check(current == reserved) { "payload budget record changed unexpectedly" }
            val next = Math.addExact(reserved, amount)
            if (next > ceiling) throw PayloadBudgetExhaustedException()
            replaceRecord(directory, record, encode(binding, ceiling, next))
            reserved = next
        } catch (failure: Throwable) {
            if (failure !is PayloadBudgetExhaustedException) failed = true
            if (failure is IOException) throw PayloadBudgetPersistenceException(failure)
            throw failure
        }
    }

    @Synchronized
    internal fun reservedBytes(): Long {
        check(!failed) { "payload budget is unavailable" }
        return reserved
    }

    internal class PayloadBudgetExhaustedException : IllegalStateException("authorized response-body payload budget exhausted")
    internal class PayloadBudgetPersistenceException(cause: IOException) :
        IllegalStateException("authorized response-body budget persistence failed", cause)

    companion object {
        internal const val RECORD_FILE_NAME = "payload-budget.v1"
        private const val FILE_NAME = RECORD_FILE_NAME
        private val BINDING = Regex("(?i)[0-9a-f]{64}")

        /** Create only a new ledger; an existing, malformed, or symlink ledger is never reset. */
        fun create(directory: File, binding: String, ceiling: Long): ForeverClientPayloadBudget {
            require(BINDING.matches(binding) && ceiling >= 0L) { "invalid payload budget identity or ceiling" }
            val path = checkedDirectory(directory)
            val record = path.resolve(FILE_NAME)
            val initial = encode(binding.lowercase(), ceiling, 0L)
            FileChannel.open(record, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use {
                writeFully(it, initial)
                it.force(true)
            }
            forceDirectory(path)
            return ForeverClientPayloadBudget(path, binding.lowercase(), ceiling, 0L)
        }

        /** Reopen only an intact ledger with the exact caller identity and ceiling. */
        fun open(directory: File, binding: String, ceiling: Long): ForeverClientPayloadBudget {
            require(BINDING.matches(binding) && ceiling >= 0L) { "invalid payload budget identity or ceiling" }
            val path = checkedDirectory(directory)
            val reserved = readRecord(path.resolve(FILE_NAME), binding.lowercase(), ceiling)
            return ForeverClientPayloadBudget(path, binding.lowercase(), ceiling, reserved)
        }

        private fun checkedDirectory(directory: File): java.nio.file.Path {
            val path = directory.toPath().toAbsolutePath().normalize()
            var current = path.root ?: throw IllegalStateException("payload budget directory has no root")
            for (part in path) {
                current = current.resolve(part)
                check(!Files.isSymbolicLink(current)) { "payload budget path contains a symbolic link" }
            }
            check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "payload budget directory is not a real directory" }
            return path
        }

        private fun validateDirectory(directory: java.nio.file.Path) { checkedDirectory(directory.toFile()) }

        private fun encode(binding: String, ceiling: Long, reserved: Long): ByteArray {
            val body = "v1\n${binding.lowercase()}\n$ceiling\n$reserved\n"
            val checksum = sha256(body.toByteArray(Charsets.US_ASCII))
            return (body + checksum + "\n").toByteArray(Charsets.US_ASCII)
        }

        private fun readRecord(path: java.nio.file.Path, binding: String, ceiling: Long): Long {
            check(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                "payload budget ledger is missing or unsafe"
            }
            val bytes = ByteArray(256)
            val size = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                var count = 0
                while (count < bytes.size) {
                    val read = channel.read(ByteBuffer.wrap(bytes, count, bytes.size - count))
                    if (read < 0) break
                    check(read > 0) { "payload budget ledger read made no progress" }
                    count += read
                }
                check(count < bytes.size && channel.size() == count.toLong()) { "payload budget ledger is oversized" }
                count
            }
            val text = String(bytes, 0, size, Charsets.US_ASCII)
            val lines = text.split('\n')
            check(lines.size == 6 && lines[0] == "v1" && lines[4].matches(Regex("[0-9a-f]{64}")) && lines[5].isEmpty()) {
                "payload budget ledger is malformed"
            }
            val storedBinding = lines[1]
            val storedCeiling = lines[2].toLongOrNull()
            val storedReserved = lines[3].toLongOrNull()
            check(storedBinding == binding && storedCeiling == ceiling && storedReserved != null && storedReserved in 0..ceiling) {
                "payload budget ledger identity or counters do not match"
            }
            val body = "${lines[0]}\n${lines[1]}\n${lines[2]}\n${lines[3]}\n"
            check(MessageDigest.isEqual(lines[4].toByteArray(Charsets.US_ASCII), sha256(body.toByteArray(Charsets.US_ASCII)).toByteArray(Charsets.US_ASCII))) {
                "payload budget ledger checksum mismatch"
            }
            return storedReserved
        }

        private fun replaceRecord(directory: java.nio.file.Path, target: java.nio.file.Path, bytes: ByteArray) {
            check(!Files.isSymbolicLink(target) && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                "payload budget ledger became unsafe"
            }
            val temporary = directory.resolve(".$FILE_NAME.${UUID.randomUUID()}.tmp")
            try {
                FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use {
                    writeFully(it, bytes)
                    it.force(true)
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (failure: AtomicMoveNotSupportedException) {
                    throw IllegalStateException("atomic payload budget update is unsupported", failure)
                }
                forceDirectory(directory)
            } finally {
                // A leftover owned temp is harmless and never treated as ledger state.
                if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(temporary)) Files.delete(temporary)
            }
        }

        private fun writeFully(channel: FileChannel, bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) check(channel.write(buffer) > 0) { "payload budget write made no progress" }
        }

        private fun forceDirectory(directory: java.nio.file.Path) {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
