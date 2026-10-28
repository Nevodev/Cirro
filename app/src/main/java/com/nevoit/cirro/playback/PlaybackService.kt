package com.nevoit.cirro.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.nevoit.cirro.MainActivity
import com.nevoit.cirro.data.AudioQualityStore
import com.nevoit.cirro.data.NcmBridge
import com.nevoit.cirro.data.PlayQueueStore
import com.nevoit.cirro.data.SongUnavailableException
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.sourceIdAt
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

    /** 听歌打卡是尽力而为的后台请求，走 IO 线程，服务销毁时随之取消。 */
    private val scrobbleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前（或刚切走的）那首歌的 id，配合 [playingDurationMs] 做打卡。 */
    private var playingSongId: Long? = null

    /** 当前这首的出身（歌单/专辑 id），打卡时一起报。没有上下文就是空串。 */
    private var playingSourceId: String = ""

    /** 当前这首的完整时长，在第一次 READY 后取一次。 */
    private var playingDurationMs: Long = C.TIME_UNSET
    private var playingStarted = false

    /** 响度归一挂在音频输出链上，换歌时由数据源那层喂 gain（见 [mediaSourceFactory]）。 */
    private val loudness = LoudnessAudioProcessor()
    private val audioAnalysis: AudioAnalysisStore by inject()
    private val beatAnalyzer by lazy { PcmBeatAudioProcessor(audioAnalysis) }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this, AudioRenderersFactory(this, loudness, beatAnalyzer))
            .setMediaSourceFactory(mediaSourceFactory())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        // 听歌打卡：开始播报 startplay，完整听完才报 play——跟官方客户端一样是两条，
        // 且都带上这首歌的来源（歌单/专辑 id）。
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                queueStore.finishPlaybackHistory(
                    nextUid = mediaItem?.queueEntryUid,
                    repeated = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
                )
                // 上一首完整播完了，涨听歌排行。两种都算：
                // AUTO = 自动切下一首；REPEAT = 单曲循环自然放完又从头来。
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                ) {
                    reportPlayed()
                }

                playingSongId = mediaItem?.mediaId?.toLongOrNull()
                playingStarted = false
                playingDurationMs = C.TIME_UNSET
                // 出身现在就记下来：打卡要等播完才发，那会儿播放器早指到别处了。
                playingSourceId = queueStore.queue.value.sourceIdAt(player.currentMediaItemIndex)

                // 歌词预取：切歌就后台取一次，不阻塞播放。
                // 队列里能拿到完整歌曲信息就用它（lrclib 那级要靠曲名/歌手模糊查）；
                // 拿不到（如 Activity 重建后模型为空）就只给 id —— 前三级只用 id，照样能取。
                playingSongId?.let { songId ->
                    val entry = queueStore.queue.value.entries.getOrNull(
                        player.currentMediaItemIndex
                    )
                    lyrics.prefetch(entry?.song ?: Song(id = songId))
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && playingDurationMs == C.TIME_UNSET) {
                    playingDurationMs = session?.player?.duration ?: C.TIME_UNSET
                }
                // 队列的最后一首播完会停在 ENDED（不再触发 AUTO 切换），单独补上报
                if (playbackState == Player.STATE_ENDED) {
                    queueStore.finishPlaybackHistory()
                    reportPlayed()
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                savePlayback(player)
                if (!player.isPlaying) return
                val uid = player.currentMediaItem?.queueEntryUid ?: return
                // 同一首歌可重复入队，索引也会因拖拽改变，必须按条目身份匹配实际出声的歌曲。
                val entry = queueStore.queue.value.entries.firstOrNull { it.uid == uid } ?: return
                queueStore.markPlaying(entry)
                // 恢复队列只展示暂停态，真正出声后才记为一次播放。
                if (!playingStarted) {
                    playingStarted = true
                    val startedId = entry.song.id
                    val sourceId = playingSourceId
                    scrobbleScope.launch { NcmBridge.scrobbleStart(startedId, sourceId).getOrNull() }
                }
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
        scrobbleScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(5_000L)
                if (player.isPlaying) savePlayback(player)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * 上报"这一首听完了"，涨听歌排行。
     *
     * 时长优先用整首长度（READY 后取到的那份）；拿不到才退回落当前进度。
     * `sourceId` 用这首歌**开始播时**记下的出身——切歌之后播放器已经指到别处，现问是问不到的。
     */
    private fun reportPlayed() {
        if (!playingStarted) return
        val id = playingSongId ?: return
        val played = playingDurationMs.takeIf { it != C.TIME_UNSET }
            ?: (session?.player?.currentPosition ?: 0L)
        val time = (played / 1000).coerceAtLeast(1)
        val sourceId = playingSourceId
        scrobbleScope.launch { NcmBridge.scrobble(id, sourceId, time).getOrNull() }
    }

    /** 用户把任务从最近应用里划掉：正在播就继续播，已经停了才收掉服务。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player ?: return
        savePlayback(player)
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.player?.let(::savePlayback)
        queueStore.finishPlaybackHistory()
        scrobbleScope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun savePlayback(player: Player) {
        val uid = player.currentMediaItem?.queueEntryUid ?: return
        val queue = queueStore.queue.value
        val index = if (queue.entries.getOrNull(queue.currentIndex)?.uid == uid) queue.currentIndex
            else queue.entries.indexOfFirst { it.uid == uid }
        if (index < 0) return
        if (index != queue.currentIndex) queueStore.set(queue.withCurrent(index))
        queueStore.updatePosition(uid, player.currentPosition)
    }

    /**
     * 在数据源那一层把 `cirro://song/<id>` 换成真实直链。
     *
     * 换链接发生在 ExoPlayer 自己的加载线程上，不在主线程——Rust 侧是阻塞调用，
     * 放主线程会 ANR。
     *
     * 档位在这里**现取**（[qualityStore]）：改档后下一次换直链就用新的，已在缓冲的不受影响。
     * 拿不到目标档时由服务端自己降级（实测请求 `hires` 回 `lossless`），客户端不做降级链，
     * 实得档位看响应回显。
     *
     * 顺带把这首歌的响度记下来：直链和 `gain` / `peak` 本来就在同一个响应里，一起拿最省事。
     * 只记不设——这里拿到的可能是预读的下一首，见 [LoudnessAudioProcessor] 的类注释。
     */
    private fun mediaSourceFactory(): DefaultMediaSourceFactory {
        val resolver = ResolvingDataSource.Resolver { spec ->
            val id = spec.uri.songIdOrNull()
                ?: throw SongUnavailableException("无法识别的歌曲地址")
            val quality = qualityStore.current
            val audio = NcmBridge.songUrl(id, quality.wire).getOrThrow()
            loudness.rememberTrack(spec.key ?: id.toString(), audio.gainDb, audio.peak)
            // 服务端可能降级，如实记下实得档位
            qualityStore.reportResolved(id, audio.level)
            spec.withUri(audio.url.toUri())
        }
        return DefaultMediaSourceFactory(
            ResolvingDataSource.Factory(DefaultDataSource.Factory(this), resolver)
        ).setLoadErrorHandlingPolicy(SongLoadErrorPolicy())
    }
}

/**
 * "这首歌确定放不了"是结论而不是故障，重试只会白白再打三次网易云，还让错误提示晚几秒才出来。
 * 其余错误照旧交给默认策略——网络抖动该重试还是要重试。
 */
@UnstableApi
private class SongLoadErrorPolicy : LoadErrorHandlingPolicy {

    private val delegate = DefaultLoadErrorHandlingPolicy()

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (loadErrorInfo.exception.songUnavailable() != null) C.TIME_UNSET
        else delegate.getRetryDelayMsFor(loadErrorInfo)

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        delegate.getMinimumLoadableRetryCount(dataType)

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? =
        delegate.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
}
