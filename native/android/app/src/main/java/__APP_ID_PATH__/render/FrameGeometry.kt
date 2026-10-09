package __APP_ID__.render

/**
 * The picture's placement in the frame. Pure maths (no Android classes), shared by Preview and, later, Export.
 *
 * Order (fixed and documented): the image is first "cover-fitted" to the frame (uniform scale, centred, no
 * stretching), then, about the FRAME CENTRE:  translate  ->  rotate  ->  scale   (EffectState values).
 *   p' = C + T + scale * R(theta) * s0 * (p - imageCentre)
 * where C is the frame centre, T = (translateX * W, translateY * H), s0 = max(W / iw, H / ih).
 *
 * Output matrix layout = android.graphics.Matrix.setValues():  x' = m0*x + m1*y + m2,  y' = m3*x + m4*y + m5.
 * Positive rotation is clockwise on screen (y axis points down).
 */
internal object FrameGeometry {

    /**
     * Fills [out] (9 floats) with the image -> frame matrix. Returns the extra zoom factor (>= 1) that the safety net
     * had to apply so the transformed image still covers every frame corner (1.0 in normal operation: the
     * EffectEngine already guarantees it; this guards against rounding and against a frame/aspect mismatch).
     * Returns 0.0 (and an identity matrix) if the frame or the image has no size.
     */
    fun compute(
        frameW: Int, frameH: Int, imgW: Int, imgH: Int,
        scale: Double, rotationDeg: Double, translateX: Double, translateY: Double,
        out: FloatArray,
    ): Double {
        if (frameW <= 0 || frameH <= 0 || imgW <= 0 || imgH <= 0) {
            identity(out)
            return 0.0
        }
        val w = frameW.toDouble(); val h = frameH.toDouble()
        val iw = imgW.toDouble(); val ih = imgH.toDouble()
        val s0 = Math.max(w / iw, h / ih)
        val sc = if (scale.isNaN() || scale < 1.0) 1.0 else scale
        val th = Math.toRadians(if (rotationDeg.isNaN()) 0.0 else rotationDeg)
        val cos = Math.cos(th); val sin = Math.sin(th)
        val tx = (if (translateX.isNaN()) 0.0 else translateX) * w
        val ty = (if (translateY.isNaN()) 0.0 else translateY) * h
        val cx = w / 2.0; val cy = h / 2.0

        var s = s0 * sc

        // Safety net: every frame corner, mapped back into the image, must lie inside the image rectangle.
        var k = 1.0
        for (i in 0 until 4) {
            val dx = (if (i and 1 == 0) 0.0 else w) - cx - tx
            val dy = (if (i and 2 == 0) 0.0 else h) - cy - ty
            val ux = Math.abs(cos * dx + sin * dy) / s   // image-space offset from the image centre
            val uy = Math.abs(-sin * dx + cos * dy) / s
            k = Math.max(k, Math.max(ux / (iw / 2.0), uy / (ih / 2.0)))
        }
        s *= k * COVER_MARGIN // 0.05 % extra so float rounding can never leave a 1-pixel sliver at an edge

        val icx = iw / 2.0; val icy = ih / 2.0
        out[0] = (s * cos).toFloat()
        out[1] = (-s * sin).toFloat()
        out[2] = (cx + tx - s * (cos * icx - sin * icy)).toFloat()
        out[3] = (s * sin).toFloat()
        out[4] = (s * cos).toFloat()
        out[5] = (cy + ty - s * (sin * icx + cos * icy)).toFloat()
        out[6] = 0f; out[7] = 0f; out[8] = 1f
        return k
    }

    const val COVER_MARGIN = 1.0005

    /** Headroom for the zoom the effects apply (scale up to ~1.25) so zoomed frames stay sharp. */
    private const val DECODE_HEADROOM = 1.3
    private const val DECODE_MAX_PIXELS = 6_000_000L // ~24 MB as ARGB_8888: a hard ceiling for 4 GB devices

    /**
     * Largest power-of-two subsampling (BitmapFactory inSampleSize) that still keeps the picture >= 1.3x what a
     * cover-fit of the frame needs, and never decodes more than 6 megapixels (the cap wins over sharpness).
     */
    fun decodeSample(rotatedW: Int, rotatedH: Int, frameW: Int, frameH: Int): Int {
        if (rotatedW <= 0 || rotatedH <= 0 || frameW <= 0 || frameH <= 0) return 1
        val need = Math.max(frameW.toDouble() / rotatedW, frameH.toDouble() / rotatedH) * DECODE_HEADROOM
        var sample = 1
        while (1.0 / (sample * 2) >= need && Math.max(rotatedW, rotatedH) / (sample * 2) >= 1) sample *= 2
        while ((rotatedW.toLong() / sample) * (rotatedH.toLong() / sample) > DECODE_MAX_PIXELS) sample *= 2
        return sample
    }

    fun identity(out: FloatArray) {
        java.util.Arrays.fill(out, 0f)
        out[0] = 1f; out[4] = 1f; out[8] = 1f
    }
}
