package com.nevoit.material.clip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kyant.shapes.RoundedRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil
import kotlin.math.floor

@RunWith(AndroidJUnit4::class)
class CornerMaskClipTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun movingBoundsClipAllFourEdgesAndCornersWithoutRemeasuring() {
        val shift = mutableFloatStateOf(0f)
        var measurements = 0
        compose.setContent {
            Box(Modifier.size(120.dp).testTag("viewport").background(Color.Magenta)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .layout { measurable, constraints ->
                            measurements++
                            val child = measurable.measure(constraints)
                            layout(child.width, child.height) { child.place(0, 0) }
                        }
                        .cornerMaskClip(
                            bounds = { size ->
                                val left = size.width * (0.1f + shift.floatValue)
                                Rect(left, size.height * 0.1f, left + size.width * 0.3f, size.height * 0.9f)
                            },
                            shape = { RoundedRectangle(8.dp) },
                        )
                        .background(Color.Blue)
                )
            }
        }
        val before = compose.onNodeWithTag("viewport").captureToImage().toPixelMap()
        assertEquals(Color.Blue, before[(before.width * 0.25f).toInt(), before.height / 2])
        val measured = measurements

        compose.runOnIdle { shift.floatValue = 0.5f }
        val pixels = compose.onNodeWithTag("viewport").captureToImage().toPixelMap()
        assertEquals(measured, measurements)
        assertEquals(Color.Magenta, pixels[(pixels.width * 0.25f).toInt(), pixels.height / 2])
        assertEquals(Color.Blue, pixels[(pixels.width * 0.75f).toInt(), pixels.height / 2])
        for (x in listOf(0.601f, 0.899f)) {
            for (y in listOf(0.101f, 0.899f)) {
                val corner = pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()]
                assertTrue("The moving corner must expose the underlay", corner.red > 0.95f && corner.blue > 0.95f)
            }
        }
        assertEquals(Color.Magenta, pixels[(pixels.width * 0.75f).toInt(), 0])
        assertEquals(Color.Magenta, pixels[(pixels.width * 0.75f).toInt(), pixels.height - 1])
    }

    @Test
    fun zeroRadiusStillClipsTheMovingRectangle() {
        compose.setContent {
            Box(Modifier.size(120.dp).testTag("viewport").background(Color.Magenta)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .cornerMaskClip(
                            bounds = { size -> Rect(size.width / 4f, size.height / 4f, size.width * 0.75f, size.height * 0.75f) },
                            shape = { RoundedRectangle(0.dp) },
                        )
                        .background(Color.Blue)
                )
            }
        }
        val pixels = compose.onNodeWithTag("viewport").captureToImage().toPixelMap()
        assertEquals(Color.Blue, pixels[pixels.width / 2, pixels.height / 2])
        assertEquals(Color.Magenta, pixels[0, pixels.height / 2])
        assertEquals(Color.Magenta, pixels[pixels.width - 1, pixels.height / 2])
        assertEquals(Color.Magenta, pixels[pixels.width / 2, 0])
        assertEquals(Color.Magenta, pixels[pixels.width / 2, pixels.height - 1])
    }

    @Test
    fun fractionalOriginsDoNotLeaveContentOnTheOutsideOfAnyCorner() {
        val fraction = mutableFloatStateOf(0.25f)
        compose.setContent {
            Box(Modifier.size(120.dp).testTag("viewport").background(Color.Magenta)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .cornerMaskClip(
                            bounds = { size ->
                                val inset = 20f + fraction.floatValue
                                Rect(inset, 20.5f, size.width - inset, size.height - 20.5f)
                            },
                            shape = { RoundedRectangle(24.dp) },
                        )
                        .background(Color.Blue)
                )
            }
        }
        for (value in listOf(0.25f, 0.5f, 0.75f)) {
            compose.runOnIdle { fraction.floatValue = value }
            val pixels = compose.onNodeWithTag("viewport").captureToImage().toPixelMap()
            val inset = 20f + value
            val left = floor(inset).toInt()
            val right = ceil(pixels.width - inset).toInt() - 1
            for (x in listOf(left, right)) {
                for (y in listOf(22, pixels.height - 23)) {
                    val pixel = pixels[x, y]
                    assertTrue("Corner leaked at ($x, $y), fraction=$value: $pixel", pixel.red > 0.99f)
                }
            }
        }
    }
}
