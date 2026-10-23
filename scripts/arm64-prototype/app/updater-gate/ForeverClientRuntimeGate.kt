package app.gamenative

import android.content.Context
import android.os.Process
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/** App-owned process observation used by both the functional PoC and the updater gate. */
class ForeverClientRuntimeGate private constructor(
    private val observer: ForeverClientRuntimeObserver,
    stateDirectory: File,
) {
    /** The fixed global lease is shared by every selected client root in this app install. */
    val operations = ForeverClientOperationLease(stateDirectory, observer::observe)

    /** May perform bounded /proc IO. Call on a worker thread, never from UI rendering. */
    fun observe(): RuntimeObservation = observer.observe()

    /** May perform /proc and durable-state IO. Call on a worker thread. */
    fun blockingReason(): String? = operations.blockingReason()

    internal val stateDirectoryPath: Path = stateDirectory.toPath().toAbsolutePath().normalize()

    companion object {
        private const val STATE_DIRECTORY = "forever-client-operation"
        private val instances = ConcurrentHashMap<String, ForeverClientRuntimeGate>()

        /**
         * One factory and one no-backup lease directory per app install, not per Activity
         * or selected storage. Constructing the gate does not scan /proc.
         */
        fun forContext(context: Context): ForeverClientRuntimeGate {
            val appContext = context.applicationContext
            val directory = stateDirectory(appContext)
            return forDirectory(
                appContext.packageName,
                appContext.filesDir.canonicalFile,
                directory,
                AndroidForeverClientProcAccess(),
                Process.myUid(),
            )
        }

        private fun forDirectory(
            packageName: String,
            filesDir: File,
            directory: File,
            proc: ForeverClientProcAccess,
            ownUid: Int,
        ): ForeverClientRuntimeGate {
            val key = directory.toPath().toAbsolutePath().normalize().toString()
            return instances.computeIfAbsent(key) {
                create(packageName, filesDir, directory, proc, ownUid)
            }
        }

        internal fun stateDirectory(context: Context): File = stateDirectory(context.noBackupFilesDir.canonicalFile)

        internal fun stateDirectory(noBackupFilesDir: File): File = File(noBackupFilesDir, STATE_DIRECTORY)

        internal fun forTesting(
            noBackupFilesDir: File,
            filesDir: File,
            packageName: String,
            proc: ForeverClientProcAccess,
            ownUid: Int,
        ): ForeverClientRuntimeGate = forDirectory(
            packageName,
            filesDir,
            stateDirectory(noBackupFilesDir),
            proc,
            ownUid,
        )

        private fun create(
            packageName: String,
            filesDir: File,
            directory: File,
            proc: ForeverClientProcAccess,
            ownUid: Int,
        ): ForeverClientRuntimeGate {
            val runtimeRoots = listOf(
                File(filesDir, "imagefs"),
                File(filesDir, "imagefs_shared/proton"),
            ).map { it.toPath().toAbsolutePath().normalize() }
            return ForeverClientRuntimeGate(
                ForeverClientRuntimeObserver(ownUid, packageName, runtimeRoots, proc),
                directory,
            )
        }
    }
}

/** Minimal injectable /proc reader. No command-line or executable metadata is read before UID proof. */
internal interface ForeverClientProcAccess {
    fun pids(): List<Int>
    fun status(pid: Int): ForeverClientProcStatus
    fun comm(pid: Int): String
    fun stat(pid: Int): String
    fun executable(pid: Int): String
    /** Reads only the first NUL-delimited argv field, and only for owned app_process PIDs. */
    fun firstArgument(pid: Int): String
    fun bootId(): String
}

internal data class ForeverClientProcStatus(val uidFields: List<Int>, val threadCount: Int?)

