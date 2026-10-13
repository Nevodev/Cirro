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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.UnevenRoundedRectangle
import com.nevoit.cirro.R
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.playback.PlayingCoverIndicator
import com.nevoit.cirro.playback.PlayingTrackNumber
import com.nevoit.material.core.component.BottomPopupMenu
import com.nevoit.material.core.component.BottomPopupMenuScope
import com.nevoit.material.core.component.HGap
import com.nevoit.material.core.component.CircleIconButton
import com.nevoit.material.core.component.Text
import com.nevoit.material.theme.MaterialTheme

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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(start = 12.dp),
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
                iconSize = 24.dp,
            )
        }
    }
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
