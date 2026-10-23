package app.gamenative

/**
 * Contrato MVP1 de diagnóstico remoto (docs/diagnostics-api.md).
 *
 * El endpoint va compilado; ninguna configuración manual del usuario.
 * Provisional hasta que el mantenedor confirme el despliegue.
 */
object ForeverRemoteDiagnosticsContract {
    /** Endpoint HTTPS público de ingestión, fijado en compilación. */
    const val ENDPOINT = "https://wowforever-diagnostics.vercel.app"

    const val SCHEMA = 1

    const val COMMENT_MAX_CHARS = 4000

    /** Metadatos/manifiesto de creación. */
    const val METADATA_MAX_BYTES = 256 * 1024

    /** Adjunto individual y suma total por informe. */
    const val ATTACHMENT_MAX_BYTES = 8 * 1024 * 1024
    const val ATTACHMENTS_TOTAL_MAX_BYTES = 24 * 1024 * 1024
    const val ATTACHMENTS_MAX_COUNT = 6

    const val EVENTS_MAX = 200
    const val SOURCES_MAX = 32

    const val INSTALLATION_ID_BYTES = 16
    const val CAPABILITY_BYTES = 32

    const val DRAFTS_RETAINED = 5

    const val CONTENT_TYPE = "text/plain; charset=utf-8"

    /** Nombres de adjunto admitidos por schema1. */
    val ATTACHMENT_NAMES = setOf("diagnostics", "stdout", "stderr", "wine", "wow", "launcher")

    /** Fuentes con motivo explícito cuando faltan o se recortan. */
    val SOURCE_NAMES = setOf("diagnostics", "stdout", "stderr", "wine", "wow", "launcher", "config", "attempt", "timeline")

    /** Estados de fuente del manifiesto. */
    val SOURCE_STATUSES = setOf("ok", "truncated", "missing", "error")

    /**
     * Acciones semánticas del wire, conjunto exacto del backend. Nunca texto
     * de campos, credenciales, inputs del juego, coordenadas ni texto tecleado.
     */
    val EVENT_ACTIONS = setOf(
        "screen",
        "launch",
        "pause",
        "resume",
        "profile-change",
        "resolution-change",
        "gpu-change",
        "cancel",
        "retry",
        "error",
        "exception",
        "wine-start",
        "wine-exit",
        "wow-start",
        "wow-exit",
        "timeout",
        "precondition-failed",
    )

    /** Outcomes exactos del wire. La evidencia técnica va en adjuntos, no aquí. */
    val EVENT_OUTCOMES = setOf("ok", "failed", "cancelled", "unknown", "pending")

    /** Claves técnicas permitidas por sección de config; ausentes = null. */
    val CONFIG_DEVICE_KEYS = setOf(
        "manufacturer", "model", "soc", "gpu", "androidRelease", "androidApi", "kernel", "driver",
    )
    val CONFIG_APP_KEYS = setOf("packageName", "versionName", "versionCode", "buildRevision")
    val CONFIG_CLIENT_KEYS = setOf("build", "architecture", "locale", "status")
    val CONFIG_PROFILE_KEYS = setOf(
        "profileId", "profileRevision", "gpuBackend", "resolution", "wine", "dxvk", "panvk", "graphics",
    )

    /**
     * Blob firmado real (prueba SDK del mantenedor): host EXACTO vercel.com y
     * ruta EXACTA /api/blob/ con query firmada. Nunca URLs vercel.com
     * arbitrarias fuera de esa ruta.
     */
    const val BLOB_HOST = "vercel.com"
    const val BLOB_PATH_PREFIX = "/api/blob/"

    /** Rutas de creación del API. */
    fun createUrl(): String = "$ENDPOINT/api/reports"
    fun completeUrl(reportId: String): String = "$ENDPOINT/api/reports/$reportId/complete"
}
