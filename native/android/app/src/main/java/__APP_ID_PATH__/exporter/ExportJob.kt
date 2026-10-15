package __APP_ID__.exporter

/** Thrown at a checkpoint when the user cancelled. Never shown as an error. */
internal class ExportCancelled : RuntimeException("cancelled")

/** An export failure with a stable [code] (mapped to a user-facing message by the web layer). */
internal class ExportException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause) {
    companion object {
        const val BUSY = "EXPORT_BUSY"
        const val BAD_REQUEST = "EXPORT_BAD_REQUEST"
        const val NO_AUDIO = "EXPORT_NO_AUDIO"
        const val AUDIO_MISSING = "EXPORT_AUDIO_MISSING"
        const val NO_BACKGROUND = "EXPORT_NO_BACKGROUND"
        const val BACKGROUND_MISSING = "EXPORT_BACKGROUND_MISSING"
        const val BACKGROUND_UNREADABLE = "EXPORT_BACKGROUND_UNREADABLE"
        const val NO_SPACE = "EXPORT_NO_SPACE"
        const val ENCODER_UNSUPPORTED = "EXPORT_ENCODER_UNSUPPORTED"
        const val AUDIO_UNSUPPORTED = "EXPORT_AUDIO_UNSUPPORTED"
        const val AUDIO_FAILED = "EXPORT_AUDIO_FAILED"
        const val VIDEO_FAILED = "EXPORT_VIDEO_FAILED"
        const val MUXER_FAILED = "EXPORT_MUXER_FAILED"
        const val VALIDATION_FAILED = "EXPORT_VALIDATION_FAILED"
        const val UNEXPECTED = "EXPORT_UNEXPECTED"
    }
}

/**
 * One export run: cancel/pause flags (checked by the worker at [checkpoint]) and the status the web layer polls.
 * All status reads/writes go through one lock, so a snapshot is always consistent.
 */
internal class ExportJob {
    class Snapshot(
        val stage: ExportPlan.Stage, val percent: Int, val frame: Int, val frames: Int,
        val width: Int, val height: Int, val fps: Int,
        val outputPath: String?, val outputName: String?, val sizeBytes: Long, val durationMs: Long,
        val errorCode: String?, val errorMessage: String?, val detail: String?,
    )

    private val lock = Any()
    @Volatile private var cancelled = false
    @Volatile private var paused = false
    private var stage = ExportPlan.Stage.IDLE
    private var percent = 0
    private var frame = 0; private var frames = 0
    private var width = 0; private var height = 0; private var fps = 0
    private var outputPath: String? = null; private var outputName: String? = null
    private var sizeBytes = 0L; private var durationMs = 0L
    private var errorCode: String? = null; private var errorMessage: String? = null; private var detail: String? = null

    val isCancelled: Boolean get() = cancelled
    fun cancel() { cancelled = true }
    fun setPaused(p: Boolean) { paused = p }

    /** Called by the worker between units of work: waits while paused, throws [ExportCancelled] once cancelled. */
    fun checkpoint() {
        while (paused && !cancelled) { try { Thread.sleep(50) } catch (e: InterruptedException) { cancelled = true } }
        if (cancelled) throw ExportCancelled()
    }

    fun setFormat(w: Int, h: Int, f: Int, totalFrames: Int) = synchronized(lock) { width = w; height = h; fps = f; frames = totalFrames }

    /** Progress never goes backwards and never leaves the current stage's range. */
    fun progress(s: ExportPlan.Stage, fraction: Double, currentFrame: Int = frame, note: String? = null) = synchronized(lock) {
        if (stage.terminal) return@synchronized
        val p = ExportPlan.percent(s, fraction)
        if (s != stage || p > percent) percent = if (s == stage) Math.max(percent, p) else p
        stage = s; frame = currentFrame; if (note != null) detail = note
    }

    fun complete(path: String, name: String, size: Long, durMs: Long) = synchronized(lock) {
        stage = ExportPlan.Stage.COMPLETED; percent = 100; outputPath = path; outputName = name; sizeBytes = size; durationMs = durMs
        errorCode = null; errorMessage = null
    }

    fun fail(code: String, message: String) = synchronized(lock) {
        stage = ExportPlan.Stage.FAILED; errorCode = code; errorMessage = message; outputPath = null; outputName = null
    }

    fun markCancelled() = synchronized(lock) { stage = ExportPlan.Stage.CANCELLED; outputPath = null; outputName = null }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(stage, percent, frame, frames, width, height, fps, outputPath, outputName, sizeBytes, durationMs, errorCode, errorMessage, detail)
    }
}
