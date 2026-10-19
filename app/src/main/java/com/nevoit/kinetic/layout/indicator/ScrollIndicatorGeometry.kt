package com.nevoit.kinetic.layout.indicator

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Two packed floats, like Compose's Size. The drawing path needs no geometry allocation. */
@JvmInline
value class ScrollIndicatorGeometry internal constructor(private val packed: Long) {
    val startPx: Float get() = Float.fromBits((packed ushr 32).toInt())
    val lengthPx: Float get() = Float.fromBits(packed.toInt())
    val endPx: Float get() = startPx + lengthPx
    val isVisible: Boolean get() = lengthPx > 0f

    companion object {
        val Empty: ScrollIndicatorGeometry = ScrollIndicatorGeometry(0L)
        internal fun of(start: Float, length: Float): ScrollIndicatorGeometry =
            ScrollIndicatorGeometry((start.toRawBits().toLong() shl 32) or
                (length.toRawBits().toLong() and 0xffffffffL))
    }
}

/**
 * Scroll-bar geometry from the platform's own indicator math, normalized to a local viewport in
 * physical pixels.
 *
 * [scrollOffsetPx] is a bounded offset from the visual start (the right edge for horizontal RTL).
 * [contentSizePx] includes any content padding/insets already accounted for in that offset.
 * [overscrollPx] is the ACTUAL content translation, in physical coordinates: positive down/right,
 * negative up/left. Do not pass raw pointer travel, delta, velocity, or a second rubber-band result.
 * The offset and translation are combined before checking the CURRENT content boundaries.
 *
 * [reverseAxis] mirrors the visual start for horizontal RTL, not reverseLayout/reverseScrolling.
 * Insets are along that logical axis; the returned start is a physical top/left coordinate.
 * Defaults are points mapped to Android dp by supplying [density].
 */
fun calculateScrollIndicator(
    viewportSizePx: Float,
    contentSizePx: Float,
    scrollOffsetPx: Float,
    overscrollPx: Float = 0f,
    density: Float = 1f,
    startInsetPx: Float = 3f * density,
    endInsetPx: Float = 3f * density,
    reverseAxis: Boolean = false,
    minimumLengthPx: Float = 36f * density,
    minimumOverscrollLengthPx: Float = 7f * density,
): ScrollIndicatorGeometry {
    if (!viewportSizePx.isFinite() || !contentSizePx.isFinite() || !scrollOffsetPx.isFinite() ||
        !overscrollPx.isFinite() || !density.isFinite() || density <= 0f ||
        !startInsetPx.isFinite() || !endInsetPx.isFinite() ||
        !minimumLengthPx.isFinite() || !minimumOverscrollLengthPx.isFinite() ||
        viewportSizePx <= 0f || contentSizePx <= 0f ||
        minimumLengthPx < 0f || minimumOverscrollLengthPx < 0f) return ScrollIndicatorGeometry.Empty

    val viewport = viewportSizePx.toDouble()
    val content = contentSizePx.toDouble()
    // The visible fraction is compared to the .99 constant before anything is drawn.
    val fraction = min(viewport / content, 1.0)
    if (fraction > .99) return ScrollIndicatorGeometry.Empty
    val insetStart = max(0.0, startInsetPx.toDouble())
    val insetEnd = max(0.0, endInsetPx.toDouble())
    val track = viewport - insetStart - insetEnd
    if (track <= 0.0) return ScrollIndicatorGeometry.Empty

    val range = content - viewport
    val logicalTranslation = if (reverseAxis) -overscrollPx.toDouble() else overscrollPx.toDouble()
    val offset = scrollOffsetPx.toDouble().coerceIn(0.0, range) - logicalTranslation
    // Ties away from zero; kotlin.math.round uses ties-to-even.
    val base = max(minimumLengthPx.toDouble(), roundPixel(fraction * track)).coerceAtMost(track)
    val overrun = when {
        offset < 0.0 -> -offset
        offset > range -> offset - range
        else -> 0.0
    }
    val length = if (overrun == 0.0) base else
        max(min(minimumOverscrollLengthPx.toDouble(), base), base - overrun)
    var position = roundPixel(insetStart + (track - base) * (offset / range))
    if (offset > range) position += overrun
    position = position.coerceIn(insetStart, viewport - insetEnd - length)
    if (reverseAxis) position = viewport - position - length
    return ScrollIndicatorGeometry.of(position.toFloat(), length.toFloat())
}

private fun roundPixel(value: Double): Double =
    if (value >= 0.0) floor(value + .5) else -floor(abs(value) + .5)