internal class ForeverClientRuntimeObserver(
    private val ownUid: Int,
    private val packageName: String,
    private val runtimeRoots: List<Path>,
    private val proc: ForeverClientProcAccess,
) {
    fun observe(): RuntimeObservation {
        return try {
            val bootBefore = proc.bootId().trim()
            if (!validBootId(bootBefore)) return RuntimeObservation.Unknown("boot_id_unreadable")
            val first = scan(bootBefore)
            val second = scan(bootBefore)
            val bootAfter = proc.bootId().trim()
            if (bootAfter != bootBefore) return RuntimeObservation.Unknown("boot_id_changed")
            if (first != second) return RuntimeObservation.Unknown("proc_race")
            if (second.active.isEmpty()) RuntimeObservation.Quiescent else RuntimeObservation.Active(second.active)
        } catch (failure: ProcObservationFailure) {
            RuntimeObservation.Unknown(failure.reasonCode)
        } catch (_: Exception) {
            RuntimeObservation.Unknown("proc_unreadable")
        }
    }

    private fun scan(bootId: String): ProcessSnapshot {
        val pids = proc.pids()
        if (pids.size > MAX_PIDS || pids.any { it <= 0 } || pids.toSet().size != pids.size) {
            throw ProcObservationFailure("proc_pid_limit")
        }
        val active = ArrayList<RuntimeIdentity>()
        val terminated = ArrayList<RuntimeIdentity>()
        for (pid in pids.sorted()) {
            val statusBefore = readStatus(pid)
            val uidBefore = statusBefore.uidFields
            when {
                uidBefore.all { it != ownUid } -> continue
                uidBefore.any { it != ownUid } -> throw ProcObservationFailure("proc_uid_inconsistent")
            }

            // UID proof is deliberately first. Do not read comm/stat/exe/cmdline for another UID.
            val statBefore = parseStat(pid, readProc(pid, "proc_stat_unreadable") { proc.stat(pid) })
            if (statBefore.state == 'Z') {
                // A leader Z alone may have live sibling threads. Both status
                // reads must prove Threads:1 as well as the same owned identity.
                val statAfter = confirmIdentity(statBefore, statusBefore)
                terminated += RuntimeIdentity(pid, statAfter.startTicks, bootId, ownUid)
                continue
            }
            val comm = readProc(pid, "proc_comm_unreadable") { proc.comm(pid) }.trimEnd('\n', '\r')
            if (comm.isEmpty() || comm.length > MAX_COMM_CHARS) throw ProcObservationFailure("proc_comm_invalid")
            val executable = readProc(pid, "proc_exe_unreadable") { proc.executable(pid) }
            if (executable.isEmpty() || executable.length > MAX_EXE_CHARS || !executable.startsWith('/')) {
                throw ProcObservationFailure("proc_exe_invalid")
            }
            val executableName = basename(executable)
            val hostProcess = if (executableName in APP_PROCESS_NAMES) {
                val processName = readProc(pid, "host_process_identity_unknown") { proc.firstArgument(pid) }
                if (!isHostProcessName(processName) || !matchesComm(comm, processName)) {
                    throw ProcObservationFailure("host_process_identity_unknown")
                }
                true
            } else false

            val statAfter = confirmIdentity(statBefore, statusBefore)
            if (hostProcess) continue
            // The launcher's log collector is not a guest runtime. Match both
            // immutable system executable and comm, after the same UID/starttime
            // checks above; a same-named private native process remains unknown.
            if (comm == "logcat" && executable == "/system/bin/logcat") continue

            val identity = RuntimeIdentity(pid, statAfter.startTicks, bootId, ownUid)
            if (isKnownRuntimeProcess(comm, executable)) {
                active += identity
                if (active.size > MAX_IDENTITIES) throw ProcObservationFailure("runtime_identity_limit")
            } else {
                // An app-owned native process we do not recognize could be Wine or a guest child.
                throw ProcObservationFailure("unrecognized_owned_process")
            }
        }
        // Keep terminal identities in the two-scan equality proof too: a corpse
        // disappearing, changing state or being replaced is not silent quiescence.
        return ProcessSnapshot(active.sortedBy { it.pid }, terminated.sortedBy { it.pid })
    }

    private fun confirmIdentity(before: ParsedStat, statusBefore: ForeverClientProcStatus): ParsedStat {
        val after = parseStat(before.pid, readProc(before.pid, "proc_stat_unreadable") { proc.stat(before.pid) })
        val statusAfter = readStatus(before.pid)
        if (statusAfter.uidFields != statusBefore.uidFields || after.pid != before.pid || after.startTicks != before.startTicks) {
            throw ProcObservationFailure("proc_pid_reused")
        }
        if ((before.state == 'Z') != (after.state == 'Z')) {
            throw ProcObservationFailure("proc_state_changed")
        }
        if (before.state == 'Z' && (statusBefore.threadCount != 1 || statusAfter.threadCount != 1)) {
            throw ProcObservationFailure("proc_terminal_threads_unconfirmed")
        }
        return after
    }

    private fun readStatus(pid: Int): ForeverClientProcStatus {
        val status = try {
            proc.status(pid)
        } catch (_: Exception) {
            throw ProcObservationFailure("proc_uid_unreadable")
        }
        val values = status.uidFields
        if (values.size != 4 || values.any { it < 0 }) throw ProcObservationFailure("proc_uid_invalid")
        if (values.any { it == ownUid } && values.any { it != ownUid }) {
            throw ProcObservationFailure("proc_uid_inconsistent")
        }
        return status
    }

    private fun <T> readProc(pid: Int, reason: String, read: () -> T): T = try {
        read()
    } catch (_: Exception) {
        throw ProcObservationFailure(reason)
    }

    private fun parseStat(pid: Int, line: String): ParsedStat {
        val close = line.lastIndexOf(") ")
        val firstSpace = line.indexOf(' ')
        if (firstSpace <= 0 || close <= firstSpace || line.substring(0, firstSpace).toIntOrNull() != pid) {
            throw ProcObservationFailure("proc_stat_invalid")
        }
        val fields = line.substring(close + 2).trim().split(Regex("\\s+"))
        // Field 22 (starttime) is index 19 after the state field (field 3).
        if (fields.size <= 19) throw ProcObservationFailure("proc_stat_invalid")
        val state = fields[0].singleOrNull()?.takeIf { it in "RSDZTtXxKWPI" }
            ?: throw ProcObservationFailure("proc_stat_invalid")
        val ticks = fields[19].toLongOrNull()?.takeIf { it > 0L }
            ?: throw ProcObservationFailure("proc_stat_invalid")
        return ParsedStat(pid, ticks, state)
    }

    private fun isHostProcessName(value: String): Boolean {
        if (value.length !in 1..MAX_PROCESS_NAME_CHARS || !value.matches(PROCESS_NAME)) return false
        return value == packageName ||
            (value.startsWith("$packageName:") && value.substringAfter(':').matches(PROCESS_SUFFIX))
    }

    private fun matchesComm(comm: String, processName: String): Boolean {
        val expected = processName.take(MAX_LINUX_COMM_CHARS)
        // Android's Java process naming uses the last 15 characters for comm;
        // Linux/native naming may truncate at the beginning instead. argv0 is
        // still required to match our package, alongside executable/UID/starttime.
        return comm == processName || comm == expected || comm == processName.takeLast(MAX_LINUX_COMM_CHARS)
    }

    private fun isKnownRuntimeProcess(comm: String, executable: String): Boolean {
        if (runtimeRoots.any { isWithin(executable, it) }) return true
        val name = comm.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (name in KNOWN_RUNTIME_NAMES) return true
        return name.matches(GUEST_EXE_NAME)
    }

    private fun isWithin(rawPath: String, root: Path): Boolean {
        val candidate = runCatching { Paths.get(rawPath.removeDeletedSuffix()).normalize() }.getOrNull() ?: return false
        return candidate.isAbsolute && candidate.startsWith(root) && candidate != root
    }

    private fun basename(path: String): String = path.removeDeletedSuffix().substringAfterLast('/')

    private fun String.removeDeletedSuffix(): String = removeSuffix(" (deleted)")

    private data class ParsedStat(val pid: Int, val startTicks: Long, val state: Char)
    private data class ProcessSnapshot(val active: List<RuntimeIdentity>, val terminated: List<RuntimeIdentity>)

    companion object {
        private const val MAX_PIDS = 8192
        private const val MAX_IDENTITIES = 32
        private const val MAX_COMM_CHARS = 256
        private const val MAX_EXE_CHARS = 4096
        private const val MAX_PROCESS_NAME_CHARS = 256
        private const val MAX_LINUX_COMM_CHARS = 15
        private val APP_PROCESS_NAMES = setOf("app_process", "app_process32", "app_process64", "app_process_fork")
        private val KNOWN_RUNTIME_NAMES = setOf(
            "wine", "wine64", "wine-preloader", "wine64-preloader", "wineboot", "wineserver",
            "winhandler", "wowb-arm64.exe", "services.exe",
        )
        private val PROCESS_NAME = Regex("[A-Za-z0-9_.:-]{1,256}")
        private val PROCESS_SUFFIX = Regex("[A-Za-z0-9_.-]{1,200}")
        private val GUEST_EXE_NAME = Regex("[A-Za-z0-9_. -]{1,120}\\.exe")
        private val BOOT_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        private fun validBootId(value: String): Boolean = BOOT_ID.matches(value)
    }
}

