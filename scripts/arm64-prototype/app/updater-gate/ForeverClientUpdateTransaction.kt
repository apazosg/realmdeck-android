package app.gamenative

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A content fingerprint required before replacing an already-owned client file. */
data class FileFingerprint(val size: Long, val sha256: String)

/** One caller-admitted staged file. A replacement must name its exact prior fingerprint. */
data class InputFile(
    val relativePath: String,
    val stagedFile: File,
    val expectedSize: Long,
    val sha256: String,
    val expectedOld: FileFingerprint? = null,
)

/**
 * One write-once staged file admitted without a caller-supplied hash. The
 * single admission hash is computed by [ForeverClientUpdateTransaction.planFresh]
 * itself, so the returned proof can never be faked by the caller. Fresh-only:
 * [expectedOld] must stay null; any replacement uses the strict [InputFile] path.
 */
internal data class FreshStagedDescriptor(
    val relativePath: String,
    val stagedFile: File,
    val expectedOld: FileFingerprint? = null,
)

/** Fresh updater-owned staging namespace created by the transaction instance. */
class UpdateStagingArea internal constructor(
    internal val ownerToken: String,
    val id: String,
    val directory: File,
    internal val markerHash: String,
)

/** Immutable result of validating the explicit file list and ownership allowlist. */
class TransactionPlan internal constructor(
    internal val ownerToken: String,
    internal val identityHash: String,
    internal val stageId: String,
    internal val stageMarkerHash: String,
    internal val operations: List<ForeverClientUpdateTransaction.Operation>,
    internal val ownedLoosePaths: Set<String>,
) {
    val fileCount: Int get() = operations.size
}

/**
 * Journaled activation of a bounded, caller-admitted file list.
 *
 * This is deliberately not a downloader, CASC parser, guest-process lease, or
 * whole-client installer. It assumes the caller has already staged and admitted
 * every payload. Every activation/repair is gated by identityStillMatches and
 * mutationAllowed; the latter is a caller gate, not durable proof that a guest
 * process is absent.
 */
