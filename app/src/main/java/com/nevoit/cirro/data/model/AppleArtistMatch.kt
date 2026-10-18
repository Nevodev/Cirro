package com.nevoit.cirro.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AppleArtistMatch(
    val appleArtistId: String,
    val storefront: String,
    val songs: List<AppleArtistSongMatch>,
)

@Serializable
data class AppleArtistSongMatch(
    val neteaseSongId: Long,
    val appleSongId: String,
)
