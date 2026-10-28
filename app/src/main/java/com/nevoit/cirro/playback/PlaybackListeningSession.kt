package com.nevoit.cirro.playback

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
internal data class ListeningTrack(
    val uid: Long,
    val songId: Long,
    val userId: Long,
    val source: String,
    val sourceId: String,
    val durationMs: Long,
    val mode: String,
)

@Serializable
internal data class ListeningSnapshot(
    val sessionId: String,
    val track: ListeningTrack,
    val startedAtMs: Long,
    val timeMs: Double = 0.0,
    val realTimeMs: Long = 0L,
    val progressMs: Long = 0L,
    val speed: Float = 1f,
) {
    fun event(end: ListeningEnd? = null, errorCode: Int = -1, eventTimeMs: Long = startedAtMs): ListeningEvent {
        val fields = buildJsonObject {
            put("_logTime", eventTimeMs)
            put("id", track.songId)
            put("type", "song")
            put("source", track.source)
            put("sourceId", track.sourceId)
            put("mode", track.mode)
            put("startlogtime", startedAtMs / 1_000L)
            put("resource_time", track.durationMs.coerceAtLeast(0L) / 1_000L)
            put("time", timeMs.toLong() / 1_000L)
            put("realtime", realTimeMs / 1_000L)
            put("progressTime", progressMs / 1_000L)
            put("play_speed", speed)
            if (end != null) {
                put("end", end.wire)
                put("errorcode", errorCode)
            }
        }
        return ListeningEvent(
            id = "$sessionId:${if (end == null) "start" else "end"}",
            userId = track.userId,
            payload = buildJsonObject {
                put("action", if (end == null) "startplay" else "play")
                put("json", fields)
            },
        )
    }
}

internal enum class ListeningEnd(val wire: String) {
    Natural("playend"), User("ui"), Interrupted("interrupt"), Error("exception"),
}

@Serializable
internal data class ListeningEvent(val id: String, val userId: Long, val payload: JsonObject)

internal data class ListeningStream(val uid: Long, val sequence: Long)

/** 输出线程按成功提交的音频计量；主线程的切曲通知可能晚于下一首的预写。 */
internal class PlaybackListeningSession(
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val emit: (ListeningEvent) -> Unit,
    private val checkpoint: (ListeningSnapshot?) -> Unit,
) {
    private var current: ListeningSnapshot? = null
    private var stream: ListeningStream? = null
    private var audibleUid: Long? = null
    private var audibleStream: ListeningStream? = null
    private var finishedStream: ListeningStream? = null
    private var mayRestart = false
    private var outputEnd = ListeningEnd.Natural
    private var tracks: Map<Long, ListeningTrack> = emptyMap()
    private var realTimeUs = 0.0

    @Synchronized
    fun setTracks(value: List<ListeningTrack>) {
        tracks = value.associateBy { it.uid }
        val previous = current
        if (previous != null && tracks[previous.track.uid]?.userId != previous.track.userId) {
            finish(ListeningEnd.Interrupted)
            // 同一输出流换了账号；下一次有效写入属于新账号的一次播放。
            mayRestart = true
        }
        current = current?.let { snapshot ->
            tracks[snapshot.track.uid]?.takeIf { it.userId == snapshot.track.userId }
                ?.let { snapshot.copy(track = it) } ?: snapshot
        }
    }

    @Synchronized
    fun observePosition(uid: Long?, positionMs: Long) {
        current = current?.takeIf { it.track.uid == uid }
            ?.let { it.copy(progressMs = maxOf(it.progressMs, positionMs.coerceAtLeast(0L))) } ?: current
    }

    @Synchronized
    fun transition(nextUid: Long?, end: ListeningEnd) {
        if (stream == audibleStream) finish(end)
        audibleUid = nextUid
        audibleStream = stream?.takeIf { it.uid == nextUid }
    }

    @Synchronized
    fun update(uid: Long?, positionMs: Long) {
        if (audibleUid != uid) transition(uid, ListeningEnd.Interrupted)
        observePosition(uid, positionMs)
        checkpoint(current)
    }

    @Synchronized
    fun flushed(value: ListeningStream?) {
        // 同曲 seek 的 flush 保留计数；结束后重新播放同一条目才允许重开同一个 sequence。
        if (value == finishedStream) mayRestart = true
        if (current != null) outputEnd = ListeningEnd.User
    }

    @Synchronized
    fun output(value: ListeningStream, timeUs: Double, realUs: Double, speed: Float) {
        if (timeUs <= 0 || realUs <= 0 || !timeUs.isFinite() || !realUs.isFinite()) return
        val track = tracks[value.uid] ?: return
        if (track.userId <= 0L) return
        if (value == finishedStream && !mayRestart) return
        val starting = current == null || stream != value || current?.track?.userId != track.userId
        if (starting) {
            finish(if (stream == value) ListeningEnd.Interrupted else outputEnd)
            stream = value
            realTimeUs = 0.0
            current = ListeningSnapshot(UUID.randomUUID().toString(), track, wallClock())
            if (audibleUid == value.uid && audibleStream == null) audibleStream = value
            emit(requireNotNull(current).event())
        }
        finishedStream = null
        mayRestart = false
        outputEnd = ListeningEnd.Natural
        realTimeUs += realUs
        current = current?.let {
            it.copy(timeMs = it.timeMs + timeUs / 1_000.0, realTimeMs = (realTimeUs / 1_000.0).toLong(), speed = speed)
        }
        if (starting) checkpoint(current)
    }

    @Synchronized
    fun finish(end: ListeningEnd, errorCode: Int = -1) {
        val snapshot = current ?: return
        current = null
        finishedStream = stream
        stream = null
        mayRestart = false
        emit(snapshot.event(end, errorCode, wallClock()))
        checkpoint(null)
    }
}
