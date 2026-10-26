package com.nevoit.cirro.playback.pearmesh

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PearMeshSurfaceReadinessTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun waitsForAnAcquiredTextureWhoseFirstImageAlreadyUsesTheArtwork() {
        val artwork = createBitmap(8, 8).apply { eraseColor(Color.RED) }
        val state = PearMeshState()
        lateinit var view: PearMeshTextureView
        var ready = false
        var firstImage: Bitmap? = null
        compose.setContent {
            AndroidView(
                modifier = Modifier.size(64.dp, 96.dp),
                factory = { context ->
                    PearMeshTextureView(context, state) {
                        if (it) {
                            firstImage = view.bitmap
                            ready = true
                        }
                    }.apply {
                        view = this
                        setArtwork(artwork)
                    }
                },
                onRelease = { it.release() },
            )
        }
        compose.runOnIdle {
            assertTrue(view.isAvailable)
            assertFalse(ready)
            assertTrue(state.snapshot().artwork?.bitmap === artwork)
            view.setRenderingEnabled(true)
        }
        compose.waitUntil(timeoutMillis = 10_000) { ready }
        compose.runOnIdle {
            val image = firstImage
            assertNotNull(image)
            image!!
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            val red = pixels.sumOf { Color.red(it).toLong() }
            val green = pixels.sumOf { Color.green(it).toLong() }
            val blue = pixels.sumOf { Color.blue(it).toLong() }
            assertTrue("First image should already be red, rather than gray", red > green + pixels.size * 8)
            assertTrue(red > blue + pixels.size * 8)
            assertEquals(1f, view.alpha)
            view.release()
            image.recycle()
            artwork.recycle()
        }
    }
}
