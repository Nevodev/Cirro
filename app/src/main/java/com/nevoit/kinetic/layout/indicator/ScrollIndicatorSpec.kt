package com.nevoit.kinetic.layout.indicator

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Geometry and timing of the scroll indicator that Kinetic containers draw on their viewport.
 *
 * UIKit points map 1:1 to Android dp. [shape] is resolved against the CURRENT bar size on every
 * draw, so the default capsule keeps half-thickness corners while the bar expands; a fixed
 * [RoundedCornerShape] with a dp radius does the same thing for a non-capsule look.
 *
 * Pass `null` instead of a spec to a Kinetic container to turn the indicator off.
 */
data class ScrollIndicatorSpec(
    val shape: Shape = ScrollIndicatorDefaults.Shape,
    /**
     * [Color.Unspecified] follows [LocalScrollIndicatorColor], which in turn defaults to the
     * platform bar colour. Set a colour here to pin one container regardless of the local.
     */
    val color: Color = Color.Unspecified,
    /** Resting bar thickness, also the drag hit width. */
    val thickness: Dp = ScrollIndicatorDefaults.Thickness,
    /** Thickness while the bar is held for direct manipulation. */
    val expandedThickness: Dp = ScrollIndicatorDefaults.ExpandedThickness,
    /**
     * Space trimmed off the head of the track — the top end in a vertical container, the leading
     * end in a horizontal one. Callers never pass an orientation.
     */
    val startPadding: Dp = ScrollIndicatorDefaults.StartPadding,
    /** Space trimmed off the tail of the track. Independent of [startPadding]. */
    val endPadding: Dp = ScrollIndicatorDefaults.EndPadding,
    /** Gap between the bar and the viewport edge it hugs. */
    val edgePadding: Dp = ScrollIndicatorDefaults.EdgePadding,
    /**
     * Per-edge override of the three scalars above; `null` keeps them. Non-null wins outright, so
     * it is only needed for an asymmetric cross-axis gap. Left/right follow the layout direction.
     */
    val insets: PaddingValues? = null,
    /** Shortest bar at a normal position, and the floor used when compressing. */
    val minimumLength: Dp = ScrollIndicatorDefaults.MinimumLength,
    /** Shortest bar while the content is pulled beyond an edge. */
    val minimumOverscrollLength: Dp = ScrollIndicatorDefaults.MinimumOverscrollLength,
    /** 0 shows the bar immediately; otherwise it fades in over this duration. */
    val fadeInMillis: Int = ScrollIndicatorDefaults.FadeInMillis,
    /** Fade-out duration once [autoHideMillis] has elapsed. */
    val fadeOutMillis: Int = ScrollIndicatorDefaults.FadeOutMillis,
    /** Idle time before the fade-out starts. */
    val autoHideMillis: Long = ScrollIndicatorDefaults.AutoHideMillis,
    /** Long-press the visible bar to drag the content. */
    val draggable: Boolean = true,
    /** Hold time before a press on the bar becomes a drag. */
    val longPressMillis: Long = ScrollIndicatorDefaults.LongPressMillis,
    /** Touch expansion around the visible bar. Blank track is never a target. */
    val dragHitSlop: Dp = ScrollIndicatorDefaults.DragHitSlop,
) {
    init {
        require(thickness.value.isFinite() && thickness.value >= 0f) { "thickness must be finite and non-negative" }
        require(expandedThickness.value.isFinite() && expandedThickness.value >= 0f) {
            "expandedThickness must be finite and non-negative"
        }
        require(startPadding.value.isFinite() && startPadding.value >= 0f) {
            "startPadding must be finite and non-negative"
        }
        require(endPadding.value.isFinite() && endPadding.value >= 0f) {
            "endPadding must be finite and non-negative"
        }
        require(edgePadding.value.isFinite() && edgePadding.value >= 0f) {
            "edgePadding must be finite and non-negative"
        }
        require(minimumLength.value.isFinite() && minimumLength.value >= 0f) {
            "minimumLength must be finite and non-negative"
        }
        require(minimumOverscrollLength.value.isFinite() && minimumOverscrollLength.value >= 0f) {
            "minimumOverscrollLength must be finite and non-negative"
        }
        require(fadeInMillis >= 0 && fadeOutMillis >= 0 && autoHideMillis >= 0L) {
            "Indicator timing must be non-negative"
        }
        require(longPressMillis > 0L) { "longPressMillis must be positive" }
        require(dragHitSlop.value.isFinite() && dragHitSlop.value >= 0f) {
            "dragHitSlop must be finite and non-negative"
        }
    }

    companion object {
        /** Attached by default; identical to the platform-derived geometry. */
        val Default: ScrollIndicatorSpec = ScrollIndicatorSpec()
    }
}

