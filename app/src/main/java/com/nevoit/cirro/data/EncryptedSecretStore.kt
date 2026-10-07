package com.nevoit.cirro.data

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感字符串的加密落盘，按名字分槽（网易 cookie、Apple 登录令牌……）。
 *
 * 明文的账号凭据不碰磁盘：值先交给 AndroidKeyStore 里的 AES-GCM 密钥加密，密文（IV 前置）
 * 再存进 SharedPreferences。密钥生成在 TEE 里、导不出来，所以拿到 prefs 文件也解不开；
 * 清掉 App 数据或换锁屏就整份失效，那时按"没存过"处理即可。
 *
 * KeyStore 首次访问要几十毫秒，调用方别放在主线程。
 *
 * ⚠️ prefs 文件名与密钥别名沿用 `cirro_session*` 这两个老名字：改掉会让已装机的用户
 * 解不开自己的 cookie，白白丢一次登录态。这里只是把通用实现从 `SessionStore` 里抽出来，
 * 落盘位置一个字节都没动。
 */
class EncryptedSecretStore(context: Context) {

    private val prefs = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    /** 读一个槽。解不开（重装、清数据、改锁屏）就把这个槽删掉，当作从没存过。 */
    fun read(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return runCatching { decrypt(stored) }.getOrElse {
            remove(name)
            null
        }
    }

    /**
     * 返回 false 说明没写进去（commit 被拒）。
     *
     * 要 `commit` 不要 `apply`：写入本身已在 IO 线程上，而凭据不能"可能还没落盘"就被算作成功。
     * 也因此用不了 KTX 的 `edit(commit = true) { }`——它返回 Unit，commit 的结果在实现里被直接
     * 丢弃；回读 prefs 也替代不了（新值是先写进内存 map 再落盘的，写盘失败时回读照样拿得到）。
     */
    @SuppressLint("UseKtx")
    fun write(name: String, value: String): Boolean =
        prefs.edit().putString(name, encrypt(value)).commit()

    fun remove(name: String) {
        prefs.edit(commit = true) { this.remove(name) }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > IvLength) { "密文长度不合法" }
        val iv = payload.copyOfRange(0, IvLength)
        val body = payload.copyOfRange(IvLength, payload.size)

        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TagLengthBits, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        val existing = keyStore.getEntry(KeyAlias, null)
        if (existing is KeyStore.SecretKeyEntry) {
            return existing.secretKey
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore)
        generator.init(
            KeyGenParameterSpec.Builder(
                KeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PrefsName = "cirro_session"
        const val KeyAlias = "cirro_session_key"
        const val AndroidKeyStore = "AndroidKeyStore"
        const val Transformation = "AES/GCM/NoPadding"
        const val IvLength = 12
        const val TagLengthBits = 128
    }
}
