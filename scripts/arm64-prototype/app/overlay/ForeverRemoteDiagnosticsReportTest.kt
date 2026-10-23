package app.gamenative

import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeverRemoteDiagnosticsReportTest {
    @Test
    fun persistedFailureAndExitSurviveReportProjectionWithoutMutation() {
        val intent = ForeverRemoteDiagnosticsStore.Intent("attempt-real", 1000L, emptyMap(),
            result = "precondition_failed", stage = "prepare", error = "IllegalStateException", endedAtMs = 2000L)
        val before = intent.copy()
        val projected = ForeverRemoteDiagnosticsReport.attemptFromIntent(intent)
        assertEquals("precondition_failed", projected.result)
        assertEquals(1000L, projected.startedAtMs)
        assertEquals(before, intent)
        val exit = ForeverRemoteDiagnosticsReport.attemptFromIntent(intent.copy(result = "wine_exit_failed", exitCode = 1))
        assertEquals(1, exit.exitCode)
        assertEquals("wine_exit_failed", exit.result)
        assertTrue(exit.terminationUnknown)
    }
    private fun baseConfig() = ForeverRemoteDiagnosticsReport.Config(
        device = mapOf("manufacturer" to "Xiaomi", "model" to "xaga", "mystery" to "dropped"),
        app = mapOf("packageName" to "com.wowforever.arm64probe", "versionName" to "0.2.93-test", "versionCode" to "117"),
        client = mapOf("build" to "1.60.1.70450", "architecture" to "arm64", "locale" to "enUS", "status" to "incomplete"),
        requested = mapOf("profileId" to "classic_forever", "resolution" to "1280x720"),
        observed = mapOf("profileId" to null, "gpuBackend" to null),
    )

    private fun inputs(attempt: ForeverRemoteDiagnosticsReport.Attempt?, comment: String = "falla al lanzar"): ForeverRemoteDiagnosticsReport.Inputs =
        ForeverRemoteDiagnosticsReport.Inputs(
            installationId = UUID.randomUUID().toString(),
            capturedAtMs = 1_769_900_000_000L,
            comment = comment,
            config = baseConfig(),
            attempt = attempt,
            events = listOf(ForeverRemoteDiagnosticsStore.TimelineEvent(1_769_900_000_000L, "launch_intended", "ok")),
            sources = listOf(
                ForeverRemoteDiagnosticsReport.Source("diagnostics", "ok", null, false, 12),
                ForeverRemoteDiagnosticsReport.Source("wine", "missing", "no journal yet", false, 0),
            ),
            attachments = listOf(
                ForeverRemoteDiagnosticsReport.Attachment("diagnostics", "{\"event\":\"launch_intended\"}"),
            ),
        )

    private fun attempt(result: String, runId: String? = "RUN-1") = ForeverRemoteDiagnosticsReport.Attempt(
        id = "attempt-1", runId = runId, status = "failed", stage = "PREFLIGHT",
        startedAtMs = 1_769_900_000_000L, endedAtMs = 1_769_900_001_000L,
        result = result, exitCode = null, terminationUnknown = false,
    )

    @Test
    fun failedPreconditionIsNotAWineFailure() {
        // Cliente incompleto antes de precondiciones: runId nulo, sin atribuir a Wine.
        val built = ForeverRemoteDiagnosticsReport.build(inputs(attempt("precondition_failed", runId = null)))
        val json = JSONObject(built.metadataJson)
        assertEquals(1, json.getInt("schema"))
        val attemptJson = json.getJSONObject("attempt")
        assertEquals("precondition_failed", attemptJson.getString("result"))
        assertTrue(attemptJson.isNull("runId"))
    }

    @Test
    fun wineFailedWowExitAndUnknownAreDistinct() {
        val wine = JSONObject(ForeverRemoteDiagnosticsReport.build(inputs(attempt("wine_failed"))).metadataJson)
        assertEquals("wine_failed", wine.getJSONObject("attempt").getString("result"))

        val exit = attempt("wow_exit").copy(exitCode = 1)
        val wow = JSONObject(ForeverRemoteDiagnosticsReport.build(inputs(exit)).metadataJson)
        assertEquals("wow_exit", wow.getJSONObject("attempt").getString("result"))
        assertEquals(1, wow.getJSONObject("attempt").getInt("exitCode"))

        val unknown = attempt("termination_unknown").copy(terminationUnknown = true)
        val unknownJson = JSONObject(ForeverRemoteDiagnosticsReport.build(inputs(unknown)).metadataJson)
        assertEquals("termination_unknown", unknownJson.getJSONObject("attempt").getString("result"))
        assertTrue(unknownJson.getJSONObject("attempt").getBoolean("terminationUnknown"))
    }

    @Test
    fun unknownKeysAreDroppedAndMissingStaysNull() {
        val json = JSONObject(ForeverRemoteDiagnosticsReport.build(inputs(attempt("unknown"))).metadataJson)
        val device = json.getJSONObject("config").getJSONObject("device")
        assertFalse(device.has("mystery"))
        assertEquals("Xiaomi", device.getString("manufacturer"))
        val observed = json.getJSONObject("config").getJSONObject("observed")
        assertTrue(observed.isNull("gpuBackend"))
    }

    @Test
    fun oversizedAttachmentIsTruncatedWithMarker() {
        val big = "y".repeat(9 * 1024 * 1024)
        val (cut, truncated) = ForeverRemoteDiagnosticsReport.truncateAttachment(big, 8 * 1024 * 1024)
        assertTrue(truncated)
        assertTrue(cut.contains("output truncated"))
        assertTrue(cut.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024)
    }

    @Test
    fun totalFitsWithin24MiBByTruncatingInOrder() {
        val eight = "q".repeat(8 * 1024 * 1024)
        val full = inputs(attempt("unknown")).copy(
            attachments = listOf("diagnostics", "stdout", "stderr", "wine").map {
                ForeverRemoteDiagnosticsReport.Attachment(it, eight)
            },
        )
        val built = ForeverRemoteDiagnosticsReport.build(full)
        // 8+8+8MiB llenan el total; el cuarto no cabe y se omite con motivo en fuentes.
        assertEquals(3, built.attachments.size)
        val total = built.attachments.sumOf { it.bytes }
        assertEquals(24 * 1024 * 1024, total)
    }

    @Test
    fun metadataStaysWithin256KiB() {
        val bigComment = "z".repeat(300 * 1024)
        val built = ForeverRemoteDiagnosticsReport.build(inputs(attempt("unknown"), comment = bigComment))
        assertTrue(built.metadataJson.toByteArray(Charsets.UTF_8).size <= 256 * 1024)
    }

    @Test
    fun attemptResultMappingUsesEvidenceOnly() {
        // Sin exitCode: la terminación no confirmada se comprueba ANTES de inferir.
        assertEquals("precondition_failed", ForeverRemoteDiagnosticsReport.attemptResult(false, false, false, false, false, false))
        assertEquals("wine_failed", ForeverRemoteDiagnosticsReport.attemptResult(true, false, false, false, false, false))
        assertEquals("wow_not_created", ForeverRemoteDiagnosticsReport.attemptResult(true, true, false, true, false, false))
        assertEquals("wow_exit", ForeverRemoteDiagnosticsReport.attemptResult(true, true, true, true, false, false))
        assertEquals("termination_unknown", ForeverRemoteDiagnosticsReport.attemptResult(true, true, true, true, true, false))
        assertEquals("cancelled", ForeverRemoteDiagnosticsReport.attemptResult(true, false, false, false, false, true))
        assertEquals("unknown", ForeverRemoteDiagnosticsReport.attemptResult(true, true, false, false, false, false))
        // Interrupción con Wine desconocido nunca se etiqueta como salida ni wow_not_created.
        assertEquals("termination_unknown", ForeverRemoteDiagnosticsReport.attemptResult(true, true, false, false, true, false))
        assertEquals("termination_unknown", ForeverRemoteDiagnosticsReport.attemptResult(true, true, false, true, true, false))
    }

    @Test
    fun builtAttachmentCarriesTruncatedTextAndOrFlag() {
        val big = "w".repeat(9 * 1024 * 1024)
        val full = inputs(attempt("unknown")).copy(
            attachments = listOf(ForeverRemoteDiagnosticsReport.Attachment("diagnostics", big)),
            sources = listOf(ForeverRemoteDiagnosticsReport.Source("diagnostics", "ok", null, false, big.length)),
        )
        val built = ForeverRemoteDiagnosticsReport.build(full)
        assertEquals(1, built.attachments.size)
        val text = built.attachments.single().text
        assertTrue(text.contains("output truncated"))
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024)
        val json = JSONObject(built.metadataJson)
        val source = json.getJSONArray("sources").getJSONObject(0)
        // La bandera es OR de captura y constructor, nunca se sobrescribe a false.
        assertTrue(source.getBoolean("truncated"))
        val manifest = json.getJSONArray("attachments").getJSONObject(0)
        assertEquals(text.toByteArray(Charsets.UTF_8).size, manifest.getInt("bytes"))
        assertEquals(ForeverRemoteDiagnosticsReport.sha256Hex(text.toByteArray(Charsets.UTF_8)), manifest.getString("sha256"))
    }
}
