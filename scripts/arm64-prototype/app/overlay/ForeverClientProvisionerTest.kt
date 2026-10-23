package app.gamenative

import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.ArrayDeque
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeverClientProvisionerTest {
    @Test
    fun safePathsRejectTraversalDuplicatesAndSymlinkParents() {
        assertThrows(IllegalArgumentException::class.java) { ForeverClientProvisioner.validatePathForTest("../escape") }
        assertThrows(IllegalArgumentException::class.java) { ForeverClientProvisioner.validatePathForTest("A//b") }
        val root = tempDir()
        val real = File(root, "real").also { it.mkdirs() }
        val link = File(root, "link")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), real.toPath())
            assertThrows(IllegalStateException::class.java) { ForeverClientProvisioner.managedPathForTest(root, "link/file") }
        } finally {
            root.deleteRecursively()
        }
        val fresh = File(root, "new-client")
        assertFalse(fresh.exists())
        assertEquals(fresh.canonicalFile, ForeverClientProvisioner.checkedRootForTest(fresh))
        assertTrue(ForeverClientProvisioner.hasSufficientSpaceForTest(100, 100))
        assertFalse(ForeverClientProvisioner.hasSufficientSpaceForTest(99, 100))
        root.deleteRecursively()
    }

    @Test
    fun sameSizedCorruptArchiveRangeCacheIsDecodedBeforePromotion() {
        val root = tempDir()
        val cache = File(root, "cache").also { it.mkdirs() }
        val decoded = "range-object".toByteArray()
        val encoded = byteArrayOf(0x42, 0x4c, 0x54, 0x45, 0, 0, 0, 0, 'N'.code.toByte()) + decoded
        val ekey = "fedcba9876543210fedcba9876543210"
        val record = JSONObject()
            .put("content_key", md5Hex(decoded))
            .put("decoded_md5", md5Hex(decoded))
            .put("decoded_size", decoded.size)
            .put("encoded_size", encoded.size)
            .put("sources", org.json.JSONArray().put(JSONObject()
                .put("kind", "archive-range")
                .put("ekey", ekey)
                .put("archive", "0123456789abcdef0123456789abcdef")
                .put("offset", 0)
                .put("length", encoded.size)
                .put("encoded_size", encoded.size)
                .put("range", "bytes=0-${encoded.size - 1}")
                .put("url", "https://us.cdn.blizzard.com/tpr/wow/data/01/23/0123456789abcdef0123456789abcdef")))
        File(cache, ekey).writeBytes(ByteArray(encoded.size) { 3 })
        val calls = mutableListOf<Boolean>()
        val result = ForeverClientProvisioner.downloadRecordForTest(record, cache, { false }) { _, _, _, ranged ->
            calls += ranged
            FakeConnection(206, encoded)
        }
        assertEquals(listOf(true), calls)
        assertEquals(encoded.toList(), result.readBytes().toList())
        root.deleteRecursively()
    }

    @Test
    fun optionalRealCatalogHarnessValidatesPinsAndSerializedBuildInfo() {
        val path = System.getProperty("wowforever.catalog")
        org.junit.Assume.assumeTrue(path != null)
        val catalog = JSONObject(File(requireNotNull(path)).readText())
        ForeverClientProvisioner.validateCatalog(catalog)
        val buildInfo = catalog.getJSONObject("build_info")
        val bytes = ForeverCasc.buildInfo(buildInfo.getJSONObject("fields"))
        assertEquals(buildInfo.getInt("serialized_size"), bytes.size)
        assertEquals(buildInfo.getString("serialized_sha256"), sha256(bytes))
        val roles = HashSet<String>()
        val objects = catalog.getJSONObject("casc_bootstrap").getJSONArray("objects")
        for (index in 0 until objects.length()) {
            val array = objects.getJSONObject(index).optJSONArray("roles") ?: continue
            for (role in 0 until array.length()) roles += array.getString(role)
        }
        assertTrue(roles.containsAll(setOf("root", "vfs-root", "encoding", "install", "download", "size")))
    }

    @Test
    fun corruptCompletePartIsReplacedAndIgnoredRangeIsNeverAppended() {
        val root = tempDir()
        val cache = File(root, "cache").also { it.mkdirs() }
        val bytes = byteArrayOf(1, 2, 3, 4)
        val ekey = "0123456789abcdef0123456789abcdef"
        val record = directRecord(ekey, bytes)
        File(cache, "$ekey.part").writeBytes(byteArrayOf(9, 9, 9, 9))
        val calls = mutableListOf<Boolean>()
        ForeverClientProvisioner.downloadRecordForTest(record, cache, { false }) { _, _, _, ranged ->
            calls += ranged
            FakeConnection(200, bytes)
        }
        assertEquals(listOf(false), calls)
        assertEquals(bytes.toList(), File(cache, ekey).readBytes().toList())

        File(cache, ekey).delete()
        File(cache, "$ekey.part").writeBytes(byteArrayOf(1))
        val ignored = mutableListOf<Boolean>()
        assertThrows(IllegalArgumentException::class.java) {
            ForeverClientProvisioner.downloadRecordForTest(record, cache, { false }) { _, _, _, ranged ->
                ignored += ranged
                FakeConnection(200, bytes)
            }
        }
        assertEquals(listOf(true), ignored)
        assertEquals(listOf(1.toByte()), File(cache, "$ekey.part").readBytes().toList())
        root.deleteRecursively()
    }

    @Test
    fun redirectForConfigKeepsPathAndCancellationDoesNotPromote() {
        val root = tempDir()
        val cache = File(root, "cache").also { it.mkdirs() }
        val bytes = byteArrayOf(5, 6, 7)
        val hash = sha256(bytes)
        val record = JSONObject()
            .put("hash", "abcdef0123456789abcdef0123456789")
            .put("url", "https://us.cdn.blizzard.com/tpr/wow/config/ab/cd/abcdef0123456789abcdef0123456789")
            .put("observed_encoded_size", bytes.size)
            .put("present_sha256", hash)
        val responses = ArrayDeque<HttpURLConnection>()
        responses.add(FakeConnection(302, ByteArray(0), "https://us.cdn.blizzard.com/tpr/wow/config/ab/cd/abcdef0123456789abcdef0123456789"))
        responses.add(FakeConnection(200, bytes))
        val result = ForeverClientProvisioner.downloadRecordForTest(record, cache, { false }) { _, _, _, _ -> responses.removeFirst() }
        assertTrue(result.isFile)

        val cancelledRecord = directRecord("fedcba9876543210fedcba9876543210", bytes)
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            ForeverClientProvisioner.downloadRecordForTest(cancelledRecord, cache, { true }) { _, _, _, _ -> FakeConnection(200, bytes) }
        }
        assertFalse(File(cache, cancelledRecord.getJSONArray("sources").getJSONObject(0).getString("ekey")).exists())
        root.deleteRecursively()
    }

    private fun directRecord(ekey: String, bytes: ByteArray): JSONObject = JSONObject()
        .put("content_key", "0".repeat(32))
        .put("decoded_size", bytes.size)
        .put("encoded_size", bytes.size)
        .put("sources", org.json.JSONArray().put(JSONObject()
            .put("kind", "direct")
            .put("ekey", ekey)
            .put("url", "https://us.cdn.blizzard.com/tpr/wow/data/01/23/$ekey")
            .put("encoded_size", bytes.size)
            .put("sha256", sha256(bytes))))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun md5Hex(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "provisioner-test-${System.nanoTime()}").also { it.mkdirs() }

    private class FakeConnection(
        private val status: Int,
        private val body: ByteArray,
        private val location: String? = null,
    ) : HttpURLConnection(URL("https://us.cdn.blizzard.com/")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getContentLengthLong(): Long = body.size.toLong()
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getHeaderField(name: String): String? = if (name.equals("Location", true)) location else if (name.equals("Content-Range", true)) "bytes 0-${body.size - 1}/${body.size}" else null
    }
}
