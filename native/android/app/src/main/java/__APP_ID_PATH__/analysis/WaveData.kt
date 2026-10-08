package __APP_ID__.analysis

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * The analysis result ("wave data"): [count] points, [pointsPerSec] per second (30), one byte per value.
 *
 * [planar] holds four consecutive series of [count] bytes each, in this order:
 *   amplitude | bass | mid | treble          (value 0..255  =  0.0..1.0)
 * Point i is at time i / pointsPerSec seconds. Meaning of the values:
 *   amplitude  loudness (RMS of the whole signal) in that 1/30 s window
 *   bass       RMS of the band below ~250 Hz
 *   mid        RMS of the 250 Hz .. ~4 kHz band
 *   treble     RMS of the band above ~4 kHz
 * Each series is relative to ITS OWN loud level in this track (99th percentile = 1.0), not absolute
 * loudness and not comparable between series or between songs. Silence (< -70 dBFS) stays 0.
 */
internal class WaveData(
    val pointsPerSec: Int,
    val count: Int,
    val durationMs: Long,
    val planar: ByteArray,
)

/** Cache file in the project folder: header + the planar bytes. Valid only for the audio file it was made from. */
internal object WaveCache {
    private const val MAGIC = 0x4D565741 // "MVWA"
    private const val VERSION = 1        // bump when the analysis changes: old caches are then ignored
    private const val MAX_POINTS = 30 * 3600 * 12 // 12 h

    /** Cheap identity of an audio file: size + modified time + CRC of its first and last 64 KB (never reads the whole file). */
    fun fingerprint(file: File): String {
        val size = file.length()
        val crc = CRC32()
        val buf = ByteArray(64 * 1024)
        RandomAccessFile(file, "r").use { raf ->
            val head = raf.read(buf)
            if (head > 0) crc.update(buf, 0, head)
            if (size > buf.size * 2L) {
                raf.seek(size - buf.size)
                val tail = raf.read(buf)
                if (tail > 0) crc.update(buf, 0, tail)
            }
        }
        return "v$VERSION-$size-${file.lastModified()}-${java.lang.Long.toHexString(crc.value)}"
    }

    fun write(target: File, tmp: File, fingerprint: String, data: WaveData) {
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(data.pointsPerSec)
            out.writeInt(data.count)
            out.writeLong(data.durationMs)
            out.writeUTF(fingerprint)
            out.write(data.planar)
        }
        if (target.exists() && !target.delete()) throw java.io.IOException("Cannot replace the cache")
        if (!tmp.renameTo(target)) throw java.io.IOException("Cannot store the cache")
    }

    /** The cached data if it exists, is intact and belongs to [fingerprint]; otherwise null (and a stale/corrupt file is deleted). */
    fun read(file: File, fingerprint: String): WaveData? {
        if (!file.isFile) return null
        try {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                if (inp.readInt() != MAGIC || inp.readInt() != VERSION) throw java.io.IOException("old format")
                val pps = inp.readInt()
                val count = inp.readInt()
                val durationMs = inp.readLong()
                val fp = inp.readUTF()
                if (fp != fingerprint) throw java.io.IOException("another audio file")
                if (pps <= 0 || count < 0 || count > MAX_POINTS) throw java.io.IOException("bad header")
                val bytes = ByteArray(count * 4)
                inp.readFully(bytes)
                if (inp.read() != -1) throw java.io.IOException("trailing data")
                return WaveData(pps, count, durationMs, bytes)
            }
        } catch (e: Exception) { // stale, truncated or corrupt: never trust it
            file.delete()
            return null
        }
    }
}
