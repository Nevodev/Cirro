package com.nevoit.cirro.lyrics.ui.internal.layout

import com.nevoit.cirro.lyrics.ui.composable.list.LyricsScrollChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsScrollChainStateTest {
    @Test
    fun delayedFirstWordMovesMoreSlowlyAtStart() {
        val ordinary = chain()
        val delayed = chain()
        delayed.focusAt(2, bounceIndex = 2)
        ordinary.moveTo(100.0, cascade = true)
        delayed.moveTo(100.0, cascade = true)
        repeat(8) {
            ordinary.advance(0.005f)
            delayed.advance(0.005f)
        }
        assertTrue(delayed.offset(2) > ordinary.offset(2) + 1f)
        assertTrue(delayed.offset(1) > 1f)
        assertEquals(ordinary.offset(0), delayed.offset(0), 0f)
    }

    @Test
    fun delayedFirstWordAddsOvershootToLeavingIncomingAndFollowingSprings() {
        val config = LyricsScrollChain.of(300f, 0.3f).copy(coupling = 0f)
        val ordinary = chain(config)
        val delayed = chain(config)
        delayed.focusAt(2, bounceIndex = 2)
        ordinary.moveTo(100.0, cascade = true)
        delayed.moveTo(100.0, cascade = true)
        val ordinaryMinimum = FloatArray(4)
        val delayedMinimum = FloatArray(4)
        repeat(400) {
            ordinary.advance(0.005f)
            delayed.advance(0.005f)
            for (index in 1..3) {
                ordinaryMinimum[index] = minOf(ordinaryMinimum[index], ordinary.offset(index))
                delayedMinimum[index] = minOf(delayedMinimum[index], delayed.offset(index))
            }
            assertEquals(ordinary.offset(0), delayed.offset(0), 0f)
        }
        for (index in 1..3) {
            assertTrue(delayedMinimum[index] < ordinaryMinimum[index] - 0.1f)
            assertEquals(0f, delayed.offset(index), 0.1f)
        }
    }

    @Test
    fun leavingLineAndIncomingLineKeepASeparateSpringResponse() {
        val delayed = chain()
        delayed.focusAt(2, bounceIndex = 2)
        delayed.moveTo(100.0, cascade = true)
        assertEquals(100f, delayed.offset(1), 0f)
        assertEquals(100f, delayed.offset(2), 0f)
        repeat(20) { delayed.advance(0.005f) }
        assertTrue(delayed.offset(2) > delayed.offset(1) + 1f)
        assertEquals(0f, delayed.offset(0), 0f)
        repeat(600) { delayed.advance(0.005f) }
        for (index in 1..3) assertEquals(0f, delayed.offset(index), 0.1f)
    }

    @Test
    fun glideDoesNotAddBounceToLeavingLine() {
        val ordinary = chain()
        val delayed = chain()
        ordinary.follow(glide = true)
        delayed.follow(glide = true)
        ordinary.focusAt(2)
        delayed.focusAt(2, bounceIndex = 2)
        ordinary.moveTo(100.0, cascade = true)
        delayed.moveTo(100.0, cascade = true)
        repeat(200) {
            ordinary.advance(0.005f)
            delayed.advance(0.005f)
            for (index in 0..3) assertEquals(ordinary.offset(index), delayed.offset(index), 0f)
        }
    }

    @Test
    fun ordinaryFocusRestoresOriginalSpringResponse() {
        val ordinary = chain()
        val delayed = chain()
        delayed.focusAt(2, bounceIndex = 2)
        delayed.focusAt(2)
        ordinary.moveTo(100.0, cascade = true)
        delayed.moveTo(100.0, cascade = true)
        repeat(200) {
            ordinary.advance(0.005f)
            delayed.advance(0.005f)
            for (index in 0..3) assertEquals(ordinary.offset(index), delayed.offset(index), 0f)
        }
    }

    private fun chain(config: LyricsScrollChain = LyricsScrollChain.of(300f, 0.3f)) =
        LyricsScrollChainState().apply {
        configure(4, config, 0.0, 1000)
        retain(0, 4)
        follow(glide = false)
        focusAt(2)
    }
}
