package __APP_ID__.scene

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Draws [TextLayer]s. Layout (words, lines, sizes) is cached per layer and frame size, so a frame only does animation
 * maths and draw calls. Every word is drawn on its own (see [TextLayoutEngine]); outline and shadow are extra passes.
 * Fonts are the system's (no bundled fonts => no licence questions; Arabic comes from the platform fonts).
 * Called under the Renderer's lock (render thread only).
 */
internal class TextOverlay {
    private class Cached(val layer: TextLayer, val w: Int, val h: Int, val block: TextBlock, val textSize: Float, val lineH: Float,
                         val left: Float, val top: Float, val ascent: Float)

    private val cache = HashMap<String, Cached>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = TextFrame()
    @Volatile var lastError: String? = null; private set

    fun reset() { cache.clear() }

    private fun typeface(l: TextLayer): Typeface {
        val family = when (l.font) {
            TextFont.SANS -> "sans-serif"; TextFont.SERIF -> "serif"; TextFont.MONO -> "monospace"
            TextFont.CONDENSED -> "sans-serif-condensed"; TextFont.LIGHT -> "sans-serif-light"
            TextFont.MEDIUM -> "sans-serif-medium"; TextFont.CURSIVE -> "cursive"; TextFont.CASUAL -> "casual"
        }
        val style = (if (l.bold) Typeface.BOLD else 0) or (if (l.italic) Typeface.ITALIC else 0)
        return Typeface.create(family, style)
    }

    private fun build(l: TextLayer, w: Int, h: Int): Cached {
        val minDim = Math.min(w, h).toFloat()
        val size = Math.max(8f, (l.size * minDim).toFloat())
        paint.typeface = typeface(l); paint.textSize = size; paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.LEFT
        val space = paint.measureText(" ").let { if (it > 0f) it else size * 0.3f }
        val block = TextLayoutEngine.layout(l.text, (l.maxWidth * w).toFloat(), l.align, space) { s -> paint.measureText(s) }
        val fm = paint.fontMetrics
        val lineH = size * l.lineSpacing.toFloat()
        val margin = minDim * 0.03f
        val blockH = block.lineCount * lineH
        val cx = (l.x * w).toFloat(); val cy = (l.y * h).toFloat()
        var left = cx - block.width / 2f; var top = cy - blockH / 2f
        left = if (block.width <= w - 2 * margin) left.coerceIn(margin, w - margin - block.width) else (w - block.width) / 2f
        top = if (blockH <= h - 2 * margin) top.coerceIn(margin, h - margin - blockH) else (h - blockH) / 2f
        return Cached(l, w, h, block, size, lineH, left, top, fm.ascent)
    }

    fun draw(canvas: Canvas, w: Int, h: Int, timeMs: Double, durationMs: Long, l: TextLayer) {
        try {
            if (!l.visible || l.text.isBlank() || w < 8 || h < 8) return
            var c = cache[l.id]
            if (c == null || c.layer !== l || c.w != w || c.h != h) { c = build(l, w, h); cache[l.id] = c }
            val b = c.block
            if (b.words.isEmpty()) return
            val units = if (l.reveal == TextReveal.WORDS) b.words.size else if (l.reveal == TextReveal.CHARS) b.totalChars else 0
            val f = TextAnimator.frame(l, timeMs, durationMs, units, frame)
            if (!f.visible || f.alpha <= 0.003) return
            paint.typeface = typeface(l); paint.textSize = c.textSize; paint.textAlign = Paint.Align.LEFT
            val px = (l.x * w).toFloat(); val py = (l.y * h).toFloat()
            canvas.save()
            try {
                canvas.translate(0f, (f.offsetY * c.textSize).toFloat())
                if (f.scale != 1.0) canvas.scale(f.scale.toFloat(), f.scale.toFloat(), px, py)
                if (l.box) {
                    val pad = c.textSize * 0.4f
                    paint.style = Paint.Style.FILL
                    paint.color = withAlpha(l.boxColor, f.alpha * l.boxOpacity)
                    canvas.drawRoundRect(c.left - pad, c.top - pad * 0.6f, c.left + b.width + pad, c.top + b.lineCount * c.lineH + pad * 0.6f, pad, pad, paint)
                }
                for (word in b.words) {
                    var alpha = f.alpha
                    var s = word.text
                    var dx = 0f
                    when (l.reveal) {
                        TextReveal.WORDS -> alpha *= TextAnimator.unitAlpha(f.revealed, word.index)
                        TextReveal.CHARS -> {
                            val n = Math.min(word.text.length.toDouble(), Math.max(0.0, f.revealed - word.charStart)).toInt()
                            if (n <= 0) continue
                            if (n < word.text.length) {
                                s = word.text.substring(0, n)
                                if (b.rtl) dx = word.w - paint.measureText(s)
                            }
                        }
                        TextReveal.LINE -> {}
                    }
                    if (alpha <= 0.003) continue
                    val x = c.left + word.x + dx
                    val baseline = c.top + word.line * c.lineH + (c.lineH - c.textSize) / 2f - c.ascent - c.textSize * 0.2f
                    if (l.shadow) {
                        paint.style = Paint.Style.FILL; paint.color = withAlpha(0xFF000000.toInt(), alpha * 0.55)
                        canvas.drawText(s, x + c.textSize * 0.04f, baseline + c.textSize * 0.05f, paint)
                    }
                    if (l.outline) {
                        paint.style = Paint.Style.STROKE; paint.strokeWidth = c.textSize * 0.12f; paint.strokeJoin = Paint.Join.ROUND
                        paint.color = withAlpha(l.outlineColor, alpha)
                        canvas.drawText(s, x, baseline, paint)
                    }
                    paint.style = Paint.Style.FILL; paint.color = withAlpha(l.color, alpha)
                    canvas.drawText(s, x, baseline, paint)
                }
            } finally { canvas.restore() }
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName
        }
    }

    private fun withAlpha(c: Int, a: Double): Int {
        val al = Math.round(((c ushr 24) / 255.0) * a.coerceIn(0.0, 1.0) * 255.0).toInt().coerceIn(0, 255)
        return (al shl 24) or (c and 0x00FFFFFF)
    }
}
