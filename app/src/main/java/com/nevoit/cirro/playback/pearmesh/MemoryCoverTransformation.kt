package com.nevoit.cirro.playback.pearmesh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.Log
import coil3.size.Size
import coil3.transform.Transformation
import com.nevoit.cirro.playback.pearmesh.gl.PearMeshCoverPreset
import com.nevoit.cirro.ui.ReflectionTransformation
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class MemoryCoverTransformation(
    context: Context,
    sourceKey: String,
    private val reflection: ReflectionTransformation? = null,
) : Transformation() {
    private val mesh = PearMeshCoverTransformation(context)
    private val squareKey = "memory-cover-v1:$sourceKey:${mesh.cacheKey}:square"
    override val cacheKey = reflection?.let {
        "memory-cover-v1:$sourceKey:${mesh.cacheKey}:${it.cacheKey}"
    } ?: squareKey
    private fun cacheFile(context: Context, key: String): AtomicFile {
        val id = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(context.filesDir, "memory-covers/$id.png"))
    }
    private val squareFile = cacheFile(context, squareKey)
    private val file = if (reflection == null) squareFile else cacheFile(context, cacheKey)
    val paletteKey: String get() = squareFile.baseFile.toURI().toString()

    // 首页先查成品，命中时连原图下载、解码和倒影变换都不需要。
    suspend fun cachedImage(): Bitmap? = generationLock.withLock { read() }
    suspend fun cachedSquare(): Bitmap? = generationLock.withLock { read(squareFile) }

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = withContext(Dispatchers.Default) {
        generationLock.withLock {
            read()?.let { return@withLock it }
            val square = read(squareFile) ?: mesh.transform(input, size).also {
                if (it !== input) write(it, squareFile)
            }
            if (reflection == null) return@withLock square
            var output: Bitmap? = null
            try {
                output = reflection.transform(square, size)
            } finally {
                if (square !== input && square !== output) square.recycle()
            }
            // GL 失败时只展示降级封面，不能把它永久存成用户选择的预设。
            if (square !== input) write(checkNotNull(output))
            checkNotNull(output)
        }
    }

    private suspend fun read(source: AtomicFile = file): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val bitmap = source.openRead().use { BitmapFactory.decodeStream(it) }
            if (bitmap != null && bitmap.width == PearMeshCoverPreset.Side && bitmap.height > 0) {
                bitmap
            } else {
                bitmap?.recycle()
                source.delete()
                null
            }
        } catch (_: FileNotFoundException) {
            null
        } catch (error: IOException) {
            Log.w("MemoryCover", "读取本地封面失败，重新生成", error)
            null
        }
    }

    private suspend fun write(bitmap: Bitmap, destination: AtomicFile = file) = withContext(Dispatchers.IO) {
        try {
            val directory = checkNotNull(destination.baseFile.parentFile)
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建回忆封面目录")
            val stream = destination.startWrite()
            try {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw IOException("无法编码回忆封面")
                }
                destination.finishWrite(stream)
            } catch (error: Exception) {
                destination.failWrite(stream)
                throw error
            }
        } catch (error: IOException) {
            Log.w("MemoryCover", "保存本地封面失败", error)
        }
    }

    private companion object {
        // AtomicFile 不提供并发保护；多个卡片请求同一封面时只允许生成和提交一次。
        val generationLock = Mutex()
    }
}
