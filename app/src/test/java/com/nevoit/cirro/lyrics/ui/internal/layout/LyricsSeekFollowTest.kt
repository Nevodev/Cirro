package com.nevoit.cirro.lyrics.ui.internal.layout

import androidx.compose.animation.core.spring
import androidx.compose.runtime.MonotonicFrameClock
import com.nevoit.cirro.lyrics.ui.composable.list.LyricsLazyListState
import com.nevoit.cirro.lyrics.ui.composable.list.LyricsListItem
import com.nevoit.cirro.lyrics.ui.composable.list.LyricsScrollChain
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSeekFollowTest {
    @Test fun seekInterpolatesMeasuredHeightsInsteadOfSnappingToEachRow() = runBlocking {
        val frames = Channel<Long>(Channel.UNLIMITED)
        val clock = clock(frames)
        val state = LyricsLazyListState()
        state.configure(listOf(
            LyricsListItem(0, 100, seekStartMillis = 1000),
            LyricsListItem(1, 300, seekStartMillis = 3000),
            LyricsListItem(2, 200, seekStartMillis = 7000),
        ), 400, 20, 0, 1000, 200)
        var nanos = 0L
        for ((time, expected) in listOf(1500 to 30.0, 2500 to 90.0, 3000 to 120.0, 5000 to 280.0, 6999 to 439.92, 7000 to 440.0)) {
            val target = state.seekPositionAt(time)!!
            val job = launch(clock, start = CoroutineStart.UNDISPATCHED) {
                state.animateSeekToItem(target.index, target.nextIndex, target.fraction)
            }
            frames.send(nanos)
            yield()
            nanos += 100_000_000L
            frames.send(nanos)
            yield()
            job.join()
            assertEquals("seek time=$time", expected, state.position, 0.01)
            nanos += 16_000_000L
        }
        frames.close()
        Unit
    }

    @Test fun scrubbingAnimatesBrieflyWithoutChainBounce() = runBlocking {
        val frames = Channel<Long>(Channel.UNLIMITED)
        val clock = clock(frames)
        val state = LyricsLazyListState()
        val items = List(20) { LyricsListItem(it, 100) }
        state.configure(items, 400, 0, 0, 1000, 200)
        state.chain.configure(items.size, LyricsScrollChain(), 0.0, 200)
        state.chain.retain(0, items.size)
        val seek = launch(clock, start = CoroutineStart.UNDISPATCHED) { state.animateSeekToItem(10) }
        assertEquals(0.0, state.position, 0.0)
        for (time in listOf(0L, 50_000_000L)) { frames.send(time); yield() }
        assertTrue("seek should interpolate, not jump", state.position > 0 && state.position < 1000)
        assertFalse(state.chain.active)
        frames.send(100_000_000L)
        yield()
        seek.join()
        assertEquals(1000.0, state.position, 0.01)
        items.indices.forEach { assertEquals(0f, state.chain.offset(it), 0f) }
        frames.close()
        Unit
    }

    @Test fun newDragTargetContinuesFromCurrentPositionAndCommitsBackingHeights() = runBlocking {
        val frames = Channel<Long>(Channel.UNLIMITED)
        val clock = clock(frames)
        var extra = 80
        val state = LyricsLazyListState()
        state.configure(List(20) { i -> LyricsListItem(
            i, 100 + if (i == 0) extra else 0,
            focusHeightPx = { if (i == 0) extra else 0 },
        ) }, 400, 0, 0, 1000, 200)
        val first = launch(clock, start = CoroutineStart.UNDISPATCHED) { state.animateSeekToItem(10) }
        for (time in listOf(0L, 50_000_000L)) { frames.send(time); yield() }
        val previous = state.position
        extra = 0
        val next = launch(clock, start = CoroutineStart.UNDISPATCHED) { state.animateSeekToItem(3) }
        yield()
        first.join()
        assertTrue(first.isCancelled)
        assertEquals(previous, state.position, 0.001)
        for (time in listOf(60_000_000L, 110_000_000L, 160_000_000L)) { frames.send(time); yield() }
        next.join()
        assertEquals(300.0, state.position, 0.01)
        assertEquals(100, state.heights.height(0))
        assertFalse(state.hasPredictedScrollGeometry)
        frames.close()
        Unit
    }

    @Test fun draggingCancelsNormalFollowAndNormalAnimationResumesAfterward() = runBlocking {
        val frames = Channel<Long>(Channel.UNLIMITED)
        val clock = clock(frames)
        val state = LyricsLazyListState()
        state.configure(List(20) { LyricsListItem(it, 100) }, 400, 0, 0, 1000, 200)
        val follow = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            state.animateFollowToItem(10, 10, false, spring())
        }
        repeat(5) { frames.send(it * 16_000_000L); yield() }
        val before = state.position
        val seek = launch(clock, start = CoroutineStart.UNDISPATCHED) { state.animateSeekToItem(3) }
        yield()
        follow.join()
        assertTrue(follow.isCancelled)
        assertEquals(before, state.position, 0.001)
        for (time in listOf(80_000_000L, 130_000_000L, 180_000_000L)) { frames.send(time); yield() }
        seek.join()
        assertEquals(300.0, state.position, 0.01)
        val resumed = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            state.animateFollowToItem(4, 4, false, spring())
        }
        assertEquals(300.0, state.position, 0.0)
        for (frame in 12..300) { frames.send(frame * 16_000_000L); yield() }
        resumed.join()
        assertEquals(400.0, state.position, 0.01)
        frames.close()
        Unit
    }

    private fun clock(frames: Channel<Long>) = object : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(frames.receive())
    }
}
