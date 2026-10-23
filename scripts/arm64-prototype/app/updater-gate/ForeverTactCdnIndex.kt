package app.gamenative

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException

/** Bounded streaming reader for the CDN archive and standalone file indexes. */
internal object ForeverTactCdnIndex {
    private const val FOOTER_SIZE = 28L
    private const val KEY_BYTES = 16
    private const val SIZE_BYTES = 4
    private const val FOOTER_HASH_BYTES = 8
    private const val MAX_INDEX_BYTES = 16L * 1024 * 1024
    private const val MAX_TOC_BYTES = 8L * 1024 * 1024
    private const val MAX_ENTRIES = 2_000_000L
    private const val MAX_PAGE_BYTES = 255L * 1024

    /**
     * Parse one CDN index without retaining its entries. Each page is verified before its
     * records are passed to [onRecord]. A failure on a later page can therefore follow earlier
     * callbacks; callers must keep those callbacks inside an abortable transaction.
     *
     * `indexHash` is the full 16-byte CDN index object name. It is provenance for emitted
     * records, not a digest to compare with the index file bytes (the CDN index filename is not
     * the MD5 of its serialized contents). EKeys are always retained as complete 16-byte keys.
     */
    fun parse(
        file: File,
        indexHash: String,
        fileIndexHash: String?,
        cancelled: () -> Boolean,
        onRecord: (ForeverTactMetadata.SourceRecord) -> Unit,
    ): ForeverTactMetadata.IndexStats {
        val canonicalIndexHash = normalizeHash(indexHash, "index hash")
        val canonicalFileIndexHash = fileIndexHash?.let { normalizeHash(it, "file-index hash") }
        val isFileIndex = canonicalIndexHash == canonicalFileIndexHash
        val expectedOffsetBytes = if (isFileIndex) 0 else 4
        checkCancelled(cancelled)

        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val fileLength = channel.size()
            invalidUnless(fileLength in FOOTER_SIZE..MAX_INDEX_BYTES, "index size outside bound")

            val footerOffset = fileLength - FOOTER_SIZE
            val footer = readFully(channel, footerOffset, FOOTER_SIZE.toInt())
            val footerFields = parseFooter(footer, expectedOffsetBytes)
            val layout = calculateLayout(footerFields, fileLength)
            verifyFooterChecksum(footer)

            val toc = readFully(channel, layout.pagesBytes, layout.tocBytes.toInt())
            verifyTocChecksum(toc, footer)

            val page = ByteArray(layout.pageBytes.toInt())
            var previousKey: ByteArray? = null
            var emitted = 0L
            for (pageIndex in 0 until layout.pageCount) {
                checkCancelled(cancelled)
                val pageOffset = multiplyExact(pageIndex.toLong(), layout.pageBytes, "page offset")
                readFully(channel, pageOffset, page)
                verifyPageChecksum(page, toc, pageIndex, layout)

                val entriesOnPage = minOf(
                    layout.entriesPerPage,
                    footerFields.entryCount - pageIndex.toLong() * layout.entriesPerPage,
                ).toInt()
                verifyAdvertisedLastKey(page, toc, pageIndex, entriesOnPage, layout)
                val lastKeyOnPage = validatePageRecords(
                    page = page,
                    entriesOnPage = entriesOnPage,
                    layout = layout,
                    previousKey = previousKey,
                )

                // No list of records is built: validate the page first, then stream its records.
                for (entryIndex in 0 until entriesOnPage) {
                    val entryOffset = entryIndex * layout.entryBytes
                    if (isZeroKey(page, entryOffset)) continue
                    val key = page.copyOfRange(entryOffset, entryOffset + KEY_BYTES)
                    val encodedSize = readU32BigEndian(page, entryOffset + KEY_BYTES)
                    val sourceOffset = if (isFileIndex) {
                        0L
                    } else {
                        readU32BigEndian(page, entryOffset + KEY_BYTES + SIZE_BYTES)
                    }
                    onRecord(
                        ForeverTactMetadata.SourceRecord(
                            encodingKey = key.toLowerHex(),
                            encodedSize = encodedSize,
                            sourceKind = if (isFileIndex) "direct" else "range",
                            sourceHash = canonicalIndexHash,
                            sourceOffset = sourceOffset,
                        ),
                    )
                    emitted = Math.addExact(emitted, 1L)
                }
                previousKey = lastKeyOnPage ?: previousKey
            }
            checkCancelled(cancelled)
            return ForeverTactMetadata.IndexStats(
                entries = emitted,
                sourceKind = if (isFileIndex) "direct" else "range",
            )
        }
    }

    private data class Footer(
        val pageBytes: Long,
        val offsetBytes: Int,
        val entryCount: Long,
        val pageHashBytes: Int,
    )

    private data class Layout(
        val pageBytes: Long,
        val entryBytes: Int,
        val entriesPerPage: Long,
        val pageCount: Int,
        val pagesBytes: Long,
        val tocBytes: Long,
        val tocKeysBytes: Long,
    )

    private fun parseFooter(raw: ByteArray, expectedOffsetBytes: Int): Footer {
        val version = raw[8].toInt() and 0xff
        val pageSizeKb = raw[11].toInt() and 0xff
        val offsetBytes = raw[12].toInt() and 0xff
        val sizeBytes = raw[13].toInt() and 0xff
        val keyBytes = raw[14].toInt() and 0xff
        val pageHashBytes = raw[15].toInt() and 0xff
        val entryCount = readU32LittleEndian(raw, 16)

        invalidUnless(version <= 1, "unsupported CDN index footer version")
        invalidUnless(keyBytes == KEY_BYTES && sizeBytes == SIZE_BYTES, "unsupported CDN index key/size width")
        invalidUnless(offsetBytes == expectedOffsetBytes, "CDN index offset width does not match its role")
        invalidUnless(offsetBytes == 0 || offsetBytes == 4, "unsupported CDN index offset width")
        invalidUnless(pageHashBytes == FOOTER_HASH_BYTES, "unsupported CDN index checksum width")
        invalidUnless(pageSizeKb > 0, "invalid CDN index page size")
        invalidUnless(entryCount in 1..MAX_ENTRIES, "CDN index entry count outside bound")

        val pageBytes = multiplyExact(pageSizeKb.toLong(), 1024L, "page size")
        invalidUnless(pageBytes <= MAX_PAGE_BYTES, "CDN index page size outside bound")
        return Footer(pageBytes, offsetBytes, entryCount, pageHashBytes)
    }

    private fun calculateLayout(footer: Footer, fileLength: Long): Layout {
        val entryBytes = KEY_BYTES + SIZE_BYTES + footer.offsetBytes
        val entriesPerPage = footer.pageBytes / entryBytes
        invalidUnless(entriesPerPage > 0, "invalid CDN index page geometry")
        val pageCountLong = addExact(
            footer.entryCount,
            entriesPerPage - 1,
            "page count",
        ) / entriesPerPage
        invalidUnless(pageCountLong in 1..Int.MAX_VALUE.toLong(), "CDN index page count outside bound")

        val pagesBytes = multiplyExact(pageCountLong, footer.pageBytes, "pages length")
        val tocKeysBytes = multiplyExact(pageCountLong, KEY_BYTES.toLong(), "TOC key length")
        val tocHashesBytes = multiplyExact(pageCountLong, footer.pageHashBytes.toLong(), "TOC checksum length")
        val tocBytes = addExact(tocKeysBytes, tocHashesBytes, "TOC length")
        invalidUnless(tocBytes <= MAX_TOC_BYTES, "CDN index TOC outside bound")
        val expectedLength = addExact(
            addExact(pagesBytes, tocBytes, "index body length"),
            FOOTER_SIZE,
            "index length",
        )
        invalidUnless(expectedLength == fileLength, "CDN index length disagrees with footer geometry")
        return Layout(
            pageBytes = footer.pageBytes,
            entryBytes = entryBytes,
            entriesPerPage = entriesPerPage,
            pageCount = pageCountLong.toInt(),
            pagesBytes = pagesBytes,
            tocBytes = tocBytes,
            tocKeysBytes = tocKeysBytes,
        )
    }

    private fun verifyFooterChecksum(footer: ByteArray) {
        // The checksum payload is version through entry_count, followed by eight zero bytes.
        val payload = ByteArray(20)
        footer.copyInto(payload, destinationOffset = 0, startIndex = 8, endIndex = 20)
        val actual = md5(payload).copyOfRange(0, FOOTER_HASH_BYTES)
        invalidUnless(actual.contentEquals(footer.copyOfRange(20, 28)), "CDN index footer checksum mismatch")
    }

    private fun verifyTocChecksum(toc: ByteArray, footer: ByteArray) {
        val actual = md5(toc).copyOfRange(0, FOOTER_HASH_BYTES)
        invalidUnless(actual.contentEquals(footer.copyOfRange(0, 8)), "CDN index TOC checksum mismatch")
    }

    private fun verifyPageChecksum(page: ByteArray, toc: ByteArray, pageIndex: Int, layout: Layout) {
        val pageChecksumStart = layout.tocKeysBytes.toInt() + pageIndex * FOOTER_HASH_BYTES
        val expected = toc.copyOfRange(pageChecksumStart, pageChecksumStart + FOOTER_HASH_BYTES)
        val actual = md5(page).copyOfRange(0, FOOTER_HASH_BYTES)
        invalidUnless(actual.contentEquals(expected), "CDN index page checksum mismatch")
    }

    private fun verifyAdvertisedLastKey(
        page: ByteArray,
        toc: ByteArray,
        pageIndex: Int,
        entriesOnPage: Int,
        layout: Layout,
    ) {
        val lastEntryOffset = (entriesOnPage - 1) * layout.entryBytes
        val actualKey = page.copyOfRange(lastEntryOffset, lastEntryOffset + KEY_BYTES)
        val advertisedOffset = pageIndex * KEY_BYTES
        val advertised = toc.copyOfRange(advertisedOffset, advertisedOffset + KEY_BYTES)
        invalidUnless(actualKey.contentEquals(advertised), "CDN index advertised page key mismatch")
    }

    private fun validatePageRecords(
        page: ByteArray,
        entriesOnPage: Int,
        layout: Layout,
        previousKey: ByteArray?,
    ): ByteArray? {
        var lastNonzeroKey = previousKey
        for (entryIndex in 0 until entriesOnPage) {
            val entryOffset = entryIndex * layout.entryBytes
            if (isZeroKey(page, entryOffset)) {
                invalidUnless(
                    isZeroRecord(page, entryOffset, layout.entryBytes),
                    "zero CDN index key has nonzero record fields",
                )
                continue
            }

            val key = page.copyOfRange(entryOffset, entryOffset + KEY_BYTES)
            invalidUnless(
                lastNonzeroKey == null || compareUnsigned(lastNonzeroKey, key) < 0,
                "CDN index EKeys are not strictly sorted",
            )
            val size = readU32BigEndian(page, entryOffset + KEY_BYTES)
            invalidUnless(size > 0, "CDN index object has zero encoded size")
            if (layout.entryBytes == KEY_BYTES + SIZE_BYTES + 4) {
                // Both fields are uint32; keep their range arithmetic in checked Long space.
                val offset = readU32BigEndian(page, entryOffset + KEY_BYTES + SIZE_BYTES)
                addExact(offset, size, "source range")
            }
            lastNonzeroKey = key
        }
        return lastNonzeroKey?.takeIf { previousKey == null || !it.contentEquals(previousKey) }
    }

    private fun isZeroKey(page: ByteArray, offset: Int): Boolean {
        for (index in 0 until KEY_BYTES) if (page[offset + index].toInt() != 0) return false
        return true
    }

    private fun isZeroRecord(page: ByteArray, offset: Int, recordBytes: Int): Boolean {
        for (index in 0 until recordBytes) if (page[offset + index].toInt() != 0) return false
        return true
    }

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        for (index in 0 until KEY_BYTES) {
            val difference = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return 0
    }

    private fun readU32BigEndian(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

    private fun readU32LittleEndian(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun readFully(channel: FileChannel, position: Long, length: Int): ByteArray {
        val result = ByteArray(length)
        readFully(channel, position, result)
        return result
    }

    private fun readFully(channel: FileChannel, position: Long, target: ByteArray) {
        val buffer = ByteBuffer.wrap(target)
        var cursor = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, cursor)
            if (read < 0) invalid("truncated CDN index")
            if (read == 0) continue
            cursor = addExact(cursor, read.toLong(), "read position")
        }
    }

    private fun md5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)

    private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
        val alphabet = "0123456789abcdef"
        for (byte in this@toLowerHex) {
            val value = byte.toInt() and 0xff
            append(alphabet[value ushr 4])
            append(alphabet[value and 0x0f])
        }
    }

    private fun normalizeHash(value: String, field: String): String {
        invalidUnless(value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }, "invalid $field")
        return value.lowercase(Locale.ROOT)
    }

    private fun multiplyExact(left: Long, right: Long, label: String): Long = try {
        Math.multiplyExact(left, right)
    } catch (_: ArithmeticException) {
        invalid("CDN index $label overflow")
    }

    private fun addExact(left: Long, right: Long, label: String): Long = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        invalid("CDN index $label overflow")
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw CancellationException("CDN index parsing cancelled")
    }

    private fun invalidUnless(condition: Boolean, message: String) {
        if (!condition) invalid(message)
    }

    private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)
}
