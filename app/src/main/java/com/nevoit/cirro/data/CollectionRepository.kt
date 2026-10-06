package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** 每日推荐在界面上叫什么。它没有接口来源，跟首页入口卡同一个写法。 */
private const val DailyTitle = "每日推荐"

/**
 * 统一详情页的内容仓库：专辑、单曲专辑、歌单、每日推荐共用一套入口。
 *
 * 桥接层是同步阻塞的（Rust 侧 `block_on`），所有方法都切到 IO。
 *
 * **进程内缓存**：详情信息按 `kind:id` 收在一张表里——从详情页返回再进来（同一天里）
 * 就是零请求瞬显；收藏状态切换成功后**立刻回写缓存**，再进来不会闪回旧状态。
 * 曲目不缓存（歌单内容会变），只有专辑的歌例外——它们跟着详情一起回来，
 * 顺手留在 [albumSongs] 里，同一次会话内的第二次取就是白拿。
 */
class CollectionRepository(
    private val home: HomeRepository,
) {

    private val infoCache = ConcurrentHashMap<String, CollectionInfo>()

    /** 专辑详情捎回来的歌，按专辑 id 分槽（专辑内容不变，缓存到换号为止）。 */
    private val albumSongs = ConcurrentHashMap<Long, List<Song>>()

    /** 详情头部信息。每日推荐的 id 恒为 0，缓存键天然只有一份。 */
    suspend fun info(kind: CollectionKind, id: Long): Result<CollectionInfo> =
        withContext(Dispatchers.IO) {
            val key = "$kind:$id"
            infoCache[key]?.let { return@withContext Result.success(it) }

            val result = when (kind) {
                CollectionKind.PLAYLIST -> NcmBridge.playlistDetail(id)
                CollectionKind.ALBUM -> NcmBridge.albumDetail(id).map { it.info }
                CollectionKind.DAILY -> dailyInfo()
            }
            result.onSuccess { infoCache[key] = it }
        }

    /** 详情页的曲目。**必须在 [info] 之后调**——专辑的歌来自详情那次响应。 */
    suspend fun tracks(kind: CollectionKind, id: Long): Result<List<Song>> =
        withContext(Dispatchers.IO) {
            when (kind) {
                // 歌单曲目走首页仓库的同一条缓存管道（当天内有效），别另起炉灶
                CollectionKind.PLAYLIST -> home.playlistTracks(id, limit = 1000)
                CollectionKind.ALBUM ->
                    albumSongs[id]?.let { Result.success(it) }
                        ?: NcmBridge.albumDetail(id).onSuccess { albumSongs[id] = it.songs }
                            .map { it.songs }
                CollectionKind.DAILY -> home.dailySongs()
            }
        }

    /**
     * 收藏 / 取消收藏。成功后把缓存里的收藏状态一并改掉——界面上按钮立即翻面，
     * 下次进来也不会看到旧状态闪一下。
     */
    suspend fun setSubscribed(
        kind: CollectionKind,
        id: Long,
        subscribe: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val result = NcmBridge.setCollectionSubscribed(kind, id, subscribe)
        result.onSuccess {
            val key = "$kind:$id"
            infoCache[key]?.let { infoCache[key] = it.copy(subscribed = subscribe) }
        }
    }

    /**
     * 每日推荐的头部信息：现拼的——标题写死，作者位放日期，封面借首曲专辑图。
     * 每日推荐不可收藏（没有可收藏的实体），[CollectionInfo.subscribed] 恒 false。
     */
    private suspend fun dailyInfo(): Result<CollectionInfo> = home.dailySongs().map { songs ->
        val today = LocalDate.now()
        val weekday = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
        CollectionInfo(
            kind = CollectionKind.DAILY,
            id = 0L,
            title = DailyTitle,
            author = "${today.monthValue}月${today.dayOfMonth}日 $weekday",
            cover = songs.firstOrNull()?.cover.orEmpty(),
            trackCount = songs.size,
        )
    }
}
