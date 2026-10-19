package com.nevoit.kinetic.layout.internal.indicator

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.nevoit.kinetic.layout.indicator.ScrollIndicatorSpec
import com.nevoit.kinetic.layout.indicator.kineticScrollIndicator
import com.nevoit.kinetic.layout.overscroll.OverscrollVisualOffset

/**
 * Attaches the container's built-in indicator. The overscroll translation is read from the
 * container's own OverscrollEffect when that effect reports it, so a caller only has to pass the
 * effect once; the read happens lazily in draw, not in composition.
 */
@Composable
internal fun Modifier.attachKineticScrollIndicator(
    state: ScrollableState,
    vertical: Boolean,
    reverseLayout: Boolean,
    spec: ScrollIndicatorSpec?,
    overscrollEffect: OverscrollEffect?,
): Modifier {
    if (spec == null) return this
    val overscroll: () -> Float = remember(overscrollEffect, vertical) {
        val visual = overscrollEffect as? OverscrollVisualOffset
        if (visual == null) {
            { 0f }
        } else {
            { if (vertical) visual.visualOffset.y else visual.visualOffset.x }
        }
    }
    return kineticScrollIndicator(
        state = state,
        orientation = if (vertical) Orientation.Vertical else Orientation.Horizontal,
        spec = spec,
        reverseScrolling = reverseLayout,
        overscroll = overscroll,
    )
}
