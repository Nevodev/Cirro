package com.nevoit.cirro.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueInfiniteTest {
    private val original = QueueSource(CollectionKind.ALBUM, "42", "专辑")
    private val recommendation = QueueSource(CollectionKind.PLAYLIST, "84", "心动模式")

    @Test
    fun recommendationsPreserveCurrentSongAndInsertedOrderAndDeduplicate() {
        val queue = playQueueOf(listOf(Song(1), Song(2)), 0, original, infinite = true)
            .withInsert(Song(3), null, 1)
        val extended = queue.withRecommendations(listOf(Song(2), Song(4), Song(4), Song(0)), recommendation)
        assertEquals(listOf(1L, 3L, 2L, 4L), extended.entries.map { it.song.id })
        assertEquals(queue.entries, extended.entries.take(queue.entries.size))
        assertEquals(queue.currentIndex, extended.currentIndex)
        assertEquals(original, extended.source)
        assertTrue(extended.infinite)
        assertFalse(extended.entries.last().inserted)
        assertTrue(extended.entries.last().recommended)
        assertTrue(extended.entries.take(queue.entries.size).none { it.recommended })
        assertEquals(recommendation, extended.entries.last().source)
        assertEquals("84", extended.sourceIdAt(extended.entries.lastIndex))
        assertEquals(extended.entries.size, extended.entries.map { it.uid }.toSet().size)
    }

    @Test
    fun staleBatchesCannotDuplicateSongsInsertedWhileFetching() {
        val live = playQueueOf(listOf(Song(1)), 0, original, infinite = true)
            .withInsert(Song(4), null, 1)
        val extended = live.withRecommendations(listOf(Song(4), Song(5)), recommendation)
        assertEquals(listOf(1L, 4L, 5L), extended.entries.map { it.song.id })
        assertSame(live, live.withRecommendations(listOf(Song(1), Song(4)), recommendation))
    }

    @Test
    fun disablingShuffleRetainsTheRecommendedBatchOrderAndInsertedSlot() {
        val queue = playQueueOf(listOf(Song(1), Song(2)), 0, original, shuffle = true, infinite = true)
            .withInsert(Song(3), null, 1)
            .withRecommendations(listOf(Song(4), Song(5)), recommendation)
        assertEquals(listOf(1L, 3L, 2L, 4L, 5L), queue.shuffledTail().restoredTail().entries.map { it.song.id })
        assertTrue(queue.shuffledTail().restoredTail().infinite)
    }

    @Test
    fun enablingInfiniteExitsRepeatingAndDisablingRemovesPendingRecommendations() {
        val repeating = playQueueOf(listOf(Song(1), Song(2)), 0, original, mode = PlayMode.REPEAT_ONE, shuffle = true)
        val enabled = repeating.withInfinite(true)
        assertEquals(PlayMode.SEQUENTIAL, enabled.mode)
        assertTrue(enabled.shuffle)
        assertEquals(repeating.entries, enabled.entries)
        val disabled = enabled.withRecommendations(listOf(Song(4)), recommendation).withInfinite(false)
        assertFalse(disabled.infinite)
        assertEquals(repeating.entries, disabled.entries)
        assertEquals(PlayMode.SEQUENTIAL, disabled.mode)
        assertTrue(disabled.shuffle)
    }

    @Test
    fun disablingWhilePlayingARecommendationKeepsCurrentAndPreviousEntries() {
        val extended = playQueueOf(listOf(Song(1)), 0, original, infinite = true)
            .withRecommendations(listOf(Song(2), Song(3), Song(4)), recommendation)
            .withCurrent(2)
        val disabled = extended.withInfinite(false)
        assertEquals(extended.entries.take(3), disabled.entries)
        assertEquals(2, disabled.currentIndex)
        assertEquals(extended.entries[2].uid, disabled.entries[disabled.currentIndex].uid)
        assertEquals(original, disabled.source)
        assertEquals(recommendation, disabled.entries.last().source)
    }

    @Test
    fun cleanupPreservesManualSongsEvenWithTheSameSongAndRecommendationSource() {
        val extended = playQueueOf(listOf(Song(1), Song(2)), 0, recommendation, infinite = true)
            .withRecommendations(listOf(Song(3), Song(4), Song(5)), recommendation)
            .withInsert(Song(4), recommendation, 3)
        val shuffled = extended.copy(entries = listOf(
            extended.entries[0], extended.entries[4], extended.entries[1],
            extended.entries[3], extended.entries[5], extended.entries[2],
        ), shuffle = true)
        val disabled = shuffled.withInfinite(false)
        assertEquals(listOf(1L, 2L, 4L), disabled.entries.map { it.song.id })
        assertEquals(extended.entries[3].uid, disabled.entries.last().uid)
        assertTrue(disabled.entries.last().inserted)
        assertTrue(disabled.entries.none { it.recommended })
        assertTrue(disabled.shuffle)
        assertEquals(disabled.entries, disabled.restoredTail().entries)
    }

    @Test
    fun disablingAnEmptyOrOrdinaryQueueOnlyChangesTheFlag() {
        val empty = PlaybackQueue(infinite = true).withInfinite(false)
        assertTrue(empty.entries.isEmpty())
        assertFalse(empty.infinite)
        val ordinary = playQueueOf(listOf(Song(1), Song(2)), 1, original, infinite = true)
        assertEquals(ordinary.copy(infinite = false), ordinary.withInfinite(false))
        assertEquals(ordinary.copy(infinite = false), ordinary.withInfinite(false).withInfinite(false))
    }

    @Test
    fun aWholeRecommendedBatchRequiresOneRemovalAndPreservesTheCurrentItem() {
        val queue = playQueueOf((1L..100L).map { Song(it) }, 80, original, infinite = true)
            .withRecommendations((101L..120L).map { Song(it) }, recommendation)
        val ranges = queue.pendingRecommendationRemovalRanges()
        assertEquals(listOf(100 until 120), ranges)
        val playerItems = queue.entries.toMutableList()
        ranges.forEach { playerItems.subList(it.first, it.last + 1).clear() }
        assertEquals(queue.withInfinite(false).entries, playerItems)
        assertEquals(queue.entries[queue.currentIndex].uid, playerItems[queue.currentIndex].uid)
    }

    @Test
    fun removalRangesRetainManualItemsBetweenRecommendedGroups() {
        val extended = playQueueOf(listOf(Song(1), Song(2)), 0, original, infinite = true)
            .withRecommendations((3L..6L).map { Song(it) }, recommendation)
            .withInsert(Song(3), recommendation, 4)
        val queue = extended.copy(entries = listOf(
            extended.entries[0], extended.entries[2], extended.entries[3],
            extended.entries[1], extended.entries[4], extended.entries[5], extended.entries[6],
        ), shuffle = true)
        val ranges = queue.pendingRecommendationRemovalRanges()
        assertEquals(listOf(5..6, 1..2), ranges)
        val playerItems = queue.entries.toMutableList()
        ranges.forEach { playerItems.subList(it.first, it.last + 1).clear() }
        assertEquals(queue.withInfinite(false).entries, playerItems)
        assertEquals(listOf(1L, 2L, 3L), playerItems.map { it.song.id })
        val playingRecommendation = queue.withCurrent(2)
        assertEquals(listOf(5..6), playingRecommendation.pendingRecommendationRemovalRanges())
        assertTrue(playingRecommendation.pendingRecommendationRemovalRanges().all {
            it.first > playingRecommendation.currentIndex
        })
        assertTrue(PlaybackQueue().pendingRecommendationRemovalRanges().isEmpty())
    }
}
