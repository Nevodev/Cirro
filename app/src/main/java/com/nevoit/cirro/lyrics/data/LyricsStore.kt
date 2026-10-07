package com.nevoit.cirro.lyrics.data

import android.content.Context
import com.nevoit.cirro.lyrics.model.Lyrics
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 歌词快照的落盘。
 *
 * 一首歌解析完就顺手写一份 JSON；下次再放同一首直接命中，不必重走四级取词（歌词要联网抓，
 * 而一首歌的歌词基本不变）。
 *
 * 按歌曲 id 命名，超过 [MaxFiles] 份时按修改时间清掉最旧的 —— 歌词是**可再生**的缓存，
 * 丢了最多重取一次，不值得上更精细的淘汰策略。
 */
class LyricsStore(context: Context) {

    private val root = File(context.filesDir, Directory)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 读一份快照；没有或坏了都返回 null（坏文件顺手删掉）。 */
    fun read(songId: Long): Lyrics? {
        val file = File(root, fileName(songId))
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(Lyrics.serializer(), file.readText())
        }.getOrElse {
            runCatching { file.delete() }
            null
        }
    }

    fun write(songId: Long, lyrics: Lyrics) {
        runCatching {
            if (!root.exists()) root.mkdirs()
            File(root, fileName(songId)).writeText(json.encodeToString(Lyrics.serializer(), lyrics))
            prune()
        }
    }

    fun clear() {
        runCatching { root.deleteRecursively() }
    }

    private fun prune() {
        val files = root.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= MaxFiles) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MaxFiles)
            .forEach { runCatching { it.delete() } }
    }

    private fun fileName(songId: Long) = "$songId.json"

    private companion object {
        const val Directory = "lyrics"
        const val MaxFiles = 150
    }
}
