package com.nevoit.cirro.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kyant.shapes.RoundedRectangle
import com.nevoit.cirro.R
import com.nevoit.cirro.data.LibraryRepository
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.forSession
import com.nevoit.material.core.animation.rememberPressAnimatable
import com.nevoit.material.core.component.BottomPopupMenuScope
import com.nevoit.material.core.component.HGap
import com.nevoit.material.core.component.Icon
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.tokens.Springs
import org.koin.compose.koinInject

val LocalOpenSongCredits = staticCompositionLocalOf<(Song) -> Unit> { {} }

@Composable
fun BottomPopupMenuScope.SongMenuContent(
    song: Song,
    canInsertQueue: Boolean,
    hasInsertQueue: Boolean,
    viewModel: SongMenuViewModel,
    onPlay: () -> Unit,
    onAddNext: () -> Unit,
    onAddToInsertQueue: () -> Unit,
    onShare: () -> Unit,
    onOpenAlbum: (Song) -> Unit,
    onOpenArtist: (Long, String) -> Unit,
    playerMenu: Boolean = false,
) {
    val state by viewModel.state.collectAsState()
    val snapshot by viewModel.likeSnapshot.collectAsState()
    val session by viewModel.session.collectAsState()
    val librarySnapshot by koinInject<LibraryRepository>().snapshot.collectAsState()
    val openCredits = LocalOpenSongCredits.current
    val userId = (session as? Session.LoggedIn)?.profile?.userId?.takeIf { it > 0 }
    val knownLike =
        snapshot.takeIf { userId != null && it.userId == userId }?.likedIds?.contains(song.id)
    val pendingLike = state.pendingLike?.takeIf { it.songId == song.id && it.userId == userId }
    val liked = pendingLike?.liked ?: knownLike
    var choosingPlaylist by remember(song.id, userId) { mutableStateOf(false) }
    var choosingArtist by remember(song.id, userId) { mutableStateOf(false) }
    var navigationSong by remember(song) { mutableStateOf(song) }
    LaunchedEffect(song) {
        // 旧缓存没有导航 id，打开菜单时补齐，避免等到缓存过期后才能跳转。
        if (song.albumId <= 0 || song.artistIds.size != song.artists.size || song.artistIds.any { it <= 0 }) {
            navigationSong = viewModel.navigationSong(song)
        }
    }
    val artists = remember(navigationSong.artistIds, navigationSong.artists) {
        navigationSong.artists.mapIndexedNotNull { index, name ->
            navigationSong.artistIds.getOrNull(index)?.takeIf { it > 0 }?.let { it to name }
        }.distinctBy { it.first }
    }

    Column {
        if (choosingPlaylist) {
            SongMenuItem(
                "返回歌曲操作",
                R.drawable.chevron_left_nav,
                contentColor
            ) { choosingPlaylist = false }
            when {
                state.loadingPlaylists -> SongMenuStatus("加载播放列表中…", contentVariantColor)
                state.playlistError != null -> {
                    SongMenuStatus(state.playlistError.orEmpty(), contentVariantColor)
                    SongMenuItem(
                        "重试",
                        R.drawable.resource_return,
                        contentColor,
                        onClick = viewModel::loadPlaylists
                    )
                }

                state.playlists.isNullOrEmpty() -> SongMenuStatus(
                    "还没有可以添加的播放列表，请先创建播放列表",
                    contentVariantColor
                )

                else -> state.playlists.orEmpty().forEach { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = state.addingPlaylistId == null) {
                                viewModel.addToPlaylist(song.id, playlist, ::dismiss)
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = playlist.cover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedRectangle(8.dp)),
                        )
                        HGap(12.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            SongMenuStatus(playlist.name, contentColor, padded = false)
                            SongMenuStatus(
                                if (state.addingPlaylistId == playlist.id) "添加中…" else "${playlist.trackCount} 首歌曲",
                                contentVariantColor,
                                padded = false,
                            )
                        }
                    }
                }
            }
        } else if (choosingArtist) {
            SongMenuItem(
                "返回歌曲操作",
                R.drawable.chevron_left_nav,
                contentColor
            ) { choosingArtist = false }
            artists.forEach { (id, name) ->
                SongMenuItem(name, R.drawable.person, contentColor) {
                    dismiss()
                    onOpenArtist(id, name)
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!playerMenu) {
                    SongMenuAction(
                        "播放",
                        R.drawable.play,
                        actionContentColor,
                        backgroundColor = actionContainerColor
                    ) {
                        onPlay()
                        dismiss()
                    }
                }
                SongMenuAction(
                    text = if (liked == true) "取消喜爱" else "喜爱",
                    icon = if (liked == true) R.drawable.star_fill else R.drawable.star,
                    color = actionContentColor,
                    backgroundColor = actionContainerColor,
                    selected = liked == true,
                    enabled = state.pendingLike == null && (userId == null || knownLike != null)
                ) { viewModel.toggleLike(song.id) }
                SongMenuAction(
                    "分享",
                    R.drawable.share,
                    actionContentColor,
                    backgroundColor = actionContainerColor
                ) {
                    onShare()
                    dismiss()
                }
            }
            SongMenuItem("添加到播放列表", R.drawable.list_add, contentColor) {
                viewModel.loadPlaylists()
                if (userId != null) choosingPlaylist = true
            }
            if (canInsertQueue) {
                SongMenuItem(
                    "下一首播放",
                    R.drawable.list_insert,
                    contentColor
                ) {
                    onAddNext()
                    dismiss()
                }
            }
            if (hasInsertQueue) {
                SongMenuItem(
                    "添加到插播队列",
                    R.drawable.list_append,
                    contentColor,
                    enabled = canInsertQueue
                ) {
                    onAddToInsertQueue()
                    dismiss()
                }
            }
            SongMenuDivider(contentVariantColor)
            SongMenuItem(
                text = "前往专辑",
                icon = R.drawable.album,
                color = contentColor,
                enabled = navigationSong.albumId > 0,
                subtitle = navigationSong.album,
                subtitleColor = contentVariantColor,
            ) {
                dismiss()
                onOpenAlbum(navigationSong)
            }
            SongMenuItem(
                text = "前往艺人",
                icon = R.drawable.microphone_stand,
                color = contentColor,
                enabled = artists.isNotEmpty(),
                subtitle = navigationSong.artistText,
                subtitleColor = contentVariantColor,
            ) {
                if (artists.size == 1) {
                    dismiss()
                    onOpenArtist(artists.single().first, artists.single().second)
                } else choosingArtist = true
            }
            SongMenuItem(
                "查看制作人员",
                R.drawable.info_circle,
                contentColor,
            ) {
                dismiss()
                openCredits(navigationSong)
            }
            val library = librarySnapshot?.forSession(session)
            val outsideLibrary =
                if (library != null && library.likesLoaded && library.albumsLoaded) {
                    liked != true && library.likedTracks.none { it.id == song.id } &&
                            library.likedSongs.none { it.id == song.id } &&
                            (navigationSong.albumId > 0 && library.albums.none { it.info.id == navigationSong.albumId })
                } else false
            if (outsideLibrary) {
                SongMenuDivider(contentVariantColor)
                SongMenuItem("减少推荐", R.drawable.heart_slash, contentColor) {
                    viewModel.reduceRecommendation(song.id)
                    dismiss()
                }
            }
        }
    }
}

