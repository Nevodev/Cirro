package com.nevoit.cirro.lyrics.ui.composable.lyrics

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import com.nevoit.cirro.lyrics.ui.internal.effects.lyricsVisualLayer
import com.nevoit.material.core.animation.rememberPressAnimatable
import com.nevoit.material.theme.tokens.Springs

@Composable
fun LyricsLineItem(
    isFocused: Boolean,
    isRightAligned: Boolean,
    onLineClicked: () -> Unit,
    onLinePressed: () -> Unit,
    blurRadius: () -> Float,
    modifier: Modifier = Modifier,
    activeAlpha: Float = 1f,
    inactiveAlpha: Float = 0.4f,
    blendMode: BlendMode = BlendMode.SrcOver,
    isInteractive: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scaleState by
    animateFloatAsState(
        targetValue = if (isFocused) 1f else 0.98f,
        animationSpec =
            if (isFocused) {
                Springs.smooth(600, 0.0, 0.0001f)
            } else {
                Springs.smooth(300, 0.0, 0.0001f)
            },
        label = "scale",
    )

    val alphaState by
    animateFloatAsState(
        targetValue = if (isFocused) activeAlpha else inactiveAlpha,
        label = "alpha",
    )

    val press = rememberPressAnimatable(
        atRest = 1f,
        whenPressed = 0.95f,
        onPressSpec = Springs.snappy(500, 0.1, 0.0001f),
        onReleaseSpec = Springs.snappy(400, 0.0, 0.0001f),
        interrupt = true,
        minPressMillis = 100L
    )

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .lyricsVisualLayer {
                    scaleX = scaleState
                    scaleY = scaleState
                    alpha = alphaState
                    transformOrigin = TransformOrigin(if (isRightAligned) 1f else 0f, 1f)
                    this.blendMode = blendMode
                    compositingStrategy = CompositingStrategy.Auto

                    val radius = blurRadius()
                    renderEffect =
                        if (radius > 0f) {
                            BlurEffect(
                                radiusX = radius,
                                radiusY = radius,
                                edgeTreatment = TileMode.Clamp,
                            )
                        } else null
                }
                .graphicsLayer {
                    scaleX = press.progress.value
                    scaleY = press.progress.value
                }
    ) {
        // Clip the ripple, never the lyrics that can draw beyond their animated layout height.
        if (isInteractive)
            Box(
                Modifier
                    .matchParentSize()
                    .clip(RoundedRectangle(20.dp))
                    .combinedClickable(
                        onClick = onLineClicked,
                        onLongClick = onLinePressed,
                        interactionSource = press.interactionSource
                    )
            )
        content()
    }
}
