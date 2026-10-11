package com.nevoit.cirro.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InfinitePlaybackRetryTest {
    @Test
    fun failuresKeepRetryingPastThreeAttemptsWithThirtySecondMaximumDelay() = runBlocking {
        var attempts = 0
        var failures = 0
        val waits = mutableListOf<Long>()
        retryInfinitePlayback(
            isCurrent = { true },
            onFailure = { failures++ },
            wait = { waits.add(it) },
        ) {
            attempts++
            if (attempts <= 7) error("offline or no new recommendations")
        }
        assertEquals(8, attempts)
        assertEquals(7, failures)
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), waits)
    }

    @Test
    fun cancellationWhileWaitingStopsFurtherRequests() = runBlocking {
        var attempts = 0
        val waiting = CompletableDeferred<Unit>()
        val job = async {
            retryInfinitePlayback(
                isCurrent = { true },
                onFailure = {},
                wait = { waiting.complete(Unit); CompletableDeferred<Unit>().await() },
            ) {
                attempts++
                error("offline")
            }
        }
        withTimeout(5000) { waiting.await() }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, attempts)
    }

    @Test
    fun anObsoleteQueueDoesNotStartAnotherAttemptAfterWaiting() = runBlocking {
        var current = true
        var attempts = 0
        retryInfinitePlayback(
            isCurrent = { current },
            onFailure = {},
            wait = { current = false },
        ) {
            attempts++
            error("offline")
        }
        assertEquals(1, attempts)
    }

    @Test
    fun aLateFailureFromAnOldQueueDoesNotLogOrRetry() = runBlocking {
        var current = true
        retryInfinitePlayback(
            isCurrent = { current },
            onFailure = { error("obsolete failure must be ignored") },
            wait = { error("obsolete queue must not retry") },
        ) {
            current = false
            error("late failure")
        }
    }

    @Test
    fun successfulFetchDoesNotRetryAndDisabledModeDoesNotFetch() = runBlocking {
        var attempts = 0
        retryInfinitePlayback(
            isCurrent = { true },
            onFailure = { error("successful fetch") },
            wait = { error("successful fetch must not retry") },
        ) { attempts++ }
        retryInfinitePlayback(
            isCurrent = { false },
            onFailure = { error("disabled mode") },
            wait = { error("disabled mode must not retry") },
        ) { error("disabled mode must not fetch") }
        assertEquals(1, attempts)
    }
}
