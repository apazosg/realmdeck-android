package app.gamenative

import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses existing external CDN evidence; no test makes a network request. */
class ForeverCdnSourceTest {
    private val targetKey = "e092b002f52c0827ce4b65d534d7316d"
    private val targetUrl = "https://us.cdn.blizzard.com/tpr/wow/data/e0/92/$targetKey"

    @Test
    fun actualStandalonePayloadDownloadsVerifiesAndReusesItsCache() {
        val (catalog, audit) = fixtures()
        val record = target(catalog)
        val source = record.getJSONArray("sources").getJSONObject(0)
        val full = audit.getJSONObject("full_download")
        assertEquals("direct", source.getString("kind"))
        assertEquals(targetUrl, source.getString("url"))
        assertEquals(3_932_016L, source.getLong("encoded_size"))
        assertEquals(8_654_776L, record.getLong("decoded_size"))
        assertEquals("d6c3f8e62bc38d8db9cb0cf1f3dee719", record.getString("content_key"))
        val raw = File(full.getString("saved_to"))
        assertEquals(source.getString("sha256"), sha256(raw))
        assertEquals(source.getString("sha256"), full.getString("raw_sha256"))
        val cache = Files.createTempDirectory("forever-cdn-cache-").toFile()
        try {
            var requests = 0
            val cached = ForeverClientProvisioner.downloadRecordForTest(record, cache, { false }) { url, _, _, ranged ->
                requests++
                assertEquals(targetUrl, url)
                assertFalse(ranged)
                FakeConnection(200, raw.readBytes())
            }
            assertEquals(1, requests)
            ForeverBlte.verify(cached, record.getLong("decoded_size"), record.getString("content_key"), { false })
            val reused = ForeverClientProvisioner.downloadRecordForTest(record, cache, { false }) { _, _, _, _ ->
                throw AssertionError("verified cache must not make another HTTP request")
            }
            assertEquals(cached.canonicalFile, reused.canonicalFile)
            assertEquals(source.getString("sha256"), sha256(reused))
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun fileIndexCannotBecomeAnArchiveOrDirectPayload() {
        val (catalog, _) = fixtures()
        val indices = catalog.getJSONObject("casc_bootstrap").getJSONArray("cdn_indices")
        val fileIndex = (0 until indices.length()).map { indices.getJSONObject(it) }
            .single { it.getString("index_role") == "file-index" }
        assertEquals(0, fileIndex.getInt("offset_bytes"))
        val hash = fileIndex.getString("hash")
        val payloadUrl = "https://us.cdn.blizzard.com/tpr/wow/data/${hash.take(2)}/${hash.substring(2, 4)}/$hash"

        val badRange = JSONObject(catalog.toString())
        val rangeSource = target(badRange).getJSONArray("sources").getJSONObject(0)
        rangeSource.put("kind", "archive-range").put("archive", hash).put("offset", 3_767_757_240L)
            .put("length", 3_932_016L).put("range", "bytes=3767757240-3771689255").put("url", payloadUrl)
        val rangeError = assertThrows(IllegalArgumentException::class.java) { ForeverClientProvisioner.validateCatalog(badRange) }
        assertTrue(rangeError.message.orEmpty().contains("file-index cannot be used as an archive payload"))

        val badDirect = JSONObject(catalog.toString())
        val directRecord = target(badDirect)
        directRecord.put("encoding_keys", JSONArray().put(hash))
        directRecord.getJSONArray("sources").getJSONObject(0).put("ekey", hash).put("url", payloadUrl)
        val directError = assertThrows(IllegalArgumentException::class.java) { ForeverClientProvisioner.validateCatalog(badDirect) }
        assertTrue(directError.message.orEmpty().contains("file-index cannot be used as a direct payload"))

        val badBinding = JSONObject(catalog.toString())
        target(badBinding).getJSONArray("sources").getJSONObject(0).put("ekey", "0".repeat(32))
        assertThrows(IllegalArgumentException::class.java) { ForeverClientProvisioner.validateCatalog(badBinding) }
    }

    @Test
    fun conflictingDecodedIdentityAndMalformedRangeTotalsFailClosed() {
        val (catalog, _) = fixtures()
        val bad = JSONObject(catalog.toString())
        target(bad).put("decoded_md5", "0".repeat(32))
        val identityError = assertThrows(IllegalArgumentException::class.java) { ForeverClientProvisioner.validateCatalog(bad) }
        assertTrue(identityError.message.orEmpty().contains("decoded MD5 does not match its content key"))
        val decoded = "range-total-fixture".toByteArray()
        val raw = "BLTE".toByteArray() + byteArrayOf(0, 0, 0, 0, 'N'.code.toByte()) + decoded
        val record = JSONObject().put("content_key", "0".repeat(32)).put("decoded_size", decoded.size)
            .put("encoded_size", raw.size).put("sources", JSONArray().put(JSONObject()
                .put("kind", "direct").put("ekey", targetKey).put("url", targetUrl)
                .put("encoded_size", raw.size).put("sha256", MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) })))
        val cache = Files.createTempDirectory("forever-cdn-total-").toFile()
        try {
            for (total in listOf("1", "*", (raw.size + 1).toString())) {
                File(cache, "$targetKey.part").writeBytes(raw.copyOfRange(0, 1))
                assertThrows(IllegalArgumentException::class.java) {
                    ForeverClientProvisioner.downloadRecordForTest(record, cache, { false }) { _, start, end, ranged ->
                        assertTrue(ranged)
                        FakeConnection(206, raw.copyOfRange(1, raw.size), "bytes $start-$end/$total")
                    }
                }
            }
            assertFalse(File(cache, targetKey).exists())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun httpFailureKeepsPartialCacheAndReportsTheExactSourceAndRange() {
        val (catalog, _) = fixtures()
        val cache = Files.createTempDirectory("forever-cdn-failure-").toFile()
        try {
            val partial = File(cache, "$targetKey.part")
            val before = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
            partial.writeBytes(before)
            val error = assertThrows(IllegalArgumentException::class.java) {
                ForeverClientProvisioner.downloadRecordForTest(target(catalog), cache, { false }) { url, start, end, ranged ->
                    assertEquals(targetUrl, url)
                    assertEquals(7L, start)
                    assertEquals(3_932_015L, end)
                    assertTrue(ranged)
                    FakeConnection(404, ByteArray(0))
                }
            }
            assertTrue(error.message.orEmpty().contains("HTTP status 404"))
            assertTrue(error.message.orEmpty().contains("kind=direct"))
            assertTrue(error.message.orEmpty().contains("url=$targetUrl"))
            assertTrue(error.message.orEmpty().contains("range=bytes=7-3932015"))
            assertEquals(before.toList(), partial.readBytes().toList())
            assertFalse(File(cache, targetKey).exists())
        } finally {
            cache.deleteRecursively()
        }
    }

    private fun fixtures(): Pair<JSONObject, JSONObject> {
        val catalogPath = System.getProperty("wowforever.catalog")
        val auditPath = System.getProperty("wowforever.cdnAudit")
        assumeTrue(catalogPath != null && auditPath != null)
        val file = File(requireNotNull(catalogPath))
        val catalog = JSONObject(file.readText())
        val audit = JSONObject(File(requireNotNull(auditPath)).readText())
        ForeverClientProvisioner.validateCatalog(catalog)
        assertEquals(sha256(file), audit.getString("catalog_sha256"))
        assertEquals("ok", audit.getString("status"))
        assertEquals(35, audit.getInt("checked_route_count"))
        assertEquals(0, audit.getInt("failure_count"))
        val routes = audit.getJSONArray("routes")
        assertEquals(35, routes.length())
        for (index in 0 until routes.length()) {
            assertEquals(206, routes.getJSONObject(index).getInt("status"))
            assertTrue(routes.getJSONObject(index).getBoolean("valid"))
        }
        val full = audit.getJSONObject("full_download")
        assertEquals(200, full.getInt("status"))
        assertEquals(targetKey, full.getString("expected_digest"))
        assertTrue(full.getBoolean("valid"))
        return Pair(catalog, audit)
    }

    private fun target(catalog: JSONObject): JSONObject {
        val loose = catalog.getJSONArray("loose_files")
        return (0 until loose.length()).map { loose.getJSONObject(it) }
            .single { it.getString("relative_path") == "Utils/discord_partner_sdk.dll" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class FakeConnection(private val status: Int, private val body: ByteArray, private val range: String? = null) : HttpURLConnection(URL("https://us.cdn.blizzard.com/")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getContentLengthLong(): Long = body.size.toLong()
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getHeaderField(name: String): String? = if (name.equals("Content-Range", true)) range else null
    }
}
