package __APP_ID__.scene

import __APP_ID__.analysis.WaveData
import __APP_ID__.render.WaveGeometry
import __APP_ID__.render.WavePosition

/** What kind of primitives [ShapeGeometry] filled in. */
internal enum class PrimKind { RECTS, LINES, CIRCLES, POLYS }

/**
 * Pure geometry of the NEW waveform shapes (no Android classes; shared by Preview and Export; allocation-free per frame).
 *
 * Honest note on "spectrum": the analysis stores 4 series (amplitude, bass, mid, treble). Spread shapes (bars, radial,
 * dots...) map bass -> mid -> treble across their positions with a stable per-bar variation. That is a banded
 * approximation of a spectrum, NOT an FFT.
 *
 * Outputs (all in frame pixels, always inside the frame):
 *  RECTS   [rects]   l,t,r,b per item; [colorT] 0..1 along the shape; items >= [mirrorFrom] are the faint reflection
 *  LINES   [rects]   x1,y1,x2,y2 per spoke
 *  CIRCLES [rects]   cx,cy,r per dot
 *  POLYS   [polys]   [polyCount] curves of [polyPts] points (x,y), [polyAlpha]/[polyWidth] per curve, [closed]
 */
internal class ShapeGeometry {
    var kind = PrimKind.RECTS; private set
    var count = 0; private set
    var mirrorFrom = Int.MAX_VALUE; private set
    var stroke = 0f; private set
    var rects = FloatArray(0); private set
    var colorT = FloatArray(0); private set
    var polys = FloatArray(0); private set
    var polyCount = 0; private set
    var polyPts = 0; private set
    var polyAlpha = FloatArray(0); private set
    var polyWidth = FloatArray(0); private set
    var closed = false; private set
    var layoutBuilds = 0; private set
    private var shape = WaveShape.NEON_BARS
    private var w = 0; private var h = 0
    private var bandL = 0f; private var bandR = 0f; private var cy = 0f; private var bandH = 0f
    private var ccx = 0f; private var ccy = 0f; private var r0 = 0f; private var maxLen = 0f
    private var barW = 0f
    private var sig: String? = null

    fun layout(fw: Int, fh: Int, l: WaveLayer): Boolean {
        val key = "$fw|$fh|${l.signature()}"
        if (key == sig) return false
        sig = key; layoutBuilds++
        w = fw; h = fh; shape = l.shape; count = 0; mirrorFrom = Int.MAX_VALUE; polyCount = 0; polyPts = 0; closed = false
        if (fw < 8 || fh < 8) return true
        val fW = fw.toFloat(); val fH = fh.toFloat(); val minDim = Math.min(fW, fH)
        stroke = Math.max(1.5f, minDim * 0.006f * l.thickness.toFloat())
        val margin = minDim * 0.05f
        bandL = margin; bandR = fW - margin
        bandH = Math.min(fH * 0.9f, Math.max((l.height * fH).toFloat(), minDim * 0.04f))
        val m = Math.min(margin, (fH - bandH) / 2f)
        val lo = bandH / 2f + m; val hi = fH - bandH / 2f - m
        cy = when (l.position) {
            WavePosition.TOP -> lo; WavePosition.BOTTOM -> hi; WavePosition.CENTER -> fH / 2f
            WavePosition.CUSTOM -> (l.customY * fH).toFloat()
        }.coerceIn(lo, Math.max(lo, hi))
        val span = bandR - bandL
        when (shape) {
            WaveShape.NEON_BARS -> { kind = PrimKind.RECTS; count = ((span / (minDim * 0.06f)).toInt()).coerceIn(10, 32); barW = span / count * 0.55f; rects = FloatArray(count * 4); colorT = FloatArray(count) }
            WaveShape.SPECTRUM_MIRROR -> {
                kind = PrimKind.RECTS; val n = ((span / (minDim * 0.022f)).toInt()).coerceIn(24, 72)
                barW = Math.max(1.5f, Math.min(span / n * 0.9f, span / n * 0.45f * l.thickness.toFloat()))
                count = n * 2; mirrorFrom = n; rects = FloatArray(count * 4); colorT = FloatArray(count)
            }
            WaveShape.DOTS -> { kind = PrimKind.CIRCLES; count = ((span / (minDim * 0.03f)).toInt()).coerceIn(20, 64); rects = FloatArray(count * 3); colorT = FloatArray(count) }
            WaveShape.RADIAL, WaveShape.RING -> {
                ccx = (l.cx * fW).toFloat(); ccy = (l.cy * fH).toFloat()
                val limit = Math.min(Math.min(ccx, fW - ccx), Math.min(ccy, fH - ccy)) - stroke * 2f
                r0 = (l.size * minDim).toFloat().coerceAtMost(Math.max(minDim * 0.04f, limit * 0.9f))
                maxLen = Math.max(0f, Math.min(r0 * 0.8f, limit - r0))
                if (shape == WaveShape.RADIAL) { kind = PrimKind.LINES; count = ((2.0 * Math.PI * r0 / (minDim * 0.03f)).toInt()).coerceIn(32, 96); rects = FloatArray(count * 4); colorT = FloatArray(count) }
                else { kind = PrimKind.POLYS; polyCount = 1; polyPts = 96; closed = true; allocPolys() }
            }
            WaveShape.RIBBON -> { kind = PrimKind.POLYS; polyCount = 4; polyPts = 120; allocPolys() }
            WaveShape.GLOW_LINE -> { kind = PrimKind.POLYS; polyCount = 1; polyPts = 160; allocPolys() }
            else -> { count = 0 } // legacy shapes are drawn by the original overlay
        }
        return true
    }

