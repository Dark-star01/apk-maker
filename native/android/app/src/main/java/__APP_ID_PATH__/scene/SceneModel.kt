package __APP_ID__.scene

import __APP_ID__.render.WavePosition
import __APP_ID__.render.WaveSettings
import __APP_ID__.render.WaveSource
import __APP_ID__.render.WaveStyle

/** Easing curves (0..1 -> 0..1; BACK_OUT overshoots slightly). Pure maths. */
internal enum class Easing(val wire: String) {
    LINEAR("linear"), EASE_OUT("easeOut"), EASE_IN_OUT("easeInOut"), BACK_OUT("backOut");

    fun apply(x: Double): Double {
        val t = if (x.isNaN()) 0.0 else x.coerceIn(0.0, 1.0)
        return when (this) {
            LINEAR -> t
            EASE_OUT -> 1.0 - Math.pow(1.0 - t, 3.0)
            EASE_IN_OUT -> if (t < 0.5) 4.0 * t * t * t else 1.0 - Math.pow(-2.0 * t + 2.0, 3.0) / 2.0
            BACK_OUT -> { val c1 = 1.70158; val c3 = c1 + 1.0; 1.0 + c3 * Math.pow(t - 1.0, 3.0) + c1 * Math.pow(t - 1.0, 2.0) }
        }
    }

    companion object { fun fromWire(s: String?): Easing = values().firstOrNull { it.wire == s } ?: EASE_OUT }
}

/** Every waveform shape. The first three are the original overlay (kept byte-for-byte by the legacy code path). */
internal enum class WaveShape(val wire: String, val legacy: Boolean) {
    LINE("line", true), BARS("bars", true), MIRRORED("mirrored", true),
    NEON_BARS("neonBars", false), RADIAL("radial", false), RING("ring", false), RIBBON("ribbon", false),
    DOTS("dots", false), SPECTRUM_MIRROR("spectrumMirror", false), GLOW_LINE("glowLine", false);

    companion object { fun fromWire(s: String?): WaveShape = values().firstOrNull { it.wire == s } ?: MIRRORED }
}

internal enum class TextFont(val wire: String) {
    SANS("sans"), SERIF("serif"), MONO("mono"), CONDENSED("condensed"), LIGHT("light"), MEDIUM("medium"), CURSIVE("cursive"), CASUAL("casual");
    companion object { fun fromWire(s: String?): TextFont = values().firstOrNull { it.wire == s } ?: SANS }
}

internal enum class TextAnim(val wire: String) {
    NONE("none"), FADE("fade"), SLIDE_UP("slideUp"), SLIDE_DOWN("slideDown"), POP("pop");
    companion object { fun fromWire(s: String?): TextAnim = values().firstOrNull { it.wire == s } ?: FADE }
}

/** What appears step by step inside the "in" animation: the whole line, word after word, or character after character. */
internal enum class TextReveal(val wire: String) {
    LINE("line"), WORDS("words"), CHARS("chars");
    companion object { fun fromWire(s: String?): TextReveal = values().firstOrNull { it.wire == s } ?: LINE }
}

internal enum class TextAlign(val wire: String) {
    LEFT("left"), CENTER("center"), RIGHT("right");
    companion object { fun fromWire(s: String?): TextAlign = values().firstOrNull { it.wire == s } ?: CENTER }
}

/** One item of the scene. Layers are drawn in list order (first = bottom) over the background picture. */
internal sealed class Layer {
    abstract val id: String
    abstract val visible: Boolean
    /** Layer is shown for startMs <= t < endMs; endMs <= 0 means "until the end of the song". */
    abstract val startMs: Long
    abstract val endMs: Long

    fun activeAt(tMs: Double): Boolean = visible && tMs >= startMs && (endMs <= 0L || tMs < endMs)
}

