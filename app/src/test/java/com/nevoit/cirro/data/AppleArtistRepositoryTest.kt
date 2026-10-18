package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.AppleArtistMatch
import com.nevoit.cirro.data.model.AppleArtistSongMatch
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.lyrics.data.AppleArtistCandidate
import com.nevoit.cirro.lyrics.data.AppleArtistHeader
import com.nevoit.cirro.lyrics.data.AppleArtistNameLogo
import com.nevoit.cirro.lyrics.data.AppleSearchResult
import com.nevoit.cirro.lyrics.data.AppleSongCandidate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AppleArtistRepositoryTest {
    private val songs = listOf(
        Song(1, "First", listOf("Artist"), "A", durationMs = 180_000, artistIds = listOf(42)),
        Song(2, "Second", listOf("Artist"), "B", durationMs = 190_000, artistIds = listOf(42)),
    )

    @Test
    fun constructionDoesNotLoadOrQueryAnything() {
        Fixture().use { fixture ->
            assertEquals(0, fixture.reads.get())
            assertEquals(0, fixture.searches.get())
        }
    }

    @Test
    fun persistedMappingIsUsedWithoutSearchingOrNeedingSongs() = runBlocking {
        val saved = AppleArtistMatch("100", "cn", listOf(AppleArtistSongMatch(1, "1"), AppleArtistSongMatch(2, "2")))
        Fixture(initial = mapOf(42L to saved)).use { fixture ->
            assertEquals(saved, fixture.repository.resolve(42, "Artist", emptyList()))
            assertEquals(saved, fixture.repository.cached(42))
            assertEquals(1, fixture.reads.get())
            assertEquals(0, fixture.searches.get())
        }
    }

    @Test
    fun successfulMatchRoundTripsIntoANewRepository() = runBlocking {
        var disk = ""
        Fixture(save = { disk = Json.encodeToString(it) }).use { fixture ->
            val match = fixture.repository.resolve(42, "Artist", songs)!!
            assertEquals("100", match.appleArtistId)
            Fixture(initial = Json.decodeFromString(disk)).use { restored ->
                assertEquals(match, restored.repository.resolve(42, "Artist", emptyList()))
                assertEquals(0, restored.searches.get())
            }
            assertEquals(match, fixture.repository.resolve(42, "Artist", songs))
            assertEquals(2, fixture.searches.get())
        }
    }

    @Test
    fun concurrentPageRequestsShareTheSameSearch() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        Fixture(beforeSearch = {
            entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS))
        }).use { fixture ->
            try {
                val first = async(Dispatchers.Default) { fixture.repository.resolve(42, "Artist", songs) }
                withTimeout(5_000) { entered.await() }
                val second = async(Dispatchers.Default) { fixture.repository.resolve(42, "Artist", songs) }
                release.countDown()
                assertEquals(first.await(), second.await())
                assertEquals(2, fixture.searches.get())
                assertEquals(1, fixture.writes.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun leavingTheFirstPageDoesNotCancelPersistence() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        Fixture(beforeSearch = {
            entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS))
        }).use { fixture ->
            try {
                val caller = async(Dispatchers.Default) { fixture.repository.resolve(42, "Artist", songs) }
                withTimeout(5_000) { entered.await() }
                caller.cancelAndJoin()
                release.countDown()
                val match = withTimeout(5_000) { fixture.repository.resolve(42, "Artist", songs) }
                assertEquals("100", match?.appleArtistId)
                assertEquals(2, fixture.searches.get())
                assertEquals(1, fixture.writes.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun failedNetworkIsNotPersistedAndCanBeRetried() = runBlocking {
        var fail = true
        Fixture(failSearch = { fail }).use { fixture ->
            assertNull(fixture.repository.resolve(42, "Artist", songs))
            assertEquals(0, fixture.writes.get())
            fail = false
            assertEquals("100", fixture.repository.resolve(42, "Artist", songs)?.appleArtistId)
            assertEquals(1, fixture.writes.get())
        }
    }

    @Test
    fun failedWriteDoesNotPretendTheMappingWasPersisted() = runBlocking {
        var fail = true
        Fixture(save = { if (fail) throw IOException("Unavailable storage") }).use { fixture ->
            assertNull(fixture.repository.resolve(42, "Artist", songs))
            assertNull(fixture.repository.cached(42))
            fail = false
            assertEquals("100", fixture.repository.resolve(42, "Artist", songs)?.appleArtistId)
        }
    }

    @Test
    fun insufficientEvidenceDoesNotSearchOrWrite() = runBlocking {
        Fixture().use { fixture ->
            assertNull(fixture.repository.resolve(42, "Artist", songs.take(1)))
            assertTrue(fixture.saved.isEmpty())
            assertEquals(0, fixture.searches.get())
        }
    }

    @Test
    fun videoIsLazyAndOnlyCachedInMemory() = runBlocking {
        val match = AppleArtistMatch("100", "cn", emptyList())
        Fixture(fetchHeader = { _, _ -> Result.success(AppleArtistHeader(videoUrl = "https://example.com/square.m3u8")) }).use { fixture ->
            assertEquals(0, fixture.videoReads.get())
            repeat(2) { assertEquals("https://example.com/square.m3u8", fixture.repository.video(match)) }
            assertEquals(1, fixture.videoReads.get())
            assertEquals(0, fixture.writes.get())
            assertEquals(0, fixture.searches.get())
        }
    }

    @Test
    fun missingSquareVideoDoesNotEraseTheArtistMapping() = runBlocking {
        val match = AppleArtistMatch("100", "cn", emptyList())
        Fixture(initial = mapOf(42L to match)).use { fixture ->
            repeat(2) { assertNull(fixture.repository.video(match)) }
            assertEquals(match, fixture.repository.cached(42))
            assertEquals(1, fixture.videoReads.get())
            assertEquals(0, fixture.writes.get())
        }
    }

    @Test
    fun videoFailureCanRetryAndStorefrontsRemainSeparate() = runBlocking {
        val match = AppleArtistMatch("100", "cn", emptyList())
        var failed = true
        Fixture(fetchHeader = { _, storefront ->
            if (failed) Result.failure(IOException("Offline")) else Result.success(AppleArtistHeader(videoUrl = "https://example.com/$storefront.m3u8"))
        }).use { fixture ->
            assertNull(fixture.repository.video(match))
            failed = false
            assertEquals("https://example.com/cn.m3u8", fixture.repository.video(match))
            assertEquals("https://example.com/us.m3u8", fixture.repository.video(match.copy(storefront = "us")))
            assertEquals(3, fixture.videoReads.get())
        }
    }

    @Test
    fun logoWithoutVideoUsesTheSameLazyMetadataCache() = runBlocking {
        val match = AppleArtistMatch("100", "cn", emptyList())
        val logo = AppleArtistNameLogo("https://example.com/{w}x{h}.png", 30000, 5000)
        Fixture(fetchHeader = { _, _ -> Result.success(AppleArtistHeader(nameLogo = logo)) }).use { fixture ->
            assertEquals(0, fixture.videoReads.get())
            assertNull(fixture.repository.video(match))
            assertEquals(logo, fixture.repository.header(match)?.nameLogo)
            assertEquals(1, fixture.videoReads.get())
            assertEquals(0, fixture.writes.get())
            assertEquals(0, fixture.searches.get())
        }
    }

    private inner class Fixture(
        initial: Map<Long, AppleArtistMatch> = emptyMap(),
        save: (Map<Long, AppleArtistMatch>) -> Unit = {},
        beforeSearch: () -> Unit = {},
        failSearch: () -> Boolean = { false },
        fetchHeader: (String, String) -> Result<AppleArtistHeader> = { _, _ -> Result.success(AppleArtistHeader()) },
    ) : Closeable {
        private val job = SupervisorJob()
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        val searches = AtomicInteger()
        val videoReads = AtomicInteger()
        var saved = initial
        val repository = AppleArtistRepository(
            CoroutineScope(job + Dispatchers.Default),
            read = { reads.incrementAndGet(); saved },
            write = { save(it); saved = it; writes.incrementAndGet() },
            search = { term, storefront ->
                searches.incrementAndGet()
                beforeSearch()
                if (failSearch()) Result.failure(IOException("Offline")) else {
                    val song = songs.first { term.startsWith(it.name) }
                    Result.success(AppleSearchResult(
                        listOf(AppleSongCandidate(song.id.toString(), song.name, "Artist", song.album,
                            song.durationMs, artists = listOf(AppleArtistCandidate("100", "Artist")))),
                        storefront.ifEmpty { "cn" }, false,
                    ))
                }
            },
            fetchHeader = { id, storefront -> videoReads.incrementAndGet(); fetchHeader(id, storefront) },
        )

        override fun close() { job.cancel() }
    }
}
