package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.PlaybackQueue
import com.nevoit.cirro.data.model.PlaybackHistoryEntry
import com.nevoit.cirro.data.model.QueueEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 播放队列的唯一真相源（进程内）。
 *
 * 为什么要单独拎一个东西出来、而不是塞进 [com.nevoit.cirro.playback.PlaybackConnection]：
 * 队列有两个读者，且它们分处两端——
 * - **写方**是 PlaybackConnection，因为只有它拿着 MediaController，模型和播放器列表必须一起改；
 * - **读方**之一是 PlaybackService（打卡要知道当前这首来自哪个歌单），而 Service 反过来依赖
 *   Connection 是说不通的（Connection 是它自己的控制器）。
 *
 * 索引与播放器列表**一一对应**：谁动了播放器的列表，就得在同一处把这里也改掉。
 *
 * [`queue`][queue] 的每一处改动都发生在主线程（MediaController 的回调线程），订阅方随便读。
 * [history] 由 PlaybackService 的真实播放事件追加，独立于队列顺序和循环游标。
 */
class PlayQueueStore {

    private val _queue = MutableStateFlow(PlaybackQueue())

    val queue: StateFlow<PlaybackQueue> = _queue.asStateFlow()

    private val _history = MutableStateFlow<List<PlaybackHistoryEntry>>(emptyList())
    val history: StateFlow<List<PlaybackHistoryEntry>> = _history.asStateFlow()

    private var playing: PlaybackHistoryEntry? = null
    private var nextHistoryId = 0L

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
        _queue.value = queue
    }

    fun clear() {
        _queue.value = PlaybackQueue()
    }
}
