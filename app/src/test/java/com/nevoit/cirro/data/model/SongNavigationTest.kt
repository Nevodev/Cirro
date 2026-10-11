package com.nevoit.cirro.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongNavigationTest {
    @Test
    fun navigationMetadataPreservesLongIdsAndAcceptsLegacySongs() {
        val song = Song(3402673449L, artists = listOf("A", "B"), albumId = 5000000001L,
            artistIds = listOf(6000000001L, 0L))
        assertEquals(song, Json.decodeFromString<Song>(Json.encodeToString(song)))
        val legacy = Json.decodeFromString<Song>("""{"id":3402673449,"artists":["A"]}""")
        assertEquals(0L, legacy.albumId)
        assertEquals(emptyList<Long>(), legacy.artistIds)
    }

    @Test
    fun artistSourceKeepsDisplayTitleWithoutSendingArtistIdToScrobble() {
        val queue = playQueueOf(listOf(Song(1L)), 0, QueueSource(CollectionKind.ARTIST, "42", "艺术家"))
        assertEquals("艺术家", queue.source?.title)
        assertEquals("", queue.sourceIdAt(0))
    }

    @Test
    fun appendActionOnlyAppearsForUpcomingInsertBlock() {
        val queue = playQueueOf(listOf(Song(1L), Song(2L), Song(3L)), 0, null)
        assertFalse(PlaybackQueue().hasPendingInserts)
        assertFalse(queue.hasPendingInserts)
        val inserted = queue.withInsert(Song(4L), null, queue.insertNextIndex)
            .withInsert(Song(5L), null, 2)
        assertTrue(inserted.hasPendingInserts)
        assertEquals(3, inserted.appendInsertIndex)
        assertTrue(inserted.withCurrent(1).hasPendingInserts)
        assertFalse(inserted.withCurrent(2).hasPendingInserts)
        assertFalse(inserted.withCurrent(3).hasPendingInserts)
        assertFalse(queue.withInsert(Song(4L), null, 2).hasPendingInserts)
    }
}