class ForeverClientUpdateTransaction(
    root: File,
    privateJournalDir: File,
    frozenIdentity: String,
    private val identityStillMatches: () -> Boolean,
    private val mutationAllowed: () -> Boolean,
) {
    private val rootPath = root.toPath().toAbsolutePath().normalize()
    private val journalDir = privateJournalDir.toPath().toAbsolutePath().normalize()
    private val journalPath = journalDir.resolve(JOURNAL_NAME)
    private val journalTempPath = journalDir.resolve(JOURNAL_TEMP_NAME)
    private val lockPath = journalDir.resolve(LOCK_NAME)
    private val identityHash = sha256(frozenIdentity.toByteArray(Charsets.UTF_8))
    private val ownerToken = UUID.randomUUID().toString()

    /** Test-only crash seam. Called only after a durable journal/move boundary. */
    internal var durableBoundary: (String) -> Unit = {}

    /**
     * Test/production progress seam for the two-pass fresh finalization. Called
     * under Phase.APPLYING with the typed finalization detail; never called on
     * the strict recovery path. Defaults to a no-op so the API stays unchanged.
     */
    internal var finalizationProgress: (ForeverClientUpdateProgress) -> Unit = {}

    /** Test-only full-content hash byte counter. Every [fingerprint] read adds its size. */
    internal val hashBytesRead = AtomicLong(0L)

    private var issuedFresh: IssuedFresh? = null
    private data class IssuedFresh(val plan: TransactionPlan, val canonical: String)

    init {
        require(frozenIdentity.isNotBlank() && frozenIdentity.length <= MAX_IDENTITY_LENGTH) {
            "frozen destination identity is empty or too long"
        }
        require(!journalDir.startsWith(rootPath) && !rootPath.startsWith(journalDir)) {
            "private journal directory must be disjoint from the client root"
        }
    }

    /** Hash of the exact root/ID owner marker, usable for a private pre-creation intent receipt. */
    fun stagingMarkerHash(stageId: String): String {
        require(UUID_PATTERN.matches(stageId)) { "staging ID is invalid" }
        return sha256(stagingMarkerBytes(stageId))
    }

    /** Create a fresh stage at an ID already recorded in the caller's private intent receipt. */
    fun createStaging(stageId: String, expectedMarkerHash: String): UpdateStagingArea = withExclusiveLock {
        validateDirectories()
        if (Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("recover the unfinished client update before creating staging")
        }
        if (Files.exists(journalTempPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("an update journal temporary exists; manual recovery is required before staging")
        }
        val base = rootPath.resolve(STAGING_DIR_NAME)
        ensureDirectory(base)
        val id = stageId
        require(UUID_PATTERN.matches(id)) { "staging ID is invalid" }
        val markerBytes = stagingMarkerBytes(id)
        val markerHash = normalizeHash(expectedMarkerHash, "staging marker hash")
        require(sha256(markerBytes) == markerHash) {
            "staging intent marker hash does not match this destination identity"
        }
        val directory = base.resolve(id)
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("staging intent path is occupied by an unsafe object")
            }
            Files.newDirectoryStream(directory).use { children ->
                if (children.iterator().hasNext()) throw IllegalStateException("unmarked staging intent directory contains unknown data")
            }
            checkMutationAllowed()
            Files.delete(directory)
            forceDirectory(base)
        }
        checkMutationAllowed()
        try {
            Files.createDirectory(directory)
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            throw IllegalStateException("fresh staging ID unexpectedly already exists", e)
        }
        forceDirectory(base)
        forceDirectory(directory)

        val marker = directory.resolve(STAGING_MARKER_NAME)
        checkMutationAllowed()
        FileChannel.open(
            marker,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(markerBytes)
            while (buffer.hasRemaining()) {
                checkIdentity()
                checkMutationAllowed()
                channel.write(buffer)
            }
            checkMutationAllowed()
            channel.force(true)
        }
        forceDirectory(directory)
        UpdateStagingArea(ownerToken, id, directory.toFile(), markerHash)
    }

    /** Create a fresh staging namespace without a caller-managed restart receipt. */
    fun createStaging(): UpdateStagingArea {
        val id = UUID.randomUUID().toString()
        return createStaging(id, stagingMarkerHash(id))
    }

    /** Reopen only the exact existing root-owned stage named in a private receipt. */
    fun resumeStaging(stageId: String, expectedMarkerHash: String): UpdateStagingArea = withExclusiveLock {
        checkIdentity()
        validateDirectories()
        require(UUID_PATTERN.matches(stageId)) { "staging ID is invalid" }
        if (Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS) || Files.exists(journalTempPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("recover the pending transaction journal before resuming staging")
        }
        val markerHash = normalizeHash(expectedMarkerHash, "staging marker hash")
        verifyStagingMarker(stageId, markerHash)
        validateResumedStagingNamespace(stageId)
        UpdateStagingArea(ownerToken, stageId, stagingDirectory(stageId).toFile(), markerHash)
    }

    /** Read-only, no-directory-creation probe of the journal and root backup namespace. */
    fun hasPendingTransaction(): Boolean {
        rejectSymlinkComponents(journalDir)
        rejectSymlinkComponents(rootPath)
        if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("client root is unavailable to the transaction probe")
        if (Files.exists(journalDir, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(journalDir, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("private transaction journal directory is not a regular directory")
        }
        for ((path, label) in listOf(journalPath to "transaction journal", journalTempPath to "transaction journal temporary")) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue
            rejectSymlinkComponents(path)
            requireRegularFile(path, label)
            return true
        }
        val support = rootPath.resolve(TRANSACTION_DIR_NAME)
        if (!Files.exists(support, LinkOption.NOFOLLOW_LINKS)) return false
        rejectSymlinkComponents(support)
        if (!Files.isDirectory(support, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("transaction backup namespace is not a regular directory")
        }
        Files.newDirectoryStream(support).use { children -> return children.iterator().hasNext() }
    }

    /** Validate and freeze the exact operation list. This method does not mutate the client. */
    fun plan(
        files: List<InputFile>,
        ownedLoosePaths: Set<String>,
        staging: UpdateStagingArea,
    ): TransactionPlan = withExclusiveLock {
        checkIdentity()
        validateDirectories()
        validateStagingArea(staging)
        // A new generic admission voids any live fast proof: one plan per instance.
        issuedFresh = null
        require(files.isNotEmpty() && files.size <= MAX_OPERATIONS) {
            "transaction must contain 1..$MAX_OPERATIONS admitted files"
        }
        require(ownedLoosePaths.size <= MAX_OPERATIONS) { "loose ownership allowlist exceeds the bounded transaction limit" }

        val loose = ownedLoosePaths.map { validateRelativePath(it) }.toSortedSet()
        require(loose.map { it.lowercase() }.toSet().size == loose.size) {
            "loose ownership allowlist contains case-insensitive path aliases"
        }
        loose.forEach { path ->
            require(isSafeLoosePath(path)) { "loose ownership allowlist contains a protected or reserved path: $path" }
        }

        val seenTargets = HashSet<String>()
        val seenStages = HashSet<String>()
        var buildInfoCount = 0
        val prepared = files.map { input ->
            val targetPath = validateTargetPath(input.relativePath, loose)
            require(seenTargets.add(input.relativePath.lowercase())) { "duplicate or case-alias transaction target: ${input.relativePath}" }
            if (input.relativePath == BUILD_INFO_PATH) buildInfoCount++
            require(input.expectedSize >= 0) { "negative staged size for ${input.relativePath}" }
            val expectedHash = normalizeHash(input.sha256, "staged SHA-256")
            val old = input.expectedOld?.let {
                require(it.size >= 0) { "negative prior size for ${input.relativePath}" }
                FileFingerprint(it.size, normalizeHash(it.sha256, "prior SHA-256"))
            }

            val stagePath = validateStagedFile(input.stagedFile, staging)
            val stageRelative = rootPath.relativize(stagePath).toString().replace(File.separatorChar, '/')
            require(seenStages.add(stageRelative.lowercase())) { "staged file is used more than once: $stageRelative" }
            require(stagePath != targetPath) { "staged file and target are identical: ${input.relativePath}" }

            val targetState = fingerprintIfPresent(targetPath)
            if (targetState == null) {
                require(old == null) { "prior fingerprint was supplied for absent target ${input.relativePath}" }
            } else {
                require(old != null) { "existing target requires an explicit prior fingerprint: ${input.relativePath}" }
                require(targetState == old) { "existing target fingerprint does not match the admitted prior file: ${input.relativePath}" }
            }
            val type = classifyTarget(input.relativePath, loose)
            require(type != TargetType.SEGMENT || old == null) { "CASC data segments are write-once and cannot be replaced" }
            require(type != TargetType.BUILD_INFO || input.relativePath == BUILD_INFO_PATH)
            val stageFingerprint = fingerprint(stagePath)
            require(stageFingerprint.size == input.expectedSize && stageFingerprint.sha256 == expectedHash) {
                "staged size or SHA-256 mismatch for ${input.relativePath}"
            }
            validateMoveFileStores(stagePath, targetPath, null)
            Operation(
                target = input.relativePath,
                staged = stageRelative,
                backup = null,
                size = input.expectedSize,
                hash = expectedHash,
                old = old,
                phase = 0,
            )
        }
        require(buildInfoCount == 1) { "transaction must publish exactly one .build.info marker" }

        val ordered = prepared.sortedWith(
            compareBy<Operation> { it.target == BUILD_INFO_PATH }
                .thenBy { it.target },
        )
        require(seenStages.intersect(seenTargets).isEmpty()) {
            "a transaction target cannot also be used as staging input"
        }
        checkIdentity()
        TransactionPlan(ownerToken, identityHash, staging.id, staging.markerHash, ordered, loose)
    }

    /** Publish a validated plan. Once its journal is durable, interruption leaves recovery work. */
    fun commit(plan: TransactionPlan) = withExclusiveLock {
        val issued = issuedFresh
        issuedFresh = null
        if (issued != null) {
            // One live plan per instance: a different plan never inherits the
            // fast proof, and a mutated issued plan never publishes. Generic
            // plans keep working whenever no fast proof is live.
            check(issued.plan === plan) {
                "a live issued fresh plan must be committed before any other plan"
            }
            check(fastCanonical(plan) == issued.canonical) {
                "issued fresh plan changed after admission; refusing to publish a mutated plan"
            }
            commitFast(plan)
            return@withExclusiveLock
        }
        commitStrict(plan)
        Unit
    }

    /**
     * Admit a fresh write-once file list with a single staged hash pass.
     *
     * Eligibility is conservative: every target must be absent, every
     * descriptor must carry no prior fingerprint, the journal/temporary must
     * be absent, and the transaction backup namespace must hold no state. Any
     * pre-existing payload selects the strict [plan] path instead; a mismatch
     * discovered after admission fails closed with no fallback.
     */
    internal fun planFresh(
        files: List<FreshStagedDescriptor>,
        ownedLoosePaths: Set<String>,
        staging: UpdateStagingArea,
        cancelled: () -> Boolean = { false },
    ): TransactionPlan = withExclusiveLock {
        checkIdentity()
        validateDirectories()
        validateStagingArea(staging)
        if (Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("recover the unfinished client update before admitting a fresh plan")
        }
        if (Files.exists(journalTempPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("an update journal temporary exists; manual recovery is required before admitting a fresh plan")
        }
        requireNoOrphanTransactionState()
        require(files.isNotEmpty() && files.size <= MAX_OPERATIONS) {
            "fresh transaction must contain 1..$MAX_OPERATIONS admitted files"
        }
        require(ownedLoosePaths.size <= MAX_OPERATIONS) { "loose ownership allowlist exceeds the bounded transaction limit" }

        val loose = ownedLoosePaths.map { validateRelativePath(it) }.toSortedSet()
        require(loose.map { it.lowercase() }.toSet().size == loose.size) {
            "loose ownership allowlist contains case-insensitive path aliases"
        }
        loose.forEach { path ->
            require(isSafeLoosePath(path)) { "loose ownership allowlist contains a protected or reserved path: $path" }
        }

        val seenTargets = HashSet<String>()
        val seenStages = HashSet<String>()
        var buildInfoCount = 0
        val validated = files.map { input ->
            if (cancelled()) throw CancellationException("client update was paused or cancelled")
            val targetPath = validateTargetPath(input.relativePath, loose)
            require(seenTargets.add(input.relativePath.lowercase())) { "duplicate or case-alias transaction target: ${input.relativePath}" }
            if (input.relativePath == BUILD_INFO_PATH) buildInfoCount++
            require(input.expectedOld == null) {
                "fresh finalization cannot replace an existing target: ${input.relativePath}"
            }
            val stagePath = validateStagedFile(input.stagedFile, staging)
            val stageRelative = rootPath.relativize(stagePath).toString().replace(File.separatorChar, '/')
            require(seenStages.add(stageRelative.lowercase())) { "staged file is used more than once: $stageRelative" }
            require(stagePath != targetPath) { "staged file and target are identical: ${input.relativePath}" }
            if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("fresh finalization target already exists; use the strict replacement path: ${input.relativePath}")
            }
            val type = classifyTarget(input.relativePath, loose)
            require(type != TargetType.BUILD_INFO || input.relativePath == BUILD_INFO_PATH)
            validateMoveFileStores(stagePath, targetPath, null)
            Triple(input.relativePath, stagePath, stageRelative)
        }
        require(buildInfoCount == 1) { "transaction must publish exactly one .build.info marker" }
        val ordered = validated.sortedWith(
            compareBy<Triple<String, java.nio.file.Path, String>> { it.first == BUILD_INFO_PATH }
                .thenBy { it.first },
        )
        require(seenStages.intersect(seenTargets).isEmpty()) {
            "a transaction target cannot also be used as staging input"
        }
        checkIdentity()

        // The single admission hash pass for this execution.
        val totalBytes = ordered.sumOf { Files.size(it.second) }
        var admittedBytes = 0L
        val prepared = ordered.mapIndexed { index, triple ->
            if (cancelled()) throw CancellationException("client update was paused or cancelled")
            checkMutationAllowed()
            val stageFingerprint = fingerprint(triple.second)
            admittedBytes = Math.addExact(admittedBytes, stageFingerprint.size)
            emitFinalization(index + 1L, ordered.size.toLong(), admittedBytes, totalBytes)
            Operation(
                target = triple.first,
                staged = triple.third,
                backup = null,
                size = stageFingerprint.size,
                hash = stageFingerprint.sha256,
                old = null,
                phase = 0,
            )
        }
        checkIdentity()
        val plan = TransactionPlan(ownerToken, identityHash, staging.id, staging.markerHash, prepared, loose)
        issuedFresh = IssuedFresh(plan, fastCanonical(plan))
        plan
    }

    /** Strict publication path: every pre/post boundary re-hashes from disk. Recovery uses this. */
    private fun commitStrict(plan: TransactionPlan) {
        validatePlan(plan)
        checkIdentity()
        checkMutationAllowed()
        validateDirectories()
        if (Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("an unfinished client update journal already exists; recover before starting another transaction")
        }
        if (Files.exists(journalTempPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("stale update journal temporary exists; refusing to overwrite it")
        }
        ensureNoOrphanTransactionDirectory()

        val id = plan.stageId
        val operations = plan.operations.mapIndexed { index, op ->
            op.copy(backup = if (op.old == null) null else backupRelativePath(id, index))
        }
        val journal = Journal(
            id,
            identityHash,
            plan.stageId,
            plan.stageMarkerHash,
            JournalState.ACTIVE,
            plan.ownedLoosePaths.toList().sorted(),
            operations,
        )

        // Flush each staged file and its containing directory before the journal
        // permits any destination rename.
        operations.forEach { op ->
            checkMutationAllowed()
            val staged = resolveRootRelative(op.staged)
            requireRegularFile(staged, "staged file")
            requireFingerprint(staged, FileFingerprint(op.size, op.hash), "staged file")
            forceFile(staged)
            forceDirectory(staged.parent)
            validateMoveFileStores(staged, resolveTarget(op, journal), backupPath(op, journal))
        }
        val marker = stagingMarkerPath(plan.stageId)
        requireFingerprint(marker, FileFingerprint(stagingMarkerBytes(plan.stageId).size.toLong(), plan.stageMarkerHash), "staging ownership marker")
        forceFile(marker)
        forceDirectory(marker.parent)
        checkMutationAllowed()
        writeJournal(journal, "journal:ACTIVE")
        rollForward(journal)
        verifyPublished(journal)
        val committed = journal.copy(state = JournalState.COMMITTED)
        writeJournal(committed, "journal:COMMITTED")
        cleanupCommitted(committed)
    }

    /**
     * Fast publication for the single live issued fresh plan. No staged or
     * target content hash happens before the renames; every published target
     * is hashed exactly once afterwards, before the build marker may pass.
     */
    private fun commitFast(plan: TransactionPlan) {
        validateFastPlan(plan)
        checkIdentity()
        checkMutationAllowed()
        validateDirectories()
        if (Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("an unfinished client update journal already exists; recover before starting another transaction")
        }
        if (Files.exists(journalTempPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("stale update journal temporary exists; refusing to overwrite it")
        }
        requireNoOrphanTransactionState()

        val journal = Journal(
            plan.stageId,
            identityHash,
            plan.stageId,
            plan.stageMarkerHash,
            JournalState.ACTIVE,
            plan.ownedLoosePaths.toList().sorted(),
            plan.operations.map { it.copy() },
        )

        // Same durable force order as the strict path, without re-hashing the
        // admitted staged bytes: the admission proof stays valid only because
        // every published target is hashed again below before COMMITTED.
        journal.operations.forEach { op ->
            checkMutationAllowed()
            val staged = resolveRootRelative(op.staged)
            requireRegularFile(staged, "staged file")
            validateMoveFileStores(staged, resolveTarget(op, journal), null)
            forceFile(staged)
            forceDirectory(staged.parent)
        }
        val marker = stagingMarkerPath(plan.stageId)
        requireFingerprint(marker, FileFingerprint(stagingMarkerBytes(plan.stageId).size.toLong(), plan.stageMarkerHash), "staging ownership marker")
        forceFile(marker)
        forceDirectory(marker.parent)
        checkMutationAllowed()
        writeJournal(journal, "journal:ACTIVE")
        val proofs = fastPublishAndVerify(journal)
        val committed = journal.copy(state = JournalState.COMMITTED)
        writeJournal(committed, "journal:COMMITTED")
        fastCleanupCommitted(committed, proofs)
    }

    /**
     * Recover the one durable transaction, rolling it forward when staged bytes
     * remain intact, or safely rolling it back if publication is no longer possible.
     */
    fun recover(): RecoveryResult = withExclusiveLock {
        checkIdentity()
        validateDirectories()
        if (!Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(journalTempPath, LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("an uncommitted journal temporary exists without its journal; manual review is required")
            }
            ensureNoOrphanTransactionDirectory()
            return@withExclusiveLock RecoveryResult.CLEAN
        }
        checkMutationAllowed()
        rejectSymlinkComponents(journalPath)
        requireRegularFile(journalPath, "transaction journal")
        val journal = readJournal()
        if (journal.identityHash != identityHash) {
            throw IllegalStateException("update journal belongs to a different destination identity")
        }
        verifyStagingMarker(journal.stageId, journal.stageMarkerHash)
        validateTransactionDirectory(journal)
        discardJournalTemporaryIfPresent()

        when (journal.state) {
            JournalState.COMMITTED -> {
                verifyPublished(journal)
                cleanupCommitted(journal)
                RecoveryResult.COMPLETED
            }
            JournalState.ROLLING_BACK -> {
                ensureRollbackPossible(journal)
                rollBack(journal)
                verifyRolledBack(journal)
                cleanupRolledBack(journal)
                RecoveryResult.ROLLED_BACK
            }
            JournalState.ACTIVE -> {
                if (canRollForward(journal)) {
                    rollForward(journal)
                    verifyPublished(journal)
                    val committed = journal.copy(state = JournalState.COMMITTED)
                    writeJournal(committed, "journal:COMMITTED")
                    cleanupCommitted(committed)
                    RecoveryResult.COMPLETED
                } else {
                    ensureRollbackPossible(journal)
                    val rollingBack = journal.copy(state = JournalState.ROLLING_BACK)
                    writeJournal(rollingBack, "journal:ROLLING_BACK")
                    rollBack(rollingBack)
                    verifyRolledBack(rollingBack)
                    cleanupRolledBack(rollingBack)
                    RecoveryResult.ROLLED_BACK
                }
            }
        }
    }

    enum class RecoveryResult { CLEAN, COMPLETED, ROLLED_BACK }

    internal data class Operation(
        val target: String,
        val staged: String,
        val backup: String?,
        val size: Long,
        val hash: String,
        val old: FileFingerprint?,
        val phase: Int,
    )

    private enum class JournalState { ACTIVE, ROLLING_BACK, COMMITTED }
    private enum class TargetType { SEGMENT, INDEX, CONFIG, LOOSE, BUILD_INFO }
    private data class Journal(
        val id: String,
        val identityHash: String,
        val stageId: String,
        val stageMarkerHash: String,
        val state: JournalState,
        val ownedLoosePaths: List<String>,
        val operations: List<Operation>,
    )

    private fun validatePlan(plan: TransactionPlan) {
        require(plan.ownerToken == ownerToken && plan.identityHash == identityHash) {
            "transaction plan belongs to another destination or transaction object"
        }
        require(plan.stageId.matches(UUID_PATTERN) && plan.stageMarkerHash.matches(HASH_PATTERN)) {
            "transaction staging identity is invalid"
        }
        checkIdentity()
        validateDirectories()
        verifyStagingMarker(plan.stageId, plan.stageMarkerHash)
        require(plan.operations.isNotEmpty() && plan.operations.size <= MAX_OPERATIONS)
        require(plan.operations.count { it.target == BUILD_INFO_PATH } == 1)
        require(plan.operations.last().target == BUILD_INFO_PATH) { "build marker must be the final transaction target" }
        val seen = HashSet<String>()
        plan.operations.forEach { op ->
            validateTargetPath(op.target, plan.ownedLoosePaths)
            require(seen.add(op.target.lowercase())) { "duplicate or case-alias transaction target" }
            validateStagedRelativePath(op.staged, plan.stageId)
            require(op.size >= 0 && op.hash.matches(HASH_PATTERN))
            require(op.phase == 0 && op.backup == null)
            classifyTarget(op.target, plan.ownedLoosePaths)
            val stage = resolveRootRelative(op.staged)
            requireRegularFile(stage, "staged file")
            requireFingerprint(stage, FileFingerprint(op.size, op.hash), "staged file")
            val old = fingerprintIfPresent(resolveRootRelative(op.target))
            require(old == op.old) { "destination changed after planning: ${op.target}" }
        }
    }

    private fun fastCanonical(plan: TransactionPlan): String {
        val canonical = StringBuilder("forever-client-finalization-v1\n")
        canonicalField(canonical, plan.ownerToken)
        canonicalField(canonical, plan.identityHash)
        canonicalField(canonical, plan.stageId)
        canonicalField(canonical, plan.stageMarkerHash)
        canonicalField(canonical, plan.operations.size.toString())
        plan.operations.forEach { op ->
            canonicalField(canonical, op.target)
            canonicalField(canonical, op.staged)
            canonicalField(canonical, op.backup ?: "")
            canonicalField(canonical, op.size.toString())
            canonicalField(canonical, op.hash)
            canonicalField(canonical, op.old?.size?.toString() ?: "-")
            canonicalField(canonical, op.old?.sha256 ?: "")
            canonicalField(canonical, op.phase.toString())
        }
        canonicalField(canonical, plan.ownedLoosePaths.size.toString())
        plan.ownedLoosePaths.sorted().forEach { canonicalField(canonical, it) }
        return sha256(canonical.toString().toByteArray(Charsets.UTF_8))
    }

    /** Metadata-only plan check for the live issued fast plan: no content hash. */
    private fun validateFastPlan(plan: TransactionPlan) {
        require(plan.ownerToken == ownerToken && plan.identityHash == identityHash) {
            "transaction plan belongs to another destination or transaction object"
        }
        require(plan.stageId.matches(UUID_PATTERN) && plan.stageMarkerHash.matches(HASH_PATTERN)) {
            "transaction staging identity is invalid"
        }
        checkIdentity()
        validateDirectories()
        verifyStagingMarker(plan.stageId, plan.stageMarkerHash)
        require(plan.operations.isNotEmpty() && plan.operations.size <= MAX_OPERATIONS)
        require(plan.operations.count { it.target == BUILD_INFO_PATH } == 1)
        require(plan.operations.last().target == BUILD_INFO_PATH) { "build marker must be the final transaction target" }
        val seen = HashSet<String>()
        plan.operations.forEach { op ->
            validateTargetPath(op.target, plan.ownedLoosePaths)
            require(seen.add(op.target.lowercase())) { "duplicate or case-alias transaction target" }
            validateStagedRelativePath(op.staged, plan.stageId)
            require(op.size >= 0 && op.hash.matches(HASH_PATTERN))
            require(op.phase == 0 && op.backup == null && op.old == null) {
                "fast finalization cannot publish a replacement or resumed operation: ${op.target}"
            }
            classifyTarget(op.target, plan.ownedLoosePaths)
            val stage = resolveRootRelative(op.staged)
            requireRegularFile(stage, "staged file")
            val target = resolveRootRelative(op.target)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("destination changed after planning: ${op.target}")
            }
            validateMoveFileStores(stage, target, null)
        }
    }

    /**
     * Fast publication with the marker barrier: every payload is moved and
     * then hashed from its current target before the next operation moves.
     * The `.build.info` marker moves only after every payload proof passed,
     * so a failed payload can never leave a marker behind: on mismatch the
     * journal stays ACTIVE with the marker still staged. Returns the live
     * per-target proofs; no global re-sweep follows.
     */
    private fun fastPublishAndVerify(initial: Journal): Map<String, FileFingerprint> {
        var journal = initial
        ensureTransactionDirectories(journal)
        val operations = journal.operations
        val totalBytes = operations.sumOf { it.size }
        val proofs = LinkedHashMap<String, FileFingerprint>(operations.size)
        var verifiedBytes = 0L
        var lastEmitNanos = System.nanoTime()
        operations.indices.forEach { index ->
            val op = journal.operations[index]
            if (op.target == BUILD_INFO_PATH) return@forEach
            journal = fastPublishPayload(journal, index)
            val actual = fastVerifyPayload(journal, op)
            proofs[op.target] = actual
            verifiedBytes = Math.addExact(verifiedBytes, actual.size)
            durableBoundary("verified:${op.target}")
            lastEmitNanos = maybeEmitFinalization(
                index + 1L,
                operations.size.toLong(),
                verifiedBytes,
                totalBytes,
                lastEmitNanos,
            )
        }
        val markerIndex = operations.indexOfLast { it.target == BUILD_INFO_PATH }
        require(markerIndex >= 0) { "build marker must be the final transaction target" }
        journal = fastPublishPayload(journal, markerIndex)
        val markerOp = journal.operations[markerIndex]
        val markerActual = fastVerifyPayload(journal, markerOp)
        proofs[markerOp.target] = markerActual
        verifiedBytes = Math.addExact(verifiedBytes, markerActual.size)
        durableBoundary("verified:${markerOp.target}")
        emitFinalization(operations.size.toLong(), operations.size.toLong(), verifiedBytes, totalBytes)
        return proofs
    }

    /** Move one fresh payload (or the marker, once every payload proof passed). Phase 2 still means RENAMED. */
    private fun fastPublishPayload(journal: Journal, index: Int): Journal {
        checkIdentity()
        checkMutationAllowed()
        verifyStagingMarker(journal.stageId, journal.stageMarkerHash)
        val op = journal.operations[index]
        require(op.old == null && op.backup == null) {
            "fast finalization cannot publish a replacement: ${op.target}"
        }
        val target = resolveTarget(op, journal)
        val staged = resolveRootRelative(op.staged)
        ensureParentDirectories(target.parent)
        validateMoveFileStores(staged, target, null)

        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("write-once target was created by an unknown source: ${op.target}")
        }

        checkMutationAllowed()
        requireRegularFile(staged, "staged file")
        forceFile(staged)
        forceDirectory(staged.parent)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("destination appeared before staged publication: ${op.target}")
        }
        atomicMove(staged, target, "publish:${op.target}")
        return updatePhase(journal, index, 2)
    }

    /** Hash one just-published target from disk; any mismatch fails closed with the marker still staged. */
    private fun fastVerifyPayload(journal: Journal, op: Operation): FileFingerprint {
        checkIdentity()
        val target = resolveTarget(op, journal)
        requireRegularFile(target, "published target")
        val actual = fingerprint(target)
        if (actual != FileFingerprint(op.size, op.hash)) {
            throw IllegalStateException("published target fingerprint mismatch: ${op.target}")
        }
        return actual
    }

    private fun maybeEmitFinalization(
        filesDone: Long,
        filesTotal: Long,
        bytesDone: Long,
        bytesTotal: Long,
        lastEmitNanos: Long,
    ): Long {
        val now = System.nanoTime()
        if (filesDone >= filesTotal || bytesDone >= bytesTotal ||
            bytesDone >= FINALIZATION_EMIT_BYTES || now - lastEmitNanos >= FINALIZATION_EMIT_NANOS
        ) {
            emitFinalization(filesDone, filesTotal, bytesDone, bytesTotal)
            return now
        }
        return lastEmitNanos
    }

    /**
     * Fresh-only cleanup: revalidates bindings and the small build marker, then
     * removes only this execution's owned staging and journal state. It never
     * re-hashes CASC payloads; the live [proofs] from this execution cover them.
     */
    private fun fastCleanupCommitted(journal: Journal, proofs: Map<String, FileFingerprint>) {
        checkIdentity()
        checkMutationAllowed()
        require(proofs.keys == journal.operations.map { it.target }.toSet()) {
            "fast cleanup requires this execution's proof for every published target"
        }
        journal.operations.forEach { op ->
            val target = resolveTarget(op, journal)
            requireRegularFile(target, "published target")
            if (Files.size(target) != proofs.getValue(op.target).size) {
                throw IllegalStateException("published target changed after verification: ${op.target}")
            }
        }
        verifyStagingMarker(journal.stageId, journal.stageMarkerHash)
        val markerProof = proofs[BUILD_INFO_PATH]
            ?: throw IllegalStateException("fast cleanup requires this execution's build marker proof")
        requireFingerprint(resolveRootRelative(BUILD_INFO_PATH), markerProof, "published build marker")
        cleanupTransactionDirectory(journal)
        deleteJournal()
        cleanupCommittedStaging(journal)
        val totalBytes = journal.operations.sumOf { it.size }
        emitFinalization(
            journal.operations.size.toLong(),
            journal.operations.size.toLong(),
            totalBytes,
            totalBytes,
        )
    }

    /** Read-only orphan check for the fresh path: never deletes, only rejects. */
    private fun requireNoOrphanTransactionState() {
        val base = rootPath.resolve(TRANSACTION_DIR_NAME)
        if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) return
        rejectSymlinkComponents(base)
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("reserved transaction support path is not a directory")
        }
        Files.newDirectoryStream(base).use { children ->
            if (children.iterator().hasNext()) {
                throw IllegalStateException("transaction backup state exists without its private journal; manual recovery is required")
            }
        }
    }

    private fun emitFinalization(filesDone: Long, filesTotal: Long, bytesDone: Long, bytesTotal: Long) {
        finalizationProgress(
            ForeverClientUpdateProgress(
                ForeverClientUpdateProgress.Phase.APPLYING,
                0,
                0,
                0,
                0,
                ForeverClientUpdateProgress.FinalizationDetail(filesDone, filesTotal, bytesDone, bytesTotal),
            ),
        )
    }

    private fun rollForward(initial: Journal) {
        var journal = initial
        ensureTransactionDirectories(journal)
        journal.operations.indices.forEach { index ->
            checkIdentity()
            checkMutationAllowed()
            verifyStagingMarker(journal.stageId, journal.stageMarkerHash)
            val op = journal.operations[index]
            val target = resolveTarget(op, journal)
            val staged = resolveRootRelative(op.staged)
            val backup = backupPath(op, journal)
            ensureParentDirectories(target.parent)
            if (backup != null) ensureParentDirectories(backup.parent)
            validateMoveFileStores(staged, target, backup)

            val targetFingerprint = fingerprintIfPresent(target)
            val backupFingerprint = backup?.let(::fingerprintIfPresent)
            if (targetFingerprint == FileFingerprint(op.size, op.hash)) {
                if (op.old != null && backupFingerprint != op.old) {
                    throw IllegalStateException("published replacement has no verified original backup: ${op.target}")
                }
                return@forEach
            }

            if (op.old != null) {
                val originalBackup = backup ?: throw IllegalStateException("replacement backup path is missing")
                if (targetFingerprint == op.old && backupFingerprint == null) {
                    atomicMove(target, originalBackup, "backup:${op.target}")
                    journal = updatePhase(journal, index, 1)
                } else if (targetFingerprint != null || backupFingerprint != op.old) {
                    throw IllegalStateException("target or backup changed during recovery: ${op.target}")
                }
            } else if (targetFingerprint != null) {
                throw IllegalStateException("write-once target was created by an unknown source: ${op.target}")
            }

            checkMutationAllowed()
            requireRegularFile(staged, "staged file")
            requireFingerprint(staged, FileFingerprint(op.size, op.hash), "staged file")
            forceFile(staged)
            forceDirectory(staged.parent)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("destination appeared before staged publication: ${op.target}")
            }
            atomicMove(staged, target, "publish:${op.target}")
            journal = updatePhase(journal, index, 2)
        }
    }

    /** Returns false only when a valid target/backup state cannot use the staged bytes. */
    private fun canRollForward(journal: Journal): Boolean {
        verifyStagingMarker(journal.stageId, journal.stageMarkerHash)
        for (op in journal.operations) {
            verifyStagingMarker(journal.stageId, journal.stageMarkerHash)
            val target = resolveTarget(op, journal)
            val backup = backupPath(op, journal)
            val targetFingerprint = fingerprintIfPresent(target)
            val backupFingerprint = backup?.let(::fingerprintIfPresent)
            val newFingerprint = FileFingerprint(op.size, op.hash)
            if (targetFingerprint == newFingerprint) {
                if (op.old != null && backupFingerprint != op.old) {
                    throw IllegalStateException("published replacement has an unexpected/missing backup: ${op.target}")
                }
                continue
            }
            if (op.old != null) {
                if (targetFingerprint == op.old && backupFingerprint == null) {
                    // This is the pre-rename state.
                } else if (targetFingerprint == null && backupFingerprint == op.old) {
                    // The original was safely moved aside; only publish the staged file.
                } else {
                    throw IllegalStateException("target/backup hashes do not describe a recoverable replacement: ${op.target}")
                }
            } else if (targetFingerprint != null || backupFingerprint != null) {
                throw IllegalStateException("write-once target has an unexpected file: ${op.target}")
            }
            if (!stagedFileMatches(op)) return false
        }
        return true
    }

    private fun ensureRollbackPossible(journal: Journal) {
        for (op in journal.operations) {
            val target = resolveTarget(op, journal)
            val backup = backupPath(op, journal)
            val targetFingerprint = fingerprintIfPresent(target)
            val backupFingerprint = backup?.let(::fingerprintIfPresent)
            val newFingerprint = FileFingerprint(op.size, op.hash)
            if (op.old == null) {
                if (backupFingerprint != null || (targetFingerprint != null && targetFingerprint != newFingerprint)) {
                    throw IllegalStateException("new target was changed by an unknown source; rollback is unsafe: ${op.target}")
                }
            } else {
                val states = (targetFingerprint == op.old && backupFingerprint == null) ||
                    (targetFingerprint == newFingerprint && backupFingerprint == op.old) ||
                    (targetFingerprint == null && backupFingerprint == op.old)
                if (!states) throw IllegalStateException("replacement target/backup is not safe to roll back: ${op.target}")
            }
        }
    }

    private fun rollBack(initial: Journal) {
        var journal = initial
        for (index in journal.operations.indices.reversed()) {
            checkIdentity()
            checkMutationAllowed()
            verifyStagingMarker(journal.stageId, journal.stageMarkerHash)
            val op = journal.operations[index]
            val target = resolveTarget(op, journal)
            val backup = backupPath(op, journal)
            val current = fingerprintIfPresent(target)
            val backupFingerprint = backup?.let(::fingerprintIfPresent)
            val newFingerprint = FileFingerprint(op.size, op.hash)
            if (op.old == null) {
                if (current == newFingerprint) {
                    deleteOwnedTarget(target, newFingerprint, "rollback-delete:${op.target}")
                    journal = updatePhase(journal, index, 3)
                } else if (current != null) {
                    throw IllegalStateException("refusing to delete changed new target during rollback: ${op.target}")
                }
                continue
            }

            when (current) {
                op.old -> {
                    if (backupFingerprint != null) throw IllegalStateException("duplicate original and backup during rollback: ${op.target}")
                }
                newFingerprint -> {
                    if (backupFingerprint != op.old) throw IllegalStateException("verified original backup is missing during rollback: ${op.target}")
                    deleteOwnedTarget(target, newFingerprint, "rollback-delete:${op.target}")
                    journal = updatePhase(journal, index, 3)
                    ensureParentDirectories(target.parent)
                    atomicMove(backup ?: throw IllegalStateException("replacement backup path is missing"), target, "rollback-restore:${op.target}")
                    journal = updatePhase(journal, index, 4)
                }
                null -> {
                    if (backupFingerprint != op.old) throw IllegalStateException("verified original backup is missing during rollback: ${op.target}")
                    ensureParentDirectories(target.parent)
                    atomicMove(backup ?: throw IllegalStateException("replacement backup path is missing"), target, "rollback-restore:${op.target}")
                    journal = updatePhase(journal, index, 4)
                }
                else -> throw IllegalStateException("refusing to overwrite changed target during rollback: ${op.target}")
            }
        }
    }

    private fun verifyPublished(journal: Journal) {
        checkIdentity()
        journal.operations.forEach { op ->
            val target = resolveTarget(op, journal)
            requireFingerprint(target, FileFingerprint(op.size, op.hash), "published target")
        }
    }

    private fun verifyRolledBack(journal: Journal) {
        journal.operations.forEach { op ->
            val current = fingerprintIfPresent(resolveTarget(op, journal))
            if (current != op.old) throw IllegalStateException("rollback did not restore the exact prior target state: ${op.target}")
        }
    }

    private fun updatePhase(journal: Journal, index: Int, phase: Int): Journal {
        val updated = journal.copy(operations = journal.operations.toMutableList().also {
            it[index] = it[index].copy(phase = phase)
        })
        writeJournal(updated, "journal:${journal.state.name}:phase:$index:$phase")
        return updated
    }

    private fun ensureTransactionDirectories(journal: Journal) {
        validateTransactionDirectory(journal)
        val base = rootPath.resolve(TRANSACTION_DIR_NAME)
        ensureDirectory(base)
        val tx = base.resolve(journal.id)
        ensureDirectory(tx)
        val backupDir = tx.resolve(BACKUP_DIR_NAME)
        if (journal.operations.any { it.old != null }) ensureDirectory(backupDir)
    }

    private fun ensureNoOrphanTransactionDirectory() {
        val base = rootPath.resolve(TRANSACTION_DIR_NAME)
        if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) return
        rejectSymlinkComponents(base)
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("reserved transaction support path is not a directory")
        }
        Files.newDirectoryStream(base).use { children ->
            if (children.iterator().hasNext()) {
                throw IllegalStateException("transaction backup state exists without its private journal; manual recovery is required")
            }
        }
        checkMutationAllowed()
        Files.delete(base)
        forceDirectory(rootPath)
    }

    private fun validateTransactionDirectory(journal: Journal) {
        val base = rootPath.resolve(TRANSACTION_DIR_NAME)
        if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) return
        rejectSymlinkComponents(base)
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("transaction support path is not a directory")
        }
        Files.newDirectoryStream(base).use { children ->
            children.forEach { child ->
                if (child.fileName.toString() != journal.id) {
                    throw IllegalStateException("transaction support directory contains unowned state")
                }
            }
        }
        val txDir = base.resolve(journal.id)
        if (!Files.exists(txDir, LinkOption.NOFOLLOW_LINKS)) return
        rejectSymlinkComponents(txDir)
        if (!Files.isDirectory(txDir, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("transaction backup path is not a directory")
        val backupDir = txDir.resolve(BACKUP_DIR_NAME)
        Files.newDirectoryStream(txDir).use { children ->
            children.forEach { child ->
                if (child.fileName.toString() != BACKUP_DIR_NAME) {
                    throw IllegalStateException("transaction directory contains unowned state")
                }
            }
        }
        if (!Files.exists(backupDir, LinkOption.NOFOLLOW_LINKS)) return
        rejectSymlinkComponents(backupDir)
        if (!Files.isDirectory(backupDir, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("transaction backup path is not a directory")
        val expected = journal.operations.mapNotNull { it.backup?.substringAfterLast('/') }.toSet()
        Files.newDirectoryStream(backupDir).use { children ->
            children.forEach { child ->
                rejectSymlinkComponents(child)
                if (child.fileName.toString() !in expected || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    throw IllegalStateException("transaction backup directory contains unowned state")
                }
            }
        }
    }

    private fun ensureParentDirectories(directory: java.nio.file.Path) {
        require(directory.startsWith(rootPath)) { "transaction directory escaped destination root" }
        val relative = rootPath.relativize(directory)
        var current = rootPath
        for (part in relative) {
            current = current.resolve(part)
            rejectSymlinkComponents(current)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) ensureDirectory(current)
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("transaction parent is not a regular directory: $current")
            }
        }
    }

    private fun ensureDirectory(directory: java.nio.file.Path) {
        checkMutationAllowed()
        rejectSymlinkComponents(directory)
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("expected directory: $directory")
            return
        }
        val parent = directory.parent ?: throw IllegalStateException("directory has no parent: $directory")
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("directory parent is missing or unsafe: $parent")
        try {
            checkMutationAllowed()
            Files.createDirectory(directory)
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) throw e
        }
        forceDirectory(parent)
        forceDirectory(directory)
    }

    private fun atomicMove(source: java.nio.file.Path, destination: java.nio.file.Path, label: String) {
        checkIdentity()
        checkMutationAllowed()
        rejectSymlinkComponents(source)
        rejectSymlinkComponents(destination)
        requireRegularFile(source, "rename source")
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("atomic rename destination already exists: $destination")
        }
        if (!Files.isDirectory(destination.parent, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("atomic rename destination parent is not a directory: ${destination.parent}")
        }
        if (!ForeverClientUpdateCatalog.sameFilesystem(source, destination.parent)) {
            throw IllegalStateException("atomic rename crosses filesystems; activation is blocked")
        }
        checkMutationAllowed()
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            throw IllegalStateException("filesystem does not support required atomic rename; activation is blocked", e)
        } catch (e: java.nio.file.FileSystemException) {
            if (e.reason?.contains("cross-device", ignoreCase = true) == true) {
                throw IllegalStateException("atomic rename crosses filesystems; activation is blocked", e)
            }
            throw e
        }
        forceDirectory(source.parent)
        if (destination.parent != source.parent) forceDirectory(destination.parent)
        durableBoundary("move:$label")
    }

    private fun deleteOwnedTarget(path: java.nio.file.Path, expected: FileFingerprint, label: String) {
        checkIdentity()
        checkMutationAllowed()
        requireFingerprint(path, expected, "transaction-owned target")
        checkMutationAllowed()
        Files.delete(path)
        forceDirectory(path.parent)
        durableBoundary("delete:$label")
    }

    private fun cleanupCommitted(journal: Journal) {
        checkIdentity()
        checkMutationAllowed()
        verifyPublished(journal)
        journal.operations.forEach { op ->
            if (op.old != null) {
                val backup = backupPath(op, journal) ?: throw IllegalStateException("replacement backup path is missing")
                if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                    requireFingerprint(backup, op.old, "transaction backup")
                    checkMutationAllowed()
                    Files.delete(backup)
                    forceDirectory(backup.parent)
                }
            }
        }
        verifyPublished(journal)
        cleanupTransactionDirectory(journal)
        deleteJournal()
        cleanupCommittedStaging(journal)
    }

    private fun cleanupRolledBack(journal: Journal) {
        checkIdentity()
        checkMutationAllowed()
        verifyRolledBack(journal)
        journal.operations.forEach { op ->
            val backup = backupPath(op, journal) ?: return@forEach
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("rollback left an unexpected backup; refusing cleanup")
            }
        }
        cleanupTransactionDirectory(journal)
        deleteJournal()
    }

    /** Remove only this committed namespace's marker and empty directories; retain any unknown staged files. */
    private fun cleanupCommittedStaging(journal: Journal) {
        val directory = stagingDirectory(journal.stageId)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        try {
            verifyStagingMarker(journal.stageId, journal.stageMarkerHash)
            val marker = stagingMarkerPath(journal.stageId)
            removeEmptyStagingDirectories(directory, marker)
            Files.newDirectoryStream(directory).use { children ->
                for (child in children) {
                    rejectSymlinkComponents(child)
                    if (child != marker) return
                }
            }

            checkMutationAllowed()
            Files.delete(marker)
            forceDirectory(directory)
            checkMutationAllowed()
            Files.delete(directory)
            forceDirectory(directory.parent)
            val base = directory.parent
            Files.newDirectoryStream(base).use { children ->
                if (children.iterator().hasNext()) return
            }
            checkMutationAllowed()
            Files.delete(base)
            forceDirectory(rootPath)
        } catch (_: Exception) {
            // This is post-commit housekeeping; a failed cleanup leaves the
            // committed client intact and any remaining staging data untouched.
        }
    }

    /** Prune only empty directories left after journal-owned files were atomically moved. */
    private fun removeEmptyStagingDirectories(directory: java.nio.file.Path, marker: java.nio.file.Path) {
        val nestedDirectories = ArrayList<java.nio.file.Path>()
        Files.walk(directory).use { paths ->
            paths.filter { it != directory && it != marker && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted(Comparator.reverseOrder())
                .forEach { nestedDirectories.add(it) }
        }
        nestedDirectories.forEach { child ->
            rejectSymlinkComponents(child)
            Files.newDirectoryStream(child).use { contents ->
                if (!contents.iterator().hasNext()) {
                    checkMutationAllowed()
                    Files.delete(child)
                    forceDirectory(child.parent)
                }
            }
        }
    }

    private fun cleanupTransactionDirectory(journal: Journal) {
        val base = rootPath.resolve(TRANSACTION_DIR_NAME)
        if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) return
        rejectSymlinkComponents(base)
        require(Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) { "transaction support path is not a directory" }
        val txDir = base.resolve(journal.id)
        if (Files.exists(txDir, LinkOption.NOFOLLOW_LINKS)) {
            rejectSymlinkComponents(txDir)
            require(Files.isDirectory(txDir, LinkOption.NOFOLLOW_LINKS)) { "transaction backup path is not a directory" }
            val backupDir = txDir.resolve(BACKUP_DIR_NAME)
            if (Files.exists(backupDir, LinkOption.NOFOLLOW_LINKS)) {
                rejectSymlinkComponents(backupDir)
                require(Files.isDirectory(backupDir, LinkOption.NOFOLLOW_LINKS)) { "transaction backup path is not a directory" }
                Files.newDirectoryStream(backupDir).use { children ->
                    if (children.iterator().hasNext()) throw IllegalStateException("transaction backup directory contains unrecognized files")
                }
                checkMutationAllowed()
                Files.delete(backupDir)
                forceDirectory(txDir)
            }
            Files.newDirectoryStream(txDir).use { children ->
                if (children.iterator().hasNext()) throw IllegalStateException("transaction directory contains unrecognized files")
            }
            checkMutationAllowed()
            Files.delete(txDir)
            forceDirectory(base)
        }
        Files.newDirectoryStream(base).use { children ->
            if (children.iterator().hasNext()) throw IllegalStateException("transaction support directory contains unrecognized state")
        }
        checkMutationAllowed()
        Files.delete(base)
        forceDirectory(rootPath)
    }

    private fun deleteJournal() {
        checkMutationAllowed()
        if (Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFile(journalPath, "transaction journal")
            checkMutationAllowed()
            Files.delete(journalPath)
            forceDirectory(journalDir)
        }
    }

    private fun writeJournal(journal: Journal, boundary: String) {
        checkIdentity()
        checkMutationAllowed()
        rejectSymlinkComponents(journalDir)
        if (!Files.isDirectory(journalDir, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("private journal directory is unavailable")
        if (Files.exists(journalTempPath, LinkOption.NOFOLLOW_LINKS)) {
            rejectSymlinkComponents(journalTempPath)
            requireRegularFile(journalTempPath, "journal temporary")
            checkMutationAllowed()
            Files.delete(journalTempPath)
            forceDirectory(journalDir)
        }
        if (Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) requireRegularFile(journalPath, "transaction journal")
        val bytes = serializeJournal(journal)
        if (bytes.size > MAX_JOURNAL_BYTES) throw IllegalStateException("transaction journal exceeds the 1 MiB bound")
        checkMutationAllowed()
        FileChannel.open(
            journalTempPath,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            var buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                checkIdentity()
                checkMutationAllowed()
                channel.write(buffer)
            }
            checkMutationAllowed()
            channel.force(true)
        }
        forceDirectory(journalDir)
        checkIdentity()
        checkMutationAllowed()
        val options = arrayOf<CopyOption>(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.move(journalTempPath, journalPath, *options)
        } catch (e: AtomicMoveNotSupportedException) {
            throw IllegalStateException("private journal filesystem lacks atomic publication; activation is blocked", e)
        }
        forceDirectory(journalDir)
        durableBoundary(boundary)
    }

    private fun readJournal(): Journal {
        rejectSymlinkComponents(journalPath)
        requireRegularFile(journalPath, "transaction journal")
        val size = Files.size(journalPath)
        if (size <= 0 || size > MAX_JOURNAL_BYTES) throw IllegalStateException("transaction journal size is invalid")
        val bytes = Files.newInputStream(journalPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val data = ByteArray(size.toInt())
            var at = 0
            while (at < data.size) {
                val count = input.read(data, at, data.size - at)
                if (count < 0) throw IllegalStateException("transaction journal was truncated")
                if (count > 0) at += count
            }
            if (input.read() >= 0) throw IllegalStateException("transaction journal grew while being read")
            data
        }
        val json = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw IllegalStateException("transaction journal is malformed", e)
        }
        requireKeys(json, setOf("version", "id", "identity", "stageId", "stageMarkerHash", "state", "loose", "operations", "checksum"))
        if (json.getInt("version") != JOURNAL_VERSION) throw IllegalStateException("unsupported transaction journal version")
        val id = json.getString("id")
        if (!UUID_PATTERN.matches(id)) throw IllegalStateException("transaction journal id is invalid")
        val journalIdentity = normalizeHash(json.getString("identity"), "journal identity")
        val stageId = json.getString("stageId")
        if (stageId != id) throw IllegalStateException("transaction journal staging ID differs from transaction ID")
        val stageMarkerHash = normalizeHash(json.getString("stageMarkerHash"), "journal staging marker hash")
        val state = try {
            JournalState.valueOf(json.getString("state"))
        } catch (e: Exception) {
            throw IllegalStateException("transaction journal state is invalid", e)
        }
        val looseArray = json.getJSONArray("loose")
        if (looseArray.length() > MAX_OPERATIONS) throw IllegalStateException("journal loose allowlist exceeds the transaction bound")
        val loose = ArrayList<String>(looseArray.length())
        repeat(looseArray.length()) { index -> loose += validateRelativePath(looseArray.getString(index)) }
        if (loose.toSet().size != loose.size || loose != loose.sorted()) throw IllegalStateException("journal loose allowlist is not canonical")
        if (loose.map { it.lowercase() }.toSet().size != loose.size) throw IllegalStateException("journal loose allowlist contains case aliases")
        loose.forEach { if (!isSafeLoosePath(it)) throw IllegalStateException("journal contains a protected loose path") }

        val array = json.getJSONArray("operations")
        if (array.length() !in 1..MAX_OPERATIONS) throw IllegalStateException("journal operation count is invalid")
        val operations = ArrayList<Operation>(array.length())
        repeat(array.length()) { index ->
            val item = array.getJSONObject(index)
            requireKeys(item, setOf("target", "staged", "backup", "size", "hash", "oldSize", "oldHash", "phase"))
            val target = validateRelativePath(item.getString("target"))
            validateTargetPath(target, loose.toSet())
            val staged = validateStagedRelativePath(item.getString("staged"), stageId)
            val backupValue = item.get("backup")
            val backup = if (backupValue == JSONObject.NULL) null else validateRelativePath(backupValue as? String
                ?: throw IllegalStateException("journal backup path is invalid"))
            val sizeValue = item.getLong("size")
            if (sizeValue < 0) throw IllegalStateException("journal file size is negative")
            val hash = normalizeHash(item.getString("hash"), "journal file hash")
            val oldSize = item.getLong("oldSize")
            val oldHash = item.getString("oldHash")
            val old = if (oldSize == -1L && oldHash.isEmpty()) null else {
                if (oldSize < 0) throw IllegalStateException("journal prior size is invalid")
                FileFingerprint(oldSize, normalizeHash(oldHash, "journal prior hash"))
            }
            val phase = item.getInt("phase")
            if (phase !in 0..4) throw IllegalStateException("journal operation phase is invalid")
            val expectedBackup = if (old == null) null else backupRelativePath(id, index)
            if (backup != expectedBackup) throw IllegalStateException("journal backup path is not transaction-owned")
            if (classifyTarget(target, loose.toSet()) == TargetType.SEGMENT && old != null) {
                throw IllegalStateException("journal attempts to replace a write-once data segment")
            }
            operations += Operation(target, staged, backup, sizeValue, hash, old, phase)
        }
        if (operations.map { it.target.lowercase() }.toSet().size != operations.size || operations.last().target != BUILD_INFO_PATH) {
            throw IllegalStateException("journal targets are duplicated or build marker is not last")
        }
        if (json.getString("checksum") != journalChecksum(id, journalIdentity, stageId, stageMarkerHash, state, loose, operations)) {
            throw IllegalStateException("transaction journal checksum mismatch")
        }
        return Journal(id, journalIdentity, stageId, stageMarkerHash, state, loose, operations)
    }

    private fun serializeJournal(journal: Journal): ByteArray {
        val operations = JSONArray()
        journal.operations.forEach { op ->
            operations.put(JSONObject()
                .put("target", op.target)
                .put("staged", op.staged)
                .put("backup", op.backup ?: JSONObject.NULL)
                .put("size", op.size)
                .put("hash", op.hash)
                .put("oldSize", op.old?.size ?: -1L)
                .put("oldHash", op.old?.sha256 ?: "")
                .put("phase", op.phase))
        }
        val loose = JSONArray()
        journal.ownedLoosePaths.sorted().forEach(loose::put)
        val checksum = journalChecksum(
            journal.id,
            journal.identityHash,
            journal.stageId,
            journal.stageMarkerHash,
            journal.state,
            journal.ownedLoosePaths.sorted(),
            journal.operations,
        )
        return JSONObject()
            .put("version", JOURNAL_VERSION)
            .put("id", journal.id)
            .put("identity", journal.identityHash)
            .put("stageId", journal.stageId)
            .put("stageMarkerHash", journal.stageMarkerHash)
            .put("state", journal.state.name)
            .put("loose", loose)
            .put("operations", operations)
            .put("checksum", checksum)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private fun journalChecksum(
        id: String,
        identity: String,
        stageId: String,
        stageMarkerHash: String,
        state: JournalState,
        loose: List<String>,
        operations: List<Operation>,
    ): String {
        val canonical = StringBuilder("forever-client-update-journal-v1\n")
        canonicalField(canonical, id)
        canonicalField(canonical, identity)
        canonicalField(canonical, stageId)
        canonicalField(canonical, stageMarkerHash)
        canonicalField(canonical, state.name)
        canonicalField(canonical, loose.size.toString())
        loose.sorted().forEach { canonicalField(canonical, it) }
        canonicalField(canonical, operations.size.toString())
        operations.forEach { op ->
            canonicalField(canonical, op.target)
            canonicalField(canonical, op.staged)
            canonicalField(canonical, op.backup ?: "")
            canonicalField(canonical, op.size.toString())
            canonicalField(canonical, op.hash)
            canonicalField(canonical, op.old?.size?.toString() ?: "-")
            canonicalField(canonical, op.old?.sha256 ?: "")
            canonicalField(canonical, op.phase.toString())
        }
        return sha256(canonical.toString().toByteArray(Charsets.UTF_8))
    }

    private fun canonicalField(out: StringBuilder, value: String) {
        out.append(value.toByteArray(Charsets.UTF_8).size).append(':').append(value).append('\n')
    }

    private fun discardJournalTemporaryIfPresent() {
        if (!Files.exists(journalTempPath, LinkOption.NOFOLLOW_LINKS)) return
        rejectSymlinkComponents(journalTempPath)
        requireRegularFile(journalTempPath, "journal temporary")
        checkMutationAllowed()
        Files.delete(journalTempPath)
        forceDirectory(journalDir)
    }

    private fun stagedFileMatches(op: Operation): Boolean {
        val staged = resolveRootRelative(op.staged)
        if (!Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) return false
        return try {
            requireRegularFile(staged, "staged file")
            fingerprint(staged) == FileFingerprint(op.size, op.hash)
        } catch (_: java.nio.file.NoSuchFileException) {
            false
        }
    }

    private fun validateDirectories() {
        rejectSymlinkComponents(rootPath)
        rejectSymlinkComponents(journalDir)
        if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("client root is not a regular directory")
        if (!Files.isDirectory(journalDir, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("private journal directory is unavailable")
    }

    private fun validateStagingArea(staging: UpdateStagingArea) {
        require(staging.ownerToken == ownerToken && staging.id.matches(UUID_PATTERN)) {
            "staging area was not freshly created by this transaction instance"
        }
        val expectedDirectory = stagingDirectory(staging.id)
        val suppliedDirectory = staging.directory.toPath().toAbsolutePath().normalize()
        require(suppliedDirectory == expectedDirectory) { "staging area path does not match its generated transaction ID" }
        verifyStagingMarker(staging.id, staging.markerHash)
    }

    private fun validateResumedStagingNamespace(id: String) {
        val directory = stagingDirectory(id)
        Files.walk(directory).use { paths ->
            paths.forEach { path ->
                if (Files.isSymbolicLink(path)) throw IllegalStateException("resumed transaction stage contains a symlink")
                if (path == directory) return@forEach
                val relative = directory.relativize(path).toString().replace(File.separatorChar, '/')
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    if (relative != "Data" && relative != "Data/data" &&
                        relative != "Data/data/$CASCADE_SCRATCH_DIRECTORY" &&
                        !relative.startsWith("Data/data/$CASCADE_SCRATCH_DIRECTORY/")
                    ) throw IllegalStateException("resumed transaction stage contains an unowned directory: $relative")
                    return@forEach
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("resumed transaction stage contains a nonregular file")
                val admitted = relative == STAGING_MARKER_NAME || STAGED_INPUT_PATTERN.matches(relative) ||
                    SEGMENT_STAGE_PATTERN.matches(relative) || INDEX_STAGE_PATTERN.matches(relative) ||
                    relative == "Data/data/$CASC_OBJECT_TEMP" ||
                    relative.startsWith("Data/data/$CASCADE_SCRATCH_DIRECTORY/")
                if (!admitted) throw IllegalStateException("resumed transaction stage contains unowned data: $relative")
            }
        }
    }

    private fun stagingDirectory(id: String): java.nio.file.Path {
        require(UUID_PATTERN.matches(id)) { "staging ID is invalid" }
        return rootPath.resolve(STAGING_DIR_NAME).resolve(id)
    }

    private fun stagingMarkerPath(id: String): java.nio.file.Path = stagingDirectory(id).resolve(STAGING_MARKER_NAME)

    private fun stagingMarkerBytes(id: String): ByteArray =
        "forever-client-update-staging-v1\n$id\n$identityHash\n".toByteArray(Charsets.UTF_8)

    private fun verifyStagingMarker(id: String, expectedMarkerHash: String) {
        val directory = stagingDirectory(id)
        val marker = stagingMarkerPath(id)
        rejectSymlinkComponents(directory)
        rejectSymlinkComponents(marker)
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("updater-owned staging namespace is missing")
        }
        val expectedBytes = stagingMarkerBytes(id)
        val calculatedHash = sha256(expectedBytes)
        if (calculatedHash != expectedMarkerHash) throw IllegalStateException("staging ownership marker does not match destination identity")
        requireFingerprint(marker, FileFingerprint(expectedBytes.size.toLong(), expectedMarkerHash), "staging ownership marker")
    }

    private fun validateStagedFile(file: File, staging: UpdateStagingArea): java.nio.file.Path {
        val path = file.toPath().toAbsolutePath().normalize()
        val stagingRoot = stagingDirectory(staging.id)
        if (!path.startsWith(stagingRoot) || path == stagingRoot) {
            throw IllegalArgumentException("staged file must be inside this fresh updater-owned staging namespace")
        }
        rejectSymlinkComponents(path)
        requireRegularFile(path, "staged file")
        val relative = rootPath.relativize(path).toString().replace(File.separatorChar, '/')
        validateStagedRelativePath(relative, staging.id)
        return path
    }

    private fun validateStagedRelativePath(relative: String, stageId: String): String {
        val valid = validateRelativePath(relative)
        val prefix = "$STAGING_DIR_NAME/$stageId/"
        if (!valid.startsWith(prefix) || valid.substring(prefix.length) == STAGING_MARKER_NAME) {
            throw IllegalArgumentException("staged path is outside its transaction-owned staging namespace")
        }
        if (isProtectedPath(valid)) throw IllegalArgumentException("protected client data cannot be used as transaction staging: $valid")
        return valid
    }

    private fun validateTargetPath(relative: String, loose: Set<String>): java.nio.file.Path {
        val valid = validateRelativePath(relative)
        if (isProtectedPath(valid) || isReservedUpdaterPath(valid)) throw IllegalArgumentException("protected client path cannot be updated: $valid")
        val type = classifyTarget(valid, loose)
        if (type == TargetType.LOOSE && valid !in loose) {
            throw IllegalArgumentException("loose target is not in the explicit ownership allowlist: $valid")
        }
        return resolveRootRelative(valid)
    }

    private fun classifyTarget(relative: String, loose: Set<String>): TargetType {
        val lower = relative.lowercase()
        if (lower == BUILD_INFO_PATH) {
            require(relative == BUILD_INFO_PATH) { "build marker path must use its canonical spelling" }
            return TargetType.BUILD_INFO
        }
        if (lower.startsWith("data/data/")) {
            require(relative.startsWith("Data/data/")) { "CASC data directory path must use canonical spelling" }
        }
        if (lower.startsWith("data/config/")) {
            require(relative.startsWith("Data/config/")) { "CASC config directory path must use canonical spelling" }
        }
        val segment = SEGMENT_PATTERN.matchEntire(relative)
        if (segment != null) {
            val id = segment.groupValues[1].toIntOrNull() ?: throw IllegalArgumentException("invalid CASC segment number")
            require(id in 0..1023 && relative == "Data/data/${"data.%03d".format(id)}") { "CASC segment number is outside the supported range" }
            return TargetType.SEGMENT
        }
        if (INDEX_PATTERN.matches(relative)) {
            val name = relative.substringAfterLast('/')
            val bucket = name.substring(0, 2).toInt(16)
            val generation = name.substring(2, 10).toLong(16)
            require(bucket in 0..15 && generation in 1..0xffff_fffeL) { "CASC index name is outside supported bounds" }
            return TargetType.INDEX
        }
        if (CONFIG_PATTERN.matches(relative)) {
            val parts = relative.split('/')
            val hash = parts[4]
            require(parts[2] == hash.substring(0, 2) && parts[3] == hash.substring(2, 4)) {
                "CASC config path does not match its content hash"
            }
            return TargetType.CONFIG
        }
        if (lower.startsWith("data/data/") || lower.startsWith("data/config/")) {
            throw IllegalArgumentException("unrecognized target inside reserved CASC namespace: $relative")
        }
        if (relative in loose && isSafeLoosePath(relative)) return TargetType.LOOSE
        throw IllegalArgumentException("target is not an admitted CASC file or allowlisted loose file: $relative")
    }

    private fun isSafeLoosePath(path: String): Boolean {
        if (isProtectedPath(path) || isReservedUpdaterPath(path)) return false
        val lower = path.lowercase()
        if (lower == BUILD_INFO_PATH || lower.startsWith("data/data/") || lower.startsWith("data/config/")) return false
        if (SEGMENT_PATTERN.matches(path) || INDEX_PATTERN.matches(path) || CONFIG_PATTERN.matches(path)) return false
        return true
    }

    private fun isProtectedPath(path: String): Boolean {
        val lower = path.lowercase()
        val parts = lower.split('/')
        if (lower == "config.wtf" || lower.endsWith("/config.wtf") || lower.endsWith("/betasuspendedtest.wtf")) return true
        val protectedParts = setOf(
            "wtf", "runtime", "wine", ".wine", "drive_c", "credentials", "credential",
            ".credentials", "secrets", "secret", "auth", "account", "accounts",
            "password", "passwords", "token", "tokens",
        )
        return parts.any {
            it in protectedParts || it.contains("credential") || it.contains("password") ||
                it.contains("secret") || it.contains("token")
        }
    }

    private fun isReservedUpdaterPath(path: String): Boolean =
        path.lowercase().startsWith(".forever-client-update") ||
            path.lowercase().startsWith("$STAGING_DIR_NAME/") ||
            path.lowercase() == STAGING_DIR_NAME

    private fun validateRelativePath(relative: String): String {
        require(relative.isNotEmpty() && relative.length <= MAX_PATH_LENGTH) { "relative transaction path is empty or too long" }
        require(!relative.startsWith('/') && !relative.endsWith('/') && '\\' !in relative && '\u0000' !in relative) {
            "transaction path is not a canonical relative path"
        }
        val parts = relative.split('/')
        require(parts.all { it.isNotEmpty() && it != "." && it != ".." }) { "transaction path contains an unsafe component" }
        require(parts.none { it.any { c -> c.code < 0x20 || c.code == 0x7f } }) { "transaction path contains a control character" }
        return relative
    }

    private fun validateMoveFileStores(stage: java.nio.file.Path, target: java.nio.file.Path, backup: java.nio.file.Path?) {
        rejectSymlinkComponents(stage)
        if (!Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) return // A recovered, already-published operation may have consumed it.
        val targetParent = nearestExistingDirectory(target.parent)
        if (!ForeverClientUpdateCatalog.sameFilesystem(stage, targetParent)) {
            throw IllegalStateException("staging and destination are on different filesystems; activation is blocked")
        }
        if (backup != null) {
            val backupParent = nearestExistingDirectory(backup.parent)
            if (!ForeverClientUpdateCatalog.sameFilesystem(stage, backupParent)) {
                throw IllegalStateException("backup and destination are on different filesystems; activation is blocked")
            }
        }
    }

    private fun nearestExistingDirectory(path: java.nio.file.Path): java.nio.file.Path {
        var current = path
        while (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            current = current.parent ?: throw IllegalStateException("no existing filesystem ancestor for $path")
        }
        rejectSymlinkComponents(current)
        if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("filesystem ancestor is not a directory: $current")
        return current
    }

    private fun resolveTarget(op: Operation, journal: Journal): java.nio.file.Path {
        validateTargetPath(op.target, journal.ownedLoosePaths.toSet())
        return resolveRootRelative(op.target)
    }

    private fun backupPath(op: Operation, journal: Journal): java.nio.file.Path? =
        op.backup?.let(::resolveRootRelative)

    private fun resolveRootRelative(relative: String): java.nio.file.Path {
        val valid = validateRelativePath(relative)
        val result = rootPath.resolve(valid).normalize()
        require(result.startsWith(rootPath)) { "transaction path escaped destination root" }
        rejectSymlinkComponents(result)
        return result
    }

    private fun fingerprintIfPresent(path: java.nio.file.Path): FileFingerprint? {
        rejectSymlinkComponents(path)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        requireRegularFile(path, "transaction file")
        return fingerprint(path)
    }

    private fun fingerprint(path: java.nio.file.Path): FileFingerprint {
        rejectSymlinkComponents(path)
        requireRegularFile(path, "fingerprinted file")
        val size = Files.size(path)
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(HASH_BUFFER_SIZE)
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer.array(), 0, count)
            }
            if (channel.size() != size) throw IllegalStateException("file changed while being fingerprinted: $path")
        }
        hashBytesRead.addAndGet(size)
        return FileFingerprint(size, digest.digest().toHex())
    }

    private fun requireFingerprint(path: java.nio.file.Path, expected: FileFingerprint, description: String) {
        val actual = fingerprintIfPresent(path)
        if (actual != expected) throw IllegalStateException("$description fingerprint mismatch: $path")
    }

    private fun requireRegularFile(path: java.nio.file.Path, description: String) {
        rejectSymlinkComponents(path)
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("$description is not a no-follow regular file: $path")
        }
    }

    private fun forceFile(path: java.nio.file.Path) {
        checkIdentity()
        checkMutationAllowed()
        requireRegularFile(path, "file to synchronize")
        FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            checkMutationAllowed()
            channel.force(true)
        }
    }

    private fun forceDirectory(path: java.nio.file.Path) {
        checkIdentity()
        checkMutationAllowed()
        rejectSymlinkComponents(path)
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("cannot synchronize missing directory: $path")
        try {
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                checkMutationAllowed()
                channel.force(true)
            }
        } catch (e: Exception) {
            throw IllegalStateException("filesystem cannot durably synchronize directory $path; activation is blocked", e)
        }
    }

    private fun rejectSymlinkComponents(path: java.nio.file.Path) {
        val absolute = path.toAbsolutePath().normalize()
        var current = absolute.root ?: throw IllegalStateException("path has no filesystem root: $path")
        for (part in absolute) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) throw IllegalStateException("symlink in transaction path: $current")
        }
    }

    private fun checkIdentity() {
        val matches = try {
            identityStillMatches()
        } catch (e: Exception) {
            throw IllegalStateException("destination identity could not be validated", e)
        }
        if (!matches) throw IllegalStateException("destination identity changed; transaction is blocked")
    }

    private fun checkMutationAllowed() {
        checkIdentity()
        val allowed = try {
            mutationAllowed()
        } catch (e: Exception) {
            throw IllegalStateException("mutation gate could not be validated", e)
        }
        if (!allowed) throw IllegalStateException("destination mutation is not currently allowed")
    }

    private fun <T> withExclusiveLock(block: () -> T): T {
        val key = journalDir.toString()
        val monitor = PROCESS_LOCKS.computeIfAbsent(key) { Any() }
        return synchronized(monitor) {
            checkIdentity()
            validateDirectories()
            checkMutationAllowed()
            if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                rejectSymlinkComponents(lockPath)
                requireRegularFile(lockPath, "transaction lock")
            }
            val existed = Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
            checkMutationAllowed()
            FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                if (!existed) forceDirectory(journalDir)
                val lock = try {
                    channel.lock()
                } catch (e: Exception) {
                    throw IllegalStateException("cannot acquire the private updater process lock", e)
                }
                lock.use { block() }
            }
        }
    }

    private fun backupRelativePath(id: String, index: Int): String =
        "$TRANSACTION_DIR_NAME/$id/$BACKUP_DIR_NAME/${"%04d".format(index)}.bak"

    private fun requireKeys(json: JSONObject, expected: Set<String>) {
        val keys = json.keys().asSequence().toSet()
        if (keys != expected) throw IllegalStateException("transaction journal fields do not match the supported format")
    }

    private fun normalizeHash(value: String, label: String): String {
        val normalized = value.lowercase()
        require(HASH_PATTERN.matches(normalized)) { "$label must be a 64-character SHA-256 hex string" }
        return normalized
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val JOURNAL_VERSION = 1
        private const val JOURNAL_NAME = ".forever-client-update.json"
        private const val JOURNAL_TEMP_NAME = ".forever-client-update.json.tmp"
        private const val LOCK_NAME = ".forever-client-update.lock"
        private const val TRANSACTION_DIR_NAME = ".forever-client-update"
        private const val STAGING_DIR_NAME = ".forever-update-staging"
        private const val STAGING_MARKER_NAME = ".owner"
        private const val CASCADE_SCRATCH_DIRECTORY = ".forever-casc-index-work"
        private const val CASC_OBJECT_TEMP = ".forever-casc-incremental-object.tmp"
        private val STAGED_INPUT_PATTERN = Regex("file-[0-9a-f]{64}")
        private val SEGMENT_STAGE_PATTERN = Regex("Data/data/data\\.[0-9]{3,4}")
        private val INDEX_STAGE_PATTERN = Regex("Data/data/[0-9a-fA-F]{10}\\.idx")
        private const val BACKUP_DIR_NAME = "backups"
        private const val BUILD_INFO_PATH = ".build.info"
        private const val MAX_OPERATIONS = 4096
        private const val MAX_JOURNAL_BYTES = 1024 * 1024
        private const val MAX_PATH_LENGTH = 1024
        private const val MAX_IDENTITY_LENGTH = 4096
        private const val HASH_BUFFER_SIZE = 64 * 1024
        private const val FINALIZATION_EMIT_BYTES = 16L * 1024 * 1024
        private const val FINALIZATION_EMIT_NANOS = 500_000_000L
        private val HASH_PATTERN = Regex("^[0-9a-f]{64}$")
        private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val SEGMENT_PATTERN = Regex("^Data/data/data\\.(\\d{3,4})$")
        private val INDEX_PATTERN = Regex("^Data/data/[0-9a-f]{10}\\.idx$")
        private val CONFIG_PATTERN = Regex("^Data/config/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{32}$")
        private val PROCESS_LOCKS = ConcurrentHashMap<String, Any>()

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
