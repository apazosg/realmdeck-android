package com.wowforever.launcher

import java.io.File

/** Reglas puras compartidas por UI, persistencia y pruebas JVM. */
object DiagnosticRunIds {
    private val safe = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    fun validate(value: String?): String? = when {
        value == null -> "falta run_id"
        value.length !in 1..128 -> "run_id debe tener entre 1 y 128 caracteres"
        !safe.matches(value) -> "run_id contiene caracteres no permitidos"
        else -> null
    }

    fun require(value: String): String {
        validate(value)?.let { throw DiagnosticStoreException(it) }
        return value
    }
}

class DiagnosticStoreException(message: String) : IllegalArgumentException(message)

object DiagnosticPathPolicy {
    fun safeChild(root: File, runId: String, suffix: String): File {
        DiagnosticRunIds.require(runId)
        require(suffix == ".json" || suffix == ".log" || suffix == ".json.tmp") {
            "sufijo de corrida no permitido"
        }
        val canonicalRoot = root.canonicalFile
        val candidate = File(canonicalRoot, "$runId$suffix").canonicalFile
        check(candidate.parentFile == canonicalRoot) { "ruta de corrida fuera del directorio" }
        return candidate
    }

    fun canCreate(existingNames: Set<String>, runId: String): Boolean {
        DiagnosticRunIds.require(runId)
        return "${runId}.json" !in existingNames &&
            "${runId}.log" !in existingNames &&
            "${runId}.json.tmp" !in existingNames
    }
}

data class ContractSummaryFields(
    val entries: Int,
    val entrySize: Int,
    val targetGroup: Int,
    val anchorGroup: Int,
    val blockSize: Int,
) {
    fun expected(): String =
        "entries=$entries entry_size=$entrySize target_group=$targetGroup " +
            "anchor_group=$anchorGroup block_size=$blockSize"
}

object ContractSummaryParser {
    fun isExact(summary: String, fields: ContractSummaryFields): Boolean =
        summary.trim() == fields.expected()
}

data class ProbeParseResult(
    val pass: Boolean,
    val reason: String,
    val unsupported: Boolean = false,
)

/** Analiza el formato textual de exec_probe por registros, nunca por contains()
 * globales. */
object ExecProbeParser {
    private val source = Regex("^exec_probe src=/\\S+$")
    private val directory = Regex("^dir=(\\S+) path=(\\S+) src=(\\S+) write=(.+) exec=(.+)$")
    private val c1 = Regex("^control=C1_directa path=/\\S+ exec=OK exit=7 \\(el kernel EJECUTÓ el binario\\)$")
    private val c2Skip = Regex("^control=C2_so SKIP \\(sin \\.so en nativeLibraryDir\\)$")
    private val c2Reject = Regex("^control=C2_so path=/\\S+ exec=FALLÓ: .*\\(errno=\\d+ (?:ENOEXEC|EACCES)\\)$")
    private val c2Executes = Regex("^control=C2_so path=/\\S+ exec=OK pero señal=\\d+ \\(execve SÍ permitió arrancar\\)$")
    private val copied = Regex("^OK\\([1-9][0-9]*B\\)$")

