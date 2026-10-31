package com.nevoit.cirro.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import com.nevoit.cirro.data.AccountRepository
import com.nevoit.cirro.data.AudioQualityStore
import com.nevoit.cirro.data.NcmBridge
import com.nevoit.cirro.data.model.AudioQuality
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.SongAudio
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class CachedAudio(
    val key: String,
    val songId: Long,
    val requestedQuality: String,
    val level: String,
    val size: Long,
    val gainDb: Double?,
    val peak: Double?,
)

/** 唯一实例活过播放服务；索引、数据库和目录扫描全部在后台准备。 */
@OptIn(UnstableApi::class)
internal class PlaybackAudioCache(
    context: Context,
    private val scope: CoroutineScope,
    private val account: AccountRepository,
    private val quality: AudioQualityStore,
) {
    val priority = PriorityTaskManager()
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val indexFile = AtomicFile(File(appContext.filesDir, "playback/audio-cache.json"))
    private val indexLock = Any()
    private var index = AudioCacheIndex()
    private val uses = ConcurrentHashMap<Long, CachedSongUse>()
    private val urls = ConcurrentHashMap<String, CachedUrl>()
    private val resolutionLocks = ConcurrentHashMap<String, Any>()
    private val evictor = PlaybackCacheEvictor(MaxBytes, uses)
    @Volatile private var playingEntryKey: String? = null
    private val storage = scope.async(Dispatchers.IO) {
        synchronized(indexLock) {
            index = readIndex()
            uses.putAll(index.uses)
        }
        try {
            val cache = SimpleCache(
                File(appContext.cacheDir, "playback-audio"), evictor,
                StandaloneDatabaseProvider(appContext),
            )
            try {
                cache.checkInitialization()
                cache
            } catch (error: Exception) {
                cache.release()
                throw error
            }
        } catch (error: Exception) {
            Log.w(Tag, "音频缓存不可用，回退网络播放", error)
            null
        }
    }

    fun owner(): Long = when (account.session.value) {
        Session.LoggedOut -> 0L
        else -> account.profile.value?.userId ?: 0L
    }

    fun setPlayingSong(songId: Long?, entryKey: String? = null) {
        evictor.playingSongId = songId
        playingEntryKey = entryKey
    }

    fun setNextSong(songId: Long?) {
        evictor.nextSongId = songId
    }

    fun recordPlay(songId: Long) {
        scope.launch(Dispatchers.IO) {
            storage.await()
            synchronized(indexLock) {
                val previous = uses[songId] ?: CachedSongUse()
                uses[songId] = CachedSongUse(
                    (previous.plays + 1).coerceAtLeast(previous.plays), System.currentTimeMillis(),
                )
                index = index.copy(uses = uses.toMap())
                writeIndex()
            }
        }
    }

    fun factory(onResolved: (String, CachedAudio) -> Unit): DataSource.Factory =
        DataSource.Factory { AudioDataSource(onResolved) }

    fun preload(songId: Long, requested: AudioQuality, owner: Long, task: AudioPreloadTask) {
        val cache = awaitStorage() ?: return
        if (task.cancelled) return
        priority.add(C.PRIORITY_DOWNLOAD)
        try {
            priority.proceed(C.PRIORITY_DOWNLOAD)
            if (task.cancelled) return
            val audio = resolve(songId, requested, owner, cache)
            if (task.cancelled) return
            val protectedSize = cache.keys.filter { it.substringBefore(':').toLongOrNull() == evictor.playingSongId }
                .sumOf { key -> cache.getCachedSpans(key).sumOf { it.length } }
            val budget = (MaxBytes - protectedSize).coerceAtLeast(0)
            val length = if (audio.size > 0) minOf(audio.size, budget) else budget
            if (length == 0L) return
            val writer = CacheWriter(
                cacheFactory(cache, audio, preload = true).createDataSource(),
                DataSpec.Builder().setUri(songUri(songId)).setKey(audio.key).setLength(length)
                    .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build(),
                null, null,
            )
            task.writer = writer
            if (task.cancelled) writer.cancel()
            while (!task.cancelled) {
                priority.proceed(C.PRIORITY_DOWNLOAD)
                try {
                    writer.cache()
                    break
                } catch (_: PriorityTaskManager.PriorityTooLowException) {
                    // 前台恢复加载时只让出网络，不销毁目标；恢复后 CacheWriter 会跳过已落盘区间。
                }
            }
            if (task.cancelled) return
            val total = ContentMetadata.getContentLength(cache.getContentMetadata(audio.key))
            if (total > 0 && cache.isCached(audio.key, 0, total)) {
                Log.i(Tag, "下一首缓存完整 song=$songId bytes=$total")
            } else if (audio.size > length) {
                Log.i(Tag, "容量不足，仅预缓存下一首的部分音频 song=$songId bytes=$length")
            } else {
                throw IOException("下一首音频尚未完整写入缓存")
            }
        } finally {
            priority.remove(C.PRIORITY_DOWNLOAD)
            task.writer = null
        }
    }

    private fun awaitStorage(): SimpleCache? = runBlocking { storage.await() }

    private fun resolve(songId: Long, requested: AudioQuality, owner: Long, cache: SimpleCache?): CachedAudio {
        val requestKey = "$owner:$songId:${requested.wire}"
        synchronized(resolutionLocks.getOrPut(requestKey) { Any() }) {
            val previous = synchronized(indexLock) { index.requests[requestKey] }
                ?.takeIf { AudioQuality.fromWire(it.level) in AudioQuality.supported }
            val storedLength = previous?.let { audio ->
                cache?.let { ContentMetadata.getContentLength(it.getContentMetadata(audio.key)) }
                    ?.takeIf { it > 0 } ?: audio.size
            } ?: 0
            if (previous != null && (
                urls[previous.key]?.valid() == true ||
                    (storedLength > 0 && cache?.isCached(previous.key, 0, storedLength) == true)
                )) return previous
            val audio = NcmBridge.songUrl(songId, requested.wire).getOrThrow()
            val resource = resource(songId, requested.wire, audio)
            rememberUrl(resource, audio)
            synchronized(indexLock) {
                index = index.copy(requests = index.requests + (requestKey to resource))
                writeIndex()
            }
            return resource
        }
    }

    private fun resource(songId: Long, requested: String, audio: SongAudio): CachedAudio {
        // CDN 主机和签名会变化，内容路径才标识文件；实际档位、格式和长度也必须一致。
        val identity = "${audio.level}:${audio.type}:${audio.sizeBytes}:${Uri.parse(audio.url).encodedPath}"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return CachedAudio("$songId:$digest", songId, requested, audio.level, audio.sizeBytes, audio.gainDb, audio.peak)
    }

    private fun rememberUrl(resource: CachedAudio, audio: SongAudio) {
        val lifetime = (audio.expiresInSeconds - 30).coerceIn(0, 86_400) * 1_000
        urls[resource.key] = CachedUrl(audio.url, SystemClock.elapsedRealtime() + lifetime)
    }

    private fun url(resource: CachedAudio, refresh: Boolean = false): String {
        synchronized(resolutionLocks.getOrPut(resource.key) { Any() }) {
            if (!refresh) urls[resource.key]?.takeIf { it.valid() }?.let { return it.url }
            val audio = NcmBridge.songUrl(resource.songId, resource.requestedQuality).getOrThrow()
            val updated = resource(resource.songId, resource.requestedQuality, audio)
            if (updated.key != resource.key) {
                urls.remove(resource.key)
                synchronized(indexLock) {
                    index = index.copy(requests = index.requests.filterValues { it.key != resource.key })
                    writeIndex()
                }
                throw AudioResourceChangedException()
            }
            rememberUrl(resource, audio)
            return audio.url
        }
    }

    private fun upstream(resource: CachedAudio): DataSource.Factory = DataSource.Factory {
        val delegate = DefaultDataSource.Factory(appContext).createDataSource()
        object : DataSource by delegate {
            override fun open(dataSpec: DataSpec): Long {
                try {
                    return delegate.open(dataSpec.withUri(Uri.parse(url(resource))))
                } catch (error: HttpDataSource.InvalidResponseCodeException) {
                    if (error.responseCode != 401 && error.responseCode != 403 && error.responseCode != 410) throw error
                    delegate.close()
                    return delegate.open(dataSpec.withUri(Uri.parse(url(resource, refresh = true))))
                }
            }
        }
    }

    private fun cacheFactory(
        cache: SimpleCache,
        audio: CachedAudio,
        preload: Boolean,
        writable: Boolean = true,
    ): CacheDataSource.Factory {
        // 有界预缓存不会自行记录 EOF；缺少总长度时，离线播放会在缓存尾部继续请求网络。
        if (audio.size > 0 && ContentMetadata.getContentLength(cache.getContentMetadata(audio.key)) == C.LENGTH_UNSET.toLong()) {
            cache.applyContentMetadataMutations(
                audio.key, ContentMetadataMutations.setContentLength(ContentMetadataMutations(), audio.size),
            )
        }
        return CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream(audio))
            .setCacheWriteDataSinkFactory(if (writable) {
                CacheDataSink.Factory().setCache(cache).setFragmentSize(FragmentBytes)
            } else null)
            .setUpstreamPriorityTaskManager(priority)
            .setUpstreamPriority(if (preload) C.PRIORITY_DOWNLOAD else C.PRIORITY_PLAYBACK)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or
                if (preload) CacheDataSource.FLAG_BLOCK_ON_CACHE else 0)
    }

    private inner class AudioDataSource(private val onResolved: (String, CachedAudio) -> Unit) : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null
        private var entryKey: String? = null
        private var selected: CachedAudio? = null
        private var cache: SimpleCache? = null
        private var request: DataSpec? = null
        private var bytesRead = 0L
        private var writable = false

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            delegate?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val id = dataSpec.uri.songIdOrNull() ?: throw IOException("无法识别的歌曲地址")
            val key = dataSpec.key ?: id.toString()
            val cache = awaitStorage()
            this.cache = cache
            if (entryKey != key || selected == null) {
                selected = resolve(id, quality.current, owner(), cache)
                entryKey = key
            }
            val audio = requireNotNull(selected)
            onResolved(key, audio)
            val total = cache?.let { ContentMetadata.getContentLength(it.getContentMetadata(audio.key)) }
                ?.takeIf { it > 0 } ?: audio.size
            if (total > 0 && cache?.isCached(audio.key, 0, total) == true) {
                Log.i(Tag, "本地完整缓存命中 song=$id bytes=$total")
            }
            request = dataSpec.buildUpon().setKey(audio.key)
                .setFlags(dataSpec.flags or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build()
            bytesRead = 0
            return openSource(audio, cache)
        }

        private fun openSource(audio: CachedAudio, cache: SimpleCache?): Long {
            writable = entryKey == playingEntryKey
            // 播放器也会准备下一首；不能让它暂停着的网络请求一直占住预缓存所需的写锁。
            val source = if (cache == null) upstream(audio).createDataSource()
                else cacheFactory(cache, audio, preload = false, writable = writable).createDataSource()
            delegate = source
            listeners.forEach(source::addTransferListener)
            return source.open(requireNotNull(request).subrange(bytesRead))
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val audio = requireNotNull(selected)
            if (writable != (entryKey == playingEntryKey)) {
                closeForReopen()
                openSource(audio, cache)
            }
            val count = try {
                requireNotNull(delegate).read(buffer, offset, length)
            } catch (error: HttpDataSource.HttpDataSourceException) {
                val position = requireNotNull(request).position + bytesRead
                if (cache?.isCached(audio.key, position, 1) != true) throw error
                // 下一首可能已打开了旧网络连接；断网后按相同字节位置接回已完成的磁盘缓存。
                closeForReopen()
                openSource(audio, cache)
                requireNotNull(delegate).read(buffer, offset, length)
            }
            if (count > 0) bytesRead += count
            return count
        }

        private fun closeForReopen() {
            try {
                delegate?.close()
            } catch (error: IOException) {
                Log.d(Tag, "关闭旧连接失败，继续按相同位置读取缓存", error)
            }
        }

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()

        override fun close() {
            try {
                delegate?.close()
            } finally {
                delegate = null
            }
        }
    }

    private fun readIndex(): AudioCacheIndex = try {
        indexFile.openRead().bufferedReader(Charsets.UTF_8).use { json.decodeFromString<AudioCacheIndex>(it.readText()) }
    } catch (_: FileNotFoundException) {
        AudioCacheIndex()
    } catch (error: Exception) {
        Log.w(Tag, "音频缓存索引读取失败，将按音源重新建立", error)
        AudioCacheIndex()
    }

    private fun writeIndex() {
        var output: java.io.FileOutputStream? = null
        try {
            indexFile.baseFile.parentFile?.mkdirs()
            output = indexFile.startWrite()
            output.write(json.encodeToString(index).toByteArray(Charsets.UTF_8))
            indexFile.finishWrite(output)
        } catch (error: Exception) {
            output?.let(indexFile::failWrite)
            Log.w(Tag, "音频缓存索引写入失败", error)
        }
    }

    private data class CachedUrl(val url: String, val expiresAt: Long) {
        fun valid() = SystemClock.elapsedRealtime() < expiresAt
    }

    @Serializable
    private data class AudioCacheIndex(
        val requests: Map<String, CachedAudio> = emptyMap(),
        val uses: Map<Long, CachedSongUse> = emptyMap(),
    )

    companion object {
        const val MaxBytes = 1_024L * 1_024 * 1_024
        private const val FragmentBytes = 2L * 1_024 * 1_024
        private const val Tag = "PlaybackAudioCache"
    }
}

@OptIn(UnstableApi::class)
internal class AudioPreloadTask {
    @Volatile var cancelled = false
        private set
    @Volatile var writer: CacheWriter? = null

    fun cancel() {
        cancelled = true
        writer?.cancel()
    }
}

internal class AudioResourceChangedException : IOException("歌曲音源已变化，需要重新加载")
