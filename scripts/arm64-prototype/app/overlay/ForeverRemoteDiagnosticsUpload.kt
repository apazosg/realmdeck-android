package app.gamenative

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Subida al backend MVP1 con HttpURLConnection, sin SDK ni dependencias.
 *
 * Sin redirects, sin reintento infinito y sin reflejar jamás capacidad, URLs
 * firmadas ni cuerpos en errores o logs. Toda llamada de red ocurre solo tras
 * el Enviar explícito y fuera del hilo UI (lo garantiza quien llama).
 */
object ForeverRemoteDiagnosticsUpload {
    data class CreatedReport(val id: String, val uploads: List<BlobTarget>, val state: String?)
    data class BlobTarget(val name: String, val url: String, val contentType: String)

    /** Fábrica inyectable para tests: el cliente real usa URL.openConnection. */
    interface ConnectionFactory {
        fun open(url: String): HttpURLConnection
    }

    object DefaultFactory : ConnectionFactory {
        override fun open(url: String): HttpURLConnection = URL(url).openConnection() as HttpURLConnection
    }

    sealed class Failure(message: String) : IllegalStateException(message) {
        class Transport(detail: String) : Failure("transport_unavailable: $detail")
        class Rejected(detail: String) : Failure("report_rejected: $detail")
        class Blob(detail: String) : Failure("blob_upload_failed: $detail")
        class Completion(detail: String) : Failure("completion_failed: $detail")
    }

