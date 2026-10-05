package com.nevoit.cirro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import com.nevoit.cirro.data.AccountRepository
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.login.LoginActivity
import com.nevoit.cirro.playback.MiniPlayer
import com.nevoit.cirro.playback.PlaybackConnection
import com.nevoit.material.core.component.HGap
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.NavigationBar
import com.nevoit.material.core.component.NavigationBarItem
import com.nevoit.material.core.component.OutlinedButton
import com.nevoit.material.core.component.PageHeader
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.layout.VerticalStack
import com.nevoit.material.navigation.CrossfadeNavContainer
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.local.ProvideContentColor
import org.koin.compose.koinInject

// TODO(临时): 内容入口还没做，先内置一首歌把播放链路跑通。做搜索/每日推荐时连同 Home 里那个按钮一起删。
private const val BuiltInSongId = 3412169601L

data class NavItem(
    val iconRes: Int,
    val label: String
)

enum class NavItems(val item: NavItem) {
    Home(NavItem(iconRes = R.drawable.home, label = "主页")),
    Explore(NavItem(iconRes = R.drawable.square_grid_2x2, label = "探索")),
    Library(NavItem(iconRes = R.drawable.music_note_square_stack, label = "资源库")),
    Search(NavItem(iconRes = R.drawable.magnifyingglass, label = "搜索")),
}

enum class MainDestinations {
    Home,
    Explore,
    Library,
    Search
}

@Composable
fun BoxScope.Main() {
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val accountRepository = koinInject<AccountRepository>()
    val playbackConnection = koinInject<PlaybackConnection>()
    val session by accountRepository.session.collectAsState()
    val playback by playbackConnection.state.collectAsState()

    val playBuiltIn = rememberNotificationAwarePlay { playbackConnection.playSong(BuiltInSongId) }

    Box(modifier = Modifier.fillMaxSize()) {
        CrossfadeNavContainer(
            destination = MainDestinations.entries[selectedIndex],
            destinations = MainDestinations.entries
        ) { destination ->
            when (destination) {
                MainDestinations.Home -> Home(session, playBuiltIn)
                MainDestinations.Explore -> {}
                MainDestinations.Library -> {}
                MainDestinations.Search -> {}
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp)
                .fillMaxWidth(),
        ) {
            if (playback.song != null) {
                MiniPlayer(
                    state = playback,
                    onTogglePlay = { playbackConnection.togglePlayPause() },
                    modifier = Modifier
                        .fillMaxWidth(),
                )
                VGap()
            }
            NavigationBar(
                items = NavItems.entries.map { entry ->
                    val icon = painterResource(entry.item.iconRes)
                    NavigationBarItem(
                        icon = icon,
                        label = entry.item.label
                    )
                },
                selectedIndex = selectedIndex,
                onSelect = { selectedIndex = it },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(12.dp)
                .fillMaxWidth()
                .height(48.dp)
        ) {
            ProvideContentColor(MaterialTheme.colors.onSecondaryContainer) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .size(48.dp)
                            .background(MaterialTheme.colors.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        val avatarUrl = (session as? Session.LoggedIn)?.profile?.avatarUrl
                        if (avatarUrl.isNullOrEmpty()) {
                            Icon(
                                painter = painterResource(R.drawable.person),
                                tint = MaterialTheme.colors.onSecondaryContainer,
                                contentDescription = "我的"
                            )
                        } else {
                            AsyncImage(
                                model = avatarUrl,
                                contentDescription = "我的",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                    HGap()
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .size(48.dp)
                            .background(MaterialTheme.colors.secondaryContainer)
                            .clickable {},
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ellipsis),
                            contentDescription = "更多"
                        )
                    }
                }
            }
        }
    }
}

/**
 * 播之前先确保通知权限。
 *
 * Android 13 起没有 POST_NOTIFICATIONS，前台服务的通知不会显示——音乐照放，但状态栏上
 * 空的，看起来很像"播放坏了"。被拒绝也照播，不拿权限去卡播放本身。
 */
@Composable
private fun rememberNotificationAwarePlay(onPlay: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onPlay() }

    return {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED

        if (needsPermission) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onPlay()
        }
    }
}

@Composable
fun Home(session: Session, onPlayBuiltIn: () -> Unit) {
    VerticalStack(modifier = Modifier.fillMaxSize()) {
        item {
            PageHeader(
                title = NavItems.Home.item.label,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
        // 已登录、登录态还没查出来、或只是离线没验证成时，都不该劝人去登录
        if (session is Session.LoggedOut) {
            item {
                LoginCard(modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
        item {
            OutlinedButton(
                onClick = onPlayBuiltIn,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Text("播放内置歌曲（临时入口）")
            }
        }
    }
}

@Composable
fun LoginCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .clip(MaterialTheme.specs.cardShape)
            .aspectRatio(256f / 160f)
            .fillMaxWidth()
            .clickable {
                context.startActivity(Intent(context, LoginActivity::class.java))
            }
    ) {
        Image(
            painter = painterResource(R.drawable.cirro_header),
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
        )
        Text(
            text = "登录网易云账号以开始使用",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .graphicsLayer {
                    blendMode = BlendMode.Plus
                    alpha = 0.8f
                },
            style = MaterialTheme.type.subHeadline,
            color = Color.White,
        )
    }
}
