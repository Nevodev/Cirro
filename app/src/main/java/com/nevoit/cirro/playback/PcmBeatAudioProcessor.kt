package com.nevoit.cirro.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Pass-through PCM processor that feeds PearWall's native classic analyzer. */
@UnstableApi
class PcmBeatAudioProcessor(
    private val output: AudioAnalysisStore,
) : BaseAudioProcessor() {
    private val fftSize = 1024
    private val samples = FloatArray(fftSize)
    private val real = DoubleArray(fftSize)
    private val imaginary = DoubleArray(fftSize)
    private var sampleCount = 0
    private var channelIndex = 0
    private var sampleRate = 48_000
    private var channels = 2
    private var analyzer: ClassicAudioAnalyzer? = null
    private var audioTimestampNanos = 0L
    private var windowCount = 0L
    private var lastWindowLogMs = 0L
    private var lastWindowWallNanos = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> Unit
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(1)
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        analyzer?.close()
        analyzer = ClassicAudioAnalyzer()
        sampleCount = 0
        channelIndex = 0
        audioTimestampNanos = 0L
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val source = inputBuffer.duplicate().order(inputBuffer.order())
        analyze(source)
        replaceOutputBuffer(size).put(inputBuffer).flip()
    }

    override fun onFlush() {
        sampleCount = 0
        channelIndex = 0
        audioTimestampNanos = 0L
        analyzer?.reset()
    }

    override fun onReset() {
        analyzer?.close()
        analyzer = null
        sampleCount = 0
        channelIndex = 0
        audioTimestampNanos = 0L
    }

    private fun analyze(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            val value = when (inputAudioFormat.encoding) {
                C.ENCODING_PCM_16BIT -> buffer.short / 32768f
                C.ENCODING_PCM_FLOAT -> buffer.float.coerceIn(-1f, 1f)
                else -> return
            }
            if (channelIndex == 0) samples[sampleCount] = value
            channelIndex++
            if (channelIndex == channels) {
                channelIndex = 0
                sampleCount++
            }
            if (sampleCount == fftSize) {
                publishWindow()
                sampleCount = 0
            }
        }
    }

    private fun publishWindow() {
        val windowDurationNanos = fftSize.toLong() * 1_000_000_000L / sampleRate
        val now = if (audioTimestampNanos == 0L) System.nanoTime() else audioTimestampNanos + windowDurationNanos
        audioTimestampNanos = now
        val waveform = ByteArray(fftSize) { index ->
            (samples[index].coerceIn(-1f, 1f) * 127f).toInt().toByte()
        }
        for (i in 0 until fftSize) {
            real[i] = samples[i].toDouble() * (0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1)))
            imaginary[i] = 0.0
        }
        fft()
        // Visualizer's FFT payload is one signed byte per real/imag component.
        // Normalize the unscaled DFT before quantizing; otherwise almost every bin clips.
        val spectrum = ByteArray(fftSize)
        val scale = 2.0 / fftSize
        for (i in spectrum.indices) {
            val value = if (i % 2 == 0) real[i / 2] else imaginary[i / 2]
            spectrum[i] = (value * scale * 127.0).coerceIn(-128.0, 127.0).toInt().toByte()
        }
        val current = analyzer ?: return
        current.processWaveform(waveform, sampleRate.toFloat(), now)
        output.publish(
            current.processFft(spectrum, sampleRate.toFloat(), now).coerceIn(0f, 1f),
            now,
        )
        windowCount++
        val gapMs = if (lastWindowWallNanos == 0L) 0L else
            (now - lastWindowWallNanos) / 1_000_000L
        lastWindowWallNanos = now
        val logNow = SystemClock.uptimeMillis()
        if (logNow - lastWindowLogMs >= 1000L) {
            Log.d("CirroPcmAudio", "windows=$windowCount lastGapMs=$gapMs expectedMs=${fftSize * 1000 / sampleRate}")
            lastWindowLogMs = logNow
            windowCount = 0L
        }
    }

    private fun fft() {
        var j = 0
        for (i in 1 until fftSize) {
            var bit = fftSize shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
            }
        }
        var length = 2
        while (length <= fftSize) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle)
            val stepImaginary = sin(angle)
            var currentReal = 1.0
            var currentImaginary = 0.0
            for (start in 0 until length / 2) {
                var i = start
                while (i < fftSize) {
                    val pair = i + length / 2
                    val tr = currentReal * real[pair] - currentImaginary * imaginary[pair]
                    val ti = currentReal * imaginary[pair] + currentImaginary * real[pair]
                    real[pair] = real[i] - tr; imaginary[pair] = imaginary[i] - ti
                    real[i] += tr; imaginary[i] += ti
                    i += length
                }
                val nextReal = currentReal * stepReal - currentImaginary * stepImaginary
                currentImaginary = currentReal * stepImaginary + currentImaginary * stepReal
                currentReal = nextReal
            }
            length = length shl 1
        }
    }

}
