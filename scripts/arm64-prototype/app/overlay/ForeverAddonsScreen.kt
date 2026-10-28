package app.gamenative

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.net.URI

private val AddonsGold = Color(0xFFD6B45A)
private val AddonsInk = Color(0xFF191713)

@Composable
internal fun ForeverAddonsScreen(
    service: ForeverAddonsService,
    onDismiss: () -> Unit,
    onBusyChanged: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var inventory by remember { mutableStateOf<ForeverAddonsService.Inventory?>(null) }
    var inventoryError by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var search by remember { mutableStateOf<ForeverAddonsApi.Search?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var packageInfo by remember { mutableStateOf<ForeverAddonsService.PackageInfo?>(null) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("") }
    var uninstallTarget by remember { mutableStateOf<ForeverAddons.Addon?>(null) }
    var confirmInstall by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    fun refreshInventory() {
        scope.launch {
            busy = true
            onBusyChanged(true)
            inventoryError = null
            try { inventory = service.inventory() }
            catch (failure: Throwable) { inventory = null; inventoryError = service.message(failure) }
            finally { busy = false; onBusyChanged(false) }
        }
    }

    LaunchedEffect(service) {
        busy = true
        onBusyChanged(true)
        try { inventory = service.inventory() }
        catch (failure: Throwable) { inventory = null; inventoryError = service.message(failure) }
        finally { busy = false; onBusyChanged(false) }
    }

    fun runOperation(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            onBusyChanged(true)
            operationError = null
            progress = ""
            try { action() }
            catch (failure: Throwable) {
                operationError = service.message(failure)
                // Surface a retained journal immediately, rather than showing stale controls.
                try { inventory = service.inventory(); inventoryError = null }
                catch (refreshFailure: Throwable) { inventory = null; inventoryError = service.message(refreshFailure) }
            }
            finally { busy = false; onBusyChanged(false) }
        }
    }

    fun searchAt(index: Int) {
        val explicitQuery = if (index == 0) query.trim() else searchQuery
        if (explicitQuery.isEmpty()) { searchError = "Escribe un nombre para buscar."; return }
        if (index == 0) {
            searchQuery = explicitQuery
            search = null
            packageInfo = null
        }
        scope.launch {
            busy = true
            onBusyChanged(true)
            searchError = null
            operationError = null
            progress = ""
            try {
                search = service.search(explicitQuery, index)
            } catch (failure: Throwable) {
                searchError = service.message(failure)
            } finally { busy = false; onBusyChanged(false) }
        }
    }

    BackHandler(enabled = true) { if (!busy) onDismiss() }
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = !busy, usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).heightIn(max = 760.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF211F1A),
            contentColor = Color(0xFFF1E8D2),
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Addons", style = MaterialTheme.typography.headlineSmall, color = AddonsGold, fontWeight = FontWeight.Bold)
                        Text("World of Warcraft · Classic Forever", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cerrar") }
                }
                Spacer(Modifier.height(8.dp))
                TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = AddonsGold) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Instalados") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Buscar") })
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    operationError?.let { Notice(it, error = true) }
                    if (tab == 0) {
                        inventoryError?.let { Notice(it, error = true) }
                        inventory?.let { local ->
                            Text("Destino: ${local.destination}", style = MaterialTheme.typography.bodySmall, color = AddonsGold)
                            if (local.recoveryRequired) {
                                Notice("Una operación anterior necesita recuperación antes de administrar addons.", error = true)
                                Button(
                                    enabled = !busy,
                                    onClick = { runOperation { inventory = service.recover(); inventoryError = null } },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AddonsGold, contentColor = AddonsInk),
                                ) { Text("Recuperar operación") }
                            } else if (local.addons.isEmpty()) {
                                Notice("No hay addons instalados. Puedes buscarlos en la pestaña Buscar.")
                            } else {
                                local.addons.forEach { addon ->
                                    AddonRow(
                                        addon = addon,
                                        enabled = !busy,
                                        onToggle = { value -> runOperation { service.setEnabled(addon, value); inventory = service.inventory() } },
                                        onUninstall = { uninstallTarget = addon },
                                    )
                                }
                            }
                        }
                        if (inventory == null && inventoryError == null) Loading("Leyendo addons instalados…")
                        TextButton(enabled = !busy, onClick = { refreshInventory() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Actualizar inventario") }
                    } else {
                        Text("La búsqueda solo se ejecuta cuando pulsas Buscar.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it.take(100) },
                            label = { Text("Nombre del addon") },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            enabled = !busy && query.trim().isNotEmpty(),
                            onClick = { searchAt(0) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AddonsGold, contentColor = AddonsInk),
                        ) { Text("Buscar") }
                        searchError?.let { Notice(it, error = true) }
                        search?.let { page ->
                            if (page.projects.isEmpty()) Notice("No se encontraron addons para esta búsqueda.")
                            page.projects.forEach { project ->
                                ProjectRow(
                                    project = project,
                                    enabled = !busy,
                                    onOpen = {
                                        packageInfo = null
                                        runOperation {
                                            packageInfo = service.packageFor(project)
                                            progress = ""
                                        }
                                    },
                                    onOpenLink = { url -> try { uriHandler.openUri(url) } catch (_: Exception) { } },
                                )
                            }
                            page.nextIndex?.let { next ->
                                Button(enabled = !busy, onClick = { searchAt(next) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text("Siguiente página")
                                }
                            }
                        }
                        packageInfo?.let { info ->
                            Divider(color = AddonsGold.copy(alpha = 0.45f))
                            Text("Archivo disponible", color = AddonsGold, fontWeight = FontWeight.Bold)
                            Text("${info.project.name} · ${info.file.name}")
                            Text("Archivo: ${info.file.fileName}")
                            Text("Destino: ${info.destination}")
                            Text("Tamaño: ${info.file.size} bytes")
                            if (info.installed) Notice("Este addon ya está instalado. Puedes administrarlo en Instalados.")
                            if (info.file.requiredDependencies.isNotEmpty()) {
                                Text("Dependencias requeridas:")
                                info.file.requiredDependencies.forEach { Text("• ${it.name}") }
                            } else Text("Sin dependencias requeridas.")
                            if (info.missingDependencies.isNotEmpty()) {
                                Notice("Faltan dependencias requeridas. Instálalas y actívalas primero; no se instalarán automáticamente.", error = true)
                            }
                            Button(
                                enabled = !busy && !info.installed && info.missingDependencies.isEmpty(),
                                onClick = { confirmInstall = true },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AddonsGold, contentColor = AddonsInk),
                            ) { Text("Revisar e instalar") }
                            if (busy && progress.isNotBlank()) Notice(progress)
                        }
                    }
                }
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = AddonsGold, modifier = Modifier.height(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(progress.ifBlank { "Procesando…" }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    uninstallTarget?.let { addon ->
        AlertDialog(
            onDismissRequest = { if (!busy) uninstallTarget = null },
            title = { Text("Desinstalar ${addon.title}?") },
            text = { Text("Se quitarán sus carpetas de addons. Los datos de WTF y SavedVariables se conservarán.") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    uninstallTarget = null
                    runOperation { service.uninstall(addon); inventory = service.inventory() }
                }) { Text("Desinstalar") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { uninstallTarget = null }) { Text("Cancelar") } },
        )
    }
    if (confirmInstall) {
        val info = packageInfo
        AlertDialog(
            onDismissRequest = { if (!busy) confirmInstall = false },
            title = { Text("Confirmar instalación") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Proyecto: ${info?.project?.name.orEmpty()}")
                    Text("Archivo: ${info?.file?.fileName.orEmpty()}")
                    Text("Destino: ${info?.destination.orEmpty()}")
                    Text("Dependencias: ${info?.file?.requiredDependencies?.joinToString { it.name } ?: "ninguna"}")
                    Text("No se instalarán dependencias automáticamente ni se sobrescribirán addons existentes.")
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && info?.installed == false && info.missingDependencies.isEmpty(), onClick = {
                    confirmInstall = false
                    info?.let { selected ->
                        runOperation {
                            service.install(selected) { phase -> scope.launch { progress = phase } }
                            packageInfo = null
                            inventory = service.inventory()
                            tab = 0
                        }
                    }
                }) { Text("Instalar") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmInstall = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun AddonRow(
    addon: ForeverAddons.Addon,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onUninstall: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF2B2923), RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(addon.title, color = AddonsGold, fontWeight = FontWeight.SemiBold)
        Text("${addon.version ?: "Versión desconocida"} · ${addon.folders.joinToString()}", style = MaterialTheme.typography.bodySmall)
        if (addon.manageable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (addon.enabled) "Activado" else "Desactivado", modifier = Modifier.weight(1f))
                Switch(checked = addon.enabled, onCheckedChange = onToggle, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp))
                TextButton(enabled = enabled, onClick = onUninstall, modifier = Modifier.heightIn(min = 48.dp)) { Text("Desinstalar") }
            }
        } else {
            Text("Carpeta manual: solo lectura", color = AddonsGold, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProjectRow(
    project: ForeverAddonsApi.Project,
    enabled: Boolean,
    onOpen: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF2B2923), RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(project.name, color = AddonsGold, fontWeight = FontWeight.SemiBold)
        if (project.authors.isNotEmpty()) Text("Por ${project.authors.joinToString()}", style = MaterialTheme.typography.bodySmall)
        if (project.summary.isNotBlank()) Text(project.summary, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = enabled && isCurseForgeAddonUrl(project.websiteUrl),
                onClick = { onOpenLink(project.websiteUrl) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("CurseForge") }
            Spacer(Modifier.weight(1f))
            TextButton(enabled = enabled, onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text("Ver archivo") }
        }
    }
}

private fun isCurseForgeAddonUrl(value: String): Boolean = try {
    val uri = URI(value)
    uri.scheme == "https" && uri.host == "www.curseforge.com" && uri.port == -1 &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        Regex("^/wow/addons/[a-zA-Z0-9][a-zA-Z0-9-]*$").matches(uri.path)
} catch (_: Exception) { false }

@Composable
private fun Notice(text: String, error: Boolean = false) {
    Text(text, color = if (error) MaterialTheme.colorScheme.error else Color(0xFFE8DFC9), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Loading(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(color = AddonsGold, modifier = Modifier.height(22.dp))
        Text(text)
    }
}
