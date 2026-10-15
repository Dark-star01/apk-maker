package __APP_ID__.exporter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import __APP_ID__.analysis.WaveData
import __APP_ID__.effects.EffectSettings
import __APP_ID__.render.BackgroundLoader
import __APP_ID__.render.Renderer
import __APP_ID__.render.WaveSettings
import java.io.File
import java.nio.ByteBuffer

/** Everything one export needs, resolved by the bridge (names -> files, cached wave data) before the worker starts. */
internal class ExportRequest(
    val title: String?,
    val background: File?,
    val audio: File?,
    val wave: WaveData?,
    val settings: EffectSettings,
    val waveSettings: WaveSettings,
    val aspect: String,
    val resolution: String,
    val fps: Int,
    val audioDurationMsHint: Long,
    val outDir: File,
)

/** Thrown when an encoder candidate cannot be configured/started (before any frame is submitted): the next candidate is tried. */
private class CandidateFailed(cause: Throwable) : Exception(cause)

/**
 * The export pipeline. Runs on ONE worker thread (the caller's), never touches the UI thread.
 *
 *   1. audio  : source -> decoder -> AAC encoder -> temporary .m4a (real duration known)
 *   2. video  : for frame i = 0..N-1: shared Renderer.renderFrame(canvas, w, h, i/fps) into a Bitmap -> EGL texture ->
 *               encoder input Surface stamped i/fps -> H.264 -> MediaMuxer; the AAC samples are interleaved by timestamp
 *   3. finish : muxer.stop, rename to a unique name, re-open with MediaExtractor and validate, only then "completed"
 * Any failure or cancel deletes every temporary file and any unvalidated output; sources and earlier exports are untouched.
 */
internal class ExportEngine(private val job: ExportJob) {

