package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.LibraryContent
import com.nevoit.cirro.data.model.LibrarySnapshot
import com.nevoit.cirro.data.model.cachedSongs
import com.nevoit.cirro.data.model.libraryArtists
import com.nevoit.cirro.data.model.recentAlbums

// 由仓库的单个后台 collector 使用；排序器不在并发任务间共享。
internal class LibraryContentCache {
    private var source: LibrarySnapshot? = null
    private var content: LibraryContent? = null

    fun prepare(snapshot: LibrarySnapshot?): LibraryContent? {
        if (snapshot == null) {
            source = null
            content = null
            return null
        }
        val before = source?.takeIf { it.userId == snapshot.userId }
        val previous = content?.takeIf { it.userId == snapshot.userId }
        val sameLikes = before?.likedSongs == snapshot.likedSongs
        val sameAlbums = before?.albums == snapshot.albums
        val albumIds = if (previous != null && sameAlbums) previous.albumIds
        else snapshot.albums.map { it.info.id }
        val prepared = LibraryContent(
            userId = snapshot.userId,
            playlists = if (previous != null && before?.playlists == snapshot.playlists) previous.playlists
            else snapshot.playlists.sortedByLibraryName { it.info.title },
            artists = if (previous != null && sameLikes && sameAlbums &&
                before.artists == snapshot.artists &&
                before.artistCovers == snapshot.artistCovers
            ) previous.artists else snapshot.libraryArtists().sortedByLibraryName { it.info.title },
            albums = if (previous != null && sameLikes && sameAlbums && before.likedTracks == snapshot.likedTracks)
                previous.albums else snapshot.recentAlbums().sortedByLibraryName { it.info.title },
            songs = if (previous != null && sameLikes && previous.albumIds == albumIds &&
                before.albumSongs == snapshot.albumSongs
            ) previous.songs else snapshot.cachedSongs().sortedByLibraryName { it.name },
            albumIds = albumIds,
        )
        source = snapshot
        content = prepared
        return prepared
    }
}
