package com.nevoit.cirro.data.model

/**
 * 一首歌的可播放音频。
 *
 * [expiresInSeconds] 是网易云给的直链有效期（响应里的 `expi`，实测 1200 秒）。
 * 这个值很短，所以直链不能跟着队列囤着——队列里存 songId，临播放前才换。
 */
data class SongAudio(
    val url: String,
    val level: String,
    val type: String,
    val sizeBytes: Long,
    val expiresInSeconds: Long,
)
