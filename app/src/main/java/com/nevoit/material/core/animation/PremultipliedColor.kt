package com.nevoit.material.core.animation

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces

// Interpolating unassociated RGB and alpha makes a faint white scrim brighten before it
// reaches an opaque dark fill. Premultiplying keeps its visible contribution in the blend.
internal val PremultipliedColorConverter = TwoWayConverter<Color, AnimationVector4D>(
    convertToVector = { color ->
        val srgb = color.convert(ColorSpaces.Srgb)
        AnimationVector4D(
            srgb.red * srgb.alpha,
            srgb.green * srgb.alpha,
            srgb.blue * srgb.alpha,
            srgb.alpha,
        )
    },
    convertFromVector = { vector ->
        val alpha = vector.v4.coerceIn(0f, 1f)
        if (alpha == 0f) {
            Color.Transparent
        } else {
            Color(
                red = (vector.v1 / alpha).coerceIn(0f, 1f),
                green = (vector.v2 / alpha).coerceIn(0f, 1f),
                blue = (vector.v3 / alpha).coerceIn(0f, 1f),
                alpha = alpha,
            )
        }
    },
)

@Composable
internal fun animatePremultipliedColorAsState(
    targetValue: Color,
    animationSpec: AnimationSpec<Color>,
    label: String,
): State<Color> = animateValueAsState(
    targetValue = targetValue,
    typeConverter = PremultipliedColorConverter,
    animationSpec = animationSpec,
    label = label,
)
