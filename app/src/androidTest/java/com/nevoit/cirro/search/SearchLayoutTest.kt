package com.nevoit.cirro.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import com.nevoit.material.core.component.Text
import com.nevoit.material.theme.MaterialTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SearchLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun imeSearchSubmitsWhileFurtherTypingReturnsToSuggestions() {
        compose.setContent {
            MaterialTheme(darkTheme = false) {
                val search = rememberSearchPresentation()
                Box(Modifier.fillMaxSize()) {
                    SearchLayout(search, visible = true, onSubmit = { search.submit() }) {
                        item { Text(if (search.submitted) "正式结果" else "搜索联想") }
                    }
                }
            }
        }
        compose.onNodeWithTag("search-input").performClick().performTextInput("陈奕迅")
        compose.onNodeWithText("搜索联想").assertExists()
        compose.onNodeWithTag("search-input").performImeAction()
        compose.onNodeWithText("正式结果").assertExists()
        compose.onNodeWithText("搜索联想").assertDoesNotExist()
        compose.onNodeWithTag("search-input").performTextInput(" live")
        compose.onNodeWithText("搜索联想").assertExists()
    }

    @Test
    fun submittedSearchReplacesScopeWithCategoryChipsAndEditingRestoresScope() {
        compose.setContent {
            MaterialTheme(darkTheme = false) {
                val search = rememberSearchPresentation()
                SearchLayout(search, visible = true, onSubmit = { search.submit() }) {}
            }
        }
        compose.onNodeWithTag("search-input").performClick().performTextInput("夜航")
        compose.onNodeWithTag("search-scope").assertExists()
        compose.onNodeWithTag("search-categories").assertDoesNotExist()
        compose.onNodeWithTag("search-input").performImeAction()
        compose.onNodeWithTag("search-scope").assertDoesNotExist()
        compose.onNodeWithText("最佳结果").assertIsSelected()
        compose.onNodeWithTag("search-categories").performScrollToNode(hasText("音乐视频"))
        compose.onNodeWithText("音乐视频").performClick().assertIsSelected()
        compose.onNodeWithTag("search-input").performTextInput(" live")
        compose.onNodeWithTag("search-categories").assertDoesNotExist()
        compose.onNodeWithTag("search-scope").assertExists()
    }

    @Test
    fun editorMovesAndResizesWithoutLosingFocusTextOrSelection() {
        val state = AtomicReference<SearchPresentation>()
        compose.setContent {
            MaterialTheme(darkTheme = false) {
                val search = rememberSearchPresentation()
                SideEffect { state.set(search) }
                Box(Modifier.fillMaxSize()) {
                    SearchLayout(search, visible = true, onSubmit = {}) {
                        item { Text("搜索历史") }
                    }
                }
            }
        }
        val idleBounds = compose.onNodeWithTag("search-field").fetchSemanticsNode().boundsInRoot
        val listId = compose.onNodeWithTag("search-candidates").fetchSemanticsNode().id
        compose.onNodeWithText("搜索历史").assertDoesNotExist()
        compose.onNodeWithTag("search-input").performClick().performTextInput("陈奕迅 live")
        compose.onNodeWithTag("search-input").assertIsFocused().assertTextEquals("陈奕迅 live")
        compose.onAllNodesWithTag("search-input").assertCountEquals(1)
        assertEquals(listId, compose.onNodeWithTag("search-candidates").fetchSemanticsNode().id)
        val activeBounds = compose.onNodeWithTag("search-field").fetchSemanticsNode().boundsInRoot
        assertTrue(activeBounds.top < idleBounds.top)
        assertTrue(activeBounds.width < idleBounds.width)
        assertEquals(idleBounds.height, activeBounds.height, 1f)
        compose.onNodeWithTag("search-input").performTextInputSelection(TextRange(0, 3))
        compose.onNodeWithText("资料库").performClick()
        compose.onNodeWithTag("search-input").assertIsFocused()
        compose.runOnIdle { assertEquals(TextRange(0, 3), state.get().input.selection) }
        compose.onNodeWithTag("search-clear").assertDoesNotExist()
        compose.runOnIdle { state.get().clearOrClose() }
        compose.onNodeWithTag("search-input").assertIsFocused().assertTextEquals("")
        compose.onNodeWithText("搜索历史").assertExists()
        compose.runOnIdle { state.get().clearOrClose() }
        compose.onNodeWithTag("search-input").assertIsNotFocused().assertTextEquals("")
        compose.onAllNodesWithTag("search-input").assertCountEquals(1)
        assertEquals(listId, compose.onNodeWithTag("search-candidates").fetchSemanticsNode().id)
        val restoredBounds = compose.onNodeWithTag("search-field").fetchSemanticsNode().boundsInRoot
        assertEquals(idleBounds.top, restoredBounds.top, 1f)
        assertEquals(idleBounds.width, restoredBounds.width, 1f)
    }
}
