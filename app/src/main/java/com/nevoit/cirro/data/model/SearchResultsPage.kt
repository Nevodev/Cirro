package com.nevoit.cirro.data.model

import kotlinx.serialization.Serializable

@Serializable
internal data class SearchResultsPage(
    val candidates: SearchCandidates = SearchCandidates(),
    val nextOffsets: Map<String, Int> = emptyMap(),
    val nextCursor: String? = null,
)

internal fun SearchCandidates.appendResults(page: SearchCandidates): SearchCandidates = copy(
    songs = (songs + page.songs).distinctBy { it.id },
    collections = (collections + page.collections).distinctBy { it.kind to it.id },
    videos = (videos + page.videos).distinctBy { it.id },
    order = (order + page.order).distinct(),
    itemOrder = (itemOrder + page.itemOrder).distinct(),
)
