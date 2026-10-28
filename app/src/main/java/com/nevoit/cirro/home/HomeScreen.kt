package com.nevoit.cirro.home

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.kyant.shapes.RoundedRectangle
import com.nevoit.cirro.R
import com.nevoit.cirro.data.RadioOpening
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.MemoryPlaylist
import com.nevoit.cirro.data.model.MemoryPlaylists
import com.nevoit.cirro.data.model.QueueSource
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.login.LoginActivity
import com.nevoit.cirro.palette.CoverPalette
import com.nevoit.cirro.playback.PlaybackConnection
import com.nevoit.cirro.playback.PlayingCoverIndicator
import com.nevoit.cirro.playback.pearmesh.memoryCoverPaletteKey
import com.nevoit.cirro.playback.pearmesh.rememberMemoryCoverRequest
import com.nevoit.cirro.playback.rememberIsCurrentSong
import com.nevoit.cirro.ui.CollectionCard
import com.nevoit.cirro.ui.ReflectionTransformation
import com.nevoit.cirro.ui.rememberCoverRequest
import com.nevoit.kinetic.layout.KineticLazyRow
import com.nevoit.kinetic.layout.overscroll.rememberOffsetOverscrollEffect
import com.nevoit.kinetic.layout.snap.rememberKineticSnapFlingBehavior
import com.nevoit.material.core.component.CircleIconButton
import com.nevoit.material.core.component.HGap
import com.nevoit.material.core.component.PageHeader
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.layout.VerticalStack
import com.nevoit.material.core.modifier.smoothGradient
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.tokens.Springs
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.time.LocalDate

private val CardWidth = 216.dp
private val CardRise = 72.dp
private val CardHeight = CardWidth + CardRise

private val CardPadding = 16.dp
private val MemoryLabelFont = FontFamily(Font(R.font.inter_display_regular, FontWeight.Normal))

/**
 * 电台卡渐变的两端（HCT tone）。
 *
 * 同一个种子色的两个明度档，色相不变，所以两端永远同色系。底下那档压到 22 是因为
 * 标题全是白字 —— 再亮就开始糊了。
 */
private const val RadioTopTone = 20
private const val RadioBottomTone = 45

/**
 * 电台卡整块（渐变底 + 三张图）的淡入时长。
 *
 * 是一次性的入场，不是循环动画：等三张图和颜色都到齐才淡入，之后完全静止。
 * 缓存命中时连这一次都不跑（见 [RadioCard] 的 `gate` 初值）。
 */
private const val RadioFadeMillis = 480

private const val CreditLines = 2

private const val CreditSeparator = ", "
private const val CreditSuffix = "等"

private const val Placeholder = "\u00A0"

private val ShelfPadding = 12.dp

/**
 * 雷达歌单区的区标题，**写死**。
 *
 * 接口也给了个标题，但那是「obviouslynev的雷达歌单」——带动加载中先空一块、数据回来再蹦出
 * 一串用户名，不如固定四个字稳。
 */
private const val RadarShelfTitle = "雷达播放列表"

/**
 * 「最近播放」的区标题，**写死**。
 *
 * 原来这块叫「最近常听」（跟着接口 `data.title` 走），现在数据源换成了听歌记录的专辑维度，
 * 接口那边根本没有标题，正好自己定一个说人话的：里面放的就是最近听过的专辑。
 */
private const val RecentShelfTitle = "最近播放"

/** 歌单卡：正方形封面 + 下方歌单名（+ 可选的一行小字）。 */
private val PlaylistCardSize = 152.dp

/** 点入口卡 / 横滑卡时要去的详情页目的地：种类、id、清洗过的显示名。 */
typealias OpenCollection = (kind: CollectionKind, id: Long, titleHint: String, coverHint: String, authorHint: String) -> Unit

