package com.nevoit.cirro.playback

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackListeningSessionTest {
    private var now = 100_000L
    private val events = mutableListOf<ListeningEvent>()
    private var snapshot: ListeningSnapshot? = null
    private val track = ListeningTrack(10, 42, 7, "list", "123", 180_000, "loop")
    private val first = ListeningStream(10, 0)
    private val listening = PlaybackListeningSession({ now }, events::add) { snapshot = it }

    @Test
    fun restoredPositionAndWaitingDoNotCountAndSeekKeepsTheOutputTotal() {
        listening.setTracks(listOf(track))
        listening.update(track.uid, 150_000)
        now += 30_000
        assertTrue(events.isEmpty())
        listening.output(first, 20_000_000.0, 20_000_000.0, 1f)
        listening.update(track.uid, 170_000)
        now += 60_000
        listening.flushed(first)
        listening.update(track.uid, 10_000)
        listening.output(first, 5_000_000.0, 5_000_000.0, 1f)
        listening.finish(ListeningEnd.User)
        assertEquals(2, events.size)
        assertEquals("25", field(events.last(), "time"))
        assertEquals("25", field(events.last(), "realtime"))
        assertEquals("170", field(events.last(), "progressTime"))
        assertEquals("ui", field(events.last(), "end"))
        assertEquals("190000", field(events.last(), "_logTime"))
    }

    @Test
    fun onlySuccessfullyWrittenAudioAddsTimeAndSpeedBelongsToEachWrite() {
        listening.setTracks(listOf(track))
        listening.update(track.uid, 0)
        listening.output(first, 10_000_000.0, 10_000_000.0, 1f)
        listening.output(first, 20_000_000.0, 10_000_000.0, 2f)
        now += 100_000
        listening.finish(ListeningEnd.Natural)
        assertEquals("30", field(events.last(), "time"))
        assertEquals("20", field(events.last(), "realtime"))
    }

    @Test
    fun nextStreamCanBeWrittenBeforeTheMainTransitionWithoutBeingSettledByIt() {
        val next = track.copy(uid = 11, sourceId = "456")
        listening.setTracks(listOf(track, next))
        listening.update(track.uid, 0)
        listening.output(first, 12_000_000.0, 12_000_000.0, 1f)
        listening.output(ListeningStream(11, 1), 3_000_000.0, 3_000_000.0, 1f)
        listening.transition(next.uid, ListeningEnd.Natural)
        listening.update(next.uid, 0)
        assertEquals(3, events.size)
        assertEquals(3_000L, requireNotNull(snapshot).realTimeMs)
        listening.finish(ListeningEnd.Natural)
        assertEquals("123", field(events[1], "sourceId"))
        assertEquals("12", field(events[1], "time"))
        assertEquals("456", field(events[3], "sourceId"))
        assertEquals("3", field(events[3], "time"))
    }

    @Test
    fun repeatWithTheSameQueueUidUsesTheNewOutputSequence() {
        listening.setTracks(listOf(track))
        listening.update(track.uid, 0)
        listening.output(first, 2_000_000.0, 2_000_000.0, 1f)
        listening.output(first.copy(sequence = 1), 1_000_000.0, 1_000_000.0, 1f)
        listening.transition(track.uid, ListeningEnd.Natural)
        assertEquals(3, events.size)
        listening.finish(ListeningEnd.Natural)
        assertEquals("2", field(events[1], "time"))
        assertEquals("1", field(events[3], "time"))
        assertEquals(4, events.map { it.id }.toSet().size)
    }

    @Test
    fun endedStreamIgnoresLateWritesButCanBePlayedAgainAfterFlush() {
        listening.setTracks(listOf(track))
        listening.update(track.uid, 0)
        listening.output(first, 500_000.0, 500_000.0, 1f)
        listening.finish(ListeningEnd.Error, 2001)
        listening.output(first, 1_000_000.0, 1_000_000.0, 1f)
        assertEquals(2, events.size)
        assertEquals("0", field(events.last(), "time"))
        assertEquals("2001", field(events.last(), "errorcode"))
        listening.flushed(first)
        listening.output(first, 1_000_000.0, 1_000_000.0, 1f)
        listening.finish(ListeningEnd.Natural)
        assertEquals(4, events.size)
        assertEquals("1", field(events.last(), "time"))
    }

    @Test
    fun accountSwitchDoesNotMixTimeAndUnknownAccountsDoNotProduceReports() {
        listening.setTracks(listOf(track.copy(userId = 0)))
        listening.output(first, 5_000_000.0, 5_000_000.0, 1f)
        assertTrue(events.isEmpty())
        listening.setTracks(listOf(track))
        listening.output(first, 2_000_000.0, 2_000_000.0, 1f)
        listening.setTracks(listOf(track.copy(userId = 8)))
        listening.output(first, 1_000_000.0, 1_000_000.0, 1f)
        listening.update(track.uid, 0)
        assertEquals(7L, events[1].userId)
        assertEquals("2", field(events[1], "time"))
        assertEquals(8L, events[2].userId)
        assertEquals(1_000L, requireNotNull(snapshot).realTimeMs)
    }

    @Test
    fun losingTheAccountWhilePausedSettlesImmediatelyAndNextOwnerStartsOnWrite() {
        listening.setTracks(listOf(track))
        listening.output(first, 2_000_000.0, 2_000_000.0, 1f)
        listening.setTracks(listOf(track.copy(userId = 0)))
        assertEquals(2, events.size)
        assertEquals("interrupt", field(events.last(), "end"))
        assertEquals(7L, events.last().userId)
        assertEquals(null, snapshot)
        listening.output(first, 3_000_000.0, 3_000_000.0, 1f)
        assertEquals(2, events.size)
        listening.setTracks(listOf(track.copy(userId = 8)))
        assertEquals(2, events.size)
        listening.output(first, 1_000_000.0, 1_000_000.0, 1f)
        listening.update(track.uid, 0)
        assertEquals(3, events.size)
        assertEquals(8L, events.last().userId)
        assertEquals(1_000L, requireNotNull(snapshot).realTimeMs)
    }

    private fun field(event: ListeningEvent, name: String) = event.payload["json"]!!.jsonObject[name]!!.jsonPrimitive.content
}
