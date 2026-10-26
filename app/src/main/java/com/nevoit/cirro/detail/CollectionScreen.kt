package com.nevoit.cirro.detail

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nevoit.cirro.R
import com.nevoit.cirro.data.AccountRepository
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
import com.nevoit.kinetic.layout.overscroll.rememberOffsetOverscrollEffect
import com.nevoit.material.core.animation.animatePremultipliedColorAsState
import com.nevoit.material.core.component.BottomPopupMenuScope
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.layout.LocalOverscrollVisualOffset
import com.nevoit.material.core.layout.VerticalStack
import com.nevoit.material.core.layout.isScrolledPast
import com.nevoit.material.core.layout.rememberNavigationTailPadding
import com.nevoit.material.core.layout.rememberOverlayScrollIndicatorSpec
import com.nevoit.material.core.modifier.drawBackground
import com.nevoit.material.core.utility.rememberColorProducer
import com.nevoit.material.navigation.LocalPageTransitionState
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.local.ProvideContentColor
import com.nevoit.material.theme.tokens.Springs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/** 这一页主题色的补间时长（种子落地时底色/按钮/文字一起走过去）。 */
private const val PageColorMs = 500

/**
 * 统一详情页（歌单 / 专辑 / 每日推荐 / 艺术家热门歌曲）。
 *
 * 界面不知道也不关心内容从哪来——[CollectionViewModel] 已经把来源折成同一份形状。
 * 顶部那排圆钮浮在内容上：左返回、右更多和分享；底部 MiniPlayer 和导航栏常驻在
 * 导航宿主之外（见 Main），列表自己留够尾距。
 */
