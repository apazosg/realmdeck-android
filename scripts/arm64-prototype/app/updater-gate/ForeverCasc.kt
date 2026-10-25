package app.gamenative

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import java.util.zip.CRC32
import kotlin.math.min

/**
 * Legacy verifier input: streams admitted official target objects in manifest
 * order for a lockstep join with the checkpoint prefix. The membership-based
 * path uses the bounded exact lookup instead and does not require this cursor.
 */
internal typealias OverlapOfficialStream = (
    cancelled: () -> Boolean,
    consume: (Sequence<ForeverCasc.IncrementalObjectSpec>) -> Unit,
) -> Unit

/** Portable writer for the bounded V7 local CASC store used by the bootstrap. */
internal interface CascReplayReader : AutoCloseable {
    /**
     * Bind to [file], closing any previously open segment first. The binding
     * proves the path is a regular non-symlink file and that the fresh FD
     * and a fresh post-open stat observe the same device, inode, kind, and
     * size; a partial open closes its FD exactly once before propagating.
     */
    fun ensureSegment(file: File)

    /**
     * Prove the open file still names a regular path covering [endExclusive]:
     * fresh path and FD stats must agree on device, inode, and regular kind,
     * and both sizes must cover the end. A stale FD alone never authorizes;
     * every object requires fresh path-plus-FD agreement, while ABA and race
     * windows remain and are not claimed away.
     */
    fun checkBinding(endExclusive: Long)

    /**
     * Prove the open file is still exactly the sealed group observed at freeze:
     * fresh path and FD stats must agree on device, inode, and regular kind,
     * and both sizes must equal [exactSize] exactly, not merely cover it. A
     * grown, truncated, or replaced file fails closed. This is the post-group
     * hygiene guard for the sealed-prefix verifier; the per-object
     * [checkBinding] above stays the pre-group guard and keeps its
     * greater-or-equal semantics.
     */
    fun checkSealedBinding(exactSize: Long)

    /**
     * Positional read returning the bytes served (0..[len]). A zero return
     * with [len] positive means end-of-file; the caller treats it as
     * truncation, never as valid data. Zero-length reads return 0.
     */
    fun readAt(buffer: ByteArray, off: Int, len: Int, position: Long): Int

    override fun close()
}

/**
 * Shared run state for the sealed-prefix overlap path (first rollout:
 * sourceless resumable with a checkpointed prefix and a reader factory).
 * Created by the update engine and handed to the writer; the writer's worker
 * thread owns the [DiskIndexTable] and the checkpoint appends, while the
 * verifier thread owns only its reader and this coordinator. The verifier
 * never mutates the table and never removes checkpoint frames; the writer
 * never verifies prefix bytes. Raw-leftover retirement fires exactly once
 * per key, from a group ack (whose staged copies are fully crypto-verified)
 * or from the individual full-strength proof on the blocked-budget path —
 * never from an unverified bare yield, and never twice: every retire path
 * first atomically claims the key here, with all file IO outside this lock.
 */
internal class CascOverlapCoordinator(
    /** Retire one checkpointed leftover raw whose staged copy is verified. */
    val retirePrefixRaw: (String) -> Unit,
    /**
     * Leftover raw key set known at seed time (at most 34). The load collects
     * staged-copy locations for exactly these keys during its single
     * streaming pass, so the blocked-budget path never rescans the
     * checkpoint per key.
     */
    wantedKeys: Set<String> = emptySet(),
) {
    /** Set by either worker on exit; the peer observes it as cancellation. */
    val peerCancel = java.util.concurrent.atomic.AtomicBoolean(false)

    /** First failure wins; the joining side propagates it as primary. */
    val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

    /** Frozen prefix object count, published by the writer at freeze. */
    val prefixTotal = java.util.concurrent.atomic.AtomicLong(-1L)

    /** Group-acked objects; the barrier requires this to equal [prefixTotal]. */
    val ackedObjects = java.util.concurrent.atomic.AtomicLong(0L)

    /** Group-acked encoded bytes, for the barrier completion log only. */
    val ackedBytes = java.util.concurrent.atomic.AtomicLong(0L)

    private val lock = Any()
    /** Checkpointed keys that still hold a leftover raw; at most 34, ordered. */
    private val rawKeys = java.util.TreeSet<String>()
    /** Legacy lexical watermark; used only by the range-based ack API. */
    private var verifiedThrough: String? = null
    /** Exact acknowledged keys relevant to the bounded wanted-raw set. */
    private val verifiedKeys = HashSet<String>()
    /** Wanted leftover keys snapshotted at construction; at most 34. */
    private val wanted: Set<String>
    /** Frozen staged-copy locations, published once by the writer at freeze. */
    @Volatile
    private var rawKeyRecords: Map<String, OverlapRawKeyRecord> = emptyMap()

    init {
        require(wantedKeys.size <= 34) { "overlap wanted raw-key set exceeds its 34-key recovery quota" }
        val normalized = HashSet<String>(34)
        var count = 0
        for (key in wantedKeys) {
            if (++count > 34) throw IllegalArgumentException("overlap wanted raw-key set exceeds its 34-key recovery quota")
            normalized += key.lowercase(java.util.Locale.ROOT)
        }
        wanted = normalized.toSet()
    }

    /** Snapshot of the wanted set for the single load pass. */
    fun snapshotWantedKeys(): Set<String> = wanted

    /** Publish the load-collected staged-copy locations, once, at freeze. */
    fun publishRawKeyRecords(records: Map<String, OverlapRawKeyRecord>) {
        rawKeyRecords = HashMap(records)
    }

    /** Staged-copy location for one claimed key, or null when unavailable. */
    fun rawKeyRecord(ekey: String): OverlapRawKeyRecord? =
        rawKeyRecords[ekey.lowercase(java.util.Locale.ROOT)]

    /**
     * Register a checkpointed key that holds a leftover raw on disk.
     *
     * @return true when the verifier already acked a group past this key
     * (late registration): the caller must retire the raw immediately and
     * exactly once instead of waiting for an ack that already happened.
     * Counter increments must happen BEFORE this call, so the immediate
     * retire can only balance them, never drive them negative.
     */
    fun registerRawKey(ekey: String): Boolean {
        val key = ekey.lowercase(java.util.Locale.ROOT)
        synchronized(lock) {
            val done = key in verifiedKeys || (verifiedThrough != null && key <= verifiedThrough!!)
            if (done) return true
            if (rawKeys.size >= 34) {
                throw IllegalStateException("overlap prefix raw-key registration exceeds its 34-key recovery quota")
            }
            rawKeys += key
            return false
        }
    }

    /**
     * Atomically claim a registered key for the individual-proof retire
     * path. Returns true only to the single winner; every other caller
     * (including a later group ack) sees it as already handled.
     */
    fun tryClaim(ekey: String): Boolean {
        synchronized(lock) {
            return rawKeys.remove(ekey.lowercase(java.util.Locale.ROOT))
        }
    }

    /**
     * Atomically claim any one registered key for the individual-proof
     * path, oldest first. Powers the blocked-budget eviction without
     * waiting for the verifier; the group ack path can never double-retire
     * a claimed key.
     */
    fun tryClaimOne(): String? {
        synchronized(lock) {
            val key = rawKeys.firstOrNull() ?: return null
            rawKeys.remove(key)
            return key
        }
    }

    /**
     * True when at least one registered key already has its frozen
     * staged-copy location, so the producer can free budget with an
     * individual proof instead of waiting for a verifier ack that may be
     * a whole slow group away. Lock-only, no IO.
     */
    fun hasProvableRaw(): Boolean {
        synchronized(lock) {
            if (rawKeys.isEmpty()) return false
            return rawKeys.any { rawKeyRecords.containsKey(it) }
        }
    }

    /**
     * Legacy acknowledgement API for an EKey-ordered checkpoint. Production
     * membership-based verification uses [ackGroupExact], since physical
     * archive groups need not occupy contiguous lexical EKey ranges.
     */
    fun ackGroup(count: Long, minKey: String, maxKey: String, encodedBytes: Long) {
        require(count > 0) { "overlap group acknowledgement is empty" }
        val toRetire: List<String>
        synchronized(lock) {
            toRetire = rawKeys.subSet(minKey, true, maxKey, true).toList()
            rawKeys.removeAll(toRetire.toSet())
            if (verifiedThrough == null || maxKey > verifiedThrough!!) verifiedThrough = maxKey
        }
        for (key in toRetire) {
            try {
                retirePrefixRaw(key)
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
                throw t
            }
        }
        ackedObjects.addAndGet(count)
        ackedBytes.addAndGet(encodedBytes)
    }

    /**
     * Acknowledge a fully verified group by its exact intersection with the
     * bounded wanted-key set. Unlike the legacy range API, this is correct
     * when EKeys from different archive groups interleave lexically. The
     * verifier supplies at most the 34 wanted keys; no group-sized key list is
     * retained. Keys are claimed under the lock and retired outside it.
     */
    fun ackGroupExact(count: Long, verifiedWantedKeys: Collection<String>, encodedBytes: Long) {
        require(count > 0) { "overlap group acknowledgement is empty" }
        require(verifiedWantedKeys.size <= 34) {
            "overlap exact acknowledgement exceeds its 34-key recovery quota"
        }
        val normalized = HashSet<String>(34)
        var countProvided = 0
        for (key in verifiedWantedKeys) {
            if (++countProvided > 34) {
                throw IllegalArgumentException("overlap exact acknowledgement exceeds its 34-key recovery quota")
            }
            normalized += key.lowercase(java.util.Locale.ROOT)
        }
        require(normalized.all { it in wanted }) {
            "overlap exact acknowledgement escapes its 34-key recovery quota"
        }
        val toRetire: List<String>
        synchronized(lock) {
            if (normalized.any { it in verifiedKeys }) {
                throw IllegalStateException("overlap exact acknowledgement repeats an already verified key")
            }
            verifiedKeys.addAll(normalized)
            toRetire = normalized.sorted().filter { rawKeys.remove(it) }
        }
        for (key in toRetire) {
            try {
                retirePrefixRaw(key)
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
                throw t
            }
        }
        ackedObjects.addAndGet(count)
        ackedBytes.addAndGet(encodedBytes)
    }
}

/**
 * Immutable snapshot of the checkpointed prefix taken once after the single
 * load/recovery pass, before the verifier thread spawns. The writer never
 * truncates or writes old prefix files after this point; new objects go to
 * fresh segment IDs above [prefixMaxId].
 */
internal data class OverlapPrefixFreeze(
    val count: Long,
    val lastEkey: String?,
    val cpByteEnd: Long,
    val cpFileKey: String,
    val identityHash: ByteArray,
    val firstArchiveId: Int,
    /** Ordered segment ID to sealed end byte; at most 1024 entries. */
    val segmentEnds: Map<Int, Long>,
    /**
     * Checkpointed staged-copy locations for the bounded leftover-raw key
     * set, collected during the single load pass. Lets the blocked-budget
     * path prove one staged copy with the reader factory without rescanning
     * the checkpoint per key.
     */
    val rawKeyRecords: Map<String, OverlapRawKeyRecord>,
) {
    val prefixMaxId: Int get() = segmentEnds.keys.maxOrNull() ?: -1
}

/**
 * Minimal staged-copy locator for one leftover raw key: the exact frame
 * fields the load admitted, nothing more. No file handles, no cursors.
 */
internal data class OverlapRawKeyRecord(
    val ekey: String,
    val contentKey: String,
    val encodedSize: Long,
    val decodedSize: Long,
    val archiveId: Int,
    val offset: Long,
)

object ForeverCasc {
    private val diagnostics = Logger.getLogger("ForeverClientUpdate")
    private const val LOCAL_HEADER_SIZE = 30
    private const val SEGMENT_HEADER_SIZE = 480
    private const val ARCHIVE_LIMIT = 1L shl 30
    private const val INDEX_SIZE = 0x30000
    /**
     * Empty update-section reserve after the 4KiB-aligned sorted end of
     * every newly written V7 index. The native 70450 binder rejects indices
     * that end exactly at the aligned sorted end ("Truncated updated
     * section"); 48KiB of zeros is the compatibility-tested variant, not a
     * proven native minimum.
     */
    private const val INDEX_UPDATE_RESERVE_BYTES = 0xC000L
    private const val MIN_INDEX_FILE_SIZE = 0x1000
    private const val INDEX_ALIGNMENT = 0x1000L
    private const val CANDIDATE_UPDATE_PAGE_SIZE = 512
    private const val CANDIDATE_UPDATE_RECORD_SIZE = 24
    private const val CANDIDATE_UPDATE_RECORDS_PER_PAGE = 21
    private const val CANDIDATE_UPDATE_PAGE_PADDING = 8
    private const val MAX_INDEX_ENTRIES_PER_BUCKET = 1_000_000
    private const val MAX_TOTAL_INDEX_ENTRIES = 2_000_000
    private const val MAX_INDEX_GENERATION_FILES = 256
    private const val MAX_SCANNED_INDEX_RECORDS = 16_000_000L
    private const val MAX_CANDIDATE_HINTS = 2_000_000
    private const val MAX_CANDIDATE_HINTS_PER_PREFIX = 512
    private const val CANDIDATE_HINT_RECORD_SIZE = 32
    private const val INCREMENTAL_SCRATCH_NAME = ".forever-casc-index-work"
    private const val INCREMENTAL_OBJECT_TEMP_NAME = ".forever-casc-incremental-object.tmp"
    private const val MAX_TEMPORARY_OBJECT_BYTES = ARCHIVE_LIMIT - SEGMENT_HEADER_SIZE - LOCAL_HEADER_SIZE
    private const val RESUME_OWNER_NAME = ".forever-casc-resume-owner"
    private const val RESUME_CHECKPOINT_NAME = ".forever-casc-resume-checkpoint"
    private val RESUME_MAGIC = "FWCASCR1".toByteArray(Charsets.US_ASCII)
    private const val RESUME_HEADER_SIZE = 40
    private const val RESUME_FRAME_SIZE = 72
    private const val RESUME_FRAME_DATA_SIZE = 68
    private const val RESUME_OBJECT_FRAME = 1
    private const val RESUME_FINISH_FRAME = 2
    private const val MAX_RESUME_CHECKPOINT_BYTES = RESUME_HEADER_SIZE.toLong() +
        MAX_TOTAL_INDEX_ENTRIES.toLong() * RESUME_FRAME_SIZE + RESUME_FRAME_SIZE
    private const val INCREMENTAL_TABLE_SLOT_SIZE = 40
    private const val INCREMENTAL_TABLE_INITIAL_CAPACITY = 32_768
    private const val INCREMENTAL_TABLE_MAX_CAPACITY = 4_194_304
    private const val INDEX_SORT_BATCH_RECORDS = 8_192
    /** Commit-stage timing cadence: aggregates cover that many new objects. */
    private const val COMMIT_TIMING_BATCH_OBJECTS = 256L
    /** Small durable batches leave room for lookahead within the unchanged raw-key cap. */
    internal const val RESUMABLE_COMMIT_GROUP_OBJECTS = 4
    private const val INDEX_MERGE_FAN_IN = 32
    private const val MAX_EKEY_HEADER_SIZE = 16 * 1024 * 1024
    private const val MAX_EKEY_HEADER_CHUNKS = 8_192
    private const val BUFFER_SIZE = 64 * 1024
    /** Shared sequential group buffer for the sealed-prefix verifier. */
    private const val SEGMENT_BUFFER_SIZE = 128 * 1024
    private val CHECKSUM_B_LUT = intArrayOf(
        0x049396B8, 0x72A82A9B.toInt(), 0xEE626CCA.toInt(), 0x9917754F.toInt(),
        0x15DE40B1, 0xF5A8A9B6.toInt(), 0x421EAC7E, 0xA9D55C9A.toInt(),
        0x317FD40C, 0x04FAF80D, 0x3D6BE971, 0x52933CFD, 0x27F64B7D,
        0xC6F5C11B.toInt(), 0xD5757E3A.toInt(), 0x6C388745,
    )

    internal data class RuntimeObject(val record: org.json.JSONObject, val raw: File)

    /** One item from a caller-owned, streaming full-manifest traversal. encodedSize is BLTE bytes, excluding the 30-byte CASC local header. */
    internal data class IncrementalObjectSpec(
        val ekey: String,
        val contentKey: String,
        val encodedSize: Long,
        val decodedSize: Long,
    )

    private data class PendingResumeCommit(
        val originalSpec: IncrementalObjectSpec,
        val spec: IncrementalObjectSpec,
        val raw: File,
        val archiveId: Int,
        val offset: Long,
    )

    /**
     * Candidate counts are populated by the canonical target path. A rejected
     * hint failed physical/full-EKey/BLTE/content checks or could not be safely
     * referenced; legacy union writes report zero candidate counts.
     */
    internal data class IncrementalResult(
        val reusedObjects: Long,
        val writtenObjects: Long,
        val newSegments: Int,
        val candidateHintsExamined: Long = 0,
        val candidateHintsRejected: Long = 0,
        val candidateHintsLoaded: Long = 0,
    )

    /** Read-only estimate for a complete canonical target manifest. */
    internal data class TargetScanResult(
        val totalObjects: Long,
        val verifiedObjects: Long,
        val missingObjects: Long,
        val downloadBytes: Long,
        val reusedEncodedBytes: Long,
        val newSegmentBytesUpperBound: Long,
        val indexBytesUpperBound: Long,
        val newSegmentCount: Int,
    )

    /** Read-only, identity-bound progress available from the owned resumable stage. */
    internal data class ResumeSummary(
        val completedObjectCount: Long,
        val completedEncodedBytes: Long,
        val checkpointBytes: Long,
        val lastEkey: String?,
        val retainedSegmentBytes: Long,
        val finalizingIndexBytes: Long,
        val reclaimableScratchBytes: Long,
        val finalizing: Boolean,
    )

    internal fun materialize(
        staging: File,
        buildInfo: ByteArray,
        configs: List<Pair<String, File>>,
        indices: List<Pair<String, File>>,
        objects: List<RuntimeObject>,
        cancelled: () -> Boolean,
    ) {
        val data = File(staging, "Data")
        val ownership = File(staging, ".forever-materializer-owned")
        if (listOf(data, ownership).any { Files.isSymbolicLink(it.toPath()) }) throw IllegalStateException("CASC staging contains an unexpected symlink")
        if (data.exists() && !ownership.isFile) {
            throw IllegalStateException("unmanaged CASC staging already exists; refusing to overwrite it")
        }
        if (data.exists()) data.deleteRecursively()
        val work = File(staging, "Data.new")
        if (Files.isSymbolicLink(work.toPath())) throw IllegalStateException("CASC partial staging contains an unexpected symlink")
        if (work.exists() && !ownership.isFile) {
            throw IllegalStateException("unmanaged CASC partial staging already exists; refusing to overwrite it")
        }
        if (work.exists()) work.deleteRecursively()
        staging.mkdirs()
        if (!ownership.exists()) writeOwnedText(ownership, "managed-casc-materializer-v1\n")
        if (!work.mkdirs()) throw IllegalStateException("cannot create CASC staging")
        val dataFiles = File(work, "data").apply { mkdirs() }
        val indexFiles = File(work, "indices").apply { mkdirs() }
        val configFiles = File(work, "config").apply { mkdirs() }
        if (listOf(dataFiles, indexFiles, configFiles).any { Files.isSymbolicLink(it.toPath()) }) throw IllegalStateException("CASC staging contains an unexpected symlink")
        try {
            configs.forEach { (hash, source) ->
                checkCancelled(cancelled)
                val target = File(configFiles, "${hash.substring(0, 2)}/${hash.substring(2, 4)}/$hash")
                target.parentFile?.mkdirs()
                copyFile(source, target, cancelled)
            }
            indices.forEach { (hash, source) ->
                checkCancelled(cancelled)
                copyFile(source, File(indexFiles, "$hash.index"), cancelled)
            }
            val entries = Array(16) { mutableListOf<IndexEntry>() }
            var archiveId = 0
            // Every archive starts with the 16 reconstruction records emitted
            // by openArchive.  The first real object therefore begins at 480,
            // not at offset zero.
            var offset = SEGMENT_HEADER_SIZE.toLong()
            var archive = openArchive(dataFiles, archiveId, entries, cancelled)
            try {
                objects.forEach { item ->
                    checkCancelled(cancelled)
                    val ekey = item.record.getJSONArray("encoding_keys").getString(0).lowercase().hexBytes()
                    val decodedSize = item.record.getLong("decoded_size")
                    if (ekey.size != 16 || decodedSize < 0) throw IllegalArgumentException("invalid CASC runtime object")
                    val rawLength = item.raw.length()
                    if (rawLength > ARCHIVE_LIMIT - SEGMENT_HEADER_SIZE - LOCAL_HEADER_SIZE) throw IllegalArgumentException("CASC object exceeds archive limit")
                    val total = LOCAL_HEADER_SIZE + rawLength
                    if (offset + total > ARCHIVE_LIMIT) {
                        archive.close()
                        archiveId += 1
                        offset = SEGMENT_HEADER_SIZE.toLong()
                        archive = openArchive(dataFiles, archiveId, entries, cancelled)
                    }
                    val globalOffset = archiveId * ARCHIVE_LIMIT + offset
                    val header = localHeader(ekey, total, globalOffset, 0)
                    archive.write(header)
                    copyFile(item.raw, archive, cancelled)
                    val entry = IndexEntry(ekey.copyOfRange(0, 9), archiveId, offset, total)
                    entries[computeBucket(ekey)].add(entry)
                    offset += total
                }
            } finally {
                archive.close()
            }
            repeat(16) { bucket ->
                checkCancelled(cancelled)
                writeIndex(File(dataFiles, "%02x%08x.idx".format(bucket, 1)), bucket, entries[bucket])
            }
            if (!work.renameTo(data)) throw IllegalStateException("CASC staging promotion failed")
            val info = File(staging, ".build.info")
            openOwnedOutput(info, false).use { it.write(buildInfo) }
        } catch (t: Throwable) {
            // Data.new is exclusively ours.  It is deliberately retained on
            // failure so a caller never mistakes a partial store for success.
            throw t
        }
    }

