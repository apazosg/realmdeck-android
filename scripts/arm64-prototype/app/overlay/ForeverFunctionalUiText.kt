package app.gamenative

import java.util.Locale

/**
 * Shared human wording for download progress and user-visible failures.
 *
 * The foreground-service notification, the client-management dialog and the
 * home status panel all format through here so the three surfaces never
 * disagree. Raw exception messages, host paths and credentials never reach
 * the user; every mapping below is a fixed, credential-free sentence.
 * Cancellation is never mapped: callers must let CancellationException
 * propagate instead of passing it here.
 */
object ForeverFunctionalUiText {
    fun clientBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return "%.1f %s".format(Locale.ROOT, value, units[index])
    }

    /** Plain Spanish verdict wording, kept independent of Compose for unit tests. */
    fun pastedClientVerdictText(verdict: PastedClientVerdict): String = when (verdict) {
        PastedClientVerdict.NONE -> "No hay ningún cliente pegado todavía."
        PastedClientVerdict.NOT_CASC -> "Lo que hay pegado no se reconoce como un cliente: falta la carpeta Data o su índice."
        PastedClientVerdict.WRONG_PRODUCT -> "Lo pegado no es el cliente de WoW Classic Beta (producto, región o idioma distintos)."
        PastedClientVerdict.NOT_ARM64 -> "Lo pegado es un cliente x86_64. Esta app necesita el cliente ARM64."
        PastedClientVerdict.TARGET_BUILD -> "Se ha detectado la versión de cliente ARM64 que busca esta app. Cuánto se reutiliza y qué falta por descargar se calcula después, al preparar la actualización."
        PastedClientVerdict.PRIOR_BUILD -> "Es un cliente ARM64 de un build anterior. Se puede reutilizar como fuente; lo que falte por descargar se calcula después, al preparar la actualización."
        PastedClientVerdict.UNKNOWN_BUILD_AS_SOURCE ->
            "Es un cliente ARM64 de una versión no reconocida. Se puede reutilizar su contenido como fuente tras validarlo; lo que falte se descarga después."
    }

    fun isAtomicPhase(phase: ForeverClientUpdateProgress.Phase): Boolean =
        phase == ForeverClientUpdateProgress.Phase.APPLYING ||
            phase == ForeverClientUpdateProgress.Phase.RECOVERING

    /**
     * Truthful one-line operation text. Numbers are shown only when the detail
     * carries valid in-range data; out-of-range counters are invalid (no
     * clamping to 100) and fall back to the phase-appropriate live text.
     * No ETA is ever synthesized.
     */
    fun downloadStatusText(detail: ForeverClientUpdateProgress?, fallback: String): String {
        if (detail == null) return fallback
        if (isAtomicPhase(detail.phase)) return atomicStatusText(detail)
        return if (detail.phase == ForeverClientUpdateProgress.Phase.DOWNLOADING) {
            if (validBytes(detail)) {
                "Descargando ${clientBytes(detail.transferredBytes)} de ${clientBytes(detail.totalTransferBytes)}"
            } else {
                fallback
            }
        } else {
            if (validCounts(detail)) {
                "Comprobando archivos: ${detail.completedObjects} de ${detail.totalObjects}"
            } else {
                fallback
            }
        }
    }

    private fun validBytes(detail: ForeverClientUpdateProgress): Boolean =
        detail.totalTransferBytes > 0 &&
            detail.transferredBytes in 0..detail.totalTransferBytes

    private fun validCounts(detail: ForeverClientUpdateProgress): Boolean =
        detail.totalObjects > 0 && detail.completedObjects in 0..detail.totalObjects

    /**
     * Truthful atomic-phase text. Phase stays APPLYING/RECOVERING (still
     * non-interruptible); only the file counters come from the typed
     * finalization detail. Absent or invalid counters keep the previous
     * fixed atomic sentence, never a clamped percentage.
     */
    private fun atomicStatusText(detail: ForeverClientUpdateProgress): String {
        val finalization = detail.finalization
        return if (finalization != null && finalization.filesTotal > 0 &&
            finalization.filesCompleted in 0..finalization.filesTotal
        ) {
            "Aplicando los archivos… ${finalization.filesCompleted} de ${finalization.filesTotal}"
        } else {
            "Aplicando los archivos…"
        }
    }

    /**
     * 0..1 fraction, or null when no honest in-range denominator exists.
     * Out-of-range counters are invalid, never clamped to a fake 100 %.
     */
    fun downloadFraction(detail: ForeverClientUpdateProgress?): Float? {
        if (detail == null || isAtomicPhase(detail.phase)) return null
        return if (detail.phase == ForeverClientUpdateProgress.Phase.DOWNLOADING) {
            if (!validBytes(detail)) {
                null
            } else {
                (detail.transferredBytes.toDouble() / detail.totalTransferBytes.toDouble()).toFloat()
            }
        } else {
            if (!validCounts(detail)) {
                null
            } else {
                (detail.completedObjects.toDouble() / detail.totalObjects.toDouble()).toFloat()
            }
        }
    }

    /** 0..100 percentage, or null when no honest denominator exists. */
    fun downloadPercent(detail: ForeverClientUpdateProgress?): Int? =
        downloadFraction(detail)?.let { (it * 100).toInt().coerceIn(0, 100) }

    /**
     * Human wording for a user-visible failure. Storage/client codes keep
     * their honest meaning (missing client, busy, unavailable) without
     * leaking raw codes, paths or transport details. Logged diagnostics keep
     * the raw failure; this is only the displayed sentence.
     */
    fun userMessage(failure: Throwable): String {
        val code = (failure as? ForeverFunctionalClientStorage.StorageException)?.code
        if (code != null) {
            return when (code) {
                "storage_paste_requires_removable" ->
                    "Para preparar la carpeta de pegado, elige una unidad USB o SD extraíble. El almacenamiento interno no se puede abrir desde el PC."
                "storage_marker_invalid" ->
                    "El cliente pegado incluye un marcador de almacenamiento de otro dispositivo o dañado. Copia solo Data/ y .build.info; no copies los ficheros ocultos internos de la app."
                "pasted_client_readme_create_failed" ->
                    "No se pudieron escribir las instrucciones en la unidad. No se ha cambiado ningún archivo del cliente."
                "pasted_client_readme_too_large" ->
                    "No se pudieron preparar las instrucciones porque superan el tamaño permitido. No se ha cambiado ningún archivo del cliente."
                "client_not_installed" -> "El cliente aún no está instalado. Usa «Descargar cliente» para obtenerlo."
                "client_incomplete" -> "Al cliente le faltan archivos. Usa «Completa la instalación» para revisarlo."
                "client_executable_invalid" -> "El ejecutable del cliente no es el esperado. Usa «Completa la instalación» para revisarlo."
                "client_admission_unavailable" ->
                    "No se encontró la admisión que vincula este cliente con el destino elegido. No significa que el ejecutable esté dañado: usa «Completa la instalación» para revisarlo."
                "client_update_pending" ->
                    "Hay una operación del cliente sin terminar o no se pudo confirmar que el entorno esté inactivo. Espera a que termine o revisa «Cliente y actualizaciones»."
                "container_registration_mismatch" ->
                    "No se pudo confirmar la identidad del cliente para vincularlo con su contenedor de Wine. Comprueba la unidad seleccionada y vuelve a intentarlo."
                "storage_busy" -> "Espera a que termine la preparación o la partida antes de continuar."
                "storage_unavailable", "storage_mount_unproven", "storage_base_unavailable",
                "storage_selection_changed", "storage_root_changed", "storage_root_replaced",
                "storage_base_replaced", "storage_mount_changed", "storage_not_directory",
                "storage_path_invalid", "storage_path_escape", "storage_identity_unproven",
                "storage_not_writable", "storage_namespace_not_writable", "storage_selection_not_saved",
                "storage_selection_corrupt", "storage_target_create_failed", "storage_data_invalid",
                "storage_wtf_invalid", "storage_executable_invalid", "storage_root_unproven",
                "storage_not_installed", "storage_unselected", "primary_storage_unavailable",
                "storage_corrupt", "client_not_verified", "client_wtf_replaced", "client_executable_replaced",
                "container_duplicate_a_drive", "container_a_drive_unknown",
                "storage_unavailable_after_resume", "guest_reservation_lost" ->
                    storageSelectionMessage(code)
                else -> "No se pudo usar este destino. Comprueba sus permisos y espacio libre; los datos existentes no se han movido."
            }
        }
        val raw = failure.message.orEmpty().lowercase(Locale.ROOT)
        return when {
            "space" in raw || "storage for metadata" in raw -> "No hay espacio suficiente en la unidad elegida o en el teléfono."
            // App-owned guest recovery (US-004 AC-10): a live runtime and an
            // unproven shutdown stay excluded with distinct brief retry texts.
            // These precede the generic runtime/guest branch below.
            "own wine runtime is unknown" in raw || "own_runtime_unknown" in raw ->
                "No se pudo comprobar que WoW esté cerrado. Reintenta."
            "own wine runtime is active" in raw || "own_runtime_active" in raw ->
                "WoW sigue activo. Ciérralo y reintenta."
            // A stale reservation owned by another destination or ticket is
            // never recovered: fail closed and point at the selected storage.
            "does not own the durable" in raw || "does not match the frozen record" in raw ||
                "no unfinished guest operation" in raw || "guest_recovery_ticket_missing" in raw ||
                "guest_recovery_reservation_missing" in raw ->
                "La reserva anterior no coincide con el destino elegido o ya no está vigente. Revisa el almacenamiento y reintenta."
            "runtime" in raw || "guest" in raw || "storage_busy" in raw -> "Cierra WoW antes de continuar."
            "permission" in raw || "access" in raw || "namespace" in raw -> "Android no permite acceder a esta ubicación. Elige otra en Ajustes."
            "http" in raw || "connect" in raw || "network" in raw -> "No se pudo completar la descarga. Comprueba la conexión y reintenta."
            // Planning-gate truthfulness: a pending transaction that must be
            // recovered/reviewed before planning is not a failed recovery.
            // This branch must stay above the recovery-failure branch.
            "before planning" in raw -> "Hay una descarga sin terminar ligada a su catálogo original. Revisa ese contexto antes de continuar."
            "recuperación" in raw || "recover" in raw -> "Falló la recuperación de la descarga anterior. Reintenta; si vuelve a fallar, espera unos minutos."
            "verification" in raw || "blte" in raw || "checksum" in raw -> "Un archivo llegó dañado desde el servidor. Reintenta; si vuelve a fallar, espera unos minutos."
            // Generic fallback makes no data-preservation claim: only the
            // updater-owned paths know whether resume data survived.
            else -> "No se pudo completar la operación. Vuelve a intentarlo."
        }
    }

    private fun storageSelectionMessage(code: String): String = when (code) {
        "guest_reservation_lost" -> "La reserva de la partida se perdió. Vuelve a preparar WoW."
        "storage_busy" -> "Espera a que termine la preparación o la partida antes de continuar."
        "storage_unavailable", "storage_mount_unproven", "storage_base_unavailable", "storage_unselected",
        "primary_storage_unavailable", "storage_selection_changed", "storage_root_changed",
        "storage_root_replaced", "storage_base_replaced", "storage_mount_changed" ->
            "El destino no está disponible o Android no expone un acceso directo compatible. Reconecta la unidad y vuelve a comprobar."
        else -> "No se pudo usar este destino. Comprueba sus permisos y espacio libre; los datos existentes no se han movido."
    }
}
