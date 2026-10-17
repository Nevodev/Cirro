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
import com.nevoit.cirro.data.model.artistsMissingCover
import com.nevoit.cirro.data.model.cachedSongs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.util.concurrent.ConcurrentHashMap

/**
 * 一轮补多少个艺人头像。
 *
 * 没有批量接口（见 [fillArtistCovers]），一条约 250 ms / 3 KB，6 路并发下一批不到 300 ms；
 * 再往上加只是同时压 3 KB 的请求数，不值。
 */
private const val ArtistCoverBatch = 6

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

    /** 正在跑的补图任务（同时只允许一趟）。 */
    private var coverFill: Job? = null

    /** 这一趟进程里查失败过的艺人 id：失败不落盘，下次启动还会再试。 */
    private val coverFailures: MutableSet<Long> = ConcurrentHashMap.newKeySet()

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
                // 扫码完成到用户资料返回之间的占位账号不是退出登录。
                if (session is Session.LoggedIn && session.profile.userId <= 0) return@collect
                val uid = (session as? Session.LoggedIn)?.profile?.userId?.takeIf { it > 0 }
                if (uid == null) {
                    mutex.withLock {
                        val previousUserId = _snapshot.value?.userId
                        _snapshot.value = null
                        _content.value = null
                        _error.value = null
                        withContext(Dispatchers.IO) {
                            previousUserId?.let { AtomicFile(File(directory, "$it.json")).delete() }
                            ownerFile.delete()
                        }
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
        // 头像补图跟着刷新走：资料库任何一页刷新都会顺手补齐（已查过的一条请求都没有）
        scheduleArtistCovers()
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

    /**
     * 排一趟「全部艺人」头像补图。已经有一趟在跑就直接返回。
     *
     * 跑在 [scope]（Application 级）而不是调用方的 scope 上：页面退出不该把补图掐断，
     * 否则每次进来都从头开始（已补到的一批有落盘，但这一趟的进度会白丢）。
     */
    private fun scheduleArtistCovers() {
        if (coverFill?.isActive == true) return
        val uid = userId() ?: return
        coverFill = scope.launch(Dispatchers.IO) { fillArtistCovers(uid) }
    }

    /**
     * 把「全部艺人」里**还没查过头像**的艺人一条条问回来。
     *
     * 为什么不是一个批量接口：`/api/v1/artist` 的批量 `ids` 参数实测直接返 `code=404`
     * （探针 `API/ncm-api-rs/examples/artist_pic_probe.rs`），只有单条的
     * `/api/artist/head/info/get` 可用（约 3 KB / 250 ms，比 `/api/v1/artist/{id}`
     * 那份带 50 首热门歌的 95 KB 轻三十倍）。所以按 [ArtistCoverBatch] 分批，
     * 一批落地一次，界面就一批批长出头像。
     *
     * 结果进 [LibrarySnapshot.artistCovers] 跟快照一起落盘，**一个艺人一辈子只查一次**；
     * 查失败（`ok:false`）只进 [coverFailures]（本进程），下次启动还会重试。
     */
    private suspend fun fillArtistCovers(uid: Long) {
        var changed = false
        try {
            while (true) {
                val snapshot = mutex.withLock { _snapshot.value?.takeIf { it.userId == uid } } ?: return
                val pending = snapshot.artistsMissingCover()
                    .filterNot { it in coverFailures }
                    .take(ArtistCoverBatch)
                if (pending.isEmpty()) return

                val results = coroutineScope {
                    pending.map { id ->
                        async(Dispatchers.IO) { id to NcmBridge.artistCover(id) }
                    }.awaitAll()
                }
                results.filter { it.second.isFailure }.forEach { coverFailures += it.first }
                // 空串也是结果（上游没这个艺人的图），照样记下来，免得反复重查
                val covers = results.mapNotNull { (id, result) ->
                    result.getOrNull()?.let { id to it }
                }
                if (covers.isEmpty()) continue

                mutex.withLock {
                    val current = _snapshot.value?.takeIf { it.userId == uid } ?: return@withLock
                    _snapshot.value = current.copy(artistCovers = current.artistCovers + covers)
                }
                changed = true
            }
        } finally {
            // 整趟结束（或被换号打断）才写一次盘：快照 JSON 是 MB 级的，一批一写不值。
            // 写盘必须拿锁——`AtomicFile` 不是并发安全的，别跟 refresh 那趟撞上
            if (changed) withContext(NonCancellable) { mutex.withLock { persist() } }
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
