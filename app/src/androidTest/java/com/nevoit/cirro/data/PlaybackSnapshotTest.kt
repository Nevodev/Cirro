package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.PlayMode
import com.nevoit.cirro.data.model.QueueSource
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.playQueueOf
import com.nevoit.cirro.data.model.withCurrent
import com.nevoit.cirro.data.model.withInsert
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSnapshotTest {
    private fun queue() = playQueueOf(
        listOf(Song(42, name = "First", durationMs = 120_000), Song(42, name = "Again", durationMs = 120_000), Song(99)),
        startIndex = 1,
        source = QueueSource(title = "Saved playlist"),
        mode = PlayMode.REPEAT_ALL,
        shuffle = true,
    ).copy(currentIndex = 1)

    @Test
    fun snapshotRoundTripKeepsTheCompletePlaylistOrderCurrentEntryAndModesWithoutHistory() {
        val store = PlayQueueStore()
        val queue = queue().withInsert(Song(7), QueueSource(title = "Insert"), 2)
        store.set(queue)
        store.markPlaying(queue.entries[0])
        store.finishPlaybackHistory()
        val snapshot = PlaybackSnapshot(store.queue.value, 37_000)
        val encoded = Json.encodeToString(snapshot)
        val restored = PlayQueueStore()
        restored.restore(Json.decodeFromString<PlaybackSnapshot>(encoded))

        assertEquals(queue, restored.queue.value)
        assertEquals(37_000L, restored.positionMs)
        assertTrue(store.history.value.isNotEmpty())
        assertTrue(restored.history.value.isEmpty())
        assertFalse(encoded.contains("history", ignoreCase = true))
    }

    @Test
    fun restoredUidsCannotCollideWithNewlyInsertedEntriesEvenForTheSameSong() {
        val queue = queue().let { original ->
            original.copy(entries = original.entries.mapIndexed { index, entry -> entry.copy(uid = 1_000_000L + index) })
        }
        val restored = PlayQueueStore()
        restored.restore(PlaybackSnapshot(queue))
        val inserted = restored.queue.value.withInsert(Song(42), null, 2)
        assertEquals(inserted.entries.size, inserted.entries.map { it.uid }.toSet().size)
        assertTrue(inserted.entries[2].uid > queue.entries.maxOf { it.uid })
    }

    @Test
    fun queueChangesAreSavedAndStalePositionSamplesCannotMoveTheNewSong() {
        val snapshots = mutableListOf<PlaybackSnapshot>()
        val store = PlayQueueStore(snapshots::add)
        val queue = queue()
        store.set(queue)
        val current = queue.entries[queue.currentIndex]
        store.updatePosition(current.uid, 37_000)
        assertEquals(37_000L, snapshots.last().positionMs)

        val nextIndex = (queue.currentIndex + 1) % queue.entries.size
        store.set(queue.withCurrent(nextIndex))
        store.updatePosition(current.uid, 98_000)
        assertEquals(0L, snapshots.last().positionMs)
        assertEquals(nextIndex, snapshots.last().queue.currentIndex)
        store.clear()
        assertTrue(snapshots.last().queue.entries.isEmpty())
    }

    @Test
    fun changingModesPreservesTheCurrentPositionAndRestorationDoesNotOverwriteTheFile() {
        val snapshots = mutableListOf<PlaybackSnapshot>()
        val store = PlayQueueStore(snapshots::add)
        val queue = queue()
        store.restore(PlaybackSnapshot(queue, 37_000))
        assertTrue(snapshots.isEmpty())
        store.set(queue.copy(mode = PlayMode.REPEAT_ONE))
        assertEquals(37_000L, snapshots.single().positionMs)
    }

    @Test
    fun invalidSnapshotsAreRejectedAndProgressIsBoundedByTheCurrentSongsDuration() {
        val queue = queue().let { original -> original.copy(entries = original.entries.map { it.copy(song = it.song.copy(durationMs = 120_000)) }) }
        assertNull(PlaybackSnapshot(queue.copy(currentIndex = 100)).validated())
        assertNull(PlaybackSnapshot(queue.copy(entries = listOf(queue.entries[0], queue.entries[0]))).validated())
        assertNull(PlaybackSnapshot(queue.copy(entries = queue.entries.map { it.copy(song = Song(-1)) })).validated())
        assertEquals(0L, PlaybackSnapshot(queue, -1).validated()?.positionMs)
        assertEquals(120_000L, PlaybackSnapshot(queue, Long.MAX_VALUE).validated()?.positionMs)
    }
}
