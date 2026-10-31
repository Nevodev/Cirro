package com.nevoit.material.core.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nevoit.material.core.modifier.smoothGradient
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.tokens.Springs

interface ModalTopBarScope {
    @Composable
    fun Action(@DrawableRes iconRes: Int, label: String, onClick: () -> Unit)
}

private object ModalTopBarScopeImpl : ModalTopBarScope {
    @Composable
    override fun Action(iconRes: Int, label: String, onClick: () -> Unit) {
        CircleIconButton(
            iconRes = iconRes,
            label = label,
            background = MaterialTheme.colors.scrimNormal,
            tint = MaterialTheme.colors.primary,
            onClick = onClick,
        )
    }
}

@Composable
fun ModalTopBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    showTitle: () -> Boolean = { true },
    leading: (@Composable ModalTopBarScope.() -> Unit)? = null,
    trailing: (@Composable ModalTopBarScope.() -> Unit)? = null,
) {
    val visible = showTitle()
    val titleAlpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 300 else 200))
    val scale by animateFloatAsState(if (visible) 1f else 0.85f, Springs.smooth(if (visible) 300 else 400))
    Layout(
        modifier = modifier.fillMaxWidth().height(48.dp),
        content = {
            if (leading != null) Box(Modifier.layoutId("leading")) { ModalTopBarScopeImpl.leading() }
            if (trailing != null) Box(Modifier.layoutId("trailing")) { ModalTopBarScopeImpl.trailing() }
            if (title != null) {
                Text(
                    text = title,
                    modifier = Modifier.layoutId("title").graphicsLayer {
                        alpha = titleAlpha
                        scaleX = scale
                        scaleY = scale
                    },
                    style = MaterialTheme.type.headline,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) { measurables, constraints ->
        val sideConstraints = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = constraints.maxWidth / 2)
        val leadingPlaceable = measurables.find { it.layoutId == "leading" }?.measure(sideConstraints)
        val trailingPlaceable = measurables.find { it.layoutId == "trailing" }?.measure(sideConstraints)
        val sideWidth = maxOf(leadingPlaceable?.width ?: 0, trailingPlaceable?.width ?: 0)
        val sidePadding = if (sideWidth > 0) sideWidth + 12.dp.roundToPx() else 24.dp.roundToPx()
        val titlePlaceable = measurables.find { it.layoutId == "title" }?.measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxWidth = (constraints.maxWidth - sidePadding * 2).coerceAtLeast(0))
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            leadingPlaceable?.placeRelative(0, (constraints.maxHeight - leadingPlaceable.height) / 2)
            trailingPlaceable?.placeRelative(
                constraints.maxWidth - trailingPlaceable.width,
                (constraints.maxHeight - trailingPlaceable.height) / 2,
            )
            titlePlaceable?.placeRelative(
                (constraints.maxWidth - titlePlaceable.width) / 2,
                (constraints.maxHeight - titlePlaceable.height) / 2,
            )
        }
    }
}

@Composable
fun ModalTopBarShade(visible: Boolean) {
    val surfaceColor = MaterialTheme.colors.elevatedPageBackground
    Box(
        modifier = Modifier
            .graphicsLayer { alpha = if (visible) 1f else 0f }
            .fillMaxWidth()
            .smoothGradient(surfaceColor, start = 1f, end = 0.5f, intensity = 0.7f)
            .padding(bottom = 80.dp)
    )
}
