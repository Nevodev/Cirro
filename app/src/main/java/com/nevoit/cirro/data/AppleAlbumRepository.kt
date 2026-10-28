package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.AppleAlbumMatch
import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.lyrics.data.AppleAlbum
import com.nevoit.cirro.lyrics.data.AppleAlbumSearchResult
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

class AppleAlbumRepository(
    private val scope: CoroutineScope,
    private val read: () -> Map<Long, AppleAlbumMatch>,
    private val write: (Map<Long, AppleAlbumMatch>) -> Unit,
    private val search: (String, String) -> Result<AppleAlbumSearchResult> = LyricsBridge::appleAlbumSearch,
    private val fetch: (String, String) -> Result<AppleAlbum> = LyricsBridge::appleAlbumDetail,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val mutex = Mutex()
    private var matches: Map<Long, AppleAlbumMatch>? = null
    private val inFlight = mutableMapOf<Long, Deferred<AppleAlbumMatch?>>()
    private val details = mutableMapOf<AppleAlbumMatch, Deferred<AppleAlbum?>>()

    suspend fun cached(id: Long): AppleAlbumMatch? = withContext(Dispatchers.IO) {
        mutex.withLock { load()[id] }
    }

    suspend fun video(match: AppleAlbumMatch): String? = detail(match)?.videoUrl

    suspend fun portraitVideo(match: AppleAlbumMatch): String? = detail(match)?.portraitVideoUrl

    suspend fun resolve(info: CollectionInfo, songs: List<Song>): AppleAlbumMatch? = withContext(Dispatchers.IO) {
        if (info.kind != CollectionKind.ALBUM || info.id <= 0 || songs.isEmpty()) return@withContext null
        val request = mutex.withLock {
            load()[info.id]?.let { return@withContext it }
            inFlight.getOrPut(info.id) {
                scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    try {
                        find(info, songs)?.also { match ->
                            mutex.withLock {
                                val updated = load() + (info.id to match)
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
                        withContext(NonCancellable) { mutex.withLock { inFlight.remove(info.id) } }
                    }
                }
            }
        }
        request.start()
        request.await()
    }

    private suspend fun detail(match: AppleAlbumMatch): AppleAlbum? {
        val request = mutex.withLock {
            details.getOrPut(match) {
                scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    try {
                        fetch(match.appleAlbumId, match.storefront).getOrThrow().also {
                            check(it.id == match.appleAlbumId) { "Apple 专辑 id 不一致" }
                        }
                    } catch (error: Exception) {
                        withContext(NonCancellable) { mutex.withLock { details.remove(match) } }
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

    private suspend fun find(info: CollectionInfo, songs: List<Song>): AppleAlbumMatch? {
        var fallback: AppleAlbumMatch? = null
        for (storefront in listOf("", "us")) {
            val result = search("${info.title} ${info.primaryArtistName.ifBlank { info.author }}".trim(), storefront)
                .getOrThrow()
            check(result.storefront.isNotBlank()) { "Apple 专辑搜索缺少区域" }
            val candidates = AppleAlbumMatcher.candidates(info, result.albums)
            val albums = candidates.chunked(4).flatMap { batch ->
                coroutineScope {
                    batch.map { candidate ->
                        async { detail(AppleAlbumMatch(candidate.id, result.storefront)) }
                    }.awaitAll()
                }
            }
            // 任一候选失败都不能假定它不是正确版本；保留静态封面，下次打开重试。
            if (albums.any { it == null }) return null
            AppleAlbumMatcher.match(info, songs, albums.filterNotNull())?.let { album ->
                val match = AppleAlbumMatch(album.id, result.storefront)
                if (!album.videoUrl.isNullOrBlank()) return match
                if (fallback == null) fallback = match
            }
            if (result.storefront == "us") break
        }
        return fallback
    }

    private fun load(): Map<Long, AppleAlbumMatch> = matches ?: runCatching(read)
        .getOrElse { onFailure(it); emptyMap() }.also { matches = it }
}
