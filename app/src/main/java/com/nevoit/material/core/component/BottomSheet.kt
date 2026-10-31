package com.nevoit.material.core.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.nevoit.material.clip.cornerMaskClip
import com.nevoit.material.navigation.shape.deviceCornerShape
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.tokens.Springs
import kotlinx.coroutines.launch

@Composable
fun BottomSheet(
    onDismissed: () -> Unit,
    onDismissRequest: (slideOut: () -> Unit) -> Unit = { slideOut -> slideOut() },
    content: @Composable BoxScope.(slideOut: () -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val sheetHeight = (LocalWindowInfo.current.containerDpSize.height -
            WindowInsets.statusBars.asPaddingValues().calculateTopPadding()).coerceAtLeast(0.dp)
    val sheetHeightPx = with(density) { sheetHeight.toPx() }
    val offset = remember { Animatable(sheetHeightPx) }
    var visible by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }

    LaunchedEffect(ready, sheetHeightPx) {
        if (ready && !dismissing) {
            visible = true
            if (!entered) {
                offset.animateTo(0f, Springs.snappy(400))
                entered = true
            } else offset.snapTo(0f)
        }
    }

    fun slideOut() {
        if (dismissing) return
        dismissing = true
        visible = false
        scope.launch {
            offset.animateTo(
                sheetHeightPx,
                tween(150, easing = CubicBezierEasing(.4f, 0f, 1f, 1f)),
            )
            onDismissed()
        }
    }

    BackHandler { onDismissRequest(::slideOut) }
    val deviceShape = deviceCornerShape(bottomLeft = 0.dp, bottomRight = 0.dp, minRadius = 24.dp)
    val shape = deviceShape.copy(
        topStart = deviceShape.cornerRadii.topStart.coerceAtMost(36.dp),
        topEnd = deviceShape.cornerRadii.topEnd.coerceAtMost(36.dp),
    )
    val scrimAlpha by animateFloatAsState(if (visible) 0.4f else 0f, tween(200))
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onDismissRequest(::slideOut) },
                )
                .background(Color.Black)
        )
        Box(
            Modifier
                .graphicsLayer { translationY = offset.value }
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
                    layout(placeable.width, constraints.maxHeight) {
                        placeable.place(0, constraints.maxHeight - placeable.height + 32.dp.roundToPx())
                        if (!ready) ready = true
                    }
                }
                .height(sheetHeight + 32.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .cornerMaskClip(shape)
                .background(MaterialTheme.colors.elevatedPageBackground)
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            content(::slideOut)
        }
    }
}
