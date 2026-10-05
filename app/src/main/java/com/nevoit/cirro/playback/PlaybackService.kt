package com.nevoit.cirro.playback

import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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

/**
 * 播放器宿主。
 *
 * 播放必须活在 Service 里：切后台、锁屏、被系统回收的时候音乐都不能停。用
 * MediaSessionService 还能顺带拿到通知栏、锁屏控制和耳机线控——这几样自己写一遍
 * 只会更长也更容易出错。
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
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

        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** 用户把任务从最近应用里划掉：正在播就继续播，已经停了才收掉服务。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
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
     */
    @OptIn(UnstableApi::class)
    private fun mediaSourceFactory(): DefaultMediaSourceFactory {
        val resolver = ResolvingDataSource.Resolver { spec ->
            val id = spec.uri.songIdOrNull()
                ?: throw SongUnavailableException("无法识别的歌曲地址")
            val audio = NcmBridge.songUrl(id).getOrThrow()
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
