package com.nevoit.cirro.playback

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.UserProfile
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReportRepositoryTest {
    private val directory = File(
        ApplicationProvider.getApplicationContext<Context>().cacheDir,
        "playback-report-test-${UUID.randomUUID()}",
    ).apply { mkdirs() }
    private val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getFilesDir(): File = directory
    }
    private val scopes = mutableListOf<CoroutineScope>()
    private val session = MutableStateFlow<Session>(Session.Unknown)
    private val file get() = File(directory, "playback/reports.json")

    @After
    fun close() {
        scopes.forEach { stop(it) }
        directory.deleteRecursively()
    }

    @Test
    fun unknownSessionWaitsAndOnlyMatchingAccountCanSendPersistedEvents() {
        val sent = CopyOnWriteArrayList<Long>()
        val repository = repository { owner, _ -> sent.add(owner); Result.success(true) }
        repository.enqueue(event("old", 7))
        repository.enqueue(event("new", 8))
        await { pending().size == 4 }
        assertTrue(sent.isEmpty())
        val sequences = pending().filter { it["id"]!!.jsonPrimitive.content.endsWith(":ua") }
            .map { it["payload"]!!.jsonObject["context"]!!.jsonObject["seq"]!!.jsonPrimitive.content.toLong() }
        assertEquals(listOf(0L, 1L), sequences)

        session.value = Session.LoggedIn(UserProfile(userId = 8))
        await { pending().size == 2 }
        assertEquals(listOf(8L, 8L), sent.toList())
        assertEquals(setOf("old", "old:ua"), pending().map { it["id"]!!.jsonPrimitive.content }.toSet())

        session.value = Session.LoggedIn(UserProfile(userId = 7))
        await { pending().isEmpty() }
        assertEquals(listOf(8L, 8L, 7L, 7L), sent.toList())
    }

    @Test
    fun failedResponseSurvivesRestartAndIsRemovedOnlyAfterConfirmedAcceptance() {
        session.value = Session.LoggedIn(UserProfile(userId = 7))
        val attempts = AtomicInteger()
        val firstScope = newScope()
        PlaybackReportRepository(context, firstScope, session, prepare = ::prepare) { _, _ ->
            attempts.incrementAndGet()
            Result.failure(IllegalStateException("offline"))
        }.enqueue(event("retry", 7))
        await { attempts.get() == 2 && pending().size == 2 }
        stop(firstScope)

        val acknowledged = CopyOnWriteArrayList<String>()
        repository { _, payload -> acknowledged.add(payload); Result.success(true) }
        await { pending().isEmpty() && acknowledged.size == 2 }
        assertEquals(2, attempts.get())
    }

    @Test
    fun channelsAcknowledgeIndependentlyAndDoNotResendAnAcceptedLegacyEvent() {
        session.value = Session.LoggedIn(UserProfile(userId = 7))
        val attempts = CopyOnWriteArrayList<String>()
        val uploads = CopyOnWriteArrayList<JsonObject>()
        val firstScope = newScope()
        PlaybackReportRepository(context, firstScope, session, prepare = ::prepare) { _, payload ->
            val action = Json.parseToJsonElement(payload).jsonObject["action"]!!.jsonPrimitive.content
            if (action == "_pld") uploads.add(Json.parseToJsonElement(payload).jsonObject["_upload"]!!.jsonObject)
            attempts.add(action)
            Result.success(action == "play")
        }.enqueue(event("separate", 7))
        await { attempts.size == 2 && pending().size == 1 }
        assertEquals("separate:ua", pending().single()["id"]!!.jsonPrimitive.content)
        stop(firstScope)

        repository { _, payload ->
            attempts.add(Json.parseToJsonElement(payload).jsonObject["action"]!!.jsonPrimitive.content)
            uploads.add(Json.parseToJsonElement(payload).jsonObject["_upload"]!!.jsonObject)
            Result.success(true)
        }
        await { pending().isEmpty() }
        assertEquals(listOf("play", "_pld", "_pld"), attempts.toList())
        assertEquals(2, uploads.size)
        assertEquals(uploads[0], uploads[1])
    }

    @Test
    fun networkWaitDoesNotBlockCheckpointAndInterruptedSessionRecoversOnce() {
        session.value = Session.LoggedIn(UserProfile(userId = 7))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstScope = newScope()
        val repository = PlaybackReportRepository(context, firstScope, session, prepare = ::prepare) { _, _ ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Result.success(false)
        }
        try {
            repository.enqueue(event("blocked", 7))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repository.checkpoint(
                ListeningSnapshot(
                    sessionId = "active",
                    track = ListeningTrack(10, 42, 7, "list", "123", 180_000, "loop"),
                    startedAtMs = 100_000,
                    timeMs = 12_000.0,
                    realTimeMs = 12_000,
                ),
            )
            await { saved()["active"]?.jsonObject?.get("sessionId")?.jsonPrimitive?.content == "active" }
        } finally {
            firstScope.cancel()
            release.countDown()
            stop(firstScope)
        }

        session.value = Session.Unknown
        repository { _, _ -> error("Unknown account must not send") }
        await { pending().any { it["id"]?.jsonPrimitive?.content == "active:end" } }
        assertEquals(1, pending().count { it["id"]?.jsonPrimitive?.content == "active:end" })
        val end = pending().single { it["id"]?.jsonPrimitive?.content == "active:end" }
        assertEquals("interrupt", end["payload"]!!.jsonObject["json"]!!.jsonObject["end"]!!.jsonPrimitive.content)
    }

    private fun repository(send: (Long, String) -> Result<Boolean>) =
        PlaybackReportRepository(context, newScope(), session, prepare = ::prepare, send = send)

    private fun prepare(owner: Long, payload: String): Result<String> = Result.success(
        JsonObject(Json.parseToJsonElement(payload).jsonObject + ("_upload" to buildJsonObject {
            put("filename", "ua_test_$owner")
            put("body", UUID.randomUUID().toString())
        })).toString()
    )

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes.add(it) }

    private fun stop(scope: CoroutineScope) = runBlocking { scope.coroutineContext.job.cancelAndJoin() }

    private fun event(id: String, owner: Long) = ListeningEvent(id, owner, buildJsonObject {
        put("action", "play")
        put("json", buildJsonObject { put("id", 42); put("type", "song"); put("time", 12) })
    })

    private fun saved(): JsonObject = Json.parseToJsonElement(file.readText()).jsonObject
    private fun pending() = saved()["pending"]?.jsonArray?.map { it.jsonObject }.orEmpty()

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            if (runCatching(predicate).getOrDefault(false)) return
            Thread.sleep(20)
        }
        assertTrue("Timed out waiting for persisted report state", predicate())
    }
}
