package com.nevoit.cirro.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import coil3.request.crossfade

@Composable
internal fun rememberCoverRequest(url: String?): Any? {
    val context = LocalContext.current
    return remember(url, context) {
        url?.takeIf { it.isNotEmpty() }?.let {
            ImageRequest.Builder(context).data(it).crossfade(true).build()
        }
    }
}
