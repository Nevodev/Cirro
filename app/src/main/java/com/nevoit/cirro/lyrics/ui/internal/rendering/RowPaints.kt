package com.nevoit.cirro.lyrics.ui.internal.rendering

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*

/** Shared by a rendering host; entries must stay read-only so rows cannot affect each other's effects. */
internal class RowPaints(color: Color) {
    val shadows =
        Array(65) { index ->
            val strength = index / 64f
            Shadow(color.copy(alpha = color.alpha * 0.4f * strength), Offset.Zero, 10f * strength)
        }
    val blurEffects =
        Array<RenderEffect?>(65) {
            if (it == 0) null else BlurEffect(10f * it / 64f, 10f * it / 64f, TileMode.Decal)
        }
    val phoneticColor = color.copy(alpha = color.alpha * 0.4f)
    val debugStroke = Stroke(1f)
}
