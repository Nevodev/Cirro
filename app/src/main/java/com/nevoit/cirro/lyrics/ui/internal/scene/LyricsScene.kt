package com.nevoit.cirro.lyrics.ui.internal.scene

import com.nevoit.cirro.lyrics.ui.internal.playback.LyricsPlaybackTimeline
import com.nevoit.cirro.lyrics.ui.preparation.PreparedLyrics
import com.nevoit.cirro.lyrics.ui.internal.rendering.LyricsRenderResources

/** A published scene has complete geometry and rasters, with a view-local playback owner. */
internal class LyricsScene(
    val lyrics: PreparedLyrics,
    val timeline: LyricsPlaybackTimeline,
    val resources: LyricsRenderResources,
    val items: LyricsItemMapping,
)
