package __APP_ID__.exporter

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Makes a finished export reachable by other apps without any storage permission: on Android 10+ the file is copied
 * into the shared Movies/Dark Studio folder through MediaStore (the supported mechanism) and the resulting content
 * Uri is used for Share / Open. The app-private original stays where it is.
 */
internal object ExportShare {
    const val UNSUPPORTED = "EXPORT_SHARE_UNSUPPORTED"
    private val published = HashMap<String, Uri>()

    fun publish(context: Context, file: File): Uri {
        synchronized(published) { published[file.path]?.let { return it } }
        if (Build.VERSION.SDK_INT < 29) throw ExportException(UNSUPPORTED, "Sharing needs Android 10 or newer. The video is stored in the app: ${file.path}")
        val resolver = context.contentResolver
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Dark Studio")
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw ExportException(UNSUPPORTED, "The system refused to create the shared video")
        try {
            val out = resolver.openOutputStream(uri) ?: throw ExportException(UNSUPPORTED, "Cannot open the shared video")
            out.use { o -> file.inputStream().use { i -> i.copyTo(o, 64 * 1024) } }
            val done = ContentValues()
            done.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (x: Exception) { }
            if (e is ExportException) throw e
            throw ExportException(UNSUPPORTED, "Cannot copy the video to Movies: " + e.javaClass.simpleName, e)
        }
        synchronized(published) { published[file.path] = uri }
        return uri
    }
}
