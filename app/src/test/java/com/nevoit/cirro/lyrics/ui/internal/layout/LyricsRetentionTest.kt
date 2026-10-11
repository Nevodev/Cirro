package com.nevoit.cirro.lyrics.ui.internal.layout

import com.nevoit.cirro.lyrics.ui.composable.list.LyricsLazyListState
import com.nevoit.cirro.lyrics.ui.composable.list.LyricsListItem
import com.nevoit.cirro.lyrics.ui.composable.list.LyricsScrollChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LyricsRetentionTest {
    @Test
    fun ordinaryLineChangeRetainsLessThanThePreviousExtraScreen() {
        val state = LyricsLazyListState()
        val items = List(50) { LyricsListItem(it, 100) }
        state.configure(items, 400, 0, 0, 1000, 1000)
        state.position = 1500.0
        state.chain.configure(items.size, LyricsScrollChain.of(300f, 0.3f), state.position, 1000)
        state.chain.retain(4, 37)
        state.chain.focusAt(15)
        state.position += 80
        state.chain.moveTo(state.position, cascade = true)

        val padding = state.retentionPadding(100)
        val oldPadding = 100 + 1000
        fun itemCount(buffer: Int): Int =
            state.heights.itemAt(state.position + 1000 + buffer) -
                state.heights.itemAt(state.position - buffer) + 1
        assertTrue(state.chain.active)
        assertTrue(padding in 180 until 400)
        assertTrue(itemCount(padding) < itemCount(oldPadding))

        repeat(600) { state.chain.advance(0.005f) }
        assertEquals(100, state.retentionPadding(100))
    }

    @Test
    fun motionEnvelopeCoversFutureCoupledOvershootAndDelayedFirstWords() {
        val configs = listOf(
            LyricsScrollChain.of(300f, 0.3f),
            LyricsScrollChain.of(300f, 0.7f),
            LyricsScrollChain.of(300f, 0f),
            LyricsScrollChain.of(300f, -0.2f),
            LyricsScrollChain(stiffness = 1000f, damping = 1f, coupling = 0.95f),
        )
        for (config in configs) for (delayed in listOf(false, true)) for (frames in listOf(0, 8, 24, 48)) {
            val chain = LyricsScrollChainState()
            chain.configure(12, config, 0.0, 1000)
            chain.retain(0, 12)
            chain.focusAt(2, if (delayed) 2 else -1)
            chain.moveTo(90.0, cascade = true)
            repeat(frames) { chain.advance(0.005f) }
            val envelope = chain.retentionExtentPx()
            repeat(600) {
                for (index in 0 until 12) {
                    assertTrue(
                        "Envelope $envelope missed row $index after $frames frames, delayed=$delayed, config=$config",
                        abs(chain.offset(index)) <= envelope + 0.1f,
                    )
                }
                chain.advance(0.005f)
            }
        }
    }

    @Test
    fun backingVocalAndBreathingShiftsAreIncludedEvenWithoutListScrolling() {
        val chain = LyricsScrollChainState()
        chain.configure(4, LyricsScrollChain.of(300f, 0.3f), 0.0, 1000)
        chain.retain(0, 4)
        chain.applyContentShift(0, 180.0)
        chain.applyLeadingContentShift(1, 120.0)
        chain.applyBreathingContentShift(1, 60.0)
        val envelope = chain.retentionExtentPx()
        assertTrue(envelope >= 360)
        repeat(600) {
            for (index in 0 until 4) {
                val offset = chain.offset(index) + chain.leadingOffset(index) + chain.breathingOffset(index)
                assertTrue(abs(offset) <= envelope + 0.1f)
            }
            chain.advance(0.005f)
        }
        assertEquals(0, chain.retentionExtentPx())
    }

    @Test
    fun collapsedExitDrawingAndCaptionOverflowKeepTheirOwnerInRange() {
        val state = LyricsLazyListState()
        val items = List(20) { index ->
            LyricsListItem(index, 80, visualOverflowPx = if (index == 3) 240 else 0)
        }
        state.configure(items, 400, 0, 0, 1000, 1000)
        state.chain.configure(items.size, LyricsScrollChain.of(300f, 0.3f), 0.0, 1000)
        state.chain.retain(0, items.size)
        state.chain.focusAt(3)
        state.chain.applyContentShift(3, -160.0)
        val padding = state.retentionPadding(100)
        assertTrue(padding >= 100 + 240 + 160)
        repeat(600) { state.chain.advance(0.005f) }
        assertEquals(340, state.retentionPadding(100))

        state.configure(listOf(LyricsListItem(0, 80)), 400, 0, 0, 1000, 1000)
        assertEquals(100, state.retentionPadding(100))
    }
}
