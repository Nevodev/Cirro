package com.nevoit.cirro.detail

import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.Song

internal fun recommendationSeed(songs: List<Song>): Long? =
    songs.firstOrNull { it.id > 0 }?.id

internal fun otherArtistAlbums(albums: List<CollectionInfo>, albumId: Long): List<CollectionInfo> =
    albums.filter { it.id > 0 && it.id != albumId }.distinctBy { it.id }.take(12)

internal fun albumSimilarSongs(
    recommendations: List<Song>,
    albumId: Long,
    tracks: List<Song>,
): List<Song> {
    val trackIds = tracks.mapTo(HashSet()) { it.id }
    return recommendations.filter { it.id > 0 && it.id !in trackIds && it.albumId != albumId }
        .distinctBy { it.id }.take(10)
}

internal fun recommendationAlbums(songs: List<Song>): List<CollectionInfo> =
    songs.filter { it.albumId > 0 }.distinctBy { it.albumId }.map { song ->
        CollectionInfo(
            kind = CollectionKind.ALBUM,
            id = song.albumId,
            title = song.album,
            author = song.artistText,
            cover = song.cover,
        )
    }
