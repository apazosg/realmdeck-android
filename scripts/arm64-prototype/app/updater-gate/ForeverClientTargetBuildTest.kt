package app.gamenative

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeverClientTargetBuildTest {
    @Test
    fun parsesImmutableTargetAndBindsBuildIdToVersion() {
        val first = ForeverClientTargetBuild.parse(targetJson(70425, "ea".repeat(16), "fc".repeat(16)))
        val second = ForeverClientTargetBuild.parse(targetJson(70700, "ab".repeat(16), "cd".repeat(16)))

        assertEquals("1.60.1.70425", first.version)
        assertEquals(70425L, first.buildId)
        assertEquals(43620, first.executable.expectedMachine)
        assertEquals("WowB-ARM64.exe", first.executable.path)
        assertEquals("1.60.1.70700", second.version)
        assertEquals(70700L, second.fingerprint.buildId)
        assertFalse(first.fingerprint == second.fingerprint)
        assertFalse(first.nativeActivationValidated)
    }

    @Test
    fun rejectsMalformedUtf8DuplicateKeysUnknownFieldsAndWrongBuildIdentity() {
        assertThrows(Exception::class.java) {
            ForeverClientTargetBuild.parse(byteArrayOf(0xc3.toByte(), 0x28))
        }
        val valid = String(targetJson(70425, "ea".repeat(16), "fc".repeat(16)), Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverClientTargetBuild.parse(valid.replace("\"product\":\"wow_classic_beta\"", "\"product\":\"wow_classic_beta\",\"product\":\"wow_classic_beta\"").toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverClientTargetBuild.parse(valid.replace("\"build_id\":70425", "\"build_id\":70500").toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverClientTargetBuild.parse(valid.replace("\"schema_version\":1", "\"schema_version\":1.0").toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverClientTargetBuild.parse(valid.replace("\"expected_machine\":43620", "\"expected_machine\":34404").toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverClientTargetBuild.parse(valid.replace("\"native_validation\":", "\"unexpected\":true,\"native_validation\":").toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverClientTargetBuild.parse(ByteArray(ForeverClientTargetBuild.MAX_JSON_BYTES + 1))
        }
    }

    @Test
    fun nativeActivationNeedsVerifiedStatusAndEvidenceInTheSameApkManifest() {
        val pending = ForeverClientTargetBuild.parse(targetJson(70500, "01".repeat(16), "02".repeat(16)))
        val verified = ForeverClientTargetBuild.parse(
            targetJson(70500, "01".repeat(16), "02".repeat(16), nativeStatus = "verified", evidence = "a".repeat(64)),
        )
        val missingEvidence = targetJson(70500, "01".repeat(16), "02".repeat(16), nativeStatus = "verified", evidence = "null")

        assertFalse(pending.nativeActivationValidated)
        assertTrue(verified.nativeActivationValidated)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverClientTargetBuild.parse(missingEvidence)
        }
    }

    @Test
    fun targetMetadataIsTypedAndConfigHashesAreNormalized() {
        val target = ForeverClientTargetBuild.parse(targetJson(70500, "AB".repeat(16), "CD".repeat(16)))

        assertEquals("ab".repeat(16), target.buildConfig.hash)
        assertEquals("cd".repeat(16), target.cdnConfig.hash)
        assertEquals(59_537L, target.buildConfig.bytes)
        assertEquals("wow_classic_beta", target.fingerprint.product)
        assertNotNull(target.catalog.gzipSha256)
    }

    private fun targetJson(
        buildId: Int,
        buildHash: String,
        cdnHash: String,
        nativeStatus: String = "pending",
        evidence: String = "null",
    ): ByteArray = JSONObject()
        .put("schema", "wowforever.client-target")
        .put("schema_version", 1)
        .put("product", "wow_classic_beta")
        .put("region", "us")
        .put("platform", "Windows")
        .put("architecture", "arm64")
        .put("locale", "enUS")
        .put("version", "1.60.1.$buildId")
        .put("build_id", buildId)
        .put("launcher_version_code", 80)
        .put("launcher_version_name", "test-target")
        .put("artifact_subdir", "client-updater-packaged-catalog/$buildId")
        .put("build_config", JSONObject()
            .put("hash", buildHash)
            .put("bytes", 59_537)
            .put("sha256", "48".repeat(32)))
        .put("cdn_config", JSONObject()
            .put("hash", cdnHash)
            .put("bytes", 59_257)
            .put("sha256", "56".repeat(32)))
        .put("catalog", JSONObject()
            .put("bytes", 99_647_488)
            .put("sha256", "83".repeat(32))
            .put("gzip_bytes", 63_202_489)
            .put("gzip_sha256", "92".repeat(32)))
        .put("executable", JSONObject()
            .put("path", "WowB-ARM64.exe")
            .put("decoded_bytes", 80_562_896)
            .put("content_key", "5e6f9642d56cf15dff2364a0da6a1abd")
            .put("expected_machine", 43_620))
        .put("server_version_source", "user-confirmed")
        .put("native_validation", JSONObject()
            .put("status", nativeStatus)
            .put("evidence_sha256", if (evidence == "null") JSONObject.NULL else evidence))
        .toString()
        .toByteArray(Charsets.UTF_8)
}
