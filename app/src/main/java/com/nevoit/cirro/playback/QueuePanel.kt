package com.nevoit.cirro.playback

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.nevoit.cirro.R
import com.nevoit.cirro.data.model.PlayMode
import com.nevoit.cirro.data.model.Song
import com.nevoit.material.core.component.HGap
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.interaction.overscroll.OffsetOverscrollEffect
import com.nevoit.material.core.interaction.rememberFlingBehavior
import com.nevoit.material.core.modifier.drawBackground
import com.nevoit.material.theme.MaterialTheme

private val RowMinHeight = 56.dp

/** 行首封面。 */
private val CoverSize = 48.dp

private val CoverCorner = 4.dp

/** 封面和文字之间的间距（跟 [HGap] 的默认值一致）。 */
private val CoverGap = 12.dp

/** 封面列占掉的横向宽度：文字那半照这个留位，两半才对得齐。 */
private val CoverAdvance = CoverSize + CoverGap

/**
 * 列表**上**边缘的渐隐长度（长的话列表可用区就窄了，40dp 差不多是一行多一点）。
 *
 * 上和下是分开调的：列表挂在按钮排下面，上边缘顶着那颗按钮、下边缘也许要压到屏幕底，
 * 两边想要的过渡长度本来就不一样。
 */
private val TopEdgeFade = 12.dp

/** 列表**下**边缘的渐隐长度。 */
private val BottomEdgeFade = 56.dp

private val ButtonHeight = 48.dp

private val ButtonGap = 8.dp

private val ButtonIconSize = 24.dp

private const val HistoryHeaderKey = "queue_history_header"
private const val ContinueHeaderKey = "queue_continue_header"

internal data class QueueSection(
    val headerKey: String,
    val nextHeaderKey: String?,
    val firstRowIndex: Int,
    val lastRowIndex: Int,
)

internal data class QueueFadeViewport(
    val top: Float,
    val bottom: Float,
    val overhangAbove: Float,
    val overhangBelow: Float,
) {
    val height: Float get() = bottom - top
}

/**
 * 播放页的队列面板，历史和当前及后续歌曲共用一个滚动列表。
 *
 * 队列状态由面板自己订阅，避免插播或切歌时让播放页顶层跟着重组。
 */
