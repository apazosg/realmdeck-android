package app.gamenative

import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeverClientPayloadBudgetTest {
    @Test
    fun reservationsSurviveReopenAndCreationNeverResetsLedger() {
        val directory = Files.createTempDirectory("payload-budget-").toFile()
        try {
            val binding = "ab".repeat(32)
            val budget = ForeverClientPayloadBudget.create(directory, binding, 20)
            budget.reserve(7)
            assertEquals(7L, ForeverClientPayloadBudget.open(directory, binding, 20).reservedBytes())
            assertThrows(Exception::class.java) { ForeverClientPayloadBudget.create(directory, binding, 20) }
            assertEquals(7L, ForeverClientPayloadBudget.open(directory, binding, 20).reservedBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun concurrentReservationsOnSharedInstanceCannotExceedCeiling() {
        val directory = Files.createTempDirectory("payload-budget-").toFile()
        try {
            val budget = ForeverClientPayloadBudget.create(directory, "cd".repeat(32), 10)
            val executor = Executors.newFixedThreadPool(8)
            try {
                val outcomes = (0 until 50).map {
                    executor.submit(Callable {
                        try {
                            budget.reserve(1)
                            true
                        } catch (_: ForeverClientPayloadBudget.PayloadBudgetExhaustedException) {
                            false
                        }
                    })
                }
                assertEquals(10, outcomes.count { it.get() })
            } finally {
                executor.shutdownNow()
            }
            assertEquals(10L, budget.reservedBytes())
            assertEquals(10L, ForeverClientPayloadBudget.open(directory, "cd".repeat(32), 10).reservedBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun corruptionIdentityAndCeilingMismatchFailClosed() {
        val directory = Files.createTempDirectory("payload-budget-").toFile()
        try {
            val binding = "ef".repeat(32)
            ForeverClientPayloadBudget.create(directory, binding, 30).reserve(4)
            assertThrows(IllegalStateException::class.java) {
                ForeverClientPayloadBudget.open(directory, "12".repeat(32), 30)
            }
            assertThrows(IllegalStateException::class.java) {
                ForeverClientPayloadBudget.open(directory, binding, 31)
            }
            val ledger = directory.toPath().resolve("payload-budget.v1")
            Files.write(ledger, "corrupt\n".toByteArray())
            assertThrows(IllegalStateException::class.java) {
                ForeverClientPayloadBudget.open(directory, binding, 30)
            }
            assertThrows(Exception::class.java) {
                ForeverClientPayloadBudget.create(directory, binding, 30)
            }
            assertTrue(Files.size(ledger) < 256L)
        } finally {
            directory.deleteRecursively()
        }
    }
}
