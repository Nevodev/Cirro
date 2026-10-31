package com.nevoit.cirro.playback

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Size
import com.nevoit.cirro.data.AudioQualityStore
import com.nevoit.cirro.data.coverSideFor
import com.nevoit.cirro.data.model.AudioQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

@OptIn(UnstableApi::class)
internal class NextTrackPreloader(
    context: Context,
    private val cache: PlaybackAudioCache,
    private val quality: AudioQualityStore,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private var target: Target? = null
    private var task: AudioPreloadTask? = null
    private var job: Job? = null
    private var retryAt = 0L
    private var coverTarget: String? = null
    private var coverJob: Job? = null
    private var coverRetryAt = 0L

    fun update(player: Player) {
        val index = player.nextMediaItemIndex
        val item = if (index != C.INDEX_UNSET && index != player.currentMediaItemIndex) {
            player.getMediaItemAt(index)
        } else null
        val songId = item?.mediaId?.toLongOrNull()
        cache.setNextSong(songId)
        val active = player.playWhenReady && player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        updateCover(
            item?.mediaMetadata?.artworkUri?.toString()?.takeIf { active && it.isNotEmpty() },
            player.playbackState == Player.STATE_READY,
        )
        val next = if (active && item != null) {
            songId?.let {
                Target(player.currentMediaItem?.queueEntryUid, item.queueEntryUid, it, quality.current, cache.owner())
            }
        } else null
        if (next == target) return
        cancelAudio()
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
        cancelAudio()
        coverJob?.cancel()
        coverJob = null
        coverTarget = null
    }

    private fun updateCover(cover: String?, ready: Boolean) {
        if (cover == coverTarget) return
        coverJob?.cancel()
        coverJob = null
        coverTarget = null
        if (cover == null || !ready || SystemClock.elapsedRealtime() < coverRetryAt) return
        coverTarget = cover
        coverJob = scope.launch {
            try {
                // 大封面默认请求原尺寸；背景和迷你条经 Mapper 后是不同 URL，必须分别预热。
                val sizes = listOf(Size.ORIGINAL, Size(CoverTextureSize, CoverTextureSize),
                    coverSideFor(Size(1, 1)).let { Size(it, it) })
                for (size in sizes) {
                    val result = appContext.imageLoader.execute(
                        ImageRequest.Builder(appContext)
                            .data(cover)
                            .size(size)
                            .allowHardware(false)
                            .build(),
                    )
                    if (result is ErrorResult) throw result.throwable
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("NextTrackPreloader", "下一首封面预缓存未完成，稍后重试", error)
                coverTarget = null
                coverRetryAt = SystemClock.elapsedRealtime() + 5_000
            }
        }
    }

    private fun cancelAudio() {
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
