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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
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

/** 列表整体的裁切圆角。跟首尾行的圆角同档，滚动时那一行贴到边上也不会露出直角。 */
private val ListCorner = 20.dp

private val RowMinHeight = 56.dp

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
    rowBackground: ColorProducer,
    content: ColorProducer,
    contentVariant: ColorProducer,
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
            LazyColumn(modifier = Modifier.fillMaxSize(), flingBehavior = rememberFlingBehavior()) {
                itemsIndexed(entries, key = { _, entry -> entry.uid }) { _, entry ->
                    QueueRow(entry.song)
                }
            }
        }
    }
}

/**
 * 待播列表的一行。形状与结构照抄详情页的曲目行（`CollectionScreen.TrackRow`）：
 * 行首封面 / 中间歌名 + 歌手 / 右侧图标——只是图标换成了 grabber。
 *
 * [position] 决定圆角：一摞行的首尾是大圆角、中间是小圆角，单独一行整块圆角。
 */
@Composable
private fun QueueRow(
    song: Song,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = queueCoverRequest(song.cover),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedRectangle(4.dp)),
        )
        HGap()
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
                color = Color.White.copy(.5f),
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
