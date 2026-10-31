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
import com.nevoit.cirro.data.AccountRepository
import com.nevoit.cirro.data.AudioQualityStore
import com.nevoit.cirro.data.IntelligenceRepository
import com.nevoit.cirro.data.IntelligenceSeed
import com.nevoit.cirro.data.NcmBridge
import com.nevoit.cirro.data.PlayQueueStore
import com.nevoit.cirro.data.PlaybackStore
import com.nevoit.cirro.data.RadioOpening
import com.nevoit.cirro.data.RadioRepository
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.PlayMode
import com.nevoit.cirro.data.model.PlaybackQueue
import com.nevoit.cirro.data.model.PlaybackHistoryEntry
import com.nevoit.cirro.data.model.QueueEntry
import com.nevoit.cirro.data.model.QueueSource
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.appendInsertIndex
import com.nevoit.cirro.data.model.insertNextIndex
import com.nevoit.cirro.data.model.manualNextIndex
import com.nevoit.cirro.data.model.manualPreviousIndex
import com.nevoit.cirro.data.model.pendingRecommendationRemovalRanges
import com.nevoit.cirro.data.model.playQueueOf
import com.nevoit.cirro.data.model.restoredTail
import com.nevoit.cirro.data.model.shuffledTail
import com.nevoit.cirro.data.model.withCurrent
import com.nevoit.cirro.data.model.withInfinite
import com.nevoit.cirro.data.model.withInsert
import com.nevoit.cirro.data.model.withMovedEntry
import com.nevoit.cirro.data.model.withRecommendations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

/**
 * 连续跳过多少首"放不了"的歌就认输。
 *
 * 整份队列都是下架歌时不能一路跳到底 —— 那样比直接报错更难懂。
 */
private const val MaxSkipUnavailable = 5

