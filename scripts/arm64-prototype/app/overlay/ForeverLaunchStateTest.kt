package app.gamenative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ForeverLaunchStateTest {
    @Test
    fun doubleBeginIsRejectedUntilTerminal() {
        val first = ForeverRunMachine.begin("one", null)
        assertThrows(IllegalArgumentException::class.java) { ForeverRunMachine.begin("two", first) }
        val exited = ForeverRunMachine.finish(first, ForeverRunStatus.EXITED)
        assertEquals("three", ForeverRunMachine.begin("three", exited).runId)
    }

    @Test
    fun cancelledRunRejectsLateTransitions() {
        val first = ForeverRunMachine.begin("one", null)
        val cancelled = ForeverRunMachine.cancel(first, "cancel")
        assertEquals(ForeverRunStatus.CANCELLED, cancelled.status)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverRunMachine.move(cancelled, ForeverStage.CLIENT, "late")
        }
    }

    @Test
    fun terminalGateRejectsSecondTerminalCallback() {
        val first = ForeverRunMachine.begin("one", null)
        val exited = ForeverRunMachine.finish(first, ForeverRunStatus.EXITED, "exit")
        assertThrows(IllegalArgumentException::class.java) {
            ForeverRunMachine.finish(exited, ForeverRunStatus.FAILED, "late-error")
        }
    }

    @Test
    fun observedProcessEvidenceRoundTripsWithoutSecrets() {
        val state = ForeverRunState(
            runId = "one",
            stage = ForeverStage.RUNNING,
            status = ForeverRunStatus.ACTIVE,
            argv = listOf("WowB-ARM64.exe", "-config", "BetaSuspendedTest.wtf", "-d3d11"),
            cwd = "/managed/client",
            pid = 42,
            startTicks = 99,
            hostPid = 7,
        )
        val restored = ForeverRunState.fromJson(state.toJson())
        assertEquals(state.argv, restored.argv)
        assertEquals(state.startTicks, restored.startTicks)
        assertEquals(state.hostPid, restored.hostPid)
    }
}
