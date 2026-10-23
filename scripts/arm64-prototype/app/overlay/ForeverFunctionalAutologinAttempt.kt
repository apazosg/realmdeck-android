package app.gamenative

import java.util.concurrent.atomic.AtomicBoolean

/** A host-owned, non-serializable identity for one game launch. */
class ForeverFunctionalAutologinLaunchToken private constructor() {
    private val attemptOwned = AtomicBoolean(false)

    override fun toString(): String = "ForeverFunctionalAutologinLaunchToken(REDACTED)"

    internal fun claimAttemptOwnership(): Boolean = attemptOwned.compareAndSet(false, true)

    companion object {
        /** The host creates this once and keeps it with the launch lifetime. */
        fun mint(): ForeverFunctionalAutologinLaunchToken = ForeverFunctionalAutologinLaunchToken()
    }
}

fun interface ForeverFunctionalAutologinMonotonicClock {
    fun nowNanos(): Long
}

enum class ForeverFunctionalAutologinProfile {
    CLASSIC_FOREVER,
    ORIGINAL,
    MANUAL,
}

/**
 * Policy inputs only.  In particular, this type has no credentials and no input
 * callback.  An instrumentation/capture configuration can never enable login.
 */
data class ForeverFunctionalAutologinPolicy(
    val profile: ForeverFunctionalAutologinProfile = ForeverFunctionalAutologinProfile.MANUAL,
    val autoLoginEnabled: Boolean = false,
    val readinessPortAvailable: Boolean = false,
    val readinessPortEnabled: Boolean = false,
    val memoryPatchEnabled: Boolean = false,
    val codeCaptureEnabled: Boolean = false,
)

data class ForeverFunctionalAutologinSession(
    val windowServerIdentity: String,
    val windowId: Long,
    val processId: Long? = null,
) {
    init {
        require(windowServerIdentity.isNotBlank()) { "window server identity is required" }
        require(windowId > 0L) { "window id must be positive" }
        require(processId == null || processId > 0L) { "process id must be positive" }
    }
}

data class ForeverFunctionalAutologinEvidence(
    val launchToken: ForeverFunctionalAutologinLaunchToken,
    val session: ForeverFunctionalAutologinSession,
    val buildReference: String,
    val expectedWindowClass: String,
    val expectedWindowName: String,
    val expectedWindowVerified: Boolean,
    /** This is window focus, not proof that either form field has focus. */
    val focusedWindowMatchesExpected: Boolean,
    val noUnexpectedModal: Boolean,
    val formReferenceId: Long?,
    val geometryRevision: Long?,
    /** Supplied by a future positive form/field gate; never inferred here. */
    val fieldReadinessConfirmed: Boolean,
)

enum class ForeverFunctionalAutologinAttemptState {
    WAITING,
    CLAIMED,
    SUBMITTED,
    CANCELLED,
    TIMED_OUT,
    MANUAL_REQUIRED,
}

/**
 * Pure, per-launch policy controller.  It decides whether a future adapter may
 * inject; it never obtains credentials, finds a form, sends input, or reports
 * authentication success.
 */
