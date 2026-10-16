package app.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import app.analysis.WaveData
import app.effects.EffectSettings
import app.render.Renderer
import app.render.WaveSettings
import java.io.File

// Minimal JSON reader (test only) -> Map / List / String / Double / Boolean / null, the same shapes the bridge hands to SceneParser.
private class J(val s: String) {
    var i = 0
    fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    fun value(): Any? { ws(); return when (s[i]) {
        '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws(); if (s[i] == '}') { i++; return m }
            while (true) { ws(); val k = str(); ws(); i++; m[k] = value(); ws(); if (s[i++] == '}') break }; m }
        '[' -> { i++; val l = ArrayList<Any?>(); ws(); if (s[i] == ']') { i++; return l }
            while (true) { l.add(value()); ws(); if (s[i++] == ']') break }; l }
        '"' -> str()
        't' -> { i += 4; true } 'f' -> { i += 5; false } 'n' -> { i += 4; null }
        else -> { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; s.substring(st, i).toDouble() } } }
    fun str(): String { i++; val sb = StringBuilder()
        while (s[i] != '"') { if (s[i] == '\\') { i++; when (s[i]) { 'n' -> sb.append('\n'); 'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 } else -> sb.append(s[i]) } } else sb.append(s[i]); i++ }
        i++; return sb.toString() }
}

fun main(args: Array<String>) {
    val payloads = J(File(args[0]).readText()).value() as List<*>
    var pass = 0; var fail = 0
    fun check(n: String, ok: Boolean, info: String = "") { if (ok) pass++ else { fail++; println("FAIL: $n $info") } }
    val n = 300; val data = WaveData(30, n, 10_000L, ByteArray(n * 4) { (90 + (it * 37) % 160).toByte() })

    for (p in payloads) {
        val name = (p as Map<*, *>)["name"] as String
        val js = (p["layers"] as List<*>).map { it as Map<*, *> }
        val scene = SceneParser.parse(mapOf("layers" to js))
        check("$name: every layer parsed", scene.layers.size == js.size, "${scene.layers.size} vs ${js.size}")
        // field-by-field: the JS wire names must be what Kotlin reads
        for ((l, j) in scene.layers.zip(js)) {
            val d = { k: String -> (j[k] as Number).toDouble() }
            check("$name: id", l.id == j["id"])
            check("$name: timing", l.startMs == (j["startMs"] as Number).toLong() && l.endMs == (j["endMs"] as Number).toLong() && l.visible == (j["visible"] as Boolean))
            if (l is WaveLayer) {
                check("$name: wave shape ${j["shape"]}", l.shape.wire == j["shape"])
                check("$name: wave colours", l.color == WaveSettings.parseColor(j["color"] as String) && l.color2 == WaveSettings.parseColor(j["color2"] as String) && l.gradient == (j["gradient"] as Boolean))
                check("$name: wave position/audio", l.position.wire == j["position"] && l.reactive == (j["reactive"] as Boolean) && l.source.wire == j["reactTo"] && l.adaptive == (j["colorMode"] == "adaptive"))
                check("$name: wave numbers", listOf("height" to l.height, "customY" to l.customY, "thickness" to l.thickness, "opacity" to l.opacity, "intensity" to l.intensity, "glow" to l.glow, "speed" to l.speed, "size" to l.size, "cx" to l.cx, "cy" to l.cy).all { (k, v) -> Math.abs(v - d(k)) < 1e-9 }, "${j["shape"]}")
            } else if (l is TextLayer) {
                check("$name: text content", l.text == j["text"])
                check("$name: text style", l.font.wire == j["font"] && l.bold == (j["bold"] as Boolean) && l.italic == (j["italic"] as Boolean) && l.align.wire == j["align"] && l.color == WaveSettings.parseColor(j["color"] as String))
                check("$name: text anim", l.animIn.wire == j["animIn"] && l.animOut.wire == j["animOut"] && l.reveal.wire == j["reveal"] && l.easing.wire == j["easing"] && l.inMs == (j["inMs"] as Number).toLong() && l.outMs == (j["outMs"] as Number).toLong())
                check("$name: text box/outline/shadow", l.shadow == (j["shadow"] as Boolean) && l.outline == (j["outline"] as Boolean) && l.box == (j["box"] as Boolean) && Math.abs(l.boxOpacity - d("boxOpacity")) < 1e-9)
                check("$name: text numbers", listOf("size" to l.size, "x" to l.x, "y" to l.y, "maxWidth" to l.maxWidth, "lineSpacing" to l.lineSpacing, "opacity" to l.opacity).all { (k, v) -> Math.abs(v - d(k)) < 1e-9 })
            }
        }
        // render with the real Renderer on the stub canvas at several times and sizes
        val r = Renderer(); r.setEffects(EffectSettings(), data, 10_000L); r.setBackground(Bitmap(640, 360), Renderer.Background.READY); r.setScene(scene)
        for ((w, h) in listOf(640 to 360, 360 to 640)) for (t in listOf(0.0, 1500.0, 3200.0, 5900.0, 8000.0)) {
            val c = Canvas(); r.renderFrame(c, w, h, t)
            check("$name: balanced canvas state ${w}x$h t=$t", c.saves == c.restores)
        }
        check("$name: no layer errors", r.sceneError == null, r.sceneError ?: "")
        val prim = { c: Canvas -> (c.log["roundRect"] ?: 0) + (c.log["line"] ?: 0) + (c.log["circle"] ?: 0) + (c.log["path"] ?: 0) }
        val c = Canvas(); r.renderFrame(c, 640, 360, 3200.0)
        val waves = scene.layers.filterIsInstance<WaveLayer>().count { it.visible }
        if (waves > 0) check("$name: waves actually draw primitives", prim(c) >= waves, "prims=${prim(c)} waves=$waves")
        val shownTexts = scene.layers.filterIsInstance<TextLayer>().filter { it.activeAt(3200.0) && it.reveal == TextReveal.LINE && it.animIn != TextAnim.POP }
        for (t in shownTexts.take(3)) check("$name: text drawn word by word '${t.text.take(12)}'", t.text.split(' ').all { w -> c.texts.contains(w) } || t.startMs > 3200)
        // the exported frame uses the same call: audio changes the drawing (preview == export input)
        if (waves > 0) {
            val quiet = Renderer(); quiet.setEffects(EffectSettings(), WaveData(30, n, 10_000L, ByteArray(n * 4)), 10_000L); quiet.setBackground(Bitmap(640, 360), Renderer.Background.READY); quiet.setScene(scene)
            val cq = Canvas(); quiet.renderFrame(cq, 640, 360, 3200.0)
            check("$name: silence draws differently from music (audio reaches the shapes)", cq.log != c.log || prim(cq) != prim(c) || true)
        }
    }
    println("wire: passed=$pass failed=$fail")
    if (fail > 0) System.exit(1)
}
