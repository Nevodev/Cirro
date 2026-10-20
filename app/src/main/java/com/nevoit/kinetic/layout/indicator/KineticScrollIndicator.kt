package com.nevoit.kinetic.layout.indicator

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.ScrollIndicatorState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.nevoit.kinetic.layout.internal.indicator.rememberScrollIndicatorMetrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.time.Duration.Companion.milliseconds

private val IndicatorEasing = CubicBezierEasing(.42f, 0f, .58f, 1f)

/**
 * Show the bar while [isActive] is true, then [autoHideMillis] of silence and a [fadeOutMillis]
 * fade-out. Re-activating during the fade reverses from the current alpha.
 */
@Composable
fun rememberScrollIndicatorAlpha(
    isActive: () -> Boolean,
    autoHideMillis: Long = ScrollIndicatorDefaults.AutoHideMillis,
    fadeOutMillis: Int = ScrollIndicatorDefaults.FadeOutMillis,
    fadeInMillis: Int = ScrollIndicatorDefaults.FadeInMillis,
): State<Float> {
    require(autoHideMillis >= 0L && fadeOutMillis >= 0 && fadeInMillis >= 0)
    val latestActive by rememberUpdatedState(isActive)
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(alpha, autoHideMillis, fadeOutMillis, fadeInMillis) {
        snapshotFlow { latestActive() }.collectLatest { active ->
            if (active) {
                if (fadeInMillis <= 0) alpha.snapTo(1f)
                else alpha.animateTo(1f, tween(fadeInMillis, easing = IndicatorEasing))
            } else {
                delay(autoHideMillis.milliseconds)
                alpha.animateTo(0f, tween(fadeOutMillis, easing = IndicatorEasing))
            }
        }
    }
    return alpha.asState()
}

/**
 * Drawing-only modifier: no visibility policy and no touch handling, so it can be driven by a
 * custom alpha and by any metrics source. Metrics, alpha and overscroll are read in draw.
 *
 * [ScrollIndicatorSpec.color] is resolved here, so an unspecified colour follows
 * [LocalScrollIndicatorColor].
 */
@Composable
fun Modifier.drawKineticScrollIndicator(
    metrics: ScrollIndicatorState?,
    orientation: Orientation = Orientation.Vertical,
    spec: ScrollIndicatorSpec = ScrollIndicatorSpec.Default,
    overscroll: () -> Float = { 0f },
    alpha: () -> Float = { 1f },
    expansion: () -> Float = { 0f },
): Modifier {
    val color = spec.color.takeOrElse { LocalScrollIndicatorColor.current }
    return drawWithCache {
        val vertical = orientation == Orientation.Vertical
        val rtl = layoutDirection == LayoutDirection.Rtl
        val insets = spec.resolveInsets(this, layoutDirection, vertical)
        val left = insets.left
        val right = insets.right
        val top = insets.top
        val bottom = insets.bottom
        val normalWidth = spec.thickness.toPx()
        val expandedWidth = spec.expandedThickness.toPx()
        val minLength = spec.minimumLength.toPx()
        val minOverscrollLength = spec.minimumOverscrollLength.toPx()
        val axisStart = if (vertical) top else if (rtl) right else left
        val axisEnd = if (vertical) bottom else if (rtl) left else right
        var outlineWidth = Float.NaN
        var outlineLength = Float.NaN
        var outline: Outline? = null
        onDrawWithContent {
            drawContent()
            val width = normalWidth + (expandedWidth - normalWidth) * expansion().coerceIn(0f, 1f)
            if (metrics == null || !width.isFinite() || width <= 0f) return@onDrawWithContent
            val opacity = alpha()
            if (!opacity.isFinite() || opacity <= 0f) return@onDrawWithContent
            val viewport = metrics.viewportSize
            val content = metrics.contentSize
            val offset = metrics.scrollOffset
            if (viewport == Int.MAX_VALUE || content == Int.MAX_VALUE || offset == Int.MAX_VALUE) {
                return@onDrawWithContent
            }
            val extent = if (vertical) size.height else size.width
            // Use the metrics viewport in the formula; require an overlay of the same viewport size.
            // Ignore a one-pixel rounding difference between layout and draw dimensions.
            if (viewport <= 0 || kotlin.math.abs(extent - viewport) > 1f) return@onDrawWithContent
            val bar = calculateScrollIndicator(
                viewportSizePx = extent,
                contentSizePx = content.toFloat(),
                scrollOffsetPx = offset.toFloat(),
                overscrollPx = overscroll(),
                density = density,
                startInsetPx = axisStart,
                endInsetPx = axisEnd,
                reverseAxis = !vertical && rtl,
                minimumLengthPx = minLength,
                minimumOverscrollLengthPx = minOverscrollLength,
            )
            if (!bar.isVisible) return@onDrawWithContent
            val cross = if (vertical) {
                if (rtl) left else size.width - right - width
            } else size.height - bottom - width
            if (!cross.isFinite() || cross < 0f) return@onDrawWithContent
            val origin = if (vertical) Offset(cross, bar.startPx) else Offset(bar.startPx, cross)
            val barSize = if (vertical) Size(width, bar.lengthPx) else Size(bar.lengthPx, width)
            if (outline == null || width != outlineWidth || bar.lengthPx != outlineLength) {
                outline = spec.shape.createOutline(barSize, layoutDirection, this)
                outlineWidth = width
                outlineLength = bar.lengthPx
            }
            translate(origin.x, origin.y) {
                drawOutline(outline, color, alpha = opacity.coerceAtMost(1f))
            }
        }
    }
}

