package com.nevoit.cirro.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nevoit.cirro.data.LibraryRepository
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.forSession
import com.nevoit.cirro.detail.CollectionRoute
import com.nevoit.cirro.home.LoginCard
import com.nevoit.cirro.home.OpenCollection
import com.nevoit.cirro.ui.BottomShade
import com.nevoit.kinetic.layout.rememberKineticFlingBehavior
import com.nevoit.material.core.component.PageHeader
import com.nevoit.material.theme.MaterialTheme
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun BoxScope.LibraryLikedSongsRoute(
    session: Session,
    onBack: () -> Unit,
    onOpenCollection: OpenCollection,
    onStatusBarAppearanceChanged: (Boolean?) -> Unit = {},
) {
    val repository = koinInject<LibraryRepository>()
    val library by repository.snapshot.collectAsStateWithLifecycle()
    val snapshot = library?.forSession(session)
    val viewModel = koinViewModel<LibraryViewModel>()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val showsCollection = snapshot != null && snapshot.likedPlaylistId > 0
    LaunchedEffect(showsCollection, onStatusBarAppearanceChanged) {
        if (!showsCollection) onStatusBarAppearanceChanged(null)
    }

    if (snapshot != null && snapshot.likedPlaylistId > 0) {
        CollectionRoute(
            kind = CollectionKind.PLAYLIST,
            id = snapshot.likedPlaylistId,
            titleHint = "喜爱歌曲",
            coverHint = "",
            onBack = onBack,
            onOpenCollection = onOpenCollection,
            likedPlaylist = true,
            onStatusBarAppearanceChanged = onStatusBarAppearanceChanged,
        )
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.pageBackground),
            flingBehavior = rememberKineticFlingBehavior(),
        ) {
            item(key = "title") {
                PageHeader(title = "喜爱歌曲", modifier = Modifier.padding(horizontal = 12.dp))
            }
            if (session == Session.LoggedOut) {
                item(key = "login") { LoginCard(Modifier.padding(horizontal = 12.dp)) }
            } else if (snapshot == null) {
                item(key = "loading") {
                    LibraryStatus(if (session == Session.Offline) "恢复登录后加载资料库" else "正在加载资料库…")
                }
            } else {
                item(key = "liked_loading") { LibraryStatus("正在加载喜爱歌曲…") }
            }
        }
        BottomShade(color = MaterialTheme.colors.pageBackground)
        LibraryBackButton(onBack)
    }
}
