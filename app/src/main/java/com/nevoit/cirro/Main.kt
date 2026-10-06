package com.nevoit.cirro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import com.nevoit.cirro.data.AccountRepository
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.detail.CollectionRoute
import com.nevoit.cirro.home.HomeRoute
import com.nevoit.cirro.playback.MiniPlayer
import com.nevoit.cirro.playback.PlaybackConnection
import com.nevoit.cirro.ui.BottomShade
import com.nevoit.material.core.component.HGap
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.NavigationBar
import com.nevoit.material.core.component.NavigationBarItem
import com.nevoit.material.core.component.VGap
import com.nevoit.material.navigation.CrossfadeNavContainer
import com.nevoit.material.navigation.NavigationHost
import com.nevoit.material.navigation.NavigationHostHandle
import com.nevoit.material.navigation.rememberNavigator
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.local.ProvideContentColor
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

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

/**
 * 导航目的地：默认页是底部标签页，往上推统一详情页。
 *
 * 必须可序列化——导航栈要用 JSON 存下来过进程重建（见 [rememberNavigator]）。
 */
@Serializable
sealed interface MainDestination {

    /** 标签页（主页/探索/资源库/搜索）+ 顶栏，整份就是导航的根页。 */
    @Serializable
    data object Tabs : MainDestination

    /**
     * 统一详情页（歌单 / 专辑 / 每日推荐）。
     *
     * [titleHint] 是入口卡上**已经清洗过**的显示名——雷达歌单接口里的名字是
     * 「推荐语|雷达名」拼死的一串，直接拿来当详情页标题会带脏东西，所以让首页把
     * 干净的名字捎过来；没有 hint 就退回详情接口的原始标题。
     */
    @Serializable
    data class Collection(
        val kind: CollectionKind,
        val id: Long,
        val titleHint: String = "",
        // 入口处的封面串：取色缓存按整串 URL 做 key，用它才保证第一帧命中
        val coverHint: String = "",
    ) : MainDestination
}

@Composable
fun BoxScope.Main() {
    val navigator = rememberNavigator<MainDestination>(MainDestination.Tabs)
    var hostHandle by remember { mutableStateOf<NavigationHostHandle?>(null) }
    // 标签选中状态架在导航外面：底栏（导航外、常驻）和标签页（根页内）都要用它
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val accountRepository = koinInject<AccountRepository>()
    val playbackConnection = koinInject<PlaybackConnection>()
    val session by accountRepository.session.collectAsState()
    val playback by playbackConnection.state.collectAsState()

    RequestNotificationPermissionOnce()

    Box(modifier = Modifier.fillMaxSize()) {
        NavigationHost(
            navigator = navigator,
            onHostReady = { hostHandle = it },
        ) {
            when (val destination = destination) {
                MainDestination.Tabs -> Box(modifier = Modifier.fillMaxSize()) {
                    MainTabs(
                        session = session,
                        selectedIndex = selectedIndex,
                        onSelect = { selectedIndex = it },
                        onOpenCollection = { kind, id, titleHint, coverHint ->
                            navigate(MainDestination.Collection(kind, id, titleHint, coverHint))
                        },
                    )
                }

                is MainDestination.Collection -> CollectionRoute(
                    kind = destination.kind,
                    id = destination.id,
                    titleHint = destination.titleHint,
                    coverHint = destination.coverHint,
                    onBack = ::back,
                )
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
                onSelect = { index ->
                    selectedIndex = index
                    // 详情页还盖在上面时点标签：切标签的同时把详情页收掉
                    hostHandle?.closeToRoot()
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 导航根页：底部标签页 + 顶栏。MiniPlayer 和底栏不在这儿——它们常驻在导航之外。 */
@Composable
private fun BoxScope.MainTabs(
    session: Session,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onOpenCollection: (CollectionKind, Long, String, String) -> Unit,
) {
    CrossfadeNavContainer(
        destination = MainDestinations.entries[selectedIndex],
        destinations = MainDestinations.entries
    ) { destination ->
        when (destination) {
            MainDestinations.Home -> HomeRoute(
                session = session,
                onOpenCollection = onOpenCollection,
            )

            MainDestinations.Explore -> {}
            MainDestinations.Library -> {}
            MainDestinations.Search -> {}
        }
    }

    // 底部 shade 归页面自己画：标签页用主题底色，详情页进来换封面色
    BottomShade(color = MaterialTheme.colors.pageBackground)

    // top bar
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

@Composable
private fun RequestNotificationPermissionOnce() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    LaunchedEffect(Unit) {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED

        if (needsPermission) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
