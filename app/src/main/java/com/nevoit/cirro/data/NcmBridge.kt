package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.AudioQuality
import com.nevoit.cirro.data.model.ArtistVideo
import com.nevoit.cirro.data.model.CollectionDetail
import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.HomeBlock
import com.nevoit.cirro.data.model.LibraryCollection
import com.nevoit.cirro.data.model.LibraryTrack
import com.nevoit.cirro.data.model.Playlist
import com.nevoit.cirro.data.model.Ranking
import com.nevoit.cirro.data.model.RecentSong
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.SongAudio
import com.nevoit.cirro.data.model.SongQualityInfo
import com.nevoit.cirro.data.model.UserProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 二维码接口的扫码状态，取值与网易云一致。 */
data class QrCheckStatus(
    val code: Int,
    val message: String,
    val cookie: String?,
) {
    companion object {
        const val EXPIRED = 800
        const val WAITING = 801
        const val SCANNED = 802
        const val CONFIRMED = 803
    }
}

/**
 * Rust 桥接层的门面。
 *
 * 原生层一律返回 JSON，成败都走正常返回（见 `API/ncm-android/src/lib.rs`），
 * 这里翻译成 [Result]，让调用方不必碰 JSON。
 *
 * 登录后的 cookie 由原生层自己持有，进程内后续请求自动带上；跨进程持久化还没做。
 */
object NcmBridge {

    private val json = Json { ignoreUnknownKeys = true }

    init {
        System.loadLibrary("ncm_android")
    }

    /** 取二维码 unikey，有效期约 3 分钟。 */
    fun qrKey(): Result<String> = decode(nativeQrKey()).map { body ->
        body.unikey.ifEmpty { throw IllegalStateException("unikey 为空") }
    }

    /** 由 unikey 生成二维码内容。原生层本地拼接，不联网。 */
    fun qrCreate(key: String): Result<String> = decode(nativeQrCreate(key)).map { body ->
        body.qrurl.ifEmpty { throw IllegalStateException("二维码 URL 为空") }
    }

    /** 轮询扫码状态。803 时原生层已写入 cookie，同时随结果返回一份。 */
    fun qrCheck(key: String): Result<QrCheckStatus> = decode(nativeQrCheck(key)).map { body ->
        QrCheckStatus(
            code = body.code,
            message = body.message,
            cookie = body.cookie.ifEmpty { null },
        )
    }

    /** 直接写入登录 cookie，用于恢复已有登录态。 */
    fun setCookie(cookie: String): Boolean = nativeSetCookie(cookie) == 0

    /** 清空原生层的登录态。 */
    fun clearCookie() {
        nativeClearCookie()
    }

    /**
     * 查登录态并取资料。
     *
     * 返回成功但值为 null 表示**确定没登录**（不是错误）；返回失败才是请求本身出了问题。
     * 两者必须分开，否则网络抖动会把一份好 cookie 误判成失效。
     */
    fun loginStatus(): Result<UserProfile?> = decode(nativeLoginStatus()).map { body ->
        body.profile.takeIf { body.loggedIn }
    }

    /**
     * 换播放直链。
     *
     * 失败分两类，调用方**必须**分开处理：
     * - [SongUnavailableException]：无版权 / 下架 / VIP 未开通，重试没有意义
     * - 其它失败：请求本身没成功（网络、超时），值得重试
     *
     * 只接受 App 支持的实际档位。杜比缺失或被服务端替换成其它特殊档位时，按 App 的普通
     * 音质顺序重新请求；网络失败直接交给播放重试，不能当成歌曲不支持杜比。
     *
     * 返回的直链有效期很短（[SongAudio.expiresInSeconds]，实测 1200 秒），别囤。
     */
    fun songUrl(id: Long, level: String = "standard"): Result<SongAudio> = runCatching {
        val requested = AudioQuality.fromWire(level)
        require(requested in AudioQuality.supported) { "Unsupported audio quality: $level" }
        val candidates = if (requested == AudioQuality.DOLBY) {
            listOf(requested) + AudioQuality.supported.asReversed().filterNot { it == AudioQuality.DOLBY }
        } else listOf(requireNotNull(requested))
        var unavailable = "这首歌暂无 App 支持的可播放音质"
        for (candidate in candidates) {
            val body = decode(nativeSongUrl(id.toString(), candidate.wire)).getOrThrow()
            if (body.url.isEmpty()) {
                unavailable = unavailableMessage(body.code, candidate.wire, body.message)
                continue
            }
            if (AudioQuality.fromWire(body.level) !in AudioQuality.supported) {
                unavailable = "这首歌暂无 App 支持的可播放音质"
                continue
            }
            return@runCatching SongAudio(
                url = body.url,
                level = body.level,
                type = body.type,
                sizeBytes = body.size,
                expiresInSeconds = body.expi,
                gainDb = body.gain,
                peak = body.peak,
            )
        }
        throw SongUnavailableException(unavailable)
    }

