package __APP_ID__.scene

/** Per-frame animation values of one text layer. Reused between frames by the renderer (no per-frame allocation). */
internal class TextFrame {
    var visible = false
    /** 0..1 multiplier for the whole block. */
    var alpha = 0.0
    /** Vertical offset in units of the font size (positive = down). */
    var offsetY = 0.0
    var scale = 1.0
    /** Units (words or chars) revealed so far, fractional; Double.MAX_VALUE = everything. */
    var revealed = Double.MAX_VALUE
}

/** Pure timing maths for text layers: (layer, time) -> [TextFrame]. Deterministic: the same inputs give the same frame. */
internal object TextAnimator {
    const val WORD_MS = 180.0
    const val CHAR_MS = 45.0
    const val SLIDE = 0.8

    /** The time a text layer really ends: its endMs, or the song's end when endMs is 0 (0 if both unknown). */
    fun effectiveEnd(l: TextLayer, durationMs: Long): Long = if (l.endMs > 0L) l.endMs else durationMs

    /** @param units number of words or characters for [TextLayer.reveal] (ignored for LINE) */
    fun frame(l: TextLayer, tMs: Double, durationMs: Long, units: Int, out: TextFrame): TextFrame {
        val end = effectiveEnd(l, durationMs)
        out.visible = false; out.alpha = 0.0; out.offsetY = 0.0; out.scale = 1.0; out.revealed = Double.MAX_VALUE
        if (!l.visible || l.text.isBlank() || tMs < l.startMs || (end > 0L && tMs >= end)) return out
        out.visible = true
        val local = tMs - l.startMs
        // In/out animations never take more than half of the layer's life each, so a short line is still readable in the middle
        // (a 0.2 s line with a 0.4 s fade-in and a 0.3 s fade-out would otherwise never be fully visible).
        val span = if (end > 0L) (end - l.startMs).toDouble() else Double.MAX_VALUE
        val inMs = Math.min(l.inMs.toDouble(), span / 2.0)
        val outMs = Math.min(l.outMs.toDouble(), span / 2.0)
        val inP = if (inMs > 0.0) l.easing.apply(local / inMs) else 1.0
        val raw = if (inMs > 0.0) (local / inMs).coerceIn(0.0, 1.0) else 1.0
        var alpha = 1.0; var dy = 0.0; var scale = 1.0
        val perUnitReveal = l.reveal != TextReveal.LINE && units > 0
        when (l.animIn) {
            TextAnim.NONE -> {}
            TextAnim.FADE -> alpha = if (perUnitReveal) 1.0 else inP
            TextAnim.SLIDE_UP -> { dy = (1.0 - inP) * SLIDE; alpha = if (perUnitReveal) 1.0 else inP }
            TextAnim.SLIDE_DOWN -> { dy = -(1.0 - inP) * SLIDE; alpha = if (perUnitReveal) 1.0 else inP }
            TextAnim.POP -> { scale = 0.6 + 0.4 * Easing.BACK_OUT.apply(raw); alpha = if (perUnitReveal) 1.0 else Math.min(1.0, raw * 2.0) }
        }
        if (end > 0L && outMs > 0.0 && tMs > end - outMs) {
            val o = l.easing.apply((tMs - (end - outMs)) / outMs)
            when (l.animOut) {
                TextAnim.NONE -> {}
                TextAnim.FADE -> alpha *= 1.0 - o
                TextAnim.SLIDE_UP -> { dy -= o * SLIDE; alpha *= 1.0 - o }
                TextAnim.SLIDE_DOWN -> { dy += o * SLIDE; alpha *= 1.0 - o }
                TextAnim.POP -> { scale *= 1.0 - 0.3 * o; alpha *= 1.0 - o }
            }
        }
        if (perUnitReveal) {
            val per = if (l.reveal == TextReveal.WORDS) WORD_MS else CHAR_MS
            var total = if (l.revealMs > 0L) l.revealMs.toDouble() else Math.max(Math.max(l.inMs.toDouble(), 300.0), units * per)
            if (l.revealMs <= 0L && end > 0L) total = Math.min(total, Math.max(200.0, (end - l.startMs) * 0.6))
            out.revealed = units * (local / total).coerceIn(0.0, 1.0)
        }
        out.alpha = (alpha * l.opacity).coerceIn(0.0, 1.0)
        out.offsetY = dy
        out.scale = scale
        return out
    }

    /** Alpha of unit [k] (0-based) while [revealed] units are shown: units fade in one after another. */
    fun unitAlpha(revealed: Double, k: Int): Double = (revealed - k).coerceIn(0.0, 1.0)

    /** Layers of "fade with the song" kinds (waves): 1 inside [start,end), a short fade at both ends, 0 outside. */
    fun fadeAlpha(l: Layer, tMs: Double, durationMs: Long, fadeMs: Double = 250.0): Double {
        if (!l.visible || tMs < l.startMs) return 0.0
        val end = if (l.endMs > 0L) l.endMs else durationMs
        if (end > 0L && tMs >= end) return 0.0
        val a = if (l.startMs > 0L) ((tMs - l.startMs) / fadeMs).coerceIn(0.0, 1.0) else 1.0
        val b = if (end > 0L && l.endMs > 0L) ((end - tMs) / fadeMs).coerceIn(0.0, 1.0) else 1.0
        return Math.min(a, b)
    }
}
