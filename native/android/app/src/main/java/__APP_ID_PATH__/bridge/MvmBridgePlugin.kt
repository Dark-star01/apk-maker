package __APP_ID__.bridge

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Base64
import android.util.Log
import androidx.activity.result.ActivityResult
import __APP_ID__.analysis.AudioAnalyzer
import __APP_ID__.analysis.WaveCache
import __APP_ID__.analysis.WaveData
import __APP_ID__.audio.AudioEngine
import __APP_ID__.effects.EffectEngine
import __APP_ID__.effects.EffectSettings
import __APP_ID__.effects.MotionMode
import __APP_ID__.effects.Preset
import __APP_ID__.media.MediaException
import __APP_ID__.media.MediaManager
import __APP_ID__.render.PreviewConfig
import __APP_ID__.render.PreviewController
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The single Web <-> Kotlin entry point. JavaScript calls these methods through
 * js/core/bridge.js; it never knows about the engines behind them.
 * Later phases add methods here that delegate to AudioEngine, TimelineEngine,
 * ExportRenderer, etc. (each in its own package).
 *
 * @PluginMethod calls arrive on Capacitor's background thread. Activity results arrive on
 * the UI thread, so anything heavy there is handed to [io].
 */
@CapacitorPlugin(name = "MvmBridge")
class MvmBridgePlugin : Plugin() {

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val analysisIo: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "mvm-analysis") }
    private val analysisGen = AtomicInteger(0) // bumping it cancels every running/queued analysis
    private val media by lazy { MediaManager(context) }
    private val engine by lazy {
        AudioEngine(context) { snap ->
            notifyListeners("playback", snapshotJs(snap))
            previewRef?.onPlayback(snap.state == AudioEngine.State.PLAYING) // the native Preview follows the same state
        }
    }
    @Volatile private var previewRef: PreviewController? = null
    private val preview: PreviewController
        get() = previewRef ?: synchronized(this) { previewRef ?: PreviewController(context, engine.clock, io).also { previewRef = it } }

    // ── Diagnostics ─────────────────────────────────────────────────────────

    // JS: await Capacitor.Plugins.MvmBridge.ping({ value: "hello" })
    @PluginMethod
    fun ping(call: PluginCall) {
        val ret = JSObject()
        ret.put("ok", true)
        ret.put("echo", call.getString("value") ?: "")
        ret.put("thread", Thread.currentThread().name)
        call.resolve(ret)
    }

    // Device facts, used later to choose safe preview/export settings.
    @PluginMethod
    fun getInfo(call: PluginCall) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val ret = JSObject()
        ret.put("sdk", Build.VERSION.SDK_INT)
        ret.put("model", Build.MODEL ?: "")
        ret.put("cores", Runtime.getRuntime().availableProcessors())
        ret.put("totalMemMb", mem.totalMem / (1024L * 1024L))
        ret.put("heapLimitMb", am.memoryClass)
        ret.put("appId", context.packageName)
        call.resolve(ret)
    }

    // ── Media (Phase 2) ─────────────────────────────────────────────────────

    // JS: await ...pickMedia({ kind: "audio" | "image", projectId })
    // Resolves { cancelled: true } or { cancelled: false, kind, media: {...} }.
    @PluginMethod
    fun pickMedia(call: PluginCall) {
        val kind = call.getString("kind")
        val projectId = call.getString("projectId")
        if ((kind != KIND_AUDIO && kind != KIND_IMAGE) || projectId.isNullOrBlank()) {
            call.reject("Bad request", MediaException.BAD_REQUEST)
            return
        }

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        if (kind == KIND_AUDIO) {
            // Broad on purpose: providers label m4a/aac inconsistently. The file is validated after the copy.
            intent.type = "audio/*"
        } else {
            intent.type = "*/*"
            intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "image/webp"))
        }
        startActivityForResult(call, intent, "pickMediaResult")
    }

    @ActivityCallback
    private fun pickMediaResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return

        if (result.resultCode != Activity.RESULT_OK) {
            val cancelled = JSObject()
            cancelled.put("cancelled", true)
            call.resolve(cancelled)
            return
        }

        val uri = result.data?.data
        val kind = call.getString("kind")
        val projectId = call.getString("projectId")
        if (uri == null || projectId == null) {
            call.reject("No file was selected", MediaException.READ_FAILED)
            return
        }

        // UI thread here: copying/inspecting must not block it.
        io.execute {
            try {
                val out = JSObject()
                out.put("cancelled", false)
                out.put("kind", kind)
                if (kind == KIND_AUDIO) {
                    analysisGen.incrementAndGet() // the old audio is about to be replaced: stop analysing it
                    dropEffectWave()
                    val r = media.importAudio(uri, projectId)
                    val m = JSObject()
                    m.put("file", r.file)
                    m.put("name", r.name)
                    m.put("mime", r.mime)
                    m.put("sizeBytes", r.sizeBytes)
                    m.put("durationMs", r.durationMs)
                    m.put("sampleRate", r.sampleRate)
                    m.put("channels", r.channels)
                    m.put("rev", r.rev)
                    out.put("media", m)
                } else {
                    val r = media.importImage(uri, projectId)
                    val m = JSObject()
                    m.put("file", r.file)
                    m.put("thumb", r.thumb)
                    m.put("name", r.name)
                    m.put("mime", r.mime)
                    m.put("sizeBytes", r.sizeBytes)
                    m.put("width", r.width)
                    m.put("height", r.height)
                    out.put("media", m)
                }
                call.resolve(out)
            } catch (t: Throwable) {
                fail(call, t)
            }
        }
    }

    // JS: await ...checkMedia({ projectId, audio?: "audio.mp3", background?: "background.jpg" })
    // Resolves { audio?: boolean, background?: boolean } — false means the file is missing.
    @PluginMethod
    fun checkMedia(call: PluginCall) {
        try {
            val projectId = call.getString("projectId")
                ?: throw MediaException(MediaException.BAD_REQUEST, "projectId is required")
            val out = JSObject()
            val audio = call.getString("audio")
            val background = call.getString("background")
            if (audio != null) out.put("audio", media.exists(projectId, audio))
            if (background != null) out.put("background", media.exists(projectId, background))
            call.resolve(out)
        } catch (t: Throwable) {
            fail(call, t)
        }
    }

    // JS: await ...getThumbnail({ projectId, file }) -> { dataUrl?: string }
    @PluginMethod
    fun getThumbnail(call: PluginCall) {
        try {
            val projectId = call.getString("projectId")
                ?: throw MediaException(MediaException.BAD_REQUEST, "projectId is required")
            val file = call.getString("file")
                ?: throw MediaException(MediaException.BAD_REQUEST, "file is required")
            val out = JSObject()
            val url = media.thumbnailDataUrl(projectId, file)
            if (url != null) out.put("dataUrl", url)
            call.resolve(out)
        } catch (t: Throwable) {
            fail(call, t)
        }
    }

    // JS: await ...removeMedia({ projectId, kind: "audio" | "image" }) — deletes that media's files.
    @PluginMethod
    fun removeMedia(call: PluginCall) {
        try {
            val projectId = call.getString("projectId")
                ?: throw MediaException(MediaException.BAD_REQUEST, "projectId is required")
            val kind = call.getString("kind")
            if (kind != KIND_AUDIO && kind != KIND_IMAGE) throw MediaException(MediaException.BAD_REQUEST, "bad kind")
            if (kind == KIND_AUDIO) { analysisGen.incrementAndGet(); dropEffectWave() }
            media.removeMedia(projectId, kind)
            call.resolve()
        } catch (t: Throwable) {
            fail(call, t)
        }
    }

    // JS: await ...deleteProject({ projectId }) — removes the project's files from disk.
    @PluginMethod
    fun deleteProject(call: PluginCall) {
        try {
            val projectId = call.getString("projectId")
                ?: throw MediaException(MediaException.BAD_REQUEST, "projectId is required")
            dropEffectWave()
            media.deleteProject(projectId)
            call.resolve()
        } catch (t: Throwable) {
            fail(call, t)
        }
    }

    // ── Playback (Phase 3) ──────────────────────────────────────────────────
    // All commands run on the main thread inside AudioEngine; every result is the same
    // snapshot shape { state, positionMs, durationMs, error? }. While playing, the same
    // snapshot is pushed to JS as the "playback" event (4 per second).

    // JS: await ...loadAudio({ projectId, file })
    @PluginMethod
    fun loadAudio(call: PluginCall) {
        val projectId = call.getString("projectId")
        val file = call.getString("file")
        engine.runOnMain {
            try {
                if (projectId.isNullOrBlank() || file.isNullOrBlank()) {
                    throw MediaException(MediaException.BAD_REQUEST, "projectId and file are required")
                }
                val f = media.resolve(projectId, file)
                    ?: throw MediaException(MediaException.AUDIO_MISSING, "Audio file is missing")
                engine.load(f) { code ->
                    if (code == null) call.resolve(snapshotJs(engine.snapshot()))
                    else call.reject("Cannot play this audio file", code)
                }
            } catch (t: Throwable) {
                fail(call, t)
            }
        }
    }

    @PluginMethod
    fun play(call: PluginCall) = onMain(call) { engine.play() }

    @PluginMethod
    fun pause(call: PluginCall) = onMain(call) { engine.pause() }

    // JS: await ...seek({ positionMs })
    @PluginMethod
    fun seek(call: PluginCall) {
        val pos = call.getDouble("positionMs")
        onMain(call) {
            if (pos == null || pos.isNaN()) throw MediaException(MediaException.BAD_REQUEST, "positionMs is required")
            engine.seek(pos.toLong())
        }
    }

    @PluginMethod
    fun stop(call: PluginCall) = onMain(call) { engine.stop() }

    @PluginMethod
    fun getPlaybackState(call: PluginCall) = onMain(call) { }

    @PluginMethod
    fun releaseAudio(call: PluginCall) = onMain(call) { engine.release(silent = true) }

    private fun onMain(call: PluginCall, block: () -> Unit) {
        engine.runOnMain {
            try {
                block()
                call.resolve(snapshotJs(engine.snapshot()))
            } catch (t: Throwable) {
                fail(call, t)
            }
        }
    }

    private fun snapshotJs(s: AudioEngine.Snapshot): JSObject {
        val o = JSObject()
        o.put("state", s.state.wire)
        o.put("positionMs", s.positionMs)
        o.put("durationMs", s.durationMs)
        if (s.errorCode != null) o.put("error", s.errorCode)
        return o
    }

    // ── Audio analysis (Phase 4) ────────────────────────────────────────────
    // Wave data = 30 points/s of {amplitude, bass, mid, treble}, computed once by Kotlin and cached next to the
    // audio file. JS never analyses audio. See analysis/WaveData.kt for the exact meaning of the values.

    // JS: await ...analyzeAudio({ projectId, file, durationMs?, force? })
    // Resolves { cached, sampleRate, count, durationMs } once the cache file is written (or was already valid).
    // While running it emits "analysisProgress" { progress: 0..1 } (at most every 5% / 300 ms).
    @PluginMethod
    fun analyzeAudio(call: PluginCall) {
        val projectId = call.getString("projectId")
        val file = call.getString("file")
        val durationMs = call.getDouble("durationMs")?.toLong() ?: 0L
        val force = call.getBoolean("force") ?: false
        val gen = analysisGen.incrementAndGet() // supersedes any earlier analysis
        val cancelled = { analysisGen.get() != gen }

        analysisIo.execute {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) // never compete with playback / UI
                if (projectId.isNullOrBlank() || file.isNullOrBlank()) {
                    throw MediaException(MediaException.BAD_REQUEST, "projectId and file are required")
                }
                if (cancelled()) throw MediaException(MediaException.ANALYSIS_CANCELLED, "Cancelled")
                val f = media.resolve(projectId, file) ?: throw MediaException(MediaException.AUDIO_MISSING, "Audio file is missing")
                val fingerprint = WaveCache.fingerprint(f)
                val cacheFile = media.analysisFile(projectId)

                if (!force) {
                    val cached = WaveCache.read(cacheFile, fingerprint)
                    if (cached != null) {
                        call.resolve(waveSummary(cached, true))
                        return@execute
                    }
                }

                var lastP = -1.0
                var lastAt = 0L
                val data = AudioAnalyzer().analyze(f, durationMs, cancelled) { p ->
                    val now = System.currentTimeMillis()
                    if (p - lastP >= 0.05 && now - lastAt >= 300) {
                        lastP = p; lastAt = now
                        val o = JSObject()
                        o.put("progress", p)
                        notifyListeners("analysisProgress", o)
                    }
                }
                if (cancelled()) throw MediaException(MediaException.ANALYSIS_CANCELLED, "Cancelled")
                // The audio file may have been replaced while we were decoding it: never cache for a different file.
                if (WaveCache.fingerprint(f) != fingerprint) throw MediaException(MediaException.ANALYSIS_CANCELLED, "Audio changed")
                try {
                    WaveCache.write(cacheFile, media.analysisTmp(projectId), fingerprint, data)
                } catch (e: java.io.IOException) {
                    throw MediaException(MediaException.STORAGE_FAILED, "Cannot store the analysis", e)
                }
                dropEffectWave() // new analysis: the effect engine must reload it
                call.resolve(waveSummary(data, false))
            } catch (t: Throwable) {
                fail(call, t)
            }
        }
    }

    // JS: await ...getWaveData({ projectId, file })
    // Resolves { sampleRate, count, durationMs, data: <base64 of count*4 bytes: amplitude|bass|mid|treble, 0..255> },
    // or rejects NO_WAVE_DATA when there is no valid cache for the CURRENT audio file (never returns stale data).
    @PluginMethod
    fun getWaveData(call: PluginCall) {
        try {
            val projectId = call.getString("projectId")
                ?: throw MediaException(MediaException.BAD_REQUEST, "projectId is required")
            val file = call.getString("file")
                ?: throw MediaException(MediaException.BAD_REQUEST, "file is required")
            val f = media.resolve(projectId, file) ?: throw MediaException(MediaException.AUDIO_MISSING, "Audio file is missing")
            val data = WaveCache.read(media.analysisFile(projectId), WaveCache.fingerprint(f))
                ?: throw MediaException(MediaException.NO_WAVE_DATA, "No wave data for this audio")
            val out = waveSummary(data, true)
            out.put("data", Base64.encodeToString(data.planar, Base64.NO_WRAP))
            call.resolve(out)
        } catch (t: Throwable) {
            fail(call, t)
        }
    }

    @PluginMethod
    fun cancelAnalysis(call: PluginCall) {
        analysisGen.incrementAndGet()
        call.resolve()
    }

    private fun waveSummary(d: WaveData, cached: Boolean): JSObject {
        val o = JSObject()
        o.put("cached", cached)
        o.put("sampleRate", d.pointsPerSec)
        o.put("count", d.count)
        o.put("durationMs", d.durationMs)
        return o
    }

    // ── Effects (Phase 5) ───────────────────────────────────────────────────
    // The effect maths lives in effects/EffectEngine (pure Kotlin). This is only a thin door for the web UI's
    // diagnostics panel; the future native renderer calls EffectEngine directly, with no bridge in between.
    private val effectLock = Any()
    private var effWaveKey: String? = null      // projectId|file the loaded wave belongs to
    private var effWave: WaveData? = null
    private var effEngine: EffectEngine? = null
    private var effEngineSig: String? = null

    private fun dropEffectWave() = synchronized(effectLock) { effWaveKey = null; effWave = null; effEngine = null; effEngineSig = null }

    // JS: await ...getEffectState({ projectId, file?, timeMs, durationMs?, effects: {preset, motion, intensity, smoothing, seed}, aspect? })
    // Resolves the EffectState (see effects/EffectState.kt). Reads the wave cache at most once per audio file
    // (never analyses, never touches the audio file); a missing wave simply gives the neutral, non-reactive state.
    @PluginMethod
    fun getEffectState(call: PluginCall) {
        try {
            val projectId = call.getString("projectId")
                ?: throw MediaException(MediaException.BAD_REQUEST, "projectId is required")
            val timeMs = call.getDouble("timeMs") ?: 0.0
            val durationMs = call.getDouble("durationMs")?.toLong() ?: 0L
            val file = call.getString("file")
            val settings = parseEffects(call)
            val st = synchronized(effectLock) {
                val wave = waveForLocked(projectId, file)
                val sig = settings.signature() + "|" + durationMs + "|" + (wave != null)
                if (effEngine == null || effEngineSig != sig) {
                    effEngine = EffectEngine(settings, wave, durationMs)
                    effEngineSig = sig
                }
                effEngine!!.getEffectState(timeMs)
            }
            val o = JSObject()
            o.put("timeMs", st.timeMs)
            o.put("scale", st.scale)
            o.put("rotationDeg", st.rotationDeg)
            o.put("translateX", st.translateX)
            o.put("translateY", st.translateY)
            o.put("opacity", st.opacity)
            o.put("glow", st.glow)
            o.put("intensity", st.intensity)
            o.put("shake", st.shake)
            o.put("audioReactive", st.audioReactive)
            val a = JSObject()
            a.put("amplitude", st.audio.amplitude)
            a.put("bass", st.audio.bass)
            a.put("mid", st.audio.mid)
            a.put("treble", st.audio.treble)
            o.put("audio", a)
            call.resolve(o)
        } catch (t: Throwable) {
            fail(call, t)
        }
    }

    private fun parseEffects(call: PluginCall): EffectSettings {
        val fx = call.getObject("effects")
        return EffectSettings(
            Preset.fromWire(fx?.optString("preset")),
            MotionMode.fromWire(fx?.optString("motion")),
            fx?.optDouble("intensity", 1.0) ?: 1.0,
            fx?.optDouble("smoothing", 0.5) ?: 0.5,
            fx?.optInt("seed", 1) ?: 1,
            call.getDouble("aspect") ?: (16.0 / 9.0),
        )
    }

    /** The cached wave data of [file] (null if none yet). Caller holds [effectLock]. Never analyses. */
    private fun waveForLocked(projectId: String, file: String?): WaveData? {
        val key = if (file.isNullOrBlank()) null else "$projectId|$file"
        if (key != effWaveKey || (effWave == null && key != null)) {
            if (key != effWaveKey) { effEngine = null; effEngineSig = null }
            effWaveKey = key
            effWave = null
            if (key != null) {
                val f = media.resolve(projectId, file!!)
                val cache = media.analysisFile(projectId)
                // stat only until a cache exists: no audio-file I/O on every call while analysis is pending
                if (f != null && cache.isFile) effWave = WaveCache.read(cache, WaveCache.fingerprint(f))
            }
        }
        return effWave
    }

    // ── Preview renderer (Phase 6) ──────────────────────────────────────────
    // The native Renderer draws on a TextureView placed over the page's #preview rectangle. JS only says WHERE
    // (CSS pixels + devicePixelRatio) and WHICH project data; it never receives frames, and it is not the clock:
    // the Renderer follows AudioEngine's clock. All methods answer { ok: true } (or a coded error).

    // JS: await ...attachPreview({ x, y, width, height, dpr, visible })
    @PluginMethod
    fun attachPreview(call: PluginCall) = onUi(call) { wv, x, y, w, h, show -> preview.attach(wv, x, y, w, h, show) }

    // JS: await ...setPreviewRect({ x, y, width, height, dpr, visible })  (moved, resized, or hidden behind a dialog)
    @PluginMethod
    fun setPreviewRect(call: PluginCall) = onUi(call) { wv, x, y, w, h, show -> preview.move(x, y, w, h, show) }

    @PluginMethod
    fun detachPreview(call: PluginCall) {
        previewRef?.detach()
        call.resolve(okJs())
    }

    // JS: await ...setPreviewProject({ projectId, background?, audioFile?, durationMs?, effects, aspect })
    // Sends no pixels: the picture is read from the project folder, the wave from its cache. Only the picture decode is
    // repeated when the picture itself (or the size it is needed at) changes, never when effect settings change.
    @PluginMethod
    fun setPreviewProject(call: PluginCall) {
        try {
            val projectId = call.getString("projectId")
                ?: throw MediaException(MediaException.BAD_REQUEST, "projectId is required")
            val settings = parseEffects(call)
            val durationMs = call.getDouble("durationMs")?.toLong() ?: 0L
            val bgName = call.getString("background")
            val bg = if (bgName.isNullOrBlank()) null else media.resolve(projectId, bgName) ?: java.io.File(media.analysisFile(projectId).parentFile, "missing_" + bgName)
            val wave = synchronized(effectLock) { waveForLocked(projectId, call.getString("audioFile")) }
            preview.setProject(PreviewConfig(settings, wave, durationMs, bg))
            call.resolve(okJs())
        } catch (t: Throwable) {
            fail(call, t)
        }
    }

    // JS: await ...getRendererState() -> diagnostics (see PreviewController.Diag)
    @PluginMethod
    fun getRendererState(call: PluginCall) {
        val o = JSObject()
        val pv = previewRef
        if (pv == null) {
            o.put("renderer", "NONE"); o.put("surface", "DETACHED"); o.put("frame", "NONE YET")
        } else {
            val d = pv.diag()
            o.put("renderer", d.renderer); o.put("surface", d.surface); o.put("frame", d.frame)
            o.put("timeMs", d.timeMs); o.put("fps", d.fps); o.put("targetFps", d.targetFps)
            o.put("frames", d.frames); o.put("drawMs", d.drawMs)
            o.put("imageW", d.imageW); o.put("imageH", d.imageH)
            o.put("background", d.background); o.put("audioReactive", d.reactive)
            if (d.error != null) o.put("error", d.error)
        }
        call.resolve(o)
    }

    // JS: await ...rendererSelfTest({ timeMs, otherMs, aspect })
    // Renders timeMs, otherMs, timeMs again with the real Renderer into an offscreen bitmap and compares the pixels.
    @PluginMethod
    fun rendererSelfTest(call: PluginCall) {
        val t = call.getDouble("timeMs") ?: 10000.0
        val other = call.getDouble("otherMs") ?: (t + 1234.0)
        val aspect = call.getDouble("aspect") ?: (16.0 / 9.0)
        io.execute {
            try {
                val r = preview.selfTest(t, other, aspect)
                val o = JSObject()
                o.put("identical", r.identical)
                o.put("crcA", r.crcA.toString(16)); o.put("crcB", r.crcB.toString(16)); o.put("crcOther", r.crcOther.toString(16))
                o.put("changesWithTime", r.changesWithTime)
                o.put("scale", r.scale); o.put("rotationDeg", r.rotationDeg)
                o.put("width", r.w); o.put("height", r.h)
                call.resolve(o)
            } catch (e: Throwable) {
                fail(call, e)
            }
        }
    }

    private fun okJs(): JSObject { val o = JSObject(); o.put("ok", true); return o }

    // Runs a view command on the UI thread with the rectangle converted from CSS px to device px.
    private fun onUi(call: PluginCall, block: (android.webkit.WebView, Float, Float, Int, Int, Boolean) -> Unit) {
        try {
            val dpr = call.getDouble("dpr") ?: 1.0
            val x = call.getDouble("x") ?: 0.0
            val y = call.getDouble("y") ?: 0.0
            val w = call.getDouble("width") ?: 0.0
            val h = call.getDouble("height") ?: 0.0
            val show = call.getBoolean("visible") ?: true
            if (w <= 0 || h <= 0 || dpr <= 0) throw MediaException(MediaException.BAD_REQUEST, "bad preview rectangle")
            activity.runOnUiThread {
                try {
                    val wv = bridge.webView
                    block(wv, (wv.left + x * dpr).toFloat(), (wv.top + y * dpr).toFloat(), Math.round(w * dpr).toInt(), Math.round(h * dpr).toInt(), show)
                    call.resolve(okJs())
                } catch (t: Throwable) { fail(call, t) }
            }
        } catch (t: Throwable) {
            fail(call, t)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun fail(call: PluginCall, t: Throwable) {
        if (t is MediaException) {
            call.reject(t.message, t.code, t)
        } else {
            Log.e(TAG, "Unexpected bridge error", t)
            call.reject("Unexpected error: " + t.javaClass.simpleName, "UNEXPECTED")
        }
    }

    // No background playback service in the MVP: leaving the app pauses the audio.
    override fun handleOnPause() {
        engine.runOnMain { engine.pauseIfPlaying() }
        previewRef?.onActivityPause()
        super.handleOnPause()
    }

    override fun handleOnResume() {
        super.handleOnResume()
        previewRef?.onActivityResume()
    }

    override fun handleOnDestroy() {
        previewRef?.release()
        previewRef = null
        analysisGen.incrementAndGet()
        analysisIo.shutdown()
        engine.runOnMain { engine.release(silent = true) }
        io.shutdown()
        super.handleOnDestroy()
    }

    companion object {
        private const val TAG = "MvmBridge"
        private const val KIND_AUDIO = "audio"
        private const val KIND_IMAGE = "image"
    }
}
