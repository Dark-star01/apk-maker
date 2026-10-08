package __APP_ID__.analysis

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a stream of decoded PCM into one compact feature point per 1/[POINTS_PER_SEC] second.
 * Pure Kotlin (no Android classes) so it can be unit-tested on a plain JVM.
 *
 * Streaming: bytes are consumed as they arrive and only four floats per point are kept
 * (3 min of audio = 5 400 points = ~86 KB). Nothing per-sample is stored or allocated.
 *
 * Per point (see [WaveData] for the output meaning):
 *  - amplitude = RMS of the whole signal in that 1/30 s window
 *  - bass      = RMS of the band below ~250 Hz
 *  - mid       = RMS of the band 250 Hz .. ~4 kHz
 *  - treble    = RMS of the band above ~4 kHz
 * The bands come from four 2nd-order Butterworth filters (12 dB/octave, Q = 1/sqrt 2):
 * bass = low-pass 250 Hz, treble = high-pass 4 kHz, mid = high-pass 250 Hz then low-pass 4 kHz.
 * A pure tone in one band leaks only ~-24 dB into the others. Cheap and stable (no FFT).
 */
internal class PcmAnalyzer(
    private val sampleRate: Int,
    private val channels: Int,
    private val encoding: Int,
) {
    private val bytesPerSample: Int
    private val frameBytes: Int
    private val carry: ByteArray
    private val carryBuf: ByteBuffer
    private var carryLen = 0

    private val bassLp: Biquad
    private val midHp: Biquad
    private val midLp: Biquad
    private val trebleHp: Biquad

    // current window accumulators
    private var curPoint = 0
    private var n = 0
    private var sAll = 0.0
    private var sBass = 0.0
    private var sMid = 0.0
    private var sTreble = 0.0
    private var frames = 0L

    // finished points
    private var cap = 1024
    private var amp = FloatArray(cap)
    private var bass = FloatArray(cap)
    private var mid = FloatArray(cap)
    private var treble = FloatArray(cap)
    private var count = 0

    init {
        require(sampleRate in 8000..384000) { "Unsupported sample rate $sampleRate" }
        require(channels in 1..8) { "Unsupported channel count $channels" }
        bytesPerSample = when (encoding) {
            ENC_16BIT -> 2
            ENC_FLOAT -> 4
            else -> throw IllegalArgumentException("Unsupported PCM encoding $encoding")
        }
        frameBytes = bytesPerSample * channels
        carry = ByteArray(frameBytes)
        carryBuf = ByteBuffer.wrap(carry).order(ByteOrder.LITTLE_ENDIAN)
        val trebleHz = minOf(TREBLE_HZ, sampleRate * 0.45) // keep the corner below Nyquist for very low sample rates
        bassLp = Biquad(sampleRate, BASS_HZ, highPass = false)
        midHp = Biquad(sampleRate, BASS_HZ, highPass = true)
        midLp = Biquad(sampleRate, trebleHz, highPass = false)
        trebleHp = Biquad(sampleRate, trebleHz, highPass = true)
    }

    /** Direct-form-II-transposed Butterworth biquad (state: two doubles; no allocation while running). */
    private class Biquad(sampleRate: Int, fc: Double, highPass: Boolean) {
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double
        private var z1 = 0.0
        private var z2 = 0.0

        init {
            val w0 = 2.0 * Math.PI * fc / sampleRate
            val cosw = cos(w0)
            val alpha = sin(w0) / (2.0 * 0.70710678118654752)
            val a0 = 1.0 + alpha
            if (highPass) {
                b0 = (1.0 + cosw) / 2.0 / a0; b1 = -(1.0 + cosw) / a0; b2 = b0
            } else {
                b0 = (1.0 - cosw) / 2.0 / a0; b1 = (1.0 - cosw) / a0; b2 = b0
            }
            a1 = -2.0 * cosw / a0
            a2 = (1.0 - alpha) / a0
        }

        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    /** Consumes [size] bytes of little-endian PCM starting at absolute index [offset] (buffer position is not used). */
    fun feed(buf: ByteBuffer, offset: Int, size: Int) {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        var pos = offset
        val end = offset + size

        if (carryLen > 0) { // finish a frame that was split across two buffers
            while (carryLen < frameBytes && pos < end) carry[carryLen++] = buf.get(pos++)
            if (carryLen < frameBytes) return
            frame(carryBuf, 0)
            carryLen = 0
        }
        while (pos + frameBytes <= end) {
            frame(buf, pos)
            pos += frameBytes
        }
        while (pos < end) carry[carryLen++] = buf.get(pos++)
    }

    private fun frame(b: ByteBuffer, idx: Int) {
        var x = 0.0
        if (bytesPerSample == 2) {
            for (c in 0 until channels) x += b.getShort(idx + 2 * c) / 32768.0
        } else {
            for (c in 0 until channels) x += b.getFloat(idx + 4 * c).toDouble()
        }
        x /= channels
        if (x.isNaN()) x = 0.0

        val bassSig = bassLp.process(x)
        val midSig = midLp.process(midHp.process(x))
        val trebleSig = trebleHp.process(x)

        val point = ((frames * POINTS_PER_SEC) / sampleRate).toInt()
        if (point != curPoint) { flushWindow(); curPoint = point }
        sAll += x * x
        sBass += bassSig * bassSig
        sMid += midSig * midSig
        sTreble += trebleSig * trebleSig
        n++
        frames++
    }

    private fun flushWindow() {
        if (n > 0) {
            if (curPoint >= cap) grow(curPoint + 1)
            amp[curPoint] = sqrt(sAll / n).toFloat()
            bass[curPoint] = sqrt(sBass / n).toFloat()
            mid[curPoint] = sqrt(sMid / n).toFloat()
            treble[curPoint] = sqrt(sTreble / n).toFloat()
            count = max(count, curPoint + 1)
        }
        n = 0; sAll = 0.0; sBass = 0.0; sMid = 0.0; sTreble = 0.0
    }

    private fun grow(min: Int) {
        var c = cap
        while (c < min) c *= 2
        amp = amp.copyOf(c); bass = bass.copyOf(c); mid = mid.copyOf(c); treble = treble.copyOf(c)
        cap = c
    }

    val framesSeen: Long get() = frames

    /** Flushes the last window and returns the normalised result. */
    fun finish(): WaveData {
        // A last window shorter than ~10 ms is noise from the cut-off: drop it.
        if (n >= sampleRate / 100) flushWindow() else { n = 0; sAll = 0.0; sBass = 0.0; sMid = 0.0; sTreble = 0.0 }
        val out = ByteArray(count * 4)
        normalizeInto(amp, count, out, 0)
        normalizeInto(bass, count, out, count)
        normalizeInto(mid, count, out, 2 * count)
        normalizeInto(treble, count, out, 3 * count)
        val durationMs = frames * 1000L / sampleRate
        return WaveData(POINTS_PER_SEC, count, durationMs, out)
    }

    companion object {
        const val POINTS_PER_SEC = 30
        const val ENC_16BIT = 2 // android.media.AudioFormat.ENCODING_PCM_16BIT
        const val ENC_FLOAT = 4 // android.media.AudioFormat.ENCODING_PCM_FLOAT
        const val BASS_HZ = 250.0
        const val TREBLE_HZ = 4000.0
        private const val SILENCE_RMS = 3.2e-4 // ~ -70 dBFS: below this a series is treated as silence

        /** series / its own 99th percentile (so one loud click does not flatten the rest), clamped to 0..1, as 0..255. */
        private fun normalizeInto(series: FloatArray, count: Int, out: ByteArray, at: Int) {
            if (count == 0) return
            val sorted = series.copyOf(count)
            sorted.sort()
            val p99 = sorted[((count - 1) * 0.99).toInt()].toDouble()
            if (p99 < SILENCE_RMS) return // stays 0
            for (i in 0 until count) {
                val v = (series[i] / p99).coerceIn(0.0, 1.0)
                out[at + i] = Math.round(v * 255.0).toInt().toByte()
            }
        }
    }
}
