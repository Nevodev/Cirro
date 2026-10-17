package com.nevoit.cirro.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nevoit.cirro.R
import com.nevoit.cirro.data.HomeRepository
import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.LibraryCollection
import com.nevoit.cirro.data.model.albumDisplayTitle
import com.nevoit.cirro.palette.CoverPalette
import com.nevoit.cirro.ui.CollectionCard
import com.nevoit.material.core.component.CircleIconButton
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.interaction.DimIndication
import com.nevoit.material.core.utility.NavigationBarSpacer
import com.nevoit.material.theme.MaterialTheme
import org.koin.compose.koinInject

internal val AlbumGridMinSize = 140.dp
internal val AlbumGridPadding = PaddingValues(horizontal = 10.dp)

@Composable
internal fun BoxScope.LibraryBackButton(onBack: () -> Unit) {
    Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp)) {
        CircleIconButton(
            iconRes = R.drawable.chevron_left_nav,
            label = "返回资料库",
            background = MaterialTheme.colors.secondaryContainer,
            tint = MaterialTheme.colors.onSecondaryContainer,
            onClick = onBack,
        )
    }
}

@Composable
internal fun LibraryCollectionRow(info: CollectionInfo, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LibraryCover(info.cover, Modifier.size(64.dp))
        Column(Modifier.weight(1f)) {
            Text(
                info.title, style = MaterialTheme.type.body, color = MaterialTheme.colors.content,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                info.author, style = MaterialTheme.type.footnote, color = MaterialTheme.colors.contentVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun LibraryAlbumGridCard(
    album: LibraryCollection,
    onOpenLibraryAlbum: (Long, String, String, String) -> Unit,
) {
    val title = rememberLibraryCollectionTitle(album.info)
    val palette = koinInject<CoverPalette>()

    LaunchedEffect(album.info.cover) {
        palette.prefetch(album.info.cover)
    }
    Column(Modifier.padding(horizontal = 6.dp)) {
        CollectionCard(
            title = title,
            subtitle = album.info.author,
            model = album.info.cover.takeIf { it.isNotBlank() },
            onClick = { onOpenLibraryAlbum(album.info.id, title, album.info.cover, album.info.author) },
            modifier = Modifier.fillMaxWidth(),
            indication = DimIndication(),
            coverBackground = MaterialTheme.colors.cardBackground,
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
private fun LibraryCover(url: String, modifier: Modifier) {
    AsyncImage(
        model = url.takeIf { it.isNotBlank() },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .background(MaterialTheme.colors.cardBackground),
    )
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

@Composable
internal fun LibraryFooter() {
    Column {
        VGap(36.dp)
        VGap(144.dp)
        NavigationBarSpacer()
    }
}
