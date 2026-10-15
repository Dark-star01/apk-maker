package __APP_ID__.exporter

import java.io.File

/** Human-readable, filesystem-safe output names. Never reuses an existing name (a previous export is never overwritten). */
internal object ExportNaming {
    const val TMP_PREFIX = "tmp_export_"

    fun baseName(title: String?): String {
        val cleaned = (title ?: "").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trim('.')
        val cut = if (cleaned.length > 60) cleaned.substring(0, 60).trim() else cleaned
        return if (cut.isEmpty()) "Music Video" else cut
    }

    /** "Title.mp4", then "Title (2).mp4", "Title (3).mp4"... */
    fun uniqueName(dir: File, title: String?): String {
        val base = baseName(title)
        var name = "$base.mp4"
        var n = 2
        while (File(dir, name).exists()) { name = "$base ($n).mp4"; n++ }
        return name
    }

    fun tmpVideo(stamp: Long) = "${TMP_PREFIX}${stamp}_video.mp4"
    fun tmpAudio(stamp: Long) = "${TMP_PREFIX}${stamp}_audio.m4a"
    fun tmpFinal(stamp: Long) = "${TMP_PREFIX}${stamp}_final.mp4"

    /** Removes leftovers of an interrupted export (only files this feature created). */
    fun cleanTemp(dir: File) {
        dir.listFiles()?.forEach { f -> if (f.isFile && f.name.startsWith(TMP_PREFIX)) f.delete() }
    }
}
