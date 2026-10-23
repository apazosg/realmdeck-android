package app.gamenative

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import android.net.Uri
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ForeverHostSafetyTest {
    @org.junit.Before
    fun startWithAFreshSyntheticAndroidProcess() {
        // Robolectric replaces Application/filesDir between methods while
        // ordinary Kotlin object statics can survive in its reused sandbox.
        // A real Android process cannot switch its private directory this way.
        // Reset ONLY this test's fake supervisor state, never runtime files or
        // native processes, so both coordinated observer cases start isolated.
        val type = ForeverProcessDiagnostics::class.java
        for (name in listOf("pending", "handles", "setupFailedProcesses", "cancelledRuns", "patchWorkers")) {
            val field = type.getDeclaredField(name).apply { isAccessible = true }
            when (val value = field.get(null)) {
                is MutableMap<*, *> -> value.clear()
                is MutableSet<*> -> value.clear()
            }
        }
        for (name in listOf("store", "appContext", "listener")) {
            type.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
        }
        type.getDeclaredField("initialized").apply { isAccessible = true }.setBoolean(null, false)
        com.winlator.core.ProcessHelper.setExecObserver(null)
    }

    @Test fun assistedSessionSecuresWindowsAndPermanentlyDeniesItsManagedRunExport() {
        val firstActivity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val secondActivity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val context = firstActivity.applicationContext
        val store = ForeverRunStoreRegistry.get(context)
        val run = store.begin()
        val attemptId = "attempt-sensitive-test"
        ForeverRemoteDiagnosticsStore.persistIntent(context.filesDir,
            ForeverRemoteDiagnosticsStore.Intent(attemptId, System.currentTimeMillis(), emptyMap()))
        val session = requireNotNull(ForeverAssistedCredentialPrivacy.begin(context, firstActivity.window))
        try {
            assertTrue(ForeverAssistedCredentialPrivacy.isSensitive(context.filesDir))
            assertNotEquals(0, firstActivity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            assertTrue(session.protect(secondActivity.window))
            assertNotEquals(0, secondActivity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            assertEquals(ForeverAssistedCredentialPrivacy.MARKER_CONTENT,
                File(context.filesDir, ForeverAssistedCredentialPrivacy.RUN_AS_MARKER_PATH.removePrefix("files/")).readText())
        } finally {
            assertTrue(session.end())
        }
        assertFalse(ForeverAssistedCredentialPrivacy.isSensitive(context.filesDir))
        assertEquals(0, firstActivity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        assertEquals(0, secondActivity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        assertTrue(ForeverAssistedCredentialPrivacy.isSensitiveRun(context.filesDir, run.runId))
        assertTrue(ForeverAssistedCredentialPrivacy.isSensitiveAttempt(context.filesDir, attemptId))
        assertFalse(ForeverAssistedCredentialPrivacy.runIfAttemptDiagnosticsAllowed(context.filesDir, attemptId) {
            throw AssertionError("sensitive attempt admitted diagnostic output")
        })
        assertNull(ForeverRemoteDiagnosticsAndroid.outputSink(context))
        val remotePrepare = runCatching { ForeverRemoteDiagnosticsAndroid.prepare(context, attemptId, "synthetic") }
        assertTrue(remotePrepare.isFailure)
        assertTrue(remotePrepare.exceptionOrNull()!!.message.orEmpty().contains("sensitive assisted-credential attempt"))
        val export = runBlocking { store.export(run.runId, Uri.parse("content://synthetic/export")) }
        assertTrue(export.isFailure)
        assertTrue(requireNotNull(export.exceptionOrNull()).message.orEmpty().contains("sensitive assisted-credential RUN"))
    }

    @Test fun restartedGuestAdmissionRetiresOnlyTheOrphanedGlobalMarker() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val context = activity.applicationContext
        val filesDir = context.filesDir
        val run = ForeverRunStoreRegistry.get(context).begin()
        val attemptId = "attempt-orphan-recovery"
        ForeverRemoteDiagnosticsStore.persistIntent(filesDir,
            ForeverRemoteDiagnosticsStore.Intent(attemptId, System.currentTimeMillis(), emptyMap()))
        assertTrue(ForeverAssistedCredentialPrivacy.startSensitiveMarkerForTest(filesDir))
        assertTrue(ForeverAssistedCredentialPrivacy.markSensitiveRunForTest(filesDir, run.runId))
        val remote = ForeverRemoteDiagnosticsStore.root(filesDir)
        File(remote, "$attemptId-assisted-sensitive").writeBytes(byteArrayOf())
        // No in-memory Session exists, just as after process death/package update.
        assertNull(ForeverAssistedCredentialPrivacy.begin(context, activity.window))
        val gate = ForeverClientOperationLease(File(filesDir, "orphan-admission")) {
            RuntimeObservation.Quiescent
        }
        val ticket = gate.beginGuest("synthetic-storage") {
            ForeverFunctionalPoc.reconcileTerminatedAssistedSession(context)
        }
        assertFalse(ForeverAssistedCredentialPrivacy.isSensitive(filesDir))
        assertTrue(ForeverAssistedCredentialPrivacy.isSensitiveRun(filesDir, run.runId))
        assertTrue(ForeverAssistedCredentialPrivacy.isSensitiveAttempt(filesDir, attemptId))
        val newSession = requireNotNull(ForeverAssistedCredentialPrivacy.begin(context, activity.window))
        assertTrue(ForeverAssistedCredentialPrivacy.isSensitive(filesDir))
        assertTrue(newSession.end())
        gate.abortGuestBeforeStart(ticket)
    }

    @Test fun releasedProcessGuardCannotOvertakeOldSensitiveSessionCleanup() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val context = activity.applicationContext
        val filesDir = context.filesDir
        ForeverRunStoreRegistry.get(context).begin()
        val prepared = ForeverFunctionalPoc.Prepared(
            org.mockito.Mockito.mock(com.winlator.container.Container::class.java),
            File("unused-game"), File("unused-log"), "test", false, File("unused-executable"),
        )
        val field = ForeverFunctionalPoc::class.java.getDeclaredField("activeGuest").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val active = field.get(ForeverFunctionalPoc) as AtomicReference<ForeverFunctionalPoc.Prepared?>
        val previousActive = active.get()
        val previousEnvironment = PluviaApp.xEnvironment
        val guard = ForeverFunctionalClientStorage.ProcessGuard
        guard.resetForTests()
        val gate = ForeverClientOperationLease(File(filesDir, "delayed-privacy-admission")) {
            RuntimeObservation.Quiescent
        }
        try {
            PluviaApp.xEnvironment = null
            active.set(prepared)
            assertTrue(prepared.assistedEntryState.beginSensitiveCredentialSession(context, activity.window))
            val oldTicket = gate.beginGuest("synthetic-storage")
            val oldIo = requireNotNull(guard.acquireIo())
            val oldReservation = requireNotNull(guard.reserveGuest(oldIo))
            oldIo.close()
            assertTrue(guard.recoverGuest(oldReservation) {
                gate.recoverMatchingGuest(oldTicket)
                true
            })
            // Deterministically pause the old release at its real gap: the local
            // guard and durable ticket are free, but privacy/activeGuest are not.
            val nextIo = requireNotNull(guard.acquireIo())
            try {
                assertTrue(runCatching {
                    gate.beginGuest("synthetic-storage") {
                        ForeverFunctionalPoc.reconcileTerminatedAssistedSession(context)
                    }
                }.isFailure)
                assertTrue(ForeverAssistedCredentialPrivacy.isSensitive(filesDir))
                assertNull(gate.blockingReason())
                assertTrue(prepared.assistedEntryState.endSensitiveCredentialSessionAfterTermination())
                active.set(null)
                val next = gate.beginGuest("synthetic-storage") {
                    ForeverFunctionalPoc.reconcileTerminatedAssistedSession(context)
                }
                val session = requireNotNull(ForeverAssistedCredentialPrivacy.begin(context, activity.window))
                assertTrue(prepared.assistedEntryState.endSensitiveCredentialSessionAfterTermination())
                assertTrue(ForeverAssistedCredentialPrivacy.isSensitive(filesDir))
                assertTrue(session.end())
                gate.abortGuestBeforeStart(next)
            } finally { nextIo.close() }
        } finally {
            prepared.assistedEntryState.endSensitiveCredentialSessionAfterTermination()
            active.set(previousActive)
            PluviaApp.xEnvironment = previousEnvironment
            guard.resetForTests()
        }
    }

    @Test fun admittedUploadLeavesDiagnosticsLockFreeAndPreventsSessionUntilReleased() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val context = activity.applicationContext
        val filesDir = context.filesDir
        val attemptId = "attempt-upload-${java.util.UUID.randomUUID()}"
        val intentFile = File(ForeverRemoteDiagnosticsStore.root(filesDir), "latest-intent.json")
        val previousIntent = intentFile.takeIf { it.isFile }?.readBytes()
        val sensitiveAttempt = File(ForeverRemoteDiagnosticsStore.root(filesDir), "$attemptId-assisted-sensitive")
        val diagnosticWrite = File(filesDir, "upload-admission-diagnostic.txt")
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>(null)
        var uploadThread: Thread? = null
        var session: ForeverAssistedCredentialPrivacy.Session? = null
        try {
            ForeverRemoteDiagnosticsStore.persistIntent(filesDir,
                ForeverRemoteDiagnosticsStore.Intent(attemptId, System.currentTimeMillis(), emptyMap()))
            val upload = Thread {
                try {
                    ForeverAssistedCredentialPrivacy.withDiagnosticsUploadAdmission(filesDir) {
                        ready.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "synthetic upload was not released" }
                    }
                } catch (failure: Throwable) {
                    workerFailure.set(failure)
                }
            }
            uploadThread = upload
            upload.start()
            assertTrue("upload admission was not reached", ready.await(5, TimeUnit.SECONDS))

            assertTrue(ForeverAssistedCredentialPrivacy.runIfAttemptDiagnosticsAllowed(filesDir, attemptId) {
                diagnosticWrite.writeText("safe concurrent diagnostic")
            })
            assertEquals("safe concurrent diagnostic", diagnosticWrite.readText())
            assertNull(ForeverAssistedCredentialPrivacy.begin(context, activity.window))
            assertFalse(ForeverAssistedCredentialPrivacy.isSensitive(filesDir))

            release.countDown()
            upload.join(5_000)
            assertFalse("synthetic upload did not finish", upload.isAlive)
            assertNull(workerFailure.get())

            session = ForeverAssistedCredentialPrivacy.begin(context, activity.window)
            assertNotNull("session should be admitted after upload completion", session)
        } finally {
            release.countDown()
            uploadThread?.join(5_000)
            session?.end()
            diagnosticWrite.delete()
            sensitiveAttempt.delete()
            if (previousIntent != null) intentFile.writeBytes(previousIntent) else intentFile.delete()
        }
    }

    @Test fun sensitiveMarkerStopsOutputRetentionWithoutLosingEarlierSafeEvidence() {
        val filesDir = Files.createTempDirectory("forever-sensitive-marker").toFile()
        val output = File(filesDir, "output")
        try {
            BoundedLogRetention(output, "run-privacy", "stdout", sensitiveFilesDir = filesDir).use { retention ->
                retention.append("safe-before-session\n".toByteArray())
                retention.flush()
                File(filesDir, "forever/runs").mkdirs()
                assertTrue(ForeverAssistedCredentialPrivacy.markSensitiveRunForTest(filesDir, "run-privacy"))
                assertTrue(ForeverAssistedCredentialPrivacy.startSensitiveMarkerForTest(filesDir))
                retention.append("synthetic-private-output\n".toByteArray())
                retention.flush()
                assertTrue(ForeverAssistedCredentialPrivacy.endSensitiveMarkerForTest(filesDir))
                retention.append("synthetic-after-session\n".toByteArray())
                retention.flush()
                val saved = BoundedLogRetention.readPersisted(output, "run-privacy", "stdout")
                val text = String(saved.head + saved.tail)
                assertTrue(text.contains("safe-before-session"))
                assertFalse(text.contains("synthetic-private-output"))
                assertFalse(text.contains("synthetic-after-session"))
            }
        } finally { filesDir.deleteRecursively() }
    }

    @Test fun rawJournalWritesStopDuringSessionAndRunExportStaysBlockedAfterEnd() {
        val filesDir = Files.createTempDirectory("forever-sensitive-export").toFile()
        try {
            val store = ForeverRunStoreRegistry.forDirectory(filesDir)
            val run = store.begin()
            assertFalse(ForeverAssistedCredentialPrivacy.sharedGameLogsMayBeSensitive(filesDir))
            store.appendRaw(run.runId, org.json.JSONObject().put("event", "safe-before"))
            assertTrue(ForeverAssistedCredentialPrivacy.markSensitiveRunForTest(filesDir, run.runId))
            assertTrue(ForeverAssistedCredentialPrivacy.startSensitiveMarkerForTest(filesDir))
            store.appendRaw(run.runId, org.json.JSONObject().put("event", "synthetic-private-output"))
            store.updatePatchState(run.runId, "SAFE_STATUS_RETAINED")
            assertEquals("SAFE_STATUS_RETAINED", store.read(run.runId)!!.patchState)
            assertTrue(ForeverAssistedCredentialPrivacy.runIfDiagnosticsAllowed(filesDir) {
                throw AssertionError("sensitive session admitted a diagnostic write")
            }.not())
            assertTrue(ForeverAssistedCredentialPrivacy.endSensitiveMarkerForTest(filesDir))
            store.appendRaw(run.runId, org.json.JSONObject().put("event", "synthetic-late-private-output"))
            val journal = File(filesDir, "forever/runs/${run.runId}-diagnostics.jsonl").readText()
            assertTrue(journal.contains("safe-before"))
            assertFalse(journal.contains("synthetic-private-output"))
            assertFalse(journal.contains("synthetic-late-private-output"))
            assertTrue(ForeverAssistedCredentialPrivacy.isSensitiveRun(filesDir, run.runId))
            assertFalse(ForeverAssistedCredentialPrivacy.isSensitiveRun(filesDir, "RUN-unaffected"))
            assertTrue(ForeverAssistedCredentialPrivacy.sharedGameLogsMayBeSensitive(filesDir))
        } finally { filesDir.deleteRecursively() }
    }

    @Test fun failedPersistenceDoesNotPresentThePreviousSnapshotAsFresh() {
        val root = Files.createTempDirectory("forever-failed-persistence").toFile()
        val failing = AtomicBoolean(false)
        try {
            BoundedLogRetention(root, "run-io", "stderr", beforePublish = {
                if (failing.get()) throw IOException("synthetic full-disk publication failure")
            }).use { retention ->
                retention.append("saved-prefix".toByteArray())
                retention.flush()
                failing.set(true)
                retention.append("new-data-not-persisted".toByteArray())
                retention.flush()
                val previous = BoundedLogRetention.readPersisted(root, "run-io", "stderr")
                assertEquals("saved-prefix", String(previous.head))
                assertNotNull(previous.persistenceError)
                assertTrue(previous.persistenceError!!.contains("synthetic full-disk"))
                assertNotNull(retention.error())
                failing.set(false)
                retention.flush()
                val repaired = BoundedLogRetention.readPersisted(root, "run-io", "stderr")
                assertNull(repaired.persistenceError)
                assertNull(retention.error())
                assertTrue(String(repaired.head).contains("new-data-not-persisted"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptOrSymlinkedCurrentMetadataIsPreservedAndBlocksBegin() {
        val root = Files.createTempDirectory("forever-corrupt-metadata").toFile()
        val store = ForeverRunStoreRegistry.forDirectory(root)
        val current = File(root, "forever/current.json")
        try {
            store.begin()
            val corrupt = "{broken-metadata"
            current.writeText(corrupt)
            assertTrue(runCatching { store.readCurrent() }.isFailure)
            assertTrue(runCatching { store.begin() }.isFailure)
            assertEquals(corrupt, current.readText())
            val outside = File(root, "outside.json").also { it.writeText(corrupt) }
            current.delete(); Files.createSymbolicLink(current.toPath(), outside.toPath())
            assertTrue(runCatching { store.readCurrent() }.isFailure)
            assertTrue(runCatching { store.begin() }.isFailure)
            assertEquals(corrupt, outside.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun delayedQuiescenceCannotMutateTerminalMetadata() {
        val root = Files.createTempDirectory("forever-terminal-evidence").toFile()
        val store = ForeverRunStoreRegistry.forDirectory(root)
        try {
            val run = store.begin()
            store.finish(run.runId, ForeverRunStatus.INTERRUPTED, "unknown")
            val current = File(root, "forever/current.json").readBytes()
            val archived = File(root, "forever/runs/${run.runId}.json").readBytes()
            store.recordQuiescence(run.runId, true)
            assertArrayEquals(current, File(root, "forever/current.json").readBytes())
            assertArrayEquals(archived, File(root, "forever/runs/${run.runId}.json").readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun malformedBootIdentifiersCannotReleaseUnknownProcessOwnership() {
        val live = "11111111-1111-1111-1111-111111111111"
        val next = "22222222-2222-2222-2222-222222222222"
        val base = ForeverRunState("RUN-boot-test", ForeverStage.RUNNING, ForeverRunStatus.INTERRUPTED,
            launchAttempted = true, terminationUnknown = true, bootId = live)
        assertFalse(ForeverProcIdentityPolicy.recoveredQuiescent(base, "a".repeat(36)))
        assertFalse(ForeverProcIdentityPolicy.recoveredQuiescent(base.copy(bootId = "boot-old"), next))
        assertFalse(ForeverProcIdentityPolicy.recoveredQuiescent(base.copy(bootId = "a".repeat(36)), next))
        assertFalse(ForeverProcIdentityPolicy.recoveredQuiescent(base, live.uppercase()))
        assertTrue(ForeverProcIdentityPolicy.recoveredQuiescent(base, next))
    }

    @Test fun cancelledBetweenGateAndStartedStillOwnsAndDestroysLateProcess() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        ForeverProcessDiagnostics.initialize(context)
        val store = ForeverRunStoreRegistry.get(context)
        val run = store.begin()
        val cwd = Files.createTempDirectory("forever-cancelled-start").toFile()
        val executable = File(cwd, "WowB-ARM64.exe").also { it.writeBytes(byteArrayOf(0)) }
        val command = "wine explorer /desktop=shell,1280x720 \"A:\\WowB-ARM64.exe\" -config BetaSuspendedTest.wtf -d3d11"
        val env = arrayOf("WOWFOREVER_RUN_ID=${run.runId}")
        val observerField = com.winlator.core.ProcessHelper::class.java.getDeclaredField("execObserver").apply { isAccessible = true }
        val observer = observerField.get(null) as com.winlator.core.ProcessHelper.ExecObserver
        var destroyed = false
        val process = object : Process() {
            override fun getInputStream() = java.io.ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream() = java.io.ByteArrayInputStream(byteArrayOf())
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun waitFor() = 0
            override fun exitValue(): Int { if (!destroyed) throw IllegalThreadStateException(); return 0 }
            override fun destroy() { destroyed = true }
            override fun isAlive() = !destroyed
        }
        try {
            ForeverProcessDiagnostics.expectLaunch(run.runId, executable, File(cwd, "contract.json").path)
            assertTrue(observer.beforeStart(command, env, cwd))
            ForeverProcessDiagnostics.cancelExpected(run.runId)
            assertEquals(ForeverProcessDiagnostics.StopResult.UNKNOWN, ForeverProcessDiagnostics.stopOwned(run.runId))
            store.finish(run.runId, ForeverRunStatus.CANCELLED, "cancelled before callback")
            val terminal = store.read(run.runId)!!.toJson().toString()
            val started = observer.onStarted(command, env, cwd, process)
            assertTrue("late root was not registered; diagnostics=" +
                File(context.filesDir, "forever/runs/${run.runId}-diagnostics.jsonl").let { if (it.exists()) it.readText() else "missing" }, started)
            assertTrue(destroyed)
            observer.onFinished(process, 0)
            assertEquals(terminal, store.read(run.runId)!!.toJson().toString())
            assertFalse(ForeverProcessDiagnostics.canStartNewRun(store.read(run.runId)))
        } finally { cwd.deleteRecursively() }
    }

    @Test fun guestElfArchitecturesAreNotValidHostPreloads() {
        val root = Files.createTempDirectory("forever-elf-machine").toFile()
        val elf = File(root, "libhost.so")
        val inspect = ForeverRuntimeEvidence::class.java.getDeclaredMethod("inspectElf", File::class.java, List::class.java)
            .apply { isAccessible = true }
        try {
            for (machine in listOf(62, 3, 183)) {
                val bytes = ByteArray(64)
                byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1).copyInto(bytes)
                java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(18, machine.toShort())
                elf.writeBytes(bytes)
                val result = inspect.invoke(ForeverRuntimeEvidence, elf, listOf(root.canonicalFile)) as org.json.JSONObject
                assertEquals(if (machine == 183) "PASS" else "UNKNOWN", result.getString("status"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun ownershipIoFailureAfterExecDestroysOnlyThatProcessAndRemainsUnknown() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        ForeverProcessDiagnostics.initialize(context)
        val store = ForeverRunStoreRegistry.get(context)
        val run = store.begin()
        val cwd = Files.createTempDirectory("forever-post-exec-io").toFile()
        val executable = File(cwd, "WowB-ARM64.exe").also { it.writeBytes(byteArrayOf(0)) }
        val command = "wine explorer /desktop=shell,1280x720 \"A:\\WowB-ARM64.exe\" -config BetaSuspendedTest.wtf -d3d11"
        val env = arrayOf("WOWFOREVER_RUN_ID=${run.runId}")
        val observerField = com.winlator.core.ProcessHelper::class.java.getDeclaredField("execObserver").apply { isAccessible = true }
        val observer = observerField.get(null) as com.winlator.core.ProcessHelper.ExecObserver
        val blockedOutput = File(context.filesDir, "forever/runs/${run.runId}")
        var destroyed = false
        val process = object : Process() {
            override fun getInputStream() = java.io.ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream() = java.io.ByteArrayInputStream(byteArrayOf())
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun waitFor() = 0
            override fun exitValue(): Int { if (!destroyed) throw IllegalThreadStateException(); return 0 }
            override fun destroy() { destroyed = true }
            override fun isAlive() = !destroyed
        }
        try {
            ForeverProcessDiagnostics.expectLaunch(run.runId, executable, File(cwd, "contract.json").path)
            assertTrue(observer.beforeStart(command, env, cwd))
            // A file where the private output directory should be reproduces
            // a post-exec setup error without executing a real child binary.
            blockedOutput.writeText("not a directory")
            assertFalse(observer.onStarted(command, env, cwd, process))
            assertTrue(destroyed)
            assertTrue(store.read(run.runId)!!.terminationUnknown)
            assertFalse(ForeverProcessDiagnostics.canStartNewRun(store.read(run.runId)))
            store.finish(run.runId, ForeverRunStatus.FAILED, "post-exec IO error")
            val terminal = store.read(run.runId)!!.toJson().toString()
            observer.onFinished(process, 0)
            assertEquals(terminal, store.read(run.runId)!!.toJson().toString())
        } finally {
            // Restore only the deliberately introduced test file. Robolectric
            // may reuse its application directory/clock for the next test.
            if (blockedOutput.isFile && blockedOutput.readText() == "not a directory") blockedOutput.delete()
            cwd.deleteRecursively()
        }
    }
}
