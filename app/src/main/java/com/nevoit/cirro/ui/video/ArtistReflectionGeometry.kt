package com.nevoit.cirro.ui.video

import kotlin.math.roundToInt

internal class ArtistReflectionGeometry(val side: Int, riseRatio: Float) {
    val rise = (side * riseRatio).roundToInt().coerceAtLeast(1)
    val strip = (side * riseRatio / 3f).roundToInt().coerceIn(1, side)
    val bleed = (side * riseRatio / 2f).roundToInt().coerceAtLeast(1)
    val height = side + rise
    val blurStart = side - bleed
    val blurHeight = rise + bleed
}