@Composable
fun BoxScope.HomeRoute(
    session: Session,
    onOpenCollection: OpenCollection = { _, _, _, _, _ -> },
    modifier: Modifier = Modifier,
    onOpenMemoryPlaylist: OpenCollection = onOpenCollection,
) {
    // 🔴 只有「确定没登录」才不建 VM。登录态还未知（冷启动 Session.Unknown）时也得建：
    // 首页第一屏全靠 HomeRepository 的磁盘快照，建了 VM 才有第一帧 —— 等 loginStatus 的
    // 网络往返回来再建，磁盘缓存就白存了。VM 自己会等 cookie 进原生层才发请求。
    val viewModel = if (session != Session.LoggedOut) koinViewModel<HomeViewModel>() else null
    val playback = koinInject<PlaybackConnection>()
    val state = viewModel?.state?.collectAsStateWithLifecycle()?.value

    // 列表期把封面的主色预取好：用户点进详情页时背景色已经在缓存里，第一帧就有
    val palette = koinInject<CoverPalette>()
    val coverUrls = remember(state) {
        buildList {
            state?.cards?.forEach { addAll(it.covers) }
            state?.shelf?.dataOrNull?.forEach { card -> addAll(card.covers) }
            state?.recent?.dataOrNull?.forEach { card -> addAll(card.covers) }
            state?.memories?.dataOrNull?.playlists?.forEach { add(it.cover) }
        }.distinct()
    }
    LaunchedEffect(coverUrls) { coverUrls.forEach(palette::prefetch) }
    val onRetry: () -> Unit = { viewModel?.refresh() }

    VerticalStack(modifier = modifier.fillMaxSize()) {
        item(key = "home_title") { HomeTitle() }

        if (session == Session.LoggedOut) {
            item(key = "home_login") { LoginCard(modifier = Modifier.padding(horizontal = 12.dp)) }
        }

        if (state != null) {
            item(key = "home_entries") {
                val rowState = rememberLazyListState()
                KineticLazyRow(
                    state = rowState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = ShelfPadding),
                    horizontalArrangement = Arrangement.spacedBy(ShelfPadding),
                    // 松手后吸附到卡片起点；滚动条本来就没有，关掉别让 kinetic 画一条
                    flingBehavior = rememberKineticSnapFlingBehavior(
                        rowState,
                        snapPosition = SnapPosition.Start
                    ),
                    overscrollEffect = rememberOffsetOverscrollEffect(rowState),
                    scrollIndicator = null,
                ) {
                    // 卡片是一个列表，界面只负责按类型分发：加新卡种不用动这里。
                    items(state.cards, key = { it.key }) { card ->
                        when (card) {
                            is HomeCard.Entry -> EntryCard(
                                entry = card.entry,
                                onRetry = onRetry,
                                onOpen = onOpenCollection,
                            )

                            is HomeCard.Radio -> RadioCard(
                                opening = card.opening,
                                onPlay = { playback.playRadio(card.opening) },
                            )
                        }
                    }
                }
            }

            if (state.showRecent) {
                item(key = "home_recent") {
                    VGap(36.dp)
                    SectionTitle(text = RecentShelfTitle)
                    VGap(16.dp)
                    PlaylistShelf(
                        shelf = state.recent,
                        kind = CollectionKind.ALBUM,
                        onRetry = onRetry,
                        onOpen = onOpenCollection,
                        revealNewItems = true,
                    )
                }
            }

            item(key = "home_radar") {
                VGap(36.dp)
                SectionTitle(text = RadarShelfTitle)
                VGap(16.dp)
                PlaylistShelf(
                    shelf = state.shelf,
                    kind = CollectionKind.PLAYLIST,
                    onRetry = onRetry,
                    onOpen = onOpenCollection,
                    showBadge = true
                )
            }

            item(key = "home_style") {
                VGap(36.dp)
                SectionTitle(text = state.style.dataOrNull?.title ?: "歌曲推荐")
                VGap(16.dp)
                StyleShelf(
                    picks = state.style,
                    onPlay = { songs, index ->
                        val source = state.style.dataOrNull?.let { QueueSource(title = it.title) }
                        playback.playSongs(songs, index, source)
                    },
                    onRetry = onRetry
                )
            }

            if (state.showMemories) {
                item(key = "home_memories") {
                    VGap(36.dp)
                    SectionTitle(text = state.memories.dataOrNull?.title ?: "回忆歌单")
                    VGap(16.dp)
                    MemoryPlaylistShelf(state.memories, onRetry, onOpenMemoryPlaylist)
                }
            }
        }
    }
}

