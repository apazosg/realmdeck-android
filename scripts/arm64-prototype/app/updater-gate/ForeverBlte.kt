package app.gamenative

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.Inflater

/** The small BLTE reader needed by the bootstrap catalog.
 *
 * Strict decoding accepts only the two verified plaintext modes: N (raw) and
 * Z (zlib).  Accepting E (TACT-encrypted frames) there would turn a malformed
 * or incompatible catalog into an apparently valid installation.
 *
 * Reconstruction paths verify already-published encoded objects whose decoded
 * content cannot be re-derived without the per-build TACT key.  When the caller
 * opts in via [decode]'s allowEncrypted flag, an E frame is validated without
 * decryption: the frame's entry checksum (MD5 over the encoded stream including
 * the mode byte) still authenticates every payload byte, the enclosing EKey
 * digest authenticates the header, and the declared totals pin the object's
 * place in the encoding chain.  No plaintext is produced, so the whole-object
 * content-key check is skipped for objects containing E frames; the game
 * decrypts its own data with its embedded runtime keys.
 */
object ForeverBlte {
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_HEADER_SIZE = 16 * 1024 * 1024
    // The pinned catalog has a small, finite chunk descriptor set.  Keeping
    // this bounded independently of the file size prevents a hostile header
    // from allocating an attacker-controlled million-entry ArrayList.
    private const val MAX_CHUNKS = 8_192
    private const val MAX_DESCRIPTOR_BYTES = 4 * 1024 * 1024
    private const val MAX_DECODED_BYTES = 512L * 1024 * 1024
    private val BLTE_MAGIC = byteArrayOf('B'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte())

    internal fun decode(
        input: File,
        output: File?,
        expectedSize: Long,
        expectedContentKey: String,
        cancelled: () -> Boolean = { false },
        allowEncrypted: Boolean = false,
    ): String {
        require(expectedSize >= 0) { "negative BLTE decoded size" }
        require(expectedSize <= MAX_DECODED_BYTES) { "BLTE decoded output exceeds safety limit" }
        require(expectedContentKey.matches(Regex("[0-9a-fA-F]{32}"))) {
            "invalid BLTE content key"
        }
        if (!input.isFile || Files.isSymbolicLink(input.toPath()) || input.length() < 4) throw IllegalArgumentException("BLTE input missing or truncated")
        // The encoded length is read once, fresh at open, and the shared core
        // regards it as N.  No atomic snapshot of a concurrently mutated file
        // is claimed: a stable file verifies exactly as before.
        val encodedSize = input.length()
        val destination = output?.also {
            rejectSymlinkParents(it)
            it.parentFile?.mkdirs()
            if (it.exists() && !it.isFile) throw IllegalStateException("BLTE output is not a regular file")
        }
        input.inputStream().use { raw ->
            val sink = destination?.let {
                BufferedOutputStream(
                    Files.newOutputStream(it.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS),
                    BUFFER_SIZE,
                )
            }
            try {
                val verdict = decodeStream(raw, encodedSize, sink, expectedSize, expectedContentKey, cancelled, allowEncrypted)
                // decodeStream never reads past N, so a stable file is exactly
                // consumed here; a byte beyond N is a grown or appended file.
                if (raw.read() != -1) throw IllegalArgumentException("trailing bytes after BLTE chunks")
                return verdict
            } finally {
                sink?.close()
            }
        }
    }

    internal fun verify(input: File, expectedSize: Long, expectedContentKey: String, cancelled: () -> Boolean = { false }) =
        decode(input, null, expectedSize, expectedContentKey, cancelled)

    /**
     * Reconstruction-grade check of an encoded object whose bytes were already
     * digested against the official EKey by the caller.  N/Z objects behave
     * exactly like [verify]; objects containing E (TACT-encrypted) frames are
     * authenticated without decryption: the frame entry checksum (MD5 over the
     * encoded stream and its mode byte) still covers every payload byte, the
     * declared decoded totals are enforced, and the content-key digest is not
     * evaluated because the plaintext cannot be produced without the build's
     * runtime key.  Returns "decoded" or "encrypted"; reconstructing callers
     * keep the raw encoded object for the CASC writer either way.
     */
    internal fun verifyEncodedObject(
        input: File,
        expectedSize: Long,
        expectedContentKey: String,
        cancelled: () -> Boolean = { false },
    ): String = decode(input, null, expectedSize, expectedContentKey, cancelled, allowEncrypted = true)

