package __APP_ID__.timeline

/**
 * What the single FrameRenderer (Phase 6) will need to draw one frame, minus the project data:
 * the playback clock. Preview and Export both build their frames from the same shape.
 * The audio engine is the only source of [timeMs] while playing (the "timeline clock").
 */
data class RenderState(
    val timeMs: Long,
    val durationMs: Long,
    val playing: Boolean,
)
