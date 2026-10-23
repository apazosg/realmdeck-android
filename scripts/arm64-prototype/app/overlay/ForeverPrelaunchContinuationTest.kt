package app.gamenative

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeverPrelaunchContinuationTest {
    @Test fun candidate62IsDistinctFromHistorical61Authority() {
        val candidate = ForeverPrelaunchContinuation.candidateAuthority()
        assertEquals(62, candidate.getInt("versionCode"))
        assertEquals("0.2.38-automated", candidate.getString("versionName"))
        assertEquals("arm64-v8a", candidate.getString("abi"))
        assertTrue(candidate.getBoolean("debuggable"))
        assertEquals(10330, candidate.getInt("uid"))
    }

    @Test fun exactKnownFailureAcceptsFreshBootAndRejectsUnknownVariants() {
        val contract = ForeverPrelaunchContinuation.fixed()
        val current = JSONObject().put("runId", "RUN-1791738410167-533692127338")
            .put("stage", "EXITED").put("status", "FAILED")
            .put("terminationUnknown", true).put("quiescenceConfirmed", false)
            .put("message", "filesDir is not the canonical private app root").toString().toByteArray()
        val binding = "binding-known-failure".toByteArray()
        val result = resultBytes()
        val prior = contract.getJSONObject("knownFailure")
        prior.getJSONObject("current").put("bytes", current.size).put("sha256", sha256(current))
        prior.getJSONObject("binding").put("bytes", binding.size).put("sha256", sha256(binding))
        prior.getJSONObject("wineResult").put("bytes", result.size).put("sha256", sha256(result))
        ForeverPrelaunchContinuation.verifyKnownFailure(
            contract, current, current, binding, result, "11111111-2222-4333-8444-555555555555",
        )
        assertThrows(IllegalArgumentException::class.java) {
            ForeverPrelaunchContinuation.verifyKnownFailure(contract, current, current, binding, result,
                "bc331988-3879-4cd4-b4d2-c1b616c5a2a1")
        }
        val wrong = JSONObject(String(result)).put("terminalStatus", "PASS").toString().toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            ForeverPrelaunchContinuation.verifyKnownFailure(contract, current, current, binding, wrong,
                "22222222-3333-4444-8555-666666666666")
        }
    }

    @Test fun receiptIsAbortOnlyAndNeverClaimsNativeClosure() {
        val receipt = ForeverPrelaunchContinuation.receipt(
            "RUN-old", "WS-" + "a".repeat(32), "b".repeat(32),
            "RUN-new", "WS-" + "c".repeat(32), "d".repeat(32),
            "11111111-2222-4333-8444-555555555555",
        )
        assertEquals("PRELAUNCH_ABORT", receipt.getString("kind"))
        assertEquals("PRELAUNCH_ABORT", receipt.getString("terminalStatus"))
        assertEquals("UNKNOWN", receipt.getString("nativeStatus"))
        assertFalse(receipt.getBoolean("closureObserved"))
        assertFalse(receipt.getBoolean("qualification"))
        assertFalse(receipt.getBoolean("echild"))
    }

    private fun resultBytes(): ByteArray = ("{" +
        "\"schema\":1,\"runId\":\"RUN-1791738410167-533692127338\"," +
        "\"wsRunId\":\"WS-51c1b011c946c45309229bd9d6fff681\",\"nonce\":\"f9f4da016c99beb427b44274e9ab4ef6\"," +
        "\"terminalStatus\":\"UNKNOWN\",\"nativeStatus\":\"UNKNOWN\",\"clientPass\":false," +
        "\"closureObserved\":false,\"qualification\":false,\"supervisorExit\":null,\"echild\":false," +
        "\"error\":\"filesDir is not the canonical private app root\"}").toByteArray()

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
