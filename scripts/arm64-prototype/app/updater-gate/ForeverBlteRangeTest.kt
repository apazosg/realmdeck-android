package app.gamenative

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.Deflater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focal File-vs-range equivalence for the single shared BLTE core: plain
 * 4..7-byte objects, zero-header N/Z/E, chunked 0x0f N/Z/E passthrough and the
 * current always-reject-0x10 rule, corruption/truncation/trailing/EKey/limit
 * rejection, and the windowed-stream contract (single-byte delivery, exact
 * N-byte consumption without overread, no close, no skip).
 */
class ForeverBlteRangeTest {
    @Test
    fun fileVsRangePlainFourToSevenBytes() {
        for (size in 4..7) {
            val raw = ByteArray(size) { (it + 1).toByte() }
            val file = fixture(raw)
            try {
                ForeverBlte.verify(file, raw.size.toLong(), md5Hex(raw))
                assertEquals(
                    "decoded",
                    ForeverBlte.verifyEncodedRange(
                        raw.inputStream(), raw.size.toLong(), independentEkey(raw),
                        raw.size.toLong(), md5Hex(raw),
                    ),
                )
                // A wrong EKey rejects an otherwise valid plain range.
                assertThrows(IllegalArgumentException::class.java) {
                    ForeverBlte.verifyEncodedRange(
                        raw.inputStream(), raw.size.toLong(), "0".repeat(32),
                        raw.size.toLong(), md5Hex(raw),
                    )
                }
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun fileVsRangeZeroHeaderRawAndZlib() {
        val raw = "zero-header".toByteArray()
        for (encoded in listOf(zeroHeader(raw, 'N'), zeroHeader(raw, 'Z'))) {
            val file = fixture(encoded)
            try {
                ForeverBlte.verify(file, raw.size.toLong(), md5Hex(raw))
                assertEquals(
                    "decoded",
                    ForeverBlte.verifyEncodedRange(
                        encoded.inputStream(), encoded.size.toLong(), independentEkey(encoded),
                        raw.size.toLong(), md5Hex(raw),
                    ),
                )
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun fileVsRangeChunkedPlainAndEncryptedPassthrough() {
        val raw = "chunked-payload".toByteArray()
        val chunkedN = chunked(raw, 'N')
        // A chunked EKey pins the header/table, never the whole object.
        assertFalse(independentEkey(chunkedN) == md5Hex(chunkedN))
        val fileN = fixture(chunkedN)
        try {
            assertEquals("decoded", ForeverBlte.verifyEncodedObject(fileN, raw.size.toLong(), md5Hex(raw)))
            assertEquals(
                "decoded",
                ForeverBlte.verifyEncodedRange(
                    chunkedN.inputStream(), chunkedN.size.toLong(), independentEkey(chunkedN),
                    raw.size.toLong(), md5Hex(raw),
                ),
            )
        } finally {
            fileN.delete()
        }

        val declared = "encrypted-declared".toByteArray()
        val payload = byteArrayOf('E'.code.toByte(), 7, 8, 9)
        val chunkedE = chunkedE(declared.size, payload)
        val ekeyE = independentEkey(chunkedE)
        val fileE = fixture(chunkedE)
        try {
            assertEquals("encrypted", ForeverBlte.verifyEncodedObject(fileE, declared.size.toLong(), "0".repeat(32)))
            assertEquals(
                "encrypted",
                ForeverBlte.verifyEncodedRange(
                    chunkedE.inputStream(), chunkedE.size.toLong(), ekeyE,
                    declared.size.toLong(), "0".repeat(32),
                ),
            )
            // Strict file verification still rejects E frames today.
            val unsupported = assertThrows(IllegalArgumentException::class.java) {
                ForeverBlte.verify(fileE, declared.size.toLong(), "0".repeat(32))
            }
            assertTrue(unsupported.message.orEmpty().contains("unsupported BLTE mode"))
        } finally {
            fileE.delete()
        }
    }

    @Test
    fun extendedTableRejectedByFileAndRange() {
        val raw = "extended-chunk".toByteArray()
        val payload = byteArrayOf('Z'.code.toByte()) + zlib(raw)
        val header = u32(52) + byteArrayOf(0x10, 0, 0, 1) +
            u32(payload.size) + u32(raw.size) + md5Bytes(payload) + md5Bytes(raw)
        val encoded = byteArrayOf(0x42, 0x4c, 0x54, 0x45) + header + payload
        val file = fixture(encoded)
        try {
            // The 0x10 per-frame decoded checksum stays unverifiable with keys,
            // even for an all-Z table: both paths reject, exactly as today.
            assertThrows(IllegalArgumentException::class.java) {
                ForeverBlte.verifyEncodedObject(file, raw.size.toLong(), md5Hex(raw))
            }
            assertThrows(IllegalArgumentException::class.java) {
                ForeverBlte.verifyEncodedRange(
                    encoded.inputStream(), encoded.size.toLong(), independentEkey(encoded),
                    raw.size.toLong(), md5Hex(raw),
                )
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun rangeAndFileRejectCorruptionTruncationTrailingKeyMismatchAndAbsurdLimits() {
        val raw = "corrupt-target".toByteArray()
        val good = chunked(raw, 'N')
        val ekey = independentEkey(good)
        val ckey = md5Hex(raw)

        // A damaged payload keeps the chunk-table EKey but both paths reject.
        val bad = good.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() }
        assertEquals("chunk-table EKey is unchanged by payload damage", ekey, independentEkey(bad))
        val badFile = fixture(bad)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ForeverBlte.verifyEncodedObject(badFile, raw.size.toLong(), ckey)
            }
        } finally {
            badFile.delete()
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverBlte.verifyEncodedRange(bad.inputStream(), bad.size.toLong(), ekey, raw.size.toLong(), ckey)
        }

        // A short window throws instead of masking truncation as clean EOF.
        val short = good.copyOfRange(0, good.size - 1)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverBlte.verifyEncodedRange(short.inputStream(), good.size.toLong(), ekey, raw.size.toLong(), ckey)
        }
        val shortFile = fixture(short)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ForeverBlte.verifyEncodedObject(shortFile, raw.size.toLong(), ckey)
            }
        } finally {
            shortFile.delete()
        }

        // A trailing byte inside the window breaks the chunk-total arithmetic.
        val trailing = good + byteArrayOf(0)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverBlte.verifyEncodedRange(trailing.inputStream(), trailing.size.toLong(), ekey, raw.size.toLong(), ckey)
        }
        val trailingFile = fixture(trailing)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ForeverBlte.verifyEncodedObject(trailingFile, raw.size.toLong(), ckey)
            }
        } finally {
            trailingFile.delete()
        }

        // Absurd headers and descriptor counts reject on tiny inputs.
        for (tiny in listOf(
            byteArrayOf(0x42, 0x4c, 0x54, 0x45) + u32(8) + byteArrayOf(0x0f, 0, 0, 1),
            byteArrayOf(0x42, 0x4c, 0x54, 0x45) + u32(99) + byteArrayOf(0x0f, 0, 0, 1),
            byteArrayOf(0x42, 0x4c, 0x54, 0x45) + u32(12 + 8193 * 24) + byteArrayOf(0x0f, 0x00, 0x20, 0x01),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                ForeverBlte.verifyEncodedRange(tiny.inputStream(), tiny.size.toLong(), "0".repeat(32), 0L, "0".repeat(32))
            }
        }
    }