    fun run(req: ExportRequest) {
        val stamp = System.currentTimeMillis()
        val dir = req.outDir
        val tmpAudio = File(dir, ExportNaming.tmpAudio(stamp))
        val tmpVideo = File(dir, ExportNaming.tmpFinal(stamp))
        var finalFile: File? = null
        var renderer: Renderer? = null
        try {
            job.progress(ExportPlan.Stage.PREPARING, 0.0)
            if (!dir.isDirectory && !dir.mkdirs()) throw ExportException(ExportException.NO_SPACE, "Cannot create the export folder")
            ExportNaming.cleanTemp(dir)

            val audio = req.audio ?: throw ExportException(ExportException.NO_AUDIO, "No audio in the project")
            if (!audio.isFile) throw ExportException(ExportException.AUDIO_MISSING, "The audio file is missing")
            val bg = req.background ?: throw ExportException(ExportException.NO_BACKGROUND, "No background image in the project")
            if (!bg.isFile) throw ExportException(ExportException.BACKGROUND_MISSING, "The background image is missing")
            val fps = if (req.fps in 10..60) req.fps else ExportPlan.DEFAULT_FPS

            val want = ExportPlan.outputSize(req.aspect, req.resolution)
            val bitrate = ExportPlan.videoBitrate(want.width, want.height, fps)
            val candidates = EncoderSelector.candidates(want, fps, bitrate)
            if (candidates.isEmpty()) throw ExportException(ExportException.ENCODER_UNSUPPORTED, "This device has no H.264 encoder for ${want.width}x${want.height} at $fps fps")
            val hintUs = Math.max(1L, req.audioDurationMsHint) * 1000L
            val need = ExportPlan.requiredFreeBytes(hintUs, bitrate)
            if (dir.usableSpace < need) throw ExportException(ExportException.NO_SPACE, "Not enough storage: about ${need / (1024 * 1024)} MB needed")
            job.checkpoint()

            // the shared Renderer, with its own picture decoded for the export size (the preview's renderer is never touched)
            val loaded = BackgroundLoader.load(bg, candidates[0].width, candidates[0].height)
            if (loaded.bitmap == null) {
                throw ExportException(if (loaded.errorCode == BackgroundLoader.ERR_MISSING) ExportException.BACKGROUND_MISSING else ExportException.BACKGROUND_UNREADABLE, "The background image cannot be used (${loaded.errorCode})")
            }
            val r = Renderer()
            renderer = r
            r.setEffects(req.settings, req.wave, req.audioDurationMsHint)
            r.setWaveSettings(req.waveSettings)
            r.setBackground(loaded.bitmap, Renderer.Background.READY)

            // 1) audio
            job.progress(ExportPlan.Stage.ENCODING_AUDIO, 0.0)
            val a = AudioTranscoder(job).transcode(audio, tmpAudio, hintUs)
            val frames = ExportPlan.frameCount(a.durationUs, fps)
            val videoDurUs = ExportPlan.videoDurationUs(frames, fps)
            if (dir.usableSpace < ExportPlan.requiredFreeBytes(a.durationUs, bitrate)) throw ExportException(ExportException.NO_SPACE, "Not enough storage for the video")

            // 2) video (+ interleaved audio): first candidate that really starts
            var done = false
            var lastFailure: Throwable? = null
            for (c in candidates) {
                job.checkpoint()
                job.setFormat(c.width, c.height, fps, frames)
                try {
                    encodeVideo(c, r, frames, fps, tmpAudio, tmpVideo)
                    done = true
                    break
                } catch (e: CandidateFailed) {
                    lastFailure = e.cause
                    tmpVideo.delete()
                }
            }
            if (!done) throw ExportException(ExportException.ENCODER_UNSUPPORTED, "No H.264 encoder could be started: " + (lastFailure?.javaClass?.simpleName ?: "unknown"), lastFailure)
            val chosen = job.snapshot()

            // 3) finish + validate
            job.progress(ExportPlan.Stage.FINALIZING, 0.0, frames, "Validating")
            job.checkpoint()
            val name = ExportNaming.uniqueName(dir, req.title)
            val target = File(dir, name)
            if (!tmpVideo.renameTo(target)) throw ExportException(ExportException.MUXER_FAILED, "Cannot store the finished video")
            finalFile = target
            val problems = ExportValidation.problems(ExportValidator.read(target), Expected(chosen.width, chosen.height, videoDurUs, a.durationUs))
            if (problems.isNotEmpty()) throw ExportException(ExportException.VALIDATION_FAILED, "The exported file did not pass validation: " + problems.joinToString("; "))
            job.complete(target.path, name, target.length(), videoDurUs / 1000L)
            finalFile = null // keep it
        } catch (e: ExportCancelled) {
            job.markCancelled()
        } catch (e: ExportException) {
            job.fail(e.code, e.message ?: e.code)
        } catch (e: OutOfMemoryError) {
            job.fail(ExportException.UNEXPECTED, "Out of memory")
        } catch (t: Throwable) {
            job.fail(ExportException.UNEXPECTED, "Unexpected error: " + t.javaClass.simpleName)
        } finally {
            try { renderer?.release() } catch (e: Throwable) { }
            tmpAudio.delete(); tmpVideo.delete()
            finalFile?.delete() // an output that did not pass validation is never left behind
        }
    }

