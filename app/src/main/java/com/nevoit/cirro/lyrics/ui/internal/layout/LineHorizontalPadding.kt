package com.nevoit.cirro.lyrics.ui.internal.layout

/**
 * Horizontal inset a lyrics row keeps from the panel edge, in dp.
 *
 * Two places must agree on this number, and they play different roles:
 * - `KaraokeLyricsView` reserves it on both sides of the width it hands to the preparation stage
 *   (`maxWidth - 2 * this`), so the prepared geometry and the drawing container span the same box.
 * - Every row applies it as padding, **exactly once**. Top-level rows take it from the call site;
 *   nested backing vocals already sit inside their owner's padding, so `PreparedLineText` keeps
 *   their own inset at zero.
 *
 * Let the two roles disagree and the whole difference lands on one side: a left-aligned row still
 * starts at its own origin, while a right-aligned row stops short of the right edge.
 */
internal const val LineHorizontalPaddingDp = 12f
