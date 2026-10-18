package com.nevoit.cirro.detail

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kyant.shapes.RoundedRectangle
import com.nevoit.cirro.R
import com.nevoit.cirro.data.model.ArtistVideo
import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.albumDisplayTitle
import com.nevoit.cirro.home.Section
import com.nevoit.cirro.home.dataOrNull
import com.nevoit.cirro.ui.rememberCoverRequest
import com.nevoit.material.core.component.CircleIconButton
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.interaction.rememberFlingBehavior
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.tokens.Springs

/** 视频缩略图的高度。宽度按视频真实比例算，所以这一区的卡片不齐宽。 */
private val VideoThumbHeight = 152.dp

/** 竖版视频（9:16）再窄就成一条缝了，横版也不让它压过两个半屏。 */
private const val VideoMinRatio = 0.5625f
private const val VideoMaxRatio = 2.4f

private val UnknownVideoRatio = 16f / 9f

/** 单曲与 EP 归一块，其余的「专辑」（含现场、精选之类）归另一块。 */
internal fun CollectionInfo.isSingleOrEp(): Boolean =
    albumType.equals("Single", ignoreCase = true) || albumType.equals("EP", ignoreCase = true)

/** 只换 [Section.Ready] 里的列表，Loading / Failed 原样透过。 */
internal fun <T> Section<List<T>>.mapData(transform: (List<T>) -> List<T>): Section<List<T>> =
    when (this) {
        is Section.Ready -> Section.Ready(transform(data))
        else -> this
    }

/**
 * 排行行的第二行：「所属专辑 · 2020年」。单曲专辑照样带上 " - Single"。
 *
 * [album] 从艺人作品列表里按 `albumId` 捡（同一份 `artistAlbums`，不另发请求）；
 * 捡不到（合唱专辑挂在别人名下）就只给专辑名，年份那截整段让位。
 */
internal fun rankingSubtitle(song: Song, album: CollectionInfo?): String? {
    val title = song.album
    if (title.isEmpty()) return null
    val year = yearOf(album?.updateTimeMs ?: 0)
    val name = albumDisplayTitle(title, album?.albumType)
    return if (year > 0) "$name · ${year}年" else name
}

/**
 * 艺人页第一张卡：最新发行的作品（专辑、单曲、EP 都算）。
 *
 * 左封面 + 右信息 + 最右一个「更多」——更多菜单还没有，先占位。
 * 信息三行按固定顺序给：发行日期 / 作品名（最多两行）/ 收录曲目数。
 */
@Composable
internal fun LatestReleaseCard(
    album: CollectionInfo,
    content: Color,
    contentVariant: Color,
    background: Color,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .heightIn(min = 104.dp)
            .clip(RoundedRectangle(20.dp))
            .background(background)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = rememberCoverRequest(album.cover),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .padding(12.dp)
                .size(80.dp)
                .clip(RoundedRectangle(8.dp)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 16.dp)
        ) {
            formatDate(album.updateTimeMs)?.let { date ->
                Text(
                    text = date,
                    style = MaterialTheme.type.footnote,
                    color = contentVariant,
                    maxLines = 1,
                )
                VGap(2.dp)
            }
            Text(
                text = albumDisplayTitle(album.title, album.albumType),
                style = MaterialTheme.type.body,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (album.trackCount > 0) {
                VGap(2.dp)
                Text(
                    text = "${album.trackCount} 首歌曲",
                    style = MaterialTheme.type.footnote,
                    color = contentVariant,
                    maxLines = 1,
                )
            }
        }
        CircleIconButton(
            iconRes = R.drawable.ellipsis_vertical,
            label = "更多",
            background = Color.Transparent,
            tint = contentVariant,
            onClick = onMore,
            iconSize = 24.dp,
        )
    }
}

/**
 * 艺人页「音乐视频」：横滑，缩略图高度固定、宽度跟视频真实比例走。
 *
 * 这一区还不能播（点卡片没反应），但直链和缩略图都是现成的——见 `NcmBridge.artistVideos`。
 */
@Composable
internal fun LazyItemScope.ArtistVideoSection(
    videos: Section<List<ArtistVideo>>,
    content: Color,
) {
    val list = videos.dataOrNull.orEmpty()
    if (list.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateItem(tween(200), Springs.crisp(), tween(200))
    ) {
        VGap(36.dp)
        RecommendationTitle("音乐视频", content)
        VGap()
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            flingBehavior = rememberFlingBehavior(),
        ) {
            items(list, key = { it.id }) { video ->
                VideoCard(video = video, content = content)
            }
        }
    }
}

@Composable
private fun VideoCard(video: ArtistVideo, content: Color) {
    // 视频的真实宽高来自最高一档 mp4；一条都没有就退回 16:9，别让卡片变成一条缝
    val ratio = if (video.width > 0 && video.height > 0) {
        video.width.toFloat() / video.height
    } else {
        UnknownVideoRatio
    }.coerceIn(VideoMinRatio, VideoMaxRatio)

    val density = LocalDensity.current
    val context = LocalContext.current
    val width = VideoThumbHeight * ratio
    // 按卡片像素尺寸直接问 CDN 要图：`param=WxH` 是服务端裁切，省得下一张 4:3 的原图再在本地裁一遍。
    // 已经带了 `param` 的地址（Mapper 只在没有时才补方形）原样用过
    val model = remember(video.cover, width, density, context) {
        if (video.cover.isEmpty()) {
            null
        } else {
            val widthPx = with(density) { width.roundToPx() }
            val heightPx = with(density) { VideoThumbHeight.roundToPx() }
            ImageRequest.Builder(context)
                .data(videoThumbUrl(video.cover, widthPx, heightPx))
                .crossfade(true)
                .build()
        }
    }

    Column(modifier = Modifier.width(width)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(VideoThumbHeight)
                .clip(RoundedRectangle(12.dp))
        ) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        VGap(8.dp)
        Text(
            text = video.title,
            style = MaterialTheme.type.subHeadline,
            color = content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** 给视频封面套上 `param=WxH`（网易云图片 CDN 按这个尺寸出图，正好等于卡片方框）。 */
private fun videoThumbUrl(url: String, widthPx: Int, heightPx: Int): String {
    if (url.contains("param=") || widthPx <= 0 || heightPx <= 0) return url
    val separator = if ('?' in url) '&' else '?'
    return "$url${separator}param=${widthPx}y$heightPx"
}
