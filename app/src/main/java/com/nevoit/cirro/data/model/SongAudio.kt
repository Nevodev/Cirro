package com.nevoit.cirro.data.model

/**
 * 一首歌的可播放音频。
 *
 * [expiresInSeconds] 是网易云给的直链有效期（响应里的 `expi`，实测 1200 秒）。
 * 这个值很短，所以直链不能跟着队列囤着——队列里存 songId，临播放前才换。
 *
 * [gainDb] 与 [peak] 是网易云自己算好的响度数据，供客户端做响度归一：
 * - [gainDb]：相对基准响度的偏移量，单位 dB。**有正值**（实测最高 +14.75），
 *   意思是"这首歌比基准轻，要提上来"，所以它不是衰减系数。
 * - [peak]：线性峰值（0..1）。用它算提升上限——超过 `1/peak` 就会削波。
 *
 * 两者都可能是 null（网易云没给这首歌算过），此时按"不做处理"走。
 */
data class SongAudio(
    val url: String,
    val level: String,
    val type: String,
    val sizeBytes: Long,
    val expiresInSeconds: Long,
    val gainDb: Double? = null,
    val peak: Double? = null,
)
