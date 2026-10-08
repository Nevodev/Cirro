package com.nevoit.cirro.lyrics.ui.internal.effects

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

internal fun Modifier.lyricsContentOffset(
    extent: Float,
    visualHeight: Float,
    offset: () -> Float,
): Modifier = if (extent <= 0f) this else
    lyricsVisualLayer(visualHeight, extent) { translationY = offset() }

internal fun Modifier.lyricsVisualLayer(
    visualHeight: Float = 0f,
    verticalOverflow: Float = 0f,
    block: GraphicsLayerScope.() -> Unit,
): Modifier = graphicsLayer {
    block()
    clip = false
    // Querying descendant alignment lines during measurement can place transformed children
    // before their parent enters Compose's RectList. Use prepared geometry instead.
    // Three times the reveal blur radius, outside the retained unscaled drawing bounds.
    outsets =
        LayerOutsets(
            left = 24.dp,
            top = verticalOverflow.toDp() + 24.dp,
            right = 24.dp,
            bottom = (visualHeight - size.height).coerceAtLeast(0f).toDp() +
                verticalOverflow.toDp() + 24.dp,
        )
}
