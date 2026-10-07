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

/** Apple 官方 TTML。[ttml] 为空说明没拿到，[reason] 说明为什么（`no_media_user_token` 等）。 */
data class AppleTtml(
    val ttml: String,
    val reason: String,
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
     * 传空串等于关闭 Apple 这一级（AMLL TTML DB 顶替）。开发者 access token 由原生层
     * 自己从 Apple 网页抓，不用给。
     */
    fun setAppleToken(
        mediaUserToken: String,
        storefront: String = "",
        language: String = "",
    ): Boolean = nativeSetAppleToken(mediaUserToken, storefront, language) == 0

    /** 是否已配置 Apple 凭据。 */
    fun appleConfigured(): Boolean =
        decode(nativeAppleConfigured()).getOrNull()?.configured ?: false

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

    /** Apple 官方 TTML。[AppleTtml.ttml] 为空表示没拿到（无凭据 / 这首歌没有官方词）。 */
    fun apple(amId: String): Result<AppleTtml> = decode(nativeApple(amId)).map { body ->
        AppleTtml(ttml = body.ttml, reason = body.reason)
    }

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

    private external fun nativeSetAppleToken(
        mediaUserToken: String,
        storefront: String,
        language: String,
    ): Int

    private external fun nativeAppleConfigured(): String

    private external fun nativeNetease(id: String): String

    private external fun nativeAmll(ncmId: String): String

    private external fun nativeApple(amId: String): String

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
