package __APP_ID__.render

internal enum class WaveStyle(val wire: String) {
    LINE("line"), BARS("bars"), MIRRORED("mirrored");
    companion object { fun fromWire(s: String?): WaveStyle = values().firstOrNull { it.wire == s } ?: MIRRORED }
}

internal enum class WavePosition(val wire: String) {
    TOP("top"), CENTER("center"), BOTTOM("bottom"), CUSTOM("custom");
    companion object { fun fromWire(s: String?): WavePosition = values().firstOrNull { it.wire == s } ?: BOTTOM }
}

/** Which analysed series drives the overlay; [series] is the index inside WaveData.planar (amplitude|bass|mid|treble). */
internal enum class WaveSource(val wire: String, val series: Int) {
    VOLUME("volume", 0), BASS("bass", 1), MID("mid", 2), TREBLE("treble", 3);
    companion object { fun fromWire(s: String?): WaveSource = values().firstOrNull { it.wire == s } ?: VOLUME }
}

/**
 * The project's `wave` settings as the renderer needs them (names follow the web schema: style, colorMode, color,
 * position, customY, reactive, reactTo, height). Immutable and always sanitized.
 * @property height band height as a fraction of the FRAME height, 0.05..0.5
 * @property customY vertical centre of the band when position == CUSTOM, 0 = top .. 1 = bottom
 * @property color ARGB; used when [adaptive] is false
 */
internal class WaveSettings(
    val enabled: Boolean = true,
    val style: WaveStyle = WaveStyle.MIRRORED,
    val adaptive: Boolean = false,
    val color: Int = -1,
    val position: WavePosition = WavePosition.BOTTOM,
    customY: Double = 0.8,
    val reactive: Boolean = true,
    val source: WaveSource = WaveSource.VOLUME,
    height: Double = 0.18,
) {
    val customY: Double = clean(customY, 0.0, 1.0, 0.8)
    val height: Double = clean(height, 0.05, 0.5, 0.18)

    /** Same text = same geometry. Decides whether the layout has to be rebuilt. */
    fun signature(): String = "$enabled|${style.wire}|${position.wire}|$customY|$reactive|${source.wire}|$height"

    companion object {
        private fun clean(v: Double, lo: Double, hi: Double, fb: Double): Double =
            if (v.isNaN() || v.isInfinite()) fb else Math.min(hi, Math.max(lo, v))

        /** "#rrggbb" / "#aarrggbb" -> ARGB (opaque for 6 digits). Anything else -> white. */
        fun parseColor(s: String?): Int {
            if (s == null) return -1
            val t = s.trim().removePrefix("#")
            val v = t.toLongOrNull(16) ?: return -1
            return when (t.length) {
                6 -> (0xFF000000L or v).toInt()
                8 -> v.toInt()
                else -> -1
            }
        }
    }
}

/** Picks a bright accent from the picture's average colour (used when colorMode == adaptive). Pure maths, no Android. */
internal object AdaptiveColor {
    fun fromAverage(r: Int, g: Int, b: Int): Int {
        val rf = r.coerceIn(0, 255) / 255f; val gf = g.coerceIn(0, 255) / 255f; val bf = b.coerceIn(0, 255) / 255f
        val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf); val d = max - min
        if (max <= 0f || d / max < 0.12f) return -1 // grey picture: white is the most legible
        val hue = when (max) {
            rf -> (((gf - bf) / d) % 6f + 6f) % 6f
            gf -> (bf - rf) / d + 2f
            else -> (rf - gf) / d + 4f
        }
        val s = 0.45f; val v = 1f
        val c = v * s; val x = c * (1f - Math.abs(hue % 2f - 1f)); val m = v - c
        val (r1, g1, b1) = when (hue.toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = Math.round((f + m) * 255f).coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }
}
