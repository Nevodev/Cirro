package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.AudioQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ResolvedAudioQuality(
    val songId: Long,
    val quality: AudioQuality,
    val requestedQuality: AudioQuality? = null,
)

internal class ResolvedAudioQualityState {
    private val values = MutableStateFlow<Map<String, ResolvedAudioQuality>>(emptyMap())
    val resolved = values.asStateFlow()

    fun report(key: String, songId: Long, level: String, requestedQuality: AudioQuality? = null) {
        val quality = AudioQuality.fromWire(level)
        values.update { previous ->
            if (quality == null) previous - key
            else previous + (key to ResolvedAudioQuality(songId, quality, requestedQuality))
        }
    }

    fun retain(keys: Set<String>) {
        values.update { previous -> previous.filterKeys { it in keys } }
    }

    fun restore(key: String, quality: ResolvedAudioQuality) {
        // 恢复快照与实际音源解析可以并发；磁盘旧值不能覆盖已返回的实际结果。
        values.update { previous -> if (key in previous) previous else previous + (key to quality) }
    }
}
