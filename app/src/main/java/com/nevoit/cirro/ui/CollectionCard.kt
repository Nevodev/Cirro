package com.nevoit.cirro.ui

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.kyant.shapes.RoundedRectangle
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.theme.MaterialTheme

@Composable
internal fun CollectionCard(
    title: String,
    subtitle: String?,
    model: Any?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    indication: Indication? = LocalIndication.current,
    contentColor: Color = MaterialTheme.colors.content,
    subtitleColor: Color = MaterialTheme.colors.contentVariant,
    coverBackground: Color = Color.Transparent,
    onError: ((AsyncImagePainter.State.Error) -> Unit)? = null,
    coverOverlay: @Composable BoxScope.() -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(modifier.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedRectangle(12.dp))
                .background(coverBackground)
                .indication(interactionSource, indication),
        ) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = onError,
                modifier = Modifier.matchParentSize(),
            )
            coverOverlay()
        }
        VGap(8.dp)
        Text(
            title, style = MaterialTheme.type.subHeadline, color = contentColor,
            minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (subtitle != null) {
            Text(
                subtitle, style = MaterialTheme.type.footnote, color = subtitleColor,
                minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
