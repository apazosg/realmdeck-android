package app.gamenative

import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(
    sdk = [29],
    application = android.app.Application::class,
    shadows = [
        ForeverWineRunStoreTestShadowOs::class,
        ForeverWineRunStoreTestShadowParcelFileDescriptor::class,
    ],
)
class ForeverWineRunStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun bindingIntentsFramesAndResultAreUnderOneAuthoritativeRun() {
        val store = ForeverRunStoreRegistry.forDirectory(temporary.newFolder("files"))
        val binding = store.beginWine("WS-" + "a".repeat(32), "b".repeat(32))
        store.recordWineProcessCreateIntent(binding.runId, binding.wsRunId, binding.nonce, "supervisor")
        assertThrows(IllegalArgumentException::class.java) {
            store.recordWineProcessCreateIntent(binding.runId, binding.wsRunId, binding.nonce, "supervisor")
        }
        val frame = "{\"schema\":2}\n".toByteArray()
        store.recordWineRawFrame(binding.runId, binding.wsRunId, binding.nonce, "native-stdout", frame)
        store.finish(binding.runId, ForeverRunStatus.FAILED, "diagnostic")
        store.writeWineResult(binding.runId, binding.wsRunId, binding.nonce, unknownResult())
        assertTrue(File(binding.outputDirectory, "wine-binding.json").isFile)
        assertTrue(File(binding.outputDirectory, "wine-result.json").isFile)
        assertArrayEquals(frame, File(binding.outputDirectory, "wine-native-stdout.jsonl").readBytes())
    }

    @Test fun staleRunAndWrongBindingCannotAppendOrAdvance() {
        val store = ForeverRunStoreRegistry.forDirectory(temporary.newFolder("stale"))
        val binding = store.beginWine("WS-" + "c".repeat(32), "d".repeat(32))
        assertThrows(IllegalArgumentException::class.java) {
            store.recordWineRawFrame(binding.runId, binding.wsRunId, "e".repeat(32), "native-stdout", "x\n".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.recordWineRawFrame("RUN-not-current", binding.wsRunId, binding.nonce, "native-stdout", "x\n".toByteArray())
        }
    }

    @Test fun resultDurabilityFailureLeavesTheAuthoritativeRunActiveAndBlocked() {
        val store = ForeverRunStoreRegistry.forDirectory(temporary.newFolder("durability"))
        val binding = store.beginWine("WS-" + "e".repeat(32), "f".repeat(32))
        store.finish(binding.runId, ForeverRunStatus.FAILED, "diagnostic")
        store.failNextAtomicWriteForTests = true
        assertThrows(IllegalStateException::class.java) {
            store.writeWineResult(binding.runId, binding.wsRunId, binding.nonce, unknownResult())
        }
        assertEquals(ForeverRunStatus.FAILED, store.readCurrent()!!.status)
        assertThrows(IllegalArgumentException::class.java) {
            store.beginWine("WS-" + "1".repeat(32), "2".repeat(32))
        }
    }

    @Test fun directorySyncFailureCannotLeaveAPositiveWineResult() {
        val store = ForeverRunStoreRegistry.forDirectory(temporary.newFolder("directory-sync"))
        val binding = store.beginWine("WS-" + "3".repeat(32), "4".repeat(32))
        store.finish(binding.runId, ForeverRunStatus.FAILED, "diagnostic")
        store.failNextDirectorySyncForTests = true
        assertThrows(IllegalStateException::class.java) {
            store.writeWineResult(binding.runId, binding.wsRunId, binding.nonce, unknownResult())
        }
        assertTrue(!File(binding.outputDirectory, "wine-result.json").exists())
        assertEquals(ForeverRunStatus.FAILED, store.readCurrent()!!.status)
    }

    @Test fun partialWineEvidenceWithoutBindingBlocksHistoricalAdmission() {
        val store = ForeverRunStoreRegistry.forDirectory(temporary.newFolder("partial"))
        val binding = store.beginWine("WS-" + "5".repeat(32), "6".repeat(32))
        File(binding.outputDirectory, "wine-result.json").writeText("{}")
        assertTrue(File(binding.outputDirectory, "wine-binding.json").delete())
        store.finish(binding.runId, ForeverRunStatus.FAILED, "partial")
        assertThrows(IllegalArgumentException::class.java) {
            store.beginWine("WS-" + "7".repeat(32), "8".repeat(32))
        }
    }

    @Test fun positiveResultIsTypedOneShotAndMatchesGenericTerminalState() {
        val store = ForeverRunStoreRegistry.forDirectory(temporary.newFolder("positive"))
        val binding = store.beginWine("WS-" + "9".repeat(32), "a".repeat(32))
        assertThrows(IllegalArgumentException::class.java) {
            store.writeWineResult(binding.runId, binding.wsRunId, binding.nonce, positiveResult())
        }
        store.update(binding.runId) { it.copy(terminationUnknown = false, quiescenceConfirmed = true) }
        store.finish(binding.runId, ForeverRunStatus.EXITED, "PASS")
        store.writeWineResult(binding.runId, binding.wsRunId, binding.nonce, positiveResult())
        assertThrows(IllegalArgumentException::class.java) {
            store.writeWineResult(binding.runId, binding.wsRunId, binding.nonce, positiveResult())
        }
        assertTrue(File(binding.outputDirectory, "wine-result-commit.json").isFile)
    }

    @Test fun resurrectedCommitWithoutRootAdmissionStillBlocksAfterEveryFault() {
        for (point in ForeverRunStore.WineResultFaultPoint.values()) {
            val store = ForeverRunStoreRegistry.forDirectory(temporary.newFolder("fault-${point.name}"))
            val binding = store.beginWine("WS-" + "b".repeat(32), "c".repeat(32))
            store.update(binding.runId) { it.copy(terminationUnknown = false, quiescenceConfirmed = true) }
            store.finish(binding.runId, ForeverRunStatus.EXITED, "PASS")
            store.failNextWineResultAt = point
            assertThrows(IllegalStateException::class.java) {
                store.writeWineResult(binding.runId, binding.wsRunId, binding.nonce, positiveResult())
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.writeWineResult(binding.runId, binding.wsRunId, binding.nonce, positiveResult())
            }
            // Simulate an unsynced deletion losing against the prior rename.
            val resurrected = org.json.JSONObject()
                .put("schema", 1).put("runId", binding.runId).put("wsRunId", binding.wsRunId).put("nonce", binding.nonce)
                .put("terminalStatus", "PASS").put("nativeStatus", "PASS")
                .put("closureObserved", true).put("qualification", true).put("clientPass", true)
                .put("supervisorExit", 0).put("echild", true)
                .put("witnessProof", org.json.JSONObject().put("status", "PASS").put("stableBeforeQ", true)
                    .put("qSent", true).put("reaped", true).put("exit", 0))
                .put("error", org.json.JSONObject.NULL)
            File(binding.outputDirectory, "wine-result.json").writeText(resurrected.toString(2))
            assertThrows(IllegalArgumentException::class.java) {
                store.beginWine("WS-" + "d".repeat(32), "e".repeat(32))
            }
        }
    }

    @Test fun cancelledClosureDoesNotInventACompletedClientPhase() {
        val store = ForeverRunStoreRegistry.forDirectory(temporary.newFolder("cancelled"))
        val binding = store.beginWine("WS-" + "f".repeat(32), "0".repeat(32))
        store.update(binding.runId) { it.copy(terminationUnknown = false, quiescenceConfirmed = true) }
        store.finish(binding.runId, ForeverRunStatus.CANCELLED, "CANCELLED")
        store.writeWineResult(binding.runId, binding.wsRunId, binding.nonce,
            positiveResult().copy(terminalStatus = "CANCELLED", nativeStatus = "CANCELLED", clientPass = false))
        store.beginWine("WS-" + "1".repeat(32), "2".repeat(32))
    }

    @Test fun lateStateDowngradeInvalidatesAnOtherwiseCommittedPositiveResult() {
        val files = temporary.newFolder("state-mismatch")
        val store = ForeverRunStoreRegistry.forDirectory(files)
        val binding = store.beginWine("WS-" + "3".repeat(32), "4".repeat(32))
        store.update(binding.runId) { it.copy(terminationUnknown = false, quiescenceConfirmed = true) }
        store.finish(binding.runId, ForeverRunStatus.EXITED, "PASS")
        store.writeWineResult(binding.runId, binding.wsRunId, binding.nonce, positiveResult())
        assertThrows(IllegalArgumentException::class.java) {
            store.update(binding.runId) { it.copy(terminationUnknown = true, quiescenceConfirmed = false) }
        }
        // Simulate conflicting durable records, not a permitted late mutation API.
        for (path in listOf(File(files, "forever/current.json"), File(files, "forever/runs/${binding.runId}.json"))) {
            val record = org.json.JSONObject(path.readText()).put("terminationUnknown", true).put("quiescenceConfirmed", false)
            path.writeText(record.toString(2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.beginWine("WS-" + "5".repeat(32), "6".repeat(32))
        }
    }

    private data class InterruptedFixture(val files: File, val store: ForeverRunStore, val current: ByteArray, val oldRoot: File)

    private data class ContinuationFixture(
        val files: File,
        val store: ForeverRunStore,
        val current: ByteArray,
        val oldRoot: File,
        val oldRunId: String,
        val oldWs: String,
        val oldNonce: String,
        val oldBoot: String,
        val newBoot: String,
    )

    private fun continuationFixture(name: String): ContinuationFixture {
        val files = temporary.newFolder(name)
        val oldRunId = "RUN-1791738410167-533692127338"
        val oldWs = "WS-51c1b011c946c45309229bd9d6fff681"
        val oldNonce = "f9f4da016c99beb427b44274e9ab4ef6"
        val oldBoot = "bc331988-3879-4cd4-b4d2-c1b616c5a2a1"
        val newBoot = "11111111-2222-4333-8444-555555555555"
        val current = ForeverRunState(
            oldRunId, ForeverStage.EXITED, ForeverRunStatus.FAILED,
            message = "filesDir is not the canonical private app root",
            terminationUnknown = true, bootId = oldBoot,
        ).toJson().toString(2).toByteArray(Charsets.UTF_8)
        val binding = org.json.JSONObject().put("schema", 1).put("kind", "wine-binding")
            .put("runId", oldRunId).put("wsRunId", oldWs).put("nonce", oldNonce).put("status", "BOUND")
            .toString(2).toByteArray(Charsets.UTF_8)
        val result = org.json.JSONObject().put("schema", 1).put("runId", oldRunId).put("wsRunId", oldWs)
            .put("nonce", oldNonce).put("terminalStatus", "UNKNOWN").put("nativeStatus", "UNKNOWN")
            .put("supervisorExit", org.json.JSONObject.NULL).put("closureObserved", false)
            .put("qualification", false).put("echild", false)
            .put("error", "filesDir is not the canonical private app root")
            .toString(2).toByteArray(Charsets.UTF_8)
        val contract = ForeverPrelaunchContinuation.fixed()
        val failure = contract.getJSONObject("knownFailure")
        failure.getJSONObject("current").put("bytes", current.size).put("sha256", sha256(current))
        failure.getJSONObject("binding").put("bytes", binding.size).put("sha256", sha256(binding))
        failure.getJSONObject("wineResult").put("bytes", result.size).put("sha256", sha256(result))
        val currentPath = "files/forever/current.json"
        val runPath = "files/forever/runs/$oldRunId.json"
        val bindingPath = "files/forever/runs/$oldRunId/wine-binding.json"
        val resultPath = "files/forever/runs/$oldRunId/wine-result.json"
        val syntheticPresent = (0 until 15).associate { index ->
            "files/forever/continuation-fixture/present-$index" to "synthetic present $index\n".toByteArray()
        }
        val fixed = org.json.JSONArray().apply {
            listOf(currentPath, runPath, bindingPath, resultPath).forEach { path ->
                val bytes = when (path) {
                    currentPath, runPath -> current
                    bindingPath -> binding
                    else -> result
                }
                put(org.json.JSONObject().put("path", path).put("status", "PRESENT")
                    .put("bytes", bytes.size).put("sha256", sha256(bytes)))
            }
            syntheticPresent.forEach { (path, bytes) ->
                put(org.json.JSONObject().put("path", path).put("status", "PRESENT")
                    .put("bytes", bytes.size).put("sha256", sha256(bytes)))
            }
            for (index in 0 until 39) {
                put(org.json.JSONObject().put("path", "files/forever/continuation-fixture/missing-$index")
                    .put("status", "MISSING").put("bytes", 0).put("sha256", ""))
            }
        }
        contract.put("fixedStatusCount", 58).put("fixedPaths", fixed)
        val store = ForeverRunStoreRegistry.forTestContinuation(
            files, contract, ForeverPrelaunchContinuation.candidateAuthority(), newBoot,
        )
        val root = File(files, "forever/runs/$oldRunId")
        root.mkdirs()
        File(files, "forever/current.json").writeBytes(current)
        File(root.parentFile, "$oldRunId.json").writeBytes(current)
        File(root, "wine-binding.json").writeBytes(binding)
        File(root, "wine-result.json").writeBytes(result)
        syntheticPresent.forEach { (path, bytes) ->
            val target = File(files, path.removePrefix("files/"))
            target.parentFile.mkdirs()
            target.writeBytes(bytes)
        }
        return ContinuationFixture(files, store, current, root, oldRunId, oldWs, oldNonce, oldBoot, newBoot)
    }

    private val fixedAuthorityPaths = listOf(
        "files/forever/controls/compiler-prefix/RUN-1791497262380-683075998117/backup-d3dcompiler_47.dll-7239e3f8-b5fc-42d3-9650-15481cd22164.bak",
        "files/forever/controls/native-supervisor-v2/NS-b0a3fe48f925d7d656f597f740b4b413/current-before-disposition.json",
        "files/forever/controls/native-supervisor-v2/NS-b0a3fe48f925d7d656f597f740b4b413/events.jsonl",
        "files/forever/controls/native-supervisor-v2/NS-b0a3fe48f925d7d656f597f740b4b413/prelaunch-consumption.json",
        "files/forever/controls/native-supervisor-v2/NS-b0a3fe48f925d7d656f597f740b4b413/prelaunch-disposition.json",
        "files/forever/controls/native-supervisor-v2/NS-b0a3fe48f925d7d656f597f740b4b413/run.json",
        "files/forever/controls/native-supervisor-v2/current.json",
        "files/forever/controls/native-supervisor/current.json",
        "files/forever/current.json",
        "files/forever/runs/RUN-1791718684192-65783495695-wine-result-admission.json",
        "files/forever/runs/RUN-1791718684192-65783495695.json",
        "files/forever/runs/RUN-1791718684192-65783495695/wine-binding.json",
        "files/forever/runs/RUN-1791718684192-65783495695/wine-native-stderr.jsonl",
        "files/forever/runs/RUN-1791718684192-65783495695/wine-native-stdin.jsonl",
        "files/forever/runs/RUN-1791718684192-65783495695/wine-native-stdout.jsonl",
        "files/forever/runs/RUN-1791718684192-65783495695/wine-process-create-intents.jsonl",
        "files/forever/runs/RUN-1791718684192-65783495695/wine-publisher-stderr.jsonl",
        "files/forever/runs/RUN-1791718684192-65783495695/wine-publisher-stdout.jsonl",
        "files/forever/runs/RUN-1791718684192-65783495695/wine-result-commit.json",
        "files/forever/runs/RUN-1791718684192-65783495695/wine-result-quarantine.json",
        "files/forever/runs/RUN-1791718684192-65783495695/wine-result.json",
        "files/forever/runs/RUN-1791718684192-65783495695/wine-stdin.jsonl",
        "files/forever/runs/RUN-1791718684192-65783495695/wine-witness-stderr.jsonl",
        "files/forever/runs/RUN-1791718684192-65783495695/wine-witness-stdout.jsonl",
        "files/forever/wine-controls/WS-024cc10c5e18649fad0c6567191aa18b",
        "files/forever/wine-controls/WS-024cc10c5e18649fad0c6567191aa18b/prefix-stage/.wine",
        "files/forever/wine-controls/WS-024cc10c5e18649fad0c6567191aa18b/prefix/.wine",
        "files/forever/wine-controls/WS-024cc10c5e18649fad0c6567191aa18b/prefix/.wine/.supervisor-output/client.stderr",
        "files/forever/wine-controls/WS-024cc10c5e18649fad0c6567191aa18b/prefix/.wine/.supervisor-output/client.stdout",
        "files/forever/wine-controls/WS-024cc10c5e18649fad0c6567191aa18b/prefix/.wine/.supervisor-output/server.stderr",
        "files/forever/wine-controls/WS-024cc10c5e18649fad0c6567191aa18b/prefix/.wine/.supervisor-output/server.stdout",
        "files/forever/wine-controls/WS-024cc10c5e18649fad0c6567191aa18b/prepared.receipt",
        "files/imagefs_shared/home/xuser-CUSTOM_GAME_1368588668/.wine/drive_c/windows/system32/d3dcompiler_47.dll",
    )

    private val fixedAuthorityPresent = setOf(
        fixedAuthorityPaths[0], fixedAuthorityPaths[1], fixedAuthorityPaths[2], fixedAuthorityPaths[3],
        fixedAuthorityPaths[4], fixedAuthorityPaths[5], fixedAuthorityPaths[6], fixedAuthorityPaths[7],
        fixedAuthorityPaths[8], fixedAuthorityPaths[10], fixedAuthorityPaths[11], fixedAuthorityPaths[32],
    )

    private fun authorityBytes(path: String): ByteArray = "synthetic authority: $path\n".toByteArray()

    private fun fixedAuthorityContract(current: ByteArray, binding: ByteArray): org.json.JSONObject {
        val value = ForeverInterruptedRunDisposition.fixed()
        value.put("fixedPaths", org.json.JSONArray().apply {
            fixedAuthorityPaths.forEach { path ->
                val present = path in fixedAuthorityPresent
                val bytes = when (path) {
                    "files/forever/current.json", "files/forever/runs/RUN-1791718684192-65783495695.json" -> current
                    "files/forever/runs/RUN-1791718684192-65783495695/wine-binding.json" -> binding
                    else -> authorityBytes(path)
                }
                put(org.json.JSONObject().put("path", path).put("status", if (present) "PRESENT" else "MISSING")
                    .put("bytes", if (present) bytes.size else 0)
                    .put("sha256", if (present) sha256(bytes) else ""))
            }
        })
        return value
    }

    private fun interruptedFixture(name: String): InterruptedFixture {
        val files = temporary.newFolder(name)
        val oldRunId = "RUN-1791718684192-65783495695"
        val oldBoot = "06254205-1db4-4aaa-8f4e-7123db10f851"
        val newBoot = "11111111-2222-4333-8444-555555555555"
        val state = ForeverRunState(oldRunId, ForeverStage.PREFLIGHT, ForeverRunStatus.ACTIVE, bootId = oldBoot)
        val current = state.toJson().toString(2).toByteArray(Charsets.UTF_8)
        val oldBinding = org.json.JSONObject().put("schema", 1).put("kind", "wine-binding")
            .put("runId", oldRunId).put("wsRunId", "WS-024cc10c5e18649fad0c6567191aa18b")
            .put("nonce", "44fa3305f5c3db9fa0d8cd6541978ba8").put("status", "BOUND")
            .toString(2).toByteArray(Charsets.UTF_8)
        val contract = fixedAuthorityContract(current, oldBinding)
        val prior = contract.getJSONObject("prior")
        prior.getJSONObject("current").put("bytes", current.size).put("sha256", sha256(current))
        prior.getJSONObject("binding").put("bytes", oldBinding.size).put("sha256", sha256(oldBinding))
        val store = ForeverRunStoreRegistry.forTestDisposition(
            files, contract, ForeverInterruptedRunDisposition.candidateAuthority(), newBoot,
        )
        val root = File(files, "forever/runs/RUN-1791718684192-65783495695")
        root.mkdirs()
        File(files, "forever/current.json").writeBytes(current)
        File(root.parentFile, "RUN-1791718684192-65783495695.json").writeBytes(current)
        File(root, "wine-binding.json").writeBytes(oldBinding)
        fixedAuthorityPresent.filterNot {
            it == "files/forever/current.json" ||
                it == "files/forever/runs/RUN-1791718684192-65783495695.json" ||
                it == "files/forever/runs/RUN-1791718684192-65783495695/wine-binding.json"
        }.forEach { path ->
            val target = File(files, path.removePrefix("files/"))
            target.parentFile.mkdirs()
            target.writeBytes(authorityBytes(path))
        }
        return InterruptedFixture(files, store, current, root)
    }

    @Test fun fixedInterruptedRunIsArchivedWithoutRewritingOldStateAndConsumedOnce() {
        val fixture = interruptedFixture("interrupted-disposition")
        val files = fixture.files
        val store = fixture.store
        val current = fixture.current
        val root = fixture.oldRoot
        assertEquals("fixture must expose the actual interrupted current", ForeverRunStatus.ACTIVE, store.readCurrent()?.status)
        val binding = store.beginWine("WS-" + "1".repeat(32), "2".repeat(32))
        assertEquals("RUN-1791718684192-65783495695", org.json.JSONObject(current.toString(Charsets.UTF_8)).getString("runId"))
        assertEquals(ForeverRunStatus.ACTIVE, store.read("RUN-1791718684192-65783495695")!!.status)
        assertTrue(File(files, "forever/runs/RUN-1791718684192-65783495695-current-before-abandonment.json").readBytes().contentEquals(current))
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(File(files, "forever/runs/RUN-1791718684192-65783495695-current-before-abandonment.json").toPath()))
        val receipt = org.json.JSONObject(File(root, "abandoned-on-new-boot.json").readText())
        assertEquals("ABANDONED_ON_NEW_BOOT", receipt.getString("terminalStatus"))
        assertFalse(receipt.getBoolean("closureObserved")); assertFalse(receipt.getBoolean("qualification")); assertFalse(receipt.getBoolean("echild"))
        assertEquals(binding.runId, store.readCurrent()!!.runId)
        assertThrows(IllegalArgumentException::class.java) {
            store.beginWine("WS-" + "3".repeat(32), "4".repeat(32))
        }
    }

    @Test fun partialDispositionAtEveryCommitBoundaryBlocksTheNextRun() {
        for (point in ForeverRunStore.DispositionFaultPoint.values()) {
            val fixture = interruptedFixture("partial-${point.name}")
            fixture.store.failNextDispositionAt = point
            assertThrows(IllegalStateException::class.java) {
                fixture.store.beginWine("WS-" + "a".repeat(32), "b".repeat(32))
            }
            assertThrows(IllegalArgumentException::class.java) {
                fixture.store.beginWine("WS-" + "c".repeat(32), "d".repeat(32))
            }
        }
    }

    @Test fun directoryFsyncFailureLeavesAPartialDispositionThatBlocksTheNextRun() {
        val fixture = interruptedFixture("disposition-fsync")
        fixture.store.failNextDirectorySyncForTests = true
        assertThrows(IllegalStateException::class.java) {
            fixture.store.beginWine("WS-" + "e".repeat(32), "f".repeat(32))
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.store.beginWine("WS-" + "1".repeat(32), "2".repeat(32))
        }
    }

    @Test fun everySealedMissingAuthorityEntryRejectsUnexpectedPresence() {
        fixedAuthorityPaths.filterNot { it in fixedAuthorityPresent }.forEachIndexed { index, path ->
            val fixture = interruptedFixture("missing-authority-$index")
            val target = File(fixture.files, path.removePrefix("files/"))
            target.parentFile.mkdirs()
            target.writeBytes("unexpected authority\n".toByteArray())
            assertThrows(IllegalArgumentException::class.java) {
                fixture.store.beginWine("WS-" + "a".repeat(32), "b".repeat(32))
            }
        }
    }

    @Test fun missingAuthorityWithSymlinkedAncestorIsRejectedWithoutFollowingIt() {
        val fixture = interruptedFixture("missing-authority-symlink")
        val target = File(fixture.files, "forever/wine-controls")
        target.parentFile.mkdirs()
        val outside = temporary.newFolder("authority-symlink-target")
        Files.createSymbolicLink(target.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            fixture.store.beginWine("WS-" + "c".repeat(32), "d".repeat(32))
        }
    }

    @Test fun missingAuthorityWithSymlinkedLeafIsRejectedWithoutFollowingIt() {
        val fixture = interruptedFixture("missing-authority-leaf-symlink")
        val relative = fixedAuthorityPaths.first { it !in fixedAuthorityPresent }
        val target = File(fixture.files, relative.removePrefix("files/"))
        val outside = temporary.newFile("authority-leaf-target")
        target.parentFile.mkdirs()
        Files.createSymbolicLink(target.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            fixture.store.beginWine("WS-" + "e".repeat(32), "f".repeat(32))
        }
    }

    @Test fun syntheticFixtureCoversExactly33PathsWithTypedShaPins() {
        val current = "synthetic current\n".toByteArray()
        val binding = "synthetic binding\n".toByteArray()
        val contract = fixedAuthorityContract(current, binding)
        val entries = contract.getJSONArray("fixedPaths")
        assertEquals(33, entries.length())
        assertEquals(4, contract.getJSONArray("transactionPreconditions").length())
        assertEquals(33, (0 until entries.length()).map { entries.getJSONObject(it).getString("path") }.toSet().size)
        assertEquals(12, (0 until entries.length()).count { entries.getJSONObject(it).getString("status") == "PRESENT" })
        assertEquals(21, (0 until entries.length()).count { entries.getJSONObject(it).getString("status") == "MISSING" })
        for (index in 0 until entries.length()) {
            val item = entries.getJSONObject(index)
            if (item.getString("status") == "PRESENT") {
                assertTrue(item.getInt("bytes") > 0)
                assertEquals(64, item.getString("sha256").length)
            } else {
                assertEquals("MISSING", item.getString("status"))
                assertEquals(0, item.getInt("bytes"))
                assertEquals("", item.getString("sha256"))
            }
        }
    }

    @Test fun everySealedPresentAuthorityEntryRejectsChangedBytes() {
        fixedAuthorityPresent.forEachIndexed { index, path ->
            val fixture = interruptedFixture("changed-present-$index")
            val target = File(fixture.files, path.removePrefix("files/"))
            target.writeBytes("changed authority\n".toByteArray())
            assertAdmissionRejected {
                fixture.store.beginWine("WS-" + "a".repeat(32), "b".repeat(32))
            }
        }
    }

    @Test fun authorityMutationAfterReservationBindingAndCurrentBoundariesIsBlocked() {
        val points = listOf(
            ForeverRunStore.DispositionFaultPoint.AFTER_RESERVATION,
            ForeverRunStore.DispositionFaultPoint.AFTER_BINDING,
            ForeverRunStore.DispositionFaultPoint.AFTER_CURRENT,
        )
        points.forEach { point ->
            val fixture = interruptedFixture("authority-mutation-${point.name}")
            fixture.store.dispositionMutationForTests = { reached ->
                if (reached == point) {
                    if (point == ForeverRunStore.DispositionFaultPoint.AFTER_CURRENT) {
                        File(fixture.files, "forever/current.json").writeBytes("mutated current\n".toByteArray())
                    } else {
                        val path = fixedAuthorityPaths[0].removePrefix("files/")
                        File(fixture.files, path).writeBytes("mutated authority\n".toByteArray())
                    }
                }
            }
            assertAdmissionRejected {
                fixture.store.beginWine("WS-" + "e".repeat(32), "f".repeat(32))
            }
        }
    }

    @Test fun everyContinuationFaultQuarantinesPartialAdmissionAndNeverReturnsBinding() {
        for (point in ForeverRunStore.ContinuationFaultPoint.values()) {
            val fixture = continuationFixture("continuation-fault-${point.name}")
            val currentPath = File(fixture.files, "forever/current.json")
            val oldRunPath = File(fixture.files, "forever/runs/${fixture.oldRunId}.json")
            val oldBindingPath = File(fixture.oldRoot, "wine-binding.json")
            val oldResultPath = File(fixture.oldRoot, "wine-result.json")
            val currentBefore = currentPath.readBytes()
            val oldRunBefore = oldRunPath.readBytes()
            val oldBindingBefore = oldBindingPath.readBytes()
            val oldResultBefore = oldResultPath.readBytes()
            fixture.store.failNextContinuationAt = point
            var returned = false
            assertThrows(IllegalStateException::class.java) {
                fixture.store.beginWine("WS-" + "1".repeat(32), "2".repeat(32)).also { returned = true }
            }
            assertFalse("faulted continuation must not return a binding", returned)
            assertArrayEquals(oldRunBefore, oldRunPath.readBytes())
            assertArrayEquals(oldBindingBefore, oldBindingPath.readBytes())
            assertArrayEquals(oldResultBefore, oldResultPath.readBytes())
            val reservationFile = File(fixture.files, "forever/runs/${fixture.oldRunId}-prelaunch-abort-reservation.json")
            assertTrue(reservationFile.isFile)
            if (point != ForeverRunStore.ContinuationFaultPoint.AFTER_CURRENT) {
                assertArrayEquals(currentBefore, currentPath.readBytes())
            } else {
                assertFalse(currentBefore.contentEquals(currentPath.readBytes()))
                assertEquals(
                    org.json.JSONObject(currentPath.readText()).getString("runId"),
                    org.json.JSONObject(reservationFile.readText()).getString("newRunId"),
                )
            }
            assertAdmissionRejected {
                fixture.store.beginWine("WS-" + "3".repeat(32), "4".repeat(32))
            }
            val reservation = org.json.JSONObject(reservationFile.readText())
            val newRoot = File(fixture.files, "forever/runs/${reservation.getString("newRunId")}")
            assertEquals(point.ordinal >= ForeverRunStore.ContinuationFaultPoint.AFTER_ARCHIVE.ordinal,
                File(fixture.files, "forever/runs/${fixture.oldRunId}-current-before-prelaunch-abort.json").isFile)
            assertEquals(point.ordinal >= ForeverRunStore.ContinuationFaultPoint.AFTER_RECEIPT.ordinal,
                File(fixture.oldRoot, "prelaunch-abort.json").isFile)
            assertEquals(point.ordinal >= ForeverRunStore.ContinuationFaultPoint.AFTER_BINDING.ordinal, newRoot.isDirectory)
            assertEquals(point.ordinal >= ForeverRunStore.ContinuationFaultPoint.AFTER_BINDING.ordinal,
                File(newRoot, "wine-binding.json").isFile)
            assertEquals(point.ordinal >= ForeverRunStore.ContinuationFaultPoint.AFTER_CONSUMPTION.ordinal,
                File(fixture.files, "forever/runs/${fixture.oldRunId}-prelaunch-abort-consumption.json").isFile)
        }
    }

    @Test fun continuationMutationAfterCurrentRejectsBindingReturnAndQuarantinesPartialTicket() {
        val fixture = continuationFixture("continuation-authority-mutation")
        fixture.store.continuationMutationForTests = { point ->
            if (point == ForeverRunStore.ContinuationFaultPoint.AFTER_CURRENT) {
                File(fixture.files, "forever/current.json").writeText("mutated current\n")
            }
        }
        var returned = false
        assertThrows(IllegalArgumentException::class.java) {
            fixture.store.beginWine("WS-" + "1".repeat(32), "2".repeat(32)).also { returned = true }
        }
        assertFalse("mutated current must not return a new Wine binding", returned)
        assertTrue(File(fixture.files, "forever/runs/${fixture.oldRunId}-prelaunch-abort-reservation.json").isFile)
        assertAdmissionRejected {
            fixture.store.beginWine("WS-" + "3".repeat(32), "4".repeat(32))
        }
    }

    @Test fun successfulContinuationPreservesOldEvidenceAndCommitsBoundReceipt() {
        val fixture = continuationFixture("continuation-success")
        val newWs = "WS-" + "1".repeat(32)
        val newNonce = "2".repeat(32)
        val oldRunBefore = File(fixture.files, "forever/runs/${fixture.oldRunId}.json").readBytes()
        val oldBindingBefore = File(fixture.oldRoot, "wine-binding.json").readBytes()
        val oldResultBefore = File(fixture.oldRoot, "wine-result.json").readBytes()
        val binding = fixture.store.beginWine(newWs, newNonce)
        assertFalse(binding.runId == fixture.oldRunId)
        assertEquals(newWs, binding.wsRunId)
        assertEquals(newNonce, binding.nonce)
        assertEquals(binding.runId, fixture.store.readCurrent()!!.runId)
        assertArrayEquals(oldRunBefore, File(fixture.files, "forever/runs/${fixture.oldRunId}.json").readBytes())
        assertArrayEquals(oldBindingBefore, File(fixture.oldRoot, "wine-binding.json").readBytes())
        assertArrayEquals(oldResultBefore, File(fixture.oldRoot, "wine-result.json").readBytes())
        assertArrayEquals(
            fixture.current,
            File(fixture.files, "forever/runs/${fixture.oldRunId}-current-before-prelaunch-abort.json").readBytes(),
        )
        val receiptBytes = File(fixture.oldRoot, "prelaunch-abort.json").readBytes()
        val receipt = org.json.JSONObject(receiptBytes.toString(Charsets.UTF_8))
        assertEquals(1, receipt.getInt("schema"))
        assertEquals("PRELAUNCH_ABORT", receipt.getString("kind"))
        assertEquals(fixture.oldRunId, receipt.getString("runId"))
        assertEquals(fixture.oldWs, receipt.getString("wsRunId"))
        assertEquals(fixture.oldNonce, receipt.getString("nonce"))
        assertEquals(binding.runId, receipt.getString("newRunId"))
        assertEquals(newWs, receipt.getString("newWsRunId"))
        assertEquals(newNonce, receipt.getString("newNonce"))
        assertEquals(fixture.newBoot, receipt.getString("newBootId"))
        assertEquals("PRELAUNCH_ABORT", receipt.getString("terminalStatus"))
        assertEquals("UNKNOWN", receipt.getString("nativeStatus"))
        assertFalse(receipt.getBoolean("closureObserved"))
        assertFalse(receipt.getBoolean("qualification"))
        assertFalse(receipt.getBoolean("echild"))
        val consumption = org.json.JSONObject(
            File(fixture.files, "forever/runs/${fixture.oldRunId}-prelaunch-abort-consumption.json").readText(),
        )
        assertEquals("PRELAUNCH_ABORT_CONSUMPTION", consumption.getString("kind"))
        assertEquals("COMMITTED", consumption.getString("state"))
        assertEquals(fixture.oldRunId, consumption.getString("oldRunId"))
        assertEquals(binding.runId, consumption.getString("newRunId"))
        assertEquals(fixture.oldWs, consumption.getString("oldWsRunId"))
        assertEquals(fixture.oldNonce, consumption.getString("oldNonce"))
        assertEquals(newWs, consumption.getString("wsRunId"))
        assertEquals(newNonce, consumption.getString("nonce"))
        assertEquals(fixture.newBoot, consumption.getString("newBootId"))
        assertEquals(sha256(receiptBytes), consumption.getString("receiptSha256"))
    }

    @Test fun continuationSecondConsumptionAndOldBindingReuseAreRejectedOnFreshFixtures() {
        val second = continuationFixture("continuation-second-consumption")
        second.store.beginWine("WS-" + "1".repeat(32), "2".repeat(32))
        assertAdmissionRejected { second.store.beginWine("WS-" + "3".repeat(32), "4".repeat(32)) }

        val oldWs = continuationFixture("continuation-old-ws-reuse")
        oldWs.store.beginWine("WS-" + "5".repeat(32), "6".repeat(32))
        assertAdmissionRejected { oldWs.store.beginWine(oldWs.oldWs, "7".repeat(32)) }

        val oldNonce = continuationFixture("continuation-old-nonce-reuse")
        oldNonce.store.beginWine("WS-" + "8".repeat(32), "9".repeat(32))
        assertAdmissionRejected { oldNonce.store.beginWine("WS-" + "a".repeat(32), oldNonce.oldNonce) }
    }

    @Test fun successfulContinuationUnknownTerminalRejectsNewAdmissionAndOldLateWrites() {
        val fixture = continuationFixture("continuation-unknown-terminal")
        val binding = fixture.store.beginWine("WS-" + "1".repeat(32), "2".repeat(32))
        fixture.store.finish(binding.runId, ForeverRunStatus.FAILED, "diagnostic")
        fixture.store.writeWineResult(binding.runId, binding.wsRunId, binding.nonce, unknownResult())
        assertAdmissionRejected {
            fixture.store.beginWine("WS-" + "3".repeat(32), "4".repeat(32))
        }
        assertAdmissionRejected {
            fixture.store.update(fixture.oldRunId) { it.copy(message = "late old update") }
        }
        assertAdmissionRejected {
            fixture.store.recordWineRawFrame(
                fixture.oldRunId, fixture.oldWs, fixture.oldNonce, "native-stdout", "late\n".toByteArray(),
            )
        }
    }

    @Test fun concurrentDispositionConsumptionAllowsExactlyOneWinner() {
        val fixture = interruptedFixture("concurrent-disposition")
        val gate = CountDownLatch(1)
        val outcomes = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val threads = (0 until 2).map { index ->
            Thread {
                gate.await()
                outcomes += runCatching {
                    fixture.store.beginWine("WS-" + ("${index}a").repeat(16), ("${index}b").repeat(16))
                }.isSuccess
            }
        }
        threads.forEach { it.start() }
        gate.countDown()
        threads.forEach { it.join() }
        assertEquals(1, outcomes.count { it })
        assertEquals(2, outcomes.size)
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun assertAdmissionRejected(action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        assertTrue("corrupt authority must fail closed", failure != null)
        assertTrue(
            "unexpected fail-closed exception: ${failure!!::class.java.name}",
            failure is IllegalArgumentException || failure is org.json.JSONException,
        )
    }

    private fun unknownResult() = ForeverRunStore.WineResult(
        terminalStatus = "UNKNOWN", nativeStatus = "UNKNOWN", closureObserved = false,
        qualification = false, clientPass = false, supervisorExit = null, echild = false,
        witnessProof = ForeverRunStore.WineWitnessProof("UNKNOWN", false, false, false, -1),
        error = "diagnostic",
    )

    private fun positiveResult() = ForeverRunStore.WineResult(
        terminalStatus = "PASS", nativeStatus = "PASS", closureObserved = true,
        qualification = true, clientPass = true, supervisorExit = 0, echild = true,
        witnessProof = ForeverRunStore.WineWitnessProof("PASS", true, true, true, 0),
        error = null,
    )
}

/**
 * Test-only Linux-FD model for Robolectric SDK 29. The platform Os shadow in
 * that environment does not provide dup/fstat or directory descriptors. Each
 * virtual directory is represented by a regular temporary backing FD carrying
 * a path marker; ParcelFileDescriptor's existing shadow can therefore duplicate
 * it without changing the production descriptor traversal.
 */
@org.robolectric.annotation.Implements(android.system.Os::class, isInAndroidSdk = true)
class ForeverWineRunStoreTestShadowOs {
    private data class Handle(
        val path: Path,
        val stat: android.system.StructStat,
        val owner: FileInputStream,
    )

    companion object {
        private const val DIRECTORY_MARKER = "forever-test-directory:"
        private val handles = IdentityHashMap<FileDescriptor, Handle>()

        @JvmStatic
        @org.robolectric.annotation.Implementation
        fun open(path: String, flags: Int, mode: Int): FileDescriptor {
            val virtual = resolvePath(path)
            val stat = stat(virtual)
            require(!Files.isSymbolicLink(virtual)) { "test Os.open followed a symlink" }
            if (flags and 0x10000 != 0) { // Linux O_DIRECTORY, not exposed by the public Android SDK.
                require(android.system.OsConstants.S_ISDIR(stat.st_mode)) { "test Os.open expected a directory" }
            }
            val backing = File.createTempFile("forever-os", ".fd")
            backing.deleteOnExit()
            if (Files.isDirectory(virtual, LinkOption.NOFOLLOW_LINKS)) {
                backing.writeText(DIRECTORY_MARKER + virtual.toAbsolutePath())
            } else {
                backing.writeBytes(Files.readAllBytes(virtual))
            }
            val owner = FileInputStream(backing)
            val descriptor = owner.fd
            synchronized(handles) { handles[descriptor] = Handle(virtual, stat, owner) }
            return descriptor
        }

        @JvmStatic
        @org.robolectric.annotation.Implementation
        fun dup(fd: FileDescriptor): FileDescriptor {
            val source = synchronized(handles) { handles[fd] }
                ?: error("test Os.dup received an unknown descriptor")
            val backing = File.createTempFile("forever-os-dup", ".fd")
            backing.deleteOnExit()
            if (Files.isDirectory(source.path, LinkOption.NOFOLLOW_LINKS)) {
                backing.writeText(DIRECTORY_MARKER + source.path.toAbsolutePath())
            } else {
                backing.writeBytes(Files.readAllBytes(source.path))
            }
            val owner = FileInputStream(backing)
            val descriptor = owner.fd
            synchronized(handles) { handles[descriptor] = Handle(source.path, source.stat, owner) }
            return descriptor
        }

        @JvmStatic
        @org.robolectric.annotation.Implementation
        fun fstat(fd: FileDescriptor): android.system.StructStat = synchronized(handles) {
            handles[fd]?.stat ?: error("test Os.fstat received an unknown descriptor")
        }

        @JvmStatic
        @org.robolectric.annotation.Implementation
        fun lstat(path: String): android.system.StructStat = stat(resolvePath(path))

        @JvmStatic
        @org.robolectric.annotation.Implementation
        fun getuid(): Int = (Files.readAttributes(
            Paths.get("."), "unix:uid", LinkOption.NOFOLLOW_LINKS,
        )["uid"] as Number).toInt()

        @JvmStatic
        @org.robolectric.annotation.Implementation
        fun close(fd: FileDescriptor) {
            val handle = synchronized(handles) { handles.remove(fd) } ?: return
            handle.owner.close()
        }

        private fun resolvePath(path: String): Path {
            val match = Regex("^/proc/self/fd/(\\d+)(?:/(.*))?$").matchEntire(path)
            if (match == null) return Paths.get(path)
            val fdPath = Paths.get("/proc/self/fd/${match.groupValues[1]}")
            val target = Files.readSymbolicLink(fdPath)
            val marker = Files.readAllBytes(target).toString(Charsets.UTF_8)
            require(marker.startsWith(DIRECTORY_MARKER)) { "test FD is not a directory anchor" }
            val parent = Paths.get(marker.removePrefix(DIRECTORY_MARKER))
            val component = match.groupValues[2]
            return if (component.isEmpty()) parent else parent.resolve(component)
        }

        private fun stat(path: Path): android.system.StructStat {
            try {
                val attrs = Files.readAttributes(
                    path,
                    "unix:dev,ino,mode,nlink,uid,gid,rdev,size,lastAccessTime,lastModifiedTime,ctime",
                    LinkOption.NOFOLLOW_LINKS,
                )
                return android.system.StructStat(
                    (attrs["dev"] as Number).toLong(),
                    (attrs["ino"] as Number).toLong(),
                    (attrs["mode"] as Number).toInt(),
                    (attrs["nlink"] as Number).toLong(),
                    (attrs["uid"] as Number).toInt(),
                    (attrs["gid"] as Number).toInt(),
                    (attrs["rdev"] as Number).toLong(),
                    (attrs["size"] as Number).toLong(),
                    (attrs["lastAccessTime"] as java.nio.file.attribute.FileTime).to(TimeUnit.SECONDS),
                    (attrs["lastModifiedTime"] as java.nio.file.attribute.FileTime).to(TimeUnit.SECONDS),
                    (attrs["ctime"] as java.nio.file.attribute.FileTime).to(TimeUnit.SECONDS),
                    4096L,
                    0L,
                )
            } catch (missing: NoSuchFileException) {
                throw android.system.ErrnoException("lstat", android.system.OsConstants.ENOENT, missing)
            }
        }
    }
}

/** Test-only PFD bridge: Robolectric's default dup returns a wrapper with no real fd. */
@org.robolectric.annotation.Implements(
    android.os.ParcelFileDescriptor::class,
    isInAndroidSdk = true,
    callThroughByDefault = true,
)
class ForeverWineRunStoreTestShadowParcelFileDescriptor {
    @org.robolectric.annotation.RealObject
    private lateinit var realObject: android.os.ParcelFileDescriptor

    companion object {
        private val descriptors = IdentityHashMap<android.os.ParcelFileDescriptor, FileDescriptor>()

        @JvmStatic
        @org.robolectric.annotation.Implementation
        fun dup(fd: FileDescriptor): android.os.ParcelFileDescriptor {
            val duplicate = android.system.Os.dup(fd)
            val parcel = org.robolectric.shadow.api.Shadow.newInstanceOf(android.os.ParcelFileDescriptor::class.java)
            synchronized(descriptors) { descriptors[parcel] = duplicate }
            return parcel
        }

        private fun descriptor(parcel: android.os.ParcelFileDescriptor): FileDescriptor =
            synchronized(descriptors) {
                descriptors[parcel] ?: error("test PFD has no owned descriptor")
            }
    }

    @org.robolectric.annotation.Implementation
    fun getFd(): Int = org.robolectric.util.ReflectionHelpers.getField<Int>(descriptor(realObject), "fd")

    @org.robolectric.annotation.Implementation
    fun getFileDescriptor(): FileDescriptor = descriptor(realObject)

    @org.robolectric.annotation.Implementation
    fun close() {
        val fd = synchronized(descriptors) { descriptors.remove(realObject) } ?: return
        android.system.Os.close(fd)
    }
}
