package com.nevoit.cirro.data.model

import kotlinx.serialization.Serializable

/**
 * 排行榜条目（首页「排行榜」用）。
 *
 * [official] 为真才是官方 4 榜（飙升 / 新歌 / 原创 / 热歌）。上游 `toplist` 会返回 60 多条，
 * 大部分是地区榜和曲风榜，首页只挑官方的那几个展示。
 */
@Serializable
data class Ranking(
    val id: Long,
    val name: String = "",
    val cover: String = "",
    val updateFrequency: String = "",
    val trackCount: Int = 0,
    val official: Boolean = false,
)