    fun parse(output: String): ProbeParseResult {
        val lines = output.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.toList()
        if (lines.count(source::matches) != 1) return ProbeParseResult(false, "source ausente o duplicado")
        val dirs = linkedMapOf<String, MatchResult>()
        var c1Count = 0
        var c2Count = 0
        var c2Skipped = false
        for (line in lines) {
            when {
                source.matches(line) -> Unit
                c1.matches(line) -> c1Count++
                c2Skip.matches(line) -> {
                    c2Count++
                    c2Skipped = true
                }
                c2Reject.matches(line) -> c2Count++
                c2Executes.matches(line) -> c2Count++
                line.startsWith("dir=") -> {
                    val match = directory.matchEntire(line)
                        ?: return ProbeParseResult(false, "registro de directorio inválido")
                    val name = match.groupValues[1]
                    if (name !in setOf("filesDir", "codeCacheDir", "externalFilesDir", "nativeLibraryDir")) {
                        return ProbeParseResult(false, "directorio inesperado: $name")
                    }
                    if (dirs.put(name, match) != null) return ProbeParseResult(false, "directorio duplicado: $name")
                    val write = match.groupValues[4]
                    val exec = match.groupValues[5]
                    when (name) {
                        "filesDir", "codeCacheDir" -> {
                            if (!copied.matches(write) || exec != "OK exit=42 (el kernel EJECUTÓ el binario)") {
                                return ProbeParseResult(false, "resultado inválido en $name")
                            }
                        }
                        "externalFilesDir" -> {
                            if (!copied.matches(write) || exec != "FALLÓ: Permission denied (errno=13 EACCES)") {
                                return ProbeParseResult(false, "resultado inválido en externalFilesDir")
                            }
                        }
                        "nativeLibraryDir" -> {
                            if (write != "open_dst falló: Permission denied (errno=13 EACCES)" ||
                                exec != "no_intentado"
                            ) return ProbeParseResult(false, "resultado inválido en nativeLibraryDir")
                        }
                    }
                }
                else -> return ProbeParseResult(false, "línea inesperada")
            }
        }
        val expected = setOf("filesDir", "codeCacheDir", "externalFilesDir", "nativeLibraryDir")
        if (dirs.keys != expected) return ProbeParseResult(false, "directorio ausente")
        if (c1Count != 1 || c2Count != 1) return ProbeParseResult(false, "control ausente o duplicado")
        if (c2Skipped) return ProbeParseResult(false, "C2 sin .so: capacidad no medida", unsupported = true)
        return ProbeParseResult(true, "ok")
    }
}

object DiagnosticProbePolicy {
    fun statusFor(parsed: ProbeParseResult): DiagnosticStatus = when {
        parsed.pass -> DiagnosticStatus.PASS
        parsed.unsupported -> DiagnosticStatus.UNSUPPORTED
        else -> DiagnosticStatus.FAIL
    }
}

object SelfTestOutputParser {
    private val field = Regex("^([A-Za-z_]+)=(\\S+)$")
    private val required = mapOf(
        "result" to "OK",
        "stores" to "1",
        "patched" to "true",
        "child_confirm" to "PATCHED",
        "rescan_already_patched" to "true",
        "child_alive_after_scan" to "true",
        "first_err" to "none",
    )

    fun isPass(output: String): Boolean {
        val tokens = output.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.firstOrNull() != "result=OK") return false
        val occurrences = tokens.mapNotNull { token ->
            field.matchEntire(token)?.let { it.groupValues[1] to it.groupValues[2] }
        }.groupingBy { it.first }.eachCount()
        if (required.keys.any { occurrences[it] != 1 }) return false
        return required.all { (key, expected) ->
            tokens.count { field.matchEntire(it)?.let { match -> match.groupValues[1] == key && match.groupValues[2] == expected } == true } == 1
        }
    }
}

object DiagnosticQueuePolicy {
    fun canStartNext(nativeCallAlive: Boolean): Boolean = !nativeCallAlive

    fun recover(status: DiagnosticStatus, resultStatuses: List<DiagnosticStatus>): DiagnosticStatus? {
        val interrupted = status == DiagnosticStatus.PENDING || status == DiagnosticStatus.RUNNING ||
            resultStatuses.any { it == DiagnosticStatus.PENDING || it == DiagnosticStatus.RUNNING }
        return if (interrupted) DiagnosticStatus.TIMEOUT else null
    }
}

/** Pequeña guarda pura que documenta/protege la regla timeout: el siguiente
 * trabajo solo puede empezar después de observar la salida del anterior. */
class DiagnosticSerialGate {
    private var nativeCallAlive = false

    fun tryStart(): Boolean {
        if (nativeCallAlive) return false
        nativeCallAlive = true
        return true
    }

    fun markTimedOut() {
        check(nativeCallAlive)
    }

    fun markFinished() {
        check(nativeCallAlive)
        nativeCallAlive = false
    }

    fun canStartNext(): Boolean = DiagnosticQueuePolicy.canStartNext(nativeCallAlive)
}

data class ExportRequest(val runId: String, val format: String)
