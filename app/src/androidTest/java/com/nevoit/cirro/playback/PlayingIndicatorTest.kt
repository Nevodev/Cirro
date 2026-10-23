package com.nevoit.cirro.playback

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PlayingIndicatorTest {
    @Test
    fun ordinaryCoverNeedsNoPlaybackDependenciesOrUiNodes() = runTest {
        val isolated = koinApplication {}
        val runtime = IndicatorComposition(this)
        try {
            runtime.setContent {
                KoinIsolatedContext(isolated) { PlayingCoverIndicator(false) }
            }
            runtime.advance(320)
        } finally {
            runtime.close()
            isolated.close()
        }
    }

    @Test
    fun ordinaryRowsAllocateAnimationOnlyWhenSelected() = runTest {
        val runtime = IndicatorComposition(this)
        try {
            runtime.show(current = false)
            assertNull(runtime.opacity)
            runtime.current = true
            runtime.advance(32)
            assertNotNull(runtime.opacity)
            runtime.advance(240)
            assertEquals(1f, runtime.opacity!!(), 0f)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun deselectedIndicatorRemainsUntilItsFadeFinishesThenReleasesAnimationState() = runTest {
        val runtime = IndicatorComposition(this)
        try {
            runtime.show(current = true)
            runtime.advance(320)
            val original = runtime.opacity
            runtime.current = false
            runtime.advance(96)
            assertSame(original, runtime.opacity)
            assertTrue(runtime.opacity!!() > 0f && runtime.opacity!!() < 1f)
            runtime.advance(240)
            assertNull(runtime.opacity)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun selectingDuringFadeKeepsTheAnimationAndReversesFromItsCurrentOpacity() = runTest {
        val runtime = IndicatorComposition(this)
        try {
            runtime.show(current = true)
            runtime.advance(320)
            val original = runtime.opacity
            runtime.current = false
            runtime.advance(96)
            val fading = runtime.opacity!!()
            assertTrue(fading > 0f && fading < 1f)
            runtime.current = true
            assertEquals(fading, runtime.opacity!!(), 0f)
            runtime.advance(32)
            assertSame(original, runtime.opacity)
            runtime.advance(240)
            assertEquals(1f, runtime.opacity!!(), 0f)
        } finally {
            runtime.close()
        }
    }

    private class IndicatorComposition(private val scope: TestScope) {
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + clock)
        private val job = scope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        private val composition = Composition(NoUiApplier(), recomposer)
        private var frameTime = 0L
        var current by mutableStateOf(false)
        var opacity: (() -> Float)? = null
            private set

        fun show(current: Boolean) {
            this.current = current
            setContent { opacity = rememberPlayingOpacity(this.current) }
        }

        fun setContent(content: @Composable () -> Unit) {
            composition.setContent(content)
            flush()
        }

        fun advance(milliseconds: Long) {
            repeat(((milliseconds + 15) / 16).toInt()) {
                flush()
                frameTime += 16_000_000L
                clock.sendFrame(frameTime)
                flush()
            }
        }

        private fun flush() {
            Snapshot.sendApplyNotifications()
            scope.runCurrent()
        }

        fun close() {
            composition.dispose()
            recomposer.close()
            job.cancel()
        }
    }

    private class NoUiApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = error("Unexpected UI node")
        override fun insertBottomUp(index: Int, instance: Unit) = error("Unexpected UI node")
        override fun remove(index: Int, count: Int) = error("Unexpected UI node")
        override fun move(from: Int, to: Int, count: Int) = error("Unexpected UI node")
        override fun onClear() = Unit
    }
}
