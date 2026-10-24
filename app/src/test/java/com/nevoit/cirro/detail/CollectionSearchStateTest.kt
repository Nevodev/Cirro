package com.nevoit.cirro.detail

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.nevoit.kinetic.layout.overscroll.OverscrollVisualOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionSearchStateTest {
    @Test
    fun onlyTheDragRemainingAfterReachingTheTopCounts() {
        val state = pressedState()
        var atTop = false
        val effect = CollectionSearchOverscrollEffect(StretchEffect(), state) { !atTop }
        effect.applyToScroll(Offset(0f, 350f), NestedScrollSource.UserInput) {
            atTop = true
            Offset(0f, 300f)
        }
        assertFalse(state.revealed)
        assertEquals(null, state.animationVelocity)

        // 后续拖动已被同一个回弹 effect 消费，nested scroll 收不到，但仍须累计。
        effect.applyToScroll(Offset(0f, 46f), NestedScrollSource.UserInput) { Offset.Zero }
        assertTrue(state.revealed)
    }

    @Test
    fun pullingFromTheTopWorksEvenWhenContentCannotScroll() {
        val state = pressedState()
        val stretch = StretchEffect()
        val effect = CollectionSearchOverscrollEffect(stretch, state) { false }
        repeat(4) {
            effect.applyToScroll(Offset(0f, 24f), NestedScrollSource.UserInput) { Offset.Zero }
        }
        assertTrue(state.revealed)
        assertEquals(stretch.visualOffset, effect.visualOffset)
    }

    @Test
    fun flingingIntoTheTopDoesNotOpenSearch() {
        val state = pressedState()
        val effect = CollectionSearchOverscrollEffect(StretchEffect(), state) { false }
        effect.applyToScroll(Offset(0f, 500f), NestedScrollSource.SideEffect) { Offset.Zero }
        assertFalse(state.revealed)
        assertEquals(null, state.animationVelocity)
    }

    @Test
    fun reversingAndReleasingBeforeTheThresholdCancelsTheReveal() = runBlocking {
        val state = pressedState()
        val effect = CollectionSearchOverscrollEffect(StretchEffect(), state) { false }
        effect.applyToScroll(Offset(0f, 70f), NestedScrollSource.UserInput) { Offset.Zero }
        effect.applyToScroll(Offset(0f, -50f), NestedScrollSource.UserInput) { Offset.Zero }
        assertFalse(state.revealed)
        effect.applyToFling(Velocity.Zero) { Velocity.Zero }
        assertFalse(state.revealed)
        assertEquals(null, state.animationVelocity)
    }

    @Test
    fun reachingTheThresholdKeepsSearchVisibleAfterReleaseAndCloseResetsIt() = runBlocking {
        val state = pressedState()
        val effect = CollectionSearchOverscrollEffect(StretchEffect(), state) { false }
        effect.applyToScroll(Offset(0f, 100f), NestedScrollSource.UserInput) { Offset.Zero }
        effect.applyToFling(Velocity.Zero) { Velocity.Zero }
        assertTrue(state.revealed)
        state.query = "晴天"
        state.close()
        assertEquals("", state.query)
        assertFalse(state.revealed)
        assertEquals(null, state.animationVelocity)
    }

    @Test
    fun revealWaitsForTheThresholdAndUsesTheFingerVelocityAtThatEvent() {
        var fingerVelocity = 120f
        var velocityReads = 0
        val state = pressedState { velocityReads++; fingerVelocity }
        state.pullBy(95f)
        assertFalse(state.revealed)
        assertEquals(null, state.animationVelocity)
        assertEquals(0, velocityReads)

        fingerVelocity = 960f
        state.pullBy(2f)
        assertTrue(state.revealed)
        assertEquals(10f, state.animationVelocity!!, 0f)

        fingerVelocity = 1920f
        state.pullBy(20f)
        assertEquals(10f, state.animationVelocity!!, 0f)
        assertEquals(1, velocityReads)
    }

    @Test
    fun reversingTheSameHeldGestureClosesSearchWithTheUpwardVelocity() {
        var fingerVelocity = 960f
        val state = pressedState { fingerVelocity }
        val effect = CollectionSearchOverscrollEffect(StretchEffect(), state) { false }
        effect.applyToScroll(Offset(0f, 100f), NestedScrollSource.UserInput) { Offset.Zero }
        state.query = "晴天"
        fingerVelocity = -480f
        effect.applyToScroll(Offset(0f, -13f), NestedScrollSource.UserInput) { Offset.Zero }
        assertFalse(state.revealed)
        assertEquals("", state.query)
        assertEquals(-5f, state.animationVelocity!!, 0f)
    }

    @Test
    fun smallJitterDoesNotCloseSearchAndDownwardMotionResetsTheReversalDistance() {
        val state = pressedState()
        state.pullBy(100f)
        state.pullBy(-8f)
        assertTrue(state.revealed)
        state.pullBy(8f)
        state.pullBy(-8f)
        assertTrue(state.revealed)
        state.pullBy(-5f)
        assertFalse(state.revealed)
    }

    @Test
    fun liftingTheFingerDisarmsReverseDismissEvenOnTheNextGesture() {
        val state = pressedState()
        state.pullBy(100f)
        state.release()
        state.pullBy(-100f)
        assertTrue(state.revealed)

        state.beginGesture { 0f }
        state.pullBy(-100f)
        assertTrue(state.revealed)
    }

    @Test
    fun canceledOrInterruptedTouchesDoNotCarryPullDistanceToTheNextGesture() {
        val state = pressedState()
        state.pullBy(80f)
        state.beginGesture { 0f }
        state.pullBy(20f)
        assertFalse(state.revealed)
        state.pullBy(76f)
        assertTrue(state.revealed)
    }

    private fun pressedState(velocity: () -> Float = { 0f }) =
        CollectionSearchState(96f, 12f).apply { beginGesture(velocity) }

    private class StretchEffect : OverscrollEffect, OverscrollVisualOffset {
        private var stretch = 0f
        override val node = object : Modifier.Node() {}
        override val isInProgress: Boolean get() = stretch != 0f
        override val visualOffset: Offset get() = Offset(0f, stretch)

        override fun applyToScroll(
            delta: Offset,
            source: NestedScrollSource,
            performScroll: (Offset) -> Offset,
        ): Offset {
            if (stretch > 0f && delta.y >= -stretch) {
                stretch += delta.y
                return delta
            }
            val consumed = performScroll(delta)
            if (source == NestedScrollSource.UserInput) stretch += delta.y - consumed.y
            return delta
        }

        override suspend fun applyToFling(
            velocity: Velocity,
            performFling: suspend (Velocity) -> Velocity,
        ) {
            stretch = 0f
            performFling(velocity)
        }
    }
}