    fun createReport(metadataJson: String, draftId: String, capability: String,
        factory: ConnectionFactory = DefaultFactory): CreatedReport {
        val bytes = metadataJson.toByteArray(Charsets.UTF_8)
        require(bytes.size <= ForeverRemoteDiagnosticsContract.METADATA_MAX_BYTES) { "metadata exceeds 256KiB" }
        val connection = factory.open(ForeverRemoteDiagnosticsContract.createUrl())
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Idempotency-Key", draftId)
            connection.setRequestProperty("Authorization", "Bearer $capability")
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status in 300..399) throw Failure.Rejected("redirect_not_followed")
            if (status !in 200..299) throw Failure.Rejected("status=$status " + safeErrorSnippet(connection))
            val body = readResponse(connection)
            return parseCreated(body)
        } catch (failure: Failure) {
            throw failure
        } catch (failure: Exception) {
            throw Failure.Transport(failure.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    fun putBlob(target: BlobTarget, text: String, factory: ConnectionFactory = DefaultFactory) {
        validateBlobUrl(target.url)
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= ForeverRemoteDiagnosticsContract.ATTACHMENT_MAX_BYTES) { "attachment exceeds 8MiB" }
        val connection = factory.open(target.url)
        try {
            connection.requestMethod = "PUT"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            // Solo Content-Type: la firma viaja en la query, sin auth headers.
            connection.setRequestProperty("Content-Type", target.contentType)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status in 300..399) throw Failure.Blob("redirect_not_followed")
            // Los objetos son inmutables: el reintento de un PUT ya confirmado
            // devuelve 409, o 400 real con error.code bad_request + mensaje
            // 'already exists'. El servidor verifica el hash al completar.
            // Nunca se refleja cuerpo/URL/secreto en el mensaje.
            if (status == 409) return
            if (status == 400 && isAlreadyExists(connection)) return
            if (status !in 200..299) throw Failure.Blob("status=$status")
            drain(connection)
        } catch (failure: Failure) {
            throw failure
        } catch (failure: Exception) {
            throw Failure.Blob(failure.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Confirma el informe. Exige 200 con id válido y estado complete en el
     * cuerpo: un 409 aquí NO es éxito (el test de 409 falso lo demuestra).
     * Devuelve el id validado.
     */
    fun completeReport(reportId: String, capability: String, factory: ConnectionFactory = DefaultFactory): String {
        require(reportId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "invalid report id" }
        val connection = factory.open(ForeverRemoteDiagnosticsContract.completeUrl(reportId))
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $capability")
            connection.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status in 300..399) throw Failure.Completion("redirect_not_followed")
            if (status != 200) throw Failure.Completion("status=$status " + safeErrorSnippet(connection))
            val body = readResponse(connection)
            val json = runCatching { JSONObject(body) }.getOrElse { throw Failure.Completion("invalid_json") }
            val id = json.optString("id")
            require(id == reportId) { "completion id mismatch" }
            val state = json.optString("state")
            require(state == "complete") { "completion state is not complete" }
            drain(connection)
            return id
        } catch (failure: Failure) {
            throw failure
        } catch (failure: Exception) {
            throw Failure.Completion(failure.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Valida la URL firmada: host EXACTO vercel.com, ruta EXACTA /api/blob/
     * con query firmada, solo https, sin userinfo ni puertos raros. Nunca
     * URLs vercel.com arbitrarias fuera de esa ruta.
     */
    fun validateBlobUrl(raw: String) {
        val url = runCatching { URL(raw) }.getOrElse { throw Failure.Blob("malformed_url") }
        require(url.protocol == "https") { "blob url must be https" }
        require(url.userInfo == null) { "blob url must not carry userinfo" }
        val host = url.host.lowercase()
        require(host == ForeverRemoteDiagnosticsContract.BLOB_HOST) { "blob url host is not allowed" }
        require(url.path == ForeverRemoteDiagnosticsContract.BLOB_PATH_PREFIX) {
            "blob url object is missing"
        }
        require(url.port == -1 || url.port == 443) { "blob url port is not allowed" }
        require(!url.query.isNullOrEmpty() && url.query.contains('=')) { "blob url signature is missing" }
    }

    /**
     * Solo el 400 real de objeto ya existente: cuerpo JSON acotado con
     * error.code bad_request y mensaje 'already exists', sobre la conexión ya
     * validada (host/ruta firmada). Cualquier otro 400 es fallo permanente.
     */
    private fun isAlreadyExists(connection: HttpURLConnection): Boolean = runCatching {
        val stream = try {
            connection.errorStream
        } catch (_: Exception) {
            null
        } ?: return false
        val bytes = stream.use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (out.size() < 4096) {
                val count = it.read(buffer, 0, (4096 - out.size()).coerceAtMost(buffer.size))
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        val error = JSONObject(String(bytes, Charsets.UTF_8)).optJSONObject("error") ?: return false
        error.optString("code") == "bad_request" &&
            error.optString("message").lowercase().contains("already exists")
    }.getOrDefault(false)

    private fun parseCreated(body: String): CreatedReport {
        val json = runCatching { JSONObject(body) }.getOrElse { throw Failure.Rejected("invalid_json") }
        val id = json.optString("id")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "invalid report id" }
        // Reintento idempotente de un informe ya completo: sin uploads.
        // Se propaga el estado para que el envío salte los PUT.
        val state = json.optString("state").takeUnless { it.isNullOrEmpty() }
        if (state == "complete") {
            val uploadsJson = json.optJSONArray("uploads")
            if (uploadsJson == null || uploadsJson.length() == 0) return CreatedReport(id, emptyList(), state)
        }
        val uploadsJson = json.optJSONArray("uploads") ?: throw Failure.Rejected("uploads_missing")
        val uploads = (0 until uploadsJson.length()).map { index ->
            val item = uploadsJson.getJSONObject(index)
            val name = item.optString("name")
            require(name in ForeverRemoteDiagnosticsContract.ATTACHMENT_NAMES) { "unexpected attachment name" }
            BlobTarget(name, item.getString("url"), item.optString("contentType",
                ForeverRemoteDiagnosticsContract.CONTENT_TYPE))
        }
        return CreatedReport(id, uploads, state)
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val stream = try {
            connection.inputStream
        } catch (_: Exception) {
            null
        } ?: return ""
        return stream.use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() < 256 * 1024) {
                val count = it.read(buffer)
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            out.toString(Charsets.UTF_8.name())
        }
    }

    private fun drain(connection: HttpURLConnection) {
        runCatching { readResponse(connection) }
    }

    /**
     * Sin reflejar tokens, URLs firmadas, SQL ni cuerpos: solo el código de
     * estado y una marca fija. El contenido del error nunca viaja al mensaje.
     */
    private fun safeErrorSnippet(connection: HttpURLConnection): String {
        runCatching { readResponse(connection) }
        return "response_body_omitted"
    }
}
