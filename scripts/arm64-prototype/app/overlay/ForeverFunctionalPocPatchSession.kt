package app.gamenative

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small session used by the functional PoC launch path.
 * `start` returns only cancellation ownership; the worker deliberately has no
 * Future whose completion could be mistaken for process containment. Its
 * only outbound channel besides bounded JSONL is the advisory data-only
 * status callback, which never authorizes writes.
 */
object ForeverFunctionalPocPatchSession {
    private const val MAX_MILLIS = 180_000L
    private const val POLL_MILLIS = 1_000L

    /**
     * WoW-startup-gated window for `codeCaptureEnabled = false` only.
     * TOTAL bounds the whole native lease (including the wait for WoW)
     * from a successful Begin; ACTIVE bounds scanning from the first
     * observed WoW startup and is always clamped to TOTAL. Capture keeps its
     * existing immediate MAX_MILLIS path untouched.
     */
    internal const val TOTAL_MILLIS = 900_000L
    internal const val ACTIVE_MILLIS = 180_000L

    private val armLock = Any()
    private var pendingArm: (() -> Unit)? = null
    private var pendingAttempt: String? = null

    internal fun registerPendingArm(attempt: String, arm: () -> Unit) {
        synchronized(armLock) { pendingAttempt = attempt; pendingArm = arm }
    }

    internal fun clearPendingArmIfMine(arm: () -> Unit) {
        synchronized(armLock) {
            if (pendingArm === arm) { pendingArm = null; pendingAttempt = null }
        }
    }

    /**
     * Single-shot startup signal bound to the run log's unique attempt ID.
     * A delayed old window can neither consume nor arm a replacement session.
     * This schedules reads only: native validation remains the write authority.
     */
    internal fun tryArmFromWowStartup(attempt: String): Boolean {
        val arm = synchronized(armLock) {
            if (pendingAttempt != attempt) return false
            val current = pendingArm
            pendingArm = null
            pendingAttempt = null
            current
        } ?: return false
        arm()
        return true
    }

    /** Capture stays immediate; only non-capture sessions wait for WoW startup. */
    internal fun requiresStartupGating(codeCaptureEnabled: Boolean): Boolean = !codeCaptureEnabled

    /**
     * Advisory-only per-attempt patch status (US-004 AC-12/FR-018). Data-only:
     * carries no raw native text, addresses, memory contents, keys, or
     * secrets, and never authorizes writes. Delivery is best-effort: a
     * throwing observer is swallowed and never affects writer control flow.
     */
    enum class PatchAttemptPhase { DISABLED, PENDING, SEARCHING, VERIFIED, FAILED, ENDED }

    data class PatchStatus(
        val phase: PatchAttemptPhase,
        val everApplied: Boolean = false,
        val captureOnly: Boolean = false,
    )

