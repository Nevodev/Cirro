package com.nevoit.cirro.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.transformations
import com.kyant.shapes.RoundedRectangle
import com.nevoit.cirro.R
import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.ui.ReflectionTransformation
import com.nevoit.material.core.animation.rememberPressAnimatable
import com.nevoit.material.core.component.CircleIconButton
import com.nevoit.material.core.component.HGap
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.MaterialShapeButton
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.modifier.smoothGradient
import com.nevoit.material.theme.MaterialShapes
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.scaled
import com.nevoit.material.theme.tokens.Springs
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

// 详情页（艺人页 ArtistScreen / 专辑歌单页 CollectionScreen）的公共骨架：
// 头部那套尺寸与取色档位、按钮排、浮层圆钮、加载提示、封面请求、元信息与落款文案。

internal val PagePadding = 12.dp

internal val TrackCorner = 8.dp

internal val HeaderRise = 128.dp

internal const val HeaderGradientRiseMultiplier = 2f

private const val HeaderGradientEdgeOverlapPx = 2f

/**
 * 详情页倒影的模糊带往上**溢进封面**的高度，按倒影带高度算。
 *
 * 艺人页视频那边是 `rise / 2`（[com.nevoit.cirro.ui.video.ArtistReflectionGeometry] 的默认值）：
 * 画面一直在动，溢一截反而看不出接缝。详情页是静止封面，溢到一半会把封面底部一大块糊掉，
 * 所以这里只留四分之一。设成 0 就是起点正好落在接缝上，封面一点都不被糊到。
 */
internal const val HeaderBleedRatio = 0.35f

internal const val HeaderCoverCapPx = 1024

internal const val BgToneDark = 30

internal const val BgToneLight = 80

internal const val VariantToneDark = 80.0

internal const val VariantToneLight = 40.0

internal const val ButtonToneDark = 50.0

internal const val ButtonToneLight = 90.0

internal const val ButtonIconToneDark = 90.0

internal const val ButtonIconToneLight = 40.0

internal const val ItemBgToneDark = 40.0

internal const val ItemBgToneLight = 90.0

internal const val CircleToneDark = 40.0

internal const val CircleToneLight = 90.0

internal const val CircleIconToneDark = 90.0

internal const val CircleIconToneLight = 30.0

internal fun Modifier.headerCoverGradient(color: ColorProducer): Modifier =
    smoothGradient(color, start = 0f, end = 1f, intensity = 1f).drawBehind {
        // The independently sampled cover layer can bleed past a coincident gradient edge
        // under fractional scaling. Cover both sides of that edge without changing layout.
        drawRect(
            color = color(),
            topLeft = Offset(0f, size.height - HeaderGradientEdgeOverlapPx),
            size = Size(size.width, HeaderGradientEdgeOverlapPx * 2f),
        )
    }

/**
 * 头部按钮排：左圆钮（艺人页是「艺人资料」，其余是「随机播放」）、中播放胶囊、右收藏圆钮。
 * 每日推荐没有可收藏的实体，右侧留一个等宽空位，让胶囊保持正中。
 */
@Composable
internal fun HeaderActions(
    canCollect: Boolean,
    subscribed: Boolean,
    subBusy: Boolean,
    leadingIconRes: Int,
    leadingLabel: String,
    onLeading: () -> Unit,
    buttonColor: ColorProducer,
    buttonIconColor: ColorProducer,
    onPlay: () -> Unit,
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
            iconRes = leadingIconRes,
            shape = MaterialShapes.Cookie4Sided,
            label = leadingLabel,
            background = buttonColor,
            tint = buttonIconColor,
            onClick = onLeading,
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
internal fun FloatingBar(
    onBack: () -> Unit,
    onShare: () -> Unit,
    background: ColorProducer,
    tint: ColorProducer,
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
internal fun InfoFailure(
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
internal fun ListHint(
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
internal fun headerCoverModel(url: String, widthDp: Int): Any? {
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
                // 变换要读像素，硬件位图读不了——放它进来就得先拷一张 5.5MB 的软件副本
                // （`ReflectionTransformation.asSoftware`）。直接要软件位图，少一次整图拷贝。
                .allowHardware(false)
                .transformations(
                    ReflectionTransformation(
                        context = context,
                        squareDp = side.toFloat(),
                        stripDp = side * riseRatio / 3,
                        riseDp = side * riseRatio,
                        bleedDp = side * riseRatio * HeaderBleedRatio,
                        blurDp = 48f
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
internal fun metaLine(info: CollectionInfo?): String {
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

internal fun yearOf(ms: Long): Int {
    if (ms <= 0) return 0
    return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).year
}

/**
 * 曲目列表底部的落款（居中多行小字）：
 * 专辑给「发行日期 / N首歌曲，总时长 / Ⓟ公司」（公司空串那行整行省略）；
 * 歌单只给「N首歌曲，总时长」；每日推荐不给落款。
 * 总时长最小单位分钟（不足一分钟不显示），满一小时进位成「n小时n分钟」。
 */
internal fun footerText(info: CollectionInfo?, songCount: Int, totalMs: Long): String {
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

internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val totalSeconds = ms / 1000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
