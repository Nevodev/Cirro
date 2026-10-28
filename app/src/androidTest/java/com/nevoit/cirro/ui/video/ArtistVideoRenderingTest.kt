package com.nevoit.cirro.ui.video

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.view.Surface
import android.view.SurfaceHolder
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nevoit.cirro.detail.HeaderBleedRatio
import com.nevoit.cirro.ui.GlReflection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ArtistVideoRenderingTest {
    @Test
    fun albumVideoBlurMatchesTheStaticCoverOnTheSameFrame() {
        val side = 256
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pixels = IntArray(side * side) { index ->
            val x = index % side
            val y = index / side
            val blue = if ((x / 8 + y / 8) % 2 == 0) 0 else 255
            (255 shl 24) or (y shl 16) or (x shl 8) or blue
        }
        val inputBitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
        val reference = checkNotNull(runBlocking { GlReflection.render(context, inputBitmap, 0.5f, HeaderBleedRatio) })
        try {
            withGl {
                val shader = ArtistReflectionEffect(
                    0.5f, side, fitSourceWidth = true, bleedRatio = HeaderBleedRatio, matchStaticCoverBlur = true,
                ).toGlShaderProgram(context, false) as BaseGlShaderProgram
                val output = shader.configure(side, side)
                assertEquals(reference.width, output.width)
                assertEquals(reference.height, output.height)
                val input = GlUtil.createTexture(side, side, false)
                val bytes = ByteBuffer.allocateDirect(side * side * 4)
                for (y in side - 1 downTo 0) for (x in 0 until side) {
                    val color = pixels[y * side + x]
                    bytes.put((color shr 16).toByte()).put((color shr 8).toByte()).put(color.toByte()).put(255.toByte())
                }
                bytes.rewind()
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, input)
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, side, side, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
                val target = GlUtil.createTexture(output.width, output.height, false)
                val fbo = GlUtil.createFboForTexture(target)
                try {
                    GlUtil.focusFramebufferUsingCurrentContext(fbo, output.width, output.height)
                    shader.drawFrame(input, 0)
                    val rendered = ByteBuffer.allocateDirect(output.width * output.height * 4)
                    GLES20.glReadPixels(0, 0, output.width, output.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rendered)
                    GlUtil.checkGlError()
                    for (top in 0 until output.height step 7) for (x in 0 until side step 7) {
                        val offset = ((output.height - 1 - top) * side + x) * 4
                        val expected = reference.getPixel(x, top)
                        for (channel in 0..2) {
                            val actual = rendered.get(offset + channel).toInt() and 255
                            val value = (expected shr (16 - channel * 8)) and 255
                            assertTrue("Static/video mismatch at $x,$top channel $channel: $value/$actual", abs(actual - value) <= 3)
                        }
                    }
                } finally {
                    shader.release()
                    GlUtil.deleteFbo(fbo)
                    GlUtil.deleteTexture(target)
                    GlUtil.deleteTexture(input)
                }
            }
        } finally {
            reference.recycle()
            inputBitmap.recycle()
        }
    }

    @Test
    fun reflectionSamplesTheSameBottomStripInReverse() {
        withGl {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val geometry = ArtistReflectionGeometry(96, 0.5f)
            val vertex = context.assets.open("shaders/artist_header.vert").bufferedReader().use { it.readText() }
            val fragment = context.assets.open("shaders/artist_reflection.frag").bufferedReader().use { it.readText() }
            val program = GlProgram(vertex, fragment)
            val input = GlUtil.createTexture(96, 96, false)
            val pixels = ByteBuffer.allocateDirect(96 * 96 * 4)
            for (y in 0 until 96) for (x in 0 until 96) {
                pixels.put(((95 - y) * 2).toByte()).put((x * 2).toByte()).put(64).put(255.toByte())
            }
            pixels.rewind()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, input)
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 96, 96, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            val output = GlUtil.createTexture(96, geometry.height, false)
            val fbo = GlUtil.createFboForTexture(output)
            try {
                GlUtil.focusFramebufferUsingCurrentContext(fbo, 96, geometry.height)
                program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
                program.setSamplerTexIdUniform("uTexSampler", input, 0)
                program.setFloatsUniform("uCrop", floatArrayOf(0f, 0f, 1f, 1f))
                program.setFloatUniform("uSide", 96f)
                program.setFloatUniform("uHeight", geometry.height.toFloat())
                program.setFloatUniform("uRise", geometry.rise.toFloat())
                program.setFloatUniform("uStripFraction", geometry.strip / 96f)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                val rendered = ByteBuffer.allocateDirect(96 * geometry.height * 4)
                GLES20.glReadPixels(0, 0, 96, geometry.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rendered)
                GlUtil.checkGlError()
                for (top in listOf(96, 100, 120, 140)) {
                    val offset = ((geometry.height - 1 - top) * 96 + 20) * 4
                    val sourceTop = 96f - (top + 0.5f - 96f) * 16f / 48f
                    val expected = ((sourceTop - 0.5f) * 2f).coerceAtMost(190f)
                    assertTrue("Reflected row $top samples the wrong strip", abs((rendered.get(offset).toInt() and 255) - expected) <= 1f)
                    assertEquals(40, rendered.get(offset + 1).toInt() and 255)
                }
            } finally {
                program.delete()
                GlUtil.deleteFbo(fbo)
                GlUtil.deleteTexture(output)
                GlUtil.deleteTexture(input)
            }
        }
    }

    @Test
    fun lpfRendersIntoTheMedia3OutputAndPreservesTheClearTop() {
        withGl {
            val side = 96
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val shader = ArtistReflectionEffect(0.5f, side).toGlShaderProgram(context, false) as BaseGlShaderProgram
            val output = shader.configure(side, side)
            assertEquals(96, output.width)
            assertEquals(144, output.height)
            val input = GlUtil.createTexture(side, side, false)
            val pixels = ByteBuffer.allocateDirect(side * side * 4)
            for (y in 0 until side) for (x in 0 until side) {
                val top = side - 1 - y
                pixels.put((top * 2).toByte()).put((x * 2).toByte()).put(64).put(255.toByte())
            }
            pixels.rewind()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, input)
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, side, side, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            val target = GlUtil.createTexture(output.width, output.height, false)
            val fbo = GlUtil.createFboForTexture(target)
            try {
                GlUtil.focusFramebufferUsingCurrentContext(fbo, output.width, output.height)
                shader.drawFrame(input, 0)
                val bound = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
                assertEquals(fbo, bound[0])
                val rendered = ByteBuffer.allocateDirect(output.width * output.height * 4)
                GLES20.glReadPixels(0, 0, output.width, output.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rendered)
                GlUtil.checkGlError()
                for (top in listOf(8, 32, 60)) {
                    val offset = ((output.height - 1 - top) * side + 20) * 4
                    assertTrue("Clear row $top was flipped or blurred", abs((rendered.get(offset).toInt() and 255) - top * 2) <= 1)
                    assertTrue(abs((rendered.get(offset + 1).toInt() and 255) - 40) <= 1)
                }
                for (offset in 3 until rendered.capacity() step 4) assertEquals(255, rendered.get(offset).toInt() and 255)
            } finally {
                shader.release()
                GlUtil.deleteFbo(fbo)
                GlUtil.deleteTexture(target)
                GlUtil.deleteTexture(input)
            }
        }
    }

    @Test
    fun portraitFitsWidthAndOnlyTheExtensionBlurs() {
        renderPortrait(inputHeight = 128) { rendered, width, height ->
            for (top in listOf(8, 64, 120, 126)) {
                val offset = ((height - 1 - top) * width + 20) * 4
                assertTrue("Portrait row $top was cropped or blurred", abs((rendered.get(offset).toInt() and 255) - top) <= 1)
                assertTrue(abs((rendered.get(offset + 1).toInt() and 255) - 40) <= 1)
                assertTrue("Original video lost sharpness", (rendered.get(offset + 2).toInt() and 255) <= 1)
            }
            val bottom = ((height - 1 - 140) * width + 48) * 4
            assertTrue("Extension is not blurred", (rendered.get(bottom + 2).toInt() and 255) in 60..190)
            for (offset in 3 until rendered.capacity() step 4) assertEquals(255, rendered.get(offset).toInt() and 255)
        }
    }

    @Test
    fun portraitTallerThanHeaderAlignsBottomWithoutBlur() {
        renderPortrait(inputHeight = 192) { rendered, width, height ->
            for (top in listOf(8, 64, 140)) {
                val offset = ((height - 1 - top) * width + 20) * 4
                val sourceTop = top + 192 - height
                assertTrue("Tall video did not align its bottom", abs((rendered.get(offset).toInt() and 255) - sourceTop) <= 1)
                assertTrue(abs((rendered.get(offset + 1).toInt() and 255) - 40) <= 1)
                assertTrue("Video that fills the header must remain sharp", (rendered.get(offset + 2).toInt() and 255) <= 1)
            }
        }
    }

    private fun renderPortrait(inputHeight: Int, verify: (ByteBuffer, Int, Int) -> Unit) {
        withGl {
            val width = 96
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val shader = ArtistReflectionEffect(0.5f, width, fitSourceWidth = true, bleedRatio = 0f)
                .toGlShaderProgram(context, false) as BaseGlShaderProgram
            val output = shader.configure(width, inputHeight)
            assertEquals(width, output.width)
            assertEquals(144, output.height)
            val input = GlUtil.createTexture(width, inputHeight, false)
            val pixels = ByteBuffer.allocateDirect(width * inputHeight * 4)
            for (y in 0 until inputHeight) for (x in 0 until width) {
                val top = inputHeight - 1 - y
                pixels.put(top.toByte()).put((x * 2).toByte())
                    .put(if (x % 2 == 0) 0.toByte() else 255.toByte()).put(255.toByte())
            }
            pixels.rewind()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, input)
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, inputHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            val target = GlUtil.createTexture(output.width, output.height, false)
            val fbo = GlUtil.createFboForTexture(target)
            try {
                GlUtil.focusFramebufferUsingCurrentContext(fbo, output.width, output.height)
                shader.drawFrame(input, 0)
                val rendered = ByteBuffer.allocateDirect(output.width * output.height * 4)
                GLES20.glReadPixels(0, 0, output.width, output.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rendered)
                GlUtil.checkGlError()
                verify(rendered, output.width, output.height)
            } finally {
                shader.release()
                GlUtil.deleteFbo(fbo)
                GlUtil.deleteTexture(target)
                GlUtil.deleteTexture(input)
            }
        }
    }

    @Test
    fun realSquareHlsVideoProducesAFrameThroughTheCustomEffect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val url = com.nevoit.cirro.lyrics.data.LyricsBridge.appleArtistVideo("1065981054", "us").getOrThrow()
        assertTrue("Test artist no longer provides square motion art", !url.isNullOrBlank())
        val frame = CountDownLatch(1)
        val failure = AtomicReference<PlaybackException?>()
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(192, 256) }
        val surface = Surface(texture)
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().apply {
                volume = 0f
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setMaxVideoSize(512, 512).build()
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { frame.countDown() }
                    override fun onPlayerError(error: PlaybackException) { failure.set(error); frame.countDown() }
                })
                setVideoEffects(listOf(ArtistReflectionEffect(1f / 3f, 192)))
                setVideoSurfaceHolder(Holder(surface, Rect(0, 0, 192, 256)))
                setMediaItem(MediaItem.Builder().setUri(url!!).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                playWhenReady = true
                prepare()
            }
        }
        try {
            assertTrue("No HLS frame within 40 seconds", frame.await(40, TimeUnit.SECONDS))
            assertNull(failure.get()?.let { "${it.errorCodeName}: ${it.cause}" }, failure.get())
        } finally {
            instrumentation.runOnMainSync { player.release() }
            surface.release()
            texture.release()
        }
    }

    private fun withGl(block: () -> Unit) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        ), 0, configs, 0, 1, count, 0))
        val config = checkNotNull(configs[0])
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context))
        try { block() } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private class Holder(private val surface: Surface, private val frame: Rect) : SurfaceHolder {
        override fun getSurface() = surface
        override fun getSurfaceFrame() = frame
        override fun addCallback(callback: SurfaceHolder.Callback) {}
        override fun removeCallback(callback: SurfaceHolder.Callback) {}
        override fun isCreating() = false
        @Deprecated("Deprecated by SurfaceHolder")
        override fun setType(type: Int) {}
        override fun setFixedSize(width: Int, height: Int) {}
        override fun setSizeFromLayout() {}
        override fun setFormat(format: Int) {}
        override fun setKeepScreenOn(screenOn: Boolean) {}
        override fun lockCanvas(): Canvas? = null
        override fun lockCanvas(dirty: Rect?): Canvas? = null
        override fun unlockCanvasAndPost(canvas: Canvas) {}
    }
}
