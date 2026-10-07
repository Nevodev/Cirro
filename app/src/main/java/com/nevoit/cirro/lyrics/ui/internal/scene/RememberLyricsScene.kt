package com.nevoit.cirro.lyrics.ui.internal.scene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import com.nevoit.cirro.lyrics.ui.internal.rendering.LyricsRenderResources
import com.nevoit.cirro.lyrics.ui.preparation.PreparedLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Shared lifecycle for full lyrics and standalone lines; no preparation happens in composition. */
@Composable
internal fun rememberLyricsScene(
    request: LyricsLayoutRequest,
    color: Color,
    currentPosition: () -> Int,
    preparedLyrics: PreparedLyrics? = null,
): LyricsScene? {
    val position by rememberUpdatedState(currentPosition)
    val layoutState = remember(request, preparedLyrics) { mutableStateOf<LyricsSession?>(null) }
    LaunchedEffect(layoutState) {
        while (isActive) {
            val session = withContext(Dispatchers.Default) {
                LyricsSession(request.lyrics, preparedLyrics ?: request.prepare())
            }
            layoutState.value = session
            // External geometry is caller-owned, including its font invalidation policy.
            if (preparedLyrics != null) return@LaunchedEffect
            snapshotFlow { session.lyrics.hasStaleFonts() }.first { it }
        }
    }
    val session = layoutState.value ?: return null
    val prepared = session.lyrics
    val timeline = session.timeline
    val items = session.items
    LaunchedEffect(timeline) {
        snapshotFlow { position() }.collect { timeline.update(it) }
    }
    val sceneState = remember(prepared, timeline) {
        mutableStateOf<LyricsScene?>(null)
    }
    // Keep the complete previous color variant attached while its replacement is prepared.
    LaunchedEffect(sceneState, color, request.density, request.direction) {
        val resources = withContext(Dispatchers.Default) {
            LyricsRenderResources(prepared, color, request.density, request.direction)
        }
        timeline.update(position())
        sceneState.value = LyricsScene(prepared, timeline, resources, items)
    }
    return sceneState.value
}
