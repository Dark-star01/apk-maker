package __APP_ID__.bridge

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResult
import __APP_ID__.audio.AudioEngine
import __APP_ID__.media.MediaException
import __APP_ID__.media.MediaManager
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The single Web <-> Kotlin entry point. JavaScript calls these methods through
 * js/core/bridge.js; it never knows about the engines behind them.
 * Later phases add methods here that delegate to AudioEngine, TimelineEngine,
 * ExportRenderer, etc. (each in its own package).
 *
 * @PluginMethod calls arrive on Capacitor's background thread. Activity results arrive on
 * the UI thread, so anything heavy there is handed to [io].
 */
@CapacitorPlugin(name = "MvmBridge")
class MvmBridgePlugin : Plugin() {

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val media by lazy { MediaManager(context) }
    private val engine by lazy { AudioEngine(context) { snap -> notifyListeners("playback", snapshotJs(snap)) } }

    // ── Diagnostics ─────────────────────────────────────────────────────────

    // JS: await Capacitor.Plugins.MvmBridge.ping({ value: "hello" })
    @PluginMethod
    fun ping(call: PluginCall) {
        val ret = JSObject()
        ret.put("ok", true)
        ret.put("echo", call.getString("value") ?: "")
        ret.put("thread", Thread.currentThread().name)
        call.resolve(ret)
    }

