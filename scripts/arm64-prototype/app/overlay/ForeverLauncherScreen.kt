package app.gamenative

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.gamenative.ui.screen.xserver.XServerScreen
import java.util.Locale

@Composable
fun ForeverLauncherScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { ForeverLaunchController.get(context) }
    val state by controller.state.collectAsState()
    var consent by rememberSaveable { mutableStateOf(false) }
    var exportRunId by rememberSaveable { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val runId = exportRunId
        if (uri != null && runId != null) controller.export(runId, uri, scope)
    }

    DisposableEffect(controller) {
        // The coordinator is application-scoped. Configuration changes must not cancel it;
        // MainActivity calls onActivityDestroyed for a real Activity destruction.
        onDispose { controller.onActivityDestroyed(changingConfigurations = true) }
    }

    val appId = state.appId
    if (state.gameVisible && appId != null) {
        XServerScreen(
            appId = appId,
            bootToContainer = false,
            testGraphics = false,
            diagnostics = false,
            debugRun = false,
            isOffline = false,
            registerBackAction = { /* GameNative dispatches back through XServerScreen. */ },
            navigateBack = { controller.onGameExit(state.runId) },
            onExit = { completion -> controller.onGameExit(state.runId); completion?.invoke() },
            onGameLaunchError = { controller.onLaunchError(state.runId, it) },
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("WoW Forever", style = MaterialTheme.typography.headlineMedium)
        Text("Cliente ARM64 experimental · GameNative Bionic · Vulkan del sistema", modifier = Modifier.padding(top = 8.dp))
        state.plan?.let { plan ->
            Text(
                "Descarga inicial: ${formatBytes(plan.downloadBytes)} · espacio de trabajo: ${formatBytes(plan.workspaceBytes)}",
                modifier = Modifier.padding(top = 18.dp),
            )
            Text(plan.runtimeWorkspaceNote, modifier = Modifier.padding(top = 6.dp))
            Text("Se descarga solo con tu confirmación. El cliente y CASC nunca se incluyen en el APK.", modifier = Modifier.padding(top = 6.dp))
            Column(modifier = Modifier.padding(top = 12.dp)) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = consent, onCheckedChange = { consent = it })
                    Text("Entiendo y autorizo esta descarga")
                }
            }
        } ?: Text("Catálogo de cliente no disponible en este APK.", color = MaterialTheme.colorScheme.error)

        if (state.operationActive) {
            CircularProgressIndicator(modifier = Modifier.padding(20.dp))
            Text(state.message ?: "Preparando…")
            Button(onClick = controller::cancel, modifier = Modifier.padding(top = 12.dp)) { Text("Cancelar") }
        } else {
            if (state.blockedByUnknownProcess) {
                Text("Terminación del árbol de procesos no demostrada. Exporta el informe y reinicia Android antes de otro intento; no hace falta borrar datos.", color = MaterialTheme.colorScheme.error)
            }
            Button(
                enabled = consent && state.plan != null && !state.blockedByUnknownProcess,
                onClick = { controller.prepareAndLaunch(scope) },
                modifier = Modifier.padding(top = 18.dp),
            ) { Text("Preparar y lanzar") }
        }
        state.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 14.dp)) }
        state.message?.let { if (!state.operationActive) Text(it, modifier = Modifier.padding(top = 8.dp)) }
        if (state.processStarted) {
            Text(
                "Wine: iniciado · WoW: ${if (state.gameIdentityObserved) "observado" else "no demostrado"} · " +
                    "render: ${state.renderState} · login Forever: ${state.loginState}",
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        state.runId?.let { runId ->
            Button(
                onClick = {
                    exportRunId = runId
                    exportLauncher.launch("forever-$runId.zip")
                },
                modifier = Modifier.padding(top = 12.dp),
            ) { Text("Exportar diagnóstico de esta corrida") }
        }
        Text(
            "El launcher no pide credenciales. Revisa el ZIP antes de compartirlo: puede contener salida del juego.",
            modifier = Modifier.padding(top = 18.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun formatBytes(value: Long): String {
    val units = listOf("B", "KiB", "MiB", "GiB")
    var number = value.toDouble()
    var index = 0
    while (number >= 1024 && index < units.lastIndex) {
        number /= 1024
        index++
    }
    return String.format(Locale.ROOT, "%.1f %s", number, units[index])
}
