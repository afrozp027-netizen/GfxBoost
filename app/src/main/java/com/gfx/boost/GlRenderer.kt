package com.gfx.boost

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GlRenderer(
    private val out: Surface,
    private val w: Int,
    private val h: Int,
    private val onInput: (Surface) -> Unit
) : Thread("gfx-gl") {

    @Volatile
    var running = true

    private val lock = Object()
    private var frame = false

    private val vsh = """
        attribute vec4 aPos;
        attribute vec2 aUv;
        uniform mat4 uMtx;
        varying vec2 uv;
        void main() {
            gl_Position = aPos;
            uv = (uMtx * vec4(aUv, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    private val fsh = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES tex;
        uniform vec2 px;
        uniform float k;
        uniform float g;
        uniform float a;
        varying vec2 uv;
        void main() {
            vec3 c = texture2D(tex, uv).rgb;
            vec3 n = texture2D(tex, uv + vec2(0.0, px.y)).rgb
                   + texture2D(tex, uv - vec2(0.0, px.y)).rgb
                   + texture2D(tex, uv + vec2(px.x, 0.0)).rgb
                   + texture2D(tex, uv - vec2(px.x, 0.0)).rgb;
            vec3 s = c + k * (c - n * 0.25);
            s = (s - 0.5) * g + 0.5;
            s = clamp(s, 0.0, 1.0);
            gl_FragColor = vec4(s * a, a);
        }
    """.trimIndent()

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    override fun run() {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(dpy, ver, 0, ver, 1)

        val attr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(dpy, attr, 0, cfgs, 0, 1, num, 0)

        val ctx = EGL14.eglCreateContext(
            dpy, cfgs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        val sfc = EGL14.eglCreateWindowSurface(dpy, cfgs[0], out, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, sfc, sfc, ctx)

        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, compile(GLES20.GL_VERTEX_SHADER, vsh))
        GLES20.glAttachShader(prog, compile(GLES20.GL_FRAGMENT_SHADER, fsh))
        GLES20.glLinkProgram(prog)

        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aUv = GLES20.glGetAttribLocation(prog, "aUv")
        val uMtx = GLES20.glGetUniformLocation(prog, "uMtx")
        val uPx = GLES20.glGetUniformLocation(prog, "px")
        val uK = GLES20.glGetUniformLocation(prog, "k")
        val uG = GLES20.glGetUniformLocation(prog, "g")
        val uA = GLES20.glGetUniformLocation(prog, "a")

        val verts = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )
        val buf = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(verts)

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(tex[0])
        st.setDefaultBufferSize(w, h)
        st.setOnFrameAvailableListener {
            synchronized(lock) {
                frame = true
                lock.notifyAll()
            }
        }
        val input = Surface(st)
        onInput(input)

        val mtx = FloatArray(16)
        var last = 0L

        while (running) {
            synchronized(lock) {
                while (!frame && running) lock.wait(100)
                frame = false
            }
            if (!running) break

            st.updateTexImage()

            val now = System.nanoTime()
            if (now - last < 30_000_000L) continue
            last = now

            st.getTransformMatrix(mtx)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(prog)

            buf.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, buf)
            GLES20.glEnableVertexAttribArray(aPos)
            buf.position(2)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, buf)
            GLES20.glEnableVertexAttribArray(aUv)

            GLES20.glUniformMatrix4fv(uMtx, 1, false, mtx, 0)
            GLES20.glUniform2f(uPx, 1f / w, 1f / h)
            GLES20.glUniform1f(uK, Cfg.sharp)
            GLES20.glUniform1f(uG, Cfg.contrast)
            GLES20.glUniform1f(uA, Cfg.alpha)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGL14.eglSwapBuffers(dpy, sfc)
        }

        input.release()
        st.release()
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(dpy, sfc)
        EGL14.eglDestroyContext(dpy, ctx)
        EGL14.eglTerminate(dpy)
    }
}
