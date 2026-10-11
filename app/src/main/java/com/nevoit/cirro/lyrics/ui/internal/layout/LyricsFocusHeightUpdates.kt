package com.nevoit.cirro.lyrics.ui.internal.layout

import com.nevoit.cirro.lyrics.ui.composable.list.LyricsListItem
import com.nevoit.cirro.lyrics.ui.internal.playback.FocusState

internal class LyricsFocusHeightUpdates(
    val items: List<LyricsListItem>,
    private val focus: () -> FocusState,
    private val itemIndex: (Int) -> Int,
) {
    private var previous: FocusState? = null

    fun reset() {
        previous = focus()
    }

    fun consumeChanges(): IntArray? {
        val current = focus()
        val last = previous
        previous = current
        // Without a baseline, reconcile every item with the existing height index once.
        if (last == null) return null
        if (last.allIndices == current.allIndices &&
            last.activeIntro == current.activeIntro &&
            last.activeInterludeIndex == current.activeInterludeIndex
        ) return EmptyIndices

        val changed = sortedSetOf<Int>()
        fun add(sourceIndex: Int) {
            val index = itemIndex(sourceIndex)
            if (index in items.indices) changed.add(index)
        }
        for (index in last.allIndices) if (index !in current.allIndices) add(index)
        for (index in current.allIndices) if (index !in last.allIndices) add(index)
        if (last.activeIntro != current.activeIntro) add(0)
        if (last.activeInterludeIndex != current.activeInterludeIndex) {
            last.activeInterludeIndex?.let(::add)
            current.activeInterludeIndex?.let(::add)
        }
        return changed.toIntArray()
    }

    private companion object {
        val EmptyIndices = intArrayOf()
    }
}
