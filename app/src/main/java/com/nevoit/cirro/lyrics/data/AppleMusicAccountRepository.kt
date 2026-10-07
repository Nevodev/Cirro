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
 * Apple 只是歌词取词链**第 1 级**（官方逐字 TTML）的凭据，没登也照常用 App —— 那一级会被
 * AMLL TTML DB 顶替，只是拿不到「直连官方」而已。
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
     * storefront / language 一律留空交给原生层自动探测：`/v1/me/storefront` 能拿到账号区域，
     * 而 `l=` 语种有自己的默认值（见 `apple.rs` 的 `DEFAULT_LYRIC_LANGUAGE`）。
     */
    private fun pushToken(token: String?) {
        runCatching { LyricsBridge.setAppleToken(token.orEmpty(), "", "") }
            .onFailure { Log.w(Tag, "Apple 凭据下发失败：${it.message}") }
    }

    private companion object {
        const val Tag = "AppleMusicAccount"
    }
}
