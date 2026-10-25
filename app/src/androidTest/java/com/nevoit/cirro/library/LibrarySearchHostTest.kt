package com.nevoit.cirro.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.LibraryCollection
import com.nevoit.material.core.component.Text
import com.nevoit.material.theme.MaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class LibrarySearchHostTest {
    @get:Rule
    val compose = createComposeRule()

    private val collections = listOf(
        LibraryCollection(CollectionInfo(CollectionKind.PLAYLIST, 1, title = "夜间驾驶", author = "Alice")),
        LibraryCollection(CollectionInfo(CollectionKind.PLAYLIST, 2, title = "午后阅读", author = "Bob")),
    )

    @Test
    fun listRetainsItsEditorWhileFloatingFilteringAndClearing() = verifySearch(grid = false)

    @Test
    fun gridRetainsItsEditorWhileFloatingFilteringAndClearing() = verifySearch(grid = true)

    private fun verifySearch(grid: Boolean) {
        val result = AtomicReference<LibraryCollectionSearchResult?>()
        compose.setContent {
            MaterialTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    val listState = rememberLazyListState()
                    val gridState = rememberLazyGridState()
                    val search = if (grid) rememberLibrarySearch(1, true, gridState)
                    else rememberLibrarySearch(1, true, listState)
                    val filtered = rememberLibraryCollectionSearchResults(collections, search.query)
                    SideEffect { result.set(filtered) }
                    val visible = if (search.query.isBlank()) collections else filtered?.matches.orEmpty()
                    if (grid) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2), state = gridState,
                            modifier = Modifier.fillMaxSize().onGloballyPositioned(search.onListPositioned),
                        ) {
                            item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                                Spacer(Modifier.height(if (search.floating) 72.dp else 160.dp))
                            }
                            item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                                LibrarySearchInline(search)
                            }
                            items(visible, key = { it.info.id }) { Text(it.info.title) }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().onGloballyPositioned(search.onListPositioned),
                        ) {
                            item(key = "title") {
                                Spacer(Modifier.height(if (search.floating) 72.dp else 160.dp))
                            }
                            item(key = "search") { LibrarySearchInline(search) }
                            items(visible, key = { it.info.id }) { Text(it.info.title) }
                        }
                    }
                    LibrarySearchOverlay(search)
                }
            }
        }
        compose.onNodeWithTag("library-song-search-input").performClick().performTextInput("ALICE")
        compose.waitUntil { result.get()?.query == "ALICE" }
        compose.onNodeWithTag("library-song-search-input").assertIsFocused().assertTextEquals("ALICE")
        compose.onNodeWithText("夜间驾驶").assertExists()
        compose.onNodeWithText("午后阅读").assertDoesNotExist()
        compose.onNodeWithTag("library-song-search-clear").performClick()
        compose.onNodeWithTag("library-song-search-input").assertIsFocused().assertTextEquals("")
        compose.onNodeWithText("午后阅读").assertExists()
        compose.onNodeWithTag("library-song-search-clear").performClick()
        compose.onNodeWithTag("library-song-search-input").assertIsNotFocused()
    }

    @Test
    fun equalCategoryRefreshDoesNotLeaveTheIndexOnAnUnusableIdentity() {
        var source by mutableStateOf(collections, referentialEqualityPolicy())
        var query by mutableStateOf("Alice")
        val result = AtomicReference<LibraryCollectionSearchResult?>()
        compose.setContent {
            val filtered = rememberLibraryCollectionSearchResults(source, query)
            SideEffect { result.set(filtered) }
        }
        compose.waitUntil { result.get()?.query == "Alice" }
        compose.runOnIdle {
            source = collections.map { it.copy() }
            query = "Bob"
        }
        compose.waitUntil { result.get()?.query == "Bob" }
        assertEquals(listOf(2L), result.get()?.matches?.map { it.info.id })
        compose.runOnIdle {
            source = listOf(LibraryCollection(CollectionInfo(CollectionKind.PLAYLIST, 3, title = "Bob的新列表")))
        }
        compose.waitUntil { result.get()?.matches?.map { it.info.id } == listOf(3L) }
    }
}
