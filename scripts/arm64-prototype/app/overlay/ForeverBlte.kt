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
 * Only the two modes present in the approved bootstrap are accepted: N (raw)
 * and Z (zlib).  In particular, accepting E or L here would turn a malformed
 * or incompatible catalog into an apparently valid installation.
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

    internal fun decode(
        input: File,
        output: File?,
        expectedSize: Long,
        expectedContentKey: String,
        cancelled: () -> Boolean = { false },
    ) {
        require(expectedSize >= 0) { "negative BLTE decoded size" }
        require(expectedSize <= MAX_DECODED_BYTES) { "BLTE decoded output exceeds safety limit" }
        require(expectedContentKey.matches(Regex("[0-9a-fA-F]{32}"))) {
            "invalid BLTE content key"
        }
        if (!input.isFile || Files.isSymbolicLink(input.toPath()) || input.length() < 4) throw IllegalArgumentException("BLTE input missing or truncated")
        val destination = output?.also {
            rejectSymlinkParents(it)
            it.parentFile?.mkdirs()
            if (it.exists() && !it.isFile) throw IllegalStateException("BLTE output is not a regular file")
        }
        val decodedMd5 = MessageDigest.getInstance("MD5")
        var decodedBytes = 0L
        BufferedInputStream(input.inputStream(), BUFFER_SIZE).use { source ->
            val sink = destination?.let {
                BufferedOutputStream(
                    Files.newOutputStream(it.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS),
                    BUFFER_SIZE,
                )
            }
            try {
                val magic = readExact(source, 4, cancelled)
                if (!magic.contentEquals(byteArrayOf('B'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte()))) {
                    // A few metadata objects are plain bytes rather than BLTE.
                    source.close()
                    copyPlain(input, sink, decodedMd5, cancelled, expectedSize).also { decodedBytes = it }
                } else {
                    val headerSize = readU32(source, cancelled)
                    if (headerSize == 0L) {
                        // available() is only a socket/read-buffer estimate and
                        // is not the number of bytes remaining in a file.
                        val payloadLength = input.length() - 8L
                        if (payloadLength < 1) throw IllegalArgumentException("truncated BLTE zero-header payload")
                        val result = processChunk(source, payloadLength, null, null, sink, decodedMd5, cancelled, expectedSize)
                        decodedBytes += result
                    } else {
                        if (headerSize < 12 || headerSize > MAX_HEADER_SIZE || headerSize > input.length()) {
                            throw IllegalArgumentException("invalid BLTE header size: $headerSize")
                        }
                        val flags = readExact(source, 1, cancelled)[0].toInt() and 0xff
                        val count = readU24(source, cancelled)
                        if (count <= 0 || count > MAX_CHUNKS || count.toLong() * 40L > MAX_DESCRIPTOR_BYTES) {
                            throw IllegalArgumentException("BLTE chunk descriptor list exceeds safety limit: $count")
                        }
                        val entrySize = if (flags == 0x10) 40 else if (flags == 0x0f) 24 else throw IllegalArgumentException("unsupported BLTE flags: $flags")
                        if (headerSize != 12L + count * entrySize) throw IllegalArgumentException("BLTE header length/count mismatch")
                        if (headerSize > input.length()) throw IllegalArgumentException("truncated BLTE chunk descriptors")
                        val entries = ArrayList<Chunk>(count)
                        var compressedTotal = 0L
                        var decodedTotal = 0L
                        repeat(count) {
                            val compressed = readU32(source, cancelled)
                            val uncompressed = readU32(source, cancelled)
                            val checksum = readExact(source, 16, cancelled)
                            val decodedChecksum = if (flags == 0x10) readExact(source, 16, cancelled) else null
                            if (compressed <= 0 || uncompressed < 0) throw IllegalArgumentException("invalid BLTE chunk sizes")
                            if (uncompressed > MAX_DECODED_BYTES || expectedSize - decodedBytes - decodedTotal < uncompressed) {
                                throw IllegalArgumentException("BLTE decoded output exceeds declared limit")
                            }
                            compressedTotal = Math.addExact(compressedTotal, compressed)
                            decodedTotal = Math.addExact(decodedTotal, uncompressed)
                            entries += Chunk(compressed, uncompressed, checksum, decodedChecksum)
                        }
                        if (compressedTotal != input.length() - headerSize) throw IllegalArgumentException("BLTE chunk sizes do not consume input")
                        entries.forEach { entry ->
                            decodedBytes += processChunk(source, entry.compressedSize, entry.compressedChecksum, entry.decodedChecksum, sink, decodedMd5, cancelled, entry.decodedSize)
                        }
                        if (source.read() != -1) throw IllegalArgumentException("trailing bytes after BLTE chunks")
                    }
                }
            } finally {
                sink?.close()
            }
        }
        if (decodedBytes != expectedSize) throw IllegalArgumentException("BLTE decoded size mismatch: $decodedBytes != $expectedSize")
        if (!decodedMd5.digest().contentEquals(expectedContentKey.hexBytes())) {
            throw IllegalArgumentException("BLTE content key mismatch")
        }
    }

    internal fun verify(input: File, expectedSize: Long, expectedContentKey: String, cancelled: () -> Boolean = { false }) =
        decode(input, null, expectedSize, expectedContentKey, cancelled)

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
    ): Long {
        if (compressedSize < 1) throw IllegalArgumentException("empty BLTE chunk")
        if (expectedDecodedSize > MAX_DECODED_BYTES) throw IllegalArgumentException("BLTE chunk output exceeds safety limit")
        val compressedMd5 = expectedCompressedMd5?.let { MessageDigest.getInstance("MD5") }
        val decodedMd5 = expectedDecodedMd5?.let { MessageDigest.getInstance("MD5") }
        val mode = readExact(source, 1, cancelled)[0]
        compressedMd5?.update(mode)
        val remaining = compressedSize - 1
        val count = when (mode.toInt().and(0xff).toChar()) {
            'N' -> copyRaw(source, remaining, sink, compressedMd5, decodedMd5, wholeDecodedMd5, cancelled, expectedDecodedSize)
            'Z' -> inflate(source, remaining, sink, compressedMd5, decodedMd5, wholeDecodedMd5, cancelled, expectedDecodedSize)
            else -> throw IllegalArgumentException("unsupported BLTE mode: ${mode.toInt().and(0xff).toChar()}")
        }
        if (expectedDecodedSize >= 0 && count != expectedDecodedSize) throw IllegalArgumentException("BLTE chunk decoded size mismatch")
        if (compressedMd5 != null && !compressedMd5.digest().contentEquals(expectedCompressedMd5)) throw IllegalArgumentException("BLTE compressed checksum mismatch")
        if (decodedMd5 != null && !decodedMd5.digest().contentEquals(expectedDecodedMd5)) throw IllegalArgumentException("BLTE decoded checksum mismatch")
        return count
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

    private fun copyPlain(input: File, sink: OutputStream?, md5: MessageDigest, cancelled: () -> Boolean, expectedSize: Long): Long {
        var total = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        input.inputStream().use { source ->
            while (true) {
                checkCancelled(cancelled)
                val n = source.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                if (total > expectedSize - n) throw IllegalArgumentException("plain object exceeds declared decoded size")
                md5.update(buffer, 0, n)
                sink?.write(buffer, 0, n)
                total += n
            }
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
