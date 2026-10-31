package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.PlaybackQueue
import com.nevoit.cirro.data.model.PlaybackHistoryEntry
import com.nevoit.cirro.data.model.QueueEntry
import com.nevoit.cirro.data.model.reserveEntryUids
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 播放队列的唯一真相源；队列与进度变更提交持久化快照，历史只在进程内保留。
 *
 * 为什么要单独拎一个东西出来、而不是塞进 [com.nevoit.cirro.playback.PlaybackConnection]：
 * 队列有两个读者，且它们分处两端——
 * - PlaybackConnection 同步队列操作，PlaybackService 从真实播放事件同步游标与进度；
 * - **读方**之一是 PlaybackService（打卡要知道当前这首来自哪个歌单），而 Service 反过来依赖
 *   Connection 是说不通的（Connection 是它自己的控制器）。
 *
 * 索引与播放器列表**一一对应**：谁动了播放器的列表，就得在同一处把这里也改掉。
 *
 * [`queue`][queue] 的每一处改动都发生在主线程（MediaController 的回调线程），订阅方随便读。
 * [history] 由 PlaybackService 的真实播放事件追加，独立于队列顺序和循环游标，绝不落盘。
 */
class PlayQueueStore(private val onPlaybackChanged: (PlaybackSnapshot) -> Unit = {}) {

    private val _queue = MutableStateFlow(PlaybackQueue())

    val queue: StateFlow<PlaybackQueue> = _queue.asStateFlow()

    private val _history = MutableStateFlow<List<PlaybackHistoryEntry>>(emptyList())
    val history: StateFlow<List<PlaybackHistoryEntry>> = _history.asStateFlow()

    private var playing: PlaybackHistoryEntry? = null
    private var nextHistoryId = 0L
    private var audioQuality: ResolvedAudioQuality? = null
    var positionMs: Long = 0L
        private set

    fun restore(snapshot: PlaybackSnapshot) {
        val restored = snapshot.validated() ?: return
        restored.queue.reserveEntryUids()
        positionMs = restored.positionMs
        audioQuality = restored.resolvedAudioQuality
        _queue.value = restored.queue
    }

    fun snapshot(): PlaybackSnapshot {
        val queue = _queue.value
        val actual = audioQuality?.takeIf { it.songId == queue.entries.getOrNull(queue.currentIndex)?.song?.id }
        return PlaybackSnapshot(queue, positionMs, actual?.quality?.wire, actual?.requestedQuality?.wire)
    }

    fun updateAudioQuality(entryUid: Long?, quality: ResolvedAudioQuality?) {
        val entry = _queue.value.entries.getOrNull(_queue.value.currentIndex) ?: return
        if (entryUid == null || entry.uid != entryUid || (quality != null && quality.songId != entry.song.id)) return
        if (audioQuality == quality) return
        audioQuality = quality
        onPlaybackChanged(snapshot())
    }

    fun updatePosition(entryUid: Long?, positionMs: Long) {
        val queue = _queue.value
        if (entryUid == null || queue.entries.getOrNull(queue.currentIndex)?.uid != entryUid) return
        this.positionMs = positionMs.coerceAtLeast(0L)
        onPlaybackChanged(snapshot())
    }

    fun markPlaying(entry: QueueEntry, startedAtMillis: Long = System.currentTimeMillis()) {
        if (playing?.entry?.uid == entry.uid) return
        finishPlaybackHistory()
        playing = PlaybackHistoryEntry(++nextHistoryId, entry, startedAtMillis)
    }

    fun finishPlaybackHistory(nextUid: Long? = null, repeated: Boolean = false) {
        val played = playing ?: return
        // 重排和元数据更新不能拆出新记录；单曲循环则是同一条目的一次新播放。
        if (played.entry.uid == nextUid && !repeated) return
        _history.value = _history.value + played
        playing = null
    }

    fun set(queue: PlaybackQueue) {
        val previous = _queue.value
        if (previous.entries.getOrNull(previous.currentIndex)?.uid != queue.entries.getOrNull(queue.currentIndex)?.uid) {
            positionMs = 0L
            audioQuality = null
        }
        _queue.value = queue
        onPlaybackChanged(snapshot())
    }

    fun clear() {
        set(PlaybackQueue())
    }
}
