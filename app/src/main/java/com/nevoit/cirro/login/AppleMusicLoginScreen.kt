package com.nevoit.cirro.login

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nevoit.cirro.R
import com.nevoit.cirro.lyrics.data.AppleMusicAccountRepository
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.PageHeader
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.component.WavyLinearProgress
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.local.ProvideContentColor
import org.koin.compose.koinInject

/**
 * 登录 Apple Music，拿 `media-user-token`（歌词取词链第 1 级的凭据）。
 *
 * ## 为什么是 WebView
 * Apple 的登录没有任何公开的"用账号密码换 token"接口，`media-user-token` 是**网页会话的
 * cookie** —— 只能让用户在 Apple 自己的页面上把账号密码 + 两步验证走完，然后把 cookie 读出来。
 * 所以这里开一个 `WebView` 加载 `music.apple.com`，**密码全程在 Apple 的页面里，App 不碰**。
 *
 * 拿到的东西有两样，这里都靠被动观察，不做任何注入式抓取：
 * - `media-user-token`：从 `CookieManager` 里扫出来的那个 cookie（登录完成后才出现）；
 * - 登录页本身：`music.apple.com` 落地页上有个 `.signin` 按钮，脚本替用户点掉，省一次手动操作。
 *
 * ⚠️ **第三方 cookie 必须显式打开**（`setAcceptThirdPartyCookies`），否则登录流程走完了
 * 但 cookie 读不出来 —— 这是这套方案最容易漏的一步。
 *
 * 退出时清掉 WebView 的 cookie / 缓存 / WebStorage：我们只要那份 token，
 * 没道理在 App 里再留一份"已登录的 Apple 网页会话"。
 */
@Composable
fun AppleMusicLoginScreen(
    onClose: () -> Unit,
    onLoggedIn: () -> Unit,
    repository: AppleMusicAccountRepository = koinInject(),
) {
    // 进度用裸 State 持有、交给 WavyLinearProgress 在绘制段读：写成 by 的话
    // 加载过程中每 1% 都会重组整棵树（连同 WebView 那一层）。
    val progress = remember { mutableFloatStateOf(0f) }
    var loaded by remember { mutableStateOf(false) }

    // WebView 的回调是在 factory 里一次性挂上的，捕获不到后续重组里的新 lambda，
    // 所以要用 rememberUpdatedState 让回调永远指向最新那个。
    val onToken by rememberUpdatedState<(String) -> Unit> { token ->
        repository.onTokenCaptured(token)
        onLoggedIn()
    }

    DisposableEffect(Unit) {
        onDispose {
            // 登录页已离开视野，留在 WebView 里的那份 Apple 会话不再需要
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
            WebStorage.getInstance().deleteAllData()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            PageHeader(
                title = "登录 Apple Music",
                subTitle = "只用来获取官方逐字歌词，与网易账号无关",
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // 窗口是 edge-to-edge 的，软键盘不会自己把内容顶上去；
                    // 不垫这一下，Apple 的账号密码框会被键盘盖住。
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                AndroidView(
                    factory = { context ->
                        createLoginWebView(
                            context = context,
                            onProgress = { progress.floatValue = it },
                            onLoaded = { loaded = true },
                            onToken = { onToken(it) },
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { view ->
                        view.stopLoading()
                        view.destroy()
                    },
                )

                if (!loaded) {
                    LoadingPanel(progress = { progress.floatValue })
                }
            }
        }

        CloseButton(onClick = onClose)
    }
}

/**
 * 建一个专门用来走 Apple 登录的 WebView。
 *
 * 回调都只在**还没拿到 token** 时干活，拿到后就静默了 —— `onLoadResource` 在页面上
 * 是高频回调，别让它每次都去问一遍 CookieManager（那是跨进程调用）。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createLoginWebView(
    context: Context,
    onProgress: (Float) -> Unit,
    onLoaded: () -> Unit,
    onToken: (String) -> Unit,
): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    // 🔴 缺了这行，登录走完也读不到 media-user-token
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onProgress(newProgress / 100f)
        }
    }

    webViewClient = object : WebViewClient() {

        /** 拿到就不再找，也就不用再碰 CookieManager 了（那是跨进程调用）。 */
        private var captured = false

        private fun captureFrom(url: String?) {
            if (captured || url == null) return
            val token = readMediaUserToken(url) ?: return
            captured = true
            onToken(token)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            onLoaded()
            // 落地页上的「登录」按钮替用户点掉。不在落地页时这个选择器不存在，脚本空转。
            view?.evaluateJavascript(ClickSignInScript, null)
            // 登录成功后页面会跳回 music.apple.com，那次 page finished 是最可能拿到 cookie 的时机
            captureFrom(url)
        }

        override fun onLoadResource(view: WebView?, url: String?) {
            captureFrom(url)
        }
    }

    loadUrl(StartUrl)
}

/**
 * 从 CookieManager 里抠 `media-user-token`。
 *
 * 这个 cookie 可能挂在 `music.apple.com` 也可能挂在 `idmsa.apple.com`，而 `url` 是当前
 * 正在加载的那个资源，未必就在它名下 —— 所以不看域名，直接把该 URL 可见的整条 Cookie
 * 头拿来扫。找不到返回 null（继续等下一次回调）。
 */
private fun readMediaUserToken(url: String): String? =
    CookieManager.getInstance()
        .getCookie(url)
        ?.split(';')
        ?.firstNotNullOfOrNull { part ->
            val trimmed = part.trim()
            if (!trimmed.startsWith(TokenPrefix)) return@firstNotNullOfOrNull null
            trimmed.removePrefix(TokenPrefix).takeIf { it.isNotEmpty() }
        }

@Composable
private fun LoadingPanel(progress: () -> Float) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.pageBackground),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WavyLinearProgress(
                progress = progress,
                modifier = Modifier.fillMaxWidth(0.5f),
            )
            VGap(16.dp)
            Text(
                text = "正在打开 Apple Music…",
                style = MaterialTheme.type.footnote,
                color = MaterialTheme.colors.contentVariant,
            )
        }
    }
}

@Composable
private fun BoxScope.CloseButton(onClick: () -> Unit) {
    ProvideContentColor(MaterialTheme.colors.onSecondaryContainer) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
                .clip(CircleShape)
                .size(48.dp)
                .background(MaterialTheme.colors.secondaryContainer)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.chevron_left_nav),
                tint = MaterialTheme.colors.onSecondaryContainer,
                contentDescription = "返回",
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private const val StartUrl = "https://music.apple.com"

private const val TokenPrefix = "media-user-token="

/**
 * 替用户点掉落地页上的登录按钮。
 *
 * 选择器不存在时什么也不做，所以在任何页面上跑都是安全的；返回 `true` 只是方便调试。
 */
private const val ClickSignInScript = """
(function () {
    var el = document.querySelector('.signin');
    if (el) {
        el.click();
        return true;
    }
    return false;
})();
"""
