package com.nevoit.cirro.lyrics.ui.composable.lyrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeAlignment
import com.nevoit.cirro.lyrics.ui.internal.effects.PreparedBreathingDots

data class KaraokeBreathingDotsDefaults(
    val number: Int = 3,
    val size: Dp = 12.dp,
    val margin: Dp = 8.dp,
    val enterDurationMs: Int = 2000,
    val preExitStillDuration: Int = 200,
    val preExitDipAndRiseDuration: Int = 3000,
    val exitDurationMs: Int = 200,
    val breathingDotsColor: Color = Color.White,
)

@Composable
fun KaraokeBreathingDots(
    alignment: KaraokeAlignment,
    startTimeMs: Int,
    endTimeMs: Int,
    currentTimeProvider: () -> Int,
    modifier: Modifier = Modifier,
    defaults: KaraokeBreathingDotsDefaults = KaraokeBreathingDotsDefaults(),
    trailingSpacing: Dp = 0.dp,
    lineHeight: Dp = 40.dp,
) {
    val currentTime by rememberUpdatedState(currentTimeProvider)
    val timeline =
        remember(startTimeMs, endTimeMs, defaults) {
            PreparedBreathingDots(startTimeMs, endTimeMs, defaults)
        }
    val diameter = defaults.size.coerceAtLeast(1.dp)
    val margin = defaults.margin.coerceAtLeast(0.dp)
    val density = LocalDensity.current
    val diameterPx = with(density) { diameter.toPx() }
    val marginPx = with(density) { margin.toPx() }
    val totalWidth = diameterPx * timeline.number + marginPx * (timeline.number - 1)
    Canvas(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = trailingSpacing.coerceAtLeast(0.dp))
            .height(lineHeight.coerceAtLeast(diameter))
    ) {
        val time = timeline.elapsed(currentTime())
        val scale = timeline.scale(time)
        val alpha = timeline.alpha(time)
        if (scale > 0f && alpha > 0f) {
            val origin = if (alignment == KaraokeAlignment.End) size.width - totalWidth else 0f
            val center = origin + totalWidth * 0.5f
            val radius = diameterPx * 0.5f * scale
            for (index in 0 until timeline.number) {
                val baseX = origin + diameterPx * 0.5f + (diameterPx + marginPx) * index
                drawCircle(
                    color = defaults.breathingDotsColor,
                    radius = radius,
                    center = Offset(center + (baseX - center) * scale, size.height * 0.5f),
                    alpha = alpha * timeline.dotAlpha(index, time),
                )
            }
        }
    }
}
