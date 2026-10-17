package com.nevoit.cirro.detail

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.kyant.shapes.RoundedRectangle
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
import com.nevoit.material.core.animation.rememberPressAnimatable
import com.nevoit.material.core.component.BottomPopupMenuScope
import com.nevoit.material.core.component.CircleIconButton
import com.nevoit.material.core.component.HGap
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.MaterialShapeButton
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.interaction.overscroll.OffsetOverscrollEffect
import com.nevoit.material.core.layout.VerticalStack
import com.nevoit.material.core.layout.isScrolledPast
import com.nevoit.material.core.modifier.smoothGradient
import com.nevoit.material.core.utility.NavigationBarSpacer
import com.nevoit.material.navigation.LocalPageTransitionState
import com.nevoit.material.theme.MaterialShapes
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.local.ProvideContentColor
import com.nevoit.material.theme.scaled
import com.nevoit.material.theme.tokens.Springs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

private val PagePadding = 12.dp

private val TrackCorner = 8.dp

private val HeaderRise = 128.dp

private const val HeaderGradientRiseMultiplier = 2f

private const val HeaderCoverCapPx = 1024

private const val BgToneDark = 30

private const val BgToneLight = 80

private const val VariantToneDark = 80.0

private const val VariantToneLight = 40.0

private const val ButtonToneDark = 50.0

private const val ButtonToneLight = 90.0

private const val ButtonIconToneDark = 90.0

private const val ButtonIconToneLight = 40.0

private const val ItemBgToneDark = 40.0

private const val ItemBgToneLight = 90.0

private const val CircleToneDark = 40.0

private const val CircleToneLight = 90.0

private const val CircleIconToneDark = 90.0

