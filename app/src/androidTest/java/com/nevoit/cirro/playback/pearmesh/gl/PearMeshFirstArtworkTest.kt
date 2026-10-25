package com.nevoit.cirro.playback.pearmesh.gl

import android.graphics.Color
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nevoit.cirro.playback.pearmesh.PearMeshState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PearMeshFirstArtworkTest {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun createOffscreenSurface() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, 0x40,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE,
                ),
                0, configs, 0, configs.size, count, 0,
            )
        )
        assertTrue(count[0] > 0)
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        surface = EGL14.eglCreatePbufferSurface(
            display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, Width, EGL14.EGL_HEIGHT, Height, EGL14.EGL_NONE), 0,
        )
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
    }

    @After
    fun releaseOffscreenSurface() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }

    @Test
    fun missingArtworkMatchesThePageBackdropIncludingColorUpdates() {
        val state = PearMeshState()
        PearMeshGlRenderer(appContext, state.snapshot()).use { renderer ->
            for (rgb in listOf(floatArrayOf(0.2f, 0.4f, 0.6f), floatArrayOf(0.6f, 0.3f, 0.1f))) {
                state.setBackdropWashColor(rgb[0], rgb[1], rgb[2])
                val pixels = render(renderer, state)
                for (pixel in 0 until Width * Height) {
                    for (channel in 0..2) {
                        assertTrue(abs((pixels[pixel * 4 + channel].toInt() and 255) - rgb[channel] * 255) <= 1)
                    }
                    assertEquals(255, pixels[pixel * 4 + 3].toInt() and 255)
                }
            }
        }
    }

    @Test
    fun firstArtworkMatchesPreloadedArtworkWhetherItArrivesBeforeOrAfterFirstDraw() {
        val artwork = createBitmap(8, 8).apply { eraseColor(Color.RED) }
        try {
            val readyState = PearMeshState().apply { setArtwork(artwork) }
            val expected = PearMeshGlRenderer(appContext, readyState.snapshot()).use {
                render(it, readyState)
            }
            for (drawBeforeArtwork in listOf(false, true)) {
                val state = PearMeshState()
                PearMeshGlRenderer(appContext, state.snapshot()).use { renderer ->
                    if (drawBeforeArtwork) render(renderer, state)
                    state.setArtwork(artwork)
                    assertPixelsNear(expected, render(renderer, state))
                }
            }
        } finally {
            artwork.recycle()
        }
    }

    @Test
    fun subsequentArtworkStillStartsFromThePreviousSong() {
        val red = createBitmap(8, 8).apply { eraseColor(Color.RED) }
        val blue = createBitmap(8, 8).apply { eraseColor(Color.BLUE) }
        try {
            val state = PearMeshState().apply { setArtwork(red) }
            PearMeshGlRenderer(appContext, state.snapshot()).use { renderer ->
                val previous = render(renderer, state)
                state.setArtwork(blue)
                assertPixelsNear(previous, render(renderer, state))
            }
        } finally {
            red.recycle()
            blue.recycle()
        }
    }

    private fun render(renderer: PearMeshGlRenderer, state: PearMeshState): ByteArray {
        renderer.render(Width, Height, state.snapshot(), time = 0.0)
        val pixels = ByteBuffer.allocateDirect(Width * Height * 4)
        GLES30.glReadPixels(0, 0, Width, Height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
        return ByteArray(pixels.capacity()).also { pixels.get(it) }
    }

    private fun assertPixelsNear(expected: ByteArray, actual: ByteArray) {
        val difference = expected.indices.maxOf {
            abs((expected[it].toInt() and 255) - (actual[it].toInt() and 255))
        }
        assertTrue("Maximum channel difference was $difference", difference <= 2)
    }

    private companion object {
        const val Width = 64
        const val Height = 96
    }
}
