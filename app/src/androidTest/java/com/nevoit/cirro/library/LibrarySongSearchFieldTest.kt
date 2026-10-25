package com.nevoit.cirro.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.nevoit.material.theme.MaterialTheme
import org.junit.Rule
import org.junit.Test

class LibrarySongSearchFieldTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyFieldOnlyShowsCloseWhileFocused() {
        setSearchContent()
        compose.onNodeWithTag("library-song-search-clear").assertDoesNotExist()
        compose.onNodeWithTag("library-song-search-input").performClick()
        compose.onNodeWithTag("library-song-search-clear").assertExists().performClick()
        compose.onNodeWithTag("library-song-search-input").assertIsNotFocused()
        compose.onNodeWithTag("library-song-search-clear").assertDoesNotExist()
    }

    @Test
    fun queryKeepsClearVisibleAfterFocusLeavesAndClearingPreservesFocus() {
        setSearchContent()
        compose.onNodeWithTag("library-song-search-input").performClick().performTextInput("Coldplay")
        compose.onNodeWithTag("other-field").performClick()
        compose.onNodeWithTag("library-song-search-input").assertIsNotFocused()
        compose.onNodeWithTag("library-song-search-clear").assertExists()
        compose.onNodeWithTag("library-song-search-input").performClick()
        compose.onNodeWithTag("library-song-search-clear").performClick()
        compose.onNodeWithTag("library-song-search-input").assertIsFocused().assertTextEquals("")
        compose.onNodeWithTag("library-song-search-clear").assertExists()
    }

    private fun setSearchContent() {
        var query by mutableStateOf("")
        var focused by mutableStateOf(false)
        compose.setContent {
            MaterialTheme(darkTheme = false) {
                Column {
                    LibrarySongSearchField(query, focused, { query = it }, { focused = it })
                    BasicTextField(
                        value = "",
                        onValueChange = {},
                        modifier = Modifier.testTag("other-field"),
                    )
                }
            }
        }
    }
}
