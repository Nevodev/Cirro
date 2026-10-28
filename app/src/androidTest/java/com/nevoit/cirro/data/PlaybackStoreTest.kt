package com.nevoit.cirro.data

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.nevoit.cirro.data.model.PlayMode
import com.nevoit.cirro.data.model.QueueSource
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.playQueueOf
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStoreTest {
    @Test
    fun diskRoundTripRestoresQueueAndProgressWithoutPlaybackHistoryAndClearSurvivesRestart() = runBlocking {
        withStore { store, context ->
            val live = PlayQueueStore(store::save)
            val queue = playQueueOf(
                listOf(Song(42, "First"), Song(42, "Duplicate"), Song(99, "Last")),
                1, QueueSource(title = "Offline queue"), mode = PlayMode.REPEAT_ALL,
            )
            live.set(queue)
            live.markPlaying(queue.entries[0])
            live.finishPlaybackHistory()
            live.updatePosition(queue.entries[1].uid, 37_000)
            awaitSaved(store) { it?.positionMs == 37_000L }

            val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val reopened = PlaybackStore(context, readerScope)
                val snapshot = withContext(Dispatchers.IO) { requireNotNull(reopened.read()) }
                val restored = PlayQueueStore()
                restored.restore(snapshot)
                assertEquals(queue, restored.queue.value)
                assertEquals(37_000L, restored.positionMs)
                assertTrue(restored.history.value.isEmpty())
                assertTrue(live.history.value.isNotEmpty())
                live.clear()
                awaitSaved(store) { it?.queue?.entries?.isEmpty() == true }
                assertTrue(requireNotNull(reopened.read()).queue.entries.isEmpty())
            } finally {
                readerScope.cancel()
            }
        }
    }

    @Test
    fun damagedAndUnsupportedCachesDoNotPreventStartup() = runBlocking {
        withStore { store, context ->
            assertNull(store.read())
            val file = File(context.filesDir, "playback/state.json")
            file.parentFile!!.mkdirs()
            file.writeText("incomplete json", Charsets.UTF_8)
            assertNull(store.read())
            file.writeText("""{"version":999,"playback":{"queue":{}}}""", Charsets.UTF_8)
            assertNull(store.read())
        }
    }

    private suspend fun awaitSaved(store: PlaybackStore, predicate: (PlaybackSnapshot?) -> Boolean) {
        withTimeout(5_000L) {
            while (!withContext(Dispatchers.IO) { predicate(store.read()) }) delay(20L)
        }
    }

    private suspend fun withStore(check: suspend (PlaybackStore, ContextWrapper) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "playback-store-test-${System.nanoTime()}")
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
        }
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        try {
            check(PlaybackStore(context, scope), context)
        } finally {
            job.cancel()
            job.join()
            directory.deleteRecursively()
        }
    }
}