private const val CircleIconToneLight = 30.0

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
    val remoteState by viewModel.state.collectAsState()
    val libraryRepository = koinInject<LibraryRepository>()
    val library by libraryRepository.snapshot.collectAsState()
    val libraryError by libraryRepository.error.collectAsState()
    val session by koinInject<AccountRepository>().session.collectAsState()
    val scope = rememberCoroutineScope()
    var tracksRevealed by remember(viewModel) { mutableStateOf(false) }
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

    val songs = state.songs.dataOrNull.orEmpty()
    // 这里是误报，不改
    @Suppress("StateFlowValueCalledInComposition")
    val playingId by remember(playback) {
        playback.state.map { it.song?.id }.distinctUntilChanged()
    }.collectAsState(initial = playback.state.value.song?.id)
    val queueActions by remember(playback) {
        playback.queue.map { it.entries.isNotEmpty() to it.hasPendingInserts }
            .distinctUntilChanged()
    }.collectAsState(initial = false to false)
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
    val backgroundColor by animateColorAsState(
        targetValue = seed?.primaryTone(if (dark) BgToneDark else BgToneLight)?.let { Color(it) }
            ?: MaterialTheme.colors.pageBackground,
        animationSpec = tween(durationMillis = 500),
        label = "collectionBackground",
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

    val circleColor by animateColorAsState(
        targetValue = poleTone(CircleToneDark, CircleToneLight)
            ?: MaterialTheme.colors.secondaryContainer,
        animationSpec = tween(durationMillis = 500),
        label = "circleColor",
    )

    val circleIconColor by animateColorAsState(
        targetValue = poleTone(CircleIconToneDark, CircleIconToneLight)
            ?: MaterialTheme.colors.onSecondaryContainer,
        animationSpec = tween(durationMillis = 500),
        label = "circleIcon",
    )

    val description = state.info.dataOrNull?.description.orEmpty().trim()
    val overscrollEffect = rememberOverscrollEffect()

    val screenWidth = LocalWindowInfo.current.containerDpSize.width
    val listState = rememberLazyListState()
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
            overscrollEffect = overscrollEffect
        ) {
            item(key = "header") {
                val info = state.info.dataOrNull
                when {
                    state.info is Section.Failed && info == null -> InfoFailure(
                        onRetry = onRetry,
                        contentVariant = contentVariantColor,
                    )

                    else -> HeaderBlock(
                        info = info,
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
                        overscrollEffect = overscrollEffect,
                    )
                }
            }
            if (!incompleteAlbum && description.isNotEmpty()) {
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
            if (state.info.dataOrNull != null) {
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

            // 尾距垫住常驻的 MiniPlayer + 底栏（数字跟首页同一个约定）
            item(key = "bottom") {
                VGap(36.dp)
                VGap(144.dp)
                NavigationBarSpacer()
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
            background = circleColor,
            tint = circleIconColor,
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
    likedPlaylist: Boolean,
    displayTitle: String,
    authorHint: String,
    coverUrl: String,
    onCoverError: () -> Unit,
    backgroundColor: Color,
    buttonColor: Color,
    buttonIconColor: Color,
    subBusy: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onToggleSubscribed: () -> Unit,
    overscrollEffect: OverscrollEffect?,
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
                        val overscrollOffset =
                            (overscrollEffect as? OffsetOverscrollEffect)?.visualOffset?.y?.coerceAtLeast(
                                0f
                            )
                                ?: 0f
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
                .smoothGradient(
                    color = backgroundColor,
                    start = 0f,
                    end = 1f,
                    intensity = 1f,
                )
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
                onPlay = onPlay,
                onShuffle = onShuffle,
                onToggleSubscribed = onToggleSubscribed,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 头部按钮排：左随机播放（圆钮）、中播放（胶囊）、右收藏（圆钮）。
 * 每日推荐没有可收藏的实体，右侧留一个等宽空位，让胶囊保持正中。
 */
@Composable
private fun HeaderActions(
    canCollect: Boolean,
    subscribed: Boolean,
    subBusy: Boolean,
    buttonColor: Color,
    buttonIconColor: Color,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onToggleSubscribed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hapticController = LocalHapticFeedback.current

    val press = rememberPressAnimatable(
        atRest = 28f,
        whenPressed = 12f,
        onPressSpec = Springs.smooth(150),
        onReleaseSpec = Springs.smooth(200, 0.3),
        onReleased = { hapticController.performHapticFeedback(HapticFeedbackType.ContextClick) },
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        MaterialShapeButton(
            iconRes = R.drawable.shuffle,
            shape = MaterialShapes.Cookie4Sided,
            label = "随机播放",
            background = buttonColor,
            tint = buttonIconColor,
            onClick = onShuffle,
        )
        HGap(8.dp)
        Box(
            modifier = Modifier
                .height(56.dp)
                .width(128.dp + (28.dp - press.progress.value.dp) / 2)
                .clip(RoundedRectangle(press.progress.value.dp))
                .background(Color.White)
                .clickable(
                    onClick = onPlay,
                    interactionSource = press.interactionSource
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.play_fill),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = Color.Black,
                )
            }
        }
        HGap(8.dp)
        if (canCollect) {
            MaterialShapeButton(
                iconRes = if (subscribed) R.drawable.star_fill else R.drawable.star,
                label = if (subscribed) "已收藏" else "收藏",
                shape = MaterialShapes.Sunny.scaled(0.97f),
                background = buttonColor,
                tint = buttonIconColor,
                enabled = !subBusy,
                onClick = onToggleSubscribed,
            )
        } else {
            Spacer(modifier = Modifier.size(56.dp))
        }
    }
}

/** 顶部浮着的那排圆钮：左返回，右分享 + 更多。 */
@Composable
private fun FloatingBar(
    onBack: () -> Unit,
    onShare: () -> Unit,
    background: Color,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .statusBarsPadding()
            .padding(PagePadding)
            .fillMaxWidth()
            .height(48.dp),
    ) {
        CircleIconButton(
            iconRes = R.drawable.chevron_left_nav,
            label = "返回",
            background = background,
            tint = tint,
            onClick = onBack,
        )
        Spacer(modifier = Modifier.weight(1f))
        CircleIconButton(
            iconRes = R.drawable.share,
            label = "分享",
            background = background,
            tint = tint,
            onClick = onShare,
        )
        HGap()
        // 更多菜单还没做，先把位置摆出来
        CircleIconButton(
            iconRes = R.drawable.ellipsis,
            label = "更多",
            background = background,
            tint = tint,
            onClick = {},
        )
    }
}

/** 详情头部整个拉不到时的占位（重试入口）。 */
@Composable
private fun InfoFailure(
    onRetry: () -> Unit,
    contentVariant: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PagePadding)
            .padding(top = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "加载失败 · 点按重试",
            style = MaterialTheme.type.footnote,
            color = contentVariant,
            modifier = Modifier
                .clip(RoundedRectangle(TrackCorner))
                .clickable(onClick = onRetry)
                .padding(8.dp),
        )
        VGap(24.dp)
    }
}

