package __APP_ID__.render

import __APP_ID__.effects.EffectEngine
import __APP_ID__.effects.EffectState

/** Everything needed to draw one frame, minus the pixels. Reused between frames (no per-frame allocation except EffectState). */
internal class FrameParams {
    val matrix = FloatArray(9)
    var alpha = 255
    /** 0..GLOW_LEVELS: quantized glow so a small fixed set of colour filters can be reused. */
    var glowLevel = 0
    var hasImage = false
    var coverBoost = 1.0
    lateinit var state: EffectState

    fun copyOf(): FrameParams {
        val p = FrameParams()
        System.arraycopy(matrix, 0, p.matrix, 0, 9)
        p.alpha = alpha; p.glowLevel = glowLevel; p.hasImage = hasImage; p.coverBoost = coverBoost; p.state = state
        return p
    }
}

/**
 * timestamp -> EffectState (EffectEngine, the only source) -> placement/opacity/glow. Pure and stateless apart from the
 * reusable [FrameParams]: the same (engine, time, sizes) always gives the same parameters. Not thread safe (render thread only).
 */
internal class FrameComposer {
    private val params = FrameParams()

    fun compose(engine: EffectEngine, timeMs: Double, frameW: Int, frameH: Int, imgW: Int, imgH: Int): FrameParams {
        val st = engine.getEffectState(timeMs)
        val p = params
        p.state = st
        val boost = FrameGeometry.compute(frameW, frameH, imgW, imgH, st.scale, st.rotationDeg, st.translateX, st.translateY, p.matrix)
        p.hasImage = boost > 0.0
        p.coverBoost = if (boost > 0.0) boost else 1.0
        // The engine keeps opacity >= 0.5; the floor here only makes "the picture never disappears" independent of that.
        p.alpha = Math.round(Math.min(1.0, Math.max(MIN_OPACITY, st.opacity)) * 255.0).toInt()
        p.glowLevel = Math.round(Math.min(1.0, Math.max(0.0, st.glow)) * GLOW_LEVELS).toInt()
        return p
    }

    companion object {
        const val GLOW_LEVELS = 16
        const val MIN_OPACITY = 0.2
    }
}
