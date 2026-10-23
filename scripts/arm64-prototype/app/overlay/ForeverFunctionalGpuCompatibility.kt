package app.gamenative

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * APK-visible compatibility policy for the fixed functional route.
 *
 * This is deliberately a policy/data card, not a launch gate.  A matching row
 * means that the exact row has historical render evidence only; it does not
 * imply gameplay, current-device success, or that a backend was loaded.
 */
object ForeverFunctionalGpuCompatibility {
    const val MATRIX_VERSION = "gpu-compatibility-matrix-v1"
    const val CLIENT_BUILD = "1.60.1.70009"
    const val PANVK_SHA256 = "5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9"
    const val PROTON_PROFILE = "proton-11.0-90624-arm64ec"
    const val DXVK_PROFILE = "2.4.1-wow-aarch64-test"
    const val PLANNED_RENDERER = "D3D11 / Vulkan"
    const val PLANNED_BACKEND = "PanVK + DXVK"
    const val MATRIX_PROFILE =
        "panvk=$PANVK_SHA256;proton=$PROTON_PROFILE;dxvk=$DXVK_PROFILE;renderer=$PLANNED_RENDERER"
    const val HISTORICAL_RECORD = "xaga/Mali-G610 MC6/API 34/kernel 5.10.209"

    data class SelectedConfiguration(
        val selectedBackend: String,
        val clientBuild: String,
        val runtimeProfile: String,
    )

    fun fixedSelectedConfiguration() = SelectedConfiguration(
        selectedBackend = PLANNED_BACKEND,
        clientBuild = CLIENT_BUILD,
        runtimeProfile = MATRIX_PROFILE,
    )

    fun decodeVulkanVersion(reportedVersion: Int?): String = reportedVersion?.let {
        val major = it ushr 22
        val minor = (it ushr 12) and 0x3ff
        val patch = it and 0xfff
        "$major.$minor.$patch (raw=$it)"
    } ?: "sin medir"

    enum class Classification {
        KNOWN_HISTORICAL_RENDER,
        UNKNOWN,
    }

    data class ClassificationResult(
        val classification: Classification,
        val reason: String,
        val matrixVersion: String = MATRIX_VERSION,
    )

    /** All fields are explicit so an incomplete observation cannot become a match. */
    data class MatrixInput(
        val hostGlRenderer: String?,
        val deviceCode: String?,
        val apiLevel: Int?,
        val kernelRelease: String?,
        val selectedBackend: String?,
        val clientBuild: String?,
        val runtimeProfile: String?,
        val readError: String? = null,
    )

    fun classify(input: MatrixInput): ClassificationResult {
        if (!input.readError.isNullOrBlank()) {
            return unknown("live GPU read failed: ${input.readError}")
        }
        if (input.hostGlRenderer.isNullOrBlank()) return unknown("host GL renderer is missing")
        if (input.deviceCode.isNullOrBlank()) return unknown("device code is missing")
        if (input.apiLevel == null) return unknown("Android API level is missing")
        if (input.kernelRelease.isNullOrBlank()) return unknown("kernel release is missing")
        if (input.selectedBackend.isNullOrBlank()) return unknown("selected PanVK/DXVK backend is missing")
        if (input.clientBuild.isNullOrBlank()) return unknown("client build is missing")
        if (input.runtimeProfile.isNullOrBlank()) return unknown("runtime profile is missing")

        val mismatches = mutableListOf<String>().apply {
            if (input.hostGlRenderer != "Mali-G610 MC6") add("host GL renderer differs")
            if (!input.deviceCode.equals("xaga", ignoreCase = true)) add("device code differs")
            if (input.apiLevel != 34) add("Android API differs")
            if (input.kernelRelease?.matches(Regex("^5\\.10\\.209(?:$|[-+_.]).*")) != true) add("kernel differs")
            if (input.selectedBackend != PLANNED_BACKEND) add("selected backend differs")
            if (input.clientBuild != CLIENT_BUILD) add("client build differs")
            if (input.runtimeProfile != MATRIX_PROFILE) add("runtime profile differs")
        }
        return if (mismatches.isEmpty()) {
            ClassificationResult(Classification.KNOWN_HISTORICAL_RENDER, "exact historical render row; gameplay not verified")
        } else {
            unknown(mismatches.joinToString())
        }
    }

