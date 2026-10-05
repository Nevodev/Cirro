package com.nevoit.material.theme

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberSystemSeedColor(): Color {
    val context = LocalContext.current

    return remember(context) { systemSeedColor(context) }
}

fun systemSeedColor(context: Context): Color =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Color(context.getColor(android.R.color.system_accent1_600))
    } else {
        FallbackSeedColor
    }

internal val FallbackSeedColor = Color(0xFF6750A4)
