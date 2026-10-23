package app.gamenative

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.Window as ActivityWindow
import com.winlator.xserver.Keyboard
import com.winlator.xserver.Window
import com.winlator.xserver.XServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Shared by Prepared copies, so recreation and late callbacks cannot rearm this launch. */
class ForeverFunctionalAutologinAssistedEntryState {
    private val consumed = AtomicBoolean(false)
    private val privacyLock = Any()
    private var privacySession: ForeverAssistedCredentialPrivacy.Session? = null
    private var privacyEnded = false

    internal fun consume(): Boolean = consumed.compareAndSet(false, true)

    internal fun beginSensitiveCredentialSession(context: Context, window: ActivityWindow): Boolean =
        synchronized(privacyLock) {
            if (privacyEnded) return@synchronized false
            val current = privacySession
            if (current != null) {
                return@synchronized runCatching { current.protect(window) }.getOrDefault(false)
            }
            val started = runCatching {
                ForeverAssistedCredentialPrivacy.begin(context, window)
            }.getOrNull() ?: return@synchronized false
            privacySession = started
            true
        }

    /** A newly created Activity must join the same still-sensitive launch. */
    internal fun protectSensitiveCredentialWindow(window: ActivityWindow): Boolean = synchronized(privacyLock) {
        val current = privacySession ?: return@synchronized true
        runCatching { current.protect(window) }.getOrDefault(false)
    }

