package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.AppleArtistMatch
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.lyrics.data.AppleSearchResult
import com.nevoit.cirro.lyrics.data.LyricsBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AppleArtistRepository(
    private val scope: CoroutineScope,
    private val read: () -> Map<Long, AppleArtistMatch>,
    private val write: (Map<Long, AppleArtistMatch>) -> Unit,
    private val search: (String, String) -> Result<AppleSearchResult> = { term, storefront ->
        LyricsBridge.appleArtistSearch(term, storefront = storefront)
    },
    private val onFailure: (Throwable) -> Unit = {},
    private val fetchVideo: (String, String) -> Result<String?> = { id, storefront ->
        LyricsBridge.appleArtistVideo(id, storefront)
    },
) {
    private val mutex = Mutex()
    private var matches: MutableMap<Long, AppleArtistMatch>? = null
    private val inFlight = mutableMapOf<Long, Deferred<AppleArtistMatch?>>()
    private val videos = mutableMapOf<Pair<String, String>, Deferred<String?>>()

    suspend fun video(match: AppleArtistMatch): String? {
        val key = match.appleArtistId to match.storefront
        val request = mutex.withLock {
            videos.getOrPut(key) {
                scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    try {
                        fetchVideo(key.first, key.second).getOrThrow()
                    } catch (error: Exception) {
                        withContext(NonCancellable) { mutex.withLock { videos.remove(key) } }
                        if (error is CancellationException) throw error
                        onFailure(error)
                        null
                    }
                }
            }
        }
        request.start()
        return request.await()
    }

    suspend fun cached(artistId: Long): AppleArtistMatch? = withContext(Dispatchers.IO) {
        mutex.withLock { load()[artistId] }
    }

    suspend fun resolve(artistId: Long, name: String, songs: List<Song>): AppleArtistMatch? =
        withContext(Dispatchers.IO) {
            if (artistId <= 0) return@withContext null
            val request = mutex.withLock {
                load()[artistId]?.let { return@withContext it }
                inFlight.getOrPut(artistId) {
                    // 发起页面离开后仍完成这次匹配及落盘，后来的页面共享同一个请求。
                    scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                        try {
                            find(artistId, name, songs)?.also { match ->
                                mutex.withLock {
                                    val updated = load().toMutableMap().apply { put(artistId, match) }
                                    write(updated)
                                    matches = updated
                                }
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            onFailure(error)
                            null
                        } finally {
                            withContext(NonCancellable) {
                                mutex.withLock { inFlight.remove(artistId) }
                            }
                        }
                    }
                }
            }
            request.start()
            request.await()
        }

    private fun load(): MutableMap<Long, AppleArtistMatch> = matches ?: runCatching { read().toMutableMap() }
        .getOrElse { onFailure(it); mutableMapOf() }
        .also { matches = it }

    private suspend fun find(artistId: Long, name: String, songs: List<Song>): AppleArtistMatch? {
        val representatives = AppleArtistMatcher.representatives(artistId, songs)
        if (representatives.size < 2) return null
        for (storefront in listOf("", "us")) {
            val results = coroutineScope {
                representatives.map { song ->
                    async(Dispatchers.IO) {
                        val result = search("${song.name} $name".trim(), storefront)
                        result.exceptionOrNull()?.let(onFailure)
                        song to result
                    }
                }.awaitAll()
            }
            val successful = results.mapNotNull { (song, result) -> result.getOrNull()?.let { song to it } }
            AppleArtistMatcher.match(artistId, name, successful)?.let { return it }
            if (results.any { it.second.isFailure } || successful.all { it.second.storefront == "us" }) break
        }
        return null
    }
}