    @Test
    fun rangeStreamContractSingleByteNoOverreadCancelWithoutClose() {
        val raw = "window-probe".toByteArray()
        val encoded = chunked(raw, 'Z')
        val ekey = independentEkey(encoded)
        val ckey = md5Hex(raw)

        // Single-byte delivery digests exactly like bulk delivery.
        assertEquals(
            "decoded",
            ForeverBlte.verifyEncodedRange(SingleByteStream(encoded), encoded.size.toLong(), ekey, raw.size.toLong(), ckey),
        )

        // The window never reads past N: the sentinel stays intact and the
        // probe records no past-window read.
        val sentinel = byteArrayOf(9, 8, 7)
        val probe = ProbeStream(encoded + sentinel, encoded.size)
        assertEquals(
            "decoded",
            ForeverBlte.verifyEncodedRange(probe, encoded.size.toLong(), ekey, raw.size.toLong(), ckey),
        )
        assertEquals(encoded.size, probe.maxRead)
        assertEquals(0, probe.pastWindowReads)
        assertArrayEquals(sentinel, probe.full.copyOfRange(encoded.size, encoded.size + sentinel.size))

        // A stream that refuses skip still verifies: the parser never needs it.
        assertEquals(
            "decoded",
            ForeverBlte.verifyEncodedRange(ThrowOnSkipStream(encoded), encoded.size.toLong(), ekey, raw.size.toLong(), ckey),
        )

        // Cancellation propagates without closing the caller's stream.
        val tracked = CloseTrackingStream(encoded)
        assertThrows(CancellationException::class.java) {
            ForeverBlte.verifyEncodedRange(tracked, encoded.size.toLong(), ekey, raw.size.toLong(), ckey, cancelled = { true })
        }
        assertFalse(tracked.closed)
        val successTracked = CloseTrackingStream(encoded)
        assertEquals(
            "decoded",
            ForeverBlte.verifyEncodedRange(successTracked, encoded.size.toLong(), ekey, raw.size.toLong(), ckey),
        )
        assertFalse(successTracked.closed)
    }

