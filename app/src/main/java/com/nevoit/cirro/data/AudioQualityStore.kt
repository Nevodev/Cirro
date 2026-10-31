package com.nevoit.cirro.data

import android.content.Context
import androidx.core.content.edit
import com.nevoit.cirro.data.model.AudioQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 音质和音量平衡设置的落盘。
 *
 * 设置不包含凭据，明文进 SharedPreferences 就够 —— 用不上
 * [SessionStore] 那套 AndroidKeyStore 加密。
 *
 * [quality] 是用户选择的档位，[resolved] 是服务端实际给的档位，
 * [volumeBalance] 控制输出链上的响度归一。
 *
 * 默认音质为标准，音量平衡保持开启。
 */
class AudioQualityStore(context: Context) {

    private val prefs = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    private val _quality = MutableStateFlow(read())

    val quality: StateFlow<AudioQuality> = _quality.asStateFlow()

    private val _volumeBalance = MutableStateFlow(prefs.getBoolean(KeyVolumeBalance, true))

    val volumeBalance: StateFlow<Boolean> = _volumeBalance.asStateFlow()

    private val resolvedState = ResolvedAudioQualityState()

    val resolved: StateFlow<Map<String, ResolvedAudioQuality>> = resolvedState.resolved

    /** 当前档位。播放器每换一次直链读一次，不是热路径。 */
    val current: AudioQuality get() = _quality.value

    fun set(quality: AudioQuality) {
        require(quality in AudioQuality.supported) { "Unsupported audio quality: ${quality.wire}" }
        if (quality == _quality.value) return
        prefs.edit { putString(KeyQuality, quality.wire) }
        _quality.value = quality
    }

    fun setVolumeBalance(enabled: Boolean) {
        if (enabled == _volumeBalance.value) return
        prefs.edit { putBoolean(KeyVolumeBalance, enabled) }
        _volumeBalance.value = enabled
    }

    /**
     * 按 MediaItem 的 customCacheKey 记录实际档位，避免下一首的预加载覆盖当前条目。
     * 加载线程写入，主线程读取或裁剪；未知档位清除旧结果，不能沿用之前的标签。
     */
    fun reportResolved(key: String, songId: Long, level: String) =
        resolvedState.report(key, songId, level)

    fun retainResolved(keys: Set<String>) = resolvedState.retain(keys)

    private fun read(): AudioQuality =
        AudioQuality.fromWire(prefs.getString(KeyQuality, null).orEmpty())
            ?.takeIf { it in AudioQuality.supported } ?: Default

    private companion object {
        const val PrefsName = "cirro_playback"

        // 键名带 _level 后缀是**故意的**：早期版本用旧键 `audio_quality` 且默认"极高"，
        // 若当时在面板里点过某档就会留下旧值。换键名让旧值失效，保证首次运行回到默认档。
        const val KeyQuality = "audio_quality_level"
        const val KeyVolumeBalance = "volume_balance"
        val Default = AudioQuality.STANDARD
    }
}
