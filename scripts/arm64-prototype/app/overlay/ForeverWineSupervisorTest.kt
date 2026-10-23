package app.gamenative

import android.content.Context
import android.content.ContextWrapper
import android.os.Process as AndroidProcess
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.rules.TemporaryFolder

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29], application = android.app.Application::class)
class ForeverWineSupervisorTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun appIdentity() {
        org.robolectric.shadows.ShadowProcess.setPid(31337)
        org.robolectric.shadows.ShadowProcess.setUid(10330)
    }
    @Test fun normalCancelAndErrorHaveSeparateClosureAndQualificationStates() {
        assertTrue(ForeverWineSupervisorState(status = "PASS", closureObserved = true, qualification = true).qualification)
        assertTrue(ForeverWineSupervisorState(status = "CANCELLED", closureObserved = true, qualification = true).closureObserved)
        assertFalse(ForeverWineSupervisorState(status = "UNKNOWN", closureObserved = true, qualification = false).qualification)
    }

    @Test fun nonceOverflowSpawnReuseAndForeignUidAreRejected() {
        val run = "WS-" + "a".repeat(32); val nonce = "b".repeat(32)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverWineCommand.create(Long.MAX_VALUE, run, "x".repeat(33), ForeverWineCommandKind.START, ForeverWinePhase.SERVER)
        }
        val event = JSONObject().apply {
            put("schema", 2); put("runId", run); put("nonce", nonce); put("seq", 1); put("event", "READY"); put("phase", "none")
            put("pid", 10); put("startTicks", 20); put("ppid", 30); (0..3).forEach { put("uid$it", 10330) }
            put("role", "supervisor"); put("status", "READY"); put("exitCode", -1); put("childCount", 0); put("echild", false)
            put("setResult", 0); put("getResult", 0); put("getValue", 1); put("prefixDev", 1); put("prefixIno", 2)
            put("socketDev", -1); put("socketIno", -1); put("lockDev", -1); put("lockIno", -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverWineEvent.parse(JSONObject(event.toString()).put("nonce", "c".repeat(32)).toString(), run, nonce)
        }
        assertEquals("READY", ForeverWineEvent.parse(event.toString(), run, nonce).event)
    }

    @Test fun unexpectedServerExitAndUnknownCleanupCannotBecomePositive() {
        val state = ForeverWineSupervisorState(status = "UNKNOWN", phase = "server", closureObserved = true, qualification = false)
        assertFalse(state.qualification)
        assertEquals("server", state.phase)
    }

    @Test fun phaseDonePassMustBeClientCandidateNotSupervisorCandidate() {
        val run = "WS-" + "a".repeat(32); val nonce = "b".repeat(32)
        val event = JSONObject().apply {
            put("schema", 2); put("runId", run); put("nonce", nonce); put("seq", 1); put("event", "PHASE_DONE"); put("phase", "client")
            put("pid", 10); put("startTicks", 20); put("ppid", 30); (0..3).forEach { put("uid$it", 10330) }
            put("role", "client"); put("status", "PASS"); put("exitCode", -1); put("childCount", 1); put("echild", false)
            put("setResult", -1); put("getResult", -1); put("getValue", -1); put("prefixDev", 1); put("prefixIno", 2)
            put("socketDev", -1); put("socketIno", -1); put("lockDev", -1); put("lockIno", -1)
        }
        assertEquals("client", ForeverWineEvent.parse(event.toString(), run, nonce).role)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverWineEvent.parse(JSONObject(event.toString()).put("role", "supervisor").toString(), run, nonce)
        }
    }

    @Test fun unknownPhaseDoneIsStickyAtStateBoundary() {
        val state = ForeverWineSupervisorState(status = "UNKNOWN", phase = "client", closureObserved = true, qualification = false)
        assertFalse(state.qualification)
        assertEquals("UNKNOWN", state.status)
    }

    @Test fun normalSchema2RunUsesClientPhaseDoneAndStrictNativeTranscript() {
        val files = temporary.newFolder("files")
        val store = ForeverRunStoreRegistry.forDirectory(files)
        val factory = ScriptedWineFactory()
        factory.requirePreparedValidation = true
        val executor = Executors.newCachedThreadPool()
        val context = WineTestContext(org.robolectric.RuntimeEnvironment.getApplication(), files)
        val controller = ForeverWineSupervisor(
            context, factory, FixtureKernel(fixtureIdentities()), ForeverWineRealClock, store,
            { ForeverWineSupervisorPolicy.forTesting(files, files) },
            { _, ws, nonce, publish ->
                check(publish(listOf("/system/bin/linker64", "/test/publisher", "--run", ws), Long.MAX_VALUE) == 0)
                ForeverWinePrefix.Prepared(ws, nonce, File(files, "prefix"), ForeverWineSupervisorPolicy.runtimeRoot(context), 11, 22, File(files, "receipt"))
            }, executor, preparedValidator = { _, _ -> factory.preparedValidated = true },
        )
        factory.supervisorScript = ::normalSupervisorTranscript
        factory.witnessScript = ::witnessReadyTranscript
        factory.publisherOutput = "{\"status\":\"published\",\"prefixDev\":11,\"prefixIno\":22}\n"
        check(controller.start(true))
        awaitTerminal(controller)
        assertEquals(controller.state.value.toString(), "PASS", controller.state.value.status)
        assertTrue(controller.state.value.qualification)
        val binding = store.readWineBinding(store.readCurrent()!!.runId)!!
        val commands = File(binding.outputDirectory, "wine-native-stdin.jsonl").readLines()
            .map { JSONObject(it).getString("command") }
        assertEquals(listOf("START", "START", "CLOSE"), commands)
        assertFalse(factory.supervisor!!.destroyed)
        assertTrue(File(binding.outputDirectory, "wine-publisher-stdout.jsonl").readText().contains("published"))
        executor.shutdownNow()
    }

    @Test fun nonzeroClientExitAndNativeUnknownTranscriptCannotQualify() {
        val run = "WS-" + "a".repeat(32); val nonce = "b".repeat(32)
        val event = schema2Event(run, nonce, 7, "PHASE_DONE", "client", "UNKNOWN", 5002, 52, 4242, 1, false, -1)
        assertEquals("UNKNOWN", ForeverWineEvent.parse(event.toString(), run, nonce).status)
        val exit = schema2Event(run, nonce, 6, "EXIT", "client", "REAPED", 5002, 52, 4242, 1, false, 7)
        assertEquals(7L, ForeverWineEvent.parse(exit.toString(), run, nonce).exitCode)
        assertFalse(ForeverWineSupervisorState(status = "UNKNOWN", qualification = false).qualification)
    }

    @Test fun malformedNonceSequencePrefixAndForgedCloseCannotBecomeAChildProof() {
        val run = "WS-" + "a".repeat(32); val nonce = "b".repeat(32)
        val ready = schema2Event(run, nonce, 1, "READY", "supervisor", "READY", 4242, 10, AndroidProcess.myPid(), 0, false, -1)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverWineEvent.parse(JSONObject(ready.toString()).put("nonce", "c".repeat(32)).toString(), run, nonce)
        }
        // Structurally valid kernel facts are cross-bound by the controller,
        // not by a parser with no prepared-prefix identity argument.
        assertEquals(99L, ForeverWineEvent.parse(JSONObject(ready.toString()).put("prefixDev", 99).toString(), run, nonce).prefixDev)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverWineEvent.parse(JSONObject(ready.toString()).put("role", "supervisor").put("event", "PHASE_DONE").put("phase", "client").toString(), run, nonce)
        }
    }

    @Test fun cancelPoisonsAWriterWithoutWaitingForCommandLock() {
        val files = temporary.newFolder("cancel-writer")
        val store = ForeverRunStoreRegistry.forDirectory(files)
        val factory = ScriptedWineFactory().also { it.blockSupervisorWrites = true }
        factory.requirePreparedValidation = true
        val executor = Executors.newCachedThreadPool()
        val context = WineTestContext(org.robolectric.RuntimeEnvironment.getApplication(), files)
        val controller = ForeverWineSupervisor(
            context, factory, FixtureKernel(fixtureIdentities()), ForeverWineRealClock, store,
            { ForeverWineSupervisorPolicy.forTesting(files, files) },
            { _, ws, nonce, publish ->
                check(publish(listOf("/system/bin/linker64", "/test/publisher", "--run", ws), Long.MAX_VALUE) == 0)
                ForeverWinePrefix.Prepared(ws, nonce, File(files, "prefix"), ForeverWineSupervisorPolicy.runtimeRoot(context), 11, 22, File(files, "receipt"))
            }, executor, ForeverWineTimeouts(prefixSeconds = 1, nativeSeconds = 1, cleanupSeconds = 1, watchdogSeconds = 1, totalSeconds = 3),
            preparedValidator = { _, _ -> factory.preparedValidated = true },
        )
        factory.supervisorScript = ::normalSupervisorTranscript
        factory.witnessScript = ::witnessReadyTranscript
        check(controller.start(true))
        var entered = false
        val writeDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
        while (!entered && System.nanoTime() < writeDeadline) {
            val process = factory.supervisor
            entered = process?.writeEntered?.await(5, java.util.concurrent.TimeUnit.MILLISECONDS) == true
            if (process == null) Thread.sleep(5)
        }
        assertTrue(controller.state.value.toString(), entered)
        val started = System.nanoTime()
        assertTrue(controller.cancel())
        assertTrue(System.nanoTime() - started < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250))
        assertTrue(controller.awaitCompletionForTesting(2_000))
        assertFalse(controller.state.value.status == "PASS")
        executor.shutdownNow()
    }

    @Test fun publisherStreamOverflowAndCombinedIpcOverflowStayUnknown() {
        fun runWith(factory: ScriptedWineFactory): ForeverWineSupervisor {
            val files = temporary.newFolder("output-${System.nanoTime()}")
            val store = ForeverRunStoreRegistry.forDirectory(files)
            val executor = Executors.newCachedThreadPool()
            val context = WineTestContext(org.robolectric.RuntimeEnvironment.getApplication(), files)
            val controller = ForeverWineSupervisor(
                context, factory, FixtureKernel(fixtureIdentities()), ForeverWineRealClock, store,
                { ForeverWineSupervisorPolicy.forTesting(files, files) },
                { _, ws, nonce, publish ->
                    check(publish(listOf("/system/bin/linker64", "/test/publisher", "--run", ws), Long.MAX_VALUE) == 0)
                    ForeverWinePrefix.Prepared(ws, nonce, File(files, "prefix"), ForeverWineSupervisorPolicy.runtimeRoot(context), 11, 22, File(files, "receipt"))
                }, executor, ForeverWineTimeouts(prefixSeconds = 1, nativeSeconds = 1, cleanupSeconds = 1, watchdogSeconds = 1, totalSeconds = 3),
                preparedValidator = { _, _ -> factory.preparedValidated = true },
            )
            factory.requirePreparedValidation = true
            factory.supervisorScript = ::normalSupervisorTranscript
            factory.witnessScript = ::witnessReadyTranscript
            controller.start(true)
            awaitTerminal(controller)
            executor.shutdownNow()
            return controller
        }
        val perStream = ScriptedWineFactory().also { it.publisherOutput = "x".repeat(ForeverWineSupervisorPolicy.MAX_STREAM_BYTES + 1) }
        assertEquals("UNKNOWN", runWith(perStream).state.value.status)
        val combined = ScriptedWineFactory().also {
            it.publisherOutput = "x".repeat(ForeverWineSupervisorPolicy.MAX_STREAM_BYTES)
            it.publisherErrorOutput = "x".repeat(ForeverWineSupervisorPolicy.MAX_STREAM_BYTES)
            it.supervisorErrorOutput = "x".repeat(ForeverWineSupervisorPolicy.MAX_STREAM_BYTES)
            it.witnessErrorOutput = "x".repeat(ForeverWineSupervisorPolicy.MAX_STREAM_BYTES)
        }
        assertEquals("UNKNOWN", runWith(combined).state.value.status)
    }

    private fun awaitTerminal(controller: ForeverWineSupervisor) {
        repeat(2_500) {
            if (controller.state.value.status in setOf("PASS", "CANCELLED", "UNKNOWN")) return
            Thread.sleep(2)
        }
        throw AssertionError("controller did not reach a terminal UI state: ${controller.state.value}")
    }

    private fun fixtureIdentities(): Map<Int, ForeverNativeProcIdentity> {
        val uid = AndroidProcess.myUid(); val parent = AndroidProcess.myPid()
        return mapOf(
            4242 to ForeverNativeProcIdentity(4242, 10, parent, List(4) { uid }),
            5001 to ForeverNativeProcIdentity(5001, 51, 4242, List(4) { uid }),
            5002 to ForeverNativeProcIdentity(5002, 52, 4242, List(4) { uid }),
            6001 to ForeverNativeProcIdentity(6001, 61, parent, List(4) { uid }),
            7001 to ForeverNativeProcIdentity(7001, 71, parent, List(4) { uid }),
        )
    }

    private fun normalSupervisorTranscript(run: String, nonce: String): String = listOf(
        schema2Event(run, nonce, 1, "READY", "supervisor", "READY", 4242, 10, AndroidProcess.myPid(), 0, false, -1),
        schema2Event(run, nonce, 2, "SPAWN", "server", "RUNNING", 5001, 51, 4242, 1, false, -1),
        schema2Event(run, nonce, 3, "SERVER_READY", "server", "RUNNING", 5001, 51, 4242, 1, false, -1, true),
        schema2Event(run, nonce, 4, "SPAWN", "client", "RUNNING", 5002, 52, 4242, 2, false, -1),
        schema2Event(run, nonce, 5, "EXIT", "client", "REAPED", 5002, 52, 4242, 1, false, 0),
        schema2Event(run, nonce, 6, "PHASE_DONE", "client", "PASS", 5002, 52, 4242, 1, false, -1),
        schema2Event(run, nonce, 7, "EXIT", "server", "REAPED", 5001, 51, 4242, 0, false, 0),
        schema2Event(run, nonce, 8, "RUN_DONE", "supervisor", "PASS", 4242, 10, AndroidProcess.myPid(), 0, true, -1),
    ).joinToString("") { "$it\n" }

    private fun witnessReadyTranscript(run: String, nonce: String): String = JSONObject().apply {
        put("schema", 1); put("runId", run); put("nonce", nonce); put("seq", 1); put("phase", 0); put("event", "READY")
        put("pid", 6001); put("startTicks", 61); put("ppid", AndroidProcess.myPid()); (0..3).forEach { put("uid$it", AndroidProcess.myUid()) }
        put("role", "witness"); put("status", "READY"); put("exitCode", -1); put("childCount", 0); put("echild", false)
        put("setResult", -1); put("getResult", -1); put("getValue", -1)
        put("prctl", -1)
    }.toString() + "\n"

    private fun schema2Event(
        run: String, nonce: String, seq: Int, event: String, role: String, status: String,
        pid: Int, ticks: Long, ppid: Int, childCount: Int, echild: Boolean, exit: Int,
        namespace: Boolean = false,
    ): JSONObject = JSONObject().apply {
        put("schema", 2); put("runId", run); put("nonce", nonce); put("seq", seq); put("event", event)
        put("phase", when (event) { "READY", "RUN_DONE", "ERROR" -> "none"; "SERVER_READY", "SPAWN", "EXIT" -> if (role == "client") "client" else "server"; else -> "client" })
        put("pid", pid); put("startTicks", ticks); put("ppid", ppid); (0..3).forEach { put("uid$it", AndroidProcess.myUid()) }
        put("role", role); put("status", status); put("exitCode", if (event == "EXIT") exit else -1); put("childCount", childCount); put("echild", echild)
        put("setResult", if (event == "READY") 0 else -1); put("getResult", if (event == "READY") 0 else -1); put("getValue", if (event == "READY") 1 else -1)
        put("prefixDev", 11); put("prefixIno", 22)
        put("socketDev", if (namespace) 31 else -1); put("socketIno", if (namespace) 32 else -1)
        put("lockDev", if (namespace) 33 else -1); put("lockIno", if (namespace) 34 else -1)
    }

    private class WineTestContext(base: Context, private val files: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = ForeverWineSupervisorPolicy.PACKAGE
        override fun getFilesDir(): File = files
    }

    private class FixtureKernel(private val identities: Map<Int, ForeverNativeProcIdentity>) : ForeverWineKernel {
        override fun read(pid: Int): ForeverNativeProcIdentity = requireNotNull(identities[pid])
    }

    private class ScriptedWineFactory : ForeverWineProcessFactory {
        var supervisorScript: (String, String) -> String = { _, _ -> "" }
        var witnessScript: (String, String) -> String = { _, _ -> "" }
        var supervisor: ScriptedProcess? = null
        var publisherOutput: String = ""
        var publisherErrorOutput: String = ""
        var supervisorErrorOutput: String = ""
        var witnessErrorOutput: String = ""
        var blockSupervisorWrites = false
        var preparedValidated = false
        var requirePreparedValidation = false

        override fun startSupervisor(argv: Array<String>): java.lang.Process {
            if (requirePreparedValidation) check(preparedValidated)
            val process = ScriptedProcess(4242, supervisorScript(argv[3], argv[5]), false, blockSupervisorWrites, supervisorErrorOutput, holdUntilClose = true)
            supervisor = process
            return process
        }

        override fun startPublisher(argv: Array<String>): java.lang.Process = ScriptedProcess(7001, publisherOutput, false, false, publisherErrorOutput)
        override fun startWitness(argv: Array<String>): java.lang.Process = ScriptedProcess(6001, witnessScript(argv[3], argv[4]), true, false, witnessErrorOutput)
    }

    private class ScriptedProcess(
        @Suppress("unused") private val pid: Int,
        output: String,
        private val holdUntilQ: Boolean = false,
        blockWrites: Boolean = false,
        error: String = "",
        holdUntilClose: Boolean = false,
    ) : java.lang.Process() {
        var destroyed = false
        val writeEntered = CountDownLatch(if (blockWrites) 1 else 0)
        private val stdin = ByteArrayOutputStream()
        private val exit = CountDownLatch(if (holdUntilQ || holdUntilClose) 1 else 0)
        private val stdout = EndGatedInputStream(output.toByteArray(), exit)
        private val stderr = EndGatedInputStream(error.toByteArray(), exit)
        private val writeRelease = CountDownLatch(if (blockWrites) 1 else 0)
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) {
                if (writeRelease.count > 0) { writeEntered.countDown(); writeRelease.await() }
                stdin.write(value); if (value == 'Q'.code) exit.countDown()
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (writeRelease.count > 0) { writeEntered.countDown(); writeRelease.await() }
                stdin.write(bytes, offset, length); if ('Q'.code.toByte() in bytes.copyOfRange(offset, offset + length)) exit.countDown()
            }
            override fun flush() = Unit
            override fun close() { writeRelease.countDown(); exit.countDown() }
        }
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int { exit.await(); return 0 }
        override fun exitValue(): Int { check(exit.count == 0L) { "process is still alive" }; return 0 }
        override fun destroy() { destroyed = true; exit.countDown() }
        override fun destroyForcibly(): java.lang.Process = apply { destroyed = true; exit.countDown() }
        override fun isAlive(): Boolean = exit.count > 0
    }

    private class EndGatedInputStream(bytes: ByteArray, private val end: CountDownLatch) : InputStream() {
        private val input = ByteArrayInputStream(bytes)
        private val closed = CountDownLatch(1)

        private fun awaitEnd() {
            while (closed.count > 0 && end.count > 0) {
                if (closed.await(10, java.util.concurrent.TimeUnit.MILLISECONDS)) return
            }
        }

        override fun read(): Int {
            val value = input.read()
            if (value >= 0) return value
            awaitEnd()
            return -1
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            val value = input.read(bytes, offset, length)
            if (value >= 0) return value
            awaitEnd()
            return -1
        }

        override fun close() = closed.countDown()
    }
}
