package com.nevoit.cirro.lyrics.ui.internal.layout

import com.nevoit.cirro.lyrics.ui.composable.list.LyricsLazyListState
import com.nevoit.cirro.lyrics.ui.composable.list.LyricsListItem
import com.nevoit.cirro.lyrics.ui.composable.list.LyricsScrollChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsFocusHeightTest {
    @Test
    fun upperAndLowerBgvOccupancyIsCommittedAndCompensatedExactlyOnce() {
        var focused = false
        val state = LyricsLazyListState()
        val items = List(3) { index ->
            LyricsListItem(
                key = index,
                estimatedHeightPx = 80,
                focusHeightPx = if (index == 1) ({ if (focused) 100 else 0 }) else null,
                focusLeadingHeightPx = if (index == 1) ({ if (focused) 60 else 0 }) else null,
            )
        }
        state.configure(items, 400, 0, 0, 1000, 1000)
        state.chain.configure(items.size, LyricsScrollChain.of(300f, 0.3f), 0.0, 1000)
        state.chain.retain(0, items.size)

        focused = true
        state.updateFocusHeights()
        assertEquals(180, state.heights.height(1))
        assertEquals(260.0, state.heights.top(2), 0.0)
        assertEquals(-60f, state.chain.leadingOffset(1), 0f)
        assertEquals(-100f, state.chain.offset(2), 0f)
        assertEquals(0f, state.chain.offset(0), 0f)
        state.updateFocusHeights()
        assertEquals(-60f, state.chain.leadingOffset(1), 0f)
        assertEquals(-100f, state.chain.offset(2), 0f)

        repeat(400) { state.chain.advance(0.005f) }
        focused = false
        state.updateFocusHeights()
        assertEquals(80, state.heights.height(1))
        assertEquals(160.0, state.heights.top(2), 0.0)
        assertEquals(60f, state.chain.leadingOffset(1), 0.001f)
        assertEquals(100f, state.chain.offset(2), 0.001f)
        assertTrue(state.chain.active)
    }

    @Test
    fun enteringWithAnAlreadyFocusedOwnerDoesNotAnimateInitialOccupancy() {
        val state = LyricsLazyListState()
        val items = listOf(LyricsListItem(
            key = 0,
            estimatedHeightPx = 180,
            focusHeightPx = { 100 },
            focusLeadingHeightPx = { 60 },
        ))
        state.configure(items, 400, 0, 0, 1000, 1000)
        state.chain.configure(items.size, LyricsScrollChain.of(300f, 0.3f), 0.0, 1000)
        state.chain.retain(0, items.size)
        state.updateFocusHeights()
        assertEquals(180, state.heights.height(0))
        assertEquals(0f, state.chain.leadingOffset(0), 0f)
        assertFalse(state.chain.active)
    }

    @Test
    fun introDotsMoveTheirOwnerAndFollowingLyricsThroughOneSpring() {
        var breathing = false
        val state = LyricsLazyListState()
        val items = List(3) { index ->
            LyricsListItem(
                key = index,
                estimatedHeightPx = 80,
                settledHeightPx = { 80 + if (index == 0 && breathing) 40 else 0 },
                breathingHeightPx = if (index == 0) ({ if (breathing) 40 else 0 }) else null,
            )
        }
        state.configure(items, 400, 0, 0, 1000, 1000)
        state.chain.configure(items.size, LyricsScrollChain.of(300f, 0.3f), 0.0, 1000)
        state.chain.retain(0, items.size)
        items.indices.forEach { state.chain.layoutAt(it, state.heights.top(it), 0.0) }

        breathing = true
        state.updateFocusHeights()
        assertEquals(120, state.heights.height(0))
        assertEquals(items[0].settledHeightPx?.invoke(), state.heights.height(0))
        assertEquals(120.0, state.heights.top(1), 0.0)
        assertEquals(-40f, state.chain.breathingOffset(0), 0f)
        assertEquals(-40f, state.chain.offset(1), 0f)
        items.indices.forEach { state.chain.layoutAt(it, state.heights.top(it), 0.0) }
        state.updateFocusHeights()
        assertEquals(-40f, state.chain.offset(1), 0f)
        repeat(400) {
            assertEquals(state.chain.breathingOffset(0), state.chain.offset(1), 0.001f)
            state.chain.advance(0.005f)
        }

        breathing = false
        state.updateFocusHeights()
        assertEquals(80, state.heights.height(0))
        assertEquals(80.0, state.heights.top(1), 0.0)
        assertEquals(40f, state.chain.breathingOffset(0), 0.001f)
        assertEquals(40f, state.chain.offset(1), 0.001f)
        items.indices.forEach { state.chain.layoutAt(it, state.heights.top(it), 0.0) }
        repeat(400) {
            assertEquals(state.chain.breathingOffset(0), state.chain.offset(1), 0.001f)
            state.chain.advance(0.005f)
        }
        assertEquals(0f, state.chain.breathingOffset(0), 0.1f)
        assertFalse(state.chain.active)
    }

    @Test
    fun dotsDisappearingAsLeadingBgvEntersPreservesBothDrawnPositions() {
        var focused = false
        val state = LyricsLazyListState()
        val items = List(3) { index ->
            LyricsListItem(
                key = index,
                estimatedHeightPx = if (index == 1) 120 else 80,
                focusHeightPx = if (index == 1) ({ if (focused) 60 else 0 }) else null,
                focusLeadingHeightPx = if (index == 1) ({ if (focused) 60 else 0 }) else null,
                breathingHeightPx = if (index == 1) ({ if (focused) 0 else 40 }) else null,
            )
        }
        state.configure(items, 400, 0, 0, 1000, 1000)
        state.chain.configure(items.size, LyricsScrollChain.of(300f, 0.3f), 0.0, 1000)
        state.chain.retain(0, items.size)
        assertFalse(state.chain.active)

        focused = true
        state.updateFocusHeights()
        assertEquals(140, state.heights.height(1))
        assertEquals(-60f, state.chain.leadingOffset(1), 0f)
        assertEquals(40f, state.chain.breathingOffset(1), 0f)
        assertEquals(-20f, state.chain.offset(2), 0f)
        repeat(400) {
            assertEquals(
                state.chain.leadingOffset(1) + state.chain.breathingOffset(1),
                state.chain.offset(2),
                0.001f,
            )
            state.chain.advance(0.005f)
        }
        assertFalse(state.chain.active)
    }

    @Test
    fun interruptedDotEntranceContinuesFromTheCurrentDrawnPosition() {
        var breathing = false
        val state = LyricsLazyListState()
        val items = List(2) { index ->
            LyricsListItem(
                key = index,
                estimatedHeightPx = 80,
                breathingHeightPx = if (index == 0) ({ if (breathing) 40 else 0 }) else null,
            )
        }
        state.configure(items, 400, 0, 0, 1000, 1000)
        state.chain.configure(items.size, LyricsScrollChain.of(300f, 0.3f), 0.0, 1000)
        state.chain.retain(0, items.size)
        breathing = true
        state.updateFocusHeights()
        repeat(20) { state.chain.advance(0.005f) }
        val drawnOwnerContentTop = 40f + state.chain.breathingOffset(0)
        val drawnFollowingTop = state.heights.top(1) + state.chain.offset(1)

        breathing = false
        state.updateFocusHeights()
        assertEquals(drawnOwnerContentTop, state.chain.breathingOffset(0), 0.001f)
        assertEquals(drawnFollowingTop, state.heights.top(1) + state.chain.offset(1), 0.001)
        repeat(400) { state.chain.advance(0.005f) }
        assertEquals(80.0, state.heights.top(1), 0.0)
        assertFalse(state.chain.active)
    }
}
