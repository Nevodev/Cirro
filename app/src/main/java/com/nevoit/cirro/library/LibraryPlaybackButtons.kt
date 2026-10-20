package com.nevoit.cirro.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.nevoit.cirro.R
import com.nevoit.material.core.component.HGap
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.Text
import com.nevoit.material.theme.MaterialTheme

@Composable
internal fun LibraryPlaybackButtons(
    enabled: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LibraryPlaybackButton("播放", R.drawable.play_fill, enabled, onPlay, Modifier.weight(1f))
        HGap(12.dp)
        LibraryPlaybackButton(
            "随机播放",
            R.drawable.shuffle,
            enabled,
            onShuffle,
            Modifier.weight(1f)
        )
    }
}

@Composable
private fun LibraryPlaybackButton(
    title: String,
    icon: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val shape = remember { Capsule() }
    val background = MaterialTheme.colors.secondaryButtonBackground
    val content = MaterialTheme.colors.onSecondaryButton
    Row(
        modifier
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = content)
        HGap(8.dp)
        Text(title, color = content)
    }
}
