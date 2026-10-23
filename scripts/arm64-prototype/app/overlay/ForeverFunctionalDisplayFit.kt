package app.gamenative

import android.content.Context
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure display policy and the small launch gate used by the functional WoW
 * launcher.  It deliberately has no dependency on XServer or on a renderer:
 * the measured Android viewport is the input and the frozen plan is the
 * output consumed by both Wine and WoW.
 */
object ForeverFunctionalDisplayFit {
    // A recreated Activity must not bypass a non-cooperative write still owned
    // by its predecessor. This coordinator retains tokens only, not Activities.
    private val processLaunchCoordinator = ForeverFunctionalDisplayFitLaunchCoordinator()

    fun launchCoordinatorForProcess(): ForeverFunctionalDisplayFitLaunchCoordinator = processLaunchCoordinator

    enum class Mode(val persistedName: String, val label: String, val targetShortEdge: Int?) {
        // Labels describe resolution only (never graphics quality); the
        // persisted names stay unchanged for compatibility with saved prefs.
        AUTO("AUTO", "Automática · 720", 720),
        PERFORMANCE("PERFORMANCE", "Resolución 540", 540),
        QUALITY("QUALITY", "Resolución 900", 900),
        NATIVE("NATIVE", "Nativa", null),
        ;

        companion object {
            fun fromPersisted(value: String?): Mode =
                values().firstOrNull { it.persistedName.equals(value, ignoreCase = true) } ?: AUTO
        }

        val nativeWarning: Boolean
            get() = this == NATIVE
    }

    data class Insets(
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0,
    ) {
        init {
            require(left >= 0 && top >= 0 && right >= 0 && bottom >= 0) {
                "display insets must not be negative"
            }
        }
    }

    /** Dimensions must be in the same coordinate space as the game root. */
    data class Viewport(
        val width: Int,
        val height: Int,
        val insets: Insets = Insets(),
        val viewLeft: Int = 0,
        val viewTop: Int = 0,
        val windowWidth: Int = width,
        val windowHeight: Int = height,
    ) {
        init {
            require(width > 0 && height > 0) { "display viewport must be positive" }
            require(viewLeft >= 0 && viewTop >= 0) { "display viewport origin must be window-relative" }
            require(windowWidth > 0 && windowHeight > 0) { "display window must be positive" }
            require(viewLeft + width <= windowWidth && viewTop + height <= windowHeight) {
                "display viewport must be inside its measured window"
            }
        }

        fun usableBounds(): Pair<Int, Int> {
            val usableWidth = width - insets.left - insets.right
            val usableHeight = height - insets.top - insets.bottom
            require(usableWidth > 0 && usableHeight > 0) {
                "display insets leave no usable viewport"
            }
            require(usableWidth >= 2 && usableHeight >= 2) {
                "display viewport is too small for an even resolution"
            }
            require(usableWidth > usableHeight) {
                "display viewport is not landscape"
            }
            return usableWidth to usableHeight
        }
    }

    data class Plan(
        val mode: Mode,
        val viewport: Viewport,
        val usableWidth: Int,
        val usableHeight: Int,
        val width: Int,
        val height: Int,
        val scale: Double,
    ) {
        val size: String
            get() = "${width}x${height}"

        /** Non-secret fields suitable for the functional JSONL run log. */
        fun logFields(applied: Boolean = false): Map<String, String> = mapOf(
            "displayMode" to mode.persistedName,
            "actualUsableWidth" to usableWidth.toString(),
            "actualUsableHeight" to usableHeight.toString(),
            "actualWidth" to viewport.width.toString(),
            "actualHeight" to viewport.height.toString(),
            "windowWidth" to viewport.windowWidth.toString(),
            "windowHeight" to viewport.windowHeight.toString(),
            "viewLeft" to viewport.viewLeft.toString(),
            "viewTop" to viewport.viewTop.toString(),
            "insetLeft" to viewport.insets.left.toString(),
            "insetTop" to viewport.insets.top.toString(),
            "insetRight" to viewport.insets.right.toString(),
            "insetBottom" to viewport.insets.bottom.toString(),
            "scale" to "%.6f".format(java.util.Locale.ROOT, scale),
            "internalWidth" to width.toString(),
            "internalHeight" to height.toString(),
            "displayApplied" to applied.toString(),
        )
    }

