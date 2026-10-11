package com.nevoit.cirro.playback.pearmesh.gl

import com.nevoit.cirro.playback.pearmesh.AudioPowerFrame
import kotlin.math.abs
import kotlin.math.exp

internal class AudioPulseEnvelope {
    private val current = FloatArray(3)
    private var previousRenderNanos = 0L
    private var initialized = false
    private var hadAudio = false
    private var generation = 0L
    private val transitionOffset = FloatArray(3)
    private var transitionStartNanos = 0L

    var hasCurrentAudio: Boolean = false
        private set

    fun update(
        frame: AudioPowerFrame,
        enabled: Boolean,
        nowNanos: Long,
        demoPower: Float? = null,
        paused: Boolean = false,
    ): FloatArray {
        hasCurrentAudio = !paused && enabled && frame.currentUpdatedAtNanos != 0L &&
                nowNanos - frame.currentUpdatedAtNanos <= AudioReportTimeoutNanos
        val hasAudio = !paused && (demoPower != null || hasCurrentAudio)
        val target = when {
            demoPower != null -> FloatArray(3) { demoPower.coerceIn(0f, 1f) }
            hasCurrentAudio -> interpolatedPower(frame, nowNanos)
            else -> FloatArray(3)
        }
        val elapsed = if (previousRenderNanos == 0L) 0L else
            (nowNanos - previousRenderNanos).coerceAtLeast(0L)
        previousRenderNanos = nowNanos
        if (hasAudio) {
            if (initialized && (!hadAudio || frame.generation != generation)) {
                transitionStartNanos = nowNanos
                for (lane in current.indices) transitionOffset[lane] = current[lane] - target[lane]
            }
            val remaining = if (transitionStartNanos == 0L) 0f else
                (1f - (nowNanos - transitionStartNanos).toFloat() / InputTransitionNanos).coerceIn(
                    0f,
                    1f
                )
            for (lane in current.indices) {
                // 只在输入重新接续时消除跳变；正常节拍直接使用原始插值，不另加回落包络。
                current[lane] = (target[lane] + transitionOffset[lane] * remaining).coerceIn(0f, 1f)
            }
            if (remaining == 0f) transitionStartNanos = 0L
            generation = frame.generation
        } else {
            transitionStartNanos = 0L
            val releaseSeconds = if (paused) PauseReleaseSeconds else InputLossReleaseSeconds
            val release = (1.0 - exp(-elapsed / 1_000_000_000.0 / releaseSeconds)).toFloat()
            for (lane in current.indices) {
                val value = current[lane] * (1f - release)
                current[lane] = if (abs(value) < 0.0001f) 0f else value
            }
        }
        initialized = true
        hadAudio = hasAudio
        return current.copyOf()
    }

    private fun interpolatedPower(frame: AudioPowerFrame, nowNanos: Long): FloatArray {
        val reportNanos = frame.currentUpdatedAtNanos - frame.previousUpdatedAtNanos
        if (frame.previousUpdatedAtNanos == 0L || reportNanos <= 0L) {
            return FloatArray(3) { frame.currentPower[it].coerceIn(0f, 1f) }
        }

        // 延后一个上报间隔，插值时才能同时拿到两个端点。
        val sampleNanos = nowNanos - reportNanos
        val mix = ((sampleNanos - frame.previousUpdatedAtNanos).toDouble() / reportNanos)
            .toFloat().coerceIn(0f, 1f)
        return FloatArray(3) { lane ->
            (frame.previousPower[lane] +
                    (frame.currentPower[lane] - frame.previousPower[lane]) * mix).coerceIn(0f, 1f)
        }
    }

    private companion object {
        const val PauseReleaseSeconds = 2.000 // 2s
        const val InputLossReleaseSeconds = 0.180 // 180ms
        const val InputTransitionNanos = 2_000_000_000f // 2000ms
        const val AudioReportTimeoutNanos = 250_000_000L
    }
}
