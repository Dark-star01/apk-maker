package __APP_ID__.analysis

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import __APP_ID__.media.MediaException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the project's audio STREAMING (MediaExtractor + MediaCodec, both part of Android: no dependency,
 * no permission) and feeds the PCM to [PcmAnalyzer] buffer by buffer. The song is never held in memory:
 * only one small decoder buffer plus the four floats per 1/30 s point exist at any time.
 * MediaCodec is used here ONLY as an audio decoder (WAV/PCM skips even that); it is not used for export.
 *
 * Blocking: call from a background thread. Throws [MediaException] with a stable code.
 */
internal class AudioAnalyzer {

    fun analyze(
        file: File,
        expectedDurationMs: Long,
        isCancelled: () -> Boolean,
        onProgress: (Double) -> Unit,
    ): WaveData {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(file.absolutePath)
            } catch (e: IOException) {
                throw MediaException(MediaException.ANALYSIS_UNSUPPORTED, "Cannot open the audio", e)
            } catch (e: RuntimeException) {
                throw MediaException(MediaException.ANALYSIS_UNSUPPORTED, "Cannot open the audio", e)
            }

            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) { track = i; format = f; break }
            }
            if (track < 0 || format == null) {
                throw MediaException(MediaException.ANALYSIS_UNSUPPORTED, "No audio track")
            }
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""

            val durationUs = when {
                format.containsKey(MediaFormat.KEY_DURATION) -> format.getLong(MediaFormat.KEY_DURATION)
                expectedDurationMs > 0 -> expectedDurationMs * 1000L
                else -> 0L
            }
            val progress = { timeUs: Long -> if (durationUs > 0) onProgress((timeUs.toDouble() / durationUs).coerceIn(0.0, 1.0)) }

            return if (mime == "audio/raw") {
                analyzeRaw(extractor, format, isCancelled, progress)
            } else {
                val decoder = try {
                    MediaCodec.createDecoderByType(mime).also { it.configure(format, null, null, 0); it.start() }
                } catch (e: Exception) {
                    throw MediaException(MediaException.ANALYSIS_UNSUPPORTED, "No decoder for $mime", e)
                }
                codec = decoder // released in finally
                analyzeDecoded(extractor, decoder, isCancelled, progress)
            }
        } catch (e: MediaException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw MediaException(MediaException.ANALYSIS_FAILED, "Out of memory while analysing")
        } catch (e: Exception) {
            Log.e(TAG, "analysis failed", e)
            throw MediaException(MediaException.ANALYSIS_FAILED, "Analysis failed: " + e.javaClass.simpleName, e)
        } finally {
            try { codec?.stop() } catch (e: Exception) { /* already stopped */ }
            try { codec?.release() } catch (e: Exception) { /* ignore */ }
            try { extractor.release() } catch (e: Exception) { /* ignore */ }
        }
    }

    // WAV / raw PCM: the extractor already hands out PCM, no decoder needed.
    private fun analyzeRaw(
        extractor: MediaExtractor,
        format: MediaFormat,
        isCancelled: () -> Boolean,
        progress: (Long) -> Unit,
    ): WaveData {
        val pcm = newPcm(format)
        val buf = ByteBuffer.allocate(RAW_CHUNK).order(ByteOrder.LITTLE_ENDIAN)
        var lastReport = 0L
        while (true) {
            if (isCancelled()) throw MediaException(MediaException.ANALYSIS_CANCELLED, "Cancelled")
            buf.clear()
            val n = extractor.readSampleData(buf, 0)
            if (n < 0) break
            pcm.feed(buf, 0, n)
            val t = extractor.sampleTime
            if (t - lastReport > REPORT_US) { lastReport = t; progress(t) }
            extractor.advance()
        }
        return finish(pcm)
    }

    private fun analyzeDecoded(
        extractor: MediaExtractor,
        codec: MediaCodec,
        isCancelled: () -> Boolean,
        progress: (Long) -> Unit,
    ): WaveData {
        val info = MediaCodec.BufferInfo()
        var pcm: PcmAnalyzer? = null
        var inputDone = false
        var outputDone = false
        var idle = 0
        var lastReport = 0L

        while (!outputDone) {
            if (isCancelled()) throw MediaException(MediaException.ANALYSIS_CANCELLED, "Cancelled")

            if (!inputDone) {
                val i = codec.dequeueInputBuffer(TIMEOUT_US)
                if (i >= 0) {
                    val ib = codec.getInputBuffer(i)
                    val n = if (ib != null) extractor.readSampleData(ib, 0) else -1
                    if (n < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val t = extractor.sampleTime
                        codec.queueInputBuffer(i, 0, n, t, 0)
                        if (t - lastReport > REPORT_US) { lastReport = t; progress(t) }
                        extractor.advance()
                    }
                }
            }

            val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                o >= 0 -> {
                    idle = 0
                    if (info.size > 0) {
                        val ob = codec.getOutputBuffer(o)
                        if (ob != null) {
                            if (pcm == null) pcm = newPcm(codec.outputFormat)
                            pcm.feed(ob, info.offset, info.size)
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    idle = 0
                    if (pcm != null) throw MediaException(MediaException.ANALYSIS_FAILED, "Audio format changed mid-stream")
                }
                else -> if (++idle > MAX_IDLE) throw MediaException(MediaException.ANALYSIS_FAILED, "Decoder stalled")
            }
        }
        return finish(pcm ?: throw MediaException(MediaException.ANALYSIS_FAILED, "No audio was decoded"))
    }

    private fun newPcm(format: MediaFormat): PcmAnalyzer {
        if (!format.containsKey(MediaFormat.KEY_SAMPLE_RATE) || !format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            throw MediaException(MediaException.ANALYSIS_UNSUPPORTED, "Unknown audio format")
        }
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        // KEY_PCM_ENCODING is API 24; missing means 16-bit.
        val encoding = if (format.containsKey(PCM_ENCODING_KEY)) format.getInteger(PCM_ENCODING_KEY) else PcmAnalyzer.ENC_16BIT
        return try {
            PcmAnalyzer(rate, channels, encoding)
        } catch (e: IllegalArgumentException) {
            throw MediaException(MediaException.ANALYSIS_UNSUPPORTED, e.message ?: "Unsupported audio format", e)
        }
    }

    private fun finish(pcm: PcmAnalyzer): WaveData {
        val data = pcm.finish()
        if (data.count == 0) throw MediaException(MediaException.ANALYSIS_FAILED, "The audio has no samples")
        return data
    }

    companion object {
        private const val TAG = "MvmAnalysis"
        private const val PCM_ENCODING_KEY = "pcm-encoding"
        private const val TIMEOUT_US = 10_000L
        private const val MAX_IDLE = 1500 // ~15 s without any output: give up instead of hanging
        private const val RAW_CHUNK = 256 * 1024
        private const val REPORT_US = 1_000_000L // look at progress at most once per second of audio
    }
}