    private fun encodeVideo(c: EncoderChoice, renderer: Renderer, frames: Int, fps: Int, audioFile: File, out: File) {
        var codec: MediaCodec? = null
        var egl: EglEncoderSurface? = null
        var muxer: MediaMuxer? = null
        var audioEx: MediaExtractor? = null
        var bmp: Bitmap? = null
        var started = false
        try {
            val format = MediaFormat.createVideoFormat(EncoderSelector.MIME, c.width, c.height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, c.bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            try {
                codec = MediaCodec.createByCodecName(c.name)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                egl = EglEncoderSurface(codec.createInputSurface(), c.width, c.height)
                codec.start()
                bmp = Bitmap.createBitmap(c.width, c.height, Bitmap.Config.ARGB_8888)
            } catch (e: Exception) { throw CandidateFailed(e) } catch (e: OutOfMemoryError) { throw CandidateFailed(e) }
            val frameBmp = bmp!!
            val canvas = Canvas(frameBmp)
            val cdc = codec!!
            val gl = egl!!

            // the audio samples (already AAC) come from the temporary file, in timestamp order
            val ax = MediaExtractor(); audioEx = ax
            ax.setDataSource(audioFile.path)
            var aIdx = -1; var aFormat: MediaFormat? = null
            for (i in 0 until ax.trackCount) { val f = ax.getTrackFormat(i); if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { aIdx = i; aFormat = f; break } }
            if (aIdx < 0 || aFormat == null) throw ExportException(ExportException.AUDIO_FAILED, "The converted audio is unreadable")
            ax.selectTrack(aIdx)

            val mx = try { MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) } catch (e: Exception) { throw ExportException(ExportException.MUXER_FAILED, "Cannot create the video file", e) }
            muxer = mx
            var videoTrack = -1; var audioTrack = -1
            val info = MediaCodec.BufferInfo()
            val aInfo = MediaCodec.BufferInfo()

            val source = object : AudioSampleSource {
                override fun nextTimeUs(): Long = ax.sampleTime
                override fun read(buf: ByteBuffer): SampleMeta? {
                    val n = ax.readSampleData(buf, 0)
                    if (n < 0) return null
                    val m = SampleMeta(n, ax.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    ax.advance()
                    return m
                }
            }
            val sink = object : SampleSink {
                override fun write(buf: ByteBuffer, meta: SampleMeta) {
                    aInfo.set(0, meta.size, meta.ptsUs, meta.flags)
                    try { mx.writeSampleData(audioTrack, buf, aInfo) } catch (e: Exception) { throw ExportException(ExportException.MUXER_FAILED, "Cannot write audio", e) }
                }
            }
            val inter = AudioInterleaver(source, sink, 256 * 1024)

            fun drain(eos: Boolean) {
                var waited = 0L
                while (true) {
                    val idx = cdc.dequeueOutputBuffer(info, if (eos) 10_000L else 0L)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!eos) return
                            job.checkpoint()
                            waited += 10
                            if (waited > END_WAIT_MS) throw ExportException(ExportException.VIDEO_FAILED, "The video encoder did not finish")
                        }
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (started) throw ExportException(ExportException.VIDEO_FAILED, "Video format changed twice")
                            try {
                                videoTrack = mx.addTrack(cdc.outputFormat)
                                audioTrack = mx.addTrack(aFormat!!)
                                mx.start(); started = true
                            } catch (e: Exception) { throw ExportException(ExportException.MUXER_FAILED, "Cannot start the muxer", e) }
                        }
                        idx >= 0 -> {
                            val buf = cdc.getOutputBuffer(idx)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                            if (info.size > 0 && buf != null) {
                                if (!started) throw ExportException(ExportException.VIDEO_FAILED, "Encoder produced data before its format")
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                try { mx.writeSampleData(videoTrack, buf, info) } catch (e: Exception) { throw ExportException(ExportException.MUXER_FAILED, "Cannot write video", e) }
                                inter.pumpUntil(info.presentationTimeUs)
                            }
                            val end = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            cdc.releaseOutputBuffer(idx, false)
                            if (end) return
                        }
                    }
                }
            }

            for (i in 0 until frames) {
                job.checkpoint()
                val pts = ExportPlan.ptsUs(i.toLong(), fps)
                renderer.renderFrame(canvas, c.width, c.height, ExportPlan.frameTimeMs(i.toLong(), fps))
                try {
                    gl.draw(frameBmp)
                    gl.setPresentationTimeNs(pts * 1000L)
                    gl.swap()
                } catch (e: IllegalStateException) { throw ExportException(ExportException.VIDEO_FAILED, "Video surface error: " + e.message, e) }
                drain(false)
                job.progress(ExportPlan.Stage.RENDERING, (i + 1).toDouble() / frames, i + 1)
            }
            cdc.signalEndOfInputStream()
            drain(true)
            if (!started) throw ExportException(ExportException.VIDEO_FAILED, "The encoder produced no video")
            inter.flush()
            job.progress(ExportPlan.Stage.FINALIZING, 0.0, frames, "Finalizing")
            try { mx.stop() } catch (e: Exception) { throw ExportException(ExportException.MUXER_FAILED, "Cannot finish the video file", e) }
            started = false
        } finally {
            try { egl?.release() } catch (e: Throwable) { }
            try { codec?.stop() } catch (e: Throwable) { }
            try { codec?.release() } catch (e: Throwable) { }
            try { if (started) muxer?.stop() } catch (e: Throwable) { }
            try { muxer?.release() } catch (e: Throwable) { }
            try { audioEx?.release() } catch (e: Throwable) { }
            try { bmp?.recycle() } catch (e: Throwable) { }
        }
    }

    companion object { private const val END_WAIT_MS = 30_000L }
}
