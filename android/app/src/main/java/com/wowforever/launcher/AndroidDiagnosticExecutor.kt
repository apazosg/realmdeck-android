package com.wowforever.launcher

import android.content.Context
import android.os.Build
import org.json.JSONObject

/** Implementación Android de las tres pruebas que ya existen. No conoce
 * perfiles ni runtimes externos: runtime y GPU permanecen informativos. */
class AndroidDiagnosticExecutor(private val context: Context) {
    private val contractJson: String by lazy {
        context.assets.open("contract.json").bufferedReader().use { it.readText() }
    }

    fun execute(testId: String): DiagnosticExecution = when (testId) {
        DiagnosticCatalog.CONTRACT -> runContract()
        DiagnosticCatalog.EXEC_PROBE -> runExecProbe()
        DiagnosticCatalog.SELF_TEST -> runSelfTest()
        else -> DiagnosticExecution(DiagnosticStatus.UNSUPPORTED, "prueba desconocida: $testId")
    }

    private fun runContract(): DiagnosticExecution {
        val native = NativeBridge.nativeVersion()
        val summary = NativeBridge.contractSummaryJson(contractJson)
        val keyStore = JSONObject(contractJson).getJSONObject("key_store")
        val entryCount = keyStore.getInt("entry_count")
        val entrySize = keyStore.getInt("entry_size")
        val knownKeys = keyStore.getJSONArray("known_keys")
        val fields = ContractSummaryFields(
            entries = entryCount,
            entrySize = entrySize,
            targetGroup = keyStore.getInt("target_group"),
            anchorGroup = keyStore.getInt("anchor_group"),
            blockSize = entryCount * entrySize,
        )
        val valid = native.isNotBlank() && knownKeys.length() == entryCount &&
            ContractSummaryParser.isExact(summary, fields)
        return DiagnosticExecution(
            if (valid) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
            "nativeVersion=$native\ncontractSummary=$summary",
        )
    }

    private fun runExecProbe(): DiagnosticExecution {
        val header = "targetSdk=${context.applicationInfo.targetSdkVersion} " +
            "sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}"
        val body = ExecProbe.runExecProbe(
            context.filesDir.absolutePath,
            context.codeCacheDir.absolutePath,
            context.getExternalFilesDir(null)?.absolutePath,
            context.applicationInfo.nativeLibraryDir,
        )
        val parsed = ExecProbeParser.parse(body)
        return DiagnosticExecution(
            DiagnosticProbePolicy.statusFor(parsed),
            "$header\nparser=${parsed.reason}\n$body",
        )
    }

    private fun runSelfTest(): DiagnosticExecution {
        val output = NativeBridge.crossProcessSelfTest(contractJson)
        return DiagnosticExecution(
            if (SelfTestOutputParser.isPass(output)) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
            output,
        )
    }
}
