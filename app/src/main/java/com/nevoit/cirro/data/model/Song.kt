package com.nevoit.cirro.data.model

import kotlinx.serialization.Serializable

/**
 * 歌曲元数据。
 *
 * 字段名与网易云原始结构无关：原生层已经把 `ar` / `al` / `dt` 那套嵌套拍平了，
 * 上游改字段只需要改 `API/ncm-android/src/lib.rs`。
 *
 * [id] 必须是 Long —— 网易云的歌曲 id 已经超过 Int 上限（如 3402673449），
 * 任何一环退回 Int 都会静默截断成另一首歌。
 */
@Serializable
data class Song(
    val id: Long,
    val name: String = "",
    val artists: List<String> = emptyList(),
    val album: String = "",
    val cover: String = "",
    val durationMs: Long = 0,
    val fee: Int = 0,
    val albumId: Long = 0L,
    /** 与 [artists] 按下标对应；缺失 id 的艺术家以 0 占位，旧缓存可能整列缺失。 */
    val artistIds: List<Long> = emptyList(),
) {
    val artistText: String get() = artists.joinToString(" & ")
}
