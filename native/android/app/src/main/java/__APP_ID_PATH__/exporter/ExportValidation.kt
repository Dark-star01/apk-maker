package __APP_ID__.exporter

/** What the validator read back from the finished file (via MediaExtractor on Android; any source in tests). */
internal class OutputInfo(
    val fileSize: Long,
    val hasVideo: Boolean, val videoW: Int, val videoH: Int, val videoDurationUs: Long,
    val hasAudio: Boolean, val audioDurationUs: Long, val audioSampleRate: Int,
    val readable: Boolean,
)

/** What the export was supposed to produce. */
internal class Expected(val width: Int, val height: Int, val videoDurationUs: Long, val audioDurationUs: Long)

/** Pure checks (the Android side only fills in [OutputInfo]). Tolerances are deliberately explicit. */
internal object ExportValidation {
    const val VIDEO_TOLERANCE_US = 250_000L   // container/encoder rounding on the video track
    const val AUDIO_TOLERANCE_US = 500_000L   // AAC priming / padding
    const val AV_TOLERANCE_US = 600_000L      // video vs audio track length

    /** Empty list = valid. Each entry is a short, human-readable reason. */
    fun problems(o: OutputInfo, e: Expected): List<String> {
        val p = ArrayList<String>()
        if (!o.readable) { p.add("the file cannot be opened again"); return p }
        val minSize = Math.max(8_000L, e.audioDurationUs / 1_000_000L * 2_000L)
        if (o.fileSize < minSize) p.add("file too small (${o.fileSize} bytes)")
        if (!o.hasVideo) p.add("no video track") else {
            if (o.videoW != e.width || o.videoH != e.height) p.add("video size ${o.videoW}x${o.videoH} instead of ${e.width}x${e.height}")
            if (Math.abs(o.videoDurationUs - e.videoDurationUs) > VIDEO_TOLERANCE_US) p.add("video duration ${o.videoDurationUs / 1000} ms instead of ${e.videoDurationUs / 1000} ms")
        }
        if (!o.hasAudio) p.add("no audio track") else {
            if (Math.abs(o.audioDurationUs - e.audioDurationUs) > AUDIO_TOLERANCE_US) p.add("audio duration ${o.audioDurationUs / 1000} ms instead of ${e.audioDurationUs / 1000} ms")
            if (o.hasVideo && Math.abs(o.audioDurationUs - o.videoDurationUs) > AV_TOLERANCE_US) p.add("audio and video lengths differ by ${Math.abs(o.audioDurationUs - o.videoDurationUs) / 1000} ms")
        }
        return p
    }
}

/** One compressed sample moving from a source (temp audio file) to the muxer. */
internal class SampleMeta(var size: Int, var ptsUs: Long, var flags: Int)

internal interface AudioSampleSource {
    /** Presentation time of the next sample, or -1 when the source is exhausted. */
    fun nextTimeUs(): Long
    /** Reads the next sample into [buf] (cleared by the caller); returns its meta, or null at the end. */
    fun read(buf: java.nio.ByteBuffer): SampleMeta?
}

internal interface SampleSink { fun write(buf: java.nio.ByteBuffer, meta: SampleMeta) }

/**
 * Feeds the already-encoded audio into the muxer in step with the video: all samples up to [leadUs] ahead of the
 * newest video frame time, in order, each exactly once. Bounded memory (one reusable buffer).
 */
internal class AudioInterleaver(private val source: AudioSampleSource, private val sink: SampleSink, bufferSize: Int, private val leadUs: Long = 500_000L) {
    private val buf = java.nio.ByteBuffer.allocate(bufferSize)
    var written = 0; private set
    var lastPtsUs = -1L; private set

    fun pumpUntil(videoPtsUs: Long) {
        while (true) {
            val t = source.nextTimeUs()
            if (t < 0 || t > videoPtsUs + leadUs) return
            writeOne()
        }
    }

    fun flush() { while (source.nextTimeUs() >= 0) writeOne() }

    private fun writeOne() {
        buf.clear()
        val m = source.read(buf) ?: return
        if (m.ptsUs < lastPtsUs) m.ptsUs = lastPtsUs // never let the muxer see time go backwards
        buf.position(0); buf.limit(m.size)
        sink.write(buf, m)
        lastPtsUs = m.ptsUs; written++
    }
}
