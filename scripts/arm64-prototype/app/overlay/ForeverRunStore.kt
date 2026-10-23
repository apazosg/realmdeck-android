package app.gamenative

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.view.Window
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.FileInputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Privacy gate for an explicitly assisted credential session. The marker is
 * secret-free and lives in app-private files so the host trial can fail closed
 * before collecting anything. A stale marker is deliberately treated as active.
 */
object ForeverAssistedCredentialPrivacy {
    const val RUN_AS_MARKER_PATH = "files/forever/assisted-credentials-sensitive"
    internal const val MARKER_CONTENT = "assisted-credentials-sensitive-v1\n"
    private const val MARKER_NAME = "assisted-credentials-sensitive"
    private val lock = Any()
    private val noFollow = LinkOption.NOFOLLOW_LINKS
    private var diagnosticsUploadsInFlight = 0

    class Session internal constructor(
        private val filesDir: File,
        initialWindow: Window,
        initiallySecure: Boolean,
    ) {
        private val protectedWindows = WeakHashMap<Window, Boolean>().apply {
            put(initialWindow, initiallySecure)
        }
        private var ended = false

        /** Reapply FLAG_SECURE to a recreated/current Activity window. */
        fun protect(window: Window): Boolean = synchronized(lock) {
            if (ended) return@synchronized false
            runCatching {
                val flags = window.attributes.flags
                if (!protectedWindows.containsKey(window)) {
                    protectedWindows[window] = flags and WindowManagerFlags.FLAG_SECURE != 0
                }
                window.addFlags(WindowManagerFlags.FLAG_SECURE)
                true
            }.getOrDefault(false)
        }

        /** Call only when the game/session is over, not when key submission returns. */
        fun end(): Boolean = synchronized(lock) {
            if (ended) return@synchronized true
            if (!clearMarker(filesDir)) return@synchronized false
            protectedWindows.forEach { (window, wasSecure) ->
                if (!wasSecure) window.clearFlags(WindowManagerFlags.FLAG_SECURE)
            }
            protectedWindows.clear()
            ended = true
            true
        }
    }

    private object WindowManagerFlags {
        const val FLAG_SECURE = 0x00002000
    }

    /** Set FLAG_SECURE and durably mark the session before the caller reads any credential. */
    fun begin(context: Context, window: Window): Session? = synchronized(lock) {
        if (diagnosticsUploadsInFlight > 0) return@synchronized null
        val filesDir = context.applicationContext.filesDir
        if (isSensitiveUnlocked(filesDir)) return@synchronized null
        val (sensitiveRunId, sensitiveAttemptId) = try {
            val runId = ForeverRunStoreRegistry.get(context.applicationContext).readCurrent()
                ?.takeIf { it.status == ForeverRunStatus.ACTIVE }?.runId
            val attemptId = ForeverRemoteDiagnosticsStore.readLatestIntent(filesDir)?.attemptId
            runId to attemptId
        } catch (_: Exception) {
            return@synchronized null
        }
        if (sensitiveRunId == null && sensitiveAttemptId == null) return@synchronized null
        val initiallySecure = runCatching {
            window.attributes.flags and WindowManagerFlags.FLAG_SECURE != 0
        }.getOrElse { return@synchronized null }
        try {
            window.addFlags(WindowManagerFlags.FLAG_SECURE)
            writeMarker(filesDir)
            if (sensitiveRunId != null) markSensitiveRun(filesDir, sensitiveRunId)
            if (sensitiveAttemptId != null) markSensitiveAttempt(filesDir, sensitiveAttemptId)
            Session(filesDir, window, initiallySecure)
        } catch (_: Exception) {
            // If marker creation partially succeeded, keep both fail-closed state
            // and FLAG_SECURE. Otherwise restore only the flag we added.
            if (!initiallySecure && !isSensitiveUnlocked(filesDir)) {
                runCatching { window.clearFlags(WindowManagerFlags.FLAG_SECURE) }
            }
            null
        }
    }

    /** Any marker, malformed marker, symlink, or marker-path IO failure is sensitive. */
    fun isSensitive(filesDir: File): Boolean = isSensitiveUnlocked(filesDir)

    /** Caller holds guest admission after proving the previous runtime terminated.
     * Only retire the global session marker; historical RUN/attempt taints remain.
     */
    internal fun finishOrphanedSessionAfterTermination(filesDir: File): Boolean = synchronized(lock) {
        clearMarker(filesDir)
    }

    internal fun <T> withDiagnosticsAllowed(filesDir: File, action: () -> T): T = synchronized(lock) {
        check(!isSensitiveUnlocked(filesDir)) { "diagnostics disabled during assisted credential session" }
        action()
    }

    /** Admit an upload without holding the privacy monitor during network work. */
    internal fun <T> withDiagnosticsUploadAdmission(filesDir: File, action: () -> T): T {
        synchronized(lock) {
            check(!isSensitiveUnlocked(filesDir)) { "diagnostics disabled during assisted credential session" }
            diagnosticsUploadsInFlight++
        }
        try {
            return action()
        } finally {
            synchronized(lock) { diagnosticsUploadsInFlight-- }
        }
    }

    /** Serialize raw output writes against marker creation; denied bytes are not buffered. */
    internal fun runIfDiagnosticsAllowed(filesDir: File, action: () -> Unit): Boolean = synchronized(lock) {
        if (isSensitiveUnlocked(filesDir)) return@synchronized false
        action()
        true
    }

    internal fun runIfDiagnosticsAllowed(filesDir: File, runId: String, action: () -> Unit): Boolean = synchronized(lock) {
        if (isSensitiveUnlocked(filesDir) || isSensitiveRun(filesDir, runId)) return@synchronized false
        action()
        true
    }

    internal fun isSensitiveAttempt(filesDir: File, attemptId: String): Boolean {
        return try {
            if (!attemptId.matches(Regex("attempt-[A-Za-z0-9._-]{1,100}"))) true
            else {
                val remote = File(File(filesDir, "forever"), ForeverRemoteDiagnosticsStore.DIR_NAME)
                when {
                    !pathExists(remote) -> false
                    !Files.isDirectory(remote.toPath(), noFollow) -> true
                    else -> pathExists(File(remote, "$attemptId-assisted-sensitive"))
                }
            }
        } catch (_: Exception) {
            true
        }
    }

    internal fun runIfAttemptDiagnosticsAllowed(filesDir: File, attemptId: String, action: () -> Unit): Boolean = synchronized(lock) {
        if (isSensitiveUnlocked(filesDir) || isSensitiveAttempt(filesDir, attemptId)) return@synchronized false
        action()
        true
    }

    /** Marker-only hooks make the persistence boundary testable without a device Window. */
    internal fun startSensitiveMarkerForTest(filesDir: File): Boolean = synchronized(lock) {
        if (isSensitiveUnlocked(filesDir)) return@synchronized false
        runCatching { writeMarker(filesDir) }.isSuccess
    }

    internal fun endSensitiveMarkerForTest(filesDir: File): Boolean = synchronized(lock) {
        clearMarker(filesDir)
    }

    internal fun markSensitiveRunForTest(filesDir: File, runId: String): Boolean = synchronized(lock) {
        runCatching { markSensitiveRun(filesDir, runId) }.isSuccess
    }

