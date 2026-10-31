package com.nevoit.cirro.data

import android.util.Log
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.UserProfile
import com.nevoit.cirro.lyrics.data.LyricsBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 登录态的唯一真相源：谁想读"现在登录了没、是谁"，只读 [session]。
 *
 * 凭据分两处：cookie 密文在 [SessionStore]，明文只在原生层的内存里。两边靠 [restore] 对齐——
 * 冷启动先解密出 cookie 塞回原生层，再问一次 [NcmBridge.loginStatus] 确认它还有效。
 *
 * 所有写操作都跑在 [scope]（Application 级）而不是某个 ViewModel 的 scope 里：登录成功后
 * LoginActivity 马上就 finish 了，跟着它的 scope 会被取消，cookie 就存不下去。
 */
class AccountRepository(
    private val store: SessionStore,
    private val scope: CoroutineScope,
) {

    private val _session = MutableStateFlow<Session>(Session.Unknown)
    val session: StateFlow<Session> = _session.asStateFlow()

    // Cached display data must not turn an unverified session into a logged-in session.
    private val _profile = MutableStateFlow<UserProfile?>(null)
    val profile: StateFlow<UserProfile?> = _profile.asStateFlow()

    /** 冷启动调用一次：把磁盘上的登录态恢复到原生层。 */
    fun restore() {
        scope.launch {
            val (cookie, cachedProfile) = withContext(Dispatchers.IO) {
                store.readCookie() to store.readProfile()
            }
            if (cookie == null) {
                _profile.value = null
                withContext(Dispatchers.IO) { runCatching { store.clearProfile() } }
                _session.value = Session.LoggedOut
                return@launch
            }

            _profile.value = cachedProfile
            NcmBridge.setCookie(cookie)
            // 歌词引擎是另一个 .so，cookie 各持一份，必须一起推（否则歌词会按未登录取）
            LyricsBridge.setCookie(cookie)

            val status = NcmBridge.loginStatus()
            val profile = status.getOrNull()
            when {
                // 请求本身失败：cookie 未必坏，留在磁盘里下次启动再验，界面按离线处理
                status.isFailure -> _session.value = Session.Offline

                profile != null -> acceptProfile(profile)

                else -> {
                    // 确实没登录，清掉免得每次启动都白发一次请求
                    forgetCookie()
                    _session.value = Session.LoggedOut
                }
            }
        }
    }

    /**
     * 扫码确认后调用。[cookie] 为空说明原生层没拿到 Set-Cookie，那就不落盘，
     * 但仍然去问一次登录态——cookie 有时已经在原生层的客户端里了。
     */
    fun onLoginSucceeded(cookie: String?) {
        scope.launch {
            _profile.value = null
            withContext(Dispatchers.IO) { runCatching { store.clearProfile() } }
            val cookieSaved = persistCookie(cookie)

            // 先落一个占位资料：登录态是确定的，头像晚一步到不该让登录卡片又冒出来
            _session.value = Session.LoggedIn(UserProfile())

            val profile = NcmBridge.loginStatus().getOrNull() ?: return@launch
            acceptProfile(profile, persist = cookieSaved)
        }
    }

    fun logout() {
        scope.launch {
            forgetCookie()
            _session.value = Session.LoggedOut
        }
    }

    private suspend fun persistCookie(cookie: String?): Boolean {
        if (cookie.isNullOrEmpty()) return false
        // 扫码确认时 NcmBridge 那份已经写进去了，这里补歌词引擎那份
        LyricsBridge.setCookie(cookie)
        // 落盘失败不该连着登录态一起丢：本次会话照样是登录的，只是下次冷启动要重扫
        val saved = withContext(Dispatchers.IO) {
            runCatching { store.writeCookie(cookie) }.getOrDefault(false)
        }
        if (!saved) {
            Log.w(Tag, "cookie 落盘失败，这次登录不会跨进程保留")
        }
        return saved
    }

    private suspend fun acceptProfile(profile: UserProfile, persist: Boolean = true) {
        _profile.value = profile
        _session.value = Session.LoggedIn(profile)
        if (!persist || profile.userId <= 0) return
        val saved = withContext(Dispatchers.IO) {
            runCatching { store.writeProfile(profile) }.getOrDefault(false)
        }
        if (!saved) Log.w(Tag, "用户资料缓存写入失败")
    }

    private suspend fun forgetCookie() {
        _profile.value = null
        withContext(Dispatchers.IO) { runCatching { store.clear() } }
        NcmBridge.clearCookie()
        LyricsBridge.clearCookie()
    }

    private companion object {
        const val Tag = "AccountRepository"
    }
}
