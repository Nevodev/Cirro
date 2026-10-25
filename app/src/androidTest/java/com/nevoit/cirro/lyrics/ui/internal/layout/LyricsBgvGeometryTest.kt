package com.nevoit.cirro.lyrics.ui.internal.layout

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.nevoit.cirro.lyrics.core.model.SyncedLyrics
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeAlignment
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeLine
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeSyllable
import com.nevoit.cirro.lyrics.ui.composable.list.LyricsLazyListState
import com.nevoit.cirro.lyrics.ui.composable.lyrics.KaraokeLyricsView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LyricsBgvGeometryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun consecutiveAndNestedBackingVocalsKeepMeasuredAndFollowHeightsInSync() {
        val time = mutableIntStateOf(500)
        val state = LyricsLazyListState()
        val backing = (1..6).map { i ->
            KaraokeLine.AccompanimentKaraokeLine(
                listOf(KaraokeSyllable("Echo $i", i * 1000, (i + 1) * 1000)),
                null, KaraokeAlignment.Start, i * 1000, (i + 1) * 1000,
                hasSyllableTiming = false,
            )
        }
        val nested = KaraokeLine.AccompanimentKaraokeLine(
            listOf(KaraokeSyllable("Nested echo", 7100, 7800)),
            null, KaraokeAlignment.Start, 7100, 7800,
        )
        val lyrics = SyncedLyrics(listOf(main(0), *backing.toTypedArray(), main(7000, listOf(nested)), main(8000)))
        compose.setContent {
            KaraokeLyricsView(
                listState = state, lyrics = lyrics, currentPosition = { time.intValue },
                onLineClicked = {}, onLinePressed = {}, modifier = Modifier.size(320.dp, 480.dp),
                useBlurEffect = false,
            )
        }
        compose.waitUntil(10_000) { state.items.size == lyrics.lines.size }
        compose.mainClock.autoAdvance = false
        for (position in listOf(500, 1500, 2500, 3500, 4500, 5500, 6500, 7500, 8500, 2500, 8500)) {
            compose.runOnIdle { time.intValue = position }
            compose.mainClock.advanceTimeBy(2500)
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue(state.retainedEnd > state.retainedFirst)
                for (index in state.retainedFirst until state.retainedEnd) {
                    assertEquals("time=$position, row=$index", state.items[index].settledHeightPx!!.invoke(), state.heights.height(index))
                }
                assertEquals("follow anchor at $position", state.heights.top(state.followAnchorIndex), state.position, 1.0)
            }
        }
    }

    private fun main(start: Int, backing: List<KaraokeLine.AccompanimentKaraokeLine>? = null) =
        KaraokeLine.MainKaraokeLine(
            listOf(KaraokeSyllable("Main vocal", start, start + 1000)), null,
            KaraokeAlignment.Start, start, start + 1000,
            accompanimentLines = backing, hasSyllableTiming = false,
        )
}
