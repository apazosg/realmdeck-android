package app.gamenative

import org.json.JSONObject
import android.system.ErrnoException
import android.system.OsConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29], application = android.app.Application::class)
class ForeverWineSupervisorPolicyTest {
    @Test fun pinsBudgetsAndDedicatedProcessAreFrozen() {
        assertEquals(2, ForeverWineSupervisorPolicy.SCHEMA)
        assertEquals("READY", ForeverWineSupervisorPolicy.SUPERVISOR_PIN_STATUS)
        assertEquals("READY", ForeverWineSupervisorPolicy.PUBLISHER_PIN_STATUS)
        assertEquals(45_056L, ForeverWineSupervisorPolicy.SUPERVISOR_SIZE)
        assertEquals("6f641c3683b239eb58a3986e947afe2099555ef138d495f8490a000b5b52f0da", ForeverWineSupervisorPolicy.SUPERVISOR_SHA256)
        assertEquals(123_560L, ForeverWineSupervisorPolicy.PUBLISHER_SIZE)
        assertEquals("f479ea8def9dc3cffd42affa489ae1554f9214273b13af7d79053a2773ad6914", ForeverWineSupervisorPolicy.PUBLISHER_SHA256)
        assertEquals(64 * 1024, ForeverWineSupervisorPolicy.MAX_STREAM_BYTES)
        assertEquals(256 * 1024, ForeverWineSupervisorPolicy.MAX_OUTPUT_BYTES)
        assertTrue(ForeverWineSupervisorPolicy.isSupervisorProcess(true, "com.wowforever.arm64probe", "com.wowforever.arm64probe:wine_supervisor"))
    }

    @Test fun onlyPlatformLinkerAndKnownApexAliasAreAccepted() {
        assertTrue(runCatching {
            ForeverWineSupervisorPolicy.validatePublisherArgv(listOf("/system/bin/linker64", "/test/publisher"))
        }.isSuccess)
        assertTrue(runCatching {
            ForeverWineSupervisorPolicy.validatePublisherArgv(listOf("/apex/com.android.runtime/bin/linker64", "/test/publisher"))
        }.isSuccess)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverWineSupervisorPolicy.validatePublisherArgv(listOf("/data/local/tmp/linker64", "/test/publisher"))
        }
    }

    @Test fun commandIsFlatExactAndPhaseClosed() {
        val run = "WS-" + "a".repeat(32); val nonce = "b".repeat(32)
        val command = ForeverWineCommand.create(1, run, nonce, ForeverWineCommandKind.START, ForeverWinePhase.SERVER)
        assertEquals(ForeverWineSupervisorPolicy.COMMAND_KEYS, command.json.keys().asSequence().toSet())
        assertEquals(command.json.toString(), ForeverWineCommand.parse(command.json.toString(), run, nonce).json.toString())
        assertThrows(IllegalArgumentException::class.java) {
            ForeverWineCommand.parse(command.json.put("extra", true).toString(), run, nonce)
        }
    }

    @Test fun eventRejectsDuplicateExtraWrongNonceAndTypedFloat() {
        val run = "WS-" + "a".repeat(32); val nonce = "b".repeat(32)
        val event = JSONObject().apply {
            put("schema", 2); put("runId", run); put("nonce", nonce); put("seq", 1); put("event", "READY"); put("phase", "none")
            put("pid", 10); put("startTicks", 20); put("ppid", 30); (0..3).forEach { put("uid$it", 10330) }
            put("role", "supervisor"); put("status", "READY"); put("exitCode", -1); put("childCount", 0); put("echild", false)
            put("setResult", 0); put("getResult", 0); put("getValue", 1); put("prefixDev", 1); put("prefixIno", 2)
            put("socketDev", -1); put("socketIno", -1); put("lockDev", -1); put("lockIno", -1)
        }
        assertEquals(1L, ForeverWineEvent.parse(event.toString(), run, nonce).seq)
        assertThrows(IllegalArgumentException::class.java) { ForeverWineEvent.parse(event.put("extra", 1).toString(), run, nonce) }
        assertThrows(IllegalArgumentException::class.java) { ForeverWineEvent.parse(event.put("startTicks", 1.5).toString(), run, nonce) }
    }

    @Test fun deniedAccessIsNotWritableButUnexpectedErrnoFailsClosed() {
        ForeverWineSupervisorPolicy.requireNotWritableForTesting { throw ErrnoException("access", OsConstants.EACCES) }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverWineSupervisorPolicy.requireNotWritableForTesting { true }
        }
    }

    @Test fun schema2PhaseDoneUsesJustReapedClientAndAllowsUnknown() {
        val run = "WS-" + "a".repeat(32); val nonce = "b".repeat(32)
        val event = JSONObject().apply {
            put("schema", 2); put("runId", run); put("nonce", nonce); put("seq", 7); put("event", "PHASE_DONE"); put("phase", "client")
            put("pid", 42); put("startTicks", 20); put("ppid", 11); (0..3).forEach { put("uid$it", 10330) }
            put("role", "client"); put("status", "UNKNOWN"); put("exitCode", -1); put("childCount", 1); put("echild", false)
            put("setResult", -1); put("getResult", -1); put("getValue", -1); put("prefixDev", 1); put("prefixIno", 2)
            put("socketDev", -1); put("socketIno", -1); put("lockDev", -1); put("lockIno", -1)
        }
        assertEquals("client", ForeverWineEvent.parse(event.toString(), run, nonce).role)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverWineEvent.parse(event.put("role", "supervisor").toString(), run, nonce)
        }
    }
}
