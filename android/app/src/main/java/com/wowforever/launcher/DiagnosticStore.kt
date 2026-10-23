package com.wowforever.launcher

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persistencia privada y pequeña, deliberadamente independiente de un runtime.
 * Cada actualización reemplaza el JSON mediante rename y añade una línea al log
 * de la corrida, de forma que una recreación no borre resultados anteriores. */
class DiagnosticStore(filesDir: File) {
    private val runsDir = File(filesDir, "diagnostics/runs").apply { mkdirs() }
    private val rejected = mutableSetOf<String>()

    init {
        check(runsDir.isDirectory) { "no se pudo crear el directorio de corridas" }
    }

    fun createRun(
        runId: String,
        appVersion: String,
        abi: String,
        sdk: Int = Build.VERSION.SDK_INT,
        targetSdk: Int,
        tests: List<DiagnosticTest>,
    ): DiagnosticRun {
        DiagnosticRunIds.require(runId)
        require(tests.map { it.id }.distinct().size == tests.size) {
            "la cola contiene IDs de prueba duplicados"
        }
        val existing = runsDir.list()?.toSet().orEmpty()
        if (!DiagnosticPathPolicy.canCreate(existing, runId)) {
            throw DiagnosticStoreException("run_id ya persistido; no se sobrescriben resultados ni logs: $runId")
        }
        val run = DiagnosticRun(
            runId = runId,
            startedAtMs = System.currentTimeMillis(),
            appVersion = appVersion,
            abi = abi,
            sdk = sdk,
            targetSdk = targetSdk,
            results = tests.map {
                DiagnosticResult(it.id, it.label, DiagnosticStatus.PENDING)
            },
            status = DiagnosticStatus.PENDING,
        )
        save(run)
        appendLog(run.runId, "run_id=${run.runId} status=PENDING")
        return run
    }

    @Synchronized
    fun save(run: DiagnosticRun) {
        val destination = fileFor(run.runId, ".json")
        val temporary = fileFor(run.runId, ".json.tmp")
        temporary.writeText(toJson(run).toString(2) + "\n", Charsets.UTF_8)
        check(temporary.renameTo(destination)) { "no se pudo guardar ${destination.name}" }
    }

    @Synchronized
    fun appendLog(runId: String, line: String) {
        fileFor(runId, ".log").appendText(line + "\n", Charsets.UTF_8)
    }

    @Synchronized
    fun latest(): DiagnosticRun? = validFiles().maxByOrNull { it.lastModified() }?.let { file ->
        runCatching { fromJson(JSONObject(file.readText(Charsets.UTF_8)), file.nameWithoutExtension) }
            .onFailure { rejected += file.name }
            .getOrNull()
    }

    @Synchronized
    fun load(runId: String): DiagnosticRun? {
        DiagnosticRunIds.require(runId)
        val file = fileFor(runId, ".json")
        if (!file.isFile) return null
        return fromJson(JSONObject(file.readText(Charsets.UTF_8)), runId)
    }

