package __APP_ID__.effects

/** What the picture's audio reaction looks like. NONE = no reaction at all (neutral state). */
enum class Preset(val wire: String) {
    NONE("none"), SUBTLE("subtle"), PULSE("pulse"), BEAT("beat"), CINEMATIC("cinematic");

    companion object {
        fun fromWire(s: String?): Preset = values().firstOrNull { it.wire == s } ?: PULSE
    }
}

/** The background's camera path. Independent of [Preset]. PULSE_ZOOM is the only audio-driven one (bass). */
enum class MotionMode(val wire: String) {
    STATIC("static"), SLOW_ZOOM("slowZoom"), FLOAT("float"), PULSE_ZOOM("pulseZoom"), CINEMATIC_DRIFT("cinematicDrift");

    companion object {
        fun fromWire(s: String?): MotionMode = values().firstOrNull { it.wire == s } ?: SLOW_ZOOM
    }
}

/**
 * Project-level effect settings (project.effects in the web schema). Immutable; always sanitized.
 * @property intensity amount multiplier for every audio reaction, 0..2 (1 = the preset as designed).
 * @property smoothing 0..1; 0 = snappy, 0.5 = preset default, 1 = very smooth (scales attack/release times 0.5x..2x).
 * @property seed makes the shake pattern unique per project but identical between Preview and Export.
 * @property aspect frame width / height, used only to keep the image covering the frame when it rotates/moves.
 */
class EffectSettings(
    val preset: Preset = Preset.PULSE,
    val motion: MotionMode = MotionMode.SLOW_ZOOM,
    intensity: Double = 1.0,
    smoothing: Double = 0.5,
    val seed: Int = 1,
    aspect: Double = 16.0 / 9.0,
) {
    val intensity: Double = clean(intensity, 0.0, 2.0, 1.0)
    val smoothing: Double = clean(smoothing, 0.0, 1.0, 0.5)
    val aspect: Double = clean(aspect, 0.25, 4.0, 16.0 / 9.0)

    /** Same text = same behaviour. Used to decide whether an engine can be reused. */
    fun signature(): String = "${preset.wire}|${motion.wire}|$intensity|$smoothing|$seed|$aspect"

    companion object {
        private fun clean(v: Double, lo: Double, hi: Double, fallback: Double): Double =
            if (v.isNaN() || v.isInfinite()) fallback else Math.min(hi, Math.max(lo, v))
    }
}
