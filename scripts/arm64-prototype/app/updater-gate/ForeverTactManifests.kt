package app.gamenative

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException

/** Bounded readers for decoded TACT install, download, size, and encoding manifests. */
internal object ForeverTactManifests {
    private const val MAX_FILE_BYTES = 256L * 1024 * 1024
    private const val MAX_ENTRIES = 5_000_000L
    private const val MAX_TAGS = 4_096
    private const val MAX_TAG_NAME_BYTES = 4_096
    private const val MAX_PATH_BYTES = 4_096
    private const val MAX_LOOSE_FILES = 4_096
    private const val MAX_TAG_BITMAP_BYTES = 128L * 1024 * 1024
    private const val MAX_INSTALL_DIRECTORIES = 16_384
    private const val MAX_INSTALL_DIRECTORY_TEXT = 4L * 1024 * 1024
    private const val MAX_ENCODING_PAGE_BYTES = 1024 * 1024
    private const val MAX_ENCODING_PAGE_COUNT = 60_000
    private const val MAX_CONTENT_MAPPINGS = 20_000_000L
    private const val BUFFER_BYTES = 64 * 1024
    private val SELECTION_TAGS = setOf("Windows", "arm64", "enUS")

    /** The caller must have authenticated and decoded this file before supplying it. */
    fun install(
        file: File,
        cancelled: () -> Boolean,
        onRecord: (ForeverTactMetadata.InstallRecord) -> Unit,
    ): Long = withReader(file, cancelled) { reader ->
        val validatedCount = parseInstall(reader, cancelled, null)
        val emittedCount = parseInstall(reader, cancelled, onRecord)
        if (emittedCount != validatedCount) fail("install manifest changed between validation and emission")
        emittedCount
    }

    /** Reads tags at the tail first, then seeks back to emit only selected full EKeys. */
    fun download(
        file: File,
        cancelled: () -> Boolean,
        onRecord: (ForeverTactMetadata.DownloadRecord) -> Unit,
    ): ForeverTactMetadata.SelectionStats = withReader(file, cancelled) { reader ->
        // parseDownload validates the entry section, tag tail, and exact EOF
        // before its second entry pass starts invoking this callback.
        parseDownload(reader, cancelled, onRecord)
    }

    /** Size entries contain only nine-byte EKey prefixes; they are estimates, not object identities. */
    fun size(
        file: File,
        cancelled: () -> Boolean,
        onRecord: (ForeverTactMetadata.SizeRecord) -> Unit,
    ): ForeverTactMetadata.SelectionStats = withReader(file, cancelled) { reader ->
        // parseSize validates the total and exact EOF before emitting its second pass.
        parseSize(reader, cancelled, onRecord)
    }

    /**
     * Validates every encoding page and its index before exposing any mapping. Pages
     * are reread for callback emission, so neither CKey/EKey pages nor their mappings
     * are retained as whole-file collections.
     */
    fun encoding(
        file: File,
        cancelled: () -> Boolean,
        onContent: (ForeverTactMetadata.ContentMapping) -> Unit,
        onEncoding: (ForeverTactMetadata.EncodingRecord) -> Unit,
    ): ForeverTactMetadata.EncodingStats = withReader(file, cancelled) { reader ->
        val validated = parseEncoding(reader, cancelled, null, null)
        val emitted = parseEncoding(reader, cancelled, onContent, onEncoding)
        if (emitted != validated) fail("encoding manifest changed between validation and emission")
        validated
    }

    private data class TagMask(val tagType: Int, val bits: ByteArray)

    private data class EncodingLayout(
        val ckeyPageBytes: Int,
        val ekeyPageBytes: Int,
        val ckeyPageCount: Int,
        val ekeyPageCount: Int,
        val especCount: Int,
        val ckeyIndexOffset: Long,
        val ckeyPagesOffset: Long,
        val ekeyIndexOffset: Long,
        val ekeyPagesOffset: Long,
        val trailingBytes: Long,
    )

    private class InstallPathNormalizer {
        private val firstDirectoryCase = HashMap<String, String>()
        private var directoryTextBytes = 0L