    @Test
    fun fragmentedMaxOneByteDeliveryHashesExactlyLikeBulk() {
        // Bytes 5..8 of a plain object arrive as single bytes after the
        // 4-byte whole-object decision: they must still reach the EKey digest.
        val plain = ByteArray(20) { (it * 7 + 3).toByte() }
        assertFalse(plain.copyOfRange(0, 4).contentEquals(byteArrayOf(0x42, 0x4c, 0x54, 0x45)))
        val plainFile = fixture(plain)
        try {
            ForeverBlte.verify(plainFile, plain.size.toLong(), md5Hex(plain))
            assertEquals(
                "decoded",
                ForeverBlte.verifyEncodedRange(
                    MaxOneByteStream(plain), plain.size.toLong(), independentEkey(plain),
                    plain.size.toLong(), md5Hex(plain),
                ),
            )
        } finally {
            plainFile.delete()
        }

        val raw = "fragmented-chunk".toByteArray()
        for (encoded in listOf(chunked(raw, 'N'), chunked(raw, 'Z'))) {
            val file = fixture(encoded)
            try {
                ForeverBlte.verifyEncodedObject(file, raw.size.toLong(), md5Hex(raw))
                assertEquals(
                    "decoded",
                    ForeverBlte.verifyEncodedRange(
                        MaxOneByteStream(encoded), encoded.size.toLong(), independentEkey(encoded),
                        raw.size.toLong(), md5Hex(raw),
                    ),
                )
            } finally {
                file.delete()
            }
        }

        // Longer than one 64KiB buffered fill: multiple fills, same digest.
        val long = ByteArray(100_003) { (it * 7 + 3).toByte() }
        val longFile = fixture(long)
        try {
            ForeverBlte.verify(longFile, long.size.toLong(), md5Hex(long))
            assertEquals(
                "decoded",
                ForeverBlte.verifyEncodedRange(
                    MaxOneByteStream(long), long.size.toLong(), independentEkey(long),
                    long.size.toLong(), md5Hex(long),
                ),
            )
        } finally {
            longFile.delete()
        }
    }

