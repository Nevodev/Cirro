package com.nevoit.cirro.lyrics.ui.internal.scene

import com.nevoit.cirro.lyrics.core.model.SyncedLyrics
import com.nevoit.cirro.lyrics.ui.internal.playback.LyricsPlaybackTimeline
import com.nevoit.cirro.lyrics.ui.preparation.PreparedLyrics

/** Build the event index and item mapping on the preparation worker, once per layout. */
internal class LyricsSession(
    source: SyncedLyrics,
    val lyrics: PreparedLyrics
) {
    val timeline = LyricsPlaybackTimeline(source, lyrics)
    val items = LyricsItemMapping(lyrics)
}
