package com.nevoit.material.navigation.shape

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp

class CornerRadii(
    val topStart: Dp,
    val topEnd: Dp,
    val bottomStart: Dp,
    val bottomEnd: Dp,
)

@Composable
fun deviceCornerShape(
    padding: Dp,
    topLeft: Boolean,
    topRight: Boolean,
    bottomRight: Boolean,
    bottomLeft: Boolean,
): Shape {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return RectangleShape
    }
    val view = LocalView.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    return remember(view, configuration, padding, topLeft, topRight, bottomRight, bottomLeft) {
        fun cornerRadius(position: Int, enabled: Boolean): Dp {
            if (!enabled) return 0.dp

            // Null when the panel is square, or when the platform does not report this corner at all —
            // there is no distinction worth drawing between those two cases.
            val inset = view.rootWindowInsets ?: return 0.dp
            val radius =
                with(density) { inset.getRoundedCorner(position)?.radius?.toFloat()?.toDp() }
                    ?: return 0.dp
            return (radius - padding).coerceAtLeast(0.dp)
        }

        G3UnevenRoundedRectangle(
            topStart = cornerRadius(RoundedCorner.POSITION_TOP_LEFT, topLeft),
            topEnd = cornerRadius(RoundedCorner.POSITION_TOP_RIGHT, topRight),
            bottomEnd = cornerRadius(RoundedCorner.POSITION_BOTTOM_RIGHT, bottomRight),
            bottomStart = cornerRadius(RoundedCorner.POSITION_BOTTOM_LEFT, bottomLeft),
        )
    }
}

@Composable
fun deviceCornerRadii(): CornerRadii {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return CornerRadii(0.dp, 0.dp, 0.dp, 0.dp)
    }
    val view = LocalView.current
    val density = LocalDensity.current

    return remember(LocalView.current, LocalConfiguration.current) {
        fun cornerRadius(position: Int): Dp {
            val inset = view.rootWindowInsets ?: return 0.dp
            val radius =
                with(density) { inset.getRoundedCorner(position)?.radius?.toFloat()?.toDp() }
                    ?: return 0.dp
            return radius
        }

        CornerRadii(
            topStart = cornerRadius(RoundedCorner.POSITION_TOP_LEFT),
            topEnd = cornerRadius(RoundedCorner.POSITION_TOP_RIGHT),
            bottomEnd = cornerRadius(RoundedCorner.POSITION_BOTTOM_RIGHT),
            bottomStart = cornerRadius(RoundedCorner.POSITION_BOTTOM_LEFT),
        )
    }
}