    @Test
    fun plainWithUndersizedDeclaredSizeWritesNothing() {
        val raw = "undersized-plain".toByteArray()
        val file = fixture(raw)
        val output = File.createTempFile("blte-range-out-", ".bin")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ForeverBlte.decode(file, output, 2L, md5Hex(raw))
            }
            assertEquals(0L, output.length())
            // The windowed path rejects the same undersized declaration.
            assertThrows(IllegalArgumentException::class.java) {
                ForeverBlte.verifyEncodedRange(
                    raw.inputStream(), raw.size.toLong(), independentEkey(raw),
                    2L, md5Hex(raw),
                )
            }
        } finally {
            file.delete()
            output.delete()
        }
    }

    private class MaxOneByteStream(bytes: ByteArray) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        override fun read(): Int = inner.read()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            return inner.read(buffer, offset, 1)
        }
    }

    private class SingleByteStream(bytes: ByteArray) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        override fun read(): Int = inner.read()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            return inner.read(buffer, offset, 1)
        }
    }

    private class ProbeStream(val full: ByteArray, private val window: Int) : InputStream() {
        var maxRead = 0
        var pastWindowReads = 0
        private var pos = 0
        override fun read(): Int {
            if (pos >= window) {
                pastWindowReads++
                return -1
            }
            maxRead = maxOf(maxRead, pos + 1)
            return full[pos++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (pos >= window) {
                pastWindowReads++
                return -1
            }
            val n = minOf(length, window - pos)
            full.copyInto(buffer, offset, pos, pos + n)
            pos += n
            maxRead = maxOf(maxRead, pos)
            return n
        }
    }

    private class ThrowOnSkipStream(bytes: ByteArray) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        override fun read(): Int = inner.read()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner.read(buffer, offset, length)
        override fun skip(n: Long): Long = throw UnsupportedOperationException("skip must not bypass the range digest")
    }

    private class CloseTrackingStream(bytes: ByteArray) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        var closed = false
        override fun read(): Int = inner.read()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner.read(buffer, offset, length)
        override fun close() {
            closed = true
        }
    }

    private fun fixture(bytes: ByteArray): File = File.createTempFile("blte-range-", ".bin").also { it.writeBytes(bytes) }
    private fun u32(value: Int): ByteArray = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    private fun u32(value: Long): ByteArray = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    private fun md5Hex(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun md5Bytes(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)
    private fun zlib(input: ByteArray): ByteArray = Deflater().let { deflater ->
        deflater.setInput(input)
        deflater.finish()
        val output = ByteArray(4096)
        val size = deflater.deflate(output)
        deflater.end()
        output.copyOf(size)
    }

    /** Independent TACT EKey rule: whole-object MD5 when headerless/plain, header MD5 when chunked. */
    private fun independentEkey(encoded: ByteArray): String {
        val magic = byteArrayOf(0x42, 0x4c, 0x54, 0x45)
        if (encoded.size < 4 || !encoded.copyOfRange(0, 4).contentEquals(magic)) return md5Hex(encoded)
        require(encoded.size >= 8) { "test fixture needs a complete BLTE prefix" }
        val headerSize = encoded.copyOfRange(4, 8).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }
        if (headerSize == 0L) return md5Hex(encoded)
        require(headerSize <= encoded.size) { "test fixture header exceeds its bytes" }
        return md5Hex(encoded.copyOfRange(0, headerSize.toInt()))
    }

    private fun zeroHeader(decoded: ByteArray, mode: Char): ByteArray =
        byteArrayOf(0x42, 0x4c, 0x54, 0x45) + u32(0) + byteArrayOf(mode.code.toByte()) +
            if (mode == 'Z') zlib(decoded) else decoded

    private fun chunked(decoded: ByteArray, mode: Char): ByteArray {
        val payload = byteArrayOf(mode.code.toByte()) + if (mode == 'Z') zlib(decoded) else decoded
        val header = u32(12 + 24) + byteArrayOf(0x0f, 0, 0, 1) +
            u32(payload.size) + u32(decoded.size) + md5Bytes(payload)
        return byteArrayOf(0x42, 0x4c, 0x54, 0x45) + header + payload
    }

    private fun chunkedE(decodedSize: Int, payload: ByteArray): ByteArray {
        val header = u32(12 + 24) + byteArrayOf(0x0f, 0, 0, 1) +
            u32(payload.size) + u32(decodedSize) + md5Bytes(payload)
        return byteArrayOf(0x42, 0x4c, 0x54, 0x45) + header + payload
    }
}
