package app.gamenative

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeverClientRuntimeGateTest {
    private val packageName = "app.gamenative"
    private val uid = 10123
    private val bootId = "12345678-abcd-4321-abcd-123456789abc"

    @Test
    fun quiescenceIgnoresOnlyProvenOwnJavaAppProcessesAndDoesNotReadForeignCommands() {
        val hostName = packageName
        val proc = FakeProc(
            listOf(
                ProcRow(101, uid, hostName.take(15), "/system/bin/app_process64", hostName, 1001),
                ProcRow(202, uid + 1, "other", "/system/bin/hidden", "private foreign args", 2002),
            ),
        )

        val observation = observer(proc).observe()

        assertEquals(RuntimeObservation.Quiescent, observation)
        assertFalse(proc.commandMetadataReads.contains(202))
        assertFalse(proc.commandLineReads.contains(202))
        assertTrue(proc.commandMetadataReads.contains(101))
        assertTrue(proc.commandLineReads.contains(101))
    }

    @Test
    fun androidHostCommSuffixStillRequiresExactPackageArgvAndSystemJavaExecutable() {
        val name = "com.wowforever.arm64probe"
        val host = ProcRow(101, uid, name.takeLast(15), "/system/bin/app_process64", name, 1001)
        fun observe(row: ProcRow) = ForeverClientRuntimeObserver(uid, name, emptyList(), FakeProc(listOf(row))).observe()
        assertEquals(RuntimeObservation.Quiescent, observe(host))
        assertEquals(RuntimeObservation.Unknown("host_process_identity_unknown"), observe(host.copy(firstArgument = "other.package.arm64probe")))
        assertEquals(RuntimeObservation.Unknown("unrecognized_owned_process"), observe(host.copy(executable = "/private/not-java")))
    }

    @Test
    fun onlyTheExactSystemLogCollectorIsExcludedFromGuestObservation() {
        val logging = ProcRow(303, uid, "logcat", "/system/bin/logcat", "", 3033)
        assertEquals(RuntimeObservation.Quiescent, observer(FakeProc(listOf(logging))).observe())
        val unknown = ProcRow(404, uid, "logcat", "/data/user/0/$packageName/files/logcat", "", 4044)
        assertEquals(RuntimeObservation.Unknown("unrecognized_owned_process"), observer(FakeProc(listOf(unknown))).observe())
    }

    @Test
    fun livePrivateWineAndGuestExeChildrenRemainActiveEvenWhenOrphaned() {
        val privateProton = "/private-test-proton/test/bin/wine"
        val wine = ProcRow(303, uid, "wine", privateProton, "", 3033)
        val guest = ProcRow(404, uid, "services.exe", "/system/bin/linker64", "", 4044)
        val observation = observer(FakeProc(listOf(wine, guest))).observe()

        assertEquals(
            RuntimeObservation.Active(
                listOf(
                    RuntimeIdentity(303, 3033, bootId, uid),
                    RuntimeIdentity(404, 4044, bootId, uid),
                ),
            ),
            observation,
        )
    }

    @Test
    fun stableOwnedZombieNeedsNoExecutableOrCommandMetadata() {
        val corpse = ProcRow(303, uid, "wine", "", "", 3033, state = 'Z')
        val proc = FakeProc(listOf(corpse)).apply { failExecutablePid = corpse.pid }
        assertEquals(RuntimeObservation.Quiescent, observer(proc).observe())
        assertFalse(proc.calls.any { it == "exe:303" || it == "comm:303" || it == "argv0:303" })
        assertEquals(4, proc.calls.count { it == "stat:303" })
        val unknown = ProcRow(404, uid, "linker64", "/system/bin/linker64", "", 4044)
        assertEquals(RuntimeObservation.Unknown("unrecognized_owned_process"), observer(FakeProc(listOf(corpse, unknown))).observe())
    }

    @Test
    fun terminalStateChangesAndInvalidStatesNeverProveQuiescence() {
        val row = ProcRow(303, uid, "wine", "/system/bin/wine", "", 3033)
        for (states in listOf(listOf('Z', 'S'), listOf('S', 'Z'), listOf('Z', 'Z', 'S', 'S'))) {
            val proc = FakeProc(listOf(row)).apply { stateSequence = states }
            assertEquals(RuntimeObservation.Unknown(if (states.size == 2) "proc_state_changed" else "proc_race"), observer(proc).observe())
        }
        assertEquals(RuntimeObservation.Unknown("proc_stat_invalid"), observer(FakeProc(listOf(row.copy(state = '?')))).observe())
        val live = FakeProc(listOf(row)).apply { stateSequence = listOf('S', 'R', 'R', 'S') }
        assertEquals(RuntimeObservation.Active(listOf(RuntimeIdentity(row.pid, row.startTicks, bootId, uid))), observer(live).observe())
    }

    @Test
    fun zombieLeaderWithLiveOrUnprovenThreadsIsNeverQuiescent() {
        val row = ProcRow(303, uid, "wine", "", "", 3033, state = 'Z')
        for (counts in listOf(listOf(2), listOf(null), listOf(1, 2))) {
            val proc = FakeProc(listOf(row)).apply {
                threadCountSequence = counts
                failExecutablePid = row.pid
            }
            assertEquals(RuntimeObservation.Unknown("proc_terminal_threads_unconfirmed"), observer(proc).observe())
        }
    }

    @Test
    fun unrecognizedOwnedNativeProcessAndUnreadableProcNeverProveQuiescence() {
        val unknown = ProcRow(505, uid, "helper", "/data/user/0/$packageName/files/helper", "", 5055)
        assertEquals(RuntimeObservation.Unknown("unrecognized_owned_process"), observer(FakeProc(listOf(unknown))).observe())

        val unreadable = FakeProc(listOf(ProcRow(606, uid, "wine", "/system/bin/wine", "", 6066)))
        unreadable.failExecutablePid = 606
        assertEquals(RuntimeObservation.Unknown("proc_exe_unreadable"), observer(unreadable).observe())
    }

    @Test
    fun pidReuseBetweenUidAndIdentityReadsIsUnknown() {
        for (state in listOf('S', 'Z')) for (changeAt in listOf(2, 3)) {
            val row = ProcRow(707, uid, "wine", "/system/bin/wine", "", 7077, state)
            val proc = FakeProc(listOf(row)).apply {
                changeStartTicksAfterFirstStat = 707
                changeStartTicksAtRead = changeAt
            }
            assertEquals(RuntimeObservation.Unknown(if (changeAt == 2) "proc_pid_reused" else "proc_race"), observer(proc).observe())
        }
    }

    @Test
    fun pidEnumerationAndBootIdentityAreBoundedAndFailClosed() {
        val tooMany = FakeProc(emptyList()).apply { forcedPidCount = 8193 }
        assertEquals(RuntimeObservation.Unknown("proc_pid_limit"), observer(tooMany).observe())

        val changedBoot = FakeProc(emptyList()).apply {
            bootIds = listOf(bootId, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        }
        assertEquals(RuntimeObservation.Unknown("boot_id_changed"), observer(changedBoot).observe())
    }

    @Test
    fun factoryUsesOneLazyNoBackupGlobalDirectoryNotASelectedRoot() {
        val appRoot = Files.createTempDirectory("runtime-gate-factory").toFile()
        val noBackup = File(appRoot, "no-backup")
        val files = File(appRoot, "files")
        val proc = FakeProc(emptyList())
        val testGate = ForeverClientRuntimeGate.forTesting(noBackup, files, packageName, proc, uid)
        val secondGate = ForeverClientRuntimeGate.forTesting(noBackup, files, packageName, proc, uid)
        val expected = File(noBackup, "forever-client-operation").toPath().toAbsolutePath().normalize()
        assertEquals(expected, testGate.stateDirectoryPath)
        assertEquals(testGate.stateDirectoryPath, secondGate.stateDirectoryPath)
        assertSame(testGate, secondGate)
        assertTrue(proc.calls.isEmpty()) // Gate creation itself does not scan /proc.
        assertFalse(testGate.stateDirectoryPath.startsWith(files.toPath()))
    }

    @Test
    fun localRecoveryProofRejectsPendingLaunchMismatchAndNonQuiescentObservation() {
        fun proof(
            active: Boolean = true,
            selected: Boolean = true,
            ticket: Boolean = true,
            closed: Boolean = true,
            environmentGone: Boolean = true,
            observation: RuntimeObservation = RuntimeObservation.Quiescent,
        ) = ForeverGuestRecoveryProof.isComplete(
            active, selected, ticket, closed, environmentGone, observation,
        )

        assertTrue(proof())
        assertFalse(proof(closed = false)) // A pending launch can still consume the reservation.
        assertFalse(proof(active = false))
        assertFalse(proof(selected = false))
        assertFalse(proof(ticket = false))
        assertFalse(proof(environmentGone = false))
        assertFalse(proof(observation = RuntimeObservation.Active(listOf(RuntimeIdentity(808, 8088, bootId, uid)))))
        assertFalse(proof(observation = RuntimeObservation.Unknown("proc_unreadable")))
    }

    @Test
    fun playGateRecoversOnlyADurableGuestAndRetainsEveryOtherExclusion() {
        val action = ForeverGuestRecoveryProof::playActionFor
        assertEquals(ForeverGuestRecoveryProof.PlayAction.PROCEED_FRESH, action(null))
        assertEquals(
            ForeverGuestRecoveryProof.PlayAction.RECOVER_MATCHING_GUEST,
            action("guest_start_reserved"),
        )
        assertEquals(
            ForeverGuestRecoveryProof.PlayAction.RECOVER_MATCHING_GUEST,
            action("guest_completion_unconfirmed"),
        )
        // Live or unknown runtime, updater state, unreadable gate and a held
        // lock all retain exclusion: explicit Play must stay blocked.
        listOf(
            "own_runtime_active",
            "own_runtime_unknown:proc_unreadable",
            "update_recovery_required",
            "operation_state_unreadable",
            "operation_lock_held",
        ).forEach { reason ->
            assertEquals("Play must stay blocked for: $reason", ForeverGuestRecoveryProof.PlayAction.RETAIN_EXCLUSION, action(reason))
        }
    }

    private fun observer(proc: FakeProc) = ForeverClientRuntimeObserver(
        ownUid = uid,
        packageName = packageName,
        runtimeRoots = listOf(
            File("/private-test-imagefs").toPath().toAbsolutePath().normalize(),
            File("/private-test-proton").toPath().toAbsolutePath().normalize(),
        ),
        proc = proc,
    )

    private data class ProcRow(
        val pid: Int,
        val uid: Int,
        val comm: String,
        val executable: String,
        val firstArgument: String,
        val startTicks: Long,
        val state: Char = 'S',
        val threadCount: Int? = 1,
    )

    private inner class FakeProc(rows: List<ProcRow>) : ForeverClientProcAccess {
        private val byPid = rows.associateBy { it.pid }
        val calls = mutableListOf<String>()
        val commandMetadataReads = mutableSetOf<Int>()
        val commandLineReads = mutableSetOf<Int>()
        var failExecutablePid: Int? = null
        var changeStartTicksAfterFirstStat: Int? = null
        var changeStartTicksAtRead = 2
        var stateSequence: List<Char>? = null
        var threadCountSequence: List<Int?>? = null
        var forcedPidCount: Int? = null
        var bootIds: List<String> = listOf(bootId, bootId)
        private val bootReads = mutableListOf<String>()
        private val statReads = mutableMapOf<Int, Int>()
        private val statusReads = mutableMapOf<Int, Int>()

        override fun pids(): List<Int> {
            calls += "pids"
            val count = forcedPidCount ?: byPid.size
            return if (forcedPidCount != null) (1..count).toList() else byPid.keys.sorted()
        }

        override fun status(pid: Int): ForeverClientProcStatus {
            calls += "uid:$pid"
            val row = byPid[pid] ?: error("unknown fake PID")
            val count = statusReads.merge(pid, 1, Int::plus) ?: 1
            val threads = threadCountSequence?.let { counts -> counts.getOrElse(count - 1) { counts.last() } }
            return ForeverClientProcStatus(List(4) { row.uid }, if (threadCountSequence == null) row.threadCount else threads)
        }

        override fun comm(pid: Int): String {
            calls += "comm:$pid"
            commandMetadataReads += pid
            return requireNotNull(byPid[pid]).comm
        }

        override fun stat(pid: Int): String {
            calls += "stat:$pid"
            commandMetadataReads += pid
            val row = requireNotNull(byPid[pid])
            val count = statReads.merge(pid, 1, Int::plus) ?: 1
            val ticks = if (pid == changeStartTicksAfterFirstStat && count >= changeStartTicksAtRead) row.startTicks + 1 else row.startTicks
            val state = stateSequence?.let { states -> states.getOrElse(count - 1) { states.last() } } ?: row.state
            val fields = listOf(state.toString()) + List(18) { "0" } + ticks.toString()
            return "$pid (${row.comm}) ${fields.joinToString(" ")}"
        }

        override fun executable(pid: Int): String {
            calls += "exe:$pid"
            commandMetadataReads += pid
            if (pid == failExecutablePid) throw IllegalStateException("private test path")
            return requireNotNull(byPid[pid]).executable
        }

        override fun firstArgument(pid: Int): String {
            calls += "argv0:$pid"
            commandLineReads += pid
            return requireNotNull(byPid[pid]).firstArgument
        }

        override fun bootId(): String {
            calls += "boot"
            return bootIds.getOrElse(bootReads.size) { bootIds.last() }.also(bootReads::add)
        }
    }
}
