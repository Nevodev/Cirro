package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.AudioQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ResolvedAudioQuality(val songId: Long, val quality: AudioQuality)

internal class ResolvedAudioQualityState {
    private val values = MutableStateFlow<Map<String, ResolvedAudioQuality>>(emptyMap())
    val resolved = values.asStateFlow()

    fun report(key: String, songId: Long, level: String) {
        val quality = AudioQuality.fromWire(level)
        values.update { previous ->
            if (quality == null) previous - key
            else previous + (key to ResolvedAudioQuality(songId, quality))
        }
    }

    fun retain(keys: Set<String>) {
        values.update { previous -> previous.filterKeys { it in keys } }
    }
}