        fun normalize(raw: String): String {
            validateSafePath(raw)
            val components = raw.replace('\\', '/').split('/')
            if (components.size == 1) return components.single()
            val canonical = ArrayList<String>(components.size)
            var key = ""
            components.forEachIndexed { index, component ->
                if (index == components.lastIndex) {
                    canonical += component
                } else {
                    if (key.isNotEmpty()) key += "/"
                    key += component.lowercase(Locale.ROOT)
                    var casing = firstDirectoryCase[key]
                    if (casing == null) {
                        if (firstDirectoryCase.size >= MAX_INSTALL_DIRECTORIES) {
                            fail("install manifest has too many directory prefixes")
                        }
                        directoryTextBytes = addExact(directoryTextBytes, key.length.toLong() + component.length)
                        if (directoryTextBytes > MAX_INSTALL_DIRECTORY_TEXT) {
                            fail("install directory-prefix storage exceeds its bound")
                        }
                        casing = component
                        firstDirectoryCase[key] = casing
                    }
                    canonical += casing
                }
            }
            return canonical.joinToString("/")
        }
    }

    private fun parseInstall(
        reader: ChannelReader,
        cancelled: () -> Boolean,
        onRecord: ((ForeverTactMetadata.InstallRecord) -> Unit)?,
    ): Long {
        reader.seek(0)
        requireMagic(reader, "IN", "install")
        val version = reader.u8("install version")
        val hashSize = reader.u8("install CKey width")
        val tagCount = reader.u16("install tag count")
        val entryCount = checkedEntryCount(reader.u32("install entry count"), "install")
        if (version !in 1..2) fail("unsupported install version: $version")
        if (hashSize != 16) fail("install CKey width is $hashSize; full 16-byte CKeys required")
        val selected = readTagMasks(reader, tagCount, entryCount, allowSizeEndMarker = false)
        val normalizer = InstallPathNormalizer()
        val seenPaths = HashSet<String>()
        var selectedCount = 0L
        for (index in 0 until entryCount) {
            if ((index and 0x3ff) == 0) checkCancelled(cancelled)
            val rawPath = reader.utf8CString(MAX_PATH_BYTES, "install path at entry $index")
            val contentKey = reader.hex(reader.readExact(16, "install CKey at entry $index"))
            val decodedSize = reader.u32("install decoded size at entry $index")
            if (version >= 2) reader.u8("install file type at entry $index")
            if (isSelected(selected, index)) {
                val path = normalizer.normalize(rawPath)
                selectedCount = addExact(selectedCount, 1L)
                if (selectedCount > MAX_LOOSE_FILES) fail("selected install file count exceeds $MAX_LOOSE_FILES")
                if (!seenPaths.add(path.lowercase(Locale.ROOT))) {
                    fail("selected install manifest contains duplicate loose path: $path")
                }
                onRecord?.invoke(ForeverTactMetadata.InstallRecord(path, contentKey, decodedSize))
            }
        }
        reader.requireEof("install manifest")
        return selectedCount
    }

    private fun parseDownload(
        reader: ChannelReader,
        cancelled: () -> Boolean,
        onRecord: ((ForeverTactMetadata.DownloadRecord) -> Unit)?,
    ): ForeverTactMetadata.SelectionStats {
        reader.seek(0)
        requireMagic(reader, "DL", "download")
        val version = reader.u8("download version")
        val keySize = reader.u8("download EKey width")
        val checksumFlag = reader.u8("download checksum flag")
        val entryCount = checkedEntryCount(reader.u32("download entry count"), "download")
        val tagCount = reader.u16("download tag count")
        if (version !in 1..3) fail("unsupported download version: $version")
        if (keySize != 16) fail("download EKey width is $keySize; full 16-byte EKeys required")
        if (checksumFlag !in 0..1) fail("invalid download checksum flag: $checksumFlag")
        val flagSize = if (version >= 2) reader.u8("download V2 flag size") else 0
        if (flagSize != 0) fail("download V2 entry flags are unsupported (flag size $flagSize)")
        if (version >= 3) {
            reader.u8("download V3 base priority")
            val reserved = reader.readExact(3, "download V3 reserved header")
            if (reserved.any { it.toInt() != 0 }) fail("nonzero reserved bytes in download V3 header")
        }
        val hasChecksum = checksumFlag == 1
        val entriesOffset = reader.position

        // Entry records precede their tag bitmaps. Validate and skip them first.
        for (index in 0 until entryCount) {
            if ((index and 0x3ff) == 0) checkCancelled(cancelled)
            reader.readExact(16, "download EKey at entry $index")
            val encodedSize = reader.u40("download encoded size at entry $index")
            if (encodedSize <= 0L) fail("download entry $index has non-positive encoded size")
            reader.u8("download priority at entry $index")
            if (hasChecksum) reader.skip(4, "download checksum at entry $index")
        }
        val selected = readTagMasks(reader, tagCount, entryCount, allowSizeEndMarker = false)
        reader.requireEof("download manifest")

        // Tag selection is now known. Reread entries and stream only selected rows.
        reader.seek(entriesOffset)
        var selectedCount = 0L
        var selectedBytes = 0L
        for (index in 0 until entryCount) {
            if ((index and 0x3ff) == 0) checkCancelled(cancelled)
            val ekey = reader.hex(reader.readExact(16, "download EKey at entry $index"))
            val encodedSize = reader.u40("download encoded size at entry $index")
            val priority = reader.i8("download priority at entry $index")
            if (hasChecksum) reader.skip(4, "download checksum at entry $index")
            if (isSelected(selected, index)) {
                selectedCount = addExact(selectedCount, 1L)
                selectedBytes = addExact(selectedBytes, encodedSize)
                onRecord?.invoke(ForeverTactMetadata.DownloadRecord(ekey, encodedSize, priority))
            }
        }
        return ForeverTactMetadata.SelectionStats(entryCount.toLong(), selectedCount, selectedBytes)
    }

