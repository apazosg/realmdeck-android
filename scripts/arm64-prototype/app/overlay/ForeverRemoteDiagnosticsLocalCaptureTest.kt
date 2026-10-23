package app.gamenative

import java.io.File
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ForeverRemoteDiagnosticsLocalCaptureTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun envelope(runId: String, stream: String, head: ByteArray, tail: ByteArray, total: Long): String {
        require(head.size.toLong() == minOf(total, BoundedLogRetention.HEAD_LIMIT.toLong()))
        require(tail.size.toLong() == minOf(maxOf(0L, total - BoundedLogRetention.HEAD_LIMIT), BoundedLogRetention.TAIL_LIMIT.toLong()))
        return JSONObject()
            .put("schema", 1).put("runId", runId).put("stream", stream)
            .put("totalBytes", total).put("headBytes", head.size)
            .put("tailStart", total - tail.size).put("tailBytes", tail.size)
            .put("truncated", total > BoundedLogRetention.HEAD_LIMIT + BoundedLogRetention.TAIL_LIMIT)
            .put("capturedAtMs", 1L).put("flushedAtMs", 2L)
            .put("headBase64", Base64.getEncoder().encodeToString(head))
            .put("tailBase64", Base64.getEncoder().encodeToString(tail))
            .toString()
    }

    @Test
    fun streamSnapshotsAreDecodedNeverRawBase64() {
        val files = temporary.newFolder("files")
        val runs = File(File(files, "forever"), "runs").apply { mkdirs() }
        File(runs, "RUN-9-diagnostics.jsonl").writeText("{\"event\":\"launch\",\"other\":\"x\"}\n")
        val output = File(runs, "RUN-9").apply { mkdirs() }
        // Secreto oculto DENTRO del base64: el saneador debe verlo tras decodificar.
        val secret = "password=hunter2\n"
        val head = ByteArray(BoundedLogRetention.HEAD_LIMIT) { 'a'.code.toByte() }
        secret.toByteArray().copyInto(head)
        val tailText = "tail line\n"
        val tail = tailText.toByteArray()
        val total = head.size.toLong() + tail.size
        File(output, "RUN-9-stdout-snapshot.json").writeText(envelope("RUN-9", "stdout", head, tail, total))
        File(files, "functional-poc").apply { mkdirs() }
        File(File(files, "functional-poc"), "run-1.jsonl").writeText("{\"event\":\"prepared\"}\n")

        val captured = ForeverRemoteDiagnosticsLocalCapture.capture(files, "RUN-9", gameObserved = true)
        val byName = captured.associateBy { it.name }
        val stdout = byName["stdout"]!!
        assertTrue(stdout.text.contains("tail line"))
        // Decodificado como texto plano (el saneado lo hace el pegamento antes de red).
        assertTrue(stdout.text.contains("hunter2"))
        val redacted = ForeverRemoteDiagnosticsSanitizer.sanitizeText(stdout.text).text
        assertFalse(redacted.contains("hunter2"))
        assertTrue(redacted.contains("[redacted]"))
        // Ni el base64 crudo del envoltorio llega al informe.
        assertFalse(stdout.text.contains(Base64.getEncoder().encodeToString(secret.toByteArray()).take(12)))
        assertEquals(null, byName["diagnostics"]!!.missingReason)
        // An unrelated latest PoC log is deliberately not included.
        assertEquals("", byName["launcher"]!!.text)
        assertTrue(byName["wine"]!!.missingReason!!.contains("no wine lines"))
        assertTrue(byName["wow"]!!.missingReason!!.isNotEmpty())
    }

    @Test
    fun invalidEnvelopeIsMissingNotSent() {
        val files = temporary.newFolder("filesA")
        val runs = File(File(files, "forever"), "runs").apply { mkdirs() }
        val output = File(runs, "RUN-1").apply { mkdirs() }
        File(output, "RUN-1-stdout-snapshot.json").writeText("{\"schema\":1}")
        val captured = ForeverRemoteDiagnosticsLocalCapture.capture(files, "RUN-1")
        assertEquals("", captured.first { it.name == "stdout" }.text)
        assertTrue(captured.first { it.name == "stdout" }.missingReason!!.isNotEmpty())
    }

    @Test
    fun missingRunReportsPreconditionReasons() {
        val files = temporary.newFolder("files2")
        val captured = ForeverRemoteDiagnosticsLocalCapture.capture(files, null)
        val byName = captured.associateBy { it.name }
        assertEquals("", byName["diagnostics"]!!.text)
        assertTrue(byName["diagnostics"]!!.missingReason!!.contains("no durable launch intent"))
        assertTrue(byName["launcher"]!!.missingReason!!.isNotEmpty())
    }

    @Test
    fun unsafeRunIdIsRejected() {
        val files = temporary.newFolder("files3")
        val captured = ForeverRemoteDiagnosticsLocalCapture.capture(files, "../evil")
        assertEquals(1, captured.size)
        assertTrue(captured.single().missingReason!!.contains("unsafe"))
    }

    @Test
    fun largeFilesAreReadBounded() {
        val files = temporary.newFolder("files4")
        val big = File(files, "big.jsonl")
        big.outputStream().use { out ->
            val block = ByteArray(64 * 1024) { 'a'.code.toByte() }
            repeat(48) { out.write(block) }
        }
        val (text, truncated) = ForeverRemoteDiagnosticsLocalCapture.readTail(big)
        assertTrue(truncated)
        assertTrue(text.contains("capture truncated"))
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= ForeverRemoteDiagnosticsLocalCapture.SOURCE_CAP_BYTES)
    }

    @Test
    fun noSecretSourcesAreRead() {
        val files = temporary.newFolder("files5")
        val vault = File(File(files, "no_backup"), "forever-autologin").apply { mkdirs() }
        File(vault, "credentials.bin").writeBytes(ByteArray(64) { 7 })
        val captured = ForeverRemoteDiagnosticsLocalCapture.capture(files, null)
        assertFalse(captured.any { it.text.contains("credentials") || it.name.contains("autologin") })
        assertTrue(vault.exists())
    }
}
