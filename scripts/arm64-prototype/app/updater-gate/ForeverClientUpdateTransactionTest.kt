package app.gamenative

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class ForeverClientUpdateTransactionTest {
    @Test
    fun publishesOnlyAdmittedDeltaAndPublishesBuildMarkerLast() {
        Fixture().use { fixture ->
            val oldSegment = fixture.write("Data/data/data.000", bytes("old-large-segment"))
            val oldIndexBytes = bytes("old-index-generation")
            val oldIndex = fixture.write("Data/data/0000000001.idx", oldIndexBytes)
            val oldLooseBytes = bytes("old-owned-loose")
            fixture.write("Interface/patch.dat", oldLooseBytes)
            val oldMarkerBytes = bytes("old-build-marker")
            fixture.write(".build.info", oldMarkerBytes)
            val preserved = mapOf(
                "README.user" to bytes("unknown root file"),
                "WTF/Config.wtf" to bytes("original config"),
                "WTF/BetaSuspendedTest.wtf" to bytes("original suspended test"),
                "runtime/loader.so" to bytes("runtime-owned bytes"),
                "credentials/login.json" to bytes("credential-owned bytes"),
            ).mapValues { (path, content) -> fixture.write(path, content) }

            val segmentBytes = bytes("new-segment-write-once")
            val indexBytes = bytes("new-index-generation")
            val configHash = "abcdef0123456789abcdef0123456789"
            val configPath = "Data/config/ab/cd/$configHash"
            val configBytes = bytes("new-config")
            val looseBytes = bytes("new-owned-loose")
            val markerBytes = bytes("new-build-marker")
            val transaction = fixture.transaction()
            val staging = transaction.createStaging()
            val inputs = listOf(
                fixture.input(staging, "Data/data/data.001", "segment", segmentBytes),
                fixture.input(staging, "Data/data/0000000001.idx", "index", indexBytes, oldIndexBytes),
                fixture.input(staging, configPath, "config", configBytes),
                fixture.input(staging, "Interface/patch.dat", "loose", looseBytes, oldLooseBytes),
                fixture.input(staging, ".build.info", "build-info", markerBytes, oldMarkerBytes),
            )
            val boundaries = mutableListOf<String>()
            transaction.durableBoundary = boundaries::add
            val plan = transaction.plan(inputs, setOf("Interface/patch.dat"), staging)

            transaction.commit(plan)

            assertEquals(5, plan.fileCount)
            assertArrayEquals(segmentBytes, File(fixture.root, "Data/data/data.001").readBytes())
            assertArrayEquals(indexBytes, File(fixture.root, "Data/data/0000000001.idx").readBytes())
            assertArrayEquals(configBytes, File(fixture.root, configPath).readBytes())
            assertArrayEquals(looseBytes, File(fixture.root, "Interface/patch.dat").readBytes())
            assertArrayEquals(markerBytes, File(fixture.root, ".build.info").readBytes())
            assertArrayEquals(oldSegment, File(fixture.root, "Data/data/data.000").readBytes())
            preserved.forEach { (path, expected) -> assertArrayEquals(expected, File(fixture.root, path).readBytes()) }
            assertEquals("build marker is the final published file", "move:publish:.build.info", boundaries.last { it.startsWith("move:publish:") })
            assertFalse(File(fixture.privateJournal, ".forever-client-update.json").exists())
            assertTrue(File(fixture.privateJournal, ".forever-client-update.lock").isFile)
            assertFalse(staging.directory.exists())
        }
    }

    @Test
    fun reopensOnlyTheExactPrivateReceiptBoundStageAndCommitsItsOriginalFiles() {
        Fixture().use { fixture ->
            val creator = fixture.transaction()
            val id = "01234567-89ab-cdef-0123-456789abcdef"
            val markerHash = creator.stagingMarkerHash(id)
            val stage = creator.createStaging(id, markerHash)
            check(File(stage.directory, "Data/data").mkdirs())
            val segment = bytes("direct-stage-segment")
            val index = bytes("direct-stage-index")
            val build = bytes("direct-stage-build")
            val inputs = listOf(
                fixture.input(stage, "Data/data/data.001", "file-${hash("Data/data/data.001".toByteArray())}", segment),
                fixture.input(stage, "Data/data/0000000001.idx", "file-${hash("Data/data/0000000001.idx".toByteArray())}", index),
                fixture.input(stage, ".build.info", "file-${hash(".build.info".toByteArray())}", build),
            )

            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                fixture.transaction().resumeStaging(id, "0".repeat(64))
            }
            val resumedOwner = fixture.transaction()
            val reopened = resumedOwner.resumeStaging(id, markerHash)
            val plan = resumedOwner.plan(inputs, emptySet(), reopened)
            resumedOwner.commit(plan)

            assertEquals(3, plan.fileCount)
            assertArrayEquals(segment, File(fixture.root, "Data/data/data.001").readBytes())
            assertArrayEquals(index, File(fixture.root, "Data/data/0000000001.idx").readBytes())
            assertArrayEquals(build, File(fixture.root, ".build.info").readBytes())
            assertFalse(stage.directory.exists())
        }
    }

    @Test
    fun readOnlyPendingProbeFindsPrivateJournalWithoutAnyRootStageAndKeepsLeaseDurable() {
        Fixture().use { fixture ->
            val transaction = fixture.transaction()
            assertFalse(transaction.hasPendingTransaction())
            assertFalse(File(fixture.root, ".forever-update-staging").exists())

            File(fixture.privateJournal, ".forever-client-update.json").writeText("pending journal")
            assertTrue(transaction.hasPendingTransaction())
            assertFalse(File(fixture.root, ".forever-update-staging").exists())

            val lease = ForeverClientOperationLease(File(fixture.privateJournal, "operation-state")) {
                RuntimeObservation.Quiescent
            }
            lease.acquireUpdate("fixture-runtime") { transaction.hasPendingTransaction() }.close()
            assertEquals("update_recovery_required", lease.blockingReason())
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                lease.recoverUpdate("fixture-runtime") { transaction.hasPendingTransaction() }
            }
            assertEquals("update_recovery_required", lease.blockingReason())
        }
    }

    @Test
    fun recoversAfterEveryDurableJournalAndRenameBoundary() {
        val successful = Fixture()
        val boundaries = mutableListOf<String>()
        try {
            val transaction = successful.transaction()
            val staging = transaction.createStaging()
            val inputs = successful.threeFileInputs(staging)
            transaction.durableBoundary = boundaries::add
            transaction.commit(transaction.plan(inputs, emptySet(), staging))
        } finally {
            successful.close()
        }
        assertTrue(boundaries.any { it.startsWith("move:backup:") })
        assertTrue(boundaries.any { it.startsWith("move:publish:") })
        assertTrue(boundaries.any { it == "journal:ACTIVE" })

        boundaries.distinct().forEach { crashAt ->
            Fixture().use { fixture ->
                val interrupted = fixture.transaction()
                val staging = interrupted.createStaging()
                val plan = interrupted.plan(fixture.threeFileInputs(staging), emptySet(), staging)
                interrupted.durableBoundary = { boundary -> if (boundary == crashAt) throw SimulatedProcessDeath(boundary) }
                val crash = org.junit.Assert.assertThrows(SimulatedProcessDeath::class.java) {
                    interrupted.commit(plan)
                }
                assertEquals(crashAt, crash.boundary)

                assertEquals(ForeverClientUpdateTransaction.RecoveryResult.COMPLETED, fixture.transaction().recover())
                fixture.assertThreeFileNewState()
                assertFalse(File(fixture.root, ".forever-client-update").exists())
                assertFalse(File(fixture.privateJournal, ".forever-client-update.json").exists())
                assertFalse(staging.directory.exists())
            }
        }
    }

    @Test
    fun damagedRemainingStageRollsBackAlreadyPublishedDelta() {
        Fixture().use { fixture ->
            val interrupted = fixture.transaction()
            val staging = interrupted.createStaging()
            val inputs = fixture.threeFileInputs(staging)
            val plan = interrupted.plan(inputs, emptySet(), staging)
            val segmentStage = File(staging.directory, "segment")
            val oldIndex = fixture.read("Data/data/0000000001.idx")
            val oldSegment = fixture.read("Data/data/data.000")
            val oldMarker = fixture.read(".build.info")
            interrupted.durableBoundary = { boundary ->
                if (boundary == "move:publish:Data/data/0000000001.idx") throw SimulatedProcessDeath(boundary)
            }
            org.junit.Assert.assertThrows(SimulatedProcessDeath::class.java) { interrupted.commit(plan) }
            assertTrue(File(fixture.root, "Data/data/0000000001.idx").readBytes().contentEquals(bytes("new-index")))
            assertTrue(staging.directory.isDirectory)

            segmentStage.writeBytes(bytes("tampered-after-interruption"))
            assertEquals(ForeverClientUpdateTransaction.RecoveryResult.ROLLED_BACK, fixture.transaction().recover())

            assertArrayEquals(oldIndex, File(fixture.root, "Data/data/0000000001.idx").readBytes())
            assertArrayEquals(oldSegment, File(fixture.root, "Data/data/data.000").readBytes())
            assertArrayEquals(oldMarker, File(fixture.root, ".build.info").readBytes())
            assertFalse(File(fixture.root, "Data/data/data.001").exists())
        }
    }

    @Test
    fun resumesRollbackAfterEachDurableDeleteRestoreAndJournalBoundary() {
        val baseline = Fixture()
        val boundaries = mutableListOf<String>()
        try {
            leavePartiallyPublishedTransaction(baseline)
            baseline.transaction().also { it.durableBoundary = boundaries::add }.recover()
        } finally {
            baseline.close()
        }
        assertTrue(boundaries.any { it == "journal:ROLLING_BACK" })
        assertTrue(boundaries.any { it.startsWith("delete:rollback-delete:") })
        assertTrue(boundaries.any { it.startsWith("move:rollback-restore:") })

        boundaries.distinct().forEach { crashAt ->
            Fixture().use { fixture ->
                leavePartiallyPublishedTransaction(fixture)
                val interruptedRecovery = fixture.transaction().also {
                    it.durableBoundary = { boundary -> if (boundary == crashAt) throw SimulatedProcessDeath(boundary) }
                }
                val crash = org.junit.Assert.assertThrows(SimulatedProcessDeath::class.java) {
                    interruptedRecovery.recover()
                }
                assertEquals(crashAt, crash.boundary)

                assertEquals(ForeverClientUpdateTransaction.RecoveryResult.ROLLED_BACK, fixture.transaction().recover())
                assertArrayEquals(bytes("old-index"), File(fixture.root, "Data/data/0000000001.idx").readBytes())
                assertArrayEquals(bytes("immutable-old-segment"), File(fixture.root, "Data/data/data.000").readBytes())
                assertArrayEquals(bytes("old-build"), File(fixture.root, ".build.info").readBytes())
                assertFalse(File(fixture.root, "Data/data/data.001").exists())
            }
        }
    }

    @Test
    fun rejectsInvalidManifestPathsHashesSymlinksTamperingAndDeniedMutation() {
        Fixture().use { fixture ->
            var mutationPermitted = true
            val transaction = fixture.transaction(mutationAllowed = { mutationPermitted })
            val staging = transaction.createStaging()
            val oldMarkerBytes = fixture.write(".build.info", bytes("prior marker"))
            val expected = bytes("new marker")
            val validInput = fixture.input(staging, ".build.info", "build", expected, oldMarkerBytes)
            val ownershipMarker = File(staging.directory, ".owner")
            val validMarkerBytes = ownershipMarker.readBytes()

            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                transaction.plan(listOf(validInput.copy(sha256 = "not-a-hash")), emptySet(), staging)
            }
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                transaction.plan(listOf(validInput.copy(expectedSize = expected.size.toLong() + 1)), emptySet(), staging)
            }
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                transaction.plan(
                    listOf(fixture.input(staging, "WTF/Config.wtf", "protected", expected)),
                    setOf("WTF/Config.wtf"),
                    staging,
                )
            }
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                transaction.plan(listOf(fixture.input(staging, "unknown.bin", "unknown", expected)), emptySet(), staging)
            }
            ownershipMarker.writeBytes(bytes("tampered marker"))
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                transaction.plan(listOf(validInput), emptySet(), staging)
            }
            ownershipMarker.writeBytes(validMarkerBytes)

            val outside = Files.createTempFile("update-stage-outside-", ".bin")
            try {
                Files.write(outside, expected)
                val linkedStage = File(staging.directory, "linked")
                linkedStage.parentFile!!.mkdirs()
                Files.createSymbolicLink(linkedStage.toPath(), outside)
                org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                    transaction.plan(
                        listOf(InputFile(".build.info", linkedStage, expected.size.toLong(), hash(expected), fingerprint(oldMarkerBytes))),
                        emptySet(),
                        staging,
                    )
                }
            } finally {
                Files.deleteIfExists(outside)
            }

            val planned = transaction.plan(listOf(validInput), emptySet(), staging)
            ownershipMarker.writeBytes(bytes("tampered after planning"))
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { transaction.commit(planned) }
            assertArrayEquals(oldMarkerBytes, File(fixture.root, ".build.info").readBytes())
            assertFalse(File(fixture.privateJournal, ".forever-client-update.json").exists())
            ownershipMarker.writeBytes(validMarkerBytes)

            File(staging.directory, "build").writeBytes(bytes("tampered after planning"))
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { transaction.commit(planned) }
            assertArrayEquals(oldMarkerBytes, File(fixture.root, ".build.info").readBytes())
            assertFalse(File(fixture.privateJournal, ".forever-client-update.json").exists())

            File(staging.directory, "build").writeBytes(expected)
            val gatedPlan = transaction.plan(listOf(validInput), emptySet(), staging)
            mutationPermitted = false
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                transaction.commit(gatedPlan)
            }
            assertArrayEquals(oldMarkerBytes, File(fixture.root, ".build.info").readBytes())
            assertFalse(File(fixture.privateJournal, ".forever-client-update.json").exists())

            mutationPermitted = true
            val inFlightPlan = transaction.plan(listOf(validInput), emptySet(), staging)
            transaction.durableBoundary = { boundary ->
                if (boundary == "move:backup:.build.info") mutationPermitted = false
            }
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { transaction.commit(inFlightPlan) }
            assertTrue(File(fixture.privateJournal, ".forever-client-update.json").isFile)
            mutationPermitted = true
            assertEquals(ForeverClientUpdateTransaction.RecoveryResult.COMPLETED, fixture.transaction().recover())
            assertArrayEquals(expected, File(fixture.root, ".build.info").readBytes())

            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                fixture.transaction(identityStillMatches = { false }).plan(listOf(validInput), emptySet(), staging)
            }
        }
    }

    @Test
    fun refusesForeignTargetOrBackupInsteadOfDeletingItDuringRecovery() {
        Fixture().use { fixture ->
            val interrupted = fixture.transaction()
            val staging = interrupted.createStaging()
            val plan = interrupted.plan(fixture.threeFileInputs(staging), emptySet(), staging)
            val target = File(fixture.root, "Data/data/0000000001.idx")
            interrupted.durableBoundary = { boundary ->
                if (boundary == "move:backup:Data/data/0000000001.idx") throw SimulatedProcessDeath(boundary)
            }
            org.junit.Assert.assertThrows(SimulatedProcessDeath::class.java) { interrupted.commit(plan) }
            target.writeBytes(bytes("foreign concurrent modification"))

            org.junit.Assert.assertThrows(IllegalStateException::class.java) { fixture.transaction().recover() }
            assertArrayEquals(bytes("foreign concurrent modification"), target.readBytes())
            assertTrue(File(fixture.privateJournal, ".forever-client-update.json").isFile)
        }
    }

    @Test
    fun arbitraryRegularRootFileCannotBeAdmittedAsStagingInput() {
        Fixture().use { fixture ->
            val oldMarker = fixture.write(".build.info", bytes("old-build-marker"))
            val unrelated = fixture.write("README.user", bytes("must remain at its original path"))
            val transaction = fixture.transaction()
            val staging = transaction.createStaging()
            val rejectedInput = InputFile(
                relativePath = ".build.info",
                stagedFile = File(fixture.root, "README.user"),
                expectedSize = unrelated.size.toLong(),
                sha256 = hash(unrelated),
                expectedOld = fingerprint(oldMarker),
            )

            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                transaction.plan(listOf(rejectedInput), emptySet(), staging)
            }

            assertArrayEquals(unrelated, File(fixture.root, "README.user").readBytes())
            assertArrayEquals(oldMarker, File(fixture.root, ".build.info").readBytes())
            assertFalse(File(fixture.privateJournal, ".forever-client-update.json").exists())
        }
    }

    @Test
    fun recoveryRefusesMissingStagingOwnershipProofWithoutTouchingBackup() {
        Fixture().use { fixture ->
            val transaction = fixture.transaction()
            val staging = transaction.createStaging()
            val inputs = fixture.threeFileInputs(staging)
            val plan = transaction.plan(inputs, emptySet(), staging)
            transaction.durableBoundary = { boundary ->
                if (boundary == "move:backup:Data/data/0000000001.idx") throw SimulatedProcessDeath(boundary)
            }
            org.junit.Assert.assertThrows(SimulatedProcessDeath::class.java) { transaction.commit(plan) }

            File(staging.directory, ".owner").writeBytes(bytes("foreign marker"))
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { fixture.transaction().recover() }

            assertFalse(File(fixture.root, "Data/data/0000000001.idx").exists())
            assertArrayEquals(
                bytes("old-index"),
                File(fixture.root, ".forever-client-update/${staging.id}/backups/0000.bak").readBytes(),
            )
            assertArrayEquals(bytes("new-segment"), File(staging.directory, "segment").readBytes())
            assertTrue(File(fixture.privateJournal, ".forever-client-update.json").isFile)
        }
    }

    private class Fixture : AutoCloseable {
        val root: File
        val privateJournal: File

        init {
            root = Files.createTempDirectory("forever-update-root-").toFile()
            privateJournal = Files.createTempDirectory("forever-update-private-").toFile()
        }

        fun transaction(
            identityStillMatches: () -> Boolean = { true },
            mutationAllowed: () -> Boolean = { true },
        ) = ForeverClientUpdateTransaction(root, privateJournal, "fixture-root-identity", identityStillMatches, mutationAllowed)

        fun write(relative: String, bytes: ByteArray): ByteArray {
            val file = File(root, relative)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            return bytes.copyOf()
        }

        fun read(relative: String): ByteArray = File(root, relative).readBytes()

        fun ownerSize(staging: UpdateStagingArea): Long = File(staging.directory, ".owner").length()

        fun input(
            staging: UpdateStagingArea,
            target: String,
            stage: String,
            bytes: ByteArray,
            oldBytes: ByteArray? = null,
        ): InputFile {
            val staged = File(staging.directory, stage)
            staged.parentFile?.mkdirs()
            staged.writeBytes(bytes)
            return InputFile(target, staged, bytes.size.toLong(), hash(bytes), oldBytes?.let(::fingerprint))
        }

        fun threeFileInputs(staging: UpdateStagingArea): List<InputFile> {
            val oldIndex = write("Data/data/0000000001.idx", bytes("old-index"))
            write("Data/data/data.000", bytes("immutable-old-segment"))
            val oldMarker = write(".build.info", bytes("old-build"))
            return listOf(
                input(staging, "Data/data/0000000001.idx", "index", bytes("new-index"), oldIndex),
                input(staging, "Data/data/data.001", "segment", bytes("new-segment")),
                input(staging, ".build.info", "build", bytes("new-build"), oldMarker),
            )
        }

        fun assertThreeFileNewState() {
            assertArrayEquals(bytes("new-index"), File(root, "Data/data/0000000001.idx").readBytes())
            assertArrayEquals(bytes("immutable-old-segment"), File(root, "Data/data/data.000").readBytes())
            assertArrayEquals(bytes("new-segment"), File(root, "Data/data/data.001").readBytes())
            assertArrayEquals(bytes("new-build"), File(root, ".build.info").readBytes())
        }

        /** Fresh write-once payloads on an empty root: no pre-existing targets, no priors. */
        fun freshDescriptors(staging: UpdateStagingArea): Triple<List<FreshStagedDescriptor>, Map<String, ByteArray>, UpdateStagingArea> {
            val payloads = mapOf(
                "Data/data/data.001" to patterned(4096, 7),
                "Data/data/0000000001.idx" to patterned(512, 13),
                ".build.info" to patterned(128, 29),
            )
            val names = mapOf(
                "Data/data/data.001" to "segment",
                "Data/data/0000000001.idx" to "index",
                ".build.info" to "build",
            )
            val descriptors = payloads.map { (target, content) ->
                val staged = File(staging.directory, names.getValue(target))
                staged.parentFile?.mkdirs()
                staged.writeBytes(content)
                FreshStagedDescriptor(target, staged, null)
            }
            return Triple(descriptors, payloads, staging)
        }

        fun assertFreshState(payloads: Map<String, ByteArray>) {
            payloads.forEach { (target, expected) ->
                assertArrayEquals(expected, File(root, target).readBytes())
            }
        }

        override fun close() {
            root.deleteRecursively()
            privateJournal.deleteRecursively()
        }
    }

    private class SimulatedProcessDeath(val boundary: String) : RuntimeException("simulated interruption at $boundary")

    private fun leavePartiallyPublishedTransaction(fixture: Fixture) {
        val transaction = fixture.transaction()
        val staging = transaction.createStaging()
        val plan = transaction.plan(fixture.threeFileInputs(staging), emptySet(), staging)
        transaction.durableBoundary = { boundary ->
            if (boundary == "move:publish:Data/data/0000000001.idx") throw SimulatedProcessDeath(boundary)
        }
        org.junit.Assert.assertThrows(SimulatedProcessDeath::class.java) { transaction.commit(plan) }
        File(staging.directory, "segment").writeBytes(bytes("damaged staged segment"))
    }

    companion object {
        private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8)
        private fun patterned(size: Int, seed: Int) = ByteArray(size) { ((it * 31 + seed) and 0xff).toByte() }
        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun fingerprint(bytes: ByteArray) = FileFingerprint(bytes.size.toLong(), hash(bytes))
    }

    @Test
    fun freshNormalPublishesWithExactlyTwoFullContentPasses() {
        Fixture().use { fixture ->
            val transaction = fixture.transaction()
            val staging = transaction.createStaging()
            val (descriptors, payloads) = fixture.freshDescriptors(staging)
            val payloadBytes = payloads.values.sumOf { it.size.toLong() }
            val buildBytes = payloads.getValue(".build.info").size.toLong()
            val ownerBytes = fixture.ownerSize(staging)
            val boundaries = mutableListOf<String>()
            transaction.durableBoundary = boundaries::add
            val events = mutableListOf<ForeverClientUpdateProgress>()
            transaction.finalizationProgress = events::add

            val plan = transaction.planFresh(descriptors, emptySet(), staging)
            assertEquals(3, plan.fileCount)
            transaction.commit(plan)

            // Two full-content passes over the payload: admission plus the
            // per-payload post-rename readback. Only small control files hash
            // again: the staging owner marker (eight tiny verifications:
            // admission, fast validation, pre-journal marker, one per
            // published operation, cleanup binding, and staging cleanup) and
            // the build marker (admitted, read back, then re-proven at
            // cleanup).
            assertEquals(2 * payloadBytes + 8 * ownerBytes + buildBytes, transaction.hashBytesRead.get())

            fixture.assertFreshState(payloads)
            // Marker barrier: every payload proof precedes the marker move,
            // and the marker is the final published file.
            val markerMove = boundaries.indexOf("move:publish:.build.info")
            assertTrue(markerMove >= 0)
            assertEquals("move:publish:.build.info", boundaries.last { it.startsWith("move:publish:") })
            listOf("Data/data/data.001", "Data/data/0000000001.idx").forEach { payload ->
                val verified = boundaries.indexOf("verified:$payload")
                assertTrue("payload $payload must be proven before the marker moves", verified >= 0 && verified < markerMove)
            }
            assertTrue(boundaries.indexOf("verified:.build.info") > markerMove)
            assertTrue(boundaries.contains("journal:COMMITTED"))
            assertFalse(File(fixture.privateJournal, ".forever-client-update.json").exists())
            assertFalse(staging.directory.exists())

            val finals = events.filter { it.phase == ForeverClientUpdateProgress.Phase.APPLYING && it.finalization != null }
            assertTrue(finals.isNotEmpty())
            val last = finals.last().finalization!!
            assertEquals(3L, last.filesTotal)
            assertEquals(3L, last.filesCompleted)
            assertEquals(payloadBytes, last.bytesTotal)
            assertEquals(payloadBytes, last.bytesCompleted)
            finals.forEach {
                val detail = it.finalization!!
                assertTrue(detail.filesCompleted in 0..detail.filesTotal)
                assertTrue(detail.bytesCompleted in 0..detail.bytesTotal)
            }
        }
    }

    @Test
    fun forgedCopiedOrAliasedFastPlanIsRejected() {
        Fixture().use { fixture ->
            val transaction = fixture.transaction()
            val staging = transaction.createStaging()
            val (descriptors, _) = fixture.freshDescriptors(staging)
            val plan = transaction.planFresh(descriptors, emptySet(), staging)

            val copy = TransactionPlan(
                plan.ownerToken,
                plan.identityHash,
                plan.stageId,
                plan.stageMarkerHash,
                plan.operations.toList(),
                plan.ownedLoosePaths.toSet(),
            )
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { transaction.commit(copy) }
            assertFalse(File(fixture.privateJournal, ".forever-client-update.json").exists())
        }
        Fixture().use { fixture ->
            val transaction = fixture.transaction()
            val staging = transaction.createStaging()
            val (descriptors, _) = fixture.freshDescriptors(staging)
            val plan = transaction.planFresh(descriptors, emptySet(), staging)

            @Suppress("UNCHECKED_CAST")
            (plan.operations as MutableList<ForeverClientUpdateTransaction.Operation>).add(
                plan.operations.first().copy(target = ".build.info"),
            )
            try {
                org.junit.Assert.assertThrows(IllegalStateException::class.java) { transaction.commit(plan) }
            } finally {
                @Suppress("UNCHECKED_CAST")
                (plan.operations as MutableList<ForeverClientUpdateTransaction.Operation>).removeAt(plan.operations.size - 1)
            }
            assertFalse(File(fixture.privateJournal, ".forever-client-update.json").exists())
        }
        Fixture().use { fixture ->
            val transaction = fixture.transaction()
            val staging = transaction.createStaging()
            val (descriptors, _) = fixture.freshDescriptors(staging)
            val plan = transaction.planFresh(descriptors, emptySet(), staging)

            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                fixture.transaction().commit(plan)
            }
            assertFalse(File(fixture.privateJournal, ".forever-client-update.json").exists())
        }
    }

    @Test
    fun stagedTamperAfterJournalActiveFailsClosedWithoutMarker() {
        Fixture().use { fixture ->
            val transaction = fixture.transaction()
            val staging = transaction.createStaging()
            val (descriptors, _) = fixture.freshDescriptors(staging)
            val plan = transaction.planFresh(descriptors, emptySet(), staging)
            val boundaries = mutableListOf<String>()
            transaction.durableBoundary = { boundary ->
                boundaries.add(boundary)
                if (boundary == "journal:ACTIVE") {
                    File(staging.directory, "segment").writeBytes(patterned(4096, 99))
                }
            }

            org.junit.Assert.assertThrows(IllegalStateException::class.java) { transaction.commit(plan) }

            assertTrue(boundaries.contains("journal:ACTIVE"))
            assertFalse(boundaries.any { it == "journal:COMMITTED" })
            assertTrue(File(fixture.privateJournal, ".forever-client-update.json").isFile)
            // Marker barrier holds on payload failure: the marker target was
            // never published and its staged bytes are preserved, with the
            // journal left ACTIVE for strict recovery.
            assertFalse(File(fixture.root, ".build.info").exists())
            assertTrue(File(staging.directory, "build").isFile)
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { fixture.transaction().recover() }
            assertTrue(File(fixture.privateJournal, ".forever-client-update.json").isFile)
            // No catch-up fallback: a second commit of the same plan also fails.
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { transaction.commit(plan) }
            assertTrue(File(fixture.privateJournal, ".forever-client-update.json").isFile)
        }
    }

    @Test
    fun publishedTargetTamperAfterMoveFailsClosedAndRecoveryRefusesBlindRepair() {
        Fixture().use { fixture ->
            val transaction = fixture.transaction()
            val staging = transaction.createStaging()
            val (descriptors, _) = fixture.freshDescriptors(staging)
            val plan = transaction.planFresh(descriptors, emptySet(), staging)
            val boundaries = mutableListOf<String>()
            transaction.durableBoundary = { boundary ->
                boundaries.add(boundary)
                if (boundary == "move:publish:Data/data/data.001") {
                    File(fixture.root, "Data/data/data.001").writeBytes(patterned(4096, 77))
                }
            }

            org.junit.Assert.assertThrows(IllegalStateException::class.java) { transaction.commit(plan) }

            assertTrue(boundaries.contains("journal:ACTIVE"))
            assertFalse(boundaries.any { it == "journal:COMMITTED" })
            assertTrue(File(fixture.privateJournal, ".forever-client-update.json").isFile)
            // Marker barrier holds on post-move tamper: every verified payload
            // proof precedes the marker move, so the marker target is absent
            // and its staged bytes are preserved.
            assertFalse(File(fixture.root, ".build.info").exists())
            assertTrue(File(staging.directory, "build").isFile)
            assertFalse(boundaries.any { it == "move:publish:.build.info" })
            // Recovery stays strict: the tampered published target matches
            // neither the admitted hash nor any backup, so it fails closed
            // instead of resetting, repairing, or deleting the evidence.
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { fixture.transaction().recover() }
            assertTrue(File(fixture.privateJournal, ".forever-client-update.json").isFile)
            assertArrayEquals(patterned(4096, 77), File(fixture.root, "Data/data/data.001").readBytes())
        }
    }

    @Test
    fun crashAtEachFastBoundaryRecoversStrictWithRealHashes() {
        val probe = Fixture()
        val probeBoundaries = mutableListOf<String>()
        try {
            val transaction = probe.transaction()
            val staging = transaction.createStaging()
            val (descriptors, _) = probe.freshDescriptors(staging)
            transaction.durableBoundary = probeBoundaries::add
            transaction.commit(transaction.planFresh(descriptors, emptySet(), staging))
        } finally {
            probe.close()
        }
        val firstPublish = probeBoundaries.first { it.startsWith("move:publish:") }
        for (crashAt in listOf("journal:ACTIVE", firstPublish, "journal:COMMITTED").distinct()) {
            Fixture().use { fixture ->
                val interrupted = fixture.transaction()
                val staging = interrupted.createStaging()
                val (descriptors, payloads) = fixture.freshDescriptors(staging)
                val plan = interrupted.planFresh(descriptors, emptySet(), staging)
                interrupted.durableBoundary = { boundary ->
                    if (boundary == crashAt) throw SimulatedProcessDeath(boundary)
                }
                val crash = org.junit.Assert.assertThrows(SimulatedProcessDeath::class.java) {
                    interrupted.commit(plan)
                }
                assertEquals(crashAt, crash.boundary)

                // A new transaction instance owns no volatile proof, so
                // recovery re-hashes real bytes through the strict path.
                val recovery = fixture.transaction()
                assertEquals(ForeverClientUpdateTransaction.RecoveryResult.COMPLETED, recovery.recover())
                assertTrue(recovery.hashBytesRead.get() > 0)
                fixture.assertFreshState(payloads)
                assertFalse(File(fixture.root, ".forever-client-update").exists())
                assertFalse(File(fixture.privateJournal, ".forever-client-update.json").exists())
                assertFalse(staging.directory.exists())
            }
        }
    }

    @Test
    fun cleanupPreservesUnknownStagedFileAndAdmissionHonoursCancel() {
        Fixture().use { fixture ->
            val transaction = fixture.transaction()
            val staging = transaction.createStaging()
            val (descriptors, payloads) = fixture.freshDescriptors(staging)
            val plan = transaction.planFresh(descriptors, emptySet(), staging)
            val unknown = File(staging.directory, "unknown.bin")
            unknown.writeBytes(patterned(64, 5))
            transaction.commit(plan)

            fixture.assertFreshState(payloads)
            assertFalse(File(fixture.privateJournal, ".forever-client-update.json").exists())
            // Post-commit housekeeping never deletes what it does not own.
            assertArrayEquals(patterned(64, 5), unknown.readBytes())
            assertTrue(staging.directory.exists())
        }
        Fixture().use { fixture ->
            val transaction = fixture.transaction()
            val staging = transaction.createStaging()
            val (descriptors, _) = fixture.freshDescriptors(staging)
            org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
                transaction.planFresh(descriptors, emptySet(), staging) { true }
            }
            assertFalse(File(fixture.privateJournal, ".forever-client-update.json").exists())
            assertFalse(File(fixture.root, ".build.info").exists())
        }
    }
}
