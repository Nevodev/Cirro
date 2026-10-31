package com.nevoit.cirro.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.nevoit.cirro.R
import com.nevoit.cirro.data.model.AudioQuality
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.hasPendingInserts
import com.nevoit.cirro.home.OpenCollection
import com.nevoit.cirro.playback.PlaybackConnection
import com.nevoit.material.core.component.BottomPopupMenu
import com.nevoit.material.core.component.CircleIconButton
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.interaction.DimIndication
import com.nevoit.material.core.layout.ListStack
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.local.ProvideContentColor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun SongCreditsScreen(song: Song, onBack: () -> Unit, onOpenCollection: OpenCollection) {
    val viewModel: SongMenuViewModel = koinViewModel()
    val creditsViewModel: SongCreditsViewModel = koinViewModel { parametersOf(song) }
    val page by creditsViewModel.state.collectAsStateWithLifecycle()
    val playback = koinInject<PlaybackConnection>()
    val context = LocalContext.current
    val displaySong = page.song
    val queueActions by remember(playback) {
        playback.queue.map { it.entries.isNotEmpty() to it.hasPendingInserts }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false to false)
    LaunchedEffect(viewModel, context) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    val onPlay = { playback.playSongs(listOf(displaySong), 0) }
    val availableQualities = remember(page.qualityInfo) {
        page.qualityInfo?.displayQualities.orEmpty()
    }
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        ListStack(
            modifier = Modifier.fillMaxSize(),
            state = listState,
        ) {
            item(key = "song-info") {
                CreditsSongInfo(displaySong, page.releaseDate, onPlay)
            }
            page.credits.forEach { group ->
                Section(header = { group.title }) {
                    group.people.forEach { person ->
                        Row(leading = { CreditAvatar(person.avatarUrl) }) {
                            Column {
                                Text(person.name, style = MaterialTheme.type.body)
                                if (person.roles.isNotEmpty()) {
                                    Text(
                                        text = person.roles.joinToString("、"),
                                        style = MaterialTheme.type.footnote,
                                        color = MaterialTheme.colors.contentVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (availableQualities.isNotEmpty()) {
                Section(header = { "可用音频质量" }, key = "audio-quality") {
                    availableQualities.forEach { quality ->
                        val iconRes = quality.iconRes
                        Row(
                            key = "audio-quality-${quality.wire}",
                            leading = if (iconRes == null) null else {
                                { AudioQualityIcon(iconRes) }
                            },
                        ) {
                            Column {
                                Text(quality.displayName, style = MaterialTheme.type.body)
                                Text(
                                    text = quality.description,
                                    style = MaterialTheme.type.footnote,
                                    color = MaterialTheme.colors.contentVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        TopShade(
            color = MaterialTheme.colors.pageBackground,
            listState = listState,
            threshold = { it.calculateTopPadding() + 12.dp },
            modifier = Modifier.height(120.dp),
        )
        BottomShade(color = MaterialTheme.colors.pageBackground)
        Box(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            CircleIconButton(
                iconRes = R.drawable.chevron_left_nav,
                label = "返回",
                background = MaterialTheme.colors.iconButtonBackground,
                tint = MaterialTheme.colors.onIconButton,
                onClick = onBack,
            )
        }
        BottomPopupMenu(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp),
            menuContent = {
                SongMenuContent(
                    song = displaySong,
                    canInsertQueue = queueActions.first,
                    hasInsertQueue = queueActions.second,
                    viewModel = viewModel,
                    onPlay = onPlay,
                    onAddNext = { playback.addNext(displaySong) },
                    onAddToInsertQueue = { playback.addToInsertQueue(displaySong) },
                    onShare = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "${displaySong.name} - ${displaySong.artistText}\nhttps://music.163.com/song?id=${displaySong.id}",
                            )
                        }
                        context.startActivity(Intent.createChooser(intent, "分享歌曲"))
                    },
                    onOpenAlbum = { metadata ->
                        onOpenCollection(
                            CollectionKind.ALBUM,
                            metadata.albumId,
                            metadata.album,
                            metadata.cover,
                            ""
                        )
                    },
                    onOpenArtist = { id, name ->
                        onOpenCollection(CollectionKind.ARTIST, id, name, "", "热门歌曲")
                    },
                )
            },
        ) { openMenu ->
            CircleIconButton(
                iconRes = R.drawable.ellipsis,
                label = "更多",
                background = MaterialTheme.colors.iconButtonBackground,
                tint = MaterialTheme.colors.onIconButton,
                onClick = openMenu,
            )
        }
    }
}

private val AudioQuality.description: String
    get() = when (this) {
        AudioQuality.STANDARD -> "标准压缩，适合节省流量。"
        AudioQuality.EXHIGH -> "高码率压缩，保留更多声音细节。"
        AudioQuality.LOSSLESS -> "无损压缩，保留原始音频中的声音细节。"
        AudioQuality.HIRES -> "高解析度无损，支持更高的采样率或位深。"
        AudioQuality.DOLBY -> "杜比全景声，呈现具有空间感的沉浸式音频。"
        else -> error("Unsupported audio quality: $wire")
    }

private val AudioQuality.iconRes: Int?
    get() = when (this) {
        AudioQuality.LOSSLESS, AudioQuality.HIRES -> R.drawable.lossless
        AudioQuality.DOLBY -> R.drawable.dolby
        else -> null
    }

@Composable
private fun AudioQualityIcon(iconRes: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFA3ACB7),
                        Color(0xFF868996)
                    )
                ),
                CircleShape
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun CreditsSongInfo(song: Song, releaseDate: String?, onPlay: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 24.dp, top = 84.dp, end = 24.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = rememberCoverRequest(song.cover),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(180.dp)
                .clip(RoundedRectangle(12.dp))
                .background(MaterialTheme.colors.cardBackground),
        )
        VGap(20.dp)
        Text(
            text = song.name,
            style = MaterialTheme.type.title4Emphasized,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        VGap(4.dp)
        val subtitle = song.artists.filter { it.isNotBlank() }.joinToString(" & ") + " · " +
                listOfNotNull(
                    song.album.takeIf { it.isNotBlank() },
                    releaseDate
                ).joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.type.subHeadline,
                color = MaterialTheme.colors.contentVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        VGap(20.dp)
        Row(
            modifier = Modifier
                .width(160.dp)
                .height(48.dp)
                .background(MaterialTheme.colors.iconButtonBackground, Capsule())
                .clickable(
                    enabled = song.playable,
                    onClick = onPlay,
                    indication = DimIndication(shape = Capsule()),
                    interactionSource = null
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProvideContentColor(MaterialTheme.colors.primary) {
                Icon(
                    painter = painterResource(R.drawable.play_fill),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Text(text = "播放", style = MaterialTheme.type.body)
            }
        }
    }
}

@Composable
private fun CreditAvatar(url: String) {
    val imageSize = with(LocalDensity.current) { 40.dp.roundToPx() }.toString()
    val imageUrl = url.replace("{w}", imageSize).replace("{h}", imageSize)
    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl.isNotBlank()) {
            AsyncImage(
                model = rememberCoverRequest(imageUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
            )
        }
    }
}