    /**
     * Gated lease for `codeCaptureEnabled = false`: nativeBegin stays
     * synchronous before Wine. One shared worker loop runs for both modes;
     * writer and pure observer alike wait for [arm] (observed WoW startup)
     * before the first poll; the explicit writer
     * (`apply != null`, authorized by the existing PoC hook) then polls on
     * that same trigger. The first signal also clamps the
     * remaining deadline to `min(begin + total, arm + active)`; later signals
     * never re-arm or extend. A dedicated
     * watchdog thread releases the lease at the dynamic deadline even with
     * no startup, and at the active deadline even while a poll is blocked, so
     * ending never depends on the worker loop returning. The lease ends
     * exactly once; a nativeEnd restore failure is logged fail-closed and
     * never reported as success.
     *
     * The worker only observes: each `poll` result is delivered unchanged to
     * [onScan] for read-only `patch_scan` logging. When [apply] is provided
     * (memory-patch opt-in only; never the capture path), one poll whose
     * `fullScan.validatedStoreCount > 0` with `candidateCapped == false`
     * triggers a single [apply] call for that poll, reported separately via
     * [onApply] for `patch_apply` logging. Top-level `VALIDATED` alone never
     * triggers: global coverage may still be INCOMPLETE while the strict
     * block is positive. Native is the final authority; no address is parsed
     * or exposed here. After a successful apply (APPLIED/ALREADY_PATCHED),
     * later iterations call [apply] instead of [poll] to maintain the same
     * natively retained candidate until DENIED (resume polling) or ERROR
     * (no further applies this session). Success never ends the lease.
     */
    internal class GatedPollSession(
        private val token: String,
        private val totalMillis: Long = TOTAL_MILLIS,
        private val activeMillis: Long = ACTIVE_MILLIS,
        private val pollIntervalMillis: Long = POLL_MILLIS,
        private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
        private val poll: (String) -> String = { value -> ForeverFunctionalPocPatchBridge.nativePoll(value) },
        private val end: (String) -> String = { value -> ForeverFunctionalPocPatchBridge.nativeEnd(value) },
        private val onScan: (String) -> Unit = {},
        private val log: (String, String) -> Unit = { _, _ -> },
        private val apply: ((String) -> String)? = null,
        private val onApply: (String) -> Unit = {},
        private val onStatus: (PatchStatus) -> Unit = {},
        private val attemptId: String = token,
    ) {
        private val stop = AtomicBoolean(false)
        private val ended = AtomicBoolean(false)
        // Arm state lives under monitor: the timestamp and the armed
        // predicate are published atomically, and both waiters re-evaluate
        // them under the same lock before waiting, so no wakeup is lost.
        // -1 means unarmed; the instant is immutable once published.
        private val monitor = Object()
        private var armMs: Long = -1L // Guarded by monitor.
        @Volatile private var beginMs: Long = 0L
        @Volatile private var worker: Thread? = null
        @Volatile private var watchdog: Thread? = null
        @Volatile private var ownArm: (() -> Unit)? = null
        // Advisory status only: everApplied confirms a successful native
        // apply this attempt; applyFailed latches a native apply ERROR so a
        // later poll or a successful end can never overwrite it green.
        // endFailed latches a failed nativeEnd closure: history and failure
        // are independent, so a late apply proof still records everApplied
        // but can never turn the failed terminal into a clean end.
        @Volatile private var everApplied = false
        @Volatile private var applyFailed = false
        @Volatile private var endFailed = false
        // Serializes advisory emissions so a live update can never slip in
        // after the exactly-once terminal. Held only around the
        // decide-and-emit step, never across native calls or the arm
        // monitor, so status can neither block nativeApply/nativeEnd nor
        // delay the watchdog's deadline accounting.
        private val statusLock = Any()
        @Volatile private var terminalSent = false

        private fun emitStatus(status: PatchStatus) {
            runCatching { onStatus(status) }
        }

        fun launch() {
            beginMs = clockMs()
            val armHook: () -> Unit = { arm() }
            try {
                ownArm = armHook
                ForeverFunctionalPocPatchSession.registerPendingArm(attemptId, armHook)
                worker = Thread({ runWorker() }, "forever-functional-poc").apply { isDaemon = true }
                watchdog = Thread({ runWatchdog() }, "forever-functional-poc-watchdog").apply { isDaemon = true }
                // Initial PENDING precedes any thread start: the lease is
                // held but no reading has begun. SEARCHING is published by
                // the worker itself after the startup gate, so no fast thread
                // or terminal can be followed by a revived initial status.
                emitStatus(PatchStatus(PatchAttemptPhase.PENDING))
                worker!!.start()
                watchdog!!.start()
            } catch (failure: Throwable) {
                stop.set(true)
                endOnce()
                synchronized(monitor) { monitor.notifyAll() }
                throw failure
            }
        }

        /** First observed startup only; later signals never extend or restart. */
        fun arm(): Boolean {
            synchronized(monitor) {
                if (stop.get() || ended.get() || armMs >= 0L) return false
                armMs = clockMs()
                monitor.notifyAll()
                return true
            }
        }

        fun cancel() {
            if (stop.compareAndSet(false, true)) {
                endOnce()
                worker?.interrupt()
                synchronized(monitor) { monitor.notifyAll() }
            }
        }

        internal fun isEnded(): Boolean = ended.get()

        /** Active scan window from an arm instant, always clamped to TOTAL. */
        internal fun activeDeadlineMs(beginMs: Long, armMs: Long): Long =
            minOf(armMs + activeMillis, beginMs + totalMillis)

        // Single dynamic bound for writer and watchdog: the global lease
        // until startup clamps it, then min(arm + active, global).
        // Must hold [monitor] when calling the locked variant.
        private fun deadlineLocked(): Long =
            if (armMs >= 0L) minOf(armMs + activeMillis, beginMs + totalMillis)
            else beginMs + totalMillis

        private fun currentDeadline(): Long = synchronized(monitor) { deadlineLocked() }

        private fun endOnce() {
            if (ended.compareAndSet(false, true)) {
                ownArm?.let { ForeverFunctionalPocPatchSession.clearPendingArmIfMine(it) }
                stop.set(true)
                synchronized(monitor) { monitor.notifyAll() }
                val result = runCatching { end(token) }
                    .getOrElse { error ->
                        val detail = ForeverFunctionalPocPatchSession.sanitize(
                            error.message ?: error.javaClass.simpleName,
                        )
                        "status=ERROR error=$detail"
                    }
                // Fail closed: a restore failure keeps the native lease; never claim success.
                runCatching { log(ForeverFunctionalPocPatchSession.statusAndError(result), "") }
                // Terminal, exactly once: a restore ERROR stays an error and
                // never becomes a green end; a prior apply ERROR latch
                // survives even a successful end. Ending never claims the
                // applied key was removed. Composed under the same lock that
                // records late apply history, so an in-flight reply can
                // neither be lost nor revive a live state afterwards.
                synchronized(statusLock) {
                    terminalSent = true
                    if (ForeverFunctionalPocPatchSession.statusOnly(result) != "ENDED") endFailed = true
                    val terminal = when {
                        applyFailed || endFailed ->
                            PatchStatus(PatchAttemptPhase.FAILED, everApplied = everApplied)
                        everApplied -> PatchStatus(PatchAttemptPhase.ENDED, everApplied = true)
                        else -> PatchStatus(PatchAttemptPhase.ENDED)
                    }
                    emitStatus(terminal)
                }
            }
        }

        /**
         * Advisory status for a native apply result. Only APPLIED /
         * ALREADY_PATCHED confirms application; CANCELLED and unparseable
         * results make no claim. DENIED resumes polling with no verified
         * claim, but after a verified apply this attempt is still monitored
         * while no longer currently verified. An explicit ERROR latches
         * failure so a later poll or a successful end can never turn it
         * green. Decided and emitted atomically under statusLock: a
         * concurrent terminal wins over a live update, while a late
         * same-attempt reply only refines recorded history and, at most,
         * the terminal itself — never a live VERIFIED/SEARCHING. Runs after
         * the native call returns, never delaying it; the observer itself
         * is best-effort.
         */
        private fun noteApplyResult(applied: String) {
            synchronized(statusLock) {
                val terminalDone = terminalSent
                when (ForeverFunctionalPocPatchSession.statusOnly(applied)) {
                    "APPLIED", "ALREADY_PATCHED" -> {
                        // History is independent of failure: a late proof is
                        // always recorded, but it can never revive a live
                        // state nor overwrite a failed closure.
                        val firstProof = !everApplied
                        everApplied = true
                        if (!terminalDone && !ended.get() && !applyFailed) {
                            emitStatus(PatchStatus(PatchAttemptPhase.VERIFIED, everApplied = true))
                        } else if (terminalDone && firstProof) {
                            // Late proof after the terminal: refine the
                            // recorded history; a failed closure stays FAILED.
                            if (applyFailed || endFailed) {
                                emitStatus(PatchStatus(PatchAttemptPhase.FAILED, everApplied = true))
                            } else {
                                emitStatus(PatchStatus(PatchAttemptPhase.ENDED, everApplied = true))
                            }
                        }
                        // Else (ended but terminal still composing): the
                        // pending terminal picks up everApplied above.
                    }
                    "ERROR" -> {
                        applyFailed = true
                        if (terminalDone) {
                            // Late error after the terminal: keep/change FAILED.
                            emitStatus(PatchStatus(PatchAttemptPhase.FAILED, everApplied = everApplied))
                        } else if (!ended.get()) {
                            emitStatus(PatchStatus(PatchAttemptPhase.FAILED, everApplied = everApplied))
                        }
                        // Else (ended but terminal still composing): the
                        // pending terminal picks up the latch above.
                    }
                    "DENIED" -> {
                        if (everApplied && !applyFailed && !terminalDone && !ended.get()) {
                            emitStatus(PatchStatus(PatchAttemptPhase.SEARCHING, everApplied = true))
                        }
                    }
                    else -> Unit // CANCELLED/unparseable: no verified claim.
                }
            }
        }

        private fun runWorker() {
            try {
                // Every gated session — writer and pure
                // observer alike — waits for observed WoW startup before
                // reading. The predicate and the global bound
                // are re-evaluated under the same monitor that arm()
                // notifies, so a signal arriving between the check and the
                // wait cannot be missed.
                val global = beginMs + totalMillis
                synchronized(monitor) {
                    while (armMs < 0L && !stop.get() && !ended.get()) {
                        val remaining = global - clockMs()
                        if (remaining <= 0L) break
                        monitor.wait(remaining)
                    }
                }
                val armedAt = synchronized(monitor) { armMs }
                if (armedAt < 0L || stop.get() || ended.get()) return
                // Readings begin now: publish SEARCHING atomically with the
                // terminal guard, so a concurrent terminal wins and no stale
                // PENDING can revive over it. Never held across native
                // calls or the arm monitor.
                synchronized(statusLock) {
                    if (!ended.get() && !terminalSent && !stop.get()) {
                        emitStatus(PatchStatus(PatchAttemptPhase.SEARCHING))
                    }
                }
                log("SEARCH_STARTED", "")
                // Maintain mode: after a successful apply the native side
                // retains the verified candidate, so iterations re-apply the
                // same candidate instead of running another full scan. A
                // slow poll may overrun the deadline, so stop/ended/deadline
                // are re-checked after every poll and before every apply.
                // The bound is dynamic via currentDeadline() (global until
                // startup clamps it), shared with the watchdog, so
                // an arm during a blocking poll shortens the window before
                // the gated apply. For an armed observer this equals the
                // original min(arm + active, global).
                var maintainApply = false
                var applyDisabled = false
                while (!stop.get() && !ended.get() && clockMs() < currentDeadline()) {
                    if (maintainApply && !applyDisabled && apply != null) {
                        if (stop.get() || ended.get() || clockMs() >= currentDeadline()) break
                        val applied = runCatching { apply(token) }
                            .getOrElse { error ->
                                val detail = ForeverFunctionalPocPatchSession.sanitize(
                                    error.message ?: error.javaClass.simpleName,
                                )
                                "status=ERROR error=$detail"
                            }
                        runCatching { onApply(applied) }
                        noteApplyResult(applied)
                        when (ForeverFunctionalPocPatchSession.statusOnly(applied)) {
                            "APPLIED", "ALREADY_PATCHED" -> Unit // Keep maintaining.
                            "DENIED" -> maintainApply = false // Transient; resume polling.
                            else -> {
                                // ERROR (including partial writes/post-verify
                                // failures), CANCELLED, or unparseable: never
                                // apply again this session. Polling continues.
                                applyDisabled = true
                                maintainApply = false
                            }
                        }
                        if (stop.get() || ended.get()) break
                    } else {
                        val result = runCatching { poll(token) }
                            .getOrElse { error ->
                                val detail = ForeverFunctionalPocPatchSession.sanitize(
                                    error.message ?: error.javaClass.simpleName,
                                )
                                "status=ERROR error=$detail"
                            }
                        runCatching { onScan(result) }
                        if (stop.get() || ended.get()) break
                        val applyNow = !applyDisabled && apply != null &&
                            clockMs() < currentDeadline() && !stop.get() && !ended.get() &&
                            shouldApplyScan(result)
                        if (applyNow) {
                            if (stop.get() || ended.get() || clockMs() >= currentDeadline()) break
                            val applied = runCatching { apply(token) }
                                .getOrElse { error ->
                                    val detail = ForeverFunctionalPocPatchSession.sanitize(
                                        error.message ?: error.javaClass.simpleName,
                                    )
                                    "status=ERROR error=$detail"
                                }
                            runCatching { onApply(applied) }
                            noteApplyResult(applied)
                            when (ForeverFunctionalPocPatchSession.statusOnly(applied)) {
                                "APPLIED", "ALREADY_PATCHED" -> maintainApply = true
                                // DENIED (maps briefly changed) stays in poll
                                // mode so the next scan can re-trigger.
                                "DENIED" -> Unit
                                else -> applyDisabled = true
                            }
                            if (stop.get() || ended.get()) break
                        }
                    }
                    val remaining = currentDeadline() - clockMs()
                    if (remaining <= 0L) break
                    Thread.sleep(minOf(pollIntervalMillis, remaining))
                }
            } catch (_: InterruptedException) {
                // Cancellation is cooperative; endOnce still invalidates reads.
            } finally {
                endOnce()
            }
        }

        /**
         * True only for a poll whose strict block is positive:
         * `fullScan.validatedStoreCount > 0` without `candidateCapped`.
         * Top-level VALIDATED alone is not enough, since global coverage may
         * still be INCOMPLETE; ALREADY_PATCHED needs no write. Anything
         * unparseable or candidate-free never applies.
         */
        private fun shouldApplyScan(result: String): Boolean {
            val full = runCatching {
                org.json.JSONObject(result).optJSONObject("fullScan")
            }.getOrNull() ?: return false
            if (full.optBoolean("candidateCapped", false)) return false
            if (full.optInt("validatedStoreCount", 0) <= 0) return false
            val status = runCatching {
                org.json.JSONObject(result).optString("status", "")
            }.getOrDefault("")
            if (status == "ALREADY_PATCHED") return false
            return true
        }

        private fun runWatchdog() {
            try {
                while (!stop.get() && !ended.get()) {
                    // Same protected arm instant as the worker: the deadline
                    // is recomputed under monitor before every wait, so the
                    // arm notification can neither slip past nor redundantly
                    // extend the window.
                    var expired = false
                    synchronized(monitor) {
                        val deadline = deadlineLocked()
                        val remaining = deadline - clockMs()
                        if (remaining <= 0L) {
                            expired = true
                        } else {
                            monitor.wait(remaining)
                        }
                    }
                    if (expired) {
                        endOnce()
                        return
                    }
                }
            } catch (_: InterruptedException) {
                // Cancel/end races here are settled by the exactly-once endOnce.
            } finally {
                endOnce()
            }
        }
    }

