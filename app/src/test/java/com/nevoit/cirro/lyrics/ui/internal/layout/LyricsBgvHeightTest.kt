package com.nevoit.cirro.lyrics.ui.internal.layout

import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeAlignment
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeLine
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeSyllable
import com.nevoit.cirro.lyrics.ui.composable.list.LyricsLazyListState
import com.nevoit.cirro.lyrics.ui.composable.list.LyricsListItem
import com.nevoit.cirro.lyrics.ui.internal.playback.FocusState
import com.nevoit.cirro.lyrics.ui.preparation.PreparedLine
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsBgvHeightTest {
    @Test fun standaloneBackingVocalsCollapseOutsideFocus() {
        val line = backing()
        assertEquals(0f, line.settledHeight(true, true, 1f, false), 0f)
        assertEquals(64f, line.settledHeight(true, true, 1f, true), 0f)
    }

    @Test fun consecutiveBackingVocalsNeverLeavePhantomHeightInTheFollowDestination() {
        var focus = FocusState(0, listOf(0), null, false)
        val lines = List(12) { backing() }
        val items = lines.mapIndexed { index, line ->
            val focused = line.settledHeight(true, true, 1f, true).toInt()
            val idle = line.settledHeight(true, true, 1f, false).toInt()
            LyricsListItem(
                key = index,
                estimatedHeightPx = if (index == 0) focused else idle,
                settledHeightPx = { line.settledHeight(true, true, 1f, index in focus.allIndices).toInt() },
                focusHeightPx = { if (index in focus.allIndices) focused - idle else 0 },
            )
        }
        val state = LyricsLazyListState()
        state.focusHeightUpdates = LyricsFocusHeightUpdates(items, { focus }, { it })
        state.configure(items, 400, 0, 0, 1000, 1000)
        for (current in lines.indices) {
            focus = FocusState(current, listOf(current), null, false)
            state.updateFocusHeights()
            // Simulate measurement: LyricsReveal reports zero extent for an unfocused BGV.
            for (i in items.indices) state.heights.update(i, if (i == current) 64 else 0)
            var destination = state.heights.top(current)
            for (i in 0 until current) destination += items[i].settledHeightPx!!.invoke() - state.heights.height(i)
            assertEquals("phantom prefix after $current backing vocals", 0.0, destination, 0.0)
        }
    }

    @Test fun nestedBackingVocalsKeepTheMainLineButCollapseTheirOwnSpace() {
        val line = PreparedLine(
            source = KaraokeLine.MainKaraokeLine(listOf(KaraokeSyllable("main", 0, 1000)), null, KaraokeAlignment.Start, 0, 1000),
            runs = emptyList(), rows = emptyList(), width = 200f, height = 80f,
            rightAligned = false, before = listOf(backing()), after = listOf(backing()),
            translation = null, phonetic = null,
        )
        assertEquals(104f, line.settledHeight(true, true, 1f, false), 0f)
        assertEquals(216f, line.settledHeight(true, true, 1f, true), 0f)
        assertEquals(0f, line.settledLeadingHeight(true, true, 1f, false), 0f)
        assertEquals(56f, line.settledLeadingHeight(true, true, 1f, true), 0f)
    }

    private fun backing() = PreparedLine(
        source = KaraokeLine.AccompanimentKaraokeLine(listOf(KaraokeSyllable("Oh", 0, 1000)), null, KaraokeAlignment.Start, 0, 1000),
        runs = emptyList(), rows = emptyList(), width = 200f, height = 40f,
        rightAligned = false, before = emptyList(), after = emptyList(), translation = null, phonetic = null,
    )
}
