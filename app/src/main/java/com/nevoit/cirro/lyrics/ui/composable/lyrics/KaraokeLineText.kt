package com.nevoit.cirro.lyrics.ui.composable.lyrics

import com.nevoit.cirro.lyrics.ui.internal.rendering.PreparedLineText

import androidx.compose.foundation.layout.*
import com.nevoit.material.theme.local.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeLine
import com.nevoit.cirro.lyrics.ui.internal.scene.rememberLyricsScene
import com.nevoit.cirro.lyrics.ui.preparation.PreparedLine
import com.nevoit.cirro.lyrics.ui.preparation.PreparedLyrics
import com.nevoit.cirro.lyrics.ui.profile.DefaultLyricsProfiles
import com.nevoit.cirro.lyrics.ui.profile.LyricsProfile
import com.nevoit.cirro.lyrics.ui.internal.scene.LyricsLayoutRequest

/**
 * Standalone entry point. In a lyrics list, pass a complete prepared line from the parent cache.
 */
@Composable
fun KaraokeLineText(
    line: KaraokeLine,
    currentTimeProvider: () -> Int,
    modifier: Modifier = Modifier,
    normalLineTextStyle: TextStyle = LocalTextStyle.current,
    accompanimentLineTextStyle: TextStyle = LocalTextStyle.current,
    phoneticTextStyle: TextStyle = LocalTextStyle.current,
    activeColor: Color = Color.White,
    blendMode: BlendMode = BlendMode.SrcOver,
    showDebugRectangles: Boolean = false,
    showTranslation: Boolean = true,
    showPhonetic: Boolean = true,
    preparedLine: PreparedLine? = null,
    textMeasurer: TextMeasurer = rememberTextMeasurer(),
    renderProfiles: List<LyricsProfile> = DefaultLyricsProfiles,
) {
    val lyrics = remember(line) {
        com.nevoit.cirro.lyrics.core.model.SyncedLyrics(listOf(line))
    }
    val external = remember(preparedLine) { preparedLine?.let { PreparedLyrics(listOf(it)) } }
    val profiles = remember(renderProfiles) {
        renderProfiles + DefaultLyricsProfiles.filter { it !in renderProfiles }
    }
    BoxWithConstraints(modifier.graphicsLayer { this.blendMode = blendMode }) {
        val density = LocalDensity.current
        val width =
            with(density) {
                    (maxWidth - if (line is KaraokeLine.AccompanimentKaraokeLine) 0.dp else 32.dp)
                        .toPx()
                }
                .coerceAtLeast(1f)
        val scene = rememberLyricsScene(
            LyricsLayoutRequest(
                lyrics, profiles, textMeasurer, normalLineTextStyle, accompanimentLineTextStyle,
                phoneticTextStyle, width, density, LocalLayoutDirection.current,
            ),
            activeColor,
            currentTimeProvider,
            external,
        ) ?: return@BoxWithConstraints
        PreparedLineText(
            scene.lyrics.lines.single()!!,
            scene.timeline.state,
            scene.resources,
            ownerFocused = { 0 in scene.timeline.focus.value.allIndices },
            showTranslation = showTranslation,
            showPhonetic = showPhonetic,
            showDebugRectangles = showDebugRectangles,
        )
    }
}
