package app.gamenative

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ForeverTactConfigsTest {
    @Test
    fun supportsTwoDistinctOfficialVersionFingerprintsWithoutBuildPins() {
        listOf(70_425L, 70_426L).forEach { buildId ->
            val raw = fakeBuild(buildId)
            val fingerprint = fingerprint(raw, buildId)

            val parsed = ForeverTactConfigs.build(raw, fingerprint)

            assertEquals(fingerprint, parsed.fingerprint)
            assertEquals(setOf("install", "download", "size", "encoding"), parsed.metadataObjects.keys)
            assertEquals(4_096L, parsed.metadataObjects.getValue("install").decodedSize)
            assertEquals(2_048L, parsed.metadataObjects.getValue("install").encodedSize)
            assertEquals(3, parsed.runtimeContents.size)
            assertEquals("root", parsed.runtimeContents.first().role)
            assertEquals("vfs-root", parsed.runtimeContents[1].role)
            assertTrue(parsed.runtimeContents.last().role.startsWith("vfs-"))
            assertEquals(sha256(raw), parsed.rawSha256)
        }
    }

    @Test
    fun rejectsConfigHashMismatchDuplicateFieldsTruncationAndMalformedRelevantHashes() {
        val valid = fakeBuild(70_425)
        val accepted = fingerprint(valid, 70_425)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverTactConfigs.build(valid, accepted.copy(buildConfigHash = "0".repeat(32)))
        }

        val duplicate = fakeBuild(70_425).toString(Charsets.UTF_8) +
            "install = ${"1".repeat(32)} ${"2".repeat(32)}\n"
        assertThrows(IllegalArgumentException::class.java) {
            parseBuild(duplicate.toByteArray(), 70_425)
        }
        val truncated = fakeBuild(70_425).toString(Charsets.UTF_8).replace(
            "install = ${"1".repeat(32)} ${"2".repeat(32)}",
            "install = ${"1".repeat(32)}",
        ).toByteArray()
        assertThrows(IllegalArgumentException::class.java) { parseBuild(truncated, 70_425) }
        val missingDirectSize = fakeBuild(70_425).toString(Charsets.UTF_8)
            .replace("install-size = 4096 2048\n", "")
            .toByteArray()
        val unsupported = assertThrows(UnsupportedOperationException::class.java) {
            parseBuild(missingDirectSize, 70_425)
        }
        assertTrue(unsupported.message.orEmpty().contains("direct install-size decoded/encoded sizes"))
        val malformed = fakeBuild(70_425).toString(Charsets.UTF_8).replace(
            "root = ${"a".repeat(32)}",
            "root = abc",
        ).toByteArray()
        assertThrows(IllegalArgumentException::class.java) { parseBuild(malformed, 70_425) }
        assertThrows(IllegalArgumentException::class.java) {
            parseBuild(fakeBuild(70_425) + byteArrayOf(0), 70_425)
        }
    }

    @Test
    fun treatsSizePairsAsDecodedThenEncodedAndDoesNotRejectUnknownFutureFields() {
        val raw = fakeBuild(70_425).toString(Charsets.UTF_8)
            .replace("install-size = 4096 2048", "install-size = 8192 2048")
            .replace("future-build-toggle = enabled", "future-build-toggle = enabled\nfuture-unknown-key = anything")
            .toByteArray()
        val install = ForeverTactConfigs.build(raw, fingerprint(raw, 70_425)).metadataObjects.getValue("install")
        assertEquals(8_192L, install.decodedSize)
        assertEquals(2_048L, install.encodedSize)
    }

    @Test
    fun rejectsMalformedCdnRoleButIgnoresUnneededUnknownFields() {
        val base = "archives = ${"a".repeat(32)} ${"b".repeat(32)}\nfile-index = ${"c".repeat(32)}\nfuture-route = https://not-a-cdn.example\n"
        val raw = base.toByteArray()
        val fingerprint = fingerprint(raw, 70_425).copy(cdnConfigHash = md5(raw))
        val parsed = ForeverTactConfigs.cdn(raw, fingerprint)
        assertEquals(listOf("a".repeat(32), "b".repeat(32)), parsed.archives)
        assertEquals("c".repeat(32), parsed.fileIndex)
        assertThrows(IllegalArgumentException::class.java) {
            val invalid = base.replace("file-index = ${"c".repeat(32)}", "file-index = evil").toByteArray()
            ForeverTactConfigs.cdn(invalid, fingerprint.copy(cdnConfigHash = md5(invalid)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            val tooMany = "archives = " + (0..8_192).joinToString(" ") { "%032x".format(it) }
            val oversized = tooMany.toByteArray()
            ForeverTactConfigs.cdn(oversized, fingerprint.copy(cdnConfigHash = md5(oversized)))
        }
    }

    @Test
    fun parsesReadOnlyPublished70425BuildAndCdnConfigReferences() {
        val buildFile = File(System.getProperty("wowforever.actualBuildConfig") ?: ACTUAL_BUILD_CONFIG)
        val cdnFile = File(System.getProperty("wowforever.actualCdnConfig") ?: ACTUAL_CDN_CONFIG)
        assumeTrue("external read-only 70425 metadata is not available", buildFile.isFile && cdnFile.isFile)
        val buildRaw = buildFile.readBytes()
        val cdnRaw = cdnFile.readBytes()
        val published = ForeverClientUpdateCheck.Fingerprint(
            product = "wow_classic_beta",
            version = "1.60.1.70425",
            buildConfigHash = "ea9c525eef74f1d682c00a8ef39a5d2b",
            cdnConfigHash = "fcab7fec2ccc6b8158b56b6938ec4551",
            buildId = 70_425,
        )

        val build = ForeverTactConfigs.build(buildRaw, published)
        val cdn = ForeverTactConfigs.cdn(cdnRaw, published)

        assertEquals(584, build.runtimeContents.size) // root + vfs-root + vfs-1..582; caller contributes four more roles.
        assertEquals("676f5891e7ed0fad6d9584ef949082a5", build.metadataObjects.getValue("encoding").encodingKey)
        assertEquals(141_273_036L, build.metadataObjects.getValue("encoding").encodedSize)
        assertEquals(141_574_945L, build.metadataObjects.getValue("encoding").decodedSize)
        assertEquals("6917be6a2d9adbb08a7e0e949d5a0d79", build.metadataObjects.getValue("download").encodingKey)
        assertEquals(48_612_067L, build.metadataObjects.getValue("download").encodedSize)
        assertEquals(56_870_749L, build.metadataObjects.getValue("download").decodedSize)
        assertEquals("b6dc3a5bd0f3e249d7859422c0f6de19", build.metadataObjects.getValue("size").encodingKey)
        assertEquals(31_289_347L, build.metadataObjects.getValue("size").encodedSize)
        assertEquals(36_993_686L, build.metadataObjects.getValue("size").decodedSize)
        assertEquals("62a5ac0a47cad9832bd6b9b4384fea60", build.metadataObjects.getValue("install").encodingKey)
        assertEquals(18_581L, build.metadataObjects.getValue("install").encodedSize)
        assertEquals(19_346L, build.metadataObjects.getValue("install").decodedSize)
        assertEquals(1_423, cdn.archives.size)
        assertEquals("41f95c1eee327d0cfa0a52e441631c2d", cdn.fileIndex)
        assertNotNull(build.rawSha256)
        assertNotNull(cdn.rawSha256)
    }

    private fun parseBuild(raw: ByteArray, buildId: Long) = ForeverTactConfigs.build(raw, fingerprint(raw, buildId))

    private fun fingerprint(raw: ByteArray, buildId: Long) = ForeverClientUpdateCheck.Fingerprint(
        product = "wow_classic_beta",
        version = "1.60.1.$buildId",
        buildConfigHash = md5(raw),
        cdnConfigHash = "f".repeat(32),
        buildId = buildId,
    )

    private fun fakeBuild(buildId: Long): ByteArray = """
        # Minimal ordinary config for $buildId
        root = ${"a".repeat(32)}
        install = ${"1".repeat(32)} ${"2".repeat(32)}
        install-size = 4096 2048
        download = ${"3".repeat(32)} ${"4".repeat(32)}
        download-size = 3000 1500
        size = ${"5".repeat(32)} ${"6".repeat(32)}
        size-size = 2000 1000
        encoding = ${"7".repeat(32)} ${"8".repeat(32)}
        encoding-size = 5000 2500
        build-uid = wow_classic_beta
        build-num = $buildId
        vfs-root = ${"9".repeat(32)} ${"a".repeat(32)}
        vfs-root-size = 128 64
        vfs-1 = ${"b".repeat(32)} ${"c".repeat(32)}
        vfs-1-size = 256 128
        future-build-toggle = enabled
    """.trimIndent().plus("\n").toByteArray()

    companion object {
        private const val ACTUAL_BUILD_CONFIG = "/home/adrian/wowforever-artifacts/arm64-prototype/client-build70425-admission/metadata/build-config-ea9c525eef74f1d682c00a8ef39a5d2b.txt"
        private const val ACTUAL_CDN_CONFIG = "/home/adrian/wowforever-artifacts/arm64-prototype/client-build70425-admission/metadata/cdn-config-fcab7fec2ccc6b8158b56b6938ec4551.txt"

        private fun md5(raw: ByteArray): String = hash("MD5", raw)
        private fun sha256(raw: ByteArray): String = hash("SHA-256", raw)
        private fun hash(algorithm: String, raw: ByteArray): String =
            MessageDigest.getInstance(algorithm).digest(raw).joinToString("") { "%02x".format(it) }
    }
}
