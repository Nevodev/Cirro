package com.nevoit.material.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.nevoit.material.theme.tokens.Red500
import com.nevoit.material.theme.tokens.Yellow500

private val InactiveTrack = Color(0xFF787880).copy(alpha = .25f)

// Fixed palette: neutral surfaces from the Cresto set, accents derived from the one accent colour.
fun paletteColors(isDark: Boolean): MaterialColors {
    val accent = Red500
    val onAccent = Color.White
    val content = if (isDark) Color.White else Color.Black
    val contentVariant = content.copy(alpha = .5f)
    val scrimLight = content.copy(alpha = if (isDark) .05f else .025f)
    val scrimNormal = content.copy(alpha = if (isDark) .1f else .05f)
    val scrimMedium = content.copy(alpha = if (isDark) .2f else .1f)
    val scrimBold = content.copy(alpha = if (isDark) .4f else .2f)
    val pageBackground = if (isDark) Color.Black else Color(0xFFF3F4F6)
    val cardBackground = if (isDark) Color(0xFF1B1C1D) else Color.White
    val elevatedCardBackground = if (isDark) Color(0xFF2C2C2E) else cardBackground
    return materialColorsFromSeed(accent, isDark).copy(
        background = if (isDark) Color.Black else Color.White,
        activeTrack = accent,
        inactiveTrack = InactiveTrack,
        activeThumb = Color.White,
        inactiveThumb = Color.White,
        pageBackground = pageBackground,
        cardBackground = cardBackground,
        elevatedPageBackground = if (isDark) Color(0xFF1C1C1E) else pageBackground,
        elevatedCardBackground = elevatedCardBackground,
        scrimLight = scrimLight,
        scrimNormal = scrimNormal,
        scrimMedium = scrimMedium,
        scrimBold = scrimBold,
        primary = accent,
        onPrimary = onAccent,
        content = content,
        contentVariant = contentVariant,
        highlightText = Yellow500,
        error = Red500,
        onError = Color.White,
        // Opaque on purpose: the bar animates this colour, and a translucent start
        // value drags the alpha through the transition.
        navigationBarIndicator = scrimNormal.compositeOver(cardBackground),
        onNavigationBarIndicator = accent,
        navigationBarInactiveIcon = contentVariant.compositeOver(cardBackground),
        iconButtonBackground = scrimNormal,
        onIconButton = accent,
        primaryButtonBackground = accent,
        onPrimaryButton = onAccent,
        secondaryButtonBackground = cardBackground,
        onSecondaryButton = accent,
        menuActionBackground = scrimNormal.compositeOver(elevatedCardBackground),
        onMenuAction = content,
        menuSelectionIndicator = accent,
        shadow = if (isDark) Color.Black else Color.Black.copy(alpha = .5f),
    )
}
