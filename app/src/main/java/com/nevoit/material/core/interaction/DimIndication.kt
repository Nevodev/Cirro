package com.nevoit.material.core.interaction

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.util.fastCoerceIn
import com.nevoit.material.theme.local.LocalContentColor
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object DimIndicationDefaults {
    const val MAX_ALPHA = 0.1f
    val PressIn = tween<Float>(150)
    val ReleaseOut = tween<Float>(200)
    val ReleaseOutFast = tween<Float>(100)
}

internal val LocalIndicationColorProducer = staticCompositionLocalOf<ColorProducer?> { null }

data class DimIndication(
    val color: Color = Color.Unspecified,
    val maxAlpha: Float = DimIndicationDefaults.MAX_ALPHA,
    val shape: Shape = RectangleShape,
    val finishPressOnRelease: Boolean = false,
    val releaseSpec: FiniteAnimationSpec<Float> = DimIndicationDefaults.ReleaseOut,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode {
        return DimIndicationNode(
            interactionSource, color, shape,
            DimIndicationAnimation(maxAlpha, finishPressOnRelease, releaseSpec),
        )
    }
}

@Composable
internal fun rememberRetainedDimIndication(
    indication: DimIndication,
    interactionSource: InteractionSource,
): IndicationNodeFactory {
    val animation = remember(indication) {
        DimIndicationAnimation(indication.maxAlpha, indication.finishPressOnRelease, indication.releaseSpec)
    }
    // A movable row detaches its nodes while reparenting. Its release animation must keep
    // running in composition scope, including while clickable recreates its indication node.
    LaunchedEffect(animation, interactionSource) { animation.collect(interactionSource) }
    return remember(indication, animation) {
        RetainedDimIndication(indication, animation)
    }
}

private data class RetainedDimIndication(
    val indication: DimIndication,
    val animation: DimIndicationAnimation,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        DimIndicationNode(null, indication.color, indication.shape, animation)
}

private class DimIndicationNode(
    private val interactionSource: InteractionSource?,
    private val color: Color,
    private val shape: Shape,
    private val animation: DimIndicationAnimation,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {

    override fun onAttach() {
        super.onAttach()
        interactionSource?.let { source ->
            coroutineScope.launch { animation.collect(source) }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (animation.alpha.value > 0f) {
            val targetColor = if (color != Color.Unspecified) {
                color
            } else {
                currentValueOf(LocalIndicationColorProducer)?.invoke() ?: currentValueOf(LocalContentColor)
            }

            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(outline, color = targetColor.copy(alpha = animation.alpha.value))
        }
    }
}

private class DimIndicationAnimation(
    maxAlpha: Float,
    private val finishPressOnRelease: Boolean,
    private val releaseSpec: FiniteAnimationSpec<Float>,
) {

    val alpha = Animatable(0f)
    private val targetAlpha = maxAlpha.fastCoerceIn(0f, 1f)

    suspend fun collect(interactionSource: InteractionSource) {
        interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    alpha.animateTo(targetAlpha, DimIndicationDefaults.PressIn)
                }

                is PressInteraction.Release -> {
                    val currentAlpha = alpha.value
                    val releasePeak = if (finishPressOnRelease) targetAlpha else targetAlpha * 0.5f

                    if (currentAlpha < releasePeak) {
                        alpha.animateTo(
                            releasePeak,
                            DimIndicationDefaults.ReleaseOutFast
                        )
                    }

                    alpha.animateTo(0f, releaseSpec)
                }

                is PressInteraction.Cancel -> {
                    alpha.animateTo(0f, DimIndicationDefaults.ReleaseOut)
                }
            }
        }
    }
}
