package com.nevoit.cirro.lyrics.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 网易歌词：三份独立文本，翻译/罗马音按行时间戳并入是在解析器里做的。 */
data class NeteaseLyric(
    val lrc: String,
    val translation: String,
    val roman: String,
) {
    /** 主歌词都没有 —— 这一级等于没取到。 */
    val isEmpty: Boolean get() = lrc.isBlank()
}

@Serializable
data class QqSongCandidate(
    val id: String = "",
    val name: String = "",
    val artists: List<String> = emptyList(),
    val album: String = "",
    val durationMs: Long = 0,
)

data class QqLyric(val qrc: String, val translation: String, val roman: String)

/**
 * AMLL TTML DB 的一首。
 *
 * [appleMusicIds] 来自 TTML 头部的 `amll:meta`，是「要不要再去 Apple 官方取一次」的依据。
 * 实测约 70% 条目带它；没有就是那首歌没有 Apple 对应版本，不是错误。
 */
data class AmllTtml(
    val ttml: String,
    val base: String,
    val appleMusicIds: List<String>,
)

/**
 * Apple TTML。[ttml] 为空说明没拿到，[reason] 说明为什么（目前只会是 `no_lyrics`）。
 *
 * [via] 是这份词的来源：`official`（登录后直连 amp-api）。
 *
 * [storefront] 是这次实际用的商店区域、[storefrontProbed] 说明它是探测出来的还是回落值。
 * 这两个字段是排障用的：catalog **按区域分区**（同一首中文歌在 `cn` 区有逐字词、在 `us` 区
 * 是零歌词），而 `reason` 只会写 `no_lyrics` —— 没有它们就分不清"这首歌没有官方词"和
 * "区域取错了"。
 */
data class AppleTtml(
    val ttml: String,
    val reason: String,
    val via: String,
    val storefront: String,
    val storefrontProbed: Boolean,
)

/**
 * Apple 搜索结果里的一个候选曲目。
 *
 * [durationMs] 是匹配的主要依据（同一首歌的不同版本靠时长分得开）；
 * [hasLyrics] 是 Apple 标称的「有歌词」，⚠️ **不保证有逐字**（`syllable-lyrics`）——
 * 那要真取一次才知道，所以它只能当加权项，不能当「有词」的判据。
 *
 * 所有字段都有默认值：原生层对某些区域会省字段，缺了不该让整条链解码失败。
 */
@Serializable
data class AppleSongCandidate(
    val id: String = "",
    val name: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val hasLyrics: Boolean = false,
    val artists: List<AppleArtistCandidate> = emptyList(),
)

@Serializable
data class AppleArtistCandidate(
    val id: String,
    val name: String = "",
)

/**
 * 一次 Apple 搜索的结果。
 *
 * [storefront] / [storefrontProbed] 与 [AppleTtml] 那两个同义（排障用）：区域取错时
 * 搜索结果照样有，但拿它去取词是空手 —— 分开记才能看出是"搜错了"还是"取不到"。
 */
data class AppleSearchResult(
    val songs: List<AppleSongCandidate>,
    val storefront: String,
    val storefrontProbed: Boolean,
)

/** lrclib 的一首。[synced] 可能为空（只有纯文本）。 */
data class LrclibLyric(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationMs: Long,
    val instrumental: Boolean,
    val synced: String,
    val plain: String,
)

/**
 * 歌词取词引擎（`libcirro_lyrics.so`）的桥。
 *
 * ⚠️ 这是**独立于 [com.nevoit.cirro.data.NcmBridge] 的第二个原生库**，网易 cookie 各持一份 ——
 * 推 cookie 时两边必须一起推（见 `AccountRepository`），否则会出现「界面显示已登录、
 * 歌词却按未登录取」的怪状态。
 *
 * 与桥的约定跟 `NcmBridge` 完全一致：原生层一律返回 JSON，成败都走正常返回，
 * 这里翻译成 [Result]；`Result.success(null)` 表示「这一级没有词」（正常），
 * 只有 [Result.failure] 才是请求本身出了问题。
 */
object LyricsBridge {

    private val json = Json { ignoreUnknownKeys = true }

    init {
        System.loadLibrary("cirro_lyrics")
    }

    /** 原生库版本号，自检用。 */
    fun version(): String? =
        decode(nativeVersion()).getOrNull()?.version?.ifEmpty { null }

