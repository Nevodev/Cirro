package com.nevoit.cirro.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.nevoit.cirro.R
import com.nevoit.material.core.component.HGap
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.modifier.drawBackground
import com.nevoit.material.core.modifier.marquee
import com.nevoit.material.core.utility.rememberColorProducer
import com.nevoit.material.theme.MaterialTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * 吸底的迷你播放条。没有在放的歌时不渲染。
 *
 * 颜色一律收 producer：详情页进出时这些颜色是动画态（底栏跟着封面走），传值会让调用方
 * 每帧重组一次；传 producer 就只有 draw 阶段去读，颜色变化只失效绘制。
 * 容器反色（深色页浅胶囊）时，调用方把文字色一起换掉。
 *
 * 状态收的是 [StateFlow] 而不是值：进度条每 500ms 要重画一次，读的位置留在这一层，
 * 重组范围就只有这条胶囊本身——传值的话每次心跳都会顺着参数把调用方连带重组一遍。
 */
@Composable
fun MiniPlayer(
    stateFlow: StateFlow<PlaybackState>,
    onClick: () -> Unit,
    onTogglePlay: () -> Unit,
    onSkipNext: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: ColorProducer = rememberColorProducer(MaterialTheme.colors.cardBackground),
    contentColor: ColorProducer = rememberColorProducer(MaterialTheme.colors.content),
    contentVariantColor: ColorProducer = rememberColorProducer(MaterialTheme.colors.contentVariant),
    progressColor: ColorProducer = rememberColorProducer(MaterialTheme.colors.primary),
) {
    val state by stateFlow.collectAsState()
    val song = state.song ?: return
    val boxWidth = LocalWindowInfo.current.containerDpSize.width - 24.dp
    val boxHeight = 56.dp
    val progressBarWidth = boxWidth - boxHeight

    val error = state.error
    val errorColor = rememberColorProducer(MaterialTheme.colors.error)

    Box(
        modifier = modifier
            .clip(Capsule())
            .drawBackground(containerColor)
            .height(boxHeight)
            // 整条可点：开播放页。里面的两个按钮自己消费事件，不会连带触发
            .clickable(onClick = onClick),
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
                MiniPlayerText(
                    text = song.title,
                    style = MaterialTheme.type.subHeadline2,
                    fontWeight = FontWeight.Medium,
                    color = contentColor,
                    restartKey = song.id,
                )
                MiniPlayerText(
                    text = error ?: song.artist,
                    style = MaterialTheme.type.footnote,
                    color = if (error != null) errorColor else contentVariantColor,
                    restartKey = song.id,
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
                    tint = contentColor,
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onSkipNext),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.forward_end_fill),
                    contentDescription = "下一首",
                    modifier = Modifier.size(28.dp),
                    tint = contentColor,
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
                color = progressColor(),
                size = size.copy(width = size.width * state.progress),
            )
        }
    }
}

// 文字色是组合期输入（要落进 TextStyle），只能在这一层读。颜色动画期间只有这两行文字重组，
// MiniPlayer 的封面、按钮、进度条都不动。
@Composable
private fun MiniPlayerText(
    text: String,
    style: TextStyle,
    color: ColorProducer,
    restartKey: Any?,
    fontWeight: FontWeight? = null,
) {
    Text(
        text = text,
        style = style,
        color = color(),
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .marquee(
                restartKey = restartKey,
                initialDelayMillis = 2000,
                startFadeOffset = 6.dp,
                endFadeOffset = 12.dp,
                spacing = 24.dp
            )
    )
}
