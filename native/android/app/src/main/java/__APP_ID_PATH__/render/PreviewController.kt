package __APP_ID__.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import __APP_ID__.analysis.WaveData
import __APP_ID__.effects.EffectSettings
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.zip.CRC32

/** What the preview needs to know about the project (everything else it reads from the audio clock). */
internal class PreviewConfig(
    val settings: EffectSettings,
    val wave: WaveData?,
    val durationMs: Long,
    val background: File?,
    val waveSettings: WaveSettings = WaveSettings(enabled = false),
)

/**
 * Native Preview: a TextureView laid over the web page's #preview rectangle, drawn by [Renderer] on the render thread
 * ([RenderLoop]), timed by the AudioEngine's [PlaybackClock].
 *
 * Why TextureView: it is an ordinary view (z-order, visibility and clipping behave, unlike SurfaceView over a WebView),
 * and the Renderer only needs a Surface to draw on, the same thing a MediaCodec input surface will be in Export.
 * No frames or bitmaps ever cross the bridge.
 *
 * Threads: public methods may be called from any thread; view work is posted to the main thread, decoding runs on [worker],
 * drawing on "mvm-render".
 */
internal class PreviewController(
    private val context: Context,
    private val clock: PlaybackClock,
    private val worker: ExecutorService,
) {
    private val main = Handler(Looper.getMainLooper())
    private val renderer = Renderer()
    private val loop = RenderLoop({ clock.timeAt(SystemClock.elapsedRealtime()) }, maxFps = 60, onEvent = { Log.i(TAG, it) })

    // main thread only
    private var view: TextureView? = null
    private var parent: ViewGroup? = null
    private var surface: Surface? = null
    private var visible = true
    private var activityPaused = false
    private var released = false

    // read by the render thread
    @Volatile private var surfaceW = 0
    @Volatile private var surfaceH = 0
    @Volatile private var surfaceState = "DETACHED"
    @Volatile private var viewW = 0   // requested size in pixels (known before the surface exists)
    @Volatile private var viewH = 0

    // project / picture bookkeeping (any thread)
    private val bgLock = Any()
    private var bgKey: String? = null        // identity of the decoded picture (path|size|mtime|decoded-for)
    private var bgDecodedPx = 0              // frame long side the picture was decoded for
    private var loadToken = 0
    private var lastConfig: PreviewConfig? = null
    @Volatile private var lastError: String? = null

    private val output = object : RenderLoop.Output {
        override fun draw(timeMs: Double) {
            val s = surface ?: return
            val w = surfaceW; val h = surfaceH
            if (w <= 0 || h <= 0 || !s.isValid) return
            val canvas: Canvas = if (Build.VERSION.SDK_INT >= 26) s.lockHardwareCanvas() else s.lockCanvas(null)
            try {
                renderer.renderFrame(canvas, w, h, timeMs)
            } finally {
                s.unlockCanvasAndPost(canvas)
            }
        }
    }

    // ── View lifecycle (main thread) ────────────────────────────────────────

    /** Adds the TextureView above [webView] at the given rectangle in PIXELS (relative to the webView's parent). */
    fun attach(webView: View, x: Float, y: Float, w: Int, h: Int, show: Boolean) = onMain {
        if (released) return@onMain
        val p = webView.parent as? ViewGroup ?: return@onMain
        if (view == null) {
            val tv = TextureView(context)
            tv.isOpaque = true
            tv.isClickable = false     // touches pass through to the page
            tv.isFocusable = false
            tv.surfaceTextureListener = listener
            p.addView(tv, ViewGroup.LayoutParams(Math.max(1, w), Math.max(1, h)))
            view = tv
            parent = p
            surfaceState = "WAITING"
        }
        place(x, y, w, h, show)
    }

    fun move(x: Float, y: Float, w: Int, h: Int, show: Boolean) = onMain {
        if (view != null) place(x, y, w, h, show)
    }

    private fun place(x: Float, y: Float, w: Int, h: Int, show: Boolean) {
        val tv = view ?: return
        val lp = tv.layoutParams
        val ww = Math.max(1, w); val hh = Math.max(1, h)
        if (lp.width != ww || lp.height != hh) {
            lp.width = ww; lp.height = hh
            tv.layoutParams = lp
        }
        tv.x = x
        tv.y = y
        visible = show
        viewW = ww; viewH = hh
        // GONE destroys the surface (the lifecycle callbacks below stop and later restart the loop); that is intended
        // while a dialog covers the preview.
        tv.visibility = if (show && !activityPaused) View.VISIBLE else View.GONE
        reloadIfTooSmall(Math.max(ww, hh))
    }

    fun detach() = onMain {
        loop.setOutput(null)
        releaseSurface()
        val tv = view
        if (tv != null) {
            tv.surfaceTextureListener = null
            try { parent?.removeView(tv) } catch (e: Exception) { /* already gone */ }
        }
        view = null
        parent = null
        surfaceState = "DETACHED"
    }

    private fun releaseSurface() {
        try { surface?.release() } catch (e: Exception) { /* ignore */ }
        surface = null
        surfaceW = 0; surfaceH = 0
    }

    private val listener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
            releaseSurface()
            surface = Surface(st)
            surfaceW = width; surfaceH = height
            surfaceState = "ATTACHED"
            loop.setOutput(output)           // starts the render thread, draws the current time
            loop.setPlaying(clock.isPlaying())
            if (activityPaused) loop.pause()
            lastConfig?.let { reloadIfTooSmall(Math.max(width, height)) }
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
            surfaceW = width; surfaceH = height
            loop.requestFrame()
            reloadIfTooSmall(Math.max(width, height))
        }

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            loop.setOutput(null)             // returns after any draw in progress: the surface is not used afterwards
            releaseSurface()
            surfaceState = "DESTROYED"
            return true                      // the system may free the SurfaceTexture
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    // ── Project / picture ───────────────────────────────────────────────────

    fun setProject(cfg: PreviewConfig) {
        lastConfig = cfg
        renderer.setEffects(cfg.settings, cfg.wave, cfg.durationMs)
        renderer.setWaveSettings(cfg.waveSettings)
        loadBackground(cfg.background, Math.max(viewW, viewH))
        loop.requestFrame()
    }

    private fun reloadIfTooSmall(px: Int) {
        val cfg = lastConfig ?: return
        if (px > bgDecodedPx * 5 / 4) loadBackground(cfg.background, px)
    }

    /** Decodes the picture on the worker thread, only when it (or the size it is needed at) really changed. */
    private fun loadBackground(file: File?, framePx: Int) {
        val key = if (file == null) null else file.path + "|" + file.length() + "|" + file.lastModified()
        val px = if (framePx > 0) framePx else DEFAULT_PX
        val token: Int
        synchronized(bgLock) {
            if (key == bgKey && !(key != null && px > bgDecodedPx * 5 / 4)) return // same picture, already big enough
            bgKey = key
            bgDecodedPx = px
            token = ++loadToken // supersedes any decode in flight
        }
        if (file == null) {
            renderer.setBackground(null, Renderer.Background.NONE)
            loop.requestFrame()
            return
        }
        if (renderer.background == Renderer.Background.NONE) renderer.setBackground(null, Renderer.Background.LOADING)
        worker.execute {
            val w = if (viewW > 0) viewW else px
            val h = if (viewH > 0) viewH else px
            val r = BackgroundLoader.load(file, w, h)
            synchronized(bgLock) {
                if (token != loadToken || released) { r.bitmap?.recycle(); return@execute }
            }
            val status = when (r.errorCode) {
                null -> Renderer.Background.READY
                BackgroundLoader.ERR_MISSING -> Renderer.Background.MISSING
                BackgroundLoader.ERR_MEMORY -> Renderer.Background.TOO_LARGE
                else -> Renderer.Background.UNREADABLE
            }
            if (r.errorCode != null) { lastError = r.errorCode; Log.w(TAG, "background: ${r.errorCode}") }
            renderer.setBackground(r.bitmap, status)
            loop.requestFrame()
        }
    }

    // ── Playback + activity lifecycle ───────────────────────────────────────

    /** AudioEngine state changed (play/pause/seek/end/...). The clock was already updated. */
    @Volatile private var wasPlaying = false

    fun onPlayback(playing: Boolean) {
        if (playing && wasPlaying) return // the 4/s progress ticks: the loop is already drawing, an extra frame would only add jitter
        wasPlaying = playing
        loop.setPlaying(playing)
        loop.requestFrame()             // paused/seek/end/start: draw the new state right away
    }

    fun onActivityPause() = onMain {
        activityPaused = true
        loop.pause()                          // thread sleeps; the surface may be destroyed by the system
        view?.visibility = View.GONE
    }

    fun onActivityResume() = onMain {
        activityPaused = false
        view?.visibility = if (visible) View.VISIBLE else View.GONE
        loop.resume()                         // continues from the current audio time
    }

    fun release() = onMain {
        released = true
        synchronized(bgLock) { loadToken++ }
        loop.release()
        releaseSurface()
        val tv = view
        if (tv != null) {
            tv.surfaceTextureListener = null
            try { parent?.removeView(tv) } catch (e: Exception) { /* ignore */ }
        }
        view = null
        parent = null
        renderer.release()
        surfaceState = "RELEASED"
    }

    // ── Diagnostics / self test ─────────────────────────────────────────────

    class Diag(
        val renderer: String, val surface: String, val frame: String, val timeMs: Double,
        val fps: Int, val targetFps: Int, val frames: Long, val drawMs: Double,
        val imageW: Int, val imageH: Int, val background: String, val reactive: Boolean, val error: String?,
        val waveStatus: String, val waveStyle: String, val waveError: String?,
    )

    fun diag(): Diag {
        val st = loop.state
        val err = loop.lastError ?: lastError
        val frame = when {
            st == RenderLoop.State.FAILED -> "FAILED"
            loop.framesDrawn == 0L -> "NONE YET"
            else -> "OK"
        }
        return Diag(
            renderer = if (st == RenderLoop.State.FAILED) "FAILED" else if (st == RenderLoop.State.RELEASED || surfaceState == "RELEASED") "RELEASED" else "READY",
            surface = surfaceState, frame = frame, timeMs = loop.lastTimeMs, fps = loop.measuredFps, targetFps = loop.fps,
            frames = loop.framesDrawn, drawMs = loop.lastDrawMs, imageW = renderer.imageW, imageH = renderer.imageH,
            background = renderer.background.name, reactive = renderer.hasWave, error = err,
            waveStatus = renderer.waveStatus, waveStyle = renderer.waveStyle, waveError = renderer.waveError,
        )
    }

    class SelfTest(val identical: Boolean, val crcA: Long, val crcB: Long, val crcOther: Long, val changesWithTime: Boolean,
                   val scale: Double, val rotationDeg: Double, val w: Int, val h: Int)

    /**
     * "Same time -> same frame": renders [timeMs], then [otherMs], then [timeMs] again with the real Renderer into an
     * offscreen software bitmap and compares the pixels (CRC32). Does not touch the live surface.
     */
    fun selfTest(timeMs: Double, otherMs: Double, aspect: Double): SelfTest {
        val w = 320
        val h = Math.max(2, Math.round(w / (if (aspect > 0) aspect else 16.0 / 9)).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bmp)
            val px = IntArray(w * h)
            fun shot(t: Double): Long {
                renderer.renderFrame(c, w, h, t)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                val crc = CRC32()
                val bytes = ByteArray(px.size * 4)
                for (i in px.indices) { val v = px[i]; bytes[i * 4] = (v ushr 24).toByte(); bytes[i * 4 + 1] = (v ushr 16).toByte(); bytes[i * 4 + 2] = (v ushr 8).toByte(); bytes[i * 4 + 3] = v.toByte() }
                crc.update(bytes)
                return crc.value
            }
            val a = shot(timeMs); val o = shot(otherMs); val b = shot(timeMs)
            val st = renderer.lastState
            return SelfTest(a == b, a, b, o, o != a, st?.scale ?: 1.0, st?.rotationDeg ?: 0.0, w, h)
        } finally {
            bmp.recycle()
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        private const val TAG = "MvmPreview"
        private const val DEFAULT_PX = 1080
    }
}
