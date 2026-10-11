package com.nevoit.cirro.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal suspend fun retryInfinitePlayback(
    isCurrent: () -> Boolean,
    onFailure: (Exception) -> Unit,
    wait: suspend (Long) -> Unit = { delay(it) },
    attempt: suspend () -> Unit,
) {
    var retryDelayMs = 1000L
    while (isCurrent()) {
        currentCoroutineContext().ensureActive()
        try {
            attempt()
            return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!isCurrent()) return
            onFailure(error)
        }
        if (!isCurrent()) return
        wait(retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
    }
}