/** Pure proof conjunction shared by explicit local recovery and fixture tests. */
internal object ForeverGuestRecoveryProof {
    fun isComplete(
        activePreparedMatches: Boolean,
        selectedStorageMatches: Boolean,
        durableTicketMatches: Boolean,
        noFutureLaunchConfirmed: Boolean,
        environmentGone: Boolean,
        observation: RuntimeObservation,
    ): Boolean = activePreparedMatches && selectedStorageMatches && durableTicketMatches &&
        noFutureLaunchConfirmed && environmentGone && observation === RuntimeObservation.Quiescent

    /**
     * Pure Play-gate triage over the fail-closed [ForeverClientOperationLease]
     * verdict. Only a durable guest reservation for the selected storage may
     * be recovered on explicit Play; every other non-idle verdict (live or
     * unknown runtime, updater state, unreadable gate, held lock) retains
     * exclusion and must stay blocked.
     */
    enum class PlayAction { PROCEED_FRESH, RECOVER_MATCHING_GUEST, RETAIN_EXCLUSION }

    fun playActionFor(blockingReason: String?): PlayAction = when (blockingReason) {
        null -> PlayAction.PROCEED_FRESH
        "guest_start_reserved", "guest_completion_unconfirmed" -> PlayAction.RECOVER_MATCHING_GUEST
        else -> PlayAction.RETAIN_EXCLUSION
    }
}

