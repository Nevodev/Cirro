package com.nevoit.cirro.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.nevoit.cirro.R
import com.nevoit.material.core.component.HGap
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.Surface
import com.nevoit.material.core.component.Text
import com.nevoit.material.theme.MaterialTheme

/** 吸底的迷你播放条。没有在放的歌时不渲染。 */
@Composable
fun MiniPlayer(
    state: PlaybackState,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val song = state.song ?: return
    val boxWidth = LocalWindowInfo.current.containerDpSize.width - 24.dp
    val boxHeight = 56.dp
    val progressBarWidth = boxWidth - boxHeight
    val colors = MaterialTheme.colors

    Surface(
        modifier = modifier
            .clip(Capsule())
            .height(boxHeight),
        color = MaterialTheme.colors.cardBackground,
        contentColor = MaterialTheme.colors.content,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 16.dp)
                    .size(36.dp)
                    .clip(RoundedRectangle(8.dp))
                    .background(MaterialTheme.colors.scrimNormal),
            ) {
                if (!song.cover.isNullOrEmpty()) {
                    AsyncImage(
                        model = song.cover,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            HGap(10.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.type.subHeadline2,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val error = state.error
                Text(
                    text = error ?: song.artist,
                    style = MaterialTheme.type.footnote,
                    color = if (error != null) {
                        MaterialTheme.colors.error
                    } else {
                        MaterialTheme.colors.contentVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HGap()
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (state.isPlaying) R.drawable.pause_fill else R.drawable.play_fill
                    ),
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colors.content,
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable {

                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.forward_end_fill),
                    contentDescription = "下一首",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colors.content,
                )
            }
            HGap(4.dp)
        }

        Canvas(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .clip(Capsule())
                .width(progressBarWidth)
                .height(2.dp)
        ) {
            drawRect(
                color = colors.primary,
                size = size.copy(width = size.width * state.progress),
            )
        }
    }
}
