package com.nevoit.cirro.data.model

import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

/**
 * 播放模式 —— **循环那一轴**：顺序 / 列表循环 / 单曲循环。
 *
 * 🔴 **随机不在这里**，它是另一根正交的轴（[PlaybackQueue.shuffle]）。播放页那两颗按钮各自
 * 记自己的状态：循环三档、随机一开一关，所以「随机 + 单曲循环」这种组合必须能同时成立 ——
 * 塞进一个枚举就只能二选一了。
 *
 * 三个循环档都交给播放器自己的 `repeatMode`；**随机不走播放器的 shuffle**——它由我们重排
 * 播放列表实现。理由是播放器的 shuffle 会把临时插播的歌一起卷进乱序里，那就做不到"紧接着播"了。
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
    REPEAT_ONE;

    /** 循环按钮转一圈：顺序 → 列表循环 → 单曲循环 → 顺序。 */
    fun nextLoop(): PlayMode = when (this) {
        SEQUENTIAL -> REPEAT_ALL
        REPEAT_ALL -> REPEAT_ONE
        REPEAT_ONE -> SEQUENTIAL
    }
}

/**
 * 队列来源。名称用于展示，集合 id 用于打卡的 `sourceId`。
 *
 * 单曲播放或上游没有来源信息时整条为 null；首页混排块可以只给名称。
 */
@Serializable
data class QueueSource(
    val kind: CollectionKind? = null,
    val id: String = "",
    val title: String = "",
) {
    /** 每日推荐和艺术家热门歌曲没有对应的服务端集合，不能把页面 id 用于打卡。 */
    val scrobbleId: String get() = if (kind == CollectionKind.DAILY || kind == CollectionKind.ARTIST) "" else id
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
    /** 手动添加也可能来自同一歌单，清理自动推荐不能只根据 [source] 判断。 */
    val recommended: Boolean = false,
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
    /**
     * 随机那一轴，跟 [mode] 正交。
     *
     * 开 = 当前位置之后的基准条目打乱（插播条目原地不动），关 = 按建队基准序还原。
     * 它**不改循环**：随机 + 顺序就是"打乱之后放完就停"。
     */
    val shuffle: Boolean = false,
    /** 整队起播时的来源，切歌、插播和重排都保留；每首歌的出身仍看 [QueueEntry.source]。 */
    val source: QueueSource? = null,
    /** 无限播放和循环互斥，随机仍独立；关闭时移除当前歌曲之后的自动推荐。 */
    val infinite: Boolean = false,
    /**
     * 电台模式：整队是一条会自己续下去的流。
     *
     * 跟 [infinite] 的区别只在「谁来续」——无限播放续的是心动模式（按当前队列的种子歌），
     * 电台续的是私人 FM。电台模式下循环 / 随机 / 无限播放三颗按钮**全部禁用**：
     * 这三件事都由电台自己决定，用户改了也没有意义。
     */
    val radio: Boolean = false,
)

/**
 * 建队：歌单 / 专辑 / 每日推荐点一首、首页播一整块都走这里。
 *
 * 🔴 **随机建队 = 整队乱序、从第一首播起**（"随机起点"就落在乱序后的第一首上），**不能**复用
 * [shuffledTail] 去洗尾部 —— 那个函数是给"播放途中切随机"用的，它要护住正在播的那一首不动；
 * 建队时压根没有"正在播"，复用它就成了"第 0 首钉死不动"：起点之后的乱序、起点之前的却还是原序，
 * 而待播区只画当前位置及之后，于是界面上凭空少了半份歌单。
 *
 * [QueueEntry.seq] 一律按**原歌单顺序**记，所以关掉随机还能回到歌单原序。
 */
fun playQueueOf(
    songs: List<Song>,
    startIndex: Int,
    source: QueueSource?,
    mode: PlayMode = PlayMode.SEQUENTIAL,
    shuffle: Boolean = false,
    infinite: Boolean = false,
    radio: Boolean = false,
): PlaybackQueue {
    if (songs.isEmpty()) {
        return PlaybackQueue(mode = mode, shuffle = shuffle, infinite = infinite, radio = radio)
    }
    val entries = songs.mapIndexed { i, song ->
        QueueEntry(uid = nextEntryUid(), song = song, source = source, seq = i)
    }
    return PlaybackQueue(
        entries = if (shuffle) entries.shuffled() else entries,
        currentIndex = if (shuffle) 0 else startIndex.coerceIn(0, songs.lastIndex),
        mode = mode,
        shuffle = shuffle,
        source = source,
        infinite = infinite,
        radio = radio,
    )
}

