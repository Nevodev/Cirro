package com.nevoit.kinetic.layout.internal.indicator

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class AccumulatedLayoutMetricsTest {
    @Test
    fun filteringToTwoSongsDropsMeasurementsBeyondTheNewEnd() {
        var layout = layout(100, item(0, "header", 400), item(1, "song-0", 60, 400))
        val metrics = AccumulatedLayoutMetrics(LazyListState()) { layout }
        metrics.contentSize
        layout = layout(100, item(80, "song-79", 60), item(81, "song-80", 60, 60))
        metrics.contentSize

        layout = layout(2, item(0, "song-3", 60), item(1, "song-8", 60, 60))
        assertEquals(120, metrics.contentSize)
        assertEquals(0, metrics.scrollOffset)
    }

    @Test
    fun equallySizedResultSetsWithDifferentKeysDoNotKeepTheOldHeaderPrefix() {
        var layout = original()
        val metrics = AccumulatedLayoutMetrics(LazyListState()) { layout }
        assertEquals(580, metrics.contentSize)

        layout = layout(4, item(2, "song-3", 60), item(3, "song-4", 60, 60))
        assertEquals(240, metrics.contentSize)
        assertEquals(120, metrics.scrollOffset)
    }

    @Test
    fun ordinaryScrollingKeepsTheMeasuredHeaderInsteadOfEstimatingItFromSongs() {
        var layout = original()
        val metrics = AccumulatedLayoutMetrics(LazyListState()) { layout }
        assertEquals(580, metrics.contentSize)

        layout = layout(4, item(1, "song-1", 60), item(2, "song-2", 60, 60))
        assertEquals(580, metrics.contentSize)
        assertEquals(400, metrics.scrollOffset)
    }

    @Test
    fun noMatchesThenClearingSearchRestoresTheOriginalMeasurements() {
        var layout = original()
        val metrics = AccumulatedLayoutMetrics(LazyListState()) { layout }
        metrics.contentSize
        layout = layout(0)
        assertEquals(0, metrics.contentSize)
        assertEquals(0, metrics.scrollOffset)

        layout = original()
        assertEquals(580, metrics.contentSize)
        assertEquals(0, metrics.scrollOffset)
    }

    @Test
    fun searchPaddingIsIncludedOnceAfterTheDatasetChanges() {
        var layout = original()
        val metrics = AccumulatedLayoutMetrics(LazyListState()) { layout }
        metrics.contentSize

        layout = layout(
            2,
            item(0, "song-2", 60),
            item(1, "song-3", 60, 60),
            before = 140,
            after = 180,
            viewport = 400,
        )
        assertEquals(440, metrics.contentSize)
        assertEquals(0, metrics.scrollOffset)
    }

    @Test
    fun scrollingThroughSearchPaddingReportsOnlyTheActualMovement() {
        val layout = layout(
            2,
            item(0, "song-2", 60, -30),
            item(1, "song-3", 60, 30),
            before = 140,
            after = 180,
            viewport = 400,
        )
        val metrics = AccumulatedLayoutMetrics(LazyListState()) { layout }
        assertEquals(440, metrics.contentSize)
        assertEquals(30, metrics.scrollOffset)
    }

    private fun original() = layout(
        4,
        item(0, "header", 400),
        item(1, "song-1", 60, 400),
        item(2, "song-2", 60, 460),
        item(3, "song-3", 60, 520),
    )

    private fun item(index: Int, key: String, size: Int, offset: Int = 0) = object : LazyListItemInfo {
        override val index = index
        override val key = key
        override val size = size
        override val offset = offset
    }

    private fun layout(
        count: Int,
        vararg items: LazyListItemInfo,
        before: Int = 0,
        after: Int = 0,
        viewport: Int = 100,
    ) = object : LazyListLayoutInfo {
        override val visibleItemsInfo = items.toList()
        override val totalItemsCount = count
        override val viewportSize = IntSize(360, viewport)
        override val viewportStartOffset = -before
        override val viewportEndOffset = viewportSize.height - before
        override val beforeContentPadding = before
        override val afterContentPadding = after
    }
}
