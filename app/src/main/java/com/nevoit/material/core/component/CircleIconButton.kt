package com.nevoit.material.core.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nevoit.material.core.modifier.drawBackground
import com.nevoit.material.core.utility.rememberColorProducer

private val CircleIconButtonSize = 48.dp
private val CircleIconButtonIconSize = 28.dp

// A round icon button on a solid fill. Colors arrive as producers so an animating caller can
// keep the read in the draw phase; the Color overload is for colors already fixed at call time.
@Composable
fun CircleIconButton(
    iconRes: Int,
    label: String,
    background: ColorProducer,
    tint: ColorProducer,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = CircleIconButtonSize,
    iconSize: Dp = CircleIconButtonIconSize,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .drawBackground(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            tint = tint,
            contentDescription = label,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
fun CircleIconButton(
    iconRes: Int,
    label: String,
    background: Color,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = CircleIconButtonSize,
    iconSize: Dp = CircleIconButtonIconSize,
) = CircleIconButton(
    iconRes = iconRes,
    label = label,
    background = rememberColorProducer(background),
    tint = rememberColorProducer(tint),
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    size = size,
    iconSize = iconSize,
)
