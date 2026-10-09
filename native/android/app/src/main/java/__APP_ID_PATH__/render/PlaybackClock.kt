package __APP_ID__.render

/**
 * The renderer's time, derived from the native AudioEngine (never from JS, Date.now() or requestAnimationFrame).
 * AudioEngine publishes {position, playing} on every state change and every 250 ms while playing; between those
 * the render thread extrapolates with a monotonic clock. Small disagreements (< [RESYNC_MS]) are ignored so the
 * picture does not jitter every time the coarse MediaPlayer position is re-read; real jumps (seek) snap at once.
 * Thread safe: one writer (main thread), any readers.
 */
internal class PlaybackClock {
    private class Anchor(val posMs: Double, val atMs: Long, val playing: Boolean, val durationMs: Long)

    @Volatile private var anchor = Anchor(0.0, 0L, false, 0L)

    /** [nowMs] is a monotonic clock (SystemClock.elapsedRealtime()). */
    fun sync(positionMs: Long, playing: Boolean, durationMs: Long, nowMs: Long) {
        val old = anchor
        val dur = if (durationMs > 0) durationMs else old.durationMs
        if (!playing) {
            anchor = Anchor(positionMs.toDouble(), nowMs, false, dur)
            return
        }
        if (!old.playing) {
            anchor = Anchor(positionMs.toDouble(), nowMs, true, dur)
            return
        }
        val expected = old.posMs + (nowMs - old.atMs)
        anchor = if (Math.abs(expected - positionMs) > RESYNC_MS) Anchor(positionMs.toDouble(), nowMs, true, dur)
        else Anchor(old.posMs, old.atMs, true, dur)
    }

    fun isPlaying(): Boolean = anchor.playing

    /** Current audio time in ms (fractional), clamped to [0, duration]. */
    fun timeAt(nowMs: Long): Double {
        val a = anchor
        var t = if (a.playing) a.posMs + (nowMs - a.atMs) else a.posMs
        if (t < 0.0) t = 0.0
        if (a.durationMs > 0 && t > a.durationMs) t = a.durationMs.toDouble()
        return t
    }

    companion object { const val RESYNC_MS = 80L }
}
