package com.nevoit.cirro.playback

import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale

@Composable
internal fun PlayerCoverCrossfade(transition: Transition<Painter>, durationMillis: Int) {
    val visible = remember(transition) { mutableStateListOf(transition.currentState) }
    if (transition.currentState == transition.targetState) {
        visible.removeAll { it != transition.targetState }
    }
    if (transition.targetState !in visible) visible.add(transition.targetState)

    // 两张半透明图用 SrcOver 叠加会露出底色；在独立图层内相加才能保持总不透明度。
    Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        visible.forEach { shown ->
            key(shown) {
                val opacity = transition.animateFloat(
                    transitionSpec = { tween(durationMillis) },
                    label = "PlayerCoverImageAlpha",
                ) { if (it == shown) 1f else 0f }
                Image(
                    painter = shown,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        alpha = opacity.value
                        blendMode = BlendMode.Plus
                    },
                )
            }
        }
    }
}
