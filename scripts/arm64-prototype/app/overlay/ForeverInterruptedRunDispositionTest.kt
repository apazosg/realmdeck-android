package app.gamenative

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeverInterruptedRunDispositionTest {
    @Test fun receiptContractNeverClaimsClosureOrQualification() {
        val receipt = ForeverInterruptedRunDisposition.fixed().getJSONObject("receipt")
        assertEquals("ABANDONED_ON_NEW_BOOT", receipt.getString("terminalStatus"))
        assertEquals("UNKNOWN", receipt.getString("nativeStatus"))
        assertFalse(receipt.getBoolean("closureObserved"))
        assertFalse(receipt.getBoolean("qualification"))
        assertFalse(receipt.getBoolean("echild"))
    }

    @Test fun wrongBootIsRejectedBeforeAdmission() {
        val directory = Files.createTempDirectory("disposition-contract").toFile()
        try {
            val current = ByteArray(575) { 1 }
            val run = current.copyOf()
            val binding = ByteArray(207) { 2 }
            val contract = ForeverInterruptedRunDisposition.fixed()
            assertThrows(IllegalArgumentException::class.java) {
                ForeverInterruptedRunDisposition.verifyAdmission(contract, current, run, binding,
                    "06254205-1db4-4aaa-8f4e-7123db10f851")
            }
            assertTrue(directory.isDirectory)
        } finally { directory.deleteRecursively() }
    }

    @Test fun exactBytesWithFreshBootStillRejectWrongBinding() {
        val contract = ForeverInterruptedRunDisposition.fixed()
        val current = "sealed current\n".toByteArray()
        val binding = "sealed binding\n".toByteArray()
        contract.getJSONObject("prior").apply {
            getJSONObject("current").put("bytes", current.size).put("sha256", sha256(current))
            getJSONObject("binding").put("bytes", binding.size).put("sha256", sha256(binding))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverInterruptedRunDisposition.verifyAdmission(
                contract, current, current, "sealed bindinX\n".toByteArray(),
                "11111111-2222-4333-8444-555555555555",
            )
        }
    }

    @Test fun candidateAuthorityPinsVersionAbiDebugCertificateAndSdkBounds() {
        val candidate = ForeverInterruptedRunDisposition.candidateAuthority()
        assertEquals("com.wowforever.arm64probe", candidate.getString("package"))
        assertEquals(61, candidate.getInt("versionCode"))
        assertEquals("0.2.37-automated", candidate.getString("versionName"))
        assertEquals("arm64-v8a", candidate.getString("abi"))
        assertEquals(34, candidate.getInt("api"))
        assertEquals(10330, candidate.getInt("uid"))
        assertTrue(candidate.getBoolean("debuggable"))
        assertEquals(29, candidate.getInt("minSdk"))
        assertEquals(36, candidate.getInt("targetSdk"))
        assertEquals(
            "2b082e8200e066e665f73e71f547f827d6674634298024cb0b823be22adb242b",
            candidate.getString("certificateSha256"),
        )
    }

    @Test fun malformedRunWsNonceAndWrongAuthorityBytesAreRejected() {
        val freshBoot = "11111111-2222-4333-8444-555555555555"
        listOf(
            "runId" to "not-a-run",
            "wsRunId" to "not-a-ws-run",
            "nonce" to "not-a-nonce",
        ).forEach { (field, value) ->
            val contract = ForeverInterruptedRunDisposition.fixed()
            contract.getJSONObject("prior").put(field, value)
            assertThrows(IllegalArgumentException::class.java) {
                ForeverInterruptedRunDisposition.verifyAdmission(
                    contract, ByteArray(575), ByteArray(575), ByteArray(207), freshBoot,
                )
            }
        }
        val contract = ForeverInterruptedRunDisposition.fixed()
        assertThrows(IllegalArgumentException::class.java) {
            ForeverInterruptedRunDisposition.verifyAdmission(
                contract, ByteArray(575) { 1 }, ByteArray(575) { 1 }, ByteArray(207) { 2 }, freshBoot,
            )
        }
    }

    @Test fun receiptShapeIsStableAndNotAnOldRunState() {
        val value = ForeverInterruptedRunDisposition.fixed()
        assertEquals("RUN-1791718684192-65783495695", value.getJSONObject("prior").getString("runId"))
        assertEquals("host-capture-interruption", value.getJSONObject("receipt").getString("reason"))
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
