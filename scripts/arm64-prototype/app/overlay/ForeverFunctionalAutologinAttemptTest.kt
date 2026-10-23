package app.gamenative

import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeverFunctionalAutologinAttemptTest {
    @Test
    fun twoConcurrentClaimsProduceOneOwnedPermit() {
        val clock = FakeClock()
        val attempt = attempt(clock)
        val evidence = evidence(attempt)
        val start = CountDownLatch(1)
        val permits = Collections.synchronizedList(mutableListOf<ForeverFunctionalAutologinPermit>())
        val workers = (1..2).map {
            thread(start = false) {
                start.await()
                attempt.claimAttempt(evidence)?.let(permits::add)
            }
        }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join() }

        assertEquals(1, permits.size)
        assertEquals(ForeverFunctionalAutologinAttemptState.CLAIMED, attempt.state)
    }

    @Test
    fun deadlineIsMeasuredFromCreationAndIncludesTheNinetySecondBoundary() {
        val clock = FakeClock()
        val attempt = attempt(clock)
        clock.advance(ForeverFunctionalAutologinAttempt.DEADLINE_NANOS - 1L)
        assertEquals(ForeverFunctionalAutologinAttemptState.WAITING, attempt.state)
        clock.advance(1L)
        assertEquals(ForeverFunctionalAutologinAttemptState.TIMED_OUT, attempt.state)
        assertNull(attempt.claimAttempt(evidence(attempt)))
    }

    @Test
    fun submitIsSingleUseAndDoesNotMeanAuthenticationSucceeded() {
        val attempt = attempt(FakeClock())
        val evidence = evidence(attempt)
        val permit = requireNotNull(attempt.claimAttempt(evidence))
        assertTrue(permit.canContinue(evidence))
        assertTrue(permit.claimSubmit(evidence))
        assertFalse(permit.claimSubmit(evidence))
        assertEquals(ForeverFunctionalAutologinAttemptState.SUBMITTED, attempt.state)
    }

    @Test
    fun cancellationBeforeClaimAndBetweenPhasesNeverRearms() {
        val first = attempt(FakeClock())
        assertTrue(first.cancel())
        assertNull(first.claimAttempt(evidence(first)))
        assertEquals(ForeverFunctionalAutologinAttemptState.CANCELLED, first.state)

        val second = attempt(FakeClock())
        val evidence = evidence(second)
        val permit = requireNotNull(second.claimAttempt(evidence))
        assertTrue(permit.canContinue(evidence)) // synthetic fill phase boundary
        assertTrue(second.cancel())
        assertFalse(permit.claimSubmit(evidence)) // synthetic submit phase boundary
        assertEquals(ForeverFunctionalAutologinAttemptState.CANCELLED, second.state)

        val manual = attempt(FakeClock())
        assertTrue(manual.requireManual())
        assertNull(manual.claimAttempt(evidence(manual)))
        assertFalse(manual.requireManual())
    }

    @Test
    fun incompleteOrLateEvidenceCannotClaim() {
        val attempt = attempt(FakeClock())
        val valid = evidence(attempt)
        val variants = listOf(
            valid.copy(session = valid.session.copy(windowId = 2L)),
            valid.copy(session = valid.session.copy(windowServerIdentity = "other-server")),
            valid.copy(launchToken = ForeverFunctionalAutologinLaunchToken.mint()),
            valid.copy(buildReference = "other-build"),
            valid.copy(expectedWindowName = "other-window"),
            valid.copy(formReferenceId = 0L),
            valid.copy(geometryRevision = 0L),
            valid.copy(formReferenceId = null),
            valid.copy(fieldReadinessConfirmed = false),
            valid.copy(focusedWindowMatchesExpected = false),
            valid.copy(noUnexpectedModal = false),
        )
        variants.forEach { late ->
            assertNull(attempt.claimAttempt(late))
            assertEquals(ForeverFunctionalAutologinAttemptState.WAITING, attempt.state)
        }
        assertNotNull(attempt.claimAttempt(valid))
    }

    @Test
    fun permitIsRevokedByIdentityWindowFormOrGeometryChange() {
        val attempt = attempt(FakeClock())
        val valid = evidence(attempt)
        val permit = requireNotNull(attempt.claimAttempt(valid))
        val changed = valid.copy(
            session = valid.session.copy(processId = 88L),
            formReferenceId = 9L,
            geometryRevision = 9L,
        )
        assertFalse(permit.canContinue(changed))
        assertEquals(ForeverFunctionalAutologinAttemptState.CANCELLED, attempt.state)
        assertFalse(permit.claimSubmit(valid))
    }

    @Test
    fun focusLossAndUnexpectedModalRevokeAReadyPermit() {
        listOf(
            { value: ForeverFunctionalAutologinEvidence -> value.copy(focusedWindowMatchesExpected = false) },
            { value: ForeverFunctionalAutologinEvidence -> value.copy(noUnexpectedModal = false) },
        ).forEach { changedEvidence ->
            val attempt = attempt(FakeClock())
            val valid = evidence(attempt)
            val permit = requireNotNull(attempt.claimAttempt(valid))
            assertFalse(permit.canContinue(changedEvidence(valid)))
            assertEquals(ForeverFunctionalAutologinAttemptState.CANCELLED, attempt.state)
        }
    }

    @Test
    fun disabledOriginalAndInstrumentationPoliciesRequireManualPath() {
        val policies = listOf(
            enabledPolicy().copy(autoLoginEnabled = false),
            enabledPolicy().copy(profile = ForeverFunctionalAutologinProfile.ORIGINAL),
            enabledPolicy().copy(profile = ForeverFunctionalAutologinProfile.MANUAL),
            enabledPolicy().copy(codeCaptureEnabled = true),
            enabledPolicy().copy(memoryPatchEnabled = true, codeCaptureEnabled = true),
            enabledPolicy().copy(readinessPortAvailable = false),
            enabledPolicy().copy(readinessPortEnabled = false),
        )
        policies.forEach { policy ->
            val attempt = attempt(FakeClock(), policy)
            assertEquals(ForeverFunctionalAutologinAttemptState.MANUAL_REQUIRED, attempt.state)
            assertNull(attempt.claimAttempt(evidence(attempt)))
        }
    }

    @Test
    fun writerOptInDoesNotBlockAutologinPermissionWhenCaptureIsOff() {
        val attempt = attempt(
            FakeClock(),
            enabledPolicy().copy(memoryPatchEnabled = true, codeCaptureEnabled = false),
        )
        val evidence = evidence(attempt)

        assertEquals(ForeverFunctionalAutologinAttemptState.WAITING, attempt.state)
        val permit = attempt.claimAttempt(evidence)
        assertNotNull(permit)
        assertTrue(requireNotNull(permit).canContinue(evidence))
    }

    @Test
    fun recreatingUiDoesNotResetThePerLaunchAttempt() {
        val attempt = attempt(FakeClock())
        val evidence = evidence(attempt)
        val permit = requireNotNull(attempt.claimAttempt(evidence))
        val recreatedUiReference = attempt
        assertEquals(ForeverFunctionalAutologinAttemptState.CLAIMED, recreatedUiReference.state)
        assertTrue(permit.canContinue(evidence))
        assertTrue(permit.claimSubmit(evidence))
    }

    @Test
    fun oneControllerCanOwnAContinueToken() {
        val token = ForeverFunctionalAutologinLaunchToken.mint()
        newAttempt(token)
        try {
            newAttempt(token)
            throw AssertionError("launch token was reused")
        } catch (_: IllegalStateException) {
            // expected: a recreated UI must retain the original controller
        }
    }

    private fun attempt(
        clock: FakeClock,
        policy: ForeverFunctionalAutologinPolicy = enabledPolicy(),
    ): ForeverFunctionalAutologinAttempt {
        val token = ForeverFunctionalAutologinLaunchToken.mint()
        val attempt = newAttempt(token, clock, policy)
        tokenRegistry[attempt] = token
        return attempt
    }

    private fun newAttempt(
        token: ForeverFunctionalAutologinLaunchToken,
        clock: FakeClock = FakeClock(),
        policy: ForeverFunctionalAutologinPolicy = enabledPolicy(),
    ): ForeverFunctionalAutologinAttempt {
        val attempt = ForeverFunctionalAutologinAttempt(
            launchToken = token,
            expectedSession = SESSION,
            expectedBuildReference = BUILD,
            expectedWindowClass = WINDOW_CLASS,
            expectedWindowName = WINDOW_NAME,
            clock = clock,
            policy = policy,
        )
        return attempt
    }

    private fun evidence(attempt: ForeverFunctionalAutologinAttempt): ForeverFunctionalAutologinEvidence =
        ForeverFunctionalAutologinEvidence(
            launchToken = tokenOf(attempt),
            session = SESSION,
            buildReference = BUILD,
            expectedWindowClass = WINDOW_CLASS,
            expectedWindowName = WINDOW_NAME,
            expectedWindowVerified = true,
            focusedWindowMatchesExpected = true,
            noUnexpectedModal = true,
            formReferenceId = 1L,
            geometryRevision = 1L,
            fieldReadinessConfirmed = true,
        )

    /* Test-only access through the evidence factory keeps production APIs free of token getters. */
    private fun tokenOf(attempt: ForeverFunctionalAutologinAttempt): ForeverFunctionalAutologinLaunchToken =
        tokenRegistry[attempt] ?: error("missing synthetic token")

    private fun enabledPolicy() = ForeverFunctionalAutologinPolicy(
        profile = ForeverFunctionalAutologinProfile.CLASSIC_FOREVER,
        autoLoginEnabled = true,
        readinessPortAvailable = true,
        readinessPortEnabled = true,
        memoryPatchEnabled = false,
        codeCaptureEnabled = false,
    )

    private class FakeClock(var now: Long = 0L) : ForeverFunctionalAutologinMonotonicClock {
        override fun nowNanos(): Long = now
        fun advance(nanos: Long) {
            now += nanos
        }
    }

    companion object {
        private const val BUILD = "synthetic-build"
        private const val WINDOW_CLASS = "synthetic-window-class"
        private const val WINDOW_NAME = "synthetic-login-window"
        private val SESSION = ForeverFunctionalAutologinSession("synthetic-window-server", 1L, 7L)
        private val tokenRegistry = java.util.WeakHashMap<ForeverFunctionalAutologinAttempt, ForeverFunctionalAutologinLaunchToken>()
    }
}
