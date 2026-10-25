package com.nevoit.cirro.lyrics.ui.internal.layout

internal data class LyricsSeekPosition(val index: Int, val nextIndex: Int, val fraction: Float)

/** Drag previews interpolate between lyric anchors; playback keeps its discrete focus timeline. */
internal class LyricsSeekTimeline(starts: List<Pair<Int, Int>>) {
    private val anchors = starts.sortedBy { it.second }.distinctBy { it.second }

    fun positionAt(time: Int): LyricsSeekPosition? {
        if (anchors.isEmpty()) return null
        var low = 0
        var high = anchors.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (anchors[middle].second <= time) low = middle + 1 else high = middle
        }
        val current = anchors[(low - 1).coerceAtLeast(0)]
        val next = anchors.getOrNull(low) ?: current
        val duration = next.second.toLong() - current.second
        val fraction = if (duration > 0) {
            ((time.toLong() - current.second).toDouble() / duration).coerceIn(0.0, 1.0).toFloat()
        } else 0f
        return LyricsSeekPosition(current.first, next.first, fraction)
    }
}
