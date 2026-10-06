package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.CollectionDetail
import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.HomeBlock
import com.nevoit.cirro.data.model.Playlist
import com.nevoit.cirro.data.model.Ranking
import com.nevoit.cirro.data.model.RecentSong
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.SongAudio
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
     * 返回的直链有效期很短（[SongAudio.expiresInSeconds]，实测 1200 秒），别囤。
     */
    fun songUrl(id: Long, level: String = "standard"): Result<SongAudio> = runCatching {
        val body = decode(nativeSongUrl(id.toString(), level)).getOrThrow()
        if (body.url.isEmpty()) {
            throw SongUnavailableException(body.message.ifEmpty { "该歌曲暂无可播放的音频" })
        }
        SongAudio(
            url = body.url,
            level = body.level,
            type = body.type,
            sizeBytes = body.size,
            expiresInSeconds = body.expi,
            gainDb = body.gain,
            peak = body.peak,
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

    /**
     * 私人 FM（需登录）。
     *
     * 一次只返回 3 首，是流式的——想接着听就得再调一次。用完即弃，别当队列囤。
     */
    fun personalFm(): Result<List<Song>> = runCatching {
        decode(nativePersonalFm()).getOrThrow().songs
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
     * 听歌打卡：一首歌完整听完（或自然结束）后上报给网易，涨听歌排行计数。
     *
     * [sourceId] 是来源歌单 id，没有就传空串；[timeSeconds] 是实际播放的秒数。
     * 上报是尽力而为——失败只体现在返回值里，调用方（播放服务）不处理、不重试。
     */
    fun scrobble(songId: Long, sourceId: String, timeSeconds: Long): Result<Boolean> =
        runCatching {
            decode(nativeScrobble(songId.toString(), sourceId, timeSeconds.toString()))
                .getOrThrow().reported
        }

    /**
     * 开始播放的上报（进「最近播放」）。歌一开始播就调，不等结束。
     *
     * 跟 [scrobble] 是官方的两条独立日志：startplay 在开头，play 在完整听完之后。
     */
    fun scrobbleStart(songId: Long, sourceId: String): Result<Boolean> = runCatching {
        decode(nativeScrobbleStart(songId.toString(), sourceId)).getOrThrow().reported
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
                ),
                songs = body.songs,
            )
    }

    /**
     * 收藏 / 取消收藏（歌单、专辑同走这里）。成功返回新状态；
     * 业务失败（收藏自己的歌单之类）走 [Result.failure]，消息给界面提示。
     */
    fun setCollectionSubscribed(kind: CollectionKind, id: Long, subscribe: Boolean): Result<Unit> =
        runCatching {
            val wireKind = when (kind) {
                CollectionKind.ALBUM -> "album"
                else -> "playlist"
            }
            decode(
                nativeCollectionSub(wireKind, id.toString(), if (subscribe) "1" else "2")
            ).getOrThrow()
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

    private external fun nativeSongDetail(ids: String): String

    private external fun nativeRecommendSongs(): String

    private external fun nativeRecommendResource(): String

    private external fun nativeToplist(): String

    private external fun nativeHomeBlocks(): String

    private external fun nativeRecentSongs(): String

    private external fun nativeAlbumType(id: String): String

    private external fun nativePersonalFm(): String

    private external fun nativePlaylistTracks(id: String, limit: Int, offset: Int): String

    private external fun nativeScrobble(id: String, sourceid: String, time: String): String

    private external fun nativeScrobbleStart(id: String, sourceid: String): String

    private external fun nativePlaylistDetail(id: String): String

    private external fun nativeAlbumDetail(id: String): String

    private external fun nativeCollectionSub(kind: String, id: String, t: String): String
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
    val songs: List<Song> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val lists: List<Ranking> = emptyList(),
    val blocks: List<HomeBlock> = emptyList(),
    val recent: List<RecentSong> = emptyList(),
    val reported: Boolean = false,
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
    val company: String = "",
    val albumType: String = "",
)
