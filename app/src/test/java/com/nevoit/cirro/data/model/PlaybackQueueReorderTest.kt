package com.nevoit.cirro.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueReorderTest {
    private val source = QueueSource(CollectionKind.PLAYLIST, "42", "歌单")

    private fun queue(): PlaybackQueue =
        playQueueOf((1L..7L).map { Song(id = it) }, startIndex = 3, source = source)

    @Test
    fun entriesBeforeCurrentCannotMoveInEitherDirection() {
        val queue = queue()
        for (past in queue.entries.take(queue.currentIndex)) {
            for (entry in queue.entries) {
                assertFalse(queue.canMoveEntry(past.uid, entry.uid))
                assertFalse(queue.canMoveEntry(entry.uid, past.uid))
                assertSame(queue, queue.withMovedEntry(past.uid, entry.uid))
                assertSame(queue, queue.withMovedEntry(entry.uid, past.uid))
            }
        }
    }

    @Test
    fun continuationUsesEntryIdentityAndPreservesInsertedMetadataAndModes() {
        val queue = queue()
            .withInsert(Song(id = 5L), source = null, at = 4)
            .copy(mode = PlayMode.REPEAT_ONE, shuffle = true)
        val inserted = queue.entries[4]
        val moved = queue.withMovedEntry(inserted.uid, queue.entries.last().uid)

        assertEquals(queue.entries.take(4), moved.entries.take(4))
        assertEquals(listOf(5L, 6L, 7L, 5L), moved.entries.drop(4).map { it.song.id })
        assertSame(inserted, moved.entries.last())
        assertTrue(moved.entries.last().inserted)
        assertEquals(queue.currentIndex, moved.currentIndex)
        assertEquals(queue.source, moved.source)
        assertEquals(queue.mode, moved.mode)
        assertEquals(queue.shuffle, moved.shuffle)
    }

    @Test
    fun currentEntryMovesAreRejectedInBothDirections() {
        val queue = queue()
        val current = queue.entries[3].uid

        for (entry in queue.entries) {
            assertFalse(queue.canMoveEntry(entry.uid, current))
            assertFalse(queue.canMoveEntry(current, entry.uid))
            assertSame(queue, queue.withMovedEntry(entry.uid, current))
            assertSame(queue, queue.withMovedEntry(current, entry.uid))
        }
    }

    @Test
    fun trackTransitionRevalidatesTheCurrentEntryBeforeMoving() {
        val queue = queue()
        val from = queue.entries[4].uid
        val to = queue.entries[6].uid
        assertTrue(queue.canMoveEntry(from, to))

        val advanced = queue.withCurrent(4)
        assertFalse(advanced.canMoveEntry(from, to))
        assertSame(advanced, advanced.withMovedEntry(from, to))

        val skipped = queue.withCurrent(5)
        assertFalse(skipped.canMoveEntry(from, to))
        assertSame(skipped, skipped.withMovedEntry(from, to))
    }

    @Test
    fun missingOrUnchangedEntriesAndInvalidCurrentAreNoOps() {
        val queue = queue()
        val uid = queue.entries.last().uid
        for ((from, to) in listOf(-1L to uid, uid to -1L, uid to uid)) {
            assertFalse(queue.canMoveEntry(from, to))
            assertSame(queue, queue.withMovedEntry(from, to))
        }
        val empty = PlaybackQueue()
        assertSame(empty, empty.withMovedEntry(1L, 2L))
        val invalid = queue.copy(currentIndex = queue.entries.size)
        assertSame(invalid, invalid.withMovedEntry(queue.entries[0].uid, uid))
    }

    @Test
    fun manualMoveRetainsTheOriginalOrderForDisablingShuffle() {
        val queue = queue().copy(shuffle = true)
        val moved = queue.withMovedEntry(queue.entries.last().uid, queue.entries[4].uid)

        assertEquals(queue, moved.restoredTail())
    }

    @Test
    fun continuationShowsOnlyThisRoundAndRetainsTheFullQueueForRepeating() {
        val initial = queue()
        for (mode in PlayMode.entries) {
            for (current in initial.entries.indices) {
                val queue = initial.copy(mode = mode).withCurrent(current)
                assertEquals(initial.entries.drop(current), queue.entriesFromCurrent)
                assertSame(initial.entries[current], queue.entriesFromCurrent.first())
                assertSame(initial.entries, queue.entries)
                assertEquals(initial.entries, queue.withCurrent(0).entriesFromCurrent)
            }
        }
        assertEquals(emptyList<QueueEntry>(), PlaybackQueue().entriesFromCurrent)
    }

    @Test
    fun futureMovesMatchDisplayedOrderAndKeepTheCurrentAndPreviousEntries() {
        val initial = queue()
        for (current in initial.entries.indices) {
            val queue = initial.withCurrent(current)
            val shown = queue.entriesFromCurrent
            for (from in 1 until shown.size) {
                for (to in 1 until shown.size) {
                    if (from == to) continue
                    val expected = shown.toMutableList().apply { add(to, removeAt(from)) }
                    val moved = queue.withMovedEntry(shown[from].uid, shown[to].uid)

                    assertEquals("current=$current, from=$from, to=$to", expected, moved.entriesFromCurrent)
                    assertSame(queue.entries[current], moved.entries[moved.currentIndex])
                    assertEquals(queue.entries.take(current), moved.entries.take(current))
                    assertEquals(queue.source, moved.source)
                }
            }
        }
    }
}
