package com.nevoit.cirro.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.nevoit.cirro.data.NcmBridge
import com.nevoit.cirro.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

private const val PositionTickMs = 500L

/** 正在播的那首歌，字段全部来自 MediaItem 的元数据，所以重连之后也拿得回来。 */
data class NowPlaying(
    val id: Long,
    val title: String,
    val artist: String,
    val cover: String?,
)

data class PlaybackState(
    val song: NowPlaying? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * 界面与播放器之间的连接。
 *
 * 界面不直接持有 ExoPlayer——播放器活在 [PlaybackService] 里，这里通过 MediaController
 * 跟它说话，再把播放器的回调收敛成一个 [PlaybackState] 给 Compose 订阅。
 *
 * 进程内只建一个：MediaController 的连接本身有成本，而多个
 * controller 会让"当前在放什么"这件事出现多个来源。
 */
class PlaybackConnection(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var controller: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(error = error.readableMessage())
        }
    }

    init {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        // 只在主线程回调：MediaController 必须用在它被创建的那个 looper 上
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { controller ->
                        this.controller = controller
                        controller.addListener(listener)
                    }
                    .onFailure {
                        _state.value = _state.value.copy(error = "播放器没连上")
                    }
            },
            ContextCompat.getMainExecutor(appContext),
        )

        // 播放位置不会自己回调，只能定时问
        scope.launch(Dispatchers.Main) {
            while (isActive) {
                controller?.takeIf { it.isPlaying }?.let { publish(it) }
                delay(PositionTickMs.milliseconds)
            }
        }
    }

    /** 播一首歌，[id] 是网易云的歌曲 id。 */
    fun playSong(id: Long) {
        scope.launch(Dispatchers.Main) {
            _state.value = _state.value.copy(error = null)

            val song = withContext(Dispatchers.IO) {
                NcmBridge.songDetail(listOf(id)).getOrNull()?.firstOrNull()
            }
            if (song == null) {
                _state.value = _state.value.copy(error = "拿不到这首歌的信息")
                return@launch
            }

            val controller = controller
            if (controller == null) {
                _state.value = _state.value.copy(error = "播放器还没准备好")
                return@launch
            }

            controller.setMediaItem(song.toMediaItem())
            controller.prepare()
            controller.play()
        }
    }

    /**
     * 把整批歌作为播放队列，从 [startIndex] 开始播。
     *
     * 列表里点某一首，听感上该接着往下听，所以不是"只播这一首"，而是"把这份列表变成队列"。
     * 队列里存的是元数据，直链仍由数据源层临播前才换（见 `songUri` 的说明）。
     */
    fun playSongs(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty()) return
        val index = startIndex.coerceIn(0, songs.lastIndex)

        scope.launch(Dispatchers.Main) {
            _state.value = _state.value.copy(error = null)

            val controller = controller
            if (controller == null) {
                _state.value = _state.value.copy(error = "播放器还没准备好")
                return@launch
            }

            controller.setMediaItems(songs.map { it.toMediaItem() }, index, 0L)
            controller.prepare()
            controller.play()
        }
    }

    fun togglePlayPause() {
        val controller = controller ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    private fun publish(player: Player) {
        // 播放器自己报的时长 READY 前是 TIME_UNSET，兜底读元数据里那份（来自 song_detail）
        val duration = player.duration.takeIf { it != C.TIME_UNSET }
            ?: player.currentMediaItem?.mediaMetadata?.durationMs?.takeIf { it != C.TIME_UNSET }
        _state.value = PlaybackState(
            song = player.currentMediaItem?.toNowPlaying(),
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = (duration ?: 0L).coerceAtLeast(0),
            // 播起来了就把上一次的错误清掉，否则它会一直挂在界面上
            error = if (player.playbackState == Player.STATE_READY) null else _state.value.error,
        )
    }
}

private fun MediaItem.toNowPlaying(): NowPlaying? {
    val id = mediaId.toLongOrNull() ?: return null
    return NowPlaying(
        id = id,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
        cover = mediaMetadata.artworkUri?.toString(),
    )
}

/** 把异常链里的"放不了"翻成人话，其余给一个笼统的说法。 */
private fun Throwable.readableMessage(): String =
    songUnavailable()?.message?.takeIf { it.isNotEmpty() } ?: message ?: "播放出错了"
