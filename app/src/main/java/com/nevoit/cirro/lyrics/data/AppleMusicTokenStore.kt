package com.nevoit.cirro.lyrics.data

import com.nevoit.cirro.data.EncryptedSecretStore

/**
 * Apple Music 登录令牌（`media-user-token`）的落盘。
 *
 * ⚠️ 这是 Apple Music **订阅账号**的令牌（与网易 cookie 完全无关），拿到它等于拿到一份
 * Apple Music 会话凭据 —— 所以和 cookie 一样必须加密落盘，绝不明文。
 * 加密本体在 [EncryptedSecretStore]，与 cookie 同文件不同槽。
 */
class AppleMusicTokenStore(private val secrets: EncryptedSecretStore) {

    fun readToken(): String? = secrets.read(KeyToken)

    /** 返回 false 说明没写进去（commit 被拒）。 */
    fun writeToken(token: String): Boolean = secrets.write(KeyToken, token)

    fun clear() {
        secrets.remove(KeyToken)
    }

    private companion object {
        const val KeyToken = "apple_media_user_token"
    }
}