    private fun unknown(reason: String) = ClassificationResult(Classification.UNKNOWN, reason)
}

data class ForeverFunctionalGpuIdentitySnapshot(
    val capturedAtEpochMs: Long,
    val source: String,
    val deviceCode: String?,
    val model: String?,
    val apiLevel: Int?,
    val kernelRelease: String?,
    val hostGlVendor: String?,
    val hostGlRenderer: String?,
    val hostGlVersion: String?,
    val vulkanHardwareLevelDeclared: Boolean?,
    val vulkanHardwareVersionFeatureDeclared: Boolean?,
    val vulkanHardwareVersionReported: Int?,
    /** The EGL reader never proves the effective Wine backend. */
    val liveBackendVerified: Boolean = false,
    val error: String? = null,
) {
    fun matrixInput(selectedConfiguration: ForeverFunctionalGpuCompatibility.SelectedConfiguration): ForeverFunctionalGpuCompatibility.MatrixInput =
        ForeverFunctionalGpuCompatibility.MatrixInput(
            hostGlRenderer = hostGlRenderer,
            deviceCode = deviceCode,
            apiLevel = apiLevel,
            kernelRelease = kernelRelease,
            selectedBackend = selectedConfiguration.selectedBackend,
            clientBuild = selectedConfiguration.clientBuild,
            runtimeProfile = selectedConfiguration.runtimeProfile,
            readError = error,
        )

    companion object {
        internal fun unknown(error: String, now: Long = System.currentTimeMillis()) =
            ForeverFunctionalGpuIdentitySnapshot(
                capturedAtEpochMs = now,
                source = "EGL14 pbuffer + PackageManager + Android build (incomplete)",
                deviceCode = null,
                model = null,
                apiLevel = null,
                kernelRelease = null,
                hostGlVendor = null,
                hostGlRenderer = null,
                hostGlVersion = null,
                vulkanHardwareLevelDeclared = null,
                vulkanHardwareVersionFeatureDeclared = null,
                vulkanHardwareVersionReported = null,
                error = error,
            )
    }
}

data class ForeverFunctionalGpuReadResult(
    val snapshot: ForeverFunctionalGpuIdentitySnapshot,
    val timedOut: Boolean = false,
)

/** Read-only prepared values supplied by the functional route after preparation. */
data class ForeverFunctionalGpuPreparedConfiguration(
    val protonProfile: String,
    val dxvkProfile: String,
    val renderer: String,
    val panvkSha256: String = ForeverFunctionalGpuCompatibility.PANVK_SHA256,
    val source: String = "prepared container (read-only)",
) {
    fun selectedConfiguration() = ForeverFunctionalGpuCompatibility.SelectedConfiguration(
        selectedBackend = ForeverFunctionalGpuCompatibility.PLANNED_BACKEND,
        clientBuild = ForeverFunctionalGpuCompatibility.CLIENT_BUILD,
        runtimeProfile = "panvk=$panvkSha256;proton=${canonicalProton(protonProfile)};dxvk=$dxvkProfile;renderer=${canonicalRenderer(renderer)}",
    )

    private fun canonicalProton(value: String): String = when {
        value == ForeverFunctionalGpuCompatibility.PROTON_PROFILE -> value
        value.equals("Proton-${ForeverFunctionalGpuCompatibility.PROTON_PROFILE}-1", ignoreCase = true) ->
            ForeverFunctionalGpuCompatibility.PROTON_PROFILE
        else -> value
    }

    private fun canonicalRenderer(value: String): String = if (value.equals("vulkan", ignoreCase = true)) {
        ForeverFunctionalGpuCompatibility.PLANNED_RENDERER
    } else value

    companion object {
        fun fromPrepared(prepared: ForeverFunctionalPoc.Prepared): ForeverFunctionalGpuPreparedConfiguration {
            val container = prepared.container
            return ForeverFunctionalGpuPreparedConfiguration(
                protonProfile = container.getWineVersion(),
                dxvkProfile = container.getDXWrapper().removePrefix("dxvk-"),
                renderer = container.getDisplayRenderer(),
            )
        }
    }
}

