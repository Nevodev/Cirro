package com.nevoit.cirro.playback

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerUiControllerTest {
    @Test
    fun closingReleasesInputImmediatelyAndCanReverseBeforeSettling() = runBlocking {
        val clock = BroadcastFrameClock()
        val controller = PlayerUiController()
        var time = 0L
        controller.open()
        assertTrue(controller.acceptsInput)
        val opening = launch(clock, start = CoroutineStart.UNDISPATCHED) { controller.animate() }
        repeat(180) {
            clock.sendFrame(time)
            time += 16_000_000L
            yield()
        }
        assertTrue(opening.isCompleted)

        controller.close()
        assertFalse(controller.acceptsInput)
        assertTrue(controller.present)
        val closing = launch(clock, start = CoroutineStart.UNDISPATCHED) { controller.animate() }
        repeat(8) {
            clock.sendFrame(time)
            time += 16_000_000L
            yield()
        }
        val position = controller.progress
        assertTrue(position > 0f && position < 1f)
        controller.open()
        assertTrue(controller.acceptsInput)
        assertTrue(controller.present)
        assertEquals(position, controller.progress, 0.0001f)
        assertTrue(controller.animationTarget().second < 0f)
        closing.cancelAndJoin()
        val reopening = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            controller.animate()
            controller.onAnimationSettled()
        }
        repeat(180) {
            clock.sendFrame(time)
            time += 16_000_000L
            yield()
        }
        assertTrue(reopening.isCompleted)
        assertEquals(1f, controller.progress, 0.0001f)
        assertTrue(controller.present)
        assertTrue(controller.acceptsInput)
    }

    @Test
    fun reversingAnOpeningAnimationPreservesMomentumAcrossCancellation() = runBlocking {
        val clock = BroadcastFrameClock()
        val controller = PlayerUiController()
        controller.open()
        val opening = launch(clock, start = CoroutineStart.UNDISPATCHED) { controller.animate() }
        var time = 0L
        repeat(8) {
            clock.sendFrame(time)
            time += 16_000_000L
            yield()
        }
        val position = controller.progress
        assertTrue(position > 0f && position < 1f)

        controller.close()
        val capturedVelocity = controller.animationTarget().second
        assertTrue(capturedVelocity > 0f)
        opening.cancelAndJoin()
        assertEquals(position, controller.progress, 0.0001f)
        assertEquals(capturedVelocity, controller.animationTarget().second, 0.0001f)

        val closing = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            controller.animate()
            controller.onAnimationSettled()
        }
        repeat(180) {
            clock.sendFrame(time)
            time += 16_000_000L
            yield()
        }
        assertTrue(closing.isCompleted)
        assertEquals(0f, controller.progress, 0.0001f)
        assertFalse(controller.present)
    }
}
