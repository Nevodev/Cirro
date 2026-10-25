package com.nevoit.cirro.library

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nevoit.cirro.data.LibraryRepository
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.forSession
import com.nevoit.cirro.ui.BottomShade
import com.nevoit.cirro.ui.TopShade
import com.nevoit.kinetic.layout.KineticLazyVerticalGrid
import com.nevoit.kinetic.layout.overscroll.rememberOffsetOverscrollEffect
import com.nevoit.material.core.component.PageHeader
import com.nevoit.material.core.layout.rememberNavigationTailPadding
import com.nevoit.material.core.layout.rememberOverlayScrollIndicatorSpec
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.tokens.Springs
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun BoxScope.LibraryAlbumsRoute(
    session: Session,
    onBack: () -> Unit,
    onOpenLibraryAlbum: (Long, String, String, String) -> Unit,
) {
    val repository = koinInject<LibraryRepository>()
    val library by repository.content.collectAsStateWithLifecycle()
    val content = library?.forSession(session)
    val loading by repository.loading.collectAsStateWithLifecycle()
    val error by repository.error.collectAsStateWithLifecycle()
    val viewModel = koinViewModel<LibraryViewModel>()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val albums = content?.albums.orEmpty()
    val listState = rememberLazyGridState()
    val search = rememberLibrarySearch(
        content?.userId, content != null && session != Session.LoggedOut, listState,
    )
    val searchResult = rememberLibraryCollectionSearchResults(albums, search.query)
    val visibleAlbums = if (search.query.isBlank()) albums else searchResult?.matches.orEmpty()

    val tailPadding = rememberNavigationTailPadding()
    val overscrollEffect = rememberOffsetOverscrollEffect(listState)
    val scrollIndicator =
        rememberOverlayScrollIndicatorSpec(tailPadding)

    KineticLazyVerticalGrid(
        state = listState,
        columns = GridCells.Adaptive(AlbumGridMinSize),
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .onGloballyPositioned(search.onListPositioned)
            .background(MaterialTheme.colors.pageBackground),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = tailPadding),
        overscrollEffect = overscrollEffect,
        scrollIndicator = scrollIndicator,
    ) {
        if (search.floating) item(key = "search-header-space", span = { GridItemSpan(maxLineSpan) }) {
            Spacer(
                Modifier.animateItem(tween(200), Springs.crisp(), tween(200))
                    .statusBarsPadding().height(72.dp)
            )
        } else item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
            PageHeader(
                title = "专辑",
                modifier = Modifier.animateItem(tween(200), Springs.crisp(), tween(200)).padding(horizontal = 2.dp),
            )
        }
        if (search.available) item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
            LibrarySearchInline(
                search,
                Modifier.animateItem(tween(200), Springs.crisp(), tween(200)).padding(horizontal = 2.dp),
            )
        }
        if (error != null) item(key = "library_error", span = { GridItemSpan(maxLineSpan) }) {
            LibraryStatus(error.orEmpty(), viewModel::refresh)
        } else if (content == null) {
            item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                LibraryStatus(if (session == Session.Offline) "恢复登录后加载资料库" else "正在加载资料库…")
            }
        } else {
            if (albums.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                LibraryStatus(if (loading) "正在加载专辑…" else "暂无专辑")
            }
        }
        items(visibleAlbums, key = { it.info.id }) { album ->
            LibraryAlbumGridCard(
                album, onOpenLibraryAlbum,
                Modifier.animateItem(tween(200), Springs.crisp(), tween(200)),
            )
        }
    }
    TopShade(
        color = MaterialTheme.colors.pageBackground,
        threshold = { it.calculateTopPadding() + 12.dp },
        listState = listState,
        modifier = Modifier.height(
            72.dp + 48.dp + if (search.floating) 12.dp + LibrarySongSearchHeight else 0.dp
        )
    )
    LibrarySearchOverlay(search)
    BottomShade(color = MaterialTheme.colors.pageBackground)
    LibraryBackButton { if (search.active) search.close() else onBack() }
}
