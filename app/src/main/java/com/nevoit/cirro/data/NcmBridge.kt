package com.nevoit.cirro.data

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
        )
    }

    /** 批量取歌曲元数据。原生层已把嵌套结构拍平，这里直接拿。 */
    fun songDetail(ids: List<Long>): Result<List<Song>> = runCatching {
        decode(nativeSongDetail(ids.joinToString(","))).getOrThrow().songs
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
    val songs: List<Song> = emptyList(),
)
