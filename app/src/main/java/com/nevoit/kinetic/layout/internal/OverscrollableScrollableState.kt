package com.nevoit.kinetic.layout.internal

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollIndicatorState
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState

/**
 * Keeps both range flags open so a container whose content is shorter than its viewport still
 * dispatches drag and fling deltas to the overscroll effect instead of dropping them.
 *
 * Foundation gates that dispatch on canScrollForward/canScrollBackward: with nothing to scroll the
 * delta never reaches the effect, which is why short content has no overscroll. The real state
 * still consumes what it can, so the flags staying true only means "the effect may see the rest".
 *
 * [alwaysOverscroll] is the per-container switch; false restores the Foundation behavior.
 * Everything else delegates. inner is swapped in place, never replaced: ScrollingLogic resets its
 * pointer input whenever the state instance changes, so the wrapper has to outlive every update.
 */
internal class OverscrollableScrollableState(
    var inner: ScrollableState,
    var alwaysOverscroll: Boolean = true,
) : ScrollableState {

    override suspend fun scroll(
        scrollPriority: MutatePriority,
        block: suspend ScrollScope.() -> Unit,
    ) = inner.scroll(scrollPriority, block)

    override fun dispatchRawDelta(delta: Float): Float = inner.dispatchRawDelta(delta)

    override val isScrollInProgress: Boolean get() = inner.isScrollInProgress

    override val canScrollForward: Boolean get() = alwaysOverscroll || inner.canScrollForward

    override val canScrollBackward: Boolean get() = alwaysOverscroll || inner.canScrollBackward

    override val lastScrolledForward: Boolean get() = inner.lastScrolledForward

    override val lastScrolledBackward: Boolean get() = inner.lastScrolledBackward

    override val scrollIndicatorState: ScrollIndicatorState? get() = inner.scrollIndicatorState
}
