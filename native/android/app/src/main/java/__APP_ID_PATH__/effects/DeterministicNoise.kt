package __APP_ID__.effects

/** Seeded, stateless 1-D value noise in [-1, 1]. Same (seed, x) = same value on every device and run. */
internal object DeterministicNoise {
    private fun hash(seed: Int, i: Long): Double {
        var x = i * -7046029254386353131L + seed.toLong() * 7146057691288625177L
        x = x xor (x ushr 33); x *= -49064778989728563L
        x = x xor (x ushr 33); x *= -4265267296055464877L
        x = x xor (x ushr 33)
        return (x ushr 11).toDouble() / 9007199254740992.0 * 2.0 - 1.0
    }

    /** Smooth noise: lattice values joined with a smoothstep, so it never jumps. */
    fun noise(seed: Int, x: Double): Double {
        val f = Math.floor(x)
        val i = f.toLong()
        val t = x - f
        val s = t * t * (3.0 - 2.0 * t)
        val a = hash(seed, i)
        return a + (hash(seed, i + 1) - a) * s
    }
}