internal class WaveLayer(
    override val id: String,
    override val visible: Boolean = true,
    override val startMs: Long = 0L,
    override val endMs: Long = 0L,
    val shape: WaveShape = WaveShape.MIRRORED,
    val color: Int = -1,
    /** Second gradient colour; only used when [gradient]. */
    val color2: Int = -1,
    val gradient: Boolean = false,
    val adaptive: Boolean = false,
    val position: WavePosition = WavePosition.BOTTOM,
    customY: Double = 0.8,
    height: Double = 0.18,
    val reactive: Boolean = true,
    val source: WaveSource = WaveSource.VOLUME,
    thickness: Double = 1.0,
    opacity: Double = 1.0,
    intensity: Double = 1.0,
    glow: Double = 0.0,
    speed: Double = 1.0,
    size: Double = 0.22,
    cx: Double = 0.5,
    cy: Double = 0.5,
) : Layer() {
    val customY = clean(customY, 0.0, 1.0, 0.8)
    val height = clean(height, 0.05, 0.5, 0.18)
    /** Stroke / bar thickness multiplier, 0.4..3. */
    val thickness = clean(thickness, 0.4, 3.0, 1.0)
    val opacity = clean(opacity, 0.0, 1.0, 1.0)
    /** How strongly the audio moves the shape, 0..2. */
    val intensity = clean(intensity, 0.0, 2.0, 1.0)
    val glow = clean(glow, 0.0, 1.0, 0.0)
    /** Animation speed multiplier for self-moving shapes (ribbon, glowLine), 0..2. */
    val speed = clean(speed, 0.0, 2.0, 1.0)
    /** Radius of radial/ring shapes as a fraction of the frame's short side, 0.08..0.45. */
    val size = clean(size, 0.08, 0.45, 0.22)
    val cx = clean(cx, 0.0, 1.0, 0.5)
    val cy = clean(cy, 0.0, 1.0, 0.5)

    /** The settings object of the ORIGINAL overlay (legacy shapes only), so that path keeps working unchanged. */
    fun toLegacy(): WaveSettings = WaveSettings(
        enabled = visible,
        style = when (shape) { WaveShape.LINE -> WaveStyle.LINE; WaveShape.BARS -> WaveStyle.BARS; else -> WaveStyle.MIRRORED },
        adaptive = adaptive, color = color, position = position, customY = customY,
        reactive = reactive, source = source, height = height,
    )

    /** Same text = same geometry; decides whether a layout is rebuilt. */
    fun signature(): String =
        "${shape.wire}|${position.wire}|$customY|$height|$reactive|${source.wire}|$thickness|$intensity|$size|$cx|$cy"

    companion object {
        internal fun clean(v: Double, lo: Double, hi: Double, fb: Double): Double =
            if (v.isNaN() || v.isInfinite()) fb else Math.min(hi, Math.max(lo, v))
    }
}

internal class TextLayer(
    override val id: String,
    override val visible: Boolean = true,
    override val startMs: Long = 0L,
    override val endMs: Long = 0L,
    val text: String = "",
    val font: TextFont = TextFont.SANS,
    val bold: Boolean = true,
    val italic: Boolean = false,
    size: Double = 0.06,
    val color: Int = -1,
    val align: TextAlign = TextAlign.CENTER,
    x: Double = 0.5,
    y: Double = 0.5,
    maxWidth: Double = 0.84,
    lineSpacing: Double = 1.2,
    val animIn: TextAnim = TextAnim.FADE,
    val animOut: TextAnim = TextAnim.FADE,
    val reveal: TextReveal = TextReveal.LINE,
    val easing: Easing = Easing.EASE_OUT,
    inMs: Long = 400L,
    outMs: Long = 300L,
    /** Time the word/char reveal takes; 0 = automatic (see [TextAnimator]). */
    revealMs: Long = 0L,
    val shadow: Boolean = true,
    val outline: Boolean = false,
    val outlineColor: Int = 0xFF000000.toInt(),
    val box: Boolean = false,
    val boxColor: Int = 0xFF000000.toInt(),
    boxOpacity: Double = 0.45,
    opacity: Double = 1.0,
) : Layer() {
    /** Font size as a fraction of the frame's short side, 0.02..0.25. */
    val size = WaveLayer.clean(size, 0.02, 0.25, 0.06)
    val x = WaveLayer.clean(x, 0.0, 1.0, 0.5)
    val y = WaveLayer.clean(y, 0.0, 1.0, 0.5)
    /** Text block width limit as a fraction of the frame width, 0.2..1. */
    val maxWidth = WaveLayer.clean(maxWidth, 0.2, 1.0, 0.84)
    val lineSpacing = WaveLayer.clean(lineSpacing, 0.8, 2.0, 1.2)
    val inMs = inMs.coerceIn(0L, 3000L)
    val outMs = outMs.coerceIn(0L, 3000L)
    val revealMs = revealMs.coerceIn(0L, 20000L)
    val boxOpacity = WaveLayer.clean(boxOpacity, 0.0, 1.0, 0.45)
    val opacity = WaveLayer.clean(opacity, 0.0, 1.0, 1.0)
}

/** The whole drawable scene above the background picture. Immutable; an empty scene draws nothing. */
internal class Scene(val layers: List<Layer>) {
    companion object { val EMPTY = Scene(emptyList()) }
}
