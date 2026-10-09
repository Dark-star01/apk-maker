package __APP_ID__.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import __APP_ID__.analysis.WaveData
import __APP_ID__.effects.EffectEngine
import __APP_ID__.effects.EffectSettings
import __APP_ID__.effects.EffectState

/**
 * THE renderer: draws one complete frame for a given timestamp onto any Canvas of any size.
 *
 *   renderFrame(canvas, w, h, timeMs)  =  EffectEngine.getEffectState(timeMs)  ->  placement / opacity / glow  ->  draw
 *
 * It knows nothing about where the canvas comes from: Preview hands it the TextureView surface's canvas; Export
 * (a later phase) will hand it the MediaCodec input surface's canvas, at the export resolution. It never decodes
 * anything, never reads files and never allocates bitmaps per frame. Setters may be called from any thread
 * (they take the same lock as [renderFrame]); the bitmap is recycled only under that lock, so a frame in flight is never
 * drawn with a recycled bitmap.
 */
internal class Renderer {
    enum class Background { NONE, LOADING, READY, MISSING, UNREADABLE, TOO_LARGE }

    private val lock = Any()
    private var engine = EffectEngine(EffectSettings(), null, 0L)
    private var bitmap: Bitmap? = null
    @Volatile var background = Background.NONE
        private set
    @Volatile var imageW = 0
        private set
    @Volatile var imageH = 0
        private set
    @Volatile var lastState: EffectState? = null
        private set
    @Volatile var hasWave = false
        private set

    // Waveform overlay (Phase 6.5): drawn after the picture, in untransformed frame coordinates.
    private var waveSettings = WaveSettings(enabled = false)
    private var waveData: WaveData? = null
    private var adaptiveColor = -1
    private val overlay = WaveOverlay()
    @Volatile var waveStatus = "off"
        private set
    val waveError: String? get() = overlay.lastError

    private val composer = FrameComposer()
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowFilters: Array<ColorMatrixColorFilter> = Array(FrameComposer.GLOW_LEVELS + 1) { level ->
        val g = level.toFloat() / FrameComposer.GLOW_LEVELS
        val s = 1f + 0.10f * g     // contrast/brightness lift ...
        val o = 14f * g            // ... plus a small offset: a cheap "bloom" without a blur pass
        ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            s, 0f, 0f, 0f, o,
            0f, s, 0f, 0f, o,
            0f, 0f, s, 0f, o,
            0f, 0f, 0f, 1f, 0f,
        )))
    }

    init {
        textPaint.color = Color.argb(200, 255, 255, 255)
        textPaint.textAlign = Paint.Align.CENTER
    }

    /** New effect settings and/or wave data (cheap: the engine is rebuilt only here, never per frame). */
    fun setEffects(settings: EffectSettings, wave: WaveData?, durationMs: Long) {
        val e = EffectEngine(settings, wave, durationMs)
        synchronized(lock) { engine = e; hasWave = e.hasWave; waveData = wave; updateWaveStatus() }
    }

    /** Overlay settings (cheap; the geometry is rebuilt lazily, only when the frame or these settings change). */
    fun setWaveSettings(s: WaveSettings) {
        synchronized(lock) { waveSettings = s; updateWaveStatus() }
    }

    @Volatile var waveStyle = "-"
        private set

    private fun updateWaveStatus() {
        waveStyle = waveSettings.style.wire
        waveStatus = when {
            !waveSettings.enabled -> "off"
            waveData == null -> "no data"
            else -> "on"
        }
    }

    /** Takes ownership of [bmp] (recycled when replaced or released). Null + a status explains why there is no picture. */
    fun setBackground(bmp: Bitmap?, status: Background) {
        val old: Bitmap?
        val tint = if (bmp != null && !bmp.isRecycled) averageTint(bmp) else -1
        synchronized(lock) {
            adaptiveColor = tint
            old = bitmap
            bitmap = bmp
            background = status
            imageW = bmp?.width ?: 0
            imageH = bmp?.height ?: 0
        }
        if (old != null && old !== bmp) old.recycle()
    }

    /** Draws the frame at [timeMs] (fractional ms are fine). Returns the EffectState that was applied. */
    fun renderFrame(canvas: Canvas, w: Int, h: Int, timeMs: Double): EffectState {
        synchronized(lock) {
            canvas.drawColor(Color.BLACK)
            val bmp = bitmap?.takeIf { !it.isRecycled }
            val p = composer.compose(engine, timeMs, w, h, bmp?.width ?: 0, bmp?.height ?: 0)
            if (bmp != null && p.hasImage) {
                matrix.setValues(p.matrix)
                paint.alpha = p.alpha
                paint.colorFilter = if (p.glowLevel > 0) glowFilters[p.glowLevel] else null
                canvas.drawBitmap(bmp, matrix, paint)
            } else {
                drawMessage(canvas, w, h)
            }
            overlay.draw(canvas, w, h, timeMs, waveData, waveSettings, adaptiveColor)
            lastState = p.state
            return p.state
        }
    }

    /** Accent colour from a coarse 12x12 sample of the picture (once per picture, never per frame). */
    private fun averageTint(bmp: Bitmap): Int {
        return try {
            var r = 0L; var g = 0L; var b = 0L; var k = 0
            for (iy in 0 until 12) for (ix in 0 until 12) {
                val px = bmp.getPixel((bmp.width - 1) * ix / 11, (bmp.height - 1) * iy / 11)
                r += (px shr 16) and 0xFF; g += (px shr 8) and 0xFF; b += px and 0xFF; k++
            }
            AdaptiveColor.fromAverage((r / k).toInt(), (g / k).toInt(), (b / k).toInt())
        } catch (e: Throwable) { -1 }
    }

    private fun drawMessage(canvas: Canvas, w: Int, h: Int) {
        val text = when (background) {
            Background.LOADING -> "Loading picture..."
            Background.MISSING -> "Background image is missing"
            Background.UNREADABLE -> "Background image cannot be read"
            Background.TOO_LARGE -> "Background image is too large for this device"
            else -> "No background image"
        }
        textPaint.textSize = Math.max(12f, Math.min(w, h) * 0.07f)
        canvas.drawText(text, w / 2f, h / 2f, textPaint)
    }

    /** Frees the bitmap. The renderer can be reused after setBackground(). */
    fun release() {
        setBackground(null, Background.NONE)
    }
}
