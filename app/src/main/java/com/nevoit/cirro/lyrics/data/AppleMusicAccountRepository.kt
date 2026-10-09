package com.nevoit.cirro.lyrics.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Apple Music 登录态。 */
sealed interface AppleMusicSession {

    /** 冷启动解密还没跑完，界面先别断言"没登"。 */
    data object Unknown : AppleMusicSession

    data object SignedOut : AppleMusicSession

    data object SignedIn : AppleMusicSession
}

/**
 * Apple Music 登录态的**唯一真相源**。
 *
 * 与网易那个 [com.nevoit.cirro.data.AccountRepository] 完全无关、互不影响：网易是主账号，
 * Apple 只是歌词取词链**第 1 级**（Apple 逐字 TTML）的凭据。没登也照常用 App —— 那一级会
 * 改用第三方（`lyrics.paxsenix.org`）取**同一份 Apple 同源 TTML**，只是不直连官方、质量略低而已。
 *
 * 令牌走 [AppleMusicTokenStore]（AndroidKeyStore AES-GCM）落盘；明文只活在原生层内存里。
 * 推给原生层时与 [LyricsBridge] 的 `setAppleToken` 对齐，冷启动由 [restore] 回灌 —— 和 cookie
 * 那套是一个套路。
 *
 * 所有写操作都跑在 [scope]（Application 级）：登录成功后 Activity 立刻 finish，
 * 跟着它的 scope 会被取消，令牌就存不下去了。
 */
class AppleMusicAccountRepository(
    private val store: AppleMusicTokenStore,
    private val scope: CoroutineScope,
) {

    private val _session = MutableStateFlow<AppleMusicSession>(AppleMusicSession.Unknown)
    val session: StateFlow<AppleMusicSession> = _session.asStateFlow()

    /** 冷启动调用一次：把磁盘上的令牌解密出来推给原生层。 */
    fun restore() {
        scope.launch {
            val token = withContext(Dispatchers.IO) { store.readToken() }
            // 这条链断了界面完全看不出来（取词会安静地回落到 AMLL），只有日志能证明。
            Log.d(Tag, "冷启动恢复 Apple 凭据：${if (token == null) "磁盘上没有 / 解不开" else "${token.length} 字符"}")
            pushToken(token)
            _session.value = if (token == null) AppleMusicSession.SignedOut else AppleMusicSession.SignedIn
        }
    }

    /** WebView 里抓到 `media-user-token` —— 登录成功。 */
    fun onTokenCaptured(token: String) {
        if (token.isBlank()) return
        scope.launch {
            // 先推内存再落盘：这次会话立刻能用，落盘失败只损失"跨进程保留"
            pushToken(token)
            _session.value = AppleMusicSession.SignedIn

            val saved = withContext(Dispatchers.IO) {
                runCatching { store.writeToken(token) }.getOrDefault(false)
            }
            if (!saved) {
                Log.w(Tag, "Apple 令牌落盘失败，这次登录不会跨进程保留")
            }
        }
    }

    fun logout() {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { store.clear() } }
            pushToken(null)
            _session.value = AppleMusicSession.SignedOut
        }
    }

    /**
     * 把令牌下发给原生层。`null`/空串等于**关闭 Apple 这一级**（原生层会把 token 置空）。
     *
     * 就这一个参数 —— storefront / language 由原生层问 `/v1/me/storefront` 自己拿。
     *
     * 🔴 一定要留日志：`.so` 与 Kotlin 侧签名对不上时这里会抛 `UnsatisfiedLinkError`，
     * 而它的后果只是「取词回落到 AMLL」，看起来一切都正常。
     */
    private fun pushToken(token: String?) {
        runCatching { LyricsBridge.setAppleToken(token.orEmpty()) }
            .onSuccess { Log.d(Tag, "Apple 凭据已下发（${token?.length ?: 0} 字符，原生层接受=$it）") }
            .onFailure { Log.w(Tag, "Apple 凭据下发失败：${it.javaClass.simpleName}: ${it.message}") }
    }

    private companion object {
        const val Tag = "AppleMusicAccount"
    }
}
