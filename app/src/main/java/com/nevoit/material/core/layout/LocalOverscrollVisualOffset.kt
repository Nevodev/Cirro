package com.nevoit.material.core.layout

import androidx.compose.runtime.staticCompositionLocalOf
import com.nevoit.kinetic.layout.overscroll.OverscrollVisualOffset

/**
 * The overscroll translation of the nearest [VerticalStack], so a page can drive a parallax
 * without creating the effect itself. Null when the container runs on an effect that does not
 * report a visual offset (for example the platform edge effect).
 */
val LocalOverscrollVisualOffset = staticCompositionLocalOf<OverscrollVisualOffset?> { null }
