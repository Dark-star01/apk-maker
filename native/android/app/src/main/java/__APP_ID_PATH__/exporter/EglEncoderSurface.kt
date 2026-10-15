package __APP_ID__.exporter

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Puts a finished frame (an ARGB Bitmap drawn by the shared Renderer) onto the video encoder's input Surface.
 *
 * Why EGL/GLES and not Surface.lockCanvas on the encoder surface: the encoder's input Surface is a producer endpoint of a
 * BufferQueue whose Canvas support differs between devices/encoders, whereas an EGL window surface on it is the documented,
 * universally supported path (EGL_RECORDABLE_ANDROID + eglPresentationTimeANDROID). The Renderer itself stays Canvas based
 * and draws into a software Bitmap, so preview and export share the exact same drawing code.
 *
 * All methods must be called from the SAME thread (the export worker): the EGL context is bound to it.
 */
internal class EglEncoderSurface(private val surface: Surface, private val width: Int, private val height: Int) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var textureAllocated = false
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also {
        // x, y, u, v : the Bitmap's first row is v = 0, the top of the screen is y = +1
        it.put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)); it.position(0)
    }

    init {
        try { setup() } catch (t: Throwable) { release(); throw t }
    }

    private fun setup() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display === EGL14.EGL_NO_DISPLAY) throw IllegalStateException("no EGL display")
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw IllegalStateException("eglInitialize failed")
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] < 1 || configs[0] == null) throw IllegalStateException("no recordable EGL config")
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (context === EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext failed")
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface === EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreateWindowSurface failed")
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) throw IllegalStateException("eglMakeCurrent failed")

        program = link(VERTEX, FRAGMENT)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texture = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glViewport(0, 0, width, height)
    }

    /** Uploads [bmp] (must be width x height) and draws it over the whole surface. */
    fun draw(bmp: Bitmap) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        if (!textureAllocated) { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0); textureAllocated = true }
        else GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        val uv = GLES20.glGetAttribLocation(program, "aUv")
        quad.position(0); GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(pos)
        quad.position(2); GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(uv)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) throw IllegalStateException("GL error 0x" + Integer.toHexString(err))
    }

    /** The timestamp the encoder will give this frame (nanoseconds); call before [swap]. */
    fun setPresentationTimeNs(ns: Long) { EGLExt.eglPresentationTimeANDROID(display, eglSurface, ns) }

    fun swap() { if (!EGL14.eglSwapBuffers(display, eglSurface)) throw IllegalStateException("eglSwapBuffers failed") }

    fun release() {
        try {
            if (display !== EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (context !== EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(display)
            }
        } catch (e: Throwable) { /* best effort */ }
        display = EGL14.EGL_NO_DISPLAY; context = EGL14.EGL_NO_CONTEXT; eglSurface = EGL14.EGL_NO_SURFACE
        try { surface.release() } catch (e: Throwable) { }
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { val log = GLES20.glGetShaderInfoLog(s); GLES20.glDeleteShader(s); throw IllegalStateException("shader: $log") }
            return s
        }
        val v = compile(GLES20.GL_VERTEX_SHADER, vs); val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("program link failed")
        return p
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val VERTEX = "attribute vec4 aPos; attribute vec2 aUv; varying vec2 vUv; void main(){ gl_Position = aPos; vUv = aUv; }"
        private const val FRAGMENT = "precision mediump float; varying vec2 vUv; uniform sampler2D uTex; void main(){ gl_FragColor = texture2D(uTex, vUv); }"
    }
}
