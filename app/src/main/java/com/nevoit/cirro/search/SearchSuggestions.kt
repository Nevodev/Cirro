package com.nevoit.cirro.search

import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.SearchCandidate
import com.nevoit.cirro.data.model.SearchCandidates
import com.nevoit.cirro.detail.normalizeSearchText

internal fun SearchCandidates.suggestionItems(query: String): List<SearchCandidate> {
    val ordered = orderedItems()
    val name = normalizeSearchText(query)
    if (name.isEmpty()) return ordered
    val (exact, remaining) = ordered.partition { candidate ->
        val title = when (candidate) {
            is SearchCandidate.Keyword -> null
            is SearchCandidate.Video -> null
            is SearchCandidate.Track -> candidate.song.name
            is SearchCandidate.Collection -> candidate.info.title.takeIf {
                candidate.info.kind == CollectionKind.ARTIST || candidate.info.kind == CollectionKind.ALBUM
            }
        }
        title != null && normalizeSearchText(title) == name
    }
    return exact + remaining
}
