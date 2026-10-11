package com.nevoit.cirro.playback

import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.sqrt

data class AudioSpectrum(
    val low: Float = 0f,
    val lowMid: Float = 0f,
    val mid: Float = 0f,
    val highMid: Float = 0f,
    val high: Float = 0f,
) {
    operator fun get(index: Int): Float = when (index) {
        0 -> low
        1 -> lowMid
        2 -> mid
        3 -> highMid
        4 -> high
        else -> error("Invalid spectrum band: $index")
    }

    fun interpolate(other: AudioSpectrum, fraction: Float): AudioSpectrum {
        if (fraction <= 0f) return this
        if (fraction >= 1f) return other
        return AudioSpectrum(
            low + (other.low - low) * fraction,
            lowMid + (other.lowMid - lowMid) * fraction,
            mid + (other.mid - mid) * fraction,
            highMid + (other.highMid - highMid) * fraction,
            high + (other.high - high) * fraction,
        )
    }

    companion object {
        const val BandCount = 5
        val Silence = AudioSpectrum()
    }
}

private val SpectrumBandEdgesHz = doubleArrayOf(20.0, 1_000.0, 1_500.0, 5_000.0, 10_000.0, 18_000.0)
private const val SpectrumTiltDbPerOctave = 4.0
private const val SpectrumFloorDb = -20.0

internal fun spectrumFromFft(
    real: DoubleArray,
    imaginary: DoubleArray,
    sampleRate: Int,
): AudioSpectrum {
    val peaksDb = DoubleArray(AudioSpectrum.BandCount) { Double.NEGATIVE_INFINITY }
    val binWidth = sampleRate.toDouble() / real.size
    for (bin in 1 until real.size / 2) {
        val frequency = bin * binWidth
        val band = (0 until AudioSpectrum.BandCount).firstOrNull {
            frequency >= SpectrumBandEdgesHz[it] && frequency < SpectrumBandEdgesHz[it + 1]
        } ?: continue
        val magnitude = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
        if (magnitude == 0.0) continue
        // Hann 窗的幅度增益为 1/2；倾斜补偿先于选峰和显示下限，静音不能凭空产生能量。
        val amplitude = magnitude * 4.0 / real.size
        val tiltDb = SpectrumTiltDbPerOctave * log2(frequency / SpectrumBandEdgesHz.first())
        peaksDb[band] = maxOf(peaksDb[band], 20.0 * log10(amplitude) + tiltDb)
    }
    val levels = peaksDb.map { peakDb ->
        ((peakDb - SpectrumFloorDb) / -SpectrumFloorDb).coerceIn(0.0, 1.0).toFloat()
    }
    return AudioSpectrum(levels[0], levels[1], levels[2], levels[3], levels[4])
}