/**
 * A single-session EGL reader.  The process-wide lease remains held after a
 * timeout until the actual native EGL worker returns.  Closing a session never
 * interrupts or kills that worker and never permits another stuck worker.
 */
internal class ForeverFunctionalGpuReader(
    private val context: Context,
    private val timeoutMs: Long = 1_500L,
    private val workerFunction: (Context) -> ForeverFunctionalGpuIdentitySnapshot =
        { context -> ForeverFunctionalGpuReader.readSnapshotForReader(context) },
    private val workerReleasedObserver: (() -> Unit)? = null,
) {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var nextRequestId = 0L
    private var activeRequestId: Long? = null
    private var callback: ((ForeverFunctionalGpuReadResult) -> Unit)? = null
    private var timeoutRunnable: Runnable? = null
    private var timedOut = false
    private var closed = false

    fun request(resultCallback: (ForeverFunctionalGpuReadResult) -> Unit): Boolean {
        var requestId = 0L
        var leaseId: Long? = null
        var unavailableReason: String? = null
        var notificationId = 0L
        synchronized(lock) {
            if (closed) return false
            nextRequestId += 1
            notificationId = nextRequestId
            if (activeRequestId != null) {
                unavailableReason = "GPU reader busy; read already in progress"
            } else if (timedOut) {
                unavailableReason = "GPU reader busy; prior EGL worker still active"
            } else {
                leaseId = acquireProcessLease()
                if (leaseId == null) {
                    unavailableReason = "GPU reader busy; prior EGL worker still active"
                } else {
                    requestId = notificationId
                    activeRequestId = requestId
                    callback = resultCallback
                }
            }
        }
        if (unavailableReason != null) {
            postBoundedUnknown(resultCallback, unavailableReason!!, notificationId)
            return false
        }
        checkNotNull(leaseId)
        val worker = Thread({
            val result = try {
                ForeverFunctionalGpuReadResult(workerFunction(context))
            } catch (_: Throwable) {
                ForeverFunctionalGpuReadResult(ForeverFunctionalGpuIdentitySnapshot.unknown("EGL reader failed"))
            } finally {
                releaseProcessLease(requireNotNull(leaseId))
            }
            postIfCurrent(requestId, result)
            // Test observer fires only after release AND enqueue, so tests can
            // deterministically drain main without racing a worker still posting.
            runCatching { workerReleasedObserver?.invoke() }
        }, "forever-gpu-reader")
        worker.isDaemon = true
        try {
            worker.start()
        } catch (_: Throwable) {
            releaseProcessLease(requireNotNull(leaseId))
            postIfCurrent(requestId, ForeverFunctionalGpuReadResult(
                ForeverFunctionalGpuIdentitySnapshot.unknown("EGL reader could not start"),
            ))
            return true
        }
        val timeout = Runnable {
            val deliver = synchronized(lock) {
                if (closed || activeRequestId != requestId) {
                    null
                } else {
                    activeRequestId = null
                    timeoutRunnable = null
                    timedOut = true
                    callback.also { callback = null }
                }
            }
            if (deliver != null && synchronized(lock) { !closed }) {
                deliver.invoke(ForeverFunctionalGpuReadResult(
                    ForeverFunctionalGpuIdentitySnapshot.unknown("GPU reader timed out; compatibility not checked"),
                    timedOut = true,
                ))
            }
        }
        synchronized(lock) {
            if (!closed && activeRequestId == requestId) {
                timeoutRunnable = timeout
                mainHandler.postDelayed(timeout, timeoutMs)
            }
        }
        return true
    }

    private fun postBoundedUnknown(
        resultCallback: (ForeverFunctionalGpuReadResult) -> Unit,
        reason: String,
        notificationId: Long,
    ) {
        mainHandler.post {
            val deliver = synchronized(lock) {
                !closed && activeRequestId == null && nextRequestId == notificationId
            }
            if (deliver) resultCallback(ForeverFunctionalGpuReadResult(
                ForeverFunctionalGpuIdentitySnapshot.unknown(reason),
            ))
        }
    }

    private fun postIfCurrent(requestId: Long, result: ForeverFunctionalGpuReadResult) {
        mainHandler.post {
            var pendingTimeout: Runnable? = null
            val deliver = synchronized(lock) {
                if (closed || activeRequestId != requestId) {
                    // A timed-out worker has now actually returned.  Only now
                    // may this session be retried; the process lease was held
                    // until this point by the worker's finally block.
                    if (!closed && timedOut && activeRequestId == null) timedOut = false
                    null
                } else if (timedOut) {
                    null
                } else {
                    activeRequestId = null
                    pendingTimeout = timeoutRunnable
                    timeoutRunnable = null
                    callback.also { callback = null }
                }
            }
            pendingTimeout?.let(mainHandler::removeCallbacks)
            deliver?.invoke(result)
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            timeoutRunnable?.let(mainHandler::removeCallbacks)
            timeoutRunnable = null
            activeRequestId = null
            callback = null
        }
    }

    companion object {
        private val processLeaseLock = Any()
        private var nextProcessLease = 0L
        private var processLease: Long? = null

        private fun acquireProcessLease(): Long? = synchronized(processLeaseLock) {
            if (processLease != null) return@synchronized null
            nextProcessLease += 1
            processLease = nextProcessLease
            nextProcessLease
        }

        private fun releaseProcessLease(leaseId: Long) {
            synchronized(processLeaseLock) {
                if (processLease == leaseId) processLease = null
            }
        }

        internal fun readSnapshotForReader(context: Context): ForeverFunctionalGpuIdentitySnapshot {
            val now = System.currentTimeMillis()
            val gl = readHostGl()
            val packageManager = context.packageManager
            return ForeverFunctionalGpuIdentitySnapshot(
                capturedAtEpochMs = now,
                source = "EGL14 1x1 pbuffer + PackageManager declared features + Android build",
                deviceCode = Build.DEVICE,
                model = Build.MODEL,
                apiLevel = Build.VERSION.SDK_INT,
                kernelRelease = System.getProperty("os.version"),
                hostGlVendor = gl.vendor,
                hostGlRenderer = gl.renderer,
                hostGlVersion = gl.version,
                vulkanHardwareLevelDeclared = packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL),
                vulkanHardwareVersionFeatureDeclared = packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION),
                vulkanHardwareVersionReported = packageManager.systemAvailableFeatures
                    .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
                    ?.version
                    ?.takeIf { it > 0 },
            )
        }

        private data class GlIdentity(val vendor: String?, val renderer: String?, val version: String?)

        private fun readHostGl(): GlIdentity {
            var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
            var surface: EGLSurface = EGL14.EGL_NO_SURFACE
            var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
            try {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
                val version = IntArray(2)
                check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
                val configAttributes = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE,
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                check(EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                    "eglChooseConfig failed"
                }
                val config = requireNotNull(configs[0])
                EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API)
                eglContext = EGL14.eglCreateContext(
                    display,
                    config,
                    EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                    0,
                )
                check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
                surface = EGL14.eglCreatePbufferSurface(
                    display,
                    config,
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                    0,
                )
                check(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
                check(EGL14.eglMakeCurrent(display, surface, surface, eglContext)) { "eglMakeCurrent failed" }
                return GlIdentity(
                    GLES20.glGetString(GLES20.GL_VENDOR),
                    GLES20.glGetString(GLES20.GL_RENDERER),
                    GLES20.glGetString(GLES20.GL_VERSION),
                )
            } finally {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                    EGL14.eglTerminate(display)
                    EGL14.eglReleaseThread()
                }
            }
        }
    }
}

