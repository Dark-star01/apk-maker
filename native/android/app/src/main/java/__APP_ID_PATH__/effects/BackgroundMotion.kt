package __APP_ID__.effects

/** Camera path of the background: a pure function of time (and track length). No audio, except PULSE_ZOOM (added by the engine). */
internal class Motion(val scale: Double, val tx: Double, val ty: Double, val rotDeg: Double)

internal object BackgroundMotion {
    private const val TAU = 2.0 * Math.PI
    private const val NO_DURATION_RAMP_SEC = 60.0

    /** Progress 0..1 through the song (or through a 60 s ramp when the length is unknown). */
    private fun progress(tSec: Double, durationSec: Double): Double =
        Math.min(1.0, Math.max(0.0, if (durationSec > 0) tSec / durationSec else tSec / NO_DURATION_RAMP_SEC))

    fun at(mode: MotionMode, tSec: Double, durationSec: Double): Motion = when (mode) {
        MotionMode.STATIC, MotionMode.PULSE_ZOOM -> Motion(1.0, 0.0, 0.0, 0.0)
        MotionMode.SLOW_ZOOM -> Motion(1.0 + 0.08 * progress(tSec, durationSec), 0.0, 0.0, 0.0)
        MotionMode.FLOAT -> Motion(
            1.0,
            0.012 * Math.sin(TAU * tSec / 13.0),
            0.009 * Math.sin(TAU * tSec / 17.0 + 1.3),
            0.4 * Math.sin(TAU * tSec / 23.0 + 0.7),
        )
        MotionMode.CINEMATIC_DRIFT -> Motion(
            1.0 + 0.10 * progress(tSec, durationSec),
            0.020 * Math.sin(TAU * tSec / 37.0),
            0.012 * Math.sin(TAU * tSec / 53.0 + 1.0),
            0.6 * Math.sin(TAU * tSec / 71.0 + 2.0),
        )
    }
}
