package com.nevoit.cirro.playback

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nevoit.material.core.utility.rememberColorProducer
import com.nevoit.material.navigation.shape.DeviceCornerState
import com.nevoit.material.navigation.shape.LocalDeviceCorners
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerExpansionInputTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun exitReleasesUnderlyingClicksAndBackWhileKeepingItsDrawingAndAllowingReopen() {
        lateinit var expansion: PlayerExpansionState
        var underlyingClicks = 0
        var playerClicks = 0
        var underlyingBack = 0
        val dispatcher = OnBackPressedDispatcher { underlyingBack++ }
        compose.setContent {
            val controller = remember { PlayerUiController() }
            val state = rememberPlayerExpansionState(controller, rememberColorProducer(Color.Black))
            val cover = rememberGraphicsLayer()
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val backOwner = remember(lifecycle) {
                object : OnBackPressedDispatcherOwner {
                    override val lifecycle = lifecycle
                    override val onBackPressedDispatcher = dispatcher
                }
            }
            SideEffect {
                expansion = state
                state.targetCoverLayer = cover
            }
            CompositionLocalProvider(
                LocalDeviceCorners provides remember { DeviceCornerState() },
                LocalOnBackPressedDispatcherOwner provides backOwner,
            ) {
                BackHandler(enabled = controller.acceptsInput) { controller.close() }
                Box(Modifier.size(240.dp, 400.dp).testTag("viewport")) {
                    Box(
                        Modifier.fillMaxSize().testTag("underlying").clickable { underlyingClicks++ }
                    )
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
                            .size(200.dp, 56.dp).testTag("mini")
                            .onGloballyPositioned(state::onSourcePositioned)
                            .clickable { controller.open() }
                    ) {
                        Box(
                            Modifier.size(36.dp).onGloballyPositioned(state::onSourceCoverPositioned)
                                .drawWithContent {
                                    state.sourceCoverLayer.record { this@drawWithContent.drawContent() }
                                    drawLayer(state.sourceCoverLayer)
                                }.background(Color.Cyan)
                        )
                    }
                    PlayerExpansion(state, Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().background(Color.Blue).clickable { playerClicks++ })
                        Box(
                            Modifier.align(Alignment.Center).size(100.dp)
                                .onGloballyPositioned(state::onTargetCoverPositioned)
                                .drawWithContent { cover.record { this@drawWithContent.drawContent() } }
                        )
                    }
                }
            }
        }
        compose.runOnIdle {
            expansion.controller.onDrag(1f)
            expansion.takeOver()
        }
        compose.onNodeWithTag("viewport").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(1, playerClicks)
            assertEquals(0, underlyingClicks)
            dispatcher.onBackPressed()
            assertFalse(expansion.controller.acceptsInput)
            assertTrue(expansion.controller.present)
        }
        val exit = compose.onNodeWithTag("viewport").captureToImage().toPixelMap()
        assertEquals(Color.Blue, exit[exit.width / 2, exit.height / 4])
        compose.onNodeWithTag("viewport").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(1, playerClicks)
            assertEquals(1, underlyingClicks)
            dispatcher.onBackPressed()
            assertEquals(1, underlyingBack)
        }
        compose.onNodeWithTag("mini").performTouchInput { click(center) }
        compose.runOnIdle {
            assertTrue(expansion.controller.acceptsInput)
            assertEquals(1f, expansion.controller.progress, 0.0001f)
        }
        compose.onNodeWithTag("viewport").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(2, playerClicks)
            assertEquals(1, underlyingClicks)
        }
    }
}
