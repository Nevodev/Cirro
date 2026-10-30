package com.nevoit.cirro.library

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nevoit.cirro.data.LibraryRepository
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.QueueSource
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.forSession
import com.nevoit.cirro.data.model.hasPendingInserts
import com.nevoit.cirro.detail.rememberCollectionSongSearchResults
import com.nevoit.cirro.home.LoginCard
import com.nevoit.cirro.home.OpenCollection
import com.nevoit.cirro.playback.PlaybackConnection
import com.nevoit.cirro.playback.rememberIsCurrentSong
import com.nevoit.cirro.ui.BottomShade
import com.nevoit.cirro.ui.SongMenuContent
import com.nevoit.cirro.ui.SongMenuViewModel
import com.nevoit.cirro.ui.TopShade
import com.nevoit.cirro.ui.TrackRow
import com.nevoit.cirro.ui.resolvePosition
import com.nevoit.material.core.component.BottomPopupMenuScope
import com.nevoit.material.core.component.PageHeader
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.layout.VerticalStack
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.tokens.Springs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun BoxScope.LibrarySongsRoute(
    session: Session,
    onBack: () -> Unit,
    onOpenCollection: OpenCollection,
) {
    val repository = koinInject<LibraryRepository>()
    val library by repository.content.collectAsStateWithLifecycle()
    val content = library?.forSession(session)
    val loading by repository.loading.collectAsStateWithLifecycle()
    val viewModel = koinViewModel<LibraryViewModel>()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val songs = content?.songs.orEmpty()
    var songsLoading by remember(content?.userId) { mutableStateOf(false) }
    var songsError by remember(content?.userId) { mutableStateOf<String?>(null) }
    val refreshVersion by viewModel.refreshVersion.collectAsStateWithLifecycle()
    val albumIds = content?.albumIds.orEmpty()
    LaunchedEffect(content?.userId, albumIds, session, refreshVersion) {
        if (content != null && session is Session.LoggedIn) {
            songsLoading = true
            try {
                repository.songs().onSuccess { songsError = null }
                    .onFailure { songsError = "歌曲未能加载，点按重试" }
            } finally {
                songsLoading = false
            }
        }
    }
    val playback = koinInject<PlaybackConnection>()
    val queueActions by remember(playback) {
        playback.queue.map { it.entries.isNotEmpty() to it.hasPendingInserts }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false to false)
    val menu = koinViewModel<SongMenuViewModel>()
    val context = LocalContext.current
    LaunchedEffect(menu) {
        menu.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    val source = remember { QueueSource(title = "歌曲") }
    val subTitle = if (songs.isNotEmpty()) "${songs.size} 首" else null
    val listState = rememberLazyListState()
    val search = rememberLibrarySearch(
        content?.userId, content != null && session != Session.LoggedOut, listState,
    )
    val searchResult = rememberCollectionSongSearchResults(songs, search.query)
    val matches = if (search.query.isNotBlank()) searchResult?.matches else null
    val songCount = matches?.size ?: songs.size
    val playbackSongs = remember(songs, search.query, searchResult) {
        if (search.query.isBlank()) songs
        else searchResult?.takeIf { it.query == search.query }?.matches?.map { it.value }.orEmpty()
    }

    VerticalStack(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .onGloballyPositioned(search.onListPositioned)
            .background(MaterialTheme.colors.pageBackground),
    ) {
        if (search.floating) item(key = "search-header-space") {
            Spacer(
                Modifier
                    .animateItem(tween(200), Springs.crisp(), tween(200))
                    .statusBarsPadding()
                    .height(72.dp)
            )
        } else item(key = "title") {
            PageHeader(
                title = "歌曲",
                subTitle = subTitle,
                modifier = Modifier
                    .animateItem(tween(200), Springs.crisp(), tween(200))
                    .padding(horizontal = 12.dp)
            )
        }
        if (session == Session.LoggedOut) {
            item(key = "login") { LoginCard(Modifier.padding(horizontal = 12.dp)) }
        } else if (content == null) {
            item(key = "loading") {
                LibraryStatus(if (session == Session.Offline) "恢复登录后加载资料库" else "正在加载资料库…")
            }
        } else {
            item(key = "search") {
                LibrarySearchInline(
                    search,
                    Modifier.animateItem(tween(200), Springs.crisp(), tween(200))
                        .padding(horizontal = 12.dp),
                )
            }
            item(key = "play") {
                Column(
                    Modifier
                        .animateItem(tween(200), Springs.crisp(), tween(200))
                        .padding(horizontal = 12.dp)
                ) {
                    LibraryPlaybackButtons(
                        enabled = true,
                        onPlay = { playback.playSongs(playbackSongs, 0, source) },
                        onShuffle = {
                            playback.playSongs(playbackSongs, 0, source, shuffle = true)
                        },
                    )
                    VGap(24.dp)
                }
            }
            if (songsError != null) item(key = "error") {
                LibraryStatus(songsError.orEmpty(), viewModel::refresh)
            }
            if (songs.isEmpty()) item(key = "empty") {
                LibraryStatus(if (songsLoading || loading) "正在加载歌曲…" else "暂无歌曲")
            }
            items(
                count = songCount,
                key = { index -> matches?.get(index)?.value?.id ?: songs[index].id },
            ) { index ->
                val match = matches?.get(index)
                val song = match?.value ?: songs[index]
                val originalIndex = match?.index ?: index
                val play = { playback.playSongs(songs, originalIndex, source) }
                TrackRow(
                    song = song,
                    backgroundColor = MaterialTheme.colors.cardBackground,
                    showArtist = true,
                    index = null,
                    position = resolvePosition(songCount, index),
                    isPlaying = rememberIsCurrentSong(song.id),
                    content = MaterialTheme.colors.content,
                    contentVariant = MaterialTheme.colors.contentVariant,
                    starColor = MaterialTheme.colors.primary,
                    onClick = play,
                    menuContent = {
                        LibrarySongMenuContent(
                            song,
                            source,
                            playback,
                            menu,
                            queueActions,
                            onOpenCollection,
                            play
                        )
                    },
                    // 左右边距在 TrackRow 内部（背景要铺满整宽，左侧那条缝留给红心星）
                    modifier = Modifier.animateItem(tween(200), Springs.crisp(), tween(200)),
                )
                if (index < songCount - 1) VGap(4.dp)
            }
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
    LibraryBackButton {
        if (search.active) search.close() else onBack()
    }
}

@Composable
internal fun BottomPopupMenuScope.LibrarySongMenuContent(
    song: Song,
    source: QueueSource,
    playback: PlaybackConnection,
    menu: SongMenuViewModel,
    queueActions: Pair<Boolean, Boolean>,
    onOpenCollection: OpenCollection,
    onPlay: () -> Unit,
) {
    val context = LocalContext.current
    SongMenuContent(
        song, queueActions.first, queueActions.second, menu,
        onPlay = onPlay,
        onAddNext = { playback.addNext(song, source) },
        onAddToInsertQueue = { playback.addToInsertQueue(song, source) },
        onShare = {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    "${song.name} - ${song.artistText}\nhttps://music.163.com/song?id=${song.id}"
                )
            }
            context.startActivity(Intent.createChooser(intent, "分享歌曲"))
        },
        onOpenAlbum = {
            onOpenCollection(
                CollectionKind.ALBUM,
                it.albumId,
                it.album,
                it.cover,
                ""
            )
        },
        onOpenArtist = { id, name ->
            onOpenCollection(
                CollectionKind.ARTIST,
                id,
                name,
                "",
                "热门歌曲"
            )
        },
    )
}
