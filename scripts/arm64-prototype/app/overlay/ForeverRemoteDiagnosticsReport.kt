package app.gamenative

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Snapshot consistente del MISMO run/config para un informe schema1.
 *
 * Entradas ya acotadas por la captura local; aquí se validan cotas del
 * contrato (metadatos ≤256KiB, adjunto ≤8MiB, total ≤24MiB, eventos ≤200,
 * fuentes ≤32, adjuntos ≤6) y se construye el JSON de creación.
 */
object ForeverRemoteDiagnosticsReport {
    data class Config(val device: Map<String, String?>, val app: Map<String, String?>, val client: Map<String, String?>,
        val requested: Map<String, String?>, val observed: Map<String, String?>)

    data class Attempt(val id: String, val runId: String?, val status: String, val stage: String?,
        val startedAtMs: Long?, val endedAtMs: Long?, val result: String, val exitCode: Int?,
        val terminationUnknown: Boolean)

    data class Source(val name: String, val status: String, val reason: String?, val truncated: Boolean, val bytes: Int)

    data class Attachment(val name: String, val text: String) {
        val bytes: Int get() = text.toByteArray(Charsets.UTF_8).size
        val sha256: String get() = sha256Hex(text.toByteArray(Charsets.UTF_8))
    }

    data class Inputs(val installationId: String, val capturedAtMs: Long, val comment: String,
        val config: Config, val attempt: Attempt?, val events: List<ForeverRemoteDiagnosticsStore.TimelineEvent>,
        val sources: List<Source>, val attachments: List<Attachment>)

    data class Built(val metadataJson: String, val attachments: List<Attachment>, val truncatedMetadata: Boolean)

    /** Read-only projection: preparing a report must never rewrite the evidence. */
    fun attemptFromIntent(intent: ForeverRemoteDiagnosticsStore.Intent): Attempt = Attempt(
        id = intent.attemptId,
        runId = null,
        status = if (intent.exitCode != null) "wrapper_exit_observed" else if (intent.endedAtMs != null) "ended" else "unknown",
        stage = intent.stage,
        startedAtMs = intent.atMs,
        endedAtMs = intent.endedAtMs,
        result = intent.result ?: "unknown",
        exitCode = intent.exitCode,
        // A wrapper exit does not prove whole-tree quiescence.
        terminationUnknown = intent.exitCode != null || intent.stage in setOf("launch", "running"),
    )

    /** Encaja el total en 24MiB truncando por orden en vez de rechazar el informe. */
    private fun attachmentsWithinTotal(
        attachments: List<Pair<Attachment, Boolean>>,
    ): List<Pair<Attachment, Boolean>> {
        var remaining = ForeverRemoteDiagnosticsContract.ATTACHMENTS_TOTAL_MAX_BYTES
        val fitted = mutableListOf<Pair<Attachment, Boolean>>()
        for ((attachment, truncated) in attachments) {
            if (attachment.bytes <= remaining) {
                fitted += attachment to truncated
                remaining -= attachment.bytes
            } else if (remaining > 1024) {
                val (cut, _) = truncateAttachment(attachment.text, remaining)
                fitted += Attachment(attachment.name, cut) to true
                remaining = 0
            }
        }
        return fitted
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Recorta un adjunto con head/tail acotado; nunca lee sin cota quien llama. */
    fun truncateAttachment(text: String, maxBytes: Int): Pair<String, Boolean> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text to false
        if (maxBytes < 2048) return decodeHead(bytes, maxBytes / 2) to true
        val headBytes = (maxBytes / 4).coerceAtLeast(1024)
        val tailBytes = maxBytes - headBytes - 128
        val head = decodeHead(bytes, headBytes)
        val tail = decodeTail(bytes, tailBytes)
        return ("$head\n[output truncated: head=${head.toByteArray().size} tail=${tail.toByteArray().size} total=${bytes.size}]\n$tail") to true
    }

