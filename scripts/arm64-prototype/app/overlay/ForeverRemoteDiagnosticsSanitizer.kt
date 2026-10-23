package app.gamenative

import java.util.Locale

/**
 * Saneado local antes de red: comentario, metadatos y CADA adjunto.
 *
 * Elimina credenciales reales de autologin (sin copiarlas a logs), patrones de
 * secretos, datos de cuenta, IP, seriales, rutas privadas y escapes de
 * terminal. Solo deja pasar acciones de la allowlist. Nada aquí registra.
 */
object ForeverRemoteDiagnosticsSanitizer {
    data class SanitizeResult(val text: String, val redactions: Int, val truncated: Boolean)

    private val CSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
    private val OSC = Regex("\u001B\\][^\u0007]*(\u0007|\u001B\\\\)")
    private val C0_CONTROLS = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]")

    private val SECRET_PATTERNS = listOf(
        // Clave/valor de secretos conocidos.
        Regex("(?i)\\b(password|passwd|pwd|contrase[ñn]a)\\s*[:=]\\s*[^\\s,}]+"),
        Regex("(?i)\\b(token|id_token|access_token|refresh_token|secret|api[_-]?key|auth[_-]?token)\\s*[:=]\\s*[^\\s,}]+"),
        Regex("(?i)\\bbearer\\s+[A-Za-z0-9_.~-]+"),
        Regex("(?i)\\b(account|username|user[_-]?name|login|email|correo)\\s*[:=]\\s*[^\\s,}]+"),
        // Correo genérico.
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        // IPv4 / IPv6.
        Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"),
        Regex("(?i)\\b(?:[0-9a-f]{0,4}:){2,}[0-9a-f:.]+\\b"),
        // Seriales y cuentas con etiqueta.
        Regex("(?i)\\b(serial[_-]?number|serial|imei|imsi|mac[_-]?address)\\s*[:=]\\s*[^\\s,}]+"),
        // Rutas privadas del dispositivo o del hogar.
        Regex("/data/(data|user|media)/[^\\s\"']*"),
        Regex("/storage/emulated/0/Android/(data|obb)/[^\\s\"']*"),
        Regex("(?i)/home/[^\\s\"']+"),
        Regex("(?i)C:\\\\Users\\\\[^\\\\\\s\"']+"),
        Regex("content://[^\\s\"']+"),
    )

    private const val REDACTED = "[redacted]"

    /**
     * Sanea texto con cota. Los secretos literales se buscan sin convertirlos
     * a String inmutable (barrido sobre CharArray) y nunca se registran.
     */
    fun sanitizeText(raw: String, secrets: List<CharArray> = emptyList(), maxBytes: Int = Int.MAX_VALUE): SanitizeResult {
        var text = raw
        var redactions = 0
        for (secret in secrets) {
            if (secret.isEmpty()) continue
            val (replaced, count) = redactLiteral(text, secret)
            text = replaced
            redactions += count
        }
        for (pattern in SECRET_PATTERNS) {
            val matches = pattern.findAll(text).count()
            if (matches > 0) {
                text = pattern.replace(text, REDACTED)
                redactions += matches
            }
        }
        text = CSI.replace(text, "")
        text = OSC.replace(text, "")
        // C0 salvo tabulación/salto de línea: fuera sin fugas.
        text = C0_CONTROLS.replace(text, "")
        var truncated = false
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) {
            text = truncateUtf8(bytes, maxBytes)
            truncated = true
        }
        return SanitizeResult(text, redactions, truncated)
    }

    fun sanitizeComment(raw: String, secrets: List<CharArray> = emptyList()): SanitizeResult {
        val capped = if (raw.length > ForeverRemoteDiagnosticsContract.COMMENT_MAX_CHARS) {
            raw.substring(0, ForeverRemoteDiagnosticsContract.COMMENT_MAX_CHARS)
        } else {
            raw
        }
        val result = sanitizeText(capped, secrets)
        return result.copy(truncated = result.truncated || raw.length > ForeverRemoteDiagnosticsContract.COMMENT_MAX_CHARS)
    }

    /** Solo acciones semánticas de la allowlist; el resto se descarta. */
    fun allowedAction(action: String): Boolean = action in ForeverRemoteDiagnosticsContract.EVENT_ACTIONS

    fun sanitizeAction(action: String): String? = action.takeIf { allowedAction(it) }

    /** Sanea todos los valores de metadatos (config) antes de red; claves intactas. */
    fun sanitizeMapValues(values: Map<String, String?>, secrets: List<CharArray> = emptyList()): Map<String, String?> {
        val result = LinkedHashMap<String, String?>(values.size)
        for ((key, value) in values) {
            result[key] = value?.let { sanitizeText(it, secrets, maxBytes = 1024).text }
        }
        return result
    }

    /** Disponibilidad de secretos de autologin para el pegamento Android. */
    enum class SecretsAvailability { AVAILABLE, NONE, STORE_UNAVAILABLE }

    /**
     * Copias propias de los secretos para sanear fuera del callback. La clase
     * real anula sus temporales al retornar useValues, así que quien llama
     * DEBE copiar dentro y anular las copias en finally.
     */
    fun ownedSecretCopies(credentials: ForeverFunctionalAutologinCredentials): List<CharArray> =
        credentials.useValues { user, pass -> listOf(user.copyOf(), pass.copyOf()) }

    /**
     * Falla cerrado si el almacén está roto: nunca enviar credenciales
     * potencialmente sin sanear por no haber podido leerlas.
     */
    fun requireSecretsAvailable(availability: SecretsAvailability) {
        require(availability != SecretsAvailability.STORE_UNAVAILABLE) {
            "credential store unavailable; refusing to send potentially unsanitized report"
        }
    }

    private fun redactLiteral(text: String, needle: CharArray): Pair<String, Int> {
        if (needle.isEmpty() || needle.size > text.length) return text to 0
        val out = StringBuilder(text.length)
        var count = 0
        var i = 0
        while (i <= text.length - needle.size) {
            var match = true
            for (j in needle.indices) {
                if (text[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                out.append(REDACTED)
                count++
                i += needle.size
            } else {
                out.append(text[i])
                i++
            }
        }
        out.append(text.substring(i))
        return out.toString() to count
    }

    private fun truncateUtf8(bytes: ByteArray, maxBytes: Int): String {
        var end = maxBytes.coerceAtMost(bytes.size)
        while (end > 0 && (bytes[end - 1].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8) +
            "\n[truncated to $maxBytes bytes]\n"
    }

    /** Etiqueta corta para la revisión de privacidad, sin contenido sensible. */
    fun previewLabel(name: String, bytes: Int, truncated: Boolean, missingReason: String?): String {
        val base = "$name · ${formatBytes(bytes)}" + if (truncated) " · recortado" else ""
        return if (missingReason != null) {
            "$base · ausente: ${missingReason.take(120)}".lowercase(Locale.ROOT)
        } else {
            base
        }
    }

    private fun formatBytes(value: Int): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> "${value / 1024} KiB"
        else -> "${value / (1024 * 1024)} MiB"
    }
}