/** Session-only state holder; no GPU identity is persisted. */
class ForeverFunctionalGpuCompatibilitySession(context: Context) {
    private val reader = ForeverFunctionalGpuReader(context.applicationContext)

    fun request(callback: (ForeverFunctionalGpuReadResult) -> Unit): Boolean = reader.request(callback)

    fun close() = reader.close()
}

@Composable
fun ForeverFunctionalGpuCompatibilityCard(
    modifier: Modifier = Modifier,
    preparedConfiguration: ForeverFunctionalGpuPreparedConfiguration? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val session = remember(context) { ForeverFunctionalGpuCompatibilitySession(context) }
    var result by remember(session) { mutableStateOf<ForeverFunctionalGpuReadResult?>(null) }
    var reading by remember(session) { mutableStateOf(false) }
    var details by remember(session) { mutableStateOf(false) }

    DisposableEffect(session) {
        reading = session.request {
            result = it
            reading = false
        }
        onDispose { session.close() }
    }

    val snapshot = result?.snapshot
    val selectedConfiguration = preparedConfiguration?.selectedConfiguration()
        ?: ForeverFunctionalGpuCompatibility.fixedSelectedConfiguration()
    val classification = snapshot?.let { ForeverFunctionalGpuCompatibility.classify(it.matrixInput(selectedConfiguration)) }
    val functionalCode = BuildConfig.VERSION_NAME
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                when {
                    reading -> "Comprobando…"
                    classification?.classification == ForeverFunctionalGpuCompatibility.Classification.KNOWN_HISTORICAL_RENDER ->
                        "${snapshot?.hostGlRenderer ?: "GPU desconocida"} · arranque/render probado (histórico)"
                    else -> "${snapshot?.hostGlRenderer ?: "GPU desconocida"} · Compatibilidad no comprobada"
                },
                modifier = Modifier.padding(top = 14.dp),
                color = if (classification?.classification == ForeverFunctionalGpuCompatibility.Classification.KNOWN_HISTORICAL_RENDER) {
                    MaterialTheme.colorScheme.secondary
                } else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(if (preparedConfiguration == null) "Configuración prevista" else "Preparada; carga sin comprobar",
                modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall)
            Text(
                if (preparedConfiguration == null || preparedConfiguration.selectedConfiguration() == ForeverFunctionalGpuCompatibility.fixedSelectedConfiguration()) {
                    "PanVK · D3D11 / Vulkan · DXVK 2.4.1 · Wine 11"
                } else {
                    "Perfil preparado distinto · consulta Detalles"
                },
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    enabled = !reading,
                    onClick = {
                        reading = session.request {
                            result = it
                            reading = false
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
                ) { Text("Recomprobar") }
                TextButton(
                    onClick = { details = true },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
                ) { Text("Detalles") }
            }
        }
    }
    if (details) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { details = false },
            title = { Text("Detalles de compatibilidad") },
            text = {
                Column(modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    Text("Matriz: ${ForeverFunctionalGpuCompatibility.MATRIX_VERSION}")
                    Text("Informativo: no bloquea el lanzamiento ni selecciona controladores.")
                    Text("Código APK: $functionalCode", modifier = Modifier.padding(top = 6.dp))
                    Text("Evidencia: ${ForeverFunctionalGpuCompatibility.HISTORICAL_RECORD}; render probado, gameplay no probado", modifier = Modifier.padding(top = 6.dp))
                    Text("Host GL vendor: ${snapshot?.hostGlVendor ?: "desconocido"}", modifier = Modifier.padding(top = 6.dp))
                    Text("Host GL renderer: ${snapshot?.hostGlRenderer ?: "desconocido"}", modifier = Modifier.padding(top = 6.dp))
                    Text("Host GL version: ${snapshot?.hostGlVersion ?: "desconocida"}", modifier = Modifier.padding(top = 6.dp))
                    Text("Dispositivo: ${snapshot?.deviceCode ?: "desconocido"} · modelo: ${snapshot?.model ?: "desconocido"}", modifier = Modifier.padding(top = 6.dp))
                    Text("API: ${snapshot?.apiLevel ?: "desconocida"} · kernel: ${snapshot?.kernelRelease ?: "desconocido"}", modifier = Modifier.padding(top = 6.dp))
                    Text(
                        "Vulkan declarado: nivel=${declared(snapshot?.vulkanHardwareLevelDeclared)} · feature versión=${declared(snapshot?.vulkanHardwareVersionFeatureDeclared)} · versión reportada=${ForeverFunctionalGpuCompatibility.decodeVulkanVersion(snapshot?.vulkanHardwareVersionReported)} (no es una prueba física completa)",
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text("Backend efectivo: ${if (snapshot?.liveBackendVerified == true) "verificado" else "no verificado"}", modifier = Modifier.padding(top = 6.dp))
                    Text("Configuración prevista: PanVK SHA-256 ${ForeverFunctionalGpuCompatibility.PANVK_SHA256}", modifier = Modifier.padding(top = 6.dp))
                    Text("Wine: ${ForeverFunctionalGpuCompatibility.PROTON_PROFILE}", modifier = Modifier.padding(top = 6.dp))
                    Text("DXVK: ${ForeverFunctionalGpuCompatibility.DXVK_PROFILE} · renderer: ${ForeverFunctionalGpuCompatibility.PLANNED_RENDERER}", modifier = Modifier.padding(top = 6.dp))
                    Text("Cliente/ruta/args: ${ForeverFunctionalGpuCompatibility.CLIENT_BUILD} · ${ForeverFunctionalPoc.ROUTE} · ${ForeverFunctionalPoc.EXEC_ARGS_TEMPLATE}", modifier = Modifier.padding(top = 6.dp))
                    preparedConfiguration?.let { prepared ->
                        Text("Preparada (solo lectura): ${prepared.source}", modifier = Modifier.padding(top = 6.dp))
                        Text("PanVK SHA-256: ${prepared.panvkSha256}", modifier = Modifier.padding(top = 6.dp))
                        Text("Wine/DXVK/renderer preparados: ${prepared.protonProfile} · ${prepared.dxvkProfile} · ${prepared.renderer}", modifier = Modifier.padding(top = 6.dp))
                    }
                    Text("Fuente: ${snapshot?.source ?: "sin lectura"}", modifier = Modifier.padding(top = 6.dp))
                    Text("Captura: ${snapshot?.capturedAtEpochMs ?: "—"}", modifier = Modifier.padding(top = 6.dp))
                    Text("Resultado: ${classification?.reason ?: "sin resultado"}", modifier = Modifier.padding(top = 6.dp))
                    snapshot?.error?.let { Text("Error: ${safeErrorCode(it)}", modifier = Modifier.padding(top = 6.dp)) }
                }
            },
            confirmButton = {
                androidx.compose.foundation.layout.Row {
                    TextButton(
                        enabled = snapshot != null,
                        onClick = { shareGpuDiagnostics(context, snapshot) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
                    ) { Text("Compartir") }
                    TextButton(
                        onClick = { details = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
                    ) { Text("Cerrar") }
                }
            },
        )
    }
}

