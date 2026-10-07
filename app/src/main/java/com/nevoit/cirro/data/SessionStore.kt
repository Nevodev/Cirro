package com.nevoit.cirro.data

/**
 * 网易 cookie 的落盘。
 *
 * 明文的 cookie 只活在原生层内存里（见 [AccountRepository]），磁盘上这份始终是密文。
 * 加解密本体在 [EncryptedSecretStore]，这里只负责给它一个语义化的槽名。
 */
class SessionStore(private val secrets: EncryptedSecretStore) {

    fun readCookie(): String? = secrets.read(KeyCookie)

    /**
     * 返回 false 说明没写进去（commit 被拒）。调用方据此决定要不要提示"这次登录不跨进程"。
     */
    fun writeCookie(cookie: String): Boolean = secrets.write(KeyCookie, cookie)

    fun clear() {
        secrets.remove(KeyCookie)
    }

    private companion object {
        const val KeyCookie = "cookie"
    }
}