    /**
     * 这首歌**有哪几档音质**（需登录）。
     *
     * 原生层的响应是一组固定键、`null` 表示没这一档，这里已经拍平成一档一条。
     *
     * ⚠️ **有这一档 ≠ 能播这一档**：实测 `vivid` 恒有字段，但请求直链仍回 `code=-110`
     * （没权益）。所以这份数据只用来**灰化界面 / 显示体积**，最终能不能播看 [songUrl] 的响应。
     *
     * 只在打开音质面板时按需查，**不在播放路径上**调用 —— 播放时少一次请求。
     */
    fun songQuality(id: Long): Result<SongQualityInfo> = runCatching {
        val body = decode(nativeSongMusicDetail(id.toString())).getOrThrow()
        SongQualityInfo(
            songId = id,
            sizes = body.levels.mapNotNull { item ->
                AudioQuality.fromWire(item.level)?.let { it to item.size }
            }.toMap(),
        )
    }

    /** 批量取歌曲元数据。原生层已把嵌套结构拍平，这里直接拿。 */
    fun songDetail(ids: List<Long>): Result<List<Song>> = runCatching {
        decode(nativeSongDetail(ids.joinToString(","))).getOrThrow().songs
    }

    /** 每日推荐歌曲（需登录）。返回今天为你挑的那批歌，约 30 多首。 */
    fun recommendSongs(): Result<List<Song>> = runCatching {
        decode(nativeRecommendSongs()).getOrThrow().songs
    }

    fun reduceRecommendation(id: Long): Result<Unit> = runCatching {
        require(id > 0)
        nativeRecommendSongDislike(id.toString())
    }.fold(onSuccess = { decode(it).map { } }, onFailure = { Result.failure(it) })

    /** 每日推荐歌单（需登录），约 26 条。 */
    fun recommendResource(): Result<List<Playlist>> = runCatching {
        decode(nativeRecommendResource()).getOrThrow().playlists
    }

    /**
     * 排行榜列表。
     *
     * 上游返回 60 多条，大部分是地区榜和曲风榜，官方 4 榜只是其中的 `official == true` 那几条。
     * 这里不过滤，交给界面决定怎么挑。
     */
    fun toplists(): Result<List<Ranking>> = runCatching {
        decode(nativeToplist()).getOrThrow().lists
    }

    /**
     * 移动端首页区块（需登录）。
     *
     * 一次下发推荐歌单、曲风推荐歌曲、雷达歌单三块，各块按 [HomeBlock.code] 区分；
     * 服务端以后加减区块，这里不用改，界面按认识的 code 挑。
     */
    fun homeBlocks(): Result<List<HomeBlock>> = runCatching {
        decode(nativeHomeBlocks()).getOrThrow().blocks
    }

    fun homeFeed(params: String): Result<String> = runCatching {
        decode(nativeHomeFeed(params)).getOrThrow().payload
    }

    /**
     * 私人 FM（需登录）。
     *
     * 一次只返回 3 首，是流式的——想接着听就得再调一次。用完即弃，别当队列囤。
     *
     * 🔴 原生层已经把「FM 的简版 id」换成过 `song/detail` 的完整对象（歌手、封面、可播性都齐），
     * 所以这里拿到的就是能直接建队列的歌，不用再补一次详情。
     */
    fun personalFm(): Result<List<Song>> = runCatching {
        decode(nativePersonalFm()).getOrThrow().songs
    }

    /**
     * 近七天播放最多的一首歌（听歌排行的周榜）。
     *
     * 服务端**已经按播放次数降序**，取第一条即可，别再自己排。
     * 电台拿它当种子：这一台电台从这首歌的样子开始。
     */
    fun weeklyTopSong(userId: Long): Result<Song> = runCatching {
        require(userId > 0)
        decode(nativeWeeklyTopSong(userId.toString())).getOrThrow().song
            ?: throw IllegalStateException("最近七天没有听歌记录")
    }

