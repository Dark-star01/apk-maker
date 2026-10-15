package __APP_ID__.exporter

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/** The H.264 encoder (and exact frame size / bitrate) chosen for this device. */
internal class EncoderChoice(val name: String, val width: Int, val height: Int, val bitrate: Int, val software: Boolean)

/** Finds an AVC encoder that really supports the requested size and frame rate on THIS device (never assumed). */
internal object EncoderSelector {
    const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    /** Candidates in order of preference: hardware first, then software. Each already checked against the capabilities. */
    fun candidates(want: ExportPlan.Dims, fps: Int, bitrate: Int): List<EncoderChoice> {
        val out = ArrayList<EncoderChoice>()
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        for (info in infos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }) continue
            val caps = try { info.getCapabilitiesForType(MIME) } catch (e: Exception) { continue }
            if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) continue
            val vc = caps.videoCapabilities ?: continue
            val software = isSoftware(info.name)
            // exactly the requested size first, then the size snapped to this encoder's alignment
            val sizes = ArrayList<ExportPlan.Dims>()
            sizes.add(want)
            val aligned = ExportPlan.alignTo(want, vc.widthAlignment, vc.heightAlignment)
            if (aligned.width != want.width || aligned.height != want.height) sizes.add(aligned)
            for (d in sizes) {
                val ok = try { vc.isSizeSupported(d.width, d.height) && vc.areSizeAndRateSupported(d.width, d.height, fps.toDouble()) } catch (e: Exception) { false }
                if (ok) {
                    val br = try { vc.bitrateRange.clamp(bitrate) } catch (e: Exception) { bitrate }
                    out.add(EncoderChoice(info.name, d.width, d.height, br, software))
                    break
                }
            }
        }
        return out.sortedBy { it.software }
    }

    private fun isSoftware(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.endsWith(".sw")
    }
}
