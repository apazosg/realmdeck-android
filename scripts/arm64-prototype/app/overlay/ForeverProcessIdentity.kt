package app.gamenative

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.io.ByteArrayOutputStream
import java.io.IOException

internal enum class ForeverProcState { ALIVE, GONE, UNKNOWN }
internal data class ForeverProcStat(
    val pid: Int,
    val parent: Int,
    val ticks: Long,
    val pgid: Int? = null,
    val sid: Int? = null,
)

internal object ForeverProcStatParser {
    /** The comm field may itself contain ')'; the final ") " is the delimiter. */
    fun parse(line: String): ForeverProcStat? = runCatching {
        val firstSpace = line.indexOf(' ')
        val close = line.lastIndexOf(") ")
        if (firstSpace <= 0 || close <= firstSpace) return null
        val pid = line.substring(0, firstSpace).toIntOrNull() ?: return null
        val fields = line.substring(close + 2).trim().split(Regex("\\s+"))
        if (pid <= 0 || fields.size <= 19) return null
        val parent = fields[1].toIntOrNull() ?: return null
        val pgid = fields[2].toIntOrNull() ?: return null
        val sid = fields[3].toIntOrNull() ?: return null
        val ticks = fields[19].toLongOrNull() ?: return null
        if (parent < 0 || pgid < 0 || sid < 0 || ticks < 0) return null
        ForeverProcStat(pid, parent, ticks, pgid, sid)
    }.getOrNull()
}

/** An unreadable proc entry is not evidence that the process has exited. */
internal object ForeverProcIdentityPolicy {
    fun validBootId(value: String?): Boolean = value != null &&
        value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
    fun read(pid: Int): ForeverProcStat {
        require(pid > 0)
        val path = Paths.get("/proc/$pid/stat")
        val text = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val bytes = ByteArrayOutputStream(16 * 1024)
            val buffer = ByteArray(1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= 16 * 1024) { "proc stat exceeds bound" }
                bytes.write(buffer, 0, count)
            }
            bytes.toString(Charsets.US_ASCII.name()).trimEnd('\n')
        }
        return requireNotNull(ForeverProcStatParser.parse(text)) { "invalid proc stat" }
    }

    fun state(pid: Int, expectedTicks: Long, read: (Int) -> ForeverProcStat = ::read): ForeverProcState = try {
        if (read(pid).ticks == expectedTicks) ForeverProcState.ALIVE else ForeverProcState.GONE
    } catch (_: NoSuchFileException) {
        ForeverProcState.GONE
    } catch (_: Exception) {
        ForeverProcState.UNKNOWN
    }

    fun belongsTo(root: ForeverProcStat, pid: Int, ticks: Long, read: (Int) -> ForeverProcStat = ::read): Boolean = try {
        if (read(root.pid).ticks != root.ticks) false else {
            var current = read(pid)
            var found = false
            val seen = HashSet<Int>()
            if (current.ticks == ticks) {
                repeat(128) {
                    if (!found && seen.add(current.pid)) {
                        if (current.pid == root.pid) found = current.ticks == root.ticks
                        else if (current.parent > 0) current = read(current.parent)
                    }
                }
            }
            found && read(root.pid).ticks == root.ticks && read(pid).ticks == ticks
        }
    } catch (_: Exception) { false }

    fun bootId(): String? = runCatching {
        String(Files.readAllBytes(Paths.get("/proc/sys/kernel/random/boot_id")), Charsets.US_ASCII).trim()
            .takeIf(::validBootId)
    }.getOrNull()

    fun groupEvidence(pid: Int, expectedTicks: Long): ForeverProcStat? = runCatching {
        val before = read(pid)
        require(before.pid == pid && before.ticks == expectedTicks)
        val after = read(pid)
        require(after == before)
        after
    }.getOrNull()

    fun recoveredQuiescent(state: ForeverRunState, bootId: String?): Boolean {
        // A restarted app has lost its Process handles and cannot prove that
        // reparented children disappeared merely because the root PID is gone.
        if (state.launchAttempted || state.spawnAt != null || state.pid != null || state.terminationUnknown) {
            return state.quiescenceConfirmed ||
                (validBootId(bootId) && validBootId(state.bootId) && !bootId.equals(state.bootId, ignoreCase = true))
        }
        return true // preparation failed/cancelled before the journalled exec gate
    }
}
