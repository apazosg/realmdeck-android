package app.gamenative

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Identidades, borradores y cronología durable del diagnóstico remoto.
 *
 * Todo vive en un directorio privado de la app (filesDir/forever/remote-diagnostics).
 * Sin hardware/seriales: installationUUID aleatorio persistido, un draftUUID
 * (Idempotency-Key) por comentario y una capacidad aleatoria de 32 bytes por
 * borrador. Reintentar reutiliza snapshot, UUID y capacidad.
 * Cambiar el comentario crea un borrador nuevo.
 *
 * El snapshot exacto (metadatos + textos saneados + IDs) se persiste una vez
 * por borrador en ficheros privados acotados (máx. 5 borradores): un reintento
 * tras rearranque envía LOS MISMOS bytes/hash/IDs, sin duplicar informe.
 */
object ForeverRemoteDiagnosticsStore {
    const val DIR_NAME = "remote-diagnostics"
    private const val IDENTITY_FILE = "identity.json"
    private const val EVENTS_FILE = "events.jsonl"
    private const val DRAFT_PREFIX = "draft-"
    private const val DRAFT_SUFFIX = ".json"
    private const val PAYLOAD_SUFFIX = ".payload.json"
    private const val INTENT_FILE = "latest-intent.json"

    data class Draft(
        val draftId: String,
        val capability: String,
        val comment: String,
        val createdAtMs: Long,
        val reportId: String? = null,
        val attemptId: String? = null,
    )

    data class Payload(
        val metadataJson: String,
        val attachments: List<Pair<String, String>>,
        val attemptId: String? = null,
        val truncatedMetadata: Boolean = false,
        val summary: List<String> = emptyList(),
        val privacySample: String = "",
        val truncated: Boolean = false,
        val hasMissing: Boolean = false,
    ) {
        val metadataBytes: Int get() = metadataJson.toByteArray(Charsets.UTF_8).size
        val totalBytes: Int get() = attachments.sumOf { it.second.toByteArray(Charsets.UTF_8).size }
    }

    data class TimelineEvent(val atMs: Long, val action: String, val outcome: String)

    data class Intent(
        val attemptId: String,
        val atMs: Long,
        val requested: Map<String, String?>,
        /** Fichero de log PoC congelado por intento (nombre blanqueado). */
        val logFile: String? = null,
        val result: String? = null,
        val stage: String? = null,
        val error: String? = null,
        val endedAtMs: Long? = null,
        /** Código de salida observado del wrapper Wine; null si no observado. */
        val exitCode: Int? = null,
    )

    /**
     * Cola acotada fuera del hilo UI para migas no críticas. La intención de
     * lanzamiento usa recordSync sobre la ruta IO existente antes de los gates.
     */
    private val writer = ThreadPoolExecutor(
        0, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(512),
    ) { runnable -> Thread(runnable, "forever-remote-diagnostics").apply { isDaemon = true } }

    /** Solo tests: drena la cola para aserciones deterministas. */
    fun flush(timeoutMs: Long = 5_000L): Boolean {
        val latch = CountDownLatch(1)
        return try {
            writer.execute { latch.countDown() }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            false
        }
    }

    fun root(filesDir: File): File = File(File(filesDir, "forever"), DIR_NAME).apply { mkdirs() }

    /** UUID de instalación aleatorio, estable entre arranques. No hardware. */
    @Synchronized
    fun installationId(filesDir: File): String {
        val file = File(root(filesDir), IDENTITY_FILE)
        val existing = runCatching {
            if (file.isFile) JSONObject(file.readText(Charsets.UTF_8)).optString("installationId") else ""
        }.getOrDefault("")
        if (existing.isNotEmpty()) return existing
        val fresh = UUID.randomUUID().toString()
        atomicText(file, JSONObject().put("schema", 1).put("installationId", fresh).toString())
        return fresh
    }

    /** Nuevo borrador: capability de 32 bytes aleatorios en base64url. */
    fun newDraft(filesDir: File, comment: String): Draft {
        val bytes = ByteArray(ForeverRemoteDiagnosticsContract.CAPABILITY_BYTES)
        SecureRandom().nextBytes(bytes)
        val draft = Draft(
            draftId = UUID.randomUUID().toString(),
            capability = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
            comment = comment,
            createdAtMs = System.currentTimeMillis(),
        )
        writeDraft(filesDir, draft)
        pruneDrafts(filesDir)
        return draft
    }