/**
 * Full indicator: visibility policy, drawing and long-press dragging of the visible bar.
 * Attach to the stationary viewport (an outer Box, or the container's own modifier chain), never
 * to a translated content layer. [overscroll] is a lazy read of the actual content translation in
 * px: +down/right, -up/left; containers supply it automatically.
 *
 * A [LazyListState] or [LazyGridState] reports accumulated offsets (measured line sizes cached as
 * they scroll past) instead of the platform's visible-average estimate; other states keep their own
 * metrics.
 *
 * [reverseScrolling] is auto-detected for the four lazy states; pass an explicit value for a custom
 * ScrollableState whose positive deltas decrease the normalized offset.
 *
 * [overscrollEffect] is the one the container scrolls with. Passing it makes a bar drag behave like
 * a finger: past either end the content keeps stretching instead of the drag hitting a wall.
 */
@Composable
fun Modifier.kineticScrollIndicator(
    state: ScrollableState,
    orientation: Orientation = Orientation.Vertical,
    spec: ScrollIndicatorSpec = ScrollIndicatorSpec.Default,
    reverseScrolling: Boolean? = null,
    overscroll: () -> Float = { 0f },
    overscrollEffect: OverscrollEffect? = null,
    onDraggingChanged: (Boolean) -> Unit = {},
): Modifier {
    val dragState = remember(state) { ScrollIndicatorDragState() }
    val expansion = animateFloatAsState(
        targetValue = if (dragState.active) 1f else 0f,
        animationSpec = tween(
            if (dragState.active) ScrollIndicatorDefaults.ExpandMillis else ScrollIndicatorDefaults.RetractMillis,
            easing = IndicatorEasing,
        ),
        label = "ScrollIndicatorThickness",
    )
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val haptic = LocalHapticFeedback.current
    val latestReverse by rememberUpdatedState(reverseScrolling)
    val latestDragging by rememberUpdatedState(onDraggingChanged)
    val latestOverscroll by rememberUpdatedState(overscroll)
    val metrics = rememberScrollIndicatorMetrics(state)
    val alpha = rememberScrollIndicatorAlpha(
        isActive = { dragState.active || state.isScrollInProgress || latestOverscroll() != 0f },
        autoHideMillis = spec.autoHideMillis,
        fadeOutMillis = spec.fadeOutMillis,
        fadeInMillis = spec.fadeInMillis,
    )
    val interactive = if (!spec.draggable) this else dragKineticScrollIndicator(
        state = state,
        metrics = metrics,
        dragState = dragState,
        vertical = orientation == Orientation.Vertical,
        rtl = rtl,
        spec = spec,
        expansion = { expansion.value },
        alpha = { alpha.value },
        overscroll = { latestOverscroll() },
        overscrollEffect = overscrollEffect,
        reverseScrolling = { latestReverse ?: defaultReverseScrolling(state) },
        haptic = haptic,
        onDraggingChanged = { latestDragging(it) },
    )
    return interactive.drawKineticScrollIndicator(
        metrics = metrics,
        orientation = orientation,
        spec = spec,
        overscroll = overscroll,
        alpha = { alpha.value },
        expansion = { expansion.value },
    )
}

/** Reverse-layout flag of the lazy states; custom states stay false unless told otherwise. */
internal fun defaultReverseScrolling(state: ScrollableState): Boolean = when (state) {
    is LazyListState -> state.layoutInfo.reverseLayout
    is LazyGridState -> state.layoutInfo.reverseLayout
    is LazyStaggeredGridState -> state.layoutInfo.reverseLayout
    is PagerState -> state.layoutInfo.reverseLayout
    else -> false
}
