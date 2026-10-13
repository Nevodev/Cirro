package com.nevoit.cirro.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nevoit.cirro.R
import com.nevoit.cirro.data.LibraryRepository
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.forSession
import com.nevoit.cirro.data.model.recentAlbums
import com.nevoit.cirro.ui.ItemPosition
import com.nevoit.cirro.ui.roundedShape
import com.nevoit.cirro.home.LoginCard
import com.nevoit.cirro.home.SectionTitle
import com.nevoit.cirro.palette.CoverPalette
import com.nevoit.material.core.component.HGap
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.PageHeader
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.interaction.rememberFlingBehavior
import com.nevoit.material.theme.MaterialTheme
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

enum class LibraryCategory(val title: String, val icon: Int) {
    LIKED("喜爱歌曲", R.drawable.star),
    PLAYLISTS("播放列表", R.drawable.music_note_list),
    ARTISTS("艺人", R.drawable.microphone_stand),
    ALBUMS("专辑", R.drawable.album),
    SONGS("歌曲", R.drawable.music_note),
}

@Composable
fun BoxScope.LibraryRoute(
    session: Session,
    onOpenLibraryAlbum: (Long, String, String) -> Unit,
    onOpenLikedSongs: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenArtists: () -> Unit,
    onOpenAlbums: () -> Unit,
    onOpenSongs: () -> Unit,
) {
    val repository = koinInject<LibraryRepository>()
    val snapshot by repository.snapshot.collectAsStateWithLifecycle()
    val loading by repository.loading.collectAsStateWithLifecycle()
    val error by repository.error.collectAsStateWithLifecycle()
    val viewModel = koinViewModel<LibraryViewModel>()
    val current = snapshot?.forSession(session)
    val recent = remember(current) { current?.recentAlbums().orEmpty().take(64) }
    val palette = koinInject<CoverPalette>()

    LaunchedEffect(recent) { recent.take(24).forEach { palette.prefetch(it.info.cover) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(AlbumGridMinSize),
        modifier = Modifier.fillMaxSize(),
        contentPadding = AlbumGridPadding,
        flingBehavior = rememberFlingBehavior(),
    ) {
        item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
            PageHeader(
                title = "资料库",
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
        if (session == Session.LoggedOut) {
            item(key = "login", span = { GridItemSpan(maxLineSpan) }) {
                LoginCard()
            }
        } else {
            item(key = "categories", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    val shortcuts = listOf(
                        LibraryCategory.LIKED to onOpenLikedSongs,
                        LibraryCategory.PLAYLISTS to onOpenPlaylists,
                        LibraryCategory.ARTISTS to onOpenArtists,
                        LibraryCategory.ALBUMS to onOpenAlbums,
                        LibraryCategory.SONGS to onOpenSongs,
                    )
                    shortcuts.forEachIndexed { index, (category, onOpen) ->
                        val position = when (index) {
                            0 -> ItemPosition.First
                            shortcuts.lastIndex -> ItemPosition.Last
                            else -> ItemPosition.Middle
                        }

                        val shape = position.roundedShape()

                        Row(
                            modifier = Modifier
                                .padding(horizontal = 2.dp)
                                .fillMaxWidth()
                                .clip(shape)
                                .clickable(onClick = onOpen)
                                .background(MaterialTheme.colors.cardBackground)
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(category.icon),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colors.primary
                            )
                            HGap(16.dp)
                            Text(
                                category.title,
                                style = MaterialTheme.type.body,
                                color = MaterialTheme.colors.content,
                                modifier = Modifier.weight(1f)
                            )
                            HGap()
                            Icon(
                                painter = painterResource(R.drawable.ic_chevron_right_compact),
                                contentDescription = null,
                                modifier = Modifier
                                    .width(12.dp),
                                tint = MaterialTheme.colors.contentVariant.copy(.2f)
                            )
                        }
                        if (index < shortcuts.lastIndex) VGap(4.dp)
                    }
                }
            }
            item(key = "recent_title", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    VGap(36.dp)
                    SectionTitle("最近添加", 14.dp)
                    VGap(16.dp)
                }
            }
            if (error != null) {
                item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                    LibraryStatus(error.orEmpty(), onClick = viewModel::refresh)
                }
            }
            if (recent.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    LibraryStatus(
                        when {
                            session == Session.Offline -> "恢复网络后可加载资料库"
                            loading || current == null || !current.likesLoaded || !current.albumsLoaded -> "正在加载资料库…"
                            else -> "喜爱歌曲或收藏专辑后，会显示在这里"
                        }
                    )
                }
            }
            items(recent, key = { it.info.id }) { album ->
                LibraryAlbumGridCard(album, onOpenLibraryAlbum)
            }
        }
        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) { LibraryFooter() }
    }
}
