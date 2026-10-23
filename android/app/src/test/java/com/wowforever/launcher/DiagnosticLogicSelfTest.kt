package com.wowforever.launcher

import java.nio.file.Files

/** Test JVM sin JUnit/AndroidX: se ejecuta como JavaExec offline. */
object DiagnosticLogicSelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        runIdAndPathTests()
        probeParserTests()
        contractAndSelfTestTests()
        queueAndExportTests()
        println("DiagnosticLogicSelfTest: PASS")
    }

    private fun runIdAndPathTests() {
        check(DiagnosticRunIds.validate("run-abc_123.v1") == null)
        check(DiagnosticRunIds.validate("../escape") != null)
        check(DiagnosticRunIds.validate("bad id") != null)
        check(DiagnosticRunIds.validate("a".repeat(129)) != null)
        val root = Files.createTempDirectory("diagnostic-store").toFile()
        check(DiagnosticPathPolicy.safeChild(root, "safe-id", ".json").parentFile == root.canonicalFile)
        check(!DiagnosticPathPolicy.canCreate(setOf("safe-id.json"), "safe-id"))
        check(DiagnosticPathPolicy.canCreate(emptySet(), "new-id"))
        runCatching { DiagnosticPathPolicy.safeChild(root, "../escape", ".log") }
            .onSuccess { error("path traversal accepted") }
    }

    private fun probeParserTests() {
        val good = """
            exec_probe src=/system/bin/sh
            dir=filesDir path=/data/files src=/system/bin/sh write=OK(10B) exec=OK exit=42 (el kernel EJECUTÓ el binario)
            dir=codeCacheDir path=/data/cache src=/system/bin/sh write=OK(10B) exec=OK exit=42 (el kernel EJECUTÓ el binario)
            dir=externalFilesDir path=/sdcard/files src=/system/bin/sh write=OK(10B) exec=FALLÓ: Permission denied (errno=13 EACCES)
            dir=nativeLibraryDir path=/data/lib src=/system/bin/sh write=open_dst falló: Permission denied (errno=13 EACCES) exec=no_intentado
            control=C1_directa path=/system/bin/sh exec=OK exit=7 (el kernel EJECUTÓ el binario)
            control=C2_so path=/data/lib/libprobe.so exec=FALLÓ: Exec format error (errno=8 ENOEXEC)
        """.trimIndent()
        check(ExecProbeParser.parse(good).pass)
        val executedSo = good.replace(
            "control=C2_so path=/data/lib/libprobe.so exec=FALLÓ: Exec format error (errno=8 ENOEXEC)",
            "control=C2_so path=/data/lib/libprobe.so exec=OK pero señal=11 (execve SÍ permitió arrancar)",
        )
        check(ExecProbeParser.parse(executedSo).pass)
        val skipped = good.replace(
            "control=C2_so path=/data/lib/libprobe.so exec=FALLÓ: Exec format error (errno=8 ENOEXEC)",
            "control=C2_so SKIP (sin .so en nativeLibraryDir)",
        )
        check(!ExecProbeParser.parse(skipped).pass && ExecProbeParser.parse(skipped).unsupported)
        check(!ExecProbeParser.parse(good.replace("ENOEXEC", "TIMEOUT")).pass)
        check(!ExecProbeParser.parse(good.replace("ENOEXEC", "FORK_FALLÓ")).pass)
        check(!ExecProbeParser.parse(good.replace("ENOEXEC", "UNKNOWN")).pass)
        check(!ExecProbeParser.parse(good.replace("dir=filesDir", "dir=otherDir")).pass)
        check(!ExecProbeParser.parse(good.replace("exec=OK exit=42 (el kernel EJECUTÓ el binario)", "exec=FALLÓ: Permission denied (errno=13 EACCES)")).pass)
        check(!ExecProbeParser.parse(good + "\n" + good.lineSequence().first { it.startsWith("dir=filesDir") }).pass)
        check(!ExecProbeParser.parse(good.replace("control=C1_directa", "control=C1_ausente")).pass)
        check(DiagnosticProbePolicy.statusFor(ExecProbeParser.parse(good)) == DiagnosticStatus.PASS)
        check(DiagnosticProbePolicy.statusFor(ExecProbeParser.parse(skipped)) == DiagnosticStatus.UNSUPPORTED)
        check(DiagnosticProbePolicy.statusFor(ProbeParseResult(false, "invalid")) == DiagnosticStatus.FAIL)
    }

    private fun contractAndSelfTestTests() {
        val fields = ContractSummaryFields(12, 40, 8, 1, 480)
        check(ContractSummaryParser.isExact(fields.expected(), fields))
        check(!ContractSummaryParser.isExact("entries=12 target_group=8", fields))
        val good = "result=OK stores=1 patched=true child_confirm=PATCHED " +
            "rescan_already_patched=true child_alive_after_scan=true first_err=none"
        check(SelfTestOutputParser.isPass(good))
        check(!SelfTestOutputParser.isPass(good.replace("result=OK", "result=OKISH")))
        check(!SelfTestOutputParser.isPass(good.replace("first_err=none", "first_err=EACCES")))
        check(!SelfTestOutputParser.isPass("$good first_err=EACCES"))
        check(!SelfTestOutputParser.isPass("$good child_confirm=FAILED"))
    }

    private fun queueAndExportTests() {
        check(DiagnosticQueuePolicy.recover(DiagnosticStatus.PENDING, listOf(DiagnosticStatus.PENDING)) == DiagnosticStatus.TIMEOUT)
        check(DiagnosticQueuePolicy.recover(DiagnosticStatus.RUNNING, listOf(DiagnosticStatus.PASS)) == DiagnosticStatus.TIMEOUT)
        val gate = DiagnosticSerialGate()
        check(gate.tryStart())
        gate.markTimedOut()
        check(!gate.canStartNext())
        check(!gate.tryStart())
        gate.markFinished()
        check(gate.canStartNext() && gate.tryStart())
        check(ExportRequest("run-fixed", "JSON") == ExportRequest("run-fixed", "JSON"))
    }
}
