package app.gamenative

/** Bounded, monotonic-clock estimator of useful network bytes, never local reuse. */
class ForeverClientDownloadRate {
    private companion object {
        const val WINDOW_MILLIS = 10_000L
    }
    data class Estimate(val bytesPerSecond: Double, val remainingSeconds: Long?) {
        fun text(): String {
            val speed = "%.1f MB/s".format(java.util.Locale.ROOT, bytesPerSecond / 1_000_000)
            val seconds = remainingSeconds ?: return speed
            val minutes = (seconds + 59) / 60
            val eta = when {
                seconds < 60 -> "<1 min"
                minutes < 60 -> "$minutes min"
                else -> "${minutes / 60} h ${minutes % 60} min"
            }
            return "$speed · ~$eta"
        }
    }

    private data class Sample(val time: Long, val bytes: Long)
    private val samples = java.util.ArrayDeque<Sample>()
    private var lastAdvance = 0L
    private var lastBytes = 0L
    private var total = 0L

    @Synchronized fun reset() {
        samples.clear()
        lastAdvance = 0
        lastBytes = 0
        total = 0
    }

    @Synchronized fun record(detail: ForeverClientUpdateProgress, now: Long) {
        if (detail.phase != ForeverClientUpdateProgress.Phase.DOWNLOADING ||
            detail.totalTransferBytes <= 0 || detail.transferredBytes !in 0..detail.totalTransferBytes) return
        if (samples.isNotEmpty() && (detail.transferredBytes < lastBytes || now < samples.last.time ||
            detail.totalTransferBytes != total)) reset()
        if (samples.isEmpty() || detail.transferredBytes > lastBytes) lastAdvance = now
        lastBytes = detail.transferredBytes
        total = detail.totalTransferBytes
        if (samples.isEmpty() || now - samples.last.time >= 250) samples.addLast(Sample(now, lastBytes))
        trimWindow(now)
    }

    @Synchronized fun estimate(now: Long): Estimate? {
        if (now < lastAdvance || now - lastAdvance >= WINDOW_MILLIS) return null
        trimWindow(now)
        if (samples.size < 2) return null
        val first = samples.first
        val elapsed = now - first.time
        val bytes = lastBytes - first.bytes
        if (elapsed < 1_000 || bytes <= 0 || lastBytes >= total) return null
        val rate = bytes * 1000.0 / elapsed
        return Estimate(rate, kotlin.math.ceil((total - lastBytes) / rate).toLong())
    }

    private fun trimWindow(now: Long) {
        while (samples.size > 1 && now - samples.first.time > WINDOW_MILLIS) samples.removeFirst()
    }
}
