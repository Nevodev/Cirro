package com.nevoit.cirro.playback

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.nevoit.cirro.data.AudioQualityStore
import com.nevoit.cirro.data.model.AudioQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

@OptIn(UnstableApi::class)
internal class NextTrackPreloader(
    private val cache: PlaybackAudioCache,
    private val quality: AudioQualityStore,
    private val scope: CoroutineScope,
) {
    private var target: Target? = null
    private var task: AudioPreloadTask? = null
    private var job: Job? = null
    private var retryAt = 0L

    fun update(player: Player) {
        val index = player.nextMediaItemIndex
        val item = if (index != C.INDEX_UNSET && index != player.currentMediaItemIndex) {
            player.getMediaItemAt(index)
        } else null
        val songId = item?.mediaId?.toLongOrNull()
        cache.setNextSong(songId)
        val next = if (player.playWhenReady && player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED && item != null
        ) {
            songId?.let {
                Target(player.currentMediaItem?.queueEntryUid, item.queueEntryUid, it, quality.current, cache.owner())
            }
        } else null
        if (next == target) return
        cancel()
        if (next == null || player.playbackState != Player.STATE_READY ||
            SystemClock.elapsedRealtime() < retryAt
        ) return
        target = next
        val operation = AudioPreloadTask()
        task = operation
        Log.i("NextTrackPreloader", "开始预缓存下一首 song=${next.songId}")
        job = scope.launch {
            try {
                runInterruptible(Dispatchers.IO) {
                    cache.preload(next.songId, next.quality, next.owner, operation)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("NextTrackPreloader", "下一首预缓存未完成 song=${next.songId}，稍后重试", error)
                // 留下已写入的片段，后续只补缺口；后台失败不打断当前播放。
                if (task === operation) {
                    target = null
                    retryAt = SystemClock.elapsedRealtime() + 5_000
                }
            }
        }
    }

    fun cancel() {
        task?.cancel()
        job?.cancel()
        task = null
        job = null
        target = null
    }

    private data class Target(
        val currentUid: Long?,
        val nextUid: Long?,
        val songId: Long,
        val quality: AudioQuality,
        val owner: Long,
    )
}
