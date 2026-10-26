package com.nevoit.cirro.detail

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Precision
import com.nevoit.cirro.R
import com.nevoit.cirro.data.AccountRepository
import com.nevoit.cirro.data.AppleArtistRepository
import com.nevoit.cirro.data.LibraryRepository
import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.QueueSource
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.albumDisplayTitle
import com.nevoit.cirro.data.model.forSession
import com.nevoit.cirro.data.model.hasPendingInserts
import com.nevoit.cirro.data.model.likedPlaylistInfo
import com.nevoit.cirro.data.model.tracksForAlbum
import com.nevoit.cirro.home.OpenCollection
import com.nevoit.cirro.home.Section
import com.nevoit.cirro.home.dataOrNull
import com.nevoit.cirro.lyrics.data.AppleArtistHeader
import com.nevoit.cirro.lyrics.data.AppleArtistNameLogo
import com.nevoit.cirro.palette.CoverPalette
import com.nevoit.cirro.palette.CoverSeed
import com.nevoit.cirro.playback.PlaybackConnection
import com.nevoit.cirro.ui.BottomShade
import com.nevoit.cirro.ui.ReflectionTransformation
import com.nevoit.cirro.ui.SongMenuContent
import com.nevoit.cirro.ui.SongMenuViewModel
import com.nevoit.cirro.ui.TopShade
import com.nevoit.cirro.ui.TrackRow
import com.nevoit.cirro.ui.resolvePosition
import com.nevoit.cirro.ui.video.ArtistHeaderVideo
import com.nevoit.material.core.animation.animatePremultipliedColorAsState
import com.nevoit.material.core.component.BottomPopupMenuScope
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.layout.LocalOverscrollVisualOffset
import com.nevoit.material.core.layout.VerticalStack
import com.nevoit.material.core.layout.isScrolledPast
import com.nevoit.material.core.utility.rememberColorProducer
import com.nevoit.material.navigation.LocalPageTransitionState
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.local.ProvideContentColor
import com.nevoit.material.theme.tokens.Springs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

private val HeaderLogoMaxHeight = 80.dp

private val ArtistHeaderSaver = Saver<MutableState<AppleArtistHeader?>, String>(
    save = { Json.encodeToString(it.value) },
    restore = { mutableStateOf(Json.decodeFromString<AppleArtistHeader?>(it)) },
)

/** 艺人页「歌曲排行」摆几首。 */
private const val ArtistRankingSize = 5

/**
 * 艺人页。
 *
 * 界面不知道也不关心内容从哪来——[CollectionViewModel] 已经把来源折成同一份形状。
 * 顶部那排圆钮浮在内容上：左返回、右更多和分享；底部 MiniPlayer 和导航栏常驻在
 * 导航宿主之外（见 Main），列表自己留够尾距。
 */
