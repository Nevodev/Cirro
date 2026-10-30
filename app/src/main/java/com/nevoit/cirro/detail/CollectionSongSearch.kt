package com.nevoit.cirro.detail

import com.nevoit.cirro.data.model.Song
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class CollectionSongSearchIndex private constructor(
    val songs: List<Song>,
    private val text: List<String>,
) {
    suspend fun find(query: String): List<IndexedValue<Song>> {
        val terms = searchTerms(query)
        if (terms.isEmpty() && query.isNotBlank()) return emptyList()
        val context = currentCoroutineContext()
        return buildList {
            text.forEachIndexed { index, searchable ->
                if (index % 64 == 0) context.ensureActive()
                if (terms.all(searchable::contains)) add(IndexedValue(index, songs[index]))
            }
        }
    }

    companion object {
        suspend fun build(songs: List<Song>): CollectionSongSearchIndex {
            val context = currentCoroutineContext()
            val text = songs.mapIndexed { index, song ->
                if (index % 64 == 0) context.ensureActive()
                normalizeSearchText(buildString {
                    append(song.name)
                    song.artists.forEach { append('\u0000').append(it) }
                    append('\u0000').append(song.album)
                })
            }
            return CollectionSongSearchIndex(songs, text)
        }
    }
}

internal class CollectionSongSearchResult(
    val songs: List<Song>,
    val query: String,
    val matches: List<IndexedValue<Song>>,
)
