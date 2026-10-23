package app.gamenative

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Log Wine acotado por intento para el diagnóstico remoto.
 *
 * El callback ya existente de ProcessHelper (siempre activo en C117 cuando hay
 * debug callback) vuelca aquí cada línea: head de 128 KiB + fichero rotativo
 * de 512 KiB con UN prev. Sin colas sin cota, sin sondas nuevas, sin red.
 * La captura lo lee como 'stdout/stderr combinado sin atribución WoW'.
 */
object ForeverRemoteDiagnosticsWineLog {
    const val MAX_BYTES = 512 * 1024
    const val MAX_LINE_CHARS = 4096
    private const val HEAD_BYTES = 128 * 1024
    private const val PREV_SUFFIX = ".prev"

    private val SAFE_ATTEMPT = Regex("[A-Za-z0-9_-]{1,64}")

    fun fileFor(dir: File, attemptId: String): File? {
        if (!SAFE_ATTEMPT.matches(attemptId)) return null
        return runCatching {
            val file = File(dir, "wine-$attemptId.log")
            if (Files.isSymbolicLink(file.toPath())) null else file
        }.getOrNull()
    }

    /** Añade una línea con tope por línea y rotación acotada. Nunca bloquea UI. */
    @Synchronized
    fun append(dir: File, attemptId: String, line: String) {
        val file = fileFor(dir, attemptId) ?: return
        runCatching {
            dir.mkdirs()
            if (Files.isSymbolicLink(dir.toPath())) return
            val clean = line.replace('\r', ' ').replace('\n', ' ').take(MAX_LINE_CHARS) + "\n"
            val bytes = clean.toByteArray(Charsets.UTF_8).take(4096).toByteArray()
            if (file.length() + bytes.size > MAX_BYTES) rotate(dir, attemptId, file)
            if (file.length() + bytes.size > MAX_BYTES) return
            file.appendBytes(bytes)
            // Bound storage across attempts, not only within each attempt.
            dir.listFiles { candidate ->
                candidate.name.matches(Regex("wine-[A-Za-z0-9_-]{1,64}\\.log"))
            }?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { old ->
                old.delete()
                File(dir, old.name + PREV_SUFFIX).delete()
            }
        }
    }

    private fun rotate(dir: File, attemptId: String, file: File) {
        runCatching {
            val prev = File(dir, "wine-$attemptId.log$PREV_SUFFIX")
            if (Files.isSymbolicLink(prev.toPath())) prev.delete()
            prev.delete()
            if (file.exists()) {
                require(file.renameTo(prev)) { "wine log rotation failed" }
            }
            file.writeText("[rotated: older lines kept in ${prev.name}]\n", Charsets.UTF_8)
        }
    }

    data class Read(val text: String, val truncated: Boolean, val present: Boolean)

    /** Cola del prev + actual vigente, acotado. Sin prev no hay recorte. */
    @Synchronized
    fun read(dir: File, attemptId: String): Read {
        val file = fileFor(dir, attemptId) ?: return Read("", false, false)
        return runCatching {
            if (!file.isFile) return Read("", false, false)
            val prev = File(dir, "wine-$attemptId.log$PREV_SUFFIX")
            val prevTail = if (prev.isFile && !Files.isSymbolicLink(prev.toPath())) {
                tailBytes(prev, HEAD_BYTES)
            } else {
                ByteArray(0)
            }
            val current = boundedBytes(file, MAX_BYTES + MAX_LINE_CHARS)
            val combined = prevTail + current
            Read(
                text = String(combined, Charsets.UTF_8),
                truncated = prevTail.isNotEmpty(),
                present = true,
            )
        }.getOrDefault(Read("", false, false))
    }

    private fun boundedBytes(file: File, maxBytes: Int): ByteArray {
        file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() < maxBytes) {
                val count = input.read(buffer, 0, (maxBytes - out.size()).coerceAtMost(buffer.size))
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }
    }

    private fun tailBytes(file: File, maxBytes: Int): ByteArray {
        val size = file.length()
        if (size <= 0) return ByteArray(0)
        if (size <= maxBytes) return boundedBytes(file, maxBytes)
        file.inputStream().use { input ->
            var skipped = 0L
            while (skipped < size - maxBytes) {
                val step = input.skip(size - maxBytes - skipped)
                if (step <= 0) break
                skipped += step
            }
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var remaining = maxBytes
            while (remaining > 0) {
                val count = input.read(buffer, 0, remaining.coerceAtMost(buffer.size))
                if (count < 0) break
                out.write(buffer, 0, count)
                remaining -= count
            }
            val bytes = out.toByteArray()
            var start = 0
            while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start++
            return bytes.copyOfRange(start, bytes.size)
        }
    }
}
