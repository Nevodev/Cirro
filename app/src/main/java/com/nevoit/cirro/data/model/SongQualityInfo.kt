package com.nevoit.cirro.data.model

/**
 * 一首歌**拥有**哪些音质档，来自 `/api/song/music/detail/get`。
 *
 * ⚠️ **有这一档 ≠ 能播这一档**：实测 `vivid` 的字段恒有值，但请求直链仍回 `code=-110`
 * （这一档没权益）。所以这份数据只够用来**灰化列表 / 显示体积**，最终能不能播一律以直链
 * 响应为准。
 *
 * [sizes] 缺的键就是这首歌没有这一档，[supports] 直接问它。
 */
data class SongQualityInfo(
    val songId: Long,
    val sizes: Map<AudioQuality, Long>,
) {
    fun supports(quality: AudioQuality): Boolean = quality in sizes

    companion object {
        /** 查不到时的空壳：所有档都当"不支持"，界面据此全灰但仍可点。 */
        val Empty = SongQualityInfo(0L, emptyMap())
    }
}