    /**
     * 插播相似歌曲（`/song/simi/get`）：给一首歌，拿"接着它插下去"的相似歌。
     *
     * 🔴 上游**一次只给一首**（官方客户端就是一首一首插），所以 [count] 首是原生层链式要的 ——
     * 每次拿上一首当种子再问一次。实测同一个种子连问两次会给出不同的歌，链式走下来
     * 就是一条"越走越像"的流。
     *
     * 跟 [similarSongs]（`/simi/song`）不是一回事：那个是整页 5 首、不能分页、也不能续。
     */
    fun songSimi(songId: Long, count: Int = 3): Result<List<Song>> = runCatching {
        require(songId > 0)
        decode(nativeSongSimiGet(songId.toString(), count)).getOrThrow().songs
    }

    /**
     * 取歌单曲目，[offset] / [limit] 分页。
     *
     * 原生层是**两步请求**（先拿 trackIds 再换歌曲详情），比别的接口慢一档；
     * 只要首曲封面时 [limit] 传 1 就够。歌单页将来整页取，用默认值。
     */
    fun playlistTracks(
        playlistId: Long,
        limit: Int = 1000,
        offset: Int = 0,
    ): Result<List<Song>> = runCatching {
        decode(nativePlaylistTracks(playlistId.toString(), limit, offset)).getOrThrow().songs
    }

    /**
     * 最近播放的**单曲**记录（需登录，服务端按最后播放倒序，最多 100 条）。
     *
     * 🔴 首页「最近播放」的数据源。**不是**专辑维度（`/api/play-record/album/list`）——
     * 那个只在官方客户端的专辑页点播放时才记账（9.6.05 APK 的 `play-record` 家族只有
     * 查询/删除端点），Cirro 的播歌永远进不去；单曲维度跟听歌排行同源，播完就有
     * （实测有传播延迟）。按专辑归并是仓库的事（`HomeRepository.recentAlbums`）。
     *
     * 它跟首页区块没关系（38 个 `HOMEPAGE_BLOCK_*` 里没有它），得单独走一个端点。
     * 空列表是"这段时间没听"，**不是错误**，界面按"这块不显示"处理。
     */
    fun recentSongs(): Result<List<RecentSong>> = runCatching {
        decode(nativeRecentSongs()).getOrThrow().recent
    }

    fun intelligenceSongs(id: Long, playlistId: Long, startId: Long): Result<List<Song>> = runCatching {
        require(id > 0 && playlistId > 0 && startId > 0)
        decode(nativeIntelligenceSongs(id.toString(), playlistId.toString(), startId.toString()))
            .getOrThrow().songs
    }

    fun likedPlaylistId(userId: Long): Result<Long> = runCatching {
        require(userId > 0)
        decode(nativeLikedPlaylistId(userId.toString())).getOrThrow().id.also {
            check(it > 0) { "未能获取喜爱歌曲播放列表" }
        }
    }

    /**
     * 专辑类型（Single / EP / 专辑…，静态元数据，查一次终身有效）。
     *
     * 首页「最近播放」给单曲专辑的标题补 " - Single" 用。最近播放单曲里的 `al`
     * 对象不带 type（实测只有 id/name/pic/picUrl/pic_str/tns），只能逐张查专辑详情；
     * 调用方必须自己缓存，别每个刷新周期都真发请求。
     */
    fun albumType(albumId: Long): Result<String> = runCatching {
        decode(nativeAlbumType(albumId.toString())).getOrThrow().type
    }

    /**
     * 旧参数形式的结束上报；新会话使用 [scrobbleEvent] 保留独立累计量和结束原因。
     *
     * [sourceId] 是来源歌单 id，没有就传空串；[timeSeconds] 是实际播放的秒数。
     * 接收成功不代表服务端已经计入时长或排行。
     */
    fun scrobble(songId: Long, sourceId: String, timeSeconds: Long): Result<Boolean> =
        runCatching {
            decode(nativeScrobble(songId.toString(), sourceId, timeSeconds.toString()))
                .getOrThrow().reported
        }

