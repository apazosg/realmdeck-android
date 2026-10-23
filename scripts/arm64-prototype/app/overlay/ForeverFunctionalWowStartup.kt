package app.gamenative

/** A scheduling signal, never proof of process ownership, presented pixels or Login. */
class ForeverFunctionalWowStartup(
    private val expectedWidth: Int,
    private val expectedHeight: Int,
    private val targetClass: String,
    private val isCurrent: () -> Boolean,
    private val onReady: (Long) -> Unit,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    val startedAtMs = clockMs()
    private val contentWindows = HashSet<Int>()
    private var closed = false
    private var signaled = false

    fun observe(id: Int, className: String, width: Int, height: Int, mapped: Boolean, contentUpdated: Boolean) {
        val ready = synchronized(this) {
            if (closed || signaled || !isCurrent()) return@synchronized false
            if (contentUpdated) contentWindows.add(id)
            val matches = expectedWidth > 1 && expectedHeight > 1 && mapped &&
                width == expectedWidth && height == expectedHeight &&
                className.contains(targetClass, ignoreCase = true) && id in contentWindows
            if (matches) signaled = true
            matches
        }
        // Never call outward while holding the observation lock.
        if (ready) runCatching { onReady(clockMs() - startedAtMs) }
    }

    @Synchronized fun forget(id: Int) { contentWindows.remove(id) }
    @Synchronized fun isActive(): Boolean = !closed && isCurrent()
    @Synchronized fun close() { closed = true; contentWindows.clear() }
}
