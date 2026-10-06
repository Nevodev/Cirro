package com.nevoit.material.core.modifier

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline

// Background whose colour is only read while drawing. Hand it a producer (`{ state.value }`) when
// the colour animates: Modifier.background(color) reads the value during composition, which
// recomposes the caller on every frame of the animation. The cache block only touches size and
// shape, the colour stays in onDrawBehind which runs on every draw, so a colour change only
// invalidates drawing.
fun Modifier.drawBackground(
    color: ColorProducer,
    shape: Shape = RectangleShape,
): Modifier = drawWithCache {
    val outline = if (shape === RectangleShape) {
        null
    } else {
        shape.createOutline(size, layoutDirection, this)
    }
    onDrawBehind {
        val value = color()
        if (outline == null) drawRect(color = value) else drawOutline(outline, value)
    }
}