@Composable
fun CollectionRoute(
    kind: CollectionKind,
    id: Long,
    titleHint: String,
    coverHint: String,
    onBack: () -> Unit,
    onOpenCollection: OpenCollection,
    modifier: Modifier = Modifier,
    libraryAlbum: Boolean = false,
    likedPlaylist: Boolean = false,
    authorHint: String = "",
) {
    val viewModel: CollectionViewModel = koinViewModel {
        parametersOf(kind, id, titleHint)
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
    val pageTransition = LocalPageTransitionState.current
    LaunchedEffect(viewModel, pageTransition) {
        pageTransition?.awaitNearlySettled()
        viewModel.revealTracks()
        tracksRevealed = true
    }

    // 上面那拍（页面动画 settle → `revealTracks()`）一到就显示，不等曲目数据、也不看行有没有画出来。
    // `tracksRevealed` 就是这次 settle 的标记，所以头部和曲目是同一个放行点。
    // 不压这一下的话，info 会在动画中途落地，头部自己先重排一次：作者/元信息多一行、
    // 收藏那颗按钮整颗冒出来，底对齐的标题跟着往上顶。
    // `state.info` 自己不被延迟：曲目门禁、简介、作品区、封面兜底都靠它先到先算。
    // 颜色不在此列——底色和按钮色跟着封面种子走，该什么时候亮就什么时候亮。
    val headerInfo = if (tracksRevealed) state.info.dataOrNull else null

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

    CollectionScreen(
        state = state,
        headerInfo = headerInfo,
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
        onShuffle = {
            // 随机播放 = 整份歌单进队列 + 随机那一轴打开（播放页那颗按钮跟着亮）。
            // 整队乱序由 `playQueueOf(shuffle = true)` 负责，起点就是乱序后的第一首。
            if (songs.isNotEmpty()) {
                playback.playSongs(songs, 0, queueSource, shuffle = true)
            }
        },
        onToggleSubscribed = viewModel::toggleSubscribed,
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
private fun CollectionScreen(
    state: CollectionViewModel.UiState,
    /** 头部显示的信息：动画 settle 之后才给，其余用途仍读 [state] 里的 info。 */
    headerInfo: CollectionInfo?,
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
    songMenuContent: @Composable BottomPopupMenuScope.(Song) -> Unit,
    onShuffle: () -> Unit,
    onToggleSubscribed: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = seed?.dark == true || MaterialTheme.darkTheme
    // 颜色在这里只"起个头"：animateColorAsState 建出 State，立刻包成 ColorProducer 往下传，
    // 全程不读 `.value`。读了就是组合期读数——种子落地（缓存没命中时正好落在进场动画中间）
    // 那 500ms 会每帧把这一整页连同头部、每一行、每一行里的文字全部重组一遍。
    // 真正读值的只有两类地方：绘制段（背景 / 渐变 / 按钮 / 图标）和叶子文字。
    val backgroundColor = rememberPageColor(
        seed?.primaryTone(if (dark) BgToneDark else BgToneLight)?.let { Color(it) }
            ?: MaterialTheme.colors.pageBackground,
        label = "collectionBackground",
    )

    val contentColor = if (dark) Color.White else Color.Black
    val contentColorProducer = rememberColorProducer(contentColor)

    fun poleTone(darkTone: Double, lightTone: Double): Color? =
        seed?.primaryTone((if (dark) darkTone else lightTone).toInt())?.let(::Color)

    val contentVariantColor = rememberPageColor(
        poleTone(VariantToneDark, VariantToneLight) ?: MaterialTheme.colors.contentVariant,
        label = "contentVariant",
    )

    val buttonColor = rememberPageColor(
        poleTone(ButtonToneDark, ButtonToneLight) ?: MaterialTheme.colors.cardBackground,
        label = "buttonColor",
    )

    val buttonIconColor = rememberPageColor(
        poleTone(ButtonIconToneDark, ButtonIconToneLight) ?: MaterialTheme.colors.content,
        label = "buttonIcon",
    )

    val itemBgColor = rememberPageColor(
        poleTone(ItemBgToneDark, ItemBgToneLight) ?: MaterialTheme.colors.cardBackground,
        label = "itemBg",
    )

    val circleColor = rememberColorProducer(
        animatePremultipliedColorAsState(
            targetValue = poleTone(CircleToneDark, CircleToneLight)
                ?: MaterialTheme.colors.iconButtonBackground,
            animationSpec = tween(durationMillis = PageColorMs),
            label = "circleColor",
        )
    )

    val circleIconColor = rememberPageColor(
        poleTone(CircleIconToneDark, CircleIconToneLight) ?: MaterialTheme.colors.onIconButton,
        label = "circleIcon",
    )

    val description = state.info.dataOrNull?.description.orEmpty().trim()

    val screenWidth = LocalWindowInfo.current.containerDpSize.width
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val searchThresholdPx = with(density) { CollectionSearchPullThreshold.toPx() }
    val dismissThresholdPx = LocalViewConfiguration.current.touchSlop
    val search = rememberSaveable(
        searchThresholdPx,
        dismissThresholdPx,
        saver = CollectionSearchState.saver(searchThresholdPx, dismissThresholdPx),
    ) { CollectionSearchState(searchThresholdPx, dismissThresholdPx) }
    val searching = search.query.isNotBlank()
    val searchResult =
        rememberCollectionSongSearchResults(state.songs.dataOrNull.orEmpty(), search.query)
    val offsetEffect = rememberOffsetOverscrollEffect(listState)
    val searchEffect = remember(offsetEffect, search, listState) {
        CollectionSearchOverscrollEffect(offsetEffect, search) { listState.canScrollBackward }
    }
    var floatingBarHeightPx by remember { mutableIntStateOf(0) }
    val searchTop = if (floatingBarHeightPx > 0) with(density) { floatingBarHeightPx.toDp() }
    else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val closeSearch = {
        keyboard?.hide()
        focus.clearFocus()
        search.close()
    }
    BackHandler(enabled = search.revealed, onBack = closeSearch)
    val showTopShade by listState.isScrolledPast(screenWidth * 0.75f)
    val topShapeAlpha by animateFloatAsState(
        targetValue = if (showTopShade) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "topShadeAlpha",
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .collectionSearchGestures(search)
            .drawBackground(backgroundColor)
    ) {
        // 底色跟着封面种子走，可能跟主题反着来（浅色主题 + 深色封面），滚动条得跟这一页的明暗，
        // 不能只看主题的 content 色。
        val scrollIndicator = rememberOverlayScrollIndicatorSpec(
            endPadding = rememberNavigationTailPadding(),
            color = contentColor.copy(if (dark) .4f else .2f),
        )
        VerticalStack(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(if (searching) Modifier.imePadding() else Modifier)
                .semantics { testTagsAsResourceId = true }
                .testTag(
                    if (state.songs is Section.Ready) "collection-tracks-ready"
                    else "collection-tracks-loading"
                ),
            scrollIndicator = scrollIndicator,
            overscrollEffect = searchEffect,
            contentPadding = if (searching) PaddingValues(
                top = searchTop + CollectionSearchHeight + 12.dp,
                bottom = rememberNavigationTailPadding(),
            ) else null,
        ) {
            if (searching) {
                when (state.songs) {
                    is Section.Loading -> item(key = "search-loading") {
                        ListHint(text = "正在加载歌曲…", contentVariant = contentVariantColor())
                    }

                    is Section.Failed -> item(key = "search-failed") {
                        ListHint(
                            text = "加载失败 · 点按重试",
                            contentVariant = contentVariantColor(),
                            onClick = onRetry,
                        )
                    }

                    is Section.Ready -> Unit
                }
            }
            if (!searching) item(key = "header") {
                val info = state.info.dataOrNull
                // 过冲位移由 VerticalStack 通过 Local 提供，这一页不自己建 effect
                val overscrollVisual = LocalOverscrollVisualOffset.current
                when {
                    state.info is Section.Failed && info == null -> InfoFailure(
                        onRetry = onRetry,
                        contentVariant = contentVariantColor(),
                        modifier = Modifier.animateItem(tween(200), Springs.crisp(), tween(200)),
                    )

                    else -> HeaderBlock(
                        info = headerInfo,
                        likedPlaylist = likedPlaylist,
                        displayTitle = displayTitle,
                        authorHint = authorHint,
                        coverUrl = coverUrl,
                        onCoverError = onCoverError,
                        backgroundColor = backgroundColor,
                        buttonColor = buttonColor,
                        buttonIconColor = buttonIconColor,
                        subBusy = state.subBusy,
                        onPlay = { onPlay(0) },
                        onShuffle = onShuffle,
                        onToggleSubscribed = onToggleSubscribed,
                        overscrollOffsetY = {
                            overscrollVisual?.visualOffset?.y?.coerceAtLeast(0f) ?: 0f
                        },
                        modifier = Modifier.animateItem(tween(200), Springs.crisp(), tween(200)),
                    )
                }
            }
            if (!searching && !incompleteAlbum && description.isNotEmpty()) {
                item(key = "description") {
                    VGap(24.dp)
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    Text(
                        text = description,
                        style = MaterialTheme.type.subHeadline,
                        lineHeight = 20.sp,
                        letterSpacing = 0.1.sp,
                        color = contentVariantColor(),
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
            if (searching || state.info.dataOrNull != null) {
                // 曲目区只有两种画面：列表，或者「加载失败」。**加载中什么都不画是故意的**——
                // 曲目在进场动画期间就预取完了（见 `CollectionViewModel`），再插一个中间态
                // 只会让列表落地时多抖一下。
                val tracks = state.songs
                if (!searching && tracks is Section.Failed) {
                    item(key = "tracks-failed") {
                        ListHint(
                            text = "加载失败 · 点按重试",
                            contentVariant = contentVariantColor(),
                            onClick = onRetry,
                        )
                    }
                } else {
                    val songs = tracks.dataOrNull.orEmpty()
                    val matches = if (searching) searchResult?.matches else null
                    val songCount = matches?.size ?: songs.size
                    if (songCount > 0) {
                        if (!searching) item(key = "tracks-gap") {
                            Box(
                                Modifier
                                    .animateItem(tween(200), Springs.crisp(), tween(200))
                                    .height(24.dp)
                            )
                        }
                        // 必须给 key：`animateItem()` 靠它做增删/位移，没 key 就只有默认的 index。
                        items(
                            count = songCount,
                            key = { index -> matches?.get(index)?.value?.id ?: songs[index].id },
                        ) { index ->
                            val entry = matches?.get(index)
                            val song = entry?.value ?: songs[index]
                            val originalIndex = entry?.index ?: index
                            TrackRow(
                                song = song,
                                backgroundColor = itemBgColor,
                                showArtist = searching || song.artistText != state.info.dataOrNull?.author,
                                index = if (state.info.dataOrNull?.kind == CollectionKind.ALBUM) {
                                    albumTrackNumbers[song.id] ?: (originalIndex + 1)
                                } else null,
                                position = resolvePosition(songCount, index),
                                isPlaying = song.id == playingId,
                                content = contentColorProducer,
                                contentVariant = contentVariantColor,
                                // 喜爱歌单整份都是「已喜爱」：行行亮着星星没有信息量，
                                // 顺带省掉每行一条喜欢态订阅（见 TrackRow 的 showStar）
                                showStar = !likedPlaylist,
                                onClick = { onPlay(originalIndex) },
                                menuContent = { songMenuContent(song) },
                                // 左右边距在 TrackRow 内部（背景要铺满整宽，左侧那条缝留给红心星）
                                modifier = Modifier
                                    .animateItem(tween(200), Springs.crisp(), tween(200)),
                            )
                            if (index < songCount - 1) {
                                VGap(4.dp)
                            }
                        }
                        if (!searching) item(key = "footer") {
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
                                    color = contentVariantColor(),
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

            if (!searching && incompleteAlbum && state.songs is Section.Ready) {
                item(key = "show-full-album") {
                    VGap()
                    ProvideContentColor(contentVariantColor()) {
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

            if (!searching && state.info.dataOrNull?.kind == CollectionKind.ALBUM && state.songs is Section.Ready) {
                val info = state.info.dataOrNull
                if (info != null && info.primaryArtistId > 0) {
                    item(key = "more-works") {
                        AlbumWorksSection(
                            title = "更多${info.primaryArtistName}的作品",
                            albums = state.moreWorks,
                            content = contentColor,
                            contentVariant = contentVariantColor(),
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
                        contentVariant = contentVariantColor(),
                        onOpenAlbum = onOpenAlbum,
                        onRetry = onRetrySimilarSongs,
                    )
                }
            }
        }

        TopShade(
            color = backgroundColor,
            modifier = Modifier.height(
                72.dp + 48.dp + if (search.revealed) 12.dp + CollectionSearchHeight else 0.dp
            ),
            alpha = { topShapeAlpha })
        // 底部 shade 跟页面走：详情页用它自己的封面底色，MiniPlayer/底栏常驻层是透明的
        BottomShade(color = backgroundColor)

        FloatingBar(
            onBack = { if (search.revealed) closeSearch() else onBack() },
            onShare = onShare,
            background = circleColor,
            tint = circleIconColor,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { floatingBarHeightPx = it.height },
        )
        CollectionSearchField(
            state = search,
            background = circleColor,
            tint = circleIconColor,
            modifier = Modifier
                .align(Alignment.TopCenter)
                // FloatingBar 的底部 padding 正好是按钮下的 12dp 间距。
                .padding(top = searchTop, start = PagePadding, end = PagePadding),
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
    likedPlaylist: Boolean,
    displayTitle: String,
    authorHint: String,
    coverUrl: String,
    onCoverError: () -> Unit,
    backgroundColor: ColorProducer,
    buttonColor: ColorProducer,
    buttonIconColor: ColorProducer,
    subBusy: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
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
        if (coverUrl.isNotEmpty()) {
            AsyncImage(
                model = headerCoverModel(coverUrl, screenWidth.value.toInt()),
                contentDescription = null,
                onError = { onCoverError() },
                contentScale = ContentScale.Crop,
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
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(HeaderRise * HeaderGradientRiseMultiplier)
                .headerCoverGradient(backgroundColor)
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = displayTitle,
                style = MaterialTheme.type.title4Emphasized,
                lineHeight = 26.sp,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer {
                    blendMode = BlendMode.Plus
                }
            )

            val author = info?.author ?: authorHint
            VGap(4.dp)
            Text(
                text = author,
                style = MaterialTheme.type.subHeadline,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                minLines = 1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer {
                    blendMode = BlendMode.Plus
                }
            )

            val meta =
                if (likedPlaylist) relativeAddedDay(info?.updateTimeMs ?: 0) else metaLine(info)
            if (meta.isNotEmpty()) {
                VGap(4.dp)
                Text(
                    text = meta,
                    style = MaterialTheme.type.footnote,
                    color = Color.White.copy(alpha = 0.3f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer {
                        blendMode = BlendMode.Plus
                    }
                )
            }

            VGap(16.dp)
            HeaderActions(
                canCollect = info != null && info.kind != CollectionKind.DAILY && !likedPlaylist,
                subscribed = info?.subscribed == true,
                subBusy = subBusy,
                buttonColor = buttonColor,
                buttonIconColor = buttonIconColor,
                leadingIconRes = R.drawable.shuffle,
                leadingLabel = "随机播放",
                onLeading = onShuffle,
                onPlay = onPlay,
                onToggleSubscribed = onToggleSubscribed,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 这一页的主题色补间，**直接包成** [ColorProducer]。
 *
 * 要点是全程不读 `.value`：`animateColorAsState` 只负责建出 State，值留给绘制段
 * （`color()`）或者叶子文字去读。写成 `by animateColorAsState` 就等于在组合期读数，
 * 颜色每动一帧就把这一整页重组一次——而种子是异步取回来的，缓存没命中时它正好在
 * 进场动画的中间落地，那 500ms 里导航位移、头部封面、整张列表会全部跟着逐帧重来。
 */
@Composable
private fun rememberPageColor(target: Color, label: String): ColorProducer =
    rememberColorProducer(
        animateColorAsState(
            targetValue = target,
            animationSpec = tween(durationMillis = PageColorMs),
            label = label,
        )
    )
