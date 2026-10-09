package __APP_ID__.render

import __APP_ID__.analysis.WaveData

/**
 * Pure geometry of the waveform overlay (no Android classes: unit-testable on the JVM and shared by Preview and Export).
 *
 *   layout(w, h, settings)        sizes of everything, only when the frame or the settings change
 *   update(data, timeMs, ...)     fills [values] and [coords] for one instant, allocation-free
 *
 * The picture is a window of the cached analysis around the playhead ([WINDOW_S] seconds, playhead in the middle), so it
 * is a pure function of (data, time, settings, frame size). Outside the data (before 0 / after the end) the value is 0:
 * nothing is invented. All coordinates are in frame pixels and always inside 0..w x 0..h.
 *
 * Output: LINE -> [coords] = x,y pairs (n points); BARS / MIRRORED -> [coords] = left,top,right,bottom (n rects).
 */
internal class WaveGeometry {
    var width = 0; private set
    var height = 0; private set
    var style = WaveStyle.MIRRORED; private set
    var n = 0; private set
    var stroke = 0f; private set
    var barW = 0f; private set
    var bandLeft = 0f; private set
    var bandRight = 0f; private set
    var bandTop = 0f; private set
    var bandBottom = 0f; private set
    var layoutBuilds = 0; private set
    var values = FloatArray(0); private set
    var coords = FloatArray(0); private set
    private var sig: String? = null

    /** Returns true if the layout was rebuilt (false = identical inputs, nothing done). */
    fun layout(w: Int, h: Int, s: WaveSettings): Boolean {
        val key = "$w|$h|${s.signature()}"
        if (key == sig) return false
        sig = key
        layoutBuilds++
        width = Math.max(0, w); height = Math.max(0, h); style = s.style
        if (width < 2 || height < 2) { n = 0; return true }
        val fw = width.toFloat(); val fh = height.toFloat()
        val minDim = Math.min(fw, fh)
        stroke = Math.max(2f, minDim * 0.008f)
        val margin = minDim * 0.05f
        bandLeft = margin; bandRight = fw - margin
        var bandH = Math.min(fh * 0.9f, Math.max((s.height * fh).toFloat(), stroke * 4f))
        val m = Math.min(margin, (fh - bandH) / 2f)
        val lo = bandH / 2f + m; val hi = fh - bandH / 2f - m
        val cy = when (s.position) {
            WavePosition.TOP -> lo
            WavePosition.BOTTOM -> hi
            WavePosition.CENTER -> fh / 2f
            WavePosition.CUSTOM -> (s.customY * fh).toFloat()
        }.coerceIn(lo, Math.max(lo, hi))
        bandTop = cy - bandH / 2f; bandBottom = cy + bandH / 2f
        val span = bandRight - bandLeft
        if (span < 8f) { n = 0; return true }
        if (style == WaveStyle.LINE) {
            n = ((span / Math.max(3f, minDim * 0.012f)).toInt()).coerceIn(24, 200)
            barW = 0f
            coords = FloatArray(n * 2)
        } else {
            n = ((span / (minDim * 0.035f)).toInt()).coerceIn(16, 64)
            val pitch = span / n
            barW = Math.max(1f, pitch * 0.62f)
            coords = FloatArray(n * 4)
        }
        values = FloatArray(n)
        return true
    }

    /** Fills values/coords for [timeMs]. Without data (null / empty) or when not reactive, every value is 0. */
    fun update(data: WaveData?, timeMs: Double, s: WaveSettings) {
        if (n == 0) return
        val reactive = s.reactive && data != null && data.count > 0 && data.pointsPerSec > 0 &&
            data.planar.size >= (s.source.series + 1) * data.count
        val tSec = if (timeMs.isNaN() || timeMs.isInfinite()) 0.0 else timeMs / 1000.0
        for (i in 0 until n) {
            val u = if (style == WaveStyle.LINE) i.toDouble() / (n - 1) else (i + 0.5) / n
            values[i] = if (reactive) sample(data!!, s.source.series, tSec + (u - 0.5) * WINDOW_S) else 0f
        }
        buildCoords()
    }

    private fun buildCoords() {
        val bandH = bandBottom - bandTop
        when (style) {
            WaveStyle.LINE -> {
                val pad = stroke / 2f
                val x0 = bandLeft + pad; val x1 = bandRight - pad
                val yBase = bandBottom - pad; val yRange = bandH - 2 * pad
                for (i in 0 until n) {
                    coords[i * 2] = x0 + (x1 - x0) * i / (n - 1)
                    coords[i * 2 + 1] = yBase - values[i] * yRange
                }
            }
            WaveStyle.BARS -> {
                val pitch = (bandRight - bandLeft) / n
                for (i in 0 until n) {
                    val l = bandLeft + i * pitch + (pitch - barW) / 2f
                    val len = Math.min(bandH, Math.max(barW, values[i] * bandH))
                    put(i, l, bandBottom - len, l + barW, bandBottom)
                }
            }
            WaveStyle.MIRRORED -> {
                val pitch = (bandRight - bandLeft) / n
                val cy = (bandTop + bandBottom) / 2f
                for (i in 0 until n) {
                    val l = bandLeft + i * pitch + (pitch - barW) / 2f
                    val half = Math.min(bandH / 2f, Math.max(barW / 2f, values[i] * bandH / 2f))
                    put(i, l, cy - half, l + barW, cy + half)
                }
            }
        }
    }

    private fun put(i: Int, l: Float, t: Float, r: Float, b: Float) {
        coords[i * 4] = l.coerceIn(0f, width.toFloat()); coords[i * 4 + 1] = t.coerceIn(0f, height.toFloat())
        coords[i * 4 + 2] = r.coerceIn(0f, width.toFloat()); coords[i * 4 + 3] = b.coerceIn(0f, height.toFloat())
    }

    companion object {
        const val WINDOW_S = 2.4

        /** Linear interpolation of one series at [tSec]; 0 outside the analysed range. */
        fun sample(d: WaveData, series: Int, tSec: Double): Float {
            val idx = tSec * d.pointsPerSec
            if (idx < 0.0 || idx > d.count - 1) return 0f
            val i0 = idx.toInt()
            val i1 = Math.min(i0 + 1, d.count - 1)
            val f = (idx - i0).toFloat()
            val off = series * d.count
            val a = (d.planar[off + i0].toInt() and 0xFF) / 255f
            val b = (d.planar[off + i1].toInt() and 0xFF) / 255f
            return a + (b - a) * f
        }
    }
}
