package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.LibraryCollection
import com.nevoit.cirro.data.model.LibraryContent
import com.nevoit.cirro.data.model.LibrarySnapshot
import com.nevoit.cirro.data.model.LibraryTrack
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.UserProfile
import com.nevoit.cirro.data.model.forSession
import org.junit.Assert.*
import org.junit.Test

class LibraryContentCacheTest {
    private fun collection(kind: CollectionKind, id: Long, title: String) = LibraryCollection(
        CollectionInfo(kind, id, title = title),
    )

    private fun snapshot() = LibrarySnapshot(
        userId = 42,
        likedSongs = listOf(
            Song(1, name = "中国", albumId = 10, album = "中国", artistIds = listOf(100), artists = listOf("中国")),
            Song(2, name = "Adele", albumId = 20, album = "Adele", artistIds = listOf(200), artists = listOf("Adele")),
        ),
        likedTracks = listOf(LibraryTrack(1, 10), LibraryTrack(2, 20)),
        playlists = listOf(
            collection(CollectionKind.PLAYLIST, 1, "中国"), collection(CollectionKind.PLAYLIST, 2, "Adele"),
        ),
        albums = listOf(collection(CollectionKind.ALBUM, 10, "中国")),
        albumSongs = mapOf(10L to listOf(Song(1, "Duplicate"), Song(3, "北京"))),
    )

    private fun assertListsSame(first: LibraryContent, second: LibraryContent) {
        assertSame(first.playlists, second.playlists)
        assertSame(first.artists, second.artists)
        assertSame(first.albums, second.albums)
        assertSame(first.songs, second.songs)
        assertSame(first.albumIds, second.albumIds)
    }

    @Test
    fun preparedListsKeepPinyinSortingAndDeduplicateSongSources() {
        val prepared = LibraryContentCache().prepare(snapshot())!!
        assertEquals(listOf(2L, 1L), prepared.playlists.map { it.info.id })
        assertEquals(listOf(200L, 100L), prepared.artists.map { it.info.id })
        assertEquals(listOf(20L, 10L), prepared.albums.map { it.info.id })
        assertEquals(listOf(2L, 3L, 1L), prepared.songs.map { it.id })
        assertEquals("中国", prepared.songs.last().name)
        assertEquals(listOf(10L), prepared.albumIds)
    }

    @Test
    fun loadingFlagsAndLikedPlaylistMetadataReuseAllPreparedLists() {
        val cache = LibraryContentCache()
        val source = snapshot()
        val first = cache.prepare(source)!!
        val second = cache.prepare(source.copy(likesLoaded = true, albumsLoaded = true, likedPlaylistId = 99))!!
        assertListsSame(first, second)
    }

    @Test
    fun playlistRefreshDoesNotRebuildArtistsAlbumsOrSongs() {
        val cache = LibraryContentCache()
        val source = snapshot()
        val first = cache.prepare(source)!!
        val second = cache.prepare(source.copy(playlists = listOf(collection(CollectionKind.PLAYLIST, 3, "北京"))))!!
        assertEquals(listOf(3L), second.playlists.map { it.info.id })
        assertSame(first.artists, second.artists)
        assertSame(first.albums, second.albums)
        assertSame(first.songs, second.songs)
    }

    @Test
    fun followedArtistRefreshDoesNotRebuildTheOtherCategories() {
        val cache = LibraryContentCache()
        val source = snapshot()
        val first = cache.prepare(source)!!
        val second = cache.prepare(source.copy(artists = listOf(collection(CollectionKind.ARTIST, 300, "北京"))))!!
        assertEquals(listOf(200L, 300L, 100L), second.artists.map { it.info.id })
        assertSame(first.playlists, second.playlists)
        assertSame(first.albums, second.albums)
        assertSame(first.songs, second.songs)
    }