    /** 设置网易 cookie（与 `NcmBridge.setCookie` 同进同退）。 */
    fun setCookie(cookie: String): Boolean = nativeSetCookie(cookie) == 0

    /** 清空网易登录态。 */
    fun clearCookie() {
        nativeClearCookie()
    }

    /**
     * 配置 Apple Music 凭据。
     *
     * [mediaUserToken] 是 Apple Music **订阅账号**的 `media-user-token`，与网易 cookie 无关；
     * 传空串 = 未登录，跳过 Apple 歌词。
     *
     * 开发者 access token、storefront、language 全由原生层自己弄（抓 Apple 网页 /
     * 问 `/v1/me/storefront`），上层不用也不该管 —— 与原项目一致。
     */
    fun setAppleToken(mediaUserToken: String): Boolean =
        nativeSetAppleToken(mediaUserToken) == 0

    /**
     * 下发**开发者 token 的落盘缓存目录**（App 启动时调一次就够）。
     *
     * 原生层拿不到 Context，这个可写路径只能由上层给 —— 传 `context.filesDir` 下的一个子目录，
     * 别用 `cacheDir`（系统会清，那就白缓存了）。
     *
     * 开发者 token 与账号无关、抓一次能用几小时，缓存下来是为了**冷启动不重抓**那个
     * 一百多 KB 的网页 + JS。传空串 = 不落盘（只在进程内存里缓存）。
     */
    fun setAppleCacheDir(dir: String) {
        nativeSetAppleCacheDir(dir)
    }

    /** 是否已配置 Apple 凭据。 */
    fun appleConfigured(): Boolean =
        decode(nativeAppleConfigured()).getOrNull()?.configured ?: false

    fun qqSearch(term: String): Result<List<QqSongCandidate>> =
        decode(nativeQqSearch(term)).map { it.qqSongs }

    fun qqLyric(id: String): Result<QqLyric?> = decode(nativeQqLyric(id)).map {
        if (it.qrc.isBlank()) null else QqLyric(it.qrc, it.translation, it.roman)
    }

    /** 网易 LRC（只逐行）。空即这一级没取到。 */
    fun netease(id: Long): Result<NeteaseLyric?> = decode(nativeNetease(id.toString())).map { body ->
        if (body.empty) {
            null
        } else {
            NeteaseLyric(
                lrc = body.lrc,
                translation = body.translation,
                roman = body.roman,
            )
        }
    }

    /** AMLL TTML DB。返回 null 表示库里没有这首。 */
    fun amll(ncmId: Long): Result<AmllTtml?> = decode(nativeAmll(ncmId.toString())).map { body ->
        if (!body.found) {
            null
        } else {
            AmllTtml(
                ttml = body.ttml,
                base = body.base,
                appleMusicIds = body.appleMusicIds,
            )
        }
    }

    /**
     * Apple 官方歌词，需要 `media-user-token`。
     *
     * [AppleTtml.ttml] 为空表示没拿到（这首歌确实没有 / 请求失败）。
     */
    fun apple(amId: String): Result<AppleTtml> = decode(nativeApple(amId)).map { body ->
        AppleTtml(
            ttml = body.ttml,
            reason = body.reason,
            via = body.via,
            storefront = body.storefront,
            storefrontProbed = body.storefrontProbed,
        )
    }

    /**
     * 搜 Apple Music，拿候选曲目。
     *
     * 这是**不依赖 AMLL 的 Apple id 入口**。在此之前 Apple 取词只能靠 AMLL 的
     * `amll:meta` 给 id，而 AMLL 索引只覆盖网易曲库的一小部分 —— 没命中的歌永远
     * 「跳过(无 appleId)」，够不到官方逐字词。自己搜之后，入口覆盖率交给 Apple 曲库本身。
     *
     * 🔴 **不需要登录**：只靠原生层从 Apple 网页抓来的开发者 token 即可。
     *
     * [term] 一般是「歌名 歌手」。**不在这里挑歌**：返回全部候选，由 [AppleSongMatcher]
     * 打分 —— 匹配是纯计算，放 Kotlin 侧改一次不用重编 `.so`。
     *
     * [storefront] 空串 = 自动（已登录用账号区域、未登录用原生层默认区域）；非空 = 显式
     * 指定区域（未登录时 `cn` 搜不到够格候选、拿 `us` 兜一次用）。
     */
    fun appleSearch(
        term: String,
        limit: Int = 12,
        storefront: String = "",
    ): Result<AppleSearchResult> =
        decode(nativeAppleSearch(term, limit.toString(), storefront)).map { body ->
            AppleSearchResult(
                songs = body.songs,
                storefront = body.storefront,
                storefrontProbed = body.storefrontProbed,
            )
        }

