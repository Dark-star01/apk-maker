package __APP_ID__.scene

import __APP_ID__.render.WavePosition
import __APP_ID__.render.WaveSettings
import __APP_ID__.render.WaveSource

/**
 * Builds a [Scene] from the plain tree the bridge produces (Map / List / String / Number / Boolean). No org.json here, so
 * this is testable on a plain JVM. Everything is sanitized: unknown types are skipped, numbers are clamped, text is capped,
 * the number of layers is limited. Garbage in never throws; it just yields fewer layers.
 */
internal object SceneParser {
    const val MAX_LAYERS = 160
    const val MAX_TEXT_CHARS = 600

    fun parse(root: Any?): Scene {
        val list = (asMap(root)?.get("layers") ?: (root as? List<*>)) as? List<*> ?: return Scene.EMPTY
        val out = ArrayList<Layer>()
        val seen = HashSet<String>()
        for ((i, item) in list.withIndex()) {
            if (out.size >= MAX_LAYERS) break
            val m = asMap(item) ?: continue
            var id = str(m, "id", "").take(40).ifEmpty { "layer-$i" }
            if (!seen.add(id)) { id = "$id-$i"; seen.add(id) }
            val layer = when (str(m, "type", "")) {
                "wave" -> wave(id, m)
                "text" -> text(id, m)
                else -> null
            }
            if (layer != null) out.add(layer)
        }
        return Scene(out)
    }

    private fun wave(id: String, m: Map<*, *>): WaveLayer {
        val c2 = m["color2"]
        return WaveLayer(
            id = id, visible = bool(m, "visible", true), startMs = long(m, "startMs", 0), endMs = long(m, "endMs", 0),
            shape = WaveShape.fromWire(str(m, "shape", "")),
            color = WaveSettings.parseColor(str(m, "color", "#ffffff")),
            color2 = if (c2 == null) -1 else WaveSettings.parseColor(c2.toString()),
            gradient = bool(m, "gradient", false), adaptive = str(m, "colorMode", "manual") == "adaptive",
            position = WavePosition.fromWire(str(m, "position", "bottom")), customY = num(m, "customY", 0.8), height = num(m, "height", 0.18),
            reactive = bool(m, "reactive", true), source = WaveSource.fromWire(str(m, "reactTo", "volume")),
            thickness = num(m, "thickness", 1.0), opacity = num(m, "opacity", 1.0), intensity = num(m, "intensity", 1.0),
            glow = num(m, "glow", 0.0), speed = num(m, "speed", 1.0), size = num(m, "size", 0.22),
            cx = num(m, "cx", 0.5), cy = num(m, "cy", 0.5),
        )
    }

    private fun text(id: String, m: Map<*, *>): TextLayer = TextLayer(
        id = id, visible = bool(m, "visible", true), startMs = long(m, "startMs", 0), endMs = long(m, "endMs", 0),
        text = str(m, "text", "").take(MAX_TEXT_CHARS),
        font = TextFont.fromWire(str(m, "font", "sans")), bold = bool(m, "bold", true), italic = bool(m, "italic", false),
        size = num(m, "size", 0.06), color = WaveSettings.parseColor(str(m, "color", "#ffffff")),
        align = TextAlign.fromWire(str(m, "align", "center")), x = num(m, "x", 0.5), y = num(m, "y", 0.5),
        maxWidth = num(m, "maxWidth", 0.84), lineSpacing = num(m, "lineSpacing", 1.2),
        animIn = TextAnim.fromWire(str(m, "animIn", "fade")), animOut = TextAnim.fromWire(str(m, "animOut", "fade")),
        reveal = TextReveal.fromWire(str(m, "reveal", "line")), easing = Easing.fromWire(str(m, "easing", "easeOut")),
        inMs = long(m, "inMs", 400), outMs = long(m, "outMs", 300), revealMs = long(m, "revealMs", 0),
        shadow = bool(m, "shadow", true), outline = bool(m, "outline", false),
        outlineColor = WaveSettings.parseColor(str(m, "outlineColor", "#000000")),
        box = bool(m, "box", false), boxColor = WaveSettings.parseColor(str(m, "boxColor", "#000000")),
        boxOpacity = num(m, "boxOpacity", 0.45), opacity = num(m, "opacity", 1.0),
    )

    private fun asMap(v: Any?): Map<*, *>? = v as? Map<*, *>
    private fun str(m: Map<*, *>, k: String, d: String): String = (m[k] as? String) ?: d
    private fun bool(m: Map<*, *>, k: String, d: Boolean): Boolean = (m[k] as? Boolean) ?: d
    private fun num(m: Map<*, *>, k: String, d: Double): Double = (m[k] as? Number)?.toDouble() ?: d
    private fun long(m: Map<*, *>, k: String, d: Long): Long {
        val n = (m[k] as? Number)?.toDouble() ?: return d
        return if (n.isNaN() || n.isInfinite()) d else n.toLong().coerceIn(0L, 24L * 3600_000L)
    }
}
