package app.gamenative

import org.json.JSONObject

/**
 * Configuración solicitada CONGELADA desde evidencia durable, nunca desde
 * preferencias vivas: el informe puede referirse a un RUN anterior o a una
 * descarga 70450 en curso mientras las prefs ya cambiaron.
 *
 * Fuentes, en orden: evento `configuration_ready` del journal del RUN
 * (perfil gestionado), evento `prepared` del log del PoC funcional y la
 * intención persistida al crear el intento. Lo observado solo con evidencia
 * real; ausente = null, nunca inventado.
 */
object ForeverRemoteDiagnosticsFrozenConfig {
    /**
     * Extrae lo solicitado congelado del journal (último configuration_ready)
     * y del log PoC (último prepared). Claves del wire o null.
     */
    fun extractRequested(diagnosticsJournal: String, pocLog: String): Map<String, String?> {
        var wine: String? = null
        var dxvk: String? = null
        var graphics: String? = null
        var profileId: String? = null
        for (line in diagnosticsJournal.lineSequence()) {
            val event = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (event.optString("event") != "configuration_ready") continue
            wine = event.optString("wineEntry").takeUnless { it.isNullOrEmpty() }
            dxvk = event.optString("dxvkEntry").takeUnless { it.isNullOrEmpty() }
            graphics = event.optString("graphicsSelected").takeUnless { it.isNullOrEmpty() }
        }
        for (line in pocLog.lineSequence()) {
            val event = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (event.optString("event") != "prepared" || event.optString("route") != "functional-wow-aa64") continue
            profileId = event.optString("profileId").takeUnless { it.isNullOrEmpty() }
            if (wine == null) wine = event.optString("wine").takeUnless { it.isNullOrEmpty() }
            if (dxvk == null) dxvk = event.optString("dxvk").takeUnless { it.isNullOrEmpty() }
        }
        return mapOf(
            "profileId" to profileId,
            "profileRevision" to null,
            "gpuBackend" to null,
            "resolution" to null,
            "wine" to wine,
            "dxvk" to dxvk,
            "panvk" to null,
            "graphics" to graphics,
        )
    }

    /**
     * Combina la intención congelada (perfil/resolución en el momento del
     * intento) con el journal: el journal gana para componentes observados
     * en la preparación; la intención gana para lo elegido por el usuario.
     */
    fun combine(intentRequested: Map<String, String?>, journalRequested: Map<String, String?>): Map<String, String?> {
        val keys = setOf("profileId", "profileRevision", "gpuBackend", "resolution", "wine", "dxvk", "panvk", "graphics")
        return keys.associateWith { key ->
            when (key) {
                "profileId", "resolution" -> intentRequested[key] ?: journalRequested[key]
                else -> journalRequested[key] ?: intentRequested[key]
            }
        }
    }
}
