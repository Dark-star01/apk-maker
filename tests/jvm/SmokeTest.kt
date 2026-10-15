package app.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import app.analysis.WaveData
import app.effects.EffectSettings
import app.render.Renderer

fun main() {
    val n = 300; val b = ByteArray(n * 4) { (100 + (it * 37) % 150).toByte() }
    val data = WaveData(30, n, 10_000L, b)
    val tree = mapOf("layers" to listOf(
        mapOf("type" to "wave", "id" to "w0", "shape" to "mirrored"),
        mapOf("type" to "wave", "id" to "w1", "shape" to "bars", "opacity" to 0.5),
        mapOf("type" to "wave", "id" to "w2", "shape" to "radial", "glow" to 0.8, "gradient" to true, "color2" to "#ff00aa"),
        mapOf("type" to "wave", "id" to "w3", "shape" to "ribbon", "glow" to 0.5),
        mapOf("type" to "wave", "id" to "w4", "shape" to "neonBars"),
        mapOf("type" to "wave", "id" to "w5", "shape" to "dots"),
        mapOf("type" to "wave", "id" to "w6", "shape" to "spectrumMirror", "gradient" to true),
        mapOf("type" to "wave", "id" to "w7", "shape" to "glowLine"),
        mapOf("type" to "wave", "id" to "w8", "shape" to "ring"),
        mapOf("type" to "text", "id" to "t0", "text" to "Hello brave world", "startMs" to 1000, "endMs" to 6000, "reveal" to "words", "box" to true, "outline" to true),
        mapOf("type" to "text", "id" to "t1", "text" to "مرحبا بك في عالمي", "startMs" to 0, "reveal" to "chars", "animIn" to "pop"),
    ))
    val scene = SceneParser.parse(tree)
    var fails = 0
    fun check(name: String, ok: Boolean) { if (!ok) { fails++; println("FAIL: $name") } }
    check("9 waves + 2 texts parsed", scene.layers.size == 11)
    val r = Renderer()
    r.setEffects(EffectSettings(), data, 10_000L)
    r.setBackground(Bitmap(640, 360), Renderer.Background.READY)
    r.setScene(scene)
    for ((w, h) in listOf(640 to 360, 360 to 640, 720 to 720)) {
        for (t in listOf(0.0, 500.0, 1500.0, 3000.0, 5990.0, 6500.0, 9999.0, 12000.0)) {
            val c = Canvas()
            r.renderFrame(c, w, h, t)
            check("balanced save/restore ${w}x$h t=$t", c.saves == c.restores)
            if (t < 10_000.0) check("draws something ${w}x$h t=$t", c.calls > 5) else check("nothing but background after the song ends ${w}x$h", (c.log["roundRect"] ?: 0) == 0 && c.texts.isEmpty())
        }
    }
    check("no layer errors", r.sceneError == null)
    // text visibility over time: t0 only inside [1000,6000)
    fun textsAt(t: Double): List<String> { val c = Canvas(); r.renderFrame(c, 640, 360, t); return c.texts }
    check("t0 hidden before start", textsAt(500.0).none { it == "Hello" || it == "brave" })
    check("t0 fully shown mid-span", textsAt(4000.0).containsAll(listOf("Hello", "brave", "world")))
    check("t0 gone after end", textsAt(6500.0).none { it == "Hello" })
    check("arabic words drawn", textsAt(9000.0).containsAll(listOf("مرحبا", "بك", "في", "عالمي")))
    // char reveal partially shows a prefix early on
    val early = textsAt(150.0)
    check("char reveal shows prefixes", early.any { it.isNotEmpty() && "مرحبا".startsWith(it) && it.length < 5 } || early.isEmpty())
    // deterministic: same time twice gives same calls
    val c1 = Canvas(); r.renderFrame(c1, 640, 360, 3210.0); val c2 = Canvas(); r.renderFrame(c2, 640, 360, 3210.0)
    check("deterministic call log", c1.log == c2.log && c1.texts == c2.texts)
    // legacy path untouched when no scene
    val r2 = Renderer(); r2.setEffects(EffectSettings(), data, 10_000L); r2.setBackground(Bitmap(640, 360), Renderer.Background.READY)
    val c3 = Canvas(); r2.renderFrame(c3, 640, 360, 3000.0)
    check("renderer without scene still renders background", (c3.log["bitmap"] ?: 0) == 1)
    println(if (fails == 0) "smoke OK" else "smoke FAILED=$fails")
    if (fails > 0) System.exit(1)
}