    fun writeDraft(filesDir: File, draft: Draft) {
        val file = File(root(filesDir), DRAFT_PREFIX + draft.draftId + DRAFT_SUFFIX)
        atomicText(file,
            JSONObject()
                .put("schema", 1)
                .put("draftId", draft.draftId)
                .put("capability", draft.capability)
                .put("comment", draft.comment)
                .put("createdAtMs", draft.createdAtMs)
                .put("reportId", draft.reportId ?: JSONObject.NULL)
                .put("attemptId", draft.attemptId ?: JSONObject.NULL)
                .toString(),
        )
    }

    fun readDraft(filesDir: File, draftId: String): Draft? = runCatching {
        val file = File(root(filesDir), DRAFT_PREFIX + draftId + DRAFT_SUFFIX)
        if (!file.isFile) return null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        if (json.optInt("schema", 0) != 1) return null
        Draft(
            draftId = json.getString("draftId"),
            capability = json.getString("capability"),
            comment = json.optString("comment", ""),
            createdAtMs = json.optLong("createdAtMs", 0L),
            reportId = json.optString("reportId").takeUnless { it.isNullOrEmpty() || it == "null" },
            attemptId = json.optString("attemptId").takeUnless { it.isNullOrEmpty() || it == "null" },
        )
    }.getOrNull()

    /**
     * Reutiliza el borrador fallido con LOS MISMOS bytes e IDs solo si el
     * comentario y el snapshot exacto coinciden; si no, null (nuevo borrador).
     */
    fun resolveReusableDraft(filesDir: File, comment: String, payload: Payload): Draft? {
        val failed = latestFailedDraft(filesDir) ?: return null
        if (failed.comment != comment) return null
        val stored = readPayload(filesDir, failed.draftId) ?: return null
        if (stored.metadataJson != payload.metadataJson || stored.attachments != payload.attachments) return null
        return failed
    }

    /** Acuña borrador + payload exacto una vez; abrir/cancelar no acuñan. */
    fun mintDraftWithPayload(filesDir: File, comment: String, payload: Payload): Draft {
        val draft = newDraft(filesDir, comment)
        val stamped = draft.copy(attemptId = payload.attemptId)
        writeDraft(filesDir, stamped)
        writePayload(filesDir, stamped.draftId, payload)
        pruneDrafts(filesDir)
        return stamped
    }

    /**
     * Persiste el snapshot exacto del borrador una vez: abrir o cancelar el
     * diálogo no vuelve a acuñarlo. Acotado al total del contrato.
     */
    fun writePayload(filesDir: File, draftId: String, payload: Payload) {
        val total = payload.metadataBytes + payload.totalBytes
        require(total <= ForeverRemoteDiagnosticsContract.ATTACHMENTS_TOTAL_MAX_BYTES +
            ForeverRemoteDiagnosticsContract.METADATA_MAX_BYTES) { "payload exceeds bound" }
        val file = File(root(filesDir), DRAFT_PREFIX + draftId + PAYLOAD_SUFFIX)
        val json = JSONObject()
            .put("schema", 1)
            .put("draftId", draftId)
            .put("attemptId", payload.attemptId ?: JSONObject.NULL)
            .put("truncatedMetadata", payload.truncatedMetadata)
            .put("metadata", payload.metadataJson)
            .put("summary", org.json.JSONArray(payload.summary))
            .put("privacySample", payload.privacySample)
            .put("truncated", payload.truncated)
            .put("hasMissing", payload.hasMissing)
        val names = org.json.JSONArray()
        val texts = org.json.JSONArray()
        for ((name, text) in payload.attachments) {
            names.put(name)
            texts.put(text)
        }
        json.put("names", names).put("texts", texts)
        val temporary = File(root(filesDir), DRAFT_PREFIX + draftId + PAYLOAD_SUFFIX + ".tmp")
        temporary.writeText(json.toString())
        require(temporary.renameTo(file)) { "payload commit failed" }
    }

    fun readPayload(filesDir: File, draftId: String): Payload? = runCatching {
        val file = File(root(filesDir), DRAFT_PREFIX + draftId + PAYLOAD_SUFFIX)
        if (!file.isFile) return null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        if (json.optInt("schema", 0) != 1 || json.optString("draftId") != draftId) return null
        val names = json.getJSONArray("names")
        val texts = json.getJSONArray("texts")
        require(names.length() == texts.length())
        Payload(
            metadataJson = json.getString("metadata"),
            attachments = (0 until names.length()).map { names.getString(it) to texts.getString(it) },
            attemptId = json.optString("attemptId").takeUnless { it.isNullOrEmpty() || it == "null" },
            truncatedMetadata = json.optBoolean("truncatedMetadata", false),
            summary = json.optJSONArray("summary")?.let { array ->
                (0 until array.length()).map { array.getString(it) }
            } ?: emptyList(),
            privacySample = json.optString("privacySample", ""),
            truncated = json.optBoolean("truncated", false),
            hasMissing = json.optBoolean("hasMissing", false),
        )
    }.getOrNull()

