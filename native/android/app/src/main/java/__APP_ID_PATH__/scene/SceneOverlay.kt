package __APP_ID__.scene

import android.graphics.Canvas
import __APP_ID__.analysis.WaveData
import __APP_ID__.render.WaveOverlay

/**
 * Draws a whole [Scene] over the background, layer by layer in list order. Legacy waveform shapes go through the
 * original [WaveOverlay] (unchanged behaviour), new shapes through [ShapeOverlay], text through [TextOverlay].
 * Used by Preview and Export through the one Renderer.
 */
internal class SceneOverlay {
    private val legacy = HashMap<String, WaveOverlay>()
    private val shapes = HashMap<String, ShapeOverlay>()
    private val text = TextOverlay()
    @Volatile var lastError: String? = null; private set

    fun reset() { legacy.clear(); shapes.clear(); text.reset() }

    fun draw(canvas: Canvas, w: Int, h: Int, timeMs: Double, durationMs: Long, data: WaveData?, scene: Scene, adaptive: Int) {
        for (layer in scene.layers) {
            try {
                when (layer) {
                    is WaveLayer -> {
                        val fade = TextAnimator.fadeAlpha(layer, timeMs, durationMs)
                        if (fade <= 0.0) continue
                        if (layer.shape.legacy) {
                            val o = legacy.getOrPut(layer.id) { WaveOverlay() }
                            o.draw(canvas, w, h, timeMs, data, layer.toLegacy(), adaptive, (fade * layer.opacity).toFloat())
                            o.lastError?.let { lastError = it }
                        } else {
                            val o = shapes.getOrPut(layer.id) { ShapeOverlay() }
                            o.draw(canvas, w, h, timeMs, data, layer, fade, adaptive)
                            o.lastError?.let { lastError = it }
                        }
                    }
                    is TextLayer -> text.draw(canvas, w, h, timeMs, durationMs, layer)
                }
            } catch (t: Throwable) { lastError = t.javaClass.simpleName }
        }
        text.lastError?.let { lastError = it }
    }
}
