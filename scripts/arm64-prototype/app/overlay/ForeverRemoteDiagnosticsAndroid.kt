package app.gamenative

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.concurrent.thread

/**
 * Pegamento Android del diagnóstico remoto: construye el snapshot consistente
 * del MISMO intento/RUN con APIs acotadas existentes, sanea TODO (comentario,
 * metadatos y cada adjunto) con copias propias de los secretos reales de
 * autologin (la clase real anula sus temporales al retornar useValues), y
 * sube fuera del hilo UI. El juego/descarga nunca se detienen ni se ven
 * afectados. Sin red hasta Enviar explícito.
 *
 * Flujo real: beginAttempt() ANTES de gates persiste la intención durable con
 * lo solicitado congelado; prepare() LEE esa intención (no la reescribe) y el
 * RUN vinculado en cualquier estado; el borrador y su payload exacto solo se
 * acuñan al Enviar; un reintento tras rearranque reutiliza payload/borrador
 * persistidos sin prepare nuevo.
 */
object ForeverRemoteDiagnosticsAndroid {
    data class Preview(
        val summary: List<String>,
        val privacySample: String,
        val truncated: Boolean,
        val hasMissing: Boolean,
        val metadataBytes: Int,
        val totalBytes: Int,
    )

    data class PreparedSend(
        val built: ForeverRemoteDiagnosticsReport.Built,
        val comment: String,
        val preview: Preview,
        /** Configuración congelada del intento; false = actual sin intento registrado. */
        val frozen: Boolean,
    )

    fun newAttemptId(): String = "attempt-${java.util.UUID.randomUUID()}"

    /**
     * Intención durable ANTES de gates con lo solicitado congelado
     * (perfil/resolución en este momento). Llamar antes de precondiciones.
     */
    fun beginAttempt(context: Context): String {
        val app = context.applicationContext
        val attemptId = newAttemptId()
        val requested = runCatching {
            mapOf(
                "profileId" to ForeverFunctionalServerProfiles.load(app).profile.id,
                "resolution" to ForeverFunctionalDisplayFit.loadMode(app).label,
            )
        }.getOrDefault(emptyMap())
        runCatching {
            ForeverRemoteDiagnosticsStore.persistIntent(
                app.filesDir,
                ForeverRemoteDiagnosticsStore.Intent(
                    attemptId = attemptId,
                    atMs = System.currentTimeMillis(),
                    requested = requested,
                ),
            )
        }
        return attemptId
    }

    /** Actualiza resultado/etapa/error/fin del intento desde los hooks existentes. */
    fun updateAttemptOutcome(context: Context, attemptId: String, result: String? = null,
        stage: String? = null, error: String? = null, ended: Boolean = false,
        onlyIfEmpty: Boolean = false) {
        runCatching {
            ForeverRemoteDiagnosticsStore.updateIntent(
                context.applicationContext.filesDir, attemptId, result, stage, error,
                endedAtMs = if (ended) System.currentTimeMillis() else null,
                onlyIfEmpty = onlyIfEmpty,
            )
        }
    }

    /**
     * Copias propias de los secretos reales. Primero acquire; después se
     * revisa el resumen: almacén roto o reingreso pendiente BLOQUEAN el envío
     * (nunca enviar potencialmente sin sanear); ausencia legítima = NONE.
     * Quien llama debe anular las copias en finally.
     */
    fun autologinSecrets(context: Context): Pair<List<CharArray>, ForeverRemoteDiagnosticsSanitizer.SecretsAvailability> {
        val store = runCatching {
            ForeverFunctionalAutologinStores.forApplication(context.applicationContext)
        }.getOrNull() ?: return emptyList<CharArray>() to
            ForeverRemoteDiagnosticsSanitizer.SecretsAvailability.STORE_UNAVAILABLE
        val credentials = runCatching { store.acquire() }.getOrElse {
            return emptyList<CharArray>() to
                ForeverRemoteDiagnosticsSanitizer.SecretsAvailability.STORE_UNAVAILABLE
        } ?: runCatching { store.settingsSummary() }.fold(
            onSuccess = { summary ->
                if (summary.requiresReentry || !summary.persistenceAvailable) {
                    return emptyList<CharArray>() to
                        ForeverRemoteDiagnosticsSanitizer.SecretsAvailability.STORE_UNAVAILABLE
                }
                return emptyList<CharArray>() to
                    ForeverRemoteDiagnosticsSanitizer.SecretsAvailability.NONE
            },
            onFailure = {
                return emptyList<CharArray>() to
                    ForeverRemoteDiagnosticsSanitizer.SecretsAvailability.STORE_UNAVAILABLE
            },
        )
        return try {
            ForeverRemoteDiagnosticsSanitizer.ownedSecretCopies(credentials) to
                ForeverRemoteDiagnosticsSanitizer.SecretsAvailability.AVAILABLE
        } finally {
            credentials.close()
        }
    }

