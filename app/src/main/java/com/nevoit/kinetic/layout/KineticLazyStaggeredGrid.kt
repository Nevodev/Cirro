/*
 * Copyright 2022 The Android Open Source Project
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

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.internal.requirePrecondition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun KineticLazyVerticalStaggeredGrid(
    columns: StaggeredGridCells,
    modifier: Modifier = Modifier,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalItemSpacing: Dp = 0.dp,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(0.dp),
    flingBehavior: FlingBehavior = rememberKineticFlingBehavior(),
    userScrollEnabled: Boolean = true,
    overscrollEffect: OverscrollEffect? = rememberOverscrollEffect(),
    scrollIndicator: ScrollIndicatorSpec? = ScrollIndicatorSpec.Default,
    clickVelocityThreshold: Dp = 40.dp,
    content: LazyStaggeredGridScope.() -> Unit,
) {

    require(clickVelocityThreshold.value.isFinite() && clickVelocityThreshold.value >= 0f)
    CompositionLocalProvider(LocalLowSpeedClickThreshold provides clickVelocityThreshold) {
        ClickThroughLazyStaggeredGrid(
            modifier = modifier.attachKineticScrollIndicator(
                state = state,
                vertical = true,
                reverseLayout = reverseLayout,
                spec = scrollIndicator,
                overscrollEffect = overscrollEffect,
            ),
            orientation = Orientation.Vertical,
            state = state,
            contentPadding = contentPadding,
            reverseLayout = reverseLayout,
            mainAxisSpacing = verticalItemSpacing,
            crossAxisSpacing = horizontalArrangement.spacing,
            flingBehavior = flingBehavior,
            userScrollEnabled = userScrollEnabled,
            overscrollEffect = overscrollEffect,
            slots = rememberColumnSlots(columns, horizontalArrangement, contentPadding),
            content = content,
        )
    }
}

@Composable
fun KineticLazyHorizontalStaggeredGrid(
    rows: StaggeredGridCells,
    modifier: Modifier = Modifier,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(0.dp),
    horizontalItemSpacing: Dp = 0.dp,
    flingBehavior: FlingBehavior = rememberKineticFlingBehavior(),
    userScrollEnabled: Boolean = true,
    overscrollEffect: OverscrollEffect? = rememberOverscrollEffect(),
    scrollIndicator: ScrollIndicatorSpec? = ScrollIndicatorSpec.Default,
    clickVelocityThreshold: Dp = 40.dp,
    content: LazyStaggeredGridScope.() -> Unit,
) {

    require(clickVelocityThreshold.value.isFinite() && clickVelocityThreshold.value >= 0f)
    CompositionLocalProvider(LocalLowSpeedClickThreshold provides clickVelocityThreshold) {
        ClickThroughLazyStaggeredGrid(
            modifier = modifier.attachKineticScrollIndicator(
                state = state,
                vertical = false,
                reverseLayout = reverseLayout,
                spec = scrollIndicator,
                overscrollEffect = overscrollEffect,
            ),
            orientation = Orientation.Horizontal,
            state = state,
            contentPadding = contentPadding,
            reverseLayout = reverseLayout,
            mainAxisSpacing = horizontalItemSpacing,
            crossAxisSpacing = verticalArrangement.spacing,
            flingBehavior = flingBehavior,
            userScrollEnabled = userScrollEnabled,
            overscrollEffect = overscrollEffect,
            slots = rememberRowSlots(rows, verticalArrangement, contentPadding),
            content = content,
        )
    }
}

@Composable
private fun rememberColumnSlots(
    columns: StaggeredGridCells,
    horizontalArrangement: Arrangement.Horizontal,
    contentPadding: PaddingValues,
) =
    remember<LazyGridStaggeredGridSlotsProvider>(columns, horizontalArrangement, contentPadding) {
        LazyStaggeredGridSlotCache { constraints ->
            requirePrecondition(constraints.maxWidth != Constraints.Infinity) {
                "LazyVerticalStaggeredGrid's width should be bound by parent."
            }
            val horizontalPadding =
                contentPadding.calculateStartPadding(LayoutDirection.Ltr) +
                    contentPadding.calculateEndPadding(LayoutDirection.Ltr)
            val gridWidth = constraints.maxWidth - horizontalPadding.roundToPx()
            with(columns) {
                calculateCrossAxisCellSizes(gridWidth, horizontalArrangement.spacing.roundToPx())
                    .let { sizes ->
                        val positions = IntArray(sizes.size)
                        with(horizontalArrangement) {
                            // Arrange with Ltr here, as placement will reverse positions if needed
                            arrange(gridWidth, sizes, LayoutDirection.Ltr, positions)
                        }
                        LazyStaggeredGridSlots(positions, sizes)
                    }
            }
        }
    }

@Composable
private fun rememberRowSlots(
    rows: StaggeredGridCells,
    verticalArrangement: Arrangement.Vertical,
    contentPadding: PaddingValues,
) =
    remember<LazyGridStaggeredGridSlotsProvider>(rows, verticalArrangement, contentPadding) {
        LazyStaggeredGridSlotCache { constraints ->
            requirePrecondition(constraints.maxHeight != Constraints.Infinity) {
                "LazyHorizontalStaggeredGrid's height should be bound by parent."
            }
            val verticalPadding =
                contentPadding.calculateTopPadding() + contentPadding.calculateBottomPadding()
            val gridHeight = constraints.maxHeight - verticalPadding.roundToPx()
            with(rows) {
                calculateCrossAxisCellSizes(gridHeight, verticalArrangement.spacing.roundToPx())
                    .let { sizes ->
                        val positions = IntArray(sizes.size)
                        with(verticalArrangement) { arrange(gridHeight, sizes, positions) }
                        LazyStaggeredGridSlots(positions, sizes)
                    }
            }
        }
    }

/** measurement cache to avoid recalculating row/column sizes on each scroll. */
private class LazyStaggeredGridSlotCache(
    private val calculation: Density.(Constraints) -> LazyStaggeredGridSlots
) : LazyGridStaggeredGridSlotsProvider {
    private var cachedConstraints = Constraints()
    private var cachedDensity: Float = 0f
    private var cachedSizes: LazyStaggeredGridSlots? = null

    override fun invoke(density: Density, constraints: Constraints): LazyStaggeredGridSlots {
        with(density) {
            if (
                cachedSizes != null &&
                    cachedConstraints == constraints &&
                    cachedDensity == this.density
            ) {
                return cachedSizes!!
            }

            cachedConstraints = constraints
            cachedDensity = this.density
            return calculation(constraints).also { cachedSizes = it }
        }
    }
}