@Composable
private fun HomeTitle() {
    PageHeader(
        title = "主页",
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@Composable
fun SectionTitle(
    text: String,
    horizontalPadding: Dp = ShelfPadding * 2
) {
    Text(
        text = text,
        style = MaterialTheme.type.title4Emphasized,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colors.content,
        minLines = 1,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = horizontalPadding),
    )
}

/**
 * 一张入口卡片。
 *
 * **结构恒定**：卡片壳、封面层、渐变层、文字层一直都在，接口回来的只是"封面图"和"署名"。
 * 所以加载中就是一块还没有图的底色块——标题早就写在那儿了，不摆"正在加载"，也不换布局。
 *
 * 卡片是**入口**不是内容：点它带着清洗过的标题进详情页；只有加载失败时点它才是重试。
 */
@Composable
private fun EntryCard(
    entry: HomeEntry,
    onRetry: () -> Unit,
    onOpen: OpenCollection,
    modifier: Modifier = Modifier,
    coverOverlay: (@Composable BoxScope.() -> Unit)? = null,
    meshCover: Boolean = false,
    drawShade: Boolean = true
) {
    val detail = entry.detail.dataOrNull
    val failed = entry.detail is Section.Failed
    val covers = rememberCovers(detail?.covers.orEmpty())
    val context = LocalContext.current
    val palette = koinInject<CoverPalette>()

    Box(
        modifier = modifier
            .width(CardWidth)
            .height(CardHeight)
            .clip(RoundedRectangle(20.dp))
            .background(MaterialTheme.colors.scrimNormal)
            .clickable {
                if (failed) onRetry() else onOpen(
                    entry.kind,
                    entry.id,
                    entry.title,
                    if (meshCover) covers.current.orEmpty()
                    else entry.detail.dataOrNull?.covers?.firstOrNull().orEmpty(),
                    "",
                )
            },
    ) {
        val imageModel = coverModel(covers.current, meshCover)
        // 读本地成品时请求暂未就绪；提交 null 会被 Coil 当成失败并错误地切换候选封面。
        if (imageModel != null) AsyncImage(
            model = imageModel,
            contentDescription = null,
            onError = { covers.advance() },
            onSuccess = {
                if (meshCover) covers.current?.let {
                    palette.prefetch(
                        memoryCoverPaletteKey(
                            context,
                            it
                        )
                    )
                }
            },
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )

        if (drawShade) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(CardRise * 2f)
                    .smoothGradient(
                        color = Color.Black,
                        start = 0.0f,
                        end = 1.5f,
                        intensity = 0.5f,
                    )
            )
        }
        if (coverOverlay != null) coverOverlay() else Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .graphicsLayer {
                    blendMode = BlendMode.Plus
                }
                .fillMaxWidth()
                .padding(horizontal = CardPadding)
                .padding(bottom = CardPadding),
        ) {
            Text(
                text = entry.title,
                style = MaterialTheme.type.subHeadline2,
                color = Color.White.copy(.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            VGap(2.dp)
            Text(
                text = entry.detail.credit(),
                style = MaterialTheme.type.subHeadline,
                color = Color.White.copy(alpha = 0.6f),
                minLines = CreditLines,
                maxLines = CreditLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MemoryPlaylistShelf(
    memories: Section<MemoryPlaylists>,
    onRetry: () -> Unit,
    onOpen: OpenCollection,
) {
    val rowState = rememberLazyListState()
    Box(Modifier.height(CardHeight)) {
        KineticLazyRow(
            state = rowState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = ShelfPadding),
            horizontalArrangement = Arrangement.spacedBy(ShelfPadding),
            flingBehavior = rememberKineticSnapFlingBehavior(
                rowState,
                snapPosition = SnapPosition.Start
            ),
            overscrollEffect = rememberOffsetOverscrollEffect(rowState),
            scrollIndicator = null,
        ) {
            items(memories.dataOrNull?.playlists.orEmpty(), key = { it.id }) { playlist ->
                EntryCard(
                    entry = HomeEntry(
                        playlist.title,
                        CollectionKind.PLAYLIST,
                        playlist.id,
                        Section.Ready(
                            EntryDetail(
                                listOf(playlist.cover),
                                playlist.subtitle.split('、').filter { it.isNotBlank() })
                        ),
                    ),
                    onRetry = onRetry,
                    onOpen = onOpen,
                    coverOverlay = { MemoryPlaylistLabel(playlist) },
                    meshCover = true,
                    drawShade = false
                )
            }
        }
        if (memories is Section.Failed) ShelfFailure(onRetry)
    }
}

@Composable
private fun BoxScope.MemoryPlaylistLabel(playlist: MemoryPlaylist) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        Column(modifier = Modifier.align(Alignment.BottomStart)) {
            Image(
                painter = painterResource(R.drawable.recall_zh_cn), contentDescription = null,
                modifier = Modifier
                    .padding(start = 12.dp, top = 48.dp)
                    .height(24.dp),
            )
            playlist.period?.let { period ->
                VGap(12.dp)
                val startPadding = if (period.month != null) 12.dp else 6.dp
                val today = LocalDate.now()
                val currentPeriod = period.year == today.year &&
                        (period.month == null || period.month == today.monthValue)
                val numberStyle = TextStyle(
                    color = Color.White,
                    fontSize = 96.sp,
                    lineHeight = 96.sp,
                    fontFamily = MemoryLabelFont,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = (-4).sp,
                ).let { style ->
                    if (currentPeriod) style else style.copy(
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White,
                                Color.White.copy(alpha = 0.6f)
                            )
                        ),
                    )
                }
                Row(
                    modifier = Modifier
                        .padding(start = startPadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        text = period.number,
                        style = numberStyle,
                        modifier = Modifier.alignByBaseline()
                    )
                    if (period.month != null) {
                        Text(
                            text = "月",
                            style = MaterialTheme.type.title3,
                            fontSize = 32.sp,
                            lineHeight = 40.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color.White.copy(.5f),
                            modifier = Modifier.alignByBaseline()
                        )
                    }
                }
            }
        }
    }
    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .padding(CardPadding),
    ) {
        Text(
            text = playlist.subtitle.split('、').filter { it.isNotBlank() }.toCredit(),
            style = MaterialTheme.type.subHeadline,
            color = Color.White.copy(alpha = 0.6f),
            minLines = CreditLines,
            maxLines = CreditLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.graphicsLayer { blendMode = BlendMode.Plus },
        )
    }
}