    /**
     * Ruta real (PoC funcional, sin RunStore gestionado): runId null honesto.
     * Sin inferir PID/WoW/render/login. Cliente incompleto = precondición.
     */
    fun buildAttempt(context: Context, attemptId: String): ForeverRemoteDiagnosticsReport.Attempt? {
        val intent = ForeverRemoteDiagnosticsStore.readLatestIntent(context.applicationContext.filesDir)
            ?.takeIf { it.attemptId == attemptId }
        return intent?.let { ForeverRemoteDiagnosticsReport.attemptFromIntent(it) }
    }

    /**
     * Solicitado congelado de la intención durable (perfil/resolución del
     * momento del intento) + componentes de la preparación PoC. Sin intento
     * registrado: config actual con etiqueta de ausencia (frozen=false).
     */
    private fun requestedFor(context: Context, attemptId: String): Pair<Map<String, String?>, Boolean> {
        val app = context.applicationContext
        val intent = runCatching {
            ForeverRemoteDiagnosticsStore.readLatestIntent(app.filesDir)
        }.getOrNull()?.takeIf { it.attemptId == attemptId }
        if (intent == null) {
            val live = runCatching {
                mapOf(
                    "profileId" to ForeverFunctionalServerProfiles.load(app).profile.id,
                    "resolution" to ForeverFunctionalDisplayFit.loadMode(app).label,
                )
            }.getOrDefault(emptyMap())
            return live to false
        }
        val poc = boundedLog(app, intent)
        val frozen = ForeverRemoteDiagnosticsFrozenConfig.extractRequested("", poc)
        return ForeverRemoteDiagnosticsFrozenConfig.combine(intent.requested, frozen) to true
    }

