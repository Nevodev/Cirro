package com.nevoit.cirro.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class PlaybackStore(context: Context, scope: CoroutineScope) {
    private val file = AtomicFile(File(context.filesDir, "playback/state.json"))
    private val json = Json { ignoreUnknownKeys = true }
    private val pending = Channel<PlaybackSnapshot>(Channel.CONFLATED)

    init {
        // 一个写入者串行提交完整快照；加载和进度采样不能让 AtomicFile 并发写入。
        scope.launch(Dispatchers.IO) {
            for (snapshot in pending) {
                runCatching { write(snapshot) }
                    .onFailure { Log.w("CirroPlayback", "保存播放状态失败", it) }
            }
        }
    }

    fun save(snapshot: PlaybackSnapshot) {
        pending.trySend(snapshot)
    }

    /** 在 IO 线程调用，缓存缺失、损坏或版本不兼容都不阻止启动。 */
    @Synchronized
    fun read(): PlaybackSnapshot? = try {
        val text = file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        val saved = json.decodeFromString<SavedPlayback>(text)
        if (saved.version == 1) saved.playback.validated() else null
    } catch (_: FileNotFoundException) {
        null
    } catch (error: Exception) {
        Log.w("CirroPlayback", "读取播放状态失败", error)
        null
    }

    @Synchronized
    private fun write(snapshot: PlaybackSnapshot) {
        val directory = file.baseFile.parentFile!!
        check(directory.isDirectory || directory.mkdirs()) { "无法创建播放状态目录" }
        val bytes = json.encodeToString(SavedPlayback(1, snapshot)).toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    @Serializable
    private data class SavedPlayback(val version: Int, val playback: PlaybackSnapshot)
}
