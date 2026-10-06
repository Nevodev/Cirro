package com.nevoit.cirro.data.model

import kotlinx.serialization.Serializable

/**
 * 「最近播放」里的一张**专辑**。
 *
 * 🔴 来源是**单曲**最近播放（`/api/play-record/song/list`）按专辑归并的结果，见
 * `HomeRepository.recentAlbums`——不是专辑维度（`/api/play-record/album/list`），
 * 那个只在官方客户端的专辑页点播放时才记账，Cirro 的播歌永远进不去。
 *
 * 字段名与网易云原始结构无关：原生层已经把 `al.name` / `al.picUrl` / `ar` 那套嵌套拍平了，
 * 上游改字段只需要改 `API/ncm-android/src/lib.rs`。
 *
 * [artists] 存的是**拆开的名字**，不是拼好的字符串 —— 跟 [Song.artists] 一个约定：
 * 拼成一行是排版的事，交给界面那侧（[artistText]）。
 *
 * [id] 是专辑 id（归并时顺手带来的），首页这张卡目前用不上，留给将来的专辑页跳转。
 */
@Serializable
data class RecentAlbum(
    val id: Long = 0L,
    val title: String = "",
    val cover: String = "",
    val artists: List<String> = emptyList(),
) {
    /** 卡片上那行歌手，多个用 `&` 隔开，跟歌曲行一致。 */
    val artistText: String get() = artists.joinToString(" & ")
}