/** Platform-derived defaults. Values are dp, so they follow the display density. */
object ScrollIndicatorDefaults {
    /** Capsule ends at any thickness, because 50% resolves against the drawn size. */
    val Shape: Shape = RoundedCornerShape(50)
    /** Fallback of [LocalScrollIndicatorColor] when the app provides none. */
    val DefaultColor: Color = Color.Black.copy(alpha = .35f)
    val Black: Color = Color.Black.copy(alpha = .5f)
    val White: Color = Color.White.copy(alpha = .5f)
    /** Convenience value for [ScrollIndicatorSpec.insets] when all four edges differ. */
    val Insets: PaddingValues = PaddingValues(3.dp)
    val Thickness: Dp = 3.dp
    val ExpandedThickness: Dp = 8.dp
    /** Head of the track. */
    val StartPadding: Dp = 3.dp
    /** Tail of the track. */
    val EndPadding: Dp = 3.dp
    /** Bar to viewport edge. */
    val EdgePadding: Dp = 3.dp
    val DragHitSlop: Dp = 12.dp
    val MinimumLength: Dp = 36.dp
    val MinimumOverscrollLength: Dp = 7.dp
    const val FadeInMillis: Int = 0
    const val FadeOutMillis: Int = 250
    const val AutoHideMillis: Long = 1000L
    const val LongPressMillis: Long = 250L
    /** Expansion / retraction of [ExpandedThickness]. Not configurable, mirrors the source curve. */
    const val ExpandMillis: Int = 200
    const val RetractMillis: Int = 300
}

/** Track padding resolved to px, ready for both drawing and hit testing. */
internal class IndicatorInsets(val left: Float, val right: Float, val top: Float, val bottom: Float)

/**
 * [ScrollIndicatorSpec.insets] wins when set. Otherwise the direction-free scalars fill it in:
 * [ScrollIndicatorSpec.startPadding] and [ScrollIndicatorSpec.endPadding] on the two track ends,
 * [ScrollIndicatorSpec.edgePadding] across to the edge the bar hugs. This is the only place that
 * has to know the orientation.
 */
internal fun ScrollIndicatorSpec.resolveInsets(
    density: Density,
    direction: LayoutDirection,
    vertical: Boolean,
): IndicatorInsets {
    val override = insets
    if (override != null) {
        return with(density) {
            IndicatorInsets(
                left = override.calculateLeftPadding(direction).toPx(),
                right = override.calculateRightPadding(direction).toPx(),
                top = override.calculateTopPadding().toPx(),
                bottom = override.calculateBottomPadding().toPx(),
            )
        }
    }
    val start = with(density) { startPadding.toPx() }
    val end = with(density) { endPadding.toPx() }
    val edge = with(density) { edgePadding.toPx() }
    return if (vertical) {
        IndicatorInsets(left = edge, right = edge, top = start, bottom = end)
    } else {
        // Horizontal tracks hug the bottom edge; the head is the leading end.
        val rtl = direction == LayoutDirection.Rtl
        IndicatorInsets(
            left = if (rtl) end else start,
            right = if (rtl) start else end,
            top = edge,
            bottom = edge,
        )
    }
}