    private fun allocPolys() { polys = FloatArray(polyCount * polyPts * 2); polyAlpha = FloatArray(polyCount); polyWidth = FloatArray(polyCount); colorT = FloatArray(polyPts) }

    fun update(d: WaveData?, timeMs: Double, l: WaveLayer) {
        if (w < 8) return
        val reactive = WaveMath.usable(d, l)
        val t = if (timeMs.isNaN() || timeMs.isInfinite()) 0.0 else timeMs / 1000.0
        when (shape) {
            WaveShape.NEON_BARS -> {
                val pitch = (bandR - bandL) / count
                for (i in 0 until count) {
                    val u = (i + 0.5) / count
                    val v = WaveMath.spectrum(d, l, reactive, t, u, i)
                    val half = Math.min(bandH / 2f, Math.max(barW / 2f, v * bandH / 2f))
                    val x = bandL + i * pitch + (pitch - barW) / 2f
                    put(i, x, cy - half, x + barW, cy + half); colorT[i] = u.toFloat()
                }
            }
            WaveShape.SPECTRUM_MIRROR -> {
                val n = mirrorFrom; val pitch = (bandR - bandL) / n; val gap = stroke
                for (i in 0 until n) {
                    val u = (i + 0.5) / n
                    val v = WaveMath.spectrum(d, l, reactive, t, u, i)
                    val len = Math.max(barW, v * bandH / 2f - gap)
                    val x = bandL + i * pitch + (pitch - barW) / 2f
                    put(i, x, cy - gap - len, x + barW, cy - gap)
                    put(n + i, x, cy + gap, x + barW, cy + gap + len * 0.55f)
                    colorT[i] = u.toFloat(); colorT[n + i] = u.toFloat()
                }
            }
            WaveShape.DOTS -> {
                val pitch = (bandR - bandL) / count; val base = Math.max(1.5f, Math.min(w, h) * 0.006f * l.thickness.toFloat())
                val maxR = Math.max(base, Math.min(bandH / 2f, pitch * 0.9f))
                for (i in 0 until count) {
                    val u = (i + 0.5) / count; val v = WaveMath.spectrum(d, l, reactive, t, u, i)
                    val r = Math.min(maxR, base + v * (maxR - base))
                    val x = bandL + (i + 0.5f) * pitch
                    rects[i * 3] = x; rects[i * 3 + 1] = cy; rects[i * 3 + 2] = r; colorT[i] = u.toFloat()
                }
            }
            WaveShape.RADIAL -> for (i in 0 until count) {
                val ang = -Math.PI / 2.0 + 2.0 * Math.PI * i / count
                val u = WaveMath.fold(i, count)
                val v = WaveMath.spectrum(d, l, reactive, t, u, i)
                val len = Math.max(stroke, v * maxLen)
                val c = Math.cos(ang).toFloat(); val s = Math.sin(ang).toFloat()
                rects[i * 4] = ccx + c * r0; rects[i * 4 + 1] = ccy + s * r0
                rects[i * 4 + 2] = ccx + c * (r0 + len); rects[i * 4 + 3] = ccy + s * (r0 + len)
                colorT[i] = (i.toFloat() / count)
            }
            WaveShape.RING -> {
                for (i in 0 until polyPts) {
                    val ang = -Math.PI / 2.0 + 2.0 * Math.PI * i / polyPts
                    val v = WaveMath.spectrum(d, l, reactive, t, WaveMath.fold(i, polyPts), i / 2)
                    val r = r0 + v * maxLen * 0.6f
                    polys[i * 2] = ccx + (Math.cos(ang) * r).toFloat(); polys[i * 2 + 1] = ccy + (Math.sin(ang) * r).toFloat()
                    colorT[i] = i.toFloat() / polyPts
                }
                polyAlpha[0] = 1f; polyWidth[0] = 1f
            }
            WaveShape.RIBBON -> for (k in 0 until polyCount) {
                val f = 1.2 + 0.7 * k; val phase = k * 1.7 + l.speed * t * (1.1 + 0.35 * k)
                for (i in 0 until polyPts) {
                    val u = i.toDouble() / (polyPts - 1)
                    val env = WaveMath.envelope(d, l, reactive, t, u)
                    val a = (0.06 + 0.94 * env) * (1.0 - 0.12 * k)
                    val y = cy - (bandH / 2f) * a * WaveMath.taper(u) * Math.sin(2.0 * Math.PI * f * u + phase)
                    polys[(k * polyPts + i) * 2] = (bandL + u * (bandR - bandL)).toFloat()
                    polys[(k * polyPts + i) * 2 + 1] = y.toFloat().coerceIn(0f, h.toFloat())
                    if (k == 0) colorT[i] = u.toFloat()
                }
                polyAlpha[k] = 1f - 0.22f * k; polyWidth[k] = 1f - 0.18f * k
            }
            WaveShape.GLOW_LINE -> {
                for (i in 0 until polyPts) {
                    val u = i.toDouble() / (polyPts - 1)
                    val env = 0.04 + 0.96 * WaveMath.envelope(d, l, reactive, t, u)
                    val y = cy - (bandH / 2f) * env * WaveMath.taper(u) * Math.sin(2.0 * Math.PI * 6.0 * u + l.speed * t * 6.0)
                    polys[i * 2] = (bandL + u * (bandR - bandL)).toFloat(); polys[i * 2 + 1] = y.toFloat().coerceIn(0f, h.toFloat())
                    colorT[i] = u.toFloat()
                }
                polyAlpha[0] = 1f; polyWidth[0] = 1f
            }
            else -> {}
        }
    }