class ForeverFunctionalAutologinAttempt(
    private val launchToken: ForeverFunctionalAutologinLaunchToken,
    private val expectedSession: ForeverFunctionalAutologinSession,
    private val expectedBuildReference: String,
    private val expectedWindowClass: String,
    private val expectedWindowName: String,
    private val clock: ForeverFunctionalAutologinMonotonicClock,
    private val policy: ForeverFunctionalAutologinPolicy,
) {
    private val lock = Any()
    private val createdAtNanos = clock.nowNanos()
    private var currentState = if (automaticAllowed()) {
        ForeverFunctionalAutologinAttemptState.WAITING
    } else {
        ForeverFunctionalAutologinAttemptState.MANUAL_REQUIRED
    }
    private var claimedEvidence: ForeverFunctionalAutologinEvidence? = null
    private var ownedPermit: ForeverFunctionalAutologinPermit? = null

    init {
        require(expectedBuildReference.isNotBlank()) { "build reference is required" }
        require(expectedWindowClass.isNotBlank()) { "window class is required" }
        require(expectedWindowName.isNotBlank()) { "window name is required" }
        check(launchToken.claimAttemptOwnership()) { "launch token already owns an attempt" }
    }

    /** Reading state also applies the monotonic deadline. */
    val state: ForeverFunctionalAutologinAttemptState
        get() = synchronized(lock) {
            expireIfNeeded()
            currentState
        }

    /** One atomic claim wins; every other caller gets null. */
    fun claimAttempt(evidence: ForeverFunctionalAutologinEvidence): ForeverFunctionalAutologinPermit? =
        synchronized(lock) {
            expireIfNeeded()
            if (currentState == ForeverFunctionalAutologinAttemptState.CLAIMED &&
                !evidenceStillMatches(evidence)
            ) {
                currentState = ForeverFunctionalAutologinAttemptState.CANCELLED
            }
            if (currentState != ForeverFunctionalAutologinAttemptState.WAITING) return null
            if (!fullProofMatches(evidence)) return null
            claimedEvidence = evidence
            return ForeverFunctionalAutologinPermit.create(this).also {
                ownedPermit = it
                currentState = ForeverFunctionalAutologinAttemptState.CLAIMED
            }
        }

    /** A manual interaction or host cancellation is terminal and never rearms. */
    fun cancel(): Boolean = synchronized(lock) {
        expireIfNeeded()
        if (currentState.isTerminal()) return false
        currentState = ForeverFunctionalAutologinAttemptState.CANCELLED
        true
    }

    /** Moves either waiting or claimed work to the manual path permanently. */
    fun requireManual(): Boolean = synchronized(lock) {
        expireIfNeeded()
        if (currentState.isTerminal()) return false
        currentState = ForeverFunctionalAutologinAttemptState.MANUAL_REQUIRED
        true
    }

    private fun automaticAllowed(): Boolean =
        policy.profile == ForeverFunctionalAutologinProfile.CLASSIC_FOREVER &&
            policy.autoLoginEnabled &&
            policy.readinessPortAvailable &&
            policy.readinessPortEnabled &&
            !policy.codeCaptureEnabled

    private fun fullProofMatches(evidence: ForeverFunctionalAutologinEvidence): Boolean =
        evidence.launchToken === launchToken &&
            sessionMatches(evidence.session) &&
            evidence.buildReference == expectedBuildReference &&
            evidence.expectedWindowClass == expectedWindowClass &&
            evidence.expectedWindowName == expectedWindowName &&
            evidence.expectedWindowVerified &&
            evidence.focusedWindowMatchesExpected &&
            evidence.noUnexpectedModal &&
            evidence.formReferenceId != null && evidence.formReferenceId > 0L &&
            evidence.geometryRevision != null && evidence.geometryRevision > 0L &&
            evidence.fieldReadinessConfirmed

    private fun sessionMatches(actual: ForeverFunctionalAutologinSession): Boolean =
        actual.windowServerIdentity == expectedSession.windowServerIdentity &&
            actual.windowId == expectedSession.windowId &&
            (expectedSession.processId == null || actual.processId == expectedSession.processId)

    private fun expireIfNeeded() {
        if (currentState == ForeverFunctionalAutologinAttemptState.WAITING ||
            currentState == ForeverFunctionalAutologinAttemptState.CLAIMED
        ) {
            val now = clock.nowNanos()
            if (now >= createdAtNanos && now - createdAtNanos >= DEADLINE_NANOS) {
                currentState = ForeverFunctionalAutologinAttemptState.TIMED_OUT
            }
        }
    }

    private fun evidenceStillMatches(evidence: ForeverFunctionalAutologinEvidence): Boolean {
        val claimed = claimedEvidence ?: return false
        return evidence == claimed && fullProofMatches(evidence)
    }

    internal fun revalidate(permit: ForeverFunctionalAutologinPermit, evidence: ForeverFunctionalAutologinEvidence): Boolean =
        synchronized(lock) {
            expireIfNeeded()
            if (currentState != ForeverFunctionalAutologinAttemptState.CLAIMED || ownedPermit !== permit) return false
            if (evidenceStillMatches(evidence)) return true
            currentState = ForeverFunctionalAutologinAttemptState.CANCELLED
            false
        }

    internal fun claimSubmit(permit: ForeverFunctionalAutologinPermit, evidence: ForeverFunctionalAutologinEvidence): Boolean =
        synchronized(lock) {
            expireIfNeeded()
            if (currentState != ForeverFunctionalAutologinAttemptState.CLAIMED || ownedPermit !== permit) return false
            if (!evidenceStillMatches(evidence)) {
                currentState = ForeverFunctionalAutologinAttemptState.CANCELLED
                return false
            }
            currentState = ForeverFunctionalAutologinAttemptState.SUBMITTED
            true
        }

    private fun ForeverFunctionalAutologinAttemptState.isTerminal(): Boolean = this ==
        ForeverFunctionalAutologinAttemptState.SUBMITTED ||
        this == ForeverFunctionalAutologinAttemptState.CANCELLED ||
        this == ForeverFunctionalAutologinAttemptState.TIMED_OUT ||
        this == ForeverFunctionalAutologinAttemptState.MANUAL_REQUIRED

    companion object {
        const val DEADLINE_NANOS: Long = 90_000_000_000L
    }
}

/** Opaque permission owned by exactly one attempt; callers cannot construct one. */
class ForeverFunctionalAutologinPermit private constructor(
    private val attempt: ForeverFunctionalAutologinAttempt,
) {
    fun canContinue(evidence: ForeverFunctionalAutologinEvidence): Boolean =
        attempt.revalidate(this, evidence)

    fun revalidate(evidence: ForeverFunctionalAutologinEvidence): Boolean = canContinue(evidence)

    /** Final policy claim only; this performs no submission or credential work. */
    fun claimSubmit(evidence: ForeverFunctionalAutologinEvidence): Boolean =
        attempt.claimSubmit(this, evidence)

    companion object {
        internal fun create(attempt: ForeverFunctionalAutologinAttempt): ForeverFunctionalAutologinPermit =
            ForeverFunctionalAutologinPermit(attempt)
    }
}