@Composable
private fun SongMenuDivider(color: ColorProducer) {
    Box(Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp)
        .height(1.dp)
        .drawBehind {
            drawRect(color().let { it.copy(alpha = it.alpha * 0.2f) })
        })
}

@Composable
private fun RowScope.SongMenuAction(
    text: String,
    @DrawableRes icon: Int,
    color: ColorProducer,
    backgroundColor: ColorProducer,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val press = rememberPressAnimatable(
        1f,
        0f,
        Springs.smooth(100, 0.0, 0.0001f),
        Springs.smooth(200, 0.2, 0.0001f)
    )

    Box(
        modifier = modifier
            .weight(1f)
            .height(64.dp)
            .graphicsLayer {
                this.shape = RoundedRectangle(16.dp + 16.dp * press.progress.value)
                clip = true
            }
            .drawBehind { drawRect(backgroundColor()) }
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = press.interactionSource,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                painterResource(icon),
                tint = color,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            BasicText(
                text,
                style = MaterialTheme.type.footnote.copy(textMotion = TextMotion.Animated),
                color = color,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SongMenuItem(
    text: String,
    @DrawableRes icon: Int,
    color: ColorProducer,
    enabled: Boolean = true,
    subtitle: String? = null,
    subtitleColor: ColorProducer = color,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.38f
    val iconColor = remember(color, alpha) { ColorProducer { color().copy(alpha = alpha) } }
    val secondaryColor = remember(
        subtitleColor,
        alpha
    ) { ColorProducer { subtitleColor().let { it.copy(alpha = it.alpha * alpha) } } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            painterResource(icon),
            tint = iconColor,
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(
                text = text,
                style = MaterialTheme.type.body.copy(textMotion = TextMotion.Animated),
                color = iconColor
            )
            if (!subtitle.isNullOrBlank()) {
                BasicText(
                    subtitle,
                    style = MaterialTheme.type.footnote.copy(textMotion = TextMotion.Animated),
                    color = secondaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SongMenuStatus(text: String, color: ColorProducer, padded: Boolean = true) {
    BasicText(
        text = text,
        style = MaterialTheme.type.subHeadline,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = if (padded) Modifier.padding(horizontal = 20.dp, vertical = 12.dp) else Modifier,
    )
}
