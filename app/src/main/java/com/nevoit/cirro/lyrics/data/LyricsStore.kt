package com.nevoit.cirro.lyrics.data

import android.content.Context
import com.nevoit.cirro.lyrics.model.Lyrics
import kotlinx.serialization.Serializable
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

    /** 读一份快照；没有、版本对不上、或者文件坏了都返回 null（顺手删掉）。 */
    fun read(songId: Long): Lyrics? {
        val file = File(root, fileName(songId))
        if (!file.isFile) return null
        return runCatching {
            val snapshot = json.decodeFromString(Snapshot.serializer(), file.readText())
            check(snapshot.version == Version) { "歌词快照版本过期" }
            snapshot.lyrics
        }.getOrElse {
            runCatching { file.delete() }
            null
        }
    }

    fun write(songId: Long, lyrics: Lyrics) {
        runCatching {
            if (!root.exists()) root.mkdirs()
            val snapshot = Snapshot(version = Version, lyrics = lyrics)
            File(root, fileName(songId))
                .writeText(json.encodeToString(Snapshot.serializer(), snapshot))
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

        /**
         * 快照格式版本。
         *
         * 🔴 **`Lyrics` 里任何会落盘的字段有变动就 +1。** 不加的话老快照照样能解出来 ——
         * `ignoreUnknownKeys` 管的是「多出来的键」，缺的键会**静默走默认值**，于是新字段在
         * 已经播过的歌上永远不生效，还要等缓存自然淘汰（150 份，靠 mtime 挤）。
         *
         * 这次取 1 是因为加这层壳之前落的是**裸的 `Lyrics`**：它缺 `version`，解不出
         * [Snapshot]，正好落进 [read] 的兜底里被删掉 —— 顺手把「解析器开始裁掉歌词两端的
         * 空白」这个改动兑现到已经缓存过的歌上，不必让用户去清数据。
         */
        const val Version = 1
    }
}

/**
 * 快照文件的外壳。
 *
 * 存在的唯一理由就是那个 [version] —— 为什么它不能省，见上面 `Version` 的注释。
 * 两个字段都**故意不给默认值**：老文件是裸的 `Lyrics`，缺 `version` 就必须解码失败，
 * 一旦给了默认值就会静默解出来，版本号也就白加了。
 */
@Serializable
private data class Snapshot(val version: Int, val lyrics: Lyrics)
