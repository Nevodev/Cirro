package com.nevoit.cirro.login

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nevoit.material.core.component.Surface
import com.nevoit.material.theme.MaterialTheme

/**
 * Apple Music 登录页的宿主。
 *
 * 做成独立 Activity（跟网易的 [LoginActivity] 一个路子）而不是导航里的一页：登录要靠
 * 一个全屏 WebView，而 `Main` 的底栏 / 迷你条 / 播放页是**挂在导航之外**的三层 chrome，
 * 走导航的话它们会浮在登录页上面。
 */
class AppleMusicLoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Surface 的 content 是 BoxScope.() -> Unit，直接写 this 会拿到 BoxScope 而不是
            // Context。按项目惯例取 LocalContext（这里就是本 Activity）。
            val context = LocalContext.current
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppleMusicLoginScreen(
                        onClose = { finish() },
                        onLoggedIn = {
                            Toast.makeText(context, "已登录 Apple Music", Toast.LENGTH_SHORT).show()
                            finish()
                        },
                    )
                }
            }
        }
    }
}