    // Device facts, used later to choose safe preview/export settings.
    @PluginMethod
    fun getInfo(call: PluginCall) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val ret = JSObject()
        ret.put("sdk", Build.VERSION.SDK_INT)
        ret.put("model", Build.MODEL ?: "")
        ret.put("cores", Runtime.getRuntime().availableProcessors())
        ret.put("totalMemMb", mem.totalMem / (1024L * 1024L))
        ret.put("heapLimitMb", am.memoryClass)
        ret.put("appId", context.packageName)
        call.resolve(ret)
    }

    // ── Media (Phase 2) ─────────────────────────────────────────────────────

    // JS: await ...pickMedia({ kind: "audio" | "image", projectId })
    // Resolves { cancelled: true } or { cancelled: false, kind, media: {...} }.
    @PluginMethod
    fun pickMedia(call: PluginCall) {
        val kind = call.getString("kind")
        val projectId = call.getString("projectId")
        if ((kind != KIND_AUDIO && kind != KIND_IMAGE) || projectId.isNullOrBlank()) {
            call.reject("Bad request", MediaException.BAD_REQUEST)
            return
        }

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        if (kind == KIND_AUDIO) {
            // Broad on purpose: providers label m4a/aac inconsistently. The file is validated after the copy.
            intent.type = "audio/*"
        } else {
            intent.type = "*/*"
            intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "image/webp"))
        }
        startActivityForResult(call, intent, "pickMediaResult")
    }

    @ActivityCallback
    private fun pickMediaResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return

        if (result.resultCode != Activity.RESULT_OK) {
            val cancelled = JSObject()
            cancelled.put("cancelled", true)
            call.resolve(cancelled)
            return
        }

        val uri = result.data?.data
        val kind = call.getString("kind")
        val projectId = call.getString("projectId")
        if (uri == null || projectId == null) {
            call.reject("No file was selected", MediaException.READ_FAILED)
            return
        }

        // UI thread here: copying/inspecting must not block it.
        io.execute {
            try {
                val out = JSObject()
                out.put("cancelled", false)
                out.put("kind", kind)
                if (kind == KIND_AUDIO) {
                    val r = media.importAudio(uri, projectId)
                    val m = JSObject()
                    m.put("file", r.file)
                    m.put("name", r.name)
                    m.put("mime", r.mime)
                    m.put("sizeBytes", r.sizeBytes)
                    m.put("durationMs", r.durationMs)
                    m.put("sampleRate", r.sampleRate)
                    m.put("channels", r.channels)
                    out.put("media", m)
                } else {
                    val r = media.importImage(uri, projectId)
                    val m = JSObject()
                    m.put("file", r.file)
                    m.put("thumb", r.thumb)
                    m.put("name", r.name)
                    m.put("mime", r.mime)
                    m.put("sizeBytes", r.sizeBytes)
                    m.put("width", r.width)
                    m.put("height", r.height)
                    out.put("media", m)
                }
                call.resolve(out)
            } catch (t: Throwable) {
                fail(call, t)
            }
        }
    }

    // JS: await ...checkMedia({ projectId, audio?: "audio.mp3", background?: "background.jpg" })
    // Resolves { audio?: boolean, background?: boolean } — false means the file is missing.
    @PluginMethod
    fun checkMedia(call: PluginCall) {
        try {
            val projectId = call.getString("projectId")
                ?: throw MediaException(MediaException.BAD_REQUEST, "projectId is required")
            val out = JSObject()
            val audio = call.getString("audio")
            val background = call.getString("background")
            if (audio != null) out.put("audio", media.exists(projectId, audio))
            if (background != null) out.put("background", media.exists(projectId, background))
            call.resolve(out)
        } catch (t: Throwable) {
            fail(call, t)
        }
    }

    // JS: await ...getThumbnail({ projectId, file }) -> { dataUrl?: string }
    @PluginMethod
    fun getThumbnail(call: PluginCall) {
        try {
            val projectId = call.getString("projectId")
                ?: throw MediaException(MediaException.BAD_REQUEST, "projectId is required")
            val file = call.getString("file")
                ?: throw MediaException(MediaException.BAD_REQUEST, "file is required")
            val out = JSObject()
            val url = media.thumbnailDataUrl(projectId, file)
            if (url != null) out.put("dataUrl", url)
            call.resolve(out)
        } catch (t: Throwable) {
            fail(call, t)
        }
    }

    // JS: await ...removeMedia({ projectId, kind: "audio" | "image" }) — deletes that media's files.
    @PluginMethod
    fun removeMedia(call: PluginCall) {
        try {
            val projectId = call.getString("projectId")
                ?: throw MediaException(MediaException.BAD_REQUEST, "projectId is required")
            val kind = call.getString("kind")
            if (kind != KIND_AUDIO && kind != KIND_IMAGE) throw MediaException(MediaException.BAD_REQUEST, "bad kind")
            media.removeMedia(projectId, kind)
            call.resolve()
        } catch (t: Throwable) {
            fail(call, t)
        }
    }

    // JS: await ...deleteProject({ projectId }) — removes the project's files from disk.
    @PluginMethod
    fun deleteProject(call: PluginCall) {
        try {
            val projectId = call.getString("projectId")
                ?: throw MediaException(MediaException.BAD_REQUEST, "projectId is required")
            media.deleteProject(projectId)
            call.resolve()
        } catch (t: Throwable) {
            fail(call, t)
        }
    }

    // ── Playback (Phase 3) ──────────────────────────────────────────────────
    // All commands run on the main thread inside AudioEngine; every result is the same
    // snapshot shape { state, positionMs, durationMs, error? }. While playing, the same
    // snapshot is pushed to JS as the "playback" event (4 per second).

    // JS: await ...loadAudio({ projectId, file })
    @PluginMethod
    fun loadAudio(call: PluginCall) {
        val projectId = call.getString("projectId")
        val file = call.getString("file")
        engine.runOnMain {
            try {
                if (projectId.isNullOrBlank() || file.isNullOrBlank()) {
                    throw MediaException(MediaException.BAD_REQUEST, "projectId and file are required")
                }
                val f = media.resolve(projectId, file)
                    ?: throw MediaException(MediaException.AUDIO_MISSING, "Audio file is missing")
                engine.load(f) { code ->
                    if (code == null) call.resolve(snapshotJs(engine.snapshot()))
                    else call.reject("Cannot play this audio file", code)
                }
            } catch (t: Throwable) {
                fail(call, t)
            }
        }
    }

    @PluginMethod
    fun play(call: PluginCall) = onMain(call) { engine.play() }

    @PluginMethod
    fun pause(call: PluginCall) = onMain(call) { engine.pause() }

    // JS: await ...seek({ positionMs })
    @PluginMethod
    fun seek(call: PluginCall) {
        val pos = call.getDouble("positionMs")
        onMain(call) {
            if (pos == null || pos.isNaN()) throw MediaException(MediaException.BAD_REQUEST, "positionMs is required")
            engine.seek(pos.toLong())
        }
    }

    @PluginMethod
    fun stop(call: PluginCall) = onMain(call) { engine.stop() }

    @PluginMethod
    fun getPlaybackState(call: PluginCall) = onMain(call) { }

    @PluginMethod
    fun releaseAudio(call: PluginCall) = onMain(call) { engine.release(silent = true) }

    private fun onMain(call: PluginCall, block: () -> Unit) {
        engine.runOnMain {
            try {
                block()
                call.resolve(snapshotJs(engine.snapshot()))
            } catch (t: Throwable) {
                fail(call, t)
            }
        }
    }

    private fun snapshotJs(s: AudioEngine.Snapshot): JSObject {
        val o = JSObject()
        o.put("state", s.state.wire)
        o.put("positionMs", s.positionMs)
        o.put("durationMs", s.durationMs)
        if (s.errorCode != null) o.put("error", s.errorCode)
        return o
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun fail(call: PluginCall, t: Throwable) {
        if (t is MediaException) {
            call.reject(t.message, t.code, t)
        } else {
            Log.e(TAG, "Unexpected bridge error", t)
            call.reject("Unexpected error: " + t.javaClass.simpleName, "UNEXPECTED")
        }
    }

    // No background playback service in the MVP: leaving the app pauses the audio.
    override fun handleOnPause() {
        engine.runOnMain { engine.pauseIfPlaying() }
        super.handleOnPause()
    }

    override fun handleOnDestroy() {
        engine.runOnMain { engine.release(silent = true) }
        io.shutdown()
        super.handleOnDestroy()
    }

    companion object {
        private const val TAG = "MvmBridge"
        private const val KIND_AUDIO = "audio"
        private const val KIND_IMAGE = "image"
    }
}
