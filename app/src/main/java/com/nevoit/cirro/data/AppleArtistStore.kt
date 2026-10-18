package com.nevoit.cirro.data

import android.content.Context
import android.util.AtomicFile
import com.nevoit.cirro.data.model.AppleArtistMatch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException

class AppleArtistStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "apple-music/artists.json"))
    private val json = Json { ignoreUnknownKeys = true }

    fun read(): Map<Long, AppleArtistMatch> {
        val text = try {
            file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: FileNotFoundException) {
            return emptyMap()
        }
        val snapshot = json.decodeFromString<Snapshot>(text)
        check(snapshot.version == Version) { "艺人映射版本不兼容" }
        return snapshot.artists.filter { (id, match) ->
            id > 0 && match.appleArtistId.isNotEmpty() &&
                match.appleArtistId.all { it in '0'..'9' } &&
                match.appleArtistId.any { it != '0' } && match.storefront.isNotBlank() &&
                match.songs.map { it.neteaseSongId }.distinct().size >= 2 &&
                match.songs.map { it.appleSongId }.distinct().size >= 2
        }
    }

    fun write(artists: Map<Long, AppleArtistMatch>) {
        val directory = file.baseFile.parentFile!!
        check(directory.isDirectory || directory.mkdirs()) { "无法创建艺人映射目录" }
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(Snapshot(Version, artists)).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private companion object {
        const val Version = 1
    }

    @Serializable
    private data class Snapshot(val version: Int, val artists: Map<Long, AppleArtistMatch>)
}
