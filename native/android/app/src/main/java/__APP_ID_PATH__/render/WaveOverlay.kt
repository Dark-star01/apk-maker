package __APP_ID__.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import __APP_ID__.analysis.WaveData

/**
 * Draws the waveform over the already-drawn background, in plain frame coordinates (never transformed like the picture).
 * Geometry comes from [WaveGeometry]; this class only owns the Paint/Path objects and issues draw calls. No blur or bloom:
 * legibility comes from a thin dark outline pass under the coloured pass. Called under the Renderer's lock.
 */
internal class WaveOverlay {
    private val geo = WaveGeometry()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    @Volatile var lastError: String? = null; private set
    val layoutBuilds: Int get() = geo.layoutBuilds

    init {
        outline.color = 0x73000000
        fill.strokeJoin = Paint.Join.ROUND; fill.strokeCap = Paint.Cap.ROUND
        outline.strokeJoin = Paint.Join.ROUND; outline.strokeCap = Paint.Cap.ROUND
    }

    fun draw(canvas: Canvas, w: Int, h: Int, timeMs: Double, data: WaveData?, s: WaveSettings, adaptiveColor: Int) {
        if (!s.enabled || data == null) return // nothing to show: draw nothing, invent nothing
        try {
            geo.layout(w, h, s)
            if (geo.n == 0) return
            geo.update(data, timeMs, s)
            fill.color = if (s.adaptive) adaptiveColor else s.color
            val c = geo.coords
            val edge = Math.max(1f, geo.stroke * 0.35f)
            if (geo.style == WaveStyle.LINE) {
                path.rewind()
                path.moveTo(c[0], c[1])
                for (i in 1 until geo.n) path.lineTo(c[i * 2], c[i * 2 + 1])
                outline.style = Paint.Style.STROKE; outline.strokeWidth = geo.stroke + edge * 2
                canvas.drawPath(path, outline)
                fill.style = Paint.Style.STROKE; fill.strokeWidth = geo.stroke
                canvas.drawPath(path, fill)
            } else {
                val r = geo.barW / 2f
                outline.style = Paint.Style.FILL; fill.style = Paint.Style.FILL
                for (i in 0 until geo.n) canvas.drawRoundRect(c[i * 4] - edge, c[i * 4 + 1] - edge, c[i * 4 + 2] + edge, c[i * 4 + 3] + edge, r + edge, r + edge, outline)
                for (i in 0 until geo.n) canvas.drawRoundRect(c[i * 4], c[i * 4 + 1], c[i * 4 + 2], c[i * 4 + 3], r, r, fill)
            }
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName // a broken overlay must never take the frame down
        }
    }
}
