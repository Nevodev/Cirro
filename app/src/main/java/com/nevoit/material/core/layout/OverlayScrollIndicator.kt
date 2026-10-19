package com.nevoit.material.core.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nevoit.kinetic.layout.indicator.ScrollIndicatorSpec

/** 列表尾距里固定的那一段（36 + 144）：垫住常驻的 MiniPlayer 和底栏。 */
internal val NavigationTailHeight = 180.dp

/** 列表尾部留白 = 导航栏 inset + [NavigationTailHeight]。 */
@Composable
fun rememberNavigationTailPadding(): Dp {
    val insets = WindowInsets.navigationBars
    val density = LocalDensity.current
    return with(density) { insets.getBottom(density).toDp() } + NavigationTailHeight
}

/**
 * 全站浮层滚动条的轨道几何：顶部让开状态栏 + 72dp（`TopShade` / 浮层按钮的位置），
 * 底部让开内容尾距 [endPadding]，轨道两端都正好停在内容区的边缘。
 */
@Composable
fun rememberOverlayScrollIndicatorSpec(endPadding: Dp): ScrollIndicatorSpec {
    val statusBars = WindowInsets.statusBars
    val density = LocalDensity.current
    val statusBarHeight = with(density) { statusBars.getTop(density).toDp() }
    return remember(statusBarHeight, endPadding) {
        ScrollIndicatorSpec(
            startPadding = statusBarHeight + 72.dp,
            endPadding = endPadding,
        )
    }
}
