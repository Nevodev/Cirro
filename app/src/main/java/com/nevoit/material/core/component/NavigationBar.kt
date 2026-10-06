package com.nevoit.material.core.component

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.nevoit.material.core.modifier.drawBackground
import com.nevoit.material.core.modifier.thenIf
import com.nevoit.material.core.utility.rememberColorProducer
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.tokens.Springs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val NavigationBarHeight = 64.dp
private val NavigationBarIndicatorInset = 4.dp

private const val NavigationBarPressedIconScale = 0.85f

@Immutable
class NavigationBarItem(
    val icon: Painter,
    val label: String,
    val selectedIcon: Painter = icon,
)

@Composable
fun NavigationBar(
    items: List<NavigationBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: ColorProducer = rememberColorProducer(MaterialTheme.colors.cardBackground),
    indicatorColor: ColorProducer = rememberColorProducer(MaterialTheme.colors.navigationBarIndicator),
    activeIconColor: ColorProducer = rememberColorProducer(MaterialTheme.colors.onNavigationBarIndicator),
    inactiveIconColor: ColorProducer = rememberColorProducer(MaterialTheme.colors.navigationBarInactiveIcon),
    height: Dp = NavigationBarHeight,
    indicatorInset: Dp = NavigationBarIndicatorInset,
) {
    if (items.isEmpty()) return

    val lastIndex = items.lastIndex
    val scope = rememberCoroutineScope()
    val indicatorShape = remember { Capsule() }

    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)

    var isDragging by remember { mutableStateOf(false) }
    var releaseVelocity by remember { mutableFloatStateOf(0f) }

    val leftEdge = remember { Animatable(selectedIndex.toFloat()) }
    val rightEdge = remember { Animatable(selectedIndex + 1f) }

    val leadingSpec = Springs.snappy(durationMillis = 300, visibilityThreshold = 0.0001f)
    val trailingSpec = Springs.snappy(durationMillis = 400, visibilityThreshold = 0.0001f)
    val followSpec = Springs.smooth(durationMillis = 200, visibilityThreshold = 0.0001f)

    LaunchedEffect(selectedIndex, isDragging) {
        if (isDragging) return@LaunchedEffect
        val velocity = releaseVelocity
        releaseVelocity = 0f
        val targetLeft = selectedIndex.toFloat()
        val targetRight = targetLeft + 1f
        val leftSpeed = approachSpeed(leftEdge.value, targetLeft, velocity)
        val rightSpeed = approachSpeed(rightEdge.value, targetRight, velocity)
        val movingRight = velocity > 0f || (velocity == 0f && targetLeft > leftEdge.value)
        if (movingRight) {
            launch {
                rightEdge.animateTo(targetRight, leadingSpec, initialVelocity = rightSpeed)
            }
            leftEdge.animateTo(targetLeft, trailingSpec, initialVelocity = leftSpeed)
        } else {
            launch {
                leftEdge.animateTo(targetLeft, leadingSpec, initialVelocity = leftSpeed)
            }
            rightEdge.animateTo(targetRight, trailingSpec, initialVelocity = rightSpeed)
        }
    }

    Box(
        modifier = modifier
            .clip(indicatorShape)
            .drawBackground(containerColor)
            .height(height)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarIndicator(
                    leftEdge = leftEdge,
                    rightEdge = rightEdge,
                    itemCount = items.size,
                    shape = indicatorShape,
                    color = indicatorColor,
                    inset = indicatorInset
                )
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                NavigationBarButton(
                    item = item,
                    selected = selected,
                    onClick = { onSelect(index) },
                    iconColor = if (selected) activeIconColor else inactiveIconColor,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .thenIf(selected) {
                            pointerInput(lastIndex) {
                                var anchor = 0f
                                var lastTime = 0L
                                var velocity = 0f
                                var lead = 1f
                                var moved = false
                                detectDragGestures(
                                    onDragStart = {
                                        anchor = leftEdge.value
                                        lastTime = 0L
                                        velocity = 0f
                                        lead = 1f
                                        moved = false
                                        isDragging = true
                                    },
                                    onDrag = { change, amount ->
                                        val cell = size.width.toFloat()
                                        if (cell > 0f) {
                                            val dt = change.uptimeMillis - lastTime
                                            velocity = when {
                                                lastTime == 0L -> 0f
                                                dt <= 0L -> velocity
                                                dt > 120L -> 0f
                                                else -> velocity * 0.6f +
                                                        amount.x / cell * 1000f / dt * 0.4f
                                            }
                                            lastTime = change.uptimeMillis
                                            lead = when {
                                                velocity > 0.1f -> 1f
                                                velocity < -0.1f -> -1f
                                                else -> lead
                                            }
                                            anchor = (anchor + amount.x / cell)
                                                .coerceIn(0f, lastIndex.toFloat())
                                            moved = true
                                            val slot = anchor
                                            val side = lead
                                            scope.launch {
                                                if (side > 0f) {
                                                    rightEdge.snapTo(slot + 1f)
                                                    leftEdge.animateTo(slot, followSpec)
                                                } else {
                                                    leftEdge.snapTo(slot)
                                                    rightEdge.animateTo(
                                                        slot + 1f,
                                                        followSpec
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        val target = if (moved) {
                                            anchor.roundToInt().coerceIn(0, lastIndex)
                                        } else {
                                            currentSelectedIndex
                                        }
                                        releaseVelocity =
                                            if (SystemClock.uptimeMillis() - lastTime > 100L) {
                                                0f
                                            } else {
                                                velocity
                                            }
                                        if (target != currentSelectedIndex) currentOnSelect(target)
                                        isDragging = false
                                    },
                                    onDragCancel = {
                                        releaseVelocity = 0f
                                        isDragging = false
                                    }
                                )
                            }
                        }
                )
            }
        }
    }
}

private fun Modifier.navigationBarIndicator(
    leftEdge: Animatable<Float, AnimationVector1D>,
    rightEdge: Animatable<Float, AnimationVector1D>,
    itemCount: Int,
    shape: Shape,
    color: ColorProducer,
    inset: Dp,
): Modifier = drawWithContent {
    val insetPx = inset.toPx()
    val itemWidth = size.width / itemCount
    val slotLeft = leftEdge.value * itemWidth
    val slotRight = rightEdge.value * itemWidth
    val outline = shape.createOutline(
        size = Size(
            width = (slotRight - slotLeft - insetPx * 2f).coerceAtLeast(0f),
            height = (size.height - insetPx * 2f).coerceAtLeast(0f)
        ),
        layoutDirection = layoutDirection,
        density = this
    )
    withTransform({
        translate(left = slotLeft + insetPx, top = insetPx)
    }) {
        drawOutline(outline = outline, color = color())
    }
    drawContent()
}

@Composable
private fun NavigationBarButton(
    item: NavigationBarItem,
    selected: Boolean,
    onClick: () -> Unit,
    iconColor: ColorProducer,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val iconScale = animateFloatAsState(
        targetValue = if (pressed) NavigationBarPressedIconScale else 1f,
        animationSpec = Springs.snappy(durationMillis = 200),
        visibilityThreshold = 0.0001f,
        label = "navigationBarIconScale"
    )

    Box(
        modifier = modifier.selectable(
            selected = selected,
            role = Role.Tab,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = if (selected) item.selectedIcon else item.icon,
            tint = iconColor,
            contentDescription = item.label,
            modifier = Modifier.graphicsLayer {
                scaleX = iconScale.value
                scaleY = iconScale.value
            }
        )
    }
}

private fun approachSpeed(from: Float, to: Float, velocity: Float): Float = when {
    to > from -> velocity.coerceAtLeast(0f)
    to < from -> velocity.coerceAtMost(0f)
    else -> 0f
}
