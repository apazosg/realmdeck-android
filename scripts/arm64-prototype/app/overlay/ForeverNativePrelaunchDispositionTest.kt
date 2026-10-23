package app.gamenative

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ForeverNativePrelaunchDispositionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun admitsOnlyThePinnedPhysicalUnknownAndPreservesEveryOriginalByte() {
        val base = arrangeEvidence(temporary.newFolder("admission"))
        val boot = FixedBootId("11111111-2222-4333-8444-555555555555")
        val store = ForeverNativeSupervisorStore.forDirectory(base, boot)

        val run = store.begin("NS-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "normal")
        PHYSICAL_NAMES.forEach { name ->
            assertArrayEquals(PHYSICAL_RUN.resolve(name).readBytes(), File(base, "NS-b0a3fe48f925d7d656f597f740b4b413/$name").readBytes())
        }
        assertEquals(2, boot.reads)

        val archive = File(base, "NS-b0a3fe48f925d7d656f597f740b4b413/current-before-disposition.json")
        assertArrayEquals(PHYSICAL_RUN.resolve("run.json").readBytes(), archive.readBytes())
        val disposition = JSONObject(File(archive.parentFile, "prelaunch-disposition.json").readText())
        assertEquals(1, disposition.getInt("schema"))
        assertEquals("PRELAUNCH_ABORT", disposition.getString("kind"))
        assertEquals("native-run-supervisor-v2", disposition.getString("variant"))
        assertEquals("UNKNOWN", disposition.getString("priorStatus"))
        assertEquals("NOT_REACHED_BY_PINNED_SOURCE", disposition.getString("processCreate"))
        assertFalse(disposition.getBoolean("echild"))
        assertEquals(FROZEN_DISPOSITION_SHA256, disposition.getString("sourceSha256"))
        assertEquals("11111111-2222-4333-8444-555555555555", disposition.getString("freshBootId"))
        assertEquals(EMPTY_SHA256, disposition.getString("priorStdoutSha256"))
        assertEquals(EMPTY_SHA256, disposition.getString("priorWitnessStderrSha256"))

        val historical = store.readCurrent()!!.getJSONObject("historicalAdmission")
        assertEquals(setOf("priorRunId", "currentArchiveSha256", "dispositionSha256", "consumptionSha256"),
            historical.keys().asSequence().toSet())
        assertEquals(run.runId, store.readCurrent()!!.getString("runId"))
        assertTrue(File(base, "current.json").isFile)
    }

    @Test fun genericUnknownAndWrongPinnedEvidenceRemainQuarantined() {
        val base = arrangeEvidence(temporary.newFolder("negative"))
        val current = File(base, "current.json")
        current.appendText("\n")
        val store = ForeverNativeSupervisorStore.forDirectory(base, FixedBootId("22222222-3333-4444-8555-666666666666"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            store.begin("NS-cccccccccccccccccccccccccccccccc", "dddddddddddddddddddddddddddddddd", "normal")
        }
        assertFalse(File(base, "NS-cccccccccccccccccccccccccccccccc").exists())

        val genericBase = temporary.newFolder("generic")
        val genericStore = ForeverNativeSupervisorStore.forDirectory(genericBase)
        val first = genericStore.begin("NS-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "ffffffffffffffffffffffffffffffff", "normal")
        genericStore.finish(first, "UNKNOWN", null, JSONObject().put("status", "UNKNOWN"), false)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            genericStore.begin("NS-99999999999999999999999999999999", "00000000000000000000000000000000", "normal")
        }
    }

    @Test fun anyHistoricalStreamEntryStopsAdmissionWhetherEmptyOrNonempty() {
        listOf(ByteArray(0), "nonempty".toByteArray()).forEachIndexed { index, bytes ->
            listOf("stdout", "stderr", "witness-stdout", "witness-stderr").forEach { name ->
                val base = arrangeEvidence(temporary.newFolder("stream-$index-$name"))
                File(base, "NS-b0a3fe48f925d7d656f597f740b4b413/$name").writeBytes(bytes)
                val store = ForeverNativeSupervisorStore.forDirectory(
                    base, FixedBootId("44444444-5555-4666-8777-888888888888"))
                org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                    store.begin("NS-${name.replace("-", "").padEnd(32, 'a').take(32)}",
                        "${name.replace("-", "").padEnd(32, 'b').take(32)}", "normal")
                }
            }
        }
    }

    @Test fun historicalStreamSymlinksStopAdmissionWithoutFollowingThem() {
        val base = arrangeEvidence(temporary.newFolder("stream-symlink"))
        val stream = File(base, "NS-b0a3fe48f925d7d656f597f740b4b413/stdout")
        java.nio.file.Files.createSymbolicLink(stream.toPath(), stream.parentFile.resolve("run.json").toPath())
        val store = ForeverNativeSupervisorStore.forDirectory(
            base, FixedBootId("55555555-6666-4777-8888-999999999999"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            store.begin("NS-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "normal")
        }
    }

    @Test fun everyReservationAndCommitBoundaryFailsClosedWithoutDoubleConsumption() {
        val steps = listOf("prelaunch_archive", "prelaunch_disposition", "prelaunch_consumption",
            "new_run_directory", "new_run_write", "current_write")
        steps.forEachIndexed { index, step ->
            val base = arrangeEvidence(temporary.newFolder("fault-$step"))
            val store = ForeverNativeSupervisorStore.forDirectory(base, FixedBootId("33333333-4444-4555-8666-777777777777"))
            store.failBeforeStepForTests = { actual -> if (actual == step) error("injected $actual") }
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                store.begin("NS-${(index + 1).toString(16).padStart(32, 'a')}", "${(index + 2).toString(16).padStart(32, 'b')}", "normal")
            }
            store.failBeforeStepForTests = null
            assertArrayEquals(PHYSICAL_RUN.resolve("run.json").readBytes(), File(base, "current.json").readBytes())
            if (step != "prelaunch_archive") {
                val historical = File(base, "NS-b0a3fe48f925d7d656f597f740b4b413")
                assertTrue(File(historical, "current-before-disposition.json").exists())
                if (step != "prelaunch_disposition") assertTrue(File(historical, "prelaunch-disposition.json").exists())
                if (step == "new_run_directory" || step == "new_run_write" || step == "current_write") {
                    assertTrue(File(historical, "prelaunch-consumption.json").exists())
                }
                org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                    store.begin(runId('c', index + 10), nonce('d', index + 20), "normal")
                }
            }
        }
    }

    @Test fun canonicalDirectoryInstancesShareOneLockAndConcurrentBeginHasOneWinner() {
        val base = temporary.newFolder("concurrency", "files", "forever", "controls", "native-supervisor-v2")
        val first = ForeverNativeSupervisorStore.forDirectory(base)
        val second = ForeverNativeSupervisorStore.forDirectory(File(base, "."))
        assertSame(first, second)

        val gate = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<Boolean>())
        val threads = listOf(
            Thread { gate.await(); results += runCatching {
                first.begin(runId('a', 1), nonce('b', 1), "normal")
            }.isSuccess },
            Thread { gate.await(); results += runCatching {
                second.begin(runId('a', 2), nonce('b', 2), "normal")
            }.isSuccess },
        )
        threads.forEach { it.start() }
        gate.countDown()
        threads.forEach { it.join(2_000) }
        assertEquals(2, results.size)
        assertEquals(1, results.count { it })
        assertEquals(1, results.count { !it })
    }

    @Test fun processMarkersAreExactSingleUseAndMarkerFailurePreventsFactorySubmission() {
        val base = temporary.newFolder("markers")
        val store = ForeverNativeSupervisorStore.forDirectory(base)
        val run = store.begin("NS-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "normal")
        store.recordProcessCreateIntent(run, "supervisor")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            store.recordProcessCreateIntent(run, "supervisor")
        }
        store.recordProcessCreateIntent(run, "witness")
        val lines = File(run.directory, "events.jsonl").readLines().map { JSONObject(it) }
            .filter { it.getString("kind") == "process_create_intent" }
        assertEquals(2, lines.size)
        lines.forEach {
            assertEquals(setOf("kind", "role", "runId", "nonce"), it.keys().asSequence().toSet())
        }

        val controllerBase = temporary.newFolder("marker-controller")
        val controllerStore = ForeverNativeSupervisorStore.forDirectory(controllerBase)
        controllerStore.failBeforeProcessCreateIntentForTests = { error("marker durability failure") }
        val context = TestContext(controllerBase)
        var factoryCalled = false
        val factory = object : ForeverNativeProcessFactory {
            override fun startSupervisor(argv: Array<String>): java.lang.Process {
                factoryCalled = true
                error("factory must not be called")
            }
            override fun startWitness(argv: Array<String>): java.lang.Process = error("factory must not be called")
        }
        val controller = ForeverNativeSupervisor(context, factory, object : ForeverNativeKernel {
            override fun read(pid: Int): ForeverNativeProcIdentity = error("kernel must not be called")
        },
            store = controllerStore,
            artifactResolver = { ForeverNativeSupervisorPolicy.forTesting(File(controllerBase, "native/supervisor"), File(controllerBase, "native/fixture")) })
        assertTrue(controller.start("normal", true))
        await { controller.state.value.status == "UNKNOWN" }
        assertFalse(factoryCalled)
    }

    private fun arrangeEvidence(root: File): File {
        require(PHYSICAL_RUN.resolve("run.json").isFile)
        require(LEGACY_RUN.isFile)
        val base = File(root, "files/forever/controls/native-supervisor-v2").apply { mkdirs() }
        val old = File(base, "NS-b0a3fe48f925d7d656f597f740b4b413").apply { mkdirs() }
        PHYSICAL_NAMES.forEach { name -> PHYSICAL_RUN.resolve(name).copyTo(File(old, name), overwrite = false) }
        PHYSICAL_RUN.resolve("run.json").copyTo(File(base, "current.json"), overwrite = false)
        val legacy = File(root, "files/forever/controls/native-supervisor/current.json").apply {
            parentFile.mkdirs()
        }
        LEGACY_RUN.copyTo(legacy, overwrite = false)
        return base
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline)
            Thread.sleep(10)
        }
    }

    private fun runId(fill: Char, suffix: Int): String = "NS-" + fill.toString().repeat(31) + suffix.toString(16)
    private fun nonce(fill: Char, suffix: Int): String = fill.toString().repeat(31) + suffix.toString(16)

    private class FixedBootId(private val value: String) : ForeverNativePrelaunchDisposition.BootIdProvider {
        var reads = 0
        override fun read(): String { reads++; return value }
    }

    private class TestContext(private val directory: File) : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getFilesDir(): File = directory
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = ForeverNativeSupervisorPolicy.PACKAGE
    }

    companion object {
        private val PHYSICAL_RUN = File("/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/native-supervisor-trial-30eac3ee6fd645cf91172db96ce86a72/native-supervisor/NS-b0a3fe48f925d7d656f597f740b4b413")
        private val LEGACY_RUN = File("/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/native-supervisor-trial-a534f2fd28544f4bba0c45b2e349a26d/native-supervisor/NS-30823b6b5acd81b24830be89b809141d/run.json")
        private val PHYSICAL_NAMES = listOf("run.json", "events.jsonl")
        private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        private const val FROZEN_DISPOSITION_SHA256 = "423ed396ac54c9caa5edf43c06ec645dc5b68275928003c064729b85ecb44c62"
    }
}
