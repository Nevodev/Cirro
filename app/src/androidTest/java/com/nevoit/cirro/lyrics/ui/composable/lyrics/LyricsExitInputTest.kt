package com.nevoit.cirro.lyrics.ui.composable.lyrics

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.nevoit.cirro.lyrics.core.model.SyncedLyrics
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeAlignment
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeLine
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeSyllable
import com.nevoit.cirro.lyrics.ui.composable.list.LyricsLazyListState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LyricsExitInputTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun exitingLyricsImmediatelyReleasesDownwardDragWhileStillComposedAndCanReopen() {
        var selected by mutableStateOf(true)
        var pageDrag = 0f
        val state = LyricsLazyListState()
        val lyrics = SyncedLyrics((0 until 12).map { index ->
            val start = index * 1_000
            KaraokeLine.MainKaraokeLine(
                listOf(KaraokeSyllable("Lyric line $index", start, start + 1_000)),
                null, KaraokeAlignment.Start, start, start + 1_000,
                hasSyllableTiming = false,
            )
        })
        compose.setContent {
            Box(
                Modifier.size(320.dp, 480.dp).testTag("player")
                    .draggable(
                        state = rememberDraggableState { pageDrag += it },
                        orientation = Orientation.Vertical,
                    )
            ) {
                KaraokeLyricsView(
                    listState = state,
                    lyrics = lyrics,
                    currentPosition = { 4_500 },
                    onLineClicked = {},
                    onLinePressed = {},
                    modifier = Modifier.fillMaxSize(),
                    useBlurEffect = false,
                    inputEnabled = selected,
                    panelAlpha = { if (selected) 1f else 0.5f },
                )
            }
        }
        compose.waitUntil(10_000) { state.items.size == lyrics.lines.size }
        compose.waitForIdle()
        compose.onNodeWithTag("player").performTouchInput {
            swipe(Offset(centerX, height * 0.3f), Offset(centerX, height * 0.7f), 300)
        }
        compose.runOnIdle { assertEquals(0f, pageDrag, 0f) }

        // 子树保持挂载并继续绘制，模拟刚开始退场，还没来得及完成淡出的窗口。
        compose.runOnIdle { selected = false }
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.onNodeWithTag("player").performTouchInput {
            swipe(Offset(centerX, height * 0.3f), Offset(centerX, height * 0.7f), 300)
        }
        compose.runOnIdle { assertTrue("The player must receive the downward drag", pageDrag > 0f) }

        compose.runOnIdle {
            pageDrag = 0f
            selected = true
        }
        compose.onNodeWithTag("player").performTouchInput {
            swipe(Offset(centerX, height * 0.3f), Offset(centerX, height * 0.7f), 300)
        }
        compose.runOnIdle { assertEquals(0f, pageDrag, 0f) }
    }
}
