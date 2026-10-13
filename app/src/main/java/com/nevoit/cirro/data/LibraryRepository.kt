package com.nevoit.cirro.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.LibraryCollection
import com.nevoit.cirro.data.model.LibraryContent
import com.nevoit.cirro.data.model.LibrarySnapshot
import com.nevoit.cirro.data.model.LibraryTrack
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.cachedSongs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class LibraryRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val account: AccountRepository,
    private val likes: SongLikeRepository,
    private val collections: CollectionRepository,
) {
    private val directory = File(context.filesDir, "library").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    private val ownerFile = AtomicFile(File(directory, "owner"))
    private val _snapshot = MutableStateFlow(runBlocking(Dispatchers.IO) { restoreInitial() })
    val snapshot = _snapshot.asStateFlow()
    private val _content = MutableStateFlow<LibraryContent?>(null)
    val content = _content.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        scope.launch(Dispatchers.Default) {
            val cache = LibraryContentCache()
            snapshot.collect { source ->
                val prepared = cache.prepare(source)
                // 数据变化期间不发布旧快照的结果，下一次收集会准备最新内容。
                if (_snapshot.value === source) _content.value = prepared
            }
        }
        scope.launch {
            var owner: Long? = null
            account.session.collect { session ->
                if (session == Session.Unknown || session == Session.Offline) return@collect
                val uid = (session as? Session.LoggedIn)?.profile?.userId?.takeIf { it > 0 }
                if (uid == null) {
                    mutex.withLock {
                        _snapshot.value = null
                        _error.value = null
                        withContext(Dispatchers.IO) { ownerFile.delete() }
                    }
                    owner = null
                    return@collect
                }
                if (_snapshot.value?.userId != uid) {
                    mutex.withLock {
                        _snapshot.value = null
                        _error.value = null
                        _snapshot.value = withContext(Dispatchers.IO) { restore(uid) } ?: LibrarySnapshot(uid)
                        persist()
                    }
                }
                if (owner != uid) {
                    owner = uid
                    scope.launch { refresh() }
                }
            }
        }
        scope.launch {
            likes.snapshot.collect { liked ->
                val uid = liked.userId
                val ids = liked.likedIds
                if (uid != null && ids != null) syncLikes(uid, ids)
            }
        }
        scope.launch {
            collections.changes.collect { change ->
                mutex.withLock {
                    val uid = userId() ?: return@withLock
                    if (change.userId != uid) return@withLock
                    val before = _snapshot.value?.takeIf { it.userId == uid } ?: return@withLock
                    fun updated(items: List<LibraryCollection>): List<LibraryCollection> =
                        items.filterNot { it.info.id == change.info.id }.let { remaining ->
                            if (change.subscribed) listOf(LibraryCollection(
                                change.info.copy(subscribed = true), System.currentTimeMillis(),
                            )) + remaining else remaining
                        }
                    _snapshot.value = when (change.info.kind) {
                        CollectionKind.ALBUM -> before.copy(
                            albums = updated(before.albums),
                            albumSongs = if (change.subscribed) before.albumSongs else before.albumSongs - change.info.id,
                        )
                        CollectionKind.ARTIST -> before.copy(artists = updated(before.artists))
                        CollectionKind.PLAYLIST -> before.copy(playlists = updated(before.playlists))
                        else -> before
                    }
                    persist()
                }
            }
        }
    }

    suspend fun refresh() {
        if (!refreshMutex.tryLock()) return
        try {
            refreshLocked()
        } finally {
            refreshMutex.unlock()
        }
    }

    private suspend fun refreshLocked() = mutex.withLock {
        val uid = userId() ?: return@withLock
        if (_snapshot.value?.userId != uid) return@withLock
        _loading.value = true
        val failures = mutableListOf<String>()
        try {
            for (kind in listOf(CollectionKind.ALBUM, CollectionKind.PLAYLIST, CollectionKind.ARTIST)) {
                val fetched = withContext(Dispatchers.IO) { NcmBridge.libraryCollections(kind, uid) }
                if (userId() != uid) return@withLock
                fetched.onSuccess { items ->
                    if (kind != CollectionKind.PLAYLIST) {
                        collections.updateLibrarySubscriptions(kind, items.map { it.info.id }.toSet())
                    }
                    _snapshot.update { current ->
                        current?.let {
                            val old = when (kind) {
                                CollectionKind.ALBUM -> it.albums
                                CollectionKind.PLAYLIST -> it.playlists
                                else -> it.artists
                            }.associateBy { item -> item.info.id }
                            val firstSync = kind == CollectionKind.ALBUM && !it.albumsLoaded
                            val timed = items.mapIndexed { index, item ->
                                item.copy(addedAtMs = item.addedAtMs.takeIf { time -> time > 0 }
                                    ?: old[item.info.id]?.addedAtMs
                                    ?: if (firstSync) -index.toLong() else System.currentTimeMillis())
                            }
                            val ids = timed.map { item -> item.info.id }.toSet()
                            when (kind) {
                                CollectionKind.ALBUM -> it.copy(
                                    albums = timed, albumsLoaded = true,
                                    albumSongs = it.albumSongs.filterKeys { id -> id in ids },
                                )
                                CollectionKind.PLAYLIST -> it.copy(playlists = timed)
                                else -> it.copy(artists = timed)
                            }
                        }
                    }
                }.onFailure { failures += it.message.orEmpty() }
            }
            loadLikes(uid).onFailure { failures += it.message.orEmpty() }
            _error.value = if (failures.isEmpty()) null else "部分内容未能更新，点按重试"
            persist()
        } finally {
            _loading.value = false
        }
    }

    private suspend fun loadLikes(uid: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val before = _snapshot.value?.takeIf { it.userId == uid } ?: return@runCatching
            val pid = NcmBridge.likedPlaylistId(uid).getOrThrow()
            val tracks = NcmBridge.likedTracks(pid).getOrThrow()
            // 红心集合是当前状态的真源；播放列表详情可能仍包含刚取消喜爱的歌曲。
            val known = likes.refreshNow(uid).getOrThrow()
            val old = before.likedTracks.associateBy { it.id }
            val trackIds = tracks.map { it.id }.toSet()
            val ordered = tracks.filter { it.id in known } + known.filterNot { it in trackIds }
                .map { LibraryTrack(it) }
            val timed = ordered.distinctBy { it.id }.mapIndexed { index, track ->
                track.copy(addedAtMs = track.addedAtMs.takeIf { it > 0 }
                    ?: old[track.id]?.addedAtMs
                    ?: if (!before.likesLoaded) -index.toLong() else System.currentTimeMillis())
            }
            val cached = before.likedSongs.associateBy { it.id }.toMutableMap()
            timed.map { it.id }.filterNot { it in cached }.chunked(200).forEach { ids ->
                NcmBridge.songDetail(ids).getOrThrow().forEach { cached[it.id] = it }
            }
            if (userId() != uid) return@runCatching
            _snapshot.update { current -> current?.copy(
                likedPlaylistId = pid, likedTracks = timed,
                likedSongs = timed.mapNotNull { cached[it.id] }, likesLoaded = true,
            ) }
        }
    }

    private suspend fun syncLikes(uid: Long, ids: Set<Long>) = mutex.withLock {
        if (userId() != uid) return@withLock
        if (likes.snapshot.value.userId != uid || likes.snapshot.value.likedIds != ids) return@withLock
        val before = _snapshot.value?.takeIf { it.userId == uid } ?: return@withLock
        if (!before.likesLoaded) return@withLock
        val oldIds = before.likedTracks.map { it.id }.toSet()
        if (oldIds == ids) return@withLock
        val additions = ids - oldIds
        val now = System.currentTimeMillis()
        val fetched = withContext(Dispatchers.IO) {
            runCatching { additions.toList().chunked(200).flatMap { NcmBridge.songDetail(it).getOrThrow() } }
        }
        if (userId() != uid) return@withLock
        _snapshot.value = before.copy(
            likedTracks = additions.map { LibraryTrack(it, now) } + before.likedTracks.filter { it.id in ids },
            likedSongs = fetched.getOrDefault(emptyList()) + before.likedSongs.filter { it.id in ids },
        )
        if (fetched.isFailure) _error.value = "部分喜爱歌曲未能更新，点按重试"
        persist()
    }

    suspend fun songs(): Result<List<Song>> = mutex.withLock {
        val before = _snapshot.value?.takeIf { it.userId == userId() }
            ?: return@withLock Result.failure(IllegalStateException("资料库尚未加载"))
        if (before.albums.all { it.info.id in before.albumSongs }) {
            return@withLock Result.success(withContext(Dispatchers.Default) { before.cachedSongs() })
        }
        withContext(Dispatchers.IO) {
            var changed = false
            try {
                runCatching {
                    for (album in before.albums) {
                        if (album.info.id !in before.albumSongs) {
                            val tracks = collections.tracks(CollectionKind.ALBUM, album.info.id).getOrThrow()
                            check(userId() == before.userId) { "账号已切换" }
                            _snapshot.update { current -> current?.copy(
                                albumSongs = current.albumSongs + (album.info.id to tracks),
                            ) }
                            changed = true
                        }
                    }
                    check(userId() == before.userId) { "账号已切换" }
                    _snapshot.value?.cachedSongs().orEmpty()
                }.onFailure { if (it is CancellationException) throw it }
            } finally {
                // 页面退出会取消补载，已完成的专辑仍需保存，避免下次从头请求。
                if (changed) withContext(NonCancellable) { persist() }
            }
        }
    }

    private fun userId(): Long? = (account.session.value as? Session.LoggedIn)?.profile?.userId?.takeIf { it > 0 }

    private fun restoreInitial(): LibrarySnapshot? {
        val session = account.session.value
        if (session == Session.LoggedOut || (session is Session.LoggedIn && session.profile.userId <= 0)) return null
        val uid = userId() ?: runCatching {
            ownerFile.openRead().use { it.readBytes().toString(Charsets.UTF_8).toLongOrNull() }
        }.getOrNull() ?: return null
        return restore(uid)
    }

    private fun restore(uid: Long): LibrarySnapshot? = runCatching {
        val file = AtomicFile(File(directory, "$uid.json"))
        json.decodeFromString<LibrarySnapshot>(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            .takeIf { it.userId == uid }
    }.getOrNull()

    private suspend fun persist() = withContext(Dispatchers.IO) {
        val snapshot = _snapshot.value ?: return@withContext
        if (userId() != snapshot.userId) return@withContext
        val file = AtomicFile(File(directory, "${snapshot.userId}.json"))
        runCatching {
            val stream = file.startWrite()
            try {
                stream.write(json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
            val ownerStream = ownerFile.startWrite()
            try {
                ownerStream.write(snapshot.userId.toString().toByteArray(Charsets.UTF_8))
                ownerFile.finishWrite(ownerStream)
            } catch (error: Exception) {
                ownerFile.failWrite(ownerStream)
                throw error
            }
        }.onFailure { Log.w("Library", "资料库缓存未能保存", it) }
    }
}
