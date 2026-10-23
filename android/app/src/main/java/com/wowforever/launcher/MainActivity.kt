package com.wowforever.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import java.util.UUID

/** Banco de pruebas diagnóstico. La Activity solo presenta estado; la cola y
 * las llamadas JNI sobreviven a una recreación dentro del mismo proceso. */
class MainActivity : Activity() {
    private val coordinator = DiagnosticCoordinator.shared()
    private lateinit var store: DiagnosticStore
    private lateinit var executor: AndroidDiagnosticExecutor
    private lateinit var appVersion: String
    private lateinit var abi: String
    private var destroyed = false
    private var pendingExport: ExportRequest? = null
    private var persistedRejection: String? = null

    private val stateListener: (DiagnosticRun) -> Unit = { run ->
        runOnUiThread {
            if (!destroyed) {
                renderRun(run)
                persistedRejection?.let { showRejected(it) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        destroyed = false
        store = DiagnosticStore(filesDir)
        pendingExport = savedInstanceState?.getString(EXPORT_RUN_ID)?.let { runId ->
            savedInstanceState.getString(EXPORT_FORMAT)?.let { format -> ExportRequest(runId, format) }
        }

        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        appVersion = "${packageInfo.versionName ?: "?"} (versionCode=$versionCode)"
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        findViewById<TextView>(R.id.app_version).text = getString(R.string.app_version_fmt, appVersion)
        findViewById<TextView>(R.id.abi).text = getString(R.string.abi_fmt, abi)
        findViewById<TextView>(R.id.runtime).text = getString(R.string.runtime_fmt, "none")
        findViewById<TextView>(R.id.gpu).text = getString(R.string.gpu_fmt, "not measured")

        showNativeHeader()
        coordinator.attach(store, stateListener)
        persistedRejection = store.rejectedPersistedIds().takeIf { it.isNotEmpty() }?.let { rejected ->
            "IDs persistidos rechazados: ${rejected.joinToString()}"
        }
        persistedRejection?.let { showRejected(it) }

        executor = AndroidDiagnosticExecutor(applicationContext)
        findViewById<Button>(R.id.battery_button).setOnClickListener {
            startTests(
                runId = "run-${UUID.randomUUID().toString().replace("-", "")}",
                tests = DiagnosticCatalog.all,
                appVersion = appVersion,
                abi = abi,
                executor = executor,
            )
        }
        findViewById<Button>(R.id.selftest_button).setOnClickListener {
            startTests(manualRunId(), listOf(DiagnosticCatalog.find(DiagnosticCatalog.SELF_TEST)), appVersion, abi, executor)
        }
        findViewById<Button>(R.id.execprobe_button).setOnClickListener {
            startTests(manualRunId(), listOf(DiagnosticCatalog.find(DiagnosticCatalog.EXEC_PROBE)), appVersion, abi, executor)
        }
        findViewById<Button>(R.id.export_json_button).setOnClickListener {
            requestExport(ExportFormat.JSON)
        }
        findViewById<Button>(R.id.export_text_button).setOnClickListener {
            requestExport(ExportFormat.TEXT)
        }

        handleCompatibilityIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCompatibilityIntent(intent)
    }

    // Contrato del smoke existente: este Intent dispara solo selftest, no la
    // batería completa, y conserva la línea phase=selftest PASS/FAIL. También se
    // procesa en onNewIntent para no depender de que el caller mate la Activity.
    private fun handleCompatibilityIntent(incoming: Intent?) {
        val runId = incoming?.getStringExtra(EXTRA_RUN_ID)
        if (incoming?.getBooleanExtra("run_selftest", false) == true) {
            if (DiagnosticRunIds.validate(runId) != null) {
                showRejected("Intent rechazado: ${DiagnosticRunIds.validate(runId)}")
                return
            }
            startTests(runId!!, listOf(DiagnosticCatalog.find(DiagnosticCatalog.SELF_TEST)), appVersion, abi, executor)
        } else if (incoming?.getBooleanExtra("run_exec_probe", false) == true) {
            if (DiagnosticRunIds.validate(runId) != null) {
                showRejected("Intent rechazado: ${DiagnosticRunIds.validate(runId)}")
                return
            }
            startTests(runId!!, listOf(DiagnosticCatalog.find(DiagnosticCatalog.EXEC_PROBE)), appVersion, abi, executor)
        }
    }

    private fun startTests(
        runId: String,
        tests: List<DiagnosticTest>,
        appVersion: String,
        abi: String,
        executor: AndroidDiagnosticExecutor,
    ) {
        val decision = coordinator.start(
            runId = runId,
            tests = tests,
            appVersion = appVersion,
            abi = abi,
            targetSdk = applicationInfo.targetSdkVersion,
            executor = executor::execute,
        )
        if (!decision.started) showRejected(decision.message ?: "corrida rechazada")
    }

    private fun showNativeHeader() {
        val native = findViewById<TextView>(R.id.native_version)
        val contract = findViewById<TextView>(R.id.contract_summary)
        try {
            native.text = NativeBridge.nativeVersion()
            val json = assets.open("contract.json").bufferedReader().use { it.readText() }
            contract.text = NativeBridge.contractSummaryJson(json)
        } catch (error: UnsatisfiedLinkError) {
            native.text = getString(R.string.unsupported_native, error.message ?: "JNI")
            contract.text = getString(R.string.unsupported_test)
        } catch (error: Throwable) {
            native.text = getString(R.string.jni_error, error.message ?: error.javaClass.simpleName)
            contract.text = getString(R.string.jni_error, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun renderRun(run: DiagnosticRun) {
        findViewById<TextView>(R.id.run_status).text = getString(R.string.run_status_fmt, run.runId, run.status.name)
        val resultText = run.results.joinToString("\n\n") { result ->
            val duration = if (result.startedAtMs != null && result.finishedAtMs != null) {
                result.finishedAtMs - result.startedAtMs
            } else "?"
            "${result.label}: ${result.status.name} (${duration} ms)\n${result.detail}"
        }
        findViewById<TextView>(R.id.results).text = resultText
        findViewById<TextView>(R.id.selftest_result).text = run.results
            .firstOrNull { it.testId == DiagnosticCatalog.SELF_TEST }?.detail.orEmpty()
        findViewById<TextView>(R.id.execprobe_result).text = run.results
            .firstOrNull { it.testId == DiagnosticCatalog.EXEC_PROBE }?.detail.orEmpty()
        val hasRun = run.results.isNotEmpty()
        findViewById<Button>(R.id.export_json_button).isEnabled = hasRun
        findViewById<Button>(R.id.export_text_button).isEnabled = hasRun
    }

    private fun requestExport(format: ExportFormat) {
        val runId = coordinator.current()?.runId ?: store.latest()?.runId
        if (runId == null) {
            showExportFailure("no hay corrida persistida")
            return
        }
        try {
            if (store.load(runId) == null) throw IllegalStateException("run_id no encontrado: $runId")
            val request = ExportRequest(runId, format.name)
            pendingExport = request
            val extension = if (format == ExportFormat.JSON) "json" else "txt"
            val mime = if (format == ExportFormat.JSON) "application/json" else "text/plain"
            createExport("diagnostic-$runId.$extension", mime)
        } catch (error: Throwable) {
            pendingExport = null
            showExportFailure(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun createExport(filename: String, mime: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, filename)
        }
        startActivityForResult(intent, EXPORT_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_REQUEST) return
        val request = pendingExport
        pendingExport = null
        if (resultCode != RESULT_OK || request == null) return
        try {
            val uri: Uri = data?.data ?: throw IllegalStateException("SAF no devolvió URI")
            val run = store.load(request.runId)
                ?: throw IllegalStateException("run_id no encontrado: ${request.runId}")
            val contents = when (request.format) {
                ExportFormat.JSON.name -> store.json(run)
                ExportFormat.TEXT.name -> store.text(run)
                else -> throw IllegalStateException("formato SAF rechazado: ${request.format}")
            }
            val output = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("SAF no abrió el destino")
            output.use { it.write(contents.toByteArray(Charsets.UTF_8)) }
            findViewById<TextView>(R.id.export_status).text = getString(R.string.exported_fmt, uri.toString())
        } catch (error: Throwable) {
            showExportFailure(error.message ?: error.javaClass.simpleName)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingExport?.let {
            outState.putString(EXPORT_RUN_ID, it.runId)
            outState.putString(EXPORT_FORMAT, it.format)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        destroyed = true
        coordinator.detach(stateListener)
        super.onDestroy()
    }

    private fun manualRunId() = "manual-${UUID.randomUUID().toString().replace("-", "")}"

    private fun showRejected(message: String) {
        findViewById<TextView>(R.id.run_status).text = getString(R.string.rejected_fmt, message)
        findViewById<TextView>(R.id.results).text = message
    }

    private fun showExportFailure(message: String) {
        findViewById<TextView>(R.id.export_status).text = getString(R.string.export_failed_fmt, message)
    }

    private enum class ExportFormat { JSON, TEXT }

    companion object {
        const val EXTRA_RUN_ID = "run_id"
        private const val EXPORT_REQUEST = 42
        private const val EXPORT_RUN_ID = "pending_export_run_id"
        private const val EXPORT_FORMAT = "pending_export_format"
    }
}
