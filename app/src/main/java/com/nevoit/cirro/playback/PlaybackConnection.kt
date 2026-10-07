package com.nevoit.cirro.playback

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.nevoit.cirro.data.NcmBridge
import com.nevoit.cirro.data.PlayQueueStore
import com.nevoit.cirro.data.model.PlayMode
import com.nevoit.cirro.data.model.PlaybackQueue
import com.nevoit.cirro.data.model.QueueEntry
import com.nevoit.cirro.data.model.QueueSource
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.appendInsertIndex
import com.nevoit.cirro.data.model.insertNextIndex
import com.nevoit.cirro.data.model.manualNextIndex
import com.nevoit.cirro.data.model.manualPreviousIndex
import com.nevoit.cirro.data.model.playQueueOf
import com.nevoit.cirro.data.model.restoredTail
import com.nevoit.cirro.data.model.shuffledTail
import com.nevoit.cirro.data.model.withCurrent
import com.nevoit.cirro.data.model.withInsert
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
    val audioSessionId: Int = C.AUDIO_SESSION_ID_UNSET,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
    /** [positionMs] 是**什么时候**采的（`SystemClock.uptimeMillis()`）。逐帧消费位置的界面靠它外推。 */
    val sampledAtMillis: Long = 0,
    /**
     * 位置**不连续**的累计次数，只增不减。
     *
     * 只有真 seek / 循环 / 换曲才 +1，**普通的位置采样绝不**。
     *
     * 逐帧消费位置的界面（歌词）用它来区分两件事：「播放器报的位置往回退了一点」
     * （缓冲、时序错位 —— 要丢掉）和「用户真的往回跳了」（拖进度条、单曲循环、切歌 —— 要跟上）。
     * 少了这个标记就只能二选一：要么画面跟着采样抖动往回跳，要么跳转之后歌词永远卡在原地。
     */
    val discontinuity: Long = 0,
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
 * 队列的模型也归这里改（见 [PlayQueueStore] 的注释）：模型和播放器列表必须一起动，
 * 而只有这里拿着 MediaController。**所有改动都走增量操作**（`addMediaItem` / `moveMediaItem`），
 * 不 `setMediaItems` 重建——重建会把正在播的那首重新缓冲一遍。
 *
 * 进程内只建一个：MediaController 的连接本身有成本，而多个
 * controller 会让"当前在放什么"这件事出现多个来源。
 */
