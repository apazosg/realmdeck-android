package app.gamenative

import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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

/** Local inspection on open; remote check, update planning and download confirmation stay explicit. */
@Composable
fun ForeverClientManagementDialog(
    onDismiss: () -> Unit,
    onClientChanged: () -> Unit,
    onPlay: () -> Unit = onDismiss,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val alive = remember { AtomicBoolean(true) }
    val cancel = remember { AtomicBoolean(false) }
    val progressFlow = remember { MutableStateFlow("") }
    val progress by progressFlow.collectAsState()
    var busy by remember { mutableStateOf(true) }
    var applying by remember { mutableStateOf(false) }
    var inspection by remember { mutableStateOf<ForeverClientUpdateService.Inspection?>(null) }
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
        runOperation("Buscando actualizaciones…") { service ->
            val local = service.inspect()
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
        if (!execution.busy.value) inspectLocal()
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
                    error = "Descarga pausada. Puedes continuar cuando quieras."
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
            service.preparePublishedVersion(
                ForeverClientUpdateService.MetadataConsent(target, 1L shl 30, false), cancel::get,
            ) { stage, completed, total ->
                progressFlow.value = if (total > 0) "Consultando archivos: ${ForeverFunctionalUiText.clientBytes(completed)} de ${ForeverFunctionalUiText.clientBytes(total)}" else "Consultando archivos…"
            }
            service.qualify(cancel::get)
            val current = service.inspect()
            val computed = service.plan(cancel::get, ::publishProgress)
            withContextOnMain {
                if (alive.get()) { inspection = current; plan = computed }
            }
        }
    }

    Dialog(
        onDismissRequest = {
            when {
                !(busy || executionBusy) -> onDismiss()
                executionBusy && !executionApplying -> onDismiss()
                !applying -> cancel.set(true)
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = true),
    ) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("WoW", style = MaterialTheme.typography.titleLarge)
                inspection?.let { current ->
                    Text("Ubicación: ${current.storageLabel}")
                    Text(current.installedVersion?.let { "Instalado: $it" } ?: if (current.clientPresent)
                        "Hay archivos del cliente, pero no se pudo identificar la instalación." else "Cliente no instalado.")
                }
                if (busy || executionBusy) {
                    CircularProgressIndicator()
                    Text(if (executionBusy) executionProgress else progress)
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
                            onClick = {
                                applying = false
                                error = null
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val service = ForeverClientUpdateService(context)
                                            try { service.inspect() } finally { service.close() }
                                            ForeverClientUpdateExecution.resetFor(readyPlan)
                                            context.startForegroundService(
                                                Intent(context, ForeverClientUpdateForegroundService::class.java),
                                            )
                                        }
                                    } catch (failure: Exception) {
                                        Log.w("ForeverClientUpdate", "download handoff to foreground service failed", failure)
                                        if (alive.get()) error = failureMessage(failure)
                                    }
                                }
                            },
                        ) { Text("Confirmar descarga") }
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
                TextButton(enabled = !(busy && !executionBusy), onClick = onDismiss) { Text("Volver") }
            }
        }
    }
}

/** Blocking worker bridge: keep Compose state publication on Main without moving IO there. */
private fun withContextOnMain(action: () -> Unit) = kotlinx.coroutines.runBlocking {
    withContext(NonCancellable + Dispatchers.Main) { action() }
}
