package com.nevoit.material.core.modifier

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import com.nevoit.material.core.utility.rememberColorProducer

// Convenience for a colour that is already fixed at composition time. Anything that animates
// should call the producer overload instead.
@Composable
fun Modifier.smoothGradient(
    color: Color,
    start: Float,
    end: Float,
    intensity: Float
): Modifier = smoothGradient(rememberColorProducer(color), start, end, intensity)

// Vertical mask gradient whose colour is only read while drawing. Hand it a producer
// (`{ state.value }`) when the colour animates: reading the value during composition builds the
// whole 33-stop ramp again on every frame of the animation and recomposes the caller with it.
//
// The producer is read inside the cache block, which registers the dependency: the ramp is rebuilt
// when the colour changes and only then, so a settled colour costs one list for the whole session.
fun Modifier.smoothGradient(
    color: ColorProducer,
    start: Float,
    end: Float,
    intensity: Float
): Modifier = drawWithCache {
    val brush = Brush.verticalGradient(
        colors = smoothStepGradientColors(color(), start, end, 1f),
        startY = 0f,
        endY = size.height
    )

    onDrawBehind {
        drawRect(brush = brush, alpha = intensity)
    }
}

private const val SmoothStepGradientSamples = 32

private fun smoothStepGradientColors(
    color: Color,
    start: Float,
    end: Float,
    intensity: Float
): List<Color> =
    List(SmoothStepGradientSamples + 1) { index ->
        val p = index / SmoothStepGradientSamples.toFloat()
        val mask = smoothStep(start, end, p)
        val alpha = (intensity * mask).coerceIn(0f, 1f)

        color.copy(alpha = color.alpha * alpha)
    }

private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
    if (edge0 == edge1) {
        return if (x < edge0) 0f else 1f
    }

    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
