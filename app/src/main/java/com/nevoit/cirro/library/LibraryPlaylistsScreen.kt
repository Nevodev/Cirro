package com.nevoit.cirro.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kyant.shapes.RoundedRectangle
import com.nevoit.cirro.data.LibraryRepository
import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.forSession
import com.nevoit.cirro.home.OpenCollection
import com.nevoit.cirro.palette.CoverPalette
import com.nevoit.cirro.ui.BottomShade
import com.nevoit.cirro.ui.ItemPosition
import com.nevoit.cirro.ui.TopShade
import com.nevoit.cirro.ui.resolvePosition
import com.nevoit.cirro.ui.roundedShape
import com.nevoit.material.core.component.HGap
import com.nevoit.material.core.component.PageHeader
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.layout.VerticalStack
import com.nevoit.material.core.modifier.thenIf
import com.nevoit.material.theme.MaterialTheme
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun BoxScope.LibraryPlaylistsRoute(
    session: Session,
    onBack: () -> Unit,
    onOpenCollection: OpenCollection,
) {
    val repository = koinInject<LibraryRepository>()
    val library by repository.content.collectAsStateWithLifecycle()
    val content = library?.forSession(session)
    val loading by repository.loading.collectAsStateWithLifecycle()
    val error by repository.error.collectAsStateWithLifecycle()
    val viewModel = koinViewModel<LibraryViewModel>()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val playlists = content?.playlists.orEmpty()
    val listState = rememberLazyListState()

    VerticalStack(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.pageBackground)
    ) {
        item(key = "title") {
            PageHeader(title = "播放列表", modifier = Modifier.padding(horizontal = 12.dp))
        }
        if (error != null) item(key = "library_error") {
            LibraryStatus(error.orEmpty(), viewModel::refresh)
        } else if (content == null) {
            item(key = "loading") {
                LibraryStatus(if (session == Session.Offline) "恢复登录后加载资料库" else "正在加载资料库…")
            }
        } else {
            if (playlists.isEmpty()) item(key = "empty") {
                LibraryStatus(if (loading) "正在加载播放列表…" else "暂无播放列表")
            }
        }
        itemsIndexed(playlists, key = { _, playlist -> playlist.info.id }) { index, playlist ->
            LibraryPlaylistRow(playlist.info, resolvePosition(playlists.size, index)) {
                onOpenCollection(
                    playlist.info.kind,
                    playlist.info.id,
                    playlist.info.title,
                    playlist.info.cover,
                    playlist.info.author
                )
            }
            if (index < playlists.lastIndex) VGap(4.dp)
        }
        item(key = "footer") { LibraryFooter() }
    }
    TopShade(
        color = MaterialTheme.colors.pageBackground,
        threshold = { it.calculateTopPadding() + 12.dp },
        listState = listState,
        modifier = Modifier.height(72.dp + 48.dp)
    )
    BottomShade(color = MaterialTheme.colors.pageBackground)
    LibraryBackButton(onBack)
}

@Composable
private fun LibraryPlaylistRow(
    info: CollectionInfo,
    position: ItemPosition,
    onClick: () -> Unit,
) {
    val palette = koinInject<CoverPalette>()
    LaunchedEffect(info.cover) { palette.prefetch(info.cover) }
    val shape = position.roundedShape()

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(shape)
            .background(MaterialTheme.colors.cardBackground)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = info.cover.takeIf { it.isNotBlank() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .padding(12.dp)
                .clip(RoundedRectangle(8.dp))
                .size(56.dp)
                .thenIf(info.cover.isBlank()) {
                    background(MaterialTheme.colors.cardBackground)
                },
        )
        Column(Modifier.weight(1f)) {
            Text(
                info.title,
                style = MaterialTheme.type.body,
                color = MaterialTheme.colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                info.author,
                style = MaterialTheme.type.footnote,
                color = MaterialTheme.colors.contentVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HGap()
    }
}
