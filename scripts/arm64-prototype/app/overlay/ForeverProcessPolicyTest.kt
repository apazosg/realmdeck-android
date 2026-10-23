package app.gamenative

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeverProcessPolicyTest {
    @Test
    fun bothPinnedProbeExecutableHintsAreRetainedWithoutArbitraryArguments() {
        assertEquals(listOf("graphicsprobe-arm64.exe", "graphicsprobelog-arm64.exe", "graphicsprobereadback-arm64.exe"),
            ForeverProcessEvidencePolicy.executableHints(listOf("A:\\GraphicsProbe-ARM64.exe", "A:\\GraphicsProbeLog-ARM64.exe", "A:\\GraphicsProbeReadback-ARM64.exe", "--password=do-not-record")))
    }
    @Test
    fun moduleErrorsAndEffectiveDllOverridesAreRetainedWithoutUndeclaredChannels() {
        val method = ForeverProcessDiagnostics::class.java.getDeclaredMethod("sanitizedEnvMap", Array<String>::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val observed = method.invoke(ForeverProcessDiagnostics, arrayOf(
            "WINEDLLOVERRIDES=dxgi=n;d3dcompiler_47=b",
            "WINEDEBUG=err+module,trace+module,trace+loaddll,+relay,+snoop",
            "PASSWORD=never-capture",
        )) as Map<String, String>
        assertEquals("dxgi=n;d3dcompiler_47=b", observed["WINEDLLOVERRIDES"])
        assertEquals("err+module,trace+module,trace+loaddll", observed["WINEDEBUG"])
        assertFalse(observed.containsKey("PASSWORD"))
    }
    @Test
    fun notFoundAndIncompleteDiscoveryNeverPermitApply() {
        assertTrue(ForeverPatchPolicy.discovery("NOT_FOUND") != ForeverPatchAction.APPLY)
        assertTrue(ForeverPatchPolicy.discovery("INCOMPLETE") != ForeverPatchAction.APPLY)
        assertFalse(ForeverPatchPolicy.inspectionAllowsApply("NOT_FOUND"))
        assertFalse(ForeverPatchPolicy.inspectionAllowsApply("INCOMPLETE"))
        assertTrue(ForeverPatchPolicy.inspectionAllowsApply("VALIDATED"))
        assertTrue(ForeverPatchPolicy.discovery("NOT_FOUND") == ForeverPatchAction.RETRY_READ_ONLY)
        assertTrue(ForeverPatchPolicy.discovery("INCOMPLETE") == ForeverPatchAction.RETRY_READ_ONLY)
    }

    @Test
    fun outputOrPatchWorkerTimeoutIsUnknown() {
        assertFalse(ForeverPatchPolicy.quiescent(true, false, true, true))
        assertFalse(ForeverPatchPolicy.quiescent(true, true, false, true))
        assertFalse(ForeverPatchPolicy.quiescent(true, true, true, false))
        assertTrue(ForeverPatchPolicy.quiescent(true, true, true, true))
    }

    @Test
    fun cancelledOrOtherGenerationCannotMatchWithoutExactRunEnvironment() {
        val command = "wine A:\\WowB-ARM64.exe -config BetaSuspendedTest.wtf -d3d11"
        assertTrue(ForeverSpawnGate.matches("run-a", "WowB-ARM64.exe", command, arrayOf("WOWFOREVER_RUN_ID=run-a")))
        assertFalse(ForeverSpawnGate.matches("run-a", "WowB-ARM64.exe", command, arrayOf("WOWFOREVER_RUN_ID=run-b")))
        assertFalse(ForeverSpawnGate.matches("run-a", "WowB-ARM64.exe", command, null))
    }

    @Test
    fun emptyDiscoveryDoesNotClaimWowAbsent() {
        val evidence = ForeverProcessEvidencePolicy.gameIdentity(candidateCount = 0, eligibleCount = 0)
        assertEquals("not_observed", evidence.getString("gameIdentity"))
        assertEquals("not_available", evidence.getString("pidProof"))
        assertEquals("not_proven", evidence.getString("absence"))
    }

    @Test
    fun candidateWithoutOwnershipProofIsRejectedAndExitAttributionIsFailClosed() {
        val evidence = ForeverProcessEvidencePolicy.gameIdentity(candidateCount = 1, eligibleCount = 0)
        assertEquals("rejected", evidence.getString("pidProof"))
        assertEquals("unattributed", ForeverProcessEvidencePolicy.historicalAttribution(true, false))
        assertEquals("unattributed", ForeverProcessEvidencePolicy.historicalAttribution(false, true))
        assertEquals("launcher", ForeverProcessEvidencePolicy.historicalAttribution(true, true))
    }
}
