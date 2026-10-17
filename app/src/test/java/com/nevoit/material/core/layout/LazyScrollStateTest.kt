package com.nevoit.material.core.layout

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LazyScrollStateTest {
    @Test
    fun cumulativeDistanceIncludesUnequalItemsAndSpacing() {
        val distance = LazyScrollDistance()
        distance.update(sample(lines = listOf(line(0, 80), line(1, 140))))

        val offset = distance.update(sample(first = 2, offset = 20, lines = listOf(line(2, 60))))

        assertEquals(264L, offset)
        assertFalse(offset > 300f)
        assertTrue(offset > 250f)
    }

    @Test
    fun returningAcrossTheThresholdUsesTheSameCachedPrefix() {
        val distance = LazyScrollDistance()
        distance.update(sample(lines = listOf(line(0, 80), line(1, 140))))
        assertTrue(distance.update(sample(2, 20, listOf(line(2, 60)))) > 250f)
        assertEquals(142L, distance.update(sample(1, 50, listOf(line(1, 140)))))
        assertEquals(0L, distance.update(sample(lines = listOf(line(0, 80)))))
    }

    @Test
    fun spacingIsNotAddedAgainWhileTheFirstItemIsStillCurrent() {
        val distance = LazyScrollDistance()
        val offset = distance.update(sample(offset = 90, lines = listOf(line(0, 80))))
        assertEquals(90L, offset)
        assertFalse(offset > 90f)
    }

    @Test
    fun gridDistanceCountsLinesInsteadOfCellsOrSpans() {
        val distance = LazyScrollDistance()
        val header = line(0, 100)
        val firstRow = LazyScrollLine(1, 1, listOf("left", "right"), 160)
        val secondRow = LazyScrollLine(2, 3, listOf("full-span"), 90)
        distance.update(sample(lines = listOf(header, firstRow)))

        assertEquals(289L, distance.update(sample(3, 5, listOf(secondRow))))
    }

    @Test
    fun remeasuredSizesInvalidateOnlyTheAffectedPrefix() {
        val distance = LazyScrollDistance()
        distance.update(sample(lines = listOf(line(0, 80), line(1, 140), line(2, 60))))
        distance.update(sample(lines = listOf(line(0, 120))))

        assertEquals(304L, distance.update(sample(2, 20, listOf(line(2, 60)))))
    }

    @Test
    fun missingMeasurementsDoNotUseAverageSizes() {
        val distance = LazyScrollDistance()
        distance.update(sample(lines = listOf(line(0, 80))))
        assertEquals(112L, distance.update(sample(5, 20, listOf(line(5, 400)))))
    }

    @Test
    fun replacingItemsOrChangingLayoutDiscardsStaleSizes() {
        val distance = LazyScrollDistance()
        distance.update(sample(lines = listOf(line(0, 80), line(1, 140))))
        val replacement = LazyScrollLine(1, 1, "replacement", 300)
        assertEquals(20L, distance.update(sample(1, 20, listOf(replacement))))

        distance.update(sample(lines = listOf(line(0, 80), line(1, 140))))
        val horizontal = sample(1, 20, listOf(line(1, 140))).let {
            it.copy(layoutKey = it.layoutKey.copy(orientation = Orientation.Horizontal))
        }
        assertEquals(20L, distance.update(horizontal))
    }

    @Test
    fun negativeSpacingMatchesTheNonnegativeMeasuredLineExtent() {
        val distance = LazyScrollDistance()
        val initial = sample(lines = listOf(line(0, 80), line(1, 140))).copy(spacing = -20)
        distance.update(initial)
        val next = sample(2, 10, listOf(line(2, 60))).copy(spacing = -20)
        assertEquals(190L, distance.update(next))
    }

    private fun line(index: Int, size: Int) = LazyScrollLine(index, index, index, size)

    private fun sample(
        first: Int = 0,
        offset: Int = 0,
        lines: List<LazyScrollLine>,
    ) = LazyScrollMeasurement(
        firstVisibleItemIndex = first,
        firstVisibleItemScrollOffset = offset,
        spacing = 12,
        lines = lines,
        layoutKey = LazyScrollLayoutKey(IntSize(300, 600), Orientation.Vertical, false, 10),
    )
}
