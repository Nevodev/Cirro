package com.nevoit.cirro.search

import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.SearchCandidates

internal enum class SearchCategory(
    val title: String,
    val wireType: String,
    val resultKey: String? = null,
    private val collectionKind: CollectionKind? = null,
) {
    BEST("最佳结果", "1018"),
    ARTISTS("艺人", "100", "artists", CollectionKind.ARTIST),
    ALBUMS("专辑", "10", "albums", CollectionKind.ALBUM),
    SONGS("歌曲", "1", "songs"),
    PLAYLISTS("播放列表", "1000", "playlists", CollectionKind.PLAYLIST),
    MUSIC_VIDEOS("音乐视频", "1004", "mvs");

    fun filter(candidates: SearchCandidates): SearchCandidates =
        if (this == BEST) candidates else candidates.copy(
            keywords = emptyList(),
            songs = if (this == SONGS) candidates.songs else emptyList(),
            collections = candidates.collections.filter { it.kind == collectionKind },
            videos = if (this == MUSIC_VIDEOS) candidates.videos else emptyList(),
            order = listOfNotNull(resultKey),
        )
}
