package com.nevoit.cirro.lyrics.ui.internal.playback

import androidx.compose.ui.geometry.Rect
import com.nevoit.cirro.lyrics.core.model.SyncedLyrics
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeAlignment
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeLine
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeSyllable
import com.nevoit.cirro.lyrics.ui.preparation.PreparedLyrics
import com.nevoit.cirro.lyrics.ui.preparation.PreparedLine
import com.nevoit.cirro.lyrics.ui.preparation.PreparedRow
import com.nevoit.cirro.lyrics.ui.preparation.RenderWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsEarlyFollowTest {
    @Test fun tightGapScrollsEarlyWhilePreviousLineRemainsActive() {
        val timeline = timeline(150, line(0, 1000), line(1199, 2200))
        timeline.update(849)
        assertEquals(0, timeline.focus.value.followIndex)
        timeline.update(850)
        assertEquals(1, timeline.focus.value.followIndex)
        assertEquals(0, timeline.focus.value.firstIndex)
        assertEquals(listOf(0), timeline.focus.value.allIndices)
        assertFalse(timeline.focus.value.firstWordStartsLater)
        timeline.update(1000)
        assertEquals(1, timeline.focus.value.followIndex)
        assertFalse(timeline.focus.value.firstWordStartsLater)
    }

    @Test fun gapOfTwoHundredMillisKeepsOriginalHandoff() {
        val timeline = timeline(150, line(0, 1000), line(1200, 2200))
        timeline.update(999)
        assertEquals(0, timeline.focus.value.followIndex)
        timeline.update(1000)
        assertEquals(1, timeline.focus.value.followIndex)
    }

    @Test fun usesFirstNonblankWordRatherThanLineStart() {
        val next = line(1000, 2000).copy(syllables = listOf(
            KaraokeSyllable(" ", 1000, 1100), KaraokeSyllable("next", 1200, 2000),
        ))
        val timeline = timeline(150, line(0, 1000), next)
        timeline.update(850)
        assertEquals(0, timeline.focus.value.followIndex)
    }

    @Test fun advanceCanBeChangedOrDisabled() {
        for (advance in listOf(0, 50, 300)) {
            val timeline = timeline(advance, line(0, 1000), line(1000, 2000))
            timeline.update(1000 - advance - 1)
            assertEquals(0, timeline.focus.value.followIndex)
            timeline.update(1000 - advance)
            assertEquals(1, timeline.focus.value.followIndex)
        }
    }

    @Test fun largeAdvanceCannotSkipShortLinesOrStartDuringIntro() {
        val timeline = timeline(2000, line(1000, 1050), line(1050, 1100), line(1100, 1150))
        timeline.update(1000)
        assertEquals(0, timeline.focus.value.followIndex)
        timeline.update(1001)
        assertEquals(1, timeline.focus.value.followIndex)
        timeline.update(1050)
        assertEquals(1, timeline.focus.value.followIndex)
        timeline.update(1051)
        assertEquals(2, timeline.focus.value.followIndex)
    }

    @Test fun overlapDoesNotStartANewEarlyHandoff() {
        val timeline = timeline(150, line(0, 1000), line(800, 1800))
        timeline.update(999)
        assertEquals(0, timeline.focus.value.followIndex)
        assertEquals(listOf(0, 1), timeline.focus.value.allIndices)
        timeline.update(1000)
        assertEquals(1, timeline.focus.value.followIndex)
    }

    @Test fun lineTimingAndBackwardSeeksUseTheSameEarlyBoundary() {
        val timeline = timeline(150, line(0, 1000).copy(hasSyllableTiming = false), line(1000, 2000).copy(hasSyllableTiming = false))
        for (time in listOf(850, 900, 1500, 849, 850)) {
            timeline.update(time)
            assertEquals(if (time < 850) 0 else 1, timeline.focus.value.followIndex)
        }
    }

    @Test fun nestedBackingVocalsReleaseLayoutEarlyButKeepRealWordFocus() {
        val backing = KaraokeLine.AccompanimentKaraokeLine(
            listOf(KaraokeSyllable("echo", 800, 1100)), null, KaraokeAlignment.Start, 800, 1100,
        )
        val timeline = timeline(150, line(0, 1000).copy(accompanimentLines = listOf(backing)), line(1100, 2100))
        timeline.update(950)
        assertEquals(1, timeline.focus.value.followIndex)
        assertEquals(listOf(0), timeline.focus.value.allIndices)
        assertEquals(listOf(1), timeline.focus.value.layoutIndices)
        timeline.update(1099)
        assertEquals(listOf(0), timeline.focus.value.allIndices)
        assertEquals(listOf(1), timeline.focus.value.layoutIndices)
        timeline.update(1100)
        assertEquals(listOf(1), timeline.focus.value.allIndices)
        assertEquals(listOf(1), timeline.focus.value.layoutIndices)
    }

    @Test fun earlyHandoffStartsBackingVocalExitWithoutChangingItsWordClock() {
        val backingSource = KaraokeLine.AccompanimentKaraokeLine(
            listOf(KaraokeSyllable("echo", 800, 1100)), null, KaraokeAlignment.Start, 800, 1100,
        )
        val source = listOf(line(0, 1000).copy(accompanimentLines = listOf(backingSource)), line(1100, 2100))
        val backingRow = PreparedRow(
            emptyList(), Rect.Zero, false, 800, 1100, 1100, true,
            listOf(RenderWindow(800, 1100)), intArrayOf(), intArrayOf(), floatArrayOf(), floatArrayOf(),
            0f, 40f, 0f, 1f,
        )
        val backing = PreparedLine(backingSource, emptyList(), listOf(backingRow), 100f, 40f, false, emptyList(), emptyList(), null, null)
        val prepared = PreparedLyrics(source.mapIndexed { index, line ->
            PreparedLine(line, emptyList(), emptyList(), 100f, 80f, false, emptyList(),
                if (index == 0) listOf(backing) else emptyList(), null, null)
        })
        val timeline = LyricsPlaybackTimeline(SyncedLyrics(source), prepared, 150)
        timeline.update(949)
        assertTrue(timeline.state.line(backing).visible.value)
        assertEquals(listOf(0), timeline.focus.value.layoutIndices)
        timeline.update(950)
        assertFalse(timeline.state.line(backing).visible.value)
        assertEquals(listOf(1), timeline.focus.value.layoutIndices)
        assertEquals(950, timeline.state.row(backingRow).time.intValue)
        timeline.update(1099)
        assertFalse(timeline.state.line(backing).visible.value)
        assertEquals(1099, timeline.state.row(backingRow).time.intValue)
        timeline.update(900)
        assertTrue(timeline.state.line(backing).visible.value)
        assertEquals(listOf(0), timeline.focus.value.layoutIndices)
        assertEquals(900, timeline.state.row(backingRow).time.intValue)
    }

    @Test fun disabledEarlyFollowKeepsBackingOccupancyUntilOriginalHandoff() {
        val backing = KaraokeLine.AccompanimentKaraokeLine(
            listOf(KaraokeSyllable("echo", 800, 1100)), null, KaraokeAlignment.Start, 800, 1100,
        )
        val timeline = timeline(0, line(0, 1000).copy(accompanimentLines = listOf(backing)), line(1100, 2100))
        timeline.update(950)
        assertEquals(listOf(0), timeline.focus.value.layoutIndices)
        assertEquals(0, timeline.focus.value.followIndex)
    }

    @Test fun syntheticFollowBoundariesDoNotAdvanceWordClocks() {
        val lines = listOf(line(0, 1000), line(1199, 2200))
        val preparedLines = lines.map { source ->
            val row = PreparedRow(
                emptyList(), Rect.Zero, false, source.start, source.end, source.end, true,
                listOf(RenderWindow(source.start, source.end)),
                intArrayOf(), intArrayOf(), floatArrayOf(), floatArrayOf(), 0f, 40f, 0f, 1f,
            )
            PreparedLine(source, emptyList(), listOf(row), 100f, 40f, false, emptyList(), emptyList(), null, null)
        }
        val timeline = LyricsPlaybackTimeline(SyncedLyrics(lines), PreparedLyrics(preparedLines), 150)
        val previous = timeline.state.row(preparedLines[0].rows.single())
        val next = timeline.state.row(preparedLines[1].rows.single())
        timeline.update(849)
        timeline.update(850)
        assertEquals(1, timeline.focus.value.followIndex)
        assertEquals(850, previous.time.intValue)
        assertEquals(Int.MIN_VALUE, next.time.intValue)
        timeline.update(1000)
        assertEquals(Int.MAX_VALUE, previous.time.intValue)
        assertEquals(Int.MIN_VALUE, next.time.intValue)
        timeline.update(1199)
        assertEquals(1199, next.time.intValue)
        timeline.update(800)
        assertEquals(0, timeline.focus.value.followIndex)
        assertEquals(800, previous.time.intValue)
        assertEquals(Int.MIN_VALUE, next.time.intValue)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeAdvanceIsRejected() {
        timeline(-1, line(0, 1000))
    }

    private fun timeline(advance: Int, vararg lines: KaraokeLine): LyricsPlaybackTimeline =
        LyricsPlaybackTimeline(SyncedLyrics(lines.toList()), PreparedLyrics(List(lines.size) { null }), advance)

    private fun line(start: Int, end: Int) = KaraokeLine.MainKaraokeLine(
        listOf(KaraokeSyllable("word", start, end)), null, KaraokeAlignment.Start, start, end,
    )
}
