package app.gamenative

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Captura local acotada para el snapshot: solo fuentes persistidas por el
 * launcher, sin root/READ_LOGS/ADB ni sondeo del teléfono.
 *
 * Los snapshots stdout/stderr se VALIDAN y DECODIFICAN con
 * BoundedLogRetention.readPersisted antes de sanear: nunca se envían
 * envoltorios base64 crudos que ocultarían secretos al saneador de texto.
 * Nunca lee preferencias completas, claves, binarios, dumps ni screenshots.
 * Cada fuente ausente o recortada se informa con motivo explícito.
 */
object ForeverRemoteDiagnosticsLocalCapture {
    data class Captured(val name: String, val text: String, val truncated: Boolean, val missingReason: String?)

    /** Cota por fuente: head/tail acotado de lo realmente retenido, sin inflar. */
    const val SOURCE_CAP_BYTES = 2 * 1024 * 1024
    private const val SOURCE_HEAD_BYTES = 256 * 1024

    private val SAFE_RUN_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")

    private fun safeFile(file: File): Boolean = runCatching {
        !Files.isSymbolicLink(file.toPath()) && file.isFile
    }.getOrDefault(false)

    /**
     * Lee la cola acotada de un fichero con head/tail sin cargarlo entero.
     */
    fun readTail(file: File, maxBytes: Int = SOURCE_CAP_BYTES): Pair<String, Boolean> {
        if (!safeFile(file)) return "" to false
        val size = file.length()
        if (size <= 0) return "" to false
        if (size <= maxBytes) {
            val bytes = readBounded(file, maxBytes)
            return String(bytes, Charsets.UTF_8) to false
        }
        val headBytes = SOURCE_HEAD_BYTES.coerceAtMost(maxBytes / 4)
        val head = decodeHead(readBounded(file, headBytes, 0), headBytes)
        val tailSize = (maxBytes - head.toByteArray(Charsets.UTF_8).size - 128).coerceAtLeast(1024)
        val tail = decodeTail(readBounded(file, tailSize, size - tailSize), tailSize)
        return ("$head\n[capture truncated: total=$size bytes]\n$tail") to true
    }

    private fun readBounded(file: File, maxBytes: Int, offset: Long = 0): ByteArray {
        file.inputStream().use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val step = input.skip(offset - skipped)
                if (step <= 0) break
                skipped += step
            }
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

