package com.nevoit.material.core.layout

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nevoit.kinetic.layout.KineticLazyColumn
import com.nevoit.kinetic.layout.indicator.ScrollIndicatorSpec
import com.nevoit.kinetic.layout.overscroll.OverscrollVisualOffset
import com.nevoit.kinetic.layout.overscroll.rememberOffsetOverscrollEffect

/**
 * 全站唯一的纵向列表容器。
 *
 * 默认已经带好两件事，页面不用再自己垫：
 * - 尾距 = 导航栏 inset + 180dp（36 + 144，垫住常驻的 MiniPlayer 和底栏，跟首页同一个约定）；
 *   走 `contentPadding` 而不是末尾塞一个 `navigationBarsPadding()` 的 item —— item 组合晚于
 *   第一帧布局，inset 下发前会算成 0 而错位。
 * - 滚动条轨道让开顶部（状态栏 + 72dp，`TopShade` / 浮层按钮的位置）和底部的那段尾距。
 */
@Composable
fun VerticalStack(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    /** 传 null 用默认尾距；传值则完全接管（不会再叠加导航栏 inset）。 */
    contentPadding: PaddingValues? = null,
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical =
        if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    userScrollEnabled: Boolean = true,
    overscrollEffect: OverscrollEffect? = null,
    /** 传 null 用默认（轨道让开顶栏与尾距）。 */
    scrollIndicator: ScrollIndicatorSpec? = null,
    content: LazyListScope.() -> Unit
) {
    val effect = overscrollEffect ?: rememberOffsetOverscrollEffect(state)

    val navigationBarInsets = WindowInsets.navigationBars
    val bottomTail = remember { WindowInsets(bottom = NavigationTailHeight) }
    val defaultContentPadding = remember(navigationBarInsets, bottomTail) {
        navigationBarInsets.add(bottomTail)
    }.asPaddingValues()
    val resolvedContentPadding = contentPadding ?: defaultContentPadding
    val defaultScrollIndicator = rememberOverlayScrollIndicatorSpec(
        resolvedContentPadding.calculateBottomPadding()
    )

    CompositionLocalProvider(
        LocalOverscrollVisualOffset provides (effect as? OverscrollVisualOffset),
    ) {
        KineticLazyColumn(
            modifier = modifier,
            state = state,
            contentPadding = resolvedContentPadding,
            reverseLayout = reverseLayout,
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            userScrollEnabled = userScrollEnabled,
            overscrollEffect = effect,
            scrollIndicator = scrollIndicator ?: defaultScrollIndicator,
        ) {
            content()
            paddingItem(state)
        }
    }
}
