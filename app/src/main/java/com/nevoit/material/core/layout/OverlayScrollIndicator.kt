package com.nevoit.material.core.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.nevoit.kinetic.layout.indicator.ScrollIndicatorSpec

internal val NavigationTailHeight = 180.dp

@Composable
fun rememberNavigationTailPadding(): Dp {
    val insets = WindowInsets.navigationBars
    val density = LocalDensity.current
    return with(density) { insets.getBottom(density).toDp() } + NavigationTailHeight
}

@Composable
fun rememberOverlayScrollIndicatorSpec(
    endPadding: Dp,
    color: Color = Color.Unspecified,
): ScrollIndicatorSpec {
    val statusBars = WindowInsets.statusBars
    val density = LocalDensity.current
    val statusBarHeight = with(density) { statusBars.getTop(density).toDp() }
    return remember(statusBarHeight, endPadding, color) {
        ScrollIndicatorSpec(
            startPadding = statusBarHeight + 72.dp,
            endPadding = endPadding,
            color = color,
            shape = Capsule()
        )
    }
}