class PlaybackConnection(
    context: Context,
    private val scope: CoroutineScope,
    private val queueStore: PlayQueueStore,
) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** 队列快照。给以后的队列界面，也给"当前这首从哪来"这类查询。 */
    val queue: StateFlow<PlaybackQueue> get() = queueStore.queue

    private var controller: MediaController? = null
    private var lastSessionLogMs = 0L

    /** 见 [PlaybackState.discontinuity]。只在这个 listener 里加。 */
    private var positionDiscontinuity = 0L

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            // 换曲也是一次位置不连续——整个时间轴换了一条，位置从新曲的 0 开始。
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) positionDiscontinuity++
            publish(player)
            syncCurrentIndex(player)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // INTERNAL 是播放器自己的微调（比如把目标夹到可 seek 区间），不是用户跳转，不该放行。
            if (reason != Player.DISCONTINUITY_REASON_INTERNAL) positionDiscontinuity++
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
                        // 进程里服务可能比这个连接活得久（Activity 重建过），先把模式对齐
                        controller.repeatMode = queueStore.queue.value.mode.toRepeatMode()
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

    /** 播一首歌，[id] 是网易云的歌曲 id。队列里就它一条。 */
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

            startQueue(controller, playQueueOf(listOf(song), 0, source = null, mode = currentMode()))
        }
    }

    /**
     * 把整批歌作为播放队列，从 [startIndex] 开始播。
     *
     * 列表里点某一首，听感上该接着往下听，所以不是"只播这一首"，而是"把这份列表变成队列"。
     * 队列里存的是元数据，直链仍由数据源层临播前才换（见 `songUri` 的说明）。
     *
     * [source] 是这批歌的出身，用来打卡时回传 `sourceId`——详情页知道自己的歌单/专辑 id，
     * 就把它传进来；上游没有集合 id 的（首页混排块、单曲）传 null。
     */
    fun playSongs(songs: List<Song>, startIndex: Int, source: QueueSource? = null) {
        if (songs.isEmpty()) return

        scope.launch(Dispatchers.Main) {
            _state.value = _state.value.copy(error = null)

            val controller = controller
            if (controller == null) {
                _state.value = _state.value.copy(error = "播放器还没准备好")
                return@launch
            }

            startQueue(controller, playQueueOf(songs, startIndex, source, mode = currentMode()))
        }
    }

    /**
     * 临时插播：**下一个播放**。紧接着当前这首。
     *
     * 只往播放器列表里插一条，不重建列表。
     */
    fun addNext(song: Song, source: QueueSource? = null) {
        insert(song, source) { it.insertNextIndex }
    }

    /**
     * 临时插播：**添加到插播队列**。手上已经有插播时排到它们最后，否则等同于「下一个播放」。
     */
    fun addToInsertQueue(song: Song, source: QueueSource? = null) {
        insert(song, source) { it.appendInsertIndex }
    }

    /**
     * 手动「下一首」。**不受单曲循环约束**——目标下标自己算，不走 `seekToNextMediaItem()`。
     */
    fun skipToNext() {
        val controller = controller ?: return
        val queue = liveQueue() ?: return
        val target = queue.manualNextIndex(controller.currentMediaItemIndex) ?: return
        controller.seekTo(target, 0L)
        if (!controller.playWhenReady) controller.play()
    }

    /** 手动「上一首」，同样不受单曲循环约束。 */
    fun skipToPrevious() {
        val controller = controller ?: return
        val queue = liveQueue() ?: return
        val target = queue.manualPreviousIndex(controller.currentMediaItemIndex) ?: return
        controller.seekTo(target, 0L)
        if (!controller.playWhenReady) controller.play()
    }

    /**
     * 切播放模式。
     *
     * 随机不是播放器那个 shuffle，而是把播放列表重排一遍（只动当前位置之后的基准条目，
     * 插播条目原地不动），所以要多一步 `moveMediaItem`。离开随机时按建队时的基准序还原。
     */
    fun setPlayMode(mode: PlayMode) {
        scope.launch(Dispatchers.Main) {
            val controller = controller
            val queue = liveQueue()
            if (controller == null || queue == null) {
                queueStore.set(queueStore.queue.value.copy(mode = mode))
                return@launch
            }

            val reordered = when (mode) {
                PlayMode.SHUFFLE -> queue.shuffledTail()
                else -> queue.restoredTail()
            }.copy(mode = mode)

            reorderPlayer(queue.entries, reordered.entries)
            controller.repeatMode = mode.toRepeatMode()
            queueStore.set(reordered)
        }
    }

    fun togglePlayPause() {
        val controller = controller ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    /**
     * 跳到指定位置。播放页拖完进度条松手时调。
     *
     * 越界交给播放器自己夹到时长内——这里拿到的时长可能还是元数据里那份（READY 之前），
     * 自己夹反而会夹错。
     */
    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    /** 拿队列，并把当前位置对齐到播放器报的下标（模型不一定比播放器事件更早）。 */
    private fun liveQueue(): PlaybackQueue? {
        val controller = controller ?: return null
        val queue = queueStore.queue.value
        if (queue.entries.isEmpty()) return null
        return queue.withCurrent(controller.currentMediaItemIndex)
    }

    private fun currentMode(): PlayMode = queueStore.queue.value.mode

    /** 整队换掉：先把顺序算好（随机模式下建队即打乱），再一次性交给播放器。 */
    private fun startQueue(controller: MediaController, queue: PlaybackQueue) {
        controller.setMediaItems(
            queue.entries.map { it.song.toMediaItem() },
            queue.currentIndex,
            0L,
        )
        controller.repeatMode = queue.mode.toRepeatMode()
        controller.prepare()
        controller.play()
        queueStore.set(queue)
    }

    private fun insert(
        song: Song,
        source: QueueSource?,
        position: (PlaybackQueue) -> Int,
    ) {
        scope.launch(Dispatchers.Main) {
            val controller = controller ?: return@launch
            val queue = liveQueue() ?: return@launch
            if (controller.mediaItemCount != queue.entries.size) return@launch

            val at = position(queue).coerceIn(0, queue.entries.size)
            controller.addMediaItem(at, song.toMediaItem())
            queueStore.set(queue.withInsert(song, source, at))
        }
    }

    /**
     * 把播放器列表重排成 [to] 的顺序。
     *
     * 逐个"就位"：谁不在目标位置上就挪过去。因为要动的槽位都在当前位置**之后**，
     * 正在播的那条的下标不会变，播放不中断。
     */
    private fun reorderPlayer(from: List<QueueEntry>, to: List<QueueEntry>) {
        val controller = controller ?: return
        val working = from.toMutableList()
        to.forEachIndexed { target, want ->
            val at = working.indexOfFirst { it.uid == want.uid }
            if (at >= 0 && at != target) {
                controller.moveMediaItem(at, target)
                working.add(target, working.removeAt(at))
            }
        }
    }

    /**
     * 播放器的当前位置是唯一权威，把它同步回模型。
     *
     * 只在真的变了才写，否则位置轮询那 500ms 一次的 `onEvents` 会白白推流给订阅方。
     */
    private fun syncCurrentIndex(player: Player) {
        val queue = queueStore.queue.value
        val index = player.currentMediaItemIndex
        if (index in queue.entries.indices && queue.currentIndex != index) {
            queueStore.set(queue.copy(currentIndex = index))
        }
    }

    private fun publish(player: Player) {
        // 播放器自己报的时长 READY 前是 TIME_UNSET，兜底读元数据里那份（来自 song_detail）
        val duration = player.duration.takeIf { it != C.TIME_UNSET }
            ?: player.currentMediaItem?.mediaMetadata?.durationMs?.takeIf { it != C.TIME_UNSET }
        val now = SystemClock.uptimeMillis()
        if (now - lastSessionLogMs >= 1000L) {
            lastSessionLogMs = now
            Log.d("CirroPlayback", "publish playing=${player.isPlaying} playbackState=${player.playbackState} audioSessionId=${player.audioSessionId} mediaId=${player.currentMediaItem?.mediaId}")
        }
        _state.value = PlaybackState(
            song = player.currentMediaItem?.toNowPlaying(),
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            audioSessionId = player.audioSessionId,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = (duration ?: 0L).coerceAtLeast(0),
            // 播起来了就把上一次的错误清掉，否则它会一直挂在界面上
            error = if (player.playbackState == Player.STATE_READY) null else _state.value.error,
            sampledAtMillis = now,
            discontinuity = positionDiscontinuity,
        )
    }
}

/**
 * 播放模式 → 播放器的 `repeatMode`。
 *
 * 只有单曲循环用得到 `REPEAT_MODE_ONE`；随机用 `REPEAT_MODE_ALL` 让整份重排过的列表循环
 * （播放器的 shuffle **始终不开**，顺序是我们自己排的）。
 */
private fun PlayMode.toRepeatMode(): Int = when (this) {
    PlayMode.SEQUENTIAL -> Player.REPEAT_MODE_OFF
    PlayMode.REPEAT_ALL, PlayMode.SHUFFLE -> Player.REPEAT_MODE_ALL
    PlayMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
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
