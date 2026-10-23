package app.gamenative

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeverClientUpdaterQualificationTest {
    @Test
    fun privateProbeChecksHardlinkAndCleansOnlyItsFreshScratch() {
        Fixture().use { fixture ->
            val foreign = File(fixture.parent, "keep.bin").also { it.writeText("outside scratch") }

            val passed = ForeverClientUpdaterQualification.runFilesystemProbe(
                fixture.parent,
                requirePrivateHardlink = true,
                cancelled = { false },
            )

            assertTrue(passed)
            assertEquals("outside scratch", foreign.readText())
            assertEquals(setOf("keep.bin"), fixture.parent.list()?.toSet())
        }
    }

    @Test
    fun clientVolumeProbeExercisesRenameWithoutAttemptingPrivateHardlink() {
        Fixture().use { fixture ->
            val passed = ForeverClientUpdaterQualification.runFilesystemProbe(
                fixture.parent,
                requirePrivateHardlink = false,
                cancelled = { false },
            )

            assertTrue(passed)
            assertEquals(emptyList<String>(), fixture.parent.list()?.toList())
        }
    }

    @Test
    fun cancellationAfterScratchCreationRemovesOnlyOwnedMarkerAndDirectory() {
        Fixture().use { fixture ->
            var checks = 0
            val passed = ForeverClientUpdaterQualification.runFilesystemProbe(
                fixture.parent,
                requirePrivateHardlink = true,
                cancelled = { ++checks >= 3 },
            )

            assertFalse(passed)
            assertEquals(3, checks)
            assertEquals(emptyList<String>(), fixture.parent.list()?.toList())
        }
    }

    @Test
    fun rejectsSymlinkedParentWithoutTouchingItsTarget() {
        Fixture().use { fixture ->
            val target = File(fixture.parent, "target").apply { mkdir() }
            val sentinel = File(target, "keep.bin").also { it.writeText("not ours") }
            val link = File(fixture.root, "linked-parent")
            Files.createSymbolicLink(link.toPath(), target.toPath())

            val passed = ForeverClientUpdaterQualification.runFilesystemProbe(
                link,
                requirePrivateHardlink = false,
                cancelled = { false },
            )

            assertFalse(passed)
            assertEquals("not ours", sentinel.readText())
            assertEquals(setOf("keep.bin"), target.list()?.toSet())
            assertTrue(Files.isSymbolicLink(link.toPath()))
        }
    }

    @Test
    fun cleanupPreservesAnUnknownFileAddedInsideTheOwnedScratchDirectory() {
        Fixture().use { fixture ->
            var checks = 0
            var scratch: File? = null
            val passed = ForeverClientUpdaterQualification.runFilesystemProbe(
                fixture.parent,
                requirePrivateHardlink = true,
                cancelled = {
                    checks++
                    if (checks == 5) {
                        scratch = fixture.parent.listFiles()!!.single { it.name.startsWith(".forever-updater-qualification-") }
                        File(requireNotNull(scratch), "foreign.bin").writeText("preserve me")
                    }
                    false
                },
            )

            assertFalse(passed)
            assertEquals("preserve me", File(requireNotNull(scratch), "foreign.bin").readText())
            assertEquals(setOf("foreign.bin"), requireNotNull(scratch).list()?.toSet())
            assertEquals(setOf(requireNotNull(scratch).name), fixture.parent.list()?.toSet())
        }
    }

    @Test
    fun parentSentinelsRemainUntouchedAfterAtomicPublicationProbe() {
        Fixture().use { fixture ->
            val sentinel = File(fixture.parent, "foreign.bin").also { it.writeBytes(byteArrayOf(4, 2, 9)) }
            val original = sentinel.readBytes().toList()

            assertTrue(
                ForeverClientUpdaterQualification.runFilesystemProbe(
                    fixture.parent,
                    requirePrivateHardlink = false,
                    cancelled = { false },
                ),
            )

            assertEquals(original, sentinel.readBytes().toList())
            assertEquals(setOf("foreign.bin"), fixture.parent.list()?.toSet())
        }
    }

    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("updater-qualification-test-").toFile()
        val parent = File(root, "parent").apply { mkdir() }

        override fun close() {
            root.deleteRecursively()
        }
    }
}
