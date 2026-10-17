package com.nevoit.cirro.data.model

import kotlinx.serialization.Serializable

/**
 * 统一详情页的内容来源。
 *
 * 专辑（含 Single / EP）和歌单各走各的详情接口，每日推荐没有 id（它不是一份真歌单，
 * 是 [HomeRepository.dailySongs] 现算出来的列表），用 [id]==0 占位。
 */
@Serializable
enum class CollectionKind {
    PLAYLIST,
    ALBUM,
    DAILY,
    ARTIST,
}

/**
 * 统一详情页的头部信息：专辑、歌单、每日推荐、艺术家热门歌曲共用一份形状。
 *
 * 字段是来源的**并集**，谁没有就留默认值：歌单给 [updateTimeMs]（更新时间），
 * 专辑给的是发行时间（语义不同，界面按 [kind] 换文案）；每日推荐没有作者和描述。
 * [subscribed] 是当前登录用户的收藏状态；每日推荐不可收藏，恒 false。
 *
 * 🔴 [id] 必须是 Long —— 歌单/专辑 id 都会超过 Int 上限。
 */
@Serializable
data class CollectionInfo(
    val kind: CollectionKind,
    val id: Long,
    val title: String = "",
    val author: String = "",
    val description: String = "",
    val cover: String = "",
    val updateTimeMs: Long = 0L,
    val trackCount: Int = 0,
    val playCount: Long = 0L,
    val subscribed: Boolean = false,
    /** 版权/发行公司。仅专辑有，老专辑可能给空串；歌单/每日推荐为空。 */
    val company: String = "",
    /** 专辑类型："专辑" / "Single" / "EP"……仅专辑有。 */
    val albumType: String = "",
    val primaryArtistId: Long = 0L,
    val primaryArtistName: String = "",
)

/**
 * 详情页一次加载的全部内容：头部信息 + 曲目。
 *
 * 专辑的歌直接跟着专辑详情一起回来（接口本来就在一个响应里）；歌单另走一步曲目请求，
 * 所以 [songs] 可能单独失败——调用方按两个 Section 分别摆。
 */
data class CollectionDetail(
    val info: CollectionInfo,
    val songs: List<Song>,
)

fun albumDisplayTitle(title: String, albumType: String?): String =
    if (title.isNotEmpty() && albumType.equals("Single", ignoreCase = true) &&
        !title.endsWith(" - Single", ignoreCase = true)
    ) "$title - Single" else title
