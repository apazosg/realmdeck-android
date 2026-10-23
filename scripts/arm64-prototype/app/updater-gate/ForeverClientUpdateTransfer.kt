package app.gamenative

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import javax.net.ssl.HttpsURLConnection

/** Resumable transfer of a single catalog-pinned encoded CASC object. */
class ForeverClientUpdateTransfer internal constructor(
    private val connectionFactory: (String) -> HttpURLConnection,
) {
    constructor() : this(::openOfficialConnection)

    /** Diagnostic per-object phase clocks (c105): nullable, allocated only for sampled keys. */
    private class FetchTimings {
        var headersWaitNanos = 0L
        var bodyReadNanos = 0L
        var channelWriteNanos = 0L
        var channelForceNanos = 0L
        var progressNanos = 0L
        var verifyNanos = 0L
        var publishNanos = 0L
    }

    private val diagnostics = Logger.getLogger("ForeverClientUpdate")

    /** Blocking I/O; invoke from a worker thread. The caller owns [workDir]. */
    @Synchronized
    fun fetch(
        record: ForeverClientUpdateCatalog.ObjectRecord,
        workDir: File,
        cancelled: () -> Boolean,
        progress: (Long) -> Unit,
    ): File {
        validateRecord(record)
        val deadlineStarted = System.nanoTime()
        // Sparse sampling: only EKeys ending in 00 (~1/256 for uniform hashes)
        // allocate clocks and emit one INFO line. All other objects run the exact
        // same operations with two null checks per clock site and no logging.
        val timings = if (isSampledForTimings(record)) FetchTimings() else null
        val directory = validateWorkDirectory(workDir)
        val output = directory.resolve("${record.ekey}.blte")
        val part = directory.resolve("${record.ekey}.part")
        checkOwnedFileState(output, "encoded object output")
        checkOwnedFileState(part, "encoded object partial")

        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            if (isVerified(output.toFile(), record, cancelled, deadlineStarted, timings)) {
                emitFetchTimings(record, timings, deadlineStarted, attempts = 0, cacheHit = true)
                return output.toFile()
            }
            // A full path not created by this invocation has no ownership proof.
            // Preserve it and require the caller to resolve the conflict explicitly.
            throw IllegalStateException("existing encoded-object output is invalid; refusing to replace an unowned file")
        }

        if (Files.exists(part, LinkOption.NOFOLLOW_LINKS)) {
            val partialSize = Files.size(part)
            if (partialSize > record.encodedSize) {
                deleteOwnedRegularFile(part)
            } else if (partialSize == record.encodedSize) {
                if (isVerified(part.toFile(), record, cancelled, deadlineStarted, timings)) {
                    val published = publishVerifiedPart(part, output, record, cancelled, deadlineStarted, timings)
                    // Complete owned partial reused without fetching: no network attempt.
                    emitFetchTimings(record, timings, deadlineStarted, attempts = 0, cacheHit = true)
                    return published
                }
                // A complete but corrupt owned partial must not poison every retry.
                deleteOwnedRegularFile(part)
            }
        }

        checkActive(cancelled, deadlineStarted)
        // Bounded intra-object retry: transient I/O (stalls, truncation, storage
        // hiccups) resumes from a re-statted offset; cancellation, the operation
        // deadline, and status/range/hash verification failures never retry.
        for (attempt in 0 until VERIFY_ATTEMPTS) {
            checkActive(cancelled, deadlineStarted)
            val existing = if (Files.exists(part, LinkOption.NOFOLLOW_LINKS)) Files.size(part) else 0L
            val sourceUrl = sourceUrl(record)
            try {
                streamObject(record, sourceUrl, existing, part, cancelled, progress, deadlineStarted, timings)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: java.util.concurrent.TimeoutException) {
                throw failure
            } catch (failure: IOException) {
                // Keep the short prefix: a stalled or truncated stream leaves a
                // resumable partial. When the prefix happens to be complete
                // (the read failed after the last byte), verify it instead of
                // discarding bytes that already passed the network.
                checkActive(cancelled, deadlineStarted)
                if (isCompleteOnDisk(part, record)) {
                    if (isVerified(part.toFile(), record, cancelled, deadlineStarted, timings)) {
                        val published = publishVerifiedPart(part, output, record, cancelled, deadlineStarted, timings)
                        emitFetchTimings(record, timings, deadlineStarted, attempts = attempt + 1, cacheHit = false)
                        return published
                    }
                    // Complete but corrupt: resuming is impossible, restart bounded.
                    deleteOwnedRegularFile(part)
                }
                if (attempt == VERIFY_ATTEMPTS - 1) throw failure
                continue
            }

            checkActive(cancelled, deadlineStarted)
            if (isVerified(part.toFile(), record, cancelled, deadlineStarted, timings)) {
                val published = publishVerifiedPart(part, output, record, cancelled, deadlineStarted, timings)
                emitFetchTimings(record, timings, deadlineStarted, attempts = attempt + 1, cacheHit = false)
                return published
            }
            deleteOwnedRegularFile(part)
            val detail = "downloaded encoded object failed EKey, BLTE, CKey, or size verification; " +
                "ekey=${record.ekey}; encodedSize=${record.encodedSize}; url=$sourceUrl; attempt=${attempt + 1}/$VERIFY_ATTEMPTS"
            if (attempt == VERIFY_ATTEMPTS - 1) throw IllegalStateException(detail)
        }
        throw IllegalStateException("unreachable encoded-object verification flow")
    }

    private fun streamObject(
        record: ForeverClientUpdateCatalog.ObjectRecord,
        sourceUrl: String,
        existing: Long,
        part: java.nio.file.Path,
        cancelled: () -> Boolean,
        progress: (Long) -> Unit,
        deadlineStarted: Long,
        timings: FetchTimings?,
    ) {
        require(existing in 0 until record.encodedSize) { "resume offset is outside the encoded-object size" }
        val rangeSource = record.sourceKind == "range"
        val ranged = rangeSource || existing > 0
        val start = if (rangeSource) Math.addExact(record.sourceOffset, existing) else existing
        val end = if (rangeSource) {
            Math.subtractExact(Math.addExact(record.sourceOffset, record.sourceLength), 1L)
        } else {
            record.encodedSize - 1L
        }
        require(start in 0..end) { "encoded-object HTTP range is invalid" }
        val expectedNetworkBytes = Math.addExact(Math.subtractExact(end, start), 1L)
        if (expectedNetworkBytes != record.encodedSize - existing) throw IllegalArgumentException("encoded-object source range length is inconsistent")

        checkActive(cancelled, deadlineStarted)
        val connection = connectionFactory(sourceUrl)
        var keepAliveCandidate = false
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (ranged) connection.setRequestProperty("Range", "bytes=$start-$end")

            checkActive(cancelled, deadlineStarted)
            // headersWait is the wall time for responseCode: DNS + TCP + TLS +
            // HTTP headers, not pure network. Timed separately so the serial
            // per-object wait can be split from payload streaming.
            val headersWaitStarted = if (timings != null) System.nanoTime() else 0L
            val status = connection.responseCode
            if (timings != null) timings.headersWaitNanos += System.nanoTime() - headersWaitStarted
            val expectedStatus = if (ranged) HttpURLConnection.HTTP_PARTIAL else HttpURLConnection.HTTP_OK
            require(status == expectedStatus) { "official CDN returned an unexpected HTTP status for encoded object" }
            val contentEncoding = connection.contentEncoding
            require(contentEncoding == null || contentEncoding.equals("identity", ignoreCase = true)) {
                "official CDN returned a transformed encoded-object body"
            }
            if (ranged) validateContentRange(
                connection,
                start,
                end,
                if (record.sourceKind == "direct") record.encodedSize else null,
            )
            require(connection.contentLengthLong == expectedNetworkBytes) { "official CDN encoded-object Content-Length mismatch" }

            val openOptions = arrayOf<OpenOption>(
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                LinkOption.NOFOLLOW_LINKS,
            )
            val channel = FileChannel.open(part, *openOptions)
            try {
                if (channel.size() != existing) throw IllegalStateException("encoded-object partial changed before resume")
                BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var received = 0L
                    while (received < expectedNetworkBytes) {
                        checkActive(cancelled, deadlineStarted)
                        // bodyRead wraps only input.read: socket stall + TLS +
                        // decode wait, excluding the channel write below.
                        val readStarted = if (timings != null) System.nanoTime() else 0L
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), expectedNetworkBytes - received).toInt())
                        if (timings != null) timings.bodyReadNanos += System.nanoTime() - readStarted
                        if (count < 0) throw IOException("official CDN encoded-object range was truncated")
                        if (count == 0) continue
                        val bytes = ByteBuffer.wrap(buffer, 0, count)
                        while (bytes.hasRemaining()) {
                            checkActive(cancelled, deadlineStarted)
                            val writeStarted = if (timings != null) System.nanoTime() else 0L
                            val written = channel.write(bytes)
                            if (timings != null) timings.channelWriteNanos += System.nanoTime() - writeStarted
                            if (written <= 0) throw IOException("could not write encoded-object partial")
                        }
                        received = Math.addExact(received, count.toLong())
                        val reported = Math.addExact(existing, received)
                        // progress covers the caller callback (Updater lock +
                        // notifications can contribute); timed apart from I/O.
                        val progressStarted = if (timings != null) System.nanoTime() else 0L
                        progress(reported)
                        if (timings != null) timings.progressNanos += System.nanoTime() - progressStarted
                    }
                    checkActive(cancelled, deadlineStarted)
                    // The trailing read is part of the drain check, so it joins bodyRead.
                    val drainStarted = if (timings != null) System.nanoTime() else 0L
                    val trailing = input.read()
                    if (timings != null) timings.bodyReadNanos += System.nanoTime() - drainStarted
                    require(trailing < 0) { "official CDN returned bytes beyond the requested encoded-object range" }
                    // The response body was fully drained: the pooled connection
                    // is reusable for the next object (c98 keep-alive).
                    keepAliveCandidate = true
                }
            } finally {
                // force timing must never skip close nor mask the original
                // failure: recording is plain Long arithmetic, then close runs
                // in the same inner finally as before.
                val forceStarted = if (timings != null) System.nanoTime() else 0L
                try {
                    channel.force(true)
                } finally {
                    if (timings != null) timings.channelForceNanos += System.nanoTime() - forceStarted
                    channel.close()
                }
            }
        } finally {
            // Keep-alive: the CDN connection stays pooled when the body was
            // fully drained; disconnect only after a failure avoids leaking an
            // unterminated socket. Diagnosed 2026-10-18: per-object TLS
            // handshakes dominated the serial download (c98 performance fix).
            if (!keepAliveCandidate) connection.disconnect()
        }
    }

    private fun validateContentRange(connection: HttpURLConnection, start: Long, end: Long, directTotal: Long?) {
        val header = connection.getHeaderField("Content-Range")
            ?: throw IllegalArgumentException("official CDN range response omitted Content-Range")
        val match = CONTENT_RANGE.matchEntire(header)
            ?: throw IllegalArgumentException("official CDN Content-Range is malformed")
        val actualStart = match.groupValues[1].toLongOrNull()
        val actualEnd = match.groupValues[2].toLongOrNull()
        val total = match.groupValues[3].toLongOrNull()
        require(actualStart == start && actualEnd == end) { "official CDN returned a different encoded-object range" }
        require(total != null && total > end && (directTotal == null || total == directTotal)) {
            "official CDN range total does not match the encoded object"
        }
    }

    private fun isCompleteOnDisk(part: java.nio.file.Path, record: ForeverClientUpdateCatalog.ObjectRecord): Boolean =
        Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS) && Files.size(part) == record.encodedSize

    private fun isVerified(
        file: File,
        record: ForeverClientUpdateCatalog.ObjectRecord,
        cancelled: () -> Boolean,
        deadlineStarted: Long,
        timings: FetchTimings?,
    ): Boolean {
        checkActive(cancelled, deadlineStarted)
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.size(file.toPath()) != record.encodedSize) return false
        return try {
            // verify covers EKey + BLTE + CKey authentication of the object.
            val verifyStarted = if (timings != null) System.nanoTime() else 0L
            try {
                verifyEKey(file, record.ekey, cancelled, deadlineStarted)
                checkActive(cancelled, deadlineStarted)
                // Reconstruction consumes the encoded bytes; TACT-encrypted frames
                // are authenticated without decryption (see ForeverBlte).
                ForeverBlte.verifyEncodedObject(file, record.decodedSize, record.contentKey, cancelled)
                checkActive(cancelled, deadlineStarted)
                true
            } finally {
                if (timings != null) timings.verifyNanos += System.nanoTime() - verifyStarted
            }
        } catch (cancelledFailure: CancellationException) {
            throw cancelledFailure
        } catch (failure: Exception) {
            false
        }
    }

    /** TACT EKey: hash the complete object for header-size zero, otherwise only BLTE header+table. */
    private fun verifyEKey(file: File, expectedEkey: String, cancelled: () -> Boolean, deadlineStarted: Long) {
        val digest = MessageDigest.getInstance("MD5")
        val fileLength = Files.size(file.toPath())
        BufferedInputStream(FileInputStream(file), BUFFER_SIZE).use { input ->
            val prefix = ByteArray(8)
            var prefixRead = 0
            while (prefixRead < prefix.size) {
                checkActive(cancelled, deadlineStarted)
                val count = input.read(prefix, prefixRead, prefix.size - prefixRead)
                if (count < 0) break
                if (count > 0) prefixRead += count
            }
            val isBlte = prefixRead == prefix.size && prefix.copyOfRange(0, 4).contentEquals(BLTE_MAGIC)
            val headerSize = if (isBlte) u32be(prefix, 4) else 0L
            if (isBlte && headerSize > 0L) {
                require(headerSize in 12..MAX_EKEY_HEADER_BYTES && headerSize <= fileLength) {
                    "BLTE EKey header exceeds the supported bound"
                }
                digest.update(prefix)
                copyDigest(input, digest, headerSize - prefix.size, cancelled, deadlineStarted, requireEndOfFile = false)
            } else {
                digest.update(prefix, 0, prefixRead)
                copyDigest(input, digest, fileLength - prefixRead, cancelled, deadlineStarted, requireEndOfFile = true)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedEkey, ignoreCase = true)) throw IllegalArgumentException("encoded object EKey mismatch")
    }

    private fun copyDigest(
        input: BufferedInputStream,
        digest: MessageDigest,
        length: Long,
        cancelled: () -> Boolean,
        deadlineStarted: Long,
        requireEndOfFile: Boolean,
    ) {
        var remaining = length
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            checkActive(cancelled, deadlineStarted)
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw IOException("encoded object was truncated while checking its EKey")
            if (count == 0) continue
            digest.update(buffer, 0, count)
            remaining -= count
        }
        if (requireEndOfFile && input.read() >= 0) throw IOException("encoded object changed while checking its EKey")
    }

    private fun publishVerifiedPart(
        part: java.nio.file.Path,
        output: java.nio.file.Path,
        record: ForeverClientUpdateCatalog.ObjectRecord,
        cancelled: () -> Boolean,
        deadlineStarted: Long,
        timings: FetchTimings?,
    ): File {
        val publishStarted = if (timings != null) System.nanoTime() else 0L
        try {
            checkActive(cancelled, deadlineStarted)
            checkOwnedFileState(output, "encoded object output")
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                if (isVerified(output.toFile(), record, cancelled, deadlineStarted, timings)) return output.toFile()
                throw IllegalStateException("encoded-object destination appeared with unverified contents; refusing replacement")
            }
            try {
                // Atomic, no-replace publication. Both paths are siblings so this stays on one filesystem.
                ForeverClientUpdateCatalog.publishVerifiedPrivateFile(output, part)
            } catch (failure: UnsupportedOperationException) {
                throw IllegalStateException("filesystem cannot atomically publish an encoded object without replacement", failure)
            } catch (failure: java.nio.file.FileSystemException) {
                if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                    if (isVerified(output.toFile(), record, cancelled, deadlineStarted, timings)) return output.toFile()
                    throw IllegalStateException("encoded-object destination appeared with unverified contents; refusing replacement", failure)
                }
                throw IllegalStateException("filesystem cannot atomically publish an encoded object", failure)
            }
            deleteOwnedRegularFile(part)
            return output.toFile()
        } finally {
            if (timings != null) timings.publishNanos += System.nanoTime() - publishStarted
        }
    }

    private fun validateRecord(record: ForeverClientUpdateCatalog.ObjectRecord) {
        require(record.ekey.matches(HEX_16) && record.contentKey.matches(HEX_16) && record.sourceHash.matches(HEX_16)) {
            "encoded-object record has an invalid key"
        }
        require(record.encodedSize in 1..ForeverClientUpdateCatalog.MAX_SUPPORTED_ENCODED_OBJECT_BYTES) {
            "encoded object exceeds the supported CASC segment-minus-header bound"
        }
        require(record.decodedSize in 0..ForeverClientUpdateCatalog.MAX_SUPPORTED_DECODED_OBJECT_BYTES) {
            "decoded object exceeds the supported BLTE decoder limit"
        }
        require(record.sourceLength == record.encodedSize && record.sourceOffset >= 0) {
            "encoded-object source length or offset is invalid"
        }
        try {
            Math.addExact(record.sourceOffset, record.sourceLength)
        } catch (failure: ArithmeticException) {
            throw IllegalArgumentException("encoded-object source range overflows", failure)
        }
        when (record.sourceKind) {
            "direct" -> require(record.sourceHash.equals(record.ekey, true) && record.sourceOffset == 0L) {
                "direct encoded-object source is not bound to its EKey"
            }
            "range" -> Unit
            else -> throw IllegalArgumentException("encoded-object source kind is unsupported")
        }
    }

    private fun sourceUrl(record: ForeverClientUpdateCatalog.ObjectRecord): String {
        val hash = if (record.sourceKind == "direct") record.ekey.lowercase(Locale.ROOT) else record.sourceHash.lowercase(Locale.ROOT)
        val url = "https://$OFFICIAL_HOST/tpr/wow/data/${hash.substring(0, 2)}/${hash.substring(2, 4)}/$hash"
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == OFFICIAL_HOST && uri.rawQuery == null && uri.rawFragment == null) {
            "encoded-object source is outside the official HTTPS CDN"
        }
        return url
    }

    private fun validateWorkDirectory(directory: File): java.nio.file.Path {
        val path = directory.toPath().toAbsolutePath().normalize()
        rejectSymlinkPath(path)
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalArgumentException("encoded-object work directory is not a regular directory")
        return path
    }

    private fun checkOwnedFileState(path: java.nio.file.Path, description: String) {
        if (Files.isSymbolicLink(path)) throw IllegalStateException("$description is a symbolic link")
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("$description is not a regular file")
        }
    }

    private fun deleteOwnedRegularFile(path: java.nio.file.Path) {
        checkOwnedFileState(path, "encoded-object partial")
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.delete(path)
    }

    private fun rejectSymlinkPath(path: java.nio.file.Path) {
        var current = path.root
        for (part in path) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) throw IllegalStateException("encoded-object work path contains a symbolic link")
        }
    }

    private fun checkActive(cancelled: () -> Boolean, started: Long) {
        if (cancelled()) throw CancellationException("encoded-object transfer cancelled")
        if (System.nanoTime() - started >= MAX_OPERATION_NANOS) {
            throw java.util.concurrent.TimeoutException("encoded-object transfer exceeded its operation deadline")
        }
    }

    private fun emitFetchTimings(
        record: ForeverClientUpdateCatalog.ObjectRecord,
        timings: FetchTimings?,
        fetchStarted: Long,
        attempts: Int,
        cacheHit: Boolean,
    ) {
        // Only sampled keys log; every other object returns above with timings null.
        if (timings == null) return
        // Best-effort diagnostic: a throwing logging backend must never turn a
        // valid publication into a failure. This catches only the diagnostic
        // call itself, never operation, cancellation, or hash errors. Non-fatal.
        try {
            // No paths, URLs, or credentials: only the public encoding key plus sizes.
            diagnostics.info(
                "fetch phases ekey=${record.ekey} bytes=${record.encodedSize} attempts=$attempts cacheHit=$cacheHit " +
                    "totalMs=${(System.nanoTime() - fetchStarted) / 1_000_000} " +
                    "headersWaitMs=${timings.headersWaitNanos / 1_000_000} " +
                    "bodyReadMs=${timings.bodyReadNanos / 1_000_000} " +
                    "channelWriteMs=${timings.channelWriteNanos / 1_000_000} " +
                    "channelForceMs=${timings.channelForceNanos / 1_000_000} " +
                    "progressMs=${timings.progressNanos / 1_000_000} " +
                    "verifyMs=${timings.verifyNanos / 1_000_000} " +
                    "publishMs=${timings.publishNanos / 1_000_000}",
            )
        } catch (diagnosticFailure: Exception) {
            // Observability-only; a broken log sink must not fail the fetch.
        }
    }

    private fun u32be(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

    companion object {
        private const val VERIFY_ATTEMPTS = 3
        private const val OFFICIAL_HOST = "us.cdn.blizzard.com"
        private const val CONNECT_TIMEOUT_MS = 30_000
        // Diagnosed 2026-10-18 (c98): a 30 s stall killed whole download sessions
        // (SocketTimeoutException at 12:51 after 25 min of progress); a slower
        // CDN stream only needs patience, the resume offset guards the bytes.
        private const val READ_TIMEOUT_MS = 120_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_EKEY_HEADER_BYTES = 16L * 1024 * 1024
        private val MAX_OPERATION_NANOS = TimeUnit.HOURS.toNanos(6)
        private val HEX_16 = Regex("(?i)[0-9a-f]{32}")
        private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
        private val BLTE_MAGIC = byteArrayOf('B'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte())

        // Deterministic sparse sample: EKeys ending in 00 (~1/256 for uniform hashes).
        private fun isSampledForTimings(record: ForeverClientUpdateCatalog.ObjectRecord): Boolean =
            record.ekey.endsWith("00", ignoreCase = true)

        private fun openOfficialConnection(value: String): HttpURLConnection {
            val uri = URI(value)
            require(uri.scheme == "https" && uri.host == OFFICIAL_HOST && uri.userInfo == null &&
                uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null
            ) { "encoded-object URL is outside the official HTTPS CDN" }
            return URL(value).openConnection() as? HttpsURLConnection
                ?: throw IllegalStateException("encoded-object connection is not HTTPS")
        }
    }
}