    /**
     * Checks the capture lease during preparation only.  It never polls and
     * therefore cannot read process memory or start Wine.  A successful lease
     * is ended immediately; an error is surfaced to the caller fail-closed.
     */
    fun preflightCapture(
        context: Context,
        executable: File,
        logFile: File,
    ) {
        val nonce = "preflight-${java.util.UUID.randomUUID().toString().replace("-", "")}"
        val contract = runCatching {
            ForeverContract.installPrivateCopy(context.applicationContext)
        }.getOrElse { error ->
            val detail = sanitize(error.message ?: error.javaClass.simpleName)
            ForeverFunctionalPoc.record(logFile, "capture_preflight", org.json.JSONObject()
                .put("status", "ERROR").put("detail", detail).put("codeCaptureEnabled", true)
                .put("leaseNonce", nonce).put("readOnly", true).put("bytesWritten", 0))
            throw IllegalStateException(detail)
        }
        val begin = runCatching {
            ForeverFunctionalPocPatchBridge.nativeBegin(executable.absolutePath, nonce, contract.absolutePath, true)
        }.getOrElse { error ->
            val detail = sanitize(error.message ?: error.javaClass.simpleName)
            ForeverFunctionalPoc.record(logFile, "capture_preflight", org.json.JSONObject()
                .put("status", "ERROR").put("detail", detail).put("codeCaptureEnabled", true)
                .put("leaseNonce", nonce).put("readOnly", true).put("bytesWritten", 0))
            throw IllegalStateException(detail)
        }
        if (!begin.startsWith("functional-")) {
            val detail = sanitize(statusAndError(begin))
            ForeverFunctionalPoc.record(logFile, "capture_preflight", org.json.JSONObject()
                .put("status", "ERROR").put("detail", detail).put("codeCaptureEnabled", true)
                .put("leaseNonce", nonce).put("readOnly", true).put("bytesWritten", 0))
            throw IllegalStateException(detail)
        }
        val end = runCatching { ForeverFunctionalPocPatchBridge.nativeEnd(begin) }
            .getOrElse { error -> "status=ERROR error=${sanitize(error.message ?: error.javaClass.simpleName)}" }
        val endStatus = statusAndError(end)
        ForeverFunctionalPoc.record(logFile, "capture_preflight", org.json.JSONObject()
            .put("status", endStatus).put("codeCaptureEnabled", true)
            .put("leaseNonce", nonce).put("readOnly", true).put("bytesWritten", 0))
        if (statusOnly(end) != "ENDED") {
            throw IllegalStateException(sanitize(endStatus))
        }
    }

