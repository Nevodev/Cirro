package com.nevoit.cirro.library

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nevoit.cirro.data.LibraryRepository
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.forSession
import com.nevoit.cirro.home.LoginCard
import com.nevoit.cirro.ui.BottomShade
import com.nevoit.material.core.component.PageHeader
import com.nevoit.material.core.interaction.rememberFlingBehavior
import com.nevoit.material.theme.MaterialTheme
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun BoxScope.LibraryAlbumsRoute(
    session: Session,
    onBack: () -> Unit,
    onOpenLibraryAlbum: (Long, String, String) -> Unit,
) {
    val repository = koinInject<LibraryRepository>()
    val library by repository.content.collectAsStateWithLifecycle()
    val content = library?.forSession(session)
    val loading by repository.loading.collectAsStateWithLifecycle()
    val error by repository.error.collectAsStateWithLifecycle()
    val viewModel = koinViewModel<LibraryViewModel>()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val albums = content?.albums.orEmpty()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(AlbumGridMinSize),
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.pageBackground),
        contentPadding = AlbumGridPadding,
        flingBehavior = rememberFlingBehavior(),
    ) {
        item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
            PageHeader(title = "专辑", modifier = Modifier.padding(horizontal = 2.dp))
        }
        if (error != null) item(key = "library_error", span = { GridItemSpan(maxLineSpan) }) {
            LibraryStatus(error.orEmpty(), viewModel::refresh)
        }
        if (session == Session.LoggedOut) {
            item(key = "login", span = { GridItemSpan(maxLineSpan) }) { LoginCard() }
        } else if (content == null) {
            item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                LibraryStatus(if (session == Session.Offline) "恢复登录后加载资料库" else "正在加载资料库…")
            }
        } else {
            if (albums.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                LibraryStatus(if (loading) "正在加载专辑…" else "暂无专辑")
            }
            items(albums, key = { it.info.id }) { album ->
                LibraryAlbumGridCard(album, onOpenLibraryAlbum)
            }
        }
        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) { LibraryFooter() }
    }
    BottomShade(color = MaterialTheme.colors.pageBackground)
    LibraryBackButton(onBack)
}