    /**
     * 开始播放的上报（进「最近播放」）。歌一开始播就调，不等结束。
     *
     * 开始与结束分别发送，结束不能额外补发一次开始。
     */
    fun scrobbleStart(songId: Long, sourceId: String): Result<Boolean> = runCatching {
        decode(nativeScrobbleStart(songId.toString(), sourceId)).getOrThrow().reported
    }

    fun scrobbleEvent(userId: Long, payload: String): Result<Boolean> = runCatching {
        decode(nativeScrobbleEvent(userId, payload)).getOrThrow().reported
    }

    fun prepareScrobbleEvent(userId: Long, payload: String): Result<String> = runCatching {
        decode(nativePrepareScrobbleEvent(userId, payload)).getOrThrow().payload
            .ifEmpty { throw IllegalStateException("播放事件准备结果为空") }
    }

    /**
     * 歌单详情（需登录）。
     *
     * [CollectionInfo.subscribed] 是当前用户的收藏状态；[CollectionInfo.updateTimeMs]
     * 是歌单的更新时间（雷达类歌单这个字段不一定跟着内容走，如实显示）。
     */
    fun playlistDetail(id: Long): Result<CollectionInfo> = runCatching {
        val body = decode(nativePlaylistDetail(id.toString())).getOrThrow()
        CollectionInfo(
            kind = CollectionKind.PLAYLIST,
            id = id,
            title = body.name,
            author = body.creator,
            description = body.description,
            cover = body.cover,
            updateTimeMs = body.updateTime,
            trackCount = body.trackCount,
            playCount = body.playCount,
            subscribed = body.subscribed,
        )
    }

    /**
     * 专辑详情（单曲专辑同走这里）。歌曲列表跟着详情一起回来，不用二次请求。
     *
     * [CollectionInfo.updateTimeMs] 实为**发行时间**（语义跟歌单不同，界面换文案）；
     * `subscribed` 是原生层补查 `/api/album/detail/dynamic` 得到的，那步失败按未收藏处理。
     */
    fun albumDetail(id: Long): Result<CollectionDetail> = runCatching {
        val body = decode(nativeAlbumDetail(id.toString())).getOrThrow()
            CollectionDetail(
                info = CollectionInfo(
                    kind = CollectionKind.ALBUM,
                    id = id,
                    title = body.name,
                    author = body.artists.joinToString(" & "),
                    description = body.description,
                    cover = body.cover,
                    updateTimeMs = body.updateTime,
                    trackCount = body.trackCount,
                    subscribed = body.subscribed,
                    company = body.company,
                    albumType = body.albumType,
                    primaryArtistId = body.primaryArtistId,
                    primaryArtistName = body.primaryArtistName,
                    artistNames = body.artists,
                    artistIds = body.artistIds,
                ),
                songs = body.songs,
            )
    }

    fun artistAlbums(id: Long): Result<List<CollectionInfo>> = runCatching {
        decode(nativeArtistAlbums(id.toString())).getOrThrow().albums
    }

    /**
     * 艺人页「音乐视频」：走 `/api/mlog/artist/video`，原生层顺手把每条 MV 的直链问好
     * （并发发出去，几百毫秒一起回来）。
     *
     * 直链一小时过期，`url` 为空表示这条没拿到，卡片照样出图。
     */
    fun artistVideos(id: Long): Result<List<ArtistVideo>> = runCatching {
        decode(nativeArtistVideos(id.toString())).getOrThrow().videos
    }

    /**
     * 艺人头像，走 `/api/artist/head/info/get`（约 3 KB），比 [artistDetail] 轻三十倍——
     * 「全部艺人」列表要补几十上百个头像，不能每条都拉一份带 50 首热门歌的详情。
     *
     * 空串表示这个艺人上游就没有图（不是失败）：调用方缓存空串，别再反复查。
     */
    fun artistCover(id: Long): Result<String> = runCatching {
        decode(nativeArtistCover(id.toString())).getOrThrow().cover
    }

    fun similarSongs(id: Long): Result<List<Song>> = runCatching {
        decode(nativeSimilarSongs(id.toString())).getOrThrow().songs
    }

    fun artistDetail(id: Long): Result<CollectionDetail> = runCatching {
        val body = decode(nativeArtistDetail(id.toString())).getOrThrow()
        CollectionDetail(
            info = CollectionInfo(
                kind = CollectionKind.ARTIST,
                id = id,
                title = body.name,
                author = "热门歌曲",
                description = body.description,
                cover = body.cover,
                trackCount = body.trackCount,
                subscribed = body.subscribed,
            ),
            songs = body.songs,
        )
    }

