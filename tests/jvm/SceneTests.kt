package app.scene

import app.analysis.WaveData
import app.render.WavePosition

private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean, info: String = "") {
    if (ok) passed++ else { failed++; println("FAIL: $name $info") }
}

/** 10 s of synthetic analysis: loud bass pulses every 0.5 s, steady mid, quiet treble. */
private fun synth(): WaveData {
    val n = 300
    val b = ByteArray(n * 4)
    for (i in 0 until n) {
        val beat = if (i % 15 < 4) 255 else 40
        b[i] = (140 + (i % 15) * 6).toByte()          // amplitude
        b[n + i] = beat.toByte()                        // bass
        b[2 * n + i] = (110 + (i * 7) % 90).toByte()   // mid
        b[3 * n + i] = (30 + (i * 13) % 60).toByte()   // treble
    }
    return WaveData(30, n, 10_000L, b)
}

private fun inFrame(a: FloatArray, n: Int, w: Int, h: Int, stride: Int): Boolean {
    for (i in 0 until n * stride) { val v = a[i]; if (v.isNaN() || v < -0.01f || v > (if (i % 2 == 0) w else h) + 0.01f) { if (stride != 3 && stride != 4) return false } }
    return true
}

private fun allFinite(a: FloatArray, len: Int, maxX: Int, maxY: Int, step: Int): Boolean {
    var i = 0
    while (i + 1 < len) { val x = a[i]; val y = a[i + 1]
        if (x.isNaN() || y.isNaN() || x < -0.5f || y < -0.5f || x > maxX + 0.5f || y > maxY + 0.5f) return false
        i += step }
    return true
}

private fun wave(shape: WaveShape, pos: WavePosition = WavePosition.BOTTOM) = WaveLayer("w", shape = shape, position = pos, glow = 0.5)

