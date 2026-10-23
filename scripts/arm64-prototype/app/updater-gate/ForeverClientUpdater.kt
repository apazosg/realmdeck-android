package app.gamenative

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.TreeSet
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Destination supplied by the storage/runtime owner. [revalidate] must re-check its
 * frozen storage identity on every call. Existing marker/loose files are replaceable
 * only when [verifiedOwnedFiles] contains their independently admitted prior hashes.
 */
data class ForeverClientUpdateDestination(
    val root: File,
    val storageId: String,
    val revalidate: () -> Unit,
    val verifiedOwnedFiles: Map<String, FileFingerprint> = emptyMap(),
    /** Storage identity encoded by the storage service's root-ownership marker. */
    val storageMarkerIdentity: String? = null,
    /** Whether an unowned CASC tree is an explicitly admitted pasted source. */
    val sourceOrigin: ForeverClientSourceOrigin = ForeverClientSourceOrigin.APPROVED_CLIENT,
)

enum class ForeverClientSourceOrigin { APPROVED_CLIENT, PASTED_SOURCE }

/** Explicit acknowledgement tied to one read-only plan and its two per-volume costs. */
data class ForeverClientUpdateConsent(
    val planId: String,
    val downloadBytes: Long,
    val peakAdditionalBytes: Long,
    val privateWorkBytes: Long,
    val destinationBytes: Long,
)

data class ForeverClientUpdateProgress(
    val phase: Phase,
    val completedObjects: Long,
    val totalObjects: Long,
    val transferredBytes: Long,
    val totalTransferBytes: Long,
    /** Typed two-pass finalization accounting. Null on every non-finalization report. */
    val finalization: FinalizationDetail? = null,
) {
    enum class Phase { SCANNING, DOWNLOADING, STAGING, VERIFYING, APPLYING, RECOVERING }

    /** Files/bytes proven during journaled finalization, reported under Phase.APPLYING. */
    data class FinalizationDetail(
        val filesCompleted: Long,
        val filesTotal: Long,
        val bytesCompleted: Long,
        val bytesTotal: Long,
    )
}

/** Read-only estimate. It contains no caller-controlled paths or mutable file list. */
class ForeverClientUpdatePlan internal constructor(
    val planId: String,
    val version: String,
    val downloadBytes: Long,
    val privateWorkBytes: Long,
    val destinationBytes: Long,
    val peakAdditionalBytes: Long,
    val privateWorkAvailableBytes: Long,
    val destinationAvailableBytes: Long,
    val privateAndDestinationShareFileStore: Boolean,
    val hasEnoughSpace: Boolean,
    val totalObjects: Long,
    val reusableObjects: Long,
    val missingObjects: Long,
    val looseChanges: List<String>,
    val transactionFileCount: Int,
    internal val updaterToken: String,
    internal val storageId: String,
    internal val destinationPath: String,
    internal val sourceRevision: String,
    internal val snapshotHash: String,
    internal val cascNewSegmentBytes: Long,
    internal val cascIndexBytes: Long,
    internal val cascNewSegmentCount: Int,
    internal val looseChangePaths: Set<String>,
)

data class ForeverClientUpdateResult(
    val version: String,
    val appliedFiles: Int,
    val reusedObjects: Long,
    val writtenObjects: Long,
    val retainedOldSegmentBytes: Long,
)

private data class UpdateStageReceipt(
    val stageId: String,
    val markerHash: String,
    val sourceRevision: String,
    val snapshotHash: String,
    val storageId: String,
    val destinationPath: String,
    val resetCasc: Boolean = false,
)

enum class ForeverClientRecoveryResult { CLEAN, COMPLETED, ROLLED_BACK, RESUMABLE_STAGE_AVAILABLE }

/**
 * A bounded updater engine for an already admitted Windows ARM64 enUS catalog.
 * Catalog admission, transfer verification, CASC writing and journaled publication
 * are separate layers; this class owns their sequencing and never changes UI state.
 */