/** 正在播的那首歌，字段全部来自 MediaItem 的元数据，所以重连之后也拿得回来。 */
data class NowPlaying(
    val id: Long,
    val title: String,
    val artist: String,
    val cover: String?,
    val queueEntryUid: Long? = null,
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
    private val account: AccountRepository,
    private val intelligence: IntelligenceRepository,
    private val radio: RadioRepository,
    private val playbackStore: PlaybackStore,
    private val qualityStore: AudioQualityStore,
) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** 队列快照。给以后的队列界面，也给"当前这首从哪来"这类查询。 */
    val queue: StateFlow<PlaybackQueue> get() = queueStore.queue
    val history: StateFlow<List<PlaybackHistoryEntry>> get() = queueStore.history

    private val _infiniteState = MutableStateFlow(InfinitePlaybackState())
    val infiniteState = _infiniteState.asStateFlow()
    private val _infiniteMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val infiniteMessages = _infiniteMessages.asSharedFlow()
    private var infiniteJob: Job? = null
    private var infiniteGeneration = 0
    private var infiniteSeed: IntelligenceSeed? = null
    private var infiniteCursor: Long? = null
    private var advanceAfterRecommendations = false

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

    /** 连着跳过了几首"放不了"的歌；真的播起来了就清零（见 [listener] 的 `onEvents`）。 */
    private var skippedUnavailable = 0

    private val listener = object : Player.Listener {
        /**
         * ⚠️ 归档老歌这件事**必须**在这里也做一次，不能只靠 service 端。
         *
         * controller 端对 `seekTo` 是本地乐观更新：它先把游标推过去、**同步**派发 `onEvents`
         * （→ [syncCurrentIndex]），命令才被 post 到 session 的 looper。而 service 端那条
         * `finishPlaybackHistory` 要等命令真跑完才回调 —— 同一根主线程，中间夹着下一个消息，
         * 也就是夹着一个帧回调：那一帧里待播区已经少了老歌、历史区还没有它，看着就是"闪一空"。
         *
         * 放在这里跟 `syncCurrentIndex` 同一条消息、同一帧落地，空档就没了。
         * [PlayQueueStore] 的归档是幂等的（`playing` 对不上才归档、归档后置空），所以 service 端
         * 稍后照跑不误、不会记两条；"真的出声了没有"仍然由 service 端说了算。
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            queueStore.finishPlaybackHistory(
                nextUid = mediaItem?.queueEntryUid,
                repeated = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
            )
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && !player.playWhenReady) {
                advanceAfterRecommendations = false
            }
            // 换曲也是一次位置不连续——整个时间轴换了一条，位置从新曲的 0 开始。
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                positionDiscontinuity++
                // 换了首歌，重试次数从头算：上一首的失败不该限制下一首。
                retryCount = 0
                retryJob?.cancel()
                // 上一首的错误提示也不该跟着飘过来（新歌成不成，等它自己的结果）。
                _state.value = _state.value.copy(error = null)
            }
            // 真的播起来了，之前攒的重试额度和"连跳"计数一起作废。
            if (player.playbackState == Player.STATE_READY) {
                retryCount = 0
                retryJob?.cancel()
                skippedUnavailable = 0
            }
            // 必须在 publish 之前算：宽限窗口是 publish 要读的状态。
            updatePlayIntent(player)
            publish(player)
            syncCurrentIndex(player)
            maybeContinuePlayback()
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
            // "这首歌确定放不了"（下架 / 无版权 / 拿不到直链）是结论而不是抖动：既不重试也不提示，
            // 直接往后跳 —— 一首坏歌不该把整份队列卡住。连着跳太多首才认输。
            if (error.songUnavailable() != null) {
                skipUnavailable(error)
                return
            }
            if (retryCount >= MaxPlaybackRetries) {
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
        if (player.playWhenReady && !player.isPlaying && player.playbackState != Player.STATE_ENDED) {
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
        skippedUnavailable = 0
        controller?.pause()
        _state.value = _state.value.copy(error = error.readableMessage())
    }

    /**
     * 播到放不了的歌：直接跳下一首，**不给提示**。
     *
     * 连着跳过 [MaxSkipUnavailable] 首还没落到一首能放的，就当这份队列没得听 —— 按原来的方式
     * 报错停住，免得一路跳到底、用户还以为是播放器卡了。
     */
    private fun skipUnavailable(error: PlaybackException) {
        val controller = controller ?: return
        val queue = liveQueue()
        val target = queue?.manualNextIndex(controller.currentMediaItemIndex)
        if (queue == null || target == null || skippedUnavailable + 1 >= MaxSkipUnavailable) {
            skippedUnavailable = 0
            giveUp(error)
            return
        }
        skippedUnavailable++
        playAt(controller, target, restartCurrent = false)
    }

    init {
        scope.launch(Dispatchers.Main) {
            val saved = withContext(Dispatchers.IO) { playbackStore.read() }
            if (queueStore.queue.value.entries.isEmpty() && saved != null) {
                queueStore.restore(saved)
                qualityStore.restorePlaybackQuality(saved)
                val entry = saved.queue.entries.getOrNull(saved.queue.currentIndex)
                val song = entry?.song
                _state.value = PlaybackState(
                    song = entry?.let { NowPlaying(it.song.id, it.song.name, it.song.artistText, it.song.cover, it.uid) },
                    positionMs = saved.positionMs,
                    durationMs = song?.durationMs?.coerceAtLeast(0L) ?: 0L,
                )
            }
            val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
            val future = MediaController.Builder(appContext, token).buildAsync()
            // 缓存先提供界面，再连接服务；已有的实时播放永远优先于磁盘快照。
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { controller ->
                            this@PlaybackConnection.controller = controller
                            val queue = queueStore.queue.value
                            controller.restorePlayback(queueStore.snapshot())
                            controller.repeatMode = queue.repeatMode()
                            controller.addListener(listener)
                            syncCurrentIndex(controller)
                            publish(controller)
                        }
                        .onFailure {
                            _state.value = _state.value.copy(error = "播放器没连上")
                        }
                },
                ContextCompat.getMainExecutor(appContext),
            )
        }

        // 播放位置不会自己回调，只能定时问
        scope.launch(Dispatchers.Main) {
            while (isActive) {
                controller?.takeIf { it.isPlaying }?.let { publish(it) }
                delay(PositionTickMs.milliseconds)
            }
        }

        scope.launch(Dispatchers.Main) {
            var owner = (account.session.value as? Session.LoggedIn)?.profile?.userId
            account.session.map { (it as? Session.LoggedIn)?.profile?.userId }
                .distinctUntilChanged().collect { userId ->
                    if (owner != userId && queue.value.infinite) {
                        disableInfinitePlayback()
                    }
                    owner = userId
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

            startQueue(controller, playQueueOf(listOf(song), 0, source = null, mode = currentMode(),
                infinite = queue.value.infinite))
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
     *
     * [shuffle] 是"随机那一轴"，跟只给一个随机 [startIndex] 不是一回事：前者建队即打乱当前位置
     * 之后的顺序、播放页那颗随机按钮跟着亮（列表循环回头时也是乱序接着走），后者只是挑了个起点、
     * 之后仍按列表原序往下放。「随机播放」按钮要的是 随机起点 + 整份列表 + 这个标志。
     */
    fun playSongs(
        songs: List<Song>,
        startIndex: Int,
        source: QueueSource? = null,
        shuffle: Boolean = false,
    ) {
        if (songs.isEmpty()) return

        scope.launch(Dispatchers.Main) {
            _state.value = _state.value.copy(error = null)

            val controller = controller
            if (controller == null) {
                _state.value = _state.value.copy(error = "播放器还没准备好")
                return@launch
            }

            startQueue(controller, playQueueOf(songs, startIndex, source, mode = currentMode(),
                shuffle = shuffle, infinite = queue.value.infinite))
        }
    }

    /**
     * 电台：种子歌打头，后面接相似歌曲 / 私人FM 的流。
     *
     * 整队标 [PlaybackQueue.radio]，播到快见底时由 [continueRadio] 再要一批 FM。
     *
     * 🔴 **不复用「无限播放」那个开关**：那个续的是心动模式，开关本身还亮在队列面板上，
     * 跟"电台模式下三颗按钮都点不动"直接冲突。两条续拉路径各走各的。
     */
    fun playRadio(opening: RadioOpening) {
        val songs = opening.queue
        if (songs.isEmpty()) return

        scope.launch(Dispatchers.Main) {
            _state.value = _state.value.copy(error = null)

            val controller = controller
            if (controller == null) {
                _state.value = _state.value.copy(error = "播放器还没准备好")
                return@launch
            }

            startQueue(
                controller,
                playQueueOf(
                    songs = songs,
                    startIndex = 0,
                    source = RadioQueueSource,
                    mode = PlayMode.SEQUENTIAL,
                    radio = true,
                ),
            )
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
        advanceAfterRecommendations = false
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
        val target = queue.manualNextIndex(controller.currentMediaItemIndex)
        if (target == null) {
            if (queue.infinite) {
                advanceAfterRecommendations = true
                maybeContinuePlayback(force = true)
            }
            return
        }
        advanceAfterRecommendations = false
        controller.seekTo(target, 0L)
        if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
        if (!controller.playWhenReady) controller.play()
    }

    /** 手动「上一首」，同样不受单曲循环约束。 */
    fun skipToPrevious() {
        advanceAfterRecommendations = false
        val controller = controller ?: return
        val queue = liveQueue() ?: return
        val target = queue.manualPreviousIndex(controller.currentMediaItemIndex) ?: return
        controller.seekTo(target, 0L)
        if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
        if (!controller.playWhenReady) controller.play()
    }

    /**
     * 切**循环**那一轴（顺序 / 列表循环 / 单曲循环）。随机那一轴原样保留 —— 两根轴互不干扰。
     *
     * 电台模式下直接忽略：电台的"循环"就是它自己的无限续拉，档位改了没有任何意义。
     */
    fun setLoopMode(mode: PlayMode) {
        if (queue.value.radio) return
        applyMode(loop = mode, shuffle = currentShuffle())
    }

    /**
     * 切**随机**那一轴。
     *
     * 随机不是播放器那个 shuffle，而是把播放列表重排一遍（只动当前位置之后的基准条目，
     * 插播条目原地不动），所以要多一步 `moveMediaItem`。关掉时按建队时的基准序还原。
     */
    fun setShuffle(enabled: Boolean) {
        // 电台不给打乱：那 3 首一批的顺序是服务端给的，重排也只是把同一批搅一遍。
        if (queue.value.radio) return
        applyMode(loop = currentMode(), shuffle = enabled)
    }

    fun setInfinite(enabled: Boolean) {
        // 电台自己就是无限的，这开关对它是空操作（面板上那颗按钮也是禁用的）。
        if (queue.value.radio) return
        scope.launch(Dispatchers.Main.immediate) {
            if (!enabled) {
                disableInfinitePlayback()
                return@launch
            }
            val uid = (account.session.value as? Session.LoggedIn)?.profile?.userId
            if (uid == null || uid <= 0) {
                _infiniteMessages.tryEmit("无限播放需要登录网易云音乐")
                return@launch
            }
            val current = liveQueue() ?: return@launch
            invalidateInfinitePlayback()
            queueStore.set(current.withInfinite(true))
            controller?.repeatMode = Player.REPEAT_MODE_OFF
            maybeContinuePlayback()
        }
    }

    private fun disableInfinitePlayback() {
        invalidateInfinitePlayback()
        val current = liveQueue() ?: queue.value
        val disabled = current.withInfinite(false)
        val removals = current.pendingRecommendationRemovalRanges()
        val player = controller
        if (removals.isNotEmpty() && player != null) {
            if (player.mediaItemCount != current.entries.size ||
                !player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
                queueStore.set(current.copy(infinite = false))
                return
            }
        }
        // 当前及之前的索引不变；先发布最终队列，删除回调不会再预取推荐。
        queueStore.set(disabled)
        // 逐首删除会反复重建 MediaController 时间轴并派发远端更新。
        removals.forEach { range -> player?.removeMediaItems(range.first, range.last + 1) }
    }

    private fun invalidateInfinitePlayback() {
        infiniteGeneration++
        infiniteJob?.cancel()
        infiniteJob = null
        infiniteSeed = null
        infiniteCursor = null
        advanceAfterRecommendations = false
        _infiniteState.value = InfinitePlaybackState()
    }

    private fun maybeContinuePlayback(force: Boolean = false) {
        val player = controller ?: return
        val initial = liveQueue() ?: return
        if ((!initial.infinite && !initial.radio) || infiniteJob?.isActive == true) return
        if (!force && initial.entries.lastIndex - initial.currentIndex > 3) return
        if (player.mediaItemCount != initial.entries.size) return

        // 电台的长尾只认私人FM，不需要登录态也不需要心动模式的歌单种子。
        if (initial.radio) {
            continueRadio(player, initial)
            return
        }

        val userId = (account.session.value as? Session.LoggedIn)?.profile?.userId ?: return
        if (userId <= 0) return
        val request = infiniteGeneration
        val firstUid = initial.entries.first().uid
        val current = initial.entries[initial.currentIndex]
        _infiniteState.value = InfinitePlaybackState(loading = true)
        infiniteJob = scope.launch(Dispatchers.Main) {
            try {
                retryInfinitePlayback(
                    isCurrent = { isCurrentInfiniteRequest(request, firstUid, userId) },
                    onFailure = { Log.w("CirroPlayback", "无限播放加载失败，将静默重试", it) },
                ) {
                    val seed = infiniteSeed ?: intelligence.seed(
                        current.song.id,
                        initial.source?.takeIf { it.kind == CollectionKind.PLAYLIST } ?: current.source,
                        userId,
                    ).getOrThrow()
                    if (!isCurrentInfiniteRequest(request, firstUid, userId)) return@retryInfinitePlayback
                    infiniteSeed = seed
                    val cursor = infiniteCursor ?: initial.entries.last().song.id
                    val songs = intelligence.songs(seed, cursor).getOrThrow()
                    if (!isCurrentInfiniteRequest(request, firstUid, userId)) return@retryInfinitePlayback
                    songs.lastOrNull()?.let { infiniteCursor = it.id }
                    val live = liveQueue() ?: return@retryInfinitePlayback
                    val extended = live.withRecommendations(
                        songs, QueueSource(CollectionKind.PLAYLIST, seed.playlistId.toString(), "心动模式"),
                    )
                    if (extended === live) error("暂时没有新的推荐歌曲")
                    if (player.mediaItemCount != live.entries.size) return@retryInfinitePlayback
                    val ended = player.playbackState == Player.STATE_ENDED
                    val resume = ended && player.playWhenReady
                    val added = extended.entries.drop(live.entries.size)
                    // 先发布来源，服务的切歌回调才能为新曲取到正确的打卡和歌词元数据。
                    check(player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS))
                    queueStore.set(extended)
                    try {
                        player.addMediaItems(added.map { it.song.toMediaItem(it.uid) })
                    } catch (error: Exception) {
                        queueStore.set(live.withCurrent(player.currentMediaItemIndex))
                        throw error
                    }
                    _infiniteState.value = InfinitePlaybackState()
                    if (advanceAfterRecommendations || resume) {
                        val target = if (ended) live.entries.size else player.currentMediaItemIndex + 1
                        playAt(player, target, restartCurrent = false)
                    }
                }
            } finally {
                if (request == infiniteGeneration) {
                    infiniteJob = null
                    _infiniteState.value = _infiniteState.value.copy(loading = false)
                }
            }
        }
    }

    /**
     * 电台续拉：再要一批私人FM（3 首），追加到队尾。
     *
     * 跟心动模式那条路的差别：没有歌单种子、没有 userId 校验，唯一的作废条件是"队列已经不是
     * 同一台电台了"（队首 uid 变了、或者 radio 标记被清掉）——用户中途点了别的歌单，
     * 这批就不该再落回来。
     */
    private fun continueRadio(player: MediaController, initial: PlaybackQueue) {
        val request = infiniteGeneration
        val firstUid = initial.entries.first().uid
        _infiniteState.value = InfinitePlaybackState(loading = true)
        infiniteJob = scope.launch(Dispatchers.Main) {
            try {
                retryInfinitePlayback(
                    isCurrent = { isCurrentRadioRequest(request, firstUid) },
                    onFailure = { Log.w("CirroPlayback", "电台续拉失败，将静默重试", it) },
                ) {
                    val live = liveQueue() ?: return@retryInfinitePlayback
                    val tail = live.entries.lastOrNull()?.song ?: return@retryInfinitePlayback
                    val played = live.entries.mapTo(mutableSetOf()) { it.song.id }
                    val songs = radio.next(tail, played).getOrThrow()
                    if (!isCurrentRadioRequest(request, firstUid)) return@retryInfinitePlayback
                    val extended = live.withRecommendations(songs, RadioQueueSource)
                    if (extended === live) error("暂时没有新的推荐歌曲")
                    if (player.mediaItemCount != live.entries.size) return@retryInfinitePlayback
                    val ended = player.playbackState == Player.STATE_ENDED
                    val added = extended.entries.drop(live.entries.size)
                    // 先发布来源，服务的切歌回调才能为新曲取到正确的打卡和歌词元数据。
                    check(player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS))
                    queueStore.set(extended)
                    try {
                        player.addMediaItems(added.map { it.song.toMediaItem(it.uid) })
                    } catch (error: Exception) {
                        queueStore.set(live.withCurrent(player.currentMediaItemIndex))
                        throw error
                    }
                    _infiniteState.value = InfinitePlaybackState()
                    // 放完了才回来的那批：直接续上，否则会停在原地等人点。
                    if (ended && player.playWhenReady) {
                        playAt(player, live.entries.size, restartCurrent = false)
                    }
                }
            } finally {
                if (request == infiniteGeneration) {
                    infiniteJob = null
                    _infiniteState.value = _infiniteState.value.copy(loading = false)
                }
            }
        }
    }

    private fun isCurrentRadioRequest(request: Int, firstUid: Long): Boolean =
        request == infiniteGeneration && queue.value.radio &&
            queue.value.entries.firstOrNull()?.uid == firstUid

    private fun isCurrentInfiniteRequest(request: Int, firstUid: Long, userId: Long): Boolean =
        request == infiniteGeneration && queue.value.infinite &&
            queue.value.entries.firstOrNull()?.uid == firstUid &&
            (account.session.value as? Session.LoggedIn)?.profile?.userId == userId

    /**
     * 两根轴一起落到模型和播放器上。
     *
     * 🔴 只在随机那一轴真的翻转时才重排列表：重复随机一次等于换一批顺序（用户只是点了一下
     * 循环，不该看到列表被打乱），照原样调 `shuffledTail()` 会白白重新洗一遍。
     */
    private fun applyMode(loop: PlayMode, shuffle: Boolean) {
        scope.launch(Dispatchers.Main) {
            if (loop != PlayMode.SEQUENTIAL && queue.value.infinite) disableInfinitePlayback()
            val controller = controller
            val queue = liveQueue()
            if (controller == null || queue == null) {
                queueStore.set(queueStore.queue.value.copy(mode = loop, shuffle = shuffle,
                    infinite = queueStore.queue.value.infinite && loop == PlayMode.SEQUENTIAL))
                return@launch
            }

            val reordered = when {
                shuffle == queue.shuffle -> queue
                shuffle -> queue.shuffledTail()
                else -> queue.restoredTail()
            }.copy(mode = loop, shuffle = shuffle, infinite = queue.infinite && loop == PlayMode.SEQUENTIAL)

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
            advanceAfterRecommendations = false
            // 手动暂停要连重试一起取消，否则退避到点它又自己放起来。
            retryJob?.cancel()
            retryJob = null
            controller.pause()
        } else {
            retryCount = 0
            if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
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
        advanceAfterRecommendations = false
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
        invalidateInfinitePlayback()
        // 新点播一次：之前那首攒下的重试额度和退避任务都不该跟过来。
        retryJob?.cancel()
        retryJob = null
        retryCount = 0
        queueStore.set(queue)
        controller.setMediaItems(
            queue.entries.map { it.song.toMediaItem(it.uid) },
            queue.currentIndex,
            0L,
        )
        controller.repeatMode = queue.repeatMode()
        controller.prepare()
        controller.play()
        maybeContinuePlayback()
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
     * 🔴 **整段一次换掉**，不能逐条 `moveMediaItem`：MediaController 每做一次列表操作，都要经
     * `IMediaSession` 发过去、再把整份 playlist 重新打包回推一遍（单次 O(n)）。逐条搬 n 个槽位就是
     * O(n²) —— 队列上千首时点一下随机就是几千万次 MediaItem 打包，主线程直接 OOM/ANR
     * （androidx/media#81）。建队那条路（[startQueue]）只发一次 `setMediaItems`，所以它一直没事。
     *
     * 🔴 改动只发生在当前位置**之后**（`PlaybackQueue.shuffledTail()` / `restoredTail()` 的契约），
     * 所以替换区间永远不含当前曲：它的 media source 原地不动，播放不中断。代价是被换掉的条目要重建 media source
     * —— 下一首的预读缓冲丢掉，真正播到时才重新解析直链（直链本来就是临播前才换的）。
     */
    private fun reorderPlayer(from: List<QueueEntry>, to: List<QueueEntry>) {
        val controller = controller ?: return
        if (controller.mediaItemCount != from.size) return
        val start = from.indices.firstOrNull { from[it].uid != to[it].uid } ?: return
        controller.replaceMediaItems(
            start, to.size, to.drop(start).map { it.song.toMediaItem(it.uid) },
        )
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
 * 电台队列的来源。
 *
 * 没有集合 id —— 打卡时 `sourceId` 留空（电台不对应服务端任何一个集合），
 * 名称只给日志和将来的队列界面看。
 */
private val RadioQueueSource = QueueSource(title = "电台")

private fun MediaItem.toNowPlaying(): NowPlaying? {
    val id = mediaId.toLongOrNull() ?: return null
    return NowPlaying(
        id = id,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
        cover = mediaMetadata.artworkUri?.toString(),
        queueEntryUid = queueEntryUid,
    )
}

/** 把异常链里的"放不了"翻成人话，其余给一个笼统的说法。 */
private fun Throwable.readableMessage(): String =
    songUnavailable()?.message?.takeIf { it.isNotEmpty() } ?: message ?: "播放出错了"
