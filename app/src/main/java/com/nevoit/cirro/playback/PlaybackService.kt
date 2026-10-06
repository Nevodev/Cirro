package com.nevoit.cirro.playback

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
import com.nevoit.cirro.data.NcmBridge
import com.nevoit.cirro.data.SongUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 播放器宿主。
 *
 * 播放必须活在 Service 里：切后台、锁屏、被系统回收的时候音乐都不能停。用
 * MediaSessionService 还能顺带拿到通知栏、锁屏控制和耳机线控——这几样自己写一遍
 * 只会更长也更容易出错。
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    /** 听歌打卡是尽力而为的后台请求，走 IO 线程，服务销毁时随之取消。 */
    private val scrobbleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前（或刚切走的）那首歌的 id，配合 [playingDurationMs] 做打卡。 */
    private var playingSongId: Long? = null

    /** 当前这首的完整时长，在第一次 READY 后取一次。 */
    private var playingDurationMs: Long = C.TIME_UNSET

    /** 响度归一挂在音频输出链上，换歌时由数据源那层喂 gain（见 [mediaSourceFactory]）。 */
    private val loudness = LoudnessAudioProcessor()

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this, AudioRenderersFactory(this, loudness))
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

        // 响度数据是跟着 resolve 来的，但"该用哪一首的"只有这里知道：ExoPlayer 会预读下一首，
        // 下一首的 resolve 可能在当前这首还没放完时就跑完了。所以切换时按 id 选一次。
        // 顺带做听歌打卡：开始播报 startplay，完整听完才报 play——跟官方客户端一样是两条。
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                loudness.selectTrack(mediaItem?.mediaId?.toLongOrNull())

                // 自动切下一首 = 上一首完整播完了，涨听歌排行
                val finishedId = playingSongId
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && finishedId != null) {
                    val time = (playingDurationMs / 1000).coerceAtLeast(1)
                    scrobbleScope.launch { NcmBridge.scrobble(finishedId, "", time).getOrNull() }
                }

                playingSongId = mediaItem?.mediaId?.toLongOrNull()
                playingDurationMs = C.TIME_UNSET

                // 开始播就报 startplay（进最近播放），不等结束
                val startedId = playingSongId
                if (startedId != null) {
                    scrobbleScope.launch { NcmBridge.scrobbleStart(startedId, "").getOrNull() }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && playingDurationMs == C.TIME_UNSET) {
                    playingDurationMs = session?.player?.duration ?: C.TIME_UNSET
                }
                // 队列的最后一首播完会停在 ENDED（不再触发 AUTO 切换），单独补上报
                if (playbackState == Player.STATE_ENDED) {
                    val id = playingSongId
                    if (id != null) {
                        val time =
                            ((session?.player?.currentPosition ?: 0) / 1000).coerceAtLeast(1)
                        scrobbleScope.launch { NcmBridge.scrobble(id, "", time).getOrNull() }
                    }
                }
            }
        })

        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** 用户把任务从最近应用里划掉：正在播就继续播，已经停了才收掉服务。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scrobbleScope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /**
     * 在数据源那一层把 `cirro://song/<id>` 换成真实直链。
     *
     * 换链接发生在 ExoPlayer 自己的加载线程上，不在主线程——Rust 侧是阻塞调用，
     * 放主线程会 ANR。
     *
     * 顺带把这首歌的响度记下来：直链和 `gain` / `peak` 本来就在同一个响应里，一起拿最省事。
     * 只记不设——这里拿到的可能是预读的下一首，见 [LoudnessAudioProcessor] 的类注释。
     */
    private fun mediaSourceFactory(): DefaultMediaSourceFactory {
        val resolver = ResolvingDataSource.Resolver { spec ->
            val id = spec.uri.songIdOrNull()
                ?: throw SongUnavailableException("无法识别的歌曲地址")
            val audio = NcmBridge.songUrl(id).getOrThrow()
            loudness.rememberTrack(id, audio.gainDb, audio.peak)
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
