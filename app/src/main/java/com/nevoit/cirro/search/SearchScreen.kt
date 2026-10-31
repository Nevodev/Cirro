package com.nevoit.cirro.search

import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.nevoit.cirro.R
import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.ArtistVideo
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.QueueSource
import com.nevoit.cirro.data.model.SearchCandidate
import com.nevoit.cirro.data.model.albumDisplayTitle
import com.nevoit.cirro.data.model.hasPendingInserts
import com.nevoit.cirro.library.LibraryCover
import com.nevoit.cirro.library.LibrarySongMenuContent
import com.nevoit.cirro.library.LibraryStatus
import com.nevoit.cirro.playback.PlaybackConnection
import com.nevoit.cirro.playback.rememberIsCurrentSong
import com.nevoit.cirro.ui.ItemPosition
import com.nevoit.cirro.ui.SongMenuViewModel
import com.nevoit.cirro.ui.TrackRow
import com.nevoit.cirro.ui.roundedShape
import com.nevoit.material.core.component.HGap
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.interaction.AlphaIndication
import com.nevoit.material.core.interaction.DimIndication
import com.nevoit.material.theme.MaterialTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private val SearchCoverSize = 48.dp

@Composable
internal fun SearchRoute(
    presentation: SearchPresentation,
    visible: Boolean,
    onOpenCollection: (CollectionKind, Long, String, String, String) -> Unit,
    onOpenLibraryAlbum: (Long, String, String, String) -> Unit,
) {
    val viewModel = koinViewModel<SearchViewModel>()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val playback = koinInject<PlaybackConnection>()
    val menu = koinViewModel<SongMenuViewModel>()
    val queueActions by remember(playback) {
        playback.queue.map { it.entries.isNotEmpty() to it.hasPendingInserts }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false to false)
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val uriHandler = LocalUriHandler.current
    val query = presentation.input.text
    val scope = presentation.scope
    val source =
        remember(query, scope) {
            QueueSource(title = if (query.isBlank()) "搜索历史" else "${scope.title}搜索：${query.trim()}")
        }
    val current = result.takeIf {
        it.request.query == query && it.request.scope == scope && it.request.active &&
                it.request.submitted == presentation.submitted && it.request.submission == presentation.submission &&
                it.request.category == presentation.category
    }
    val candidateItems = remember(current?.candidates, query, presentation.submitted) {
        current?.candidates?.let {
            if (presentation.submitted) it.orderedItems() else it.suggestionItems(query)
        }.orEmpty()
    }
    val displayedItems = remember(history, candidateItems, query, current?.candidates?.keywords) {
        if (query.isBlank()) history
        else current?.candidates?.keywords.orEmpty().take(3)
            .map { SearchCandidate.Keyword(it) } + candidateItems
    }
    val songs = remember(displayedItems, query, current?.candidates?.songs) {
        if (query.isBlank()) displayedItems.filterIsInstance<SearchCandidate.Track>()
            .map { it.song }
        else current?.candidates?.songs.orEmpty()
    }
    LaunchedEffect(
        query,
        scope,
        presentation.active,
        visible,
        presentation.submitted,
        presentation.submission,
        presentation.category,
    ) {
        viewModel.search(
            query,
            scope,
            presentation.active && visible,
            presentation.submitted,
            presentation.submission,
            presentation.category,
        )
    }
    LaunchedEffect(menu) {
        menu.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    val rememberQuery = { viewModel.rememberQuery(presentation.input.text) }
    val submit = {
        if (presentation.submit()) {
            rememberQuery()
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(
        query,
        scope,
        presentation.active,
        presentation.submission,
        presentation.category,
    ) { if (presentation.active) listState.scrollToItem(0) }
    val itemFadeOut = if (presentation.active) tween<Float>(200) else null
    SearchLayout(presentation, visible, onSubmit = submit, listState = listState) {
        if (query.isBlank() && history.isNotEmpty()) {
            item(key = "history-title") {
                Row(
                    Modifier
                        .animateItem(tween(200), null, itemFadeOut)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("搜索历史", style = MaterialTheme.type.bodyEmphasized)
                    if (history.isNotEmpty()) Text(
                        "清除",
                        style = MaterialTheme.type.subHeadline,
                        color = MaterialTheme.colors.primary,
                        modifier = Modifier.clickable(
                            interactionSource = null,
                            indication = AlphaIndication(),
                            role = Role.Button,
                            onClick = viewModel::clearHistory,
                        ),
                    )
                }
            }
        }
        if (query.isNotBlank()) {
            current?.message?.let { message ->
                item(key = "message") {
                    Box(Modifier.animateItem(tween(200), null, itemFadeOut)) {
                        LibraryStatus(message, viewModel::retry)
                    }
                }
            }
            val candidates = current?.candidates
            if (presentation.submitted && candidates != null && candidates.isEmpty &&
                !current.loading && current.message == null
            ) {
                item(key = "empty") {
                    Box(Modifier.animateItem(tween(200), null, itemFadeOut)) {
                        LibraryStatus("没有找到相关内容")
                    }
                }
            }
        }
        itemsIndexed(
            displayedItems,
            key = { _, candidate -> candidate.key },
            contentType = { _, candidate ->
                when (candidate) {
                    is SearchCandidate.Keyword -> "keyword"
                    is SearchCandidate.Track -> "song"
                    is SearchCandidate.Collection -> "collection"
                    is SearchCandidate.Video -> "video"
                }
            },
        ) { index, candidate ->
            val firstInGroup = index == 0 || displayedItems[index - 1] is SearchCandidate.Keyword
            val lastInGroup =
                index == displayedItems.lastIndex || displayedItems[index + 1] is SearchCandidate.Keyword
            val position = when {
                firstInGroup && lastInGroup -> ItemPosition.Single
                firstInGroup -> ItemPosition.First
                lastInGroup -> ItemPosition.Last
                else -> ItemPosition.Middle
            }
            Column(Modifier.animateItem(tween(200), null, itemFadeOut)) {
                if (index > 0 &&
                    (displayedItems[index - 1] is SearchCandidate.Keyword) != (candidate is SearchCandidate.Keyword)
                ) VGap(4.dp)
                when (candidate) {
                    is SearchCandidate.Video -> SearchVideoRow(candidate.video, position) {
                        viewModel.rememberItem(candidate)
                        keyboard?.hide()
                        runCatching { uriHandler.openUri("https://music.163.com/#/mv?id=${candidate.video.id}") }
                            .onFailure { Toast.makeText(context, "无法打开音乐视频", Toast.LENGTH_SHORT).show() }
                    }

                    is SearchCandidate.Keyword -> SearchKeywordRow(
                        keyword = candidate.text,
                        onClick = {
                            presentation.useKeyword(candidate.text)
                            submit()
                        },
                    )

                    is SearchCandidate.Track -> {
                        val song = candidate.song
                        val play = {
                            viewModel.rememberItem(candidate)
                            keyboard?.hide()
                            playback.playSongs(
                                songs,
                                songs.indexOfFirst { it.id == song.id },
                                source
                            )
                        }
                        TrackRow(
                            song,
                            MaterialTheme.colors.cardBackground,
                            showArtist = true,
                            index = null,
                            position = position,
                            isPlaying = rememberIsCurrentSong(song.id),
                            content = MaterialTheme.colors.content,
                            contentVariant = MaterialTheme.colors.contentVariant,
                            coverSize = SearchCoverSize,
                            coverVerticalPadding = 12.dp,
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
                    }

                    is SearchCandidate.Collection -> {
                        val info = candidate.info
                        SearchCollectionRow(info, position) {
                            viewModel.rememberItem(candidate)
                            keyboard?.hide()
                            if (scope == SearchScope.LIBRARY && info.kind == CollectionKind.ALBUM) {
                                onOpenLibraryAlbum(info.id, info.title, info.cover, info.author)
                            } else onOpenCollection(
                                info.kind,
                                info.id,
                                info.title,
                                info.cover,
                                info.author
                            )
                        }
                    }
                }
                if (candidate !is SearchCandidate.Keyword && !lastInGroup) VGap(4.dp)
            }
        }
        if (query.isNotBlank()) {
            if (current?.loadingMore == true) item(key = "loading-more") {
                Box(Modifier.animateItem(tween(200), null, itemFadeOut)) {
                    LibraryStatus("正在加载更多…")
                }
            } else if (current?.hasMore == true) item(key = "load-more") {
                Box(Modifier.animateItem(tween(200), null, itemFadeOut)) {
                    LibraryStatus(current.moreMessage ?: "加载更多", viewModel::loadMore)
                }
            }
        }
    }
}

@Composable
private fun SearchVideoRow(video: ArtistVideo, position: ItemPosition, onClick: () -> Unit) {
    val colors = MaterialTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(position.roundedShape())
            .background(colors.cardBackground)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(SearchCoverSize * (16f / 9f))
                .height(SearchCoverSize)
                .clip(RoundedRectangle(8.dp))
                .background(colors.iconButtonBackground),
            contentAlignment = Alignment.Center,
        ) {
            if (video.cover.isNotBlank()) LibraryCover(video.cover, Modifier.matchParentSize())
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(video.title, style = MaterialTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val duration = video.durationMs / 1000
            Text(
                listOf("音乐视频", video.artist, if (duration > 0) "%d:%02d".format(duration / 60, duration % 60) else "")
                    .filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.type.subHeadline,
                color = colors.contentVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchKeywordRow(keyword: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .heightIn(min = 52.dp)
            .clickable(
                onClick = onClick,
                interactionSource = null,
                indication = DimIndication(shape = Capsule())
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.magnifyingglass),
            null,
            tint = MaterialTheme.colors.contentVariant,
            modifier = Modifier.size(24.dp),
        )
        HGap()
        Text(
            keyword, style = MaterialTheme.type.body, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 16.dp),
        )
    }
}

@Composable
private fun SearchCollectionRow(info: CollectionInfo, position: ItemPosition, onClick: () -> Unit) {
    val colors = MaterialTheme.colors
    val artist = info.kind == CollectionKind.ARTIST
    val type = when (info.kind) {
        CollectionKind.ARTIST -> "艺人"
        CollectionKind.ALBUM -> "专辑"
        else -> "歌单"
    }
    val icon = when (info.kind) {
        CollectionKind.ARTIST -> R.drawable.microphone_stand
        CollectionKind.ALBUM -> R.drawable.album
        else -> R.drawable.music_note_list
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(position.roundedShape())
            .background(colors.cardBackground)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(SearchCoverSize)
                .clip(if (artist) Capsule() else RoundedRectangle(8.dp))
                .background(colors.iconButtonBackground), contentAlignment = Alignment.Center
        ) {
            if (info.cover.isNotBlank()) LibraryCover(info.cover, Modifier.matchParentSize())
            else Icon(
                painterResource(icon),
                null,
                tint = colors.contentVariant,
                modifier = Modifier.size(24.dp)
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                if (info.kind == CollectionKind.ALBUM) albumDisplayTitle(
                    info.title,
                    info.albumType
                ) else info.title,
                style = MaterialTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                listOf(type, info.author).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.type.subHeadline, color = colors.contentVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}
