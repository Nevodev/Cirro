package com.nevoit.material.core.layout

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/** 首个可见项目不再是第 0 项，或第 0 项的滚动距离严格超过阈值时返回 true。 */
@Composable
fun LazyListState.isScrolledPast(threshold: Dp): State<Boolean> {
    val thresholdPx = with(LocalDensity.current) { threshold.toPx() }
    return remember(this, thresholdPx) {
        scrolledPastState(thresholdPx)
    }
}

/** 与列表版本使用相同的 index 和 offset 门控，不累计网格行的尺寸。 */
@Composable
fun LazyGridState.isScrolledPast(threshold: Dp): State<Boolean> {
    val thresholdPx = with(LocalDensity.current) { threshold.toPx() }
    return remember(this, thresholdPx) {
        scrolledPastState(thresholdPx)
    }
}

internal fun LazyListState.scrolledPastState(thresholdPx: Float): State<Boolean> =
    derivedStateOf {
        firstVisibleItemIndex != 0 || firstVisibleItemScrollOffset > thresholdPx
    }

internal fun LazyGridState.scrolledPastState(thresholdPx: Float): State<Boolean> =
    derivedStateOf {
        firstVisibleItemIndex != 0 || firstVisibleItemScrollOffset > thresholdPx
    }
