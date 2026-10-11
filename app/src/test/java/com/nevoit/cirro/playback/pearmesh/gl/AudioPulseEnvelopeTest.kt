package com.nevoit.cirro.playback.pearmesh.gl

import com.nevoit.cirro.playback.pearmesh.AudioPowerFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPulseEnvelopeTest {
    private fun frame(power: Float, now: Long, generation: Long = 0L) = AudioPowerFrame(
        currentPower = FloatArray(4) { power },
        currentUpdatedAtNanos = now,
        generation = generation,
    )

    private fun warmed(): Pair<AudioPulseEnvelope, Long> {
        val envelope = AudioPulseEnvelope()
        var now = 1_000_000_000L
        envelope.update(frame(1f, now), enabled = true, nowNanos = now)
        repeat(30) {
            now += 16_000_000L
            envelope.update(frame(1f, now), enabled = true, nowNanos = now)
        }
        return envelope to now
    }

    @Test
    fun disablingAudioKeepsTheDisplayedPowerThenDecaysToRest() {
        val (envelope, start) = warmed()
        val displayed = envelope.update(frame(1f, start), enabled = true, nowNanos = start)
        var previous = envelope.update(frame(0f, start), enabled = false, nowNanos = start, paused = true)
        assertArrayEquals(displayed, previous, 0f)

        repeat(120) { frame ->
            val next = envelope.update(
                AudioPowerFrame(), enabled = false, nowNanos = start + (frame + 1) * 16_000_000L,
                paused = true,
            )
            for (lane in next.indices) {
                assertTrue(next[lane] in 0f..previous[lane])
                if (frame == 0) assertTrue(next[lane] > displayed[lane] * 0.9f)
            }
            previous = next
        }
        assertArrayEquals(FloatArray(3), previous, 0f)
    }

    @Test
    fun emptyReportsDuringSeekDoNotResetTheRenderedEnvelope() {
        val (envelope, start) = warmed()
        val displayed = envelope.update(frame(1f, start), enabled = true, nowNanos = start)
        val flushed = envelope.update(AudioPowerFrame(), enabled = true, nowNanos = start)
        assertArrayEquals(displayed, flushed, 0f)

        val decayed = envelope.update(AudioPowerFrame(), enabled = true, nowNanos = start + 32_000_000L)
        assertTrue(decayed.all { it > 0.5f && it < displayed[0] })
        val resumed = envelope.update(frame(1f, start + 32_000_000L), true, start + 32_000_000L)
        assertArrayEquals(decayed, resumed, 0f)
        val next = envelope.update(frame(1f, start + 48_000_000L), true, start + 48_000_000L)
        assertTrue(next.all { it > decayed[0] && it < 1f })
    }

    @Test
    fun firstReportFromANewTrackContinuesFromTheLastRenderedPower() {
        val (envelope, start) = warmed()
        val displayed = envelope.update(frame(1f, start), true, start)
        val first = envelope.update(frame(0.1f, start, generation = 1L), true, start)
        assertArrayEquals(displayed, first, 0f)
        val next = envelope.update(frame(0.1f, start + 16_000_000L, generation = 1L), true, start + 16_000_000L)
        assertTrue(next.all { it > 0.1f && it < displayed[0] })
    }

    @Test
    fun expiredAudioReportsReleaseGraduallyInsteadOfSnappingToZero() {
        val (envelope, start) = warmed()
        val lastReport = frame(1f, start)
        var previous = envelope.update(lastReport, true, start)
        var expired = previous
        repeat(16) { frame ->
            previous = expired
            expired = envelope.update(lastReport, true, start + (frame + 1) * 16_000_000L)
        }
        assertFalse(envelope.hasCurrentAudio)
        assertTrue(expired.all { it > 0.8f && it < previous[0] })
    }

    @Test
    fun decayHasTheSameSpeedAtDifferentRenderRates() {
        val (slower, start) = warmed()
        val (faster, _) = warmed()
        var slow = FloatArray(3)
        var fast = FloatArray(3)
        repeat(10) { frame ->
            slow = slower.update(AudioPowerFrame(), false, start + (frame + 1) * 50_000_000L, paused = true)
        }
        repeat(50) { frame ->
            fast = faster.update(AudioPowerFrame(), false, start + (frame + 1) * 10_000_000L, paused = true)
        }
        assertArrayEquals(slow, fast, 0.0001f)
    }

    @Test
    fun delayedReportInterpolationRetainsBothEndpoints() {
        val (interpolated, start) = warmed()
        val report = AudioPowerFrame(
            previousPower = FloatArray(4) { 0.2f },
            currentPower = FloatArray(4) { 0.8f },
            previousUpdatedAtNanos = start,
            currentUpdatedAtNanos = start + 16_000_000L,
        )
        for ((elapsed, expected) in listOf(16_000_000L to 0.2f, 24_000_000L to 0.5f, 32_000_000L to 0.8f)) {
            val now = start + elapsed
            assertArrayEquals(
                FloatArray(3) { expected },
                interpolated.update(report, true, now),
                0.0001f,
            )
        }
    }

    @Test
    fun normalBeatsIncludingSilenceDoNotUseThePauseEnvelope() {
        val (envelope, start) = warmed()
        for ((index, power) in listOf(0.2f, 0f, 0.8f, 0.1f).withIndex()) {
            val now = start + (index + 1) * 16_000_000L
            assertArrayEquals(FloatArray(3) { power }, envelope.update(frame(power, now), true, now), 0f)
        }
    }

    @Test
    fun pauseReleasesEvenWhenTheLastAudioReportIsStillEnabledAndFresh() {
        val (envelope, start) = warmed()
        val next = envelope.update(frame(1f, start), true, start + 16_000_000L, paused = true)
        assertFalse(envelope.hasCurrentAudio)
        assertTrue(next.all { it > 0.9f && it < 1f })
    }

    @Test
    fun normalResponseReturnsAfterTheInputTransitionFinishes() {
        val (envelope, start) = warmed()
        envelope.update(frame(0.2f, start, generation = 1L), true, start)
        val end = start + 180_000_000L
        assertArrayEquals(
            FloatArray(3) { 0.2f },
            envelope.update(frame(0.2f, end, generation = 1L), true, end),
            0f,
        )
        assertArrayEquals(
            FloatArray(3),
            envelope.update(frame(0f, end + 16_000_000L, generation = 1L), true, end + 16_000_000L),
            0f,
        )
    }
}
