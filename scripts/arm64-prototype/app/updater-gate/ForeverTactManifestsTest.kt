package app.gamenative

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ForeverTactManifestsTest {
    private lateinit var temporary: File

    @Before
    fun createTemporaryDirectory() {
        temporary = Files.createTempDirectory("forever-tact-manifest-test-").toFile()
    }

    @After
    fun removeTemporaryDirectory() {
        temporary.deleteRecursively()
    }

    @Test
    fun installStreamsOnlyWindowsArm64EnUsAndNormalizesInstallPaths() {
        val input = file("install.bin", installFixture(version = 2))
        val emitted = mutableListOf<ForeverTactMetadata.InstallRecord>()

        val selected = ForeverTactManifests.install(input, { false }, emitted::add)

        assertEquals(1L, selected)
        assertEquals(1, emitted.size)
        assertEquals("Utils/third.dat", emitted.single().path)
        assertEquals("22".repeat(16), emitted.single().contentKey)
        assertEquals(103L, emitted.single().decodedSize)
    }

    @Test
    fun installRejectsTruncationUnsafePathsAndDuplicateLoosePathsBeforeCallbacks() {
        var emitted = 0
        val truncated = file("install-truncated.bin", installFixture(entryCountOverride = 5))
        assertThrows(IllegalArgumentException::class.java) {
            ForeverTactManifests.install(truncated, { false }) { emitted++ }
        }
        assertEquals(0, emitted)

        val unsafe = file(
            "install-unsafe.bin",
            installFixture(entryCountOverride = 1, paths = listOf("../escape"), tags = allSelectedTags(1)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ForeverTactManifests.install(unsafe, { false }) { emitted++ }
        }
        assertEquals(0, emitted)

        val duplicate = file(
            "install-duplicate.bin",
            installFixture(
                entryCountOverride = 2,
                paths = listOf("Shared\\same.dat", "shared/same.dat"),
                tags = allSelectedTags(2),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ForeverTactManifests.install(duplicate, { false }) { emitted++ }
        }
        assertEquals(0, emitted)
    }

    @Test
    fun downloadUsesFullEkeysAndCascetteTagTypeOrAndAndSemantics() {
        val input = file(
            "download.bin",
            downloadFixture(version = 3, hasChecksum = true, tags = targetTags + FixtureTag("OSX", 1, 0x10)),
        )
        val emitted = mutableListOf<ForeverTactMetadata.DownloadRecord>()

        val stats = ForeverTactManifests.download(input, { false }, emitted::add)

        assertEquals(ForeverTactMetadata.SelectionStats(4, 1, 30), stats)
        assertEquals(1, emitted.size)
        assertEquals("02".repeat(16), emitted.single().encodingKey)
        assertEquals(30L, emitted.single().encodedSize)
        assertEquals(-3, emitted.single().priority)
    }

    @Test
    fun downloadRejectsBadCountsTruncationTrailingDataAndUnsupportedEntryFlagsBeforeCallbacks() {
        var emitted = 0
        val overflowing = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeBytes("DL")
                out.writeByte(1)
                out.writeByte(16)
                out.writeByte(0)
                out.writeInt(5_000_001)
                out.writeShort(0)
            }
        }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            ForeverTactManifests.download(file("download-overflow.bin", overflowing), { false }) { emitted++ }
        }
        assertEquals(0, emitted)

        val trailing = downloadFixture().plus(0x01)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverTactManifests.download(file("download-trailing.bin", trailing), { false }) { emitted++ }
        }
        assertEquals(0, emitted)

        val flags = downloadFixture(version = 2, flagSize = 1)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverTactManifests.download(file("download-flags.bin", flags), { false }) { emitted++ }
        }
        assertEquals(0, emitted)

        val unsupportedVersion = downloadFixture().also { it[2] = 4 }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverTactManifests.download(file("download-version.bin", unsupportedVersion), { false }) { emitted++ }
        }
        assertEquals(0, emitted)

        // The API accepts decoded manifests only; an encrypted/BLTE object is not
        // treated as an opaque file and cannot reach the resolver callback.
        val encryptedObject = byteArrayOf('B'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte(), 'E'.code.toByte())
        assertThrows(IllegalArgumentException::class.java) {
            ForeverTactManifests.download(file("download-encrypted.blte", encryptedObject), { false }) { emitted++ }
        }
        assertEquals(0, emitted)

        val truncated = downloadFixture().copyOfRange(0, downloadFixture().size - 2)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverTactManifests.download(file("download-truncated.bin", truncated), { false }) { emitted++ }
        }
        assertEquals(0, emitted)
    }

    @Test
    fun sizeReturnsSelectedNineByteEstimatesNotObjectIdentities() {
        val input = file("size.bin", sizeFixture(version = 2))
        val emitted = mutableListOf<ForeverTactMetadata.SizeRecord>()

        val stats = ForeverTactManifests.size(input, { false }, emitted::add)

        assertEquals(ForeverTactMetadata.SelectionStats(4, 1, 300), stats)
        assertEquals(1, emitted.size)
        assertEquals("02".repeat(9), emitted.single().encodingKeyPrefix)
        assertEquals(300L, emitted.single().encodedSize)
    }

    @Test
    fun sizeRejectsWrongTotalPrefixWidthAndTruncatedRowsBeforeCallbacks() {
        var emitted = 0
        val wrongTotal = sizeFixture(declaredTotalOverride = 1)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverTactManifests.size(file("size-total.bin", wrongTotal), { false }) { emitted++ }
        }
        assertEquals(0, emitted)

        val wrongWidth = sizeFixture(keyWidth = 8)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverTactManifests.size(file("size-width.bin", wrongWidth), { false }) { emitted++ }
        }
        assertEquals(0, emitted)

        val truncated = sizeFixture().copyOfRange(0, sizeFixture().size - 1)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverTactManifests.size(file("size-truncated.bin", truncated), { false }) { emitted++ }
        }
        assertEquals(0, emitted)
    }

    @Test
    fun installDownloadAndSizeRequireEveryExactTargetTagAndNonemptySelection() {
        listOf("Windows", "arm64", "enUS").forEach { missingTag ->
            val tagsWithoutOneTarget = targetTags.filterNot { it.name == missingTag }
            var installCallbacks = 0
            var downloadCallbacks = 0
            var sizeCallbacks = 0

            assertThrows(IllegalArgumentException::class.java) {
                ForeverTactManifests.install(
                    file("install-missing-$missingTag.bin", installFixture(tags = tagsWithoutOneTarget)),
                    { false },
                ) { installCallbacks++ }
            }
            assertThrows(IllegalArgumentException::class.java) {
                ForeverTactManifests.download(
                    file("download-missing-$missingTag.bin", downloadFixture(tags = tagsWithoutOneTarget)),
                    { false },
                ) { downloadCallbacks++ }
            }
            assertThrows(IllegalArgumentException::class.java) {
                ForeverTactManifests.size(
                    file("size-missing-$missingTag.bin", sizeFixture(tags = tagsWithoutOneTarget)),
                    { false },
                ) { sizeCallbacks++ }
            }

            assertEquals(0, installCallbacks)
            assertEquals(0, downloadCallbacks)
            assertEquals(0, sizeCallbacks)
        }

        val emptyMasks = targetTags.map { it.copy(bitmap = 0) }
        var installCallbacks = 0
        var downloadCallbacks = 0
        var sizeCallbacks = 0
        assertThrows(IllegalArgumentException::class.java) {
            ForeverTactManifests.install(file("install-empty-selection.bin", installFixture(tags = emptyMasks)), { false }) {
                installCallbacks++
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverTactManifests.download(file("download-empty-selection.bin", downloadFixture(tags = emptyMasks)), { false }) {
                downloadCallbacks++
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverTactManifests.size(file("size-empty-selection.bin", sizeFixture(tags = emptyMasks)), { false }) {
                sizeCallbacks++
            }
        }
        assertEquals(0, installCallbacks)
        assertEquals(0, downloadCallbacks)
        assertEquals(0, sizeCallbacks)

        val wrongCase = targetTags + FixtureTag("windows", 1, 0xf0)
        var wrongCaseCallbacks = 0
        assertThrows(IllegalArgumentException::class.java) {
            ForeverTactManifests.download(file("download-wrong-case-tag.bin", downloadFixture(tags = wrongCase)), { false }) {
                wrongCaseCallbacks++
            }
        }
        assertEquals(0, wrongCaseCallbacks)
    }

    @Test
    fun encodingEmitsAllMappingsAndEncodingEntriesAfterPageValidation() {
        val input = file("encoding.bin", encodingFixture(trailing = ByteArray(61) { it.toByte() }))
        val mappings = mutableListOf<ForeverTactMetadata.ContentMapping>()
        val encodings = mutableListOf<ForeverTactMetadata.EncodingRecord>()

        val stats = ForeverTactManifests.encoding(input, { false }, mappings::add, encodings::add)

        assertEquals(ForeverTactMetadata.EncodingStats(2, 2, 61), stats)
        assertEquals(listOf(encodingKey(1), encodingKey(2)), mappings.map { it.encodingKey })
        assertTrue(mappings.all { it.contentKey == "10".repeat(16) && it.decodedSize == 123L })
        assertEquals(listOf(encodingKey(1), encodingKey(2)), encodings.map { it.encodingKey })
        assertEquals(listOf(90L, 91L), encodings.map { it.encodedSize })
    }

    @Test
    fun encodingRejectsPageChecksumsIndexKeysFlagsAndMalformedEspecBeforeCallbacks() {
        var contentCallbacks = 0
        var encodingCallbacks = 0
        val invalidFiles = listOf(
            "encoding-checksum.bin" to encodingFixture(corruptCkeyPage = true),
            "encoding-index.bin" to encodingFixture(wrongCkeyIndexKey = true),
            "encoding-flags.bin" to encodingFixture(headerFlags = 1),
            "encoding-espec.bin" to encodingFixture(espec = byteArrayOf(0xff.toByte(), 0)),
        )
        invalidFiles.forEach { (name, bytes) ->
            assertThrows(IllegalArgumentException::class.java) {
                ForeverTactManifests.encoding(file(name, bytes), { false }, { contentCallbacks++ }, { encodingCallbacks++ })
            }
        }
        assertEquals(0, contentCallbacks)
        assertEquals(0, encodingCallbacks)

        val truncated = encodingFixture().copyOfRange(0, encodingFixture().size - 4_000)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverTactManifests.encoding(file("encoding-truncated.bin", truncated), { false }, { contentCallbacks++ }, { encodingCallbacks++ })
        }
        assertEquals(0, contentCallbacks)
        assertEquals(0, encodingCallbacks)
    }

    @Test
    fun identicalParserCodeAcceptsTwoUnpinnedSyntheticBuildFixtures() {
        listOf("fake-version-a", "fake-version-b").forEachIndexed { index, syntheticVersion ->
            val decodedSize = 700L + index
            val input = file(
                "$syntheticVersion-install.bin",
                installFixture(
                    entryCountOverride = 1,
                    paths = listOf("Data/file$index.bin"),
                    decodedSize = decodedSize,
                    tags = allSelectedTags(1),
                ),
            )
            val result = mutableListOf<ForeverTactMetadata.InstallRecord>()
            assertEquals(1L, ForeverTactManifests.install(input, { false }, result::add))
            assertEquals(decodedSize, result.single().decodedSize)
        }
    }

    @Test
    fun cancellationIsHonoredBeforeAnyRecordIsEmitted() {
        val input = file("cancelled-install.bin", installFixture())
        var emitted = 0

        assertThrows(java.util.concurrent.CancellationException::class.java) {
            ForeverTactManifests.install(input, { true }) { emitted++ }
        }

        assertEquals(0, emitted)
    }

    @Test
    fun optionalReadOnly70425FixtureMatchesHostManifestClosure() {
        val directory = System.getProperty("wowforever.tact70425.metadataDir")?.let(::File) ?: return
        require(directory.isDirectory) { "70425 metadata fixture directory does not exist" }
        val install = File(directory, "install-acc0330c72efa9eb745a87f550280eab.manifest")
        val download = File(directory, "download-a82cc27e938f1c72e036fae950737459.manifest")
        val size = File(directory, "size-0b9307a2f6d71b88cee1575dfb4d7aca.manifest")
        val encoding = File(directory, "encoding-89bf217d268bf919bb8c7f288c71034f.manifest")

        var installCount = 0L
        assertEquals(36L, ForeverTactManifests.install(install, { false }) { installCount++ })
        assertEquals(36L, installCount)

        val downloadStats = ForeverTactManifests.download(download, { false }) {}
        assertEquals(2_208_563L, downloadStats.totalEntries)
        assertEquals(1_144_392L, downloadStats.selectedEntries)
        assertEquals(90_752_004_058L, downloadStats.selectedEncodedBytes)

        val sizeStats = ForeverTactManifests.size(size, { false }) {}
        assertEquals(2_208_563L, sizeStats.totalEntries)
        assertEquals(1_144_392L, sizeStats.selectedEntries)
        assertEquals(downloadStats.selectedEntries, sizeStats.selectedEntries)
        assertEquals(downloadStats.selectedEncodedBytes, sizeStats.selectedEncodedBytes)
        assertEquals(90_752_004_058L, sizeStats.selectedEncodedBytes)

        var mappings = 0L
        var encodingEntries = 0L
        val encodingStats = ForeverTactManifests.encoding(
            encoding,
            { false },
            { mapping ->
                assertEquals(32, mapping.contentKey.length)
                assertEquals(32, mapping.encodingKey.length)
                mappings++
            },
            { record ->
                assertEquals(32, record.encodingKey.length)
                encodingEntries++
            },
        )
        assertEquals(2_209_149L, encodingStats.contentMappings)
        assertEquals(2_209_149L, mappings)
        assertEquals(2_209_149L, encodingStats.encodingEntries)
        assertEquals(2_209_149L, encodingEntries)
        assertEquals(61L, encodingStats.trailingBytes)
        assertFalse(downloadStats.selectedEntries == 1_319_619L)
        assertFalse(downloadStats.selectedEncodedBytes == 107_213_933_722L)
    }

    private fun file(name: String, bytes: ByteArray): File = File(temporary, name).apply { writeBytes(bytes) }

    private data class FixtureTag(val name: String, val type: Int, val bitmap: Int)

    private val targetTags = listOf(
        FixtureTag("Windows", 1, 0xa0),
        FixtureTag("arm64", 1, 0x60),
        FixtureTag("enUS", 3, 0x30),
    )

    private fun allSelectedTags(entryCount: Int): List<FixtureTag> {
        val mask = (0xff shl (8 - entryCount)) and 0xff
        return listOf(
            FixtureTag("Windows", 1, mask),
            FixtureTag("arm64", 2, mask),
            FixtureTag("enUS", 3, mask),
        )
    }

    private fun installFixture(
        version: Int = 1,
        entryCountOverride: Int = 4,
        paths: List<String> = listOf("first.dat", "second.dat", "Utils\\third.dat", "fourth.dat"),
        decodedSize: Long = 103,
        tags: List<FixtureTag> = targetTags,
    ): ByteArray = bytes { out ->
        out.writeBytes("IN")
        out.writeByte(version)
        out.writeByte(16)
        out.writeShort(tags.size)
        out.writeInt(entryCountOverride)
        tags.forEach { tag -> writeTag(out, tag, entryCountOverride) }
        paths.forEachIndexed { index, path ->
            out.write(path.toByteArray(Charsets.UTF_8))
            out.writeByte(0)
            out.write(ByteArray(16) { (0x20 + index).toByte() })
            out.writeInt(decodedSize.toInt())
            if (version >= 2) out.writeByte(0)
        }
    }

    private fun downloadFixture(
        version: Int = 1,
        hasChecksum: Boolean = false,
        flagSize: Int = 0,
        tags: List<FixtureTag> = targetTags,
    ): ByteArray = bytes { out ->
        out.writeBytes("DL")
        out.writeByte(version)
        out.writeByte(16)
        out.writeByte(if (hasChecksum) 1 else 0)
        out.writeInt(4)
        out.writeShort(tags.size)
        if (version >= 2) out.writeByte(flagSize)
        if (version >= 3) {
            out.writeByte(0)
            out.write(byteArrayOf(0, 0, 0))
        }
        repeat(4) { index ->
            out.write(ByteArray(16) { index.toByte() })
            writeU40(out, (index + 1L) * 10L)
            out.writeByte(if (index == 2) -3 else index)
            if (hasChecksum) out.writeInt(index)
        }
        tags.forEach { tag -> writeTag(out, tag, 4) }
    }

    private fun sizeFixture(
        version: Int = 1,
        keyWidth: Int = 9,
        declaredTotalOverride: Long? = null,
        tags: List<FixtureTag> = targetTags,
    ): ByteArray = bytes { out ->
        out.writeBytes("DS")
        out.writeByte(version)
        out.writeByte(keyWidth)
        out.writeInt(4)
        out.writeShort(tags.size)
        writeU40(out, declaredTotalOverride ?: 1_000L)
        tags.forEach { tag -> writeTag(out, tag, 4) }
        repeat(4) { index ->
            out.write(ByteArray(keyWidth) { index.toByte() })
            out.writeInt((index + 1) * 100)
        }
    }

    private fun encodingFixture(
        trailing: ByteArray = byteArrayOf(),
        corruptCkeyPage: Boolean = false,
        wrongCkeyIndexKey: Boolean = false,
        headerFlags: Int = 0,
        espec: ByteArray = byteArrayOf('n'.code.toByte(), 0),
    ): ByteArray {
        val contentKey = ByteArray(16) { 0x10 }
        val ekeys = listOf(ByteArray(16).also { it[15] = 1 }, ByteArray(16).also { it[15] = 2 })
        val ckeyPage = ByteArray(4 * 1024)
        ckeyPage[0] = 2
        putU40(ckeyPage, 1, 123)
        contentKey.copyInto(ckeyPage, 6)
        ekeys[0].copyInto(ckeyPage, 22)
        ekeys[1].copyInto(ckeyPage, 38)
        if (corruptCkeyPage) ckeyPage[100] = 0x7f

        val ekeyPage = ByteArray(4 * 1024)
        ekeys.forEachIndexed { index, key ->
            val offset = index * 25
            key.copyInto(ekeyPage, offset)
            putU32(ekeyPage, offset + 16, 0)
            putU40(ekeyPage, offset + 20, 90L + index)
        }
        val ckeyIndexKey = contentKey.copyOf().also { if (wrongCkeyIndexKey) it[15] = 0x11 }
        val ckeyPageChecksum = md5(ckeyPage).also { if (corruptCkeyPage) it[0] = (it[0].toInt() xor 0xff).toByte() }

        return bytes { out ->
            out.writeBytes("EN")
            out.writeByte(1)
            out.writeByte(16)
            out.writeByte(16)
            out.writeShort(4)
            out.writeShort(4)
            out.writeInt(1)
            out.writeInt(1)
            out.writeByte(headerFlags)
            out.writeInt(espec.size)
            out.write(espec)
            out.write(ckeyIndexKey)
            out.write(ckeyPageChecksum)
            out.write(ckeyPage)
            out.write(ekeys[0])
            out.write(md5(ekeyPage))
            out.write(ekeyPage)
            out.write(trailing)
        }
    }

    private fun writeTag(out: DataOutputStream, tag: FixtureTag, entryCount: Int) {
        out.write(tag.name.toByteArray(Charsets.UTF_8))
        out.writeByte(0)
        out.writeShort(tag.type)
        val maskSize = (entryCount + 7) / 8
        val mask = ByteArray(maskSize)
        if (mask.isNotEmpty()) mask[0] = tag.bitmap.toByte()
        out.write(mask)
    }

    private fun bytes(block: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use(block)
    }.toByteArray()

    private fun writeU40(out: DataOutputStream, value: Long) {
        require(value in 0..((1L shl 40) - 1))
        for (shift in 32 downTo 0 step 8) out.writeByte((value ushr shift).toInt())
    }

    private fun putU40(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 5) bytes[offset + index] = (value ushr (32 - index * 8)).toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt())
    }

    private fun md5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)

    private fun encodingKey(lastByte: Int): String = "00".repeat(15) + "%02x".format(lastByte)
}
