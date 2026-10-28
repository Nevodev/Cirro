package com.nevoit.cirro.data

import android.content.Context
import com.nevoit.cirro.data.model.HomeBlock
import com.nevoit.cirro.data.model.MemoryPlaylists
import com.nevoit.cirro.data.model.Playlist
import com.nevoit.cirro.data.model.Ranking
import com.nevoit.cirro.data.model.RecentAlbum
import com.nevoit.cirro.data.model.RecentSong
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

/** 「最近播放」展示的专辑张数，跟旧专辑维度端点的条数（服务端默认 10）对齐。 */
private const val RecentAlbumCount = 10

private val HomeJson = Json { ignoreUnknownKeys = true }

/** 网易云的内容日切时刻：每日推荐、雷达过了这个点才换成今天的内容（多源报 6 点，取 7 点留足余量）。 */
private const val ContentRolloverHour = 7

/**
 * 服务器的内容日，格式 `c-2026-10-05`。minSdk 30，java.time 直接用。
 *
 * **不是日历日**：日切（[ContentRolloverHour] 点）之前仍算**前一天**——凌晨拉的推荐
 * 还是昨天的内容。现在它只服务一件事：ViewModel 靠它判断「进程活着熬过了日切」，
 * 该再刷一遍了（缓存已不按天失效，见类文档）。
 */
internal fun contentDay(): String {
    val now = LocalDate.now()
    val day = if (LocalTime.now().hour < ContentRolloverHour) now.minusDays(1) else now
    return "c-$day"
}

/** 进程内快照槽：存最近一次真拉到的数据，供界面同步读垫底。不做任何有效期判断。 */
private class Slot<T> {
    @Volatile var value: T? = null
}

// ---- 磁盘持久化的信封（纯快照，不带天戳——失效只看「冷启动后必刷新」这条纪律） ----

@Serializable
private data class SongPack(val songs: List<Song>)

@Serializable
private data class PlaylistPack(val playlists: List<Playlist>)

@Serializable
private data class BlockPack(val blocks: List<HomeBlock>)

@Serializable
private data class TrackPack(val tracks: Map<String, List<Song>>)

@Serializable
private data class RecentPack(val albums: List<RecentAlbum>)

@Serializable
private data class AlbumTypePack(val types: Map<Long, String>)

/**
 * 首页内容。
 *
 * 桥接层的调用是**同步阻塞**的（Rust 侧 `block_on` 把异步拉平），所以每个方法都切到 IO 上；
 * 直接留在 ViewModel 的默认调度器（Main）上会卡主线程。
 *
 * **缓存只当快照，不当真**（进程内 + 磁盘，磁盘在 `cacheDir/home/`）：
 * - 磁盘快照只为**冷启动第一屏**瞬显（见 [dailySongsSnapshot] 一族），没有有效期——
 *   🔴 每次**冷启动后必真拉一遍**，拉回来的内容跟快照一样时界面自然不动
 *   （StateFlow 对相等数据自动去重），变了就顶掉。曾经按天盖戳"当天内不发请求"，
 *   结果个性化内容变了（用户红心/讨厌、口味更新）也看不见，还出过"昨天的内容
 *   盖上今天的戳钉死一整天"的事故——**缓存不做有效期判断，失效靠每次启动重拉**。
 * - **最近播放**：连快照读都只有冷启动那一次，之后每次都真拉（一分钟轮询）。
 *
 * 缓存跟登录身份走：磁盘文件带 owner，确定换号了就整体作废——上一账号的数据
 * 绝不能漏给下一账号。冷启动时 session 多半还没验完（[Session.Offline]），
 * 这种情况照常恢复磁盘（同一台机同一个 cookie，不是换号）。
 */
class HomeRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val account: AccountRepository,
) {

    private val dir = File(context.cacheDir, "home").apply { mkdirs() }

    private val dailySongsSlot = Slot<List<Song>>()
    private val dailyPlaylistsSlot = Slot<List<Playlist>>()
    private val blocksSlot = Slot<List<HomeBlock>>()
    private val memoriesSlot = Slot<MemoryPlaylists>()

    private val recentSlot = Slot<List<RecentAlbum>>()

    /**
     * 磁盘上那份缓存属于谁（`owner` 文件里的 userId）。
     *
     * 冷启动时 session 多半还是 `Unknown`，[restoreDisk] 那趟没法跟当前身份比对，只能先记下来，
     * 交给 [watchSession] 当"已知归属"的起点。
     */
    private var diskOwnerId: Long? = null

    /** 歌单曲目按歌单 id 分槽，存最近一次真拉到的结果（快照 + 最新值，见 [playlistTracks]）。 */
    private val tracksCache = ConcurrentHashMap<Long, Slot<List<Song>>>()

    /**
     * 专辑类型（Single / EP / 专辑…）。
     *
     * 🔴 **静态元数据**——一个专辑的类型一辈子不变，查一次终身有效（跟响度 gain 一个性质），
     * 所以不做天戳、不做失效，只有换号清缓存时跟着走。「最近播放」给单曲专辑的标题
     * 补 " - Single" 用（最近播放单曲里的 `al` 对象不带 type，只能逐张查专辑详情）。
     */
    private val albumTypeCache = ConcurrentHashMap<Long, String>()

    init {
        // 冷启动第一屏全靠磁盘快照，同步读一把（几个小 JSON，几十毫秒一次性的事）
        runBlocking(Dispatchers.IO) { restoreDisk() }
        watchSession()
    }

    // ---- 快照（同步读，可能跨天陈旧，给界面瞬显垫底用） ----

    fun dailySongsSnapshot(): List<Song>? = dailySongsSlot.value

    fun dailyPlaylistsSnapshot(): List<Playlist>? = dailyPlaylistsSlot.value

    fun homeBlocksSnapshot(): List<HomeBlock>? = blocksSlot.value

    fun memoryPlaylistsSnapshot(): MemoryPlaylists? = memoriesSlot.value

    suspend fun memoryPlaylists(): Result<MemoryPlaylists> = withContext(Dispatchers.IO) {
        runCatching {
            val owner = currentOwnerId()
            val metrics = context.resources.displayMetrics
            val first = homeFeedRequest(metrics.widthPixels / metrics.density, metrics.heightPixels / metrics.density)
            var request = first
            val loaded = linkedSetOf<String>()
            val cursors = mutableSetOf<Int>()
            var result: MemoryPlaylists? = null
            // 首页是有限的模块列表；游标不前进立即退出，避免异常响应无限重复取页。
            for (pageIndex in 0 until 8) {
                check(currentOwnerId() == owner) { "账号已变化" }
                val page = HomeFeedPage(NcmBridge.homeFeed(request.toString()).getOrThrow())
                result = page.memories()
                if (result != null || !page.hasMore) break
                check(cursors.add(page.cursor)) { "首页游标未前进" }
                loaded += page.blocks.mapNotNull { it["positionCode"]?.let { value ->
                    (value as? kotlinx.serialization.json.JsonPrimitive)?.content
                } }
                request = page.nextRequest(first, loaded.toList())
                check(pageIndex < 7) { "首页分页尚未完成" }
            }
            check(currentOwnerId() == owner) { "账号已变化" }
            val memories = result ?: MemoryPlaylists()
            val known = memoriesSlot.value?.playlists.orEmpty().associateBy { it.id }
            coroutineScope {
                memories.copy(playlists = memories.playlists.map { playlist ->
                    async {
                        val cached = known[playlist.id]
                        if (cached?.period != null) {
                            playlist.copy(title = cached.title, period = cached.period)
                        } else {
                            val info = NcmBridge.playlistDetail(playlist.id).getOrNull()
                            playlist.copy(
                                title = info?.title ?: playlist.title,
                                period = info?.let { memoryPeriod(it.title, it.description) },
                            )
                        }
                    }
                }.awaitAll())
            }.also { check(currentOwnerId() == owner) { "账号已变化" } }
        }.onSuccess {
            memoriesSlot.value = it
            persist("memories.json") { HomeJson.encodeToString(it) }
        }
    }

    fun recentAlbumsSnapshot(): List<RecentAlbum>? = recentSlot.value

    /** 同步读已缓存的专辑类型，没查过返回 null（异步补查是 [albumType] 的事）。 */
    fun albumTypeSnapshot(albumId: Long): String? = albumTypeCache[albumId]

    /**
     * 专辑类型，没有就查（见 [albumTypeCache] 的缓存语义）。
     *
     * 请求**失败不进缓存**——下个轮询周期自动补查；「查到了但上游没给 type」
     * 缓存空串，免得每次刷新都白查一遍。
     */
    suspend fun albumType(albumId: Long): String = withContext(Dispatchers.IO) {
        albumTypeCache[albumId] ?: run {
            val fetched = NcmBridge.albumType(albumId).getOrNull()
            if (fetched == null) {
                ""
            } else {
                albumTypeCache[albumId] = fetched
                persistAlbumTypes()
                fetched
            }
        }
    }

    fun tracksSnapshot(playlistId: Long, limit: Int): List<Song>? =
        tracksSlot(playlistId).value?.take(limit)

    // ---- 取数 ----

    /** 每日推荐歌曲（需登录）。**每次调用都真拉**——内容没变时界面靠 StateFlow 去重自然不动。 */
    suspend fun dailySongs(): Result<List<Song>> = withContext(Dispatchers.IO) {
        NcmBridge.recommendSongs().onSuccess {
            dailySongsSlot.value = it
            persist("daily_songs.json") { HomeJson.encodeToString(SongPack(it)) }
        }
    }

    /**
     * 每日推荐歌单（需登录），约 26 条。**每次调用都真拉**。
     *
     * 首页的雷达入口卡已改走 [homeBlocks]（那边给的是客户端真正在用的那组雷达），这里先留着。
     */
    suspend fun dailyPlaylists(): Result<List<Playlist>> = withContext(Dispatchers.IO) {
        NcmBridge.recommendResource().onSuccess {
            dailyPlaylistsSlot.value = it
            persist("daily_playlists.json") {
                HomeJson.encodeToString(PlaylistPack(it))
            }
        }
    }

    /**
     * 移动端首页区块（需登录）。**每次调用都真拉**，没有"进程内只拉一次"一说——
     * 曲风推荐区（`HOMEPAGE_BLOCK_STYLE_RCMD`）服务端每次给的都不一样，雷达歌单区的
     * 曲目也随日切更新；调用方（ViewModel）自己控制节奏（冷启动 + 内容日切换）。
     * 雷达歌单区（`HOMEPAGE_BLOCK_MGC_PLAYLIST`）同乘这一次请求。
     * 拉完写缓存供冷启动瞬显。
     */
    suspend fun homeBlocks(): Result<List<HomeBlock>> = withContext(Dispatchers.IO) {
        NcmBridge.homeBlocks().onSuccess {
            blocksSlot.value = it
            persist("blocks.json") { HomeJson.encodeToString(BlockPack(it)) }
        }
    }

    /** 排行榜列表（含官方榜与地区/曲风榜）。首页已撤下，留给后续榜单页。 */
    suspend fun toplists(): Result<List<Ranking>> =
        withContext(Dispatchers.IO) { NcmBridge.toplists() }

    /**
     * 最近播放的专辑（需登录，最多 [RecentAlbumCount] 张）。**每次都真拉**——
     * 界面一分钟轮询一次、靠数据相等性判断「真变了」才刷新，这层缓存反而碍事。
     *
     * 🔴 数据源是**单曲**最近播放（见 [NcmBridge.recentSongs]），不是专辑维度——
     * 那个只在官方客户端的专辑页点播放时才记账，Cirro 的播歌永远进不去。
     * 单曲维度跟听歌排行同源，播完就有（服务端有传播延迟）。
     *
     * 归并规则：服务端按最后播放**倒序**，所以**每个专辑保留第一次出现的那条**
     * （即最近一次），顺序天然就是倒序，不用再排；[RecentAlbumCount] 张截断。
     * `albumId` 为 0 的条目（上游字段异常）直接丢——没有 id 的专辑没法去重。
     *
     * 它跟首页区块没关系（没有块码），得单独走一个端点。
     * 空列表是正常空态（"这段时间没听"），不是失败，界面按"这块不显示"处理。
     */
    suspend fun recentAlbums(): Result<List<RecentAlbum>> = withContext(Dispatchers.IO) {
        NcmBridge.recentSongs().map { songs ->
            val albums = mergeRecent(songs)
            recentSlot.value = albums
            persist("recent.json") { HomeJson.encodeToString(RecentPack(albums)) }
            albums
        }
    }

    /** 单曲记录按专辑归并（见 [recentAlbums] 的规则）。 */
    private fun mergeRecent(songs: List<RecentSong>): List<RecentAlbum> = buildList {
        val seen = mutableSetOf<Long>()
        for (song in songs) {
            if (song.albumId == 0L || !seen.add(song.albumId)) continue
            add(
                RecentAlbum(
                    id = song.albumId,
                    title = song.album,
                    cover = song.cover,
                    artists = song.artists,
                )
            )
            if (size >= RecentAlbumCount) break
        }
    }

    /** 私人 FM（需登录）。一次 3 首。首页已撤下，留给后续 FM 入口。**不缓存**——本来就是流式的。 */
    suspend fun personalFm(): Result<List<Song>> =
        withContext(Dispatchers.IO) { NcmBridge.personalFm() }

    /**
     * 歌单曲目。首页取前若干首——首曲的专辑图当封面、这批歌手当署名；
     * 歌单页将来是整页取，默认值就够。
     *
     * **每次调用都真拉**，结果记进 [tracksCache] 只为下次快照读（[tracksSnapshot]）和
     * 写盘。曾经按歌单 id 分槽做"当天内有效"的缓存，但雷达歌单的曲目随日切变、
     * 用户还会红心/讨厌单曲改变歌单内容——缓存答案只会慢半拍。
     */
    suspend fun playlistTracks(playlistId: Long, limit: Int = 1): Result<List<Song>> =
        withContext(Dispatchers.IO) {
            NcmBridge.playlistTracks(playlistId, limit).onSuccess {
                tracksSlot(playlistId).value = it
                persistTracks()
            }
        }

    // ---- 生命周期 ----

    /**
     * 盯着登录态：确定是**另一个登录用户**（换号）就整体作废，磁盘一起清——
     * 上一账号的「最近播放」绝不能漏给下一账号。
     *
     * 🔴 **身份不明（Unknown / 登出，userId 取不到）时一个字都不许动**。首页的 VM 现在
     * 在登录态查明前就创建了（冷启动要画磁盘快照），这个仓库也就跟着在 Unknown 时出生——
     * 把"取不到 id"当成 id=0 认下来，随后 LoggedIn(uid) 就会被判成换号，`wipe()` 把
     * 刚恢复的磁盘缓存整个删掉，缓存等于没有。所以只在**拿到真 id** 时才认身份 / 判换号。
     */
    private fun watchSession() {
        scope.launch {
            // 起点是磁盘上那份缓存的归属（可能为 null —— 首次运行），不是"第一个看到的身份"
            var owner: Long? = diskOwnerId
            account.session.collect { session ->
                val id = (session as? Session.LoggedIn)?.profile?.userId?.takeIf { it != 0L }
                    ?: return@collect
                val known = owner
                when {
                    known == null -> owner = id
                    known != id -> {
                        owner = id
                        wipe()
                    }
                }
            }
        }
    }

    private fun wipe() {
        dailySongsSlot.value = null
        dailyPlaylistsSlot.value = null
        blocksSlot.value = null
        memoriesSlot.value = null
        recentSlot.value = null
        albumTypeCache.clear()
        tracksCache.clear()
        scope.launch(Dispatchers.IO) {
            runCatching { dir.listFiles()?.forEach { it.delete() } }
        }
    }

    // ---- 磁盘 ----

    /** 归属判断只对「确定登录了谁」生效；Unknown/Offline 不表态（同一 cookie，不是换号）。 */
    private fun currentOwnerId(): Long? =
        (account.session.value as? Session.LoggedIn)?.profile?.userId?.takeIf { it != 0L }

    /** 后台写盘。必须确定当前登录者才写——归属不明的缓存没有留着的价值。 */
    private fun persist(name: String, content: () -> String) {
        val ownerId = currentOwnerId() ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                File(dir, "owner").writeText(ownerId.toString())
                File(dir, name).writeText(content())
            }
        }
    }

    private fun persistTracks() {
        val snapshot = tracksCache.entries
            .mapNotNull { (id, slot) -> slot.value?.let { id.toString() to it } }
            .toMap()
        persist("tracks.json") { HomeJson.encodeToString(TrackPack(snapshot)) }
    }

    private fun persistAlbumTypes() {
        persist("album_types.json") { HomeJson.encodeToString(AlbumTypePack(albumTypeCache.toMap())) }
    }

    private fun restoreDisk() {
        val saved = runCatching { File(dir, "owner").readText().toLongOrNull() }.getOrNull()
        val current = currentOwnerId()
        if (saved != null && current != null && saved != current) {
            // 磁盘上是别人的，全部作废
            runCatching { dir.listFiles()?.forEach { it.delete() } }
            return
        }
        // 冷启动时 session 多半还是 Unknown（current 为 null），这趟比不了；把磁盘上的归属
        // 记下来交给 watchSession 当起点，等真身份到了再判换号——否则 Unknown 出生这一步
        // 会让"磁盘属于 B、cookie 已是 A"这种情形漏过去。
        diskOwnerId = saved

        runCatching {
            val pack = HomeJson.decodeFromString<SongPack>(File(dir, "daily_songs.json").readText())
            dailySongsSlot.value = pack.songs
        }
        runCatching {
            val pack = HomeJson.decodeFromString<PlaylistPack>(File(dir, "daily_playlists.json").readText())
            dailyPlaylistsSlot.value = pack.playlists
        }
        runCatching {
            val pack = HomeJson.decodeFromString<BlockPack>(File(dir, "blocks.json").readText())
            blocksSlot.value = pack.blocks
        }
        runCatching {
            memoriesSlot.value = HomeJson.decodeFromString<MemoryPlaylists>(File(dir, "memories.json").readText())
        }
        runCatching {
            val pack = HomeJson.decodeFromString<TrackPack>(File(dir, "tracks.json").readText())
            pack.tracks.forEach { (id, songs) ->
                tracksSlot(id.toLong()).value = songs
            }
        }
        runCatching {
            val pack = HomeJson.decodeFromString<RecentPack>(File(dir, "recent.json").readText())
            recentSlot.value = pack.albums
        }
        runCatching {
            val pack = HomeJson.decodeFromString<AlbumTypePack>(File(dir, "album_types.json").readText())
            pack.types.forEach { (id, type) -> albumTypeCache[id] = type }
        }
    }

    private fun tracksSlot(playlistId: Long): Slot<List<Song>> =
        tracksCache.getOrPut(playlistId) { Slot() }
}
