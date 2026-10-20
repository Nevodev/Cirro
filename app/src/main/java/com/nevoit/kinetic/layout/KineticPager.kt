/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "CANNOT_OVERRIDE_INVISIBLE_MEMBER", "EXPOSED_SUPER_CLASS", "EXPOSED_PROPERTY_TYPE", "EXPOSED_PARAMETER_TYPE")

package com.nevoit.kinetic.layout

import com.nevoit.kinetic.layout.internal.lazy.ClickThroughLazyList
import com.nevoit.kinetic.layout.internal.lazy.grid.ClickThroughLazyGrid
import com.nevoit.kinetic.layout.internal.lazy.staggeredgrid.ClickThroughLazyStaggeredGrid
import com.nevoit.kinetic.layout.internal.pager.ClickThroughPager
import com.nevoit.kinetic.layout.indicator.ScrollIndicatorSpec
import com.nevoit.kinetic.layout.internal.indicator.attachKineticScrollIndicator

import androidx.annotation.FloatRange
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.ComposeFoundationFlags.isReverseLayoutNestedScrollConnectionInPagerFixEnabled
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.calculateFinalSnappingBound
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.foundation.internal.requirePrecondition
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.pageDown
import androidx.compose.ui.semantics.pageLeft
import androidx.compose.ui.semantics.pageRight
import androidx.compose.ui.semantics.pageUp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

import androidx.compose.foundation.pager.*
import com.nevoit.kinetic.layout.internal.gestures.snapping.TrackedSnapFlingBehavior
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun KineticLazyHorizontalPager(
    state: PagerState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    pageSize: PageSize = PageSize.Fill,
    beyondViewportPageCount: Int = PagerDefaults.BeyondViewportPageCount,
    pageSpacing: Dp = 0.dp,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    flingBehavior: TargetedFlingBehavior = rememberKineticPagerFlingBehavior(state = state),
    userScrollEnabled: Boolean = true,
    reverseLayout: Boolean = false,
    key: ((index: Int) -> Any)? = null,
    pageNestedScrollConnection: NestedScrollConnection =
        PagerDefaults.pageNestedScrollConnection(state, Orientation.Horizontal),
    snapPosition: SnapPosition = SnapPosition.Start,
    overscrollEffect: OverscrollEffect? = rememberOverscrollEffect(),
    /** Content shorter than the viewport still stretches; false restores the Foundation behavior. */
    alwaysOverscroll: Boolean = true,
    scrollIndicator: ScrollIndicatorSpec? = ScrollIndicatorSpec.Default,
    clickVelocityThreshold: Dp = 40.dp,
    pageContent: @Composable PagerScope.(page: Int) -> Unit,
) {

    require(clickVelocityThreshold.value.isFinite() && clickVelocityThreshold.value >= 0f)
    CompositionLocalProvider(LocalLowSpeedClickThreshold provides clickVelocityThreshold) {
        ClickThroughPager(
            state = state,
            modifier = modifier.attachKineticScrollIndicator(
                state = state,
                vertical = false,
                reverseLayout = reverseLayout,
                spec = scrollIndicator,
                overscrollEffect = overscrollEffect,
            ),
            contentPadding = contentPadding,
            pageSize = pageSize,
            beyondViewportPageCount = beyondViewportPageCount,
            pageSpacing = pageSpacing,
            orientation = Orientation.Horizontal,
            verticalAlignment = verticalAlignment,
            horizontalAlignment = Alignment.CenterHorizontally,
            flingBehavior = flingBehavior,
            userScrollEnabled = userScrollEnabled,
            reverseLayout = reverseLayout,
            key = key,
            pageNestedScrollConnection = pageNestedScrollConnection,
            snapPosition = snapPosition,
            overscrollEffect = overscrollEffect,
            alwaysOverscroll = alwaysOverscroll,
            pageContent = pageContent,
        )
    }
}

