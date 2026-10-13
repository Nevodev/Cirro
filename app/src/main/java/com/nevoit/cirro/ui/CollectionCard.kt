package com.nevoit.cirro.ui

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.theme.MaterialTheme

@Composable
internal fun CollectionCard(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = LocalIndication.current,
    cover: @Composable () -> Unit,
) {
    Column(modifier.clickable(interactionSource = interactionSource, indication = indication, onClick = onClick)) {
        cover()
        VGap(8.dp)
        Text(
            title, style = MaterialTheme.type.subHeadline, color = MaterialTheme.colors.content,
            minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (subtitle != null) {
            Text(
                subtitle, style = MaterialTheme.type.footnote, color = MaterialTheme.colors.contentVariant,
                minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
