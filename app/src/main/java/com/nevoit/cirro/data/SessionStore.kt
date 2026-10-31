package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.UserProfile
import kotlinx.serialization.json.Json

/**
 * 网易 cookie 与用户显示资料的落盘。
 *
 * 明文的 cookie 只活在原生层内存里（见 [AccountRepository]），磁盘上这份始终是密文。
 * 加解密本体在 [EncryptedSecretStore]，这里只负责给它一个语义化的槽名。
 */
class SessionStore internal constructor(
    private val read: (String) -> String?,
    private val write: (String, String) -> Boolean,
    private val remove: (String) -> Unit,
) {
    constructor(secrets: EncryptedSecretStore) : this(secrets::read, secrets::write, secrets::remove)

    private val json = Json { ignoreUnknownKeys = true }

    fun readCookie(): String? = read(KeyCookie)

    /**
     * 返回 false 说明没写进去（commit 被拒）。调用方据此决定要不要提示"这次登录不跨进程"。
     */
    fun writeCookie(cookie: String): Boolean = write(KeyCookie, cookie)

    fun readProfile(): UserProfile? = read(KeyProfile)?.let { encoded ->
        runCatching { json.decodeFromString<UserProfile>(encoded) }.getOrNull()
            ?.takeIf { it.userId > 0 }
    }

    fun writeProfile(profile: UserProfile): Boolean =
        write(KeyProfile, json.encodeToString(profile))

    fun clearProfile() {
        remove(KeyProfile)
    }

    fun clear() {
        remove(KeyCookie)
        clearProfile()
    }

    private companion object {
        const val KeyCookie = "cookie"
        const val KeyProfile = "profile"
    }
}
