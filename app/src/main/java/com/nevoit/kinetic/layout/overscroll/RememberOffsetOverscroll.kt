package com.nevoit.kinetic.layout.overscroll

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs

/** Item key and main-axis layout offset, BEFORE reverseLayout / horizontal RTL placement. */
data class OffsetOverscrollAnchor(val key: Any, val offset: Int)

/**
 * Adapter for custom scroll containers, including custom LazyLayout implementations.
 * State must use positive scroll for forward movement. All getters run on the UI thread.
 * layoutInfo must read snapshot-observable layout state and change when scroll range changes.
 * Anchors must use stable keys and logical main-axis offsets (before reverse / RTL placement).
 * Do not return sticky/non-scrolling or animated-placement anchors.
 */
interface OffsetOverscrollBinding {
    val state: ScrollableState
    val orientation: Orientation
    val reverseLayout: Boolean
    val layoutInfo: Any?
    fun visibleAnchors(): List<OffsetOverscrollAnchor>
}

/** Works with both LazyColumn and LazyRow; orientation/reversal come from measured layout. */
@Composable
fun rememberOffsetOverscrollEffect(
    state: LazyListState,
    animationSpec: AnimationSpec<Float> = OffsetOverscrollEffect.DefaultAnimationSpec,
    maxFraction: Float = 0.55f,
): OffsetOverscrollEffect = rememberOffsetOverscrollEffect(remember(state) {
    object : OffsetOverscrollBinding {
        override val state: ScrollableState = state
        override val orientation get() = state.layoutInfo.orientation
        override val reverseLayout get() = state.layoutInfo.reverseLayout
        override val layoutInfo get() = state.layoutInfo
        override fun visibleAnchors() = state.layoutInfo.visibleItemsInfo
            .map { OffsetOverscrollAnchor(it.key, it.offset) }
    }
}, animationSpec, maxFraction)

/** Works with LazyVerticalGrid and LazyHorizontalGrid. */
@Composable
fun rememberOffsetOverscrollEffect(
    state: LazyGridState,
    animationSpec: AnimationSpec<Float> = OffsetOverscrollEffect.DefaultAnimationSpec,
    maxFraction: Float = 0.55f,
): OffsetOverscrollEffect = rememberOffsetOverscrollEffect(remember(state) {
    object : OffsetOverscrollBinding {
        override val state: ScrollableState = state
        override val orientation get() = state.layoutInfo.orientation
        override val reverseLayout get() = state.layoutInfo.reverseLayout
        override val layoutInfo get() = state.layoutInfo
        override fun visibleAnchors() = state.layoutInfo.visibleItemsInfo
            .filter { it.row != -1 && it.column != -1 }
            .map {
                OffsetOverscrollAnchor(
                    it.key,
                    if (orientation == Orientation.Vertical) it.offset.y else it.offset.x
                )
            }
    }
}, animationSpec, maxFraction)

/** Works with LazyVerticalStaggeredGrid and LazyHorizontalStaggeredGrid. */
@Composable
fun rememberOffsetOverscrollEffect(
    state: LazyStaggeredGridState,
    animationSpec: AnimationSpec<Float> = OffsetOverscrollEffect.DefaultAnimationSpec,
    maxFraction: Float = 0.55f,
): OffsetOverscrollEffect = rememberOffsetOverscrollEffect(remember(state) {
    object : OffsetOverscrollBinding {
        override val state: ScrollableState = state
        override val orientation get() = state.layoutInfo.orientation
        override val reverseLayout get() = state.layoutInfo.reverseLayout
        override val layoutInfo get() = state.layoutInfo
        override fun visibleAnchors() = state.layoutInfo.visibleItemsInfo.map {
            OffsetOverscrollAnchor(
                it.key,
                if (orientation == Orientation.Vertical) it.offset.y else it.offset.x
            )
        }
    }
}, animationSpec, maxFraction)

/** Works with HorizontalPager and VerticalPager; normal snapping after release is retained. */
@Composable
fun rememberOffsetOverscrollEffect(
    state: PagerState,
    animationSpec: AnimationSpec<Float> = OffsetOverscrollEffect.DefaultAnimationSpec,
    maxFraction: Float = 0.55f,
): OffsetOverscrollEffect = rememberOffsetOverscrollEffect(remember(state) {
    object : OffsetOverscrollBinding {
        override val state: ScrollableState = state
        override val orientation get() = state.layoutInfo.orientation
        override val reverseLayout get() = state.layoutInfo.reverseLayout
        override val layoutInfo get() = state.layoutInfo
        override fun visibleAnchors() =
            state.layoutInfo.visiblePagesInfo.map { OffsetOverscrollAnchor(it.key, it.offset) }
    }
}, animationSpec, maxFraction)

/** Use an explicit binding for a custom LazyLayout; built-in containers use the overloads above. */
@Composable
fun rememberOffsetOverscrollEffect(
    binding: OffsetOverscrollBinding,
    animationSpec: AnimationSpec<Float> = OffsetOverscrollEffect.DefaultAnimationSpec,
    maxFraction: Float = 0.55f,
): OffsetOverscrollEffect {
    require(maxFraction > 0f && maxFraction.isFinite())
    val scope = rememberCoroutineScope()
    val layoutDirection = LocalLayoutDirection.current
    val orientation by remember(binding) { derivedStateOf { binding.orientation } }
    val reverseLayout by remember(binding) { derivedStateOf { binding.reverseLayout } }
    val effect = remember(
        binding,
        orientation,
        reverseLayout,
        layoutDirection,
        scope,
        animationSpec,
        maxFraction
    ) {
        OffsetOverscrollEffect(scope, animationSpec, maxFraction).also { effect ->
            effect.scrollForRebase = { visual ->
                val horizontal = binding.orientation == Orientation.Horizontal
                val reversed =
                    binding.reverseLayout xor (horizontal && layoutDirection == LayoutDirection.Rtl)
                val direction = if (reversed) 1f else -1f
                val requested = (if (horizontal) visual.x else visual.y) * direction
                val state = binding.state
                if ((requested > 0f && !state.canScrollForward) ||
                    (requested < 0f && !state.canScrollBackward) || requested == 0f
                ) {
                    Offset.Zero
                } else {
                    // Coordinate migration must not cancel the held gesture or redistribute
                    // this same displacement through nested scroll / overscroll a second time.
                    val anchors = binding.visibleAnchors()
                    val reported = state.dispatchRawDelta(requested)
                    val after = binding.visibleAnchors()
                    // Prefer the scrolling item closest to reported movement, rather than
                    // accidentally using a pinned header (whose offset may stay fixed).
                    val actual = anchors.mapNotNull { old ->
                        after.firstOrNull { it.key == old.key }
                            ?.let { (old.offset - it.offset).toFloat() }
                    }.minByOrNull { abs(it - reported) } ?: reported
                    if (horizontal) Offset(actual * direction, 0f) else Offset(
                        0f,
                        actual * direction
                    )
                }
            }
        }
    }
    LaunchedEffect(binding, effect) {
        // Avoid observing every scroll frame when there is no stretch to migrate.
        snapshotFlow { if (effect.hasStretch) binding.layoutInfo else null }
            .collect { if (it != null) effect.rebaseToScroll() }
    }
    return effect
}
