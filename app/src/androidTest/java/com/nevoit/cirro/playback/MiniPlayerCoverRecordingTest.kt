package com.nevoit.cirro.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.nevoit.material.core.utility.rememberColorProducer
import com.nevoit.material.theme.MaterialTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor

class MiniPlayerCoverRecordingTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun takeoverLeavesNoCoverPixelsInTheForegroundEvenAtFractionalOffsets() {
        lateinit var expansion: PlayerExpansionState
        val playback = MutableStateFlow(PlaybackState(song = NowPlaying(1, "歌曲", "艺人", null)))
        compose.setContent {
            MaterialTheme(darkTheme = false) {
                val controller = remember { PlayerUiController() }
                val state = rememberPlayerExpansionState(controller, rememberColorProducer(Color.Black))
                SideEffect { expansion = state }
                Column(Modifier.width(240.dp).onGloballyPositioned(state::onPagePositioned)) {
                    MiniPlayer(
                        stateFlow = playback,
                        onClick = {},
                        onTogglePlay = {},
                        onSkipNext = {},
                        modifier = Modifier.fillMaxWidth(),
                        expansion = state,
                    )
                    Box(
                        Modifier.fillMaxWidth().height(56.dp).testTag("foreground")
                            .background(Color.Magenta)
                            .drawWithContent {
                                drawContent()
                                translate(0.37f, 0.61f) { drawLayer(state.foregroundLayer) }
                            }
                    )
                }
            }
        }
        val cover = compose.runOnIdle {
            expansion.sourceCoverBounds.translate(-expansion.sourceBounds.topLeft)
        }
        val before = compose.onNodeWithTag("foreground").captureToImage().toPixelMap()
        assertNotEquals(Color.Magenta, before[cover.center.x.toInt(), cover.center.y.toInt()])
        compose.runOnIdle {
            expansion.controller.onDrag(0.15f)
            expansion.takeOver()
        }
        val after = compose.onNodeWithTag("foreground").captureToImage().toPixelMap()
        for (y in floor(cover.top).toInt() - 2..ceil(cover.bottom).toInt() + 2) {
            for (x in floor(cover.left).toInt() - 2..ceil(cover.right).toInt() + 2) {
                assertEquals("Cover residue at ($x, $y)", Color.Magenta, after[x, y])
            }
        }
    }
}
