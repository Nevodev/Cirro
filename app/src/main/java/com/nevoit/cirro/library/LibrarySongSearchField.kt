package com.nevoit.cirro.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.nevoit.cirro.R
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.interaction.AlphaIndication
import com.nevoit.material.theme.MaterialTheme

internal val LibrarySongSearchHeight = 48.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibrarySongSearchField(
    query: String,
    focused: Boolean,
    onQueryChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colors.scrimNormal,
    onDismiss: (() -> Unit)? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
    enabled: Boolean = true,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    var wasImeVisible by remember { mutableStateOf(imeVisible) }
    LaunchedEffect(imeVisible) {
        // A hidden IME during initial focus is not a dismissal.
        if (wasImeVisible && !imeVisible && focused) focus.clearFocus()
        wasImeVisible = imeVisible
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(LibrarySongSearchHeight)
            .clip(Capsule())
            .background(background)
            .clickable(enabled = enabled, interactionSource = null, indication = null) {
                focusRequester.requestFocus()
                keyboard?.show()
            }
            .testTag("library-song-search"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.magnifyingglass),
            contentDescription = null,
            tint = MaterialTheme.colors.contentVariant,
            modifier = Modifier
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 8.dp)
                .size(24.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = { onQueryChange(it.lineSequence().joinToString(" ")) },
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.type.body.copy(color = MaterialTheme.colors.content),
            cursorBrush = SolidColor(MaterialTheme.colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                focus.clearFocus()
            }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChange(it.isFocused) }
                .testTag("library-song-search-input"),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = "搜索",
                            style = MaterialTheme.type.body,
                            color = MaterialTheme.colors.contentVariant,
                            maxLines = 1,
                        )
                    }
                    field()
                }
            },
        )
        if (focused || query.isNotEmpty()) {
            Icon(
                painter = painterResource(R.drawable.xmark_bold_circle_fill),
                contentDescription = if (query.isEmpty()) "关闭搜索" else "清除内容",
                tint = MaterialTheme.colors.contentVariant,
                modifier = Modifier
                    .testTag("library-song-search-clear")
                    .size(48.dp)
                    .clip(Capsule())
                    .clickable(
                        enabled = enabled,
                        role = Role.Button,
                        interactionSource = null,
                        indication = AlphaIndication(),
                    ) {
                        if (query.isNotEmpty()) onQueryChange("")
                        else {
                            keyboard?.hide()
                            focus.clearFocus()
                            onDismiss?.invoke()
                        }
                    }
                    .padding(15.dp),
            )
        } else {
            Spacer(Modifier.size(12.dp))
        }
    }
}
