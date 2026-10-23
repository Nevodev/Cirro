package com.nevoit.cirro.playback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.nevoit.material.core.component.Text
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

@Composable
@Suppress("StateFlowValueCalledInComposition")
internal fun rememberIsCurrentSong(songId: Long): Boolean {
    val connection = koinInject<PlaybackConnection>()
    val current by remember(connection, songId) {
        connection.state.map { it.song?.id == songId }.distinctUntilChanged()
    }.collectAsState(initial = connection.state.value.song?.id == songId)
    return current
}

@Composable
internal fun PlayingCoverIndicator(isCurrent: Boolean, modifier: Modifier = Modifier) {
    val opacity = rememberPlayingOpacity(isCurrent) ?: return
    SpectrumCanvas(isCurrent, opacity, Color.White, shadeCover = true, modifier = modifier)
}

@Composable
internal fun PlayingTrackNumber(
    index: Int,
    isCurrent: Boolean,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val opacity = rememberPlayingOpacity(isCurrent)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = index.toString(),
            style = style,
            color = color,
            modifier = if (opacity == null) Modifier else {
                Modifier.graphicsLayer { alpha = 1f - opacity() }
            },
        )
        if (opacity != null) {
            SpectrumCanvas(
                isCurrent, opacity, color, shadeCover = false,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
internal fun rememberPlayingOpacity(isCurrent: Boolean): (() -> Float)? {
    var retained by remember { mutableStateOf(isCurrent) }
    if (!isCurrent && !retained) return null

    val opacity = remember { Animatable(0f) }
    LaunchedEffect(isCurrent) {
        // Keep the old indicator through its fade, including a reversal before it finishes.
        // Ordinary rows never allocate animation or spectrum resources.
        retained = true
        opacity.animateTo(if (isCurrent) 1f else 0f, tween(200))
        if (!isCurrent) retained = false
    }
    return remember(opacity) { { opacity.value } }
}

@Composable
@Suppress("StateFlowValueCalledInComposition")
private fun SpectrumCanvas(
    isCurrent: Boolean,
    opacity: () -> Float,
    color: Color,
    shadeCover: Boolean,
    modifier: Modifier,
) {
    val audioAnalysis = koinInject<AudioAnalysisStore>()
    val levels = remember { mutableStateOf(AudioSpectrum.Silence) }
    val audible = if (isCurrent) {
        val connection = koinInject<PlaybackConnection>()
        val playing by remember(connection) {
            connection.state.map { it.isPlaying }.distinctUntilChanged()
        }.collectAsState(initial = connection.state.value.isPlaying)
        playing
    } else {
        false
    }
    DisposableEffect(audioAnalysis, isCurrent, audible) {
        if (isCurrent && audible) {
            val listener: (AudioSpectrum) -> Unit = { levels.value = it }
            audioAnalysis.addSpectrumListener(listener)
            onDispose { audioAnalysis.removeSpectrumListener(listener) }
        } else {
            onDispose {}
        }
    }
    LaunchedEffect(isCurrent, audible) {
        if (isCurrent && !audible) {
            // 暂停后 PCM 不再更新，从最后显示的柱高继续释放包络；恢复播放会取消这段下落。
            val decay = AudioSpectrumSmoother(levels.value)
            var previousFrame = withFrameNanos { it }
            while (levels.value != AudioSpectrum.Silence) {
                withFrameNanos { now ->
                    levels.value = decay.update(AudioSpectrum.Silence, now - previousFrame)
                    previousFrame = now
                }
            }
        }
    }
    Canvas(modifier = modifier) {
        val alpha = opacity()
        if (alpha <= 0f) return@Canvas
        if (shadeCover) drawRect(Color.Black.copy(alpha = 0.45f * alpha))
        val spectrum = levels.value
        val width = minOf(size.width * 0.7f, 24.dp.toPx())
        val barWidth = width / (AudioSpectrum.BandCount * 2 - 1)
        val maxHeight = minOf(size.height * 0.6f, 20.dp.toPx())
        val minHeight = minOf(2.dp.toPx(), maxHeight)
        val left = (size.width - width) / 2f
        for (band in 0 until AudioSpectrum.BandCount) {
            val height = minHeight + (maxHeight - minHeight) * spectrum[band]
            drawRoundRect(
                color = color.copy(alpha = color.alpha * alpha),
                topLeft = Offset(left + band * barWidth * 2f, (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}
