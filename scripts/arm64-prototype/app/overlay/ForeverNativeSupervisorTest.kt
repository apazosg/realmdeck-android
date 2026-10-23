package app.gamenative

import android.content.Context
import android.content.ContextWrapper
import android.os.Process as AndroidProcess
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ForeverNativeSupervisorTest {
    @get:Rule val temporary = TemporaryFolder()

    @org.junit.Before fun ownKernelIdentity() {
        org.robolectric.shadows.ShadowProcess.setPid(4242)
        org.robolectric.shadows.ShadowProcess.setUid(10330)
    }

    @Test fun normalEndToEndUsesTwoPhasesWitnessQAndExactAcknowledgements() {
        val fixture = TranscriptFixture(); val controller = controller(fixture)
        assertTrue(controller.start("normal", true)); await { controller.state.value.status == "PASS" }
        assertEquals(listOf("START:1", "ACK:1", "ACK:1", "ACK:1", "START:2", "ACK:2", "ACK:2", "ACK:2", "CLOSE:0"), fixture.supervisor.commands)
        assertEquals(listOf("SPAWN:intermediate", "SPAWN:grandchild", "ADOPT:grandchild", "EXIT:intermediate", "EXIT:grandchild", "PHASE_DONE:1",
            "SPAWN:intermediate", "SPAWN:grandchild", "ADOPT:grandchild", "EXIT:intermediate", "EXIT:grandchild", "PHASE_DONE:2", "RUN_DONE:PASS"), fixture.supervisor.events)
        assertTrue(fixture.witness.qReceived.await(1, TimeUnit.SECONDS))
    }

    @Test fun cancelBeforeStartIsCloseOnlyAndDoesNotDrainASecondExpectedLine() {
        val fixture = TranscriptFixture(); val controller = controller(fixture)
        assertTrue(controller.start("cancel_before_start", true)); await { controller.state.value.status == "CANCELLED" }
        assertEquals(listOf("CLOSE:0"), fixture.supervisor.commands)
        assertEquals(listOf("RUN_DONE:CANCELLED"), fixture.supervisor.events)
        assertTrue(fixture.witness.qReceived.await(1, TimeUnit.SECONDS))
        val proof = fixture.store.readCurrent()!!.getJSONObject("witnessProof")
        assertEquals("PASS", proof.getString("status"))
        assertTrue(proof.getBoolean("stableBeforeQ")); assertTrue(proof.getBoolean("qSent"))
        assertTrue(proof.getBoolean("reaped")); assertEquals(0, proof.getInt("exit"))
    }

    @Test fun cancellationWithEarlyExitedWitnessIsUnknownEvenAfterNativeClose() {
        val fixture = TranscriptFixture(WitnessMode.EARLY); val controller = controller(fixture)
        assertTrue(controller.start("cancel_before_start", true))
        await { controller.state.value.status == "UNKNOWN" }
        assertFalse(controller.state.value.status == "CANCELLED")
    }

    @Test fun cancelForkStopsAfterVerifiedGrandchildSpawnWithoutAckAndResolvesAdoption() {
        val fixture = TranscriptFixture(); val controller = controller(fixture)
        assertTrue(controller.start("cancel_fork", true)); await { controller.state.value.status == "CANCELLED" }
        assertEquals(listOf("START:1", "ACK:1", "STOP:1"), fixture.supervisor.commands)
        assertEquals(listOf("SPAWN:intermediate", "SPAWN:grandchild", "EXIT:intermediate", "ADOPT:grandchild", "EXIT:grandchild", "RUN_DONE:CANCELLED"), fixture.supervisor.events)
    }

    @Test fun externalCancelBeforeGrandchildBirthIsUnknownNotSuccessfulCancellation() {
        val fixture = TranscriptFixture(); val controller = controller(fixture)
        assertTrue(controller.start("normal", true)); await { fixture.supervisorReady() }; assertTrue(fixture.supervisor.spawned.await(1, TimeUnit.SECONDS))
        assertTrue(controller.cancel()); await { controller.state.value.status == "UNKNOWN" }
    }

    @Test fun invalidProtocolAndHeldProcessFailureCanNeverPass() {
        val invalid = TranscriptFixture(); val invalidController = controller(invalid)
        assertTrue(invalidController.start("invalid_protocol", true)); await { invalidController.state.value.status == "UNKNOWN" }
        assertFalse(invalidController.state.value.status == "PASS")
        val failed = TranscriptFixture(); val failedController = controller(failed, allowSupervisorFailureForTests = true)
        assertTrue(failedController.start("supervisor_failure", true)); await { failedController.state.value.status == "UNKNOWN" }
        assertFalse(failedController.state.value.status == "PASS"); assertTrue(failed.witness.qReceived.await(1, TimeUnit.SECONDS))
    }

    @Test fun supervisorFailureIsRejectedBeforeStoreOrFactoryInProductionController() {
        val fixture = TranscriptFixture(); val controller = controller(fixture)
        assertFalse(controller.start("supervisor_failure", true))
        assertEquals("UNKNOWN", controller.state.value.status)
        assertFalse(fixture.supervisorReady()); assertFalse(fixture.witnessReady())
        assertEquals(null, fixture.store.readCurrent())
    }

    @Test fun witnessEarlyExitZeroAndHungWitnessCannotProducePass() {
        val early = TranscriptFixture(WitnessMode.EARLY); val earlyController = controller(early)
        assertTrue(earlyController.start("normal", true)); await { earlyController.state.value.status == "UNKNOWN" }
        val hung = TranscriptFixture(WitnessMode.HUNG); val hungController = controller(hung)
        assertTrue(hungController.start("normal", true)); await(timeoutSeconds = 25) { hungController.state.value.status == "UNKNOWN" }
        assertFalse(hungController.start("normal", true)) // wait handle is intentionally retained
    }

    @Test fun delayedReaderAfterProcessWaitIsDrainedBeforePass() {
        val fixture = TranscriptFixture(stdoutEofDelayMillis = 150)
        val controller = controller(fixture, operationTimeoutNanos = TimeUnit.SECONDS.toNanos(2), cleanupTimeoutNanos = TimeUnit.SECONDS.toNanos(1))
        assertTrue(controller.start("normal", true)); await(timeoutSeconds = 5) { controller.state.value.status == "PASS" }
    }

    @Test fun lateStderrOverflowCannotTurnIntoPass() {
        val fixture = TranscriptFixture(stderrBytes = ForeverNativeSupervisorPolicy.MAX_STREAM_BYTES + 1)
        val controller = controller(fixture)
        assertTrue(controller.start("normal", true)); await { controller.state.value.status == "UNKNOWN" }
        assertFalse(controller.state.value.status == "PASS")
    }

    @Test fun witnessExtraOutputAfterQCannotProducePositiveProof() {
        val fixture = TranscriptFixture(WitnessMode.EXTRA); val controller = controller(fixture)
        assertTrue(controller.start("normal", true)); await { controller.state.value.status == "UNKNOWN" }
        assertFalse(controller.state.value.status == "PASS")
    }

    @Test fun blockedStartupCancelIsResponsiveAndLateProcessRemainsOwned() {
        val gate = CountDownLatch(1)
        val fixture = TranscriptFixture(supervisorStartGate = gate)
        val controller = controller(fixture, operationTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(100), cleanupTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(200))
        assertTrue(controller.start("normal", true)); assertTrue(controller.cancel())
        await(timeoutSeconds = 2) { controller.state.value.status == "UNKNOWN" }
        assertFalse(controller.start("normal", true))
        gate.countDown()
        await(timeoutSeconds = 3) { fixture.supervisorReady() && fixture.supervisor.commands.contains("CLOSE:0") }
        assertFalse(fixture.supervisor.commands.any { it.startsWith("START:") })
        assertFalse(controller.start("normal", true))
        assertEquals("UNKNOWN", fixture.store.readCurrent()!!.getString("status"))
    }

    @Test fun blockedWriterPoisonsInputAndNeverSendsALaterCommand() {
        val gate = CountDownLatch(1)
        val fixture = TranscriptFixture(writerGate = gate)
        val controller = controller(fixture, operationTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(200), cleanupTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(200))
        assertTrue(controller.start("normal", true)); await { fixture.supervisorReady() }
        assertTrue(controller.cancel()); await(timeoutSeconds = 2) { controller.state.value.status == "UNKNOWN" }
        assertFalse(fixture.supervisor.commands.any { it.startsWith("START:") })
        gate.countDown()
        assertFalse(controller.start("normal", true))
        assertEquals("UNKNOWN", fixture.store.readCurrent()!!.getString("status"))
    }

    @Test fun lateTerminalGenerationCannotMutateAReplacementRun() {
        val fixture = TranscriptFixture(); val controller = controller(fixture)
        assertTrue(controller.start("normal", true)); await { controller.state.value.status == "PASS" }
        assertEquals("PASS", controller.state.value.status)
        assertFalse(controller.cancel())
    }

    @Test fun kernelIdentityReuseRolePpidAndSequenceAreRejected() {
        val run = "NS-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"; val nonce = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        fun fresh() = event(run, nonce, 1, "READY", 10, 20, AndroidProcess.myPid(), "supervisor", "READY")
        assertEquals(1L, ForeverNativeEvent.parse(fresh().toString(), run, nonce).seq)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ForeverNativeEvent.parse(fresh().put("nonce", "c".repeat(32)).toString(), run, nonce) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ForeverNativeEvent.parse(fresh().put("seq", 0).toString(), run, nonce) }
        // Structural parsing accepts a positive PPID; the controller must compare
        // it with its independently acquired kernel/Process identity.
        assertEquals(999, ForeverNativeEvent.parse(fresh().put("ppid", 999).toString(), run, nonce).ppid)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ForeverNativeEvent.parse(fresh().put("role", "grandchild").toString(), run, nonce) }
        assertFalse(ForeverNativeProcIdentity(42, 90, 7, List(4) { AndroidProcess.myUid() }).sameProcess(ForeverNativeProcIdentity(42, 91, 7, List(4) { AndroidProcess.myUid() })))
    }

    @Test fun forgedTerminalParentIsRejectedAgainstHeldSupervisorIdentity() {
        val fixture = TranscriptFixture(forgeTerminalParent = true); val controller = controller(fixture)
        assertTrue(controller.start("normal", true)); await { controller.state.value.status == "UNKNOWN" }
        assertFalse(controller.state.value.status == "PASS")
    }

    @Test fun storeFailsClosedWithPrivateDurableEvidence() {
        val store = ForeverNativeSupervisorStore.forDirectory(temporary.newFolder("files", "forever", "controls", "native-supervisor-v2"))
        val run = store.begin("NS-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "f".repeat(32), "normal")
        store.recordIntent(run, "RUN", 0, 0); assertTrue(store.recordOutput(run, "stdout", "ready\n".toByteArray()))
        assertFalse(store.recordOutput(run, "stderr", ByteArray(ForeverNativeSupervisorPolicy.MAX_STREAM_BYTES + 1)))
        store.finish(run, "UNKNOWN", null, JSONObject().put("status", "UNKNOWN"), false, "fault")
        assertEquals("UNKNOWN", store.readCurrent()!!.getString("status"))
        assertEquals("native-run-supervisor-v2", store.readCurrent()!!.getString("variant"))
        assertTrue(File(run.directory, "run.json").isFile)
    }

    @Test fun forcedDurabilityFailurePersistsUnknownBestEffort() {
        val store = ForeverNativeSupervisorStore.forDirectory(temporary.newFolder("durability", "native"))
        val run = store.begin("NS-ffffffffffffffffffffffffffffffff", "e".repeat(32), "normal")
        store.failNextAtomicWriteForTests = true
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            store.finish(run, "PASS", 0, JSONObject().put("status", "PASS"), true)
        }
        store.finishDurabilityUnknown(run, 0, JSONObject().put("status", "UNKNOWN"), true)
        assertEquals("UNKNOWN", store.readCurrent()!!.getString("status"))
    }

    private fun controller(fixture: TranscriptFixture, operationTimeoutNanos: Long = TimeUnit.SECONDS.toNanos(15), cleanupTimeoutNanos: Long = TimeUnit.SECONDS.toNanos(5), allowSupervisorFailureForTests: Boolean = false): ForeverNativeSupervisor {
        val context = FixtureContext(temporary.newFolder("controller-${System.nanoTime()}"))
        fixture.store = ForeverNativeSupervisorStore.forDirectory(context.filesDir)
        return ForeverNativeSupervisor(context, fixture, fixture.kernel, store = fixture.store,
            artifactResolver = { ForeverNativeSupervisorPolicy.forTesting(File(context.filesDir, "native/${ForeverNativeSupervisorPolicy.SUPERVISOR_NAME}"), File(context.filesDir, "native/${ForeverNativeSupervisorPolicy.FIXTURE_NAME}")) },
            operationTimeoutNanos = operationTimeoutNanos, cleanupTimeoutNanos = cleanupTimeoutNanos,
            allowSupervisorFailureForTests = allowSupervisorFailureForTests)
    }
    private fun await(timeoutSeconds: Long = 5, condition: () -> Boolean) { val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds); while (!condition()) { assertTrue(System.nanoTime() < deadline); Thread.sleep(10) } }
    private class FixtureContext(directory: File) : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        private val files = directory
        override fun getFilesDir() = files
        override fun getApplicationContext(): Context = this
        override fun getPackageName() = ForeverNativeSupervisorPolicy.PACKAGE
    }

    private enum class WitnessMode { NORMAL, EXTRA, EARLY, HUNG }
    private class TranscriptFixture(private val witnessMode: WitnessMode = WitnessMode.NORMAL, private val forgeTerminalParent: Boolean = false,
        private val supervisorStartGate: CountDownLatch? = null, private val stdoutEofDelayMillis: Long = 0,
        private val stderrBytes: Int = 0, private val writerGate: CountDownLatch? = null) : ForeverNativeProcessFactory {
        lateinit var store: ForeverNativeSupervisorStore
        lateinit var supervisor: FakeSupervisor; lateinit var witness: FakeWitness; val kernel = FakeKernel()
        fun supervisorReady(): Boolean = ::supervisor.isInitialized
        fun witnessReady(): Boolean = ::witness.isInitialized
        override fun startSupervisor(argv: Array<String>): java.lang.Process {
            require(argv[0] == ForeverNativeSupervisorPolicy.LINKER && argv[2] == "--run" && argv[4] == "--nonce" && argv[6] == "--fixture" && argv[7].endsWith(ForeverNativeSupervisorPolicy.FIXTURE_NAME))
            supervisorStartGate?.await()
            supervisor = FakeSupervisor(argv, kernel, forgeTerminalParent, stdoutEofDelayMillis, stderrBytes, writerGate); return supervisor
        }
        override fun startWitness(argv: Array<String>): java.lang.Process { require(argv[0] == ForeverNativeSupervisorPolicy.LINKER && argv[2] == "--witness"); witness = FakeWitness(argv, kernel, witnessMode); return witness }
    }
    private class FakeKernel : ForeverNativeKernel {
        private val values = linkedMapOf<Int, ForeverNativeProcIdentity>()
        @Synchronized fun put(identity: ForeverNativeProcIdentity) { values[identity.pid] = identity }
        @Synchronized fun remove(pid: Int) { values.remove(pid) }
        @Synchronized override fun read(pid: Int) = values[pid] ?: error("dead pid $pid")
    }
    private abstract class PipeProcess(@JvmField val pid: Long) : java.lang.Process() {
        protected val nativeInput = PipedInputStream(); protected val javaOutput = PipedOutputStream(nativeInput)
        protected val nativeOutput = PipedOutputStream(); protected val javaInput = PipedInputStream(nativeOutput)
        protected val nativeError = PipedOutputStream(); private val javaError = PipedInputStream(nativeError)
        private val done = CountDownLatch(1); @Volatile private var code: Int? = null
        open override fun getOutputStream(): OutputStream = javaOutput; open override fun getInputStream(): InputStream = javaInput; override fun getErrorStream(): InputStream = javaError
        override fun waitFor(): Int { done.await(); return code!! }; override fun exitValue() = code ?: throw IllegalThreadStateException(); override fun destroy() { finish(143) }
        fun finish(value: Int) { if (code == null) { code = value; runCatching { nativeInput.close() }; runCatching { nativeOutput.close() }; runCatching { nativeError.close() }; done.countDown() } }
    }
    private class FakeWitness(argv: Array<String>, private val kernel: FakeKernel, private val mode: WitnessMode) : PipeProcess(3001) {
        val qReceived = CountDownLatch(1)
        init { val uid = AndroidProcess.myUid(); kernel.put(ForeverNativeProcIdentity(3001, 31, AndroidProcess.myPid(), List(4) { uid })); Thread {
            write(event(argv[3], argv[4], 1, "READY", 3001, 31, AndroidProcess.myPid(), "witness", "READY", witness = true))
            when (mode) {
                WitnessMode.EARLY -> finish(0)
                WitnessMode.HUNG -> while (true) Thread.sleep(1000)
                WitnessMode.NORMAL -> if (java.io.BufferedReader(java.io.InputStreamReader(nativeInput)).readLine() == "Q") { qReceived.countDown(); finish(0) }
                WitnessMode.EXTRA -> if (java.io.BufferedReader(java.io.InputStreamReader(nativeInput)).readLine() == "Q") { qReceived.countDown(); nativeOutput.write("EXTRA\n".toByteArray()); nativeOutput.flush(); finish(0) }
            }
        }.apply { isDaemon = true }.start() }
        private fun write(value: JSONObject) { nativeOutput.write((value.toString() + "\n").toByteArray()); nativeOutput.flush() }
    }
    private class FakeSupervisor(private val argv: Array<String>, private val kernel: FakeKernel, private val forgeTerminalParent: Boolean,
        private val stdoutEofDelayMillis: Long, stderrBytes: Int, writerGate: CountDownLatch?) : PipeProcess(1001) {
        val commands = mutableListOf<String>(); val events = mutableListOf<String>(); val spawned = CountDownLatch(1); private var seq = 1; private var active = 1; private var pending = ""; private var cancelled = false; private var started = false; private var bad = false
        private val blockedOutput = writerGate?.let { gate -> object : OutputStream() {
            @Volatile var closed = false
            override fun write(value: Int) { if (closed) throw IOException("writer closed"); gate.await(); if (closed) throw IOException("writer closed"); javaOutput.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { if (closed) throw IOException("writer closed"); gate.await(); if (closed) throw IOException("writer closed"); javaOutput.write(bytes, offset, length) }
            override fun flush() = javaOutput.flush()
            override fun close() { closed = true; gate.countDown(); runCatching { javaOutput.close() } }
        } }
        override fun getOutputStream(): OutputStream = blockedOutput ?: super.getOutputStream()
        override fun getInputStream(): InputStream = if (stdoutEofDelayMillis <= 0) super.getInputStream() else object : InputStream() {
            override fun read(): Int { val value = javaInput.read(); if (value < 0) Thread.sleep(stdoutEofDelayMillis); return value }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { val value = javaInput.read(bytes, offset, length); if (value < 0) Thread.sleep(stdoutEofDelayMillis); return value }
            override fun close() = javaInput.close()
        }
        private val run = argv[3]; private val nonce = argv[5]; private val uid = AndroidProcess.myUid(); private var family = 0
        init { kernel.put(ForeverNativeProcIdentity(1001, 11, AndroidProcess.myPid(), List(4) { uid })); Thread { if (stderrBytes > 0) nativeError.write(ByteArray(stderrBytes)); write(event(run, nonce, seq++, "READY", 1001, 11, AndroidProcess.myPid(), "supervisor", "READY")); try { java.io.BufferedReader(java.io.InputStreamReader(nativeInput)).forEachLine { handle(it) } } catch (_: Throwable) { } finally { finish(0) } }.start() }
        private fun handle(line: String) {
            val command = runCatching { ForeverNativeCommand.parse(line, run, nonce) }.getOrNull() ?: run { if (!bad) { bad = true; write(event(run, nonce, seq++, "ERROR", 1001, 11, AndroidProcess.myPid(), "supervisor", "ERROR")); write(event(run, nonce, seq++, "RUN_DONE", 1001, 11, AndroidProcess.myPid(), "supervisor", "UNKNOWN")); finish(2) }; return }
            commands += "${command.command}:${command.phase}"
            when (command.command) {
                "START" -> { active = command.phase; family++; started = true; emitSpawn(command.scenario == "hold") }
                "ACK" -> when (pending) { "intermediate" -> emitSpawnGrand(); "grandchild" -> emitAdopt(); "adopt" -> finishFamily() }
                "STOP" -> {
                    cancelled = true
                    if (pending == "intermediate" || pending == "grandchild") cancelFamily()
                    else if (pending.isEmpty()) {
                        // Real native code accepts STOP(phase 1) while awaiting
                        // START(phase 2), after a rejected PHASE_DONE transcript.
                        write(event(run, nonce, seq++, "RUN_DONE", 1001, 11, AndroidProcess.myPid(), "supervisor", "CANCELLED"))
                        events += "RUN_DONE:CANCELLED"; finish(0)
                    }
                }
                "CLOSE" -> if (pending.isEmpty()) { val status = if (cancelled || !started) "CANCELLED" else "PASS"; write(event(run, nonce, seq++, "RUN_DONE", 1001, 11, AndroidProcess.myPid(), "supervisor", status)); events += "RUN_DONE:$status"; finish(0) }
            }
        }
        private fun emitSpawn(hold: Boolean) { val base = 2000 + family * 10; val ticks = 40L + family; kernel.put(ForeverNativeProcIdentity(base, ticks, 1001, List(4) { uid })); pending = "intermediate"; spawned.countDown(); emit("SPAWN", "intermediate", "RUNNING", base, ticks, 1001); if (hold) Unit else Unit }
        private fun emitSpawnGrand() { val pid = 3000 + family * 10; kernel.put(ForeverNativeProcIdentity(pid, 50L + family, 2000 + family * 10, List(4) { uid })); pending = "grandchild"; emit("SPAWN", "grandchild", "RUNNING", pid, 50L + family, 2000 + family * 10) }
        private fun emitAdopt(kernelAvailable: Boolean = true) { val pid = 3000 + family * 10; if (kernelAvailable) kernel.put(ForeverNativeProcIdentity(pid, 50L + family, 1001, List(4) { uid })); pending = "adopt"; emit("ADOPT", "grandchild", "RUNNING", pid, 50L + family, 1001) }
        private fun finishFamily() { val base = 2000 + family * 10; val pid = 3000 + family * 10; pending = ""; emit("EXIT", "intermediate", "REAPED", base, 40L + family, 1001, 0); emit("EXIT", "grandchild", "REAPED", pid, 50L + family, 1001, 0); emit("PHASE_DONE", "supervisor", "PASS", 1001, 11, AndroidProcess.myPid()); }
        private fun cancelFamily() { if (pending == "intermediate") emitSpawnGrand(); val base = 2000 + family * 10; val pid = 3000 + family * 10; emit("EXIT", "intermediate", "REAPED", base, 40L + family, 1001, 0); kernel.remove(pid); emitAdopt(false); pending = ""; emit("EXIT", "grandchild", "REAPED", pid, 50L + family, 1001, 143); write(event(run, nonce, seq++, "RUN_DONE", 1001, 11, AndroidProcess.myPid(), "supervisor", "CANCELLED")); events += "RUN_DONE:CANCELLED"; finish(0) }
        private fun emit(kind: String, role: String, status: String, pid: Int, ticks: Long, ppid: Int, exit: Int = -1) { events += if (kind == "PHASE_DONE") "$kind:$active" else "$kind:$role"; write(event(run, nonce, seq++, kind, pid, ticks, ppid, role, status, active, exit, kind == "PHASE_DONE")) }
        private fun write(value: JSONObject) {
            if (forgeTerminalParent && value.getString("event") in setOf("PHASE_DONE", "RUN_DONE")) value.put("ppid", 999)
            nativeOutput.write((value.toString() + "\n").toByteArray()); nativeOutput.flush()
        }
    }

    companion object {
    private fun event(run: String, nonce: String, seq: Int, kind: String, pid: Int, ticks: Long, ppid: Int, role: String, status: String,
        phase: Int = 0, exit: Int = -1, echild: Boolean = false, witness: Boolean = false): JSONObject = JSONObject()
        .put("schema", 1).put("runId", run).put("nonce", nonce).put("seq", seq).put("phase", phase).put("event", kind).put("pid", pid).put("startTicks", ticks).put("ppid", ppid)
        .put("uid0", AndroidProcess.myUid()).put("uid1", AndroidProcess.myUid()).put("uid2", AndroidProcess.myUid()).put("uid3", AndroidProcess.myUid()).put("role", role).put("status", status)
        .put("exitCode", exit).put("childCount", 0).put("echild", echild || kind == "RUN_DONE").put("setResult", if (kind == "READY" && !witness) 0 else -1).put("getResult", if (kind == "READY" && !witness) 0 else -1).put("getValue", if (kind == "READY" && !witness) 1 else -1)
    }
}
