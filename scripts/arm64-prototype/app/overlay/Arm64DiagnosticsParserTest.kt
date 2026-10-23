package app.gamenative

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class Arm64DiagnosticsParserTest {
    private val stages = mapOf(
        "HelloArm64" to listOf("architecture"),
        "ApiArm64" to listOf(
            "load-library-search-system32",
            "load-library-default",
            "get-module-kernel32",
            "get-proc-initialize-critical-section-ex",
            "get-proc-load-library-ex",
            "initialize-critical-section",
            "free-library-search-system32",
            "free-library-default",
        ),
        "DynCodeArm64" to listOf(
            "private-rw-rx",
            "shared-section",
            "shared-views",
            "shared-first-call",
            "shared-second-call",
            "shared-cleanup",
        ),
    )

    @Test
    fun parsesAllProbeLogsAndExpectedExits() {
        val fixtures = listOf(
            "HelloArm64" to 42,
            "ApiArm64" to 0,
            "DynCodeArm64" to 0,
        )
        for ((name, expected) in fixtures) {
            val file = File.createTempFile("arm64-$name", ".log")
            try {
                val lines = mutableListOf(
                    "RUN-ID=RUN-test PROBE-BEGIN name=$name pe_machine=0xAA64",
                )
                for (stage in stages.getValue(name)) {
                    lines += "RUN-ID=RUN-test STAGE-BEGIN name=$stage"
                    lines += "RUN-ID=RUN-test STAGE-END name=$stage returncode=0"
                }
                if (name == "DynCodeArm64") {
                    lines += "RUN-ID=RUN-test STAGE-NOT-INCLUDED name=syscall-copied reason=no-arbitrary-or-offset-fragile-byte-copy"
                }
                lines += "RUN-ID=RUN-test PROBE-END name=$name returncode=$expected"
                file.writeText(lines.joinToString("\n") + "\n")
                assertEquals("PASS", Arm64Diagnostics.parseProbeLogForTest(file, "RUN-test", expected, name).getString("status"))
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun rejectsMissingAndOversizedLogs() {
        val missing = File("/no/such/arm64-log")
        assertEquals("MISSING", Arm64Diagnostics.parseProbeLogForTest(missing, "RUN-test", 0, "ApiArm64").getString("status"))
        val oversized = File.createTempFile("arm64-large", ".log")
        try {
            oversized.writeBytes(ByteArray(512 * 1024 + 1))
            assertEquals("FAILED", Arm64Diagnostics.parseProbeLogForTest(oversized, "RUN-test", 0, "ApiArm64").getString("status"))
        } finally {
            oversized.delete()
        }
    }

    @Test
    fun rejectsDuplicateConflictingAndTruncatedMarkers() {
        val file = File.createTempFile("arm64-negative", ".log")
        try {
            val valid = listOf(
                "RUN-ID=RUN-test PROBE-BEGIN name=HelloArm64 pe_machine=0xAA64",
                "RUN-ID=RUN-test STAGE-BEGIN name=architecture",
                "RUN-ID=RUN-test STAGE-END name=architecture returncode=0",
                "RUN-ID=RUN-test PROBE-END name=HelloArm64 returncode=42",
            )
            file.writeText((valid + valid.last()).joinToString("\n") + "\n")
            assertEquals("FAILED", Arm64Diagnostics.parseProbeLogForTest(file, "RUN-test", 42, "HelloArm64").getString("status"))

            file.writeText(valid.joinToString("\n"))
            assertEquals("FAILED", Arm64Diagnostics.parseProbeLogForTest(file, "RUN-test", 42, "HelloArm64").getString("status"))

            file.writeText(valid.map { it.replace("RUN-test", "OTHER") }.joinToString("\n") + "\n")
            assertEquals("FAILED", Arm64Diagnostics.parseProbeLogForTest(file, "RUN-test", 42, "HelloArm64").getString("status"))
        } finally {
            file.delete()
        }
    }
}