@Composable
internal fun QueuePanel(
    connection: PlaybackConnection,
    buttonBackground: ColorProducer,
    buttonIcon: ColorProducer,
    activeButtonBackground: ColorProducer,
    activeButtonIcon: ColorProducer,
    modifier: Modifier = Modifier,
    panelAlpha: () -> Float = { 1f },
    panelScale: () -> Float = { 1f },
    onContentReady: () -> Unit = {},
) {
    val queue by connection.queue.collectAsState()
    val entries = queue.entries
    val historySize = queue.currentIndex.coerceIn(0, entries.size)
    val history = remember(entries, historySize) { entries.take(historySize) }
    val continuation = remember(entries, historySize) { entries.drop(historySize) }
    val historySection = remember(historySize) {
        QueueSection(HistoryHeaderKey, ContinueHeaderKey, 1, historySize)
    }
    val continueHeaderIndex = if (history.isEmpty()) 0 else history.size + 1
    val continueSection = remember(continueHeaderIndex, continuation.size) {
        QueueSection(
            ContinueHeaderKey, null, continueHeaderIndex + 1,
            continueHeaderIndex + continuation.size.coerceAtLeast(1),
        )
    }
    val mode = queue.mode
    val shuffle = queue.shuffle

    // 回调按状态 remember：不固定的话每次重组都是新 lambda，LazyColumn 那一堆行的跳过全失效。
    val onToggleShuffle = remember(connection, shuffle) { { connection.setShuffle(!shuffle) } }
    val onCycleLoop = remember(connection, mode) { { connection.setLoopMode(mode.nextLoop()) } }
    val onInfinite = remember { {} }

    // 行要按"自己滚到了哪儿"算渐隐系数，所以列表状态得由面板持有、下发给每一行。
    // 当前歌紧跟“继续播放”标题；从标题开始，避免吸顶标题遮住当前行。
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = continueHeaderIndex)
    val overscrollEffect = rememberOverscrollEffect()
    val latestReady = rememberUpdatedState(onContentReady)
    val readyReported = remember { booleanArrayOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                // Alpha belongs to each blend layer; an outer buffer would isolate Plus from the backdrop.
                val scale = panelScale().coerceAtLeast(0f)
                scaleX = scale
                scaleY = scale
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ButtonHeight),
            horizontalArrangement = Arrangement.spacedBy(ButtonGap),
        ) {
            PanelButton(
                iconRes = R.drawable.shuffle,
                label = "随机播放",
                active = shuffle,
                background = buttonBackground,
                tint = buttonIcon,
                activeBackground = activeButtonBackground,
                activeTint = activeButtonIcon,
                onClick = onToggleShuffle,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer { alpha = panelAlpha().coerceIn(0f, 1f) },
            )
            PanelButton(
                iconRes = mode.buttonIconRes(),
                label = mode.buttonLabel(),
                active = mode != PlayMode.SEQUENTIAL,
                background = buttonBackground,
                tint = buttonIcon,
                activeBackground = activeButtonBackground,
                activeTint = activeButtonIcon,
                onClick = onCycleLoop,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer { alpha = panelAlpha().coerceIn(0f, 1f) },
            )
            // 无限播放还没做，先摆着：按下有反馈，什么都不做。
            PanelButton(
                iconRes = R.drawable.infinity,
                label = "无限播放",
                active = false,
                background = buttonBackground,
                tint = buttonIcon,
                activeBackground = activeButtonBackground,
                activeTint = activeButtonIcon,
                onClick = onInfinite,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer { alpha = panelAlpha().coerceIn(0f, 1f) },
            )
        }
        VGap()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        // LazyColumn composes its visible rows during measurement.
                        if (!readyReported[0] && coordinates.size.width > 0 && coordinates.size.height > 0) {
                            readyReported[0] = true
                            latestReady.value()
                        }
                    },
                flingBehavior = rememberFlingBehavior(),
                overscrollEffect = overscrollEffect,
            ) {
                if (history.isNotEmpty()) {
                    stickyHeader(key = HistoryHeaderKey) {
                        QueueHeader(
                            "播放历史",
                            HistoryHeaderKey,
                            listState,
                            overscrollEffect,
                            panelAlpha
                        )
                    }
                    items(history, key = { it.uid }, contentType = { "song" }) { entry ->
                        QueueRow(
                            song = entry.song,
                            rowKey = entry.uid,
                            listState = listState,
                            section = historySection,
                            overscrollEffect = overscrollEffect,
                            panelAlpha = panelAlpha,
                        )
                    }
                }
                stickyHeader(key = ContinueHeaderKey) {
                    QueueHeader(
                        "继续播放", ContinueHeaderKey, listState, overscrollEffect, panelAlpha,
                        subtitle = queue.source?.title?.takeIf { it.isNotBlank() }
                            ?.let { "来自 $it" },
                    )
                }
                if (continuation.isEmpty()) {
                    item(key = "queue_continue_empty") {
                        QueueEmptySection(
                            "暂无待播歌曲",
                            listState,
                            continueSection,
                            overscrollEffect,
                            panelAlpha,
                        )
                    }
                }
                items(continuation, key = { it.uid }, contentType = { "song" }) { entry ->
                    QueueRow(
                        song = entry.song,
                        rowKey = entry.uid,
                        listState = listState,
                        section = continueSection,
                        overscrollEffect = overscrollEffect,
                        panelAlpha = panelAlpha,
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueHeader(
    title: String,
    headerKey: String,
    listState: LazyListState,
    overscrollEffect: OverscrollEffect?,
    panelAlpha: () -> Float,
    subtitle: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .headerEdgeFade(listState, headerKey, overscrollEffect, panelAlpha)
            .semantics { heading() }
            .padding(vertical = 12.dp),
    ) {
        QueueText(
            title,
            MaterialTheme.type.title4.copy(fontWeight = FontWeight.Medium),
            Color.White
        )
        if (subtitle != null) {
            VGap(2.dp)
            QueueText(subtitle, MaterialTheme.type.subHeadline2, Color.White.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun QueueEmptySection(
    text: String,
    listState: LazyListState,
    section: QueueSection,
    overscrollEffect: OverscrollEffect?,
    panelAlpha: () -> Float,
) {
    val rowKey = "queue_continue_empty"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .edgeFade(listState, rowKey, BlendMode.Plus, section, overscrollEffect, panelAlpha),
        contentAlignment = Alignment.CenterStart,
    ) {
        QueueText(text, MaterialTheme.type.subHeadline2, Color.White.copy(alpha = 0.4f))
    }
}

private val OverscrollEffect?.visualOffsetY: Float
    get() = (this as? OffsetOverscrollEffect)?.visualOffset?.y ?: 0f

private fun Modifier.headerEdgeFade(
    listState: LazyListState,
    headerKey: String,
    overscrollEffect: OverscrollEffect?,
    panelAlpha: () -> Float,
): Modifier = this
    .graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
        blendMode = BlendMode.Plus
        alpha = panelAlpha().coerceIn(0f, 1f)
    }
    .drawWithContent {
        val layout = listState.layoutInfo
        val header = layout.visibleItemsInfo.firstOrNull { it.key == headerKey }
            ?: return@drawWithContent
        val visualOffset = overscrollEffect.visualOffsetY
        val top = layout.viewportStartOffset - visualOffset
        val bottom = layout.viewportEndOffset - visualOffset
        val viewportHeight = bottom - top
        val clipTop = (top - header.offset).coerceAtLeast(0f)
        val clipBottom = (bottom - header.offset).coerceAtMost(size.height)
        if (viewportHeight <= 0f || clipBottom <= clipTop) return@drawWithContent

        val scale = fadeScale(viewportHeight, TopEdgeFade.toPx(), BottomEdgeFade.toPx())
        // 正常吸顶时不淡化标题，只在被下一个标题推出顶部时展开上渐隐带。
        val topFade = minOf(TopEdgeFade.toPx() * scale, (top - header.offset).coerceAtLeast(0f))
        val last = layout.visibleItemsInfo.maxByOrNull { it.index }
        val overhangBelow = when {
            last == null -> 0f
            last.index < layout.totalItemsCount - 1 -> Float.POSITIVE_INFINITY
            else -> (last.offset + last.size - bottom).coerceAtLeast(0f)
        }
        val bottomFade = minOf(BottomEdgeFade.toPx() * scale, overhangBelow)
        clipRect(top = clipTop, bottom = clipBottom) {
            this@drawWithContent.drawContent()
            val brush = rowFadeBrush(
                offset = header.offset - top,
                size = header.size.toFloat(),
                viewportHeight = viewportHeight,
                topFade = topFade,
                bottomFade = bottomFade,
            )
            if (brush != null) drawRect(brush = brush, blendMode = BlendMode.DstIn)
        }
    }

/**
 * 待播列表的一行：**左半是封面、右半是文字 + 图标**，两半各是一个边缘渐隐层。
 *
 * 🔴 分成两层不是为了排版，是**为了合成**：右半要跟屏幕底色做 `Plus` 叠加（见 [edgeFade]），
 * 而封面不能——照片被加性混一下就洗白了。`Plus` 是"这一层贴回屏幕时怎么合成"，所以**要叠加的
 * 像素必须自己独占一层**，同一个层里做不到一半 SrcOver 一半 Plus。
 *
 * 两层按同一套规则算渐隐系数（都看这一行在列表视口里的位置），所以边缘是连着的、没有接缝。
 * 🔴 **右半必须在左半之后画**：它叠加的底是"屏幕 + 左半刚贴回去的内容"。
 */
@Composable
private fun QueueRow(
    song: Song,
    rowKey: Any,
    listState: LazyListState,
    section: QueueSection,
    overscrollEffect: OverscrollEffect?,
    panelAlpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight),
    ) {
        // 左半：封面。贴回屏幕照常 SrcOver，没落进渐隐带就连缓冲都不用建。
        Box(
            modifier = Modifier
                .matchParentSize()
                .edgeFade(
                    listState,
                    rowKey,
                    BlendMode.SrcOver,
                    section,
                    overscrollEffect,
                    panelAlpha
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            AsyncImage(
                model = queueCoverRequest(song.cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(CoverSize)
                    .clip(RoundedRectangle(CoverCorner)),
            )
        }

        // 右半：文字 + 图标，以 `Plus` 贴回屏幕。行高也由它撑起来（左半是 matchParentSize，不参与）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RowMinHeight)
                .edgeFade(listState, rowKey, BlendMode.Plus, section, overscrollEffect, panelAlpha),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HGap(CoverAdvance)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp)
            ) {
                QueueText(
                    text = song.name,
                    style = MaterialTheme.type.body,
                    color = Color.White,
                    maxLines = 1,
                )
                QueueText(
                    text = song.artistText,
                    style = MaterialTheme.type.subHeadline2,
                    color = Color.White.copy(.4f),
                )
            }
        }
    }
}

/**
 * 一行的**边缘渐隐层**：上下边缘淡出，同时（右半）以 `Plus` 贴回屏幕。
 *
 * `compositingStrategy`（能不能在缓冲里做遮罩）和 `blendMode`（这一层贴回屏幕时怎么合成）是
 * 同一个 `graphicsLayer` 上两个**正交**的属性，所以两件事能同时成立：
 *
 * - **渐隐**：内容先画进离屏缓冲，再在缓冲里铺一道上下渐变的 `DstIn` —— 它只乘缓冲里的像素
 *   （预乘 RGB 和 alpha 一起缩），碰不到底下的东西，所以只能让这一层的内容淡出。
 * - **`Plus`**：`restore` 之后这块缓冲怎么贴回屏幕，跟遮罩互不干扰（`Plus` 是
 *   `min(1, 源 + 底)`，源被遮罩缩放多少，叠加量就少多少 —— 淡出与叠加天然一致）。
 *
 * 于是淡出是**逐像素连续**的，不是"整行调个 alpha"那种台阶。
 *
 * 🔴 **`Plus` 那半必须常驻离屏**：`blendMode` 就是它的本职，掉了层叠加就没了。只有 SrcOver 那半
 * 能在没落进渐隐带时直通，省掉那层缓冲。
 *
 * 🔴 渐隐系数按**这一行在列表视口里的位置**算，所以只能在图层段 / 绘制段读 —— 跟播放页
 * "`progress` 只在 draw/layer 读"是同一条规矩；读在组合期就是滚一下重组整个列表。
 *
 * 🔴 每个分区的视口从自己的标题下沿延伸到下一个标题或列表底部（[fadeViewport]）。
 * 带宽按那一端被裁掉的歌曲内容展开；完整显示的首尾行不淡出，滚入边界时裁剪线处收到 α = 0。
 *
 * ⚠️ 反着做（带宽固定、只把系数整体往 1 抬）看着像等价，其实不是：裁剪线处的 α 会被抬到
 * `1 - gate`，被父级切掉的那半行就成了"半透明地被截断"，硬切口感官上更难看。带宽收到 0 就不存在
 * 这个问题 —— 那里压根没有带。
 */
private fun Modifier.edgeFade(
    listState: LazyListState,
    rowKey: Any,
    blendMode: BlendMode,
    section: QueueSection,
    overscrollEffect: OverscrollEffect?,
    panelAlpha: () -> Float,
): Modifier = this
    .graphicsLayer {
        this.blendMode = blendMode
        alpha = panelAlpha().coerceIn(0f, 1f)
        // `Auto` 只在"这行离两端都足够远、绘制段肯定不会铺遮罩"时才给（见 isNearEdge）。
        // ⚠️ 方向不能反：给了 `Auto`（没有独立缓冲）却铺了 `DstIn`，遮罩会直接作用在屏幕画布上，
        // 把底下的内容连背景一起擦掉。所以这个判据只允许**偏保守**（多建一层缓冲），不许偏激进。
        compositingStrategy =
            if (blendMode == BlendMode.SrcOver &&
                !listState.isNearEdge(
                    rowKey, section, overscrollEffect.visualOffsetY,
                    TopEdgeFade.toPx(), BottomEdgeFade.toPx(),
                )
            ) {
                CompositingStrategy.Auto
            } else {
                CompositingStrategy.Offscreen
            }
    }
    .drawWithContent {
        val layoutInfo = listState.layoutInfo
        val info = layoutInfo.visibleItemsInfo.firstOrNull { it.key == rowKey }
            ?: return@drawWithContent
        val viewport = layoutInfo.fadeViewport(section, overscrollEffect.visualOffsetY)
        val viewportHeight = viewport.height
        val clipTop = (viewport.top - info.offset).coerceAtLeast(0f)
        val clipBottom = (viewport.bottom - info.offset).coerceAtMost(size.height)
        if (viewportHeight <= 0f || clipBottom <= clipTop) return@drawWithContent
        // 两条带各按各的上限来；总长赶上视口时等比收窄，保证中间还剩一段通透区。
        val scale = fadeScale(viewportHeight, TopEdgeFade.toPx(), BottomEdgeFade.toPx())
        // 🔴 再按"那一端被裁掉多少"把带宽收一下：滚到顶/底那端收到 0（整条带消失），有溢出时
        // 带宽 = 溢出量 —— 带始终从视口边起算，所以裁剪线处恒为 0，切口是透明的。
        val topFade = minOf(TopEdgeFade.toPx() * scale, viewport.overhangAbove)
        val bottomFade = minOf(BottomEdgeFade.toPx() * scale, viewport.overhangBelow)
        // 标题保持透明；即使渐隐带收到了 0，歌曲也不能画进标题占据的区域。
        clipRect(top = clipTop, bottom = clipBottom) {
            this@drawWithContent.drawContent()
            val brush = rowFadeBrush(
                offset = info.offset - viewport.top,
                size = info.size.toFloat(),
                viewportHeight = viewportHeight,
                topFade = topFade,
                bottomFade = bottomFade,
            )
            if (brush != null) drawRect(brush = brush, blendMode = BlendMode.DstIn)
        }
    }

/**
 * 这一行内部的渐隐遮罩。整行都没落进渐隐带时回 `null`（这行不用遮罩，连矩形都省了）。
 *
 * 🔴 **系数定义在视口坐标上，得先搬进行内坐标再取色标** —— 不能只算行两端的系数、再在行内
 * 线性插值。行整个在视口里时两者碰巧等价（渐变本来就是线性的），但**行只露出半截时就错了**：
 * 那时行的一端已经跑到渐隐带外面，`coerceIn` 会把它的系数钉死在 0 / 1，斜率随之被改掉 ——
 * 露出来的那半截会明显偏亮（该透明的地方还留着一大截不透明度）。
 *
 * 做法：把整条遮罩的四个折点（视口顶 α=0 → 上带终点 α=1 → 下带起点 α=1 → 视口底 α=0）在行内
 * 的位置都求出来，连同行两端一起排序，逐点算系数当色标。函数是分段线性的，端点值就是全部信息，
 * 插值出来和整块遮罩逐像素一致，行与行之间也没有接缝。
 *
 * [offset] 是行顶相对视口顶的位置（滚出顶部时为负），[size] 是整行高度（不是可见高度）。
 * 两条带的宽度**分开给**，且必须已经收过两次：先 [fadeScale] 收窄，再按溢出量收（见 [edgeFade]）。
 * 这里只管照折点算遮罩，不校验宽度。
 */
private fun rowFadeBrush(
    offset: Float,
    size: Float,
    viewportHeight: Float,
    topFade: Float,
    bottomFade: Float,
): Brush? {
    fun alphaAt(y: Float) = rowFadeAlphaAt(y, offset, topFade, bottomFade, viewportHeight)

    if (alphaAt(0f) >= 1f && alphaAt(size) >= 1f) return null

    // 折点在行内的纵坐标（行顶 = 0）；只留落在行内的，跟行的两端一起当色标。
    val topEnd = topFade - offset
    val bottomStart = viewportHeight - bottomFade - offset
    val viewportBottom = viewportHeight - offset
    val stops = listOf(0f, -offset, topEnd, bottomStart, viewportBottom, size)
        .filter { it in 0f..size }
        .distinct()
        .sorted()
        .map { y -> y / size to Color.Black.copy(alpha = alphaAt(y)) }
    // 色标位置不等分，只能走 colorStops 这一路；`startY`/`endY` 取默认 = 整个绘制区（行高）。
    return Brush.verticalGradient(*stops.toTypedArray())
}

/**
 * 行内纵坐标 [y]（行顶 = 0）处的渐隐系数。整条遮罩就四个折点：
 * 视口顶 α=0 → 上带终点 α=1 →（中间通透）→ 下带起点 α=1 → 视口底 α=0。
 *
 * 🔴 带宽是**从视口边起算**的，所以裁剪线处恒为 0：父级（LazyColumn）就在那里把这一行切掉，
 * 断口是透明的，看不到硬边。行只露半截时尤其明显 —— 那半截的顶端正好压在裁剪线上。
 *
 * ⚠️ 带宽为 0（滚到头的那一端）时，它那个分支的条件会和前一个分支完全重合
 * （`topEnd == -offset`），永远命中不到 —— 但**下面两个取商的分支都必须自带 `> 0f` 守卫**：
 * `y < -offset` 是严格小于，边界那一像素（`y == -offset`）会落到带里来，那时除数是 0。
 */
private fun rowFadeAlphaAt(
    y: Float,
    offset: Float,
    topFade: Float,
    bottomFade: Float,
    viewportHeight: Float,
): Float {
    val topEnd = topFade - offset                            // 视口 y = topFade
    val bottomStart = viewportHeight - bottomFade - offset    // 视口 y = viewportHeight - bottomFade
    val viewportBottom = viewportHeight - offset             // 视口 y = viewportHeight
    return when {
        y < -offset -> 0f                                       // 还在视口上边之外
        topFade > 0f && y <= topEnd -> (y + offset) / topFade   // 上渐隐带里
        y <= bottomStart -> 1f                                  // 中间通透段
        bottomFade > 0f && y <= viewportBottom -> (viewportBottom - y) / bottomFade
        else -> 0f                                              // 已过视口下边
    }
}

/**
 * 两条渐隐带总长超过视口高度时，等比收窄要用到的系数（没超就是 1）。
 *
 * 中间必须剩一段通透区：两带一旦相交（上带的下沿跑到下带下沿之后），折点顺序就乱了、遮罩不再
 * 单调。收窄在"按溢出量收带宽"**之前**做，两处入参一致，结果自然一致。
 */
private fun fadeScale(viewportHeight: Float, topFade: Float, bottomFade: Float): Float =
    if (topFade + bottomFade <= viewportHeight) 1f else viewportHeight / (topFade + bottomFade)

/**
 * 吸顶标题会被下一个标题推出视口，必须使用布局里的实际偏移，不能假设标题始终位于 0。
 * 溢出量只统计本分区的歌曲行；把吸顶项算进去会让完整显示的首行也被淡出。
 * overscroll 在外层平移内容，layoutInfo 不包含这段位移；把固定视口反向移回内容坐标，
 * 标题边界就能随内容移动，而屏幕边缘的渐隐和裁剪保持固定。
 */
internal fun LazyListLayoutInfo.fadeViewport(
    section: QueueSection,
    visualOffsetY: Float
): QueueFadeViewport {
    val start = viewportStartOffset - visualOffsetY
    val end = viewportEndOffset - visualOffsetY
    var top = start
    var bottom = end
    var first: LazyListItemInfo? = null
    var last: LazyListItemInfo? = null
    for (item in visibleItemsInfo) {
        if (item.key == section.headerKey) {
            top = (item.offset + item.size).toFloat().coerceIn(start, end)
        }
        if (item.key == section.nextHeaderKey) {
            bottom = item.offset.toFloat().coerceIn(start, end)
        }
        if (item.index in section.firstRowIndex..section.lastRowIndex) {
            if (first == null || item.index < first.index) first = item
            if (last == null || item.index > last.index) last = item
        }
    }
    bottom = bottom.coerceAtLeast(top)
    val above = when {
        first == null -> 0f
        first.index > section.firstRowIndex -> Float.POSITIVE_INFINITY
        else -> (top - first.offset).coerceAtLeast(0f)
    }
    val below = when {
        last == null -> 0f
        last.index < section.lastRowIndex -> Float.POSITIVE_INFINITY
        else -> (last.offset + last.size - bottom).coerceAtLeast(0f)
    }
    return QueueFadeViewport(top, bottom, above, below)
}

/**
 * 这一行离视口两端够不够远 —— 够远回 `false`，图层段就省掉那层离屏缓冲（`Auto` 直通）。
 *
 * 🔴 判据是**故意放宽**的：只取两条带里宽的那条 `reach`，要求"离两端都有 `reach` 的余量"才算远。
 * 带宽经过两次收窄后只会比 `reach` 小，所以判定为远时一定真的不会铺遮罩；判定为近则可能多建一层
 * 缓冲 —— 这个方向是安全的。**反过来（该给 `Offscreen` 却给了 `Auto`）会把遮罩铺到屏幕画布上**，
 * 那才是要命的，所以宁可多建。
 */
private fun LazyListState.isNearEdge(
    rowKey: Any,
    section: QueueSection,
    visualOffsetY: Float,
    topFade: Float,
    bottomFade: Float,
): Boolean {
    val layout = layoutInfo
    val info = layout.visibleItemsInfo.firstOrNull { it.key == rowKey } ?: return true
    val viewport = layout.fadeViewport(section, visualOffsetY)
    if (viewport.height <= 0f) return true
    val reach = maxOf(topFade, bottomFade)
    val offset = info.offset.toFloat()
    return offset < viewport.top + reach || offset + info.size > viewport.bottom - reach
}

/**
 * 按钮排里的一颗按钮。
 *
 * [active] 只决定取哪一对颜色——关闭态是上下一首按钮那套（底 + 图标），打开态是播放键那套。
 * 两侧都是 producer，`if` 只是挑引用，值仍在绘制段/叶子节点里读。
 */
@Composable
private fun PanelButton(
    iconRes: Int,
    label: String,
    active: Boolean,
    background: ColorProducer,
    tint: ColorProducer,
    activeBackground: ColorProducer,
    activeTint: ColorProducer,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(Capsule())
            .drawBackground(if (active) activeBackground else background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            tint = if (active) activeTint else tint,
            contentDescription = label,
            modifier = Modifier.size(ButtonIconSize),
        )
    }
}

