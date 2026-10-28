package com.nevoit.cirro.playback

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

// 全局基线。改成 1f 就等于跳过这一步，但别把它删了：它是给 gain 的正值留余量的旋钮
// （gain 实测最高 +14.75 dB，而 peak 又常常已经顶到 1.0，不留余量就直接撞满刻度）。
private const val BaseGain = 1f

/**
 * 响度归一：把每首歌按网易云给的 `gain` 拉到同一条基准线上，免得好不容易排好的歌单里
 * 一首轻一首重。
 *
 * 为什么不用 media3 自带的 `GainProcessor`：它的 `setGain(float)` 只接受 0f..1f，也就是
 * 只会衰减。而网易云的 `gain` 是会取正值的，"提上来"这个方向它表达不了。
 *
 * 增益分两层叠：
 * 1. [BaseGain] 全局基线；
 * 2. 每首歌自己的 `gain`（dB）换算成线性系数乘上去。
 *
 * 最后用 `peak` 兜底：输出峰值 = 原始峰值 × 总增益，越过 1.0 就削波，所以总增益封顶 `1 / peak`。
 * 实测有约 4% 的歌 `peak × 10^(gain/20)` 会过 1.0，这一钳省不得。
 *
 * 数据源只用 [rememberTrack] 记下增益：预读下一首时不能改变当前音频。
 * [onFlush] 根据实际输入流的条目身份选取增益，此时旧流已排空，新流还未进入处理器。
 * 不能依赖主线程的切歌通知：下一首开头可能已经按旧增益写入音频缓冲，通知后再切换
 * 会让下一首播放一段后突然变音量。
 */
@UnstableApi
class LoudnessAudioProcessor : BaseAudioProcessor() {

    /** 数据源的条目 key → 实际加载音质的线性增益。写入在加载线程，读在音频线程。 */
    private val tracks = ConcurrentHashMap<String, Float>()

    private var gain = BaseGain
    private val period = Timeline.Period()
    private val window = Timeline.Window()

    /**
     * 换直链时顺手记下这首歌的响度，**不代表它正在播放**（可能是预读的下一首）。
     *
     * [key] 与 MediaItem 的 customCacheKey 一致，同一首歌的不同入队条目也要分开。
     * 增益跟随这次直链响应，不能在开始播放时重新读取设置档位：已预读的音频可能还是旧档位。
     *
     * [gainDb] / [peak] 可能为 null，表示网易云没给这首歌算过，那就只乘基线。
     */
    fun rememberTrack(key: String, gainDb: Double?, peak: Double?) {
        tracks[key] = linearGain(gainDb, peak)
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val timeline = streamMetadata.timeline
        val periodUid = streamMetadata.periodUid
        val key = if (periodUid != null && timeline.getIndexOfPeriod(periodUid) != C.INDEX_UNSET) {
            timeline.getPeriodByUid(periodUid, period)
            val item = timeline.getWindow(period.windowIndex, window).mediaItem
            item.localConfiguration?.customCacheKey ?: item.mediaId
        } else {
            null
        }
        gain = key?.let { tracks[it] } ?: BaseGain
    }

    override fun onReset() {
        gain = BaseGain
    }

    // 父类的实现是"configure 是否已经配出输出格式"，不是空壳，所以必须转给它——
    // 内置的 GainProcessor 也是 `super.isActive() && ...` 这个写法。
    // 之后不再叠加增益条件：media3 只在 configure 时问一次，让结果跟着增益变
    // 会导致处理链每首歌重建一次。
    override fun isActive(): Boolean = super.isActive()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> Unit
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // 逐样本缩放，采样率和声道数都不动。
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val current = gain
        // Unity 快路径：无增益时整块拷贝，省掉音频线程上逐样本的读-乘-写。
        if (current == 1f) {
            replaceOutputBuffer(size).put(inputBuffer).flip()
            return
        }
        val output = replaceOutputBuffer(size)
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> while (inputBuffer.hasRemaining()) {
                val scaled = (inputBuffer.short * current).toInt()
                output.putShort(scaled.coerceIn(-32768, 32767).toShort())
            }

            C.ENCODING_PCM_FLOAT -> while (inputBuffer.hasRemaining()) {
                output.putFloat(inputBuffer.float * current)
            }
        }
        output.flip()
    }

    private companion object {
        fun linearGain(gainDb: Double?, peak: Double?): Float {
            var value = BaseGain * if (gainDb == null) 1.0 else 10.0.pow(gainDb / 20.0)
            if (peak != null && peak > 0.0) {
                value = minOf(value, 1.0 / peak)
            }
            return value.toFloat()
        }
    }
}
