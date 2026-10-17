package com.nevoit.material.core.layout

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize

/**
 * 按从起点累计滚动的距离判断，需从起点连续观测项目的实际尺寸。
 * 跳过未测量前缀时只计算已知距离，可能延后返回 true；不使用平均尺寸估算。
 */
@Composable
fun LazyListState.isScrolledPast(threshold: Dp): State<Boolean> =
    rememberIsScrolledPast(this, threshold) {
        val info = layoutInfo
        LazyScrollMeasurement(
            firstVisibleItemIndex = firstVisibleItemIndex,
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
            spacing = info.mainAxisItemSpacing,
            lines = info.visibleItemsInfo.map {
                LazyScrollLine(it.index, it.index, it.key, it.size)
            },
            layoutKey = LazyScrollLayoutKey(
                info.viewportSize, info.orientation, info.reverseLayout, info.totalItemsCount,
            ),
        )
    }

/** 与列表版本相同，按行的最大主轴尺寸累计，支持跨列项和横向网格。 */
@Composable
fun LazyGridState.isScrolledPast(threshold: Dp): State<Boolean> =
    rememberIsScrolledPast(this, threshold) {
        val info = layoutInfo
        val isVertical = info.orientation == Orientation.Vertical
        val lines = info.visibleItemsInfo
            .filter { if (isVertical) it.row >= 0 else it.column >= 0 }
            .groupBy { if (isVertical) it.row else it.column }
            .map { (lineIndex, items) ->
                LazyScrollLine(
                    index = lineIndex,
                    firstItemIndex = items.minOf { it.index },
                    key = items.map { it.key },
                    size = items.maxOf { if (isVertical) it.size.height else it.size.width },
                )
            }
        LazyScrollMeasurement(
            firstVisibleItemIndex = firstVisibleItemIndex,
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
            spacing = info.mainAxisItemSpacing,
            lines = lines,
            layoutKey = LazyScrollLayoutKey(
                info.viewportSize, info.orientation, info.reverseLayout, info.totalItemsCount,
                info.maxSpan,
            ),
        )
    }

@Composable
private fun rememberIsScrolledPast(
    state: Any,
    threshold: Dp,
    measurement: () -> LazyScrollMeasurement,
): State<Boolean> {
    val thresholdPx by rememberUpdatedState(with(LocalDensity.current) { threshold.toPx() })
    val currentMeasurement by rememberUpdatedState(measurement)
    val scrolledPast = remember(state) { mutableStateOf(false) }

    LaunchedEffect(state) {
        val distance = LazyScrollDistance()
        snapshotFlow { currentMeasurement() to thresholdPx }.collect { (sample, limit) ->
            scrolledPast.value = distance.update(sample).toDouble() > limit
        }
    }
    return scrolledPast
}

internal data class LazyScrollLine(
    val index: Int,
    val firstItemIndex: Int,
    val key: Any,
    val size: Int,
)

internal data class LazyScrollLayoutKey(
    val viewportSize: IntSize,
    val orientation: Orientation,
    val reverseLayout: Boolean,
    val totalItemsCount: Int,
    val maxSpan: Int = 1,
)

internal data class LazyScrollMeasurement(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val spacing: Int,
    val lines: List<LazyScrollLine>,
    val layoutKey: LazyScrollLayoutKey,
)

internal class LazyScrollDistance {
    private val lines = mutableMapOf<Int, LazyScrollLine>()
    private val lineByFirstItem = mutableMapOf<Int, Int>()
    private val prefixOffsets = mutableListOf(0L)
    private var layoutKey: LazyScrollLayoutKey? = null
    private var spacing = 0

    fun update(measurement: LazyScrollMeasurement): Long {
        if (layoutKey != measurement.layoutKey || spacing != measurement.spacing ||
            measurement.lines.any { line ->
                lines[line.index]?.let { it.key != line.key } == true
            }
        ) {
            lines.clear()
            lineByFirstItem.clear()
            prefixOffsets.clear()
            prefixOffsets.add(0L)
        }
        layoutKey = measurement.layoutKey
        spacing = measurement.spacing

        for (line in measurement.lines) {
            if (lines[line.index]?.size != line.size) {
                while (prefixOffsets.lastIndex > line.index) {
                    prefixOffsets.removeAt(prefixOffsets.lastIndex)
                }
            }
            lines[line.index] = line
            lineByFirstItem[line.firstItemIndex] = line.index
        }
        while (true) {
            val line = lines[prefixOffsets.lastIndex] ?: break
            val extent = (line.size.toLong() + spacing).coerceAtLeast(0L)
            prefixOffsets.add(prefixOffsets.last() + extent)
        }

        // ScrollOffset 已包含当前行内的间距和 padding 位移，只补齐它之前的完整行。
        // 未测过的前缀不能恢复；保留已知距离下界，避免伪造精确值。
        val firstLine = lineByFirstItem[measurement.firstVisibleItemIndex]
        val prefix = firstLine?.let { prefixOffsets.getOrNull(it) } ?: prefixOffsets.last()
        return prefix + measurement.firstVisibleItemScrollOffset
    }
}