/** 把 [index] 认作当前位置（播放器说了算）。越界就原样返回。 */
fun PlaybackQueue.withCurrent(index: Int): PlaybackQueue =
    if (index in entries.indices) copy(currentIndex = index) else this

val PlaybackQueue.entriesFromCurrent: List<QueueEntry>
    get() = if (currentIndex in entries.indices) {
        entries.drop(currentIndex)
    } else {
        entries
    }

fun PlaybackQueue.canMoveEntry(fromUid: Long, toUid: Long): Boolean =
    canMoveEntryAt(
        entries.indexOfFirst { it.uid == fromUid },
        entries.indexOfFirst { it.uid == toUid },
    )

fun PlaybackQueue.withMovedEntry(fromUid: Long, toUid: Long): PlaybackQueue {
    val from = entries.indexOfFirst { it.uid == fromUid }
    val to = entries.indexOfFirst { it.uid == toUid }
    if (!canMoveEntryAt(from, to)) return this
    val moved = entries.toMutableList().apply { add(to, removeAt(from)) }
    return copy(entries = moved)
}

private fun PlaybackQueue.canMoveEntryAt(from: Int, to: Int): Boolean =
    currentIndex in entries.indices &&
        from in entries.indices && to in entries.indices && from != to &&
        from > currentIndex && to > currentIndex

/** 「下一个播放」的落点：紧接着当前这首。 */
val PlaybackQueue.insertNextIndex: Int
    get() = (currentIndex + 1).coerceAtMost(entries.size)

val PlaybackQueue.hasPendingInserts: Boolean
    get() = currentIndex in entries.indices && entries.getOrNull(currentIndex + 1)?.inserted == true

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

/** 推荐只追加到尾部；保留当前游标、用户插播和整队来源，排除已经入队的歌及批次内重复。 */
fun PlaybackQueue.withRecommendations(songs: List<Song>, source: QueueSource): PlaybackQueue {
    val seen = entries.mapTo(mutableSetOf()) { it.song.id }
    val nextSeq = (entries.maxOfOrNull { it.seq } ?: -1) + 1
    val added = songs.filter { it.id > 0 && seen.add(it.id) }.mapIndexed { index, song ->
        QueueEntry(nextEntryUid(), song, source, seq = nextSeq + index, recommended = true)
    }
    return if (added.isEmpty()) this else copy(entries = entries + added)
}

fun PlaybackQueue.withInfinite(enabled: Boolean): PlaybackQueue =
    if (enabled) copy(infinite = true, mode = PlayMode.SEQUENTIAL)
    else copy(infinite = false, entries = entries.filterIndexed { index, entry ->
        index <= currentIndex || !entry.recommended
    })

/** 连续推荐合并删除，范围按尾部优先排列，前面的索引不会因删除而失效。 */
fun PlaybackQueue.pendingRecommendationRemovalRanges(): List<IntRange> = buildList {
    var index = entries.lastIndex
    while (index > currentIndex) {
        if (!entries[index].recommended) {
            index--
            continue
        }
        val endExclusive = index + 1
        while (index > currentIndex && entries[index].recommended) index--
        add(index + 1 until endExclusive)
    }
}

/** 切到随机：只重排当前位置**之后**的基准条目，插播条目和当前之前的队列条目原地不动。 */
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
 * 到头了返回 null：顺序（含"随机 + 顺序"）就是没有下一首，列表循环则回到第一首。
 */
fun PlaybackQueue.manualNextIndex(from: Int): Int? {
    if (from + 1 < entries.size) return from + 1
    return if (mode == PlayMode.REPEAT_ALL && entries.isNotEmpty()) 0 else null
}

/** 手动「上一首」的目标，**忽略单曲循环**。到头了返回 null。 */
fun PlaybackQueue.manualPreviousIndex(from: Int): Int? {
    if (from - 1 >= 0) return from - 1
    return if (mode == PlayMode.REPEAT_ALL && entries.isNotEmpty()) entries.lastIndex else null
}

/** 第 [index] 条该报的 `sourceId`，没有就是空串。 */
fun PlaybackQueue.sourceIdAt(index: Int): String =
    entries.getOrNull(index)?.source?.scrobbleId.orEmpty()

/** 当前位置之后、**不是插播**的那些槽位。随机重排只动它们。 */
private fun PlaybackQueue.baseSlotsAfterCurrent(): List<Int> =
    (currentIndex + 1 until entries.size).filter { !entries[it].inserted }

private val EntryUidSeq = AtomicLong()

internal fun PlaybackQueue.reserveEntryUids() {
    val highest = entries.maxOfOrNull { it.uid } ?: return
    EntryUidSeq.updateAndGet { maxOf(it, highest) }
}

private fun nextEntryUid(): Long = EntryUidSeq.incrementAndGet()
