package com.nevoit.cirro.data

import android.content.Context
import androidx.core.content.edit
import com.nevoit.cirro.data.model.AudioQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 最近一次换直链**实际拿到**的档位。
 *
 * 跟 [AudioQualityStore.current]（当前档）**不是一回事**：实测服务端会自己降级
 * （请求 `hires` 回 `lossless`、请求 `dolby` 回 `jyeffect`），这里记的才是真的。
 */
data class ResolvedAudioQuality(val songId: Long, val quality: AudioQuality)

/**
 * 音质档位的落盘。
 *
 * 只存一个 `level` 字符串，不是凭据，明文进 SharedPreferences 就够 —— 用不上
 * [SessionStore] 那套 AndroidKeyStore 加密。
 *
 * 对外给两个状态：[quality] 是当前档（读的一方在播放服务；写的一方是音质选择界面，
 * 界面还没做，所以现在写入口 [set] 暂时闲置），[resolved] 是服务端实际给的档（写的一方在
 * 播放服务的加载线程）。用流比两边各自回读 prefs / 传参干净。
 *
 * 默认取"标准"（128 kbps）：音质选择界面还没做，先把默认档压到最低以免白耗流量；
 * 等界面接上再谈默认值。
 */
class AudioQualityStore(context: Context) {

    private val prefs = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    private val _quality = MutableStateFlow(read())

    val quality: StateFlow<AudioQuality> = _quality.asStateFlow()

    private val _resolved = MutableStateFlow<ResolvedAudioQuality?>(null)

    val resolved: StateFlow<ResolvedAudioQuality?> = _resolved.asStateFlow()

    /** 当前档位。播放器每换一次直链读一次，不是热路径。 */
    val current: AudioQuality get() = _quality.value

    fun set(quality: AudioQuality) {
        if (quality == _quality.value) return
        prefs.edit { putString(KeyQuality, quality.wire) }
        _quality.value = quality
    }

    /**
     * 换直链后回报**实得档位**。在 ExoPlayer 的加载线程上调用，所以必须是线程安全的
     * （[MutableStateFlow] 是）。
     *
     * 认不出的 `level` 直接忽略：上游加了新档而本地还没认识时，宁可显示"未知"也不要瞎猜。
     */
    fun reportResolved(songId: Long, level: String) {
        val quality = AudioQuality.fromWire(level) ?: return
        _resolved.value = ResolvedAudioQuality(songId, quality)
    }

    private fun read(): AudioQuality =
        AudioQuality.fromWire(prefs.getString(KeyQuality, null).orEmpty()) ?: Default

    private companion object {
        const val PrefsName = "cirro_playback"

        // 键名带 _level 后缀是**故意的**：早期版本用旧键 `audio_quality` 且默认"极高"，
        // 若当时在面板里点过某档就会留下旧值。换键名让旧值失效，保证首次运行回到默认档。
        const val KeyQuality = "audio_quality_level"
        val Default = AudioQuality.STANDARD
    }
}
