package com.nevoit.cirro

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.modifier.thenIf
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.tokens.Springs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class NavItem(
    val iconRes: Int,
    val label: String
)

enum class NavItems(val item: NavItem) {
    Home(NavItem(iconRes = R.drawable.home, label = "Home")),
    Explore(NavItem(iconRes = R.drawable.square_grid_2x2, label = "Explore")),
    Library(NavItem(iconRes = R.drawable.music_note_square_stack, label = "Library")),
    Search(NavItem(iconRes = R.drawable.magnifyingglass, label = "Search")),
}

private val IndicatorInset = 4.dp

private fun approachSpeed(from: Float, to: Float, velocity: Float): Float = when {
    to > from -> velocity.coerceAtLeast(0f)
    to < from -> velocity.coerceAtMost(0f)
    else -> 0f
}

@Composable
fun BoxScope.Main() {
    val colors = MaterialTheme.colors
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }

    var isDragging by remember { mutableStateOf(false) }
    var releaseVelocity by remember { mutableFloatStateOf(0f) }

    val leftEdge = remember { Animatable(selectedIndex.toFloat()) }
    val rightEdge = remember { Animatable(selectedIndex + 1f) }

    val leadingSpec = Springs.snappy(durationMillis = 300, visibilityThreshold = 0.0001f)
    val trailingSpec = Springs.snappy(durationMillis = 400, visibilityThreshold = 0.0001f)
    val followSpec = Springs.smooth(durationMillis = 200, visibilityThreshold = 0.0001f)

    val lastIndex = NavItems.entries.size - 1
    val scope = rememberCoroutineScope()

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

    val indicatorShape = remember { Capsule() }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .clip(Capsule())
                    .background(color = colors.cardBackground)
                    .height(64.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            val inset = IndicatorInset.toPx()
                            val itemWidth = size.width / NavItems.entries.size
                            val slotLeft = leftEdge.value * itemWidth
                            val slotRight = rightEdge.value * itemWidth
                            val indicator = indicatorShape.createOutline(
                                size = Size(
                                    width = (slotRight - slotLeft - inset * 2f)
                                        .coerceAtLeast(0f),
                                    height = (size.height - inset * 2f).coerceAtLeast(0f)
                                ),
                                layoutDirection = layoutDirection,
                                density = this
                            )
                            withTransform({
                                translate(left = slotLeft + inset, top = inset)
                            }) {
                                drawOutline(
                                    outline = indicator,
                                    color = colors.navigationBarIndicator
                                )
                            }
                            drawContent()
                        }
                ) {
                    NavItems.entries.forEachIndexed { index, navItem ->
                        val selected = index == selectedIndex

                        val interactionSource = remember { MutableInteractionSource() }
                        val pressed by interactionSource.collectIsPressedAsState()
                        val iconScale = animateFloatAsState(
                            targetValue = if (pressed) 0.85f else 1f,
                            animationSpec = Springs.snappy(durationMillis = 200),
                            visibilityThreshold = 0.0001f,
                            label = "navIconScale"
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .thenIf(selected) {
                                    pointerInput(Unit) {
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
                                                    selectedIndex
                                                }
                                                releaseVelocity =
                                                    if (SystemClock.uptimeMillis() - lastTime > 100L) {
                                                        0f
                                                    } else {
                                                        velocity
                                                    }
                                                if (target != selectedIndex) selectedIndex = target
                                                isDragging = false
                                            },
                                            onDragCancel = {
                                                releaseVelocity = 0f
                                                isDragging = false
                                            }
                                        )
                                    }
                                }
                                .selectable(
                                    selected = selected,
                                    role = Role.Tab,
                                    interactionSource = interactionSource,
                                    indication = null,
                                    onClick = { selectedIndex = index }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = navItem.item.iconRes),
                                contentDescription = navItem.item.label,
                                modifier = Modifier.graphicsLayer {
                                    // 只读取状态、不参与重组，动画每帧只失效图层。
                                    scaleX = iconScale.value
                                    scaleY = iconScale.value
                                },
                                tint = if (selected) {
                                    colors.onNavigationBarIndicator
                                } else {
                                    colors.navigationBarInactiveIcon
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
