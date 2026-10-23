package app.gamenative

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ForeverClientOperationLeaseTest {
    @Test
    fun preReservationCleanupRequiresQuiescenceAndRecoveredOwner() {
        Fixture().use { fixture ->
            val gate = fixture.gate()
            var cleanups = 0
            val cleanup = { cleanups++; Unit }
            fixture.observation = RuntimeObservation.Active(listOf(identity(703, 1237)))
            assertTrue(runCatching { gate.beginGuest("wine-runtime-a", cleanup) }.isFailure)
            fixture.observation = RuntimeObservation.Unknown("proc_unreadable")
            assertTrue(runCatching { gate.beginGuest("wine-runtime-a", cleanup) }.isFailure)
            assertEquals(0, cleanups)
            fixture.observation = RuntimeObservation.Quiescent
            val old = gate.beginGuest("wine-runtime-a")
            assertTrue(runCatching { gate.beginGuest("wine-runtime-a", cleanup) }.isFailure)
            assertEquals(0, cleanups)
            gate.recoverMatchingGuest(old)
            val current = gate.beginGuest("wine-runtime-a", cleanup)
            assertEquals(1, cleanups)
            gate.abortGuestBeforeStart(current)
            assertTrue(runCatching {
                gate.beginGuest("wine-runtime-a") { error("synthetic cleanup failure") }
            }.isFailure)
            assertNull(gate.blockingReason())
        }
    }

    @Test
    fun updaterAndGuestExcludeEachOtherAcrossInstancesAndThreads() {
        Fixture().use { fixture ->
            val updater = fixture.gate()
            val secondInstance = fixture.gate()
            val updateLease = updater.acquireUpdate("wine-runtime-a") { false }

            val started = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val guestFailure = AtomicReference<Throwable?>()
            Thread {
                started.countDown()
                try {
                    secondInstance.beginGuest("wine-runtime-a")
                } catch (failure: Throwable) {
                    guestFailure.set(failure)
                } finally {
                    completed.countDown()
                }
            }.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertTrue(guestFailure.get() is IllegalStateException)
            assertTrue(secondInstance.hasUnfinishedOperation())
            updateLease.close()

            val ticket = secondInstance.beginGuest("wine-runtime-a")
            assertTrue(kotlin.runCatching { updater.acquireUpdate("wine-runtime-a") }.isFailure)
            fixture.observation = RuntimeObservation.Active(listOf(identity(700, 1234)))
            secondInstance.markGuestStarted(ticket)
            fixture.observation = RuntimeObservation.Quiescent
            secondInstance.finishGuest(ticket)
            assertNull(updater.blockingReason())
        }
    }

    @Test
    fun guestRecordSurvivesHostRestartAndOnlyExplicitQuiescentRecoveryClearsIt() {
        Fixture().use { fixture ->
            val original = fixture.gate()
            val ticket = original.beginGuest("wine-runtime-a")
            fixture.observation = RuntimeObservation.Active(listOf(identity(701, 1235)))
            original.markGuestStarted(ticket)
            fixture.assertChecksummedJsonState("GUEST_STARTED", 1)

            fixture.observation = RuntimeObservation.Unknown("proc_unreadable")
            val restarted = fixture.gate()
            assertEquals("own_runtime_unknown:proc_unreadable", restarted.blockingReason())
            assertTrue(kotlin.runCatching { restarted.recoverGuest("wine-runtime-a") }.isFailure)
            assertTrue(restarted.hasUnfinishedOperation())
            assertTrue(kotlin.runCatching { restarted.beginGuest("wine-runtime-a") }.isFailure)

            fixture.observation = RuntimeObservation.Quiescent
            restarted.recoverGuest("wine-runtime-a")
            assertFalse(restarted.hasUnfinishedOperation())
            assertNull(restarted.blockingReason())
        }
    }

    @Test
    fun staleGuestTokenCannotClearTheNewLaunchOwner() {
        Fixture().use { fixture ->
            val gate = fixture.gate()
            val stale = gate.beginGuest("wine-runtime-a")
            gate.abortGuestBeforeStart(stale)
            val current = gate.beginGuest("wine-runtime-a")
            fixture.observation = RuntimeObservation.Active(listOf(identity(702, 1236)))
            gate.markGuestStarted(current)
            assertTrue(kotlin.runCatching { gate.abortGuestBeforeStart(stale) }.isFailure)
            assertTrue(kotlin.runCatching { gate.finishGuest(stale) }.isFailure)
            assertEquals("own_runtime_active", gate.blockingReason())

            fixture.observation = RuntimeObservation.Quiescent
            gate.finishGuest(current)
            assertFalse(gate.hasUnfinishedOperation())
        }
    }

    @Test
    fun pendingUpdateJournalKeepsLaunchBlockedUntilExplicitRecovery() {
        Fixture().use { fixture ->
            val gate = fixture.gate()
            val update = gate.acquireUpdate("wine-runtime-a")
            update.close()
            fixture.assertChecksummedJsonState("UPDATING", 0)
            assertEquals("update_recovery_required", gate.blockingReason())
            assertTrue(kotlin.runCatching { gate.beginGuest("wine-runtime-a") }.isFailure)

            assertTrue(kotlin.runCatching { gate.recoverUpdate("wine-runtime-a") { true } }.isFailure)
            assertEquals("update_recovery_required", gate.blockingReason())

            val resumed = gate.acquireUpdate("wine-runtime-a") { false }
            fixture.observation = RuntimeObservation.Unknown("proc_unreadable")
            resumed.close()
            assertEquals("own_runtime_unknown:proc_unreadable", gate.blockingReason())
            assertTrue(kotlin.runCatching { gate.beginGuest("wine-runtime-a") }.isFailure)

            fixture.observation = RuntimeObservation.Quiescent
            gate.recoverUpdate("wine-runtime-a") { false }
            assertNull(gate.blockingReason())
            val ticket = gate.beginGuest("wine-runtime-a")
            gate.abortGuestBeforeStart(ticket)
        }
    }

    @Test
    fun corruptStateAndSymlinkStateFailClosed() {
        Fixture().use { fixture ->
            val gate = fixture.gate()
            val ticket = gate.beginGuest("wine-runtime-a")
            gate.abortGuestBeforeStart(ticket)
            fixture.stateFile.writeText("not-json")
            assertEquals("operation_state_unreadable", gate.blockingReason())
            assertTrue(gate.hasUnfinishedOperation())
            assertTrue(kotlin.runCatching { gate.beginGuest("wine-runtime-a") }.isFailure)
        }

        Fixture().use { fixture ->
            val gate = fixture.gate()
            val ticket = gate.beginGuest("wine-runtime-a")
            gate.abortGuestBeforeStart(ticket)
            val outside = Files.createTempFile("operation-state-target-", ".json")
            try {
                Files.delete(fixture.stateFile.toPath())
                Files.createSymbolicLink(fixture.stateFile.toPath(), outside)
                assertEquals("operation_state_unreadable", gate.blockingReason())
                assertTrue(gate.hasUnfinishedOperation())
            } finally {
                Files.deleteIfExists(fixture.stateFile.toPath())
                Files.deleteIfExists(outside)
            }
        }

        Fixture().use { fixture ->
            val gate = fixture.gate()
            val ticket = gate.beginGuest("wine-runtime-a")
            gate.abortGuestBeforeStart(ticket)
            Files.delete(File(fixture.directory, ".forever-client-operation.lock").toPath())
            assertEquals("operation_state_unreadable", gate.blockingReason())
            assertTrue(kotlin.runCatching { gate.beginGuest("wine-runtime-a") }.isFailure)
        }
    }

    @Test
    fun reservationAbortNeedsQuiescentProofAndNeverClearsStartedGuest() {
        Fixture().use { fixture ->
            val gate = fixture.gate()
            val reserved = gate.beginGuest("wine-runtime-a")
            fixture.observation = RuntimeObservation.Unknown("proc_unreadable")
            assertTrue(kotlin.runCatching { gate.abortGuestBeforeStart(reserved) }.isFailure)
            fixture.observation = RuntimeObservation.Active(listOf(identity(703, 1237)))
            assertTrue(kotlin.runCatching { gate.abortGuestBeforeStart(reserved) }.isFailure)
            fixture.observation = RuntimeObservation.Quiescent
            gate.abortGuestBeforeStart(reserved)
            assertNull(gate.blockingReason())

            val started = gate.beginGuest("wine-runtime-a")
            fixture.observation = RuntimeObservation.Unknown("post_spawn_unknown")
            gate.markGuestStarted(started)
            fixture.observation = RuntimeObservation.Quiescent
            assertTrue(kotlin.runCatching { gate.abortGuestBeforeStart(started) }.isFailure)
            gate.finishGuest(started)
            assertFalse(gate.hasUnfinishedOperation())
        }
    }

    @Test
    fun matchingTicketRecoveryReleasesBothGuardsOnlyAfterShutdownQuiescence() {
        val guard = ForeverFunctionalClientStorage.ProcessGuard
        guard.resetForTests()
        try {
            Fixture().use { fixture ->
                val gate = fixture.gate()
                val stale = gate.beginGuest("wine-runtime-a")
                gate.abortGuestBeforeStart(stale)
                val ticket = gate.beginGuest("wine-runtime-a")
                val io = requireNotNull(guard.acquireIo())
                val local = requireNotNull(guard.reserveGuest(io))
                io.close()
                assertTrue(guard.markGuestStarted(local))

                fixture.observation = RuntimeObservation.Active(listOf(identity(704, 1238)))
                gate.markGuestStarted(ticket)
                val noFutureLaunch = AtomicBoolean(false)
                fun recover(): Boolean = guard.recoverGuest(local) {
                    if (!ForeverGuestRecoveryProof.isComplete(
                            activePreparedMatches = true,
                            selectedStorageMatches = true,
                            durableTicketMatches = ticket.storageId == "wine-runtime-a",
                            noFutureLaunchConfirmed = noFutureLaunch.get(),
                            environmentGone = true,
                            observation = fixture.observation,
                        )
                    ) return@recoverGuest false
                    gate.recoverMatchingGuest(ticket)
                    true
                }
                assertTrue(kotlin.runCatching { gate.recoverMatchingGuest(stale) }.isFailure)
                assertFalse(recover()) // A still-pending launch is not recoverable.
                noFutureLaunch.set(true)
                assertFalse(recover()) // Active runtime is not recoverable.
                assertNull(guard.acquireIo())

                // The same host retains its reservation after Unknown; only a new
                // Quiescent proof and the exact durable ticket may clear both guards.
                fixture.observation = RuntimeObservation.Unknown("proc_unreadable")
                assertFalse(recover())
                assertNull(guard.acquireIo())

                fixture.observation = RuntimeObservation.Quiescent
                assertTrue(recover())
                assertNull(gate.blockingReason())
                val nextIo = requireNotNull(guard.acquireIo())
                nextIo.close()

                // A launch callback can close before the durable STARTED transition;
                // the RESERVED ticket still needs the same terminal proof and local gate.
                val reservedTicket = gate.beginGuest("wine-runtime-a")
                val reservedIo = requireNotNull(guard.acquireIo())
                val reservedLocal = requireNotNull(guard.reserveGuest(reservedIo))
                reservedIo.close()
                assertTrue(guard.recoverGuest(reservedLocal) {
                    gate.recoverMatchingGuest(reservedTicket)
                    true
                })
                assertNull(gate.blockingReason())
            }
        } finally {
            guard.resetForTests()
        }
    }

    private class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("forever-client-operation-").toFile()
        var observation: RuntimeObservation = RuntimeObservation.Quiescent
        val stateFile: File get() = File(directory, ".forever-client-operation.json")

        fun gate() = ForeverClientOperationLease(directory) { observation }

        fun assertChecksummedJsonState(status: String, identityCount: Int) {
            assertTrue("state record exists", stateFile.isFile)
            val envelope = JSONObject(stateFile.readText(Charsets.UTF_8))
            assertEquals(1, envelope.getInt("schema"))
            val payloadText = envelope.getString("payload")
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(payloadText.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals(actual, envelope.getString("sha256"))
            val payload = JSONObject(payloadText)
            assertEquals(status, payload.getString("status"))
            assertEquals(identityCount, payload.getJSONArray("identities").length())
            assertNotNull(payload.get("token"))
        }

        override fun close() {
            Files.walk(directory.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    companion object {
        private fun identity(pid: Int, startTicks: Long) = RuntimeIdentity(pid, startTicks, "boot-test", uid = 10123)
    }
}