private class ProcObservationFailure(val reasonCode: String) : Exception()

/** Read-only procfs implementation with strict per-file and PID-count bounds. */
private class AndroidForeverClientProcAccess : ForeverClientProcAccess {
    override fun pids(): List<Int> {
        val result = ArrayList<Int>()
        try {
            Files.newDirectoryStream(Paths.get("/proc")).use { entries ->
                for (entry in entries) {
                    val pid = entry.fileName.toString().toIntOrNull() ?: continue
                    if (result.size == MAX_PIDS) throw ProcObservationFailure("proc_pid_limit")
                    result += pid
                }
            }
        } catch (failure: ProcObservationFailure) {
            throw failure
        } catch (_: Exception) {
            throw ProcObservationFailure("proc_enumeration_failed")
        }
        return result
    }

    override fun status(pid: Int): ForeverClientProcStatus {
        val path = Paths.get("/proc/$pid/status")
        try {
            Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                val line = ByteArrayOutputStream()
                var uids: List<Int>? = null
                var threadCount: Int? = null
                var total = 0
                while (total < MAX_STATUS_BYTES) {
                    val value = input.read()
                    if (value < 0) break
                    total++
                    if (value == '\n'.code) {
                        val text = line.toString(Charsets.US_ASCII.name())
                        if (text.startsWith("Uid:")) {
                            val fields = text.substringAfter("Uid:").trim().split(Regex("\\s+"))
                            uids = fields.map { it.toIntOrNull() ?: throw ProcObservationFailure("proc_uid_invalid") }
                                .takeIf { it.size == 4 } ?: throw ProcObservationFailure("proc_uid_invalid")
                        } else if (text.startsWith("Threads:")) {
                            threadCount = text.substringAfter("Threads:").trim().toIntOrNull()?.takeIf { it > 0 }
                        }
                        if (uids != null && threadCount != null) return ForeverClientProcStatus(uids, threadCount)
                        line.reset()
                    } else {
                        line.write(value)
                    }
                }
                return ForeverClientProcStatus(uids ?: throw ProcObservationFailure("proc_uid_invalid"), threadCount)
            }
        } catch (failure: ProcObservationFailure) {
            throw failure
        } catch (_: Exception) {
            throw ProcObservationFailure("proc_uid_unreadable")
        }
    }

    override fun comm(pid: Int): String = readText(Paths.get("/proc/$pid/comm"), MAX_COMM_BYTES).trimEnd('\n', '\r')

    override fun stat(pid: Int): String = readText(Paths.get("/proc/$pid/stat"), MAX_STAT_BYTES).trimEnd('\n', '\r')

    override fun executable(pid: Int): String = try {
        Files.readSymbolicLink(Paths.get("/proc/$pid/exe")).toString()
    } catch (_: Exception) {
        throw ProcObservationFailure("proc_exe_unreadable")
    }

    override fun firstArgument(pid: Int): String {
        val path = Paths.get("/proc/$pid/cmdline")
        try {
            Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                val bytes = ByteArray(MAX_PROCESS_NAME_BYTES + 1)
                var count = 0
                while (count < bytes.size) {
                    val value = input.read()
                    if (value < 0) throw ProcObservationFailure("host_process_identity_unknown")
                    if (value == 0) break
                    bytes[count++] = value.toByte()
                }
                if (count == 0 || count == bytes.size) throw ProcObservationFailure("host_process_identity_unknown")
                return String(bytes, 0, count, Charsets.UTF_8)
            }
        } catch (failure: ProcObservationFailure) {
            throw failure
        } catch (_: Exception) {
            throw ProcObservationFailure("host_process_identity_unknown")
        }
    }

    override fun bootId(): String = readText(Paths.get("/proc/sys/kernel/random/boot_id"), MAX_BOOT_ID_BYTES).trim()

    private fun readText(path: Path, limit: Int): String {
        try {
            Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                val output = ByteArrayOutputStream(minOf(limit, 4096))
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                    if (count < 0) break
                    if (count == 0) continue
                    if (output.size() + count > limit) throw ProcObservationFailure("proc_file_too_large")
                    output.write(buffer, 0, count)
                }
                return output.toString(Charsets.US_ASCII.name())
            }
        } catch (failure: ProcObservationFailure) {
            throw failure
        } catch (_: IOException) {
            throw ProcObservationFailure("proc_file_unreadable")
        } catch (_: Exception) {
            throw ProcObservationFailure("proc_file_unreadable")
        }
    }

    companion object {
        private const val MAX_PIDS = 8192
        private const val MAX_STATUS_BYTES = 4 * 1024
        private const val MAX_COMM_BYTES = 256
        private const val MAX_STAT_BYTES = 16 * 1024
        private const val MAX_PROCESS_NAME_BYTES = 256
        private const val MAX_BOOT_ID_BYTES = 64
    }
}
