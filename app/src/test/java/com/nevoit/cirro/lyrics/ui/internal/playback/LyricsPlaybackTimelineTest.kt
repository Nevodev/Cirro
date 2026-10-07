package com.nevoit.cirro.lyrics.ui.internal.playback

import com.nevoit.cirro.lyrics.core.model.SyncedLyrics
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeAlignment
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeLine
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeSyllable
import com.nevoit.cirro.lyrics.ui.preparation.PreparedLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsPlaybackTimelineTest {
    @Test
    fun waitBelowTwoHundredMillisDoesNotAddBounce() {
        val timeline = timeline(line(0, 1000), line(1199, 2200))
        timeline.update(1000)
        assertEquals(1, timeline.focus.value.firstIndex)
        assertFalse(timeline.focus.value.firstWordStartsLater)
    }

    @Test
    fun waitOfExactlyTwoHundredMillisAddsBounce() {
        val timeline = timeline(line(0, 1000), line(1200, 2200))
        timeline.update(1000)
        assertEquals(1, timeline.focus.value.firstIndex)
        assertTrue(timeline.focus.value.firstWordStartsLater)
        timeline.update(1200)
        assertTrue(timeline.focus.value.firstWordStartsLater)
    }

    @Test
    fun gapBeforeFirstWordKeepsBounceDecisionAfterWordStarts() {
        val timeline = timeline(line(0, 1000), line(1500, 2500))
        timeline.update(999)
        assertEquals(0, timeline.focus.value.firstIndex)
        timeline.update(1000)
        assertEquals(1, timeline.focus.value.firstIndex)
        assertTrue(timeline.focus.value.firstWordStartsLater)
        timeline.update(1500)
        assertTrue(timeline.focus.value.firstWordStartsLater)
    }

    @Test
    fun simultaneousFirstWordDoesNotAddBounce() {
        val timeline = timeline(line(0, 1000), line(1000, 2000))
        timeline.update(1000)
        assertEquals(1, timeline.focus.value.firstIndex)
        assertFalse(timeline.focus.value.firstWordStartsLater)
    }

    @Test
    fun overlappingFirstWordDoesNotAddBounce() {
        val timeline = timeline(line(0, 1000), line(800, 1800))
        timeline.update(1000)
        assertEquals(1, timeline.focus.value.firstIndex)
        assertFalse(timeline.focus.value.firstWordStartsLater)
    }

    @Test
    fun firstWordInsideLineCanStartAfterLineChange() {
        val next = line(1000, 2000).copy(syllables = listOf(
            KaraokeSyllable(" ", 1000, 1100),
            KaraokeSyllable("word", 1200, 2000),
        ))
        val timeline = timeline(line(0, 1000), next)
        timeline.update(1000)
        assertTrue(timeline.focus.value.firstWordStartsLater)
    }

    @Test
    fun nextOrdinaryLineClearsDelayedFirstWordDecision() {
        val timeline = timeline(line(0, 1000), line(1500, 2500), line(2500, 3500))
        timeline.update(1000)
        assertTrue(timeline.focus.value.firstWordStartsLater)
        timeline.update(2500)
        assertEquals(2, timeline.focus.value.firstIndex)
        assertFalse(timeline.focus.value.firstWordStartsLater)
    }

    private fun timeline(vararg lines: KaraokeLine): LyricsPlaybackTimeline =
        LyricsPlaybackTimeline(SyncedLyrics(lines.toList()), PreparedLyrics(List(lines.size) { null }))

    private fun line(start: Int, end: Int) = KaraokeLine.MainKaraokeLine(
        syllables = listOf(KaraokeSyllable("word", start, end)),
        translation = null,
        alignment = KaraokeAlignment.Start,
        start = start,
        end = end,
    )
}