    /**
     * 收藏 / 取消收藏（歌单、专辑、艺术家同走这里）。成功返回新状态；
     * 业务失败（收藏自己的歌单之类）走 [Result.failure]，消息给界面提示。
     */
    fun setCollectionSubscribed(kind: CollectionKind, id: Long, subscribe: Boolean): Result<Unit> =
        runCatching {
            val wireKind = when (kind) {
                CollectionKind.ALBUM -> "album"
                CollectionKind.ARTIST -> "artist"
                else -> "playlist"
            }
            decode(
                nativeCollectionSub(wireKind, id.toString(), if (subscribe) "1" else "2")
            ).getOrThrow()
        }

    fun likedSongIds(userId: Long): Result<Set<Long>> = runCatching {
        require(userId > 0)
        decode(nativeLikedSongIds(userId.toString())).getOrThrow().likedSongIds
            ?.toSet() ?: error("喜爱歌曲列表缺少 ids")
    }

    /** 歌曲红心，与歌单 / 专辑的收藏接口独立。 */
    fun setSongLiked(id: Long, userId: Long, liked: Boolean): Result<Unit> = runCatching {
        require(id > 0 && userId > 0)
        decode(nativeSongLike(id.toString(), userId.toString(), liked)).getOrThrow()
    }

    fun ownedPlaylists(userId: Long): Result<List<Playlist>> = runCatching {
        require(userId > 0)
        val playlists = mutableListOf<Playlist>()
        var offset = 0
        do {
            val page = decode(nativeOwnedPlaylists(userId.toString(), offset)).getOrThrow()
            playlists += page.playlists
            if (!page.more) break
            check(page.nextOffset > offset) { "播放列表分页没有前进" }
            offset = page.nextOffset
        } while (true)
        playlists.distinctBy { it.id }
    }

    fun addSongToPlaylist(songId: Long, playlistId: Long): Result<Unit> = runCatching {
        require(songId > 0 && playlistId > 0)
        decode(nativeAddSongToPlaylist(playlistId.toString(), songId.toString())).getOrThrow()
    }

    fun libraryCollections(kind: CollectionKind, userId: Long): Result<List<LibraryCollection>> = runCatching {
        require(userId > 0 && kind in listOf(CollectionKind.PLAYLIST, CollectionKind.ALBUM, CollectionKind.ARTIST))
        val items = mutableListOf<LibraryCollection>()
        var offset = 0
        do {
            val page = decode(nativeLibraryPage(kind.name, userId.toString(), offset)).getOrThrow()
            items += page.libraryItems
            if (!page.more) break
            check(page.nextOffset > offset) { "资料库分页没有前进" }
            offset = page.nextOffset
        } while (true)
        items.distinctBy { it.info.id }
    }

    fun likedTracks(playlistId: Long): Result<List<LibraryTrack>> = runCatching {
        require(playlistId > 0)
        decode(nativeLikedTracks(playlistId.toString())).getOrThrow().libraryTracks
    }

    /**
     * 直链空时给界面的一句话。
     *
     * `-110` 是"**这一档**拿不到"（实测请求 `vivid` 就是它），跟"这首歌整首不能播"是两回事 ——
     * 用户在音质面板里挑了够不着的档时，这句话要让他知道换个档就行，别以为歌废了。
     */
    private fun unavailableMessage(code: Int, level: String, serverMessage: String): String =
        if (code == -110) {
            "这首歌不支持「${AudioQuality.fromWire(level)?.displayName ?: level}」，换一档试试"
        } else {
            serverMessage.ifEmpty { "该歌曲暂无可播放的音频" }
        }

    private fun decode(raw: String?): Result<BridgeResponse> = runCatching {
        val body = json.decodeFromString<BridgeResponse>(
            raw ?: throw IllegalStateException("原生层返回空")
        )
        if (!body.ok) throw IllegalStateException(body.message.ifEmpty { "未知错误" })
        body
    }

    private external fun nativeQrKey(): String

    private external fun nativeQrCreate(key: String): String

    private external fun nativeQrCheck(key: String): String

    private external fun nativeSetCookie(cookie: String): Int

    private external fun nativeClearCookie()

    private external fun nativeLoginStatus(): String

    private external fun nativeSongUrl(id: String, level: String): String

