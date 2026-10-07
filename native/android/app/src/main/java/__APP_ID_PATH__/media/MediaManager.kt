package __APP_ID__.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.File

/**
 * Imports the user's audio/background into the project folder and answers simple
 * questions about them. All methods block: call them from a background thread.
 *
 * Import is "validate before replace": the new file is copied to a temp name, inspected,
 * and only then swapped in, so a bad pick never destroys the previous file.
 */
class MediaManager(context: Context) {
    private val appContext = context.applicationContext
    private val files = ProjectFiles(appContext)

    class AudioResult(
        val file: String,
        val name: String,
        val mime: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val sampleRate: Int,
        val channels: Int,
    )

    class ImageResult(
        val file: String,
        val thumb: String,
        val name: String,
        val mime: String,
        val sizeBytes: Long,
        val width: Int,
        val height: Int,
    )

    private class SourceInfo(val name: String, val size: Long)

    fun importAudio(uri: Uri, projectId: String): AudioResult {
        val dir = files.dir(projectId, create = true)
        files.cleanTemp(dir)
        val source = queryInfo(uri)

        val tmp = File(dir, "tmp_audio_src")
        try {
            files.copy(appContext.contentResolver, uri, tmp, source.size)
            val info = AudioInspector.inspect(tmp, source.name)

            files.removeByPrefix(dir, "audio.")
            val finalFile = File(dir, "audio." + info.ext)
            if (!tmp.renameTo(finalFile)) {
                throw MediaException(MediaException.STORAGE_FAILED, "Cannot store the audio file")
            }
            return AudioResult(
                file = finalFile.name,
                name = source.name,
                mime = info.mime,
                sizeBytes = finalFile.length(),
                durationMs = info.durationMs,
                sampleRate = info.sampleRate,
                channels = info.channels,
            )
        } finally {
            tmp.delete() // no-op after a successful rename
        }
    }

    fun importImage(uri: Uri, projectId: String): ImageResult {
        val dir = files.dir(projectId, create = true)
        files.cleanTemp(dir)
        val source = queryInfo(uri)

        val tmpImage = File(dir, "tmp_background_src")
        val tmpThumb = File(dir, "tmp_background_thumb")
        try {
            files.copy(appContext.contentResolver, uri, tmpImage, source.size)
            val info = ImageInspector.inspect(tmpImage)
            ImageInspector.writeThumbnail(tmpImage, info.orientation, tmpThumb)

            files.removeByPrefix(dir, "background.", "background_thumb.")
            val finalImage = File(dir, "background." + info.ext)
            val finalThumb = File(dir, "background_thumb.jpg")
            if (!tmpImage.renameTo(finalImage) || !tmpThumb.renameTo(finalThumb)) {
                finalImage.delete()
                finalThumb.delete()
                throw MediaException(MediaException.STORAGE_FAILED, "Cannot store the image")
            }
            return ImageResult(
                file = finalImage.name,
                thumb = finalThumb.name,
                name = source.name,
                mime = info.mime,
                sizeBytes = finalImage.length(),
                width = info.width,
                height = info.height,
            )
        } finally {
            tmpImage.delete()
            tmpThumb.delete()
        }
    }

    /** True when the project file is still there (the user or the OS may have removed it). */
    fun exists(projectId: String, name: String): Boolean {
        val f = files.file(projectId, name)
        return f.isFile && f.length() > 0L
    }

    /** Thumbnail as a data URL (small: ~20-60 KB), or null if it is missing. */
    fun thumbnailDataUrl(projectId: String, name: String): String? {
        val f = files.file(projectId, name)
        if (!f.isFile || f.length() <= 0L || f.length() > MAX_THUMB_BYTES) return null
        return "data:image/jpeg;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
    }

    /** Removes one imported media kind ("audio" or "image") from the project folder. */
    fun removeMedia(projectId: String, kind: String) {
        val dir = files.dir(projectId)
        if (!dir.isDirectory) return
        if (kind == "audio") files.removeByPrefix(dir, "audio.") else files.removeByPrefix(dir, "background.", "background_thumb.")
    }

    fun deleteProject(projectId: String) {
        files.deleteProject(projectId)
    }

    private fun queryInfo(uri: Uri): SourceInfo {
        var name: String? = null
        var size = -1L
        try {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            appContext.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !c.isNull(nameIndex)) name = c.getString(nameIndex)
                    val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = c.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            // Name and size are optional hints.
        }
        return SourceInfo(name ?: uri.lastPathSegment ?: "file", size)
    }

    companion object {
        private const val MAX_THUMB_BYTES = 2L * 1024L * 1024L
    }
}