    fun appleArtistSearch(
        term: String,
        limit: Int = 12,
        storefront: String = "",
    ): Result<AppleSearchResult> =
        decode(nativeAppleArtistSearch(term, limit.toString(), storefront)).map { body ->
            AppleSearchResult(body.songs, body.storefront, body.storefrontProbed)
        }

    fun appleArtistHeader(id: String, storefront: String): Result<AppleArtistHeader> =
        decode(nativeAppleArtistHeader(id, storefront)).map { it.header }

    fun appleArtistVideo(id: String, storefront: String): Result<String?> =
        appleArtistHeader(id, storefront).map { it.videoUrl }

    /** lrclib。返回 null 表示查不到。 */
    fun lrclib(
        trackName: String,
        artistName: String,
        albumName: String,
        durationMs: Long?,
    ): Result<LrclibLyric?> = decode(
        nativeLrclib(
            trackName,
            artistName,
            albumName,
            // 原生层按秒查；没时长就不要这个参数（不然会筛掉正确结果）
            durationMs?.takeIf { it > 0 }?.let { (it / 1000).toString() }.orEmpty(),
        )
    ).map { body ->
        if (!body.found) {
            null
        } else {
            LrclibLyric(
                id = body.id,
                trackName = body.trackName,
                artistName = body.artistName,
                albumName = body.albumName,
                durationMs = (body.duration * 1000).toLong(),
                instrumental = body.instrumental,
                synced = body.syncedLyrics,
                plain = body.plainLyrics,
            )
        }
    }

    private fun decode(raw: String?): Result<BridgeResponse> = runCatching {
        val body = json.decodeFromString<BridgeResponse>(
            raw ?: throw IllegalStateException("原生层返回空")
        )
        if (!body.ok) throw IllegalStateException(body.message.ifEmpty { "未知错误" })
        body
    }

    private external fun nativeVersion(): String

    private external fun nativeSetCookie(cookie: String): Int

    private external fun nativeClearCookie()

    private external fun nativeSetAppleToken(mediaUserToken: String): Int

    private external fun nativeSetAppleCacheDir(dir: String)

    private external fun nativeAppleConfigured(): String

    private external fun nativeNetease(id: String): String

    private external fun nativeQqSearch(term: String): String

    private external fun nativeQqLyric(id: String): String

    private external fun nativeAmll(ncmId: String): String

    private external fun nativeApple(amId: String): String

    private external fun nativeAppleSearch(term: String, limit: String, storefront: String): String

    private external fun nativeAppleArtistSearch(term: String, limit: String, storefront: String): String

    private external fun nativeAppleArtistHeader(id: String, storefront: String): String

    private external fun nativeLrclib(
        trackName: String,
        artistName: String,
        albumName: String,
        durationSec: String,
    ): String
}

/**
 * 原生层的统一信封。字段是四个接口的并集，缺的走默认值 —— 与 `NcmBridge.BridgeResponse`
 * 同一套写法，加接口不用改这里。
 */
@Serializable
private data class BridgeResponse(
    val ok: Boolean = false,
    val message: String = "",
    // 通用
    val version: String = "",
    val configured: Boolean = false,
    val qqSongs: List<QqSongCandidate> = emptyList(),
    val qrc: String = "",
    // 网易
    val empty: Boolean = false,
    val lrc: String = "",
    val translation: String = "",
    val roman: String = "",
    // AMLL
    val found: Boolean = false,
    val ttml: String = "",
    val base: String = "",
    val appleMusicIds: List<String> = emptyList(),
    // Apple
    val reason: String = "",
    val via: String = "",
    val storefront: String = "",
    val storefrontProbed: Boolean = false,
    // Apple 搜索
    val songs: List<AppleSongCandidate> = emptyList(),
    val header: AppleArtistHeader = AppleArtistHeader(),
    // lrclib
    val id: Long = 0,
    val trackName: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val duration: Double = 0.0,
    val instrumental: Boolean = false,
    val syncedLyrics: String = "",
    val plainLyrics: String = "",
)
