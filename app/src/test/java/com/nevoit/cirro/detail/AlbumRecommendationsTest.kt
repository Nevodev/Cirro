package com.nevoit.cirro.detail

import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.Song
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumRecommendationsTest {
    @Test
    fun seedFollowsAlbumOrderAndDoesNotUseDurationOrPlaybackAvailability() {
        assertEquals(9L, recommendationSeed(listOf(Song(0), Song(9, playable = false), Song(2))))
        assertNull(recommendationSeed(emptyList()))
    }

    @Test
    fun otherWorksExcludeCurrentAlbumAndDuplicateOrInvalidIds() {
        val albums = listOf(4L, 0L, 8L, 8L, 10L).map { CollectionInfo(CollectionKind.ALBUM, it) }
        assertEquals(listOf(8L, 10L), otherArtistAlbums(albums, 4).map { it.id })
    }

    @Test
    fun similarSongsExcludeAllAlbumTracksAndPreserveRecommendationOrder() {
        val recommendations = listOf(
            Song(1), Song(2, albumId = 7), Song(3, albumId = 8), Song(3), Song(0), Song(4),
        )
        assertEquals(listOf(3L, 4L), albumSimilarSongs(recommendations, 7, listOf(Song(1))).map { it.id })
    }

    @Test
    fun recommendedSongsBecomeAlbumDestinationsWithTheFirstOccurrenceKept() {
        val albums = recommendationAlbums(listOf(
            Song(1, name = "song", album = "album", albumId = 8, cover = "cover", artists = listOf("artist"), playable = false),
            Song(2, album = "duplicate", albumId = 8),
            Song(3, albumId = 0),
            Song(4, album = "next", albumId = 9),
        ))
        assertEquals(listOf(8L, 9L), albums.map { it.id })
        assertEquals(CollectionKind.ALBUM, albums.first().kind)
        assertEquals("album", albums.first().title)
        assertEquals("artist", albums.first().author)
        assertEquals("cover", albums.first().cover)
    }

    @Test
    fun olderCollectionMetadataStillDecodesWithoutArtistIdentity() {
        val info = Json.decodeFromString<CollectionInfo>("""{"kind":"ALBUM","id":7}""")
        assertEquals(0L, info.primaryArtistId)
        assertEquals("", info.primaryArtistName)
    }
}
