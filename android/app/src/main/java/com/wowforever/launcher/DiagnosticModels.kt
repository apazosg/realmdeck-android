package com.wowforever.launcher

/** Estados que se pueden afirmar para una prueba. RUNNING/PENDING solo son
 * estados transitorios de la cola; nunca se convierten implícitamente en PASS. */
enum class DiagnosticStatus {
    PENDING,
    RUNNING,
    PASS,
    FAIL,
    TIMEOUT,
    UNSUPPORTED,
}

data class DiagnosticTest(
    val id: String,
    val label: String,
    val timeoutMs: Long,
)

data class DiagnosticResult(
    val testId: String,
    val label: String,
    val status: DiagnosticStatus,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val detail: String = "",
)

data class DiagnosticRun(
    val runId: String,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    val appVersion: String,
    val abi: String,
    val sdk: Int,
    val targetSdk: Int,
    val runtime: String = "none",
    val gpu: String = "not_measured",
    val results: List<DiagnosticResult>,
    val status: DiagnosticStatus,
)

object DiagnosticCatalog {
    const val CONTRACT = "jni_contract"
    const val EXEC_PROBE = "exec_probe"
    const val SELF_TEST = "cross_process_self_test"

    val all: List<DiagnosticTest> = listOf(
        DiagnosticTest(CONTRACT, "JNI + contrato", 15_000),
        DiagnosticTest(EXEC_PROBE, "ExecProbe", 45_000),
        DiagnosticTest(SELF_TEST, "crossProcessSelfTest", 90_000),
    )

    fun find(id: String): DiagnosticTest = all.first { it.id == id }
}

data class DiagnosticExecution(
    val status: DiagnosticStatus,
    val detail: String,
)

data class DiagnosticStart(
    val started: Boolean,
    val message: String? = null,
)