    private fun decodeHead(bytes: ByteArray, max: Int): String {
        var end = max.coerceAtMost(bytes.size)
        while (end > 0 && (bytes[end - 1].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    private fun decodeTail(bytes: ByteArray, max: Int): String {
        var start = (bytes.size - max).coerceAtLeast(0)
        while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start++
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    /**
     * Decodifica un snapshot stdout/stderr validando su envoltorio y une
     * head+tail ordenados como texto plano saneable. Nunca base64 crudo.
     */
    fun decodeStreamSnapshot(outputDir: File, runId: String, stream: String): Pair<String, Boolean> {
        if (!SAFE_RUN_ID.matches(runId) || stream !in setOf("stdout", "stderr")) return "" to false
        if (!safeFile(File(outputDir, "$runId-$stream-snapshot.json"))) return "" to false
        return runCatching {
            val saved = BoundedLogRetention.readPersisted(outputDir, runId, stream)
            val head = saved.head
            val tail = saved.tail
            val total = saved.metadata.totalBytes
            val overlap = maxOf(0L, head.size.toLong() - (total - tail.size)).toInt().coerceAtMost(tail.size)
            val combined = ByteArray(head.size + tail.size - overlap)
            head.copyInto(combined)
            tail.copyInto(combined, head.size, overlap)
            val marker = if (saved.metadata.truncated) "[output truncated: head=128KiB tail=384KiB]\n" else ""
            (marker + String(combined, Charsets.UTF_8)) to saved.metadata.truncated
        }.getOrDefault("" to false)
    }

    /**
     * Captura las fuentes disponibles. [runId] nulo = sin run gestionado
     * (precondición o ruta funcional). [wineAttemptId] lee SOLO el log Wine
     * del intento vigente; sin él no hay fuente wine.
     */
    fun capture(filesDir: File, runId: String?, gameObserved: Boolean = false,
        wineAttemptId: String? = null): List<Captured> {
        if (runId != null && !SAFE_RUN_ID.matches(runId)) {
            return listOf(Captured("diagnostics", "", false, "unsafe run id rejected"))
        }
        val forever = File(filesDir, "forever")
        val runs = File(forever, "runs")
        val out = mutableListOf<Captured>()
        val intent = ForeverRemoteDiagnosticsStore.readLatestIntent(filesDir)
            ?.takeIf { it.attemptId == wineAttemptId }

        if (runId != null) {
            val diagnostics = File(runs, "$runId-diagnostics.jsonl")
            if (safeFile(diagnostics)) {
                val (text, truncated) = readTail(diagnostics)
                out += Captured("diagnostics", text, truncated, null)
            } else {
                out += Captured("diagnostics", "", false, "run diagnostics journal is absent")
            }
            val output = File(runs, runId)
            for (stream in listOf("stdout", "stderr")) {
                val (text, truncated) = decodeStreamSnapshot(output, runId, stream)
                if (text.isNotEmpty()) {
                    out += Captured(stream, text, truncated, null)
                } else {
                    out += Captured(stream, "", false, "bounded $stream snapshot is absent or invalid")
                }
            }
        } else {
            if (intent != null) {
                val evidence = org.json.JSONObject()
                    .put("attemptId", intent.attemptId).put("atMs", intent.atMs)
                    .put("stage", intent.stage ?: org.json.JSONObject.NULL)
                    .put("result", intent.result ?: org.json.JSONObject.NULL)
                    .put("error", intent.error ?: org.json.JSONObject.NULL)
                    .put("endedAtMs", intent.endedAtMs ?: org.json.JSONObject.NULL)
                    .put("exitCode", intent.exitCode ?: org.json.JSONObject.NULL)
                out += Captured("diagnostics", evidence.toString(), false, null)
            } else {
                out += Captured("diagnostics", "", false, "no durable launch intent; historical events unavailable")
            }
            for (name in listOf("stdout", "stderr")) {
                out += Captured(name, "", false, "functional route without managed run; not a perfect precondition")
            }
        }

        val pocLog = intent?.logFile?.takeIf { it.matches(Regex("run-\\d+\\.jsonl")) }
            ?.let { name ->
                val dir = File(filesDir, "functional-poc")
                File(dir, name).takeIf { !Files.isSymbolicLink(dir.toPath()) && safeFile(it) }
            }
        if (pocLog != null) {
            val (text, truncated) = readTail(pocLog)
            out += Captured("launcher", text, truncated, null)
        } else {
            out += Captured("launcher", "", false, "no functional PoC log bound to this attempt")
        }
        // Wine solo a través del log del intento vigente (callback existente).
        val wineDir = File(File(filesDir, "forever"), ForeverRemoteDiagnosticsStore.DIR_NAME)
        val wine = if (wineAttemptId != null) {
            ForeverRemoteDiagnosticsWineLog.read(wineDir, wineAttemptId)
        } else {
            ForeverRemoteDiagnosticsWineLog.Read("", false, false)
        }
        if (wine.present) {
            out += Captured("wine", wine.text, wine.truncated, null)
        } else {
            out += Captured("wine", "", false, "no wine lines for this attempt; functional route has no managed capture")
        }
        out += if (gameObserved) {
            Captured("wow", "", false, "game identity observed but no game stdout is captured; render stays unverified")
        } else {
            Captured("wow", "", false, "no game output is captured; renderer/auth stay unverified")
        }
        return out
    }

}
