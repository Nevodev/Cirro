package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.LibraryCollection
import com.nevoit.cirro.data.model.LibrarySnapshot
import com.nevoit.cirro.data.model.LibraryTrack
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.recentAlbums
import com.nevoit.cirro.data.model.tracksForAlbum
import com.nevoit.cirro.data.model.libraryArtists
import com.nevoit.cirro.data.model.cachedSongs
import com.nevoit.cirro.data.model.forSession
import com.nevoit.cirro.data.model.likedPlaylistInfo
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.UserProfile
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LibrarySnapshotTest {
    private fun song(id: Long, albumId: Long) = Song(id, album = "Album $albumId", albumId = albumId)
    private fun collection(id: Long, kind: CollectionKind, time: Long) = LibraryCollection(
        CollectionInfo(kind, id, title = "Collection $id", subscribed = true), time,
    )

    @Test
    fun recentGroupsLikesAndDeduplicatesSubscribedAlbumsUsingTheNewestAddition() {
        val library = LibrarySnapshot(
            userId = 42,
            likedSongs = listOf(song(1, 10), song(2, 10), song(3, 20)),
            likedTracks = listOf(LibraryTrack(1, 100), LibraryTrack(2, 300), LibraryTrack(3, 200)),
            albums = listOf(collection(10, CollectionKind.ALBUM, 150), collection(30, CollectionKind.ALBUM, 250)),
        )
        val recent = library.recentAlbums()
        assertEquals(listOf(10L, 30L, 20L), recent.map { it.info.id })
        assertEquals(300L, recent.first().addedAtMs)
        assertTrue(recent.first().info.subscribed)
        assertEquals(1, recent.last().info.trackCount)
    }

    @Test
    fun playlistsNeverContributeRecentEntries() {
        val library = LibrarySnapshot(
            userId = 42,
            playlists = listOf(collection(99, CollectionKind.PLAYLIST, 999)),
            likedSongs = listOf(song(1, 10)),
        )
        assertEquals(listOf(10L), library.recentAlbums().map { it.info.id })
        assertTrue(library.copy(likedSongs = emptyList()).recentAlbums().isEmpty())
    }

    @Test
    fun removingTheLastLikeKeepsOnlyAnExplicitlySubscribedAlbum() {
        val library = LibrarySnapshot(
            userId = 42,
            likedSongs = listOf(song(1, 10), song(2, 20)),
            albums = listOf(collection(10, CollectionKind.ALBUM, 100)),
        )
        val after = library.copy(likedSongs = emptyList(), likedTracks = emptyList())
        assertEquals(listOf(10L), after.recentAlbums().map { it.info.id })
        assertTrue(after.copy(albums = emptyList()).recentAlbums().isEmpty())
    }

    @Test
    fun missingAlbumIdsAreNotMergedIntoAFakeAlbumAndUnknownTimesKeepSourceOrder() {
        val library = LibrarySnapshot(userId = 42, likedSongs = listOf(song(1, 0), song(2, 20), song(3, 10)))
        assertEquals(listOf(20L, 10L), library.recentAlbums().map { it.info.id })
    }

    @Test
    fun likedAlbumPlaysOnlyLikedTracksUntilTheWholeAlbumIsSubscribed() {
        val songs = listOf(song(1, 10), song(2, 10))
        val library = LibrarySnapshot(userId = 42, likedSongs = songs.take(1), likedTracks = listOf(LibraryTrack(1)))
        assertEquals(listOf(1L), library.tracksForAlbum(10, songs).map { it.id })
        assertEquals(songs, library.copy(albums = listOf(collection(10, CollectionKind.ALBUM, 0)))
            .tracksForAlbum(10, songs))
    }

    @Test
    fun artistsIncludeLibraryContributorsAndKeepFollowedMetadata() {
        val followed = LibraryCollection(CollectionInfo(CollectionKind.ARTIST, 50, title = "Artist", cover = "portrait"))
        val library = LibrarySnapshot(
            userId = 42,
            likedSongs = listOf(song(1, 10).copy(artistIds = listOf(50, 0), artists = listOf("Artist", "Unknown"))),
            albums = listOf(collection(10, CollectionKind.ALBUM, 0).copy(artistIds = listOf(60), artistNames = listOf("Other"))),
            artists = listOf(followed),
        )
        assertEquals(listOf(50L, 60L), library.libraryArtists().map { it.info.id })
        assertEquals("portrait", library.libraryArtists().first().info.cover)
    }

    @Test
    fun restoredSongCacheIncludesSubscribedAlbumsAndDeduplicatesLikes() {
        val library = LibrarySnapshot(
            userId = 42,
            likedSongs = listOf(song(1, 10)),
            albums = listOf(collection(10, CollectionKind.ALBUM, 0)),
            albumSongs = mapOf(10L to listOf(song(1, 10), song(2, 10)), 20L to listOf(song(3, 20))),
        )
        val restored = Json.decodeFromString<LibrarySnapshot>(Json.encodeToString(library))
        assertEquals(listOf(1L, 2L), restored.cachedSongs().map { it.id })
        assertEquals(listOf(1L), restored.copy(albums = emptyList()).cachedSongs().map { it.id })
        assertEquals(listOf(1L, 2L), restored.copy(likedSongs = emptyList()).cachedSongs().map { it.id })
    }

    @Test
    fun existingDiskSnapshotsWithoutAlbumTracksRemainReadable() {
        val restored = Json.decodeFromString<LibrarySnapshot>("""{"userId":42,"likesLoaded":true}""")
        assertTrue(restored.albumSongs.isEmpty())
        assertTrue(restored.cachedSongs().isEmpty())
        assertTrue(restored.likesLoaded)
    }

    @Test
    fun startupCacheRemainsVisibleUntilAccountIsKnownAndNeverCrossesAccounts() {
        val library = LibrarySnapshot(userId = 42)
        assertEquals(library, library.forSession(Session.Unknown))
        assertEquals(library, library.forSession(Session.Offline))
        assertEquals(library, library.forSession(Session.LoggedIn(UserProfile(userId = 42))))
        assertNull(library.forSession(Session.LoggedIn(UserProfile(userId = 99))))
        assertNull(library.forSession(Session.LoggedIn(UserProfile())))
        assertNull(library.forSession(Session.LoggedOut))
    }

    @Test
    fun likedPlaylistHeaderUsesNewestAdditionRatherThanServerUpdateTime() {
        val library = LibrarySnapshot(
            userId = 42, likedPlaylistId = 99,
            likedSongs = listOf(song(1, 10).copy(cover = "song-cover"), song(2, 20)),
            likedTracks = listOf(LibraryTrack(1, 300), LibraryTrack(2, 100)),
            likesLoaded = true,
        )
        val server = CollectionInfo(CollectionKind.PLAYLIST, 99, title = "我喜欢的音乐",
            author = "Old name", cover = "playlist-cover", updateTimeMs = 999, trackCount = 1)
        val info = library.likedPlaylistInfo("Current name", server)
        assertEquals("喜爱歌曲", info.title)
        assertEquals("Current name", info.author)
        assertEquals(300L, info.updateTimeMs)
        assertEquals("playlist-cover", info.cover)
        assertEquals(2, info.trackCount)
        assertFalse(info.subscribed)
        assertEquals(100L, library.copy(likedTracks = library.likedTracks.drop(1))
            .likedPlaylistInfo("Current name", server).updateTimeMs)
    }

    @Test
    fun likedPlaylistDoesNotTreatUnknownHistoricOrderingAsAnAdditionDate() {
        val library = LibrarySnapshot(userId = 42, likedPlaylistId = 99,
            likedTracks = listOf(LibraryTrack(1), LibraryTrack(2, -1)))
        val server = CollectionInfo(CollectionKind.PLAYLIST, 99, author = "Name", updateTimeMs = 999)
        val info = library.likedPlaylistInfo("", server)
        assertEquals(0L, info.updateTimeMs)
        assertEquals("Name", info.author)
        assertEquals(0L, library.copy(likedTracks = emptyList()).likedPlaylistInfo("Name").updateTimeMs)
    }

    @Test
    fun cachedLikedPlaylistIsNotLimitedToOneThousandSongs() {
        val library = LibrarySnapshot(userId = 42, likedPlaylistId = 99, likesLoaded = true,
            likedSongs = (1L..1001L).map { song(it, 10) })
        val info = library.likedPlaylistInfo("Name")
        assertEquals(99L, info.id)
        assertEquals(CollectionKind.PLAYLIST, info.kind)
        assertEquals(1001, info.trackCount)
    }
}