@Composable
fun KineticLazyVerticalPager(
    state: PagerState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    pageSize: PageSize = PageSize.Fill,
    beyondViewportPageCount: Int = PagerDefaults.BeyondViewportPageCount,
    pageSpacing: Dp = 0.dp,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    flingBehavior: TargetedFlingBehavior = rememberKineticPagerFlingBehavior(state = state),
    userScrollEnabled: Boolean = true,
    reverseLayout: Boolean = false,
    key: ((index: Int) -> Any)? = null,
    pageNestedScrollConnection: NestedScrollConnection =
        PagerDefaults.pageNestedScrollConnection(state, Orientation.Vertical),
    snapPosition: SnapPosition = SnapPosition.Start,
    overscrollEffect: OverscrollEffect? = rememberOverscrollEffect(),
    /** Content shorter than the viewport still stretches; false restores the Foundation behavior. */
    alwaysOverscroll: Boolean = true,
    scrollIndicator: ScrollIndicatorSpec? = ScrollIndicatorSpec.Default,
    clickVelocityThreshold: Dp = 40.dp,
    pageContent: @Composable PagerScope.(page: Int) -> Unit,
) {

    require(clickVelocityThreshold.value.isFinite() && clickVelocityThreshold.value >= 0f)
    CompositionLocalProvider(LocalLowSpeedClickThreshold provides clickVelocityThreshold) {
        ClickThroughPager(
            state = state,
            modifier = modifier.attachKineticScrollIndicator(
                state = state,
                vertical = true,
                reverseLayout = reverseLayout,
                spec = scrollIndicator,
                overscrollEffect = overscrollEffect,
            ),
            contentPadding = contentPadding,
            pageSize = pageSize,
            beyondViewportPageCount = beyondViewportPageCount,
            pageSpacing = pageSpacing,
            orientation = Orientation.Vertical,
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = horizontalAlignment,
            flingBehavior = flingBehavior,
            userScrollEnabled = userScrollEnabled,
            reverseLayout = reverseLayout,
            key = key,
            pageNestedScrollConnection = pageNestedScrollConnection,
            snapPosition = snapPosition,
            overscrollEffect = overscrollEffect,
            alwaysOverscroll = alwaysOverscroll,
            pageContent = pageContent,
        )
    }
}

@Composable
fun rememberKineticPagerFlingBehavior(
        state: PagerState,
        pagerSnapDistance: PagerSnapDistance = PagerSnapDistance.atMost(1),
        decayAnimationSpec: DecayAnimationSpec<Float> = rememberSplineBasedDecay(),
        snapAnimationSpec: AnimationSpec<Float> =
            spring(
                stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = Int.VisibilityThreshold.toFloat(),
            ),
        @FloatRange(from = 0.0, to = 1.0) snapPositionalThreshold: Float = 0.5f,
    ): TargetedFlingBehavior {
        requirePrecondition(snapPositionalThreshold in 0f..1f) {
            "snapPositionalThreshold should be a number between 0 and 1. " +
                "You've specified $snapPositionalThreshold"
        }
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        return remember(
            state,
            decayAnimationSpec,
            snapAnimationSpec,
            pagerSnapDistance,
            density,
            layoutDirection,
        ) {
            val snapLayoutInfoProvider =
                SnapLayoutInfoProvider(state, pagerSnapDistance) {
                    flingVelocity,
                    lowerBound,
                    upperBound ->
                    calculateFinalSnappingBound(
                        pagerState = state,
                        layoutDirection = layoutDirection,
                        snapPositionalThreshold = snapPositionalThreshold,
                        flingVelocity = flingVelocity,
                        lowerBoundOffset = lowerBound,
                        upperBoundOffset = upperBound,
                    )
                }

            TrackedSnapFlingBehavior(
                snapLayoutInfoProvider = snapLayoutInfoProvider,
                decayAnimationSpec = decayAnimationSpec,
                snapAnimationSpec = snapAnimationSpec,
            )
        }
    }
