package app.gamenative

import com.winlator.core.envvars.EnvVars
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.concurrent.CountDownLatch
import org.junit.Assert.*
import org.junit.Test

class ForeverRecoveryTest {
    @Test fun unreadableOrMalformedProcIsNeverGone() {
        assertEquals(ForeverProcState.UNKNOWN, ForeverProcIdentityPolicy.state(10, 30) { throw IOException("denied") })
        assertEquals(ForeverProcState.UNKNOWN, ForeverProcIdentityPolicy.state(10, 30) { error("malformed") })
        assertEquals(ForeverProcState.GONE, ForeverProcIdentityPolicy.state(10, 30) { throw NoSuchFileException("stat") })
        assertEquals(ForeverProcState.GONE, ForeverProcIdentityPolicy.state(10, 30) { ForeverProcStat(10, 1, 40) })
    }

    @Test fun rootProofRejectsAnotherRunAndPidReuse() {
        val root = ForeverProcStat(10, 1, 30)
        val processes = mapOf(10 to root, 11 to ForeverProcStat(11, 10, 32),
            20 to ForeverProcStat(20, 1, 33), 21 to ForeverProcStat(21, 20, 34))
        val read: (Int) -> ForeverProcStat = { processes.getValue(it) }
        assertTrue(ForeverProcIdentityPolicy.belongsTo(root, 11, 32, read))
        assertFalse(ForeverProcIdentityPolicy.belongsTo(root, 21, 34, read))
        assertFalse(ForeverProcIdentityPolicy.belongsTo(root, 11, 99, read))
        assertFalse(ForeverProcIdentityPolicy.belongsTo(root.copy(ticks = 99), 11, 32, read))
        assertFalse(ForeverProcIdentityPolicy.belongsTo(root, 11, 32) { throw IOException("unreadable") })
    }

    @Test fun crashBetweenExecAndPidJournalRemainsBlockedUntilReboot() {
        val initial = ForeverRunMachine.begin("run-a", null)
        assertTrue(ForeverProcIdentityPolicy.recoveredQuiescent(initial, "boot-a"))
        val bootA = "11111111-1111-1111-1111-111111111111"
        val bootB = "22222222-2222-2222-2222-222222222222"
        val spawning = initial.copy(launchAttempted = true, bootId = bootA, terminationUnknown = true)
        val recovered = ForeverRunState.fromJson(spawning.toJson())
        assertFalse(ForeverProcIdentityPolicy.recoveredQuiescent(recovered, bootA))
        assertFalse(ForeverProcIdentityPolicy.recoveredQuiescent(recovered, null))
        assertTrue(ForeverProcIdentityPolicy.recoveredQuiescent(recovered, bootB))
        assertTrue(ForeverProcIdentityPolicy.recoveredQuiescent(recovered.copy(quiescenceConfirmed = true), bootA))
        for (status in listOf(ForeverRunStatus.EXITED, ForeverRunStatus.CANCELLED, ForeverRunStatus.FAILED)) {
            val terminal = recovered.copy(status = status)
            assertFalse(ForeverProcIdentityPolicy.recoveredQuiescent(terminal, bootA))
            assertTrue(ForeverProcIdentityPolicy.recoveredQuiescent(terminal, bootB))
        }
    }

