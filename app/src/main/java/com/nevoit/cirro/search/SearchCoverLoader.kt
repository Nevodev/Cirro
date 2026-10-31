package com.nevoit.cirro.search

import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.SearchCandidates
import com.nevoit.cirro.data.model.Song
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class SearchCoverLoader(
    private val loadSongs: suspend (List<Long>) -> Result<List<Song>>,
    private val loadCollection: suspend (CollectionInfo) -> Result<String>,
) {
    private val covers = linkedMapOf<String, String>()

    fun cached(candidates: SearchCandidates): SearchCandidates = candidates.copy(
        songs = candidates.songs.map { song ->
            if (song.cover.isNotBlank()) song else song.copy(
                cover = covers["song:${song.id}"]?.takeIf { it.isNotBlank() }
                    ?: covers["${CollectionKind.ALBUM}:${song.albumId}"].orEmpty(),
            )
        },
        collections = candidates.collections.map { info ->
            if (info.cover.isNotBlank()) info else info.copy(cover = covers["${info.kind}:${info.id}"].orEmpty())
        },
    )

    suspend fun load(candidates: SearchCandidates, onUpdate: (SearchCandidates) -> Unit) {
        var latest = cached(candidates)
        fun publish() {
            val updated = cached(candidates)
            if (updated != latest) {
                latest = updated
                onUpdate(updated)
            }
        }
        val songIds = latest.songs.filter { it.cover.isBlank() && "song:${it.id}" !in covers }.map { it.id }.distinct()
        if (songIds.isNotEmpty()) {
            val songs = loadSongs(songIds)
            currentCoroutineContext().ensureActive()
            songs.getOrNull()?.filter { it.id in songIds }?.forEach { song ->
                remember("song:${song.id}", song.cover)
                if (song.albumId > 0 && song.cover.isNotBlank()) {
                    remember("${CollectionKind.ALBUM}:${song.albumId}", song.cover)
                }
            }
            publish()
        }
        val missing = cached(candidates).collections.filter {
            it.cover.isBlank() && "${it.kind}:${it.id}" !in covers
        }.distinctBy { it.kind to it.id }
        val permits = Semaphore(3)
        coroutineScope {
            for (info in missing) launch {
                permits.withPermit {
                    val cover = loadCollection(info)
                    currentCoroutineContext().ensureActive()
                    cover.getOrNull()?.let { remember("${info.kind}:${info.id}", it) }
                    publish()
                }
            }
        }
    }

    private fun remember(key: String, cover: String) {
        covers.remove(key)
        covers[key] = cover
        if (covers.size > 256) covers.remove(covers.keys.first())
    }
}
