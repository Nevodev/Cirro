package com.nevoit.kinetic.layout.internal.indicator

import androidx.compose.foundation.ScrollIndicatorState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.math.max

/**
 * Scroll metrics accumulated from measured lines instead of re-derived from the visible average.
 *
 * The platform computes `offset = averageItemSize * firstVisibleIndex` and
 * `content = averageItemSize * itemCount` on every read, so both follow whatever happens to be on
 * screen. Here a line (one item for a list, one row for a grid) caches its main axis size as it is
 * laid out, and line offsets accumulate into a prefix from the content start: everything already
 * scrolled through is exact, and a line that was never laid out (a jump, or a fling that skipped it)
 * only inserts one averaged step into the prefix until it is measured.
 *
 * Reads the same [LazyListState.layoutInfo] / [LazyGridState.layoutInfo] the platform objects read,
 * so it subscribes to the same snapshot changes. The caches are plain fields, not observable state.
 */
internal class AccumulatedLayoutMetrics(
    private val state: ScrollableState,
) : ScrollIndicatorState {

    // One entry per laid out line, ascending by the first item index of the line.
    private var lineStarts = IntArray(0)
    private var lineSizes = LongArray(0)
    // Averaged distance inserted before a line that follows items which were never laid out.
    private var lineGaps = LongArray(0)
    // lineOffsets[k] is the content offset of line k (leading padding excluded). [lines] is the
    // offset just past the last line, trailing spacing included.
    private var lineOffsets = LongArray(1)
    private var lines = 0
    private var sizeSum = 0L
    private var itemsSum = 0L
    private var end = 0

    private var total = 0
    private var before = 0L
    private var after = 0L
    private var spacing = 0L
    private var viewport = 0
    private var reverse = false

    // The line the state calls first visible, plus its main axis offset inside the viewport.
    private var anchorStart = -1
    private var anchorOffset = 0L

    // Lines of the layout being sampled, rebuilt on every layout change.
    private var pendingStarts = IntArray(0)
    private var pendingEnds = IntArray(0)
    private var pendingSizes = LongArray(0)
    private var pendingOffsets = LongArray(0)
    private var pendingCount = 0
    private var sampleLineSize = 0L
    private var sampleItemsPerLine = 1.0
    private var sampledLayout: Any? = null

    override val viewportSize: Int
        get() {
            sample()
            return viewport
        }

    override val contentSize: Int
        get() {
            sample()
            return contentSizePx().toIntClamped()
        }

    override val scrollOffset: Int
        get() {
            sample()
            if (total <= 0) return 0
            val line = lineOf(anchorStart)
            // Nothing anchored yet: keep the platform value until an item is measurable.
            if (line < 0) return state.scrollIndicatorState?.scrollOffset ?: 0
            val range = (contentSizePx() - viewport).coerceAtLeast(0L)
            var offset = (before + lineOffsets[line] - anchorOffset).coerceIn(0L, range)
            if (reverse) offset = range - offset
            return offset.toIntClamped()
        }

    private fun sample() {
        val layout = currentLayout()
        if (layout === sampledLayout) return
        sampledLayout = layout
        pendingCount = 0
        when (state) {
            is LazyListState -> readList(state.layoutInfo)
            is LazyGridState -> readGrid(state.layoutInfo)
            else -> return
        }
        commit()
    }

    private fun currentLayout(): Any = when (state) {
        is LazyListState -> state.layoutInfo
        is LazyGridState -> state.layoutInfo
        else -> state
    }

    /** One line per item. */
    private fun readList(info: LazyListLayoutInfo) {
        val vertical = info.orientation == Orientation.Vertical
        begin(
            total = info.totalItemsCount,
            beforePx = info.beforeContentPadding,
            afterPx = info.afterContentPadding,
            itemSpacing = info.mainAxisItemSpacing,
            viewportPx = if (vertical) info.viewportSize.height else info.viewportSize.width,
            reversed = info.reverseLayout,
        )
        for (item in info.visibleItemsInfo) {
            addLine(item.index, item.index + 1, item.size.toLong(), item.offset.toLong())
        }
    }

    /** One line per row: consecutive visible items sharing a row, sized by the tallest of them. */
    private fun readGrid(info: LazyGridLayoutInfo) {
        val vertical = info.orientation == Orientation.Vertical
        begin(
            total = info.totalItemsCount,
            beforePx = info.beforeContentPadding,
            afterPx = info.afterContentPadding,
            itemSpacing = info.mainAxisItemSpacing,
            viewportPx = if (vertical) info.viewportSize.height else info.viewportSize.width,
            reversed = info.reverseLayout,
        )
        var start = -1
        var endItem = 0
        var row = -1
        var size = 0L
        var offset = 0L
        for (item in info.visibleItemsInfo) {
            val itemRow = if (vertical) item.row else item.column
            if (itemRow == -1) continue // items animating out have no line
            val itemOffset = (if (vertical) item.offset.y else item.offset.x).toLong()
            val itemSize = (if (vertical) item.size.height else item.size.width).toLong()
            if (start >= 0 && itemRow == row) {
                size = max(size, itemSize)
                endItem = item.index + 1
            } else {
                if (start >= 0) addLine(start, endItem, size, offset)
                start = item.index
                endItem = item.index + 1
                row = itemRow
                size = itemSize
                offset = itemOffset
            }
        }
        if (start >= 0) addLine(start, endItem, size, offset)
    }

    private fun begin(
        total: Int,
        beforePx: Int,
        afterPx: Int,
        itemSpacing: Int,
        viewportPx: Int,
        reversed: Boolean,
    ) {
        val newSpacing = itemSpacing.toLong()
        if (newSpacing != spacing) {
            spacing = newSpacing
            lineStarts = IntArray(0)
            lineSizes = LongArray(0)
            lineGaps = LongArray(0)
            lineOffsets = LongArray(1)
            lines = 0
            sizeSum = 0L
            itemsSum = 0L
            end = 0
        }
        this.total = total
        before = beforePx.toLong()
        after = afterPx.toLong()
        viewport = viewportPx
        reverse = reversed
    }

    private fun addLine(start: Int, endItem: Int, size: Long, offset: Long) {
        if (pendingCount >= pendingStarts.size) {
            val capacity = max(pendingCount + 1, pendingStarts.size * 2)
            pendingStarts = pendingStarts.copyOf(capacity)
            pendingEnds = pendingEnds.copyOf(capacity)
            pendingSizes = pendingSizes.copyOf(capacity)
            pendingOffsets = pendingOffsets.copyOf(capacity)
        }
        pendingStarts[pendingCount] = start
        pendingEnds[pendingCount] = endItem
        pendingSizes[pendingCount] = size
        pendingOffsets[pendingCount] = offset
        pendingCount++
    }

    /** Folds the sampled lines into the prefix, then remembers which one anchors the viewport. */
    private fun commit() {
        if (pendingCount == 0) return
        var sizeAcc = 0L
        var itemAcc = 0
        for (k in 0 until pendingCount) {
            sizeAcc += pendingSizes[k]
            itemAcc += pendingEnds[k] - pendingStarts[k]
        }
        sampleLineSize = sizeAcc / pendingCount
        sampleItemsPerLine = if (itemAcc > 0) itemAcc.toDouble() / pendingCount else 1.0

        for (k in 0 until pendingCount) {
            val start = pendingStarts[k]
            val endItem = pendingEnds[k]
            val size = pendingSizes[k]
            val known = lineOf(start)
            if (known >= 0) {
                if (lineSizes[known] != size) {
                    sizeSum += size - lineSizes[known]
                    lineSizes[known] = size
                    rebuild(known)
                }
            } else if (lines == 0 || start > lineStarts[lines - 1]) {
                val from = if (lines == 0) 0 else end
                val gap = if (start > from) ((start - from) * stepPerItem()).toLong() else 0L
                append(start, size, gap)
                itemsSum += endItem - start
            }
            if (endItem > end) end = endItem
        }

        val target = firstVisibleIndex()
        var k = 0
        while (k < pendingCount - 1 && pendingEnds[k] <= target) k++
        anchorStart = pendingStarts[k]
        anchorOffset = pendingOffsets[k]
    }

    private fun append(start: Int, size: Long, gap: Long) {
        if (lines >= lineStarts.size) {
            val capacity = max(lines + 1, lineStarts.size * 2)
            lineStarts = lineStarts.copyOf(capacity)
            lineSizes = lineSizes.copyOf(capacity)
            lineGaps = lineGaps.copyOf(capacity)
        }
        if (lines + 1 >= lineOffsets.size) {
            lineOffsets = lineOffsets.copyOf(max(lines + 2, lineOffsets.size * 2))
        }
        // lineOffsets[lines] is the offset just past the last line, so a gap only has to be added.
        val offset = lineOffsets[lines] + gap
        lineStarts[lines] = start
        lineSizes[lines] = size
        lineGaps[lines] = gap
        lineOffsets[lines] = offset
        sizeSum += size
        lines++
        lineOffsets[lines] = offset + size + spacing
    }

    private fun rebuild(from: Int) {
        var k = from + 1
        while (k <= lines) {
            lineOffsets[k] = lineOffsets[k - 1] + lineSizes[k - 1] + spacing +
                (if (k < lines) lineGaps[k] else 0L)
            k++
        }
    }

    /** Index of the accumulated line holding [itemIndex], or -1 when that item was never laid out. */
    private fun lineOf(itemIndex: Int): Int {
        if (itemIndex < 0) return -1
        var low = 0
        var high = lines - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lineStarts[mid] <= itemIndex) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return if (found >= 0 && itemIndex < lineEnd(found)) found else -1
    }

    private fun lineEnd(index: Int): Int = if (index < lines - 1) lineStarts[index + 1] else end

    private fun contentSizePx(): Long {
        if (total <= 0) return before + after
        if (lines == 0) return before + after + (total * stepPerItem()).toLong()
        val remaining = (total - end).coerceAtLeast(0)
        return before + lineOffsets[lines] - spacing + (remaining * stepPerItem()).toLong() + after
    }

    /** Main axis distance one item index advances, averaged over what has been laid out. */
    private fun stepPerItem(): Double {
        val lineSize = if (lines > 0) sizeSum.toDouble() / lines else sampleLineSize.toDouble()
        // A screen filled by one full-span row would report 1 item per line; the accumulated lines
        // know better.
        val perLine = if (lines > 1) max(sampleItemsPerLine, itemsSum.toDouble() / lines)
        else sampleItemsPerLine
        return (lineSize + spacing) / perLine.coerceAtLeast(1.0)
    }

    private fun firstVisibleIndex(): Int = when (state) {
        is LazyListState -> state.firstVisibleItemIndex
        is LazyGridState -> state.firstVisibleItemIndex
        else -> 0
    }

    private fun Long.toIntClamped(): Int = when {
        this <= 0L -> 0
        this >= Int.MAX_VALUE -> Int.MAX_VALUE
        else -> toInt()
    }
}

/**
 * The indicator's metrics source: accumulated for lazy lists and grids, the platform's own object
 * (already exact for pagers) otherwise.
 */
@Composable
internal fun rememberScrollIndicatorMetrics(state: ScrollableState): ScrollIndicatorState? {
    if (state is LazyListState || state is LazyGridState) {
        return remember(state) { AccumulatedLayoutMetrics(state) }
    }
    return state.scrollIndicatorState
}
