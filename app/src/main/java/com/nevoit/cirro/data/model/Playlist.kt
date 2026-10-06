package com.nevoit.cirro.data.model

import kotlinx.serialization.Serializable

/**
 * 歌单（首页「每日推荐歌单」用）。
 *
 * [id] 必须是 Long —— 歌单 id 同样会超过 Int 上限（实测 `17715824860`），
 * 任何一环退回 Int 都会静默截断成另一个歌单。
 */
@Serializable
data class Playlist(
    val id: Long,
    val name: String = "",
    val cover: String = "",
    val playCount: Long = 0L,
    val trackCount: Int = 0,
    val creator: String = "",
)