    private fun parseSize(
        reader: ChannelReader,
        cancelled: () -> Boolean,
        onRecord: ((ForeverTactMetadata.SizeRecord) -> Unit)?,
    ): ForeverTactMetadata.SelectionStats {
        reader.seek(0)
        requireMagic(reader, "DS", "size")
        val version = reader.u8("size version")
        val keySize = reader.u8("size EKey-prefix width")
        val entryCount = checkedEntryCount(reader.u32("size entry count"), "size")
        val tagCount = reader.u16("size tag count")
        val declaredTotal = reader.u40("size declared total")
        if (version !in 1..2) fail("unsupported size version: $version")
        if (keySize != 9) fail("size EKey-prefix width is $keySize; the supported estimate format requires 9 bytes")
        val selected = readTagMasks(reader, tagCount, entryCount, allowSizeEndMarker = true)
        val entriesOffset = reader.position
        var totalBytes = 0L
        var selectedCount = 0L
        var selectedBytes = 0L
        for (index in 0 until entryCount) {
            if ((index and 0x3ff) == 0) checkCancelled(cancelled)
            reader.skip(9, "size EKey prefix at entry $index")
            val estimate = reader.u32("size estimate at entry $index")
            totalBytes = addExact(totalBytes, estimate)
            if (isSelected(selected, index)) {
                selectedCount = addExact(selectedCount, 1L)
                selectedBytes = addExact(selectedBytes, estimate)
            }
        }
        if (totalBytes != declaredTotal) {
            fail("size total mismatch: entry sum=$totalBytes header total=$declaredTotal")
        }
        reader.requireEof("size manifest")

        if (onRecord != null) {
            reader.seek(entriesOffset)
            for (index in 0 until entryCount) {
                if ((index and 0x3ff) == 0) checkCancelled(cancelled)
                val prefix = reader.hex(reader.readExact(9, "size EKey prefix at entry $index"))
                val estimate = reader.u32("size estimate at entry $index")
                if (isSelected(selected, index)) {
                    onRecord(ForeverTactMetadata.SizeRecord(prefix, estimate))
                }
            }
        }
        return ForeverTactMetadata.SelectionStats(entryCount.toLong(), selectedCount, selectedBytes)
    }

