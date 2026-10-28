package com.nevoit.cirro.lyrics.data

import kotlinx.serialization.Serializable

@Serializable
data class AppleAlbum(
    val id: String = "",
    val name: String = "",
    val artist: String = "",
    val trackCount: Int = 0,
    val releaseDate: String = "",
    val videoUrl: String? = null,
    val tracks: List<AppleAlbumTrack> = emptyList(),
)

@Serializable
data class AppleAlbumTrack(val name: String = "", val durationMs: Long = 0)

data class AppleAlbumSearchResult(val albums: List<AppleAlbum>, val storefront: String)
