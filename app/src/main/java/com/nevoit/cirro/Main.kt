package com.nevoit.cirro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import com.materialkolor.hct.Hct
import com.nevoit.cirro.data.AccountRepository
import com.nevoit.cirro.data.LibraryRepository
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.detail.ArtistRoute
import com.nevoit.cirro.detail.CollectionRoute
import com.nevoit.cirro.home.HomeRoute
import com.nevoit.cirro.library.LibraryRoute
import com.nevoit.cirro.library.LibraryLikedSongsRoute
import com.nevoit.cirro.library.LibraryPlaylistsRoute
import com.nevoit.cirro.library.LibraryArtistsRoute
import com.nevoit.cirro.library.LibraryAlbumsRoute
import com.nevoit.cirro.library.LibrarySongsRoute
import com.nevoit.cirro.login.AppleMusicLoginActivity
import com.nevoit.cirro.palette.CoverPalette
import com.nevoit.cirro.palette.CoverSeed
import com.nevoit.cirro.playback.MiniPlayer
import com.nevoit.cirro.playback.PlaybackConnection
import com.nevoit.cirro.playback.PlayerLayer
import com.nevoit.cirro.playback.PlayerUiController
import com.nevoit.cirro.ui.BottomShade
import com.nevoit.material.core.component.BottomPopupMenuHost
import com.nevoit.material.core.component.HGap
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.NavigationBar
import com.nevoit.material.core.component.NavigationBarItem
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.utility.rememberColorProducer
import com.nevoit.material.navigation.CrossfadeNavContainer
import com.nevoit.material.navigation.NavigationHost
import com.nevoit.material.navigation.NavigationHostHandle
import com.nevoit.material.navigation.rememberNavigator
import com.nevoit.material.navigation.shape.clipShape
import com.nevoit.material.navigation.shape.deviceCornerShape
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.local.ProvideContentColor
import com.nevoit.material.theme.materialColorsFromSeed
import com.nevoit.material.theme.rememberSystemSeedColor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

data class NavItem(
    val iconRes: Int,
    val label: String
)

enum class NavItems(val item: NavItem) {
    Home(NavItem(iconRes = R.drawable.home, label = "主页")),
    Explore(NavItem(iconRes = R.drawable.square_grid_2x2, label = "探索")),
    Library(NavItem(iconRes = R.drawable.music_note_square_stack, label = "资料库")),
    Search(NavItem(iconRes = R.drawable.magnifyingglass, label = "搜索")),
}

enum class MainDestinations {
    Home,
    Explore,
    Library,
    Search
}

// ---- 底栏 chrome 的固定 tone 档（封面种子的 primary 色带，HCT 明度 0–100）----
// 详情页在顶时底栏跟封面走：胶囊底 深色页 T98 / 浅色页 T94；指示器 深色页 T30 / 浅色页 T90。
// 图标跟自己坐的底拉开：激活图标在指示器上（深色页亮 T90 / 浅色页暗 T20），
// 未激活图标在胶囊上（深色页暗 T40 / 浅色页暗 T50）。标签页在顶时全跟主题。
private const val BarToneDark = 12

private const val BarToneLight = 98

private const val MenuActionToneDark = 20

private const val MenuActionToneLight = 95

private const val BarIndicatorToneDark = 20

private const val BarIndicatorToneLight = 90

private const val BarActiveIconToneDark = 90

private const val BarActiveIconToneLight = 30

private const val BarInactiveIconToneDark = 80

private const val BarInactiveIconToneLight = 40

// MiniPlayer 跟色时胶囊底与导航栏同色（BarTone*），文字/进度自己配档：
// 主文字 T10/T20、次级文字与进度条 T40/T50（都坐在胶囊上，跟底拉开）。
private const val MiniContentToneDark = 90

private const val MiniContentToneLight = 10

private const val MiniVariantToneDark = 60

private const val MiniVariantToneLight = 50

private const val MiniPrimaryToneDark = 80