    private fun readTagMasks(
        reader: ChannelReader,
        tagCount: Int,
        entryCount: Int,
        allowSizeEndMarker: Boolean,
    ): ByteArray {
        if (tagCount > MAX_TAGS) fail("manifest tag count exceeds $MAX_TAGS")
        val maskBytes = (entryCount.toLong() + 7L) / 8L
        val retained = HashMap<String, TagMask>(3)
        val names = HashSet<String>()
        var totalMaskBytes = 0L
        val selectionWorkBytes = multiplyExact(maskBytes, 4L) // result bitmap plus at most three tag-type groups.
        for (tagIndex in 0 until tagCount) {
            val name = reader.utf8CString(MAX_TAG_NAME_BYTES, "manifest tag $tagIndex name")
            if (name.isEmpty()) fail("manifest tag $tagIndex has an empty name")
            val tagType = reader.u16("manifest tag $name type")
            if (allowSizeEndMarker && tagType in setOf(0, 0xffff)) break
            val requestedName = SELECTION_TAGS.firstOrNull { name.equals(it, ignoreCase = true) }
            if (requestedName != null && name != requestedName) {
                fail("selection tag has unexpected casing: $name; expected $requestedName")
            }
            if (!names.add(name)) fail("duplicate manifest tag name: $name")
            totalMaskBytes = addExact(totalMaskBytes, maskBytes)
            if (addExact(totalMaskBytes, selectionWorkBytes) > MAX_TAG_BITMAP_BYTES) {
                fail("manifest tag bitmaps exceed the $MAX_TAG_BITMAP_BYTES-byte limit")
            }
            if (name in SELECTION_TAGS) {
                retained[name] = TagMask(tagType, reader.readExact(maskBytes.toInt(), "manifest tag $name bitmap"))
            } else {
                reader.skip(maskBytes, "manifest tag $name bitmap")
            }
        }
        val missing = SELECTION_TAGS - retained.keys
        if (missing.isNotEmpty()) {
            fail("manifest is missing required target tag${if (missing.size == 1) "" else "s"}: ${missing.sorted().joinToString()}")
        }
        return buildSelection(entryCount, retained)
    }

    private fun buildSelection(entryCount: Int, tags: Map<String, TagMask>): ByteArray {
        val size = (entryCount + 7) / 8
        val selection = ByteArray(size) { 0xff.toByte() }
        val groups = HashMap<Int, ByteArray>(3)
        for (name in SELECTION_TAGS) {
            val tag = tags[name] ?: continue
            val group = groups.getOrPut(tag.tagType) { ByteArray(size) }
            for (index in group.indices) group[index] = (group[index].toInt() or tag.bits[index].toInt()).toByte()
        }
        // Match cascette apply_tag_query: requested tags OR within tag type,
        // and the resulting groups AND together. Unknown requested names are ignored.
        for (group in groups.values) {
            for (index in selection.indices) {
                selection[index] = (selection[index].toInt() and group[index].toInt()).toByte()
            }
        }
        if (!hasSelectedEntry(selection, entryCount)) fail("target tag selection contains no entries")
        return selection
    }

    private fun hasSelectedEntry(selection: ByteArray, entryCount: Int): Boolean {
        if (entryCount == 0) return false
        val remainder = entryCount and 7
        for (index in selection.indices) {
            var bits = selection[index].toInt() and 0xff
            if (index == selection.lastIndex && remainder != 0) {
                bits = bits and ((0xff shl (8 - remainder)) and 0xff)
            }
            if (bits != 0) return true
        }
        return false
    }

    private fun isSelected(selection: ByteArray, index: Int): Boolean =
        (selection[index ushr 3].toInt() and (0x80 ushr (index and 7))) != 0

