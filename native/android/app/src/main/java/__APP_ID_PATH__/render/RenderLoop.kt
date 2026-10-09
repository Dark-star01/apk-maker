package __APP_ID__.render

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One rendering thread with an explicit lifecycle. No Android classes (testable on a JVM).
 *
 *   create (idle) -> setOutput(surface) [thread starts] -> render ... -> pause / resume -> setOutput(null) / release [thread ends]
 *
 * The thread exists only while an [Output] is attached, and sleeps (no CPU) when paused, when not playing and no frame
 * was requested. While playing it draws continuously, paced to [fps] (30, auto-raised to 60 only if frames are very cheap).
 * The time of each frame comes from [timeSource] (the AudioEngine clock) at the moment the frame starts.
 */
internal class RenderLoop(
    private val timeSource: () -> Double,
    private val maxFps: Int = 60,
    private val onEvent: (String) -> Unit = {},
) {
    /** The surface side. [draw] may throw; it is only ever called from the render thread, never after setOutput(null) returned. */
    interface Output { fun draw(timeMs: Double) }

    enum class State { IDLE, RUNNING, PAUSED, FAILED, RELEASED }

    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    private val drawLock = ReentrantLock()

    // guarded by [lock]
    private var thread: Thread? = null
    private var wanted = false
    private var playing = false
    private var paused = false
    private var requested = false
    private var failed = false
    private var released = false
    @Volatile private var output: Output? = null

    @Volatile var fps = 30
        private set
    @Volatile var framesDrawn = 0L
        private set
    @Volatile var lastTimeMs = 0.0
        private set
    @Volatile var lastDrawMs = 0.0
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var measuredFps = 0
        private set

    val state: State
        get() = lock.withLock { lockedState() }

    private fun lockedState(): State = when {
        released -> State.RELEASED
        failed -> State.FAILED
        output == null -> State.IDLE
        paused -> State.PAUSED
        else -> State.RUNNING
    }

    val hasThread: Boolean get() = lock.withLock { thread?.isAlive == true }

    /** Attach (or, with null, detach) the surface. Detaching returns only after any draw in progress has finished. */
    fun setOutput(o: Output?) {
        var toJoin: Thread? = null
        lock.lock()
        try {
            if (released) return
            output = o
            if (o != null) {
                failed = false
                lastError = null
                requested = true
                if (thread == null || thread?.isAlive != true) {
                    wanted = true
                    val t = Thread({ run() }, "mvm-render")
                    t.isDaemon = true
                    thread = t
                    t.start()
                }
                wake.signalAll()
            } else {
                wanted = false
                toJoin = thread
                thread = null
                wake.signalAll()
            }
        } finally { lock.unlock() }
        if (o == null) {
            drawLock.lock(); drawLock.unlock() // wait for an in-progress draw to leave the surface alone
            if (toJoin != null && toJoin !== Thread.currentThread()) toJoin.join(1000)
        }
    }

    /** Continuous drawing on/off (follows the audio: playing). */
    fun setPlaying(p: Boolean) {
        lock.lock()
        try { playing = p; if (!p) requested = true /* one last frame at the final position */; wake.signalAll() } finally { lock.unlock() }
    }

    /** Draw one frame soon (seek, setting change, new picture...). Cheap; many calls collapse into one frame. */
    fun requestFrame() {
        lock.lock()
        try { requested = true; wake.signalAll() } finally { lock.unlock() }
    }

    /** Activity paused / app in background: stop drawing (thread sleeps). */
    fun pause() { lock.lock(); try { paused = true; wake.signalAll() } finally { lock.unlock() } }

    /** Back in the foreground: continue from the CURRENT audio time. */
    fun resume() { lock.lock(); try { paused = false; requested = true; wake.signalAll() } finally { lock.unlock() } }

    /** Final. Stops the thread; the loop cannot be used again. */
    fun release() {
        setOutput(null)
        lock.lock(); try { released = true; wanted = false; wake.signalAll() } finally { lock.unlock() }
    }

    private fun run() {
        var emaMs = 0.0
        var sinceChange = 0
        var nextDeadline = 0L
        var consecutiveFailures = 0
        try {
            while (true) {
                var continuous: Boolean
                lock.lock()
                try {
                    while (true) {
                        if (!wanted) return
                        val ready = output != null && !paused && !failed && (playing || requested)
                        if (ready) break
                        wake.await()
                    }
                    requested = false
                    continuous = playing
                } finally { lock.unlock() }

                val start = System.nanoTime()
                drawLock.lock()
                try {
                    val out = output
                    if (out != null) {
                        val t = timeSource()
                        try {
                            out.draw(t)
                            lastTimeMs = t
                            framesDrawn++
                            consecutiveFailures = 0
                        } catch (e: Throwable) {
                            consecutiveFailures++
                            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
                            if (consecutiveFailures == 1) onEvent("draw failed: $lastError")
                            if (consecutiveFailures >= MAX_FAILURES) {
                                lock.lock(); try { failed = true } finally { lock.unlock() }
                                onEvent("renderer stopped after $MAX_FAILURES failed frames")
                            }
                        }
                    }
                } finally { drawLock.unlock() }

                val ms = (System.nanoTime() - start) / 1e6
                lastDrawMs = ms
                emaMs = if (emaMs == 0.0) ms else emaMs * 0.9 + ms * 0.1
                if (continuous) {
                    sinceChange++
                    if (sinceChange >= 90) { // adapt rarely, with hysteresis
                        if (fps < maxFps && emaMs < 3.5) { fps = maxFps; sinceChange = 0; onEvent("fps -> $fps") }
                        else if (fps > 30 && emaMs > 9.0) { fps = 30; sinceChange = 0; onEvent("fps -> 30") }
                    }
                    val interval = 1_000_000_000L / fps
                    val now = System.nanoTime()
                    nextDeadline = if (nextDeadline == 0L || now - nextDeadline > interval * 4) start + interval else nextDeadline + interval
                    val remaining = nextDeadline - now
                    measure(now)
                    if (remaining > 0) {
                        lock.lock()
                        try { if (wanted && playing && !paused) wake.awaitNanos(remaining) } finally { lock.unlock() }
                    }
                } else {
                    nextDeadline = 0L
                    sinceChange = 0
                    windowStart = 0L; windowFrames = 0; measuredFps = 0
                }
            }
        } catch (e: InterruptedException) {
            // shutting down
        }
    }

    private var windowStart = 0L
    private var windowFrames = 0
    private fun measure(now: Long) {
        if (windowStart == 0L) windowStart = now
        windowFrames++
        val dt = now - windowStart
        if (dt >= TimeUnit.SECONDS.toNanos(1)) {
            measuredFps = Math.round(windowFrames * 1e9 / dt).toInt()
            windowStart = now
            windowFrames = 0
        }
    }

    companion object { const val MAX_FAILURES = 8 }
}