@Composable
fun ArtistRoute(
    kind: CollectionKind,
    id: Long,
    titleHint: String,
    coverHint: String,
    onBack: () -> Unit,
    onOpenCollection: OpenCollection,
    onOpenShelf: (section: ArtistShelfSection, artistName: String) -> Unit,
    modifier: Modifier = Modifier,
    libraryAlbum: Boolean = false,
    likedPlaylist: Boolean = false,
    authorHint: String = "",
    onPaletteChanged: (CoverSeed?) -> Unit = {},
) = MaterialTheme(
    darkTheme = true,
    specs = MaterialTheme.specs,
    type = MaterialTheme.type,
) {
    val viewModel: CollectionViewModel = koinViewModel {
        parametersOf(kind, id, titleHint)
    }
    val appleArtists = koinInject<AppleArtistRepository>()
    val pageTransition = LocalPageTransitionState.current
    var artistHeader by rememberSaveable(id, saver = ArtistHeaderSaver) {
        mutableStateOf<AppleArtistHeader?>(null)
    }
    var showTitleWhileLoading by rememberSaveable(id) { mutableStateOf(false) }
    LaunchedEffect(viewModel, appleArtists, pageTransition) {
        if (artistHeader != null) return@LaunchedEffect
        if (kind != CollectionKind.ARTIST) {
            showTitleWhileLoading = true
            return@LaunchedEffect
        }
        val cachedMatch = appleArtists.cached(id)
        showTitleWhileLoading = cachedMatch == null
        val match = cachedMatch ?: run {
            val loaded =
                viewModel.state.first { it.info is Section.Ready && it.songs is Section.Ready }
            appleArtists.resolve(
                id,
                loaded.info.dataOrNull?.title.orEmpty(),
                loaded.songs.dataOrNull.orEmpty()
            )
        }
        if (match != null) {
            val header = appleArtists.header(match)
            pageTransition?.awaitNearlySettled()
            artistHeader = header
            if (header?.nameLogo == null) showTitleWhileLoading = true
        }
    }
    val playback = koinInject<PlaybackConnection>()
    val songMenu: SongMenuViewModel = koinViewModel()
    val remoteState by viewModel.state.collectAsStateWithLifecycle()
    val libraryRepository = koinInject<LibraryRepository>()
    val library by libraryRepository.snapshot.collectAsStateWithLifecycle()
    val libraryError by libraryRepository.error.collectAsStateWithLifecycle()
    val session by koinInject<AccountRepository>().session.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tracksRevealed by rememberSaveable(kind, id) { mutableStateOf(false) }
    var showFullAlbum by rememberSaveable(kind, id) { mutableStateOf(false) }
    val state = remember(
        remoteState,
        library,
        libraryError,
        session,
        libraryAlbum,
        likedPlaylist,
        id,
        tracksRevealed,
        showFullAlbum,
    ) {
        val liked =
            library?.forSession(session)?.takeIf { likedPlaylist && it.likedPlaylistId == id }
        if (liked != null) {
            remoteState.copy(
                info = Section.Ready(
                    liked.likedPlaylistInfo(
                        (session as? Session.LoggedIn)?.profile?.nickname.orEmpty(),
                        remoteState.info.dataOrNull,
                    )
                ),
                songs = when {
                    !liked.likesLoaded -> remoteState.songs
                    tracksRevealed -> Section.Ready(liked.likedSongs)
                    else -> Section.Loading
                },
            )
        } else if (libraryAlbum && !showFullAlbum && remoteState.songs is Section.Ready) {
            val uid = (session as? Session.LoggedIn)?.profile?.userId
            val snapshot = library?.takeIf { it.userId == uid }
            when {
                snapshot?.albums?.any { it.info.id == id } == true -> remoteState
                snapshot?.likesLoaded == true -> remoteState.copy(
                    songs = Section.Ready(
                        snapshot.tracksForAlbum(
                            id,
                            remoteState.songs.dataOrNull.orEmpty()
                        )
                    ),
                )

                libraryError != null -> remoteState.copy(songs = Section.Failed(libraryError.orEmpty()))
                else -> remoteState.copy(songs = Section.Loading)
            }
        } else remoteState
    }
    val albumTrackNumbers = remember(libraryAlbum, kind, remoteState.songs) {
        if (libraryAlbum && kind == CollectionKind.ALBUM) {
            remoteState.songs.dataOrNull.orEmpty()
                .mapIndexed { index, song -> song.id to index + 1 }
                .toMap()
        } else emptyMap()
    }
    val incompleteAlbum = libraryAlbum && kind == CollectionKind.ALBUM && !showFullAlbum &&
            (state.songs !is Section.Ready ||
                    state.songs.dataOrNull?.size != remoteState.songs.dataOrNull?.size)
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(songMenu) {
        songMenu.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // 曲目请求已经由 ViewModel **和进场动画同时发出**了，这里只负责在动画**看着到位**之后把它亮出来：
    // 一整屏列表（外加每行的封面请求）在滑动过程中落地，会把导航那条 350ms 的位移弹簧顶掉帧。
    // 不等弹簧真正 settle —— 最后 5% 的位移只有几 px、速度已接近 0，肉眼早读作"到位"了，
    // 卡在这一小段后面白等（约 0.05 × 宿主宽度 px，1080px 宽上就是 54px）。
    // 头部不等——标题和底色本来就是进场动画的一部分。
    // 拿不到导航状态（预览/独立测试）就直接亮，别把内容卡住。
    LaunchedEffect(viewModel, pageTransition) {
        pageTransition?.awaitNearlySettled()
        viewModel.revealTracks()
        tracksRevealed = true
    }

    val songs = state.songs.dataOrNull.orEmpty()

    val playingId by remember(playback) {
        playback.state.map { it.song?.id }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = remember { playback.state.value.song?.id })
    val queueActions by remember(playback) {
        playback.queue.map { it.entries.isNotEmpty() to it.hasPendingInserts }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false to false)
    // 这一页的出身：随歌一起交给队列，打卡时回传 sourceId（每日推荐没有服务端集合，QueueSource 会留空）
    val queueSource = QueueSource(kind, id.toString(), title = viewModel.displayTitle)

    var coverHintFailed by remember(coverHint) { mutableStateOf(false) }
    val coverUrl = coverHint.takeUnless { coverHintFailed }.orEmpty()
        .ifEmpty { state.info.dataOrNull?.cover.orEmpty() }
    val palette = koinInject<CoverPalette>()
    val paletteKey = coverUrl
    var seed by remember(paletteKey) { mutableStateOf(palette.cached(paletteKey)) }
    LaunchedEffect(paletteKey) {
        if (paletteKey.isEmpty()) return@LaunchedEffect
        palette.prefetch(paletteKey)
        seed = palette.seed(paletteKey)
    }
    SideEffect {
        onPaletteChanged(seed)
    }

    ArtistScreen(
        state = state,
        artist = kind == CollectionKind.ARTIST,
        revealed = tracksRevealed,
        videoUrl = artistHeader?.videoUrl,
        nameLogo = artistHeader?.nameLogo,
        showTitleWhileLoading = showTitleWhileLoading,
        likedPlaylist = likedPlaylist,
        incompleteAlbum = incompleteAlbum,
        onShowFullAlbum = { showFullAlbum = true },
        albumTrackNumbers = albumTrackNumbers,
        displayTitle = viewModel.displayTitle,
        authorHint = authorHint,
        coverUrl = coverUrl,
        onCoverError = { coverHintFailed = true },
        playingId = playingId,
        seed = seed,
        onPlay = { index -> if (songs.isNotEmpty()) playback.playSongs(songs, index, queueSource) },
        onOpenAlbum = { album ->
            onOpenCollection(
                CollectionKind.ALBUM,
                album.id,
                albumDisplayTitle(album.title, album.albumType),
                album.cover,
                album.author
            )
        },
        onRetryMoreWorks = viewModel::loadMoreWorks,
        onRetrySimilarSongs = viewModel::loadSimilarSongs,
        onOpenShelf = onOpenShelf,
        songMenuContent = { song ->
            SongMenuContent(
                song = song,
                canInsertQueue = queueActions.first,
                hasInsertQueue = queueActions.second,
                viewModel = songMenu,
                onPlay = {
                    playback.playSongs(
                        songs,
                        songs.indexOfFirst { it.id == song.id },
                        queueSource
                    )
                },
                onAddNext = { playback.addNext(song, queueSource) },
                onAddToInsertQueue = { playback.addToInsertQueue(song, queueSource) },
                onOpenAlbum = { metadata ->
                    onOpenCollection(
                        CollectionKind.ALBUM,
                        metadata.albumId,
                        metadata.album,
                        metadata.cover,
                        "",
                    )
                },
                onOpenArtist = { artistId, name ->
                    onOpenCollection(
                        CollectionKind.ARTIST,
                        artistId,
                        name,
                        "",
                        "热门歌曲",
                    )
                },
                onShare = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "${song.name} - ${song.artistText}\nhttps://music.163.com/song?id=${song.id}"
                        )
                    }
                    context.startActivity(Intent.createChooser(intent, "分享歌曲"))
                },
            )
        },
        onToggleSubscribed = viewModel::toggleSubscribed,
        onInfo = {},
        onRetry = {
            viewModel.load()
            if (libraryAlbum || likedPlaylist) scope.launch { libraryRepository.refresh() }
        },
        onBack = onBack,
        onShare = {
            val link = when (kind) {
                CollectionKind.PLAYLIST -> "https://music.163.com/playlist?id=$id"
                CollectionKind.ALBUM -> "https://music.163.com/album?id=$id"
                CollectionKind.DAILY -> "https://music.163.com/"
                CollectionKind.ARTIST -> "https://music.163.com/artist?id=$id"
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "${viewModel.displayTitle}\n$link")
            }
            context.startActivity(Intent.createChooser(intent, "分享"))
        },
        modifier = modifier,
    )
}

