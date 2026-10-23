package app.gamenative

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeverClientUpdateTransferTest {
    @Test
    fun downloadsDirectZeroHeaderObjectAndReusesOnlyVerifiedFinalOutput() {
        Fixture().use { fixture ->
            val objectData = objectData()
            val progress = mutableListOf<Long>()
            var requests = 0
            val transfer = ForeverClientUpdateTransfer { url ->
                requests++
                assertEquals(objectData.directUrl, url)
                FakeConnection(HttpURLConnection.HTTP_OK, objectData.bytes)
            }

            val result = transfer.fetch(objectData.record, fixture.workDir, { false }, progress::add)

            assertEquals(1, requests)
            assertEquals(objectData.bytes.toList(), result.readBytes().toList())
            assertTrue(progress.isNotEmpty())
            assertEquals(objectData.bytes.size.toLong(), progress.last())
            assertFalse(File(fixture.workDir, "${objectData.record.ekey}.part").exists())
            val reused = transfer.fetch(objectData.record, fixture.workDir, { false }, {})
            assertEquals(result.canonicalFile, reused.canonicalFile)
            assertEquals(1, requests)
        }
    }

    @Test
    fun truncatedResponseIsRetriedWithinTheSameCallFromASafeOffset() {
        Fixture().use { fixture ->
            val item = objectData()
            val part = File(fixture.workDir, "${item.record.ekey}.part")
            part.writeBytes(item.bytes.copyOfRange(0, 9))
            var calls = 0
            val seen = mutableListOf<FakeConnection>()
            val transfer = ForeverClientUpdateTransfer { url ->
                assertEquals(item.directUrl, url)
                calls++
                val start = if (calls == 1) 9 else 14
                // First response declares the full remainder but goes quiet
                // after 5 bytes; the retry resumes from a re-statted offset.
                val body = if (calls == 1) {
                    item.bytes.copyOfRange(start, start + 5)
                } else {
                    item.bytes.copyOfRange(start, item.bytes.size)
                }
                FakeConnection(
                    HttpURLConnection.HTTP_PARTIAL,
                    body,
                    contentRange = "bytes $start-${item.bytes.size - 1}/${item.bytes.size}",
                    declaredLength = (item.bytes.size - start).toLong(),
                ).also { seen += it }
            }
            val result = transfer.fetch(item.record, fixture.workDir, { false }) { }
            assertEquals(2, calls)
            assertEquals(
                listOf("bytes=9-${item.bytes.size - 1}", "bytes=14-${item.bytes.size - 1}"),
                seen.map { it.requestedRange },
            )
            assertEquals(item.bytes.toList(), result.readBytes().toList())
            assertFalse(part.exists())
        }
    }

    @Test
    fun readFailureAfterTheLastByteStillPublishesTheVerifiedObject() {
        Fixture().use { fixture ->
            val item = objectData()
            var calls = 0
            val transfer = ForeverClientUpdateTransfer {
                calls++
                FakeConnection(HttpURLConnection.HTTP_OK, item.bytes, failAfterBody = true)
            }
            val result = transfer.fetch(item.record, fixture.workDir, { false }) { }
            assertEquals(1, calls)
            assertEquals(item.bytes.toList(), result.readBytes().toList())
        }
    }

    @Test
    fun cancellationLeavesDurablePartialAndTheNextCallResumesIt() {
        Fixture().use { fixture ->
            val decoded = ByteArray(200_000) { (it * 31).toByte() }
            val raw = byteArrayOf('B'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte(), 0, 0, 0, 0, 'N'.code.toByte()) + decoded
            val ekey = md5(raw)
            val record = ForeverClientUpdateCatalog.ObjectRecord(
                ekey, md5(decoded), raw.size.toLong(), decoded.size.toLong(), "direct", ekey, 0, raw.size.toLong(),
            )
            val part = File(fixture.workDir, "$ekey.part")
            var cancelled = false
            var requests = 0
            var latest: FakeConnection? = null
            val transfer = ForeverClientUpdateTransfer { url ->
                assertEquals("https://us.cdn.blizzard.com/tpr/wow/data/${ekey.take(2)}/${ekey.substring(2, 4)}/$ekey", url)
                requests++
                if (requests == 1) {
                    FakeConnection(HttpURLConnection.HTTP_OK, raw).also { latest = it }
                } else {
                    val start = part.length()
                    FakeConnection(
                        HttpURLConnection.HTTP_PARTIAL,
                        raw.copyOfRange(start.toInt(), raw.size),
                        contentRange = "bytes $start-${raw.size - 1}/${raw.size}",
                    ).also { latest = it }
                }
            }

            assertThrows(java.util.concurrent.CancellationException::class.java) {
                transfer.fetch(record, fixture.workDir, { cancelled }) { received -> if (received > 0) cancelled = true }
            }
            assertTrue(part.length() in 1 until record.encodedSize)
            assertEquals(null, latest!!.requestedRange)

            cancelled = false
            val resumeAt = part.length()
            val completed = transfer.fetch(record, fixture.workDir, { false }) { }
            assertEquals("bytes=$resumeAt-${raw.size - 1}", latest!!.requestedRange)
            assertEquals(raw.toList(), completed.readBytes().toList())
            assertEquals(2, requests)
        }
    }

    @Test
    fun archiveRangeAlwaysStartsAtPinnedOffsetAndValidatesReturnedInterval() {
        Fixture().use { fixture ->
            val item = objectData()
            val offset = 50_000L
            val end = offset + item.record.encodedSize - 1
            val archiveHash = "ab".repeat(16)
            val rangeRecord = item.record.copy(
                sourceKind = "range",
                sourceHash = archiveHash,
                sourceOffset = offset,
                sourceLength = item.record.encodedSize,
            )
            val expectedUrl = "https://us.cdn.blizzard.com/tpr/wow/data/ab/ab/$archiveHash"
            var connection: FakeConnection? = null
            val transfer = ForeverClientUpdateTransfer { url ->
                assertEquals(expectedUrl, url)
                FakeConnection(
                    HttpURLConnection.HTTP_PARTIAL,
                    item.bytes,
                    contentRange = "bytes $offset-$end/${end + 4096}",
                ).also { connection = it }
            }

            val result = transfer.fetch(rangeRecord, fixture.workDir, { false }) { }

            assertEquals("bytes=$offset-$end", connection!!.requestedRange)
            assertEquals(item.bytes.toList(), result.readBytes().toList())
        }
    }

    @Test
    fun rejectsWrongRangeAndDoesNotAcceptRedirectsOrOverwriteUnknownOutputs() {
        Fixture().use { fixture ->
            val item = objectData()
            val rangePart = File(fixture.workDir, "${item.record.ekey}.part").also { it.writeBytes(byteArrayOf(1, 2)) }
            val wrongRange = ForeverClientUpdateTransfer {
                FakeConnection(HttpURLConnection.HTTP_PARTIAL, byteArrayOf(3), contentRange = "bytes 0-0/1")
            }
            assertThrows(IllegalArgumentException::class.java) {
                wrongRange.fetch(item.record, fixture.workDir, { false }) { }
            }
            assertEquals(listOf(1.toByte(), 2.toByte()), rangePart.readBytes().toList())
            rangePart.delete()

            val redirect = ForeverClientUpdateTransfer {
                FakeConnection(HttpURLConnection.HTTP_MOVED_TEMP, ByteArray(0))
            }
            assertThrows(IllegalArgumentException::class.java) { redirect.fetch(item.record, fixture.workDir, { false }) { } }
            assertFalse(File(fixture.workDir, "${item.record.ekey}.blte").exists())

            val output = File(fixture.workDir, "${item.record.ekey}.blte").also { it.writeText("foreign") }
            val conflict = assertThrows(IllegalStateException::class.java) {
                ForeverClientUpdateTransfer { throw AssertionError("must not network when an output conflicts") }
                    .fetch(item.record, fixture.workDir, { false }) { }
            }
            assertTrue(conflict.message.orEmpty().contains("refusing to replace"))
            assertEquals("foreign", output.readText())
        }
    }

    @Test
    fun corruptedCompletePartIsRemovedAndAValidChunkedEkeyUsesOnlyItsHeaderTable() {
        Fixture().use { fixture ->
            val item = objectData()
            val corruptPart = File(fixture.workDir, "${item.record.ekey}.part")
            corruptPart.writeBytes(item.bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            var requests = 0
            val direct = ForeverClientUpdateTransfer {
                requests++
                FakeConnection(HttpURLConnection.HTTP_OK, item.bytes)
            }
            val result = direct.fetch(item.record, fixture.workDir, { false }) { }
            assertEquals(1, requests)
            assertEquals(item.bytes.toList(), result.readBytes().toList())

            val decoded = "chunked TACT EKey fixture".toByteArray()
            val raw = chunkedBlte(decoded)
            val headerLength = 12 + 24
            val expectedEkey = md5(raw.copyOfRange(0, headerLength))
            assertFalse(expectedEkey == md5(raw))
            val chunkedRecord = ForeverClientUpdateCatalog.ObjectRecord(
                expectedEkey,
                md5(decoded),
                raw.size.toLong(),
                decoded.size.toLong(),
                "direct",
                expectedEkey,
                0,
                raw.size.toLong(),
            )
            val chunked = ForeverClientUpdateTransfer {
                FakeConnection(HttpURLConnection.HTTP_OK, raw)
            }.fetch(chunkedRecord, fixture.workDir, { false }) { }
            assertEquals(raw.toList(), chunked.readBytes().toList())
        }
    }

    @Test
    fun mismatchedPayloadIsRetriedWithinTheSameCall() {
        Fixture().use { fixture ->
            val item = objectData()
            var calls = 0
            val transfer = ForeverClientUpdateTransfer {
                calls++
                val bad = item.bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                FakeConnection(HttpURLConnection.HTTP_OK, if (calls == 1) bad else item.bytes)
            }
            val part = File(fixture.workDir, "${item.record.ekey}.part")
            val result = transfer.fetch(item.record, fixture.workDir, { false }) { }
            assertFalse("a cryptographically invalid complete part must be removed before publish", part.exists())
            assertEquals(item.bytes.toList(), result.readBytes().toList())
            assertEquals(2, calls)
            // The verified output is reused without touching the network again.
            assertEquals(result.canonicalFile, transfer.fetch(item.record, fixture.workDir, { false }) { }.canonicalFile)
            assertEquals(2, calls)
        }
    }

    @Test
    fun sampledEkeyLogsBestEffortTimingsWithoutFailingPublish() {
        Fixture().use { fixture ->
            // Reuse the direct zero-header shape; scan small payloads with an
            // integer suffix until the real MD5 EKey lands in the ~1/256 sample
            // (about 256 candidates expected, hard bound below, no huge fixture).
            var sampled: EncodedObject? = null
            var counter = 0
            while (sampled == null) {
                require(counter < 100_000) { "no sampled EKey within the scan bound" }
                val decoded = "timings-sample-$counter".toByteArray()
                val bytes = byteArrayOf('B'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte(), 0, 0, 0, 0, 'N'.code.toByte()) + decoded
                val ekey = md5(bytes)
                if (ekey.endsWith("00", ignoreCase = true)) {
                    sampled = EncodedObject(
                        bytes,
                        ForeverClientUpdateCatalog.ObjectRecord(
                            ekey, md5(decoded), bytes.size.toLong(), decoded.size.toLong(), "direct", ekey, 0, bytes.size.toLong(),
                        ),
                        "https://us.cdn.blizzard.com/tpr/wow/data/${ekey.take(2)}/${ekey.substring(2, 4)}/$ekey",
                    )
                }
                counter++
            }
            val item = checkNotNull(sampled)
            val progress = mutableListOf<Long>()
            // A logging backend that throws only for the sampled timings line.
            // The fetch must still publish the verified object (best-effort log).
            val logger = Logger.getLogger("ForeverClientUpdate")
            var handlerInvoked = false
            val failingHandler = object : Handler() {
                override fun publish(record: LogRecord) {
                    if (record.message.startsWith("fetch phases")) {
                        handlerInvoked = true
                        throw IOException("synthetic diagnostics failure")
                    }
                }

                override fun flush() = Unit
                override fun close() = Unit
            }
            logger.addHandler(failingHandler)
            try {
                val transfer = ForeverClientUpdateTransfer { url ->
                    assertEquals(item.directUrl, url)
                    FakeConnection(HttpURLConnection.HTTP_OK, item.bytes)
                }
                val result = transfer.fetch(item.record, fixture.workDir, { false }, progress::add)
                assertEquals(item.bytes.toList(), result.readBytes().toList())
                assertTrue(progress.isNotEmpty())
                assertEquals(item.bytes.size.toLong(), progress.last())
                assertFalse(File(fixture.workDir, "${item.record.ekey}.part").exists())
            } finally {
                logger.removeHandler(failingHandler)
            }
            assertTrue("sampled fetch must reach the timings log", handlerInvoked)
        }
    }

    @Test
    fun validatesRecordAndFilesystemBoundsBeforeOpeningNetwork() {
        Fixture().use { fixture ->
            val item = objectData()
            var opened = false
            val transfer = ForeverClientUpdateTransfer { opened = true; throw AssertionError("network is unsupported for invalid record") }
            val tooLarge = item.record.copy(encodedSize = (1L shl 30) - 480 - 30 + 1, sourceLength = (1L shl 30) - 480 - 30 + 1)
            assertThrows(IllegalArgumentException::class.java) { transfer.fetch(tooLarge, fixture.workDir, { false }) { } }
            val decodedTooLarge = item.record.copy(decodedSize = 512L * 1024 * 1024 + 1)
            assertThrows(IllegalArgumentException::class.java) { transfer.fetch(decodedTooLarge, fixture.workDir, { false }) { } }
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                transfer.fetch(item.record, fixture.workDir, { true }) { }
            }
            assertFalse(opened)

            val outside = File(fixture.root, "outside").also { it.writeText("sentinel") }
            val link = File(fixture.workDir, "${item.record.ekey}.part")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertThrows(IllegalStateException::class.java) {
                ForeverClientUpdateTransfer { throw AssertionError("symlink must fail before network") }
                    .fetch(item.record, fixture.workDir, { false }) { }
            }
            assertEquals("sentinel", outside.readText())
        }
    }

    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("client-update-transfer-").toFile()
        val workDir = File(root, "stage").apply { mkdir() }
        override fun close() { root.deleteRecursively() }
    }

    private data class EncodedObject(
        val bytes: ByteArray,
        val record: ForeverClientUpdateCatalog.ObjectRecord,
        val directUrl: String,
    )

    private fun objectData(): EncodedObject {
        val decoded = "resumable-encoded-object".toByteArray()
        val bytes = byteArrayOf('B'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte(), 0, 0, 0, 0, 'N'.code.toByte()) + decoded
        val ekey = md5(bytes)
        return EncodedObject(
            bytes,
            ForeverClientUpdateCatalog.ObjectRecord(ekey, md5(decoded), bytes.size.toLong(), decoded.size.toLong(), "direct", ekey, 0, bytes.size.toLong()),
            "https://us.cdn.blizzard.com/tpr/wow/data/${ekey.take(2)}/${ekey.substring(2, 4)}/$ekey",
        )
    }

    private class FakeConnection(
        private val status: Int,
        private val body: ByteArray,
        private val contentRange: String? = null,
        private val declaredLength: Long = body.size.toLong(),
        private val failAfterBody: Boolean = false,
    ) : HttpURLConnection(URL("https://us.cdn.blizzard.com/")) {
        var requestedRange: String? = null
            private set

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getContentLengthLong(): Long = declaredLength
        override fun getInputStream(): InputStream {
            if (!failAfterBody) return ByteArrayInputStream(body)
            val delegate = ByteArrayInputStream(body)
            var failed = false
            return object : InputStream() {
                override fun read(): Int {
                    val value = delegate.read()
                    if (value < 0 && !failed) {
                        failed = true
                        throw IOException("synthetic post-body failure")
                    }
                    return value
                }

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    val count = delegate.read(bytes, offset, length)
                    if (count < 0 && !failed) {
                        failed = true
                        throw IOException("synthetic post-body failure")
                    }
                    return count
                }
            }
        }
        override fun getHeaderField(name: String): String? = when {
            name.equals("Content-Range", true) -> contentRange
            name.equals("Content-Encoding", true) -> null
            else -> null
        }

        override fun setRequestProperty(key: String, value: String) {
            super.setRequestProperty(key, value)
            if (key.equals("Range", true)) requestedRange = value
        }
    }

    companion object {
        private fun chunkedBlte(decoded: ByteArray): ByteArray {
            val modeAndPayload = byteArrayOf('N'.code.toByte()) + decoded
            val header = ByteArray(12 + 24)
            byteArrayOf('B'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte()).copyInto(header)
            putU32be(header, 4, 36)
            header[8] = 0x0f
            header[11] = 1
            putU32be(header, 12, modeAndPayload.size.toLong())
            putU32be(header, 16, decoded.size.toLong())
            md5Bytes(modeAndPayload).copyInto(header, 20)
            return header + modeAndPayload
        }

        private fun putU32be(bytes: ByteArray, offset: Int, value: Long) {
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }

        private fun md5(bytes: ByteArray): String = md5Bytes(bytes).joinToString("") { "%02x".format(it) }
        private fun md5Bytes(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)
    }
}
