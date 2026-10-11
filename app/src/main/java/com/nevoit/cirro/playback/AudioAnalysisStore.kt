package com.nevoit.cirro.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/** Replays ordered analyzer windows on a steady render clock instead of keeping only the latest value. */
class AudioAnalysisStore {
    private data class Sample(
        val power: Float,
        val timestampNanos: Long,
        val spectrum: AudioSpectrum,
        val generation: Long,
    )

    private val listener = AtomicReference<((Float, Long) -> Unit)?>(null)
    private val samples = ConcurrentLinkedQueue<Sample>()
    private val handler = Handler(Looper.getMainLooper())
    // 行内频谱共享回放时钟，但不能占用背景节拍的单一回调。
    private val spectrumListeners = mutableSetOf<(AudioSpectrum) -> Unit>()
    private val spectrumGeneration = AtomicLong()
    private var renderedGeneration = 0L
    private var previousSpectrum = AudioSpectrum.Silence
    private var nextSpectrum = AudioSpectrum.Silence
    private var renderedSpectrum = AudioSpectrum.Silence
    private val spectrumSmoother = AudioSpectrumSmoother()
    private var ticking = false
    private var previous = 0f
    private var next = 0f
    private var hasNext = false
    private var phaseNanos = 0L
    private var lastTickNanos = 0L
    private var lastLogMs = 0L
    private var inputCount = 0L
    private var tickCount = 0L
    private var lastInputNanos = 0L
    private var expectedIntervalNanos = WINDOW_INTERVAL_NANOS

    private val ticker = object : Runnable {
        override fun run() {
            val callback = listener.get()
            if (callback == null && spectrumListeners.isEmpty()) {
                ticking = false
                lastTickNanos = 0L
                return
            }
            val generation = spectrumGeneration.get()
            if (renderedGeneration != generation) {
                renderedGeneration = generation
                previousSpectrum = AudioSpectrum.Silence
                nextSpectrum = AudioSpectrum.Silence
                renderedSpectrum = AudioSpectrum.Silence
                spectrumSmoother.reset()
                hasNext = false
                phaseNanos = 0L
            }
            val now = System.nanoTime()
            val elapsed = if (lastTickNanos == 0L) TICK_INTERVAL_NANOS
            else (now - lastTickNanos).coerceIn(1_000_000L, 100_000_000L)
            lastTickNanos = now

            if (!hasNext) {
                val first = pollCurrentSample()
                if (first != null) {
                    previous = first.power
                    next = first.power
                    previousSpectrum = first.spectrum
                    nextSpectrum = first.spectrum
                    hasNext = true
                    phaseNanos = 0L
                }
            }
            // 清空 PCM 后暂时没有样本，不代表歌曲真的播放了一个静音节拍。
            if (hasNext) {
                phaseNanos += elapsed
                while (phaseNanos >= expectedIntervalNanos) {
                    phaseNanos -= expectedIntervalNanos
                    previous = next
                    previousSpectrum = nextSpectrum
                    val sample = pollCurrentSample()
                    if (sample == null) {
                        next = previous
                        break
                    }
                    next = sample.power
                    nextSpectrum = sample.spectrum
                    expectedIntervalNanos = sample.timestampNanos
                        .takeIf { it > 0L }
                        ?.let { WINDOW_INTERVAL_NANOS }
                        ?: WINDOW_INTERVAL_NANOS
                }
                val mix = (phaseNanos.toFloat() / expectedIntervalNanos).coerceIn(0f, 1f)
                callback?.invoke(previous + (next - previous) * mix, renderedGeneration)
                renderedSpectrum = spectrumSmoother.update(
                    previousSpectrum.interpolate(nextSpectrum, mix), elapsed,
                )
            }
            spectrumListeners.forEach { it(renderedSpectrum) }
            tickCount++
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun setListener(callback: ((Float, Long) -> Unit)?) {
        listener.set(callback)
        Log.i(TAG, "listener=${callback != null}")
        updateTicker()
    }

    internal fun addSpectrumListener(callback: (AudioSpectrum) -> Unit) {
        spectrumListeners.add(callback)
        callback(if (renderedGeneration == spectrumGeneration.get()) renderedSpectrum else AudioSpectrum.Silence)
        updateTicker()
    }

    internal fun removeSpectrumListener(callback: (AudioSpectrum) -> Unit) {
        spectrumListeners.remove(callback)
        updateTicker()
    }

    fun resetSpectrum() {
        samples.clear()
        spectrumGeneration.incrementAndGet()
    }

    private fun pollCurrentSample(): Sample? {
        while (true) {
            val sample = samples.poll() ?: return null
            if (sample.generation == renderedGeneration) return sample
        }
    }

    private fun updateTicker() {
        val active = listener.get() != null || spectrumListeners.isNotEmpty()
        if (active == ticking) return
        ticking = active
        handler.removeCallbacks(ticker)
        samples.clear()
        previous = 0f
        next = 0f
        hasNext = false
        phaseNanos = 0L
        lastTickNanos = 0L
        previousSpectrum = AudioSpectrum.Silence
        nextSpectrum = AudioSpectrum.Silence
        renderedSpectrum = AudioSpectrum.Silence
        spectrumSmoother.reset()
        if (active) handler.post(ticker)
    }

    fun publish(
        power: Float,
        timestampNanos: Long = System.nanoTime(),
        spectrum: AudioSpectrum = AudioSpectrum.Silence,
    ) {
        val now = timestampNanos
        val previousInput = lastInputNanos
        if (previousInput > 0L) {
            val observed = now - previousInput
            if (observed in 1_000_000L..100_000_000L) {
                expectedIntervalNanos = WINDOW_INTERVAL_NANOS
            }
        }
        lastInputNanos = now
        // Keep a short real-time buffer. Dropping only the oldest data prevents unbounded latency.
        samples.add(Sample(power.coerceIn(0f, 1f), now, spectrum, spectrumGeneration.get()))
        while (samples.size > MAX_BUFFERED_SAMPLES) samples.poll()

        inputCount++
        val logNow = SystemClock.uptimeMillis()
        if (logNow - lastLogMs >= 1000L) {
            lastLogMs = logNow
            Log.d(TAG, "input count=$inputCount queue=${samples.size} ticks=$tickCount power=${"%.3f".format(power)} listener=${listener.get() != null}")
            inputCount = 0L
            tickCount = 0L
        }
    }

    private companion object {
        const val TAG = "CirroPcmAudio"
        const val TICK_MS = 16L
        const val TICK_INTERVAL_NANOS = 16_000_000L
        const val WINDOW_INTERVAL_NANOS = 21_333_333L
        const val MAX_BUFFERED_SAMPLES = 96
    }
}
