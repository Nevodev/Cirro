package com.nevoit.cirro.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSpectrumSmootherTest {
    @Test
    fun alternatingFftWindowsNoLongerMakeTheBarsJumpBetweenExtremes() {
        val smoother = AudioSpectrumSmoother()
        val heights = (0 until 120).map { frame ->
            val target = if (frame % 2 == 0) 0.8f else 0.2f
            smoother.update(AudioSpectrum(mid = target), 16_000_000L).mid
        }.takeLast(60)

        assertTrue(heights.max() - heights.min() < 0.15f)
        assertTrue(heights.all { it in 0.2f..0.8f })
    }

    @Test
    fun beatsRisePromptlyAndFallMoreGently() {
        val rising = AudioSpectrumSmoother().update(AudioSpectrum(low = 1f), 200_000_000L).low
        val smoother = AudioSpectrumSmoother()
        smoother.update(AudioSpectrum(low = 1f), 2_000_000_000L)
        val falling = smoother.update(AudioSpectrum.Silence, 200_000_000L).low

        assertTrue(rising > 0.9f && rising < 1f)
        assertTrue(falling > 0.2f && falling < 0.5f)
        assertTrue(rising > 1f - falling)
    }

    @Test
    fun differentRenderRatesHaveTheSameResponseOverEqualElapsedTime() {
        val target = AudioSpectrum(1f, 0.8f, 0.6f, 0.4f, 0.2f)
        val slower = AudioSpectrumSmoother()
        val faster = AudioSpectrumSmoother()
        var slowerResult = AudioSpectrum.Silence
        var fasterResult = AudioSpectrum.Silence
        repeat(10) { slowerResult = slower.update(target, 50_000_000L) }
        repeat(50) { fasterResult = faster.update(target, 10_000_000L) }
        for (band in 0 until AudioSpectrum.BandCount) {
            assertEquals(slowerResult[band], fasterResult[band], 0.0001f)
        }
    }

    @Test
    fun smoothingDoesNotMixEnergyBetweenFrequencyBands() {
        val target = AudioSpectrum(low = 1f, mid = 0.5f, high = 0.2f)
        val result = AudioSpectrumSmoother().update(target, 100_000_000L)
        assertEquals(0f, result.lowMid, 0f)
        assertEquals(0f, result.highMid, 0f)
        assertTrue(result.low > result.mid && result.mid > result.high)
        assertTrue(result.low < target.low && result.mid < target.mid && result.high < target.high)
    }

    @Test
    fun trackChangesDiscardThePreviousEnvelope() {
        val smoother = AudioSpectrumSmoother()
        smoother.update(AudioSpectrum(high = 1f), 2_000_000_000L)
        smoother.reset()
        assertEquals(AudioSpectrum.Silence, smoother.update(AudioSpectrum.Silence, 16_000_000L))
        val next = smoother.update(AudioSpectrum(low = 1f), 16_000_000L)
        assertEquals(0f, next.high, 0f)
        assertTrue(next.low > 0f && next.low < 1f)
    }

    @Test
    fun pauseDecayStartsAtTheDisplayedHeightsAndGraduallyReachesSilence() {
        val displayed = AudioSpectrum(0.9f, 0.7f, 0.5f, 0.3f, 0.1f)
        val decay = AudioSpectrumSmoother(displayed)
        assertEquals(displayed, decay.update(AudioSpectrum.Silence, 0L))

        var previous = displayed
        repeat(120) { frame ->
            val next = decay.update(AudioSpectrum.Silence, 16_000_000L)
            for (band in 0 until AudioSpectrum.BandCount) {
                assertTrue(next[band] >= 0f && next[band] <= previous[band])
                if (frame == 0) {
                    assertTrue(next[band] > displayed[band] * 0.9f)
                    assertTrue(next[band] < displayed[band])
                }
            }
            previous = next
        }
        assertEquals(AudioSpectrum.Silence, previous)
    }

    @Test
    fun pauseDecayHasTheSameSpeedAtDifferentFrameRates() {
        val displayed = AudioSpectrum(0.9f, 0.7f, 0.5f, 0.3f, 0.1f)
        val slower = AudioSpectrumSmoother(displayed)
        val faster = AudioSpectrumSmoother(displayed)
        var slowerResult = displayed
        var fasterResult = displayed
        repeat(10) { slowerResult = slower.update(AudioSpectrum.Silence, 50_000_000L) }
        repeat(50) { fasterResult = faster.update(AudioSpectrum.Silence, 10_000_000L) }
        for (band in 0 until AudioSpectrum.BandCount) {
            assertEquals(slowerResult[band], fasterResult[band], 0.0001f)
        }
    }
}