    /**
     * Reconstruction-grade check of exactly N encoded bytes drawn from a
     * caller-owned window (for example the N bytes after a 30-byte CASC local
     * header).  The EKey and the BLTE structure are verified in one pass over
     * the window: [expectedEkey] is the TACT encoded key (whole-object MD5 for
     * headerless/plain objects, header-and-table MD5 for chunked objects) and
     * the stream must deliver exactly [encodedSize] bytes, no more and no
     * fewer.  E frames pass through with the same semantics as
     * [verifyEncodedObject], so the result is "decoded" or "encrypted".
     *
     * The window owns its stream: this never closes [source], even on
     * cancellation or verification failure, and never reads past N, so an
     * adjacent following object stays untouched.  No Android dependencies are
     * used; only java.io plus MessageDigest.
     */
    internal fun verifyEncodedRange(
        source: InputStream,
        encodedSize: Long,
        expectedEkey: String,
        expectedSize: Long,
        expectedContentKey: String,
        cancelled: () -> Boolean = { false },
    ): String {
        require(expectedSize >= 0) { "negative BLTE decoded size" }
        require(expectedSize <= MAX_DECODED_BYTES) { "BLTE decoded output exceeds safety limit" }
        require(expectedContentKey.matches(Regex("[0-9a-fA-F]{32}"))) {
            "invalid BLTE content key"
        }
        val wantedEkey = expectedEkey.lowercase()
        require(wantedEkey.matches(Regex("[0-9a-f]{32}"))) {
            "invalid BLTE encoded key"
        }
        require(encodedSize >= 4) { "BLTE encoded range is truncated" }
        return decodeStream(source, encodedSize, null, expectedSize, expectedContentKey, cancelled, allowEncrypted = true, expectedEkey = wantedEkey)
    }

