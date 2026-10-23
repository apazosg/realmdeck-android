package app.gamenative

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Diálogo de diagnóstico remoto: comentario + Enviar. Sin frontend.
 *
 * No hay red antes de Enviar: abrir el diálogo solo crea el snapshot local y
 * no acuña borrador. Cancelar nunca provoca red. El error conserva comentario
 * y borrador con su ID para reintentar sin duplicados; el éxito devuelve el ID.
 */
@Composable
fun ForeverRemoteDiagnosticsDialog(
    attemptId: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var comment by remember { mutableStateOf("") }
    var recoveredNote by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(true) }
    var prepared by remember { mutableStateOf<ForeverRemoteDiagnosticsAndroid.PreparedSend?>(null) }
    var prepareError by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var reportId by remember { mutableStateOf<String?>(null) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var retried by remember { mutableStateOf(false) }
    var activeAttemptId by remember { mutableStateOf(attemptId) }
    var activeDraft by remember { mutableStateOf<ForeverRemoteDiagnosticsStore.Draft?>(null) }
    var storedPayload by remember { mutableStateOf<ForeverRemoteDiagnosticsStore.Payload?>(null) }

    fun buildSnapshot(currentComment: String, forAttempt: String) {
        preparing = true
        prepareError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ForeverRemoteDiagnosticsAndroid.prepare(context, forAttempt, currentComment) }
            }
            prepared = result.getOrNull()
            prepareError = result.exceptionOrNull()?.let { failure ->
                if (failure.message?.contains("credential store unavailable") == true) {
                    "El almacén de credenciales no está disponible; no se envía nada sin sanear."
                } else {
                    "No se pudo preparar el diagnóstico"
                }
            }
            preparing = false
        }
    }

    LaunchedEffect(Unit) {
        val (failed, intent) = withContext(Dispatchers.IO) {
            runCatching {
                ForeverRemoteDiagnosticsStore.latestFailedDraft(context.filesDir) to
                    ForeverRemoteDiagnosticsStore.readLatestIntent(context.filesDir)
            }.getOrDefault(null to null)
        }
        val payload = failed?.let {
            withContext(Dispatchers.IO) {
                runCatching { ForeverRemoteDiagnosticsStore.readPayload(context.filesDir, it.draftId) }.getOrNull()
            }
        }
        if (failed != null && payload != null) {
            // Reutilización directa: MISMO payload/IDs, sin prepare nuevo.
            comment = failed.comment
            activeDraft = failed
            storedPayload = payload
            activeAttemptId = payload.attemptId ?: failed.attemptId ?: attemptId
            prepared = ForeverRemoteDiagnosticsAndroid.PreparedSend(
                built = ForeverRemoteDiagnosticsReport.Built(
                    metadataJson = payload.metadataJson,
                    attachments = payload.attachments.map { (name, text) ->
                        ForeverRemoteDiagnosticsReport.Attachment(name, text)
                    },
                    truncatedMetadata = payload.truncatedMetadata,
                ),
                comment = failed.comment,
                preview = ForeverRemoteDiagnosticsAndroid.Preview(
                    summary = payload.summary,
                    privacySample = payload.privacySample,
                    truncated = payload.truncated,
                    hasMissing = payload.hasMissing,
                    metadataBytes = payload.metadataBytes,
                    totalBytes = payload.totalBytes,
                ),
                frozen = true,
            )
            preparing = false
            recoveredNote = "Borrador conservado de un envío anterior. Reintentar no duplica el informe."
            retried = true
            return@LaunchedEffect
        }
        if (failed != null) {
            comment = failed.comment
            recoveredNote = "Borrador conservado de un envío anterior. Reintentar no duplica el informe."
            retried = true
        } else if (intent?.result != null && intent.attemptId == attemptId) {
            recoveredNote = "Intento anterior: ${intent.result}" +
                (intent.error?.let { " · $it" } ?: "") +
                ". Reintentar no duplica el informe."
        }
        activeAttemptId = intent?.attemptId ?: attemptId
        buildSnapshot(comment, activeAttemptId)
    }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text("Enviar diagnóstico", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Se envía solo al pulsar Enviar. Solo el mantenedor puede " +
                        "leerlo y se borra a los 7 días. No escribas contraseñas " +
                        "ni datos personales: el saneado es automático pero no " +
                        "garantiza la eliminación total de datos sensibles. " +
                        "Cancelar no envía nada.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (recoveredNote != null) {
                    Text(
                        recoveredNote!!,
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { value ->
                        comment = value.take(ForeverRemoteDiagnosticsContract.COMMENT_MAX_CHARS)
                        sendError = null
                    },
                    enabled = !sending,
                    label = { Text("Comentario (qué falló, qué esperabas)") },
                    singleLine = false,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                when {
                    preparing -> {
                        CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
                    }
                    prepared != null -> {
                        val preview = prepared!!.preview
                        Text(
                            "Adjuntos: ${preview.summary.joinToString("; ")}",
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Metadatos: ${preview.metadataBytes} B · total adjuntos: ${preview.totalBytes} B" +
                                (if (preview.truncated) " · con recortes" else "") +
                                (if (preview.hasMissing) " · con fuentes ausentes" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (preview.privacySample.isNotEmpty()) {
                            Text(
                                "Revisión antes de enviar:\n${preview.privacySample}",
                                modifier = Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (reportId != null) {
                            Text(
                                "Informe enviado: $reportId",
                                modifier = Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                    prepareError != null -> {
                        Text(
                            prepareError!!,
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (sendError != null) {
                    Text(
                        sendError!!,
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (reportId == null) {
                Button(
                    enabled = !preparing && !sending && prepared != null,
                    onClick = {
                        val draft = activeDraft
                        // Reutilización exacta: mismo comentario y payload
                        // persistido, sin prepare nuevo ni IDs nuevos.
                        if (draft != null && storedPayload != null && comment == draft.comment) {
                            sending = true
                            sendError = null
                            ForeverRemoteDiagnosticsAndroid.sendStored(
                                context, draft.draftId, isRetry = true,
                            ) { result ->
                                sending = false
                                retried = true
                                result
                                    .onSuccess { id -> reportId = id }
                                    .onFailure { failure -> sendError = failure.message }
                            }
                            return@Button
                        }
                        val snapshot = prepared ?: return@Button
                        // Cambiar el comentario crea un borrador nuevo.
                        sending = true
                        sendError = null
                        scope.launch {
                            val fresh = withContext(Dispatchers.IO) {
                                runCatching {
                                    if (comment == snapshot.comment) {
                                        snapshot
                                    } else {
                                        ForeverRemoteDiagnosticsAndroid.prepare(context, activeAttemptId, comment)
                                    }
                                }
                            }
                            val ready = fresh.getOrNull()
                            if (ready == null) {
                                sending = false
                                sendError = "No se pudo preparar el diagnóstico"
                                return@launch
                            }
                            prepared = ready
                            val resolved = withContext(Dispatchers.IO) {
                                runCatching {
                                    ForeverRemoteDiagnosticsAndroid.resolveDraftForSend(
                                        context, activeAttemptId, comment, ready.built, ready.preview)
                                }.getOrNull()
                            }
                            if (resolved == null) {
                                sending = false
                                sendError = "No se pudo preparar el diagnóstico"
                                return@launch
                            }
                            activeDraft = resolved
                            storedPayload = withContext(Dispatchers.IO) {
                                runCatching {
                                    ForeverRemoteDiagnosticsStore.readPayload(context.filesDir, resolved.draftId)
                                }.getOrNull()
                            }
                            ForeverRemoteDiagnosticsAndroid.sendStored(
                                context, resolved.draftId, isRetry = retried || sendError != null,
                            ) { result ->
                                sending = false
                                retried = true
                                result
                                    .onSuccess { id -> reportId = id }
                                    .onFailure { failure -> sendError = failure.message }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (sending) "Enviando…" else "Enviar") }
            } else {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Cerrar")
                }
            }
        },
        dismissButton = {
            if (reportId == null) {
                TextButton(
                    enabled = !sending,
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Cancelar") }
            }
        },
    )
}

/** Registra una acción semántica sin red y sin lanzar nada. Nunca falla. */
fun recordRemoteDiagnosticsAction(context: Context, action: String, outcome: String = "ok") {
    runCatching {
        ForeverRemoteDiagnosticsStore.record(context.filesDir, action, outcome)
    }
}

/** Ruta síncrona para la intención crítica antes de los gates. Nunca falla. */
fun recordRemoteDiagnosticsActionSync(context: Context, action: String, outcome: String = "ok") {
    runCatching {
        ForeverRemoteDiagnosticsStore.recordSync(context.filesDir, action, outcome)
    }
}
