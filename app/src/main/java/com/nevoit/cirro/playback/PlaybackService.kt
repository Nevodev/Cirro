package com.nevoit.cirro.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.nevoit.cirro.MainActivity
import com.nevoit.cirro.data.AudioQualityStore
import com.nevoit.cirro.data.AccountRepository
import com.nevoit.cirro.data.PlayQueueStore
import com.nevoit.cirro.data.model.AudioQuality
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.PlayMode
import com.nevoit.cirro.data.model.withCurrent
import com.nevoit.cirro.lyrics.data.LyricsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 播放器宿主。
 *
 * 播放必须活在 Service 里：切后台、锁屏、被系统回收的时候音乐都不能停。用
 * MediaSessionService 还能顺带拿到通知栏、锁屏控制和耳机线控——这几样自己写一遍
 * 只会更长也更容易出错。
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService(), KoinComponent {

    /**
     * 音质档位。在换直链时**现取**，所以**改档是"下一首生效"**——
     * 已经在缓冲或正在播的那首不会被打断重载。
     */
    private val qualityStore: AudioQualityStore by inject()

    /**
     * 队列顺序由 PlaybackConnection 写；这里同步真实游标与进度，并追加独立的历史。
     */
    private val queueStore: PlayQueueStore by inject()

    /** 歌词。切歌时顺手预取，界面要显示时通常已经就绪。 */
    private val lyrics: LyricsRepository by inject()

    private var session: MediaSession? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val account: AccountRepository by inject()
    private val reports: PlaybackReportRepository by inject()
    private val audioCache: PlaybackAudioCache by inject()
    private val nextTrackPreloader by lazy { NextTrackPreloader(this, audioCache, qualityStore, serviceScope) }
    private var countedPlaybackUid: Long? = null
    private val listening by lazy {
        PlaybackListeningSession(emit = reports::enqueue, checkpoint = reports::checkpoint)
    }
    private val outputAccounting by lazy { PlaybackOutputAccounting(listening) }

    /** 响度归一挂在音频输出链上，换歌时由数据源那层喂 gain（见 [mediaSourceFactory]）。 */
    private val loudness = LoudnessAudioProcessor()
    private val audioAnalysis: AudioAnalysisStore by inject()
    private val beatAnalyzer by lazy { PcmBeatAudioProcessor(audioAnalysis) }

    override fun onCreate() {
        super.onCreate()
        val restored = queueStore.snapshot()
        val restoredEntry = restored.queue.entries.getOrNull(restored.queue.currentIndex)
        qualityStore.retainResolved(setOfNotNull(restoredEntry?.let { "${it.song.id}@${it.uid}" }))
        qualityStore.restorePlaybackQuality(restored)
        loudness.enabled = qualityStore.volumeBalance.value
        serviceScope.launch {
            qualityStore.volumeBalance.collect { loudness.enabled = it }
        }
        val player = ExoPlayer.Builder(this, AudioRenderersFactory(this, loudness, beatAnalyzer, outputAccounting))
            .setMediaSourceFactory(mediaSourceFactory())
            .setPriorityTaskManager(audioCache.priority)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val window = Timeline.Window()
                qualityStore.retainResolved(buildSet {
                    for (index in 0 until timeline.windowCount) {
                        timeline.getWindow(index, window).mediaItem.localConfiguration?.customCacheKey
                            ?.let { add(it) }
                    }
                })
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
                    countedPlaybackUid != mediaItem?.queueEntryUid
                ) countedPlaybackUid = null
                audioCache.setPlayingSong(
                    mediaItem?.mediaId?.toLongOrNull(), mediaItem?.localConfiguration?.customCacheKey,
                )
                queueStore.finishPlaybackHistory(
                    nextUid = mediaItem?.queueEntryUid,
                    repeated = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
                )
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
                    mediaItem?.queueEntryUid != queueStore.queue.value.entries.getOrNull(queueStore.queue.value.currentIndex)?.uid
                ) {
                    listening.transition(mediaItem?.queueEntryUid, when (reason) {
                        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> ListeningEnd.Natural
                        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> ListeningEnd.User
                        else -> ListeningEnd.Interrupted
                    })
                }

                // 歌词预取：切歌就后台取一次，不阻塞播放。
                // 队列里能拿到完整歌曲信息就用它（lrclib 那级要靠曲名/歌手模糊查）；
                // 拿不到（如 Activity 重建后模型为空）就只给 id —— 前三级只用 id，照样能取。
                mediaItem?.mediaId?.toLongOrNull()?.let { songId ->
                    val entry = queueStore.queue.value.entries.getOrNull(
                        player.currentMediaItemIndex
                    )
                    lyrics.prefetch(entry?.song ?: Song(id = songId))
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    countedPlaybackUid = null
                    queueStore.finishPlaybackHistory()
                    sampleListening(player)
                    listening.finish(ListeningEnd.Natural)
                } else if (playbackState == Player.STATE_IDLE) {
                    sampleListening(player)
                    listening.finish(ListeningEnd.Interrupted)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                sampleListening(player)
                listening.finish(ListeningEnd.Error, error.errorCode)
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                listening.observePosition(oldPosition.mediaItem?.queueEntryUid, oldPosition.positionMs)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                savePlayback(player)
                sampleListening(player)
                nextTrackPreloader.update(player)
                if (!player.isPlaying) return
                val uid = player.currentMediaItem?.queueEntryUid ?: return
                if (countedPlaybackUid != uid) {
                    countedPlaybackUid = uid
                    player.currentMediaItem?.mediaId?.toLongOrNull()?.let(audioCache::recordPlay)
                }
                // 同一首歌可重复入队，索引也会因拖拽改变，必须按条目身份匹配实际出声的歌曲。
                val entry = queueStore.queue.value.entries.firstOrNull { it.uid == uid } ?: return
                queueStore.markPlaying(entry)
            }
        })

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(PlaybackSessionCallback())
            .build()
        serviceScope.launch {
            account.session.collect {
                sampleListening(player)
                nextTrackPreloader.update(player)
            }
        }
        serviceScope.launch {
            qualityStore.quality.collect { nextTrackPreloader.update(player) }
        }
        serviceScope.launch {
            while (isActive) {
                delay(5_000L)
                if (player.isPlaying) savePlayback(player)
                sampleListening(player)
                nextTrackPreloader.update(player)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private fun sampleListening(player: Player) {
        val queue = queueStore.queue.value
        val uid = player.currentMediaItem?.queueEntryUid
        val userId = (account.session.value as? Session.LoggedIn)?.profile?.userId ?: 0L
        val tracks = queue.entries.map {
            ListeningTrack(it.uid, it.song.id, userId,
                if (queue.radio) "userfm" else "list", it.source?.scrobbleId.orEmpty(),
                if (it.uid == uid) player.duration.takeIf { duration -> duration != C.TIME_UNSET } ?: it.song.durationMs
                    else it.song.durationMs,
                when {
                    queue.infinite -> "infinite"
                    queue.mode == PlayMode.REPEAT_ONE -> "single"
                    queue.shuffle -> "ai_random"
                    queue.mode == PlayMode.REPEAT_ALL -> "circulation"
                    else -> "loop"
                })
        }
        listening.setTracks(tracks)
        listening.update(uid, player.currentPosition)
    }

    /** 用户把任务从最近应用里划掉：正在播就继续播，已经停了才收掉服务。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player ?: return
        savePlayback(player)
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.player?.let {
            savePlayback(it)
            sampleListening(it)
        }
        listening.finish(ListeningEnd.Interrupted)
        queueStore.finishPlaybackHistory()
        nextTrackPreloader.cancel()
        audioCache.setPlayingSong(null)
        audioCache.setNextSong(null)
        serviceScope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        qualityStore.retainResolved(emptySet())
        super.onDestroy()
    }

    private fun savePlayback(player: Player) {
        val uid = player.currentMediaItem?.queueEntryUid ?: return
        val queue = queueStore.queue.value
        val index = if (queue.entries.getOrNull(queue.currentIndex)?.uid == uid) queue.currentIndex
            else queue.entries.indexOfFirst { it.uid == uid }
        if (index < 0) return
        if (index != queue.currentIndex) queueStore.set(queue.withCurrent(index))
        val key = player.currentMediaItem?.localConfiguration?.customCacheKey
        qualityStore.resolved.value[key]?.let { queueStore.updateAudioQuality(uid, it) }
        queueStore.updatePosition(uid, player.currentPosition)
    }

    private fun mediaSourceFactory(): DefaultMediaSourceFactory = DefaultMediaSourceFactory(
        audioCache.factory { entryKey, audio ->
            // 命中磁盘也恢复实得音质和响度；后台预缓存不改变当前音频处理链。
            loudness.rememberTrack(entryKey, audio.gainDb, audio.peak)
            qualityStore.reportResolved(
                entryKey, audio.songId, audio.level, AudioQuality.fromWire(audio.requestedQuality),
            )
            serviceScope.launch {
                val uid = entryKey.substringAfter('@', "").toLongOrNull()
                queueStore.updateAudioQuality(uid, qualityStore.resolved.value[entryKey])
            }
        }
    ).setLoadErrorHandlingPolicy(SongLoadErrorPolicy())
}

/**
 * "这首歌确定放不了"是结论而不是故障，重试只会白白再打三次网易云，还让错误提示晚几秒才出来。
 * 其余错误照旧交给默认策略——网络抖动该重试还是要重试。
 */
@UnstableApi
private class SongLoadErrorPolicy : LoadErrorHandlingPolicy {

    private val delegate = DefaultLoadErrorHandlingPolicy()

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (loadErrorInfo.exception.songUnavailable() != null ||
            loadErrorInfo.exception is AudioResourceChangedException
        ) C.TIME_UNSET
        else delegate.getRetryDelayMsFor(loadErrorInfo)

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        delegate.getMinimumLoadableRetryCount(dataType)

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? =
        delegate.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
}
