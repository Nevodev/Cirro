package com.nevoit.cirro.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nevoit.material.core.layout.isScrolledPast
import com.nevoit.material.core.modifier.smoothGradient

/** 常驻层（MiniPlayer + 底栏 + 上下留白）在导航条 inset 之外占的高度。 */
private val BottomShadeContentHeight = 156.dp

/**
 * 页面自己的底部 shade：MiniPlayer + 底栏底下那道往页面底色里融的渐变。
 *
 * shade 固定在页面内而不挂在常驻层上——每页拿自己的底色画，详情页进来
 * shade 自然是封面色，返回时也跟着页面一起滑走。渐变参数与原常驻 shade 一致。
 */
@Composable
fun BoxScope.BottomShade(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .smoothGradient(
                color = color,
                start = 0f,
                end = 0.7f,
                intensity = 0.8f,
            )
            .navigationBarsPadding()
            .height(BottomShadeContentHeight)

    )
}

@Composable
fun BoxScope.TopShade(
    color: Color,
    alpha: () -> Float = { 1f },
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = Modifier
            .graphicsLayer {
                this.alpha = alpha()
            }
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .smoothGradient(
                color = color,
                start = 1f,
                end = 0.5f,
                intensity = 0.8f,
            )
            .statusBarsPadding()
            .then(modifier)
    )
}

@Composable
fun BoxScope.TopShade(
    color: Color,
    listState: LazyListState,
    threshold: (PaddingValues) -> Dp,
    modifier: Modifier = Modifier,
) {
    val windowInsets = WindowInsets.statusBars.asPaddingValues()
    val showTopShade by listState.isScrolledPast(threshold(windowInsets))
    val topShapeAlpha by animateFloatAsState(
        targetValue = if (showTopShade) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "topShadeAlpha",
    )

    Box(
        modifier = Modifier
            .graphicsLayer {
                this.alpha = topShapeAlpha
            }
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .smoothGradient(
                color = color,
                start = 1f,
                end = 0.5f,
                intensity = 0.8f,
            )
            .statusBarsPadding()
            .then(modifier)
    )
}

@Composable
fun BoxScope.TopShade(
    color: Color,
    listState: LazyGridState,
    threshold: (PaddingValues) -> Dp,
    modifier: Modifier = Modifier,
) {
    val windowInsets = WindowInsets.statusBars.asPaddingValues()
    val showTopShade by listState.isScrolledPast(threshold(windowInsets))
    val topShapeAlpha by animateFloatAsState(
        targetValue = if (showTopShade) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "topShadeAlpha",
    )

    TopShade(color = color, alpha = { topShapeAlpha }, modifier = modifier)
}