    private external fun nativeSongMusicDetail(id: String): String

    private external fun nativeSongDetail(ids: String): String

    private external fun nativeRecommendSongs(): String

    private external fun nativeRecommendSongDislike(id: String): String

    private external fun nativeRecommendResource(): String

    private external fun nativeToplist(): String

    private external fun nativeHomeBlocks(): String

    private external fun nativeHomeFeed(params: String): String

    private external fun nativeRecentSongs(): String

    private external fun nativeIntelligenceSongs(id: String, pid: String, sid: String): String

    private external fun nativeLikedPlaylistId(uid: String): String

    private external fun nativeAlbumType(id: String): String

    private external fun nativePersonalFm(): String

    private external fun nativeWeeklyTopSong(uid: String): String

    private external fun nativeSongSimiGet(id: String, count: Int): String

    private external fun nativePlaylistTracks(id: String, limit: Int, offset: Int): String

    private external fun nativeScrobble(id: String, sourceid: String, time: String): String

    private external fun nativeScrobbleStart(id: String, sourceid: String): String

    private external fun nativeScrobbleEvent(userId: Long, payload: String): String

    private external fun nativePrepareScrobbleEvent(userId: Long, payload: String): String

    private external fun nativePlaylistDetail(id: String): String

    private external fun nativeAlbumDetail(id: String): String
    private external fun nativeArtistAlbums(id: String): String

    private external fun nativeArtistVideos(id: String): String
    private external fun nativeSimilarSongs(id: String): String

    private external fun nativeArtistDetail(id: String): String

    private external fun nativeArtistCover(id: String): String

    private external fun nativeCollectionSub(kind: String, id: String, t: String): String

    private external fun nativeLikedSongIds(uid: String): String

    private external fun nativeSongLike(id: String, uid: String, liked: Boolean): String

    private external fun nativeOwnedPlaylists(uid: String, offset: Int): String

    private external fun nativeAddSongToPlaylist(pid: String, songId: String): String

    private external fun nativeLibraryPage(kind: String, uid: String, offset: Int): String

    private external fun nativeLikedTracks(id: String): String
}

@Serializable
private data class BridgeResponse(
    val ok: Boolean = false,
    val message: String = "",
    val unikey: String = "",
    val qrurl: String = "",
    val code: Int = 0,
    val cookie: String = "",
    val loggedIn: Boolean = false,
    val profile: UserProfile? = null,
    val url: String = "",
    val level: String = "",
    val type: String = "",
    val size: Long = 0,
    val expi: Long = 0,
    val gain: Double? = null,
    val peak: Double? = null,
    /** `songQuality` 用：这首歌实际存在的档位（高→低），缺哪档就没有哪条。 */
    val levels: List<QualityLevel> = emptyList(),
    val songs: List<Song> = emptyList(),
    /** `weeklyTopSong` 用：周榜第一条，拿不到就是 null。 */
    val song: Song? = null,
    val playlists: List<Playlist> = emptyList(),
    val more: Boolean = false,
    val nextOffset: Int = 0,
    val lists: List<Ranking> = emptyList(),
    val blocks: List<HomeBlock> = emptyList(),
    val recent: List<RecentSong> = emptyList(),
    val reported: Boolean = false,
    val payload: String = "",
    val likedSongIds: List<Long>? = null,
    val libraryItems: List<LibraryCollection> = emptyList(),
    val libraryTracks: List<LibraryTrack> = emptyList(),
    // ---- 统一详情页 ----
    val id: Long = 0,
    val name: String = "",
    val cover: String = "",
    val creator: String = "",
    val description: String = "",
    val updateTime: Long = 0,
    val trackCount: Int = 0,
    val playCount: Long = 0,
    val subscribed: Boolean = false,
    val artists: List<String> = emptyList(),
    val artistIds: List<Long> = emptyList(),
    val company: String = "",
    val albumType: String = "",
    val primaryArtistId: Long = 0L,
    val primaryArtistName: String = "",
    val albums: List<CollectionInfo> = emptyList(),
    val videos: List<ArtistVideo> = emptyList(),
)

/** [NcmBridge.songQuality] 原生层返回的一档：`level` 是 wire 名，`size` 是这一档的体积（字节）。 */
@Serializable
private data class QualityLevel(
    val level: String = "",
    val br: Int = 0,
    val size: Long = 0,
)
