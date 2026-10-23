package app.gamenative

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tiny synthetic BLTE payloads only; this suite never requests CDN/game payloads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ForeverClientUpdaterTest {
    private companion object {
        const val STORAGE_MARKER_PATH = ".forever-client-storage"
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Test
    fun planIsReadOnlyReportsBoundCostAndRejectsMismatchedConsentBeforeTransfer() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val paths = fixture.makePaths()
            val fetches = AtomicInteger(0)
            val updater = fixture.updater(paths, configAssets) { _, _, _, _ ->
                fetches.incrementAndGet()
                error("cost planning and rejected consent must not invoke the downloader")
            }
            val destination = fixture.destination(paths)
            val plan = updater.plan(destination)

            assertEquals("1.60.1.70425", plan.version)
            assertEquals(2L, plan.totalObjects)
            assertEquals(0L, plan.reusableObjects)
            assertEquals(2L, plan.missingObjects)
            assertEquals(2L * fixture.directEncodedSize + fixture.rangeEncodedSize, plan.downloadBytes)
            assertEquals(listOf("Interface/patch.mpq"), plan.looseChanges)
            assertTrue(plan.transactionFileCount in 1..4096)
            assertTrue(plan.peakAdditionalBytes >= plan.privateWorkBytes + plan.destinationBytes)
            // Private cost covers the prefetch in-flight bound, not just one
            // object: a sourceless install fetches ahead of the commit cursor.
            assertTrue(plan.privateWorkBytes >= plan.downloadBytes)
            assertTrue(plan.hasEnoughSpace)
            assertEquals(emptyList<String>(), paths.client.list()!!.toList())
            assertFalse(updater.hasPendingTransaction(destination))

            val replanned = fixture.updater(paths, configAssets) { _, _, _, _ ->
                fetches.incrementAndGet()
                error("replanning must remain metadata-only")
            }.plan(destination)
            assertEquals("same snapshot and cost produce a restart-stable consent ID", plan.planId, replanned.planId)
            // planId alone does not assert exact cost: the replan must also
            // reproduce every stable cost and snapshot field (free-space and
            // timing observations are excluded by design).
            assertEquals(plan.downloadBytes, replanned.downloadBytes)
            assertEquals(plan.privateWorkBytes, replanned.privateWorkBytes)
            assertEquals(plan.destinationBytes, replanned.destinationBytes)
            assertEquals(plan.peakAdditionalBytes, replanned.peakAdditionalBytes)
            assertEquals(plan.snapshotHash, replanned.snapshotHash)

            val rejected = ForeverClientUpdateConsent(
                plan.planId,
                plan.downloadBytes - 1,
                plan.peakAdditionalBytes,
                plan.privateWorkBytes,
                plan.destinationBytes,
            )
            assertThrows(IllegalArgumentException::class.java) {
                updater.execute(destination, plan, rejected)
            }
            assertEquals(0, fetches.get())
            assertEquals(emptyList<String>(), paths.client.list()!!.toList())
            assertFalse(updater.hasPendingTransaction(destination))
        }
    }

    @Test
    fun orderedSourceNullPlanningKeepsExactConsentCostsStable() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val paths = fixture.makePaths()
            val updater = fixture.updater(paths, configAssets) { _, _, _, _ ->
                error("cost planning must not invoke the downloader")
            }
            val destination = fixture.destination(paths)
            val first = updater.plan(destination)
            val second = updater.plan(destination)
            // The sourceless replan through the ordered planner must reproduce
            // the entire consent surface: plan identity, every public cost,
            // and the snapshot binding requireSamePlan enforces before transfer.
            assertEquals(first.planId, second.planId)
            assertEquals(first.downloadBytes, second.downloadBytes)
            assertEquals(first.privateWorkBytes, second.privateWorkBytes)
            assertEquals(first.destinationBytes, second.destinationBytes)
            assertEquals(first.peakAdditionalBytes, second.peakAdditionalBytes)
            assertEquals(first.snapshotHash, second.snapshotHash)
            assertEquals(first.totalObjects, second.totalObjects)
            assertEquals(first.reusableObjects, second.reusableObjects)
            assertEquals(first.missingObjects, second.missingObjects)
            assertEquals(first.transactionFileCount, second.transactionFileCount)
            assertEquals(first.looseChanges, second.looseChanges)
            assertEquals(first.version, second.version)
            // Exact pinned costs for the tiny sourceless fixture: nothing is
            // reusable, every encoded byte downloads, consent stays stable.
            assertEquals(2L, first.totalObjects)
            assertEquals(0L, first.reusableObjects)
            assertEquals(2L, first.missingObjects)
            assertEquals(2L * fixture.directEncodedSize + fixture.rangeEncodedSize, first.downloadBytes)
            // The ordered scan must agree with the generic planner row-for-row
            // over the same admitted cursor before the updater trusts its costs.
            val sorted = fixture.targetSpecs().sortedBy { it.ekey }
            val compareRoot = Files.createTempDirectory("ordered-plan-compare-").toFile()
            try {
                val generic = ForeverCasc.inspectTarget(null, File(compareRoot, "generic"), sorted.asSequence(), { false }) { _, _ -> }
                val ordered = ForeverCasc.inspectTargetOrderedSourceNull(File(compareRoot, "ordered"), sorted.asSequence(), { false }) { _, _ -> }
                assertEquals(generic, ordered)
                assertEquals(first.totalObjects, ordered.totalObjects)
                assertEquals(first.missingObjects, ordered.missingObjects)
                // The scan covers CASC objects only (direct + range); the
                // plan's downloadBytes additionally carries the loose-file
                // fetch for the direct key. Both are pinned, not conflated.
                assertEquals(fixture.directEncodedSize + fixture.rangeEncodedSize, ordered.downloadBytes)
                assertEquals(ordered.downloadBytes + fixture.directEncodedSize, first.downloadBytes)
            } finally {
                compareRoot.deleteRecursively()
            }
            assertEquals(emptyList<String>(), paths.client.list()!!.toList())
            assertFalse(updater.hasPendingTransaction(destination))
        }
    }

    @Test
    fun pastedCascSourcePlansOnlyTheDeltaAndRejectsACorruptIndexedObjectForReuse() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val sourcePaths = fixture.makePaths("pasted")
            val freshPaths = fixture.makePaths("fresh")
            val updater = fixture.updater(sourcePaths, configAssets) { _, _, _, _ ->
                error("read-only planning must not invoke the downloader")
            }
            val sourceData = File(sourcePaths.client, "Data/data").apply { check(mkdirs()) }
            fixture.seedSourceCasc(sourceData)
            val pastedBuildInfo = File(sourcePaths.client, ".build.info").apply {
                writeText("unrecognized pasted build metadata\n")
            }
            val pastedBuildInfoBytes = pastedBuildInfo.readBytes()

            val pastedDestination = fixture.destination(sourcePaths).copy(
                sourceOrigin = ForeverClientSourceOrigin.PASTED_SOURCE,
            )
            val pastedPlan = updater.plan(pastedDestination)
            val freshPlan = updater.plan(fixture.destination(freshPaths))
            assertEquals(2L, pastedPlan.totalObjects)
            assertEquals(2L, pastedPlan.reusableObjects)
            assertEquals(0L, pastedPlan.missingObjects)
            assertEquals(fixture.directEncodedSize, pastedPlan.downloadBytes)
            assertTrue("pasted CASC should cost less than a source-less install", pastedPlan.downloadBytes < freshPlan.downloadBytes)
            assertTrue("pasted build metadata is informational, not app-owned", pastedDestination.verifiedOwnedFiles.isEmpty())
            assertArrayEquals("read-only planning preserves pasted build metadata", pastedBuildInfoBytes, pastedBuildInfo.readBytes())

            fixture.corruptFirstSourceObject(sourceData)
            val corruptPlan = updater.plan(pastedDestination)
            assertEquals(2L, corruptPlan.totalObjects)
            assertEquals("a corrupt object must not be counted as reused", 1L, corruptPlan.reusableObjects)
            assertEquals(1L, corruptPlan.missingObjects)
            assertTrue(corruptPlan.downloadBytes > pastedPlan.downloadBytes)
            assertTrue(corruptPlan.downloadBytes < freshPlan.downloadBytes)
        }
    }

    @Test
    fun planRejectsPostAdmissionCatalogGrowthBeforeAnyClientMutation() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val paths = fixture.makePaths()
            val fetches = AtomicInteger(0)
            val updater = fixture.updater(paths, configAssets) { _, _, _, _ ->
                fetches.incrementAndGet()
                error("aggregate rejection must precede any transfer")
            }
            val destination = fixture.destination(paths)
            // RANGE row only: it backs no loose file, so the loose inspection
            // stays pristine and the one-row aggregate is the first gate that
            // observes the growth. Both size columns move together to satisfy
            // the admitted source_length=encoded_size table constraint.
            val huge = ForeverClientUpdateCatalog.MAX_SUPPORTED_ENCODED_OBJECT_BYTES + 1
            val rangeEkey = fixture.targetSpecs()[1].ekey
            fixture.mutateCatalogAfterAdmission(
                "UPDATE objects SET encoded_size=$huge, source_length=$huge WHERE ekey=X'$rangeEkey'",
            )
            assertThrows(IllegalStateException::class.java) { updater.plan(destination) }
            assertEquals(0, fetches.get())
            assertEquals(emptyList<String>(), paths.client.list()!!.toList())
            assertFalse(updater.hasPendingTransaction(destination))
            // The aggregate runs before any private scratch or client staging
            // is created: the work base itself must still be absent.
            assertFalse(File(paths.work, ".forever-client-update-work").exists())
        }
    }

    @Test
    fun guestIdentityUnknownBlocksReadOnlyPlanningWithoutCreatingDestinationState() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val paths = fixture.makePaths()
            val activeLease = ForeverClientOperationLease(File(paths.privateRoot, "active-operation")) {
                RuntimeObservation.Unknown("observer_failed")
            }
            val updater = ForeverClientUpdater(
                fixture.catalog,
                configAssets,
                paths.work,
                paths.journal,
                activeLease,
                { _, _, _, _ -> error("operation lease denial must precede transfer") },
            )
            assertThrows(IllegalStateException::class.java) {
                updater.plan(fixture.destination(paths))
            }
            assertEquals(emptyList<String>(), paths.client.list()!!.toList())
            assertFalse(File(paths.client, ".forever-update-staging").exists())
        }
    }

    @Test
    fun selectedStorageMarkerOnlyAllowsEmptyRootAndForeignOrWrongPrefixMarkerBlocks() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val paths = fixture.makePaths()
            val identity = "volume:synthetic-usb"
            val markerBytes = "forever-client-storage-v1\n$identity\n".toByteArray()
            val marker = File(paths.client, ".forever-client-storage").apply { writeBytes(markerBytes) }
            val admitted = mapOf(STORAGE_MARKER_PATH to FileFingerprint(marker.length(), sha256(markerBytes)))
            val destination = fixture.destination(paths, identity, admitted)
            val updater = fixture.updater(paths, configAssets) { _, _, _, _ -> error("read-only plan must not fetch") }

            val plan = updater.plan(destination)
            assertEquals(2L, plan.missingObjects)
            assertArrayEquals(markerBytes, marker.readBytes())
            assertEquals(listOf(STORAGE_MARKER_PATH), paths.client.list()!!.toList())

            val foreignPaths = fixture.makePaths("foreign")
            val foreignMarker = File(foreignPaths.client, STORAGE_MARKER_PATH).apply { writeBytes(markerBytes) }
            File(foreignPaths.client, "user-owned.txt").writeText("preserve")
            val foreignDestination = fixture.destination(
                foreignPaths,
                identity,
                mapOf(STORAGE_MARKER_PATH to FileFingerprint(foreignMarker.length(), sha256(markerBytes))),
            )
            assertThrows(IllegalStateException::class.java) { updater.plan(foreignDestination) }
            assertEquals("preserve", File(foreignPaths.client, "user-owned.txt").readText())

            val wrongPaths = fixture.makePaths("wrong")
            val wrongBytes = "foreign-owner-v1\n$identity\n".toByteArray()
            val wrongMarker = File(wrongPaths.client, STORAGE_MARKER_PATH).apply { writeBytes(wrongBytes) }
            val wrongDestination = fixture.destination(
                wrongPaths,
                identity,
                mapOf(STORAGE_MARKER_PATH to FileFingerprint(wrongMarker.length(), sha256(wrongBytes))),
            )
            assertThrows(IllegalStateException::class.java) { updater.plan(wrongDestination) }
            assertArrayEquals(wrongBytes, wrongMarker.readBytes())
        }
    }

    @Test
    fun largestAdmittedEncodedObjectIsCostedWithoutDownloadingOrMutatingClient() {
        val configAssets = configAssetsOrSkip()
        Fixture(directEncodedSizeOverride = ForeverClientUpdateCatalog.MAX_SUPPORTED_ENCODED_OBJECT_BYTES).use { fixture ->
            val paths = fixture.makePaths()
            val fetches = AtomicInteger(0)
            val updater = fixture.updater(paths, configAssets) { _, _, _, _ ->
                fetches.incrementAndGet()
                error("unsupported object must be rejected before download")
            }
            val plan = updater.plan(fixture.destination(paths))
            assertEquals(2L * fixture.directEncodedSize + fixture.rangeEncodedSize, plan.downloadBytes)
            assertTrue(plan.destinationBytes >= fixture.directEncodedSize + 480L + 30L)
            assertEquals(0, fetches.get())
            assertEquals(emptyList<String>(), paths.client.list()!!.toList())
        }
    }

    @Test
    fun offlineExecutePublishesSyntheticBuildOnlyAfterBoundCostConsent() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val paths = fixture.makePaths()
            val fetches = AtomicInteger(0)
            val updater = fixture.updater(paths, configAssets) { record, workDir, cancelled, progress ->
                assertFalse(cancelled())
                fetches.incrementAndGet()
                val output = File(workDir, "${record.ekey}.blte")
                Files.write(output.toPath(), fixture.rawFor(record))
                progress(output.length())
                output
            }
            val destination = fixture.destination(paths)
            val plan = updater.plan(destination)
            val consent = ForeverClientUpdateConsent(
                plan.planId,
                plan.downloadBytes,
                plan.peakAdditionalBytes,
                plan.privateWorkBytes,
                plan.destinationBytes,
            )

            val result = updater.execute(destination, plan, consent)

            assertEquals(3, fetches.get()) // two CASC objects plus one separate loose-file materialization.
            assertEquals(2, result.writtenObjects)
            assertEquals(0L, result.reusedObjects)
            assertEquals(plan.transactionFileCount, result.appliedFiles)
            assertFalse(updater.hasPendingTransaction(destination))
            assertFalse(File(paths.client, ".forever-update-staging").exists())
            assertEquals(fixture.directDecoded.toList(), File(paths.client, "Interface/patch.mpq").readBytes().toList())
            assertTrue(File(paths.client, ".build.info").readText().contains("1.60.1.70425"))
            ForeverCasc.verifyExisting(
                paths.client,
                fixture.targetSpecs().map { ForeverCasc.RuntimeObjectSpec(it.ekey, it.contentKey, it.decodedSize) },
                { false },
            )
        }
    }

    @Test
    fun freshExecuteReportsCompleteTwoPassFinalizationUnderAtomicPhase() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val paths = fixture.makePaths()
            val updater = fixture.updater(paths, configAssets) { record, workDir, _, progress ->
                val output = File(workDir, "${record.ekey}.blte")
                Files.write(output.toPath(), fixture.rawFor(record))
                progress(output.length())
                output
            }
            val destination = fixture.destination(paths)
            val plan = updater.plan(destination)
            val events = java.util.Collections.synchronizedList(ArrayList<ForeverClientUpdateProgress>())
            val result = updater.execute(destination, plan, consentFor(plan), { false }, events::add)

            assertEquals(plan.transactionFileCount, result.appliedFiles)
            assertFalse(updater.hasPendingTransaction(destination))
            assertFalse(File(paths.client, ".forever-update-staging").exists())

            // The fresh path reports its two passes through the typed
            // finalization detail while the phase stays atomic: interruption
            // remains blocked exactly as before.
            val finals = events.filter { it.phase == ForeverClientUpdateProgress.Phase.APPLYING && it.finalization != null }
            assertTrue("fresh finalization must report typed file/byte progress", finals.isNotEmpty())
            val publishedDataFiles = File(File(paths.client, "Data/data").also { check(it.isDirectory) }.path).list()!!.size
            val expectedFiles = (publishedDataFiles + configAssets.size + plan.looseChanges.size + 1).toLong()
            val last = finals.last().finalization!!
            assertEquals(expectedFiles, last.filesTotal)
            assertEquals(expectedFiles, last.filesCompleted)
            assertTrue(last.bytesTotal > 0)
            assertEquals(last.bytesTotal, last.bytesCompleted)
            finals.forEach {
                val detail = it.finalization!!
                assertEquals(expectedFiles, detail.filesTotal)
                assertTrue(detail.filesCompleted in 0..detail.filesTotal)
                assertTrue(detail.bytesCompleted in 0..detail.bytesTotal)
                assertTrue(detail.bytesTotal > 0)
            }
            assertNoLiveFetchWorkers("fresh two-pass run")
        }
    }

    @Test
    fun cancelledFreshAdmissionPublishesNothingAndLeavesNoJournal() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val paths = fixture.makePaths()
            val updater = fixture.updater(paths, configAssets) { record, workDir, _, progress ->
                val output = File(workDir, "${record.ekey}.blte")
                Files.write(output.toPath(), fixture.rawFor(record))
                progress(output.length())
                output
            }
            val destination = fixture.destination(paths)
            val plan = updater.plan(destination)
            val admissionStarted = AtomicBoolean(false)
            val seenAdmission = { saw: ForeverClientUpdateProgress ->
                if (saw.phase == ForeverClientUpdateProgress.Phase.APPLYING && saw.finalization != null) {
                    admissionStarted.set(true)
                }
            }
            // Cancel only once the first admission hash is proven underway:
            // downloads and staging stay untouched, and no journal may exist.
            val cancelAfterAdmissionStart = {
                admissionStarted.get()
            }
            val failure = assertThrows(java.util.concurrent.CancellationException::class.java) {
                updater.execute(
                    destination,
                    plan,
                    consentFor(plan),
                    cancelAfterAdmissionStart,
                    { progress -> seenAdmission(progress) },
                )
            }
            assertTrue(failure.message.orEmpty().contains("paused or cancelled"))
            assertTrue("admission must have started before the cancel fired", admissionStarted.get())
            assertFalse(File(paths.journal, ".forever-client-update.json").exists())
            assertFalse(File(paths.journal, ".forever-client-update.json.tmp").exists())
            assertFalse(File(destination.root, ".build.info").exists())
            assertFalse(File(destination.root, "Data/data").exists())
            assertNoLiveFetchWorkers("cancelled fresh admission")
        }
    }

    @Test
    fun pauseAfterCheckpointResumesSameTransactionOwnedSegmentWithoutAnotherFullDeltaCache() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val paths = fixture.makePaths()
            val fetches = AtomicInteger(0)
            val fetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { record, workDir, _, progress ->
                    fetches.incrementAndGet()
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), fixture.rawFor(record))
                    progress(output.length())
                    output
                }
            val destination = fixture.destination(paths)
            val firstUpdater = fixture.updater(paths, configAssets, fetch)
            val originalPlan = firstUpdater.plan(destination)
            val originalConsent = ForeverClientUpdateConsent(
                originalPlan.planId,
                originalPlan.downloadBytes,
                originalPlan.peakAdditionalBytes,
                originalPlan.privateWorkBytes,
                originalPlan.destinationBytes,
            )
            val cancelAfterFirstCheckpoint = {
                val privateBase = File(paths.work, ".forever-client-update-work")
                val owner = privateBase.listFiles().orEmpty().firstOrNull { File(it, ".owner").isFile }
                owner?.let { File(it, ".forever-casc-resume-checkpoint").length() > 40L } == true
            }
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                firstUpdater.execute(destination, originalPlan, originalConsent, cancelAfterFirstCheckpoint)
            }
            // c98 prefetch: the cancel fires after the first commit; workers may
            // already be fetching queued objects (assigned [first, prefetch tail]).
            assertTrue(fetches.get() >= 1)
            assertNoLiveFetchWorkers("cancelled run")
            assertWorkspaceWritesSettled(paths)
            assertFalse(File(paths.client, "Data/data").exists())
            val stageBase = File(paths.client, ".forever-update-staging")
            val transactionStage = stageBase.listFiles()!!.single()
            assertTrue(File(transactionStage, ".owner").isFile)
            assertTrue(File(transactionStage, "Data/data/data.000").isFile)

            // A new engine and transaction object reopen only the private-receipt-bound stage.
            val resumedUpdater = fixture.updater(paths, configAssets, fetch)
            assertEquals(ForeverClientRecoveryResult.RESUMABLE_STAGE_AVAILABLE, resumedUpdater.recover(destination).result)
            val resumedPlan = resumedUpdater.plan(destination)
            assertEquals(originalPlan.planId, resumedPlan.planId)
            assertTrue(resumedPlan.downloadBytes < originalPlan.downloadBytes)
            val result = resumedUpdater.execute(destination, resumedPlan, originalConsent)

            // c98: the resumed producer skips the staged object, then fetches the
            // remaining CASC object plus the loose file (2 calls); the first run
            // contributed between 1 and 2 calls before the cancel fired.
            assertTrue(fetches.get() in 3..4)
            assertEquals(1L, result.reusedObjects)
            assertEquals(1L, result.writtenObjects)
            assertFalse(resumedUpdater.hasPendingTransaction(destination))
            assertFalse(stageBase.exists())
            val dataFiles = File(paths.client, "Data/data")
            val expectedSegmentBytes = 480L + fixture.targetSpecs().sumOf { 30L + it.encodedSize }
            assertEquals(expectedSegmentBytes, File(dataFiles, "data.000").length())
            ForeverCasc.verifyExisting(
                paths.client,
                fixture.targetSpecs().map { ForeverCasc.RuntimeObjectSpec(it.ekey, it.contentKey, it.decodedSize) },
                { false },
            )
        }
    }

    @Test
    fun deltaDownloadPrefetchesConcurrentlyWhileCommitFollowsCatalogOrder() {
        val configAssets = configAssetsOrSkip()
        // Sixteen CASC objects saturate the sixteen prefetch workers; the
        // seventeenth fetch is the loose-file materialization that runs
        // serially after the CASC phase, so at most sixteen fetches are ever
        // in flight and at most sixteen keys sit in the private workspace,
        // under the 18-key legacy prefetch bound.
        Fixture(cascObjectCount = 16).use { fixture ->
            val paths = fixture.makePaths()
            val active = AtomicInteger(0)
            val maxActive = AtomicInteger(0)
            // Arrival order vs finish order: the first arrival waits for every
            // other fetch to finish its file, so the first-started fetch
            // finishes last among the CASC fetches while the writer still
            // commits in catalog order (the final CASC verification below only
            // passes on an ordered commit).
            val arrivals = java.util.Collections.synchronizedList(ArrayList<String>())
            val finishOrder = java.util.Collections.synchronizedList(ArrayList<String>())
            val allStarted = CountDownLatch(16)
            val restFinished = CountDownLatch(15)
            val fetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { record, workDir, _, progress ->
                    active.incrementAndGet()
                    try {
                        val myArrival = synchronized(arrivals) {
                            arrivals.add(record.ekey)
                            arrivals.size - 1
                        }
                        // Deterministic overlap without sleeps: no CASC fetch
                        // proceeds until all sixteen workers have started, so
                        // all sixteen are simultaneously active.
                        allStarted.countDown()
                        assertTrue(
                            "prefetch workers must overlap deterministically",
                            allStarted.await(30, TimeUnit.SECONDS),
                        )
                        maxActive.updateAndGet { current -> maxOf(current, active.get()) }
                        if (myArrival == 0) {
                            assertTrue(
                                "first-started fetch must wait for every other fetch to finish",
                                restFinished.await(30, TimeUnit.SECONDS),
                            )
                        }
                        val output = File(workDir, "${record.ekey}.blte")
                        Files.write(output.toPath(), fixture.rawFor(record))
                        progress(output.length())
                        synchronized(finishOrder) { finishOrder.add(record.ekey) }
                        if (myArrival > 0) restFinished.countDown()
                        output
                    } finally {
                        active.decrementAndGet()
                    }
                }
            val updater = fixture.updater(paths, configAssets, fetch)
            val destination = fixture.destination(paths)
            val plan = updater.plan(destination)
            val consent = ForeverClientUpdateConsent(
                plan.planId,
                plan.downloadBytes,
                plan.peakAdditionalBytes,
                plan.privateWorkBytes,
                plan.destinationBytes,
            )
            val result = updater.execute(destination, plan, consent)

            assertEquals(
                "all sixteen prefetch workers must overlap (observed ${maxActive.get()})",
                16,
                maxActive.get(),
            )
            // Sixteen CASC fetches plus the serial loose-file fetch afterwards:
            // seventeen fetch calls, but sixteen distinct keys, because the
            // loose record reuses the direct CASC object's admitted ekey (its
            // catalog row references objects(ekey)); no fetch is skipped.
            assertEquals(17, arrivals.size)
            assertEquals(16, arrivals.distinct().size)
            assertEquals(17, finishOrder.size)
            assertEquals(
                "first-started fetch must finish last among the CASC fetches",
                15,
                finishOrder.indexOf(arrivals[0]),
            )
            assertEquals(16, result.writtenObjects)
            assertFalse(updater.hasPendingTransaction(destination))
            assertNoLiveFetchWorkers("completed prefetch run")
            ForeverCasc.verifyExisting(
                paths.client,
                fixture.targetSpecs().map { ForeverCasc.RuntimeObjectSpec(it.ekey, it.contentKey, it.decodedSize) },
                { false },
            )
        }
    }

    @Test
    fun legacyPrefetchBoundAdmitsThirtyFourKeysAndRejectsThirtyFiveWithoutDeleting() {
        val configAssets = configAssetsOrSkip()
        // Thirty-four admitted keys fit the prefetch bound: the first
        // fetch plants one legitimate 16-byte prefix per admitted key, all
        // sixteen workers then block until the cancel fires, and the replan
        // must still admit the workspace and resume to a verified install
        // with no live workers and no hash skip.
        Fixture(cascObjectCount = 34).use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            val originalPlan = fixture.updater(paths, configAssets) { _, _, _, _ ->
                error("planning must remain metadata-only")
            }.plan(destination)
            val consent = ForeverClientUpdateConsent(
                originalPlan.planId,
                originalPlan.downloadBytes,
                originalPlan.peakAdditionalBytes,
                originalPlan.privateWorkBytes,
                originalPlan.destinationBytes,
            )
            cancelWithPlantedPrefixes(fixture, paths, configAssets, destination, originalPlan, consent)
            val parts = workspaceObjectsDir(paths).listFiles().orEmpty().filter { it.extension == "part" }
            assertEquals(34, parts.size)
            assertNoLiveFetchWorkers("cancelled thirty-four-key run")
            assertWorkspaceWritesSettled(paths)

            val resumeCalls = AtomicInteger(0)
            val resumedUpdater = fixture.updater(paths, configAssets) { record, workDir, _, progress ->
                resumeCalls.incrementAndGet()
                val output = File(workDir, "${record.ekey}.blte")
                Files.write(output.toPath(), fixture.rawFor(record))
                progress(output.length())
                output
            }
            val resumedPlan = resumedUpdater.plan(destination)
            assertEquals("same snapshot and cost produce a restart-stable consent ID", originalPlan.planId, resumedPlan.planId)
            // Conservative maximum: the planted prefixes may be evicted to
            // hold the 34-key quota, so the plan promises no prefix credit;
            // the resume still reuses them at runtime, keeping actual network
            // under consent.
            assertEquals(originalPlan.downloadBytes, resumedPlan.downloadBytes)
            val result = resumedUpdater.execute(destination, resumedPlan, consent)
            assertEquals(35, resumeCalls.get())
            assertEquals(34, result.writtenObjects)
            assertEquals(0L, result.reusedObjects)
            assertFalse(resumedUpdater.hasPendingTransaction(destination))
            assertNoLiveFetchWorkers("resumed thirty-four-key run")
            ForeverCasc.verifyExisting(
                paths.client,
                fixture.targetSpecs().map { ForeverCasc.RuntimeObjectSpec(it.ekey, it.contentKey, it.decodedSize) },
                { false },
            )
        }

        // Thirty-five admitted keys exceed the bound: the read-only replan must
        // refuse without deleting or truncating any leftover.
        Fixture(cascObjectCount = 35).use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            val originalPlan = fixture.updater(paths, configAssets) { _, _, _, _ ->
                error("planning must remain metadata-only")
            }.plan(destination)
            val consent = ForeverClientUpdateConsent(
                originalPlan.planId,
                originalPlan.downloadBytes,
                originalPlan.peakAdditionalBytes,
                originalPlan.privateWorkBytes,
                originalPlan.destinationBytes,
            )
            cancelWithPlantedPrefixes(fixture, paths, configAssets, destination, originalPlan, consent)
            val parts = workspaceObjectsDir(paths).listFiles().orEmpty().filter { it.extension == "part" }
            assertEquals(35, parts.size)
            assertNoLiveFetchWorkers("cancelled thirty-five-key run")
            assertWorkspaceWritesSettled(paths)
            val before = parts.associate { it.name to it.length() }

            val failure = assertThrows(IllegalStateException::class.java) {
                fixture.updater(paths, configAssets) { _, _, _, _ ->
                    error("bound rejection must precede transfer")
                }.plan(destination)
            }
            assertTrue(
                "thirty-five keys must hit the prefetch bound: ${failure.message}",
                failure.message.orEmpty().contains("legacy prefetch bound"),
            )
            assertEquals(
                "rejected leftovers must be preserved untouched",
                before,
                workspaceObjectsDir(paths).listFiles().orEmpty().filter { it.extension == "part" }
                    .associate { it.name to it.length() },
            )
        }
    }

    private fun cancelWithPlantedPrefixes(
        fixture: Fixture,
        paths: Paths,
        configs: Map<String, File>,
        destination: ForeverClientUpdateDestination,
        plan: ForeverClientUpdatePlan,
        consent: ForeverClientUpdateConsent,
    ) {
        val started = CountDownLatch(16)
        val cancelNow = AtomicBoolean(false)
        val planted = AtomicBoolean(false)
        val blockingFetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
            { record, workDir, _, _ ->
                if (planted.compareAndSet(false, true)) {
                    fixture.targetSpecs().forEach { spec ->
                        File(workDir, "${spec.ekey}.part")
                            .writeBytes(fixture.rawFor(record.copy(ekey = spec.ekey)).copyOfRange(0, 16))
                    }
                }
                started.countDown()
                while (!cancelNow.get()) Thread.sleep(25)
                throw java.util.concurrent.CancellationException("synthetic cancel")
            }
        val canceller = Thread {
            if (!started.await(30, TimeUnit.SECONDS)) throw AssertionError("all sixteen prefetch workers must start")
            cancelNow.set(true)
        }.apply { isDaemon = true; start() }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            fixture.updater(paths, configs) { record, workDir, cancelled, progress ->
                blockingFetch(record, workDir, cancelled, progress)
            }.execute(destination, plan, consent, { cancelNow.get() })
        }
        canceller.join(30_000)
    }

    @Test
    fun differentAdmittedTargetsBindPlansConfigsAndResumableStateToCatalog() {
        val buildA = "synthetic build config for target A".toByteArray()
        val cdnA = "synthetic CDN config for target A".toByteArray()
        Fixture(version = "1.60.1.70500", buildConfigContents = buildA, cdnConfigContents = cdnA).use { first ->
            val configA = first.syntheticConfigAssets()
            val paths = first.makePaths()
            val destination = first.destination(paths)
            val fetches = AtomicInteger(0)
            val fetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { record, workDir, _, progress ->
                    fetches.incrementAndGet()
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), first.rawFor(record))
                    progress(output.length())
                    output
                }
            val firstUpdater = first.updater(paths, configA, fetch)
            val firstPlan = firstUpdater.plan(destination)
            assertEquals("1.60.1.70500", firstPlan.version)

            val firstConsent = ForeverClientUpdateConsent(
                firstPlan.planId,
                firstPlan.downloadBytes,
                firstPlan.peakAdditionalBytes,
                firstPlan.privateWorkBytes,
                firstPlan.destinationBytes,
            )
            val cancelAfterFirstCheckpoint = {
                val privateBase = File(paths.work, ".forever-client-update-work")
                val owner = privateBase.listFiles().orEmpty().firstOrNull { File(it, ".owner").isFile }
                owner?.let { File(it, ".forever-casc-resume-checkpoint").length() > 40L } == true
            }
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                firstUpdater.execute(destination, firstPlan, firstConsent, cancelAfterFirstCheckpoint)
            }
            // c98 prefetch: the cancel fires after the first commit; queued workers
            // may already be fetching the second object.
            assertTrue(fetches.get() >= 1)

            val buildB = "synthetic build config for target B".toByteArray()
            val cdnB = "synthetic CDN config for target B".toByteArray()
            Fixture(version = "1.60.1.70501", buildConfigContents = buildB, cdnConfigContents = cdnB).use { second ->
                val configB = second.syntheticConfigAssets()
                assertFalse(first.catalog.verifiedSha256 == second.catalog.verifiedSha256)
                assertFalse(first.buildConfigHash == second.buildConfigHash)
                assertFalse(first.cdnConfigHash == second.cdnConfigHash)
                assertFalse(buildA.contentEquals(buildB))
                assertFalse(cdnA.contentEquals(cdnB))

                // A different target cannot treat A's UUID-owned root stage as resumable.
                val secondUpdaterOnFirstRoot = second.updater(paths, configB) { _, _, _, _ ->
                    error("a different target must not consume another catalog's stage")
                }
                assertThrows(IllegalStateException::class.java) { secondUpdaterOnFirstRoot.plan(destination) }

                // A fresh updater instance for the exact same catalog resumes A's state.
                val resumedUpdater = first.updater(paths, configA, fetch)
                assertEquals(ForeverClientRecoveryResult.RESUMABLE_STAGE_AVAILABLE, resumedUpdater.recover(destination).result)
                val resumedPlan = resumedUpdater.plan(destination)
                assertEquals(firstPlan.planId, resumedPlan.planId)
                assertTrue(resumedPlan.downloadBytes < firstPlan.downloadBytes)
                resumedUpdater.execute(destination, resumedPlan, firstConsent)

                val secondPaths = second.makePaths("second-target")
                val secondDestination = second.destination(secondPaths)
                val secondUpdater = second.updater(secondPaths, configB) { _, _, _, _ ->
                    error("plan and consent mismatch must be rejected before transfer")
                }
                val secondPlan = secondUpdater.plan(secondDestination)
                assertEquals("1.60.1.70501", secondPlan.version)
                assertThrows(IllegalArgumentException::class.java) {
                    secondUpdater.execute(secondDestination, firstPlan, firstConsent)
                }
                val secondConsent = ForeverClientUpdateConsent(
                    secondPlan.planId,
                    secondPlan.downloadBytes,
                    secondPlan.peakAdditionalBytes,
                    secondPlan.privateWorkBytes,
                    secondPlan.destinationBytes,
                )
                assertThrows(IllegalArgumentException::class.java) {
                    firstUpdater.execute(destination, secondPlan, secondConsent)
                }
            }
        }
    }

    @Test
    fun ownedStageReceiptCountsAsPendingResumeAfterRecoveryLeavesNoJournalPending() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            val fetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { record, workDir, _, progress ->
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), fixture.rawFor(record))
                    progress(output.length())
                    output
                }
            val firstUpdater = fixture.updater(paths, configAssets, fetch)
            val originalPlan = firstUpdater.plan(destination)
            assertEquals(ForeverClientUpdater.ResumeGuardState.IDLE, firstUpdater.resumeGuardState(destination))
            val consent = consentFor(originalPlan)
            val cancelAfterFirstCheckpoint = {
                val privateBase = File(paths.work, ".forever-client-update-work")
                val owner = privateBase.listFiles().orEmpty().firstOrNull { File(it, ".owner").isFile }
                owner?.let { File(it, ".forever-casc-resume-checkpoint").length() > 40L } == true
            }
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                firstUpdater.execute(destination, originalPlan, consent, cancelAfterFirstCheckpoint)
            }
            assertNoLiveFetchWorkers("cancelled run")
            assertWorkspaceWritesSettled(paths)

            // The interrupted run already counts as own pending resume: its
            // validated stage receipt names the selected destination even
            // though the journal may not report a pending transaction yet.
            val interruptedUpdater = fixture.updater(paths, configAssets, fetch)
            assertEquals(ForeverClientUpdater.ResumeGuardState.OWN_PENDING, interruptedUpdater.resumeGuardState(destination))

            // Recovery clears the journal but the validated own stage receipt
            // (and its resumable workspace) remains: the journal alone would
            // report idle while resumable work is still staged.
            val resumedUpdater = fixture.updater(paths, configAssets, fetch)
            assertEquals(ForeverClientRecoveryResult.RESUMABLE_STAGE_AVAILABLE, resumedUpdater.recover(destination).result)
            assertFalse(resumedUpdater.hasPendingTransaction(destination))
            assertEquals(ForeverClientUpdater.ResumeGuardState.OWN_PENDING, resumedUpdater.resumeGuardState(destination))

            // An untouched destination under the same catalog reports idle
            // and stays read-only.
            val freshPaths = fixture.makePaths("fresh-owner")
            val freshDestination = fixture.destination(freshPaths)
            assertEquals(
                ForeverClientUpdater.ResumeGuardState.IDLE,
                fixture.updater(freshPaths, configAssets, fetch).resumeGuardState(freshDestination),
            )
            assertEquals(emptyList<String>(), freshPaths.client.list()!!.toList())
        }
    }

    @Test
    fun otherWorkspaceResumeEvidenceBlocksAnOtherwiseIdleSelectedDestination() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            val fetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { record, workDir, _, progress ->
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), fixture.rawFor(record))
                    progress(output.length())
                    output
                }
            val updater = fixture.updater(paths, configAssets, fetch)
            val plan = updater.plan(destination)
            val cancelAfterFirstCheckpoint = {
                val privateBase = File(paths.work, ".forever-client-update-work")
                val owner = privateBase.listFiles().orEmpty().firstOrNull { File(it, ".owner").isFile }
                owner?.let { File(it, ".forever-casc-resume-checkpoint").length() > 40L } == true
            }
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                updater.execute(destination, plan, consentFor(plan), cancelAfterFirstCheckpoint)
            }
            assertNoLiveFetchWorkers("cancelled run")
            assertWorkspaceWritesSettled(paths)
            assertEquals(ForeverClientUpdater.ResumeGuardState.OWN_PENDING, updater.resumeGuardState(destination))
            val before = workspaceSnapshot(paths)

            // A different selected destination (another workspace binding) must
            // not treat the first workspace's staged work as its own resume,
            // and must not ignore it either: unknown pending fails closed.
            // App-private work and journal roots are shared across selected volumes.
            val otherPaths = fixture.makePaths("other-binding").copy(
                privateRoot = paths.privateRoot, work = paths.work, journal = paths.journal,
            )
            val otherDestination = fixture.destination(otherPaths)
            assertEquals(
                "staged work under another workspace must block, not read as idle",
                ForeverClientUpdater.ResumeGuardState.BLOCKED,
                fixture.updater(otherPaths, configAssets, fetch).resumeGuardState(otherDestination),
            )
            // The guard is read-only: neither workspace nor the idle
            // destination gained, lost or changed a control file.
            assertEquals(before, workspaceSnapshot(paths))
            assertEquals(emptyList<String>(), otherPaths.client.list()!!.toList())
        }
    }

    @Test
    fun malformedStageReceiptAndForeignRootStageAreBlockedNeverOwned() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            val fetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { record, workDir, _, progress ->
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), fixture.rawFor(record))
                    progress(output.length())
                    output
                }
            val updater = fixture.updater(paths, configAssets, fetch)
            val plan = updater.plan(destination)
            val cancelAfterFirstCheckpoint = {
                val privateBase = File(paths.work, ".forever-client-update-work")
                val owner = privateBase.listFiles().orEmpty().firstOrNull { File(it, ".owner").isFile }
                owner?.let { File(it, ".forever-casc-resume-checkpoint").length() > 40L } == true
            }
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                updater.execute(destination, plan, consentFor(plan), cancelAfterFirstCheckpoint)
            }
            assertNoLiveFetchWorkers("cancelled run")
            assertWorkspaceWritesSettled(paths)
            val workspace = requireNotNull(workspaceRoot(paths)) { "expected an owned private updater workspace" }
            val receipt = File(workspace, ".transaction-stage-receipt.json")
            assertTrue(receipt.isFile)

            // A corrupted receipt checksum is ambiguous pending, never valid
            // own resume; the bytes stay preserved for manual review.
            val receiptBefore = receipt.readBytes()
            receipt.writeBytes(receiptBefore.copyOf().also { it[it.lastIndex - 2] = (it[it.lastIndex - 2].toInt() xor 1).toByte() })
            val corruptBeforeGuard = receipt.readBytes()
            assertEquals(
                ForeverClientUpdater.ResumeGuardState.BLOCKED,
                updater.resumeGuardState(destination),
            )
            val receiptAfter = receipt.readBytes()
            assertTrue("guard must not rewrite a corrupt receipt", corruptBeforeGuard.contentEquals(receiptAfter))
            assertEquals("corrupt receipt size must be preserved", receiptBefore.size, receiptAfter.size)
            assertFalse("corruption must have landed", receiptBefore.contentEquals(receiptAfter))

            // Simulate lost controls in this tiny fixture only. Even with no root
            // stage, receipt or pending journal, the selected workspace's checkpoint
            // still forbids switching its catalog. The guard must preserve it.
            assertTrue(receipt.delete())
            assertTrue(File(paths.client, ".forever-update-staging").deleteRecursively())
            assertFalse(updater.hasPendingTransaction(destination))
            val orphanBeforeGuard = workspaceSnapshot(paths)
            assertEquals(ForeverClientUpdater.ResumeGuardState.BLOCKED, updater.resumeGuardState(destination))
            assertEquals(orphanBeforeGuard, workspaceSnapshot(paths))

            // A root stage not named by any validated receipt is foreign or
            // unknown staging, even with no private workspace at all.
            val foreignPaths = fixture.makePaths("foreign-root")
            val foreignStaging = File(File(foreignPaths.client, ".forever-update-staging"), "12345678-1234-1234-1234-1234567890ab")
            assertTrue(foreignStaging.mkdirs())
            val foreignDestination = fixture.destination(foreignPaths)
            assertEquals(
                ForeverClientUpdater.ResumeGuardState.BLOCKED,
                fixture.updater(foreignPaths, configAssets, fetch).resumeGuardState(foreignDestination),
            )
            assertTrue("foreign staging must be preserved, never cleaned by the guard", foreignStaging.isDirectory)
        }
    }

    @Test
    fun bareOwnerMarkerAndDotScratchStayIdleWithoutReceiptOrCheckpointEvidence() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            val fetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { _, _, _, _ -> error("idle guard must remain metadata-only") }
            val updater = fixture.updater(paths, configAssets, fetch)
            assertEquals(ForeverClientUpdater.ResumeGuardState.IDLE, updater.resumeGuardState(destination))

            // A foreign workspace directory with only a bare owner marker and
            // empty areas carries no receipt or checkpoint evidence: idle.
            // Dot-prefixed planner scratch is likewise not a workspace.
            val base = File(paths.work, ".forever-client-update-work").apply { mkdirs() }
            val bare = File(base, "ab".repeat(32)).apply { mkdirs() }
            File(bare, ".owner").writeBytes("bare-owner-marker".toByteArray())
            File(bare, "objects").mkdirs()
            File(bare, "loose").mkdirs()
            File(base, ".target-inspection-scratch").apply { mkdirs() }
            assertEquals(
                ForeverClientUpdater.ResumeGuardState.IDLE,
                updater.resumeGuardState(destination),
            )
        }
    }

    @Test
    fun cancelDuringParallelFetchKeepsSixteenPartialsAndResumeCompletesWithoutOverdownload() {
        val configAssets = configAssetsOrSkip()
        Fixture(cascObjectCount = 16).use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            val originalPlan = fixture.updater(paths, configAssets) { _, _, _, _ ->
                error("planning must remain metadata-only")
            }.plan(destination)
            val consent = ForeverClientUpdateConsent(
                originalPlan.planId,
                originalPlan.downloadBytes,
                originalPlan.peakAdditionalBytes,
                originalPlan.privateWorkBytes,
                originalPlan.destinationBytes,
            )

            // Hold all sixteen prefetch workers mid-flight with a legit
            // resumable prefix each, exactly as a killed transfer would leave
            // them.
            val started = CountDownLatch(16)
            val cancelNow = AtomicBoolean(false)
            val blockedCalls = AtomicInteger(0)
            val blockingFetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { record, workDir, _, _ ->
                    blockedCalls.incrementAndGet()
                    File(workDir, "${record.ekey}.part").writeBytes(fixture.rawFor(record).copyOfRange(0, 16))
                    started.countDown()
                    while (!cancelNow.get()) Thread.sleep(25)
                    throw java.util.concurrent.CancellationException("synthetic cancel")
                }
            val canceller = Thread {
                if (!started.await(30, TimeUnit.SECONDS)) throw AssertionError("all sixteen prefetch workers must start")
                cancelNow.set(true)
            }.apply { isDaemon = true; start() }
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                fixture.updater(paths, configAssets, blockingFetch)
                    .execute(destination, originalPlan, consent, { cancelNow.get() })
            }
            canceller.join(30_000)
            assertEquals(16, blockedCalls.get())

            // No commit happened, but all sixteen prefixes stay on disk and
            // the workspace accepts them (within the 18-key legacy bound),
            // with no late worker writes after the call returned.
            val parts = workspaceObjectsDir(paths).listFiles().orEmpty().filter { it.extension == "part" }
            assertEquals(16, parts.size)
            assertNoLiveFetchWorkers("cancelled parallel run")
            assertWorkspaceWritesSettled(paths)

            val resumeCalls = AtomicInteger(0)
            val resumedUpdater = fixture.updater(paths, configAssets) { record, workDir, _, progress ->
                resumeCalls.incrementAndGet()
                val output = File(workDir, "${record.ekey}.blte")
                Files.write(output.toPath(), fixture.rawFor(record))
                progress(output.length())
                output
            }
            val resumedPlan = resumedUpdater.plan(destination)
            assertEquals("same snapshot and cost produce a restart-stable consent ID", originalPlan.planId, resumedPlan.planId)
            // Conservative maximum: the planted prefixes may be evicted to
            // hold the 34-key quota, so the plan promises no prefix credit;
            // the resume still reuses them at runtime, keeping actual network
            // under consent.
            assertEquals(originalPlan.downloadBytes, resumedPlan.downloadBytes)
            assertTrue(resumedPlan.privateWorkBytes <= originalPlan.privateWorkBytes)
            val result = resumedUpdater.execute(destination, resumedPlan, consent)
            assertEquals(17, resumeCalls.get())
            assertEquals(16, result.writtenObjects)
            assertEquals(0L, result.reusedObjects)
            assertFalse(resumedUpdater.hasPendingTransaction(destination))
            assertNoLiveFetchWorkers("resumed run")
            ForeverCasc.verifyExisting(
                paths.client,
                fixture.targetSpecs().map { ForeverCasc.RuntimeObjectSpec(it.ekey, it.contentKey, it.decodedSize) },
                { false },
            )
        }
    }

    @Test
    fun planMovesVerificationTemporaryCostFromDestinationToPrivateWork() {
        val configAssets = configAssetsOrSkip()
        Fixture(directEncodedSizeOverride = ForeverClientUpdateCatalog.MAX_SUPPORTED_ENCODED_OBJECT_BYTES).use { fixture ->
            val paths = fixture.makePaths()
            val updater = fixture.updater(paths, configAssets) { _, _, _, _ ->
                error("cost planning must not fetch")
            }
            val plan = updater.plan(fixture.destination(paths))
            // Same CASC content cost as before the move.
            assertEquals(2L * fixture.directEncodedSize + fixture.rangeEncodedSize, plan.downloadBytes)
            // The largest-object reserve now sits in private work on top of
            // the prefetch in-flight bound ...
            assertTrue(
                "private work must cover prefetch plus one largest verification temporary",
                plan.privateWorkBytes >= (1L shl 30) + fixture.directEncodedSize,
            )
            // ... and no longer in the destination volume: the destination
            // still stages the object bytes themselves in new segments, but
            // without an extra whole-object temporary reserve.
            assertTrue(plan.destinationBytes >= fixture.directEncodedSize)
            assertTrue(
                "destination must not reserve a whole-object verification temporary",
                plan.destinationBytes < (1L shl 30) + 512L * 1024 * 1024,
            )
        }
    }

    @Test
    fun privateVerificationTemporaryIsAdmittedOnlyWithCheckpointOwnershipAndNeverCleanedByPlan() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            val fetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { record, workDir, _, progress ->
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), fixture.rawFor(record))
                    progress(output.length())
                    output
                }
            val updater = fixture.updater(paths, configAssets, fetch)
            val originalPlan = updater.plan(destination)
            val consent = ForeverClientUpdateConsent(
                originalPlan.planId,
                originalPlan.downloadBytes,
                originalPlan.peakAdditionalBytes,
                originalPlan.privateWorkBytes,
                originalPlan.destinationBytes,
            )
            val cancelAfterFirstCheckpoint = {
                val privateBase = File(paths.work, ".forever-client-update-work")
                val owner = privateBase.listFiles().orEmpty().firstOrNull { File(it, ".owner").isFile }
                owner?.let { File(it, ".forever-casc-resume-checkpoint").length() > 40L } == true
            }
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                updater.execute(destination, originalPlan, consent, cancelAfterFirstCheckpoint)
            }
            assertNoLiveFetchWorkers("cancelled run")
            assertWorkspaceWritesSettled(paths)
            val resumedUpdater = fixture.updater(paths, configAssets, fetch)
            assertEquals(ForeverClientRecoveryResult.RESUMABLE_STAGE_AVAILABLE, resumedUpdater.recover(destination).result)
            val privateBase = File(paths.work, ".forever-client-update-work")
            val workspace = privateBase.listFiles().orEmpty().firstOrNull { File(it, ".owner").isFile }
                ?: throw AssertionError("expected an owned private updater workspace")
            val planBeforeTemp = resumedUpdater.plan(destination)

            // A small leftover with valid receipt-bound markers is admitted
            // as occupied scratch and preserved by the read-only plan.
            val temp = File(workspace, ".forever-casc-incremental-object.tmp")
            temp.writeBytes(byteArrayOf(1, 2, 3, 4))
            val planWithTemp = resumedUpdater.plan(destination)
            assertEquals(planBeforeTemp.planId, planWithTemp.planId)
            assertEquals(planBeforeTemp.downloadBytes, planWithTemp.downloadBytes)
            assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte()), temp.readBytes().toList())

            // A symlink with the exact name is rejected and never followed.
            assertTrue(temp.delete())
            val outside = File(paths.privateRoot, "outside.bin").apply { writeBytes(byteArrayOf(7, 7)) }
            Files.createSymbolicLink(temp.toPath(), outside.toPath())
            val symlinkError = assertThrows(IllegalStateException::class.java) { resumedUpdater.plan(destination) }
            assertTrue(symlinkError.message.orEmpty().contains("unowned state"))
            assertTrue(Files.isSymbolicLink(temp.toPath()))
            assertEquals(listOf(7.toByte(), 7.toByte()), outside.readBytes().toList())
            Files.delete(temp.toPath())

            // An oversized leftover (sparse: no disk cost) is rejected, never truncated.
            java.io.RandomAccessFile(temp, "rw").use { it.setLength(1L shl 30) }
            val oversizedError = assertThrows(IllegalStateException::class.java) { resumedUpdater.plan(destination) }
            assertTrue(oversizedError.message.orEmpty().contains("exceeds the admitted encoded-object bound"))
            assertEquals(1L shl 30, temp.length())
            assertTrue(temp.delete())

            // An unknown file is rejected and preserved.
            val mystery = File(workspace, "mystery.bin").apply { writeBytes(byteArrayOf(5)) }
            val unknownError = assertThrows(IllegalStateException::class.java) { resumedUpdater.plan(destination) }
            assertTrue(unknownError.message.orEmpty().contains("unowned state"))
            assertEquals(listOf(5.toByte()), mystery.readBytes().toList())
            assertTrue(mystery.delete())

            // Without receipt-bound markers the exact-name file is an orphan:
            // rejected, never adopted or deleted by the read-only plan. The
            // destination transaction stage is cleared in setup so the plan
            // reaches the workspace ownership check instead of stopping at
            // the unrelated orphaned destination stage.
            temp.writeBytes(byteArrayOf(1, 2, 3, 4))
            assertTrue(File(paths.client, ".forever-update-staging").deleteRecursively())
            assertTrue(File(workspace, ".transaction-stage-receipt.json").delete())
            assertTrue(File(workspace, ".forever-casc-resume-owner").delete())
            assertTrue(File(workspace, ".forever-casc-resume-checkpoint").delete())
            val orphanError = assertThrows(IllegalStateException::class.java) { resumedUpdater.plan(destination) }
            assertTrue(orphanError.message.orEmpty().contains("no receipt-bound checkpoint ownership"))
            assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte()), temp.readBytes().toList())
        }
    }

    @Test
    fun updateOverExistingSourceReusesWithoutDownload() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            val installCalls = AtomicInteger(0)
            val installUpdater = fixture.updater(paths, configAssets) { record, workDir, _, progress ->
                installCalls.incrementAndGet()
                val output = File(workDir, "${record.ekey}.blte")
                Files.write(output.toPath(), fixture.rawFor(record))
                progress(output.length())
                output
            }
            val plan = installUpdater.plan(destination)
            val consent = ForeverClientUpdateConsent(
                plan.planId,
                plan.downloadBytes,
                plan.peakAdditionalBytes,
                plan.privateWorkBytes,
                plan.destinationBytes,
            )
            val installed = installUpdater.execute(destination, plan, consent)
            assertEquals(2, installed.writtenObjects)

            // The client now has a CASC source: the serial path must satisfy a
            // fully reusable update from it without spending any download.
            // Installed outputs re-enter through the explicit ownership
            // allowlist, exactly as a launcher would persist them.
            val ownedAfterInstall = mapOf(
                ".build.info" to fingerprintOf(File(paths.client, ".build.info")),
                "Interface/patch.mpq" to fingerprintOf(File(paths.client, "Interface/patch.mpq")),
            )
            val installedDestination = fixture.destination(paths, verifiedOwnedFiles = ownedAfterInstall)
            val updateCalls = AtomicInteger(0)
            val updateUpdater = fixture.updater(paths, configAssets) { _, _, _, _ ->
                updateCalls.incrementAndGet()
                error("a fully reusable update must not download")
            }
            val replan = updateUpdater.plan(installedDestination)
            assertEquals(0L, replan.missingObjects)
            assertEquals(2L, replan.reusableObjects)
            assertEquals(0L, replan.downloadBytes)
            val reconsent = ForeverClientUpdateConsent(
                replan.planId,
                replan.downloadBytes,
                replan.peakAdditionalBytes,
                replan.privateWorkBytes,
                replan.destinationBytes,
            )
            // The serial CASC phase completes download-free with full reuse;
            // committing a byte-identical re-application is then refused by
            // the transaction contract (it requires a backup for a replaced
            // target, and identical content takes none). Recovery refuses the
            // resulting journal for the same reason, so the stuck journal is
            // pinned here verbatim for the owning layer to resolve; it is not
            // papered over from this side.
            val failure = assertThrows(IllegalStateException::class.java) {
                updateUpdater.execute(installedDestination, replan, reconsent)
            }
            assertTrue(
                "serial phase must reach commit, not fail mid-transfer: ${failure.message}",
                failure.message.orEmpty().contains("no verified original backup"),
            )
            assertEquals(0, updateCalls.get())
            val recoveryFailure = assertThrows(IllegalStateException::class.java) {
                updateUpdater.recover(installedDestination)
            }
            assertTrue(
                "stuck journal must surface the same contract edge, not a new failure: ${recoveryFailure.message}",
                recoveryFailure.message.orEmpty().contains("unexpected/missing backup"),
            )
            assertNoLiveFetchWorkers("reusable update")
        }
    }

    @Test
    fun retiringCheckpointedCopyKeepsFullDeclaredLooseCost() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            val originalPlan = fixture.updater(paths, configAssets) { _, _, _, _ ->
                error("planning must remain metadata-only")
            }.plan(destination)
            val consent = ForeverClientUpdateConsent(
                originalPlan.planId,
                originalPlan.downloadBytes,
                originalPlan.peakAdditionalBytes,
                originalPlan.privateWorkBytes,
                originalPlan.destinationBytes,
            )
            // Manifest order is ORDER BY ekey, so the head commit is the
            // smallest admitted ekey. Only it is served; every other fetch
            // fails fast without writing any file, so the crash below leaves
            // exactly one checkpointed object plus its not-yet-deleted raw.
            val headEkey = fixture.targetSpecs().minByOrNull { it.ekey }!!.ekey
            val headSize = fixture.targetSpecs().first { it.ekey == headEkey }.encodedSize
            val crashOnce = AtomicBoolean(false)
            val crashingProgress: (ForeverClientUpdateProgress) -> Unit = { progress ->
                if (progress.phase == ForeverClientUpdateProgress.Phase.DOWNLOADING &&
                    progress.completedObjects == 1L && crashOnce.compareAndSet(false, true)
                ) {
                    throw IllegalStateException("synthetic kill after durable checkpoint and before raw delete")
                }
            }
            val crashingFetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { record, workDir, _, progress ->
                    if (record.ekey != headEkey) throw java.util.concurrent.CancellationException("no file for non-head object")
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), fixture.rawFor(record))
                    progress(output.length())
                    output
                }
            val failure = assertThrows(IllegalStateException::class.java) {
                fixture.updater(paths, configAssets, crashingFetch)
                    .execute(destination, originalPlan, consent, { false }, crashingProgress)
            }
            assertTrue(failure.message.orEmpty().contains("synthetic kill"))
            assertNoLiveFetchWorkers("crashed run")
            assertWorkspaceWritesSettled(paths)
            assertEquals(
                "crash window must leave exactly the checkpointed head raw",
                listOf("$headEkey.blte"),
                workspaceObjectsDir(paths).listFiles()!!.map { it.name }.sorted(),
            )

            // C106 retires the surviving raw after re-verifying its staged
            // CASC copy, so the replan must carry the full declared cost of
            // whatever still needs downloading. The old code credited the
            // leftover a second time: through the loose branch when the head
            // is the loose-shared ekey (it assumed the raw would survive for
            // loose reuse — false once the copy is retired, the loose file
            // really is re-downloaded), or through the CASC-miss branch for
            // any older checkpointed key (real misses still download, so the
            // consented remainder would not cover them). Either old path
            // planned 24 here; the correct remainder is 48.
            val resumedUpdater = fixture.updater(paths, configAssets) { record, workDir, _, progress ->
                val output = File(workDir, "${record.ekey}.blte")
                Files.write(output.toPath(), fixture.rawFor(record))
                progress(output.length())
                output
            }
            val resumedPlan = resumedUpdater.plan(destination)
            assertEquals(originalPlan.downloadBytes - headSize, resumedPlan.downloadBytes)
            assertTrue(resumedPlan.downloadBytes < originalPlan.downloadBytes)

            val actualNetwork = AtomicLong(0L)
            val countingUpdater = fixture.updater(paths, configAssets) { record, workDir, _, progress ->
                val output = File(workDir, "${record.ekey}.blte")
                Files.write(output.toPath(), fixture.rawFor(record))
                progress(output.length())
                actualNetwork.addAndGet(output.length())
                output
            }
            val resumedConsent = ForeverClientUpdateConsent(
                resumedPlan.planId,
                resumedPlan.downloadBytes,
                resumedPlan.peakAdditionalBytes,
                resumedPlan.privateWorkBytes,
                resumedPlan.destinationBytes,
            )
            val result = countingUpdater.execute(destination, resumedPlan, resumedConsent)
            assertEquals(1L, result.reusedObjects)
            assertEquals(1L, result.writtenObjects)
            // The retired head is gone before the loose phase, so the loose
            // copy is genuinely re-downloaded: declared cost matches real
            // network exactly, never under-consents.
            assertEquals(resumedPlan.downloadBytes, actualNetwork.get())
            assertFalse(countingUpdater.hasPendingTransaction(destination))
            assertNoLiveFetchWorkers("resumed run")
            ForeverCasc.verifyExisting(
                paths.client,
                fixture.targetSpecs().map { ForeverCasc.RuntimeObjectSpec(it.ekey, it.contentKey, it.decodedSize) },
                { false },
            )
        }
    }

    @Test
    fun dualPartAndBlteForSameKeyIsRefusedBeforeProducing() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            val originalPlan = fixture.updater(paths, configAssets) { _, _, _, _ ->
                error("planning must remain metadata-only")
            }.plan(destination)
            val consent = ForeverClientUpdateConsent(
                originalPlan.planId,
                originalPlan.downloadBytes,
                originalPlan.peakAdditionalBytes,
                originalPlan.privateWorkBytes,
                originalPlan.destinationBytes,
            )
            // First leave exactly one checkpointed raw via the crash window.
            val headEkey = fixture.targetSpecs().minByOrNull { it.ekey }!!.ekey
            val crashOnce = AtomicBoolean(false)
            assertThrows(IllegalStateException::class.java) {
                fixture.updater(paths, configAssets) { record, workDir, _, progress ->
                    if (record.ekey != headEkey) throw java.util.concurrent.CancellationException("no file for non-head object")
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), fixture.rawFor(record))
                    progress(output.length())
                    output
                }.execute(
                    destination,
                    originalPlan,
                    consent,
                    { false },
                    { progress ->
                        if (progress.phase == ForeverClientUpdateProgress.Phase.DOWNLOADING &&
                            progress.completedObjects == 1L && crashOnce.compareAndSet(false, true)
                        ) {
                            throw IllegalStateException("synthetic kill after durable checkpoint")
                        }
                    },
                )
            }
            assertNoLiveFetchWorkers("crashed run")
            assertWorkspaceWritesSettled(paths)
            val headBlte = File(workspaceObjectsDir(paths), "$headEkey.blte")
            assertTrue(headBlte.isFile)
            // Simulate the ambiguous dual state no transfer flow produces
            // (publication moves .part to .blte atomically; a valid .blte is
            // returned without creating a .part): a stale partial next to the
            // verified output for the same key.
            val headPart = File(workspaceObjectsDir(paths), "$headEkey.part")
            val partBytes = headBlte.readBytes().copyOfRange(0, 16)
            headPart.writeBytes(partBytes)
            val before = mapOf(headBlte.name to headBlte.readBytes().toList(), headPart.name to headPart.readBytes().toList())

            // Planning admits the pair conservatively (both bytes counted,
            // no second credit), but the parallel run must refuse it before
            // producing: no transfer flow rests a key with both files, so a
            // single per-key bound could not cover it. Zero fetch calls, both
            // files preserved byte-for-byte, no client output.
            val fetchCalls = AtomicInteger(0)
            val dualPlan = fixture.updater(paths, configAssets) { _, _, _, _ ->
                error("dual-state planning must remain metadata-only")
            }.plan(destination)
            val dualConsent = ForeverClientUpdateConsent(
                dualPlan.planId,
                dualPlan.downloadBytes,
                dualPlan.peakAdditionalBytes,
                dualPlan.privateWorkBytes,
                dualPlan.destinationBytes,
            )
            val failure = assertThrows(IllegalStateException::class.java) {
                fixture.updater(paths, configAssets) { record, workDir, _, progress ->
                    fetchCalls.incrementAndGet()
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), fixture.rawFor(record))
                    progress(output.length())
                    output
                }.execute(destination, dualPlan, dualConsent)
            }
            assertTrue(
                "ambiguous dual raw must be refused expressly: ${failure.message}",
                failure.message.orEmpty().contains("ambiguous dual raw"),
            )
            assertEquals("refused run must not fetch anything", 0, fetchCalls.get())
            assertEquals(
                "refused leftovers must be preserved untouched",
                before,
                workspaceObjectsDir(paths).listFiles()!!.associate { it.name to it.readBytes().toList() },
            )
            assertFalse(File(paths.client, "Data/data").exists())
            assertNoLiveFetchWorkers("refused run")
            assertWorkspaceWritesSettled(paths)
        }
    }

    @Test
    fun checkpointedPartDrainsDurablyAndResumeCompletes() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            val originalPlan = fixture.updater(paths, configAssets) { _, _, _, _ ->
                error("planning must remain metadata-only")
            }.plan(destination)
            val consent = ForeverClientUpdateConsent(
                originalPlan.planId,
                originalPlan.downloadBytes,
                originalPlan.peakAdditionalBytes,
                originalPlan.privateWorkBytes,
                originalPlan.destinationBytes,
            )
            // Crash window again: exactly one checkpointed raw on disk.
            val specs = fixture.targetSpecs()
            val headEkey = specs.minByOrNull { it.ekey }!!.ekey
            val headSize = specs.first { it.ekey == headEkey }.encodedSize
            val otherEkey = specs.first { it.ekey != headEkey }.ekey
            val otherSize = specs.first { it.ekey != headEkey }.encodedSize
            val normalFetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { record, workDir, _, progress ->
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), fixture.rawFor(record))
                    progress(output.length())
                    output
                }
            val firstCrash = AtomicBoolean(false)
            assertThrows(IllegalStateException::class.java) {
                fixture.updater(paths, configAssets) { record, workDir, _, progress ->
                    if (record.ekey != headEkey) throw java.util.concurrent.CancellationException("no file for non-head object")
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), fixture.rawFor(record))
                    progress(output.length())
                    output
                }.execute(
                    destination,
                    originalPlan,
                    consent,
                    { false },
                    { progress ->
                        if (progress.phase == ForeverClientUpdateProgress.Phase.DOWNLOADING &&
                            progress.completedObjects == 1L && firstCrash.compareAndSet(false, true)
                        ) {
                            throw IllegalStateException("synthetic first kill after durable checkpoint")
                        }
                    },
                )
            }
            assertNoLiveFetchWorkers("first crashed run")
            assertWorkspaceWritesSettled(paths)
            // Publication persists only a lock plus an atomic move with no
            // directory force, so a crash can leave the partial visible while
            // the checkpoint is already durable. Simulate that legitimate
            // artifact: the output name lost, the partial still on disk.
            val objectsDir = workspaceObjectsDir(paths)
            val headBlte = File(objectsDir, "$headEkey.blte")
            assertTrue(headBlte.isFile)
            val partBytes = headBlte.readBytes().copyOfRange(0, 16)
            assertTrue(headBlte.delete())
            File(objectsDir, "$headEkey.part").writeBytes(partBytes)

            // Second crash after the next new commit: the head prefix must be
            // gone — drained durably once its staged CASC copy was
            // re-verified — while the second raw survives its own window.
            // The head needed no fetch: exactly one CASC call happens here.
            val secondCrash = AtomicBoolean(false)
            val secondCalls = AtomicInteger(0)
            val secondPlan = fixture.updater(paths, configAssets, normalFetch).plan(destination)
            assertEquals(originalPlan.downloadBytes - headSize, secondPlan.downloadBytes)
            assertThrows(IllegalStateException::class.java) {
                fixture.updater(paths, configAssets) { record, workDir, cancelled, progress ->
                    secondCalls.incrementAndGet()
                    normalFetch(record, workDir, cancelled, progress)
                }.execute(
                    destination,
                    secondPlan,
                    consent,
                    { false },
                    { progress ->
                        if (progress.phase == ForeverClientUpdateProgress.Phase.DOWNLOADING &&
                            progress.completedObjects == 1L && secondCrash.compareAndSet(false, true)
                        ) {
                            throw IllegalStateException("synthetic second kill after durable checkpoint")
                        }
                    },
                )
            }
            assertNoLiveFetchWorkers("second crashed run")
            assertWorkspaceWritesSettled(paths)
            assertEquals("one CASC fetch before the second kill", 1, secondCalls.get())
            assertEquals(
                "head prefix drained durably after its staged re-verification; second raw survives its own crash window",
                listOf("$otherEkey.blte"),
                objectsDir.listFiles()!!.map { it.name }.sorted(),
            )

            // Final resume completes with full verification: both checkpointed
            // objects credited exactly once, no whole-Data redownload.
            val resumedUpdater = fixture.updater(paths, configAssets, normalFetch)
            val resumedPlan = resumedUpdater.plan(destination)
            assertEquals(originalPlan.downloadBytes - headSize - otherSize, resumedPlan.downloadBytes)
            val actualNetwork = AtomicLong(0L)
            val result = fixture.updater(paths, configAssets) { record, workDir, _, progress ->
                val output = File(workDir, "${record.ekey}.blte")
                Files.write(output.toPath(), fixture.rawFor(record))
                progress(output.length())
                actualNetwork.addAndGet(output.length())
                output
            }.execute(
                destination,
                resumedPlan,
                ForeverClientUpdateConsent(
                    resumedPlan.planId,
                    resumedPlan.downloadBytes,
                    resumedPlan.peakAdditionalBytes,
                    resumedPlan.privateWorkBytes,
                    resumedPlan.destinationBytes,
                ),
            )
            assertEquals(2L, result.reusedObjects)
            assertEquals(0L, result.writtenObjects)
            assertTrue(
                "resumed network must not exceed the consented remainder: ${actualNetwork.get()} vs ${resumedPlan.downloadBytes}",
                actualNetwork.get() <= resumedPlan.downloadBytes,
            )
            assertFalse(resumedUpdater.hasPendingTransaction(destination))
            assertFalse(File(paths.client, ".forever-update-staging").exists())
            assertNoLiveFetchWorkers("resumed run")
            ForeverCasc.verifyExisting(
                paths.client,
                fixture.targetSpecs().map { ForeverCasc.RuntimeObjectSpec(it.ekey, it.contentKey, it.decodedSize) },
                { false },
            )
        }
    }

    @Test
    fun corruptStagedCopyBlocksResumeWithoutDeletingCheckpointedPart() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            val originalPlan = fixture.updater(paths, configAssets) { _, _, _, _ ->
                error("planning must remain metadata-only")
            }.plan(destination)
            val consent = ForeverClientUpdateConsent(
                originalPlan.planId,
                originalPlan.downloadBytes,
                originalPlan.peakAdditionalBytes,
                originalPlan.privateWorkBytes,
                originalPlan.destinationBytes,
            )
            // Same legitimate artifact as the drain test: checkpointed head
            // with only its partial on disk.
            val headEkey = fixture.targetSpecs().minByOrNull { it.ekey }!!.ekey
            val crashOnce = AtomicBoolean(false)
            assertThrows(IllegalStateException::class.java) {
                fixture.updater(paths, configAssets) { record, workDir, _, progress ->
                    if (record.ekey != headEkey) throw java.util.concurrent.CancellationException("no file for non-head object")
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), fixture.rawFor(record))
                    progress(output.length())
                    output
                }.execute(
                    destination,
                    originalPlan,
                    consent,
                    { false },
                    { progress ->
                        if (progress.phase == ForeverClientUpdateProgress.Phase.DOWNLOADING &&
                            progress.completedObjects == 1L && crashOnce.compareAndSet(false, true)
                        ) {
                            throw IllegalStateException("synthetic kill after durable checkpoint")
                        }
                    },
                )
            }
            assertNoLiveFetchWorkers("crashed run")
            assertWorkspaceWritesSettled(paths)
            val objectsDir = workspaceObjectsDir(paths)
            val headBlte = File(objectsDir, "$headEkey.blte")
            assertTrue(headBlte.isFile)
            val partBytes = headBlte.readBytes().copyOfRange(0, 16)
            assertTrue(headBlte.delete())
            val headPart = File(objectsDir, "$headEkey.part")
            headPart.writeBytes(partBytes)
            // Corrupt the staged segment bytes (sizes and names intact) so the
            // writer's staged-copy verification fails on its hashes.
            val stageBase = File(paths.client, ".forever-update-staging").listFiles()!!.single()
            val segment = File(stageBase, "Data/data/data.000")
            assertTrue(segment.isFile)
            val staged = segment.readBytes()
            // data.000 holds the 480-byte segment header, then the 30-byte
            // local header plus the 24-byte encoded payload of the head
            // object: flip a payload byte so sizes and names stay intact
            // while the hashes fail.
            staged[480 + 30 + 10] = (staged[480 + 30 + 10].toInt() xor 0xff).toByte()
            Files.write(segment.toPath(), staged)
            val beforePart = headPart.readBytes().toList()

            // The resume must block on the failed staged verification without
            // deleting the checkpointed partial and without committing output.
            val fetchCalls = AtomicInteger(0)
            val resumedUpdater = fixture.updater(paths, configAssets) { record, workDir, _, progress ->
                fetchCalls.incrementAndGet()
                val output = File(workDir, "${record.ekey}.blte")
                Files.write(output.toPath(), fixture.rawFor(record))
                progress(output.length())
                output
            }
            val resumedPlan = resumedUpdater.plan(destination)
            val failure = assertThrows(IllegalStateException::class.java) {
                resumedUpdater.execute(
                    destination,
                    resumedPlan,
                    ForeverClientUpdateConsent(
                        resumedPlan.planId,
                        resumedPlan.downloadBytes,
                        resumedPlan.peakAdditionalBytes,
                        resumedPlan.privateWorkBytes,
                        resumedPlan.destinationBytes,
                    ),
                )
            }
            assertTrue(
                "corrupt staged copy must fail its value check: ${failure.message}",
                failure.message.orEmpty().contains("checkpointed CASC object failed EKey, BLTE, content-key, or size verification"),
            )
            assertEquals("checkpointed partial preserved byte-for-byte", beforePart, headPart.readBytes().toList())
            assertFalse("no head output recreated", File(objectsDir, "$headEkey.blte").exists())
            assertFalse("nothing committed to the client", File(paths.client, "Data/data").exists())
            assertNoLiveFetchWorkers("blocked run")
            assertWorkspaceWritesSettled(paths)
        }
    }

    @Test
    fun prefetchBudgetCapNeedsDrainableWorkOrFailsExpressly() {
        // Direct arithmetic on the private budget (reflection, no new public
        // API): all sizes are declared-encoded metadata (plain Longs, no
        // payload bytes allocated anywhere) under the same per-key full-bound
        // contract the parallel writer uses.
        val budgetClass = Class.forName("app.gamenative.ForeverClientUpdater\$PrefetchBudget")
        val constructor = budgetClass.declaredConstructors.single().apply { isAccessible = true }
        val seed = budgetClass.getDeclaredMethod("seed", String::class.java, java.lang.Long.TYPE).apply { isAccessible = true }
        val request = budgetClass.getDeclaredMethod(
            "request",
            String::class.java,
            java.lang.Long.TYPE,
            Function0::class.java,
            Function0::class.java,
            Function0::class.java,
        ).apply { isAccessible = true }
        val release = budgetClass.getDeclaredMethod("release", String::class.java).apply { isAccessible = true }
        val reservedBytesField = budgetClass.getDeclaredField("reservedBytes").apply { isAccessible = true }
        val reservationsField = budgetClass.getDeclaredField("reservations").apply { isAccessible = true }
        fun newBudget(): Any = constructor.newInstance()
        fun reservedBytes(budget: Any): Long = reservedBytesField.getLong(budget)
        fun reservationCount(budget: Any): Int = (reservationsField.get(budget) as Map<*, *>).size
        fun request(
            budget: Any,
            key: String,
            size: Long,
            cancelled: () -> Boolean = { false },
            drainPossible: () -> Boolean = { false },
            evictOne: () -> Boolean = { false },
        ) {
            try {
                request.invoke(budget, key, size, cancelled, drainPossible, evictOne)
            } catch (failure: java.lang.reflect.InvocationTargetException) {
                throw failure.cause ?: failure
            }
        }
        val mib = 1024L * 1024L
        val gib = 1024L * 1024L * 1024L

        // Inherited debt over the hard quota: a new fetch with nothing
        // drainable and no victim fails expressly and immediately instead of
        // hanging.
        val indebted = newBudget()
        seed.invoke(indebted, "aa".repeat(16), 700L * mib)
        seed.invoke(indebted, "bb".repeat(16), 700L * mib)
        assertEquals(2, reservationCount(indebted))
        val started = System.nanoTime()
        val failure = assertThrows(IllegalStateException::class.java) {
            request(indebted, "cc".repeat(16), 24L, { false }, { false }, { false })
        }
        assertTrue("debt over quota must fail expressly: ${failure.message}", failure.message.orEmpty().contains("manual review"))
        assertTrue("express failure must not wait out a hang", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10))
        assertEquals("no reservation is paid by the failed request", 2, reservationCount(indebted))

        // An existing hash-recorded identity is accepted without double
        // count while the hard total stays within quota.
        val small = newBudget()
        seed.invoke(small, "dd".repeat(16), 100L)
        request(small, "dd".repeat(16), 100L, { false }, { false }, { false })
        assertEquals(100L, reservedBytes(small))
        assertEquals(1, reservationCount(small))
        request(small, "ee".repeat(16), gib - 100L, { false }, { false }, { false })
        assertEquals(gib, reservedBytes(small))
        val overCap = assertThrows(IllegalStateException::class.java) {
            request(small, "ff".repeat(16), 1L, { false }, { false }, { false })
        }
        assertTrue(overCap.message.orEmpty().contains("manual review"))

        // Reservation presence is keyed and canonical: seeded keys read back,
        // unknown or released keys do not.
        val hasReservation = budgetClass.getDeclaredMethod("hasReservation", String::class.java).apply { isAccessible = true }
        fun hasReservation(budget: Any, key: String): Boolean = hasReservation.invoke(budget, key) as Boolean
        val probe = newBudget()
        assertFalse(hasReservation(probe, "dd".repeat(16)))
        seed.invoke(probe, "DD".repeat(16), 100L)
        assertTrue(hasReservation(probe, "dd".repeat(16)))
        release.invoke(probe, "dd".repeat(16))
        assertFalse(hasReservation(probe, "dd".repeat(16)))

        // An existing reservation above quota does not start new work
        // either: it waits for the bare prefix to drain first.
        val stale = newBudget()
        seed.invoke(stale, "aa".repeat(16), 700L * mib)
        seed.invoke(stale, "bb".repeat(16), 700L * mib)
        val staleFailure = assertThrows(IllegalStateException::class.java) {
            request(stale, "aa".repeat(16), 700L * mib, { false }, { false }, { false })
        }
        assertTrue(staleFailure.message.orEmpty().contains("manual review"))
        assertEquals(1400L * mib, reservedBytes(stale))

        // While drainable work exists the request waits, and a release lets
        // it proceed: only the admitted bytes are ever reserved.
        val draining = newBudget()
        seed.invoke(draining, "00".repeat(16), gib)
        var admitted = false
        val waiter = Thread {
            request(draining, "11".repeat(16), 100L, { false }, { true }, { false })
            admitted = true
        }.apply { isDaemon = true; start() }
        Thread.sleep(300)
        assertFalse("over-quota request must not slip through before a release", admitted)
        release.invoke(draining, "00".repeat(16))
        waiter.join(10_000)
        assertFalse("drainable wait must resolve", waiter.isAlive)
        assertTrue(admitted)
        assertEquals(100L, reservedBytes(draining))

        // With nothing drainable, one legal eviction makes room and the
        // request retries instead of failing: the victim slot frees first.
        val evicting = newBudget()
        seed.invoke(evicting, "aa".repeat(16), gib)
        request(evicting, "bb".repeat(16), 100L, { false }, { false }, { release.invoke(evicting, "aa".repeat(16)); true })
        assertEquals(100L, reservedBytes(evicting))
        assertEquals(1, reservationCount(evicting))

        // Single snapshot per iteration: drainability read true once, then
        // false. A double read would throw before ever evicting; the single
        // read waits once, retries, evicts for real and succeeds.
        val transitioning = newBudget()
        repeat(34) { index -> seed.invoke(transitioning, "%032x".format(index), 24L) }
        var drainReads = 0
        var evicted = false
        request(
            transitioning,
            "%032x".format(34),
            24L,
            { false },
            {
                drainReads++
                drainReads == 1
            },
            {
                release.invoke(transitioning, "%032x".format(0))
                evicted = true
                true
            },
        )
        assertTrue("eviction must have made room", evicted)
        assertEquals("drainability is snapshotted once per wait cycle", 2, drainReads)
        assertEquals(34, reservationCount(transitioning))
        assertEquals(34L * 24L, reservedBytes(transitioning))

        // The reservation map holds at most the 34-key recovery quota, old
        // plus new: the 35th distinct key is refused before any file exists.
        val many = newBudget()
        repeat(34) { index -> seed.invoke(many, "%032x".format(index), 24L) }
        assertEquals(34, reservationCount(many))
        val structural = assertThrows(IllegalStateException::class.java) {
            request(many, "%032x".format(34), 24L, { false }, { false }, { false })
        }
        assertTrue(structural.message.orEmpty().contains("manual review"))
        assertEquals("refused key pays no reservation", 34, reservationCount(many))

        // The seed path enforces the same 34-key quota on every call path:
        // the 35th inherited key is refused before producing, with no
        // deletion and no silent admission past the bound.
        fun seedChecked(budget: Any, key: String, size: Long) {
            try {
                seed.invoke(budget, key, size)
            } catch (failure: java.lang.reflect.InvocationTargetException) {
                throw failure.cause ?: failure
            }
        }
        val seedBound = newBudget()
        repeat(34) { index -> seedChecked(seedBound, "%032x".format(index), 24L) }
        assertEquals(34, reservationCount(seedBound))
        val seedFailure = assertThrows(IllegalStateException::class.java) {
            seedChecked(seedBound, "%032x".format(34), 24L)
        }
        assertTrue(seedFailure.message.orEmpty().contains("34-key recovery quota"))
        assertEquals("refused seed pays no reservation", 34, reservationCount(seedBound))
        seedChecked(seedBound, "%032x".format(0), 24L)
        assertEquals("re-seeding an existing key stays idempotent", 34, reservationCount(seedBound))
    }

    @Test
    fun farFutureCacheHoldsThirtyFourKeyQuotaThroughEvictionAndResume() {
        val configAssets = configAssetsOrSkip()
        // Sixty-four tiny objects in ekey order: the last 34 arrive as valid
        // inherited raws with an empty checkpoint — as after a
        // pointer-rollback reset, which wipes Data/checkpoint but not the
        // objects directory. The 34-key quota (old plus new) must hold for the
        // whole run: new keys evict far-future victims instead of piling a
        // 64-raw state that the next replan would have to reject.
        Fixture(cascObjectCount = 64).use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            val originalPlan = fixture.updater(paths, configAssets) { _, _, _, _ ->
                error("planning must remain metadata-only")
            }.plan(destination)
            val consent = ForeverClientUpdateConsent(
                originalPlan.planId,
                originalPlan.downloadBytes,
                originalPlan.peakAdditionalBytes,
                originalPlan.privateWorkBytes,
                originalPlan.destinationBytes,
            )
            // Run one: block all sixteen workers, cancel before any commit.
            // No files, no checkpoint — but the owned workspace exists.
            val started = CountDownLatch(16)
            val cancelNow = AtomicBoolean(false)
            val blockingFetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { _, _, _, _ ->
                    started.countDown()
                    while (!cancelNow.get()) Thread.sleep(25)
                    throw java.util.concurrent.CancellationException("synthetic cancel")
                }
            val canceller = Thread {
                if (!started.await(30, TimeUnit.SECONDS)) throw AssertionError("all sixteen prefetch workers must start")
                cancelNow.set(true)
            }.apply { isDaemon = true; start() }
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                fixture.updater(paths, configAssets, blockingFetch)
                    .execute(destination, originalPlan, consent, { cancelNow.get() })
            }
            canceller.join(30_000)
            assertNoLiveFetchWorkers("cancelled vacuum run")
            assertWorkspaceWritesSettled(paths)
            val objectsDir = workspaceObjectsDir(paths)
            assertTrue("vacuum run leaves no raws", objectsDir.listFiles()!!.isEmpty())

            // Plant 34 valid raws for the 34 largest ekeys: legitimate
            // far-future leftovers the seed inventories as disk debt.
            val farFuture = fixture.targetSpecs().map { it.ekey }.sorted().takeLast(34)
            assertEquals(34, farFuture.size)
            farFuture.forEach { key ->
                Files.write(File(objectsDir, "$key.blte").toPath(), fixture.rawFor(fixture.catalog.objectByKey(key)))
            }

            // Run two: fetch everything, crash after the tenth new commit.
            // New keys must evict far-future victims (never the object
            // currently needed nor checkpointed prefixes); leftovers must
            // never exceed 34 distinct keys, or the replan could not accept
            // its own state.
            val normalFetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { record, workDir, _, progress ->
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), fixture.rawFor(record))
                    progress(output.length())
                    output
                }
            val secondPlan = fixture.updater(paths, configAssets, normalFetch).plan(destination)
            val crashAtTen = AtomicBoolean(false)
            assertThrows(IllegalStateException::class.java) {
                fixture.updater(paths, configAssets, normalFetch).execute(
                    destination,
                    secondPlan,
                    consent,
                    { false },
                    { progress ->
                        if (progress.phase == ForeverClientUpdateProgress.Phase.DOWNLOADING &&
                            progress.completedObjects == 10L && crashAtTen.compareAndSet(false, true)
                        ) {
                            throw IllegalStateException("synthetic kill mid-prefetch")
                        }
                    },
                )
            }
            assertNoLiveFetchWorkers("crashed prefetch run")
            assertWorkspaceWritesSettled(paths)
            val leftoverKeys = objectsDir.listFiles()!!.map { it.name.substringBefore('.') }.distinct()
            assertTrue(
                "leftover raws must stay within the 34-key recovery quota, found ${leftoverKeys.size}",
                leftoverKeys.size <= 34,
            )
            // The replan must accept this self-generated state: no 35+ key
            // rejection of leftovers the run itself produced.
            val resumedUpdater = fixture.updater(paths, configAssets, normalFetch)
            val resumedPlan = resumedUpdater.plan(destination)
            val resumeCalls = AtomicInteger(0)
            val actualNetwork = AtomicLong(0L)
            val result = fixture.updater(paths, configAssets) { record, workDir, _, progress ->
                resumeCalls.incrementAndGet()
                val output = File(workDir, "${record.ekey}.blte")
                Files.write(output.toPath(), fixture.rawFor(record))
                progress(output.length())
                actualNetwork.addAndGet(output.length())
                output
            }.execute(
                destination,
                resumedPlan,
                ForeverClientUpdateConsent(
                    resumedPlan.planId,
                    resumedPlan.downloadBytes,
                    resumedPlan.peakAdditionalBytes,
                    resumedPlan.privateWorkBytes,
                    resumedPlan.destinationBytes,
                ),
            )
            assertEquals(10L, result.reusedObjects)
            assertEquals(54L, result.writtenObjects)
            assertEquals("fifty-four CASC misses plus the serial loose fetch", 55, resumeCalls.get())
            assertTrue(
                "resumed network must not exceed the consented remainder: ${actualNetwork.get()} vs ${resumedPlan.downloadBytes}",
                actualNetwork.get() <= resumedPlan.downloadBytes,
            )
            assertFalse(resumedUpdater.hasPendingTransaction(destination))
            assertFalse(File(paths.client, ".forever-update-staging").exists())
            assertNoLiveFetchWorkers("resumed run")
            ForeverCasc.verifyExisting(
                paths.client,
                fixture.targetSpecs().map { ForeverCasc.RuntimeObjectSpec(it.ekey, it.contentKey, it.decodedSize) },
                { false },
            )
        }
    }

    @Test
    fun serialCheckpointedLooseRawKeepsItsCreditWithoutRedownload() {
        val configAssets = configAssetsOrSkip()
        // Single-object fixture: the only CASC object shares its ekey with
        // the loose file, so every assertion is deterministic.
        Fixture(cascObjectCount = 1).use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            // Full install first: the client becomes the update source.
            val installUpdater = fixture.updater(paths, configAssets) { record, workDir, _, progress ->
                val output = File(workDir, "${record.ekey}.blte")
                Files.write(output.toPath(), fixture.rawFor(record))
                progress(output.length())
                output
            }
            val installPlan = installUpdater.plan(destination)
            installUpdater.execute(
                destination,
                installPlan,
                ForeverClientUpdateConsent(
                    installPlan.planId,
                    installPlan.downloadBytes,
                    installPlan.peakAdditionalBytes,
                    installPlan.privateWorkBytes,
                    installPlan.destinationBytes,
                ),
            )
            val directEkey = fixture.catalog.looseFiles().first { it.path == "Interface/patch.mpq" }.objectRecord.ekey
            val directSpec = fixture.targetSpecs().first { it.ekey == directEkey }
            val ownedAfterInstall = mapOf(
                ".build.info" to fingerprintOf(File(paths.client, ".build.info")),
                "Interface/patch.mpq" to fingerprintOf(File(paths.client, "Interface/patch.mpq")),
            )

            // Knock out the direct object in the installed source at its real
            // local-entry offset and drop the loose file: the next plan must
            // download exactly the direct object plus its loose copy,
            // serially, with no prefetch.
            val sortedSpecs = fixture.targetSpecs().map { it.ekey to it.encodedSize }.sortedBy { it.first }
            var payloadOffset = 480 + 30 + 5
            for ((key, size) in sortedSpecs) {
                if (key == directEkey) break
                payloadOffset = Math.addExact(payloadOffset, Math.addExact(30, size.toInt()))
            }
            val segment = File(File(paths.client, "Data/data"), "data.000")
            assertTrue(segment.isFile)
            val staged = segment.readBytes()
            staged[payloadOffset] = (staged[payloadOffset].toInt() xor 0xff).toByte()
            Files.write(segment.toPath(), staged)
            assertTrue(File(paths.client, "Interface/patch.mpq").delete())
            val damagedDestination = fixture.destination(
                paths,
                verifiedOwnedFiles = ownedAfterInstall.filterKeys { it != "Interface/patch.mpq" },
            )
            val damagePlan = fixture.updater(paths, configAssets) { _, _, _, _ ->
                error("planning must remain metadata-only")
            }.plan(damagedDestination)
            assertEquals(2 * directSpec.encodedSize, damagePlan.downloadBytes)
            val damageConsent = ForeverClientUpdateConsent(
                damagePlan.planId,
                damagePlan.downloadBytes,
                damagePlan.peakAdditionalBytes,
                damagePlan.privateWorkBytes,
                damagePlan.destinationBytes,
            )

            // Abort right after the durable checkpoint lands: the cancel
            // predicate reads the genuine checkpoint file, the fetch reports
            // full bytes normally, and the exact CancellationException proves
            // the transaction commit is never reached. The commit already
            // retired its own raw, so the workspace holds no leftovers — the
            // checkpoint alone is genuine here.
            val cancelAfterFirstCheckpoint = {
                val privateBase = File(paths.work, ".forever-client-update-work")
                val owner = privateBase.listFiles().orEmpty().firstOrNull { File(it, ".owner").isFile }
                owner?.let { File(it, ".forever-casc-resume-checkpoint").length() > 40L } == true
            }
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                fixture.updater(paths, configAssets) { record, workDir, _, progress ->
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), fixture.rawFor(record))
                    progress(output.length())
                    output
                }.execute(damagedDestination, damagePlan, damageConsent, cancelAfterFirstCheckpoint)
            }
            assertNoLiveFetchWorkers("cancelled serial run")
            assertWorkspaceWritesSettled(paths)
            val objectsDir = workspaceObjectsDir(paths)
            assertTrue("serial cancel leaves no crash-window raw by itself", objectsDir.listFiles()!!.isEmpty())
            val resumedUpdater = fixture.updater(paths, configAssets) { _, _, _, _ ->
                error("recovery must precede replanning")
            }
            assertEquals(ForeverClientRecoveryResult.RESUMABLE_STAGE_AVAILABLE, resumedUpdater.recover(destination).result)

            // State injection, not interception: represent the kill-9
            // filesystem image just before the raw delete — the checkpoint,
            // owner and receipt above are genuine from the aborted run; only
            // this one authentic raw (exact Transfer output bytes, verified
            // on admission) is placed by the test. No other file is touched.
            val planted = File(objectsDir, "$directEkey.blte")
            Files.write(planted.toPath(), fixture.rawFor(fixture.catalog.objectByKey(directEkey)))
            assertEquals(listOf("$directEkey.blte"), objectsDir.listFiles()!!.map { it.name }.sorted())

            // The replan subtracts the persisting shared raw from the loose
            // leg (and the checkpoint delta from CASC): zero network left to
            // consent, with no whole-client redownload.
            val replanned = fixture.updater(paths, configAssets) { _, _, _, _ ->
                error("planning must remain metadata-only")
            }.plan(damagedDestination)
            assertEquals(0L, replanned.downloadBytes)

            // The real Transfer serves that persisting valid raw without
            // touching the network: a factory that fails on any connection
            // proves the cache hit instead of promising a transaction commit
            // that the known identical-content edge refuses.
            val progressBytes = AtomicLong(0L)
            val cached = ForeverClientUpdateTransfer { _ ->
                throw IllegalStateException("network must not be used for a persisted valid raw")
            }.fetch(fixture.catalog.objectByKey(directEkey), objectsDir, { false }) { bytes ->
                progressBytes.addAndGet(bytes)
            }
            assertEquals(planted, cached)
            assertEquals(0L, progressBytes.get())
            assertNoLiveFetchWorkers("serial cache-hit proof")
        }
    }

    @Test
    fun lookaheadThirtyTwoKeepsSixteenWorkersOverlappingBeyondHeadCommit() {
        val configAssets = configAssetsOrSkip()
        // Thirty-two CASC objects need the full 32-task window
        // (consumer 1 + queue 30 + producer 1): the first-started fetch waits
        // for the other thirty-one to finish, so a 16-deep window could never
        // release it. Sixteen workers must overlap, exactly thirty-two fetch
        // tasks run (no 64), and at most thirty-two distinct keys sit private.
        Fixture(cascObjectCount = 32).use { fixture ->
            val paths = fixture.makePaths()
            val active = AtomicInteger(0)
            val maxActive = AtomicInteger(0)
            val arrivals = java.util.Collections.synchronizedList(ArrayList<String>())
            val finishOrder = java.util.Collections.synchronizedList(ArrayList<String>())
            val allStarted = CountDownLatch(16)
            val restFinished = CountDownLatch(31)
            val fetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { record, workDir, _, progress ->
                    active.incrementAndGet()
                    try {
                        val myArrival = synchronized(arrivals) {
                            arrivals.add(record.ekey)
                            arrivals.size - 1
                        }
                        allStarted.countDown()
                        assertTrue(
                            "prefetch workers must overlap deterministically",
                            allStarted.await(30, TimeUnit.SECONDS),
                        )
                        maxActive.updateAndGet { current -> maxOf(current, active.get()) }
                        if (myArrival == 0) {
                            assertTrue(
                                "first-started fetch must wait for every other fetch to finish",
                                restFinished.await(30, TimeUnit.SECONDS),
                            )
                        }
                        val output = File(workDir, "${record.ekey}.blte")
                        Files.write(output.toPath(), fixture.rawFor(record))
                        progress(output.length())
                        synchronized(finishOrder) { finishOrder.add(record.ekey) }
                        if (myArrival > 0) restFinished.countDown()
                        output
                    } finally {
                        active.decrementAndGet()
                    }
                }
            val updater = fixture.updater(paths, configAssets, fetch)
            val destination = fixture.destination(paths)
            val plan = updater.plan(destination)
            val consent = ForeverClientUpdateConsent(
                plan.planId,
                plan.downloadBytes,
                plan.peakAdditionalBytes,
                plan.privateWorkBytes,
                plan.destinationBytes,
            )
            val result = updater.execute(destination, plan, consent)

            assertEquals(
                "all sixteen prefetch workers must overlap (observed ${maxActive.get()})",
                16,
                maxActive.get(),
            )
            // Thirty-two CASC fetches plus the serial loose-file fetch
            // afterwards: thirty-three calls, thirty-two distinct keys (the
            // loose record reuses the direct CASC object's admitted ekey).
            assertEquals(33, arrivals.size)
            assertEquals(32, arrivals.distinct().size)
            assertEquals(33, finishOrder.size)
            assertEquals(
                "first-started fetch must finish last among the CASC fetches",
                31,
                finishOrder.indexOf(arrivals[0]),
            )
            assertEquals(32, result.writtenObjects)
            assertFalse(updater.hasPendingTransaction(destination))
            assertNoLiveFetchWorkers("completed prefetch run")
            ForeverCasc.verifyExisting(
                paths.client,
                fixture.targetSpecs().map { ForeverCasc.RuntimeObjectSpec(it.ekey, it.contentKey, it.decodedSize) },
                { false },
            )
        }
    }

    @Test
    fun twoCrashesRetireCheckpointedRawsOnlyAfterCascVerifyAndResumeCompletes() {
        val configAssets = configAssetsOrSkip()
        Fixture().use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            val originalPlan = fixture.updater(paths, configAssets) { _, _, _, _ ->
                error("planning must remain metadata-only")
            }.plan(destination)
            val consent = ForeverClientUpdateConsent(
                originalPlan.planId,
                originalPlan.downloadBytes,
                originalPlan.peakAdditionalBytes,
                originalPlan.privateWorkBytes,
                originalPlan.destinationBytes,
            )
            val specs = fixture.targetSpecs()
            val headEkey = specs.minByOrNull { it.ekey }!!.ekey
            val headSize = specs.first { it.ekey == headEkey }.encodedSize
            val otherEkey = specs.first { it.ekey != headEkey }.ekey
            val otherSize = specs.first { it.ekey != headEkey }.encodedSize
            val normalFetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { record, workDir, _, progress ->
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), fixture.rawFor(record))
                    progress(output.length())
                    output
                }

            // First crash: only the head is served, its checkpoint is durable
            // and its raw survives the kill before the owned delete.
            val headOnlyFetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { record, workDir, _, progress ->
                    if (record.ekey != headEkey) throw java.util.concurrent.CancellationException("no file for non-head object")
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), fixture.rawFor(record))
                    progress(output.length())
                    output
                }
            val firstCrash = AtomicBoolean(false)
            assertThrows(IllegalStateException::class.java) {
                fixture.updater(paths, configAssets, headOnlyFetch).execute(
                    destination,
                    originalPlan,
                    consent,
                    { false },
                    { progress ->
                        if (progress.phase == ForeverClientUpdateProgress.Phase.DOWNLOADING &&
                            progress.completedObjects == 1L && firstCrash.compareAndSet(false, true)
                        ) {
                            throw IllegalStateException("synthetic first kill after durable checkpoint")
                        }
                    },
                )
            }
            assertNoLiveFetchWorkers("first crashed run")
            assertWorkspaceWritesSettled(paths)
            assertEquals(
                listOf("$headEkey.blte"),
                workspaceObjectsDir(paths).listFiles()!!.map { it.name }.sorted(),
            )

            // Second crash: the head is checkpointed (bare spec), the writer
            // re-verifies its staged CASC copy and only then is its surviving
            // raw retired inline after the yield; the second object commits
            // and its own raw survives this kill in turn.
            val secondCrash = AtomicBoolean(false)
            val secondCalls = AtomicInteger(0)
            val secondPlan = fixture.updater(paths, configAssets, normalFetch).plan(destination)
            assertEquals(originalPlan.downloadBytes - headSize, secondPlan.downloadBytes)
            assertThrows(IllegalStateException::class.java) {
                fixture.updater(paths, configAssets) { record, workDir, cancelled, progress ->
                    secondCalls.incrementAndGet()
                    normalFetch(record, workDir, cancelled, progress)
                }.execute(
                    destination,
                    secondPlan,
                    consent,
                    { false },
                    { progress ->
                        if (progress.phase == ForeverClientUpdateProgress.Phase.DOWNLOADING &&
                            progress.completedObjects == 1L && secondCrash.compareAndSet(false, true)
                        ) {
                            throw IllegalStateException("synthetic second kill after durable checkpoint")
                        }
                    },
                )
            }
            assertNoLiveFetchWorkers("second crashed run")
            assertWorkspaceWritesSettled(paths)
            assertEquals("one CASC fetch before the second kill", 1, secondCalls.get())
            assertEquals(
                "head raw retired only after its staged CASC copy was re-verified; second raw survives its own crash window",
                listOf("$otherEkey.blte"),
                workspaceObjectsDir(paths).listFiles()!!.map { it.name }.sorted(),
            )

            // Both checkpointed objects are credited exactly once; the loose
            // copy keeps its full declared network cost.
            val resumedUpdater = fixture.updater(paths, configAssets, normalFetch)
            val resumedPlan = resumedUpdater.plan(destination)
            assertEquals(originalPlan.downloadBytes - headSize - otherSize, resumedPlan.downloadBytes)
            val actualNetwork = AtomicLong(0L)
            val resumedConsent = ForeverClientUpdateConsent(
                resumedPlan.planId,
                resumedPlan.downloadBytes,
                resumedPlan.peakAdditionalBytes,
                resumedPlan.privateWorkBytes,
                resumedPlan.destinationBytes,
            )
            val result = fixture.updater(paths, configAssets) { record, workDir, _, progress ->
                val output = File(workDir, "${record.ekey}.blte")
                Files.write(output.toPath(), fixture.rawFor(record))
                progress(output.length())
                actualNetwork.addAndGet(output.length())
                output
            }.execute(destination, resumedPlan, resumedConsent)
            assertEquals(2L, result.reusedObjects)
            assertEquals(0L, result.writtenObjects)
            assertTrue(
                "resumed network must not exceed the consented remainder: ${actualNetwork.get()} vs ${resumedPlan.downloadBytes}",
                actualNetwork.get() <= resumedPlan.downloadBytes,
            )
            assertFalse(resumedUpdater.hasPendingTransaction(destination))
            assertFalse(File(paths.client, ".forever-update-staging").exists())
            assertNoLiveFetchWorkers("resumed run")
            ForeverCasc.verifyExisting(
                paths.client,
                fixture.targetSpecs().map { ForeverCasc.RuntimeObjectSpec(it.ekey, it.contentKey, it.decodedSize) },
                { false },
            )
        }
    }

    private fun fingerprintOf(file: File): FileFingerprint {
        check(file.isFile) { "expected an installed file for the ownership allowlist: ${file.path}" }
        return FileFingerprint(file.length(), sha256(file.readBytes()))
    }

    private fun assertNoLiveFetchWorkers(context: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            val live = Thread.getAllStackTraces().keys
                .filter { it.name == "forever-fetch" && it.isAlive }
            if (live.isEmpty()) return
            if (System.nanoTime() >= deadline) {
                throw AssertionError("late fetch workers still alive after $context: $live")
            }
            Thread.sleep(50)
        }
    }

    private fun assertWorkspaceWritesSettled(paths: Paths) {
        fun snapshot(): Map<String, Long> {
            val base = File(paths.work, ".forever-client-update-work")
            if (!base.isDirectory) return emptyMap()
            val entries = LinkedHashMap<String, Long>()
            base.walkTopDown()
                .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                .forEach { file -> entries[base.toPath().relativize(file.toPath()).toString()] = file.length() }
            return entries
        }
        val before = snapshot()
        Thread.sleep(500)
        assertEquals("late worker writes after teardown", before, snapshot())
    }

    private fun workspaceObjectsDir(paths: Paths): File {
        val privateBase = File(paths.work, ".forever-client-update-work")
        val owner = privateBase.listFiles().orEmpty().firstOrNull { File(it, ".owner").isFile }
            ?: throw AssertionError("expected an owned private updater workspace")
        return File(owner, "objects")
    }

    private fun configAssetsOrSkip(): Map<String, File> {
        val configured = System.getProperty("wowforever.clientConfigAssets")
        val directory = File(configured ?: "/home/adrian/wowforever-artifacts/arm64-prototype/client-build70425-admission/metadata")
        val build = File(directory, "build-config-${ForeverClientUpdateCatalog.PINNED_BUILD_CONFIG_HASH}.txt")
        val cdn = File(directory, "cdn-config-${ForeverClientUpdateCatalog.PINNED_CDN_CONFIG_HASH}.txt")
        assumeTrue("small admitted TACT config assets are not available", build.isFile && cdn.isFile)
        return mapOf(
            ForeverClientUpdateCatalog.PINNED_BUILD_CONFIG_HASH to build,
            ForeverClientUpdateCatalog.PINNED_CDN_CONFIG_HASH to cdn,
        )
    }

    // ------------------------------------------------------------------
    // Sealed-prefix overlap (C+A) through the update engine: sourceless
    // resume with a checkpointed prefix and an explicit reader factory
    // downloads and commits the new tail while one sequential verifier
    // proves the prefix; nothing is published before the joint barrier.
    // ------------------------------------------------------------------

    private class UpdaterFakeReader(
        private val cancelled: () -> Boolean = { false },
        private val onEnsure: (() -> Unit)? = null,
    ) : CascReplayReader {
        private var file: File? = null
        override fun ensureSegment(file: File) {
            if (cancelled()) throw java.util.concurrent.CancellationException("cancelled")
            onEnsure?.invoke()
            if (Files.isSymbolicLink(file.toPath())) throw IllegalStateException("FAKE staged segment is a symlink")
            if (!file.isFile) throw IllegalStateException("FAKE staged segment is not a regular file")
            this.file = file
        }

        override fun checkBinding(endExclusive: Long) {
            if (cancelled()) throw java.util.concurrent.CancellationException("cancelled")
            val bound = file ?: throw IllegalStateException("FAKE replay reader is closed")
            if (Files.isSymbolicLink(bound.toPath()) || !bound.isFile) throw IllegalStateException("FAKE staged binding changed")
            if (bound.length() < endExclusive) throw IllegalStateException("FAKE staged segment no longer covers its object end")
        }

        override fun checkSealedBinding(exactSize: Long) {
            if (cancelled()) throw java.util.concurrent.CancellationException("cancelled")
            val bound = file ?: throw IllegalStateException("FAKE replay reader is closed")
            if (Files.isSymbolicLink(bound.toPath()) || !bound.isFile) throw IllegalStateException("FAKE staged binding changed")
            if (bound.length() != exactSize) throw IllegalStateException("FAKE sealed segment size is not its frozen group end")
        }

        override fun readAt(buffer: ByteArray, off: Int, len: Int, position: Long): Int {
            if (off < 0 || len < 0 || off > buffer.size || len > buffer.size - off) {
                throw IndexOutOfBoundsException("FAKE replay read is outside its buffer")
            }
            if (position < 0) throw IllegalArgumentException("negative FAKE replay read position")
            val bound = file ?: throw IllegalStateException("FAKE replay reader is closed")
            if (len == 0) return 0
            if (cancelled()) throw java.util.concurrent.CancellationException("cancelled")
            java.io.RandomAccessFile(bound, "r").use { input ->
                input.seek(position)
                val count = input.read(buffer, off, len)
                return if (count < 0) 0 else count
            }
        }

        override fun close() {
            file = null
        }
    }

    private fun consentFor(plan: ForeverClientUpdatePlan) = ForeverClientUpdateConsent(
        plan.planId,
        plan.downloadBytes,
        plan.peakAdditionalBytes,
        plan.privateWorkBytes,
        plan.destinationBytes,
    )

    private fun workspaceRoot(paths: Paths): File? {
        val base = File(paths.work, ".forever-client-update-work")
        if (!base.isDirectory) return null
        return base.listFiles()?.firstOrNull { File(it, ".owner").isFile }
    }

    /** Snapshot the tiny fixture workspace bytes to prove the guard is read-only. */
    private fun workspaceSnapshot(paths: Paths): Map<String, List<Byte>> {
        val base = File(paths.work, ".forever-client-update-work")
        if (!base.isDirectory) return emptyMap()
        val entries = LinkedHashMap<String, List<Byte>>()
        base.walkTopDown()
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .forEach { file -> entries[base.toPath().relativize(file.toPath()).toString()] = file.readBytes().toList() }
        return entries
    }

    private fun checkpointLength(paths: Paths): Long {
        val root = workspaceRoot(paths) ?: return 0L
        val checkpoint = File(root, ".forever-casc-resume-checkpoint")
        return if (checkpoint.isFile) checkpoint.length() else 0L
    }

    private fun objectsDir(paths: Paths): File = File(requireNotNull(workspaceRoot(paths)) { "no resumable workspace yet" }, "objects")

    /** Bounded join: a deadlock must fail the test, never hang the suite. */
    private fun joinBounded(thread: Thread, label: String) {
        thread.join(180_000)
        assertFalse("deadlock: $label did not finish", thread.isAlive)
    }

    @Test
    fun overlapBlockedVerifierStillCommitsNewTailViaIndividualProofs() {
        // The contract case: all 34 quota keys are checkpointed leftover raws
        // near the end of the old prefix, and the verifier is parked at its
        // very first segment bind. The 35-object new tail must still commit —
        // each blocked request frees budget with an individual full-strength
        // staged-copy proof on its own reader, never by waiting for a group
        // ack that is a whole slow group away. The V block is scoped to the
        // verifier thread only, so proof readers pass through.
        val configAssets = configAssetsOrSkip()
        Fixture(cascObjectCount = 69).use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            val rawBytes = HashMap<String, ByteArray>()
            val fetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { record, workDir, _, progress ->
                    val bytes = fixture.rawFor(record)
                    rawBytes[record.ekey] = bytes
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), bytes)
                    progress(output.length())
                    output
                }
            val firstUpdater = fixture.updater(paths, configAssets, fetch)
            val plan = firstUpdater.plan(destination)
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                firstUpdater.execute(destination, plan, consentFor(plan), { checkpointLength(paths) >= 40L + 34L * 72L })
            }
            assertEquals(34L, (checkpointLength(paths) - 40L) / 72L)
            // Leftover raws for EXACTLY the checkpointed prefix, in walk
            // (ekey) order: the first 34 admitted objects hold the quota.
            // Insertion order differs from walk order, so an unsorted take
            // would plant the wrong keys and the budget would self-balance
            // through commit releases without ever proving anything.
            val prefixKeys = fixture.targetSpecs().sortedBy { it.ekey }.take(34).map { it.ekey }.toSet()
            prefixKeys.forEach { key ->
                val bytes = fixture.rawFor(fixture.catalog.objectByKey(key))
                Files.write(File(objectsDir(paths), "$key.blte").toPath(), bytes)
            }
            // Drop prefetch tail debt beyond the checkpoint: only the
            // checkpointed prefix holds quota debt in this scenario; the
            // dropped keys refetch as ordinary new tail below.
            objectsDir(paths).listFiles()!!.forEach { leftover ->
                val name = Regex("([0-9a-f]{32})\\.(blte|part)").matchEntire(leftover.name)
                if (name != null && name.groupValues[1] !in prefixKeys) {
                    assertTrue("only tail debt is dropped", leftover.delete())
                }
            }
            val vGate = CountDownLatch(1)
            val vEntered = CountDownLatch(1)
            val created = AtomicInteger(0)
            val resumedUpdater = fixture.updater(paths, configAssets, fetch)
            resumedUpdater.replayReaderFactory = { cancelled ->
                created.incrementAndGet()
                if (Thread.currentThread().name == "forever-prefix-verify") {
                    UpdaterFakeReader(cancelled) {
                        vEntered.countDown()
                        if (!vGate.await(180, TimeUnit.SECONDS)) throw IllegalStateException("test V gate timed out")
                    }
                } else {
                    UpdaterFakeReader(cancelled)
                }
            }
            val resumedPlan = resumedUpdater.plan(destination)
            val newCommitted = CountDownLatch(1)
            var maxCommitted = 0L
            var result: ForeverClientUpdateResult? = null
            val run = Thread {
                result = resumedUpdater.execute(
                    destination,
                    resumedPlan,
                    consentFor(resumedPlan),
                    { false },
                    { progress ->
                        if (progress.phase == ForeverClientUpdateProgress.Phase.DOWNLOADING) {
                            maxCommitted = maxOf(maxCommitted, progress.completedObjects)
                            if (maxCommitted >= 35L) newCommitted.countDown()
                        }
                    },
                )
            }
            run.isDaemon = true
            run.start()
            assertTrue("verifier must park at its first segment bind", vEntered.await(180, TimeUnit.SECONDS))
            assertTrue("35 new objects must commit while V is still blocked", newCommitted.await(180, TimeUnit.SECONDS))
            assertEquals("all 35 new commits durable with the gate closed", 69L, (checkpointLength(paths) - 40L) / 72L)
            // The gate never opened above: every freed budget unit came from
            // an individual full-strength proof on its own reader, never from
            // a group ack. At least the first blocked request must have proven
            // (the quota starts full); the rest flow via commit releases.
            // Exact proof counts depend on fetch/commit interleaving, so only
            // the lower bound is asserted; exactly-once is covered by the
            // coordinator race test.
            assertTrue("at least one individual proof plus the verifier ran", created.get() >= 2)
            vGate.countDown()
            joinBounded(run, "proof-driven overlap resume")
            val done = requireNotNull(result)
            assertEquals(34L, done.reusedObjects)
            assertEquals(35L, done.writtenObjects)
            assertTrue("the private workspace is fully cleaned after success", workspaceRoot(paths) == null)
            assertFalse(resumedUpdater.hasPendingTransaction(destination))
        }
    }

    @Test
    fun overlapSourcelessResumeVerifiesPrefixWhileDownloadingTail() {
        val configAssets = configAssetsOrSkip()
        Fixture(cascObjectCount = 10).use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            val fetched = AtomicInteger(0)
            val fetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { record, workDir, _, progress ->
                    fetched.incrementAndGet()
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), fixture.rawFor(record))
                    progress(output.length())
                    output
                }
            // Run 1 with the factory set but an empty checkpoint: the initial
            // install keeps the old writer with no verifier thread.
            val created = AtomicInteger(0)
            val firstUpdater = fixture.updater(paths, configAssets, fetch)
            firstUpdater.replayReaderFactory = { _ -> created.incrementAndGet(); UpdaterFakeReader() }
            val plan = firstUpdater.plan(destination)
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                firstUpdater.execute(destination, plan, consentFor(plan), { checkpointLength(paths) > 40L })
            }
            assertTrue(fetched.get() >= 1)
            assertEquals("empty prefix must not spawn a verifier", 0, created.get())
            val prefixObjects = ((checkpointLength(paths) - 40L) / 72L)
            assertTrue("interrupted run must leave a checkpointed prefix", prefixObjects >= 1)

            // Resume with the factory armed: the new tail downloads and
            // commits while the verifier proves the sealed prefix; the
            // barrier admits the old metadata only after proof.
            val resumedUpdater = fixture.updater(paths, configAssets, fetch)
            resumedUpdater.replayReaderFactory = { _ -> created.incrementAndGet(); UpdaterFakeReader() }
            val resumedPlan = resumedUpdater.plan(destination)
            val before = fetched.get()
            var result: ForeverClientUpdateResult? = null
            val run = Thread { result = resumedUpdater.execute(destination, resumedPlan, consentFor(resumedPlan)) }
            run.isDaemon = true
            run.start()
            joinBounded(run, "overlap resume")
            val done = requireNotNull(result)
            assertTrue("verifier must have run for the non-empty prefix", created.get() > 0)
            assertEquals(prefixObjects, done.reusedObjects)
            assertEquals(resumedPlan.totalObjects - prefixObjects, done.writtenObjects)
            assertTrue("resume must fetch the missing tail", fetched.get() > before)
            assertFalse(resumedUpdater.hasPendingTransaction(destination))
        }
    }

    @Test
    fun overlapStaysOffWithoutFactoryOrPrefix() {
        val configAssets = configAssetsOrSkip()
        Fixture(cascObjectCount = 4).use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            val fetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { record, workDir, _, progress ->
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), fixture.rawFor(record))
                    progress(output.length())
                    output
                }
            // Fresh install with the factory set: no prefix, no verifier.
            val created = AtomicInteger(0)
            val installer = fixture.updater(paths, configAssets, fetch)
            installer.replayReaderFactory = { _ -> created.incrementAndGet(); UpdaterFakeReader() }
            val installPlan = installer.plan(destination)
            installer.execute(destination, installPlan, consentFor(installPlan))
            assertEquals("initial install must not spawn a verifier", 0, created.get())

            // Sourced/resume path without a factory keeps the old serial
            // replay: a partial checkpoint resumes and completes.
            val resumePaths = fixture.makePaths("resume-nofactory")
            val resumeDestination = fixture.destination(resumePaths)
            val resumeUpdater = fixture.updater(resumePaths, configAssets, fetch)
            val resumePlan = resumeUpdater.plan(resumeDestination)
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                resumeUpdater.execute(resumeDestination, resumePlan, consentFor(resumePlan), { checkpointLength(resumePaths) > 40L })
            }
            val stalePlan = resumePlan
            val resumeUpdater2 = fixture.updater(resumePaths, configAssets, fetch)
            // No factory: old checkpointed replay path, factory never consulted.
            val resumedPlan = resumeUpdater2.plan(resumeDestination)
            val staleFetches = AtomicInteger(0)
            val guardUpdater = fixture.updater(resumePaths, configAssets) { record, workDir, cancelled, progress ->
                staleFetches.incrementAndGet()
                fetch(record, workDir, cancelled, progress)
            }
            assertThrows(IllegalStateException::class.java) {
                guardUpdater.execute(resumeDestination, stalePlan, consentFor(stalePlan))
            }
            assertEquals("a changed plan must be rejected before new fetches", 0, staleFetches.get())
            val result = resumeUpdater2.execute(resumeDestination, resumedPlan, consentFor(resumedPlan))
            assertTrue(result.writtenObjects > 0)
            assertFalse(resumeUpdater2.hasPendingTransaction(resumeDestination))
        }
    }

    @Test
    fun overlapThirtyFourCheckpointRawsResumeWithoutDeadlock() {
        val configAssets = configAssetsOrSkip()
        Fixture(cascObjectCount = 40).use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            val rawBytes = HashMap<String, ByteArray>()
            val fetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { record, workDir, _, progress ->
                    val bytes = fixture.rawFor(record)
                    rawBytes[record.ekey] = bytes
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), bytes)
                    progress(output.length())
                    output
                }
            val firstUpdater = fixture.updater(paths, configAssets, fetch)
            val plan = firstUpdater.plan(destination)
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                firstUpdater.execute(destination, plan, consentFor(plan), { checkpointLength(paths) >= 40L + 34L * 72L })
            }
            val prefixObjects = ((checkpointLength(paths) - 40L) / 72L)
            assertEquals("interrupted run must checkpoint exactly the quota", 34L, prefixObjects)
            // Leftover raws for EXACTLY the checkpointed prefix, in walk
            // (ekey) order. Insertion order differs from walk order, so an
            // unsorted take would plant the wrong keys.
            val prefixKeys = fixture.targetSpecs().sortedBy { it.ekey }.take(34).map { it.ekey }.toSet()
            prefixKeys.forEach { key ->
                val bytes = fixture.rawFor(fixture.catalog.objectByKey(key))
                Files.write(File(objectsDir(paths), "$key.blte").toPath(), bytes)
            }
            // Drop prefetch tail debt beyond the checkpoint so the plan sees
            // exactly the bounded checkpointed debt, not an over-quota mix.
            objectsDir(paths).listFiles()!!.forEach { leftover ->
                val name = Regex("([0-9a-f]{32})\\.(blte|part)").matchEntire(leftover.name)
                if (name != null && name.groupValues[1] !in prefixKeys) {
                    assertTrue("only tail debt is dropped", leftover.delete())
                }
            }
            val created = AtomicInteger(0)
            val resumedUpdater = fixture.updater(paths, configAssets, fetch)
            resumedUpdater.replayReaderFactory = { _ -> created.incrementAndGet(); UpdaterFakeReader() }
            val resumedPlan = resumedUpdater.plan(destination)
            var result: ForeverClientUpdateResult? = null
            val run = Thread { result = resumedUpdater.execute(destination, resumedPlan, consentFor(resumedPlan)) }
            run.isDaemon = true
            run.start()
            joinBounded(run, "34-key overlap resume")
            val done = requireNotNull(result)
            assertTrue(created.get() > 0)
            assertEquals(prefixObjects, done.reusedObjects)
            assertTrue("the private workspace is fully cleaned after success", workspaceRoot(paths) == null)
            assertFalse(resumedUpdater.hasPendingTransaction(destination))
        }
    }

    @Test
    fun overlapPrivateCacheRejectsUnownedEntriesWithoutDeleting() {
        val configAssets = configAssetsOrSkip()
        Fixture(cascObjectCount = 4).use { fixture ->
            val paths = fixture.makePaths()
            val destination = fixture.destination(paths)
            val fetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File =
                { record, workDir, _, progress ->
                    val output = File(workDir, "${record.ekey}.blte")
                    Files.write(output.toPath(), fixture.rawFor(record))
                    progress(output.length())
                    output
                }
            val updater = fixture.updater(paths, configAssets, fetch)
            val plan = updater.plan(destination)
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                updater.execute(destination, plan, consentFor(plan), { checkpointLength(paths) > 40L })
            }
            val dir = objectsDir(paths)
            // Oversized raw beyond its admitted encoded bound.
            val spec = fixture.targetSpecs().first()
            val oversized = File(dir, "${spec.ekey}.part")
            Files.write(oversized.toPath(), ByteArray((spec.encodedSize + 1).toInt()))
            val resumeUpdater = fixture.updater(paths, configAssets, fetch)
            val resumedPlan = resumeUpdater.plan(destination)
            val oversizedError = assertThrows(IllegalStateException::class.java) {
                resumeUpdater.execute(destination, resumedPlan, consentFor(resumedPlan))
            }
            assertTrue("oversized debt must fail closed: ${oversizedError.message}", oversizedError.message.orEmpty().contains("admitted"))
            assertTrue("unowned debt is never deleted", oversized.isFile)
            assertTrue(oversized.delete())

            // Unknown filename.
            val foreign = File(dir, "not-a-casc-key.blte")
            Files.write(foreign.toPath(), byteArrayOf(1, 2, 3))
            val unknownError = assertThrows(IllegalStateException::class.java) {
                fixture.updater(paths, configAssets, fetch).execute(destination, resumedPlan, consentFor(resumedPlan))
            }
            assertTrue("unknown debt must fail closed: ${unknownError.message}", unknownError.message.orEmpty().contains("unrecognized"))
            assertTrue("foreign files are never deleted", foreign.isFile)
            assertTrue(foreign.delete())

            // Symlink entry (dangling, so it is the only bad entry).
            val link = File(dir, "${spec.ekey}.blte")
            try {
                Files.createSymbolicLink(link.toPath(), Path.of("nowhere-target.blte"))
            } catch (_: UnsupportedOperationException) {
                return
            }
            val linkError = assertThrows(IllegalStateException::class.java) {
                fixture.updater(paths, configAssets, fetch).execute(destination, resumedPlan, consentFor(resumedPlan))
            }
            assertTrue("symlink debt must fail closed", linkError.message.orEmpty().contains("unsafe") || linkError.message.orEmpty().contains("symlink"))
            assertTrue("symlinks are never deleted", Files.isSymbolicLink(link.toPath()))
        }
    }

    private data class Paths(val client: File, val privateRoot: File, val work: File, val journal: File)

    private class Fixture(
        directEncodedSizeOverride: Long? = null,
        val version: String = "1.60.1.70425",
        private val buildConfigContents: ByteArray? = null,
        private val cdnConfigContents: ByteArray? = null,
        private val cascObjectCount: Int = 2,
    ) : AutoCloseable {
        init {
            require(cascObjectCount in 1..96) { "synthetic CASC object count is outside the small-fixture bound" }
        }
        private val root = Files.createTempDirectory("client-updater-engine-test-").toFile()
        private val database = File(root, "synthetic-catalog.sqlite3")
        val directDecoded = "synthetic-loose".toByteArray()
        private val rangeDecoded = "synthetic-range".toByteArray()
        private val directRaw = blteRaw(directDecoded)
        private val rangeRaw = blteRaw(rangeDecoded)
        val directEncodedSize = directEncodedSizeOverride ?: directRaw.size.toLong()
        val rangeEncodedSize = rangeRaw.size.toLong()
        private val directEkey = ekey(directRaw)
        private val rangeEkey = ekey(rangeRaw)
        private val directCkey = md5(directDecoded)
        private val rangeCkey = md5(rangeDecoded)
        private val extraDecoded: List<ByteArray> = (2 until cascObjectCount).map { "synthetic-extra-$it".toByteArray() }
        private val extraRaw: List<ByteArray> = extraDecoded.map { blteRaw(it) }
        private val extraEkey: List<String> = extraRaw.map { ekey(it) }
        private val extraCkey: List<String> = extraDecoded.map { md5(it) }
        private val rawByKey = mapOf(directEkey to directRaw, rangeEkey to rangeRaw) +
            extraEkey.mapIndexed { index, key -> key to extraRaw[index] }.toMap()
        val buildConfigHash = buildConfigContents?.let(::md5) ?: ForeverClientUpdateCatalog.PINNED_BUILD_CONFIG_HASH
        val cdnConfigHash = cdnConfigContents?.let(::md5) ?: ForeverClientUpdateCatalog.PINNED_CDN_CONFIG_HASH
        val catalog: ForeverClientUpdateCatalog

        init {
            val db = SQLiteDatabase.openOrCreateDatabase(database, null)
            try {
                db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                db.execSQL(
                    "CREATE TABLE objects (ekey BLOB PRIMARY KEY CHECK(length(ekey)=16), ckey BLOB NOT NULL CHECK(length(ckey)=16), " +
                        "encoded_size INTEGER NOT NULL CHECK(encoded_size>0), decoded_size INTEGER NOT NULL CHECK(decoded_size>=0), " +
                        "source_kind TEXT NOT NULL CHECK(source_kind IN ('direct','range')), source_hash BLOB NOT NULL CHECK(length(source_hash)=16), " +
                        "source_offset INTEGER NOT NULL CHECK(source_offset>=0), source_length INTEGER NOT NULL CHECK(source_length=encoded_size), " +
                        "CHECK((source_kind='direct' AND source_hash=ekey AND source_offset=0) OR source_kind='range')) WITHOUT ROWID",
                )
                db.execSQL(
                    "CREATE TABLE loose (path TEXT PRIMARY KEY, ekey BLOB NOT NULL CHECK(length(ekey)=16), ckey BLOB NOT NULL CHECK(length(ckey)=16), " +
                        "encoded_size INTEGER NOT NULL CHECK(encoded_size>0), decoded_size INTEGER NOT NULL CHECK(decoded_size>=0), " +
                        "FOREIGN KEY(ekey) REFERENCES objects(ekey)) WITHOUT ROWID",
                )
                insertObject(db, directEkey, directCkey, directEncodedSize, directDecoded.size.toLong(), "direct", directEkey, 0)
                insertObject(db, rangeEkey, rangeCkey, rangeEncodedSize, rangeDecoded.size.toLong(), "range", "33".repeat(16), 50)
                extraEkey.forEachIndexed { index, key ->
                    insertObject(db, key, extraCkey[index], extraRaw[index].size.toLong(), extraDecoded[index].size.toLong(), "direct", key, 0)
                }
                db.insertOrThrow("loose", null, ContentValues().apply {
                    put("path", "Interface/patch.mpq")
                    put("ekey", directEkey.decodeHex())
                    put("ckey", directCkey.decodeHex())
                    put("encoded_size", directEncodedSize)
                    put("decoded_size", directDecoded.size.toLong())
                })
                metadataValues().forEach { (key, value) ->
                    db.insertOrThrow("metadata", null, ContentValues().apply { put("key", key); put("value", value) })
                }
                db.execSQL("DROP TABLE IF EXISTS android_metadata")
            } finally {
                db.close()
            }
            val admission = JSONObject()
                .put("schema", "wowforever.client-target").put("schema_version", 1)
                .put("product", "wow_classic_beta").put("region", "us")
                .put("platform", "Windows").put("architecture", "arm64").put("locale", "enUS")
                .put("version", version).put("build_id", version.substringAfterLast('.').toLong())
                .put("launcher_version_code", 80).put("launcher_version_name", "engine-test")
                .put("artifact_subdir", "client-updater-packaged-catalog/${version.substringAfterLast('.')}")
                .put("build_config", JSONObject().put("hash", buildConfigHash).put("bytes", 1).put("sha256", "11".repeat(32)))
                .put("cdn_config", JSONObject().put("hash", cdnConfigHash).put("bytes", 1).put("sha256", "22".repeat(32)))
                .put("catalog", JSONObject().put("bytes", database.length()).put("sha256", sha256File(database))
                    .put("gzip_bytes", 1).put("gzip_sha256", "33".repeat(32)))
                .put("executable", JSONObject().put("path", "WowB-ARM64.exe").put("decoded_bytes", 1)
                    .put("content_key", "44".repeat(16)).put("expected_machine", 43620))
                .put("server_version_source", "user-confirmed")
                .put("native_validation", JSONObject().put("status", "pending").put("evidence_sha256", JSONObject.NULL))
            catalog = ForeverClientUpdateCatalog(database, sha256File(database), database.length(),
                ForeverClientTargetBuild.parse(admission.toString().toByteArray(Charsets.UTF_8)))
        }

        fun makePaths(label: String = "client"): Paths {
            val client = File(root, "$label-root").apply { check(mkdir()) }
            val privateRoot = File(root, "$label-no-backup").apply { check(mkdir()) }
            val work = File(privateRoot, "work").apply { check(mkdir()) }
            val journal = File(privateRoot, "journal").apply { check(mkdir()) }
            return Paths(client, privateRoot, work, journal)
        }

        fun destination(
            paths: Paths,
            markerIdentity: String? = null,
            verifiedOwnedFiles: Map<String, FileFingerprint> = emptyMap(),
        ): ForeverClientUpdateDestination = ForeverClientUpdateDestination(
            paths.client,
            "synthetic-storage",
            revalidate = {
                check(paths.client.isDirectory && !Files.isSymbolicLink(paths.client.toPath()))
            },
            verifiedOwnedFiles = verifiedOwnedFiles,
            storageMarkerIdentity = markerIdentity,
        )

        fun targetSpecs(): List<ForeverCasc.IncrementalObjectSpec> = listOf(
            ForeverCasc.IncrementalObjectSpec(directEkey, directCkey, directEncodedSize, directDecoded.size.toLong()),
            ForeverCasc.IncrementalObjectSpec(rangeEkey, rangeCkey, rangeEncodedSize, rangeDecoded.size.toLong()),
        ) + extraEkey.mapIndexed { index, key ->
            ForeverCasc.IncrementalObjectSpec(key, extraCkey[index], extraRaw[index].size.toLong(), extraDecoded[index].size.toLong())
        }

        fun rawFor(record: ForeverClientUpdateCatalog.ObjectRecord): ByteArray = rawByKey.getValue(record.ekey)

        fun seedSourceCasc(dataFiles: File) {
            val seedRoot = File(root, "seed-${UUID.randomUUID()}").apply { check(mkdirs()) }
            val objects = targetSpecs().mapIndexed { index, spec ->
                val raw = File(seedRoot, "raw-$index.blte").apply { writeBytes(rawByKey.getValue(spec.ekey)) }
                val record = JSONObject()
                    .put("encoding_keys", JSONArray().put(spec.ekey))
                    .put("decoded_size", spec.decodedSize)
                ForeverCasc.RuntimeObject(record, raw)
            }
            ForeverCasc.materialize(seedRoot, "source fixture".toByteArray(), emptyList(), emptyList(), objects) { false }
            check(File(seedRoot, "Data/data").copyRecursively(dataFiles, overwrite = true))
        }

        fun corruptFirstSourceObject(dataFiles: File) {
            val offset = 480L + 30L + directEncodedSize - 1L
            RandomAccessFile(File(dataFiles, "data.000"), "rw").use { archive ->
                archive.seek(offset)
                val original = archive.read()
                check(original >= 0)
                archive.seek(offset)
                archive.write(original xor 1)
            }
        }

        fun syntheticConfigAssets(): Map<String, File> {
            val buildBytes = requireNotNull(buildConfigContents) { "synthetic build-config contents are required" }
            val cdnBytes = requireNotNull(cdnConfigContents) { "synthetic CDN-config contents are required" }
            val directory = File(root, "synthetic-configs").apply { check(mkdir()) }
            val build = File(directory, "build-${buildConfigHash}.txt").apply { writeBytes(buildBytes) }
            val cdn = File(directory, "cdn-${cdnConfigHash}.txt").apply { writeBytes(cdnBytes) }
            return mapOf(buildConfigHash to build, cdnConfigHash to cdn)
        }

        fun updater(
            paths: Paths,
            configs: Map<String, File>,
            fetch: (ForeverClientUpdateCatalog.ObjectRecord, File, () -> Boolean, (Long) -> Unit) -> File,
        ): ForeverClientUpdater = ForeverClientUpdater(
            catalog,
            configs,
            paths.work,
            paths.journal,
            ForeverClientOperationLease(File(paths.privateRoot, "operation-state")) { RuntimeObservation.Quiescent },
            fetch,
        )

        /** Post-admission test mutation only: short RW handle with default
         * PRAGMAs, one statement, then close. Production code never mutates. */
        fun mutateCatalogAfterAdmission(sql: String) {
            val writable = SQLiteDatabase.openDatabase(database.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                writable.execSQL(sql)
            } finally {
                writable.close()
            }
        }

        override fun close() {
            catalog.close()
            root.deleteRecursively()
        }

        private fun metadataValues(): Map<String, String> {
            fun json(value: Any): String = when (value) {
                is String -> JSONObject.quote(value)
                is JSONArray, is JSONObject -> value.toString()
                else -> value.toString()
            }
            val roles = listOf("download", "encoding", "install", "root", "size", "vfs-root")
            val fingerprints = JSONObject()
            roles.forEachIndexed { index, role ->
                val direct = index % 2 == 0
                val key = if (direct) directEkey else rangeEkey
                val contentKey = if (direct) directCkey else rangeCkey
                fingerprints.put(
                    role,
                    JSONObject()
                        .put("ekey", key)
                        .put("ckey", contentKey)
                        .put("encoded_size", if (direct) directEncodedSize else rangeEncodedSize)
                        .put("decoded_size", if (direct) directDecoded.size else rangeDecoded.size)
                        .put("source_kind", if (direct) "direct" else "range"),
                )
            }
            val metadataObjects = JSONObject()
            listOf("download", "encoding", "install", "size").forEach { role ->
                metadataObjects.put(
                    role,
                    JSONObject()
                        .put("role", role)
                        .put("blte_flags", 15)
                        .put("blte_modes", JSONArray().put("N").put("Z"))
                        .put("encoding_key", directEkey)
                        .put("content_key", directCkey)
                        .put("decoded_md5", directCkey)
                        .put("declared_encoded_size", directEncodedSize)
                        .put("decoded_size", directDecoded.size)
                        .put("decoded_sha256", "d".repeat(64))
                        .put("raw_sha256", "e".repeat(64)),
                )
            }
            return mapOf(
                "schema" to json("wowforever.arm64-client-catalog.sqlite"),
                "schema_version" to json(1),
                "product" to json("wow_classic_beta"),
                "region" to json("us"),
                "build_id" to json(version.substringAfterLast('.').toLong()),
                "version" to json(version),
                "platform" to json("Windows"),
                "architecture" to json("arm64"),
                "locale" to json("enUS"),
                "build_config_hash" to json(buildConfigHash),
                "cdn_config_hash" to json(cdnConfigHash),
                "official_versions_sha256" to json("f".repeat(64)),
                "selection_query" to json("Windows,arm64,enUS"),
                "install_manifest_sha256" to json("a".repeat(64)),
                "download_manifest_sha256" to json("b".repeat(64)),
                "size_manifest_sha256" to json("c".repeat(64)),
                "encoding_manifest_sha256" to json("d".repeat(64)),
                "metadata_objects" to json(metadataObjects),
                "required_runtime_roles" to json(JSONArray(roles)),
                "runtime_role_fingerprints" to json(fingerprints),
                "cdn_index_count" to json(1),
                "cdn_index_sha256_manifest" to json("1".repeat(64)),
                "cdn_file_index_hash" to json("2".repeat(32)),
                "download_selected_entry_count" to json(cascObjectCount),
                "download_selected_unique_ekey_count" to json(cascObjectCount),
                "download_selected_unique_encoded_bytes" to json(directEncodedSize + rangeEncodedSize + extraRaw.sumOf { it.size.toLong() }),
                "size_selected_entry_count" to json(cascObjectCount),
                "size_selected_estimated_bytes" to json(directEncodedSize + rangeEncodedSize + extraRaw.sumOf { it.size.toLong() }),
                "selected_loose_file_count" to json(1),
                "source_unresolved_count" to json(0),
                "payloads_acquired_or_verified" to json(false),
                "source_boundary" to json("metadata-only synthetic fixture"),
                "server_compatibility" to json(JSONObject().put("build_id", version.substringAfterLast('.').toLong()).put("login_or_gameplay_verified", false)),
            )
        }

        private fun insertObject(
            db: SQLiteDatabase,
            ekey: String,
            ckey: String,
            encoded: Long,
            decoded: Long,
            sourceKind: String,
            sourceHash: String,
            offset: Long,
        ) {
            db.insertOrThrow("objects", null, ContentValues().apply {
                put("ekey", ekey.decodeHex())
                put("ckey", ckey.decodeHex())
                put("encoded_size", encoded)
                put("decoded_size", decoded)
                put("source_kind", sourceKind)
                put("source_hash", sourceHash.decodeHex())
                put("source_offset", offset)
                put("source_length", encoded)
            })
        }

        private fun String.decodeHex(): ByteArray = ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }

        companion object {
            private fun blteRaw(decoded: ByteArray): ByteArray =
                byteArrayOf(0x42, 0x4c, 0x54, 0x45, 0, 0, 0, 0, 'N'.code.toByte()) + decoded

            private fun ekey(raw: ByteArray): String = md5(raw)

            private fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes)
                .joinToString("") { "%02x".format(it) }

            private fun sha256File(file: File): String {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) digest.update(buffer, 0, count)
                    }
                }
                return digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
    }
}
