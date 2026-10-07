package com.nevoit.cirro.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSeekStateTest {
    @Test
    fun draggingMovesRelativeToTheCurrentPositionInBothDirections() {
        val state = PlayerSeekState()
        state.begin(20_000L, 80_000L)
        assertEquals(20_000L, state.previewPosition.value)
        assertTrue(state.isDragging.value)

        state.dragBy(0.125f, 80_000L)
        assertEquals(30_000L, state.previewPosition.value)
        state.dragBy(-0.25f, 80_000L)
        assertEquals(10_000L, state.previewPosition.value)
    }

    @Test
    fun previewIsClampedToTheTrackDuration() {
        val state = PlayerSeekState()
        state.begin(20_000L, 80_000L)
        state.dragBy(2f, 80_000L)
        assertEquals(80_000L, state.previewPosition.value)
        state.dragBy(-2f, 80_000L)
        assertEquals(0L, state.previewPosition.value)
    }

    @Test
    fun releaseKeepsThePreviewUntilThePlayerConfirmsTheSeek() {
        val state = PlayerSeekState()
        state.begin(20_000L, 80_000L)
        state.dragBy(-0.125f, 80_000L)
        assertEquals(10_000L, state.commit(7L))
        assertFalse(state.isDragging.value)
        state.onPlaybackDiscontinuity(7L)
        assertEquals(10_000L, state.previewPosition.value)

        state.onPlaybackDiscontinuity(8L)
        assertNull(state.previewPosition.value)
    }

    @Test
    fun playbackUpdatesCannotOverrideAnActiveDrag() {
        val state = PlayerSeekState()
        state.begin(20_000L, 80_000L)
        state.onPlaybackDiscontinuity(8L)
        assertEquals(20_000L, state.previewPosition.value)
        assertTrue(state.isDragging.value)
    }

    @Test
    fun aNewDragIgnoresThePreviousSeekConfirmation() {
        val state = PlayerSeekState()
        state.begin(20_000L, 80_000L)
        state.commit(7L)
        state.begin(30_000L, 80_000L)
        state.onPlaybackDiscontinuity(8L)
        assertEquals(30_000L, state.previewPosition.value)
        assertTrue(state.isDragging.value)
    }

    @Test
    fun cancellationDiscardsThePreviewWithoutASeek() {
        val state = PlayerSeekState()
        state.begin(20_000L, 80_000L)
        state.cancel()
        assertNull(state.previewPosition.value)
        assertFalse(state.isDragging.value)
        assertNull(state.commit(7L))
    }

    @Test
    fun unknownDurationDoesNotStartSeeking() {
        val state = PlayerSeekState()
        state.begin(20_000L, 0L)
        state.dragBy(0.125f, 0L)
        assertNull(state.previewPosition.value)
        assertFalse(state.isDragging.value)
    }
}
