package com.nevoit.cirro.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
internal data class SearchCandidates(
    val keywords: List<String> = emptyList(),
    val songs: List<Song> = emptyList(),
    val collections: List<CollectionInfo> = emptyList(),
    val order: List<String> = emptyList(),
    val videos: List<ArtistVideo> = emptyList(),
    val itemOrder: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = keywords.isEmpty() && songs.isEmpty() && collections.isEmpty() && videos.isEmpty()

    fun orderedItems(): List<SearchCandidate> {
        val grouped = buildList<SearchCandidate> {
            // Older responses and local results have no order; omitted known types must stay visible.
            for (type in (order + listOf("songs", "artists", "albums", "playlists", "mvs")).distinct()) {
                if (type == "songs") {
                    songs.forEachIndexed { index, song -> add(SearchCandidate.Track(song, index)) }
                } else if (type == "mvs") {
                    videos.forEach { add(SearchCandidate.Video(it)) }
                } else {
                    val kind = when (type) {
                        "artists" -> CollectionKind.ARTIST
                        "albums" -> CollectionKind.ALBUM
                        "playlists" -> CollectionKind.PLAYLIST
                        else -> continue
                    }
                    collections.filter { it.kind == kind }.forEach { add(SearchCandidate.Collection(it)) }
                }
            }
        }
        if (itemOrder.isEmpty()) return grouped
        val positions = itemOrder.withIndex().associate { it.value to it.index }
        return grouped.sortedBy { positions[it.key] ?: Int.MAX_VALUE }
    }
}

@Serializable
internal sealed interface SearchCandidate {
    val key: String

    @Serializable
    @SerialName("keyword")
    data class Keyword(val text: String) : SearchCandidate {
        override val key: String get() = "keyword:$text"
    }

    @Serializable
    @SerialName("song")
    data class Track(val song: Song, val songIndex: Int) : SearchCandidate {
        override val key: String get() = "song:${song.id}"
    }

    @Serializable
    @SerialName("collection")
    data class Collection(val info: CollectionInfo) : SearchCandidate {
        override val key: String get() = "${info.kind}:${info.id}"
    }

    @Serializable
    @SerialName("video")
    data class Video(val video: ArtistVideo) : SearchCandidate {
        override val key: String get() = "mv:${video.id}"
    }
}
