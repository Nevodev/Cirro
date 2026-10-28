package com.nevoit.cirro.playback

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import android.util.Log
import com.nevoit.cirro.data.NcmBridge
import com.nevoit.cirro.data.model.Session
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 待发事件先落盘再发送，存储和网络各自串行；服务退出不会取消应用级待发任务。 */
internal class PlaybackReportRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val session: StateFlow<Session>,
    private val prepare: (Long, String) -> Result<String> = NcmBridge::prepareScrobbleEvent,
    private val send: (Long, String) -> Result<Boolean> = NcmBridge::scrobbleEvent,
) {
    private val file = AtomicFile(File(context.filesDir, "playback/reports.json"))
    private val json = Json { ignoreUnknownKeys = true }
    private val messages = Channel<Message>(Channel.UNLIMITED)
    private val clientContext = context.packageManager.getPackageInfo(context.packageName, 0).let { info ->
        buildJsonObject {
            put("appver", info.versionName.orEmpty())
            put("buildver", info.longVersionCode.toString())
            put("userAgent", "Cirro/${info.versionName.orEmpty()} (Android ${Build.VERSION.RELEASE}; API ${Build.VERSION.SDK_INT})")
        }
    }

    init {
        scope.launch(Dispatchers.IO) {
            var saved = read()
            fun append(event: ListeningEvent) {
                for (report in reports(event)) {
                    if (saved.pending.any { it.id == report.id }) continue
                    val modern = report.payload["action"]?.jsonPrimitive?.content?.startsWith("_") == true
                    val sequenced = if (modern) report.copy(payload = JsonObject(report.payload + (
                        "context" to JsonObject(report.payload["context"]!!.jsonObject + (
                            "seq" to JsonPrimitive(saved.nextSequence)
                        ))
                    ))) else report
                    saved = saved.copy(
                        pending = saved.pending + sequenced,
                        nextSequence = if (modern) (saved.nextSequence + 1L) and 0xffff_ffffL else saved.nextSequence,
                    )
                }
            }
            saved.active?.let { active ->
                append(active.event(ListeningEnd.Interrupted, eventTimeMs = System.currentTimeMillis()))
                saved = saved.copy(active = null)
            }
            var dirty = true
            var inFlight: String? = null
            val retries = mutableMapOf<Pair<Long, Boolean>, Retry>()
            for (message in messages) {
                when (message) {
                    is Message.Event -> {
                        val event = message.value
                        append(event)
                        if (event.payload["action"]?.jsonPrimitive?.content == "play" &&
                            event.id == "${saved.active?.sessionId}:end"
                        ) saved = saved.copy(active = null)
                        dirty = true
                    }
                    is Message.Checkpoint -> {
                        saved = saved.copy(active = message.value)
                        dirty = true
                    }
                    is Message.Result -> {
                        inFlight = null
                        val key = saved.pending.firstOrNull { it.id == message.id }?.let(::retryKey)
                        if (message.accepted) {
                            saved = saved.copy(pending = saved.pending.filterNot { it.id == message.id })
                            dirty = true
                            retries.remove(key)
                            Log.d("CirroPlaybackReport", "播放事件已确认接收 id=${message.id}")
                        } else if (key != null) {
                            val failures = ((retries[key]?.failures ?: 0) + 1).coerceAtMost(8)
                            retries[key] = Retry(
                                failures,
                                android.os.SystemClock.elapsedRealtime() + minOf(300_000L, 1_000L shl failures),
                            )
                            Log.w("CirroPlaybackReport", "上报未确认接收，保留待发事件 id=${message.id} retry=$failures")
                        }
                    }
                    is Message.Prepared -> {
                        inFlight = null
                        val event = saved.pending.firstOrNull { it.id == message.id }
                        val payload = message.payload
                        if (event != null && payload != null) {
                            saved = saved.copy(pending = saved.pending.map {
                                if (it.id == event.id) it.copy(payload = payload) else it
                            })
                            dirty = true
                        } else if (event != null) {
                            val key = retryKey(event)
                            val failures = ((retries[key]?.failures ?: 0) + 1).coerceAtMost(8)
                            retries[key] = Retry(failures, android.os.SystemClock.elapsedRealtime() +
                                minOf(300_000L, 1_000L shl failures))
                            Log.w("CirroPlaybackReport", "播放日志文件准备失败，保留事件 id=${message.id}")
                        }
                    }
                    Message.Tick -> Unit
                }
                if (dirty) {
                    dirty = !runCatching { write(saved) }.isSuccess
                    if (dirty) Log.w("CirroPlaybackReport", "播放统计写盘失败，暂缓发送")
                }
                // 不能把旧账号待发记录用新账号的凭据发出去；未知或离线登录态只等待。
                val owner = (session.value as? Session.LoggedIn)?.profile?.userId?.takeIf { it > 0L }
                if (!dirty && inFlight == null) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val next = saved.pending.firstOrNull {
                        it.userId == owner && now >= (retries[retryKey(it)]?.at ?: 0L)
                    }
                    if (next != null) {
                        inFlight = next.id
                        scope.launch(Dispatchers.IO) {
                            val currentOwner = (session.value as? Session.LoggedIn)?.profile?.userId
                            if (retryKey(next).second && next.payload["_upload"] == null) {
                                val payload = if (currentOwner == next.userId) runCatching {
                                    json.parseToJsonElement(prepare(next.userId, next.payload.toString()).getOrThrow()).jsonObject
                                }.getOrNull() else null
                                messages.send(Message.Prepared(next.id, payload))
                            } else {
                                val accepted = currentOwner == next.userId &&
                                    runCatching { send(next.userId, next.payload.toString()).getOrThrow() }.getOrDefault(false)
                                messages.send(Message.Result(next.id, accepted))
                            }
                        }
                    }
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            while (true) {
                messages.send(Message.Tick)
                delay(1_000L)
            }
        }
    }

    fun enqueue(event: ListeningEvent) {
        messages.trySend(Message.Event(event))
    }

    fun checkpoint(snapshot: ListeningSnapshot?) {
        messages.trySend(Message.Checkpoint(snapshot))
    }

    private fun reports(event: ListeningEvent): List<ListeningEvent> {
        val action = when (event.payload["action"]?.jsonPrimitive?.content) {
            "startplay" -> "_plv"
            "play" -> "_pld"
            else -> return listOf(event)
        }
        // 原版同时走两条通道，分别确认，避免其中一条失败时重发已接收的另一条。
        val modern = event.copy(
            id = "${event.id}:ua",
            payload = JsonObject(event.payload + mapOf(
                "action" to JsonPrimitive(action),
                "context" to clientContext,
                "json" to JsonObject(event.payload["json"]!!.jsonObject.let { fields ->
                    fields + mapOf(
                        "pid" to JsonPrimitive(Process.myPid()),
                        "_log_thoroughfare" to JsonPrimitive("ua"),
                        "_logTime" to (fields["_logTime"] ?: JsonPrimitive(System.currentTimeMillis())),
                    )
                }),
            )),
        )
        return listOf(event, modern)
    }

    private fun retryKey(event: ListeningEvent) =
        event.userId to (event.payload["action"]?.jsonPrimitive?.content?.startsWith("_") == true)

    private data class Retry(val failures: Int, val at: Long)

    private fun read(): Saved = try {
        val text = file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        json.decodeFromString<Saved>(text).also { check(it.version == 1) }
    } catch (_: FileNotFoundException) {
        Saved()
    } catch (_: Exception) {
        Log.w("CirroPlaybackReport", "播放统计缓存无法读取")
        Saved()
    }

    private fun write(saved: Saved) {
        val directory = requireNotNull(file.baseFile.parentFile)
        check(directory.isDirectory || directory.mkdirs())
        val bytes = json.encodeToString(saved).toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    @Serializable
    private data class Saved(
        val version: Int = 1,
        val nextSequence: Long = 0L,
        val pending: List<ListeningEvent> = emptyList(),
        val active: ListeningSnapshot? = null,
    )

    private sealed interface Message {
        data class Event(val value: ListeningEvent) : Message
        data class Checkpoint(val value: ListeningSnapshot?) : Message
        data class Result(val id: String, val accepted: Boolean) : Message
        data class Prepared(val id: String, val payload: JsonObject?) : Message
        data object Tick : Message
    }
}