private fun declared(value: Boolean?): String = when (value) {
    true -> "sí"
    false -> "no"
    null -> "desconocido"
}

private fun safeErrorCode(error: String): String = when {
    error.contains("timed out", ignoreCase = true) -> "timeout"
    error.contains("busy", ignoreCase = true) -> "reader_busy"
    error.contains("could not start", ignoreCase = true) -> "worker_start_failed"
    error.contains("EGL reader failed", ignoreCase = true) -> "egl_failure"
    else -> "unknown_reader_error"
}

private fun shareGpuDiagnostics(context: Context, snapshot: ForeverFunctionalGpuIdentitySnapshot?) {
    if (snapshot == null) return
    val text = buildString {
        appendLine("WoW Forever GPU compatibility")
        appendLine("matrix=${ForeverFunctionalGpuCompatibility.MATRIX_VERSION}")
        appendLine("device=${snapshot.deviceCode ?: "unknown"}")
        appendLine("hostGl=${snapshot.hostGlRenderer ?: "unknown"}")
        appendLine("api=${snapshot.apiLevel ?: "unknown"}")
        appendLine("kernel=${snapshot.kernelRelease ?: "unknown"}")
        appendLine("androidVulkanFeatureLevel=${declared(snapshot.vulkanHardwareLevelDeclared)}")
        appendLine("androidVulkanFeatureVersion=${declared(snapshot.vulkanHardwareVersionFeatureDeclared)}")
        appendLine("androidVulkanReportedVersion=${ForeverFunctionalGpuCompatibility.decodeVulkanVersion(snapshot.vulkanHardwareVersionReported)}")
        appendLine("liveBackendVerified=${snapshot.liveBackendVerified}")
        appendLine("error=${snapshot.error?.let(::safeErrorCode) ?: "none"}")
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Compartir diagnóstico gráfico")) }
}
