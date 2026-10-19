package com.nevoit.kinetic.layout.overscroll

import androidx.compose.ui.geometry.Offset

/**
 * Optional contract for an [androidx.compose.foundation.OverscrollEffect] whose content translation
 * can be measured. Kinetic containers read it to feed the built-in scroll indicator, so callers
 * never hand the same translation over a second time and never keep their own copy in sync.
 *
 * Report the CURRENT visual translation in px, after damping and pixel rounding: positive down or
 * right, negative up or left. Never pointer travel, velocity or a raw unconsumed delta. The value
 * must be snapshot-observable so a draw-only read stays correct.
 */
interface OverscrollVisualOffset {
    val visualOffset: Offset
}
