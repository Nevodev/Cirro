package com.nevoit.cirro.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.nevoit.cirro.detail.animateCollectionSearchExit
import com.nevoit.cirro.detail.collectionSearchTransform
import com.nevoit.material.core.component.VGap
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.tokens.Springs

internal class LibrarySearch(
    val query: String,
    val floating: Boolean,
    val active: Boolean,
    val available: Boolean,
    val editor: @Composable (Modifier) -> Unit,
    val close: () -> Unit,
    val onListPositioned: (LayoutCoordinates) -> Unit,
    val onInlinePositioned: (LayoutCoordinates) -> Unit,
    val floatingModifier: Modifier,
)

@Composable
internal fun rememberLibrarySearch(
    userId: Long?,
    available: Boolean,
    listState: LazyListState,
): LibrarySearch {
    val heightPx = with(LocalDensity.current) { LibrarySongSearchHeight.roundToPx() }
    val inlineVisible = remember(listState, heightPx) {
        {
            val layout = listState.layoutInfo
            layout.visibleItemsInfo.any {
                it.key == "search" && it.offset < layout.viewportEndOffset &&
                    it.offset + heightPx > layout.viewportStartOffset
            }
        }
    }
    val scrollToTop = remember(listState) { { listState.requestScrollToItem(0) } }
    return rememberLibrarySearch(userId, available, inlineVisible, scrollToTop)
}

@Composable
internal fun rememberLibrarySearch(
    userId: Long?,
    available: Boolean,
    gridState: LazyGridState,
): LibrarySearch {
    val heightPx = with(LocalDensity.current) { LibrarySongSearchHeight.roundToPx() }
    val inlineVisible = remember(gridState, heightPx) {
        {
            val layout = gridState.layoutInfo
            layout.visibleItemsInfo.any {
                it.key == "search" && it.offset.y < layout.viewportEndOffset &&
                    it.offset.y + heightPx > layout.viewportStartOffset
            }
        }
    }
    val scrollToTop = remember(gridState) { { gridState.requestScrollToItem(0) } }
    return rememberLibrarySearch(userId, available, inlineVisible, scrollToTop)
}

@Composable
private fun rememberLibrarySearch(
    userId: Long?,
    available: Boolean,
    inlineVisible: () -> Boolean,
    scrollToTop: () -> Unit,
): LibrarySearch {
    var query by rememberSaveable(userId) { mutableStateOf("") }
    var focused by remember(userId) { mutableStateOf(false) }
    var floating by rememberSaveable(userId) { mutableStateOf(false) }
    var closing by remember(userId) { mutableStateOf(false) }
    val opacity = remember(userId) { Animatable(1f) }
    val exitProgress = remember(userId) { Animatable(1f) }
    LaunchedEffect(userId, closing) {
        if (closing) {
            // Keep the same editor in its floating layer until its exit has finished.
            animateCollectionSearchExit(opacity, exitProgress)
            floating = false
            closing = false
        } else {
            opacity.snapTo(1f)
            exitProgress.snapTo(1f)
        }
    }
    var inlineCoordinates by remember(userId) { mutableStateOf<LayoutCoordinates?>(null) }
    var listCoordinates by remember(userId) { mutableStateOf<LayoutCoordinates?>(null) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp
    val topPx = with(LocalDensity.current) { top.roundToPx() }
    val currentTopPx by rememberUpdatedState(topPx)
    var liftStartPx by remember(userId) { mutableFloatStateOf(topPx.toFloat()) }
    val focusRequester = remember(userId) { FocusRequester() }
    val lift = remember(floating, userId) {
        Animatable(if (floating) liftStartPx else topPx.toFloat())
    }
    LaunchedEffect(floating, topPx, lift, available) {
        if (floating && available) {
            focusRequester.requestFocus()
            lift.animateTo(topPx.toFloat(), Springs.crisp())
        }
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val active = floating || focused || query.isNotEmpty()
    val close = {
        if (!closing) {
            closing = floating && !inlineVisible()
            if (!closing) floating = false
            query = ""
            keyboard?.hide()
            focus.clearFocus()
        }
    }
    BackHandler(enabled = active, onBack = close)
    val currentClose by rememberUpdatedState(close)
    val currentScrollToTop by rememberUpdatedState(scrollToTop)
    // Reparent one editor rather than creating another TextField and losing its selection.
    val editor = remember(userId) {
        movableContentOf<Modifier> { modifier ->
            LibrarySongSearchField(
                query = query,
                focused = focused,
                onQueryChange = { query = it },
                onFocusChange = { hasFocus ->
                    if (hasFocus && !floating && !closing) {
                        val inline = inlineCoordinates?.takeIf { it.isAttached }
                        val list = listCoordinates?.takeIf { it.isAttached }
                        liftStartPx = if (inline != null && list != null) {
                            list.localPositionOf(inline, Offset.Zero).y
                        } else currentTopPx.toFloat()
                        floating = true
                        currentScrollToTop()
                    }
                    focused = hasFocus
                },
                onDismiss = { currentClose() },
                background = MaterialTheme.colors.scrimNormal,
                focusRequester = focusRequester,
                enabled = !closing,
                modifier = modifier,
            )
        }
    }
    val onListPositioned: (LayoutCoordinates) -> Unit = remember(userId) {
        { listCoordinates = it }
    }
    val onInlinePositioned: (LayoutCoordinates) -> Unit = remember(userId) {
        { inlineCoordinates = it }
    }
    val floatingModifier = remember(lift, exitProgress, opacity) {
        Modifier.graphicsLayer {
            translationY = lift.value
            collectionSearchTransform(exitProgress.value, opacity.value)
        }
    }
    return LibrarySearch(
        query, floating, active, available, editor, close,
        onListPositioned, onInlinePositioned, floatingModifier,
    )
}

@Composable
internal fun LibrarySearchInline(search: LibrarySearch, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().onGloballyPositioned(search.onInlinePositioned)) {
        if (search.floating) Spacer(Modifier.height(LibrarySongSearchHeight))
        else search.editor(Modifier)
        VGap(12.dp)
    }
}

@Composable
internal fun BoxScope.LibrarySearchOverlay(search: LibrarySearch) {
    if (search.available && search.floating) {
        search.editor(
            Modifier.align(Alignment.TopStart).then(search.floatingModifier).padding(horizontal = 12.dp)
        )
    }
}
