package com.nevoit.cirro.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner

@Composable
internal fun PlayerUnderlay(
    sleeping: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val lifecycleOwner = rememberLifecycleOwner(
        maxLifecycle = if (sleeping) Lifecycle.State.CREATED else Lifecycle.State.RESUMED,
    )
    CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
        // Keep the page tree and its coordinates alive for the closing transition. The fully
        // opaque player covers it, so neither hidden drawing nor STARTED work needs to continue.
        Box(
            modifier = modifier.drawWithContent { if (!sleeping) drawContent() },
            content = content,
        )
    }
}
