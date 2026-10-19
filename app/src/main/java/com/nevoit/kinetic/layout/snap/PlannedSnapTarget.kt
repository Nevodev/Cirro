package com.nevoit.kinetic.layout.snap

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.lazy.LazyListState
import kotlin.math.abs

internal fun interface SnapTargetProvider {
    /** null until the real measured anchors bracket the planned approach point. */
    fun calculateTarget(referenceOffset: Float, selectionVelocity: Float, forwardOnly: Boolean): Float?
    fun safeAdvance(direction: Float): Float = Float.POSITIVE_INFINITY
}

internal class LazyListSnapTargets(
    private val state: LazyListState,
    private val snapPosition: SnapPosition,
    private val minimumFlingVelocity: Float,
) : SnapTargetProvider {
    override fun calculateTarget(referenceOffset: Float, selectionVelocity: Float, forwardOnly: Boolean): Float? {
        val info = state.layoutInfo
        val viewport = if (info.orientation == Orientation.Vertical) info.viewportSize.height else info.viewportSize.width
        var lower = Float.NEGATIVE_INFINITY
        var upper = Float.POSITIVE_INFINITY
        var hasStart = false
        var hasEnd = false
        for (item in info.visibleItemsInfo) {
            if (item.index < state.firstVisibleItemIndex) continue // pinned headers
            val offset = (item.offset - snapPosition.position(viewport, item.size,
                info.beforeContentPadding, info.afterContentPadding, item.index, info.totalItemsCount)).toFloat()
            if (offset <= referenceOffset && offset > lower) lower = offset
            if (offset >= referenceOffset && offset < upper) upper = offset
            hasStart = hasStart || item.index == 0
            hasEnd = hasEnd || item.index == info.totalItemsCount - 1
        }
        return selectPlannedSnapTarget(lower, upper, referenceOffset, selectionVelocity,
            minimumFlingVelocity, hasStart, hasEnd, forwardOnly)
    }

    override fun safeAdvance(direction: Float): Float {
        val info = state.layoutInfo
        val viewport = if (info.orientation == Orientation.Vertical) info.viewportSize.height else info.viewportSize.width
        var furthest = 0f
        for (item in info.visibleItemsInfo) {
            if (item.index < state.firstVisibleItemIndex) continue
            val offset = item.offset - snapPosition.position(viewport, item.size,
                info.beforeContentPadding, info.afterContentPadding, item.index, info.totalItemsCount)
            furthest = maxOf(furthest, offset * direction)
        }
        return if (furthest > 1f) furthest - .5f else maxOf(1f, viewport / 2f)
    }
}

/** The platform's closest/next/previous policy, evaluated at the predicted approach position. */
internal fun selectPlannedSnapTarget(
    lower: Float, upper: Float, reference: Float, velocity: Float,
    minimumFlingVelocity: Float, hasStart: Boolean, hasEnd: Boolean,
    forwardOnly: Boolean = false,
): Float? {
    if (!lower.isFinite() && !hasStart || !upper.isFinite() && !hasEnd) return null
    val chosen = if (abs(velocity) < minimumFlingVelocity) {
        if (abs(upper - reference) <= abs(lower - reference)) upper else lower
    } else if (velocity > 0f) upper else lower
    val finite = when {
        chosen.isFinite() -> chosen
        lower.isFinite() -> lower
        upper.isFinite() -> upper
        else -> return null
    }
    if (!forwardOnly || finite * velocity >= 0f) return finite
    return if (velocity > 0f) upper.takeIf { it.isFinite() && it >= 0f }
        else lower.takeIf { it.isFinite() && it <= 0f }
}
