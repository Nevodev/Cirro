package com.nevoit.cirro.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.LibraryCollection
import com.nevoit.cirro.data.model.albumDisplayTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale

internal class LibraryCollectionSearchIndex private constructor(
    val collections: List<LibraryCollection>,
    private val text: List<String>,
) {
    suspend fun find(query: String): List<LibraryCollection> {
        val terms = query.trim().lowercase(Locale.ROOT).split(Whitespace).filter(String::isNotEmpty)
        val context = currentCoroutineContext()
        return buildList {
            text.forEachIndexed { index, searchable ->
                if (index % 64 == 0) context.ensureActive()
                if (terms.all(searchable::contains)) add(collections[index])
            }
        }
    }

    companion object {
        private val Whitespace = Regex("\\s+")

        suspend fun build(collections: List<LibraryCollection>): LibraryCollectionSearchIndex {
            val context = currentCoroutineContext()
            val text = collections.mapIndexed { index, collection ->
                if (index % 64 == 0) context.ensureActive()
                val info = collection.info
                val title = if (info.kind == CollectionKind.ALBUM) {
                    albumDisplayTitle(info.title, info.albumType)
                } else info.title
                buildString {
                    append(title)
                    if (info.kind != CollectionKind.ARTIST) {
                        append('\u0000').append(info.author)
                        collection.artistNames.forEach { append('\u0000').append(it) }
                    }
                }.lowercase(Locale.ROOT)
            }
            return LibraryCollectionSearchIndex(collections, text)
        }
    }
}

internal class LibraryCollectionSearchResult(
    val collections: List<LibraryCollection>,
    val query: String,
    val matches: List<LibraryCollection>,
)

@Composable
internal fun rememberLibraryCollectionSearchResults(
    collections: List<LibraryCollection>,
    query: String,
): LibraryCollectionSearchResult? {
    // An unchanged category may arrive as an equal new list when another category refreshes.
    // Keep its identity aligned with the index while allowing stale async results to be rejected.
    val source = remember(collections) { collections }
    val index by produceState<LibraryCollectionSearchIndex?>(null, source) {
        value = withContext(Dispatchers.Default) { LibraryCollectionSearchIndex.build(source) }
    }
    val result by produceState<LibraryCollectionSearchResult?>(null, source, index, query) {
        if (query.isBlank()) {
            value = null
            return@produceState
        }
        val current = index?.takeIf { it.collections === source } ?: return@produceState
        delay(60)
        value = withContext(Dispatchers.Default) {
            LibraryCollectionSearchResult(source, query, current.find(query))
        }
    }
    return result?.takeIf { it.collections === source }
}
