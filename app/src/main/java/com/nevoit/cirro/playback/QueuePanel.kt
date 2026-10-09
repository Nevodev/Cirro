package com.nevoit.cirro.playback

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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
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
private val TopEdgeFade = 40.dp

/** 列表**下**边缘的渐隐长度。 */
private val BottomEdgeFade = 56.dp

private val GrabberSize = 24.dp

private val ButtonHeight = 48.dp

private val ButtonGap = 8.dp

private val ButtonIconSize = 24.dp

/**
 * 播放页的「待播列表」面板。
 *
 * 结构就两块：**上面列表、下面按钮排**，中间空 12dp。
 *
 * - 列表：整体圆角裁切，行样式（含首尾圆角那套形状）跟详情页曲目行同款，右侧图标换成 grabber；
 *   行底和文字色都从调用方来（行底 = 上下一首按钮的底色，文字 = 播放页 meta 的 content / variant）。
 * - 按钮排：**随机**（两态）、**循环**（顺序 / 列表循环 / 单曲循环）、**无限**（还没有功能）。
 *   关闭态用上下一首按钮那套配色，打开态用播放键那套，一眼能看出哪颗是开着的。
 *
 * 🔴 出现动画还没做：调用方按选中状态直接挂/摘整棵子树。
 * 🔴 队列状态**由面板自己订**（而不是从 PlayerOverlay 传下来）：队列每加一条插播都会变，
 * 让播放页顶层跟着重组不划算。颜色是推进来的 producer，读值只在绘制段和叶子文字里。
 */
@Composable
internal fun QueuePanel(
    connection: PlaybackConnection,
    buttonBackground: ColorProducer,
    buttonIcon: ColorProducer,
    activeButtonBackground: ColorProducer,
    activeButtonIcon: ColorProducer,
    modifier: Modifier = Modifier,
) {
    val queue by connection.queue.collectAsState()
    val entries = queue.entries
    val mode = queue.mode
    val shuffle = queue.shuffle

    // 回调按状态 remember：不固定的话每次重组都是新 lambda，LazyColumn 那一堆行的跳过全失效。
    val onToggleShuffle = remember(connection, shuffle) { { connection.setShuffle(!shuffle) } }
    val onCycleLoop = remember(connection, mode) { { connection.setLoopMode(mode.nextLoop()) } }
    val onInfinite = remember { {} }

    // 行要按"自己滚到了哪儿"算渐隐系数，所以列表状态得由面板持有、下发给每一行。
    val listState = rememberLazyListState()

    Column(modifier = modifier.fillMaxSize()) {
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
                modifier = Modifier.weight(1f),
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
                modifier = Modifier.weight(1f),
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
                modifier = Modifier.weight(1f),
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
                modifier = Modifier.fillMaxSize(),
                flingBehavior = rememberFlingBehavior(),
            ) {
                itemsIndexed(entries, key = { _, entry -> entry.uid }) { _, entry ->
                    QueueRow(
                        song = entry.song,
                        rowKey = entry.uid,
                        listState = listState,
                    )
                }
            }
        }
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
                .edgeFade(listState, rowKey, BlendMode.SrcOver),
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
                .edgeFade(listState, rowKey, BlendMode.Plus),
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
            HGap()
            Icon(
                painter = painterResource(R.drawable.grabber),
                contentDescription = null,
                tint = Color.White.copy(.5f),
                modifier = Modifier.size(GrabberSize),
            )
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
 * 🔴 带宽**从视口边起算**，两端各按"那一端被裁掉多少内容"展开（[fadeOverhangAbove] /
 * [fadeOverhangBelow]）：列表滚到顶时上面没有被切断的内容，带宽收到 0 —— 第一行的顶边一点都不淡；
 * 一滚起来就展开，而**裁剪线处永远收到 α = 0**，视口边上就看不到硬切口。
 *
 * ⚠️ 反着做（带宽固定、只把系数整体往 1 抬）看着像等价，其实不是：裁剪线处的 α 会被抬到
 * `1 - gate`，被父级切掉的那半行就成了"半透明地被截断"，硬切口感官上更难看。带宽收到 0 就不存在
 * 这个问题 —— 那里压根没有带。
 */
