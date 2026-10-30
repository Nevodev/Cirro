package com.nevoit.cirro.detail

import com.nevoit.cirro.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionSongSearchTest {
    @Test
    fun ignoresSymbolsAndLatinAccentsWithoutJoiningFields() = runBlocking {
        val song = Song(1, "Rock & Roll", listOf("ADÉLA"), "Vol. 1")
        val index = CollectionSongSearchIndex.build(listOf(song))
        assertEquals(listOf(song), index.find("rockroll adela vol1").map { it.value })
        assertEquals(listOf(song), index.find("rock&roll adéla").map { it.value })
        assertTrue(index.find("rolladela").isEmpty())
        assertTrue(index.find("!!!").isEmpty())
    }

    private val songs = listOf(
        Song(1, "晴天", listOf("周杰伦"), "叶惠美"),
        Song(2, "Yellow", listOf("Coldplay"), "Parachutes"),
        Song(3, "Sparks", listOf("Coldplay"), "Parachutes"),
        Song(4, "夜曲", listOf("周杰伦"), "十一月的萧邦"),
    )

    @Test
    fun searchesNamesArtistsAndAlbumsWithOriginalPositions() = runBlocking {
        val index = CollectionSongSearchIndex.build(songs)
        assertEquals(listOf(0), index.find("晴天").map { it.index })
        assertEquals(listOf(1, 2), index.find("cOlDpLaY").map { it.index })
        assertEquals(listOf(1, 2), index.find("PARACHUTES").map { it.index })
        assertEquals(listOf(3), index.find("夜曲").map { it.index })
        assertEquals(songs[3], index.find("夜曲").single().value)
    }

    @Test
    fun multipleTermsMatchAcrossMetadataAndIgnoreOuterWhitespace() = runBlocking {
        val index = CollectionSongSearchIndex.build(songs)
        assertEquals(listOf(2), index.find("  sparks\tCOLDPLAY  ").map { it.index })
        assertEquals(listOf(0), index.find("周杰伦 叶惠美").map { it.index })
        assertTrue(index.find("Sparks 周杰伦").isEmpty())
    }

    @Test
    fun emptyAndUnmatchedQueriesHandleEmptyCollections() = runBlocking {
        val empty = CollectionSongSearchIndex.build(emptyList())
        assertTrue(empty.find("晴天").isEmpty())
        val index = CollectionSongSearchIndex.build(songs)
        assertEquals(songs, index.find(" \t ").map { it.value })
        assertTrue(index.find("不存在的歌曲").isEmpty())
    }

    @Test
    fun thousandsOfSongsKeepOrderAndTheCorrectPlaybackIndex() = runBlocking {
        val large = List(6000) { index ->
            Song(index.toLong(), "Track $index", listOf("Artist ${index % 10}"), "Album ${index % 30}")
        }
        val matches = withContext(Dispatchers.Default) {
            CollectionSongSearchIndex.build(large).find("Track 5999")
        }
        assertEquals(listOf(5999), matches.map { it.index })
        assertEquals(large[5999], matches.single().value)
    }
}