    /**
     * The single shared BLTE core over exactly N encoded bytes.  Both the
     * File wrappers ([decode]) and the caller-windowed [verifyEncodedRange]
     * parse here, so acceptance and rejection stay identical and no second
     * parser can drift.  [encodedSize] replaces every former input.length()
     * use in header, table-total, and trailing arithmetic; all values stay in
     * Long.  Cancellation, decoded-size/content-key enforcement, the
     * 512MiB/zlib bomb guards, and the always-reject-0x10-with-allowEncrypted
     * rule are unchanged.
     */
    private fun decodeStream(
        source: InputStream,
        encodedSize: Long,
        sink: OutputStream?,
        expectedSize: Long,
        expectedContentKey: String,
        cancelled: () -> Boolean,
        allowEncrypted: Boolean,
        expectedEkey: String? = null,
    ): String {
        require(encodedSize >= 4) { "BLTE input is truncated" }
        val bounded = BoundedRangeStream(source, encodedSize)
        val digesting = expectedEkey?.let { EKeyDigestingStream(bounded, encodedSize) }
        // Buffering always sits above the exact N-byte bound, never below it,
        // so no read-ahead can pull bytes past the window.
        val windowed = BufferedInputStream(digesting ?: bounded, BUFFER_SIZE)
        val decodedMd5 = MessageDigest.getInstance("MD5")
        var decodedBytes = 0L
        var encryptedFrames = 0
        val magic = readExact(windowed, 4, cancelled)
        if (!magic.contentEquals(BLTE_MAGIC)) {
            // A few metadata objects are plain bytes rather than BLTE.  The 4
            // probed bytes belong to the content: hash and sink them, then keep
            // reading the same stream instead of closing and reopening it.
            // The declared-size guard runs before any hash or sink write, so an
            // undersized declaration rejects without emitting bytes.
            if (expectedSize < 4) throw IllegalArgumentException("plain object exceeds declared decoded size")
            decodedMd5.update(magic)
            sink?.write(magic)
            decodedBytes = 4L + copyPlainRemainder(windowed, sink, decodedMd5, cancelled, expectedSize, 4L)
        } else {
            val headerSize = readU32(windowed, cancelled)
            if (headerSize == 0L) {
                // available() is only a socket/read-buffer estimate and
                // is not the number of bytes remaining in a stream.
                val payloadLength = encodedSize - 8L
                if (payloadLength < 1) throw IllegalArgumentException("truncated BLTE zero-header payload")
                val result = processChunk(windowed, payloadLength, null, null, sink, decodedMd5, cancelled, expectedSize, allowEncrypted)
                decodedBytes += result.first
                if (result.second) encryptedFrames += 1
            } else {
                if (headerSize < 12 || headerSize > MAX_HEADER_SIZE || headerSize > encodedSize) {
                    throw IllegalArgumentException("invalid BLTE header size: $headerSize")
                }
                val flags = readExact(windowed, 1, cancelled)[0].toInt() and 0xff
                val count = readU24(windowed, cancelled)
                if (count <= 0 || count > MAX_CHUNKS || count.toLong() * 40L > MAX_DESCRIPTOR_BYTES) {
                    throw IllegalArgumentException("BLTE chunk descriptor list exceeds safety limit: $count")
                }
                val entrySize = if (flags == 0x10) 40 else if (flags == 0x0f) 24 else throw IllegalArgumentException("unsupported BLTE flags: $flags")
                if (headerSize != 12L + count * entrySize) throw IllegalArgumentException("BLTE header length/count mismatch")
                val entries = ArrayList<Chunk>(count)
                var compressedTotal = 0L
                var decodedTotal = 0L
                repeat(count) {
                    val compressed = readU32(windowed, cancelled)
                    val uncompressed = readU32(windowed, cancelled)
                    val checksum = readExact(windowed, 16, cancelled)
                    val decodedChecksum = if (flags == 0x10) readExact(windowed, 16, cancelled) else null
                    if (compressed <= 0 || uncompressed < 0) throw IllegalArgumentException("invalid BLTE chunk sizes")
                    if (uncompressed > MAX_DECODED_BYTES || expectedSize - decodedBytes - decodedTotal < uncompressed) {
                        throw IllegalArgumentException("BLTE decoded output exceeds declared limit")
                    }
                    compressedTotal = Math.addExact(compressedTotal, compressed)
                    decodedTotal = Math.addExact(decodedTotal, uncompressed)
                    // A per-frame decoded checksum pins the decrypted frame
                    // bytes; without TACT keys it can never be reproduced.
                    if (flags == 0x10 && allowEncrypted) {
                        throw IllegalArgumentException("BLTE per-frame decoded checksum is unverifiable for encrypted frames")
                    }
                    entries += Chunk(compressed, uncompressed, checksum, decodedChecksum)
                }
                if (compressedTotal != encodedSize - headerSize) throw IllegalArgumentException("BLTE chunk sizes do not consume input")
                entries.forEach { entry ->
                    val result = processChunk(windowed, entry.compressedSize, entry.compressedChecksum, entry.decodedChecksum, sink, decodedMd5, cancelled, entry.decodedSize, allowEncrypted)
                    decodedBytes += result.first
                    if (result.second) encryptedFrames += 1
                }
                if (windowed.read() != -1) throw IllegalArgumentException("trailing bytes after BLTE chunks")
            }
        }
        if (bounded.consumed != encodedSize) throw IllegalArgumentException("BLTE input was not fully consumed")
        if (decodedBytes != expectedSize) throw IllegalArgumentException("BLTE decoded size mismatch: $decodedBytes != $expectedSize")
        // With encrypted frames no plaintext exists this side of the key store,
        // so the whole-object content-key digest is deliberately not evaluated.
        if (encryptedFrames == 0 && !decodedMd5.digest().contentEquals(expectedContentKey.hexBytes())) {
            throw IllegalArgumentException("BLTE content key mismatch")
        }
        if (expectedEkey != null && digesting!!.ekeyHex() != expectedEkey) {
            throw IllegalArgumentException("BLTE encoded key mismatch")
        }
        return if (encryptedFrames > 0) "encrypted" else "decoded"
    }

    /**
     * An exact N-byte window over a caller-owned stream.  Reads are capped at
     * the remaining window so the parser can never consume a following object;
     * a short underlying stream throws instead of masking a truncation as a
     * clean EOF.  Zero-length reads return 0 without touching the source.
     * Skip is refused so bytes cannot bypass the EKey digest, mark/reset are
     * unsupported, and available() reports no length.  Close is a no-op: the
     * window never owns the caller's FD.
     */
    private class BoundedRangeStream(private val source: InputStream, limit: Long) : InputStream() {
        var consumed = 0L
            private set
        private var remaining = limit

