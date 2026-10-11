package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.QueueSource
import com.nevoit.cirro.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class IntelligenceSeed(val songId: Long, val playlistId: Long)

class IntelligenceRepository(
    private val likedPlaylist: (Long) -> Result<Long> = { NcmBridge.likedPlaylistId(it) },
    private val fetch: (Long, Long, Long) -> Result<List<Song>> = { id, pid, sid ->
        NcmBridge.intelligenceSongs(id, pid, sid)
    },
) {
    suspend fun seed(songId: Long, source: QueueSource?, userId: Long): Result<IntelligenceSeed> =
        withContext(Dispatchers.IO) {
            val pid = source?.takeIf { it.kind == CollectionKind.PLAYLIST }
                ?.id?.toLongOrNull()?.takeIf { it > 0 }
            if (pid != null) Result.success(IntelligenceSeed(songId, pid))
            else likedPlaylist(userId).map { IntelligenceSeed(songId, it) }
        }

    suspend fun songs(seed: IntelligenceSeed, startId: Long): Result<List<Song>> =
        withContext(Dispatchers.IO) { fetch(seed.songId, seed.playlistId, startId) }
}
