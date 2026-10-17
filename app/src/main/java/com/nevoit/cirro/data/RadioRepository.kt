package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** 电台卡片要几张图：种子一张 + 两位不同艺人的头像。 */
private const val CoverCount = 3

/** 除种子之外，卡片上还要几位艺人的头像。 */
private const val GuestCount = 2

/** 开场要几首：种子 + 这几首就是起播时的队列。 */
private const val OpeningCount = 5

/** 之后每次续拉几首。 */
private const val BatchCount = 3

/**
 * 一台电台的起步。
 *
 * [seed] 是种子歌（近七天播放最多那首），它**不重复进 [songs]**，但播放时排在队列最前面 ——
 * 所以卡片的大圆是它、队列的第 0 首也是它。
 */
data class RadioOpening(
    val seed: Song,
    val songs: List<Song>,
    /**
     * 第二、三张圆图：**艺人头像**（不是专辑封面），最多两张。
     *
     * 头像拿不到（上游本来就没图 / 请求失败）时退回那位艺人的歌曲封面 ——
     * 有张图好过空一个洞。顺序就是挑艺人的顺序。
     */
    val guestCovers: List<String> = emptyList(),
) {
    /** 整队：种子在前，之后是这批推荐。 */
    val queue: List<Song> get() = listOf(seed) + songs

    /**
     * 卡片上的三张圆图，按顺序：种子封面 + 两位艺人的头像。
     *
     * 🔴 第二、三张走 `/api/artist/head/info/get`（约 250ms 一次，**没有批量版**），
     * 所以 [guestCovers] 在仓库里就并发取好了，界面不再发请求。
     *
     * 去重键是**主艺人**而不是歌：相似链越走越窄，连着几首同一位艺人是常态，
     * 按歌取图会得到三个同一个人的作品。凑不满就返回实际的几张，不拿重复的脸充数。
     */
    val covers: List<String>
        get() = (listOf(seed.cover) + guestCovers).filter { it.isNotEmpty() }
}

/** 去重用的艺人身份：主艺人 id 优先，缺 id 退回名字，都没有才按歌区分。 */
private fun Song.artistKey(): String {
    val artistId = artistIds.firstOrNull() ?: 0L
    if (artistId > 0) return "id:$artistId"
    val name = artists.firstOrNull().orEmpty()
    return if (name.isEmpty()) "song:$id" else "name:$name"
}

/**
 * 电台：种子歌 + 插播相似歌曲的无限链。
 *
 * 网易云没有「以一首歌为种子的无限电台」这种端点，最贴近的是 `/song/simi/get`
 * （官方客户端"插播相似歌曲"用的就是它）：**传一首歌，回一首相似的**。
 * 所以这里的结构是「一颗种子，之后每一首都是上一首的相似歌」——
 * 自然越走越远，也正是官方那种"一首一首插"的手感。
 *
 * 相似链断了（某次回空 / 全是已经入队的）才回落到私人 FM：它每次给三首全新的，
 * 保证电台不会停在那里。不缓存，本来就是流。
 */
class RadioRepository(
    private val topSong: (Long) -> Result<Song> = { NcmBridge.weeklyTopSong(it) },
    private val simi: (Long, Int) -> Result<List<Song>> = { id, count -> NcmBridge.songSimi(id, count) },
    private val fm: () -> Result<List<Song>> = { NcmBridge.personalFm() },
    private val artistCover: (Long) -> Result<String> = { NcmBridge.artistCover(it) },
) {

    /** 种子歌：近七天播放最多的一首。 */
    suspend fun seed(userId: Long): Result<Song> = withContext(Dispatchers.IO) { topSong(userId) }

    /**
     * 下一批：以**队尾那首**当种子链式要几首。
     *
     * [played] 是已经入队的所有歌 id —— 相似链会大量重复，剔掉之后才是真正能插的新歌。
     * 全撞完时回落到私人 FM。
     */
    suspend fun next(after: Song, played: Set<Long>): Result<List<Song>> =
        withContext(Dispatchers.IO) {
            val fresh = simi(after.id, BatchCount).getOrNull().orEmpty()
                .filter { it.id > 0 && it.id !in played }
            if (fresh.isNotEmpty()) return@withContext Result.success(fresh)
            fm()
        }

    /**
     * 起步：种子 → 沿相似链要 [OpeningCount] 首。
     *
     * 相似链一首都没要到（接口抽风 / 这首歌没相似）时才用私人 FM 兜底 ——
     * 有链就优先用链，那才是"从这首歌出发"。
     */
    suspend fun opening(userId: Long): Result<RadioOpening> = withContext(Dispatchers.IO) {
        val seed = topSong(userId).getOrElse { return@withContext Result.failure(it) }

        val songs = mutableListOf<Song>()
        val seen = mutableSetOf(seed.id)
        simi(seed.id, OpeningCount).getOrNull().orEmpty().forEach { song ->
            if (song.id > 0 && seen.add(song.id)) songs += song
        }

        // 相似链是一路走窄的，很可能连着好几首都是同一位艺人；卡片要三张不同的脸，
        // 不够就沿链尾再要一小批（只补一次，别把首页拖太久）。
        if (songs.isEmpty() || distinctArtists(seed, songs) < CoverCount) {
            val tail = songs.lastOrNull() ?: seed
            simi(tail.id, BatchCount).getOrNull().orEmpty().forEach { song ->
                if (song.id > 0 && seen.add(song.id)) songs += song
            }
        }

        if (songs.isEmpty()) {
            fm().getOrNull().orEmpty().forEach { song ->
                if (song.id > 0 && seen.add(song.id)) songs += song
            }
        }

        if (songs.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("电台暂时没有可播的歌"))
        }
        Result.success(RadioOpening(seed, songs, guestCovers(seed, songs)))
    }

    /**
     * 第二、三张圆图用的艺人头像。
     *
     * 种子艺人之外按队列顺序挑两位不同的（同一位只出现一次）；头像接口一次一个、
     * 没有批量版，两位并发拉。某位上游没有头像（`artistCover` 返空串）时退回他这首歌的封面。
     */
    private suspend fun guestCovers(seed: Song, songs: List<Song>): List<String> = coroutineScope {
        val taken = mutableSetOf(seed.artistIds.firstOrNull() ?: 0L)
        val guests = mutableListOf<Pair<Long, String>>()
        for (song in songs) {
            val artistId = song.artistIds.firstOrNull() ?: 0L
            if (artistId <= 0 || !taken.add(artistId)) continue
            guests += artistId to song.cover
            if (guests.size >= GuestCount) break
        }
        guests.map { (artistId, fallback) ->
            async {
                artistCover(artistId).getOrNull().orEmpty().ifEmpty { fallback }
            }
        }.awaitAll().filter { it.isNotEmpty() }
    }
}

/** 种子 + 这批歌一共覆盖了几位不同的艺人（卡片三张图要的就是这个数）。 */
private fun distinctArtists(seed: Song, songs: List<Song>): Int =
    (listOf(seed) + songs).map { it.artistKey() }.distinct().size