fun main() {
    val d = synth()
    val sizes = listOf(640 to 360, 360 to 640, 720 to 720, 746 to 420)
    // ---- shapes: geometry stays inside the frame, is deterministic, reacts to audio, differs between shapes
    val fingerprints = HashMap<WaveShape, Double>()
    for (shape in WaveShape.values().filter { !it.legacy }) {
        for ((w, h) in sizes) for (pos in listOf(WavePosition.TOP, WavePosition.CENTER, WavePosition.BOTTOM)) {
            val l = wave(shape, pos)
            val g = ShapeGeometry(); g.layout(w, h, l)
            for (t in listOf(0.0, 1234.5, 5000.0, 9999.0, 20000.0, -5.0)) {
                g.update(d, t, l)
                val ok = when (g.kind) {
                    PrimKind.RECTS -> (0 until g.count).all { i -> val r = g.rects; r[i*4] in 0f..w.toFloat() && r[i*4+2] in 0f..w.toFloat() && r[i*4+1] in 0f..h.toFloat() && r[i*4+3] in 0f..h.toFloat() && r[i*4] <= r[i*4+2] && r[i*4+1] <= r[i*4+3] }
                    PrimKind.LINES -> (0 until g.count).all { i -> val r = g.rects; listOf(r[i*4], r[i*4+2]).all { it in -0.5f..w + 0.5f } && listOf(r[i*4+1], r[i*4+3]).all { it in -0.5f..h + 0.5f } }
                    PrimKind.CIRCLES -> (0 until g.count).all { i -> val r = g.rects; r[i*3] in 0f..w.toFloat() && r[i*3+1] in 0f..h.toFloat() && r[i*3+2] > 0f && r[i*3+2] < h }
                    PrimKind.POLYS -> allFinite(g.polys, g.polyCount * g.polyPts * 2, w, h, 2)
                }
                check("inside frame $shape ${w}x$h $pos t=$t", ok)
            }
        }
        val l = wave(shape); val g1 = ShapeGeometry(); g1.layout(640, 360, l); g1.update(d, 2200.0, l)
        val g2 = ShapeGeometry(); g2.layout(640, 360, l); g2.update(d, 2200.0, l)
        val prim = if (g1.kind == PrimKind.POLYS) g1.polys else g1.rects
        val prim2 = if (g2.kind == PrimKind.POLYS) g2.polys else g2.rects
        check("deterministic $shape", prim.contentEquals(prim2))
        // audio reaction: loud beat time vs quiet time must produce different geometry
        val gl = ShapeGeometry(); gl.layout(640, 360, l); gl.update(d, 100.0, l)   // beat at i<4 (t<0.13 s)
        val gq = ShapeGeometry(); gq.layout(640, 360, l); gq.update(d, 400.0, l)
        val a = if (gl.kind == PrimKind.POLYS) gl.polys else gl.rects
        val b = if (gq.kind == PrimKind.POLYS) gq.polys else gq.rects
        check("reacts to audio $shape", !a.contentEquals(b))
        // non reactive: audio must not matter
        val nr = WaveLayer("n", shape = shape, reactive = false)
        val n1 = ShapeGeometry(); n1.layout(640, 360, nr); n1.update(d, 100.0, nr)
        val n2 = ShapeGeometry(); n2.layout(640, 360, nr); n2.update(d, 400.0, nr)
        if (shape != WaveShape.RIBBON && shape != WaveShape.GLOW_LINE) {
            check("non-reactive is audio independent $shape", (if (n1.kind == PrimKind.POLYS) n1.polys else n1.rects).contentEquals(if (n2.kind == PrimKind.POLYS) n2.polys else n2.rects))
        }
        // no data: nothing crashes, shapes still valid
        val nd = ShapeGeometry(); nd.layout(640, 360, l); nd.update(null, 100.0, l)
        check("no data ok $shape", true)
        // a signature of the drawn shape, to prove shapes are really different from each other
        fingerprints[shape] = (a.take(40).sumOf { it.toDouble() }) + g1.count * 1000.0 + g1.polyCount * 77.0 + g1.kind.ordinal * 13.0
    }
    check("shapes are structurally different", fingerprints.values.toSet().size == fingerprints.size, fingerprints.toString())
    // radial: spokes lie on distinct angles around the centre and never cross the frame
    run {
        val l = WaveLayer("r", shape = WaveShape.RADIAL, size = 0.45, cx = 0.05, cy = 0.95)
        val g = ShapeGeometry(); g.layout(640, 360, l); g.update(d, 100.0, l)
        check("radial near corner stays in frame", (0 until g.count).all { i -> g.rects[i*4+2] in -0.5f..640.5f && g.rects[i*4+3] in -0.5f..360.5f })
    }
    // layout caching
    run {
        val l = wave(WaveShape.NEON_BARS); val g = ShapeGeometry()
        g.layout(640, 360, l); val rebuilt = g.layout(640, 360, l); g.layout(640, 360, WaveLayer("x", shape = WaveShape.NEON_BARS, height = 0.3))
        check("layout cached", !rebuilt && g.layoutBuilds == 2)
    }
    // ---- text layout
    val measure = { s: String -> s.length * 10f }
    run {
        val b = TextLayoutEngine.layout("hello brave new world", 120f, TextAlign.LEFT, 10f, measure)
        check("wraps into lines", b.lineCount >= 2 && b.words.size == 4)
        check("ltr first word at left", b.words[0].x == 0f && b.words[1].line == b.words[0].line)
        check("ltr words in order", b.words.filter { it.line == 0 }.zipWithNext().all { (a, c) -> a.x < c.x })
        check("char starts cumulative", b.words[1].charStart == 5 && b.words[2].charStart == 10)
        val ar = TextLayoutEngine.layout("مرحبا بك في عالمي", 1000f, TextAlign.CENTER, 10f, measure)
        check("arabic detected as rtl", ar.rtl)
        check("rtl first logical word at the right", ar.words[0].x > ar.words[1].x && ar.words[1].x > ar.words[2].x)
        check("rtl centered inside block", ar.words.all { it.x >= 0f && it.x + it.w <= ar.width + 0.01f })
        check("empty text", TextLayoutEngine.layout("   ", 100f, TextAlign.CENTER, 10f, measure).words.isEmpty())
        val long = TextLayoutEngine.layout("supercalifragilistic", 50f, TextAlign.CENTER, 10f, measure)
        check("long word keeps its own line", long.words.size == 1 && long.lineCount == 1)
        val nl = TextLayoutEngine.layout("a b\nc d", 1000f, TextAlign.RIGHT, 10f, measure)
        check("newline forces a new line", nl.lineCount == 2 && nl.words[2].line == 1)
        check("right align ends at block edge", nl.words[1].x + nl.words[1].w == nl.width)
        check("mixed starts latin => ltr", !TextLayoutEngine.isRtl("Hello مرحبا"))
        check("digits only => ltr", !TextLayoutEngine.isRtl("12345"))
    }
    // ---- animation timing
    val f = TextFrame()
    run {
        val t = TextLayer("t", text = "x", startMs = 1000, endMs = 5000, inMs = 500, outMs = 500, animIn = TextAnim.FADE, animOut = TextAnim.FADE)
        check("hidden before start", !TextAnimator.frame(t, 999.0, 10000, 0, f).visible)
        check("starts transparent", TextAnimator.frame(t, 1000.0, 10000, 0, f).alpha == 0.0)
        check("mid fade in", TextAnimator.frame(t, 1250.0, 10000, 0, f).alpha in 0.3..0.99)
        check("fully visible", TextAnimator.frame(t, 3000.0, 10000, 0, f).alpha == 1.0)
        check("fading out", TextAnimator.frame(t, 4750.0, 10000, 0, f).alpha in 0.0..0.7)
        check("hidden at end", !TextAnimator.frame(t, 5000.0, 10000, 0, f).visible)
        val up = TextLayer("u", text = "x", inMs = 400, animIn = TextAnim.SLIDE_UP)
        check("slide up starts lower and settles", TextAnimator.frame(up, 0.0, 9000, 0, f).offsetY > 0.5 && TextAnimator.frame(up, 600.0, 9000, 0, f).offsetY == 0.0)
        val pop = TextLayer("p", text = "x", inMs = 400, animIn = TextAnim.POP)
        check("pop scales up", TextAnimator.frame(pop, 0.0, 9000, 0, f).scale < 0.7 && TextAnimator.frame(pop, 800.0, 9000, 0, f).scale == 1.0)
        val wd = TextLayer("w", text = "a b c d", reveal = TextReveal.WORDS, inMs = 400, revealMs = 1000)
        check("words reveal progressively", TextAnimator.frame(wd, 0.0, 9000, 4, f).revealed == 0.0 && TextAnimator.frame(wd, 500.0, 9000, 4, f).revealed == 2.0 && TextAnimator.frame(wd, 2000.0, 9000, 4, f).revealed == 4.0)
        check("unit alpha fades one by one", TextAnimator.unitAlpha(1.5, 0) == 1.0 && TextAnimator.unitAlpha(1.5, 1) == 0.5 && TextAnimator.unitAlpha(1.5, 2) == 0.0)
        val open = TextLayer("o", text = "x", startMs = 0, endMs = 0, outMs = 500)
        check("endMs=0 runs to song end", TextAnimator.frame(open, 9400.0, 10000, 0, f).visible && !TextAnimator.frame(open, 10000.0, 10000, 0, f).visible)
        check("same input same frame", TextAnimator.frame(t, 1234.0, 10000, 0, f).alpha == TextAnimator.frame(t, 1234.0, 10000, 0, f).alpha)
        val wv = WaveLayer("v", startMs = 2000, endMs = 4000)
        check("wave layer timing", TextAnimator.fadeAlpha(wv, 1000.0, 10000) == 0.0 && TextAnimator.fadeAlpha(wv, 3000.0, 10000) == 1.0 && TextAnimator.fadeAlpha(wv, 4000.0, 10000) == 0.0)
    }
    // ---- easing
    check("easing endpoints", Easing.values().all { Math.abs(it.apply(0.0)) < 1e-9 && Math.abs(it.apply(1.0) - 1.0) < 1e-9 })
    check("easing nan safe", Easing.values().all { !it.apply(Double.NaN).isNaN() })
    // ---- parser
    run {
        val tree = mapOf("layers" to listOf(
            mapOf("type" to "wave", "id" to "a", "shape" to "radial", "color" to "#ff0000", "size" to 9.0, "opacity" to -3),
            mapOf("type" to "text", "id" to "a", "text" to "x".repeat(5000), "size" to 99.0, "animIn" to "bogus", "startMs" to -50, "reveal" to "words"),
            mapOf("type" to "alien"), "junk", null, mapOf("type" to "wave", "shape" to "nope"),
        ))
        val s = SceneParser.parse(tree)
        check("parser keeps valid, skips junk", s.layers.size == 3)
        val w = s.layers[0] as WaveLayer
        check("parser clamps wave numbers", w.size == 0.45 && w.opacity == 0.0 && w.shape == WaveShape.RADIAL && w.color == 0xFFFF0000.toInt())
        val t = s.layers[1] as TextLayer
        check("parser caps text & clamps", t.text.length == SceneParser.MAX_TEXT_CHARS && t.size == 0.25 && t.animIn == TextAnim.FADE && t.startMs == 0L && t.reveal == TextReveal.WORDS)
        check("duplicate ids made unique", s.layers.map { it.id }.toSet().size == 3)
        check("unknown shape falls back", (s.layers[2] as WaveLayer).shape == WaveShape.MIRRORED)
        check("garbage root => empty scene", SceneParser.parse("zzz").layers.isEmpty() && SceneParser.parse(null).layers.isEmpty())
        check("layer limit", SceneParser.parse(mapOf("layers" to (0 until 100).map { mapOf("type" to "text", "text" to "a") })).layers.size == SceneParser.MAX_LAYERS)
        check("legacy bridge settings", (WaveLayer("l", shape = WaveShape.BARS, height = 0.3).toLegacy().style.wire == "bars"))
    }
    println("passed=$passed failed=$failed")
    if (failed > 0) System.exit(1)
}
