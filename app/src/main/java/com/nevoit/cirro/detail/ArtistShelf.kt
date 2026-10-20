package com.nevoit.cirro.detail

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.kyant.shapes.RoundedRectangle
import com.nevoit.cirro.data.CollectionRepository
import com.nevoit.cirro.data.model.ArtistVideo
import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.QueueSource
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.albumDisplayTitle
import com.nevoit.cirro.data.model.hasPendingInserts
import com.nevoit.cirro.home.OpenCollection
import com.nevoit.cirro.home.Section
import com.nevoit.cirro.home.dataOrNull
import com.nevoit.cirro.library.AlbumGridMinSize
import com.nevoit.cirro.library.LibraryBackButton
import com.nevoit.cirro.library.LibraryPlaybackButtons
import com.nevoit.cirro.library.LibrarySongMenuContent
import com.nevoit.cirro.library.LibraryStatus
import com.nevoit.cirro.palette.CoverPalette
import com.nevoit.cirro.playback.PlaybackConnection
import com.nevoit.cirro.playback.rememberIsCurrentSong
import com.nevoit.cirro.ui.BottomShade
import com.nevoit.cirro.ui.CollectionCard
import com.nevoit.cirro.ui.ItemPosition
import com.nevoit.cirro.ui.SongMenuViewModel
import com.nevoit.cirro.ui.TopShade
import com.nevoit.cirro.ui.TrackRow
import com.nevoit.cirro.ui.rememberCoverRequest
import com.nevoit.cirro.ui.resolvePosition
import com.nevoit.cirro.ui.roundedShape
import com.nevoit.kinetic.layout.KineticLazyVerticalGrid
import com.nevoit.kinetic.layout.overscroll.rememberOffsetOverscrollEffect
import com.nevoit.material.core.component.HGap
import com.nevoit.material.core.component.PageHeader
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.interaction.DimIndication
import com.nevoit.material.core.layout.VerticalStack
import com.nevoit.material.core.layout.rememberNavigationTailPadding
import com.nevoit.material.core.layout.rememberOverlayScrollIndicatorSpec
import com.nevoit.material.theme.MaterialTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/** 艺人页标题点进去的那几页。 */
@Serializable
enum class ArtistShelfSection {
    SONGS,
    ALBUMS,
    SINGLES,
    VIDEOS,
}

private val VideoThumbHeight = 84.dp
private val VideoThumbWidth = VideoThumbHeight * 16f / 9f

/**
 * 艺人页某一块的「全部」页。
 *
 * 数据全从 [CollectionRepository] 的艺人缓存里拿（艺人页刚拉过一次，进来基本零请求）。
 * 配色就是普通页面那一套——不跟艺人页的封面种子走。
 */
@Composable
fun BoxScope.ArtistShelfRoute(
    artistId: Long,
    artistName: String,
    section: ArtistShelfSection,
    onBack: () -> Unit,
    onOpenCollection: OpenCollection,
) {
    val viewModel: ArtistShelfViewModel = koinViewModel {
        parametersOf(artistId, section)
    }
    val state by viewModel.state.collectAsState()
    val background = MaterialTheme.colors.pageBackground
    val content = MaterialTheme.colors.content
    val contentVariant = MaterialTheme.colors.contentVariant
    val itemBackground = MaterialTheme.colors.cardBackground

    when (section) {
        ArtistShelfSection.SONGS -> SongShelf(
            state = state,
            artistName = artistName,
            artistId = artistId,
            background = background,
            content = content,
            contentVariant = contentVariant,
            itemBackground = itemBackground,
            onRetry = viewModel::load,
            onOpenCollection = onOpenCollection,
            onBack = onBack,
        )

        ArtistShelfSection.ALBUMS, ArtistShelfSection.SINGLES -> AlbumShelf(
            title = if (section == ArtistShelfSection.ALBUMS) "专辑" else "单曲与EP",
            albums = state.albums.mapData { list ->
                if (section == ArtistShelfSection.ALBUMS) list.filterNot { it.isSingleOrEp() }
                else list.filter { it.isSingleOrEp() }
            },
            artistName = artistName,
            background = background,
            onRetry = viewModel::load,
            onOpenCollection = onOpenCollection,
            onBack = onBack,
        )

        ArtistShelfSection.VIDEOS -> VideoShelf(
            videos = state.videos,
            artistName = artistName,
            background = background,
            content = content,
            contentVariant = contentVariant,
            itemBackground = itemBackground,
            onRetry = viewModel::load,
            onBack = onBack,
        )
    }
}

/**
 * 艺人页几块的数据。一个页面只查自己那一块，副标题要的「年份 / 单曲后缀」由作品列表补齐。
 */