    /**
     * Verify the requested, bounded object list without rewriting the store.
     * Index history is validated with the same bounded V7 parser as incremental
     * merges, while the in-memory lookup is limited to requested EKey prefixes.
     * This List API is not a full-manifest verifier; that needs a streaming or
     * disk-backed request interface.
     */
    internal fun verifyExisting(
        installDir: File,
        objects: List<RuntimeObjectSpec>,
        cancelled: () -> Boolean,
    ) {
        val dataFiles = File(installDir, "Data/data")
        if (Files.isSymbolicLink(installDir.toPath()) || !Files.isDirectory(installDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("existing CASC install directory is not a regular directory")
        }
        if (Files.isSymbolicLink(dataFiles.toPath()) || !Files.isDirectory(dataFiles.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("existing CASC data directory is not a regular directory")
        }
        rejectSymlinkParents(installDir)
        rejectSymlinkParents(dataFiles)
        val indexSet = inspectExistingIndexFiles(dataFiles, cancelled, allowCandidateHints = true)
        val requestedByPrefix = HashMap<String, String>(objects.size.coerceAtLeast(16))
        objects.forEach { spec ->
            checkCancelled(cancelled)
            val ekey = spec.ekey.lowercase()
            require(ekey.matches(Regex("[0-9a-f]{32}"))) { "invalid CASC encoding key in verification request" }
            val prefix = ekey.substring(0, 18)
            val previous = requestedByPrefix.putIfAbsent(prefix, ekey)
            if (previous != null && previous != ekey) {
                throw IllegalStateException("CASC short EKey prefix collision in verification request")
            }
        }
        if (objects.isEmpty()) return
        val verifyScratch = Files.createTempDirectory(installDir.toPath(), ".forever-casc-verify-")
        val candidates = try {
            CandidateHintStore.create(
                File(verifyScratch.toFile(), "candidate-prefixes.bin"),
                File(verifyScratch.toFile(), "candidate-hints.bin"),
                indexSet.initialRecordEstimate,
                cancelled,
            )
        } catch (t: Throwable) {
            deleteOwnedScratch(verifyScratch.toFile())
            throw t
        }
        try {
            val temporary = Files.createTempFile(verifyScratch, "object-", ".blte").toFile()
            buildCandidateHints(indexSet, candidates, cancelled)
            objects.forEach { objectSpec ->
                checkCancelled(cancelled)
                val spec = RuntimeObjectSpec(objectSpec.ekey.lowercase(), objectSpec.contentKey.lowercase(), objectSpec.decodedSize)
                if (findVerifiedCandidate(dataFiles, candidates, spec, temporary, cancelled).entry == null) {
                    throw IllegalStateException("existing CASC has no verified target-content candidate for ${objectSpec.ekey}")
                }
            }
        } finally {
            try {
                candidates.close()
            } finally {
                deleteOwnedScratch(verifyScratch.toFile())
            }
        }
    }

    /**
     * Stage merged V7 indices and only newly required archive segments in a
     * separate, initially absent directory. The source store is read-only and
     * remains in place. The manifest is consumed once; callers must stream it
     * from disk rather than materializing a whole-game list in memory.
     *
     * Index records are held in a bounded disk-backed hash table. Final records
     * are externally sorted in bounded runs, so neither source history nor the
     * merged manifest is retained as a whole-game object graph. V7 indices keep
     * their legacy 0x30000 minimum size and grow in 4KiB units as required,
     * always leaving a 48KiB empty update reserve after the aligned sorted
     * section for the native binder.
     * An exception can leave unpromoted files in outputDataFiles; callers must
     * treat it as invalid and never activate it.
     */
    internal fun writeIncremental(
        sourceDataFiles: File,
        outputDataFiles: File,
        objects: Sequence<IncrementalObjectSpec>,
        sourceFor: (IncrementalObjectSpec) -> File?,
        cancelled: () -> Boolean,
    ): IncrementalResult = writeIncrementalInternal(
        sourceDataFiles, outputDataFiles, objects, sourceFor, cancelled, canonicalTarget = false,
    )

    /**
     * Build canonical indices for exactly the supplied target manifest. The
     * caller must establish that the sequence is the complete admitted target;
     * this method never unions unverified old keys to imply completeness.
     * Old sorted and guarded update records are only candidate locations; a
     * candidate is reused after exact EKey, size, BLTE, decoded CKey/size, local
     * header, and referenced segment reconstruction records are verified. No
     * unrequested source index entry is copied to the output generation.
     */
    internal fun writeTargetIncremental(
        sourceDataFiles: File,
        outputDataFiles: File,
        objects: Sequence<IncrementalObjectSpec>,
        sourceFor: (IncrementalObjectSpec) -> File?,
        cancelled: () -> Boolean,
    ): IncrementalResult = writeIncrementalInternal(
        sourceDataFiles, outputDataFiles, objects, sourceFor, cancelled, canonicalTarget = true,
    )

    /**
     * Canonical target writer with an identity-bound, append-only checkpoint. Completed
     * groups are kept only in the updater-owned output segments; a retry verifies and
     * reuses checkpointed objects, truncating only an uncheckpointed tail of the final
     * segment. The checkpoint is deliberately local to this writer, not a generic cache.
     */
    internal fun writeTargetIncrementalResumable(
        sourceDataFiles: File?,
        outputDataFiles: File,
        checkpointDirectory: File,
        objects: Sequence<IncrementalObjectSpec>,
        sourceFor: (IncrementalObjectSpec) -> File?,
        cancelled: () -> Boolean,
        checkpointIdentity: String,
        sourceConsumed: (IncrementalObjectSpec, File) -> Unit = { _, _ -> },
        replayReaderFactory: ((() -> Boolean) -> CascReplayReader)? = null,
        overlapCoordinator: CascOverlapCoordinator? = null,
        overlapOfficialStream: OverlapOfficialStream? = null,
        commitFlushRequest: AtomicReference<(() -> Boolean)?>? = null,
        targetObjectCount: Int = 0,
        targetMembership: ((String) -> IndexedValue<IncrementalObjectSpec>)? = null,
    ): IncrementalResult = writeIncrementalInternal(
        sourceDataFiles,
        outputDataFiles,
        objects,
        sourceFor,
        cancelled,
        canonicalTarget = true,
        checkpointIdentity = checkpointIdentity,
        checkpointDirectory = checkpointDirectory,
        sourceConsumed = sourceConsumed,
        replayReaderFactory = replayReaderFactory,
        overlapCoordinator = overlapCoordinator,
        overlapOfficialStream = overlapOfficialStream,
        commitFlushRequest = commitFlushRequest,
        targetObjectCount = targetObjectCount,
        targetMembership = targetMembership,
    )

    /**
     * Read-only estimate of complete checkpoint frames. Every frame is rebound to
     * the caller's streaming target manifest; this never truncates a torn tail or
     * trusts a checkpoint object that the writer would later reject.
     */
    internal fun inspectResumableCheckpoint(
        outputDataFiles: File,
        checkpointDirectory: File,
        objects: Sequence<IncrementalObjectSpec>,
        cancelled: () -> Boolean,
        checkpointIdentity: String,
        targetObjectCount: Int = 0,
        targetMembership: ((String) -> IndexedValue<IncrementalObjectSpec>)? = null,
    ): ResumeSummary {
        require(if (targetMembership == null) targetObjectCount == 0 else
            targetObjectCount > 0 && targetObjectCount.toLong() <= MAX_SCANNED_INDEX_RECORDS) {
            "checkpoint membership count is outside its bounded target"
        }
        // The lookup must bind to the admitted catalog and a unique stable ordinal
        // per EKey. The derived ordering hint supplies ordinals, never object data.
        val seen = if (targetMembership == null) null else java.util.BitSet(targetObjectCount)
        val directory = outputDataFiles.toPath().toAbsolutePath().normalize()
        val checkpointDir = checkpointDirectory.toPath().toAbsolutePath().normalize()
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
        ) {
            throw IllegalStateException("resumable CASC output is not a regular directory")
        }
        rejectSymlinkParents(outputDataFiles)
        rejectSymlinkParents(checkpointDirectory)
        val identityHash = MessageDigest.getInstance("SHA-256").digest(checkpointIdentity.toByteArray(Charsets.UTF_8))
        val ownerPath = checkpointDir.resolve(RESUME_OWNER_NAME)
        val checkpointPath = checkpointDir.resolve(RESUME_CHECKPOINT_NAME)
        if (!Files.exists(ownerPath, LinkOption.NOFOLLOW_LINKS) && !Files.exists(checkpointPath, LinkOption.NOFOLLOW_LINKS)) {
            return ResumeSummary(0, 0, 0, null, 0, 0, 0, false)
        }
        requireExactFile(ownerPath, RESUME_MAGIC + identityHash, "resumable CASC owner marker")
        if (Files.isSymbolicLink(checkpointPath) || !Files.isRegularFile(checkpointPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("resumable CASC checkpoint is missing or unsafe")
        }
        val size = Files.size(checkpointPath)
        if (size !in RESUME_HEADER_SIZE.toLong()..MAX_RESUME_CHECKPOINT_BYTES) {
            throw IllegalStateException("resumable CASC checkpoint size exceeds its bounded object-frame limit")
        }
        FileChannel.open(checkpointPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val header = ByteArray(RESUME_HEADER_SIZE)
            readFullyAt(channel, ByteBuffer.wrap(header), 0L)
            if (!header.copyOfRange(0, RESUME_MAGIC.size).contentEquals(RESUME_MAGIC) ||
                !header.copyOfRange(RESUME_MAGIC.size, RESUME_HEADER_SIZE).contentEquals(identityHash)
            ) throw IllegalStateException("resumable CASC checkpoint belongs to a different target or source revision")
            val target = objects.iterator()
            var next = if (targetMembership == null && target.hasNext()) normalizeIncrementalSpec(target.next()) else null
            var previous = ""
            var completed = 0L
            var encodedBytes = 0L
            var finalizing = false
            var currentArchiveId = -1
            var currentSegmentEnd = SEGMENT_HEADER_SIZE.toLong()
            var retainedSegmentBytes = 0L
            var reclaimableScratchBytes = 0L
            var position = RESUME_HEADER_SIZE.toLong()
            while (size - position >= RESUME_FRAME_SIZE) {
                checkCancelled(cancelled)
                val frame = ByteArray(RESUME_FRAME_SIZE)
                readFullyAt(channel, ByteBuffer.wrap(frame), position)
                val expectedCrc = ByteBuffer.wrap(frame, RESUME_FRAME_DATA_SIZE, 4).order(ByteOrder.BIG_ENDIAN).int
                val crc = CRC32().also { it.update(frame, 0, RESUME_FRAME_DATA_SIZE) }.value.toInt()
                if (crc != expectedCrc || frame.copyOfRange(1, 8).any { it.toInt() != 0 }) {
                    throw IllegalStateException("resumable CASC checkpoint frame checksum or reserved fields are invalid")
                }
                when (frame[0].toInt() and 0xff) {
                    RESUME_OBJECT_FRAME -> {
                        if (finalizing) throw IllegalStateException("resumable CASC checkpoint has objects after its finalization record")
                        val saved = decodeResumeObject(frame)
                        val key = saved.ekey.lowercase()
                        if (!key.matches(Regex("[0-9a-f]{32}")) || !saved.contentKey.matches(Regex("(?i)[0-9a-f]{32}")) ||
                            saved.encodedSize !in 1..(ARCHIVE_LIMIT - SEGMENT_HEADER_SIZE - LOCAL_HEADER_SIZE) ||
                            saved.decodedSize < 0 || saved.archiveId !in 0..1023 || saved.offset < SEGMENT_HEADER_SIZE ||
                            (targetMembership == null && key <= previous)
                        ) throw IllegalStateException("resumable CASC checkpoint object keys or sizes are invalid")
                        if (saved.archiveId != currentArchiveId) {
                            if ((currentArchiveId >= 0 && saved.archiveId != currentArchiveId + 1) ||
                                saved.archiveId !in 0..1023 || saved.offset != SEGMENT_HEADER_SIZE.toLong()
                            ) {
                                throw IllegalStateException("resumable CASC checkpoint segment sequence is invalid")
                            }
                            if (currentArchiveId >= 0) retainedSegmentBytes = Math.addExact(retainedSegmentBytes, currentSegmentEnd)
                            currentArchiveId = saved.archiveId
                            currentSegmentEnd = SEGMENT_HEADER_SIZE.toLong()
                        } else if (saved.offset != currentSegmentEnd) {
                            throw IllegalStateException("resumable CASC checkpoint segment offsets are not contiguous")
                        }
                        currentSegmentEnd = Math.addExact(saved.offset, Math.addExact(LOCAL_HEADER_SIZE.toLong(), saved.encodedSize))
                        if (currentSegmentEnd > ARCHIVE_LIMIT) throw IllegalStateException("resumable CASC checkpoint exceeds its segment byte bound")
                        val admitted = if (targetMembership == null) null else try {
                            targetMembership(key).also {
                                if (it.index !in 0 until targetObjectCount || requireNotNull(seen)[it.index]) {
                                    throw IllegalStateException("resumable CASC checkpoint repeats an ordinal or exceeds its target")
                                }
                            }
                        } catch (missing: NoSuchElementException) {
                            throw IllegalStateException("resumable CASC checkpoint contains an object outside the admitted target", missing)
                        }
                        if (targetMembership == null) {
                            while (next != null && next!!.ekey < key) next = if (target.hasNext()) normalizeIncrementalSpec(target.next()) else null
                        }
                        val spec = admitted?.value?.let(::normalizeIncrementalSpec) ?: next
                            ?: throw IllegalStateException("resumable CASC checkpoint contains an object outside the admitted target")
                        if (spec.ekey != key || spec.contentKey != saved.contentKey.lowercase() ||
                            spec.encodedSize != saved.encodedSize || spec.decodedSize != saved.decodedSize
                        ) throw IllegalStateException("resumable CASC checkpoint object does not match the admitted target manifest")
                        previous = key
                        if (admitted != null) requireNotNull(seen).set(admitted.index)
                        else next = if (target.hasNext()) normalizeIncrementalSpec(target.next()) else null
                        completed = Math.addExact(completed, 1L)
                        encodedBytes = Math.addExact(encodedBytes, saved.encodedSize)
                    }
                    RESUME_FINISH_FRAME -> {
                        if (finalizing || frame.copyOfRange(8, RESUME_FRAME_DATA_SIZE).any { it.toInt() != 0 }) {
                            throw IllegalStateException("resumable CASC finalization record is malformed")
                        }
                        finalizing = true
                    }
                    else -> throw IllegalStateException("resumable CASC checkpoint contains an unknown frame type")
                }
                position += RESUME_FRAME_SIZE
            }
            if (currentArchiveId >= 0) retainedSegmentBytes = Math.addExact(retainedSegmentBytes, currentSegmentEnd)
            var finalizingIndexBytes = 0L
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.newDirectoryStream(directory).use { children ->
                children.forEach { path ->
                    checkCancelled(cancelled)
                    val name = path.fileName.toString()
                    if (Files.isSymbolicLink(path)) throw IllegalStateException("resumable CASC stage contains a symlink")
                    when {
                        name == INCREMENTAL_SCRATCH_NAME -> {
                            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("resumable CASC index scratch is unsafe")
                            Files.walk(path).use { scratch ->
                                scratch.forEach { item ->
                                    if (Files.isSymbolicLink(item)) throw IllegalStateException("resumable CASC index scratch contains a symlink")
                                    if (Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS)) {
                                        reclaimableScratchBytes = Math.addExact(reclaimableScratchBytes, Files.size(item))
                                    }
                                }
                            }
                        }
                        name == INCREMENTAL_OBJECT_TEMP_NAME -> {
                            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("resumable CASC object temporary is unsafe")
                            reclaimableScratchBytes = Math.addExact(reclaimableScratchBytes, Files.size(path))
                        }
                        name.matches(Regex("data\\.[0-9]{3,4}")) -> {
                            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("resumable CASC segment is unsafe")
                        }
                        name.matches(Regex("[0-9a-fA-F]{10}\\.idx")) -> {
                            if (!finalizing) throw IllegalStateException("resumable CASC stage has an index without a finalization checkpoint")
                            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("resumable CASC finalizing index is unsafe")
                            finalizingIndexBytes = Math.addExact(finalizingIndexBytes, Files.size(path))
                        }
                        else -> throw IllegalStateException("resumable CASC stage contains unowned output: $name")
                    }
                }
            }
            return ResumeSummary(
                completed,
                encodedBytes,
                size,
                previous.takeIf { it.isNotEmpty() },
                retainedSegmentBytes,
                finalizingIndexBytes,
                reclaimableScratchBytes,
                finalizing,
            )
        }
    }

    /** Stream complete checkpoint object keys without retaining the delta in memory. */
    internal fun <T> withResumableCheckpointObjects(
        outputDataFiles: File,
        checkpointDirectory: File,
        checkpointIdentity: String,
        cancelled: () -> Boolean,
        block: (Sequence<IncrementalObjectSpec>) -> T,
    ): T {
        val directory = outputDataFiles.toPath().toAbsolutePath().normalize()
        val checkpointDir = checkpointDirectory.toPath().toAbsolutePath().normalize()
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
        ) {
            throw IllegalStateException("resumable CASC output is not a regular directory")
        }
        rejectSymlinkParents(outputDataFiles)
        rejectSymlinkParents(checkpointDirectory)
        val identityHash = MessageDigest.getInstance("SHA-256").digest(checkpointIdentity.toByteArray(Charsets.UTF_8))
        val ownerPath = checkpointDir.resolve(RESUME_OWNER_NAME)
        val checkpointPath = checkpointDir.resolve(RESUME_CHECKPOINT_NAME)
        if (!Files.exists(ownerPath, LinkOption.NOFOLLOW_LINKS) && !Files.exists(checkpointPath, LinkOption.NOFOLLOW_LINKS)) {
            return block(emptySequence())
        }
        requireExactFile(ownerPath, RESUME_MAGIC + identityHash, "resumable CASC owner marker")
        if (Files.isSymbolicLink(checkpointPath) || !Files.isRegularFile(checkpointPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("resumable CASC checkpoint is missing or unsafe")
        }
        FileChannel.open(checkpointPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            if (size !in RESUME_HEADER_SIZE.toLong()..MAX_RESUME_CHECKPOINT_BYTES) {
                throw IllegalStateException("resumable CASC checkpoint size exceeds its bounded object-frame limit")
            }
            val header = ByteArray(RESUME_HEADER_SIZE)
            readFullyAt(channel, ByteBuffer.wrap(header), 0L)
            if (!header.copyOfRange(0, RESUME_MAGIC.size).contentEquals(RESUME_MAGIC) ||
                !header.copyOfRange(RESUME_MAGIC.size, RESUME_HEADER_SIZE).contentEquals(identityHash)
            ) throw IllegalStateException("resumable CASC checkpoint belongs to a different target or source revision")
            val objects = sequence {
                var position = RESUME_HEADER_SIZE.toLong()
                var finalizing = false
                while (size - position >= RESUME_FRAME_SIZE) {
                    checkCancelled(cancelled)
                    val frame = ByteArray(RESUME_FRAME_SIZE)
                    readFullyAt(channel, ByteBuffer.wrap(frame), position)
                    val expectedCrc = ByteBuffer.wrap(frame, RESUME_FRAME_DATA_SIZE, 4).order(ByteOrder.BIG_ENDIAN).int
                    val crc = CRC32().also { it.update(frame, 0, RESUME_FRAME_DATA_SIZE) }.value.toInt()
                    if (crc != expectedCrc || frame.copyOfRange(1, 8).any { it.toInt() != 0 }) {
                        throw IllegalStateException("resumable CASC checkpoint frame checksum or reserved fields are invalid")
                    }
                    when (frame[0].toInt() and 0xff) {
                        RESUME_OBJECT_FRAME -> {
                            if (finalizing) throw IllegalStateException("resumable CASC checkpoint has an object after finalization")
                            val saved = decodeResumeObject(frame)
                            yield(IncrementalObjectSpec(saved.ekey.lowercase(), saved.contentKey.lowercase(), saved.encodedSize, saved.decodedSize))
                        }
                        RESUME_FINISH_FRAME -> {
                            if (finalizing || frame.copyOfRange(8, RESUME_FRAME_DATA_SIZE).any { it.toInt() != 0 }) {
                                throw IllegalStateException("resumable CASC finalization record is malformed")
                            }
                            finalizing = true
                        }
                        else -> throw IllegalStateException("resumable CASC checkpoint contains an unknown frame type")
                    }
                    position += RESUME_FRAME_SIZE
                }
            }
            return block(objects)
        }
    }

    /** Drop only this identity-bound private checkpoint after transaction rollback consumed its output files. */
    internal fun resetResumableCheckpoint(checkpointDirectory: File, checkpointIdentity: String) {
        val directory = checkpointDirectory.toPath().toAbsolutePath().normalize()
        rejectSymlinkParents(checkpointDirectory)
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
            throw IllegalStateException("private CASC checkpoint directory is unsafe")
        }
        val identityHash = MessageDigest.getInstance("SHA-256").digest(checkpointIdentity.toByteArray(Charsets.UTF_8))
        val owner = directory.resolve(RESUME_OWNER_NAME)
        val checkpoint = directory.resolve(RESUME_CHECKPOINT_NAME)
        val hasOwner = Files.exists(owner, LinkOption.NOFOLLOW_LINKS)
        val hasCheckpoint = Files.exists(checkpoint, LinkOption.NOFOLLOW_LINKS)
        if (!hasOwner && !hasCheckpoint) return
        if (hasOwner) requireExactFile(owner, RESUME_MAGIC + identityHash, "private CASC checkpoint owner marker")
        if (hasCheckpoint) {
            if (Files.isSymbolicLink(checkpoint) || !Files.isRegularFile(checkpoint, LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("private CASC checkpoint is unsafe")
            }
            if (Files.size(checkpoint) !in RESUME_HEADER_SIZE.toLong()..MAX_RESUME_CHECKPOINT_BYTES) {
                throw IllegalStateException("private CASC checkpoint exceeds its bounded size")
            }
            val header = ByteArray(RESUME_HEADER_SIZE)
            FileChannel.open(checkpoint, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { readFullyAt(it, ByteBuffer.wrap(header), 0L) }
            if (!header.copyOfRange(0, RESUME_MAGIC.size).contentEquals(RESUME_MAGIC) ||
                !header.copyOfRange(RESUME_MAGIC.size, RESUME_HEADER_SIZE).contentEquals(identityHash)
            ) throw IllegalStateException("private CASC checkpoint belongs to another transaction stage")
        } else if (hasOwner) {
            // An interruption may have occurred between publication of the owner header and checkpoint file.
        }
        // The owned private verification temporary belongs to the same
        // identity-bound state: validate it, then remove it BEFORE the
        // markers. A crash between these deletes can then only leave markers
        // without scratch (resumable) — never scratch without markers, which
        // preparation would have to reject as a blocked orphan.
        val privateTemp = directory.resolve(INCREMENTAL_OBJECT_TEMP_NAME)
        if (Files.isSymbolicLink(privateTemp)) {
            throw IllegalStateException("private CASC object temporary is a symlink")
        }
        if (Files.exists(privateTemp, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(privateTemp, LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("private CASC object temporary is unsafe")
            }
            if (Files.size(privateTemp) > MAX_TEMPORARY_OBJECT_BYTES) {
                throw IllegalStateException("private CASC object temporary exceeds its bounded size")
            }
            Files.delete(privateTemp)
            // Durably persist the scratch removal before discarding the
            // authority markers below: a power cut must never persist marker
            // deletion ahead of the temporary deletion it was ordered after.
            // A single force, only when there was a temporary to remove; the
            // final force after the markers is preserved as is.
            forceDirectory(directory)
        }
        if (hasCheckpoint) Files.delete(checkpoint)
        if (hasOwner) Files.delete(owner)
        FileChannel.open(directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
    }

    /**
     * Inspect a complete canonical target manifest without creating output CASC
     * segments or indices. The caller owns the completeness claim and must pass
     * a fresh, app-owned work directory outside the client installation. The
     * source store is read-only; null or absent source data means the caller has
     * already established that there is no old client to reuse.
     *
     * Each object is consumed once and reported immediately, allowing a caller
     * to persist its own resumable plan without retaining the manifest in RAM.
     * Callbacks are plan rows only: callers must defer all downloads until this
     * method returns normally and the user has approved the completed estimate.
     * The only writes are bounded index/hint scratch and one transient verified
     * object file under workDirectory; the workspace is removed on every exit.
     */
    internal fun inspectTarget(
        sourceDataFiles: File?,
        workDirectory: File,
        objects: Sequence<IncrementalObjectSpec>,
        cancelled: () -> Boolean,
        onObject: (IncrementalObjectSpec, Boolean) -> Unit,
    ): TargetScanResult {
        val inspectionStarted = System.nanoTime()
        diagnostics.info("CASC target inspection begin source=${sourceDataFiles != null}")
        validateTargetInspectionPaths(sourceDataFiles, workDirectory)
        if (!workDirectory.mkdir()) throw IllegalStateException("cannot create fresh CASC target-inspection work directory")
        try {
            checkCancelled(cancelled)
            val source = sourceDataFiles?.takeIf { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }?.let {
                inspectExistingIndexFiles(it, cancelled, allowCandidateHints = true).also { indexSet ->
                    if (indexSet.generations.any { generation -> generation < 0 || generation >= 0xffffffffL }) {
                        throw IllegalStateException("CASC V7 index generation capacity is exhausted")
                    }
                }
            }
            val table = DiskIndexTable(
                File(workDirectory, "target-records.bin"),
                source?.initialRecordEstimate ?: 0,
                cancelled,
            )
            var candidates: CandidateHintStore? = null
            try {
                if (source != null) {
                    candidates = CandidateHintStore.create(
                        File(workDirectory, "candidate-prefixes.bin"),
                        File(workDirectory, "candidate-hints.bin"),
                        source.initialRecordEstimate,
                        cancelled,
                    )
                }
                val candidateStore = candidates
                if (source != null) buildCandidateHints(source, candidateStore!!, cancelled)

                val temporary = File(workDirectory, "verified-object.tmp")
                val sourceSegmentHeaders = arrayOfNulls<List<ExistingIndexRecord>>(1024)
                val sourceSegmentHeaderChecked = BooleanArray(1024)
                val outputSegmentHeadersAdded = BooleanArray(1024)
                val packing = TargetPlanPacking((source?.maxArchiveId ?: -1) + 1)
                var verifiedObjects = 0L
                var reusedEncodedBytes = 0L

                objects.forEach { originalSpec ->
                    checkCancelled(cancelled)
                    val spec = normalizeIncrementalSpec(originalSpec)
                    validateIncrementalSpec(spec)
                    val ekeyBytes = spec.ekey.hexBytes()
                    val bucket = computeBucket(ekeyBytes)
                    val existing = table.find(ekeyBytes)
                    if (existing?.targetSeen == true) {
                        throw IllegalArgumentException("duplicate EKey in incremental manifest: ${spec.ekey}")
                    }
                    if (existing != null) {
                        existing.targetSeen = true
                        table.put(existing)
                    }
                    if (existing?.flags == 1) {
                        throw IllegalStateException("CASC object EKey conflicts with a segment reconstruction record")
                    }

                    val candidateMatch = if (source != null && candidateStore != null && existing == null) {
                        findCanonicalCandidate(
                            sourceDataFiles!!,
                            candidateStore,
                            spec,
                            temporary,
                            cancelled,
                            table,
                            packing.bucketCounts,
                            packing.totalRecords,
                            outputSegmentHeadersAdded,
                            sourceSegmentHeaders,
                            sourceSegmentHeaderChecked,
                        )
                    } else {
                        null
                    }
                    val reused = candidateMatch?.search?.entry
                    if (reused != null) {
                        candidateMatch!!.reconstructionRecords.forEach { generated ->
                            if (!table.put(generated)) {
                                throw IllegalStateException("validated source segment reconstruction EKey already exists")
                            }
                            val headerBucket = computeBucket(generated.entry.key, 1)
                            packing.bucketCounts[headerBucket] = Math.addExact(packing.bucketCounts[headerBucket], 1)
                            packing.totalRecords = Math.addExact(packing.totalRecords, 1L)
                        }
                        outputSegmentHeadersAdded[reused.entry.archiveId] = true
                        val canonicalRecord = ExistingIndexRecord(
                            spec.ekey,
                            reused.entry,
                            0,
                            Long.MAX_VALUE,
                            targetSeen = true,
                        )
                        if (!table.put(canonicalRecord)) throw IllegalStateException("canonical target EKey already exists")
                        packing.bucketCounts[bucket] = Math.addExact(packing.bucketCounts[bucket], 1)
                        packing.totalRecords = Math.addExact(packing.totalRecords, 1L)
                        verifiedObjects = Math.addExact(verifiedObjects, 1L)
                        reusedEncodedBytes = Math.addExact(reusedEncodedBytes, spec.encodedSize)
                        packing.totalObjects = Math.addExact(packing.totalObjects, 1L)
                        onObject(originalSpec, true)
                        return@forEach
                    }

                    val localObjectSize = packing.beginMissingObject(spec, bucket)
                    val targetEntry = IndexEntry(ekeyBytes.copyOfRange(0, 9), packing.archiveId, packing.offset, localObjectSize)
                    val targetRecord = ExistingIndexRecord(spec.ekey, targetEntry, 0, Long.MAX_VALUE, targetSeen = true)
                    if (!table.put(targetRecord)) throw IllegalStateException("canonical target EKey already exists")
                    packing.commitMissingObject(spec, bucket, localObjectSize)
                    onObject(originalSpec, false)
                }

                return TargetScanResult(
                    packing.totalObjects,
                    verifiedObjects,
                    packing.missingObjects,
                    packing.downloadBytes,
                    reusedEncodedBytes,
                    packing.newSegmentBytes,
                    packing.indexBytesUpperBound(),
                    packing.newSegmentCount,
                )
            } finally {
                try {
                    candidates?.close()
                } finally {
                    table.close()
                }
            }
        } finally {
            deleteOwnedScratch(workDirectory)
            diagnostics.info("CASC target inspection end elapsedMs=${(System.nanoTime() - inspectionStarted) / 1_000_000}")
        }
    }

    /**
     * Canonical missing-object packing shared by the generic planner and the
     * sourceless ordered planner. The 480-byte segment header, the 30-byte
     * local header, the 16 virtual reconstruction records counted per new
     * segment, the bucket counts, the 2M-record and per-bucket caps, the
     * 0..1023 archive-ID range, and every addExact overflow guard are
     * identical on both paths; only duplicate/collision detection differs
     * (disk-backed table vs. validated order). Entry creation and table
     * insertion stay with the generic caller, between [beginMissingObject]
     * and [commitMissingObject].
     */
    private class TargetPlanPacking(firstArchiveId: Int) {
        val bucketCounts = IntArray(16)
        var totalRecords = 0L
        var archiveId = -1
        var nextArchiveId = firstArchiveId
        var offset = SEGMENT_HEADER_SIZE.toLong()
        var archiveOpen = false
        var totalObjects = 0L
        var missingObjects = 0L
        var downloadBytes = 0L
        var newSegmentBytes = 0L
        var newSegmentCount = 0

        fun beginMissingObject(spec: IncrementalObjectSpec, bucket: Int): Long {
            val localObjectSize = Math.addExact(LOCAL_HEADER_SIZE.toLong(), spec.encodedSize)
            if (localObjectSize > ARCHIVE_LIMIT - SEGMENT_HEADER_SIZE) {
                throw IllegalArgumentException("CASC encoded object exceeds the supported archive limit")
            }
            val needsNewSegment = !archiveOpen || offset > ARCHIVE_LIMIT - localObjectSize
            val plannedId = if (needsNewSegment) nextArchiveId else archiveId
            if (needsNewSegment && plannedId !in 0..1023) {
                throw IllegalStateException("CASC archive id capacity is exhausted; refusing to overwrite old segments")
            }
            if (needsNewSegment) {
                val newTotalRecords = Math.addExact(totalRecords, 17L) // 16 reconstruction records plus this object.
                if (newTotalRecords > MAX_TOTAL_INDEX_ENTRIES) {
                    throw IllegalArgumentException("CASC V7 merged index exceeds the bounded total-record limit")
                }
                for (indexBucket in 0..15) {
                    val targetAddition = if (indexBucket == bucket) 1 else 0
                    requireIndexCapacity(indexBucket, Math.addExact(bucketCounts[indexBucket], 1 + targetAddition))
                }
                for (indexBucket in 0..15) bucketCounts[indexBucket] = Math.addExact(bucketCounts[indexBucket], 1)
                totalRecords = Math.addExact(totalRecords, 16L)
                archiveId = plannedId
                nextArchiveId = Math.addExact(plannedId, 1)
                offset = SEGMENT_HEADER_SIZE.toLong()
                archiveOpen = true
                newSegmentCount = Math.addExact(newSegmentCount, 1)
                newSegmentBytes = Math.addExact(newSegmentBytes, SEGMENT_HEADER_SIZE.toLong())
            } else {
                requireIndexCapacity(bucket, Math.addExact(bucketCounts[bucket], 1))
                if (totalRecords >= MAX_TOTAL_INDEX_ENTRIES) {
                    throw IllegalArgumentException("CASC V7 merged index exceeds the bounded total-record limit")
                }
            }
            return localObjectSize
        }

        fun commitMissingObject(spec: IncrementalObjectSpec, bucket: Int, localObjectSize: Long) {
            bucketCounts[bucket] = Math.addExact(bucketCounts[bucket], 1)
            totalRecords = Math.addExact(totalRecords, 1L)
            offset = Math.addExact(offset, localObjectSize)
            newSegmentBytes = Math.addExact(newSegmentBytes, localObjectSize)
            downloadBytes = Math.addExact(downloadBytes, spec.encodedSize)
            missingObjects = Math.addExact(missingObjects, 1L)
            totalObjects = Math.addExact(totalObjects, 1L)
        }

        fun indexBytesUpperBound(): Long = bucketCounts.fold(0L) { sum, count ->
            Math.addExact(sum, indexFileLength(count.toLong()))
        }
    }

    /**
     * Sourceless planning over an explicitly caller-ordered target manifest.
     * The caller must stream the complete admitted manifest in strictly
     * increasing EKey order (lowercase hex, the same ORDER BY ekey the
     * catalog cursor produces); the order is still validated per row and any
     * deviation is rejected, never trusted. With no source there are no
     * reusable physical candidates and no source reconstruction records, so
     * the disk-backed target table (used by the generic path for global
     * duplicate/collision detection) is provably redundant here: in strict
     * order, equal EKeys are adjacent (duplicate) and equal 9-byte prefixes
     * are adjacent (prefix collision), so comparing against the previous row
     * detects exactly the same violations with O(1) memory. No
     * target-records.bin, candidate hints, or verification temporary are
     * created; packing, bounds, costs, per-row cancellation, and the
     * TargetScanResult are exactly the generic ones via [TargetPlanPacking].
     * The generic [inspectTarget] keeps its arbitrary-sequence route: source
     * reuse and its broader duplicate/collision checks never use this path.
     */
    internal fun inspectTargetOrderedSourceNull(
        workDirectory: File,
        objects: Sequence<IncrementalObjectSpec>,
        cancelled: () -> Boolean,
        onObject: (IncrementalObjectSpec, Boolean) -> Unit,
    ): TargetScanResult {
        val inspectionStarted = System.nanoTime()
        diagnostics.info("CASC target inspection begin source=false ordered=true")
        validateTargetInspectionPaths(null, workDirectory)
        if (!workDirectory.mkdir()) throw IllegalStateException("cannot create fresh CASC target-inspection work directory")
        try {
            checkCancelled(cancelled)
            val packing = TargetPlanPacking(0)
            var previous: String? = null
            objects.forEach { originalSpec ->
                checkCancelled(cancelled)
                val spec = normalizeIncrementalSpec(originalSpec)
                validateIncrementalSpec(spec)
                val key = spec.ekey
                val prev = previous
                if (prev != null) {
                    if (key == prev) {
                        throw IllegalArgumentException("duplicate EKey in incremental manifest: ${spec.ekey}")
                    }
                    if (key < prev) {
                        throw IllegalStateException("CASC target manifest is not in strictly sorted EKey order; refusing unordered source-null plan")
                    }
                    if (key.substring(0, 18) == prev.substring(0, 18)) {
                        throw EKeyPrefixCollisionException("CASC short EKey prefix collision in ordered target manifest; refusing ambiguous plan")
                    }
                }
                previous = key
                val bucket = computeBucket(spec.ekey.hexBytes())
                val localObjectSize = packing.beginMissingObject(spec, bucket)
                packing.commitMissingObject(spec, bucket, localObjectSize)
                onObject(originalSpec, false)
            }
            return TargetScanResult(
                packing.totalObjects,
                0L,
                packing.missingObjects,
                packing.downloadBytes,
                0L,
                packing.newSegmentBytes,
                packing.indexBytesUpperBound(),
                packing.newSegmentCount,
            )
        } finally {
            deleteOwnedScratch(workDirectory)
            diagnostics.info("CASC target inspection end elapsedMs=${(System.nanoTime() - inspectionStarted) / 1_000_000}")
        }
    }

    private fun writeIncrementalInternal(
        sourceDataFiles: File?,
        outputDataFiles: File,
        objects: Sequence<IncrementalObjectSpec>,
        sourceFor: (IncrementalObjectSpec) -> File?,
        cancelled: () -> Boolean,
        canonicalTarget: Boolean,
        checkpointIdentity: String? = null,
        checkpointDirectory: File? = null,
        sourceConsumed: (IncrementalObjectSpec, File) -> Unit = { _, _ -> },
        replayReaderFactory: ((() -> Boolean) -> CascReplayReader)? = null,
        overlapCoordinator: CascOverlapCoordinator? = null,
        overlapOfficialStream: OverlapOfficialStream? = null,
        commitFlushRequest: AtomicReference<(() -> Boolean)?>? = null,
        targetObjectCount: Int = 0,
        targetMembership: ((String) -> IndexedValue<IncrementalObjectSpec>)? = null,
    ): IncrementalResult {
        val writerStarted = System.nanoTime()
        var firstSourceRequested = false
        diagnostics.info("CASC writer begin resumable=${checkpointIdentity != null}")
        if (checkpointIdentity == null) {
            require(sourceDataFiles != null) { "legacy incremental writer requires an existing CASC source" }
            validateIncrementalPaths(sourceDataFiles, outputDataFiles)
        } else {
            validateResumableIncrementalPaths(sourceDataFiles, outputDataFiles, requireNotNull(checkpointDirectory))
        }
        require(if (targetMembership == null) targetObjectCount == 0 else
            checkpointIdentity != null && canonicalTarget &&
                targetObjectCount > 0 && targetObjectCount.toLong() <= MAX_SCANNED_INDEX_RECORDS) {
            "checkpoint membership count is outside its bounded target"
        }
        if (targetMembership != null) {
            // Authenticate complete existing frames read-only before output
            // preparation can recover finalizing indexes or discard owned
            // scratch. The load pass repeats this check against its own FD.
            inspectResumableCheckpoint(
                outputDataFiles,
                requireNotNull(checkpointDirectory),
                emptySequence(),
                cancelled,
                requireNotNull(checkpointIdentity),
                targetObjectCount,
                targetMembership,
            )
        }
        val source = sourceDataFiles?.let { inspectExistingIndexFiles(it, cancelled, allowCandidateHints = canonicalTarget) }
            ?: ExistingIndexSet(emptyList(), LongArray(16), -1, 0)
        if (source.generations.any { it < 0 || it >= 0xffffffffL }) {
            if (sourceDataFiles != null || source.generations.any { it != 0L }) {
                throw IllegalStateException("CASC V7 index generation capacity is exhausted")
            }
        }
        if (checkpointIdentity == null) {
            if (!outputDataFiles.mkdirs()) throw IllegalStateException("cannot create isolated CASC incremental output")
        } else {
            prepareResumableOutput(outputDataFiles, requireNotNull(checkpointDirectory), checkpointIdentity)
        }
        val scratch = File(outputDataFiles, INCREMENTAL_SCRATCH_NAME)
        if (!scratch.mkdir()) throw IllegalStateException("cannot create owned CASC index work directory")
        val table = try {
            DiskIndexTable(
                File(scratch, "records.bin"),
                source.initialRecordEstimate,
                cancelled,
            )
        } catch (t: Throwable) {
            deleteOwnedScratch(scratch)
            throw t
        }
        val candidates = try {
            if (canonicalTarget && sourceDataFiles != null) {
                CandidateHintStore.create(
                    File(scratch, "candidate-prefixes.bin"),
                    File(scratch, "candidate-hints.bin"),
                    source.initialRecordEstimate,
                    cancelled,
                )
            } else {
                null
            }
        } catch (t: Throwable) {
            try {
                table.close()
            } finally {
                deleteOwnedScratch(scratch)
            }
            throw t
        }
        // The resumable writer owns a private identity-bound checkpoint directory:
        // its verification temporary lives there, never on the destination
        // volume. The legacy writer without a private checkpoint keeps the
        // output-local file.
        val temporary = if (checkpointIdentity != null) {
            File(requireNotNull(checkpointDirectory), INCREMENTAL_OBJECT_TEMP_NAME)
        } else {
            File(outputDataFiles, INCREMENTAL_OBJECT_TEMP_NAME)
        }
        var archive: BufferedOutputStream? = null
        var archiveId = -1
        var offset = SEGMENT_HEADER_SIZE.toLong()
        var nextArchiveId = source.maxArchiveId + 1
        var reused = 0L
        var written = 0L
        var segmentCount = 0
        var candidateHintsExamined = 0L
        var candidateHintsRejected = 0L
        // Cumulative commit-stage timings for new (downloaded) objects only.
        // Reused, checkpointed, and candidate-reused objects never reach the
        // timed region below, so the aggregates isolate: sourceResolveMs (the
        // caller-supplied source lookup per object, which for the updater is
        // its storage revalidation plus the prefetch-future wait, never a
        // pure network figure), privateVerifyMs (copy plus EKey/BLTE/content
        // verification into the owned private temporary), appendAndForceMs
        // (owned segment open, object append and bounded group data force), and
        // checkpointMs (bounded group frame append/force). Plain monotonic sums
        // of a few Longs; reported every batch below.
        var newObjectBytes = 0L
        var sourceResolveMs = 0L
        var privateVerifyMs = 0L
        var appendAndForceMs = 0L
        var checkpointMs = 0L
        // Cumulative staged-replay verification breakdown for successful
        // flags=2 cases only. One holder per writer, never per object.
        val stagedReplayTimings = StagedReplayTimings()
        // Cold-replay fast path: a single reader owned by this writer call,
        // created lazily on the first flags=2 object after tail recovery and
        // closed before any new-source fetch or segment write. A closed
        // instance is never reused: a later flags=2 object builds a fresh one
        // from the factory, never from a stale FD.
        var replayReader: CascReplayReader? = null
        var replayFailure: Throwable? = null
        fun replayReaderOrCreate(): CascReplayReader {
            replayReader?.let { return it }
            val factory = replayReaderFactory
                ?: throw IllegalStateException("CASC replay reader is unavailable")
            checkCancelled(cancelled)
            return factory(cancelled).also { replayReader = it }
        }
        fun closeReplayReader() {
            val reader = replayReader ?: return
            replayReader = null
            reader.close()
        }
        val replayMode = if (replayReaderFactory != null) "replay-reader" else "temp-copy"
        val bucketCounts = IntArray(16)
        var checkpoint: ResumableCheckpoint? = null
        var checkpointObjectCount = 0L
        var checkpointObjectsReused = 0L
        val pendingResumeCommits = ArrayList<PendingResumeCommit>(RESUMABLE_COMMIT_GROUP_OBJECTS)
        val targetOrdinalsSeen = if (targetMembership == null) null else java.util.BitSet(targetObjectCount)
        val flushPendingResumeCommits: () -> Boolean = {
            run {
                if (pendingResumeCommits.isEmpty()) {
                    false
                } else {
                    val batch = pendingResumeCommits.toList()
                    val batchArchiveId = batch.first().archiveId
                    if (batch.size > RESUMABLE_COMMIT_GROUP_OBJECTS || batch.any { it.archiveId != batchArchiveId }) {
                        throw IllegalStateException("resumable CASC commit group exceeds its object or segment bound")
                    }
                    val segment = archive
                        ?: throw IllegalStateException("resumable CASC commit group has no open segment")
                    val dataForceStarted = System.nanoTime()
                    segment.flush()
                    forceFile(File(outputDataFiles, "data.%03d".format(batchArchiveId)))
                    appendAndForceMs += (System.nanoTime() - dataForceStarted) / 1_000_000

                    val checkpointStarted = System.nanoTime()
                    appendResumeObjects(requireNotNull(checkpoint), batch)
                    checkpointMs += (System.nanoTime() - checkpointStarted) / 1_000_000
                    pendingResumeCommits.clear()
                    for (commit in batch) sourceConsumed(commit.originalSpec, commit.raw)
                    true
                }
            }
        }
        // Sealed-prefix overlap state. Only the writer thread mutates the
        // table and the checkpoint; the verifier thread owns its reader and
        // reports through the coordinator. Both join before finalization.
        var overlap = false
        var overlapFreeze: OverlapPrefixFreeze? = null
        var verifierThread: Thread? = null
        var verifierPeerCancel: AtomicBoolean? = null
        var prefixSkipped = 0L
        fun joinOverlapVerifier(): Boolean {
            val thread = verifierThread ?: return false
            var interrupted = Thread.interrupted()
            if (interrupted) verifierPeerCancel?.set(true)
            while (thread.isAlive) {
                try {
                    thread.join()
                } catch (_: InterruptedException) {
                    interrupted = true
                    verifierPeerCancel?.set(true)
                }
            }
            verifierThread = null
            if (interrupted) Thread.currentThread().interrupt()
            return interrupted
        }
        try {
            try {
                if (canonicalTarget) {
                    if (sourceDataFiles != null) buildCandidateHints(source, candidates!!, cancelled)
                } else {
                    readExistingIndexRecords(source, requireNotNull(sourceDataFiles), table, cancelled).copyInto(bucketCounts)
                }
                var totalRecords = table.recordCount
                val sourceSegmentHeaders = arrayOfNulls<List<ExistingIndexRecord>>(1024)
                val sourceSegmentHeaderChecked = BooleanArray(1024)
                val outputSegmentHeadersAdded = BooleanArray(1024)
                if (checkpointIdentity != null) {
                    val checkpointStarted = System.nanoTime()
                    val loaded = loadResumableCheckpoint(
                        outputDataFiles,
                        requireNotNull(checkpointDirectory),
                        checkpointIdentity,
                        source.maxArchiveId,
                        source.generations,
                        table,
                        bucketCounts,
                        cancelled,
                        overlapCoordinator?.snapshotWantedKeys() ?: emptySet(),
                        targetObjectCount,
                        targetMembership,
                    )
                    diagnostics.info("CASC checkpoint loaded objects=${loaded.objectCount} elapsedMs=${(System.nanoTime() - checkpointStarted) / 1_000_000}")
                    checkpoint = loaded.checkpoint
                    archiveId = loaded.lastArchiveId
                    offset = loaded.lastOffset
                    nextArchiveId = loaded.nextArchiveId
                    segmentCount = loaded.segmentCount
                    checkpointObjectCount = loaded.objectCount
                    loaded.segmentIds.forEach { outputSegmentHeadersAdded[it] = true }
                    totalRecords = table.recordCount
                    // Sealed-prefix overlap: sourceless resumable with a
                    // checkpointed prefix and an explicit reader factory. The
                    // coordinator and either the legacy official stream or the
                    // exact membership lookup must be present; anything else is
                    // a wiring error, never a silent fallback to serial replay.
                    // An armed coordinator with an empty checkpoint is equally express: the
                    // eligibility peek runs before any write, so a mismatch
                    // here fails closed instead of downgrading mid-run.
                    if (overlapCoordinator != null || overlapOfficialStream != null) {
                        if (overlapCoordinator == null ||
                            (overlapOfficialStream == null && targetMembership == null) ||
                            sourceDataFiles != null || !canonicalTarget || replayReaderFactory == null
                        ) {
                            throw IllegalStateException("overlap CASC run is partially armed; refusing silent fallback to serial replay")
                        }
                        if (loaded.objectCount <= 0) {
                            throw IllegalStateException("overlap CASC run armed without a checkpointed prefix; refusing silent fallback")
                        }
                    }
                    overlap = overlapCoordinator != null && loaded.objectCount > 0
                    if (overlap) {
                        val coordinator = requireNotNull(overlapCoordinator)
                        val freezeStarted = System.nanoTime()
                        // Freeze AFTER the single load/recovery pass: the load
                        // above already truncated the torn tail exactly like
                        // the serial path. From here no old prefix file is
                        // truncated or written; new objects start at a fresh
                        // ID above the frozen maximum with offset 480. The
                        // last frozen segment stays sealed: it is never opened
                        // for append while the verifier reads it.
                        val checkpointFile = loaded.checkpoint.file
                        val freeze = OverlapPrefixFreeze(
                            count = loaded.objectCount,
                            lastEkey = loaded.lastEkey,
                            cpByteEnd = Files.size(checkpointFile.toPath()),
                            cpFileKey = checkpointFileKey(checkpointFile),
                            identityHash = loaded.checkpoint.identityHash,
                            firstArchiveId = loaded.segmentIds.firstOrNull() ?: -1,
                            segmentEnds = loaded.segmentEnds,
                            rawKeyRecords = loaded.rawKeyRecords,
                        )
                        overlapFreeze = freeze
                        coordinator.publishRawKeyRecords(loaded.rawKeyRecords)
                        coordinator.prefixTotal.set(freeze.count)
                        verifierPeerCancel = coordinator.peerCancel
                        verifierThread = Thread({
                            try {
                                runOverlapPrefixVerify(
                                    outputDataFiles,
                                    checkpointFile,
                                    freeze,
                                    overlapOfficialStream,
                                    requireNotNull(replayReaderFactory),
                                    coordinator,
                                    { coordinator.peerCancel.get() || cancelled() },
                                    targetObjectCount,
                                    targetMembership,
                                )
                            } catch (t: Throwable) {
                                coordinator.failure.compareAndSet(null, t)
                            }
                        }, "forever-prefix-verify").apply { isDaemon = true; start() }
                        diagnostics.info("CASC overlap freeze prefixObjects=${freeze.count} segments=${freeze.segmentEnds.size} cpByteEnd=${freeze.cpByteEnd} elapsedMs=${(System.nanoTime() - freezeStarted) / 1_000_000}")
                        archiveId = -1
                        offset = SEGMENT_HEADER_SIZE.toLong()
                        nextArchiveId = freeze.prefixMaxId + 1
                    } else if (loaded.lastArchiveId >= 0) {
                        val path = File(outputDataFiles, "data.%03d".format(loaded.lastArchiveId))
                        archive = openAppendArchive(path)
                    }
                }
                if (checkpointIdentity != null) commitFlushRequest?.set(flushPendingResumeCommits)
                for (originalSpec in objects) {
                    checkCancelled(cancelled)
                    if (overlap) overlapCoordinator?.failure?.get()?.let { throw it }
                    val spec = normalizeIncrementalSpec(originalSpec)
                    validateIncrementalSpec(spec)
                    if (targetOrdinalsSeen != null) {
                        verifyTargetMembership(
                            spec,
                            targetObjectCount,
                            requireNotNull(targetMembership),
                            targetOrdinalsSeen,
                        )
                    }
                    val ekeyBytes = spec.ekey.hexBytes()
                    val prefix = spec.ekey.substring(0, 18)
                    val bucket = computeBucket(ekeyBytes)
                    val replayIndexStarted = System.nanoTime()
                    val existing = table.find(ekeyBytes)
                    if (existing?.targetSeen == true) {
                        throw IllegalArgumentException("duplicate EKey in incremental manifest: ${spec.ekey}")
                    }
                    if (existing != null) {
                        existing.targetSeen = true
                        table.put(existing)
                    }
                    val replayIndexNanos = System.nanoTime() - replayIndexStarted

                    if (existing?.flags == 1) {
                        throw IllegalStateException("CASC object EKey conflicts with a segment reconstruction record")
                    }
                    if (canonicalTarget && existing?.flags == 2) {
                        if (overlap) {
                            // Sealed prefix: the metadata claim is admitted
                            // into the table but its bytes stay UNVERIFIED
                            // here. Only the verifier thread proves them, one
                            // sealed group at a time; the joint barrier admits
                            // the count only after every group acked. The
                            // writer never re-verifies, never yields this
                            // entry to fetch/commit, and never retires its
                            // leftover raw early: retirement fires from group
                            // acks or not at all before the barrier.
                            overlapCoordinator?.failure?.get()?.let { throw it }
                            prefixSkipped = Math.addExact(prefixSkipped, 1L)
                            continue
                        }
                        val staged = if (replayReaderFactory != null) {
                            verifyStagedReplayObject(
                                outputDataFiles,
                                replayReaderOrCreate(),
                                existing.copy(flags = 0),
                                spec,
                                cancelled,
                                stagedReplayTimings,
                            )
                        } else {
                            verifyExistingIncrementalObject(
                                outputDataFiles,
                                existing.copy(flags = 0),
                                spec,
                                temporary,
                                cancelled,
                                stagedReplayTimings,
                            )
                        }
                        if (!staged) throw IllegalStateException("checkpointed CASC object failed EKey, BLTE, content-key, or size verification")
                        stagedReplayTimings.replayIndexNanos = Math.addExact(stagedReplayTimings.replayIndexNanos, replayIndexNanos)
                        reused++
                        checkpointObjectsReused = Math.addExact(checkpointObjectsReused, 1L)
                        if ((checkpointObjectsReused and 0x3ffL) == 0L) {
                            // Staged replay wall-clock breakdown (all ms, cumulative over successful flags=2 verifications only):
                            // replayIndexMs = table.find + targetSeen put before the flags=2 branch;
                            // openHeaderMs = combined temp-prep delete + file-name format + source NOFOLLOW/isRegular + channel open + size + 30-byte header read/validate + full EKey from header (not pure I/O latency);
                            // copyMs = private-temp open/close plus archive reads and temp writes;
                            // ekeyMs = encodedEkey hash of the private temp;
                            // blteMs = ForeverBlte.verifyEncodedObject;
                            // rangeVerifyMs = ForeverBlte.verifyEncodedRange over the bounded replay window (replay-reader mode only);
                            // bindingMs = pre/post path-vs-FD binding plus the 30-byte staged header proof (replay-reader mode only);
                            // cleanupMs = private-temp delete in finally (temp-copy mode only);
                            // totalVerifyMs = whole verify wall per success (accounting only, not a sum of phases).
                            diagnostics.info("CASC staged verification mode=$replayMode objects=$checkpointObjectsReused elapsedMs=${(System.nanoTime() - writerStarted) / 1_000_000} replayIndexMs=${stagedReplayTimings.replayIndexNanos / 1_000_000} openHeaderMs=${stagedReplayTimings.openHeaderNanos / 1_000_000} copyMs=${stagedReplayTimings.copyNanos / 1_000_000} ekeyMs=${stagedReplayTimings.ekeyNanos / 1_000_000} blteMs=${stagedReplayTimings.blteNanos / 1_000_000} cleanupMs=${stagedReplayTimings.cleanupNanos / 1_000_000} rangeVerifyMs=${stagedReplayTimings.rangeVerifyNanos / 1_000_000} bindingMs=${stagedReplayTimings.bindingNanos / 1_000_000} totalVerifyMs=${stagedReplayTimings.totalVerifyNanos / 1_000_000}")
                        }
                        continue
                    } else if (canonicalTarget && existing == null && sourceDataFiles != null) {
                        // Leaving the staged prefix: the replay FD must not
                        // stay open across candidate verification or writes.
                        closeReplayReader()
                        val candidateMatch = findCanonicalCandidate(
                            sourceDataFiles!!,
                            candidates!!,
                            spec,
                            temporary,
                            cancelled,
                            table,
                            bucketCounts,
                            totalRecords.toLong(),
                            outputSegmentHeadersAdded,
                            sourceSegmentHeaders,
                            sourceSegmentHeaderChecked,
                        )
                        val candidateSearch = candidateMatch.search
                        candidateHintsExamined += candidateSearch.examined
                        candidateHintsRejected += candidateSearch.rejected
                        val verified = candidateSearch.entry
                        if (verified != null) {
                            candidateMatch.reconstructionRecords.forEach { generated ->
                                if (!table.put(generated)) throw IllegalStateException("validated source segment reconstruction EKey already exists")
                                bucketCounts[computeBucket(generated.entry.key, 1)]++
                                totalRecords++
                            }
                            outputSegmentHeadersAdded[verified.entry.archiveId] = true
                            val canonicalRecord = ExistingIndexRecord(
                                spec.ekey,
                                verified.entry,
                                0,
                                Long.MAX_VALUE,
                                targetSeen = true,
                            )
                            if (!table.put(canonicalRecord)) throw IllegalStateException("canonical target EKey already exists")
                            bucketCounts[bucket]++
                            totalRecords++
                            reused++
                            continue
                        }
                    } else if (existing != null && checkpointIdentity == null) {
                        // Legacy source reuse never shares the staged prefix;
                        // a live replay reader (only possible with an explicit
                        // factory) is closed before falling back to the copy
                        // verifier, and a failed reuse still drops to the
                        // download path below with no FD held.
                        closeReplayReader()
                        if (verifyExistingIncrementalObject(requireNotNull(sourceDataFiles), existing, spec, temporary, cancelled)) {
                            reused++
                            continue
                        }
                    }

                    // A new download follows: no replay FD may be held open
                    // during source resolution, verification, or appends.
                    closeReplayReader()
                    val total = LOCAL_HEADER_SIZE.toLong() + spec.encodedSize
                    if (total > ARCHIVE_LIMIT - SEGMENT_HEADER_SIZE) {
                        throw IllegalArgumentException("CASC encoded object exceeds the supported archive limit")
                    }
                    val needsNewSegment = archive == null || offset > ARCHIVE_LIMIT - total
                    val plannedId = if (needsNewSegment) nextArchiveId else archiveId
                    if (needsNewSegment && plannedId !in 0..1023) {
                        throw IllegalStateException("CASC archive id capacity is exhausted; refusing to overwrite old segments")
                    }

                    val plannedSegment = if (needsNewSegment) {
                        val pathHash = MessageDigest.getInstance("MD5").digest(outputDataFiles.absolutePath.toByteArray(Charsets.UTF_8))
                        val bytes = segmentHeader(plannedId, pathHash)
                        val headerRecords = segmentIndexRecords(bytes, plannedId)
                        val additions = IntArray(16)
                        val plannedPrefixes = ArrayList<String>(headerRecords.size)
                        headerRecords.forEach { generated ->
                            val generatedKey = generated.ekey.hexBytes()
                            val prior = table.find(generatedKey)
                            if (prior != null) {
                                if (prior.ekey != generated.ekey) {
                                    throw IllegalStateException("CASC short EKey prefix collision in generated segment header")
                                }
                                throw IllegalStateException("generated CASC segment header EKey already exists")
                            }
                            if (generated.ekey == spec.ekey || plannedPrefixes.any { it.substring(0, 18) == generated.ekey.substring(0, 18) }) {
                                throw IllegalStateException("generated CASC segment header EKey already exists")
                            }
                            plannedPrefixes += generated.ekey
                            additions[computeBucket(generated.entry.key, 1)]++
                        }
                        if (plannedPrefixes.any { it.substring(0, 18) == prefix && it != spec.ekey }) {
                            throw IllegalStateException("CASC short EKey prefix collision in generated segment header")
                        }
                        var totalAdditions = if (existing == null) 1 else 0
                        for (indexBucket in 0..15) {
                            val targetAddition = if (indexBucket == bucket && existing == null) 1 else 0
                            requireIndexCapacity(indexBucket, bucketCounts[indexBucket] + additions[indexBucket] + targetAddition)
                            totalAdditions += additions[indexBucket]
                        }
                        if (totalRecords.toLong() + totalAdditions > MAX_TOTAL_INDEX_ENTRIES) {
                            throw IllegalArgumentException("CASC V7 merged index exceeds the bounded total-record limit")
                        }
                        bytes to headerRecords
                    } else {
                        if (existing == null) requireIndexCapacity(bucket, bucketCounts[bucket] + 1)
                        if (existing == null && totalRecords >= MAX_TOTAL_INDEX_ENTRIES) {
                            throw IllegalArgumentException("CASC V7 merged index exceeds the bounded total-record limit")
                        }
                        null
                    }

                    overlapCoordinator?.failure?.get()?.let { throw it }
                    if (!firstSourceRequested) {
                        firstSourceRequested = true
                        if (overlap) {
                            // Overlap mode reports live verifier progress,
                            // not the frozen prefix count: the download starts
                            // while the prefix is still PENDING, and the tail
                            // before V completion is expected, not a shortcut.
                            val freeze = requireNotNull(overlapFreeze)
                            val acked = requireNotNull(overlapCoordinator).ackedObjects.get()
                            val pending = (freeze.count - acked).coerceAtLeast(0L)
                            diagnostics.info("CASC first source request overlap=true checkpointVerified=$acked checkpointPending=$pending elapsedMs=${(System.nanoTime() - writerStarted) / 1_000_000}")
                        } else {
                            diagnostics.info("CASC first source request checkpointVerified=$checkpointObjectsReused elapsedMs=${(System.nanoTime() - writerStarted) / 1_000_000}")
                        }
                    }
                    val sourceResolveStarted = System.nanoTime()
                    val raw = sourceFor(originalSpec)
                        ?: throw IllegalStateException("CASC object ${spec.ekey} is missing or corrupt and has no verified replacement source")
                    sourceResolveMs += (System.nanoTime() - sourceResolveStarted) / 1_000_000
                    val privateVerifyStarted = System.nanoTime()
                    writeVerifiedSourceToTemporary(raw, temporary, spec, cancelled)
                    privateVerifyMs += (System.nanoTime() - privateVerifyStarted) / 1_000_000
                    try {
                        if (plannedSegment != null && checkpointIdentity != null) flushPendingResumeCommits()
                        val appendStarted = System.nanoTime()
                        run {
                            if (plannedSegment != null) {
                                archive?.close()
                                archive = null
                                val openedFile = File(outputDataFiles, "data.%03d".format(plannedId))
                                if (openedFile.exists() || Files.isSymbolicLink(openedFile.toPath())) {
                                    throw IllegalStateException("incremental CASC output segment already exists")
                                }
                                val stream = BufferedOutputStream(openOwnedOutput(openedFile, false), BUFFER_SIZE)
                                archive = stream
                                stream.write(plannedSegment.first)
                                stream.flush()
                                forceFile(openedFile)
                                plannedSegment.second.forEach { generated ->
                                    if (!table.put(generated)) throw IllegalStateException("generated CASC segment header EKey already exists")
                                    val generatedBucket = computeBucket(generated.entry.key, 1)
                                    bucketCounts[generatedBucket]++
                                    totalRecords++
                                }
                                if (canonicalTarget) outputSegmentHeadersAdded[plannedId] = true
                                archiveId = plannedId
                                nextArchiveId = plannedId + 1
                                offset = SEGMENT_HEADER_SIZE.toLong()
                                segmentCount++
                            }

                            val currentArchive = archive ?: throw IllegalStateException("incremental CASC segment was not opened")
                            val currentId = archiveId
                            val objectOffset = offset
                            val localHeader = localHeader(ekeyBytes, total, currentId * ARCHIVE_LIMIT + objectOffset, 0)
                            currentArchive.write(localHeader)
                            copyFile(temporary, currentArchive, cancelled)
                            val entry = IndexEntry(ekeyBytes.copyOfRange(0, 9), currentId, objectOffset, total)
                            val record = ExistingIndexRecord(spec.ekey, entry, 0, Long.MAX_VALUE, targetSeen = true)
                            if (table.put(record)) {
                                bucketCounts[bucket]++
                                totalRecords++
                            }
                            offset += total
                            written++
                            newObjectBytes = Math.addExact(newObjectBytes, spec.encodedSize)
                            appendAndForceMs += (System.nanoTime() - appendStarted) / 1_000_000
                            if (checkpointIdentity != null) {
                                if (pendingResumeCommits.size >= RESUMABLE_COMMIT_GROUP_OBJECTS) {
                                    throw IllegalStateException("resumable CASC commit group exceeded its object bound")
                                }
                                pendingResumeCommits += PendingResumeCommit(
                                    originalSpec,
                                    spec,
                                    raw,
                                    currentId,
                                    entry.archiveOffset,
                                )
                                if (pendingResumeCommits.size >= RESUMABLE_COMMIT_GROUP_OBJECTS) {
                                    flushPendingResumeCommits()
                                }
                            }
                        }
                        if (written % COMMIT_TIMING_BATCH_OBJECTS == 0L) {
                            diagnostics.info("CASC commit batch newObjects=$written newBytes=$newObjectBytes sourceResolveMs=$sourceResolveMs privateVerifyMs=$privateVerifyMs appendAndForceMs=$appendAndForceMs checkpointMs=$checkpointMs elapsedMs=${(System.nanoTime() - writerStarted) / 1_000_000}")
                        }
                    } finally {
                        if (temporary.exists() && !temporary.delete()) {
                            throw IllegalStateException("cannot remove CASC incremental object temporary")
                        }
                    }
                }
                if (targetOrdinalsSeen != null && targetOrdinalsSeen.cardinality() != targetObjectCount) {
                    throw IllegalStateException("incremental manifest does not cover its complete admitted target")
                }
                if (checkpointIdentity != null) flushPendingResumeCommits()
                if (overlap) {
                    val coordinator = requireNotNull(overlapCoordinator)
                    val freeze = requireNotNull(overlapFreeze)
                    val barrierStarted = System.nanoTime()
                    if (joinOverlapVerifier()) throw java.util.concurrent.CancellationException("client provisioning cancelled")
                    coordinator.failure.get()?.let { throw it }
                    // Joint publish barrier: every frozen prefix object
                    // group-acked by the verifier AND every new object durably
                    // committed by this thread. No FINISH frame, no indices,
                    // and no stage publication happen before both hold. The
                    // table `reused` count admits the old metadata only here,
                    // after proof, never as a pre-verified claim during the run.
                    val acked = coordinator.ackedObjects.get()
                    if (acked != freeze.count || prefixSkipped != freeze.count) {
                        throw IllegalStateException("CASC sealed prefix verification incomplete: acked=$acked skipped=$prefixSkipped frozen=${freeze.count}")
                    }
                    reused = Math.addExact(reused, freeze.count)
                    diagnostics.info("CASC prefix verification complete objects=${freeze.count} bytes=${coordinator.ackedBytes.get()} elapsedMs=${(System.nanoTime() - barrierStarted) / 1_000_000}")
                }
                if (written > 0L && written % COMMIT_TIMING_BATCH_OBJECTS != 0L) {
                    diagnostics.info("CASC commit final newObjects=$written newBytes=$newObjectBytes sourceResolveMs=$sourceResolveMs privateVerifyMs=$privateVerifyMs appendAndForceMs=$appendAndForceMs checkpointMs=$checkpointMs elapsedMs=${(System.nanoTime() - writerStarted) / 1_000_000}")
                }
                if (checkpointIdentity != null && !overlap && checkpointObjectsReused != checkpointObjectCount) {
                    throw IllegalStateException("resumable CASC stage contains objects outside the complete target manifest")
                }
            } catch (t: Throwable) {
                var failure = t
                if (t is java.util.concurrent.CancellationException && checkpointIdentity != null) {
                    try {
                        flushPendingResumeCommits()
                    } catch (flushFailure: Throwable) {
                        if (flushFailure !== t) flushFailure.addSuppressed(t)
                        failure = flushFailure
                    }
                }
                if (overlap) {
                    verifierPeerCancel?.set(true)
                    joinOverlapVerifier()
                    val verifierFailure = overlapCoordinator?.failure?.get()
                    if (verifierFailure != null && verifierFailure !== failure &&
                        verifierFailure !is java.util.concurrent.CancellationException
                    ) {
                        failure.addSuppressed(verifierFailure)
                    }
                }
                replayFailure = failure
                throw failure
            } finally {
                // Cancel, error, or normal end always runs all three owned
                // cleanups here, even when the staged prefix was never
                // entered (null reader close is a no-op). The first failure
                // is preserved and every later failure is suppressed into
                // it; a cleanup failure alone (no body failure) propagates
                // only after all three cleanups were attempted, so a reader
                // close failure can never skip the archive close or the
                // temporary cleanup, and an archive close failure can never
                // mask the primary outcome either.
                var cleanupFailure: Throwable? = null
                commitFlushRequest?.compareAndSet(flushPendingResumeCommits, null)
                try {
                    closeReplayReader()
                } catch (closeError: Throwable) {
                    cleanupFailure = closeError
                }
                run {
                    try {
                        archive?.close()
                    } catch (archiveError: Throwable) {
                        if (cleanupFailure == null) cleanupFailure = archiveError
                        else cleanupFailure.addSuppressed(archiveError)
                    }
                }
                try {
                    if (temporary.exists()) temporary.delete()
                } catch (temporaryError: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = temporaryError
                    else cleanupFailure.addSuppressed(temporaryError)
                }
                val primary = replayFailure
                if (primary != null) {
                    if (cleanupFailure != null) primary.addSuppressed(cleanupFailure)
                } else if (cleanupFailure != null) {
                    throw cleanupFailure
                }
            }
            if (checkpointIdentity != null) appendResumeFinalizing(requireNotNull(checkpoint))
            writeIncrementalIndices(table, outputDataFiles, scratch, source.generations, bucketCounts, cancelled)
            return IncrementalResult(
                reused,
                written,
                segmentCount,
                candidateHintsExamined,
                candidateHintsRejected,
                candidates?.hintCount ?: 0,
            )
        } finally {
            try {
                candidates?.close()
            } finally {
                try {
                    table.close()
                } finally {
                    deleteOwnedScratch(scratch)
                }
            }
        }
    }

    internal data class RuntimeObjectSpec(val ekey: String, val contentKey: String, val decodedSize: Long)

    internal fun buildInfo(fields: org.json.JSONObject): ByteArray {
        val branch = fields.getString("branch")
        val build = fields.getString("build_key")
        val cdn = fields.getString("cdn_key")
        val install = fields.getString("install_key")
        val path = fields.getString("cdn_path")
        val hostsArray = fields.getJSONArray("cdn_hosts")
        val hosts = (0 until hostsArray.length()).map { hostsArray.getString(it) }
        val servers = hosts.flatMap { listOf("http://$it/?maxhosts=4", "https://$it/?maxhosts=4&fallback=1") }.joinToString(" ")
        val version = fields.getString("version")
        val product = fields.getString("product")
        val platform = fields.getString("platform")
        val arch = fields.getString("architecture")
        val locale = fields.getString("locale")
        val region = fields.getString("region").uppercase()
        val tags = "$platform $arch ${region}? $locale speech?:$platform $arch ${region}? $locale text?"
        val header = "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|CDN Servers!STRING:0|Tags!STRING:0|Armadillo!STRING:0|Last Activated!STRING:0|Version!STRING:0|Product!STRING:0"
        val values = listOf(branch, "1", build, cdn, install, "", path, hosts.joinToString(" "), servers, tags, "", "", version, product)
        return (header + "\n" + values.joinToString("|") + "\n").toByteArray(Charsets.UTF_8)
    }

    private data class IndexEntry(val key: ByteArray, val archiveId: Int, val archiveOffset: Long, val size: Long)

    private data class ExistingIndexRecord(
        val ekey: String,
        val entry: IndexEntry,
        val flags: Int,
        val generation: Long,
        var targetSeen: Boolean = false,
    )

    private data class IncrementalIndexFile(val file: File, val bucket: Int, val generation: Long, val recordCount: Int)

    private data class ExistingIndexSet(
        val files: List<IncrementalIndexFile>,
        val generations: LongArray,
        val maxArchiveId: Int,
        val initialRecordEstimate: Int,
    )

    private data class ResumeLoadResult(
        val checkpoint: ResumableCheckpoint,
        val lastArchiveId: Int,
        val lastOffset: Long,
        val nextArchiveId: Int,
        val segmentCount: Int,
        val segmentIds: List<Int>,
        val objectCount: Long,
        val lastEkey: String?,
        val segmentEnds: Map<Int, Long>,
        val rawKeyRecords: Map<String, OverlapRawKeyRecord>,
    )

    private data class ResumableCheckpoint(val file: File, val identityHash: ByteArray)

    private data class ResumeObject(
        val ekey: String,
        val contentKey: String,
        val encodedSize: Long,
        val decodedSize: Long,
        val archiveId: Int,
        val offset: Long,
    )

    /**
     * Cumulative wall-clock breakdown for successful staged (flags=2) replay
     * verifications owned by one writer. All fields are nanoseconds, Long-only
     * sums, accumulated only when verification returns true. The temp-copy
     * phases (openHeader/copy/ekey/blte/cleanup) stay zero in replay-reader
     * mode and are never repurposed: reader mode reports rangeVerify
     * (ForeverBlte.verifyEncodedRange over the bounded window) and binding
     * (pre/post path-vs-FD binding plus the 30-byte staged header proof).
     * Failures (false) and cancellations never accumulate. Private diagnostic
     * state only, never an API config.
     */
    private class StagedReplayTimings {
        var replayIndexNanos = 0L
        var openHeaderNanos = 0L
        var copyNanos = 0L
        var ekeyNanos = 0L
        var blteNanos = 0L
        var cleanupNanos = 0L
        var rangeVerifyNanos = 0L
        var bindingNanos = 0L
        var totalVerifyNanos = 0L
    }

    private class EKeyPrefixCollisionException(message: String) : IllegalStateException(message)

    internal data class V7UpdateSectionDiagnostic(
        val reasonCode: String,
        val classification: String,
        val updateStart: Int,
        val pageSize: Int?,
        val pageCount: Int,
        val nonemptyRecordCount: Int,
        val validGuardRecordCount: Int,
        val statusCounts: Map<Int, Int>,
        val pageLocalDuplicatePrefixGroups: Int,
        val pageLocalDuplicateExtraRecords: Int,
        val sortedPrefixOverlapCount: Int,
        val zeroPadding: Boolean,
        val reason: String?,
    )

    internal class UnsupportedV7IndexFormatException(
        val reasonCode: String,
        val sortedPrefixConflictCount: Int = 0,
        val updateDiagnostic: V7UpdateSectionDiagnostic? = null,
    ) : IllegalStateException(
        buildString {
            append("unsupported CASC V7 index [reason=")
            append(reasonCode)
            if (sortedPrefixConflictCount > 0) {
                append(", conflictingSortedPrefixPairs=")
                append(sortedPrefixConflictCount)
            }
            append("]")
            updateDiagnostic?.let { diagnostic ->
                append(": ")
                append(diagnostic.reason ?: diagnostic.classification)
                append("; framing=")
                append(diagnostic.classification)
                append("; updateStart=0x")
                append(diagnostic.updateStart.toString(16))
                append("; pageSize=")
                append(diagnostic.pageSize ?: "unknown")
                append("; pages=")
                append(diagnostic.pageCount)
                append("; records=")
                append(diagnostic.nonemptyRecordCount)
                append("; validGuards=")
                append(diagnostic.validGuardRecordCount)
                append("; statuses=")
                append(diagnostic.statusCounts.toSortedMap())
                append("; pageLocalDuplicateGroups=")
                append(diagnostic.pageLocalDuplicatePrefixGroups)
                append("; pageLocalDuplicateExtraRows=")
                append(diagnostic.pageLocalDuplicateExtraRecords)
                append("; sortedPrefixOverlaps=")
                append(diagnostic.sortedPrefixOverlapCount)
                append("; zeroPadding=")
                append(diagnostic.zeroPadding)
                append("; reasonCode=")
                append(diagnostic.reasonCode)
            }
        },
    )

    private fun validateIncrementalPaths(sourceDataFiles: File, outputDataFiles: File) {
        if (Files.isSymbolicLink(sourceDataFiles.toPath()) || !Files.isDirectory(sourceDataFiles.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("incremental CASC source is not a regular data directory")
        }
        rejectSymlinkParents(sourceDataFiles)
        if (Files.isSymbolicLink(outputDataFiles.toPath()) || outputDataFiles.exists()) {
            throw IllegalStateException("incremental CASC output must be a new directory")
        }
        rejectSymlinkParents(outputDataFiles)
        val sourcePath = sourceDataFiles.canonicalFile.toPath().normalize()
        val outputPath = outputDataFiles.canonicalFile.toPath().normalize()
        if (sourcePath == outputPath || sourcePath.startsWith(outputPath) || outputPath.startsWith(sourcePath)) {
            throw IllegalArgumentException("incremental CASC source and output must be disjoint")
        }
    }

    private fun validateResumableIncrementalPaths(
        sourceDataFiles: File?,
        outputDataFiles: File,
        checkpointDirectory: File,
    ) {
        if (sourceDataFiles != null) {
            if (Files.isSymbolicLink(sourceDataFiles.toPath()) || !Files.isDirectory(sourceDataFiles.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("resumable CASC source is not a regular data directory")
            }
            rejectSymlinkParents(sourceDataFiles)
        }
        val outputPath = outputDataFiles.toPath().toAbsolutePath().normalize()
        val checkpointPath = checkpointDirectory.toPath().toAbsolutePath().normalize()
        if (Files.isSymbolicLink(outputPath)) throw IllegalStateException("resumable CASC output is a symlink")
        if (Files.isSymbolicLink(checkpointPath) || !Files.isDirectory(checkpointPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("resumable CASC checkpoint directory is unavailable")
        }
        rejectSymlinkParents(outputDataFiles)
        rejectSymlinkParents(checkpointDirectory)
        val parent = outputPath.parent ?: throw IllegalArgumentException("resumable CASC output has no parent")
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("resumable CASC output parent is not a regular directory")
        val outputCanonical = outputDataFiles.canonicalFile.toPath().normalize()
        val checkpointCanonical = checkpointDirectory.canonicalFile.toPath().normalize()
        if (outputCanonical == checkpointCanonical || outputCanonical.startsWith(checkpointCanonical) || checkpointCanonical.startsWith(outputCanonical)) {
            throw IllegalArgumentException("CASC checkpoint state and destination staging must be disjoint")
        }
        if (sourceDataFiles != null) {
            val sourceCanonical = sourceDataFiles.canonicalFile.toPath().normalize()
            if (sourceCanonical == outputCanonical || sourceCanonical.startsWith(outputCanonical) || outputCanonical.startsWith(sourceCanonical)) {
                throw IllegalArgumentException("resumable CASC source and output must be disjoint")
            }
            if (sourceCanonical == checkpointCanonical || sourceCanonical.startsWith(checkpointCanonical) || checkpointCanonical.startsWith(sourceCanonical)) {
                throw IllegalArgumentException("CASC checkpoint state and source data must be disjoint")
            }
        }
    }

    private fun prepareResumableOutput(outputDataFiles: File, checkpointDirectory: File, identity: String) {
        val directory = outputDataFiles.toPath().toAbsolutePath().normalize()
        val checkpointDir = checkpointDirectory.toPath().toAbsolutePath().normalize()
        if (!Files.isDirectory(checkpointDir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(checkpointDir)) {
            throw IllegalStateException("resumable CASC checkpoint directory is unavailable")
        }
        val owner = checkpointDir.resolve(RESUME_OWNER_NAME)
        val checkpoint = checkpointDir.resolve(RESUME_CHECKPOINT_NAME)
        val identityHash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        val markerBytes = RESUME_MAGIC + identityHash
        val hasOwner = Files.exists(owner, LinkOption.NOFOLLOW_LINKS)
        val hasCheckpoint = Files.exists(checkpoint, LinkOption.NOFOLLOW_LINKS)
        // A leftover verification temporary without its identity-bound owner
        // and checkpoint markers must never gain retroactive authority: reject
        // it before any marker creation, and never delete it here. The guard
        // is symmetric for both locations: the legacy destination-side name
        // needs the same ownership proof as the private one.
        val privateTemp = checkpointDir.resolve(INCREMENTAL_OBJECT_TEMP_NAME)
        val privateTempExists = Files.exists(privateTemp, LinkOption.NOFOLLOW_LINKS)
        if (privateTempExists && (!hasOwner || !hasCheckpoint)) {
            throw IllegalStateException("private CASC object temporary has no checkpoint ownership; manual review is required")
        }
        val legacyTemp = directory.resolve(INCREMENTAL_OBJECT_TEMP_NAME)
        val legacyTempExists = Files.exists(legacyTemp, LinkOption.NOFOLLOW_LINKS)
        if (legacyTempExists && (!hasOwner || !hasCheckpoint)) {
            throw IllegalStateException("resumable CASC object temporary has no checkpoint ownership; manual review is required")
        }
        if (!hasOwner && !hasCheckpoint) {
            FileChannel.open(owner, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                writeSequential(channel, ByteBuffer.wrap(markerBytes))
                channel.force(true)
            }
            FileChannel.open(checkpoint, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                writeSequential(channel, ByteBuffer.wrap(RESUME_MAGIC + identityHash))
                channel.force(true)
            }
            forceDirectory(checkpointDir)
        } else {
            requireExactFile(owner, markerBytes, "resumable CASC owner marker")
            if (!Files.isRegularFile(checkpoint, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(checkpoint)) {
                throw IllegalStateException("resumable CASC checkpoint is missing or unsafe")
            }
            // The checkpoint header binds the same target identity as the
            // owner marker; validate it before any temporary cleanup below.
            val header = ByteArray(RESUME_HEADER_SIZE)
            FileChannel.open(checkpoint, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { readFullyAt(it, ByteBuffer.wrap(header), 0L) }
            if (!header.copyOfRange(0, RESUME_MAGIC.size).contentEquals(RESUME_MAGIC) ||
                !header.copyOfRange(RESUME_MAGIC.size, RESUME_HEADER_SIZE).contentEquals(identityHash)
            ) throw IllegalStateException("resumable CASC checkpoint belongs to a different target or source revision")
        }
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(directory)
            } catch (failure: java.nio.file.FileAlreadyExistsException) {
                throw IllegalStateException("resumable CASC output appeared before ownership was established", failure)
            }
            forceDirectory(directory)
            forceDirectory(directory.parent)
        } else {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
                throw IllegalStateException("resumable CASC output is not a regular directory")
            }
        }
        // The legacy destination-side name stays recognized for c99 stages:
        // only the exact name, only a regular non-symlink file, and only
        // within the encoded-object size bound is ever removed. Unknown
        // entries are left for the strict stage listing to reject.
        val temp = directory.resolve(INCREMENTAL_OBJECT_TEMP_NAME)
        if (Files.isSymbolicLink(temp)) throw IllegalStateException("resumable CASC object temporary is a symlink")
        if (Files.exists(temp, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(temp, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("resumable CASC object temporary is not a regular file")
            if (Files.size(temp) > MAX_TEMPORARY_OBJECT_BYTES) throw IllegalStateException("resumable CASC object temporary exceeds the admitted encoded-object bound")
            Files.delete(temp)
        }
        // Owned private temporary: ownership was proven above, so the same
        // exact-name, regular-file, and size guards apply before removal.
        if (Files.isSymbolicLink(privateTemp)) throw IllegalStateException("private CASC object temporary is a symlink")
        if (Files.exists(privateTemp, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(privateTemp, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("private CASC object temporary is not a regular file")
            if (Files.size(privateTemp) > MAX_TEMPORARY_OBJECT_BYTES) throw IllegalStateException("private CASC object temporary exceeds the admitted encoded-object bound")
            Files.delete(privateTemp)
        }
        val scratch = directory.resolve(INCREMENTAL_SCRATCH_NAME)
        if (Files.exists(scratch, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(scratch, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(scratch)) {
                throw IllegalStateException("resumable CASC index scratch is not an owned directory")
            }
            deleteOwnedScratch(scratch.toFile())
        }
    }

    private fun loadResumableCheckpoint(
        outputDataFiles: File,
        checkpointDirectory: File,
        identity: String,
        sourceMaxArchiveId: Int,
        generations: LongArray,
        table: DiskIndexTable,
        bucketCounts: IntArray,
        cancelled: () -> Boolean,
        wantedRawKeys: Set<String> = emptySet(),
        targetObjectCount: Int = 0,
        targetMembership: ((String) -> IndexedValue<IncrementalObjectSpec>)? = null,
    ): ResumeLoadResult {
        val identityHash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        val directory = outputDataFiles.toPath().toAbsolutePath().normalize()
        val checkpointDir = checkpointDirectory.toPath().toAbsolutePath().normalize()
        val owner = checkpointDir.resolve(RESUME_OWNER_NAME)
        val checkpointPath = checkpointDir.resolve(RESUME_CHECKPOINT_NAME)
        requireExactFile(owner, RESUME_MAGIC + identityHash, "resumable CASC owner marker")
        if (Files.isSymbolicLink(checkpointPath) || !Files.isRegularFile(checkpointPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("resumable CASC checkpoint is not a regular file")
        }

        val segmentEnd = LongArray(1024) { -1L }
        val segmentSeen = BooleanArray(1024)
        val segmentIds = ArrayList<Int>()
        val targetOrdinalsSeen = if (targetMembership == null) null else java.util.BitSet(targetObjectCount)
        // Staged-copy locations for the bounded wanted set, collected during
        // this single streaming pass — never a rescan per key later.
        val rawKeyRecords = HashMap<String, OverlapRawKeyRecord>()
        val size = Files.size(checkpointPath)
        if (size !in RESUME_HEADER_SIZE.toLong()..MAX_RESUME_CHECKPOINT_BYTES) {
            throw IllegalStateException("resumable CASC checkpoint size exceeds its bounded object-frame limit")
        }
        val channel = FileChannel.open(checkpointPath, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        var lastArchiveId = -1
        var lastOffset = SEGMENT_HEADER_SIZE.toLong()
        var expectedArchiveId = sourceMaxArchiveId + 1
        var objectCount = 0L
        var lastEkey: String? = null
        var sawFinalizing = false
        var validEnd = RESUME_HEADER_SIZE.toLong()
        try {
            val header = ByteArray(RESUME_HEADER_SIZE)
            readFullyAt(channel, ByteBuffer.wrap(header), 0L)
            if (!header.copyOfRange(0, RESUME_MAGIC.size).contentEquals(RESUME_MAGIC) ||
                !header.copyOfRange(RESUME_MAGIC.size, RESUME_HEADER_SIZE).contentEquals(identityHash)
            ) throw IllegalStateException("resumable CASC checkpoint belongs to a different target or source revision")
            var position = RESUME_HEADER_SIZE.toLong()
            while (position < size) {
                checkCancelled(cancelled)
                val remaining = size - position
                if (remaining < RESUME_FRAME_SIZE) {
                    // Only an incomplete final frame in this private owned checkpoint may be truncated.
                    channel.truncate(position)
                    channel.force(true)
                    break
                }
                val frame = ByteArray(RESUME_FRAME_SIZE)
                readFullyAt(channel, ByteBuffer.wrap(frame), position)
                val expectedCrc = ByteBuffer.wrap(frame, RESUME_FRAME_DATA_SIZE, 4).order(ByteOrder.BIG_ENDIAN).int
                val crc = CRC32().also { it.update(frame, 0, RESUME_FRAME_DATA_SIZE) }.value.toInt()
                if (crc != expectedCrc || frame.copyOfRange(1, 8).any { it.toInt() != 0 }) {
                    throw IllegalStateException("resumable CASC checkpoint frame checksum or reserved fields are invalid")
                }
                when (frame[0].toInt() and 0xff) {
                    RESUME_OBJECT_FRAME -> {
                        if (sawFinalizing) throw IllegalStateException("resumable CASC checkpoint has object data after finalization began")
                        val record = decodeResumeObject(frame)
                        if (!record.ekey.matches(Regex("(?i)[0-9a-f]{32}")) || !record.contentKey.matches(Regex("(?i)[0-9a-f]{32}")) ||
                            record.ekey.substring(0, 18) == "0".repeat(18) || record.encodedSize <= 0 || record.decodedSize < 0 ||
                            record.encodedSize > ARCHIVE_LIMIT - SEGMENT_HEADER_SIZE - LOCAL_HEADER_SIZE ||
                            record.archiveId !in 0..1023 || record.offset < SEGMENT_HEADER_SIZE
                        ) throw IllegalStateException("resumable CASC checkpoint object record is outside supported bounds")
                        if (targetOrdinalsSeen != null) {
                            verifyTargetMembership(
                                IncrementalObjectSpec(
                                    record.ekey.lowercase(),
                                    record.contentKey.lowercase(),
                                    record.encodedSize,
                                    record.decodedSize,
                                ),
                                targetObjectCount,
                                requireNotNull(targetMembership),
                                targetOrdinalsSeen,
                                "resumable CASC checkpoint",
                            )
                        }
                        val end = Math.addExact(record.offset, Math.addExact(LOCAL_HEADER_SIZE.toLong(), record.encodedSize))
                        if (record.archiveId != lastArchiveId) {
                            if (record.archiveId != expectedArchiveId || record.offset != SEGMENT_HEADER_SIZE.toLong()) {
                                throw IllegalStateException("resumable CASC checkpoint segment sequence or first offset is invalid")
                            }
                            expectedArchiveId = Math.addExact(record.archiveId, 1)
                            lastArchiveId = record.archiveId
                            lastOffset = SEGMENT_HEADER_SIZE.toLong()
                            segmentSeen[record.archiveId] = true
                            segmentIds += record.archiveId
                            val path = File(outputDataFiles, "data.%03d".format(record.archiveId))
                            verifyOwnedSegmentHeader(path, record.archiveId, outputDataFiles)
                            segmentIndexRecords(
                                readSegmentPrefix(path),
                                record.archiveId,
                            ).forEach { generated ->
                                val bucket = computeBucket(generated.entry.key, 1)
                                if (!table.put(generated)) throw IllegalStateException("resumable CASC reconstruction EKey collision")
                                bucketCounts[bucket] = Math.addExact(bucketCounts[bucket], 1)
                                requireIndexCapacity(bucket, bucketCounts[bucket])
                            }
                        } else if (record.offset != lastOffset) {
                            throw IllegalStateException("resumable CASC checkpoint object offsets are not contiguous")
                        }
                        if (record.offset != lastOffset) throw IllegalStateException("resumable CASC checkpoint has an unexpected segment gap")
                        val ekeyBytes = record.ekey.lowercase().hexBytes()
                        val entry = IndexEntry(ekeyBytes.copyOfRange(0, 9), record.archiveId, record.offset, LOCAL_HEADER_SIZE.toLong() + record.encodedSize)
                        val staged = ExistingIndexRecord(record.ekey.lowercase(), entry, 2, Long.MAX_VALUE)
                        if (!table.put(staged)) throw IllegalStateException("resumable CASC checkpoint EKey collides with another staged record")
                        val bucket = computeBucket(ekeyBytes)
                        bucketCounts[bucket] = Math.addExact(bucketCounts[bucket], 1)
                        requireIndexCapacity(bucket, bucketCounts[bucket])
                        segmentEnd[record.archiveId] = end
                        lastOffset = end
                        objectCount = Math.addExact(objectCount, 1L)
                        lastEkey = record.ekey.lowercase()
                        if (lastEkey in wantedRawKeys && lastEkey !in rawKeyRecords) {
                            rawKeyRecords[lastEkey!!] = OverlapRawKeyRecord(
                                ekey = lastEkey!!,
                                contentKey = record.contentKey.lowercase(),
                                encodedSize = record.encodedSize,
                                decodedSize = record.decodedSize,
                                archiveId = record.archiveId,
                                offset = record.offset,
                            )
                        }
                    }
                    RESUME_FINISH_FRAME -> {
                        if (sawFinalizing || frame.copyOfRange(8, RESUME_FRAME_DATA_SIZE).any { it.toInt() != 0 }) {
                            throw IllegalStateException("resumable CASC finalization record is malformed")
                        }
                        sawFinalizing = true
                    }
                    else -> throw IllegalStateException("resumable CASC checkpoint contains an unknown frame type")
                }
                position += RESUME_FRAME_SIZE
                validEnd = position
            }

            val expectedIndexes = (0..15).associateWith { bucket ->
                "%02x%08x.idx".format(bucket, generations[bucket] + 1L)
            }.values.toSet()
            val actualSegmentIds = HashSet<Int>()
            val actualIndexes = HashSet<String>()
            Files.newDirectoryStream(directory).use { children ->
                children.forEach { path ->
                    checkCancelled(cancelled)
                    val name = path.fileName.toString()
                    if (Files.isSymbolicLink(path)) throw IllegalStateException("resumable CASC stage contains a symlink")
                    when {
                        name == INCREMENTAL_SCRATCH_NAME || name == INCREMENTAL_OBJECT_TEMP_NAME -> Unit
                        name.matches(Regex("data\\.[0-9]{3,4}")) -> {
                            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("resumable CASC segment is not a regular file")
                            val id = name.substringAfter("data.").toIntOrNull() ?: throw IllegalStateException("resumable CASC segment ID is malformed")
                            if (id !in 0..1023 || !actualSegmentIds.add(id)) throw IllegalStateException("resumable CASC segment ID is invalid")
                        }
                        name.matches(Regex("[0-9a-fA-F]{10}\\.idx")) -> {
                            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("resumable CASC index is not a regular file")
                            actualIndexes += name
                        }
                        else -> throw IllegalStateException("resumable CASC stage contains unowned output: $name")
                    }
                }
            }
            if (sawFinalizing) {
                if (actualIndexes.any { it !in expectedIndexes }) throw IllegalStateException("resumable CASC stage has an unowned index generation")
                actualIndexes.forEach { Files.delete(directory.resolve(it)) }
                forceDirectory(directory)
                channel.truncate(validEnd - RESUME_FRAME_SIZE)
                channel.force(true)
                sawFinalizing = false
            } else if (actualIndexes.isNotEmpty()) {
                throw IllegalStateException("resumable CASC stage has indices without a durable finalization record")
            }

            val loggedIds = segmentIds.toSet()
            val maxLogged = segmentIds.lastOrNull() ?: sourceMaxArchiveId
            if (actualSegmentIds.any { it !in loggedIds && it != maxLogged + 1 }) {
                throw IllegalStateException("resumable CASC stage contains a segment outside the checkpoint tail")
            }
            actualSegmentIds.filter { it !in loggedIds }.forEach { id ->
                if (id != maxLogged + 1) throw IllegalStateException("resumable CASC torn segment is not the final segment")
                val torn = File(outputDataFiles, "data.%03d".format(id))
                if (Files.size(torn.toPath()) > ARCHIVE_LIMIT) throw IllegalStateException("resumable CASC torn segment exceeds its bound")
                Files.delete(torn.toPath())
            }
            segmentIds.forEach { id ->
                val path = File(outputDataFiles, "data.%03d".format(id)).toPath()
                val end = segmentEnd[id]
                if (end < SEGMENT_HEADER_SIZE || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw IllegalStateException("resumable CASC segment has no durable object record")
                }
                val actualSize = Files.size(path)
                if (actualSize < end || actualSize > ARCHIVE_LIMIT) throw IllegalStateException("resumable CASC segment size disagrees with its checkpoint")
                if (id != maxLogged && actualSize != end) throw IllegalStateException("a nonfinal resumable CASC segment has an uncheckpointed tail")
                if (id == maxLogged && actualSize > end) {
                    FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { segment ->
                        segment.truncate(end)
                        segment.force(true)
                    }
                }
            }
            if (segmentIds.size > 1024 || (segmentIds.isNotEmpty() && segmentIds.first() != sourceMaxArchiveId + 1)) {
                throw IllegalStateException("resumable CASC stage exceeds the available archive ID range")
            }
        } finally {
            channel.close()
        }
        return ResumeLoadResult(
            ResumableCheckpoint(checkpointPath.toFile(), identityHash),
            lastArchiveId,
            lastOffset,
            if (lastArchiveId >= 0) Math.addExact(lastArchiveId, 1) else sourceMaxArchiveId + 1,
            segmentIds.size,
            segmentIds,
            objectCount,
            lastEkey,
            segmentIds.associateWith { segmentEnd[it] },
            rawKeyRecords,
        )
    }

    private fun decodeResumeObject(frame: ByteArray): ResumeObject {
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        val ekey = frame.copyOfRange(8, 24).hex()
        val contentKey = frame.copyOfRange(24, 40).hex()
        val encodedSize = buffer.getLong(40)
        val decodedSize = buffer.getLong(48)
        val archiveId = buffer.getInt(56)
        val offset = buffer.getLong(60)
        return ResumeObject(ekey, contentKey, encodedSize, decodedSize, archiveId, offset)
    }

    private fun appendResumeObjects(
        checkpoint: ResumableCheckpoint,
        commits: List<PendingResumeCommit>,
    ) {
        require(commits.isNotEmpty() && commits.size <= RESUMABLE_COMMIT_GROUP_OBJECTS) {
            "resumable CASC commit frame batch is outside its object bound"
        }
        if (!Files.isRegularFile(checkpoint.file.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(checkpoint.file.toPath())) {
            throw IllegalStateException("resumable CASC checkpoint changed before append")
        }
        val frames = ByteArray(Math.multiplyExact(commits.size, RESUME_FRAME_SIZE))
        commits.forEachIndexed { index, commit ->
            val frame = ByteArray(RESUME_FRAME_SIZE)
            val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
            frame[0] = RESUME_OBJECT_FRAME.toByte()
            commit.spec.ekey.hexBytes().copyInto(frame, 8)
            commit.spec.contentKey.hexBytes().copyInto(frame, 24)
            buffer.putLong(40, commit.spec.encodedSize)
            buffer.putLong(48, commit.spec.decodedSize)
            buffer.putInt(56, commit.archiveId)
            buffer.putLong(60, commit.offset)
            putResumeCrc(frame)
            frame.copyInto(frames, index * RESUME_FRAME_SIZE)
        }
        FileChannel.open(
            checkpoint.file.toPath(),
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            writeSequential(channel, ByteBuffer.wrap(frames))
            channel.force(true)
        }
        forceDirectory(requireNotNull(checkpoint.file.parentFile).toPath())
    }

    private fun appendResumeFinalizing(checkpoint: ResumableCheckpoint) {
        val frame = ByteArray(RESUME_FRAME_SIZE)
        frame[0] = RESUME_FINISH_FRAME.toByte()
        putResumeCrc(frame)
        appendResumeFrame(checkpoint, frame)
    }

    private fun appendResumeFrame(checkpoint: ResumableCheckpoint, frame: ByteArray) {
        if (!Files.isRegularFile(checkpoint.file.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(checkpoint.file.toPath())) {
            throw IllegalStateException("resumable CASC checkpoint changed before append")
        }
        FileChannel.open(
            checkpoint.file.toPath(),
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            writeSequential(channel, ByteBuffer.wrap(frame))
            channel.force(true)
        }
        forceDirectory(requireNotNull(checkpoint.file.parentFile).toPath())
    }

    private fun putResumeCrc(frame: ByteArray) {
        val crc = CRC32().also { it.update(frame, 0, RESUME_FRAME_DATA_SIZE) }.value.toInt()
        ByteBuffer.wrap(frame, RESUME_FRAME_DATA_SIZE, 4).order(ByteOrder.BIG_ENDIAN).putInt(crc)
    }

    private fun verifyOwnedSegmentHeader(path: File, id: Int, outputDataFiles: File) {
        val target = path.toPath()
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) < SEGMENT_HEADER_SIZE) {
            throw IllegalStateException("resumable CASC segment header is missing or truncated")
        }
        val expected = segmentHeader(id, MessageDigest.getInstance("MD5").digest(outputDataFiles.absolutePath.toByteArray(Charsets.UTF_8)))
        val actual = readSegmentPrefix(path)
        if (!actual.contentEquals(expected)) throw IllegalStateException("resumable CASC segment header does not match the owned output identity")
    }

    private fun readSegmentPrefix(path: File): ByteArray {
        val result = ByteArray(SEGMENT_HEADER_SIZE)
        FileChannel.open(path.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            readFullyAt(channel, ByteBuffer.wrap(result), 0L)
        }
        return result
    }

    private fun openAppendArchive(path: File): BufferedOutputStream {
        if (Files.isSymbolicLink(path.toPath()) || !Files.isRegularFile(path.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("resumable CASC append segment is not a regular file")
        }
        return BufferedOutputStream(
            Files.newOutputStream(path.toPath(), StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS),
            BUFFER_SIZE,
        )
    }

    private fun requireExactFile(path: java.nio.file.Path, expected: ByteArray, label: String) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != expected.size.toLong()) {
            throw IllegalStateException("$label is missing or malformed")
        }
        val actual = ByteArray(expected.size)
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            readFullyAt(channel, ByteBuffer.wrap(actual), 0L)
        }
        if (!actual.contentEquals(expected)) throw IllegalStateException("$label does not match this destination and catalog")
    }

    private fun forceFile(file: File) {
        if (Files.isSymbolicLink(file.toPath()) || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("CASC output file cannot be synchronized")
        }
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
    }

    private fun forceDirectory(path: java.nio.file.Path) {
        try {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        } catch (failure: Exception) {
            throw IllegalStateException("filesystem cannot durably synchronize resumable CASC output directory", failure)
        }
    }

    private fun validateTargetInspectionPaths(sourceDataFiles: File?, workDirectory: File) {
        if (Files.isSymbolicLink(workDirectory.toPath()) || Files.exists(workDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("CASC target-inspection work directory must be fresh and not a symlink")
        }
        rejectSymlinkParents(workDirectory)
        val parent = workDirectory.absoluteFile.parentFile
            ?: throw IllegalArgumentException("CASC target-inspection work directory has no parent")
        if (Files.isSymbolicLink(parent.toPath()) || !Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("CASC target-inspection work parent is not a regular directory")
        }
        if (sourceDataFiles != null) {
            rejectSymlinkParents(sourceDataFiles)
            if (Files.isSymbolicLink(sourceDataFiles.toPath())) {
                throw IllegalStateException("CASC target-inspection source is a symlink")
            }
            val sourcePath = sourceDataFiles.canonicalFile.toPath().normalize()
            val workPath = workDirectory.canonicalFile.toPath().normalize()
            if (sourcePath == workPath || sourcePath.startsWith(workPath) || workPath.startsWith(sourcePath)) {
                throw IllegalArgumentException("CASC target-inspection source and work directory must be disjoint")
            }
            if (Files.exists(sourceDataFiles.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                !Files.isDirectory(sourceDataFiles.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                throw IllegalStateException("CASC target-inspection source is not a regular data directory")
            }
        }
    }

    private fun normalizeIncrementalSpec(spec: IncrementalObjectSpec) = spec.copy(
        ekey = spec.ekey.lowercase(),
        contentKey = spec.contentKey.lowercase(),
    )

    private fun verifyTargetMembership(
        spec: IncrementalObjectSpec,
        targetObjectCount: Int,
        targetMembership: (String) -> IndexedValue<IncrementalObjectSpec>,
        seenOrdinals: java.util.BitSet,
        source: String = "incremental manifest",
    ) {
        val admitted = try {
            targetMembership(spec.ekey)
        } catch (missing: NoSuchElementException) {
            throw IllegalStateException("$source contains an object outside the admitted target", missing)
        }
        if (admitted.index !in 0 until targetObjectCount) {
            throw IllegalStateException("$source membership ordinal is outside its bounded target")
        }
        if (normalizeIncrementalSpec(admitted.value) != spec) {
            throw IllegalStateException("$source object does not match the admitted target manifest")
        }
        if (seenOrdinals[admitted.index]) {
            throw IllegalStateException("$source repeats a target membership ordinal")
        }
        seenOrdinals.set(admitted.index)
    }

    private fun validateIncrementalSpec(spec: IncrementalObjectSpec) {
        require(spec.ekey.matches(Regex("[0-9a-f]{32}"))) { "invalid CASC encoding key" }
        require(spec.ekey.substring(0, 18) != "0".repeat(18)) { "CASC encoding key has a reserved all-zero 9-byte index prefix" }
        require(spec.contentKey.matches(Regex("[0-9a-f]{32}"))) { "invalid CASC content key" }
        require(spec.encodedSize >= 0 && spec.decodedSize >= 0) { "negative CASC object size" }
        require(spec.encodedSize <= ARCHIVE_LIMIT - SEGMENT_HEADER_SIZE - LOCAL_HEADER_SIZE) {
            "CASC encoded object exceeds the supported archive limit"
        }
    }

    private fun inspectExistingIndexFiles(
        sourceDataFiles: File,
        cancelled: () -> Boolean,
        allowCandidateHints: Boolean = false,
    ): ExistingIndexSet {
        val indexPattern = Regex("^[0-9a-fA-F]{10}\\.idx$")
        val segmentPattern = Regex("^data\\.(\\d{3,})$")
        val indexFiles = ArrayList<IncrementalIndexFile>()
        var maxArchiveId = -1
        Files.newDirectoryStream(sourceDataFiles.toPath()).use { children ->
            children.forEach { path ->
                val child = path.toFile()
                if (Files.isSymbolicLink(path)) throw IllegalStateException("existing CASC data directory contains a symlink")
                if (child.name.endsWith(".idx", ignoreCase = true) && !indexPattern.matches(child.name)) {
                    throw IllegalStateException("existing CASC index filename is malformed")
                }
                if (indexPattern.matches(child.name)) {
                    if (indexFiles.size >= MAX_INDEX_GENERATION_FILES) {
                        throw IllegalStateException("existing CASC index generation count exceeds the bounded scan")
                    }
                    val bucket = child.name.substring(0, 2).toInt(16)
                    if (bucket !in 0..15) throw IllegalStateException("existing CASC index bucket is outside the supported 16-bucket V7 format")
                    val generation = generationOf(child.name)
                    val indexBytes = readIncrementalIndexNoFollow(child)
                    val recordCount = validateIncrementalIndex(indexBytes, bucket, cancelled, allowCandidateHints)
                    indexFiles += IncrementalIndexFile(child, bucket, generation, recordCount)
                }
                val match = segmentPattern.matchEntire(child.name) ?: return@forEach
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("existing CASC segment is not a regular file")
                val id = match.groupValues[1].toIntOrNull() ?: throw IllegalStateException("existing CASC segment id is invalid")
                if (id !in 0..1023) throw IllegalStateException("existing CASC archive id exceeds V7 location capacity")
                if (Files.size(path) > ARCHIVE_LIMIT) throw IllegalStateException("existing CASC segment exceeds the supported archive limit")
                maxArchiveId = maxOf(maxArchiveId, id)
            }
        }
        indexFiles.sortWith(compareBy<IncrementalIndexFile> { it.bucket }.thenBy { it.generation })
        if (indexFiles.isEmpty()) throw IllegalStateException("existing CASC local V7 indices are missing")

        val generations = LongArray(16) { -1L }
        val presentBuckets = BooleanArray(16)
        val latestCounts = IntArray(16) { -1 }
        var scannedRecords = 0L
        indexFiles.forEach { indexFile ->
            checkCancelled(cancelled)
            presentBuckets[indexFile.bucket] = true
            if (indexFile.generation > generations[indexFile.bucket]) {
                generations[indexFile.bucket] = indexFile.generation
                latestCounts[indexFile.bucket] = indexFile.recordCount
            }
            scannedRecords = Math.addExact(scannedRecords, indexFile.recordCount.toLong())
            if (scannedRecords > MAX_SCANNED_INDEX_RECORDS) {
                throw IllegalStateException("existing CASC index history exceeds the bounded 16-million-record scan")
            }
        }
        for (bucket in 0..15) {
            if (!presentBuckets[bucket]) throw IllegalStateException("existing CASC local V7 store is missing bucket $bucket")
        }
        if (maxArchiveId < 0) throw IllegalStateException("existing CASC local V7 store has no archive segments")
        return ExistingIndexSet(indexFiles, generations, maxArchiveId, latestCounts.sum().coerceAtLeast(0))
    }

    private fun readExistingIndexRecords(
        source: ExistingIndexSet,
        sourceDataFiles: File,
        table: DiskIndexTable,
        cancelled: () -> Boolean,
    ): IntArray {
        val bucketCounts = IntArray(16)
        var scannedRecords = 0L
        ReadOnlyArchiveCache(sourceDataFiles).use { archives ->
            source.files.forEach { indexFile ->
                checkCancelled(cancelled)
                val bytes = readIncrementalIndexNoFollow(indexFile.file)
                val count = validateIncrementalIndex(bytes, indexFile.bucket, cancelled)
                if (count != indexFile.recordCount) throw IllegalStateException("existing CASC index changed while being read")
                val end = 0x28 + count * 18
                var at = 0x28
                while (at < end) {
                    checkCancelled(cancelled)
                    scannedRecords++
                    if (scannedRecords > MAX_SCANNED_INDEX_RECORDS) {
                        throw IllegalStateException("existing CASC index history exceeds the bounded 16-million-record scan")
                    }
                    val key = bytes.copyOfRange(at, at + 9)
                    val locationWord = u32be(bytes, at + 10).toLong() and 0xffffffffL
                    val archiveId = ((bytes[at + 9].toInt() and 0xff) shl 2) or ((locationWord ushr 30).toInt() and 3)
                    val archiveOffset = locationWord and 0x3fffffffL
                    val size = u32(bytes, at + 14).toLong() and 0xffffffffL
                    if (archiveId !in 0..1023 || archiveOffset >= ARCHIVE_LIMIT || size < LOCAL_HEADER_SIZE || size > ARCHIVE_LIMIT) {
                        throw IllegalStateException("existing CASC index location is outside supported V7 bounds")
                    }
                    val entry = IndexEntry(key, archiveId, archiveOffset, size)
                    val header = archives.readLocalHeader(entry)
                    validateLocalHeader(header, entry)
                    val flags = u16(header, 0x14)
                    if (flags != 0 && flags != 1) {
                        throw IllegalStateException("existing CASC local record flags are unsupported; nonzero update/record flags are not mergeable")
                    }
                    if ((flags == 1 && size != LOCAL_HEADER_SIZE.toLong()) || (flags == 0 && size < LOCAL_HEADER_SIZE)) {
                        throw IllegalStateException("existing CASC local record size is invalid")
                    }
                    val fullKeyBytes = header.copyOfRange(0, 16).reversedArray()
                    val expectedBucket = computeBucket(fullKeyBytes, if (flags == 1) 1 else 0)
                    if (!fullKeyBytes.copyOfRange(0, 9).contentEquals(key) || expectedBucket != indexFile.bucket) {
                        throw IllegalStateException("existing CASC index entry does not match its full EKey/header bucket")
                    }
                    val ekey = fullKeyBytes.hex()
                    val record = ExistingIndexRecord(ekey, entry, flags, indexFile.generation)
                    val prior = table.find(fullKeyBytes)
                    if (prior != null && prior.flags != flags) {
                        throw IllegalStateException("existing CASC EKey changes between object and reconstruction records")
                    }
                    if (table.put(record.copy(targetSeen = prior?.targetSeen ?: false))) {
                        val mergedCount = bucketCounts[indexFile.bucket] + 1
                        requireIndexCapacity(indexFile.bucket, mergedCount)
                        bucketCounts[indexFile.bucket] = mergedCount
                        if (table.recordCount > MAX_TOTAL_INDEX_ENTRIES) {
                            throw IllegalArgumentException("CASC V7 merged index exceeds the bounded total-record limit")
                        }
                    }
                    at += 18
                }
            }
        }
        return bucketCounts
    }

    private fun buildCandidateHints(
        source: ExistingIndexSet,
        candidates: CandidateHintStore,
        cancelled: () -> Boolean,
    ) {
        var scannedRecords = 0L
        source.files.forEach { indexFile ->
            checkCancelled(cancelled)
            val bytes = readIncrementalIndexNoFollow(indexFile.file)
            val recordCount = validateIncrementalIndex(bytes, indexFile.bucket, cancelled, allowCandidateHints = true)
            if (recordCount != indexFile.recordCount) {
                throw IllegalStateException("existing CASC index changed while candidate hints were being collected")
            }
            scannedRecords = Math.addExact(scannedRecords, recordCount.toLong())
            if (scannedRecords > MAX_SCANNED_INDEX_RECORDS) {
                throw IllegalStateException("existing CASC index history exceeds the bounded 16-million-record scan")
            }
            forEachV7IndexLocationHint(bytes, recordCount, cancelled) { hint ->
                candidates.add(hint.keyPrefix, hint.archiveId, hint.archiveOffset, hint.size)
            }
        }
    }

    private fun readIncrementalIndexNoFollow(file: File): ByteArray {
        val path = file.toPath()
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("existing CASC V7 index is not a regular file")
        }
        val size = Files.size(path)
        // New outputs always carry the empty update reserve, so the admission
        // bound must cover it; this only widens acceptance for our own
        // outputs and never tightens the read-only parser.
        val maxSize = indexFileLength(MAX_INDEX_ENTRIES_PER_BUCKET.toLong())
        if (size < MIN_INDEX_FILE_SIZE || size > maxSize || size % INDEX_ALIGNMENT != 0L) {
            throw IllegalStateException("existing CASC V7 index length is truncated or outside bounded variable-size limits")
        }
        val bytes = ByteArray(size.toInt())
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            readFullyAt(channel, ByteBuffer.wrap(bytes), 0L)
            if (channel.size() != size) throw IllegalStateException("existing CASC index changed while being read")
        }
        return bytes
    }

    private fun validateIncrementalIndex(
        bytes: ByteArray,
        bucket: Int,
        cancelled: () -> Boolean,
        allowCandidateHints: Boolean = false,
    ): Int {
        if (bytes.size < MIN_INDEX_FILE_SIZE || bytes.size.toLong() % INDEX_ALIGNMENT != 0L ||
            u32(bytes, 0) != 16 || u16(bytes, 8) != 7 || (bytes[10].toInt() and 0xff) != bucket ||
            (bytes[12].toInt() and 0xff) != 4 || (bytes[13].toInt() and 0xff) != 5 ||
            (bytes[14].toInt() and 0xff) != 9 || (bytes[15].toInt() and 0xff) != 30 ||
            u32(bytes, 4) != hashlittle(bytes.copyOfRange(8, 24), 0) ||
            u32(bytes, 16) != 0 || u32(bytes, 20) != 64
        ) throw IllegalStateException("existing CASC index header is incompatible")
        val blockSize = u32(bytes, 0x20).toLong() and 0xffffffffL
        if (blockSize % 18L != 0L || blockSize / 18L > MAX_INDEX_ENTRIES_PER_BUCKET) {
            throw IllegalStateException("existing CASC index has an invalid or excessive sorted-section record count")
        }
        val sortedEnd = 0x28L + blockSize
        val updateStart = roundUpIndexLength(sortedEnd)
        if (sortedEnd > bytes.size || updateStart > bytes.size) {
            throw IllegalStateException("existing CASC V7 index is truncated before its aligned update section")
        }
        var pc = 0
        var pb = 0
        var at = 0x28
        var previous: ByteArray? = null
        var previousEntry: ByteArray? = null
        var duplicatePrefixConflicts = 0
        val end = at + blockSize.toInt()
        var checked = 0
        while (at < end) {
            if ((checked++ and 0x3fff) == 0) checkCancelled(cancelled)
            val key = bytes.copyOfRange(at, at + 9)
            if (key.all { it.toInt() == 0 }) {
                throw IllegalStateException("existing CASC V7 index contains a reserved all-zero 9-byte EKey prefix")
            }
            val entryBytes = bytes.copyOfRange(at, at + 18)
            val ordering = previous?.let { compareBytes(it, key) } ?: -1
            if (ordering > 0) {
                throw IllegalStateException("existing CASC index sorted section is not strictly sorted")
            }
            if (ordering == 0 && previousEntry?.contentEquals(entryBytes) != true) duplicatePrefixConflicts++
            previous = key
            previousEntry = entryBytes
            val hashes = hashlittle2(entryBytes, pc, pb)
            pc = hashes.first
            pb = hashes.second
            at += 18
        }
        if (u32(bytes, 0x24) != pc) throw IllegalStateException("existing CASC index sorted-section checksum is invalid")
        val updateDiagnostic = diagnoseV7UpdateSection(bytes, sortedEnd.toInt(), cancelled)
        val sortedPrefixConflict = duplicatePrefixConflicts > 0 && !allowCandidateHints
        val unsupportedUpdate = updateDiagnostic.reasonCode != "NONE" &&
            !(allowCandidateHints && updateDiagnostic.reasonCode == "UNSUPPORTED_UPDATE_SEMANTICS")
        if (sortedPrefixConflict || unsupportedUpdate) {
            val reasonCode = if (duplicatePrefixConflicts > 0) "CONFLICTING_SORTED_PREFIX_RECORDS" else updateDiagnostic.reasonCode
            throw UnsupportedV7IndexFormatException(reasonCode, duplicatePrefixConflicts, updateDiagnostic)
        }
        return (blockSize / 18L).toInt()
    }

    /**
     * Classify, but do not interpret, a nonzero V7 update section.  The cached
     * client-written sample has a strong 512-byte / 21-record candidate frame;
     * its status values and LSM/update precedence remain unsupported until
     * those semantics are independently established.
     */
    internal fun diagnoseV7UpdateSection(
        bytes: ByteArray,
        sortedEnd: Int,
        cancelled: () -> Boolean = { false },
    ): V7UpdateSectionDiagnostic {
        require(sortedEnd in 0x28..bytes.size) { "sorted section end is outside index bytes" }
        val updateStartLong = roundUpIndexLength(sortedEnd.toLong())
        if (updateStartLong > bytes.size) {
            return V7UpdateSectionDiagnostic(
                "TRUNCATED_UPDATE_BOUNDARY", "truncated", updateStartLong.toInt(), null, 0, 0, 0,
                emptyMap(), 0, 0, 0, false, "file ends before the 4KiB update-section boundary",
            )
        }
        val updateStart = updateStartLong.toInt()
        val gapZero = (sortedEnd until updateStart).all { bytes[it].toInt() == 0 }
        if (!gapZero) {
            return V7UpdateSectionDiagnostic(
                "NONZERO_SORTED_ALIGNMENT_PADDING", "unrecognized-nonzero-tail", updateStart, null, 0, 0, 0,
                emptyMap(), 0, 0, 0, false,
                "nonzero bytes occur between the sorted section and its aligned update boundary",
            )
        }
        val tailSize = bytes.size - updateStart
        if ((updateStart until bytes.size).all { bytes[it].toInt() == 0 }) {
            return V7UpdateSectionDiagnostic(
                "NONE", "empty-update-tail", updateStart, null, 0, 0, 0, emptyMap(), 0, 0, 0,
                true, null,
            )
        }
        if (tailSize == 0 || tailSize % CANDIDATE_UPDATE_PAGE_SIZE != 0) {
            return V7UpdateSectionDiagnostic(
                "UNRECOGNIZED_NONZERO_UPDATE_LAYOUT", "opaque-nonzero-update-tail", updateStart, null, 0, 0, 0,
                emptyMap(), 0, 0, 0, false,
                "nonzero update section does not have a whole-number 512-byte page extent",
            )
        }

        val pages = tailSize / CANDIDATE_UPDATE_PAGE_SIZE
        val statusCounts = HashMap<Int, Int>()
        var records = 0
        var validGuards = 0
        var pageLocalDuplicateGroups = 0
        var pageLocalDuplicateExtraRows = 0
        var sortedPrefixOverlaps = 0
        var zeroPadding = true
        var framingValid = true
        var sawEmptyPage = false
        for (page in 0 until pages) {
            checkCancelled(cancelled)
            val pageStart = updateStart + page * CANDIDATE_UPDATE_PAGE_SIZE
            val pageKeys = HashMap<String, Int>(CANDIDATE_UPDATE_RECORDS_PER_PAGE)
            var sawEmptySlot = false
            var pageHasRecord = false
            for (slot in 0 until CANDIDATE_UPDATE_RECORDS_PER_PAGE) {
                val recordStart = pageStart + slot * CANDIDATE_UPDATE_RECORD_SIZE
                val record = bytes.copyOfRange(recordStart, recordStart + CANDIDATE_UPDATE_RECORD_SIZE)
                val guard = u32(record, 0)
                if (guard == 0) {
                    if (record.any { it.toInt() != 0 }) framingValid = false
                    sawEmptySlot = true
                    continue
                }
                pageHasRecord = true
                if (sawEmptySlot) framingValid = false
                val expectedGuard = hashlittle(record.copyOfRange(4, 23), 0) or Int.MIN_VALUE
                if (guard != expectedGuard || (guard and Int.MIN_VALUE) == 0) framingValid = false
                else validGuards++
                val key = record.copyOfRange(4, 13)
                if (key.all { it.toInt() == 0 } || record[23].toInt() != 0) framingValid = false
                val status = record[22].toInt() and 0xff
                statusCounts[status] = (statusCounts[status] ?: 0) + 1
                val keyHex = key.hex()
                val priorCount = pageKeys[keyHex] ?: 0
                if (priorCount == 1) pageLocalDuplicateGroups++
                if (priorCount > 0) pageLocalDuplicateExtraRows++
                pageKeys[keyHex] = priorCount + 1
                if (sortedSectionContainsPrefix(bytes, sortedEnd, key)) sortedPrefixOverlaps++
                records++
            }
            val paddingStart = pageStart + CANDIDATE_UPDATE_RECORDS_PER_PAGE * CANDIDATE_UPDATE_RECORD_SIZE
            if ((paddingStart until pageStart + CANDIDATE_UPDATE_PAGE_SIZE).any { bytes[it].toInt() != 0 }) {
                zeroPadding = false
                framingValid = false
            }
            if (pageHasRecord && sawEmptyPage) framingValid = false
            if (!pageHasRecord) sawEmptyPage = true
        }

        val reasonCode = if (framingValid && zeroPadding && validGuards == records) {
            "UNSUPPORTED_UPDATE_SEMANTICS"
        } else {
            "UNRECOGNIZED_NONZERO_UPDATE_LAYOUT"
        }
        val reason = if (reasonCode == "UNSUPPORTED_UPDATE_SEMANTICS") {
            "nonzero update section matches candidate 512-byte pages (21 guarded 24-byte records plus 8 zero pad bytes), " +
                "but status semantics and LSM/update precedence, including repeated 9-byte prefixes, are unproven"
        } else {
            "nonzero update section fails the candidate 512-byte guarded-page framing checks"
        }
        return V7UpdateSectionDiagnostic(
            reasonCode,
            if (reasonCode == "UNSUPPORTED_UPDATE_SEMANTICS") "candidate-512-byte-update-pages" else "opaque-nonzero-update-tail",
            updateStart,
            CANDIDATE_UPDATE_PAGE_SIZE,
            pages,
            records,
            validGuards,
            statusCounts.toSortedMap(),
            pageLocalDuplicateGroups,
            pageLocalDuplicateExtraRows,
            sortedPrefixOverlaps,
            zeroPadding,
            reason,
        )
    }

    private fun sortedSectionContainsPrefix(bytes: ByteArray, sortedEnd: Int, key: ByteArray): Boolean {
        var low = 0
        var high = (sortedEnd - 0x28) / 18 - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val at = 0x28 + middle * 18
            val compare = compareBytes(bytes.copyOfRange(at, at + 9), key)
            when {
                compare < 0 -> low = middle + 1
                compare > 0 -> high = middle - 1
                else -> return true
            }
        }
        return false
    }

    private data class IndexLocationHint(
        val keyPrefix: ByteArray,
        val archiveId: Int,
        val archiveOffset: Long,
        val size: Long,
    )

    /** Visit sorted entries and structurally validated update locations as hints. */
    private fun forEachV7IndexLocationHint(
        bytes: ByteArray,
        recordCount: Int,
        cancelled: () -> Boolean,
        action: (IndexLocationHint) -> Unit,
    ) {
        val sortedEnd = 0x28 + recordCount * 18
        var at = 0x28
        while (at < sortedEnd) {
            checkCancelled(cancelled)
            val key = bytes.copyOfRange(at, at + 9)
            val locationWord = u32be(bytes, at + 10).toLong() and 0xffffffffL
            val archiveId = ((bytes[at + 9].toInt() and 0xff) shl 2) or ((locationWord ushr 30).toInt() and 3)
            action(IndexLocationHint(key, archiveId, locationWord and 0x3fffffffL, u32(bytes, at + 14).toLong() and 0xffffffffL))
            at += 18
        }

        val update = diagnoseV7UpdateSection(bytes, sortedEnd, cancelled)
        when (update.reasonCode) {
            "NONE" -> return
            "UNSUPPORTED_UPDATE_SEMANTICS" -> Unit // status is deliberately ignored; fields remain untrusted hints.
            else -> throw UnsupportedV7IndexFormatException(update.reasonCode, updateDiagnostic = update)
        }
        for (page in 0 until update.pageCount) {
            checkCancelled(cancelled)
            val pageStart = update.updateStart + page * CANDIDATE_UPDATE_PAGE_SIZE
            for (slot in 0 until CANDIDATE_UPDATE_RECORDS_PER_PAGE) {
                val atRecord = pageStart + slot * CANDIDATE_UPDATE_RECORD_SIZE
                if (u32(bytes, atRecord) == 0) continue
                val key = bytes.copyOfRange(atRecord + 4, atRecord + 13)
                val locationWord = u32be(bytes, atRecord + 14).toLong() and 0xffffffffL
                val archiveId = ((bytes[atRecord + 13].toInt() and 0xff) shl 2) or ((locationWord ushr 30).toInt() and 3)
                action(
                    IndexLocationHint(
                        key,
                        archiveId,
                        locationWord and 0x3fffffffL,
                        u32(bytes, atRecord + 18).toLong() and 0xffffffffL,
                    ),
                )
            }
        }
    }

    private fun validateLocalHeader(header: ByteArray, entry: IndexEntry) {
        if (header.size != LOCAL_HEADER_SIZE ||
            (u32(header, 0x10).toLong() and 0xffffffffL) != entry.size ||
            !header.copyOfRange(0, 16).reversedArray().copyOfRange(0, 9).contentEquals(entry.key) ||
            u32(header, 0x16) != hashlittle(header.copyOfRange(0, 0x16), 0x3D6BE971) ||
            u32(header, 0x1a) != checksumB(header, entry.archiveId * ARCHIVE_LIMIT + entry.archiveOffset)
        ) throw IllegalStateException("existing CASC local header/index location mismatch")
    }

    private fun segmentIndexRecords(segment: ByteArray, archiveId: Int): List<ExistingIndexRecord> =
        (0 until 16).map { slot ->
            val header = segment.copyOfRange(slot * LOCAL_HEADER_SIZE, (slot + 1) * LOCAL_HEADER_SIZE)
            val key = header.copyOfRange(0, 16).reversedArray()
            if (key.copyOfRange(0, 9).all { it.toInt() == 0 }) {
                throw IllegalStateException("generated CASC segment reconstruction EKey has a reserved all-zero 9-byte prefix")
            }
            val bucket = computeBucket(key, 1)
            if (bucket != slot) throw IllegalStateException("generated CASC segment reconstruction bucket is invalid")
            ExistingIndexRecord(
                key.hex(),
                IndexEntry(key.copyOfRange(0, 9), archiveId, (slot * LOCAL_HEADER_SIZE).toLong(), LOCAL_HEADER_SIZE.toLong()),
                1,
                Long.MAX_VALUE,
            )
        }

    /**
     * Staged/source verification of one indexed object through the owned
     * private temporary. Order and guards are frozen: temp-prep delete, source
     * symlink/regular guard, READ/NOFOLLOW open, size check, 30-byte header
     * read/validate plus full EKey from header, private copy loop, source/temp
     * close, temp EKey hash, BLTE/content verification, true; cancellation
     * rethrows, other failures return false, and the temp is deleted in
     * finally without hiding the outcome. No fd caching, no skip-if-verified.
     * The trailing optional holder is private diagnostic state only (default
     * null keeps legacy/source reuse calls unchanged) and is not a public
     * contract: external scan probes using reflection must expect the new
     * trailing parameter.
     */
    private fun verifyExistingIncrementalObject(
        sourceDataFiles: File,
        record: ExistingIndexRecord,
        spec: IncrementalObjectSpec,
        temporary: File,
        cancelled: () -> Boolean,
        replayTimings: StagedReplayTimings? = null,
    ): Boolean {
        if (record.flags != 0 || record.entry.size != LOCAL_HEADER_SIZE.toLong() + spec.encodedSize) return false
        val totalVerifyStarted = System.nanoTime()
        var openHeaderNanos = 0L
        var copyNanos = 0L
        var ekeyNanos = 0L
        var blteNanos = 0L
        var succeeded = false
        try {
            val openHeaderStarted = System.nanoTime()
            if (temporary.exists() || Files.isSymbolicLink(temporary.toPath())) {
                if (Files.isSymbolicLink(temporary.toPath()) || !temporary.delete()) throw IllegalStateException("cannot prepare CASC verification temporary")
            }
            val archiveFile = File(sourceDataFiles, "data.%03d".format(record.entry.archiveId))
            if (Files.isSymbolicLink(archiveFile.toPath()) || !Files.isRegularFile(archiveFile.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
            FileChannel.open(archiveFile.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                if (record.entry.archiveOffset > channel.size() - record.entry.size) return false
                val header = ByteArray(LOCAL_HEADER_SIZE)
                readFullyAt(channel, ByteBuffer.wrap(header), record.entry.archiveOffset)
                validateLocalHeader(header, record.entry)
                if (header.copyOfRange(0, 16).reversedArray().hex() != spec.ekey) return false
                openHeaderNanos = System.nanoTime() - openHeaderStarted
                val copyStarted = System.nanoTime()
                openOwnedOutput(temporary, false).use { output ->
                    var at = record.entry.archiveOffset + LOCAL_HEADER_SIZE
                    var left = spec.encodedSize
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (left > 0) {
                        checkCancelled(cancelled)
                        val count = min(buffer.size.toLong(), left).toInt()
                        val byteBuffer = ByteBuffer.wrap(buffer, 0, count)
                        readFullyAt(channel, byteBuffer, at)
                        output.write(buffer, 0, count)
                        at += count
                        left -= count
                    }
                }
                copyNanos = System.nanoTime() - copyStarted
            }
            val ekeyStarted = System.nanoTime()
            if (encodedEkey(temporary, cancelled) != spec.ekey) return false
            ekeyNanos = System.nanoTime() - ekeyStarted
            val blteStarted = System.nanoTime()
            // Reconstruction consumes encoded bytes; E frames pass through.
            ForeverBlte.verifyEncodedObject(temporary, spec.decodedSize, spec.contentKey, cancelled)
            blteNanos = System.nanoTime() - blteStarted
            succeeded = true
            return true
        } catch (cancelledException: java.util.concurrent.CancellationException) {
            throw cancelledException
        } catch (_: Exception) {
            return false
        } finally {
            val cleanupStarted = System.nanoTime()
            if (temporary.exists()) temporary.delete()
            val cleanupNanos = System.nanoTime() - cleanupStarted
            if (succeeded && replayTimings != null) {
                replayTimings.openHeaderNanos = Math.addExact(replayTimings.openHeaderNanos, openHeaderNanos)
                replayTimings.copyNanos = Math.addExact(replayTimings.copyNanos, copyNanos)
                replayTimings.ekeyNanos = Math.addExact(replayTimings.ekeyNanos, ekeyNanos)
                replayTimings.blteNanos = Math.addExact(replayTimings.blteNanos, blteNanos)
                replayTimings.cleanupNanos = Math.addExact(replayTimings.cleanupNanos, cleanupNanos)
                replayTimings.totalVerifyNanos = Math.addExact(replayTimings.totalVerifyNanos, System.nanoTime() - totalVerifyStarted)
            }
        }
    }

    /**
     * Staged (flags=2) verification through the caller-owned replay reader:
     * no object copy, no private temporary, no whole-file hash. Order and
     * guards are frozen: flags=0 plus entry-size guard, addExact
     * start=[offset]+30/end=[start]+N within ARCHIVE_LIMIT, ensureSegment,
     * pre checkBinding, exact 30-byte header via readAt plus local-header
     * and full-EKey proof, bounded [start, start+N) stream into
     * ForeverBlte.verifyEncodedRange, consumed-exactly-N check, post
     * checkBinding. Only full success returns true; cancellation rethrows
     * and every other failure returns false, so the caller rejects the
     * staged object exactly like the copy path. No fallback to the copy
     * verifier after a mismatch, no materialized object, no retained FD
     * beyond the single reader owned by the writer call.
     */
    private fun verifyStagedReplayObject(
        outputDataFiles: File,
        reader: CascReplayReader,
        record: ExistingIndexRecord,
        spec: IncrementalObjectSpec,
        cancelled: () -> Boolean,
        replayTimings: StagedReplayTimings,
    ): Boolean {
        if (record.flags != 0 || record.entry.size != LOCAL_HEADER_SIZE.toLong() + spec.encodedSize) return false
        if (spec.encodedSize < 0 || spec.decodedSize < 0) return false
        val totalVerifyStarted = System.nanoTime()
        var bindingNanos = 0L
        var rangeVerifyNanos = 0L
        var succeeded = false
        try {
            val start = Math.addExact(record.entry.archiveOffset, LOCAL_HEADER_SIZE.toLong())
            val end = Math.addExact(start, spec.encodedSize)
            if (start < 0 || end < 0 || end > ARCHIVE_LIMIT) return false
            checkCancelled(cancelled)
            reader.ensureSegment(File(outputDataFiles, "data.%03d".format(record.entry.archiveId)))
            val bindingStarted = System.nanoTime()
            reader.checkBinding(end)
            val header = ByteArray(LOCAL_HEADER_SIZE)
            readAtFully(reader, header, 0, LOCAL_HEADER_SIZE, record.entry.archiveOffset, cancelled)
            validateLocalHeader(header, record.entry)
            if (header.copyOfRange(0, 16).reversedArray().hex() != spec.ekey) return false
            bindingNanos = System.nanoTime() - bindingStarted
            val bounded = BoundedReplayInputStream(reader, start, spec.encodedSize, cancelled)
            val rangeStarted = System.nanoTime()
            ForeverBlte.verifyEncodedRange(bounded, spec.encodedSize, spec.ekey, spec.decodedSize, spec.contentKey, cancelled)
            rangeVerifyNanos = System.nanoTime() - rangeStarted
            if (bounded.remaining != 0L) return false
            val postStarted = System.nanoTime()
            reader.checkBinding(end)
            bindingNanos = Math.addExact(bindingNanos, System.nanoTime() - postStarted)
            succeeded = true
            return true
        } catch (cancelledException: java.util.concurrent.CancellationException) {
            throw cancelledException
        } catch (_: Exception) {
            return false
        } finally {
            if (succeeded) {
                replayTimings.bindingNanos = Math.addExact(replayTimings.bindingNanos, bindingNanos)
                replayTimings.rangeVerifyNanos = Math.addExact(replayTimings.rangeVerifyNanos, rangeVerifyNanos)
                replayTimings.totalVerifyNanos = Math.addExact(replayTimings.totalVerifyNanos, System.nanoTime() - totalVerifyStarted)
            }
        }
    }

    /**
     * Caller-owned window over one staged object: reads only
     * [start, start+encodedSize), never the following object. Zero-length
     * reads return 0; once the window is consumed the stream reports EOF
     * without touching the reader, so the parser can neither over-read into
     * the next object nor mistake a short segment for valid data. skip() is
     * prohibited so a digest observer can never be bypassed; mark/reset are
     * unsupported and available() never decides length.
     */
    private class BoundedReplayInputStream(
        private val reader: CascReplayReader,
        start: Long,
        encodedSize: Long,
        private val cancelled: () -> Boolean,
    ) : InputStream() {
        private var position = start
        var remaining: Long = encodedSize
            private set

        override fun read(): Int {
            if (remaining <= 0) return -1
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || off > buffer.size || len > buffer.size - off) {
                throw IndexOutOfBoundsException("CASC replay read is outside its buffer")
            }
            if (len == 0) return 0
            if (remaining <= 0) return -1
            checkCancelled(cancelled)
            val count = min(len.toLong(), remaining).toInt()
            val served = reader.readAt(buffer, off, count, position)
            if (served <= 0) throw IllegalStateException("truncated CASC staged object")
            position += served
            remaining -= served
            return served
        }

        override fun skip(n: Long): Long =
            throw UnsupportedOperationException("CASC replay stream does not support skip")
    }

    private fun readAtFully(
        reader: CascReplayReader,
        buffer: ByteArray,
        off: Int,
        len: Int,
        position: Long,
        cancelled: () -> Boolean,
    ) {
        if (off < 0 || len < 0 || off > buffer.size || len > buffer.size - off) {
            throw IndexOutOfBoundsException("CASC replay read is outside its buffer")
        }
        if (position < 0) throw IllegalArgumentException("negative CASC replay read position")
        var offset = off
        var remaining = len
        var at = position
        while (remaining > 0) {
            checkCancelled(cancelled)
            val count = reader.readAt(buffer, offset, remaining, at)
            if (count <= 0) throw IllegalStateException("truncated CASC staged segment")
            offset += count
            at += count
            remaining -= count
        }
    }

    /**
     * Read-only identity proof for one overlap checkpoint file: the same
     * inode that was frozen, never a symlink or non-regular file. A missing
     * file key fails closed outright — an absolute path is not an identity
     * proof and must never stand in for a device/inode binding. The size is
     * NOT part of the key: the writer appends new frames past the frozen end
     * while the verifier reads, so only truncation below
     * [OverlapPrefixFreeze.cpByteEnd] fails; expected growth never
     * invalidates the frozen prefix.
     */
    internal fun checkpointFileKey(file: File): String {
        val path = file.toPath()
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("resumable CASC checkpoint changed before overlap freeze")
        }
        val attributes = Files.readAttributes(
            path,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        return attributes.fileKey()?.toString()
            ?: throw IllegalStateException("resumable CASC checkpoint has no stable file identity; refusing overlap verification")
    }

    /**
     * Re-prove the frozen checkpoint seal against the live path: same inode,
     * same header identity, size at or past the frozen byte end. Runs once
     * before the verifier starts and again after every sealed group before
     * its ack. Writer append growth past the frozen end is tolerated;
     * replacement, truncation, or header change fail closed with nothing
     * acknowledged and nothing published.
     */
    private fun checkCheckpointStillSealed(checkpointFile: File, freeze: OverlapPrefixFreeze) {
        val path = checkpointFile.toPath()
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("CASC sealed checkpoint changed under its verifier")
        }
        if (checkpointFileKey(checkpointFile) != freeze.cpFileKey) {
            throw IllegalStateException("CASC sealed checkpoint was replaced under its verifier")
        }
        if (Files.size(path) < freeze.cpByteEnd) {
            throw IllegalStateException("CASC sealed checkpoint truncated under its verifier")
        }
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (channel.size() < freeze.cpByteEnd) {
                throw IllegalStateException("CASC sealed checkpoint truncated under its verifier")
            }
            val header = ByteArray(RESUME_HEADER_SIZE)
            readFullyAt(channel, ByteBuffer.wrap(header), 0L)
            if (!header.copyOfRange(0, RESUME_MAGIC.size).contentEquals(RESUME_MAGIC) ||
                !header.copyOfRange(RESUME_MAGIC.size, RESUME_HEADER_SIZE).contentEquals(freeze.identityHash)
            ) throw IllegalStateException("CASC sealed checkpoint belongs to a different target or source revision")
        }
    }

    /** Read-only peek: does this identity own a checkpoint with objects? */
    internal fun resumableCheckpointHasObjects(
        outputDataFiles: File,
        checkpointDirectory: File,
        checkpointIdentity: String,
        cancelled: () -> Boolean,
    ): Boolean {
        checkCancelled(cancelled)
        val checkpointDir = checkpointDirectory.toPath().toAbsolutePath().normalize()
        rejectSymlinkParents(checkpointDirectory)
        rejectSymlinkParents(outputDataFiles)
        val identityHash = MessageDigest.getInstance("SHA-256").digest(checkpointIdentity.toByteArray(Charsets.UTF_8))
        val ownerPath = checkpointDir.resolve(RESUME_OWNER_NAME)
        val checkpointPath = checkpointDir.resolve(RESUME_CHECKPOINT_NAME)
        if (!Files.exists(ownerPath, LinkOption.NOFOLLOW_LINKS) && !Files.exists(checkpointPath, LinkOption.NOFOLLOW_LINKS)) {
            return false
        }
        requireExactFile(ownerPath, RESUME_MAGIC + identityHash, "resumable CASC owner marker")
        if (Files.isSymbolicLink(checkpointPath) || !Files.isRegularFile(checkpointPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("resumable CASC checkpoint is missing or unsafe")
        }
        val size = Files.size(checkpointPath)
        if (size < RESUME_HEADER_SIZE) throw IllegalStateException("resumable CASC checkpoint is truncated")
        if (size > MAX_RESUME_CHECKPOINT_BYTES) {
            throw IllegalStateException("resumable CASC checkpoint size exceeds its bounded object-frame limit")
        }
        FileChannel.open(checkpointPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val header = ByteArray(RESUME_HEADER_SIZE)
            readFullyAt(channel, ByteBuffer.wrap(header), 0L)
            if (!header.copyOfRange(0, RESUME_MAGIC.size).contentEquals(RESUME_MAGIC) ||
                !header.copyOfRange(RESUME_MAGIC.size, RESUME_HEADER_SIZE).contentEquals(identityHash)
            ) throw IllegalStateException("resumable CASC checkpoint belongs to a different target or source revision")
        }
        return size > RESUME_HEADER_SIZE
    }

    /**
     * Read-only listing of owned staged segment IDs for the overlap capacity
     * probe. Regular `data.NNN` files only, at most 1024; anything else in
     * the stage fails closed exactly like the load path, without deleting.
     */
    internal fun resumableStagedSegmentIds(outputDataFiles: File): List<Int> {
        val directory = outputDataFiles.toPath().toAbsolutePath().normalize()
        rejectSymlinkParents(outputDataFiles)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("resumable CASC output is not a regular directory")
        }
        val ids = ArrayList<Int>()
        Files.newDirectoryStream(directory).use { children ->
            children.forEach { path ->
                if (Files.isSymbolicLink(path)) throw IllegalStateException("resumable CASC stage contains a symlink")
                val name = path.fileName.toString()
                if (!name.matches(Regex("data\\.[0-9]{3,4}"))) return@forEach
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("resumable CASC segment is not a regular file")
                val id = name.substringAfter("data.").toIntOrNull()
                    ?: throw IllegalStateException("resumable CASC segment ID is malformed")
                if (id !in 0..1023 || !ids.add(id)) throw IllegalStateException("resumable CASC segment ID is invalid")
            }
        }
        if (ids.size > 1024) throw IllegalStateException("resumable CASC stage exceeds the available archive ID range")
        return ids.sorted()
    }

    /**
     * Overlap admission capacity: new segments start above the frozen prefix
     * maximum, so the plan's segment count must still fit the 0..1023 ID
     * range. Runs before any write, identically in the original and the fresh
     * plan, so approval always covers the sealed layout; never silently added
     * after consent.
     */
    internal fun requireOverlapSegmentCapacity(prefixMaxId: Int, newSegmentCount: Int) {
        if (prefixMaxId < -1 || prefixMaxId > 1023) throw IllegalStateException("overlap CASC prefix segment ID is invalid")
        if (newSegmentCount < 0) throw IllegalArgumentException("overlap CASC new segment count is invalid")
        if (prefixMaxId + newSegmentCount > 1023) {
            throw IllegalStateException("CASC archive id capacity is exhausted; refusing to overwrite old segments")
        }
    }

    /**
     * The sealed-prefix verifier: exactly one thread, at most one open
     * segment FD at a time, read-only over old prefix files. Every frozen
     * checkpoint frame is re-read from the checkpoint file (bounded to the
     * frozen byte end, so writer appends past it never invalidate the
     * prefix), cross-checked against the admitted catalog tuples, then
     * crypto-verified group by group. A group is acknowledged only after ALL
     * its objects pass header/EKey/BLTE/CKey/size checks AND the exact
     * post-group sealed binding holds; a failed or cancelled group leaves
     * prior acknowledgements valid for this run but never advances a partial
     * group. Cancellation, peer failure, and corruption all abort without
     * deleting, truncating, or rewriting any old or new durable state, and
     * without publishing anything: repair is explicitly not performed here.
     */
    /**
     * The sealed-prefix verifier: exactly one thread, at most one open
     * segment FD at a time, read-only over old prefix files, O(128 KiB +
     * 34 + 1024 + targetObjectCount/8) memory regardless of group size. Every
     * frozen checkpoint frame is re-read from the checkpoint file (bounded to
     * the frozen byte end, so writer appends past it never invalidate the
     * prefix) and checked either through the legacy lockstep stream or the
     * bounded membership lookup plus duplicate bitmap. Each object is
     * crypto-verified streaming inside its sealed group; a group is
     * acknowledged by exact wanted keys in membership mode, or by count and
     * key range in legacy mode, only
     * after ALL its objects pass header/EKey/BLTE/CKey/size checks AND the
     * post-group guards hold: the exact segment sealed binding plus a fresh
     * checkpoint seal recheck (same inode, header identity, size at or past
     * the frozen end — growth is tolerated, replacement/truncation/header
     * change is not). A failed or cancelled group leaves prior
     * acknowledgements valid for this run but never advances a partial
     * group. Cancellation, peer failure, and corruption all abort without
     * deleting, truncating, or rewriting any old or new durable state, and
     * without publishing anything: repair is explicitly not performed here.
     */
    private fun runOverlapPrefixVerify(
        outputDataFiles: File,
        checkpointFile: File,
        freeze: OverlapPrefixFreeze,
        officialStream: OverlapOfficialStream?,
        readerFactory: ((() -> Boolean) -> CascReplayReader),
        coordinator: CascOverlapCoordinator,
        cancelled: () -> Boolean,
        targetObjectCount: Int,
        targetMembership: ((String) -> IndexedValue<IncrementalObjectSpec>)?,
    ) {
        val verifyStarted = System.nanoTime()
        checkCheckpointStillSealed(checkpointFile, freeze)
        var verifiedGroups = 0L
        val membershipSeen = if (targetMembership == null) null else java.util.BitSet(targetObjectCount)
        val wantedKeys = coordinator.snapshotWantedKeys()
        val verify: (Sequence<IncrementalObjectSpec>?) -> Unit = { officialSeq ->
            val officialIt = officialSeq?.iterator()
            var officialNext: IncrementalObjectSpec? = if (officialIt != null && officialIt.hasNext()) officialIt.next() else null
            val path = checkpointFile.toPath()
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val reader = readerFactory(cancelled)
                try {
                    var position = RESUME_HEADER_SIZE.toLong()
                    var expectedArchiveId = freeze.firstArchiveId
                    var groupId = -1
                    var groupEnd = -1L
                    var groupCursor = SEGMENT_HEADER_SIZE.toLong()
                    var groupCount = 0L
                    var groupBytes = 0L
                    var groupMinKey: String? = null
                    var groupMaxKey: String? = null
                    val groupWantedKeys = HashSet<String>(wantedKeys.size)
                    var source: SegmentBufferedSource? = null
                    fun flushGroup() {
                        if (groupId < 0) return
                        if (groupCursor != groupEnd) {
                            throw IllegalStateException("CASC sealed group does not tile its frozen end: data.$groupId")
                        }
                        val active = requireNotNull(source) { "CASC sealed group has no open segment: data.$groupId" }
                        if (!active.atEnd) {
                            throw IllegalStateException("CASC sealed group left unread tail bytes: data.$groupId")
                        }
                        // POST guards, before any ack: the exact segment seal
                        // first, then the checkpoint seal (same inode, header
                        // identity, size floor). Either failure fails closed
                        // with nothing acknowledged and nothing published.
                        reader.checkSealedBinding(groupEnd)
                        checkCheckpointStillSealed(checkpointFile, freeze)
                        val groupMin = requireNotNull(groupMinKey) { "CASC sealed group has no verified objects: data.$groupId" }
                        val groupMax = requireNotNull(groupMaxKey) { "CASC sealed group has no verified objects: data.$groupId" }
                        if (targetMembership == null) {
                            coordinator.ackGroup(groupCount, groupMin, groupMax, groupBytes)
                        } else {
                            coordinator.ackGroupExact(groupCount, groupWantedKeys, groupBytes)
                        }
                        verifiedGroups++
                        if (verifiedGroups % 64 == 0L) {
                            diagnostics.info("CASC sealed prefix groups=$verifiedGroups objects=${coordinator.ackedObjects.get()} elapsedMs=${(System.nanoTime() - verifyStarted) / 1_000_000}")
                        }
                        groupId = -1
                        source = null
                        groupCount = 0L
                        groupBytes = 0L
                        groupMinKey = null
                        groupMaxKey = null
                        groupWantedKeys.clear()
                    }
                    while (position < freeze.cpByteEnd) {
                        checkCancelled(cancelled)
                        if (coordinator.peerCancel.get()) throw java.util.concurrent.CancellationException("client provisioning cancelled")
                        if (freeze.cpByteEnd - position < RESUME_FRAME_SIZE) {
                            throw IllegalStateException("CASC sealed checkpoint prefix ends mid-frame")
                        }
                        val frame = ByteArray(RESUME_FRAME_SIZE)
                        readFullyAt(channel, ByteBuffer.wrap(frame), position)
                        val expectedCrc = ByteBuffer.wrap(frame, RESUME_FRAME_DATA_SIZE, 4).order(ByteOrder.BIG_ENDIAN).int
                        val crc = CRC32().also { it.update(frame, 0, RESUME_FRAME_DATA_SIZE) }.value.toInt()
                        if (crc != expectedCrc || frame.copyOfRange(1, 8).any { it.toInt() != 0 }) {
                            throw IllegalStateException("CASC sealed checkpoint frame checksum or reserved fields are invalid")
                        }
                        when (frame[0].toInt() and 0xff) {
                            RESUME_OBJECT_FRAME -> {
                                val record = decodeResumeObject(frame)
                                if (!record.ekey.matches(Regex("[0-9a-f]{32}")) || !record.contentKey.matches(Regex("[0-9a-f]{32}")) ||
                                    record.encodedSize !in 1..(ARCHIVE_LIMIT - SEGMENT_HEADER_SIZE - LOCAL_HEADER_SIZE) ||
                                    record.decodedSize < 0 || record.archiveId !in 0..1023 || record.offset < SEGMENT_HEADER_SIZE
                                ) throw IllegalStateException("CASC sealed prefix object record is outside supported bounds")
                                val end = Math.addExact(record.offset, Math.addExact(LOCAL_HEADER_SIZE.toLong(), record.encodedSize))
                                if (end > ARCHIVE_LIMIT) throw IllegalStateException("CASC sealed prefix object exceeds its segment bound")
                                val key = record.ekey.lowercase()
                                if (targetMembership == null) {
                                    // Legacy ordered join; the exact production
                                    // path does not assume EKey chronology.
                                    val official = officialNext
                                        ?: throw IllegalStateException("CASC sealed prefix is longer than the admitted official sequence")
                                    if (official.ekey != key || official.contentKey != record.contentKey.lowercase() ||
                                        official.encodedSize != record.encodedSize || official.decodedSize != record.decodedSize
                                    ) throw IllegalStateException("CASC sealed prefix object differs from the admitted target manifest")
                                    officialNext = if (officialIt!!.hasNext()) officialIt.next() else null
                                } else {
                                    verifyTargetMembership(
                                        IncrementalObjectSpec(key, record.contentKey.lowercase(), record.encodedSize, record.decodedSize),
                                        targetObjectCount,
                                        targetMembership,
                                        requireNotNull(membershipSeen),
                                        "CASC sealed prefix",
                                    )
                                }
                                if (record.archiveId != groupId) {
                                    flushGroup()
                                    if (record.archiveId != expectedArchiveId || record.offset != SEGMENT_HEADER_SIZE.toLong()) {
                                        throw IllegalStateException("CASC sealed prefix segment sequence or first offset is invalid")
                                    }
                                    expectedArchiveId = Math.addExact(record.archiveId, 1)
                                    groupId = record.archiveId
                                    groupEnd = freeze.segmentEnds[groupId]
                                        ?: throw IllegalStateException("CASC sealed prefix references an unfrozen segment: data.$groupId")
                                    if (groupEnd <= SEGMENT_HEADER_SIZE || groupEnd > ARCHIVE_LIMIT) {
                                        throw IllegalStateException("CASC sealed group end is outside the archive bound: data.$groupId")
                                    }
                                    groupCursor = SEGMENT_HEADER_SIZE.toLong()
                                    reader.ensureSegment(File(outputDataFiles, "data.%03d".format(groupId)))
                                    reader.checkBinding(groupEnd)
                                    source = SegmentBufferedSource(reader, SEGMENT_HEADER_SIZE.toLong(), groupEnd, cancelled)
                                } else if (record.offset != groupCursor) {
                                    throw IllegalStateException("CASC sealed prefix object offsets are not contiguous: data.$groupId")
                                }
                                val active = requireNotNull(source) { "CASC sealed group has no open segment: data.$groupId" }
                                if (active.position != record.offset) {
                                    throw IllegalStateException("CASC sealed group cursor left its checkpoint offset: data.$groupId")
                                }
                                val entry = IndexEntry(
                                    key.hexBytes().copyOfRange(0, 9),
                                    groupId,
                                    record.offset,
                                    LOCAL_HEADER_SIZE.toLong() + record.encodedSize,
                                )
                                val header = ByteArray(LOCAL_HEADER_SIZE)
                                active.readFully(header, 0, LOCAL_HEADER_SIZE)
                                validateLocalHeader(header, entry)
                                if (header.copyOfRange(0, 16).reversedArray().hex() != key) {
                                    throw IllegalStateException("CASC sealed prefix EKey mismatch: data.$groupId")
                                }
                                val window = active.window(record.encodedSize)
                                // Reconstruction consumes encoded bytes; E frames pass through.
                                ForeverBlte.verifyEncodedRange(window, record.encodedSize, key, record.decodedSize, record.contentKey, cancelled)
                                if (window.remaining != 0L) throw IllegalStateException("CASC sealed prefix object left unread bytes: data.$groupId")
                                if (targetMembership != null && key in wantedKeys) groupWantedKeys += key
                                if (groupMinKey == null || key < groupMinKey!!) groupMinKey = key
                                if (groupMaxKey == null || key > groupMaxKey!!) groupMaxKey = key
                                groupCount = Math.addExact(groupCount, 1L)
                                groupBytes = Math.addExact(groupBytes, record.encodedSize)
                                groupCursor = end
                            }
                            else -> throw IllegalStateException("CASC sealed prefix contains an unexpected frame type")
                        }
                        position += RESUME_FRAME_SIZE
                    }
                    flushGroup()
                } finally {
                    reader.close()
                }
            }
        }
        if (targetMembership == null) {
            requireNotNull(officialStream) { "CASC overlap official stream is unavailable" }.invoke(
                { coordinator.peerCancel.get() || cancelled() },
                { officialSeq -> verify(officialSeq) },
            )
        } else {
            verify(null)
        }
        diagnostics.info("CASC sealed prefix verifier done groups=$verifiedGroups objects=${coordinator.ackedObjects.get()} elapsedMs=${(System.nanoTime() - verifyStarted) / 1_000_000}")
    }


    /**
     * Caller-owned sequential window over one sealed segment range. Reads
     * travel to the reader in large spans (one 128 KiB buffer refill) and
     * never past the sealed end; per-object windows cap exactly N bytes so
     * the shared parser can neither over-read into the next object nor
     * mistake a short segment for valid data. Zero-length reads return 0;
     * end-of-range reports EOF without touching the reader.
     */
    private class SegmentBufferedSource(
        private val reader: CascReplayReader,
        start: Long,
        private val endExclusive: Long,
        private val cancelled: () -> Boolean,
    ) {
        private val buffer = ByteArray(SEGMENT_BUFFER_SIZE)
        private var bufferedFrom = start
        private var bufferedTo = start
        var position = start
            private set
        val atEnd: Boolean get() = position == endExclusive

        fun readFully(out: ByteArray, off: Int, len: Int) {
            if (off < 0 || len < 0 || off > out.size || len > out.size - off) {
                throw IndexOutOfBoundsException("CASC sealed group read is outside its buffer")
            }
            var offset = off
            var remaining = len
            while (remaining > 0) {
                if (position >= bufferedTo) refill()
                val available = (bufferedTo - position).toInt()
                if (available <= 0) throw IllegalStateException("truncated CASC sealed segment")
                val count = minOf(remaining, available)
                System.arraycopy(buffer, (position - bufferedFrom).toInt(), out, offset, count)
                offset += count
                position += count
                remaining -= count
            }
        }

        fun window(encodedSize: Long): BoundedWindow {
            if (encodedSize < 0 || position + encodedSize > endExclusive) {
                throw IllegalStateException("CASC sealed object window escapes its group")
            }
            return BoundedWindow(encodedSize)
        }

        private fun refill() {
            checkCancelled(cancelled)
            if (position >= endExclusive) throw IllegalStateException("truncated CASC sealed segment")
            val want = minOf(buffer.size.toLong(), endExclusive - position).toInt()
            var served = 0
            while (served < want) {
                checkCancelled(cancelled)
                val count = reader.readAt(buffer, served, want - served, position + served)
                if (count < 0) throw IllegalStateException("cannot read CASC sealed segment")
                if (count == 0) throw IllegalStateException("truncated CASC sealed segment")
                served += count
            }
            bufferedFrom = position
            bufferedTo = position + served
        }

        inner class BoundedWindow(var remaining: Long) : InputStream() {
            override fun read(): Int {
                if (remaining <= 0) return -1
                val single = ByteArray(1)
                return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
            }

            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                if (off < 0 || len < 0 || off > buffer.size || len > buffer.size - off) {
                    throw IndexOutOfBoundsException("CASC sealed window read is outside its buffer")
                }
                if (len == 0) return 0
                if (remaining <= 0) return -1
                val count = minOf(len.toLong(), remaining).toInt()
                this@SegmentBufferedSource.readFully(buffer, off, count)
                remaining -= count
                return count
            }
        }
    }

    /**
     * Individual full-strength proof of one checkpointed staged copy, for
     * the blocked-budget path only: when inherited checkpointed raws hold the
     * 34-key/1 GiB quota and no committable or staged work can drain it, the
     * producer may retire exactly that key's redundant private raw after its
     * ACTUAL staged copy passes the same gates as the serial verifier (30
     * header, full EKey, entire encoded range BLTE/CKey/size) with individual
     * pre/post path binding. No metadata-header-only or raw-hash-only proof
     * is accepted, and nothing is deleted on any failure. The verifier may
     * repeat these objects later in its ordered group pass; this proof never
     * acknowledges a group. Call only when actually blocked (or to prepare
     * the bounded key set), never as a per-object scan of the manifest.
     *
     * Returns true only on full success; any mismatch returns false without
     * deleting. Cancellation rethrows.
     */
    /**
     * Individual full-strength proof of one checkpointed staged copy, for
     * the blocked-budget path only: when checkpointed leftover raws hold the
     * 34-key/1 GiB quota and no committable or staged work can drain it, the
     * producer may retire exactly that key's redundant private raw after its
     * ACTUAL staged copy passes the same gates as the serial verifier. The
     * location comes from the freeze record collected during the single load
     * pass (no checkpoint rescan per key); the official spec fields must all
     * match; the proof itself runs through the caller-owned reader factory
     * with the exact staged-replay verification (pre/post path-vs-FD
     * binding by device/inode plus the whole-range hash), never a bare
     * path/size check that a replaced same-size inode would satisfy. The
     * owned private raw is retired only after this proof; any binding
     * mismatch fails without retiring and without falling back to a fetch.
     * The verifier may repeat this object later in its ordered group pass;
     * this proof never acknowledges a group.
     *
     * Returns true only on full success; any mismatch returns false without
     * deleting. Cancellation rethrows. Call only when actually blocked (or
     * to prepare the bounded key set), never as a per-object scan.
     */
    internal fun verifyStagedCopyForRawRetire(
        outputDataFiles: File,
        record: OverlapRawKeyRecord,
        spec: IncrementalObjectSpec,
        readerFactory: ((() -> Boolean) -> CascReplayReader),
        cancelled: () -> Boolean,
    ): Boolean {
        checkCancelled(cancelled)
        val normalized = normalizeIncrementalSpec(spec)
        validateIncrementalSpec(normalized)
        if (normalized.ekey != record.ekey.lowercase() || normalized.contentKey != record.contentKey.lowercase() ||
            normalized.encodedSize != record.encodedSize || normalized.decodedSize != record.decodedSize
        ) return false
        if (record.archiveId !in 0..1023 || record.offset < SEGMENT_HEADER_SIZE) return false
        val total = Math.addExact(LOCAL_HEADER_SIZE.toLong(), normalized.encodedSize)
        if (record.offset > ARCHIVE_LIMIT - total) return false
        val entry = IndexEntry(
            normalized.ekey.hexBytes().copyOfRange(0, 9),
            record.archiveId,
            record.offset,
            total,
        )
        val reader = readerFactory(cancelled)
        try {
            return verifyStagedReplayObject(
                outputDataFiles,
                reader,
                ExistingIndexRecord(normalized.ekey, entry, 0, Long.MAX_VALUE),
                normalized,
                cancelled,
                StagedReplayTimings(),
            )
        } catch (cancelledException: java.util.concurrent.CancellationException) {
            throw cancelledException
        } catch (_: Exception) {
            return false
        } finally {
            reader.close()
        }
    }

    private fun readCandidateLocalRecord(
        sourceDataFiles: File,
        hint: CandidateHint,
        ekey: String,
        expectedEncodedSize: Long?,
        cancelled: () -> Boolean,
    ): ExistingIndexRecord? {
        if (hint.size < LOCAL_HEADER_SIZE || hint.size > ARCHIVE_LIMIT) return null
        try {
            checkCancelled(cancelled)
            val archiveFile = File(sourceDataFiles, "data.%03d".format(hint.archiveId))
            if (Files.isSymbolicLink(archiveFile.toPath()) || !Files.isRegularFile(archiveFile.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
            FileChannel.open(archiveFile.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                if (hint.archiveOffset > channel.size() - hint.size || hint.archiveOffset > ARCHIVE_LIMIT - hint.size) return null
                val entry = IndexEntry(ekey.hexBytes().copyOfRange(0, 9), hint.archiveId, hint.archiveOffset, hint.size)
                val header = ByteArray(LOCAL_HEADER_SIZE)
                readFullyAt(channel, ByteBuffer.wrap(header), hint.archiveOffset)
                validateLocalHeader(header, entry)
                if (header.copyOfRange(0, 16).reversedArray().hex() != ekey) return null
                val flags = u16(header, 0x14)
                if (flags != 0 ||
                    (expectedEncodedSize != null && hint.size != LOCAL_HEADER_SIZE.toLong() + expectedEncodedSize)
                ) return null
                return ExistingIndexRecord(ekey, entry, flags, 0)
            }
        } catch (cancelledException: java.util.concurrent.CancellationException) {
            throw cancelledException
        } catch (_: Exception) {
            // Index and update entries are only location hints. A bad, stale,
            // collided, or nonmatching location is a miss, never content truth.
            return null
        }
    }

    private data class CandidateSearchResult(
        val entry: ExistingIndexRecord?,
        val examined: Long,
        val rejected: Long,
    )

    private data class CanonicalCandidateMatch(
        val search: CandidateSearchResult,
        val reconstructionRecords: List<ExistingIndexRecord>,
    )

    /** Shared by planning and writing so reuse admission uses identical checks. */
    private fun findCanonicalCandidate(
        sourceDataFiles: File,
        candidates: CandidateHintStore,
        spec: IncrementalObjectSpec,
        temporary: File,
        cancelled: () -> Boolean,
        table: DiskIndexTable,
        bucketCounts: IntArray,
        totalRecords: Long,
        outputSegmentHeadersAdded: BooleanArray,
        sourceSegmentHeaders: Array<List<ExistingIndexRecord>?>,
        sourceSegmentHeaderChecked: BooleanArray,
    ): CanonicalCandidateMatch {
        var reconstructionRecords: List<ExistingIndexRecord> = emptyList()
        val search = findVerifiedCandidate(sourceDataFiles, candidates, spec, temporary, cancelled) { candidate ->
            val sourceId = candidate.entry.archiveId
            if (!sourceSegmentHeaderChecked[sourceId]) {
                sourceSegmentHeaderChecked[sourceId] = true
                sourceSegmentHeaders[sourceId] = readValidatedSegmentReconstructionRecords(
                    sourceDataFiles, sourceId, cancelled,
                )
            }
            val headers = sourceSegmentHeaders[sourceId] ?: return@findVerifiedCandidate false
            val headersToAdd = if (outputSegmentHeadersAdded[sourceId]) emptyList() else headers
            if (!canAddCanonicalTarget(table, bucketCounts, totalRecords, headersToAdd, candidate, computeBucket(spec.ekey.hexBytes()))) {
                return@findVerifiedCandidate false
            }
            reconstructionRecords = headersToAdd
            true
        }
        return CanonicalCandidateMatch(search, reconstructionRecords)
    }

    private fun findVerifiedCandidate(
        sourceDataFiles: File,
        candidates: CandidateHintStore,
        spec: IncrementalObjectSpec,
        temporary: File,
        cancelled: () -> Boolean,
        acceptCandidate: (ExistingIndexRecord) -> Boolean = { true },
    ): CandidateSearchResult = findVerifiedCandidate(
        sourceDataFiles,
        candidates,
        spec.ekey,
        spec.contentKey,
        spec.decodedSize,
        spec.encodedSize,
        temporary,
        cancelled,
        acceptCandidate,
    )

    private fun findVerifiedCandidate(
        sourceDataFiles: File,
        candidates: CandidateHintStore,
        spec: RuntimeObjectSpec,
        temporary: File,
        cancelled: () -> Boolean,
    ): CandidateSearchResult = findVerifiedCandidate(
        sourceDataFiles,
        candidates,
        spec.ekey,
        spec.contentKey,
        spec.decodedSize,
        null,
        temporary,
        cancelled,
    )

    private fun findVerifiedCandidate(
        sourceDataFiles: File,
        candidates: CandidateHintStore,
        ekey: String,
        contentKey: String,
        decodedSize: Long,
        expectedEncodedSize: Long?,
        temporary: File,
        cancelled: () -> Boolean,
        acceptCandidate: (ExistingIndexRecord) -> Boolean = { true },
    ): CandidateSearchResult {
        var verified: ExistingIndexRecord? = null
        var examined = 0L
        var rejected = 0L
        candidates.forEachCandidate(ekey.hexBytes().copyOfRange(0, 9)) { hint ->
            if (verified != null) return@forEachCandidate false
            examined++
            val candidate = readCandidateLocalRecord(sourceDataFiles, hint, ekey, expectedEncodedSize, cancelled)
            if (candidate == null) {
                rejected++
                return@forEachCandidate true
            }
            val verificationSpec = IncrementalObjectSpec(
                ekey,
                contentKey,
                candidate.entry.size - LOCAL_HEADER_SIZE,
                decodedSize,
            )
            if (!verifyExistingIncrementalObject(sourceDataFiles, candidate, verificationSpec, temporary, cancelled)) {
                rejected++
                return@forEachCandidate true
            }
            if (!acceptCandidate(candidate)) {
                rejected++
                return@forEachCandidate true
            }
            verified = candidate
            false
        }
        return CandidateSearchResult(verified, examined, rejected)
    }

    private fun readValidatedSegmentReconstructionRecords(
        sourceDataFiles: File,
        archiveId: Int,
        cancelled: () -> Boolean,
    ): List<ExistingIndexRecord>? {
        try {
            checkCancelled(cancelled)
            val archiveFile = File(sourceDataFiles, "data.%03d".format(archiveId))
            if (Files.isSymbolicLink(archiveFile.toPath()) || !Files.isRegularFile(archiveFile.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
            FileChannel.open(archiveFile.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                if (channel.size() < SEGMENT_HEADER_SIZE) return null
                val segment = ByteArray(SEGMENT_HEADER_SIZE)
                readFullyAt(channel, ByteBuffer.wrap(segment), 0L)
                val records = segmentIndexRecords(segment, archiveId)
                records.forEachIndexed { slot, record ->
                    val header = segment.copyOfRange(slot * LOCAL_HEADER_SIZE, (slot + 1) * LOCAL_HEADER_SIZE)
                    validateLocalHeader(header, record.entry)
                    if (u16(header, 0x14) != 1 || record.entry.size != LOCAL_HEADER_SIZE.toLong()) return null
                }
                return records
            }
        } catch (cancelledException: java.util.concurrent.CancellationException) {
            throw cancelledException
        } catch (_: Exception) {
            return null
        }
    }

    private fun canAddCanonicalTarget(
        table: DiskIndexTable,
        bucketCounts: IntArray,
        totalRecords: Long,
        reconstructionRecords: List<ExistingIndexRecord>,
        target: ExistingIndexRecord,
        targetBucket: Int,
    ): Boolean {
        val additions = IntArray(16)
        val prefixes = HashSet<String>(reconstructionRecords.size + 1)
        try {
            reconstructionRecords.forEach { generated ->
                if (!prefixes.add(generated.ekey.substring(0, 18))) return false
                if (table.find(generated.ekey.hexBytes()) != null) return false
                additions[computeBucket(generated.entry.key, 1)]++
                if (generated.ekey.substring(0, 18) == target.ekey.substring(0, 18)) return false
            }
            if (!prefixes.add(target.ekey.substring(0, 18))) return false
            if (table.find(target.ekey.hexBytes()) != null) return false
            additions[targetBucket]++
        } catch (cancelledException: java.util.concurrent.CancellationException) {
            throw cancelledException
        } catch (_: EKeyPrefixCollisionException) {
            // A 9-byte-prefix collision in this old segment makes it unusable
            // for the canonical output; the target can still be downloaded anew.
            return false
        }
        for (bucket in 0..15) {
            if (bucketCounts[bucket].toLong() + additions[bucket] > MAX_INDEX_ENTRIES_PER_BUCKET) return false
        }
        return totalRecords + reconstructionRecords.size + 1L <= MAX_TOTAL_INDEX_ENTRIES
    }

    private fun writeVerifiedSourceToTemporary(
        source: File,
        temporary: File,
        spec: IncrementalObjectSpec,
        cancelled: () -> Boolean,
    ) {
        try {
            if (Files.isSymbolicLink(source.toPath()) || !Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("CASC replacement source is not a regular file")
            }
            rejectSymlinkParents(source)
            if (source.length() != spec.encodedSize) throw IllegalStateException("CASC replacement encoded size mismatch")
            var copied = 0L
            Files.newInputStream(source.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                openOwnedOutput(temporary, false).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        checkCancelled(cancelled)
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        copied = Math.addExact(copied, count.toLong())
                        if (copied > spec.encodedSize) throw IllegalStateException("CASC replacement source exceeds encoded size")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (copied != spec.encodedSize) throw IllegalStateException("CASC replacement encoded size mismatch")
            if (encodedEkey(temporary, cancelled) != spec.ekey) throw IllegalStateException("CASC replacement raw BLTE EKey mismatch")
            // Reconstruction consumes encoded bytes; E frames pass through.
            ForeverBlte.verifyEncodedObject(temporary, spec.decodedSize, spec.contentKey, cancelled)
        } catch (t: Throwable) {
            if (temporary.exists()) temporary.delete()
            throw t
        }
    }

    /** TACT hashes a chunked BLTE header/table, but a chunkless/plain object in full. */
    private fun encodedEkey(file: File, cancelled: () -> Boolean): String {
        val path = file.toPath()
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("CASC encoded object is not a regular file")
        }
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val fileSize = channel.size()
            if (fileSize < 4) throw IllegalArgumentException("CASC encoded object is truncated")
            val firstFour = ByteArray(4)
            readFullyAt(channel, ByteBuffer.wrap(firstFour), 0L)
            val blteMagic = byteArrayOf('B'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte())
            val hashLength = if (!firstFour.contentEquals(blteMagic)) {
                fileSize
            } else {
                if (fileSize < 8) throw IllegalArgumentException("truncated BLTE header")
                val encodedHeaderSize = ByteArray(4)
                readFullyAt(channel, ByteBuffer.wrap(encodedHeaderSize), 4L)
                val headerSize = encodedHeaderSize.fold(0L) { value, byte -> value * 256L + (byte.toLong() and 0xff) }
                when {
                    headerSize == 0L -> fileSize
                    headerSize < 12L || headerSize > fileSize || headerSize > MAX_EKEY_HEADER_SIZE ->
                        throw IllegalArgumentException("invalid or oversized BLTE EKey header: $headerSize")
                    else -> {
                        val chunkInfo = ByteArray(4)
                        readFullyAt(channel, ByteBuffer.wrap(chunkInfo), 8L)
                        val tableFormat = chunkInfo[0].toInt() and 0xff
                        val chunkCount = ((chunkInfo[1].toInt() and 0xff) shl 16) or
                            ((chunkInfo[2].toInt() and 0xff) shl 8) or (chunkInfo[3].toInt() and 0xff)
                        if (chunkCount <= 0 || chunkCount > MAX_EKEY_HEADER_CHUNKS) {
                            throw IllegalArgumentException("BLTE EKey chunk table exceeds safety limit: $chunkCount")
                        }
                        val descriptorSize = when (tableFormat) {
                            0x0f -> 24
                            0x10 -> 40
                            else -> throw IllegalArgumentException("unsupported BLTE EKey table format: $tableFormat")
                        }
                        if (headerSize != 12L + chunkCount.toLong() * descriptorSize) {
                            throw IllegalArgumentException("BLTE EKey header size/count mismatch")
                        }
                        headerSize
                    }
                }
            }

            val digest = MessageDigest.getInstance("MD5")
            var offset = 0L
            var left = hashLength
            val buffer = ByteArray(BUFFER_SIZE)
            while (left > 0) {
                checkCancelled(cancelled)
                val count = min(buffer.size.toLong(), left).toInt()
                readFullyAt(channel, ByteBuffer.wrap(buffer, 0, count), offset)
                digest.update(buffer, 0, count)
                offset += count
                left -= count
            }
            if (channel.size() != fileSize) throw IllegalStateException("CASC encoded object changed while hashing")
            return digest.digest().hex()
        }
    }

    private fun requireIndexCapacity(bucket: Int, entryCount: Int) {
        if (bucket !in 0..15 || entryCount < 0 || entryCount > MAX_INDEX_ENTRIES_PER_BUCKET) {
            throw IllegalArgumentException("CASC V7 local index record limit exceeded for bucket $bucket (max $MAX_INDEX_ENTRIES_PER_BUCKET)")
        }
    }

    private class DiskIndexTable(path: File, estimatedRecords: Int, private val cancelled: () -> Boolean) : AutoCloseable {
        private var tablePath = path
        private val tableStem = path.nameWithoutExtension
        private var capacity = capacityForEstimate(estimatedRecords)
        private var channel = createTableFile(path, capacity)
        private val slotBuffer = ByteBuffer.allocate(INCREMENTAL_TABLE_SLOT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        var recordCount: Int = 0
            private set

        fun find(fullKey: ByteArray): ExistingIndexRecord? {
            require(fullKey.size == 16)
            val (slot, found) = locate(fullKey)
            return if (found) decodeRecord(slotBuffer.array()) else null
        }

        /** Returns true only if this key was newly inserted. */
        fun put(record: ExistingIndexRecord): Boolean {
            val fullKey = record.ekey.hexBytes()
            if (fullKey.size != 16 || !record.entry.key.contentEquals(fullKey.copyOfRange(0, 9))) {
                throw IllegalArgumentException("invalid disk-backed CASC index record")
            }
            val (slot, found) = locate(fullKey)
            if (found) {
                writeRecord(slot, record)
                return false
            }
            if (recordCount >= MAX_TOTAL_INDEX_ENTRIES) {
                throw IllegalArgumentException("CASC V7 merged index exceeds the bounded total-record limit")
            }
            if ((recordCount + 1L) * 10L > capacity.toLong() * 6L) {
                grow()
            }
            val (newSlot, nowFound) = locate(fullKey)
            if (nowFound) {
                writeRecord(newSlot, record)
                return false
            }
            writeRecord(newSlot, record)
            recordCount++
            return true
        }

        fun forEachRecord(action: (ExistingIndexRecord) -> Unit) {
            forEachRecords(channel, capacity, action)
        }

        private fun forEachRecords(sourceChannel: FileChannel, sourceCapacity: Int, action: (ExistingIndexRecord) -> Unit) {
            val slotsPerRead = 1_024
            val bytes = ByteArray(slotsPerRead * INCREMENTAL_TABLE_SLOT_SIZE)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var firstSlot = 0
            while (firstSlot < sourceCapacity) {
                checkCancelled(cancelled)
                val count = minOf(slotsPerRead, sourceCapacity - firstSlot)
                buffer.clear()
                buffer.limit(count * INCREMENTAL_TABLE_SLOT_SIZE)
                readFullyAt(sourceChannel, buffer, firstSlot.toLong() * INCREMENTAL_TABLE_SLOT_SIZE)
                for (index in 0 until count) {
                    val at = index * INCREMENTAL_TABLE_SLOT_SIZE
                    if (bytes[at].toInt() != 0) action(decodeRecord(bytes, at))
                }
                firstSlot += count
            }
        }

        private fun locate(fullKey: ByteArray): Pair<Long, Boolean> {
            val mask = capacity - 1
            var slot = (hashPrefix(fullKey) and mask.toLong()).toInt()
            repeat(capacity) { probe ->
                if ((probe and 0x3ff) == 0) checkCancelled(cancelled)
                slotBuffer.clear()
                readFullyAt(channel, slotBuffer, slot.toLong() * INCREMENTAL_TABLE_SLOT_SIZE)
                val stored = slotBuffer.array()
                if (stored[0].toInt() == 0) return slot.toLong() to false
                var prefixMatches = true
                for (index in 0 until 9) {
                    if (stored[4 + index] != fullKey[index]) {
                        prefixMatches = false
                        break
                    }
                }
                if (prefixMatches) {
                    for (index in 0 until 16) {
                        if (stored[4 + index] != fullKey[index]) {
                            throw EKeyPrefixCollisionException("CASC short EKey prefix collision; refusing ambiguous index merge")
                        }
                    }
                    return slot.toLong() to true
                }
                slot = (slot + 1) and mask
            }
            throw IllegalStateException("CASC disk-backed index table is unexpectedly full")
        }

        private fun writeRecord(slot: Long, record: ExistingIndexRecord) {
            val fullKey = record.ekey.hexBytes()
            slotBuffer.clear()
            slotBuffer.put(0, 1)
            slotBuffer.put(1, record.flags.toByte())
            slotBuffer.put(2, if (record.targetSeen) 1.toByte() else 0.toByte())
            for (index in 0 until 16) slotBuffer.put(4 + index, fullKey[index])
            slotBuffer.putShort(20, record.entry.archiveId.toShort())
            slotBuffer.putLong(22, record.entry.archiveOffset)
            slotBuffer.putInt(30, record.entry.size.toInt())
            val generation = if (record.generation == Long.MAX_VALUE) 0xffffffffL else record.generation
            if (generation !in 0..0xffffffffL) throw IllegalArgumentException("CASC V7 index generation is outside its encoded bounds")
            slotBuffer.putInt(34, generation.toInt())
            slotBuffer.position(0)
            slotBuffer.limit(INCREMENTAL_TABLE_SLOT_SIZE)
            writeFullyAt(channel, slotBuffer, slot.toLong() * INCREMENTAL_TABLE_SLOT_SIZE)
        }

        private fun grow() {
            val growthStarted = System.nanoTime()
            diagnostics.info("CASC disk index rehash begin records=$recordCount capacity=$capacity")
            if (capacity >= INCREMENTAL_TABLE_MAX_CAPACITY) {
                throw IllegalArgumentException("CASC V7 disk index table reached its bounded capacity")
            }
            val newCapacity = capacity * 2
            val grownPath = File(tablePath.parentFile, "$tableStem-$newCapacity.bin")
            val oldChannel = channel
            val oldPath = tablePath
            val oldCapacity = capacity
            val oldCount = recordCount
            var grownChannel: FileChannel? = null
            var published = false
            try {
                grownChannel = createTableFile(grownPath, newCapacity)
                channel = grownChannel
                capacity = newCapacity
                recordCount = 0
                forEachRecords(oldChannel, oldCapacity) { record -> putWithoutGrowth(record) }
                if (recordCount != oldCount) throw IllegalStateException("CASC disk index rehash lost records")
                oldChannel.close()
                tablePath = grownPath
                grownChannel = null
                published = true
                if (!oldPath.delete()) throw IllegalStateException("cannot remove obsolete CASC disk-index table")
            } catch (t: Throwable) {
                if (!published) {
                    grownChannel?.let { runCatching { it.close() } }
                    channel = oldChannel
                    capacity = oldCapacity
                    recordCount = oldCount
                    grownPath.delete()
                }
                throw t
            } finally {
                diagnostics.info("CASC disk index rehash end records=$recordCount capacity=$capacity elapsedMs=${(System.nanoTime() - growthStarted) / 1_000_000}")
            }
        }

        private fun putWithoutGrowth(record: ExistingIndexRecord) {
            val fullKey = record.ekey.hexBytes()
            val mask = capacity - 1
            var slot = (hashPrefix(fullKey) and mask.toLong()).toInt()
            repeat(capacity) { probe ->
                if ((probe and 0x3ff) == 0) checkCancelled(cancelled)
                slotBuffer.clear()
                readFullyAt(channel, slotBuffer, slot.toLong() * INCREMENTAL_TABLE_SLOT_SIZE)
                val stored = slotBuffer.array()
                if (stored[0].toInt() == 0) {
                    writeRecord(slot.toLong(), record)
                    recordCount++
                    return
                }
                var prefixMatches = true
                for (index in 0 until 9) if (stored[4 + index] != fullKey[index]) { prefixMatches = false; break }
                if (prefixMatches) {
                    for (index in 0 until 16) if (stored[4 + index] != fullKey[index]) {
                        throw EKeyPrefixCollisionException("CASC short EKey prefix collision during disk-table rehash")
                    }
                    writeRecord(slot.toLong(), record)
                    return
                }
                slot = (slot + 1) and mask
            }
            throw IllegalStateException("CASC disk-backed index table is unexpectedly full during rehash")
        }

        private fun decodeRecord(bytes: ByteArray, at: Int = 0): ExistingIndexRecord {
            val buffer = ByteBuffer.wrap(bytes, at, INCREMENTAL_TABLE_SLOT_SIZE).slice().order(ByteOrder.LITTLE_ENDIAN)
            val fullKey = bytes.copyOfRange(at + 4, at + 20)
            val archiveId = buffer.getShort(20).toInt() and 0xffff
            val offset = buffer.getLong(22)
            val size = buffer.getInt(30).toLong() and 0xffffffffL
            val encodedGeneration = buffer.getInt(34).toLong() and 0xffffffffL
            val generation = if (encodedGeneration == 0xffffffffL) Long.MAX_VALUE else encodedGeneration
            val entry = IndexEntry(fullKey.copyOfRange(0, 9), archiveId, offset, size)
            return ExistingIndexRecord(fullKey.hex(), entry, bytes[at + 1].toInt() and 0xff, generation, bytes[at + 2].toInt() != 0)
        }

        override fun close() = channel.close()

        companion object {
            private fun capacityForEstimate(estimate: Int): Int {
                val bounded = estimate.coerceIn(0, MAX_TOTAL_INDEX_ENTRIES)
                var capacity = INCREMENTAL_TABLE_INITIAL_CAPACITY
                while (bounded.toLong() * 10L > capacity.toLong() * 6L && capacity < INCREMENTAL_TABLE_MAX_CAPACITY) {
                    capacity *= 2
                }
                return capacity
            }

            private fun createTableFile(file: File, capacity: Int): FileChannel {
                val channel = FileChannel.open(
                    file.toPath(),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                )
                try {
                    val length = capacity.toLong() * INCREMENTAL_TABLE_SLOT_SIZE
                    if (length > 0) {
                        channel.position(length - 1)
                        writeSequential(channel, ByteBuffer.wrap(byteArrayOf(0)))
                    }
                    return channel
                } catch (t: Throwable) {
                    channel.close()
                    file.delete()
                    throw t
                }
            }
        }
    }

    private data class CandidateHint(val archiveId: Int, val archiveOffset: Long, val size: Long)

    /** Disk-backed 9-byte-prefix lookup with bounded per-prefix location chains. */
    private class CandidateHintStore private constructor(
        private val prefixTable: DiskIndexTable,
        private val hintsChannel: FileChannel,
        private val cancelled: () -> Boolean,
    ) : AutoCloseable {
        private var totalHints = 0
        val hintCount: Long get() = totalHints.toLong()

        fun add(keyPrefix: ByteArray, archiveId: Int, archiveOffset: Long, size: Long) {
            if (keyPrefix.size != 9 || keyPrefix.all { it.toInt() == 0 }) {
                throw IllegalStateException("CASC candidate hint has a reserved or invalid 9-byte EKey prefix")
            }
            if (archiveId !in 0..1023 || archiveOffset !in 0 until ARCHIVE_LIMIT || size !in 0..0xffffffffL) {
                // Keep the structural hint as untrusted data only when its packed
                // location is representable; impossible bitfields fail closed.
                throw IllegalStateException("CASC candidate hint location is outside V7 bounds")
            }
            val pseudoKey = prefixKey(keyPrefix)
            val prior = prefixTable.find(pseudoKey)
            var head = prior?.entry?.archiveOffset ?: 0L
            val chainCount = prior?.entry?.size?.toInt() ?: 0
            var pointer = head
            repeat(chainCount) {
                val node = readNode(pointer)
                if (node.archiveId == archiveId && node.archiveOffset == archiveOffset && node.size == size) return
                pointer = node.nextPointer
            }
            if (pointer != 0L) throw IllegalStateException("CASC candidate hint chain is corrupt")
            if (chainCount >= MAX_CANDIDATE_HINTS_PER_PREFIX) {
                throw IllegalArgumentException("CASC candidate hint chain exceeds $MAX_CANDIDATE_HINTS_PER_PREFIX locations for one prefix")
            }
            if (totalHints >= MAX_CANDIDATE_HINTS) {
                throw IllegalArgumentException("CASC candidate hints exceed the bounded $MAX_CANDIDATE_HINTS-record limit")
            }

            val recordOffset = hintsChannel.size()
            val node = ByteBuffer.allocate(CANDIDATE_HINT_RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(head)
                .putInt(archiveId)
                .putInt(0)
                .putLong(archiveOffset)
                .putLong(size)
                .flip() as ByteBuffer
            writeFullyAt(hintsChannel, node, recordOffset)
            head = recordOffset + 1L
            val candidateIndexRecord = ExistingIndexRecord(
                pseudoKey.hex(),
                IndexEntry(keyPrefix.copyOf(), 0, head, (chainCount + 1).toLong()),
                0,
                0,
            )
            val inserted = prefixTable.put(candidateIndexRecord)
            if ((prior == null) != inserted) throw IllegalStateException("CASC candidate prefix table changed unexpectedly")
            totalHints++
        }

        fun forEachCandidate(keyPrefix: ByteArray, action: (CandidateHint) -> Boolean) {
            val pseudoKey = prefixKey(keyPrefix)
            val record = prefixTable.find(pseudoKey) ?: return
            val count = record.entry.size.toInt()
            var pointer = record.entry.archiveOffset
            repeat(count) {
                checkCancelled(cancelled)
                val node = readNode(pointer)
                if (!action(CandidateHint(node.archiveId, node.archiveOffset, node.size))) return
                pointer = node.nextPointer
            }
            if (pointer != 0L) throw IllegalStateException("CASC candidate hint chain is corrupt")
        }

        private fun readNode(pointer: Long): CandidateNode {
            if (pointer <= 0L) throw IllegalStateException("CASC candidate hint chain has an invalid pointer")
            val offset = pointer - 1L
            if (offset % CANDIDATE_HINT_RECORD_SIZE != 0L || offset > hintsChannel.size() - CANDIDATE_HINT_RECORD_SIZE) {
                throw IllegalStateException("CASC candidate hint chain points outside its owned spool")
            }
            val bytes = ByteBuffer.allocate(CANDIDATE_HINT_RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            readFullyAt(hintsChannel, bytes, offset)
            bytes.flip()
            val nextPointer = bytes.long
            val archiveId = bytes.int
            bytes.int // reserved
            val archiveOffset = bytes.long
            val size = bytes.long
            return CandidateNode(nextPointer, archiveId, archiveOffset, size)
        }

        private data class CandidateNode(
            val nextPointer: Long,
            val archiveId: Int,
            val archiveOffset: Long,
            val size: Long,
        )

        override fun close() {
            try {
                hintsChannel.close()
            } finally {
                prefixTable.close()
            }
        }

        companion object {
            fun create(tablePath: File, hintsPath: File, estimatedPrefixes: Int, cancelled: () -> Boolean): CandidateHintStore {
                val hints = FileChannel.open(
                    hintsPath.toPath(),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                )
                var table: DiskIndexTable? = null
                try {
                    table = DiskIndexTable(tablePath, estimatedPrefixes, cancelled)
                    return CandidateHintStore(table, hints, cancelled)
                } catch (t: Throwable) {
                    runCatching { table?.close() }
                    runCatching { hints.close() }
                    Files.deleteIfExists(hintsPath.toPath())
                    throw t
                }
            }

            private fun prefixKey(prefix: ByteArray): ByteArray {
                require(prefix.size == 9)
                return ByteArray(16).also { prefix.copyInto(it) }
            }
        }
    }

    private fun hashPrefix(fullKey: ByteArray): Long {
        var hash = -3750763034362895579L
        for (index in 0 until 9) {
            hash = (hash xor (fullKey[index].toLong() and 0xffL)) * 1099511628211L
        }
        hash = hash xor (hash ushr 33)
        hash *= -49064778989728563L
        hash = hash xor (hash ushr 33)
        return hash
    }

    private fun writeIncrementalIndices(
        table: DiskIndexTable,
        outputDataFiles: File,
        scratch: File,
        generations: LongArray,
        bucketCounts: IntArray,
        cancelled: () -> Boolean,
    ) {
        val batches = Array(16) { ArrayList<IndexEntry>(INDEX_SORT_BATCH_RECORDS) }
        val runs = Array(16) { ArrayList<File>() }
        var runNumber = 0
        fun flush(bucket: Int) {
            val entries = batches[bucket]
            if (entries.isEmpty()) return
            entries.sortWith { left, right -> compareBytes(left.key, right.key) }
            val runFile = File(scratch, "bucket-%02x-run-%06d.bin".format(bucket, runNumber++))
            FileChannel.open(
                runFile.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val writer = IndexByteWriter(channel)
                val encoded = ByteArray(18)
                entries.forEach { entry ->
                    checkCancelled(cancelled)
                    encodeIndexEntry(entry, encoded)
                    writer.write(encoded)
                }
                writer.flush()
            }
            runs[bucket] += runFile
            entries.clear()
        }

        table.forEachRecord { record ->
            checkCancelled(cancelled)
            val bucket = computeBucket(record.entry.key, if (record.flags == 1) 1 else 0)
            val batch = batches[bucket]
            batch += record.entry
            if (batch.size >= INDEX_SORT_BATCH_RECORDS) flush(bucket)
        }
        for (bucket in 0..15) flush(bucket)

        for (bucket in 0..15) {
            checkCancelled(cancelled)
            var currentRuns = runs[bucket]
            var pass = 0
            while (currentRuns.size > INDEX_MERGE_FAN_IN) {
                val nextRuns = ArrayList<File>((currentRuns.size + INDEX_MERGE_FAN_IN - 1) / INDEX_MERGE_FAN_IN)
                currentRuns.chunked(INDEX_MERGE_FAN_IN).forEach { group ->
                    checkCancelled(cancelled)
                    val merged = File(scratch, "bucket-%02x-merge-%02d-%06d.bin".format(bucket, pass, runNumber++))
                    mergeIndexRuns(group, merged, cancelled) { entry, output ->
                        encodeIndexEntry(entry, output)
                        output
                    }
                    group.forEach { if (!it.delete()) throw IllegalStateException("cannot remove consumed CASC index sort run") }
                    nextRuns += merged
                }
                currentRuns = nextRuns
                pass++
            }
            val generation = generations[bucket] + 1
            if (generation > 0xffffffffL) throw IllegalStateException("CASC V7 index generation capacity is exhausted")
            writeIncrementalIndex(
                File(outputDataFiles, "%02x%08x.idx".format(bucket, generation)),
                bucket,
                bucketCounts[bucket],
                currentRuns,
                cancelled,
            )
            currentRuns.forEach { if (!it.delete()) throw IllegalStateException("cannot remove final CASC index sort run") }
        }
    }

    private fun mergeIndexRuns(
        runs: List<File>,
        outputFile: File,
        cancelled: () -> Boolean,
        encode: (IndexEntry, ByteArray) -> ByteArray,
    ) {
        FileChannel.open(
            outputFile.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS,
        ).use { output ->
            val writer = IndexByteWriter(output)
            forEachMergedIndexEntry(runs, cancelled) { entry ->
                val bytes = encode(entry, ByteArray(18))
                writer.write(bytes)
            }
            writer.flush()
        }
    }

    private fun forEachMergedIndexEntry(runs: List<File>, cancelled: () -> Boolean, action: (IndexEntry) -> Unit) {
        data class RunHead(val reader: IndexRunReader, val entry: IndexEntry)
        val queue = java.util.PriorityQueue<RunHead> { left, right -> compareBytes(left.entry.key, right.entry.key) }
        val readers = ArrayList<IndexRunReader>(runs.size)
        try {
            runs.forEach { file ->
                val reader = IndexRunReader(file)
                readers += reader
                reader.next()?.let { queue += RunHead(reader, it) }
            }
            while (queue.isNotEmpty()) {
                checkCancelled(cancelled)
                val next = queue.remove()
                action(next.entry)
                next.reader.next()?.let { queue += RunHead(next.reader, it) }
            }
        } finally {
            readers.forEach { it.close() }
        }
    }

    private class IndexRunReader(file: File) : AutoCloseable {
        private val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        private val fileSize = channel.size()
        private val recordCount = fileSize / 18L
        private val bytes = ByteBuffer.allocate(64 * 1024 + 18)
        private var readPosition = 0L
        private var nextRecord = 0L

        init {
            if (fileSize % 18L != 0L) throw IllegalStateException("CASC external-sort run is truncated")
            bytes.limit(0)
        }

        fun next(): IndexEntry? {
            if (nextRecord >= recordCount) return null
            while (bytes.remaining() < 18 && readPosition < fileSize) {
                bytes.compact()
                val count = channel.read(bytes, readPosition)
                if (count < 0) throw IllegalStateException("CASC external-sort run is truncated")
                if (count == 0) {
                    bytes.flip()
                    Thread.yield()
                    continue
                }
                readPosition += count
                bytes.flip()
            }
            if (bytes.remaining() < 18) throw IllegalStateException("CASC external-sort run is truncated")
            val entryBytes = ByteArray(18)
            bytes.get(entryBytes)
            nextRecord++
            val key = entryBytes.copyOfRange(0, 9)
            val locationWord = u32be(entryBytes, 10).toLong() and 0xffffffffL
            val archiveId = ((entryBytes[9].toInt() and 0xff) shl 2) or ((locationWord ushr 30).toInt() and 3)
            val offset = locationWord and 0x3fffffffL
            val size = u32(entryBytes, 14).toLong() and 0xffffffffL
            return IndexEntry(key, archiveId, offset, size)
        }

        override fun close() = channel.close()
    }

    private fun writeIncrementalIndex(
        file: File,
        bucket: Int,
        expectedCount: Int,
        runs: List<File>,
        cancelled: () -> Boolean,
    ) {
        requireIndexCapacity(bucket, expectedCount)
        val blockSize = expectedCount.toLong() * 18L
        if (blockSize > 0xffffffffL) throw IllegalArgumentException("CASC V7 sorted section exceeds encoded length capacity")
        val indexLength = indexFileLength(expectedCount.toLong())
        if (file.exists() || Files.isSymbolicLink(file.toPath())) throw IllegalStateException("incremental CASC index output already exists")
        FileChannel.open(
            file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val header = ByteArray(0x28)
            putU32(header, 0, 16)
            putU16(header, 8, 7)
            header[10] = bucket.toByte()
            header[12] = 4
            header[13] = 5
            header[14] = 9
            header[15] = 30
            putU64(header, 16, 256L shl 30)
            putU32(header, 4, hashlittle(header.copyOfRange(8, 24), 0))
            putU32(header, 0x20, blockSize)
            val writer = IndexByteWriter(channel)
            writer.write(header)

            var pc = 0
            var pb = 0
            var actualCount = 0
            var previous: ByteArray? = null
            val encoded = ByteArray(18)
            forEachMergedIndexEntry(runs, cancelled) { entry ->
                if (entry.key.all { it.toInt() == 0 }) {
                    throw IllegalStateException("CASC V7 output contains a reserved all-zero 9-byte EKey prefix")
                }
                if (previous != null && compareBytes(previous!!, entry.key) >= 0) {
                    throw IllegalStateException("CASC V7 external-sort output is not strictly sorted")
                }
                previous = entry.key
                encodeIndexEntry(entry, encoded)
                writer.write(encoded)
                val hashes = hashlittle2(encoded, pc, pb)
                pc = hashes.first
                pb = hashes.second
                actualCount++
            }
            if (actualCount != expectedCount) throw IllegalStateException("CASC V7 output record count changed during index generation")
            val padding = indexLength - 0x28L - blockSize
            if (padding > 0) {
                val zeros = ByteArray(BUFFER_SIZE)
                var remaining = padding
                while (remaining > 0) {
                    checkCancelled(cancelled)
                    val count = min(zeros.size.toLong(), remaining).toInt()
                    writer.write(zeros, 0, count)
                    remaining -= count
                }
            }
            writer.flush()
            val checksum = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(pc).flip() as ByteBuffer
            writeFullyAt(channel, checksum, 0x24)
            if (channel.size() != indexLength) throw IllegalStateException("CASC V7 output index length mismatch")
        }
    }

    private fun encodeIndexEntry(entry: IndexEntry, output: ByteArray) {
        entry.key.copyInto(output, 0)
        output[9] = (entry.archiveId ushr 2).toByte()
        putU32BE(output, 10, ((entry.archiveId and 3) shl 30) or (entry.archiveOffset.toInt() and 0x3fffffff))
        putU32(output, 14, entry.size)
    }

    private fun writeSequential(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            val written = channel.write(buffer)
            if (written < 0) throw IllegalStateException("CASC file write failed")
            if (written == 0) Thread.yield()
        }
    }

    private class IndexByteWriter(private val channel: FileChannel) {
        private val buffer = ByteBuffer.allocate(BUFFER_SIZE)

        fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
            var at = offset
            var remaining = length
            while (remaining > 0) {
                if (!buffer.hasRemaining()) flush()
                val count = minOf(buffer.remaining(), remaining)
                buffer.put(bytes, at, count)
                at += count
                remaining -= count
            }
        }

        fun flush() {
            buffer.flip()
            while (buffer.hasRemaining()) {
                val written = channel.write(buffer)
                if (written < 0) throw IllegalStateException("CASC file write failed")
                if (written == 0) Thread.yield()
            }
            buffer.clear()
        }
    }

    private fun writeFullyAt(channel: FileChannel, buffer: ByteBuffer, start: Long) {
        var position = start
        while (buffer.hasRemaining()) {
            val written = channel.write(buffer, position)
            if (written < 0) throw IllegalStateException("CASC file write failed")
            if (written == 0) Thread.yield() else position += written
        }
    }

    private fun roundUpIndexLength(size: Long): Long = (size + INDEX_ALIGNMENT - 1L) and -INDEX_ALIGNMENT

    /**
     * Total file length for a V7 index holding [entryCount] sorted records:
     * the 4KiB-aligned sorted end plus the empty update reserve, keeping
     * the legacy 0x30000 floor for compact indices. Overflow-checked with
     * the same addExact/multiplyExact guards as the planning path; the
     * entry capacity itself stays enforced by [requireIndexCapacity] at
     * every caller, so this helper never widens admission.
     */
    private fun indexFileLength(entryCount: Long): Long {
        val sortedEnd = Math.addExact(0x28L, Math.multiplyExact(entryCount, 18L))
        val withReserve = Math.addExact(roundUpIndexLength(sortedEnd), INDEX_UPDATE_RESERVE_BYTES)
        return maxOf(INDEX_SIZE.toLong(), withReserve)
    }

    private fun deleteOwnedScratch(directory: File) {
        if (Files.isSymbolicLink(directory.toPath())) {
            Files.deleteIfExists(directory.toPath())
            return
        }
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(directory.toPath())
            return
        }
        Files.newDirectoryStream(directory.toPath()).use { children ->
            children.forEach { child ->
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(child)) {
                    deleteOwnedScratch(child.toFile())
                } else {
                    Files.deleteIfExists(child)
                }
            }
        }
        Files.deleteIfExists(directory.toPath())
    }

    private class ReadOnlyArchiveCache(private val dataFiles: File) : AutoCloseable {
        private val channels = LinkedHashMap<Int, FileChannel>(8, 0.75f, true)

        fun readLocalHeader(entry: IndexEntry): ByteArray {
            val channel = channel(entry.archiveId)
            if (entry.size > channel.size() || entry.archiveOffset > channel.size() - entry.size ||
                entry.archiveOffset > ARCHIVE_LIMIT - entry.size
            ) throw IllegalStateException("existing CASC index entry is outside its archive")
            val header = ByteArray(LOCAL_HEADER_SIZE)
            readFullyAt(channel, ByteBuffer.wrap(header), entry.archiveOffset)
            return header
        }

        private fun channel(archiveId: Int): FileChannel {
            channels[archiveId]?.let { return it }
            if (channels.size >= 8) {
                val iterator = channels.entries.iterator()
                val eldest = iterator.next()
                eldest.value.close()
                iterator.remove()
            }
            val file = File(dataFiles, "data.%03d".format(archiveId))
            if (Files.isSymbolicLink(file.toPath()) || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("existing CASC archive segment is missing or not a regular file")
            }
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            channels[archiveId] = channel
            return channel
        }

        override fun close() {
            channels.values.forEach { runCatching { it.close() } }
            channels.clear()
        }
    }

    private fun readFullyAt(channel: FileChannel, buffer: ByteBuffer, start: Long) {
        var position = start
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, position)
            if (count < 0) throw IllegalStateException("truncated CASC file")
            if (count == 0) continue
            position += count
        }
    }

    private fun generationOf(name: String): Long = name.substring(2, 10).toLong(16)

    private fun openArchive(dataFiles: File, archiveId: Int, entries: Array<MutableList<IndexEntry>>, cancelled: () -> Boolean): BufferedOutputStream {
        checkCancelled(cancelled)
        val file = File(dataFiles, "data.%03d".format(archiveId))
        val stream = BufferedOutputStream(openOwnedOutput(file, false), BUFFER_SIZE)
        val segment = segmentHeader(archiveId, MessageDigest.getInstance("MD5").digest(dataFiles.absolutePath.toByteArray(Charsets.UTF_8)))
        stream.write(segment)
        repeat(16) { slot ->
            val local = segment.copyOfRange(slot * LOCAL_HEADER_SIZE, (slot + 1) * LOCAL_HEADER_SIZE)
            val key = local.copyOfRange(0, 16).reversedArray()
            val bucket = computeBucket(key, 1)
            entries[bucket].add(IndexEntry(key.copyOfRange(0, 9), archiveId, (slot * LOCAL_HEADER_SIZE).toLong(), LOCAL_HEADER_SIZE.toLong()))
        }
        return stream
    }

    private fun segmentHeader(segmentIndex: Int, pathHash: ByteArray): ByteArray = ByteArray(SEGMENT_HEADER_SIZE).also { result ->
        repeat(16) { bucket ->
            val key = pathHash.copyOf()
            key[1] = (segmentIndex and 0xff).toByte()
            key[2] = ((segmentIndex ushr 8) and 0xff).toByte()
            var probe = 0
            while (probe < 256) {
                key[0] = probe.toByte()
                if (computeBucket(key, 1) == bucket) break
                probe++
            }
            val header = localHeader(key, LOCAL_HEADER_SIZE.toLong(), segmentIndex * ARCHIVE_LIMIT + bucket * LOCAL_HEADER_SIZE, 1)
            header.copyInto(result, bucket * LOCAL_HEADER_SIZE)
        }
    }

    private fun localHeader(encodingKey: ByteArray, size: Long, offset: Long, flags: Int): ByteArray {
        if (encodingKey.size != 16 || size !in 0..0xffffffffL) throw IllegalArgumentException("invalid CASC local header")
        val result = ByteArray(LOCAL_HEADER_SIZE)
        encodingKey.reversedArray().copyInto(result, 0)
        putU32(result, 0x10, size)
        putU16(result, 0x14, flags)
        putU32(result, 0x16, hashlittle(result.copyOfRange(0, 0x16), 0x3D6BE971))
        putU32(result, 0x1a, checksumB(result, offset))
        return result
    }

    private fun checksumB(header: ByteArray, offset: Long): Int {
        val accum = IntArray(4)
        for (i in 0 until 0x1a) {
            val slot = offset.toInt().plus(i) and 3
            accum[slot] = accum[slot] xor (header[i].toInt() and 0xff)
        }
        val plus = ((offset + LOCAL_HEADER_SIZE) and 0xffffffffL).toInt()
        val mask = CHECKSUM_B_LUT[plus and 0xf] xor plus
        val maskBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(mask).array()
        val result = ByteArray(4)
        for (i in 0 until 4) {
            val slot = offset.toInt().plus(0x1a + i) and 3
            result[i] = (accum[slot] xor (maskBytes[slot].toInt() and 0xff)).toByte()
        }
        return ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun writeIndex(file: File, bucket: Int, entries: List<IndexEntry>) {
        val sorted = entries.sortedWith { a, b -> compareBytes(a.key, b.key) }
        requireIndexCapacity(bucket, sorted.size)
        val entryBytes = ByteArray(sorted.size * 18)
        sorted.forEachIndexed { index, entry ->
            val at = index * 18
            entry.key.copyInto(entryBytes, at)
            entryBytes[at + 9] = (entry.archiveId ushr 2).toByte()
            putU32BE(entryBytes, at + 10, ((entry.archiveId and 3) shl 30) or (entry.archiveOffset.toInt() and 0x3fffffff))
            putU32(entryBytes, at + 14, entry.size)
        }
        val indexLength = indexFileLength(sorted.size.toLong())
        val result = ByteArray(indexLength.toInt())
            putU32(result, 0, 16)
            putU16(result, 8, 7)
        result[10] = bucket.toByte()
        result[12] = 4
        result[13] = 5
        result[14] = 9
        result[15] = 30
            putU64(result, 16, 256L shl 30)
            // The guarded header checksum covers the completed header fields;
            // computing it before bucket/format/segment fields are filled
            // produces an index that cascette correctly rejects.
            putU32(result, 4, hashlittle(result.copyOfRange(8, 24), 0))
        putU32(result, 0x20, entryBytes.size)
        var pc = 0
        var pb = 0
        sorted.forEach { entry ->
            val bytes = ByteArray(18)
            entry.key.copyInto(bytes, 0)
            bytes[9] = (entry.archiveId ushr 2).toByte()
            putU32BE(bytes, 10, ((entry.archiveId and 3) shl 30) or (entry.archiveOffset.toInt() and 0x3fffffff))
            putU32(bytes, 14, entry.size)
            val hashes = hashlittle2(bytes, pc, pb)
            pc = hashes.first
            pb = hashes.second
        }
        putU32(result, 0x24, pc)
        entryBytes.copyInto(result, 0x28)
        openOwnedOutput(file, false).use { it.write(result) }
    }

    private fun copyFile(source: File, target: File, cancelled: () -> Boolean) {
        target.parentFile?.mkdirs()
        openOwnedOutput(target, false).use { out -> copyFile(source, out, cancelled) }
    }

    private fun copyFile(source: File, out: OutputStream, cancelled: () -> Boolean) {
        if (!source.isFile || Files.isSymbolicLink(source.toPath())) throw IllegalStateException("CASC source is not a regular file")
        BufferedInputStream(source.inputStream(), BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                checkCancelled(cancelled)
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) out.write(buffer, 0, n)
            }
        }
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until min(a.size, b.size)) {
            val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }

    private fun computeBucket(key: ByteArray, seed: Int = 0): Int {
        var xor = 0
        repeat(min(9, key.size)) { xor = xor xor (key[it].toInt() and 0xff) }
        return (((xor ushr 4) xor xor) + seed) and 0xf
    }

    private fun putU16(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = value.toByte()
        bytes[at + 1] = (value ushr 8).toByte()
    }

    private fun putU32(bytes: ByteArray, at: Int, value: Long) {
        repeat(4) { bytes[at + it] = (value ushr (8 * it)).toByte() }
    }

    private fun putU32(bytes: ByteArray, at: Int, value: Int) = putU32(bytes, at, value.toLong() and 0xffffffffL)

    private fun putU32BE(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value ushr 24).toByte()
        bytes[at + 1] = (value ushr 16).toByte()
        bytes[at + 2] = (value ushr 8).toByte()
        bytes[at + 3] = value.toByte()
    }

    private fun putU64(bytes: ByteArray, at: Int, value: Long) = putU32(bytes, at, value).also { putU32(bytes, at + 4, value ushr 32) }

    private fun u16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
    private fun u32(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8) or ((bytes[at + 2].toInt() and 0xff) shl 16) or (bytes[at + 3].toInt() shl 24)
    private fun u32be(bytes: ByteArray, at: Int): Int = ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)

    private fun Int.hex() = toUInt().toString(16).padStart(8, '0')
    private fun ByteArray.hex(): String {
        val digits = "0123456789abcdef"
        val output = CharArray(size * 2)
        for (index in indices) {
            val value = this[index].toInt() and 255
            output[index * 2] = digits[value ushr 4]
            output[index * 2 + 1] = digits[value and 15]
        }
        return String(output)
    }
    private fun String.hexBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun checkCancelled(cancelled: () -> Boolean) { if (cancelled()) throw java.util.concurrent.CancellationException("client provisioning cancelled") }

    private fun openOwnedOutput(file: File, append: Boolean): OutputStream {
        rejectSymlinkParents(file)
        file.parentFile?.mkdirs()
        val options = if (append) {
            arrayOf<OpenOption>(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)
        } else {
            arrayOf<OpenOption>(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)
        }
        return Files.newOutputStream(file.toPath(), *options)
    }

    private fun writeOwnedText(file: File, text: String) = openOwnedOutput(file, false).use { it.write(text.toByteArray(Charsets.UTF_8)) }

    private fun rejectSymlinkParents(file: File) {
        var parent = file.parentFile
        while (parent != null) {
            if (Files.isSymbolicLink(parent.toPath())) throw IllegalStateException("CASC output path contains a symlink")
            parent = parent.parentFile
        }
    }

    private fun rot(value: Int, shift: Int) = (value shl shift) or (value ushr (32 - shift))
    private fun mix(values: IntArray) {
        var a = values[0]; var b = values[1]; var c = values[2]
        a -= c; a = a xor rot(c, 4); c += b; b -= a; b = b xor rot(a, 6); a += c
        c -= b; c = c xor rot(b, 8); b += a; a -= c; a = a xor rot(c, 16); c += b
        b -= a; b = b xor rot(a, 19); a += c; c -= b; c = c xor rot(b, 4); b += a
        values[0] = a; values[1] = b; values[2] = c
    }
    private fun finish(values: IntArray) {
        var a = values[0]; var b = values[1]; var c = values[2]
        c = c xor b; c -= rot(b, 14); a = a xor c; a -= rot(c, 11); b = b xor a; b -= rot(a, 25)
        c = c xor b; c -= rot(b, 16); a = a xor c; a -= rot(c, 4); b = b xor a; b -= rot(a, 14)
        c = c xor b; c -= rot(b, 24); values[0] = a; values[1] = b; values[2] = c
    }
    private fun hashlittle(data: ByteArray, seed: Int): Int {
        var offset = 0; var length = data.size; val v = intArrayOf(-559038737 + length + seed, -559038737 + length + seed, -559038737 + length + seed)
        while (length > 12) { addWord(v, 0, data, offset); addWord(v, 1, data, offset + 4); addWord(v, 2, data, offset + 8); mix(v); offset += 12; length -= 12 }
        for (i in 0 until min(length, 4)) v[0] += (data[offset + i].toInt() and 0xff) shl (8 * i)
        for (i in 4 until min(length, 8)) v[1] += (data[offset + i].toInt() and 0xff) shl (8 * (i - 4))
        for (i in 8 until length) v[2] += (data[offset + i].toInt() and 0xff) shl (8 * (i - 8))
        finish(v); return v[2]
    }
    private fun hashlittle2(data: ByteArray, pc: Int, pb: Int): Pair<Int, Int> {
        var offset = 0; var length = data.size; val initial = -559038737 + length + pc; val v = intArrayOf(initial, initial, initial + pb)
        while (length > 12) { addWord(v, 0, data, offset); addWord(v, 1, data, offset + 4); addWord(v, 2, data, offset + 8); mix(v); offset += 12; length -= 12 }
        for (i in 0 until min(length, 4)) v[0] += (data[offset + i].toInt() and 0xff) shl (8 * i)
        for (i in 4 until min(length, 8)) v[1] += (data[offset + i].toInt() and 0xff) shl (8 * (i - 4))
        for (i in 8 until length) v[2] += (data[offset + i].toInt() and 0xff) shl (8 * (i - 8))
        finish(v); return v[2] to v[1]
    }
    private fun addWord(values: IntArray, index: Int, data: ByteArray, at: Int) { values[index] += (data[at].toInt() and 0xff) or ((data[at + 1].toInt() and 0xff) shl 8) or ((data[at + 2].toInt() and 0xff) shl 16) or (data[at + 3].toInt() shl 24) }
}
