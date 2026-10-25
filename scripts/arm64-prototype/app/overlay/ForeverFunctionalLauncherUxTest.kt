package app.gamenative

import android.content.pm.ActivityInfo
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Home state decisions: a live download dominates partial-file labels, an
 * unavailable store routes to storage selection, and READY never claims a
 * full integrity proof (that wording lives in ClientReadiness.label).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ForeverFunctionalLauncherUxTest {
    @Test
    fun accountButtonHidesOnlyAfterThisLaunchVerifiesPatchApplication() {
        val pending = ForeverFunctionalPocPatchSession.PatchStatus(
            ForeverFunctionalPocPatchSession.PatchAttemptPhase.PENDING,
        )
        assertTrue(assistedAccountButtonVisible(null))
        assertTrue(assistedAccountButtonVisible(pending))
        assertTrue(assistedAccountButtonVisible(pending.copy(
            phase = ForeverFunctionalPocPatchSession.PatchAttemptPhase.DISABLED,
        )))
        assertTrue(assistedAccountButtonVisible(pending.copy(
            phase = ForeverFunctionalPocPatchSession.PatchAttemptPhase.FAILED,
        )))
        val verified = pending.copy(
            phase = ForeverFunctionalPocPatchSession.PatchAttemptPhase.VERIFIED,
            everApplied = true,
        )
        assertEquals(false, assistedAccountButtonVisible(verified))
        assertEquals(false, assistedAccountButtonVisible(verified.copy(
            phase = ForeverFunctionalPocPatchSession.PatchAttemptPhase.ENDED,
        )))
        assertTrue(assistedAccountButtonVisible(verified.copy(captureOnly = true)))
        // A fresh launch's status cannot inherit the previous launch's visibility.
        assertTrue(assistedAccountButtonVisible(pending))
    }

    @Test
    fun accessoryKeysSendBalancedTapsAndReleaseStickyModifiers() {
        val events = mutableListOf<Pair<Int, Int>>()
        val keys = ForeverFunctionalKeyboardKeys { keyCode, action -> events += keyCode to action }

        keys.tap(android.view.KeyEvent.KEYCODE_ESCAPE)
        assertEquals(
            listOf(
                android.view.KeyEvent.KEYCODE_ESCAPE to android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_ESCAPE to android.view.KeyEvent.ACTION_UP,
            ),
            events,
        )

        assertTrue(keys.toggleModifier(android.view.KeyEvent.KEYCODE_CTRL_LEFT))
        assertTrue(keys.toggleModifier(android.view.KeyEvent.KEYCODE_ALT_LEFT))
        assertEquals(false, keys.toggleModifier(android.view.KeyEvent.KEYCODE_CTRL_LEFT))
        keys.releaseModifiers()
        assertEquals(
            listOf(
                android.view.KeyEvent.KEYCODE_ESCAPE to android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_ESCAPE to android.view.KeyEvent.ACTION_UP,
                android.view.KeyEvent.KEYCODE_CTRL_LEFT to android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_ALT_LEFT to android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_CTRL_LEFT to android.view.KeyEvent.ACTION_UP,
                android.view.KeyEvent.KEYCODE_ALT_LEFT to android.view.KeyEvent.ACTION_UP,
            ),
            events,
        )
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun wineExitPresentationChangesRunOnMainThread() {
        // Do not run onCreate: this test only exercises the terminal callback,
        // not native runtime/bootstrap or the Compose screen.
        val activity = Robolectric.buildActivity(ForeverFunctionalPocActivity::class.java).get()
        val callback = ForeverFunctionalPocActivity::class.java.getDeclaredMethod(
            "setWineGuiRunning", java.lang.Boolean.TYPE,
        ).apply { isAccessible = true }
        callback.invoke(activity, true)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, activity.requestedOrientation)

        val exit = FutureTask<Unit> { callback.invoke(activity, false); Unit }
        Thread(exit, "gamenative-process-wait-test").start()
        exit.get(5, TimeUnit.SECONDS)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, activity.requestedOrientation)

        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
    }

    private fun readiness(
        state: ForeverFunctionalClientStorage.ClientInspection.State =
            ForeverFunctionalClientStorage.ClientInspection.State.NOT_INSTALLED,
        storageAvailable: Boolean = true,
        folder: Boolean = false,
        executable: Boolean = false,
        config: Boolean = false,
        data: Boolean = false,
    ) = ForeverFunctionalLauncherUx.ClientReadiness(
        folderPresent = folder,
        executablePresent = executable,
        originalConfigPresent = config,
        dataPresent = data,
        storageAvailable = storageAvailable,
        storageState = state,
    )

    private fun readyReadiness() = readiness(
        state = ForeverFunctionalClientStorage.ClientInspection.State.READY,
        folder = true,
        executable = true,
        config = true,
        data = true,
    )

    private fun derive(
        checking: Boolean = false,
        storageKnown: Boolean = true,
        readiness: ForeverFunctionalLauncherUx.ClientReadiness = readiness(),
        downloadBusy: Boolean = false,
        downloadApplying: Boolean = false,
    ) = ForeverFunctionalLauncherUx.deriveHomeState(
        checking = checking,
        storageKnown = storageKnown,
        readiness = readiness,
        downloadBusy = downloadBusy,
        downloadApplying = downloadApplying,
    )

    @Test
    fun activeDownloadDominatesEvenInitialSnapshot() {
        // The live download is the truthful operation: it wins over the
        // initial checking snapshot and over a fully ready local state.
        assertEquals(
            ForeverFunctionalLauncherUx.HomeState.Downloading(applying = false),
            derive(checking = true, readiness = readyReadiness(), downloadBusy = true),
        )
        assertEquals(
            ForeverFunctionalLauncherUx.HomeState.Downloading(applying = true),
            derive(readiness = readyReadiness(), downloadBusy = true, downloadApplying = true),
        )
    }

    @Test
    fun checkingShowsOnlyBeforeAnyDownload() {
        assertEquals(
            ForeverFunctionalLauncherUx.HomeState.Checking,
            derive(checking = true, storageKnown = false),
        )
    }

    @Test
    fun unknownOrUnavailableStorageFailsClosed() {
        // Unchecked storage never offers an actionable ready/missing CTA.
        assertEquals(
            ForeverFunctionalLauncherUx.HomeState.StorageUnavailable,
            derive(storageKnown = false),
        )
        assertEquals(
            ForeverFunctionalLauncherUx.HomeState.StorageUnavailable,
            derive(storageKnown = false, readiness = readyReadiness()),
        )
        assertEquals(
            ForeverFunctionalLauncherUx.HomeState.StorageUnavailable,
            derive(readiness = readiness(storageAvailable = false)),
        )
    }

    @Test
    fun liveDownloadDominatesMissingFiles() {
        val missing = readiness()
        assertEquals(
            ForeverFunctionalLauncherUx.HomeState.Downloading(applying = false),
            derive(readiness = missing, downloadBusy = true),
        )
    }

    @Test
    fun missingIncompleteAndUnverifiedMapToHonestStates() {
        assertEquals(ForeverFunctionalLauncherUx.HomeState.Missing, derive(readiness = readiness()))
        assertEquals(
            ForeverFunctionalLauncherUx.HomeState.Incomplete,
            derive(
                readiness = readiness(
                    state = ForeverFunctionalClientStorage.ClientInspection.State.INCOMPLETE,
                    folder = true,
                ),
            ),
        )
        // Present-but-unverified files are not a repair claim: the manager
        // re-detects; the home state only routes there honestly.
        assertEquals(
            ForeverFunctionalLauncherUx.HomeState.Incomplete,
            derive(
                readiness = readiness(
                    state = ForeverFunctionalClientStorage.ClientInspection.State.PRESENT_NOT_VERIFIED,
                    folder = true,
                    executable = true,
                    config = true,
                    data = true,
                ),
            ),
        )
        val pasted = readiness(
            state = ForeverFunctionalClientStorage.ClientInspection.State.PASTED_SOURCE,
            folder = true,
            data = true,
        )
        assertEquals(ForeverFunctionalLauncherUx.HomeState.Incomplete, derive(readiness = pasted))
        assertEquals("Cliente pegado detectado; sin verificar", pasted.label)
    }

    @Test
    fun pastedClientVerdictsHavePlainSpanishCopy() {
        assertEquals(
            "No hay ningún cliente pegado todavía.",
            ForeverFunctionalUiText.pastedClientVerdictText(PastedClientVerdict.NONE),
        )
        assertEquals(
            "Lo que hay pegado no se reconoce como un cliente: falta la carpeta Data o su índice.",
            ForeverFunctionalUiText.pastedClientVerdictText(PastedClientVerdict.NOT_CASC),
        )
        assertEquals(
            "Lo pegado no es el cliente de WoW Classic Beta (producto, región o idioma distintos).",
            ForeverFunctionalUiText.pastedClientVerdictText(PastedClientVerdict.WRONG_PRODUCT),
        )
        assertEquals(
            "Lo pegado es un cliente x86_64. Esta app necesita el cliente ARM64.",
            ForeverFunctionalUiText.pastedClientVerdictText(PastedClientVerdict.NOT_ARM64),
        )
        assertEquals(
            "Se ha detectado la versión de cliente ARM64 que busca esta app. Cuánto se reutiliza y qué falta por descargar se calcula después, al preparar la actualización.",
            ForeverFunctionalUiText.pastedClientVerdictText(PastedClientVerdict.TARGET_BUILD),
        )
        assertEquals(
            "Es un cliente ARM64 de un build anterior. Se puede reutilizar como fuente; lo que falte por descargar se calcula después, al preparar la actualización.",
            ForeverFunctionalUiText.pastedClientVerdictText(PastedClientVerdict.PRIOR_BUILD),
        )
        assertEquals(
            "Es un cliente ARM64 de una versión no reconocida. Se puede reutilizar su contenido como fuente tras validarlo; lo que falte se descarga después.",
            ForeverFunctionalUiText.pastedClientVerdictText(PastedClientVerdict.UNKNOWN_BUILD_AS_SOURCE),
        )
    }

    @Test
    fun readyRequiresKnownAvailableStorageAndFullLocalPresence() {
        assertEquals(
            ForeverFunctionalLauncherUx.HomeState.Ready,
            derive(readiness = readyReadiness()),
        )
        // READY is local presence only: the label must not claim verification.
        assertTrue(
            readyReadiness().label.contains("preparar", ignoreCase = true) ||
                readyReadiness().label.contains("comprobar", ignoreCase = true),
        )
    }

    @Test
    fun localPresenceOffersPlayWithoutPretendingIntegrityOrRequiringAnUpdate() {
        val managed = readiness(
            state = ForeverFunctionalClientStorage.ClientInspection.State.PRESENT_NOT_VERIFIED,
            folder = true,
            executable = true,
            data = true,
        )
        assertTrue(managed.canRequestPlay)
        assertEquals(false, managed.ready)
        assertEquals(false, managed.copy(storageAvailable = false).canRequestPlay)
        assertEquals(false, managed.copy(dataPresent = false).canRequestPlay)
        assertEquals(false, managed.copy(executablePresent = false).canRequestPlay)
        assertEquals(false, managed.copy(
            storageState = ForeverFunctionalClientStorage.ClientInspection.State.PASTED_SOURCE,
        ).canRequestPlay)
    }

    @Test
    fun downloadTextAndFractionStayHonestWithoutDenominator() {
        assertEquals(
            "Descargando 2.0 KiB de 10.0 KiB",
            ForeverFunctionalUiText.downloadStatusText(
                ForeverClientUpdateProgress(
                    ForeverClientUpdateProgress.Phase.DOWNLOADING, 3, 10, 2048, 10240,
                ),
                "Descargando…",
            ),
        )
        val noDenominator = ForeverClientUpdateProgress(
            ForeverClientUpdateProgress.Phase.DOWNLOADING, 0, 0, 10L, 0L,
        )
        // No honest denominator: no bytes, no percentage, just the live text.
        assertEquals("Descargando…", ForeverFunctionalUiText.downloadStatusText(noDenominator, "Descargando…"))
        assertEquals(null, ForeverFunctionalUiText.downloadFraction(noDenominator))
        assertEquals(null, ForeverFunctionalUiText.downloadPercent(noDenominator))
        // Out-of-range counters are invalid, never clamped to a fake 100 %:
        // no bar/percentage and a phase-appropriate truthful fallback.
        val overTotal = ForeverClientUpdateProgress(
            ForeverClientUpdateProgress.Phase.DOWNLOADING, 11, 10, 20480, 10240,
        )
        assertEquals("Descargando…", ForeverFunctionalUiText.downloadStatusText(overTotal, "Descargando…"))
        assertEquals(null, ForeverFunctionalUiText.downloadFraction(overTotal))
        val overObjects = ForeverClientUpdateProgress(
            ForeverClientUpdateProgress.Phase.SCANNING, 11, 10, 0, 0,
        )
        assertEquals("Comprobando…", ForeverFunctionalUiText.downloadStatusText(overObjects, "Comprobando…"))
        assertEquals(null, ForeverFunctionalUiText.downloadFraction(overObjects))
        // Atomic apply/recover phases never expose a pause affordance basis.
        assertEquals(
            "Aplicando los archivos…",
            ForeverFunctionalUiText.downloadStatusText(
                ForeverClientUpdateProgress(ForeverClientUpdateProgress.Phase.APPLYING, 1, 1, 1, 1),
                "Comprobando…",
            ),
        )
        assertEquals(
            null,
            ForeverFunctionalUiText.downloadFraction(
                ForeverClientUpdateProgress(ForeverClientUpdateProgress.Phase.RECOVERING, 1, 1, 1, 1),
            ),
        )
    }

    @Test
    fun userMessageNeverLeaksRawCodesOrPaths() {
        val installed = ForeverFunctionalUiText.userMessage(
            ForeverFunctionalClientStorage.StorageException("client_not_installed"),
        )
        assertTrue(installed.contains("Descargar cliente"))
        assertTrue("client_not_installed" !in installed)
        val busy = ForeverFunctionalUiText.userMessage(
            ForeverFunctionalClientStorage.StorageException("storage_busy"),
        )
        assertTrue("storage_busy" !in busy)
        val foreignMarker = ForeverFunctionalUiText.userMessage(
            ForeverFunctionalClientStorage.StorageException("storage_marker_invalid"),
        )
        assertTrue(foreignMarker.contains("marcador de almacenamiento"))
        assertTrue(foreignMarker.contains("Data/ y .build.info"))
        // The generic fallback claims no resume-data preservation.
        val generic = ForeverFunctionalUiText.userMessage(Exception("something odd"))
        assertTrue("reanudación" !in generic)
    }

    @Test
    fun containerRegistrationMismatchStaysHonestWithoutPaths() {
        val mismatch = ForeverFunctionalUiText.userMessage(
            ForeverFunctionalClientStorage.StorageException("container_registration_mismatch"),
        )
        assertTrue(mismatch.contains("Comprueba la unidad seleccionada"))
        assertTrue("container_registration_mismatch" !in mismatch)
    }

    @Test
    fun missingAdmissionIsReportedDistinctFromCorruptExecutable() {
        val unavailable = ForeverFunctionalUiText.userMessage(
            ForeverFunctionalClientStorage.StorageException("client_admission_unavailable"),
        )
        assertTrue(unavailable.contains("admisión"))
        assertTrue(unavailable.contains("No significa que el ejecutable esté dañado"))
        assertTrue("client_admission_unavailable" !in unavailable)
        val invalid = ForeverFunctionalUiText.userMessage(
            ForeverFunctionalClientStorage.StorageException("client_executable_invalid"),
        )
        assertTrue(invalid != unavailable)
        val pending = ForeverFunctionalUiText.userMessage(
            ForeverFunctionalClientStorage.StorageException("client_update_pending"),
        )
        assertTrue(pending.contains("sin terminar"))
        assertTrue("client_update_pending" !in pending)
        assertTrue(pending != unavailable)
    }

    @Test
    fun planningGateNeverClaimsARecoveryFailed() {
        val expected =
            "Hay una descarga sin terminar ligada a su catálogo original. Revisa ese contexto antes de continuar."
        listOf(
            "recover the unfinished client transaction before planning or updating",
            "recover the interrupted rollback-stage reset before planning or updating",
            ForeverClientUpdateService.FROZEN_RESUME_REQUIRED_MESSAGE,
        ).forEach { raw ->
            assertEquals("planning gate must stay truthful for: $raw", expected, ForeverFunctionalUiText.userMessage(Exception(raw)))
        }
    }

    @Test
    fun guestRecoveryBlocksStayDistinctAndNeverLeakRawVerdicts() {
        // US-004 AC-10: live runtime and unproven shutdown keep exclusion
        // with distinct brief retry texts, from both the lease wording and
        // the fail-closed gate verdicts.
        val active = listOf(
            "own Wine runtime is active during guest recovery",
            "own Wine runtime is active during guest admission",
            "own_runtime_active",
        ).map { ForeverFunctionalUiText.userMessage(Exception(it)) }
        assertTrue(active.all { it == "WoW sigue activo. Ciérralo y reintenta." })
        val unknown = listOf(
            "own Wine runtime is unknown during guest recovery (proc_unreadable)",
            "own Wine runtime is unknown during guest admission (proc_unreadable)",
            "own_runtime_unknown:proc_unreadable",
        ).map { ForeverFunctionalUiText.userMessage(Exception(it)) }
        assertTrue(unknown.all { it == "No se pudo comprobar que WoW esté cerrado. Reintenta." })
        assertTrue(active.first() != unknown.first())
        // A stale reservation owned by another destination or ticket is
        // never recovered: fail closed without leaking the raw verdict.
        val mismatch = listOf(
            "guest recovery ticket does not own the durable record",
            "guest recovery storage ID does not match the frozen record",
            "there is no unfinished guest operation to recover",
            "guest_recovery_ticket_missing",
            "guest_recovery_reservation_missing",
        ).map { ForeverFunctionalUiText.userMessage(Exception(it)) }
        val expectedMismatch =
            "La reserva anterior no coincide con el destino elegido o ya no está vigente. Revisa el almacenamiento y reintenta."
        assertTrue(mismatch.all { it == expectedMismatch })
        (active + unknown + mismatch).forEach { text ->
            assertTrue("own_" !in text)
            assertTrue("guest_recovery" !in text)
        }
    }

    // Enter-gated observer (codeCaptureEnabled=false): short injectable
    // durations keep these focused without production configuration changes.

    private fun gatedSession(
        totalMillis: Long = 10_000L,
        activeMillis: Long = 1_000L,
        pollIntervalMillis: Long = 10L,
        poll: (String) -> String = { "status=NOT_FOUND" },
        end: (String) -> String = { "status=ENDED" },
        apply: ((String) -> String)? = null,
        onScan: (String) -> Unit = {},
        onApply: (String) -> Unit = {},
        onStatus: (ForeverFunctionalPocPatchSession.PatchStatus) -> Unit = {},
    ): ForeverFunctionalPocPatchSession.GatedPollSession {
        val polls = pollCalls
        val ends = endCalls
        val applies = applyCalls
        return ForeverFunctionalPocPatchSession.GatedPollSession(
            token = "functional-test-${java.util.UUID.randomUUID()}",
            totalMillis = totalMillis,
            activeMillis = activeMillis,
            pollIntervalMillis = pollIntervalMillis,
            clockMs = { System.nanoTime() / 1_000_000L },
            poll = { token ->
                polls.incrementAndGet()
                poll(token)
            },
            end = { token ->
                try {
                    end(token)
                } finally {
                    // Counted only after the end callback returns, so waiting
                    // on it proves the release ran instead of racing isEnded.
                    ends.incrementAndGet()
                }
            },
            onScan = onScan,
            apply = apply?.let { fn ->
                { token: String ->
                    applies.incrementAndGet()
                    fn(token)
                }
            },
            onApply = onApply,
            onStatus = onStatus,
        )
    }

    private val pollCalls = AtomicInteger(0)
    private val endCalls = AtomicInteger(0)
    private val applyCalls = AtomicInteger(0)

    private fun awaitEnds(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (endCalls.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10L)
        return endCalls.get() > 0
    }

    private fun awaitCount(calls: AtomicInteger, want: Int, timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (calls.get() < want && System.nanoTime() < deadline) Thread.sleep(10L)
        return calls.get() >= want
    }

    private fun awaitEndsCount(want: Int, timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (endCalls.get() < want && System.nanoTime() < deadline) Thread.sleep(10L)
        return endCalls.get() >= want
    }

    private fun strictCandidatePoll(status: String = "INCOMPLETE"): String =
        "{\"status\":\"$status\",\"readOnly\":true,\"writeAuthorized\":false,\"bytesWritten\":0," +
            "\"fullScan\":{\"mode\":\"full-readable\",\"validatedStoreCount\":1," +
            "\"candidateCapped\":false,\"readOnly\":true,\"writeAuthorized\":false}}"

    private fun emptyCandidatePoll(): String =
        "{\"status\":\"INCOMPLETE\",\"readOnly\":true,\"writeAuthorized\":false,\"bytesWritten\":0," +
            "\"fullScan\":{\"mode\":\"full-readable\",\"validatedStoreCount\":0," +
            "\"candidateCapped\":false,\"readOnly\":true,\"writeAuthorized\":false}}"

    private fun cappedCandidatePoll(): String =
        "{\"status\":\"INCOMPLETE\",\"readOnly\":true,\"writeAuthorized\":false,\"bytesWritten\":0," +
            "\"fullScan\":{\"mode\":\"full-readable\",\"validatedStoreCount\":1," +
            "\"candidateCapped\":true,\"readOnly\":true,\"writeAuthorized\":false}}"

    private fun appliedResult(): String =
        "{\"status\":\"APPLIED\",\"readOnly\":false,\"writeAuthorized\":true,\"bytesWritten\":32}"

    private fun deniedResult(): String =
        "{\"status\":\"DENIED\",\"readOnly\":false,\"writeAuthorized\":true,\"bytesWritten\":0}"

    private fun errorResult(): String =
        "{\"status\":\"ERROR\",\"errorKind\":\"identity\",\"error\":\"target_changed\"," +
            "\"readOnly\":false,\"writeAuthorized\":true,\"bytesWritten\":0}"

    @Test
    fun strictCandidateInIncompleteScanAppliesOnceThenMaintainsWithoutRescanning() {
        val scans = java.util.Collections.synchronizedList(mutableListOf<String>())
        val applied = java.util.Collections.synchronizedList(mutableListOf<String>())
        val session = gatedSession(
            totalMillis = 10_000L,
            activeMillis = 800L,
            pollIntervalMillis = 10L,
            poll = { strictCandidatePoll() },
            apply = { appliedResult() },
            onScan = { scans.add(it) },
            onApply = { applied.add(it) },
        )
        session.launch()
        assertEquals(true, session.arm())
        assertEquals(true, awaitCount(applyCalls, 2, 5_000L))
        // Maintain mode re-applies the same native candidate: no second full scan.
        assertEquals(1, pollCalls.get())
        assertEquals(1, scans.size)
        assertEquals(strictCandidatePoll(), scans[0])
        assertTrue(applied.isNotEmpty())
        assertTrue(applied.all { it.contains("APPLIED") })
        assertEquals(true, awaitEnds(5_000L))
        assertEquals(1, endCalls.get())
    }

    @Test
    fun emptyCandidateNeverApplies() {
        val session = gatedSession(
            totalMillis = 10_000L,
            activeMillis = 400L,
            pollIntervalMillis = 10L,
            poll = { emptyCandidatePoll() },
            apply = { appliedResult() },
        )
        session.launch()
        assertEquals(true, session.arm())
        assertEquals(true, awaitEnds(5_000L))
        assertTrue(pollCalls.get() > 1)
        assertEquals(0, applyCalls.get())
        assertEquals(1, endCalls.get())
    }

    @Test
    fun cappedCandidateNeverApplies() {
        val session = gatedSession(
            totalMillis = 10_000L,
            activeMillis = 400L,
            pollIntervalMillis = 10L,
            poll = { cappedCandidatePoll() },
            apply = { appliedResult() },
        )
        session.launch()
        assertEquals(true, session.arm())
        assertEquals(true, awaitEnds(5_000L))
        assertTrue(pollCalls.get() > 1)
        assertEquals(0, applyCalls.get())
        assertEquals(1, endCalls.get())
    }

    @Test
    fun defaultObserverWithoutApplyCallbackNeverWrites() {
        val scans = java.util.Collections.synchronizedList(mutableListOf<String>())
        val session = gatedSession(
            totalMillis = 10_000L,
            activeMillis = 400L,
            pollIntervalMillis = 10L,
            poll = { strictCandidatePoll() },
            onScan = { scans.add(it) },
        )
        session.launch()
        assertEquals(true, session.arm())
        assertEquals(true, awaitEnds(5_000L))
        assertTrue(pollCalls.get() > 1)
        assertTrue(scans.isNotEmpty())
        assertEquals(0, applyCalls.get())
        assertEquals(1, endCalls.get())
    }

    @Test
    fun cancelledOrExpiredLeaseNeverApplies() {
        // Cancel while a writer poll is blocked: the gated apply never runs.
        val enteredCancel = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val cancelled = gatedSession(
            poll = {
                enteredCancel.countDown()
                releaseCancel.await(5L, TimeUnit.SECONDS)
                strictCandidatePoll()
            },
            apply = { appliedResult() },
        )
        cancelled.launch()
        assertEquals(true, cancelled.arm())
        assertEquals(true, enteredCancel.await(5L, TimeUnit.SECONDS))
        cancelled.cancel()
        releaseCancel.countDown()
        assertEquals(true, awaitEnds(5_000L))
        assertEquals(1, pollCalls.get())
        assertEquals(0, applyCalls.get())
        assertEquals(1, endCalls.get())
        // Total expiry while an armed writer poll is blocked: the
        // watchdog ends the lease and the late poll never triggers an apply.
        val pollsBefore = pollCalls.get()
        val appliesBefore = applyCalls.get()
        val enteredExpire = CountDownLatch(1)
        val releaseExpire = CountDownLatch(1)
        val expired = gatedSession(
            totalMillis = 150L,
            activeMillis = 5_000L,
            poll = {
                enteredExpire.countDown()
                releaseExpire.await(5L, TimeUnit.SECONDS)
                strictCandidatePoll()
            },
            apply = { appliedResult() },
        )
        expired.launch()
        assertEquals(true, expired.arm())
        assertEquals(true, enteredExpire.await(5L, TimeUnit.SECONDS))
        assertEquals(true, awaitEndsCount(2, 5_000L))
        releaseExpire.countDown()
        Thread.sleep(200L)
        expired.cancel()
        assertEquals(pollsBefore + 1, pollCalls.get())
        assertEquals(appliesBefore, applyCalls.get())
        assertEquals(2, endCalls.get())
    }

    @Test
    fun deniedApplyResumesPollingAndCanReTrigger() {
        val attempts = AtomicInteger(0)
        val session = gatedSession(
            totalMillis = 10_000L,
            activeMillis = 1_500L,
            pollIntervalMillis = 10L,
            poll = { strictCandidatePoll() },
            apply = { if (attempts.incrementAndGet() == 1) deniedResult() else appliedResult() },
        )
        session.launch()
        assertEquals(true, session.arm())
        // DENIED drops back to poll mode, so another full scan runs before
        // the re-triggered apply, which then enters maintain mode.
        assertEquals(true, awaitCount(applyCalls, 2, 5_000L))
        assertTrue(pollCalls.get() >= 2)
        session.cancel()
        assertEquals(true, awaitEnds(5_000L))
        assertEquals(1, endCalls.get())
    }

    @Test
    fun errorApplyDisablesFurtherAppliesWhilePollingContinues() {
        val session = gatedSession(
            totalMillis = 10_000L,
            activeMillis = 800L,
            pollIntervalMillis = 10L,
            poll = { strictCandidatePoll() },
            apply = { errorResult() },
        )
        session.launch()
        assertEquals(true, session.arm())
        assertEquals(true, awaitCount(applyCalls, 1, 5_000L))
        Thread.sleep(300L)
        // Exactly one apply attempt: the failure latch holds for this session.
        assertEquals(1, applyCalls.get())
        assertTrue(pollCalls.get() > 1)
        assertEquals(true, awaitEnds(5_000L))
        assertEquals(1, endCalls.get())
    }

    @Test
    fun gatedObserverDoesNotPollBeforeWowStartup() {
        val session = gatedSession(totalMillis = 5_000L, activeMillis = 500L)
        session.launch()
        Thread.sleep(150L)
        assertEquals(0, pollCalls.get())
        assertEquals(false, session.isEnded())
        session.cancel()
        assertEquals(true, awaitEnds(5_000L))
        assertEquals(0, pollCalls.get())
        assertEquals(1, endCalls.get())
    }

    @Test
    fun writerWaitsForWowStartupThenPollsAndApplies() {
        // Startup replaces Enter; writer authority and the bounded loop remain.
        val applied = java.util.Collections.synchronizedList(mutableListOf<String>())
        val statuses = java.util.Collections.synchronizedList(mutableListOf<ForeverFunctionalPocPatchSession.PatchStatus>())
        val appliedSeen = CountDownLatch(1)
        val session = gatedSession(
            totalMillis = 10_000L,
            activeMillis = 800L,
            pollIntervalMillis = 10L,
            poll = { strictCandidatePoll() },
            apply = { appliedResult() },
            onApply = { applied.add(it); appliedSeen.countDown() },
            onStatus = { statuses.add(it) },
        )
        session.launch()
        Thread.sleep(150L)
        assertEquals(0, pollCalls.get())
        assertEquals(false, session.isEnded())
        assertEquals(1, statuses.size)
        assertEquals(ForeverFunctionalPocPatchSession.PatchAttemptPhase.PENDING, statuses[0].phase)
        assertEquals(true, session.arm())
        assertEquals(true, appliedSeen.await(5L, TimeUnit.SECONDS))
        assertTrue(applyCalls.get() >= 1)
        assertTrue(pollCalls.get() >= 1)
        assertTrue(applied.isNotEmpty())
        assertTrue(applied.all { it.contains("APPLIED") })
        assertEquals(true, awaitPhase(statuses, ForeverFunctionalPocPatchSession.PatchAttemptPhase.VERIFIED, 5_000L))
        assertTrue(synchronized(statuses) {
            statuses.any { it.phase == ForeverFunctionalPocPatchSession.PatchAttemptPhase.SEARCHING }
        })
        session.cancel()
        assertEquals(true, awaitEnds(5_000L))
        assertEquals(1, endCalls.get())
    }

    @Test
    fun armedPollCannotApplyAfterActiveDeadline() {
        val enteredPoll = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)
        val session = gatedSession(
            totalMillis = 10_000L,
            activeMillis = 200L,
            pollIntervalMillis = 10L,
            poll = {
                enteredPoll.countDown()
                releasePoll.await(5L, TimeUnit.SECONDS)
                strictCandidatePoll()
            },
            apply = { appliedResult() },
        )
        session.launch()
        // Startup opens the gate and clamps the deadline before polling starts.
        assertEquals(true, session.arm())
        assertEquals(true, enteredPoll.await(5L, TimeUnit.SECONDS))
        // The clamped deadline passes while the poll is blocked.
        Thread.sleep(400L)
        releasePoll.countDown()
        Thread.sleep(300L)
        // The clamped deadline passed during the block: no late apply.
        assertEquals(0, applyCalls.get())
        assertEquals(true, awaitEnds(5_000L))
        assertEquals(1, endCalls.get())
        assertEquals(false, session.arm())
    }

    @Test
    fun writerFirstStartupClampsAndLaterSignalsDoNotExtend() {
        val session = gatedSession(
            totalMillis = 10_000L,
            activeMillis = 300L,
            pollIntervalMillis = 10L,
            poll = { "status=NOT_FOUND" },
            apply = { appliedResult() },
        )
        session.launch()
        // Gated: nothing is read before the startup signal.
        Thread.sleep(150L)
        assertEquals(0, pollCalls.get())
        assertEquals(false, session.isEnded())
        assertEquals(true, session.arm())
        assertEquals(false, session.arm())
        assertEquals(false, session.arm())
        assertEquals(true, awaitEnds(5_000L))
        assertTrue(pollCalls.get() > 1)
        val settled = pollCalls.get()
        Thread.sleep(150L)
        assertEquals(settled, pollCalls.get())
        assertEquals(1, endCalls.get())
    }

    @Test
    fun firstStartupArmsOnceAndLaterSignalsDoNotExtend() {
        val session = gatedSession(totalMillis = 10_000L, activeMillis = 300L)
        session.launch()
        assertEquals(true, session.arm())
        assertEquals(false, session.arm())
        assertEquals(false, session.arm())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (pollCalls.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10L)
        assertTrue(pollCalls.get() > 0)
        assertEquals(true, awaitEnds(5_000L))
        // The active window must hold repeated polls, not a single one.
        assertTrue(pollCalls.get() > 1)
        val settled = pollCalls.get()
        Thread.sleep(150L)
        assertEquals(settled, pollCalls.get())
        assertEquals(1, endCalls.get())
    }

    @Test
    fun cancelBeforeStartupEndsLeaseWithoutReads() {
        val session = gatedSession()
        session.launch()
        session.cancel()
        assertEquals(true, awaitEnds(5_000L))
        assertEquals(0, pollCalls.get())
        assertEquals(1, endCalls.get())
    }

    @Test
    fun globalDeadlineEndsLeaseWithoutStartup() {
        val session = gatedSession(totalMillis = 200L, activeMillis = 5_000L)
        session.launch()
        assertEquals(true, awaitEnds(5_000L))
        assertEquals(0, pollCalls.get())
        assertEquals(1, endCalls.get())
    }

    @Test
    fun watchdogEndsLeaseWhilePollBlocked() {
        val release = CountDownLatch(1)
        val enteredPoll = CountDownLatch(1)
        val session = gatedSession(
            totalMillis = 10_000L,
            activeMillis = 200L,
            poll = {
                enteredPoll.countDown()
                release.await(5L, TimeUnit.SECONDS)
                "status=NOT_FOUND"
            },
        )
        session.launch()
        assertEquals(true, session.arm())
        assertEquals(true, enteredPoll.await(5L, TimeUnit.SECONDS))
        // The active watchdog releases the lease even though the poll is blocked.
        assertEquals(true, awaitEnds(5_000L))
        assertEquals(1, endCalls.get())
        release.countDown()
        session.cancel()
        Thread.sleep(100L)
        assertEquals(1, endCalls.get())
    }

    @Test
    fun pendingArmSlotClearsOnlyItsOwnSession() {
        var firedA = false
        var firedB = false
        val armA: () -> Unit = { firedA = true }
        val armB: () -> Unit = { firedB = true }
        ForeverFunctionalPocPatchSession.registerPendingArm("A", armA)
        ForeverFunctionalPocPatchSession.registerPendingArm("B", armB)
        ForeverFunctionalPocPatchSession.clearPendingArmIfMine(armA)
        // An old window cannot consume the current attempt's hook.
        assertEquals(false, ForeverFunctionalPocPatchSession.tryArmFromWowStartup("A"))
        assertEquals(true, ForeverFunctionalPocPatchSession.tryArmFromWowStartup("B"))
        assertEquals(false, firedA)
        assertEquals(true, firedB)
        // Single-shot: the consumed arm never fires again.
        assertEquals(false, ForeverFunctionalPocPatchSession.tryArmFromWowStartup("B"))
        ForeverFunctionalPocPatchSession.clearPendingArmIfMine(armB)
    }

    @Test
    fun activeWindowIsClampedToTotalLease() {
        val session = gatedSession(totalMillis = 1_000L, activeMillis = 500L)
        assertEquals(600L, session.activeDeadlineMs(0L, 100L))
        assertEquals(1_000L, session.activeDeadlineMs(0L, 900L))
    }

    @Test
    fun onlyNonCaptureSessionsWaitForWowStartup() {
        assertEquals(true, ForeverFunctionalPocPatchSession.requiresStartupGating(false))
        assertEquals(false, ForeverFunctionalPocPatchSession.requiresStartupGating(true))
    }

    // US-004 AC-12/FR-018/FR-019: advisory per-attempt patch status. Only a
    // successful native apply confirms application; VALIDATED scans, DENIED,
    // and expiry never do. Terminals distinguish verified from never
    // verified; a restore ERROR stays an error; late results after the
    // terminal never revive a green state.

    private fun validatedTopLevelScan(): String =
        "{\"status\":\"VALIDATED\",\"readOnly\":true,\"writeAuthorized\":false,\"bytesWritten\":0," +
            "\"fullScan\":{\"mode\":\"full-readable\",\"validatedStoreCount\":0," +
            "\"candidateCapped\":false,\"readOnly\":true,\"writeAuthorized\":false}}"

    private fun collectStatuses(): MutableList<ForeverFunctionalPocPatchSession.PatchStatus> =
        java.util.Collections.synchronizedList(mutableListOf())

    private fun awaitPhase(
        statuses: MutableList<ForeverFunctionalPocPatchSession.PatchStatus>,
        phase: ForeverFunctionalPocPatchSession.PatchAttemptPhase,
        timeoutMillis: Long,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            synchronized(statuses) {
                if (statuses.any { it.phase == phase }) return true
            }
            Thread.sleep(10L)
        }
        return false
    }

    @Test
    fun validatedTopLevelScanNeverConfirmsApplication() {
        val statuses = collectStatuses()
        val session = gatedSession(
            totalMillis = 10_000L,
            activeMillis = 400L,
            pollIntervalMillis = 10L,
            // Top-level VALIDATED with no strict block: must never apply.
            poll = { validatedTopLevelScan() },
            apply = { appliedResult() },
            onStatus = { statuses.add(it) },
        )
        session.launch()
        assertEquals(true, session.arm())
        assertEquals(true, awaitEnds(5_000L))
        assertEquals(true, awaitPhase(statuses, ForeverFunctionalPocPatchSession.PatchAttemptPhase.ENDED, 5_000L))
        assertEquals(0, applyCalls.get())
        synchronized(statuses) {
            assertTrue(statuses.none { it.phase == ForeverFunctionalPocPatchSession.PatchAttemptPhase.VERIFIED })
            val terminal = statuses.last()
            assertEquals(ForeverFunctionalPocPatchSession.PatchAttemptPhase.ENDED, terminal.phase)
            assertEquals(false, terminal.everApplied)
        }
        assertEquals(1, endCalls.get())
    }

    @Test
    fun successfulApplyReportsVerifiedThenEndsApplied() {
        val statuses = collectStatuses()
        val session = gatedSession(
            totalMillis = 10_000L,
            activeMillis = 5_000L,
            pollIntervalMillis = 10L,
            poll = { strictCandidatePoll() },
            apply = { appliedResult() },
            onStatus = { statuses.add(it) },
        )
        session.launch()
        assertEquals(true, session.arm())
        assertEquals(true, awaitPhase(statuses, ForeverFunctionalPocPatchSession.PatchAttemptPhase.VERIFIED, 5_000L))
        session.cancel()
        assertEquals(true, awaitEnds(5_000L))
        assertEquals(true, awaitPhase(statuses, ForeverFunctionalPocPatchSession.PatchAttemptPhase.ENDED, 5_000L))
        synchronized(statuses) {
            val terminal = statuses.last()
            assertEquals(ForeverFunctionalPocPatchSession.PatchAttemptPhase.ENDED, terminal.phase)
            assertEquals(true, terminal.everApplied)
            // Monotonic: no failure or second terminal after the verified end.
            assertTrue(statuses.none { it.phase == ForeverFunctionalPocPatchSession.PatchAttemptPhase.FAILED })
        }
        assertEquals(1, endCalls.get())
    }

    @Test
    fun neverAppliedExpiryEndsWithoutVerifiedApplication() {
        val statuses = collectStatuses()
        val session = gatedSession(
            totalMillis = 10_000L,
            activeMillis = 400L,
            pollIntervalMillis = 10L,
            poll = { emptyCandidatePoll() },
            apply = { appliedResult() },
            onStatus = { statuses.add(it) },
        )
        session.launch()
        assertEquals(true, session.arm())
        assertEquals(true, awaitEnds(5_000L))
        assertEquals(true, awaitPhase(statuses, ForeverFunctionalPocPatchSession.PatchAttemptPhase.ENDED, 5_000L))
        assertEquals(0, applyCalls.get())
        synchronized(statuses) {
            assertTrue(statuses.none { it.phase == ForeverFunctionalPocPatchSession.PatchAttemptPhase.VERIFIED })
            val terminal = statuses.last()
            assertEquals(ForeverFunctionalPocPatchSession.PatchAttemptPhase.ENDED, terminal.phase)
            assertEquals(false, terminal.everApplied)
        }
        assertEquals(1, endCalls.get())
    }

    @Test
    fun applyErrorLatchSurvivesSuccessfulEnd() {
        val statuses = collectStatuses()
        val session = gatedSession(
            totalMillis = 10_000L,
            activeMillis = 800L,
            pollIntervalMillis = 10L,
            poll = { strictCandidatePoll() },
            apply = { errorResult() },
            onStatus = { statuses.add(it) },
        )
        session.launch()
        assertEquals(true, session.arm())
        // Exactly one apply attempt: the failure latch holds for this session.
        assertEquals(true, awaitPhase(statuses, ForeverFunctionalPocPatchSession.PatchAttemptPhase.FAILED, 5_000L))
        assertEquals(true, awaitEnds(5_000L))
        assertEquals(1, applyCalls.get())
        synchronized(statuses) {
            // The successful native end must not overwrite the apply error.
            val terminal = statuses.last()
            assertEquals(ForeverFunctionalPocPatchSession.PatchAttemptPhase.FAILED, terminal.phase)
        }
        assertEquals(1, endCalls.get())
    }

    @Test
    fun endRestoreErrorRemainsError() {
        val statuses = collectStatuses()
        val session = gatedSession(
            totalMillis = 200L,
            activeMillis = 5_000L,
            poll = { "status=NOT_FOUND" },
            end = { "status=ERROR error=subreaper_restore" },
            onStatus = { statuses.add(it) },
        )
        session.launch()
        assertEquals(true, awaitEnds(5_000L))
        assertEquals(true, awaitPhase(statuses, ForeverFunctionalPocPatchSession.PatchAttemptPhase.FAILED, 5_000L))
        synchronized(statuses) {
            assertTrue(statuses.none { it.phase == ForeverFunctionalPocPatchSession.PatchAttemptPhase.ENDED })
            val terminal = statuses.last()
            assertEquals(ForeverFunctionalPocPatchSession.PatchAttemptPhase.FAILED, terminal.phase)
            assertEquals(false, terminal.everApplied)
        }
        assertEquals(1, endCalls.get())
    }

    @Test
    fun deniedApplyResumesPollingWithoutVerifiedClaim() {
        val statuses = collectStatuses()
        val session = gatedSession(
            totalMillis = 10_000L,
            activeMillis = 800L,
            pollIntervalMillis = 10L,
            poll = { strictCandidatePoll() },
            apply = { deniedResult() },
            onStatus = { statuses.add(it) },
        )
        session.launch()
        assertEquals(true, session.arm())
        assertEquals(true, awaitCount(applyCalls, 2, 5_000L))
        session.cancel()
        assertEquals(true, awaitEnds(5_000L))
        synchronized(statuses) {
            assertTrue(statuses.none { it.phase == ForeverFunctionalPocPatchSession.PatchAttemptPhase.VERIFIED })
        }
        assertEquals(1, endCalls.get())
    }

    @Test
    fun repeatedCancelEndsLeaseExactlyOnce() {
        // US-004 TC-025: a failed Wine setup (no normal exit) and a repeated
        // terminal callback cancel the same owned session; native end runs once.
        val session = gatedSession(
            totalMillis = 10_000L,
            activeMillis = 5_000L,
            poll = { "status=NOT_FOUND" },
        )
        session.launch()
        session.cancel()
        session.cancel()
        assertEquals(true, awaitEnds(5_000L))
        Thread.sleep(100L)
        session.cancel()
        assertEquals(1, endCalls.get())
        assertEquals(false, session.arm())
    }

    @Test
    fun lateApplyReplyAfterTerminalRefinesHistoryWithoutRevivingVerified() {
        // A native apply reply arriving after the watchdog ended the lease:
        // the terminal phase stands and no live VERIFIED/SEARCHING is
        // revived, but the recorded history truthfully reflects the proof.
        val statuses = collectStatuses()
        val enteredApply = CountDownLatch(1)
        val releaseApply = CountDownLatch(1)
        val session = gatedSession(
            totalMillis = 400L,
            activeMillis = 5_000L,
            pollIntervalMillis = 10L,
            poll = { strictCandidatePoll() },
            apply = {
                enteredApply.countDown()
                releaseApply.await(5L, TimeUnit.SECONDS)
                appliedResult()
            },
            onStatus = { statuses.add(it) },
        )
        session.launch()
        assertEquals(true, session.arm())
        assertEquals(true, enteredApply.await(5L, TimeUnit.SECONDS))
        assertEquals(true, awaitEnds(5_000L))
        assertEquals(true, awaitPhase(statuses, ForeverFunctionalPocPatchSession.PatchAttemptPhase.ENDED, 5_000L))
        releaseApply.countDown()
        assertEquals(true, awaitCount(applyCalls, 1, 5_000L))
        assertEquals(true, awaitStatusWhere(statuses, 5_000L) { it.everApplied })
        session.cancel()
        synchronized(statuses) {
            assertTrue(statuses.none { it.phase == ForeverFunctionalPocPatchSession.PatchAttemptPhase.VERIFIED })
            // Clean end first, then the historical refinement: still a
            // terminal, never a live verified state.
            val firstEnd = statuses.first { it.phase == ForeverFunctionalPocPatchSession.PatchAttemptPhase.ENDED }
            assertEquals(false, firstEnd.everApplied)
            val terminal = statuses.last()
            assertEquals(ForeverFunctionalPocPatchSession.PatchAttemptPhase.ENDED, terminal.phase)
            assertEquals(true, terminal.everApplied)
        }
        assertEquals(1, endCalls.get())
    }

    @Test
    fun lateApplyErrorAfterTerminalKeepsFailed() {
        // A native apply ERROR arriving after the watchdog ended the lease:
        // the clean end becomes FAILED; the error is never hidden by it.
        val statuses = collectStatuses()
        val enteredApply = CountDownLatch(1)
        val releaseApply = CountDownLatch(1)
        val session = gatedSession(
            totalMillis = 400L,
            activeMillis = 5_000L,
            pollIntervalMillis = 10L,
            poll = { strictCandidatePoll() },
            apply = {
                enteredApply.countDown()
                releaseApply.await(5L, TimeUnit.SECONDS)
                errorResult()
            },
            onStatus = { statuses.add(it) },
        )
        session.launch()
        assertEquals(true, session.arm())
        assertEquals(true, enteredApply.await(5L, TimeUnit.SECONDS))
        assertEquals(true, awaitEnds(5_000L))
        assertEquals(true, awaitPhase(statuses, ForeverFunctionalPocPatchSession.PatchAttemptPhase.ENDED, 5_000L))
        releaseApply.countDown()
        assertEquals(true, awaitCount(applyCalls, 1, 5_000L))
        assertEquals(true, awaitPhase(statuses, ForeverFunctionalPocPatchSession.PatchAttemptPhase.FAILED, 5_000L))
        session.cancel()
        synchronized(statuses) {
            assertTrue(statuses.none { it.phase == ForeverFunctionalPocPatchSession.PatchAttemptPhase.VERIFIED })
            val firstEnd = statuses.first { it.phase == ForeverFunctionalPocPatchSession.PatchAttemptPhase.ENDED }
            assertEquals(false, firstEnd.everApplied)
            val terminal = statuses.last()
            assertEquals(ForeverFunctionalPocPatchSession.PatchAttemptPhase.FAILED, terminal.phase)
            assertEquals(false, terminal.everApplied)
        }
        assertEquals(1, endCalls.get())
    }

    @Test
    fun lateApplyProofAfterFailedEndKeepsFailedWithHistory() {
        // Closure failed (nativeEnd ERROR) while an apply was in flight: the
        // late apply proof records history, but the terminal stays FAILED
        // and no live verified state is revived.
        val statuses = collectStatuses()
        val enteredApply = CountDownLatch(1)
        val releaseApply = CountDownLatch(1)
        val session = gatedSession(
            totalMillis = 400L,
            activeMillis = 5_000L,
            pollIntervalMillis = 10L,
            poll = { strictCandidatePoll() },
            end = { "status=ERROR error=subreaper_restore" },
            apply = {
                enteredApply.countDown()
                releaseApply.await(5L, TimeUnit.SECONDS)
                appliedResult()
            },
            onStatus = { statuses.add(it) },
        )
        session.launch()
        assertEquals(true, session.arm())
        assertEquals(true, enteredApply.await(5L, TimeUnit.SECONDS))
        assertEquals(true, awaitEnds(5_000L))
        assertEquals(true, awaitPhase(statuses, ForeverFunctionalPocPatchSession.PatchAttemptPhase.FAILED, 5_000L))
        releaseApply.countDown()
        assertEquals(true, awaitCount(applyCalls, 1, 5_000L))
        assertEquals(true, awaitStatusWhere(statuses, 5_000L) { it.everApplied })
        session.cancel()
        synchronized(statuses) {
            assertTrue(statuses.none { it.phase == ForeverFunctionalPocPatchSession.PatchAttemptPhase.VERIFIED })
            assertTrue(statuses.none { it.phase == ForeverFunctionalPocPatchSession.PatchAttemptPhase.ENDED })
            val terminal = statuses.last()
            assertEquals(ForeverFunctionalPocPatchSession.PatchAttemptPhase.FAILED, terminal.phase)
            assertEquals(true, terminal.everApplied)
        }
        assertEquals(1, endCalls.get())
    }

    private fun awaitStatusWhere(
        statuses: MutableList<ForeverFunctionalPocPatchSession.PatchStatus>,
        timeoutMillis: Long,
        predicate: (ForeverFunctionalPocPatchSession.PatchStatus) -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            synchronized(statuses) {
                if (statuses.any(predicate)) return true
            }
            Thread.sleep(10L)
        }
        return false
    }

    @Test
    fun appliedThenDeniedLeavesMonitoringWithoutVerifiedClaim() {
        // APPLIED then DENIED: polling resumes and the live state returns to
        // searching (with history), never staying currently verified and
        // never going green from DENIED.
        val statuses = collectStatuses()
        val attempts = AtomicInteger(0)
        val session = gatedSession(
            totalMillis = 10_000L,
            activeMillis = 5_000L,
            pollIntervalMillis = 10L,
            poll = { strictCandidatePoll() },
            apply = { if (attempts.incrementAndGet() <= 2) appliedResult() else deniedResult() },
            onStatus = { statuses.add(it) },
        )
        session.launch()
        assertEquals(true, session.arm())
        assertEquals(true, awaitPhase(statuses, ForeverFunctionalPocPatchSession.PatchAttemptPhase.VERIFIED, 5_000L))
        assertEquals(
            true,
            awaitStatusWhere(statuses, 5_000L) {
                it.phase == ForeverFunctionalPocPatchSession.PatchAttemptPhase.SEARCHING && it.everApplied
            },
        )
        session.cancel()
        assertEquals(true, awaitEnds(5_000L))
        synchronized(statuses) {
            assertTrue(statuses.none { it.phase == ForeverFunctionalPocPatchSession.PatchAttemptPhase.FAILED })
            val terminal = statuses.last()
            assertEquals(ForeverFunctionalPocPatchSession.PatchAttemptPhase.ENDED, terminal.phase)
            assertEquals(true, terminal.everApplied)
        }
        assertEquals(1, endCalls.get())
    }
}
