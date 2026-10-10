package com.nevoit.cirro.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackQueueSourceTest {
    @Test
    fun albumSourceSurvivesShuffleInsertionAndTrackChanges() {
        val album = QueueSource(CollectionKind.ALBUM, "42", "专辑名")
        val playlist = QueueSource(CollectionKind.PLAYLIST, "84", "歌单名")
        val songs = (1L..4L).map { Song(id = it) }
        val queue = playQueueOf(songs, 0, album, shuffle = true)
            .withInsert(Song(id = 5L), playlist, at = 1)
            .withCurrent(1)
            .shuffledTail()
            .restoredTail()

        assertEquals(album, queue.source)
        assertEquals(playlist, queue.entries[1].source)
        assertEquals("84", queue.sourceIdAt(1))
        assertEquals(songs.drop(1), queue.entries.drop(2).map { it.song })
        queue.entries.filterNot { it.inserted }.forEach { entry ->
            assertEquals(album, entry.source)
        }
        assertEquals(album, queue.withCurrent(2).source)
        assertEquals("42", queue.sourceIdAt(2))
    }

    @Test
    fun dailyRecommendationsKeepTheirTitleWithoutAScrobbleId() {
        val source = QueueSource(CollectionKind.DAILY, "0", "每日推荐")
        val queue = playQueueOf(listOf(Song(id = 1L)), 0, source)

        assertEquals("每日推荐", queue.source?.title)
        assertEquals("", queue.sourceIdAt(0))
    }

    @Test
    fun namedHomeSectionDoesNotInventAScrobbleId() {
        val source = QueueSource(title = "歌曲推荐")
        val queue = playQueueOf(listOf(Song(id = 1L)), 0, source)

        assertEquals("歌曲推荐", queue.source?.title)
        assertEquals("", queue.sourceIdAt(0))
    }

    @Test
    fun clearingOrStartingSingleSongPlaybackDropsThePreviousSource() {
        val store = com.nevoit.cirro.data.PlayQueueStore()
        val source = QueueSource(CollectionKind.ALBUM, "42", "专辑名")
        store.set(playQueueOf(listOf(Song(id = 1L)), 0, source))
        store.set(playQueueOf(listOf(Song(id = 2L)), 0, source = null))
        assertNull(store.queue.value.source)
        assertNull(store.queue.value.entries.single().source)

        store.set(playQueueOf(listOf(Song(id = 1L)), 0, source))
        store.clear()
        assertNull(store.queue.value.source)
        assertEquals(emptyList<QueueEntry>(), store.queue.value.entries)
    }

    @Test
    fun serializedQueuesPreserveSourceAndAcceptMissingNewFields() {
        val source = QueueSource(CollectionKind.ALBUM, "42", "专辑名")
        val queue = playQueueOf(listOf(Song(id = 1L)), 0, source)
        val restored = Json.decodeFromString<PlaybackQueue>(Json.encodeToString(queue))
        assertEquals(queue, restored)

        val legacy = Json.decodeFromString<PlaybackQueue>(
            """{"entries":[{"uid":1,"song":{"id":1},"source":{"kind":"ALBUM","id":"42"}}]}""",
        )
        assertNull(legacy.source)
        assertEquals("", legacy.entries.single().source?.title)
        assertEquals("42", legacy.sourceIdAt(0))
    }
}
