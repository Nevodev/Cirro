package com.nevoit.material.core.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.nevoit.material.core.modifier.drawBackground
import com.nevoit.material.core.utility.rememberColorProducer
import com.nevoit.material.theme.MaterialShapes
import com.nevoit.material.theme.toShape

private val ShapeButtonSize = 56.dp
private val ShapeButtonIconSize = 24.dp

// A shape-clipped icon button: a MaterialShapes silhouette filled with a solid background and a
// centred icon. The silhouette is what makes a row of these read as a set, so it is a parameter.
@Composable
fun MaterialShapeButton(
    iconRes: Int,
    label: String,
    background: ColorProducer,
    tint: ColorProducer,
    onClick: () -> Unit,
    enabled: Boolean = true,
    shape: RoundedPolygon = MaterialShapes.Circle,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(ShapeButtonSize)
            .clip(shape.toShape())
            .drawBackground(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            tint = tint,
            contentDescription = label,
            modifier = Modifier.size(ShapeButtonIconSize),
        )
    }
}

// Convenience for colours that are already fixed at composition time.
@Composable
fun MaterialShapeButton(
    iconRes: Int,
    label: String,
    background: Color,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    shape: RoundedPolygon = MaterialShapes.Circle,
    modifier: Modifier = Modifier,
) = MaterialShapeButton(
    iconRes = iconRes,
    label = label,
    background = rememberColorProducer(background),
    tint = rememberColorProducer(tint),
    onClick = onClick,
    enabled = enabled,
    shape = shape,
    modifier = modifier,
)
