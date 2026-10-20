package com.nevoit.cirro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.UnevenRoundedRectangle
import com.nevoit.cirro.R
import com.nevoit.cirro.data.SongLikeRepository
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.playback.PlayingCoverIndicator
import com.nevoit.cirro.playback.PlayingTrackNumber
import com.nevoit.material.core.component.BottomPopupMenu
import com.nevoit.material.core.component.BottomPopupMenuScope
import com.nevoit.material.core.component.CircleIconButton
import com.nevoit.material.core.component.HGap
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.modifier.drawBackground
import com.nevoit.material.core.utility.rememberColorProducer
import com.nevoit.material.theme.MaterialTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

/** 播不了的歌，内容那一层的透明度（背景不淡）。 */
private const val UnplayableAlpha = 0.5f

/** 「更多」按钮的底：透明，这里只是给 producer 形式留一个常量。 */
private val TransparentBackground = ColorProducer { Color.Transparent }

internal enum class ItemPosition {
    Single,
    First,
    Middle,
    Last,
}

internal fun ItemPosition.roundedShape(): Shape = when (this) {
    ItemPosition.Single -> RoundedRectangle(20.dp)
    ItemPosition.First -> UnevenRoundedRectangle(20.dp, 20.dp, 8.dp, 8.dp)
    ItemPosition.Middle -> RoundedRectangle(8.dp)
    ItemPosition.Last -> UnevenRoundedRectangle(8.dp, 8.dp, 20.dp, 20.dp)
}

/**
 * 曲目一行：序号或封面 + 歌名/歌手 + 更多操作。整行就是播放入口。
 *
 * 颜色全是 [ColorProducer]：详情页的底色跟着封面种子做 500ms 补间，组合期读一次就等于
 * 这 500ms 里每帧把整页（连同每一行、每一行里的文字）重组一遍。交给 producer 之后
 * 底色只在绘制段读，文字色由下面两个叶子各自读——行本身一次都不重组。
 */
@Composable
internal fun TrackRow(
    song: Song,
    backgroundColor: ColorProducer,
    showArtist: Boolean,
    index: Int?,
    position: ItemPosition,
    isPlaying: Boolean,
    content: ColorProducer,
    contentVariant: ColorProducer,
    /**
     * 第二行改写成别的东西（艺人页排行要「专辑 · 年份」）。给了就不再显示歌手名，
     * [showArtist] 只管默认那条。
     */
    subtitle: String? = null,
    showStar: Boolean = true,
    starColor: ColorProducer = contentVariant,
    onClick: () -> Unit,
    menuContent: @Composable BottomPopupMenuScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = position.roundedShape()
    val liked = if (showStar) rememberIsLiked(song.id) else false
    val playable = song.playable

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp)
                .clip(shape)
                .drawBackground(backgroundColor)
                .clickable(enabled = playable, onClick = onClick)
                .padding(start = 12.dp)
                .graphicsLayer { alpha = if (playable) 1f else UnplayableAlpha },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index == null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedRectangle(8.dp)),
                ) {
                    AsyncImage(
                        model = rememberCoverRequest(song.cover),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                    PlayingCoverIndicator(isPlaying, Modifier.matchParentSize())
                }
            } else {
                TrackIndex(
                    index = index,
                    isCurrent = isPlaying,
                    color = contentVariant,
                    style = MaterialTheme.type.body.copy(fontFeatureSettings = "tnum"),
                    modifier = Modifier.width(32.dp),
                )
            }
            HGap()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp)
            ) {
                TrackText(
                    text = song.name,
                    style = MaterialTheme.type.body,
                    color = content,
                    maxLines = 2,
                )
                val second = subtitle ?: song.artistText.takeIf { showArtist }
                if (second != null) {
                    TrackText(
                        text = second,
                        style = MaterialTheme.type.subHeadline2,
                        color = contentVariant,
                        maxLines = 1,
                    )
                }
            }
            HGap()
            BottomPopupMenu(
                menuContent = menuContent
            ) { openMenu ->
                CircleIconButton(
                    iconRes = R.drawable.ellipsis_vertical,
                    label = "更多",
                    background = TransparentBackground,
                    tint = contentVariant,
                    onClick = openMenu,
                    enabled = playable,
                    iconSize = 24.dp,
                )
            }
        }

        if (liked) {
            Icon(
                painter = painterResource(R.drawable.star_fill_small),
                tint = starColor,
                contentDescription = "已喜爱",
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .graphicsLayer { alpha = if (playable) 1f else UnplayableAlpha }
                    .size(12.dp),
            )
        }
    }
}

/** [TrackRow] 的固定色版本：颜色在组合期就已经定死的调用方用这个，producer 由这里补上。 */
@Composable
internal fun TrackRow(
    song: Song,
    backgroundColor: Color,
    showArtist: Boolean,
    index: Int?,
    position: ItemPosition,
    isPlaying: Boolean,
    content: Color,
    contentVariant: Color,
    subtitle: String? = null,
    showStar: Boolean = true,
    starColor: Color = contentVariant,
    onClick: () -> Unit,
    menuContent: @Composable BottomPopupMenuScope.() -> Unit,
    modifier: Modifier = Modifier,
) = TrackRow(
    song = song,
    backgroundColor = rememberColorProducer(backgroundColor),
    showArtist = showArtist,
    index = index,
    position = position,
    isPlaying = isPlaying,
    content = rememberColorProducer(content),
    contentVariant = rememberColorProducer(contentVariant),
    subtitle = subtitle,
    showStar = showStar,
    starColor = rememberColorProducer(starColor),
    onClick = onClick,
    menuContent = menuContent,
    modifier = modifier,
)

// 文字色是组合期输入（要落进 TextStyle），只能在叶子这一层读——补间期间重组的就只有这几行字，
// 行本身、封面、按钮一次都不动。跟播放页的 PlayerText 是同一个套路。
@Composable
private fun TrackText(
    text: String,
    style: TextStyle,
    color: ColorProducer,
    maxLines: Int,
) {
    Text(
        text = text,
        style = style,
        color = color(),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

// 序号同理：PlayingTrackNumber 要的是具体颜色，在这儿读一次，别让 TrackRow 组合期碰它。
@Composable
private fun TrackIndex(
    index: Int,
    isCurrent: Boolean,
    color: ColorProducer,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    PlayingTrackNumber(
        index = index,
        isCurrent = isCurrent,
        color = color(),
        style = style,
        modifier = modifier,
    )
}

@Composable
internal fun rememberIsLiked(songId: Long): Boolean {
    val likes = koinInject<SongLikeRepository>()
    val liked by remember(likes, songId) {
        likes.snapshot.map { it.likedIds?.contains(songId) == true }.distinctUntilChanged()
    }.collectAsState(initial = remember { likes.snapshot.value.likedIds?.contains(songId) == true })
    return liked
}

internal fun resolvePosition(size: Int, index: Int): ItemPosition {
    return when {
        size <= 1 -> ItemPosition.Single
        index == 0 -> ItemPosition.First
        index == size - 1 -> ItemPosition.Last
        else -> ItemPosition.Middle
    }
}
