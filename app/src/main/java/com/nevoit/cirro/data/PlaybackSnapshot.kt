package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.PlaybackQueue
import kotlinx.serialization.Serializable

/** 只保存继续播放所需的数据；实际播放历史和播放器的瞬态状态不进磁盘。 */
@Serializable
data class PlaybackSnapshot(
    val queue: PlaybackQueue,
    val positionMs: Long = 0L,
) {
    internal fun validated(): PlaybackSnapshot? {
        if (queue.entries.isEmpty()) return copy(positionMs = 0L)
        if (queue.currentIndex !in queue.entries.indices) return null
        val uids = HashSet<Long>(queue.entries.size)
        if (queue.entries.any { it.song.id <= 0L || it.uid <= 0L || it.uid == Long.MAX_VALUE || !uids.add(it.uid) }) {
            return null
        }
        val duration = queue.entries[queue.currentIndex].song.durationMs
        val position = positionMs.coerceAtLeast(0L).let { if (duration > 0L) it.coerceAtMost(duration) else it }
        return copy(positionMs = position)
    }
}