    private fun parseEncoding(
        reader: ChannelReader,
        cancelled: () -> Boolean,
        onContent: ((ForeverTactMetadata.ContentMapping) -> Unit)?,
        onEncoding: ((ForeverTactMetadata.EncodingRecord) -> Unit)?,
    ): ForeverTactMetadata.EncodingStats {
        reader.seek(0)
        requireMagic(reader, "EN", "encoding")
        val version = reader.u8("encoding version")
        val ckeyWidth = reader.u8("encoding CKey width")
        val ekeyWidth = reader.u8("encoding EKey width")
        val ckeyPageKb = reader.u16("encoding CKey page size")
        val ekeyPageKb = reader.u16("encoding EKey page size")
        val ckeyPageCount = checkedPageCount(reader.u32("encoding CKey page count"), "CKey")
        val ekeyPageCount = checkedPageCount(reader.u32("encoding EKey page count"), "EKey")
        val unknownFlags = reader.u8("encoding flags")
        val especSize = reader.u32("encoding ESpec size")
        if (version != 1) fail("unsupported encoding version: $version")
        if (ckeyWidth != 16 || ekeyWidth != 16) {
            fail("encoding keys must be full 16-byte CKeys and EKeys")
        }
        if (unknownFlags != 0) fail("unsupported encoding header flags: 0x${unknownFlags.toString(16)}")
        if (ckeyPageKb == 0 || ekeyPageKb == 0) fail("encoding page size is zero")
        val ckeyPageBytes = Math.multiplyExact(ckeyPageKb, 1024)
        val ekeyPageBytes = Math.multiplyExact(ekeyPageKb, 1024)
        if (ckeyPageBytes > MAX_ENCODING_PAGE_BYTES || ekeyPageBytes > MAX_ENCODING_PAGE_BYTES) {
            fail("encoding page exceeds the $MAX_ENCODING_PAGE_BYTES-byte limit")
        }
        if (especSize !in 1..MAX_ENCODING_PAGE_BYTES.toLong()) {
            fail("encoding ESpec table size is outside 1..$MAX_ENCODING_PAGE_BYTES bytes")
        }
        val especCount = validateEspec(reader.readExact(especSize.toInt(), "encoding ESpec table"))
        val ckeyIndexOffset = reader.position
        val ckeyIndexBytes = multiplyExact(ckeyPageCount.toLong(), 32L)
        val ckeyPagesOffset = addExact(ckeyIndexOffset, ckeyIndexBytes)
        val ckeyPagesBytes = multiplyExact(ckeyPageCount.toLong(), ckeyPageBytes.toLong())
        val ekeyIndexOffset = addExact(ckeyPagesOffset, ckeyPagesBytes)
        val ekeyIndexBytes = multiplyExact(ekeyPageCount.toLong(), 32L)
        val ekeyPagesOffset = addExact(ekeyIndexOffset, ekeyIndexBytes)
        val ekeyPagesBytes = multiplyExact(ekeyPageCount.toLong(), ekeyPageBytes.toLong())
        val expectedEnd = addExact(ekeyPagesOffset, ekeyPagesBytes)
        if (expectedEnd > reader.fileSize) {
            fail("encoding page/index geometry requires $expectedEnd bytes; file has ${reader.fileSize}")
        }
        val layout = EncodingLayout(
            ckeyPageBytes, ekeyPageBytes, ckeyPageCount, ekeyPageCount, especCount,
            ckeyIndexOffset, ckeyPagesOffset, ekeyIndexOffset, ekeyPagesOffset,
            reader.fileSize - expectedEnd,
        )
        val ckeyCount = scanCkeyPages(reader, layout, cancelled, onContent)
        val ekeyCount = scanEkeyPages(reader, layout, cancelled, onEncoding)
        reader.seek(expectedEnd)
        reader.skip(layout.trailingBytes, "encoding trailing metadata")
        reader.requireEof("encoding manifest")
        return ForeverTactMetadata.EncodingStats(ckeyCount, ekeyCount, layout.trailingBytes)
    }

    private fun scanCkeyPages(
        reader: ChannelReader,
        layout: EncodingLayout,
        cancelled: () -> Boolean,
        onContent: ((ForeverTactMetadata.ContentMapping) -> Unit)?,
    ): Long {
        var recordCount = 0L
        var mappingCount = 0L
        var previousIndexKey: ByteArray? = null
        var previousRecordKey: ByteArray? = null
        var checkedRecords = 0L
        for (pageIndex in 0 until layout.ckeyPageCount) {
            checkCancelled(cancelled)
            val index = readPageIndex(reader, layout.ckeyIndexOffset, pageIndex, "CKey")
            if (previousIndexKey != null && compareUnsigned(index.firstKey, previousIndexKey!!) <= 0) {
                fail("encoding CKey page index is not strictly sorted at page $pageIndex")
            }
            val nextIndexKey = if (pageIndex + 1 < layout.ckeyPageCount) {
                readPageIndex(reader, layout.ckeyIndexOffset, pageIndex + 1, "CKey").firstKey
            } else null
            val pageOffset = addExact(layout.ckeyPagesOffset, multiplyExact(pageIndex.toLong(), layout.ckeyPageBytes.toLong()))
            reader.seek(pageOffset)
            val page = reader.readExact(layout.ckeyPageBytes, "encoding CKey page $pageIndex")
            verifyPageChecksum(page, index.checksum, "CKey", pageIndex)

            var offset = 0
            var firstActual: ByteArray? = null
            while (offset < page.size) {
                if (page[offset].toInt() == 0) break
                if (offset > page.size - 22) fail("truncated CKey page $pageIndex entry header")
                val keyCount = page[offset].toInt() and 0xff
                val decodedSize = u40At(page, offset + 1)
                val contentKey = page.copyOfRange(offset + 6, offset + 22)
                val keyBytes = Math.multiplyExact(keyCount, 16)
                val end = offset.toLong() + 22L + keyBytes.toLong()
                if (end > page.size) fail("CKey page $pageIndex EKey list exceeds page")
                if (firstActual == null) {
                    firstActual = contentKey
                    if (!contentKey.contentEquals(index.firstKey)) {
                        fail("encoding CKey page $pageIndex first key/index mismatch")
                    }
                }
                if (previousRecordKey != null && compareUnsigned(contentKey, previousRecordKey!!) <= 0) {
                    fail("encoding CKey records are not strictly sorted at page $pageIndex")
                }
                if (nextIndexKey != null && compareUnsigned(contentKey, nextIndexKey) >= 0) {
                    fail("encoding CKey page $pageIndex record crosses its page-index key range")
                }
                previousRecordKey = contentKey
                recordCount = addExact(recordCount, 1L)
                if (recordCount > MAX_ENTRIES) fail("encoding CKey record count exceeds $MAX_ENTRIES")
                mappingCount = addExact(mappingCount, keyCount.toLong())
                if (mappingCount > MAX_CONTENT_MAPPINGS) fail("encoding CKey/EKey mappings exceed $MAX_CONTENT_MAPPINGS")
                if (onContent != null) {
                    var ekeyOffset = offset + 22
                    repeat(keyCount) {
                        val ekey = page.copyOfRange(ekeyOffset, ekeyOffset + 16)
                        onContent(ForeverTactMetadata.ContentMapping(reader.hex(contentKey), reader.hex(ekey), decodedSize))
                        ekeyOffset += 16
                    }
                }
                offset = end.toInt()
                checkedRecords++
                if ((checkedRecords and 0x3ffL) == 0L) checkCancelled(cancelled)
            }
            if (firstActual == null) fail("encoding CKey page $pageIndex contains no indexed records")
            previousIndexKey = index.firstKey
        }
        return mappingCount
    }

