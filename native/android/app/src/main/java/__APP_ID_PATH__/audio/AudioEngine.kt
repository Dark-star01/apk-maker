package __APP_ID__.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import __APP_ID__.media.MediaException
import __APP_ID__.render.PlaybackClock
import __APP_ID__.timeline.RenderState
import java.io.File

/**
 * Plays the project's audio with the platform MediaPlayer (no extra dependency): the file is
 * streamed from disk, never loaded into memory. It is also the timeline clock.
 *
 * Threading: every method must be called on the main thread (use [runOnMain]); listeners fire there too.
 */
internal class AudioEngine(
    context: Context,
    private val onChange: (Snapshot) -> Unit,
) {
    enum class State(val wire: String) {
        IDLE("idle"), READY("ready"), PLAYING("playing"), PAUSED("paused"), ENDED("ended"), ERROR("error"),
    }

    class Snapshot(val state: State, val positionMs: Long, val durationMs: Long, val errorCode: String?)

    private val main = Handler(Looper.getMainLooper())

    /** The time the native Renderer follows (published on every state change and every tick while playing). */
    val clock = PlaybackClock()
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var player: MediaPlayer? = null
    private var state = State.IDLE
    private var durationMs = 0L
    private var seekTargetMs = -1L // shown as the position until the seek really completes
    private var errorCode: String? = null
    private var hasFocus = false

    private val ticker = object : Runnable {
        override fun run() {
            if (state == State.PLAYING) {
                emit()
                main.postDelayed(this, TICK_MS)
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pauseIfPlaying()
    }

    fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else main.post(block)
    }

    // ── State ───────────────────────────────────────────────────────────────

    fun snapshot(): Snapshot = Snapshot(state, positionMs(), durationMs, errorCode)

    fun renderState(): RenderState = RenderState(positionMs(), durationMs, state == State.PLAYING)

    private fun positionMs(): Long {
        if (state == State.ENDED) return durationMs
        if (seekTargetMs >= 0) return seekTargetMs
        val p = player ?: return 0L
        return try { p.currentPosition.toLong().coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE) } catch (e: IllegalStateException) { 0L }
    }

    private fun emit() {
        val snap = snapshot()
        clock.sync(snap.positionMs, snap.state == State.PLAYING, snap.durationMs, SystemClock.elapsedRealtime())
        onChange(snap)
    }

    // ── Commands ────────────────────────────────────────────────────────────

    /** Loads [file]. [done] gets null on success or the failure code. */
    fun load(file: File, done: (String?) -> Unit) {
        releasePlayer()
        val p = MediaPlayer()
        player = p
        errorCode = null
        durationMs = 0L
        seekTargetMs = -1L
        state = State.IDLE
        var finished = false
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            p.setOnPreparedListener {
                if (player !== p) return@setOnPreparedListener
                durationMs = try { p.duration.toLong().coerceAtLeast(0L) } catch (e: IllegalStateException) { 0L }
                state = State.READY
                finished = true
                emit()
                done(null)
            }
            p.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                if (player === p) {
                    if (!finished) {
                        finished = true
                        failPlayer(MediaException.AUDIO_UNPLAYABLE, silent = true)
                        done(MediaException.AUDIO_UNPLAYABLE)
                    } else {
                        failPlayer(MediaException.PLAYER_FAILED, silent = false)
                    }
                }
                true
            }
            p.setOnCompletionListener {
                if (player !== p) return@setOnCompletionListener
                state = State.ENDED
                seekTargetMs = -1L
                abandonFocus()
                emit()
            }
            p.setOnSeekCompleteListener {
                if (player !== p) return@setOnSeekCompleteListener
                seekTargetMs = -1L
                emit()
            }
            p.setDataSource(file.absolutePath)
            p.prepareAsync()
        } catch (t: Throwable) {
            Log.e(TAG, "load failed", t)
            if (!finished) {
                finished = true
                failPlayer(MediaException.AUDIO_UNPLAYABLE, silent = true)
                done(MediaException.AUDIO_UNPLAYABLE)
            }
        }
    }

    fun play() {
        val p = player
        if (p == null || state == State.IDLE || state == State.ERROR) {
            throw MediaException(MediaException.NO_AUDIO, "No audio loaded")
        }
        try {
            if (state == State.ENDED) { seekInternal(p, 0L) }
            requestFocus()
            p.start()
            state = State.PLAYING
            main.removeCallbacks(ticker)
            main.postDelayed(ticker, TICK_MS)
            emit()
        } catch (e: IllegalStateException) {
            failPlayer(MediaException.PLAYER_FAILED, silent = false)
            throw MediaException(MediaException.PLAYER_FAILED, "Player is in a bad state", e)
        }
    }

    fun pause() {
        val p = player ?: return
        if (state != State.PLAYING) return
        try { p.pause() } catch (e: IllegalStateException) { /* state is corrected below */ }
        state = State.PAUSED
        main.removeCallbacks(ticker)
        abandonFocus()
        emit()
    }

    fun pauseIfPlaying() {
        if (state == State.PLAYING) pause()
    }

    fun seek(positionMs: Long) {
        val p = player
        if (p == null || state == State.IDLE || state == State.ERROR) {
            throw MediaException(MediaException.NO_AUDIO, "No audio loaded")
        }
        val target = positionMs.coerceIn(0L, durationMs)
        try {
            seekInternal(p, target)
        } catch (e: IllegalStateException) {
            failPlayer(MediaException.PLAYER_FAILED, silent = false)
            throw MediaException(MediaException.PLAYER_FAILED, "Player is in a bad state", e)
        }
        if (state == State.ENDED) state = if (target > 0L) State.PAUSED else State.READY
        emit()
    }

    /** Pause and go back to 00:00. */
    fun stop() {
        pauseIfPlaying()
        if (player != null && state != State.IDLE && state != State.ERROR) {
            seek(0L)
            state = State.READY
            emit()
        }
    }

    /** Frees the player (project closed, audio removed/replaced, app closing). */
    fun release(silent: Boolean) {
        releasePlayer()
        durationMs = 0L
        seekTargetMs = -1L
        errorCode = null
        state = State.IDLE
        if (!silent) emit()
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun seekInternal(p: MediaPlayer, target: Long) {
        seekTargetMs = target
        if (Build.VERSION.SDK_INT >= 26) p.seekTo(target, MediaPlayer.SEEK_CLOSEST) else p.seekTo(target.toInt())
    }

    private fun failPlayer(code: String, silent: Boolean) {
        main.removeCallbacks(ticker)
        abandonFocus()
        try { player?.release() } catch (e: Exception) { /* already unusable */ }
        player = null
        state = State.ERROR
        errorCode = code
        seekTargetMs = -1L
        if (!silent) emit()
    }

    private fun releasePlayer() {
        main.removeCallbacks(ticker)
        abandonFocus()
        val p = player
        player = null // listeners compare against this, so late callbacks from the old player are ignored
        if (p != null) {
            try { p.setOnPreparedListener(null); p.setOnErrorListener(null); p.setOnCompletionListener(null); p.setOnSeekCompleteListener(null) } catch (e: Exception) { /* ignore */ }
            try { p.release() } catch (e: Exception) { /* ignore */ }
        }
    }

    @Suppress("DEPRECATION")
    private fun requestFocus() {
        if (hasFocus) return
        // Playing still works if focus is denied, so the result is not checked.
        audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        hasFocus = true
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        if (!hasFocus) return
        hasFocus = false
        audioManager.abandonAudioFocus(focusListener)
    }

    companion object {
        private const val TAG = "MvmAudio"
        private const val TICK_MS = 250L
    }
}
