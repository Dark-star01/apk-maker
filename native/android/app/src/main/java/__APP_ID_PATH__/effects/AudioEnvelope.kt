package __APP_ID__.effects

import __APP_ID__.analysis.WaveData

/**
 * wave.data -> normalized -> smoothed, as a small table at the wave's own resolution (30 points/s).
 * Built ONCE per (wave, preset, smoothing); [levelsAt] is then only a linear interpolation between two table rows.
 *
 * 1. Normalization (per series): the track's own quiet level (15th percentile) maps to 0 and its loud level
 *    (97th percentile) to 1, with a minimum span so a flat track stays flat instead of being stretched. Silence stays 0.
 * 2. Smoothing (per series): envelope follower, fast attack / slower release, run once over the table.
 *    The table is a pure function of (wave, attack, release); querying any time in any order gives the same value.
 */
internal class AudioEnvelope private constructor(
    private val count: Int,
    private val pps: Int,
    private val table: FloatArray, // planar: amplitude | bass | mid | treble
) {
    /** Linearly interpolated, normalized + smoothed levels at [timeMs] (clamped to the track). */
    fun levelsAt(timeMs: Double): AudioLevels {
        val pos = (if (timeMs.isNaN()) 0.0 else timeMs) / 1000.0 * pps
        val last = count - 1
        val p = Math.min(last.toDouble(), Math.max(0.0, pos))
        val i0 = p.toInt()
        val i1 = Math.min(last, i0 + 1)
        val f = p - i0
        return AudioLevels(lerp(0, i0, i1, f), lerp(1, i0, i1, f), lerp(2, i0, i1, f), lerp(3, i0, i1, f))
    }

    private fun lerp(series: Int, i0: Int, i1: Int, f: Double): Double {
        val o = series * count
        val a = table[o + i0].toDouble()
        return a + (table[o + i1].toDouble() - a) * f
    }

    companion object {
        private const val FLOOR_PCT = 0.15
        private const val CEIL_PCT = 0.97
        private const val MIN_SPAN = 0.15

        /** Null when there is nothing to react to (no or empty wave data). */
        fun build(wave: WaveData?, attackSec: Double, releaseSec: Double): AudioEnvelope? {
            if (wave == null || wave.count <= 0 || wave.pointsPerSec <= 0 || wave.planar.size < wave.count * 4) return null
            val n = wave.count
            val dt = 1.0 / wave.pointsPerSec
            val aAtk = 1.0 - Math.exp(-dt / Math.max(1e-3, attackSec))
            val aRel = 1.0 - Math.exp(-dt / Math.max(1e-3, releaseSec))
            val out = FloatArray(n * 4)
            val hist = IntArray(256)
            for (s in 0 until 4) {
                val o = s * n
                java.util.Arrays.fill(hist, 0)
                for (i in 0 until n) hist[wave.planar[o + i].toInt() and 0xFF]++
                val lo = percentile(hist, n, FLOOR_PCT) / 255.0
                val hi = percentile(hist, n, CEIL_PCT) / 255.0
                val span = Math.max(hi - lo, MIN_SPAN)
                var y = 0.0
                for (i in 0 until n) {
                    val x = Math.min(1.0, Math.max(0.0, ((wave.planar[o + i].toInt() and 0xFF) / 255.0 - lo) / span))
                    if (i == 0) y = x else y += (x - y) * (if (x > y) aAtk else aRel)
                    out[o + i] = y.toFloat()
                }
            }
            return AudioEnvelope(n, wave.pointsPerSec, out)
        }

        private fun percentile(hist: IntArray, n: Int, q: Double): Int {
            val target = Math.ceil(q * n).toInt()
            var acc = 0
            for (v in 0..255) { acc += hist[v]; if (acc >= target) return v }
            return 255
        }
    }
}
