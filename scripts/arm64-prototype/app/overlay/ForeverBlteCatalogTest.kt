package app.gamenative

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Cache-only compatibility with independently decoded, pinned CDN objects. */
class ForeverBlteCatalogTest {
    @Test
    fun everyPinnedRuntimeObjectDecodesWithTheAndroidReader() {
        val fixture = realFixture() ?: return
        val catalogFile = File(requireNotNull(System.getProperty("wowforever.catalog")))
        assertEquals(fixture.getString("catalog_sha256"), digest(catalogFile, "SHA-256"))
        val catalog = JSONObject(catalogFile.readText())
        ForeverClientProvisioner.validateCatalog(catalog)
        val records = catalog.getJSONObject("casc_bootstrap").getJSONArray("objects")
        val requiredRoles = setOf("root", "vfs-root", "encoding", "install", "download", "size")
        // The catalog also retains optional patch-index provenance. Like the
        // provisioner, this audit selects only the clean-install runtime set.
        val expected = (0 until records.length()).map { records.getJSONObject(it) }
            .filter { record ->
                val roles = record.getJSONArray("roles")
                (0 until roles.length()).any { roles.getString(it) in requiredRoles || roles.getString(it).startsWith("vfs-") }
            }.associateBy { it.getJSONArray("encoding_keys").getString(0) }
        val objects = fixture.getJSONArray("objects")
        assertEquals(832, expected.size)
        assertEquals(expected.size, objects.length())
        assertEquals(expected.size, fixture.getInt("verified_source_count"))
        val seen = HashSet<String>()
        for (index in 0 until objects.length()) {
            val item = objects.getJSONObject(index)
            val ekey = item.getString("ekey")
            assertTrue("duplicate fixture EKey: $ekey", seen.add(ekey))
            val record = requireNotNull(expected[ekey]) { "fixture EKey is not in the catalog: $ekey" }
            val source = record.getJSONArray("sources").getJSONObject(0)
            val raw = File(item.getString("source_path"))
            assertEquals("encoded size: $ekey", record.getLong("encoded_size"), raw.length())
            assertEquals("fixture size: $ekey", item.getLong("encoded_size"), raw.length())
            val actualSha = digest(raw, "SHA-256")
            assertEquals("catalog source SHA: $ekey", source.getString("sha256"), actualSha)
            assertEquals("fixture SHA: $ekey", item.getString("raw_sha256"), actualSha)
            assertEquals("content key: $ekey", record.getString("content_key"), item.getString("ckey"))
            assertEquals("decoded MD5: $ekey", item.getString("ckey"), item.getString("decoded_md5"))
            assertEquals("decoded size: $ekey", record.getLong("decoded_size"), item.getLong("decoded_size"))
            // This is the exact streaming reader used by the APK, not cascette.
            // It validates every chunk, the final decoded length and CKey MD5.
            ForeverBlte.verify(raw, record.getLong("decoded_size"), record.getString("content_key"))
        }
        assertEquals(expected.keys, seen)
    }

    @Test
    fun reportedObjectRejectsEncodedSizeAndAcceptsOnlyVerifiedDecodedSize() {
        val fixture = realFixture() ?: return
        val objects = fixture.getJSONArray("objects")
        val item = (0 until objects.length()).map { objects.getJSONObject(it) }
            .single { it.getString("ekey") == "bc0fe26fca9629c46c33c478077ecd4d" }
        val raw = File(item.getString("source_path"))
        assertEquals(181L, raw.length())
        assertEquals(154L, item.getLong("decoded_size"))
        val ckey = "001edd43c5b3baa939ebbff41d750e1a"
        val oldCatalogFailure = assertThrows(IllegalArgumentException::class.java) {
            ForeverBlte.verify(raw, 181, ckey)
        }
        assertEquals("BLTE decoded size mismatch: 154 != 181", oldCatalogFailure.message)
        ForeverBlte.verify(raw, 154, ckey)
        val wrongKey = assertThrows(IllegalArgumentException::class.java) {
            ForeverBlte.verify(raw, 154, "0".repeat(32))
        }
        assertEquals("BLTE content key mismatch", wrongKey.message)
    }

    private fun realFixture(): JSONObject? {
        val path = System.getProperty("wowforever.runtimeAudit")
        assumeTrue("cache-only real CDN compatibility is opt-in", path != null)
        if (path == null) return null
        return JSONObject(File(path).readText()).also {
            assertEquals("wowforever.arm64-client-runtime-audit", it.getString("schema"))
            assertEquals(1, it.getInt("schema_version"))
            assertEquals(832, it.getInt("required_object_count"))
        }
    }

    private fun digest(file: File, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count > 0) md.update(buffer, 0, count)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
