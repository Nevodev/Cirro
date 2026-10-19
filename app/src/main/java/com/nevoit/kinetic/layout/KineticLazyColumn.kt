@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.nevoit.kinetic.layout

import com.nevoit.kinetic.layout.internal.lazy.ClickThroughLazyList
import com.nevoit.kinetic.layout.internal.lazy.grid.ClickThroughLazyGrid
import com.nevoit.kinetic.layout.internal.lazy.staggeredgrid.ClickThroughLazyStaggeredGrid
import com.nevoit.kinetic.layout.internal.pager.ClickThroughPager
import com.nevoit.kinetic.layout.indicator.ScrollIndicatorSpec
import com.nevoit.kinetic.layout.internal.indicator.attachKineticScrollIndicator

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Optional contract for a custom FlingBehavior that supports low-speed clicks.
 * Read/written on the UI thread. Velocity is px/s in the fling's scroll coordinate system;
 * null means no active velocity sample. Report the actual velocity before each scrollBy.
 * A behavior without this contract retains the official tap-to-stop policy.
 */
interface FlingVelocityProvider {
    val currentFlingVelocity: Float?
}

internal val LocalLowSpeedClickThreshold = staticCompositionLocalOf { 40.dp }

internal fun canClickThroughFling(
    isFlinging: Boolean,
    overscrollInProgress: Boolean,
    velocity: Float?,
    threshold: Float,
): Boolean = isFlinging && !overscrollInProgress && threshold.isFinite() && threshold > 0f &&
    velocity != null && velocity.isFinite() && abs(velocity) <= threshold

/**
 * Android LazyColumn, pinned to Foundation 1.12.1, with one touch policy change:
 * a DOWN during a fling at <= [clickVelocityThreshold] stops the fling without taking
 * ownership of that press. The item's original clickable/combinedClickable handles it.
 *
 * [clickVelocityThreshold] expresses dp per second; 0.dp restores the official policy.
 * Overscroll, programmatic scrolling, and unknown custom velocities keep official handling.
 * [scrollIndicator] draws and drives the built-in scroll bar; it reads the container's own
 * overscroll translation, so no extra parameter is needed. Pass null to turn it off.
 * This is not a replacement Foundation artifact; official LazyColumn can coexist with it.
 */
@Composable
fun KineticLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical =
        if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    flingBehavior: FlingBehavior = rememberKineticFlingBehavior(),
    userScrollEnabled: Boolean = true,
    overscrollEffect: OverscrollEffect? = rememberOverscrollEffect(),
    scrollIndicator: ScrollIndicatorSpec? = ScrollIndicatorSpec.Default,
    clickVelocityThreshold: Dp = 40.dp,
    content: LazyListScope.() -> Unit,
) {
    require(clickVelocityThreshold.value.isFinite() && clickVelocityThreshold.value >= 0f) {
        "clickVelocityThreshold must be finite and non-negative (dp/s)."
    }
    CompositionLocalProvider(LocalLowSpeedClickThreshold provides clickVelocityThreshold) {
        ClickThroughLazyList(
            modifier = modifier.attachKineticScrollIndicator(
                state = state,
                vertical = true,
                reverseLayout = reverseLayout,
                spec = scrollIndicator,
                overscrollEffect = overscrollEffect,
            ),
            state = state,
            contentPadding = contentPadding,
            reverseLayout = reverseLayout,
            isVertical = true,
            flingBehavior = flingBehavior,
            userScrollEnabled = userScrollEnabled,
            overscrollEffect = overscrollEffect,
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/** Horizontal counterpart with the same upstream layout and low-speed touch policy. */
@Composable
fun KineticLazyRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    horizontalArrangement: Arrangement.Horizontal =
        if (!reverseLayout) Arrangement.Start else Arrangement.End,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    flingBehavior: FlingBehavior = rememberKineticFlingBehavior(),
    userScrollEnabled: Boolean = true,
    overscrollEffect: OverscrollEffect? = rememberOverscrollEffect(),
    scrollIndicator: ScrollIndicatorSpec? = ScrollIndicatorSpec.Default,
    clickVelocityThreshold: Dp = 40.dp,
    content: LazyListScope.() -> Unit,
) {
    require(clickVelocityThreshold.value.isFinite() && clickVelocityThreshold.value >= 0f) {
        "clickVelocityThreshold must be finite and non-negative (dp/s)."
    }
    CompositionLocalProvider(LocalLowSpeedClickThreshold provides clickVelocityThreshold) {
        ClickThroughLazyList(
            modifier = modifier.attachKineticScrollIndicator(
                state = state,
                vertical = false,
                reverseLayout = reverseLayout,
                spec = scrollIndicator,
                overscrollEffect = overscrollEffect,
            ),
            state = state,
            contentPadding = contentPadding,
            reverseLayout = reverseLayout,
            isVertical = false,
            flingBehavior = flingBehavior,
            userScrollEnabled = userScrollEnabled,
            overscrollEffect = overscrollEffect,
            verticalAlignment = verticalAlignment,
            horizontalArrangement = horizontalArrangement,
            content = content,
        )
    }
}