    @Test
    fun newLikeUpdatesItsSongAlbumAndArtistWithoutRebuildingPlaylists() {
        val cache = LibraryContentCache()
        val source = snapshot()
        val first = cache.prepare(source)!!
        val song = Song(4, "安静", albumId = 30, album = "安静", artistIds = listOf(300), artists = listOf("安静"))
        val second = cache.prepare(source.copy(
            likedSongs = source.likedSongs + song,
            likedTracks = source.likedTracks + LibraryTrack(4, 30),
        ))!!
        assertEquals(listOf(2L, 4L, 3L, 1L), second.songs.map { it.id })
        assertEquals(listOf(20L, 30L, 10L), second.albums.map { it.info.id })
        assertEquals(listOf(200L, 300L, 100L), second.artists.map { it.info.id })
        assertSame(first.playlists, second.playlists)
    }

    @Test
    fun albumTrackLoadingOnlyRebuildsSongs() {
        val cache = LibraryContentCache()
        val source = snapshot()
        val first = cache.prepare(source)!!
        val second = cache.prepare(source.copy(albumSongs = mapOf(10L to listOf(Song(4, "安静")))))!!
        assertEquals(listOf(2L, 4L, 1L), second.songs.map { it.id })
        assertSame(first.playlists, second.playlists)
        assertSame(first.artists, second.artists)
        assertSame(first.albums, second.albums)
        assertSame(first.albumIds, second.albumIds)
    }

    @Test
    fun additionDatesOnlyRebuildAlbumMetadata() {
        val cache = LibraryContentCache()
        val source = snapshot()
        val first = cache.prepare(source)!!
        val second = cache.prepare(source.copy(likedTracks = listOf(LibraryTrack(1, 100), LibraryTrack(2, 20))))!!
        assertEquals(100L, second.albums.last().addedAtMs)
        assertSame(first.playlists, second.playlists)
        assertSame(first.artists, second.artists)
        assertSame(first.songs, second.songs)
    }

    @Test
    fun albumMetadataRefreshDoesNotRebuildSongsButUnsubscribingRemovesItsTracks() {
        val cache = LibraryContentCache()
        val source = snapshot()
        val first = cache.prepare(source)!!
        val second = cache.prepare(source.copy(albums = source.albums.map {
            it.copy(info = it.info.copy(cover = "new-cover"))
        }))!!
        assertEquals("new-cover", second.albums.last().info.cover)
        assertSame(first.songs, second.songs)
        val removed = cache.prepare(source.copy(albums = emptyList()))!!
        assertEquals(listOf(2L, 1L), removed.songs.map { it.id })
        assertTrue(removed.albumIds.isEmpty())
    }

    @Test
    fun accountChangesAndLogoutNeverKeepAnotherAccountsContent() {
        val cache = LibraryContentCache()
        val first = cache.prepare(snapshot())!!
        assertSame(first, first.forSession(Session.Unknown))
        assertSame(first, first.forSession(Session.Offline))
        assertSame(first, first.forSession(Session.LoggedIn(UserProfile(userId = 42))))
        assertNull(first.forSession(Session.LoggedIn(UserProfile(userId = 99))))
        assertNull(first.forSession(Session.LoggedOut))
        val second = cache.prepare(LibrarySnapshot(userId = 99, likedSongs = listOf(Song(99, "Other"))))!!
        assertEquals(99L, second.userId)
        assertEquals(listOf(99L), second.songs.map { it.id })
        assertTrue(second.playlists.isEmpty())
        assertTrue(second.artists.isEmpty())
        assertTrue(second.albums.isEmpty())
        assertNull(cache.prepare(null))
        val restored = cache.prepare(snapshot())!!
        assertEquals(first, restored)
        assertNotSame(first.songs, restored.songs)
    }

    @Test
    fun albumCategoryKeepsMoreThanSixtyFourEntries() {
        val prepared = LibraryContentCache().prepare(LibrarySnapshot(
            userId = 42,
            albums = (1L..70L).map { collection(CollectionKind.ALBUM, it, "Album $it") },
        ))!!
        assertEquals(70, prepared.albums.size)
    }
}
