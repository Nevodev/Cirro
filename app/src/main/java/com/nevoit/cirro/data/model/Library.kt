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
    /**
     * 艺人头像：艺人 id → 图 URL。
     *
     * 🔴 艺人**静态元数据**（跟专辑类型一个性质），查一次终身有效，所以跟快照一起落盘。
     * 空串是**查过了、上游没有图**，别当成"还没查"——否则没头像的艺人每次刷新都会被再问一遍。
     */
    val artistCovers: Map<Long, String> = emptyMap(),
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
        // 头图固定跟第一首歌走：远程歌单详情后到，若让它优先会当场换图。
        cover = likedSongs.firstOrNull()?.cover?.takeIf { it.isNotEmpty() }
            ?: fallback?.cover.orEmpty(),
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
                // 头像来自补图缓存（见 [artistCovers]）：这批艺人是从歌曲/专辑里推出来的，
                // 订阅接口没给过他们的图。
                CollectionInfo(CollectionKind.ARTIST, id, title = name, cover = artistCovers[id].orEmpty()),
            ))
        }
    }
    likedSongs.forEach { add(it.artistIds, it.artists) }
    albums.forEach { add(it.artistIds, it.artistNames) }
    // 订阅艺人自带 `artist_sublist` 的图，优先用它；没有才退回补图缓存
    artists.forEach { artist ->
        result[artist.info.id] = artist.copy(
            info = artist.info.copy(
                cover = artist.info.cover.ifEmpty { artistCovers[artist.info.id].orEmpty() },
            ),
        )
    }
    return result.values.toList()
}

/**
 * 「全部艺人」里还缺头像、且**没查过**的那批 id。
 *
 * 🔴 判据是 `id !in artistCovers`，不是 `cover.isBlank()`：空串代表"查过了，上游没图"。
 * 两者混为一谈会让没头像的艺人永远留在待办里，每次刷新重查一遍。
 */
fun LibrarySnapshot.artistsMissingCover(): List<Long> =
    libraryArtists().filter { it.info.cover.isBlank() }.map { it.info.id }.filterNot { it in artistCovers }
