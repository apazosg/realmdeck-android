package app.gamenative

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** UI-facing orchestration. Store/API and all client operations stay behind the shared gates. */
internal class ForeverAddonsService(context: Context) {
    data class Inventory(val addons: List<ForeverAddons.Addon>, val destination: String, val recoveryRequired: Boolean)

    private val appContext = context.applicationContext
    private val storage = ForeverFunctionalClientStorage.service(appContext)
    private val runtimeGate = ForeverClientRuntimeGate.forContext(appContext)

    suspend fun inventory(): Inventory = guarded { _, store, destination ->
        val pending = store.hasPendingTransaction()
        Inventory(if (pending) emptyList() else store.list(), destination, pending)
    }

    suspend fun setEnabled(addon: ForeverAddons.Addon, enabled: Boolean) = guarded { target, store, _ ->
        storage.revalidate(target)
        store.setEnabled(addon.id, enabled)
    }

    suspend fun uninstall(addon: ForeverAddons.Addon) = guarded { target, store, _ ->
        storage.revalidate(target)
        store.uninstall(addon.id)
    }

    suspend fun recover(): Inventory = guarded { target, store, destination ->
        storage.revalidate(target)
        store.recover()
        Inventory(store.list(), destination, false)
    }

    suspend fun search(query: String, index: Int): ForeverAddonsApi.Search = withContext(Dispatchers.IO) {
        ForeverAddonsApi.search(query, index)
    }

    suspend fun packageFor(project: ForeverAddonsApi.Project): PackageInfo = guarded { target, store, destination ->
        check(!store.hasPendingTransaction()) { "addons_recovery_required" }
        val metadata = ForeverAddonsApi.file(project.id)
        val current = store.list()
        val missing = metadata.requiredDependencies.filter { dependency ->
            current.none { it.projectId == dependency.projectId && it.enabled }
        }
        PackageInfo(project, metadata, destination, storage.operationStorageId(target), missing,
            current.any { it.projectId == metadata.projectId })
    }

    suspend fun install(packageInfo: PackageInfo, onProgress: (String) -> Unit): Unit = guarded { target, store, _ ->
        storage.revalidate(target)
        check(storage.operationStorageId(target) == packageInfo.targetStorageId) { "destination-changed" }
        val latest = ForeverAddonsApi.file(packageInfo.project.id)
        check(latest.fileId == packageInfo.file.fileId) { "file-changed" }
        check(store.list().none { it.projectId == latest.projectId }) { "already-installed" }
        check(latest.requiredDependencies.all { dependency ->
            store.list().any { it.projectId == dependency.projectId && it.enabled }
        }) { "dependency-missing" }

        val cache = File(appContext.cacheDir, "forever-addons").apply { mkdirs() }
        require(cache.isDirectory) { "cache-unavailable" }
        var zip: File? = null
        try {
            val downloaded = ForeverAddonsApi.download(cache, latest) { received, total ->
                onProgress("Descargando: ${formatBytes(received)} de ${formatBytes(total)}")
            }
            zip = downloaded
            storage.revalidate(target)
            store.install(downloaded, ForeverAddons.PackageMetadata(
                projectId = latest.projectId,
                fileId = latest.fileId,
                title = packageInfo.project.name,
                version = latest.name,
                requiredProjectIds = latest.requiredDependencies.map { it.projectId },
            ), onProgress)
        } finally {
            zip?.delete()
        }
    }

    data class PackageInfo(
        val project: ForeverAddonsApi.Project,
        val file: ForeverAddonsApi.Package,
        val destination: String,
        internal val targetStorageId: String,
        val missingDependencies: List<ForeverAddonsApi.Dependency>,
        val installed: Boolean,
    )

    fun message(failure: Throwable): String = when {
        failure is ForeverAddonsApi.ApiException -> ForeverAddonsApi.message(failure)
        failure is ForeverAddons.AddonException -> ForeverAddons.message(failure)
        failure.message == "dependency-missing" -> "Primero instala y activa las dependencias requeridas."
        failure.message == "already-installed" -> "Este addon ya está instalado. No se sobrescribirá; puedes administrarlo en Instalados."
        failure.message == "file-changed" -> "El archivo disponible cambió. Vuelve a consultar antes de instalar."
        failure.message == "destination-changed" -> "El cliente seleccionado cambió. Consulta de nuevo el archivo y confirma el destino actual."
        failure.message == "cache-unavailable" -> "No hay espacio temporal disponible para preparar la descarga."
        failure.message == "addons_recovery_required" -> "Hay una operación de addons pendiente. Usa Recuperar para terminarla de forma segura."
        else -> "No se pudo validar el cliente o completar la operación. Comprueba el destino y vuelve a intentarlo."
    }

    private suspend fun <T> guarded(
        operation: (ForeverFunctionalClientStorage.ResolvedTarget, ForeverAddons.Store, String) -> T,
    ): T = withContext(NonCancellable + Dispatchers.IO) {
        val ioLease = storage.acquireIoLease() ?: error("client-busy")
        try {
            val target = storage.resolveForPrepare()
            check(PluviaApp.xEnvironment == null) { "client-busy" }
            val blocker = runtimeGate.blockingReason()
            check(blocker == null || blocker == "addons_recovery_required") { blocker ?: "client-busy" }
            val store = ForeverAddons.Store(target.root) { storage.revalidate(target) }
            val lease = runtimeGate.operations.acquireAddons(storage.operationStorageId(target)) {
                try {
                    storage.revalidate(target)
                    store.hasPendingTransaction()
                } catch (_: Throwable) { true }
            }
            try {
                val label = storage.snapshot().selected?.label?.take(100).orEmpty().ifBlank { "Cliente seleccionado" }
                operation(target, store, label)
            } finally { lease.close() }
        } finally { ioLease.close() }
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = listOf("KiB", "MiB", "GiB")
        var size = value.toDouble()
        for (unit in units) {
            size /= 1024.0
            if (size < 1024.0 || unit == units.last()) return "%.1f %s".format(java.util.Locale.ROOT, size, unit)
        }
        return "$value B"
    }
}
