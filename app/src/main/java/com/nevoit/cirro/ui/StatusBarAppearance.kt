package com.nevoit.cirro.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.nevoit.cirro.playback.PlayerExpansionState

@Composable
internal fun StatusBarAppearance(darkIcons: Boolean, player: PlayerExpansionState) {
    val window = LocalActivity.current?.window
    val view = LocalView.current
    val statusBarHeight = WindowInsets.statusBars.getTop(LocalDensity.current)
    val pageDarkIcons by rememberUpdatedState(darkIcons)
    LaunchedEffect(window, view, player, statusBarHeight) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) } ?: return@LaunchedEffect
        val original = controller.isAppearanceLightStatusBars
        try {
            // 一个写入点处理页面和播放页的优先级，避免播放页关闭时恢复过时的封面颜色。
            snapshotFlow {
                if (player.overlapsStatusBar(statusBarHeight)) false else pageDarkIcons
            }.collect { controller.isAppearanceLightStatusBars = it }
        } finally {
            controller.isAppearanceLightStatusBars = original
        }
    }
}