private fun Modifier.edgeFade(
    listState: LazyListState,
    rowKey: Any,
    blendMode: BlendMode,
): Modifier = this
    .graphicsLayer {
        this.blendMode = blendMode
        // `Auto` 只在"这行离两端都足够远、绘制段肯定不会铺遮罩"时才给（见 isNearEdge）。
        // ⚠️ 方向不能反：给了 `Auto`（没有独立缓冲）却铺了 `DstIn`，遮罩会直接作用在屏幕画布上，
        // 把底下的内容连背景一起擦掉。所以这个判据只允许**偏保守**（多建一层缓冲），不许偏激进。
        compositingStrategy =
            if (blendMode == BlendMode.SrcOver &&
                !listState.isNearEdge(rowKey, TopEdgeFade.toPx(), BottomEdgeFade.toPx())
            ) {
                CompositingStrategy.Auto
            } else {
                CompositingStrategy.Offscreen
            }
    }
    .drawWithContent {
        drawContent()
        val layoutInfo = listState.layoutInfo
        // 不可见的行根本不绘制；查不到就是没这根行，不用管。
        val info = layoutInfo.visibleItemsInfo.firstOrNull { it.key == rowKey }
            ?: return@drawWithContent
        val viewportHeight = layoutInfo.viewportSize.height.toFloat()
        // 两条带各按各的上限来；总长赶上视口时等比收窄，保证中间还剩一段通透区。
        val scale = fadeScale(viewportHeight, TopEdgeFade.toPx(), BottomEdgeFade.toPx())
        // 🔴 再按"那一端被裁掉多少"把带宽收一下：滚到顶/底那端收到 0（整条带消失），有溢出时
        // 带宽 = 溢出量 —— 带始终从视口边起算，所以裁剪线处恒为 0，切口是透明的。
        val topFade = minOf(TopEdgeFade.toPx() * scale, listState.fadeOverhangAbove())
        val bottomFade = minOf(BottomEdgeFade.toPx() * scale, listState.fadeOverhangBelow())
        if (topFade + bottomFade <= 0f) return@drawWithContent
        val brush = rowFadeBrush(
            offset = info.offset.toFloat(),
            size = info.size.toFloat(),
            viewportHeight = viewportHeight,
            topFade = topFade,
            bottomFade = bottomFade,
        ) ?: return@drawWithContent
        drawRect(brush = brush, blendMode = BlendMode.DstIn)
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
 * 🔴 [rowFadeBrush] 和 [isNearEdge] **都走这一个函数** —— 那两边必须同口径（一边决定画不画遮罩、
 * 一边决定要不要给独立缓冲），各写一份迟早走岔，而走岔的后果是把屏幕连 GL 一起擦掉。
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
 * 视口上边**被裁掉多少内容**（px）。列表滚到顶时为 0 —— 上面没有被切断的内容，第一行的顶边
 * 一点都不该淡；上方还压着整项时给无穷大，让调用方的 `minOf` 直接落到带宽上限。
 *
 * 🔴 拿它去**收带宽**（而不是去抬系数）：带宽锚在视口边上，裁剪线处才恒为 α = 0。
 * 只读 [LazyListState.layoutInfo]，所以只能在 draw / layer 段调。
 */
private fun LazyListState.fadeOverhangAbove(): Float {
    val first = layoutInfo.visibleItemsInfo.firstOrNull() ?: return 0f
    if (first.index > 0) return Float.POSITIVE_INFINITY
    return (-first.offset).coerceAtLeast(0).toFloat()
}

/** 视口下边**被裁掉多少内容**（px）。列表滚到底时为 0；下面还有没渲染的项时给无穷大。 */
private fun LazyListState.fadeOverhangBelow(): Float {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    if (last.index < info.totalItemsCount - 1) return Float.POSITIVE_INFINITY
    return (last.offset + last.size - info.viewportSize.height).coerceAtLeast(0).toFloat()
}

/**
 * 这一行离视口两端够不够远 —— 够远回 `false`，图层段就省掉那层离屏缓冲（`Auto` 直通）。
 *
 * 🔴 判据是**故意放宽**的：只取两条带里宽的那条 `reach`，要求"离两端都有 `reach` 的余量"才算远。
 * 带宽经过两次收窄后只会比 `reach` 小，所以判定为远时一定真的不会铺遮罩；判定为近则可能多建一层
 * 缓冲 —— 这个方向是安全的。**反过来（该给 `Offscreen` 却给了 `Auto`）会把遮罩铺到屏幕画布上**，
 * 那才是要命的，所以宁可多建。
 */
private fun LazyListState.isNearEdge(rowKey: Any, topFade: Float, bottomFade: Float): Boolean {
    val reach = maxOf(topFade, bottomFade)
    if (reach <= 0f) return false
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.key == rowKey } ?: return false
    val offset = info.offset.toFloat()
    val viewportHeight = layoutInfo.viewportSize.height.toFloat()
    return offset < reach || offset + info.size > viewportHeight - reach
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
