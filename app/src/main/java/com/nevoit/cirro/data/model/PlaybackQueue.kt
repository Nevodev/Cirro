package com.nevoit.cirro.data.model

import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

/**
 * 播放模式。
 *
 * 顺序 / 列表循环 / 单曲循环 交给播放器自己的 `repeatMode`；**随机不走播放器的 shuffle**
 * ——它由我们重排播放列表实现。理由是播放器的 shuffle 会把临时插播的歌一起卷进乱序里，
 * 那就做不到"紧接着播"了。
 */
enum class PlayMode {
    /** 顺序播放：最后一首放完就停。 */
    SEQUENTIAL,

    /** 列表循环：最后一首放完回到第一首。 */
    REPEAT_ALL,

    /**
     * 单曲循环：**自然放完**重播本曲；**手动上/下一首照常切歌**，不受它约束。
     *
     * 所以手动切歌一律自己算目标下标，不能用 `seekToNextMediaItem()`——那个跟着 `repeatMode` 走，
     * 在单曲循环下会原地不动。而自然放完交给 `REPEAT_MODE_ONE`，插播区就自然被跳过了。
     */
    REPEAT_ONE,

    /** 随机播放：一轮内不重复。 */
    SHUFFLE,
}

/**
 * 队列来源。既给打卡的 `sourceId`，也给以后"正在播自哪个歌单"这类展示。
 *
 * 单曲播放、或者上游本来就没有集合 id 时整条为 null。
 */
@Serializable
data class QueueSource(
    val kind: CollectionKind? = null,
    val id: String = "",
) {
    /** 送去打卡的 `sourceId`。每日推荐是现拼的列表、没有对应的服务端集合，只能留空。 */
    val scrobbleId: String get() = if (kind == CollectionKind.DAILY) "" else id
}

/**
 * 队列里的一条。
 *
 * [uid] 是这一条的**身份**，跟是哪首歌无关——同一首歌可以在队列里出现两次，随机重排时
 * 靠它认人，不能拿 `song.id` 代替。
 *
 * [inserted] 标记"临时插播进来的"。插播条目**不参与随机重排**，永远钉在它们被插的位置上。
 *
 * [seq] 是建队时的**基准序**。关掉随机时按它还原原始顺序。
 */
@Serializable
data class QueueEntry(
    val uid: Long,
    val song: Song,
    val source: QueueSource? = null,
    val inserted: Boolean = false,
    val seq: Int = 0,
)

/**
 * 播放队列快照。
 *
 * [entries] 的顺序**就是播放器的顺序**，索引与播放器的 media item 一一对应——插播、随机都是
 * 先把这里算好，再去改播放器，两边必须同进同退。
 *
 * [currentIndex] 是"正在播的那条"，跟着播放器的 `currentMediaItemIndex` 走。
 */
@Serializable
data class PlaybackQueue(
    val entries: List<QueueEntry> = emptyList(),
    val currentIndex: Int = 0,
    val mode: PlayMode = PlayMode.SEQUENTIAL,
)

/** 建队：歌单 / 专辑 / 每日推荐点一首、首页播一整块都走这里。随机模式下建队即打乱。 */
fun playQueueOf(
    songs: List<Song>,
    startIndex: Int,
    source: QueueSource?,
    mode: PlayMode = PlayMode.SEQUENTIAL,
): PlaybackQueue {
    if (songs.isEmpty()) return PlaybackQueue(mode = mode)
    val index = startIndex.coerceIn(0, songs.lastIndex)
    val entries = songs.mapIndexed { i, song ->
        QueueEntry(uid = nextEntryUid(), song = song, source = source, seq = i)
    }
    val queue = PlaybackQueue(entries = entries, currentIndex = index, mode = mode)
    return if (mode == PlayMode.SHUFFLE) queue.shuffledTail() else queue
}

/** 把 [index] 认作当前位置（播放器说了算）。越界就原样返回。 */
fun PlaybackQueue.withCurrent(index: Int): PlaybackQueue =
    if (index in entries.indices) copy(currentIndex = index) else this

/** 「下一个播放」的落点：紧接着当前这首。 */
val PlaybackQueue.insertNextIndex: Int
    get() = (currentIndex + 1).coerceAtMost(entries.size)

/**
 * 「添加到插播队列」的落点：跳过当前之后**已有的**插播条目，排到它们后面。
 * 手上没有插播时，它跟「下一个播放」就是同一个位置。
 */
val PlaybackQueue.appendInsertIndex: Int
    get() {
        var i = currentIndex + 1
        while (i < entries.size && entries[i].inserted) i++
        return i
    }

/** 在 [at] 处插一条临时插播。 */
fun PlaybackQueue.withInsert(song: Song, source: QueueSource?, at: Int): PlaybackQueue {
    val entry = QueueEntry(uid = nextEntryUid(), song = song, source = source, inserted = true)
    val list = entries.toMutableList().apply { add(at.coerceIn(0, size), entry) }
    return copy(entries = list)
}

/** 切到随机：只重排当前位置**之后**的基准条目，插播条目和已播过的一律原地不动。 */
fun PlaybackQueue.shuffledTail(): PlaybackQueue {
    val slots = baseSlotsAfterCurrent()
    if (slots.size < 2) return this
    val list = entries.toMutableList()
    val pool = slots.map { list[it] }.shuffled()
    slots.forEachIndexed { i, slot -> list[slot] = pool[i] }
    return copy(entries = list)
}

/** 离开随机：按建队时的基准序还原。 */
fun PlaybackQueue.restoredTail(): PlaybackQueue {
    val slots = baseSlotsAfterCurrent()
    if (slots.size < 2) return this
    val list = entries.toMutableList()
    val pool = slots.map { list[it] }.sortedBy { it.seq }
    slots.forEachIndexed { i, slot -> list[slot] = pool[i] }
    return copy(entries = list)
}

/**
 * 手动「下一首」的目标，**忽略单曲循环**（手动切歌不受模式约束）。
 *
 * 到头了返回 null：顺序 / 单曲循环就是没有下一首，列表循环 / 随机则回到第一首。
 */
fun PlaybackQueue.manualNextIndex(from: Int): Int? = when {
    from + 1 < entries.size -> from + 1
    mode == PlayMode.SEQUENTIAL || mode == PlayMode.REPEAT_ONE -> null
    entries.isNotEmpty() -> 0
    else -> null
}

/** 手动「上一首」的目标，**忽略单曲循环**。到头了返回 null。 */
fun PlaybackQueue.manualPreviousIndex(from: Int): Int? = when {
    from - 1 >= 0 -> from - 1
    mode == PlayMode.SEQUENTIAL || mode == PlayMode.REPEAT_ONE -> null
    entries.isNotEmpty() -> entries.lastIndex
    else -> null
}

/** 第 [index] 条该报的 `sourceId`，没有就是空串。 */
fun PlaybackQueue.sourceIdAt(index: Int): String =
    entries.getOrNull(index)?.source?.scrobbleId.orEmpty()

/** 当前位置之后、**不是插播**的那些槽位。随机重排只动它们。 */
private fun PlaybackQueue.baseSlotsAfterCurrent(): List<Int> =
    (currentIndex + 1 until entries.size).filter { !entries[it].inserted }

private val EntryUidSeq = AtomicLong()

private fun nextEntryUid(): Long = EntryUidSeq.incrementAndGet()
