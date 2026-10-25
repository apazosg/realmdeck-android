package app.gamenative

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

internal enum class MetadataTransportFaultPoint {
    PARTIAL_OWNER_UPDATE_PERSISTED,
    INDEX_PROOF_PERSISTED,
    INDEX_TARGET_PUBLISHED,
    INDEX_PART_OWNER_REMOVED,
    INDEX_RECEIPT_PUBLISHED,
}

/**
 * Bounded HTTPS transport and verified, app-private cache for NGDP metadata only.
 * It intentionally has no API that can address arbitrary URLs or game payload roles.
 */
class ForeverClientMetadataTransport internal constructor(
    cacheRoot: File,
    private val connectionFactory: (String) -> HttpURLConnection,
    private val faultInjector: (MetadataTransportFaultPoint) -> Unit,
) {
    constructor(cacheRoot: File) : this(cacheRoot, ::openOfficialConnection, {})
    internal constructor(cacheRoot: File, connectionFactory: (String) -> HttpURLConnection) :
        this(cacheRoot, connectionFactory, {})

    private val root: Path = cacheRoot.toPath().toAbsolutePath().normalize()
    private val ownerToken: String = initializeCache()

    /** Fetch and MD5-verify one official raw build/CDN config (maximum 4 MiB). */
    fun config(hash: String, cancelled: () -> Boolean, progress: (Long) -> Unit): File =
        withCacheLock {
            checkActive(cancelled, System.nanoTime())
            val key = normalizeHash(hash, "config hash")
            val target = cachePath("config", key, ".config")
            ensureParent(target)
            if (existsNoFollow(target)) {
                verifyMd5File(target, key, MAX_CONFIG_BYTES, "cached config", cancelled)
                return@withCacheLock target.toFile()
            }
            val part = target.resolveSibling("${target.fileName}.part")
            val partOwner = "config|$key|*"
            val downloaded = download(
                url = configUrl(key),
                part = part,
                ownerDescription = partOwner,
                maxBytes = MAX_CONFIG_BYTES,
                expectedTotal = null,
                cancelled = cancelled,
                progress = progress,
            )
            try {
                verifyMd5File(part, key, MAX_CONFIG_BYTES, "downloaded config", cancelled)
            } catch (failure: Exception) {
                deleteOwnedPartial(part, partOwner)
                throw failure
            }
            publishVerified(part, target)
            removePartOwner(part, partOwner)
            target.toFile()
        }

    /** Fetch one of the four config-authenticated metadata BLTE objects, never a payload. */
    internal fun objectRaw(reference: ForeverTactMetadata.ObjectReference, cancelled: () -> Boolean, progress: (Long) -> Unit): File =
        withCacheLock {
            checkActive(cancelled, System.nanoTime())
            validateMetadataReference(reference)
            objectRawLocked(reference, cancelled, progress)
        }

    /** Decode a metadata BLTE object to a verified CKey-named immutable cache file. */
    internal fun decoded(reference: ForeverTactMetadata.ObjectReference, cancelled: () -> Boolean, progress: (Long) -> Unit): File =
        withCacheLock {
            checkActive(cancelled, System.nanoTime())
            validateMetadataReference(reference)
            val decodedSize = reference.decodedSize ?: throw UnsupportedOperationException(
                "cannot decode ${reference.role}: config has no decoded-size value; resolve the role through its encoding metadata first",
            )
            require(decodedSize in 1..MAX_DECODED_OBJECT_BYTES) {
                "${reference.role} decoded metadata size exceeds the supported BLTE bound"
            }
            val target = cachePath("decoded", reference.contentKey, ".decoded")
            ensureParent(target)
            if (existsNoFollow(target)) {
                verifyDecodedFile(target, reference.contentKey, decodedSize, "cached decoded metadata", cancelled)
                return@withCacheLock target.toFile()
            }

            val encoded = objectRawLocked(reference, cancelled, progress)
            val part = target.resolveSibling("${target.fileName}.part")
            val partOwner = "decoded|${reference.contentKey}|$decodedSize|${reference.encodingKey}|${reference.encodedSize}"
            prepareOwnedPartial(part, partOwner)
            try {
                checkActive(cancelled, System.nanoTime())
                ForeverBlte.decode(encoded, part.toFile(), decodedSize, reference.contentKey, cancelled)
                verifyDecodedFile(part, reference.contentKey, decodedSize, "decoded metadata", cancelled)
            } catch (failure: Throwable) {
                deleteOwnedPartial(part, partOwner)
                throw failure
            }
            publishVerified(part, target)
            removePartOwner(part, partOwner)
            target.toFile()
        }

    /**
     * Fetch one raw CDN `.index` object. Its hash is an index name, not a raw MD5;
     * the returned file remains structurally untrusted until the caller's parser validates it.
     */
    fun index(hash: String, cancelled: () -> Boolean, progress: (Long) -> Unit): File =
        withCacheLock {
            checkActive(cancelled, System.nanoTime())
            val key = normalizeHash(hash, "CDN index hash")
            val target = cachePath("index", key, ".index")
            val receipt = target.resolveSibling("${target.fileName}.receipt")
            val proof = target.resolveSibling("${target.fileName}.publish")
            ensureParent(target)
            recoverIndexPublication(target, receipt, proof, key, cancelled)?.let { return@withCacheLock it }
            require(indexCacheCount() < MAX_INDEX_COUNT) { "CDN metadata index cache exceeds the $MAX_INDEX_COUNT entry bound" }
            val part = target.resolveSibling("${target.fileName}.part")
            val partOwner = "index|$key|*"
            val size = download(
                url = indexUrl(key),
                part = part,
                ownerDescription = partOwner,
                maxBytes = MAX_INDEX_BYTES,
                expectedTotal = null,
                cancelled = cancelled,
                progress = progress,
            )
            require(size in 1..MAX_INDEX_BYTES) { "official CDN index body is empty or exceeds its bound" }
            val rawSha256 = sha256File(part, cancelled)
            writeIndexReceipt(proof, key, size, rawSha256)
            faultInjector(MetadataTransportFaultPoint.INDEX_PROOF_PERSISTED)
            publishVerified(part, target)
            faultInjector(MetadataTransportFaultPoint.INDEX_TARGET_PUBLISHED)
            verifyIndexBytes(target, IndexProof(size, rawSha256), cancelled)
            removePartOwner(part, partOwner)
            faultInjector(MetadataTransportFaultPoint.INDEX_PART_OWNER_REMOVED)
            publishIndexReceipt(proof, receipt)
            faultInjector(MetadataTransportFaultPoint.INDEX_RECEIPT_PUBLISHED)
            target.toFile()
        }

    private fun objectRawLocked(
        reference: ForeverTactMetadata.ObjectReference,
        cancelled: () -> Boolean,
        progress: (Long) -> Unit,
    ): File {
        val target = cachePath("raw", reference.encodingKey, ".blte")
        ensureParent(target)
        if (existsNoFollow(target)) {
            verifyRawObject(target, reference, cancelled)
            return target.toFile()
        }
        val part = target.resolveSibling("${target.fileName}.part")
        val partOwner = "raw|${reference.encodingKey}|${reference.encodedSize}|${reference.contentKey}|${reference.decodedSize}"
        val size = download(
            url = dataUrl(reference.encodingKey),
            part = part,
            ownerDescription = partOwner,
            maxBytes = MAX_RAW_OBJECT_BYTES,
            expectedTotal = reference.encodedSize,
            cancelled = cancelled,
            progress = progress,
        )
        try {
            require(size == reference.encodedSize) { "downloaded ${reference.role} encoded size does not match its config reference" }
            verifyRawObject(part, reference, cancelled)
        } catch (failure: Exception) {
            deleteOwnedPartial(part, partOwner)
            throw failure
        }
        publishVerified(part, target)
        removePartOwner(part, partOwner)
        return target.toFile()
    }

    /** Transfer a fresh or resumable sibling part and return its exact total size. */
    private fun download(
        url: String,
        part: Path,
        ownerDescription: String,
        maxBytes: Long,
        expectedTotal: Long?,
        cancelled: () -> Boolean,
        progress: (Long) -> Unit,
    ): Long {
        require(maxBytes > 0 && (expectedTotal == null || expectedTotal in 1..maxBytes)) {
            "metadata transfer size is outside its role bound"
        }
        prepareOwnedPartial(part, ownerDescription)
        var existing = Files.size(part)
        var resumeTotal = partialTotal(part, ownerDescription) ?: expectedTotal
        if (expectedTotal != null && resumeTotal != null) {
            require(expectedTotal == resumeTotal) { "metadata partial range checkpoint differs from its declared object size" }
        }
        if (resumeTotal != null && resumeTotal > maxBytes) {
            deleteOwnedPartial(part, ownerDescription)
            prepareOwnedPartial(part, ownerDescription)
            existing = 0L
            resumeTotal = expectedTotal
        }
        if (existing > maxBytes || (resumeTotal != null && existing > resumeTotal)) {
            deleteOwnedPartial(part, ownerDescription)
            prepareOwnedPartial(part, ownerDescription)
            existing = 0L
            resumeTotal = expectedTotal
        }
        if (existing > 0 && resumeTotal != null && existing == resumeTotal) return existing
        val started = System.nanoTime()
        checkActive(cancelled, started)
        val ranged = existing > 0L
        val connection = connectionFactory(url)
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (ranged) {
                val end = resumeTotal?.minus(1L)
                connection.setRequestProperty("Range", if (end == null) "bytes=$existing-" else "bytes=$existing-$end")
            }
            checkActive(cancelled, started)
            val status = connection.responseCode
            require(status == if (ranged) HttpURLConnection.HTTP_PARTIAL else HttpURLConnection.HTTP_OK) {
                "official CDN returned HTTP $status for a bounded metadata transfer; redirects are not followed"
            }
            val contentEncoding = connection.contentEncoding
            require(contentEncoding == null || contentEncoding.equals("identity", ignoreCase = true)) {
                "official CDN returned a transformed metadata body"
            }
            val contentLength = connection.contentLengthLong
            require(contentLength > 0 && contentLength <= maxBytes) {
                "official CDN metadata Content-Length is absent, empty, or exceeds the role bound"
            }
            val totalSize = if (ranged) {
                validateContentRange(connection, existing, resumeTotal, contentLength, maxBytes)
            } else {
                require(expectedTotal == null || contentLength == expectedTotal) {
                    "official CDN metadata Content-Length does not match its config reference"
                }
                contentLength
            }
            if (expectedTotal != null) require(totalSize == expectedTotal) {
                "official CDN metadata total differs from its config reference"
            }
            require(totalSize in 1..maxBytes && totalSize > existing) {
                "official CDN metadata range total is outside the role bound"
            }
            recordPartialTotal(part, ownerDescription, totalSize)
            val reservedBytes = Math.addExact(contentLength, 1L)
            reserveNetworkBudget(reservedBytes)
            var networkBytes = 0L
            try {
                val channel = FileChannel.open(
                    part,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                    LinkOption.NOFOLLOW_LINKS,
                )
                try {
                    if (channel.size() != existing) throw IllegalStateException("metadata partial changed before resume")
                    BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var written = 0L
                        while (written < contentLength) {
                            checkActive(cancelled, started)
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), contentLength - written).toInt())
                            if (count < 0) throw IOException("official CDN metadata range was truncated")
                            if (count == 0) continue
                            networkBytes = Math.addExact(networkBytes, count.toLong())
                            val bytes = ByteBuffer.wrap(buffer, 0, count)
                            while (bytes.hasRemaining()) {
                                checkActive(cancelled, started)
                                if (channel.write(bytes) <= 0) throw IOException("could not persist metadata partial")
                            }
                            written = Math.addExact(written, count.toLong())
                            progress(Math.addExact(existing, written))
                        }
                        checkActive(cancelled, started)
                        if (input.read() >= 0) {
                            networkBytes = Math.addExact(networkBytes, 1L)
                            throw IllegalArgumentException("official CDN returned bytes beyond the declared metadata length")
                        }
                    }
                    if (channel.size() != totalSize) throw IOException("metadata partial size differs from the CDN range total")
                    channel.force(true)
                } finally {
                    try {
                        channel.force(true)
                    } finally {
                        channel.close()
                    }
                }
            } finally {
                finishNetworkBudget(networkBytes)
            }
            forceDirectory(part.parent)
            totalSize
        } finally {
            connection.disconnect()
        }
    }

    private fun validateContentRange(
        connection: HttpURLConnection,
        start: Long,
        expectedTotal: Long?,
        contentLength: Long,
        maxBytes: Long,
    ): Long {
        val header = connection.getHeaderField("Content-Range")
            ?: throw IllegalArgumentException("official CDN range response omitted Content-Range")
        val match = CONTENT_RANGE.matchEntire(header)
            ?: throw IllegalArgumentException("official CDN Content-Range is malformed")
        val actualStart = match.groupValues[1].toLongOrNull()
        val actualEnd = match.groupValues[2].toLongOrNull()
        val total = match.groupValues[3].toLongOrNull()
        require(actualStart == start && total != null && total > start && total <= maxBytes && actualEnd == total - 1L) {
            "official CDN returned a different or out-of-bounds metadata byte range"
        }
        require(expectedTotal == null || total == expectedTotal) { "official CDN range total differs from the declared metadata size" }
        require(contentLength == total - start) { "official CDN range Content-Length does not match its exact byte interval" }
        return total
    }

    private fun verifyRawObject(file: Path, reference: ForeverTactMetadata.ObjectReference, cancelled: () -> Boolean) {
        verifyEncodedEKey(file, reference.encodingKey, reference.encodedSize, cancelled)
        val decodedSize = reference.decodedSize ?: throw UnsupportedOperationException(
            "cannot accept ${reference.role} raw metadata without a decoded-size binding",
        )
        ForeverBlte.verify(file.toFile(), decodedSize, reference.contentKey, cancelled)
        require(Files.size(file) == reference.encodedSize) { "metadata object changed during CKey verification" }
    }

    /** TACT EKey rule: MD5 of BLTE header+chunk table, otherwise the whole raw object. */
    private fun verifyEncodedEKey(file: Path, expectedEKey: String, expectedSize: Long, cancelled: () -> Boolean) {
        checkRegularFile(file, "raw metadata object")
        require(Files.size(file) == expectedSize && expectedSize in 1..MAX_RAW_OBJECT_BYTES) {
            "raw metadata object encoded size mismatch or exceeds the 256 MiB bound"
        }
        val before = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val digest = MessageDigest.getInstance("MD5")
        Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val prefix = ByteArray(8)
            var prefixRead = 0
            while (prefixRead < prefix.size) {
                checkCancelled(cancelled)
                val count = input.read(prefix, prefixRead, prefix.size - prefixRead)
                if (count < 0) break
                if (count > 0) prefixRead += count
            }
            val isBlte = prefixRead >= 4 && prefix.copyOfRange(0, 4).contentEquals(BLTE_MAGIC)
            if (isBlte && prefixRead < 8) throw IllegalArgumentException("truncated BLTE header before EKey verification")
            val headerSize = if (isBlte) u32be(prefix, 4) else 0L
            if (isBlte && headerSize > 0L) {
                require(headerSize in MIN_EKEY_HEADER_BYTES..MAX_EKEY_HEADER_BYTES && headerSize <= expectedSize) {
                    "BLTE EKey header/table exceeds the supported metadata bound"
                }
                digest.update(prefix)
                copyDigest(input, digest, headerSize - prefix.size, cancelled)
            } else {
                digest.update(prefix, 0, prefixRead)
                copyDigest(input, digest, expectedSize - prefixRead, cancelled)
                if (input.read() >= 0) throw IOException("raw metadata object changed while verifying its EKey")
            }
        }
        val after = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(before.fileKey() == after.fileKey() && before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime()) {
            "raw metadata object changed while verifying its EKey"
        }
        val actual = digest.digest().hex()
        require(actual.equals(expectedEKey, ignoreCase = true)) { "raw metadata object EKey mismatch" }
    }

    private fun copyDigest(input: java.io.InputStream, digest: MessageDigest, byteCount: Long, cancelled: () -> Boolean) {
        var remaining = byteCount
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            checkCancelled(cancelled)
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw IOException("raw metadata object was truncated while hashing")
            if (count == 0) continue
            digest.update(buffer, 0, count)
            remaining -= count
        }
    }

    private fun verifyMd5File(
        path: Path,
        expected: String,
        maxBytes: Long,
        description: String,
        cancelled: () -> Boolean,
    ) {
        checkRegularFile(path, description)
        val size = Files.size(path)
        require(size in 1..maxBytes) { "$description is empty or exceeds its byte bound" }
        val digest = digestFile(path, "MD5", cancelled)
        require(Files.size(path) == size && digest.equals(expected, ignoreCase = true)) {
            "$description MD5 or size does not match its hash-bound cache name"
        }
    }

    private fun verifyDecodedFile(
        path: Path,
        expectedCKey: String,
        expectedSize: Long,
        description: String,
        cancelled: () -> Boolean,
    ) {
        checkRegularFile(path, description)
        require(Files.size(path) == expectedSize && expectedSize in 1..MAX_DECODED_OBJECT_BYTES) {
            "$description decoded size mismatch or exceeds the 512 MiB bound"
        }
        require(digestFile(path, "MD5", cancelled).equals(expectedCKey, ignoreCase = true) && Files.size(path) == expectedSize) {
            "$description CKey or size does not match its config reference"
        }
    }

    private data class IndexProof(val size: Long, val sha256: String)

    /** Recover only publication states backed by the owner-bound raw SHA/size proof. */
    private fun recoverIndexPublication(
        target: Path,
        receipt: Path,
        proofFile: Path,
        key: String,
        cancelled: () -> Boolean,
    ): File? {
        val targetExists = existsNoFollow(target)
        val receiptExists = existsNoFollow(receipt)
        val proofExists = existsNoFollow(proofFile)
        if (!targetExists && !receiptExists && !proofExists) return null
        if (proofExists && receiptExists) {
            throw IllegalStateException("CDN index has both pending and final publication records; preserving all files")
        }
        if (!proofExists && !receiptExists) {
            throw IllegalStateException("CDN index bytes have no owner-bound receipt; refusing adoption or refetch over the existing path")
        }

        val recordPath = if (proofExists) proofFile else receipt
        val record = readIndexProof(recordPath, key)
        val part = target.resolveSibling("${target.fileName}.part")
        val partOwner = "index|$key|*"
        val owner = ownerPath(part)

        if (targetExists) {
            verifyIndexBytes(target, record, cancelled)
            if (existsNoFollow(part)) {
                throw IllegalStateException("CDN index target and owned partial coexist; preserving both pending review")
            }
            if (existsNoFollow(owner)) {
                verifyIndexPartialOwner(owner, partOwner, record.size)
                removePartOwner(part, partOwner)
                faultInjector(MetadataTransportFaultPoint.INDEX_PART_OWNER_REMOVED)
            }
            if (proofExists) {
                publishIndexReceipt(proofFile, receipt)
                faultInjector(MetadataTransportFaultPoint.INDEX_RECEIPT_PUBLISHED)
            }
            return target.toFile()
        }

        if (receiptExists) {
            throw IllegalStateException("CDN index receipt exists but its target bytes are missing; refusing to refetch or discard the proof")
        }
        verifyIndexPartialOwner(owner, partOwner, record.size)
        verifyIndexBytes(part, record, cancelled)
        publishVerified(part, target)
        faultInjector(MetadataTransportFaultPoint.INDEX_TARGET_PUBLISHED)
        removePartOwner(part, partOwner)
        faultInjector(MetadataTransportFaultPoint.INDEX_PART_OWNER_REMOVED)
        publishIndexReceipt(proofFile, receipt)
        faultInjector(MetadataTransportFaultPoint.INDEX_RECEIPT_PUBLISHED)
        return target.toFile()
    }

    private fun verifyIndexPartialOwner(owner: Path, description: String, expectedTotal: Long) {
        checkRegularFile(owner, "CDN index partial ownership marker")
        val record = readPartialOwner(owner)
        require(record.first == description && record.second == expectedTotal) {
            "CDN index publication proof does not match its owned partial checkpoint"
        }
    }

    private fun readIndexProof(path: Path, key: String): IndexProof {
        checkRegularFile(path, "CDN index publication proof")
        val lines = readSmallText(path, 256).split('\n')
        val size = lines.getOrNull(3)?.toLongOrNull()
        require(lines.size == 5 && lines[0] == "forever-tact-index-v1" && lines[1] == ownerToken &&
            lines[2] == key && size != null && size in 1..MAX_INDEX_BYTES && lines[4].matches(HEX_SHA256)
        ) { "CDN index publication proof is malformed or belongs to another cache/index" }
        return IndexProof(size, lines[4])
    }

    private fun verifyIndexBytes(index: Path, proof: IndexProof, cancelled: () -> Boolean) {
        checkRegularFile(index, "CDN index bytes")
        require(Files.size(index) == proof.size && digestFile(index, "SHA-256", cancelled).equals(proof.sha256, ignoreCase = true) &&
            Files.size(index) == proof.size
        ) { "CDN index bytes do not match their owner-bound raw SHA/size proof" }
    }

    private fun writeIndexReceipt(proof: Path, key: String, size: Long, sha256: String) {
        val contents = "forever-tact-index-v1\n$ownerToken\n$key\n$size\n$sha256"
        writeNewImmutable(proof, contents.toByteArray(Charsets.US_ASCII))
    }

    private fun publishIndexReceipt(proof: Path, receipt: Path) {
        checkRegularFile(proof, "durable CDN index publication proof")
        require(!existsNoFollow(receipt)) { "CDN index receipt destination already exists; refusing replacement" }
        try {
            Files.move(proof, receipt, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: FileAlreadyExistsException) {
            throw IllegalStateException("CDN index receipt appeared before atomic publication; preserving proof", failure)
        } catch (failure: AtomicMoveNotSupportedException) {
            throw IllegalStateException("filesystem cannot atomically publish the CDN index receipt", failure)
        }
        forceDirectory(receipt.parent)
    }

    private fun reserveNetworkBudget(reserved: Long) {
        require(reserved > 0 && reserved <= MAX_TOTAL_NETWORK_BYTES) { "metadata response exceeds the cumulative network budget" }
        val current = readBudgetState()
        val recovered = if (current.pending > 0L) Math.addExact(current.total, current.pending) else current.total
        require(recovered <= MAX_TOTAL_NETWORK_BYTES && reserved <= MAX_TOTAL_NETWORK_BYTES - recovered) {
            "metadata network response budget exceeds the $MAX_TOTAL_NETWORK_BYTES byte cache limit"
        }
        writeBudgetState(BudgetState(recovered, reserved))
    }

    private fun finishNetworkBudget(received: Long) {
        val state = readBudgetState()
        require(state.pending > 0 && received in 0..state.pending) { "metadata network budget checkpoint is invalid" }
        val total = Math.addExact(state.total, received)
        require(total <= MAX_TOTAL_NETWORK_BYTES) { "metadata network response budget exceeded its cache limit" }
        writeBudgetState(BudgetState(total, 0L))
    }

    private data class BudgetState(val total: Long, val pending: Long)

    private fun readBudgetState(): BudgetState {
        val path = root.resolve(BUDGET_FILE)
        if (!existsNoFollow(path)) return BudgetState(0L, 0L)
        checkRegularFile(path, "metadata transfer budget state")
        val lines = readSmallText(path, 128).split('\n')
        require(lines.size == 3 && lines[0] == "forever-tact-budget-v1") { "metadata transfer budget state is malformed" }
        val total = lines[1].toLongOrNull()
        val pending = lines[2].toLongOrNull()
        require(total != null && pending != null && total in 0..MAX_TOTAL_NETWORK_BYTES && pending in 0..MAX_TOTAL_NETWORK_BYTES) {
            "metadata transfer budget counters are invalid"
        }
        return BudgetState(total, pending)
    }

    private fun writeBudgetState(state: BudgetState) {
        val target = root.resolve(BUDGET_FILE)
        val part = root.resolve("$BUDGET_FILE.part")
        if (existsNoFollow(part)) throw IllegalStateException("stale metadata budget checkpoint is present; refusing to replace it")
        if (existsNoFollow(target)) checkRegularFile(target, "metadata transfer budget state")
        val contents = "forever-tact-budget-v1\n${state.total}\n${state.pending}".toByteArray(Charsets.US_ASCII)
        writeNew(part, contents)
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: AtomicMoveNotSupportedException) {
            throw IllegalStateException("filesystem cannot atomically persist the metadata network budget", failure)
        }
        forceDirectory(root)
    }

    private fun prepareOwnedPartial(part: Path, description: String) {
        ensureParent(part)
        val ownerPath = ownerPath(part)
        val updatePath = partialOwnerUpdatePath(part)
        val partExists = existsNoFollow(part)
        val ownerExists = existsNoFollow(ownerPath)
        val updateExists = existsNoFollow(updatePath)
        if (partExists) checkRegularFile(part, "metadata partial")
        if (ownerExists) {
            checkRegularFile(ownerPath, "metadata partial ownership marker")
            require(partialOwnerDescription(ownerPath) == description) {
                "metadata partial is not owned by this cache operation; refusing to adopt or delete it"
            }
            if (updateExists) recoverPartialOwnerUpdate(part, ownerPath, updatePath, description, partExists)
        } else {
            require(!updateExists) {
                "metadata partial update exists without its original owner marker; preserving it and refusing recovery"
            }
            require(!partExists) { "metadata partial has no ownership marker; refusing to adopt or delete it" }
            writeNew(ownerPath, "$ownerToken\n$description".toByteArray(Charsets.US_ASCII))
            forceDirectory(ownerPath.parent)
        }
        if (!partExists) {
            writeNew(part, ByteArray(0))
            forceDirectory(part.parent)
        }
    }

    private fun deleteOwnedPartial(part: Path, description: String) {
        val marker = ownerPath(part)
        if (!existsNoFollow(marker)) throw IllegalStateException("cannot remove metadata partial without its ownership marker")
        checkRegularFile(marker, "metadata partial ownership marker")
        require(partialOwnerDescription(marker) == description) {
            "refusing to remove a metadata partial owned by another operation"
        }
        if (existsNoFollow(part)) {
            checkRegularFile(part, "metadata partial")
            Files.delete(part)
        }
        Files.delete(marker)
        forceDirectory(part.parent)
    }

    private fun removePartOwner(part: Path, description: String) {
        val marker = ownerPath(part)
        if (existsNoFollow(marker)) {
            checkRegularFile(marker, "metadata partial ownership marker")
            require(partialOwnerDescription(marker) == description) { "metadata partial ownership marker changed before publication" }
            Files.delete(marker)
            forceDirectory(marker.parent)
        }
    }

    private fun ownerPath(part: Path): Path = part.resolveSibling("${part.fileName}.owner")
    private fun partialOwnerUpdatePath(part: Path): Path {
        val marker = ownerPath(part)
        return marker.resolveSibling("${marker.fileName}.update")
    }

    private fun partialOwnerDescription(path: Path): String {
        return readPartialOwner(path).first
    }

    private fun partialTotal(part: Path, description: String): Long? {
        val marker = ownerPath(part)
        if (!existsNoFollow(marker)) return null
        val owner = readPartialOwner(marker)
        require(owner.first == description) {
            "metadata partial ownership marker does not match this transfer"
        }
        return owner.second
    }

    private fun readPartialOwner(path: Path): Pair<String, Long?> {
        checkRegularFile(path, "metadata partial ownership marker")
        val lines = readSmallText(path, 512).split('\n')
        require(lines.size in 2..3 && lines[0] == ownerToken) { "metadata partial ownership marker is malformed" }
        val total = if (lines.size == 3) lines[2].toLongOrNull() else null
        require(lines.size == 2 || total != null && total in 1..MAX_RAW_OBJECT_BYTES) {
            "metadata partial total-size checkpoint is malformed"
        }
        return lines[1] to total
    }

    private fun recoverPartialOwnerUpdate(
        part: Path,
        marker: Path,
        update: Path,
        description: String,
        partExists: Boolean,
    ) {
        checkRegularFile(update, "metadata partial owner update")
        require(partExists) { "metadata partial owner update has no matching partial; preserving update and refusing recovery" }
        val current = readPartialOwner(marker)
        val pending = readPartialOwner(update)
        require(current.first == description && pending.first == description) {
            "metadata partial owner update identity does not match this transfer; preserving both markers"
        }
        val (maximum, expectedTotal) = transferBounds(description)
        val total = pending.second
            ?: throw IllegalStateException("metadata partial owner update has no total-size checkpoint; preserving it")
        val partSize = Files.size(part)
        require(total <= maximum && (expectedTotal == null || expectedTotal == total) && partSize <= total) {
            "metadata partial owner update conflicts with its role size or retained bytes; preserving all files"
        }
        require(current.second == null || current.second == total) {
            "metadata owner and update checkpoints disagree; preserving both for explicit recovery"
        }
        try {
            Files.move(update, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: AtomicMoveNotSupportedException) {
            throw IllegalStateException("filesystem cannot atomically recover the owned metadata range checkpoint", failure)
        }
        forceDirectory(marker.parent)
    }

    private fun recordPartialTotal(part: Path, description: String, total: Long) {
        val marker = ownerPath(part)
        checkRegularFile(marker, "metadata partial ownership marker")
        val (maximum, expectedTotal) = transferBounds(description)
        require(partialOwnerDescription(marker) == description && total in 1..maximum &&
            (expectedTotal == null || expectedTotal == total)
        ) {
            "cannot checkpoint metadata range total for an unowned partial"
        }
        val update = partialOwnerUpdatePath(part)
        if (existsNoFollow(update)) throw IllegalStateException("stale metadata ownership update is present; refusing to replace it")
        writeNew(update, "$ownerToken\n$description\n$total".toByteArray(Charsets.US_ASCII))
        forceDirectory(update.parent)
        faultInjector(MetadataTransportFaultPoint.PARTIAL_OWNER_UPDATE_PERSISTED)
        try {
            Files.move(update, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: AtomicMoveNotSupportedException) {
            throw IllegalStateException("filesystem cannot durably checkpoint a resumable metadata range", failure)
        }
        forceDirectory(marker.parent)
    }

    private fun transferBounds(description: String): Pair<Long, Long?> {
        val fields = description.split('|')
        return when (fields.firstOrNull()) {
            "config" -> {
                require(fields.size == 3 && HEX_MD5.matches(fields[1]) && fields[2] == "*") {
                    "metadata config partial owner identity is malformed"
                }
                MAX_CONFIG_BYTES to null
            }
            "index" -> {
                require(fields.size == 3 && HEX_MD5.matches(fields[1]) && fields[2] == "*") {
                    "metadata index partial owner identity is malformed"
                }
                MAX_INDEX_BYTES to null
            }
            "raw" -> {
                require(fields.size == 5 && HEX_MD5.matches(fields[1]) && HEX_MD5.matches(fields[3])) {
                    "raw metadata partial owner identity is malformed"
                }
                val encodedSize = fields[2].toLongOrNull()
                val decodedSize = fields[4].toLongOrNull()
                require(encodedSize != null && encodedSize in 1..MAX_RAW_OBJECT_BYTES &&
                    decodedSize != null && decodedSize in 1..MAX_DECODED_OBJECT_BYTES
                ) { "raw metadata partial owner sizes are outside their bounds" }
                MAX_RAW_OBJECT_BYTES to encodedSize
            }
            else -> throw IllegalStateException("unsupported metadata partial owner role; refusing checkpoint recovery")
        }
    }

    private fun publishVerified(part: Path, target: Path) {
        checkRegularFile(part, "verified metadata staging file")
        require(!existsNoFollow(target)) { "metadata cache destination already exists; refusing to overwrite it" }
        FileChannel.open(part, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        makeImmutable(part)
        forceDirectory(part.parent)
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: FileAlreadyExistsException) {
            throw IllegalStateException("metadata cache destination appeared before publication; refusing to overwrite it", failure)
        } catch (failure: AtomicMoveNotSupportedException) {
            throw IllegalStateException("filesystem cannot atomically publish verified metadata without replacement", failure)
        }
        forceDirectory(target.parent)
    }

    private fun writeNewImmutable(path: Path, contents: ByteArray) {
        writeNew(path, contents)
        makeImmutable(path)
        forceDirectory(path.parent)
    }

    private fun writeNew(path: Path, contents: ByteArray) {
        checkNoSymlinkPath(path)
        val channel = FileChannel.open(
            path,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        try {
            val buffer = ByteBuffer.wrap(contents)
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) <= 0) throw IOException("could not persist metadata cache ownership state")
            }
            channel.force(true)
        } finally {
            channel.close()
        }
    }

    private fun makeImmutable(path: Path) {
        try {
            if (System.getProperty("java.vm.name") == "Dalvik") {
                android.system.Os.chmod(path.toString(), 0x124) // 0444
            } else {
            Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ))
            }
        } catch (failure: UnsupportedOperationException) {
            throw IllegalStateException("metadata cache filesystem does not support immutable POSIX file permissions", failure)
        }
        checkRegularFile(path, "immutable metadata cache file")
    }

    private fun cachePath(category: String, hash: String, suffix: String): Path =
        root.resolve(category).resolve(hash.substring(0, 2)).resolve("$hash$suffix")

    private fun ensureParent(path: Path) {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("metadata cache root is not a no-follow regular directory")
        }
        val parent = path.parent ?: throw IllegalArgumentException("metadata cache path has no parent directory")
        require(parent.startsWith(root)) { "metadata cache path escaped its owned root" }
        var current = root
        for (part in root.relativize(parent)) {
            current = current.resolve(part)
            if (!existsNoFollow(current)) {
                try {
                    Files.createDirectory(current)
                } catch (failure: FileAlreadyExistsException) {
                    // Re-validate below; a concurrent symlink/different node is not accepted.
                }
                forceDirectory(current.parent)
            }
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("metadata cache directory is not a no-follow regular directory")
            }
        }
    }

    private fun initializeCache(): String = synchronized(JVM_CACHE_LOCK) {
        ensurePathDirectories(root)
        try {
            if (System.getProperty("java.vm.name") == "Dalvik") {
                android.system.Os.chmod(root.toString(), 0x1c0) // 0700
            } else {
            Files.setPosixFilePermissions(root, setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ))
            }
        } catch (failure: Exception) {
            throw IllegalStateException("metadata cache root filesystem cannot enforce app-private POSIX permissions", failure)
        }
        val marker = root.resolve(OWNER_FILE)
        if (existsNoFollow(marker)) {
            checkRegularFile(marker, "metadata cache ownership marker")
            val text = readSmallText(marker, 128)
            val match = OWNER_MARKER.matchEntire(text)
                ?: throw IllegalStateException("metadata cache ownership marker is malformed")
            match.groupValues[1]
        } else {
            val isEmpty = Files.newDirectoryStream(root).use { stream -> !stream.iterator().hasNext() }
            require(isEmpty) { "metadata cache root has no app-created ownership marker and is not empty" }
            val token = UUID.randomUUID().toString().replace("-", "")
            writeNew(marker, "forever-tact-cache-v1\n$token".toByteArray(Charsets.US_ASCII))
            makeImmutable(marker)
            forceDirectory(root)
            token
        }
    }

    private fun ensurePathDirectories(path: Path) {
        val absolute = path.toAbsolutePath().normalize()
        var current = absolute.root ?: throw IllegalArgumentException("metadata cache root is not absolute")
        for (part in absolute) {
            current = current.resolve(part)
            if (!existsNoFollow(current)) {
                try {
                    Files.createDirectory(current)
                } catch (failure: FileAlreadyExistsException) {
                    // Revalidate without following the new entry.
                }
                forceDirectory(current.parent)
            }
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("metadata cache root path contains a symlink or non-directory")
            }
        }
    }

    private fun withCacheLock(block: () -> File): File = synchronized(JVM_CACHE_LOCK) {
        checkNoSymlinkPath(root.resolve(LOCK_FILE))
        val lockChannel = FileChannel.open(
            root.resolve(LOCK_FILE),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        try {
            checkRegularFile(root.resolve(LOCK_FILE), "metadata cache lock")
            lockChannel.lock().use { block() }
        } finally {
            lockChannel.close()
        }
    }

    private fun indexCacheCount(): Int {
        val base = root.resolve("index")
        if (!existsNoFollow(base)) return 0
        if (Files.isSymbolicLink(base) || !Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("metadata index cache directory is unsafe")
        }
        var count = 0
        Files.newDirectoryStream(base).use { prefixes ->
            for (prefix in prefixes) {
                if (Files.isSymbolicLink(prefix) || !Files.isDirectory(prefix, LinkOption.NOFOLLOW_LINKS)) {
                    throw IllegalStateException("metadata index cache contains an unsafe prefix directory")
                }
                Files.newDirectoryStream(prefix).use { entries ->
                    for (entry in entries) {
                        if (entry.fileName.toString().endsWith(".index")) {
                            count++
                            require(count <= MAX_INDEX_COUNT) { "CDN metadata index cache exceeds the $MAX_INDEX_COUNT entry bound" }
                        }
                    }
                }
            }
        }
        return count
    }

    private fun readSmallText(path: Path, maxBytes: Int): String {
        checkRegularFile(path, "metadata cache receipt")
        val before = Files.size(path)
        require(before in 1..maxBytes.toLong()) { "metadata cache ownership record exceeds its bound" }
        val bytes = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val result = ByteArray(before.toInt())
            var offset = 0
            while (offset < result.size) {
                val count = input.read(result, offset, result.size - offset)
                if (count < 0) throw IOException("metadata cache ownership record was truncated")
                if (count > 0) offset += count
            }
            if (input.read() >= 0) throw IOException("metadata cache ownership record grew while reading")
            result
        }
        require(Files.size(path) == before) { "metadata cache ownership record changed while reading" }
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun digestFile(path: Path, algorithm: String, cancelled: () -> Boolean = { false }): String {
        checkRegularFile(path, "metadata cache file")
        val digest = MessageDigest.getInstance(algorithm)
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                checkCancelled(cancelled)
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }

    private fun sha256File(path: Path, cancelled: () -> Boolean = { false }): String = digestFile(path, "SHA-256", cancelled)

    private fun normalizeHash(value: String, description: String): String {
        require(HEX_MD5.matches(value)) { "$description must be a full 32-hex hash" }
        return value.lowercase(Locale.ROOT)
    }

    private fun validateMetadataReference(reference: ForeverTactMetadata.ObjectReference) {
        require(reference.role in METADATA_ROLES) { "${reference.role} is not an allowed metadata-object role; runtime/client payloads use the separate transfer engine" }
        require(HEX_MD5.matches(reference.contentKey) && HEX_MD5.matches(reference.encodingKey)) {
            "${reference.role} metadata reference must use full 32-hex CKey/EKey values"
        }
        require(reference.encodedSize in 1..MAX_RAW_OBJECT_BYTES) {
            "${reference.role} encoded metadata size exceeds the 256 MiB transport bound"
        }
        require(reference.decodedSize != null && reference.decodedSize in 1..MAX_DECODED_OBJECT_BYTES) {
            "${reference.role} metadata reference lacks a supported decoded-size/CKey verification binding"
        }
    }

    private fun configUrl(hash: String): String = "https://$OFFICIAL_HOST/$CDN_PATH/config/${hash.substring(0, 2)}/${hash.substring(2, 4)}/$hash"
    private fun dataUrl(hash: String): String = "https://$OFFICIAL_HOST/$CDN_PATH/data/${hash.substring(0, 2)}/${hash.substring(2, 4)}/$hash"
    private fun indexUrl(hash: String): String = "https://$OFFICIAL_HOST/$CDN_PATH/data/${hash.substring(0, 2)}/${hash.substring(2, 4)}/$hash.index"

    private fun checkRegularFile(path: Path, description: String) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("$description is not a no-follow regular file")
        }
    }

    private fun existsNoFollow(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    private fun checkNoSymlinkPath(path: Path) {
        var current = path.root ?: throw IllegalArgumentException("metadata cache path is not absolute")
        for (part in path) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) throw IllegalStateException("metadata cache path contains a symbolic link")
        }
    }

    private fun forceDirectory(path: Path?) {
        if (path == null) throw IllegalStateException("metadata cache directory has no parent")
        try {
            FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
        } catch (failure: Exception) {
            throw IllegalStateException("filesystem cannot durably sync metadata cache directory $path", failure)
        }
    }

    private fun checkActive(cancelled: () -> Boolean, started: Long) {
        if (cancelled()) throw CancellationException("metadata transport cancelled")
        if (System.nanoTime() - started >= MAX_OPERATION_NANOS) {
            throw java.util.concurrent.TimeoutException("metadata transport exceeded its 10-minute operation deadline")
        }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw CancellationException("metadata verification cancelled")
    }

    private fun u32be(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val OFFICIAL_HOST = "us.cdn.blizzard.com"
        private const val CDN_PATH = "tpr/wow"
        private const val OWNER_FILE = ".forever-tact-owner"
        private const val LOCK_FILE = ".forever-tact-lock"
        private const val BUDGET_FILE = ".forever-tact-budget"
        private const val MAX_CONFIG_BYTES = 4L * 1024 * 1024
        private const val MAX_RAW_OBJECT_BYTES = 256L * 1024 * 1024
        private const val MAX_DECODED_OBJECT_BYTES = 512L * 1024 * 1024
        private const val MAX_INDEX_BYTES = 16L * 1024 * 1024
        private const val MAX_INDEX_COUNT = 8_192
        private const val MAX_TOTAL_NETWORK_BYTES = 1L shl 30
        private const val MIN_EKEY_HEADER_BYTES = 12L
        private const val MAX_EKEY_HEADER_BYTES = 4L * 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val BUFFER_SIZE = 64 * 1024
        private val MAX_OPERATION_NANOS = TimeUnit.MINUTES.toNanos(10)
        private val HEX_MD5 = Regex("(?i)[0-9a-f]{32}")
        private val HEX_SHA256 = Regex("(?i)[0-9a-f]{64}")
        private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
        private val OWNER_MARKER = Regex("forever-tact-cache-v1\\n([0-9a-f]{32})")
        private val BLTE_MAGIC = byteArrayOf('B'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte())
        private val METADATA_ROLES = setOf("install", "download", "size", "encoding")
        private val JVM_CACHE_LOCK = Any()

        private fun openOfficialConnection(value: String): HttpURLConnection {
            val uri = URI(value)
            val path = uri.rawPath.orEmpty()
            val validPath = path.matches(Regex("/tpr/wow/(?:config|data)/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{32}(?:\\.index)?"))
            require(uri.scheme == "https" && uri.host == OFFICIAL_HOST && uri.userInfo == null &&
                uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null && validPath
            ) { "refusing metadata URL outside the official Blizzard HTTPS CDN" }
            return ForeverWifiDownloads.open(URL(value)) as? HttpsURLConnection
                ?: throw IllegalStateException("official metadata endpoint is not HTTPS")
        }
    }
}
