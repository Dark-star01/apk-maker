package __APP_ID__.media

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.util.Locale

/**
 * Validates an imported audio file with Android's own parsers (no libraries) and reads
 * its basic facts. Nothing is decoded here, so memory use is tiny.
 */
internal object AudioInspector {
    private val ALLOWED_EXT = setOf("mp3", "wav", "m4a", "aac")

    class Info(
        val ext: String,
        val mime: String,
        val durationMs: Long,
        val sampleRate: Int,
        val channels: Int,
    )

    fun inspect(file: File, displayName: String): Info {
        val nameExt = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)

        var retrieverDurationMs: Long? = null
        var containerMime: String? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            retrieverDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            containerMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        } catch (e: Exception) {
            // Not fatal on its own: MediaExtractor below is the real validation.
        } finally {
            retriever.release()
        }

        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(file.path)
            } catch (e: Exception) {
                throw MediaException(MediaException.BAD_AUDIO, "Cannot parse the audio file", e)
            }

            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME)
                if (m != null && m.startsWith("audio/")) {
                    format = f
                    break
                }
            }
            if (format == null) throw MediaException(MediaException.BAD_AUDIO, "No audio track")

            val ext = when {
                nameExt in ALLOWED_EXT -> nameExt
                else -> extFromContainer(containerMime)
                    ?: throw MediaException(MediaException.UNSUPPORTED_AUDIO, "Unsupported audio format")
            }

            var durationMs = retrieverDurationMs ?: 0L
            if (durationMs <= 0L && format.containsKey(MediaFormat.KEY_DURATION)) {
                durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000L
            }
            if (durationMs <= 0L) throw MediaException(MediaException.BAD_AUDIO, "Cannot read the duration")

            return Info(
                ext = ext,
                mime = containerMime ?: format.getString(MediaFormat.KEY_MIME) ?: "",
                durationMs = durationMs,
                sampleRate = intOr(format, MediaFormat.KEY_SAMPLE_RATE, 0),
                channels = intOr(format, MediaFormat.KEY_CHANNEL_COUNT, 0),
            )
        } finally {
            extractor.release()
        }
    }

    private fun intOr(format: MediaFormat, key: String, fallback: Int): Int =
        if (format.containsKey(key)) format.getInteger(key) else fallback

    private fun extFromContainer(mime: String?): String? = when (mime?.lowercase(Locale.ROOT)) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
        "audio/x-wav", "audio/wav", "audio/wave", "audio/vnd.wave" -> "wav"
        "audio/aac", "audio/aac-adts", "audio/x-aac" -> "aac"
        else -> null
    }
}