private const val MiniPrimaryToneLight = 40

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

    @Serializable
    data object LibraryLikedSongs : MainDestination

    @Serializable
    data object LibraryPlaylists : MainDestination

    @Serializable
    data object LibraryArtists : MainDestination

    @Serializable
    data object LibraryAlbums : MainDestination

    @Serializable
    data object LibrarySongs : MainDestination

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
        val libraryAlbum: Boolean = false,
        val authorHint: String = "",
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
    val playerUi = koinInject<PlayerUiController>()
    val session by accountRepository.session.collectAsState()
    // 只订阅"到底有没有歌在放"这一位。订阅整个 state 会让 Main 跟着 500ms 的位置心跳重组，
    // 连带 NavigationHost、MiniPlayer 和它们底下的整棵树一起——播放位置该由真正需要它的
    // 那一小块自己去读（MiniPlayer / 播放页）。
    // `initial` 是首帧快照：StateFlow.value 本来就是零成本的当前值，这里要的是一个**值**不是一份
    // 订阅，所以刻意同步读一次。观察变化由紧随其后的 collectAsState 负责，不是漏订阅。
    @Suppress("StateFlowValueCalledInComposition")
    val hasSong by remember(playbackConnection) {
        playbackConnection.state.map { it.song != null }.distinctUntilChanged()
    }.collectAsState(initial = playbackConnection.state.value.song != null)

    // 底栏 chrome：详情页（Collection）在导航顶上时跟封面种子走，否则跟主题。
    // seed 用 produceState 异步取——缓存命中第一帧就有（首页预取过）；没命中则取完自动
    // 刷新底栏，顺带给详情页暖了取色缓存。navigator.current 是 snapshot state，
    // 进出详情页这里自动重算。
    val palette = koinInject<CoverPalette>()
    // 🔴 「喜爱歌曲」是资料库的子页：歌单详情在它**内部**渲染，不走 MainDestination.Collection，
    // 所以拿不到入口处的 coverHint，底栏就会掉回主题色（页面自己有颜色，底栏没有）。
    // 这里直接盯资料库快照里第一首歌的封面 —— 跟详情页内部用的是同一个来源，资料库加载完底栏自己会跟上。
    // 只订阅这一张图：喜欢状态之类的其它快照变化不会惊动底栏。
    val libraryRepository = koinInject<LibraryRepository>()
    val likedCover by produceState<String?>(initialValue = null) {
        libraryRepository.snapshot
            .map { it?.likedSongs?.firstOrNull()?.cover }
            .distinctUntilChanged()
            .collect { value = it }
    }
    val artistPageSeeds = remember { mutableStateMapOf<Long, CoverSeed?>() }
    val currentEntry = navigator.current
    val coverHint = when (val destination = currentEntry.destination) {
        is MainDestination.Collection -> destination.coverHint
        MainDestination.LibraryLikedSongs -> likedCover
        else -> null
    }
    val hintedBarSeed by produceState<CoverSeed?>(
        initialValue = coverHint?.let { palette.cached(it) },
        coverHint
    ) {
        value = coverHint?.let { palette.seed(it) }
    }
    // 常驻 chrome 在导航树外，需要跟艺人页独立的深色主题同步。
    val artistPage = (currentEntry.destination as? MainDestination.Collection)?.kind == CollectionKind.ARTIST
    // null 也表示页内的实际状态，不能重新拿入口封面的旧颜色覆盖它。
    val barSeed = if (artistPage && artistPageSeeds.containsKey(currentEntry.id)) {
        artistPageSeeds[currentEntry.id]
    } else hintedBarSeed
    val barDark = artistPage || barSeed?.dark == true || MaterialTheme.darkTheme
    val barColors = if (artistPage && !MaterialTheme.darkTheme) {
        val seedColor = rememberSystemSeedColor()
        remember(seedColor) { materialColorsFromSeed(seedColor, isDark = true) }
    } else MaterialTheme.colors

    fun barTone(darkTone: Int, lightTone: Int): Color? =
        barSeed?.primaryTone(if (barDark) darkTone else lightTone)?.let(::Color)

    // 这几个颜色都是动画态（进出详情页 400ms 过渡）。这里刻意不用 by 读出值——一旦在组合期
    // 读，Main 每帧重组一次，连带 MiniPlayer、底栏和它们底下的整棵树。留着 State，下面用
    // rememberColorProducer 包成 producer 传下去，读取发生在各自的 draw 阶段。
    val barContainerColor = animateColorAsState(
        targetValue = barTone(BarToneDark, BarToneLight)
            ?: barColors.cardBackground,
        animationSpec = tween(durationMillis = 400),
        label = "barContainer",
    )
    val menuActionTone = if (barDark) MenuActionToneDark else MenuActionToneLight
    val themePrimary = barColors.primary
    val menuActionFallback = remember(themePrimary, menuActionTone) {
        val hct = Hct.fromInt(themePrimary.toArgb())
        Color(Hct.from(hct.hue, hct.chroma, menuActionTone.toDouble()).toInt())
    }
    val menuActionContainerColor = animateColorAsState(
        targetValue = barTone(MenuActionToneDark, MenuActionToneLight) ?: menuActionFallback,
        animationSpec = tween(durationMillis = 400),
        label = "menuActionContainer",
    )
    val barIndicatorColor = animateColorAsState(
        targetValue = barTone(BarIndicatorToneDark, BarIndicatorToneLight)
            ?: barColors.navigationBarIndicator,
        animationSpec = tween(durationMillis = 400),
        label = "barIndicator",
    )
    val barActiveIconColor = animateColorAsState(
        targetValue = barTone(BarActiveIconToneDark, BarActiveIconToneLight)
            ?: barColors.onNavigationBarIndicator,
        animationSpec = tween(durationMillis = 400),
        label = "barActiveIcon",
    )
    val barInactiveIconColor = animateColorAsState(
        targetValue = barTone(BarInactiveIconToneDark, BarInactiveIconToneLight)
            ?: barColors.navigationBarInactiveIcon,
        animationSpec = tween(durationMillis = 400),
        label = "barInactiveIcon",
    )

    val miniContentColor = animateColorAsState(
        targetValue = barTone(MiniContentToneDark, MiniContentToneLight)
            ?: barColors.content,
        animationSpec = tween(durationMillis = 400),
        label = "miniContent",
    )
    val miniVariantColor = animateColorAsState(
        targetValue = barTone(MiniVariantToneDark, MiniVariantToneLight)
            ?: barColors.contentVariant,
        animationSpec = tween(durationMillis = 400),
        label = "miniVariant",
    )
    val miniPrimaryColor = animateColorAsState(
        targetValue = barTone(MiniPrimaryToneDark, MiniPrimaryToneLight)
            ?: barColors.primary,
        animationSpec = tween(durationMillis = 400),
        label = "miniPrimary",
    )

    val navigationPageShape = deviceCornerShape()
    RequestNotificationPermissionOnce()

    BottomPopupMenuHost(
        modifier = Modifier.fillMaxSize(),
        containerColor = rememberColorProducer(barContainerColor),
        contentColor = rememberColorProducer(miniContentColor),
        contentVariantColor = rememberColorProducer(miniVariantColor),
        actionContainerColor = rememberColorProducer(menuActionContainerColor),
    ) {
        NavigationHost(
            navigator = navigator,
            onHostReady = { hostHandle = it },
            transitionModifier = { page, isTransitioning ->
                page
                    .graphicsLayer {
                        compositingStrategy = if (isTransitioning) {
                            CompositingStrategy.Offscreen
                        } else {
                            CompositingStrategy.Auto
                        }
                    }
                    .clipShape(
                        shape = navigationPageShape,
                        enabled = isTransitioning,
                    )
            }
        ) {
            when (val destination = destination) {
                MainDestination.Tabs -> Box(modifier = Modifier.fillMaxSize()) {
                    MainTabs(
                        session = session,
                        selectedIndex = selectedIndex,
                        onSelect = { selectedIndex = it },
                        onOpenCollection = { kind, id, titleHint, coverHint, authorHint ->
                            navigate(MainDestination.Collection(kind, id, titleHint, coverHint, authorHint = authorHint))
                        },
                        onOpenLibraryAlbum = { id, title, cover, author ->
                            navigate(MainDestination.Collection(CollectionKind.ALBUM, id, title, cover, libraryAlbum = true, authorHint = author))
                        },
                        onOpenLibraryLikedSongs = { navigate(MainDestination.LibraryLikedSongs) },
                        onOpenLibraryPlaylists = { navigate(MainDestination.LibraryPlaylists) },
                        onOpenLibraryArtists = { navigate(MainDestination.LibraryArtists) },
                        onOpenLibraryAlbums = { navigate(MainDestination.LibraryAlbums) },
                        onOpenLibrarySongs = { navigate(MainDestination.LibrarySongs) },
                    )
                }

                MainDestination.LibraryLikedSongs -> LibraryLikedSongsRoute(
                    session = session,
                    onBack = ::back,
                    onOpenCollection = { kind, id, titleHint, coverHint, authorHint ->
                        navigate(MainDestination.Collection(kind, id, titleHint, coverHint, authorHint = authorHint))
                    },
                )

                MainDestination.LibraryPlaylists -> LibraryPlaylistsRoute(
                    session = session,
                    onBack = ::back,
                    onOpenCollection = { kind, id, titleHint, coverHint, authorHint ->
                        navigate(MainDestination.Collection(kind, id, titleHint, coverHint, authorHint = authorHint))
                    },
                )

                MainDestination.LibraryArtists -> LibraryArtistsRoute(
                    session = session,
                    onBack = ::back,
                    onOpenCollection = { kind, id, titleHint, coverHint, authorHint ->
                        navigate(MainDestination.Collection(kind, id, titleHint, coverHint, authorHint = authorHint))
                    },
                )

                MainDestination.LibraryAlbums -> LibraryAlbumsRoute(
                    session = session,
                    onBack = ::back,
                    onOpenLibraryAlbum = { id, title, cover, author ->
                        navigate(MainDestination.Collection(CollectionKind.ALBUM, id, title, cover, libraryAlbum = true, authorHint = author))
                    },
                )

                MainDestination.LibrarySongs -> LibrarySongsRoute(
                    session = session,
                    onBack = ::back,
                    onOpenCollection = { kind, id, titleHint, coverHint, authorHint ->
                        navigate(MainDestination.Collection(kind, id, titleHint, coverHint, authorHint = authorHint))
                    },
                )

                is MainDestination.Collection -> {
                    if (destination.kind == CollectionKind.ARTIST) {
                        val pageId = entry.id
                        // 退出动画中仍可能复用原页面，颜色记录须随页面实际销毁再释放。
                        DisposableEffect(pageId) {
                            onDispose { artistPageSeeds.remove(pageId) }
                        }
                        ArtistRoute(
                            kind = destination.kind,
                            id = destination.id,
                            titleHint = destination.titleHint,
                            coverHint = destination.coverHint,
                            authorHint = destination.authorHint,
                            onBack = ::back,
                            onOpenCollection = { kind, id, titleHint, coverHint, authorHint ->
                                navigate(MainDestination.Collection(kind, id, titleHint, coverHint, authorHint = authorHint))
                            },
                            onPaletteChanged = { seed ->
                                artistPageSeeds[pageId] = seed
                            },
                        )
                    } else {
                        CollectionRoute(
                            kind = destination.kind,
                            id = destination.id,
                            titleHint = destination.titleHint,
                            coverHint = destination.coverHint,
                            authorHint = destination.authorHint,
                            libraryAlbum = destination.libraryAlbum,
                            onBack = ::back,
                            onOpenCollection = { kind, id, titleHint, coverHint, authorHint ->
                                navigate(MainDestination.Collection(kind, id, titleHint, coverHint, authorHint = authorHint))
                            },
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp)
                .fillMaxWidth()
                // 播放页铺开时整块 chrome 往下沉并淡出。位移在 layer 阶段算，Main 不参与
                .chromeExit { playerUi.progress },
        ) {
            if (hasSong) {
                MiniPlayer(
                    stateFlow = playbackConnection.state,
                    onClick = { playerUi.open() },
                    onTogglePlay = { playbackConnection.togglePlayPause() },
                    onSkipNext = { playbackConnection.skipToNext() },
                    containerColor = rememberColorProducer(barContainerColor),
                    contentColor = rememberColorProducer(miniContentColor),
                    contentVariantColor = rememberColorProducer(miniVariantColor),
                    progressColor = rememberColorProducer(miniPrimaryColor),
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
                containerColor = rememberColorProducer(barContainerColor),
                indicatorColor = rememberColorProducer(barIndicatorColor),
                activeIconColor = rememberColorProducer(barActiveIconColor),
                inactiveIconColor = rememberColorProducer(barInactiveIconColor),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 播放页盖在最上层：导航、迷你条、底栏全在它下面。它整套位置自己算，
        // 和上面 chrome 的退场共用一个进度值，所以两边永远同步。
        PlayerLayer(
            controller = playerUi,
            connection = playbackConnection,
        )
    }
}

/**
 * 常驻 chrome 在播放页展开时的退场：整块往下沉，同时淡出。
 *
 * 进度是每帧都在变的动画态，所以位移和透明**只在 graphicsLayer 的 block 里读**。
 * 放进组合期读的话，Main 会跟着手指（和弹簧）每帧重组一次，连带它底下所有页面。
 */
private fun Modifier.chromeExit(progress: () -> Float): Modifier = graphicsLayer {
    val value = progress()
    translationY = value * size.height
    alpha = 1f - value
}

/** 导航根页：底部标签页 + 顶栏。MiniPlayer 和底栏不在这儿——它们常驻在导航之外。 */
@Composable
private fun BoxScope.MainTabs(
    session: Session,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onOpenCollection: (CollectionKind, Long, String, String, String) -> Unit,
    onOpenLibraryAlbum: (Long, String, String, String) -> Unit,
    onOpenLibraryLikedSongs: () -> Unit,
    onOpenLibraryPlaylists: () -> Unit,
    onOpenLibraryArtists: () -> Unit,
    onOpenLibraryAlbums: () -> Unit,
    onOpenLibrarySongs: () -> Unit,
) {
    val context = LocalContext.current

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
            MainDestinations.Library -> LibraryRoute(
                session = session,
                onOpenLibraryAlbum = onOpenLibraryAlbum,
                onOpenLikedSongs = onOpenLibraryLikedSongs,
                onOpenPlaylists = onOpenLibraryPlaylists,
                onOpenArtists = onOpenLibraryArtists,
                onOpenAlbums = onOpenLibraryAlbums,
                onOpenSongs = onOpenLibrarySongs,
            )
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
                        .clickable {
                            context.startActivity(
                                Intent(context, AppleMusicLoginActivity::class.java)
                            )
                        },
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
