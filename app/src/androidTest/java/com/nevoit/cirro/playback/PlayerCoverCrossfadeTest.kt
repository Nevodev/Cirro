package com.nevoit.cirro.playback

import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlayerCoverCrossfadeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun opaqueCoversKeepTheWhiteBackgroundHiddenIncludingWhenInterrupted() {
        compose.mainClock.autoAdvance = false
        val target = mutableStateOf<Painter>(ColorPainter(Color.Red))
        compose.setContent {
            Box(Modifier.size(100.dp).background(Color.White).testTag("cover")) {
                Box(Modifier.fillMaxSize()) {
                    PlayerCoverCrossfade(updateTransition(target.value, label = "test"), 300)
                }
            }
        }
        compose.runOnIdle { target.value = ColorPainter(Color.Blue) }
        compose.mainClock.advanceTimeByFrame()
        repeat(4) {
            compose.mainClock.advanceTimeBy(32)
            assertNoWhiteBackground()
        }
        compose.runOnIdle { target.value = ColorPainter(Color.Black) }
        compose.mainClock.advanceTimeByFrame()
        repeat(12) {
            compose.mainClock.advanceTimeBy(32)
            assertNoWhiteBackground()
        }
    }

    private fun assertNoWhiteBackground() {
        val pixels = compose.onNodeWithTag("cover").captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertEquals("White background leaked through the cover blend", 0f, center.green, 0.01f)
        assertEquals(1f, center.alpha, 0.01f)
    }
}
