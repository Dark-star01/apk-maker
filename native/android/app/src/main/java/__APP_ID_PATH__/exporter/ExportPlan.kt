package __APP_ID__.exporter

/**
 * All the arithmetic of an export, as pure functions (no Android classes: unit-tested on the JVM).
 * Frame i is rendered at EXACTLY i / fps seconds and stamped with the same time, so the picture never depends on how
 * long rendering takes. Audio time is derived from the number of samples written, never from a wall clock.
 */
internal object ExportPlan {
    const val DEFAULT_FPS = 30
    const val AUDIO_BITRATE = 128_000

    class Dims(val width: Int, val height: Int) {
        override fun toString() = "${width}x$height"
    }

    private fun even(n: Double): Int = Math.max(2, Math.round(n / 2.0).toInt() * 2)

    /** "360p" -> 360 (the SHORT side). Unknown values fall back to 720 like the web layer. */
    fun shortSide(resolution: String?): Int {
        val v = resolution?.trim()?.removeSuffix("p")?.toIntOrNull() ?: return 720
        return if (v == 360 || v == 420 || v == 720) v else 720
    }

    /** Same mapping as js/core/schema.js outputSize(): 16:9 -> W x short, 9:16 -> short x H, 1:1 -> short x short (even sizes). */
    fun outputSize(aspect: String?, resolution: String?): Dims {
        val s = shortSide(resolution)
        return when (aspect) {
            "9:16" -> Dims(s, even(s * 16.0 / 9.0))
            "1:1" -> Dims(even(s.toDouble()), even(s.toDouble()))
            else -> Dims(even(s * 16.0 / 9.0), s)
        }
    }

    /** Rounds both sides to the nearest multiple of the encoder's alignment (never below one alignment step). Alignments < 2 count as 2. */
    fun alignTo(d: Dims, wAlign: Int, hAlign: Int): Dims {
        fun snap(v: Int, a: Int): Int { val k = Math.max(2, a); return Math.max(k, Math.round(v.toDouble() / k).toInt() * k) }
        return Dims(snap(d.width, wAlign), snap(d.height, hAlign))
    }

    /** Number of frames that cover [durationUs] (the last frame may end slightly after the audio, by less than one frame). At least 1. */
    fun frameCount(durationUs: Long, fps: Int): Int {
        if (durationUs <= 0 || fps <= 0) return 1
        val n = (durationUs * fps + 999_999L) / 1_000_000L
        return Math.max(1L, Math.min(n, Int.MAX_VALUE.toLong())).toInt()
    }

    fun ptsUs(frameIndex: Long, fps: Int): Long = frameIndex * 1_000_000L / fps
    fun frameTimeMs(frameIndex: Long, fps: Int): Double = frameIndex * 1000.0 / fps
    fun videoDurationUs(frames: Int, fps: Int): Long = ptsUs(frames.toLong(), fps)
    fun audioPtsUs(samplesPerChannel: Long, sampleRate: Int): Long = samplesPerChannel * 1_000_000L / sampleRate

    /** AAC-LC encoders accept these rates; anything else is reported instead of silently resampled. */
    fun isSupportedSampleRate(r: Int): Boolean = r in intArrayOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)

    fun videoBitrate(w: Int, h: Int, fps: Int): Int {
        val v = w.toLong() * h * fps * 7L / 100L // ~0.07 bits per pixel per frame
        return Math.max(800_000L, Math.min(6_000_000L, v)).toInt()
    }

    /** Upper estimate of the output + temporary files (video + audio temp + audio in the MP4), with a 30 % margin and a fixed reserve. */
    fun requiredFreeBytes(durationUs: Long, videoBps: Int): Long {
        val sec = durationUs / 1_000_000.0
        val out = (videoBps + AUDIO_BITRATE) / 8.0 * sec
        val tmp = AUDIO_BITRATE / 8.0 * sec
        return ((out + tmp) * 1.3).toLong() + 64L * 1024 * 1024
    }

    enum class Stage(val wire: String, val from: Int, val to: Int) {
        IDLE("idle", 0, 0),
        PREPARING("preparing", 0, 2),
        ENCODING_AUDIO("encodingAudio", 2, 10),
        RENDERING("rendering", 10, 96),
        FINALIZING("finalizing", 96, 99),
        COMPLETED("completed", 100, 100),
        FAILED("failed", 0, 0),
        CANCELLED("cancelled", 0, 0);

        val terminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
        val active: Boolean get() = this == PREPARING || this == ENCODING_AUDIO || this == RENDERING || this == FINALIZING
    }

    /** Percent of the whole job for a stage and the fraction (0..1) of that stage's real work done. Monotonic within a stage. */
    fun percent(stage: Stage, fraction: Double): Int {
        val f = if (fraction.isNaN()) 0.0 else Math.min(1.0, Math.max(0.0, fraction))
        return (stage.from + (stage.to - stage.from) * f).toInt()
    }
}