    @Synchronized
    fun rejectedPersistedIds(): List<String> {
        validFiles()
        runsDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".log") }
            .forEach { file ->
                val id = file.name.removeSuffix(".log")
                if (DiagnosticRunIds.validate(id) != null) rejected += file.name
            }
        return rejected.toList().sorted()
    }

    fun json(run: DiagnosticRun): String = toJson(run).toString(2) + "\n"

    fun text(run: DiagnosticRun): String {
        val builder = StringBuilder()
        builder.appendLine("WoW Forever diagnostic run ${run.runId}")
        builder.appendLine("status=${run.status} started_at_ms=${run.startedAtMs} finished_at_ms=${run.finishedAtMs ?: ""}")
        builder.appendLine("app_version=${run.appVersion} abi=${run.abi} sdk=${run.sdk} target_sdk=${run.targetSdk}")
        builder.appendLine("runtime=${run.runtime} gpu=${run.gpu}")
        run.results.forEach { result ->
            builder.appendLine("${result.testId}: ${result.status} duration_ms=${duration(result)}")
            if (result.detail.isNotEmpty()) builder.appendLine(result.detail)
        }
        val log = fileFor(run.runId, ".log")
        if (log.isFile) {
            builder.appendLine("\n-- log --")
            builder.append(log.readText(Charsets.UTF_8))
        }
        return builder.toString()
    }

    /** Un run RUNNING que sobrevive a la muerte del proceso ya no tiene un hilo
     * nativo observable por esta instancia; se conserva, pero se marca incierto. */
    fun recoverInterrupted(run: DiagnosticRun): DiagnosticRun? {
        if (run.finishedAtMs != null || DiagnosticQueuePolicy.recover(
                run.status,
                run.results.map { it.status },
            ) == null
        ) return null
        val recovered = run.copy(
            finishedAtMs = System.currentTimeMillis(),
            status = DiagnosticStatus.TIMEOUT,
            results = run.results.map {
                if (it.status == DiagnosticStatus.RUNNING || it.status == DiagnosticStatus.PENDING) {
                    it.copy(
                        status = DiagnosticStatus.TIMEOUT,
                        finishedAtMs = System.currentTimeMillis(),
                        detail = "proceso Android recreado; no se afirma cancelación nativa",
                    )
                } else it
            },
        )
        save(recovered)
        appendLog(recovered.runId, "run_id=${recovered.runId} status=TIMEOUT cause=process_recreated")
        return recovered
    }

    private fun fileFor(runId: String, suffix: String): File =
        DiagnosticPathPolicy.safeChild(runsDir, runId, suffix)

    private fun validFiles(): List<File> {
        val files = runsDir.listFiles().orEmpty()
        return files.filter { file ->
            if (!file.isFile || !file.name.endsWith(".json") || file.name.endsWith(".json.tmp")) return@filter false
            val nameId = file.name.removeSuffix(".json")
            val safeName = DiagnosticRunIds.validate(nameId) == null
            if (!safeName) {
                rejected += file.name
                return@filter false
            }
            runCatching {
                val json = JSONObject(file.readText(Charsets.UTF_8))
                val jsonId = json.getString("run_id")
                if (jsonId != nameId) throw DiagnosticStoreException("run_id no coincide con filename")
                DiagnosticRunIds.require(jsonId)
            }.onFailure { rejected += file.name }
                .isSuccess
        }
    }

    private fun toJson(run: DiagnosticRun): JSONObject = JSONObject().apply {
        put("run_id", run.runId)
        put("started_at_ms", run.startedAtMs)
        put("finished_at_ms", run.finishedAtMs ?: JSONObject.NULL)
        put("status", run.status.name)
        put("app_version", run.appVersion)
        put("abi", run.abi)
        put("sdk", run.sdk)
        put("target_sdk", run.targetSdk)
        put("runtime", run.runtime)
        put("gpu", run.gpu)
        put("results", JSONArray().apply {
            run.results.forEach { result ->
                put(JSONObject().apply {
                    put("test_id", result.testId)
                    put("label", result.label)
                    put("status", result.status.name)
                    put("started_at_ms", result.startedAtMs ?: JSONObject.NULL)
                    put("finished_at_ms", result.finishedAtMs ?: JSONObject.NULL)
                    put("detail", result.detail)
                })
            }
        })
    }

    private fun fromJson(json: JSONObject, expectedRunId: String) : DiagnosticRun {
        val resultsJson = json.optJSONArray("results") ?: JSONArray()
        val results = buildList {
            for (index in 0 until resultsJson.length()) {
                val item = resultsJson.getJSONObject(index)
                add(
                    DiagnosticResult(
                        testId = item.getString("test_id"),
                        label = item.getString("label"),
                        status = DiagnosticStatus.valueOf(item.getString("status")),
                        startedAtMs = item.optLongOrNull("started_at_ms"),
                        finishedAtMs = item.optLongOrNull("finished_at_ms"),
                        detail = item.optString("detail"),
                    ),
                )
            }
        }
        val runId = json.getString("run_id")
        if (runId != expectedRunId) throw DiagnosticStoreException("run_id no coincide con filename")
        DiagnosticRunIds.require(runId)
        return DiagnosticRun(
            runId = runId,
            startedAtMs = json.getLong("started_at_ms"),
            finishedAtMs = json.optLongOrNull("finished_at_ms"),
            appVersion = json.optString("app_version", "?"),
            abi = json.optString("abi", "?"),
            sdk = json.optInt("sdk", 0),
            targetSdk = json.optInt("target_sdk", 0),
            runtime = json.optString("runtime", "none"),
            gpu = json.optString("gpu", "not_measured"),
            results = results,
            status = DiagnosticStatus.valueOf(json.optString("status", "TIMEOUT")),
        )
    }

    private fun duration(result: DiagnosticResult): Long =
        if (result.startedAtMs != null && result.finishedAtMs != null) {
            result.finishedAtMs - result.startedAtMs
        } else 0
}

private fun JSONObject.optLongOrNull(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return optLong(name)
}