class ArtistShelfViewModel(
    private val repository: CollectionRepository,
    private val artistId: Long,
    private val section: ArtistShelfSection,
) : ViewModel() {

    data class UiState(
        val songs: Section<List<Song>> = Section.Loading,
        val albums: Section<List<CollectionInfo>> = Section.Loading,
        val videos: Section<List<ArtistVideo>> = Section.Loading,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            when (section) {
                // 排行的第二行是「专辑 · 年份」，作品列表也得要（同一份缓存，不额外打请求）
                ArtistShelfSection.SONGS -> {
                    val albums = async { repository.artistAlbums(artistId) }
                    val songs = repository.tracks(CollectionKind.ARTIST, artistId)
                    _state.update {
                        it.copy(songs = songs.toSection(), albums = albums.await().toSection())
                    }
                }

                ArtistShelfSection.ALBUMS, ArtistShelfSection.SINGLES ->
                    _state.update {
                        it.copy(
                            albums = repository.artistAlbums(artistId).toSection()
                        )
                    }

                ArtistShelfSection.VIDEOS ->
                    _state.update {
                        it.copy(
                            videos = repository.artistVideos(artistId).toSection()
                        )
                    }
            }
        }
    }

    private fun <T> Result<T>.toSection(): Section<T> = fold(
        onSuccess = { Section.Ready(it) },
        onFailure = { Section.Failed(it.message.orEmpty().ifEmpty { "加载失败" }) },
    )
}

