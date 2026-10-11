package com.nevoit.cirro.playback

import kotlin.math.abs
import kotlin.math.exp

internal class AudioSpectrumSmoother(initial: AudioSpectrum = AudioSpectrum.Silence) {
    private var current = initial

    fun update(target: AudioSpectrum, elapsedNanos: Long): AudioSpectrum {
        if (elapsedNanos <= 0L) return current
        val elapsedSeconds = elapsedNanos / 1_000_000_000.0
        // 按实际经过的时间计算跟随系数，避免刷新率和主线程调度改变平滑强度。
        val attack = (1.0 - exp(-elapsedSeconds / AttackSeconds)).toFloat()
        val release = (1.0 - exp(-elapsedSeconds / ReleaseSeconds)).toFloat()
        fun smooth(previous: Float, next: Float): Float {
            val fraction = if (next > previous) attack else release
            val value = previous + (next - previous) * fraction
            return if (abs(next - value) < 0.0001f) next else value
        }
        current = AudioSpectrum(
            smooth(current.low, target.low),
            smooth(current.lowMid, target.lowMid),
            smooth(current.mid, target.mid),
            smooth(current.highMid, target.highMid),
            smooth(current.high, target.high),
        )
        return current
    }

    fun reset() {
        current = AudioSpectrum.Silence
    }

    private companion object {
        const val AttackSeconds = 0.065
        const val ReleaseSeconds = 0.180
    }
}
