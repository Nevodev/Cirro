package com.nevoit.cirro.playback

import androidx.media3.common.C
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
 * ## 为什么要分 [rememberTrack] 和 [selectTrack] 两个入口
 *
 * 响度数据是跟着 `song_url_v1` 来的，也就是只有数据源那层才知道；而"现在该用哪一首的增益"
 * 只有播放器知道。**这两件事不是同一个时刻**：ExoPlayer 会预读下一首，下一首的 resolve
 * 常常在当前这首还在放的时候就跑完了。所以数据源只能"记下来"，切换曲目时再"选出来"，
 * 直接拿 resolve 的结果去设当前增益会让当前这首在结尾几十秒突然变成下一首的音量。
 */
@UnstableApi
class LoudnessAudioProcessor : BaseAudioProcessor() {

    /** `"<id>@<level>"` → 线性增益。写入在加载线程，读在音频线程。 */
    private val tracks = ConcurrentHashMap<String, Float>()

    @Volatile
    private var currentKey: String? = null

    @Volatile
    private var gain = BaseGain

    /**
     * 换直链时顺手记下这首歌的响度，**不代表它正在播放**（可能是预读的下一首）。
     *
     * 🔴 [level] 必须带上：`gain` 是**跟着档位变的**（实测 lossless 比 standard 低约
     * 0.4 dB），只按 id 记会让切档后响度对不上。
     *
     * [gainDb] / [peak] 可能为 null，表示网易云没给这首歌算过，那就只乘基线。
     */
    fun rememberTrack(id: Long, level: String, gainDb: Double?, peak: Double?) {
        val key = trackKey(id, level)
        val value = linearGain(gainDb, peak)
        tracks[key] = value
        // 预读那首正好就是当前这首（首次播放、重试、跳播都会这样）时，记下就该立刻生效。
        if (currentKey == key) gain = value
    }

    /**
     * 切换曲目。[level] 传**当前档位**：切档后同一首歌的增益和已记账的那份对不上，
     * 查不到就先按基线走，等它下次换直链时 [rememberTrack] 补上。
     */
    fun selectTrack(id: Long?, level: String) {
        val key = id?.let { trackKey(it, level) }
        currentKey = key
        gain = key?.let { tracks[it] } ?: BaseGain
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
        // 档位也要进 key：同一首歌在不同档位下 gain 不同（见 rememberTrack 注释）。
        fun trackKey(id: Long, level: String): String = "$id@$level"

        fun linearGain(gainDb: Double?, peak: Double?): Float {
            var value = BaseGain * if (gainDb == null) 1.0 else 10.0.pow(gainDb / 20.0)
            if (peak != null && peak > 0.0) {
                value = minOf(value, 1.0 / peak)
            }
            return value.toFloat()
        }
    }
}
