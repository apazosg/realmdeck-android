package app.gamenative

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.winlator.container.Container
import com.winlator.renderer.XServerRenderer
import com.winlator.widget.XServerRendererView
import com.winlator.xserver.Keyboard
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XKeycode
import com.winlator.xserver.Window as XWindow
import com.winlator.xserver.WindowManager as XWindowManager
import com.winlator.xserver.XServer
import com.winlator.xenvironment.XEnvironment
import java.io.File
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class ForeverFunctionalAutologinAssistedEntryTest {
    @Test
    fun tapConsumesLaunchOnceAndSendsTheCompleteSequence() {
        val state = ForeverFunctionalAutologinAssistedEntryState()
        val input = FakeInput()
        val scheduler = FakeScheduler()
        var acquisitions = 0
        val results = mutableListOf<ForeverFunctionalAutologinAssistedResult>()
        val sender = sender(state, input, scheduler, results, acquire = {
            acquisitions++
            credentials("u", "p")
        })

        // Constructing the sender is inert; only the explicit request acquires.
        assertEquals(0, acquisitions)
        assertTrue(input.sent.isEmpty())
        sender.request()
        val recreatedSender = sender(state, FakeInput(), FakeScheduler(), mutableListOf(), acquire = {
            acquisitions++
            credentials("u", "p")
        })
        recreatedSender.request()
        scheduler.runAll()
        sender.request()

        assertEquals(1, acquisitions)
        assertEquals(
            listOf(KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_ENTER),
            input.sent.map { it.keyCode },
        )
        assertEquals(1, input.sent.count { it.keyCode == KeyEvent.KEYCODE_ENTER })
        assertEquals(ForeverFunctionalAutologinAssistedResult.SENT, results.last())
    }

    @Test
    fun unsupportedPairIsRejectedBeforeAnyCharacterOrControlInput() {
        val input = FakeInput()
        val scheduler = FakeScheduler()
        val results = mutableListOf<ForeverFunctionalAutologinAssistedResult>()
        val sender = sender(
            ForeverFunctionalAutologinAssistedEntryState(), input, scheduler, results,
            acquire = { credentials("uñ", "p") },
        )

        sender.request()
        scheduler.runAll()

        assertTrue(input.sent.isEmpty())
        assertEquals(ForeverFunctionalAutologinAssistedResult.UNSUPPORTED_CREDENTIAL, results.last())
        assertSecretFree(results)
    }

    @Test
    fun privacyFailureBlocksCredentialAcquisitionAndInput() {
        val input = FakeInput()
        val scheduler = FakeScheduler()
        val results = mutableListOf<ForeverFunctionalAutologinAssistedResult>()
        var acquisitions = 0
        var privacyBegins = 0
        val sender = sender(
            ForeverFunctionalAutologinAssistedEntryState(), input, scheduler, results,
            beginSensitiveSession = { privacyBegins++; false },
            acquire = { acquisitions++; credentials("u", "p") },
        )

        sender.request()
        scheduler.runAll()

        assertEquals(1, privacyBegins)
        assertEquals(0, acquisitions)
        assertTrue(input.sent.isEmpty())
        assertFalse(input.manualObserverActive)
        assertEquals(ForeverFunctionalAutologinAssistedResult.PRIVACY_UNAVAILABLE, results.last())
    }

    @Test
    fun manualInputDuringPrivacySetupCancelsBeforeCredentialAcquisition() {
        val input = FakeInput()
        val scheduler = FakeScheduler()
        val worker = FakeWorker()
        val results = mutableListOf<ForeverFunctionalAutologinAssistedResult>()
        var acquisitions = 0
        val sender = sender(
            ForeverFunctionalAutologinAssistedEntryState(), input, scheduler, results,
            beginSensitiveSession = {
                val manualEvent = Thread { input.manualInput() }
                manualEvent.start()
                manualEvent.join(1_000)
                assertFalse(manualEvent.isAlive)
                true
            },
            acquire = { acquisitions++; credentials("u", "p") },
            worker = worker,
        )

        sender.request()
        worker.runNext()
        scheduler.runAll()

        assertEquals(0, acquisitions)
        assertTrue(input.sent.isEmpty())
        assertFalse(input.manualObserverActive)
        assertEquals(ForeverFunctionalAutologinAssistedResult.CANCELLED, results.last())
    }

    @Test
    fun sensitiveSessionSurvivesSenderCompletionAndProtectsRecreatedWindow() {
        val context = RuntimeEnvironment.getApplication()
        val initialActivity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val recreatedActivity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val store = ForeverRunStoreRegistry.get(context)
        val run = store.begin()
        val state = ForeverFunctionalAutologinAssistedEntryState()
        val input = FakeInput()
        val scheduler = FakeScheduler()
        val results = mutableListOf<ForeverFunctionalAutologinAssistedResult>()
        val sender = sender(
            state, input, scheduler, results,
            beginSensitiveSession = { state.beginSensitiveCredentialSession(context, initialActivity.window) },
            acquire = { credentials("u", "p") },
        )

        try {
            sender.request()
            scheduler.runAll()

            assertEquals(ForeverFunctionalAutologinAssistedResult.SENT, results.last())
            assertTrue(ForeverAssistedCredentialPrivacy.isSensitive(context.filesDir))
            assertTrue(initialActivity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertTrue(state.protectSensitiveCredentialWindow(recreatedActivity.window))
            assertTrue(recreatedActivity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)

            val endResult = AtomicReference<Boolean?>()
            val endWorker = Thread({
                endResult.set(state.endSensitiveCredentialSessionAfterTermination())
            }, "assisted-entry-privacy-end-test").apply {
                isDaemon = true
                start()
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (endWorker.isAlive && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(1)
            }
            endWorker.join(100)
            assertFalse(endWorker.isAlive)
            assertEquals(true, endResult.get())
        } finally {
            try {
                assertTrue(state.endSensitiveCredentialSessionAfterTermination())
            } finally {
                store.finish(run.runId, ForeverRunStatus.CANCELLED, "synthetic assisted-entry test complete")
            }
        }

        assertFalse(ForeverAssistedCredentialPrivacy.isSensitive(context.filesDir))
        assertEquals(0, initialActivity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        assertEquals(0, recreatedActivity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Test
    fun tabControlRouteAvoidsUnicodeNineOverride() {
        val now = SystemClock.uptimeMillis()
        val oldRouteServer = mock(XServer::class.java)
        val oldRouteKeyboard = Keyboard.createKeyboard(oldRouteServer)
        val androidTab = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0)
        if (androidTab.unicodeChar == '\t'.code) {
            assertTrue(oldRouteKeyboard.onVirtualKeyEvent(androidTab))
        } else {
            // Robolectric KCMs may omit Tab's Unicode value. Reproduce the
            // virtual route's exact XServer injection with Unicode 9.
            oldRouteServer.injectKeyPress(XKeycode.KEY_TAB, '\t'.code)
        }
        verify(oldRouteServer).injectKeyPress(XKeycode.KEY_TAB, '\t'.code)

        val server = mock(XServer::class.java)
        val keyboard = Keyboard.createKeyboard(server)
        val sendVirtualKeyEvent: (KeyEvent) -> Boolean = { event ->
            if (event.keyCode == KeyEvent.KEYCODE_TAB &&
                event.action == KeyEvent.ACTION_DOWN && androidTab.unicodeChar != '\t'.code
            ) {
                // Robolectric KCMs may omit Tab's Unicode value. Reproduce
                // the same upstream virtual-keyboard injection with Unicode 9.
                server.injectKeyPress(XKeycode.KEY_TAB, '\t'.code)
                true
            } else {
                keyboard.onVirtualKeyEvent(event)
            }
        }
        val encoded = requireNotNull(
            ForeverFunctionalAutologinKeyEncoder.encode("a".toCharArray(), "p".toCharArray()),
        )
        encoded.toList().chunked(2).forEach { (keyCode, metaState) ->
            assertTrue(
                dispatchForeverFunctionalAutologinStroke(
                    ForeverFunctionalAutologinStroke(keyCode, metaState),
                    sendVirtualKeyEvent,
                    keyboard::onKeyEvent,
                ),
            )
        }
        verify(server).injectKeyPress(XKeycode.KEY_TAB)
        verify(server).injectKeyPress(XKeycode.KEY_ENTER)
        verify(server, never()).injectKeyPress(XKeycode.KEY_TAB, '\t'.code)
    }

    @Test
    fun productionEncoderAndKeyboardDispatchPreserveMixedCaseAndSymbols() {
        val encoded = ForeverFunctionalAutologinKeyEncoder.encode("aZ9!@".toCharArray(), "Pp-_".toCharArray())
        assertNotNull(encoded)
        val xServer = mock(XServer::class.java)
        val keyboard = Keyboard.createKeyboard(xServer)

        requireNotNull(encoded).toList().chunked(2).forEach { (keyCode, metaState) ->
            assertTrue(
                dispatchForeverFunctionalAutologinStroke(
                    ForeverFunctionalAutologinStroke(keyCode, metaState),
                    keyboard::onVirtualKeyEvent,
                    keyboard::onKeyEvent,
                ),
            )
        }

        verify(xServer).injectKeyPress(XKeycode.KEY_A, 'a'.code)
        verify(xServer).injectKeyPress(XKeycode.KEY_Z, 'Z'.code)
        verify(xServer).injectKeyPress(XKeycode.KEY_9, '9'.code)
        verify(xServer).injectKeyPress(XKeycode.KEY_1, '!'.code)
        verify(xServer).injectKeyPress(XKeycode.KEY_2, '@'.code)
        verify(xServer).injectKeyPress(XKeycode.KEY_P, 'P'.code)
        verify(xServer).injectKeyPress(XKeycode.KEY_P, 'p'.code)
        verify(xServer).injectKeyPress(XKeycode.KEY_MINUS, '-'.code)
        verify(xServer).injectKeyPress(XKeycode.KEY_MINUS, '_'.code)
        verify(xServer).injectKeyPress(XKeycode.KEY_TAB)
        verify(xServer).injectKeyPress(XKeycode.KEY_ENTER)
        verify(xServer, times(5)).injectKeyPress(XKeycode.KEY_SHIFT_L)
        verify(xServer, times(9)).injectKeyRelease(XKeycode.KEY_SHIFT_L)
    }

    @Test
    fun productionAdapterDoesNotSuppressConcurrentManualKeyDuringSyntheticStroke() {
        val activity = Robolectric.buildActivity(FocusedTestActivity::class.java).setup().get()
        val server = mock(XServer::class.java)
        val keyboard = Keyboard(server)
        val windowManager = mock(XWindowManager::class.java)
        val syntheticPressStarted = CountDownLatch(1)
        val concurrentManualEventDone = CountDownLatch(1)
        val pauseFirstSyntheticA = AtomicBoolean(true)
        setXServerField(server, "keyboard", keyboard)
        setXServerField(server, "screenInfo", ScreenInfo(800, 600))
        setXServerField(server, "windowManager", windowManager)
        org.mockito.Mockito.doAnswer { invocation ->
            val key = invocation.getArgument<XKeycode>(0)
            val keysym = invocation.getArgument<Int>(1)
            keyboard.setKeyPress(key.id, keysym)
            if (key == XKeycode.KEY_A && pauseFirstSyntheticA.compareAndSet(true, false)) {
                syntheticPressStarted.countDown()
                check(concurrentManualEventDone.await(5, TimeUnit.SECONDS))
            }
            null
        }.`when`(server).injectKeyPress(
            org.mockito.ArgumentMatchers.any(XKeycode::class.java),
            org.mockito.ArgumentMatchers.anyInt(),
        )
        org.mockito.Mockito.doAnswer { invocation ->
            val key = invocation.getArgument<XKeycode>(0)
            keyboard.setKeyPress(key.id, 0)
            null
        }.`when`(server).injectKeyPress(org.mockito.ArgumentMatchers.any(XKeycode::class.java))
        org.mockito.Mockito.doAnswer { invocation ->
            val key = invocation.getArgument<XKeycode>(0)
            keyboard.setKeyRelease(key.id)
            null
        }.`when`(server).injectKeyRelease(org.mockito.ArgumentMatchers.any(XKeycode::class.java))
        val renderer = TestRendererView(activity, server)
        val previousView = PluviaApp.xServerView
        val previousEnvironment = PluviaApp.xEnvironment
        val prepared = ForeverFunctionalPoc.Prepared(
            container = mock(Container::class.java),
            gameFolder = File("unused-game"),
            logFile = File("unused-log"),
            profileId = "test",
            memoryPatchEnabled = false,
            executable = File("unused-executable"),
        )
        val gameWindow = object : XWindow(123, null, 0, 0, 800, 600, null) {
            override fun getClassName(): String = ForeverFunctionalPoc.EXECUTABLE_NAME
        }.apply { attributes.setMapped(true) }
        org.mockito.Mockito.`when`(windowManager.focusedWindow).thenReturn(gameWindow)
        try {
            PluviaApp.xServerView = renderer
            PluviaApp.xEnvironment = mock(XEnvironment::class.java)
            activity.setContentView(renderer)
            renderer.measure(
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            )
            renderer.layout(0, 0, 800, 600)

            val adapter = ForeverFunctionalAutologinXServerInput(
                activity,
                prepared,
                AtomicReference(server to gameWindow),
            )
            val snapshot = requireNotNull(adapter.snapshot())
            assertTrue(snapshot.usable())

            val notifications = AtomicInteger()
            val manualObserved = CountDownLatch(1)
            val observer = adapter.observeManualInput {
                notifications.incrementAndGet()
                manualObserved.countDown()
            }
            val manualFailure = AtomicReference<Throwable?>()
            val manualEvent = Thread({
                try {
                    check(syntheticPressStarted.await(5, TimeUnit.SECONDS))
                    val downAt = SystemClock.uptimeMillis()
                    server.keyboard.onKeyEvent(
                        KeyEvent(downAt, downAt, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, 0),
                    )
                    val upAt = SystemClock.uptimeMillis()
                    server.keyboard.onKeyEvent(
                        KeyEvent(downAt, upAt, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_B, 0),
                    )
                } catch (failure: Throwable) {
                    manualFailure.set(failure)
                } finally {
                    concurrentManualEventDone.countDown()
                }
            }, "assisted-entry-concurrent-manual-input").apply {
                isDaemon = true
                start()
            }

            try {
                assertTrue(
                    adapter.send(
                        snapshot,
                        ForeverFunctionalAutologinStroke(KeyEvent.KEYCODE_A, 0),
                    ),
                )
                manualEvent.join(5_000)
                assertFalse(manualEvent.isAlive)
                assertNull(manualFailure.get())
                assertEquals(1, notifications.get())
                assertTrue(manualObserved.await(0, TimeUnit.MILLISECONDS))
            } finally {
                observer.close()
                concurrentManualEventDone.countDown()
            }
        } finally {
            PluviaApp.xServerView = previousView
            PluviaApp.xEnvironment = previousEnvironment
        }
    }

    @Test
    fun productionDispatchAttemptsReleaseAfterDownOrReleaseThrows() {
        val downFailureEvents = mutableListOf<Int>()
        assertFalse(
            dispatchForeverFunctionalAutologinStroke(ForeverFunctionalAutologinStroke(KeyEvent.KEYCODE_A, 0)) {
                downFailureEvents += it.action
                if (it.action == KeyEvent.ACTION_DOWN) throw IllegalStateException()
                true
            },
        )
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), downFailureEvents)

        val releaseFailureEvents = mutableListOf<Int>()
        var releaseCalls = 0
        assertFalse(
            dispatchForeverFunctionalAutologinStroke(
                ForeverFunctionalAutologinStroke(KeyEvent.KEYCODE_SHIFT_LEFT, 0),
            ) {
                releaseFailureEvents += it.action
                if (it.action == KeyEvent.ACTION_UP && releaseCalls++ == 0) throw IllegalStateException()
                true
            },
        )
        assertEquals(
            listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP, KeyEvent.ACTION_UP),
            releaseFailureEvents,
        )
    }

    @Test
    fun manualInputDuringPreparationMakesLateCallbackInert() {
        val state = ForeverFunctionalAutologinAssistedEntryState()
        val input = FakeInput()
        val scheduler = FakeScheduler()
        val worker = FakeWorker()
        val results = mutableListOf<ForeverFunctionalAutologinAssistedResult>()
        var acquisitions = 0
        val sender = sender(state, input, scheduler, results, worker = worker, acquire = {
            acquisitions++
            credentials("u", "p")
        })

        sender.request()
        input.manualInput()
        worker.runNext()
        scheduler.runAll()
        sender(state, FakeInput(), FakeScheduler(), mutableListOf(), acquire = {
            acquisitions++
            credentials("u", "p")
        }).request()

        assertEquals(0, acquisitions)
        assertTrue(input.sent.isEmpty())
        assertEquals(ForeverFunctionalAutologinAssistedResult.CANCELLED, results.last())
    }

    @Test
    fun unavailableSessionOrCredentialsProducesNoInput() {
        val noSessionInput = FakeInput().apply { current = null }
        val noSessionScheduler = FakeScheduler()
        val noSessionResults = mutableListOf<ForeverFunctionalAutologinAssistedResult>()
        var noSessionAcquisitions = 0
        sender(
            ForeverFunctionalAutologinAssistedEntryState(), noSessionInput, noSessionScheduler,
            noSessionResults, acquire = {
                noSessionAcquisitions++
                credentials("u", "p")
            },
        ).request()

        val noCredentialInput = FakeInput()
        val noCredentialScheduler = FakeScheduler()
        val noCredentialResults = mutableListOf<ForeverFunctionalAutologinAssistedResult>()
        var noCredentialAcquisitions = 0
        sender(
            ForeverFunctionalAutologinAssistedEntryState(), noCredentialInput, noCredentialScheduler,
            noCredentialResults, acquire = {
                noCredentialAcquisitions++
                null
            },
        ).request()
        noCredentialScheduler.runAll()

        assertEquals(0, noSessionAcquisitions)
        assertEquals(1, noCredentialAcquisitions)
        assertTrue(noSessionInput.sent.isEmpty())
        assertTrue(noCredentialInput.sent.isEmpty())
        assertEquals(ForeverFunctionalAutologinAssistedResult.SESSION_UNAVAILABLE, noSessionResults.last())
        assertEquals(ForeverFunctionalAutologinAssistedResult.CREDENTIALS_UNAVAILABLE, noCredentialResults.last())
    }

    @Test
    fun manualInputAndObservableSessionChangesStopFurtherInputWithoutRearming() {
        val mutations: List<(FakeInput, ForeverFunctionalAutologinAssistedEntry) -> Unit> = listOf(
            { input, _ -> input.manualInput() },
            { input, _ -> input.current = input.current?.copy(activityFocused = false) },
            { input, _ -> input.current = input.current?.copy(windowFocused = false) },
            { input, _ -> input.current = input.current?.copy(width = 801) },
            { input, _ -> input.current = input.current?.copy(rendererY = 25) },
            { input, _ -> input.current = null },
            { _, sender -> sender.cancel() },
        )

        mutations.forEach { mutate ->
            val state = ForeverFunctionalAutologinAssistedEntryState()
            val input = FakeInput()
            val scheduler = FakeScheduler()
            val results = mutableListOf<ForeverFunctionalAutologinAssistedResult>()
            var acquisitions = 0
            val sender = sender(state, input, scheduler, results, acquire = {
                acquisitions++
                credentials("u", "p")
            })

            sender.request()
            scheduler.runNext() // first stroke is sent; the remaining sequence is pending
            assertEquals(1, input.sent.size)
            mutate(input, sender)
            scheduler.runAll()
            sender.request()

            assertEquals(1, input.sent.size)
            assertEquals(1, acquisitions)
            assertEquals(ForeverFunctionalAutologinAssistedResult.CANCELLED, results.last())
        }
    }

    @Test
    fun captureBlocksBeforeCredentialAcquisitionAndReportsOnlyCodes() {
        val input = FakeInput().apply { current = current?.copy(memoryCapture = true) }
        val scheduler = FakeScheduler()
        val results = mutableListOf<ForeverFunctionalAutologinAssistedResult>()
        var acquisitions = 0
        val sender = sender(
            ForeverFunctionalAutologinAssistedEntryState(), input, scheduler, results, acquire = {
            acquisitions++
            credentials("username-secret", "password-secret")
        })

        sender.request()
        scheduler.runAll()

        assertEquals(0, acquisitions)
        assertTrue(input.sent.isEmpty())
        assertEquals(ForeverFunctionalAutologinAssistedResult.CAPTURE_BLOCKED, results.last())
        assertSecretFree(results)
        credentials("username-secret", "password-secret").use {
            assertFalse(it.toString().contains("secret"))
        }
    }

    private fun sender(
        state: ForeverFunctionalAutologinAssistedEntryState,
        input: FakeInput,
        scheduler: FakeScheduler,
        results: MutableList<ForeverFunctionalAutologinAssistedResult>,
        beginSensitiveSession: () -> Boolean = { true },
        acquire: () -> ForeverFunctionalAutologinCredentials?,
        encoder: ForeverFunctionalAutologinInputEncoder = faithfulSyntheticEncoder,
        worker: ForeverFunctionalAutologinWorker = ForeverFunctionalAutologinWorker { it.run() },
    ) = ForeverFunctionalAutologinAssistedEntry(
        launch = state,
        beginSensitiveSession = beginSensitiveSession,
        acquireCredentials = acquire,
        input = input,
        scheduler = scheduler,
        encoder = encoder,
        worker = worker,
        report = { results += it },
    )

    private fun assertSecretFree(results: List<ForeverFunctionalAutologinAssistedResult>) {
        val output = results.joinToString()
        assertFalse(output.contains("username-secret"))
        assertFalse(output.contains("password-secret"))
    }

    private fun credentials(username: String, password: String) =
        ForeverFunctionalAutologinCredentials(username.toCharArray(), password.toCharArray())

    private val faithfulSyntheticEncoder = ForeverFunctionalAutologinInputEncoder { username, password ->
        if (!username.contentEquals(charArrayOf('u')) || !password.contentEquals(charArrayOf('p'))) {
            null
        } else {
            intArrayOf(
                KeyEvent.KEYCODE_U, 0,
                KeyEvent.KEYCODE_TAB, 0,
                KeyEvent.KEYCODE_P, 0,
                KeyEvent.KEYCODE_ENTER, 0,
            )
        }
    }

    private class FakeScheduler : ForeverFunctionalAutologinScheduler {
        private val callbacks = ArrayDeque<Runnable>()

        override fun post(delayMs: Long, callback: Runnable): Boolean {
            callbacks.addLast(callback)
            return true
        }

        override fun remove(callback: Runnable) {
            callbacks.remove(callback)
        }

        fun runNext() {
            callbacks.removeFirstOrNull()?.run()
        }

        fun runAll() {
            while (callbacks.isNotEmpty()) runNext()
        }
    }

    private class FakeWorker : ForeverFunctionalAutologinWorker {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(task: Runnable) {
            tasks.addLast(task)
        }

        fun runNext() {
            tasks.removeFirstOrNull()?.run()
        }
    }

    private class FakeInput : ForeverFunctionalAutologinInputPort {
        private val session = Any()
        private val renderer = Any()
        private val window = Any()
        var current: ForeverFunctionalAutologinInputSnapshot? = ForeverFunctionalAutologinInputSnapshot(
            sessionIdentity = session,
            rendererIdentity = renderer,
            windowIdentity = window,
            windowId = 1L,
            x = 0,
            y = 0,
            width = 800,
            height = 600,
            screenWidth = 800,
            screenHeight = 600,
            rendererX = 0,
            rendererY = 24,
            rendererWidth = 800,
            rendererHeight = 600,
            sessionOpen = true,
            targetWindow = true,
            windowFocused = true,
            activityFocused = true,
            memoryCapture = false,
        )
        val sent = mutableListOf<ForeverFunctionalAutologinStroke>()
        private var manualCallback: (() -> Unit)? = null
        val manualObserverActive: Boolean get() = manualCallback != null

        override fun snapshot(): ForeverFunctionalAutologinInputSnapshot? = current

        override fun send(
            expected: ForeverFunctionalAutologinInputSnapshot,
            stroke: ForeverFunctionalAutologinStroke,
        ): Boolean {
            if (!expected.matches(current)) return false
            sent += stroke
            return true
        }

        override fun observeManualInput(callback: () -> Unit): AutoCloseable {
            manualCallback = callback
            return AutoCloseable { manualCallback = null }
        }

        fun manualInput() {
            manualCallback?.invoke()
        }
    }

    private class FocusedTestActivity : ComponentActivity() {
        override fun hasWindowFocus(): Boolean = true
    }

    private class TestRendererView(
        context: Context,
        private val server: XServer,
    ) : View(context), XServerRendererView {
        override fun queueEvent(runnable: Runnable) = runnable.run()
        override fun requestRender() = Unit
        override fun setFrameRateLimit(limit: Int) = Unit
        override fun onResume() = Unit
        override fun onPause() = Unit
        override fun getxServer(): XServer = server
        override fun getRenderer(): XServerRenderer? = null
    }

    private fun setXServerField(server: XServer, name: String, value: Any) {
        XServer::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(server, value)
        }
    }
}
