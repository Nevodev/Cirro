package com.nevoit.cirro.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.core.utility.rememberColorProducer
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MiniPlayerGestureTest {
    @get:Rule
    val compose = createComposeRule()

    private var opens = 0
    private var toggles = 0
    private var skips = 0
    private lateinit var controller: PlayerUiController

    @Test
    fun upwardDragUpdatesProgressBeforeReleaseAndCanBePulledBack() {
        setContent(followFinger = true)
        val mini = compose.onNodeWithTag("mini")
        mini.performTouchInput {
            down(Offset(width * 0.3f, centerY))
            moveBy(Offset(0f, -120f), 200)
        }
        val raised = compose.runOnIdle {
            assertTrue(controller.present)
            assertTrue(controller.isDragging)
            assertEquals(0, controller.animationRequest)
            assertTrue(controller.progress > 0f && controller.progress < 1f)
            controller.progress
        }
        mini.performTouchInput { moveBy(Offset(0f, 60f), 200) }
        compose.runOnIdle {
            assertTrue(controller.progress < raised)
            assertTrue(controller.isDragging)
        }
        mini.performTouchInput {
            advanceEventTime(300)
            up()
        }
        compose.runOnIdle {
            assertFalse(controller.isDragging)
            assertTrue(controller.isClosing)
            assertEquals(0f, controller.animationTarget().first, 0f)
            assertEquals(0, opens)
        }
    }

    @Test
    fun upwardSwipeOpensOnceAndDownwardOrHorizontalSwipesDoNotOpen() {
        setContent()
        val mini = compose.onNodeWithTag("mini")
        mini.performTouchInput {
            val start = Offset(width * 0.3f, centerY)
            swipe(start, start + Offset(0f, 120f), 240)
        }
        compose.runOnIdle { assertEquals(0, opens) }
        mini.performTouchInput {
            val start = Offset(width * 0.3f, centerY)
            swipe(start, start + Offset(120f, 0f), 240)
        }
        compose.runOnIdle { assertEquals(0, opens) }
        mini.performTouchInput {
            val start = Offset(width * 0.3f, centerY)
            swipe(start, start + Offset(0f, -120f), 240)
        }
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun tapAndTransportButtonsKeepTheirSeparateActions() {
        setContent()
        compose.onNodeWithTag("mini").performTouchInput { click(Offset(width * 0.3f, centerY)) }
        compose.onNodeWithContentDescription("播放").performClick()
        compose.onNodeWithContentDescription("下一首").performClick()
        compose.runOnIdle {
            assertEquals(1, opens)
            assertEquals(1, toggles)
            assertEquals(1, skips)
        }
    }

    private fun setContent(followFinger: Boolean = false) {
        val playback = MutableStateFlow(PlaybackState(song = NowPlaying(1, "歌曲", "艺人", null)))
        compose.setContent {
            MaterialTheme(darkTheme = false) {
                val player = remember { PlayerUiController() }
                val expansion = rememberPlayerExpansionState(player, rememberColorProducer(Color.Black))
                SideEffect { controller = player }
                Box {
                    MiniPlayer(
                        stateFlow = playback,
                        onClick = { opens++ },
                        onTogglePlay = { toggles++ },
                        onSkipNext = { skips++ },
                        modifier = Modifier.fillMaxWidth().testTag("mini"),
                        expansion = if (followFinger) expansion else null,
                    )
                }
            }
        }
    }
}
