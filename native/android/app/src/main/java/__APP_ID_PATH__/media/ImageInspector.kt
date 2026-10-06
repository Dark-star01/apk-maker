package __APP_ID__.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Validates an imported image and makes a small thumbnail. The full-size image is never
 * decoded: only its header is read, and the thumbnail is decoded with inSampleSize.
 */
internal object ImageInspector {
    private const val THUMB_MAX = 480
    private const val THUMB_QUALITY = 82
    private const val MAX_PIXELS = 200_000_000L // ~200 MP; beyond this the device is at risk

    class Info(
        val ext: String,
        val mime: String,
        val width: Int,  // after applying EXIF orientation
        val height: Int, // after applying EXIF orientation
        val orientation: Int,
    )

    fun inspect(file: File): Info {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.path, bounds)

        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        val mime = bounds.outMimeType
        if (rawW <= 0 || rawH <= 0 || mime == null) {
            throw MediaException(MediaException.BAD_IMAGE, "Cannot read the image")
        }
        val ext = when (mime) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> throw MediaException(MediaException.UNSUPPORTED_IMAGE, "Unsupported image format: $mime")
        }
        if (rawW.toLong() * rawH.toLong() > MAX_PIXELS) {
            throw MediaException(MediaException.IMAGE_TOO_LARGE, "Image is too large")
        }

        val orientation = readOrientation(file.path)
        val swap = swapsAxes(orientation)
        return Info(
            ext = ext,
            mime = mime,
            width = if (swap) rawH else rawW,
            height = if (swap) rawW else rawH,
            orientation = orientation,
        )
    }

    /** Writes a JPEG thumbnail (long side <= 480 px, orientation applied) to [out]. */
    fun writeThumbnail(src: File, orientation: Int, out: File) {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(src.path, bounds)
        val longSide = max(bounds.outWidth, bounds.outHeight)

        // Largest power-of-two subsample that keeps the long side >= THUMB_MAX.
        var sample = 1
        while (longSide / (sample * 2) >= THUMB_MAX) sample *= 2

        // `bmp` is always the live bitmap (non-null); `toRecycle` mirrors it for the cleanup in `finally`.
        var toRecycle: Bitmap? = null
        try {
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            var bmp: Bitmap = BitmapFactory.decodeFile(src.path, opts)
                ?: throw MediaException(MediaException.BAD_IMAGE, "Cannot decode the image")
            toRecycle = bmp

            val matrix = matrixFor(orientation)
            if (matrix != null) {
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                if (rotated !== bmp) bmp.recycle()
                bmp = rotated
                toRecycle = bmp
            }

            val scale = THUMB_MAX.toFloat() / max(bmp.width, bmp.height).toFloat()
            if (scale < 1f) {
                val w = max(1, (bmp.width * scale).roundToInt())
                val h = max(1, (bmp.height * scale).roundToInt())
                val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
                toRecycle = bmp
            }

            val finalBitmap: Bitmap = bmp
            val ok = FileOutputStream(out).use { stream ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, stream)
            }
            if (!ok) throw MediaException(MediaException.STORAGE_FAILED, "Cannot write the thumbnail")
        } catch (e: OutOfMemoryError) {
            out.delete()
            throw MediaException(MediaException.IMAGE_TOO_LARGE, "Not enough memory to read the image")
        } finally {
            toRecycle?.recycle()
        }
    }

    private fun readOrientation(path: String): Int = try {
        ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun swapsAxes(o: Int): Boolean = o == 5 || o == 6 || o == 7 || o == 8

    private fun matrixFor(o: Int): Matrix? {
        val m = Matrix()
        when (o) {
            2 -> m.setScale(-1f, 1f)
            3 -> m.setRotate(180f)
            4 -> m.setScale(1f, -1f)
            5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.setRotate(90f)
            7 -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            8 -> m.setRotate(-90f)
            else -> return null
        }
        return m
    }
}