@Composable
private fun RadioCard(
    opening: RadioOpening,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = koinInject<CoverPalette>()
    // 大圆是种子歌的专辑封面，两个小圆是**艺人头像**（仓库里已经并发取好）。
    val heroCover = opening.seed.cover
    val guests = opening.guestCovers

    // 颜色：首帧同步读缓存，命中就直接算"已就绪"。
    val cached = remember(heroCover) { palette.cached(heroCover) }
    var seed by remember(heroCover) { mutableStateOf(cached) }
    var colorReady by remember(heroCover) { mutableStateOf(cached != null) }
    LaunchedEffect(heroCover) {
        if (cached == null) seed = palette.seed(heroCover)
        // 取色失败也算就绪 —— 不能因为一张封面取不出色就永远不显示这张卡。
        colorReady = true
    }

    // 三张图要**全部落地**才放行。成功和失败都算落地：挂掉一张也不能把整张卡锁死。
    val imageTotal = (if (heroCover.isEmpty()) 0 else 1) + guests.size
    var settled by remember(heroCover, guests) { mutableStateOf(emptySet<String>()) }
    val imagesReady = settled.size >= imageTotal

    // 🔴 缓存已经在手 = 这张卡以前完整显示过 → gate 从 1f 起步，一次动画都不跑（秒进）。
    // 只有真正第一次显示才等齐再淡入。gate 只在 graphicsLayer 里读，不参与组合。
    val gate = remember(heroCover) { Animatable(if (cached != null) 1f else 0f) }
    LaunchedEffect(imagesReady, colorReady) {
        if (imagesReady && colorReady && gate.value < 1f) {
            gate.animateTo(1f, tween(RadioFadeMillis, easing = FastOutSlowInEasing))
        }
    }

    val fallback = MaterialTheme.colors.cardBackground
    val artist = opening.seed.artists.firstOrNull().orEmpty()

    Box(
        modifier = modifier
            .width(CardWidth)
            .height(CardHeight)
            .clip(RoundedRectangle(20.dp))
            .clickable(onClick = onPlay),
    ) {
        // 渐变底 + 三张图是**一整块**：一起等齐、一起淡入。文字在它外面，始终可见。
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = gate.value }
                .drawBehind {
                    val topColor = seed?.let { Color(it.primaryTone(RadioTopTone)) } ?: fallback
                    val bottomColor =
                        seed?.let { Color(it.primaryTone(RadioBottomTone)) } ?: fallback
                    drawRect(Brush.verticalGradient(listOf(topColor, bottomColor)))
                },
        ) {
            if (heroCover.isNotEmpty()) {
                RadioCircle(heroCover, size = 112.dp, x = 16.dp, y = 16.dp) {
                    settled = settled + "hero"
                }
            }
            guests.getOrNull(0)?.let { url ->
                RadioCircle(url, size = 80.dp, x = 120.dp, y = 88.dp) {
                    settled = settled + "guest0"
                }
            }
            guests.getOrNull(1)?.let { url ->
                RadioCircle(url, size = 64.dp, x = 56.dp, y = 136.dp) {
                    settled = settled + "guest1"
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = CardPadding)
                .padding(bottom = CardPadding)
                .graphicsLayer(blendMode = BlendMode.Plus),
        ) {
            Text(
                text = "最近七天最常听",
                style = MaterialTheme.type.subHeadline2,
                color = Color.White.copy(alpha = 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            VGap(2.dp)
            Text(
                text = if (artist.isEmpty()) "为你电台" else "$artist 与类似艺人",
                style = MaterialTheme.type.body,
                color = Color.White.copy(.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 电台卡上的一枚圆形艺人/封面图。位置与直径都是按参考图的比例摊到 [CardWidth] 上的。
 *
 * [onSettled] 在**成功或失败**时都回调一次：整张卡的入场动画要等三张图都落地，
 * 挂掉的那张也必须算落地，否则卡片永远不显示。
 */
@Composable
private fun BoxScope.RadioCircle(
    url: String,
    size: Dp,
    x: Dp,
    y: Dp,
    onSettled: () -> Unit = {},
) {
    AsyncImage(
        model = rememberCoverRequest(url),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onSuccess = { onSettled() },
        onError = { onSettled() },
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(x = x, y = y)
            .size(size)
            .clip(CircleShape),
    )
}

@Composable
private fun PlaylistShelf(
    shelf: Section<List<ShelfCard>>,
    kind: CollectionKind,
    onRetry: () -> Unit,
    onOpen: OpenCollection,
    showBadge: Boolean = false,
    revealNewItems: Boolean = false,
) {
    val listState = rememberLazyListState()
    Box {
        KineticLazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = ShelfPadding),
            horizontalArrangement = Arrangement.spacedBy(ShelfPadding),
            flingBehavior = rememberKineticSnapFlingBehavior(
                listState,
                snapPosition = SnapPosition.Start,
            ),
            overscrollEffect = rememberOffsetOverscrollEffect(listState),
            scrollIndicator = null,
        ) {
            items(shelf.dataOrNull.orEmpty(), key = { it.id }) { card ->
                PlaylistCard(
                    card = card,
                    showBadge = showBadge,
                    onClick = {
                        onOpen(
                            kind,
                            card.id,
                            card.title,
                            card.covers.firstOrNull().orEmpty(),
                            if (kind == CollectionKind.ALBUM) card.subtitle.orEmpty() else "",
                        )
                    },
                )
            }
        }

        if (shelf is Section.Failed) {
            ShelfFailure(onRetry)
        }
    }

    if (revealNewItems) {
        LaunchedEffect(shelf.dataOrNull) {
            // LazyRow 按「第一个可见 item 的 key」锚定滚动位置：新卡插进队首时，
            // 旧首卡原地不动、视口左让一位，新卡就落在画面外了。用户还停在开头
            // 附近时滚回去把它亮出来；已经滑到深处就看也不看，不抢用户的手。
            if (listState.firstVisibleItemIndex <= 1) {
                listState.animateScrollToItem(0)
            }
        }
    }
}

/**
 * 「曲风推荐」横滑带：小标题 + 一列三首的歌曲列表。
 *
 * 小标题是接口给的（「听过的 Tritonal 为你定制」），所以加载中它先是空的——
 * 但那一行的位置一直在，标题行、带子高度都不变。
 *
 * 点某一首会把**整块**歌变成播放队列，从这一首开始往下听。
 */
@Composable
private fun StyleShelf(
    picks: Section<StylePicks>,
    onPlay: (List<Song>, Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val picksData = picks.dataOrNull
    val columns = picksData?.columns.orEmpty()
    val songs = picksData?.songs.orEmpty()

    Box(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        val rowState = rememberLazyListState()
        KineticLazyRow(
            state = rowState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = ShelfPadding),
            horizontalArrangement = Arrangement.spacedBy(ShelfPadding),
            flingBehavior = rememberKineticSnapFlingBehavior(
                rowState,
                snapPosition = SnapPosition.Start,
            ),
            overscrollEffect = rememberOffsetOverscrollEffect(rowState),
            scrollIndicator = null,
        ) {
            itemsIndexed(columns) { index, column ->
                // 整块是一个队列，所以点第几列第几首要换算成这批歌里的第几首。
                val start = columns.take(index).sumOf { it.size }
                SongColumn(
                    column = column,
                    startIndex = start,
                    songs = songs,
                    onPlay = onPlay,
                )
            }
        }

        if (picks is Section.Failed) {
            ShelfFailure(onRetry)
        }
    }
}

/** 某个区拉不到内容时的提示，摆在横滑带的起点。 */
@Composable
private fun BoxScope.ShelfFailure(onRetry: () -> Unit) {
    Text(
        text = "加载失败 · 点按重试",
        style = MaterialTheme.type.footnote,
        color = MaterialTheme.colors.contentVariant,
        modifier = Modifier
            .align(Alignment.CenterStart)
            .padding(horizontal = ShelfPadding)
            .clickable { onRetry() },
    )
}

/** 正方形封面 + 下方歌单名（+ 可选的一行小字）。整张卡就是详情页入口。 */
@Composable
private fun LazyItemScope.PlaylistCard(
    card: ShelfCard,
    showBadge: Boolean = false,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val covers = rememberCovers(card.covers)

    CollectionCard(
        title = card.title,
        // 雷达歌单没有可用副标题，留空才能保持卡片带的原有高度。
        subtitle = card.subtitle,
        model = rememberCoverRequest(covers.current),
        onError = { covers.advance() },
        onClick = onClick,
        modifier = modifier
            .animateItem(placementSpec = Springs.crisp())
            .width(PlaylistCardSize),
    ) {
        if (showBadge) {
            Image(
                painter = painterResource(R.drawable.cirro_logo_badge),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(20.dp)
            )
        }
    }
}

/** 曲风推荐的一列：三首叠着放，行高全部对齐封面。 */
@Composable
private fun SongColumn(
    column: List<Song>,
    startIndex: Int,
    songs: List<Song>,
    onPlay: (List<Song>, Int) -> Unit,
) {
    val width = LocalWindowInfo.current.containerDpSize.width - 48.dp
    Column(
        modifier = Modifier.width(width),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        column.forEachIndexed { index, song ->
            SongRow(
                song = song,
                onClick = { onPlay(songs, startIndex + index) },
            )
        }
    }
}

/**
 * 一列里的一首歌：封面 + 歌名/歌手 + 右侧播放键。
 *
 * 整行和右边那颗键走的是**同一个动作**（从这首起播整块队列）——按钮只是把"这儿能点"
 * 明摆出来，不是另一个入口。哪天整行改成进歌曲页，这两个才需要分家。
 */
@Composable
private fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedRectangle(8.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .fillMaxHeight()
                .clip(RoundedRectangle(8.dp)),
        ) {
            AsyncImage(
                model = rememberCoverRequest(song.cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            PlayingCoverIndicator(rememberIsCurrentSong(song.id), Modifier.matchParentSize())
        }
        HGap(12.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.name,
                style = MaterialTheme.type.subHeadline,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artistText,
                style = MaterialTheme.type.footnote,
                color = MaterialTheme.colors.contentVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CircleIconButton(
            iconRes = R.drawable.play_fill,
            label = "播放",
            background = Color.Transparent,
            tint = MaterialTheme.colors.content,
            onClick = onClick,
        )
    }
}

/**
 * 封面请求：按卡片真实的像素尺寸要图，再挂上倒影变换。
 *
 * 要的尺寸就是 [CardWidth] × [CardHeight]，而变换的输出宽度等于输入宽度、高度是宽度的 4/3，
 * 于是输出跟布局盒子一个像素对一个像素，不会被二次缩放。地址为空时返回 null，
 * Coil 什么都不画，露出卡片底色。
 */
@Composable
private fun coverModel(url: String?, meshCover: Boolean = false): Any? {
    val context = LocalContext.current
    val density = LocalDensity.current
    val width = with(density) { CardWidth.roundToPx() }
    val height = with(density) { CardHeight.roundToPx() }

    if (meshCover) {
        if (url.isNullOrEmpty()) return null
        return rememberMemoryCoverRequest(
            url, width, height,
            ReflectionTransformation(
                context, squareDp = CardWidth.value, riseDp = CardRise.value,
                bleedDp = CardRise.value * 0.5f,
            ),
        )
    }

    return remember(url, width, height) {
        url?.let {
            ImageRequest.Builder(context)
                .data(it)
                .size(width, height)
                .transformations(
                    listOf(
                        ReflectionTransformation(
                            context = context,
                            squareDp = CardWidth.value,
                            riseDp = CardRise.value,
                            bleedDp = CardRise.value * 0.5f
                        ),
                    )
                )
                .crossfade(true)
                .build()
        }
    }
}

/**
 * 一张封面的候选表，外加"现在试到第几张"。
 *
 * 当前该加载哪张要走 [current] 拿，**由调用方在自己的组合里读** —— Compose 只在"读状态的
 * 那个作用域"里重组：要是这里先把 `index` 读成一个 `url` 再返回，换图那一下只会重组本函数
 * 自己那个组，调用方那一层不重组，`AsyncImage` 拿到的还是旧模型，"顺延"就不会生效。
 *
 * 网易云的专辑图**不保证还在 CDN 上**：实测「时光雷达」首曲那张返回 404，接口却照样下发
 * URL。所以判空字符串是不够的，只有 Coil 真的加载失败才算数 —— [advance] 就挂在
 * `AsyncImage` 的 `onError` 上。
 */
private class CoverActualCandidates(
    private val urls: List<String>,
    private val index: MutableIntState,
) {
    /** 当前该加载的那张。全挂完为 null，Coil 走 fallback（什么都不画）。 */
    val current: String? get() = urls.getOrNull(index.intValue)

    /** 当前这张图挂了，换下一张。已经是最后一张就什么都不做，所以不会死循环。 */
    fun advance() {
        if (index.intValue < urls.lastIndex) index.intValue++
    }
}

/**
 * 把候选封面 [covers] 包成一个可顺延的封面位。
 *
 * 顺序就是优先级，从第一张往后试；列表内容一变（换歌单、刷新）就从头再试 ——
 * `remember` 的 key 用列表本身，而它来自 data class，内容一样就算同一个 key，
 * 重组不会把"已经试到第几张"打回去。
 */
@Composable
private fun rememberCovers(covers: List<String>): CoverActualCandidates {
    val urls = remember(covers) { covers.filter { it.isNotEmpty() } }
    val index = remember(urls) { mutableIntStateOf(0) }
    return remember(urls, index) { CoverActualCandidates(urls, index) }
}

/** 署名那行随状态变，但始终占着同样的位置（恒定 [CreditLines] 行）。 */
@Composable
private fun Section<EntryDetail>.credit(): String = when (this) {
    is Section.Ready -> data.artists.toCredit()
    is Section.Failed -> "加载失败 · 点按重试"
    Section.Loading -> Placeholder
}

/**
 * 把歌手名字排成署名：用分隔符连接，**最多 [CreditLines] 行**，末尾挂一个「等」。
 *
 * 不能按个数截 —— 名字长短差得远（"周杰伦" 三个字，一串英文能占半行），固定取几个会让
 * 有的卡空半行、有的卡溢出。所以这里**按排版量**来：名字一个个往里加，量到"加上它再挂个等
 * 就超行数"就停手。量一次是纯文本布局、不落绘制，而且结果进 [remember] 就不再算。
 *
 * 还有一处要照顾：前面几个名字刚好把上面的行填满时，「等」会被挤到下一行**孤零零一个**。
 * 遇到这种就再从剩下的名字里挑一个**最短的**塞进去 —— 短的才保证能和「等」待在同一行。
 *
 * 拼接和截断只发生在这里，数据层给的是拆开的名字（[EntryDetail.artists]）。
 */
@Composable
private fun List<String>.toCredit(): String {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.type.footnote
    val width = with(LocalDensity.current) { (CardWidth - CardPadding * 2).roundToPx() }

    return remember(this, style, width) {
        if (isEmpty()) return@remember ""

        val constraints = Constraints(maxWidth = width)

        fun layoutOf(text: String) = measurer.measure(
            text = AnnotatedString(text),
            style = style,
            maxLines = CreditLines,
            constraints = constraints,
        )

        /** [text] 排得进 [CreditLines] 行，且末行不是孤零零一个「等」。 */
        fun isTidy(text: String): Boolean {
            val layout = layoutOf(text)
            return !layout.hasVisualOverflow && layout.getLineStart(layout.lineCount - 1) != text.lastIndex
        }

        val builder = StringBuilder()
        var count = 0

        for (name in this) {
            val candidate = if (count == 0) name else builder.toString() + CreditSeparator + name
            val probe = layoutOf(candidate + CreditSuffix)

            // 第一个名字再长也收下（否则署名整行空着）；之后一溢出就停手。
            if (count > 0 && probe.hasVisualOverflow) break

            if (count == 0) builder.append(name) else builder.append(CreditSeparator).append(name)
            count++

            // 行数已经占满，再多放一个就溢出了。
            if (probe.lineCount >= CreditLines) break
        }

        if (count > 0 && !isTidy(builder.toString() + CreditSuffix)) {
            val prefix = builder.toString() + CreditSeparator
            val companion = drop(count)
                .filter { isTidy(prefix + it + CreditSuffix) }
                .minByOrNull { it.length }
            if (companion != null) builder.append(CreditSeparator).append(companion)
        }

        builder.append(CreditSuffix).toString()
    }
}

/** 未登录时摆在首页的登录入口。 */
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
