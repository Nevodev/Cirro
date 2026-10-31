package com.nevoit.cirro.search

import com.nevoit.cirro.data.model.LibraryContent
import com.nevoit.cirro.data.model.SearchCandidates
import com.nevoit.cirro.detail.CollectionSongSearchIndex
import com.nevoit.cirro.library.LibraryCollectionSearchIndex

internal class LibrarySearchIndex private constructor(
    val source: LibraryContent,
    private val songs: CollectionSongSearchIndex,
    private val collections: LibraryCollectionSearchIndex,
) {
    suspend fun find(query: String): SearchCandidates = SearchCandidates(
        songs = songs.find(query).map { it.value },
        collections = collections.find(query).map { it.info },
    )

    companion object {
        suspend fun build(content: LibraryContent): LibrarySearchIndex = LibrarySearchIndex(
            content,
            CollectionSongSearchIndex.build(content.songs),
            LibraryCollectionSearchIndex.build(content.artists + content.albums + content.playlists),
        )
    }
}
