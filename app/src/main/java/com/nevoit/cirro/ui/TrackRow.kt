package com.nevoit.cirro.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import com.nevoit.material.theme.MaterialTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

/** 播不了的歌，内容那一层的透明度（背景不淡）。 */
private const val UnplayableAlpha = 0.5f

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

/** 曲目一行：序号或封面 + 歌名/歌手 + 更多操作。整行就是播放入口。 */
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
    onClick: () -> Unit,
    menuContent: @Composable BottomPopupMenuScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = position.roundedShape()
    val liked = rememberIsLiked(song.id)

    // 最外层就代表 TrackRow 的整个宽度。行本体（clip / 背景 / 点击）在里层缩进一圈，
    // 于是它的左边缘外面空出一条缝 —— 红心星就落在那儿（在 clip 之外，不会被圆角裁掉）。
    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                // 原来调用点在行**外面**加的左右间距，现在归 TrackRow 自己管。
                .padding(horizontal = 12.dp)
                .clip(shape)
                .background(backgroundColor)
                .clickable(enabled = song.playable, onClick = onClick)
                .padding(start = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = if (song.playable) 1f else UnplayableAlpha },
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
                    PlayingTrackNumber(
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
                    Text(
                        text = song.name,
                        style = MaterialTheme.type.body,
                        color = content,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showArtist) {
                        Text(
                            text = song.artistText,
                            style = MaterialTheme.type.subHeadline2,
                            color = contentVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                        background = Color.Transparent,
                        tint = contentVariant,
                        onClick = openMenu,
                        enabled = song.playable,
                        iconSize = 24.dp,
                    )
                }
            }
        }

        if (liked) {
            Icon(
                painter = painterResource(R.drawable.star_fill_small),
                contentDescription = "已喜爱",
                tint = contentVariant,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .graphicsLayer { alpha = if (song.playable) 1f else UnplayableAlpha }
                    .size(12.dp),
            )
        }
    }
}

/**
 * 这一首有没有红心。
 *
 * 跟播放状态同一套路（`rememberIsCurrentSong`）：**每行自己订阅**，状态读取关在行内 ——
 * `likedIds` 一波动只有状态真变的那一行重组，页面那层完全不用跟着动。`LazyColumn` 只组合可见行，
 * 所以订阅数也就是十来条。
 *
 * `likedIds` 为 null（还没加载 / 刚切账号）一律当"没红心"；`SongLikeRepository` 切账号时会立刻
 * 把快照清空，所以这里读到的不会串到上一个账号。
 */
@Composable
internal fun rememberIsLiked(songId: Long): Boolean {
    val likes = koinInject<SongLikeRepository>()
    val liked by remember(likes, songId) {
        likes.snapshot.map { it.likedIds?.contains(songId) == true }.distinctUntilChanged()
    }.collectAsState(initial = likes.snapshot.value.likedIds?.contains(songId) == true)
    return liked
}

internal fun resolvePosition(size: Int, index: Int): ItemPosition {
    // 单元素列表（Single 专辑就 1 首）四角都要圆，让 First 和 Last 的圆角叠加不了，
    // 单给一个值
    return when {
        size <= 1 -> ItemPosition.Single
        index == 0 -> ItemPosition.First
        index == size - 1 -> ItemPosition.Last
        else -> ItemPosition.Middle
    }
}
