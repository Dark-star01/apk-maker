// Minimal stand-ins for android.graphics so the Android-facing drawing code can be type-checked and smoke-run on a plain JVM.
// Signatures follow the real API as far as the project uses it. NOT the Android SDK: a real build is still required.
package android.graphics

class Bitmap(val width: Int, val height: Int) { var isRecycled = false; fun recycle() { isRecycled = true }; fun getPixel(x: Int, y: Int): Int = 0xFF808080.toInt() }
object Color { const val BLACK = -16777216; fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b }
class ColorMatrix(val a: FloatArray)
open class ColorFilter
class ColorMatrixColorFilter(m: ColorMatrix) : ColorFilter()
class Matrix { fun setValues(v: FloatArray) {} }
open class Shader { enum class TileMode { CLAMP, REPEAT, MIRROR } }
class LinearGradient(x0: Float, y0: Float, x1: Float, y1: Float, c0: Int, c1: Int, t: Shader.TileMode) : Shader()
class Typeface private constructor(val family: String, val style: Int) {
    companion object { const val BOLD = 1; const val ITALIC = 2; fun create(f: String, s: Int) = Typeface(f, s) }
}
class Path { fun rewind() {}; fun moveTo(x: Float, y: Float) {}; fun lineTo(x: Float, y: Float) {}; fun close() {} }
class Paint(flags: Int = 0) {
    companion object { const val ANTI_ALIAS_FLAG = 1; const val FILTER_BITMAP_FLAG = 2 }
    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Join { ROUND, BEVEL, MITER }
    enum class Cap { ROUND, BUTT, SQUARE }
    enum class Align { LEFT, CENTER, RIGHT }
    class FontMetrics { @JvmField var ascent = -0.9f; @JvmField var descent = 0.25f }
    var color = 0; var alpha = 255; var style = Style.FILL; var strokeWidth = 0f; var strokeJoin = Join.MITER; var strokeCap = Cap.BUTT
    var typeface: Typeface? = null; var textSize = 12f; var textAlign = Align.LEFT; var shader: Shader? = null; var colorFilter: ColorFilter? = null
    val fontMetrics: FontMetrics get() = FontMetrics().also { it.ascent = -0.9f * textSize; it.descent = 0.25f * textSize }
    fun measureText(s: String): Float = s.length * textSize * 0.55f
}
open class Canvas {
    var calls = 0; val log = HashMap<String, Int>(); var saves = 0; var restores = 0
    private fun c(n: String) { calls++; log[n] = (log[n] ?: 0) + 1 }
    open fun drawColor(c: Int) = c("color")
    open fun drawBitmap(b: Bitmap, m: Matrix, p: Paint?) = c("bitmap")
    open fun drawText(s: String, x: Float, y: Float, p: Paint) { c("text"); texts.add(s) }
    open fun drawPath(path: Path, p: Paint) = c("path")
    open fun drawRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, p: Paint) { c("roundRect"); require(!l.isNaN() && !t.isNaN() && !r.isNaN() && !b.isNaN()) }
    open fun drawLine(a: Float, b: Float, c: Float, d: Float, p: Paint) = c("line")
    open fun drawCircle(x: Float, y: Float, r: Float, p: Paint) { c("circle"); require(r >= 0f) }
    fun save(): Int { saves++; return saves }
    fun restore() { restores++ }
    fun translate(x: Float, y: Float) {}
    fun scale(sx: Float, sy: Float, px: Float, py: Float) {}
    val texts = ArrayList<String>()
}