/** 列表加载中 / 失败的小字提示，摆在曲目区的位置上。 */
@Composable
private fun ListHint(
    text: String,
    contentVariant: Color,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.type.footnote,
        color = contentVariant,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedRectangle(TrackCorner))
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = PagePadding, vertical = 12.dp),
    )
}

/**
 * 头部封面的请求：按「方形 ×（方形 + 倒影带）」的像素尺寸要图，挂上倒影变换。
 *
 * 方形边长封顶在 [HeaderCoverCapPx]——布局比它宽时出图**整体等比缩**：倒影带的宽高比不变，
 * 模糊半径跟着方形等比折算，观感一致，只是 AsyncImage 把小一号的图拉回布局尺寸。
 * 变换的输出宽高跟请求尺寸一一对应（详见 [ReflectionTransformation]），不会被二次缩放。
 * 地址为空时返回 null，Coil 什么都不画，露出底色。
 */
@Composable
private fun headerCoverModel(url: String, widthDp: Int): Any? {
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthPx = with(density) { widthDp.dp.roundToPx() }
    val side = minOf(widthPx, HeaderCoverCapPx)
    val riseRatio = HeaderRise.value / widthDp
    val risePx = (side * riseRatio).roundToInt()

    return remember(url, side, risePx) {
        url.takeIf { it.isNotEmpty() }?.let {
            ImageRequest.Builder(context)
                .data(it)
                .size(side, side + risePx)
                .transformations(
                    ReflectionTransformation(
                        squareDp = side.toFloat(),
                        stripDp = side * riseRatio / 3,
                        riseDp = side * riseRatio,
                        bleedDp = side * riseRatio / 2,
                        blurDp = 96f
                    )
                )
                .crossfade(true)
                .build()
        }
    }
}

/**
 * 头部元信息一行（小标题）：专辑给「年份」、单曲/EP 给「年份 · 类型」、
 * 歌单给「更新时间」。每日推荐的日期已在作者位，这行让空。
 * 没有可说的就给空串，那一行整个让位。
 */
private fun metaLine(info: CollectionInfo?): String {
    info ?: return ""
    return when (info.kind) {
        CollectionKind.ALBUM -> {
            val parts = mutableListOf<String>()
            val year = yearOf(info.updateTimeMs)
            if (year > 0) parts += year.toString()
            // 普通专辑小标题只有年份；Single/EP 之类捎上类型
            if (info.albumType.isNotEmpty() && info.albumType != "专辑") parts += info.albumType
            parts.joinToString(" · ")
        }

        CollectionKind.PLAYLIST -> formatDate(info.updateTimeMs).orEmpty()
        CollectionKind.DAILY, CollectionKind.ARTIST -> ""
    }
}

private fun yearOf(ms: Long): Int {
    if (ms <= 0) return 0
    return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).year
}

/** 「XXXX年X月X日」。时间戳无效给 null，调用方整段省略。 */
private fun formatDate(ms: Long): String? {
    if (ms <= 0) return null
    val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
}

/**
 * 曲目列表底部的落款（居中多行小字）：
 * 专辑给「发行日期 / N首歌曲，总时长 / Ⓟ公司」（公司空串那行整行省略）；
 * 歌单只给「N首歌曲，总时长」；每日推荐不给落款。
 * 总时长最小单位分钟（不足一分钟不显示），满一小时进位成「n小时n分钟」。
 */
private fun footerText(info: CollectionInfo?, songCount: Int, totalMs: Long): String {
    info ?: return ""
    if (songCount <= 0) return ""
    val countLine = buildString {
        append(songCount).append("首歌曲")
        val minutes = totalMs / 60_000
        if (minutes > 0) {
            append(", ")
            val hours = minutes / 60
            if (hours > 0) append(hours).append("小时").append(minutes % 60).append("分钟")
            else append(minutes).append("分钟")
        }
    }
    return when (info.kind) {
        CollectionKind.ALBUM -> {
            val lines = mutableListOf<String>()
            formatDate(info.updateTimeMs)?.let { lines += it }
            lines += countLine
            if (info.company.isNotEmpty()) lines += "Ⓟ ${info.company}"
            lines.joinToString("\n")
        }

        CollectionKind.PLAYLIST -> countLine
        CollectionKind.ARTIST -> countLine
        CollectionKind.DAILY -> ""
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val totalSeconds = ms / 1000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
