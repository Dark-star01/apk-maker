package __APP_ID__.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import __APP_ID__.media.ImageInspector
import java.io.File

/**
 * Decodes the project's background picture for the renderer: EXIF orientation applied, decoded at (about) the size
 * the frame needs and never larger, so a 12 MP photo costs a few MB, not 48 MB. Runs on a worker thread, once per
 * picture (not per frame).
 */
internal object BackgroundLoader {
    class Result(val bitmap: Bitmap?, val errorCode: String?, val sourceW: Int, val sourceH: Int, val sample: Int)

    const val ERR_MISSING = "BACKGROUND_MISSING"
    const val ERR_UNREADABLE = "BACKGROUND_UNREADABLE"
    const val ERR_MEMORY = "BACKGROUND_TOO_LARGE"

    fun sampleFor(rotatedW: Int, rotatedH: Int, frameW: Int, frameH: Int): Int = FrameGeometry.decodeSample(rotatedW, rotatedH, frameW, frameH)

    fun load(file: File, frameW: Int, frameH: Int): Result {
        if (!file.isFile) return Result(null, ERR_MISSING, 0, 0, 1)
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Result(null, ERR_UNREADABLE, 0, 0, 1)

        val orientation = ImageInspector.readOrientation(file.path)
        val swap = ImageInspector.swapsAxes(orientation)
        val rw = if (swap) bounds.outHeight else bounds.outWidth
        val rh = if (swap) bounds.outWidth else bounds.outHeight

        var sample = sampleFor(rw, rh, frameW, frameH)
        var attempts = 0
        while (attempts < 4) {
            attempts++
            var decoded: Bitmap? = null
            try {
                val opts = BitmapFactory.Options()
                opts.inSampleSize = sample
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888
                decoded = BitmapFactory.decodeFile(file.path, opts) ?: return Result(null, ERR_UNREADABLE, rw, rh, sample)
                val matrix = ImageInspector.matrixFor(orientation)
                if (matrix == null) return Result(decoded, null, rw, rh, sample)
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (rotated !== decoded) decoded.recycle()
                return Result(rotated, null, rw, rh, sample)
            } catch (e: OutOfMemoryError) {
                decoded?.recycle()
                sample *= 2 // try again at half the size
            }
        }
        return Result(null, ERR_MEMORY, rw, rh, sample)
    }
}
