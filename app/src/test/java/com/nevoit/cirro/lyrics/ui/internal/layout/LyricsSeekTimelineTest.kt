package com.nevoit.cirro.lyrics.ui.internal.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsSeekTimelineTest {
    @Test fun positionChangesWithinASingleLineAndIsContinuousAtTheBoundary() {
        val timeline = LyricsSeekTimeline(listOf(0 to 1000, 1 to 3000, 2 to 7000))
        assertEquals(LyricsSeekPosition(0, 1, 0.25f), timeline.positionAt(1500))
        assertEquals(LyricsSeekPosition(0, 1, 0.75f), timeline.positionAt(2500))
        assertEquals(0.9995f, timeline.positionAt(2999)!!.fraction, 0.00001f)
        assertEquals(LyricsSeekPosition(1, 2, 0f), timeline.positionAt(3000))
        assertEquals(LyricsSeekPosition(1, 2, 0.5f), timeline.positionAt(5000))
    }

    @Test fun handlesIntroOutroDuplicatesAndBackwardSeeks() {
        val timeline = LyricsSeekTimeline(listOf(0 to 1000, 1 to 1000, 3 to 3000))
        assertEquals(LyricsSeekPosition(0, 0, 0f), timeline.positionAt(0))
        assertEquals(LyricsSeekPosition(3, 3, 0f), timeline.positionAt(4000))
        assertEquals(LyricsSeekPosition(0, 3, 0.5f), timeline.positionAt(2000))
        assertEquals(LyricsSeekPosition(0, 3, 0f), timeline.positionAt(1000))
        assertNull(LyricsSeekTimeline(emptyList()).positionAt(1000))
    }
}
