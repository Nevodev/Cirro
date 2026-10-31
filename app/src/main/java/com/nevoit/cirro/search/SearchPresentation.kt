package com.nevoit.cirro.search

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal enum class SearchScope(val title: String) {
    NETEASE("Cloud Music"),
    LIBRARY("资料库"),
}

internal class SearchPresentation(initiallyActive: Boolean = false) {
    val activationProgress = Animatable(if (initiallyActive) 1f else 0f)
    private var inputValue by mutableStateOf(TextFieldValue())
    var input: TextFieldValue
        get() = inputValue
        set(value) {
            if (value.text != inputValue.text) {
                submitted = false
                category = SearchCategory.BEST
            }
            inputValue = value
        }
    var scope by mutableStateOf(SearchScope.NETEASE)
    var category by mutableStateOf(SearchCategory.BEST)
    var active by mutableStateOf(initiallyActive)
    var submitted by mutableStateOf(false)
        private set
    var submission by mutableStateOf(0)
        private set

    fun useKeyword(keyword: String) {
        input = TextFieldValue(keyword, TextRange(keyword.length))
        active = true
    }

    fun submit(): Boolean {
        val keyword = input.text.trim()
        if (keyword.isEmpty()) return false
        input = TextFieldValue(keyword, TextRange(keyword.length))
        active = true
        submitted = true
        category = SearchCategory.BEST
        submission++
        return true
    }

    fun clearOrClose() {
        if (input.text.isEmpty()) close()
        else input = TextFieldValue()
    }

    fun close() {
        active = false
        submitted = false
        input = TextFieldValue()
    }

    companion object {
        val Saver = listSaver<SearchPresentation, Any>(
            save = {
                listOf(
                    it.input.text,
                    it.input.selection.start,
                    it.input.selection.end,
                    it.scope.name,
                    it.active,
                    it.submitted,
                    it.submission,
                    it.category.name
                )
            },
            restore = {
                SearchPresentation(initiallyActive = it[4] as Boolean).apply {
                    input = TextFieldValue(it[0] as String, TextRange(it[1] as Int, it[2] as Int))
                    scope = SearchScope.valueOf(it[3] as String)
                    active = it[4] as Boolean
                    submitted = it.getOrNull(5) as? Boolean ?: false
                    submission = it.getOrNull(6) as? Int ?: 0
                    category = (it.getOrNull(7) as? String)?.let(SearchCategory::valueOf)
                        ?: SearchCategory.BEST
                }
            },
        )
    }
}

@Composable
internal fun rememberSearchPresentation(): SearchPresentation =
    rememberSaveable(saver = SearchPresentation.Saver) {
        SearchPresentation()
    }
