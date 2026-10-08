package com.nevoit.cirro.lyrics.ui.internal.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricsVisualBoundsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun scaledLyricsCanChangeLeadingOccupancyAndRemoveCaptions() {
        var occupied by mutableStateOf(false)
        var visible by mutableStateOf(true)
        var caption by mutableStateOf(true)
        var attached by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val lineHeight = with(LocalDensity.current) { 40.dp.toPx() }
            Box(Modifier.size(240.dp)) {
                if (attached) {
                    Column(Modifier.fillMaxWidth().lyricsVisualLayer(4f * lineHeight, lineHeight) {
                        scaleX = 0.98f
                        scaleY = 0.98f
                        alpha = 0.4f
                    }) {
                        LyricsReveal(
                            visible = visible,
                            animateInitial = true,
                            origin = TransformOrigin(0f, 1f),
                            occupied = { occupied },
                            visualHeight = lineHeight,
                        ) {
                            Box(Modifier.size(40.dp).background(Color.Red))
                        }
                        Column(Modifier.lyricsContentOffset(lineHeight, 2f * lineHeight) {
                            if (occupied) -lineHeight else 0f
                        }) {
                            Box(Modifier.size(40.dp).background(Color.Blue).testTag("main"))
                            LyricsReveal(caption) {
                                Box(Modifier.size(40.dp).background(Color.Green))
                            }
                        }
                    }
                }
            }
        }
        repeat(30) { frame ->
            compose.runOnIdle {
                attached = frame % 7 != 0
                occupied = frame % 2 == 0
                visible = frame % 3 != 0
                caption = frame % 4 != 0
            }
            compose.mainClock.advanceTimeBy(64L)
            compose.waitForIdle()
        }
        compose.runOnIdle {
            attached = true
            occupied = true
            visible = true
            caption = true
        }
        compose.mainClock.advanceTimeBy(2000L)
        compose.onNodeWithTag("main").assertIsDisplayed()
    }

    @Test
    fun collapsedBgvRemainsDrawableOutsideTheOwnersMeasuredHeight() {
        var pixelsPerDp = 1f
        compose.setContent {
            val density = LocalDensity.current
            pixelsPerDp = density.density
            val lineHeight = with(density) { 40.dp.toPx() }
            Box(Modifier.size(160.dp).background(Color.Black).testTag("viewport")) {
                Column(Modifier.fillMaxWidth().lyricsVisualLayer(2f * lineHeight) {
                    compositingStrategy = CompositingStrategy.Offscreen
                    alpha = 0.4f
                }) {
                    Box(Modifier.size(40.dp).background(Color.Blue))
                    LyricsReveal(
                        visible = true,
                        occupied = { false },
                        visualHeight = lineHeight,
                    ) {
                        Box(Modifier.size(40.dp).background(Color.Red))
                    }
                }
            }
        }
        val pixels = compose.onNodeWithTag("viewport").captureToImage().toPixelMap()
        val bgv = pixels[(10f * pixelsPerDp).toInt(), (60f * pixelsPerDp).toInt()]
        assertTrue("The fading BGV must survive zero layout occupancy", bgv.red > 0.2f)
        assertTrue(bgv.blue < 0.01f)
    }
}
