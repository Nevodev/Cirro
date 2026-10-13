package com.nevoit.cirro.data.model

import kotlinx.serialization.Serializable

@Serializable
data class LibraryCollection(
    val info: CollectionInfo,
    val addedAtMs: Long = 0,
    val artistIds: List<Long> = emptyList(),
    val artistNames: List<String> = emptyList(),
)

@Serializable
data class LibraryTrack(val id: Long, val addedAtMs: Long = 0)

@Serializable
data class LibrarySnapshot(
    val userId: Long,
    val likedPlaylistId: Long = 0,
    val likedSongs: List<Song> = emptyList(),
    val likedTracks: List<LibraryTrack> = emptyList(),
    val playlists: List<LibraryCollection> = emptyList(),
    val albums: List<LibraryCollection> = emptyList(),
    val artists: List<LibraryCollection> = emptyList(),
    val likesLoaded: Boolean = false,
    val albumsLoaded: Boolean = false,
    val albumSongs: Map<Long, List<Song>> = emptyMap(),
)

data class LibraryContent(
    val userId: Long,
    val playlists: List<LibraryCollection>,
    val artists: List<LibraryCollection>,
    val albums: List<LibraryCollection>,
    val songs: List<Song>,
    val albumIds: List<Long>,
)

fun LibraryContent.forSession(session: Session): LibraryContent? = when (session) {
    Session.Unknown, Session.Offline -> this
    is Session.LoggedIn -> takeIf { userId == session.profile.userId }
    Session.LoggedOut -> null
}

fun LibrarySnapshot.forSession(session: Session): LibrarySnapshot? = when (session) {
    Session.Unknown, Session.Offline -> this
    is Session.LoggedIn -> takeIf { userId == session.profile.userId }
    Session.LoggedOut -> null
}

fun LibrarySnapshot.cachedSongs(): List<Song> =
    (likedSongs + albums.flatMap { albumSongs[it.info.id].orEmpty() }).distinctBy { it.id }

fun LibrarySnapshot.likedPlaylistInfo(author: String, fallback: CollectionInfo? = null): CollectionInfo =
    (fallback ?: CollectionInfo(CollectionKind.PLAYLIST, likedPlaylistId)).copy(
        title = "喜爱歌曲",
        author = author.ifEmpty { fallback?.author.orEmpty() },
        cover = fallback?.cover?.takeIf { it.isNotEmpty() } ?: likedSongs.firstOrNull()?.cover.orEmpty(),
        updateTimeMs = likedTracks.maxOfOrNull { it.addedAtMs.coerceAtLeast(0) } ?: 0,
        trackCount = if (likesLoaded) likedSongs.size else fallback?.trackCount ?: 0,
        subscribed = false,
    )

fun LibrarySnapshot.recentAlbums(): List<LibraryCollection> {
    val times = likedTracks.associate { it.id to it.addedAtMs }
    val grouped = likedSongs.filter { it.albumId > 0 }.groupBy { it.albumId }
    val result = linkedMapOf<Long, LibraryCollection>()
    grouped.forEach { (id, songs) ->
        val first = songs.first()
        result[id] = LibraryCollection(
            CollectionInfo(
                kind = CollectionKind.ALBUM, id = id, title = first.album,
                author = songs.flatMap { it.artists }.distinct().joinToString(" & "),
                cover = first.cover, trackCount = songs.size,
            ),
            songs.maxOf { times[it.id] ?: 0 },
        )
    }
    albums.forEach { album ->
        val liked = result[album.info.id]
        result[album.info.id] = album.copy(addedAtMs = maxOf(album.addedAtMs, liked?.addedAtMs ?: 0))
    }
    return result.values.sortedByDescending { it.addedAtMs }
}

fun LibrarySnapshot.tracksForAlbum(albumId: Long, songs: List<Song>): List<Song> {
    if (albums.any { it.info.id == albumId }) return songs
    val liked = likedTracks.map { it.id }.toSet()
    return songs.filter { it.id in liked }
}

fun LibrarySnapshot.libraryArtists(): List<LibraryCollection> {
    val result = linkedMapOf<Long, LibraryCollection>()
    fun add(ids: List<Long>, names: List<String>) {
        ids.forEachIndexed { index, id ->
            val name = names.getOrNull(index).orEmpty()
            if (id > 0 && name.isNotBlank()) result.putIfAbsent(id, LibraryCollection(
                CollectionInfo(CollectionKind.ARTIST, id, title = name),
            ))
        }
    }
    likedSongs.forEach { add(it.artistIds, it.artists) }
    albums.forEach { add(it.artistIds, it.artistNames) }
    artists.forEach { result[it.info.id] = it }
    return result.values.toList()
}
