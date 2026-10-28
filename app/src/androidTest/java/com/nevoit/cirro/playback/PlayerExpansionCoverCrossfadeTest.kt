package com.nevoit.cirro.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nevoit.material.core.utility.rememberColorProducer
import com.nevoit.material.navigation.shape.DeviceCornerState
import com.nevoit.material.navigation.shape.LocalDeviceCorners
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerExpansionCoverCrossfadeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aStaleMiniCoverCannotShowThroughStaticCoverCrossfadesOnTheExpandedPage() {
        lateinit var expansion: PlayerExpansionState
        val mix = mutableFloatStateOf(0f)
        compose.setContent {
            val controller = remember { PlayerUiController() }
            val state = rememberPlayerExpansionState(controller, rememberColorProducer(Color.Black))
            val target = rememberGraphicsLayer()
            SideEffect {
                expansion = state
                state.targetCoverLayer = target
                state.targetCoverBackground = Color.Black
                state.hasCover = true
            }
            CompositionLocalProvider(LocalDeviceCorners provides remember { DeviceCornerState() }) {
                Box(Modifier.size(240.dp, 400.dp).testTag("viewport").onGloballyPositioned(state::onUnderlayPositioned)) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).size(200.dp, 56.dp)
                            .onGloballyPositioned(state::onSourcePositioned)
                    ) {
                        Box(
                            Modifier.size(36.dp).onGloballyPositioned(state::onSourceCoverPositioned)
                                .drawWithContent {
                                    state.sourceCoverLayer.record { drawRect(Color.Green) }
                                }
                        )
                    }
                    PlayerExpansion(state, Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().background(Color.Black))
                        Box(
                            Modifier.align(Alignment.Center).size(100.dp)
                                .onGloballyPositioned(state::onTargetCoverPositioned)
                                .drawWithContent {
                                    val progress = mix.floatValue
                                    target.record {
                                        drawRect(Color.Red, alpha = 1f - progress)
                                        drawRect(Color.Blue, alpha = progress)
                                    }
                                    state.targetCoverOpaque = progress == 0f || progress == 1f
                                }
                        )
                    }
                }
            }
        }
        compose.runOnIdle {
            expansion.backgroundReady = true
            expansion.controller.onDrag(1f)
            expansion.takeOver()
        }
        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            compose.runOnIdle { mix.floatValue = progress }
            val pixels = compose.onNodeWithTag("viewport").captureToImage().toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 2]
            assertEquals("Stale mini cover leaked at $progress", 0f, center.green, 0.01f)
            assertTrue("Missing both static covers at $progress", center.red + center.blue > 0.5f)
        }
    }
}