    /** Último borrador con snapshot y sin éxito, para reintentar sin duplicar. */
    fun latestFailedDraft(filesDir: File): Draft? = runCatching {
        root(filesDir).listFiles { file ->
            file.isFile && file.name.startsWith(DRAFT_PREFIX) && file.name.endsWith(DRAFT_SUFFIX)
        }?.mapNotNull { readDraft(filesDir, it.name.removePrefix(DRAFT_PREFIX).removeSuffix(DRAFT_SUFFIX)) }
            ?.filter { it.reportId == null && readPayload(filesDir, it.draftId) != null }
            ?.maxByOrNull { it.createdAtMs }
    }.getOrDefault(null)

    private fun pruneDrafts(filesDir: File) {
        runCatching {
            val drafts = root(filesDir).listFiles { file ->
                file.isFile && file.name.startsWith(DRAFT_PREFIX) && file.name.endsWith(DRAFT_SUFFIX) &&
                    !file.name.endsWith(PAYLOAD_SUFFIX)
            }?.sortedBy { it.lastModified() } ?: return
            val excess = drafts.size - ForeverRemoteDiagnosticsContract.DRAFTS_RETAINED
            for (index in 0 until excess.coerceAtLeast(0)) {
                val id = drafts[index].name.removePrefix(DRAFT_PREFIX).removeSuffix(DRAFT_SUFFIX)
                drafts[index].delete()
                File(root(filesDir), DRAFT_PREFIX + id + PAYLOAD_SUFFIX).delete()
            }
        }
    }

    /**
     * Intención de lanzamiento durable: ID + hora + config solicitada
     * congelada, con resultado/etapa/error actualizados por los hooks
     * existentes. Un intento fresco fallido nunca arrastra el RUN anterior.
     */
    /** Congela el log PoC del intento: solo nombres run-<n>.jsonl. */
    @Synchronized
    fun freezeIntentLog(filesDir: File, attemptId: String, fileName: String): Boolean {
        if (!fileName.matches(Regex("run-\\d+\\.jsonl"))) return false
        return runCatching {
            val current = readLatestIntent(filesDir) ?: return false
            if (current.attemptId != attemptId) return false
            persistIntent(filesDir, current.copy(logFile = fileName))
            true
        }.getOrDefault(false)
    }

    /** Actualiza resultado/etapa/error/fin del intento desde los hooks existentes. */
    @Synchronized
    fun updateIntent(filesDir: File, attemptId: String, result: String? = null,
        stage: String? = null, error: String? = null, endedAtMs: Long? = null,
        exitCode: Int? = null, onlyIfEmpty: Boolean = false) {
        runCatching {
            val current = readLatestIntent(filesDir) ?: return
            if (current.attemptId != attemptId) return
            // Un exit code ya observado nunca se sobrescribe con unknown.
            if (onlyIfEmpty && current.result != null) return
            persistIntent(
                filesDir,
                current.copy(
                    result = result ?: current.result,
                    stage = stage ?: current.stage,
                    error = error ?: current.error,
                    endedAtMs = endedAtMs ?: current.endedAtMs,
                    exitCode = exitCode ?: current.exitCode,
                ),
            )
        }
    }

    @Synchronized
    fun persistIntent(filesDir: File, intent: Intent) {
        val requested = JSONObject()
        for ((key, value) in intent.requested) requested.put(key, value ?: JSONObject.NULL)
        atomicText(File(root(filesDir), INTENT_FILE),
            JSONObject()
                .put("schema", 1)
                .put("attemptId", intent.attemptId)
                .put("atMs", intent.atMs)
                .put("requested", requested)
                .put("logFile", intent.logFile ?: JSONObject.NULL)
                .put("result", intent.result ?: JSONObject.NULL)
                .put("stage", intent.stage ?: JSONObject.NULL)
                .put("error", intent.error ?: JSONObject.NULL)
                .put("endedAtMs", intent.endedAtMs ?: JSONObject.NULL)
                .put("exitCode", intent.exitCode ?: JSONObject.NULL)
                .toString(),
        )
    }

