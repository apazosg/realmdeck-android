package app.gamenative

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ForeverRemoteDiagnosticsStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun filesDir(): File = File(temporary.root, "files").apply { mkdirs() }

    @Test
    fun installationIdIsStableAndRandom() {
        val first = ForeverRemoteDiagnosticsStore.installationId(filesDir())
        val second = ForeverRemoteDiagnosticsStore.installationId(filesDir())
        assertEquals(first, second)
        // UUID v4 textual, no hardware ni seriales.
        assertTrue(first.matches(Regex("[0-9a-f-]{36}")))
    }

    @Test
    fun retryConservesDraftIdsAndChangingCommentCreatesNewDraft() {
        val files = filesDir()
        val draft = ForeverRemoteDiagnosticsStore.newDraft(files, "falla wine")
        val reread = ForeverRemoteDiagnosticsStore.readDraft(files, draft.draftId)
        assertNotNull(reread)
        assertEquals(draft.draftId, reread!!.draftId)
        assertEquals(draft.capability, reread.capability)
        assertEquals(32, java.util.Base64.getUrlDecoder().decode(draft.capability).size)

        val second = ForeverRemoteDiagnosticsStore.newDraft(files, "falla wine con más detalle")
        assertNotEquals(draft.draftId, second.draftId)
        assertNotEquals(draft.capability, second.capability)
    }

    @Test
    fun draftsRotateKeepingOnlyRecent() {
        val files = filesDir()
        repeat(ForeverRemoteDiagnosticsContract.DRAFTS_RETAINED + 2) { index ->
            Thread.sleep(2)
            ForeverRemoteDiagnosticsStore.newDraft(files, "borrador $index")
        }
        val remaining = File(File(files, "forever"), "remote-diagnostics")
            .listFiles { file -> file.name.startsWith("draft-") }?.size ?: 0
        assertEquals(ForeverRemoteDiagnosticsContract.DRAFTS_RETAINED, remaining)
    }

    @Test
    fun failedDraftWithPayloadSurvivesRestartForRetry() {
        val files = filesDir()
        val draft = ForeverRemoteDiagnosticsStore.newDraft(files, "conservar ante error")
        // Sin payload no es reintentable: el snapshot exacto es obligatorio.
        assertNull(ForeverRemoteDiagnosticsStore.latestFailedDraft(files))
        val payload = ForeverRemoteDiagnosticsStore.Payload(
            metadataJson = "{\"schema\":1}",
            attachments = listOf("diagnostics" to "texto saneado"),
        )
        ForeverRemoteDiagnosticsStore.writePayload(files, draft.draftId, payload)
        // Simula rearranque: mismos bytes/hash/IDs disponibles.
        val recovered = ForeverRemoteDiagnosticsStore.latestFailedDraft(files)
        assertNotNull(recovered)
        assertEquals(draft.draftId, recovered!!.draftId)
        assertEquals(draft.capability, recovered.capability)
        val reread = ForeverRemoteDiagnosticsStore.readPayload(files, draft.draftId)
        assertEquals("{\"schema\":1}", reread!!.metadataJson)
        assertEquals(listOf("diagnostics" to "texto saneado"), reread.attachments)

        ForeverRemoteDiagnosticsStore.writeDraft(files, draft.copy(reportId = "r_123"))
        assertNull(ForeverRemoteDiagnosticsStore.latestFailedDraft(files))
    }

    @Test
    fun timelineEnforcesExactWireVocabulary() {
        val files = filesDir()
        ForeverRemoteDiagnosticsStore.record(files, "launch", "pending")
        ForeverRemoteDiagnosticsStore.record(files, "precondition-failed", "failed")
        // Fuera del wire se descarta: acciones inventadas, outcomes con datos.
        ForeverRemoteDiagnosticsStore.record(files, "key_press_a", "ok")
        ForeverRemoteDiagnosticsStore.record(files, "launch", "managed")
        ForeverRemoteDiagnosticsStore.record(files, "launch", "user@example.com")
        assertTrue(ForeverRemoteDiagnosticsStore.flush())
        val events = ForeverRemoteDiagnosticsStore.readTimeline(files)
        assertEquals(2, events.size)
        assertEquals("launch", events[0].action)
        assertEquals("pending", events[0].outcome)
        assertEquals("precondition-failed", events[1].action)
        assertEquals("failed", events[1].outcome)
    }

    @Test
    fun timelineRotatesAndRecovers() {
        val files = filesDir()
        repeat(ForeverRemoteDiagnosticsContract.EVENTS_MAX + 20) { index ->
            ForeverRemoteDiagnosticsStore.recordSync(files, "screen", "ok")
        }
        val events = ForeverRemoteDiagnosticsStore.readTimeline(files)
        assertEquals(ForeverRemoteDiagnosticsContract.EVENTS_MAX, events.size)
        assertTrue(events.all { it.action == "screen" && it.outcome == "ok" })
    }

    @Test
    fun intentFreezeLogWhitelistsFileNames() {
        val files = filesDir()
        val intent = ForeverRemoteDiagnosticsStore.Intent(
            attemptId = "attempt-7",
            atMs = 1000L,
            requested = mapOf("profileId" to "classic_forever"),
        )
        ForeverRemoteDiagnosticsStore.persistIntent(files, intent)
        // Nombre ajeno o con ruta: rechazado, sin tocar la intención.
        assertFalse(ForeverRemoteDiagnosticsStore.freezeIntentLog(files, "attempt-7", "../evil.jsonl"))
        assertFalse(ForeverRemoteDiagnosticsStore.freezeIntentLog(files, "attempt-7", "other.txt"))
        assertFalse(ForeverRemoteDiagnosticsStore.freezeIntentLog(files, "other-attempt", "run-3.jsonl"))
        assertTrue(ForeverRemoteDiagnosticsStore.freezeIntentLog(files, "attempt-7", "run-42.jsonl"))
        val reread = ForeverRemoteDiagnosticsStore.readLatestIntent(files)
        assertEquals("run-42.jsonl", reread!!.logFile)
        assertEquals("classic_forever", reread.requested["profileId"])
    }

    @Test
    fun reusableDraftRequiresSameCommentAndBytes() {
        val files = filesDir()
        val payload = ForeverRemoteDiagnosticsStore.Payload(
            metadataJson = "{\"schema\":1}",
            attachments = listOf("diagnostics" to "texto"),
            attemptId = "attempt-1",
        )
        val draft = ForeverRemoteDiagnosticsStore.mintDraftWithPayload(files, "mismo comentario", payload)
        // Mismo comentario y bytes: reutiliza IDs.
        val reused = ForeverRemoteDiagnosticsStore.resolveReusableDraft(files, "mismo comentario", payload)
        assertEquals(draft.draftId, reused!!.draftId)
        assertEquals(draft.capability, reused.capability)
        // Comentario distinto o bytes distintos: borrador nuevo.
        assertEquals(null, ForeverRemoteDiagnosticsStore.resolveReusableDraft(files, "otro comentario", payload))
        val changed = payload.copy(metadataJson = "{\"schema\":1,\"x\":2}")
        assertEquals(null, ForeverRemoteDiagnosticsStore.resolveReusableDraft(files, "mismo comentario", changed))
    }

    @Test
    fun observedExitCodeIsNeverOverwrittenByUnknown() {
        val files = filesDir()
        ForeverRemoteDiagnosticsStore.persistIntent(
            files,
            ForeverRemoteDiagnosticsStore.Intent("attempt-X", 1L, mapOf("profileId" to "classic_forever")),
        )
        // finished() del callback real: exitCode + fin observados.
        ForeverRemoteDiagnosticsStore.updateIntent(
            files, "attempt-X", result = "wine_exit", stage = "running",
            endedAtMs = 3000L, exitCode = 1)
        // onExit posterior de la Activity: no pisa lo observado.
        ForeverRemoteDiagnosticsStore.updateIntent(
            files, "attempt-X", result = "unknown", stage = "running",
            endedAtMs = 4000L, onlyIfEmpty = true)
        val kept = ForeverRemoteDiagnosticsStore.readLatestIntent(files)
        assertEquals("wine_exit", kept!!.result)
        assertEquals(1, kept.exitCode)
        assertEquals(3000L, kept.endedAtMs)
        // Sin onlyIfEmpty sí actualiza (prepare inicial, errores frescos).
        ForeverRemoteDiagnosticsStore.updateIntent(
            files, "attempt-X", result = "unknown", stage = "prepare")
        val updated = ForeverRemoteDiagnosticsStore.readLatestIntent(files)
        assertEquals("unknown", updated!!.result)
        assertEquals(1, updated.exitCode)
    }

    @Test
    fun intentUpdatesOnlyTouchMatchingAttempt() {
        val files = filesDir()
        ForeverRemoteDiagnosticsStore.persistIntent(
            files,
            ForeverRemoteDiagnosticsStore.Intent("attempt-A", 1L, mapOf("profileId" to "classic_forever")),
        )
        ForeverRemoteDiagnosticsStore.updateIntent(files, "attempt-B", result = "unknown")
        assertEquals(null, ForeverRemoteDiagnosticsStore.readLatestIntent(files)!!.result)
        ForeverRemoteDiagnosticsStore.updateIntent(
            files, "attempt-A", result = "unknown", stage = "prepare",
            error = "IllegalStateException", endedAtMs = 2000L)
        val reread = ForeverRemoteDiagnosticsStore.readLatestIntent(files)
        assertEquals("unknown", reread!!.result)
        assertEquals("prepare", reread.stage)
        assertEquals("IllegalStateException", reread.error)
        assertEquals(2000L, reread.endedAtMs)
    }

    @Test
    fun intentPersistsFrozenRequestWithResult() {
        val files = filesDir()
        val intent = ForeverRemoteDiagnosticsStore.Intent(
            attemptId = "attempt-1",
            atMs = 1000L,
            requested = mapOf("profileId" to "classic_forever", "resolution" to "1280x720"),
        )
        ForeverRemoteDiagnosticsStore.persistIntent(files, intent)
        // Los hooks existentes actualizan resultado/etapa sin competir.
        ForeverRemoteDiagnosticsStore.persistIntent(files, intent.copy(result = "precondition_failed", stage = "PREFLIGHT"))
        val reread = ForeverRemoteDiagnosticsStore.readLatestIntent(files)
        assertNotNull(reread)
        assertEquals("attempt-1", reread!!.attemptId)
        assertEquals("classic_forever", reread.requested["profileId"])
        assertEquals("precondition_failed", reread.result)
        assertEquals("PREFLIGHT", reread.stage)
    }
}