class ForeverClientUpdater(
    private val catalog: ForeverClientUpdateCatalog,
    private val configFiles: Map<String, File>,
    /** App-private no-backup directory; never place this on the client/FUSE volume. */
    private val privateWorkDirectory: File,
    /** Existing app-private no-backup directory for the durable transaction journal. */
    private val privateJournalDirectory: File,
    private val operationLease: ForeverClientOperationLease,
    private val fetchObject: (
        ForeverClientUpdateCatalog.ObjectRecord,
        File,
        () -> Boolean,
        (Long) -> Unit,
    ) -> File = { record, work, cancelled, progress ->
        ForeverClientUpdateTransfer().fetch(record, work, cancelled, progress)
    },
) {
    /**
     * Cold-replay fast path for resumable staged verification. Null keeps
     * every writer on the copy-based verifier; a non-null factory is
     * forwarded only to the resumable flags=2 writer calls below and never
     * leaves this instance: no public option, no static registry, no Service
     * wiring until the FUSE gate decides. Production therefore stays on the
     * old mode while this is null.
     */
    internal var replayReaderFactory: ((() -> Boolean) -> CascReplayReader)? = null
    private val targetSummary = validatedTargetSummary()
    private val targetCatalogSha256 = catalog.verifiedSha256.lowercase(Locale.ROOT).also {
        require(it.matches(HASH_PATTERN)) { "verified client catalog SHA-256 is malformed" }
    }
    private val targetBinding = "$targetCatalogSha256|${targetSummary.version}"
    private val updaterToken = "$ENGINE_ID|$RUNTIME_SCHEMA|$targetBinding"
    private val workBase = File(privateWorkDirectory, WORK_BASE_NAME)

    data class RecoveryStatus(val result: ForeverClientRecoveryResult, val pendingTransaction: Boolean)

    /** Performs only read-only destination inspection and bounded private scratch work. */
    fun plan(
        destination: ForeverClientUpdateDestination,
        cancelled: () -> Boolean = { false },
        progress: (ForeverClientUpdateProgress) -> Unit = {},
    ): ForeverClientUpdatePlan = withLease(destination) {
        ensureNoRootTransaction(destination)
        buildPlan(destination, cancelled, progress)
    }

    /**
     * Replans under the durable operation lease, compares the entire snapshot and cost,
     * checks per-volume free space, and only then creates CASC/output staging or downloads.
     */
    fun execute(
        destination: ForeverClientUpdateDestination,
        plan: ForeverClientUpdatePlan,
        consent: ForeverClientUpdateConsent,
        cancelled: () -> Boolean = { false },
        progress: (ForeverClientUpdateProgress) -> Unit = {},
    ): ForeverClientUpdateResult = withLease(destination) { leaseHeld ->
        checkConsent(destination, plan, consent)
        ensureNoRootTransaction(destination)
        destination.revalidate()
        val fresh = buildPlan(destination, cancelled, progress)
        requireSamePlan(plan, fresh)
        if (!fresh.hasEnoughSpace) {
            throw IllegalStateException(
                if (fresh.privateAndDestinationShareFileStore) {
                    "insufficient free space: private updater work and client staging share a volume; need " +
                        "${fresh.peakAdditionalBytes} bytes, currently available ${fresh.privateWorkAvailableBytes}"
                } else {
                    "insufficient free space: need ${fresh.privateWorkBytes} bytes in private updater storage and " +
                        "${fresh.destinationBytes} bytes in the client volume; currently available " +
                        "${fresh.privateWorkAvailableBytes} and ${fresh.destinationAvailableBytes}"
                },
            )
        }
        checkCancelled(cancelled)
        destination.revalidate()

        val transaction = transaction(destination, leaseHeld)
        val paths = prepareWorkspace(destination, plan, transaction)
        val buildInfo = buildInfoBytes()
        val loosePlan = inspectLooseFiles(destination)
        val privateResume = inspectPrivateWorkspace(destination, plan.sourceRevision, loosePlan)
        val retainedOldSegments = existingSegmentBytes(destination)
        val cascResult = writeCascDelta(destination, plan, paths, cancelled, progress)
        if (Math.addExact(cascResult.reusedObjects, cascResult.writtenObjects) != plan.totalObjects) {
            throw IllegalStateException("CASC writer did not account for the complete admitted object manifest")
        }
        materializeLooseFiles(destination, loosePlan, paths, privateResume.stagedLoosePaths, cancelled, progress, plan.downloadBytes)
        checkCancelled(cancelled)
        destination.revalidate()
        val configs = validateConfigAssets()
        destination.revalidate()
        val currentBuildInfo = existingFingerprint(File(destination.root, BUILD_INFO_PATH))
        if (currentBuildInfo != null && destination.verifiedOwnedFiles[BUILD_INFO_PATH] != currentBuildInfo &&
            destination.sourceOrigin != ForeverClientSourceOrigin.PASTED_SOURCE
        ) {
            throw IllegalStateException("existing .build.info is not in the explicit verified ownership allowlist")
        }
        // Conservative fresh eligibility, derived from the already admitted
        // plan and cheap target metadata: sourceless empty root, no build
        // marker, no loose replacements, and no config reused outside the
        // staged inputs. Anything else keeps the strict replacement path.
        val freshEligible = fresh.sourceRevision == EMPTY_SOURCE_REVISION &&
            currentBuildInfo == null &&
            destination.verifiedOwnedFiles[BUILD_INFO_PATH] == null &&
            loosePlan.targets.all { it.existing == null } &&
            configs.all { existingFingerprint(configTarget(destination.root, it.hash)) == null }
        if (freshEligible) {
            executeFresh(destination, plan, paths, transaction, loosePlan, configs, buildInfo, cancelled, progress)
        } else {
            val inputs = ArrayList<InputFile>(plan.transactionFileCount)
            stageCascFiles(paths.cascDataFiles, inputs, cancelled, progress)
            stageConfigs(destination, configs, paths.staging, inputs, cancelled)
            stageLooseFiles(loosePlan, paths, paths.staging, inputs, cancelled)
            val buildInfoStage = transactionStageFile(paths.staging, BUILD_INFO_PATH)
            copyBytesOrVerifyStage(buildInfo, buildInfoStage, cancelled)
            inputs += inputFile(BUILD_INFO_PATH, buildInfoStage, currentBuildInfo)
            validateStageInputs(paths.staging, inputs)
            val transactionPlan = transaction.plan(inputs, loosePlan.catalogPaths.toSet(), paths.staging)
            destination.revalidate()
            progress(ForeverClientUpdateProgress(ForeverClientUpdateProgress.Phase.APPLYING, plan.totalObjects, plan.totalObjects, plan.downloadBytes, plan.downloadBytes))
            transaction.commit(transactionPlan)
        }

        if (Files.exists(transactionStageDirectory(destination, paths.stageReceipt).toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("transaction committed but its owned stage cleanup is incomplete; recover before launch")
        }
        cleanupWorkspace(paths.root, paths.ownerBytes)
        ForeverClientUpdateResult(
            targetSummary.version,
            plan.transactionFileCount,
            cascResult.reusedObjects,
            cascResult.writtenObjects,
            retainedOldSegments,
        )
    }

    /** Recovers only the transaction's own durable journal; never infers a clean guest. */
    fun recover(
        destination: ForeverClientUpdateDestination,
        progress: (ForeverClientUpdateProgress) -> Unit = {},
    ): RecoveryStatus = withLease(destination, allowRootPending = true) {
        destination.revalidate()
        progress(ForeverClientUpdateProgress(ForeverClientUpdateProgress.Phase.RECOVERING, 0, 0, 0, 0))
        val transaction = transaction(destination) { true }
        val receiptBeforeRecovery = readStageReceipt(destination)
        val recoveryIntent = receiptBeforeRecovery?.takeIf { transaction.hasPendingTransaction() }
            ?.copy(resetCasc = true)
        if (recoveryIntent != null) persistStageReceipt(destination, resumableWorkspacePath(destination), recoveryIntent)
        val recovered = transaction.recover()
        if (recovered == ForeverClientUpdateTransaction.RecoveryResult.COMPLETED) {
            cleanupCommittedResumeState(destination)
        } else if (recovered == ForeverClientUpdateTransaction.RecoveryResult.ROLLED_BACK) {
            val receipt = readStageReceipt(destination)
            if (receipt != null) resetCascAfterRollback(destination, receipt.copy(resetCasc = true))
        } else {
            cleanupOrphanTransactionStaging(destination)
            val receipt = readStageReceipt(destination)
            if (receipt?.resetCasc == true) resetCascAfterRollback(destination, receipt)
        }
        val rootPending = hasPendingTransaction(destination)
        if (rootPending) throw IllegalStateException("client transaction recovery left unresolved root staging/backup state")
        val resumable = resumableWorkspacePath(destination).exists() || readStageReceipt(destination) != null
        if (!rootPending && resumable) {
            return@withLease RecoveryStatus(ForeverClientRecoveryResult.RESUMABLE_STAGE_AVAILABLE, false)
        }
        val result = when (recovered) {
            ForeverClientUpdateTransaction.RecoveryResult.CLEAN -> ForeverClientRecoveryResult.CLEAN
            ForeverClientUpdateTransaction.RecoveryResult.COMPLETED -> ForeverClientRecoveryResult.COMPLETED
            ForeverClientUpdateTransaction.RecoveryResult.ROLLED_BACK -> ForeverClientRecoveryResult.ROLLED_BACK
        }
        RecoveryStatus(result, rootPending)
    }

    /** Includes transaction journal/temp/backup/staging state; a lock file alone is idle. */
    fun hasPendingTransaction(destination: ForeverClientUpdateDestination): Boolean {
        val root = destination.root.toPath().toAbsolutePath().normalize()
        requireSafeAncestors(root)
        if (transaction(destination) { false }.hasPendingTransaction()) return true
        val staging = root.resolve(TRANSACTION_STAGING_NAME)
        if (!Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) return false
        if (Files.isSymbolicLink(staging) || !Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("reserved transaction staging path is not a regular directory")
        }
        val receipt = readStageReceipt(destination)
        Files.newDirectoryStream(staging).use { children ->
            for (child in children) {
                if (receipt == null || child.fileName.toString() != receipt.stageId) return true
            }
        }
        return false
    }

    /** Bounded catalog-pinning guard outcome: idle, valid own resume, or fail-closed. */
    internal enum class ResumeGuardState { IDLE, OWN_PENDING, BLOCKED }

    /**
     * Read-only catalog-pinning guard. A validated own stage receipt counts as
     * pending even when the transaction journal reports no pending transaction
     * (a lock file alone stays idle). Only bounded metadata, receipt and root
     * markers are read: no checkpoint or object scan runs here. A receipt is
     * read once; any malformed, missing (with staging), mismatched or foreign
     * state — on the selected destination or in any other private workspace —
     * yields BLOCKED, never own resume. Nothing is created, moved, deleted,
     * recovered or reset.
     */
    internal fun resumeGuardState(destination: ForeverClientUpdateDestination): ResumeGuardState {
        val receipt: UpdateStageReceipt? = try {
            readStageReceipt(destination)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            return ResumeGuardState.BLOCKED
        }
        val rootForeign: Boolean = try {
            rootStagingHasForeignChild(destination, receipt?.stageId)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            return ResumeGuardState.BLOCKED
        }
        if (rootForeign) return ResumeGuardState.BLOCKED
        val journalPending: Boolean = try {
            transaction(destination) { false }.hasPendingTransaction()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            return ResumeGuardState.BLOCKED
        }
        if (receipt != null) return ResumeGuardState.OWN_PENDING
        if (journalPending) return ResumeGuardState.BLOCKED
        return try {
            if (privateWorkspaceEvidencePresent()) ResumeGuardState.BLOCKED
            else ResumeGuardState.IDLE
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            ResumeGuardState.BLOCKED
        }
    }

    /**
     * Strict root-staging ownership: any child not named by the validated own
     * receipt (or any child at all when there is no receipt) is foreign or
     * unknown staging. Unsafe paths throw fail-closed.
     */
    private fun rootStagingHasForeignChild(destination: ForeverClientUpdateDestination, ownStageId: String?): Boolean {
        val root = destination.root.toPath().toAbsolutePath().normalize()
        requireSafeAncestors(root)
        val staging = root.resolve(TRANSACTION_STAGING_NAME)
        if (!Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) return false
        if (Files.isSymbolicLink(staging) || !Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("reserved transaction staging path is not a regular directory")
        }
        Files.newDirectoryStream(staging).use { children ->
            for (child in children) {
                if (ownStageId == null || child.fileName.toString() != ownStageId) return true
            }
        }
        return false
    }

    /**
     * With no valid own receipt, fail-closed scan for resume evidence in any private workspace
     * under the existing work base: stage receipts (or their interrupted
     * temporaries), CASC resume/checkpoint markers and non-trivial staged
     * objects/loose files. Only fixed existing entry names, ancestor/symlink
     * validators and bounded directory metadata are used: no checkpoint
     * contents are read, no foreign receipt is parsed and no identity is
     * inferred. Bare owner markers and dot-prefixed planner scratch stay
     * idle; anything else unknown or unsafe throws fail-closed.
     */
    private fun privateWorkspaceEvidencePresent(): Boolean {
        val base = workBase.toPath().toAbsolutePath().normalize()
        requireSafeAncestors(base)
        if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) return false
        if (Files.isSymbolicLink(base) || !Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("private updater work base is not a regular directory")
        }
        Files.newDirectoryStream(base).use { children ->
            for (child in children) {
                val name = child.fileName.toString()
                if (name.startsWith(".")) continue
                if (workspaceDirHasResumeEvidence(child)) return true
            }
        }
        return false
    }

    private fun workspaceDirHasResumeEvidence(dir: java.nio.file.Path): Boolean {
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("private updater workspace entry is not a regular directory")
        }
        if (!HASH_PATTERN.matches(dir.fileName.toString())) {
            throw IllegalStateException("private updater workspace entry has an unowned name")
        }
        for (relative in listOf(
            STAGE_RECEIPT_NAME,
            STAGE_RECEIPT_TEMP_NAME,
            CASC_RESUME_OWNER_NAME,
            CASC_RESUME_CHECKPOINT_NAME,
            CASC_OBJECT_TEMP_NAME,
        )) {
            if (Files.exists(dir.resolve(relative), LinkOption.NOFOLLOW_LINKS)) return true
        }
        val objects = dir.resolve("objects")
        if (Files.exists(objects, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(objects) || !Files.isDirectory(objects, LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("private updater object cache is not a regular directory")
            }
            Files.newDirectoryStream(objects).use { entries ->
                for (entry in entries) {
                    if (entry.fileName.toString() == ".forever-private-publish.lock" &&
                        Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) && Files.size(entry) == 0L
                    ) continue
                    return true
                }
            }
        }
        val loose = dir.resolve("loose")
        if (Files.exists(loose, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(loose) || !Files.isDirectory(loose, LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("private updater loose staging is not a regular directory")
            }
            Files.newDirectoryStream(loose).use { entries ->
                if (entries.iterator().hasNext()) return true
            }
        }
        return false
    }

    private data class TargetState(
        val sourceDataFiles: File?,
        val sourceRevision: String,
        val emptyRoot: Boolean,
        val storageMarker: FileFingerprint?,
        val sourceOrigin: ForeverClientSourceOrigin,
    )

    private data class ConfigState(val hash: String, val source: File, val size: Long, val sha256: String)

    private data class LooseTarget(
        val path: String,
        val record: ForeverClientUpdateCatalog.ObjectRecord,
        val existing: FileFingerprint?,
        val needsChange: Boolean,
    )

    private data class LooseState(
        val catalogPaths: List<String>,
        val targets: List<LooseTarget>,
        val changes: List<LooseTarget>,
        val changedDecodedBytes: Long,
        val backupBytes: Long,
        val observedFingerprints: Map<String, FileFingerprint?>,
    )

    private data class WorkspacePaths(
        val root: File,
        val ownerBytes: ByteArray,
        val objects: File,
        val stageReceipt: UpdateStageReceipt,
        val staging: UpdateStagingArea,
        val cascDataFiles: File,
        val loose: File,
    )

    private data class PrivateWorkspaceSummary(
        val occupiedBytes: Long,
        val cachedObjectKey: String?,
        val cachedObjectBytes: Long,
        val stagedLoosePaths: Set<String>,
        val stagedLooseEncodedBytes: Long,
        val stageReceipt: UpdateStageReceipt?,
    )

    private fun buildPlan(
        destination: ForeverClientUpdateDestination,
        cancelled: () -> Boolean,
        progress: (ForeverClientUpdateProgress) -> Unit,
    ): ForeverClientUpdatePlan {
        checkCancelled(cancelled)
        validatePrivateDirectories()
        validateDestination(destination)
        val summary = targetSummary
        val configs = validateConfigAssets()
        val target = inspectTargetState(destination)
        val loose = inspectLooseFiles(destination)
        val buildInfo = buildInfoBytes()
        val buildState = inspectBuildInfo(destination, buildInfo, target.emptyRoot, target.sourceOrigin)
        val privateResume = inspectPrivateWorkspace(destination, target.sourceRevision, loose)
        if (privateResume.stageReceipt?.resetCasc == true) {
            throw IllegalStateException("recover the interrupted rollback-stage reset before planning or updating")
        }

        var scanned = 0L
        var cachedObjectIsCascMiss: Boolean? = null
        var cachedKeyIsCheckpointed = false
        var checkpointedMissingBytes = 0L
        var checkpointedEncodedBytes = 0L
        var checkpointedObjects = 0L
        val stageReceipt = privateResume.stageReceipt
        val stageDirectory = stageReceipt?.let { transactionStageDirectory(destination, it) }
            ?: File(File(destination.root, TRANSACTION_STAGING_NAME), "unused-${workspaceKey(destination)}")
        val checkpointIdentity = stageReceipt?.let { cascCheckpointIdentity(destination, target.sourceRevision, it) }
            ?: "no-stage|${destination.storageId}|${target.sourceRevision}"
        val cascOutput = File(stageDirectory, "Data/data")
        // Fresh one-row size aggregate: same admitted maxima without
        // materializing every row. The full per-record transfer bounds stay
        // enforced in the main scan below and in every writer pipeline.
        val sizeBounds = catalog.objectSizeBounds(cancelled)
        if (sizeBounds.maxEncodedSize !in 1..MAX_ENCODED_BYTES) {
            throw IllegalStateException(
                "catalog object exceeds the admitted CASC/transfer encoded-size bound: ${sizeBounds.maxEncodedSize}",
            )
        }
        if (sizeBounds.maxDecodedSize !in 0..MAX_DECODED_BYTES) {
            throw IllegalStateException(
                "catalog object exceeds the admitted 512 MiB BLTE decoded-size bound: ${sizeBounds.maxDecodedSize}",
            )
        }
        val largestEncoded = sizeBounds.maxEncodedSize
        val plannerScratchRequired = Math.addExact(
            CASC_TABLE_AND_SORT_RESERVE,
            if (target.sourceDataFiles == null) 0L else largestEncoded,
        )
        val plannerAvailable = ForeverClientUpdateCatalog.availableBytes(privateWorkDirectory.toPath())
        if (plannerAvailable < plannerScratchRequired) {
            throw IllegalStateException(
                "insufficient app-private free space for a bounded read-only CASC plan: need " +
                    "$plannerScratchRequired bytes, available $plannerAvailable",
            )
        }
        val scratchParent = workBase
        ensureDirectoryTree(scratchParent.toPath())
        val inspectionWork = Files.createTempDirectory(scratchParent.toPath(), ".target-inspection-").toFile()
        val scan = try {
            catalog.withObjects(cancelled) { records ->
                ForeverCasc.withResumableCheckpointObjects(
                    cascOutput,
                    resumableWorkspacePath(destination),
                    checkpointIdentity,
                    cancelled,
                ) { stagedObjects ->
                    val stagedIterator = stagedObjects.iterator()
                    var stagedNext = if (stagedIterator.hasNext()) stagedIterator.next() else null
                    val specs = sequence {
                        records.forEach { record ->
                            checkCancelled(cancelled)
                            validateTransferBounds(record)
                            scanned = Math.addExact(scanned, 1L)
                            if ((scanned and 0x3fffL) == 0L) {
                                progress(ForeverClientUpdateProgress(ForeverClientUpdateProgress.Phase.SCANNING, scanned, summary.objectCount, 0, 0))
                            }
                            yield(ForeverCasc.IncrementalObjectSpec(record.ekey, record.contentKey, record.encodedSize, record.decodedSize))
                        }
                    }
                    val onScannedObject: (ForeverCasc.IncrementalObjectSpec, Boolean) -> Unit = { objectSpec, verified ->
                        val ekey = objectSpec.ekey.lowercase(Locale.ROOT)
                        while (stagedNext != null && stagedNext!!.ekey < ekey) {
                            throw IllegalStateException("resumable CASC checkpoint contains an object missing from the target cursor")
                        }
                        if (stagedNext?.ekey == ekey) {
                            val staged = requireNotNull(stagedNext)
                            if (staged.contentKey != objectSpec.contentKey.lowercase(Locale.ROOT) ||
                                staged.encodedSize != objectSpec.encodedSize || staged.decodedSize != objectSpec.decodedSize
                            ) throw IllegalStateException("resumable CASC checkpoint object differs from the admitted target manifest")
                            checkpointedObjects = Math.addExact(checkpointedObjects, 1L)
                            checkpointedEncodedBytes = Math.addExact(checkpointedEncodedBytes, staged.encodedSize)
                            if (!verified) checkpointedMissingBytes = Math.addExact(checkpointedMissingBytes, staged.encodedSize)
                            if (objectSpec.ekey == privateResume.cachedObjectKey) cachedKeyIsCheckpointed = true
                            stagedNext = if (stagedIterator.hasNext()) stagedIterator.next() else null
                        }
                        if (objectSpec.ekey == privateResume.cachedObjectKey) cachedObjectIsCascMiss = !verified
                    }
                    val inspected = if (target.sourceDataFiles == null) {
                        // Sourceless install: the admitted catalog cursor already
                        // streams ORDER BY ekey, so the ordered planner validates
                        // that strict order per row and skips the disk-backed
                        // target table. Packing, bounds, costs, per-row
                        // cancellation, and callbacks are exactly the generic
                        // ones; source reuse keeps the generic route below.
                        ForeverCasc.inspectTargetOrderedSourceNull(
                            File(inspectionWork, "scan"),
                            specs,
                            cancelled,
                            onScannedObject,
                        )
                    } else {
                        ForeverCasc.inspectTarget(
                            target.sourceDataFiles,
                            File(inspectionWork, "scan"),
                            specs,
                            cancelled,
                            onScannedObject,
                        )
                    }
                    if (stagedNext != null) throw IllegalStateException("resumable CASC checkpoint has an object outside the complete target cursor")
                    inspected
                }
            }
        } finally {
            inspectionWork.deleteRecursively()
        }
        if (scanned != summary.objectCount || scan.totalObjects != summary.objectCount) {
            throw IllegalStateException("CASC inspection did not consume the complete admitted object manifest")
        }
        val resume = catalog.withObjects(cancelled) { records ->
            ForeverCasc.inspectResumableCheckpoint(
                cascOutput,
                resumableWorkspacePath(destination),
                sequence {
                    records.forEach { record ->
                        validateTransferBounds(record)
                        yield(ForeverCasc.IncrementalObjectSpec(record.ekey, record.contentKey, record.encodedSize, record.decodedSize))
                    }
                },
                cancelled,
                checkpointIdentity,
            )
        }
        if (resume.completedObjectCount > scan.totalObjects || checkpointedMissingBytes > scan.downloadBytes) {
            throw IllegalStateException("resumable CASC checkpoint exceeds the admitted target or missing-object delta")
        }
        if (resume.completedObjectCount != checkpointedObjects || resume.completedEncodedBytes != checkpointedEncodedBytes) {
            throw IllegalStateException("resumable checkpoint summary and current source scan disagree")
        }
        var configsToAdd = 0
        var configBytes = 0L
        val configObserved = configs.associate { state ->
            val targetFile = configTarget(destination.root, state.hash)
            val current = existingFingerprint(targetFile)
            if (current == null) {
                configsToAdd++
                configBytes = Math.addExact(configBytes, state.size)
            } else {
                if (!configMatches(targetFile, state.hash)) throw IllegalStateException("existing hash-addressed CASC config is corrupt or foreign")
            }
            state.hash to current
        }
        val operationCount = Math.addExact(
            Math.addExact(scan.newSegmentCount, 16),
            Math.addExact(Math.addExact(loose.changes.size, configsToAdd), 1),
        )
        require(operationCount in 1..MAX_TRANSACTION_FILES) {
            "update requires $operationCount destination files, beyond the bounded $MAX_TRANSACTION_FILES-file transaction"
        }
        val stagedConfigTargets = configs.filter { configObserved[it.hash] == null }.map { configRelativePath(it.hash) }
        val stageTargets = (loose.changes.map { it.path } + stagedConfigTargets + BUILD_INFO_PATH).toSet()
        val existingStageInputBytes = inspectTransactionStageInputs(destination, privateResume.stageReceipt, stageTargets)

        val looseFetchBytes = loose.changes.sumOf { it.record.encodedSize }
        var cascTransferBytes = scan.downloadBytes - checkpointedMissingBytes
        var looseTransferBytes = looseFetchBytes - privateResume.stagedLooseEncodedBytes
        // Parallel sourceless branch: no credit for evictable private raws.
        // The plan carries the conservative maximum — checkpoint delta plus
        // genuinely staged loose decoded stay credited above — because any
        // leftover raw may be evicted to hold the quota, and actual reuse
        // then only reduces real network below consent. The sourced serial
        // branch keeps its current credits and source reuse: its leftovers
        // are never evicted and a persisting raw really is reused.
        if (target.sourceDataFiles != null && privateResume.cachedObjectKey != null && privateResume.cachedObjectBytes > 0L) {
            val cachedKey = privateResume.cachedObjectKey
            // A cached raw at or below the checkpoint cursor is already
            // credited through the checkpoint missing-bytes delta, so it must
            // not be discounted again as a CASC miss. Its loose reuse stays
            // credited: unlike the parallel branch, the serial path never
            // retires the raw — it stays preserved and Transfer.fetch serves
            // it as a cache hit — so a checkpointed raw shared with a loose
            // file genuinely saves that download.
            val checkpointedReuse = cachedKey == resume.lastEkey || cachedKeyIsCheckpointed
            val looseMatch = loose.changes.any { it.record.ekey == cachedKey } && cachedKey !in privateResume.stagedLoosePaths
            when {
                looseMatch && (checkpointedReuse || cachedObjectIsCascMiss != true) -> {
                    looseTransferBytes = (looseTransferBytes - privateResume.cachedObjectBytes).coerceAtLeast(0L)
                }
                checkpointedReuse -> Unit
                cachedObjectIsCascMiss == true -> {
                    cascTransferBytes = (cascTransferBytes - privateResume.cachedObjectBytes).coerceAtLeast(0L)
                }
            }
        }
        val transferBytes = Math.addExact(cascTransferBytes.coerceAtLeast(0L), looseTransferBytes.coerceAtLeast(0L))
        if (target.sourceDataFiles == null && replayReaderFactory != null && resume.completedObjectCount > 0) {
            // Sealed-prefix overlap admission: new segments start at fresh IDs
            // above the frozen prefix maximum, and the last frozen segment is
            // sealed (never appended) rather than continued. The plan already
            // packs missing objects into fresh 480-based segments — exactly
            // the sealed layout — so the byte bounds need no post-consent
            // delta; only the 0..1023 ID range needs this explicit pre-consent
            // probe. It runs identically in the original and the fresh plan,
            // so approval always covers the sealed layout.
            val prefixIds = ForeverCasc.resumableStagedSegmentIds(cascOutput)
            ForeverCasc.requireOverlapSegmentCapacity(prefixIds.maxOrNull() ?: -1, scan.newSegmentCount)
        }
        // Private cost covers the new in-flight bound, not just one object: a
        // sourceless install prefetches up to PREFETCH_MAX_BYTES ahead of the
        // commit cursor, while a sourced update fetches serially one object at
        // a time. Leftovers already on disk reduce the remainder via occupied.
        val prefetchInFlightBound = if (target.sourceDataFiles == null) {
            val missingTotal = try {
                Math.multiplyExact(scan.missingObjects, largestEncoded)
            } catch (_: ArithmeticException) {
                Long.MAX_VALUE
            }
            minOf(PREFETCH_MAX_BYTES, missingTotal)
        } else {
            largestEncoded
        }
        val privateRequired = sumExact(
            prefetchInFlightBound, // private resumable transfer file(s)
            largestEncoded, // one private CASC verification temporary for the largest admitted encoded object
            loose.changedDecodedBytes,
            Math.multiplyExact(scan.missingObjects, CHECKPOINT_FRAME_BYTES.toLong()),
            CASC_CHECKPOINT_HEADER_BYTES + CASC_CHECKPOINT_FINALIZE_BYTES,
            METADATA_RESERVE,
        )
        val privatePlannerBytes = Math.addExact(plannerScratchRequired, METADATA_RESERVE)
        val destinationRequired = sumExact(
            scan.newSegmentBytesUpperBound,
            scan.indexBytesUpperBound,
            CASC_TABLE_AND_SORT_RESERVE,
            loose.changedDecodedBytes,
            configBytes,
            buildInfo.size.toLong(),
            loose.backupBytes,
            buildState.fingerprint?.size ?: 0L,
            JOURNAL_AND_MARGIN_RESERVE,
        )
        val workspaceBytes = maxOf(
            (privateRequired - privateResume.occupiedBytes).coerceAtLeast(0L),
            privatePlannerBytes,
        )
        val cascStageOccupied = Math.addExact(
            resume.retainedSegmentBytes,
            Math.addExact(resume.finalizingIndexBytes, resume.reclaimableScratchBytes),
        )
        val destinationBytes = (destinationRequired - cascStageOccupied - existingStageInputBytes).coerceAtLeast(0L)
        val peak = Math.addExact(workspaceBytes, destinationBytes)
        val sharedFileStore = ForeverClientUpdateCatalog.sharedCapacity(privateWorkDirectory.toPath(), destination.root.toPath())
        val privateAvailable = ForeverClientUpdateCatalog.availableBytes(privateWorkDirectory.toPath())
        val destinationAvailable = ForeverClientUpdateCatalog.availableBytes(destination.root.toPath())
        val enough = if (sharedFileStore) privateAvailable >= peak else
            privateAvailable >= workspaceBytes && destinationAvailable >= destinationBytes
        val snapshot = snapshotHash(
            target.sourceRevision,
            target.storageMarker,
            destination.storageMarkerIdentity,
            loose,
            buildState,
            configs,
            configObserved,
            buildInfo,
        )
        privateResume.stageReceipt?.let { receipt ->
            require(receipt.snapshotHash == snapshot) {
                "resumable transaction stage is bound to a different destination/loose/config snapshot"
            }
        }
        val looseChanges = loose.changes.map { it.path }
        val normalizedDestination = destination.root.toPath().toAbsolutePath().normalize().toString()
        val planId = sha256(
            listOf(
                ENGINE_ID,
                targetCatalogSha256,
                targetSummary.version,
                destination.storageId,
                normalizedDestination,
                snapshot,
            ).joinToString("\n").toByteArray(Charsets.UTF_8),
        )
        return ForeverClientUpdatePlan(
            planId,
            summary.version,
            transferBytes,
            workspaceBytes,
            destinationBytes,
            peak,
            privateAvailable,
            destinationAvailable,
            sharedFileStore,
            enough,
            scan.totalObjects,
            scan.verifiedObjects,
            scan.missingObjects,
            looseChanges,
            operationCount,
            updaterToken,
            destination.storageId,
            normalizedDestination,
            target.sourceRevision,
            snapshot,
            scan.newSegmentBytesUpperBound,
            scan.indexBytesUpperBound,
            scan.newSegmentCount,
            loose.changes.map { it.path }.toSet(),
        )
    }

    /** One parallel prefetch slot: spec + record + its asynchronous verified output. */
    private class Prefetched(
        val spec: ForeverCasc.IncrementalObjectSpec,
        val record: ForeverClientUpdateCatalog.ObjectRecord,
        val fetchedBytes: AtomicLong,
        val future: Future<File>,
    )

    private object PrefetchEnd

    /** Keyed in-flight bound: one full encoded bound per distinct private raw,
     * never double-counted for the same key. Seeded from the owned leftovers
     * found on disk, so a resume never starts a second 1 GiB on top of crash
     * debt; old and new count together under one hard 1 GiB total. A
     * reservation is released only when its copy is really retired (owned
     * unlink made durable first), never before. New fetches wait while
     * inherited debt keeps the total over cap — but only while committable or
     * staged work can still free bytes; with nothing submitted, nothing
     * queued and no bare awaiting its after-yield retire, an evictable
     * inherited victim makes room or the request fails expressly instead of
     * waiting forever. The map itself is bounded at the same 34-key recovery
     * ceiling as admission: old leftovers plus new work counted together
     * before any file is created, never a wider figure. Release stays short
     * and never blocks: no byte wait, no progress lock, no fetch, no queue
     * wait. */
    private class PrefetchBudget {
        private val reservations = LinkedHashMap<String, Long>()
        private var reservedBytes = 0L
        fun seed(key: String, size: Long) {
            val normalized = key.lowercase(Locale.ROOT)
            require(size in 1..PREFETCH_MAX_BYTES) { "prefetch seed exceeds the bounded private byte budget" }
            synchronized(this) {
                if (reservations.containsKey(normalized)) return
                // Same 34-key recovery quota as request(): inherited disk
                // debt counts before any file is produced, on every call path
                // — including leftovers planted after the plan. No deletion,
                // no silent admission past the bound: refuse before producing.
                if (reservations.size >= MAX_RESERVED_KEYS) {
                    throw IllegalStateException("prefetch reservation map exceeds its 34-key recovery quota")
                }
                reservations[normalized] = size
                reservedBytes = Math.addExact(reservedBytes, size)
            }
        }
        fun request(
            key: String,
            size: Long,
            cancelled: () -> Boolean,
            drainPossible: () -> Boolean,
            evictOne: () -> Boolean,
        ) {
            val normalized = key.lowercase(Locale.ROOT)
            require(size in 1..PREFETCH_MAX_BYTES) { "prefetch request exceeds the bounded private byte budget" }
            while (true) {
                // waitForDrain is snapshotted once per iteration: the
                // producer is the only submitter and the consumer only
                // decreases, so a false snapshot is stable — no new submit
                // can appear while this request waits, and only a commit or
                // retire can free room. The eviction callback runs outside
                // the budget lock so its file IO never blocks consumer
                // releases, with no semaphores or new framework.
                val waitForDrain = synchronized(this) {
                    val current = reservations[normalized]
                    if (current != null) {
                        // Identity already hash-recorded: accept without
                        // double count while the hard total stays within cap;
                        // above cap the inherited debt must drain first.
                        if (reservedBytes <= PREFETCH_MAX_BYTES) return
                    } else if (reservations.size < MAX_RESERVED_KEYS && reservedBytes <= PREFETCH_MAX_BYTES - size) {
                        reservations[normalized] = size
                        reservedBytes = Math.addExact(reservedBytes, size)
                        return
                    }
                    // Over the hard 34-key/1 GiB quota: while queued,
                    // submitted or bare-pending work can still free bytes,
                    // waiting is valid liveness.
                    drainPossible()
                }
                if (waitForDrain) {
                    if (cancelled()) throw CancellationException("client update was paused or cancelled")
                    Thread.sleep(100)
                    continue
                }
                // Nothing submitted, nothing queued and no bare awaiting its
                // after-yield retire: evict one legal inherited victim and
                // retry, or — with no victim either — fail expressly instead
                // of waiting forever, so the run escalates to manual review
                // instead of hanging.
                if (evictOne()) continue
                throw IllegalStateException(
                    "prefetch budget cannot admit a new fetch while inherited disk debt holds the 34-key/1 GiB quota " +
                        "and no committable or staged work remains to drain it; manual review is required",
                )
            }
        }
        fun release(key: String) {
            synchronized(this) {
                reservations.remove(key.lowercase(Locale.ROOT))?.let { reservedBytes = (reservedBytes - it).coerceAtLeast(0L) }
            }
        }
        fun hasReservation(key: String): Boolean {
            synchronized(this) { return reservations.containsKey(key.lowercase(Locale.ROOT)) }
        }
        fun reset() {
            synchronized(this) {
                reservations.clear()
                reservedBytes = 0L
            }
        }
    }

    private fun writeCascDelta(
        destination: ForeverClientUpdateDestination,
        plan: ForeverClientUpdatePlan,
        paths: WorkspacePaths,
        cancelled: () -> Boolean,
        progress: (ForeverClientUpdateProgress) -> Unit,
    ): ForeverCasc.IncrementalResult {
        val target = inspectTargetState(destination)
        if (target.sourceRevision != plan.sourceRevision) throw IllegalStateException("client CASC source changed after planning")
        // Parallel prefetch downloads every non-checkpointed object up front. Over
        // an existing source most of those are reusable, so prefetch would spend
        // consent on bytes the writer never needs. With a source present the
        // writer pulls one object at a time and fetches only genuine misses.
        // The sealed-prefix overlap arms only on the sourceless path: sourced
        // serial updates keep the old behavior even with a reader factory.
        return if (target.sourceDataFiles == null) {
            val overlapIdentity = cascCheckpointIdentity(destination, plan.sourceRevision, paths.stageReceipt)
            val overlapArmed = replayReaderFactory != null &&
                ForeverCasc.resumableCheckpointHasObjects(paths.cascDataFiles, paths.root, overlapIdentity, cancelled)
            writeCascDeltaParallel(destination, plan, paths, cancelled, progress, overlapArmed, overlapIdentity)
        } else {
            writeCascDeltaSerial(destination, plan, paths, target, cancelled, progress)
        }
    }

    /** Serial writer for updates over an existing source: fetches only misses, in order. */
    private fun writeCascDeltaSerial(
        destination: ForeverClientUpdateDestination,
        plan: ForeverClientUpdatePlan,
        paths: WorkspacePaths,
        target: TargetState,
        cancelled: () -> Boolean,
        progress: (ForeverClientUpdateProgress) -> Unit,
    ): ForeverCasc.IncrementalResult {
        val transferred = AtomicLong(0L)
        val lastReported = AtomicLong(0L)
        var committedObjects = 0L
        fun reportLocked(currentNetworkBytes: Long) {
            val total = Math.addExact(transferred.get(), currentNetworkBytes)
                .coerceIn(0L, plan.downloadBytes)
            val floor = lastReported.get()
            if (total > floor) {
                lastReported.set(total)
                progress(
                    ForeverClientUpdateProgress(
                        ForeverClientUpdateProgress.Phase.DOWNLOADING,
                        committedObjects,
                        plan.totalObjects,
                        total,
                        plan.downloadBytes,
                    ),
                )
            }
        }
        return catalog.withObjects(cancelled) { records ->
            // The writer consumes specs strictly in order on this thread, so the
            // record staged alongside the most recently yielded spec is exactly
            // the one a sourceFor call can ask about. Reused and checkpointed
            // objects never reach sourceFor; the staging slot is simply
            // overwritten by the next yield. O(1) memory, no catalog re-query.
            var staged: Pair<Long, ForeverClientUpdateCatalog.ObjectRecord>? = null
            var counter = 0L
            val networkQueue = ArrayDeque<Long>()
            val specs = sequence {
                records.forEach { record ->
                    checkCancelled(cancelled)
                    validateTransferBounds(record)
                    counter += 1
                    staged = Pair(counter, record)
                    yield(ForeverCasc.IncrementalObjectSpec(record.ekey, record.contentKey, record.encodedSize, record.decodedSize))
                }
                staged = null
            }
            ForeverCasc.writeTargetIncrementalResumable(
                target.sourceDataFiles,
                paths.cascDataFiles,
                paths.root,
                specs,
                { spec ->
                    checkCancelled(cancelled)
                    destination.revalidate()
                    val (_, record) = staged
                        ?: throw IllegalStateException("CASC writer requested an object outside the fetch pipeline")
                    require(record.ekey.equals(spec.ekey, ignoreCase = true)) {
                        "CASC writer requested an object outside the fetch pipeline"
                    }
                    staged = null
                    val part = File(paths.objects, "${record.ekey}.part")
                    val resumeOffset = if (Files.isRegularFile(part.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        Files.size(part.toPath()).takeIf { it in 1 until record.encodedSize } ?: 0L
                    } else 0L
                    var objectNetworkBytes = 0L
                    val raw = fetchObject(record, paths.objects, cancelled) { bytes ->
                        objectNetworkBytes = maxOf(objectNetworkBytes, (bytes - resumeOffset).coerceAtLeast(0L))
                        reportLocked(objectNetworkBytes)
                    }
                    networkQueue.addLast(objectNetworkBytes)
                    raw
                },
                cancelled,
                cascCheckpointIdentity(destination, plan.sourceRevision, paths.stageReceipt),
                { _, raw ->
                    if (Files.isSymbolicLink(raw.toPath()) || !Files.isRegularFile(raw.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        throw IllegalStateException("verified encoded transfer is not a regular private-work file")
                    }
                    transferred.addAndGet(networkQueue.removeFirstOrNull() ?: 0L)
                    committedObjects += 1
                    reportLocked(0L)
                    Files.delete(raw.toPath())
                },
                replayReaderFactory = replayReaderFactory,
            )
        }
    }

    /** Inventory the owned private raws left by an interrupted run so the
     * in-flight budget starts from known disk debt instead of zero. Only
     * admitted catalog keys count, one full encoded bound each; anything
     * unowned was already rejected by the read-only admission before this
     * runs, and anything unrecognized here fails closed. A key resting with
     * both a partial and a verified output is refused before producing: real
     * transfer flows never leave that dual state (publication moves .part to
     * .blte atomically and a valid .blte is returned without creating a
     * .part), so reserving a single bound could not cover it. Names must
     * already be canonical lowercase: an uppercase variant is an atypical
     * artifact that is never deleted, only refused. Returns the inherited
     * key set that the producer may later evict once scheduled. */
    private fun seedInheritedRawReservations(objectsDir: File, budget: PrefetchBudget): Set<String> {
        val directory = objectsDir.toPath()
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return emptySet()
        val seen = HashSet<String>()
        Files.newDirectoryStream(directory).use { children ->
            children.forEach { path ->
                val fileName = path.fileName.toString()
                val match = Regex("(?i)([0-9a-f]{32})\\.(blte|part)").matchEntire(fileName)
                if (match == null) {
                    // The only non-raw the admission tolerates here is the
                    // empty publish lock; anything else changed under the
                    // read-only plan, so refuse before producing instead of
                    // silently adopting it. Never deleted.
                    if (fileName == ".forever-private-publish.lock" &&
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == 0L
                    ) return@forEach
                    throw IllegalStateException("private updater object cache contains an unrecognized entry")
                }
                if (fileName != fileName.lowercase(Locale.ROOT)) {
                    throw IllegalStateException("private updater object cache holds a non-canonical key name; manual review is required")
                }
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw IllegalStateException("private updater object cache contains an unsafe entry")
                }
                val key = match.groupValues[1].lowercase(Locale.ROOT)
                if (!seen.add(key)) {
                    throw IllegalStateException("private updater cache holds an ambiguous dual raw for $key; manual review is required")
                }
                val record = catalog.objectByKey(key)
                if (Files.size(path) > record.encodedSize) {
                    throw IllegalStateException("private updater cached raw exceeds its admitted encoded bound: $key")
                }
                budget.seed(key, record.encodedSize)
            }
        }
        return seen
    }

    /** Retire leftover private raws known to be redundant: either a
     * checkpointed object whose staged CASC copy the writer just re-verified
     * with full EKey/BLTE/content-key/size checks, or an unscheduled
     * inherited raw the producer evicts to hold the 34-key quota. Only owned
     * regular files for the admitted catalog key are removed, and only before
     * any mutation is every guard proven: exact canonical lowercase names, no
     * symlinks, regular files, and sizes within 0..encodedSize. A verified
     * `.blte` additionally passes its full encoded validity check; a partial
     * is never content-verified here — its value was already proven (by the
     * writer's staged-copy check, or by its admission as a bounded leftover),
     * and its bytes are never used to build Data. A checkpointed `.part`
     * without its output is a legitimate crash artifact (publication persists
     * only a lock plus an atomic move with no directory force, so the partial
     * can stay visible while the checkpoint is already durable): it is
     * drained here, durably, exactly like the output — never retained
     * indefinitely, never a manual dead end. Unknown names, symlinks,
     * non-regular files, oversized raws or invalid verified outputs fail
     * closed and nothing is deleted (no finally delete). No durable data is
     * lost: the CASC staged copy was verified and its checkpoint was forced
     * before any unlink, and an evicted raw was never incorporated. */
    private fun retireOwnedLeftoverRaw(objectsDir: File, budget: PrefetchBudget, ekey: String) {
        val key = ekey.lowercase(Locale.ROOT)
        val blte = File(objectsDir, "$key.blte").toPath()
        val part = File(objectsDir, "$key.part").toPath()
        val blteExists = Files.exists(blte, LinkOption.NOFOLLOW_LINKS)
        val partExists = Files.exists(part, LinkOption.NOFOLLOW_LINKS)
        if (!blteExists && !partExists) {
            // No raw to retire. A reservation without any file is not a
            // legitimate generated state — normal paths delete, force and
            // release in the same call, an exception aborts the run, the next
            // seed maps only existing files, emitted checkpointed keys are
            // never evicted and evicting active keys is forbidden — so a
            // ghost reservation fails closed expressly instead of freeing or
            // forcing an absence that proves nothing about the storage. The
            // common full-replay case (no reservation, nothing on disk)
            // returns with no fsync at all.
            if (budget.hasReservation(key)) {
                throw IllegalStateException("private updater holds a reservation without its raw files: $key; manual review is required")
            }
            return
        }
        // Catalog lookup only for real on-disk candidates: a full replay
        // re-yields every checkpointed spec, but queries only its handful of
        // surviving raws instead of one query per admitted object.
        val record = catalog.objectByKey(key)
        if (blteExists) {
            if (Files.isSymbolicLink(blte)) throw IllegalStateException("private updater checkpointed raw is a symlink: $key")
            if (!Files.isRegularFile(blte, LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("private updater checkpointed raw is not a regular file: $key")
            }
            if (Files.size(blte) != record.encodedSize || !verifyEncodedObject(blte.toFile(), record)) {
                throw IllegalStateException("private updater checkpointed raw failed its owned validity check: $key")
            }
        }
        if (partExists) {
            if (Files.isSymbolicLink(part)) throw IllegalStateException("private updater checkpointed prefix is a symlink: $key")
            if (!Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("private updater checkpointed prefix is not a regular file: $key")
            }
            if (Files.size(part) > record.encodedSize) {
                throw IllegalStateException("private updater checkpointed prefix exceeds its admitted encoded bound: $key")
            }
        }
        if (blteExists) Files.delete(blte)
        if (partExists) Files.delete(part)
        // Durable retirement before freeing: only this private cache
        // directory is forced — Data, checkpoint and journal cadence is
        // unchanged. Absence is only counted after the force: without it a
        // power cut could resurrect the unlink, so a reappeared raw fails
        // closed instead of freeing bytes for a file still on disk. A failed
        // force propagates without releasing, so the next resume
        // re-inventories instead of undercounting.
        forceDirectory(objectsDir.toPath())
        if (Files.exists(blte, LinkOption.NOFOLLOW_LINKS) || Files.exists(part, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("private updater retired raw reappeared after its durable retire: $key; manual review is required")
        }
        budget.release(key)
    }

    /** Parallel prefetch writer for sourceless installs: nothing is reusable, so
     * every non-checkpointed object will be downloaded and fetching ahead is free. */
    private fun writeCascDeltaParallel(
        destination: ForeverClientUpdateDestination,
        plan: ForeverClientUpdatePlan,
        paths: WorkspacePaths,
        cancelled: () -> Boolean,
        progress: (ForeverClientUpdateProgress) -> Unit,
        overlapArmed: Boolean,
        overlapIdentity: String,
    ): ForeverCasc.IncrementalResult {
        val transferred = AtomicLong(0L)
        val lastReported = AtomicLong(0L)
        val progressLock = Any()
        // In-flight per-object maxima. Committed bytes live in [transferred];
        // uncommitted in-flight bytes live here. Both mutate only under
        // [progressLock], and every callback runs under it, so reports are
        // serialized and monotonic.
        val inFlight = LinkedHashMap<String, Long>()
        val committedObjects = AtomicLong(0L)
        fun reportLocked() {
            var total = transferred.get()
            inFlight.values.forEach { total = Math.addExact(total, it) }
            total = total.coerceIn(0L, plan.downloadBytes)
            if (total >= lastReported.get()) {
                lastReported.set(total)
                progress(
                    ForeverClientUpdateProgress(
                        ForeverClientUpdateProgress.Phase.DOWNLOADING,
                        committedObjects.get(),
                        plan.totalObjects,
                        total,
                        plan.downloadBytes,
                    ),
                )
            }
        }
        val tearingDown = AtomicBoolean(false)
        // Workers observe the teardown flag as cancellation: an interrupt alone
        // cannot unblock a socket read, and without this a worker could retry a
        // transient failure and keep writing its .part after this call returns.
        val workerCancelled = { tearingDown.get() || cancelled() }
        // Sealed-prefix overlap was decided by the caller before any thread or
        // write (sourceless install, explicit reader factory, non-empty
        // checkpointed prefix). Initial installs (empty checkpoint) and
        // sourced updates keep the old paths with no verifier thread, no
        // coordinator, and no behavior change.
        return catalog.withObjects(cancelled) { records ->
            val queue = LinkedBlockingQueue<Any>(PREFETCH_QUEUE_DEPTH)
            val budget = PrefetchBudget()
            // Inventory the owned leftovers of any interrupted run first: the
            // in-flight budget starts from known disk debt instead of zero,
            // so a resume never reserves a second 1 GiB on top of crash
            // copies that are still useful or awaiting retired verification.
            // Single scan: it both seeds the budget and builds the evictable
            // inherited set the producer drains from.
            // Fetches submitted but not yet committed, and bare specs emitted
            // but not yet retired: while any exists, a budget request blocked
            // by inherited debt can still be freed by an upcoming commit or
            // retire. With nothing submitted, nothing queued and no bare
            // awaiting its after-yield retire, no prefix can drain, so the
            // request evicts or fails expressly.
            val submittedUncommitted = AtomicInteger(0)
            val outstandingBare = AtomicInteger(0)
            // Inherited raws the producer has not scheduled yet: the only
            // legal eviction victims when the quota is full and nothing
            // in-flight can drain. A key leaves this set the moment the
            // producer reaches it; the far-future (largest) key goes first,
            // never the object currently needed.
            val leftoverKeys = seedInheritedRawReservations(paths.objects, budget)
            val evictable = TreeSet(leftoverKeys)
            // Overlap coordinator: created exactly when armed, shared by the
            // producer (registration/retire accounting) and the writer (freeze
            // and group acks). The wanted leftover set is snapshotted here so
            // the load can collect staged-copy locations during its single
            // streaming pass. Raw retirement fires exactly once per key: from
            // a verifier group ack (staged copy fully proven) or from the
            // individual full-strength proof on the blocked-budget path below
            // — never from an unverified bare yield, and never twice.
            val overlap = if (overlapArmed) {
                CascOverlapCoordinator(
                    retirePrefixRaw = { ekey ->
                        retireOwnedLeftoverRaw(paths.objects, budget, ekey)
                        outstandingBare.decrementAndGet()
                    },
                    wantedKeys = leftoverKeys,
                )
            } else {
                null
            }
            // Budget waits additionally observe verifier death (overlap only;
            // null otherwise, so the expression is constant false on the old
            // paths). No per-object ack wait: this only observes terminal
            // failure so a dead verifier can never strand the producer behind
            // group acks that will never arrive.
            val requestCancelled = { workerCancelled() || (overlap?.failure?.get() != null) }
            val pending = LinkedHashMap<String, Prefetched>()
            val producerCrash = AtomicReference<Throwable?>()
            val executor = Executors.newFixedThreadPool(PREFETCH_WORKERS) { runnable ->
                Thread(runnable).apply { name = "forever-fetch"; isDaemon = true }
            }
            fun emit(entry: Any) {
                while (!queue.offer(entry, 250, TimeUnit.MILLISECONDS)) {
                    if (tearingDown.get()) throw CancellationException("client update was paused or cancelled")
                    checkCancelled(cancelled)
                }
            }
            fun worker(record: ForeverClientUpdateCatalog.ObjectRecord, entryBytes: AtomicLong): File {
                // Resume accounting lives in the worker now: the parallel pool runs
                // ahead of the commit cursor while the writer stays strictly in
                // catalog order.
                val part = File(paths.objects, "${record.ekey}.part")
                val resumeOffset = if (Files.isRegularFile(part.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    Files.size(part.toPath()).takeIf { it in 1 until record.encodedSize } ?: 0L
                } else 0L
                return fetchObject(record, paths.objects, workerCancelled) { bytes ->
                    if (tearingDown.get()) throw CancellationException("client update was paused or cancelled")
                    val network = (bytes - resumeOffset).coerceAtLeast(0L)
                    synchronized(progressLock) {
                        entryBytes.accumulateAndGet(network) { current, next -> maxOf(current, next) }
                        inFlight[record.ekey] = maxOf(inFlight.getOrDefault(record.ekey, 0L), network)
                        reportLocked()
                    }
                }
            }
            val producer = Thread {
                try {
                    val identity = cascCheckpointIdentity(destination, plan.sourceRevision, paths.stageReceipt)
                    // The writer creates the identity-bound owner/checkpoint on
                    // the calling thread concurrently with this open. A read
                    // that lands mid-creation must not fail the run: retry
                    // bounded while nothing was submitted yet. Any failure
                    // after the checkpoint opened propagates immediately, and a
                    // genuinely foreign checkpoint still throws after the bound.
                    var checkpointOpened = false
                    var openAttempts = 0
                    while (true) {
                        try {
                            ForeverCasc.withResumableCheckpointObjects(
                                paths.cascDataFiles,
                                paths.root,
                                identity,
                                workerCancelled,
                            ) { staged ->
                                checkpointOpened = true
                                val stagedIterator = staged.iterator()
                                var stagedNext: ForeverCasc.IncrementalObjectSpec? = if (stagedIterator.hasNext()) stagedIterator.next() else null
                                records.forEach { record ->
                                    if (tearingDown.get()) throw CancellationException("client update was paused or cancelled")
                                    checkCancelled(cancelled)
                                    validateTransferBounds(record)
                                    val ekey = record.ekey.lowercase(Locale.ROOT)
                                    val contentKey = record.contentKey.lowercase(Locale.ROOT)
                                    while (stagedNext != null && stagedNext!!.ekey < ekey) {
                                        throw IllegalStateException("resumable CASC checkpoint contains an object missing from the target cursor")
                                    }
                                    // Reached means scheduled: this key can no
                                    // longer be evicted as an unscheduled
                                    // inherited leftover.
                                    evictable.remove(ekey)
                                    val spec = ForeverCasc.IncrementalObjectSpec(ekey, contentKey, record.encodedSize, record.decodedSize)
                                    if (stagedNext?.ekey == ekey) {
                                        val completed = requireNotNull(stagedNext)
                                        if (completed.contentKey != contentKey || completed.encodedSize != record.encodedSize || completed.decodedSize != record.decodedSize) {
                                            throw IllegalStateException("resumable CASC checkpoint staged object changed in the target catalog")
                                        }
                                        stagedNext = if (stagedIterator.hasNext()) stagedIterator.next() else null
                                        if (overlap != null) {
                                            // Sealed-prefix overlap: the staged
                                            // bytes stay UNVERIFIED until the
                                            // verifier group-acks them, so the
                                            // original bare-yield raw retirement
                                            // is removed here. Only keys that
                                            // actually hold a leftover raw are
                                            // registered (bounded by the 34-key
                                            // recovery quota). The increment
                                            // happens BEFORE registration, so
                                            // a late registration (the verifier
                                            // already acked past this key)
                                            // balances with an immediate single
                                            // retire instead of stranding the
                                            // budget or double-counting.
                                            if (completed.ekey.lowercase(java.util.Locale.ROOT) in leftoverKeys) {
                                                outstandingBare.incrementAndGet()
                                                if (overlap?.registerRawKey(completed.ekey) == true) {
                                                    retireOwnedLeftoverRaw(paths.objects, budget, completed.ekey)
                                                    outstandingBare.decrementAndGet()
                                                }
                                            }
                                            emit(spec)
                                            return@forEach
                                        }
                                        // Already committed by the interrupted run: yielded
                                        // bare, with no pending entry. The writer verifies
                                        // the staged bytes itself and never calls sourceFor
                                        // for this spec, so keeping it out of [pending]
                                        // also fixes the O(N) leak of never-removed
                                        // checkpoint entries. Counted before the
                                        // queue offer so the consumer's liveness
                                        // check never sees a dequeued-but-
                                        // unverified bare as drainable-nothing.
                                        outstandingBare.incrementAndGet()
                                        emit(spec)
                                        return@forEach
                                    }
                                    try {
                                        budget.request(
                                            record.ekey,
                                            record.encodedSize,
                                            // A dead verifier unblocks a budget
                                            // wait immediately, and a registered
                                            // prefix key with a frozen
                                            // staged-copy location never waits
                                            // for a group ack when an
                                            // individual proof can free it: the
                                            // verifier may still be a whole slow
                                            // group away while the tail could
                                            // already download. The wrapper
                                            // below rethrows the verifier's
                                            // error as primary.
                                            requestCancelled,
                                            {
                                                submittedUncommitted.get() > 0 || !queue.isEmpty() ||
                                                    (outstandingBare.get() > 0 && overlap?.hasProvableRaw() != true)
                                            },
                                        ) {
                                        // Registered prefix keys first: an
                                        // individual full-strength proof frees
                                        // budget without waiting for the
                                        // verifier. The atomic claim means a
                                        // concurrent group ack can never
                                        // double-retire. Its ACTUAL staged copy
                                        // (located by the freeze record
                                        // collected during the single load
                                        // pass, no checkpoint rescan) must pass
                                        // the full-strength reader proof with
                                        // path-vs-FD binding before the single
                                        // retire. Any mismatch (a replaced
                                        // same-size inode included) aborts the
                                        // run without retiring and without
                                        // falling back. Unregistered far-tail
                                        // victims keep the legacy raw-only
                                        // retirement below.
                                        val claimed = overlap?.tryClaimOne()
                                        if (claimed != null) {
                                            val factory = replayReaderFactory
                                                ?: throw IllegalStateException("overlap evict proof requires a reader factory")
                                            val staged = overlap?.rawKeyRecord(claimed)
                                                ?: throw IllegalStateException("overlap freeze records unavailable before staged-copy proof")
                                            val victimRecord = catalog.objectByKey(claimed)
                                            val victimSpec = ForeverCasc.IncrementalObjectSpec(
                                                victimRecord.ekey,
                                                victimRecord.contentKey,
                                                victimRecord.encodedSize,
                                                victimRecord.decodedSize,
                                            )
                                            val proven = ForeverCasc.verifyStagedCopyForRawRetire(
                                                paths.cascDataFiles,
                                                staged,
                                                victimSpec,
                                                factory,
                                                workerCancelled,
                                            )
                                            if (!proven) {
                                                throw IllegalStateException("private updater checkpointed raw cannot prove its staged copy: $claimed")
                                            }
                                            evictable.remove(claimed)
                                            retireOwnedLeftoverRaw(paths.objects, budget, claimed)
                                            outstandingBare.decrementAndGet()
                                            true
                                        } else {
                                            // Unregistered far-tail victims
                                            // (never checkpointed, never in a
                                            // group) keep the legacy raw-only
                                            // retirement: their bytes were never
                                            // incorporated, so no staged-copy
                                            // proof exists to run.
                                            val victim = evictable.lastOrNull()
                                            if (victim == null) {
                                                false
                                            } else {
                                                evictable.remove(victim)
                                                retireOwnedLeftoverRaw(paths.objects, budget, victim)
                                                true
                                            }
                                        }
                                    }
                                    } catch (cancelledFailure: CancellationException) {
                                        if (!workerCancelled()) overlap?.failure?.get()?.let { throw it }
                                        throw cancelledFailure
                                    }
                                    if (tearingDown.get()) throw CancellationException("client update was paused or cancelled")
                                    val entryBytes = AtomicLong(0L)
                                    val future = executor.submit<File> {
                                        worker(record, entryBytes)
                                    }
                                    submittedUncommitted.incrementAndGet()
                                    emit(Prefetched(spec, record, entryBytes, future))
                                }
                                if (stagedNext != null) throw IllegalStateException("resumable CASC checkpoint contains objects outside the target cursor")
                            }
                            break
                        } catch (cancelledFailure: CancellationException) {
                            throw cancelledFailure
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw interrupted
                        } catch (failure: IllegalStateException) {
                            if (checkpointOpened) throw failure
                            checkCancelled(workerCancelled)
                            if (tearingDown.get()) throw CancellationException("client update was paused or cancelled")
                            openAttempts++
                            if (openAttempts > MAX_CHECKPOINT_OPEN_ATTEMPTS) throw failure
                            Thread.sleep(100)
                        }
                    }
                } catch (crash: Throwable) {
                    // First failure wins: a teardown interrupt must not mask the
                    // original error, and the consumer rethrows it after the end
                    // marker instead of truncating the manifest silently.
                    producerCrash.compareAndSet(null, crash)
                } finally {
                    runCatching {
                        while (!queue.offer(PrefetchEnd, 250, TimeUnit.MILLISECONDS)) {
                            // A slow final commit must not lose the end marker.
                            // Teardown interrupts this wait and joins the producer.
                            if (tearingDown.get() || Thread.currentThread().isInterrupted) break
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            fun nextEntry(): Any {
                var idleChecks = 0
                while (true) {
                    queue.poll(2, TimeUnit.SECONDS)?.let { return it }
                    producerCrash.get()?.let { throw it }
                    checkCancelled(cancelled)
                    if (!producer.isAlive) {
                        idleChecks++
                        if (idleChecks > 8) throw IllegalStateException("fetch producer exited without terminating the pipeline")
                    }
                }
            }
            var activeEntry: Prefetched? = null
            var sourceRevalidateNanos = 0L
            var verifiedSourceWaitNanos = 0L
            var resolvedSources = 0L
            val sourceDiagnostics = Logger.getLogger("ForeverClientUpdate")
            try {
                val specs = sequence {
                    while (true) {
                        when (val next = nextEntry()) {
                            is Prefetched -> {
                                pending[next.spec.ekey] = next
                                yield(next.spec)
                            }
                            // Checkpoint-committed specs travel bare: the writer
                            // consumes them from its staged output without a fetch.
                            // Only inline here — never in teardown, so an
                            // abandoned run preserves its raws.
                            // Serial mode: the coroutine resumes for the next
                            // spec only after the writer re-verified this staged
                            // copy (EKey/BLTE/content-key/size) and continued,
                            // which makes its leftover private raws provably
                            // redundant. Decrement only after a successful
                            // retire: while this bare is dequeued and hashing
                            // in CASC, the counter keeps producer waits valid
                            // instead of failing on a merely idled queue.
                            // Overlap mode: the staged copy is still
                            // UNVERIFIED here (the verifier proves it in
                            // group order), so no bare-yield retirement. The
                            // verifier's group ack retires registered leftover
                            // raws and decrements instead.
                            is ForeverCasc.IncrementalObjectSpec -> {
                                yield(next)
                                if (overlap == null) {
                                    retireOwnedLeftoverRaw(paths.objects, budget, next.ekey)
                                    outstandingBare.decrementAndGet()
                                }
                            }
                            PrefetchEnd -> break
                            else -> throw IllegalStateException("unknown fetch pipeline entry")
                        }
                    }
                    // The end marker alone is not success: a producer crash
                    // enqueues it from its finally block, so rethrow the cause
                    // instead of committing a truncated manifest.
                    producerCrash.get()?.let { throw it }
                }
                ForeverCasc.writeTargetIncrementalResumable(
                    null,
                    paths.cascDataFiles,
                    paths.root,
                    specs,
                    { spec ->
                        checkCancelled(cancelled)
                        // Per-object, before any destination access or write: the
                        // storage identity is re-checked for every object, never
                        // throttled to an interval and never deferred to commit.
                        val revalidateStarted = System.nanoTime()
                        destination.revalidate()
                        sourceRevalidateNanos += System.nanoTime() - revalidateStarted
                        val entry = pending.remove(spec.ekey)
                            ?: throw IllegalStateException("CASC writer requested an object outside the fetch pipeline")
                        activeEntry = entry
                        val waitStarted = System.nanoTime()
                        val verified = try {
                            if (overlap != null) {
                                // Overlap: bound the fetch wait so a dead
                                // verifier unblocks the writer promptly instead
                                // of parking on the future while the producer
                                // already propagated the failure. The old path
                                // keeps the single blocking get.
                                var resolved: File? = null
                                while (resolved == null) {
                                    try {
                                        resolved = entry.future.get(2, TimeUnit.SECONDS)
                                    } catch (timeout: java.util.concurrent.TimeoutException) {
                                        overlap?.failure?.get()?.let { throw it }
                                        checkCancelled(cancelled)
                                    }
                                }
                                resolved
                            } else {
                                entry.future.get()
                            }
                        } catch (failure: ExecutionException) {
                            throw (failure.cause ?: failure)
                        }
                        verifiedSourceWaitNanos += System.nanoTime() - waitStarted
                        resolvedSources++
                        // Diagnostic only: both phases previously shared the
                        // CASC sourceResolve timer. The writer owns these sums.
                        if ((resolvedSources and 0xffL) == 0L) {
                            sourceDiagnostics.info(
                                "CASC source timing resolved=$resolvedSources " +
                                    "revalidateMs=${sourceRevalidateNanos / 1_000_000} " +
                                    "verifiedWaitMs=${verifiedSourceWaitNanos / 1_000_000}",
                            )
                        }
                        verified
                    },
                    cancelled,
                    cascCheckpointIdentity(destination, plan.sourceRevision, paths.stageReceipt),
                    { spec, raw ->
                        val entry = requireNotNull(activeEntry) { "verified encoded transfer committed without an active fetch" }
                        activeEntry = null
                        if (Files.isSymbolicLink(raw.toPath()) || !Files.isRegularFile(raw.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            throw IllegalStateException("verified encoded transfer is not a regular private-work file")
                        }
                        synchronized(progressLock) {
                            transferred.addAndGet(entry.fetchedBytes.get())
                            committedObjects.incrementAndGet()
                            inFlight.remove(spec.ekey)
                            reportLocked()
                        }
                        Files.delete(raw.toPath())
                        // Make the unlink durable before freeing its budget:
                        // only this private cache directory is forced — Data,
                        // checkpoint and journal cadence is unchanged. A
                        // failed force propagates without releasing, so the
                        // next resume re-inventories instead of undercounting.
                        forceDirectory(paths.objects.toPath())
                        // The updater never deletes .part files outside the
                        // owned-leftover retirement: keep the reservation
                        // while a sibling raw of this key persists instead
                        // of freeing bytes for a file still on disk.
                        if (!Files.exists(File(paths.objects, "${spec.ekey}.part").toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            budget.release(spec.ekey)
                        }
                        submittedUncommitted.decrementAndGet()
                    },
                    replayReaderFactory = replayReaderFactory,
                    overlapCoordinator = overlap,
                    overlapOfficialStream = if (overlap != null) {
                        { verifierCancelled, consume ->
                            // Dedicated official cursor on the verifier thread:
                            // same ORDER BY ekey as the producer's cursor, so
                            // the verifier's join with the checkpoint prefix
                            // is positional with O(1) memory and no per-key
                            // query. Never a cursor shared with the producer.
                            catalog.withObjects(verifierCancelled) { records ->
                                consume(records.map { record ->
                                    ForeverCasc.IncrementalObjectSpec(
                                        record.ekey.lowercase(java.util.Locale.ROOT),
                                        record.contentKey.lowercase(java.util.Locale.ROOT),
                                        record.encodedSize,
                                        record.decodedSize,
                                    )
                                })
                            }
                        }
                    } else {
                        null
                    },
                )
            } finally {
                // Combined stop: no new submits or writes from here on, then join
                // every worker before returning. The wait is bounded by the socket
                // read timeout per stalled worker; a pause or failure is never
                // reported as complete while a worker can still write.
                tearingDown.set(true)
                producer.interrupt()
                executor.shutdownNow()
                try {
                    producer.join(30_000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                if (producer.isAlive) producer.join()
                var waitedMs = 0L
                while (!executor.isTerminated && waitedMs < TEARDOWN_WORKER_GRACE_MS) {
                    if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow()
                    waitedMs += 10_000L
                }
                if (!executor.isTerminated) {
                    executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
                }
                for (ekey in pending.keys.toList()) {
                    pending.remove(ekey)?.let { entry ->
                        if (!entry.future.isDone) entry.future.cancel(true)
                        // Completed-but-unconsumed verified outputs stay on disk:
                        // the private workspace accepts them as prefetch
                        // leftovers (up to its legacy bound) and the next run
                        // reuses them without spending consent again.
                    }
                }
                budget.reset()
            }
        }
    }

    private fun materializeLooseFiles(
        destination: ForeverClientUpdateDestination,
        loose: LooseState,
        paths: WorkspacePaths,
        alreadyStaged: Set<String>,
        cancelled: () -> Boolean,
        progress: (ForeverClientUpdateProgress) -> Unit,
        totalTransferBytes: Long,
    ) {
        var transferred = 0L
        loose.changes.forEachIndexed { index, item ->
            checkCancelled(cancelled)
            // Per-object storage revalidation before any destination access or
            // write, matching the CASC object path.
            destination.revalidate()
            if (item.path in alreadyStaged) {
                progress(ForeverClientUpdateProgress(ForeverClientUpdateProgress.Phase.STAGING, index + 1L, loose.changes.size.toLong(), totalTransferBytes, totalTransferBytes))
                return@forEachIndexed
            }
            var objectNetworkBytes = 0L
            val part = File(paths.objects, "${item.record.ekey}.part")
            val resumeOffset = if (Files.isRegularFile(part.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                Files.size(part.toPath()).takeIf { it in 1 until item.record.encodedSize } ?: 0L
            } else 0L
            val raw = fetchObject(item.record, paths.objects, cancelled) { bytes ->
                objectNetworkBytes = maxOf(objectNetworkBytes, (bytes - resumeOffset).coerceAtLeast(0L))
                progress(ForeverClientUpdateProgress(ForeverClientUpdateProgress.Phase.DOWNLOADING, index.toLong(), loose.changes.size.toLong(), Math.addExact(transferred, objectNetworkBytes), totalTransferBytes))
            }
            val staged = safeRelativeFile(paths.loose, item.path)
            ensureDirectoryTree(requireNotNull(staged.parentFile).toPath())
            ForeverBlte.decode(raw, staged, item.record.decodedSize, item.record.contentKey, cancelled)
            if (staged.length() != item.record.decodedSize) throw IllegalStateException("decoded loose file size differs from admitted content")
            Files.delete(raw.toPath())
            transferred = Math.addExact(transferred, objectNetworkBytes)
            progress(ForeverClientUpdateProgress(ForeverClientUpdateProgress.Phase.STAGING, index + 1L, loose.changes.size.toLong(), transferred, totalTransferBytes))
        }
    }

    private fun inspectLooseFiles(destination: ForeverClientUpdateDestination): LooseState {
        val looseFiles = catalog.looseFiles()
        val loosePaths = looseFiles.map { it.path }
        val allowedOld = destination.verifiedOwnedFiles.keys
        val allowed = loosePaths.toSet() + setOf(BUILD_INFO_PATH, STORAGE_MARKER_PATH)
        if (!allowedOld.all { it in allowed }) throw IllegalArgumentException("ownership allowlist contains a path outside the admitted marker/loose catalog")
        val changes = ArrayList<LooseTarget>(looseFiles.size)
        val observed = LinkedHashMap<String, FileFingerprint?>(looseFiles.size)
        var changedBytes = 0L
        var backupBytes = 0L
        looseFiles.forEach { loose ->
            val target = safeRelativeFile(destination.root, loose.path)
            rejectSymlinkPath(target.toPath())
            val current = existingFingerprint(target)
            observed[loose.path] = current
            val matches = if (current == null) false else {
                current.size == loose.objectRecord.decodedSize && md5File(target) == loose.objectRecord.contentKey
            }
            val old = if (current == null) {
                if (destination.verifiedOwnedFiles.containsKey(loose.path)) {
                    throw IllegalStateException("ownership allowlist describes an absent loose file: ${loose.path}")
                }
                null
            } else if (matches) {
                destination.verifiedOwnedFiles[loose.path]?.let { expected ->
                    if (expected != current) throw IllegalStateException("verified loose-file ownership fingerprint changed: ${loose.path}")
                }
                current
            } else {
                val admitted = destination.verifiedOwnedFiles[loose.path]
                if (admitted == null || admitted != current) {
                    throw IllegalStateException("loose-file collision is not explicitly admitted for replacement: ${loose.path}")
                }
                current
            }
            val needsChange = !matches
            if (needsChange) {
                changedBytes = Math.addExact(changedBytes, loose.objectRecord.decodedSize)
                backupBytes = Math.addExact(backupBytes, old?.size ?: 0L)
            }
            changes += LooseTarget(loose.path, loose.objectRecord, old, needsChange)
        }
        return LooseState(loosePaths, changes, changes.filter { it.needsChange }, changedBytes, backupBytes, observed)
    }

    private data class BuildState(val fingerprint: FileFingerprint?, val serializedHash: String)

    private fun inspectBuildInfo(
        destination: ForeverClientUpdateDestination,
        bytes: ByteArray,
        emptyRoot: Boolean,
        sourceOrigin: ForeverClientSourceOrigin,
    ): BuildState {
        val file = File(destination.root, BUILD_INFO_PATH)
        val current = existingFingerprint(file)
        val admitted = destination.verifiedOwnedFiles[BUILD_INFO_PATH]
        if (current == null) {
            if (admitted != null || (!emptyRoot && sourceOrigin != ForeverClientSourceOrigin.PASTED_SOURCE)) {
                throw IllegalStateException("existing client lacks an admitted .build.info ownership fingerprint")
            }
        } else if ((admitted == null || admitted != current) && sourceOrigin != ForeverClientSourceOrigin.PASTED_SOURCE) {
            throw IllegalStateException("existing .build.info is not bound to an explicitly verified prior client build")
        }
        return BuildState(current, sha256(bytes))
    }

    private fun inspectTargetState(destination: ForeverClientUpdateDestination): TargetState {
        val root = destination.root.toPath().toAbsolutePath().normalize()
        requireSafeAncestors(root)
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw IllegalArgumentException("client destination is not an existing regular directory")
        val markerFingerprint = verifyStorageMarker(destination)
        val data = root.resolve("Data")
        val dataFiles = data.resolve("data")
        if (Files.exists(data, LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(data) || !Files.isDirectory(data, LinkOption.NOFOLLOW_LINKS))) {
            throw IllegalStateException("client Data path is not a regular directory")
        }
        if (Files.exists(dataFiles, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(dataFiles) || !Files.isDirectory(dataFiles, LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("client CASC Data/data path is not a regular directory")
            }
            return TargetState(dataFiles.toFile(), sourceRevision(dataFiles.toFile()), false, markerFingerprint, destination.sourceOrigin)
        }
        Files.newDirectoryStream(root).use { children ->
            for (child in children) {
                if (child == root.resolve(STORAGE_MARKER_PATH) && markerFingerprint != null) continue
                if (child == root.resolve(TRANSACTION_STAGING_NAME) && !hasPendingTransaction(destination)) continue
                throw IllegalStateException("nonempty destination without a complete CASC source cannot be bootstrapped by the updater")
            }
        }
        return TargetState(null, EMPTY_SOURCE_REVISION, true, markerFingerprint, destination.sourceOrigin)
    }

    private fun verifyStorageMarker(destination: ForeverClientUpdateDestination): FileFingerprint? {
        val marker = File(destination.root, STORAGE_MARKER_PATH)
        val path = marker.toPath()
        rejectSymlinkPath(path)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (destination.verifiedOwnedFiles.containsKey(STORAGE_MARKER_PATH)) {
                throw IllegalStateException("storage ownership allowlist describes an absent root marker")
            }
            return null
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) !in 1..MAX_STORAGE_MARKER_BYTES.toLong()) {
            throw IllegalStateException("client storage ownership marker is not a small regular no-follow file")
        }
        val storageIdentity = destination.storageMarkerIdentity
            ?: throw IllegalStateException("storage marker exists but the trusted storage identity was not supplied")
        require(storageIdentity.matches(STORAGE_ID_PATTERN)) { "trusted storage marker identity is malformed" }
        val expected = "$STORAGE_MARKER_VERSION\n$storageIdentity\n".toByteArray(Charsets.UTF_8)
        if (!Files.readAllBytes(path).contentEquals(expected)) {
            throw IllegalStateException("client storage marker has an unexpected ownership prefix/identity")
        }
        val actual = FileFingerprint(Files.size(path), sha256File(marker))
        val admitted = destination.verifiedOwnedFiles[STORAGE_MARKER_PATH]
            ?: throw IllegalStateException("client storage marker fingerprint is absent from the trusted ownership allowlist")
        if (actual != admitted) throw IllegalStateException("client storage marker differs from its trusted fingerprint")
        return actual
    }

    private fun sourceRevision(dataFiles: File): String {
        val entries = ArrayList<String>()
        entries += "target=$targetBinding"
        Files.newDirectoryStream(dataFiles.toPath()).use { children ->
            children.forEach { path ->
                if (Files.isSymbolicLink(path)) throw IllegalStateException("client CASC source contains a symlink")
                val name = path.fileName.toString()
                val segment = SEGMENT_PATTERN.matchEntire(name)
                val isIndex = INDEX_PATTERN.matches(name)
                if (segment == null && !isIndex) return@forEach
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("client CASC source file is not regular")
                val attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                val indexHash = if (isIndex) sha256File(path.toFile()) else "-"
                val prefixHash = if (segment != null) sha256Prefix(path.toFile(), CASC_SEGMENT_HEADER_BYTES) else "-"
                entries += listOf(
                    name,
                    attrs.size().toString(),
                    attrs.lastModifiedTime().toMillis().toString(),
                    attrs.fileKey()?.toString().orEmpty(),
                    indexHash,
                    prefixHash,
                ).joinToString("|")
            }
        }
        entries.sort()
        return sha256(entries.joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    private fun validateConfigAssets(): List<ConfigState> {
        val summary = targetSummary
        val expected = setOf(summary.buildConfigHash, summary.cdnConfigHash)
        require(configFiles.keys == expected) { "packaged TACT config assets do not match the admitted catalog summary" }
        return configFiles.toSortedMap().map { (hash, source) ->
            require(hash.matches(CONFIG_HASH_PATTERN)) { "catalog TACT config hash is malformed" }
            val path = source.toPath().toAbsolutePath().normalize()
            rejectSymlinkPath(path)
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("packaged TACT config is not a regular file")
            val size = Files.size(path)
            require(size in 1..MAX_CONFIG_BYTES) { "packaged TACT config exceeds the small-asset bound" }
            val md5 = md5File(source)
            if (!md5.equals(hash, true)) throw IllegalStateException("packaged TACT config content does not match its catalog hash")
            ConfigState(hash, source, size, sha256File(source))
        }
    }

    private fun buildInfoBytes(): ByteArray {
        val summary = targetSummary
        val installKey = installEncodingKey()
        val fields = JSONObject()
            .put("branch", "us")
            .put("build_key", summary.buildConfigHash)
            .put("cdn_key", summary.cdnConfigHash)
            .put("install_key", installKey)
            .put("cdn_path", "tpr/wow")
            .put("cdn_hosts", JSONArray().put("level3.blizzard.com").put("us.cdn.blizzard.com"))
            .put("version", summary.version)
            .put("product", PRODUCT)
            .put("platform", "Windows")
            .put("architecture", "arm64")
            .put("locale", "enUS")
            .put("region", "us")
        return ForeverCasc.buildInfo(fields)
    }

    private fun installEncodingKey(): String {
        val metadata = JSONObject(catalog.metadata("metadata_objects"))
        val key = metadata.getJSONObject("install").getString("encoding_key").lowercase(Locale.ROOT)
        val objectRecord = catalog.objectByKey(key)
        if (objectRecord.ekey != key) throw IllegalStateException("install metadata object does not bind to its encoded EKey")
        return key
    }

    private fun prepareWorkspace(
        destination: ForeverClientUpdateDestination,
        plan: ForeverClientUpdatePlan,
        transaction: ForeverClientUpdateTransaction,
    ): WorkspacePaths {
        val root = resumableWorkspacePath(destination)
        val ownerBytes = workspaceOwnerBytes(destination, plan.sourceRevision)
        ensureDirectoryTree(workBase.toPath())
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(root.toPath())
            FileChannel.open(
                File(root, WORKSPACE_OWNER_NAME).toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                writeFully(channel, ByteBuffer.wrap(ownerBytes))
                channel.force(true)
            }
            forceDirectory(root.toPath())
            forceDirectory(requireNotNull(root.parentFile).toPath())
        } else {
            requireExactFile(File(root, WORKSPACE_OWNER_NAME).toPath(), ownerBytes, "client updater workspace marker")
        }
        val objectDir = File(root, "objects")
        val looseDir = File(root, "loose")
        listOf(objectDir, looseDir).forEach { ensureDirectoryTree(it.toPath()) }
        var receipt = readStageReceipt(destination)?.also {
            require(it.sourceRevision == plan.sourceRevision && it.snapshotHash == plan.snapshotHash) {
                "resumable transaction stage belongs to a different source snapshot; manual review is required"
            }
        } ?: run {
            val stageId = UUID.randomUUID().toString()
            val markerHash = transaction.stagingMarkerHash(stageId)
            UpdateStageReceipt(
                stageId,
                markerHash,
                plan.sourceRevision,
                plan.snapshotHash,
                destination.storageId,
                destination.root.toPath().toAbsolutePath().normalize().toString(),
            ).also { persistStageReceipt(destination, root, it) }
        }
        val stagePath = transactionStageDirectory(destination, receipt)
        val stageOwner = File(stagePath, TRANSACTION_STAGE_OWNER_NAME)
        var stage = if (Files.exists(stagePath.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            Files.exists(stageOwner.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            transaction.resumeStaging(receipt.stageId, receipt.markerHash)
        } else {
            transaction.createStaging(receipt.stageId, receipt.markerHash)
        }
        if (receipt.resetCasc) {
            resetCascAfterRollback(destination, receipt)
            receipt = readStageReceipt(destination) ?: throw IllegalStateException("rollback reset discarded its transaction-stage receipt")
            stage = transaction.resumeStaging(receipt.stageId, receipt.markerHash)
        }
        val cascData = File(stage.directory, "Data/data")
        ensureDirectoryTree(File(stage.directory, "Data").toPath())
        return WorkspacePaths(root, ownerBytes, objectDir, receipt, stage, cascData, looseDir)
    }

    private fun stageCascFiles(
        cascDataFiles: File,
        inputs: MutableList<InputFile>,
        cancelled: () -> Boolean,
        progress: (ForeverClientUpdateProgress) -> Unit,
    ) {
        val seenBuckets = HashSet<Int>()
        var count = 0
        Files.newDirectoryStream(cascDataFiles.toPath()).use { children ->
            children.forEach { source ->
                checkCancelled(cancelled)
                val name = source.fileName.toString()
                if (Files.isSymbolicLink(source)) throw IllegalStateException("CASC output stage contains a symlink")
                val segment = SEGMENT_PATTERN.matchEntire(name)
                val index = INDEX_PATTERN.matchEntire(name)
                if (segment == null && index == null) {
                    throw IllegalStateException("CASC writer produced an unrecognized output file")
                }
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("CASC writer output is not a regular file")
                if (index != null) {
                    val bucket = name.substring(0, 2).toInt(16)
                    if (!seenBuckets.add(bucket)) throw IllegalStateException("CASC writer produced duplicate bucket indices")
                }
                val target = "Data/data/$name"
                inputs += inputFile(target, source.toFile(), null)
                count++
            }
        }
        if (seenBuckets != (0..15).toSet()) throw IllegalStateException("CASC writer did not produce all sixteen target index buckets")
        if (count < 16 || count > MAX_CASC_PUBLISHED_FILES) throw IllegalStateException("CASC writer output count is outside transaction bounds")
        progress(ForeverClientUpdateProgress(ForeverClientUpdateProgress.Phase.STAGING, 0, 0, 0, 0))
    }

    private fun stageConfigs(
        destination: ForeverClientUpdateDestination,
        configs: List<ConfigState>,
        staging: UpdateStagingArea,
        inputs: MutableList<InputFile>,
        cancelled: () -> Boolean,
    ) {
        configs.forEach { config ->
            checkCancelled(cancelled)
            val target = configTarget(destination.root, config.hash)
            val existing = existingFingerprint(target)
            if (existing != null) {
                if (!configMatches(target, config.hash)) throw IllegalStateException("existing content-addressed CASC config is not valid")
                return@forEach
            }
            val relative = configRelativePath(config.hash)
            val staged = transactionStageFile(staging, relative)
            copyOrVerifyStageFile(config.source, staged, cancelled)
            if (sha256File(staged) != config.sha256 || staged.length() != config.size) {
                throw IllegalStateException("staged TACT config changed after read-only planning")
            }
            inputs += inputFile(relative, staged, null)
        }
    }

    private fun stageLooseFiles(
        loose: LooseState,
        paths: WorkspacePaths,
        staging: UpdateStagingArea,
        inputs: MutableList<InputFile>,
        cancelled: () -> Boolean,
    ) {
        loose.changes.forEach { item ->
            checkCancelled(cancelled)
            val source = safeRelativeFile(paths.loose, item.path)
            if (Files.isSymbolicLink(source.toPath()) || !Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                source.length() != item.record.decodedSize || md5File(source) != item.record.contentKey
            ) throw IllegalStateException("staged loose file failed BLTE content-key/size validation")
            val staged = transactionStageFile(staging, item.path)
            copyOrVerifyStageFile(source, staged, cancelled)
            inputs += inputFile(item.path, staged, item.existing)
        }
    }

    /**
     * Fresh two-pass finalization: stage descriptors without hashing the CASC
     * payloads here. The single admission hash runs inside
     * [ForeverClientUpdateTransaction.planFresh]; the second and final hash
     * reads the published targets back. Official EKey/BLTE/MD5 checks stay.
     */
    private fun executeFresh(
        destination: ForeverClientUpdateDestination,
        plan: ForeverClientUpdatePlan,
        paths: WorkspacePaths,
        transaction: ForeverClientUpdateTransaction,
        loose: LooseState,
        configs: List<ConfigState>,
        buildInfo: ByteArray,
        cancelled: () -> Boolean,
        progress: (ForeverClientUpdateProgress) -> Unit,
    ) {
        val descriptors = ArrayList<FreshStagedDescriptor>(plan.transactionFileCount)
        stageCascFilesFresh(paths.cascDataFiles, descriptors, cancelled)
        stageConfigsFresh(destination, configs, paths.staging, descriptors, cancelled)
        stageLooseFilesFresh(loose, paths, paths.staging, descriptors, cancelled)
        val buildInfoStage = transactionStageFile(paths.staging, BUILD_INFO_PATH)
        copyBytesOrVerifyStage(buildInfo, buildInfoStage, cancelled)
        descriptors += FreshStagedDescriptor(BUILD_INFO_PATH, buildInfoStage, null)
        validateFreshStageInputs(paths.staging, descriptors)
        transaction.finalizationProgress = progress
        try {
            val transactionPlan = transaction.planFresh(descriptors, loose.catalogPaths.toSet(), paths.staging, cancelled)
            destination.revalidate()
            progress(ForeverClientUpdateProgress(ForeverClientUpdateProgress.Phase.APPLYING, plan.totalObjects, plan.totalObjects, plan.downloadBytes, plan.downloadBytes))
            transaction.commit(transactionPlan)
        } finally {
            transaction.finalizationProgress = {}
        }
    }

    private fun stageCascFilesFresh(
        cascDataFiles: File,
        descriptors: MutableList<FreshStagedDescriptor>,
        cancelled: () -> Boolean,
    ) {
        val seenBuckets = HashSet<Int>()
        var count = 0
        Files.newDirectoryStream(cascDataFiles.toPath()).use { children ->
            children.forEach { source ->
                checkCancelled(cancelled)
                val name = source.fileName.toString()
                if (Files.isSymbolicLink(source)) throw IllegalStateException("CASC output stage contains a symlink")
                val segment = SEGMENT_PATTERN.matchEntire(name)
                val index = INDEX_PATTERN.matchEntire(name)
                if (segment == null && index == null) {
                    throw IllegalStateException("CASC writer produced an unrecognized output file")
                }
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("CASC writer output is not a regular file")
                if (index != null) {
                    val bucket = name.substring(0, 2).toInt(16)
                    if (!seenBuckets.add(bucket)) throw IllegalStateException("CASC writer produced duplicate bucket indices")
                }
                descriptors += FreshStagedDescriptor("Data/data/$name", source.toFile(), null)
                count++
            }
        }
        if (seenBuckets != (0..15).toSet()) throw IllegalStateException("CASC writer did not produce all sixteen target index buckets")
        if (count < 16 || count > MAX_CASC_PUBLISHED_FILES) throw IllegalStateException("CASC writer output count is outside transaction bounds")
    }

    private fun stageConfigsFresh(
        destination: ForeverClientUpdateDestination,
        configs: List<ConfigState>,
        staging: UpdateStagingArea,
        descriptors: MutableList<FreshStagedDescriptor>,
        cancelled: () -> Boolean,
    ) {
        configs.forEach { config ->
            checkCancelled(cancelled)
            val target = configTarget(destination.root, config.hash)
            if (existingFingerprint(target) != null) {
                throw IllegalStateException("fresh finalization encountered an existing CASC config; use the strict replacement path")
            }
            val relative = configRelativePath(config.hash)
            val staged = transactionStageFile(staging, relative)
            copyOrVerifyStageFile(config.source, staged, cancelled)
            if (sha256File(staged) != config.sha256 || staged.length() != config.size) {
                throw IllegalStateException("staged TACT config changed after read-only planning")
            }
            descriptors += FreshStagedDescriptor(relative, staged, null)
        }
    }

    private fun stageLooseFilesFresh(
        loose: LooseState,
        paths: WorkspacePaths,
        staging: UpdateStagingArea,
        descriptors: MutableList<FreshStagedDescriptor>,
        cancelled: () -> Boolean,
    ) {
        loose.changes.forEach { item ->
            checkCancelled(cancelled)
            if (item.existing != null) {
                throw IllegalStateException("fresh finalization encountered an existing loose file; use the strict replacement path: ${item.path}")
            }
            val source = safeRelativeFile(paths.loose, item.path)
            if (Files.isSymbolicLink(source.toPath()) || !Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                source.length() != item.record.decodedSize || md5File(source) != item.record.contentKey
            ) throw IllegalStateException("staged loose file failed BLTE content-key/size validation")
            val staged = transactionStageFile(staging, item.path)
            copyOrVerifyStageFile(source, staged, cancelled)
            descriptors += FreshStagedDescriptor(item.path, staged, null)
        }
    }

    private fun validateFreshStageInputs(staging: UpdateStagingArea, descriptors: List<FreshStagedDescriptor>) {
        val stageRoot = staging.directory.toPath().toAbsolutePath().normalize()
        val expectedFlat = descriptors.mapNotNull { descriptor ->
            val path = descriptor.stagedFile.toPath().toAbsolutePath().normalize()
            if (path.parent == stageRoot) path else null
        }.toSet()
        val expectedCasc = descriptors.filter { it.relativePath.startsWith("Data/data/") }
            .map { it.relativePath.substringAfter("Data/data/") }.toSet()
        Files.newDirectoryStream(stageRoot).use { children ->
            children.forEach { child ->
                if (Files.isSymbolicLink(child)) throw IllegalStateException("transaction stage contains a symlink")
                when {
                    child.fileName.toString() == TRANSACTION_STAGE_OWNER_NAME -> Unit
                    child.fileName.toString() == "Data" -> {
                        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("transaction CASC stage root is not a directory")
                        val data = child.resolve("data")
                        if (Files.isSymbolicLink(data) || !Files.isDirectory(data, LinkOption.NOFOLLOW_LINKS)) {
                            throw IllegalStateException("transaction CASC data staging path is missing or unsafe")
                        }
                        Files.newDirectoryStream(data).use { cascFiles ->
                            cascFiles.forEach { cascFile ->
                                if (Files.isSymbolicLink(cascFile) || !Files.isRegularFile(cascFile, LinkOption.NOFOLLOW_LINKS) ||
                                    cascFile.fileName.toString() !in expectedCasc
                                ) throw IllegalStateException("transaction CASC stage contains an unplanned output")
                            }
                        }
                    }
                    child in expectedFlat -> {
                        if (!Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("transaction staged input is not regular")
                    }
                    else -> throw IllegalStateException("transaction stage contains an unplanned path: ${child.fileName}")
                }
            }
        }
        if (expectedFlat.any { !Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }) {
            throw IllegalStateException("transaction stage is missing a planned loose/config/build-info file")
        }
        if (expectedCasc.isEmpty()) throw IllegalStateException("transaction stage has no planned CASC outputs")
    }

    private fun transaction(
        destination: ForeverClientUpdateDestination,
        leaseHeld: () -> Boolean = { true },
    ): ForeverClientUpdateTransaction = ForeverClientUpdateTransaction(
        destination.root,
        privateJournalDirectory,
        "$targetBinding|${destination.storageId}|${destination.root.toPath().toAbsolutePath().normalize()}",
        identityStillMatches = {
            try {
                destination.revalidate()
                verifyStorageMarker(destination)
                true
            } catch (_: Exception) {
                false
            }
        },
        mutationAllowed = {
            leaseHeld() && runCatching {
                destination.revalidate()
                verifyStorageMarker(destination)
            }.isSuccess
        },
    )

    private inline fun <T> withLease(
        destination: ForeverClientUpdateDestination,
        allowRootPending: Boolean = false,
        action: (leaseHeld: () -> Boolean) -> T,
    ): T {
        destination.revalidate()
        ensurePrivateDirectories()
        var held = true
        val lease = operationLease.acquireUpdate(destination.storageId) { hasPendingUpdateState(destination) }
        try {
            destination.revalidate()
            if (!allowRootPending && hasPendingTransaction(destination)) {
                throw IllegalStateException("recover the unfinished client transaction before planning or updating")
            }
            return action { held }
        } finally {
            held = false
            lease.close()
        }
    }

    private fun checkConsent(
        destination: ForeverClientUpdateDestination,
        plan: ForeverClientUpdatePlan,
        consent: ForeverClientUpdateConsent,
    ) {
        require(plan.updaterToken == updaterToken) { "update plan belongs to another updater instance" }
        require(plan.storageId == destination.storageId && plan.destinationPath == destination.root.toPath().toAbsolutePath().normalize().toString()) {
            "update plan is bound to a different frozen destination"
        }
        require(
            consent.planId == plan.planId && consent.downloadBytes >= plan.downloadBytes &&
                consent.peakAdditionalBytes >= plan.peakAdditionalBytes &&
                consent.privateWorkBytes >= plan.privateWorkBytes && consent.destinationBytes >= plan.destinationBytes,
        ) { "recorded download or storage consent does not cover this plan's remaining cost" }
    }

    private fun requireSamePlan(original: ForeverClientUpdatePlan, fresh: ForeverClientUpdatePlan) {
        if (original.snapshotHash != fresh.snapshotHash || original.sourceRevision != fresh.sourceRevision ||
            original.downloadBytes != fresh.downloadBytes || original.privateWorkBytes != fresh.privateWorkBytes ||
            original.destinationBytes != fresh.destinationBytes || original.totalObjects != fresh.totalObjects ||
            original.reusableObjects != fresh.reusableObjects || original.missingObjects != fresh.missingObjects ||
            original.looseChangePaths != fresh.looseChangePaths || original.transactionFileCount != fresh.transactionFileCount
        ) throw IllegalStateException("destination or admitted source changed after the displayed update plan; plan again")
    }

    private fun hasPendingUpdateState(destination: ForeverClientUpdateDestination): Boolean =
        hasPendingTransaction(destination) ||
            Files.exists(resumableWorkspacePath(destination).toPath(), LinkOption.NOFOLLOW_LINKS)

    /** Reject any root stage that is not the exact UUID/hash pair in private updater state. */
    private fun cleanupOrphanTransactionStaging(destination: ForeverClientUpdateDestination) {
        val base = File(destination.root, TRANSACTION_STAGING_NAME).toPath()
        if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(base) || !Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("reserved update staging directory is unsafe")
        }
        val receipt = readStageReceipt(destination)
        Files.newDirectoryStream(base).use { children ->
            children.forEach { child ->
                if (receipt == null || child.fileName.toString() != receipt.stageId) {
                    throw IllegalStateException("transaction staging contains state not named by its private checksum receipt")
                }
                val marker = child.resolve(TRANSACTION_STAGE_OWNER_NAME)
                if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                    requireExactFile(marker, transactionStageMarkerBytes(destination, receipt.stageId), "receipt-bound transaction-stage marker")
                } else {
                    Files.newDirectoryStream(child).use { contents ->
                        if (contents.iterator().hasNext()) throw IllegalStateException("unmarked transaction stage contains unknown data")
                    }
                }
            }
        }
        Files.newDirectoryStream(base).use { children ->
            if (!children.iterator().hasNext()) Files.delete(base)
        }
    }

    private fun cleanupCommittedResumeState(destination: ForeverClientUpdateDestination) {
        val receipt = readStageReceipt(destination)
        if (receipt != null && Files.exists(transactionStageDirectory(destination, receipt).toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("completed transaction left its receipt-bound stage directory behind")
        }
        val privateRoot = resumableWorkspacePath(destination)
        if (Files.exists(privateRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(privateRoot.toPath()) || !Files.isDirectory(privateRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("private updater workspace is unsafe after transaction recovery")
            }
            val owner = File(privateRoot, WORKSPACE_OWNER_NAME).toPath()
            if (Files.isSymbolicLink(owner) || !Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("private updater workspace has no ownership proof after transaction recovery")
            }
            val size = Files.size(owner)
            if (size !in 1..512) throw IllegalStateException("private updater workspace ownership record is malformed")
            val bytes = ByteArray(size.toInt())
            FileChannel.open(owner, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { readFully(it, ByteBuffer.wrap(bytes)) }
            val marker = String(bytes, Charsets.UTF_8)
            if (!Regex("^${WORKSPACE_MARKER}\\n[0-9a-f]{64}\\n$").matches(marker)) {
                throw IllegalStateException("private updater workspace ownership record is malformed")
            }
            cleanupWorkspace(privateRoot, bytes)
        }
    }

    private fun resetCascAfterRollback(destination: ForeverClientUpdateDestination, original: UpdateStageReceipt) {
        var receipt = readStageReceipt(destination) ?: original
        if (receipt.stageId != original.stageId || receipt.markerHash != original.markerHash) {
            throw IllegalStateException("rollback reset receipt no longer identifies the same transaction stage")
        }
        if (!receipt.resetCasc) {
            receipt = receipt.copy(resetCasc = true)
            persistStageReceipt(destination, resumableWorkspacePath(destination), receipt)
        }
        val transaction = transaction(destination) { true }
        val stagePath = transactionStageDirectory(destination, receipt)
        val stageOwner = File(stagePath, TRANSACTION_STAGE_OWNER_NAME)
        val stage = if (Files.exists(stagePath.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            Files.exists(stageOwner.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            transaction.resumeStaging(receipt.stageId, receipt.markerHash)
        } else {
            transaction.createStaging(receipt.stageId, receipt.markerHash)
        }
        val outputData = File(stage.directory, "Data/data")
        if (Files.exists(outputData.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            validateCascOutputForReset(outputData)
            if (!outputData.deleteRecursively()) throw IllegalStateException("could not reset rolled-back CASC staging output")
            forceDirectory(requireNotNull(outputData.parentFile).toPath())
        }
        ForeverCasc.resetResumableCheckpoint(
            resumableWorkspacePath(destination),
            cascCheckpointIdentity(destination, receipt.sourceRevision, receipt),
        )
        persistStageReceipt(destination, resumableWorkspacePath(destination), receipt.copy(resetCasc = false))
    }

    private fun validateCascOutputForReset(outputData: File) {
        if (Files.isSymbolicLink(outputData.toPath()) || !Files.isDirectory(outputData.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("rolled-back CASC output directory is unsafe")
        }
        Files.newDirectoryStream(outputData.toPath()).use { children ->
            children.forEach { path ->
                if (Files.isSymbolicLink(path)) throw IllegalStateException("rolled-back CASC output contains a symlink")
                val name = path.fileName.toString()
                when {
                    SEGMENT_PATTERN.matches(name) || INDEX_PATTERN.matches(name) || name == CASC_OBJECT_TEMP_NAME -> {
                        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("rolled-back CASC output file is unsafe")
                    }
                    name == ".forever-casc-index-work" -> {
                        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("rolled-back CASC scratch directory is unsafe")
                        Files.walk(path).use { scratch ->
                            scratch.forEach { if (Files.isSymbolicLink(it)) throw IllegalStateException("rolled-back CASC scratch contains a symlink") }
                        }
                    }
                    else -> throw IllegalStateException("rolled-back CASC output contains unowned state: $name")
                }
            }
        }
    }

    private fun ensureNoRootTransaction(destination: ForeverClientUpdateDestination) {
        if (hasPendingTransaction(destination)) throw IllegalStateException("recover the unfinished client transaction before proceeding")
    }

    private fun validateDestination(destination: ForeverClientUpdateDestination) {
        require(destination.storageId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "frozen client storage ID is invalid" }
        val root = destination.root.toPath().toAbsolutePath().normalize()
        requireSafeAncestors(root)
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw IllegalArgumentException("client destination must already be an existing app-owned directory")
        verifyStorageMarker(destination)
        val work = privateWorkDirectory.toPath().toAbsolutePath().normalize()
        val journal = privateJournalDirectory.toPath().toAbsolutePath().normalize()
        require(!work.startsWith(root) && !root.startsWith(work)) { "private transfer work directory must be disjoint from the client root" }
        require(!journal.startsWith(root) && !root.startsWith(journal)) { "private transaction journal must be disjoint from the client root" }
        require(!work.startsWith(journal) && !journal.startsWith(work)) { "private transfer work and transaction journal directories must be disjoint" }
    }

    private fun validatePrivateDirectories() {
        ensurePrivateDirectories()
        if (!Files.isDirectory(privateWorkDirectory.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            !Files.isDirectory(privateJournalDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) throw IllegalStateException("private work/journal directories are unavailable")
    }

    private fun ensurePrivateDirectories() {
        requireSafeAncestors(privateWorkDirectory.toPath().toAbsolutePath().normalize())
        requireSafeAncestors(privateJournalDirectory.toPath().toAbsolutePath().normalize())
        if (!Files.isDirectory(privateWorkDirectory.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            !Files.isDirectory(privateJournalDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) throw IllegalStateException("provide existing app-private no-backup work and journal directories")
    }

    private fun resumableWorkspacePath(destination: ForeverClientUpdateDestination): File {
        return File(workBase, workspaceKey(destination))
    }

    private fun cascCheckpointIdentity(
        destination: ForeverClientUpdateDestination,
        sourceRevision: String,
        receipt: UpdateStageReceipt,
    ): String = "$targetBinding|${destination.storageId}|" +
        "${destination.root.toPath().toAbsolutePath().normalize()}|$sourceRevision|${receipt.stageId}|${receipt.markerHash}"

    private fun workspaceKey(destination: ForeverClientUpdateDestination): String = sha256(
        "${destination.storageId}\n${destination.root.toPath().toAbsolutePath().normalize()}\n$targetBinding".toByteArray(Charsets.UTF_8),
    )


    private fun snapshotHash(
        sourceRevision: String,
        storageMarker: FileFingerprint?,
        storageMarkerIdentity: String?,
        loose: LooseState,
        build: BuildState,
        configs: List<ConfigState>,
        configObserved: Map<String, FileFingerprint?>,
        buildInfo: ByteArray,
    ): String {
        val canonical = buildString {
            append(targetBinding).append('\n')
            append(sourceRevision).append('\n')
            append(storageMarkerIdentity ?: "no-storage-marker").append('|')
                .append(storageMarker?.let { "${it.size}:${it.sha256}" } ?: "absent").append('\n')
            append(build.fingerprint?.let { "${it.size}:${it.sha256}" } ?: "absent").append('\n')
            append(build.serializedHash).append('\n')
            loose.catalogPaths.forEach { path ->
                val state = loose.observedFingerprints[path]
                append(path).append('|').append(state?.let { "${it.size}:${it.sha256}" } ?: "absent").append('\n')
            }
            configs.forEach {
                val current = configObserved[it.hash]
                append(it.hash).append('|').append(it.size).append('|').append(it.sha256).append('|')
                    .append(current?.let { fingerprint -> "${fingerprint.size}:${fingerprint.sha256}" } ?: "absent").append('\n')
            }
        }
        require(build.serializedHash == sha256(buildInfo)) { "build-info serialized fingerprint changed" }
        return sha256(canonical.toByteArray(Charsets.UTF_8))
    }

    private fun configTarget(root: File, hash: String): File = File(root, "Data/config/${hash.substring(0, 2)}/${hash.substring(2, 4)}/$hash")

    private fun configMatches(file: File, hash: String): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath()) && md5File(file).equals(hash, true)

    private fun inputFile(relative: String, stage: File, old: FileFingerprint?): InputFile =
        InputFile(relative, stage, Files.size(stage.toPath()), sha256File(stage), old)

    private fun transactionStageFile(staging: UpdateStagingArea, target: String): File =
        File(staging.directory, "file-${sha256(target.toByteArray(Charsets.UTF_8))}")

    private fun configRelativePath(hash: String): String =
        "Data/config/${hash.substring(0, 2)}/${hash.substring(2, 4)}/$hash"

    private fun inspectTransactionStageInputs(
        destination: ForeverClientUpdateDestination,
        receipt: UpdateStageReceipt?,
        expectedTargets: Set<String>,
    ): Long {
        if (receipt == null) return 0L
        val stage = transactionStageDirectory(destination, receipt)
        if (!Files.exists(stage.toPath(), LinkOption.NOFOLLOW_LINKS)) return 0L
        requireExactFile(File(stage, TRANSACTION_STAGE_OWNER_NAME).toPath(), transactionStageMarkerBytes(destination, receipt.stageId), "receipt-bound transaction-stage marker")
        val expectedFiles = expectedTargets.map { transactionStageFileName(it) }.toSet()
        var occupied = 0L
        Files.newDirectoryStream(stage.toPath()).use { children ->
            children.forEach { path ->
                if (Files.isSymbolicLink(path)) throw IllegalStateException("receipt-bound transaction stage contains a symlink")
                val name = path.fileName.toString()
                when {
                    name == TRANSACTION_STAGE_OWNER_NAME -> Unit
                    name == "Data" -> {
                        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("transaction CASC stage root is unsafe")
                        Files.newDirectoryStream(path).use { dataChildren ->
                            dataChildren.forEach { dataPath ->
                                if (Files.isSymbolicLink(dataPath) || dataPath.fileName.toString() != "data" ||
                                    !Files.isDirectory(dataPath, LinkOption.NOFOLLOW_LINKS)
                                ) throw IllegalStateException("transaction CASC stage has an unrecognized path")
                                Files.newDirectoryStream(dataPath).use { cascChildren ->
                                    cascChildren.forEach { cascPath ->
                                        if (Files.isSymbolicLink(cascPath)) throw IllegalStateException("transaction CASC data stage contains a symlink")
                                        val cascName = cascPath.fileName.toString()
                                        when {
                                            SEGMENT_PATTERN.matches(cascName) || INDEX_PATTERN.matches(cascName) -> {
                                                if (!Files.isRegularFile(cascPath, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("transaction CASC staged payload is not regular")
                                            }
                                            cascName == ".forever-casc-index-work" -> {
                                                if (!Files.isDirectory(cascPath, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("transaction CASC scratch is unsafe")
                                                Files.walk(cascPath).use { scratch ->
                                                    scratch.forEach { if (Files.isSymbolicLink(it)) throw IllegalStateException("transaction CASC scratch contains a symlink") }
                                                }
                                            }
                                            cascName == CASC_OBJECT_TEMP_NAME -> {
                                                if (!Files.isRegularFile(cascPath, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("transaction CASC object temporary is unsafe")
                                            }
                                            else -> throw IllegalStateException("transaction CASC data stage contains unowned output: $cascName")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    name in expectedFiles -> {
                        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("transaction staged input is unsafe")
                        occupied = Math.addExact(occupied, Files.size(path))
                    }
                    else -> throw IllegalStateException("transaction stage contains a file outside the current admitted plan: $name")
                }
            }
        }
        return occupied
    }

    private fun transactionStageFileName(target: String): String =
        "file-${sha256(target.toByteArray(Charsets.UTF_8))}"

    private fun validateStageInputs(staging: UpdateStagingArea, inputs: List<InputFile>) {
        val stageRoot = staging.directory.toPath().toAbsolutePath().normalize()
        val expectedFlat = inputs.mapNotNull { input ->
            val path = input.stagedFile.toPath().toAbsolutePath().normalize()
            if (path.parent == stageRoot) path else null
        }.toSet()
        val expectedCasc = inputs.filter { it.relativePath.startsWith("Data/data/") }
            .map { it.relativePath.substringAfter("Data/data/") }.toSet()
        Files.newDirectoryStream(stageRoot).use { children ->
            children.forEach { child ->
                if (Files.isSymbolicLink(child)) throw IllegalStateException("transaction stage contains a symlink")
                when {
                    child.fileName.toString() == TRANSACTION_STAGE_OWNER_NAME -> Unit
                    child.fileName.toString() == "Data" -> {
                        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("transaction CASC stage root is not a directory")
                        val data = child.resolve("data")
                        if (Files.isSymbolicLink(data) || !Files.isDirectory(data, LinkOption.NOFOLLOW_LINKS)) {
                            throw IllegalStateException("transaction CASC data staging path is missing or unsafe")
                        }
                        Files.newDirectoryStream(data).use { cascFiles ->
                            cascFiles.forEach { cascFile ->
                                if (Files.isSymbolicLink(cascFile) || !Files.isRegularFile(cascFile, LinkOption.NOFOLLOW_LINKS) ||
                                    cascFile.fileName.toString() !in expectedCasc
                                ) throw IllegalStateException("transaction CASC stage contains an unplanned output")
                            }
                        }
                    }
                    child in expectedFlat -> {
                        if (!Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("transaction staged input is not regular")
                    }
                    else -> throw IllegalStateException("transaction stage contains an unplanned path: ${child.fileName}")
                }
            }
        }
        if (expectedFlat.any { !Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }) {
            throw IllegalStateException("transaction stage is missing a planned loose/config/build-info file")
        }
        if (expectedCasc.isEmpty()) throw IllegalStateException("transaction stage has no planned CASC outputs")
    }

    private fun copyBytesOrVerifyStage(bytes: ByteArray, target: File, cancelled: () -> Boolean) {
        val expectedHash = sha256(bytes)
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(target.toPath()) || !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("transaction-owned staged file path is unsafe")
            }
            if (Files.size(target.toPath()) == bytes.size.toLong() && sha256File(target) == expectedHash) return
            Files.delete(target.toPath())
        }
        checkCancelled(cancelled)
        FileChannel.open(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            writeFully(channel, ByteBuffer.wrap(bytes))
            channel.force(true)
        }
    }

    private fun copyOrVerifyStageFile(source: File, target: File, cancelled: () -> Boolean) {
        if (Files.isSymbolicLink(source.toPath()) || !Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("stage-copy source is not a regular file")
        }
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(target.toPath()) || !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("transaction-owned staged path is unsafe")
            }
            if (Files.size(target.toPath()) == Files.size(source.toPath()) && sha256File(target) == sha256File(source)) return
            Files.delete(target.toPath())
        }
        copyFileFresh(source, target, cancelled)
    }

    private fun copyFileFresh(source: File, target: File, cancelled: () -> Boolean) {
        val sourcePath = source.toPath()
        rejectSymlinkPath(sourcePath)
        if (!Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("staging source is not a regular file")
        ensureDirectoryTree(requireNotNull(target.parentFile).toPath())
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target.toPath())) {
            throw IllegalStateException("fresh transaction staging path already exists")
        }
        FileChannel.open(sourcePath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            FileChannel.open(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
                val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
                while (true) {
                    checkCancelled(cancelled)
                    buffer.clear()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    buffer.flip()
                    writeFully(output, buffer)
                }
                output.force(true)
            }
        }
    }

    private fun safeRelativeFile(root: File, relative: String): File {
        validateRelativePath(relative)
        val base = root.toPath().toAbsolutePath().normalize()
        val path = base.resolve(relative).normalize()
        require(path.startsWith(base)) { "relative updater path escaped its owner directory" }
        return path.toFile()
    }

    private fun existingFingerprint(file: File): FileFingerprint? {
        val path = file.toPath()
        rejectSymlinkPath(path)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("existing updater target is not a regular file: ${file.path}")
        return FileFingerprint(Files.size(path), sha256File(file))
    }

    private fun validateTransferBounds(record: ForeverClientUpdateCatalog.ObjectRecord) {
        if (record.encodedSize !in 1..MAX_ENCODED_BYTES) {
            throw IllegalStateException("catalog object ${record.ekey} exceeds the admitted CASC/transfer encoded-size bound")
        }
        if (record.decodedSize !in 0..MAX_DECODED_BYTES) {
            throw IllegalStateException("catalog object ${record.ekey} exceeds the admitted 512 MiB BLTE decoded-size bound")
        }
    }

    private fun inspectPrivateWorkspace(
        destination: ForeverClientUpdateDestination,
        sourceRevision: String,
        loose: LooseState,
    ): PrivateWorkspaceSummary {
        val root = resumableWorkspacePath(destination)
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return PrivateWorkspaceSummary(0L, null, 0L, emptySet(), 0L, null)
        }
        if (Files.isSymbolicLink(root.toPath()) || !Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("private resumable updater workspace is not a regular directory")
        }
        val expectedOwner = workspaceOwnerBytes(destination, sourceRevision)
        requireExactFile(File(root, WORKSPACE_OWNER_NAME).toPath(), expectedOwner, "client updater workspace marker")
        val stageReceipt = readStageReceipt(destination)
        if (stageReceipt != null && stageReceipt.sourceRevision != sourceRevision) {
            throw IllegalStateException("private transaction-stage receipt is for a different CASC source revision")
        }
        Files.newDirectoryStream(root.toPath()).use { children ->
            children.forEach { path ->
                val name = path.fileName.toString()
                if (name !in PRIVATE_WORKSPACE_ENTRIES || Files.isSymbolicLink(path)) {
                    throw IllegalStateException("private updater workspace contains unowned state: $name")
                }
                if (name in PRIVATE_WORKSPACE_FILES && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw IllegalStateException("private updater state file is not regular: $name")
                }
                if (name !in PRIVATE_WORKSPACE_FILES && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw IllegalStateException("private updater workspace area is not a regular directory")
                }
            }
        }
        // The private CASC verification temporary is known scratch only with
        // its identity-bound ownership: workspace marker (proven above) plus a
        // receipt-bound checkpoint. Without both it is rejected, never
        // deleted: this read-only inspection never cleans. Occupation is
        // counted by the regular-file sweep below, like every owned file.
        val cascTemp = File(root, CASC_OBJECT_TEMP_NAME)
        if (Files.exists(cascTemp.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(cascTemp.toPath()) || !Files.isRegularFile(cascTemp.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("private CASC object temporary is unsafe")
            }
            if (Files.size(cascTemp.toPath()) > MAX_ENCODED_BYTES) {
                throw IllegalStateException("private CASC object temporary exceeds the admitted encoded-object bound")
            }
            if (stageReceipt == null) {
                throw IllegalStateException("private CASC object temporary has no receipt-bound checkpoint ownership")
            }
            val resumeOwner = File(root, CASC_RESUME_OWNER_NAME).toPath()
            val resumeCheckpoint = File(root, CASC_RESUME_CHECKPOINT_NAME).toPath()
            if (Files.isSymbolicLink(resumeOwner) || !Files.isRegularFile(resumeOwner, LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(resumeCheckpoint) || !Files.isRegularFile(resumeCheckpoint, LinkOption.NOFOLLOW_LINKS)
            ) {
                throw IllegalStateException("private CASC object temporary has no valid checkpoint ownership")
            }
        }
        val objectDir = File(root, "objects")
        val looseDir = File(root, "loose")
        val allowedLoose = loose.catalogPaths.toSet()
        var occupied = 0L
        var cachedKey: String? = null
        var cachedBytes = 0L
        val objectStates = LinkedHashMap<String, MutableList<Pair<String, File>>>()
        val stagedLoose = LinkedHashSet<String>()
        var stagedLooseEncoded = 0L

        if (Files.exists(objectDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(objectDir.toPath()) || !Files.isDirectory(objectDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("private updater object cache is not a regular directory")
            }
            Files.newDirectoryStream(objectDir.toPath()).use { children ->
                children.forEach { path ->
                    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        throw IllegalStateException("private updater object cache contains an unsafe entry")
                    }
                    if (path.fileName.toString() == ".forever-private-publish.lock" && Files.size(path) == 0L) return@forEach
                    val match = Regex("(?i)([0-9a-f]{32})\\.(blte|part)").matchEntire(path.fileName.toString())
                        ?: throw IllegalStateException("private updater object cache contains an unrecognized filename")
                    val key = match.groupValues[1].lowercase(Locale.ROOT)
                    val extension = match.groupValues[2].lowercase(Locale.ROOT)
                    val rows = objectStates.getOrPut(key) { ArrayList(2) }
                    if (rows.size >= 2) throw IllegalStateException("private updater cache has duplicate encoded-object files")
                    rows += extension to path.toFile()
                    occupied = Math.addExact(occupied, Files.size(path))
                }
            }
        }
        if (objectStates.keys.size > MAX_LEGACY_PREFETCH_KEYS) throw IllegalStateException("private updater cache exceeds the legacy prefetch bound")
        objectStates.forEach { (key, files) ->
            val record = catalog.objectByKey(key)
            val complete = files.firstOrNull { it.first == "blte" }?.second
            val partial = files.firstOrNull { it.first == "part" }?.second
            val completeVerified = complete?.let { verifyEncodedObject(it, record) } ?: false
            if (complete != null && !completeVerified) {
                throw IllegalStateException("private updater has an invalid completed transfer file for $key")
            }
            if (completeVerified) {
                cachedKey = key
                cachedBytes = record.encodedSize
            } else if (partial != null) {
                val length = Files.size(partial.toPath())
                cachedKey = key
                cachedBytes = when {
                    length in 1 until record.encodedSize -> length
                    length == record.encodedSize && verifyEncodedObject(partial, record) -> record.encodedSize
                    else -> 0L
                }
            }
        }

        if (Files.exists(looseDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(looseDir.toPath()) || !Files.isDirectory(looseDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("private updater loose staging is not a regular directory")
            }
            Files.walk(looseDir.toPath()).use { stream ->
                stream.forEach { path ->
                    if (Files.isSymbolicLink(path)) throw IllegalStateException("private updater loose staging contains a symlink")
                    if (path == looseDir.toPath()) return@forEach
                    val relative = looseDir.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        if (allowedLoose.none { it.startsWith("$relative/") }) {
                            throw IllegalStateException("private loose stage contains an unowned directory")
                        }
                        return@forEach
                    }
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || relative !in allowedLoose) {
                        throw IllegalStateException("private loose stage contains an unowned file")
                    }
                    occupied = Math.addExact(occupied, Files.size(path))
                    val item = loose.targets.first { it.path == relative }
                    if (Files.size(path) == item.record.decodedSize && md5File(path.toFile()) == item.record.contentKey && item.needsChange) {
                        stagedLoose += relative
                        stagedLooseEncoded = Math.addExact(stagedLooseEncoded, item.record.encodedSize)
                    }
                }
            }
        }
        Files.newDirectoryStream(root.toPath()).use { children ->
            children.forEach { path ->
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    occupied = Math.addExact(occupied, Files.size(path))
                }
            }
        }
        return PrivateWorkspaceSummary(occupied, cachedKey, cachedBytes, stagedLoose, stagedLooseEncoded, stageReceipt)
    }

    private fun workspaceOwnerBytes(destination: ForeverClientUpdateDestination, sourceRevision: String): ByteArray {
        val identity = "$targetBinding|${destination.storageId}|" +
            "${destination.root.toPath().toAbsolutePath().normalize()}|$sourceRevision"
        return "$WORKSPACE_MARKER\n${sha256(identity.toByteArray(Charsets.UTF_8))}\n".toByteArray(Charsets.UTF_8)
    }

    private fun readStageReceipt(destination: ForeverClientUpdateDestination): UpdateStageReceipt? {
        val root = resumableWorkspacePath(destination)
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.isSymbolicLink(root.toPath()) || !Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("private updater receipt directory is unsafe")
        }
        val receiptPath = File(root, STAGE_RECEIPT_NAME).toPath()
        val temporaryPath = File(root, STAGE_RECEIPT_TEMP_NAME).toPath()
        if (Files.exists(temporaryPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("a private stage receipt temporary exists; manual review is required")
        }
        if (!Files.exists(receiptPath, LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.isSymbolicLink(receiptPath) || !Files.isRegularFile(receiptPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("private transaction-stage receipt is not a regular file")
        }
        val size = Files.size(receiptPath)
        if (size !in 1..MAX_STAGE_RECEIPT_BYTES) throw IllegalStateException("private transaction-stage receipt exceeds its bound")
        val bytes = Files.readAllBytes(receiptPath)
        val envelope = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (failure: Exception) {
            throw IllegalStateException("private transaction-stage receipt is malformed", failure)
        }
        if (envelope.keys().asSequence().toSet() != setOf("payload", "sha256")) {
            throw IllegalStateException("private transaction-stage receipt fields are invalid")
        }
        val payloadText = envelope.getString("payload")
        val payloadBytes = payloadText.toByteArray(Charsets.UTF_8)
        if (sha256(payloadBytes) != envelope.getString("sha256")) {
            throw IllegalStateException("private transaction-stage receipt checksum mismatch")
        }
        val payload = JSONObject(payloadText)
        if (payload.keys().asSequence().toSet() != STAGE_RECEIPT_FIELDS || payload.getInt("version") != 2) {
            throw IllegalStateException("private transaction-stage receipt schema is invalid")
        }
        val receipt = UpdateStageReceipt(
            payload.getString("stageId"),
            payload.getString("markerHash"),
            payload.getString("sourceRevision"),
            payload.getString("snapshotHash"),
            payload.getString("storageId"),
            payload.getString("destinationPath"),
            payload.getBoolean("resetCasc"),
        )
        if (!TRANSACTION_STAGE_ID.matches(receipt.stageId) || !HASH_PATTERN.matches(receipt.markerHash) ||
            receipt.storageId != destination.storageId ||
            receipt.destinationPath != destination.root.toPath().toAbsolutePath().normalize().toString() ||
            !HASH_PATTERN.matches(receipt.snapshotHash) || receipt.sourceRevision.isBlank() || receipt.sourceRevision.length > 128
        ) throw IllegalStateException("private transaction-stage receipt binding is invalid")
        val expectedOwner = workspaceOwnerBytes(destination, receipt.sourceRevision)
        requireExactFile(File(root, WORKSPACE_OWNER_NAME).toPath(), expectedOwner, "client updater workspace marker")
        val stage = transactionStageDirectory(destination, receipt)
        if (Files.exists(stage.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(stage.toPath()) || !Files.isDirectory(stage.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("receipt-bound transaction stage is not a regular directory")
            }
            val stageMarker = File(stage, TRANSACTION_STAGE_OWNER_NAME).toPath()
            if (!Files.exists(stageMarker, LinkOption.NOFOLLOW_LINKS)) {
                Files.newDirectoryStream(stage.toPath()).use { children ->
                    if (children.iterator().hasNext()) throw IllegalStateException("unmarked receipt-bound stage contains unknown data")
                }
            } else {
                requireExactFile(stageMarker, transactionStageMarkerBytes(destination, receipt.stageId), "receipt-bound transaction-stage marker")
                val actualMarkerHash = sha256(transactionStageMarkerBytes(destination, receipt.stageId))
                if (actualMarkerHash != receipt.markerHash) throw IllegalStateException("receipt and transaction-stage marker hashes disagree")
            }
        }
        return receipt
    }

    private fun persistStageReceipt(destinationOwner: ForeverClientUpdateDestination, root: File, receipt: UpdateStageReceipt) {
        val existing = readStageReceipt(destinationOwner)
        if (existing != null && (existing.stageId != receipt.stageId || existing.markerHash != receipt.markerHash ||
                existing.sourceRevision != receipt.sourceRevision || existing.snapshotHash != receipt.snapshotHash)
        ) throw IllegalStateException("private stage receipt update changed its immutable destination/source binding")
        val payload = JSONObject()
            .put("version", 2)
            .put("stageId", receipt.stageId)
            .put("markerHash", receipt.markerHash)
            .put("sourceRevision", receipt.sourceRevision)
            .put("snapshotHash", receipt.snapshotHash)
            .put("storageId", receipt.storageId)
            .put("destinationPath", receipt.destinationPath)
            .put("resetCasc", receipt.resetCasc)
            .toString()
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val envelope = JSONObject()
            .put("payload", payload)
            .put("sha256", sha256(payloadBytes))
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(envelope.size <= MAX_STAGE_RECEIPT_BYTES) { "private transaction-stage receipt exceeds its bound" }
        val temporary = File(root, STAGE_RECEIPT_TEMP_NAME).toPath()
        val destination = File(root, STAGE_RECEIPT_NAME).toPath()
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("transaction-stage receipt temporary is occupied")
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(destination) || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS))
        ) {
            throw IllegalStateException("transaction-stage receipt destination is unsafe")
        }
        FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            writeFully(channel, ByteBuffer.wrap(envelope))
            channel.force(true)
        }
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Exception) {
            throw IllegalStateException("private transaction-stage receipt cannot be atomically published", failure)
        }
        forceDirectory(destination.parent)
    }

    private fun transactionStageMarkerBytes(destination: ForeverClientUpdateDestination, stageId: String): ByteArray {
        val identity = "$targetBinding|${destination.storageId}|${destination.root.toPath().toAbsolutePath().normalize()}"
        val identityHash = sha256(identity.toByteArray(Charsets.UTF_8))
        return "forever-client-update-staging-v1\n$stageId\n$identityHash\n".toByteArray(Charsets.UTF_8)
    }

    private fun transactionStageDirectory(destination: ForeverClientUpdateDestination, receipt: UpdateStageReceipt): File =
        File(File(destination.root, TRANSACTION_STAGING_NAME), receipt.stageId)

    private fun verifyEncodedObject(file: File, record: ForeverClientUpdateCatalog.ObjectRecord): Boolean {
        return try {
            if (Files.isSymbolicLink(file.toPath()) || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                Files.size(file.toPath()) != record.encodedSize
            ) return false
            verifyEncodedEKey(file, record.ekey)
            // Reconstruction consumes encoded bytes; E frames pass through.
            ForeverBlte.verifyEncodedObject(file, record.decodedSize, record.contentKey)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun verifyEncodedEKey(file: File, expected: String) {
        val digest = MessageDigest.getInstance("MD5")
        val size = Files.size(file.toPath())
        BufferedInputStream(FileInputStream(file), COPY_BUFFER_BYTES).use { input ->
            val prefix = ByteArray(8)
            var count = 0
            while (count < prefix.size) {
                val read = input.read(prefix, count, prefix.size - count)
                if (read < 0) break
                if (read > 0) count += read
            }
            val blte = count == prefix.size && prefix.copyOfRange(0, 4).contentEquals(byteArrayOf(0x42, 0x4c, 0x54, 0x45))
            val headerSize = if (blte) {
                ((prefix[4].toLong() and 0xff) shl 24) or ((prefix[5].toLong() and 0xff) shl 16) or
                    ((prefix[6].toLong() and 0xff) shl 8) or (prefix[7].toLong() and 0xff)
            } else 0L
            val toHash = if (blte && headerSize > 0) {
                require(headerSize in 12..MAX_EKEY_HEADER_BYTES && headerSize <= size) { "BLTE EKey header exceeds its bound" }
                digest.update(prefix)
                headerSize - prefix.size
            } else {
                digest.update(prefix, 0, count)
                size - count
            }
            var remaining = toHash
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IllegalStateException("encoded transfer file is truncated")
                if (read == 0) continue
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        if (!digest.digest().hex().equals(expected, true)) throw IllegalStateException("encoded transfer EKey mismatch")
    }

    private fun sourceSegmentBytes(root: File): Long {
        val data = File(root, "Data/data")
        if (!data.isDirectory) return 0L
        var total = 0L
        Files.newDirectoryStream(data.toPath()).use { children ->
            children.forEach { path ->
                if (SEGMENT_PATTERN.matches(path.fileName.toString()) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    total = Math.addExact(total, Files.size(path))
                }
            }
        }
        return total
    }

    private fun existingSegmentBytes(destination: ForeverClientUpdateDestination): Long = sourceSegmentBytes(destination.root)

    private fun ensureDirectoryTree(path: java.nio.file.Path) {
        val absolute = path.toAbsolutePath().normalize()
        requireSafeAncestors(absolute)
        val missing = ArrayDeque<java.nio.file.Path>()
        var current: java.nio.file.Path? = absolute
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            missing.addFirst(current)
            current = current.parent
        }
        if (current == null || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
            throw IllegalStateException("updater directory parent is unavailable or unsafe")
        }
        missing.forEach { Files.createDirectory(it) }
    }

    private fun cleanupWorkspace(root: File, ownerBytes: ByteArray) {
        requireExactFile(File(root, WORKSPACE_OWNER_NAME).toPath(), ownerBytes, "client updater workspace marker")
        validatePrivateWorkspaceContents(root)
        if (!root.deleteRecursively()) throw IllegalStateException("could not clean committed private update workspace")
        forceDirectory(requireNotNull(root.parentFile).toPath())
    }

    private fun validatePrivateWorkspaceContents(root: File) {
        val loosePaths = catalog.looseFiles().map { it.path }.toSet()
        val objectKeys = HashSet<String>()
        Files.walk(root.toPath()).use { paths ->
            paths.forEach { path ->
                if (Files.isSymbolicLink(path)) throw IllegalStateException("private updater workspace contains a symlink")
                if (path == root.toPath()) return@forEach
                val relative = root.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    val looseDirectory = relative.removePrefix("loose/")
                    if (relative !in setOf("objects", "loose") &&
                        !(relative.startsWith("loose/") && loosePaths.any { it.startsWith("$looseDirectory/") })
                    ) {
                        throw IllegalStateException("private updater workspace contains an unowned directory: $relative")
                    }
                    return@forEach
                }
                val objectPart = Regex("objects/([0-9a-fA-F]{32})\\.(?:blte|part)").matchEntire(relative)
                if (relative == "objects/.forever-private-publish.lock" &&
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == 0L) return@forEach
                objectPart?.let { objectKeys += it.groupValues[1].lowercase(Locale.ROOT) }
                if (objectKeys.size > MAX_LEGACY_PREFETCH_KEYS) throw IllegalStateException("private updater workspace has more than its legacy prefetch bound of cached objects")
                val looseFile = relative.startsWith("loose/") && relative.removePrefix("loose/") in loosePaths
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && relative != WORKSPACE_OWNER_NAME) {
                    throw IllegalStateException("private updater workspace file is unsafe: $relative")
                }
                val privateReceipt = relative in PRIVATE_WORKSPACE_FILES
                if (!privateReceipt && objectPart == null && !looseFile) {
                    throw IllegalStateException("private updater workspace contains unowned state: $relative")
                }
            }
        }
    }

    private fun requireExactFile(path: java.nio.file.Path, expected: ByteArray, label: String) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != expected.size.toLong()) {
            throw IllegalStateException("$label is missing or malformed")
        }
        val actual = ByteArray(expected.size)
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            readFully(channel, ByteBuffer.wrap(actual))
        }
        if (!actual.contentEquals(expected)) throw IllegalStateException("$label does not match the current destination")
    }

    private fun sha256File(file: File): String = digestFile(file, "SHA-256")
    private fun md5File(file: File): String = digestFile(file, "MD5")

    private fun digestFile(file: File, algorithm: String): String {
        val path = file.toPath()
        rejectSymlinkPath(path)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("cannot fingerprint a nonregular updater file")
        val digest = MessageDigest.getInstance(algorithm)
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val initialSize = channel.size()
            val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer.array(), 0, count)
            }
            if (channel.size() != initialSize) throw IllegalStateException("file changed while hashing: $file")
        }
        return digest.digest().hex()
    }

    private fun sha256Prefix(file: File, count: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(count)
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer)
                if (read < 0) throw IllegalStateException("client CASC segment header is truncated")
                if (read == 0) continue
            }
            digest.update(buffer.array())
        }
        return digest.digest().hex()
    }

    private fun rejectSymlinkPath(path: java.nio.file.Path) {
        val absolute = path.toAbsolutePath().normalize()
        requireSafeAncestors(absolute)
    }

    private fun requireSafeAncestors(path: java.nio.file.Path) {
        var current = path.root ?: throw IllegalStateException("updater path is not absolute")
        for (part in path) {
            current = current.resolve(part)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw IllegalStateException("symlink in updater-owned or client path: $current")
            }
        }
    }

    private fun validateRelativePath(path: String) {
        require(path.isNotBlank() && path.length <= MAX_PATH_CHARS && !path.startsWith('/') && !path.endsWith('/') &&
            '\\' !in path && '\u0000' !in path && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
            path.split('/').none { part -> part.any { it.code < 0x20 || it.code == 0x7f } }
        ) { "updater path is not a safe canonical relative path" }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw CancellationException("client update was paused or cancelled")
    }

    private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            val count = channel.write(buffer)
            if (count < 0) throw IllegalStateException("private updater write failed")
            if (count == 0) Thread.yield()
        }
    }

    private fun readFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer)
            if (count < 0) throw IllegalStateException("private updater file is truncated")
            if (count == 0) Thread.yield()
        }
    }

    private fun forceDirectory(path: java.nio.file.Path) {
        try {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        } catch (failure: Exception) {
            throw IllegalStateException("filesystem cannot durably synchronize updater state", failure)
        }
    }

    private fun sumExact(vararg values: Long): Long = values.fold(0L, Math::addExact)

    private fun validatedTargetSummary(): ForeverClientUpdateCatalog.Summary = catalog.summary().also { summary ->
        require(summary.version.matches(VERSION_PATTERN)) { "client catalog version is malformed" }
        require(summary.buildConfigHash.matches(CONFIG_HASH_PATTERN) && summary.cdnConfigHash.matches(CONFIG_HASH_PATTERN)) {
            "client catalog TACT config hashes are malformed"
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val PRODUCT = "wow_classic_beta"
        private const val ENGINE_ID = "forever-client-updater"
        private const val RUNTIME_SCHEMA = "v1"
        /** c106 lookahead-32 tuning: 16 workers against the widened
         * depth-bound pipeline. The ordered consumer holds 1 pending entry
         * while awaiting its offered future, the bounded queue holds at most
         * 30 further entries, and the producer offers at most 1 more, so 16
         * workers stay saturated while the commit cursor advances strictly in
         * catalog order: at most ~32 in-flight keys stay under the 34-key
         * prefetch bound (32 live plus margin for older crash-window
         * leftovers), and the strict 1 GiB encoded-bytes budget is
         * unchanged. Per-worker memory stays streaming-bounded: 64 KiB
         * buffers with a bounded 8192-entry chunk table and no decoded-size
         * allocation (ForeverBlte.verifyEncodedObject decodes to a null
         * sink), plus one private verification temporary per largest
         * admitted object. */
        private const val PREFETCH_WORKERS = 16
        private const val PREFETCH_QUEUE_DEPTH = 30
        private const val PREFETCH_MAX_BYTES = (1L shl 30)
        /** Lookahead-32 crash-window bound: up to 32 live in-flight keys plus
         * margin for checkpoint-then-crash leftovers. The workspace admits
         * only this many distinct validated keys, every path and hash exactly
         * as before, and never deletes unknowns. An unclean cut can only
         * resurrect already-unlinked raws, which the next admission
         * inventories again and either reuses inside this same bound or
         * rejects without deleting. */
        private const val MAX_LEGACY_PREFETCH_KEYS = 34
        /** Structural reservation-map bound: at most 34 distinct keys, old
         * leftovers plus new work counted together before any file is
         * created. The queue only bounds live transit; recovery admits 34
         * total, so the map enforces the same recovery ceiling instead of a
         * wider figure that could generate a state the replan rejects. */
        private const val MAX_RESERVED_KEYS = MAX_LEGACY_PREFETCH_KEYS
        /** Bounded teardown grace: stalled workers are still capped by the
         * socket read timeout, so this only covers scheduling latency before
         * the unconditional join below. */
        private const val TEARDOWN_WORKER_GRACE_MS = 150_000L
        /** Bounded producer retries when its checkpoint open lands mid-creation
         * by the writer thread (same identity, ~100 ms apart). Genuine
         * mismatches still throw after the bound; the writer fails fast first. */
        private const val MAX_CHECKPOINT_OPEN_ATTEMPTS = 50
        private const val WORK_BASE_NAME = ".forever-client-update-work"
        private const val WORKSPACE_OWNER_NAME = ".owner"
        private const val WORKSPACE_MARKER = "forever-client-updater-work-v1"
        private const val CASC_OBJECT_TEMP_NAME = ".forever-casc-incremental-object.tmp"
        private const val CASC_RESUME_OWNER_NAME = ".forever-casc-resume-owner"
        private const val CASC_RESUME_CHECKPOINT_NAME = ".forever-casc-resume-checkpoint"
        private const val STORAGE_MARKER_PATH = ".forever-client-storage"
        private const val STORAGE_MARKER_VERSION = "forever-client-storage-v1"
        private const val STAGE_RECEIPT_NAME = ".transaction-stage-receipt.json"
        private const val STAGE_RECEIPT_TEMP_NAME = ".transaction-stage-receipt.tmp"
        private const val TRANSACTION_STAGING_NAME = ".forever-update-staging"
        private const val TRANSACTION_STAGE_OWNER_NAME = ".owner"
        private val TRANSACTION_STAGE_FILE = Regex("file-[0-9a-f]{64}")
        private const val BUILD_INFO_PATH = ".build.info"
        private const val MAX_TRANSACTION_FILES = 4096
        private const val MAX_CASC_PUBLISHED_FILES = 1040
        private const val MAX_PATH_CHARS = 4096
        private const val MAX_CONFIG_BYTES = 4L * 1024 * 1024
        private const val MAX_ENCODED_BYTES = (1L shl 30) - 510L
        private const val MAX_DECODED_BYTES = 512L * 1024 * 1024
        private const val CASC_SEGMENT_HEADER_BYTES = 480
        private const val CHECKPOINT_FRAME_BYTES = 72
        private const val CASC_CHECKPOINT_HEADER_BYTES = 40L
        private const val CASC_CHECKPOINT_FINALIZE_BYTES = 72L
        private const val MAX_EKEY_HEADER_BYTES = 16L * 1024 * 1024
        private const val MAX_STAGE_RECEIPT_BYTES = 16 * 1024
        private const val MAX_STORAGE_MARKER_BYTES = 512
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val CASC_TABLE_AND_SORT_RESERVE = 256L * 1024 * 1024
        private const val METADATA_RESERVE = 16L * 1024 * 1024
        private const val JOURNAL_AND_MARGIN_RESERVE = 64L * 1024 * 1024
        private const val EMPTY_SOURCE_REVISION = "empty-target-v1"
        private val SEGMENT_PATTERN = Regex("data\\.(\\d{3,4})")
        private val INDEX_PATTERN = Regex("[0-9a-fA-F]{10}\\.idx")
        private val TRANSACTION_STAGE_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val HASH_PATTERN = Regex("[0-9a-f]{64}")
        private val CONFIG_HASH_PATTERN = Regex("(?i)[0-9a-f]{32}")
        private val VERSION_PATTERN = Regex("[0-9]+(?:\\.[0-9]+){3}")
        private val STORAGE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val STAGE_RECEIPT_FIELDS = setOf("version", "stageId", "markerHash", "sourceRevision", "snapshotHash", "storageId", "destinationPath", "resetCasc")
        private val PRIVATE_WORKSPACE_FILES = setOf(WORKSPACE_OWNER_NAME, STAGE_RECEIPT_NAME, CASC_RESUME_OWNER_NAME, CASC_RESUME_CHECKPOINT_NAME, CASC_OBJECT_TEMP_NAME)
        private val PRIVATE_WORKSPACE_ENTRIES = PRIVATE_WORKSPACE_FILES + setOf("objects", "loose")
    }
}