    /**
     * Log PoC congelado POR INTENTO: el fichero blanqueado registrado en la
     * intención (nunca el último global ajeno), con cota y sin symlinks.
     */
    private fun boundedLog(context: Context, intent: ForeverRemoteDiagnosticsStore.Intent): String {
        return runCatching {
            val dir = File(context.filesDir, "functional-poc")
            if (java.nio.file.Files.isSymbolicLink(dir.toPath())) return ""
            val frozenName = intent.logFile
            val file = if (frozenName != null && frozenName.matches(Regex("run-\\d+\\.jsonl"))) {
                File(dir, frozenName)
            } else {
                return ""
            }
            if (!file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath())) return ""
            ForeverRemoteDiagnosticsLocalCapture.readTail(file, 512 * 1024).first
        }.getOrDefault("")
    }

    private fun buildConfig(context: Context, requested: Map<String, String?>, installedVersion: String?,
        runStatus: String?): ForeverRemoteDiagnosticsReport.Config {
        val app = context.applicationContext
        val packageInfo = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0)
        }.getOrNull()
        return ForeverRemoteDiagnosticsReport.Config(
            device = mapOf(
                "manufacturer" to Build.MANUFACTURER.takeIf { it.isNotBlank() },
                "model" to Build.MODEL.takeIf { it.isNotBlank() },
                "soc" to Build.HARDWARE.takeIf { it.isNotBlank() },
                // Sin sonda nueva: GPU nulo salvo detección cacheada existente (no hay).
                "gpu" to null,
                "androidRelease" to Build.VERSION.RELEASE,
                "androidApi" to Build.VERSION.SDK_INT.toString(),
                "kernel" to runCatching { System.getProperty("os.version") }.getOrNull(),
                "driver" to null,
            ),
            app = mapOf(
                "packageName" to app.packageName,
                "versionName" to packageInfo?.versionName,
                @Suppress("DEPRECATION")
                "versionCode" to packageInfo?.versionCode?.toString(),
                "buildRevision" to null,
            ),
            client = mapOf(
                // Versión instalada REAL, no el default compatible del servidor.
                "build" to installedVersion,
                "architecture" to "arm64",
                "locale" to null,
                "status" to runStatus,
            ),
            requested = requested,
            observed = mapOf(
                "profileId" to null,
                "profileRevision" to null,
                "gpuBackend" to null,
                "resolution" to null,
                "wine" to null,
                "dxvk" to null,
                "panvk" to null,
                "graphics" to null,
            ),
        )
    }

    /**
     * Crea el snapshot local (sin red, sin acuñar borrador): LEE la intención
     * persistida (no la reescribe) y el RUN vinculado en cualquier estado.
     * Sanea TODO y persiste el resultado del intento. Llamar fuera del hilo UI.
     */
    fun prepare(context: Context, attemptId: String, comment: String): PreparedSend {
        val app = context.applicationContext
        return ForeverAssistedCredentialPrivacy.withDiagnosticsAllowed(app.filesDir) {
            check(!ForeverAssistedCredentialPrivacy.isSensitiveAttempt(app.filesDir, attemptId)) {
                "diagnostics export disabled for a sensitive assisted-credential attempt"
            }
            val (secrets, availability) = autologinSecrets(app)
            ForeverRemoteDiagnosticsSanitizer.requireSecretsAvailable(availability)
            try {
            val cleanComment = ForeverRemoteDiagnosticsSanitizer.sanitizeComment(comment, secrets).text
            val installationId = ForeverRemoteDiagnosticsStore.installationId(app.filesDir)
            val (requested, frozen) = requestedFor(app, attemptId)
            val attempt = buildAttempt(app, attemptId)
            // service.inspect UNA vez: versión instalada real del cliente.
            val installedVersion = runCatching {
                val service = ForeverClientUpdateService(app)
                try {
                    service.inspect().installedVersion
                } finally {
                    service.close()
                }
            }.getOrNull()
            // Ruta real: sin identidad de juego observada que afirmar.
            // El log Wine es el del intento vigente (callback ya existente).
            val captured = ForeverRemoteDiagnosticsLocalCapture.capture(app.filesDir, null, false, attemptId)
            val sanitized = captured.map { item ->
                val result = if (item.text.isEmpty()) {
                    ForeverRemoteDiagnosticsSanitizer.SanitizeResult("", 0, false)
                } else {
                    ForeverRemoteDiagnosticsSanitizer.sanitizeText(item.text, secrets)
                }
                Triple(item, result.text, result.truncated)
            }
            val sources = sanitized.map { (item, _, wasTruncated) ->
                val status = when {
                    item.text.isEmpty() -> "missing"
                    wasTruncated || item.truncated -> "truncated"
                    else -> "ok"
                }
                ForeverRemoteDiagnosticsReport.Source(
                    name = item.name,
                    status = status,
                    reason = item.missingReason,
                    truncated = wasTruncated || item.truncated,
                    bytes = item.text.toByteArray(Charsets.UTF_8).size,
                )
            }
            val attachments = sanitized
                .filter { (item, text, _) -> item.name in ForeverRemoteDiagnosticsContract.ATTACHMENT_NAMES && text.isNotEmpty() }
                .map { (item, text, _) -> ForeverRemoteDiagnosticsReport.Attachment(item.name, text) }
            val rawConfig = buildConfig(app, requested, installedVersion, runStatus = null)
            val config = ForeverRemoteDiagnosticsReport.Config(
                device = ForeverRemoteDiagnosticsSanitizer.sanitizeMapValues(rawConfig.device, secrets),
                app = ForeverRemoteDiagnosticsSanitizer.sanitizeMapValues(rawConfig.app, secrets),
                client = ForeverRemoteDiagnosticsSanitizer.sanitizeMapValues(rawConfig.client, secrets),
                requested = ForeverRemoteDiagnosticsSanitizer.sanitizeMapValues(requested, secrets),
                observed = rawConfig.observed,
            )
            val events = ForeverRemoteDiagnosticsStore.readTimeline(app.filesDir)
            val built = ForeverRemoteDiagnosticsReport.build(
                ForeverRemoteDiagnosticsReport.Inputs(
                    installationId = installationId,
                    capturedAtMs = System.currentTimeMillis(),
                    comment = cleanComment,
                    config = config,
                    attempt = attempt,
                    events = events,
                    sources = sources,
                    attachments = attachments,
                ),
            )
            val summary = built.attachments.map { attachment ->
                val source = sources.firstOrNull { it.name == attachment.name }
                ForeverRemoteDiagnosticsSanitizer.previewLabel(attachment.name, attachment.bytes,
                    source?.truncated == true, source?.reason)
            } + sources.filter { it.status == "missing" }.map { source ->
                ForeverRemoteDiagnosticsSanitizer.previewLabel(source.name, 0, false, source.reason)
            }
            val diagnosticsText = sanitized.firstOrNull { it.first.name == "diagnostics" }?.second.orEmpty()
            PreparedSend(
                built = built,
                comment = cleanComment,
                preview = Preview(
                    summary = summary,
                    privacySample = diagnosticsText.take(1500),
                    truncated = built.truncatedMetadata || sanitized.any { it.third },
                    hasMissing = sources.any { it.status == "missing" },
                    metadataBytes = built.metadataJson.toByteArray(Charsets.UTF_8).size,
                    totalBytes = built.attachments.sumOf { it.bytes },
                ),
                frozen = frozen,
            )
            } finally {
                secrets.forEach { it.fill('\u0000') }
            }
        }
    }

    /**
     * Resuelve el borrador para un snapshot preparado: reutiliza el fallido
     * con MISMO comentario y bytes, o acuña uno nuevo con su payload exacto.
     */
    fun resolveDraftForSend(context: Context, attemptId: String, comment: String,
        built: ForeverRemoteDiagnosticsReport.Built, preview: Preview): ForeverRemoteDiagnosticsStore.Draft {
        val app = context.applicationContext
        val payload = ForeverRemoteDiagnosticsStore.Payload(
            metadataJson = built.metadataJson,
            attachments = built.attachments.map { it.name to it.text },
            attemptId = attemptId,
            truncatedMetadata = built.truncatedMetadata,
            summary = preview.summary,
            privacySample = preview.privacySample,
            truncated = preview.truncated,
            hasMissing = preview.hasMissing,
        )
        return ForeverAssistedCredentialPrivacy.withDiagnosticsAllowed(app.filesDir) {
            check(!ForeverAssistedCredentialPrivacy.isSensitiveAttempt(app.filesDir, attemptId)) {
                "diagnostics export disabled for a sensitive assisted-credential attempt"
            }
            ForeverRemoteDiagnosticsStore.resolveReusableDraft(app.filesDir, comment, payload)
                ?: ForeverRemoteDiagnosticsStore.mintDraftWithPayload(app.filesDir, comment, payload)
        }
    }

    /**
     * Envía el payload PERSISTIDO del borrador (nunca una reconstrucción).
     * Si create devuelve complete sin uploads se saltan los PUT y se va
     * directo a complete idempotente. Solo desde Enviar, en hilo de fondo.
     */
    fun sendStored(context: Context, draftId: String, isRetry: Boolean, callback: (Result<String>) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        if (ForeverAssistedCredentialPrivacy.isSensitive(app.filesDir)) {
            main.post { callback(Result.failure(IllegalStateException("Los diagnósticos están desactivados durante la entrada asistida."))) }
            return
        }
        if (isRetry) recordRemoteDiagnosticsAction(app, "retry", "pending")
        thread(name = "forever-remote-diagnostics", isDaemon = true) {
            try {
                val confirmedId = ForeverAssistedCredentialPrivacy.withDiagnosticsUploadAdmission(app.filesDir) {
                    val (draft, stored) = ForeverAssistedCredentialPrivacy.withDiagnosticsAllowed(app.filesDir) {
                        val draft = ForeverRemoteDiagnosticsStore.readDraft(app.filesDir, draftId)
                            ?: throw IllegalStateException("draft is missing")
                        check(draft.attemptId == null || !ForeverAssistedCredentialPrivacy.isSensitiveAttempt(
                            app.filesDir, draft.attemptId)) {
                            "diagnostics export disabled for a sensitive assisted-credential attempt"
                        }
                        // The upload uses persisted bytes, never a reconstruction.
                        val stored = ForeverRemoteDiagnosticsStore.readPayload(app.filesDir, draft.draftId)
                            ?: throw IllegalStateException("draft payload is missing")
                        draft to stored
                    }
                    val created = ForeverRemoteDiagnosticsUpload.createReport(
                        stored.metadataJson, draft.draftId, draft.capability)
                    if (created.uploads.isNotEmpty()) {
                        val targets = created.uploads.associateBy { it.name }
                        for ((name, text) in stored.attachments) {
                            val target = targets[name]
                                ?: throw ForeverRemoteDiagnosticsUpload.Failure.Blob("missing_target")
                            ForeverRemoteDiagnosticsUpload.putBlob(target, text)
                        }
                    }
                    val confirmedId = ForeverRemoteDiagnosticsUpload.completeReport(created.id, draft.capability)
                    runCatching {
                        ForeverAssistedCredentialPrivacy.withDiagnosticsAllowed(app.filesDir) {
                            ForeverRemoteDiagnosticsStore.writeDraft(app.filesDir, draft.copy(reportId = confirmedId))
                        }
                    }
                    confirmedId
                }
                main.post { callback(Result.success(confirmedId)) }
            } catch (failure: Throwable) {
                val userMessage = when (failure) {
                    is ForeverRemoteDiagnosticsUpload.Failure.Transport -> "Sin conexión o el servidor no responde. Tu comentario y el borrador se conservan para reintentar."
                    is ForeverRemoteDiagnosticsUpload.Failure.Rejected -> "El servidor rechazó el informe. Puedes reintentarlo o probar más tarde."
                    is ForeverRemoteDiagnosticsUpload.Failure.Blob -> "Falló la subida de adjuntos. El borrador se conserva para reintentar sin duplicar."
                    is ForeverRemoteDiagnosticsUpload.Failure.Completion -> "El informe se subió pero no se pudo confirmar. Reintentar no lo duplica."
                    else -> "No se pudo enviar el diagnóstico. El borrador se conserva para reintentar."
                }
                main.post { callback(Result.failure(IllegalStateException(userMessage, failure))) }
            }
        }
    }

    /**
     * Sumidero para el callback ya existente de ProcessHelper (siempre activo
     * en C117 cuando hay debug callback): fija el intento durable vigente UNA
     * vez al crear el closure y vuelca cada línea al log Wine acotado del
     * intento. Sin observadores nuevos, sin red, sin envío automático.
     * Contrato del padre: append(line) y finished(status), ambos read-only
     * para el runtime (errores capturados, sin efecto en el juego).
     */
    fun outputSink(context: Context): OutputSink? {
        val app = context.applicationContext
        if (ForeverAssistedCredentialPrivacy.isSensitive(app.filesDir)) return null
        val attemptId = runCatching {
            ForeverRemoteDiagnosticsStore.readLatestIntent(app.filesDir)?.attemptId
        }.getOrNull() ?: return null
        if (ForeverAssistedCredentialPrivacy.isSensitiveAttempt(app.filesDir, attemptId)) return null
        if (ForeverRemoteDiagnosticsWineLog.fileFor(ForeverRemoteDiagnosticsStore.root(app.filesDir), attemptId) == null) {
            return null
        }
        return OutputSink(app.filesDir, attemptId)
    }

    class OutputSink internal constructor(private val filesDir: File, private val attemptId: String) {
        /** Llamado desde el worker del reader existente; acotado, sin UI. */
        fun append(line: String) {
            runCatching {
                ForeverAssistedCredentialPrivacy.runIfAttemptDiagnosticsAllowed(filesDir, attemptId) {
                    ForeverRemoteDiagnosticsWineLog.append(
                        ForeverRemoteDiagnosticsStore.root(filesDir), attemptId, line)
                }
            }
        }

        /**
         * Callback de terminación del programa guest real (wrapper Wine, no
         * pasos de preinstall): guarda exitCode + fin y outcome wine-exit
         * ok/failed según status, SOLO en el intento fijado al crear el sink.
         * No prueba WoW/auth/render ni afirma quiescencia del árbol.
         */
        fun finished(status: Int) {
            runCatching {
                // This bounded terminal status is safe metadata, not captured
                // process content, and remains available during assisted entry.
                ForeverRemoteDiagnosticsStore.recordSync(
                    filesDir, "wine-exit", if (status == 0) "ok" else "failed")
                ForeverRemoteDiagnosticsStore.updateIntent(
                    filesDir, attemptId, result = if (status == 0) "wine_exit" else "wine_exit_failed", stage = "running",
                    endedAtMs = System.currentTimeMillis(), exitCode = status)
            }
        }
    }
}
