package __APP_ID__.effects

import __APP_ID__.analysis.WaveData

/**
 * The single source of truth for effect values. Preview and Export both call [getEffectState] and render
 * what it returns; neither has effect maths of its own.
 *
 *   wave.data -> AudioEnvelope (normalize + smooth, once) -> levelsAt(t) (interpolate) -> mapping -> EffectState
 *
 * Guarantees: deterministic (no randomness, no clock), stateless between calls (any time, any order),
 * no I/O, no DOM, no Android classes, ~4 table lookups + a few sin() per call. Immutable: to change settings
 * or wave data, build a new engine (cheap: one pass over the wave table).
 *
 * @param durationMs track length; if <= 0 the wave's own length is used (0 = unknown).
 */
class EffectEngine internal constructor(val settings: EffectSettings, wave: WaveData?, durationMs: Long) {

    private val params = PresetParams.of(settings.preset)
    private val durationMs: Long = if (durationMs > 0) durationMs else (wave?.durationMs ?: 0L)
    private val envelope: AudioEnvelope?
    val hasWave: Boolean get() = envelope != null

    init {
        // smoothing 0 / .5 / 1  ->  x0.5 / x1 / x2 on both times.
        val k = Math.pow(2.0, (settings.smoothing - 0.5) * 2.0)
        envelope = AudioEnvelope.build(wave, params.attackSec * k, params.releaseSec * k)
    }

    fun getEffectState(timeMs: Long): EffectState = getEffectState(timeMs.toDouble())

    /** [timeMs] may be fractional (export frames are at n * 1000/30 ms). Out-of-range or NaN times are clamped. */
    fun getEffectState(timeMs: Double): EffectState {
        var t = if (timeMs.isNaN() || timeMs < 0) 0.0 else timeMs
        if (durationMs > 0 && t > durationMs) t = durationMs.toDouble()
        val tSec = t / 1000.0
        val durSec = durationMs / 1000.0

        val lv = envelope?.levelsAt(t) ?: AudioLevels.SILENT
        val reactive = envelope != null
        val amount = settings.intensity
        val none = settings.preset == Preset.NONE

        // Background camera path (time only).
        val m = BackgroundMotion.at(settings.motion, tSec, durSec)
        var scale = m.scale
        var tx = m.tx
        var ty = m.ty
        var rot = m.rotDeg
        if (settings.motion == MotionMode.PULSE_ZOOM) scale += 0.05 * lv.bass * amount

        // Audio reaction (preset). Neutral without audio data or with preset NONE.
        var opacity = 1.0
        var glow = 0.0
        var intensity = 0.0
        var shake = 0.0
        if (reactive && !none) {
            val p = params
            scale += p.scaleAmt * lv.bass * amount
            rot += p.rotationDeg * amount * lv.mid * Math.sin(TWO_PI * tSec / 9.0)
            tx += p.swayAmt * amount * lv.mid * Math.sin(TWO_PI * tSec / 7.3 + 0.9)
            ty += p.swayAmt * amount * lv.mid * 0.7 * Math.sin(TWO_PI * tSec / 11.1)
            opacity = 1.0 - Math.min(0.5, p.opacityDepth * amount) * (1.0 - lv.amplitude)
            glow = clamp01(lv.treble * p.glowAmt * amount)
            intensity = clamp01(lv.amplitude * amount)
            if (p.shakeAmt > 0) {
                shake = clamp01(p.shakeAmt * smoothstep(0.55, 0.95, lv.bass) * amount)
                if (shake > 0) {
                    val x = tSec * SHAKE_HZ
                    tx += DeterministicNoise.noise(settings.seed * 3, x) * shake * SHAKE_MAX_T
                    ty += DeterministicNoise.noise(settings.seed * 3 + 1, x) * shake * SHAKE_MAX_T
                    rot += DeterministicNoise.noise(settings.seed * 3 + 2, x) * shake * SHAKE_MAX_ROT
                }
            }
        }

        // Never let the image reveal an empty border: grow the scale just enough (continuous in t).
        scale = Math.max(scale, coverScale(tx, ty, rot, settings.aspect))
        return EffectState(t, scale, rot, tx, ty, opacity, glow, intensity, shake, lv, reactive)
    }

    private fun coverScale(tx: Double, ty: Double, rotDeg: Double, aspect: Double): Double {
        val th = Math.abs(rotDeg) * Math.PI / 180.0
        val rotNeed = Math.cos(th) + Math.sin(th) * Math.max(aspect, 1.0 / aspect)
        return rotNeed + 2.0 * Math.max(Math.abs(tx), Math.abs(ty))
    }

    private fun clamp01(v: Double) = Math.min(1.0, Math.max(0.0, v))
    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = clamp01((x - e0) / (e1 - e0))
        return t * t * (3.0 - 2.0 * t)
    }

    companion object {
        private const val TWO_PI = 2.0 * Math.PI
        private const val SHAKE_HZ = 14.0
        private const val SHAKE_MAX_T = 0.012   // fraction of the frame
        private const val SHAKE_MAX_ROT = 1.2   // degrees
    }
}
