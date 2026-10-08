package com.nevoit.cirro.lyrics.ui.internal.rendering

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeLine
import com.nevoit.cirro.lyrics.ui.internal.diagnostics.traceLyrics
import com.nevoit.cirro.lyrics.ui.internal.effects.LyricsReveal
import com.nevoit.cirro.lyrics.ui.internal.effects.LyricsRevealSpring
import com.nevoit.cirro.lyrics.ui.internal.effects.BgvExitSpring
import com.nevoit.cirro.lyrics.ui.internal.effects.lyricsContentOffset
import com.nevoit.cirro.lyrics.ui.internal.layout.settledHeight
import com.nevoit.cirro.lyrics.ui.internal.layout.LineVerticalPaddingDp
import com.nevoit.cirro.lyrics.ui.internal.playback.LyricsPlaybackState
import com.nevoit.cirro.lyrics.ui.preparation.PreparedLine
import kotlin.math.roundToInt

@Composable
internal fun PreparedLineText(
    prepared: PreparedLine,
    playback: LyricsPlaybackState,
    resources: LyricsRenderResources,
    ownerFocused: () -> Boolean,
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 8.dp,
    showTranslation: Boolean = true,
    showPhonetic: Boolean = true,
    showDebugRectangles: Boolean = false,
    occupied: (() -> Boolean)? = null,
    leadingContentOffset: () -> Float = { 0f },
) {
    val density = LocalDensity.current
    val activeColor = resources.color
    val raster = resources.raster(prepared)
    val paints = remember(activeColor) { RowPaints(activeColor) }
    val alignment = if (prepared.rightAligned) Alignment.End else Alignment.Start
    val phoneticProgress =
        animateFloatAsState(
            if (showPhonetic) 1f else 0f,
            LyricsRevealSpring,
            label = "inlinePhonetic",
        )
    val accompaniment = prepared.source is KaraokeLine.AccompanimentKaraokeLine
    val leadingHeights = remember(prepared, density.density) {
        prepared.before.map { it.settledHeight(true, true, density.density, true, true) }
    }
    val leadingHeight = leadingHeights.sum()
    val visualHeight = remember(prepared, density.density, verticalPadding) {
        prepared.settledHeight(true, true, density.density, true) +
            2f * (verticalPadding.value - LineVerticalPaddingDp) * density.density
    }
    LyricsReveal(
        visible = playback.line(prepared).visible.value,
        animateInitial = accompaniment,
        exitAnimationSpec = if (accompaniment) BgvExitSpring else LyricsRevealSpring,
        origin =
            if (accompaniment)
                TransformOrigin(
                    if (prepared.rightAligned) 1f else 0f,
                    if (prepared.revealFromBottom) 1f else 0f,
                )
            else TransformOrigin.Center,
        // The owner supplies occupancy independently of the backing vocal's visible window.
        occupied = occupied ?: if (accompaniment) ownerFocused else null,
        visualHeight = visualHeight,
        verticalOverflow = leadingHeight,
    ) {
        Column(
            modifier
                .fillMaxWidth()
                .padding(
                    vertical = verticalPadding,
                    horizontal =
                        if (prepared.source is KaraokeLine.AccompanimentKaraokeLine) 0.dp else 12.dp,
                ),
            horizontalAlignment = alignment,
        ) {
            var prefix = 0f
            for ((index, line) in prepared.before.withIndex()) {
                val precedingHeight = prefix
                Box(Modifier.lyricsContentOffset(precedingHeight, leadingHeights[index]) {
                    leadingContentOffset() * (precedingHeight / leadingHeight)
                }) {
                    PreparedLineText(
                        line,
                        playback = playback,
                        resources = resources,
                        ownerFocused = ownerFocused,
                        occupied = ownerFocused,
                        showTranslation = showTranslation,
                        showPhonetic = showPhonetic,
                        showDebugRectangles = showDebugRectangles,
                    )
                }
                prefix += leadingHeights[index]
            }
            Column(
                Modifier.fillMaxWidth().lyricsContentOffset(
                    leadingHeight, visualHeight - leadingHeight, leadingContentOffset,
                ),
                horizontalAlignment = alignment,
            ) {
                // Separate draw scopes mean a ticking row cannot invalidate its static neighbours.
                Column(Modifier.fillMaxWidth()) {
                    for ((index, row) in prepared.rows.withIndex()) {
                        val clock = playback.row(row).time
                        val renderState = resources.row(row)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    val hidden =
                                        row.phoneticHeight * (1f - phoneticProgress.value.coerceIn(
                                            0f,
                                            1f
                                        ))
                                    layout(
                                        placeable.width,
                                        (placeable.height - hidden).roundToInt().coerceAtLeast(0),
                                    ) {
                                        placeable.place(0, 0)
                                    }
                                }
                                .height(with(density) { row.height.toDp() })
                                .drawWithCache {
                                    val layers = raster.rows[index]
                                    val glows =
                                        layers
                                            .takeIf { it.hasGlow || it.hasPhonetics }
                                            ?.let {
                                                traceLyrics("Lyrics.layerCache") {
                                                    RowGlowLayers(
                                                        this,
                                                        it
                                                    )
                                                }
                                            }
                                    onDrawBehind {
                                        val progress = phoneticProgress.value.coerceIn(0f, 1f)
                                        translate(
                                            top = -row.top - row.phoneticHeight * (1f - progress)
                                        ) {
                                            drawPreparedRow(
                                                row,
                                                clock.intValue,
                                                renderState,
                                                activeColor,
                                                paints,
                                                showDebugRectangles,
                                                layers,
                                                glows,
                                                progress,
                                            )
                                        }
                                    }
                                }
                        )
                    }
                }
                if (prepared.translation != null || prepared.phonetic != null) {
                    val captionGap =
                        animateFloatAsState(
                            if (
                                (showTranslation && prepared.translation != null) ||
                                (showPhonetic && prepared.phonetic != null)
                            )
                                8f
                            else 0f,
                            LyricsRevealSpring,
                            label = "captionGap",
                        )
                    Spacer(
                        Modifier.layout { _, constraints ->
                            layout(
                                constraints.minWidth,
                                constraints.constrainHeight(captionGap.value.dp.roundToPx()),
                            ) {}
                        }
                    )
                }
                prepared.translation?.let { layout ->
                    LyricsReveal(showTranslation) {
                        Canvas(
                            Modifier.size(
                                with(density) { layout.size.width.toDp() },
                                with(density) { layout.size.height.toDp() },
                            )
                        ) {
                            drawText(layout, paints.phoneticColor)
                        }
                    }
                }
                prepared.phonetic?.let { layout ->
                    LyricsReveal(showPhonetic) {
                        Canvas(
                            Modifier.size(
                                with(density) { layout.size.width.toDp() },
                                with(density) { layout.size.height.toDp() },
                            )
                        ) {
                            drawText(layout, paints.phoneticColor)
                        }
                    }
                }
                for (line in prepared.after) PreparedLineText(
                    line,
                    playback = playback,
                    resources = resources,
                    ownerFocused = ownerFocused,
                    occupied = ownerFocused,
                    showTranslation = showTranslation,
                    showPhonetic = showPhonetic,
                    showDebugRectangles = showDebugRectangles,
                )
            }
        }
    }
}
