package com.nevoit.kinetic.layout.indicator

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colour of the bar while [ScrollIndicatorSpec.color] is [Color.Unspecified].
 *
 * The default stays the platform bar colour: this module only depends on Foundation, and
 * `LocalContentColor` lives in Material. An app that already themes its content can hand the
 * ambient colour down with one line next to its theme:
 *
 * ```
 * CompositionLocalProvider(LocalScrollIndicatorColor provides LocalContentColor.current) {
 *     KineticLazyColumn { ... }
 * }
 * ```
 *
 * A per-container [ScrollIndicatorSpec.color] always wins over this local.
 */
val LocalScrollIndicatorColor: ProvidableCompositionLocal<Color> =
    staticCompositionLocalOf { ScrollIndicatorDefaults.DefaultColor }
