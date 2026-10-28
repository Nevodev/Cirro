package com.nevoit.cirro.playback.pearmesh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.util.Log
import coil3.size.Size
import coil3.transform.Transformation
import com.nevoit.cirro.playback.pearmesh.gl.PearMeshGlRenderer
import com.nevoit.cirro.playback.pearmesh.gl.PearMeshCoverPreset
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 将播放器使用的网格渲染为静态方形封面，再交给后续倒影变换。 */
internal class PearMeshCoverTransformation(context: Context) : Transformation() {
    private val context = context.applicationContext
    override val cacheKey = "pear-mesh-cover-v4:${PearMeshCoverPreset.cacheKey}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = withContext(Dispatchers.Default) {
        renderLock.withLock {
            try {
                render(input)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("PearMeshCover", "静态封面渲染失败，保留原图", error)
                input
            }
        }
    }

    private fun render(input: Bitmap): Bitmap {
        val side = PearMeshCoverPreset.Side
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1))
        var eglContext = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x40,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            ), 0, configs, 0, 1, count, 0) && count[0] > 0)
            eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(eglContext != EGL14.EGL_NO_CONTEXT)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, side, EGL14.EGL_HEIGHT, side, EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, surface, surface, eglContext))

            val state = PearMeshState(
                renderScale = 1f,
                scrimAlpha = PearMeshCoverPreset.ScrimAlpha,
                blurMultiplier = PearMeshCoverPreset.BlurMultiplier,
            ).apply {
                setArtwork(input)
                // 播放页由宿主提供底色；静态封面从同一输入取色，不能沿用默认黑色冲淡下半幅。
                var red = 0L
                var green = 0L
                var blue = 0L
                for (y in 0 until 8) for (x in 0 until 8) {
                    val color = input.getPixel(
                        ((x + 0.5f) * input.width / 8).toInt().coerceAtMost(input.width - 1),
                        ((y + 0.5f) * input.height / 8).toInt().coerceAtMost(input.height - 1),
                    )
                    red += Color.red(color)
                    green += Color.green(color)
                    blue += Color.blue(color)
                }
                setBackdropWashColor(red / (64f * 255f), green / (64f * 255f), blue / (64f * 255f))
            }.snapshot()
            var output: Bitmap? = null
            val capture = AtomicReference<((Bitmap) -> Unit)?>({ output = it })
            PearMeshGlRenderer(context, state, capture, PearMeshCoverPreset.mesh).use { renderer ->
                renderer.render(side, side, state, time = PearMeshCoverPreset.Time)
                check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
            }
            return checkNotNull(output)
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
    }

    private companion object {
        val renderLock = Mutex()
    }
}
