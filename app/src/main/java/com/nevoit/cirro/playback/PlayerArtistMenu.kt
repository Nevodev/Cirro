package com.nevoit.cirro.playback

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nevoit.cirro.R
import com.nevoit.cirro.data.CollectionRepository
import com.nevoit.cirro.data.LibraryRepository
import com.nevoit.cirro.data.NcmBridge
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.ui.rememberCoverRequest
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.PopupMenu
import com.nevoit.material.core.component.PopupMenuAnchor
import com.nevoit.material.core.component.PopupMenuEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
internal fun PlayerArtistMenu(
    song: NowPlaying,
    connection: PlaybackConnection,
    onOpenCollection: (CollectionKind, Long, String, String, String) -> Unit,
    containerColor: ColorProducer,
    contentColor: ColorProducer,
    contentVariantColor: ColorProducer,
    content: @Composable (openMenu: () -> Unit) -> Unit,
) {
    val repository = koinInject<CollectionRepository>()
    val context = LocalContext.current
    val artistCovers = remember { mutableMapOf<Long, String>() }
    var navigationSong by remember(song.id, connection) {
        mutableStateOf(
            connection.queue.value.entries.firstOrNull { it.song.id == song.id }?.song
                ?: Song(id = song.id, name = song.title, cover = song.cover.orEmpty()),
        )
    }
    var ready by remember(song.id, connection) {
        mutableStateOf(
            navigationSong.albumId > 0 && navigationSong.artists.isNotEmpty() &&
                    navigationSong.artistIds.size == navigationSong.artists.size &&
                    navigationSong.artistIds.all { it > 0 },
        )
    }
    var openRequested by remember(song.id) { mutableStateOf(false) }
    LaunchedEffect(openRequested) {
        if (openRequested && !ready) {
            repository.songMetadata(song.id).onSuccess {
                navigationSong = it
                ready = true
            }.onFailure {
                openRequested = false
                Toast.makeText(context, "无法加载歌曲资料，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val artistIcon = painterResource(R.drawable.microphone_stand)
    val albumIcon = painterResource(R.drawable.album)
    val artists = remember(navigationSong.artists, navigationSong.artistIds) {
        navigationSong.artists.mapIndexedNotNull { index, name ->
            navigationSong.artistIds.getOrNull(index)?.takeIf { it > 0 }?.let { it to name }
        }.distinctBy { it.first }
    }
    val items =
        remember(navigationSong, artists, artistIcon, albumIcon, song.artist, onOpenCollection, contentVariantColor) {
            val artistItems = artists.map { (id, name) ->
                PopupMenuEntry.Item(
                    text = name,
                    leadingContent = { ArtistMenuAvatar(id, artistCovers, contentVariantColor) },
                ) {
                    onOpenCollection(CollectionKind.ARTIST, id, name, "", "")
                }
            }
            listOf(
                PopupMenuEntry.Item(
                    text = "前往专辑",
                    icon = albumIcon,
                    subtitle = navigationSong.album,
                    enabled = navigationSong.albumId > 0,
                ) {
                    onOpenCollection(
                        CollectionKind.ALBUM, navigationSong.albumId, navigationSong.album,
                        navigationSong.cover, navigationSong.artistText,
                    )
                },
                PopupMenuEntry.Item(
                    text = "前往艺人",
                    icon = artistIcon,
                    subtitle = navigationSong.artistText.ifBlank { song.artist },
                    enabled = artists.isNotEmpty(),
                    submenu = if (artists.size > 1) artistItems else emptyList(),
                ) { artistItems.singleOrNull()?.onClick?.invoke() },
            )
        }
    PopupMenu(
        items = items,
        anchor = PopupMenuAnchor.TopStart,
        containerColor = containerColor,
        contentColor = contentColor,
        contentVariantColor = contentVariantColor,
    ) { openMenu ->
        // Publish the resolved list before opening; no data fetch or list replacement enters the animation.
        LaunchedEffect(openRequested, ready) {
            if (openRequested && ready) {
                openMenu()
                openRequested = false
            }
        }
        content { if (ready) openMenu() else openRequested = true }
    }
}

@Composable
private fun ArtistMenuAvatar(
    artistId: Long,
    covers: MutableMap<Long, String>,
    placeholderColor: ColorProducer,
) {
    val library = koinInject<LibraryRepository>()
    var cover by remember(artistId, library) {
        mutableStateOf(
            covers[artistId] ?: library.snapshot.value?.let { snapshot ->
                snapshot.artistCovers[artistId]
                    ?: snapshot.artists.firstOrNull { it.info.id == artistId }?.info?.cover?.takeIf { it.isNotBlank() }
            },
        )
    }
    LaunchedEffect(artistId, library) {
        if (cover == null) {
            val resolved = withContext(Dispatchers.IO) { NcmBridge.artistCover(artistId).getOrNull() }
            if (resolved != null) {
                covers[artistId] = resolved
                cover = resolved
            }
        }
    }
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).drawBehind {
            drawRect(placeholderColor().let { it.copy(alpha = it.alpha * 0.12f) })
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.person_fill),
            tint = placeholderColor,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        if (!cover.isNullOrBlank()) AsyncImage(
            model = rememberCoverRequest(cover),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
