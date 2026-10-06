package __APP_ID__.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Where project files live and how they get there.
 *
 * Layout: <app files dir>/projects/<projectId>/{audio.<ext>, background.<ext>, background_thumb.jpg}
 * Project ids and file names arrive from JavaScript, so they are validated before touching the disk.
 * Files are copied with a small fixed buffer (never loaded whole into RAM).
 */
internal class ProjectFiles(context: Context) {
    private val root = File(context.filesDir, "projects")

    fun dir(projectId: String, create: Boolean = false): File {
        requireSafe(projectId)
        val d = File(root, projectId)
        if (create && !d.isDirectory && !d.mkdirs()) {
            throw MediaException(MediaException.STORAGE_FAILED, "Cannot create the project folder")
        }
        return d
    }

    fun file(projectId: String, name: String): File {
        requireSafe(name)
        return File(dir(projectId), name)
    }

    fun deleteProject(projectId: String) {
        dir(projectId).deleteRecursively()
    }

    /** Leftovers of an import that was interrupted (crash / process death). */
    fun cleanTemp(dir: File) {
        dir.listFiles()?.forEach { f -> if (f.name.startsWith(TMP_PREFIX)) f.delete() }
    }

    fun removeByPrefix(dir: File, vararg prefixes: String) {
        dir.listFiles()?.forEach { f ->
            if (prefixes.any { p -> f.name.startsWith(p) }) f.delete()
        }
    }

    /** Streams [uri] into [dest]. On any failure [dest] is deleted and a [MediaException] is thrown. */
    fun copy(resolver: ContentResolver, uri: Uri, dest: File, expectedSize: Long) {
        val parent = dest.parentFile
            ?: throw MediaException(MediaException.STORAGE_FAILED, "Invalid destination")
        if (expectedSize > 0 && parent.usableSpace < expectedSize + SPACE_MARGIN) {
            throw MediaException(MediaException.NO_SPACE, "Not enough storage space")
        }
        try {
            val input = resolver.openInputStream(uri)
                ?: throw MediaException(MediaException.READ_FAILED, "Cannot open the selected file")
            input.use { src ->
                FileOutputStream(dest).use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = src.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
            }
        } catch (e: MediaException) {
            dest.delete()
            throw e
        } catch (e: IOException) {
            dest.delete()
            if (isNoSpace(e)) throw MediaException(MediaException.NO_SPACE, "Not enough storage space", e)
            throw MediaException(MediaException.READ_FAILED, "Cannot copy the file", e)
        } catch (e: SecurityException) {
            dest.delete()
            throw MediaException(MediaException.READ_FAILED, "No permission to read the file", e)
        }
    }

    private fun isNoSpace(e: IOException): Boolean {
        val m = e.message ?: return false
        return m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)
    }

    private fun requireSafe(value: String) {
        if (!SAFE.matches(value)) throw MediaException(MediaException.BAD_REQUEST, "Invalid name")
    }

    companion object {
        const val TMP_PREFIX = "tmp_"
        private const val BUFFER_SIZE = 64 * 1024
        private const val SPACE_MARGIN = 16L * 1024L * 1024L

        // First char alphanumeric => rejects ".", ".." and hidden names; no slashes possible.
        private val SAFE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}\$")
    }
}
