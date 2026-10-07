package com.nevoit.cirro.playback

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

internal class PlayerSeekState {
    private val preview = mutableStateOf<Long?>(null)
    val previewPosition: State<Long?> = preview
    private val dragging = mutableStateOf(false)
    val isDragging: State<Boolean> = dragging
    private var fraction = 0.0
    private var pendingDiscontinuity: Long? = null

    fun begin(positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        fraction = (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
        preview.value = (fraction * durationMs).toLong()
        pendingDiscontinuity = null
        dragging.value = true
    }

    fun dragBy(deltaFraction: Float, durationMs: Long) {
        if (!dragging.value || durationMs <= 0L) return
        fraction = (fraction + deltaFraction).coerceIn(0.0, 1.0)
        preview.value = (fraction * durationMs).toLong()
    }

    fun commit(discontinuity: Long): Long? {
        if (!dragging.value) return null
        pendingDiscontinuity = discontinuity
        dragging.value = false
        return preview.value
    }

    fun onPlaybackDiscontinuity(discontinuity: Long) {
        val pending = pendingDiscontinuity ?: return
        // Keep the release position until the controller publishes the actual seek.
        if (discontinuity != pending) cancel()
    }

    fun cancel() {
        dragging.value = false
        preview.value = null
        pendingDiscontinuity = null
    }
}
