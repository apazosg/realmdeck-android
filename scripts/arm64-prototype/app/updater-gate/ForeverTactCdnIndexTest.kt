package app.gamenative

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeverTactCdnIndexTest {
    @Test
    fun fileIndexEmitsDirectSourcesWithCompleteKeysAndFilenameProvenance() {
        val indexHash = "A1".repeat(16)
        val expectedKeys = listOf(key(1), key(2))
        withIndex(fixture(expectedKeys.mapIndexed { number, ekey ->
            Record(ekey, 0xffff_ffffL - number, 0)
        }, offsetBytes = 0)) { file ->
            val records = mutableListOf<ForeverTactMetadata.SourceRecord>()
            val stats = ForeverTactCdnIndex.parse(
                file,
                indexHash,
                indexHash.lowercase(),
                { false },
                records::add,
            )

            assertEquals(2L, stats.entries)
            assertEquals("direct", stats.sourceKind)
            assertEquals(expectedKeys.map { it.toLowerHex() }, records.map { it.encodingKey })
            assertEquals(listOf(0xffff_ffffL, 0xffff_fffeL), records.map { it.encodedSize })
            assertTrue(records.all { it.sourceKind == "direct" && it.sourceOffset == 0L })
            assertTrue(records.all { it.sourceHash == indexHash.lowercase() })
        }
    }

    @Test
    fun archiveIndexStreamsMultiplePagesAndKeepsUnsignedRangeOffsets() {
        val entries = (1..171).map { number ->
            Record(key(number), 100L + number, if (number == 171) 0xffff_ffffL else number * 4096L)
        }
        val indexHash = "c2".repeat(16)
        withIndex(fixture(entries, offsetBytes = 4)) { file ->
            val records = mutableListOf<ForeverTactMetadata.SourceRecord>()
            val stats = ForeverTactCdnIndex.parse(file, indexHash, null, { false }, records::add)
            assertEquals(entries.size.toLong(), stats.entries)
            assertEquals("range", stats.sourceKind)
            assertEquals(entries.map { it.key.toLowerHex() }, records.map { it.encodingKey })
            assertEquals(0xffff_ffffL, records.last().sourceOffset)
            assertTrue(records.all { it.sourceKind == "range" && it.sourceHash == indexHash })
        }
    }

    @Test
    fun checksFooterTocPageChecksumsAndAdvertisedPageKeyBeforeEmittingPage() {
        val valid = fixture(listOf(Record(key(1), 9, 3)), offsetBytes = 4)

        val badFooter = valid.copyOf().apply { this[size - 28 + 9] = 1 }
        expectInvalid(badFooter, "footer checksum")

        val badTocChecksum = valid.copyOf().apply { this[size - 28] = (this[size - 28].toInt() xor 1).toByte() }
        expectInvalid(badTocChecksum, "TOC checksum")

        val badPageChecksum = valid.copyOf().apply { this[20] = (this[20].toInt() xor 1).toByte() }
        expectInvalid(badPageChecksum, "page checksum")

        val badPageKey = valid.copyOf().apply {
            val pagesBytes = PAGE_BYTES
            this[pagesBytes] = (this[pagesBytes].toInt() xor 1).toByte()
            rewriteTocChecksum(this)
        }
        expectInvalid(badPageKey, "advertised page key")
    }

    @Test
    fun rejectsTruncationMisalignmentAndUnboundedFooterCounts() {
        val valid = fixture(listOf(Record(key(1), 9, 3)), offsetBytes = 4)
        expectInvalid(valid.copyOf(valid.size - 1), "CDN index")
        expectInvalid(valid + byteArrayOf(0), "CDN index")

        val oversizedCount = valid.copyOf().apply {
            val footerAt = size - 28
            writeU32LittleEndian(this, footerAt + 16, 2_000_001L)
            rewriteFooterChecksum(this)
        }
        expectInvalid(oversizedCount, "entry count")
    }

    @Test
    fun rejectsUnsupportedLayoutsZeroSizesAndNonzeroZeroKeyRecords() {
        expectInvalid(fixture(listOf(Record(key(1), 9, 3)), offsetBytes = 6), "offset width")
        expectInvalid(fixture(listOf(Record(key(1), 0, 3)), offsetBytes = 4), "zero encoded size")
        expectInvalid(fixture(listOf(Record(ByteArray(16), 1, 3)), offsetBytes = 4), "zero CDN index key")
    }

    @Test
    fun rejectsDuplicateAndDescendingFullEKeys() {
        expectInvalid(
            fixture(listOf(Record(key(2), 9, 3), Record(key(1), 9, 4)), offsetBytes = 4),
            "strictly sorted",
        )
        expectInvalid(
            fixture(listOf(Record(key(1), 9, 3), Record(key(1), 9, 4)), offsetBytes = 4),
            "strictly sorted",
        )
    }

    @Test
    fun skipsOnlyEntirelyZeroDeclaredRecordsAndRejectsCancelledWork() {
        val fixture = fixture(
            listOf(Record(key(1), 9, 3), Record(ByteArray(16), 0, 0)),
            offsetBytes = 4,
        )
        withIndex(fixture) { file ->
            val records = mutableListOf<ForeverTactMetadata.SourceRecord>()
            val stats = ForeverTactCdnIndex.parse(file, "d4".repeat(16), null, { false }, records::add)
            assertEquals(1L, stats.entries)
            assertEquals(1, records.size)

            try {
                ForeverTactCdnIndex.parse(file, "d4".repeat(16), null, { true }) { records.add(it) }
                throw AssertionError("expected cancellation")
            } catch (_: CancellationException) {
                // Expected; no record callback is made before initial cancellation.
            }
        }
    }

    @Test
    fun aLaterBadPageCanFollowCallbacksFromEarlierVerifiedPages() {
        val entries = (1..171).map { Record(key(it), 10, it.toLong()) }
        val corruptedSecondPage = fixture(entries, offsetBytes = 4).apply {
            this[PAGE_BYTES] = (this[PAGE_BYTES].toInt() xor 1).toByte()
        }
        withIndex(corruptedSecondPage) { file ->
            var emitted = 0
            try {
                ForeverTactCdnIndex.parse(file, "d6".repeat(16), null, { false }) { emitted++ }
                throw AssertionError("expected second-page checksum rejection")
            } catch (failure: IllegalArgumentException) {
                assertTrue(failure.message.orEmpty().contains("page checksum"))
                assertEquals(170, emitted)
            }
        }
    }

    @Test
    fun rejectsIndexesAboveRawByteLimitBeforeReadingTheirBody() {
        val path = Files.createTempFile("forever-tact-index-limit-", ".index")
        try {
            RandomAccessFile(path.toFile(), "rw").use { it.setLength(16L * 1024 * 1024 + 1) }
            try {
                ForeverTactCdnIndex.parse(path.toFile(), "d5".repeat(16), null, { false }) { }
                throw AssertionError("expected raw index size rejection")
            } catch (failure: IllegalArgumentException) {
                assertTrue(failure.message.orEmpty().contains("size outside bound"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    /**
     * Optional read-only real-cache cross-check. Supply semicolon-delimited rows:
     * `hash|file|entry_count|size_bytes|sha256`, plus file-index hash from build 70425.
     */
    @Test
    fun optional70425AdmissionCacheSamplesMatchHostRows() {
        val samples = System.getProperty("wowforever.tactCdnIndexSamples") ?: return
        val fileIndexHash = System.getProperty("wowforever.tactFileIndexHash")
        for (sample in samples.split(';')) {
            val fields = sample.split('|')
            require(fields.size == 5) { "bad optional CDN index sample specification" }
            val hash = fields[0]
            val file = File(fields[1])
            val expectedEntries = fields[2].toLong()
            val expectedLength = fields[3].toLong()
            val expectedSha256 = fields[4]
            assertEquals(expectedLength, file.length())
            assertEquals(expectedSha256, file.sha256Hex())

            var emitted = 0L
            val stats = ForeverTactCdnIndex.parse(file, hash, fileIndexHash, { false }) { emitted++ }
            assertEquals(expectedEntries, stats.entries)
            assertEquals(if (hash.equals(fileIndexHash, ignoreCase = true)) "direct" else "range", stats.sourceKind)
            assertEquals(expectedEntries, emitted)
        }
    }

    private data class Record(val key: ByteArray, val size: Long, val offset: Long)

    private fun fixture(records: List<Record>, offsetBytes: Int): ByteArray {
        require(records.isNotEmpty())
        require(offsetBytes == 0 || offsetBytes == 4 || offsetBytes == 6)
        val entryBytes = 16 + 4 + offsetBytes
        val entriesPerPage = PAGE_BYTES / entryBytes
        val pageCount = (records.size + entriesPerPage - 1) / entriesPerPage
        val pages = ByteArray(pageCount * PAGE_BYTES)
        val tocKeys = ByteArray(pageCount * 16)
        val tocHashes = ByteArray(pageCount * HASH_BYTES)

        for (pageIndex in 0 until pageCount) {
            val firstRecord = pageIndex * entriesPerPage
            val recordsOnPage = minOf(entriesPerPage, records.size - firstRecord)
            var cursor = pageIndex * PAGE_BYTES
            repeat(recordsOnPage) { slot ->
                val record = records[firstRecord + slot]
                require(record.key.size == 16)
                record.key.copyInto(pages, cursor)
                cursor += 16
                writeU32BigEndian(pages, cursor, record.size)
                cursor += 4
                repeat(offsetBytes) { byteIndex ->
                    val shift = (offsetBytes - byteIndex - 1) * 8
                    pages[cursor + byteIndex] = (record.offset ushr shift).toByte()
                }
                cursor += offsetBytes
            }
            val lastRecord = records[firstRecord + recordsOnPage - 1]
            lastRecord.key.copyInto(tocKeys, pageIndex * 16)
            md5(pages.copyOfRange(pageIndex * PAGE_BYTES, (pageIndex + 1) * PAGE_BYTES))
                .copyInto(tocHashes, pageIndex * HASH_BYTES, 0, HASH_BYTES)
        }

        val toc = tocKeys + tocHashes
        val footer = ByteArray(28)
        md5(toc).copyInto(footer, 0, 0, 8)
        footer[8] = 1
        footer[11] = (PAGE_BYTES / 1024).toByte()
        footer[12] = offsetBytes.toByte()
        footer[13] = 4
        footer[14] = 16
        footer[15] = HASH_BYTES.toByte()
        writeU32LittleEndian(footer, 16, records.size.toLong())
        val raw = pages + toc + footer
        rewriteFooterChecksum(raw)
        return raw
    }

    private fun expectInvalid(raw: ByteArray, expectedMessage: String) {
        withIndex(raw) { file ->
            try {
                ForeverTactCdnIndex.parse(file, "e5".repeat(16), null, { false }) { }
                throw AssertionError("expected parser rejection: $expectedMessage")
            } catch (failure: IllegalArgumentException) {
                assertTrue("${failure.message}", failure.message.orEmpty().contains(expectedMessage, ignoreCase = true))
            }
        }
    }

    private fun withIndex(raw: ByteArray, block: (File) -> Unit) {
        val path = Files.createTempFile("forever-tact-index-", ".index")
        try {
            Files.write(path, raw)
            block(path.toFile())
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun key(number: Int): ByteArray = ByteArray(16).apply {
        this[12] = (number ushr 24).toByte()
        this[13] = (number ushr 16).toByte()
        this[14] = (number ushr 8).toByte()
        this[15] = number.toByte()
    }

    private fun File.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { stream ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toLowerHex()
    }

    private fun ByteArray.toLowerHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun rewriteTocChecksum(raw: ByteArray) {
        val footerAt = raw.size - 28
        val footer = raw.copyOfRange(footerAt, raw.size)
        val toc = raw.copyOfRange(PAGE_BYTES, footerAt)
        md5(toc).copyInto(footer, 0, 0, 8)
        footer.copyInto(raw, footerAt)
    }

    private fun rewriteFooterChecksum(raw: ByteArray) {
        val footerAt = raw.size - 28
        val payload = ByteArray(20)
        raw.copyInto(payload, 0, footerAt + 8, footerAt + 20)
        md5(payload).copyInto(raw, footerAt + 20, 0, 8)
    }

    private fun writeU32BigEndian(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) target[offset + index] = (value ushr (24 - index * 8)).toByte()
    }

    private fun writeU32LittleEndian(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) target[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun md5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)

    private companion object {
        const val PAGE_BYTES = 4096
        const val HASH_BYTES = 8
    }
}