    private const val PREFS = "functional_poc_display_fit"
    private const val MODE_KEY = "display_mode"

    fun defaultMode(): Mode = Mode.AUTO

    fun loadMode(context: Context): Mode = Mode.fromPersisted(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MODE_KEY, null),
    )

    fun saveMode(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(MODE_KEY, mode.persistedName)
            .apply()
    }

    /**
     * Calculates a proportional internal desktop size.  Insets are removed
     * exactly once here; callers must give XServer the same inset-adjusted
     * root bounds rather than subtracting them again downstream.
     */
    fun calculate(mode: Mode, viewport: Viewport): Plan {
        val (usableWidth, usableHeight) = viewport.usableBounds()
        val usableShortEdge = min(usableWidth, usableHeight)
        val scale = mode.targetShortEdge?.let {
            min(1.0, it.toDouble() / usableShortEdge.toDouble())
        } ?: 1.0
        val rawWidth = usableWidth * scale
        val rawHeight = usableHeight * scale
        val width = evenFloor(rawWidth, usableWidth)
        val height = evenFloor(rawHeight, usableHeight)
        val rounded = chooseEvenPair(
            rawWidth = rawWidth,
            rawHeight = rawHeight,
            usableWidth = usableWidth,
            usableHeight = usableHeight,
            seedWidth = width,
            seedHeight = height,
        )
        return Plan(
            mode = mode,
            viewport = viewport,
            usableWidth = usableWidth,
            usableHeight = usableHeight,
            width = rounded.first,
            height = rounded.second,
            scale = min(
                rounded.first.toDouble() / usableWidth.toDouble(),
                rounded.second.toDouble() / usableHeight.toDouble(),
            ),
        )
    }

    /**
     * Converts a window-edge cutout into padding relative to the measured
     * content view.  A fitted view which already starts inside the cutout is
     * therefore not inset a second time.  Null means the coordinate contract
     * was not measurable; a non-null all-zero value means it was measured and
     * no cutout overlaps the view.
     */
    fun calculateOverlappingInsets(
        windowWidth: Int,
        windowHeight: Int,
        viewLeft: Int,
        viewTop: Int,
        viewWidth: Int,
        viewHeight: Int,
        physicalCutout: Insets?,
    ): Insets? {
        if (windowWidth <= 0 || windowHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return null
        if (viewLeft < 0 || viewTop < 0 || viewLeft + viewWidth > windowWidth || viewTop + viewHeight > windowHeight) {
            return null
        }
        val cutout = physicalCutout ?: return null
        if (cutout.left > windowWidth || cutout.right > windowWidth ||
            cutout.top > windowHeight || cutout.bottom > windowHeight
        ) return null

        fun overlap(start: Int, end: Int, unsafeStart: Int, unsafeEnd: Int): Int =
            max(0, min(end, unsafeEnd) - max(start, unsafeStart))

        val viewRight = viewLeft + viewWidth
        val viewBottom = viewTop + viewHeight
        return Insets(
            left = overlap(viewLeft, viewRight, 0, cutout.left),
            top = overlap(viewTop, viewBottom, 0, cutout.top),
            right = overlap(viewLeft, viewRight, windowWidth - cutout.right, windowWidth),
            bottom = overlap(viewTop, viewBottom, windowHeight - cutout.bottom, windowHeight),
        )
    }

    private fun evenFloor(raw: Double, usable: Int): Int =
        (floor(raw / 2.0) * 2.0).toInt().coerceIn(2, (usable / 2) * 2)

    /** Chooses nearby even pixels while prioritising aspect error over size. */
    private fun chooseEvenPair(
        rawWidth: Double,
        rawHeight: Double,
        usableWidth: Int,
        usableHeight: Int,
        seedWidth: Int,
        seedHeight: Int,
    ): Pair<Int, Int> {
        val widthStart = max(2, seedWidth - 8)
        val widthEnd = min(usableWidth, floor(rawWidth).toInt())
        val heightStart = max(2, seedHeight - 8)
        val heightEnd = min(usableHeight, floor(rawHeight).toInt())
        var best: Pair<Int, Int>? = null
        var bestScore = Double.POSITIVE_INFINITY
        val desiredAspect = usableWidth.toDouble() / usableHeight.toDouble()
        for (candidateWidth in widthStart..widthEnd) {
            if (candidateWidth % 2 != 0) continue
            for (candidateHeight in heightStart..heightEnd) {
                if (candidateHeight % 2 != 0) continue
                val aspectError = abs(candidateWidth.toDouble() / candidateHeight - desiredAspect)
                val distance = abs(candidateWidth - rawWidth) + abs(candidateHeight - rawHeight)
                val score = aspectError * 1_000_000.0 + distance
                if (score < bestScore) {
                    bestScore = score
                    best = candidateWidth to candidateHeight
                }
            }
        }
        return requireNotNull(best) { "display policy could not produce an even resolution" }
    }

    sealed class Observation {
        object Waiting : Observation()
        data class Ready(val plan: Plan) : Observation()
        data class Invalid(val reason: String) : Observation()
        object TimedOut : Observation()
        object AlreadyFinalized : Observation()
    }

    /**
     * Requires two equal, valid landscape frames and has one terminal result.
     * A caller may cancel and reuse it when orientation, exit, or an error
     * returns control to the launcher.
     */
    class ForeverFunctionalDisplayFitLaunchGate(
        private val stableFramesRequired: Int = 2,
        private val timeoutMs: Long = 5_000L,
    ) {
        init {
            require(stableFramesRequired >= 2) { "display gate needs at least two stable frames" }
            require(timeoutMs > 0) { "display gate timeout must be positive" }
        }

        private var startedAtMs: Long? = null
        private var lastMode: Mode? = null
        private var lastViewport: Viewport? = null
        private var stableFrames = 0
        private var finalized = false

        fun observe(mode: Mode, viewport: Viewport?, nowMs: Long): Observation {
            if (finalized) return Observation.AlreadyFinalized
            val started = startedAtMs ?: nowMs.also { startedAtMs = it }
            if (nowMs - started >= timeoutMs) {
                finalized = true
                return Observation.TimedOut
            }
            if (viewport == null) {
                stableFrames = 0
                lastViewport = null
                return Observation.Invalid("display viewport is not ready")
            }
            val plan = try {
                calculate(mode, viewport)
            } catch (failure: IllegalArgumentException) {
                stableFrames = 0
                lastViewport = viewport
                return Observation.Invalid(failure.message ?: "invalid display viewport")
            }
            if (lastMode == mode && lastViewport == viewport) {
                stableFrames += 1
            } else {
                lastMode = mode
                lastViewport = viewport
                stableFrames = 1
            }
            if (stableFrames < stableFramesRequired) return Observation.Waiting
            finalized = true
            return Observation.Ready(plan)
        }

        fun cancel() {
            startedAtMs = null
            lastMode = null
            lastViewport = null
            stableFrames = 0
            finalized = false
        }

        val isFinalized: Boolean
            get() = finalized
    }

    /**
     * Token and I/O lease ownership for a pending launch.  Invalidating a
     * token never touches another token, and an old non-cooperative I/O task
     * keeps the launcher from starting a replacement until its lease closes.
     */
    class ForeverFunctionalDisplayFitLaunchCoordinator {
        data class Token(val id: Long)

        private var nextId = 0L
        private var current: Token? = null
        private var pendingIo = 0

        @Synchronized
        fun begin(): Token? {
            if (current != null || pendingIo != 0) return null
            val token = Token(++nextId)
            current = token
            return token
        }

        @Synchronized
        fun invalidate(token: Token) {
            if (current == token) current = null
        }

        @Synchronized
        fun isCurrent(token: Token): Boolean = current == token

        @Synchronized
        fun canCommit(token: Token, lifecycleResumed: Boolean): Boolean =
            lifecycleResumed && current == token

        @Synchronized
        fun complete(token: Token): Boolean {
            if (current != token) return false
            current = null
            return true
        }

        @Synchronized
        fun acquireIo(token: Token): IoLease? {
            if (current != token) return null
            pendingIo += 1
            return IoLease(this, token)
        }

        @Synchronized
        private fun releaseIo(token: Token) {
            if (pendingIo > 0) pendingIo -= 1
        }

        @Synchronized
        fun hasPendingIo(): Boolean = pendingIo != 0

        class IoLease internal constructor(
            private val owner: ForeverFunctionalDisplayFitLaunchCoordinator,
            private val token: Token,
        ) : AutoCloseable {
            private var closed = false

            @Synchronized
            override fun close() {
                if (closed) return
                closed = true
                owner.releaseIo(token)
            }
        }
    }
}
