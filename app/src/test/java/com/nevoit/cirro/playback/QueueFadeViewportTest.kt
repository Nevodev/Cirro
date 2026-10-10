package com.nevoit.cirro.playback

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueFadeViewportTest {
    private val continuation = QueueSection("continue", null, 1, 5)

    private val fullList = Layout(
        listOf(
            Item(0, "continue", 0, 40),
            Item(1, "first", 40, 100),
            Item(5, "last", 500, 100),
        ),
        totalItemsCount = 6,
    )

    @Test
    fun restingAtTheEndsKeepsCompleteRowsOpaque() {
        val viewport = fullList.fadeViewport(continuation, visualOffsetY = 0f)
        assertEquals(40f, viewport.top, 0f)
        assertEquals(600f, viewport.bottom, 0f)
        assertEquals(0f, viewport.overhangAbove, 0f)
        assertEquals(0f, viewport.overhangBelow, 0f)
    }

    @Test
    fun pullingDownMovesTheHeaderBoundaryButKeepsTheScreenBottomFixed() {
        val shift = 60f
        val viewport = fullList.fadeViewport(continuation, visualOffsetY = shift)
        assertEquals(100f, viewport.top + shift, 0f)
        assertEquals(600f, viewport.bottom + shift, 0f)
        assertEquals(0f, viewport.overhangAbove, 0f)
        assertEquals(60f, viewport.overhangBelow, 0f)
    }

    @Test
    fun pullingUpClipsAtTheScreenTopWhenTheHeaderHasMovedOutside() {
        val shift = -60f
        val viewport = fullList.fadeViewport(continuation, visualOffsetY = shift)
        assertEquals(0f, viewport.top + shift, 0f)
        assertEquals(600f, viewport.bottom + shift, 0f)
        assertEquals(20f, viewport.overhangAbove, 0f)
        assertEquals(0f, viewport.overhangBelow, 0f)
    }

    @Test
    fun visibleSectionHeadersKeepTheirClearSpaceDuringOverscroll() {
        val layout = Layout(
            listOf(
                Item(0, "history", 0, 40),
                Item(1, "old", 40, 200),
                Item(2, "continue", 240, 40),
                Item(3, "current", 280, 100),
            ),
            totalItemsCount = 4,
        )
        val shift = 75f
        val history = layout.fadeViewport(QueueSection("history", "continue", 1, 1), shift)
        val next = layout.fadeViewport(QueueSection("continue", null, 3, 3), shift)
        assertEquals(315f, history.bottom + shift, 0f)
        assertEquals(355f, next.top + shift, 0f)
        assertEquals(40f, next.top - history.bottom, 0f)
    }

    @Test
    fun headerHandoffLeavesNoHistoryPixelsBehindTheNextHeader() {
        val layout = Layout(
            listOf(
                Item(0, "history", -20, 40),
                Item(1, "old", -80, 100),
                Item(2, "continue", 20, 40),
                Item(3, "current", 60, 100),
            ),
            totalItemsCount = 4,
        )
        val section = QueueSection("history", "continue", 1, 1)
        for (shift in listOf(-10f, 0f, 10f)) {
            assertEquals(0f, layout.fadeViewport(section, shift).height, 0f)
        }
    }

    private data class Item(
        override val index: Int,
        override val key: Any,
        override val offset: Int,
        override val size: Int,
    ) : LazyListItemInfo

    private data class Layout(
        override val visibleItemsInfo: List<LazyListItemInfo>,
        override val totalItemsCount: Int,
        override val viewportStartOffset: Int = 0,
        override val viewportEndOffset: Int = 600,
    ) : LazyListLayoutInfo
}
