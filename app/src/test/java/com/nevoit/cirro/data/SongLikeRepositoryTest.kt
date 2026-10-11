package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.UserProfile
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongLikeRepositoryTest {
    @Test
    fun switchingSongsAndOpeningThePlayerNeverRefreshAnExistingSnapshot() = runBlocking {
        Fixture(read = { Result.success(setOf(123L, 456L)) }).use { f ->
            f.login(1)
            f.loaded(1)
            f.time.set(60 * 60 * 1000L)
            repeat(10) {
                assertEquals(true, f.repository.isLiked(123, 1))
                assertEquals(false, f.repository.isLiked(789, 1))
                assertEquals(true, f.repository.isLiked(456, 1))
            }
            f.repository.ensureLoaded().join()
            assertEquals(1, f.reads.get())
        }
    }

    @Test
    fun returningToTheForegroundRefreshesOnlyAfterFiveMinutes() = runBlocking {
        val ids = AtomicReference(setOf(123L))
        Fixture(read = { Result.success(ids.get()) }).use { f ->
            f.login(1)
            f.loaded(1)
            f.time.set(5 * 60 * 1000L - 1)
            f.repository.onForeground().join()
            assertEquals(1, f.reads.get())
            f.time.incrementAndGet()
            ids.set(setOf(456L))
            f.repository.onForeground().join()
            f.loaded(1) { it == setOf(456L) }
            assertEquals(2, f.reads.get())
            assertEquals(false, f.repository.isLiked(123, 1))
            f.repository.onForeground().join()
            assertEquals(2, f.reads.get())
        }
    }

    @Test
    fun loginPlayerOpeningAndForegroundRequestsShareOneInitialFetch() = runBlocking {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        Fixture(read = {
            started.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            Result.success(setOf(123L))
        }).use { f ->
            try {
                f.login(1)
                assertTrue(started.await(5, TimeUnit.SECONDS))
                val opening = f.repository.ensureLoaded()
                val foreground = f.repository.onForeground()
                finish.countDown()
                withTimeout(5000) { opening.join(); foreground.join() }
                f.loaded(1)
                assertEquals(1, f.reads.get())
            } finally {
                finish.countDown()
            }
        }
    }

    @Test
    fun successfulWritesUpdateLocalMembershipWithoutAnotherFetch() = runBlocking {
        val writes = CopyOnWriteArrayList<Triple<Long, Long, Boolean>>()
        Fixture(
            read = { Result.success(setOf(123L)) },
            write = { id, uid, liked -> writes.add(Triple(id, uid, liked)); Result.success(Unit) },
        ).use { f ->
            f.login(1)
            f.loaded(1)
            f.repository.setLiked(123, 1, false).getOrThrow()
            assertEquals(false, f.repository.isLiked(123, 1))
            f.repository.setLiked(456, 1, true).getOrThrow()
            assertEquals(true, f.repository.isLiked(456, 1))
            assertEquals(1, f.reads.get())
            assertEquals(listOf(Triple(123L, 1L, false), Triple(456L, 1L, true)), writes)
        }
    }

    @Test
    fun failedRefreshKeepsKnownLikesAndRetriesWithoutHoldingTheWriteLock() = runBlocking {
        val actual = AtomicReference(setOf(123L))
        val confirmed = CompletableDeferred<Unit>()
        val waiting = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var attempt = 0
        Fixture(
            read = {
                attempt++
                if (attempt == 3) confirmed.complete(Unit)
                if (attempt == 2) Result.failure(IllegalStateException("offline"))
                else Result.success(actual.get())
            },
            write = { id, _, liked ->
                actual.set(if (liked) actual.get() + id else actual.get() - id)
                Result.success(Unit)
            },
            wait = { waiting.complete(Unit); resume.await() },
        ).use { f ->
            f.login(1)
            f.loaded(1)
            f.time.set(5 * 60 * 1000L)
            f.repository.onForeground().join()
            withTimeout(5000) { waiting.await() }
            assertEquals(true, f.repository.isLiked(123, 1))
            assertEquals(false, f.repository.isLiked(456, 1))
            withTimeout(5000) { f.repository.setLiked(456, 1, true).getOrThrow() }
            assertEquals(true, f.repository.isLiked(456, 1))
            f.time.addAndGet(1000)
            resume.complete(Unit)
            withTimeout(5000) { confirmed.await() }
            f.loaded(1) { it == actual.get() }
            assertEquals(true, f.repository.isLiked(123, 1))
            assertEquals(true, f.repository.isLiked(456, 1))
        }
    }

    @Test
    fun uncertainWritePreservesKnownStateUntilTheServerCanConfirmIt() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val time = AtomicLong()
        var attempt = 0
        Fixture(
            read = {
                attempt++
                when (attempt) {
                    1 -> Result.success(setOf(123L))
                    2 -> Result.failure(IllegalStateException("offline"))
                    else -> Result.success(emptySet())
                }
            },
            write = { _, _, _ -> Result.failure(IllegalStateException("response lost")) },
            time = time,
            wait = { waiting.complete(Unit); resume.await(); time.addAndGet(it) },
        ).use { f ->
            f.login(1)
            f.loaded(1)
            assertTrue(f.repository.setLiked(123, 1, false).isFailure)
            withTimeout(5000) { waiting.await() }
            assertEquals(true, f.repository.isLiked(123, 1))
            resume.complete(Unit)
            f.loaded(1) { it.isEmpty() }
            assertEquals(false, f.repository.isLiked(123, 1))
        }
    }

    @Test
    fun logoutClearsStateAndSameAccountLoginFetchesAgain() = runBlocking {
        Fixture(read = { uid -> Result.success(if (uid == 1L) setOf(123L) else setOf(456L)) }).use { f ->
            f.login(1)
            f.loaded(1)
            f.session.value = Session.LoggedOut
            withTimeout(5000) { f.repository.snapshot.first { it.userId == null } }
            assertNull(f.repository.isLiked(123, 1))
            f.login(1)
            f.loaded(1)
            assertEquals(2, f.reads.get())
            f.login(2)
            f.loaded(2)
            assertNull(f.repository.isLiked(123, 1))
            assertEquals(false, f.repository.isLiked(123, 2))
            assertEquals(true, f.repository.isLiked(456, 2))
            assertEquals(3, f.reads.get())
        }
    }

    @Test
    fun initialFailureRemainsUnknownAndRetriesWithCappedBackoff() = runBlocking {
        val time = AtomicLong()
        val waits = CopyOnWriteArrayList<Long>()
        var attempt = 0
        Fixture(
            read = {
                attempt++
                if (attempt <= 7) Result.failure(IllegalStateException("offline"))
                else Result.success(setOf(456L))
            },
            time = time,
            wait = { waits.add(it); time.addAndGet(it) },
        ).use { f ->
            f.login(1)
            f.loaded(1)
            assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), waits)
            assertEquals(8, f.reads.get())
            assertEquals(true, f.repository.isLiked(456, 1))
        }
    }

    @Test
    fun logoutCancelsInitialRetryAndDoesNotTreatFailureAsUnliked() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        Fixture(
            read = { Result.failure(IllegalStateException("offline")) },
            wait = { waiting.complete(Unit); CompletableDeferred<Unit>().await() },
        ).use { f ->
            f.login(1)
            withTimeout(5000) { waiting.await() }
            assertNull(f.repository.isLiked(123, 1))
            f.session.value = Session.LoggedOut
            withTimeout(5000) { f.repository.snapshot.first { it.userId == null } }
            f.repository.onForeground().join()
            assertEquals(1, f.reads.get())
        }
    }

    @Test
    fun responseFromThePreviousAccountCannotPopulateTheNewSnapshot() = runBlocking {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        Fixture(read = { uid ->
            if (uid == 1L) {
                started.countDown()
                check(finish.await(5, TimeUnit.SECONDS))
            }
            Result.success(if (uid == 1L) setOf(123L) else setOf(456L))
        }).use { f ->
            try {
                f.login(1)
                assertTrue(started.await(5, TimeUnit.SECONDS))
                f.login(2)
                assertNull(f.repository.isLiked(123, 2))
                finish.countDown()
                f.loaded(2)
                assertEquals(setOf(456L), f.repository.snapshot.value.likedIds)
                assertEquals(2, f.reads.get())
            } finally {
                finish.countDown()
            }
        }
    }

    @Test
    fun aCancelledViewCannotLetForegroundRefreshOvertakeItsJniWrite() = runBlocking {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val secondRead = CompletableDeferred<Unit>()
        val actual = AtomicBoolean(false)
        var attempt = 0
        Fixture(
            read = {
                attempt++
                if (attempt == 2) secondRead.complete(Unit)
                Result.success(if (actual.get()) setOf(123L) else emptySet())
            },
            write = { _, _, next ->
                started.countDown()
                check(finish.await(5, TimeUnit.SECONDS))
                actual.set(next)
                Result.success(Unit)
            },
        ).use { f ->
            f.login(1)
            f.loaded(1)
            val writer = launch(Dispatchers.Default) { f.repository.setLiked(123, 1, true) }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                writer.cancel()
                f.time.set(5 * 60 * 1000L)
                val foreground = f.repository.onForeground()
                assertNull(withTimeoutOrNull(100) { secondRead.await() })
                finish.countDown()
                withTimeout(5000) { writer.join(); foreground.join(); secondRead.await() }
                f.loaded(1) { it.contains(123L) }
                assertEquals(true, f.repository.isLiked(123, 1))
            } finally {
                finish.countDown()
                writer.cancel()
            }
        }
    }

    @Test
    fun staleAccountWritesNeverReachJni() = runBlocking {
        val writes = AtomicInteger()
        Fixture(write = { _, _, _ -> writes.incrementAndGet(); Result.success(Unit) }).use { f ->
            f.login(1)
            f.loaded(1)
            f.session.value = Session.LoggedOut
            assertTrue(f.repository.setLiked(123, 1, true).isFailure)
            assertEquals(0, writes.get())
        }
    }

    private class Fixture(
        read: (Long) -> Result<Set<Long>> = { Result.success(emptySet()) },
        write: (Long, Long, Boolean) -> Result<Unit> = { _, _, _ -> Result.success(Unit) },
        val time: AtomicLong = AtomicLong(),
        wait: suspend (Long) -> Unit = { delay(it) },
    ) : Closeable {
        private val job = SupervisorJob()
        val session = MutableStateFlow<Session>(Session.Unknown)
        val reads = AtomicInteger()
        val repository = SongLikeRepository(
            scope = CoroutineScope(job + Dispatchers.Default),
            session = session,
            read = { reads.incrementAndGet(); read(it) },
            write = write,
            nowMs = { time.get() },
            wait = wait,
        )

        fun login(userId: Long) {
            session.value = Session.LoggedIn(UserProfile(userId = userId))
        }

        suspend fun loaded(userId: Long, predicate: (Set<Long>) -> Boolean = { true }) {
            withTimeout(5000) {
                repository.snapshot.first {
                    it.userId == userId && it.likedIds?.let(predicate) == true
                }
            }
        }

        override fun close() { job.cancel() }
    }
}
