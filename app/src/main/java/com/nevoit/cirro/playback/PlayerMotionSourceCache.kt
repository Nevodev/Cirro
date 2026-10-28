package com.nevoit.cirro.playback

import androidx.compose.runtime.mutableStateMapOf

internal data class PlayerMotionSource(
    val resolved: Boolean = false,
    val url: String? = null,
    val albumId: Long = 0,
) {
    fun keepsMotionLayout(shownUrl: String?, shownReplacesCover: Boolean): Boolean =
        resolved && url != null && (shownReplacesCover || url != shownUrl)
}

internal data class PlayerMotionSources(
    val current: PlayerMotionSource,
    val next: PlayerMotionSource,
)

internal class PlayerMotionSourceCache {
    private val songs = mutableStateMapOf<Long, PlayerMotionSource>()
    private val albums = mutableStateMapOf<Long, PlayerMotionSource>()
    private val failures = mutableSetOf<Long>()

    fun get(songId: Long?, albumId: Long = 0): PlayerMotionSource? =
        songs[songId] ?: albums[albumId]

    fun put(songId: Long, albumId: Long, url: String?, failed: Boolean = false) {
        val source = PlayerMotionSource(resolved = true, url = url, albumId = albumId)
        songs[songId] = source
        if (failed) failures += songId else {
            failures -= songId
            if (albumId > 0) albums[albumId] = source
        }
    }

    fun retryFailures() {
        failures.forEach(songs::remove)
        failures.clear()
    }
}
