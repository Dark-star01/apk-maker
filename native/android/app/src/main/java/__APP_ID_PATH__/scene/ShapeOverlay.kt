package __APP_ID__.scene

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import __APP_ID__.analysis.WaveData

/**
 * Draws one NEW-shape waveform layer from [ShapeGeometry]. Android drawing only; no per-frame allocation (Paint/Path/shader
 * are reused, the shader is rebuilt only when its inputs change). Glow = two extra wider, fainter passes under the
 * core pass (no BlurMaskFilter: not reliable on hardware canvases). Called under the Renderer's lock.
 */
internal class ShapeOverlay {
    private val geo = ShapeGeometry()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var shader: Shader? = null
    private var shaderKey = ""
    @Volatile var lastError: String? = null; private set

    init { paint.strokeJoin = Paint.Join.ROUND; paint.strokeCap = Paint.Cap.ROUND }

    fun draw(canvas: Canvas, w: Int, h: Int, timeMs: Double, data: WaveData?, l: WaveLayer, fade: Double, adaptive: Int) {
        try {
            val a = (fade * l.opacity).coerceIn(0.0, 1.0)
            if (a <= 0.004) return
            geo.layout(w, h, l)
            if (geo.count == 0 && geo.polyCount == 0) return
            geo.update(data, timeMs, l)
            val c1 = if (l.adaptive) adaptive else l.color
            val c2 = if (l.gradient) l.color2 else c1
            val passes = if (l.glow > 0.02) 3 else 1
            for (p in 0 until passes) { // p = 0: widest faint glow ... last: core
                val core = p == passes - 1
                val widthMul = if (!core) (if (p == 0) 4.0f else 2.2f) else 1f
                val alphaMul = if (!core) (if (p == 0) 0.10 else 0.22) * l.glow else 1.0
                val pa = a * alphaMul
                if (pa <= 0.003) continue
                when (geo.kind) {
                    PrimKind.RECTS -> drawRects(canvas, l, c1, c2, pa, widthMul, core)
                    PrimKind.LINES -> drawLines(canvas, c1, c2, pa, widthMul)
                    PrimKind.CIRCLES -> drawCircles(canvas, c1, c2, pa, if (core) 1f else widthMul * 0.5f + 0.6f)
                    PrimKind.POLYS -> drawPolys(canvas, c1, c2, pa, widthMul)
                }
            }
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName // a broken layer must never take the frame down
        }
    }

    private fun argb(c1: Int, c2: Int, t: Float, alpha: Double): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int { val x = (c1 ushr shift) and 0xFF; val y = (c2 ushr shift) and 0xFF; return Math.round(x + (y - x) * k) }
        val al = Math.round(255.0 * alpha.coerceIn(0.0, 1.0)).toInt()
        return (al shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun drawRects(canvas: Canvas, l: WaveLayer, c1: Int, c2: Int, pa: Double, widthMul: Float, core: Boolean) {
        val r = geo.rects
        val outline = l.shape == WaveShape.NEON_BARS
        paint.shader = null
        if (outline) { paint.style = Paint.Style.STROKE; paint.strokeWidth = geo.stroke * widthMul } else paint.style = Paint.Style.FILL
        val grow = if (outline || core) 0f else geo.stroke * (widthMul - 1f) * 0.5f
        for (i in 0 until geo.count) {
            val reflect = if (i >= geo.mirrorFrom) 0.35 else 1.0
            paint.color = argb(c1, c2, geo.colorT[i], pa * reflect)
            val left = r[i * 4] - grow; val top = r[i * 4 + 1] - grow; val right = r[i * 4 + 2] + grow; val bottom = r[i * 4 + 3] + grow
            val rad = Math.max(0.5f, (right - left) / 2f)
            canvas.drawRoundRect(left, top, right, bottom, rad, rad, paint)
        }
    }

    private fun drawLines(canvas: Canvas, c1: Int, c2: Int, pa: Double, widthMul: Float) {
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = geo.stroke * 1.4f * widthMul
        val r = geo.rects
        for (i in 0 until geo.count) {
            paint.color = argb(c1, c2, geo.colorT[i], pa)
            canvas.drawLine(r[i * 4], r[i * 4 + 1], r[i * 4 + 2], r[i * 4 + 3], paint)
        }
    }

    private fun drawCircles(canvas: Canvas, c1: Int, c2: Int, pa: Double, radiusMul: Float) {
        paint.shader = null; paint.style = Paint.Style.FILL
        val r = geo.rects
        for (i in 0 until geo.count) {
            paint.color = argb(c1, c2, geo.colorT[i], pa)
            canvas.drawCircle(r[i * 3], r[i * 3 + 1], r[i * 3 + 2] * radiusMul, paint)
        }
    }

    private fun drawPolys(canvas: Canvas, c1: Int, c2: Int, pa: Double, widthMul: Float) {
        paint.style = Paint.Style.STROKE
        val gradient = c1 != c2
        if (gradient) {
            val key = "$c1|$c2|${geo.polys[0]}|${geo.polys[(geo.polyPts - 1) * 2]}"
            if (key != shaderKey || shader == null) {
                val x0 = geo.polys[0]; val x1 = geo.polys[(geo.polyPts - 1) * 2]
                shader = LinearGradient(x0, 0f, if (x1 > x0 + 1f) x1 else x0 + 1f, 0f, c1 or (0xFF shl 24), c2 or (0xFF shl 24), Shader.TileMode.CLAMP)
                shaderKey = key
            }
            paint.shader = shader
        } else { paint.shader = null; paint.color = c1 }
        for (k in 0 until geo.polyCount) {
            path.rewind()
            val base = k * geo.polyPts * 2
            path.moveTo(geo.polys[base], geo.polys[base + 1])
            for (i in 1 until geo.polyPts) path.lineTo(geo.polys[base + i * 2], geo.polys[base + i * 2 + 1])
            if (geo.closed) path.close()
            paint.strokeWidth = geo.stroke * geo.polyWidth[k] * widthMul * 1.2f
            paint.alpha = Math.round(255.0 * (pa * geo.polyAlpha[k]).coerceIn(0.0, 1.0)).toInt()
            canvas.drawPath(path, paint)
        }
        paint.shader = null
    }
}