        override fun read(): Int {
            if (remaining <= 0) return -1
            val next = source.read()
            if (next < 0) throw IllegalArgumentException("truncated encoded range")
            remaining -= 1
            consumed += 1
            return next
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || length > buffer.size - offset) throw IndexOutOfBoundsException("invalid range read")
            if (length == 0) return 0
            if (remaining <= 0) return -1
            val n = source.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (n < 0) throw IllegalArgumentException("truncated encoded range")
            if (n == 0) throw IllegalArgumentException("encoded range source made no progress")
            remaining -= n
            consumed += n
            return n
        }

        override fun skip(n: Long): Long =
            throw UnsupportedOperationException("skip would bypass the encoded range digest")

        override fun available(): Int = 0

        override fun markSupported(): Boolean = false

        override fun close() {
            // The window never owns the caller's stream.
        }
    }

    /**
     * Observing EKey digest over the bytes passing through to the parser.  At
     * most 8 bytes are retained: once the first 4 arrive, a non-BLTE prefix
     * decides a whole-object hash of N; a BLTE prefix waits for all 8 to read
     * the big-endian header size, hashing N for a zero header or exactly the
     * header bytes otherwise.  Only [0, hashLen) is digested while all N bytes
     * still reach the parser.  Plain objects of 4..7 bytes therefore hash
     * without waiting for 8; a BLTE window shorter than 8 is rejected by the
     * shared core.  Single-byte and bulk reads digest identically, skip is
     * refused, and close never propagates.
     */
    private class EKeyDigestingStream(source: InputStream, private val total: Long) : InputStream() {
        private val origin = source
        private val md5 = MessageDigest.getInstance("MD5")
        private val prefix = ByteArray(8)
        private var prefixLen = 0
        private var hashLen = -1L
        private var fed = 0L
        private val single = ByteArray(1)

        fun ekeyHex(): String = md5.digest().joinToString("") { "%02x".format(it) }

        private fun decide() {
            if (hashLen >= 0 || prefixLen < 4) return
            if (!prefix.copyOfRange(0, 4).contentEquals(BLTE_MAGIC)) {
                adopt(total)
                return
            }
            if (prefixLen < 8) return
            val headerSize = prefix.copyOfRange(4, 8).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }
            adopt(if (headerSize == 0L) total else headerSize)
        }

        private fun adopt(length: Long) {
            hashLen = length
            val initial = minOf(prefixLen.toLong(), length)
            if (initial > 0) {
                md5.update(prefix, 0, initial.toInt())
                fed = initial
            }
        }

        private fun observe(bytes: ByteArray, offset: Int, length: Int) {
            var current = offset
            var left = length
            // The prefix only buffers undecided bytes: once the hash length is
            // known, every remaining byte flows straight to the digest (up to
            // the limit) instead of being parked in the prefix and never hashed
            // when deliveries arrive fragmented.
            while (left > 0 && hashLen < 0 && prefixLen < 8) {
                prefix[prefixLen++] = bytes[current++]
                left -= 1
                decide()
            }
            if (hashLen >= 0 && fed < hashLen && left > 0) {
                val n = minOf(left.toLong(), hashLen - fed).toInt()
                md5.update(bytes, current, n)
                fed += n
            }
        }

        override fun read(): Int {
            val next = origin.read()
            if (next < 0) return -1
            single[0] = next.toByte()
            observe(single, 0, 1)
            return next
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || length > buffer.size - offset) throw IndexOutOfBoundsException("invalid range read")
            if (length == 0) return 0
            val n = origin.read(buffer, offset, length)
            if (n < 0) return -1
            if (n == 0) return 0
            observe(buffer, offset, n)
            return n
        }

        override fun skip(n: Long): Long =
            throw UnsupportedOperationException("skip would bypass the encoded range digest")

        override fun available(): Int = 0

        override fun markSupported(): Boolean = false

        override fun close() {
            // The digest observes the caller's stream without owning it.
        }
    }

    private data class Chunk(
        val compressedSize: Long,
        val decodedSize: Long,
        val compressedChecksum: ByteArray,
        val decodedChecksum: ByteArray?,
    )

    private fun processChunk(
        source: InputStream,
        compressedSize: Long,
        expectedCompressedMd5: ByteArray?,
        expectedDecodedMd5: ByteArray?,
        sink: OutputStream?,
        wholeDecodedMd5: MessageDigest,
        cancelled: () -> Boolean,
        expectedDecodedSize: Long = -1,
        allowEncrypted: Boolean = false,
    ): Pair<Long, Boolean> {
        if (compressedSize < 1) throw IllegalArgumentException("empty BLTE chunk")
        if (expectedDecodedSize > MAX_DECODED_BYTES) throw IllegalArgumentException("BLTE chunk output exceeds safety limit")
        val compressedMd5 = expectedCompressedMd5?.let { MessageDigest.getInstance("MD5") }
        val decodedMd5 = expectedDecodedMd5?.let { MessageDigest.getInstance("MD5") }
        val mode = readExact(source, 1, cancelled)[0]
        compressedMd5?.update(mode)
        val remaining = compressedSize - 1
        val decodedCount: Long
        var encrypted = false
        when (mode.toInt().and(0xff).toChar()) {
            'N' -> decodedCount = copyRaw(source, remaining, sink, compressedMd5, decodedMd5, wholeDecodedMd5, cancelled, expectedDecodedSize)
            'Z' -> decodedCount = inflate(source, remaining, sink, compressedMd5, decodedMd5, wholeDecodedMd5, cancelled, expectedDecodedSize)
            'E' -> {
                if (!allowEncrypted) throw IllegalArgumentException("unsupported BLTE mode: E")
                check(sink == null) { "decrypted BLTE output is unsupported without keys" }
                encrypted = true
                skipEncrypted(source, remaining, compressedMd5, cancelled)
                decodedCount = expectedDecodedSize.coerceAtLeast(0L)
            }
            else -> throw IllegalArgumentException("unsupported BLTE mode: ${mode.toInt().and(0xff).toChar()}")
        }
        if (expectedDecodedSize >= 0 && expectedDecodedSize > 0 && decodedCount != expectedDecodedSize) {
            // Encrypted frames contribute the declared size; plaintext frames the real count.
            if (!encrypted) throw IllegalArgumentException("BLTE chunk decoded size mismatch")
        }
        if (compressedMd5 != null && !compressedMd5.digest().contentEquals(expectedCompressedMd5)) throw IllegalArgumentException("BLTE compressed checksum mismatch")
        if (decodedMd5 != null && !decodedMd5.digest().contentEquals(expectedDecodedMd5)) throw IllegalArgumentException("BLTE decoded checksum mismatch")
        return decodedCount to encrypted
    }

    /**
     * Pass-through of an encrypted frame without decryption.  Calculating the
     * compressed-checksum digest over the encoded bytes still authenticates the
     * exact published payload byte-for-byte; the plaintext is never produced.
     */
    private fun skipEncrypted(
        source: InputStream,
        length: Long,
        compressedMd5: MessageDigest?,
        cancelled: () -> Boolean,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var left = length
        while (left > 0) {
            checkCancelled(cancelled)
            val n = source.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw IllegalArgumentException("truncated encrypted BLTE chunk")
            if (n == 0) continue
            compressedMd5?.update(buffer, 0, n)
            left -= n
        }
    }

    private fun copyRaw(
        source: InputStream,
        length: Long,
        sink: OutputStream?,
        compressedMd5: MessageDigest?,
        decodedMd5: MessageDigest?,
        wholeDecodedMd5: MessageDigest,
        cancelled: () -> Boolean,
        expectedDecodedSize: Long,
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var left = length
        var total = 0L
        while (left > 0) {
            checkCancelled(cancelled)
            val n = source.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw IllegalArgumentException("truncated BLTE chunk")
            if (n == 0) continue
            if (expectedDecodedSize >= 0 && total > expectedDecodedSize - n) throw IllegalArgumentException("BLTE raw chunk exceeds declared decoded size")
            compressedMd5?.update(buffer, 0, n)
            decodedMd5?.update(buffer, 0, n)
            wholeDecodedMd5.update(buffer, 0, n)
            sink?.write(buffer, 0, n)
            total += n
            left -= n
        }
        return total
    }

    private fun inflate(
        source: InputStream,
        length: Long,
        sink: OutputStream?,
        compressedMd5: MessageDigest?,
        decodedMd5: MessageDigest?,
        wholeDecodedMd5: MessageDigest,
        cancelled: () -> Boolean,
        expectedDecodedSize: Long,
    ): Long {
        val inflater = Inflater(false)
        val input = ByteArray(BUFFER_SIZE)
        val output = ByteArray(BUFFER_SIZE)
        var left = length
        var total = 0L
        try {
            while (left > 0 && !inflater.finished()) {
                checkCancelled(cancelled)
                val n = source.read(input, 0, minOf(input.size.toLong(), left).toInt())
                if (n < 0) throw IllegalArgumentException("truncated zlib BLTE chunk")
                if (n == 0) continue
                compressedMd5?.update(input, 0, n)
                left -= n
                inflater.setInput(input, 0, n)
                while (!inflater.needsInput() && !inflater.finished()) {
                    checkCancelled(cancelled)
                    val produced = inflater.inflate(output)
                    if (produced == 0 && inflater.needsDictionary()) throw IllegalArgumentException("zlib dictionary is unsupported")
                    if (produced == 0 && !inflater.finished() && !inflater.needsInput()) throw IllegalArgumentException("invalid zlib BLTE stream")
                    if (produced > 0) {
                        if (expectedDecodedSize >= 0 && total > expectedDecodedSize - produced) {
                            throw IllegalArgumentException("BLTE zlib chunk exceeds declared decoded size")
                        }
                        decodedMd5?.update(output, 0, produced)
                        wholeDecodedMd5.update(output, 0, produced)
                        sink?.write(output, 0, produced)
                        total += produced
                    }
                }
                if (inflater.finished() && inflater.remaining != 0) throw IllegalArgumentException("trailing bytes in zlib BLTE chunk")
            }
            if (!inflater.finished()) throw IllegalArgumentException("truncated zlib BLTE stream")
            // The final input block may contain bytes that were not consumed.
            if (inflater.remaining != 0 || left != 0L) throw IllegalArgumentException("trailing bytes in zlib BLTE chunk")
        } catch (e: java.util.zip.DataFormatException) {
            throw IllegalArgumentException("invalid zlib BLTE stream", e)
        } finally {
            inflater.end()
        }
        return total
    }

    /**
     * Remainder of a plain (non-BLTE) object after its 4 probed magic bytes.
     * [alreadyCounted] seeds the declared-size guard so the 4 hashed prefix
     * bytes count toward the limit exactly as a reopened whole-file copy did.
     */
    private fun copyPlainRemainder(
        source: InputStream,
        sink: OutputStream?,
        md5: MessageDigest,
        cancelled: () -> Boolean,
        expectedSize: Long,
        alreadyCounted: Long,
    ): Long {
        var total = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            checkCancelled(cancelled)
            val n = source.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            if (alreadyCounted + total > expectedSize - n) throw IllegalArgumentException("plain object exceeds declared decoded size")
            md5.update(buffer, 0, n)
            sink?.write(buffer, 0, n)
            total += n
        }
        return total
    }

    private fun readExact(source: InputStream, count: Int, cancelled: () -> Boolean): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            checkCancelled(cancelled)
            val n = source.read(result, offset, count - offset)
            if (n < 0) throw IllegalArgumentException("truncated BLTE header")
            if (n == 0) continue
            offset += n
        }
        return result
    }

    private fun readU32(source: InputStream, cancelled: () -> Boolean): Long =
        readExact(source, 4, cancelled).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }

    private fun readU24(source: InputStream, cancelled: () -> Boolean): Int =
        readExact(source, 3, cancelled).fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 0xff) }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw java.util.concurrent.CancellationException("client provisioning cancelled")
    }

    private fun rejectSymlinkParents(file: File) {
        var parent = file.parentFile
        while (parent != null) {
            if (Files.isSymbolicLink(parent.toPath())) throw IllegalStateException("BLTE output path contains a symlink")
            parent = parent.parentFile
        }
    }

    private fun String.hexBytes(): ByteArray = ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
