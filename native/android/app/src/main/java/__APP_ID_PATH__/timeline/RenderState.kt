package __APP_ID__.timeline

import __APP_ID__.effects.EffectState

/**
 * What the single FrameRenderer (Phase 6) will need to draw one frame, minus the project data:
 * the playback clock plus the effect values for that instant. Preview and Export both build their frames from the
 * same shape, and [effects] always comes from EffectEngine.getEffectState(timeMs): there is no second source.
 * The audio engine is the only source of [timeMs] while playing (the "timeline clock").
 */
data class RenderState(
    val timeMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val effects: EffectState? = null,
)
