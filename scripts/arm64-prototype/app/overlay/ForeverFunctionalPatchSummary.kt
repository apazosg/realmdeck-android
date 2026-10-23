package app.gamenative

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject

/** Fixed public counters only. Never copy native JSON, account data or error text to disk. */
class ForeverFunctionalPatchSummary private constructor(
    private val output: File,
    private val originMs: Long,
    private val clockMs: () -> Long,
) {
    private val attemptId = UUID.randomUUID().toString()
    private var scope = "UNREPORTED"
    private var phase = "PENDING"
    private var searchMs: Long? = null
    private var candidateMs: Long? = null
    private var appliedMs: Long? = null
    private var submittedMs: Long? = null
    private var polls = 0L
    private var endedPasses = 0L
    private var bytesRead = 0L
    private var readErrors = 0L
    private val applyCounts = mutableMapOf<String, Long>()
    private var lastApply: String? = null

    fun notePhase(value: ForeverFunctionalPocPatchSession.PatchAttemptPhase) = change {
        phase = value.name
        if (value == ForeverFunctionalPocPatchSession.PatchAttemptPhase.SEARCHING && searchMs == null) {
            searchMs = elapsed()
        }
    }

    fun noteScan(result: String) {
        // Parsing is observational and never escapes to the session or sender.
        val value = runCatching { JSONObject(result) }.getOrNull()
        val full = value?.optJSONObject("fullScan")
        val coverage = value?.optJSONObject("coverage")
        change {
            polls++
            if (full?.optString("scope") == "APPLY_ELIGIBLE_REGIONS") scope = "APPLY_ELIGIBLE_REGIONS"
            if (full?.optBoolean("passComplete", false) == true) endedPasses++
            if ((full?.optLong("validatedStoreCount", 0L) ?: 0L) > 0L && candidateMs == null) {
                candidateMs = elapsed()
            }
            bytesRead += (coverage?.optLong("actualMemoryBytesRead", 0L) ?: 0L).coerceAtLeast(0L)
            readErrors += (coverage?.optLong("readErrors", 0L) ?: 0L).coerceAtLeast(0L)
        }
    }

    fun noteApply(result: String) {
        val parsed = runCatching { JSONObject(result).optString("status") }.getOrDefault("")
        val status = if (parsed in APPLY_STATUSES) parsed else "UNKNOWN"
        change {
            applyCounts[status] = (applyCounts[status] ?: 0L) + 1L
            lastApply = status
            if ((status == "APPLIED" || status == "ALREADY_PATCHED") && appliedMs == null) appliedMs = elapsed()
        }
    }

    /** Called only for the existing sender's SENT result, never arbitrary key input. */
    fun noteAssistedSubmission() = change {
        if (submittedMs == null) submittedMs = elapsed()
    }

    private fun elapsed(): Long = (clockMs() - originMs).coerceAtLeast(0L)

    private fun change(edit: () -> Unit) {
        runCatching {
            synchronized(lock) {
                if (current !== this) return
                edit()
                dirty = true
                scheduleLocked()
            }
        }
    }

    internal fun snapshot(): JSONObject = synchronized(lock) { snapshotLocked() }

    private fun snapshotLocked(): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("attemptId", attemptId)
        .put("scanScope", scope)
        .put("phase", phase)
        .put("searchStartedMs", searchMs ?: JSONObject.NULL)
        .put("firstCandidateObservedMs", candidateMs ?: JSONObject.NULL)
        .put("firstApplicationVerifiedMs", appliedMs ?: JSONObject.NULL)
        .put("assistedSubmittedMs", submittedMs ?: JSONObject.NULL)
        .put("polls", polls)
        .put("endedPasses", endedPasses)
        .put("actualMemoryBytesRead", bytesRead)
        .put("readErrors", readErrors)
        .put("nativeApplyCounts", JSONObject(applyCounts.toMap()))
        .put("lastNativeApplyStatus", lastApply ?: JSONObject.NULL)

    companion object {
        private val APPLY_STATUSES = setOf("APPLIED", "ALREADY_PATCHED", "DENIED", "ERROR", "CANCELLED")
        private val lock = Any()
        private var current: ForeverFunctionalPatchSummary? = null
        private var dirty = false
        private var scheduled = false
        // Coalesce snapshots: at most one task, not one queued task per event.
        private val writer = Executors.newSingleThreadExecutor { task ->
            Thread(task, "forever-patch-summary").apply { isDaemon = true }
        }

        fun begin(
            output: File,
            originMs: Long,
            clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
        ): ForeverFunctionalPatchSummary = synchronized(lock) {
            ForeverFunctionalPatchSummary(output, originMs, clockMs).also {
                current = it
                dirty = true
                scheduleLocked()
            }
        }

        private fun scheduleLocked() {
            if (scheduled) return
            scheduled = true
            runCatching { writer.execute { persistLatest() } }.onFailure { scheduled = false }
        }

        private fun persistLatest() {
            while (true) {
                val work = synchronized(lock) {
                    val owner = current
                    if (owner == null || !dirty) {
                        scheduled = false
                        return
                    }
                    dirty = false
                    owner to owner.snapshotLocked().toString()
                }
                runCatching {
                    val destination = work.first.output.toPath()
                    val temporary = destination.resolveSibling(destination.fileName.toString() + ".tmp")
                    Files.write(temporary, work.second.toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                    // No filesystem IO under the event lock: UI and patch callbacks
                    // never wait on disk. This sole writer orders publications; a
                    // new owner always dirties the next snapshot, so no older work
                    // can overwrite a summary already published for the new owner.
                    if (synchronized(lock) { current === work.first }) {
                        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING)
                    } else {
                        Files.deleteIfExists(temporary)
                    }
                }
            }
        }
    }
}
