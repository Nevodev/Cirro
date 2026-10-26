package com.nevoit.cirro.playback

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nevoit.material.core.utility.rememberColorProducer
import com.nevoit.material.navigation.shape.DeviceCornerState
import com.nevoit.material.navigation.shape.LocalDeviceCorners
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerExpansionCoordinatesTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun closingKeepsTheCoverAnchorsFixedWhileTheContentAndUnderlayMove() {
        lateinit var expansion: PlayerExpansionState
        lateinit var viewport: LayoutCoordinates
        lateinit var content: LayoutCoordinates
        compose.setContent {
            val controller = remember { PlayerUiController() }
            val state = rememberPlayerExpansionState(controller, rememberColorProducer(Color.Black))
            val targetLayer = rememberGraphicsLayer()
            SideEffect {
                expansion = state
                state.targetCoverLayer = targetLayer
            }
            CompositionLocalProvider(LocalDeviceCorners provides remember { DeviceCornerState() }) {
                Box(Modifier.size(240.dp, 400.dp).onGloballyPositioned { viewport = it }) {
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        val scale = 1f - 0.04f * controller.progress
                        scaleX = scale
                        scaleY = scale
                    }) {
                        Box(
                            Modifier.fillMaxSize().onGloballyPositioned(state::onUnderlayPositioned)
                        ) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 24.dp)
                                    .size(200.dp, 56.dp)
                                    .onGloballyPositioned(state::onSourcePositioned)
                                    .drawWithContent {
                                        state.foregroundLayer.record { this@drawWithContent.drawContent() }
                                        drawLayer(state.foregroundLayer)
                                    }
                            ) {
                                Box(
                                    Modifier
                                        .size(36.dp)
                                        .onGloballyPositioned(state::onSourceCoverPositioned)
                                        .drawWithContent {
                                            state.sourceCoverLayer.record { this@drawWithContent.drawContent() }
                                            drawLayer(state.sourceCoverLayer)
                                        }
                                        .background(Color.Cyan)
                                )
                            }
                        }
                    }
                    PlayerExpansion(state, Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().onGloballyPositioned { content = it })
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .size(100.dp)
                                .onGloballyPositioned(state::onTargetCoverPositioned)
                                .drawWithContent {
                                    targetLayer.record { this@drawWithContent.drawContent() }
                                }
                                .background(Color.Blue)
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
        compose.waitForIdle()
        val target = compose.runOnIdle { expansion.targetCoverBounds }
        val source = compose.runOnIdle { expansion.sourceBounds }
        val sourceCover = compose.runOnIdle { expansion.sourceCoverBounds }
        for (progress in listOf(0.85f, 0.6f, 0.3f, 0.1f, 0f)) {
            compose.runOnIdle {
                expansion.controller.onDrag(progress - expansion.controller.progress)
            }
            compose.waitForIdle()
            compose.runOnIdle {
                val actualTarget = expansion.targetCoverBounds
                assertEquals(target.top, actualTarget.top, 0.1f)
                assertEquals(target.bottom, actualTarget.bottom, 0.1f)
                assertEquals(source.left, expansion.sourceBounds.left, 0.1f)
                assertEquals(source.top, expansion.sourceBounds.top, 0.1f)
                assertEquals(source.width, expansion.sourceBounds.width, 0.1f)
                assertEquals(sourceCover.left, expansion.sourceCoverBounds.left, 0.1f)
                assertEquals(sourceCover.top, expansion.sourceCoverBounds.top, 0.1f)
                assertEquals(sourceCover.width, expansion.sourceCoverBounds.width, 0.1f)
                val actualContent = viewport.localBoundingBoxOf(content, clipBounds = false)
                val clip = expansion.bounds(viewport.size.toSize())
                assertEquals(clip.top, actualContent.top, 0.1f)
            }
        }
    }
}
