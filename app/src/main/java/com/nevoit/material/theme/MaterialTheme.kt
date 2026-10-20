package com.nevoit.material.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.nevoit.kinetic.layout.indicator.LocalScrollIndicatorColor
import com.nevoit.material.core.interaction.DimIndication
import com.nevoit.material.theme.local.LocalColors
import com.nevoit.material.theme.local.LocalContentColor
import com.nevoit.material.theme.local.LocalDarkTheme
import com.nevoit.material.theme.local.LocalSpecs
import com.nevoit.material.theme.local.LocalTextStyle
import com.nevoit.material.theme.local.LocalType

object MaterialTheme {
    val colors: MaterialColors
        @Composable get() = LocalColors.current

    val specs: MaterialSpecs
        @Composable get() = LocalSpecs.current

    val type: MaterialType
        @Composable get() = LocalType.current

    val darkTheme: Boolean
        @Composable get() = LocalDarkTheme.current

    // Fixed palette from [paletteColors]; pass [colors] to pin a different one.
    @Composable
    operator fun invoke(
        darkTheme: Boolean = resolveDarkTheme(ThemeMode.SYSTEM),
        colors: MaterialColors = remember(darkTheme) { paletteColors(darkTheme) },
        specs: MaterialSpecs = MaterialSpecsStandard,
        type: MaterialType = MaterialTypeStandard,
        content: @Composable () -> Unit
    ) {
        CompositionLocalProvider(
            LocalColors provides colors,
            LocalSpecs provides specs,
            LocalType provides type,
            LocalTextStyle provides type.body,
            LocalContentColor provides colors.content,
            LocalScrollIndicatorColor provides colors.content.copy(if (darkTheme) .4f else .2f),
            LocalDarkTheme provides darkTheme,
            LocalIndication provides DimIndication()
        ) {
            content()
        }
    }
}