    private fun scanEkeyPages(
        reader: ChannelReader,
        layout: EncodingLayout,
        cancelled: () -> Boolean,
        onEncoding: ((ForeverTactMetadata.EncodingRecord) -> Unit)?,
    ): Long {
        var recordCount = 0L
        var previousIndexKey: ByteArray? = null
        var previousRecordKey: ByteArray? = null
        var checkedRecords = 0L
        for (pageIndex in 0 until layout.ekeyPageCount) {
            checkCancelled(cancelled)
            val index = readPageIndex(reader, layout.ekeyIndexOffset, pageIndex, "EKey")
            if (previousIndexKey != null && compareUnsigned(index.firstKey, previousIndexKey!!) <= 0) {
                fail("encoding EKey page index is not strictly sorted at page $pageIndex")
            }
            val nextIndexKey = if (pageIndex + 1 < layout.ekeyPageCount) {
                readPageIndex(reader, layout.ekeyIndexOffset, pageIndex + 1, "EKey").firstKey
            } else null
            val pageOffset = addExact(layout.ekeyPagesOffset, multiplyExact(pageIndex.toLong(), layout.ekeyPageBytes.toLong()))
            reader.seek(pageOffset)
            val page = reader.readExact(layout.ekeyPageBytes, "encoding EKey page $pageIndex")
            verifyPageChecksum(page, index.checksum, "EKey", pageIndex)

            var offset = 0
            var firstActual: ByteArray? = null
            var sentinelFound = false
            while (offset <= page.size - 25) {
                val ekey = page.copyOfRange(offset, offset + 16)
                val especIndex = u32At(page, offset + 16)
                if (especIndex == 0xffff_ffffL || (especIndex == 0L && ekey.all { it.toInt() == 0 })) {
                    sentinelFound = true
                    break
                }
                val encodedSize = u40At(page, offset + 20)
                if (especIndex >= layout.especCount.toLong()) {
                    fail("encoding EKey page $pageIndex has invalid ESpec index $especIndex")
                }
                if (encodedSize <= 0L) fail("encoding EKey page $pageIndex has non-positive object size")
                if (firstActual == null) {
                    firstActual = ekey
                    if (!ekey.contentEquals(index.firstKey)) {
                        fail("encoding EKey page $pageIndex first key/index mismatch")
                    }
                }
                if (previousRecordKey != null && compareUnsigned(ekey, previousRecordKey!!) <= 0) {
                    fail("encoding EKey records are not strictly sorted at page $pageIndex")
                }
                if (nextIndexKey != null && compareUnsigned(ekey, nextIndexKey) >= 0) {
                    fail("encoding EKey page $pageIndex record crosses its page-index key range")
                }
                previousRecordKey = ekey
                recordCount = addExact(recordCount, 1L)
                if (recordCount > MAX_ENTRIES) fail("encoding EKey record count exceeds $MAX_ENTRIES")
                onEncoding?.invoke(ForeverTactMetadata.EncodingRecord(reader.hex(ekey), encodedSize))
                offset += 25
                checkedRecords++
                if ((checkedRecords and 0x3ffL) == 0L) checkCancelled(cancelled)
            }
            if (firstActual == null) fail("encoding EKey page $pageIndex contains no indexed records")
            // A non-sentinel partial record is malformed rather than silently ignored.
            val remaining = page.size - offset
            if (!sentinelFound && remaining in 1..24 && page.copyOfRange(offset, page.size).any { it.toInt() != 0 }) {
                fail("truncated nonzero EKey page $pageIndex entry")
            }
            previousIndexKey = index.firstKey
        }
        return recordCount
    }

