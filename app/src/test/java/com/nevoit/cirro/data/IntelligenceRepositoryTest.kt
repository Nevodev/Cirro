package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.QueueSource
import com.nevoit.cirro.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntelligenceRepositoryTest {
    @Test
    fun playlistQueuesUseTheirOwnPlaylistWithoutFetchingTheLikedList() = runBlocking {
        val repository = IntelligenceRepository(
            likedPlaylist = { error("playlist context must not query liked playlists") },
            fetch = { id, pid, sid ->
                assertEquals(listOf(123L, 456L, 789L), listOf(id, pid, sid))
                Result.success(listOf(Song(999)))
            },
        )
        val seed = repository.seed(123, QueueSource(CollectionKind.PLAYLIST, "456", "歌单"), 42).getOrThrow()
        assertEquals(listOf(Song(999)), repository.songs(seed, 789).getOrThrow())
    }

    @Test
    fun albumsDailyAndSingleSongsUseTheAccountsLikedPlaylist() = runBlocking {
        val users = mutableListOf<Long>()
        val repository = IntelligenceRepository(
            likedPlaylist = { uid -> users.add(uid); Result.success(uid * 100) },
            fetch = { _, _, _ -> Result.success(emptyList()) },
        )
        for (source in listOf(null, QueueSource(CollectionKind.ALBUM, "123"), QueueSource(CollectionKind.DAILY))) {
            assertEquals(IntelligenceSeed(123, 4200), repository.seed(123, source, 42).getOrThrow())
        }
        assertEquals(IntelligenceSeed(123, 4300), repository.seed(123, null, 43).getOrThrow())
        assertEquals(listOf(42L, 42L, 42L, 43L), users)
    }

    @Test
    fun missingContextAndFailedRecommendationsStayFailures() = runBlocking {
        val repository = IntelligenceRepository(
            likedPlaylist = { Result.failure(IllegalStateException("offline")) },
            fetch = { _, _, _ -> Result.failure(IllegalStateException("offline")) },
        )
        assertTrue(repository.seed(123, null, 42).isFailure)
        assertTrue(repository.songs(IntelligenceSeed(123, 456), 789).isFailure)
    }
}
