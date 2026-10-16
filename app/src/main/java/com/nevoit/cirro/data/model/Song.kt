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
    /**
     * 上游说这首歌**还能不能播**：`privileges` 里下架（`st < 0`）或一个可播档位都没有（`pl == 0`）。
     *
     * 🔴 判据**不是** [durationMs] == 0：有版权的歌照样可能不给时长。实测 Phaxe/Morten Granau
     * 的「Lost」（id 485964202）`dt=0` 但 `pl=999000`，直链正常；而下架的「月亮之子」
     * `st=-200`/`pl=0`，`song_url` 回 404。
     *
     * 默认 `true`：拿不到 `privileges` 的来源（每日推荐、首页、以及**旧缓存**）一律按能播，
     * 宁可漏灰也不要误灰。
     */
    val playable: Boolean = true,
) {
    val artistText: String get() = artists.joinToString(" & ")
}
