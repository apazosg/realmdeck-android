package app.gamenative

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

/** Frozen, fail-closed policy for the private panvk WSI process. */
object PanvkWsiPolicy {
    /** A same-Activity start Intent may pause delivery before onNewIntent/onResume.
     * No GPU work may begin while paused; an already-started native worker cancels. */
    fun cancelOnPause(nativeStarted: Boolean): Boolean = nativeStarted
    fun mayStartNative(hostResumed: Boolean): Boolean = hostResumed
    const val FROZENWSI_CONTROL_ENABLED = true
    const val PROCESS_SUFFIX = ":panvk_wsi"
    const val START_EXTRA = "start"

    /** Called by the parent Application before normal process initialization. */
    @JvmStatic
    fun shouldIsolate(context: Context): Boolean {
        val packageName = context.packageName
        val processName = if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            null
        }
        val debug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return isWsiProcess(debug, packageName, processName)
    }

    /** Pure predicate intentionally suitable for an early Application hook. */
    @JvmStatic
    fun isWsiProcess(debug: Boolean, packageName: String?, processName: String?): Boolean {
        return FROZENWSI_CONTROL_ENABLED && debug &&
            !packageName.isNullOrEmpty() && processName == "$packageName$PROCESS_SUFFIX"
    }

    /** Only a Boolean true value is a command; all other extras are data noise. */
    @JvmStatic
    fun isStartIntent(intent: Intent?): Boolean =
        intent?.extras?.get(START_EXTRA) is Boolean && intent.getBooleanExtra(START_EXTRA, false)

    internal fun acceptedHook(name: String): Boolean = name in KNOWN_HOOKS

    internal val KNOWN_HOOKS = setOf(
        "surfaceCreated", "surfaceChanged", "surfaceDestroyed", "start", "cancel", "nativeReturned",
    )

    private val processSessionClaimed = AtomicBoolean(false)

    internal fun claimProcessSession(): Boolean = processSessionClaimed.compareAndSet(false, true)

    /** Test-fixture reset only; production has no release or reattach operation. */
    internal fun resetProcessSessionForTests() {
        processSessionClaimed.set(false)
    }
}

/** Small pure gate shared by the Activity and unit tests. */
internal class PanvkWsiStartGate {
    private var requested = false
    private var cancelled = false
    private var armed = false

    @Synchronized
    fun request(surfaceReady: Boolean): Boolean {
        if (requested || cancelled) return false
        requested = true
        return surfaceReady && arm()
    }

    @Synchronized
    fun surfaceBecameReady(): Boolean = requested && !cancelled && arm()

    @Synchronized
    fun cancel(): Boolean {
        if (cancelled) return false
        cancelled = true
        return true
    }

    @Synchronized
    fun isCancelled(): Boolean = cancelled

    @Synchronized
    fun isArmed(): Boolean = armed

    @Synchronized
    fun wasRequested(): Boolean = requested

    private fun arm(): Boolean {
        if (armed) return false
        armed = true
        return true
    }
}