    @Test fun freshControllerNeverLeavesAnActiveRunAfterLostSupervision() {
        val directory = Files.createTempDirectory("forever-recovery").toFile()
        try {
            val store = ForeverRunStoreRegistry.forDirectory(directory)
            val active = store.begin()
            store.update(active.runId) {
                it.copy(
                    stage = ForeverStage.RUNNING,
                    launchAttempted = true,
                    spawnAt = 1_000L,
                    rootPid = 18_319,
                    rootStartTicks = 101_969_434L,
                    bootId = "same-boot",
                    terminationUnknown = true,
                )
            }

            val recovered = store.recoverActiveRun { false }!!
            assertEquals(ForeverRunStatus.INTERRUPTED, recovered.status)
            assertEquals(ForeverStage.RUNNING, recovered.stage)
            assertTrue(recovered.terminationUnknown)
            assertEquals("supervision_lost_reboot_required", recovered.message)
            assertEquals(recovered.runId, store.readCurrent()!!.runId)
            assertEquals(ForeverRunStatus.INTERRUPTED, store.readCurrent()!!.status)
            assertFalse(ForeverProcIdentityPolicy.recoveredQuiescent(recovered, "same-boot"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun recoveryIsTerminalAndDoesNotClaimResumeOnSecondRead() {
        val directory = Files.createTempDirectory("forever-recovery-terminal").toFile()
        try {
            val store = ForeverRunStoreRegistry.forDirectory(directory)
            val active = store.begin()
            val first = store.recoverActiveRun { false }!!
            val second = store.recoverActiveRun { error("must not inspect a terminal run") }!!
            assertEquals(first, second)
            assertEquals(active.runId, second.runId)
            assertEquals(ForeverRunStatus.INTERRUPTED, second.status)
        } finally { directory.deleteRecursively() }
    }

    @Test fun storeIsSharedAndConcurrentUpdatesCannotResurrectTerminalRuns() {
        val directory = Files.createTempDirectory("forever-store").toFile()
        try {
            val first = ForeverRunStoreRegistry.forDirectory(directory)
            val second = ForeverRunStoreRegistry.forDirectory(directory)
            assertSame(first, second)
            val id = first.begin().runId
            val gate = CountDownLatch(1)
            val workers = (0..3).map { number -> Thread {
                gate.await()
                repeat(20) { second.update(id) { it.copy(events = it.events + "worker-$number") } }
            }.apply { start() } }
            gate.countDown()
            workers.forEach { it.join() }
            assertEquals(80, first.read(id)!!.events.size)
            first.finish(id, ForeverRunStatus.EXITED)
            assertThrows(IllegalArgumentException::class.java) { second.updatePatchState(id, "late") }
            val next = first.begin()
            assertThrows(IllegalArgumentException::class.java) { second.finish(id, ForeverRunStatus.FAILED) }
            second.recordQuiescence(id, true)
            assertEquals(next.runId, first.readCurrent()!!.runId)
            assertEquals(ForeverRunStatus.EXITED, first.read(id)!!.status)
        } finally { directory.deleteRecursively() }
    }

    @Test fun childForkedAndReparentedBetweenMonitorTicksPreventsTerminationClaim() {
        // Root is gone and all SAMPLED children are gone. An unsampled child
        // can still exist: absence of kernel containment must veto the claim.
        assertFalse(ForeverPatchPolicy.treeQuiescent(trackedGone = true, containmentConfirmed = false))
        assertFalse(ForeverPatchPolicy.treeQuiescent(trackedGone = false, containmentConfirmed = true))
        assertTrue(ForeverPatchPolicy.treeQuiescent(trackedGone = true, containmentConfirmed = true))
    }

    @Test fun spawnCallbackUsesSameMonitorAsCancellationAndTerminalTransition() {
        // Regression guard for the real controller entrypoint, not an unrelated
        // fake coordinator: JVM's ACC_SYNCHRONIZED must cover the entire callback.
        val method = ForeverLaunchController::class.java.declaredMethods.single { it.name == "onProcessStarted" }
        assertTrue(java.lang.reflect.Modifier.isSynchronized(method.modifiers))
    }

    @Test fun hostLifecycleApiIsDiagnosticOnlyAndHasStableSignature() {
        val method = ForeverLaunchController::class.java.getDeclaredMethod(
            "recordHostLifecycle",
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
        )
        assertFalse(java.lang.reflect.Modifier.isStatic(method.modifiers))
        assertTrue(java.lang.reflect.Modifier.isSynchronized(method.modifiers))
    }

    @Test fun processHintsNeverExportRawCommandArgumentsOrTreatThemAsIdentityProof() {
        val hints = ForeverProcessEvidencePolicy.executableHints(listOf(
            "wine", "C:\\windows\\system32\\winhandler.exe", "A:\\WowB-ARM64.exe",
            "--password", "sensitive-value", "--token=secret", "not-allowlisted.exe",
        ))
        assertEquals(listOf("winhandler.exe", "wowb-arm64.exe"), hints)
        assertFalse(hints.toString().contains("sensitive-value"))
        assertEquals("not_proven", ForeverProcessEvidencePolicy.gameIdentity(0, 0).getString("absence"))
    }

    @Test fun anActivityRecreationCannotClaimTheSameWineRootTwice() {
        val claim = ForeverSpawnClaim()
        val accepted = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = CountDownLatch(1)
        val threads = (0 until 16).map {
            Thread { gate.await(); if (claim.tryClaim()) accepted.incrementAndGet() }.apply { start() }
        }
        gate.countDown()
        threads.forEach { it.join() }
        assertEquals(1, accepted.get())
        assertFalse(claim.tryClaim())
        assertTrue(ForeverSpawnClaim().tryClaim()) // a different armed run has its own claim
    }

    @Test fun reusedHostPidNeedsAnExactRunSummaryBeforeExitAttribution() {
        assertFalse(ForeverProcessEvidencePolicy.summaryMatches(null, "run-a"))
        assertFalse(ForeverProcessEvidencePolicy.summaryMatches(ForeverProcessEvidencePolicy.hostSummary("run-old"), "run-a"))
        assertTrue(ForeverProcessEvidencePolicy.summaryMatches(ForeverProcessEvidencePolicy.hostSummary("run-a"), "run-a"))
        assertEquals("unattributed", ForeverProcessEvidencePolicy.historicalAttribution(false, true))
    }

    @Test fun environmentRoundTripsRunIdAsSeparateVariable() {
        val env = EnvVars().apply { put("WINEESYNC", "0"); put("WOWFOREVER_RUN_ID", "run-a") }
        val persisted = EnvVars(env.toString())
        assertEquals("0", persisted.get("WINEESYNC"))
        assertEquals("run-a", persisted.get("WOWFOREVER_RUN_ID"))
        assertTrue(ForeverSpawnGate.matches("run-a", "WowB-ARM64.exe", "wine A:\\WowB-ARM64.exe", persisted.toStringArray()))
    }

    @Test fun wtfDoesNotTouchConfigAndRejectsSymlinks() {
        val directory = Files.createTempDirectory("forever-wtf").toFile()
        val outside = Files.createTempDirectory("forever-outside").toFile()
        try {
            val wtf = directory.resolve("WTF").apply { mkdirs() }
            wtf.resolve("Config.wtf").writeText("untouched")
            val target = wtf.resolve("BetaSuspendedTest.wtf")
            target.writeText("SET PORTAL \"old\"\nSET portal \"duplicate\"\nSET gxApi \"D3D11\"\n")
            ForeverContract.writeManagedWtf(directory, target.name, "auth.gpon.com.co", "enUS")
            assertEquals("untouched", wtf.resolve("Config.wtf").readText())
            assertEquals(1, target.readLines().count { it.startsWith("SET portal ") })
            assertTrue(target.readText().contains("SET gxApi"))
            target.delete()
            Files.createSymbolicLink(target.toPath(), outside.resolve("escape.wtf").toPath())
            assertThrows(IllegalArgumentException::class.java) { ForeverContract.writeManagedWtf(directory, target.name, "portal", "enUS") }
            assertFalse(outside.resolve("escape.wtf").exists())
            Files.delete(target.toPath())
            wtf.deleteRecursively()
            Files.createSymbolicLink(wtf.toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { ForeverContract.writeManagedWtf(directory, target.name, "portal", "enUS") }
        } finally { directory.deleteRecursively(); outside.deleteRecursively() }
    }
}
