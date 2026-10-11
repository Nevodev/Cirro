package com.nevoit.cirro.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSpectrumTest {
    @Test
    fun silenceProducesNoBandEnergy() {
        assertEquals(AudioSpectrum.Silence, spectrumFromFft(DoubleArray(1024), DoubleArray(1024), 48_000))
    }

    @Test
    fun realTonesDriveTheirFrequencyBands() {
        val tones = listOf(93.75, 375.0, 1_500.0, 6_000.0, 12_000.0)
        tones.forEachIndexed { expectedBand, frequency ->
            val spectrum = tone(frequency, 48_000)
            val strongestBand = (0 until AudioSpectrum.BandCount).maxBy { spectrum[it] }
            assertEquals("frequency=$frequency", expectedBand, strongestBand)
            assertTrue(spectrum[expectedBand] > 0.2f)
        }
    }

    @Test
    fun frequencyBandsUseTheActualSampleRate() {
        val real = DoubleArray(1024).apply { this[128] = 256.0 }
        val imaginary = DoubleArray(1024)
        val highMid = spectrumFromFft(real, imaginary, 48_000)
        val mid = spectrumFromFft(real, imaginary, 16_000)
        assertEquals(1f, highMid.highMid, 0f)
        assertEquals(0f, highMid.mid, 0f)
        assertEquals(1f, mid.mid, 0f)
        assertEquals(0f, mid.highMid, 0f)
    }

    @Test
    fun quieterAudioReducesTheBarHeight() {
        val loud = tone(93.75, 48_000, amplitude = 0.01)
        val quiet = tone(93.75, 48_000, amplitude = 0.001)
        assertEquals(1f / 3f, loud.low - quiet.low, 0.002f)
    }

    @Test
    fun dcDoesNotDriveTheSpectrumAndOverloadStaysBounded() {
        val real = DoubleArray(1024).apply { this[0] = 10_000.0 }
        val imaginary = DoubleArray(1024)
        assertEquals(AudioSpectrum.Silence, spectrumFromFft(real, imaginary, 48_000))
        real[32] = 10_000.0
        assertEquals(1f, spectrumFromFft(real, imaginary, 48_000).mid, 0f)
    }

    @Test
    fun equalAmplitudeTonesGainTenDecibelsPerOctave() {
        val lower = tone(1_500.0, 48_000, amplitude = 0.000001).mid
        val higher = tone(3_000.0, 48_000, amplitude = 0.000001).highMid
        assertTrue(lower > 0f && higher < 1f)
        assertEquals(10f / 60f, higher - lower, 0.002f)
    }

    @Test
    fun lowestFrequencyRemainsTheZeroGainReference() {
        val real = DoubleArray(1024).apply { this[1] = 25.6 }
        val spectrum = spectrumFromFft(real, DoubleArray(1024), 20_480)
        assertEquals(2f / 3f, spectrum.low, 0.0001f)
    }

    @Test
    fun lowFrequencyEnergyReceivesGainInsteadOfAttenuation() {
        val spectrum = tone(93.75, 48_000, amplitude = 0.01)
        assertEquals(0.705f, spectrum.low, 0.002f)
    }

    @Test
    fun tiltRecoversQuietHighFrequencyEnergyBeforeTheDisplayFloor() {
        val spectrum = tone(12_000.0, 48_000, amplitude = 0.0000002)
        assertTrue(spectrum.high > 0.3f && spectrum.high < 0.5f)
    }

    @Test
    fun renderClockInterpolatesEveryBandIndependently() {
        val first = AudioSpectrum(0f, 0.2f, 0.4f, 0.6f, 0.8f)
        val second = AudioSpectrum(1f, 0.8f, 0.6f, 0.4f, 0.2f)
        val between = first.interpolate(second, 0.5f)
        for (band in 0 until AudioSpectrum.BandCount) {
            assertEquals(0.5f, between[band], 0.0001f)
        }
        assertEquals(first, first.interpolate(second, 0f))
        assertEquals(second, first.interpolate(second, 1f))
    }

    private fun tone(frequency: Double, sampleRate: Int, amplitude: Double = 1.0): AudioSpectrum {
        val size = 1024
        val samples = DoubleArray(size) { sample ->
            amplitude * sin(2.0 * PI * frequency * sample / sampleRate) *
                (0.5 - 0.5 * cos(2.0 * PI * sample / (size - 1)))
        }
        val real = DoubleArray(size)
        val imaginary = DoubleArray(size)
        // 独立 DFT 对照 PCM 的 FFT 输出，覆盖频段归属和 Hann 窗后的幅度标定。
        for (bin in 0 until size / 2) {
            for (sample in samples.indices) {
                val angle = 2.0 * PI * bin * sample / size
                real[bin] += samples[sample] * cos(angle)
                imaginary[bin] -= samples[sample] * sin(angle)
            }
        }
        return spectrumFromFft(real, imaginary, sampleRate)
    }
}