    /**
     * Uses the existing verified `forever/contract.json` asset, calls
     * nativeBegin synchronously, then observes within bounded leases: the
     * code-capture opt-in polls immediately for at most 180 seconds and never
     * applies, while the startup-gated path scans inside a 15-minute total
     * lease: writer and pure observer alike wait for observed WoW startup,
     * and the explicit writer then polls with that signal clamping
     * the remainder to 180 seconds,
     * maintaining a successfully applied candidate via nativeApply instead of
     * rescanning. The caller
     * sets WOWFOREVER_POC_RUN_ID in the runtime environment before Wine starts.
     */
    fun start(
        context: Context,
        executable: File,
        logFile: File,
        nonce: String,
        codeCaptureEnabled: Boolean,
        applyEnabled: Boolean = false,
        onStatus: (PatchStatus) -> Unit = {},
        timingOriginMs: Long = System.nanoTime() / 1_000_000L,
        summary: ForeverFunctionalPatchSummary? = null,
    ): () -> Unit {
        require(nonce.isNotEmpty() && !nonce.contains('\u0000')) { "nonce must be non-empty and NUL-free" }
        // Writer and capture opt-ins are mutually exclusive: enabling both is
        // rejected with no lease, no poll, and no apply.
        require(!(applyEnabled && codeCaptureEnabled)) { "memory patch and code capture are mutually exclusive" }
        val stop = AtomicBoolean(false)
        val ended = AtomicBoolean(false)
        val contract = ForeverContract.installPrivateCopy(context.applicationContext)
        // Only an explicit applyEnabled starts a lease that may write; the
        // default observer stays read-only. Capture never applies.
        val writesAuthorized = applyEnabled
        fun log(status: String, detail: String = "") {
            // Deliberately excludes memory bytes, heap dumps, and account fields.
            runCatching {
                ForeverFunctionalPoc.record(logFile, "patch_session", org.json.JSONObject()
                    .put("status", status).put("detail", detail)
                    .put("elapsedSinceWatchStartMs", System.nanoTime() / 1_000_000L - timingOriginMs)
                    .put("codeCaptureEnabled", codeCaptureEnabled)
                    .put("leaseNonce", nonce)
                    .put("readOnly", !writesAuthorized).put("writeAuthorized", writesAuthorized)
                    .put("bytesWritten", 0))
            }
        }

        val begin = runCatching {
            ForeverFunctionalPocPatchBridge.nativeBegin(executable.absolutePath, nonce, contract.absolutePath, codeCaptureEnabled)
        }.getOrElse { error ->
            val detail = sanitize(error.message ?: error.javaClass.simpleName)
            log("ERROR", detail)
            throw IllegalStateException(detail)
        }
        if (!begin.startsWith("functional-")) {
            val detail = sanitize(statusAndError(begin))
            log("ERROR", detail)
            throw IllegalStateException(detail)
        }
        val token = begin
        log(if (codeCaptureEnabled) "BEGIN_CODE_CAPTURE_OPT_IN" else "BEGIN")

        if (requiresStartupGating(codeCaptureEnabled)) {
            // Observer that may become the session's one writer: the lease
            // (and its subreaper hold) starts now. No mode polls before the
            // observed WoW startup; after it, an explicit writer polls
            // immediately with that signal clamping the remainder.
            // Cancel, guest exit, onDestroy, or either deadline ends the
            // token exactly once. Polls stay read-only `patch_scan` events; a
            // triggered apply is logged separately as `patch_apply` with its
            // actual bytesWritten/writeAuthorized/readOnly, and success
            // maintains the same native candidate instead of rescanning.
            val gated = try {
                GatedPollSession(
                    token = token,
                    attemptId = logFile.name,
                    poll = { value ->
                        val startMs = System.nanoTime() / 1_000_000L
                        val result = ForeverFunctionalPocPatchBridge.nativePoll(value)
                        org.json.JSONObject(result)
                            .put("pollDurationMs", System.nanoTime() / 1_000_000L - startMs)
                            .put("elapsedSinceWatchStartMs", System.nanoTime() / 1_000_000L - timingOriginMs)
                            .toString()
                    },
                    onScan = { result ->
                        summary?.noteScan(result)
                        runCatching {
                            ForeverFunctionalPoc.record(logFile, "patch_scan", org.json.JSONObject(result))
                        }.onFailure { log("ERROR", "invalid_native_result") }
                    },
                    log = { status, detail -> log(status, detail) },
                    // The one writer callback exists only under the explicit
                    // applyEnabled opt-in; the default observer and capture
                    // pass null and can never write.
                    apply = if (applyEnabled) {
                        { value -> ForeverFunctionalPocPatchBridge.nativeApply(value) }
                    } else {
                        null
                    },
                    onApply = { applied ->
                        summary?.noteApply(applied)
                        runCatching {
                            ForeverFunctionalPoc.record(logFile, "patch_apply", org.json.JSONObject(applied)
                                .put("elapsedSinceWatchStartMs", System.nanoTime() / 1_000_000L - timingOriginMs))
                        }.onFailure { log("ERROR", "invalid_native_apply_result") }
                    },
                    onStatus = { snapshot ->
                        summary?.notePhase(snapshot.phase)
                        onStatus(snapshot)
                    },
                )
            } catch (failure: Throwable) {
                // launch() never ran, so endOnce never ran: release the
                // native lease here exactly once instead of leaking it.
                val detail = sanitize(failure.message ?: failure.javaClass.simpleName)
                runCatching { ForeverFunctionalPocPatchBridge.nativeEnd(token) }
                log("ERROR", detail)
                throw failure
            }
            // launch() covers registration, thread creation, and start: its
            // own failure path already ended the lease, so nothing is added here.
            gated.launch()
            return { gated.cancel() }
        }

        // Capture is diagnostic only: it never applies, so the advisory
        // status carries captureOnly and can never confirm application.
        // Emitted synchronously before the worker starts, so a terminal
        // from an early cancel can never be preceded by a late live update.
        runCatching { onStatus(PatchStatus(PatchAttemptPhase.SEARCHING, captureOnly = true)) }
        val worker = Thread {
            val deadline = System.nanoTime() + MAX_MILLIS * 1_000_000L
            try {
                while (!stop.get() && System.nanoTime() < deadline) {
                    val result = runCatching { ForeverFunctionalPocPatchBridge.nativePoll(token) }
                        .getOrElse { error -> "status=ERROR error=${sanitize(error.message ?: error.javaClass.simpleName)}" }
                    runCatching {
                        ForeverFunctionalPoc.record(logFile, "patch_scan", org.json.JSONObject(result))
                    }.onFailure { log("ERROR", "invalid_native_result") }
                    if (stop.get()) break
                    Thread.sleep(POLL_MILLIS)
                }
            } catch (_: InterruptedException) {
                // Cancellation is cooperative; nativeEnd still invalidates reads.
            } finally {
                if (ended.compareAndSet(false, true)) {
                    val end = runCatching { ForeverFunctionalPocPatchBridge.nativeEnd(token) }
                        .getOrElse { error -> "status=ERROR error=${sanitize(error.message ?: error.javaClass.simpleName)}" }
                    log(statusAndError(end))
                    // A restore ERROR stays an error, never a green end.
                    val terminal = if (statusOnly(end) != "ENDED") {
                        PatchStatus(PatchAttemptPhase.FAILED, captureOnly = true)
                    } else {
                        PatchStatus(PatchAttemptPhase.ENDED, captureOnly = true)
                    }
                    runCatching { onStatus(terminal) }
                }
            }
        }.apply {
            name = "forever-functional-poc"
            isDaemon = true
        }
        worker.start()

        return {
            if (stop.compareAndSet(false, true)) {
                if (ended.compareAndSet(false, true)) {
                    val end = runCatching { ForeverFunctionalPocPatchBridge.nativeEnd(token) }
                        .getOrElse { error -> "status=ERROR error=${sanitize(error.message ?: error.javaClass.simpleName)}" }
                    log(statusAndError(end))
                    val terminal = if (statusOnly(end) != "ENDED") {
                        PatchStatus(PatchAttemptPhase.FAILED, captureOnly = true)
                    } else {
                        PatchStatus(PatchAttemptPhase.ENDED, captureOnly = true)
                    }
                    runCatching { onStatus(terminal) }
                }
                worker.interrupt()
            }
        }
    }

    private fun statusOnly(text: String): String = Regex("(?:\\\"status\\\"|status)\\s*[:=]\\s*\\\"?([A-Z_]+)").find(text)?.groupValues?.get(1) ?: "ERROR"

    private fun statusAndError(text: String): String {
        val status = statusOnly(text)
        val error = Regex("\\\"error\\\"\\s*:\\s*\\\"([^\\\"]*)").find(text)?.groupValues?.get(1)
        return if (error.isNullOrEmpty()) status else "$status:${sanitize(error)}"
    }

    private fun sanitize(value: String): String = value.replace(Regex("[\\r\\n\\t ]+"), "_").take(240)
}
