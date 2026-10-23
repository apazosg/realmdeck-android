package app.gamenative

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView

/** Dedicated debug Activity; it deliberately has no dependency on the normal launcher. */
class PanvkWsiActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        const val WIDTH = 320
        const val HEIGHT = 240
        private const val LABEL_HEIGHT_DP = 32
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private lateinit var store: PanvkWsiStore
    private lateinit var root: FrameLayout
    private lateinit var statusLabel: TextView
    private lateinit var surfaceView: SurfaceView
    private var run: PanvkWsiRun? = null
    private var surface: Surface? = null
    private var surfaceReady = false
    private var readyPublished = false
    private var startRequested = false
    private var nativeStarted = false
    private var nativeReturned = false
    private var nativeCallEntered = false
    private var cancelRequested = false
    private var destroyed = false
    private var sessionClaimed = false
    private var preparationThread: Thread? = null
    private var nativeThread: Thread? = null
    private var icdPath: String? = null
    private val startGate = PanvkWsiStartGate()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!PanvkWsiPolicy.shouldIsolate(this)) {
            // The manifest/process gate is the authoritative protection.  Do not
            // touch files or assets if a normal process reaches this Activity.
            throw SecurityException("PanVK WSI Activity reached from a non-WSI process")
        }
        buildSurfaceUi()
        if (!PanvkWsiPolicy.claimProcessSession()) {
            status("WSI process session already claimed")
            return
        }
        sessionClaimed = true
        store = PanvkWsiStore(this)
        val created = try {
            store.createRun()
        } catch (failure: Throwable) {
            status("WSI setup rejected")
            return
        }
        synchronized(lock) { run = created }
        prepareAsset(created)
        if (PanvkWsiPolicy.isStartIntent(intent)) requestStart()
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (sessionClaimed && PanvkWsiPolicy.isStartIntent(intent)) requestStart()
    }

    private fun buildSurfaceUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        statusLabel = TextView(this).apply {
            text = "PanVK WSI PREPARING"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            setPadding(8, 0, 8, 0)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = (LABEL_HEIGHT_DP * resources.displayMetrics.density).toInt()
        }
        root.addView(statusLabel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.START))
        surfaceView = SurfaceView(this).apply {
            setZOrderOnTop(false)
            holder.setFormat(PixelFormat.OPAQUE)
            holder.setFixedSize(WIDTH, HEIGHT)
            holder.addCallback(this@PanvkWsiActivity)
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(WIDTH, HEIGHT, Gravity.TOP or Gravity.START).apply {
            topMargin = (LABEL_HEIGHT_DP * resources.displayMetrics.density).toInt()
        })
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val params = surfaceView.layoutParams as FrameLayout.LayoutParams
            if (params.topMargin != statusLabel.bottom) {
                params.topMargin = statusLabel.bottom
                surfaceView.layoutParams = params
            }
        }
        setContentView(root)
    }

    private fun prepareAsset(created: PanvkWsiRun) {
        preparationThread = Thread({
            try {
                val icd = store.prepareIcd(created)
                main.post {
                    synchronized(lock) {
                        if (destroyed || run?.generation != created.generation) return@post
                        icdPath = icd.absolutePath
                        publishReadyIfPossible()
                    }
                }
            } catch (_: Throwable) {
                main.post {
                    synchronized(lock) {
                        if (!destroyed && run?.generation == created.generation) status("WSI asset error")
                    }
                }
            }
        }, "panvk-wsi-asset")
        preparationThread?.start()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(lock) {
            if (destroyed || !sessionClaimed) return
            surface = holder.surface
            surfaceReady = holder.surface.isValid
            publishReadyIfPossible()
            run?.let { store.appendEvent(it, "surfaceCreated") }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(lock) {
            if (destroyed || !sessionClaimed) return
            if (width != WIDTH || height != HEIGHT) {
                stopForLifecycle("surface_resize")
                return
            }
            surfaceReady = holder.surface.isValid
            publishReadyIfPossible()
            run?.let { store.appendEvent(it, "surfaceChanged") }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(lock) {
            if (!sessionClaimed) return
            surfaceReady = false
            surface = holder.surface
            stopForLifecycle("surface_destroyed")
            run?.let { store.appendEvent(it, "surfaceDestroyed") }
        }
    }

    private fun publishReadyIfPossible() {
        val current = run ?: return
        if (!sessionClaimed || readyPublished || icdPath == null || !surfaceReady || startGate.isCancelled()) return
        if (store.readState(current)?.status != PanvkWsiStatus.PREPARING) return
        if (surfaceView.width != WIDTH || surfaceView.height != HEIGHT) return
        val bounds = Rect()
        if (!surfaceView.getGlobalVisibleRect(bounds)) return
        try {
            store.publishReady(current, ApplicationProcessName.name(), WIDTH, HEIGHT,
                surfaceView.width, surfaceView.height, true,
                listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))
            readyPublished = true
            status("WSI READY ${current.runId}")
            startGate.surfaceBecameReady()
            startNativeIfReady()
        } catch (_: Throwable) {
            status("WSI surface rejected")
        }
    }

    private fun requestStart() {
        synchronized(lock) {
            if (!sessionClaimed || destroyed || startRequested || startGate.isCancelled()) return
            startRequested = true
            val canStart = startGate.request(readyPublished)
            run?.let { store.appendEvent(it, "start") }
            if (canStart) startNativeIfReady()
        }
    }

    private fun startNativeIfReady() {
        val current = run ?: return
        val currentSurface = surface ?: return
        val path = icdPath ?: return
        if (!PanvkWsiPolicy.mayStartNative(hostResumed) || !sessionClaimed || !readyPublished || !startGate.isArmed() || nativeStarted || destroyed || cancelRequested) return
        nativeStarted = true
        try {
            store.markStarting(current)
            nativeThread = Thread({ runNative(current, currentSurface, path) }, "panvk-wsi-native-${current.generation}")
            nativeThread?.start()
        } catch (failure: Throwable) {
            // The one-use arm is consumed even when STARTING cannot be
            // journalled. Never make a READY run eligible for a second worker.
            storeFailure(current, failure)
        }
    }

    private fun runNative(current: PanvkWsiRun, currentSurface: Surface, path: String) {
        try {
            synchronized(lock) {
                val state = store.readState(current)
                if (destroyed || cancelRequested || !nativeStarted ||
                    state?.status != PanvkWsiStatus.STARTING) return
                store.markRunning(current)
                nativeCallEntered = true
            }
            val report = PanvkWsiBridge.run(currentSurface, path, current.nativeJournalPath.absolutePath,
                current.runId, current.generation)
            synchronized(lock) {
                nativeReturned = true
                val state = store.readState(current)
                if (state?.status == PanvkWsiStatus.UNKNOWN) {
                    store.recordLateNativeReturn(current, report)
                } else if (!destroyed && state?.status !in setOf(PanvkWsiStatus.CANCELLED, PanvkWsiStatus.ERROR)) {
                    store.finishNative(current, report)
                } else {
                    store.recordLateNativeReturn(current, report)
                }
            }
        } catch (failure: Throwable) {
            synchronized(lock) {
                nativeReturned = true
                if (!destroyed && store.readState(current)?.status !in setOf(PanvkWsiStatus.UNKNOWN, PanvkWsiStatus.CANCELLED)) {
                    storeFailure(current, failure)
                }
            }
        }
    }

    private fun stopForLifecycle(reason: String) {
        val current = run ?: return
        startGate.cancel()
        cancelRequested = true
        if (nativeStarted && !nativeReturned) {
            PanvkWsiBridge.cancel(current.generation)
            if (nativeCallEntered && nativeThread?.isAlive == true) store.markUnknown(current, "$reason while native was in flight")
            else store.cancel(current, reason)
        } else {
            store.cancel(current, reason)
        }
        status("WSI ${reason.take(24)}")
    }

    private fun storeFailure(current: PanvkWsiRun, failure: Throwable) {
        // A native failure is not exposed as a visual observation; in-flight
        // uncertainty remains UNKNOWN rather than claiming driver cleanup.
        store.markUnknown(current, "native error: ${(failure.message ?: "unknown").take(180)}")
    }

    private var hostResumed = false

    override fun onResume() {
        super.onResume()
        synchronized(lock) {
            hostResumed = true
            if (!destroyed) {
                publishReadyIfPossible()
                startNativeIfReady()
            }
        }
    }

    override fun onPause() {
        synchronized(lock) {
            hostResumed = false
            if (!destroyed && PanvkWsiPolicy.cancelOnPause(nativeStarted)) stopForLifecycle("pause")
        }
        super.onPause()
    }

    override fun onStop() {
        synchronized(lock) { if (!destroyed) stopForLifecycle("stop") }
        super.onStop()
    }

    override fun onDestroy() {
        synchronized(lock) {
            destroyed = true
            startGate.cancel()
            cancelRequested = true
            val current = run
            if (sessionClaimed && current != null) {
                if (nativeStarted && !nativeReturned && nativeCallEntered && nativeThread?.isAlive == true) {
                    PanvkWsiBridge.cancel(current.generation)
                    store.markUnknown(current, "activity_destroyed while native was in flight")
                } else if (store.readState(current)?.status !in setOf(PanvkWsiStatus.RESULT, PanvkWsiStatus.UNKNOWN,
                        PanvkWsiStatus.ERROR, PanvkWsiStatus.CANCELLED)) {
                    store.cancel(current, "activity_destroyed")
                }
            }
        }
        super.onDestroy()
    }

    private fun status(value: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) statusLabel.text = value.take(200)
        else main.post { if (!destroyed) statusLabel.text = value.take(200) }
    }

    private object ApplicationProcessName {
        fun name(): String = android.app.Application.getProcessName()
    }
}