    fun readLatestIntent(filesDir: File): Intent? = runCatching {
        val file = File(root(filesDir), INTENT_FILE)
        if (!file.isFile) return null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        if (json.optInt("schema", 0) != 1) return null
        val requestedJson = json.optJSONObject("requested") ?: JSONObject()
        Intent(
            attemptId = json.getString("attemptId"),
            atMs = json.optLong("atMs", 0L),
            requested = requestedJson.keys().asSequence().associateWith {
                requestedJson.optString(it).takeUnless { value -> value.isEmpty() || value == "null" }
            },
            logFile = json.optString("logFile").takeUnless { it.isNullOrEmpty() || it == "null" },
            result = json.optString("result").takeUnless { it.isNullOrEmpty() || it == "null" },
            stage = json.optString("stage").takeUnless { it.isNullOrEmpty() || it == "null" },
            error = json.optString("error").takeUnless { it.isNullOrEmpty() || it == "null" },
            endedAtMs = json.optLong("endedAtMs", 0L).takeIf { it > 0L },
            exitCode = if (json.isNull("exitCode")) null else json.optInt("exitCode"),
        )
    }.getOrNull()

    private fun atomicText(file: File, text: String) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(text, Charsets.UTF_8)
        require(temporary.renameTo(file)) { "diagnostics commit failed" }
    }

    /**
     * Cronología durable rotatoria con el vocabulario EXACTO del wire.
     * Acciones o outcomes fuera de la allowlist se descartan (sin keylogging
     * ni texto libre como outcome: la evidencia técnica vive en adjuntos).
     */
    fun record(filesDir: File, action: String, outcome: String) {
        if (!ForeverRemoteDiagnosticsSanitizer.allowedAction(action)) return
        if (outcome !in ForeverRemoteDiagnosticsContract.EVENT_OUTCOMES) return
        writer.execute {
            runCatching {
                val file = File(root(filesDir), EVENTS_FILE)
                val line = JSONObject()
                    .put("at", System.currentTimeMillis())
                    .put("action", action)
                    .put("outcome", outcome)
                    .toString() + "\n"
                val lineBytes = line.toByteArray(Charsets.UTF_8)
                if (file.length() + lineBytes.size > 512 * 1024) rotate(filesDir)
                file.appendBytes(lineBytes)
            }
        }
    }

    /** Ruta IO existente para la intención crítica antes de los gates. */
    @Synchronized
    fun recordSync(filesDir: File, action: String, outcome: String) {
        if (!ForeverRemoteDiagnosticsSanitizer.allowedAction(action)) return
        if (outcome !in ForeverRemoteDiagnosticsContract.EVENT_OUTCOMES) return
        runCatching {
            val file = File(root(filesDir), EVENTS_FILE)
            val line = JSONObject()
                .put("at", System.currentTimeMillis())
                .put("action", action)
                .put("outcome", outcome)
                .toString() + "\n"
            val lineBytes = line.toByteArray(Charsets.UTF_8)
            if (file.length() + lineBytes.size > 512 * 1024) rotate(filesDir)
            file.appendBytes(lineBytes)
        }
    }

    /** Lee la cronología tras el arranque; líneas corruptas se omiten. */
    fun readTimeline(filesDir: File): List<TimelineEvent> = runCatching {
        val file = File(root(filesDir), EVENTS_FILE)
        if (!file.isFile) return emptyList()
        file.readLines(Charsets.UTF_8).mapNotNull { line ->
            runCatching {
                val json = JSONObject(line)
                val action = json.getString("action")
                val outcome = json.optString("outcome", "ok")
                if (!ForeverRemoteDiagnosticsSanitizer.allowedAction(action)) return@runCatching null
                if (outcome !in ForeverRemoteDiagnosticsContract.EVENT_OUTCOMES) return@runCatching null
                TimelineEvent(json.getLong("at"), action, outcome)
            }.getOrNull()
        }.takeLast(ForeverRemoteDiagnosticsContract.EVENTS_MAX)
    }.getOrDefault(emptyList())

    private fun rotate(filesDir: File) {
        runCatching {
            val file = File(root(filesDir), EVENTS_FILE)
            val lines = file.readLines(Charsets.UTF_8)
            if (lines.size > ForeverRemoteDiagnosticsContract.EVENTS_MAX) {
                file.writeText(lines.takeLast(ForeverRemoteDiagnosticsContract.EVENTS_MAX).joinToString("\n") + "\n")
            }
        }
    }
}
