package com.nevoit.cirro.lyrics.ui.internal.effects

import com.nevoit.cirro.lyrics.ui.composable.lyrics.KaraokeBreathingDotsDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedBreathingDotsTest {
    @Test
    fun entranceFadesInImmediatelyWhileGrowingFromHalfSize() {
        for (duration in listOf(1000, 10000)) {
            val dots = PreparedBreathingDots(0, duration, KaraokeBreathingDotsDefaults())
            assertEquals(0.5f, dots.scale(0f), 0f)
            assertEquals(0f, dots.alpha(0f), 0f)
            var previousScale = 0.5f
            var previousAlpha = 0f
            for (step in 1..100) {
                val time = dots.enterEnd * step / 100f
                val scale = dots.scale(time)
                val alpha = dots.alpha(time)
                assertTrue(scale > 0.5f && scale >= previousScale && scale <= 1f)
                assertTrue(alpha > 0f)
                assertTrue(alpha >= previousAlpha && alpha <= 1f)
                previousScale = scale
                previousAlpha = alpha
            }
            assertEquals(1f, dots.alpha(dots.enterEnd), 0f)
        }
    }

    @Test
    fun exitFadesOutImmediatelyWhileShrinkingToHalfSize() {
        for (duration in listOf(1000, 10000)) {
            val dots = PreparedBreathingDots(0, duration, KaraokeBreathingDotsDefaults())
            assertEquals(1f, dots.scale(dots.exitStart), 0f)
            assertEquals(1f, dots.alpha(dots.exitStart), 0f)
            var previousScale = 1f
            var previousAlpha = 1f
            for (step in 1..100) {
                val time = dots.exitStart + (dots.duration - dots.exitStart) * step / 100f
                val scale = dots.scale(time)
                val alpha = dots.alpha(time)
                assertTrue(scale >= 0.5f && scale <= previousScale && scale < 1f)
                assertTrue(alpha < 1f)
                assertTrue(alpha <= previousAlpha && alpha >= 0f)
                previousScale = scale
                previousAlpha = alpha
            }
            assertEquals(0.5f, dots.scale(dots.duration), 0f)
            assertEquals(0f, dots.alpha(dots.duration), 0f)
        }
    }
}
