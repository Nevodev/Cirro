package com.nevoit.cirro.lyrics.ui.internal.layout

import com.nevoit.cirro.lyrics.ui.preparation.PreparedLine

/**
 * Vertical padding the top-level `PreparedLineText` adds around a line, in dp. Measured item heights
 * are `placeable.height`, so the settled height model has to describe the whole item rather than its
 * content: leave this out and every retained row above the target biases the follow destination,
 * parking the focused line below the anchor by one padding per row. Keep in step with the call site.
 */
internal const val LineVerticalPaddingDp = 12f

internal fun PreparedLine.settledLeadingHeight(
    translationShown: Boolean,
    phoneticShown: Boolean,
    density: Float,
    ownerFocused: Boolean,
): Float = before.sumOf {
    it.settledHeight(translationShown, phoneticShown, density, ownerFocused, true).toDouble()
}.toFloat()

/** Nested vocals occupy space only while their owning list item is focused. */
internal fun PreparedLine.settledHeight(
    translationShown: Boolean,
    phoneticShown: Boolean,
    density: Float,
    ownerFocused: Boolean,
    nested: Boolean = false,
): Float {
    if (nested && !ownerFocused) return 0f
    // Nested lines are rendered with the inner default padding on both sides; the top level adds its
    // own, and only the top level is measured as a list item.
    val padding = if (nested) 16f else 2f * LineVerticalPaddingDp
    var extent = height + padding * density
    if (!phoneticShown) for (row in rows) extent -= row.phoneticHeight
    if (translationShown) extent += translation?.size?.height ?: 0
    if (phoneticShown) extent += phonetic?.size?.height ?: 0
    if ((translationShown && translation != null) || (phoneticShown && phonetic != null))
        extent += 8f * density
    for (line in before)
        extent += line.settledHeight(translationShown, phoneticShown, density, ownerFocused, true)
    for (line in after)
        extent += line.settledHeight(translationShown, phoneticShown, density, ownerFocused, true)
    return extent
}
