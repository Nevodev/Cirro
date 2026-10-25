package com.nevoit.cirro.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nevoit.cirro.R
import com.nevoit.cirro.data.HomeRepository
import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.LibraryCollection
import com.nevoit.cirro.data.model.albumDisplayTitle
import com.nevoit.cirro.palette.CoverPalette
import com.nevoit.cirro.ui.CollectionCard
import com.nevoit.cirro.ui.rememberCoverRequest
import com.nevoit.material.core.component.CircleIconButton
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.interaction.DimIndication
import com.nevoit.material.core.modifier.thenIf
import com.nevoit.material.theme.MaterialTheme
import org.koin.compose.koinInject

internal val AlbumGridMinSize = 140.dp

/**
 * 列表行里的小封面。尺寸和裁剪由调用方给，没图的时候垫一层行底色占位。
 */
@Composable
internal fun LibraryCover(
    cover: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    AsyncImage(
        model = remember(context, cover) {
            ImageRequest.Builder(context)
                .data(cover.takeIf { it.isNotBlank() })
                .crossfade(true)
                .build()
        },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.thenIf(cover.isBlank()) {
            background(MaterialTheme.colors.cardBackground)
        },
    )
}

@Composable
internal fun BoxScope.LibraryBackButton(onBack: () -> Unit) {
    Box(
        Modifier
            .align(Alignment.TopStart)
            .statusBarsPadding()
            .padding(12.dp)
    ) {
        CircleIconButton(
            iconRes = R.drawable.chevron_left_nav,
            label = "返回资料库",
            background = MaterialTheme.colors.iconButtonBackground,
            tint = MaterialTheme.colors.onIconButton,
            onClick = onBack,
        )
    }
}

@Composable
internal fun LibraryAlbumGridCard(
    album: LibraryCollection,
    onOpenLibraryAlbum: (Long, String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = rememberLibraryCollectionTitle(album.info)
    val palette = koinInject<CoverPalette>()

    LaunchedEffect(album.info.cover) {
        palette.prefetch(album.info.cover)
    }
    Column(modifier.padding(horizontal = 6.dp)) {
        CollectionCard(
            title = title,
            subtitle = album.info.author,
            model = rememberCoverRequest(album.info.cover),
            onClick = {
                onOpenLibraryAlbum(
                    album.info.id,
                    title,
                    album.info.cover,
                    album.info.author
                )
            },
            modifier = Modifier.fillMaxWidth(),
            indication = DimIndication(),
        )
        VGap(16.dp)
    }
}

@Composable
private fun rememberLibraryCollectionTitle(info: CollectionInfo): String {
    if (info.kind != CollectionKind.ALBUM) return info.title
    val repository = koinInject<HomeRepository>()
    var type by remember(repository, info.id, info.albumType) {
        mutableStateOf(info.albumType.ifEmpty { repository.albumTypeSnapshot(info.id).orEmpty() })
    }
    LaunchedEffect(repository, info.id, info.albumType) {
        if (info.albumType.isEmpty()) type = repository.albumType(info.id)
    }
    return albumDisplayTitle(info.title, type)
}


@Composable
internal fun LibraryStatus(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text, style = MaterialTheme.type.footnote, color = MaterialTheme.colors.contentVariant,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    )
}
