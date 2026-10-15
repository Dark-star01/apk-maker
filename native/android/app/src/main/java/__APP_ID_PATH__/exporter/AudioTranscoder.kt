package __APP_ID__.exporter

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Source audio (mp3 / m4a / wav / ogg ... whatever the platform can decode) -> AAC-LC in a temporary .m4a.
 *
 * Streaming: extractor -> decoder -> encoder -> muxer, one buffer at a time (bounded memory; the audio is never held whole).
 * Timestamps come from the number of PCM samples handed to the encoder (sample counter / sample rate), starting at 0,
 * so there is no drift and nothing depends on a clock. The source file is only read.
 * The result's duration is the REAL decoded length, which is what the video length is derived from.
 */
internal class AudioTranscoder(private val job: ExportJob) {
    class Result(val durationUs: Long, val sampleRate: Int, val channels: Int)

    fun transcode(src: File, dest: File, expectedUs: Long): Result {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            try { extractor.setDataSource(src.path) } catch (e: Exception) { throw ExportException(ExportException.AUDIO_FAILED, "Cannot open the audio file", e) }
            var track = -1
            var inFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; inFormat = f; break }
            }
            if (track < 0 || inFormat == null) throw ExportException(ExportException.AUDIO_UNSUPPORTED, "No audio track")
            extractor.selectTrack(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: throw ExportException(ExportException.AUDIO_UNSUPPORTED, "Unknown audio type")
            val dec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) { throw ExportException(ExportException.AUDIO_UNSUPPORTED, "No decoder for $mime", e) }
            decoder = dec
            try { dec.configure(inFormat, null, null, 0); dec.start() } catch (e: Exception) { throw ExportException(ExportException.AUDIO_UNSUPPORTED, "Cannot start the audio decoder", e) }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var rate = 0
            var channels = 0
            var samplesFed = 0L          // per channel
            var lastProgress = System.nanoTime()
            var muxTrack = -1

            fun initEncoder(outFormat: MediaFormat) {
                rate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (outFormat.containsKey("pcm-encoding") && outFormat.getInteger("pcm-encoding") != 2) throw ExportException(ExportException.AUDIO_UNSUPPORTED, "Only 16-bit PCM decoder output is supported")
                if (channels < 1 || channels > 2) throw ExportException(ExportException.AUDIO_UNSUPPORTED, "$channels audio channels are not supported (mono or stereo only)")
                if (!ExportPlan.isSupportedSampleRate(rate)) throw ExportException(ExportException.AUDIO_UNSUPPORTED, "Audio sample rate $rate Hz is not supported by the AAC encoder")
                val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels)
                f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                f.setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 96_000 else ExportPlan.AUDIO_BITRATE)
                f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                val enc = try { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC) } catch (e: Exception) { throw ExportException(ExportException.AUDIO_UNSUPPORTED, "No AAC encoder", e) }
                encoder = enc
                try { enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); enc.start() } catch (e: Exception) { throw ExportException(ExportException.AUDIO_UNSUPPORTED, "Cannot start the AAC encoder", e) }
                muxer = try { MediaMuxer(dest.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) } catch (e: Exception) { throw ExportException(ExportException.MUXER_FAILED, "Cannot create the audio file", e) }
            }

            // Pulls finished AAC out of the encoder (never blocks long). Returns true when something moved.
            fun drainEncoder(): Boolean {
                val enc = encoder ?: return false
                var moved = false
                while (true) {
                    val idx = enc.dequeueOutputBuffer(info, 0)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return moved
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxTrack < 0) {
                                try { muxTrack = muxer!!.addTrack(enc.outputFormat); muxer!!.start(); muxerStarted = true } catch (e: Exception) { throw ExportException(ExportException.MUXER_FAILED, "Cannot start the audio muxer", e) }
                            }
                            moved = true
                        }
                        idx >= 0 -> {
                            val buf = enc.getOutputBuffer(idx)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                            if (info.size > 0 && buf != null && muxerStarted) {
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                try { muxer!!.writeSampleData(muxTrack, buf, info) } catch (e: Exception) { throw ExportException(ExportException.MUXER_FAILED, "Cannot write audio", e) }
                            }
                            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            enc.releaseOutputBuffer(idx, false)
                            moved = true
                            if (eos) { encoderDone = true; return true }
                        }
                    }
                }
            }

            while (!encoderDone) {
                job.checkpoint()
                var moved = false
                // 1) source -> decoder
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(0)
                    if (i >= 0) {
                        val b = dec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(b, 0)
                        if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { dec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                        moved = true
                    }
                }
                // 2) decoder -> encoder
                if (!decoderDone) {
                    val o = dec.dequeueOutputBuffer(info, 0)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (encoder == null) initEncoder(dec.outputFormat)
                        moved = true
                    } else if (o >= 0) {
                        if (encoder == null) initEncoder(dec.outputFormat)
                        val enc = encoder!!
                        val pcm = dec.getOutputBuffer(o)!!
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        var off = info.offset
                        var remaining = info.size
                        val bytesPerFrame = 2 * channels
                        while (remaining > 0) {
                            job.checkpoint()
                            val ei = enc.dequeueInputBuffer(10_000)
                            if (ei < 0) { drainEncoder(); continue }
                            val eb = enc.getInputBuffer(ei)!!
                            eb.clear()
                            val n = Math.min(remaining, eb.capacity()) / bytesPerFrame * bytesPerFrame
                            if (n <= 0) { enc.queueInputBuffer(ei, 0, 0, ExportPlan.audioPtsUs(samplesFed, rate), 0); break }
                            pcm.position(off); pcm.limit(off + n)
                            eb.put(pcm)
                            enc.queueInputBuffer(ei, 0, n, ExportPlan.audioPtsUs(samplesFed, rate), 0)
                            samplesFed += n / bytesPerFrame
                            off += n; remaining -= n
                        }
                        dec.releaseOutputBuffer(o, false)
                        moved = true
                        if (eos) {
                            decoderDone = true
                            val enc2 = encoder ?: throw ExportException(ExportException.AUDIO_FAILED, "The audio file produced no sound")
                            while (true) {
                                job.checkpoint()
                                val ei = enc2.dequeueInputBuffer(10_000)
                                if (ei >= 0) { enc2.queueInputBuffer(ei, 0, 0, ExportPlan.audioPtsUs(samplesFed, rate), MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
                                drainEncoder()
                            }
                        }
                    }
                }
                // 3) encoder -> muxer
                if (drainEncoder()) moved = true

                if (rate > 0) job.progress(ExportPlan.Stage.ENCODING_AUDIO, if (expectedUs > 0) ExportPlan.audioPtsUs(samplesFed, rate).toDouble() / expectedUs else 0.0)
                if (moved) lastProgress = System.nanoTime()
                else if (System.nanoTime() - lastProgress > STALL_NS) throw ExportException(ExportException.AUDIO_FAILED, "Audio conversion stalled")
                else try { Thread.sleep(2) } catch (e: InterruptedException) { }
            }
            if (samplesFed <= 0L) throw ExportException(ExportException.AUDIO_FAILED, "The audio file produced no sound")
            return Result(ExportPlan.audioPtsUs(samplesFed, rate), rate, channels)
        } catch (e: ExportException) {
            throw e
        } catch (e: ExportCancelled) {
            throw e
        } catch (e: Exception) {
            throw ExportException(ExportException.AUDIO_FAILED, "Audio conversion failed: " + e.javaClass.simpleName, e)
        } finally {
            try { decoder?.stop() } catch (e: Exception) { }
            try { decoder?.release() } catch (e: Exception) { }
            try { encoder?.stop() } catch (e: Exception) { }
            try { encoder?.release() } catch (e: Exception) { }
            try { if (muxerStarted) muxer?.stop() } catch (e: Exception) { }
            try { muxer?.release() } catch (e: Exception) { }
            try { extractor.release() } catch (e: Exception) { }
        }
    }

    companion object { private const val STALL_NS = 15_000_000_000L }
}
