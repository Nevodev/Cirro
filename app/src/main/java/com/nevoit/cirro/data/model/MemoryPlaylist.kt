package com.nevoit.cirro.data.model

import kotlinx.serialization.Serializable

@Serializable
data class MemoryPlaylist(
    val id: Long,
    val title: String,
    val cover: String,
    val coverText: String,
    val subtitle: String,
    val period: MemoryPeriod? = null,
)

@Serializable
data class MemoryPeriod(val year: Int, val month: Int? = null) {
    val number: String get() = month?.toString() ?: "'${year.toString().takeLast(2)}"
}

@Serializable
data class MemoryPlaylists(
    val title: String = "回忆歌单",
    val playlists: List<MemoryPlaylist> = emptyList(),
)
