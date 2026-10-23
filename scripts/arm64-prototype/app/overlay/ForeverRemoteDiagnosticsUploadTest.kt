package app.gamenative

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ForeverRemoteDiagnosticsUploadTest {
    private class FakeConnection(url: URL, var status: Int, var body: String, var errorBody: String = "") : HttpURLConnection(url) {
        val sentHeaders = mutableMapOf<String, String>()
        val sentBody = ByteArrayOutputStream()
        var redirectCount = 0
        override fun connect() {
            connected = true
        }
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
        override fun setRequestProperty(key: String, value: String) {
            sentHeaders[key] = value
        }
        override fun getOutputStream() = sentBody
        override fun getInputStream(): InputStream =
            if (status in 200..299) body.byteInputStream() else throw IOException("no input")
        override fun getErrorStream(): InputStream? =
            if (status !in 200..299 && errorBody.isNotEmpty()) errorBody.byteInputStream() else null
        override fun getResponseCode(): Int {
            if (status in 300..399) redirectCount++
            return status
        }
    }

    private class FakeFactory(responses: List<FakeConnection>) : ForeverRemoteDiagnosticsUpload.ConnectionFactory {
        val queue = ArrayDeque(responses)
        val opened = mutableListOf<String>()
        val used = mutableListOf<FakeConnection>()
        override fun open(url: String): HttpURLConnection {
            opened.add(url)
            val next = queue.removeFirst()
            used.add(next)
            return next
        }
    }

    private fun createdBody(): String = """
        {"id":"r_abc123","state":"uploading","expiresAt":"2030-01-01T00:00:00Z",
        "uploads":[{"name":"diagnostics","url":"https://vercel.com/api/blob/?pathname=abc123&token=signed-fields","contentType":"text/plain; charset=utf-8"}]}
    """.trimIndent()

    private fun blobUrl() = "https://vercel.com/api/blob/?pathname=abc123&token=signed-fields&expiry=3600"

    @Test
    fun createSendsIdempotencyKeyAndBearer() {
        val connection = FakeConnection(URL("https://wowforever-diagnostics.vercel.app/api/reports"), 200, createdBody())
        val factory = FakeFactory(listOf(connection))
        val created = ForeverRemoteDiagnosticsUpload.createReport("{}", "draft-1", "CAPABILITY_ABC", factory)
        assertEquals("r_abc123", created.id)
        assertEquals("draft-1", connection.sentHeaders["Idempotency-Key"])
        assertEquals("Bearer CAPABILITY_ABC", connection.sentHeaders["Authorization"])
    }

    @Test
    fun retryReusesDraftAndCapabilityWithoutNewReport() {
        val first = FakeConnection(URL("https://wowforever-diagnostics.vercel.app/api/reports"), 200, createdBody())
        val second = FakeConnection(URL("https://wowforever-diagnostics.vercel.app/api/reports"), 200, createdBody())
        val factory = FakeFactory(listOf(first, second))
        ForeverRemoteDiagnosticsUpload.createReport("{}", "draft-same", "CAP_SAME", factory)
        ForeverRemoteDiagnosticsUpload.createReport("{}", "draft-same", "CAP_SAME", factory)
        assertEquals(listOf("draft-same", "draft-same"), factory.used.map { it.sentHeaders["Idempotency-Key"] })
        assertEquals(listOf("Bearer CAP_SAME", "Bearer CAP_SAME"), factory.used.map { it.sentHeaders["Authorization"] })
    }

    @Test
    fun redirectsAreRejectedAndSecretsNeverSurface() {
        val capability = "CAP_SUPER_SECRET_99"
        val connection = FakeConnection(URL("https://wowforever-diagnostics.vercel.app/api/reports"), 307, "", "busy")
        val factory = FakeFactory(listOf(connection))
        try {
            ForeverRemoteDiagnosticsUpload.createReport("{}", "draft-x", capability, factory)
            fail("redirect must fail")
        } catch (failure: ForeverRemoteDiagnosticsUpload.Failure) {
            assertFalse(failure.message!!.contains(capability))
            assertFalse(failure.message!!.contains("wowforever-diagnostics"))
        }
    }

    @Test
    fun blobHostValidationRejectsUnexpectedTargets() {
        // MIME del wire: text/plain; charset=utf-8, sin cambios.
        assertEquals("text/plain; charset=utf-8", ForeverRemoteDiagnosticsContract.CONTENT_TYPE)
        ForeverRemoteDiagnosticsUpload.validateBlobUrl(blobUrl())
        for (bad in listOf(
            "http://vercel.com/api/blob/abc?token=x",
            "https://evil.example.com/api/blob/abc?token=x",
            "https://x.blob.vercel-storage.com/o/1",
            "https://vercel.com.evil.com/api/blob/abc?token=x",
            "https://user@vercel.com/api/blob/abc?token=x",
            "https://vercel.com/other/path?token=x",
            "https://vercel.com/api/blob/abc",
            "https://127.0.0.1/api/blob/abc?token=x",
            "not-a-url",
        )) {
            try {
                ForeverRemoteDiagnosticsUpload.validateBlobUrl(bad)
                fail("must reject $bad")
            } catch (_: RuntimeException) {
                // Esperado: require (IllegalArgumentException) o Failure (IllegalStateException).
            }
        }
    }

    @Test
    fun errorBodiesNeverReflectTokensOrSignedUrls() {
        val capability = "CAP_NEVER_LOGGED"
        val signed = blobUrl()
        val blob = FakeConnection(URL(signed), 500, "", "capability=$capability url=$signed trace SELECT * FROM reports")
        val factory = FakeFactory(listOf(blob))
        try {
            ForeverRemoteDiagnosticsUpload.putBlob(
                ForeverRemoteDiagnosticsUpload.BlobTarget("diagnostics", signed, "text/plain; charset=utf-8"),
                "{}", factory,
            )
            fail("500 must fail")
        } catch (failure: ForeverRemoteDiagnosticsUpload.Failure) {
            assertFalse(failure.message!!.contains(capability))
            assertFalse(failure.message!!.contains(signed))
            assertFalse(failure.message!!.contains("SELECT"))
        }
    }

    @Test
    fun completeStateWithoutUploadsIsIdempotentSuccess() {
        val body = """{"id":"r_done","state":"complete","expiresAt":"2030-01-01T00:00:00Z"}"""
        val connection = FakeConnection(URL("https://wowforever-diagnostics.vercel.app/api/reports"), 200, body)
        val factory = FakeFactory(listOf(connection))
        val created = ForeverRemoteDiagnosticsUpload.createReport("{}", "draft-r", "CAP_R", factory)
        assertEquals("r_done", created.id)
        assertTrue(created.uploads.isEmpty())
    }

    @Test
    fun completionRequiresConfirmedMatchingReceipt() {
        val url = URL("https://wowforever-diagnostics.vercel.app/api/reports/r_done/complete")
        val good = FakeFactory(listOf(FakeConnection(url, 200, """{"id":"r_done","state":"complete"}""")))
        assertEquals("r_done", ForeverRemoteDiagnosticsUpload.completeReport("r_done", "CAP_R", good))
        val pending = FakeFactory(listOf(FakeConnection(url, 200, """{"id":"r_done","state":"pending"}""")))
        try {
            ForeverRemoteDiagnosticsUpload.completeReport("r_done", "CAP_R", pending)
            fail("pending is not success")
        } catch (_: ForeverRemoteDiagnosticsUpload.Failure.Completion) { }
    }

    @Test
    fun partialPutReturns409ThenCompleteVerifies() {
        // Primer PUT ok, reintento 409 por inmutabilidad: no es fallo permanente.
        val first = FakeConnection(URL(blobUrl()), 200, "")
        val retry = FakeConnection(URL(blobUrl()), 409, "")
        val factory = FakeFactory(listOf(first, retry))
        val target = ForeverRemoteDiagnosticsUpload.BlobTarget("diagnostics", blobUrl(), "text/plain; charset=utf-8")
        ForeverRemoteDiagnosticsUpload.putBlob(target, "{}", factory)
        ForeverRemoteDiagnosticsUpload.putBlob(target, "{}", factory)
        assertEquals(2, factory.used.size)
        // Completion estricta: 409 NO es éxito (test de 409 falso).
        val complete = FakeConnection(URL("https://wowforever-diagnostics.vercel.app/api/reports/r_done/complete"), 409, "")
        val factory2 = FakeFactory(listOf(complete))
        try {
            ForeverRemoteDiagnosticsUpload.completeReport("r_done", "CAP_R", factory2)
            fail("complete 409 must fail")
        } catch (failure: ForeverRemoteDiagnosticsUpload.Failure.Completion) {
            // Esperado.
        }
    }

    @Test
    fun realBlobSecondPut400AlreadyExistsContinuesToComplete() {
        // Dato real reproducido: segundo PUT inmutable devuelve 400 con
        // error.code bad_request y mensaje 'already exists' (NO 409).
        val alreadyExists = """{"error":{"code":"bad_request","message":"Blob already exists"}}"""
        val retry = FakeConnection(URL(blobUrl()), 400, "", alreadyExists)
        val factory = FakeFactory(listOf(retry))
        val target = ForeverRemoteDiagnosticsUpload.BlobTarget("wine", blobUrl(), "text/plain; charset=utf-8")
        ForeverRemoteDiagnosticsUpload.putBlob(target, "{}", factory)
        assertEquals(1, factory.used.size)
    }

    @Test
    fun other400IsPermanentFailureWithoutReflectingBody() {
        val secretBody = """{"error":{"code":"bad_request","message":"quota exceeded for cap SECRET_XYZ"}}"""
        val bad = FakeConnection(URL(blobUrl()), 400, "", secretBody)
        val factory = FakeFactory(listOf(bad))
        val target = ForeverRemoteDiagnosticsUpload.BlobTarget("wine", blobUrl(), "text/plain; charset=utf-8")
        try {
            ForeverRemoteDiagnosticsUpload.putBlob(target, "{}", factory)
            fail("other 400 must fail")
        } catch (failure: ForeverRemoteDiagnosticsUpload.Failure.Blob) {
            assertFalse(failure.message!!.contains("SECRET_XYZ"))
            assertFalse(failure.message!!.contains("quota"))
            assertFalse(failure.message!!.contains(blobUrl()))
        }
    }
}
