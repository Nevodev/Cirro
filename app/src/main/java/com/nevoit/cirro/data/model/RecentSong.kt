package com.nevoit.cirro.data.model

import kotlinx.serialization.Serializable

/**
 * 「最近播放」里的一条**单曲**记录（`/api/play-record/song/list`）。
 *
 * 原生层已把嵌套拍平（这个接口的歌是短键 `al`/`ar` 格式）：[album] 是**专辑名**、
 * [cover] 是专辑封面、[artists] 是拆开的歌手名列表、[playTime] 是播放时间（毫秒）。
 *
 * 同一首歌在流里只出现最近一次（服务端按最后播放倒序），但同一张**专辑**会有多条
 * ——听了几首就有几条。按专辑归并成 [RecentAlbum] 是仓库那侧的事（见
 * `HomeRepository.recentAlbums`），这个模型只负责如实转述。
 */
@Serializable
data class RecentSong(
    val albumId: Long = 0L,
    val album: String = "",
    val cover: String = "",
    val artists: List<String> = emptyList(),
    val playTime: Long = 0L,
)