    private data class PageIndex(val firstKey: ByteArray, val checksum: ByteArray)

    private fun readPageIndex(reader: ChannelReader, offset: Long, pageIndex: Int, kind: String): PageIndex {
        val entryOffset = addExact(offset, multiplyExact(pageIndex.toLong(), 32L))
        reader.seek(entryOffset)
        return PageIndex(
            reader.readExact(16, "encoding $kind page $pageIndex index key"),
            reader.readExact(16, "encoding $kind page $pageIndex checksum"),
        )
    }

    private fun verifyPageChecksum(page: ByteArray, expected: ByteArray, kind: String, pageIndex: Int) {
        val actual = MessageDigest.getInstance("MD5").digest(page)
        if (!actual.contentEquals(expected)) fail("encoding $kind page $pageIndex checksum mismatch")
    }

    /** Validates the entire bounded ESpec string table without interpreting compression specifications. */
    private fun validateEspec(raw: ByteArray): Int {
        if (raw.last().toInt() != 0) fail("encoding ESpec table is not NUL-terminated")
        var stringCount = 0
        var stringLength = 0
        for (byte in raw) {
            val value = byte.toInt() and 0xff
            if (value == 0) {
                if (stringLength == 0) fail("encoding ESpec table contains an empty string")
                stringCount++
                stringLength = 0
            } else {
                if (value !in 0x20..0x7e) fail("encoding ESpec table contains unsupported non-printable/non-ASCII data")
                stringLength++
            }
        }
        if (stringCount == 0 || stringLength != 0) fail("encoding ESpec table contains no complete string")
        return stringCount
    }

    private fun checkedPageCount(value: Long, label: String): Int {
        if (value !in 1..MAX_ENCODING_PAGE_COUNT.toLong()) {
            fail("encoding $label page count is outside 1..$MAX_ENCODING_PAGE_COUNT")
        }
        return value.toInt()
    }

    private fun checkedEntryCount(value: Long, label: String): Int {
        if (value !in 0..MAX_ENTRIES) fail("$label manifest entry count exceeds $MAX_ENTRIES")
        return value.toInt()
    }

    private fun validateSafePath(value: String) {
        if (value.isEmpty() || value.length > MAX_PATH_BYTES || '\u0000' in value) fail("unsafe install path")
        val normalized = value.replace('\\', '/')
        if (normalized.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            fail("absolute install path is not allowed: $value")
        }
        val components = normalized.split('/')
        if (components.any { component ->
                component.isEmpty() || component == "." || component == ".." ||
                    component.any { it.code < 0x20 || it.code == 0x7f || it in "<>:\"|?*" } ||
                    component.endsWith('.') || component.endsWith(' ') || isWindowsDeviceName(component)
            }
        ) fail("install path contains an unsafe component: $value")
    }

    private fun isWindowsDeviceName(component: String): Boolean {
        val base = component.substringBefore('.').uppercase(Locale.ROOT)
        return base in setOf("CON", "PRN", "AUX", "NUL") ||
            base.matches(Regex("(?:COM|LPT)[1-9]"))
    }