/** 全部歌曲：跟资料库「全部歌曲」一个布局（播放 / 随机 + 整列表）。 */
@Composable
private fun BoxScope.SongShelf(
    state: ArtistShelfViewModel.UiState,
    artistName: String,
    artistId: Long,
    background: Color,
    content: Color,
    contentVariant: Color,
    itemBackground: Color,
    onRetry: () -> Unit,
    onOpenCollection: OpenCollection,
    onBack: () -> Unit,
) {
    val songs = state.songs.dataOrNull.orEmpty()
    val albumsById =
        remember(state.albums) { state.albums.dataOrNull.orEmpty().associateBy { it.id } }
    val playback = koinInject<PlaybackConnection>()
    val menu: SongMenuViewModel = koinViewModel()
    val context = LocalContext.current
    val queueActions by remember(playback) {
        playback.queue.map { it.entries.isNotEmpty() to it.hasPendingInserts }
            .distinctUntilChanged()
    }.collectAsState(initial = false to false)
    LaunchedEffect(menu) {
        menu.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    val source = remember(artistId) {
        QueueSource(CollectionKind.ARTIST, artistId.toString(), title = artistName)
    }
    val listState = rememberLazyListState()

    VerticalStack(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(background),
    ) {
        item(key = "title") {
            PageHeader(
                title = "歌曲排行",
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        if (state.songs is Section.Failed) {
            item(key = "error") { LibraryStatus("加载失败 · 点按重试", onRetry) }
        }
        item(key = "play") {
            Column(Modifier.padding(horizontal = 12.dp)) {
                LibraryPlaybackButtons(
                    enabled = songs.isNotEmpty(),
                    onPlay = { if (songs.isNotEmpty()) playback.playSongs(songs, 0, source) },
                    onShuffle = {
                        // 跟资料库一致：整份列表入队 + 随机那一轴打开（乱序在 playQueueOf 里做）
                        if (songs.isNotEmpty()) {
                            playback.playSongs(songs, 0, source, shuffle = true)
                        }
                    },
                )
                VGap(24.dp)
            }
        }
        if (songs.isEmpty() && state.songs !is Section.Failed) {
            item(key = "empty") {
                LibraryStatus(if (state.songs is Section.Ready) "暂无歌曲" else "正在加载歌曲…")
            }
        }
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            val play = { playback.playSongs(songs, index, source) }
            TrackRow(
                song = song,
                backgroundColor = itemBackground,
                showArtist = false,
                index = null,
                position = resolvePosition(songs.size, index),
                isPlaying = rememberIsCurrentSong(song.id),
                content = content,
                contentVariant = contentVariant,
                subtitle = rankingSubtitle(song, albumsById[song.albumId]),
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
            )
            if (index < songs.lastIndex) VGap(4.dp)
        }
    }
    TopShade(
        color = background,
        threshold = { it.calculateTopPadding() + 12.dp },
        listState = listState,
        modifier = Modifier.height(72.dp + 48.dp)
    )
    BottomShade(color = background)
    LibraryBackButton(onBack)
}

/** 全部专辑 / 全部单曲与EP：跟资料库「全部专辑」一个网格。 */
@Composable
private fun BoxScope.AlbumShelf(
    title: String,
    albums: Section<List<CollectionInfo>>,
    artistName: String,
    background: Color,
    onRetry: () -> Unit,
    onOpenCollection: OpenCollection,
    onBack: () -> Unit,
) {
    val list = albums.dataOrNull.orEmpty()
    val gridState = rememberLazyGridState()
    val palette = koinInject<CoverPalette>()

    val tailPadding = rememberNavigationTailPadding()
    val overscrollEffect = rememberOffsetOverscrollEffect(gridState)
    val scrollIndicator = rememberOverlayScrollIndicatorSpec(tailPadding)

    KineticLazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(AlbumGridMinSize),
        modifier = Modifier
            .fillMaxSize()
            .background(background),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = tailPadding),
        overscrollEffect = overscrollEffect,
        scrollIndicator = scrollIndicator,
    ) {
        item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
            PageHeader(
                title = title,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
        if (albums is Section.Failed) item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
            LibraryStatus("加载失败 · 点按重试", onRetry)
        }
        if (list.isEmpty() && albums !is Section.Failed) item(
            key = "empty",
            span = { GridItemSpan(maxLineSpan) },
        ) {
            LibraryStatus(if (albums is Section.Ready) "暂无内容" else "正在加载…")
        }
        items(list, key = { it.id }) { album ->
            // 跟资料库专辑格一样：图一露头就取色，点进去详情页第一帧颜色就是对的
            LaunchedEffect(album.cover) { palette.prefetch(album.cover) }
            Column(Modifier.padding(horizontal = 6.dp)) {
                CollectionCard(
                    title = albumDisplayTitle(album.title, album.albumType),
                    subtitle = album.author,
                    model = rememberCoverRequest(album.cover),
                    onClick = {
                        onOpenCollection(
                            CollectionKind.ALBUM,
                            album.id,
                            albumDisplayTitle(album.title, album.albumType),
                            album.cover,
                            album.author,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    indication = DimIndication(),
                )
                VGap(16.dp)
            }
        }
    }
    TopShade(
        color = background,
        threshold = { it.calculateTopPadding() + 12.dp },
        listState = gridState,
        modifier = Modifier.height(72.dp + 48.dp)
    )
    BottomShade(color = background)
    LibraryBackButton(onBack)
}

/** 音乐视频：左 16:9 缩略图 + 右信息，没有尾部按钮（还播不了）。 */
@Composable
private fun BoxScope.VideoShelf(
    videos: Section<List<ArtistVideo>>,
    artistName: String,
    background: Color,
    content: Color,
    contentVariant: Color,
    itemBackground: Color,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val list = videos.dataOrNull.orEmpty()
    val listState = rememberLazyListState()

    VerticalStack(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(background),
    ) {
        item(key = "title") {
            PageHeader(
                title = "音乐视频",
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        if (videos is Section.Failed) {
            item(key = "error") { LibraryStatus("加载失败 · 点按重试", onRetry) }
        }
        if (list.isEmpty() && videos !is Section.Failed) {
            item(key = "empty") {
                LibraryStatus(if (videos is Section.Ready) "暂无视频" else "正在加载…")
            }
        }
        itemsIndexed(list, key = { _, video -> video.id }) { index, video ->
            VideoRow(
                video = video,
                position = resolvePosition(list.size, index),
                background = itemBackground,
                content = content,
                contentVariant = contentVariant,
            )
            if (index < list.lastIndex) VGap(4.dp)
        }
    }
    TopShade(
        color = background,
        threshold = { it.calculateTopPadding() + 12.dp },
        listState = listState,
        modifier = Modifier.height(72.dp + 48.dp)
    )
    BottomShade(color = background)
    LibraryBackButton(onBack)
}

@Composable
private fun VideoRow(
    video: ArtistVideo,
    position: ItemPosition,
    background: Color,
    content: Color,
    contentVariant: Color,
) {
    val meta = listOfNotNull(
        playCountText(video.playCount),
        formatDuration(video.durationMs).ifEmpty { null },
    ).joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .heightIn(min = VideoThumbHeight + 24.dp)
            .clip(position.roundedShape())
            .background(background)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = rememberCoverRequest(video.cover),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(VideoThumbWidth)
                .height(VideoThumbHeight)
                .clip(RoundedRectangle(8.dp)),
        )
        HGap()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                style = MaterialTheme.type.body,
                color = content,
                maxLines = 2,
            )
            if (meta.isNotEmpty()) {
                VGap(2.dp)
                Text(
                    text = meta,
                    style = MaterialTheme.type.subHeadline2,
                    color = contentVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun playCountText(count: Long): String? = when {
    count >= 100_000_000 -> "${count / 100_000_000} 亿次播放"
    count >= 10_000 -> "${count / 10_000} 万次播放"
    count > 0 -> "$count 次播放"
    else -> null
}
