package __APP_ID__.exporter

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer

/** Re-opens the finished MP4 with a fresh MediaExtractor and reports what it really contains (judged by [ExportValidation]). */
internal object ExportValidator {
    fun read(file: File): OutputInfo {
        val size = if (file.isFile) file.length() else 0L
        if (size <= 0L) return OutputInfo(size, false, 0, 0, 0, false, 0, 0, false)
        val ex = MediaExtractor()
        try {
            try { ex.setDataSource(file.path) } catch (e: Exception) { return OutputInfo(size, false, 0, 0, 0, false, 0, 0, false) }
            var hasV = false; var w = 0; var h = 0; var vd = 0L
            var hasA = false; var ad = 0L; var rate = 0
            var readable = true
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                val dur = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
                if (mime.startsWith("video/") && !hasV) {
                    hasV = true
                    w = if (f.containsKey(MediaFormat.KEY_WIDTH)) f.getInteger(MediaFormat.KEY_WIDTH) else 0
                    h = if (f.containsKey(MediaFormat.KEY_HEIGHT)) f.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    vd = dur
                } else if (mime.startsWith("audio/") && !hasA) {
                    hasA = true; ad = dur
                    rate = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                }
                // the first sample of every track must be readable
                ex.selectTrack(i)
                val buf = ByteBuffer.allocate(1 shl 20)
                if (ex.readSampleData(buf, 0) <= 0) readable = false
                ex.unselectTrack(i)
            }
            return OutputInfo(size, hasV, w, h, vd, hasA, ad, rate, readable)
        } catch (e: Exception) {
            return OutputInfo(size, false, 0, 0, 0, false, 0, 0, false)
        } finally {
            ex.release()
        }
    }
}