    private fun <T> withReader(file: File, cancelled: () -> Boolean, block: (ChannelReader) -> T): T {
        checkCancelled(cancelled)
        val path = file.toPath().toAbsolutePath().normalize()
        if (!java.nio.file.Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || java.nio.file.Files.isSymbolicLink(path)) {
            fail("TACT metadata is not a regular non-symlink file")
        }
        val size = java.nio.file.Files.size(path)
        if (size !in 1..MAX_FILE_BYTES) fail("TACT metadata file size is outside 1..$MAX_FILE_BYTES bytes")
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (channel.size() != size) fail("TACT metadata file changed before parsing")
            val reader = ChannelReader(channel, size, cancelled)
            val result = block(reader)
            checkCancelled(cancelled)
            if (channel.size() != size) fail("TACT metadata file changed while parsing")
            return result
        }
    }

    private fun requireMagic(reader: ChannelReader, magic: String, kind: String) {
        val actual = reader.readExact(2, "$kind magic")
        if (actual[0] != magic[0].code.toByte() || actual[1] != magic[1].code.toByte()) {
            fail("$kind manifest magic mismatch: ${reader.hex(actual)}")
        }
    }

    private fun u40At(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 5) value = (value shl 8) or (bytes[offset + index].toLong() and 0xffL)
        return value
    }

    private fun u32At(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        for (index in left.indices) {
            val difference = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return 0
    }

    private fun addExact(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (failure: ArithmeticException) {
        throw IllegalArgumentException("TACT metadata numeric field overflows", failure)
    }

    private fun multiplyExact(left: Long, right: Long): Long = try {
        Math.multiplyExact(left, right)
    } catch (failure: ArithmeticException) {
        throw IllegalArgumentException("TACT metadata section geometry overflows", failure)
    }

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw CancellationException("TACT metadata parsing cancelled")
    }

    private class ChannelReader(
        private val channel: FileChannel,
        val fileSize: Long,
        private val cancelled: () -> Boolean,
    ) {
        private val buffer = ByteBuffer.allocate(BUFFER_BYTES)
        private var bufferStart = -1L
        private var bufferLength = 0
        var position: Long = 0L
            private set

        fun seek(offset: Long) {
            if (offset !in 0..fileSize) fail("metadata seek is outside the file")
            position = offset
        }

        fun u8(context: String): Int = readExact(1, context)[0].toInt() and 0xff

        fun i8(context: String): Int = readExact(1, context)[0].toInt()

        fun u16(context: String): Int {
            val bytes = readExact(2, context)
            return ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)
        }

        fun u32(context: String): Long = u32At(readExact(4, context), 0)

        fun u40(context: String): Long = u40At(readExact(5, context), 0)

        fun utf8CString(limit: Int, context: String): String {
            val output = ByteArray(limit.coerceAtMost(256))
            var data = output
            var length = 0
            while (true) {
                val next = u8(context)
                if (next == 0) break
                if (length >= limit) fail("$context exceeds $limit bytes")
                if (length == data.size) data = data.copyOf(minOf(limit, maxOf(data.size * 2, 16)))
                data[length++] = next.toByte()
            }
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, 0, length))
                    .toString()
            } catch (failure: Exception) {
                throw IllegalArgumentException("invalid UTF-8 in $context", failure)
            }
        }

        fun readExact(length: Int, context: String): ByteArray {
            if (length < 0 || length > MAX_ENCODING_PAGE_BYTES) fail("$context has an unsupported read length")
            val result = ByteArray(length)
            readInto(result, 0, length, context)
            return result
        }

        fun skip(length: Long, context: String) {
            if (length < 0L) fail("negative length while skipping $context")
            val end = addExact(position, length)
            if (end > fileSize) fail("truncated $context")
            position = end
        }

        fun requireEof(context: String) {
            if (position != fileSize) fail("unexpected trailing bytes in $context: ${fileSize - position}")
        }

        fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }

        private fun readInto(target: ByteArray, targetOffset: Int, length: Int, context: String) {
            var written = 0
            while (written < length) {
                if (position >= fileSize) fail("truncated $context: expected $length bytes")
                if (bufferStart < 0L || position < bufferStart || position >= bufferStart + bufferLength) fillBuffer()
                val inBuffer = (position - bufferStart).toInt()
                val count = minOf(length - written, bufferLength - inBuffer)
                if (count <= 0) fail("truncated $context")
                System.arraycopy(buffer.array(), inBuffer, target, targetOffset + written, count)
                position += count
                written += count
            }
        }

        private fun fillBuffer() {
            checkCancelled(cancelled)
            buffer.clear()
            val start = position
            while (buffer.hasRemaining()) {
                val count = channel.read(buffer, start + buffer.position())
                if (count < 0) break
                if (count == 0) break
            }
            bufferLength = buffer.position()
            bufferStart = start
            if (bufferLength == 0) fail("unexpected end of TACT metadata file")
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