    /** Caller must have established guest/session termination before invoking this. */
    internal fun endSensitiveCredentialSessionAfterTermination(): Boolean {
        val current = synchronized(privacyLock) {
            privacySession ?: run {
                privacyEnded = true
                return true
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return endPrivacySession(current)

        // Session.end restores Activity Window flags, so guest-release workers
        // must do it on main before they drop the fail-closed guest reservation.
        val complete = CountDownLatch(1)
        var ended = false
        if (!Handler(Looper.getMainLooper()).post {
                try {
                    ended = endPrivacySession(current)
                } finally {
                    complete.countDown()
                }
            }
        ) return false
        return try {
            complete.await()
            ended
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun endPrivacySession(session: ForeverAssistedCredentialPrivacy.Session): Boolean =
        synchronized(privacyLock) {
            if (privacySession !== session) return@synchronized privacySession == null
            val ended = runCatching { session.end() }.getOrDefault(false)
            if (ended) {
                privacySession = null
                privacyEnded = true
            }
            ended
    }
}

internal data class ForeverFunctionalAutologinInputSnapshot(
    val sessionIdentity: Any,
    val rendererIdentity: Any,
    val windowIdentity: Any,
    val windowId: Long,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val rendererX: Int,
    val rendererY: Int,
    val rendererWidth: Int,
    val rendererHeight: Int,
    val sessionOpen: Boolean,
    val targetWindow: Boolean,
    val windowFocused: Boolean,
    val activityFocused: Boolean,
    val memoryCapture: Boolean,
) {
    fun usable(): Boolean = windowId > 0L && width > 0 && height > 0 &&
        screenWidth > 0 && screenHeight > 0 && rendererWidth > 0 && rendererHeight > 0 &&
        sessionOpen && targetWindow && windowFocused && activityFocused && !memoryCapture

    fun matches(other: ForeverFunctionalAutologinInputSnapshot?): Boolean =
        other != null && usable() && other.usable() &&
            sessionIdentity === other.sessionIdentity &&
            rendererIdentity === other.rendererIdentity &&
            windowIdentity === other.windowIdentity &&
            windowId == other.windowId &&
            x == other.x && y == other.y && width == other.width && height == other.height &&
            screenWidth == other.screenWidth && screenHeight == other.screenHeight &&
            rendererX == other.rendererX && rendererY == other.rendererY &&
            rendererWidth == other.rendererWidth && rendererHeight == other.rendererHeight

    override fun toString(): String = "ForeverFunctionalAutologinInputSnapshot(REDACTED)"
}

internal data class ForeverFunctionalAutologinStroke(val keyCode: Int, val metaState: Int)

internal fun interface ForeverFunctionalAutologinInputEncoder {
    /** Encodes the complete user, Tab, password, Enter sequence, or rejects it in full. */
    fun encode(username: CharArray, password: CharArray): IntArray?
}

internal interface ForeverFunctionalAutologinInputPort {
    fun snapshot(): ForeverFunctionalAutologinInputSnapshot?
    /** Rechecks the expected observable session immediately before dispatch. */
    fun send(expected: ForeverFunctionalAutologinInputSnapshot, stroke: ForeverFunctionalAutologinStroke): Boolean
    fun observeManualInput(callback: () -> Unit): AutoCloseable
}

internal interface ForeverFunctionalAutologinScheduler {
    fun post(delayMs: Long, callback: Runnable): Boolean
    fun remove(callback: Runnable)
}

internal fun interface ForeverFunctionalAutologinWorker {
    fun execute(task: Runnable)
}

internal object ForeverFunctionalAutologinCredentialWorker : ForeverFunctionalAutologinWorker {
    override fun execute(task: Runnable) {
        Thread(task, "forever-assisted-entry").apply { isDaemon = true }.start()
    }
}

internal class ForeverFunctionalAutologinMainScheduler : ForeverFunctionalAutologinScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun post(delayMs: Long, callback: Runnable): Boolean =
        handler.postDelayed(callback, delayMs)

    override fun remove(callback: Runnable) {
        handler.removeCallbacks(callback)
    }
}

internal enum class ForeverFunctionalAutologinAssistedResult {
    PREPARING,
    SENDING,
    SENT,
    CANCELLED,
    SESSION_UNAVAILABLE,
    CREDENTIALS_UNAVAILABLE,
    PRIVACY_UNAVAILABLE,
    CAPTURE_BLOCKED,
    UNSUPPORTED_CREDENTIAL,
    INPUT_FAILED,
}

/** One explicit, single-use sender. It never infers the focused game field or an internal modal. */
internal class ForeverFunctionalAutologinAssistedEntry(
    private val launch: ForeverFunctionalAutologinAssistedEntryState,
    private val beginSensitiveSession: () -> Boolean,
    private val acquireCredentials: () -> ForeverFunctionalAutologinCredentials?,
    private val input: ForeverFunctionalAutologinInputPort,
    private val scheduler: ForeverFunctionalAutologinScheduler,
    private val encoder: ForeverFunctionalAutologinInputEncoder = ForeverFunctionalAutologinKeyEncoder,
    private val worker: ForeverFunctionalAutologinWorker = ForeverFunctionalAutologinCredentialWorker,
    private val report: (ForeverFunctionalAutologinAssistedResult) -> Unit,
) {
    private val lock = Any()
    private var terminal = false
    private var active = false
    private var preparing = false
    private var credentials: ForeverFunctionalAutologinCredentials? = null
    private var encoded: IntArray? = null
    private var initialSnapshot: ForeverFunctionalAutologinInputSnapshot? = null
    private var cursor = 0
    private var pending: Runnable? = null
    private var manualInput: AutoCloseable? = null
    fun request() {
        val first = synchronized(lock) {
            if (terminal || !launch.consume()) return
            val first = input.snapshot()
            if (first?.memoryCapture == true) {
                finishLocked(ForeverFunctionalAutologinAssistedResult.CAPTURE_BLOCKED)
                return
            }
            if (first == null || !first.usable()) {
                finishLocked(ForeverFunctionalAutologinAssistedResult.SESSION_UNAVAILABLE)
                return
            }
            initialSnapshot = first
            preparing = true
            first
        }

        val observer = try {
            input.observeManualInput(::cancel)
        } catch (_: Throwable) {
            synchronized(lock) {
                finishLocked(ForeverFunctionalAutologinAssistedResult.SESSION_UNAVAILABLE)
            }
            return
        }
        synchronized(lock) {
            if (terminal) {
                runCatching { observer.close() }
                return
            }
            manualInput = observer
        }

        // Let concurrent manual-input callbacks acquire the sender lock while
        // privacy setup touches the window and marker.
        if (synchronized(lock) { terminal }) return
        val privacyReady = runCatching(beginSensitiveSession).getOrDefault(false)
        synchronized(lock) {
            if (terminal) return
            if (!privacyReady) {
                finishLocked(ForeverFunctionalAutologinAssistedResult.PRIVACY_UNAVAILABLE)
                return
            }
            notify(ForeverFunctionalAutologinAssistedResult.PREPARING)
            try {
                worker.execute(Runnable { prepare(first) })
            } catch (_: Throwable) {
                finishLocked(ForeverFunctionalAutologinAssistedResult.CREDENTIALS_UNAVAILABLE)
            }
        }
    }

    /** Called by Activity input, XServer keyboard observation, window loss, and disposal. */
    fun cancel() {
        synchronized(lock) {
            if (terminal || (!active && !preparing)) return
            finishLocked(ForeverFunctionalAutologinAssistedResult.CANCELLED)
        }
    }

    private fun prepare(first: ForeverFunctionalAutologinInputSnapshot) {
        if (synchronized(lock) { terminal }) return
        val acquired = try {
            acquireCredentials()
        } catch (_: Throwable) {
            null
        }
        if (acquired == null) {
            finishFromWorker(ForeverFunctionalAutologinAssistedResult.CREDENTIALS_UNAVAILABLE)
            return
        }
        if (synchronized(lock) { terminal }) {
            acquired.close()
            return
        }
        val plan = try {
            acquired.useValues { username, password -> encoder.encode(username, password) }
        } catch (_: Throwable) {
            null
        }
        if (plan == null || plan.isEmpty() || plan.size % 2 != 0) {
            acquired.close()
            plan?.fill(0)
            finishFromWorker(ForeverFunctionalAutologinAssistedResult.UNSUPPORTED_CREDENTIAL)
            return
        }
        if (synchronized(lock) { terminal }) {
            acquired.close()
            plan.fill(0)
            return
        }
        val posted = scheduler.post(0L, Runnable {
            synchronized(lock) {
                if (terminal) {
                    acquired.close()
                    plan.fill(0)
                    return@synchronized
                }
                preparing = false
                credentials = acquired
                encoded = plan
                if (!first.matches(input.snapshot())) {
                    finishLocked(ForeverFunctionalAutologinAssistedResult.SESSION_UNAVAILABLE)
                    return@synchronized
                }
                active = true
                notify(ForeverFunctionalAutologinAssistedResult.SENDING)
                sendNextLocked()
            }
        })
        if (!posted) {
            acquired.close()
            plan.fill(0)
            finishFromWorker(ForeverFunctionalAutologinAssistedResult.INPUT_FAILED)
        }
    }

    private fun finishFromWorker(result: ForeverFunctionalAutologinAssistedResult) {
        synchronized(lock) {
            if (terminal) return
            finishLocked(result)
        }
    }

    private fun sendNextLocked() {
        if (terminal || !active) return
        val expected = initialSnapshot
        if (expected == null || !expected.matches(input.snapshot())) {
            finishLocked(ForeverFunctionalAutologinAssistedResult.CANCELLED)
            return
        }
        val plan = encoded ?: run {
            finishLocked(ForeverFunctionalAutologinAssistedResult.INPUT_FAILED)
            return
        }
        if (cursor >= plan.size) {
            finishLocked(ForeverFunctionalAutologinAssistedResult.SENT)
            return
        }

        val stroke = ForeverFunctionalAutologinStroke(plan[cursor], plan[cursor + 1])
        val sent = try {
            input.send(expected, stroke)
        } catch (_: Throwable) {
            false
        }
        if (!sent) {
            val result = if (expected.matches(input.snapshot())) {
                ForeverFunctionalAutologinAssistedResult.INPUT_FAILED
            } else {
                ForeverFunctionalAutologinAssistedResult.CANCELLED
            }
            finishLocked(result)
            return
        }
        cursor += 2
        lateinit var callback: Runnable
        callback = Runnable {
            synchronized(lock) {
                if (pending === callback) pending = null
                sendNextLocked()
            }
        }
        pending = callback
        if (!scheduler.post(KEY_INTERVAL_MS, callback)) {
            pending = null
            finishLocked(ForeverFunctionalAutologinAssistedResult.INPUT_FAILED)
        }
    }

    private fun finishLocked(result: ForeverFunctionalAutologinAssistedResult) {
        if (terminal) return
        terminal = true
        active = false
        preparing = false
        pending?.let(scheduler::remove)
        pending = null
        runCatching { manualInput?.close() }
        manualInput = null
        credentials?.close()
        credentials = null
        encoded?.fill(0)
        encoded = null
        initialSnapshot = null
        notify(result)
    }

    private fun notify(result: ForeverFunctionalAutologinAssistedResult) {
        runCatching { report(result) }
    }

    companion object {
        private const val KEY_INTERVAL_MS = 2L
    }
}

/** Conservative Android virtual-keyboard mapping: printable ASCII only, checked against the active KCM. */
internal object ForeverFunctionalAutologinKeyEncoder : ForeverFunctionalAutologinInputEncoder {
    private val keyCodes = intArrayOf(
        KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_D,
        KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H,
        KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L,
        KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_P,
        KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_T,
        KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_X,
        KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_Z,
        KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3,
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7,
        KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SEMICOLON,
        KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET,
        KeyEvent.KEYCODE_GRAVE, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS,
        KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_BACKSLASH, KeyEvent.KEYCODE_AT,
        KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_POUND, KeyEvent.KEYCODE_PLUS,
    )

    override fun encode(username: CharArray, password: CharArray): IntArray? {
        val output = IntArray((username.size + password.size + 2) * 2)
        var transferred = false
        return try {
            val keyMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            var index = 0
            for (character in username) {
                val stroke = findStroke(keyMap, character) ?: return null
                output[index++] = stroke.keyCode
                output[index++] = stroke.metaState
            }
            output[index++] = KeyEvent.KEYCODE_TAB
            output[index++] = 0
            for (character in password) {
                val stroke = findStroke(keyMap, character) ?: return null
                output[index++] = stroke.keyCode
                output[index++] = stroke.metaState
            }
            output[index++] = KeyEvent.KEYCODE_ENTER
            output[index++] = 0
            transferred = true
            output
        } finally {
            if (!transferred) output.fill(0)
        }
    }

    private fun findStroke(keyMap: KeyCharacterMap, character: Char): ForeverFunctionalAutologinStroke? {
        if (character.code !in 0x20..0x7e) return null
        for (keyCode in keyCodes) {
            for (metaState in intArrayOf(0, KeyEvent.META_SHIFT_ON)) {
                val mapped = runCatching { keyMap.get(keyCode, metaState) }.getOrDefault(0)
                if (mapped != character.code) continue
                val event = KeyEvent(
                    0L,
                    0L,
                    KeyEvent.ACTION_DOWN,
                    keyCode,
                    0,
                    metaState,
                    KeyCharacterMap.VIRTUAL_KEYBOARD,
                    0,
                    0,
                    InputDevice.SOURCE_KEYBOARD,
                )
                if (event.unicodeChar == character.code) {
                    return ForeverFunctionalAutologinStroke(keyCode, metaState)
                }
            }
        }
        return null
    }
}

/** Dispatches key-up even if key-down throws, and retries a failed release once. */
internal fun dispatchForeverFunctionalAutologinStroke(
    stroke: ForeverFunctionalAutologinStroke,
    sendEvent: (KeyEvent) -> Boolean,
): Boolean = dispatchForeverFunctionalAutologinStroke(stroke, sendEvent, sendEvent)

internal fun dispatchForeverFunctionalAutologinStroke(
    stroke: ForeverFunctionalAutologinStroke,
    sendEvent: (KeyEvent) -> Boolean,
    sendControlEvent: (KeyEvent) -> Boolean,
): Boolean {
    val dispatchEvent =
        if (stroke.keyCode == KeyEvent.KEYCODE_TAB || stroke.keyCode == KeyEvent.KEYCODE_ENTER) {
            sendControlEvent
        } else {
            sendEvent
        }
    val now = SystemClock.uptimeMillis()
    val down = KeyEvent(
        now, now, KeyEvent.ACTION_DOWN, stroke.keyCode, 0, stroke.metaState,
        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
    )
    val up = KeyEvent(
        now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, stroke.keyCode, 0, stroke.metaState,
        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
    )
    var accepted = false
    var released = false
    var failed = false
    try {
        accepted = dispatchEvent(down)
    } catch (_: Throwable) {
        failed = true
    }
    try {
        released = dispatchEvent(up)
    } catch (_: Throwable) {
        failed = true
        runCatching { dispatchEvent(up) }
    }
    return accepted && released && !failed
}

/** Adapter over the current owned XServer window; it deliberately exposes no field/modal evidence. */
internal class ForeverFunctionalAutologinXServerInput(
    private val activity: Activity,
    private val prepared: ForeverFunctionalPoc.Prepared,
    private val mappedGameWindow: AtomicReference<Pair<XServer, Window>?>,
) : ForeverFunctionalAutologinInputPort {
    private val syntheticDispatchThread = ThreadLocal<Boolean>()

    override fun snapshot(): ForeverFunctionalAutologinInputSnapshot? {
        if (prepared.guestLaunchClosed.get()) return null
        val renderer = PluviaApp.xServerView ?: return null
        val rendererView = renderer as? View ?: return null
        val server = runCatching { renderer.getxServer() }.getOrNull() ?: return null
        val ownedWindow = mappedGameWindow.get() ?: return null
        if (ownedWindow.first !== server) return null
        val window = ownedWindow.second
        val focused = runCatching { server.windowManager.focusedWindow }.getOrNull()
        val targetWindow = window.className?.contains(ForeverFunctionalPoc.EXECUTABLE_NAME, ignoreCase = true) == true
        val rendererPosition = IntArray(2)
        return runCatching {
            rendererView.getLocationOnScreen(rendererPosition)
            ForeverFunctionalAutologinInputSnapshot(
                sessionIdentity = server,
                rendererIdentity = renderer,
                windowIdentity = window,
                windowId = window.id.toLong(),
                x = window.x.toInt(),
                y = window.y.toInt(),
                width = window.width.toInt(),
                height = window.height.toInt(),
                screenWidth = server.screenInfo.width.toInt(),
                screenHeight = server.screenInfo.height.toInt(),
                rendererX = rendererPosition[0],
                rendererY = rendererPosition[1],
                rendererWidth = rendererView.width,
                rendererHeight = rendererView.height,
                sessionOpen = PluviaApp.xEnvironment != null && !prepared.guestLaunchClosed.get() &&
                    window.attributes.isMapped,
                targetWindow = targetWindow,
                windowFocused = focused === window,
                activityFocused = activity.hasWindowFocus() &&
                    (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState ==
                    androidx.lifecycle.Lifecycle.State.RESUMED,
                memoryCapture = prepared.codeCaptureEnabled,
            )
        }.getOrNull()
    }

    override fun send(
        expected: ForeverFunctionalAutologinInputSnapshot,
        stroke: ForeverFunctionalAutologinStroke,
    ): Boolean {
        val current = snapshot() ?: return false
        if (!expected.matches(current)) return false
        val server = current.sessionIdentity as? XServer ?: return false
        val keyboard = server.keyboard
        val previous = syntheticDispatchThread.get()
        syntheticDispatchThread.set(true)
        return try {
            dispatchForeverFunctionalAutologinStroke(
                stroke,
                keyboard::onVirtualKeyEvent,
                keyboard::onKeyEvent,
            )
        } finally {
            if (previous == null) syntheticDispatchThread.remove()
            else syntheticDispatchThread.set(previous)
        }
    }

    override fun observeManualInput(callback: () -> Unit): AutoCloseable {
        val current = snapshot() ?: error("session unavailable")
        val server = current.sessionIdentity as? XServer ?: error("session unavailable")
        val keyboard = server.keyboard
        val observer = object : Keyboard.OnKeyboardListener {
            override fun onKeyPress(keycode: Byte, keysym: Int) {
                if (syntheticDispatchThread.get() != true) callback()
            }

            override fun onKeyRelease(keycode: Byte) = Unit
        }
        keyboard.addOnKeyboardListener(observer)
        return AutoCloseable { keyboard.removeOnKeyboardListener(observer) }
    }
}
