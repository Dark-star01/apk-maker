package __APP_ID__.effects

/**
 * How strongly / how fast a preset reacts. Designed amounts at settings.intensity = 1.
 * attack/release are seconds at settings.smoothing = 0.5.
 */
// Rotation is kept small on purpose: at 16:9 every 1 degree costs ~3% extra scale to keep the frame covered.
internal class PresetParams(
    val attackSec: Double,
    val releaseSec: Double,
    val scaleAmt: Double,     // extra scale at full bass:        1.00 -> 1 + scaleAmt
    val rotationDeg: Double,  // max rotation at full mid:        -rot .. +rot
    val swayAmt: Double,      // max translate at full mid (fraction of frame)
    val opacityDepth: Double, // opacity in silence:              1 - depth .. 1 at full loudness
    val glowAmt: Double,      // glow at full treble
    val shakeAmt: Double,     // shake at full bass hit (0 = never shakes)
) {
    companion object {
        private val NONE = PresetParams(0.1, 0.4, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        private val SUBTLE = PresetParams(0.12, 0.60, 0.020, 0.25, 0.004, 0.06, 0.35, 0.0)
        private val PULSE = PresetParams(0.05, 0.30, 0.060, 0.5, 0.008, 0.10, 0.60, 0.0)
        private val BEAT = PresetParams(0.02, 0.16, 0.100, 0.8, 0.010, 0.18, 0.80, 0.6)
        private val CINEMATIC = PresetParams(0.20, 0.90, 0.035, 0.7, 0.015, 0.12, 0.50, 0.0)

        fun of(p: Preset): PresetParams = when (p) {
            Preset.NONE -> NONE
            Preset.SUBTLE -> SUBTLE
            Preset.PULSE -> PULSE
            Preset.BEAT -> BEAT
            Preset.CINEMATIC -> CINEMATIC
        }
    }
}