    internal fun isSensitiveRun(filesDir: File, runId: String): Boolean {
        return try {
            if (!runId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"))) true
            else {
                val runs = File(File(filesDir, "forever"), "runs")
                when {
                    !pathExists(runs) -> false
                    !Files.isDirectory(runs.toPath(), noFollow) -> true
                    else -> pathExists(File(runs, "$runId-assisted-sensitive"))
                }
            }
        } catch (_: Exception) {
            true
        }
    }

    /** Shared game logs can retain content across RUNs; never export them after a sensitive RUN. */
    internal fun sharedGameLogsMayBeSensitive(filesDir: File): Boolean = try {
        val runs = File(File(filesDir, "forever"), "runs")
        if (!pathExists(runs)) false
        else if (!Files.isDirectory(runs.toPath(), noFollow)) true
        else Files.newDirectoryStream(runs.toPath(), "*-assisted-sensitive").use { it.iterator().hasNext() }
    } catch (_: Exception) {
        true
    }

    private fun marker(filesDir: File) = File(File(filesDir, "forever"), MARKER_NAME)

    private fun isSensitiveUnlocked(filesDir: File): Boolean {
        return try {
            val parent = File(filesDir, "forever")
            if (pathExists(parent) && !Files.isDirectory(parent.toPath(), noFollow)) true
            else pathExists(marker(filesDir))
        } catch (_: Exception) {
            true
        }
    }

    private fun pathExists(file: File): Boolean = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, noFollow)
        true
    } catch (_: java.nio.file.NoSuchFileException) {
        false
    }

    private fun markSensitiveRun(filesDir: File, runId: String) {
        require(runId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"))) { "invalid sensitive RUN id" }
        val runs = File(File(filesDir, "forever"), "runs")
        require(Files.isDirectory(runs.toPath(), noFollow)) { "managed run directory is unavailable" }
        val target = File(runs, "$runId-assisted-sensitive")
        if (Files.exists(target.toPath(), noFollow)) {
            require(Files.isRegularFile(target.toPath(), noFollow) && target.length() == 0L) {
                "sensitive RUN marker is invalid"
            }
            return
        }
        FileChannel.open(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, noFollow).use {
            it.force(true)
        }
        forcePrivacyDirectory(runs)
    }

    private fun markSensitiveAttempt(filesDir: File, attemptId: String) {
        require(attemptId.matches(Regex("attempt-[A-Za-z0-9._-]{1,100}"))) { "invalid sensitive attempt id" }
        val remote = File(File(filesDir, "forever"), ForeverRemoteDiagnosticsStore.DIR_NAME)
        require(Files.isDirectory(remote.toPath(), noFollow)) { "remote diagnostics directory is unavailable" }
        val target = File(remote, "$attemptId-assisted-sensitive")
        if (pathExists(target)) {
            require(Files.isRegularFile(target.toPath(), noFollow) && target.length() == 0L) {
                "sensitive attempt marker is invalid"
            }
            return
        }
        FileChannel.open(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, noFollow).use {
            it.force(true)
        }
        forcePrivacyDirectory(remote)
    }

    private fun writeMarker(filesDir: File) {
        val parent = File(filesDir, "forever")
        val createdParent = !pathExists(parent)
        if (createdParent) Files.createDirectory(parent.toPath())
        require(Files.isDirectory(parent.toPath(), noFollow)) { "privacy marker parent is not a real directory" }
        val target = marker(filesDir)
        FileChannel.open(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, noFollow).use { channel ->
            val bytes = ByteBuffer.wrap(MARKER_CONTENT.toByteArray(Charsets.US_ASCII))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        forcePrivacyDirectory(parent)
        if (createdParent) forcePrivacyDirectory(filesDir)
    }

    private fun clearMarker(filesDir: File): Boolean {
        val target = marker(filesDir)
        return try {
            if (!pathExists(target)) return true
            if (!Files.isRegularFile(target.toPath(), noFollow) || target.length() > 128) return false
            val contents = Files.newInputStream(target.toPath(), noFollow).use { input ->
                val bytes = ByteArray(129)
                val count = input.read(bytes)
                if (count < 0) ByteArray(0) else bytes.copyOf(count)
            }
            if (!contents.contentEquals(MARKER_CONTENT.toByteArray(Charsets.US_ASCII))) return false
            Files.delete(target.toPath())
            forcePrivacyDirectory(target.parentFile!!)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun forcePrivacyDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ, noFollow).use { it.force(true) }
    }
}

object ForeverRunStoreRegistry {
    private val stores = java.util.concurrent.ConcurrentHashMap<String, ForeverRunStore>()

    fun get(context: Context): ForeverRunStore {
        val app = context.applicationContext
        return stores.computeIfAbsent(app.filesDir.canonicalPath) { ForeverRunStore(app.filesDir, app) }
    }

    internal fun forDirectory(directory: File): ForeverRunStore =
        stores.computeIfAbsent(directory.canonicalPath) { ForeverRunStore(directory, null) }

    internal fun forTestDisposition(
        directory: File,
        disposition: JSONObject,
        authority: JSONObject,
        bootId: String,
    ): ForeverRunStore = ForeverRunStore(directory, null).apply {
        dispositionForTests = JSONObject(disposition.toString())
        admissionAuthorityForTests = JSONObject(authority.toString())
        bootIdForTests = bootId
    }

    internal fun forTestContinuation(
        directory: File,
        continuation: JSONObject,
        authority: JSONObject,
        bootId: String,
    ): ForeverRunStore = ForeverRunStore(directory, null).apply {
        continuationForTests = JSONObject(continuation.toString())
        continuationAuthorityForTests = JSONObject(authority.toString())
        bootIdForTests = bootId
    }
}

class ForeverRunStore internal constructor(directory: File, private val context: Context?) {
    private val configuredBase = directory.absoluteFile
    private val base = directory.canonicalFile
    private val root = File(base, "forever/runs")
    private val current = File(base, "forever/current.json")
    private val processLock = File(base, "forever/.run-store.lock")
    private val sharedJvmLock = sharedLocks.computeIfAbsent(base.path) { ReentrantLock(true) }
    private val lockDepth = ThreadLocal.withInitial { 0 }

    internal var failNextAtomicWriteForTests: Boolean = false
    internal var failNextDirectorySyncForTests: Boolean = false
    internal var failNextWineResultAt: WineResultFaultPoint? = null
    internal var admissionAuthorityForTests: JSONObject? = null
    internal var dispositionForTests: JSONObject? = null
    internal var bootIdForTests: String? = null
    internal var failNextDispositionAt: DispositionFaultPoint? = null
    internal var dispositionMutationForTests: ((DispositionFaultPoint) -> Unit)? = null
    internal var continuationForTests: JSONObject? = null
    internal var continuationAuthorityForTests: JSONObject? = null
    internal var failNextContinuationAt: ContinuationFaultPoint? = null
    internal var continuationMutationForTests: ((ContinuationFaultPoint) -> Unit)? = null

    companion object {
        private val sharedLocks = ConcurrentHashMap<String, ReentrantLock>()
        private const val AUTHORITY_DIRECTORY_FLAG = 0x10000
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 0xff) }

    /** The Wine handoff is metadata for the authoritative RUN, not a second store. */
    data class WineBinding(
        val runId: String,
        val wsRunId: String,
        val nonce: String,
        val outputDirectory: File,
    )

    internal data class WineWitnessProof(
        val status: String,
        val stableBeforeQ: Boolean,
        val qSent: Boolean,
        val reaped: Boolean,
        val exit: Int,
    )

    /** Closed terminal contract; callers cannot smuggle arbitrary positive JSON. */
    internal data class WineResult(
        val terminalStatus: String,
        val nativeStatus: String,
        val closureObserved: Boolean,
        val qualification: Boolean,
        val clientPass: Boolean,
        val supervisorExit: Int?,
        val echild: Boolean,
        val witnessProof: WineWitnessProof,
        val error: String?,
    )

    internal enum class WineResultFaultPoint {
        BEFORE_RESULT_RENAME,
        AFTER_RESULT_RENAME,
        BEFORE_COMMIT_RENAME,
        AFTER_COMMIT_RENAME,
    }

    internal enum class DispositionFaultPoint {
        AFTER_RESERVATION,
        AFTER_ARCHIVE,
        AFTER_RECEIPT,
        AFTER_BINDING,
        AFTER_CONSUMPTION,
        AFTER_CURRENT,
    }

    internal enum class ContinuationFaultPoint {
        AFTER_RESERVATION,
        AFTER_ARCHIVE,
        AFTER_RECEIPT,
        AFTER_BINDING,
        AFTER_CONSUMPTION,
        AFTER_CURRENT,
    }

    private val wineRunPattern = Regex("WS-[0-9a-f]{32}")
    private val wineNoncePattern = Regex("[0-9a-f]{32}")
    private val winePurposes = setOf("prefix-publisher", "supervisor", "witness")
    private val wineFrameDirections = setOf(
        "native-stdin", "native-stdout", "native-stderr", "publisher-stdout", "publisher-stderr",
        "witness-stdout", "witness-stderr",
    )

    private fun ensureManagedRoot() {
        for (dir in listOf(File(base, "forever"), root)) {
            if (!Files.exists(dir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createDirectory(dir.toPath()) } catch (_: FileAlreadyExistsException) { /* another owner created it */ }
            }
            require(Files.isDirectory(dir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "managed run directory is not a real directory" }
        }
    }

    private fun <T> withDurableLock(action: () -> T): T {
        // Every entry point must acquire the instance monitor before the shared
        // JVM/OS lock. Raw output readers also call synchronized metadata reads;
        // the reverse order would deadlock against synchronized control writes.
        synchronized(this) {
        sharedJvmLock.lock()
        if (lockDepth.get() > 0) {
            try { return action() } finally { sharedJvmLock.unlock() }
        }
        try {
            ensureManagedRoot()
            FileChannel.open(processLock.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS).use { channel ->
                channel.lock().use {
                    lockDepth.set(1)
                    try { return action() } finally { lockDepth.set(0) }
                }
            }
        } finally { sharedJvmLock.unlock() }
        }
    }

    @Synchronized
    internal fun outputDirectory(runId: String): File {
        return withDurableLock { outputDirectoryLocked(runId) }
    }

    private fun outputDirectoryLocked(runId: String): File {
        require(safeRunId(runId)) { "invalid run id" }
        ensureManagedRoot()
        val output = File(root, runId)
        require(output.parentFile == root.canonicalFile) { "output path escaped run root" }
        if (!Files.exists(output.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(output.toPath())
        require(Files.isDirectory(output.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        return output
    }

    @Synchronized
    internal fun signalTracePath(runId: String): File {
        return withDurableLock { signalTracePathLocked(runId) }
    }

    private fun signalTracePathLocked(runId: String): File {
        require(safeRunId(runId)) { "invalid run id" }
        ensureManagedRoot()
        val path = File(root, "$runId-sigint.bin")
        require(path.parentFile == root.canonicalFile)
        require(!Files.exists(path.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            "signal trace destination already exists"
        }
        return path
    }

    @Synchronized
    internal fun recordSignalIdentity(runId: String, host: ForeverProcStat, bootId: String) {
        withDurableLock {
            val state = read(runId) ?: error("run not found")
            require(readCurrent()?.runId == runId && state.status == ForeverRunStatus.ACTIVE && state.hostPid == host.pid)
            atomicWrite(File(root, "$runId-sigint-binding.json"), JSONObject()
                .put("schema", 1).put("runId", runId).put("bootId", bootId)
                .put("pid", host.pid).put("startTicks", host.ticks).put("uid", android.os.Process.myUid())
                .put("recordedAt", System.currentTimeMillis()).toString())
        }
    }

    @Synchronized
    fun begin(): ForeverRunState {
        return withDurableLock { beginInternal(recordHostMetadata = true) }
    }

    /**
     * Starts the managed Wine profile while retaining ForeverRunStore as the only RUN owner.
     * The WS/nonce binding is published under the new RUN before a caller can create a
     * publisher, supervisor, or witness process.  This deliberately does not invoke the
     * generic host/process diagnostics path.
     */
    @Synchronized
    internal fun beginWine(wsRunId: String, nonce: String): WineBinding {
        return withDurableLock { beginWineLocked(wsRunId, nonce) }
    }

    private fun beginWineLocked(wsRunId: String, nonce: String): WineBinding {
        require(wineRunPattern.matches(wsRunId)) { "invalid Wine supervisor RUN" }
        require(wineNoncePattern.matches(nonce)) { "invalid Wine supervisor nonce" }
        val previous = readCurrent()
        if (previous?.status == ForeverRunStatus.ACTIVE) {
            return abandonInterruptedWineOnNewBootLocked(previous, wsRunId, nonce)
        }
        if (previous?.status == ForeverRunStatus.FAILED) {
            return continueKnownPrelaunchAbortLocked(previous, wsRunId, nonce)
        }
        requireCandidateAuthority(ForeverPrelaunchContinuation.fixed(), continuationAuthority())
        requireWineHistoricalAdmission(previous)
        val state = beginInternal(recordHostMetadata = false)
        val output = outputDirectory(state.runId)
        val binding = JSONObject()
            .put("schema", 1).put("kind", "wine-binding")
            .put("runId", state.runId).put("wsRunId", wsRunId).put("nonce", nonce)
            .put("status", "BOUND")
        atomicWrite(File(output, "wine-binding.json"), binding.toString(2))
        forceDirectory(output)
        return WineBinding(state.runId, wsRunId, nonce, output)
    }

    /** Consume the single known61 prelaunch failure without rewriting its UNKNOWN history. */
    private fun continueKnownPrelaunchAbortLocked(previous: ForeverRunState, wsRunId: String, nonce: String): WineBinding {
        val contract = continuationForTests ?: context?.let { ForeverPrelaunchContinuation.read(it) }
            ?: ForeverPrelaunchContinuation.fixed()
        val authority = continuationAuthority()
        ForeverPrelaunchContinuation.verifyCandidateAuthority(contract, authority)
        val failure = contract.getJSONObject("knownFailure")
        require(previous.runId == failure.getString("runId") && previous.status == ForeverRunStatus.FAILED &&
            previous.stage == ForeverStage.EXITED && previous.terminationUnknown && !previous.quiescenceConfirmed &&
            !previous.launchAttempted) { "FAILED RUN is not the fixed Wine61 prelaunch failure" }
        require(wineRunPattern.matches(wsRunId) && wineNoncePattern.matches(nonce)) { "invalid Wine continuation identity" }
        require(wsRunId != failure.getString("wsRunId") && nonce != failure.getString("nonce")) { "old WS/nonce may not be reused" }
        val currentBytes = readExact(current, failure.getJSONObject("current").getInt("bytes"))
        val runFile = File(root, "${previous.runId}.json")
        val runBytes = readExact(runFile, currentBytes.size)
        val oldOutput = File(root, previous.runId)
        val bindingFile = File(oldOutput, "wine-binding.json")
        val bindingBytes = readExact(bindingFile, failure.getJSONObject("binding").getInt("bytes"))
        val resultFile = File(oldOutput, "wine-result.json")
        val resultBytes = readExact(resultFile, failure.getJSONObject("wineResult").getInt("bytes"))
        val newBoot = bootIdForTests ?: requireNotNull(ForeverProcIdentityPolicy.bootId()) { "current boot id is unreadable" }
        ForeverPrelaunchContinuation.verifyKnownFailure(contract, currentBytes, runBytes, bindingBytes, resultBytes, newBoot)
        require(currentBytes.contentEquals(runBytes)) { "known Wine61 RUN is not byte-exact" }
        val fixedBefore = validateFixedAuthorityLocked(contract)

        val reservation = File(root, "${previous.runId}-prelaunch-abort-reservation.json")
        val archive = File(root, "${previous.runId}-current-before-prelaunch-abort.json")
        val receipt = File(oldOutput, "prelaunch-abort.json")
        val consumption = File(root, "${previous.runId}-prelaunch-abort-consumption.json")
        require(listOf(reservation, archive, receipt, consumption).none {
            Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS)
        }) { "prelaunch abort was already consumed or is partial" }
        require(!Files.isSymbolicLink(oldOutput.toPath()) && oldOutput.isDirectory) { "known Wine61 output is not private" }
        val newRunId = newRunId()
        val nextOutput = File(root, newRunId)
        require(!Files.exists(nextOutput.toPath(), LinkOption.NOFOLLOW_LINKS)) { "new RUN namespace already exists" }
        val reservationText = JSONObject().put("schema", 1).put("kind", "PRELAUNCH_ABORT")
            .put("state", "RESERVED").put("oldRunId", previous.runId).put("newRunId", newRunId)
            .put("oldWsRunId", failure.getString("wsRunId")).put("oldNonce", failure.getString("nonce"))
            .put("wsRunId", wsRunId).put("nonce", nonce).put("oldBootId", failure.getString("bootId"))
            .put("newBootId", newBoot).put("candidateAuthority", authority)
            .put("contractSha256", "0008db0586756da7b8e20a88725788ce64dc4c290f2d04d360068ff65a64da27").toString(2)
        writeCreateNew(reservation, reservationText.toByteArray(Charsets.UTF_8))
        continuationFault(ContinuationFaultPoint.AFTER_RESERVATION)
        writeCreateNew(archive, currentBytes)
        continuationFault(ContinuationFaultPoint.AFTER_ARCHIVE)
        val receiptText = ForeverPrelaunchContinuation.receipt(
            previous.runId, failure.getString("wsRunId"), failure.getString("nonce"),
            newRunId, wsRunId, nonce, newBoot,
        ).toString(2)
        writeCreateNew(receipt, receiptText.toByteArray(Charsets.UTF_8))
        continuationFault(ContinuationFaultPoint.AFTER_RECEIPT)

        ensureManagedRoot()
        Files.createDirectory(nextOutput.toPath())
        forceDirectory(root)
        val next = ForeverRunMachine.begin(newRunId, null).copy(
            hostPid = context?.let { android.os.Process.myPid() },
            bootId = newBoot,
            events = listOf("previous_run_prelaunch_aborted_on_new_boot"),
        )
        atomicWrite(File(root, "$newRunId.json"), next.toJson().toString(2))
        val binding = JSONObject().put("schema", 1).put("kind", "wine-binding")
            .put("runId", newRunId).put("wsRunId", wsRunId).put("nonce", nonce).put("status", "BOUND")
        writeCreateNew(File(nextOutput, "wine-binding.json"), binding.toString(2).toByteArray(Charsets.UTF_8))
        forceDirectory(nextOutput)
        continuationFault(ContinuationFaultPoint.AFTER_BINDING)
        writeCreateNew(consumption, JSONObject().put("schema", 1).put("kind", "PRELAUNCH_ABORT_CONSUMPTION")
            .put("state", "COMMITTED").put("oldRunId", previous.runId).put("newRunId", newRunId)
            .put("oldWsRunId", failure.getString("wsRunId")).put("oldNonce", failure.getString("nonce"))
            .put("wsRunId", wsRunId).put("nonce", nonce).put("newBootId", newBoot)
            .put("receiptSha256", sha256(receiptText.toByteArray(Charsets.UTF_8))).toString(2).toByteArray(Charsets.UTF_8))
        continuationFault(ContinuationFaultPoint.AFTER_CONSUMPTION)
        validateFixedAuthorityLocked(contract, expectedBefore = fixedBefore)
        require(readFixedAuthorityBytes("files/forever/current.json", currentBytes.size, sha256(currentBytes)).contentEquals(currentBytes)) {
            "known Wine61 current changed before continuation commit"
        }
        atomicWrite(current, next.toJson().toString(2))
        continuationFault(ContinuationFaultPoint.AFTER_CURRENT)
        validateFixedAuthorityLocked(contract, allowCurrentAdvance = true, expectedBefore = fixedBefore)
        val nextCurrentBytes = next.toJson().toString(2).toByteArray(Charsets.UTF_8)
        require(readFixedAuthorityBytes(
            "files/forever/current.json", nextCurrentBytes.size, sha256(nextCurrentBytes),
        ).contentEquals(nextCurrentBytes)) { "current changed after continuation advance" }
        return WineBinding(newRunId, wsRunId, nonce, nextOutput)
    }

    /**
     * Consume the single reviewed Wine60 interruption authorization.  This is
     * deliberately not recovery: the old RUN remains ACTIVE/PREFLIGHT and its
     * state/binding bytes are never rewritten.  A reservation, append-only
     * archive, receipt, new state/binding, and finally current.json form the
     * only admission order.
     */
    private fun abandonInterruptedWineOnNewBootLocked(previous: ForeverRunState, wsRunId: String, nonce: String): WineBinding {
        val contract = dispositionForTests ?: context?.let { ForeverInterruptedRunDisposition.read(it) }
            ?: ForeverInterruptedRunDisposition.fixed()
        val candidateAuthority = admissionAuthority()
        requireCandidateAuthority(contract, candidateAuthority)
        val prior = contract.getJSONObject("prior")
        require(previous.runId == prior.getString("runId") && previous.status == ForeverRunStatus.ACTIVE &&
            previous.stage == ForeverStage.PREFLIGHT) { "active Wine RUN is not the fixed interrupted authority" }
        val currentBytes = readExact(current, prior.getJSONObject("current").getInt("bytes"))
        val runFile = File(root, "${previous.runId}.json")
        val runBytes = readExact(runFile, prior.getJSONObject("current").getInt("bytes"))
        val bindingFile = File(root, "${previous.runId}/wine-binding.json")
        val bindingBytes = readExact(bindingFile, prior.getJSONObject("binding").getInt("bytes"))
        val newBoot = bootIdForTests ?: requireNotNull(ForeverProcIdentityPolicy.bootId()) { "current boot id is unreadable" }
        ForeverInterruptedRunDisposition.verifyAdmission(contract, currentBytes, runBytes, bindingBytes, newBoot)
        require(wineRunPattern.matches(wsRunId) && wsRunId != prior.getString("wsRunId")) { "old WS may not be reused" }
        require(nonce != prior.getString("nonce") && wineNoncePattern.matches(nonce)) { "old nonce may not be reused" }
        require(currentBytes.contentEquals(runBytes)) { "old RUN state is not byte-exact" }
        require(previous.launchAttempted.not() && !previous.terminationUnknown && !previous.quiescenceConfirmed) {
            "interrupted RUN state is no longer exact"
        }

        val oldOutput = bindingFile.parentFile ?: error("old Wine output missing")
        val forbidden = listOf(
            "wine-native-stdin.jsonl", "wine-stdin.jsonl", "wine-native-stdout.jsonl", "wine-native-stderr.jsonl",
            "wine-witness-stdout.jsonl", "wine-witness-stderr.jsonl", "wine-publisher-stdout.jsonl",
            "wine-publisher-stderr.jsonl", "wine-process-create-intents.jsonl", "wine-result.json",
            "wine-result-quarantine.json", "wine-result-commit.json",
        )
        require(forbidden.none { Files.exists(File(oldOutput, it).toPath(), LinkOption.NOFOLLOW_LINKS) }) {
            "interrupted Wine RUN acquired new evidence"
        }

        val newRunId = newRunId()
        val reservation = File(root, "${previous.runId}-abandonment-reservation.json")
        val archive = File(root, "${previous.runId}-current-before-abandonment.json")
        val receipt = File(oldOutput, "abandoned-on-new-boot.json")
        val consumption = File(root, "${previous.runId}-abandonment-consumption.json")
        require(listOf(reservation, archive, receipt, consumption).none {
            Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS)
        }) { "interrupted disposition was already consumed or is partial" }
        validateTransactionPreconditionsLocked(contract)
        val fixedBefore = validateFixedAuthorityLocked(contract)
        val nextOutput = File(root, newRunId)
        require(!Files.exists(nextOutput.toPath(), LinkOption.NOFOLLOW_LINKS)) { "new RUN namespace already exists" }

        val reservationText = JSONObject()
            .put("schema", 1).put("kind", "interrupted-run-disposition")
            .put("state", "RESERVED").put("oldRunId", previous.runId).put("newRunId", newRunId)
            .put("wsRunId", wsRunId).put("nonce", nonce).put("oldBootId", prior.getString("lastObservedBootId"))
            .put("newBootId", newBoot).put("candidateAuthority", candidateAuthority)
            .put("contractSha256", "b65e2a66668e7c137259b68e832f587edc41e9f03ed4d5ee8976d19f8d4e92ad")
            .toString(2)
        writeCreateNew(reservation, reservationText.toByteArray(Charsets.UTF_8))
        dispositionFault(DispositionFaultPoint.AFTER_RESERVATION)
        writeCreateNew(archive, currentBytes)
        dispositionFault(DispositionFaultPoint.AFTER_ARCHIVE)
        val receiptText = JSONObject()
            .put("schema", 1).put("kind", "ABANDONED_ON_NEW_BOOT")
            .put("runId", previous.runId).put("wsRunId", prior.getString("wsRunId"))
            .put("nonce", prior.getString("nonce")).put("oldBootId", prior.getString("lastObservedBootId"))
            .put("newBootId", newBoot).put("terminalStatus", "ABANDONED_ON_NEW_BOOT")
            .put("nativeStatus", "UNKNOWN").put("closureObserved", false)
            .put("qualification", false).put("echild", false)
            .put("reason", "host-capture-interruption")
            .put("candidateAuthority", candidateAuthority)
            .put("oldCurrentSha256", sha256(currentBytes)).put("oldRunSha256", sha256(runBytes))
            .put("oldBindingSha256", sha256(bindingBytes)).toString(2)
        writeCreateNew(receipt, receiptText.toByteArray(Charsets.UTF_8))
        dispositionFault(DispositionFaultPoint.AFTER_RECEIPT)

        ensureManagedRoot()
        Files.createDirectory(nextOutput.toPath())
        forceDirectory(root)
        val next = ForeverRunMachine.begin(newRunId, null).copy(
            hostPid = context?.let { android.os.Process.myPid() },
            bootId = newBoot,
            events = listOf("previous_run_abandoned_on_new_boot"),
        )
        atomicWrite(File(root, "$newRunId.json"), next.toJson().toString(2))
        val binding = JSONObject().put("schema", 1).put("kind", "wine-binding")
            .put("runId", newRunId).put("wsRunId", wsRunId).put("nonce", nonce).put("status", "BOUND")
        writeCreateNew(File(nextOutput, "wine-binding.json"), binding.toString(2).toByteArray(Charsets.UTF_8))
        forceDirectory(nextOutput)
        dispositionFault(DispositionFaultPoint.AFTER_BINDING)
        writeCreateNew(consumption, JSONObject().put("schema", 1).put("state", "COMMITTED")
            .put("oldRunId", previous.runId).put("newRunId", newRunId).put("wsRunId", wsRunId)
            .put("nonce", nonce).put("candidateAuthority", candidateAuthority)
            .put("receiptSha256", sha256(receiptText.toByteArray(Charsets.UTF_8))).toString(2).toByteArray(Charsets.UTF_8))
        dispositionFault(DispositionFaultPoint.AFTER_CONSUMPTION)
        // Re-read the complete old authority, including current.json, directly
        // before the one controlled current.json advance.  Skipping current at
        // this point would validate a possibly different old RUN than the one
        // sealed above.
        validateFixedAuthorityLocked(contract, expectedBefore = fixedBefore)
        val oldCurrentBeforeAdvance = readFixedAuthorityBytes(
            "files/forever/current.json", currentBytes.size, sha256(currentBytes),
        )
        require(oldCurrentBeforeAdvance.contentEquals(currentBytes)) {
            "old current changed immediately before current advance"
        }
        atomicWrite(current, next.toJson().toString(2))
        dispositionFault(DispositionFaultPoint.AFTER_CURRENT)
        validateFixedAuthorityLocked(contract, allowCurrentAdvance = true, expectedBefore = fixedBefore)
        val nextCurrentBytes = next.toJson().toString(2).toByteArray(Charsets.UTF_8)
        require(readFixedAuthorityBytes(
            "files/forever/current.json", nextCurrentBytes.size, sha256(nextCurrentBytes),
        ).contentEquals(nextCurrentBytes)) { "current changed after disposition advance" }
        return WineBinding(newRunId, wsRunId, nonce, nextOutput)
    }

    private fun authorityPath(relative: String): File {
        require(relative.startsWith("files/") && !relative.contains("..")) { "fixed authority path escaped app files" }
        val path = File(base, relative.removePrefix("files/"))
        var cursor = base
        val parts = relative.removePrefix("files/").split('/').filter { it.isNotEmpty() }
        parts.forEachIndexed { index, part ->
            cursor = File(cursor, part)
            require(!Files.isSymbolicLink(cursor.toPath())) { "fixed authority path contains a symlink" }
            if (index < parts.lastIndex && Files.exists(cursor.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isDirectory(cursor.toPath(), LinkOption.NOFOLLOW_LINKS)) { "fixed authority ancestor is not a directory" }
            }
        }
        return path
    }

    private fun validateTransactionPreconditionsLocked(contract: JSONObject) {
        val entries = contract.optJSONArray("transactionPreconditions")
            ?: error("transaction preconditions are missing")
        require(entries.length() == 4) { "fixed authority must contain exactly 4 prospective paths" }
        val seen = HashSet<String>()
        for (index in 0 until entries.length()) {
            val item = entries.getJSONObject(index)
            require(item.optString("status") == "MISSING" && item.optBoolean("prospective")) {
                "transaction precondition is not prospective missing"
            }
            val path = authorityPath(item.getString("path"))
            require(seen.add(item.getString("path"))) { "transaction precondition path is duplicated" }
            require(!Files.exists(path.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path.toPath())) {
                "transaction precondition already exists"
            }
        }
    }

    private fun validateFixedAuthorityLocked(
        contract: JSONObject,
        allowCurrentAdvance: Boolean = false,
        expectedBefore: Map<String, String>? = null,
    ): Map<String, String> {
        val entries = contract.optJSONArray("fixedPaths") ?: error("fixed authority paths are missing")
        val expectedCount = contract.optInt("fixedStatusCount", contract.optJSONObject("evidence")?.optInt("fixedStatusCount", 33) ?: 33)
        require(entries.length() == expectedCount) { "fixed authority path count is not exact" }
        val observed = linkedMapOf<String, String>()
        val seen = HashSet<String>()
        for (index in 0 until entries.length()) {
            val item = entries.getJSONObject(index)
            val relative = item.getString("path")
            require(seen.add(relative)) { "fixed authority path is duplicated: $relative" }
            if (allowCurrentAdvance && relative == "files/forever/current.json") continue
            val status = item.getString("status")
            if (status == "MISSING") {
                require(readFixedAuthorityPresence(relative) == FixedAuthorityPresence.MISSING) {
                    "fixed authority missing path became present: $relative"
                }
                observed[relative] = "MISSING"
                continue
            }
            require(status == "PRESENT") {
                "fixed authority present path is not a regular file: $relative"
            }
            val bytes = item.getInt("bytes")
            val raw = readFixedAuthorityBytes(relative, bytes, item.getString("sha256"))
            require(raw.size == bytes) {
                "fixed authority bytes changed: $relative"
            }
            observed[relative] = item.getString("sha256")
        }
        expectedBefore?.forEach { (path, value) ->
            if (path != "files/forever/current.json") require(observed[path] == value) {
                "fixed authority changed before current advance: $path"
            }
        }
        return observed
    }

    private enum class FixedAuthorityPresence { MISSING, PRESENT }

    private fun readFixedAuthorityPresence(relative: String): FixedAuthorityPresence {
        return if (inspectFixedAuthority(relative, expectedBytes = null, expectedSha256 = null) == null) {
            FixedAuthorityPresence.MISSING
        } else {
            FixedAuthorityPresence.PRESENT
        }
    }

    private fun readFixedAuthorityBytes(relative: String, expectedBytes: Int, expectedSha256: String): ByteArray {
        require(expectedBytes >= 0) { "invalid fixed authority size" }
        return inspectFixedAuthority(relative, expectedBytes, expectedSha256)
            ?: error("fixed authority path is missing: $relative")
    }

    /** Reads only the sealed 33-path authority, never generic store metadata. */
    private fun inspectFixedAuthority(
        relative: String,
        expectedBytes: Int?,
        expectedSha256: String?,
    ): ByteArray? {
        require(relative.startsWith("files/") && !relative.contains("..")) {
            "fixed authority path escaped app files"
        }
        val parts = relative.removePrefix("files/").split('/').filter { it.isNotEmpty() }
        require(parts.isNotEmpty() && parts.none { it == "." || it == ".." }) {
            "fixed authority path is malformed"
        }
        val rootFd = openFixedAuthorityRoot()
        var parent = rootFd
        var parentOwned = false
        try {
            parts.forEachIndexed { index, component ->
                val final = index == parts.lastIndex
                val anchor = ParcelFileDescriptor.dup(parent)
                try {
                    val procPath = "/proc/self/fd/${anchor.fd}/$component"
                    val pathBefore = lstatOrMissing(procPath)
                    if (pathBefore == null) return null
                    require(!OsConstants.S_ISLNK(pathBefore.st_mode)) {
                        "fixed authority path contains a symlink: $relative"
                    }
                    if (!final) require(OsConstants.S_ISDIR(pathBefore.st_mode)) {
                        "fixed authority ancestor is not a directory: $relative"
                    }
                    val flags = OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
                        if (final) 0 else AUTHORITY_DIRECTORY_FLAG
                    val child = Os.open(procPath, flags, 0)
                    var childOwned = true
                    try {
                        val opened = Os.fstat(child)
                        require(sameAuthorityObject(pathBefore, opened)) {
                            "fixed authority path changed while opening: $relative"
                        }
                        require(opened.st_uid == Os.getuid()) {
                            "fixed authority component is not owned by this process: $relative"
                        }
                        if (final) {
                            require(OsConstants.S_ISREG(opened.st_mode)) {
                                "fixed authority present path is not a regular file: $relative"
                            }
                            if (expectedBytes == null) {
                                return ByteArray(0)
                            }
                            val bytes = readFixedAuthorityFd(child, opened, procPath, expectedBytes)
                            require(sha256(bytes) == expectedSha256) {
                                "fixed authority bytes changed: $relative"
                            }
                            return bytes
                        }
                        if (parentOwned) {
                            val previousParent = parent
                            parentOwned = false
                            Os.close(previousParent)
                        }
                        parent = child
                        parentOwned = true
                        childOwned = false
                    } finally {
                        if (childOwned) runCatching { Os.close(child) }
                    }
                } finally {
                    anchor.close()
                }
            }
            error("fixed authority path is empty: $relative")
        } finally {
            if (parentOwned) runCatching { Os.close(parent) }
            // parent is a separately opened child after the first directory
            // component.  It never owns rootFd, so close both descriptors on
            // every traversal path (including a successful leaf read).
            runCatching { Os.close(rootFd) }
        }
    }

    private fun openFixedAuthorityRoot(): FileDescriptor {
        val canonical = base.canonicalFile
        require(context == null || context.filesDir.canonicalFile == canonical) {
            "fixed authority root is not the application filesDir"
        }
        require(!Files.isSymbolicLink(configuredBase.toPath()) &&
            !Files.isSymbolicLink(canonical.toPath()) && canonical.isDirectory) {
            "fixed authority root is not a real directory"
        }
        val fd = Os.open(canonical.path, OsConstants.O_RDONLY or AUTHORITY_DIRECTORY_FLAG or OsConstants.O_NOFOLLOW, 0)
        try {
            val stat = Os.fstat(fd)
            val pathStat = Os.lstat(canonical.path)
            require(sameAuthorityObject(pathStat, stat) &&
                OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Os.getuid()) {
                "fixed authority root is not owned by this process"
            }
        } catch (failure: Throwable) {
            runCatching { Os.close(fd) }
            throw failure
        }
        return fd
    }

    private fun lstatOrMissing(path: String): android.system.StructStat? = try {
        Os.lstat(path)
    } catch (failure: ErrnoException) {
        if (failure.errno == OsConstants.ENOENT) null else throw failure
    }

    private fun sameAuthorityObject(left: android.system.StructStat, right: android.system.StructStat): Boolean =
        left.st_dev == right.st_dev && left.st_ino == right.st_ino && left.st_mode == right.st_mode

    private fun readFixedAuthorityFd(
        fd: FileDescriptor,
        beforeFd: android.system.StructStat,
        procPath: String,
        expectedBytes: Int,
    ): ByteArray {
        require(beforeFd.st_size == expectedBytes.toLong()) { "fixed authority size changed" }
        val bytes = FileInputStream(Os.dup(fd)).use { readBounded(it, expectedBytes) }
        val afterFd = Os.fstat(fd)
        require(sameAuthorityObject(beforeFd, afterFd) && afterFd.st_size == bytes.size.toLong()) {
            "fixed authority changed during read"
        }
        val afterPath = lstatOrMissing(procPath)
        require(afterPath != null && sameAuthorityObject(beforeFd, afterPath) &&
            afterPath.st_size == bytes.size.toLong()) {
            "fixed authority path changed during read"
        }
        return bytes
    }

    private fun admissionAuthority(): JSONObject {
        admissionAuthorityForTests?.let { return JSONObject(it.toString()) }
        val app = context ?: return ForeverInterruptedRunDisposition.candidateAuthority()
        require(app.packageName == "com.wowforever.arm64probe") { "candidate package identity mismatch" }
        require(Build.VERSION.SDK_INT == 34) { "candidate API identity mismatch" }
        require(Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "candidate ABI identity mismatch" }
        require(android.os.Process.myUid() == 10330) { "candidate UID identity mismatch" }
        val appInfo = app.applicationInfo
        require(appInfo != null && (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            appInfo.minSdkVersion == 29 && appInfo.targetSdkVersion == 36) {
            "candidate manifest identity mismatch"
        }
        val info = app.packageManager.getPackageInfo(
            app.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        require(info.longVersionCode == 61L && info.versionName == "0.2.37-automated") {
            "candidate version identity mismatch"
        }
        val signers = info.signingInfo?.apkContentsSigners ?: emptyArray()
        require(signers.size == 1) { "candidate certificate identity is ambiguous" }
        return JSONObject()
            .put("package", app.packageName)
            .put("versionCode", info.longVersionCode.toInt())
            .put("versionName", info.versionName)
            .put("abi", "arm64-v8a")
            .put("api", Build.VERSION.SDK_INT)
            .put("uid", android.os.Process.myUid())
            .put("certificateSha256", sha256(signers[0].toByteArray()))
            .put("debuggable", true).put("minSdk", appInfo.minSdkVersion).put("targetSdk", appInfo.targetSdkVersion)
    }

    private fun continuationAuthority(): JSONObject {
        continuationAuthorityForTests?.let { return JSONObject(it.toString()) }
        val app = context ?: return ForeverPrelaunchContinuation.candidateAuthority()
        require(app.packageName == "com.wowforever.arm64probe") { "candidate package identity mismatch" }
        require(Build.VERSION.SDK_INT == 34 && Build.SUPPORTED_ABIS.contains("arm64-v8a") && android.os.Process.myUid() == 10330) {
            "candidate62 platform identity mismatch"
        }
        val appInfo = app.applicationInfo
        require(appInfo != null && (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            appInfo.minSdkVersion == 29 && appInfo.targetSdkVersion == 36) { "candidate62 manifest identity mismatch" }
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        require(info.longVersionCode == 62L && info.versionName == "0.2.38-automated") { "candidate62 version identity mismatch" }
        val signers = info.signingInfo?.apkContentsSigners ?: emptyArray()
        require(signers.size == 1) { "candidate62 certificate identity is ambiguous" }
        return ForeverPrelaunchContinuation.candidateAuthority().put("certificateSha256", sha256(signers[0].toByteArray()))
    }

    private fun requireCandidateAuthority(contract: JSONObject, authority: JSONObject) {
        if (contract.optString("kind") == "PRELAUNCH_CONTINUATION") {
            ForeverPrelaunchContinuation.verifyCandidateAuthority(contract, authority)
        } else {
            ForeverInterruptedRunDisposition.verifyCandidateAuthority(contract, authority)
        }
    }

    private fun dispositionFault(point: DispositionFaultPoint) {
        if (failNextDispositionAt == point) {
            failNextDispositionAt = null
            error("injected interrupted disposition failure at $point")
        }
        dispositionMutationForTests?.invoke(point)
    }

    private fun continuationFault(point: ContinuationFaultPoint) {
        if (failNextContinuationAt == point) {
            failNextContinuationAt = null
            error("injected continuation failure at $point")
        }
        continuationMutationForTests?.invoke(point)
    }

    /** Durable marker immediately before submitting one owned process creation purpose. */
    @Synchronized
    internal fun recordWineProcessCreateIntent(
        runId: String,
        wsRunId: String,
        nonce: String,
        purpose: String,
    ) = withDurableLock {
        require(wineRunPattern.matches(wsRunId) && wineNoncePattern.matches(nonce))
        val binding = requireWineBinding(runId, wsRunId, nonce)
        require(purpose in winePurposes) { "invalid Wine process purpose" }
        val file = File(binding.outputDirectory, "wine-process-create-intents.jsonl")
        val prior = readJsonLines(file)
        require(prior.none { it.optString("purpose") == purpose }) { "Wine process purpose already recorded" }
        appendWineLine(file, JSONObject()
            .put("schema", 1).put("kind", "process_create_intent")
            .put("runId", runId).put("wsRunId", wsRunId).put("nonce", nonce)
            .put("purpose", purpose).toString())
    }

    /** Stores the exact bounded IPC frame before it is interpreted by the controller. */
    @Synchronized
    internal fun recordWineRawFrame(
        runId: String,
        wsRunId: String,
        nonce: String,
        direction: String,
        frame: ByteArray,
    ) = withDurableLock {
        require(direction in wineFrameDirections)
        val binding = requireWineBinding(runId, wsRunId, nonce)
        require(frame.isNotEmpty() && frame.size <= 8 * 1024 && frame.last() == '\n'.code.toByte())
        require(frame.none { it == '\r'.code.toByte() })
        appendWineBytes(File(binding.outputDirectory, "wine-$direction.jsonl"), frame)
    }

    internal fun recordWineRawOutput(
        runId: String,
        wsRunId: String,
        nonce: String,
        direction: String,
        bytes: ByteArray,
    ) = withDurableLock {
        require(direction.endsWith("-stderr") || direction == "publisher-stdout")
        val binding = requireWineBinding(runId, wsRunId, nonce)
        require(bytes.isNotEmpty() && bytes.size <= 8 * 1024)
        appendWineBytes(File(binding.outputDirectory, "wine-$direction.jsonl"), bytes)
    }

    /** Atomic terminal protocol result; state is intentionally separate from closure. */
    @Synchronized
    internal fun writeWineResult(
        runId: String,
        wsRunId: String,
        nonce: String,
        result: WineResult,
    ) = withDurableLock {
        val binding = requireWineBinding(runId, wsRunId, nonce, allowTerminal = true)
        val state = requireNotNull(read(runId))
        require(!Files.exists(File(binding.outputDirectory, "wine-result.json").toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Wine result is one-shot"
        }
        require(result.terminalStatus in setOf("PASS", "CANCELLED", "UNKNOWN"))
        if (result.terminalStatus == "UNKNOWN") {
            require(!result.qualification && result.error.orEmpty().isNotEmpty()) { "UNKNOWN Wine result must be diagnostic" }
            require(state.status == ForeverRunStatus.FAILED) { "UNKNOWN Wine result requires FAILED generic state" }
        } else {
            require(result.terminalStatus == result.nativeStatus && result.qualification && result.closureObserved &&
                (result.terminalStatus != "PASS" || result.clientPass) && result.supervisorExit == 0 && result.echild &&
                result.witnessProof.status == "PASS" && result.witnessProof.stableBeforeQ &&
                result.witnessProof.qSent && result.witnessProof.reaped && result.witnessProof.exit == 0 &&
                result.error.isNullOrEmpty()) { "positive Wine result proof mismatch" }
            require(!state.terminationUnknown && state.quiescenceConfirmed)
            require((result.terminalStatus == "PASS" && state.status == ForeverRunStatus.EXITED) ||
                (result.terminalStatus == "CANCELLED" && state.status == ForeverRunStatus.CANCELLED)) {
                "positive Wine result does not match generic state"
            }
        }
        val value = JSONObject()
            .put("schema", 1).put("runId", runId).put("wsRunId", wsRunId).put("nonce", nonce)
            .put("terminalStatus", result.terminalStatus).put("nativeStatus", result.nativeStatus)
            .put("closureObserved", result.closureObserved).put("qualification", result.qualification)
            .put("clientPass", result.clientPass).put("supervisorExit", result.supervisorExit ?: JSONObject.NULL)
            .put("echild", result.echild)
            .put("witnessProof", JSONObject().put("status", result.witnessProof.status)
                .put("stableBeforeQ", result.witnessProof.stableBeforeQ).put("qSent", result.witnessProof.qSent)
                .put("reaped", result.witnessProof.reaped).put("exit", result.witnessProof.exit))
            .put("error", result.error ?: JSONObject.NULL)
        val destination = File(binding.outputDirectory, "wine-result.json")
        val resultText = value.toString(2)
        val quarantine = File(binding.outputDirectory, "wine-result-quarantine.json")
        val commit = File(binding.outputDirectory, "wine-result-commit.json")
        val admission = File(root, "$runId-wine-result-admission.json")
        require(!Files.exists(quarantine.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.exists(commit.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Wine result commit may not be retried" }
        require(!Files.exists(admission.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Wine admission is one-shot" }
        if (result.terminalStatus != "UNKNOWN") {
            atomicWrite(quarantine, JSONObject().put("schema", 1).put("state", "PREPARE")
                .put("runId", runId).put("wsRunId", wsRunId).put("nonce", nonce)
                .put("terminalStatus", result.terminalStatus).toString(2))
            forceDirectory(binding.outputDirectory)
        }
        try {
            failWineResultAt(WineResultFaultPoint.BEFORE_RESULT_RENAME)
            atomicWrite(destination, resultText)
            failWineResultAt(WineResultFaultPoint.AFTER_RESULT_RENAME)
            forceDirectory(binding.outputDirectory)
            if (result.terminalStatus != "UNKNOWN") {
                failWineResultAt(WineResultFaultPoint.BEFORE_COMMIT_RENAME)
                atomicWrite(commit, JSONObject().put("schema", 1).put("state", "COMMITTED")
                    .put("runId", runId).put("wsRunId", wsRunId).put("nonce", nonce)
                    .put("terminalStatus", result.terminalStatus).put("resultSha256", sha256(resultText.toByteArray())).toString(2))
                failWineResultAt(WineResultFaultPoint.AFTER_COMMIT_RENAME)
                forceDirectory(binding.outputDirectory)
                // A commit whose output-directory sync failed must never be
                // admitted, even if an unsynced deletion later resurrects it.
                // Publish the independent root gate only after every output
                // commit/sync boundary completed successfully.
                atomicWrite(admission, JSONObject().put("schema", 1).put("state", "COMMITTED")
                    .put("runId", runId).put("wsRunId", wsRunId).put("nonce", nonce)
                    .put("terminalStatus", result.terminalStatus)
                    .put("resultSha256", sha256(resultText.toByteArray())).toString(2))
                forceDirectory(root)
            }
        } catch (failure: Throwable) {
            // A result whose parent metadata was not synced is not a positive
            // commit.  Do not let a later admission mistake the renamed but
            // unconfirmed file for durable evidence.
            runCatching { Files.deleteIfExists(destination.toPath()) }
            throw failure
        }
    }

    @Synchronized
    internal fun readWineBinding(runId: String): WineBinding? = runCatching {
        val output = outputDirectory(runId)
        val value = JSONObject(readBounded(File(output, "wine-binding.json"), 8 * 1024).toString(Charsets.UTF_8))
        require(value.getInt("schema") == 1 && value.getString("kind") == "wine-binding")
        require(value.getString("status") == "BOUND" && value.getString("runId") == runId)
        require(wineRunPattern.matches(value.getString("wsRunId")) && wineNoncePattern.matches(value.getString("nonce")))
        WineBinding(runId, value.getString("wsRunId"), value.getString("nonce"), output)
    }.getOrNull()

    private fun beginInternal(recordHostMetadata: Boolean): ForeverRunState {
        val previous = readCurrent()
        if (!recordHostMetadata) archiveCurrentByteExact(previous)
        val state = ForeverRunMachine.begin(newRunId(), previous).copy(
            hostPid = context?.let { android.os.Process.myPid() },
        )
        write(state)
        if (recordHostMetadata) context?.let {
            ForeverProcessDiagnostics.recordHostRun(it, state.runId)
            appendRaw(state.runId, JSONObject().put("event", "device_metadata")
                .put("at", System.currentTimeMillis()).put("package", it.packageName)
                .put("hostPid", android.os.Process.myPid())
                .put("sdk", android.os.Build.VERSION.SDK_INT).put("manufacturer", android.os.Build.MANUFACTURER)
                .put("model", android.os.Build.MODEL).put("hardware", android.os.Build.HARDWARE)
                .put("abis", org.json.JSONArray(android.os.Build.SUPPORTED_ABIS.toList()))
                .put("gpuEffective", "unmeasured"))
        }
        return state
    }

    private fun requireWineHistoricalAdmission(previous: ForeverRunState?) {
        if (previous == null) return
        val bindingFile = File(root, "${previous.runId}/wine-binding.json")
        val priorBinding = if (Files.exists(bindingFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            requireNotNull(readWineBinding(previous.runId)) { "prior Wine binding is corrupt" }
        } else null
        val priorOutput = File(root, previous.runId)
        val hasPartialWineEvidence = priorOutput.isDirectory && priorOutput.listFiles()?.any {
            !Files.isSymbolicLink(it.toPath()) && (it.name == "wine-binding.json" || it.name.startsWith("wine-"))
        } == true
        require(!hasPartialWineEvidence || priorBinding != null) { "prior Wine RUN binding is incomplete" }
        if (priorBinding != null) {
            val resultBytes = runCatching {
                readBounded(File(priorBinding.outputDirectory, "wine-result.json"), 32 * 1024)
            }.getOrNull()
            val result = resultBytes?.let { runCatching { JSONObject(it.toString(Charsets.UTF_8)) }.getOrNull() }
            val quarantine = runCatching {
                JSONObject(readBounded(File(priorBinding.outputDirectory, "wine-result-quarantine.json"), 8 * 1024).toString(Charsets.UTF_8))
            }.getOrNull()
            val commit = runCatching {
                JSONObject(readBounded(File(priorBinding.outputDirectory, "wine-result-commit.json"), 8 * 1024).toString(Charsets.UTF_8))
            }.getOrNull()
            val admission = runCatching {
                JSONObject(readBounded(File(root, "${previous.runId}-wine-result-admission.json"), 8 * 1024).toString(Charsets.UTF_8))
            }.getOrNull()
            require(result != null &&
                admission != null && admission.getInt("schema") == 1 && admission.getString("state") == "COMMITTED" &&
                admission.getString("runId") == previous.runId && admission.getString("wsRunId") == priorBinding.wsRunId &&
                admission.getString("nonce") == priorBinding.nonce &&
                admission.getString("resultSha256") == sha256(requireNotNull(resultBytes)) &&
                admission.getString("terminalStatus") == result.getString("terminalStatus") &&
                quarantine != null && quarantine.getString("state") == "PREPARE" &&
                quarantine.getString("runId") == previous.runId && quarantine.getString("wsRunId") == priorBinding.wsRunId &&
                quarantine.getString("nonce") == priorBinding.nonce &&
                commit != null && commit.getString("state") == "COMMITTED" &&
                commit.getString("runId") == previous.runId && commit.getString("wsRunId") == priorBinding.wsRunId &&
                commit.getString("nonce") == priorBinding.nonce &&
                commit.getString("resultSha256") == sha256(requireNotNull(resultBytes)) &&
                result.getInt("schema") == 1 &&
                result.getString("runId") == previous.runId &&
                result.getString("wsRunId") == priorBinding.wsRunId &&
                result.getString("nonce") == priorBinding.nonce &&
                result.getString("terminalStatus") in setOf("PASS", "CANCELLED") &&
                result.getString("nativeStatus") == result.getString("terminalStatus") &&
                result.getBoolean("qualification") &&
                result.getBoolean("closureObserved") &&
                (result.getString("terminalStatus") != "PASS" || result.getBoolean("clientPass")) &&
                result.getInt("supervisorExit") == 0 &&
                result.getBoolean("echild") &&
                result.getJSONObject("witnessProof").getString("status") == "PASS" &&
                result.getJSONObject("witnessProof").getBoolean("stableBeforeQ") &&
                result.getJSONObject("witnessProof").getBoolean("qSent") &&
                result.getJSONObject("witnessProof").getBoolean("reaped") &&
                result.getJSONObject("witnessProof").getInt("exit") == 0 &&
                !previous.terminationUnknown && previous.quiescenceConfirmed &&
                ((result.getString("terminalStatus") == "PASS" && previous.status == ForeverRunStatus.EXITED) ||
                    (result.getString("terminalStatus") == "CANCELLED" && previous.status == ForeverRunStatus.CANCELLED)) &&
                (!result.has("error") || result.isNull("error") || result.optString("error").isEmpty())) {
                "prior Wine RUN is unproven or unknown"
            }
            return
        }
    }

    private fun archiveCurrentByteExact(previous: ForeverRunState?) {
        if (previous == null || !Files.isRegularFile(current.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        ensureManagedRoot()
        val archive = File(root, "${previous.runId}-current-before-wine.json")
        require(!Files.exists(archive.toPath(), LinkOption.NOFOLLOW_LINKS))
        val bytes = Files.readAllBytes(current.toPath())
        FileChannel.open(archive.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) require(channel.write(buffer) > 0)
            channel.force(true)
        }
        forceDirectory(root)
    }

    private fun requireWineBinding(runId: String, wsRunId: String, nonce: String, allowTerminal: Boolean = false): WineBinding {
        val binding = requireNotNull(readWineBinding(runId)) { "Wine RUN binding is missing" }
        require(binding.wsRunId == wsRunId && binding.nonce == nonce) { "Wine RUN binding mismatch" }
        require(readCurrent()?.runId == runId) { "stale RUN may not write Wine evidence" }
        require(allowTerminal || read(runId)?.status == ForeverRunStatus.ACTIVE) { "terminal RUN may not write Wine evidence" }
        return binding
    }

    private fun readJsonLines(file: File): List<JSONObject> {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return readBounded(file, 64 * 1024).toString(Charsets.UTF_8).lineSequence()
            .filter { it.isNotEmpty() }.map { JSONObject(it) }.toList()
    }

    private fun appendWineLine(file: File, line: String) = appendWineBytes(file, (line + "\n").toByteArray(Charsets.UTF_8))

    private fun appendWineBytes(file: File, bytes: ByteArray) {
        ensureManagedRoot()
        require(bytes.size <= 8 * 1024)
        val old = if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.size(file.toPath()) else 0L
        require(old + bytes.size <= 256 * 1024) { "Wine evidence exceeds bound" }
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        }
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE, StandardOpenOption.APPEND,
            LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) require(channel.write(buffer) > 0)
            channel.force(true)
        }
        forceDirectory(file.parentFile ?: error("Wine evidence parent missing"))
    }

    @Synchronized
    fun recoverActiveRun(isProcessQuiescent: (ForeverRunState) -> Boolean = { true }): ForeverRunState? {
        return withDurableLock { recoverActiveRunLocked(isProcessQuiescent) }
    }

    private fun recoverActiveRunLocked(isProcessQuiescent: (ForeverRunState) -> Boolean): ForeverRunState? {
        val state = readCurrent() ?: return null
        if (state.status != ForeverRunStatus.ACTIVE) return state
        val quiescent = isProcessQuiescent(state)
        // A new Android process has no handle that can safely resume an old
        // launch.  Mark it terminal even when the old process tree is still
        // unknown; leaving ACTIVE here makes a fresh controller look RUNNING.
        val recovered = ForeverRunMachine.finish(
            state,
            ForeverRunStatus.INTERRUPTED,
            if (quiescent) "activity_or_process_interrupted" else "supervision_lost_reboot_required",
        ).copy(
            events = state.events + "recovery_interrupted",
            terminationUnknown = state.terminationUnknown || !quiescent,
            quiescenceConfirmed = state.quiescenceConfirmed || quiescent,
        )
        write(recovered)
        return recovered
    }

    @Synchronized
    fun update(runId: String, transform: (ForeverRunState) -> ForeverRunState): ForeverRunState {
        return withDurableLock { updateLocked(runId, transform) }
    }

    private fun updateLocked(runId: String, transform: (ForeverRunState) -> ForeverRunState): ForeverRunState {
        val state = read(runId) ?: error("unknown Forever run $runId")
        require(readCurrent()?.runId == runId) { "run $runId is no longer current" }
        require(state.status == ForeverRunStatus.ACTIVE) { "run $runId is terminal" }
        val updated = transform(state)
        require(updated.runId == runId) { "run id cannot change" }
        write(updated)
        return updated
    }

    @Synchronized
    fun finish(runId: String, status: ForeverRunStatus, message: String? = null): ForeverRunState {
        return withDurableLock { finishLocked(runId, status, message) }
    }

    private fun finishLocked(runId: String, status: ForeverRunStatus, message: String? = null): ForeverRunState {
        require(readCurrent()?.runId == runId) { "run $runId is no longer current" }
        val state = read(runId) ?: error("unknown Forever run $runId")
        val updated = ForeverRunMachine.finish(state, status, message)
        write(updated)
        return updated
    }

    @Synchronized
    fun updateObservedProcess(
        runId: String,
        argv: List<String>? = null,
        cwd: String? = null,
        pid: Int? = null,
        startTicks: Long? = null,
        spawnAt: Long? = null,
        exitCode: Int? = null,
        durationMs: Long? = null,
        terminationUnknown: Boolean? = null,
        rootPid: Int? = null,
        rootStartTicks: Long? = null,
    ): ForeverRunState = update(runId) { state ->
        state.copy(
            argv = argv ?: state.argv,
            cwd = cwd ?: state.cwd,
            pid = pid ?: state.pid,
            startTicks = startTicks ?: state.startTicks,
            spawnAt = spawnAt ?: state.spawnAt,
            exitCode = exitCode ?: state.exitCode,
            durationMs = durationMs ?: state.durationMs,
            terminationUnknown = terminationUnknown ?: state.terminationUnknown,
            rootPid = rootPid ?: state.rootPid,
            rootStartTicks = rootStartTicks ?: state.rootStartTicks,
        )
    }

    @Synchronized
    fun recordGameIdentity(runId: String, pid: Int, startTicks: Long): ForeverRunState = update(runId) {
        it.copy(gamePid = pid, gameStartTicks = startTicks)
    }

    @Synchronized
    fun updatePatchState(runId: String, patchState: String): ForeverRunState = update(runId) {
        it.copy(patchState = patchState)
    }

    @Synchronized
    fun markLaunchAttempt(runId: String) = update(runId) {
        it.copy(launchAttempted = true, bootId = ForeverProcIdentityPolicy.bootId(),
            terminationUnknown = true, quiescenceConfirmed = false)
    }

    @Synchronized
    fun recordQuiescence(runId: String, confirmed: Boolean) {
        withDurableLock { recordQuiescenceLocked(runId, confirmed) }
    }

    private fun recordQuiescenceLocked(runId: String, confirmed: Boolean) {
        val state = read(runId) ?: return
        if (state.status != ForeverRunStatus.ACTIVE || readCurrent()?.runId != runId) return
        // This is metadata only: never resurrect a terminal run or replace a
        // newer run's current.json when a delayed waiter finishes.
        val updated = state.copy(terminationUnknown = !confirmed, quiescenceConfirmed = confirmed)
        atomicWrite(File(root, "$runId.json"), updated.toJson().toString(2))
        if (readCurrent()?.runId == runId) atomicWrite(current, updated.toJson().toString(2))
    }

    @Synchronized
    fun read(runId: String): ForeverRunState? {
        if (!safeRunId(runId)) return null
        val file = File(root, "$runId.json")
        return readStateFile(file)?.also { require(it.runId == runId) { "run metadata identity mismatch" } }
    }

    @Synchronized
    fun readCurrent(): ForeverRunState? = readStateFile(current)

    private fun readStateFile(file: File): ForeverRunState? {
        for (directory in listOf(File(base, "forever"), root)) {
            if (Files.exists(directory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isDirectory(directory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    "managed metadata parent is not a real directory"
                }
            }
        }
        val bytes = try { readBounded(file, 128 * 1024) }
        catch (_: java.nio.file.NoSuchFileException) { return null }
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.getInt("schema") == 1 && safeRunId(json.getString("runId"))) { "invalid run metadata" }
        for (key in listOf("launchAttempted", "terminationUnknown", "quiescenceConfirmed")) {
            if (json.has(key)) require(json.get(key) is Boolean) { "invalid run $key" }
        }
        return ForeverRunState.fromJson(json)
    }

    suspend fun export(runId: String, destination: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(safeRunId(runId)) { "invalid run id" }
            check(!ForeverAssistedCredentialPrivacy.isSensitive(base)) {
                "diagnostics export disabled during assisted credential session"
            }
            check(!ForeverAssistedCredentialPrivacy.isSensitiveRun(base, runId)) {
                "diagnostics export disabled for a sensitive assisted-credential RUN"
            }
            val snapshot = synchronized(this@ForeverRunStore) {
                val state = read(runId) ?: error("run not found: $runId")
                val diagnostics = File(root, "$runId-diagnostics.jsonl")
                Triple(state, state.toJson().toString(2), if (diagnostics.isFile) diagnostics.readText() else "")
            }
            ForeverAssistedCredentialPrivacy.withDiagnosticsAllowed(base) {
                check(!ForeverAssistedCredentialPrivacy.isSensitiveRun(base, runId)) {
                    "diagnostics export disabled for a sensitive assisted-credential RUN"
                }
                requireNotNull(context).contentResolver.openOutputStream(destination)?.use { output ->
                    ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("run.json"))
                    zip.write(snapshot.second.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    addBoundedEntry(zip, File(root, "${runId}-sigint.bin"), "native/${runId}-sigint.bin", 512)
                    zip.putNextEntry(ZipEntry("native/${runId}-sigint.json"))
                    zip.write(decodeTraceSnapshot(snapshot.first, File(root, "${runId}-sigint.bin")).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    addOutputSnapshots(zip, runId)
                    if (snapshot.third.isNotEmpty()) {
                        zip.putNextEntry(ZipEntry("diagnostics.jsonl"))
                        zip.write(snapshot.third.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry("provenance.json"))
                    context.assets.open("forever/provenance.json").use { it.copyTo(zip) }
                    zip.closeEntry()
                    val logIndex = org.json.JSONArray()
                    val start = snapshot.first.spawnAt
                    val logRoot = File(app.gamenative.utils.CustomGameScanner.defaultRootPath, "WoW Forever/Logs")
                    if (!ForeverAssistedCredentialPrivacy.sharedGameLogsMayBeSensitive(base) &&
                        start != null && logRoot.isDirectory && !Files.isSymbolicLink(logRoot.toPath())) {
                        logRoot.walkTopDown().maxDepth(2).onEnter { !Files.isSymbolicLink(it.toPath()) }
                            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.lastModified() >= start && it.length() <= 1024 * 1024 }
                            .take(16).forEachIndexed { index, file ->
                                // Prefix numeric entries instead of trusting game-supplied ZIP paths.
                                val entry = "game-logs/$index.log"
                                zip.putNextEntry(ZipEntry(entry))
                                file.inputStream().use { input ->
                                    val buffer = ByteArray(8192)
                                    var remaining = 1024 * 1024
                                    while (remaining > 0) {
                                        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                                        if (count < 0) break
                                        zip.write(buffer, 0, count); remaining -= count
                                    }
                                }
                                zip.closeEntry()
                                logIndex.put(JSONObject().put("entry", entry).put("source", file.name)
                                    .put("modifiedAt", file.lastModified()).put("selection", "shared managed Logs path, modified since spawn; not a unique-run guarantee"))
                            }
                    }
                    zip.putNextEntry(ZipEntry("game-logs-index.json"))
                    zip.write(logIndex.toString(2).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    }
                }
                    ?: error("SAF destination could not be opened")
            }
        }
    }

    internal fun appendRaw(runId: String, event: JSONObject) {
        val append = { withDurableLock { appendRawLocked(runId, event) } }
        if (Thread.holdsLock(this)) {
            // beginInternal already owns the store monitor and emits only
            // secret-free device metadata; avoid reversing the privacy/store lock order.
            if (!ForeverAssistedCredentialPrivacy.isSensitive(base) &&
                !ForeverAssistedCredentialPrivacy.isSensitiveRun(base, runId)) append()
        } else {
            ForeverAssistedCredentialPrivacy.runIfDiagnosticsAllowed(base, runId, append)
        }
    }

    private fun appendRawLocked(runId: String, event: JSONObject) {
        if (!safeRunId(runId)) return
        val file = File(root, "$runId-diagnostics.jsonl")
        ensureManagedRoot()
        val line = (event.toString() + "\n").toByteArray(Charsets.UTF_8)
        if (file.length() + line.size > 4L * 1024 * 1024) return // never write a partial JSONL event
        Files.newOutputStream(file.toPath(), java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { it.write(line) }
    }

    private fun addOutputSnapshots(zip: ZipOutputStream, runId: String) {
        val output = File(root, runId)
        for (stream in listOf("stdout", "stderr")) {
            runCatching { BoundedLogRetention.readPersisted(output, runId, stream) }
                .onSuccess { saved ->
                    for ((name, bytes) in listOf("head.bin" to saved.head, "tail.bin" to saved.tail)) {
                        zip.putNextEntry(ZipEntry("$stream/$name")); zip.write(bytes); zip.closeEntry()
                    }
                    val meta = saved.metadata
                    val json = JSONObject().put("schema", 1).put("runId", runId).put("stream", stream)
                        .put("status", "UNKNOWN")
                        .put("snapshotValidity", "validated-bounded-bytes")
                        .put("reason", "periodic snapshot only; final stream state and unpersisted IO failures cannot be proved after host death")
                        .put("persistenceError", saved.persistenceError ?: JSONObject.NULL)
                        .put("totalBytes", meta.totalBytes).put("headBytes", meta.headBytes).put("tailStart", meta.tailStart)
                        .put("tailBytes", meta.tailBytes).put("truncated", meta.truncated)
                        .put("capturedAtMs", meta.capturedAtMs).put("flushedAtMs", meta.flushedAtMs)
                    zip.putNextEntry(ZipEntry("$stream/manifest.json")); zip.write(json.toString().toByteArray()); zip.closeEntry()
                }.onFailure {
                    zip.putNextEntry(ZipEntry("$stream/manifest.json"))
                    zip.write(JSONObject().put("schema", 1).put("runId", runId).put("stream", stream)
                        .put("status", "UNKNOWN").put("reason", "snapshot_missing_or_invalid").toString().toByteArray())
                    zip.closeEntry()
                }
        }
    }

    private fun addBoundedEntry(zip: ZipOutputStream, source: File, entryName: String, maxBytes: Int) {
        if (!source.isFile || Files.isSymbolicLink(source.toPath())) return
        require(source.length() <= maxBytes) { "bounded evidence exceeds export limit" }
        zip.putNextEntry(ZipEntry(entryName))
        val bytes = readBounded(source, maxBytes)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun decodeTraceSnapshot(state: ForeverRunState, source: File): String {
        if (!source.isFile || Files.isSymbolicLink(source.toPath()) || source.length() > ForeverSignalTraceDecoder.MAX_BYTES) {
            return "{\"schema\":1,\"status\":\"NOT_CAPTURED\",\"reason\":\"trace_missing_or_oversize\",\"events\":[]}"
        }
        return runCatching {
            val bytes = readBounded(source, ForeverSignalTraceDecoder.MAX_BYTES)
            if (bytes.size < ForeverSignalTraceDecoder.HEADER_BYTES) error("trace_truncated")
            // Expected identity comes from a separate live-host observation,
            // never from the untrusted binary header being validated.
            val binding = JSONObject(String(readBounded(File(root, "${state.runId}-sigint-binding.json"), 2048)))
            require(binding.getInt("schema") == 1 && binding.getString("runId") == state.runId)
            val hostPid = binding.getInt("pid")
            val hostTicks = binding.getLong("startTicks")
            val boot = binding.getString("bootId")
            require(state.hostPid == hostPid && state.bootId == boot)
            require(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(20) == binding.getInt("uid"))
            ForeverSignalTraceDecoder.decode(
                bytes,
                SignalTraceIdentity(state.runId, boot, hostPid, hostTicks),
                nowMonotonicNanos = if (boot == ForeverProcIdentityPolicy.bootId()) System.nanoTime() else Long.MAX_VALUE,
            ).boundedJson()
        }.getOrElse { error ->
            JSONObject().put("schema", 1).put("status", "INVALID")
                .put("reason", error.message ?: "trace_decode_failed").put("events", org.json.JSONArray()).toString()
        }
    }

    private fun write(state: ForeverRunState) {
        ensureManagedRoot()
        atomicWrite(File(root, "${state.runId}.json"), state.toJson().toString(2))
        atomicWrite(current, state.toJson().toString(2))
    }

    private fun atomicWrite(destination: File, text: String) {
        ensureManagedRoot()
        if (failNextAtomicWriteForTests) {
            failNextAtomicWriteForTests = false
            error("injected atomic write failure")
        }
        val temporary = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.tmp")
        java.nio.channels.FileChannel.open(temporary.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { output ->
            val bytes = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) output.write(bytes)
            output.force(true)
        }
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            if (!temporary.renameTo(destination)) error("could not atomically replace ${destination.name}")
        }
        // A durable file without durable parent metadata can disappear after a
        // power loss.  Keep this fsync adjacent to the rename, and use
        // NOFOLLOW_LINKS so metadata is never forced through an attacker path.
        forceDirectory(destination.parentFile ?: error("destination parent missing"))
    }

    private fun newRunId(): String = "RUN-${System.currentTimeMillis()}-${System.nanoTime()}"

    private fun safeRunId(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"))

    private fun readBounded(file: File, limit: Int): ByteArray =
        Files.newInputStream(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
            readBounded(input, limit)
        }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= limit) { "evidence exceeds bound" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
    }

    private fun readExact(file: File, expectedBytes: Int): ByteArray {
        require(expectedBytes >= 0) { "invalid exact file size" }
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(file.toPath())) { "exact evidence file is missing or unsafe" }
        val bytes = readBounded(file, expectedBytes)
        require(bytes.size == expectedBytes) { "exact evidence file size changed" }
        return bytes
    }

    private fun writeCreateNew(destination: File, bytes: ByteArray) {
        ensureManagedRoot()
        require(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "append-only evidence destination already exists"
        }
        destination.parentFile?.let {
            require(!Files.isSymbolicLink(it.toPath()) && Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS))
        }
        FileChannel.open(destination.toPath(),
            setOf<java.nio.file.OpenOption>(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")),
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) require(channel.write(buffer) > 0)
            channel.force(true)
        }
        forceDirectory(destination.parentFile ?: error("append-only evidence parent missing"))
    }

    private fun forceDirectory(directory: File) {
        if (failNextDirectorySyncForTests) {
            failNextDirectorySyncForTests = false
            error("injected directory sync failure")
        }
        FileChannel.open(directory.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
    }

    private fun failWineResultAt(point: WineResultFaultPoint) {
        if (failNextWineResultAt == point) {
            failNextWineResultAt = null
            error("injected Wine result failure at $point")
        }
    }
}
