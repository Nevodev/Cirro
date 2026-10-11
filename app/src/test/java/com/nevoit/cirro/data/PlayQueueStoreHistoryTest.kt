package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.PlaybackQueue
import com.nevoit.cirro.data.model.QueueEntry
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.playQueueOf
import com.nevoit.cirro.data.model.withCurrent
import com.nevoit.cirro.data.model.withMovedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayQueueStoreHistoryTest {
    private fun queue(): PlaybackQueue =
        playQueueOf((1L..5L).map { Song(id = it) }, startIndex = 2, source = null)

    @Test
    fun startingInTheMiddleAndSkippingUnstartedTracksDoesNotInventHistory() {
        val store = PlayQueueStore()
        val queue = queue()
        store.set(queue)
        store.finishPlaybackHistory(nextUid = queue.entries[4].uid)
        store.set(queue.withCurrent(4))

        assertTrue(store.history.value.isEmpty())
        store.markPlaying(queue.entries[4], startedAtMillis = 10L)
        store.finishPlaybackHistory()
        assertEquals(listOf(5L), store.history.value.map { it.entry.song.id })
    }

    @Test
    fun onlyTheActuallyPlayingEntryIsRecordedWhenItIsLeft() {
        val store = PlayQueueStore()
        val queue = queue()
        val entry = queue.entries[2]
        store.set(queue)
        store.markPlaying(entry, startedAtMillis = 10L)
        assertTrue(store.history.value.isEmpty())

        store.finishPlaybackHistory(nextUid = queue.entries[4].uid)
        assertEquals(1, store.history.value.size)
        assertSame(entry, store.history.value.single().entry)
        assertEquals(10L, store.history.value.single().startedAtMillis)
    }

    @Test
    fun pauseResumeAndQueueReorderingDoNotSplitTheSamePlayback() {
        val store = PlayQueueStore()
        val queue = queue()
        val current = queue.entries[2]
        store.markPlaying(current, startedAtMillis = 10L)
        store.markPlaying(current, startedAtMillis = 20L)
        store.set(queue.withMovedEntry(queue.entries[3].uid, queue.entries[4].uid))
        store.finishPlaybackHistory(nextUid = current.uid)
        assertTrue(store.history.value.isEmpty())

        store.finishPlaybackHistory()
        store.finishPlaybackHistory()
        assertEquals(1, store.history.value.size)
        assertEquals(10L, store.history.value.single().startedAtMillis)
    }

    @Test
    fun singleTrackRepeatsProduceDistinctChronologicalRecords() {
        val store = PlayQueueStore()
        val current = queue().entries[2]
        store.markPlaying(current, startedAtMillis = 10L)
        store.finishPlaybackHistory(nextUid = current.uid, repeated = true)
        store.markPlaying(current, startedAtMillis = 20L)
        store.finishPlaybackHistory(nextUid = current.uid, repeated = true)

        assertEquals(listOf(10L, 20L), store.history.value.map { it.startedAtMillis })
        assertEquals(listOf(current, current), store.history.value.map { it.entry })
        assertEquals(2, store.history.value.map { it.id }.distinct().size)
    }

    @Test
    fun historySurvivesWrappingReplacingAndClearingTheQueue() {
        val store = PlayQueueStore()
        val queue = queue()
        store.set(queue)
        store.markPlaying(queue.entries.last(), startedAtMillis = 10L)
        store.finishPlaybackHistory(nextUid = queue.entries.first().uid)
        val history = store.history.value

        store.set(queue.withCurrent(0))
        store.set(playQueueOf(listOf(Song(id = 99L)), 0, source = null))
        store.clear()
        assertSame(history, store.history.value)
        assertEquals(5L, store.history.value.single().entry.song.id)
    }

    @Test
    fun replacingTheQueueKeepsThePreviousPlayingEntrySnapshot() {
        val store = PlayQueueStore()
        val entry = queue().entries[2]
        store.markPlaying(entry, startedAtMillis = 10L)
        store.set(playQueueOf(listOf(Song(id = 99L)), 0, source = null))
        store.markPlaying(store.queue.value.entries.single(), startedAtMillis = 20L)

        assertSame(entry, store.history.value.single().entry)
    }

    @Test
    fun repeatedPlaybackKeepsAllHistoryWithUniqueOccurrenceKeys() {
        val store = PlayQueueStore()
        val entry = QueueEntry(uid = 1L, song = Song(id = 42L))
        val playCount = 250
        repeat(playCount) { index ->
            store.markPlaying(entry, startedAtMillis = index.toLong())
            store.finishPlaybackHistory(nextUid = entry.uid, repeated = true)
        }

        assertEquals(playCount, store.history.value.size)
        assertEquals((0 until playCount).map { it.toLong() }, store.history.value.map { it.startedAtMillis })
        assertEquals(playCount, store.history.value.map { it.id }.distinct().size)
    }
}
