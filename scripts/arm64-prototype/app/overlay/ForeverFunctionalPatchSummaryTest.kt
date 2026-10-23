package app.gamenative

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ForeverFunctionalPatchSummaryTest {
    @Test
    fun countersAndMilestonesAreTypedAndNeverCopyNativePayload() {
        var now = 100L
        val output = Files.createTempDirectory("patch-summary").resolve("latest.json").toFile()
        val summary = ForeverFunctionalPatchSummary.begin(output, 100L) { now }
        now = 110L
        summary.notePhase(ForeverFunctionalPocPatchSession.PatchAttemptPhase.SEARCHING)
        now = 125L
        summary.noteScan("""{"status":"VALIDATED","error":"PRIVATE_SENTINEL","address":"PRIVATE_SENTINEL",
            "fullScan":{"scope":"APPLY_ELIGIBLE_REGIONS","validatedStoreCount":1,"passComplete":true},
            "coverage":{"actualMemoryBytesRead":512,"readErrors":2}}""")
        assertTrue(summary.snapshot().isNull("firstApplicationVerifiedMs"))
        now = 140L
        summary.noteAssistedSubmission()
        now = 150L
        summary.noteApply("""{"status":"APPLIED","bytesWritten":32,"error":"PRIVATE_SENTINEL"}""")
        val saved = awaitSummary(output) { !it.isNull("firstApplicationVerifiedMs") }
        assertEquals("APPLY_ELIGIBLE_REGIONS", saved.getString("scanScope"))
        assertEquals(10L, saved.getLong("searchStartedMs"))
        assertEquals(25L, saved.getLong("firstCandidateObservedMs"))
        assertEquals(40L, saved.getLong("assistedSubmittedMs"))
        assertEquals(50L, saved.getLong("firstApplicationVerifiedMs"))
        assertEquals(1L, saved.getLong("polls"))
        assertEquals(1L, saved.getLong("endedPasses"))
        assertEquals(512L, saved.getLong("actualMemoryBytesRead"))
        assertEquals(2L, saved.getLong("readErrors"))
        assertEquals(1L, saved.getJSONObject("nativeApplyCounts").getLong("APPLIED"))
        assertFalse(saved.toString().contains("PRIVATE_SENTINEL"))
        assertEquals(setOf("schemaVersion", "attemptId", "scanScope", "phase", "searchStartedMs",
            "firstCandidateObservedMs", "firstApplicationVerifiedMs", "assistedSubmittedMs", "polls",
            "endedPasses", "actualMemoryBytesRead", "readErrors", "nativeApplyCounts", "lastNativeApplyStatus"),
            saved.keys().asSequence().toSet())
    }

    @Test
    fun supersededCallbacksCannotReplacePublishedCurrentAttempt() {
        val output = Files.createTempDirectory("patch-summary-owner").resolve("latest.json").toFile()
        val old = ForeverFunctionalPatchSummary.begin(output, 0L) { 10L }
        val latest = ForeverFunctionalPatchSummary.begin(output, 10L) { 20L }
        latest.noteAssistedSubmission()
        val id = latest.snapshot().getString("attemptId")
        awaitSummary(output) { it.optString("attemptId") == id && !it.isNull("assistedSubmittedMs") }
        old.noteApply("""{"status":"APPLIED"}""")
        old.noteAssistedSubmission()
        latest.notePhase(ForeverFunctionalPocPatchSession.PatchAttemptPhase.ENDED)
        val saved = awaitSummary(output) { it.optString("phase") == "ENDED" }
        assertEquals(id, saved.getString("attemptId"))
        assertTrue(saved.isNull("firstApplicationVerifiedMs"))
        assertEquals(0, saved.getJSONObject("nativeApplyCounts").length())
    }

    @Test
    fun storageFailureIsBestEffortAndDoesNotPoisonLaterPublication() {
        val parentFile = File.createTempFile("patch-summary-file", ".tmp")
        val failed = ForeverFunctionalPatchSummary.begin(File(parentFile, "impossible.json"), 0L) { 10L }
        failed.noteApply("""{"status":"APPLIED"}""")
        failed.noteAssistedSubmission()
        assertEquals(10L, failed.snapshot().getLong("assistedSubmittedMs"))
        assertEquals(10L, failed.snapshot().getLong("firstApplicationVerifiedMs"))
        val output = Files.createTempDirectory("patch-summary-recovered").resolve("latest.json").toFile()
        val recovered = ForeverFunctionalPatchSummary.begin(output, 0L) { 20L }
        recovered.noteAssistedSubmission()
        assertEquals(20L, awaitSummary(output) { !it.isNull("assistedSubmittedMs") }.getLong("assistedSubmittedMs"))
    }

    @Test
    fun deniedAndUnknownResultsNeverVerifyAndLateApplyRetainsFailedPhase() {
        val output = Files.createTempDirectory("patch-summary-results").resolve("latest.json").toFile()
        val summary = ForeverFunctionalPatchSummary.begin(output, 0L) { 30L }
        summary.noteScan("PRIVATE_SENTINEL")
        assertEquals(1L, summary.snapshot().getLong("polls"))
        assertTrue(summary.snapshot().isNull("firstCandidateObservedMs"))
        summary.noteApply("""{"status":"DENIED"}""")
        summary.noteApply("""{"status":"PRIVATE_SENTINEL","error":"PRIVATE_SENTINEL"}""")
        assertTrue(summary.snapshot().isNull("firstApplicationVerifiedMs"))
        summary.notePhase(ForeverFunctionalPocPatchSession.PatchAttemptPhase.FAILED)
        summary.noteApply("""{"status":"APPLIED"}""")
        val saved = awaitSummary(output) { it.optString("lastNativeApplyStatus") == "APPLIED" }
        assertEquals("FAILED", saved.getString("phase"))
        assertEquals(30L, saved.getLong("firstApplicationVerifiedMs"))
        assertEquals(1L, saved.getJSONObject("nativeApplyCounts").getLong("UNKNOWN"))
        assertFalse(saved.toString().contains("PRIVATE_SENTINEL"))
    }

    private fun awaitSummary(output: File, ready: (JSONObject) -> Boolean): JSONObject {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val value = runCatching { JSONObject(output.readText()) }.getOrNull()
            if (value != null && ready(value)) return value
            Thread.sleep(10L)
        }
        error("summary not published")
    }
}