@Composable
private fun ArtistScreen(
    state: CollectionViewModel.UiState,
    artist: Boolean,
    /** 进场动画已经「看着到位」，跟曲目一起放行（见 `revealTracks`）。 */
    revealed: Boolean,
    videoUrl: String?,
    nameLogo: AppleArtistNameLogo?,
    showTitleWhileLoading: Boolean,
    likedPlaylist: Boolean,
    incompleteAlbum: Boolean,
    onShowFullAlbum: () -> Unit,
    albumTrackNumbers: Map<Long, Int>,
    displayTitle: String,
    authorHint: String,
    coverUrl: String,
    onCoverError: () -> Unit,
    playingId: Long?,
    seed: CoverSeed?,
    onPlay: (Int) -> Unit,
    onOpenAlbum: (CollectionInfo) -> Unit,
    onRetryMoreWorks: () -> Unit,
    onRetrySimilarSongs: () -> Unit,
    onOpenShelf: (section: ArtistShelfSection, artistName: String) -> Unit,
    songMenuContent: @Composable BottomPopupMenuScope.(Song) -> Unit,
    onToggleSubscribed: () -> Unit,
    onInfo: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = seed?.dark == true || MaterialTheme.darkTheme
    val backgroundColor by animateColorAsState(
        targetValue = seed?.primaryTone(if (dark) BgToneDark else BgToneLight)?.let { Color(it) }
            ?: MaterialTheme.colors.pageBackground,
        animationSpec = tween(durationMillis = 500),
        label = "artistBackground",
    )

    val contentColor = if (dark) Color.White else Color.Black

    fun poleTone(darkTone: Double, lightTone: Double): Color? =
        seed?.primaryTone((if (dark) darkTone else lightTone).toInt())?.let(::Color)

    val contentVariantColor by animateColorAsState(
        targetValue = poleTone(VariantToneDark, VariantToneLight)
            ?: MaterialTheme.colors.contentVariant,
        animationSpec = tween(durationMillis = 500),
        label = "contentVariant",
    )

    val buttonColor by animateColorAsState(
        targetValue = poleTone(ButtonToneDark, ButtonToneLight)
            ?: MaterialTheme.colors.cardBackground,
        animationSpec = tween(durationMillis = 500),
        label = "buttonColor",
    )

    val buttonIconColor by animateColorAsState(
        targetValue = poleTone(ButtonIconToneDark, ButtonIconToneLight)
            ?: MaterialTheme.colors.content,
        animationSpec = tween(durationMillis = 500),
        label = "buttonIcon",
    )

    val itemBgColor by animateColorAsState(
        targetValue = poleTone(ItemBgToneDark, ItemBgToneLight)
            ?: MaterialTheme.colors.cardBackground,
        animationSpec = tween(durationMillis = 500),
        label = "itemBg",
    )

    val circleColor = animatePremultipliedColorAsState(
        targetValue = poleTone(CircleToneDark, CircleToneLight)
            ?: MaterialTheme.colors.iconButtonBackground,
        animationSpec = tween(durationMillis = 500),
        label = "circleColor",
    )

    val circleIconColor by animateColorAsState(
        targetValue = poleTone(CircleIconToneDark, CircleIconToneLight)
            ?: MaterialTheme.colors.onIconButton,
        animationSpec = tween(durationMillis = 500),
        label = "circleIcon",
    )

    val description = state.info.dataOrNull?.description.orEmpty().trim()

    val screenWidth = LocalWindowInfo.current.containerDpSize.width
    // Logo 请求随页面保留，避免 Lazy 头部重建时从加载态再次淡入。
    val titleContent = rememberArtistTitleContent(
        logo = nameLogo,
        showTextWhileLoading = showTitleWhileLoading,
        maxWidth = (screenWidth - 48.dp).coerceAtLeast(1.dp),
    )
    val listState = rememberLazyListState()
    val headerVisible by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == "header" } }
    }
    val showTopShade by listState.isScrolledPast(screenWidth * 0.75f)
    val topShapeAlpha by animateFloatAsState(
        targetValue = if (showTopShade) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "topShadeAlpha",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        VerticalStack(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                val info = state.info.dataOrNull
                // 过冲位移由 VerticalStack 通过 Local 提供，这一页不自己建 effect
                val overscrollVisual = LocalOverscrollVisualOffset.current
                when {
                    state.info is Section.Failed && info == null -> InfoFailure(
                        onRetry = onRetry,
                        contentVariant = contentVariantColor,
                    )

                    else -> HeaderBlock(
                        info = info,
                        videoUrl = videoUrl,
                        titleContent = titleContent,
                        videoVisible = headerVisible,
                        likedPlaylist = likedPlaylist,
                        displayTitle = displayTitle,
                        coverUrl = coverUrl,
                        onCoverError = onCoverError,
                        backgroundColor = backgroundColor,
                        buttonColor = buttonColor,
                        buttonIconColor = buttonIconColor,
                        subBusy = state.subBusy,
                        onPlay = { onPlay(0) },
                        onInfo = onInfo,
                        onToggleSubscribed = onToggleSubscribed,
                        overscrollOffsetY = {
                            overscrollVisual?.let { it.visualOffset.y.coerceAtLeast(0f) } ?: 0f
                        },
                    )
                }
            }
            if (artist) {
                // 这几块标题都能点进「全部」页；名字和封面从这一页带过去（页头 + 取色种子都要）
                val openShelf = { section: ArtistShelfSection ->
                    onOpenShelf(section, displayTitle)
                }
                val works = state.artistAlbums.dataOrNull.orEmpty()
                val ranking = state.songs.dataOrNull.orEmpty().take(ArtistRankingSize)
                // 这几块必须**同一帧**出现，而且跟专辑/歌单页一个节奏：等进场动画收尾才放行。
                // 曲目本来就要等 `revealTracks()`，作品列表走的是另一条请求，谁先到谁先画就会在
                // 动画期间把下面已经画好的块挤走一次。
                val settled = revealed &&
                        state.artistAlbums !is Section.Loading && state.songs !is Section.Loading
                val latest = works.filter { it.updateTimeMs > 0 }.maxByOrNull { it.updateTimeMs }
                if (settled && latest != null) {
                    item(key = "artist-latest") {
                        VGap(24.dp)
                        LatestReleaseCard(
                            album = latest,
                            content = contentColor,
                            contentVariant = contentVariantColor,
                            background = itemBgColor,
                            onClick = { onOpenAlbum(latest) },
                            onMore = {},
                            modifier = Modifier.animateItem(
                                tween(200),
                                Springs.crisp(),
                                tween(200)
                            ),
                        )
                    }
                }
                if (settled && ranking.isNotEmpty()) {
                    item(key = "artist-ranking") {
                        // 排行行的第二行是「专辑 · 年份」，专辑类型/发行年从同一份作品列表里捡
                        val albumsById = remember(works) { works.associateBy { it.id } }
                        VGap(36.dp)
                        RecommendationTitle(
                            "歌曲排行", contentColor, contentVariantColor,
                            modifier = Modifier.animateItem(
                                tween(200),
                                Springs.crisp(),
                                tween(200)
                            ),
                            onClick = { openShelf(ArtistShelfSection.SONGS) },
                        )
                        VGap()
                        ranking.forEachIndexed { index, song ->
                            TrackRow(
                                song = song,
                                backgroundColor = itemBgColor,
                                showArtist = false,
                                index = null,
                                position = resolvePosition(ranking.size, index),
                                isPlaying = song.id == playingId,
                                content = contentColor,
                                contentVariant = contentVariantColor,
                                subtitle = rankingSubtitle(song, albumsById[song.albumId]),
                                onClick = { onPlay(index) },
                                menuContent = { songMenuContent(song) },
                                modifier = Modifier.animateItem(
                                    tween(200),
                                    Springs.crisp(),
                                    tween(200)
                                )
                            )
                            if (index < ranking.size - 1) VGap(4.dp)
                        }
                    }
                }
                if (settled) item(key = "artist-albums") {
                    AlbumWorksSection(
                        title = "专辑",
                        albums = state.artistAlbums.mapData { list -> list.filterNot { it.isSingleOrEp() } },
                        content = contentColor,
                        contentVariant = contentVariantColor,
                        onOpenAlbum = onOpenAlbum,
                        onOpenAll = { openShelf(ArtistShelfSection.ALBUMS) },
                        onRetry = onRetry,
                    )
                }
                if (settled) item(key = "artist-singles") {
                    AlbumWorksSection(
                        title = "单曲与EP",
                        albums = state.artistAlbums.mapData { list -> list.filter { it.isSingleOrEp() } },
                        content = contentColor,
                        contentVariant = contentVariantColor,
                        onOpenAlbum = onOpenAlbum,
                        onOpenAll = { openShelf(ArtistShelfSection.SINGLES) },
                        onRetry = onRetry,
                    )
                }
                // 视频比前面几块慢（原生层要替每条 MV 问一次直链），但它在最后：
                // 晚到只是往下接一节，不会挤上面，所以也只跟 `settled` 走，不自己等它
                if (settled) item(key = "artist-videos") {
                    ArtistVideoSection(
                        videos = state.artistVideos,
                        content = contentColor,
                        contentVariant = contentVariantColor,
                        onOpenAll = { openShelf(ArtistShelfSection.VIDEOS) },
                    )
                }
            }
            if (!artist && !incompleteAlbum && description.isNotEmpty()) {
                item(key = "description") {
                    VGap(24.dp)
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    Text(
                        text = description,
                        style = MaterialTheme.type.subHeadline,
                        lineHeight = 20.sp,
                        letterSpacing = 0.1.sp,
                        color = contentVariantColor,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .animateItem(tween(200), Springs.crisp(), tween(200))
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clickable { expanded = !expanded },
                    )
                }
            }
            if (!artist && state.info.dataOrNull != null) {
                // 曲目区只有两种画面：列表，或者「加载失败」。**加载中什么都不画是故意的**——
                // 曲目在进场动画期间就预取完了（见 `CollectionViewModel`），再插一个中间态
                // 只会让列表落地时多抖一下。
                val tracks = state.songs
                if (tracks is Section.Failed) {
                    item(key = "tracks-failed") {
                        ListHint(
                            text = "加载失败 · 点按重试",
                            contentVariant = contentVariantColor,
                            onClick = onRetry,
                        )
                    }
                } else {
                    val songs = tracks.dataOrNull.orEmpty()
                    if (songs.isNotEmpty()) {
                        item(key = "tracks-gap") {
                            VGap(24.dp)
                        }
                        // 必须给 key：`animateItem()` 靠它做增删/位移，没 key 就只有默认的 index。
                        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                            TrackRow(
                                song = song,
                                backgroundColor = itemBgColor,
                                showArtist = song.artistText != state.info.dataOrNull?.author,
                                index = if (state.info.dataOrNull?.kind == CollectionKind.ALBUM) {
                                    albumTrackNumbers[song.id] ?: (index + 1)
                                } else null,
                                position = resolvePosition(songs.size, index),
                                isPlaying = song.id == playingId,
                                content = contentColor,
                                contentVariant = contentVariantColor,
                                // 喜爱歌单整份都是「已喜爱」：行行亮着星星没有信息量，
                                // 顺带省掉每行一条喜欢态订阅（见 TrackRow 的 showStar）
                                showStar = !likedPlaylist,
                                onClick = { onPlay(index) },
                                menuContent = { songMenuContent(song) },
                                // 左右边距在 TrackRow 内部（背景要铺满整宽，左侧那条缝留给红心星）
                                modifier = Modifier
                                    .animateItem(tween(200), Springs.crisp(), tween(200)),
                            )
                            if (index < songs.size - 1) {
                                VGap(4.dp)
                            }
                        }
                        item(key = "footer") {
                            val info = state.info.dataOrNull
                            val songsLoaded = state.songs.dataOrNull
                            val count = songsLoaded?.size ?: info?.trackCount ?: 0
                            val totalMs = songsLoaded?.sumOf { it.durationMs } ?: 0L
                            val footer = footerText(info, count, totalMs)
                            if (footer.isNotEmpty()) {
                                VGap(24.dp)
                                Text(
                                    text = footer,
                                    style = MaterialTheme.type.subHeadline,
                                    lineHeight = 20.sp,
                                    color = contentVariantColor,
                                    modifier = Modifier
                                        .animateItem(tween(200), Springs.crisp(), tween(200))
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (incompleteAlbum && state.songs is Section.Ready) {
                item(key = "show-full-album") {
                    VGap()
                    ProvideContentColor(contentVariantColor) {
                        Row(
                            modifier = Modifier
                                .animateItem(tween(200), Springs.crisp(), tween(200))
                                .fillMaxWidth()
                                .heightIn(48.dp)
                                .clickable(onClick = onShowFullAlbum)
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = "显示完整专辑")
                            Icon(
                                painter = painterResource(R.drawable.chevron_right_semibold),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            if (state.info.dataOrNull?.kind == CollectionKind.ALBUM && state.songs is Section.Ready) {
                val info = state.info.dataOrNull
                if (info != null && info.primaryArtistId > 0) {
                    item(key = "more-works") {
                        AlbumWorksSection(
                            title = "更多${info.primaryArtistName}的作品",
                            albums = state.moreWorks,
                            content = contentColor,
                            contentVariant = contentVariantColor,
                            onOpenAlbum = onOpenAlbum,
                            onRetry = onRetryMoreWorks,
                        )
                    }
                }
                item(key = "similar-songs") {
                    AlbumWorksSection(
                        title = "你可能也喜欢",
                        albums = state.similarAlbums,
                        content = contentColor,
                        contentVariant = contentVariantColor,
                        onOpenAlbum = onOpenAlbum,
                        onRetry = onRetrySimilarSongs,
                    )
                }
            }
        }

        TopShade(
            color = backgroundColor,
            modifier = Modifier.height(72.dp + 48.dp),
            alpha = { topShapeAlpha })
        // 底部 shade 跟页面走：详情页用它自己的封面底色，MiniPlayer/底栏常驻层是透明的
        BottomShade(color = backgroundColor)

        FloatingBar(
            onBack = onBack,
            onShare = onShare,
            background = rememberColorProducer(circleColor),
            tint = rememberColorProducer(circleIconColor),
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/**
 * 全幅头部：封面本体占满屏宽（出血到状态栏底下），下方接一段**倒影带**——
 * 用 [ReflectionTransformation] 按屏宽现算，倒影上的渐变压着标题、作者、元信息和按钮排。
 *
 * 结构恒定：信息没回来时封面位就是一块底色，文字排和按钮排照样占住位置，
 * 不会在加载前后换布局。
 */
@Composable
private fun HeaderBlock(
    info: CollectionInfo?,
    videoUrl: String?,
    titleContent: ArtistTitleContent,
    videoVisible: Boolean,
    likedPlaylist: Boolean,
    displayTitle: String,
    coverUrl: String,
    onCoverError: () -> Unit,
    backgroundColor: Color,
    buttonColor: Color,
    buttonIconColor: Color,
    subBusy: Boolean,
    onPlay: () -> Unit,
    onInfo: () -> Unit,
    onToggleSubscribed: () -> Unit,
    overscrollOffsetY: () -> Float,
    modifier: Modifier = Modifier,
) {
    val screenWidth = LocalWindowInfo.current.containerDpSize.width
    val screenWidthPx = LocalWindowInfo.current.containerSize.width
    val risePx = with(LocalDensity.current) { HeaderRise.toPx() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(screenWidth + HeaderRise)
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val overscrollOffset = overscrollOffsetY()
                    val scale =
                        (screenWidthPx + overscrollOffset + risePx) / (screenWidthPx + risePx)
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .fillMaxSize(),
        ) {
            if (coverUrl.isNotEmpty()) {
                AsyncImage(
                    model = headerCoverModel(coverUrl, screenWidth.value.toInt()),
                    contentDescription = null,
                    onError = { onCoverError() },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (videoUrl != null) {
                ArtistHeaderVideo(
                    url = videoUrl,
                    riseRatio = HeaderRise.value / screenWidth.value,
                    referenceSide = minOf(screenWidthPx, HeaderCoverCapPx).coerceAtLeast(1),
                    isVisible = videoVisible,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(HeaderRise * HeaderGradientRiseMultiplier)
                .headerCoverGradient(rememberColorProducer(backgroundColor))
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ArtistTitle(
                title = displayTitle,
                titleContent = titleContent,
            )

            VGap(24.dp)
            HeaderActions(
                canCollect = info != null && info.kind != CollectionKind.DAILY && !likedPlaylist,
                subscribed = info?.subscribed == true,
                subBusy = subBusy,
                // 这颗钮是「艺人资料」，不是随机播放。界面先摆出来，点开的内容还没做
                leadingIconRes = R.drawable.info_circle,
                leadingLabel = "艺人资料",
                onLeading = onInfo,
                buttonColor = rememberColorProducer(buttonColor),
                buttonIconColor = rememberColorProducer(buttonIconColor),
                onPlay = onPlay,
                onToggleSubscribed = onToggleSubscribed,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private sealed interface ArtistTitleContent {
    data object Loading : ArtistTitleContent
    data object Name : ArtistTitleContent
    data class Logo(val artwork: AppleArtistNameLogo, val painter: Painter) : ArtistTitleContent
}

@Composable
private fun rememberArtistTitleContent(
    logo: AppleArtistNameLogo?,
    showTextWhileLoading: Boolean,
    maxWidth: Dp,
): ArtistTitleContent {
    val context = LocalContext.current
    val density = LocalDensity.current
    val thumbnail = remember(logo, maxWidth, density) {
        with(density) {
            logo?.thumbnail(
                maxWidth.roundToPx().coerceIn(1, HeaderCoverCapPx),
                HeaderLogoMaxHeight.roundToPx().coerceIn(1, HeaderCoverCapPx),
            )
        }
    }
    return if (thumbnail != null) {
        val request = remember(context, thumbnail) {
            ImageRequest.Builder(context)
                .data(thumbnail.url)
                .size(thumbnail.width, thumbnail.height)
                .precision(Precision.EXACT)
                .build()
        }
        val painter = rememberAsyncImagePainter(request)
        val imageState by painter.state.collectAsState()
        when (val state = imageState) {
            is AsyncImagePainter.State.Success -> ArtistTitleContent.Logo(thumbnail, state.painter)
            is AsyncImagePainter.State.Error -> ArtistTitleContent.Name
            else -> if (showTextWhileLoading) ArtistTitleContent.Name else ArtistTitleContent.Loading
        }
    } else if (showTextWhileLoading || logo != null) {
        ArtistTitleContent.Name
    } else {
        ArtistTitleContent.Loading
    }
}

@Composable
private fun ArtistTitle(
    title: String,
    titleContent: ArtistTitleContent,
) {
    val density = LocalDensity.current
    Crossfade(
        targetState = titleContent,
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        animationSpec = tween(500),
        label = "artistTitle",
    ) { content ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            when (content) {
                ArtistTitleContent.Loading -> Unit
                ArtistTitleContent.Name -> Text(
                    text = title,
                    style = MaterialTheme.type.title1Emphasized,
                    lineHeight = 32.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { blendMode = BlendMode.Plus },
                )

                is ArtistTitleContent.Logo -> Image(
                    painter = content.painter,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(
                        with(density) { content.artwork.width.toDp() },
                        with(density) { content.artwork.height.toDp() },
                    ),
                )
            }
        }
    }
}
