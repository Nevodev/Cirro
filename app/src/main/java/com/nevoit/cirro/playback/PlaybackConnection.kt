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
import com.nevoit.cirro.data.model.PlaybackHistoryEntry
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
import com.nevoit.cirro.data.model.withMovedEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

private const val PositionTickMs = 500L

/**
 * 起播宽限期（毫秒）。
 *
 * 点了播放 / 切了歌之后，`playWhenReady` 立刻就是 true，但播放器要缓冲完才出声——
 * 这段窗口里界面照样显示"正在播放"（见 [PlaybackState.isPlayingOptimistic]），
 * 超过这个时长还没出声才落回暂停。
 */
private const val PlayIntentGraceMs = 15_000L

/** 播放失败后的自动重试次数上限（不含第一次尝试）。 */
private const val MaxPlaybackRetries = 3

/** 自动重试的退避基数：第 n 次重试等 `n × 这个值`。 */
private const val RetryBaseDelayMs = 1_000L

/** 正在播的那首歌，字段全部来自 MediaItem 的元数据，所以重连之后也拿得回来。 */
data class NowPlaying(
    val id: Long,
    val title: String,
    val artist: String,
    val cover: String?,
)

data class PlaybackState(
    val song: NowPlaying? = null,
    /**
     * 播放器**真的**在出声。
     *
     * 必须跟真实音频对齐的地方用它（歌词外推、进度波纹的滚动）——
     * 界面上的播放键请用 [isPlayingOptimistic]。
     */
    val isPlaying: Boolean = false,
    /** 播放意图已亮、但还没出声（切歌缓冲 / 刚点播放），且还在 [PlayIntentGraceMs] 窗口内。 */
    val pendingPlay: Boolean = false,
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
    /**
     * 界面意义上的"正在播放"：真出声了，或者还在起播宽限期内（缓冲 / 重试中）。
     *
     * 播放键、迷你条、网格背景这些**用户感知**的地方看它。直接拿 [isPlaying] 驱动按钮的话，
     * 每次切歌都会在缓冲期闪一下暂停——`ExoPlayer` 在 `STATE_BUFFERING` 时报的就是
     * `isPlaying = false`，哪怕 `playWhenReady` 还亮着。
     */
    val isPlayingOptimistic: Boolean get() = isPlaying || pendingPlay

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
    val history: StateFlow<List<PlaybackHistoryEntry>> get() = queueStore.history

    private var controller: MediaController? = null
    private var lastSessionLogMs = 0L

    /** 见 [PlaybackState.discontinuity]。只在这个 listener 里加。 */
    private var positionDiscontinuity = 0L

    /** 见 [PlaybackState.pendingPlay]。 */
    private var playIntentPending = false
    private var playIntentJob: Job? = null

    /** 自动重试计数，起播成功 / 换曲 / 重新点播时清零。 */
    private var retryCount = 0
    private var retryJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            // 换曲也是一次位置不连续——整个时间轴换了一条，位置从新曲的 0 开始。
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                positionDiscontinuity++
                // 换了首歌，重试次数从头算：上一首的失败不该限制下一首。
                retryCount = 0
                retryJob?.cancel()
                // 上一首的错误提示也不该跟着飘过来（新歌成不成，等它自己的结果）。
                _state.value = _state.value.copy(error = null)
            }
            // 真的播起来了，之前攒的重试额度作废。
            if (player.playbackState == Player.STATE_READY) {
                retryCount = 0
                retryJob?.cancel()
            }
            // 必须在 publish 之前算：宽限窗口是 publish 要读的状态。
            updatePlayIntent(player)
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
            // "这首歌确定放不了"（无版权 / 下架 / 拿不到直链）是结论而不是抖动，重试只是白等。
            if (error.songUnavailable() != null || retryCount >= MaxPlaybackRetries) {
                giveUp(error)
                return
            }
            retryCount++
            val attempt = retryCount
            retryJob?.cancel()
            _state.value = _state.value.copy(error = "播放出错，正在重试（$attempt/$MaxPlaybackRetries）")
            retryJob = scope.launch(Dispatchers.Main) {
                delay(RetryBaseDelayMs * attempt)
                val controller = controller ?: return@launch
                // prepare() 从当前进度重新加载——中途断网接回来是接着放，不是从头开始。
                controller.prepare()
                controller.play()
            }
        }
    }

    /**
     * 起播宽限窗口（见 [PlaybackState.pendingPlay]）。
     *
     * `playWhenReady` 亮着但还没出声 = 正在起播：开一个最多 [PlayIntentGraceMs] 的窗口，
     * 窗口内界面照样显示"正在播放"。到点还没出声就把播放器真的按停——不按停的话
     * `playWhenReady` 永远亮着，乐观状态会把按钮一直画成"正在播放"，点了也没反应。
     */
    private fun updatePlayIntent(player: Player) {
        if (player.playWhenReady && !player.isPlaying) {
            if (playIntentPending) return
            playIntentPending = true
            playIntentJob = scope.launch(Dispatchers.Main) {
                delay(PlayIntentGraceMs)
                playIntentPending = false
                val controller = controller
                if (controller != null && controller.playWhenReady && !controller.isPlaying) {
                    controller.pause()
                }
                controller?.let(::publish)
            }
        } else if (playIntentPending) {
            playIntentPending = false
            playIntentJob?.cancel()
            playIntentJob = null
        }
    }

    /**
     * 放弃这一首：报错，并把播放器真的按停。
     *
     * 按停是有意的——否则 `playWhenReady` 还亮着，界面会一直显示成"正在播放"，
     * 但底下早就没声了，两套真相。
     */
    private fun giveUp(error: PlaybackException) {
        retryJob?.cancel()
        retryJob = null
        retryCount = 0
        controller?.pause()
        _state.value = _state.value.copy(error = error.readableMessage())
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
                        controller.repeatMode = queueStore.queue.value.repeatMode()
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
     * [source] 是这批歌的出身，名称随队列保留，集合 id 用于打卡时回传 `sourceId`。
     * 详情页传歌单/专辑的名称和 id，首页混排块只传名称，没有来源信息时传 null。
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

    suspend fun moveQueueEntry(fromUid: Long, toUid: Long): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val controller = controller ?: return@withContext false
            val queue = liveQueue() ?: return@withContext false
            if (controller.mediaItemCount != queue.entries.size) return@withContext false

            // 切歌可能发生在拖拽回调之前，按最新快照重新校验当前歌曲是否仍固定。
            val moved = queue.withMovedEntry(fromUid, toUid)
            if (moved === queue) return@withContext false
            val from = queue.entries.indexOfFirst { it.uid == fromUid }
            val to = moved.entries.indexOfFirst { it.uid == fromUid }
            controller.moveMediaItem(from, to)
            queueStore.set(moved)
            true
        }

    fun playQueueEntry(uid: Long) {
        scope.launch(Dispatchers.Main.immediate) {
            val controller = controller ?: return@launch
            val queue = liveQueue() ?: return@launch
            if (controller.mediaItemCount != queue.entries.size) return@launch
            val target = queue.entries.indexOfFirst { it.uid == uid }
            if (target < 0 || controller.getMediaItemAt(target).queueEntryUid != uid) return@launch

            // 只跳游标，跳过的条目仍留在完整队列里；历史由实际出声的播放事件记录。
            playAt(controller, target, restartCurrent = false)
        }
    }

    fun playHistoryEntry(entry: QueueEntry) {
        scope.launch(Dispatchers.Main.immediate) {
            val controller = controller ?: return@launch
            val queue = liveQueue()
            if (queue == null) {
                startQueue(
                    controller,
                    playQueueOf(
                        listOf(entry.song), 0, entry.source,
                        mode = currentMode(), shuffle = currentShuffle(),
                    ),
                )
                return@launch
            }
            if (controller.mediaItemCount != queue.entries.size) return@launch
            val existing = queue.entries.indexOfFirst { it.uid == entry.uid }
            val target = if (existing >= 0) {
                if (controller.getMediaItemAt(existing).queueEntryUid != entry.uid) return@launch
                existing
            } else {
                // 换过队列的历史歌曲已没有原条目，插入新条目才能保留当前队列及其来源。
                val at = queue.insertNextIndex
                val inserted = queue.withInsert(entry.song, entry.source, at)
                controller.addMediaItem(at, entry.song.toMediaItem(inserted.entries[at].uid))
                queueStore.set(inserted)
                at
            }
            playAt(controller, target, restartCurrent = true)
        }
    }

    private fun playAt(controller: MediaController, target: Int, restartCurrent: Boolean) {
        retryJob?.cancel()
        retryJob = null
        retryCount = 0
        _state.value = _state.value.copy(error = null)
        if (restartCurrent || target != controller.currentMediaItemIndex ||
            controller.playbackState == Player.STATE_ENDED
        ) {
            controller.seekTo(target, 0L)
        }
        if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
        controller.play()
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
     * 切**循环**那一轴（顺序 / 列表循环 / 单曲循环）。随机那一轴原样保留 —— 两根轴互不干扰。
     */
    fun setLoopMode(mode: PlayMode) {
        applyMode(loop = mode, shuffle = currentShuffle())
    }

    /**
     * 切**随机**那一轴。
     *
     * 随机不是播放器那个 shuffle，而是把播放列表重排一遍（只动当前位置之后的基准条目，
     * 插播条目原地不动），所以要多一步 `moveMediaItem`。关掉时按建队时的基准序还原。
     */
    fun setShuffle(enabled: Boolean) {
        applyMode(loop = currentMode(), shuffle = enabled)
    }

    /**
     * 两根轴一起落到模型和播放器上。
     *
     * 🔴 只在随机那一轴真的翻转时才重排列表：重复随机一次等于换一批顺序（用户只是点了一下
     * 循环，不该看到列表被打乱），照原样调 `shuffledTail()` 会白白重新洗一遍。
     */
    private fun applyMode(loop: PlayMode, shuffle: Boolean) {
        scope.launch(Dispatchers.Main) {
            val controller = controller
            val queue = liveQueue()
            if (controller == null || queue == null) {
                queueStore.set(queueStore.queue.value.copy(mode = loop, shuffle = shuffle))
                return@launch
            }

            val reordered = when {
                shuffle == queue.shuffle -> queue
                shuffle -> queue.shuffledTail()
                else -> queue.restoredTail()
            }.copy(mode = loop, shuffle = shuffle)

            reorderPlayer(queue.entries, reordered.entries)
            controller.repeatMode = reordered.repeatMode()
            queueStore.set(reordered)
        }
    }

    /**
     * 🔴 用 `playWhenReady`（播放**意图**）而不是 `isPlaying` 判方向。
     *
     * 缓冲期 `isPlaying` 是 false，拿它判断的话，"显示暂停图标时点一下"会变成 `play()`
     * ——正好点反。意图是唯一不会在缓冲期抖动的信号。
     */
    fun togglePlayPause() {
        val controller = controller ?: return
        if (controller.playWhenReady) {
            // 手动暂停要连重试一起取消，否则退避到点它又自己放起来。
            retryJob?.cancel()
            retryJob = null
            controller.pause()
        } else {
            retryCount = 0
            controller.play()
        }
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

    private fun currentShuffle(): Boolean = queueStore.queue.value.shuffle

    /** 整队换掉：先把顺序算好（随机模式下建队即打乱），再一次性交给播放器。 */
    private fun startQueue(controller: MediaController, queue: PlaybackQueue) {
        // 新点播一次：之前那首攒下的重试额度和退避任务都不该跟过来。
        retryJob?.cancel()
        retryJob = null
        retryCount = 0
        controller.setMediaItems(
            queue.entries.map { it.song.toMediaItem(it.uid) },
            queue.currentIndex,
            0L,
        )
        controller.repeatMode = queue.repeatMode()
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
            val inserted = queue.withInsert(song, source, at)
            controller.addMediaItem(at, song.toMediaItem(inserted.entries[at].uid))
            queueStore.set(inserted)
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
            Log.d("CirroPlayback", "publish playing=${player.isPlaying} pending=$playIntentPending playWhenReady=${player.playWhenReady} retry=$retryCount playbackState=${player.playbackState} audioSessionId=${player.audioSessionId} mediaId=${player.currentMediaItem?.mediaId}")
        }
        _state.value = PlaybackState(
            song = player.currentMediaItem?.toNowPlaying(),
            isPlaying = player.isPlaying,
            pendingPlay = playIntentPending,
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
 * 队列的两根轴 → 播放器的 `repeatMode`。
 *
 * 只有单曲循环用得到 `REPEAT_MODE_ONE`，列表循环用 `REPEAT_MODE_ALL`。
 *
 * 🔴 随机**不进这里**：播放器的 shuffle 始终不开（顺序是我们自己排的），随机也不改循环 ——
 * 「随机 + 顺序」就是打乱之后放完就停，「随机 + 列表循环」才是整份重排过的列表循环。
 */
private fun PlaybackQueue.repeatMode(): Int = when (mode) {
    PlayMode.SEQUENTIAL -> Player.REPEAT_MODE_OFF
    PlayMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
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
