package com.nevoit.cirro.detail

import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nevoit.cirro.R
import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.albumDisplayTitle
import com.nevoit.cirro.home.Section
import com.nevoit.cirro.home.dataOrNull
import com.nevoit.cirro.palette.CoverPalette
import com.nevoit.cirro.ui.CollectionCard
import com.nevoit.cirro.ui.rememberCoverRequest
import com.nevoit.kinetic.layout.KineticLazyRow
import com.nevoit.kinetic.layout.overscroll.rememberOffsetOverscrollEffect
import com.nevoit.kinetic.layout.snap.rememberKineticSnapFlingBehavior
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.interaction.AlphaIndication
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.tokens.Springs
import org.koin.compose.koinInject

/**
 * 区块标题：右边带一个箭头表示这块钱能点进「全部」。没有去处时传 null，
 * 箭头和点击一起去掉（专辑页那两块就是——它们没有对应的大页）。
 */
@Composable
internal fun RecommendationTitle(
    title: String,
    content: Color,
    contentVariant: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .then(
                if (onClick == null) Modifier else Modifier.clickable(
                    interactionSource = null,
                    enabled = true,
                    onClick = onClick,
                    indication = AlphaIndication()
                )
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.type.title4Emphasized,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onClick != null) {
            Icon(
                painter = painterResource(R.drawable.chevron_right_semibold),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = contentVariant
            )
        }
    }
}

@Composable
internal fun LazyItemScope.AlbumWorksSection(
    title: String,
    albums: Section<List<CollectionInfo>>,
    content: Color,
    contentVariant: Color,
    onOpenAlbum: (CollectionInfo) -> Unit,
    onRetry: () -> Unit,
    onOpenAll: (() -> Unit)? = null,
) {
    val works = albums.dataOrNull.orEmpty()
    // remember 必须在提前 return 之前：条件调用会让槽位错位。
    val rowState = rememberLazyListState()
    if (works.isEmpty() && albums !is Section.Failed || albums is Section.Failed) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateItem(tween(200), Springs.crisp(), tween(200))
    ) {
        VGap(36.dp)
        RecommendationTitle(title, content, contentVariant, onClick = onOpenAll)
        VGap()
        KineticLazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            flingBehavior = rememberKineticSnapFlingBehavior(
                rowState,
                snapPosition = SnapPosition.Start,
            ),
            overscrollEffect = rememberOffsetOverscrollEffect(rowState),
            scrollIndicator = null,
        ) {
            items(works, key = { it.id }) { album ->
                // 卡上的封面就是点进去那张专辑的头部封面：图一露头就把主色取了（跟首页横滑区、
                // 资料库专辑格同一个约定）。点进去时 `CollectionRoute` 的 `cached()` 直接命中，
                // 底色第一帧就是对的 —— 不然详情页要等进场后再现算，看着就是"点进去才变色"。
                val palette = koinInject<CoverPalette>()
                LaunchedEffect(album.cover) {
                    palette.prefetch(album.cover)
                }
                CollectionCard(
                    title = albumDisplayTitle(album.title, album.albumType),
                    subtitle = album.author,
                    model = rememberCoverRequest(album.cover),
                    contentColor = content,
                    subtitleColor = contentVariant,
                    onClick = { onOpenAlbum(album) },
                    modifier = Modifier.width(152.dp),
                )
            }
        }
    }
}
