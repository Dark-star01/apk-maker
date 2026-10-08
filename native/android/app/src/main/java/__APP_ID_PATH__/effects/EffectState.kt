package __APP_ID__.effects

/** Audio levels the effects reacted to: normalized 0..1 and smoothed (NOT the raw wave values). */
class AudioLevels(val amplitude: Double, val bass: Double, val mid: Double, val treble: Double) {
    companion object { val SILENT = AudioLevels(0.0, 0.0, 0.0, 0.0) }
}

/**
 * Everything a renderer needs to know about the effects at one instant. A pure function of
 * (project settings, wave data, time): no history, no side effects.
 *
 * Phase 5 animates ONE layer, the background image. All transform values are for that layer:
 *  - scale        >= 1. Total zoom (camera motion + audio pulse). Never lower than what is needed to keep
 *                 the image covering the whole frame after translation/rotation (no empty borders).
 *  - rotationDeg  degrees, + = clockwise, about the frame centre.
 *  - translateX/Y fraction of the frame width/height; + = image moves right/down.
 *  - opacity      0..1 (1 = fully visible).
 *  - glow         0..1 glow / bloom strength (treble driven).
 *  - intensity    0..1 overall energy (loudness driven, scaled by settings.intensity).
 *  - shake        0..1 how much shake is already included in translate/rotation (informational).
 * Apply order for a renderer: translate, rotate, scale about the frame centre.
 */
class EffectState(
    val timeMs: Double,
    val scale: Double,
    val rotationDeg: Double,
    val translateX: Double,
    val translateY: Double,
    val opacity: Double,
    val glow: Double,
    val intensity: Double,
    val shake: Double,
    val audio: AudioLevels,
    /** false when no wave data exists: audio-driven parts are neutral, time-based motion still runs. */
    val audioReactive: Boolean,
)
