package app.gamenative

import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

/** Sample the clock and estimate together, independently of progress recompositions. */
@Composable
fun ForeverClientDownloadEstimate() {
    val detail by ForeverClientUpdateExecution.detail.collectAsState()
    val active by ForeverClientUpdateExecution.busy.collectAsState()
    val phase = detail?.phase
    var estimateText by remember(active, phase) { mutableStateOf("Sin estimación") }
    LaunchedEffect(active, phase) {
        while (active && phase == ForeverClientUpdateProgress.Phase.DOWNLOADING) {
            // A cached UI clock can precede a fresh network sample and falsely
            // invalidate it. Format once per second with a fresh monotonic clock.
            val rate = ForeverClientUpdateExecution.rate
            val estimate = synchronized(rate) { rate.estimate(android.os.SystemClock.elapsedRealtime()) }
            estimateText = estimate?.text() ?: "Sin estimación"
            delay(1_000)
        }
    }
    if (active && phase == ForeverClientUpdateProgress.Phase.DOWNLOADING) {
        Text(estimateText, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"))
    }
}

/** Opening reads local state only; Home may supply its missing-client presentation snapshot. */
@Composable
fun ForeverClientManagementDialog(
    onDismiss: () -> Unit,
    onClientChanged: () -> Unit,
    onPlay: () -> Unit = onDismiss,
    inline: Boolean = false,
    initialMissingStorageLabel: String? = null,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val alive = remember { AtomicBoolean(true) }
    val cancel = remember { AtomicBoolean(false) }
    val progressFlow = remember { MutableStateFlow("") }
    val progress by progressFlow.collectAsState()
    val missingHome = if (inline) initialMissingStorageLabel else null
    var busy by remember { mutableStateOf(missingHome == null && !ForeverClientUpdateExecution.busy.value) }
    var details by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    // Presentation only: never use this snapshot to admit, plan or execute a
    // mutation. Continue/checkVersions and the engine still revalidate freshly.
    var inspection by remember { mutableStateOf(missingHome?.let {
        ForeverClientUpdateService.Inspection(null, "", it, 0, "", false, null)
    }) }
    var published by remember { mutableStateOf<ForeverClientUpdateCheck.Fingerprint?>(null) }
    var plan by remember { mutableStateOf<ForeverClientUpdatePlan?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val execution = remember { ForeverClientUpdateExecution }
    val executionBusy by execution.busy.collectAsState()
    val executionApplying by execution.applying.collectAsState()
    val executionProgress by execution.progress.collectAsState()

    fun failureMessage(failure: Exception): String = ForeverFunctionalUiText.userMessage(failure)

    fun runOperation(title: String, work: (ForeverClientUpdateService) -> Unit) {
        busy = true
        error = null
        cancel.set(false)
        progressFlow.value = title
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val service = ForeverClientUpdateService(context)
                    try { work(service) } finally { service.close() }
                }
            } catch (_: CancellationException) {
                if (alive.get()) error = "Descarga pausada. Puedes continuar cuando quieras."
            } catch (failure: Exception) {
                Log.w("ForeverClientUpdate", "client operation failed", failure)
                if (alive.get()) error = failureMessage(failure)
            } finally {
                if (alive.get()) { busy = false; applying = false }
            }
        }
    }

    // Opening the dialog only reads the local installation. Remote version
    // checks are an explicit user action (checkForUpdates) and never gate play.
    fun inspectLocal() {
        runOperation("Comprobando instalación…") { service ->
            val local = service.inspect()
            withContextOnMain {
                if (alive.get()) { inspection = local; plan = null }
            }
        }
    }

    fun checkForUpdates() {
        // This snapshot is presentation only. checkVersions still performs fresh
        // installed-content admission; execute/plan retain their own revalidation.
        val localSnapshot = inspection
        runOperation("Buscando actualizaciones…") { service ->
            val local = localSnapshot ?: service.inspect()
            val remote = try {
                service.checkVersions()
            } catch (failure: Exception) {
                Log.w("ForeverClientUpdate", "version check failed", failure)
                withContextOnMain {
                    if (alive.get()) { inspection = local; plan = null }
                }
                throw failure
            }
            if (remote.published == null) {
                withContextOnMain {
                    if (alive.get()) { inspection = local; plan = null }
                }
                throw IllegalStateException("network versions unavailable")
            }
            withContextOnMain {
                if (alive.get()) { inspection = local; published = remote.published; plan = null }
            }
        }
    }

    LaunchedEffect(Unit) {
        // While a foreground download owns the service lease, reopening the dialog
        // must not collide with it; the hub flows already describe the state.
        if (!execution.busy.value && missingHome == null) inspectLocal()
    }
    LaunchedEffect(Unit) {
        execution.outcome.collect { outcome ->
            when (outcome) {
                is ForeverClientUpdateExecution.Outcome.Done -> {
                    error = null
                    inspection = execution.inspection.value
                    plan = null
                    if (alive.get()) onClientChanged()
                }
                is ForeverClientUpdateExecution.Outcome.Paused -> {
                    error = if (inline) null else "Descarga pausada. Puedes continuar cuando quieras."
                    plan = null
                }
                is ForeverClientUpdateExecution.Outcome.Failed -> {
                    error = failureMessage(outcome.failure)
                    plan = null
                }
                null -> return@collect
            }
            execution.outcome.value = null
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            alive.set(false)
            // A foreground download keeps running; only dialog-owned work is cancelled.
            if (!execution.busy.value) cancel.set(true)
        }
    }
    // Back never pauses implicitly: a foreground download keeps running and
    // back navigates home, exactly like tapping outside the dialog. Pause is
    // only the explicit "Pausar" button. Local detection/planning work is
    // still cancelled on exit; the atomic apply/recover phases stay locked.
    BackHandler(enabled = busy || executionBusy) {
        if (executionBusy) {
            if (!applying && !executionApplying) onDismiss()
        } else if (!applying) {
            cancel.set(true)
        }
    }

    fun publishProgress(update: ForeverClientUpdateProgress) {
        val atomicPhase = ForeverFunctionalUiText.isAtomicPhase(update.phase)
        withContextOnMain { if (alive.get()) applying = atomicPhase }
        progressFlow.value = ForeverFunctionalUiText.downloadStatusText(update, progressFlow.value)
    }

    // Explicit metadata consent + planning. The service still enforces its own
    // guest/storage/qualification gates; this only sequences the user action.
    fun startUpdate(target: ForeverClientUpdateCheck.Fingerprint) {
        runOperation("Preparando la descarga…") { service ->
            val current = service.preparePublishedVersion(
                ForeverClientUpdateService.MetadataConsent(target, 1L shl 30, false), cancel::get,
            ) { stage, completed, total ->
                progressFlow.value = ForeverFunctionalUiText.preparationStatus(stage, completed, total)
            }
            service.qualify(cancel::get)
            val computed = service.plan(cancel::get, ::publishProgress)
            withContextOnMain {
                if (alive.get()) { inspection = current; plan = computed }
            }
        }
    }

    fun handoff(readyPlan: ForeverClientUpdatePlan) = withContextOnMain {
        if (!alive.get() || cancel.get()) throw CancellationException("download handoff cancelled")
        ForeverClientUpdateExecution.resetFor(readyPlan)
        try {
            context.startForegroundService(Intent(context, ForeverClientUpdateForegroundService::class.java))
        } catch (failure: Exception) {
            if (!execution.busy.value && execution.pendingPlan === readyPlan) execution.pendingPlan = null
            throw failure
        }
    }

    fun beginDownload(readyPlan: ForeverClientUpdatePlan) {
        runOperation("Comprobando almacenamiento…") { service ->
            // The user can leave the consent screen open beyond the receipt's
            // lifetime. Renew the proof, never relax the executor's age gate.
            service.qualify(cancel::get)
            handoff(readyPlan)
        }
    }

    fun continueInstallation() {
        runOperation("Consultando versión…") { service ->
            val remote = service.checkVersions()
            val target = remote.published ?: throw IllegalStateException("network versions unavailable")
            val current = service.preparePublishedVersion(
                ForeverClientUpdateService.MetadataConsent(target, 1L shl 30, false), cancel::get,
            ) { stage, completed, total ->
                progressFlow.value = ForeverFunctionalUiText.preparationStatus(stage, completed, total)
            }
            service.qualify(cancel::get)
            val computed = service.plan(cancel::get, ::publishProgress)
            withContextOnMain {
                if (alive.get()) { published = target; inspection = current; plan = computed }
            }
        }
    }

    fun resumeDownload(previous: ForeverClientUpdatePlan) {
        runOperation("Comprobando descarga pendiente…") { service ->
            service.qualify(cancel::get)
            val computed = service.plan(cancel::get, ::publishProgress)
            val consent = ForeverClientUpdateConsent(previous.planId, previous.downloadBytes,
                previous.peakAdditionalBytes, previous.privateWorkBytes, previous.destinationBytes)
            // The ID binds identity/snapshot; residual costs must stay within
            // the original consent, not prompt again just for saved progress.
            if (execution.canResume(computed, consent, previous.sourceRevision)) {
                handoff(computed)
            } else withContextOnMain { if (alive.get()) plan = computed }
        }
    }

    val content: @Composable () -> Unit = {
        Surface(shape = MaterialTheme.shapes.large,
            color = if (inline) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxWidth().then(if (inline) Modifier.animateContentSize() else Modifier)
                    .heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(if (inline) 8.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(if (inline && inspection?.installedVersion == null) "Instalar WoW" else "WoW",
                    style = if (inline) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
                inspection?.let { current ->
                    Text("Destino: ${current.storageLabel}")
                    if (!inline || details) Text(current.installedVersion?.let { "Instalado: $it" } ?: if (current.clientPresent)
                        "Hay archivos del cliente, pero no se pudo identificar la instalación." else "Cliente no instalado.")
                }
                if (busy || executionBusy) {
                    val detail by execution.detail.collectAsState()
                    val fraction = if (executionBusy) ForeverFunctionalUiText.downloadFraction(detail) else null
                    if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(if (executionBusy) executionProgress else progress)
                    ForeverClientDownloadEstimate()
                    TextButton(enabled = !applying && !executionApplying, onClick = {
                        if (executionBusy) {
                            context.startService(
                                Intent(context, ForeverClientUpdateForegroundService::class.java)
                                    .setAction(ForeverClientUpdateForegroundService.ACTION_PAUSE),
                            )
                        } else {
                            cancel.set(true)
                        }
                    }) {
                        if (inline && !(applying || executionApplying)) Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text(if (applying || executionApplying) "Aplicando…" else "Pausar")
                    }
                } else {
                    val readyPlan = plan
                    val local = inspection
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (readyPlan != null) {
                        Text("Versión: ${readyPlan.version}")
                        Text("Descarga: ${ForeverFunctionalUiText.clientBytes(readyPlan.downloadBytes)}")
                        Text("Espacio adicional: ${ForeverFunctionalUiText.clientBytes(readyPlan.peakAdditionalBytes)}")
                        if (!readyPlan.hasEnoughSpace) Text("No hay espacio suficiente.", color = MaterialTheme.colorScheme.error)
                        Button(
                            enabled = readyPlan.hasEnoughSpace,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            onClick = { beginDownload(readyPlan) },
                        ) {
                            if (inline) Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text(if (inline) "Descargar" else "Confirmar descarga")
                        }
                    } else if (inline && execution.resumePlan != null) {
                        Text("En pausa")
                        Button(onClick = { execution.resumePlan?.let(::resumeDownload) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("Reanudar")
                        }
                    } else if (inline && local != null && !local.clientPresent) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Wifi, contentDescription = "Wi-Fi")
                            Text("Preparación: hasta 1 GiB de metadatos", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = ::continueInstallation, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("Continuar")
                        }
                    } else if (local != null) {
                        val installed = local.installedVersion
                        val latest = published
                        val pastedReady = ForeverClientUpdateService.isPastedSourceCompletable(local.pastedReport)
                        if (installed != null && latest != null && installed != latest.version) {
                            Text("Disponible: ${latest.version}")
                            Text("Al continuar se consultan los archivos de la versión (hasta 1 GiB). El tamaño del juego se confirma después.",
                                style = MaterialTheme.typography.bodySmall)
                            Button(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                onClick = { startUpdate(latest) },
                            ) { Text("Actualizar") }
                            TextButton(onClick = onPlay) { Text("Jugar") }
                        } else if (installed != null) {
                            if (latest != null) Text("El cliente está actualizado.")
                            Button(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                onClick = onPlay,
                            ) { Text("Jugar") }
                            if (latest == null) TextButton(onClick = ::checkForUpdates) { Text("Buscar actualizaciones") }
                        } else if (latest != null) {
                            if (local.clientPresent && !pastedReady) {
                                Button(onClick = ::checkForUpdates, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Reintentar detección") }
                            } else {
                                Text("Disponible: ${latest.version}")
                                Text("Al continuar se consultan los archivos de la versión (hasta 1 GiB). El tamaño del juego se confirma después.",
                                    style = MaterialTheme.typography.bodySmall)
                                Button(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    onClick = { startUpdate(latest) },
                                ) { Text(if (pastedReady) "Completar la instalación" else "Descargar e instalar") }
                            }
                        } else {
                            Button(onClick = ::checkForUpdates, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Buscar actualizaciones") }
                        }
                    } else {
                        Button(onClick = ::inspectLocal, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Reintentar") }
                    }
                }
                if (inline && !inspection?.detail.isNullOrBlank()) {
                    TextButton(onClick = { details = !details }, modifier = Modifier.semantics {
                        stateDescription = if (details) "Expandido" else "Contraído"
                    }) {
                        Icon(if (details) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                        Text("Detalles")
                    }
                    if (details) inspection?.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                TextButton(enabled = !(busy && !executionBusy), onClick = onDismiss) { Text("Volver") }
            }
        }
    }
    if (inline) content() else Dialog(
        onDismissRequest = {
            when {
                !(busy || executionBusy) -> onDismiss()
                executionBusy && !executionApplying -> onDismiss()
                !applying -> cancel.set(true)
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = true),
    ) {
        content()
    }
}

/** Blocking worker bridge: keep Compose state publication on Main without moving IO there. */
private fun withContextOnMain(action: () -> Unit) = kotlinx.coroutines.runBlocking {
    withContext(NonCancellable + Dispatchers.Main) { action() }
}
