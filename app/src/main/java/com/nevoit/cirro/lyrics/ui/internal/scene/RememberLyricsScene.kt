package com.nevoit.cirro.lyrics.ui.internal.scene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import com.nevoit.cirro.lyrics.ui.internal.rendering.LocalLyricsRenderCache
import com.nevoit.cirro.lyrics.ui.internal.rendering.LyricsRenderCache
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
    // 位图缓存由播放页 provide（生命周期 = 播放页），这里只取用。
    val renderCache = LocalLyricsRenderCache.current
    // Keep the complete previous color variant attached while its replacement is prepared.
    LaunchedEffect(sceneState, color, request.density, request.direction, renderCache) {
        // 整曲光栅化是这条链上唯一贵的一步，而且**每次组合都躲不掉**。命中播放页那份缓存时
        // 连后台都不用去：直接拿同一份资源发布，省掉一次线程往返。
        val key = LyricsRenderCache.Key(prepared, color, request.density, request.direction)
        val resources =
            renderCache?.get(key)
                ?: withContext(Dispatchers.Default) {
                    LyricsRenderResources(prepared, color, request.density, request.direction)
                }.also { renderCache?.put(key, it) }
        timeline.update(position())
        sceneState.value = LyricsScene(prepared, timeline, resources, items)
    }
    return sceneState.value
}
