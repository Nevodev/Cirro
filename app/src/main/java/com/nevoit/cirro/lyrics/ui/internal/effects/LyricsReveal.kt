package com.nevoit.cirro.lyrics.ui.internal.effects

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.constrainHeight
import kotlin.math.roundToInt

/** Transition owns size and effects together, so exit content survives until both have settled. */
@Composable
internal fun LyricsReveal(
    visible: Boolean,
    animateInitial: Boolean = false,
    origin: TransformOrigin = TransformOrigin.Center,
    occupied: (() -> Boolean)? = null,
    exitAnimationSpec: FiniteAnimationSpec<Float> = LyricsRevealSpring,
    visualHeight: Float = 0f,
    verticalOverflow: Float = 0f,
    content: @Composable () -> Unit,
) {
    val visibility = remember { MutableTransitionState(if (animateInitial) false else visible) }
    visibility.targetState = visible
    val transition = rememberTransition(visibility, label = "lyricsVisibility")
    val progress =
        transition.animateFloat(
            transitionSpec = { if (targetState) LyricsRevealSpring else exitAnimationSpec },
            label = "lyricsReveal",
        ) {
            if (it) 1f else 0f
        }
    // Explicit occupancy decouples layout from the text's visibility transition.
    if (visibility.currentState || visibility.targetState || occupied != null) {
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = 0))
                    val height =
                        constraints.constrainHeight(
                            when (occupied?.invoke()) {
                                true -> placeable.height
                                false -> 0
                                null -> (placeable.height * progress.value.coerceIn(0f, 1f)).roundToInt()
                            }
                        )
                    // Explicit occupancy changes only the list extent; exiting text keeps its
                    // original drawing position instead of jumping when that extent becomes zero.
                    val offset = if (occupied != null) 0 else
                        ((height - placeable.height) * origin.pivotFractionY).roundToInt()
                    layout(
                        placeable.width,
                        height,
                    ) {
                        placeable.place(0, offset)
                    }
                }
                .lyricsVisualLayer(visualHeight, verticalOverflow) {
                    val value = progress.value
                    transformOrigin = origin
                    scaleX = revealScale(value)
                    scaleY = scaleX
                    alpha = revealAlpha(value)
                }
        ) {
            content()
        }
    }
}
