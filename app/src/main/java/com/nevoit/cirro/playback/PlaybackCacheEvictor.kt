package com.nevoit.cirro.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

@Serializable
internal data class CachedSongUse(val plays: Long = 0, val lastPlayedAt: Long = 0)

@OptIn(UnstableApi::class)
internal class PlaybackCacheEvictor(
    private val maxBytes: Long,
    private val uses: ConcurrentHashMap<Long, CachedSongUse>,
) : CacheEvictor {
    @Volatile var playingSongId: Long? = null
    @Volatile var nextSongId: Long? = null

    private val spans = mutableSetOf<CacheSpan>()
    private var bytes = 0L
    private var initialized = false
    private var cache: Cache? = null

    override fun requiresCacheSpanTouches() = true

    override fun onCacheInitialized() {
        initialized = true
        cache?.let { trim(it, 0, null) }
    }

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) trim(cache, length, key)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        this.cache = cache
        if (spans.add(span)) bytes += span.length
        if (initialized) trim(cache, 0, span.key)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        if (spans.remove(span)) bytes -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        spans.remove(oldSpan)
        spans.add(newSpan)
    }

    private fun trim(cache: Cache, incoming: Long, writingKey: String?) {
        while (bytes + incoming > maxBytes && spans.isNotEmpty()) {
            // 当前、下一首和正在提交的片段最后才淘汰，避免两路加载反复挤掉彼此的数据。
            val candidates = spans.filter {
                it.key != writingKey && songId(it.key) != playingSongId && songId(it.key) != nextSongId
            }
            val victim = candidates.minWithOrNull(
                compareBy<CacheSpan> { uses[songId(it.key)]?.plays ?: 0 }
                    .thenBy { uses[songId(it.key)]?.lastPlayedAt ?: 0 }
                    .thenBy { it.lastTouchTimestamp }
                    .thenBy { it.key }
                    .thenBy { it.position }
            )
            if (victim != null) {
                val id = songId(victim.key)
                spans.filter { songId(it.key) == id && it.key != writingKey }
                    .map { it.key }.toSet().forEach(cache::removeResource)
            } else {
                // 单首音频也可能超过容量；此时保留最近片段，仍严格限制总量。
                cache.removeSpan(spans.minBy { it.lastTouchTimestamp })
            }
        }
    }

    private fun songId(key: String): Long = key.substringBefore(':').toLongOrNull() ?: 0L
}
