package com.nevoit.cirro.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nevoit.cirro.data.CollectionRepository
import com.nevoit.cirro.data.NcmBridge
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.SongQualityInfo
import com.nevoit.cirro.detail.formatDate
import com.nevoit.cirro.lyrics.data.AppleCreditGroup
import com.nevoit.cirro.lyrics.data.AppleSongMatcher
import com.nevoit.cirro.lyrics.data.LyricsBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SongCreditsViewModel internal constructor(
    song: Song,
    loadSong: suspend (Song) -> Song,
    loadReleaseDate: suspend (Song) -> String?,
    loadCredits: suspend (Song) -> List<AppleCreditGroup>,
    loadQuality: suspend (Long) -> SongQualityInfo?,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {
    constructor(repository: CollectionRepository, song: Song) : this(
        song = song,
        loadSong = { original -> repository.songMetadata(original.id).getOrNull() ?: original },
        loadReleaseDate = { resolved ->
            if (resolved.albumId > 0) {
                repository.info(CollectionKind.ALBUM, resolved.albumId).getOrNull()
                    ?.let { formatDate(it.updateTimeMs) }
            } else null
        },
        loadCredits = ::fetchCredits,
        loadQuality = { id -> withContext(Dispatchers.IO) { NcmBridge.songQuality(id).getOrNull() } },
    )

    data class UiState(
        val song: Song,
        val releaseDate: String? = null,
        val credits: List<AppleCreditGroup> = emptyList(),
        val qualityInfo: SongQualityInfo? = null,
    )

    private val _state = MutableStateFlow(UiState(song))
    val state = _state.asStateFlow()

    init {
        // The navigation entry retains this ViewModel across theme changes, including active loads.
        viewModelScope.launch {
            val resolved = async {
                val metadata = if (song.cover.isBlank() || song.artists.isEmpty() ||
                    song.album.isBlank() || song.albumId <= 0 || song.durationMs <= 0
                ) loadOrNull { loadSong(song) } ?: song else song
                _state.update { it.copy(song = metadata) }
                metadata
            }
            launch {
                val date = loadOrNull { loadReleaseDate(resolved.await()) }
                _state.update { it.copy(releaseDate = date) }
            }
            launch {
                val credits = loadOrNull { loadCredits(resolved.await()) }.orEmpty()
                _state.update { it.copy(credits = credits) }
            }
            launch {
                val quality = loadOrNull { loadQuality(song.id) }
                _state.update { it.copy(qualityInfo = quality) }
            }
        }
    }
}

private suspend fun <T> loadOrNull(load: suspend () -> T): T? = try {
    load()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

private suspend fun fetchCredits(song: Song): List<AppleCreditGroup> = withContext(Dispatchers.IO) {
    val term = listOf(song.name, song.artists.firstOrNull().orEmpty())
        .filter { it.isNotBlank() }.joinToString(" ")
    require(term.isNotBlank()) { "歌曲信息不足，无法查找制作人员" }
    var searchError: Throwable? = null
    for (storefront in listOf("cn", "us")) {
        val result = LyricsBridge.appleSearch(term, storefront = storefront)
        if (result.isFailure) {
            searchError = result.exceptionOrNull()
            continue
        }
        val match = AppleSongMatcher.best(result.getOrThrow().songs, song) ?: continue
        return@withContext LyricsBridge.appleSongCredits(match.id, storefront).getOrThrow()
    }
    if (searchError != null) throw searchError
    emptyList()
}
