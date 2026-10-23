package app.gamenative

import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

/** The private JNI entry point for the Android WSI control only. */
object PanvkWsiBridge {
    @JvmStatic
    external fun nativeRun(
        surface: Surface,
        icdPath: String,
        outputPath: String,
        runId: String,
        generation: Long,
    ): String

    @JvmStatic
    external fun nativeCancel(generation: Long)

    internal interface NativeApi {
        fun run(surface: Surface, icdPath: String, outputPath: String, runId: String, generation: Long): String
        fun cancel(generation: Long)
    }

    /* Tests may provide a fake; production never installs one. */
    @Volatile
    internal var testApi: NativeApi? = null

    private val loaded = AtomicBoolean(false)
    private val pendingCancels = ConcurrentHashMap.newKeySet<Long>()

    internal fun run(
        surface: Surface,
        icdPath: String,
        outputPath: String,
        runId: String,
        generation: Long,
    ): String {
        if (pendingCancels.contains(generation)) throw java.util.concurrent.CancellationException("WSI cancelled before JNI")
        testApi?.let { return it.run(surface, icdPath, outputPath, runId, generation) }
        loadForWork()
        if (pendingCancels.contains(generation)) throw java.util.concurrent.CancellationException("WSI cancelled while JNI loaded")
        return nativeRun(surface, icdPath, outputPath, runId, generation)
    }

    internal fun cancel(generation: Long) {
        pendingCancels.add(generation)
        testApi?.let {
            runCatching { it.cancel(generation) }
            return
        }
        // A cancellation before the worker has entered native code must not load
        // a library or create a native worker of its own.
        if (loaded.get()) runCatching { nativeCancel(generation) }
    }

    private fun loadForWork() {
        if (loaded.compareAndSet(false, true)) {
            try {
                System.loadLibrary("panvk_wsi_probe")
            } catch (failure: Throwable) {
                loaded.set(false)
                throw failure
            }
        }
    }
}
