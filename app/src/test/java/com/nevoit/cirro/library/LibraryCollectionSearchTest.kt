package com.nevoit.cirro.library

import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.LibraryCollection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCollectionSearchTest {
    @Test
    fun albumsMatchTitlesAuthorsAndContributingArtists() = runBlocking {
        val albums = listOf(
            collection(CollectionKind.ALBUM, 1, "叶惠美", "周杰伦"),
            collection(CollectionKind.ALBUM, 2, "Parachutes", "Coldplay"),
            collection(CollectionKind.ALBUM, 3, "Collaborations").copy(artistNames = listOf("Guest Artist")),
        )
        val index = LibraryCollectionSearchIndex.build(albums)
        assertEquals(listOf(albums[0]), index.find("叶惠美"))
        assertEquals(listOf(albums[0]), index.find("周杰伦"))
        assertEquals(listOf(albums[1]), index.find("PARACHUTES coldPLAY"))
        assertEquals(listOf(albums[2]), index.find("guest artist"))
    }

    @Test
    fun artistsOnlyMatchTheirNames() = runBlocking {
        val artist = collection(CollectionKind.ARTIST, 1, "Coldplay", "unrelated metadata")
        val index = LibraryCollectionSearchIndex.build(listOf(artist))
        assertEquals(listOf(artist), index.find("coldPLAY"))
        assertTrue(index.find("unrelated").isEmpty())
    }

    @Test
    fun playlistQueriesCanSpanTitleAndCreatorWithoutChangingOrder() = runBlocking {
        val playlists = listOf(
            collection(CollectionKind.PLAYLIST, 30, "夜间驾驶", "Alice"),
            collection(CollectionKind.PLAYLIST, 10, "夜间阅读", "Bob"),
            collection(CollectionKind.PLAYLIST, 20, "夜间散步", "Alice"),
        )
        val index = LibraryCollectionSearchIndex.build(playlists)
        val matches = index.find(" 夜间\tALICE ")
        assertEquals(listOf(30L, 20L), matches.map { it.info.id })
        assertSame(playlists[2], matches[1])
        assertTrue(index.find("驾驶 Bob").isEmpty())
    }

    @Test
    fun singleAlbumsMatchTheirDisplayedSuffixAndWhitespaceRestoresEverything() = runBlocking {
        val single = collection(CollectionKind.ALBUM, 1, "Yellow", "Coldplay")
            .let { it.copy(info = it.info.copy(albumType = "Single")) }
        val albums = listOf(single, collection(CollectionKind.ALBUM, 2, "Parachutes", "Coldplay"))
        val index = LibraryCollectionSearchIndex.build(albums)
        assertEquals(listOf(single), index.find("yellow single"))
        assertEquals(albums, index.find(" \t "))
        assertTrue(LibraryCollectionSearchIndex.build(emptyList()).find("anything").isEmpty())
    }

    @Test
    fun refreshedAndLargeCollectionsUseTheirOwnMetadata() = runBlocking {
        val original = listOf(collection(CollectionKind.PLAYLIST, 1, "旧名称"))
        val updated = listOf(collection(CollectionKind.PLAYLIST, 1, "新名称"))
        assertTrue(LibraryCollectionSearchIndex.build(original).find("新名称").isEmpty())
        assertEquals(updated, LibraryCollectionSearchIndex.build(updated).find("新名称"))
        val large = List(6000) { collection(CollectionKind.ALBUM, it.toLong(), "Album $it", "Artist ${it % 10}") }
        val matches = withContext(Dispatchers.Default) {
            LibraryCollectionSearchIndex.build(large).find("Album 5999 Artist 9")
        }
        assertEquals(listOf(large.last()), matches)
    }

    private fun collection(kind: CollectionKind, id: Long, title: String, author: String = "") =
        LibraryCollection(CollectionInfo(kind, id, title = title, author = author))
}