    private fun put(i: Int, l: Float, t: Float, r: Float, b: Float) {
        rects[i * 4] = l.coerceIn(0f, w.toFloat()); rects[i * 4 + 1] = t.coerceIn(0f, h.toFloat())
        rects[i * 4 + 2] = r.coerceIn(0f, w.toFloat()); rects[i * 4 + 3] = b.coerceIn(0f, h.toFloat())
    }
}

/** Value helpers shared by the shapes. Pure and deterministic (no random, no clock). */
internal object WaveMath {
    fun usable(d: WaveData?, l: WaveLayer): Boolean =
        l.reactive && d != null && d.count > 0 && d.pointsPerSec > 0 && d.planar.size >= 4 * d.count

    /** 0 -> 1 -> 0 over a closed loop of n items (symmetric spectrum: bass at the top, treble at the bottom). */
    fun fold(i: Int, n: Int): Double { val half = n / 2.0; return if (i <= half) i / half else (n - i) / half }

    /** Stable per-item variation 0.82..1.0 (integer hash). */
    fun jitter(i: Int): Double {
        var x = i * -1640531535 + 12345
        x = x xor (x ushr 15); x *= 668265261; x = x xor (x ushr 13)
        return 0.82 + 0.18 * ((x and 0xFFFF) / 65535.0)
    }

    /** Fades both ends of a curve to 0 so ribbons taper like the references. */
    fun taper(u: Double): Double = Math.pow(Math.sin(Math.PI * u.coerceIn(0.0, 1.0)), 1.2)

    private fun band(d: WaveData, series: Int, t: Double) = WaveGeometry.sample(d, series, t).toDouble()

    /** Spread value for position [u] (0..1): bass -> mid -> treble, modulated by loudness; a chosen band drives all items. */
    fun spectrum(d: WaveData?, l: WaveLayer, reactive: Boolean, tSec: Double, u: Double, i: Int): Float {
        if (!reactive || d == null) return 0f
        val tt = tSec - 0.06 * u // slight delay along the shape = visible flow
        val v = if (l.source.series == 0) {
            val wb = Math.max(0.0, 1.0 - 2.0 * u); val wt = Math.max(0.0, 2.0 * u - 1.0); val wm = 1.0 - wb - wt
            val mix = wb * band(d, 1, tt) + wm * band(d, 2, tt) + wt * band(d, 3, tt)
            mix * (0.4 + 0.6 * band(d, 0, tt))
        } else band(d, l.source.series, tt)
        return (v * jitter(i) * l.intensity).coerceIn(0.0, 1.0).toFloat()
    }

    /** Scrolling loudness envelope (2.4 s window around the playhead) at curve position [u]. */
    fun envelope(d: WaveData?, l: WaveLayer, reactive: Boolean, tSec: Double, u: Double): Double {
        if (!reactive || d == null) return 0.0
        val v = band(d, l.source.series, tSec + (u - 0.5) * WaveGeometry.WINDOW_S)
        return (v * l.intensity).coerceIn(0.0, 1.0)
    }
}
