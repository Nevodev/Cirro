package com.nevoit.cirro.data

import android.content.Context
import android.util.AtomicFile
import com.nevoit.cirro.data.model.AppleAlbumMatch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException

class AppleAlbumStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "apple-music/albums.json"))
    private val json = Json { ignoreUnknownKeys = true }

    fun read(): Map<Long, AppleAlbumMatch> {
        val text = try {
            file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: FileNotFoundException) {
            return emptyMap()
        }
        val snapshot = json.decodeFromString<Snapshot>(text)
        check(snapshot.version == 1) { "专辑映射版本不兼容" }
        return snapshot.albums.filter { (id, match) ->
            id > 0 && match.appleAlbumId.isNotEmpty() && match.appleAlbumId.all { it in '0'..'9' } &&
                match.appleAlbumId.any { it != '0' } && match.storefront.isNotBlank()
        }
    }

    fun write(albums: Map<Long, AppleAlbumMatch>) {
        val directory = file.baseFile.parentFile!!
        check(directory.isDirectory || directory.mkdirs()) { "无法创建专辑映射目录" }
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(Snapshot(1, albums)).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    @Serializable
    private data class Snapshot(val version: Int, val albums: Map<Long, AppleAlbumMatch>)
}