/**
 * 叶子文字：颜色在这里才被读出来。
 *
 * 跟播放页的 `PlayerText` 同一个套路——主题色是换歌时动 500ms 的，读在组合期的话那一行会每帧
 * 重组；隔在这么一层里，重组范围就只剩这几行文字。
 */
@Composable
private fun QueueText(
    text: String,
    style: TextStyle,
    color: Color,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 行首封面的请求：跟详情页曲目行同款——一个淡入，尺寸交给 Coil 按布局量。
 * 地址为空时返回 null，Coil 什么都不画，露出行底。
 */
@Composable
private fun queueCoverRequest(url: String): Any? {
    val context = LocalContext.current
    return remember(url) {
        url.takeIf { it.isNotEmpty() }?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(true)
                .build()
        }
    }
}

/**
 * 循环按钮的图标：**顺序和列表循环共用 `repeat`**（就是循环关掉的样子），单曲循环才换 `repeat_one`。
 *
 * 两态共用一个图标是有意的——关闭态用的是上下一首那套暗色，打开态换成播放键那套亮色，
 * 「关没关」靠配色说，不靠图标说。
 */
private fun PlayMode.buttonIconRes(): Int = when (this) {
    PlayMode.SEQUENTIAL, PlayMode.REPEAT_ALL -> R.drawable.repeat
    PlayMode.REPEAT_ONE -> R.drawable.repeat_one
}

private fun PlayMode.buttonLabel(): String = when (this) {
    PlayMode.SEQUENTIAL -> "顺序播放"
    PlayMode.REPEAT_ALL -> "列表循环"
    PlayMode.REPEAT_ONE -> "单曲循环"
}