    private fun decodeHead(bytes: ByteArray, max: Int): String {
        var end = max.coerceAtMost(bytes.size)
        while (end > 0 && (bytes[end - 1].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    private fun decodeTail(bytes: ByteArray, max: Int): String {
        var start = (bytes.size - max).coerceAtLeast(0)
        while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start++
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    /**
     * Resultado del intento desde la evidencia del RunStore, sin inventar hitos.
     * La terminación no confirmada se comprueba ANTES de inferir resultados:
     * una interrupción con Wine desconocido nunca se etiqueta como salida.
     */
    fun attemptResult(launchAttempted: Boolean, spawnObserved: Boolean, gameObserved: Boolean,
        exitObserved: Boolean, terminationUnknown: Boolean, cancelled: Boolean): String = when {
        cancelled -> "cancelled"
        !launchAttempted -> "precondition_failed"
        terminationUnknown -> "termination_unknown"
        !spawnObserved -> "wine_failed"
        !exitObserved -> "unknown"
        !gameObserved -> "wow_not_created"
        else -> "wow_exit"
    }

    fun build(inputs: Inputs): Built {
        val contract = ForeverRemoteDiagnosticsContract
        require(inputs.installationId.isNotBlank()) { "installationId is required" }

        val attachments = attachmentsWithinTotal(
            inputs.attachments
                .filter { it.name in contract.ATTACHMENT_NAMES }
                .take(contract.ATTACHMENTS_MAX_COUNT)
                .map { attachment ->
                    var text = attachment.text
                    var truncated = false
                    if (text.toByteArray(Charsets.UTF_8).size > contract.ATTACHMENT_MAX_BYTES) {
                        val (cut, was) = truncateAttachment(text, contract.ATTACHMENT_MAX_BYTES)
                        text = cut
                        truncated = was
                    }
                    Attachment(attachment.name, text) to truncated
                },
        )

        val sources = inputs.sources
            .filter { it.name in contract.SOURCE_NAMES && it.status in contract.SOURCE_STATUSES }
            .take(contract.SOURCES_MAX)
        val events = inputs.events.takeLast(contract.EVENTS_MAX)

        val root = JSONObject()
            .put("schema", contract.SCHEMA)
            .put("installationId", inputs.installationId)
            .put("capturedAt", isoUtc(inputs.capturedAtMs))
            .put("comment", inputs.comment.take(contract.COMMENT_MAX_CHARS))
            .put("config", configJson(inputs.config))
            .put("attempt", inputs.attempt?.let { attemptJson(it) } ?: JSONObject.NULL)
            .put("sources", JSONArray(sources.map { sourceJson(it, attachments) }))
            .put("events", JSONArray(events.map { eventJson(it) }))
            .put("attachments", JSONArray(attachments.map { attachmentJson(it.first) }))

        var metadata = root.toString()
        var truncatedMetadata = false
        if (metadata.toByteArray(Charsets.UTF_8).size > contract.METADATA_MAX_BYTES) {
            // Recorte explícito: comentario acortado, nunca campos inventados.
            root.put("comment", inputs.comment.take(512))
            metadata = root.toString()
            truncatedMetadata = true
            require(metadata.toByteArray(Charsets.UTF_8).size <= contract.METADATA_MAX_BYTES) {
                "metadata exceeds 256KiB after comment trim"
            }
        }
        return Built(metadata, attachments.map { it.first }, truncatedMetadata)
    }

    private fun configJson(config: Config): JSONObject = JSONObject()
        .put("device", filtered(config.device, ForeverRemoteDiagnosticsContract.CONFIG_DEVICE_KEYS))
        .put("app", filtered(config.app, ForeverRemoteDiagnosticsContract.CONFIG_APP_KEYS))
        .put("client", filtered(config.client, ForeverRemoteDiagnosticsContract.CONFIG_CLIENT_KEYS))
        .put("requested", filtered(config.requested, ForeverRemoteDiagnosticsContract.CONFIG_PROFILE_KEYS))
        .put("observed", filtered(config.observed, ForeverRemoteDiagnosticsContract.CONFIG_PROFILE_KEYS))

    private fun filtered(values: Map<String, String?>, allowed: Set<String>): JSONObject {
        val json = JSONObject()
        for ((key, value) in values) {
            if (key in allowed) json.put(key, value ?: JSONObject.NULL)
        }
        return json
    }

    private fun attemptJson(attempt: Attempt): JSONObject = JSONObject()
        .put("id", attempt.id)
        .put("runId", attempt.runId ?: JSONObject.NULL)
        .put("status", attempt.status)
        .put("stage", attempt.stage ?: JSONObject.NULL)
        .put("startedAt", attempt.startedAtMs?.let { isoUtc(it) } ?: JSONObject.NULL)
        .put("endedAt", attempt.endedAtMs?.let { isoUtc(it) } ?: JSONObject.NULL)
        .put("result", attempt.result)
        .put("exitCode", attempt.exitCode ?: JSONObject.NULL)
        .put("terminationUnknown", attempt.terminationUnknown)

    private fun sourceJson(source: Source, attachments: List<Pair<Attachment, Boolean>>): JSONObject {
        val builderFlag = attachments.firstOrNull { it.first.name == source.name }?.second == true
        val bytes = attachments.firstOrNull { it.first.name == source.name }?.first?.bytes ?: source.bytes
        return JSONObject()
            .put("name", source.name)
            .put("status", source.status)
            .put("reason", source.reason ?: JSONObject.NULL)
            .put("truncated", source.truncated || builderFlag)
            .put("bytes", bytes)
    }

    private fun eventJson(event: ForeverRemoteDiagnosticsStore.TimelineEvent): JSONObject = JSONObject()
        .put("at", isoUtc(event.atMs))
        .put("action", event.action)
        .put("outcome", event.outcome)

    private fun attachmentJson(attachment: Attachment): JSONObject = JSONObject()
        .put("name", attachment.name)
        .put("bytes", attachment.bytes)
        .put("sha256", attachment.sha256)
        .put("contentType", ForeverRemoteDiagnosticsContract.CONTENT_TYPE)

    private fun isoUtc(millis: Long): String {
        val instant = java.time.Instant.ofEpochMilli(millis)
        return java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant)
    }
}
