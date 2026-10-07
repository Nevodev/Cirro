package com.nevoit.cirro.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/** Replays ordered analyzer windows on a steady render clock instead of keeping only the latest value. */
class AudioAnalysisStore {
    private data class Sample(val power: Float, val timestampNanos: Long)

    private val listener = AtomicReference<((Float) -> Unit)?>(null)
    private val samples = ConcurrentLinkedQueue<Sample>()
    private val handler = Handler(Looper.getMainLooper())
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
            val callback = listener.get() ?: run {
                lastTickNanos = 0L
                return
            }
            val now = System.nanoTime()
            val elapsed = if (lastTickNanos == 0L) TICK_INTERVAL_NANOS
            else (now - lastTickNanos).coerceIn(1_000_000L, 100_000_000L)
            lastTickNanos = now

            if (!hasNext) {
                val first = samples.poll()
                if (first != null) {
                    previous = first.power
                    next = first.power
                    hasNext = true
                    phaseNanos = 0L
                }
            }
            if (hasNext) {
                phaseNanos += elapsed
                while (phaseNanos >= expectedIntervalNanos) {
                    phaseNanos -= expectedIntervalNanos
                    previous = next
                    val sample = samples.poll()
                    if (sample == null) {
                        next = previous
                        break
                    }
                    next = sample.power
                    expectedIntervalNanos = sample.timestampNanos
                        .takeIf { it > 0L }
                        ?.let { WINDOW_INTERVAL_NANOS }
                        ?: WINDOW_INTERVAL_NANOS
                }
                val mix = (phaseNanos.toFloat() / expectedIntervalNanos).coerceIn(0f, 1f)
                callback(previous + (next - previous) * mix)
            } else {
                callback(0f)
            }
            tickCount++
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun setListener(callback: ((Float) -> Unit)?) {
        listener.set(callback)
        samples.clear()
        previous = 0f
        next = 0f
        hasNext = false
        phaseNanos = 0L
        lastTickNanos = 0L
        Log.i(TAG, "listener=${callback != null}")
        handler.removeCallbacks(ticker)
        if (callback != null) handler.post(ticker)
    }

    fun publish(power: Float, timestampNanos: Long = System.nanoTime()) {
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
        samples.add(Sample(power.coerceIn(0f, 1f), now))
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
