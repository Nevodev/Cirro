package com.nevoit.cirro.lyrics.ui.internal.layout

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.MonotonicFrameClock
import com.nevoit.cirro.lyrics.ui.composable.list.LyricsLazyListState
import com.nevoit.cirro.lyrics.ui.composable.list.LyricsListItem
import com.nevoit.cirro.lyrics.ui.internal.playback.FocusState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsFollowContinuityTest {
    @Test fun formalHandoffKeepsTheSameFollowRangeWithoutGeometryChange() {
        val early = FocusState(0, listOf(0), null, false, followIndex = 1)
        val formal = FocusState(1, listOf(1), null, false)
        assertEquals(early.followIndex, formal.followIndex)
        assertEquals(early.followEndIndex, formal.followEndIndex)
        var focus = early
        val items = List(3) { index ->
            LyricsListItem(index, 100, focusHeightPx = { 0 })
        }
        val state = LyricsLazyListState()
        state.focusHeightUpdates = LyricsFocusHeightUpdates(items, { focus }, { it })
        state.configure(items, 400, 0, 0, 1000, 1000)
        val revision = state.followGeometryRevision
        focus = formal
        state.updateFocusHeights()
        assertEquals(revision, state.followGeometryRevision)
    }

    @Test fun onlyChangesBeforeTheFollowedRowInvalidateItsDestination() = runBlocking {
        var beforeExtra = 0
        var afterExtra = 0
        val state = LyricsLazyListState()
        val items = List(6) { i -> LyricsListItem(
            i, 100, settledHeightPx = { 100 + when (i) { 0 -> beforeExtra; 5 -> afterExtra; else -> 0 } },
            focusHeightPx = { when (i) { 0 -> beforeExtra; 5 -> afterExtra; else -> 0 } },
        ) }
        state.configure(items, 400, 0, 0, 1000, 200)
        withContext(object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(0L)
        }) { state.animateFollowToItem(3, 3, false, androidx.compose.animation.core.snap()) }
        val revision = state.followGeometryRevision
        afterExtra = 40
        state.updateFocusHeights()
        assertEquals(revision, state.followGeometryRevision)
        beforeExtra = 40
        state.updateFocusHeights()
        assertEquals(revision + 1, state.followGeometryRevision)
        state.updateFocusHeights()
        assertEquals(revision + 1, state.followGeometryRevision)
    }

    @Test fun backingVocalRetargetKeepsCurrentSpeedAndReachesNewDestination() = checkRetarget(false)

    @Test fun manualScrollClearsVelocityBeforeFollowingResumes() = checkRetarget(true)

    private fun checkRetarget(manualInterruption: Boolean) = runBlocking {
        val frames = Channel<Long>(Channel.UNLIMITED)
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(frames.receive())
        }
        var extra = 80
        val items = List(8) { i -> LyricsListItem(
            i, 100 + if (i == 0) extra else 0,
            settledHeightPx = { 100 + if (i == 0) extra else 0 },
            focusHeightPx = { if (i == 0) extra else 0 },
        ) }
        val state = LyricsLazyListState()
        state.configure(items, 400, 0, 0, 1000, 200)
        state.retainedFirst = 0
        state.retainedEnd = items.size
        val spec = RecordingSpec()
        val first = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            state.animateFollowToItem(5, 5, false, spec)
        }
        for (frame in 0..10) { frames.send(frame * 16_000_000L); yield() }
        assertTrue(state.position > 0)
        val before = state.position
        first.cancelAndJoin()
        if (manualInterruption) state.suspendChain()
        extra = 0
        state.updateFocusHeights()
        val correctionSpec = RecordingSpec()
        val correction = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            state.animateFollowToItem(5, 5, false, correctionSpec, continueVelocity = true)
        }
        frames.send(176_000_000L)
        yield()
        if (manualInterruption) {
            assertEquals(0f, correctionSpec.initialVelocities.first(), 0f)
        } else {
            assertTrue("same-target correction must retain velocity", correctionSpec.initialVelocities.first() > 0f)
        }
        assertEquals("retarget starts at the currently drawn position", before, state.position, 0.001)
        for (frame in 12..300) { frames.send(frame * 16_000_000L); yield() }
        assertTrue("correction must finish", correction.isCompleted)
        assertEquals(500.0, state.position, 0.01)
        frames.close()
        Unit
    }

    private class RecordingSpec : AnimationSpec<Float> {
        val initialVelocities = mutableListOf<Float>()
        override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<Float, V>): VectorizedAnimationSpec<V> {
            val delegate = spring<Float>(dampingRatio = 1f, stiffness = 100f).vectorize(converter)
            return object : VectorizedAnimationSpec<V> by delegate {
                override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V {
                    initialVelocities += converter.convertFromVector(initialVelocity)
                    return delegate.getValueFromNanos(playTimeNanos, initialValue, targetValue, initialVelocity)
                }
            }
        }
    }
}
